package com.my.newproject;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** A movie collection (saga): every part in release order, what is already in the library, and one button to add the rest. */
public class WnCollectionActivity extends CgBase {

    static final String EXTRA_ID = "id";
    static final String EXTRA_NAME = "name";

    static void open(Activity a, long id, String name) {
        Intent in = new Intent(a, WnCollectionActivity.class);
        in.putExtra(EXTRA_ID, id);
        in.putExtra(EXTRA_NAME, name == null ? "" : name);
        a.startActivity(in);
    }

    private LinearLayout root;
    private JSONObject data;

    @Override
    protected String screenTitle() {
        String n = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_NAME);
        return n == null || n.length() == 0 ? "سلسلة أفلام" : n;
    }

    @Override
    protected void onBuild() {
        root = scrollBody();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (WnUi.dirty && data != null) load();
    }

    private void load() {
        root.removeAllViews();
        root.addView(CxUi.skeleton(this, 3));
        final long id = getIntent().getLongExtra(EXTRA_ID, 0);
        CgHttp.async(this, new CgHttp.Work<JSONObject>() {
            @Override public JSONObject run() throws Exception { return WnApi.collection(id); }
        }, new CgHttp.Done<JSONObject>() {
            @Override public void ok(JSONObject d) {
                if (!alive()) return;
                data = d;
                render();
            }

            @Override public void fail(String message) {
                if (!alive()) return;
                root.removeAllViews();
                root.addView(CgUi.errorCard(WnCollectionActivity.this, message, new Runnable() {
                    @Override public void run() { load(); }
                }));
            }
        });
    }

    private void render() {
        root.removeAllViews();
        LinearLayout head = WnUi.glass(this);
        head.addView(CgUi.text(this, data.optString("name"), 20, CgCfg.TEXT, true));
        if (data.optString("overview").length() > 0) head.addView(CgUi.muted(this, data.optString("overview")));
        head.addView(CgUi.text(this, data.optInt("total") + " جزء · صدر " + data.optInt("released") + " · في المكتبة " + data.optInt("in_library"), 13, CgCfg.CYAN, true));
        head.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        root.addView(head);

        final JSONArray pending = new JSONArray();
        JSONArray parts = data.optJSONArray("parts");
        for (int i = 0; parts != null && i < parts.length(); i++) {
            JSONObject p = parts.optJSONObject(i);
            if (p != null && !p.optBoolean("imported", false) && p.optBoolean("released", false)) pending.put(p);
        }
        TextView all = CgUi.button(this, pending.length() > 0 ? "إضافة السلسلة كاملة (" + pending.length() + " جزء)" : "كل الأجزاء المتاحة موجودة في المكتبة", 0);
        all.setAlpha(pending.length() > 0 ? 1f : 0.45f);
        all.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (pending.length() == 0) { CgUi.toast(WnCollectionActivity.this, "لا يوجد جزء جديد لإضافته."); return; }
                Intent in = new Intent(WnCollectionActivity.this, WnBulkActivity.class);
                in.putExtra(WnBulkActivity.EXTRA_CARDS, pending.toString());
                startActivity(in);
            }
        });
        root.addView(all, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 12));

        int cols = WnUi.columns(this);
        int w = WnUi.cardWidthPx(this, cols);
        LinearLayout row = null;
        int fill = 0;
        for (int i = 0; parts != null && i < parts.length(); i++) {
            final JSONObject p = parts.optJSONObject(i);
            if (p == null) continue;
            if (row == null || fill >= cols) {
                row = CgUi.hbox(this);
                row.setGravity(Gravity.TOP);
                for (int k = 0; k < cols; k++) {
                    FrameLayout slot = new FrameLayout(this);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
                    lp.setMargins(CgUi.dp(this, 5), 0, CgUi.dp(this, 5), 0);
                    row.addView(slot, lp);
                }
                root.addView(row, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
                fill = 0;
            }
            FrameLayout slot = (FrameLayout) row.getChildAt(fill++);
            View v = WnUi.posterCard(this, p, w, false, false, new Runnable() {
                @Override public void run() { WnDetailActivity.open(WnCollectionActivity.this, p); }
            }, null);
            slot.addView(v);
            WnUi.reveal(v, i);
        }
    }
}
