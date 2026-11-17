package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * v10.67: the user's profile (name, gender, photo) is saved on the device per account, so an edit
 * is never lost and every page shows the same thing.
 *
 *  - {@link #snapshot}: copies the photo and the profile fields into the account's own store.
 *  - {@link #restore}: gives them back to the session after a login / a cleared session / a stale
 *    server answer (the login code drops avatar_local_path; this puts it back).
 *  - {@link #saveLocalEdit}: an edit that could not reach the server (no network) is kept at once
 *    and sent later by {@link #syncPending}; the pages show the new name immediately.
 *
 * Nothing here runs on the main thread except the cheap preference reads of {@link #restore}.
 */
public final class CigramProfile {

    private static final String PREFS = "cigram_profile_v1";
    private static final String API = "https://cigram-auth-api.wwq-mixtv.workers.dev/profile/update";
    private static final long SYNC_GAP_MS = 60L * 1000L;

    private static long lastSyncAt;
    private static boolean syncing;
    private static long lastSnapshotAt;

    private CigramProfile() { }

    private static SharedPreferences auth(Context c) {
        return c.getApplicationContext().getSharedPreferences("auth_session", Context.MODE_PRIVATE);
    }

    private static SharedPreferences store(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean loggedIn(SharedPreferences a) {
        String t = a.getString("token", "");
        return a.getBoolean("logged_in", false) && t != null && t.trim().length() > 0;
    }

    private static String acct(Context c) {
        try { return CigramUserData.accountHash(c.getApplicationContext()); } catch (Throwable e) { return ""; }
    }

    // ------------------------------------------------------------------ restore (cheap, any thread)

    /** Puts the saved name / gender / photo back into the session when it lost them. */
    public static void restore(Context context) {
        if (context == null) return;
        try {
            SharedPreferences a = auth(context);
            if (!loggedIn(a)) return;
            String acct = acct(context);
            if (acct.length() == 0) return;
            SharedPreferences s = store(context);
            SharedPreferences.Editor ed = null;

            boolean pending = s.getBoolean("pending::" + acct, false);
            String name = s.getString("name::" + acct, "");
            String gender = s.getString("gender::" + acct, "");
            String curName = a.getString("name", "");
            String curGender = a.getString("gender", "");
            if (pending) {
                // an edit that the server has not confirmed yet wins over what the login brought back
                if (name.length() > 0 && !name.equals(curName)) { ed = begin(a, ed); ed.putString("name", name); }
                if (gender.length() > 0 && !gender.equals(curGender)) { ed = begin(a, ed); ed.putString("gender", gender); }
            } else {
                if ((curName == null || curName.trim().length() == 0) && name.length() > 0) {
                    ed = begin(a, ed); ed.putString("name", name);
                }
                if ((curGender == null || curGender.trim().length() == 0) && gender.length() > 0) {
                    ed = begin(a, ed); ed.putString("gender", gender);
                }
            }

            String path = a.getString("avatar_local_path", "");
            boolean hasLocal = path != null && path.trim().length() > 0 && new File(path.trim()).isFile();
            if (!hasLocal) {
                File own = new File(CigramAvatar.dir(context), acct + ".jpg");
                if (own.isFile() && own.length() > 0L) {
                    ed = begin(a, ed);
                    ed.putString("avatar_local_path", own.getAbsolutePath());
                }
            }
            if (ed != null) ed.apply();
        } catch (Throwable error) {
            android.util.Log.w("CigramProfile", "restore failed", error);
        }
    }

    private static SharedPreferences.Editor begin(SharedPreferences p, SharedPreferences.Editor ed) {
        return ed == null ? p.edit() : ed;
    }

    // ------------------------------------------------------------------ snapshot (background)

    /** Copies the current profile into the account's store. Call from any page, any time. */
    public static void snapshot(final Context context) {
        if (context == null) return;
        long now = System.currentTimeMillis();
        synchronized (CigramProfile.class) {
            if (now - lastSnapshotAt < 4000L) return;
            lastSnapshotAt = now;
        }
        final Context app = context.getApplicationContext();
        CigramSync.runIO(new Runnable() {
            @Override public void run() {
                try {
                    SharedPreferences a = auth(app);
                    if (!loggedIn(a)) return;
                    String acct = acct(app);
                    if (acct.length() == 0) return;
                    File dir = CigramAvatar.dir(app);

                    String path = a.getString("avatar_local_path", "");
                    if (path != null && path.trim().length() > 0) {
                        File src = new File(path.trim());
                        File own = new File(dir, acct + ".jpg");
                        if (src.isFile() && src.length() > 0L && (!own.isFile()
                                || own.length() != src.length() || own.lastModified() < src.lastModified())) {
                            copy(src, own);
                            CigramAvatar.prewarm(app);
                        }
                    }
                    String url = a.getString("avatar_url", "");
                    if (url != null && url.trim().length() > 0) {
                        String hex = Integer.toHexString(url.trim().hashCode());
                        File remote = new File(dir, acct + "_" + hex + ".jpg");
                        File cached = new File(app.getCacheDir(), "cigram_nav_avatar_" + hex + ".jpg");
                        if (!remote.isFile() && cached.isFile() && cached.length() > 0L) {
                            copy(cached, remote);
                            File[] old = dir.listFiles();
                            for (int i = 0; old != null && i < old.length; i++) {
                                String n = old[i].getName();
                                if (n.startsWith(acct + "_") && !n.equals(remote.getName())) old[i].delete();
                            }
                        }
                    }

                    SharedPreferences.Editor ed = store(app).edit();
                    boolean pending = store(app).getBoolean("pending::" + acct, false);
                    if (!pending) {
                        String n = a.getString("name", "");
                        String g = a.getString("gender", "");
                        if (n != null && n.trim().length() > 0) ed.putString("name::" + acct, n.trim());
                        if (g != null && g.trim().length() > 0) ed.putString("gender::" + acct, g.trim());
                    }
                    ed.putString("avatar_url::" + acct, url == null ? "" : url.trim());
                    ed.apply();
                } catch (Throwable error) {
                    android.util.Log.w("CigramProfile", "snapshot failed", error);
                }
            }
        });
    }

    // ------------------------------------------------------------------ edits that could not reach the server

    /**
     * Keeps an edit made without a network: the session shows it at once and it is sent later.
     * Returns false when nobody is signed in.
     */
    public static boolean saveLocalEdit(Context context, String name, String gender) {
        if (context == null || name == null || name.trim().length() == 0) return false;
        try {
            SharedPreferences a = auth(context);
            if (!loggedIn(a)) return false;
            String acct = acct(context);
            if (acct.length() == 0) return false;
            String g = gender == null || gender.trim().length() == 0 ? a.getString("gender", "unspecified") : gender.trim();
            a.edit().putString("name", name.trim()).putString("gender", g).apply();
            store(context).edit().putString("name::" + acct, name.trim()).putString("gender::" + acct, g)
                    .putBoolean("pending::" + acct, true).putLong("pending_at::" + acct, System.currentTimeMillis()).apply();
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    /** Sends a saved edit to the server (at most once a minute; quiet when it cannot). */
    public static void syncPending(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        final String acct;
        try {
            SharedPreferences a = auth(app);
            if (!loggedIn(a)) return;
            acct = acct(app);
            if (acct.length() == 0 || !store(app).getBoolean("pending::" + acct, false)) return;
        } catch (Throwable ignored) { return; }
        long now = System.currentTimeMillis();
        synchronized (CigramProfile.class) {
            if (syncing || now - lastSyncAt < SYNC_GAP_MS) return;
            syncing = true;
            lastSyncAt = now;
        }
        CigramSync.runIO(new Runnable() {
            @Override public void run() {
                java.net.HttpURLConnection con = null;
                try {
                    SharedPreferences s = store(app);
                    String token = auth(app).getString("token", "").trim();
                    JSONObject body = new JSONObject();
                    body.put("name", s.getString("name::" + acct, ""));
                    body.put("gender", s.getString("gender::" + acct, "unspecified"));
                    con = (java.net.HttpURLConnection) new java.net.URL(API).openConnection();
                    con.setRequestMethod("POST");
                    con.setConnectTimeout(15000);
                    con.setReadTimeout(15000);
                    con.setDoOutput(true);
                    con.setUseCaches(false);
                    con.setRequestProperty("Authorization", "Bearer " + token);
                    con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    con.setRequestProperty("Accept", "application/json");
                    OutputStream out = con.getOutputStream();
                    try { out.write(body.toString().getBytes("UTF-8")); out.flush(); } finally { out.close(); }
                    int code = con.getResponseCode();
                    InputStream in = code >= 200 && code < 300 ? con.getInputStream() : con.getErrorStream();
                    StringBuilder sb = new StringBuilder();
                    if (in != null) {
                        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
                        try { String line; while ((line = r.readLine()) != null && sb.length() < 65536) sb.append(line); }
                        finally { r.close(); }
                    }
                    boolean ok = code >= 200 && code < 300 && new JSONObject(sb.toString()).optBoolean("success", false);
                    // a rejected edit (4xx) is dropped; a network/server error stays pending for the next try
                    if (ok || (code >= 400 && code < 500 && code != 401 && code != 408 && code != 429)) {
                        s.edit().putBoolean("pending::" + acct, false).apply();
                    }
                } catch (Throwable ignored) {
                    // offline: stays pending
                } finally {
                    if (con != null) con.disconnect();
                    synchronized (CigramProfile.class) { syncing = false; }
                }
            }
        });
    }

    /** Account deleted: remove the saved profile and photo of the account. */
    public static void wipe(Context context) {
        if (context == null) return;
        try {
            String acct = acct(context);
            if (acct.length() > 0) {
                SharedPreferences s = store(context);
                SharedPreferences.Editor ed = s.edit();
                for (String k : new java.util.ArrayList<String>(s.getAll().keySet())) {
                    if (k.endsWith("::" + acct)) ed.remove(k);
                }
                ed.apply();
                File[] files = CigramAvatar.dir(context).listFiles();
                for (int i = 0; files != null && i < files.length; i++) {
                    if (files[i].getName().startsWith(acct)) files[i].delete();
                }
            }
            CigramAvatar.forget();
        } catch (Throwable ignored) { }
    }

    private static void copy(File from, File to) throws Exception {
        File tmp = new File(to.getParentFile(), to.getName() + ".tmp");
        InputStream in = new FileInputStream(from);
        OutputStream out = new FileOutputStream(tmp, false);
        try {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
            try { out.close(); } catch (Throwable ignored) { }
        }
        if (to.exists()) to.delete();
        if (!tmp.renameTo(to)) tmp.delete();
        else to.setLastModified(Math.max(from.lastModified(), System.currentTimeMillis()));
    }
}
