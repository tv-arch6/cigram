package com.my.newproject;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Cigram Studio 4 — one search for everything: the tools of the Studio (Arabic or English, spelling-forgiving, see
 * {@link StudioFind}) and the titles of the catalogue (same matching as «كل المحتوى»). With nothing typed it shows what
 * was opened last and the six rooms.
 */
public class StudioSearchActivity extends CgBase {

    private static final int MAX_TOOLS = 8;
    private static final int MAX_TITLES = 14;

    private EditText field;
    private LinearLayout results;
    private String shown = "\u0000";

    private final Runnable run = new Runnable() {
        @Override public void run() {
            if (alive()) render(field.getText().toString());
        }
    };

    @Override
    protected String screenTitle() {
        return "بحث في الاستوديو";
    }

    @Override
    protected void onBuild() {
        body.setBackgroundColor(StudioUi.INK);
        field = CgUi.field(this, "اسم أداة أو عنوان…", false);
        field.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        field.setTextDirection(View.TEXT_DIRECTION_RTL);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        body.addView(field, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 14, 8, 14, 8));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        results = CgUi.vbox(this);
        int p = CgUi.dp(this, 14);
        results.setPadding(p, 0, p, CgUi.dp(this, 28));
        sv.addView(results, new ScrollView.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        body.addView(sv, new LinearLayout.LayoutParams(CgUi.MATCH, 0, 1f));

        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                ui().removeCallbacks(run);
                ui().postDelayed(run, 110);
            }
        });
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        field.requestFocus();
        render("");
        // the catalogue may not be in memory yet (the admin came straight here): load it once, then show titles too
        CgData.loadIfStale(this, 5L * 60000L, new CgHttp.Done<CgData>() {
            @Override public void ok(CgData d) { if (alive()) { shown = "\u0000"; render(field.getText().toString()); } }
            @Override public void fail(String message) { }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        shown = "\u0000";                                         // recents / badges may have changed while away
        render(field == null ? "" : field.getText().toString());
    }

    // ------------------------------------------------------------------ results

    private void render(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.equals(shown)) return;
        shown = q;
        results.removeAllViews();
        if (q.length() == 0) {
            renderEmpty();
            return;
        }
        List<StudioMap.Tool> tools = StudioFind.tools(q, MAX_TOOLS);
        if (!tools.isEmpty()) {
            results.addView(StudioUi.label(this, "الأدوات"));
            LinearLayout g = StudioUi.group(this);
            for (int i = 0; i < tools.size(); i++) toolRow(g, tools.get(i));
            results.addView(g);
        }
        CgData d = CgData.cur();
        int titles = 0;
        if (d != null) {
            ArrayList<JSONObject> found = d.query(q, null);
            if (!found.isEmpty()) {
                results.addView(StudioUi.label(this, "العناوين"
                        + (found.size() > MAX_TITLES ? " (" + MAX_TITLES + " من " + found.size() + ")" : " (" + found.size() + ")")));
                LinearLayout g = StudioUi.group(this);
                for (int i = 0; i < found.size() && i < MAX_TITLES; i++) titleRow(g, d, found.get(i));
                results.addView(g);
                titles = found.size();
            }
        } else {
            results.addView(CgUi.muted(this, "المكتبة قيد التحميل… ستظهر العناوين بعد لحظات."));
        }
        if (tools.isEmpty() && titles == 0 && d != null) {
            results.addView(CgUi.empty(this, "لا نتائج", "جرّب كلمة أقصر أو اسماً آخر"));
        }
    }

    private void renderEmpty() {
        List<String> recent = StudioPrefs.recents(this);
        if (!recent.isEmpty()) {
            results.addView(StudioUi.label(this, "فُتحت مؤخراً"));
            LinearLayout g = StudioUi.group(this);
            for (int i = 0; i < recent.size(); i++) {
                StudioMap.Tool t = StudioMap.byId(recent.get(i));
                if (t != null) toolRow(g, t);
            }
            results.addView(g);
        }
        results.addView(StudioUi.label(this, "غرف الاستوديو"));
        LinearLayout rooms = StudioUi.group(this);
        for (int i = 0; i < StudioMap.ROOMS.length; i++) {
            final StudioMap.Room r = StudioMap.ROOMS[i];
            StudioUi.row(rooms, r.icon, r.title, r.sub, StudioMap.inRoom(r.index).size() + " أداة", CgCfg.TEXT, new Runnable() {
                @Override public void run() { StudioGo.openRoom(StudioSearchActivity.this, r.index); }
            });
        }
        results.addView(rooms);
    }

    private void toolRow(LinearLayout g, StudioMap.Tool t) {
        StudioUi.row(g, t.icon, t.title, t.sub, StudioGo.badge(this, t), CgCfg.TEXT, StudioGo.opener(this, t));
    }

    private void titleRow(LinearLayout g, CgData d, final JSONObject it) {
        int kind = CgData.kind(it);
        String title = CgData.s(it, "title");
        if (title.length() == 0) title = CgData.s(it, "id");
        String sub = CgData.kindLabel(kind);
        if (kind == CgData.K_EPISODE) {
            JSONObject head = d.headerOf(it);
            sub += " · " + CgData.epCode(it) + (head == null ? "" : " · " + CgData.s(head, "title"));
        }
        int icon = kind == CgData.K_SERIES ? CgIcons.TV : kind == CgData.K_EPISODE ? CgIcons.PLAY : CgIcons.FILM;
        final String id = CgData.s(it, "id");
        StudioUi.row(g, icon, title, sub, "", CgCfg.TEXT, new Runnable() {
            @Override public void run() {
                if (id.length() > 0) CxNav.openItem(StudioSearchActivity.this, id);
                else CgUi.info(StudioSearchActivity.this, "لا يمكن الفتح", "هذا العنصر بلا ID في movies.json.");
            }
        });
    }
}
