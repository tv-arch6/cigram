package com.google.android.exoplayer2;
import com.google.android.exoplayer2.video.VideoSize;
public interface Player {
 int STATE_IDLE=1, STATE_BUFFERING=2, STATE_READY=3, STATE_ENDED=4;
 interface Listener { default void onPlaybackStateChanged(int s){} default void onPlayWhenReadyChanged(boolean p,int r){} default void onPlayerError(PlaybackException e){} default void onVideoSizeChanged(VideoSize v){} }
 void addListener(Listener l); void removeListener(Listener l);
 void setMediaItem(MediaItem m); void seekTo(long ms); void setPlayWhenReady(boolean b); boolean getPlayWhenReady(); void prepare(); void release();
 long getCurrentPosition(); long getDuration(); int getPlaybackState(); boolean isPlaying();
}
