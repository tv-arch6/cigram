package com.google.android.exoplayer2;
import android.net.Uri; import java.util.List;
public final class MediaItem {
 public static final class Builder { public Builder setUri(Uri u){return this;} public Builder setMimeType(String m){return this;} public Builder setMediaMetadata(MediaMetadata m){return this;} public Builder setSubtitleConfigurations(List<SubtitleConfiguration> l){return this;} public MediaItem build(){return null;} }
 public static final class SubtitleConfiguration { public static final class Builder { public Builder(Uri u){} public Builder setMimeType(String m){return this;} public Builder setLanguage(String l){return this;} public Builder setSelectionFlags(int f){return this;} public SubtitleConfiguration build(){return null;} } }
}
