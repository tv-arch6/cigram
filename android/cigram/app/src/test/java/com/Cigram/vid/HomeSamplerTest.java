package com.Cigram.vid;

import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/** Measurement only: a 2 ms stack sampler over collectDynamicSectionContent on the real catalogue (where do the 1.1 s go?). */
public class HomeSamplerTest {
    @Test public void sample() throws Exception {
        String path = System.getenv("CIGRAM_CATALOGUE");
        assumeTrue(path != null && new File(path).isFile());
        final String raw = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        Class<?> P = Class.forName("com.Cigram.vid.CigramStage1InitProvider");
        final Method collect = P.getDeclaredMethod("collectDynamicSectionContent", Object.class, String.class, org.json.JSONObject.class, LinkedHashMap.class, int.class);
        collect.setAccessible(true);
        final Thread worker = Thread.currentThread();
        final Map<String, Integer> self = new HashMap<String, Integer>();
        final Map<String, Integer> incl = new HashMap<String, Integer>();
        final boolean[] stop = {false};
        final int[] total = {0};
        Thread sampler = new Thread(new Runnable() { @Override public void run() {
            while (!stop[0]) {
                StackTraceElement[] st = worker.getStackTrace();
                if (st.length > 0) {
                    total[0]++;
                    StackTraceElement top = st[0];
                    String k = top.getClassName().replace("com.Cigram.vid.", "") + "." + top.getMethodName();
                    self.put(k, self.containsKey(k) ? self.get(k) + 1 : 1);
                    java.util.HashSet<String> seen = new java.util.HashSet<String>();
                    for (StackTraceElement e : st) {
                        String c = e.getClassName().replace("com.Cigram.vid.", "") + "." + e.getMethodName();
                        if (c.startsWith("CigramStage1InitProvider") || c.startsWith("CigramOrder") || c.startsWith("HomeCatalog")) if (seen.add(c)) incl.put(c, incl.containsKey(c) ? incl.get(c) + 1 : 1);
                    }
                }
                try { Thread.sleep(2); } catch (InterruptedException e) { return; }
            }
        }});
        sampler.setDaemon(true);
        sampler.start();
        for (int i = 0; i < 8; i++) {
            LinkedHashMap<String, Object> grouped = new LinkedHashMap<String, Object>();
            collect.invoke(null, new org.json.JSONTokener(raw).nextValue(), "", null, grouped, 0);
        }
        stop[0] = true;
        System.out.println("SAMPLER samples=" + total[0]);
        print("SELF", self, total[0]);
        print("INCLUSIVE(app frames)", incl, total[0]);
    }
    private static void print(String title, Map<String, Integer> m, int total) {
        java.util.List<Map.Entry<String, Integer>> l = new java.util.ArrayList<Map.Entry<String, Integer>>(m.entrySet());
        java.util.Collections.sort(l, new java.util.Comparator<Map.Entry<String, Integer>>() { @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) { return b.getValue() - a.getValue(); } });
        System.out.println("SAMPLER ---- " + title);
        for (int i = 0; i < Math.min(14, l.size()); i++) System.out.println("SAMPLER  " + String.format("%5.1f%%", 100.0 * l.get(i).getValue() / total) + "  " + l.get(i).getKey());
    }
}
