package com.my.newproject;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The only thing the add-movie / add-episode screens need to know about direct upload.
 * They add two buttons next to the existing link fields and forward onActivityResult.
 * The link fields stay exactly as they were: the admin can still paste a URL by hand.
 */
final class CgMediaHook {

    private CgMediaHook() {}

    static final int REQ_VIDEO = 7701;
    static final int REQ_SUBTITLE = 7702;

    /** What the screen knows about the item being added (read at click time, so it is always current). */
    interface Target {
        String kind();      // "movie" | "episode"

        String slug();      // folder name in R2

        String title();

        String poster();

        int season();

        int episode();
    }

    /** Lets the episode form take over the season / episode numbers the admin chose in the upload screen. */
    interface NumbersSetter {
        void set(int season, int episode);
    }

    private static CxUi.Field pendingVideo;
    private static CxUi.Field pendingSubtitle;
    private static Target pendingTarget;
    private static NumbersSetter pendingNumbers;
    /** Finished uploads the admin already answered about (per process). */
    private static final java.util.HashSet<String> answered = new java.util.HashSet<String>();

    /** The R2 folder of the item: the stored slug (series: also the one inferred from its episode links), else one made from the title. */
    static String resolveSlug(Target t) {
        String slug = t.slug() == null ? "" : t.slug().trim();
        if (slug.length() == 0) slug = CgData.slugify(t.title());
        return slug;
    }

    /** Returns null (and tells the admin why) when the upload cannot start yet. */
    private static String checkSlug(Activity a, Target t) {
        String slug = resolveSlug(t);
        if (slug.length() == 0) {
            if ("episode".equals(t.kind())) {
                CgUi.info(a, "مجلد R2 غير معروف", "لا يوجد Slug محفوظ لهذا المسلسل. افتح شاشة «موسم كامل» وأدخل اسم المجلد هناك، أو أضف Slug للمسلسل أولاً.");
            } else {
                CgUi.info(a, "الـ Slug مطلوب", "حدد الـ Slug (اسم المجلد في R2) في خطوة المعلومات أولاً، فهو يحدد مكان الملف المرفوع.");
            }
            return null;
        }
        if ("episode".equals(t.kind()) && (t.season() < 1 || t.episode() < 1)) {
            CgUi.info(a, "رقم الحلقة", "أدخل رقم الموسم ورقم الحلقة أولاً لتحديد اسم الملف.");
            return null;
        }
        return slug;
    }

    /** Adds «رفع فيديو من الجهاز» under the video field. */
    static void addVideoButton(final Activity a, LinearLayout container, final CxUi.Field videoField, final Target t) {
        addVideoButton(a, container, videoField, t, null);
    }

    /** Same, and the screen is told when the admin picks the season / episode that the file name shows. */
    static void addVideoButton(final Activity a, LinearLayout container, final CxUi.Field videoField, final Target t, final NumbersSetter numbers) {
        TextView b = CgUi.button(a, "⬆  رفع فيديو من الجهاز", 0);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String slug = checkSlug(a, t);
                if (slug == null) return;
                pendingVideo = videoField;
                pendingTarget = t;
                pendingNumbers = numbers;
                Intent i = new Intent(a, CgMediaUploadActivity.class);
                i.putExtra("kind", t.kind());
                i.putExtra("slug", slug);
                i.putExtra("title", t.title());
                i.putExtra("poster", t.poster());
                i.putExtra("season", t.season());
                i.putExtra("episode", t.episode());
                a.startActivityForResult(i, REQ_VIDEO);
            }
        });
        container.addView(b, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 8));
    }

    /** Adds «رفع ملف ترجمة» under the subtitle field. */
    static void addSubtitleButton(final Activity a, LinearLayout container, final CxUi.Field subtitleField, final Target t) {
        TextView b = CgUi.smallButton(a, "⬆  رفع ملف ترجمة", 1);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (checkSlug(a, t) == null) return;
                pendingSubtitle = subtitleField;
                pendingTarget = t;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    a.startActivityForResult(i, REQ_SUBTITLE);
                } catch (Exception e) {
                    CgUi.toast(a, "تعذر فتح منتقي الملفات.");
                }
            }
        });
        container.addView(b, CgUi.lp(a, CgUi.WRAP, CgUi.WRAP, 0, 0, 0, 0));
    }

    /** Call from the screen's onActivityResult. Returns true when the result belonged to the upload. */
    static boolean onResult(final Activity a, int req, int res, Intent data) {
        if (CxSubsActivity.onResult(a, req, res, data)) return true;
        if (req == REQ_VIDEO) {
            if (res == Activity.RESULT_OK && data != null && pendingVideo != null) {
                int rs = data.getIntExtra("season", 0);
                int re = data.getIntExtra("episode", 0);
                if (pendingNumbers != null && rs > 0 && re > 0) pendingNumbers.set(rs, re);
                String url = data.getStringExtra("video_url");
                if (url != null && url.length() > 0) {
                    pendingVideo.input.setText(url);   // not silent: the screen's own watchers run, as if typed
                    CgUi.toast(a, "تم وضع رابط الفيديو المرفوع في الحقل.");
                }
            }
            return true;
        }
        if (req == REQ_SUBTITLE) {
            final CxUi.Field field = pendingSubtitle;
            final Target t = pendingTarget;
            if (res == Activity.RESULT_OK && data != null && data.getData() != null && field != null && t != null) {
                Uri uri = data.getData();
                try {
                    a.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                    // not offered by every provider; the file is read right now anyway
                }
                CgSubtitleUpload.upload(a, uri, t, new CgUi.Callback<String>() {
                    @Override
                    public void run(String url) {
                        field.input.setText(url);
                    }
                });
            }
            return true;
        }
        return false;
    }

    /**
     * An upload that finished while the admin was away from the form (it keeps running in the background):
     * offers to put its verified link in the video field. Asked once per finished upload.
     */
    static void offerCompleted(final Activity a, final Target t, final CxUi.Field videoField) {
        try {
            if (videoField == null || t == null || !"episode".equals(t.kind())) return;
            String slug = resolveSlug(t);
            if (slug.length() == 0 || t.season() < 1 || t.episode() < 1) return;
            java.util.ArrayList<CgUploadSession> all = CgUploadManager.get(a).list();
            long now = System.currentTimeMillis();
            for (int i = 0; i < all.size(); i++) {
                final CgUploadSession s = all.get(i);
                if (!CgUploadSession.COMPLETED.equals(s.state) || !s.isEpisode() || s.publicUrl.length() == 0) continue;
                if (!s.slug.equals(slug) || s.season != t.season() || s.episode != t.episode()) continue;
                if (now - s.updatedAt > 24L * 3600L * 1000L) continue;
                if (s.publicUrl.equals(videoField.text())) return;
                if (answered.contains(s.id)) continue;
                answered.add(s.id);
                final String url = s.publicUrl;
                CgUi.confirm(a, "اكتمل رفع فيديو هذه الحلقة", "تم رفع «" + s.srcName + "» (" + CxNames.code(s.season, s.episode)
                        + ") والتحقق منه في الخلفية. هل تضع رابطه في حقل الفيديو؟", "استخدام الرابط", false, new Runnable() {
                    @Override
                    public void run() {
                        videoField.input.setText(url);
                    }
                });
                return;
            }
        } catch (Throwable ignored) {
            // purely a convenience: never break the form
        }
    }
}
