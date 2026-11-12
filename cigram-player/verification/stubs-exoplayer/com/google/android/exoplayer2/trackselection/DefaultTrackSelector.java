package com.google.android.exoplayer2.trackselection;
public class DefaultTrackSelector { public DefaultTrackSelector(android.content.Context c){}
 public Parameters.Builder buildUponParameters(){return null;} public void setParameters(Parameters.Builder b){}
 public static final class Parameters { public static final class Builder { public Builder setPreferredAudioLanguages(String... l){return this;} public Builder setPreferredTextLanguage(String l){return this;} public Builder setMaxVideoSize(int w,int h){return this;} } } }
