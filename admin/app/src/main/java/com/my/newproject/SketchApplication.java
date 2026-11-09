package com.my.newproject;

import android.app.Application;
import android.util.Log;

public class SketchApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CgCfg.loadToken(this);   // the admin token typed once in the app (see TokenGate)
        // Last line of defence: an exception thrown on a worker thread (network, upload, parsing) must not kill the whole app.
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                Log.e("CigramAdmin", "uncaught on " + (t == null ? "?" : t.getName()), e);
                boolean main = t != null && t == android.os.Looper.getMainLooper().getThread();
                if (!main) return;                       // background failure: log and keep the app alive
                if (previous != null) previous.uncaughtException(t, e);
            }
        });
    }
}
