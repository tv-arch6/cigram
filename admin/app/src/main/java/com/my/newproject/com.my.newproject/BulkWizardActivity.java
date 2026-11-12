package com.my.newproject;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * معالج الرفع الجماعي — Bulk Upload Wizard (Studio 3.0).
 *
 * Three steps on one screen: 1) what are you uploading, 2) pick all the files at once, 3) the plan
 * ({@link BulkPlan}): what each file is, which episode, what is missing, doubled or wrong — BEFORE anything is sent.
 * Then the files go to the screen that owns the upload: a season to «رفع موسم من الجوال» (resumable upload,
 * analysis, publish, undo) with the files already loaded; season subtitles to «ترجمات موسم كامل»; films one by one
 * to «إضافة فيلم» with the cleaned title on the clipboard.
 */
public class BulkWizardActivity extends CgBase {

    private static final int REQ_FILES = 731;
    private static final int MODE_SEASON = 0, MODE_FILMS = 1, MODE_SUBS = 2;

    private int mode = MODE_SEASON;
    private final ArrayList<BulkPlan.Item> picked = new ArrayList<BulkPlan.Item>();
    private BulkPlan.Plan plan;
    private LinearLayout stepBox;

    @Override protected String screenTitle() { return "معالج الرفع الجماعي"; }

    @Override protected void onBuild() {
        LinearLayout c = scrollBody();
        stepBox = CgUi.vbox(this);
        c.addView(stepBox);
        showChooser();
    }

    private TextView stepTitle(String n, String text) {
        TextView t = CgUi.text(this, n + "  " + text, 16, CgCfg.TEXT, true);
        t.setGravity(Gravity.RIGHT);
        t.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 4, 6, 4, 12));
        return t;
    }

    private void showChooser() {
        stepBox.removeAllViews();
        stepBox.addView(stepTitle("١", "ماذا ترفع؟"));
        stepBox.addView(StudioUi.pair(this,
                StudioUi.deck(this, CgIcons.TV, "موسم مسلسل", "حلقات (وترجماتها) تُطابق بأرقامها", StudioUi.ORANGE, new Runnable() { @Override public void run() { choose(MODE_SEASON); } }),
                StudioUi.deck(this, CgIcons.FILM, "عدة أفلام", "يُستخرج الاسم والسنة من كل ملف", StudioUi.SKY, new Runnable() { @Override public void run() { choose(MODE_FILMS); } })));
        stepBox.addView(StudioUi.deck(this, CgIcons.FILE, "ترجمات موسم", "ملفات SRT / VTT أو حزمة ZIP لموسم موجود", StudioUi.MINT, new Runnable() {
            @Override public void run() { startActivity(new Intent(BulkWizardActivity.this, CgSeasonSubsActivity.class)); }
        }));
        TextView note = CgUi.muted(this, "المعالج لا يرفع شيئاً قبل أن ترى الخطة كاملة. الرفع نفسه يتم بالشاشة المتخصصة (يستأنف بعد الانقطاع ويمكن التراجع عنه).");
        note.setGravity(Gravity.RIGHT);
        stepBox.addView(note, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 4, 14, 4, 0));
        CgUi.applyTree(stepBox);
    }

    private void choose(int m) {
        mode = m;
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "application/x-matroska", "application/x-subrip", "text/vtt", "text/plain", "application/octet-stream"});
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_FILES);
        } catch (Throwable t) {
            CgUi.info(this, "تعذر فتح منتقي الملفات", "لا يوجد تطبيق لاختيار الملفات على هذا الهاتف.");
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILES || res != Activity.RESULT_OK || data == null) return;
        ArrayList<Uri> uris = new ArrayList<Uri>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) if (clip.getItemAt(i).getUri() != null) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        picked.clear();
        for (Uri u : uris) {
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Throwable ignored) { }
            BulkPlan.Item it = new BulkPlan.Item();
            it.uri = u.toString();
            it.name = AdMedia.displayName(this, u);
            it.size = AdMedia.displaySize(this, u);
            picked.add(it);
        }
        if (picked.isEmpty()) return;
        if (mode == MODE_FILMS) {
            HashSet<String> titles = new HashSet<String>();
            CgData d = CgData.cur();
            if (d != null) for (JSONObject o : d.items) if (CgData.kind(o) == CgData.K_MOVIE) titles.add(BulkPlan.norm(o.optString("title", o.optString("name", ""))));
            plan = BulkPlan.films(picked, titles);
        } else {
            plan = BulkPlan.season(picked);
        }
        showPlan();
    }

    private void showPlan() {
        stepBox.removeAllViews();
        stepBox.addView(stepTitle("٣", "الخطة"));
        LinearLayout head = CgUi.card(this);
        TextView h = CgUi.text(this, plan.headline(), 15, CgCfg.TEXT, true);
        h.setGravity(Gravity.RIGHT);
        head.addView(h);
        for (String n : plan.notes) {
            TextView t = CgUi.text(this, "• " + n, 12.5f, CgCfg.WARN, false);
            t.setGravity(Gravity.RIGHT);
            head.addView(t, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
        }
        if (plan.notes.isEmpty() && plan.problems == 0) {
            TextView ok = CgUi.text(this, "✓ كل الملفات جاهزة", 12.5f, CgCfg.GOOD, true);
            ok.setGravity(Gravity.RIGHT);
            head.addView(ok, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
        }
        stepBox.addView(head);

        LinearLayout group = StudioUi.group(this);
        int shown = 0;
        for (final BulkPlan.Item it : plan.items) {
            if (shown++ >= 200) break;
            boolean bad = it.problem.length() > 0;
            String badge, sub;
            if (mode == MODE_FILMS) {
                badge = it.year > 0 ? String.valueOf(it.year) : "";
                sub = (bad ? it.problem + " · " : "") + it.name + (it.size > 0 ? " · " + BulkPlan.sizeText(it.size) : "");
                StudioUi.row(group, CgIcons.FILM, it.title.length() > 0 ? it.title : it.name, sub, badge, bad ? CgCfg.WARN : StudioUi.SKY, new Runnable() {
                    @Override public void run() { addFilm(it); }
                });
            } else {
                badge = it.episode > 0 ? "ح " + it.episode : "؟";
                sub = (bad ? it.problem : ("video".equals(it.kind) ? "فيديو" : "ترجمة")) + (it.size > 0 ? " · " + BulkPlan.sizeText(it.size) : "");
                StudioUi.row(group, "video".equals(it.kind) ? CgIcons.FILM : CgIcons.FILE, it.name, sub, badge, bad ? CgCfg.WARN : CgCfg.GOOD, new Runnable() {
                    @Override public void run() { if (it.problem.length() > 0) CgUi.info(BulkWizardActivity.this, it.name, it.problem); }
                });
            }
        }
        LinearLayout.LayoutParams gl = CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 12, 0, 12);
        stepBox.addView(group, gl);

        if (mode == MODE_SEASON) {
            TextView go = CgUi.button(this, plan.ready() ? "متابعة إلى الرفع (" + plan.videos + " حلقة)" : "أصلح الملفات المكررة أولاً", plan.ready() ? 0 : 1);
            go.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!plan.ready()) { CgUi.info(BulkWizardActivity.this, "الخطة غير جاهزة", plan.notes.isEmpty() ? "لا يوجد فيديو." : plan.notes.get(0)); return; }
                    ArrayList<Uri> uris = new ArrayList<Uri>();
                    for (BulkPlan.Item it : plan.items) if (!"other".equals(it.kind)) uris.add(Uri.parse(it.uri));
                    Intent in = new Intent(BulkWizardActivity.this, CxSeasonUploadActivity.class);
                    in.putParcelableArrayListExtra("wizard_uris", uris);
                    if (plan.season > 0) in.putExtra("wizard_season", plan.season);
                    in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(in);
                }
            });
            stepBox.addView(go);
        } else {
            TextView hint = CgUi.muted(this, "اضغط على فيلم: يُنسخ اسمه ويُفتح «إضافة فيلم» — الصق الاسم في البحث ثم اختر الملف نفسه للرفع.");
            hint.setGravity(Gravity.RIGHT);
            stepBox.addView(hint);
        }
        TextView again = CgUi.button(this, "اختيار ملفات أخرى", 3);
        again.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showChooser(); } });
        stepBox.addView(again, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        CgUi.applyTree(stepBox);
    }

    private void addFilm(BulkPlan.Item it) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("title", it.title));
            CgUi.toast(this, "نُسخ الاسم: " + it.title);
        } catch (Throwable ignored) { }
        startActivity(new Intent(this, NxMovieActivity.class));
    }
}
