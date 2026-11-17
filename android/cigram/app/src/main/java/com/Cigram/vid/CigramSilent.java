package com.Cigram.vid;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

/**
 * No failure text on the viewer's screen.
 *
 * A screen of the app that cannot finish something (a list that did not load, a link that did not open, a server that
 * did not answer) handles it itself — it retries, keeps the last good copy, or simply stays as it was. What it must not
 * do is put «تعذّر …» / «خطأ …» / «فشل …» in front of the viewer. Every short message of the app passes through
 * {@link #toast}: a failure sentence is written to the log (for the diagnostics page) and dropped; anything else —
 * a confirmation, an instruction, a validation hint the viewer has to act on — is shown as usual.
 */
final class CigramSilent {

    private CigramSilent() { }

    /** True for a sentence that only reports a failure. */
    static boolean isFailure(CharSequence text) {
        if (text == null) return true;
        String s = text.toString();
        return s.contains("تعذ") || s.contains("خطأ") || s.contains("فشل") || s.contains("حدثت مشكلة")
                || s.contains("استجابة") && s.contains("ناقصة");
    }

    static void toast(Context c, CharSequence text) {
        toast(c, text, Toast.LENGTH_SHORT);
    }

    static void toast(Context c, CharSequence text, int length) {
        if (c == null || text == null || text.length() == 0) return;
        if (isFailure(text)) {
            Log.w("CigramSilent", "handled quietly: " + text);
            try { CigramDiag.recovery("ui", "message", "QUIET", String.valueOf(text), null, 0, "hidden"); } catch (Throwable ignored) { }
            return;
        }
        try { Toast.makeText(c, text, length).show(); } catch (Throwable ignored) { }
    }
}
