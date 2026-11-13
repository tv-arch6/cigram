package com.my.newproject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Studio 4 search: Arabic spelling folding, word matching and ranking (JVM, no device). */
public class StudioFindTest {

    private static boolean has(List<StudioMap.Tool> r, String id) {
        for (StudioMap.Tool t : r) if (t.id.equals(id)) return true;
        return false;
    }

    @Test
    public void normFoldsArabicSpelling() {
        assertEquals("ااا", StudioFind.norm("أإآ"));
        assertEquals("مدرسه", StudioFind.norm("مَدْرَسَة"));
        assertEquals("علي", StudioFind.norm("على"));
        assertEquals("123", StudioFind.norm("١٢٣"));
        assertEquals("123", StudioFind.norm("۱۲۳"));
        assertEquals("a b", StudioFind.norm("  A   b  "));
        assertEquals("كتاب", StudioFind.norm("كـــتاب"));
        assertEquals("admob تشغيل", StudioFind.norm("AdMob: تشغيل"));
        assertEquals("", StudioFind.norm(null));
        assertEquals("", StudioFind.norm("   "));
    }

    @Test
    public void emptyOrUnknownQueriesFindNothing() {
        assertTrue(StudioFind.tools("", 8).isEmpty());
        assertTrue(StudioFind.tools("   ", 8).isEmpty());
        assertTrue(StudioFind.tools(null, 8).isEmpty());
        assertTrue(StudioFind.tools("zzzzqqqq", 8).isEmpty());
        assertTrue(StudioFind.tools("فيلم", 0).isEmpty());
    }

    @Test
    public void everyToolIsFoundByItsOwnTitle() {
        for (StudioMap.Tool t : StudioMap.TOOLS) {
            assertTrue(t.id + " is not found by its title «" + t.title + "»", has(StudioFind.tools(t.title, StudioMap.TOOLS.length), t.id));
        }
    }

    @Test
    public void everyToolIsFoundBySomeOfItsKeywords() {
        for (StudioMap.Tool t : StudioMap.TOOLS) {
            String first = t.keys.trim().split(" ")[0];
            assertTrue(t.id + " has no keywords", first.length() > 0);
            assertTrue(t.id + " is not found by its keyword «" + first + "»", has(StudioFind.tools(first, StudioMap.TOOLS.length), t.id));
        }
    }

    @Test
    public void exactTitleRanksFirst() {
        assertEquals("add_movie", StudioFind.tools("إضافة فيلم", 5).get(0).id);
        assertEquals("add_movie", StudioFind.tools("اضافه فيلم", 5).get(0).id);          // spelled without hamza and ta marbuta
        assertEquals("quick_add", StudioFind.tools("اضافة سريعة", 5).get(0).id);
        assertEquals("bulk", StudioFind.tools("bulk", 5).get(0).id);
    }

    @Test
    public void englishAndArabicKeywordsBothWork() {
        assertTrue(has(StudioFind.tools("subtitles", 8), "sub_center"));
        assertTrue(has(StudioFind.tools("ترجمة", 8), "sub_center"));
        assertTrue(has(StudioFind.tools("ترجمة", 8), "sub_upload"));
        assertTrue(has(StudioFind.tools("draft", 8), "drafts"));
        assertTrue(has(StudioFind.tools("تليجرام", 8), "links"));
        assertTrue(has(StudioFind.tools("backup", 8), "history"));
    }

    @Test
    public void stemMeetsArabicWordForms() {
        assertEquals("ترجم", StudioFind.stem(StudioFind.norm("ترجمة")));
        assertEquals("ترجم", StudioFind.stem(StudioFind.norm("ترجمات")));
        assertEquals("ترجم", StudioFind.stem(StudioFind.norm("الترجمة")));
        assertEquals("موسم", StudioFind.stem("موسم"));
        assertEquals("abc", StudioFind.stem("abc"));
        assertEquals("bulk", StudioFind.stem("bulk"));                 // not Arabic: untouched
        assertEquals("قنا", StudioFind.stem(StudioFind.norm("قناة")));
        assertEquals("من", StudioFind.stem("من"));                      // too short to cut
        assertEquals(null, StudioFind.stem(null));
    }

    @Test
    public void arabicWordFormsFindTheirTool() {
        assertTrue(has(StudioFind.tools("مسودة", 8), "drafts"));          // the title says «المسودات»
        assertTrue(has(StudioFind.tools("الاشعارات", 8), "notify"));      // the title says «إرسال إشعار»
        assertTrue(has(StudioFind.tools("طلب", 8), "requests"));          // the title says «طلبات المستخدمين»
        assertTrue(has(StudioFind.tools("قنوات", 8), "channels"));
    }

    @Test
    public void allWordsMustMatch() {
        List<StudioMap.Tool> r = StudioFind.tools("ترجمة موسم", 8);
        assertTrue(has(r, "sub_season"));
        assertFalse("a tool that matches only one of the words must not appear", has(r, "sub_center"));
    }

    @Test
    public void titleMatchBeatsDescriptionMatch() {
        List<StudioMap.Tool> r = StudioFind.tools("admob", 8);
        assertEquals("admob", r.get(0).id);
        assertEquals("admob_check", r.get(1).id);
        int pulse = -1;
        for (int i = 0; i < r.size(); i++) if (r.get(i).id.equals("pulse")) pulse = i;
        assertTrue("pulse only mentions AdMob in its description, it must come after the AdMob tools", pulse > 1);
    }

    @Test
    public void limitIsRespectedAndOrderIsStable() {
        assertTrue(StudioFind.tools("ا", 3).size() <= 3);
        List<StudioMap.Tool> a = StudioFind.tools("قناة", 10);
        List<StudioMap.Tool> b = StudioFind.tools("قناة", 10);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) assertEquals(a.get(i).id, b.get(i).id);
    }
}
