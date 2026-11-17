package com.Cigram.vid;

import android.app.Activity;
import android.os.Bundle;

import org.json.JSONObject;

/**
 * Invisible trampoline for a tap on a widget slide: reads the slide (id, type, title, pictures) and opens the film / series page
 * exactly like a tap on the Home carousel does, then closes itself. A slide that cannot be opened opens the app instead.
 */
public class CigramWidgetOpenActivity extends Activity {

    static final String EXTRA_ITEM = "cg_widget_item";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            String raw = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ITEM);
            JSONObject item = raw == null ? null : new JSONObject(raw);
            if (CigramWidgetData.openable(item)) {
                CigramStage1InitProvider.openTitleDetails(this, item, null);
            } else {
                startActivity(new android.content.Intent(this, MainActivity.class));
            }
        } catch (Throwable t) {
            android.util.Log.w("CigramWidget", "open failed", t);
            try { startActivity(new android.content.Intent(this, MainActivity.class)); } catch (Throwable ignored) { }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
