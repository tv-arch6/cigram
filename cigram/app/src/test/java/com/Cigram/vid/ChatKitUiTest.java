package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 19.2 — the new conversation pieces on the REAL Android view system (Robolectric, native graphics): they are
 * created, measured, laid out and drawn exactly as on a phone, and the pictures are written to build/ui-shots so a
 * person can look at them. This catches what a compiler cannot: a crash while a view is built or drawn, a missing
 * asset, an emoji that is not found, a layout that collapses.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ChatKitUiTest {

    static Bitmap shoot(View v, int w, int h, String name) throws Exception {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), h > 0
                ? View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY) : View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(Math.max(1, v.getMeasuredWidth()), Math.max(1, v.getMeasuredHeight()), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF000000);
        v.draw(c);
        File dir = new File("build/ui-shots");
        dir.mkdirs();
        FileOutputStream os = new FileOutputStream(new File(dir, name + ".png"));
        b.compress(Bitmap.CompressFormat.PNG, 100, os);
        os.close();
        return b;
    }

    /** Share of pixels that are not the background: a view that drew nothing fails. */
    static double inked(Bitmap b, int background) {
        int n = 0, total = 0;
        for (int y = 0; y < b.getHeight(); y += 3) for (int x = 0; x < b.getWidth(); x += 3) { total++; if (b.getPixel(x, y) != background) n++; }
        return n / (double) total;
    }

    @Test
    public void theEmojiAtlasLoadsWithAllEightGroupsAndEveryStripDecodes() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        CigramEmoji.Index ix = CigramEmoji.index(a);
        assertEquals(1914, ix.count);
        assertEquals(8, CigramEmoji.GROUPS.length);
        for (int g = 0; g < 8; g++) assertTrue("group " + g + " is empty", ix.groupStart[g + 1] > ix.groupStart[g]);
        int strips = (ix.count + ix.per - 1) / ix.per;
        for (int s = 0; s < strips; s++) {
            Bitmap b = CigramEmoji.strip(a, s);
            assertNotNull("strip " + s, b);
            assertEquals(ix.cell * ix.per, b.getWidth());
            assertEquals(ix.cell, b.getHeight());
        }
        CigramEmoji.trimMemory();
    }

    @Test
    public void emojiInTextAreFoundAsWholeSequencesAndPlainTextIsLeftAlone() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        CigramEmoji.Index ix = CigramEmoji.index(a);
        int[] len = new int[1];
        String family = "👨‍👩‍👧";      // man + woman + girl, one emoji
        assertTrue(CigramEmoji.find(ix, family, 0, len) >= 0);
        assertEquals(family.length(), len[0]);
        String flag = "🇮🇶";                               // Iraq
        assertTrue(CigramEmoji.find(ix, "x" + flag, 1, len) >= 0);
        assertEquals(4, len[0]);
        String wave = "👋🏽";                               // waving hand + skin tone: one span
        assertTrue(CigramEmoji.find(ix, wave, 0, len) >= 0);
        assertEquals(4, len[0]);
        assertEquals(-1, CigramEmoji.find(ix, "مرحبا 123 #5 * © hello", 0, len));
        String plain = "مرحباً، السعر 25$ والمدة 7 أيام (c) #1";
        assertTrue("no emoji: the same object comes back", CigramEmoji.apply(a, plain) == plain);
        CharSequence spanned = CigramEmoji.apply(a, "أهلاً 👋 بك " + flag);
        assertEquals(2, ((android.text.Spanned) spanned).getSpans(0, spanned.length(), CigramEmoji.EmojiSpan.class).length);
        assertTrue(CigramEmoji.isJumbo(a, "😀 ❤️"));
        assertTrue(!CigramEmoji.isJumbo(a, "ok 😀"));
    }

    @Test
    public void typingAndDeletingKeepsTheFieldPlainUnicodeAndRemovesAWholeEmojiPerPress() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        EditText in = new EditText(a);
        CigramEmoji.attach(in);
        in.setText("هلا ");
        in.setSelection(in.length());
        String family = "👨‍👩‍👧";
        CigramEmoji.insert(in, family);
        CigramEmoji.insert(in, "😀");
        assertEquals("هلا " + family + "😀", in.getText().toString());
        assertEquals(2, in.getText().getSpans(0, in.length(), CigramEmoji.EmojiSpan.class).length);
        in.setSelection(in.length());
        CigramEmoji.backspace(in);
        assertEquals("هلا " + family, in.getText().toString());
        CigramEmoji.backspace(in);
        assertEquals("هلا ", in.getText().toString());
        CigramEmoji.backspace(in);
        assertEquals("هلا", in.getText().toString());
        assertEquals(0, in.getText().getSpans(0, in.length(), CigramEmoji.EmojiSpan.class).length);
    }

    @Test
    public void theEmojiKeyboardAndTheSuggestionChipsAreBuiltLaidOutAndDrawn() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        final String[] picked = {null};
        CigramEmojiPanel panel = new CigramEmojiPanel(a, 0xFF0A0A0B, 0xFFE21B14, new CigramEmojiPanel.Listener() {
            @Override public void onEmoji(String e) { picked[0] = e; }
            @Override public void onBackspace() { }
        });
        FrameLayout host = new FrameLayout(a);
        host.addView(panel);
        a.setContentView(host);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        Bitmap shot = shoot(panel, 720, 572, "emoji_panel");
        assertTrue("the keyboard drew its emoji", inked(shot, 0xFF0A0A0B) > 0.10);

        CigramChatSuggest chips = new CigramChatSuggest(a);
        final String[] sentence = {null};
        chips.setListener(new CigramChatSuggest.Listener() { @Override public void onPick(String t) { sentence[0] = t; } });
        chips.setItems(CigramChatSuggest.forUser(true, "", false));
        Bitmap row = shoot(chips, 720, 0, "suggest_user_empty");
        assertTrue(row.getHeight() > 60);
        assertTrue("chips are visible", inked(row, 0xFF000000) > 0.08);
        chips.setItems(CigramChatSuggest.forUser(false, "quote", false));
        shoot(chips, 720, 0, "suggest_user_quote");
        chips.setItems(CigramChatSuggest.forDeveloper(false, "image"));
        shoot(chips, 720, 0, "suggest_developer_media");
        // every suggestion carries an emoji the atlas really has
        CigramEmoji.Index ix = CigramEmoji.index(a);
        String[][][] all = {CigramChatSuggest.forUser(true, "", false), CigramChatSuggest.forUser(false, "quote", false),
                CigramChatSuggest.forUser(false, "", true), CigramChatSuggest.forDeveloper(true, ""), CigramChatSuggest.forDeveloper(false, "image"), CigramChatSuggest.forDeveloper(false, "")};
        for (String[][] set : all) for (String[] it : set) {
            assertTrue("emoji of «" + it[1] + "» is in the atlas", ix.lookup.containsKey(it[0]) || ix.lookup.containsKey(it[0].replace("️", "")));
            assertTrue(it[2].length() > 0);
        }
    }
}
