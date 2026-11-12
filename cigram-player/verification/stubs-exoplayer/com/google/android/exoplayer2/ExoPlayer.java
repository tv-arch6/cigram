package com.google.android.exoplayer2;
import android.content.Context;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
public interface ExoPlayer extends Player {
 void setAudioAttributes(AudioAttributes a, boolean focus); void setHandleAudioBecomingNoisy(boolean b);
 final class Builder { public Builder(Context c, DefaultRenderersFactory f){}
  public Builder setTrackSelector(DefaultTrackSelector t){return this;} public Builder setLoadControl(DefaultLoadControl l){return this;}
  public Builder setMediaSourceFactory(MediaSource.Factory f){return this;} public Builder setSeekBackIncrementMs(long l){return this;} public Builder setSeekForwardIncrementMs(long l){return this;}
  public ExoPlayer build(){return null;} }
}
