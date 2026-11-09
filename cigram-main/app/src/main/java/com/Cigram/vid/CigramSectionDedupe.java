package com.Cigram.vid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * "Every section and every carousel slide appears once" (18.3) — pure helpers, unit-tested.
 *
 * Deterministic precedence rules (documented here and in the report):
 *  1. Sections: a section is identified by its ID. Items that carry only the section NAME join the section whose normalised
 *     name equals it ({@link #aliasMap}); an id wins over a name. Two different ids with the same title stay two sections
 *     (they are different sections to the admin), but a repeated id is one section.
 *  2. The admin's registry (/home-sections): the FIRST entry of an id / slug / name decides its position and visibility; a later
 *     duplicate never overwrites it ({@link #buildOrder}).
 *  3. Carousel: a slide is identified by its id (or, when it has none, by link + image); the first occurrence keeps its position
 *     and every later copy is dropped ({@link #dedupeCarouselItems}).
 * Nothing here invents ids or changes any content id.
 */
final class CigramSectionDedupe {

    private CigramSectionDedupe() { }

    interface Normalizer { String normalize(String s); }

    /** One section as the grouping step knows it. */
    static final class Sec {
        final String key;
        final String id;
        final String name;

        Sec(String key, String id, String name) {
            this.key = key;
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
        }
    }

    /**
     * Name-only sections (no id) that must be merged into the id-bearing section of the same normalised name.
     * @return map nameOnlyKey -> idSectionKey (empty when there is nothing to merge)
     */
    static Map<String, String> aliasMap(List<Sec> sections, Normalizer n) {
        Map<String, String> idKeyByName = new HashMap<String, String>();
        for (Sec s : sections) {
            if (s.id.trim().length() == 0) continue;
            String name = n.normalize(s.name);
            if (name.length() > 0 && !idKeyByName.containsKey(name)) idKeyByName.put(name, s.key);   // first id wins
        }
        Map<String, String> out = new HashMap<String, String>();
        for (Sec s : sections) {
            if (s.id.trim().length() > 0) continue;
            String target = idKeyByName.get(n.normalize(s.name));
            if (target != null && !target.equals(s.key)) out.put(s.key, target);
        }
        return out;
    }

    /**
     * Fills {@code order} / {@code hidden} from the registry with FIRST-WINS semantics: the first entry that claims an id, a slug
     * or a name keeps it. The previous code let a later duplicate silently overwrite the position of an earlier section.
     */
    static void buildOrder(JSONArray list, Map<String, Integer> order, Map<String, Boolean> hidden) {
        if (list == null) return;
        HashSet<String> seenIds = new HashSet<String>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject s = list.optJSONObject(i);
            if (s == null) continue;
            String id = key(s.optString("id"));
            if (id.length() > 0 && !seenIds.add(id)) continue;        // the same section registered twice: the first stays
            int pos = s.optInt("order", i + 1);
            boolean hide = !s.optBoolean("show_home", true) || s.optBoolean("more_only", false);
            for (String n : new String[]{s.optString("id"), s.optString("slug"), s.optString("name")}) {
                String k = key(n);
                if (k.length() == 0 || order.containsKey(k)) continue;
                order.put(k, Integer.valueOf(pos));
                hidden.put(k, Boolean.valueOf(hide));
            }
        }
    }

    static String key(String s) {
        return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Carousel slides without repeats; non-objects are dropped; the order of first occurrences is kept. */
    static JSONArray dedupeCarouselItems(JSONArray in) {
        JSONArray out = new JSONArray();
        if (in == null) return out;
        HashSet<String> seen = new HashSet<String>();
        for (int i = 0; i < in.length(); i++) {
            JSONObject o = in.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "").trim();
            String k = id.length() > 0 ? "id:" + id.toLowerCase(java.util.Locale.ROOT)
                    : "li:" + o.optString("link", o.optString("url", "")).trim().toLowerCase(java.util.Locale.ROOT)
                    + "|" + o.optString("image", o.optString("poster", "")).trim().toLowerCase(java.util.Locale.ROOT);
            if (k.equals("li:|")) { out.put(o); continue; }             // nothing to identify it by: keep, never guess
            if (seen.add(k)) out.put(o);
        }
        return out;
    }

    /** First occurrence wins; the ids stay exactly as they are. */
    static List<String> dedupeIds(List<String> ids) {
        List<String> out = new ArrayList<String>();
        HashSet<String> seen = new HashSet<String>();
        for (String id : ids) {
            String k = id == null ? "" : id.trim();
            if (k.length() == 0 || seen.add(k.toLowerCase(java.util.Locale.ROOT))) out.add(id);
        }
        return out;
    }
}
