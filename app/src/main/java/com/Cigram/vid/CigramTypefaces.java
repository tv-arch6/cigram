package com.Cigram.vid;

import android.content.res.AssetManager;
import android.graphics.Typeface;

import java.util.HashMap;

/**
 * Typeface.createFromAsset() parses the font file on every call and does not cache the result. The
 * screens call it dozens of times while they are built (the same font for every title), all on the
 * main thread. This keeps one Typeface per asset path.
 */
final class CigramTypefaces {

    private static final HashMap<String, Typeface> CACHE = new HashMap<String, Typeface>();

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
}
