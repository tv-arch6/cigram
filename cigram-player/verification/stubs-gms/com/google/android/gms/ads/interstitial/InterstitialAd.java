package com.google.android.gms.ads.interstitial; import android.app.Activity; import android.content.Context; import com.google.android.gms.ads.*;
public abstract class InterstitialAd { public static void load(Context c, String u, AdRequest r, InterstitialAdLoadCallback cb){} public void setFullScreenContentCallback(FullScreenContentCallback c){} public void show(Activity a){} }
