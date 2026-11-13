package com.Cigram.vid;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * «تابعنا على Telegram» — the official Cigram channel.
 *
 * A tap goes straight into the official Telegram app (the Google Play build, then the build from telegram.org) on the
 * channel itself: no browser page in between and no chooser. Only when Telegram is not installed the channel's web
 * page opens, which offers to install it.
 */
final class CigramTelegram {

    private CigramTelegram() { }

    static final String CHANNEL = "CigramApp";
    static final String WEB = "https://t.me/" + CHANNEL;
    /** Official Telegram packages only (third-party clients are not opened on the user's behalf). */
    private static final String[] OFFICIAL = {"org.telegram.messenger", "org.telegram.messenger.web"};

    static void open(Context c) {
        if (c == null) return;
        for (String pkg : OFFICIAL) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=" + CHANNEL));
                i.setPackage(pkg);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return;
            } catch (ActivityNotFoundException notInstalled) {
                // try the next official build
            } catch (Throwable ignored) { }
        }
        try {
            Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(WEB));
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(web);
        } catch (Throwable t) {
            try { CigramSilent.toast(c, "ثبّت تطبيق Telegram ثم ابحث عن @" + CHANNEL, android.widget.Toast.LENGTH_LONG); } catch (Throwable ignored) { }
        }
    }
}
