package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

import java.io.File;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * v10.67: the signed-in user's photo, kept for good and drawn the same way on every page.
 *
 * Where it comes from (first one that exists):
 *   1. the photo the user picked on this device (auth_session → avatar_local_path);
 *   2. the account's own saved copy in filesDir/cigram_avatar/ (written by {@link CigramProfile},
 *      so a re-login, a cleared cache or a new session never loses it);
 *   3. the copy of the server photo saved for the current avatar_url.
 *
 * The decoded picture is kept in memory for the whole process, so the bottom bar of the home, the
 * search, the channels, the account and the film page all show it on their first frame — no empty
 * circle, no jump, no second download. Decoding happens off the main thread.
 */
public final class CigramAvatar {

    private static final String DIR = "cigram_avatar";
    private static final int MAX_PX = 384;
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "cigram-avatar");
            t.setDaemon(true);
            return t;
        }
    });
    private static final WeakHashMap<ImageView, String> WANTED = new WeakHashMap<ImageView, String>();
    private static final ViewOutlineProvider OVAL = new ViewOutlineProvider() {
        @Override public void getOutline(View v, Outline outline) {
            if (v.getWidth() > 0 && v.getHeight() > 0) outline.setOval(0, 0, v.getWidth(), v.getHeight());
        }
    };

    private static String memoryKey = "";
    private static Bitmap memoryBitmap;

    private CigramAvatar() { }

    static File dir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static boolean valid(File f) {
        return f != null && f.isFile() && f.length() > 0L;
    }

    /** The file the photo is read from now, or null (guest / no photo yet). */
    public static File source(Context context) {
        if (context == null) return null;
        try {
            Context app = context.getApplicationContext();
            SharedPreferences p = app.getSharedPreferences("auth_session", Context.MODE_PRIVATE);
            String token = p.getString("token", "");
            if (!p.getBoolean("logged_in", false) || token == null || token.trim().length() == 0) return null;
            String path = p.getString("avatar_local_path", "");
            if (path != null && path.trim().length() > 0) {
                File f = new File(path.trim());
                if (valid(f)) return f;
            }
            String acct = CigramUserData.accountHash(app);
            if (acct.length() == 0) return null;
            File own = new File(dir(app), acct + ".jpg");
            if (valid(own)) return own;
            String url = p.getString("avatar_url", "");
            if (url != null && url.trim().length() > 0) {
                File remote = new File(dir(app), acct + "_" + Integer.toHexString(url.trim().hashCode()) + ".jpg");
                if (valid(remote)) return remote;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static String keyOf(File f) {
        return f.getAbsolutePath() + "|" + f.lastModified() + "|" + f.length();
    }

    /** Decodes the photo into memory ahead of time (app start, right after a login). */
    public static void prewarm(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    File f = source(app);
                    if (f == null) return;
                    String key = keyOf(f);
                    synchronized (LOCK) { if (key.equals(memoryKey) && memoryBitmap != null) return; }
                    Bitmap b = decode(f);
                    if (b == null) return;
                    synchronized (LOCK) { memoryKey = key; memoryBitmap = b; }
                } catch (Throwable ignored) { }
            }
        });
    }

    /** Forgets the decoded picture (logout, account deleted). */
    public static void forget() {
        synchronized (LOCK) {
            memoryKey = "";
            memoryBitmap = null;
        }
    }

    /**
     * Puts the photo on {@code view} as a round picture. Returns false when there is no photo to
     * show (the caller keeps its own fallback); true when the photo is shown now or a moment later.
     */
    public static boolean applyTo(final ImageView view, final boolean selected, boolean compact) {
        if (view == null) return false;
        final File f = source(view.getContext());
        if (f == null) return false;
        final String key = keyOf(f);
        Bitmap hit;
        synchronized (LOCK) { hit = key.equals(memoryKey) ? memoryBitmap : null; }
        if (hit != null && !hit.isRecycled()) {
            show(view, hit, selected, compact);
            return true;
        }
        if (view.getDrawable() == null) {
            // the first frame is never an empty circle: the plain account icon until the photo is ready
            int id = view.getResources().getIdentifier("nav_account", "drawable", view.getContext().getPackageName());
            if (id != 0) view.setImageResource(id);
        }
        synchronized (WANTED) { WANTED.put(view, key); }
        IO.execute(new Runnable() {
            @Override public void run() {
                final Bitmap b = decode(f);
                if (b == null) return;
                synchronized (LOCK) { memoryKey = key; memoryBitmap = b; }
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        String wanted;
                        synchronized (WANTED) { wanted = WANTED.get(view); }
                        if (key.equals(wanted)) show(view, b, selected, compact);
                    }
                });
            }
        });
        return true;
    }

    private static void show(ImageView view, Bitmap bitmap, boolean selected, boolean compact) {
        // Only the small bottom-bar avatar (compact) carries the badge itself; the account header adds its own badge view
        if (compact && CigramAvatarFrame.selfFramed(view.getContext())) {
            CigramAvatarFrame.applyToImage(view, bitmap, selected);
            return;
        }
        CigramAvatarFrame.clearImage(view);
        view.setPadding(0, 0, 0, 0);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.clearColorFilter();   // v10.83: the photo is never tinted like the symbols
        view.setImageBitmap(bitmap);
        view.setAlpha(selected ? 1f : 0.82f);
        view.setOutlineProvider(OVAL);
        view.setClipToOutline(true);
        view.invalidateOutline();
    }

    private static Bitmap decode(File f) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / sample > MAX_PX || bounds.outHeight / sample > MAX_PX) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable error) {
            return null;
        }
    }
}
