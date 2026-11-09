package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.OrientationEventListener;
import android.view.View;

/**
 * v10.80: the player turns with the phone (landscape or portrait) without pressing anything when the phone's
 * auto-rotate is on, and the square button beside the total time (Cinemana style) switches between the two by
 * hand. With auto-rotate off the player starts in landscape and only the button changes it.
 *
 * After a manual switch the screen keeps that direction until the phone itself is turned to match it; then
 * automatic turning takes over again, so the button never fights the sensor.
 */
final class CigramPlayerOrientation {

    private static final int AUTO = ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
    private static final int LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
    private static final int PORTRAIT = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private OrientationEventListener sensor;
    private Boolean forcedPortrait;   // null = follows the phone
    private final Runnable onChanged;
    private int requested = -100;
    private int pendingKind;          // 1 portrait, 2 landscape, 0 none: what the phone looks to be doing
    private final Runnable apply = new Runnable() {
        @Override public void run() { follow(pendingKind); }
    };

    CigramPlayerOrientation(Activity activity, Runnable onChanged) {
        this.activity = activity;
        this.onChanged = onChanged;
    }

    static boolean autoRotateOn(Context c) {
        try {
            return Settings.System.getInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private void request(int orientation) {
        if (orientation == requested) return;
        requested = orientation;
        try { activity.setRequestedOrientation(orientation); } catch (Throwable ignored) { }
    }

    /**
     * First orientation of the screen. The phone's own auto-rotate setting is used when it is on (the system
     * turns the player). When it is off the player still follows how the phone is held (a video screen should
     * not stay sideways in a hand held upright), starting in landscape; the button turns it by hand either way.
     */
    void start() {
        current = this;
        request(autoRotateOn(activity) ? AUTO : LANDSCAPE);
        try {
            sensor = new OrientationEventListener(activity) {
                @Override public void onOrientationChanged(int degrees) {
                    if (degrees == ORIENTATION_UNKNOWN) return;
                    boolean devicePortrait = degrees < 30 || degrees > 330 || (degrees > 150 && degrees < 210);
                    boolean deviceLandscape = (degrees > 60 && degrees < 120) || (degrees > 240 && degrees < 300);
                    int kind = devicePortrait ? 1 : deviceLandscape ? 2 : 0;
                    if (kind == 0) return;   // in between: nothing decided
                    if (forcedPortrait != null) {
                        // after a manual switch the screen stays until the phone itself matches it
                        if ((forcedPortrait.booleanValue() && kind == 1) || (!forcedPortrait.booleanValue() && kind == 2)) {
                            forcedPortrait = null;
                        } else {
                            return;
                        }
                    }
                    if (kind != pendingKind) {
                        pendingKind = kind;
                        handler.removeCallbacks(apply);
                        handler.postDelayed(apply, 450L);   // held steady for a moment: not a passing tilt
                    }
                }
            };
            if (sensor.canDetectOrientation()) sensor.enable();
        } catch (Throwable ignored) { }
    }

    private void follow(int kind) {
        if (forcedPortrait != null || kind == 0) return;
        if (autoRotateOn(activity)) {
            request(AUTO);                       // the system turns it
        } else {
            request(kind == 1 ? PORTRAIT : LANDSCAPE);   // locked by the user: follow the hold ourselves
        }
    }

    private static CigramPlayerOrientation current;

    /** After a full-screen ad (which forced landscape): the screen follows the phone again. */
    static void afterAd() {
        final CigramPlayerOrientation c = current;
        if (c == null) return;
        c.pendingKind = 0;
        c.requested = -100;
        c.request(autoRotateOn(c.activity) ? AUTO : LANDSCAPE);
    }

    void stop() {
        if (current == this) current = null;
        try { handler.removeCallbacks(apply); } catch (Throwable ignored) { }
        try { if (sensor != null) sensor.disable(); } catch (Throwable ignored) { }
    }

    boolean isPortrait() {
        return activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    /** The button: portrait <-> landscape. */
    void toggle() {
        try {
            boolean toPortrait = !isPortrait();
            forcedPortrait = Boolean.valueOf(toPortrait);
            request(toPortrait ? PORTRAIT : LANDSCAPE);
            if (onChanged != null) onChanged.run();
        } catch (Throwable ignored) { }
    }

    // ================================================================== the square button

    /** A rounded square with four corner brackets: pointing outwards in portrait (enlarge), inwards in landscape (shrink). */
    static final class Button extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF box = new RectF();
        private boolean enlarge = true;

        Button(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(0xFFFFFFFF);
            stroke.setStrokeWidth(2f * d);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            frame.setStyle(Paint.Style.STROKE);
            frame.setColor(0x66FFFFFF);
            frame.setStrokeWidth(1.2f * d);
            setClickable(true);
            setFocusable(true);
            setContentDescription("تدوير الشاشة");
        }

        /** {@code enlarge}: true shows "go full / wide" brackets, false shows "go back" brackets. */
        void setEnlarge(boolean enlarge) {
            if (this.enlarge != enlarge) {
                this.enlarge = enlarge;
                invalidate();
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float d = getResources().getDisplayMetrics().density;
            float side = Math.min(w, h) - 4f * d;
            float left = (w - side) / 2f, top = (h - side) / 2f;
            box.set(left, top, left + side, top + side);
            canvas.drawRoundRect(box, 8f * d, 8f * d, frame);
            float pad = side * 0.24f, arm = side * 0.2f;
            float l = left + pad, t = top + pad, r = left + side - pad, b = top + side - pad;
            path.reset();
            if (enlarge) {
                // corners open outwards
                path.moveTo(l, t + arm); path.lineTo(l, t); path.lineTo(l + arm, t);
                path.moveTo(r - arm, t); path.lineTo(r, t); path.lineTo(r, t + arm);
                path.moveTo(r, b - arm); path.lineTo(r, b); path.lineTo(r - arm, b);
                path.moveTo(l + arm, b); path.lineTo(l, b); path.lineTo(l, b - arm);
            } else {
                // corners point inwards
                path.moveTo(l, t + arm); path.lineTo(l + arm, t + arm); path.lineTo(l + arm, t);
                path.moveTo(r - arm, t); path.lineTo(r - arm, t + arm); path.lineTo(r, t + arm);
                path.moveTo(r, b - arm); path.lineTo(r - arm, b - arm); path.lineTo(r - arm, b);
                path.moveTo(l + arm, b); path.lineTo(l + arm, b - arm); path.lineTo(l, b - arm);
            }
            canvas.drawPath(path, stroke);
        }
    }
}
