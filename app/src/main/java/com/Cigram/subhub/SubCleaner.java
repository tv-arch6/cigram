package com.Cigram.subhub;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * CigramSubtitleHub — Subtitle Cleaner («حذف الأسطر المكررة والرموز غير المرغوبة»).
 *
 * Every switch is independent and every removal is counted and listed, so the admin sees exactly what left the file
 * before saving it. Times are only touched in one case: a cue that repeats the text of the cue right before it is
 * folded into that cue (its end time is kept), because two identical lines back to back flicker on screen.
 *
 * The viewer's player uses the same class with {@link Options#viewer()} — display-time only, the file on the server
 * is never rewritten from a phone.
 */
public final class SubCleaner {

    private SubCleaner() {}

    public static final class Options {
        /** A cue whose text equals the previous cue's text (touching or overlapping it) is merged into it. */
        public boolean mergeRepeatedCues = true;
        /** The same line twice inside one cue is written once. */
        public boolean dropRepeatedLines = true;
        /** Invisible and broken characters: zero-width, soft hyphen, BOM, U+FFFD, control codes, stray bidi marks. */
        public boolean stripInvisible = true;
        /** «!!!!!!» → «!!», «؟؟؟؟» → «؟؟», «......» → «…», long tatweel runs → one. */
        public boolean squeezePunctuation = true;
        /** Spaces at line ends, double spaces, empty lines inside a cue. */
        public boolean tidySpaces = true;
        /** Cues that are an advert or a download-site stamp (links, «Subtitles by …», «Downloaded from …»). */
        public boolean dropAdverts = true;
        /** {@code <font ...>} colour / size tags (italic, bold and underline are kept). */
        public boolean stripFontTags = true;
        /** {@code {\an8}}-style ASS override blocks left in an SRT. */
        public boolean stripAssTags = false;
        /** Music notes ♪ ♫ and lines that are only notes. */
        public boolean stripMusicNotes = false;
        /** [door slams], (laughs) and SPEAKER: prefixes written for the hard of hearing. */
        public boolean stripHearingImpaired = false;
        /** A cue that has no text left after cleaning is removed. */
        public boolean dropEmptied = true;

        /** Safe defaults for showing a file to a viewer: nothing that changes the meaning of a line. */
        public static Options viewer() {
            Options o = new Options();
            o.stripAssTags = false;
            o.stripMusicNotes = false;
            o.stripHearingImpaired = false;
            return o;
        }

        /** Everything on (the admin's «تنظيف شامل»). */
        public static Options deep() {
            Options o = new Options();
            o.stripAssTags = true;
            o.stripMusicNotes = true;
            o.stripHearingImpaired = true;
            return o;
        }
    }

    public static final class Report {
        public final SubDocument document;
        public final int cuesBefore;
        public final int cuesAfter;
        public final int repeatedCuesMerged;
        public final int repeatedLinesDropped;
        public final int invisibleRemoved;
        public final int punctuationSqueezed;
        public final int spacesTidied;
        public final int advertsDropped;
        public final int tagsStripped;
        public final int musicStripped;
        public final int hearingImpairedStripped;
        public final int emptiedDropped;
        /** What was removed, most telling first (at most 40 entries): "#12 www.example.com". */
        public final List<String> removed;

        Report(SubDocument d, int before, int[] n, List<String> removed) {
            this.document = d;
            this.cuesBefore = before;
            this.cuesAfter = d.size();
            this.repeatedCuesMerged = n[0];
            this.repeatedLinesDropped = n[1];
            this.invisibleRemoved = n[2];
            this.punctuationSqueezed = n[3];
            this.spacesTidied = n[4];
            this.advertsDropped = n[5];
            this.tagsStripped = n[6];
            this.musicStripped = n[7];
            this.hearingImpairedStripped = n[8];
            this.emptiedDropped = n[9];
            this.removed = Collections.unmodifiableList(removed);
        }

        public int total() {
            return repeatedCuesMerged + repeatedLinesDropped + invisibleRemoved + punctuationSqueezed + spacesTidied
                    + advertsDropped + tagsStripped + musicStripped + hearingImpairedStripped + emptiedDropped;
        }

        public boolean changed() {
            return total() > 0;
        }

        public String summary() {
            if (!changed()) return "الملف نظيف · لا شيء يُحذف";
            StringBuilder b = new StringBuilder();
            add(b, repeatedCuesMerged, "مقطع مكرر");
            add(b, repeatedLinesDropped, "سطر مكرر");
            add(b, advertsDropped, "إعلان / رابط");
            add(b, invisibleRemoved, "رمز خفي");
            add(b, punctuationSqueezed, "ترقيم مكرر");
            add(b, tagsStripped, "وسم تنسيق");
            add(b, musicStripped, "رمز موسيقى");
            add(b, hearingImpairedStripped, "وصف صوتي");
            add(b, spacesTidied, "فراغ زائد");
            add(b, emptiedDropped, "مقطع أصبح فارغاً");
            return b.toString();
        }

        private static void add(StringBuilder b, int n, String label) {
            if (n <= 0) return;
            if (b.length() > 0) b.append(" · ");
            b.append(n).append(' ').append(label);
        }
    }

    // ------------------------------------------------------------------ patterns

    /**
     * A whole cue that is nothing but a site stamp / advert. Checked on the plain text. Deliberately narrow: a line of
     * dialogue must never match (so «corrected by my teacher» or «No.To the left» stay), only the stamps that
     * download sites and release groups really write.
     */
    private static final Pattern ADVERT = Pattern.compile(
            "(?is)^\\s*(?:"
                    + ".*(?:https?://|www\\.)\\S+.*"
                    + "|.*\\b[\\w-]{3,}\\.(?-i:com|net|org|tv|io|info|xyz|cc|ws)\\b(?![\\w.]).*"
                    + "|\\W*(?:subtitles?|subs|captions?|sync(?:ed)?|synced\\s*(?:and|&)\\s*corrected|sync\\s*(?:and|&)\\s*corrections?|ripped|encoded|resync(?:ed)?|transcript|improved)\\s+by\\b[^\\n]{0,60}"
                    + "|.*\\bopen\\s*subtitles\\b.*"
                    + "|.*\\b(?:advertise|advertising)\\s+your\\s+product\\b.*"
                    + "|.*\\bsupport\\s+us\\s+and\\s+become\\s+vip\\b.*"
                    + "|.*\\b(?:yts|yify|rarbg|subscene|addic7ed|podnapisi|subdl|opensubtitles)\\b.*"
                    + "|.*(?:تابعونا على|حمّل تطبيق|حمل تطبيق|زوروا موقع|تم التحميل من|حصرياً على موقع).*"
                    + ")\\s*$");
    /** Telegram / social handles used as stamps: «@channel», «t.me/…». */
    private static final Pattern HANDLE = Pattern.compile("(?i)(?:^|\\s)(?:@[A-Za-z0-9_]{4,}|t\\.me/\\S+|telegram\\s*:\\s*\\S+)");
    private static final Pattern FONT_TAG = Pattern.compile("(?i)</?font[^<>]{0,120}>");
    private static final Pattern ASS_TAG = Pattern.compile("\\{\\\\[^}]*\\}");
    private static final Pattern MUSIC = Pattern.compile("[\u2669-\u266F]|\uD83C[\uDFB5\uDFB6]|(?m)^\\s*#\\s*|\\s*#\\s*$");
    private static final Pattern BRACKETED = Pattern.compile("\\[[^\\]\\n]{1,60}\\]|\\([^)\\n]{1,60}\\)|\uFF08[^\uFF09\\n]{1,60}\uFF09");
    private static final Pattern SPEAKER = Pattern.compile("(?m)^(\\s*-?\\s*)[A-Z][A-Z0-9 .'\\-]{1,24}:\\s+");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t\u00A0]{2,}");

    // ------------------------------------------------------------------ clean

    public static Report clean(SubDocument d) {
        return clean(d, new Options());
    }

    public static Report clean(SubDocument d, Options opt) {
        Options o = opt == null ? new Options() : opt;
        int[] n = new int[10];
        List<String> removed = new ArrayList<String>();
        List<SubCue> out = new ArrayList<SubCue>(d.size());
        for (SubCue c : d.cues) {
            if (c.isBlank()) { out.add(c); continue; }            // empty cues are the Missing Lines Detector's business
            String plain = c.plainText().trim();
            if (o.dropAdverts && isAdvert(plain)) {
                n[5]++;
                note(removed, c, plain);
                continue;
            }
            String text = cleanText(c.text, o, n);
            if (o.dropEmptied && SubCue.plain(text).trim().length() == 0) {
                n[9]++;
                note(removed, c, plain);
                continue;
            }
            SubCue cur = text.equals(c.text) ? c : c.withText(text);
            if (o.mergeRepeatedCues && !out.isEmpty()) {
                SubCue prev = out.get(out.size() - 1);
                // "touching": the repeat starts before the previous one has been gone for a quarter of a second
                if (!prev.isBlank() && key(prev).equals(key(cur)) && cur.startMs - prev.endMs <= 250 && cur.startMs >= prev.startMs) {
                    out.set(out.size() - 1, prev.withTimes(prev.startMs, Math.max(prev.endMs, cur.endMs)));
                    n[0]++;
                    note(removed, c, plain);
                    continue;
                }
            }
            out.add(cur);
        }
        return new Report(d.withCues(out), d.size(), n, removed);
    }

    /** Cleans one cue text with the per-line switches (no cue is dropped or merged here). Used by the player at display time. */
    public static String cleanText(String text, Options o) {
        return cleanText(text, o == null ? new Options() : o, new int[10]);
    }

    static boolean isAdvert(String plain) {
        if (plain.length() == 0 || plain.length() > 220) return false;
        if (ADVERT.matcher(plain).matches()) return true;
        // a lone handle on a short line («@CigramSubs») is a stamp; a handle inside a sentence of dialogue is not
        return plain.length() <= 60 && HANDLE.matcher(plain).find() && plain.split("\\s+").length <= 5;
    }

    private static String key(SubCue c) {
        return c.plainText().replaceAll("\\s+", " ").trim();
    }

    private static void note(List<String> removed, SubCue c, String plain) {
        if (removed.size() >= 40) return;
        String p = plain.replace('\n', ' ');
        removed.add("#" + c.index + " " + (p.length() > 70 ? p.substring(0, 70) + "…" : p));
    }

    private static String cleanText(String input, Options o, int[] n) {
        String t = input;
        if (o.stripInvisible) {
            StringBuilder b = null;
            for (int i = 0; i < t.length(); i++) {
                char ch = t.charAt(i);
                if (isInvisibleJunk(ch, t, i)) {
                    if (b == null) { b = new StringBuilder(t.length()); b.append(t, 0, i); }
                    n[2]++;
                } else if (b != null) {
                    b.append(ch);
                }
            }
            if (b != null) t = b.toString();
        }
        if (o.stripFontTags) {
            String r = FONT_TAG.matcher(t).replaceAll("");
            if (!r.equals(t)) { n[6]++; t = r; }
        }
        if (o.stripAssTags) {
            String r = ASS_TAG.matcher(t).replaceAll("");
            if (!r.equals(t)) { n[6]++; t = r; }
        }
        if (o.stripMusicNotes) {
            String r = MUSIC.matcher(t).replaceAll("");
            if (!r.equals(t)) { n[7]++; t = r; }
        }
        if (o.stripHearingImpaired) {
            String r = BRACKETED.matcher(t).replaceAll("");
            r = SPEAKER.matcher(r).replaceAll("$1");
            if (!r.equals(t)) { n[8]++; t = r; }
        }
        if (o.squeezePunctuation) {
            String r = t.replaceAll("\\.{4,}", "…").replaceAll("…{2,}", "…")
                    .replaceAll("!{3,}", "!!").replaceAll("\\?{3,}", "??").replaceAll("؟{3,}", "؟؟")
                    .replaceAll("ـ{2,}", "ـ").replaceAll("-{4,}", "—").replaceAll("،{2,}", "،").replaceAll(",{2,}", ",");
            if (!r.equals(t)) { n[3]++; t = r; }
        }
        // per line: spaces, repeated lines, emptied lines
        String[] lines = t.split("\n", -1);
        List<String> keep = new ArrayList<String>(lines.length);
        boolean spaceChange = false;
        for (String line : lines) {
            String l = line;
            if (o.tidySpaces) {
                String r = MULTI_SPACE.matcher(l.replace('\t', ' ')).replaceAll(" ").trim();
                if (!r.equals(l)) spaceChange = true;
                l = r;
            }
            String bare = SubCue.plain(l).trim();
            if (bare.length() == 0 || bare.matches("[-–—\\s.]*")) {
                if (o.tidySpaces || o.stripMusicNotes || o.stripHearingImpaired) {
                    if (line.trim().length() > 0 || o.tidySpaces) spaceChange |= line.length() > 0;
                    continue;                                   // a line with nothing to read
                }
            }
            if (o.dropRepeatedLines && !keep.isEmpty() && SubCue.plain(keep.get(keep.size() - 1)).trim().equals(bare)) {
                n[1]++;
                continue;
            }
            keep.add(l);
        }
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < keep.size(); i++) {
            if (i > 0) b.append('\n');
            b.append(keep.get(i));
        }
        String res = b.toString();
        if (spaceChange && !res.equals(input)) n[4]++;
        return res;
    }

    /**
     * Characters nobody should see in a subtitle. Bidi marks that DO something useful are kept: RLM / LRM (U+200F /
     * U+200E, the hub adds them itself) and the zero-width joiner / non-joiner BETWEEN two Arabic letters (Persian,
     * Kurdish and some Arabic words need them).
     */
    private static boolean isInvisibleJunk(char ch, String t, int i) {
        if (ch == '\n') return false;
        if (ch == '\uFFFD' || ch == '\uFEFF' || ch == '\u00AD' || ch == '\u200B' || ch == '\u2060') return true;
        if (ch == '\u200C' || ch == '\u200D') {
            boolean before = i > 0 && SubCodec.isArabicLetter(t.charAt(i - 1));
            boolean after = i + 1 < t.length() && SubCodec.isArabicLetter(t.charAt(i + 1));
            return !(before && after);
        }
        if (ch >= 0x202A && ch <= 0x202E) return true;          // embedding / override controls: leak and reverse lines
        if (ch < 0x20 && ch != '\t') return true;
        return ch >= 0x7F && ch <= 0x9F;
    }
}
