package com.my.newproject;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Keeps the upload sessions on disk (one small JSON file, written atomically) so an interrupted upload
 * can continue after the app or the phone restarted. SharedPreferences is not used because the parts
 * list of a big movie can reach a few hundred entries.
 */
final class CgUploadStorage {

    private static final String TAG = CgMediaAnalyzer.TAG;
    private static final String FILE = "cigram_media_uploads.json";

    private final File file;
    private final LinkedHashMap<String, CgUploadSession> sessions = new LinkedHashMap<String, CgUploadSession>();

    CgUploadStorage(Context c) {
        file = new File(c.getApplicationContext().getFilesDir(), FILE);
        load();
    }

    private synchronized void load() {
        sessions.clear();
        if (!file.exists()) return;
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            JSONObject root = new JSONObject(new String(out.toByteArray(), "UTF-8"));
            JSONArray arr = root.optJSONArray("sessions");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                CgUploadSession s = CgUploadSession.fromJson(o);
                if (s.id.length() > 0) sessions.put(s.id, s);
            }
        } catch (Exception e) {
            Log.e(TAG, "uploads store unreadable, keeping a backup copy", e);
            try {
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(new File(file.getParentFile(), FILE + ".bad"));
            } catch (Exception ignored) { }
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) { }
        }
    }

    /** Live objects (the manager mutates them). */
    synchronized ArrayList<CgUploadSession> all() {
        return new ArrayList<CgUploadSession>(sessions.values());
    }

    synchronized CgUploadSession get(String id) {
        return sessions.get(id);
    }

    synchronized void put(CgUploadSession s) {
        sessions.put(s.id, s);
        flush();
    }

    synchronized void remove(String id) {
        sessions.remove(id);
        flush();
    }

    synchronized void flush() {
        FileOutputStream out = null;
        File tmp = new File(file.getParentFile(), FILE + ".tmp");
        try {
            JSONArray arr = new JSONArray();
            for (CgUploadSession s : sessions.values()) arr.put(s.toJson());
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("sessions", arr);
            out = new FileOutputStream(tmp);
            out.write(root.toString().getBytes("UTF-8"));
            out.flush();
            try {
                out.getFD().sync();
            } catch (Exception ignored) { }
            out.close();
            out = null;
            if (!tmp.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                if (!tmp.renameTo(file)) Log.e(TAG, "uploads store: rename failed");
            }
        } catch (Exception e) {
            Log.e(TAG, "uploads store: write failed", e);
        } finally {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) { }
        }
    }
}
