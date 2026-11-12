package com.cigram.player;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything the player accepts from outside, in one place. The Activity is exported, so ALL input is untrusted.
 *
 * Entry points
 *   1. Cigram app:   action com.cigram.player.action.PLAY with string extras (below)
 *   2. Other apps:   ACTION_VIEW with a video / HLS / DASH mime type or an http(s) .mp4/.m3u8/.mpd link,
 *                    ACTION_VIEW content:// (file manager), ACTION_SEND text/plain containing a link ("Share")
 *   3. Deep link:    cigram-player://play?url=...
 *
 * Extras (all optional except the link): url | video | movie_url | link, headers (JSON), title, subtitle (url),
 *   poster, id, type, series_title, season, episode, series_episodes_json, skip_segments ("10-95,1200-1290" seconds),
 *   resume_ms (long), ads (boolean, default true: false = no ads for this title), watch_now (boolean; the action PLAY implies it).
 * Result (RESULT_OK): position_ms, duration_ms, ended.
 *
 * Accepted schemes: http, https, rtsp(s), content. file:// is rejected. Only an allow-list of request-header
 * names is honoured, with CR/LF and size limits.
 */
final class PlayerIntent {

    static final String ACTION_PLAY = "com.cigram.player.action.PLAY";
    private static final int MAX_HEADERS = 12;
    private static final int MAX_VALUE = 2000;
    private static final int MAX_URL = 4000;
    private static final Pattern URL_IN_TEXT = Pattern.compile("(?i)\\b(https?://[^\\s<>\"']+)");
    private static final String[] HEADER_ALLOW = {
            "user-agent", "referer", "origin", "cookie", "authorization", "accept", "accept-language"};

    String url = "";            // clean link (no pipe part)
    String mime;                // null = no hint
    Map<String, String> headers = new HashMap<String, String>();
    String title = "", subtitle = "", poster = "", id = "", type = "", seriesTitle = "", season = "", episode = "";
    String episodesJson = "", skipSegments = "";
    long resumeMs;
    boolean adsEnabled = true;
    /** The user pressed "watch now" (Cigram hand-over, or play in this app): the AdMob interstitial belongs to this press. */
    boolean watchNow;
    /** The Cigram app said this account has NO Plus ("plus" = false): 720p cap and no Picture-in-Picture. No extra = unrestricted. */
    boolean restricted;

    private PlayerIntent() { }

    /** Returns null when the intent carries no playable, allowed link. */
    static PlayerIntent from(Intent i) {
        if (i == null) return null;
        Uri data = i.getData();
        boolean deepLink = data != null && "cigram-player".equalsIgnoreCase(data.getScheme());

        String raw = first(i, "url", "video", "movie_url", "link");
        if (raw.length() == 0 && deepLink) raw = nz(data.getQueryParameter("url"));
        if (raw.length() == 0 && data != null && !deepLink) raw = data.toString();
        if (raw.length() == 0 && Intent.ACTION_SEND.equals(i.getAction())) raw = urlInText(i.getStringExtra(Intent.EXTRA_TEXT));
        if (raw.length() == 0) {
            ClipData clip = i.getClipData();
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getUri() != null) raw = clip.getItemAt(0).getUri().toString();
        }

        PlayerIntent p = new PlayerIntent();
        p.url = Streams.cleanUrl(raw);
        if (p.url.length() == 0 || p.url.length() > MAX_URL || !Streams.isAllowedScheme(p.url)) return null;

        p.headers.putAll(Streams.headersOf(raw));
        mergeJsonHeaders(p.headers, str(i, data, deepLink, "headers"));
        sanitize(p.headers);
        p.mime = Streams.mimeOf(p.url);

        p.title = str(i, data, deepLink, "title");
        p.subtitle = str(i, data, deepLink, "subtitle");
        if (p.subtitle.length() == 0) p.subtitle = str(i, data, deepLink, "subtitle_url");
        if (p.subtitle.length() > 0 && !Streams.isAllowedScheme(Streams.cleanUrl(p.subtitle))) p.subtitle = "";
        p.poster = str(i, data, deepLink, "poster");
        p.id = str(i, data, deepLink, "id");
        p.type = str(i, data, deepLink, "type");
        p.seriesTitle = str(i, data, deepLink, "series_title");
        p.season = str(i, data, deepLink, "season");
        p.episode = str(i, data, deepLink, "episode");
        p.episodesJson = nz(i.getStringExtra("series_episodes_json"));
        p.skipSegments = nz(i.getStringExtra("skip_segments"));
        p.resumeMs = Math.max(0L, i.getLongExtra("resume_ms", 0L));
        p.adsEnabled = i.getBooleanExtra("ads", true);
        p.watchNow = ACTION_PLAY.equals(i.getAction()) || i.getBooleanExtra("watch_now", false);
        p.restricted = i.hasExtra("plus") && !i.getBooleanExtra("plus", false);
        if (p.title.length() == 0) p.title = guessTitle(p.url);
        return p;
    }

    /** First http(s) link inside shared text, or "". */
    static String urlInText(String text) {
        if (text == null) return "";
        Matcher m = URL_IN_TEXT.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /** A readable name from the last path segment ("My.Film.2020.mp4" -> "My.Film.2020"). */
    static String guessTitle(String url) {
        try {
            if (url.toLowerCase(Locale.US).startsWith("content://")) return "";
            String path = Uri.parse(url).getLastPathSegment();
            if (path == null) return "";
            int dot = path.lastIndexOf('.');
            String name = dot > 0 ? path.substring(0, dot) : path;
            return name.length() > 80 ? name.substring(0, 80) : name;
        } catch (Throwable t) {
            return "";
        }
    }

    /** Stable key for the saved position and for placing the ad breaks of this title. */
    String key() {
        String base = id.length() > 0 ? "id:" + id : "url:" + url;
        return season.length() > 0 || episode.length() > 0 ? base + "|s" + season + "e" + episode : base;
    }

    String displayTitle() {
        if (seriesTitle.length() > 0 && title.length() > 0 && !title.equals(seriesTitle)) return seriesTitle + " — " + title;
        return title.length() > 0 ? title : seriesTitle;
    }

    // ------------------------------------------------------------------ helpers

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String first(Intent i, String... keys) {
        for (String k : keys) {
            String v = nz(i.getStringExtra(k));
            if (v.length() > 0) return v;
        }
        return "";
    }

    private static String str(Intent i, Uri data, boolean deepLink, String key) {
        String v = nz(i.getStringExtra(key));
        if (v.length() == 0 && deepLink && data != null) v = nz(data.getQueryParameter(key));
        return v;
    }

    private static void mergeJsonHeaders(Map<String, String> out, String json) {
        if (json == null || json.length() == 0 || json.length() > 20000) return;
        try {
            JSONObject o = new JSONObject(json);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = o.optString(k, "");
                if (k.length() > 0 && v.length() > 0) out.put(k, v);
            }
        } catch (Throwable ignored) { }
    }

    private static boolean allowedHeader(String name) {
        String n = name.toLowerCase(Locale.US);
        for (String a : HEADER_ALLOW) if (a.equals(n)) return true;
        return false;
    }

    /** Untrusted caller: allow-list of header names, bounded count/size, no CR/LF (header injection). */
    static void sanitize(Map<String, String> h) {
        Iterator<Map.Entry<String, String>> it = h.entrySet().iterator();
        int kept = 0;
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            String k = e.getKey(), v = e.getValue();
            boolean bad = !allowedHeader(k) || ++kept > MAX_HEADERS || k.length() > 100 || v.length() > MAX_VALUE
                    || k.indexOf('\r') >= 0 || k.indexOf('\n') >= 0 || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0;
            if (bad) it.remove();
        }
    }
}
