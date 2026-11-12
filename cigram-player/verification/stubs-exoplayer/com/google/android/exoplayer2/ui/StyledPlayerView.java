package com.google.android.exoplayer2.ui;
import com.google.android.exoplayer2.Player;
public class StyledPlayerView extends android.widget.FrameLayout { public StyledPlayerView(android.content.Context c){super(c);}
 public interface ControllerVisibilityListener { void onVisibilityChanged(int v); }
 public void setPlayer(Player p){} public void setShowSubtitleButton(boolean b){} public void setControllerShowTimeoutMs(int i){} public void setKeepContentOnPlayerReset(boolean b){}
 public void setShutterBackgroundColor(int c){} public void setControllerVisibilityListener(ControllerVisibilityListener l){} public void hideController(){} public void setUseController(boolean b){} }
