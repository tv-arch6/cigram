package com.Cigram.subhub;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CigramSubtitleHub — a parsed subtitle file. Cues stay in SOURCE order (a validator reports disorder; nothing is
 * silently re-sorted). Header blocks of WebVTT files (STYLE / REGION / X-TIMESTAMP-MAP ...) are kept verbatim.
 */
public final class SubDocument {

    public enum Format { SRT, VTT }

    public final Format format;
    public final List<SubCue> cues;
    /** WebVTT only: the lines after "WEBVTT" up to the first cue (header, STYLE, REGION blocks). "" for SRT. */
    public final String vttHeader;
    /** How the bytes were decoded: UTF-8, UTF-8+BOM, UTF-16LE, UTF-16BE, windows-1256, and "+mojibake-repaired". */
    public final String encoding;
    /** True when the file declared X-TIMESTAMP-MAP (an HLS fragment). Its cues may need a reviewed offset. */
    public final boolean hasTimestampMap;
    /** The raw X-TIMESTAMP-MAP line ("" when absent). Recorded, never applied automatically. */
    public final String timestampMap;
    /** Parser notes (skipped blocks, invalid stamps ...) in Arabic, for the admin preview. */
    public final List<String> warnings;
    /** Timing lines that could not be read (the cue was skipped). */
    public final int skippedCues;
    /** Cues whose fraction of a second was not written with 3 digits (",5" / ",50" / ".5000") — ExoPlayer misreads those. */
    public final int nonStandardFractions;
    /** Cues that used '.' instead of ',' in an SRT file (ExoPlayer 2.19 drops such cues). */
    public final int dotSeparatorsInSrt;

    public SubDocument(Format format, List<SubCue> cues, String vttHeader, String encoding, String timestampMap,
                       List<String> warnings, int skippedCues, int nonStandardFractions, int dotSeparatorsInSrt) {
        this.format = format;
        this.cues = Collections.unmodifiableList(new ArrayList<SubCue>(cues));
        this.vttHeader = vttHeader == null ? "" : vttHeader;
        this.encoding = encoding == null ? "UTF-8" : encoding;
        this.timestampMap = timestampMap == null ? "" : timestampMap;
        this.hasTimestampMap = this.timestampMap.length() > 0;
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings == null ? Collections.<String>emptyList() : warnings));
        this.skippedCues = skippedCues;
        this.nonStandardFractions = nonStandardFractions;
        this.dotSeparatorsInSrt = dotSeparatorsInSrt;
    }

    public int size() {
        return cues.size();
    }

    public boolean isEmpty() {
        return cues.isEmpty();
    }

    /** Same document with other cues (format, header and decoding notes kept). */
    public SubDocument withCues(List<SubCue> newCues) {
        return new SubDocument(format, newCues, vttHeader, encoding, timestampMap, warnings, skippedCues, nonStandardFractions, dotSeparatorsInSrt);
    }

    public SubDocument asFormat(Format f) {
        return new SubDocument(f, cues, f == Format.VTT ? vttHeader : "", encoding, f == Format.VTT ? timestampMap : "", warnings, skippedCues, nonStandardFractions, dotSeparatorsInSrt);
    }

    /** Last end time (0 when empty). */
    public long lastEndMs() {
        long m = 0;
        for (SubCue c : cues) m = Math.max(m, c.endMs);
        return m;
    }
}
