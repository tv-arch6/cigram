package com.Cigram.vid;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * «هل أعجبك …؟» — the card shown after a film or an episode has been watched.
 *
 * A black square with a white hairline (iOS alert proportions) in the app's own font: the question in the middle with
 * the title's name, and right under it the like and dislike thumbs, white, without any background.
 *
 *   like     → a thank-you drawn with thin geometric lines (a ring that closes, a tick, rays);
 *   dislike  → a heart that appears whole, then breaks and cries, within a couple of seconds.
 *
 * The answer is the title's real reaction: it goes through the same code as the «أعجبني / لا يعجبني» buttons of the
 * details page, so the public counters move with it. The card closes itself; it never blocks the page.
 *
 * When it appears: the player was open for a real viewing ({@link #MIN_WATCH_MS}) and the viewer is back on the
 * title's page. The player and the details page report through {@link #playerOpened} / {@link #playerClosed} /
 * {@link #detailsResumed}.
 */
final class CigramRateCard {

    /** Shorter than this is a peek, not a viewing. */
    static final long MIN_WATCH_MS = 3L * 60L * 1000L;

    private static long playerSince;
    private static boolean pending;
    private static Dialog showing;

    private CigramRateCard() { }

    private static boolean external;

    static void playerOpened() {
        if (playerSince == 0L) playerSince = SystemClock.elapsedRealtime();
        external = false;
    }

    /** The title was handed to the companion player app: the viewing ends when the viewer is back on the page. */
    static void externalPlayerOpened() {
        playerSince = SystemClock.elapsedRealtime();
        external = true;
    }

    static void playerClosed() {
        if (playerSince == 0L) return;
        long watched = SystemClock.elapsedRealtime() - playerSince;
        playerSince = 0L;
        if (watched >= MIN_WATCH_MS) pending = true;
    }

    /** The viewer is back on a title's page: ask once for the viewing that just ended. */
    static void detailsResumed(final Activity a) {
        if (external && playerSince != 0L) {
            external = false;
            playerClosed();
        }
        if (!pending || a == null) return;
        pending = false;
        final View content = a.findViewById(android.R.id.content);
        if (content == null) return;
        content.postDelayed(new Runnable() {
            @Override public void run() {
                if (a.isFinishing() || a.isDestroyed()) return;
                String title = CigramStage1InitProvider.cigramDetailsTitle(a);
                if (title.length() == 0) return;
                show(a, title);
            }
        }, 450L);
    }

    static void show(final Activity a, String title) {
        try {
            if (showing != null && showing.isShowing()) return;
            final float d = a.getResources().getDisplayMetrics().density;
            final Dialog dialog = new Dialog(a);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            final Typeface face = CigramUI.font(a);
            final Typeface bold = CigramTypefaces.bold() != null ? CigramTypefaces.bold() : Typeface.DEFAULT_BOLD;

            final FrameLayout card = new FrameLayout(a);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF000000);
            bg.setCornerRadius(26 * d);
            bg.setStroke(Math.max(1, (int) (1.2f * d)), 0xFFFFFFFF);
            card.setBackground(bg);
            int side = (int) (272 * d);

            // ---- the question
            final LinearLayout ask = new LinearLayout(a);
            ask.setOrientation(LinearLayout.VERTICAL);
            ask.setGravity(Gravity.CENTER);
            ask.setPadding((int) (22 * d), 0, (int) (22 * d), 0);
            TextView q = new TextView(a);
            q.setText("هل أعجبك");
            q.setTextColor(0xB3FFFFFF);
            q.setTextSize(15f);
            q.setTypeface(face);
            q.setGravity(Gravity.CENTER);
            ask.addView(q, new LinearLayout.LayoutParams(-1, -2));
            TextView name = new TextView(a);
            name.setText(title + "؟");
            name.setTextColor(0xFFFFFFFF);
            name.setTextSize(20f);
            name.setTypeface(bold, Typeface.BOLD);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
            nl.topMargin = (int) (6 * d);
            ask.addView(name, nl);

            LinearLayout thumbs = new LinearLayout(a);
            thumbs.setOrientation(LinearLayout.HORIZONTAL);
            thumbs.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            thumbs.setGravity(Gravity.CENTER);
            final CigramFx.Thumb no = new CigramFx.Thumb(a, true);
            final CigramFx.Thumb yes = new CigramFx.Thumb(a, false);
            no.setContentDescription("لا يعجبني");
            yes.setContentDescription("أعجبني");
            int pad = (int) (10 * d), box = (int) (64 * d);
            no.setPadding(pad, pad, pad, pad);
            yes.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(box, box);
            tl.setMargins((int) (18 * d), 0, (int) (18 * d), 0);
            thumbs.addView(no, tl);
            thumbs.addView(yes, new LinearLayout.LayoutParams(tl));
            LinearLayout.LayoutParams thl = new LinearLayout.LayoutParams(-1, -2);
            thl.topMargin = (int) (22 * d);
            ask.addView(thumbs, thl);
            card.addView(ask, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

            View.OnTouchListener press = new View.OnTouchListener() {
                @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                    int act = e.getActionMasked();
                    if (act == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).start();
                    else if (act == android.view.MotionEvent.ACTION_UP || act == android.view.MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                    return false;
                }
            };
            yes.setOnTouchListener(press);
            no.setOnTouchListener(press);

            final Runnable close = new Runnable() {
                @Override public void run() { try { if (dialog.isShowing()) dialog.dismiss(); } catch (Throwable ignored) { } }
            };
            yes.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    CigramStage1InitProvider.cigramReact(a, true);
                    ask.animate().alpha(0f).setDuration(160L).withEndAction(new Runnable() {
                        @Override public void run() {
                            ask.setVisibility(View.GONE);
                            final Thanks thanks = new Thanks(a, face, bold);
                            card.addView(thanks, new FrameLayout.LayoutParams(-1, -1));
                            thanks.play();
                            card.postDelayed(close, 2100L);
                        }
                    }).start();
                }
            });
            no.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    CigramStage1InitProvider.cigramReact(a, false);
                    ask.animate().alpha(0f).setDuration(160L).withEndAction(new Runnable() {
                        @Override public void run() {
                            ask.setVisibility(View.GONE);
                            CigramFx.Heart heart = new CigramFx.Heart(a);
                            int hs = (int) (150 * d);
                            card.addView(heart, new FrameLayout.LayoutParams(hs, (int) (hs * 1.25f), Gravity.CENTER));
                            heart.play(new Runnable() { @Override public void run() { card.postDelayed(close, 250L); } });
                        }
                    }).start();
                }
            });

            dialog.setContentView(card, new FrameLayout.LayoutParams(side, side));
            dialog.setCanceledOnTouchOutside(true);
            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(0));
                w.setLayout(side, side);
                w.setGravity(Gravity.CENTER);
                w.setDimAmount(0.55f);
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
            dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                @Override public void onDismiss(android.content.DialogInterface di) { if (showing == dialog) showing = null; }
            });
            showing = dialog;
            dialog.show();
            card.setScaleX(0.9f);
            card.setScaleY(0.9f);
            card.setAlpha(0f);
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220L).start();
        } catch (Throwable error) {
            android.util.Log.w("CigramRateCard", "card skipped", error);
        }
    }

    /** «شكراً لك»: a ring that draws itself, a tick inside it and twelve thin rays; then the words. */
    private static final class Thanks extends FrameLayout {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tick = new Path();
        private float t;

        Thanks(Activity a, Typeface face, Typeface bold) {
            super(a);
            setWillNotDraw(false);
            float d = a.getResources().getDisplayMetrics().density;
            p.setColor(0xFFFFFFFF);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            LinearLayout words = new LinearLayout(a);
            words.setOrientation(LinearLayout.VERTICAL);
            words.setGravity(Gravity.CENTER);
            TextView big = new TextView(a);
            big.setText("شكراً لك");
            big.setTextColor(0xFFFFFFFF);
            big.setTextSize(20f);
            big.setTypeface(bold, Typeface.BOLD);
            big.setGravity(Gravity.CENTER);
            words.addView(big);
            TextView small = new TextView(a);
            small.setText("رأيك يرتّب لك ما تشاهده بعده");
            small.setTextColor(0x99FFFFFF);
            small.setTextSize(12.5f);
            small.setTypeface(face);
            small.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
            sl.topMargin = (int) (4 * d);
            words.addView(small, sl);
            LayoutParams wl = new LayoutParams(-1, -2, Gravity.BOTTOM);
            wl.bottomMargin = (int) (34 * d);
            addView(words, wl);
            words.setAlpha(0f);
            words.animate().alpha(1f).setStartDelay(520L).setDuration(260L).start();
        }

        void play() {
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(900L);
            a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator v) { t = ((Float) v.getAnimatedValue()).floatValue(); invalidate(); }
            });
            a.start();
        }

        @Override protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f, cy = getHeight() * 0.36f, r = 34 * d;
            p.setStrokeWidth(2.2f * d);
            float ring = Math.min(1f, t / 0.55f);
            c.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, 360f * ring, false, p);
            float k = t < 0.45f ? 0f : Math.min(1f, (t - 0.45f) / 0.35f);
            if (k > 0f) {
                tick.reset();
                float x1 = cx - 14 * d, y1 = cy + 1 * d, x2 = cx - 4 * d, y2 = cy + 11 * d, x3 = cx + 15 * d, y3 = cy - 10 * d;
                tick.moveTo(x1, y1);
                if (k < 0.4f) {
                    float q = k / 0.4f;
                    tick.lineTo(x1 + (x2 - x1) * q, y1 + (y2 - y1) * q);
                } else {
                    float q = (k - 0.4f) / 0.6f;
                    tick.lineTo(x2, y2);
                    tick.lineTo(x2 + (x3 - x2) * q, y2 + (y3 - y2) * q);
                }
                p.setStrokeWidth(3f * d);
                c.drawPath(tick, p);
            }
            float rays = t < 0.6f ? 0f : (t - 0.6f) / 0.4f;
            if (rays > 0f) {
                p.setStrokeWidth(1.6f * d);
                p.setAlpha((int) (255 * (1f - rays * 0.6f)));
                for (int i = 0; i < 12; i++) {
                    double ang = Math.toRadians(i * 30);
                    float a0 = r + 7 * d + 6 * d * rays, a1 = a0 + (i % 2 == 0 ? 9 : 5) * d;
                    c.drawLine(cx + (float) Math.cos(ang) * a0, cy + (float) Math.sin(ang) * a0,
                            cx + (float) Math.cos(ang) * a1, cy + (float) Math.sin(ang) * a1, p);
                }
                p.setAlpha(255);
            }
        }
    }
}
