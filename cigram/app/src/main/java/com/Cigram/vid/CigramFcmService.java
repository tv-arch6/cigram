package com.Cigram.vid;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * Receives every push sent from the admin app / Cloudflare Worker (v10.67).
 * Runs on a background thread, also when Cigram is closed (HIGH-priority messages wake the app).
 * New films / series / episodes, admin messages and update notices are all handled by CigramPush.
 */
public class CigramFcmService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(RemoteMessage message) {
        try {
            if (message == null) return;
            // Firebase is created in CigramStage1InitProvider.onCreate (before any service starts);
            // this is only a safety net for a process that was started by this push.
            try { CigramFcm.ensureApp(getApplicationContext()); } catch (Throwable ignored) { }
            java.util.Map<String, String> data = message.getData();
            String nTitle = null;
            String nBody = null;
            try {
                RemoteMessage.Notification n = message.getNotification();
                if (n != null) {
                    nTitle = n.getTitle();
                    nBody = n.getBody();
                }
            } catch (Throwable ignored) { }
            if ((data == null || data.isEmpty()) && nTitle == null && nBody == null) return;
            if (data == null) data = new java.util.HashMap<String, String>();
            CigramPush.onMessage(getApplicationContext(), data, nTitle, nBody);
        } catch (Throwable error) {
            android.util.Log.w("CigramFcm", "onMessageReceived failed", error);
        }
    }

    @Override
    public void onNewToken(String token) {
        // topic subscriptions follow the token automatically; re-run the sync to be sure
        try {
            CigramFcm.resetState(getApplicationContext());
            CigramFcm.sync(getApplicationContext());
        } catch (Throwable ignored) {
        }
        // 19.0: the private-support chat needs the new token too (targeted push of an administrator's reply)
        AdsDevice.onNewToken(getApplicationContext(), token);
    }
}
