package com.Cigram.vid;

import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;

import org.junit.Test;

/** Measurement only (prints, asserts nothing): what each stage of the Home pipeline costs on the REAL catalogue. */
public class HomePipelineProfileTest {

    private static Method m(Class<?> c, String name, Class<?>... types) throws Exception {
        Method x = c.getDeclaredMethod(name, types);
        x.setAccessible(true);
        return x;
    }

    @Test public void profile() throws Exception {
        String path = System.getenv("CIGRAM_CATALOGUE");
        assumeTrue(path != null && new File(path).isFile());
        String raw = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        Class<?> P = Class.forName("com.Cigram.vid.CigramStage1InitProvider");
        for (int round = 1; round <= 3; round++) {
            System.out.println("---- round " + round + " (round 1 = cold JIT, a phone is always cold)");
            long t = System.nanoTime();
            Object root = new org.json.JSONTokener(raw).nextValue();
            System.out.println("PROFILE parse JSONTokener            " + ms(t));
            t = System.nanoTime();
            Object prepared = m(P, "prepareCatalog", String.class).invoke(null, raw);
            System.out.println("PROFILE prepareCatalog (sort+index)   " + ms(t));
            t = System.nanoTime();
            String norm = CigramTitles.normalizeCatalog(raw);
            System.out.println("PROFILE CigramTitles.normalizeCatalog " + ms(t));
            t = System.nanoTime();
            org.json.JSONArray items = new HomeCatalog().parse(norm);
            System.out.println("PROFILE HomeCatalog.parse             " + ms(t) + " items=" + items.length());
            t = System.nanoTime();
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.util.ArrayList<java.util.HashMap<String, Object>> maps = new java.util.ArrayList<java.util.HashMap<String, Object>>();
            for (int i = 0; i < items.length(); i++) {
                @SuppressWarnings("unchecked") java.util.HashMap<String, Object> e = (java.util.HashMap<String, Object>) gson.fromJson(items.getJSONObject(i).toString(), java.util.HashMap.class);
                maps.add(e);
            }
            System.out.println("PROFILE gson maps                     " + ms(t));
            t = System.nanoTime();
            CigramOrder.sortMaps(maps);
            System.out.println("PROFILE CigramOrder.sortMaps          " + ms(t));
            t = System.nanoTime();
            Class<?> sec = Class.forName("com.Cigram.vid.CigramStage1InitProvider$DynamicHomeSection");
            LinkedHashMap<String, Object> grouped = new LinkedHashMap<String, Object>();
            Method collect = m(P, "collectDynamicSectionContent", Object.class, String.class, org.json.JSONObject.class, LinkedHashMap.class, int.class);
            collect.invoke(null, new org.json.JSONTokener(raw).nextValue(), "", null, grouped, 0);
            long collectMs = (System.nanoTime() - t) / 1000000L;
            System.out.println("PROFILE collectDynamicSectionContent  " + collectMs + " ms sections=" + grouped.size());
            t = System.nanoTime();
            m(P, "addDerivedSections", Object.class, LinkedHashMap.class).invoke(null, new org.json.JSONTokener(raw).nextValue(), grouped);
            System.out.println("PROFILE addDerivedSections (+parse)   " + ms(t) + " sections=" + grouped.size());
            t = System.nanoTime();
            Object claims = m(P, "customSectionClaims", String.class).invoke(null, raw);
            System.out.println("PROFILE customSectionClaims (re-collect) " + ms(t));
            t = System.nanoTime();
            String sig = (String) tryRenderSig(P, grouped);
            System.out.println("PROFILE renderSignature (main thread!) " + ms(t));
        }
    }

    private static Object tryRenderSig(Class<?> P, LinkedHashMap<String, Object> grouped) {
        try {
            long bytes = 0;
            Class<?> sec = Class.forName("com.Cigram.vid.CigramStage1InitProvider$DynamicHomeSection");
            java.lang.reflect.Field items = sec.getDeclaredField("items");
            items.setAccessible(true);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            for (Object s : grouped.values()) {
                @SuppressWarnings("unchecked") LinkedHashMap<String, org.json.JSONObject> it = (LinkedHashMap<String, org.json.JSONObject>) items.get(s);
                for (org.json.JSONObject o : it.values()) { byte[] b = o.toString().getBytes("UTF-8"); bytes += b.length; md.update(b); }
            }
            System.out.println("PROFILE   signature input bytes = " + bytes);
            return "x";
        } catch (Throwable t) { return "err " + t; }
    }

    private static String ms(long t0) { return (System.nanoTime() - t0) / 1000000L + " ms"; }
}
