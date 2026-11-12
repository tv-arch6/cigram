package com.cigram.player;

import java.util.*;

public class LogicTests {
    static int fail = 0, pass = 0;
    static void check(boolean ok, String name) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + name); } }

    public static void main(String[] a) {
        // ---- AdBreaks
        check(AdBreaks.forItem("k", 5 * 60000L).length == 0, "no breaks under 8 min");
        long[] b30 = AdBreaks.forItem("movie-a", 30 * 60000L);
        check(b30.length == 2, "2 breaks for 30 min, got " + b30.length);
        long[] b120 = AdBreaks.forItem("movie-b", 120 * 60000L);
        check(b120.length == 4, "4 breaks for 120 min, got " + b120.length);
        for (long dur : new long[]{9 * 60000L, 20 * 60000L, 45 * 60000L, 61 * 60000L, 90 * 60000L, 180 * 60000L}) {
            for (int k = 0; k < 200; k++) {
                long[] b = AdBreaks.forItem("t" + k, dur);
                long prev = -1;
                for (long t : b) {
                    check(t >= 5 * 60000L, "break not before 5 min (" + dur + ")");
                    check(t <= dur - 3 * 60000L, "break not in last 3 min (" + dur + "," + t + ")");
                    check(prev < 0 || t - prev >= 4 * 60000L, "gap >= 4 min");
                    prev = t;
                }
            }
        }
        check(Arrays.equals(AdBreaks.forItem("same", 100 * 60000L), AdBreaks.forItem("same", 100 * 60000L)), "deterministic per title");
        check(!Arrays.equals(AdBreaks.forItem("x1", 100 * 60000L), AdBreaks.forItem("x2", 100 * 60000L)), "differs per title");

        // ---- Streams
        check(Streams.cleanUrl("https://h/a.m3u8|Referer=x").equals("https://h/a.m3u8"), "cleanUrl");
        Map<String, String> h = Streams.headersOf("https://h/a.mp4|User-Agent=VLC&Referer=https%3A%2F%2Fsite%2F");
        check("VLC".equals(h.get("User-Agent")) && "https://site/".equals(h.get("Referer")), "headersOf decode");
        check(Streams.mimeOf("https://h/a.m3u8?x=1") != null && Streams.mimeOf("https://h/a.m3u8?x=1").contains("mpegURL"), "m3u8 mime");
        check(Streams.mimeOf("https://h/a.mp4").equals("video/mp4"), "mp4 mime");
        check(Streams.mimeOf("https://h/stream?id=5") == null, "no hint -> null");
        check(Streams.isAllowedScheme("https://x") && Streams.isAllowedScheme("http://x") && Streams.isAllowedScheme("content://x"), "schemes ok");
        check(!Streams.isAllowedScheme("file:///sdcard/a.mp4") && !Streams.isAllowedScheme("javascript:alert(1)") && !Streams.isAllowedScheme("intent://x") && !Streams.isAllowedScheme(""), "bad schemes rejected");
        check(Streams.subtitleMime("a.SRT").contains("subrip") && Streams.subtitleMime("a.vtt").contains("vtt"), "subtitle mime");

        // ---- PlayerIntent helpers
        check(PlayerIntent.urlInText("شاهد هذا https://cdn.example/v/a.mp4?t=1 رائع").equals("https://cdn.example/v/a.mp4?t=1"), "url in shared text");
        check(PlayerIntent.urlInText("no link here").equals(""), "no url in text");
        Map<String, String> hd = new HashMap<String, String>();
        hd.put("Referer", "https://a/"); hd.put("X-Evil", "1"); hd.put("Cookie", "a=b\r\nInjected: 1"); hd.put("authorization", "Bearer t"); hd.put("User-Agent", "VLC");
        PlayerIntent.sanitize(hd);
        check(hd.containsKey("Referer") && hd.containsKey("User-Agent") && hd.containsKey("authorization"), "allowed headers kept");
        check(!hd.containsKey("X-Evil") && !hd.containsKey("Cookie"), "unknown header and CRLF injection dropped");

        // ---- Episodes
        String flat = "[{\"season\":\"1\",\"episode\":\"2\",\"title\":\"B\",\"video\":\"https://h/2.mp4\",\"skip_segments\":\"5-20\"},{\"season\":\"1\",\"episode\":\"1\",\"title\":\"A\",\"video\":\"https://h/1.mp4\"},{\"season\":\"2\",\"episode\":\"1\",\"title\":\"C\",\"link\":\"https://h/3.mp4\"}]";
        Episodes e = Episodes.parse(flat);
        check(e.list.size() == 3 && e.list.get(0).title.equals("A") && e.list.get(2).title.equals("C"), "episodes sorted S/E");
        int idx = e.indexOf("1", "2", "");
        check(idx == 1 && e.next(idx) != null && e.next(idx).title.equals("C"), "next after S1E2 is S2E1");
        check(e.next(2) == null, "no next after last");
        check(e.next(-1) == null, "unknown current -> no next");
        Episodes e2 = Episodes.parse("{\"seasons\":[{\"season\":\"1\",\"episodes\":[{\"episode\":\"1\",\"video\":\"https://h/a.m3u8\"},{\"episode\":\"2\",\"video\":\"file:///x\"},{\"episode\":\"3\",\"video\":\"https://h/c.m3u8\"}]}]}");
        check(e2.list.size() == 3 && e2.next(0) != null && e2.next(0).video.contains("c.m3u8"), "skips episodes with disallowed scheme");
        check(Episodes.parse("garbage").list.isEmpty() && Episodes.parse(null).list.isEmpty(), "bad json safe");
        long[][] sk = Episodes.parseSkip("10-95, 1200-1290,bad,5-3");
        check(sk.length == 2 && sk[0][0] == 10000L && sk[1][1] == 1290000L, "skip segments parse");

        System.out.println("passed=" + pass + " failed=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
