package com.Cigram.vid;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.os.Build;
import android.util.Log;

import java.util.HashMap;

/**
 * Single owner of the app typeface (Beiruti, a variable Arabic + Latin font).
 *
 * <ul>
 *   <li>{@link #get} parses a font asset once per process (Typeface.createFromAsset() re-parses the file on every call).</li>
 *   <li>{@link #app()} / {@link #bold()} give the app font from anywhere, without a Context. They never return null:
 *       until the font has been loaded they answer with the system fonts, so a screen is never blocked on it.</li>
 *   <li>On Android 8+ the bold face is a real wght=700 instance of the variable font; older versions let the
 *       system synthesise bold from the regular face.</li>
 * </ul>
 */
final class CigramTypefaces {

    private static final String TAG = "CigramTypefaces";

    /** The one UI font of the application. */
    static final String APP_FONT = "fonts/beiruti.ttf";

    private static final HashMap<String, Typeface> CACHE = new HashMap<String, Typeface>();
    private static volatile Typeface appRegular;
    private static volatile Typeface appBold;

    private CigramTypefaces() { }

    static Typeface get(AssetManager assets, String path) {
        synchronized (CACHE) {
            Typeface hit = CACHE.get(path);
            if (hit != null) return hit;
        }
        Typeface created = Typeface.createFromAsset(assets, path);   // throws like the original on a bad path
        synchronized (CACHE) {
            Typeface raced = CACHE.get(path);
            if (raced != null) return raced;
            CACHE.put(path, created);
        }
        return created;
    }

    /** Loads the app font (both weights) if that has not happened yet. Safe to call from any thread. */
    static Typeface app(AssetManager assets) {
        Typeface regular = appRegular;
        if (regular != null) return regular;
        synchronized (CigramTypefaces.class) {
            if (appRegular != null) return appRegular;
            try {
                Typeface loaded = get(assets, APP_FONT);
                appBold = buildBold(assets, loaded);
                appRegular = loaded;
                return loaded;
            } catch (RuntimeException error) {
                Log.w(TAG, "app font could not be loaded, using the system font", error);
                return Typeface.DEFAULT;
            }
        }
    }

    /** Same as {@link #app(AssetManager)}, from a Context. */
    static Typeface app(Context context) {
        return app(context.getApplicationContext().getAssets());
    }

    /** The app font if it is already loaded, otherwise the system font (never null, never blocks). */
    static Typeface app() {
        Typeface regular = appRegular;
        return regular != null ? regular : Typeface.DEFAULT;
    }

    /** The bold weight of the app font if it is already loaded, otherwise the system bold (never null, never blocks). */
    static Typeface bold() {
        Typeface bold = appBold;
        return bold != null ? bold : Typeface.DEFAULT_BOLD;
    }

    /** Bold face matching the requested style flags (0 = regular, Typeface.BOLD = bold). */
    static Typeface forStyle(int style) {
        return (style & Typeface.BOLD) != 0 ? bold() : app();
    }

    /** True for the regular or bold face of the app font (used to leave already-styled text alone). */
    static boolean isAppFace(Typeface face) {
        return face != null && (face == appRegular || face == appBold);
    }

    /** Loads the font on a background thread so the first screen never waits for the file to be parsed. */
    static void warm(final Context context) {
        if (appRegular != null || context == null) return;
        final AssetManager assets = context.getApplicationContext().getAssets();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    app(assets);
                } catch (RuntimeException error) {
                    Log.w(TAG, "font warm-up failed", error);
                }
            }
        }, "cigram-font");
        t.setDaemon(true);
        t.start();
    }

    private static Typeface buildBold(AssetManager assets, Typeface regular) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Typeface real = new Typeface.Builder(assets, APP_FONT)
                        .setFontVariationSettings("'wght' 700")
                        .setWeight(700)
                        .build();
                if (real != null) return real;
            } catch (RuntimeException error) {
                Log.w(TAG, "variable bold instance unavailable, using synthesised bold", error);
            }
        }
        return Typeface.create(regular, Typeface.BOLD);
    }
}
