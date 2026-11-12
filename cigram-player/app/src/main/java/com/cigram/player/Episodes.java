package com.cigram.player;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Episode list handed over by the Cigram app (extra "series_episodes_json") and "skip intro" ranges.
 * Accepts the same shapes as the app: a flat array, {"episodes":[...]}, or {"seasons":[{"season":..,"episodes":[...]}]}.
 */
final class Episodes {

    static final class Ep {
        String season = "", episode = "", title = "", video = "", subtitle = "", skip = "", poster = "";
    }

    static final int MAX_EPISODES = 500;

    final List<Ep> list = new ArrayList<Ep>();

    static Episodes parse(String json) {
        Episodes e = new Episodes();
        if (json == null || json.trim().length() == 0 || json.length() > 2000000) return e;
        try {
            Object root = new JSONTokener(json).nextValue();
            if (root instanceof JSONArray) {
                e.readArray((JSONArray) root, "");
            } else if (root instanceof JSONObject) {
                JSONObject o = (JSONObject) root;
                JSONArray direct = o.optJSONArray("episodes");
                if (direct != null) {
                    e.readArray(direct, o.optString("season", ""));
                } else {
                    JSONArray seasons = o.optJSONArray("seasons");
                    if (seasons != null) e.readArray(seasons, "");
                }
            }
        } catch (Throwable ignored) { }
        e.sortIfComplete();
        return e;
    }

    private void readArray(JSONArray array, String defaultSeason) {
        for (int i = 0; i < array.length() && list.size() < MAX_EPISODES; i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            JSONArray nested = o.optJSONArray("episodes");
            if (nested != null) {
                readArray(nested, o.optString("season", o.optString("number", defaultSeason)));
                continue;
            }
            Ep ep = new Ep();
            ep.poster = o.optString("episode_poster", o.optString("backdrop", o.optString("poster", "")));
            ep.season = o.optString("season", defaultSeason);
            ep.episode = o.optString("episode", "");
            ep.title = o.optString("title", "");
            ep.video = o.optString("video", "");
            if (ep.video.trim().length() == 0) ep.video = o.optString("link", "");
            ep.subtitle = o.optString("subtitle", "");
            ep.skip = o.optString("skip_segments", "");
            if (ep.video.trim().length() > 0 || ep.episode.trim().length() > 0) list.add(ep);
        }
    }

    private static long num(String v) {
        String d = v == null ? "" : v.replaceAll("[^0-9]", "");
        if (d.length() == 0 || d.length() > 9) return -1L;
        return Long.parseLong(d);
    }

    /** Order by season/episode when every number is readable; otherwise keep the source order. */
    private void sortIfComplete() {
        if (list.isEmpty()) return;
        for (Ep e : list) if (num(e.season) < 0 || num(e.episode) < 0) return;
        Collections.sort(list, new Comparator<Ep>() {
            @Override public int compare(Ep a, Ep b) {
                long s = num(a.season) - num(b.season);
                if (s != 0) return s < 0 ? -1 : 1;
                long e = num(a.episode) - num(b.episode);
                return e < 0 ? -1 : (e > 0 ? 1 : 0);
            }
        });
    }

    /** Index of the playing episode (season+episode match, else same link), or -1. */
    int indexOf(String season, String episode, String url) {
        long s = num(season), e = num(episode);
        for (int i = 0; i < list.size(); i++) {
            Ep x = list.get(i);
            if (s >= 0 && e >= 0 && num(x.season) == s && num(x.episode) == e) return i;
        }
        if (e >= 0) {
            for (int i = 0; i < list.size(); i++) if (num(list.get(i).episode) == e && num(list.get(i).season) < 0) return i;
        }
        String clean = Streams.cleanUrl(url);
        for (int i = 0; i < list.size(); i++) {
            if (clean.length() > 0 && clean.equals(Streams.cleanUrl(list.get(i).video))) return i;
        }
        return -1;
    }

    /** The next episode that has a playable link, or null. */
    Ep next(int index) {
        for (int i = index + 1; i >= 1 && i < list.size(); i++) {
            Ep x = list.get(i);
            if (x.video.trim().length() > 0 && Streams.isAllowedScheme(Streams.cleanUrl(x.video))) return x;
        }
        return null;
    }

    /** "10-95,1200-1290" (seconds) -> [[startMs,endMs],...]; invalid parts are ignored. */
    static long[][] parseSkip(String data) {
        List<long[]> out = new ArrayList<long[]>();
        if (data != null && data.trim().length() > 0) {
            for (String range : data.split(",")) {
                String[] p = range.trim().split("-");
                if (p.length != 2) continue;
                try {
                    long s = Long.parseLong(p[0].trim()) * 1000L;
                    long e = Long.parseLong(p[1].trim()) * 1000L;
                    if (s >= 0 && e > s) out.add(new long[]{s, e});
                } catch (NumberFormatException ignored) { }
            }
        }
        return out.toArray(new long[0][]);
    }
}
