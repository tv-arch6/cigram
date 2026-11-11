package com.Cigram.vid;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import com.google.android.exoplayer2.MediaItem;

import org.json.JSONObject;

/**
 * CigramVidIntegration v1.0
 *
 * نقطة التكامل بين CigramVidWatchSheet / CigramVidResolver وبقية التطبيق.
 *
 * ═══════════════════════════════════════════════════════════════════════
 *  كيفية الاستخدام — خطوتان فقط:
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  1. في صفحة التفاصيل (FilmsAndSeriesActivity أو أي Activity تعرض زر "شاهد"):
 *     استبدل كود فتح المشغّل بـ:
 *
 *     watchBtn.setOnClickListener(v -> {
 *         CigramVidIntegration.onWatchClicked(this, currentItem, selectedSeason, selectedEpisode);
 *     });
 *
 *  2. في نفس الـ Activity أضف:
 *
 *     @Override
 *     protected void onActivityResult(int requestCode, int resultCode, Intent data) {
 *         super.onActivityResult(requestCode, resultCode, data);
 *         CigramVidIntegration.onActivityResult(this, requestCode, resultCode, data);
 *     }
 *
 * ═══════════════════════════════════════════════════════════════════════
 *  كيفية ربط الـ URL المحلول بمشغّل سيكرام الموجود:
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  في الـ Activity/Fragment الذي يشغّل الفيديو (قبل ExoPlayer.prepare()):
 *
 *     String url = CigramVidIntegration.extractUrl(intent);
 *     String srt = CigramVidIntegration.extractSrt(intent);
 *
 *     if (!url.isEmpty()) {
 *         // override the player url with the resolved one
 *         MediaItem.Builder b = new MediaItem.Builder();
 *         CigramStreams.apply(b, url);
 *
 *         if (!srt.isEmpty()) {
 *             b.setSubtitleConfigurations(
 *                 java.util.Collections.singletonList(
 *                     new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(new java.io.File(srt)))
 *                         .setMimeType(com.google.android.exoplayer2.util.MimeTypes.APPLICATION_SUBRIP)
 *                         .setLanguage("ar")
 *                         .setSelectionFlags(com.google.android.exoplayer2.C.SELECTION_FLAG_DEFAULT)
 *                         .build()
 *                 )
 *             );
 *         }
 *         player.setMediaItem(b.build());
 *         player.prepare();
 *     }
 *
 * ═══════════════════════════════════════════════════════════════════════
 */
public final class CigramVidIntegration {

    private CigramVidIntegration() { }

    // Intent extras keys (ثابتة — استخدمها في كل مكان)
    public static final String EXTRA_URL    = "vid_direct_url";
    public static final String EXTRA_SRT    = "vid_srt_path";
    public static final String EXTRA_SOURCE = "vid_source_name";
    public static final String EXTRA_TITLE  = "vid_content_title";
    public static final String EXTRA_MODE   = "vid_mode";    // "solo" | "together"

    /**
     * استدعيه عند ضغط زر "شاهد الآن" في صفحة التفاصيل.
     *
     * @param a       الـ Activity الحالية
     * @param item    عنصر المحتوى (JSONObject من سيكرام)
     * @param season  رقم الموسم (0 = فيلم)
     * @param episode رقم الحلقة (0 = فيلم)
     */
    public static void onWatchClicked(Activity a, JSONObject item, int season, int episode) {
        CigramVidWatchSheet.show(a, item, season, episode);
    }

    /**
     * نسخة مبسطة للفيلم (بدون موسم/حلقة).
     */
    public static void onWatchClicked(Activity a, JSONObject item) {
        onWatchClicked(a, item, 0, 0);
    }

    /**
     * أضفه في onActivityResult الخاص بالـ Activity.
     */
    public static void onActivityResult(Activity a, int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) return;

        if (requestCode == CigramVidWatchSheet.REQ_SRT) {
            CigramVidWatchSheet.onSrtResult(a, data);
        } else if (requestCode == CigramVidResolver.REQUEST_SRT) {
            CigramVidResolver.onSubtitleResult(a, data);
        }
    }

    /**
     * يستخرج الـ URL المحلول من الـ Intent.
     * استخدمه داخل المشغّل قبل prepare().
     */
    public static String extractUrl(Intent intent) {
        if (intent == null) return "";
        String url = intent.getStringExtra(EXTRA_URL);
        return url != null ? url : "";
    }

    /**
     * يستخرج مسار ملف الترجمة SRT من الـ Intent.
     */
    public static String extractSrt(Intent intent) {
        if (intent == null) return "";
        String srt = intent.getStringExtra(EXTRA_SRT);
        return srt != null ? srt : "";
    }

    /**
     * يبني MediaItem.SubtitleConfiguration لإضافة الترجمة لـ ExoPlayer.
     * يرجع null إذا لم توجد ترجمة.
     */
    public static MediaItem.SubtitleConfiguration buildSubtitle(String srtPath) {
        if (srtPath == null || srtPath.isEmpty()) return null;
        try {
            return new MediaItem.SubtitleConfiguration.Builder(
                    Uri.fromFile(new java.io.File(srtPath)))
                    .setMimeType(com.google.android.exoplayer2.util.MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage("ar")
                    .setSelectionFlags(com.google.android.exoplayer2.C.SELECTION_FLAG_DEFAULT)
                    .build();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * الطريقة الكاملة — تطبّق الـ URL والترجمة على الـ ExoPlayer مرة واحدة.
     *
     * استدعيه في بداية setup المشغّل بعد بناء الـ ExoPlayer:
     *
     *   boolean applied = CigramVidIntegration.applyToPlayer(player, getIntent());
     *   if (!applied) {
     *       // fallback: استخدم الـ url الموجود في الـ item العادي
     *   }
     */
    public static boolean applyToPlayer(com.google.android.exoplayer2.ExoPlayer player, Intent intent) {
        String url = extractUrl(intent);
        if (url == null || url.isEmpty()) return false;

        MediaItem.Builder b = new MediaItem.Builder();
        CigramStreams.apply(b, url);

        String srt = extractSrt(intent);
        if (!srt.isEmpty()) {
            MediaItem.SubtitleConfiguration sub = buildSubtitle(srt);
            if (sub != null) {
                b.setSubtitleConfigurations(java.util.Collections.singletonList(sub));
            }
        }

        player.setMediaItem(b.build());
        return true;
    }

    /**
     * يتحقق إذا كان الـ Intent قادماً من VidResolver/WatchSheet
     * (يعني فيه رابط محلول جاهز).
     */
    public static boolean hasResolvedUrl(Intent intent) {
        return intent != null && intent.hasExtra(EXTRA_URL)
                && !intent.getStringExtra(EXTRA_URL).isEmpty();
    }
}
