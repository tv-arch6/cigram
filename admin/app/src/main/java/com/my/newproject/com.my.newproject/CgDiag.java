package com.my.newproject;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * 19.3 — Cigram Diagnostics: one place where failures are written down, instead of a thousand silent {@code catch} blocks.
 *
 * <ul>
 *   <li><b>Structured</b>: every event has an id, time, severity, component, operation, error category, app version,
 *   Android version, correlation id, recovery attempt and recovery outcome ({@link Event}).</li>
 *   <li><b>Private by construction</b>: text is passed through {@link #redact} BEFORE it is stored — tokens, passwords,
 *   bearer values, signed-URL parameters, e-mail addresses and long opaque strings never reach the buffer or the file.
 *   Nothing is sent anywhere: the log lives in the app's private folder and leaves the phone only when the viewer
 *   exports it himself ({@link #export}).</li>
 *   <li><b>Grouped</b>: the same failure repeated 500 times is ONE row with a count (first / last time), so a retry loop is
 *   visible as a number instead of burying everything else.</li>
 *   <li><b>Bounded</b>: at most {@link Store#MAX_GROUPS} rows in memory; the file keeps at most {@link Store#MAX_BYTES} bytes
 *   and {@link Store#MAX_AGE_MS} of history; the same failure is written to disk at most once per 30 s.</li>
 *   <li><b>Cheap</b>: recording is a few string operations; disk writes go through one background thread and never run on
 *   the screen's thread. Recording can never throw into the caller.</li>
 * </ul>
 *
 * It reports; it does not decide. Nothing in the app changes behaviour because of this class.
 */
final class CgDiag {

    private CgDiag() { }

    enum Severity { INFO, WARN, ERROR }

    /** One row: a failure and how many times it happened. */
    static final class Event {
        final long id;
        final long firstAt;
        long lastAt;
        int count;
        final Severity severity;
        final String component, operation, category, message, appVersion, correlationId;
        final int androidSdk;
        int recoveryAttempt;
        String recoveryOutcome;

        Event(long id, long at, Severity severity, String component, String operation, String category, String message,
              String appVersion, int androidSdk, String correlationId, int recoveryAttempt, String recoveryOutcome) {
            this.id = id;
            this.firstAt = at;
            this.lastAt = at;
            this.count = 1;
            this.severity = severity;
            this.component = component;
            this.operation = operation;
            this.category = category;
            this.message = message;
            this.appVersion = appVersion;
            this.androidSdk = androidSdk;
            this.correlationId = correlationId;
            this.recoveryAttempt = recoveryAttempt;
            this.recoveryOutcome = recoveryOutcome;
        }

        String fingerprint() {
            return component + "|" + operation + "|" + category + "|" + shape(message);
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id).put("first", firstAt).put("last", lastAt).put("n", count).put("sev", severity.name())
                        .put("component", component).put("op", operation).put("cat", category).put("msg", message)
                        .put("app", appVersion).put("sdk", androidSdk).put("cid", correlationId)
                        .put("attempt", recoveryAttempt).put("outcome", recoveryOutcome == null ? "" : recoveryOutcome);
            } catch (org.json.JSONException ignored) { }
            return o;
        }
    }

    // ------------------------------------------------------------------ the engine (no Android types: testable)

    /** Time source, so tests can move the clock. */
    interface Clock { long now(); }

    static final class Store {
        static final int MAX_GROUPS = 300;
        static final long MAX_BYTES = 200L * 1024L;
        static final long MAX_AGE_MS = 14L * 24L * 3600L * 1000L;
        static final long WRITE_EVERY_MS = 30000L;
        static final int MAX_MESSAGE = 240;

        private final Map<String, Event> groups = new LinkedHashMap<String, Event>();
        private final Map<String, Long> lastWritten = new LinkedHashMap<String, Long>();
        private final File file;
        private final Executor io;
        private final Clock clock;
        private final String appVersion;
        private final int sdk;
        private long nextId = 1;
        private long totalRecorded;

        Store(File file, Executor io, Clock clock, String appVersion, int sdk) {
            this.file = file;
            this.io = io;
            this.clock = clock;
            this.appVersion = appVersion == null ? "" : appVersion;
            this.sdk = sdk;
            load();
        }

        synchronized Event record(Severity sev, String component, String operation, String category, String message,
                                  String correlationId, int recoveryAttempt, String recoveryOutcome) {
            long now = clock.now();
            Event fresh = new Event(nextId, now, sev == null ? Severity.INFO : sev, clean(component, 40), clean(operation, 60),
                    clean(category, 24), redact(message), appVersion, sdk, clean(correlationId, 24), recoveryAttempt, recoveryOutcome);
            totalRecorded++;
            String fp = fresh.fingerprint();
            Event known = groups.get(fp);
            Event shown;
            if (known != null) {
                known.count++;
                known.lastAt = now;
                known.recoveryAttempt = Math.max(known.recoveryAttempt, recoveryAttempt);
                if (recoveryOutcome != null && recoveryOutcome.length() > 0) known.recoveryOutcome = recoveryOutcome;
                groups.remove(fp);
                groups.put(fp, known);                 // most recent last
                shown = known;
            } else {
                nextId++;
                groups.put(fp, fresh);
                shown = fresh;
                while (groups.size() > MAX_GROUPS) {
                    String oldest = groups.keySet().iterator().next();
                    groups.remove(oldest);
                    lastWritten.remove(oldest);
                }
            }
            Long last = lastWritten.get(fp);
            if (last == null || now - last >= WRITE_EVERY_MS) {
                lastWritten.put(fp, now);
                append(shown);
            }
            return shown;
        }

        /** Newest first. */
        synchronized List<Event> snapshot() {
            ArrayList<Event> all = new ArrayList<Event>(groups.values());
            Collections.sort(all, new Comparator<Event>() {
                @Override public int compare(Event a, Event b) { return a.lastAt < b.lastAt ? 1 : (a.lastAt > b.lastAt ? -1 : 0); }
            });
            return all;
        }

        synchronized long totalRecorded() { return totalRecorded; }

        synchronized void clear() {
            groups.clear();
            lastWritten.clear();
            totalRecorded = 0;
            final File f = file;
            if (f != null) io.execute(new Runnable() { @Override public void run() { f.delete(); } });
        }

        /** The report the viewer can share: counts by category, then the rows. Already redacted. */
        synchronized String export() {
            StringBuilder sb = new StringBuilder();
            sb.append("Cigram Studio diagnostics\n");
            sb.append("app ").append(appVersion).append(" · android ").append(sdk).append('\n');
            sb.append("events recorded: ").append(totalRecorded).append(" · distinct: ").append(groups.size()).append("\n\n");
            Map<String, Integer> byCategory = new LinkedHashMap<String, Integer>();
            for (Event e : groups.values()) {
                Integer c = byCategory.get(e.category);
                byCategory.put(e.category, (c == null ? 0 : c) + e.count);
            }
            sb.append("by category:\n");
            for (Map.Entry<String, Integer> en : byCategory.entrySet()) sb.append("  ").append(en.getKey()).append(": ").append(en.getValue()).append('\n');
            sb.append("\nevents (newest first):\n");
            for (Event e : snapshot()) {
                sb.append(e.severity.name().charAt(0)).append(' ').append(e.component).append('/').append(e.operation)
                        .append(" [").append(e.category).append("] x").append(e.count)
                        .append(" last=").append(e.lastAt).append(" cid=").append(e.correlationId);
                if (e.recoveryAttempt > 0 || (e.recoveryOutcome != null && e.recoveryOutcome.length() > 0)) {
                    sb.append(" recovery#").append(e.recoveryAttempt).append(':').append(e.recoveryOutcome == null ? "" : e.recoveryOutcome);
                }
                sb.append("\n    ").append(e.message).append('\n');
            }
            return sb.toString();
        }

        // --- persistence: one JSON object per line, oldest first

        private void load() {
            if (file == null || !file.isFile()) return;
            long now = clock.now();
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    try {
                        JSONObject o = new JSONObject(line);
                        long last = o.optLong("last", 0L);
                        if (last <= 0L || now - last > MAX_AGE_MS) continue;           // retention
                        Event e = new Event(nextId++, o.optLong("first", last), parse(o.optString("sev")),
                                o.optString("component"), o.optString("op"), o.optString("cat"), o.optString("msg"),
                                o.optString("app"), o.optInt("sdk"), o.optString("cid"), o.optInt("attempt"), o.optString("outcome"));
                        e.lastAt = last;
                        e.count = Math.max(1, o.optInt("n", 1));
                        Event known = groups.get(e.fingerprint());
                        if (known != null) { known.count = Math.max(known.count, e.count); known.lastAt = Math.max(known.lastAt, last); }
                        else groups.put(e.fingerprint(), e);
                    } catch (Throwable badLine) { /* a damaged line is skipped, never fatal */ }
                }
            } catch (IOException ignored) {
            } finally {
                if (r != null) try { r.close(); } catch (IOException ignored) { }
            }
            while (groups.size() > MAX_GROUPS) groups.remove(groups.keySet().iterator().next());
        }

        private void append(final Event e) {
            if (file == null) return;
            final String line = e.toJson().toString();
            io.execute(new Runnable() {
                @Override public void run() {
                    synchronized (file) {
                        try {
                            File dir = file.getParentFile();
                            if (dir != null && !dir.isDirectory()) dir.mkdirs();
                            if (file.length() > MAX_BYTES) shrinkFile();
                            Writer w = new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8");
                            try { w.write(line); w.write('\n'); } finally { w.close(); }
                        } catch (Throwable ignored) { /* diagnostics must never hurt the app */ }
                    }
                }
            });
        }

        /** Keeps the newest half of the file. */
        private void shrinkFile() throws IOException {
            ArrayList<String> lines = new ArrayList<String>();
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            try { String l; while ((l = r.readLine()) != null) lines.add(l); } finally { r.close(); }
            int from = lines.size() / 2;
            Writer w = new OutputStreamWriter(new FileOutputStream(file, false), "UTF-8");
            try { for (int i = from; i < lines.size(); i++) { w.write(lines.get(i)); w.write('\n'); } } finally { w.close(); }
        }
    }

    private static Severity parse(String s) {
        try { return Severity.valueOf(s); } catch (Throwable t) { return Severity.INFO; }
    }

    // ------------------------------------------------------------------ privacy + classification (pure)

    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)\"(token|access_token|refresh_token|password|passwd|authorization|secret|api_?key|session)\"\\s*:\\s*\"[^\"]*\"");
    private static final Pattern PARAM_SECRET = Pattern.compile(
            "(?i)\\b(token|access_token|refresh_token|password|passwd|pwd|secret|api_?key|apikey|auth|authorization|signature|sig|session|sid|code|key|x-amz-signature|x-amz-credential)=([^&\\s\"']+)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern OPAQUE = Pattern.compile("[A-Za-z0-9_\\-]{32,}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    /** Removes what must never be stored. Idempotent; never returns null; bounded length. */
    static String redact(String text) {
        if (text == null) return "";
        String s = text;
        try {
            s = BEARER.matcher(s).replaceAll("Bearer ***");
            s = JSON_SECRET.matcher(s).replaceAll("\"$1\":\"***\"");
            s = PARAM_SECRET.matcher(s).replaceAll("$1=***");
            s = EMAIL.matcher(s).replaceAll("***@***");
            s = OPAQUE.matcher(s).replaceAll("***");
        } catch (Throwable t) {
            s = "(unprintable)";
        }
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > Store.MAX_MESSAGE ? s.substring(0, Store.MAX_MESSAGE) : s;
    }

    /** {@link #redact} for text that keeps its lines (a stack trace): each line is cleaned on its own, 300 lines at most. */
    static String redactLines(String text) {
        if (text == null) return "";
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length && i < 300; i++) {
            if (i > 0) sb.append('\n');
            sb.append(redact(lines[i]));
        }
        return sb.toString();
    }

    /** The grouping shape of a message: numbers are one number (an HTTP 503 at 12:01 and at 12:09 is one failure). */
    static String shape(String message) {
        String m = message == null ? "" : message;
        if (m.length() > 80) m = m.substring(0, 80);
        return DIGITS.matcher(m).replaceAll("#");
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        String t = redact(s);
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** Error category of an exception: stable names, never the message. */
    static String categoryOf(Throwable t) {
        for (int depth = 0; t != null && depth < 6; depth++, t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException) return "DNS";
            if (t instanceof java.net.SocketTimeoutException) return "TIMEOUT";
            if (t instanceof javax.net.ssl.SSLException) return "TLS";
            if (t instanceof java.net.ConnectException || t instanceof java.net.SocketException
                    || t instanceof java.io.InterruptedIOException || t instanceof java.io.EOFException) return "NETWORK";
            if (t instanceof org.json.JSONException) return "PARSE";
            if (t instanceof OutOfMemoryError) return "MEMORY";
            if (t instanceof SecurityException) return "PERMISSION";
            if (t instanceof IOException) {
                String m = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
                if (m.contains("no space") || m.contains("enospc")) return "STORAGE";
                return "IO";
            }
        }
        return "OTHER";
    }

    static String categoryOfHttp(int status) {
        if (status == 401 || status == 403) return "AUTH";
        if (status == 404) return "NOT_FOUND";
        if (status == 408) return "TIMEOUT";
        if (status == 429) return "RATE_LIMIT";
        if (status >= 500) return "SERVER";
        if (status >= 400) return "CLIENT";
        return "OTHER";
    }

    /** Short random id that ties the events of one operation together (an upload, a repair, a playback). */
    static String newCorrelationId() {
        return String.format(Locale.ROOT, "%08x", new Random().nextInt());
    }

    // ------------------------------------------------------------------ the app-wide instance

    private static final Object LOCK = new Object();
    private static volatile Store store;
    private static ExecutorService ioThread;

    /** Called once at process start. Safe to call again. */
    static void init(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            if (store != null && store.file != null) return;
            try {
                Context app = context.getApplicationContext();
                String version = "";
                try { version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName; } catch (Throwable ignored) { }
                if (ioThread == null) ioThread = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                    @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "cg-diag"); t.setDaemon(true); return t; }
                });
                Store old = store;
                Store s = new Store(new File(app.getFilesDir(), "cg_diag.jsonl"), ioThread, SYSTEM_CLOCK,
                        version, android.os.Build.VERSION.SDK_INT);
                if (old != null) for (Event e : old.snapshot()) s.record(e.severity, e.component, e.operation, e.category, e.message,
                        e.correlationId, e.recoveryAttempt, e.recoveryOutcome);   // what was recorded before init is kept
                store = s;
            } catch (Throwable ignored) { /* stays in-memory */ }
        }
    }

    private static final Clock SYSTEM_CLOCK = new Clock() { @Override public long now() { return System.currentTimeMillis(); } };

    private static Store store() {
        Store s = store;
        if (s == null) {
            synchronized (LOCK) {
                if (store == null) store = new Store(null, new Executor() { @Override public void execute(Runnable r) { } },
                        SYSTEM_CLOCK, "", android.os.Build.VERSION.SDK_INT);
                s = store;
            }
        }
        return s;
    }

    static void info(String component, String operation, String message) {
        record(Severity.INFO, component, operation, "INFO", message, null, 0, null);
    }

    static void warn(String component, String operation, String message, Throwable error) {
        record(Severity.WARN, component, operation, error == null ? "OTHER" : categoryOf(error), describe(message, error), null, 0, null);
    }

    static void error(String component, String operation, String message, Throwable error) {
        record(Severity.ERROR, component, operation, error == null ? "OTHER" : categoryOf(error), describe(message, error), null, 0, null);
    }

    /** A repair step: which attempt, and how it ended ("started", "ok", "failed", "gave-up"). */
    static void recovery(String component, String operation, String category, String message, String correlationId,
                         int attempt, String outcome) {
        record(outcome != null && outcome.startsWith("fail") ? Severity.WARN : Severity.INFO, component, operation,
                category, message, correlationId, attempt, outcome);
    }

    static void record(Severity sev, String component, String operation, String category, String message,
                       String correlationId, int attempt, String outcome) {
        try {
            store().record(sev, component, operation, category, message, correlationId, attempt, outcome);
        } catch (Throwable ignored) { /* recording never throws into the caller */ }
    }

    static List<Event> events() {
        try { return store().snapshot(); } catch (Throwable t) { return new ArrayList<Event>(); }
    }

    static String export() {
        try { return store().export(); } catch (Throwable t) { return "Cigram Studio diagnostics unavailable"; }
    }

    static void clear() {
        try { store().clear(); } catch (Throwable ignored) { }
    }

    private static String describe(String message, Throwable error) {
        if (error == null) return message == null ? "" : message;
        return (message == null ? "" : message + ": ") + error.getClass().getSimpleName() + (error.getMessage() == null ? "" : " " + error.getMessage());
    }
}
