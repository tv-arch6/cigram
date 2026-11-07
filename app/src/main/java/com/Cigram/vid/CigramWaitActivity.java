package com.Cigram.vid;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * v10.36: the mandatory wait screen shown after «شاهد هَسه» / an episode press, before the
 * rewarded ad. Plain black, always portrait (vertical; "portrait" in the manifest), with only
 * "Please wait (5)..." in the middle counting down 5, 4, 3, 2, 1. No buttons and the back button
 * does nothing: the viewer waits, the ad starts by itself on this screen, then the title opens.
 *
 * The countdown, the ad and the decisions live in {@link CigramRewardUnlock}; this class is only
 * the screen. It closes itself when there is no request to wait for (e.g. the process was
 * restarted while it was open).
 */
public class CigramWaitActivity extends Activity {
    private TextView label;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable ignored) { }
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(0xFF000000);
        label = new TextView(this);
        label.setText("Please wait (5)...");
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(22f);
        label.setGravity(Gravity.CENTER);
        label.setTypeface(CigramTypefaces.bold());
        box.addView(label, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(box);
        hideSystemBars();
        if (!CigramRewardUnlock.attachHost(this, label)) finish();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    /** Mandatory: back does nothing while a request is waiting (a stale screen can still be left). */
    @Override public void onBackPressed() {
        if (!CigramRewardUnlock.isHostActive(this)) finish();
    }

    @Override protected void onDestroy() {
        CigramRewardUnlock.hostDestroyed(this);
        super.onDestroy();
    }

    private void hideSystemBars() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Throwable ignored) { }
    }
}
