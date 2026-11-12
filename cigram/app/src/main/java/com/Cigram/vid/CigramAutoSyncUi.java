package com.Cigram.vid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;

import com.Cigram.subhub.SubAutoSync;
import com.Cigram.subhub.SubDocument;
import com.Cigram.subhub.SubSync;

/**
 * Subtitle Auto Sync, the viewer's side: «ضبط تلقائي من صوت الفيلم».
 *
 * The sound heard so far ({@link CigramAudioTap}) is compared with the subtitle's on / off pattern in the background
 * ({@link SubAutoSync#alignToActivity}); the viewer is shown what was found and decides. Nothing is applied by
 * itself, and a weak match is reported as weak with advice (keep watching, try again) instead of a guess.
 * An accepted result becomes the viewer's delay for this subtitle file — the same value «توقيت الترجمة» edits.
 */
final class CigramAutoSyncUi {

    private CigramAutoSyncUi() { }

    /** How much of the film around the current position is used. */
    private static final long WINDOW_BEFORE_MS = 12 * 60000L, WINDOW_AFTER_MS = 60000L;

    static void run(final Activity a, final CigramSubtitleSyncEngine engine, final CigramAudioTap tap) {
        if (a == null || a.isFinishing() || engine == null) return;
        final SubDocument doc = engine.document();
        if (doc == null || doc.isEmpty()) { say(a, "لا توجد ترجمة محمّلة لضبطها."); return; }
        if (tap == null) {
            say(a, "الضبط التلقائي يعمل مع العناوين التي تُفتح ومعها ترجمة. أعد فتح العنوان ثم جرّب بعد دقيقتين من المشاهدة.");
            return;
        }
        final long pos = Math.max(0, engine.positionMs());
        long heard = tap.heardUpToMs();
        // the sound must belong to this title's timeline: the newest sound is within a few seconds of what is on screen
        if (heard < 0 || Math.abs(heard - pos) > 8000) {
            say(a, tap.sound.coveredMs() < 1000 ? "لم يُسمع صوت الفيلم بعد (أو أن الصوت بصيغة تمر مباشرة إلى السماعة). شاهد دقيقتين ثم أعد المحاولة."
                    : "تعذر ربط الصوت بتوقيت هذا البث. استخدم «تعديل دقيق مع معاينة».");
            return;
        }
        final AlertDialog wait = new AlertDialog.Builder(a).setMessage("جارٍ مطابقة الترجمة مع صوت الفيلم…").setCancelable(true).create();
        try { wait.show(); } catch (Throwable ignored) { }
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                SubAutoSync.Alignment found;
                try {
                    SubAutoSync.Activity sound = tap.sound.snapshot(Math.max(0, pos - WINDOW_BEFORE_MS), pos + WINDOW_AFTER_MS);
                    found = SubAutoSync.alignToActivity(doc, sound, 30000L);
                } catch (Throwable t) {
                    found = null;
                }
                final SubAutoSync.Alignment r = found;
                main.post(new Runnable() {
                    @Override public void run() {
                        try { wait.dismiss(); } catch (Throwable ignored) { }
                        if (a.isFinishing()) return;
                        show(a, engine, r);
                    }
                });
            }
        }, "cigram-autosync").start();
    }

    private static void show(final Activity a, final CigramSubtitleSyncEngine engine, final SubAutoSync.Alignment r) {
        if (r == null) { say(a, "تعذر تحليل الصوت. استخدم «تعديل دقيق مع معاينة»."); return; }
        final long now = engine.delayMs();
        if (!r.reliable()) {
            String why = r.note.length() > 0 ? r.note : "التطابق مع الصوت غير كافٍ.";
            String guess = r.matched > 0 && r.confidence > 0.2 ? "\n\nأقرب تقدير: " + SubSync.delayLabel(r.offsetMs) + " (ثقة " + Math.round(r.confidence * 100) + "% — غير كافية)." : "";
            say(a, why + guess + "\n\nتابع المشاهدة لدقائق في مشهد فيه حوار ثم أعد المحاولة.");
            return;
        }
        final long target = SubSync.clampDelay(r.offsetMs);
        if (Math.abs(target - now) < 150) {
            say(a, "الترجمة متزامنة مع الصوت (الثقة " + Math.round(r.confidence * 100) + "%). لا حاجة لأي تعديل.");
            return;
        }
        String msg = "حسب صوت الفيلم يجب أن يكون توقيت الترجمة " + SubSync.delayLabel(target)
                + (now != 0 ? " (الحالي " + SubSync.delayLabel(now) + ")" : "") + ".\n\nالثقة " + Math.round(r.confidence * 100)
                + "% — حُلّل " + (r.matched * 50L / 60000L) + " دقيقة من الصوت.\nيمكنك التراجع في أي وقت من «توقيت الترجمة».";
        try {
            new AlertDialog.Builder(a).setTitle("الضبط التلقائي").setMessage(msg)
                    .setPositiveButton("تطبيق", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            engine.setDelayMs(target);
                            say(a, "طُبّق " + SubSync.delayLabel(engine.delayMs()) + " على هذه الترجمة.");
                        }
                    })
                    .setNegativeButton("ليس الآن", null).show();
        } catch (Throwable ignored) { }
    }

    private static void say(Activity a, String text) {
        try {
            new AlertDialog.Builder(a).setMessage(text).setPositiveButton("حسناً", null).show();
        } catch (Throwable ignored) { }
    }
}
