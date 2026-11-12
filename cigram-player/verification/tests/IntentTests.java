package com.cigram.player;
import android.content.Intent;
public class IntentTests {
  static int fail = 0;
  static void check(boolean ok, String n) { if (!ok) { fail++; System.out.println("FAIL: " + n); } }
  public static void main(String[] a) {
    Intent i = new Intent(PlayerIntent.ACTION_PLAY);
    i.putExtra("url", "https://cdn.example/f/film.m3u8|Referer=https://site/&X-Bad=1");
    i.putExtra("title", "Film"); i.putExtra("season","1"); i.putExtra("episode","2"); i.putExtra("resume_ms", 90000L);
    PlayerIntent p = PlayerIntent.from(i);
    System.out.println("handoff: url=" + p.url + " mime=" + p.mime + " headers=" + p.headers + " resume=" + p.resumeMs + " key=" + p.key() + " watchNow=" + p.watchNow);
    check(p.url.equals("https://cdn.example/f/film.m3u8") && p.headers.size() == 1 && p.resumeMs == 90000L, "handoff fields");
    check(p.watchNow, "Cigram hand-over (action PLAY) counts as a watch-now press");
    Intent home = new Intent("x.y.Z"); home.putExtra("url", "https://h/v.mp4"); home.putExtra("watch_now", true);
    check(PlayerIntent.from(home).watchNow, "Home play button sets watch_now");
    Intent view = new Intent(Intent.ACTION_VIEW);
    check(PlayerIntent.from(view) == null, "empty VIEW rejected");
    Intent bad = new Intent(PlayerIntent.ACTION_PLAY); bad.putExtra("url", "file:///sdcard/a.mp4");
    check(PlayerIntent.from(bad) == null, "file:// rejected");
    check(PlayerIntent.from(new Intent(PlayerIntent.ACTION_PLAY)) == null, "empty hand-over rejected");
    Intent send = new Intent(Intent.ACTION_SEND); send.putExtra(Intent.EXTRA_TEXT, "look https://h.example/v.mp4 nice");
    PlayerIntent s = PlayerIntent.from(send);
    check(s != null && s.url.equals("https://h.example/v.mp4") && !s.watchNow, "shared link plays without an ad");
    Intent noAds = new Intent(PlayerIntent.ACTION_PLAY); noAds.putExtra("url","https://h/v.mp4"); noAds.putExtra("ads", false);
    check(!PlayerIntent.from(noAds).adsEnabled, "ads=false honoured");
    Intent free = new Intent(PlayerIntent.ACTION_PLAY); free.putExtra("url","https://h/v.m3u8"); free.putExtra("plus", false);
    check(PlayerIntent.from(free).restricted, "plus=false from the Cigram app -> restricted (720p, no PiP)");
    Intent paid = new Intent(PlayerIntent.ACTION_PLAY); paid.putExtra("url","https://h/v.m3u8"); paid.putExtra("plus", true);
    check(!PlayerIntent.from(paid).restricted, "plus=true -> unrestricted");
    Intent alone = new Intent(PlayerIntent.ACTION_PLAY); alone.putExtra("url","https://h/v.m3u8");
    check(!PlayerIntent.from(alone).restricted, "no plus extra (standalone use) -> unrestricted");
    System.out.println(fail == 0 ? "IntentTests: all passed" : "IntentTests: FAILED " + fail);
    System.exit(fail == 0 ? 0 : 1);
  }
}
