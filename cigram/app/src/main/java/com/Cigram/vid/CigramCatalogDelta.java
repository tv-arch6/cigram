package com.Cigram.vid;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Pure helpers for the incremental catalogue (18.4): find the record list inside a catalogue document, fold a server delta
 * (upserts + removals by content id) into it, and compute the id-set digest the server publishes in its manifest.
 *
 * Rules that keep existing data safe:
 *  - a record is identified ONLY by its existing {@code id}; no id is ever generated or changed here;
 *  - an upsert replaces the record in place (same position); a record the document does not have yet is inserted by
 *    {@code created_at_ms}, newest first, like the Worker keeps it;
 *  - a removal removes exactly that id; ids the delta does not mention are never touched;
 *  - everything else in the document (envelope fields, other arrays) is left as it is.
 */
final class CigramCatalogDelta {

    private CigramCatalogDelta() { }

    static final String[] CONTAINER_KEYS = {"movies", "items", "data"};   // the same keys the Worker's getWritableMoviesArray accepts

    /** A parsed catalogue document and the array that holds its records. */
    static final class Doc {
        final JSONObject root;      // null when the document itself is the array
        final JSONArray list;
        Doc(JSONObject root, JSONArray list) { this.root = root; this.list = list; }
        String serialize() { return root != null ? root.toString() : list.toString(); }
    }

    static final class Merge {
        final String json;
        final int records;
        final String idsDigest;
        final int inserted;
        final int replaced;
        final int removed;
        Merge(String json, int records, String idsDigest, int inserted, int replaced, int removed) {
            this.json = json; this.records = records; this.idsDigest = idsDigest;
            this.inserted = inserted; this.replaced = replaced; this.removed = removed;
        }
    }

    /** Locates the record list; throws when the document has none (the caller then falls back to a full download). */
    static Doc open(String json) throws JSONException {
        Object v = new org.json.JSONTokener(json).nextValue();
        if (v instanceof JSONArray) return new Doc(null, (JSONArray) v);
        if (!(v instanceof JSONObject)) throw new JSONException("not a catalogue document");
        JSONObject root = (JSONObject) v;
        for (String k : CONTAINER_KEYS) {
            JSONArray a = root.optJSONArray(k);
            if (a != null) return new Doc(root, a);
        }
        throw new JSONException("no record list");
    }

    static String idOf(Object o) {
        if (!(o instanceof JSONObject)) return "";
        Object v = ((JSONObject) o).opt("id");
        if (v == null || v == JSONObject.NULL) return "";
        String s = String.valueOf(v).trim();
        return "null".equals(s) ? "" : s;
    }

    /** Distinct non-empty ids of the list. */
    static List<String> distinctIds(JSONArray list) {
        HashSet<String> seen = new HashSet<String>();
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < list.length(); i++) {
            String id = idOf(list.opt(i));
            if (id.length() > 0 && seen.add(id)) out.add(id);
        }
        return out;
    }

    /** Number of distinct ids of a catalogue document, or -1 when it has no record list (not a catalogue). */
    static int countRecords(String json) {
        try {
            return distinctIds(open(json).list).size();
        } catch (Throwable e) {
            return -1;
        }
    }

    /** SHA-256 hex of the ids sorted by UTF-16 code units and joined with "\n": the same bytes the Worker hashes. */
    static String idsDigest(Collection<String> ids) {
        ArrayList<String> sorted = new ArrayList<String>(ids);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder(sorted.size() * 14);
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(sorted.get(i));
        }
        return CigramCatalogStore.sha256(sb.toString().getBytes(CigramCatalogStore.UTF8));
    }

    /**
     * Folds a delta into {@code baseJson}. Returns the merged document plus the facts a caller verifies against the server's manifest.
     * Never throws for a bad record inside the delta: records without an id are skipped (counted nowhere), they can not be matched.
     */
    static Merge apply(String baseJson, JSONArray upserts, JSONArray removedIds) throws JSONException {
        Doc doc = open(baseJson);
        JSONArray list = doc.list;
        HashMap<String, Integer> position = new HashMap<String, Integer>();
        ArrayList<Object> items = new ArrayList<Object>(list.length() + 16);
        for (int i = 0; i < list.length(); i++) {
            Object o = list.opt(i);
            String id = idOf(o);
            if (id.length() > 0) {
                Integer prev = position.get(id);
                if (prev != null) { items.set(prev.intValue(), o); continue; }      // keep the first position, the later copy wins
                position.put(id, Integer.valueOf(items.size()));
            }
            items.add(o);
        }
        int replaced = 0, inserted = 0, removed = 0;
        HashSet<String> removeSet = new HashSet<String>();
        if (removedIds != null) for (int i = 0; i < removedIds.length(); i++) {
            String id = String.valueOf(removedIds.opt(i)).trim();
            if (id.length() > 0 && !"null".equals(id)) removeSet.add(id);
        }
        ArrayList<JSONObject> fresh = new ArrayList<JSONObject>();
        if (upserts != null) for (int i = 0; i < upserts.length(); i++) {
            JSONObject rec = upserts.optJSONObject(i);
            String id = idOf(rec);
            if (id.length() == 0) continue;
            removeSet.remove(id);                                  // an id that is both removed and upserted in one delta stays (upsert is later)
            Integer at = position.get(id);
            if (at != null) { items.set(at.intValue(), rec); replaced++; }
            else { fresh.add(rec); }
        }
        // new records: newest created_at_ms first, ahead of anything older (same as the Worker's sortNewestFirst for new items)
        Collections.sort(fresh, new java.util.Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return Long.compare(b.optLong("created_at_ms", 0L), a.optLong("created_at_ms", 0L));
            }
        });
        ArrayList<Object> merged = new ArrayList<Object>(items.size() + fresh.size());
        int fi = 0;
        for (Object o : items) {
            while (fi < fresh.size() && fresh.get(fi).optLong("created_at_ms", 0L) > createdOf(o)) { merged.add(fresh.get(fi++)); inserted++; }
            merged.add(o);
        }
        while (fi < fresh.size()) { merged.add(fresh.get(fi++)); inserted++; }
        if (!removeSet.isEmpty()) {
            ArrayList<Object> kept = new ArrayList<Object>(merged.size());
            for (Object o : merged) {
                if (removeSet.contains(idOf(o))) { removed++; continue; }
                kept.add(o);
            }
            merged = kept;
        }
        JSONArray out = new JSONArray();
        for (Object o : merged) out.put(o);
        if (doc.root != null) {
            for (String k : CONTAINER_KEYS) if (doc.root.optJSONArray(k) == list) { doc.root.put(k, out); break; }
        }
        List<String> ids = distinctIds(out);
        return new Merge(doc.root != null ? doc.root.toString() : out.toString(), ids.size(), idsDigest(ids), inserted, replaced, removed);
    }

    private static long createdOf(Object o) {
        return o instanceof JSONObject ? ((JSONObject) o).optLong("created_at_ms", 0L) : 0L;
    }

    /** id -> record, for tests and for the verification helpers. */
    static Map<String, JSONObject> byId(String json) throws JSONException {
        Doc d = open(json);
        HashMap<String, JSONObject> m = new HashMap<String, JSONObject>();
        for (int i = 0; i < d.list.length(); i++) {
            JSONObject o = d.list.optJSONObject(i);
            String id = idOf(o);
            if (id.length() > 0) m.put(id, o);
        }
        return m;
    }
}
