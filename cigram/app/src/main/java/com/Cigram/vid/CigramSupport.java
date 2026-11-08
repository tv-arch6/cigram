package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Cigram Private Support (الدعم الخاص) — the private conversation between ONE viewer and the Cigram developer.
 *
 * It is the existing chat (cigram-ads-api ChatHub: one thread per signed-in account, WebSocket + polling, encrypted
 * storage, media uploads) with a new face; no second chat system. This class holds what the screens share: the
 * developer's PUBLIC identity (display name, picture, online / last active — never an internal id), the black theme,
 * the 24-hour clock and the entry point that replaced the old «أعلن هنا» page.
 */
public final class CigramSupport {

    private CigramSupport() {}

    // ------------------------------------------------------------------ look (premium black)

    static final int BG = 0xFF000000;
    static final int BAR = 0xFF050505;
    static final int HAIRLINE = 0x1FFFFFFF;
    static final int MINE = 0xFF262629;          // the viewer's bubbles (right)
    static final int THEIRS = 0xFF111113;        // the developer's bubbles (left)
    static final int THEIRS_STROKE = 0x26FFFFFF;
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0x99FFFFFF;
    static final int FAINT = 0x66FFFFFF;
    static final int ONLINE = 0xFF3DDC97;
    static final int ERROR = 0xFFFFB4AB;

    static final String TITLE = "الدعم الخاص";
    static final String SUBTITLE_EN = "Cigram Private Support";

    // ------------------------------------------------------------------ entry

    /** «أريد إعلاناً» (and every old link to the advertise page) opens the private conversation. */
    public static void open(Activity a) {
        if (a == null) return;
        try {
            a.startActivity(new Intent(a, AdsChatActivity.class));
        } catch (Throwable e) {
            android.util.Log.w("CigramSupport", "open failed", e);
        }
    }

    // ------------------------------------------------------------------ developer identity (public part only)

    private static final String PREFS = "cigram_support_v1";

    static final class Developer {
        String name = "مطوّر Cigram";
        String avatar = "";
        boolean online;
        long lastActive;
    }

    static Developer developer(Context c) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Developer d = new Developer();
        String n = p.getString("dev_name", "");
        if (n.length() > 0) d.name = n;
        d.avatar = p.getString("dev_avatar", "");
        d.lastActive = p.getLong("dev_last_active", 0);
        return d;
    }

    /**
     * Learns the developer's public name / picture from a server object ({@code developer} of /chat/presence or
     * {@code dev} of a message). Ids or e-mails in it are ignored on purpose.
     */
    static void learn(Context c, JSONObject dev) {
        if (dev == null) return;
        SharedPreferences.Editor e = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        String name = clean(dev.optString("name", dev.optString("display_name", "")));
        String avatar = dev.optString("avatar", dev.optString("avatar_url", ""));
        if (name.length() > 0) e.putString("dev_name", name);
        if (avatar.startsWith("https://")) e.putString("dev_avatar", avatar);
        long la = dev.optLong("last_active", 0);
        if (la > 0) e.putLong("dev_last_active", la);
        e.apply();
    }

    private static String clean(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\u0000-\\u001F\\u202A-\\u202E\\u2066-\\u2069]", "").trim();
        return t.length() > 40 ? t.substring(0, 40) : t;
    }

    // ------------------------------------------------------------------ time (24-hour clock, local time zone)

    /** "19:45" in the device's time zone; the server stores UTC epoch milliseconds. */
    static String time(long ts) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ts));
    }

    /** "اليوم 19:45" / "أمس 08:05" / "3 نوفمبر 19:45". */
    static String when(long ts) {
        Calendar now = Calendar.getInstance();
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(ts);
        String hm = time(ts);
        if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)) return "اليوم " + hm;
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)) return "أمس " + hm;
        return arabicDayMonth(t) + " " + hm;
    }

    private static final String[] MONTHS = {"يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"};

    static String arabicDayMonth(Calendar t) {
        return t.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[t.get(Calendar.MONTH)];
    }

    /** "آخر ظهور اليوم 19:45" or "" when unknown. */
    static String lastSeen(long ts) {
        return ts <= 0 ? "" : "آخر ظهور " + when(ts);
    }

    // ------------------------------------------------------------------ text direction

    /** True when the first strong character is right-to-left (Arabic / Hebrew …): the bubble text is right-aligned. */
    static boolean startsRtl(CharSequence s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); i++) {
            byte d = Character.getDirectionality(s.charAt(i));
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true;
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ emoji panel content

    static final String[][] EMOJI = {
            {"😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "😉", "😍", "🥰", "😘", "😋", "😎", "🤩", "🥳", "😏",
                    "😒", "😞", "😔", "😟", "😕", "🙁", "😣", "😖", "😫", "😩", "🥺", "😢", "😭", "😤", "😠", "😡", "🤯", "😳", "😱", "😨",
                    "🤔", "🤗", "🤭", "🤫", "😶", "😐", "😑", "😬", "🙄", "😯", "😴", "🤤", "😪", "😷", "🤒", "🤕", "🤑", "🤠", "😈", "👻"},
            {"👍", "👎", "👌", "✌️", "🤞", "🤝", "🙏", "👏", "🙌", "👋", "💪", "👀", "🫡", "🤲", "✋", "👆", "👇", "👉", "👈", "☝️"},
            {"❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "💔", "❣️", "💕", "💯", "✨", "⭐", "🌟", "🔥", "🎉", "🎊", "✅", "❌",
                    "⚠️", "❓", "❗", "💡", "📌", "⏰", "📅", "📎", "🔔", "🔕"},
            {"🎬", "🎥", "📺", "🍿", "🎞️", "📽️", "🎭", "🎧", "🎮", "📱", "💻", "📶", "🔊", "🔇", "⏯️", "⏸️", "▶️", "⏩", "⏪", "🔁"},
    };
    static final String[] EMOJI_TABS = {"😀", "👍", "❤️", "🎬"};
}
