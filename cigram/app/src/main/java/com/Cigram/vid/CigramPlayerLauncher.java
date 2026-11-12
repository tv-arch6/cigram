package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

/**
 * Hands playback over to the companion "Cigram Player" app (package com.cigram.player), which plays the
 * title and serves the Cigram campaigns itself.
 *
 * Called once from PlayerActivity.onCreate. It forwards ONLY when all of this is true, otherwise the built-in
 * player runs exactly as before (so nothing can break for users who do not have the companion app):
 *   - BuildConfig.EXTERNAL_PLAYER is on and the user did not switch it off (pref "external_player")
 *   - the companion app is installed
 *   - the link is a direct media link (type known from the link, or Kodi-style "|headers"); embed pages that
 *     need this app's resolver (CigramEmbedResolver) stay in the built-in player
 *
 * This class never downloads, installs or prompts to install anything (Google Play policy): it only starts an
 * app that is already on the device.
 */
final class CigramPlayerLauncher {

    static final String PKG = "com.cigram.player";
    static final String ACTION_PLAY = "com.cigram.player.action.PLAY";
    private static final String PREFS = "cigram_player_prefs";
    static final String KEY_EXTERNAL = "external_player";

    private CigramPlayerLauncher() { }

    static boolean isInstalled(Context c) {
        try {
            Intent probe = new Intent(ACTION_PLAY).setPackage(PKG);
            return c.getPackageManager().resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 19.2.1: OFF for good. Titles with a direct link were handed to the companion app while embed titles stayed
     * here, so the viewer saw two different players (and the companion one had none of this player's seek and
     * repair work). Every film and every episode now opens in this app's own player. The class is kept so the
     * companion can be brought back deliberately later; the ads logic reads this too (no companion = in-app ads).
     */
    static boolean isEnabled(Context c) {
        return false;
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_EXTERNAL, on).apply();
    }

    /** @return true if the companion player was started (the caller must then finish itself). */
    static boolean forward(Activity a, Intent src) {
        try {
            if (a == null || src == null) return false;
            if (src.getBooleanExtra("cg_internal_player", false)) return false;   // escape hatch for callers
            if (!isEnabled(a) || !isInstalled(a)) return false;

            String raw = first(src, "video", "movie_url", "link");
            if (raw.length() == 0) return false;
            boolean hasHeaders = raw.indexOf('|') >= 0;
            if (!hasHeaders && CigramEmbedResolver.isKnownHost(raw)) return false;       // needs the built-in resolver
            if (!hasHeaders && CigramStreams.mimeOf(raw) == null) return false;          // unknown type: built-in sniffing

            String title = s(src, "title");
            String id = s(src, "id");
            String type = s(src, "type");
            String seriesTitle = s(src, "series_title");
            String season = s(src, "season");
            String episode = s(src, "episode");
            boolean isSeries = "series".equalsIgnoreCase(type) || season.length() > 0 || episode.length() > 0;

            Intent i = new Intent(ACTION_PLAY).setPackage(PKG);
            i.putExtra("url", raw);
            i.putExtra("title", title);
            i.putExtra("subtitle", first(src, "subtitle", "subtitle_url"));
            i.putExtra("poster", s(src, "poster"));
            i.putExtra("id", id);
            i.putExtra("type", type);
            i.putExtra("series_title", seriesTitle);
            i.putExtra("season", season);
            i.putExtra("episode", episode);
            // series: the episode list (next-episode) and the skip-intro ranges travel with the title.
            // A Binder transaction is limited to ~1 MB, so a very large list is simply not sent (the player then has no "next").
            String episodes = s(src, "series_episodes_json");
            if (episodes.length() > 0 && episodes.length() <= 150000) i.putExtra("series_episodes_json", episodes);
            String skip = s(src, "skip_segments");
            if (skip.length() > 0 && skip.length() <= 2000) i.putExtra("skip_segments", skip);
            boolean plus = CigramPlus.isPlus(a);
            i.putExtra("ads", !plus);     // Cigram Plus = no ads in the companion player either
            i.putExtra("plus", plus);     // the companion player applies the same Plus services (720p cap, no PiP) when this is false
            long resume = CigramResume.position(a, isSeries, seriesTitle, title, id, season, episode);
            if (resume > 0L) i.putExtra("resume_ms", resume);
            a.startActivity(i);
            CigramRateCard.externalPlayerOpened();
            return true;
        } catch (Throwable t) {
            return false;   // anything unexpected: fall back to the built-in player
        }
    }

    private static String s(Intent i, String k) {
        String v = i.getStringExtra(k);
        return v == null ? "" : v.trim();
    }

    private static String first(Intent i, String... keys) {
        for (String k : keys) {
            String v = s(i, k);
            if (v.length() > 0) return v;
        }
        return "";
    }
}
