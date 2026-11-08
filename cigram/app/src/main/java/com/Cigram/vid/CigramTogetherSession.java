package com.Cigram.vid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

/**
 * جلسة "شاهد مع صديقك" الحالية (واحدة لكل تطبيق). تملك اتصال WebSocket وآخر لقطة للغرفة
 * وفارق ساعة الخادم، وتنقل الأحداث للشاشات المسجّلة. الشاشات تأتي وتذهب، والجلسة تبقى.
 *
 * - الحالة المرجعية للتشغيل يقررها الخادم فقط؛ هذا الصنف لا يعدّلها محلياً.
 * - فارق الساعة يُقاس بطريقة NTP مبسّطة (أقل زمن ذهاب-وإياب)، فلا نعتمد على ساعة الهاتف وحدها.
 * - كل الأحداث على الخيط الرئيسي.
 */
public final class CigramTogetherSession {

    public interface Listener {
        /** لقطة/حالة جديدة للغرفة (cause: سبب التغيير مثل play/seek/episode.changed أو ""). */
        void onRoom(JSONObject room, String cause);
        void onChat(JSONObject message);
        /** أي حدث آخر: join.request, ctl.request, wait.request, reaction, moment, kicked, room.closed ... */
        void onEvent(String type, JSONObject event);
        void onConnection(boolean connected);
    }

    public interface Ack { void onAck(boolean ok, String error, String message, JSONObject raw); }

    private static final CigramTogetherSession I = new CigramTogetherSession();
    public static CigramTogetherSession get() { return I; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Listener> listeners = new ArrayList<Listener>();
    private final HashMap<String, Ack> acks = new HashMap<String, Ack>();
    private final HashMap<String, Runnable> ackTimeouts = new HashMap<String, Runnable>();

    private Context app;
    private CigramTogetherWs ws;
    private String roomId = "";
    private JSONObject room;
    private boolean connected;
    private long offsetMs;            // serverNow ≈ System.currentTimeMillis() + offsetMs
    private long bestRtt = Long.MAX_VALUE;
    private final ArrayList<JSONObject> chat = new ArrayList<JSONObject>();
    private final Runnable timeTick = new Runnable() {
        @Override public void run() {
            sendTimeProbe();
            main.postDelayed(this, 30000L);
        }
    };

    private CigramTogetherSession() { }

    // ------------------------------------------------------------------ listeners

    public void register(Listener l) { if (l != null && !listeners.contains(l)) listeners.add(l); }
    public void unregister(Listener l) { listeners.remove(l); }

    // ------------------------------------------------------------------ state

    public String roomId() { return roomId; }
    public JSONObject room() { return room; }
    public boolean isConnected() { return connected; }
    public ArrayList<JSONObject> chatLog() { return chat; }
    public boolean active() { return roomId.length() > 0 && room != null; }

    public String role() { return room == null ? "" : room.optString("role", ""); }
    public boolean isOwner() { return "owner".equals(role()); }
    public int epoch() { return room == null ? 0 : room.optInt("epoch", 0); }
    public JSONObject content() { return room == null ? null : room.optJSONObject("content"); }
    public boolean canControl() { return isOwner() || (room != null && room.optBoolean("shared_control", false)); }

    public JSONObject other() {
        if (room == null) return null;
        return isOwner() ? room.optJSONObject("guest") : room.optJSONObject("owner");
    }

    public long serverNow() { return System.currentTimeMillis() + offsetMs; }

    /** موضع التشغيل المرجعي الآن (ms) محسوباً من حالة الخادم وفارق الساعة. */
    public long referencePositionMs() {
        JSONObject pb = room == null ? null : room.optJSONObject("playback");
        if (pb == null) return 0L;
        long pos = pb.optLong("position_ms", 0L);
        if ("playing".equals(pb.optString("status"))) pos += Math.max(0L, serverNow() - pb.optLong("ref_ms", 0L));
        return Math.max(0L, pos);
    }

    /** ms المتبقية حتى لحظة البدء المتزامن (0 إذا بدأ أو لا يوجد بدء مجدول). */
    public long msUntilStart() {
        JSONObject pb = room == null ? null : room.optJSONObject("playback");
        if (pb == null) return 0L;
        long startAt = pb.optLong("start_at", 0L);
        return startAt <= 0L ? 0L : Math.max(0L, startAt - serverNow());
    }

    // ------------------------------------------------------------------ lifecycle

    public void adopt(Context c, JSONObject newRoom) {
        app = c.getApplicationContext();
        if (newRoom == null) return;
        String id = newRoom.optString("id", "");
        if (!id.equals(roomId)) {
            chat.clear();
            roomId = id;
        }
        room = newRoom;
    }

    public void connect(Context c) {
        app = c.getApplicationContext();
        if (roomId.length() == 0) return;
        if (ws != null) return;
        ws = new CigramTogetherWs(app, CigramTogetherApi.BASE, "/rooms/" + roomId + "/ws", new CigramTogetherWs.Listener() {
            @Override public void onOpen() {
                connected = true;
                bestRtt = Long.MAX_VALUE;
                sendTimeProbe();
                main.removeCallbacks(timeTick);
                main.postDelayed(timeTick, 30000L);
                for (Listener l : new ArrayList<Listener>(listeners)) l.onConnection(true);
            }
            @Override public void onMessage(JSONObject m) { handle(m); }
            @Override public void onClosed(boolean authFailed) {
                connected = false;
                main.removeCallbacks(timeTick);
                for (Listener l : new ArrayList<Listener>(listeners)) l.onConnection(false);
            }
        });
        ws.start();
    }

    /** خروج من الغرفة محلياً (لا يخبر الخادم؛ استدعِ leave()/close() قبله عند الحاجة). */
    public void reset() {
        main.removeCallbacks(timeTick);
        if (ws != null) { try { ws.stop(); } catch (Throwable ignored) { } ws = null; }
        connected = false;
        roomId = "";
        room = null;
        chat.clear();
        acks.clear();
        ackTimeouts.clear();
        bestRtt = Long.MAX_VALUE;
    }

    // ------------------------------------------------------------------ commands

    /** يرسل أمراً عبر WebSocket؛ الـ ack يصل من الخادم (أو مهلة 8 ثوانٍ). */
    public void cmd(JSONObject msg, final Ack cb) {
        try {
            final String id = UUID.randomUUID().toString().substring(0, 8);
            msg.put("id", id);
            if (ws == null || !ws.isOpen()) {
                if (cb != null) cb.onAck(false, "offline", "غير متصل بالغرفة حالياً.", null);
                return;
            }
            if (cb != null) {
                acks.put(id, cb);
                Runnable to = new Runnable() {
                    @Override public void run() {
                        Ack a = acks.remove(id);
                        ackTimeouts.remove(id);
                        if (a != null) a.onAck(false, "timeout", "لم يصل رد من الخادم.", null);
                    }
                };
                ackTimeouts.put(id, to);
                main.postDelayed(to, 8000L);
            }
            if (!ws.sendText(msg.toString()) && cb != null) {
                Ack a = acks.remove(id);
                Runnable to = ackTimeouts.remove(id);
                if (to != null) main.removeCallbacks(to);
                if (a != null) a.onAck(false, "offline", "غير متصل بالغرفة حالياً.", null);
            }
        } catch (Throwable t) {
            if (cb != null) cb.onAck(false, "error", "تعذر إرسال الأمر.", null);
        }
    }

    public void cmd(String type, Ack cb) {
        try { cmd(new JSONObject().put("t", type), cb); } catch (Throwable ignored) { }
    }

    public void sync() { cmd("sync", null); sendTimeProbe(); }

    private void sendTimeProbe() {
        try {
            if (ws != null && ws.isOpen()) ws.sendText(new JSONObject().put("t", "time")
                    .put("client_ms", SystemClock.elapsedRealtime()).toString());
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ incoming

    private void handle(JSONObject m) {
        String t = m.optString("t", "");
        if ("ack".equals(t)) {
            String id = m.optString("id", "");
            Ack a = acks.remove(id);
            Runnable to = ackTimeouts.remove(id);
            if (to != null) main.removeCallbacks(to);
            if (a != null) a.onAck(m.optBoolean("ok", false), m.optString("error", ""), m.optString("message", ""), m);
            return;
        }
        if ("time".equals(t)) {
            long rtt = SystemClock.elapsedRealtime() - m.optLong("client_ms", 0L);
            if (rtt >= 0L && rtt < bestRtt + 40L) {
                if (rtt < bestRtt) bestRtt = rtt;
                offsetMs = m.optLong("server_ms", 0L) + rtt / 2L - System.currentTimeMillis();
            }
            return;
        }
        if ("snapshot".equals(t) || "state".equals(t)) {
            JSONObject r = m.optJSONObject("room");
            if (r != null) {
                room = r;
                if ("snapshot".equals(t) && m.optLong("server_ms", 0L) > 0L && bestRtt == Long.MAX_VALUE) {
                    offsetMs = m.optLong("server_ms") - System.currentTimeMillis();
                }
                String cause = m.optString("cause", "");
                for (Listener l : new ArrayList<Listener>(listeners)) l.onRoom(r, cause);
            }
            return;
        }
        if ("chat".equals(t)) {
            JSONObject msg = m.optJSONObject("message");
            if (msg != null) {
                chat.add(msg);
                if (chat.size() > 300) chat.remove(0);
                for (Listener l : new ArrayList<Listener>(listeners)) l.onChat(msg);
            }
            return;
        }
        if ("ready".equals(t) && room != null && m.optJSONObject("ready") != null) {
            try { room.put("ready", m.optJSONObject("ready")); } catch (Throwable ignored) { }   // من جهز ومن لم يجهز بعد
        }
        if ("presence".equals(t) && room != null) {
            try { room.put("presence", m.optJSONObject("presence")); } catch (Throwable ignored) { }
        }
        if ("room.closed".equals(t) || "kicked".equals(t)) {
            if (ws != null) { try { ws.stop(); } catch (Throwable ignored) { } ws = null; }
        }
        for (Listener l : new ArrayList<Listener>(listeners)) l.onEvent(t, m);
    }

    // ------------------------------------------------------------------ helpers

    public static String clock(long ms) {
        long s = Math.max(0L, ms / 1000L);
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", s / 3600L, (s / 60L) % 60L, s % 60L);
    }

    public static JSONArray emptyArray() { return new JSONArray(); }
}
