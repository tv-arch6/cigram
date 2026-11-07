package com.Cigram.vid;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.content.res.*;
import android.graphics.*;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.media.*;
import android.net.*;
import android.os.*;
import android.text.*;
import android.text.style.*;
import android.util.*;
import android.view.*;
import android.view.View;
import android.view.View.*;
import android.view.animation.*;
import android.webkit.*;
import android.widget.*;
import androidx.activity.*;
import androidx.annotation.*;
import androidx.annotation.experimental.*;
import androidx.appcompat.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.resources.*;
import androidx.arch.core.*;
import androidx.coordinatorlayout.*;
import androidx.core.*;
import androidx.core.ktx.*;
import androidx.cursoradapter.*;
import androidx.customview.*;
import androidx.customview.poolingcontainer.*;
import androidx.drawerlayout.*;
import androidx.fragment.*;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.interpolator.*;
import androidx.lifecycle.*;
import androidx.lifecycle.livedata.*;
import androidx.lifecycle.livedata.core.*;
import androidx.lifecycle.runtime.*;
import androidx.lifecycle.viewmodel.*;
import androidx.loader.*;
import androidx.media.*;
import androidx.mediarouter.*;
import androidx.palette.*;
import androidx.print.*;
import androidx.recyclerview.*;
import androidx.savedstate.*;
import androidx.swiperefreshlayout.*;
import androidx.vectordrawable.*;
import androidx.vectordrawable.animated.*;
import androidx.versionedparcelable.*;
import androidx.viewpager.*;
import com.Cigram.vid.databinding.*;
import com.google.android.datatransport.*;
import com.google.android.datatransport.backend.cct.*;
import com.google.android.datatransport.runtime.*;
import com.google.android.exoplayer2.common.*;
import com.google.android.exoplayer2.container.*;
import com.google.android.exoplayer2.core.*;
import com.google.android.exoplayer2.database.*;
import com.google.android.exoplayer2.decoder.*;
import com.google.android.exoplayer2.extractor.*;
import com.google.android.exoplayer2.source.dash.*;
import com.google.android.exoplayer2.source.hls.*;
import com.google.android.exoplayer2.source.rtsp.*;
import com.google.android.exoplayer2.ui.*;
import com.google.android.exoplayer2.upstream.*;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.cast.*;
import com.google.android.gms.cast.framework.*;
import com.google.android.gms.flags.*;
import com.google.firebase.encoders.json.*;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;

public class ChannelsActivity extends AppCompatActivity {
	
	private ChannelsBinding binding;
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		binding = ChannelsBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		initialize(_savedInstanceState);
		
		CigramAdManager.initialize(getApplicationContext()); // self-throttled, background thread, consent-gated
		
		initializeLogic();
	}
	
	private void initialize(Bundle _savedInstanceState) {
		
		binding.cardBein3.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				android.content.Intent liveIntent =
				new android.content.Intent(
				ChannelsActivity.this,
				LiveActivity.class
				);
				
				liveIntent.putExtra(
				"live_url",
				"http://194.76.0.163/8zpo3GsVY7/beneficial2concern/652335?token=ShJdY2ZmQQNHCmMZCDZXUh9GSHAWGFMD.ZDsGQVN.WGBFNX013GR9YV1QbGBp0QE9SWmpcXlQXXlUHWlcbRxFACmcDY1tXEVkbVAoAAQJUFxUbRFldAxdeUAdaVAFcUwcHAhwWQlpXQQMLTFhUG0FQQU1VQl4HWTsFVBQLVABGCVxEXFgeEVwNZgFcWVlZBxcDGwESHERcFxETWAxCCQgfEFNZQEBSRwYbX1dBVFtPF1pWRV5EFExGWxMmJxVJRlZKRVVaQVpcDRtfG0BLFU8XUEpvQlUVQRYEUA8HRUdeEQITHBZfUks8WgpXWl1UF1xWV0MSCkQERk0TDw1ZDBBcQG5AXVYRCQ1MCVVJ"
				);
				
				startActivity(liveIntent);
			}
		});
		
		binding.cardview2.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				android.content.Intent liveIntent =
				new android.content.Intent(
				ChannelsActivity.this,
				LiveActivity.class
				);
				
				liveIntent.putExtra(
				"live_url",
				"https://5d94523502c2d.streamlock.net/home/mystream/chunklist_w1496386509.m3u8"
				);
				
				startActivity(liveIntent);
			}
		});
	}
	
	private void initializeLogic() {
		binding.textviewSportsTitle.setTypeface(CigramTypefaces.get(getAssets(),"fonts/sima.ttf"), 1);
		binding.textBein3.setTypeface(Typeface.DEFAULT, 0);
		binding.textview3.setTypeface(CigramTypefaces.get(getAssets(),"fonts/sima.ttf"), 0);
		binding.textview6.setTypeface(CigramTypefaces.get(getAssets(),"fonts/sima.ttf"), 0);
		// v10.63: الصفحة الجديدة للقنوات (CigramChannelHub). الكود القديم أدناه يعمل فقط إذا تعذّر بناؤها.
		boolean cgChannelsHub = false;
		try { cgChannelsHub = CigramChannelHub.install(ChannelsActivity.this); }
		catch (Throwable cgHubError) { cgChannelsHub = false; }
		if (!cgChannelsHub) {
			// CIGRAM — ChannelsActivity: RTL + Sketchware channels + Admin channels.
			// Keeps the original XML channels, aligns every row from the far right,
			// then merges channels created from the Admin app into their matching sections.
			try {
				final int VISIBLE = android.view.View.VISIBLE;
				final int RTL = android.view.View.LAYOUT_DIRECTION_RTL;
				final int LTR = android.view.View.LAYOUT_DIRECTION_LTR;
				final float cgDensity = getResources().getDisplayMetrics().density;
				
				final android.widget.LinearLayout channelsRoot =
				(android.widget.LinearLayout) findViewById(R.id.linear_channels_root);
				final android.widget.LinearLayout channelsHeader =
				(android.widget.LinearLayout) findViewById(R.id.linear_my_content_header);
				final android.widget.TextView channelsTitle =
				(android.widget.TextView) findViewById(R.id.textview_my_content_title);
				final android.widget.ImageView channelsBack =
				(android.widget.ImageView) findViewById(R.id.imageview_my_content_back);
				
				final android.widget.TextView sportsTitle =
				(android.widget.TextView) findViewById(R.id.textview_sports_title);
				final android.widget.HorizontalScrollView sportsScroll =
				(android.widget.HorizontalScrollView) findViewById(R.id.scroll_sports);
				final android.widget.LinearLayout sportsList =
				(android.widget.LinearLayout) findViewById(R.id.linear_sports_channels);
				final androidx.cardview.widget.CardView beinCard =
				(androidx.cardview.widget.CardView) findViewById(R.id.card_bein3);
				
				final android.widget.TextView newsTitle =
				(android.widget.TextView) findViewById(R.id.textview6);
				final android.widget.HorizontalScrollView newsScroll =
				(android.widget.HorizontalScrollView) findViewById(R.id.hscroll2);
				final android.widget.LinearLayout newsList =
				(android.widget.LinearLayout) findViewById(R.id.linear7);
				final androidx.cardview.widget.CardView sharqiyaCard =
				(androidx.cardview.widget.CardView) findViewById(R.id.cardview2);
				
				if (channelsRoot != null) {
					channelsRoot.setVisibility(VISIBLE);
					channelsRoot.setOrientation(android.widget.LinearLayout.VERTICAL);
					channelsRoot.setLayoutDirection(RTL);
					channelsRoot.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
					channelsRoot.setBackgroundColor(0xFF00121A);
				}
				
				// Keep the back arrow at the far right while Arabic title is right aligned.
				if (channelsHeader != null) {
					channelsHeader.setVisibility(VISIBLE);
					channelsHeader.setBackgroundColor(0xFF00121A);
					channelsHeader.setLayoutDirection(LTR);
					channelsHeader.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
				}
				if (channelsTitle != null) {
					channelsTitle.setVisibility(VISIBLE);
					channelsTitle.setText("القنوات");
					channelsTitle.setTextColor(android.graphics.Color.WHITE);
					channelsTitle.setLayoutDirection(RTL);
					channelsTitle.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
					channelsTitle.setTextAlignment(android.view.View.TEXT_ALIGNMENT_VIEW_END);
					channelsTitle.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
				}
				if (channelsBack != null) {
					channelsBack.setVisibility(VISIBLE);
					channelsBack.setEnabled(true);
					channelsBack.setClickable(true);
					channelsBack.setContentDescription("رجوع");
					channelsBack.setOnClickListener(new android.view.View.OnClickListener() {
						@Override public void onClick(android.view.View v) { finish(); }
					});
				}
				
				if (sportsTitle != null) {
					sportsTitle.setVisibility(VISIBLE);
					sportsTitle.setText("الرياضة");
					sportsTitle.setTextColor(android.graphics.Color.WHITE);
					sportsTitle.setLayoutDirection(RTL);
					sportsTitle.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
					sportsTitle.setTextAlignment(android.view.View.TEXT_ALIGNMENT_VIEW_END);
					sportsTitle.setGravity(android.view.Gravity.RIGHT);
				}
				if (newsTitle != null) {
					newsTitle.setVisibility(VISIBLE);
					newsTitle.setText("الأخبارية");
					newsTitle.setTextColor(android.graphics.Color.WHITE);
					newsTitle.setLayoutDirection(RTL);
					newsTitle.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
					newsTitle.setTextAlignment(android.view.View.TEXT_ALIGNMENT_VIEW_END);
					newsTitle.setGravity(android.view.Gravity.RIGHT);
				}
				
				// IMPORTANT: the HorizontalScrollView itself is LTR for predictable physical scrolling.
				// Its child row is RTL. We open on the physical RIGHT edge.
				if (sportsScroll != null) {
					sportsScroll.setVisibility(VISIBLE);
					sportsScroll.setLayoutDirection(LTR);
					sportsScroll.setHorizontalScrollBarEnabled(false);
					sportsScroll.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
					sportsScroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
					sportsScroll.setFillViewport(false);
				}
				if (newsScroll != null) {
					newsScroll.setVisibility(VISIBLE);
					newsScroll.setLayoutDirection(LTR);
					newsScroll.setHorizontalScrollBarEnabled(false);
					newsScroll.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
					newsScroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
					newsScroll.setFillViewport(false);
				}
				
				if (sportsList != null) {
					sportsList.setVisibility(VISIBLE);
					sportsList.setOrientation(android.widget.LinearLayout.HORIZONTAL);
					sportsList.setLayoutDirection(RTL);
					sportsList.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
					sportsList.setBackgroundColor(android.graphics.Color.TRANSPARENT);
					android.view.ViewGroup.LayoutParams lp = sportsList.getLayoutParams();
					if (lp != null) { lp.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT; sportsList.setLayoutParams(lp); }
				}
				if (newsList != null) {
					newsList.setVisibility(VISIBLE);
					newsList.setOrientation(android.widget.LinearLayout.HORIZONTAL);
					newsList.setLayoutDirection(RTL);
					newsList.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
					newsList.setBackgroundColor(android.graphics.Color.TRANSPARENT);
					android.view.ViewGroup.LayoutParams lp = newsList.getLayoutParams();
					if (lp != null) { lp.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT; newsList.setLayoutParams(lp); }
				}
				
				if (beinCard != null) {
					beinCard.setVisibility(VISIBLE);
					beinCard.setEnabled(true);
					beinCard.setClickable(true);
				}
				if (sharqiyaCard != null) {
					sharqiyaCard.setVisibility(VISIBLE);
					sharqiyaCard.setEnabled(true);
					sharqiyaCard.setClickable(true);
				}
				
				android.widget.ImageView beinLogo = (android.widget.ImageView) findViewById(R.id.image_bein3);
				android.widget.ImageView sharqiyaLogo = (android.widget.ImageView) findViewById(R.id.imageview3);
				if (beinLogo != null) {
					beinLogo.setVisibility(VISIBLE);
					beinLogo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
					beinLogo.setAdjustViewBounds(true);
				}
				if (sharqiyaLogo != null) {
					sharqiyaLogo.setVisibility(VISIBLE);
					sharqiyaLogo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
					sharqiyaLogo.setAdjustViewBounds(true);
				}
				android.widget.TextView sharqiyaText = (android.widget.TextView) findViewById(R.id.textview3);
				if (sharqiyaText != null) sharqiyaText.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
				
				final android.graphics.Typeface cgChannelFont;
				android.graphics.Typeface cgTempFont = android.graphics.Typeface.DEFAULT;
				try { cgTempFont = CigramTypefaces.get(getAssets(), "fonts/sima.ttf"); }
				catch (Exception ignored) {}
				cgChannelFont = cgTempFont;
				
				class CgChannelImageLoader {
					final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
					final android.util.LruCache<String, android.graphics.Bitmap> memory =
					new android.util.LruCache<String, android.graphics.Bitmap>(20);
					
					android.graphics.Bitmap fetch(String url) {
						if (url == null || url.trim().length() == 0) return null;
						java.net.HttpURLConnection c = null;
						java.io.InputStream input = null;
						try {
							c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
							c.setConnectTimeout(10000);
							c.setReadTimeout(15000);
							c.setInstanceFollowRedirects(true);
							c.setUseCaches(true);
							c.setRequestProperty("Accept", "image/*");
							int code = c.getResponseCode();
							if (code >= 200 && code < 300) {
								input = c.getInputStream();
								return android.graphics.BitmapFactory.decodeStream(input);
							}
						} catch (Exception ignored) {
						} finally {
							try { if (input != null) input.close(); } catch (Exception ignored) {}
							if (c != null) c.disconnect();
						}
						return null;
					}
					
					String alternate(String url) {
						if (url == null) return "";
						final String oldHost = "https://pub-eabe41d14a0644e5800344c99ef4d971.r2.dev";
						final String newHost = "https://pub-6ce52a4bbf2543ee88ad9883ae42a806.r2.dev";
						if (url.startsWith(oldHost)) return newHost + url.substring(oldHost.length());
						if (url.startsWith(newHost)) return oldHost + url.substring(newHost.length());
						return "";
					}
					
					void load(final String rawUrl, final android.widget.ImageView target) {
						if (target == null) return;
						final String url = rawUrl == null ? "" : rawUrl.trim();
						target.setImageDrawable(null);
						target.setBackgroundColor(0xFF101820);
						if (url.length() == 0) return;
						
						android.graphics.Bitmap cached = memory.get(url);
						if (cached != null && !cached.isRecycled()) {
							target.setBackgroundColor(android.graphics.Color.TRANSPARENT);
							target.setImageBitmap(cached);
							return;
						}
						
						target.setTag(R.id.imageview3, url);
						new java.lang.Thread(new java.lang.Runnable() {
							@Override public void run() {
								android.graphics.Bitmap bitmap = fetch(url);
								if (bitmap == null) {
									String backup = alternate(url);
									if (backup.length() > 0) bitmap = fetch(backup);
								}
								final android.graphics.Bitmap result = bitmap;
								if (result != null) memory.put(url, result);
								main.post(new java.lang.Runnable() {
									@Override public void run() {
										Object tag = target.getTag(R.id.imageview3);
										if (result != null && tag != null && url.equals(String.valueOf(tag))) {
											target.setBackgroundColor(android.graphics.Color.TRANSPARENT);
											target.setImageBitmap(result);
										}
									}
								});
							}
						}, "cigram-channel-image").start();
					}
				}
				final CgChannelImageLoader cgChannelImages = new CgChannelImageLoader();
				
				class CgChannelMerge {
					final String ADMIN_TAG = "cigram_admin_channel:";
					final String CATEGORY_TAG = "cigram_admin_category";
					
					int dp(float value) { return Math.round(value * cgDensity); }
					
					
					android.widget.LinearLayout.LayoutParams standardCardParams() {
						android.widget.LinearLayout.LayoutParams lp =
						new android.widget.LinearLayout.LayoutParams(dp(140), dp(160));
						lp.setMargins(dp(5), 0, dp(5), dp(6));
						return lp;
					}
					
					void normalizeExistingCard(androidx.cardview.widget.CardView card) {
						if (card == null) return;
						android.view.ViewGroup.LayoutParams raw = card.getLayoutParams();
						android.widget.LinearLayout.LayoutParams lp;
						if (raw instanceof android.widget.LinearLayout.LayoutParams) {
							lp = (android.widget.LinearLayout.LayoutParams) raw;
							lp.width = dp(140);
							lp.height = dp(160);
							lp.setMargins(dp(5), 0, dp(5), dp(6));
						} else {
							lp = standardCardParams();
						}
						card.setLayoutParams(lp);
						card.setRadius(dp(16));
						card.setCardElevation(0f);
						card.setUseCompatPadding(false);
						card.setCardBackgroundColor(0x66000000);
						
						if (card.getChildCount() > 0 && card.getChildAt(0) instanceof android.widget.LinearLayout) {
							android.widget.LinearLayout body = (android.widget.LinearLayout) card.getChildAt(0);
							body.setGravity(android.view.Gravity.CENTER);
							android.view.ViewGroup.LayoutParams bodyRaw = body.getLayoutParams();
							if (bodyRaw != null) {
								bodyRaw.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
								bodyRaw.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
								body.setLayoutParams(bodyRaw);
							}
						}
					}
					
					String normalize(String value) {
						if (value == null) return "";
						return value.trim().toLowerCase(java.util.Locale.US)
						.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
						.replace("ة", "ه").replace("ى", "ي")
						.replaceAll("[\\s_\\-]+", "");
					}
					
					void collectText(android.view.View view, java.util.HashSet<String> out) {
						if (view == null) return;
						if (view instanceof android.widget.TextView) {
							String key = normalize(String.valueOf(((android.widget.TextView) view).getText()));
							if (key.length() > 0) out.add(key);
						}
						if (view instanceof android.view.ViewGroup) {
							android.view.ViewGroup g = (android.view.ViewGroup) view;
							for (int i = 0; i < g.getChildCount(); i++) collectText(g.getChildAt(i), out);
						}
					}
					
					void removeAdminCards(android.widget.LinearLayout row) {
						if (row == null) return;
						for (int i = row.getChildCount() - 1; i >= 0; i--) {
							android.view.View child = row.getChildAt(i);
							Object tag = child == null ? null : child.getTag();
							if (tag != null && String.valueOf(tag).startsWith(ADMIN_TAG)) row.removeViewAt(i);
						}
					}
					
					void removeAdminCategories() {
						if (channelsRoot == null) return;
						for (int i = channelsRoot.getChildCount() - 1; i >= 0; i--) {
							android.view.View child = channelsRoot.getChildAt(i);
							Object tag = child == null ? null : child.getTag();
							if (CATEGORY_TAG.equals(tag)) channelsRoot.removeViewAt(i);
						}
					}
					
					void snapRight(final android.widget.HorizontalScrollView scroll, final android.widget.LinearLayout row) {
						if (scroll == null || row == null) return;
						scroll.post(new java.lang.Runnable() {
							@Override public void run() {
								int viewport = Math.max(0, scroll.getWidth() - scroll.getPaddingLeft() - scroll.getPaddingRight());
								if (row.getMinimumWidth() != viewport) row.setMinimumWidth(viewport);
								row.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
								row.requestLayout();
								row.post(new java.lang.Runnable() {
									@Override public void run() {
										int max = Math.max(0, row.getWidth() - scroll.getWidth());
										scroll.scrollTo(max, 0);
									}
								});
							}
						});
					}
					
					android.widget.TextView makeLabel(String text, float size) {
						android.widget.TextView v = new android.widget.TextView(ChannelsActivity.this);
						v.setText(text == null ? "" : text);
						v.setTextSize(size);
						v.setTextColor(android.graphics.Color.WHITE);
						v.setTypeface(cgChannelFont, android.graphics.Typeface.BOLD);
						v.setGravity(android.view.Gravity.CENTER);
						v.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
						v.setMaxLines(2);
						v.setEllipsize(android.text.TextUtils.TruncateAt.END);
						return v;
					}
					
					android.view.View makeCard(final org.json.JSONObject channel) {
						final String name = channel.optString("name", "").trim();
						final String channelUrl = channel.optString("url", channel.optString("link", "")).trim();
						final String imageUrl = channel.optString(
						"image",
						channel.optString("poster",
						channel.optString("logo",
						channel.optString("image_url", channel.optString("poster_url", ""))
						)
						)
						).trim();
						final boolean available = channel.optBoolean("available", true) && channelUrl.length() > 0;
						
						androidx.cardview.widget.CardView card = new androidx.cardview.widget.CardView(ChannelsActivity.this);
						card.setRadius(dp(16));
						card.setCardElevation(0f);
						card.setUseCompatPadding(false);
						card.setCardBackgroundColor(0x66000000);
						card.setAlpha(available ? 1f : 0.4f);
						card.setTag(ADMIN_TAG + normalize(name) + ":" + channelUrl);
						
						android.widget.LinearLayout body = new android.widget.LinearLayout(ChannelsActivity.this);
						body.setOrientation(android.widget.LinearLayout.VERTICAL);
						body.setGravity(android.view.Gravity.CENTER);
						body.setPadding(dp(8), dp(8), dp(8), dp(8));
						card.addView(body, new androidx.cardview.widget.CardView.LayoutParams(-1, -1));
						
						android.widget.ImageView logo = new android.widget.ImageView(ChannelsActivity.this);
						logo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
						logo.setAdjustViewBounds(false);
						logo.setContentDescription(name);
						body.addView(logo, new android.widget.LinearLayout.LayoutParams(dp(130), dp(85)));
						cgChannelImages.load(imageUrl, logo);
						
						android.widget.TextView label = makeLabel(name, 16);
						label.setPadding(dp(8), dp(8), dp(8), dp(8));
						label.setGravity(android.view.Gravity.CENTER);
						android.widget.LinearLayout.LayoutParams labelLp =
						new android.widget.LinearLayout.LayoutParams(-1, -2);
						body.addView(label, labelLp);
						
						card.setClickable(true);
						card.setFocusable(true);
						card.setOnClickListener(new android.view.View.OnClickListener() {
							@Override public void onClick(android.view.View v) {
								if (!available) {
									android.widget.Toast.makeText(
									ChannelsActivity.this,
									"القناة غير متوفرة حالياً",
									android.widget.Toast.LENGTH_SHORT
									).show();
									return;
								}
								android.content.Intent live = new android.content.Intent(ChannelsActivity.this, LiveActivity.class);
								live.putExtra("live_url", channelUrl);
								live.putExtra("video", channelUrl);
								live.putExtra("movie_url", channelUrl);
								live.putExtra("title", name);
								live.putExtra("type", "live");
								live.putExtra("autoplay", true);
								startActivity(live);
							}
						});
						return card;
					}
					
					void addChannels(android.widget.LinearLayout row, org.json.JSONArray channels, java.util.HashSet<String> existing) {
						if (row == null || channels == null) return;
						for (int i = 0; i < channels.length(); i++) {
							org.json.JSONObject channel = channels.optJSONObject(i);
							if (channel == null) continue;
							String name = channel.optString("name", "").trim();
							String key = normalize(name);
							if (key.length() == 0 || existing.contains(key)) continue;
							android.view.View card = makeCard(channel);
							android.widget.LinearLayout.LayoutParams lp = standardCardParams();
							row.addView(card, 0, lp);
							existing.add(key);
						}
					}
					
					void createExtraCategory(String name, org.json.JSONArray channels) {
						if (channelsRoot == null || channels == null || channels.length() == 0) return;
						android.widget.LinearLayout box = new android.widget.LinearLayout(ChannelsActivity.this);
						box.setTag(CATEGORY_TAG);
						box.setOrientation(android.widget.LinearLayout.VERTICAL);
						box.setLayoutDirection(RTL);
						box.setBackgroundColor(0xFF00121A);
						
						android.widget.TextView title = makeLabel(name.length() == 0 ? "القنوات" : name, 22);
						title.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
						title.setPadding(dp(8), dp(10), dp(8), dp(10));
						box.addView(title, new android.widget.LinearLayout.LayoutParams(-1, -2));
						
						final android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(ChannelsActivity.this);
						scroll.setLayoutDirection(LTR);
						scroll.setHorizontalScrollBarEnabled(false);
						scroll.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
						final android.widget.LinearLayout row = new android.widget.LinearLayout(ChannelsActivity.this);
						row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
						row.setLayoutDirection(RTL);
						row.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
						row.setPadding(dp(5), dp(5), dp(5), dp(5));
						scroll.addView(row, new android.widget.HorizontalScrollView.LayoutParams(-2, -2));
						box.addView(scroll, new android.widget.LinearLayout.LayoutParams(-1, -2));
						channelsRoot.addView(box, new android.widget.LinearLayout.LayoutParams(-1, -2));
						
						java.util.HashSet<String> existing = new java.util.HashSet<String>();
						addChannels(row, channels, existing);
						snapRight(scroll, row);
					}
					
					void render(String raw) {
						if (raw == null || raw.trim().length() == 0) {
							snapRight(sportsScroll, sportsList);
							snapRight(newsScroll, newsList);
							return;
						}
						try {
							org.json.JSONObject root = new org.json.JSONObject(raw);
							org.json.JSONArray groups = root.optJSONArray("categories");
							if (groups == null) return;
							
							removeAdminCards(sportsList);
							removeAdminCards(newsList);
							removeAdminCategories();
							
							java.util.HashSet<String> sportsExisting = new java.util.HashSet<String>();
							java.util.HashSet<String> newsExisting = new java.util.HashSet<String>();
							collectText(sportsList, sportsExisting);
							collectText(newsList, newsExisting);
							
							for (int g = 0; g < groups.length(); g++) {
								org.json.JSONObject group = groups.optJSONObject(g);
								if (group == null) continue;
								String groupName = group.optString("name", "").trim();
								String key = normalize(groupName);
								org.json.JSONArray channels = group.optJSONArray("channels");
								if (channels == null || channels.length() == 0) continue;
								
								if (key.contains("رياض") || key.contains("sport")) {
									addChannels(sportsList, channels, sportsExisting);
								} else if (key.contains("اخبار") || key.contains("خبر") || key.contains("news")) {
									addChannels(newsList, channels, newsExisting);
								} else {
									createExtraCategory(groupName, channels);
								}
							}
							
							// Never let the remote catalog rename the page itself.
							if (channelsTitle != null) channelsTitle.setText("القنوات");
							snapRight(sportsScroll, sportsList);
							snapRight(newsScroll, newsList);
						} catch (Exception error) {
							android.util.Log.e("CigramChannels", "Admin channels render failed", error);
							snapRight(sportsScroll, sportsList);
							snapRight(newsScroll, newsList);
						}
					}
				}
				
				final CgChannelMerge cgMerge = new CgChannelMerge();
				cgMerge.normalizeExistingCard(beinCard);
				cgMerge.normalizeExistingCard(sharqiyaCard);
				final android.content.SharedPreferences cgPrefs =
				getSharedPreferences("cigram_channels_cache_v2", android.content.Context.MODE_PRIVATE);
				
				// Show cached Admin channels instantly, then refresh from the Worker.
				cgMerge.render(cgPrefs.getString("json", ""));
				
				new java.lang.Thread(new java.lang.Runnable() {
					@Override public void run() {
						java.net.HttpURLConnection connection = null;
						java.io.BufferedReader reader = null;
						try {
							String endpoint = "https://sigram-channels-api.wwq-mixtv.workers.dev/channels?_=" +
							java.lang.System.currentTimeMillis();
							connection = (java.net.HttpURLConnection) new java.net.URL(endpoint).openConnection();
							connection.setConnectTimeout(10000);
							connection.setReadTimeout(15000);
							connection.setUseCaches(false);
							connection.setRequestProperty("Accept", "application/json");
							connection.setRequestProperty("Cache-Control", "no-cache");
							int code = connection.getResponseCode();
							if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
							
							reader = new java.io.BufferedReader(
							new java.io.InputStreamReader(connection.getInputStream(), "UTF-8"));
							StringBuilder json = new StringBuilder();
							String line;
							while ((line = reader.readLine()) != null) {
								json.append(line);
								if (json.length() > 4000000) throw new java.io.IOException("Catalog too large");
							}
							final String value = json.toString();
							org.json.JSONObject root = new org.json.JSONObject(value);
							if (!root.optBoolean("ok", true) || root.optJSONArray("categories") == null) {
								throw new java.io.IOException("Invalid channels catalog");
							}
							cgPrefs.edit()
							.putString("json", root.toString())
							.putLong("fetched_at", java.lang.System.currentTimeMillis())
							.putString("error", "")
							.apply();
							
							runOnUiThread(new java.lang.Runnable() {
								@Override public void run() {
									cgMerge.render(value);
									CigramChannels.refresh(ChannelsActivity.this);
								}
							});
						} catch (Exception error) {
							cgPrefs.edit().putString("error", "تعذر تحديث القنوات؛ آخر نسخة محفوظة باقية").apply();
							android.util.Log.e("CigramChannels", "Admin channels refresh failed", error);
						} finally {
							try { if (reader != null) reader.close(); } catch (Exception ignored) {}
							if (connection != null) connection.disconnect();
						}
					}
				}, "cigram-channels-admin-refresh").start();
				
				// Initial right-edge placement for XML channels even before network returns.
				cgMerge.snapRight(sportsScroll, sportsList);
				cgMerge.snapRight(newsScroll, newsList);
				
				// v2.7: new channels page (search, chips, favourites, picture cards)
				CigramChannels.bind(ChannelsActivity.this);
				
				if (channelsRoot != null) {
					channelsRoot.requestLayout();
					channelsRoot.invalidate();
				}
			} catch (Exception channelsError) {
				android.util.Log.e("CigramChannels", "Failed to prepare RTL/Admin channels", channelsError);
			}
			
			// CIGRAM: no side drawer / no visible edge scrollbars on this screen.
			try {
				final android.view.View channelsUiRoot = findViewById(android.R.id.content);
				class CigramChannelsUiCleanup {
					void apply(android.view.View view) {
						if (view == null) return;
						try {
							view.setVerticalScrollBarEnabled(false);
							view.setHorizontalScrollBarEnabled(false);
						} catch (Exception ignored) {}
						String className = view.getClass().getName();
						if (className != null && className.contains("DrawerLayout")) {
							try {
								java.lang.reflect.Method lockMethod = view.getClass().getMethod("setDrawerLockMode", int.class);
								lockMethod.invoke(view, 1);
							} catch (Exception ignored) {}
						}
						if (className != null && className.contains("NavigationView")) view.setVisibility(android.view.View.GONE);
						if (view instanceof android.view.ViewGroup) {
							android.view.ViewGroup group = (android.view.ViewGroup) view;
							for (int i = 0; i < group.getChildCount(); i++) apply(group.getChildAt(i));
						}
					}
				}
				new CigramChannelsUiCleanup().apply(channelsUiRoot);
			} catch (Exception ignored) {}
		}
		
		{
			final android.app.Activity tnActivity = this;
			final float tnDensity = getResources().getDisplayMetrics().density;
			final android.view.ViewGroup tnContent =
			(android.view.ViewGroup) findViewById(android.R.id.content);
			
			final Class<?>[] tnScreens = {
				AccountActivity.class,
				SearchXmlActivity.class,
				ChannelsActivity.class,
				MainActivity.class
			};
			final String[] tnLabels = {
				"الحساب", "البحث", "القنوات", "الرئيسية"
			};
			final int[] tnIcons = {
				R.drawable.nav_account,
				R.drawable.nav_search,
				R.drawable.nav_channels,
				R.drawable.nav_home
			};
			
			int tnFound = -1;
			for (int n = 0; n < tnScreens.length; n++) {
				if (tnActivity.getClass().equals(tnScreens[n])) {
					tnFound = n;
					break;
				}
			}
			
			if (tnFound >= 0 && tnContent != null && tnContent.getChildCount() > 0 &&
			tnContent.findViewWithTag("cigram_nav_shell_v2") == null) {
				
				final int tnCurrent = tnFound;
				final int tnFrom = Math.max(0, Math.min(3,
				getIntent().getIntExtra("cigram_nav_from", tnCurrent)));
				
				final int tnBarHeight = (int) (50 * tnDensity + 0.5f);
				final int tnBarSideMargin = 0;
				final int tnBarBaseBottomMargin = 0;
				final int tnBarCorner = (int) (26 * tnDensity + 0.5f);
				final int tnIndicatorGap = (int) (4 * tnDensity + 0.5f);
				
				/*
     * مهم: محتوى الصفحة يبقى بحجمه الأصلي بالكامل.
     * ماكو bottomMargin ولا قص للصفحة. الشريط فقط يطفو فوق المحتوى.
     */
				final android.view.View tnOriginal = tnContent.getChildAt(0);
				tnContent.removeView(tnOriginal);
				
				final android.widget.FrameLayout tnShell =
				new android.widget.FrameLayout(tnActivity);
				tnShell.setTag("cigram_nav_shell_v2");
				tnShell.setBackgroundColor(0xFF00121A);
				tnContent.addView(tnShell,
				new android.view.ViewGroup.LayoutParams(
				android.view.ViewGroup.LayoutParams.MATCH_PARENT,
				android.view.ViewGroup.LayoutParams.MATCH_PARENT));
				
				android.widget.FrameLayout.LayoutParams tnOriginalParams =
				new android.widget.FrameLayout.LayoutParams(
				android.view.ViewGroup.LayoutParams.MATCH_PARENT,
				android.view.ViewGroup.LayoutParams.MATCH_PARENT);
				tnShell.addView(tnOriginal, tnOriginalParams);
				if (getIntent().getBooleanExtra("cigram_nav_reveal", false)) {
					CigramNavFx.reveal(tnOriginal);
				}
				
				final android.widget.FrameLayout tnBar =
				new android.widget.FrameLayout(tnActivity);
				tnBar.setTag("transparent_cigram_nav");
				tnBar.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);
				tnBar.setClipToPadding(false);
				tnBar.setClipChildren(false);
				tnBar.setElevation(6 * tnDensity);
				
				/*
     * الخلفية موجودة فقط داخل الشريط الدائري نفسه.
     * ما حوله يبقى شفاف 100% حتى تختفي الزوايا المستطيلة القديمة.
     */
				final CigramNavFx.Glass tnBarGlass = CigramNavFx.barGlass(tnActivity);
				tnBar.setBackground(tnBarGlass);
				
				final android.widget.FrameLayout.LayoutParams tnBarParams =
				new android.widget.FrameLayout.LayoutParams(
				android.view.ViewGroup.LayoutParams.MATCH_PARENT,
				tnBarHeight,
				android.view.Gravity.BOTTOM | android.view.Gravity.LEFT);
				tnBarParams.setMargins(
				tnBarSideMargin,
				0,
				tnBarSideMargin,
				tnBarBaseBottomMargin);
				tnShell.addView(tnBar, tnBarParams);
				
				/* مؤشر الصفحة الحالية */
				final android.view.View tnCapsule = new android.view.View(tnActivity);
				tnCapsule.setBackground(CigramNavFx.capsuleGlass(tnActivity));
				
				android.widget.FrameLayout.LayoutParams tnCapsuleParams =
				new android.widget.FrameLayout.LayoutParams(
				1,
				(int) (64 * tnDensity + 0.5f),
				android.view.Gravity.TOP | android.view.Gravity.LEFT);
				tnCapsuleParams.topMargin = (int) (4 * tnDensity + 0.5f);
				tnBar.addView(tnCapsule, tnCapsuleParams);
				
				final android.widget.LinearLayout tnTabs =
				new android.widget.LinearLayout(tnActivity);
				tnTabs.setOrientation(android.widget.LinearLayout.HORIZONTAL);
				tnTabs.setGravity(android.view.Gravity.CENTER);
				tnTabs.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);
				tnTabs.setClipToPadding(false);
				tnTabs.setClipChildren(false);
				tnBar.addView(tnTabs,
				new android.widget.FrameLayout.LayoutParams(
				android.view.ViewGroup.LayoutParams.MATCH_PARENT,
				android.view.ViewGroup.LayoutParams.MATCH_PARENT));
				
				final boolean[] tnOpening = {false};
				
				/* زجاج متحرك داخل الشريط والكبسولة عند تمرير الصفحة للأعلى / لليسار */
				CigramNavFx.bindScrollFx(tnShell, tnOriginal, tnBarGlass, tnCapsule.getBackground());
				final android.widget.ImageView[] tnAccountIcon = {null};
				final android.widget.ImageView[] tnProfileAvatar = {null};
				
				class TnAvatarLoader {
					final java.util.ArrayList<android.widget.ImageView> pendingViews =
					new java.util.ArrayList<android.widget.ImageView>();
					final java.util.ArrayList<java.lang.Boolean> pendingSelected =
					new java.util.ArrayList<java.lang.Boolean>();
					
					String pendingUrl = "";
					String memoryUrl = "";
					android.graphics.Bitmap memoryBitmap = null;
					boolean loading = false;
					
					final long CACHE_MAX_AGE_MS = 12L * 60L * 60L * 1000L;
					final int MAX_AVATAR_BYTES = 6 * 1024 * 1024;
					
					android.graphics.Bitmap decode(java.io.File file) {
						if (file == null || !file.isFile() || file.length() <= 0) {
							return null;
						}
						try {
							android.graphics.BitmapFactory.Options bounds =
							new android.graphics.BitmapFactory.Options();
							bounds.inJustDecodeBounds = true;
							android.graphics.BitmapFactory.decodeFile(
							file.getAbsolutePath(), bounds);
							
							if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
								return null;
							}
							
							int sample = 1;
							while (bounds.outWidth / sample > 512 ||
							bounds.outHeight / sample > 512) {
								sample *= 2;
							}
							
							android.graphics.BitmapFactory.Options options =
							new android.graphics.BitmapFactory.Options();
							options.inSampleSize = sample;
							options.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
							
							return android.graphics.BitmapFactory.decodeFile(
							file.getAbsolutePath(), options);
						} catch (Throwable ignored) {
							return null;
						}
					}
					
					void makeCircular(final android.widget.ImageView view) {
						if (view == null) return;
						
						view.setOutlineProvider(new android.view.ViewOutlineProvider() {
							@Override
							public void getOutline(android.view.View target,
							android.graphics.Outline outline) {
								int w = target.getWidth();
								int h = target.getHeight();
								if (w > 0 && h > 0) {
									outline.setOval(0, 0, w, h);
								}
							}
						});
						view.setClipToOutline(true);
						view.invalidateOutline();
					}
					
					void fallback(android.widget.ImageView view,
					boolean selected, boolean compact) {
						if (view == null) return;
						
						view.setImageResource(R.drawable.nav_account);
						view.setScaleType(view instanceof de.hdodenhof.circleimageview.CircleImageView ? android.widget.ImageView.ScaleType.CENTER_CROP : android.widget.ImageView.ScaleType.FIT_CENTER);
						
						int padding = compact
						? 0
						: (int) (18 * tnDensity + 0.5f);
						view.setPadding(padding, padding, padding, padding);
						view.setAlpha(selected ? 1f : 0.65f);
						
						if (!compact) {
							makeCircular(view);
						}
					}
					
					void apply(android.widget.ImageView view,
					android.graphics.Bitmap bitmap, boolean selected) {
						if (view == null || bitmap == null) return;
						
						view.setPadding(0, 0, 0, 0);
						view.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
						view.clearColorFilter();   // v10.83: the photo is never tinted like the symbols
						view.setImageBitmap(bitmap);
						view.setAlpha(selected ? 1f : 0.82f);
						makeCircular(view);
					}
					
					boolean isLoggedIn() {
						android.content.SharedPreferences prefs =
						tnActivity.getSharedPreferences(
						"auth_session",
						android.content.Context.MODE_PRIVATE);
						
						String tokenValue = prefs.getString("token", "");
						String token = tokenValue == null ? "" : tokenValue.trim();
						
						return prefs.getBoolean("logged_in", false) && !token.isEmpty();
					}
					
					boolean sessionMatches(String expectedUrl) {
						if (expectedUrl == null || expectedUrl.trim().isEmpty()) {
							return false;
						}
						
						android.content.SharedPreferences prefs =
						tnActivity.getSharedPreferences(
						"auth_session",
						android.content.Context.MODE_PRIVATE);
						
						String savedToken = prefs.getString("token", "");
						String savedUrl = prefs.getString("avatar_url", "");
						String savedPath = prefs.getString("avatar_local_path", "");
						
						boolean hasLocalAvatar =
						savedPath != null &&
						!savedPath.trim().isEmpty() &&
						new java.io.File(savedPath.trim()).isFile();
						
						return prefs.getBoolean("logged_in", false)
						&& savedToken != null
						&& !savedToken.trim().isEmpty()
						&& savedUrl != null
						&& expectedUrl.equals(savedUrl.trim())
						&& !hasLocalAvatar;
					}
					
					synchronized void addPending(android.widget.ImageView view,
					boolean selected) {
						if (view == null) return;
						
						for (int i = pendingViews.size() - 1; i >= 0; i--) {
							if (pendingViews.get(i) == view) {
								pendingViews.remove(i);
								pendingSelected.remove(i);
							}
						}
						
						pendingViews.add(view);
						pendingSelected.add(java.lang.Boolean.valueOf(selected));
					}
					
					synchronized void clearPending() {
						pendingViews.clear();
						pendingSelected.clear();
					}
					
					void finishRemote(final String url,
					final android.graphics.Bitmap bitmap) {
						tnActivity.runOnUiThread(new java.lang.Runnable() {
							@Override
							public void run() {
								java.util.ArrayList<android.widget.ImageView> views;
								java.util.ArrayList<java.lang.Boolean> selectedValues;
								
								synchronized (TnAvatarLoader.this) {
									if (!url.equals(pendingUrl)) {
										return;
									}
									
									loading = false;
									views = new java.util.ArrayList<android.widget.ImageView>(
									pendingViews);
									selectedValues =
									new java.util.ArrayList<java.lang.Boolean>(
									pendingSelected);
									clearPending();
									
									if (bitmap != null) {
										memoryUrl = url;
										memoryBitmap = bitmap;
									}
								}
								
								if (bitmap == null ||
								tnActivity.isFinishing() ||
								!sessionMatches(url)) {
									return;
								}
								
								for (int i = 0; i < views.size(); i++) {
									android.widget.ImageView target = views.get(i);
									if (target != null && target.getWindowToken() != null) {
										apply(
										target,
										bitmap,
										selectedValues.get(i).booleanValue());
									}
								}
							}
						});
					}
					
					void download(final String url) {
						new java.lang.Thread(new java.lang.Runnable() {
							@Override
							public void run() {
								java.net.HttpURLConnection connection = null;
								java.io.InputStream input = null;
								java.io.ByteArrayOutputStream output = null;
								java.io.FileOutputStream cacheOutput = null;
								android.graphics.Bitmap downloaded = null;
								
								try {
									java.net.URL parsed = new java.net.URL(url);
									connection =
									(java.net.HttpURLConnection) parsed.openConnection();
									connection.setConnectTimeout(10000);
									connection.setReadTimeout(15000);
									connection.setInstanceFollowRedirects(true);
									connection.setUseCaches(true);
									connection.setRequestProperty("Accept", "image/*");
									
									int response = connection.getResponseCode();
									if (response < 200 || response >= 300) {
										throw new java.io.IOException(
										"Avatar HTTP " + response);
									}
									
									String contentType = connection.getContentType();
									if (contentType != null &&
									!contentType.toLowerCase(java.util.Locale.US)
									.startsWith("image/")) {
										throw new java.io.IOException(
										"Avatar content type is not image");
									}
									
									int length = connection.getContentLength();
									if (length > MAX_AVATAR_BYTES) {
										throw new java.io.IOException(
										"Avatar is too large");
									}
									
									input = connection.getInputStream();
									output = new java.io.ByteArrayOutputStream(
									length > 0 ? length : 32768);
									
									byte[] buffer = new byte[8192];
									int count;
									int total = 0;
									
									while ((count = input.read(buffer)) != -1) {
										total += count;
										if (total > MAX_AVATAR_BYTES) {
											throw new java.io.IOException(
											"Avatar is too large");
										}
										output.write(buffer, 0, count);
									}
									
									byte[] bytes = output.toByteArray();
									downloaded = android.graphics.BitmapFactory.decodeByteArray(
									bytes, 0, bytes.length);
									
									if (downloaded != null && sessionMatches(url)) {
										java.io.File cacheFile = getCacheFile(url);
										cacheOutput = new java.io.FileOutputStream(
										cacheFile, false);
										cacheOutput.write(bytes);
										cacheOutput.flush();
									}
								} catch (Throwable ignored) {
									downloaded = null;
								} finally {
									try {
										if (input != null) input.close();
									} catch (Exception ignored) {}
									try {
										if (output != null) output.close();
									} catch (Exception ignored) {}
									try {
										if (cacheOutput != null) cacheOutput.close();
									} catch (Exception ignored) {}
									if (connection != null) {
										connection.disconnect();
									}
								}
								
								finishRemote(url, downloaded);
							}
						}, "cigram-avatar-loader").start();
					}
					
					java.io.File getCacheFile(String url) {
						return new java.io.File(
						tnActivity.getCacheDir(),
						"cigram_nav_avatar_" +
						java.lang.Integer.toHexString(url.hashCode()) +
						".jpg");
					}
					
					boolean cacheIsFresh(java.io.File file) {
						if (file == null || !file.isFile() || file.length() <= 0) {
							return false;
						}
						long age = java.lang.System.currentTimeMillis() - file.lastModified();
						return age >= 0 && age <= CACHE_MAX_AGE_MS;
					}
					
					void refresh(android.widget.ImageView view,
					boolean selected, boolean compact) {
						if (view == null) return;
						if (CigramAvatar.applyTo(view, selected, compact)) return;   // v10.67: saved photo, same on every page
						
						android.content.SharedPreferences prefs =
						tnActivity.getSharedPreferences(
						"auth_session",
						android.content.Context.MODE_PRIVATE);
						
						if (!isLoggedIn()) {
							synchronized (this) {
								pendingUrl = "";
								loading = false;
								clearPending();
								memoryUrl = "";
								memoryBitmap = null;
							}
							fallback(view, selected, compact);
							return;
						}
						
						String localPathValue = prefs.getString("avatar_local_path", "");
						String localPath = localPathValue == null
						? ""
						: localPathValue.trim();
						
						java.io.File localFile = localPath.isEmpty()
						? null
						: new java.io.File(localPath);
						android.graphics.Bitmap localBitmap = decode(localFile);
						
						if (localBitmap != null) {
							apply(view, localBitmap, selected);
							return;
						}
						
						String avatarUrlValue = prefs.getString("avatar_url", "");
						final String url = avatarUrlValue == null
						? ""
						: avatarUrlValue.trim();
						
						if (!(url.startsWith("https://") || url.startsWith("http://"))) {
							fallback(view, selected, compact);
							return;
						}
						
						synchronized (this) {
							if (url.equals(memoryUrl) && memoryBitmap != null) {
								apply(view, memoryBitmap, selected);
								return;
							}
						}
						
						java.io.File cacheFile = getCacheFile(url);
						android.graphics.Bitmap cached = decode(cacheFile);
						
						if (cached != null && cacheIsFresh(cacheFile)) {
							synchronized (this) {
								memoryUrl = url;
								memoryBitmap = cached;
							}
							apply(view, cached, selected);
							return;
						}
						
						if (cached != null) {
							apply(view, cached, selected);
						} else {
							fallback(view, selected, compact);
						}
						
						boolean shouldStart = false;
						synchronized (this) {
							if (!url.equals(pendingUrl)) {
								pendingUrl = url;
								clearPending();
								loading = false;
							}
							
							addPending(view, selected);
							
							if (!loading) {
								loading = true;
								shouldStart = true;
							}
						}
						
						if (shouldStart) {
							download(url);
						}
					}
				}
				
				final TnAvatarLoader tnAvatarLoader = new TnAvatarLoader();
				
				final android.widget.ImageView[] tnIconViews =
				new android.widget.ImageView[tnScreens.length];
				final android.widget.TextView[] tnLabelViews =
				new android.widget.TextView[tnScreens.length];
				final CigramNavFx.Go[] tnGoRef = {null};
				
				for (int n = 0; n < tnScreens.length; n++) {
					final int tnIndex = n;
					
					android.widget.LinearLayout tnTab =
					new android.widget.LinearLayout(tnActivity);
					tnTab.setOrientation(android.widget.LinearLayout.VERTICAL);
					tnTab.setGravity(android.view.Gravity.CENTER);
					tnTab.setBackgroundColor(android.graphics.Color.TRANSPARENT);
					tnTab.setContentDescription(tnLabels[n]);
					tnTab.setSelected(n == tnCurrent);
					tnTab.setClickable(true);
					tnTab.setFocusable(true);
					
					android.widget.LinearLayout.LayoutParams tnTabParams =
					new android.widget.LinearLayout.LayoutParams(
					0,
					android.view.ViewGroup.LayoutParams.MATCH_PARENT,
					1f);
					tnTabs.addView(tnTab, tnTabParams);
					
					final android.widget.ImageView tnIcon =
					new android.widget.ImageView(tnActivity);
					
					if (tnIndex == 0) {
						tnIcon.setTag("cigram_nav_account_icon");
						tnAccountIcon[0] = tnIcon;
						tnAvatarLoader.refresh(
						tnIcon,
						tnIndex == tnCurrent,
						true);
					} else {
						tnIcon.setImageResource(tnIcons[n]);
						tnIcon.setScaleType(
						android.widget.ImageView.ScaleType.FIT_CENTER);
						tnIcon.setAlpha(n == tnCurrent ? 1f : 0.65f);
					}
					
					android.widget.LinearLayout.LayoutParams tnIconParams =
					new android.widget.LinearLayout.LayoutParams(
					(int) (25 * tnDensity + 0.5f),
					(int) (25 * tnDensity + 0.5f));
					tnTab.addView(tnIcon, tnIconParams);
					
					android.widget.TextView tnLabel =
					new android.widget.TextView(tnActivity);
					tnLabel.setText(tnLabels[n]);
					tnLabel.setTextSize(12);
					tnLabel.setTextColor(
					n == tnCurrent
					? 0xFFFFFFFF
					: 0xFFADBBC2);
					tnLabel.setGravity(android.view.Gravity.CENTER);
					tnLabel.setIncludeFontPadding(false);
					tnLabel.setSingleLine(true);
					
					try {
						tnLabel.setTypeface(
						CigramTypefaces.get(
						getAssets(),
						"fonts/sima.ttf"));
					} catch (Throwable ignored) {}
					
					android.widget.LinearLayout.LayoutParams tnTextParams =
					new android.widget.LinearLayout.LayoutParams(
					android.view.ViewGroup.LayoutParams.MATCH_PARENT,
					android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
					tnTextParams.topMargin = (int) (4 * tnDensity + 0.5f);
					tnTab.addView(tnLabel, tnTextParams);
					
					tnIconViews[n] = tnIcon;
					tnLabelViews[n] = tnLabel;
					CigramNavFx.attachPress(tnTab);
					
					tnTab.setOnClickListener(new android.view.View.OnClickListener() {
						@Override
						public void onClick(final android.view.View v) {
							if (tnOpening[0]) {
								return;
							}
							
							/* النقر على التبويب الحالي: تمرير سلس لأعلى الصفحة */
							if (tnIndex == tnCurrent) {
								CigramNavFx.scrollToTop(tnOriginal, v);
								return;
							}
							
							tnOpening[0] = true;
							v.setEnabled(false);
							CigramNavFx.tick(v);
							
							/* حركة المؤشر تبدأ فوراً في هذه الشاشة، ثم تُفتح الشاشة الجديدة */
							if (tnGoRef[0] != null) {
								tnGoRef[0].run(tnCurrent, tnIndex);
							}
							
							tnBar.postDelayed(new java.lang.Runnable() {
								@Override
								public void run() {
									if (tnActivity.isFinishing()) {
										return;
									}
									
									android.content.Intent tnIntent =
									new android.content.Intent(
									tnActivity,
									tnScreens[tnIndex]);
									tnIntent.putExtra("cigram_nav_from", tnIndex);
									tnIntent.putExtra("cigram_nav_reveal", true);
									tnIntent.addFlags(
									android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION);
									
									if (tnIndex == 3) {
										tnIntent.addFlags(
										android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP |
										android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
										
										tnActivity.getSharedPreferences(
										"cigram_nav_ui", 0)
										.edit()
										.putInt("return_from", tnCurrent)
										.apply();
									}
									
									try {
										tnActivity.startActivity(tnIntent);
										if (tnCurrent != 3) {
											tnActivity.finish();
										}
										tnActivity.overridePendingTransition(0, 0);
									} catch (Throwable tnError) {
										android.util.Log.w("CigramNav", "tab switch failed", tnError);
										tnOpening[0] = false;
										v.setEnabled(true);
										if (tnGoRef[0] != null) {
											tnGoRef[0].run(tnIndex, tnCurrent);
										}
									}
								}
							}, CigramNavFx.LAUNCH_DELAY_MS);
						}
					});
				}
				
				android.view.View tnProfileView =
				tnContent.findViewById(R.id.circleimageview_profile_avatar);
				if (tnProfileView instanceof android.widget.ImageView) {
					tnProfileAvatar[0] =
					(android.widget.ImageView) tnProfileView;
					tnAvatarLoader.refresh(
					tnProfileAvatar[0],
					true,
					false);
				}
				
				final CigramNavFx.Visual tnVisual =
				new CigramNavFx.Visual(tnIconViews, tnLabelViews, 0);
				final android.animation.ValueAnimator[] tnAnim = {null};
				
				class TnIndicator {
					void layoutCapsule() {
						int width = tnBar.getWidth();
						if (width <= 0) return;
						
						float cell = width / (float) tnScreens.length;
						android.widget.FrameLayout.LayoutParams p =
						(android.widget.FrameLayout.LayoutParams)
						tnCapsule.getLayoutParams();
						int wantedWidth = Math.max(
						1,
						(int) cell - (tnIndicatorGap * 2));
						int wantedHeight = (int) (64 * tnDensity + 0.5f);
						if (p.width != wantedWidth || p.height != wantedHeight) {
							p.width = wantedWidth;
							p.height = wantedHeight;
							p.topMargin = (int) (4 * tnDensity + 0.5f);
							tnCapsule.setLayoutParams(p);
						}
					}
					
					void position(int from, boolean animate) {
						int width = tnBar.getWidth();
						if (width <= 0) return;
						
						layoutCapsule();
						float cell = width / (float) tnScreens.length;
						int safeFrom = Math.max(
						0,
						Math.min(tnScreens.length - 1, from));
						
						float startX = safeFrom * cell + tnIndicatorGap;
						float targetX = tnCurrent * cell + tnIndicatorGap;
						
						tnCapsule.animate().cancel();
						if (tnAnim[0] != null) {
							tnAnim[0].cancel();
							tnAnim[0] = null;
						}
						
						if (!animate || safeFrom == tnCurrent) {
							tnCapsule.setTranslationX(targetX);
							tnCapsule.setScaleX(1f);
							tnCapsule.setScaleY(1f);
							tnBarGlass.setGlintX(
							targetX + (cell - tnIndicatorGap * 2) / 2f);
							tnVisual.apply(tnCurrent, tnCurrent, 1f);
							return;
						}
						
						tnAnim[0] = CigramNavFx.slide(
						tnCapsule, tnBarGlass, tnVisual,
						safeFrom, tnCurrent,
						startX, targetX, cell, CigramNavFx.SLIDE_MS);
					}
					
					/* حركة المؤشر عند النقر: من تبويب الشاشة الحالية إلى التبويب الهدف. */
					void animateTo(int from, int to) {
						int width = tnBar.getWidth();
						if (width <= 0) return;
						
						layoutCapsule();
						float cell = width / (float) tnScreens.length;
						
						tnCapsule.animate().cancel();
						if (tnAnim[0] != null) {
							tnAnim[0].cancel();
							tnAnim[0] = null;
						}
						
						tnAnim[0] = CigramNavFx.slide(
						tnCapsule, tnBarGlass, tnVisual,
						from, to,
						from * cell + tnIndicatorGap,
						to * cell + tnIndicatorGap,
						cell, CigramNavFx.SLIDE_MS);
					}
				}
				
				final TnIndicator tnIndicator = new TnIndicator();
				tnGoRef[0] = new CigramNavFx.Go() {
					@Override
					public void run(int from, int to) {
						tnIndicator.animateTo(from, to);
					}
				};
				
				tnBar.post(new java.lang.Runnable() {
					@Override
					public void run() {
						tnIndicator.position(tnFrom, true);
					}
				});
				
				tnBar.addOnLayoutChangeListener(
				new android.view.View.OnLayoutChangeListener() {
					@Override
					public void onLayoutChange(
					android.view.View v,
					int l,
					int t,
					int r,
					int b,
					int oldL,
					int oldT,
					int oldR,
					int oldB) {
						
						int newWidth = r - l;
						int oldWidth = oldR - oldL;
						
						if (newWidth > 0 && newWidth != oldWidth) {
							tnIndicator.position(tnCurrent, false);
						}
					}
				});
				
				/*
     * دعم شريط الإيماءات/Navigation Bar على الأجهزة الحديثة.
     * يزيد فقط المسافة تحت الـ floating bar، بدون تغيير حجم الصفحة.
     */
				if (android.os.Build.VERSION.SDK_INT >= 21) {
					tnShell.setOnApplyWindowInsetsListener(
					new android.view.View.OnApplyWindowInsetsListener() {
						@Override
						public android.view.WindowInsets onApplyWindowInsets(
						android.view.View v,
						android.view.WindowInsets insets) {
							
							int systemBottom = 0;
							if (insets != null) {
								systemBottom = insets.getSystemWindowInsetBottom();
							}
							
							android.widget.FrameLayout.LayoutParams lp =
							(android.widget.FrameLayout.LayoutParams)
							tnBar.getLayoutParams();
							int wantedBottom =
							tnBarBaseBottomMargin + Math.max(0, systemBottom);
							
							if (lp.bottomMargin != wantedBottom) {
								lp.bottomMargin = wantedBottom;
								tnBar.setLayoutParams(lp);
							}
							
							return insets;
						}
					});
					tnShell.requestApplyInsets();
				}
				
				/* إعادة تفعيل النقر وتحديث الحساب عند الرجوع للشاشة. */
				tnBar.getViewTreeObserver().addOnWindowFocusChangeListener(
				new android.view.ViewTreeObserver.OnWindowFocusChangeListener() {
					@Override
					public void onWindowFocusChanged(boolean focused) {
						if (!focused) return;
						
						tnOpening[0] = false;
						
						for (int i = 0; i < tnTabs.getChildCount(); i++) {
							android.view.View child = tnTabs.getChildAt(i);
							if (child != null) child.setEnabled(true);
						}
						
						tnAvatarLoader.refresh(
						tnAccountIcon[0],
						tnCurrent == 0,
						true);
						tnAvatarLoader.refresh(
						tnProfileAvatar[0],
						true,
						false);
						
						if (tnCurrent == 3) {
							android.content.SharedPreferences prefs =
							tnActivity.getSharedPreferences(
							"cigram_nav_ui", 0);
							int from = prefs.getInt("return_from", -1);
							
							if (from >= 0 && from < tnScreens.length) {
								prefs.edit()
								.remove("return_from")
								.apply();
							}
							tnIndicator.position(tnCurrent, false);
						}
					}
				});
			}
		}
		
	}
	
}
