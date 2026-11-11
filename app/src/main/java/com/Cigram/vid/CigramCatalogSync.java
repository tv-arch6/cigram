package com.Cigram.vid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * The ONE place that decides how the saved catalogue is brought up to date (18.4). Pure Java, unit-tested on the JVM with a fake
 * transport; the Android glue (CigramStage1InitProvider) only supplies the HTTP calls and the existing prepare/index pipeline.
 *
 * One run = {@link #syncOnce}: manifest → (nothing to do | delta pages | full download) → verify → atomic commit. Nothing here runs on
 * the UI thread and nothing here ever blocks the screen: the screens read the saved snapshot, this only replaces it.
 *
 * Data-safety rules (each one has a test):
 *  1. A failed request, a timeout, a 4xx/5xx, an unparsable or EMPTY answer never touches the saved catalogue.
 *  2. A catalogue with no records never replaces one that has records. (Before 18.4 {"movies":[]} passed validation and did.)
 *  3. A catalogue that lost more than half of the saved records is HELD, not applied: it is applied only when the very same
 *     answer is seen again after {@link Config#shrinkConfirmMs} (a real bulk delete by the admin, not a glitch).
 *  4. A delta is only committed when the merged catalogue has exactly the server's record count and id digest; otherwise the full
 *     download is used. A delta is never applied "mostly".
 *  5. Interrupted multi-page syncs resume from the last page that was received (state is persisted after every page).
 *  6. Applying a delta is idempotent, so recording a revision older than the content (a change landed while downloading) is safe.
 *  7. Removal of a record happens only through an explicit tombstone from the server or a verified full snapshot.
 *  8. Every commit is atomic (see {@link CigramCatalogStore}); the previous valid generation stays on disk.
 */
final class CigramCatalogSync {

    private CigramCatalogSync() { }

    // property names inside the store (the legacy SharedPreferences keys keep their names so a migration is a plain copy)
    static final String S_ETAG = "catalog_etag_v1";
    static final String S_RAW_HASH = "catalog_raw_hash_v1";
    static final String S_DISPLAY_HASH = "json_hash_v2";
    static final String S_MANIFEST_ETAG = "manifest_etag";
    static final String S_IDS_DIGEST = "ids_digest";
    static final String L_UPDATED = "updated_at";
    static final String L_SEARCH_UPDATED = "search_index_updated_at";
    static final String L_REV = "rev";
    static final String L_RECORDS = "records";
    static final String L_LAST_FULL = "last_full_at";
    /** 1 = the last delta left a structural finding (see CigramCatalogDoctor): the next run on an unmetered network verifies with a full download. */
    static final String L_AUDIT_NOW = "audit_now";
    static final String S_DOCTOR = "doctor";
    /** A structural finding schedules at most one verifying full download per this period. */
    static final long AUDIT_MIN_GAP_MS = 6L * 3600L * 1000L;

    static final String PATH_MANIFEST = "/catalog/manifest";
    static final String PATH_CHANGES = "/catalog/changes";

    interface Transport {
        /** GET {base}{path} with an optional If-None-Match. IOException = no usable connection (timeout, reset, offline). */
        Reply get(String path, String etag) throws IOException;
        /** The legacy full catalogue (/movies), body already BOM-stripped and text-normalised by the implementation. */
        Reply getFull(String etag) throws IOException;
    }

    interface Pipeline {
        /** Text repair of freshly downloaded records (idempotent); takes and returns a catalogue document. */
        String normalizeRecords(String docJson);
        /** Section ordering + search index of a complete catalogue document. */
        Prepared prepare(String docJson);
    }

    static final class Prepared {
        final String display;
        final String index;
        Prepared(String display, String index) { this.display = display; this.index = index; }
    }

    static final class Reply {
        final int status;
        final String etag;
        final String body;
        Reply(int status, String etag, String body) {
            this.status = status; this.etag = etag == null ? "" : etag; this.body = body == null ? "" : body;
        }
    }

    static final class Config {
        long now = System.currentTimeMillis();
        boolean metered;
        boolean forceFull;
        int pageSize = 300;
        int maxPages = 40;
        long shrinkConfirmMs = 5L * 60L * 1000L;
        long auditMs = 7L * 24L * 3600L * 1000L;
    }

    enum Kind { UP_TO_DATE, UPDATED, HELD, REJECTED, FAILED, BUSY }

    static final class Outcome {
        Kind kind;
        /** "delta", "full", "manifest_304", "same_body", ... for logs and tests. */
        String mode = "";
        String reason = "";
        boolean retryable;
        boolean permanent;
        int status;
        int upserts;
        int removed;
        int requests;
        boolean contentChanged;
        String displayHash = "";
        CigramCatalogStore.Snapshot snapshot;

        static Outcome of(Kind k, String mode) { Outcome o = new Outcome(); o.kind = k; o.mode = mode; return o; }
        @Override public String toString() { return kind + "/" + mode + (reason.length() > 0 ? "(" + reason + ")" : ""); }
    }

    // ----------------------------------------------------------------------------------------------------------- entry point

    static Outcome syncOnce(CigramCatalogStore store, Transport net, Pipeline pipe, Config cfg) {
        Outcome out = new Outcome();
        try {
            return run(store, net, pipe, cfg, out);
        } catch (Throwable t) {
            // anything unexpected: the saved catalogue is untouched (every commit is atomic and happens last)
            Outcome f = Outcome.of(Kind.FAILED, "exception");
            f.reason = t.getClass().getSimpleName();
            f.retryable = true;
            f.requests = out.requests;
            return f;
        }
    }

    private static Outcome run(CigramCatalogStore store, Transport net, Pipeline pipe, Config cfg, Outcome acc) throws Exception {
        CigramCatalogStore.Snapshot local = store.load();
        long localRev = local == null ? 0L : local.num(L_REV);
        // 1. the manifest: a few hundred bytes, answered 304 while nothing changed
        Reply m;
        try {
            acc.requests++;
            m = net.get(PATH_MANIFEST, local == null ? "" : local.str(S_MANIFEST_ETAG));
        } catch (IOException e) {
            return failure(acc, "manifest_io", 0, true);
        }
        if (m.status == 304 && local != null) {
            if (!cfg.forceFull && !auditDue(local, cfg)) {
                confirm(store, local, cfg.now);
                Outcome o = Outcome.of(Kind.UP_TO_DATE, "manifest_304");
                o.requests = acc.requests;
                return o;
            }
            try {                                    // forced / self-check: we need the manifest's body this time
                acc.requests++;
                m = net.get(PATH_MANIFEST, "");
            } catch (IOException e) {
                return failure(acc, "manifest_io", 0, true);
            }
        }
        Manifest man = null;
        if (m.status == 200) man = Manifest.parse(m.body, m.etag);
        if (m.status == 404 || m.status == 405 || m.status == 500 || m.status == 501 || (m.status == 200 && man == null)) {
            // the Worker has no (working) feed: the full download of /movies is the only way, exactly as before 18.4
            return full(store, net, pipe, cfg, acc, local, null, "no_feed");
        }
        if (m.status != 200) {
            return failure(acc, "manifest_http", m.status, m.status >= 500 || m.status == 429 || m.status == 408 || m.status == 0);
        }
        if (man != null && man.busy) { Outcome b = Outcome.of(Kind.BUSY, "busy"); b.retryable = true; b.requests = acc.requests; return b; }

        // 2. already current?
        if (man != null && local != null && localRev == man.rev && man.idsDigest.equals(local.str(S_IDS_DIGEST)) && man.records == local.num(L_RECORDS)
                && !cfg.forceFull && !auditDue(local, cfg)) {
            Map<String, String> s = new HashMap<String, String>(local.strings);
            s.put(S_MANIFEST_ETAG, man.httpEtag);
            Map<String, Long> l = new HashMap<String, Long>(local.longs);
            l.put(L_UPDATED, cfg.now); l.put(L_SEARCH_UPDATED, cfg.now);
            store.updateProps(s, l);
            Outcome o = Outcome.of(Kind.UP_TO_DATE, "rev_equal");
            o.requests = acc.requests;
            return o;
        }
        if (man == null) return failure(acc, "manifest_missing", 0, true);

        // 3. how to catch up
        boolean canDelta = local != null && !cfg.forceFull && !auditDue(local, cfg) && localRev > 0 && localRev >= man.windowFrom && localRev <= man.rev;
        if (!canDelta) return full(store, net, pipe, cfg, acc, local, man, cfg.forceFull ? "forced" : local == null ? "first_run" : auditDue(local, cfg) ? "audit" : "out_of_window");
        return delta(store, net, pipe, cfg, acc, local, man);
    }

    private static boolean auditDue(CigramCatalogStore.Snapshot local, Config cfg) {
        if (cfg.metered) return false;                                   // never spend mobile data on a self-check
        if (local.num(L_AUDIT_NOW) == 1L) return true;                   // the doctor found something a full snapshot could repair
        long last = local.num(L_LAST_FULL);
        return last > 0L && cfg.now - last > cfg.auditMs;
    }

    // ----------------------------------------------------------------------------------------------------------- delta path

    private static Outcome delta(CigramCatalogStore store, Transport net, Pipeline pipe, Config cfg, Outcome acc,
                                 CigramCatalogStore.Snapshot local, Manifest man) throws Exception {
        long baseRev = local.num(L_REV);
        LinkedHashMap<String, JSONObject> up = new LinkedHashMap<String, JSONObject>();
        LinkedHashSet<String> rm = new LinkedHashSet<String>();
        long since = baseRev;
        Pending resumed = Pending.read(store);
        if (resumed != null && resumed.baseRev == baseRev && resumed.to >= baseRev && resumed.to <= man.rev) {
            up.putAll(resumed.up);
            rm.addAll(resumed.rm);
            since = resumed.to;
        } else if (resumed != null) {
            Pending.clear(store);
        }
        JSONObject head = null;
        for (int page = 0; page < cfg.maxPages; page++) {
            Reply r;
            try {
                acc.requests++;
                r = net.get(PATH_CHANGES + "?since=" + since + "&max=" + cfg.pageSize, null);
            } catch (IOException e) {
                new Pending(baseRev, since, up, rm).write(store);       // resume from here next time
                return failure(acc, "changes_io", 0, true);
            }
            if (r.status != 200) {
                new Pending(baseRev, since, up, rm).write(store);
                if (r.status == 404) { Pending.clear(store); return full(store, net, pipe, cfg, acc, local, man, "changes_404"); }
                return failure(acc, "changes_http", r.status, r.status >= 500 || r.status == 429 || r.status == 408);
            }
            JSONObject body;
            try { body = new JSONObject(r.body); } catch (Throwable e) { return failure(acc, "changes_bad_json", 200, true); }
            if (!body.optBoolean("ok", false)) return failure(acc, "changes_not_ok", 200, true);
            if (body.optBoolean("busy", false)) { new Pending(baseRev, since, up, rm).write(store); Outcome b = Outcome.of(Kind.BUSY, "busy"); b.retryable = true; b.requests = acc.requests; return b; }
            if (body.optBoolean("full", false)) { Pending.clear(store); return full(store, net, pipe, cfg, acc, local, man, "server_full:" + body.optString("reason", "")); }
            long from = body.optLong("from", -1L), to = body.optLong("to", -1L);
            if (from != since || to < since) { Pending.clear(store); return full(store, net, pipe, cfg, acc, local, man, "page_out_of_step"); }
            JSONArray ups = body.optJSONArray("upserts"), rms = body.optJSONArray("removed");
            if (ups != null) for (int i = 0; i < ups.length(); i++) {
                JSONObject rec = ups.optJSONObject(i);
                String id = CigramCatalogDelta.idOf(rec);
                if (id.length() == 0) continue;
                up.put(id, rec);
                rm.remove(id);
            }
            if (rms != null) for (int i = 0; i < rms.length(); i++) {
                String id = String.valueOf(rms.opt(i)).trim();
                if (id.length() == 0) continue;
                rm.add(id);
                up.remove(id);
            }
            since = to;
            head = body.optJSONObject("head");
            new Pending(baseRev, since, up, rm).write(store);
            if (!body.optBoolean("more", false)) break;
            if (page == cfg.maxPages - 1) return failure(acc, "too_many_pages", 200, true);   // pending stays: the next run continues
        }
        if (head == null) return failure(acc, "no_head", 200, true);

        // apply to the saved catalogue, verify against the server's head, only then commit
        JSONArray upArr = new JSONArray();
        for (JSONObject o : up.values()) upArr.put(o);
        JSONArray rmArr = new JSONArray();
        for (String id : rm) rmArr.put(id);
        if (upArr.length() > 0) {
            String normalised = pipe.normalizeRecords(wrapRecords(upArr));
            upArr = CigramCatalogDelta.open(normalised).list;
        }
        CigramCatalogDelta.Merge merged;
        try {
            merged = CigramCatalogDelta.apply(local.display, upArr, rmArr);
        } catch (Throwable e) {
            Pending.clear(store);
            return full(store, net, pipe, cfg, acc, local, man, "cannot_apply");
        }
        if (merged.records != head.optInt("records", -1) || !merged.idsDigest.equals(head.optString("ids_digest", ""))) {
            Pending.clear(store);
            return full(store, net, pipe, cfg, acc, local, man, "verify_failed");
        }
        long headRev = head.optLong("rev", since);
        int localRecords = (int) local.num(L_RECORDS);
        Verdict v = judge(store, localRecords, merged.records, "delta:" + headRev, cfg, merged.removed);
        if (v.kind != Verdict.ACCEPT) { Outcome o = verdictOutcome(v, "delta"); o.requests = acc.requests; return o; }

        Prepared prepared = pipe.prepare(merged.json);
        Map<String, String> s = new HashMap<String, String>(local.strings);
        CigramCatalogDoctor.Report rep = CigramCatalogDoctor.diagnose(merged.json);
        if (rep != null) s.put(S_DOCTOR, rep.toJson());
        s.put(S_IDS_DIGEST, merged.idsDigest);
        s.put(S_MANIFEST_ETAG, man.httpEtag);
        s.put(S_DISPLAY_HASH, CigramCatalogStore.sha256(prepared.display.getBytes(CigramCatalogStore.UTF8)));
        Map<String, Long> l = new HashMap<String, Long>(local.longs);
        l.put(L_REV, headRev);
        l.put(L_RECORDS, (long) merged.records);
        l.put(L_UPDATED, cfg.now);
        l.put(L_SEARCH_UPDATED, cfg.now);
        // structural finding (orphan episode, duplicate id ...): ask the server's full snapshot to confirm, at most every 6 h
        l.put(L_AUDIT_NOW, rep != null && rep.structural() && cfg.now - local.num(L_LAST_FULL) > AUDIT_MIN_GAP_MS ? 1L : 0L);
        CigramCatalogStore.Result res = store.commit(prepared.display, prepared.index, s, l);
        if (!res.ok) return failure(acc, "commit_" + res.reason, 0, true);
        Pending.clear(store);
        clearHold(store);
        Outcome o = Outcome.of(Kind.UPDATED, "delta");
        o.upserts = upArr.length();
        o.removed = merged.removed;
        o.requests = acc.requests;
        o.displayHash = s.get(S_DISPLAY_HASH);
        o.contentChanged = !o.displayHash.equals(local.str(S_DISPLAY_HASH));
        o.snapshot = store.load();
        return o;
    }

    private static String wrapRecords(JSONArray records) throws Exception {
        JSONObject doc = new JSONObject();
        doc.put("ok", true);
        doc.put("movies", records);
        return doc.toString();
    }

    // ----------------------------------------------------------------------------------------------------------- full path

    private static Outcome full(CigramCatalogStore store, Transport net, Pipeline pipe, Config cfg, Outcome acc,
                                CigramCatalogStore.Snapshot local, Manifest man, String why) throws Exception {
        Reply f;
        try {
            acc.requests++;
            // a forced refresh and the weekly self-check must really compare content: no If-None-Match for them
            f = net.getFull(local == null || cfg.forceFull || "audit".equals(why) || "forced".equals(why) ? "" : local.str(S_ETAG));
        } catch (IOException e) {
            return failure(acc, "full_io", 0, true);
        }
        if (f.status == 304 && local != null) {
            Map<String, String> s = new HashMap<String, String>(local.strings);
            Map<String, Long> l = new HashMap<String, Long>(local.longs);
            // our validator is the server's current file: adopt the manifest's revision, the content is the same
            if (man != null && !local.str(S_ETAG).isEmpty() && local.str(S_ETAG).equals(man.fileEtag)) {
                l.put(L_REV, man.rev); l.put(L_RECORDS, (long) man.records); s.put(S_IDS_DIGEST, man.idsDigest); s.put(S_MANIFEST_ETAG, man.httpEtag);
            }
            l.put(L_AUDIT_NOW, 0L);
            l.put(L_UPDATED, cfg.now); l.put(L_SEARCH_UPDATED, cfg.now); l.put(L_LAST_FULL, cfg.now);
            store.updateProps(s, l);
            Outcome o = Outcome.of(Kind.UP_TO_DATE, "full_304");
            o.requests = acc.requests;
            return o;
        }
        if (f.status != 200) return failure(acc, "full_http", f.status, f.status >= 500 || f.status == 429 || f.status == 408);
        String body = f.body;
        Facts facts = Facts.of(body);
        int localRecords = local == null ? 0 : (int) local.num(L_RECORDS);
        if (facts == null) return reject(acc, "not_a_catalogue", why);
        Verdict v = judge(store, localRecords, facts.records, "full:" + CigramCatalogStore.sha256(body.getBytes(CigramCatalogStore.UTF8)), cfg, 0);
        if (v.kind != Verdict.ACCEPT) { Outcome o = verdictOutcome(v, "full"); o.requests = acc.requests; return o; }

        String rawHash = CigramCatalogStore.sha256(body.getBytes(CigramCatalogStore.UTF8));
        Map<String, String> s = new HashMap<String, String>(local == null ? new HashMap<String, String>() : local.strings);
        Map<String, Long> l = new HashMap<String, Long>(local == null ? new HashMap<String, Long>() : local.longs);
        if (local != null && rawHash.equals(local.str(S_RAW_HASH))) {                   // identical body: nothing to parse, index or redraw
            s.put(S_ETAG, f.etag.isEmpty() ? local.str(S_ETAG) : f.etag);
            adopt(s, l, man, facts, local.num(L_REV));
            l.put(L_AUDIT_NOW, 0L);
            l.put(L_UPDATED, cfg.now); l.put(L_SEARCH_UPDATED, cfg.now); l.put(L_LAST_FULL, cfg.now);
            store.updateProps(s, l);
            Outcome o = Outcome.of(Kind.UP_TO_DATE, "same_body");
            o.requests = acc.requests;
            return o;
        }
        Prepared prepared = pipe.prepare(body);
        String displayHash = CigramCatalogStore.sha256(prepared.display.getBytes(CigramCatalogStore.UTF8));
        s.put(S_ETAG, f.etag);
        s.put(S_RAW_HASH, rawHash);
        s.put(S_DISPLAY_HASH, displayHash);
        CigramCatalogDoctor.Report rep = CigramCatalogDoctor.diagnose(body);
        if (rep != null) s.put(S_DOCTOR, rep.toJson());
        adopt(s, l, man, facts, 0L);
        l.put(L_RECORDS, (long) facts.records);
        l.put(L_AUDIT_NOW, 0L);                                   // the server's own snapshot is the authority: the question is answered
        l.put(L_UPDATED, cfg.now); l.put(L_SEARCH_UPDATED, cfg.now); l.put(L_LAST_FULL, cfg.now);
        CigramCatalogStore.Result res = store.commit(prepared.display, prepared.index, s, l);
        if (!res.ok) return failure(acc, "commit_" + res.reason, 0, true);
        Pending.clear(store);
        clearHold(store);
        Outcome o = Outcome.of(Kind.UPDATED, "full");
        o.reason = why;
        o.requests = acc.requests;
        o.displayHash = displayHash;
        o.contentChanged = local == null || !displayHash.equals(local.str(S_DISPLAY_HASH));
        o.snapshot = store.load();
        return o;
    }

    /** Records which revision the content corresponds to. Older than the content is fine (deltas are idempotent); never newer. */
    private static void adopt(Map<String, String> s, Map<String, Long> l, Manifest man, Facts facts, long keepRev) {
        s.put(S_IDS_DIGEST, facts.idsDigest);
        l.put(L_RECORDS, (long) facts.records);
        if (man != null) { l.put(L_REV, man.rev); s.put(S_MANIFEST_ETAG, man.httpEtag); }
        else if (keepRev > 0L) l.put(L_REV, keepRev);
        else l.put(L_REV, 0L);
    }

    // ----------------------------------------------------------------------------------------------------------- judging

    static final class Verdict {
        static final int ACCEPT = 0, REJECT_EMPTY = 1, HOLD_SHRINK = 2;
        final int kind;
        final String reason;
        Verdict(int kind, String reason) { this.kind = kind; this.reason = reason; }
    }

    /**
     * Is replacing {@code local} records by {@code candidate} records acceptable? Empty never is; losing more than half (of at least 20)
     * is held until the same answer is seen again at least shrinkConfirmMs later.
     */
    static Verdict judge(CigramCatalogStore store, int local, int candidate, String key, Config cfg, int removedCount) {
        if (candidate <= 0) return new Verdict(Verdict.REJECT_EMPTY, "empty_catalogue");
        if (local >= 20 && candidate * 2 < local) {
            JSONObject hold = readHold(store);
            if (hold != null && key.equals(hold.optString("key", "")) && cfg.now - hold.optLong("at", cfg.now) >= cfg.shrinkConfirmMs) {
                return new Verdict(Verdict.ACCEPT, "confirmed_shrink");
            }
            if (hold == null || !key.equals(hold.optString("key", ""))) writeHold(store, key, cfg.now, local, candidate);
            return new Verdict(Verdict.HOLD_SHRINK, "lost_more_than_half:" + local + "->" + candidate);
        }
        return new Verdict(Verdict.ACCEPT, "");
    }

    private static Outcome verdictOutcome(Verdict v, String mode) {
        Outcome o = Outcome.of(v.kind == Verdict.REJECT_EMPTY ? Kind.REJECTED : Kind.HELD, mode);
        o.reason = v.reason;
        o.retryable = true;
        return o;
    }

    private static JSONObject readHold(CigramCatalogStore store) {
        try {
            byte[] b = store.readAux("shrink_hold.json");
            return b == null ? null : new JSONObject(new String(b, CigramCatalogStore.UTF8));
        } catch (Throwable e) {
            return null;
        }
    }

    private static void writeHold(CigramCatalogStore store, String key, long at, int local, int candidate) {
        try {
            JSONObject o = new JSONObject();
            o.put("key", key); o.put("at", at); o.put("local", local); o.put("candidate", candidate);
            store.writeAux("shrink_hold.json", o.toString().getBytes(CigramCatalogStore.UTF8));
        } catch (Throwable ignored) { }
    }

    private static void clearHold(CigramCatalogStore store) {
        store.deleteAux("shrink_hold.json");
    }

    // ----------------------------------------------------------------------------------------------------------- helpers

    private static Outcome failure(Outcome acc, String reason, int status, boolean retryable) {
        Outcome o = Outcome.of(Kind.FAILED, reason);
        o.reason = reason;
        o.status = status;
        o.retryable = retryable;
        o.permanent = !retryable && status >= 400 && status < 500;
        o.requests = acc.requests;
        return o;
    }

    private static Outcome reject(Outcome acc, String reason, String why) {
        Outcome o = Outcome.of(Kind.REJECTED, "full");
        o.reason = reason + (why.length() > 0 ? ":" + why : "");
        o.retryable = true;
        o.requests = acc.requests;
        return o;
    }

    private static void confirm(CigramCatalogStore store, CigramCatalogStore.Snapshot local, long now) {
        Map<String, Long> l = new HashMap<String, Long>(local.longs);
        l.put(L_UPDATED, now);
        l.put(L_SEARCH_UPDATED, now);
        store.updateProps(local.strings, l);
    }

    /** What /catalog/manifest says. */
    static final class Manifest {
        long rev, windowFrom;
        int records;
        String idsDigest = "", fileEtag = "", httpEtag = "";
        boolean busy;

        static Manifest parse(String body, String httpEtag) {
            try {
                JSONObject o = new JSONObject(body);
                if (!o.optBoolean("ok", false) || o.optInt("schema", 0) != 1) return null;
                Manifest m = new Manifest();
                m.busy = o.optBoolean("busy", false);
                m.rev = o.optLong("rev", -1L);
                m.records = o.optInt("records", -1);
                m.idsDigest = o.optString("ids_digest", "");
                m.fileEtag = o.optString("etag", "");
                m.windowFrom = o.optLong("window_from", Long.MAX_VALUE);
                m.httpEtag = httpEtag == null ? "" : httpEtag;
                if (m.rev < 0 || m.records < 0 || m.idsDigest.length() != 64) return m.busy ? m : null;
                return m;
            } catch (Throwable e) {
                return null;
            }
        }
    }

    /** Record count and id digest of a catalogue document (one parse). Null when it is not a catalogue. */
    static final class Facts {
        final int records;
        final String idsDigest;
        private Facts(int records, String idsDigest) { this.records = records; this.idsDigest = idsDigest; }
        static Facts of(String json) {
            try {
                java.util.List<String> ids = CigramCatalogDelta.distinctIds(CigramCatalogDelta.open(json).list);
                return new Facts(ids.size(), CigramCatalogDelta.idsDigest(ids));
            } catch (Throwable e) {
                return null;
            }
        }
    }

    /** Pages received so far by a delta sync that has not finished (so an interrupted sync resumes instead of starting over). */
    static final class Pending {
        final long baseRev;
        final long to;
        final LinkedHashMap<String, JSONObject> up;
        final LinkedHashSet<String> rm;

        Pending(long baseRev, long to, LinkedHashMap<String, JSONObject> up, LinkedHashSet<String> rm) {
            this.baseRev = baseRev; this.to = to; this.up = up; this.rm = rm;
        }

        void write(CigramCatalogStore store) {
            try {
                JSONObject o = new JSONObject();
                o.put("base_rev", baseRev);
                o.put("to", to);
                JSONArray ua = new JSONArray();
                for (JSONObject r : up.values()) ua.put(r);
                o.put("up", ua);
                JSONArray ra = new JSONArray();
                for (String id : rm) ra.put(id);
                o.put("rm", ra);
                store.writeAux("sync_pending.json", o.toString().getBytes(CigramCatalogStore.UTF8));
            } catch (Throwable ignored) { }
        }

        static Pending read(CigramCatalogStore store) {
            try {
                byte[] b = store.readAux("sync_pending.json");
                if (b == null) return null;
                JSONObject o = new JSONObject(new String(b, CigramCatalogStore.UTF8));
                LinkedHashMap<String, JSONObject> up = new LinkedHashMap<String, JSONObject>();
                JSONArray ua = o.optJSONArray("up");
                if (ua != null) for (int i = 0; i < ua.length(); i++) { JSONObject r = ua.optJSONObject(i); String id = CigramCatalogDelta.idOf(r); if (id.length() > 0) up.put(id, r); }
                LinkedHashSet<String> rm = new LinkedHashSet<String>();
                JSONArray ra = o.optJSONArray("rm");
                if (ra != null) for (int i = 0; i < ra.length(); i++) rm.add(String.valueOf(ra.opt(i)));
                return new Pending(o.optLong("base_rev", -1L), o.optLong("to", -1L), up, rm);
            } catch (Throwable e) {
                return null;
            }
        }

        static void clear(CigramCatalogStore store) {
            store.deleteAux("sync_pending.json");
        }
    }
}
