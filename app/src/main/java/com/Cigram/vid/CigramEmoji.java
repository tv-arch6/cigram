package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ReplacementSpan;
import android.util.LruCache;
import android.widget.EditText;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Cigram emoji engine: every emoji is drawn from the app's own atlas (assets/emoji), not from the phone's emoji font.
 * The private conversation therefore looks the same on both sides and on every phone — an old Android 7 device and a
 * new one show the same glossy artwork, never the system (Android) emoji and never an empty box for a new emoji.
 *
 * <ul>
 *   <li>Atlas: 1,914 emoji in the eight keyboard groups (smileys &amp; people, animals, food, activity, travel, objects,
 *   symbols, flags), 64 px cells packed 32 per strip. A strip is decoded only when one of its emoji is needed and kept
 *   in a small memory cache (smaller on low-RAM phones), so the whole set never sits in memory.</li>
 *   <li>Text stays plain Unicode: what is typed, stored and sent is the standard emoji sequence. Only the drawing is
 *   replaced, by a span over the sequence ({@link #apply}); copy / paste and other apps see normal emoji.</li>
 *   <li>An emoji the atlas does not have (a future one) is left to the system font; nothing breaks.</li>
 * </ul>
 * Artwork licences: assets/emoji/LICENSES.txt (Fluent Emoji — MIT, Twemoji — CC-BY 4.0).
 */
final class CigramEmoji {

    private CigramEmoji() { }

    static final String DIR = "emoji";
    /** Group ids in keyboard order. */
    static final String[] GROUPS = {"smileys", "animals", "food", "activity", "travel", "objects", "symbols", "flags"};
    static final String[] GROUP_LABELS = {"الوجوه والأشخاص", "الحيوانات والطبيعة", "الطعام والشراب", "الأنشطة", "السفر والأماكن", "الأشياء", "الرموز", "الأعلام"};

    static final class Index {
        int cell = 64, per = 32, count;
        /** Emoji sequence of every cell, fully qualified. */
        String[] seq = new String[0];
        /** groupStart[g] .. groupStart[g + 1] - 1 are the cells of group g. */
        int[] groupStart = new int[GROUPS.length + 1];
        final HashMap<String, Integer> lookup = new HashMap<String, Integer>(4096);
        int maxLen = 1;

        boolean ok() { return count > 0; }
    }

    private static volatile Index INDEX;
    private static final Object LOCK = new Object();

    /** The atlas index (loaded once, ~60 KB of text). Never null; empty when the assets are missing. */
    static Index index(Context c) {
        Index ix = INDEX;
        if (ix != null) return ix;
        synchronized (LOCK) {
            if (INDEX != null) return INDEX;
            ix = new Index();
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(c.getApplicationContext().getAssets().open(DIR + "/index.txt"), "UTF-8"));
                String head = r.readLine();
                String[] h = head == null ? new String[0] : head.split(" ");
                if (h.length >= 4) { ix.cell = Integer.parseInt(h[1]); ix.per = Integer.parseInt(h[2]); }
                ArrayList<String> seqs = new ArrayList<String>(2048);
                int[] counts = new int[GROUPS.length];
                String line;
                while ((line = r.readLine()) != null) {
                    int sp = line.indexOf(' ');
                    if (sp <= 0) continue;
                    int g = Integer.parseInt(line.substring(0, sp));
                    if (g < 0 || g >= GROUPS.length) continue;
                    String s = fromHex(line.substring(sp + 1).trim());
                    if (s.length() == 0) continue;
                    counts[g]++;
                    seqs.add(s);
                }
                ix.seq = seqs.toArray(new String[0]);
                ix.count = ix.seq.length;
                int at = 0;
                for (int g = 0; g < GROUPS.length; g++) { ix.groupStart[g] = at; at += counts[g]; }
                ix.groupStart[GROUPS.length] = at;
                for (int i = 0; i < ix.count; i++) {
                    String s = ix.seq[i];
                    if (!ix.lookup.containsKey(s)) ix.lookup.put(s, i);
                    ix.maxLen = Math.max(ix.maxLen, s.length());
                    String bare = s.replace("️", "");
                    if (bare.length() > 0 && !bare.equals(s) && !ix.lookup.containsKey(bare) && unqualifiedAllowed(bare)) ix.lookup.put(bare, i);
                }
            } catch (Throwable t) {
                android.util.Log.w("CigramEmoji", "index not loaded", t);
                ix = new Index();
            } finally {
                if (r != null) try { r.close(); } catch (Throwable ignored) { }
            }
            INDEX = ix;
            return ix;
        }
    }

    private static String fromHex(String unified) {
        StringBuilder b = new StringBuilder();
        for (String p : unified.split("-")) {
            try { b.appendCodePoint(Integer.parseInt(p, 16)); } catch (Throwable ignored) { return ""; }
        }
        return b.toString();
    }

    /**
     * «©», «™», digits, arrows and small geometric shapes are ordinary text unless the emoji selector (U+FE0F) follows
     * them; the other symbols (☀ ❤ ✈ …) are drawn as emoji either way, as phones do.
     */
    private static boolean unqualifiedAllowed(String bare) {
        char c = bare.charAt(0);
        if (Character.isHighSurrogate(c)) return true;
        if (bare.length() > 1) return c >= 0x2600;                     // sequences that only lose an inner selector
        if (c < 0x231A) return false;
        if (c >= 0x25AA && c <= 0x25FE) return false;
        if (c >= 0x2194 && c <= 0x21AA) return false;
        return true;
    }

    // ------------------------------------------------------------------ atlas

    private static LruCache<Integer, Bitmap> STRIPS;
    private static final Paint BITMAP = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    private static LruCache<Integer, Bitmap> strips(Context c) {
        if (STRIPS == null) {
            boolean low = false;
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                low = am != null && (am.isLowRamDevice() || am.getMemoryClass() <= 128);
            } catch (Throwable ignored) { }
            STRIPS = new LruCache<Integer, Bitmap>(low ? 6 : 16);       // one strip = 2048 x 64 px = 512 KB
        }
        return STRIPS;
    }

    /** Frees the decoded strips (memory pressure, leaving the conversation). They are decoded again on demand. */
    static void trimMemory() {
        LruCache<Integer, Bitmap> s = STRIPS;
        if (s != null) s.evictAll();
    }

    static Bitmap strip(Context c, int n) {
        LruCache<Integer, Bitmap> cache = strips(c);
        Bitmap b = cache.get(n);
        if (b != null && !b.isRecycled()) return b;
        InputStream in = null;
        try {
            in = c.getApplicationContext().getAssets().open(DIR + "/strip_" + (n < 10 ? "0" : "") + n + ".webp");
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inScaled = false;
            b = BitmapFactory.decodeStream(in, null, o);
            if (b != null) cache.put(n, b);
            return b;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static final Rect SRC = new Rect();

    /** Draws cell {@code idx} into {@code dst}. Returns false when the artwork is not available. */
    static boolean draw(Context c, Canvas canvas, int idx, RectF dst) {
        Index ix = index(c);
        if (idx < 0 || idx >= ix.count) return false;
        Bitmap b = strip(c, idx / ix.per);
        if (b == null) return false;
        int x = (idx % ix.per) * ix.cell;
        synchronized (SRC) {
            SRC.set(x, 0, x + ix.cell, ix.cell);
            canvas.drawBitmap(b, SRC, dst, BITMAP);
        }
        return true;
    }

    // ------------------------------------------------------------------ finding emoji in text

    private static boolean emojiPart(char c) {
        return Character.isSurrogate(c) || c == 0x200D || c == 0xFE0F || c == 0x20E3 || (c >= 0x203C && c <= 0x3299) || c == 0xA9 || c == 0xAE;
    }

    private static boolean canStart(CharSequence s, int i) {
        char c = s.charAt(i);
        if (c >= 0xD83C && c <= 0xD83E) return true;
        if ((c >= 0x203C && c <= 0x3299) || c == 0xA9 || c == 0xAE) return true;
        if ((c >= '0' && c <= '9') || c == '#' || c == '*') {
            char n = i + 1 < s.length() ? s.charAt(i + 1) : 0;
            return n == 0xFE0F || n == 0x20E3;
        }
        return false;
    }

    /**
     * The atlas cell of the emoji that starts at {@code at}, or -1. {@code outLen[0]} = how many chars it covers
     * (a skin-tone modifier after the emoji is covered too: the atlas draws the default tone).
     */
    static int find(Index ix, CharSequence s, int at, int[] outLen) {
        if (!ix.ok() || at >= s.length() || !canStart(s, at)) return -1;
        int end = at + 1;
        int limit = Math.min(s.length(), at + ix.maxLen);
        while (end < limit && emojiPart(s.charAt(end))) end++;
        for (int e = end; e > at; e--) {
            if (e < s.length() && Character.isLowSurrogate(s.charAt(e))) continue;   // never cut a pair
            Integer idx = ix.lookup.get(s.subSequence(at, e).toString());
            if (idx == null) continue;
            int len = e - at;
            // 🏻..🏿 directly after the emoji
            if (e + 1 < s.length() && s.charAt(e) == 0xD83C && s.charAt(e + 1) >= 0xDFFB && s.charAt(e + 1) <= 0xDFFF) len += 2;
            else if (e < s.length() && s.charAt(e) == 0xFE0F) len += 1;
            outLen[0] = len;
            return idx;
        }
        return -1;
    }

    /** True when the text is nothing but one to three emoji (drawn large, as messengers do). */
    static boolean isJumbo(Context c, CharSequence s) {
        if (s == null) return false;
        String t = s.toString().trim();
        if (t.length() == 0 || t.length() > 3 * 16) return false;
        Index ix = index(c);
        int[] len = new int[1];
        int n = 0;
        for (int i = 0; i < t.length(); ) {
            if (t.charAt(i) == ' ') { i++; continue; }
            if (find(ix, t, i, len) < 0) return false;
            i += len[0];
            if (++n > 3) return false;
        }
        return n > 0;
    }

    // ------------------------------------------------------------------ spans

    static final class EmojiSpan extends ReplacementSpan {
        final int idx;
        private final Context app;
        private final RectF dst = new RectF();

        EmojiSpan(Context c, int idx) {
            this.app = c.getApplicationContext();
            this.idx = idx;
        }

        private static float side(Paint p) {
            return p.getTextSize() * 1.24f;
        }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return Math.round(side(paint) + paint.getTextSize() * 0.08f);
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            float s = side(paint);
            float pad = paint.getTextSize() * 0.04f;
            float mid = y + (paint.ascent() + paint.descent()) / 2f;
            dst.set(x + pad, mid - s / 2f, x + pad + s, mid + s / 2f);
            if (!CigramEmoji.draw(app, canvas, idx, dst)) {
                canvas.drawText(text, start, end, x, y, paint);   // artwork missing: the system glyph
            }
        }
    }

    /**
     * {@code text} with every known emoji drawn from the atlas. Returns the SAME object when it holds no emoji
     * (the common case costs one scan and no allocation).
     */
    static CharSequence apply(Context c, CharSequence text) {
        if (text == null || text.length() == 0) return text;
        Index ix = index(c);
        if (!ix.ok()) return text;
        SpannableStringBuilder out = null;
        int[] len = new int[1];
        int n = text.length();
        for (int i = 0; i < n; ) {
            char ch = text.charAt(i);
            if (ch < 0x23 || (ch > 0xAE && ch < 0x203C)) { i++; continue; }     // fast path: Latin / Arabic letters
            int idx = find(ix, text, i, len);
            if (idx < 0) { i++; continue; }
            if (out == null) out = new SpannableStringBuilder(text);
            out.setSpan(new EmojiSpan(c, idx), i, i + len[0], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i += len[0];
        }
        return out == null ? text : out;
    }

    /** Sets the text of a bubble / label with atlas emoji. */
    static void bind(TextView tv, CharSequence text) {
        try {
            tv.setText(apply(tv.getContext(), text), TextView.BufferType.SPANNABLE);
        } catch (Throwable t) {
            tv.setText(text);
        }
    }

    /**
     * Keeps the emoji of an input field drawn from the atlas while the user types, pastes or deletes. The text itself
     * is not modified (only spans are added), so {@code getText().toString()} stays plain Unicode.
     */
    static void attach(final EditText input) {
        final Context c = input.getContext();
        input.addTextChangedListener(new TextWatcher() {
            private boolean busy;
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable e) {
                if (busy) return;
                busy = true;
                try {
                    sync(c, e);
                } catch (Throwable ignored) {
                } finally {
                    busy = false;
                }
            }
        });
    }

    private static void sync(Context c, Editable e) {
        Index ix = index(c);
        if (!ix.ok()) return;
        EmojiSpan[] old = e.getSpans(0, e.length(), EmojiSpan.class);
        // spans whose text was edited away or cut in half are dropped; intact ones are kept (no flicker while typing)
        int[] len = new int[1];
        for (EmojiSpan sp : old) {
            int s = e.getSpanStart(sp), en = e.getSpanEnd(sp);
            if (s < 0 || en <= s || find(ix, e, s, len) != sp.idx || s + len[0] != en) e.removeSpan(sp);
        }
        int n = e.length();
        for (int i = 0; i < n; ) {
            char ch = e.charAt(i);
            if (ch < 0x23 || (ch > 0xAE && ch < 0x203C)) { i++; continue; }
            int idx = find(ix, e, i, len);
            if (idx < 0) { i++; continue; }
            if (e.getSpans(i, i + len[0], EmojiSpan.class).length == 0) {
                e.setSpan(new EmojiSpan(c, idx), i, i + len[0], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            i += len[0];
        }
    }

    /** Deletes the character before the cursor; a whole emoji (pair, sequence, flag) goes in one press. */
    static void backspace(EditText input) {
        Editable e = input.getText();
        int a = Math.max(0, input.getSelectionStart()), b = Math.max(0, input.getSelectionEnd());
        if (a != b) { e.delete(Math.min(a, b), Math.max(a, b)); return; }
        if (a == 0) return;
        EmojiSpan[] sp = e.getSpans(a - 1, a, EmojiSpan.class);
        for (EmojiSpan s : sp) {
            int st = e.getSpanStart(s), en = e.getSpanEnd(s);
            if (en == a && st >= 0) { e.delete(st, en); return; }
        }
        int start = a - 1;
        if (start > 0 && Character.isLowSurrogate(e.charAt(start)) && Character.isHighSurrogate(e.charAt(start - 1))) start--;
        e.delete(start, a);
    }

    /** Inserts an emoji at the cursor (replacing a selection). */
    static void insert(EditText input, String emoji) {
        Editable e = input.getText();
        int a = Math.max(0, input.getSelectionStart()), b = Math.max(0, input.getSelectionEnd());
        e.replace(Math.min(a, b), Math.max(a, b), emoji, 0, emoji.length());
    }

    // ------------------------------------------------------------------ recently used

    private static final String PREFS = "cigram_emoji";
    private static final int RECENT_MAX = 40;

    static ArrayList<Integer> recents(Context c) {
        ArrayList<Integer> out = new ArrayList<Integer>();
        Index ix = index(c);
        try {
            String raw = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("recent", "");
            for (String s : raw.split("\n")) {
                if (s.length() == 0) continue;
                Integer idx = ix.lookup.get(s);
                if (idx != null && !out.contains(idx)) out.add(idx);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    static void remember(Context c, String emoji) {
        try {
            SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = p.getString("recent", "");
            StringBuilder b = new StringBuilder(emoji);
            int n = 1;
            for (String s : raw.split("\n")) {
                if (s.length() == 0 || s.equals(emoji)) continue;
                if (n++ >= RECENT_MAX) break;
                b.append('\n').append(s);
            }
            p.edit().putString("recent", b.toString()).apply();
        } catch (Throwable ignored) { }
    }
}
