package com.my.newproject;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.Cigram.subhub.SubAutoSync;
import com.Cigram.subhub.SubCleaner;
import com.Cigram.subhub.SubCodec;
import com.Cigram.subhub.SubCue;
import com.Cigram.subhub.SubDocument;
import com.Cigram.subhub.SubEncoding;
import com.Cigram.subhub.SubProviders;
import com.Cigram.subhub.SubQa;
import com.Cigram.subhub.SubRepair;
import com.Cigram.subhub.SubSync;
import com.Cigram.subhub.SubTools;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * مختبر الترجمة — Cigram Subtitle Lab (Studio 3.0).
 *
 * One bench for a subtitle file, with the ten tools of the hub ({@link SubTools}) plus the online sources
 * ({@link SubProviders}):
 *
 * <pre>
 *   Auto Sync · Subtitle Delay · Timing Drift Fix · SRT Repair · Encoding Fixer · Overlap Detector ·
 *   Missing Lines Detector · FPS Converter · Subtitle Cleaner · Subtitle Preview   (+ «الطبيب»: the safe ones in one tap)
 * </pre>
 * The file is opened from the phone, pasted, or fetched from a source; every tool shows what it found BEFORE anything
 * changes, a change can be undone, and the result is saved as a standard UTF-8 SRT. Nothing is uploaded or published
 * from here: publishing stays in «الترجمة» / «مركز الترجمة العربية» with their approval steps.
 */
public class SubLabActivity extends CgBase {

    private static final int REQ_OPEN = 611, REQ_REF = 612, REQ_SAVE = 613;
    private static final String KEYS = "cigram_admin_vault";

    private byte[] raw;                 // the bytes as they were opened (for the encoding fixer / the doctor)
    private SubDocument doc;            // the working copy
    private SubDocument reference;      // a file that IS in sync (Auto Sync, missing lines)
    private String name = "subtitle";
    private final ArrayList<SubDocument> undo = new ArrayList<SubDocument>();
    private final ArrayList<String> log = new ArrayList<String>();

    private LinearLayout summary, logBox;
    private TextView refLabel;

    @Override protected String screenTitle() { return "مختبر الترجمة"; }

    @Override protected void onBuild() {
        final Activity a = this;
        LinearLayout c = scrollBody();

        c.addView(StudioUi.label(this, "الملف"));
        LinearLayout src = StudioUi.group(this);
        StudioUi.row(src, CgIcons.FOLDER, "فتح ملف ترجمة", "SRT / VTT / ZIP من الجهاز", "", StudioUi.SKY, new Runnable() {
            @Override public void run() { pick(REQ_OPEN); }
        });
        StudioUi.row(src, CgIcons.COPY, "لصق نص الترجمة", "من الحافظة", "", StudioUi.VIOLET, new Runnable() {
            @Override public void run() { paste(); }
        });
        StudioUi.row(src, CgIcons.SEARCH, "جلب من مواقع الترجمة", "OpenSubtitles · SubDL · Podnapisi · TVSubtitles (عربي ثم إنكليزي)", "جديد", StudioUi.MINT, new Runnable() {
            @Override public void run() { searchOnline(); }
        });
        StudioUi.row(src, CgIcons.LINK, "ملف مرجعي متزامن", "الترجمة الأصلية أو أي ترجمة مضبوطة لنفس النسخة (للمزامنة التلقائية)", "", StudioUi.ORANGE, new Runnable() {
            @Override public void run() { pick(REQ_REF); }
        });
        c.addView(src);
        refLabel = CgUi.muted(this, "");
        refLabel.setGravity(Gravity.RIGHT);
        refLabel.setVisibility(View.GONE);
        c.addView(refLabel, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 6, 6, 6, 0));

        summary = CgUi.card(this);
        summary.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 14, 0, 0));
        c.addView(summary);

        c.addView(StudioUi.label(this, "الأدوات"));
        LinearLayout tools = StudioUi.group(this);
        StudioUi.row(tools, CgIcons.SHIELD, "الطبيب (إصلاح تلقائي)", "ترميز ← إصلاح ← تنظيف ← فحص، بضغطة واحدة", "", StudioUi.RED, new Runnable() { @Override public void run() { doctor(); } });
        StudioUi.row(tools, CgIcons.REFRESH, "Auto Sync", "مزامنة تلقائية مع الملف المرجعي (إزاحة + انحراف)", "", StudioUi.MINT, new Runnable() { @Override public void run() { autoSync(); } });
        StudioUi.row(tools, CgIcons.HISTORY, "Subtitle Delay", "تقديم أو تأخير كل الترجمة", "", StudioUi.SKY, new Runnable() { @Override public void run() { delay(); } });
        StudioUi.row(tools, CgIcons.CHART, "Timing Drift Fix", "إصلاح اختلاف التوقيت التدريجي بنقطتين", "", StudioUi.VIOLET, new Runnable() { @Override public void run() { drift(); } });
        StudioUi.row(tools, CgIcons.EDIT, "SRT Repair", "إصلاح ملف SRT تالف (توقيتات، ترتيب، ترقيم)", "", StudioUi.ORANGE, new Runnable() { @Override public void run() { repair(); } });
        StudioUi.row(tools, CgIcons.FILE, "Encoding Fixer", "إصلاح ظهور الحروف العربية بشكل خاطئ", "", StudioUi.PINK, new Runnable() { @Override public void run() { encoding(); } });
        StudioUi.row(tools, CgIcons.COPY, "Overlap Detector", "اكتشاف تداخل سطور الترجمة وإغلاقه", "", StudioUi.GOLD, new Runnable() { @Override public void run() { overlaps(); } });
        StudioUi.row(tools, CgIcons.WARNING, "Missing Lines Detector", "الأسطر الفارغة أو الناقصة أو غير المترجمة", "", CgCfg.BAD, new Runnable() { @Override public void run() { missing(); } });
        StudioUi.row(tools, CgIcons.FILM, "FPS Converter", "تحويل التوقيت بين معدلات الإطارات", "", StudioUi.SKY, new Runnable() { @Override public void run() { fps(); } });
        StudioUi.row(tools, CgIcons.TRASH, "Subtitle Cleaner", "حذف المكرر والإعلانات والرموز غير المرغوبة", "", StudioUi.MINT, new Runnable() { @Override public void run() { clean(); } });
        StudioUi.row(tools, CgIcons.EYE, "Subtitle Preview", "معاينة ما يظهر في كل لحظة", "", StudioUi.VIOLET, new Runnable() { @Override public void run() { preview(); } });
        c.addView(tools);

        c.addView(StudioUi.label(this, "النتيجة"));
        LinearLayout outRow = CgUi.hbox(this);
        TextView undoBtn = CgUi.button(this, "تراجع", 1);
        undoBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { undoLast(); } });
        TextView copy = CgUi.button(this, "نسخ", 1);
        copy.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { copyOut(); } });
        TextView save = CgUi.button(this, "حفظ SRT", 0);
        save.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { saveOut(); } });
        outRow.addView(undoBtn, CgUi.lpWeight(this, 1f, 0, 0, 4, 0));
        outRow.addView(copy, CgUi.lpWeight(this, 1f, 4, 0, 4, 0));
        outRow.addView(save, CgUi.lpWeight(this, 1.4f, 4, 0, 0, 0));
        c.addView(outRow);

        c.addView(StudioUi.label(this, "سجل العمليات"));
        logBox = CgUi.card(this);
        c.addView(logBox);
        refresh();
    }

    // ------------------------------------------------------------------ state

    private boolean need() {
        if (doc != null && !doc.isEmpty()) return true;
        CgUi.toast(this, "افتح ملف ترجمة أولاً.");
        return false;
    }

    private void apply(SubDocument next, String what) {
        if (next == null) return;
        if (doc != null) { undo.add(doc); if (undo.size() > 12) undo.remove(0); }
        doc = next;
        log.add(0, what);
        refresh();
    }

    private void undoLast() {
        if (undo.isEmpty()) { CgUi.toast(this, "لا يوجد ما يُتراجع عنه."); return; }
        doc = undo.remove(undo.size() - 1);
        log.add(0, "تراجع عن آخر عملية");
        refresh();
    }

    private void refresh() {
        summary.removeAllViews();
        if (doc == null) {
            TextView t = CgUi.muted(this, "لم يُفتح ملف بعد. افتح ملفاً، الصق نصاً، أو اجلبه من مواقع الترجمة.");
            t.setGravity(Gravity.RIGHT);
            summary.addView(t);
        } else {
            SubQa.Options qo = new SubQa.Options();
            StringBuilder sample = new StringBuilder();
            for (SubCue cu : doc.cues) { sample.append(cu.plainText()).append('\n'); if (sample.length() > 8000) break; }
            double ar = SubCodec.arabicShare(sample.toString());
            qo.expectArabic = ar >= 0.5;
            SubQa.Report qa = SubQa.analyze(doc, qo);
            TextView n = CgUi.text(this, name, 15, CgCfg.TEXT, true);
            n.setGravity(Gravity.RIGHT);
            summary.addView(n);
            String info = doc.size() + " مقطعاً · " + SubCodec.stamp(doc.lastEndMs(), ',') + " · " + doc.encoding
                    + " · " + (ar >= 0.5 ? "عربي" : "غير عربي") + "\n" + qa.summary();
            TextView i = CgUi.text(this, info, 12.5f, CgCfg.MUTED, false);
            i.setGravity(Gravity.RIGHT);
            summary.addView(i, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
            for (int k = 0; k < doc.warnings.size() && k < 3; k++) {
                TextView w = CgUi.text(this, "• " + doc.warnings.get(k), 12, CgCfg.WARN, false);
                w.setGravity(Gravity.RIGHT);
                summary.addView(w);
            }
        }
        logBox.removeAllViews();
        if (log.isEmpty()) {
            TextView t = CgUi.muted(this, "لا عمليات بعد.");
            t.setGravity(Gravity.RIGHT);
            logBox.addView(t);
        }
        for (int k = 0; k < log.size() && k < 30; k++) {
            TextView t = CgUi.text(this, (log.size() - k) + ". " + log.get(k), 12.5f, k == 0 ? CgCfg.TEXT : CgCfg.MUTED, false);
            t.setGravity(Gravity.RIGHT);
            logBox.addView(t, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 2, 0, 2));
        }
        CgUi.applyTree(summary);
        CgUi.applyTree(logBox);
    }

    private void report(String title, List<String> lines, String fallback) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; lines != null && i < lines.size() && i < 60; i++) b.append("• ").append(lines.get(i)).append('\n');
        CgUi.info(this, title, b.length() == 0 ? fallback : b.toString().trim());
    }

    // ------------------------------------------------------------------ loading

    private void pick(int req) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, req);
        } catch (Throwable t) {
            CgUi.toast(this, "لا يوجد تطبيق لاختيار الملفات.");
        }
    }

    private void paste() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            CharSequence t = cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0 ? cm.getPrimaryClip().getItemAt(0).coerceToText(this) : null;
            if (t == null || t.toString().trim().length() < 10) { CgUi.toast(this, "الحافظة لا تحتوي نص ترجمة."); return; }
            load(t.toString().getBytes("UTF-8"), "نص ملصوق", false);
        } catch (Throwable e) {
            CgUi.toast(this, "تعذر قراءة الحافظة.");
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (req == REQ_SAVE) { writeOut(uri); return; }
        if (req != REQ_OPEN && req != REQ_REF) return;
        final boolean ref = req == REQ_REF;
        final String display = AdMedia.displayName(this, uri);
        showLoading("جارٍ قراءة الملف…");
        new Thread(new Runnable() {
            @Override public void run() {
                byte[] bytes = null;
                String err = null;
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new java.io.IOException("open");
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (out.size() + n > 12 * 1024 * 1024) throw new IllegalStateException("الملف كبير جداً لملف ترجمة.");
                        out.write(buf, 0, n);
                    }
                    in.close();
                    bytes = SubProviders.fromArchive(out.toByteArray(), 0, 0);
                } catch (IllegalStateException e) {
                    err = e.getMessage();
                } catch (Throwable t) {
                    err = "تعذر قراءة الملف (أو لا يحتوي الأرشيف ملف ترجمة).";
                }
                final byte[] fb = bytes;
                final String fe = err;
                ui().post(new Runnable() {
                    @Override public void run() {
                        if (!alive()) return;
                        hideLoading();
                        if (fe != null) { CgUi.toast(SubLabActivity.this, fe); return; }
                        load(fb, display, ref);
                    }
                });
            }
        }, "sublab-open").start();
    }

    private void load(byte[] bytes, String display, boolean asReference) {
        try {
            SubEncoding.Fix f = SubEncoding.fix(bytes);
            SubDocument d = SubCodec.parse(f.text, f.encoding);
            if (d.isEmpty()) {
                // nothing readable as it is: let the repair pass try before giving up
                SubRepair.Report r = SubRepair.repair(f.text, f.encoding, new SubRepair.Options());
                if (r.document.isEmpty()) { CgUi.toast(this, "لم يُعثر على أي مقطع ترجمة في الملف."); return; }
                d = r.document;
            }
            if (asReference) {
                reference = d;
                refLabel.setText("المرجع: " + display + " (" + d.size() + " مقطعاً)");
                refLabel.setVisibility(View.VISIBLE);
                return;
            }
            raw = bytes;
            name = display == null || display.length() == 0 ? "subtitle" : display;
            undo.clear();
            log.clear();
            doc = d;
            log.add("فُتح «" + name + "» — " + d.size() + " مقطعاً، الترميز " + f.encoding);
            for (String note : f.notes) log.add(0, note);
            refresh();
        } catch (IllegalArgumentException e) {
            CgUi.toast(this, e.getMessage());
        } catch (Throwable t) {
            CgUi.toast(this, "تعذر قراءة الملف.");
        }
    }

    // ------------------------------------------------------------------ tools

    private void doctor() {
        if (raw == null) { if (need()) CgUi.toast(this, "الطبيب يعمل على الملف الأصلي: افتح ملفاً."); return; }
        try {
            SubTools.DoctorReport r = SubTools.doctor(raw, new SubCleaner.Options(), 0);
            if (r.document.isEmpty()) { CgUi.info(this, "الطبيب", r.headline()); return; }
            apply(r.document, "الطبيب: " + r.headline());
            List<String> lines = new ArrayList<String>(r.steps);
            lines.addAll(r.repair.notes);
            lines.addAll(r.missing.notes);
            report("تقرير الطبيب", lines, r.headline());
        } catch (Throwable t) {
            CgUi.toast(this, "تعذر تشغيل الطبيب على هذا الملف.");
        }
    }

    private void autoSync() {
        if (!need()) return;
        if (reference == null) { CgUi.info(this, "Auto Sync", "اختر أولاً «ملف مرجعي متزامن»: الترجمة الأصلية (الإنكليزية مثلاً) المضبوطة على نفس نسخة الفيديو.\n\nالمزامنة من صوت الفيلم نفسه متاحة للمشاهد داخل مشغل Cigram («توقيت الترجمة» ← «ضبط تلقائي من صوت الفيلم»)."); return; }
        final SubAutoSync.Alignment al = SubAutoSync.alignToReference(doc, reference);
        if (!al.reliable()) { CgUi.info(this, "Auto Sync", al.describe() + (al.note.length() > 0 ? "\n\n" + al.note : "") + "\n\nلم يُطبَّق أي تغيير."); return; }
        if (al.alreadyInSync()) { CgUi.info(this, "Auto Sync", al.describe()); return; }
        CgUi.confirm(this, "Auto Sync", al.describe() + "\n\nطابق " + al.matched + " مقطعاً من " + al.targetCues + ". تطبيق التصحيح؟", "تطبيق", false, new Runnable() {
            @Override public void run() {
                SubSync.ShiftResult r = al.apply(doc);
                apply(r.document, "Auto Sync: " + al.describe());
            }
        });
    }

    private void delay() {
        if (!need()) return;
        CgUi.input(this, "Subtitle Delay", "بالثواني: 1.5 للتأخير، ‎-0.8 للتقديم", "", false, new CgUi.Callback<String>() {
            @Override public void run(String v) {
                Long ms = seconds(v);
                if (ms == null || ms == 0) { CgUi.toast(SubLabActivity.this, "أدخل رقماً بالثواني (مثل 1.5 أو ‎-0.8)."); return; }
                SubSync.ShiftResult r = SubTools.delay(doc, ms);
                apply(r.document, "تأخير " + SubSync.delayLabel(ms) + (r.dropped > 0 ? " (حُذف " + r.dropped + " مقطعاً قبل الصفر)" : ""));
            }
        });
    }

    private void drift() {
        if (!need()) return;
        final LinearLayout box = CgUi.vbox(this);
        TextView hint = CgUi.muted(this, "اختر سطراً قرب أول الفيلم وسطراً قرب آخره. لكل سطر: توقيته في الملف، ثم اللحظة التي يُقال فيها فعلاً في الفيديو (‎س:د:ث‎ أو ‎د:ث.جزء‎).");
        hint.setGravity(Gravity.RIGHT);
        box.addView(hint);
        SubCue first = doc.cues.get(0), last = doc.cues.get(doc.size() - 1);
        final EditText f1 = CgUi.field(this, "توقيت السطر الأول في الملف", false);
        f1.setText(SubCodec.stamp(first.startMs, '.'));
        final EditText v1 = CgUi.field(this, "توقيته الصحيح في الفيديو", false);
        final EditText f2 = CgUi.field(this, "توقيت السطر الأخير في الملف", false);
        f2.setText(SubCodec.stamp(last.startMs, '.'));
        final EditText v2 = CgUi.field(this, "توقيته الصحيح في الفيديو", false);
        box.addView(CgUi.labeled(this, "السطر الأول — في الملف", f1));
        box.addView(CgUi.labeled(this, "السطر الأول — في الفيديو", v1));
        box.addView(CgUi.labeled(this, "السطر الأخير — في الملف", f2));
        box.addView(CgUi.labeled(this, "السطر الأخير — في الفيديو", v2));
        CgUi.dialog(this).setTitle("Timing Drift Fix").setView(box).setPositiveButton("تصحيح", new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int w) {
                Long a1 = clock(f1.getText().toString()), b1 = clock(v1.getText().toString()), a2 = clock(f2.getText().toString()), b2 = clock(v2.getText().toString());
                if (a1 == null || b1 == null || a2 == null || b2 == null) { CgUi.toast(SubLabActivity.this, "توقيت غير مفهوم. مثال: 01:02:03.500"); return; }
                try {
                    SubTools.Drift r = SubTools.driftFix(doc, a1, b1, a2, b2);
                    apply(r.result.document, "إصلاح الانحراف: " + r.describe());
                } catch (IllegalArgumentException e) {
                    CgUi.info(SubLabActivity.this, "Timing Drift Fix", e.getMessage());
                }
            }
        }).setNegativeButton("إلغاء", null).show();
    }

    private void repair() {
        if (!need()) return;
        try {
            SubRepair.Report r = raw != null && undo.isEmpty() ? SubRepair.repair(raw) : SubRepair.repair(SubCodec.toSrt(doc), doc.encoding, new SubRepair.Options());
            if (r.document.isEmpty()) { CgUi.info(this, "SRT Repair", r.summary()); return; }
            if (r.changed()) apply(r.document, "SRT Repair: " + r.summary());
            report("SRT Repair", r.notes, r.summary());
        } catch (Throwable t) {
            CgUi.toast(this, "تعذر إصلاح الملف.");
        }
    }

    private void encoding() {
        if (raw == null) { CgUi.toast(this, "افتح الملف الأصلي أولاً (الإصلاح يعمل على بايتات الملف)."); return; }
        final SubEncoding.Fix f = SubEncoding.fix(raw);
        final ArrayList<String> labels = new ArrayList<String>();
        final ArrayList<String> charsets = new ArrayList<String>();
        labels.add("تلقائي — " + f.encoding);
        charsets.add("");
        for (SubEncoding.Candidate cand : f.candidates) {
            labels.add(cand.charset + " — " + (cand.sample.length() > 28 ? cand.sample.substring(0, 28) + "…" : cand.sample));
            charsets.add(cand.charset);
        }
        for (String extra : new String[]{"UTF-8", "windows-1256", "ISO-8859-6", "windows-1252", "UTF-16LE"}) {
            if (!charsets.contains(extra)) { labels.add("فرض " + extra); charsets.add(extra); }
        }
        CgUi.choose(this, "Encoding Fixer", labels.toArray(new CharSequence[0]), new CgUi.IntCb() {
            @Override public void run(int i) {
                try {
                    SubEncoding.Fix chosen = i == 0 ? f : SubEncoding.fix(raw, charsets.get(i));
                    SubDocument d = SubCodec.parse(chosen.text, chosen.encoding);
                    if (d.isEmpty()) { CgUi.toast(SubLabActivity.this, "بهذا الترميز لا يُقرأ أي مقطع."); return; }
                    apply(d, "الترميز: " + chosen.encoding + (chosen.repairedLines > 0 ? " — أُصلح " + chosen.repairedLines + " سطراً" : ""));
                    List<String> notes = new ArrayList<String>(chosen.notes);
                    if (notes.isEmpty()) notes.add("الملف مقروء بالترميز " + chosen.encoding + " ولا يحتاج إصلاحاً.");
                    notes.add("معاينة: " + (d.cues.get(0).plainText().replace('\n', ' ')));
                    report("Encoding Fixer", notes, "");
                } catch (Throwable t) {
                    CgUi.toast(SubLabActivity.this, "تعذر القراءة بهذا الترميز.");
                }
            }
        });
    }

    private void overlaps() {
        if (!need()) return;
        final List<SubRepair.Overlap> list = SubRepair.overlaps(doc, 0);
        if (list.isEmpty()) { CgUi.info(this, "Overlap Detector", "لا يوجد أي تداخل بين السطور."); return; }
        StringBuilder b = new StringBuilder(list.size() + " تداخلاً · مجموعها " + (SubRepair.overlappedMs(list) / 1000.0) + " ث\n\n");
        for (int i = 0; i < list.size() && i < 25; i++) {
            SubRepair.Overlap o = list.get(i);
            b.append("#").append(o.firstIndex).append(" ↔ #").append(o.secondIndex).append(" · ").append(o.overlapMs).append(" مللي ث · ")
                    .append(SubCodec.stamp(o.atMs, ',')).append(o.sameText ? " · نص مكرر" : "").append('\n');
        }
        if (list.size() > 25) b.append("…");
        CgUi.confirm(this, "Overlap Detector", b.toString().trim() + "\n\nإغلاق التداخلات؟ (يُقصَّر السطر السابق، والنص المكرر يُدمج، والحوار المتزامن يُترك)", "إغلاق التداخلات", false, new Runnable() {
            @Override public void run() {
                SubDocument fixed = SubRepair.fixOverlaps(doc, 84, 500);
                int left = SubRepair.overlaps(fixed, 0).size();
                apply(fixed, "إغلاق التداخل: " + (list.size() - left) + " أُغلق، " + left + " تُرك");
            }
        });
    }

    private void missing() {
        if (!need()) return;
        CgUi.input(this, "Missing Lines Detector", "مدة الفيديو (اختياري) مثل 01:52:30", "", false, new CgUi.Callback<String>() {
            @Override public void run(String v) {
                Long dur = v == null || v.trim().length() == 0 ? Long.valueOf(0) : clock(v);
                SubRepair.Missing m = SubRepair.missing(doc, reference, dur == null ? 0 : dur);
                List<String> lines = new ArrayList<String>(m.notes);
                for (int i = 0; i < m.missingReferenceTexts.size() && i < 12; i++) lines.add("ناقص من الأصل: " + m.missingReferenceTexts.get(i));
                if (reference == null) lines.add("أضف «ملفاً مرجعياً» لمقارنة سطراً بسطر مع الأصل.");
                report("Missing Lines Detector — " + m.summary(), lines, m.summary());
                log.add(0, "فحص الأسطر الناقصة: " + m.summary());
                refresh();
            }
        });
    }

    private void fps() {
        if (!need()) return;
        final double[][] pairs = {{25, 23.976}, {23.976, 25}, {25, 24}, {24, 25}, {24, 23.976}, {23.976, 24}, {29.97, 25}, {25, 29.97}, {30, 25}, {29.97, 23.976}};
        CharSequence[] labels = new CharSequence[pairs.length];
        for (int i = 0; i < pairs.length; i++) labels[i] = "من " + SubTools.fpsName(pairs[i][0]) + " إلى " + SubTools.fpsName(pairs[i][1]) + " fps";
        CgUi.choose(this, "FPS Converter — الترجمة صُنعت لنسخة… وتُستخدم مع نسخة…", labels, new CgUi.IntCb() {
            @Override public void run(int i) {
                SubSync.ShiftResult r = SubTools.convertFps(doc, pairs[i][0], pairs[i][1]);
                apply(r.document, "تحويل الإطارات " + SubTools.fpsName(pairs[i][0]) + " → " + SubTools.fpsName(pairs[i][1]) + " · آخر سطر الآن عند " + SubCodec.stamp(r.document.lastEndMs(), ','));
            }
        });
    }

    private void clean() {
        if (!need()) return;
        CgUi.choose(this, "Subtitle Cleaner", new CharSequence[]{"تنظيف عادي (مكرر · إعلانات · رموز خفية · ترقيم · فراغات)", "تنظيف شامل (+ رموز الموسيقى وأوصاف ضعاف السمع ووسوم ASS)"}, new CgUi.IntCb() {
            @Override public void run(int i) {
                SubCleaner.Report r = SubCleaner.clean(doc, i == 1 ? SubCleaner.Options.deep() : new SubCleaner.Options());
                if (!r.changed()) { CgUi.info(SubLabActivity.this, "Subtitle Cleaner", r.summary()); return; }
                apply(r.document, "تنظيف: " + r.summary());
                report("Subtitle Cleaner — " + r.summary(), r.removed, r.summary());
            }
        });
    }

    private void preview() {
        if (!need()) return;
        final SubSync.Timeline tl = new SubSync.Timeline(doc);
        final long total = Math.max(1000, doc.lastEndMs());
        LinearLayout box = CgUi.vbox(this);
        final TextView time = CgUi.text(this, "00:00:00,000", 13, CgCfg.MUTED, true);
        time.setGravity(Gravity.CENTER);
        final TextView screen = CgUi.text(this, "", 17, 0xFFFFFFFF, true);
        screen.setGravity(Gravity.CENTER);
        screen.setMinLines(3);
        screen.setBackground(CgUi.bg(this, 0xFF000000, 14, CgCfg.STROKE));
        screen.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 18), CgUi.dp(this, 12), CgUi.dp(this, 18));
        final TextView next = CgUi.muted(this, "");
        next.setGravity(Gravity.RIGHT);
        final SeekBar bar = new SeekBar(this);
        bar.setMax(10000);
        final Runnable show = new Runnable() {
            @Override public void run() {
                long pos = total * bar.getProgress() / 10000L;
                SubTools.Preview p = SubTools.preview(tl, pos, 0);
                time.setText(SubCodec.stamp(pos, ','));
                screen.setText(p.text.length() == 0 ? "—" : p.text);
                next.setText(p.next == null ? "لا سطر بعد هذا" : "التالي بعد " + (Math.max(0, p.next.startMs - pos) / 1000.0) + " ث: " + p.next.plainText().replace('\n', ' '));
            }
        };
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) { show.run(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout nav = CgUi.hbox(this);
        TextView prevBtn = CgUi.smallButton(this, "السطر السابق", 1), nextBtn = CgUi.smallButton(this, "السطر التالي", 1);
        final List<SubTools.StripRow> rows = SubTools.strip(doc, 0, Long.MAX_VALUE / 4, 0, 100000);
        View.OnClickListener step = new View.OnClickListener() {
            @Override public void onClick(View v) {
                long pos = total * bar.getProgress() / 10000L;
                boolean forward = v.getTag() != null;
                SubTools.StripRow target = null;
                for (SubTools.StripRow r : rows) {
                    if (forward) { if (r.showMs > pos + 30) { target = r; break; } }
                    else if (r.showMs < pos - 30) target = r;
                }
                if (target != null) bar.setProgress((int) Math.min(10000, (target.showMs + 20) * 10000L / total));
            }
        };
        nextBtn.setTag("f");
        prevBtn.setOnClickListener(step);
        nextBtn.setOnClickListener(step);
        nav.addView(nextBtn, CgUi.lpWeight(this, 1f, 0, 0, 4, 0));
        nav.addView(prevBtn, CgUi.lpWeight(this, 1f, 4, 0, 0, 0));
        box.addView(time);
        box.addView(screen, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 6));
        box.addView(bar);
        box.addView(next, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 8));
        box.addView(nav);
        show.run();
        CgUi.dialog(this).setTitle("Subtitle Preview").setView(box).setPositiveButton("إغلاق", null).show();
    }

    // ------------------------------------------------------------------ online sources

    private SharedPreferences keys() { return getSharedPreferences(KEYS, Context.MODE_PRIVATE); }

    private String key(String id) {
        String sealed = keys().getString("key_" + id, "");
        if (sealed.length() == 0) return "";
        String open = CgVault.open(sealed);
        return open == null ? (sealed.startsWith("v1:") ? "" : sealed) : open;
    }

    private void setKey(String id, String value) {
        String v = value == null ? "" : value.trim();
        String sealed = v.length() == 0 ? "" : CgVault.seal(v);
        keys().edit().putString("key_" + id, sealed == null ? v : sealed).apply();
    }

    private void searchOnline() {
        final LinearLayout box = CgUi.vbox(this);
        final EditText title = CgUi.field(this, "اسم الفيلم أو المسلسل بالإنكليزية", false);
        final EditText imdb = CgUi.field(this, "tt0133093 (اختياري، الأدق)", false);
        final EditText year = CgUi.numberField(this, "السنة");
        final EditText season = CgUi.numberField(this, "الموسم");
        final EditText episode = CgUi.numberField(this, "الحلقة");
        box.addView(CgUi.labeled(this, "العنوان", title));
        box.addView(CgUi.labeled(this, "معرّف IMDb", imdb));
        LinearLayout nums = CgUi.hbox(this);
        nums.addView(CgUi.labeled(this, "الحلقة", episode), CgUi.lpWeight(this, 1f, 0, 0, 4, 0));
        nums.addView(CgUi.labeled(this, "الموسم", season), CgUi.lpWeight(this, 1f, 4, 0, 4, 0));
        nums.addView(CgUi.labeled(this, "السنة", year), CgUi.lpWeight(this, 1f, 4, 0, 0, 0));
        box.addView(nums);
        final boolean hasOs = key("opensubtitles").length() > 0, hasSd = key("subdl").length() > 0;
        TextView keysNote = CgUi.muted(this, "OpenSubtitles: " + (hasOs ? "المفتاح محفوظ" : "بلا مفتاح") + " · SubDL: " + (hasSd ? "المفتاح محفوظ" : "بلا مفتاح")
                + "\nPodnapisi و TVSubtitles لا يحتاجان مفتاحاً.");
        keysNote.setGravity(Gravity.RIGHT);
        box.addView(keysNote, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        CgUi.dialog(this).setTitle("جلب من مواقع الترجمة").setView(box)
                .setPositiveButton("بحث", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        SubProviders.Query q = new SubProviders.Query();
                        q.title = title.getText().toString().trim();
                        q.imdbId = imdb.getText().toString().trim();
                        q.year = num(year);
                        q.season = num(season);
                        q.episode = num(episode);
                        if (q.title.length() == 0 && q.imdbDigits().length() == 0) { CgUi.toast(SubLabActivity.this, "اكتب العنوان أو معرّف IMDb."); return; }
                        runSearch(q);
                    }
                })
                .setNeutralButton("المفاتيح", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { editKeys(); }
                })
                .setNegativeButton("إلغاء", null).show();
    }

    private static int num(EditText e) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return 0; }
    }

    private void editKeys() {
        CgUi.choose(this, "مفاتيح مواقع الترجمة (تُحفظ مشفّرة على هذا الجهاز)", new CharSequence[]{"OpenSubtitles Api-Key", "SubDL api_key"}, new CgUi.IntCb() {
            @Override public void run(final int i) {
                final String id = i == 0 ? "opensubtitles" : "subdl";
                CgUi.input(SubLabActivity.this, i == 0 ? "OpenSubtitles Api-Key" : "SubDL api_key", "الصق المفتاح (اتركه فارغاً للحذف)", "", false, new CgUi.Callback<String>() {
                    @Override public void run(String v) { setKey(id, v); CgUi.toast(SubLabActivity.this, v == null || v.trim().length() == 0 ? "حُذف المفتاح." : "حُفظ المفتاح مشفّراً."); }
                });
            }
        });
    }

    private void runSearch(final SubProviders.Query q) {
        showLoading("جارٍ البحث في مواقع الترجمة…");
        final String os = key("opensubtitles"), sd = key("subdl");
        new Thread(new Runnable() {
            @Override public void run() {
                final List<SubProviders.Provider> providers = SubProviders.all(os, sd);
                final SubProviders.Outcome o = SubProviders.searchAll(q, providers, SubProviders.NET);
                ui().post(new Runnable() {
                    @Override public void run() {
                        if (!alive()) return;
                        hideLoading();
                        StringBuilder notes = new StringBuilder();
                        for (String n : o.notes.values()) notes.append("• ").append(n).append('\n');
                        if (o.results.isEmpty()) { CgUi.info(SubLabActivity.this, "لا نتائج", notes.toString().trim()); return; }
                        final int shown = Math.min(40, o.results.size());
                        CharSequence[] labels = new CharSequence[shown];
                        for (int i = 0; i < shown; i++) labels[i] = o.results.get(i).label();
                        CgUi.choose(SubLabActivity.this, o.results.size() + " نتيجة" + (notes.length() > 0 ? " · " + o.notes.size() + " موقع بلا نتيجة" : ""), labels, new CgUi.IntCb() {
                            @Override public void run(int i) { fetch(o.results.get(i), providers); }
                        });
                    }
                });
            }
        }, "sublab-search").start();
    }

    private void fetch(final SubProviders.Result r, final List<SubProviders.Provider> providers) {
        showLoading("جارٍ تنزيل الترجمة…");
        new Thread(new Runnable() {
            @Override public void run() {
                byte[] bytes = null;
                String err = null;
                try {
                    for (SubProviders.Provider p : providers) if (p.id().equals(r.provider)) bytes = p.download(r, SubProviders.NET);
                } catch (SubProviders.ProviderException e) {
                    err = r.providerName + ": " + e.getMessage();
                } catch (Throwable t) {
                    err = "تعذر تنزيل الملف من " + r.providerName + ".";
                }
                final byte[] fb = bytes;
                final String fe = err;
                ui().post(new Runnable() {
                    @Override public void run() {
                        if (!alive()) return;
                        hideLoading();
                        if (fe != null || fb == null) { CgUi.toast(SubLabActivity.this, fe == null ? "لم يُنزَّل أي ملف." : fe); return; }
                        load(fb, r.label(), false);
                    }
                });
            }
        }, "sublab-fetch").start();
    }

    // ------------------------------------------------------------------ output

    private void copyOut() {
        if (!need()) return;
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("subtitle", SubCodec.toSrt(doc)));
            CgUi.toast(this, "نُسخ نص الترجمة.");
        } catch (Throwable t) {
            CgUi.toast(this, "الملف أكبر من أن يُنسخ: استخدم «حفظ SRT».");
        }
    }

    private void saveOut() {
        if (!need()) return;
        try {
            String base = name.replaceAll("(?i)\\.(srt|vtt|zip|txt)$", "").replaceAll("[\\\\/:*?\"<>|\\n]", " ").trim();
            if (base.length() > 60) base = base.substring(0, 60);
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/x-subrip");
            i.putExtra(Intent.EXTRA_TITLE, (base.length() == 0 ? "subtitle" : base) + ".cigram.srt");
            startActivityForResult(i, REQ_SAVE);
        } catch (Throwable t) {
            CgUi.toast(this, "لا يوجد تطبيق لحفظ الملفات.");
        }
    }

    private void writeOut(Uri uri) {
        try {
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new java.io.IOException("open");
            os.write(SubCodec.toSrt(doc).getBytes("UTF-8"));
            os.close();
            log.add(0, "حُفظ الملف (UTF-8 · SRT قياسي · " + doc.size() + " مقطعاً)");
            refresh();
            CgUi.toast(this, "تم الحفظ.");
        } catch (Throwable t) {
            CgUi.toast(this, "تعذر حفظ الملف.");
        }
    }

    // ------------------------------------------------------------------ parsing what the admin types

    /** "1.5" / "-0.8" / "١٫٥" seconds → ms; null when not a number. */
    static Long seconds(String s) {
        if (s == null) return null;
        String t = normalDigits(s).replace('−', '-').replace("‎", "").replace("‏", "").trim();
        try { return Math.round(Double.parseDouble(t) * 1000.0); } catch (Exception e) { return null; }
    }

    /** "01:02:03.500" / "62:03,5" / "3723.5" → ms; null when not understood. */
    static Long clock(String s) {
        if (s == null) return null;
        String t = normalDigits(s).replace(',', '.').replace("‎", "").replace("‏", "").trim();
        if (t.length() == 0) return null;
        try {
            String[] p = t.split(":");
            double sec;
            if (p.length == 1) sec = Double.parseDouble(p[0]);
            else if (p.length == 2) sec = Integer.parseInt(p[0].trim()) * 60 + Double.parseDouble(p[1]);
            else if (p.length == 3) sec = Integer.parseInt(p[0].trim()) * 3600 + Integer.parseInt(p[1].trim()) * 60 + Double.parseDouble(p[2]);
            else return null;
            return sec < 0 ? null : Math.round(sec * 1000.0);
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalDigits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0660 && c <= 0x0669) c = (char) ('0' + (c - 0x0660));
            else if (c >= 0x06F0 && c <= 0x06F9) c = (char) ('0' + (c - 0x06F0));
            else if (c == '٫') c = '.';
            b.append(c);
        }
        return b.toString();
    }

    static String describe(Locale l, long ms) {
        return String.format(l, "%.3f", ms / 1000.0);
    }
}
