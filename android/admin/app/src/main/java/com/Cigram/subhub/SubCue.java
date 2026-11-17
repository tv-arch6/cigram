package com.Cigram.subhub;

/**
 * CigramSubtitleHub — one subtitle cue, exactly as it is in the source file.
 *
 * Times are integer milliseconds on the media timeline. A cue is immutable: a timing correction or a translation
 * produces a NEW cue with the same {@link #index}, so the link between an original cue and its translated twin is
 * never lost (rule: "maintain the relationship between original and translated cue indices").
 *
 * Shared, byte-identical, between the Cigram app and Cigram Admin. Pure Java (no Android classes) so it is unit-tested
 * on the JVM.
 */
public final class SubCue {

    /** 1-based position in the source file (source order, not sorted order). */
    public final int index;
    /** SRT number / WebVTT identifier as written in the source ("" when absent). */
    public final String id;
    public final long startMs;
    public final long endMs;
    /** Cue text, lines separated by '\n'. Formatting tags ({@code <i>}, {@code {\an8}}, VTT tags) are kept. */
    public final String text;
    /** WebVTT cue settings ("line:10% align:start ..."), "" for SRT. Kept so positioning survives a round trip. */
    public final String settings;

    public SubCue(int index, String id, long startMs, long endMs, String text, String settings) {
        this.index = index;
        this.id = id == null ? "" : id;
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text == null ? "" : text;
        this.settings = settings == null ? "" : settings.trim();
    }

    public long durationMs() {
        return endMs - startMs;
    }

    public SubCue withTimes(long start, long end) {
        return new SubCue(index, id, start, end, text, settings);
    }

    public SubCue withText(String newText) {
        return new SubCue(index, id, startMs, endMs, newText, settings);
    }

    /** Text without markup: {@code <i>}, {@code <c.x>}, {@code <v Name>}, {@code {\an8}}, ASS overrides, inline timestamps. */
    public String plainText() {
        return plain(text);
    }

    public static String plain(String s) {
        if (s == null || s.length() == 0) return "";
        String t = s.replaceAll("\\{\\\\[^}]*\\}", "");          // {\an8} {\i1} ...
        t = t.replaceAll("<\\d{1,3}:\\d{2}(?::\\d{2})?[.,]\\d{1,3}>", ""); // VTT karaoke timestamps
        t = t.replaceAll("</?[A-Za-z][^<>]{0,80}>", "");          // <i> <b> <u> <font ...> <c.x> <v Bob> <ruby> <rt> <lang>
        t = t.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&nbsp;", " ").replace("\\N", "\n").replace("\\h", " ");
        return t;
    }

    /** Text lines (markup removed), empty lines dropped. */
    public String[] plainLines() {
        String[] raw = plainText().split("\n", -1);
        int n = 0;
        for (String r : raw) if (r.trim().length() > 0) n++;
        String[] out = new String[n];
        int k = 0;
        for (String r : raw) if (r.trim().length() > 0) out[k++] = r.trim();
        return out;
    }

    public boolean isBlank() {
        return plainText().trim().length() == 0;
    }

    @Override
    public String toString() {
        return "#" + index + " " + SubCodec.stamp(startMs, ',') + " --> " + SubCodec.stamp(endMs, ',') + " " + text.replace('\n', '|');
    }
}
