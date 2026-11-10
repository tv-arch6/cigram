package com.Cigram.vid;

import android.content.Context;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;

/**
 * تسجيل ورسائل صوتية: MediaRecorder (AAC داخل MPEG-4 أحادي 24kbps) مع عيّنة موجة صوتية،
 * ومشغّل واحد مشترك (تشغيل رسالة يوقف السابقة). كل الأخطاء تُبتلع وتُحوَّل لحالة فشل لا تُسقط التطبيق.
 */
public final class AdsVoice {

    private AdsVoice() { }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------------ التسجيل

    public static final class Recorder {
        private MediaRecorder rec;
        private File file;
        private long startedAt;
        private final ArrayList<Integer> amps = new ArrayList<Integer>();
        private boolean running;
        private final Runnable sampler = new Runnable() {
            @Override public void run() {
                if (!running || rec == null) return;
                try {
                    amps.add(rec.getMaxAmplitude());
                } catch (Throwable ignored) { }
                if (tick != null) tick.onTick(elapsed(), lastAmp());
                MAIN.postDelayed(this, 100);
            }
        };
        private Tick tick;

        public interface Tick { void onTick(int elapsedMs, int amp); }

        public static final class Result {
            public File file;
            public int durMs;
            public int[] wave;
            /** "voice.m4a" (AAC) or "voice.3gp" (AMR, only on phones whose AAC encoder refuses to start). */
            public String name = "voice.m4a";
        }

        /** Why the last {@link #start} failed, in Arabic (shown to the user), "" when it did not fail. */
        public String lastError = "";
        private android.media.AudioManager focusManager;
        private String fileName = "voice.m4a";

        /**
         * 19.2: the recorder no longer depends on ONE encoder setting. Each profile is tried in turn until the phone
         * accepts one (some chipsets refuse 24 kHz AAC, some refuse the plain MIC source while another app holds it):
         * AAC 24 kHz → AAC 44.1 kHz → AAC 16 kHz → AAC 8 kHz, each on MIC then on VOICE_RECOGNITION, and as the last
         * resort AMR-NB in a 3GP file. Playback of a voice message is stopped first and audio focus is taken, so the
         * microphone is not fighting the speaker.
         */
        public boolean start(Context c, Tick t) {
            this.tick = t;
            lastError = "";
            try { AdsVoice.stop(); } catch (Throwable ignored) { }
            File dir = new File(c.getCacheDir(), "ads_up");
            if (!dir.exists()) dir.mkdirs();
            try {
                focusManager = (android.media.AudioManager) c.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
                if (focusManager != null) focusManager.requestAudioFocus(null, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            } catch (Throwable ignored) { }
            // {output format, encoder, sample rate, bit rate}
            int[][] profiles = {
                    {MediaRecorder.OutputFormat.MPEG_4, MediaRecorder.AudioEncoder.AAC, 24000, 32000},
                    {MediaRecorder.OutputFormat.MPEG_4, MediaRecorder.AudioEncoder.AAC, 44100, 64000},
                    {MediaRecorder.OutputFormat.MPEG_4, MediaRecorder.AudioEncoder.AAC, 16000, 24000},
                    {MediaRecorder.OutputFormat.MPEG_4, MediaRecorder.AudioEncoder.AAC, 8000, 16000},
                    {MediaRecorder.OutputFormat.THREE_GPP, MediaRecorder.AudioEncoder.AMR_NB, 8000, 12200},
            };
            int[] sources = {MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.DEFAULT};
            Throwable last = null;
            for (int[] p : profiles) {
                for (int src : sources) {
                    boolean amr = p[1] == MediaRecorder.AudioEncoder.AMR_NB;
                    try {
                        file = File.createTempFile("v_", amr ? ".3gp" : ".m4a", dir);
                        fileName = amr ? "voice.3gp" : "voice.m4a";
                        rec = android.os.Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(c.getApplicationContext()) : new MediaRecorder();
                        rec.setAudioSource(src);
                        rec.setOutputFormat(p[0]);
                        rec.setAudioEncoder(p[1]);
                        rec.setAudioChannels(1);
                        rec.setAudioSamplingRate(p[2]);
                        rec.setAudioEncodingBitRate(p[3]);
                        rec.setMaxDuration(180000);
                        rec.setOutputFile(file.getAbsolutePath());
                        rec.prepare();
                        rec.start();
                        startedAt = System.currentTimeMillis();
                        running = true;
                        amps.clear();
                        MAIN.postDelayed(sampler, 100);
                        return true;
                    } catch (Throwable e) {
                        last = e;
                        cleanup(true);
                    }
                }
            }
            releaseFocus();
            android.util.Log.w("CigramVoice", "no recorder profile worked", last);
            boolean busy = false;
            try {
                int mode = focusManager == null ? 0 : focusManager.getMode();
                busy = mode == android.media.AudioManager.MODE_IN_CALL || mode == android.media.AudioManager.MODE_IN_COMMUNICATION;
            } catch (Throwable ignored) { }
            lastError = busy ? "الميكروفون مشغول بمكالمة جارية. أنهِ المكالمة ثم سجّل رسالتك."
                    : "الميكروفون مستخدم من تطبيق آخر الآن. أغلق التطبيق الذي يسجّل الصوت ثم أعد المحاولة.";
            return false;
        }

        private void releaseFocus() {
            try { if (focusManager != null) focusManager.abandonAudioFocus(null); } catch (Throwable ignored) { }
        }

        public int elapsed() {
            return running ? (int) (System.currentTimeMillis() - startedAt) : 0;
        }

        private int lastAmp() {
            return amps.isEmpty() ? 0 : amps.get(amps.size() - 1);
        }

        /** يوقف التسجيل. إن كان cancel=true يُحذف الملف ويعيد null. */
        public Result stop(boolean cancel) {
            if (rec == null) return null;
            int dur = elapsed();
            running = false;
            MAIN.removeCallbacks(sampler);
            boolean okStop = true;
            releaseFocus();
            try {
                rec.stop();
            } catch (Throwable e) {
                okStop = false; // يحدث إن كان التسجيل قصيراً جداً
            }
            cleanup(false);
            if (cancel || !okStop || file == null || !file.exists() || file.length() < 200) {
                if (file != null) file.delete();
                file = null;
                return null;
            }
            Result r = new Result();
            r.file = file;
            r.name = fileName;
            r.durMs = dur;
            r.wave = waveform(amps, 48);
            file = null;
            return r;
        }

        private void cleanup(boolean deleteFile) {
            running = false;
            MAIN.removeCallbacks(sampler);
            if (rec != null) {
                try { rec.release(); } catch (Throwable ignored) { }
                rec = null;
            }
            if (deleteFile && file != null) {
                file.delete();
                file = null;
            }
        }

        public boolean isRunning() { return running; }
    }

    /** يختصر عيّنات السعة إلى n عمود (0..255) متناسبة مع أعلى قيمة. */
    public static int[] waveform(ArrayList<Integer> amps, int n) {
        int[] out = new int[n];
        if (amps.isEmpty()) {
            for (int i = 0; i < n; i++) out[i] = 20;
            return out;
        }
        int max = 1;
        for (int a : amps) if (a > max) max = a;
        for (int i = 0; i < n; i++) {
            int from = (int) ((long) i * amps.size() / n);
            int to = Math.max(from + 1, (int) ((long) (i + 1) * amps.size() / n));
            int peak = 0;
            for (int k = from; k < to && k < amps.size(); k++) peak = Math.max(peak, amps.get(k));
            out[i] = Math.max(12, Math.min(255, (int) (255.0 * peak / max)));
        }
        return out;
    }

    // ------------------------------------------------------------------ المشغّل المشترك

    public interface PlayListener {
        void onState(String id, boolean playing, int posMs, int durMs);
    }

    private static MediaPlayer player;
    private static String playingId;
    private static PlayListener listener;
    private static boolean prepared;
    private static final Runnable PROGRESS = new Runnable() {
        @Override public void run() {
            if (player == null || playingId == null) return;
            try {
                if (prepared && listener != null) listener.onState(playingId, player.isPlaying(), player.getCurrentPosition(), player.getDuration());
            } catch (Throwable ignored) { }
            MAIN.postDelayed(this, 120);
        }
    };

    public static String currentId() { return playingId; }

    public static void setListener(PlayListener l) { listener = l; }

    /** يشغّل/يوقف مؤقتاً الرسالة id من الرابط url. */
    public static void toggle(final String id, String url) {
        try {
            if (player != null && id.equals(playingId)) {
                if (!prepared) return;
                if (player.isPlaying()) {
                    player.pause();
                } else {
                    player.start();
                    MAIN.removeCallbacks(PROGRESS);
                    MAIN.post(PROGRESS);
                }
                if (listener != null) listener.onState(id, player.isPlaying(), player.getCurrentPosition(), player.getDuration());
                return;
            }
            stop();
            player = new MediaPlayer();
            playingId = id;
            prepared = false;
            player.setDataSource(url);
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer mp) {
                    prepared = true;
                    try { mp.start(); } catch (Throwable ignored) { }
                    MAIN.removeCallbacks(PROGRESS);
                    MAIN.post(PROGRESS);
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) {
                    String done = playingId;
                    stop();
                    if (listener != null && done != null) listener.onState(done, false, 0, 0);
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                    String bad = playingId;
                    stop();
                    if (listener != null && bad != null) listener.onState(bad, false, 0, -1);
                    return true;
                }
            });
            player.prepareAsync();
            if (listener != null) listener.onState(id, true, 0, 0);
        } catch (Throwable t) {
            String bad = playingId;
            stop();
            if (listener != null && bad != null) listener.onState(bad, false, 0, -1);
        }
    }

    public static void stop() {
        MAIN.removeCallbacks(PROGRESS);
        MediaPlayer p = player;
        String id = playingId;
        player = null;
        playingId = null;
        prepared = false;
        if (p != null) {
            try { p.stop(); } catch (Throwable ignored) { }
            try { p.release(); } catch (Throwable ignored) { }
        }
        if (listener != null && id != null) listener.onState(id, false, 0, 0);
    }
}
