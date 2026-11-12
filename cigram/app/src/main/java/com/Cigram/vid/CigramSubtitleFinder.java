package com.Cigram.vid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;

import com.Cigram.subhub.SubProviders;
import com.Cigram.subhub.SubTools;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * «ترجمة من الإنترنت» — the viewer finds a subtitle for the title on screen, inside the player.
 *
 * Asked: the subtitle sources that need no account key (Podnapisi, TVSubtitles for episodes); the key-based sources
 * (OpenSubtitles, SubDL) stay on the admin's side, where the keys are, so no secret ever ships inside the app.
 * Arabic results come first. The chosen file goes through the subtitle doctor (encoding, broken timings, adverts)
 * and is shown by the same engine as the title's own subtitle — the viewer's delay, Auto Sync and style all apply.
 * The file stays on this phone (cache) and is never published.
 */
final class CigramSubtitleFinder {

    private CigramSubtitleFinder() { }

    interface Done { void loaded(String fileUrl, String label); }

    static void run(final Activity a, String title, int season, int episode, final String language, final Done done) {
        if (a == null || a.isFinishing()) return;
        final SubProviders.Query q = new SubProviders.Query();
        q.title = clean(title);
        q.season = season;
        q.episode = episode;
        q.languages = "en".equals(language) ? new String[]{"en"} : new String[]{"ar", "en"};
        if (q.title.length() == 0) { say(a, "لا يوجد اسم للعنوان للبحث به."); return; }
        final AlertDialog wait = new AlertDialog.Builder(a).setMessage("جارٍ البحث عن ترجمة «" + q.title + "»…").setCancelable(true).create();
        try { wait.show(); } catch (Throwable ignored) { }
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                final List<SubProviders.Provider> providers = new ArrayList<SubProviders.Provider>();
                providers.add(new SubProviders.Podnapisi());
                providers.add(new SubProviders.TvSubtitles());
                final SubProviders.Outcome o = SubProviders.searchAll(q, providers, SubProviders.NET);
                main.post(new Runnable() {
                    @Override public void run() {
                        try { wait.dismiss(); } catch (Throwable ignored) { }
                        if (a.isFinishing()) return;
                        if (o.results.isEmpty()) {
                            StringBuilder b = new StringBuilder("لم يُعثر على ترجمة لـ«" + q.title + "».");
                            for (String n : o.notes.values()) b.append("\n• ").append(n);
                            say(a, b.toString());
                            return;
                        }
                        final int n = Math.min(30, o.results.size());
                        CharSequence[] labels = new CharSequence[n];
                        for (int i = 0; i < n; i++) labels[i] = o.results.get(i).label();
                        try {
                            new AlertDialog.Builder(a).setTitle("اختر الترجمة").setItems(labels, new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) { fetch(a, o.results.get(w), providers, done); }
                            }).setNegativeButton("إلغاء", null).show();
                        } catch (Throwable ignored) { }
                    }
                });
            }
        }, "cigram-sub-finder").start();
    }

    private static void fetch(final Activity a, final SubProviders.Result r, final List<SubProviders.Provider> providers, final Done done) {
        final AlertDialog wait = new AlertDialog.Builder(a).setMessage("جارٍ تنزيل الترجمة وتجهيزها…").setCancelable(false).create();
        try { wait.show(); } catch (Throwable ignored) { }
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null, url = null;
                try {
                    byte[] raw = null;
                    for (SubProviders.Provider p : providers) if (p.id().equals(r.provider)) raw = p.download(r, SubProviders.NET);
                    if (raw == null) throw new IllegalStateException("no file");
                    SubTools.DoctorReport doc = SubTools.doctor(raw, null, 0);
                    if (doc.document.isEmpty()) throw new IllegalStateException("empty");
                    File dir = new File(a.getCacheDir(), "cg_subs_online");
                    if (!dir.isDirectory()) dir.mkdirs();
                    File f = new File(dir, Integer.toHexString((r.provider + r.id).hashCode()) + ".srt");
                    FileOutputStream out = new FileOutputStream(f);
                    try { out.write(doc.srt.getBytes("UTF-8")); } finally { out.close(); }
                    url = android.net.Uri.fromFile(f).toString();
                } catch (SubProviders.ProviderException e) {
                    err = r.providerName + ": " + e.getMessage();
                } catch (Throwable t) {
                    err = "تعذر تنزيل هذه الترجمة. جرّب نتيجة أخرى.";
                }
                final String fe = err, fu = url;
                main.post(new Runnable() {
                    @Override public void run() {
                        try { wait.dismiss(); } catch (Throwable ignored) { }
                        if (a.isFinishing()) return;
                        if (fe != null) { say(a, fe); return; }
                        if (done != null) done.loaded(fu, r.label());
                    }
                });
            }
        }, "cigram-sub-fetch").start();
    }

    /** "Ghost Soldier (2026)" / "الحلقة 3" → a name the sources understand. */
    static String clean(String t) {
        if (t == null) return "";
        return t.replaceAll("\\(\\d{4}\\)", " ").replaceAll("(?i)\\b(s\\d{1,2}e\\d{1,3})\\b", " ").replaceAll("\\s+", " ").trim();
    }

    private static void say(Activity a, String s) {
        try { new AlertDialog.Builder(a).setMessage(s).setPositiveButton("حسناً", null).show(); } catch (Throwable ignored) { }
    }
}
