package com.Cigram.vid;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/**
 * Compatibility shim: DetailsActivity (generated from Logic) calls cgGlass.px/label/bind/press,
 * but the cgGlass helper only exists inside the account page's Logic, so the build failed with
 * "cgGlass cannot be resolved". This class provides the same four calls on top of CigramGlass.
 * Safe to delete once DetailsActivity uses CigramGlass directly.
 */
public final class cgGlass {

    private cgGlass() { }

    /** dp -> px using the device density. */
    public static int px(float dp) {
        return Math.round(dp * Resources.getSystem().getDisplayMetrics().density);
    }

    /** Plain label used by the details page ("عرض المزيد"); styling is finished by bind()/press(). */
    public static TextView label(String text, float sp) {
        Context c = appContext();
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        try {
            Typeface f = CigramDesign.font(c);
            if (f != null) t.setTypeface(f);
        } catch (Throwable ignored) { }
        return t;
    }

    public static void bind(View view, float radiusDp, boolean outlined) {
        CigramGlass.bind(view, radiusDp, outlined);
    }

    public static void press(View view) {
        CigramGlass.press(view);
    }

    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) { }
        throw new IllegalStateException("no application context");
    }
}
