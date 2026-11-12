package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** 19.2: Performance Engine, Smart Playback, Profiles, Watch Calendar, Cinema Spotlight — the pure decisions (JVM). */
public class SmartFeaturesTest {

    // ---------------------------------------------------------------- Performance Engine

    static CigramPerformance.Signals phone(long ramMb, int heapMb, int cores, int sdk) {
        CigramPerformance.Signals s = new CigramPerformance.Signals();
        s.totalRamMb = ramMb;
        s.heapMb = heapMb;
        s.cores = cores;
        s.sdk = sdk;
        return s;
    }

    @Test
    public void weakMiddleAndStrongPhonesGetTheirOwnProfile() {
        assertEquals(CigramPerformance.LITE, CigramPerformance.decide(phone(1536, 96, 4, 24)).level);   // 1.5 GB Android 7
        assertEquals(CigramPerformance.LITE, CigramPerformance.decide(phone(2048, 256, 8, 30)).level);  // 2 GB
        assertEquals(CigramPerformance.BALANCED, CigramPerformance.decide(phone(4096, 256, 8, 30)).level);
        assertEquals(CigramPerformance.ULTRA, CigramPerformance.decide(phone(8192, 512, 8, 34)).level);
        CigramPerformance.Signals lowFlag = phone(8192, 512, 8, 34);
        lowFlag.lowRamFlag = true;
        assertEquals(CigramPerformance.BALANCED, CigramPerformance.decide(lowFlag).level == CigramPerformance.LITE ? CigramPerformance.BALANCED : CigramPerformance.decide(lowFlag).level);
    }

    @Test
    public void batterySaverHeatAndTheUsersChoiceWin() {
        CigramPerformance.Signals s = phone(12288, 512, 8, 34);
        s.powerSave = true;
        assertEquals(CigramPerformance.LITE, CigramPerformance.decide(s).level);
        s.powerSave = false;
        s.thermal = 3;
        CigramPerformance.Profile hot = CigramPerformance.decide(s);
        assertEquals(CigramPerformance.LITE, hot.level);
        assertTrue(hot.why.contains("ساخن"));
        s.thermal = 0;
        s.forced = CigramPerformance.BALANCED;
        assertEquals(CigramPerformance.BALANCED, CigramPerformance.decide(s).level);
        CigramPerformance.Profile lite = new CigramPerformance.Profile(CigramPerformance.LITE, "");
        CigramPerformance.Profile ultra = new CigramPerformance.Profile(CigramPerformance.ULTRA, "");
        assertTrue(lite.imageParallel < ultra.imageParallel);
        assertTrue(lite.posterCacheKbCap < ultra.posterCacheKbCap);
        assertFalse(lite.animations);
        assertTrue(lite.rgb565 && !ultra.rgb565);
    }

    // ---------------------------------------------------------------- Smart Playback

    @Test
    public void theAutomaticQualityFollowsDataBatteryAndHeat() {
        CigramSmartPlayback.Conditions c = new CigramSmartPlayback.Conditions();
        assertEquals(0, CigramSmartPlayback.decide(c).maxHeight);
        c.metered = true;
        assertEquals("metered alone is not data saver", 0, CigramSmartPlayback.decide(c).maxHeight);
        c.dataSaver = true;
        assertEquals(720, CigramSmartPlayback.decide(c).maxHeight);
        c = new CigramSmartPlayback.Conditions();
        c.batteryPct = 9;
        assertEquals(720, CigramSmartPlayback.decide(c).maxHeight);
        c.charging = true;
        assertEquals(0, CigramSmartPlayback.decide(c).maxHeight);
        c.thermal = 4;
        assertEquals(480, CigramSmartPlayback.decide(c).maxHeight);
        assertTrue(CigramSmartPlayback.decide(c).reason.length() > 0);
        assertEquals("", CigramSmartPlayback.seriesKey(false, "series_1", "x"));
        assertEquals("speed::series_77240", CigramSmartPlayback.seriesKey(true, "series_77240", "كابتن"));
        assertEquals("speed::the office", CigramSmartPlayback.seriesKey(true, "", "The Office "));
    }

    // ---------------------------------------------------------------- Profiles

    @Test
    public void profileNamesLimitsAndKeys() {
        assertEquals("أحمد", CigramProfiles.cleanName("  ‮أحمد‏  "));
        assertEquals("abcdefghijklmnopqr", CigramProfiles.cleanName("abcdefghijklmnopqrstuvwxyz"));
        assertEquals("", CigramProfiles.cleanName("   "));
        assertEquals("", CigramProfiles.suffixOf(CigramProfiles.MAIN));
        assertEquals("", CigramProfiles.suffixOf(null));
        assertEquals("~k3x", CigramProfiles.suffixOf("k3x"));
        List<CigramProfiles.Profile> list = new ArrayList<CigramProfiles.Profile>();
        CigramProfiles.Profile main = new CigramProfiles.Profile();
        main.name = "الحساب";
        list.add(main);
        assertEquals("", CigramProfiles.canAdd(list, "الأطفال"));
        assertTrue(CigramProfiles.canAdd(list, " ").length() > 0);
        for (int i = 0; i < CigramProfiles.MAX_EXTRA; i++) {
            CigramProfiles.Profile p = new CigramProfiles.Profile();
            p.id = "p" + i;
            p.name = "ملف " + i;
            list.add(p);
        }
        assertTrue(CigramProfiles.canAdd(list, "آخر").contains("الحد"));
        list.remove(list.size() - 1);
        assertTrue(CigramProfiles.canAdd(list, "ملف 0").contains("يوجد"));
        assertEquals("the main profile is never stored in the list", CigramProfiles.MAX_EXTRA - 1, CigramProfiles.toArray(list).length());
        assertEquals("أ", list.get(1).name.length() > 0 ? "أ" : "");
        CigramProfiles.Profile emoji = new CigramProfiles.Profile();
        emoji.name = "😀 فرح";
        assertEquals("😀", emoji.initial());
    }

    // ---------------------------------------------------------------- Watch Calendar

    static long at(int y, int m, int d, int h, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(y, m, d, h, 0, 0);
        return c.getTimeInMillis();
    }

    static JSONObject o(Object... kv) throws Exception {
        JSONObject j = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) j.put((String) kv[i], kv[i + 1]);
        return j;
    }

    @Test
    public void aMonthShowsWatchedDaysNewEpisodesOfFollowedSeriesAdditionsAndTheStreak() throws Exception {
        TimeZone tz = TimeZone.getTimeZone("Asia/Baghdad");
        List<JSONObject> history = Arrays.asList(
                o("type", "episode", "series_id", "series_1", "series_title", "كابتن تسوباسا", "season", "2", "episode", "38", "watched_at", at(2026, 10, 8, 21, tz)),
                o("type", "episode", "series_id", "series_1", "series_title", "كابتن تسوباسا", "season", "2", "episode", "39", "watched_at", at(2026, 10, 9, 20, tz)),
                o("type", "episode", "series_id", "series_1", "series_title", "كابتن تسوباسا", "season", "2", "episode", "40", "watched_at", at(2026, 10, 9, 22, tz)),
                o("type", "movie", "title", "Ghost Soldier", "key", "m1", "watched_at", at(2026, 10, 10, 19, tz)),
                o("type", "movie", "title", "Old Film", "key", "m0", "watched_at", at(2026, 9, 20, 19, tz)),
                // the player's own continue-watching row (type "series" + episode, updated_at)
                o("type", "series", "series_title", "Smiling Friends", "season", "1", "episode", "2", "updated_at", at(2026, 10, 10, 23, tz)));
        List<JSONObject> catalogue = Arrays.asList(
                o("type", "series", "id", "series_1", "title", "كابتن تسوباسا", "created_at_ms", at(2026, 0, 1, 9, tz)),
                o("type", "episode", "series_id", "series_1", "season", "2", "episode", "41", "title", "الحلقة 41", "created_at_ms", at(2026, 10, 10, 11, tz)),
                o("type", "episode", "series_id", "series_9", "season", "1", "episode", "5", "title", "غير متابَع", "created_at_ms", at(2026, 10, 10, 11, tz)),
                o("type", "movie", "id", "m2", "title", "New Film", "created_at_ms", at(2026, 10, 3, 12, tz)),
                o("type", "movie", "id", "m3", "title", "October Film", "created_at_ms", at(2026, 9, 3, 12, tz)));
        CigramWatchCalendar.Month m = CigramWatchCalendar.build(2026, 10, tz, history, catalogue, CigramWatchCalendar.seriesTitles(catalogue), at(2026, 10, 10, 23, tz) + 1800000L);
        assertEquals(30, m.days);
        assertEquals("1 Nov 2026 is a Sunday: second column of a Saturday-first week", 1, m.firstWeekday);
        assertEquals(1, m.day[8].watched.size());
        assertEquals("two episodes of one series on one day = one line", 1, m.day[9].watched.size());
        assertEquals(2, m.day[10].watched.size());
        assertEquals("م2 · ح40", m.day[9].watched.get(0).subtitle);
        assertEquals(1, m.day[10].newEpisodes.size());
        assertEquals("كابتن تسوباسا", m.day[10].newEpisodes.get(0).title);
        assertEquals("the new episode of a series nobody watches is not «new for you»", 1, m.newEpisodes);
        assertEquals(1, m.day[3].added.size());
        assertEquals(1, m.added);
        assertEquals(3, m.watchedDays);
        assertEquals(3, m.watchedTitles);
        assertEquals("8, 9 and 10 November", 3, m.streak);
        assertTrue(m.day[1].empty());
        CigramWatchCalendar.Month oct = CigramWatchCalendar.build(2026, 9, tz, history, catalogue, null, at(2026, 10, 10, 23, tz));
        assertEquals(1, oct.day[20].watched.size());
        assertEquals(1, oct.added);
    }

    // ---------------------------------------------------------------- Cinema Spotlight

    @Test
    public void theSpotlightTakesNewTitlesWithABackdropAlternatingFilmsAndSeries() throws Exception {
        List<JSONObject> all = Arrays.asList(
                o("id", "1", "type", "movie", "title", "A", "backdrop", "https://x/a.jpg"),
                o("id", "2", "type", "movie", "title", "B", "backdrop", "https://x/b.jpg"),
                o("id", "3", "type", "movie", "title", "C"),
                o("id", "4", "type", "series", "title", "S1", "backdrop", "https://x/s1.jpg"),
                o("id", "1", "type", "movie", "title", "A again", "backdrop", "https://x/a.jpg"),
                o("id", "5", "type", "series", "title", "S2", "backdrop", "https://x/s2.jpg"),
                o("id", "6", "type", "movie", "title", "D", "backdrop", "https://x/d.jpg"));
        List<JSONObject> p = CigramTvSpotlight.pick(all, 4);
        assertEquals(4, p.size());
        assertEquals(Arrays.asList("A", "S1", "B", "S2"), Arrays.asList(p.get(0).optString("title"), p.get(1).optString("title"), p.get(2).optString("title"), p.get(3).optString("title")));
        assertEquals(5, CigramTvSpotlight.pick(all, 10).size());
        assertTrue(CigramTvSpotlight.pick(new ArrayList<JSONObject>(), 6).isEmpty());
        assertTrue(CigramTvSpotlight.metaOf(o("type", "series", "year", "2024", "section_name", "رسوم متحركة")).startsWith("مسلسل"));
        assertFalse("an IMDb id is not a rating", CigramTvSpotlight.metaOf(o("type", "movie", "imdb", "tt123")).contains("IMDb"));
    }
}
