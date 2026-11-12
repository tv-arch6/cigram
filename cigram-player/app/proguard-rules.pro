# Cigram Player — R8 rules. No reflection in this app: the manifest rules AGP generates keep the Activities,
# ExoPlayer ships its own consumer rules. Nothing else needs to be kept.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*
-repackageclasses ''

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn com.google.android.gms.ads.**   # AdMob and UMP ship their own consumer rules
-dontwarn kotlin.**
-dontwarn kotlinx.**
