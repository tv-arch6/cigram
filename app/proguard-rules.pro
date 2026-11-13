# =====================================================================================
#  CIGRAM (user app, com.Cigram.vid) — R8 / ProGuard rules for AGP 8.13 (R8 full mode)
#
#  Written from the real code of this project (not a generic template):
#   * JSON is org.json + HashMap (Gson only does toJson/fromJson on HashMap)  -> no model classes to keep
#   * reflection that R8 would silently break is listed in section 3
#   * the old blanket "-ignorewarnings / -dontwarn / -dontnote" is REMOVED: it hid real problems.
#     If the first release build reports "Missing classes", add the lines from
#     app/build/outputs/mapping/<variant>/missing_rules.txt (or temporarily add -ignorewarnings).
# =====================================================================================

# ---- 0. Crash reports -------------------------------------------------------------
# Keep file/line info so Play Console / Crashlytics stack traces can be de-obfuscated with mapping.txt
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Needed by reflection, generics (Gson TypeToken), annotations, inner classes
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*

# Original project rule (kept): move every renamed class to the root package -> smaller dex
-repackageclasses ''

# Strip verbose logging from release (keeps Log.w / Log.e for diagnostics)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ---- 1. Manifest components -------------------------------------------------------
# Activities, services, providers declared in AndroidManifest.xml are kept (with their names) by the
# rules AGP generates from the manifest. Only classes referenced from <meta-data> by NAME are not:

# Cast SDK instantiates this provider by the class name stored in <meta-data OPTIONS_PROVIDER_CLASS_NAME>
-keep class com.Cigram.vid.CigramCastOptionsProvider { <init>(); }
-keep class * implements com.google.android.gms.cast.framework.OptionsProvider { <init>(); }

# ---- 2. Player (PlayerActivity, CigramLivePlayer, CigramChannelPlayer, CigramTogetherPlayerActivity) --
# ExoPlayer 2.19 / extension-cast ship their own consumer rules. Extra safety for what this app does:
# CigramFonts and others use Proxy / activity-result reflection (section 3); player UI classes are used
# through layouts. Only silence annotation-only warnings that the player libraries reference:
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.*

# ---- 3. Reflection used by this code base (verified by grep) --------------------------
# CigramFonts: androidx.activity.result.* via Class.forName + Proxy.newProxyInstance + getMethod("launch"/"registerForActivityResult")
-keep class androidx.activity.result.** { *; }

# CigramStage1InitProvider: Class.forName("androidx.swiperefreshlayout...SwipeRefreshLayout") +
#   getMethod("setOnRefreshListener"/"setColorSchemeColors") + Proxy for OnRefreshListener
-keep class androidx.swiperefreshlayout.widget.SwipeRefreshLayout { public *; }
-keep interface androidx.swiperefreshlayout.widget.SwipeRefreshLayout$OnRefreshListener { *; }

# Main/Search/Settings/Section/Account/Ads/Player/Channels: className.contains("DrawerLayout") + getMethod("setDrawerLockMode")
-keepnames class androidx.drawerlayout.widget.DrawerLayout
-keepclassmembers class androidx.drawerlayout.widget.DrawerLayout { public void setDrawerLockMode(int); }

# SettingsActivity: profileAvatar.getClass().getMethod("setBorderWidth", int.class)  (CircleImageView)
-keepclassmembers class de.hdodenhof.circleimageview.CircleImageView { public void setBorderWidth(int); }

# CigramNavFx: getMethod("smoothScrollToPosition"/"computeHorizontalScrollOffset"/"computeVerticalScrollOffset")
-keepclassmembers class androidx.recyclerview.widget.RecyclerView {
    public void smoothScrollToPosition(int);
    public int computeHorizontalScrollOffset();
    public int computeVerticalScrollOffset();
}

# Class.forName(pkg + "." + name) for screens that are manifest Activities (CigramBottomNav, CigramPreload,
# CigramPush, CigramChannels, ...) -> names are already kept by the manifest rules. Belt and braces:
-keepnames class com.Cigram.vid.*Activity

# ---- 4. Networking (RequestNetwork / RequestNetworkController / OkHttp / Gson) ---------------
# Gson is only used as toJson(HashMap) / fromJson(String, HashMap.class); no data classes are bound.
# Gson 2.11 ships consumer rules; these are the standard extras for the (unused today) TypeToken/Serializable paths:
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Network layer (callbacks are interfaces implemented by Activities)
-keep class com.Cigram.vid.RequestNetwork { *; }
-keep class com.Cigram.vid.RequestNetwork$* { *; }
-keepclassmembers class com.Cigram.vid.RequestNetworkController { *; }

# OkHttp / Okio: consumer rules ship with 4.12; optional TLS providers are not on the class path
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Jsoup
-keeppackagenames org.jsoup.nodes
-dontwarn org.jspecify.annotations.**

# ---- 5. Ads (AdMob / UMP / Cigram ad engine) -----------------------------------------------
# play-services-ads and user-messaging-platform ship their own consumer rules.
# Ad engine classes (CigramAdManager, CigramAdConfig, CigramEpisodeAds, CigramRewardUnlock, CigramAppOpenAd,
# Ads*, AdSlotView, ...) are plain Java: no reflection, safe to obfuscate.
-dontwarn com.google.android.gms.ads.**

# ---- 6. Push (Firebase Messaging) ---------------------------------------------------------
# Firebase is initialised from code (CigramFcm -> FirebaseOptions.Builder); CigramFcmService is a manifest
# component. Firebase ships its own consumer rules: nothing to add.

# ---- 7. WebView bridges (none today; defensive for future @JavascriptInterface use) ------------
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- 8. Standard platform rules not in proguard-android-optimize.txt -----------------------
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Kotlin metadata is only present through third-party libs
-dontwarn kotlin.**
-dontwarn kotlinx.**
