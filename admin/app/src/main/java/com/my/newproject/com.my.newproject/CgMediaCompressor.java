package com.my.newproject;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Real VIDEO compression (not ZIP): decode -> (GPU copy) -> encode with H.265 / H.264 -> MP4.
 * - The resolution is never changed; only the codec / bitrate.
 * - The original file is only read, never touched. The result goes to a temporary file.
 * - Audio: AAC is copied untouched; any other audio is first converted to AAC (stereo at most).
 * - Runs on the caller's background thread, uses MediaExtractor / MediaCodec / MediaMuxer only.
 */
final class CgMediaCompressor {

    static final String TAG = CgMediaAnalyzer.TAG;

    static final class Cancelled extends Exception {
        Cancelled() {
            super("cancelled");
        }
    }

    static final class Failed extends Exception {
        Failed(String message) {
            super(message);
        }

        Failed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    interface Progress {
        void onProgress(float fraction, long outBytes);
    }

    static final class EncoderChoice {
        String name = "";
        boolean hardware;
        int maxBitrate;
        boolean vbr;
        int profile;
    }

    // ---------------------------------------------------------------- capability checks

    static boolean isSoftwareName(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("c2.google.") || n.contains(".sw.");
    }

    /** Best encoder for mime/size/fps (hardware first). Software is only used when allowSoftware is true. */
    static EncoderChoice findEncoder(String mime, int w, int h, double fps, boolean allowSoftware) {
        try {
            MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            EncoderChoice best = null;
            for (int i = 0; i < infos.length; i++) {
                MediaCodecInfo ci = infos[i];
                if (!ci.isEncoder()) continue;
                boolean typeOk = false;
                String[] types = ci.getSupportedTypes();
                for (int t = 0; t < types.length; t++) {
                    if (types[t].equalsIgnoreCase(mime)) typeOk = true;
                }
                if (!typeOk) continue;
                boolean software = isSoftwareName(ci.getName());
                if (software && !allowSoftware) continue;
                MediaCodecInfo.CodecCapabilities caps;
                try {
                    caps = ci.getCapabilitiesForType(mime);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                boolean surface = false;
                for (int k = 0; k < caps.colorFormats.length; k++) {
                    if (caps.colorFormats[k] == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surface = true;
                }
                if (!surface) continue;
                MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
                if (vc == null || !vc.isSizeSupported(w, h)) continue;
                double rate = fps > 0 ? fps : 30;
                if (!vc.areSizeAndRateSupported(w, h, rate)) continue;
                EncoderChoice c = new EncoderChoice();
                c.name = ci.getName();
                c.hardware = !software;
                c.maxBitrate = vc.getBitrateRange().getUpper().intValue();
                MediaCodecInfo.EncoderCapabilities ec = caps.getEncoderCapabilities();
                c.vbr = ec != null && ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
                c.profile = pickProfile(mime, caps);
                if (best == null || (c.hardware && !best.hardware)) best = c;
            }
            return best;
        } catch (Throwable e) {
            Log.w(TAG, "findEncoder failed: " + e.getMessage());
            return null;
        }
    }

    private static int pickProfile(String mime, MediaCodecInfo.CodecCapabilities caps) {
        int wanted = "video/hevc".equals(mime)
                ? MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                : MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;
        for (int i = 0; i < caps.profileLevels.length; i++) {
            if (caps.profileLevels[i].profile == wanted) return wanted;
        }
        return 0;
    }

    /** True when the audio can go into the MP4: AAC is copied, anything else needs a decoder on this phone. */
    static boolean audioSupported(CgMediaInfo info) {
        if (!info.hasAudio || info.audioIsAac()) return true;
        if (info.audioChannels > 2 && info.audioChannels != 6 && info.audioChannels != 8) return false;
        if (info.audioSampleRate < 8000 || info.audioSampleRate > 96000) return false;
        try {
            MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            for (int i = 0; i < infos.length; i++) {
                if (infos[i].isEncoder()) continue;
                String[] types = infos[i].getSupportedTypes();
                for (int t = 0; t < types.length; t++) {
                    if (types[t].equalsIgnoreCase(info.audioMime)) return true;
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "audioSupported failed: " + e.getMessage());
        }
        return false;
    }

    // ---------------------------------------------------------------------- the job

    private final Context ctx;
    private final Uri src;
    private final CgMediaInfo info;
    private final File out;
    private final String videoMime;
    private final long videoBitrate;
    private final Progress progress;
    private volatile boolean cancel;

    private MediaCodec enc;
    private MediaMuxer muxer;
    private MediaExtractor aEx;
    private boolean muxerStarted;
    private int vMuxTrack = -1;
    private int aMuxTrack = -1;
    private boolean aDone;
    private boolean encOutEos;
    private long videoSamples;
    private long lastEncPts;
    private ByteBuffer aBuf;
    private final MediaCodec.BufferInfo eInfo = new MediaCodec.BufferInfo();
    private final MediaCodec.BufferInfo aInfo = new MediaCodec.BufferInfo();

    private float base;
    private float span = 1f;

    CgMediaCompressor(Context ctx, Uri src, CgMediaInfo info, File out, String videoMime, long videoBitrate, Progress progress) {
        this.ctx = ctx.getApplicationContext();
        this.src = src;
        this.info = info;
        this.out = out;
        this.videoMime = videoMime;
        this.videoBitrate = videoBitrate;
        this.progress = progress;
    }

    void cancel() {
        cancel = true;
    }

    private void report(float local, long bytes) {
        if (progress != null) progress.onProgress(Math.max(0f, Math.min(1f, base + span * Math.max(0f, Math.min(1f, local)))), bytes);
    }

    private void check() throws Cancelled {
        if (cancel) throw new Cancelled();
    }

    /** Blocks until the compressed file is complete (or throws). The partial file is deleted on failure. */
    void run() throws Failed, Cancelled {
        File audioTmp = null;
        boolean ok = false;
        try {
            File dir = out.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) throw new Failed("تعذر إنشاء مجلد الملفات المؤقتة.");
            if (out.exists() && !out.delete()) throw new Failed("تعذر حذف ملف مؤقت قديم.");
            Uri audioSource = null;
            if (info.hasAudio && !info.audioIsAac()) {
                audioTmp = new File(dir, out.getName() + ".audio.m4a");
                base = 0f;
                span = 0.04f;
                new AudioPass(ctx, src, audioTmp, info.durationMs * 1000L, this).run();
                audioSource = Uri.fromFile(audioTmp);
                base = 0.04f;
                span = 0.96f;
            } else if (info.hasAudio) {
                audioSource = src;
            }
            videoPass(audioSource);
            verifyOutput();
            ok = true;
        } catch (Cancelled c) {
            throw c;
        } catch (Failed f) {
            throw f;
        } catch (Throwable t) {
            Log.e(TAG, "compress failed", t);
            throw new Failed("فشل ضغط الفيديو على هذا الهاتف.", t);
        } finally {
            if (audioTmp != null) {
                //noinspection ResultOfMethodCallIgnored
                audioTmp.delete();
            }
            if (!ok && out.exists()) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        }
    }

    private void videoPass(Uri audioSource) throws Failed, Cancelled {
        MediaExtractor vEx = null;
        MediaCodec dec = null;
        CgGl.InputSurface inSurf = null;
        CgGl.OutputSurface outSurf = null;
        boolean finished = false;
        try {
            vEx = new MediaExtractor();
            vEx.setDataSource(ctx, src, null);
            int vTrack = findTrack(vEx, "video/");
            if (vTrack < 0) throw new Failed("لا يوجد مسار فيديو في الملف.");
            vEx.selectTrack(vTrack);
            MediaFormat vFmt = vEx.getTrackFormat(vTrack);
            String vMime = vFmt.getString(MediaFormat.KEY_MIME);
            int w = vFmt.getInteger(MediaFormat.KEY_WIDTH) & ~1;
            int h = vFmt.getInteger(MediaFormat.KEY_HEIGHT) & ~1;
            double fps = info.fps > 0 ? info.fps : 30;

            EncoderChoice choice = findEncoder(videoMime, w, h, fps, !"video/hevc".equals(videoMime));
            if (choice == null) {
                throw new Failed("هذا الهاتف لا يدعم ترميز " + CgMediaAnalyzer.videoLabel(videoMime) + " بدقة " + w + "x" + h + ".");
            }

            MediaFormat eFmt = MediaFormat.createVideoFormat(videoMime, w, h);
            eFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            long br = Math.max(300000L, Math.min(videoBitrate, (long) choice.maxBitrate));
            eFmt.setInteger(MediaFormat.KEY_BIT_RATE, (int) br);
            eFmt.setInteger(MediaFormat.KEY_FRAME_RATE, (int) Math.ceil(fps));
            eFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            if (choice.vbr) eFmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            if (choice.profile > 0) eFmt.setInteger(MediaFormat.KEY_PROFILE, choice.profile);

            enc = MediaCodec.createByCodecName(choice.name);
            enc.configure(eFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inSurf = new CgGl.InputSurface(enc.createInputSurface());
            enc.start();
            inSurf.makeCurrent();
            outSurf = new CgGl.OutputSurface();
            dec = MediaCodec.createDecoderByType(vMime);
            dec.configure(vFmt, outSurf.surface(), null, 0);
            dec.start();

            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int rot = ((info.rotation % 360) + 360) % 360;
            if (rot % 90 == 0 && rot != 0) muxer.setOrientationHint(rot);

            if (audioSource != null) {
                aEx = new MediaExtractor();
                aEx.setDataSource(ctx, audioSource, null);
                int aTrack = findTrack(aEx, "audio/");
                if (aTrack >= 0) {
                    aEx.selectTrack(aTrack);
                    aMuxTrack = muxer.addTrack(aEx.getTrackFormat(aTrack));
                    aBuf = ByteBuffer.allocate(1 << 20);
                } else {
                    aEx.release();
                    aEx = null;
                }
            }
            aDone = aEx == null;

            final MediaCodec.BufferInfo dInfo = new MediaCodec.BufferInfo();
            boolean decInEos = false;
            boolean decOutEos = false;
            long lastActivity = System.currentTimeMillis();
            long lastReport = 0;
            long durUs = Math.max(1L, info.durationMs * 1000L);

            while (!encOutEos) {
                check();
                boolean activity = false;

                if (!decInEos) {
                    for (int n = 0; n < 4; n++) {
                        int idx = dec.dequeueInputBuffer(0);
                        if (idx < 0) break;
                        ByteBuffer ib = dec.getInputBuffer(idx);
                        int size = ib == null ? -1 : vEx.readSampleData(ib, 0);
                        activity = true;
                        if (size < 0) {
                            dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            decInEos = true;
                            break;
                        }
                        dec.queueInputBuffer(idx, 0, size, vEx.getSampleTime(), 0);
                        vEx.advance();
                    }
                }

                if (!decOutEos) {
                    for (int n = 0; n < 4; n++) {
                        int di = dec.dequeueOutputBuffer(dInfo, n == 0 ? 2000 : 0);
                        if (di == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                        if (di < 0) {
                            activity = true;
                            continue;
                        }
                        boolean render = dInfo.size != 0;
                        boolean eos = (dInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        dec.releaseOutputBuffer(di, render);
                        activity = true;
                        if (render) {
                            outSurf.awaitNewImage();
                            outSurf.drawImage();
                            inSurf.setPresentationTime(dInfo.presentationTimeUs * 1000L);
                            inSurf.swapBuffers();
                            long now = System.currentTimeMillis();
                            if (now - lastReport > 500) {
                                lastReport = now;
                                report((float) ((double) dInfo.presentationTimeUs / (double) durUs), out.length());
                            }
                        }
                        if (eos) {
                            decOutEos = true;
                            enc.signalEndOfInputStream();
                            break;
                        }
                        if (drainEncoder(0)) activity = true;
                    }
                }

                if (drainEncoder(decOutEos ? 10000 : 0)) activity = true;

                long now = System.currentTimeMillis();
                if (activity) {
                    lastActivity = now;
                } else {
                    if (now - lastActivity > 120000L) throw new Failed("توقف ترميز الفيديو عن الاستجابة. أعد المحاولة.");
                    try {
                        Thread.sleep(4);
                    } catch (InterruptedException e) {
                        throw new Cancelled();
                    }
                }
            }
            if (videoSamples == 0 || !muxerStarted) throw new Failed("لم ينتج الترميز أي إطارات.");
            pumpAudio(Long.MAX_VALUE);
            muxer.stop();
            muxerStarted = false;
            finished = true;
            report(1f, out.length());
        } catch (Cancelled c) {
            throw c;
        } catch (Failed f) {
            throw f;
        } catch (Exception e) {
            Log.e(TAG, "video pass failed", e);
            throw new Failed("فشل ضغط الفيديو على هذا الهاتف (" + e.getClass().getSimpleName() + ").", e);
        } finally {
            safeStop(dec);
            safeStop(enc);
            if (outSurf != null) outSurf.release();
            if (inSurf != null) inSurf.release();
            if (vEx != null) {
                try {
                    vEx.release();
                } catch (Exception ignored) { }
            }
            if (aEx != null) {
                try {
                    aEx.release();
                } catch (Exception ignored) { }
                aEx = null;
            }
            if (muxer != null) {
                try {
                    if (muxerStarted && !finished) muxer.stop();
                } catch (Exception ignored) { }
                try {
                    muxer.release();
                } catch (Exception ignored) { }
                muxer = null;
            }
            enc = null;
        }
    }

    /** Moves every available encoded frame into the muxer. Returns true when something happened. */
    private boolean drainEncoder(int timeoutUs) throws Failed {
        boolean any = false;
        int wait = timeoutUs;
        while (true) {
            int ei = enc.dequeueOutputBuffer(eInfo, wait);
            if (ei == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (ei == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (vMuxTrack >= 0) throw new Failed("تغيّر تنسيق الترميز أثناء العمل.");
                vMuxTrack = muxer.addTrack(enc.getOutputFormat());
                muxer.start();
                muxerStarted = true;
                any = true;
                wait = 0;
                continue;
            }
            if (ei < 0) continue;
            ByteBuffer data = enc.getOutputBuffer(ei);
            if (data == null) throw new Failed("خرج المرمّز فارغاً.");
            if ((eInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) eInfo.size = 0;
            if (eInfo.size != 0) {
                if (!muxerStarted) throw new Failed("وصلت إطارات قبل جاهزية الملف.");
                data.position(eInfo.offset);
                data.limit(eInfo.offset + eInfo.size);
                muxer.writeSampleData(vMuxTrack, data, eInfo);
                videoSamples++;
                lastEncPts = eInfo.presentationTimeUs;
                pumpAudio(lastEncPts + 600000L);
            }
            boolean eos = (eInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            enc.releaseOutputBuffer(ei, false);
            any = true;
            wait = 0;
            if (eos) {
                encOutEos = true;
                break;
            }
        }
        return any;
    }

    /** Copies audio samples (already AAC) up to limitUs so audio and video stay interleaved. */
    private void pumpAudio(long limitUs) {
        if (aEx == null || aDone || !muxerStarted) return;
        while (!aDone) {
            long t = aEx.getSampleTime();
            if (t < 0) {
                aDone = true;
                break;
            }
            if (t > limitUs) break;
            aBuf.clear();
            int size = aEx.readSampleData(aBuf, 0);
            if (size < 0) {
                aDone = true;
                break;
            }
            aBuf.position(0);
            aBuf.limit(size);
            aInfo.set(0, size, t, (aEx.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            muxer.writeSampleData(aMuxTrack, aBuf, aInfo);
            aEx.advance();
        }
    }

    private void verifyOutput() throws Failed {
        MediaExtractor v = new MediaExtractor();
        try {
            v.setDataSource(out.getAbsolutePath());
            boolean video = false;
            long dur = 0;
            for (int t = 0; t < v.getTrackCount(); t++) {
                MediaFormat f = v.getTrackFormat(t);
                String mime = CgMediaAnalyzer.str(f, CgMediaAnalyzer.K_MIME);
                if (mime.startsWith("video/")) video = true;
                dur = Math.max(dur, CgMediaAnalyzer.lng(f, CgMediaAnalyzer.K_DURATION));
            }
            long expected = info.durationMs * 1000L;
            if (!video || out.length() < 1024 || (expected > 0 && dur > 0 && dur < expected * 9L / 10L)) {
                throw new Failed("نتيجة الضغط غير صالحة (المدة أو المسارات ناقصة). سيُرفع الملف الأصلي بدلاً منها إن أردت.");
            }
        } catch (Failed f) {
            throw f;
        } catch (Exception e) {
            throw new Failed("تعذر التحقق من الفيديو المضغوط.", e);
        } finally {
            try {
                v.release();
            } catch (Exception ignored) { }
        }
    }

    private static void safeStop(MediaCodec c) {
        if (c == null) return;
        try {
            c.stop();
        } catch (Exception ignored) { }
        try {
            c.release();
        } catch (Exception ignored) { }
    }

    static int findTrack(MediaExtractor ex, String prefix) {
        for (int t = 0; t < ex.getTrackCount(); t++) {
            String mime = CgMediaAnalyzer.str(ex.getTrackFormat(t), CgMediaAnalyzer.K_MIME);
            if (mime.startsWith(prefix)) return t;
        }
        return -1;
    }

    // =================================================================================== audio

    /** Non-AAC audio -> AAC in a small temporary .m4a (stereo at most). */
    private static final class AudioPass {
        private final Context ctx;
        private final Uri src;
        private final File out;
        private final long durUs;
        private final CgMediaCompressor owner;

        private MediaCodec enc;
        private MediaMuxer mux;
        private boolean muxStarted;
        private int muxTrack = -1;
        private boolean encOutEos;
        private int channels;
        private int outChannels;
        private int rate;
        private byte[] pcm = new byte[0];
        private int pcmLen;
        private int pcmOff;
        private long nextPts = -1;
        private final MediaCodec.BufferInfo eInfo = new MediaCodec.BufferInfo();

        AudioPass(Context ctx, Uri src, File out, long durUs, CgMediaCompressor owner) {
            this.ctx = ctx;
            this.src = src;
            this.out = out;
            this.durUs = durUs;
            this.owner = owner;
        }

        void run() throws Failed, Cancelled {
            MediaExtractor ex = null;
            MediaCodec dec = null;
            boolean done = false;
            try {
                ex = new MediaExtractor();
                ex.setDataSource(ctx, src, null);
                int track = findTrack(ex, "audio/");
                if (track < 0) throw new Failed("لا يوجد مسار صوت.");
                ex.selectTrack(track);
                MediaFormat f = ex.getTrackFormat(track);
                String mime = f.getString(MediaFormat.KEY_MIME);
                dec = MediaCodec.createDecoderByType(mime);
                dec.configure(f, null, null, 0);
                dec.start();
                mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                MediaCodec.BufferInfo dInfo = new MediaCodec.BufferInfo();
                boolean decInEos = false;
                boolean decOutEos = false;
                boolean encInEos = false;
                long lastActivity = System.currentTimeMillis();
                long lastReport = 0;

                while (!encOutEos) {
                    owner.check();
                    boolean activity = false;

                    if (!decInEos) {
                        int idx = dec.dequeueInputBuffer(0);
                        if (idx >= 0) {
                            ByteBuffer ib = dec.getInputBuffer(idx);
                            int size = ib == null ? -1 : ex.readSampleData(ib, 0);
                            if (size < 0) {
                                dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                decInEos = true;
                            } else {
                                dec.queueInputBuffer(idx, 0, size, ex.getSampleTime(), 0);
                                ex.advance();
                            }
                            activity = true;
                        }
                    }

                    if (pcmOff >= pcmLen && !decOutEos) {
                        int di = dec.dequeueOutputBuffer(dInfo, 2000);
                        if (di == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            setupEncoder(dec.getOutputFormat());
                            activity = true;
                        } else if (di >= 0) {
                            ByteBuffer ob = dec.getOutputBuffer(di);
                            if (dInfo.size > 0 && ob != null) {
                                if (enc == null) setupEncoder(dec.getOutputFormat());
                                if (nextPts < 0) nextPts = dInfo.presentationTimeUs;
                                convert(ob, dInfo.offset, dInfo.size);
                            }
                            if ((dInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decOutEos = true;
                            dec.releaseOutputBuffer(di, false);
                            activity = true;
                        }
                    }

                    if (enc != null && !encInEos) {
                        if (pcmOff < pcmLen) {
                            int ei = enc.dequeueInputBuffer(0);
                            if (ei >= 0) {
                                ByteBuffer ib = enc.getInputBuffer(ei);
                                ib.clear();
                                int frameBytes = 2 * outChannels;
                                int n = Math.min(ib.remaining(), pcmLen - pcmOff);
                                n -= n % frameBytes;
                                ib.put(pcm, pcmOff, n);
                                enc.queueInputBuffer(ei, 0, n, nextPts, 0);
                                pcmOff += n;
                                nextPts += (long) (n / frameBytes) * 1000000L / rate;
                                activity = true;
                            }
                        } else if (decOutEos) {
                            int ei = enc.dequeueInputBuffer(0);
                            if (ei >= 0) {
                                enc.queueInputBuffer(ei, 0, 0, Math.max(0, nextPts), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                encInEos = true;
                                activity = true;
                            }
                        }
                    }
                    if (decOutEos && enc == null) throw new Failed("تعذر فك ترميز الصوت (" + CgMediaAnalyzer.audioLabel(mime) + ") على هذا الهاتف.");

                    if (enc != null && drain()) activity = true;

                    long now = System.currentTimeMillis();
                    if (now - lastReport > 700 && durUs > 0 && nextPts > 0) {
                        lastReport = now;
                        owner.report((float) ((double) nextPts / (double) durUs), 0);
                    }
                    if (activity) {
                        lastActivity = now;
                    } else {
                        if (now - lastActivity > 60000L) throw new Failed("توقف تحويل الصوت عن الاستجابة.");
                        try {
                            Thread.sleep(3);
                        } catch (InterruptedException e) {
                            throw new Cancelled();
                        }
                    }
                }
                if (!muxStarted) throw new Failed("لم ينتج تحويل الصوت أي بيانات.");
                mux.stop();
                muxStarted = false;
                done = true;
                owner.report(1f, 0);
            } catch (Cancelled c) {
                throw c;
            } catch (Failed f) {
                throw f;
            } catch (Exception e) {
                Log.e(TAG, "audio pass failed", e);
                throw new Failed("فشل تحويل الصوت إلى AAC على هذا الهاتف.", e);
            } finally {
                safeStop(dec);
                safeStop(enc);
                if (ex != null) {
                    try {
                        ex.release();
                    } catch (Exception ignored) { }
                }
                if (mux != null) {
                    try {
                        if (muxStarted && !done) mux.stop();
                    } catch (Exception ignored) { }
                    try {
                        mux.release();
                    } catch (Exception ignored) { }
                }
            }
        }

        private void setupEncoder(MediaFormat of) throws Failed {
            if (enc != null) return;
            channels = (int) CgMediaAnalyzer.lng(of, CgMediaAnalyzer.K_CHANNELS);
            rate = (int) CgMediaAnalyzer.lng(of, CgMediaAnalyzer.K_SAMPLE_RATE);
            if (of.containsKey("pcm-encoding") && CgMediaAnalyzer.lng(of, "pcm-encoding") != 2) {
                throw new Failed("ترميز الصوت الناتج عن فك الترميز غير مدعوم.");
            }
            if (channels < 1 || rate < 8000 || rate > 96000) throw new Failed("خصائص الصوت غير مدعومة للتحويل.");
            if (channels > 2 && channels != 6 && channels != 8) throw new Failed("عدد قنوات الصوت (" + channels + ") غير مدعوم للتحويل.");
            outChannels = channels > 2 ? 2 : channels;
            try {
                MediaFormat ef = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, outChannels);
                ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                ef.setInteger(MediaFormat.KEY_BIT_RATE, outChannels == 1 ? 96000 : 192000);
                enc = MediaCodec.createEncoderByType("audio/mp4a-latm");
                enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                enc.start();
            } catch (Exception e) {
                throw new Failed("تعذر تشغيل مرمّز AAC على هذا الهاتف.", e);
            }
        }

        /** 16-bit PCM from the decoder -> interleaved 16-bit with at most two channels. */
        private void convert(ByteBuffer ob, int offset, int size) {
            ob.position(offset);
            ob.limit(offset + size);
            ShortBuffer sb = ob.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            int frames = sb.remaining() / channels;
            int needed = frames * outChannels * 2;
            if (pcm.length < needed) pcm = new byte[Math.max(needed, pcm.length * 2)];
            pcmLen = needed;
            pcmOff = 0;
            int o = 0;
            if (channels == outChannels) {
                for (int i = 0; i < frames * channels; i++) {
                    short s = sb.get();
                    pcm[o++] = (byte) (s & 0xFF);
                    pcm[o++] = (byte) ((s >> 8) & 0xFF);
                }
                return;
            }
            // 5.1 / 7.1 in WAVE order: FL FR FC LFE BL BR (SL SR). Down-mixed to stereo (LFE dropped).
            for (int i = 0; i < frames; i++) {
                float fl = sb.get();
                float fr = sb.get();
                float fc = sb.get();
                sb.get();
                float bl = sb.get();
                float br = sb.get();
                float sl = 0;
                float sr = 0;
                if (channels == 8) {
                    sl = sb.get();
                    sr = sb.get();
                }
                float l = (fl + 0.707f * fc + 0.707f * bl + 0.5f * sl) * 0.6f;
                float r = (fr + 0.707f * fc + 0.707f * br + 0.5f * sr) * 0.6f;
                short ls = clamp(l);
                short rs = clamp(r);
                pcm[o++] = (byte) (ls & 0xFF);
                pcm[o++] = (byte) ((ls >> 8) & 0xFF);
                pcm[o++] = (byte) (rs & 0xFF);
                pcm[o++] = (byte) ((rs >> 8) & 0xFF);
            }
        }

        private static short clamp(float v) {
            if (v > 32767f) return 32767;
            if (v < -32768f) return -32768;
            return (short) v;
        }

        private boolean drain() throws Failed {
            boolean any = false;
            while (true) {
                int ei = enc.dequeueOutputBuffer(eInfo, 0);
                if (ei == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                if (ei == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxTrack >= 0) throw new Failed("تغيّر تنسيق الصوت أثناء العمل.");
                    muxTrack = mux.addTrack(enc.getOutputFormat());
                    mux.start();
                    muxStarted = true;
                    any = true;
                    continue;
                }
                if (ei < 0) continue;
                ByteBuffer data = enc.getOutputBuffer(ei);
                if (data == null) throw new Failed("خرج مرمّز الصوت فارغاً.");
                if ((eInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) eInfo.size = 0;
                if (eInfo.size != 0 && muxStarted) {
                    data.position(eInfo.offset);
                    data.limit(eInfo.offset + eInfo.size);
                    mux.writeSampleData(muxTrack, data, eInfo);
                }
                boolean eos = (eInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                enc.releaseOutputBuffer(ei, false);
                any = true;
                if (eos) {
                    encOutEos = true;
                    break;
                }
            }
            return any;
        }
    }
}
