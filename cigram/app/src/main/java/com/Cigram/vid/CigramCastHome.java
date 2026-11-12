package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.Map;
import java.util.WeakHashMap;

public final class CigramCastHome {
    private static final String TAG = "CigramCastHome";
    private static final String VIEW_TAG = "cigram_home_cast_button";
    private static final int DISCONNECTED = Color.WHITE;
    private static final int CONNECTED = 0xFFE21B14;
    private static final Map<Activity, Holder> HOLDERS = new WeakHashMap<Activity, Holder>();

    private CigramCastHome() {
    }

    public static void bind(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (!"MainActivity".equals(activity.getClass().getSimpleName())) return;
        // v2.6: the home cast button is the round floating one from CigramCastDetails
        CigramCastDetails.bind(activity);
        if (true) return;
        try {
            Holder old = HOLDERS.get(activity);
            if (old != null) {
                old.button.bringToFront();
                old.updateConnectedState();
                return;
            }

            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup) content;
            View existing = root.findViewWithTag(VIEW_TAG);
            if (existing != null) {
                existing.bringToFront();
                return;
            }

            final androidx.mediarouter.app.MediaRouteButton button =
                    new androidx.mediarouter.app.MediaRouteButton(activity);
            button.setTag(VIEW_TAG);
            button.setContentDescription("البث إلى التلفاز");
            button.setClickable(true);
            button.setFocusable(true);
            button.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));

            button.setBackground(null);

            com.google.android.gms.cast.framework.CastButtonFactory
                    .setUpMediaRouteButton(activity, button);

            final CastGlyphDrawable glyph = new CastGlyphDrawable(DISCONNECTED);
            button.setRemoteIndicatorDrawable(glyph);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    dp(activity, 50),
                    dp(activity, 50),
                    Gravity.TOP | Gravity.RIGHT
            );
            lp.rightMargin = dp(activity, 72);
            lp.topMargin = statusBarHeight(activity) + dp(activity, 8);

            if (root instanceof FrameLayout) {
                ((FrameLayout) root).addView(button, lp);
            } else {
                FrameLayout overlay = new FrameLayout(activity);
                overlay.setTag("cigram_home_cast_overlay");
                root.addView(overlay, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                overlay.addView(button, lp);
            }
            button.bringToFront();

            Holder holder = new Holder(activity, button, glyph);
            HOLDERS.put(activity, holder);
            holder.start();
        } catch (Throwable error) {
            Log.w(TAG, "bind failed", error);
        }
    }

    public static void unbind(Activity activity) {
        if (activity == null) return;
        Holder holder = HOLDERS.remove(activity);
        if (holder != null) holder.stop();
    }

    public static void hidePlayerCast(Activity activity) {
        if (activity == null) return;
        try {
            View root = activity.findViewById(android.R.id.content);
            hideCastViews(activity, root);
        } catch (Throwable error) {
            Log.w(TAG, "hidePlayerCast failed", error);
        }
    }

    private static boolean hideCastViews(Activity activity, View view) {
        if (view == null) return false;

        boolean isCast = view instanceof androidx.mediarouter.app.MediaRouteButton;
        if (!isCast && view.getId() != View.NO_ID) {
            try {
                String name = activity.getResources().getResourceEntryName(view.getId());
                if (name != null) {
                    String n = name.toLowerCase(java.util.Locale.ROOT);
                    isCast = n.contains("cast") || n.contains("media_route");
                }
            } catch (Throwable error) {
                Log.w(TAG, "resource name lookup failed", error);
            }
        }

        if (!isCast) {
            CharSequence cd = view.getContentDescription();
            if (cd != null) {
                String d = String.valueOf(cd).toLowerCase(java.util.Locale.ROOT);
                isCast = d.contains("cast") || d.contains("بث") || d.contains("تلفاز");
            }
        }

        if (isCast) {
            view.setBackground(null);
            view.setAlpha(0f);
            view.setVisibility(View.GONE);
            view.setEnabled(false);
            return true;
        }

        boolean containsCast = false;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hideCastViews(activity, group.getChildAt(i))) {
                    containsCast = true;
                }
            }

            if (containsCast && isSmallCastContainer(activity, group)) {
                group.setBackground(null);
                group.setAlpha(0f);
                group.setVisibility(View.GONE);
                group.setEnabled(false);
            }
        }
        return containsCast;
    }

    private static boolean isSmallCastContainer(Context context, View view) {
        int limit = dp(context, 96);
        int width = view.getWidth();
        int height = view.getHeight();
        if (width > 0 && height > 0 && width <= limit && height <= limit) {
            return true;
        }

        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp == null) return false;
        return lp.width > 0 && lp.height > 0
                && lp.width <= limit && lp.height <= limit;
    }

    private static int statusBarHeight(Context context) {
        try {
            int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return context.getResources().getDimensionPixelSize(id);
        } catch (Throwable error) {
            Log.w(TAG, "statusBarHeight failed", error);
        }
        return 0;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class Holder {
        private final Activity activity;
        private final androidx.mediarouter.app.MediaRouteButton button;
        private final CastGlyphDrawable glyph;
        private com.google.android.gms.cast.framework.CastContext castContext;
        private com.google.android.gms.cast.framework.SessionManagerListener<
                com.google.android.gms.cast.framework.CastSession> listener;

        Holder(Activity activity,
               androidx.mediarouter.app.MediaRouteButton button,
               CastGlyphDrawable glyph) {
            this.activity = activity;
            this.button = button;
            this.glyph = glyph;
        }

        void start() {
            try {
                castContext = com.google.android.gms.cast.framework.CastContext
                        .getSharedInstance(activity.getApplicationContext());
                listener = new com.google.android.gms.cast.framework.SessionManagerListener<
                        com.google.android.gms.cast.framework.CastSession>() {
                    @Override public void onSessionStarting(
                            com.google.android.gms.cast.framework.CastSession session) {
                        updateConnectedState();
                    }
                    @Override public void onSessionStarted(
                            com.google.android.gms.cast.framework.CastSession session,
                            String sessionId) {
                        updateConnectedState();
                    }
                    @Override public void onSessionStartFailed(
                            com.google.android.gms.cast.framework.CastSession session,
                            int error) {
                        updateConnectedState();
                    }
                    @Override public void onSessionEnding(
                            com.google.android.gms.cast.framework.CastSession session) {
                        updateConnectedState();
                    }
                    @Override public void onSessionEnded(
                            com.google.android.gms.cast.framework.CastSession session,
                            int error) {
                        updateConnectedState();
                    }
                    @Override public void onSessionResuming(
                            com.google.android.gms.cast.framework.CastSession session,
                            String sessionId) {
                        updateConnectedState();
                    }
                    @Override public void onSessionResumed(
                            com.google.android.gms.cast.framework.CastSession session,
                            boolean wasSuspended) {
                        updateConnectedState();
                    }
                    @Override public void onSessionResumeFailed(
                            com.google.android.gms.cast.framework.CastSession session,
                            int error) {
                        updateConnectedState();
                    }
                    @Override public void onSessionSuspended(
                            com.google.android.gms.cast.framework.CastSession session,
                            int reason) {
                        updateConnectedState();
                    }
                };
                castContext.getSessionManager().addSessionManagerListener(
                        listener,
                        com.google.android.gms.cast.framework.CastSession.class
                );
            } catch (Throwable error) {
                Log.w(TAG, "Cast session listener setup failed", error);
            }
            updateConnectedState();
        }

        void updateConnectedState() {
            try {
                boolean connected = false;
                if (castContext != null) {
                    com.google.android.gms.cast.framework.CastSession session =
                            castContext.getSessionManager().getCurrentCastSession();
                    connected = session != null && session.isConnected();
                }
                glyph.setColor(connected ? CONNECTED : DISCONNECTED);
                button.invalidate();
                button.setContentDescription(
                        connected ? "متصل بالتلفاز" : "البث إلى التلفاز");
            } catch (Throwable error) {
                Log.w(TAG, "updateConnectedState failed", error);
            }
        }

        void stop() {
            try {
                if (castContext != null && listener != null) {
                    castContext.getSessionManager().removeSessionManagerListener(
                            listener,
                            com.google.android.gms.cast.framework.CastSession.class
                    );
                }
            } catch (Throwable error) {
                Log.w(TAG, "Cast session listener removal failed", error);
            }
            listener = null;
            castContext = null;
        }
    }

    private static final class CastGlyphDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color;

        CastGlyphDrawable(int color) {
            this.color = color;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColor(int color) {
            this.color = color;
            invalidateSelf();
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float w = b.width();
            float h = b.height();
            float s = Math.min(w, h);
            float left = b.left + (w - s) / 2f;
            float top = b.top + (h - s) / 2f;
            paint.setColor(color);
            paint.setStrokeWidth(Math.max(2f, s * 0.075f));
            RectF screen = new RectF(
                    left + s * 0.20f,
                    top + s * 0.18f,
                    left + s * 0.82f,
                    top + s * 0.70f);
            canvas.drawRoundRect(screen, s * 0.06f, s * 0.06f, paint);
            RectF arc1 = new RectF(
                    left + s * 0.08f,
                    top + s * 0.50f,
                    left + s * 0.48f,
                    top + s * 0.90f);
            RectF arc2 = new RectF(
                    left + s * 0.08f,
                    top + s * 0.62f,
                    left + s * 0.34f,
                    top + s * 0.88f);
            canvas.drawArc(arc1, 270f, 90f, false, paint);
            canvas.drawArc(arc2, 270f, 90f, false, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(left + s * 0.16f, top + s * 0.82f, s * 0.045f, paint);
            paint.setStyle(Paint.Style.STROKE);
        }

        @Override public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
