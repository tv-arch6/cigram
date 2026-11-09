package com.my.newproject;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;

/** "المسودات": every unfinished add-form saved on this device. Resume or delete. */
public class CxDraftsActivity extends CxBase {

    @Override
    protected String screenTitle() {
        return "المسودات";
    }

    @Override
    protected void onBuild() {
        initCx(null);
        setBottomVisible(false);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (page != null) render();
    }

    private void render() {
        page.removeAllViews();
        ArrayList<JSONObject> list = CxDraft.list(this, null);
        if (list.isEmpty()) {
            page.addView(CgUi.empty(this, "لا توجد مسودات", "أي نموذج تبدأ بتعبئته ولا تنشره يُحفظ هنا تلقائياً على جهازك."));
            return;
        }
        page.addView(CgUi.muted(this, "تُحفظ المسودات على هذا الجهاز فقط، وتُحذف تلقائياً بعد النشر."));
        page.addView(CgUi.space(this, 8));
        for (int i = 0; i < list.size(); i++) page.addView(row(list.get(i)));
    }

    private View row(final JSONObject d) {
        LinearLayout card = CgUi.card(this);
        LinearLayout top = CgUi.hbox(this);
        top.addView(CgUi.badge(this, CxDraft.kindLabel(d.optString("kind")), CgCfg.ACCENT), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        TextView ago = CgUi.muted(this, "  " + CxUi.agoMs(d.optLong("updated")));
        top.addView(ago, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        card.addView(top);
        String t = d.optString("title");
        TextView title = CgUi.text(this, t.length() > 0 ? t : "بدون عنوان", 15.5f, CgCfg.TEXT, true);
        title.setPadding(0, CgUi.dp(this, 6), 0, CgUi.dp(this, 8));
        card.addView(title);
        LinearLayout actions = CgUi.hbox(this);
        TextView go = CgUi.smallButton(this, "متابعة", 0);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CxNav.resumeDraft(CxDraftsActivity.this, d);
            }
        });
        actions.addView(go, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        TextView del = CgUi.smallButton(this, "حذف", 2);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUi.confirm(CxDraftsActivity.this, "حذف المسودة", "ستُحذف هذه المسودة نهائياً من الجهاز.", "حذف", true, new Runnable() {
                    @Override
                    public void run() {
                        CxDraft.delete(CxDraftsActivity.this, d.optString("id"));
                        render();
                    }
                });
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        lp.setMargins(CgUi.dp(this, 8), 0, 0, 0);
        actions.addView(del, lp);
        card.addView(actions);
        card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        return card;
    }
}
