package com.my.newproject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The admin's ready replies carry no emoji (the monochrome identity), every chip names a real {@link CgIcons} glyph,
 * and the message sent to a Plus customer has none either. Plain JVM: only static helpers are touched.
 */
public class CigramChatSuggestTest {

    /** True for any code point in the emoji / pictograph / dingbat blocks (and the variation selector). */
    private static boolean hasEmoji(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0x1F000 && cp <= 0x1FAFF) return true;       // emoticons, symbols, pictographs
            if (cp >= 0x2600 && cp <= 0x27BF) return true;         // misc symbols + dingbats (✅ ✏ ★ ...)
            if (cp == 0x2B50 || cp == 0xFE0F || cp == 0x200D) return true;
        }
        return false;
    }

    private static String[][][] allSets() {
        return new String[][][]{
                CigramChatSuggest.forDeveloper(true, ""),
                CigramChatSuggest.forDeveloper(false, ""),
                CigramChatSuggest.forDeveloper(false, "image"),
                CigramChatSuggest.forDeveloper(false, "video"),
                CigramChatSuggest.forDeveloper(false, "file"),
        };
    }

    @Test
    public void everyReadyReplyIsEmojiFreeAndHasARealIcon() {
        for (String[][] set : allSets()) {
            assertTrue("a set is never empty", set.length > 0);
            for (String[] chip : set) {
                assertEquals("{icon, label, sentence}", 3, chip.length);
                int kind = CigramChatSuggest.iconOf(chip[0]);
                assertTrue("icon of «" + chip[1] + "» is a CgIcons number", kind >= CgIcons.UPDATE && kind <= CgIcons.BOX);
                assertFalse("label «" + chip[1] + "»", hasEmoji(chip[1]));
                assertFalse("sentence of «" + chip[1] + "»", hasEmoji(chip[2]));
                assertTrue("sentence of «" + chip[1] + "» is not blank", chip[2].trim().length() > 0);
            }
        }
    }

    @Test
    public void iconOfIsTolerantAndMinusOneMeansNoIcon() {
        assertEquals(CgIcons.CHAT, CigramChatSuggest.iconOf(String.valueOf(CgIcons.CHAT)));
        assertEquals(CgIcons.UPDATE, CigramChatSuggest.iconOf(" 0 "));    // 0 is a real glyph in the admin set
        assertEquals(-1, CigramChatSuggest.iconOf(null));
        assertEquals(-1, CigramChatSuggest.iconOf(""));
        assertEquals(-1, CigramChatSuggest.iconOf("📌"));        // a leftover emoji cell draws nothing instead of crashing
    }

    @Test
    public void plusCustomerMessageHasNoEmojiAndStillCarriesTheCode() {
        String named = PlusMsg.message("علي", "ABCD-1234", 3, 7);
        String anon = PlusMsg.message("", "ABCD-1234", 12, 0);
        for (String m : new String[]{named, anon}) {
            assertFalse(hasEmoji(m));
            assertTrue(m.contains("ABCD-1234"));
            assertTrue(m.contains("تم اعتماد اشتراكك في Cigram Plus"));
        }
        assertTrue(named.startsWith("أهلاً علي\n"));
        assertTrue(anon.startsWith("أهلاً بك\n"));
        assertTrue(named.contains("خلال 7 يوماً"));
        assertFalse(anon.contains("يُفعَّل خلال"));
    }
}
