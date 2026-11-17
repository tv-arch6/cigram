package com.my.newproject;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * Unified content center: central search (name / ids / slug / season-episode / links / section / notes),
 * filters, color status with reasons, paginated list, multi-select and bulk edit.
 */
public class CgContentActivity extends CgBase {

    private static final int PAGE = 40;

    private final CgData.Filter filter = new CgData.Filter();
    private final LinkedHashSet<String> selected = new LinkedHashSet<String>();
    private ArrayList<JSONObject> results = new ArrayList<JSONObject>();
    private int shown;
    private boolean selecting;
    private boolean built;

    private EditText search;
    private LinearLayout list;
    private TextView countText;
    private TextView moreButton;
    private LinearLayout selectionBar;
    private TextView selectionCount;
    private TextView sectionChip;
    private final ArrayList<TextView> kindChips = new ArrayList<TextView>();
    private TextView chipNoSub;
    private TextView chipNoVideo;
    private TextView chipErrors;
    private TextView chipFeatured;
    private TextView chipHidden;
    private TextView chipPublished;
    private final Runnable searchRunner = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };

    @Override
    protected String screenTitle() {
        return "المحتوى";
    }

    @Override
    protected void onBuild() {
        Intent in = getIntent();
        filter.kind = in.getIntExtra("kind", -1);
        String preset = in.getStringExtra("preset");
        if ("no_subtitle".equals(preset)) filter.noSubtitle = true;
        else if ("errors".equals(preset)) filter.errorsOnly = true;
        else if ("featured".equals(preset)) filter.featuredOnly = true;
        else if ("hidden".equals(preset)) filter.hiddenOnly = true;
        String sec = in.getStringExtra("section");
        if (sec != null) filter.sectionId = sec;

        bar.action("تحديد", new Runnable() {
            @Override
            public void run() {
                setSelecting(!selecting);
            }
        });
        bar.action("تحديث", new Runnable() {
            @Override
            public void run() {
                reloadData(new Runnable() {
                    @Override
                    public void run() {
                        buildUi();
                    }
                });
            }
        });
        ensureData(new Runnable() {
            @Override
            public void run() {
                buildUi();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (built && CgData.loaded()) refresh();
    }

    @Override
    public void onBackPressed() {
        if (selecting) {
            setSelecting(false);
            return;
        }
        super.onBackPressed();
    }

    // ----------------------------------------------------------------- UI

    private void buildUi() {
        body.removeAllViews();
        kindChips.clear();
        LinearLayout head = CgUi.vbox(this);
        head.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 10), CgUi.dp(this, 12), 0);

        View warn = warningCard();
        if (warn != null) head.addView(warn);

        search = CgUi.field(this, "بحث: الاسم، ID، IMDb/TMDB، series_id، Slug، S02E04، الرابط، القسم، الملاحظات", false);
        String initialQuery = getIntent().getStringExtra("q");
        if (initialQuery != null && initialQuery.length() > 0) search.setText(initialQuery);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable e) {
                ui().removeCallbacks(searchRunner);
                ui().postDelayed(searchRunner, 250);
            }
        });
        head.addView(search, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        CgUi.ChipBar kinds = new CgUi.ChipBar(this);
        String[] kn = {"الكل", "أفلام", "مسلسلات", "حلقات"};
        for (int i = 0; i < kn.length; i++) {
            final int kindValue = i - 1;
            final TextView chip = kinds.add(kn[i], filter.kind == kindValue);
            kindChips.add(chip);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    filter.kind = kindValue;
                    syncChips();
                    refresh();
                }
            });
        }
        head.addView(kinds.scroll, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        CgUi.ChipBar flags = new CgUi.ChipBar(this);
        chipNoSub = flags.add("بدون ترجمة", filter.noSubtitle);
        chipNoVideo = flags.add("بدون فيديو", filter.noVideo);
        chipErrors = flags.add("أخطاء فقط", filter.errorsOnly);
        chipFeatured = flags.add("مميز فقط", filter.featuredOnly);
        chipHidden = flags.add("مخفي", filter.hiddenOnly);
        chipPublished = flags.add("منشور", filter.publishedOnly);
        sectionChip = flags.add("القسم: الكل", false);
        chipNoSub.setOnClickListener(flagClick(0));
        chipNoVideo.setOnClickListener(flagClick(1));
        chipErrors.setOnClickListener(flagClick(2));
        chipFeatured.setOnClickListener(flagClick(3));
        chipHidden.setOnClickListener(flagClick(4));
        chipPublished.setOnClickListener(flagClick(5));
        sectionChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseSection();
            }
        });
        head.addView(flags.scroll, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        countText = CgUi.muted(this, "");
        countText.setPadding(CgUi.dp(this, 4), CgUi.dp(this, 6), CgUi.dp(this, 4), CgUi.dp(this, 6));
        head.addView(countText, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        body.addView(head, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        list = CgUi.vbox(this);
        list.setPadding(CgUi.dp(this, 12), 0, CgUi.dp(this, 12), CgUi.dp(this, 90));
        sv.addView(list, new ScrollView.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        body.addView(sv, new LinearLayout.LayoutParams(CgUi.MATCH, 0, 1f));

        selectionBar = CgUi.hbox(this);
        selectionBar.setBackgroundColor(CgCfg.CARD);
        selectionBar.setPadding(CgUi.dp(this, 10), CgUi.dp(this, 8), CgUi.dp(this, 10), CgUi.dp(this, 8));
        selectionCount = CgUi.text(this, "", 13, CgCfg.TEXT, true);
        selectionBar.addView(selectionCount, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        TextView all = CgUi.smallButton(this, "تحديد الكل", 1);
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (int i = 0; i < results.size(); i++) {
                    String id = CgData.s(results.get(i), "id");
                    if (id.length() > 0) selected.add(id);
                }
                renderAll();
            }
        });
        selectionBar.addView(all, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 0, 4, 0));
        TextView act = CgUi.smallButton(this, "إجراء جماعي", 0);
        act.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgBulk.show(CgContentActivity.this, new ArrayList<String>(selected), new Runnable() {
                    @Override
                    public void run() {
                        selected.clear();
                        setSelecting(false);
                    }
                });
            }
        });
        selectionBar.addView(act, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 0, 4, 0));
        TextView cancel = CgUi.smallButton(this, "إلغاء", 3);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSelecting(false);
            }
        });
        selectionBar.addView(cancel, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        selectionBar.setVisibility(View.GONE);
        body.addView(selectionBar, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        built = true;
        updateSectionChip();
        refresh();
    }

    private View.OnClickListener flagClick(final int which) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (which == 0) filter.noSubtitle = !filter.noSubtitle;
                else if (which == 1) filter.noVideo = !filter.noVideo;
                else if (which == 2) filter.errorsOnly = !filter.errorsOnly;
                else if (which == 3) filter.featuredOnly = !filter.featuredOnly;
                else if (which == 4) {
                    filter.hiddenOnly = !filter.hiddenOnly;
                    if (filter.hiddenOnly) filter.publishedOnly = false;
                } else if (which == 5) {
                    filter.publishedOnly = !filter.publishedOnly;
                    if (filter.publishedOnly) filter.hiddenOnly = false;
                }
                syncChips();
                refresh();
            }
        };
    }

    private void syncChips() {
        for (int i = 0; i < kindChips.size(); i++) CgUi.setChipState(kindChips.get(i), filter.kind == i - 1);
        CgUi.setChipState(chipNoSub, filter.noSubtitle);
        CgUi.setChipState(chipNoVideo, filter.noVideo);
        CgUi.setChipState(chipErrors, filter.errorsOnly);
        CgUi.setChipState(chipFeatured, filter.featuredOnly);
        CgUi.setChipState(chipHidden, filter.hiddenOnly);
        CgUi.setChipState(chipPublished, filter.publishedOnly);
    }

    private void updateSectionChip() {
        if (filter.sectionId.length() == 0) {
            sectionChip.setText("القسم: الكل");
            CgUi.setChipState(sectionChip, false);
        } else {
            sectionChip.setText("القسم: " + CgData.cur().sectionName(filter.sectionId));
            CgUi.setChipState(sectionChip, true);
        }
    }

    private void chooseSection() {
        final ArrayList<JSONObject> secs = CgData.cur().sections;
        CharSequence[] labels = new CharSequence[secs.size() + 1];
        labels[0] = "كل الأقسام";
        for (int i = 0; i < secs.size(); i++) labels[i + 1] = CgData.s(secs.get(i), "name");
        CgUi.choose(this, "تصفية حسب القسم", labels, new CgUi.IntCb() {
            @Override
            public void run(int index) {
                filter.sectionId = index == 0 ? "" : CgData.s(secs.get(index - 1), "id");
                updateSectionChip();
                refresh();
            }
        });
    }

    // ----------------------------------------------------------- results

    private void refresh() {
        if (!built || !alive()) return;
        CgData d = CgData.cur();
        if (d == null) return;
        results = d.query(search.getText().toString(), filter);
        // drop selections that no longer exist
        java.util.Iterator<String> it = selected.iterator();
        while (it.hasNext()) {
            if (d.byId(it.next()) == null) it.remove();
        }
        renderAll();
    }

    private void renderAll() {
        list.removeAllViews();
        shown = 0;
        moreButton = null;
        countText.setText("النتائج: " + results.size() + " من " + CgData.cur().items.size());
        updateSelectionUi();
        if (results.isEmpty()) {
            list.addView(CgUi.empty(this, "لا توجد نتائج", "غيّر كلمات البحث أو أزل بعض عوامل التصفية."));
            return;
        }
        renderMore();
    }

    private void renderMore() {
        final CgData d = CgData.cur();
        if (moreButton != null) list.removeView(moreButton);
        int end = Math.min(results.size(), shown + PAGE);
        for (int i = shown; i < end; i++) {
            final JSONObject it = results.get(i);
            final String id = CgData.s(it, "id");
            boolean sel = selecting && selected.contains(id);
            View row = CgRows.itemRow(this, d, it, selecting, sel, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (selecting) {
                        toggle(id);
                    } else {
                        open(it);
                    }
                }
            }, new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (id.length() == 0) return true;
                    if (!selecting) {
                        selecting = true;
                        selected.clear();
                        selected.add(id);
                        renderAll();
                    } else {
                        toggle(id);
                    }
                    return true;
                }
            });
            list.addView(row, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
        shown = end;
        if (shown < results.size()) {
            moreButton = CgUi.button(this, "عرض المزيد (" + (results.size() - shown) + " متبقٍ)", 1);
            moreButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    renderMore();
                }
            });
            list.addView(moreButton, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 8));
        }
    }

    private void toggle(String id) {
        if (id.length() == 0) return;
        if (selected.contains(id)) selected.remove(id);
        else selected.add(id);
        if (selected.isEmpty()) {
            setSelecting(false);
            return;
        }
        // keep scroll position: re-render only the first `shown` rows
        int keep = shown;
        list.removeAllViews();
        shown = 0;
        moreButton = null;
        while (shown < keep && shown < results.size()) renderMoreKeep(keep);
        updateSelectionUi();
    }

    private void renderMoreKeep(int keep) {
        int oldPage = shown;
        // render up to `keep` rows in a single pass
        int target = Math.min(results.size(), keep);
        final CgData d = CgData.cur();
        for (int i = oldPage; i < target; i++) {
            final JSONObject it = results.get(i);
            final String id = CgData.s(it, "id");
            View row = CgRows.itemRow(this, d, it, selecting, selecting && selected.contains(id), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (selecting) toggle(id);
                    else open(it);
                }
            }, new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (selecting) toggle(id);
                    return true;
                }
            });
            list.addView(row, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
        shown = target;
        if (shown < results.size()) {
            moreButton = CgUi.button(this, "عرض المزيد (" + (results.size() - shown) + " متبقٍ)", 1);
            moreButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    renderMore();
                }
            });
            list.addView(moreButton, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 8));
        }
    }

    private void setSelecting(boolean on) {
        selecting = on;
        if (!on) selected.clear();
        if (built) renderAll();
    }

    private void updateSelectionUi() {
        if (selectionBar == null) return;
        selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selectionCount.setText("المحدد: " + selected.size());
        selectionCount.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    }

    private void open(JSONObject it) {
        String id = CgData.s(it, "id");
        if (id.length() == 0) {
            CgUi.info(this, "لا يمكن الفتح", "هذا العنصر بلا ID في movies.json، لذلك لا يمكن تعديله من هنا.");
            return;
        }
        Intent i = new Intent(this, CgData.kind(it) == CgData.K_SERIES ? CgSeriesActivity.class : CgItemActivity.class);
        i.putExtra("id", id);
        startActivity(i);
    }
}
