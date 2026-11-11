package com.Cigram.vid;

public class CigramCastOptionsProvider
implements com.google.android.gms.cast.framework.OptionsProvider {

    @Override
    public com.google.android.gms.cast.framework.CastOptions getCastOptions(
        android.content.Context appContext
    ) {
        // Media notification: keeps the cast session (and the app process) alive and controllable
        // while the user browses the phone, and brings the app back when tapped.
        com.google.android.gms.cast.framework.media.NotificationOptions notification =
            new com.google.android.gms.cast.framework.media.NotificationOptions.Builder()
                .setTargetActivityClassName("com.Cigram.vid.MainActivity")
                .build();
        com.google.android.gms.cast.framework.media.CastMediaOptions media =
            new com.google.android.gms.cast.framework.media.CastMediaOptions.Builder()
                .setNotificationOptions(notification)
                .build();

        return new com.google.android.gms.cast.framework.CastOptions.Builder()
            .setReceiverApplicationId(
                com.google.android.gms.cast.CastMediaControlIntent
                    .DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
            )
            .setCastMediaOptions(media)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build();
    }

    @Override
    public java.util.List<
        com.google.android.gms.cast.framework.SessionProvider
    > getAdditionalSessionProviders(
        android.content.Context appContext
    ) {

        return java.util.Collections.emptyList();
    }
}
