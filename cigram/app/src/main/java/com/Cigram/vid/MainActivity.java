package com.Cigram.vid;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.content.Intent;
import android.content.res.*;
import android.graphics.*;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.media.*;
import android.net.*;
import android.net.Uri;
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
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.encoders.json.*;


import com.google.firebase.messaging.FirebaseMessaging;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.*;
import org.json.*;

public class MainActivity extends AppCompatActivity {
	
	private MainBinding binding;
	
	private RequestNetwork req_movies;
	private RequestNetwork.RequestListener _req_movies_request_listener;
	private Intent intent_details = new Intent();
	private OnCompleteListener fcm_cigram_onCompleteListener;
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		// CIGRAM TV EDITION: on a television, hand off to the dedicated TV front-end and skip the
		// phone home entirely (the phone UI is never built on TV). No effect on phones / tablets.
		if (CigramTvActivity.shouldRunTvEdition(this)) {
			startActivity(new android.content.Intent(this, CigramTvActivity.class));
			overridePendingTransition(0, 0);
			finish();
			return;
		}
		binding = MainBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		initialize(_savedInstanceState);
		if (_savedInstanceState != null) {
			// restored after the system killed Home: the sections come back from the saved catalogue, the reader goes back where they were
			CigramStage1InitProvider.restoreHomeScrollWhenReady(this, _savedInstanceState.getInt(STATE_SCROLL_Y, 0));
		}
		
		// ANR fix: AdMob initialisation can block for seconds; run it off the main thread
		CigramConsent.gather(this); // UMP consent (EEA/UK), then CigramAdManager starts the SDK off the main thread
		
		initializeLogic();
	}
	
	private static final String STATE_SCROLL_Y = "cigram_home_scroll_y";
	
	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		try {
			android.widget.ScrollView page = (android.widget.ScrollView) findViewById(R.id.vscroll1);
			if (page != null) outState.putInt(STATE_SCROLL_Y, page.getScrollY());
		} catch (Throwable ignored) { }
	}
	
	private void initialize(Bundle _savedInstanceState) {
		req_movies = new RequestNetwork(this);
		
		// Every Home section (أحدث الأفلام and المسلسلات المميزة included) is drawn by ONE pipeline,
		// CigramStage1InitProvider.renderDynamicSections. This listener is only the door the catalogue comes in by:
		// the saved copy, the early download, the tab refresh and this activity's own request all end here.
		_req_movies_request_listener = new RequestNetwork.RequestListener() {
			@Override
			public void onResponse(String _param1, String _param2, HashMap<String, Object> _param3) {
				if (isFinishing() || isDestroyed()) return;
				final String response = _param2;
				if (response == null || response.trim().isEmpty()) {
					android.util.Log.e("CigramDiag", "HOME got NULL/EMPTY catalogue, tag=" + _param1);
					return;
				}
				android.util.Log.i("CigramDiag", "HOME got catalogue tag=" + _param1 + " len=" + response.length());
				CigramStage1InitProvider.trace("R" + response.length());
				try {
					CigramAnalytics.timerStop(MainActivity.this, "home_load_ms");
				} catch (Throwable ignored) { }
				CigramStage1InitProvider.homeCatalogReceived(MainActivity.this, response);
			}
			
			@Override
			public void onErrorResponse(String _param1, String _param2) {
				android.util.Log.e("CigramDiag", "HOME onErrorResponse tag=" + _param1 + " message=" + _param2);
				if (isFinishing() || isDestroyed()) return;
				cgShowCatalogFailure();
			}
		};
	}
	
	/**
	 * The catalogue could not be downloaded. A Home that already shows sections stays as it is; otherwise the last saved
	 * catalogue is drawn, and only when there is none either does the page say so, with a retry button.
	 */
	private void cgShowCatalogFailure() {
		final android.widget.LinearLayout page = (android.widget.LinearLayout) findViewById(R.id.linear1);
		if (page == null) return;
		android.view.View loading = page.findViewWithTag("cigram_home_loading_state");
		if (loading != null) page.removeView(loading);
		
		if (CigramStage1InitProvider.homeHasSections(this)) {
			android.widget.Toast.makeText(this, "تعذر تحديث المحتوى؛ المعروض حاليًا باقٍ", android.widget.Toast.LENGTH_SHORT).show();
			return;
		}
		String saved = CigramCatalogPrefs.of(this).getString("json", "");
		if (saved != null && !saved.trim().isEmpty()) {
			_req_movies_request_listener.onResponse("movies", saved, new HashMap<String, Object>());
			android.widget.Toast.makeText(this, "تعذر الاتصال؛ تم عرض آخر محتوى محفوظ", android.widget.Toast.LENGTH_SHORT).show();
			return;
		}
		if (page.findViewWithTag("cigram_home_retry_box") != null) return;
		
		final float d = getResources().getDisplayMetrics().density;
		final android.widget.LinearLayout box = new android.widget.LinearLayout(this);
		box.setTag("cigram_home_retry_box");
		box.setOrientation(android.widget.LinearLayout.VERTICAL);
		box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
		box.setPadding((int) (16 * d), (int) (28 * d), (int) (16 * d), (int) (20 * d));
		
		final android.widget.TextView status = new android.widget.TextView(this);
		status.setText("تعذر تحميل الأفلام والمسلسلات. تحقق من الاتصال ثم أعد المحاولة.");
		status.setTextSize(15);
		status.setTextColor(0xFFFFFFFF);
		status.setGravity(android.view.Gravity.CENTER);
		status.setTypeface(CigramTypefaces.get(getAssets(), CigramTypefaces.APP_FONT));
		box.addView(status, new android.widget.LinearLayout.LayoutParams(-1, -2));
		
		android.widget.TextView retry = new android.widget.TextView(this);
		retry.setText("أعد المحاولة");
		retry.setTextSize(15);
		retry.setTextColor(0xFFFFFFFF);
		retry.setGravity(android.view.Gravity.CENTER);
		retry.setTypeface(CigramTypefaces.get(getAssets(), CigramTypefaces.APP_FONT));
		android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
		background.setColor(0xFFE21B14);
		background.setCornerRadius(24f * d);
		retry.setBackground(background);
		android.widget.LinearLayout.LayoutParams retryParams = new android.widget.LinearLayout.LayoutParams((int) (170 * d), (int) (46 * d));
		retryParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
		retryParams.topMargin = (int) (16 * d);
		box.addView(retry, retryParams);
		retry.setOnClickListener(new android.view.View.OnClickListener() {
			@Override
			public void onClick(android.view.View view) {
				view.setEnabled(false);
				status.setText("جارٍ إعادة تحميل المحتوى…");
				req_movies.startRequestNetwork(RequestNetworkController.GET, "https://cigram-admin-api.wwq-mixtv.workers.dev/movies", "movies", _req_movies_request_listener);
			}
		});
		page.addView(box, new android.widget.LinearLayout.LayoutParams(-1, -2));
	}
	
	/**
	 * The catalogue download starts when the process starts (CigramStage1InitProvider). Starting a
	 * second identical download here would split the user's bandwidth, so this one only runs when the
	 * early download is not running / failed (checked for up to 3 s).
	 */
	private void cgStartCatalogRequest() {
		final Runnable own = new Runnable() {
			@Override public void run() {
				if (isFinishing() || isDestroyed()) return;
				req_movies.startRequestNetwork(RequestNetworkController.GET, "https://cigram-admin-api.wwq-mixtv.workers.dev/movies", "movies", _req_movies_request_listener);
			}
		};
		// 18.3: a catalogue confirmed within the last minutes is already on its way to the screen from the saved copy; a second
		// 5 MB download here (state 0 only means "the early download finished more than 2 minutes ago") is not needed.
		if (CigramStage1InitProvider.catalogSnapshotFresh(getApplicationContext())) return;
		if (CigramStage1InitProvider.earlyCatalogState() == 0) { own.run(); return; }
		final android.os.Handler cgHandler = new android.os.Handler(android.os.Looper.getMainLooper());
		final long cgDeadline = android.os.SystemClock.uptimeMillis() + 3000L;
		cgHandler.post(new Runnable() {
			@Override public void run() {
				if (isFinishing() || isDestroyed()) return;
				int state = CigramStage1InitProvider.earlyCatalogState();
				if (state == 2) return;   // delivered by the early download (CigramStage1InitProvider)
				if (state == 1 && android.os.SystemClock.uptimeMillis() < cgDeadline) {
					cgHandler.postDelayed(this, 150L);
					return;
				}
				own.run();
			}
		});
	}
	
	private void initializeLogic() {
		cgStartCatalogRequest();
		binding.textview4.setTypeface(CigramTypefaces.get(getAssets(),CigramTypefaces.APP_FONT), 0);
		/* إعداد السحب العمودي للصفحة */
		android.widget.ScrollView mainScroll =
		(android.widget.ScrollView)
		findViewById(R.id.vscroll1);
		
		mainScroll.setVerticalScrollBarEnabled(false);
		mainScroll.setHorizontalScrollBarEnabled(false);
		
		mainScroll.setOverScrollMode(
		android.view.View.OVER_SCROLL_NEVER
		);
		
		mainScroll.setSmoothScrollingEnabled(true);
		
		// While a finger is on Home (or it is scrolling) the background section builders yield the main thread.
		mainScroll.getViewTreeObserver().addOnScrollChangedListener(
		new android.view.ViewTreeObserver.OnScrollChangedListener() {
			@Override public void onScrollChanged() { CigramStage1InitProvider.markHomeInteracting(); }
		});
		mainScroll.setOnTouchListener(new android.view.View.OnTouchListener() {
			@Override public boolean onTouch(android.view.View v, android.view.MotionEvent e) {
				int a = e.getActionMasked();
				if (a == android.view.MotionEvent.ACTION_DOWN || a == android.view.MotionEvent.ACTION_MOVE) {
					CigramStage1InitProvider.markHomeInteracting();
				}
				return false;   // never consume: the ScrollView scrolls as normal
			}
		});
		
		/* منع ظهور الخلفية البيضاء أثناء تحميل البيانات */
		final int appBackground =
		android.graphics.Color.parseColor("#0E0E10");
		
		getWindow().setBackgroundDrawable(
		new android.graphics.drawable.ColorDrawable(
		appBackground
		)
		);
		
		mainScroll.setBackgroundColor(appBackground);
		
		android.widget.LinearLayout pageContent =
		(android.widget.LinearLayout)
		findViewById(R.id.linear1);
		
		pageContent.setBackgroundColor(appBackground);
		/*
 * ضع هذا الكود داخل بلوكة add source directly جديدة في:
 * MainActivity > Event > onCreate
 * ويجب أن تكون آخر بلوكة، بعد كود الشريط السفلي.
 *
 * عند عدم وجود إنترنت تظهر شاشة فارغة برسالة وزر إعادة المحاولة.
 * بعد الضغط على الزر يستمر الفحص بصمت كل 1500ms، وعند رجوع الإنترنت
 * يعاد إنشاء MainActivity فيعمل بلوك req_movies الموجود تلقائياً ويحمل البيانات.
 */
		{
			final android.app.Activity offlineActivity = MainActivity.this;
			final android.view.ViewGroup offlineRoot =
			(android.view.ViewGroup) findViewById(android.R.id.content);
			final float offlineDensity = getResources().getDisplayMetrics().density;
			
			class InternetState {
				boolean isAvailable() {
					try {
						android.net.ConnectivityManager manager =
						(android.net.ConnectivityManager) getSystemService(
						android.content.Context.CONNECTIVITY_SERVICE);
						
						if (manager == null) return true; // can't tell -> don't block the home screen

						if (android.os.Build.VERSION.SDK_INT >= 23) {
							android.net.Network network = manager.getActiveNetwork();
							if (network == null) return false;

							android.net.NetworkCapabilities capabilities =
							manager.getNetworkCapabilities(network);

							// Require only NET_CAPABILITY_INTERNET, NOT NET_CAPABILITY_VALIDATED.
							// VALIDATED depends on Android's probe to Google reachability endpoints,
							// which is often blocked/slow even when the Cloudflare catalog API is
							// reachable; requiring it showed the offline screen over a working home.
							return capabilities != null
							&& capabilities.hasCapability(
							android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
						}

						android.net.NetworkInfo information =
						manager.getActiveNetworkInfo();

						return information != null && information.isConnected();
					} catch (Exception ignoredInternetError) {
						return true; // on any error, don't block the home screen
					}
				}
			}
			
			final InternetState internetState = new InternetState();
			
			// 18.4 offline-first: a blocking "no internet" screen is for a phone that has NOTHING to show. With a saved catalogue Home opens
			// at once from it (the small "browsing offline" banner is shown by CigramStage1InitProvider instead).
			if (!internetState.isAvailable()
			&& offlineRoot.findViewWithTag("cigram_offline_screen") == null
			&& !CigramCatalogPrefs.of(MainActivity.this).hasCatalogue()) {
				
				final android.widget.FrameLayout offlineScreen =
				new android.widget.FrameLayout(offlineActivity);
				
				offlineScreen.setTag("cigram_offline_screen");
				offlineScreen.setBackgroundColor(0xFF0E0E10);
				offlineScreen.setClickable(true);
				offlineScreen.setFocusable(true);
				offlineScreen.setElevation(1000 * offlineDensity);
				
				final android.widget.LinearLayout offlineBox =
				new android.widget.LinearLayout(offlineActivity);
				
				offlineBox.setOrientation(android.widget.LinearLayout.VERTICAL);
				offlineBox.setGravity(android.view.Gravity.CENTER);
				offlineBox.setPadding(
				(int)(28 * offlineDensity),
				(int)(20 * offlineDensity),
				(int)(28 * offlineDensity),
				(int)(20 * offlineDensity)
				);
				
				android.widget.FrameLayout.LayoutParams offlineBoxParams =
				new android.widget.FrameLayout.LayoutParams(-1, -2);
				
				offlineBoxParams.gravity = android.view.Gravity.CENTER;
				offlineScreen.addView(offlineBox, offlineBoxParams);
				
				final android.widget.TextView offlineTitle =
				new android.widget.TextView(offlineActivity);
				
				offlineTitle.setText("خطأ في التحميل");
				offlineTitle.setTextColor(android.graphics.Color.WHITE);
				offlineTitle.setTextSize(22);
				offlineTitle.setGravity(android.view.Gravity.CENTER);
				offlineTitle.setTypeface(CigramTypefaces.bold());
				
				offlineBox.addView(
				offlineTitle,
				new android.widget.LinearLayout.LayoutParams(-1, -2)
				);
				
				final android.widget.TextView offlineMessage =
				new android.widget.TextView(offlineActivity);
				
				offlineMessage.setText("يرجى الاتصال بالإنترنت");
				offlineMessage.setTextColor(0xFFABB7BD);
				offlineMessage.setTextSize(16);
				offlineMessage.setGravity(android.view.Gravity.CENTER);
				
				android.widget.LinearLayout.LayoutParams offlineMessageParams =
				new android.widget.LinearLayout.LayoutParams(-1, -2);
				
				offlineMessageParams.setMargins(
				0, (int)(9 * offlineDensity), 0, (int)(24 * offlineDensity)
				);
				
				offlineBox.addView(offlineMessage, offlineMessageParams);
				
				final android.widget.TextView retryButton =
				new android.widget.TextView(offlineActivity);
				
				retryButton.setText("أعد المحاولة");
				retryButton.setTextColor(android.graphics.Color.WHITE);
				retryButton.setTextSize(16);
				retryButton.setGravity(android.view.Gravity.CENTER);
				retryButton.setTypeface(CigramTypefaces.bold());
				retryButton.setClickable(true);
				
				android.graphics.drawable.GradientDrawable retryBackground =
				new android.graphics.drawable.GradientDrawable();
				
				retryBackground.setColor(0xFFE21B14);
				retryBackground.setCornerRadius(25 * offlineDensity);
				retryButton.setBackground(retryBackground);
				
				offlineBox.addView(
				retryButton,
				new android.widget.LinearLayout.LayoutParams(
				(int)(190 * offlineDensity),
				(int)(50 * offlineDensity)
				)
				);
				
				offlineRoot.addView(
				offlineScreen,
				new android.view.ViewGroup.LayoutParams(-1, -1)
				);
				
				final android.os.Handler retryHandler =
				new android.os.Handler(android.os.Looper.getMainLooper());
				
				final boolean[] retryRunning = {false};
				final java.lang.Runnable[] retryTask = new java.lang.Runnable[1];
				
				retryTask[0] = new java.lang.Runnable() {
					@Override
					public void run() {
						if (!retryRunning[0] || !offlineScreen.isAttachedToWindow()) {
							return;
						}
						
						if (internetState.isAvailable()) {
							retryRunning[0] = false;
							retryHandler.removeCallbacks(this);
							
							offlineScreen.animate()
							.alpha(0f)
							.setDuration(160L)
							.withEndAction(new java.lang.Runnable() {
								@Override
								public void run() {
									if (offlineScreen.getParent() != null) {
										offlineRoot.removeView(offlineScreen);
									}
									
									offlineActivity.recreate();
									offlineActivity.overridePendingTransition(0, 0);
								}
							})
							.start();
						} else {
							retryHandler.postDelayed(this, 1500L);
						}
					}
				};
				
				retryButton.setOnClickListener(new android.view.View.OnClickListener() {
					@Override
					public void onClick(android.view.View view) {
						if (retryRunning[0]) return;
						
						retryRunning[0] = true;
						retryHandler.post(retryTask[0]);
					}
				});
				
				offlineScreen.addOnAttachStateChangeListener(
				new android.view.View.OnAttachStateChangeListener() {
					@Override
					public void onViewAttachedToWindow(android.view.View view) {
					}
					
					@Override
					public void onViewDetachedFromWindow(android.view.View view) {
						retryRunning[0] = false;
						retryHandler.removeCallbacks(retryTask[0]);
					}
				}
				);
			}
		}
		
		// Dependency-free image loader.  It uses Android's own APIs,
		// keeps a small memory cache, and persists downloaded images in the app cache
		// so channel preloading is available when the Channels screen is opened.
		class CigramImage {
			final android.content.Context app;
			final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
			// Use Android's shared worker pool so each Activity does not keep a
			// separate set of download threads alive.
			final java.util.concurrent.Executor workers = android.os.AsyncTask.THREAD_POOL_EXECUTOR;
			final java.util.LinkedHashMap<String,android.graphics.Bitmap> memory =
			new java.util.LinkedHashMap<String,android.graphics.Bitmap>(18,.75f,true) {
				@Override protected boolean removeEldestEntry(java.util.Map.Entry<String,android.graphics.Bitmap> entry) {
					return size()>16;
				}
			};
			CigramImage(android.content.Context value) { app=value.getApplicationContext(); }
			String clean(String raw) { return raw==null?"":raw.trim(); }
			String cacheName(String url) {
				try {
					byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(url.getBytes("UTF-8"));
					StringBuilder out=new StringBuilder();
					for(int i=0;i<bytes.length;i++)out.append(String.format(java.util.Locale.US,"%02x",bytes[i]));
					return out.toString()+".img";
				} catch(Exception ignored) { return Integer.toHexString(url.hashCode())+".img"; }
			}
			java.io.File file(String url) {
				java.io.File dir=new java.io.File(app.getCacheDir(),"cigram_image_cache");
				if(!dir.exists())dir.mkdirs();
				return new java.io.File(dir,cacheName(url));
			}
			android.graphics.Bitmap decode(java.io.File source) {
				try {
					android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();
					bounds.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(source.getAbsolutePath(),bounds);
					if(bounds.outWidth<=0||bounds.outHeight<=0)return null;
					int largest=Math.max(bounds.outWidth,bounds.outHeight),sample=1;
					while(largest/sample>960)sample*=2;
					android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();
					options.inSampleSize=sample;options.inPreferredConfig=android.graphics.Bitmap.Config.RGB_565;
					return android.graphics.BitmapFactory.decodeFile(source.getAbsolutePath(),options);
				} catch(Exception ignored) { return null; }
			}
			android.graphics.Bitmap obtain(String url) {
				synchronized(memory) { android.graphics.Bitmap cached=memory.get(url);if(cached!=null&&!cached.isRecycled())return cached; }
				java.io.File target=file(url);android.graphics.Bitmap bitmap=null;
				if(target.isFile()&&target.length()>0)bitmap=decode(target);
				if(bitmap==null&&(url.startsWith("http://")||url.startsWith("https://"))) {
					java.net.HttpURLConnection connection=null;java.io.InputStream input=null;java.io.FileOutputStream output=null;
					java.io.File temporary=new java.io.File(target.getParentFile(),target.getName()+".part");
					try {
						connection=(java.net.HttpURLConnection)new java.net.URL(url).openConnection();
						connection.setConnectTimeout(10000);connection.setReadTimeout(15000);connection.setInstanceFollowRedirects(true);
						if(connection.getResponseCode()<200||connection.getResponseCode()>=300)throw new java.io.IOException("HTTP");
						input=connection.getInputStream();output=new java.io.FileOutputStream(temporary,false);
						byte[] buffer=new byte[16384];int count;long total=0L;
						while((count=input.read(buffer))!=-1){total+=count;if(total>12582912L)throw new java.io.IOException("image too large");output.write(buffer,0,count);}
						output.flush();if(target.exists())target.delete();
						if(temporary.renameTo(target))bitmap=decode(target);else bitmap=decode(temporary);
					} catch(Exception ignored) { if(temporary.exists())temporary.delete(); }
					finally {
						try {if(input!=null)input.close();}catch(Exception ignored){}
						try {if(output!=null)output.close();}catch(Exception ignored){}
						if(connection!=null)connection.disconnect();
					}
				}
				if(bitmap==null&&url.startsWith("file://"))bitmap=decode(new java.io.File(android.net.Uri.parse(url).getPath()));
				if(bitmap!=null)synchronized(memory){memory.put(url,bitmap);}
				return bitmap;
			}
			void preload(final String raw) {
				final String url=clean(raw);if(url.isEmpty())return;
				workers.execute(new Runnable(){@Override public void run(){obtain(url);}});
			}
			void load(final String raw,final android.widget.ImageView view,final boolean crop) {
				final String url=clean(raw);if(view==null)return;
				view.setScaleType((crop||view instanceof de.hdodenhof.circleimageview.CircleImageView)?android.widget.ImageView.ScaleType.CENTER_CROP:android.widget.ImageView.ScaleType.FIT_CENTER);
				final String token="cigram-image:"+url;view.setTag(token);
				android.graphics.drawable.GradientDrawable cgPlaceholder=new android.graphics.drawable.GradientDrawable();
				cgPlaceholder.setColor(0xFF161619);cgPlaceholder.setCornerRadius(14f*app.getResources().getDisplayMetrics().density);
				view.setImageDrawable(null);view.setBackground(cgPlaceholder);
				if(url.isEmpty())return;
				android.graphics.Bitmap cached;
				synchronized(memory){cached=memory.get(url);}
				if(cached!=null&&!cached.isRecycled()){view.setImageBitmap(cached);return;}
				workers.execute(new Runnable(){@Override public void run(){
						final android.graphics.Bitmap bitmap=obtain(url);if(bitmap==null)return;
						main.post(new Runnable(){@Override public void run(){
								if(token.equals(String.valueOf(view.getTag()))){view.setImageBitmap(bitmap);}
							}});
					}});
			}
		}
		final CigramImage cgImage = new CigramImage(getApplicationContext());
		class CigramChannelCache {
			final android.content.Context app=getApplicationContext();
			final android.content.SharedPreferences prefs=app.getSharedPreferences("cigram_channels_cache_v2",0);
			final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
			final int logoWidth=Math.round(132*app.getResources().getDisplayMetrics().density);
			final int logoHeight=Math.round(92*app.getResources().getDisplayMetrics().density);
			String image(String raw) {
				return raw==null?"":raw.trim().replace("https://pub-eabe41d14a0644e5800344c99ef4d971.r2.dev",
				"https://pub-6ce52a4bbf2543ee88ad9883ae42a806.r2.dev");
			}
			void prewarm(String raw) {
				try {
					org.json.JSONArray groups=new org.json.JSONObject(raw).optJSONArray("categories");
					if(groups==null)return;
					java.util.HashSet<String> seen=new java.util.HashSet<String>();
					for(int i=0;i<groups.length();i++) {
						org.json.JSONObject group=groups.optJSONObject(i);if(group==null)continue;
						org.json.JSONArray channels=group.optJSONArray("channels");if(channels==null)continue;
						for(int n=0;n<channels.length();n++) {
							org.json.JSONObject channel=channels.optJSONObject(n);if(channel==null)continue;
							String url=image(channel.optString("image",channel.optString("poster","")));
							if(url.isEmpty()||!seen.add(url))continue;
							cgImage.preload(url);
						}
					}
				}catch(Exception ignored){}
			}
			void warm() {
				prewarm(prefs.getString("json",""));
				long now=System.currentTimeMillis();
				if(now-prefs.getLong("fetched_at",0L)<300000L||prefs.getLong("loading_until",0L)>now)return;
				prefs.edit().putLong("loading_until",now+35000L).apply();
				new Thread(new Runnable(){@Override public void run(){
						java.net.HttpURLConnection connection=null;
						try {
							connection=(java.net.HttpURLConnection)new java.net.URL("https://sigram-channels-api.wwq-mixtv.workers.dev/channels").openConnection();
							connection.setConnectTimeout(10000);connection.setReadTimeout(15000);
							connection.setRequestProperty("Accept","application/json");
							int status=connection.getResponseCode();if(status<200||status>=300)throw new java.io.IOException("HTTP "+status);
							java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream(),"UTF-8"));
							StringBuilder json=new StringBuilder();String line;
							try {while((line=reader.readLine())!=null){json.append(line);if(json.length()>4000000)throw new java.io.IOException("Catalog too large");}}
							finally {reader.close();}
							org.json.JSONObject root=new org.json.JSONObject(json.toString());
							if(!root.optBoolean("ok",true)||root.optJSONArray("categories")==null)throw new java.io.IOException("Invalid catalog");
							final String value=root.toString();
							main.post(new Runnable(){@Override public void run(){prewarm(value);}});
							prefs.edit().putString("json",value).putLong("fetched_at",System.currentTimeMillis()).putString("error","").apply();
						} catch(Exception error){prefs.edit().putString("error","تعذر تحديث القنوات؛ آخر نسخة محفوظة باقية").apply();}
						finally {if(connection!=null)connection.disconnect();prefs.edit().putLong("loading_until",0L).apply();}
					}},"cigram-channel-preload").start();
			}
		}
		final CigramChannelCache cgChannelCache=new CigramChannelCache();
		
		cgChannelCache.warm();
		
		// CIGRAM HOME LOADING STATE — لا نعرض الأقسام فارغة أثناء تحميل الكتالوج.
		{
			final android.widget.LinearLayout cgHomeRoot=(android.widget.LinearLayout)findViewById(R.id.linear1);
			boolean cgHasContent=CigramStage1InitProvider.homeHasSections(MainActivity.this);
			if(cgHomeRoot!=null&&!cgHasContent){
				android.view.View old=cgHomeRoot.findViewWithTag("cigram_home_loading_state");
				if(old==null){
					final float d=getResources().getDisplayMetrics().density;
					android.widget.LinearLayout box=new android.widget.LinearLayout(MainActivity.this);
					box.setTag("cigram_home_loading_state");box.setOrientation(android.widget.LinearLayout.VERTICAL);
					box.setGravity(android.view.Gravity.TOP);box.setPadding((int)(12*d),(int)(6*d),(int)(12*d),(int)(6*d));box.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
					for(int cgSkRow=0;cgSkRow<2;cgSkRow++){
						android.view.View cgSkTitle=new android.view.View(MainActivity.this);
						CigramSkeleton.apply(cgSkTitle,6f);
						android.widget.LinearLayout.LayoutParams cgSkTitleLp=new android.widget.LinearLayout.LayoutParams((int)(130*d),(int)(18*d));
						cgSkTitleLp.topMargin=(int)(14*d);cgSkTitleLp.bottomMargin=(int)(12*d);cgSkTitleLp.setMarginStart((int)(4*d));
						box.addView(cgSkTitle,cgSkTitleLp);
						android.widget.LinearLayout cgSkCards=new android.widget.LinearLayout(MainActivity.this);
						cgSkCards.setOrientation(android.widget.LinearLayout.HORIZONTAL);
						cgSkCards.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
						for(int cgSkCard=0;cgSkCard<4;cgSkCard++){
							android.view.View cgSkPoster=new android.view.View(MainActivity.this);
							CigramSkeleton.apply(cgSkPoster,14f);
							android.widget.LinearLayout.LayoutParams cgSkPosterLp=new android.widget.LinearLayout.LayoutParams((int)(110*d),(int)(165*d));
							cgSkPosterLp.setMargins((int)(4*d),0,(int)(4*d),0);
							cgSkCards.addView(cgSkPoster,cgSkPosterLp);
						}
						box.addView(cgSkCards,new android.widget.LinearLayout.LayoutParams(-1,-2));
					}
					cgHomeRoot.addView(box,Math.min(1,cgHomeRoot.getChildCount()),new android.widget.LinearLayout.LayoutParams(-1,-2));
				}
			}
		}
		// Watchdog: if Home still shows only the skeleton after a few seconds, draw the saved catalogue or say why (retry button).
		{
			final android.os.Handler cgDog = new android.os.Handler(android.os.Looper.getMainLooper());
			final Runnable cgDogRun = new Runnable() {
				@Override public void run() {
					if (isFinishing() || isDestroyed()) return;
					android.widget.LinearLayout cgRoot = (android.widget.LinearLayout) findViewById(R.id.linear1);
					if (cgRoot == null || cgRoot.findViewWithTag("cigram_home_loading_state") == null) return;
					if (CigramStage1InitProvider.homeHasSections(MainActivity.this)) return;
					cgShowCatalogFailure();
				}
			};
			cgDog.postDelayed(cgDogRun, 8000L);

			// On-screen diagnosis (no adb needed): if Home is still empty after 12 s, say why.
			final android.app.AlertDialog[] cgDiagDialog = new android.app.AlertDialog[1];
			final Runnable cgDiagRun = new Runnable() {
				@Override public void run() {
					if (!CigramStage1InitProvider.SHOW_DIAG || isFinishing() || isDestroyed()) return;
					try { if (cgDiagDialog[0] != null && cgDiagDialog[0].isShowing()) cgDiagDialog[0].dismiss(); } catch (Throwable ignored) { }
					if (CigramStage1InitProvider.homeHasSections(MainActivity.this)) return;
					int inMemory = CigramStage1InitProvider.cigramCatalogSnapshot().size();
					String cgTrace = CigramStage1InitProvider.HOME_TRACE;
					try {
						cgDiagDialog[0] = new android.app.AlertDialog.Builder(MainActivity.this)
								.setTitle("تشخيص Cigram")
								.setMessage("الكتالوج بالذاكرة = " + inMemory + " عنصر\n"
										+ CigramStage1InitProvider.LAST_DIAG + "\n\nمراحل:\n" + cgTrace)
								.setPositiveButton("حسنًا", null)
								.show();
					} catch (Throwable cgDialogError) {
						android.widget.Toast.makeText(MainActivity.this,
								cgTrace.length() > 120 ? cgTrace.substring(cgTrace.length() - 120) : cgTrace,
								android.widget.Toast.LENGTH_LONG).show();
					}
				}
			};
			cgDog.postDelayed(cgDiagRun, 12000L);
			cgDog.postDelayed(cgDiagRun, 45000L);
		}
		
		// CIGRAM: no side drawer / no visible edge scrollbars anywhere on this screen.
		try {
			final android.view.View cigramNoSideRoot =
			findViewById(android.R.id.content);
			class CigramNoSideUi {
				void apply(android.view.View view) {
					if (view == null) return;
					try {
						view.setVerticalScrollBarEnabled(false);
						view.setHorizontalScrollBarEnabled(false);
					} catch (Exception ignored) {}
					
					String className = view.getClass().getName();
					if (className != null && className.contains("DrawerLayout")) {
						try {
							java.lang.reflect.Method lockMethod =
							view.getClass().getMethod(
							"setDrawerLockMode",
							int.class
							);
							lockMethod.invoke(view, 1); // LOCK_MODE_LOCKED_CLOSED
						} catch (Exception ignored) {}
					}
					if (
					className != null
					&&
					className.contains("NavigationView")
					) {
						view.setVisibility(android.view.View.GONE);
					}
					if (view instanceof android.view.ViewGroup) {
						android.view.ViewGroup group = (android.view.ViewGroup) view;
						for (int i = 0; i < group.getChildCount(); i++) {
							apply(group.getChildAt(i));
						}
					}
				}
			}
			new CigramNoSideUi().apply(cigramNoSideRoot);
		} catch (Exception ignored) {}
		
		// CIGRAM APP UPDATE BANNER
		// Remote JSON:
		// https://pub-eabe41d14a0644e5800344c99ef4d971.r2.dev/cigram_update.json
		// Supported keys:
		// versionCode/version_code/code, versionName/version_name,
		// url/download_url/apk, message/notes/changelog.
		{
			final android.widget.LinearLayout cgUpdateHost =
			(android.widget.LinearLayout) findViewById(
			R.id.linear1
			);
			
			if (
			cgUpdateHost != null
			&& cgUpdateHost.findViewWithTag(
			"cigram_update_banner"
			) == null
			) {
				new Thread(
				new java.lang.Runnable() {
					@Override
					public void run() {
						java.net.HttpURLConnection connection =
						null;
						
						try {
							java.net.URL endpoint =
							new java.net.URL(
							"https://pub-eabe41d14a0644e5800344c99ef4d971.r2.dev/cigram_update.json"
							);
							
							connection =
							(java.net.HttpURLConnection)
							endpoint.openConnection();
							
							connection.setConnectTimeout(
							8000
							);
							connection.setReadTimeout(
							10000
							);
							connection.setUseCaches(
							false
							);
							connection.setRequestProperty(
							"Accept",
							"application/json"
							);
							
							int status =
							connection.getResponseCode();
							
							if (
							status < 200
							|| status >= 300
							) {
								return;
							}
							
							java.io.BufferedReader reader =
							new java.io.BufferedReader(
							new java.io.InputStreamReader(
							connection.getInputStream(),
							"UTF-8"
							)
							);
							
							StringBuilder response =
							new StringBuilder();
							
							try {
								String line;
								while (
								(line = reader.readLine())
								!= null
								) {
									response.append(line);
									
									if (
									response.length()
									> 65536
									) {
										throw new java.io.IOException(
										"Update payload too large"
										);
									}
								}
							} finally {
								reader.close();
							}
							
							org.json.JSONObject update =
							new org.json.JSONObject(
							response.toString()
							);
							
							long remoteCode =
							update.optLong(
							"versionCode",
							update.optLong(
							"version_code",
							update.optLong(
							"code",
							0L
							)
							)
							);
							
							android.content.pm.PackageInfo
							packageInfo =
							getPackageManager()
							.getPackageInfo(
							getPackageName(),
							0
							);
							
							long localCode =
							packageInfo.versionCode;
							
							if (
							remoteCode <= 0L
							|| remoteCode <= localCode
							) {
								return;
							}
							
							final String remoteName =
							update.optString(
							"versionName",
							update.optString(
							"version_name",
							""
							)
							).trim();
							
							String rawUrl =
							update.optString(
							"url",
							update.optString(
							"download_url",
							update.optString(
							"apk",
							""
							)
							)
							);
							
							final String downloadUrl =
							rawUrl == null
							? ""
							: rawUrl.trim();
							
							String rawMessage =
							update.optString(
							"message",
							update.optString(
							"notes",
							update.optString(
							"changelog",
							""
							)
							)
							);
							
							final String updateMessage =
							rawMessage == null
							|| rawMessage.trim().isEmpty()
							? "يتوفر إصدار أحدث من سيكرام."
							: rawMessage.trim();
							
							runOnUiThread(
							new java.lang.Runnable() {
								@Override
								public void run() {
									if (
									isFinishing()
									|| (
									android.os.Build.VERSION.SDK_INT
									>= 17
									&& isDestroyed()
									)
									|| cgUpdateHost
									.findViewWithTag(
									"cigram_update_banner"
									)
									!= null
									) {
										return;
									}
									
									final float d =
									getResources()
									.getDisplayMetrics()
									.density;
									
									final android.widget.LinearLayout
									banner =
									new android.widget.LinearLayout(
									MainActivity.this
									);
									
									banner.setTag(
									"cigram_update_banner"
									);
									banner.setOrientation(
									android.widget.LinearLayout.VERTICAL
									);
									banner.setLayoutDirection(
									android.view.View.LAYOUT_DIRECTION_RTL
									);
									banner.setPadding(
									(int) (16 * d),
									(int) (14 * d),
									(int) (16 * d),
									(int) (14 * d)
									);
									
									android.graphics.drawable.GradientDrawable
									bannerBg =
									new android.graphics.drawable
									.GradientDrawable();
									
									bannerBg.setColor(
									0xF21C1C20
									);
									bannerBg.setCornerRadius(
									20 * d
									);
									bannerBg.setStroke(
									Math.max(
									1,
									(int) d
									),
									0x33FFFFFF
									);
									banner.setBackground(
									bannerBg
									);
									banner.setElevation(
									6 * d
									);
									
									android.widget.LinearLayout
									header =
									new android.widget.LinearLayout(
									MainActivity.this
									);
									header.setOrientation(
									android.widget.LinearLayout.HORIZONTAL
									);
									header.setLayoutDirection(
									android.view.View.LAYOUT_DIRECTION_RTL
									);
									header.setGravity(
									android.view.Gravity.CENTER_VERTICAL
									);
									
									android.widget.ImageView icon =
									new android.widget.ImageView(
									MainActivity.this
									);
									icon.setImageResource(
									R.drawable.hoo
									);
									icon.setScaleType(
									android.widget.ImageView
									.ScaleType.CENTER_CROP
									);
									
									android.graphics.drawable.GradientDrawable
									iconBg =
									new android.graphics.drawable
									.GradientDrawable();
									iconBg.setColor(
									0xFF26262B
									);
									iconBg.setCornerRadius(
									12 * d
									);
									icon.setBackground(
									iconBg
									);
									icon.setClipToOutline(
									true
									);
									
									android.widget.LinearLayout.LayoutParams
									iconLp =
									new android.widget.LinearLayout
									.LayoutParams(
									(int) (46 * d),
									(int) (46 * d)
									);
									iconLp.leftMargin =
									(int) (12 * d);
									header.addView(
									icon,
									iconLp
									);
									
									android.widget.LinearLayout
									titleBox =
									new android.widget.LinearLayout(
									MainActivity.this
									);
									titleBox.setOrientation(
									android.widget.LinearLayout.VERTICAL
									);
									
									android.widget.TextView title =
									new android.widget.TextView(
									MainActivity.this
									);
									title.setText(
									remoteName.isEmpty()
									? "يتوفر تحديث جديد"
									: "يتوفر تحديث جديد - "
									+ remoteName
									);
									title.setTextColor(
									0xFFFFFFFF
									);
									title.setTextSize(
									17
									);
									title.setGravity(
									android.view.Gravity.RIGHT
									);
									title.setMaxLines(
									Integer.MAX_VALUE
									);
									title.setSingleLine(
									false
									);
									
									android.widget.TextView message =
									new android.widget.TextView(
									MainActivity.this
									);
									message.setText(
									updateMessage
									);
									message.setTextColor(
									0xCCFFFFFF
									);
									message.setTextSize(
									13
									);
									message.setGravity(
									android.view.Gravity.RIGHT
									);
									message.setPadding(
									0,
									(int) (4 * d),
									0,
									0
									);
									message.setMaxLines(
									Integer.MAX_VALUE
									);
									message.setSingleLine(
									false
									);
									
									try {
										android.graphics.Typeface font =
										CigramTypefaces.get(
										getAssets(),
										CigramTypefaces.APP_FONT
										);
										title.setTypeface(
										font,
										android.graphics.Typeface.BOLD
										);
										message.setTypeface(
										font
										);
									} catch (
									Exception ignored
									) {}
									
									titleBox.addView(
									title,
									new android.widget.LinearLayout
									.LayoutParams(
									-1,
									-2
									)
									);
									titleBox.addView(
									message,
									new android.widget.LinearLayout
									.LayoutParams(
									-1,
									-2
									)
									);
									
									header.addView(
									titleBox,
									new android.widget.LinearLayout
									.LayoutParams(
									0,
									-2,
									1f
									)
									);
									
									banner.addView(
									header,
									new android.widget.LinearLayout
									.LayoutParams(
									-1,
									-2
									)
									);
									
									android.widget.LinearLayout
									buttons =
									new android.widget.LinearLayout(
									MainActivity.this
									);
									buttons.setOrientation(
									android.widget.LinearLayout.HORIZONTAL
									);
									buttons.setLayoutDirection(
									android.view.View.LAYOUT_DIRECTION_RTL
									);
									buttons.setGravity(
									android.view.Gravity.CENTER
									);
									
									final android.widget.TextView
									updateNow =
									new android.widget.TextView(
									MainActivity.this
									);
									updateNow.setText(
									"تحديث الآن"
									);
									updateNow.setTextColor(
									0xFFFFFFFF
									);
									updateNow.setTextSize(
									15
									);
									updateNow.setGravity(
									android.view.Gravity.CENTER
									);
									
									android.graphics.drawable.GradientDrawable
									nowBg =
									new android.graphics.drawable
									.GradientDrawable();
									nowBg.setColor(
									0xFFE21B14
									);
									nowBg.setCornerRadius(
									14 * d
									);
									updateNow.setBackground(
									nowBg
									);
									
									final android.widget.TextView
									later =
									new android.widget.TextView(
									MainActivity.this
									);
									later.setText(
									"لاحقاً"
									);
									later.setTextColor(
									0xFFFFFFFF
									);
									later.setTextSize(
									15
									);
									later.setGravity(
									android.view.Gravity.CENTER
									);
									
									android.graphics.drawable.GradientDrawable
									laterBg =
									new android.graphics.drawable
									.GradientDrawable();
									laterBg.setColor(
									0xFF2D2D30
									);
									laterBg.setCornerRadius(
									14 * d
									);
									laterBg.setStroke(
									Math.max(
									1,
									(int) d
									),
									0x2AFFFFFF
									);
									later.setBackground(
									laterBg
									);
									
									try {
										android.graphics.Typeface font =
										CigramTypefaces.get(
										getAssets(),
										CigramTypefaces.APP_FONT
										);
										updateNow.setTypeface(
										font,
										android.graphics.Typeface.BOLD
										);
										later.setTypeface(
										font
										);
									} catch (
									Exception ignored
									) {}
									
									android.widget.LinearLayout.LayoutParams
									buttonLp =
									new android.widget.LinearLayout
									.LayoutParams(
									0,
									(int) (46 * d),
									1f
									);
									
									android.widget.LinearLayout.LayoutParams
									laterLp =
									new android.widget.LinearLayout
									.LayoutParams(
									0,
									(int) (46 * d),
									1f
									);
									laterLp.rightMargin =
									(int) (8 * d);
									
									buttons.addView(
									updateNow,
									buttonLp
									);
									buttons.addView(
									later,
									laterLp
									);
									
									android.widget.LinearLayout.LayoutParams
									buttonsLp =
									new android.widget.LinearLayout
									.LayoutParams(
									-1,
									-2
									);
									buttonsLp.topMargin =
									(int) (12 * d);
									
									banner.addView(
									buttons,
									buttonsLp
									);
									
									updateNow.setOnClickListener(
									new android.view.View
									.OnClickListener() {
										@Override
										public void onClick(
										android.view.View v
										) {
											if (
											downloadUrl.isEmpty()
											) {
												CigramUtil
												.showMessage(
												getApplicationContext(),
												"رابط التحديث غير متوفر"
												);
												return;
											}
											
											try {
												android.content.Intent
												open =
												new android.content
												.Intent(
												android.content
												.Intent
												.ACTION_VIEW,
												android.net.Uri
												.parse(
												downloadUrl
												)
												);
												startActivity(
												open
												);
											} catch (
											Exception error
											) {
												CigramUtil
												.showMessage(
												getApplicationContext(),
												"تعذر فتح رابط التحديث"
												);
											}
										}
									}
									);
									
									later.setOnClickListener(
									new android.view.View
									.OnClickListener() {
										@Override
										public void onClick(
										android.view.View v
										) {
											banner.animate()
											.alpha(0f)
											.translationY(
											-12 * d
											)
											.setDuration(
											160L
											)
											.withEndAction(
											new java.lang
											.Runnable() {
												@Override
												public void run() {
													android.view
													.ViewParent
													parent =
													banner
													.getParent();
													if (
													parent
													instanceof
													android.view
													.ViewGroup
													) {
														((android.view
														.ViewGroup)
														parent)
														.removeView(
														banner
														);
													}
												}
											}
											)
											.start();
										}
									}
									);
									
									android.widget.LinearLayout.LayoutParams
									bannerLp =
									new android.widget.LinearLayout
									.LayoutParams(
									-1,
									-2
									);
									bannerLp.setMargins(
									(int) (12 * d),
									(int) (10 * d),
									(int) (12 * d),
									(int) (6 * d)
									);
									
									int insertAt =
									Math.min(
									1,
									cgUpdateHost
									.getChildCount()
									);
									
									cgUpdateHost.addView(
									banner,
									insertAt,
									bannerLp
									);
									
									banner.setAlpha(
									0f
									);
									banner.setTranslationY(
									-10 * d
									);
									banner.animate()
									.alpha(1f)
									.translationY(0f)
									.setDuration(
									220L
									)
									.start();
								}
							}
							);
							
						} catch (
						Exception ignored
						) {
							// Update checks must never block app startup.
						} finally {
							if (
							connection != null
							) {
								connection.disconnect();
							}
						}
					}
				},
				"cigram-update-check"
				).start();
			}
		}
		
		// CIGRAM — Admin-driven hidden hero carousel.
		// Hidden by default. It is inserted below the header only when /carousel contains valid items.
		{
			final float cgHeroD = getResources().getDisplayMetrics().density;
			final android.widget.LinearLayout cgHeroRoot =
			(android.widget.LinearLayout) findViewById(R.id.linear1);
			
			if (cgHeroRoot != null && cgHeroRoot.findViewWithTag("cigram_admin_hero_carousel") == null) {
				final android.widget.LinearLayout cgHeroHolder =
				new android.widget.LinearLayout(MainActivity.this);
				cgHeroHolder.setTag("cigram_admin_hero_carousel");
				cgHeroHolder.setOrientation(android.widget.LinearLayout.VERTICAL);
				cgHeroHolder.setVisibility(android.view.View.GONE);
				cgHeroHolder.setBackgroundColor(0xFF0E0E10);
				cgHeroHolder.setPadding(0, 0, 0, (int)(8 * cgHeroD));
				
				final CigramCarousel.Pager cgHeroPager =
				new CigramCarousel.Pager(MainActivity.this);
				cgHeroPager.setBackgroundColor(0xFF0E0E10);
				cgHeroPager.setClipToPadding(false);
				
				final android.widget.LinearLayout cgHeroDots =
				new android.widget.LinearLayout(MainActivity.this);
				cgHeroDots.setOrientation(android.widget.LinearLayout.HORIZONTAL);
				cgHeroDots.setGravity(android.view.Gravity.CENTER);
				cgHeroDots.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);
				cgHeroDots.setPadding(0, (int)(4 * cgHeroD), 0, 0);
				
				// v4.4: height follows the screen width (~16:10 card), 200–300 dp; the pager is inset
				// so each slide is a rounded floating card and the next one shows a gap while dragging.
				// v5.0 (Stories): full-bleed card, no side inset, taller (220-340 dp).
				// v10.41 (coverflow): a card ~60% of the screen wide and a little taller than wide; the
				// pager has room above and below for the neighbours that are smaller.
				final int cgCardW = (int)(getResources().getDisplayMetrics().widthPixels * 0.60f);
				final int cgCardH = Math.min((int)(cgCardW * 1.15f), (int)(300 * cgHeroD));
				final int cgHeroH = cgCardH + (int)(16 * cgHeroD);
				cgHeroPager.setPadding(0, 0, 0, 0);
				android.widget.LinearLayout.LayoutParams cgPagerLp =
				new android.widget.LinearLayout.LayoutParams(
				android.view.ViewGroup.LayoutParams.MATCH_PARENT,
				cgHeroH
				);
				cgHeroHolder.addView(cgHeroPager, cgPagerLp);
				cgHeroHolder.addView(
				cgHeroDots,
				new android.widget.LinearLayout.LayoutParams(
				android.view.ViewGroup.LayoutParams.MATCH_PARENT,
				(int)(34 * cgHeroD)
				)
				);
				
				// Keep it physically outside the visible layout until real carousel data exists.
				final int cgInsertAt = Math.min(1, cgHeroRoot.getChildCount());
				cgHeroRoot.addView(
				cgHeroHolder,
				cgInsertAt,
				new android.widget.LinearLayout.LayoutParams(
				android.view.ViewGroup.LayoutParams.MATCH_PARENT,
				android.view.ViewGroup.LayoutParams.WRAP_CONTENT
				)
				);
				
				final java.util.ArrayList<org.json.JSONObject> cgHeroItems =
				new java.util.ArrayList<org.json.JSONObject>();
				final java.util.ArrayList<android.view.View> cgHeroPages =
				new java.util.ArrayList<android.view.View>();
				final int[] cgHeroIndex = {0};
				final java.util.concurrent.ExecutorService cgHeroImageWorkers =
				CigramPool.fixed(2, "cg-hero");
				// v5.0 (Stories): segmented progress bars at the top, filling right-to-left (RTL).
				final CigramCarousel.StoryBars cgHeroBars = new CigramCarousel.StoryBars(MainActivity.this);
				cgHeroBars.setRtl(true);
				cgHeroBars.setColors(0x59FFFFFF, 0xFFF1EFE8, 0xFFE21B14);
				// v10.41: dots under the cards (first dot on the right, like the cards)
				final CigramCarousel.Indicator cgHeroIndicator = new CigramCarousel.Indicator(MainActivity.this);
				cgHeroIndicator.setColors(0xFF1D4252, 0xFFE21B14);
				cgHeroDots.addView(cgHeroIndicator, new android.widget.LinearLayout.LayoutParams(
				android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
				cgHeroDots.setVisibility(android.view.View.VISIBLE);
				
				class CigramAdminHero {
					int px(float value) {
						return Math.max(1, Math.round(value * cgHeroD));
					}
					
					android.graphics.Typeface font() {
						try {
							return CigramTypefaces.get(
							getAssets(), CigramTypefaces.APP_FONT
							);
						} catch (Exception ignored) {
							return android.graphics.Typeface.DEFAULT;
						}
					}
					
					String text(org.json.JSONObject object, String... keys) {
						if (object == null) return "";
						for (String key : keys) {
							Object value = object.opt(key);
							if (value != null && value != org.json.JSONObject.NULL) {
								String out = String.valueOf(value).trim();
								if (!out.isEmpty()) return out;
							}
						}
						return "";
					}
					
					java.util.HashMap<String, Object> findCatalogItem(org.json.JSONObject carousel) {
						try {
							org.json.JSONObject found = CigramStage1InitProvider.cigramCatalogFind(
							text(carousel, "type").toLowerCase(java.util.Locale.ROOT),
							text(carousel, "content_id", "id", "series_id"),
							text(carousel, "title", "name"));
							if (found == null) return null;
							@SuppressWarnings("unchecked")
							java.util.HashMap<String, Object> map = (java.util.HashMap<String, Object>)
							new com.google.gson.Gson().fromJson(found.toString(), java.util.HashMap.class);
							return map;
						} catch (Exception ignored) {}
						
						return null;
					}
					
					String stringify(Object value) {
						if (value == null) return "";
						try {
							if (value instanceof java.util.Map || value instanceof java.util.List) {
								return new com.google.gson.Gson().toJson(value);
							}
							if (value instanceof org.json.JSONObject || value instanceof org.json.JSONArray) {
								return value.toString();
							}
						} catch (Exception ignored) {}
						return String.valueOf(value);
					}
					
					void openDetails(
					final org.json.JSONObject carousel,
					final android.widget.ImageView sharedHero,
					final String sourceImageUrl
					) {
						java.util.HashMap<String, Object> selected = findCatalogItem(carousel);
						if (selected == null) {
							selected = new java.util.HashMap<String, Object>();
							selected.put("id", text(carousel, "content_id", "id"));
							selected.put("series_id", text(carousel, "content_id", "series_id"));
							selected.put("type", text(carousel, "type"));
							selected.put("title", text(carousel, "title"));
							selected.put("poster", text(carousel, "source_poster", "poster"));
							selected.put("backdrop", text(carousel, "source_backdrop", "landscape_poster", "backdrop"));
						}
						CigramStage1InitProvider.openTitleDetails(
						MainActivity.this, selected, sharedHero
						);
					}
					
					java.io.File heroCacheFile(String url) {
						java.io.File dir = new java.io.File(getCacheDir(), "cigram_hero_cache");
						if (!dir.exists()) dir.mkdirs();
						String name;
						try {
							byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(url.getBytes("UTF-8"));
							StringBuilder out = new StringBuilder();
							for (byte b : bytes) out.append(String.format(java.util.Locale.US, "%02x", b & 255));
							name = out.toString() + ".img";
						} catch (Exception ignored) {
							name = Integer.toHexString(url.hashCode()) + ".img";
						}
						return new java.io.File(dir, name);
					}
					
					android.graphics.Bitmap decodeHero(java.io.File file) {
						if (file == null || !file.isFile() || file.length() <= 0) return null;
						try {
							android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
							bounds.inJustDecodeBounds = true;
							android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
							int sample = 1;
							int targetW = Math.max(720, getResources().getDisplayMetrics().widthPixels);
							int targetH = px(260);
							while (bounds.outWidth / sample > targetW * 2 || bounds.outHeight / sample > targetH * 2) sample *= 2;
							android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
							options.inSampleSize = Math.max(1, sample);
							options.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
							return android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), options);
						} catch (Exception ignored) {
							return null;
						}
					}
					
					android.graphics.Bitmap obtainHero(String raw) {
						if (raw == null) return null;
						String url = raw.trim();
						if (!(url.startsWith("https://") || url.startsWith("http://"))) return null;
						java.io.File target = heroCacheFile(url);
						android.graphics.Bitmap cached = decodeHero(target);
						if (cached != null) return cached;
						
						java.net.HttpURLConnection connection = null;
						java.io.InputStream input = null;
						java.io.FileOutputStream output = null;
						java.io.File temp = new java.io.File(target.getParentFile(), target.getName() + ".part");
						try {
							connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
							connection.setConnectTimeout(8000);
							connection.setReadTimeout(10000);
							connection.setInstanceFollowRedirects(true);
							connection.setRequestProperty("Accept", "image/*");
							connection.setRequestProperty("User-Agent", "Cigram/1.0");
							int status = connection.getResponseCode();
							if (status < 200 || status >= 300) return null;
							input = connection.getInputStream();
							output = new java.io.FileOutputStream(temp, false);
							byte[] buffer = new byte[16384];
							int count;
							long total = 0L;
							while ((count = input.read(buffer)) != -1) {
								total += count;
								if (total > 32L * 1024L * 1024L) throw new java.io.IOException("hero too large");
								output.write(buffer, 0, count);
							}
							output.flush();
							if (target.exists()) target.delete();
							if (!temp.renameTo(target)) target = temp;
							return decodeHero(target);
						} catch (Exception ignored) {
							if (temp.exists()) temp.delete();
							return null;
						} finally {
							try { if (input != null) input.close(); } catch (Exception ignored) {}
							try { if (output != null) output.close(); } catch (Exception ignored) {}
							if (connection != null) connection.disconnect();
						}
					}
					
					void loadHero(final String primary, final String backup, final android.widget.ImageView image) {
						final String token = "cigram-hero-" + System.nanoTime();
						image.setTag(token);
						image.setImageDrawable(null);
						image.setBackgroundColor(0xFF07151C);
						cgHeroImageWorkers.execute(new java.lang.Runnable() {
							@Override public void run() {
								android.graphics.Bitmap bitmap = obtainHero(primary);
								if (bitmap == null && backup != null && !backup.trim().isEmpty() && !backup.trim().equals(primary == null ? "" : primary.trim())) {
									bitmap = obtainHero(backup);
								}
								final android.graphics.Bitmap result = bitmap;
								image.post(new java.lang.Runnable() {
									@Override public void run() {
										if (!token.equals(String.valueOf(image.getTag()))) return;
										if (result != null) image.setImageBitmap(result);
									}
								});
							}
						});
					}
					
					android.view.View buildPage(final org.json.JSONObject item) {
						final android.widget.FrameLayout page =
						new android.widget.FrameLayout(MainActivity.this);
						page.setBackgroundColor(0xFF07151C);
						page.setClickable(true);
						page.setFocusable(true);
						// v10.41: rounded card
						page.setClipToOutline(true);
						page.setOutlineProvider(new android.view.ViewOutlineProvider() {
							@Override public void getOutline(android.view.View view, android.graphics.Outline outline) {
								outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(16));
							}
						});
						// v5.0 (Stories): full-bleed page, square corners.
						
						final android.widget.ImageView hero =
						new android.widget.ImageView(MainActivity.this);
						hero.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
						hero.setAdjustViewBounds(false);
						page.addView(hero, new android.widget.FrameLayout.LayoutParams(-1, -1));
						
						android.view.View shade = new android.view.View(MainActivity.this);
						android.graphics.drawable.GradientDrawable shadeBg =
						new android.graphics.drawable.GradientDrawable(
						android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
						new int[]{0x260E0E10, 0x000E0E10, 0x000E0E10, 0x990E0E10, 0xEB0E0E10}
						);
						shade.setBackground(shadeBg);
						page.addView(shade, new android.widget.FrameLayout.LayoutParams(-1, -1));
						// v4.4: soft scrim on the RIGHT (text side) so the title reads on any artwork
						android.view.View sideShade = new android.view.View(MainActivity.this);
						sideShade.setBackground(new android.graphics.drawable.GradientDrawable(
						android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT,
						new int[]{0x990E0E10, 0x330E0E10, 0x000E0E10}));
						// v10.41: no side scrim (the title sits at the bottom of a narrow card)
						
						android.widget.LinearLayout bottom =
						new android.widget.LinearLayout(MainActivity.this);
						bottom.setOrientation(android.widget.LinearLayout.HORIZONTAL);
						bottom.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_VERTICAL);
						bottom.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
						bottom.setPadding(px(12), px(10), px(12), px(12));
						
						android.widget.FrameLayout.LayoutParams bottomLp =
						new android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM);
						page.addView(bottom, bottomLp);
						
						android.widget.TextView watch = new android.widget.TextView(MainActivity.this);
						watch.setText("شاهد هَسه");     // v4.4: same label as the details button
						watch.setVisibility(android.view.View.GONE);   // v10.41: tap the middle card to open it
						watch.setTextColor(0xFFFFFFFF);
						watch.setTextSize(16);
						watch.setTypeface(font(), android.graphics.Typeface.BOLD);
						watch.setGravity(android.view.Gravity.CENTER);
						watch.setSingleLine(true);
						watch.setPadding(px(18), 0, px(18), 0);
						android.graphics.drawable.GradientDrawable watchBg = new android.graphics.drawable.GradientDrawable();
						watchBg.setColor(0xFFE21B14);
						watchBg.setCornerRadius(px(24));
						watch.setBackground(watchBg);
						android.widget.LinearLayout.LayoutParams watchLp =
						new android.widget.LinearLayout.LayoutParams(-2, px(48));
						watch.setMinWidth(px(136));
						watchLp.leftMargin = px(12);
						bottom.addView(watch, watchLp);
						
						android.widget.LinearLayout titleBox = new android.widget.LinearLayout(MainActivity.this);
						titleBox.setOrientation(android.widget.LinearLayout.VERTICAL);
						titleBox.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
						android.widget.LinearLayout.LayoutParams titleBoxLp =
						new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
						bottom.addView(titleBox, titleBoxLp);
						
						android.widget.TextView title = new android.widget.TextView(MainActivity.this);
						title.setText(text(item, "title", "name"));
						title.setTextColor(0xFFFFFFFF);
						title.setTextSize(15);
						title.setTypeface(font(), android.graphics.Typeface.BOLD);
						title.setShadowLayer(px(6), 0, px(1), 0xB3000A10);
						title.setGravity(android.view.Gravity.RIGHT);
						title.setMaxLines(2);
						title.setShadowLayer(px(6), 0, px(1), 0xB3000A10);
						title.setEllipsize(android.text.TextUtils.TruncateAt.END);
						titleBox.addView(title, new android.widget.LinearLayout.LayoutParams(-1, -2));
						
						String type = text(item, "type").equalsIgnoreCase("series") ? "مسلسل" : "فيلم";
						// v4.4: type + year + first genre when the carousel item really has them
						String cgYear = text(item, "year", "release_year");
						String cgGenre = text(item, "category", "genre");
						if (cgGenre.length() > 0) cgGenre = cgGenre.split("[,،|/]")[0].trim();
						StringBuilder cgMeta = new StringBuilder(type);
						if (cgYear.length() > 0) cgMeta.append("  •  ").append(cgYear);
						if (cgGenre.length() > 0) cgMeta.append("  •  ").append(cgGenre);
						android.widget.TextView meta = new android.widget.TextView(MainActivity.this);
						meta.setText(cgMeta.toString());
						meta.setSingleLine(true);
						meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
						meta.setShadowLayer(px(4), 0, px(1), 0x99000A10);
						meta.setTextColor(0xFFD6DEE2);
						meta.setTextSize(10.5f);
						meta.setTypeface(font());
						meta.setGravity(android.view.Gravity.RIGHT);
						meta.setPadding(0, px(3), 0, 0);
						titleBox.addView(meta, new android.widget.LinearLayout.LayoutParams(-1, -2));
						
						final String heroUrl = text(item, "landscape_poster", "hero", "poster_horizontal", "source_backdrop");
						final String backup = text(item, "source_backdrop", "source_poster", "poster");
						final String preloadPoster = text(item, "source_poster", "poster");
						
						final android.view.View.OnTouchListener warm = new android.view.View.OnTouchListener() {
							@Override public boolean onTouch(android.view.View view, android.view.MotionEvent event) {
								if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
									CigramStage1InitProvider.preloadDetailsImages(
									MainActivity.this,
									preloadPoster,
									heroUrl.isEmpty() ? backup : heroUrl
									);
								}
								return false;
							}
						};
						page.setOnTouchListener(warm);
						watch.setOnTouchListener(warm);
						
						final android.view.View.OnClickListener open = new android.view.View.OnClickListener() {
							@Override public void onClick(android.view.View view) {
								openDetails(
								item,
								hero,
								heroUrl.isEmpty() ? backup : heroUrl
								);
							}
						};
						page.setOnClickListener(new android.view.View.OnClickListener() {
							@Override public void onClick(android.view.View view) {
								int at = cgHeroPages.indexOf(page);
								if (at >= 0 && at != cgHeroIndex[0]) {
									held = false;
									show(at, true);          // a side card: bring it to the middle
								} else {
									open.onClick(view);       // the middle card: open the title
								}
							}
						});
						watch.setOnClickListener(open);
						
						// v10.41: portrait poster in the card unless the admin made a custom wide image
						final String cardUrl = item.optBoolean("custom_image", false) || preloadPoster.isEmpty()
						? heroUrl : preloadPoster;
						loadHero(cardUrl, heroUrl.isEmpty() ? backup : heroUrl, hero);
						
						android.view.View border = new android.view.View(MainActivity.this);
						android.graphics.drawable.GradientDrawable borderBg = new android.graphics.drawable.GradientDrawable();
						borderBg.setColor(0x00000000);
						borderBg.setCornerRadius(px(16));
						borderBg.setStroke(Math.max(1, px(1)), 0x1FFFFFFF);
						border.setBackground(borderBg);
						page.addView(border, new android.widget.FrameLayout.LayoutParams(-1, -1));
						
						return page;
					}
					
					// ---- v5.0 Stories: auto-advance driven by the progress bar itself ----
					boolean held = false;
					android.animation.ValueAnimator prog;
					
					void stopProgress() {
						if (prog != null) {
							prog.removeAllListeners();
							prog.removeAllUpdateListeners();
							prog.cancel();
							prog = null;
						}
					}
					
					void startProgress() {
						stopProgress();
						cgHeroBars.setFraction(0f);
						if (cgHeroPages.size() < 2) {
							cgHeroBars.setFraction(1f);
							return;
						}
						prog = android.animation.ValueAnimator.ofFloat(0f, 1f);
						prog.setDuration(5200L);
						prog.setInterpolator(new android.view.animation.LinearInterpolator());
						prog.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
							@Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
								cgHeroBars.setFraction((Float) a.getAnimatedValue());
							}
						});
						prog.addListener(new android.animation.AnimatorListenerAdapter() {
							@Override public void onAnimationEnd(android.animation.Animator a) {
								if (cgHeroPages.size() < 2) return;
								boolean visible = cgHeroHolder.getWindowToken() != null
								&& cgHeroHolder.getVisibility() == android.view.View.VISIBLE
								&& cgHeroHolder.getWindowVisibility() == android.view.View.VISIBLE;
								if (visible && !cgHeroPager.isDragging()) show(cgHeroIndex[0] + 1, true);
								else startProgress();
							}
						});
						prog.start();
						if (held) prog.pause();
					}
					
					void hold() {
						held = true;
						if (prog != null && prog.isRunning()) prog.pause();
					}
					
					void release() {
						held = false;
						if (prog != null && prog.isPaused()) prog.resume();
					}
					
					void attachBars(int size) {
						try {
							cgHeroIndicator.setCount(size);
						} catch (Exception ignored) {}
					}
					
					void show(int index, boolean animate) {
						if (cgHeroPages.isEmpty()) return;
						int size = cgHeroPages.size();
						int safe = index % size;
						if (safe < 0) safe += size;
						cgHeroIndex[0] = safe;
						cgHeroPager.flowTo(safe, animate);
						attachBars(size);
						// dots run right to left like the cards: mirror the index
						if (animate) cgHeroIndicator.animateTo(size - 1 - safe);
						else cgHeroIndicator.setCurrent(size - 1 - safe);
						startProgress();
					}
					
					/** 18.3: signature of what is on screen; an identical payload (cache, then the same answer from the server) is not rebuilt. */
					String cgRenderedSignature = "";
					
					void render(org.json.JSONObject payload) {
						// every slide once (by id, else link + image) and only when the payload really changed
						try {
							if (payload != null) {
								org.json.JSONArray rawItems = payload.optJSONArray("items");
								if (rawItems == null) rawItems = payload.optJSONArray("carousel");
								if (rawItems != null) {
									org.json.JSONArray unique = CigramSectionDedupe.dedupeCarouselItems(rawItems);
									if (unique.length() != rawItems.length()) {
										payload = new org.json.JSONObject(payload.toString());
										payload.put(payload.has("items") ? "items" : "carousel", unique);
									}
								}
							}
							String signature = payload == null ? "" : payload.toString();
							if (signature.equals(cgRenderedSignature) && !cgHeroPages.isEmpty()) return;
							cgRenderedSignature = signature;
						} catch (Exception ignored) { }
						stopProgress();
						cgHeroItems.clear();
						cgHeroPages.clear();
						cgHeroPager.removeAllViews();
						
						if (payload == null || payload.optBoolean("enabled", true) == false) {
							cgHeroHolder.setVisibility(android.view.View.GONE);
							return;
						}
						
						org.json.JSONArray items = payload.optJSONArray("items");
						if (items == null && payload.optJSONArray("carousel") != null) items = payload.optJSONArray("carousel");
						if (items == null || items.length() == 0) {
							cgHeroHolder.setVisibility(android.view.View.GONE);
							return;
						}
						
						for (int i = 0; i < items.length() && i < 10; i++) {
							org.json.JSONObject item = items.optJSONObject(i);
							if (item == null) continue;
							String hero = text(item, "landscape_poster", "hero", "poster_horizontal", "source_backdrop");
							if (!(hero.startsWith("https://") || hero.startsWith("http://"))) continue;
							cgHeroItems.add(item);
							android.view.View page = buildPage(item);
							cgHeroPages.add(page);
							cgHeroPager.addView(page, new android.widget.FrameLayout.LayoutParams(
							cgCardW, cgCardH, android.view.Gravity.CENTER));
						}
						
						if (cgHeroItems.isEmpty()) {
							cgHeroHolder.setVisibility(android.view.View.GONE);
							return;
						}
						
						cgHeroIndex[0] = 0;
						show(0, false);
						cgHeroHolder.setVisibility(android.view.View.VISIBLE);
					}
					
					String getText(String urlText) throws Exception {
						java.net.HttpURLConnection connection = null;
						java.io.InputStream input = null;
						try {
							connection = (java.net.HttpURLConnection) new java.net.URL(urlText).openConnection();
							connection.setRequestMethod("GET");
							connection.setConnectTimeout(7000);
							connection.setReadTimeout(8000);
							connection.setUseCaches(false);
							connection.setInstanceFollowRedirects(true);
							connection.setRequestProperty("Accept", "application/json");
							connection.setRequestProperty("Cache-Control", "no-cache");
							int status = connection.getResponseCode();
							if (status < 200 || status >= 300) throw new java.io.IOException("HTTP " + status);
							input = connection.getInputStream();
							java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, "UTF-8"));
							StringBuilder out = new StringBuilder();
							String line;
							while ((line = reader.readLine()) != null) out.append(line);
							return out.toString();
						} finally {
							try { if (input != null) input.close(); } catch (Exception ignored) {}
							if (connection != null) connection.disconnect();
						}
					}
					
					void loadRemote() {
						// 18.3: Home used to fetch /carousel (with a cache-busting query) on every creation; within two minutes of the
						// last successful fetch the saved slides are enough
						try {
							long fetchedAt = getSharedPreferences("cigram_carousel_cache", MODE_PRIVATE).getLong("fetched_at", 0L);
							long age = System.currentTimeMillis() - fetchedAt;
							if (fetchedAt > 0L && age >= 0L && age < 120000L) return;
						} catch (Exception ignored) { }
						new java.lang.Thread(new java.lang.Runnable() {
							@Override public void run() {
								String raw = null;
								String[] urls = {
									"https://cigram-admin-api.wwq-mixtv.workers.dev/carousel?ts=" + System.currentTimeMillis(),
									"https://pub-eabe41d14a0644e5800344c99ef4d971.r2.dev/cigram_carousel.json?ts=" + System.currentTimeMillis()
								};
								for (String url : urls) {
									try {
										raw = getText(url);
										if (raw != null && !raw.trim().isEmpty()) break;
									} catch (Exception ignored) {}
								}
								final String result = raw;
								if (result == null || result.trim().isEmpty()) return;
								try {
									final org.json.JSONObject payload;
									String trimmed = result.trim();
									if (trimmed.startsWith("[")) {
										payload = new org.json.JSONObject();
										payload.put("enabled", true);
										payload.put("items", new org.json.JSONArray(trimmed));
									} else {
										payload = new org.json.JSONObject(trimmed);
									}
									String previousJson = getSharedPreferences("cigram_carousel_cache", MODE_PRIVATE).getString("json", "");
									getSharedPreferences("cigram_carousel_cache", MODE_PRIVATE)
									.edit().putString("json", payload.toString()).putLong("fetched_at", System.currentTimeMillis()).apply();
									if (payload.toString().equals(previousJson)) return;   // unchanged: no widget refresh, no re-render
									CigramCarouselWidget.refreshAll(getApplicationContext());   // the home-screen widget shows the same slides
									MainActivity.this.runOnUiThread(new java.lang.Runnable() {
										@Override public void run() {
											if (!MainActivity.this.isFinishing()) render(payload);
										}
									});
								} catch (Exception ignored) {}
							}
						}, "cigram-carousel-loader").start();
					}
				}
				
				final CigramAdminHero cgHero = new CigramAdminHero();
				
				// Render cached data instantly, but keep the holder invisible if cache is empty/invalid.
				try {
					String cached = getSharedPreferences("cigram_carousel_cache", MODE_PRIVATE)
					.getString("json", "");
					if (cached != null && !cached.trim().isEmpty()) {
						cgHero.render(new org.json.JSONObject(cached));
					}
				} catch (Exception ignored) {
					cgHeroHolder.setVisibility(android.view.View.GONE);
				}
				
				cgHeroPager.setAdapter(new CigramCarousel.Adapter() {
					@Override public int count() { return cgHeroPages.size(); }
					@Override public int current() { return cgHeroIndex[0]; }
					@Override public android.view.View pageAt(int index) {
						return (index >= 0 && index < cgHeroPages.size()) ? cgHeroPages.get(index) : null;
					}
					@Override public void onSettle(int newIndex) { cgHero.show(newIndex, false); }
					@Override public CigramCarousel.Indicator indicator() { return null; }
				});
				
				// v5.0: press-and-hold pauses the story bar; releasing resumes it.
				cgHeroPager.setHoldListener(new CigramCarousel.HoldListener() {
					@Override public void onHold() { cgHero.hold(); }
					@Override public void onRelease() { cgHero.release(); }
				});
				
				cgHero.loadRemote();
			}
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
				tnShell.setBackgroundColor(0xFF0E0E10);
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
					: 0xFFB0B0B5);
					tnLabel.setGravity(android.view.Gravity.CENTER);
					tnLabel.setIncludeFontPadding(false);
					tnLabel.setSingleLine(true);
					
					try {
						tnLabel.setTypeface(
						CigramTypefaces.get(
						getAssets(),
						CigramTypefaces.APP_FONT));
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
				
				/*
     * Home is never finished when the user leaves it, so coming back from another tab only
     * resumes it. Before this, the bar kept the old capsule position until the window regained
     * focus and the page popped in without the fade. Reset both as soon as Home resumes.
     */
				if (tnCurrent == 3 && tnActivity.getApplication() != null) {
					final android.app.Application tnApp = tnActivity.getApplication();
					final android.app.Application.ActivityLifecycleCallbacks[] tnCallbacks =
					new android.app.Application.ActivityLifecycleCallbacks[1];
					tnCallbacks[0] = new android.app.Application.ActivityLifecycleCallbacks() {
						@Override public void onActivityResumed(android.app.Activity activity) {
							if (activity != tnActivity) return;
							tnOpening[0] = false;
							for (int i = 0; i < tnTabs.getChildCount(); i++) {
								android.view.View child = tnTabs.getChildAt(i);
								if (child != null) {
									child.setEnabled(true);
									child.setScaleX(1f);
									child.setScaleY(1f);
								}
							}
							tnIndicator.position(tnCurrent, false);
							
							android.content.SharedPreferences tnPrefs =
							tnActivity.getSharedPreferences("cigram_nav_ui", 0);
							int tnReturnFrom = tnPrefs.getInt("return_from", -1);
							if (tnReturnFrom >= 0 && tnReturnFrom < tnScreens.length) {
								tnPrefs.edit().remove("return_from").apply();
								CigramNavFx.reveal(tnOriginal);
							}
						}
						@Override public void onActivityDestroyed(android.app.Activity activity) {
							if (activity == tnActivity) {
								tnApp.unregisterActivityLifecycleCallbacks(tnCallbacks[0]);
							}
						}
						@Override public void onActivityCreated(android.app.Activity activity, android.os.Bundle state) { }
						@Override public void onActivityStarted(android.app.Activity activity) { }
						@Override public void onActivityPaused(android.app.Activity activity) { }
						@Override public void onActivityStopped(android.app.Activity activity) { }
						@Override public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle state) { }
					};
					tnApp.registerActivityLifecycleCallbacks(tnCallbacks[0]);
				}
			}
		}
		
		// CIGRAM ADS — إعلانات الشركات (تظهر فقط عند وجود إعلان معتمد، ولا تأخذ حيّزاً بدونه)
		{
			android.widget.LinearLayout cgAdRoot = (android.widget.LinearLayout) findViewById(R.id.linear1);
			if (cgAdRoot != null) {
				AdSlotView.install(MainActivity.this, cgAdRoot, "hero", Math.min(2, cgAdRoot.getChildCount()));
				AdSlotView.install(MainActivity.this, cgAdRoot, "sponsor", Math.min(3, cgAdRoot.getChildCount()));
				AdSlotView.installSticky(MainActivity.this, 64);
			}
			AdsOverlay.onAppOpen(MainActivity.this);
		}
		
	}
	
}
