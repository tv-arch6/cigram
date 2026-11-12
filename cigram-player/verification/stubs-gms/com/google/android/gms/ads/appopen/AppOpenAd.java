package com.google.android.gms.ads.appopen; import android.app.Activity; import android.content.Context; import com.google.android.gms.ads.*;
public abstract class AppOpenAd { public abstract static class AppOpenAdLoadCallback { public void onAdLoaded(AppOpenAd a){} public void onAdFailedToLoad(LoadAdError e){} }
 public static void load(Context c, String u, AdRequest r, int orientation, AppOpenAdLoadCallback cb){}
 public void setFullScreenContentCallback(FullScreenContentCallback c){} public void show(Activity a){} }
