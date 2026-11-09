package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v6.0 (design 5): «قفل الشاشة». A lock button on the left edge of the player. While locked, every
 * touch on the player is swallowed (no accidental pause, seek or back to the controls) and all the
 * controls stay hidden; a tap shows the unlock button for a few seconds.
 * The lock button follows the player's own show / hide of its controls.
 */
public final class CigramPlayerLock {

    private static final long UNLOCK_SHOWN_MS = 2600L;

    private final Activity activity;
    private final FrameLayout host;
    private final View[] controls;
    private final int[] savedVisibility;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final FrameLayout lockButton;
    private FrameLayout shield;
    private View unlockChip;
    private boolean locked;

    private final Runnable hideUnlock = new Runnable() {
        @Override public void run() {
            if (unlockChip != null) unlockChip.animate().alpha(0f).setDuration(180L).start();
        }
    };

    private CigramPlayerLock(Activity activity, FrameLayout host, View[] controls) {
        this.activity = activity;
        this.host = host;
        this.controls = controls == null ? new View[0] : controls;
        this.savedVisibility = new int[this.controls.length];
        lockButton = roundButton(false);
        lockButton.setContentDescription("قفل الشاشة");
        lockButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { lock(); }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(44), dp(44),
                Gravity.LEFT | Gravity.CENTER_VERTICAL);
        lp.leftMargin = dp(22);
        host.addView(lockButton, lp);
    }

    /** Called once from the player Logic, after its controls exist. Safe to call with nulls. */
    public static void attach(Activity activity, FrameLayout host, View[] controls) {
        if (activity == null || host == null) return;
        try {
            final CigramPlayerLock lock = new CigramPlayerLock(activity, host, controls);
            host.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    lock.sync();
                    return true;
                }
            });
        } catch (Throwable error) {
            android.util.Log.w("CigramPlayer", "lock attach failed", error);
        }
    }

    private int dp(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    /** Runs before every frame: keeps the lock button in step with the controls, and controls hidden while locked. */
    private void sync() {
        if (locked) {
            for (View c : controls) {
                if (c != null && c.getVisibility() == View.VISIBLE) c.setVisibility(View.INVISIBLE);
            }
            return;
        }
        View ref = null;
        for (View c : controls) {
            if (c != null && c.getVisibility() != View.GONE) { ref = c; break; }
        }
        boolean shown = ref != null && ref.getVisibility() == View.VISIBLE && ref.getAlpha() > 0.05f;
        float alpha = ref == null ? 0f : ref.getAlpha();
        if (lockButton.getVisibility() != (shown ? View.VISIBLE : View.GONE)) {
            lockButton.setVisibility(shown ? View.VISIBLE : View.GONE);
        }
        if (Math.abs(lockButton.getAlpha() - alpha) > 0.01f) lockButton.setAlpha(alpha);
    }

    private void lock() {
        if (locked) return;
        locked = true;
        for (int i = 0; i < controls.length; i++) {
            savedVisibility[i] = controls[i] == null ? View.GONE : controls[i].getVisibility();
        }
        lockButton.setVisibility(View.GONE);

        shield = new FrameLayout(activity);
        shield.setClickable(true);
        shield.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP) showUnlock();
                return true; // nothing reaches the player while locked
            }
        });

        LinearLayout chip = new LinearLayout(activity);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout unlock = roundButton(true);
        unlock.setContentDescription("فتح القفل");
        unlock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { unlock(); }
        });
        chip.addView(unlock, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView hint = new TextView(activity);
        hint.setText("الشاشة مقفلة — اضغط لفتحها");
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(12.5f);
        hint.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.topMargin = dp(8);
        chip.addView(hint, hl);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        cl.leftMargin = dp(16);
        shield.addView(chip, cl);
        unlockChip = chip;

        host.addView(shield, new FrameLayout.LayoutParams(-1, -1));
        showUnlock();
    }

    private void showUnlock() {
        if (unlockChip == null) return;
        ui.removeCallbacks(hideUnlock);
        unlockChip.animate().cancel();
        unlockChip.setAlpha(1f);
        ui.postDelayed(hideUnlock, UNLOCK_SHOWN_MS);
    }

    private void unlock() {
        if (!locked) return;
        locked = false;
        ui.removeCallbacks(hideUnlock);
        if (shield != null) host.removeView(shield);
        shield = null;
        unlockChip = null;
        for (int i = 0; i < controls.length; i++) {
            View c = controls[i];
            if (c == null || savedVisibility[i] == View.GONE) continue;
            c.setVisibility(View.VISIBLE);
            c.setAlpha(1f);
        }
    }

    // ------------------------------------------------------------------ look

    private FrameLayout roundButton(final boolean open) {
        FrameLayout b = new FrameLayout(activity);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x66000000);
        bg.setStroke(Math.max(1, dp(1)), 0x40FFFFFF);
        b.setBackground(bg);
        b.setClickable(true);
        b.setFocusable(true);
        b.addView(new PadlockView(activity, open), new FrameLayout.LayoutParams(-1, -1));
        return b;
    }

    /** Padlock drawn with Canvas: closed on the lock button, open on the unlock button. */
    private static final class PadlockView extends View {
        private final boolean open;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        PadlockView(Context c, boolean open) {
            super(c);
            this.open = open;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setColor(0xFFFFFFFF);
            fill.setColor(0xFFFFFFFF);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight());
            if (s <= 0) return;
            float ox = (getWidth() - s) / 2f;
            float oy = (getHeight() - s) / 2f;
            stroke.setStrokeWidth(Math.max(1.5f, s * 0.055f));
            // body
            r.set(ox + s * 0.32f, oy + s * 0.46f, ox + s * 0.68f, oy + s * 0.72f);
            c.drawRoundRect(r, s * 0.05f, s * 0.05f, fill);
            // shackle (open: lifted and shifted to the right)
            float shift = open ? s * 0.12f : 0f;
            float lift = open ? s * 0.05f : 0f;
            r.set(ox + s * 0.38f + shift, oy + s * 0.27f - lift, ox + s * 0.62f + shift, oy + s * 0.53f - lift);
            c.drawArc(r, 180f, 180f, false, stroke);
            c.drawLine(ox + s * 0.62f + shift, oy + s * 0.40f - lift, ox + s * 0.62f + shift, oy + s * 0.47f, stroke);
            if (!open) c.drawLine(ox + s * 0.38f, oy + s * 0.40f, ox + s * 0.38f, oy + s * 0.47f, stroke);
        }
    }
}
