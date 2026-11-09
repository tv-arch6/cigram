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
        }

        public boolean start(Context c, Tick t) {
            try {
                this.tick = t;
                File dir = new File(c.getCacheDir(), "ads_up");
                if (!dir.exists()) dir.mkdirs();
                file = File.createTempFile("v_", ".m4a", dir);
                rec = new MediaRecorder();
                rec.setAudioSource(MediaRecorder.AudioSource.MIC);
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                rec.setAudioChannels(1);
                rec.setAudioSamplingRate(24000);
                rec.setAudioEncodingBitRate(32000);
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
                cleanup(true);
                return false;
            }
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
