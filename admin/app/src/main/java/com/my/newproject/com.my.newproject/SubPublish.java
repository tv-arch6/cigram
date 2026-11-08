package com.my.newproject;

import android.app.Activity;

import com.Cigram.subhub.SubCodec;
import com.Cigram.subhub.SubDocument;
import com.Cigram.subhub.SubQueue;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Publishing an APPROVED Arabic subtitle with the routes that exist today:
 * {@code /media/upload?action=put} (the same small-file upload as CgSubtitleUpload, into the title's R2 folder) and
 * {@code /admin/item/update} ({@code changes.subtitle} = the new link, with the Worker's own undo record).
 *
 * A title that already has a subtitle is only replaced when the admin said so; an existing R2 object with the same
 * name is only overwritten when the admin said so. Publication history is recorded on this phone.
 */
final class SubPublish {

    private SubPublish() {}

    static final class Outcome {
        String url = "";
        String undoId = "";
        String error = "";
        boolean needsReplaceConfirm;
        boolean objectExists;
        String existingSubtitle = "";
    }

    /** Blocking (background thread). */
    static Outcome publishBlocking(Activity a, SubQueue.Job j, boolean replaceExisting, String onObjectExists) {
        Outcome o = new Outcome();
        try {
            if (j.state != SubQueue.State.APPROVED) { o.error = "الملف غير معتمد."; return o; }
            CgData d = CgData.cur();
            JSONObject item = d == null ? null : d.byId(j.contentId);
            if (item == null) { o.error = "العنوان لم يعد موجوداً في الكتالوج."; return o; }
            String current = CgData.s(item, "subtitle");
            if (current.length() > 0 && !replaceExisting && !current.equals(j.publishedUrl)) {
                o.needsReplaceConfirm = true;
                o.existingSubtitle = current;
                return o;
            }
            byte[] bytes = SubCenterStore.getText(a, j.translatedFile);
            if (bytes == null) { o.error = "ملف الترجمة المحفوظ غير موجود على الجهاز."; return o; }
            SubDocument doc = SubCodec.read(bytes);
            String ext = doc.format == SubDocument.Format.VTT ? "vtt" : "srt";
            byte[] clean = SubCodec.writeBytes(doc, doc.format);       // UTF-8, three ms digits, original times
            JSONObject owner = j.isEpisode() ? d.headerOf(item) : item;
            String slug = owner == null ? "" : d.slugOf(owner);
            if (slug.length() == 0) slug = CgData.inferSlugFromUrl(CgData.s(item, "video"));
            if (slug.length() == 0) { o.error = "لا يوجد Slug (مجلد R2) لهذا العنوان. أضفه من صفحة العنوان ثم أعد النشر."; return o; }
            String fileName = j.isEpisode() ? String.format(Locale.US, "S%02dE%02d.ar.%s", j.season, j.episode, ext) : slug + ".ar." + ext;
            JSONObject r;
            try {
                r = CgMediaApi.putSubtitle(j.isEpisode() ? "episode" : "movie", slug, fileName, j.season, j.episode, onObjectExists, clean);
            } catch (CxApi.Failure f) {
                if (f.is("OBJECT_EXISTS")) { o.objectExists = true; return o; }
                throw f;
            }
            String url = r.optString("publicUrl", r.optString("url", ""));
            if (url.length() == 0) { o.error = "لم يصل رابط الملف من الخادم."; return o; }
            JSONObject res = CxApi.post("/admin/item/update", new JSONObject().put("id", j.contentId).put("changes", new JSONObject().put("subtitle", url)));
            o.url = url;
            o.undoId = res.optString("undo_id", "");
            SubQueue q = SubCenterStore.queue(a);
            q.markPublished(j, url, SubCenterStore.nowIso(), current, System.currentTimeMillis());
            SubCenterStore.save(a);
            SubCenterStore.addHistory(a, new JSONObject().put("content_id", j.contentId).put("title", j.displayTitle()).put("action", current.length() > 0 ? "نشر (استبدال)" : "نشر")
                    .put("url", url).put("previous", current).put("source", j.sourceProvider).put("cues", j.cues).put("quality", j.quality)
                    .put("offset_ms", j.approvedOffsetMs).put("undo_id", o.undoId));
            try { d.replaceItem(new JSONObject(item.toString()).put("subtitle", url)); } catch (Throwable ignored) { }
            SubTranslate.recordApproval(j.contentId, true, url, "admin");   // from now on the Worker's automatic routes never replace this file
        } catch (CxApi.Failure f) {
            o.error = f.getMessage();
        } catch (Throwable t) {
            o.error = "تعذر النشر: " + t.getClass().getSimpleName();
        }
        return o;
    }

    interface Done {
        void done(Outcome o);
    }

    /** One title, with the replace / overwrite questions asked on screen. */
    static void publishOne(final Activity a, final SubQueue.Job j, final boolean replace, final String onExists, final Done done) {
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                final Outcome o = publishBlocking(a, j, replace, onExists);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (a.isFinishing()) return;
                        if (o.needsReplaceConfirm) {
                            CgUi.confirm(a, "للعنوان ترجمة حالياً", "الرابط الحالي:\n" + o.existingSubtitle + "\n\nاستبداله بالملف المعتمد؟ (يمكن التراجع من سجل الإدارة)", "استبدال", true, new Runnable() {
                                @Override public void run() { publishOne(a, j, true, onExists, done); }
                            });
                            return;
                        }
                        if (o.objectExists) {
                            CgUi.choose(a, "يوجد ملف بنفس الاسم في R2", new CharSequence[]{"استبدال الملف الموجود", "رفع باسم جديد"}, new CgUi.IntCb() {
                                @Override public void run(int w) { publishOne(a, j, true, w == 0 ? "overwrite" : "rename", done); }
                            });
                            return;
                        }
                        done.done(o);
                    }
                });
            }
        });
    }

    /** Bulk: every APPROVED file; titles that already have a subtitle are skipped unless the admin confirms them as a group. */
    static void publishAll(final Activity a, final List<SubQueue.Job> jobs, final Runnable after) {
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                final List<SubQueue.Job> needConfirm = new ArrayList<SubQueue.Job>();
                int ok = 0;
                final StringBuilder errs = new StringBuilder();
                for (SubQueue.Job j : jobs) {
                    Outcome o = publishBlocking(a, j, false, "");
                    if (o.needsReplaceConfirm || o.objectExists) needConfirm.add(j);
                    else if (o.error.length() > 0) errs.append("• ").append(j.displayTitle()).append(": ").append(o.error).append('\n');
                    else ok++;
                }
                final int fOk = ok;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (a.isFinishing()) return;
                        String msg = "نُشر " + fOk + " ملفاً." + (errs.length() > 0 ? "\n\nتعذّر:\n" + errs : "");
                        if (needConfirm.isEmpty()) { CgUi.info(a, "النشر", msg); after.run(); return; }
                        CgUi.confirm(a, "النشر", msg + "\n\n" + needConfirm.size() + " عنواناً لها ترجمة حالية أو ملف بنفس الاسم. استبدالها كلها بالملفات المعتمدة؟", "استبدال الكل", true, new Runnable() {
                            @Override public void run() {
                                CgHttp.bg(new Runnable() {
                                    @Override public void run() {
                                        int n = 0;
                                        for (SubQueue.Job j : needConfirm) if (publishBlocking(a, j, true, "overwrite").error.length() == 0) n++;
                                        final int fn = n;
                                        a.runOnUiThread(new Runnable() {
                                            @Override public void run() { CgUi.toast(a, "استُبدل " + fn + " ملفاً."); after.run(); }
                                        });
                                    }
                                });
                            }
                        });
                        after.run();
                    }
                });
            }
        });
    }
}
