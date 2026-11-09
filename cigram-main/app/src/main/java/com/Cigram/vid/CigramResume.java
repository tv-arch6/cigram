package com.Cigram.vid;

import android.content.Context;

import org.json.JSONObject;

/**
 * v10.62: الاستئناف من حيث توقفت — على أي جهاز.
 *
 * المشغل كان يقرأ الموضع من ذاكرة هذا الجهاز فقط (watchProgress / continueWatchingStore).
 * هذا الكلاس هو الخيار الثالث: إذا لم يجد المشغل موضعاً محلياً، نقرأ آخر موضع من سجل المشاهدة
 * الذي يُزامن مع الحساب (CigramUserData + CigramSync). نفس مفتاح CigramUserData.record حرفياً.
 */
public final class CigramResume {

    private CigramResume() { }

    /** ms to resume from, or 0 when nothing useful is saved (finished, too early, or too close to the end). */
    public static long position(Context context, boolean isSeries, String seriesTitle, String title, String id,
                                String season, String episode) {
        try {
            if (context == null) return 0L;
            int s = toInt(season);
            int e = toInt(episode);
            boolean isEpisode = isSeries || s > 0 || e > 0;
            String key;
            if (isEpisode) {
                String st = seriesTitle == null || seriesTitle.trim().length() == 0 ? title : seriesTitle;
                String sKey = CigramContent.seriesKeyOfTitle(st);
                if (sKey.length() == 0) return 0L;
                key = CigramContent.episodeKey(sKey, s, e);
            } else {
                JSONObject info = new JSONObject();
                info.put("id", id == null ? "" : id);
                info.put("title", title == null ? "" : title);
                key = CigramContent.movieKey(info);
            }
            if (key.length() == 0) return 0L;
            JSONObject entry = CigramUserData.entry(context, key);
            if (entry == null || entry.optBoolean("finished", false)) return 0L;
            long pos = entry.optLong("last_position", 0L);
            long dur = entry.optLong("duration", 0L);
            if (pos < 5000L) return 0L;
            if (dur > 0L && dur - pos <= 15000L) return 0L;
            return pos;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static int toInt(String v) {
        if (v == null) return 0;
        try {
            String digits = v.replaceAll("[^0-9]", "");
            return digits.length() == 0 || digits.length() > 6 ? 0 : Integer.parseInt(digits);
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
