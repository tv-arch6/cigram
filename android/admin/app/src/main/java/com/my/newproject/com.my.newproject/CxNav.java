package com.my.newproject;

import android.app.Activity;
import android.content.Intent;

import org.json.JSONObject;

/** Navigation between the content-management screens and the existing item / series screens. */
final class CxNav {

    private CxNav() {}

    static void home(Activity a) {
        Intent in = new Intent(a, DxHomeActivity.class);
        in.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        a.startActivity(in);
    }

    /** Opens the existing detail screen of an item (series header -> series screen). */
    static void openItem(Activity a, String id) {
        CgData d = CgData.cur();
        JSONObject it = d == null ? null : d.byId(id);
        Intent in;
        if (it != null && CgData.kind(it) == CgData.K_SERIES) in = new Intent(a, CgSeriesActivity.class);
        else in = new Intent(a, CgItemActivity.class);
        in.putExtra("id", id);
        a.startActivity(in);
    }

    static void editMovie(Activity a, String id) {
        Intent in = new Intent(a, CxMovieActivity.class);
        in.putExtra("edit_id", id);
        a.startActivity(in);
    }

    static void addMovie(Activity a) {
        a.startActivity(new Intent(a, CxMovieActivity.class));
    }

    static void addSeries(Activity a) {
        a.startActivity(new Intent(a, CxSeriesAddActivity.class));
    }

    static void addEpisode(Activity a, String seriesKey, int season, int episode) {
        Intent in = new Intent(a, CxEpisodeActivity.class);
        if (seriesKey != null && seriesKey.length() > 0) in.putExtra("series_id", seriesKey);
        if (season > 0) in.putExtra("season", season);
        if (episode > 0) in.putExtra("episode", episode);
        a.startActivity(in);
    }

    static void editEpisode(Activity a, String id) {
        Intent in = new Intent(a, CxEpisodeActivity.class);
        in.putExtra("edit_id", id);
        a.startActivity(in);
    }

    static void addSeason(Activity a, String seriesKey, int season) {
        Intent in = new Intent(a, CxSeasonActivity.class);
        if (seriesKey != null && seriesKey.length() > 0) in.putExtra("series_id", seriesKey);
        if (season > 0) in.putExtra("season", season);
        a.startActivity(in);
    }

    /** Same as addSeason, but the season is built at once from VidBolt links (TMDB id + season + episode). */
    static void addSeasonVidbolt(Activity a, String seriesKey, int season) {
        Intent in = new Intent(a, CxSeasonActivity.class);
        if (seriesKey != null && seriesKey.length() > 0) in.putExtra("series_id", seriesKey);
        if (season > 0) in.putExtra("season", season);
        in.putExtra("vidbolt", true);
        a.startActivity(in);
    }

    /** Pick many video / subtitle files of a season from the phone, upload them all, recognise season + episode, publish. */
    static void seasonUpload(Activity a, String seriesKey, int season) {
        Intent in = new Intent(a, CxSeasonUploadActivity.class);
        if (seriesKey != null && seriesKey.length() > 0) in.putExtra("series_id", seriesKey);
        if (season > 0) in.putExtra("season", season);
        a.startActivity(in);
    }

    static void carouselManage(Activity a) {
        a.startActivity(new Intent(a, CxCarouselActivity.class));
    }

    static void carouselAdd(Activity a, String contentId) {
        Intent in = new Intent(a, CxCarouselEditActivity.class);
        if (contentId != null && contentId.length() > 0) in.putExtra("content_id", contentId);
        a.startActivity(in);
    }

    static void channel(Activity a) {
        a.startActivity(new Intent(a, CxChannelActivity.class));
    }

    static void drafts(Activity a) {
        a.startActivity(new Intent(a, CxDraftsActivity.class));
    }

    /** Resumes a saved draft in the screen that owns it. */
    static void resumeDraft(Activity a, JSONObject draft) {
        String kind = draft.optString("kind");
        Intent in;
        if ("movie".equals(kind)) in = new Intent(a, CxMovieActivity.class);
        else if ("series".equals(kind)) in = new Intent(a, CxSeriesAddActivity.class);
        else if ("episode".equals(kind)) in = new Intent(a, CxEpisodeActivity.class);
        else if ("season".equals(kind)) in = new Intent(a, CxSeasonActivity.class);
        else if ("carousel".equals(kind)) in = new Intent(a, CxCarouselEditActivity.class);
        else in = new Intent(a, CxChannelActivity.class);
        in.putExtra("draft_id", draft.optString("id"));
        a.startActivity(in);
    }

    static void success(Activity a, String kind, String id, String title, String poster, String note, String undoId,
                        String seriesKey, int season, int episode) {
        Intent in = new Intent(a, CxSuccessActivity.class);
        in.putExtra("kind", kind);
        in.putExtra("id", id == null ? "" : id);
        in.putExtra("title", title == null ? "" : title);
        in.putExtra("poster", poster == null ? "" : poster);
        in.putExtra("note", note == null ? "" : note);
        in.putExtra("undo_id", undoId == null ? "" : undoId);
        in.putExtra("series_id", seriesKey == null ? "" : seriesKey);
        in.putExtra("season", season);
        in.putExtra("episode", episode);
        a.startActivity(in);
    }
}
