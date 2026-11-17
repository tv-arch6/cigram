package com.Cigram.vid;

import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Equivalence guard for the performance work on the sections pipeline: prints a canonical digest of EXACTLY what
 * CigramStage1InitProvider.collectDynamicSectionContent produces on the real catalogue (sections, their flags, every item with every
 * field, episode lists included, keys sorted). Before and after an optimisation the digest must be identical.
 */
public class HomeGroupingDigestTest {

    private static String canon(Object v) throws Exception {
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            List<String> keys = new ArrayList<String>();
            Iterator<String> it = o.keys();
            while (it.hasNext()) keys.add(it.next());
            Collections.sort(keys);
            StringBuilder b = new StringBuilder("{");
            for (String k : keys) {
                Object x = o.opt(k);
                if (x instanceof String && ("series_episodes_json".equals(k)) && (((String) x).startsWith("[") || ((String) x).startsWith("{"))) {
                    x = new org.json.JSONTokener((String) x).nextValue();
                }
                b.append(k).append('=').append(canon(x)).append(',');
            }
            return b.append('}').toString();
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < a.length(); i++) b.append(canon(a.opt(i))).append(',');
            return b.append(']').toString();
        }
        return String.valueOf(v);
    }

    @Test public void digest() throws Exception {
        String path = System.getenv("CIGRAM_CATALOGUE");
        assumeTrue(path != null && new File(path).isFile());
        String raw = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        Class<?> P = Class.forName("com.Cigram.vid.CigramStage1InitProvider");
        Class<?> S = Class.forName("com.Cigram.vid.CigramStage1InitProvider$DynamicHomeSection");
        Method collect = P.getDeclaredMethod("collectDynamicSectionContent", Object.class, String.class, JSONObject.class, LinkedHashMap.class, int.class);
        collect.setAccessible(true);
        LinkedHashMap<String, Object> grouped = new LinkedHashMap<String, Object>();
        collect.invoke(null, new org.json.JSONTokener(raw).nextValue(), "", null, grouped, 0);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        int items = 0, eps = 0;
        List<String> lines = new ArrayList<String>();
        for (Object s : grouped.values()) {
            StringBuilder b = new StringBuilder();
            for (String f : new String[]{"key", "id", "name", "rank", "keepOrder", "noMore", "style"}) {
                Field fl = S.getDeclaredField(f); fl.setAccessible(true); b.append(f).append('=').append(fl.get(s)).append('|');
            }
            Field itf = S.getDeclaredField("items"); itf.setAccessible(true);
            @SuppressWarnings("unchecked") LinkedHashMap<String, JSONObject> it = (LinkedHashMap<String, JSONObject>) itf.get(s);
            List<String> itemLines = new ArrayList<String>();
            for (java.util.Map.Entry<String, JSONObject> e : it.entrySet()) { itemLines.add(e.getKey() + "=>" + canon(e.getValue())); items++; }
            Collections.sort(itemLines);                       // the order of the items is decided at render time (sorted unless keepOrder)
            for (String l : itemLines) b.append('\n').append(l);
            lines.add(b.toString());
        }
        Collections.sort(lines);
        for (String l : lines) md.update(l.getBytes("UTF-8"));
        StringBuilder hex = new StringBuilder();
        for (byte x : md.digest()) hex.append(String.format("%02x", x & 255));
        System.out.println("GROUPING-DIGEST sections=" + grouped.size() + " items=" + items + " sha256=" + hex);
    }
}
