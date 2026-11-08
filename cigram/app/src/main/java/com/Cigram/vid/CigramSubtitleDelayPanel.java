package com.Cigram.vid;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.Cigram.subhub.SubCodec;
import com.Cigram.subhub.SubCue;
import com.Cigram.subhub.SubSync;

/**
 * «توقيت الترجمة» — the viewer's subtitle delay with a live preview against the real video timeline.
 *
 * Shows the current delay, the cue nearest to the playing position (its time after the delay and the playing
 * time), and nudges of ±0.1 s / ±0.5 s / ±1 s plus «إعادة الضبط إلى 0». Every change is applied at once by
 * {@link CigramSubtitleSyncEngine} (no re-buffering) and saved for this subtitle file only.
 */
final class CigramSubtitleDelayPanel {

    private CigramSubtitleDelayPanel() {}

    private static final int BG = 0xFF0E0E10;
    private static final int CARD = 0xFF1A1A1E;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFA1A1AA;
    private static final int ACCENT = 0xFFE21B14;

    static void show(final Activity a, final CigramSubtitleSyncEngine engine) {
        if (a == null || engine == null || a.isFinishing()) return;
        final float dp = a.getResources().getDisplayMetrics().density;
        final Typeface font = CigramTypefaces.app(a.getAssets());
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (16 * dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadius(22 * dp);
        root.setBackground(bg);

        root.addView(label(a, "توقيت الترجمة", 18, TEXT, true, font));
        final TextView value = label(a, "", 30, TEXT, true, font);
        value.setPadding(0, (int) (8 * dp), 0, 0);
        root.addView(value);
        final TextView meaning = label(a, "", 13, MUTED, false, font);
        root.addView(meaning);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(CARD);
        cb.setCornerRadius(14 * dp);
        card.setBackground(cb);
        final TextView cueText = label(a, "", 16, TEXT, false, font);
        cueText.setGravity(Gravity.CENTER);
        final TextView cueTime = label(a, "", 12, MUTED, false, font);
        cueTime.setTextDirection(View.TEXT_DIRECTION_RTL);
        card.addView(cueText);
        card.addView(cueTime);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.setMargins(0, (int) (14 * dp), 0, (int) (14 * dp));
        root.addView(card, cl);

        final Runnable[] refresh = new Runnable[1];
        long[][] rows = {{-1000, -500, -100}, {100, 500, 1000}};
        for (long[] r : rows) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (final long step : r) {
                TextView b = button(a, (step > 0 ? "+" : "−") + (Math.abs(step) >= 1000 ? (Math.abs(step) / 1000) + " ث" : "0." + (Math.abs(step) / 100) + " ث"), false, font, dp);
                b.setContentDescription(step > 0 ? "تأخير الترجمة " + Math.abs(step) + " ملّي ثانية" : "تقديم الترجمة " + Math.abs(step) + " ملّي ثانية");
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        engine.setDelayMs(engine.delayMs() + step);
                        refresh[0].run();
                    }
                });
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, (int) (48 * dp), 1f);
                bl.setMargins((int) (4 * dp), (int) (4 * dp), (int) (4 * dp), (int) (4 * dp));
                row.addView(b, bl);
            }
            root.addView(row);
        }
        TextView reset = button(a, "إعادة الضبط إلى 0", true, font, dp);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                engine.setDelayMs(0);
                refresh[0].run();
            }
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, (int) (50 * dp));
        rl.setMargins((int) (4 * dp), (int) (10 * dp), (int) (4 * dp), (int) (6 * dp));
        root.addView(reset, rl);
        TextView hint = label(a, "إذا ظهر النص قبل الكلام اضغط «+»، وإذا تأخر عنه اضغط «−». يُحفظ التعديل لهذا الملف فقط ولا يغيّر الملف نفسه.", 12, MUTED, false, font);
        root.addView(hint);

        final Handler h = new Handler(Looper.getMainLooper());
        refresh[0] = new Runnable() {
            @Override public void run() {
                long ms = engine.delayMs();
                value.setText(ms == 0 ? "متزامنة (0)" : SubSync.delayLabel(ms));
                meaning.setText(ms == 0 ? "الترجمة على توقيت الملف الأصلي." : ms > 0 ? "تظهر الترجمة متأخرة " + SubSync.delayLabel(ms).replace("+", "") + " عن توقيت الملف." : "تظهر الترجمة مبكرة " + SubSync.delayLabel(ms).replace("−", "") + " عن توقيت الملف.");
                SubCue c = engine.previewCue();
                if (engine.document() == null) {
                    String err = engine.lastError();
                    cueText.setText(err.length() > 0 ? err : "جارٍ تحميل الترجمة…");
                    cueTime.setText("");
                } else if (c == null) {
                    cueText.setText("لا يوجد نص قريب من هذا الموضع.");
                    cueTime.setText("");
                } else {
                    cueText.setText(CigramSubtitleSyncEngine.render(c.text));
                    long pos = engine.positionMs();
                    cueTime.setText("‏الآن " + "⁦" + SubCodec.stamp(pos, '.') + "⁩" + "  ·  يظهر المقطع " + "⁦" + SubCodec.stamp(c.startMs + ms, '.') + "⁩");
                }
            }
        };
        final Runnable loop = new Runnable() {
            @Override public void run() {
                if (!d.isShowing()) return;
                refresh[0].run();
                h.postDelayed(this, 250);
            }
        };
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = (int) Math.min(a.getResources().getDisplayMetrics().widthPixels * 0.92f, 460 * dp);
            lp.dimAmount = 0.25f;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface di) {
                h.removeCallbacksAndMessages(null);
            }
        });
        refresh[0].run();
        d.show();
        h.postDelayed(loop, 250);
        reset.requestFocus();
    }

    private static TextView label(Activity a, String s, float sp, int color, boolean bold, Typeface font) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.RIGHT);
        t.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        if (font != null) t.setTypeface(font, bold ? Typeface.BOLD : Typeface.NORMAL);
        else if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static TextView button(Activity a, String s, boolean primary, Typeface font, float dp) {
        TextView b = label(a, s, 15, TEXT, true, font);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        GradientDrawable n = new GradientDrawable();
        n.setColor(primary ? ACCENT : CARD);
        n.setCornerRadius(12 * dp);
        GradientDrawable f = new GradientDrawable();
        f.setColor(primary ? 0xFFB8150F : 0xFF2A2A30);
        f.setCornerRadius(12 * dp);
        f.setStroke((int) (2 * dp), TEXT);
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, f);
        sl.addState(new int[]{android.R.attr.state_focused}, f);
        sl.addState(new int[]{}, n);
        b.setBackground(sl);
        return b;
    }
}
