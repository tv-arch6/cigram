package com.my.newproject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeSet;

/**
 * Read-only diagnosis of a catalogue document (18.4). Pure Java.
 *
 * It answers "is what I saved structurally sound?" without changing anything: it never deletes, merges, renumbers or invents a
 * record, an episode, an id or a link. What the app does about a finding is decided elsewhere, and is limited to safe actions:
 *  - structural problems after a delta (duplicate ids, orphan episodes, duplicate episode rows) schedule ONE verified full download
 *    (the server's snapshot is the authority) at most every 6 hours;
 *  - missing posters / season holes are only reported (the images are retried by the image loader, the data is the admin's to fix).
 *
 * The same checks run in docs/tools/catalogue_integrity.py against the live Cloudflare file (2026-11-09: 5,592 records, no findings
 * except an envelope `count` that is not the record count).
 */
final class CgCatDoctor {

    private CgCatDoctor() { }

    static final class Report {
        int records;
        int recordsWithoutId;
        int duplicateIds;
        int duplicateEpisodeRows;
        int orphanEpisodes;
        int seriesWithoutEpisodes;
        int seasonsWithHoles;
        int missingPosters;
        boolean envelopeCountMismatch;

        /** Findings that a full download of the server's catalogue could repair (as opposed to data the admin has to fix). */
        boolean structural() {
            return duplicateIds > 0 || orphanEpisodes > 0 || duplicateEpisodeRows > 0;
        }

        String toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("records", records);
                o.put("without_id", recordsWithoutId);
                o.put("duplicate_ids", duplicateIds);
                o.put("duplicate_episode_rows", duplicateEpisodeRows);
                o.put("orphan_episodes", orphanEpisodes);
                o.put("series_without_episodes", seriesWithoutEpisodes);
                o.put("seasons_with_holes", seasonsWithHoles);
                o.put("missing_posters", missingPosters);
                o.put("envelope_count_mismatch", envelopeCountMismatch);
                return o.toString();
            } catch (Throwable e) {
                return "{}";
            }
        }
    }

    /** Null when the text is not a catalogue. */
    static Report diagnose(String catalogueJson) {
        try {
            CgCatDelta.Doc doc = CgCatDelta.open(catalogueJson);
            JSONArray list = doc.list;
            Report r = new Report();
            HashSet<String> ids = new HashSet<String>();
            HashSet<String> headers = new HashSet<String>();
            HashSet<String> seriesWithEpisodes = new HashSet<String>();
            HashSet<String> episodeKeys = new HashSet<String>();
            HashMap<String, TreeSet<Integer>> seasons = new HashMap<String, TreeSet<Integer>>();
            java.util.ArrayList<String> episodeSeries = new java.util.ArrayList<String>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                String id = CgCatDelta.idOf(o);
                if (id.length() == 0) { r.recordsWithoutId++; continue; }
                if (!ids.add(id)) r.duplicateIds++;
                String type = o.optString("type", "").trim().toLowerCase(java.util.Locale.ROOT);
                if ("series".equals(type) && o.optInt("episode", 0) == 0) {
                    headers.add(id);
                    String sid = o.optString("series_id", "").trim();
                    if (sid.length() > 0) headers.add(sid);
                } else if ("episode".equals(type)) {
                    String sid = o.optString("series_id", "").trim();
                    episodeSeries.add(sid);
                    seriesWithEpisodes.add(sid);
                    int season = o.optInt("season", 0), ep = o.optInt("episode", 0);
                    if (!episodeKeys.add(sid + "|" + season + "|" + ep)) r.duplicateEpisodeRows++;
                    if (ep > 0) {
                        TreeSet<Integer> set = seasons.get(sid + "|" + season);
                        if (set == null) { set = new TreeSet<Integer>(); seasons.put(sid + "|" + season, set); }
                        set.add(ep);
                    }
                } else if (("movie".equals(type) || "series".equals(type) || type.length() == 0)) {
                    String poster = o.optString("poster", "").trim() + o.optString("series_poster", "").trim();
                    if (poster.length() == 0) r.missingPosters++;
                }
            }
            r.records = ids.size();
            for (String sid : episodeSeries) if (!headers.contains(sid)) r.orphanEpisodes++;
            int realHeaders = 0, withoutEpisodes = 0;
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                if ("series".equals(o.optString("type", "").trim().toLowerCase(java.util.Locale.ROOT)) && o.optInt("episode", 0) == 0) {
                    realHeaders++;
                    String id = CgCatDelta.idOf(o), sid = o.optString("series_id", "").trim();
                    if (!seriesWithEpisodes.contains(id) && !seriesWithEpisodes.contains(sid)) withoutEpisodes++;
                }
            }
            r.seriesWithoutEpisodes = withoutEpisodes;
            for (Map.Entry<String, TreeSet<Integer>> e : seasons.entrySet()) {
                if (e.getValue().last().intValue() > e.getValue().size()) r.seasonsWithHoles++;
            }
            if (doc.root != null && doc.root.has("count") && doc.root.optInt("count", -1) != list.length()) r.envelopeCountMismatch = true;
            return r;
        } catch (Throwable e) {
            return null;
        }
    }
}
