package com.Cigram.vid;

import android.content.Context;

import com.Cigram.subhub.SubAutoSync;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.audio.ForwardingAudioSink;
import com.google.android.exoplayer2.util.MimeTypes;

import java.nio.ByteBuffer;

/**
 * Subtitle Auto Sync, the listening half: the film's own decoded sound is reduced to "how loud was the speech band in
 * each 50 ms" while it is played ({@link SubAutoSync.ActivityBuilder}). Nothing is recorded, stored or sent anywhere,
 * and the microphone is not involved — the numbers come from the audio the player is already decoding.
 *
 * How: ExoPlayer lets an app wrap its audio output ({@link ForwardingAudioSink}, the supported extension point). The
 * wrapper passes every call straight through; in {@code handleBuffer} it additionally hands a read-only view of the
 * PCM buffer and its media time to the builder. Anything unexpected inside the tap is swallowed: playback never
 * depends on it. Compressed pass-through audio (no PCM in the app) simply leaves the builder empty.
 *
 * Switch off: {@code SharedPreferences("subtitle_settings").autosync_tap = false} (the player then uses the stock
 * renderers factory exactly as before 19.2).
 */
final class CigramAudioTap {

    /** ExoPlayer adds this to every timestamp of the first media period (MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US). */
    private static final long RENDERER_OFFSET_US = 1000000000000L;

    final SubAutoSync.ActivityBuilder sound = new SubAutoSync.ActivityBuilder();
    private volatile boolean listening;
    private volatile long lastMediaUs = -1;

    private CigramAudioTap() { }

    /** Only titles that open with a subtitle are listened to (nothing to align otherwise). */
    static boolean wanted(Context c, String subtitleUrl) {
        if (subtitleUrl == null || subtitleUrl.trim().length() == 0) return false;
        try {
            return c.getSharedPreferences("subtitle_settings", Context.MODE_PRIVATE).getBoolean("autosync_tap", true);
        } catch (Throwable t) {
            return false;
        }
    }

    static CigramAudioTap create() {
        return new CigramAudioTap();
    }

    /** Media time (ms) of the newest sound heard, -1 when nothing was heard yet. */
    long heardUpToMs() {
        return lastMediaUs < 0 ? -1 : lastMediaUs / 1000L;
    }

    /** The stock renderers factory with the tap on its audio output. */
    DefaultRenderersFactory renderersFactory(Context context) {
        final CigramAudioTap tap = this;
        return new DefaultRenderersFactory(context) {
            @Override protected AudioSink buildAudioSink(Context c, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams, boolean enableOffload) {
                AudioSink real = super.buildAudioSink(c, enableFloatOutput, enableAudioTrackPlaybackParams, enableOffload);
                if (real == null) return null;
                try {
                    return new Sink(real, tap);
                } catch (Throwable t) {
                    return real;
                }
            }
        };
    }

    private static final class Sink extends ForwardingAudioSink {
        private final CigramAudioTap tap;
        private long lastPts = Long.MIN_VALUE;

        Sink(AudioSink sink, CigramAudioTap tap) {
            super(sink);
            this.tap = tap;
        }

        @Override public void configure(Format inputFormat, int specifiedBufferSize, int[] outputChannels) throws AudioSink.ConfigurationException {
            try {
                boolean pcm = MimeTypes.AUDIO_RAW.equals(inputFormat.sampleMimeType);
                int enc = inputFormat.pcmEncoding;
                boolean pcm16 = enc == com.google.android.exoplayer2.C.ENCODING_PCM_16BIT || enc == Format.NO_VALUE;
                boolean pcmFloat = enc == com.google.android.exoplayer2.C.ENCODING_PCM_FLOAT;
                if (pcm && (pcm16 || pcmFloat) && inputFormat.sampleRate > 0 && inputFormat.channelCount > 0) {
                    tap.sound.configure(inputFormat.sampleRate, inputFormat.channelCount, pcmFloat);
                    tap.listening = true;
                } else {
                    tap.listening = false;
                }
            } catch (Throwable ignored) {
                tap.listening = false;
            }
            super.configure(inputFormat, specifiedBufferSize, outputChannels);
        }

        @Override public boolean handleBuffer(ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount)
                throws AudioSink.InitializationException, AudioSink.WriteException {
            // the same buffer comes again until the output has taken all of it: it is heard once
            if (tap.listening && presentationTimeUs != lastPts) {
                lastPts = presentationTimeUs;
                try {
                    long mediaUs = presentationTimeUs >= RENDERER_OFFSET_US ? presentationTimeUs - RENDERER_OFFSET_US : presentationTimeUs;
                    if (buffer != null && buffer.hasRemaining()) {
                        tap.sound.write(mediaUs, buffer);
                        tap.lastMediaUs = mediaUs;
                    }
                } catch (Throwable ignored) { }
            }
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount);
        }
    }
}
