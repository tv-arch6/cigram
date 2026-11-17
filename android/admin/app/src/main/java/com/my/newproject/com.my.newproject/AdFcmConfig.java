package com.my.newproject;

/**
 * 19.0 — the PUBLIC client settings of the Android app {@code com.my.newproject} in the Firebase project "cigram" (they are not secrets:
 * every Firebase app ships them in its APK). Fill the two empty values, rebuild, and the admin app receives a push for every new
 * user message even when it is closed. While they are empty FCM stays OFF, the 15-minute poll keeps working, and the inbox menu
 * «الإشعارات والنسخ» says exactly what is missing.
 *
 * Where to find them: Firebase console -> Project settings -> General -> Your apps -> (add the Android app com.my.newproject if it is
 * not there) -> download google-services.json and copy:
 *   APP_ID  = client[0].client_info.mobilesdk_app_id        e.g. "1:288492524620:android:0123456789abcdef"
 *   API_KEY = client[0].api_key[0].current_key               e.g. "AIzaSy..."
 * PROJECT_ID and SENDER_ID below are the ones of the project the main app already uses (see CigramFcm in the main app).
 * Plain constants on purpose: the IDE that builds this project (CodeAssist) does not evaluate Gradle expressions.
 */
final class AdFcmConfig {
    static final String APP_ID = "";
    static final String API_KEY = "";
    static final String PROJECT_ID = "cigram";
    static final String SENDER_ID = "288492524620";

    private AdFcmConfig() { }
}
