package com.my.newproject;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.HashMap;
import java.util.Map;

/** 19.0 — receives the Worker's push for a new user message (also while the app is closed) and the refreshed device token. */
public class AdFcmService extends FirebaseMessagingService {

    @Override public void onMessageReceived(RemoteMessage message) {
        try {
            if (message == null) return;
            Map<String, String> data = message.getData();
            if (data == null) data = new HashMap<String, String>();
            if ("chat".equals(data.get("kind")) && "admin".equals(data.get("to"))) AdInboxNotifier.showPush(getApplicationContext(), data);
        } catch (Throwable error) {
            android.util.Log.w("AdFcm", "onMessageReceived failed", error);
        }
    }

    @Override public void onNewToken(String token) {
        AdFcm.onNewToken(getApplicationContext(), token);
    }
}
