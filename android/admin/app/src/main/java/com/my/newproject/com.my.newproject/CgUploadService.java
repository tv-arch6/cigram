package com.my.newproject;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Foreground service (type dataSync) that only keeps the process alive and shows the progress
 * notification while CgUploadManager works. All the logic stays in CgUploadManager.
 */
public class CgUploadService extends Service {

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        CgUploadManager m = CgUploadManager.get(this);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(CgUploadManager.NOTIF_PROGRESS, m.currentNotification(), 1 /* FOREGROUND_SERVICE_TYPE_DATA_SYNC */);
            } else {
                startForeground(CgUploadManager.NOTIF_PROGRESS, m.currentNotification());
            }
        } catch (Throwable t) {
            // Missing FOREGROUND_SERVICE permission or background-start restriction: the upload still
            // runs while the app is open, it just may not survive the app being closed.
            Log.w(CgMediaAnalyzer.TAG, "startForeground failed: " + t.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null) {
            // The system restarted us after killing the process.
            try {
                m.resumeInterrupted();
            } catch (Throwable t) {
                Log.e(CgMediaAnalyzer.TAG, "resumeInterrupted failed", t);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }
}
