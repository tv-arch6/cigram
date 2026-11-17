package com.Cigram.vid;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the «المزيد» page shows, kept in memory by the Home render.
 *
 * Every section Home draws is {@link #publish published} here (a reference to the items it already holds, no copy, no work).
 * The page then opens from the store on its very first frame: no network, no spinner, no skeleton, no catalogue parse. A section
 * that Home has not drawn yet (a chip tapped while the lower rows are still being built) is found through the same store because
 * publishing happens for the whole list at once, before any row view is built.
 *
 * Keys are the normalised section id AND the normalised section title, so every way into the page (the row header, a chip, the
 * built-in rows, a notification) finds the same list.
 */
final class CigramSectionStore {

    /** One section's items, in the order the page shows them. */
    static final class Page {
        final String id;
        final String title;
        final String type;
        /** Already in display order, never null. */
        final List<JSONObject> items;
        /** True once the list holds everything the catalogue has (Home may hold only a trimmed copy of a big section). */
        final boolean complete;

        Page(String id, String title, String type, List<JSONObject> items, boolean complete) {
            this.id = id == null ? "" : id;
            this.title = title == null ? "" : title;
            this.type = type == null ? "" : type;
            this.items = items == null ? new ArrayList<JSONObject>() : items;
            this.complete = complete;
        }
    }

    /** Sections Home published but that have not been put in display order yet (sorted on first use, not at publish time). */
    private static final class Lazy {
        final String id;
        final String title;
        final String type;
        final Collection<JSONObject> source;
        final boolean ordered;
        Lazy(String id, String title, String type, Collection<JSONObject> source, boolean ordered) {
            this.id = id; this.title = title; this.type = type; this.source = source; this.ordered = ordered;
        }
    }

    private static final int MAX_PAGES = 64;
    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, Lazy> LAZY = new LinkedHashMap<String, Lazy>();
    private static final LinkedHashMap<String, Page> READY = new LinkedHashMap<String, Page>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Page> eldest) { return size() > MAX_PAGES; }
    };

    private CigramSectionStore() { }

    static String key(String sectionId, String sectionTitle) {
        String id = norm(sectionId);
        if (id.length() > 0) return "id:" + id;
        return "name:" + norm(sectionTitle);
    }

    /** Same normalisation the catalogue uses for ids / names, so keys written by Home match keys read by the page. */
    private static String norm(String value) {
        if (value == null) return "";
        return CigramStage1InitProvider.cigramNormalize(value).trim();
    }

    /**
     * Home draws these sections: remember them. {@code items} is the live collection of the section (not copied); {@code ordered} says
     * it already is in display order (best first, or sorted by the section builder).
     */
    static void publish(String sectionId, String sectionTitle, String sectionType, Collection<JSONObject> items, boolean ordered) {
        if (items == null || items.isEmpty()) return;
        Lazy entry = new Lazy(sectionId, sectionTitle, sectionType, items, ordered);
        synchronized (LOCK) {
            String byId = norm(sectionId);
            String byName = norm(sectionTitle);
            if (byId.length() > 0) { LAZY.put("id:" + byId, entry); READY.remove("id:" + byId); }
            if (byName.length() > 0) { LAZY.put("name:" + byName, entry); READY.remove("name:" + byName); }
            while (LAZY.size() > MAX_PAGES * 2) {
                java.util.Iterator<String> it = LAZY.keySet().iterator();
                it.next();
                it.remove();
            }
        }
    }

    /** Stores a finished page (the related titles of a film, a top-up from the catalogue). */
    static void put(String key, Page page) {
        if (key == null || key.length() == 0 || page == null) return;
        synchronized (LOCK) {
            READY.put(key, page);
        }
    }

    /** A finished page stored under an explicit key (see {@link #put}), or null. */
    static Page getByKey(String key) {
        if (key == null || key.length() == 0) return null;
        synchronized (LOCK) {
            return READY.get(key);
        }
    }

    /** The page for this section, in display order, or null when Home never drew it. Cheap: sorts once per publish. */
    static Page get(String sectionId, String sectionTitle) {
        String byId = key(sectionId, "");
        String byName = key("", sectionTitle);
        Lazy lazy;
        synchronized (LOCK) {
            Page hit = READY.get(byId.length() > 3 ? byId : "");
            if (hit == null) hit = READY.get(byName.length() > 5 ? byName : "");
            if (hit != null) return hit;
            lazy = LAZY.get(byId.length() > 3 ? byId : "");
            if (lazy == null) lazy = LAZY.get(byName.length() > 5 ? byName : "");
        }
        if (lazy == null) return null;
        ArrayList<JSONObject> list = new ArrayList<JSONObject>(lazy.source);   // a snapshot: Home may keep mutating its own map
        if (!lazy.ordered) {
            try { CigramOrder.sortJson(list); } catch (Throwable ignored) { }
        }
        Page page = new Page(lazy.id, lazy.title, lazy.type, list, false);
        synchronized (LOCK) {
            String idKey = key(lazy.id, "");
            String nameKey = key("", lazy.title);
            if (idKey.length() > 3) READY.put(idKey, page);
            if (nameKey.length() > 5) READY.put(nameKey, page);
        }
        return page;
    }

    /** Appends (never reorders, never replaces) items the page did not have yet; returns what was really new, in order. */
    static List<JSONObject> topUp(Page page, List<JSONObject> more) {
        if (page == null || more == null || more.isEmpty()) return Collections.<JSONObject>emptyList();
        java.util.HashSet<String> have = new java.util.HashSet<String>();
        for (JSONObject item : page.items) have.add(identity(item));
        ArrayList<JSONObject> added = new ArrayList<JSONObject>();
        for (JSONObject item : more) {
            if (item != null && have.add(identity(item))) added.add(item);
        }
        return added;
    }

    /** One identity per title (a series is its series id, a film its id), the same rule the Home sections use. */
    static String identity(JSONObject item) {
        if (item == null) return "";
        boolean series = "series".equalsIgnoreCase(item.optString("type", ""));
        String id = series ? item.optString("series_id", "") : "";
        if (id.length() == 0) id = item.optString("id", "");
        if (id.length() == 0) id = item.optString("series_title", item.optString("title", ""));
        return (series ? "s|" : "m|") + id.trim();
    }

    /** Test hook / sign-out: forget everything. */
    static void clear() {
        synchronized (LOCK) {
            LAZY.clear();
            READY.clear();
        }
    }
}
