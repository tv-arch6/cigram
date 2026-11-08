package com.Cigram.vid;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.content.Intent;
import android.content.res.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.*;
import android.net.*;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.text.style.*;
import android.util.*;
import android.view.*;
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

public class PlayerActivity extends AppCompatActivity {

	/** v10.80: released with the screen (the phone's orientation sensor must not stay on). */
	private CigramPlayerOrientation cgOrientationRef;

	@Override
	protected void onDestroy() {
		// Safety net: ExoPlayer.release() is idempotent, so this is harmless when onStop already released it.
		try {
			android.view.View cgRoot = findViewById(android.R.id.content);
			Object cgRaw = cgRoot == null ? null : cgRoot.getTag(0x7f0f0c61);
			if (cgRaw instanceof java.util.HashMap) {
				Object cgPlayer = ((java.util.HashMap) cgRaw).get("player");
				if (cgPlayer instanceof com.google.android.exoplayer2.ExoPlayer) {
					com.google.android.exoplayer2.ExoPlayer cgExo = (com.google.android.exoplayer2.ExoPlayer) cgPlayer;
					try { cgExo.stop(); } catch (Throwable ignored) { }
					try { cgExo.clearMediaItems(); } catch (Throwable ignored) { }
					try { cgExo.release(); } catch (Throwable ignored) { }
				}
				((java.util.HashMap) cgRaw).remove("player");
			}
		} catch (Throwable ignored) { }
		try { if (cgOrientationRef != null) cgOrientationRef.stop(); } catch (Throwable ignored) { }
		try { CigramSeekPreview.stop(); } catch (Throwable ignored) { }
		CigramSeekPreview.release(this);   // the preview card is static: it must not keep this page alive
		try { CigramSeekGuard.cancel(); } catch (Throwable ignored) { }
		try { CigramAudioBoost.release(); } catch (Throwable ignored) { }
		super.onDestroy();
	}

	
	private PlayerBinding binding;
	private String video_url = "";
	
	private Intent intent_player = new Intent();
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		// Companion player (com.cigram.player): plays the title and serves the Cigram campaigns. Falls back to this player.
		if (CigramPlayerLauncher.forward(this, getIntent())) { finish(); return; }
		binding = PlayerBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		initialize(_savedInstanceState);
		
		CigramAdManager.initialize(getApplicationContext()); // self-throttled, background thread, consent-gated
		
		initializeLogic();
	}
	
	private void initialize(Bundle _savedInstanceState) {
	}
	
	private void initializeLogic() {
		// Cigram Liquid Glass: transparent surfaces, specular light and animated controls.
		class CigramGlass {
			final android.content.Context context;
			final float density;
			final android.graphics.Typeface font;
			CigramGlass(android.content.Context value) {
				context = value;
				density = value.getResources().getDisplayMetrics().density;
				android.graphics.Typeface f = android.graphics.Typeface.DEFAULT;
				try { f = CigramTypefaces.get(value.getAssets(), CigramTypefaces.APP_FONT); }
				catch (Exception ignored) {}
				font = f;
			}
			int px(float n) { return Math.round(n * density); }
			class Skin extends android.graphics.drawable.Drawable {
				final float radius;
				final boolean outlined;
				final boolean green;
				final android.graphics.Paint paint = new android.graphics.Paint(3);
				int opacity = 255;
				Skin(float r, boolean o, boolean g) { radius = px(r); outlined = o; green = g; }
				@Override public void draw(android.graphics.Canvas canvas) {
					android.graphics.Rect b = getBounds();
					android.graphics.RectF rect = new android.graphics.RectF(b);
					rect.inset(density / 2f, density / 2f);
					int saved = canvas.saveLayerAlpha(rect, opacity);
					android.graphics.Path clip = new android.graphics.Path();
					clip.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW);
					canvas.clipPath(clip);
					paint.setStyle(android.graphics.Paint.Style.FILL);
					paint.setShader(null);
					paint.setColor(green ? 0x6639DB91 : 0xB0111113);
					canvas.drawRoundRect(rect, radius, radius, paint);
					paint.setShader(new android.graphics.LinearGradient(0, rect.top, rect.width(), rect.bottom,
					new int[]{0x34FFFFFF, 0x09FFFFFF, 0x00111113, 0x16FFFFFF},
					new float[]{0f, 0.28f, 0.65f, 1f}, android.graphics.Shader.TileMode.CLAMP));
					canvas.drawRoundRect(rect, radius, radius, paint);
					paint.setShader(null);
					paint.setColor(green ? 0x344AEDA1 : 0x0CFFFFFF);
					android.graphics.Path wave = new android.graphics.Path();
					wave.moveTo(rect.left, rect.top);
					wave.lineTo(rect.right, rect.top);
					wave.lineTo(rect.right, rect.top + rect.height() * 0.26f);
					wave.cubicTo(rect.right * 0.6f, rect.top + rect.height() * 0.11f,
					rect.left + rect.width() * 0.25f, rect.top + rect.height() * 0.58f,
					rect.left, rect.top + rect.height() * 0.32f);
					wave.close();
					canvas.drawPath(wave, paint);
					if (outlined) {
						paint.setStyle(android.graphics.Paint.Style.STROKE);
						paint.setStrokeWidth(density);
						paint.setShader(new android.graphics.LinearGradient(0, rect.top, 0, rect.bottom,
						new int[]{0x90FFFFFF, 0x15FFFFFF, 0x42FFFFFF}, null,
						android.graphics.Shader.TileMode.CLAMP));
						canvas.drawRoundRect(rect, radius, radius, paint);
					}
					paint.setShader(null);
					paint.setStyle(android.graphics.Paint.Style.FILL);
					canvas.restoreToCount(saved);
				}
				@Override public void setAlpha(int a) { opacity = a; invalidateSelf(); }
				@Override public void setColorFilter(android.graphics.ColorFilter filter) {}
				@Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
				@Override public void getOutline(android.graphics.Outline outline) {
					outline.setRoundRect(getBounds(), radius);
				}
			}
			android.graphics.drawable.Drawable surface(float radius, boolean outlined) {
				return new Skin(radius, outlined, false);
			}
			void bind(android.view.View view, float radius, boolean outlined) {
				if (view == null) return;
				view.setBackgroundTintList(null);
				view.setBackground(surface(radius, outlined));
				view.setElevation(0f);
				view.setStateListAnimator(null);
				view.setClipToOutline(true);
			}
			void press(final android.view.View view) {
				view.setOnTouchListener(new android.view.View.OnTouchListener() {
					@Override public boolean onTouch(android.view.View v, android.view.MotionEvent e) {
						boolean down = e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN;
						if (down || e.getActionMasked() == android.view.MotionEvent.ACTION_UP
						|| e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
							v.animate().scaleX(1f).scaleY(1f)
							.setDuration(down ? 100L : 190L).start();
						}
						return false;
					}
				});
			}
			android.widget.TextView label(String text, float size) {
				android.widget.TextView v = new android.widget.TextView(context);
				v.setText(text); v.setTextSize(size); v.setTextColor(0xFFFFFFFF);
				v.setTypeface(font); v.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
				v.setTextDirection(android.view.View.TEXT_DIRECTION_FIRST_STRONG);
				return v;
			}
			class Check extends android.graphics.drawable.Drawable {
				final boolean checked;
				final android.graphics.Paint paint = new android.graphics.Paint(3);
				Check(boolean on) { checked = on; }
				@Override public void draw(android.graphics.Canvas c) {
					android.graphics.Rect b = getBounds();
					float cx=b.exactCenterX(), cy=b.exactCenterY(), r=Math.min(b.width(),b.height())*.40f;
					paint.setStyle(android.graphics.Paint.Style.FILL);
					paint.setShader(new android.graphics.LinearGradient(cx-r,cy-r,cx+r,cy+r,
					checked ? new int[]{0xDC7FFBC0,0x7039DB91,0xCB159B65} : new int[]{0x16FFFFFF,0x04FFFFFF},
					null,android.graphics.Shader.TileMode.CLAMP));
					c.drawCircle(cx,cy,r,paint);
					paint.setShader(null); paint.setStyle(android.graphics.Paint.Style.STROKE);
					paint.setStrokeWidth(Math.max(1f,density)); paint.setColor(checked?0x995DF2A5:0x55FFFFFF);
					c.drawCircle(cx,cy,r,paint);
					if (checked) {
						paint.setColor(0xFFFFFFFF); paint.setStrokeWidth(density*1.8f);
						paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
						android.graphics.Path tick=new android.graphics.Path();
						tick.moveTo(cx-r*.45f,cy); tick.lineTo(cx-r*.1f,cy+r*.3f); tick.lineTo(cx+r*.48f,cy-r*.32f);
						c.drawPath(tick,paint);
					}
				}
				@Override public void setAlpha(int a) { paint.setAlpha(a); }
				@Override public void setColorFilter(android.graphics.ColorFilter f) { paint.setColorFilter(f); }
				@Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
			}
			void state(android.widget.TextView view, boolean on, String description) {
				view.setText(""); view.setCompoundDrawablePadding(0);
				Check icon=new Check(on); icon.setBounds(0,0,px(28),px(28));
				view.setCompoundDrawables(null,null,icon,null);
				view.setGravity(android.view.Gravity.CENTER);
				view.setContentDescription(description + (on ? "، مفعّل" : "، غير مفعّل"));
			}
			class Popup extends android.widget.PopupWindow {
				boolean closing;
				Popup(android.view.View body,int width,int height) { super(body,width,height,true); }
				void finishClose() { super.dismiss(); }
				@Override public void dismiss() {
					if (closing || !isShowing()) return;
					closing=true;
					getContentView().animate().cancel();
					getContentView().animate().alpha(0f).translationY(px(28)).setDuration(180L)
					.withEndAction(new java.lang.Runnable(){ @Override public void run(){ finishClose(); }}).start();
				}
			}
			Popup popup(android.view.View body,android.view.View anchor,int width,int height,int gravity) {
				final Popup popup=new Popup(body,width,height);
				popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
				popup.setOutsideTouchable(true); popup.setFocusable(true); popup.setElevation(px(12));
				popup.setAnimationStyle(0);
				body.setAlpha(0f); body.setTranslationY(px(28));
				popup.showAtLocation(anchor,gravity,0,0); CigramTvFocus.attachWindowOf(body); // v10.66: TV remote inside the panel (TV only)
				body.animate().alpha(1f).translationY(0f).setDuration(260L)
				.setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
				return popup;
			}
			Popup choose(android.view.View anchor,String title,final String[] options,int chosen,
			final android.content.DialogInterface.OnClickListener action) {
				final android.widget.LinearLayout panel=new android.widget.LinearLayout(context);
				panel.setOrientation(1); panel.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
				panel.setPadding(px(16),px(14),px(16),px(18)); bind(panel,24,true);
				android.widget.TextView heading=label(title,19); heading.setPadding(px(8),0,px(8),px(12));
				panel.addView(heading,new android.widget.LinearLayout.LayoutParams(-1,-2));
				android.widget.ScrollView scroll=new android.widget.ScrollView(context);
				scroll.setVerticalScrollBarEnabled(false);
				android.widget.LinearLayout list=new android.widget.LinearLayout(context); list.setOrientation(1);
				scroll.addView(list,new android.widget.ScrollView.LayoutParams(-1,-2));
				panel.addView(scroll,new android.widget.LinearLayout.LayoutParams(-1,0,1f));
				final Popup[] holder=new Popup[1];
				for(int i=0;i<options.length;i++) {
					final int index=i;
					android.widget.LinearLayout row=new android.widget.LinearLayout(context);
					row.setGravity(android.view.Gravity.CENTER_VERTICAL); row.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
					row.setPadding(px(12),0,px(12),0); bind(row,17,i==chosen); press(row);
					android.widget.TextView name=label(options[i],16);
					row.addView(name,new android.widget.LinearLayout.LayoutParams(0,px(50),1f));
					android.widget.TextView check=label("",16); state(check,i==chosen,options[i]);
					row.addView(check,new android.widget.LinearLayout.LayoutParams(px(32),px(50)));
					android.widget.LinearLayout.LayoutParams lp=new android.widget.LinearLayout.LayoutParams(-1,px(50));
					lp.bottomMargin=px(7); list.addView(row,lp);
					row.setOnClickListener(new android.view.View.OnClickListener(){
						@Override public void onClick(android.view.View v){ action.onClick(null,index); holder[0].dismiss(); }
					});
				}
				if (android.os.Build.VERSION.SDK_INT >= 28 && anchor != null) {
					try {
						android.view.WindowInsets wi = anchor.getRootWindowInsets();
						android.view.DisplayCutout cutout = wi == null ? null : wi.getDisplayCutout();
						if (cutout != null) {
							panel.setPadding(
							px(16) + Math.max(0, cutout.getSafeInsetLeft()),
							px(14),
							px(16) + Math.max(0, cutout.getSafeInsetRight()),
							px(18)
							);
						}
					} catch (Throwable ignored) {}
				}
				int height=Math.min(context.getResources().getDisplayMetrics().heightPixels*3/4,px(84+options.length*57));
				holder[0]=popup(panel,anchor,-1,height,android.view.Gravity.BOTTOM);
				return holder[0];
			}
		}
		// This source can be emitted inside Sketchware network listeners, where `this`
		// is the listener itself rather than the Activity.  The application context is
		// available from both scopes.
		final CigramGlass cgGlass = new CigramGlass(getApplicationContext());
		// Dependency-free image loader for Sketchware.  It uses Android's own APIs,
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
		// CIGRAM PlayerActivity > onCreate > add source directly
		// Fixed build v8 SAFE: adds per-account Continue Watching metadata while keeping all v7.1 player fixes.
		// Subtitle show/hide is handled by SubtitleView, while quality still uses DefaultTrackSelector.
		
		CigramStage1InitProvider.applyPlayerEdgeToEdge(PlayerActivity.this);
		// v10.80: follows the phone (landscape or portrait) when auto-rotate is on; landscape otherwise
		final CigramPlayerOrientation cgOrientation = new CigramPlayerOrientation(PlayerActivity.this, null);
		cgOrientationRef = cgOrientation;
		cgOrientation.start();
		try {
			final android.view.View cgPlayerDecor = getWindow().getDecorView();
			if (cgPlayerDecor != null) {
				cgPlayerDecor.postDelayed(new java.lang.Runnable() {
					@Override public void run() {
						CigramStage1InitProvider.applyPlayerEdgeToEdge(PlayerActivity.this);
					}
				}, 140L);
			}
		} catch (Throwable ignored) {}
		
		final float dp = getResources().getDisplayMetrics().density;
		
		// =====================================================
		// INTENT DATA
		// =====================================================
		
		String videoValue = getIntent().getStringExtra("video");
		if (videoValue == null || videoValue.trim().equals("")) {
			videoValue = getIntent().getStringExtra("movie_url");
		}
		if (videoValue == null || videoValue.trim().equals("")) {
			videoValue = getIntent().getStringExtra("link");
		}
		
		if (videoValue == null || videoValue.trim().equals("")) {
			try {
				java.io.File urlFile = new java.io.File(getFilesDir(), "video_url.txt");
				if (urlFile.exists()) {
					java.io.BufferedReader reader =
					new java.io.BufferedReader(new java.io.FileReader(urlFile));
					StringBuilder savedUrl = new StringBuilder();
					String line;
					while ((line = reader.readLine()) != null) {
						savedUrl.append(line);
					}
					reader.close();
					videoValue = savedUrl.toString();
				}
			} catch (Exception ignored) {}
		}
		
		String subtitleValue = getIntent().getStringExtra("subtitle");
		if (subtitleValue == null || subtitleValue.trim().equals("")) {
			subtitleValue = getIntent().getStringExtra("subtitle_url");
		}
		
		String idValue = getIntent().getStringExtra("id");
		String titleValue = getIntent().getStringExtra("title");
		String typeValue = getIntent().getStringExtra("type");
		String posterValue = getIntent().getStringExtra("poster");
		String backdropValue = getIntent().getStringExtra("backdrop");
		String seriesTitleValue = getIntent().getStringExtra("series_title");
		String seasonValue = getIntent().getStringExtra("season");
		String episodeValue = getIntent().getStringExtra("episode");
		String episodesJsonValue = getIntent().getStringExtra("series_episodes_json");
		String skipValue = getIntent().getStringExtra("skip_segments");
		
		if (videoValue == null) videoValue = "";
		
		// vidbolt.pro / other embed sites: NO separate screen. The one app player (this screen, all its
		// buttons) is built for every title; MediaLoader.load() turns an embed link into the real
		// stream (mp4 / HLS) before ExoPlayer gets it - for the movie and for every episode.
		if (subtitleValue == null) subtitleValue = "";
		if (idValue == null) idValue = "";
		if (titleValue == null) titleValue = "";
		if (typeValue == null) typeValue = "";
		if (posterValue == null) posterValue = "";
		if (backdropValue == null) backdropValue = "";
		if (seriesTitleValue == null || seriesTitleValue.trim().equals("")) {
			seriesTitleValue = titleValue;
		}
		if (seasonValue == null) seasonValue = "";
		if (episodeValue == null) episodeValue = "";
		if (episodesJsonValue == null) episodesJsonValue = "";
		if (skipValue == null) skipValue = "";
		
		final String mediaIdentity =
		!idValue.trim().equals("")
		? idValue.trim()
		: (!seriesTitleValue.trim().equals("")
		? seriesTitleValue.trim()
		: (!titleValue.trim().equals("")
		? titleValue.trim()
		: videoValue.trim()));
		
		final String seriesTitle =
		seriesTitleValue.trim();
		
		final String episodesJson =
		episodesJsonValue.trim();
		
		final String mediaId =
		idValue.trim();
		
		final String mediaTitle =
		titleValue.trim();
		
		final String mediaType =
		typeValue.trim();
		
		final String mediaPoster =
		posterValue.trim();
		
		final String mediaBackdrop =
		backdropValue.trim();
		
		final String[] currentSeason = { seasonValue.trim() };
		final String[] currentEpisode = { episodeValue.trim() };
		final String[] currentVideo = { videoValue.trim() };
		// The link the title was OPENED with (an embed page such as vidbolt, or the file itself). History / continue-watching must
		// store THIS, never the resolved stream: a resolved vidbolt stream is a short-lived tokenised URL that no longer works
		// when «تابع المشاهدة» opens it again.
		final String[] sourceVideo = { videoValue.trim() };
		final boolean[] fromResolver = { false };
		final String[] currentSubtitle = { subtitleValue.trim() };
		
		final boolean isSeries =
		"series".equalsIgnoreCase(typeValue.trim())
		|| !currentSeason[0].equals("")
		|| !currentEpisode[0].equals("")
		|| !episodesJson.equals("");
		
		// =====================================================
		// ROOT + PLAYER VIEW
		// =====================================================
		
		final android.widget.FrameLayout playerHost =
		(android.widget.FrameLayout) findViewById(android.R.id.content);
		
		playerHost.removeAllViews();
		playerHost.setPadding(0, 0, 0, 0);
		playerHost.setFitsSystemWindows(false);
		playerHost.setBackgroundColor(android.graphics.Color.BLACK);
		
		final com.google.android.exoplayer2.ui.PlayerView exoView =
		new com.google.android.exoplayer2.ui.PlayerView(this);
		
		exoView.setBackgroundColor(android.graphics.Color.BLACK);
		exoView.setPadding(0, 0, 0, 0);
		exoView.setFitsSystemWindows(false);
		exoView.setUseController(false);
		exoView.setKeepScreenOn(true);
		exoView.setShowBuffering(
		com.google.android.exoplayer2.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING
		);
		exoView.setResizeMode(
		com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
		);
		
		final android.widget.FrameLayout.LayoutParams cgVideoLp =
		new android.widget.FrameLayout.LayoutParams(-1, -1);
		cgVideoLp.setMargins(0, 0, 0, 0);
		playerHost.addView(exoView, cgVideoLp);
		
		// =====================================================
		// EXOPLAYER + BUFFER
		// =====================================================
		
		final com.google.android.exoplayer2.trackselection.DefaultTrackSelector trackSelector =
		new com.google.android.exoplayer2.trackselection.DefaultTrackSelector(this);
		try {
			// Sound: choose an audio track this phone can really decode (AAC / MP3 / Opus first, AC-3 and
			// E-AC-3 only when nothing else is offered), Arabic then English. The video is never held back.
			trackSelector.setParameters(trackSelector.buildUponParameters()
			.setPreferredAudioMimeTypes(
			com.google.android.exoplayer2.util.MimeTypes.AUDIO_AAC,
			com.google.android.exoplayer2.util.MimeTypes.AUDIO_MPEG,
			com.google.android.exoplayer2.util.MimeTypes.AUDIO_OPUS,
			com.google.android.exoplayer2.util.MimeTypes.AUDIO_VORBIS,
			com.google.android.exoplayer2.util.MimeTypes.AUDIO_E_AC3,
			com.google.android.exoplayer2.util.MimeTypes.AUDIO_AC3)
			.setPreferredAudioLanguages("ar", "en")
			.setAllowAudioMixedMimeTypeAdaptiveness(true)
			.setConstrainAudioChannelCountToDeviceCapabilities(true));
		} catch (Throwable ignored) { }
		try {
			if (!CigramPlus.isPlus(PlayerActivity.this)) {
				// Cigram Plus: 1080p-4K is a Plus service. Without Plus the automatic choice never goes above 720p
				// (only titles that really offer several qualities can be capped; a single-quality file plays as it is).
				trackSelector.setParameters(trackSelector.buildUponParameters()
				.setMaxVideoSize(Integer.MAX_VALUE, CigramPlus.FREE_MAX_HEIGHT));
			}
		} catch (Throwable ignored) { }
		
		final CigramBufferPolicy cgBuffer = CigramBufferPolicy.of(this);   // smaller buffers on weak phones (no out-of-memory crashes)
		final com.google.android.exoplayer2.DefaultLoadControl loadControl =
		new com.google.android.exoplayer2.DefaultLoadControl.Builder()
		.setBufferDurationsMs(
		cgBuffer.minMs, // min
		cgBuffer.maxMs, // max
		700,   // playback starts after 0.7 s of video (was 1.5 s)
		1000   // after a seek / stall: 1 s (was 2.5 s)
		)
		.setBackBuffer(cgBuffer.backMs, true)   // going back is instant (a minute on normal phones)
		.setPrioritizeTimeOverSizeThresholds(true)
		.build();
		
		final com.google.android.exoplayer2.ExoPlayer exoPlayer =
		new com.google.android.exoplayer2.ExoPlayer.Builder(this,
		new com.google.android.exoplayer2.DefaultRenderersFactory(this)
		.setEnableDecoderFallback(true))   // try the next decoder when the first one cannot start (audio / video)
		.setTrackSelector(trackSelector)
		.setLoadControl(loadControl)
		.setSeekBackIncrementMs(10000L)
		.setSeekForwardIncrementMs(10000L)
		.setMediaSourceFactory(CigramStreams.sourceFactory(this))
		.build();
		
		exoPlayer.setHandleAudioBecomingNoisy(true);
		// Seeking: jump to the nearest key frame instead of decoding every frame up to the exact second,
		// and keep the last picture on screen (no black shutter) while the new part loads.
		try { exoPlayer.setSeekParameters(com.google.android.exoplayer2.SeekParameters.CLOSEST_SYNC); } catch (Throwable ignored) { }
		try {
			exoView.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT);
			exoView.setKeepContentOnPlayerReset(true);
		} catch (Throwable ignored) { }
		// CIGRAM: تذكّر سرعة العرض وفترة التمرير وجودة الفيديو بين المرات.
		final android.content.SharedPreferences cgPlayerPrefs =
		getSharedPreferences("cigram_player_prefs", MODE_PRIVATE);
		try {
			float cgSavedSpeed = cgPlayerPrefs.getFloat("speed", 1f);
			if (cgSavedSpeed >= 0.5f && cgSavedSpeed <= 2f) {
				exoPlayer.setPlaybackSpeed(cgSavedSpeed);
			}
		} catch (Throwable ignored) {
		}
		
		/*
 * مهم:
 * لا نربط PlayerView بالـ ExoPlayer قبل setMediaItem.
 * عندك الانهيار يحدث داخل ListenerSet أثناء تغيير الـ Timeline.
 * لذلك نجهز الميديا أولاً بدون أي PlayerView listener، وبعد READY نربط الواجهة.
 */
		exoView.setPlayer(null);
		
		final int[] mediaLoadGeneration = { 0 };
		final boolean[] endedHandled = { false };
		// طلب انتقال واحد فقط، ويمكن إلغاؤه عند اختيار حلقة يدوياً.
		final java.lang.Runnable[] pendingAutoNext = { null };
		
		// =====================================================
		// SUBTITLE FONT
		// IMPORTANT: NO setTrackSelectionParameters HERE
		// =====================================================
		
		final android.content.SharedPreferences subtitlePrefs =
		getSharedPreferences("subtitle_settings", MODE_PRIVATE);
		
		final String selectedSubtitleFont =
		subtitlePrefs.getString("subtitle_font", CigramFonts.DEFAULT_KEY);
		
		final String selectedSubtitleFontPath = CigramFonts.assetPathForKey(selectedSubtitleFont);
		
		android.graphics.Typeface loadedSubtitleTypeface = CigramTypefaces.app(getAssets());
		
		try {
			loadedSubtitleTypeface =
			CigramTypefaces.get(
			getAssets(),
			selectedSubtitleFontPath
			);
		} catch (RuntimeException error) {
			android.util.Log.w("CigramPlayer", "subtitle font " + selectedSubtitleFontPath + " unavailable, using the app font", error);
		}
		
		try { loadedSubtitleTypeface = CigramFonts.subtitleTypeface(PlayerActivity.this, loadedSubtitleTypeface); } catch (Throwable ignored) {}
		final android.graphics.Typeface subtitleTypeface = loadedSubtitleTypeface;
		
		final com.google.android.exoplayer2.ui.SubtitleView subtitleView =
		exoView.getSubtitleView();
		
		// 18.2: CigramSubtitleSyncEngine (created below, once the subtitle view sits in its final place)
		final CigramSubtitleSyncEngine[] cgSubEngine = { null };
		
		final java.lang.Runnable applySubtitleStyle =
		new java.lang.Runnable() {
			@Override
			public void run() {
				if (subtitleView == null) return;
				
				String backgroundMode =
				subtitlePrefs.getString("subtitle_background", "black");
				
				int backgroundColor =
				CigramFonts.subtitleBackgroundColor(PlayerActivity.this, backgroundMode);
				
				subtitleView.setApplyEmbeddedStyles(false);
				subtitleView.setApplyEmbeddedFontSizes(false);
				
				com.google.android.exoplayer2.ui.CaptionStyleCompat style =
				new com.google.android.exoplayer2.ui.CaptionStyleCompat(
				CigramFonts.subtitleTextColor(PlayerActivity.this),
				backgroundColor,
				android.graphics.Color.TRANSPARENT,
				com.google.android.exoplayer2.ui.CaptionStyleCompat.EDGE_TYPE_NONE,
				android.graphics.Color.BLACK,
				subtitleTypeface
				);
				
				subtitleView.setStyle(style);
				// v10.68: «حجم الترجمة» من إعدادات المشغّل / صفحة الإعدادات
				float cgSubSize = com.google.android.exoplayer2.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION;
				try {
					float cgSubScale = subtitlePrefs.getFloat("subtitle_scale", 1f);
					if (cgSubScale < 0.5f || cgSubScale > 2f) cgSubScale = 1f;
					cgSubSize = com.google.android.exoplayer2.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * cgSubScale;
					subtitleView.setFractionalTextSize(cgSubSize);
				} catch (Throwable ignored) {}
				// 18.2: the engine's overlay gets exactly the same look (same bottom position as below: 0.12)
				if (cgSubEngine[0] != null) cgSubEngine[0].applyStyle(style, cgSubSize, 0.12f);
			}
		};
		
		applySubtitleStyle.run();
		
		// =====================================================
		// CIGRAM: SUBTITLE FIXED POSITION
		// الترجمة تُرسم فوق الشاشة نفسها وليس داخل إطار الفيديو، حتى يبقى مستواها
		// ثابتاً مهما كان «أبعاد الشاشة» (متناسق / ملء الشاشة / تكبير).
		// لتغيير الارتفاع عدّل cgSubtitleBottomFraction (0.08 = الارتفاع القديم).
		// =====================================================
		final float cgSubtitleBottomFraction = 0.12f;
		
		try {
			if (subtitleView != null) {
				android.view.ViewParent cgSubtitleOldParent = subtitleView.getParent();
				
				if (
				cgSubtitleOldParent instanceof android.view.ViewGroup
				&& cgSubtitleOldParent != exoView
				) {
					((android.view.ViewGroup) cgSubtitleOldParent).removeView(subtitleView);
					exoView.addView(
					subtitleView,
					new android.widget.FrameLayout.LayoutParams(-1, -1)
					);
				}
				
				subtitleView.setBottomPaddingFraction(cgSubtitleBottomFraction);
			}
		} catch (Throwable ignored) {}
		
		// =====================================================
		// 18.2 CIGRAM SUBTITLE SYNC ENGINE
		// The subtitle file is parsed by CigramSubtitleHub (correct milliseconds, SRT and VTT, Arabic encodings) and
		// drawn over ExoPlayer's own subtitle view, with the viewer's delay applied once at display time.
		// =====================================================
		try {
			cgSubEngine[0] = CigramSubtitleSyncEngine.attach(PlayerActivity.this, exoPlayer, subtitleView);
			if (cgSubEngine[0] != null) {
				applySubtitleStyle.run();
				cgSubEngine[0].setListener(new CigramSubtitleSyncEngine.Listener() {
					@Override public void onLoaded(com.Cigram.subhub.SubDocument doc) { }
					@Override public void onFailed(String message) {
						if (isFinishing() || isDestroyed()) return;
						android.widget.Toast.makeText(PlayerActivity.this, message, android.widget.Toast.LENGTH_SHORT).show();
					}
				});
			}
		} catch (Throwable cgSubError) {
			android.util.Log.w("CigramPlayer", "subtitle engine unavailable, using ExoPlayer decoders", cgSubError);
			cgSubEngine[0] = null;
		}
		
		final android.os.Handler uiHandler =
		new android.os.Handler(android.os.Looper.getMainLooper());
		// audio boost: (re)attach to the player's audio session (it changes when a stream is prepared) - every 2 s, cheap
		uiHandler.post(new java.lang.Runnable() {
			@Override public void run() {
				if (isFinishing() || isDestroyed()) return;
				try { CigramAudioBoost.sync(PlayerActivity.this, exoPlayer.getAudioSessionId()); } catch (Throwable ignored) { }
				uiHandler.postDelayed(this, 2000L);
			}
		});
		
		// =====================================================
		// WATCH PROGRESS
		// =====================================================
		
		final android.content.SharedPreferences watchProgress =
		getSharedPreferences("watch_progress", android.content.Context.MODE_PRIVATE);
		
		final String[] watchProgressKey = { "" };
		final long[] lastProgressSaveTime = { 0L };
		
		class ProgressKeyFactory {
			String make() {
				if (isSeries) {
					return "series_" + Integer.toHexString(
					(
					mediaIdentity
					+ "|s" + currentSeason[0]
					+ "|e" + currentEpisode[0]
					).hashCode()
					);
				}
				
				return "movie_" + Integer.toHexString(mediaIdentity.hashCode());
			}
		}
		
		final ProgressKeyFactory progressKeyFactory = new ProgressKeyFactory();
		
		
		// =====================================================
		// CONTINUE WATCHING - PER LOGGED-IN ACCOUNT
		// =====================================================
		
		final android.content.SharedPreferences continueAuth =
		getSharedPreferences(
		"auth_session",
		MODE_PRIVATE
		);
		
		final android.content.SharedPreferences continuePrefs =
		getSharedPreferences(
		"continue_watching",
		MODE_PRIVATE
		);
		
		final boolean continueLoggedIn =
		continueAuth.getBoolean(
		"logged_in",
		false
		);
		
		final String continueToken =
		continueAuth.getString(
		"token",
		""
		);
		
		// موضع المشاهدة مفصول لكل حساب. مفاتيح قائمة "أكمل المشاهدة" تبقى كما هي.
		final String progressOwner = continueLoggedIn
		&& continueToken != null && !continueToken.trim().isEmpty()
		? "account_" + CigramUserData.accountHash(getApplicationContext())
		: "guest";
		class ScopedProgressKey {
			String make() { return progressOwner + "|" + progressKeyFactory.make(); }
		}
		final ScopedProgressKey scopedProgressKey = new ScopedProgressKey();
		watchProgressKey[0] = scopedProgressKey.make();
		
		final String continueItemsKey =
		"items_"
		+ CigramUserData.accountHash(getApplicationContext());
		
		
		class ContinueWatchingStore {
			
			boolean enabled() {
				
				return
				continueLoggedIn
				&&
				continueToken != null
				&&
				!continueToken.trim().equals("");
			}
			
			
			String currentItemKey() {
				
				return
				progressKeyFactory.make();
			}
			
			long savedPositionForCurrent() {
				if (!enabled()) return 0L;
				org.json.JSONArray items = readItems();
				String key = currentItemKey();
				for (int i = 0; i < items.length(); i++) {
					org.json.JSONObject item = items.optJSONObject(i);
					if (item != null && key.equals(item.optString("key", ""))) {
						long position = item.optLong("position", 0L);
						long duration = item.optLong("duration", 0L);
						return position >= 5000L && (duration <= 0L || duration - position > 15000L)
						? position : 0L;
					}
				}
				return 0L;
			}
			
			
			org.json.JSONArray readItems() {
				
				try {
					
					String raw =
					continuePrefs.getString(
					continueItemsKey,
					"[]"
					);
					
					
					if (
					raw == null
					||
					raw.trim().equals("")
					) {
						
						return new org.json.JSONArray();
					}
					
					
					return new org.json.JSONArray(
					raw
					);
					
				} catch (Exception ignored) {
					
					return new org.json.JSONArray();
				}
			}
			
			
			void writeItems(
			org.json.JSONArray items
			) {
				
				if (
				!enabled()
				||
				items == null
				) {
					return;
				}
				
				
				continuePrefs.edit()
				.putString(
				continueItemsKey,
				items.toString()
				)
				.apply();
			}
			
			
			void removeCurrent() {
				
				history(0L, 0L, true);
				
				if (
				!enabled()
				) {
					return;
				}
				
				
				String wantedKey =
				currentItemKey();
				
				
				org.json.JSONArray oldItems =
				readItems();
				
				
				org.json.JSONArray newItems =
				new org.json.JSONArray();
				
				
				for (
				int i = 0;
				i < oldItems.length();
				i++
				) {
					
					org.json.JSONObject item =
					oldItems.optJSONObject(
					i
					);
					
					
					if (
					item == null
					) {
						continue;
					}
					
					
					if (
					wantedKey.equals(
					item.optString(
					"key",
					""
					)
					)
					) {
						continue;
					}
					
					
					newItems.put(
					item
					);
				}
				
				
				writeItems(
				newItems
				);
			}
			
			
			void history(
			long position,
			long duration,
			boolean finished
			) {
				try {
					org.json.JSONObject cgInfo = new org.json.JSONObject();
					cgInfo.put("type", isSeries ? "series" : "movie");
					cgInfo.put("id", mediaId);
					cgInfo.put("title", mediaTitle);
					cgInfo.put("series_title", seriesTitle);
					cgInfo.put("series_id", getIntent().getStringExtra("series_id"));
					cgInfo.put("poster", mediaPoster);
					cgInfo.put("backdrop", mediaBackdrop);
					cgInfo.put("video", CigramQuality.originOf(sourceVideo[0].length() > 0 ? sourceVideo[0] : currentVideo[0]));
					cgInfo.put("season", currentSeason[0]);
					cgInfo.put("episode", currentEpisode[0]);
					CigramUserData.record(
					getApplicationContext(),
					cgInfo,
					position,
					duration,
					finished
					);
				} catch (Throwable ignored) {
				}
			}
			
			
			void save(
			long position,
			long duration
			) {
				
				if (
				position >= 5000L
				&&
				currentVideo[0] != null
				&&
				!currentVideo[0].trim().equals("")
				&&
				!(
				duration > 0L
				&&
				duration != com.google.android.exoplayer2.C.TIME_UNSET
				&&
				duration - position <= 15000L
				)
				) {
					history(position, duration, false);
				}
				
				if (
				!enabled()
				||
				position < 5000L
				||
				currentVideo[0] == null
				||
				currentVideo[0].trim().equals("")
				) {
					return;
				}
				
				
				if (
				duration > 0L
				&&
				duration
				!=
				com.google.android.exoplayer2.C.TIME_UNSET
				&&
				duration - position <= 15000L
				) {
					
					removeCurrent();
					
					return;
				}
				
				
				try {
					
					final String wantedKey =
					currentItemKey();
					
					
					org.json.JSONObject current =
					new org.json.JSONObject();
					
					
					current.put(
					"key",
					wantedKey
					);
					
					current.put(
					"id",
					mediaId
					);
					
					current.put(
					"title",
					mediaTitle
					);
					
					current.put(
					"series_title",
					seriesTitle
					);
					
					current.put(
					"type",
					isSeries
					? "series"
					: (
					mediaType.equals("")
					? "movie"
					: mediaType
					)
					);
					
					current.put(
					"poster",
					mediaPoster
					);
					
					current.put(
					"backdrop",
					mediaBackdrop
					);
					
					current.put(
					"video",
					CigramQuality.originOf(sourceVideo[0].length() > 0 ? sourceVideo[0] : currentVideo[0])
					);
					
					current.put(
					"subtitle",
					currentSubtitle[0]
					);
					
					current.put(
					"season",
					currentSeason[0]
					);
					
					current.put(
					"episode",
					currentEpisode[0]
					);
					
					current.put(
					"series_episodes_json",
					episodesJson
					);
					
					current.put(
					"position",
					position
					);
					
					current.put(
					"duration",
					duration
					);
					
					current.put(
					"updated_at",
					System.currentTimeMillis()
					);
					
					
					org.json.JSONArray oldItems =
					readItems();
					
					
					org.json.JSONArray newItems =
					new org.json.JSONArray();
					
					
					newItems.put(
					current
					);
					
					
					for (
					int i = 0;
					i < oldItems.length();
					i++
					) {
						
						org.json.JSONObject item =
						oldItems.optJSONObject(
						i
						);
						
						
						if (
						item == null
						) {
							continue;
						}
						
						
						if (
						wantedKey.equals(
						item.optString(
						"key",
						""
						)
						)
						) {
							continue;
						}
						
						
						newItems.put(
						item
						);
						
						
						if (
						newItems.length() >= 100
						) {
							break;
						}
					}
					
					
					writeItems(
					newItems
					);
					
				} catch (Exception ignored) {
				}
			}
		}
		
		
		final ContinueWatchingStore continueWatchingStore =
		new ContinueWatchingStore();
		
		// =====================================================
		// SKIP SEGMENTS
		// =====================================================
		
		final boolean[] skipScenesEnabled = { false };
		final java.util.ArrayList<long[]> skipSegments = new java.util.ArrayList<>();
		
		class SkipParser {
			void parse(String data) {
				skipSegments.clear();
				
				if (data == null || data.trim().equals("")) return;
				
				try {
					String[] ranges = data.split(",");
					for (String range : ranges) {
						String[] points = range.trim().split("-");
						if (points.length == 2) {
							long startMs =
							Long.parseLong(points[0].trim()) * 1000L;
							
							long endMs =
							Long.parseLong(points[1].trim()) * 1000L;
							
							if (endMs > startMs) {
								skipSegments.add(
								new long[] { startMs, endMs }
								);
							}
						}
					}
				} catch (Exception ignored) {}
			}
		}
		
		final SkipParser skipParser = new SkipParser();
		skipParser.parse(skipValue);
		
		// =====================================================
		// SERIES EPISODES PARSER
		// Supports flat array + {"episodes":[]} + seasons with episodes[]
		// =====================================================
		
		class EpisodeData {
			String poster = "";
			String season = "";
			String episode = "";
			String title = "";
			String video = "";
			String subtitle = "";
			String skip = "";
		}
		
		final java.util.ArrayList<EpisodeData> episodeItems =
		new java.util.ArrayList<>();
		
		class EpisodeParser {
			void addEpisode(org.json.JSONObject obj, String defaultSeason) {
				if (obj == null) return;
				
				EpisodeData item = new EpisodeData();
				item.poster = obj.optString("episode_poster", obj.optString("backdrop", obj.optString("poster", "")));
				
				item.season = obj.optString("season", defaultSeason);
				item.episode = obj.optString("episode", "");
				item.title = obj.optString("title", "");
				
				item.video = obj.optString("video", "");
				if (item.video.trim().equals("")) {
					item.video = obj.optString("link", "");
				}
				
				item.subtitle = obj.optString("subtitle", "");
				item.skip = obj.optString("skip_segments", "");
				
				if (
				!item.video.trim().equals("")
				|| !item.episode.trim().equals("")
				) {
					episodeItems.add(item);
				}
			}
			
			void parseArray(org.json.JSONArray array, String defaultSeason) {
				if (array == null) return;
				
				for (int i = 0; i < array.length(); i++) {
					Object value = array.opt(i);
					
					if (value instanceof org.json.JSONObject) {
						org.json.JSONObject obj = (org.json.JSONObject) value;
						
						org.json.JSONArray nested = obj.optJSONArray("episodes");
						
						if (nested != null) {
							String season =
							obj.optString(
							"season",
							obj.optString("number", defaultSeason)
							);
							
							parseArray(nested, season);
						} else {
							addEpisode(obj, defaultSeason);
						}
					}
				}
			}
			
			void parse(String json) {
				if (json == null || json.trim().equals("")) return;
				
				try {
					Object root =
					new org.json.JSONTokener(json).nextValue();
					
					if (root instanceof org.json.JSONArray) {
						parseArray((org.json.JSONArray) root, "");
						
					} else if (root instanceof org.json.JSONObject) {
						org.json.JSONObject object =
						(org.json.JSONObject) root;
						
						org.json.JSONArray direct =
						object.optJSONArray("episodes");
						
						if (direct != null) {
							parseArray(direct, object.optString("season", ""));
							return;
						}
						
						org.json.JSONArray seasons =
						object.optJSONArray("seasons");
						
						if (seasons != null) {
							parseArray(seasons, "");
						}
					}
				} catch (Exception ignored) {}
			}
		}
		
		// The Details screen may hand over only part of the series (one season, or a resumed item):
		// complete it with every season/episode the catalogue knows for the same series.
		new EpisodeParser().parse(
		CigramStage1InitProvider.completeSeriesEpisodes(
		PlayerActivity.this, mediaId, seriesTitle, episodesJson));
		
		// إذا كانت أرقام كل الحلقات واضحة، نرتبها زمنياً حتى "التالي" يعني الحلقة التالية فعلاً.
		// عند وجود رقم ناقص أو غير قابل للقراءة نحتفظ بترتيب المصدر كما هو.
		boolean episodeNumbersComplete = !episodeItems.isEmpty();
		for (EpisodeData entry : episodeItems) {
			String seasonDigits = entry.season == null ? "" : entry.season.replaceAll("[^0-9]", "");
			String episodeDigits = entry.episode == null ? "" : entry.episode.replaceAll("[^0-9]", "");
			if (seasonDigits.isEmpty() || episodeDigits.isEmpty()) {
				episodeNumbersComplete = false;
				break;
			}
			try {
				Long.parseLong(seasonDigits);
				Long.parseLong(episodeDigits);
			} catch (NumberFormatException error) {
				episodeNumbersComplete = false;
				break;
			}
		}
		if (episodeNumbersComplete) {
			java.util.Collections.sort(episodeItems, new java.util.Comparator<EpisodeData>() {
				long number(String value) {
					return Long.parseLong(value.replaceAll("[^0-9]", ""));
				}
				@Override public int compare(EpisodeData first, EpisodeData second) {
					int seasonOrder = Long.compare(number(first.season), number(second.season));
					return seasonOrder != 0 ? seasonOrder
					: Long.compare(number(first.episode), number(second.episode));
				}
			});
		}
		
		final int[] currentEpisodeIndex = { -1 };
		
		if (isSeries && !episodeItems.isEmpty()) {
			
			for (int i = 0; i < episodeItems.size(); i++) {
				EpisodeData item = episodeItems.get(i);
				
				if (
				item.season.equals(currentSeason[0])
				&&
				item.episode.equals(currentEpisode[0])
				) {
					currentEpisodeIndex[0] = i;
					break;
				}
			}
			
			if (currentEpisodeIndex[0] == -1 && !currentVideo[0].equals("")) {
				for (int i = 0; i < episodeItems.size(); i++) {
					if (currentVideo[0].equals(episodeItems.get(i).video)) {
						currentEpisodeIndex[0] = i;
						break;
					}
				}
			}
			
			if (currentEpisodeIndex[0] == -1) {
				currentEpisodeIndex[0] = 0;
			}
			
			EpisodeData first =
			episodeItems.get(currentEpisodeIndex[0]);
			
			if (currentSeason[0].equals("")) {
				currentSeason[0] = first.season;
			}
			
			if (currentEpisode[0].equals("")) {
				currentEpisode[0] = first.episode;
			}
			
			if (currentVideo[0].equals("")) {
				currentVideo[0] = first.video;
			}
			
			if (currentSubtitle[0].equals("")) {
				currentSubtitle[0] = first.subtitle;
			}
			
			if (skipSegments.isEmpty()) {
				skipParser.parse(first.skip);
			}
			
			watchProgressKey[0] =
			scopedProgressKey.make();
		}
		
		// =====================================================
		// TOP SERIES INFO - LEFT
		// =====================================================
		
		final android.widget.LinearLayout seriesInfo =
		new android.widget.LinearLayout(this);
		
		seriesInfo.setOrientation(android.widget.LinearLayout.VERTICAL);
		seriesInfo.setGravity(android.view.Gravity.LEFT);
		seriesInfo.setBackgroundColor(android.graphics.Color.TRANSPARENT);
		
		final android.widget.TextView seriesTitleView =
		new android.widget.TextView(this);
		
		seriesTitleView.setText(seriesTitle);
		// v6.0 design 5: series name small and dim, episode line bold and white
		seriesTitleView.setTextColor(0xFFA9B4BA);
		seriesTitleView.setTextSize(14);
		seriesTitleView.setTypeface(CigramTypefaces.bold());
		seriesTitleView.setSingleLine(true);
		seriesTitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
		seriesTitleView.setGravity(android.view.Gravity.LEFT);
		
		final android.widget.TextView episodeInfoView =
		new android.widget.TextView(this);
		
		episodeInfoView.setTextColor(android.graphics.Color.WHITE);
		episodeInfoView.setTextSize(19);
		episodeInfoView.setGravity(android.view.Gravity.LEFT);
		
		class SeriesHeaderUpdater {
			void update() {
				String info = "";
				
				if (!currentSeason[0].equals("")) {
					info = "الموسم " + currentSeason[0];
				}
				
				if (!currentEpisode[0].equals("")) {
					if (!info.equals("")) info += " | ";
					info += "الحلقة " + currentEpisode[0];
				}
				
				episodeInfoView.setText(info);
			}
		}
		
		final SeriesHeaderUpdater headerUpdater =
		new SeriesHeaderUpdater();
		
		headerUpdater.update();
		
		try {
			android.graphics.Typeface playerFont =
			CigramTypefaces.get(
			getAssets(),
			CigramTypefaces.APP_FONT
			);
			
			seriesTitleView.setTypeface(playerFont);
			
			episodeInfoView.setTypeface(
			playerFont,
			android.graphics.Typeface.BOLD
			);
			
		} catch (Exception ignored) {}
		
		seriesInfo.addView(
		seriesTitleView,
		new android.widget.LinearLayout.LayoutParams(-1, -2)
		);
		
		android.widget.LinearLayout.LayoutParams episodeInfoLp =
		new android.widget.LinearLayout.LayoutParams(-1, -2);
		
		episodeInfoLp.topMargin = (int) (2f * dp);
		
		seriesInfo.addView(episodeInfoView, episodeInfoLp);
		
		android.widget.FrameLayout.LayoutParams seriesInfoLp =
		new android.widget.FrameLayout.LayoutParams(
		(int) (getResources().getDisplayMetrics().widthPixels * 0.58f),
		-2
		);
		
		seriesInfoLp.gravity =
		android.view.Gravity.TOP | android.view.Gravity.LEFT;
		
		seriesInfoLp.setMargins(
		(int) (22f * dp),
		(int) (18f * dp),
		0,
		0
		);
		
		playerHost.addView(seriesInfo, seriesInfoLp);
		
		seriesInfo.setVisibility(
		isSeries
		? android.view.View.VISIBLE
		: android.view.View.GONE
		);
		
		// =====================================================
		// CENTER CONTROLS - CINEMANA STYLE
		// =====================================================
		
		final android.widget.LinearLayout centerControls =
		new android.widget.LinearLayout(this);
		
		centerControls.setOrientation(
		android.widget.LinearLayout.HORIZONTAL
		);
		
		centerControls.setGravity(
		android.view.Gravity.CENTER
		);
		
		centerControls.setBackgroundColor(
		android.graphics.Color.TRANSPARENT
		);
		
		
		// -----------------------------------------------------
		// أزرار مشغل Cigram بأسلوب سينمانا
		// - زر Play/Pause أحمر واضح بالمنتصف
		// - Previous/Next أصغر
		// - Rewind/Forward بأيقونة 10 ثوانٍ مخصصة
		// - بدون أي ملفات صور إضافية
		// -----------------------------------------------------
		
		class PlayerCenterButtonStyle {
			// v10.68 «أزرار جديدة»: زر التشغيل مربّع بحواف دائرية بتدرّج أحمر، أزرار التقديم والترجيع
			// زجاجية تعرض فترة التمرير الحالية (5 / 10 / 15 / 30)، والسابق / التالي بلا خلفية.
			static final int ICON_PREVIOUS = 0;
			static final int ICON_REWIND_10 = 1;
			static final int ICON_PLAY = 2;
			static final int ICON_PAUSE = 3;
			static final int ICON_FORWARD_10 = 4;
			static final int ICON_NEXT = 5;
			
			class ControlIcon extends android.graphics.drawable.Drawable {
				final int type;
				final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
				final android.graphics.Path path = new android.graphics.Path();
				ControlIcon(int iconType) { type = iconType; }
				
				@Override public void draw(android.graphics.Canvas canvas) {
					android.graphics.Rect b = getBounds();
					float cx = b.exactCenterX(), cy = b.exactCenterY();
					float u = Math.min(b.width(), b.height());
					android.graphics.ColorFilter keep = paint.getColorFilter();
					int alpha = paint.getAlpha();
					paint.reset();
					paint.setAntiAlias(true);
					paint.setColorFilter(keep);
					paint.setColor(android.graphics.Color.WHITE);
					paint.setAlpha(alpha);
					paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
					paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
					path.reset();
					if (type == ICON_PLAY) {
						// rounded triangle, shifted right so it looks centred
						paint.setStyle(android.graphics.Paint.Style.FILL);
						paint.setPathEffect(new android.graphics.CornerPathEffect(u * 0.08f));
						float w = u * 0.40f, h = u * 0.46f, x = cx - w * 0.40f;
						path.moveTo(x, cy - h / 2f);
						path.lineTo(x + w, cy);
						path.lineTo(x, cy + h / 2f);
						path.close();
						canvas.drawPath(path, paint);
						return;
					}
					if (type == ICON_PAUSE) {
						paint.setStyle(android.graphics.Paint.Style.FILL);
						float bw = u * 0.13f, bh = u * 0.44f, gap = u * 0.075f, r = bw * 0.45f;
						canvas.drawRoundRect(new android.graphics.RectF(cx - gap - bw, cy - bh / 2f, cx - gap, cy + bh / 2f), r, r, paint);
						canvas.drawRoundRect(new android.graphics.RectF(cx + gap, cy - bh / 2f, cx + gap + bw, cy + bh / 2f), r, r, paint);
						return;
					}
					if (type == ICON_PREVIOUS || type == ICON_NEXT) {
						boolean next = type == ICON_NEXT;
						float dir = next ? 1f : -1f;
						paint.setStyle(android.graphics.Paint.Style.FILL);
						paint.setPathEffect(new android.graphics.CornerPathEffect(u * 0.05f));
						float w = u * 0.34f, h = u * 0.44f;
						float tip = cx + dir * w * 0.42f, back = cx - dir * w * 0.58f;
						path.moveTo(back, cy - h / 2f);
						path.lineTo(tip, cy);
						path.lineTo(back, cy + h / 2f);
						path.close();
						canvas.drawPath(path, paint);
						paint.setPathEffect(null);
						paint.setStyle(android.graphics.Paint.Style.STROKE);
						paint.setStrokeWidth(u * 0.085f);
						float bar = tip + dir * u * 0.07f;
						canvas.drawLine(bar, cy - h / 2f, bar, cy + h / 2f, paint);
						return;
					}
					if (type == ICON_REWIND_10 || type == ICON_FORWARD_10) {
						boolean forward = type == ICON_FORWARD_10;
						paint.setStyle(android.graphics.Paint.Style.STROKE);
						paint.setStrokeWidth(u * 0.06f);
						float r = u * 0.36f;
						android.graphics.RectF arc = new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r);
						// open circle with a gap at the top; the arrow head sits in the gap and points
						// right (forward, clockwise) or left (rewind, counter-clockwise)
						canvas.drawArc(arc, -60f, 300f, false, paint);
						paint.setStyle(android.graphics.Paint.Style.FILL);
						float a = u * 0.13f, d = forward ? 1f : -1f, hy = cy - r;
						path.moveTo(cx + d * a * 0.9f, hy);
						path.lineTo(cx - d * a * 0.5f, hy - a * 0.85f);
						path.lineTo(cx - d * a * 0.5f, hy + a * 0.85f);
						path.close();
						canvas.drawPath(path, paint);
						long step = cgPlayerPrefs.getLong("seek_step", 10000L);
						if (step != 5000L && step != 10000L && step != 15000L && step != 25000L && step != 30000L) step = 10000L;
						paint.setTextAlign(android.graphics.Paint.Align.CENTER);
						paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
						paint.setTextSize(u * 0.27f);
						android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
						canvas.drawText(String.valueOf(step / 1000L), cx, cy - (fm.ascent + fm.descent) / 2f + u * 0.02f, paint);
					}
				}
				@Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
				@Override public void setColorFilter(android.graphics.ColorFilter colorFilter) { paint.setColorFilter(colorFilter); invalidateSelf(); }
				@Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
			}
			
			/** main = red rounded square; glass = round glass disc; plain = ripple only. */
			android.graphics.drawable.Drawable makeBackground(boolean mainButton) {
				return makeBackground(mainButton ? 2 : 1);
			}
			
			android.graphics.drawable.Drawable makeBackground(int kind) {
				android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
				android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
				mask.setColor(android.graphics.Color.WHITE);
				if (kind == 2) {
					shape.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
					shape.setCornerRadius(26f * dp);
					shape.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
					shape.setColors(new int[]{0xFFFF3B2F, 0xFFE21B14, 0xFFB0100C});
					shape.setStroke(Math.max(1, (int) (1f * dp)), 0x40FFFFFF);
					mask.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
					mask.setCornerRadius(26f * dp);
				} else if (kind == 1) {
					shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
					shape.setColor(0x4D000000);
					shape.setStroke(Math.max(1, (int) (1.2f * dp)), 0x33FFFFFF);
					mask.setShape(android.graphics.drawable.GradientDrawable.OVAL);
				} else {
					shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
					shape.setColor(0x00000000);
					mask.setShape(android.graphics.drawable.GradientDrawable.OVAL);
				}
				return new android.graphics.drawable.RippleDrawable(
				android.content.res.ColorStateList.valueOf(kind == 2 ? 0x55FFFFFF : 0x33FFFFFF), shape, mask);
			}
			
			void apply(android.widget.ImageButton button, int iconType, boolean mainButton) {
				boolean plain = iconType == ICON_PREVIOUS || iconType == ICON_NEXT;
				button.setBackground(makeBackground(mainButton ? 2 : plain ? 0 : 1));
				button.setImageDrawable(new ControlIcon(iconType));
				button.setColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
				button.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
				int padding = (int) ((mainButton ? 19f : plain ? 11f : 12f) * dp);
				button.setPadding(padding, padding, padding, padding);
				button.setClickable(true);
				button.setFocusable(true);
				button.setSoundEffectsEnabled(true);
				button.setElevation(mainButton ? 10f * dp : plain ? 0f : 2f * dp);
				if (mainButton && android.os.Build.VERSION.SDK_INT >= 28) {
					button.setOutlineAmbientShadowColor(0xFFE21B14);
					button.setOutlineSpotShadowColor(0xFFE21B14);
				}
			}
			
			void setPlayState(android.widget.ImageButton button, boolean playing) {
				button.setImageDrawable(new ControlIcon(playing ? ICON_PAUSE : ICON_PLAY));
				button.setColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
			}
			
			void press(final android.view.View button) {
				if (button == null) return;
				button.animate().cancel();
				button.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70L).withEndAction(new java.lang.Runnable() {
					@Override public void run() {
						button.animate().scaleX(1f).scaleY(1f).setDuration(160L)
						.setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
					}
				}).start();
			}
		}
		
		
		final PlayerCenterButtonStyle playerCenterButtonStyle =
		new PlayerCenterButtonStyle();
		
		
		final android.widget.ImageButton previousButton =
		new android.widget.ImageButton(this);
		
		final android.widget.ImageButton rewindButton =
		new android.widget.ImageButton(this);
		
		final android.widget.ImageButton playButton =
		new android.widget.ImageButton(this);
		
		final android.widget.ImageButton forwardButton =
		new android.widget.ImageButton(this);
		
		final android.widget.ImageButton nextButton =
		new android.widget.ImageButton(this);
		
		
		playerCenterButtonStyle.apply(
		previousButton,
		PlayerCenterButtonStyle.ICON_PREVIOUS,
		false
		);
		
		playerCenterButtonStyle.apply(
		rewindButton,
		PlayerCenterButtonStyle.ICON_REWIND_10,
		false
		);
		
		playerCenterButtonStyle.apply(
		playButton,
		PlayerCenterButtonStyle.ICON_PAUSE,
		true
		);
		
		playerCenterButtonStyle.apply(
		forwardButton,
		PlayerCenterButtonStyle.ICON_FORWARD_10,
		false
		);
		
		playerCenterButtonStyle.apply(
		nextButton,
		PlayerCenterButtonStyle.ICON_NEXT,
		false
		);
		
		
		previousButton.setContentDescription(
		"الحلقة السابقة"
		);
		
		rewindButton.setContentDescription(
		"رجوع 10 ثوانٍ"
		);
		
		playButton.setContentDescription(
		"تشغيل أو إيقاف"
		);
		
		forwardButton.setContentDescription(
		"تقديم 10 ثوانٍ"
		);
		
		nextButton.setContentDescription(
		"الحلقة التالية"
		);
		
		
		android.widget.ImageButton[] controlButtons =
		new android.widget.ImageButton[] {
			previousButton,
			rewindButton,
			playButton,
			forwardButton,
			nextButton
		};
		
		
		int[] controlSizes = {
			(int)(48f * dp),
			(int)(60f * dp),
			(int)(76f * dp),
			(int)(60f * dp),
			(int)(48f * dp)
		};
		
		
		int gap =
		(int)(12f * dp);
		
		
		for (
		int i = 0;
		i < controlButtons.length;
		i++
		) {
			
			int size =
			controlSizes[i];
			
			
			android.widget.LinearLayout.LayoutParams lp =
			new android.widget.LinearLayout.LayoutParams(
			size,
			size
			);
			
			
			int sideGap =
			i == 2
			? (int)(20f * dp)
			: gap;
			
			
			lp.setMargins(
			sideGap,
			0,
			sideGap,
			0
			);
			
			
			centerControls.addView(
			controlButtons[i],
			lp
			);
		}
		
		
		android.widget.FrameLayout.LayoutParams centerLp =
		new android.widget.FrameLayout.LayoutParams(
		-2,
		-2
		);
		
		centerLp.gravity =
		android.view.Gravity.CENTER;
		
		playerHost.addView(
		centerControls,
		centerLp
		);
		
		
		// =====================================================
		// SEEK FEEDBACK
		// يظهر مع الضغط أو Double Tap
		// =====================================================
		
		final android.widget.TextView seekFeedback =
		new android.widget.TextView(this);
		
		seekFeedback.setTextColor(
		android.graphics.Color.WHITE
		);
		
		seekFeedback.setTextSize(
		18
		);
		
		seekFeedback.setGravity(
		android.view.Gravity.CENTER
		);
		
		seekFeedback.setTypeface(
		CigramTypefaces.bold()
		);
		
		seekFeedback.setPadding(
		(int)(18f * dp),
		(int)(9f * dp),
		(int)(18f * dp),
		(int)(9f * dp)
		);
		
		
		android.graphics.drawable.GradientDrawable seekFeedbackBg =
		new android.graphics.drawable.GradientDrawable();
		
		seekFeedbackBg.setColor(
		0xC9141414
		);
		
		seekFeedbackBg.setCornerRadius(
		22f * dp
		);
		
		seekFeedback.setBackground(
		seekFeedbackBg
		);
		
		seekFeedback.setVisibility(
		android.view.View.GONE
		);
		
		
		android.widget.FrameLayout.LayoutParams seekFeedbackLp =
		new android.widget.FrameLayout.LayoutParams(
		-2,
		-2
		);
		
		seekFeedbackLp.gravity =
		android.view.Gravity.CENTER;
		
		playerHost.addView(
		seekFeedback,
		seekFeedbackLp
		);
		
		
		final java.lang.Runnable[] hideSeekFeedback =
		new java.lang.Runnable[] {
			null
		};
		
		
		class SeekFeedbackController {
			
			void show(
			String text
			) {
				
				if (
				hideSeekFeedback[0] != null
				) {
					
					uiHandler.removeCallbacks(
					hideSeekFeedback[0]
					);
				}
				
				
				seekFeedback.animate()
				.cancel();
				
				seekFeedback.setText(
				text
				);
				
				seekFeedback.setAlpha(
				0f
				);
				
				seekFeedback.setScaleX(
				0.86f
				);
				
				seekFeedback.setScaleY(
				0.86f
				);
				
				seekFeedback.setVisibility(
				android.view.View.VISIBLE
				);
				
				
				seekFeedback.animate()
				.alpha(1f)
				.scaleX(1f)
				.scaleY(1f)
				.setDuration(120L)
				.start();
				
				
				hideSeekFeedback[0] =
				new java.lang.Runnable() {
					
					@Override
					public void run() {
						
						seekFeedback.animate()
						.alpha(0f)
						.scaleX(0.92f)
						.scaleY(0.92f)
						.setDuration(180L)
						.withEndAction(
						new java.lang.Runnable() {
							
							@Override
							public void run() {
								
								seekFeedback.setVisibility(
								android.view.View.GONE
								);
							}
						}
						)
						.start();
					}
				};
				
				
				uiHandler.postDelayed(
				hideSeekFeedback[0],
				520L
				);
			}
		}
		
		
		final SeekFeedbackController seekFeedbackController =
		new SeekFeedbackController();
		
		
		// =====================================================
		// BOTTOM CONTROLS
		// =====================================================
		
		final android.widget.LinearLayout bottomControls =
		new android.widget.LinearLayout(this);
		
		bottomControls.setOrientation(android.widget.LinearLayout.HORIZONTAL);
		bottomControls.setGravity(android.view.Gravity.CENTER_VERTICAL);
		bottomControls.setBackgroundColor(android.graphics.Color.TRANSPARENT);
		bottomControls.setPadding(
		(int) (18f * dp),
		0,
		(int) (18f * dp),
		(int) (7f * dp)
		);
		
		final android.widget.TextView currentTime =
		new android.widget.TextView(this);
		
		final android.widget.TextView totalTime =
		new android.widget.TextView(this);
		
		final android.widget.SeekBar timeBar =
		new android.widget.SeekBar(this);
		
		currentTime.setText("00:00");
		totalTime.setText("--:--");
		
		currentTime.setTextColor(android.graphics.Color.WHITE);
		totalTime.setTextColor(android.graphics.Color.WHITE);
		
		currentTime.setTextSize(18);
		totalTime.setTextSize(18);
		
		currentTime.setTypeface(
		android.graphics.Typeface.MONOSPACE,
		android.graphics.Typeface.BOLD
		);
		
		totalTime.setTypeface(
		android.graphics.Typeface.MONOSPACE,
		android.graphics.Typeface.BOLD
		);
		CigramFontGuard.keepFont(currentTime);   // monospace digits keep the time readout from jittering
		CigramFontGuard.keepFont(totalTime);
		
		currentTime.setShadowLayer(
		3f,
		0f,
		1f,
		0xAA000000
		);
		
		totalTime.setShadowLayer(
		3f,
		0f,
		1f,
		0xAA000000
		);
		
		currentTime.setGravity(android.view.Gravity.CENTER);
		totalTime.setGravity(android.view.Gravity.CENTER);
		
		/*
 * max يتغير تلقائياً إلى مدة الفيديو/الحلقة بالثواني
 * بعد ما ExoPlayer يتعرف على المدة.
 */
		timeBar.setMax(1);
		timeBar.setProgress(0);
		timeBar.setSecondaryProgress(0);
		timeBar.setEnabled(false);
		timeBar.setSplitTrack(false);
		
		timeBar.setPadding(
		(int)(8f * dp),
		(int)(10f * dp),
		(int)(8f * dp),
		(int)(10f * dp)
		);
		
		timeBar.setProgressTintList(
		android.content.res.ColorStateList.valueOf(0xFFE21B14)
		);
		
		timeBar.setSecondaryProgressTintList(
		android.content.res.ColorStateList.valueOf(0x88D73A35)
		);
		
		timeBar.setProgressBackgroundTintList(
		android.content.res.ColorStateList.valueOf(0x55FFFFFF)
		);
		
		timeBar.setThumbTintList(
		android.content.res.ColorStateList.valueOf(0xFFFFFFFF)
		);
		
		timeBar.setBackground(null);
		timeBar.setStateListAnimator(null);
		
		final int thumbSize = (int) (18f * dp);
		
		android.graphics.drawable.GradientDrawable thumb =
		new android.graphics.drawable.GradientDrawable();
		
		thumb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
		thumb.setColor(android.graphics.Color.WHITE);
		thumb.setSize(thumbSize, thumbSize);
		
		timeBar.setThumb(thumb);
		timeBar.setThumbOffset(thumbSize / 2);
		
		int timeWidth = (int) (96f * dp);
		
		bottomControls.addView(
		currentTime,
		new android.widget.LinearLayout.LayoutParams(timeWidth, -2)
		);
		
		bottomControls.addView(
		timeBar,
		new android.widget.LinearLayout.LayoutParams(
		0,
		(int)(44f * dp),
		1f
		)
		);
		
		bottomControls.addView(
		totalTime,
		new android.widget.LinearLayout.LayoutParams(timeWidth, -2)
		);
		
		// v10.80: square button (Cinemana style) on the right of the total time: portrait <-> landscape
		final CigramPlayerOrientation.Button cgOrientButton = new CigramPlayerOrientation.Button(this);
		android.widget.LinearLayout.LayoutParams cgOrientLp =
		new android.widget.LinearLayout.LayoutParams((int)(40f * dp), (int)(40f * dp));
		cgOrientLp.setMargins((int)(4f * dp), 0, (int)(2f * dp), 0);
		bottomControls.addView(cgOrientButton, cgOrientLp);
		cgOrientButton.setOnClickListener(new android.view.View.OnClickListener() {
			@Override public void onClick(android.view.View v) {
				cgOrientation.toggle();
			}
		});
		
		// the times and the bar fit the width in either direction
		final int cgTimeWide = timeWidth;
		final android.view.View.OnLayoutChangeListener cgFit = new android.view.View.OnLayoutChangeListener() {
			@Override public void onLayoutChange(android.view.View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
				try {
					boolean portrait = (r - l) < (b - t);
					cgOrientButton.setEnlarge(portrait);
					int want = portrait ? (int)(62f * dp) : cgTimeWide;
					android.widget.LinearLayout.LayoutParams a = (android.widget.LinearLayout.LayoutParams) currentTime.getLayoutParams();
					if (a.width != want) {
						a.width = want;
						currentTime.setLayoutParams(a);
						android.widget.LinearLayout.LayoutParams z = (android.widget.LinearLayout.LayoutParams) totalTime.getLayoutParams();
						z.width = want;
						totalTime.setLayoutParams(z);
						currentTime.setTextSize(portrait ? 13 : 18);
						totalTime.setTextSize(portrait ? 13 : 18);
					}
				} catch (Throwable ignored) { }
			}
		};
		playerHost.addOnLayoutChangeListener(cgFit);
		
		android.widget.FrameLayout.LayoutParams bottomLp =
		new android.widget.FrameLayout.LayoutParams(-1, -2);
		
		bottomLp.gravity = android.view.Gravity.BOTTOM;
		
		playerHost.addView(bottomControls, bottomLp);
		
		// =====================================================
		// SETTINGS BUTTON - TOP RIGHT
		// =====================================================
		
		final android.widget.ImageButton settingsButton =
		new android.widget.ImageButton(this);
		
		settingsButton.setBackgroundColor(android.graphics.Color.TRANSPARENT);
		settingsButton.setImageResource(R.drawable.mic_2);
		settingsButton.setColorFilter(
		android.graphics.Color.WHITE,
		android.graphics.PorterDuff.Mode.SRC_IN
		);
		settingsButton.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
		settingsButton.setClickable(true);
		settingsButton.setFocusable(true);
		
		int topButtonSize = (int) (44f * dp);
		final int topButtonMargin = (int) (14f * dp);
		int topButtonPadding = (int) (8f * dp);
		
		settingsButton.setPadding(
		topButtonPadding,
		topButtonPadding,
		topButtonPadding,
		topButtonPadding
		);
		
		android.widget.FrameLayout.LayoutParams settingsLp =
		new android.widget.FrameLayout.LayoutParams(
		topButtonSize,
		topButtonSize
		);
		
		settingsLp.gravity =
		android.view.Gravity.TOP | android.view.Gravity.RIGHT;
		
		settingsLp.setMargins(
		0,
		topButtonMargin,
		topButtonMargin,
		0
		);
		
		playerHost.addView(settingsButton, settingsLp);
		
		// =====================================================
		// SERIES LIST BUTTON - BESIDE SETTINGS
		// =====================================================
		
		final android.widget.ImageButton episodesButton =
		new android.widget.ImageButton(this);
		
		episodesButton.setBackgroundColor(android.graphics.Color.TRANSPARENT);
		episodesButton.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
		episodesButton.setClickable(true);
		episodesButton.setFocusable(true);
		
		episodesButton.setImageResource(R.drawable.mic_5);
		episodesButton.setColorFilter(
		android.graphics.Color.WHITE,
		android.graphics.PorterDuff.Mode.SRC_IN
		);
		
		android.widget.FrameLayout.LayoutParams episodesButtonLp =
		new android.widget.FrameLayout.LayoutParams(
		topButtonSize,
		topButtonSize
		);
		
		episodesButtonLp.gravity =
		android.view.Gravity.TOP | android.view.Gravity.RIGHT;
		
		episodesButtonLp.setMargins(
		0,
		topButtonMargin,
		(int) (66f * dp),
		0
		);
		
		playerHost.addView(episodesButton, episodesButtonLp);
		
		episodesButton.setVisibility(
		isSeries
		? android.view.View.VISIBLE
		: android.view.View.GONE
		);
		
		// =====================================================
		// DISPLAY CUTOUT SAFE AREA FOR CONTROLS ONLY
		// Video stays edge-to-edge; controls/captions stay outside the camera cutout.
		// =====================================================
		final int cgBottomBaseLeft = (int) (18f * dp);
		final int cgBottomBaseRight = (int) (18f * dp);
		final int cgBottomBaseBottom = (int) (7f * dp);
		final int cgSeriesBaseLeft = (int) (22f * dp);
		final int cgSeriesBaseTop = (int) (18f * dp);
		final int cgSettingsBaseRight = topButtonMargin;
		final int cgEpisodesBaseRight = (int) (66f * dp);
		final int cgSubtitleSide = (int) (18f * dp);
		final int[] cgLastSafeInsets = { -1, -1, -1, -1 };
		
		if (android.os.Build.VERSION.SDK_INT >= 20) {
			playerHost.setOnApplyWindowInsetsListener(
			new android.view.View.OnApplyWindowInsetsListener() {
				@Override
				public android.view.WindowInsets onApplyWindowInsets(
				android.view.View view,
				android.view.WindowInsets insets
				) {
					int safeLeft = 0;
					int safeTop = 0;
					int safeRight = 0;
					int safeBottom = 0;
					
					if (android.os.Build.VERSION.SDK_INT >= 28 && insets != null) {
						try {
							android.view.DisplayCutout cutout = insets.getDisplayCutout();
							if (cutout != null) {
								safeLeft = Math.max(0, cutout.getSafeInsetLeft());
								safeTop = Math.max(0, cutout.getSafeInsetTop());
								safeRight = Math.max(0, cutout.getSafeInsetRight());
								safeBottom = Math.max(0, cutout.getSafeInsetBottom());
							}
						} catch (Throwable ignored) {}
					}
					
					bottomControls.setPadding(
					cgBottomBaseLeft + safeLeft,
					0,
					cgBottomBaseRight + safeRight,
					cgBottomBaseBottom + safeBottom
					);
					
					if (subtitleView != null) {
						subtitleView.setPadding(
						cgSubtitleSide + safeLeft,
						subtitleView.getPaddingTop(),
						cgSubtitleSide + safeRight,
						subtitleView.getPaddingBottom()
						);
					}
					
					try {
						android.widget.FrameLayout.LayoutParams lp =
						(android.widget.FrameLayout.LayoutParams) seriesInfo.getLayoutParams();
						lp.leftMargin = cgSeriesBaseLeft + safeLeft;
						lp.topMargin = cgSeriesBaseTop + safeTop;
						lp.rightMargin = 0;
						lp.bottomMargin = 0;
						seriesInfo.setLayoutParams(lp);
					} catch (Throwable ignored) {}
					
					try {
						android.widget.FrameLayout.LayoutParams lp =
						(android.widget.FrameLayout.LayoutParams) settingsButton.getLayoutParams();
						lp.leftMargin = 0;
						lp.topMargin = topButtonMargin + safeTop;
						lp.rightMargin = cgSettingsBaseRight + safeRight;
						lp.bottomMargin = 0;
						settingsButton.setLayoutParams(lp);
					} catch (Throwable ignored) {}
					
					try {
						android.widget.FrameLayout.LayoutParams lp =
						(android.widget.FrameLayout.LayoutParams) episodesButton.getLayoutParams();
						lp.leftMargin = 0;
						lp.topMargin = topButtonMargin + safeTop;
						lp.rightMargin = cgEpisodesBaseRight + safeRight;
						lp.bottomMargin = 0;
						episodesButton.setLayoutParams(lp);
					} catch (Throwable ignored) {}
					
					centerControls.setTranslationX((safeLeft - safeRight) * 0.5f);
					
					if (
					cgLastSafeInsets[0] != safeLeft
					|| cgLastSafeInsets[1] != safeTop
					|| cgLastSafeInsets[2] != safeRight
					|| cgLastSafeInsets[3] != safeBottom
					) {
						cgLastSafeInsets[0] = safeLeft;
						cgLastSafeInsets[1] = safeTop;
						cgLastSafeInsets[2] = safeRight;
						cgLastSafeInsets[3] = safeBottom;
						android.util.Log.w(
						"CigramPlayerInsets",
						"cutout safe L=" + safeLeft
						+ " T=" + safeTop
						+ " R=" + safeRight
						+ " B=" + safeBottom
						);
					}
					
					return insets;
				}
			}
			);
			playerHost.requestApplyInsets();
		}
		
		// =====================================================
		// MEDIA LOADER
		// CRASH FIX: DOES NOT CALL exoPlayer.setTrackSelectionParameters()
		// =====================================================
		
		final boolean[] subtitlesEnabled = { subtitlePrefs.getBoolean("subtitle_enabled", true) };
		
		class MediaLoader {
			
			void load(
			String video,
			String subtitle,
			long startPosition
			) {
				if (video == null || video.trim().equals("")) {
					android.widget.Toast.makeText(
					PlayerActivity.this,
					"رابط الفيديو غير متوفر",
					android.widget.Toast.LENGTH_SHORT
					).show();
					
					return;
				}
				
				if (fromResolver[0]) fromResolver[0] = false;           // the stream found for an embed link: keep the embed link as the source
				else sourceVideo[0] = video.trim();
				// An embed page (vidbolt, ...) is not a video file: find its real stream first, then play
				// that in this same player. Resolved links carry "|Referer=..." so they are not resolved twice.
				if (video.indexOf('|') < 0 && CigramEmbedResolver.isKnownHost(video)) {
					final String embedLink = video.trim();
					final String embedSubtitle = subtitle;
					final long embedStart = startPosition;
					final int embedGeneration = ++mediaLoadGeneration[0];
					try { exoPlayer.stop(); } catch (Exception ignored) {}
					CigramEmbedResolver.resolveAsync(PlayerActivity.this, embedLink, new CigramEmbedResolver.Callback() {
						@Override public void onResolved(String url) {
							if (isFinishing() || isDestroyed() || embedGeneration != mediaLoadGeneration[0]) return;
							fromResolver[0] = true;
							MediaLoader.this.load(url, embedSubtitle, embedStart);
						}
						@Override public void onFailed(String reason) {
							if (isFinishing() || isDestroyed() || embedGeneration != mediaLoadGeneration[0]) return;
							new android.app.AlertDialog.Builder(PlayerActivity.this)
							.setTitle("تعذّر تشغيل الفيديو")
							.setMessage(CigramEmbedResolver.friendly(reason) + "\n\n(" + reason + ")")
							.setCancelable(false)
							.setPositiveButton("إعادة المحاولة", new android.content.DialogInterface.OnClickListener() {
								@Override public void onClick(android.content.DialogInterface d, int w) {
									MediaLoader.this.load(embedLink, embedSubtitle, embedStart);
								}
							})
							.setNegativeButton("رجوع", new android.content.DialogInterface.OnClickListener() {
								@Override public void onClick(android.content.DialogInterface d, int w) { finish(); }
							})
							.show();
						}
					});
					return;
				}
				
				currentVideo[0] = video.trim();
				currentSubtitle[0] =
				subtitle == null ? "" : subtitle.trim();
				
				com.google.android.exoplayer2.MediaItem.Builder builder =
				new com.google.android.exoplayer2.MediaItem.Builder()
				.setUri(
				android.net.Uri.parse(currentVideo[0])
				);
				
				if (!currentSubtitle[0].equals("") && cgSubEngine[0] != null) {
					// 18.2: CigramSubtitleSyncEngine owns the subtitle (no SubtitleConfiguration for ExoPlayer)
					cgSubEngine[0].load(currentSubtitle[0]);
					if (subtitleView != null) {
						subtitleView.setVisibility(subtitlesEnabled[0]
						? android.view.View.VISIBLE : android.view.View.GONE);
					}
				} else if (!currentSubtitle[0].equals("")) {
					
					String mime =
					com.google.android.exoplayer2.util.MimeTypes.APPLICATION_SUBRIP;
					
					String lower =
					currentSubtitle[0].toLowerCase(
					java.util.Locale.US
					);
					
					if (lower.contains(".vtt")) {
						mime =
						com.google.android.exoplayer2.util.MimeTypes.TEXT_VTT;
					}
					
					com.google.android.exoplayer2.MediaItem.SubtitleConfiguration cfg =
					new com.google.android.exoplayer2.MediaItem.SubtitleConfiguration.Builder(
					android.net.Uri.parse(currentSubtitle[0])
					)
					.setMimeType(mime)
					.setLanguage("ar")
					.setLabel("العربية")
					.setSelectionFlags(
					com.google.android.exoplayer2.C.SELECTION_FLAG_DEFAULT
					)
					.build();
					
					java.util.ArrayList<
					com.google.android.exoplayer2.MediaItem.SubtitleConfiguration
					> subtitleList = new java.util.ArrayList<>();
					
					subtitleList.add(cfg);
					builder.setSubtitleConfigurations(subtitleList);
					
					if (subtitleView != null) {
						subtitleView.setVisibility(subtitlesEnabled[0]
						? android.view.View.VISIBLE : android.view.View.GONE);
					}
					
				} else {
					
					if (cgSubEngine[0] != null) cgSubEngine[0].clear();
					if (subtitleView != null) {
						subtitleView.setVisibility(android.view.View.GONE);
					}
				}
				
				// CIGRAM: أي نوع رابط (HLS / DASH / MP4 / MKV / TS / RTSP / رابط|Headers)
				CigramStreams.apply(builder, currentVideo[0]);
				// v10.68: يبحث في الخلفية عن نسخ 2K / 1080p / 720p لنفس الفيلم (CigramQuality)
				try { CigramQuality.probe(currentVideo[0], null); } catch (Throwable ignored) {}
				
				final long safeStart =
				Math.max(0L, startPosition);
				
				/*
         * CRASH FIX:
         * نفصل جميع Player.Listener المعروفة قبل تغيير MediaItem.
         * PlayerView نفسه يسجل Listener داخلي، لذلك نفصله أيضاً.
         */
				try {
					exoView.setPlayer(null);
				} catch (Exception ignored) {}
				
				endedHandled[0] = false;
				
				final int thisLoad = ++mediaLoadGeneration[0];
				
				try {
					exoPlayer.stop();
				} catch (Exception ignored) {}
				
				try {
					exoPlayer.clearMediaItems();
				} catch (Exception ignored) {}
				
				try {
					exoPlayer.setPlayWhenReady(true);
					exoPlayer.setMediaItem(
					builder.build(),
					safeStart
					);
					exoPlayer.prepare();
				} catch (Throwable mediaError) {
					android.widget.Toast.makeText(
					PlayerActivity.this,
					"تعذر تجهيز الفيديو",
					android.widget.Toast.LENGTH_SHORT
					).show();
					return;
				}
				
				/*
         * ننتظر READY بدون Listener.
         * هكذا تحديثات Timeline/Tracks الأولى تكتمل قبل ربط PlayerView.
         */
				uiHandler.post(
				new java.lang.Runnable() {
					
					int attempts = 0;
					
					@Override
					public void run() {
						
						if (thisLoad != mediaLoadGeneration[0]) {
							return;
						}
						
						attempts++;
						
						int state =
						exoPlayer.getPlaybackState();
						
						if (
						state == com.google.android.exoplayer2.Player.STATE_READY
						|| attempts >= 100
						) {
							
							try {
								exoView.setPlayer(exoPlayer);
							} catch (Throwable attachError) {
								android.widget.Toast.makeText(
								PlayerActivity.this,
								"تعذر ربط واجهة المشغل",
								android.widget.Toast.LENGTH_SHORT
								).show();
								return;
							}
							
							// v10.81: the film starts at once; the seek bar is warmed up quietly in the background
							try {
								if (state == com.google.android.exoplayer2.Player.STATE_READY) CigramSeekGate.warm(exoPlayer);
							} catch (Throwable warmError) {
								android.util.Log.w("CigramGate", "warm failed", warmError);
							}
							
							// Playback is already armed before prepare().  Do not call
							// play() after PlayerView is reattached.
							
							if (
							subtitleView != null
							&&
							subtitlesEnabled[0]
							) {
								subtitleView.postDelayed(
								applySubtitleStyle,
								250L
								);
								
								subtitleView.postDelayed(
								applySubtitleStyle,
								900L
								);
							}
							
							return;
						}
						
						if (
						exoPlayer.getPlayerError() != null
						) {
							android.widget.Toast.makeText(
							PlayerActivity.this,
							"تعذر تشغيل الفيديو",
							android.widget.Toast.LENGTH_SHORT
							).show();
							return;
						}
						
						uiHandler.postDelayed(
						this,
						100L
						);
					}
				}
				);
			}
		}
		
		final MediaLoader mediaLoader = new MediaLoader();
		
		// =====================================================
		// EPISODE CONTROLLER
		// =====================================================
		
		class EpisodeController {
			
			void saveCurrentProgress() {
				if (watchProgressKey[0].equals("")) return;
				
				long position = exoPlayer.getCurrentPosition();
				long duration = exoPlayer.getDuration();
				
				if (
				exoPlayer.getPlaybackState()
				== com.google.android.exoplayer2.Player.STATE_ENDED
				||
				(
				duration > 0L
				&&
				duration - position < 15000L
				)
				) {
					watchProgress.edit()
					.remove(watchProgressKey[0])
					.apply();
					
					continueWatchingStore.removeCurrent();
					
				} else if (position >= 5000L) {
					watchProgress.edit()
					.putLong(
					watchProgressKey[0],
					position
					)
					.apply();
					
					continueWatchingStore.save(
					position,
					duration
					);
				}
			}
			
			int nextPlayableIndex() {
				if (!isSeries || currentEpisodeIndex[0] < 0) return -1;
				for (int i = currentEpisodeIndex[0] + 1; i < episodeItems.size(); i++) {
					String url = episodeItems.get(i).video;
					if (url != null && !url.trim().isEmpty()) return i;
				}
				return -1;
			}
			
			int previousPlayableIndex() {
				if (!isSeries) return -1;
				for (int i = currentEpisodeIndex[0] - 1; i >= 0; i--) {
					String url = episodeItems.get(i).video;
					if (url != null && !url.trim().isEmpty()) return i;
				}
				return -1;
			}
			
			boolean hasPrevious() { return previousPlayableIndex() >= 0; }
			
			boolean hasNext() { return nextPlayableIndex() >= 0; }
			
			void open(int index) {
				if (
				index < 0
				||
				index >= episodeItems.size()
				) return;
				
				EpisodeData data =
				episodeItems.get(index);
				
				if (
				data.video == null
				||
				data.video.trim().equals("")
				) {
					android.widget.Toast.makeText(
					PlayerActivity.this,
					"رابط الحلقة غير متوفر",
					android.widget.Toast.LENGTH_SHORT
					).show();
					
					return;
				}
				
				if (pendingAutoNext[0] != null) {
					uiHandler.removeCallbacks(pendingAutoNext[0]);
					pendingAutoNext[0] = null;
				}
				saveCurrentProgress();
				
				currentEpisodeIndex[0] = index;
				currentSeason[0] = data.season;
				currentEpisode[0] = data.episode;
				currentVideo[0] = data.video;
				currentSubtitle[0] = data.subtitle;
				
				headerUpdater.update();
				
				watchProgressKey[0] =
				scopedProgressKey.make();
				
				skipParser.parse(data.skip);
				
				long saved =
				watchProgress.getLong(
				watchProgressKey[0],
				0L
				);
				if (saved <= 0L) {
					saved = continueWatchingStore.savedPositionForCurrent();
					if (saved > 0L) watchProgress.edit().putLong(watchProgressKey[0], saved).apply();
				}
				// v10.62: آخر موضع محفوظ في الحساب (من جهاز آخر)
				if (saved <= 0L) {
					try {
						saved = CigramResume.position(PlayerActivity.this.getApplicationContext(), isSeries,
						seriesTitle, mediaTitle, mediaId, currentSeason[0], currentEpisode[0]);
					} catch (Throwable cgResumeError) { saved = 0L; }
				}
				
				mediaLoader.load(
				data.video,
				data.subtitle,
				saved
				);
			}
		}
		
		final EpisodeController episodeController =
		new EpisodeController();
		
		// CIGRAM: get an AdMob interstitial ready so the break between episodes never makes the user wait.
		if (isSeries) {
			CigramEpisodeAds.preload(PlayerActivity.this);
		}
		
		// =====================================================
		// BUTTON EVENTS - ICON VERSION
		// =====================================================
		
		final long[] seekStepMs = {
			(cgPlayerPrefs.getLong("seek_step", 10000L) == 5000L
			|| cgPlayerPrefs.getLong("seek_step", 10000L) == 15000L
			|| cgPlayerPrefs.getLong("seek_step", 10000L) == 25000L
			|| cgPlayerPrefs.getLong("seek_step", 10000L) == 30000L)
			? cgPlayerPrefs.getLong("seek_step", 10000L)
			: 10000L
		};
		rewindButton.setContentDescription("رجوع " + (seekStepMs[0] / 1000L) + " ثوانٍ");
		forwardButton.setContentDescription("تقديم " + (seekStepMs[0] / 1000L) + " ثوانٍ");
		
		
		previousButton.setOnClickListener(
		new android.view.View.OnClickListener() {
			
			@Override
			public void onClick(
			android.view.View view
			) {
				
				playerCenterButtonStyle.press(
				previousButton
				);
				
				
				if (
				episodeController.hasPrevious()
				) {
					
					episodeController.open(
					episodeController.previousPlayableIndex()
					);
					
				} else {
					
					exoPlayer.seekTo(
					0L
					);
				}
			}
		}
		);
		
		
		rewindButton.setOnClickListener(
		new android.view.View.OnClickListener() {
			
			@Override
			public void onClick(
			android.view.View view
			) {
				
				playerCenterButtonStyle.press(
				rewindButton
				);
				
				
				long current =
				Math.max(
				0L,
				exoPlayer.getCurrentPosition()
				);
				
				
				long target =
				Math.max(
				0L,
				current - seekStepMs[0]
				);
				
				
				exoPlayer.seekTo(
				target
				);
				
				
				seekFeedbackController.show(
				"-"
				+
				(seekStepMs[0] / 1000L)
				+
				" ث"
				);
			}
		}
		);
		
		
		playButton.setOnClickListener(
		new android.view.View.OnClickListener() {
			
			@Override
			public void onClick(
			android.view.View view
			) {
				
				playerCenterButtonStyle.press(
				playButton
				);
				
				
				if (
				exoPlayer.isPlaying()
				) {
					
					exoPlayer.pause();
					
				} else {
					
					exoPlayer.play();
				}
				
				
				playerCenterButtonStyle.setPlayState(
				playButton,
				exoPlayer.isPlaying()
				);
			}
		}
		);
		
		
		forwardButton.setOnClickListener(
		new android.view.View.OnClickListener() {
			
			@Override
			public void onClick(
			android.view.View view
			) {
				
				playerCenterButtonStyle.press(
				forwardButton
				);
				
				
				long current =
				Math.max(
				0L,
				exoPlayer.getCurrentPosition()
				);
				
				
				long target =
				current
				+
				seekStepMs[0];
				
				
				long duration =
				exoPlayer.getDuration();
				
				
				if (
				duration > 0L
				&&
				duration
				!=
				com.google.android.exoplayer2.C.TIME_UNSET
				) {
					
					target =
					Math.min(
					target,
					duration
					);
				}
				
				
				exoPlayer.seekTo(
				Math.max(
				0L,
				target
				)
				);
				
				
				seekFeedbackController.show(
				"+"
				+
				(seekStepMs[0] / 1000L)
				+
				" ث"
				);
			}
		}
		);
		
		
		nextButton.setOnClickListener(
		new android.view.View.OnClickListener() {
			
			@Override
			public void onClick(
			android.view.View view
			) {
				
				playerCenterButtonStyle.press(
				nextButton
				);
				
				
				if (
				episodeController.hasNext()
				) {
					
					final int cgNextIndex =
					episodeController.nextPlayableIndex();
					CigramEpisodeAds.beforeEpisode(
					PlayerActivity.this,
					new java.lang.Runnable() {
						@Override public void run() {
							try { exoPlayer.pause(); } catch (Exception ignored) { }
						}
					},
					new java.lang.Runnable() {
						@Override public void run() {
							if (isFinishing() || isDestroyed()) return;
							episodeController.open(cgNextIndex);
						}
					}
					);
				}
			}
		}
		);
		
		
		// =====================================================
		// AUTO-HIDE FORWARD HOLDER
		// Fixes Sketchware/Java local-variable scope issue.
		// =====================================================
		
		final java.lang.Runnable[] hideControlsHolder =
		new java.lang.Runnable[] {
			null
		};
		
		
		// =====================================================
		// VIDEO TIME / DURATION ENGINE
		// =====================================================
		
		class PlayerTimeEngine {
			
			long getDurationMs() {
				
				long duration =
				com.google.android.exoplayer2.C.TIME_UNSET;
				
				try {
					duration =
					exoPlayer.getDuration();
				} catch (Exception ignored) {
				}
				
				
				if (
				duration <= 0L
				||
				duration
				==
				com.google.android.exoplayer2.C.TIME_UNSET
				) {
					
					try {
						duration =
						exoPlayer.getContentDuration();
					} catch (Exception ignored) {
					}
				}
				
				
				/*
         * Fallback:
         * نقرأ مدة الـ Window مباشرة من Timeline.
         * هذا يفيد ببعض الروابط التي تتأخر بإرجاع getDuration().
         */
				if (
				duration <= 0L
				||
				duration
				==
				com.google.android.exoplayer2.C.TIME_UNSET
				) {
					
					try {
						
						com.google.android.exoplayer2.Timeline timeline =
						exoPlayer.getCurrentTimeline();
						
						
						if (
						timeline != null
						&&
						!timeline.isEmpty()
						) {
							
							int windowIndex =
							exoPlayer.getCurrentMediaItemIndex();
							
							
							if (
							windowIndex < 0
							||
							windowIndex >= timeline.getWindowCount()
							) {
								
								windowIndex =
								0;
							}
							
							
							com.google.android.exoplayer2.Timeline.Window window =
							new com.google.android.exoplayer2.Timeline.Window();
							
							
							timeline.getWindow(
							windowIndex,
							window
							);
							
							
							long timelineDuration =
							window.getDurationMs();
							
							
							if (
							timelineDuration > 0L
							&&
							timelineDuration
							!=
							com.google.android.exoplayer2.C.TIME_UNSET
							) {
								
								duration =
								timelineDuration;
							}
						}
						
					} catch (Exception ignored) {
					}
				}
				
				
				if (
				duration <= 0L
				||
				duration
				==
				com.google.android.exoplayer2.C.TIME_UNSET
				) {
					
					return 0L;
				}
				
				
				return duration;
			}
			
			
			long getPositionMs() {
				
				long position =
				0L;
				
				
				try {
					position =
					exoPlayer.getCurrentPosition();
				} catch (Exception ignored) {
				}
				
				
				if (
				position < 0L
				||
				position
				==
				com.google.android.exoplayer2.C.TIME_UNSET
				) {
					
					try {
						position =
						exoPlayer.getContentPosition();
					} catch (Exception ignored) {
					}
				}
				
				
				if (
				position < 0L
				||
				position
				==
				com.google.android.exoplayer2.C.TIME_UNSET
				) {
					
					position =
					0L;
				}
				
				
				return position;
			}
			
			
			String format(
			long milliseconds
			) {
				
				long seconds =
				Math.max(
				0L,
				milliseconds / 1000L
				);
				
				
				/*
         * DateUtils:
         * أقل من ساعة  ->  42:15
         * أكثر من ساعة ->  1:42:15
         */
				return android.text.format.DateUtils
				.formatElapsedTime(
				seconds
				);
			}
			
			
			int getTotalSeconds(
			long durationMs
			) {
				
				long seconds =
				Math.max(
				1L,
				durationMs / 1000L
				);
				
				
				if (
				seconds > Integer.MAX_VALUE
				) {
					
					return Integer.MAX_VALUE;
				}
				
				
				return (int)seconds;
			}
			
			
			int getPositionSeconds(
			long positionMs,
			int maxSeconds
			) {
				
				long seconds =
				Math.max(
				0L,
				positionMs / 1000L
				);
				
				
				if (
				seconds > maxSeconds
				) {
					
					seconds =
					maxSeconds;
				}
				
				
				return (int)seconds;
			}
		}
		
		
		final PlayerTimeEngine playerTimeEngine =
		new PlayerTimeEngine();
		
		
		// =====================================================
		// SEEKBAR - SEEK ON RELEASE
		// =====================================================
		
		final boolean[] dragging = {
			false
		};
		
		final long[] pendingSeekPosition = {
			-1L
		};
		
		
		timeBar.setOnSeekBarChangeListener(
		new android.widget.SeekBar.OnSeekBarChangeListener() {
			
			@Override
			public void onProgressChanged(
			android.widget.SeekBar bar,
			int progress,
			boolean fromUser
			) {
				
				if (!fromUser) {
					return;
				}
				
				
				long duration =
				playerTimeEngine.getDurationMs();
				
				
				if (
				duration <= 0L
				) {
					
					return;
				}
				
				
				int max =
				Math.max(
				1,
				bar.getMax()
				);
				
				
				int bounded =
				Math.max(
				0,
				Math.min(
				max,
				progress
				)
				);
				
				
				long target =
				(
				duration
				*
				(long)bounded
				)
				/
				(long)max;
				
				
				target =
				Math.max(
				0L,
				Math.min(
				duration,
				target
				)
				);
				
				
				pendingSeekPosition[0] =
				target;
				
				
				/*
             * أثناء السحب نعرض الوقت المتوقع فوراً،
             * لكن ما نسوي seek بكل حركة حتى يبقى السحب ناعم.
             */
				currentTime.setText(
				playerTimeEngine.format(
				target
				)
				);
				
				CigramSeekPreview.move(target, duration);
			}
			
			
			@Override
			public void onStartTrackingTouch(
			android.widget.SeekBar bar
			) {
				
				dragging[0] =
				true;
				
				CigramSeekPreview.start(PlayerActivity.this, bar, exoPlayer);
				
				pendingSeekPosition[0] =
				playerTimeEngine.getPositionMs();
				
				
				if (
				hideControlsHolder[0] != null
				) {
					uiHandler.removeCallbacks(
					hideControlsHolder[0]
					);
				}
			}
			
			
			@Override
			public void onStopTrackingTouch(
			android.widget.SeekBar bar
			) {
				
				dragging[0] =
				false;
				
				CigramSeekPreview.stop();
				
				long duration =
				playerTimeEngine.getDurationMs();
				
				
				if (
				duration > 0L
				&&
				pendingSeekPosition[0] >= 0L
				) {
					
					long target =
					Math.max(
					0L,
					Math.min(
					duration,
					pendingSeekPosition[0]
					)
					);
					
					
					try {
						
						CigramSeekGuard.seek(
						exoPlayer,
						target
						);
						
					} catch (Exception ignored) {
					}
				}
				
				
				pendingSeekPosition[0] =
				-1L;
				
				
				if (
				hideControlsHolder[0] != null
				) {
					uiHandler.postDelayed(
					hideControlsHolder[0],
					3000L
					);
				}
			}
		}
		);
		
		
		// =====================================================
		// PROGRESS UPDATE - FIXED
		// =====================================================
		
		// =====================================================
		// v10.62: «تخطي المقدمة» + بطاقة «الحلقة التالية» (CigramSkipIntro)
		// =====================================================
		final CigramSkipIntro cgSkipIntro = CigramSkipIntro.attach(
		PlayerActivity.this,
		playerHost,
		new CigramSkipIntro.Host() {
			@Override public void seekTo(long ms) {
				try { exoPlayer.seekTo(Math.max(0L, ms)); } catch (Throwable ignored) {}
			}
			@Override public boolean isSeries() { return isSeries; }
			@Override public boolean hasNext() {
				try { return isSeries && episodeController.hasNext(); } catch (Throwable ignored) { return false; }
			}
			@Override public void playNext() {
				try {
					final int cgNextIndex = episodeController.nextPlayableIndex();
					if (cgNextIndex < 0) return;
					CigramEpisodeAds.beforeEpisode(
					PlayerActivity.this,
					null,
					new java.lang.Runnable() {
						@Override public void run() {
							if (isFinishing() || isDestroyed()) return;
							episodeController.open(cgNextIndex);
						}
					}
					);
				} catch (Throwable ignored) {}
			}
			@Override public String seriesTitle() { return seriesTitle; }
			@Override public String seriesId() {
				String cgSid = getIntent().getStringExtra("series_id");
				return cgSid == null ? "" : cgSid;
			}
			@Override public String season() { return currentSeason[0]; }
			@Override public String episode() { return currentEpisode[0]; }
		}
		);
		
		final java.lang.Runnable updateProgress =
		new java.lang.Runnable() {
			
			@Override
			public void run() {
				
				/*
             * مهم جداً:
             * لا نسوي return إذا الواجهة بعد ما Attached.
             * سابقاً الـ Runnable كان ينتهي من أول تشغيل،
             * ولذلك الوقت يبقى ثابت والشريط ما يتحرك.
             */
				
				try {
					
					long position =
					playerTimeEngine.getPositionMs();
					
					
					long duration =
					playerTimeEngine.getDurationMs();
					
					
					// ---------------------------------------------
					// Skip configured scenes
					// ---------------------------------------------
					
					if (
					skipScenesEnabled[0]
					&&
					exoPlayer.isPlaying()
					) {
						
						for (
						long[] segment :
						skipSegments
						) {
							
							if (
							position >= segment[0]
							&&
							position < segment[1]
							) {
								
								exoPlayer.seekTo(
								segment[1]
								);
								
								position =
								segment[1];
								
								break;
							}
						}
					}
					
					
					// v10.62: تخطي المقدمة + الحلقة التالية
					try {
						cgSkipIntro.tick(position, duration, exoPlayer.isPlaying(), dragging[0]);
					} catch (Throwable cgSkipError) {}
					
					// ---------------------------------------------
					// Current time
					// ---------------------------------------------
					
					if (
					!dragging[0]
					) {
						
						currentTime.setText(
						playerTimeEngine.format(
						position
						)
						);
					}
					
					
					// ---------------------------------------------
					// Duration + seekbar
					// ---------------------------------------------
					
					if (
					duration > 0L
					) {
						
						totalTime.setText(
						playerTimeEngine.format(
						duration
						)
						);
						
						
						int maxSeconds =
						playerTimeEngine.getTotalSeconds(
						duration
						);
						
						
						if (
						timeBar.getMax()
						!=
						maxSeconds
						) {
							
							timeBar.setMax(
							maxSeconds
							);
						}
						
						
						if (
						!timeBar.isEnabled()
						) {
							
							timeBar.setEnabled(
							true
							);
							
							timeBar.setAlpha(
							1f
							);
						}
						
						
						if (
						!dragging[0]
						) {
							
							timeBar.setProgress(
							playerTimeEngine.getPositionSeconds(
							position,
							maxSeconds
							)
							);
						}
						
						
						int bufferedPercent =
						exoPlayer.getBufferedPercentage();
						
						
						bufferedPercent =
						Math.max(
						0,
						Math.min(
						100,
						bufferedPercent
						)
						);
						
						
						int secondary =
						(int)(
						(
						(long)maxSeconds
						*
						bufferedPercent
						)
						/
						100L
						);
						
						
						timeBar.setSecondaryProgress(
						Math.max(
						0,
						Math.min(
						maxSeconds,
						secondary
						)
						)
						);
						
					} else {
						
						/*
                     * قبل ما ExoPlayer يعرف المدة.
                     */
						totalTime.setText(
						"--:--"
						);
						
						timeBar.setMax(
						1
						);
						
						timeBar.setProgress(
						0
						);
						
						timeBar.setSecondaryProgress(
						0
						);
						
						timeBar.setEnabled(
						false
						);
						
						timeBar.setAlpha(
						0.65f
						);
					}
					
					
					// ---------------------------------------------
					// Watch progress
					// ---------------------------------------------
					
					if (
					!watchProgressKey[0].equals("")
					) {
						
						if (
						exoPlayer.getPlaybackState()
						==
						com.google.android.exoplayer2.Player.STATE_ENDED
						) {
							
							watchProgress.edit()
							.remove(
							watchProgressKey[0]
							)
							.apply();
							
							
							continueWatchingStore.removeCurrent();
							
							
							if (
							!endedHandled[0]
							&&
							episodeController.hasNext()
							// «بدء الحلقة التالية تلقائياً» (settings sheet)
							&& CigramPlayerSheet.autoNextEnabled(PlayerActivity.this)
							) {
								
								endedHandled[0] =
								true;
								
								
								final int nextIndex = episodeController.nextPlayableIndex();
								
								
								final int expectedEpisode = currentEpisodeIndex[0];
								final int expectedLoad = mediaLoadGeneration[0];
								pendingAutoNext[0] = new java.lang.Runnable() {
									@Override public void run() {
										pendingAutoNext[0] = null;
										if (isFinishing() || isDestroyed()
										|| currentEpisodeIndex[0] != expectedEpisode
										|| mediaLoadGeneration[0] != expectedLoad) return;
										if (exoPlayer.getPlaybackState()
										!= com.google.android.exoplayer2.Player.STATE_ENDED) {
											endedHandled[0] = false;
											return;
										}
										CigramEpisodeAds.beforeEpisode(
										PlayerActivity.this,
										null,
										new java.lang.Runnable() {
											@Override public void run() {
												if (isFinishing() || isDestroyed()) return;
												episodeController.open(nextIndex);
											}
										}
										);
									}
								};
								uiHandler.postDelayed(pendingAutoNext[0], 500L);
							}
							
						} else if (
						position >= 5000L
						&&
						android.os.SystemClock.elapsedRealtime()
						-
						lastProgressSaveTime[0]
						>=
						5000L
						) {
							
							watchProgress.edit()
							.putLong(
							watchProgressKey[0],
							position
							)
							.apply();
							
							
							continueWatchingStore.save(
							position,
							duration
							);
							
							
							lastProgressSaveTime[0] =
							android.os.SystemClock.elapsedRealtime();
						}
					}
					
					
					// v6.0 design 5: scenes marked for skipping, in yellow on the time bar
					try {
						CigramSeekMarks.update(timeBar, skipSegments, playerTimeEngine.getDurationMs());
					} catch (Throwable ignored) {}
					
					// ---------------------------------------------
					// Play/Pause icon
					// ---------------------------------------------
					
					playerCenterButtonStyle.setPlayState(
					playButton,
					exoPlayer.isPlaying()
					);
					
					
					// ---------------------------------------------
					// Previous / Next state
					// ---------------------------------------------
					
					previousButton.setColorFilter(
					episodeController.hasPrevious()
					?
					android.graphics.Color.WHITE
					:
					0xFF777777,
					android.graphics.PorterDuff.Mode.SRC_IN
					);
					
					
					previousButton.setAlpha(
					episodeController.hasPrevious()
					?
					1f
					:
					0.58f
					);
					
					
					nextButton.setColorFilter(
					episodeController.hasNext()
					?
					android.graphics.Color.WHITE
					:
					0xFF777777,
					android.graphics.PorterDuff.Mode.SRC_IN
					);
					
					
					nextButton.setAlpha(
					episodeController.hasNext()
					?
					1f
					:
					0.58f
					);
					
				} catch (Exception ignored) {
				}
				
				
				/*
             * هذا السطر لازم دائماً ينفذ.
             * حتى إذا المدة لحد الآن غير جاهزة، نعيد الفحص.
             */
				uiHandler.postDelayed(
				this,
				250L
				);
			}
		};
		
		
		uiHandler.post(
		updateProgress
		);
		
		
		// =====================================================
		// EPISODE LIST POPUP - SEASON SELECTOR + CURRENT EPISODE
		// =====================================================
		
		final android.widget.PopupWindow[] episodesPopupHolder={null};
		episodesButton.setOnClickListener(new android.view.View.OnClickListener(){
			@Override public void onClick(android.view.View v) {
				if(!isSeries||episodeItems.isEmpty())return;
				if(episodesPopupHolder[0]!=null&&episodesPopupHolder[0].isShowing()){episodesPopupHolder[0].dismiss();return;}
				final android.widget.LinearLayout panel=new android.widget.LinearLayout(PlayerActivity.this);
				panel.setOrientation(1);panel.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
				panel.setPadding(cgGlass.px(16),cgGlass.px(12),cgGlass.px(16),cgGlass.px(12));cgGlass.bind(panel,27,true);
				android.widget.LinearLayout header=new android.widget.LinearLayout(PlayerActivity.this);
				header.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);header.setGravity(android.view.Gravity.CENTER_VERTICAL);
				android.widget.TextView title=cgGlass.label(seriesTitle,19);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
				header.addView(title,new android.widget.LinearLayout.LayoutParams(0,cgGlass.px(42),1f));
				android.widget.TextView close=cgGlass.label("×",28);close.setGravity(android.view.Gravity.CENTER);
				header.addView(close,new android.widget.LinearLayout.LayoutParams(cgGlass.px(42),cgGlass.px(42)));panel.addView(header);
				final android.widget.HorizontalScrollView seasons=new android.widget.HorizontalScrollView(PlayerActivity.this);
				seasons.setHorizontalScrollBarEnabled(false);seasons.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);seasons.setFillViewport(true);
				final android.widget.LinearLayout seasonRow=new android.widget.LinearLayout(PlayerActivity.this);
				seasonRow.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);seasonRow.setGravity(android.view.Gravity.RIGHT|android.view.Gravity.CENTER_VERTICAL);
				seasons.addView(seasonRow,new android.widget.HorizontalScrollView.LayoutParams(-2,cgGlass.px(52)));panel.addView(seasons);
				final android.widget.ScrollView scroll=new android.widget.ScrollView(PlayerActivity.this);scroll.setVerticalScrollBarEnabled(false);
				final android.widget.LinearLayout list=new android.widget.LinearLayout(PlayerActivity.this);list.setOrientation(1);list.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
				scroll.addView(list);panel.addView(scroll,new android.widget.LinearLayout.LayoutParams(-1,0,1f));
				final String[] selected={currentSeason[0].isEmpty()?"1":currentSeason[0]};
				final java.util.ArrayList<String> seasonNumbers=new java.util.ArrayList<String>();
				for(EpisodeData e:episodeItems){String s=e.season.isEmpty()?"1":e.season;if(!seasonNumbers.contains(s))seasonNumbers.add(s);}
				java.util.Collections.sort(seasonNumbers,new java.util.Comparator<String>(){
					@Override public int compare(String a,String b){try{return Integer.compare(Integer.parseInt(a),Integer.parseInt(b));}catch(Exception e){return a.compareTo(b);}}
				});
				{
					// The popup used to stay empty when the current season string did not match any
					// episode season exactly ("1" vs "01", stale value, ...). Resolve it properly.
					String cgWanted=selected[0];
					if(currentEpisodeIndex[0]>=0&&currentEpisodeIndex[0]<episodeItems.size()){
						String cgSeason=episodeItems.get(currentEpisodeIndex[0]).season;
						cgWanted=(cgSeason==null||cgSeason.trim().isEmpty())?"1":cgSeason;
					}
					if(!seasonNumbers.contains(cgWanted)){
						String cgNorm=cgWanted.trim().replaceFirst("^0+(?=.)","");
						String cgFound=null;
						for(String sn:seasonNumbers){if(sn.trim().replaceFirst("^0+(?=.)","").equals(cgNorm)){cgFound=sn;break;}}
						cgWanted=cgFound!=null?cgFound:(seasonNumbers.isEmpty()?"1":seasonNumbers.get(0));
					}
					selected[0]=cgWanted;
				}
				class PlaylistRenderer {
					android.view.View currentRow=null;
					void render() {
						currentRow=null;
						final int cgKeepSeasonX=seasons.getScrollX();
						seasonRow.removeAllViews();list.removeAllViews();
						// seasons are laid out left-to-right, so add them last-to-first: season 1 sits at the far RIGHT
						for(int cgSi=seasonNumbers.size()-1;cgSi>=0;cgSi--) {
							final String s=seasonNumbers.get(cgSi);
							android.widget.TextView chip=cgGlass.label("الموسم "+s,14);chip.setGravity(android.view.Gravity.CENTER);
							chip.setPadding(cgGlass.px(14),0,cgGlass.px(14),0);cgGlass.bind(chip,22,s.equals(selected[0]));cgGlass.press(chip);
							chip.setAlpha(s.equals(selected[0])?1f:.68f);
							android.widget.LinearLayout.LayoutParams lp=new android.widget.LinearLayout.LayoutParams(-2,cgGlass.px(40));lp.setMargins(cgGlass.px(3),cgGlass.px(6),cgGlass.px(3),cgGlass.px(6));seasonRow.addView(chip,lp);
							chip.setOnClickListener(new android.view.View.OnClickListener(){@Override public void onClick(android.view.View v){selected[0]=s;render();list.setAlpha(0f);list.animate().alpha(1f).setDuration(180L).start();}});
						}
						for(int i=0;i<episodeItems.size();i++) {
							final int index=i;final EpisodeData e=episodeItems.get(i);
							if(!selected[0].equals(e.season.isEmpty()?"1":e.season))continue;
							boolean current=i==currentEpisodeIndex[0];
							android.widget.LinearLayout row=new android.widget.LinearLayout(PlayerActivity.this);
							row.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);row.setGravity(android.view.Gravity.RIGHT|android.view.Gravity.CENTER_VERTICAL);
							row.setPadding(cgGlass.px(10),cgGlass.px(10),cgGlass.px(10),cgGlass.px(10));cgGlass.bind(row,18,current);cgGlass.press(row);
							// Compact landscape episode artwork distinguishes playlist cards from movie posters.
							android.widget.ImageView art=new android.widget.ImageView(PlayerActivity.this);art.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);cgGlass.bind(art,12,false);
							row.addView(art,new android.widget.LinearLayout.LayoutParams(cgGlass.px(96),cgGlass.px(54)));
							if(!e.poster.isEmpty())cgImage.load(e.poster,art,true);
							android.widget.LinearLayout info=new android.widget.LinearLayout(PlayerActivity.this);info.setOrientation(1);info.setGravity(android.view.Gravity.RIGHT);
							info.setPadding(cgGlass.px(8),0,cgGlass.px(12),0);
							android.widget.TextView number=cgGlass.label("الحلقة "+(e.episode.isEmpty()?String.valueOf(i+1):e.episode)+(current?"  •  قيد المشاهدة":""),15);
							info.addView(number);android.widget.TextView episodeTitle=cgGlass.label(e.title,12);episodeTitle.setAlpha(.7f);episodeTitle.setMaxLines(2);info.addView(episodeTitle);
							row.addView(info,new android.widget.LinearLayout.LayoutParams(0,-2,1f));
							android.widget.LinearLayout.LayoutParams lp=new android.widget.LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=cgGlass.px(8);list.addView(row,lp);
							if(current)currentRow=row;
							row.setOnClickListener(new android.view.View.OnClickListener(){@Override public void onClick(android.view.View v){
									if(episodesPopupHolder[0]!=null)episodesPopupHolder[0].dismiss();
									if(index==currentEpisodeIndex[0]){episodeController.open(index);return;}
									CigramEpisodeAds.beforeEpisode(PlayerActivity.this,
									new java.lang.Runnable(){@Override public void run(){try{exoPlayer.pause();}catch(Exception ignored){}}},
									new java.lang.Runnable(){@Override public void run(){
											if(isFinishing()||isDestroyed())return;
											episodeController.open(index);
										}});
								}});
						}
						if(list.getChildCount()==0){
							android.widget.TextView empty=cgGlass.label("لا توجد حلقات في هذا الموسم",15);
							empty.setGravity(android.view.Gravity.CENTER);empty.setAlpha(.7f);
							list.addView(empty,new android.widget.LinearLayout.LayoutParams(-1,cgGlass.px(120)));
						}
						seasons.post(new Runnable(){@Override public void run(){seasons.scrollTo(cgKeepSeasonX,0);}});
						if(currentRow!=null){
							final android.view.View target=currentRow;
							scroll.post(new Runnable(){@Override public void run(){scroll.scrollTo(0,Math.max(0,target.getTop()-cgGlass.px(8)));}});
						}
					}
				}
				new PlaylistRenderer().render();
				seasons.post(new Runnable(){@Override public void run(){seasonRow.setMinimumWidth(seasons.getWidth());seasons.scrollTo(seasonRow.getWidth(),0);seasons.fullScroll(android.view.View.FOCUS_RIGHT);}});
				final CigramGlass.Popup popup=cgGlass.popup(panel,playerHost,
				Math.min(cgGlass.px(460),(int)(getResources().getDisplayMetrics().widthPixels*.82f)),
				(int)(getResources().getDisplayMetrics().heightPixels*.95f),android.view.Gravity.RIGHT|android.view.Gravity.CENTER_VERTICAL);
				episodesPopupHolder[0]=popup;
				popup.setOnDismissListener(new android.widget.PopupWindow.OnDismissListener(){@Override public void onDismiss(){episodesPopupHolder[0]=null;}});
				close.setOnClickListener(new android.view.View.OnClickListener(){@Override public void onClick(android.view.View v){popup.dismiss();}});
			}
		});
		
		// Settings are populated from the active media's real tracks.
		final android.widget.PopupWindow[] settingsPopupHolder={null};
		final String[] cgQualityLabel={"تلقائي"};
		final int[] cgQualitySelection={-1,-1,-1};
		// CIGRAM: إعادة تطبيق آخر جودة اختارها المستخدم (أو «الجودة المفضلة» من الإعدادات) عند كل فيديو جديد.
		// v10.68: يعمل أيضاً مع الملف الواحد (MP4) إذا وُجدت نسخ بدقات أخرى بجانبه على الخادم (CigramQuality).
		final int[] cgQualityAppliedGen={-1};
		final String[] cgOriginUrl={""};
		final int[] cgOriginSize={0,0};
		final long[] cgSleepAt={0L};
		final int[] cgSleepChoice={0};
		final java.lang.Runnable[] cgSleepTask={null};
		final java.lang.Runnable cgQualityRestore=new java.lang.Runnable(){
			@Override public void run(){
				if(isFinishing()||isDestroyed())return;
				try{
					com.google.android.exoplayer2.Format cgNow=exoPlayer.getVideoFormat();
					if(cgNow!=null&&(cgNow.height>0||cgNow.width>0)&&!CigramQuality.isCopy(currentVideo[0])){
						cgOriginUrl[0]=currentVideo[0];cgOriginSize[0]=cgNow.height;cgOriginSize[1]=cgNow.width;
					}
					int wanted=CigramPlus.clampQuality(PlayerActivity.this,cgPlayerPrefs.getInt("quality_height",0));
					if(wanted>0&&cgQualityAppliedGen[0]!=mediaLoadGeneration[0]){
						com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo mapped=trackSelector.getCurrentMappedTrackInfo();
						if(mapped!=null){
							int bestR=-1,bestG=-1,bestT=-1,bestH=-1,minR=-1,minG=-1,minT=-1,minH=Integer.MAX_VALUE,count=0;
							for(int r=0;r<mapped.getRendererCount();r++){
								if(mapped.getRendererType(r)!=com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO)continue;
								com.google.android.exoplayer2.source.TrackGroupArray groups=mapped.getTrackGroups(r);
								for(int g=0;g<groups.length;g++)for(int k=0;k<groups.get(g).length;k++){
									if(mapped.getTrackSupport(r,g,k)!=com.google.android.exoplayer2.C.FORMAT_HANDLED)continue;
									int h=groups.get(g).getFormat(k).height;
									if(h<=0)continue;
									count++;
									if(h<=wanted&&h>bestH){bestH=h;bestR=r;bestG=g;bestT=k;}
									if(h<minH){minH=h;minR=r;minG=g;minT=k;}
								}
							}
							if(count>1){
								if(bestR<0){bestH=minH;bestR=minR;bestG=minG;bestT=minT;}
								com.google.android.exoplayer2.trackselection.DefaultTrackSelector.Parameters.Builder builder=trackSelector.buildUponParameters();
								for(int r=0;r<mapped.getRendererCount();r++)if(mapped.getRendererType(r)==com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO)builder.clearSelectionOverrides(r);
								builder.setSelectionOverride(bestR,mapped.getTrackGroups(bestR),
								new com.google.android.exoplayer2.trackselection.DefaultTrackSelector.SelectionOverride(bestG,bestT));
								trackSelector.setParameters(builder);
								cgQualitySelection[0]=bestR;cgQualitySelection[1]=bestG;cgQualitySelection[2]=bestT;
								cgQualityLabel[0]=CigramQuality.label(bestH);
							} else if(count==1&&cgNow!=null&&!CigramQuality.isCopy(currentVideo[0])
							&&exoPlayer.getPlaybackState()==com.google.android.exoplayer2.Player.STATE_READY){
								// ملف واحد: ننتقل إلى نسخة بالدقة المفضلة إن كانت موجودة على الخادم
								java.util.List<CigramQuality.Variant> copies=CigramQuality.variants(currentVideo[0]);
								if(copies==null){
									CigramQuality.probe(currentVideo[0],null);
									count=0;   // نعيد المحاولة عند انتهاء البحث
								} else {
									int own=CigramQuality.classify(cgNow.height,cgNow.width);
									CigramQuality.Variant best=null;
									if(own>wanted)for(CigramQuality.Variant v:copies){
										if(v.height<own&&v.height<=wanted&&(best==null||v.height>best.height))best=v;
									}
									if(best!=null){
										cgQualityAppliedGen[0]=mediaLoadGeneration[0]+1;
										cgQualitySelection[0]=-1;cgQualitySelection[1]=-1;cgQualitySelection[2]=-1;
										cgQualityLabel[0]=CigramQuality.label(best.height);
										mediaLoader.load(best.url,currentSubtitle[0],Math.max(0L,exoPlayer.getCurrentPosition()));
										uiHandler.postDelayed(this,700L);
										return;
									}
								}
							}
							if(count>0)cgQualityAppliedGen[0]=mediaLoadGeneration[0];
						}
					}
				}catch(Throwable ignored){}
				uiHandler.postDelayed(this,700L);
			}
		};
		uiHandler.postDelayed(cgQualityRestore,1200L);
		settingsButton.setOnClickListener(new android.view.View.OnClickListener(){
			int cgVideoTrackCount(){
				int n=0;
				try{
					com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo m=trackSelector.getCurrentMappedTrackInfo();
					if(m!=null)for(int r=0;r<m.getRendererCount();r++){
						if(m.getRendererType(r)!=com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO)continue;
						com.google.android.exoplayer2.source.TrackGroupArray g=m.getTrackGroups(r);
						for(int i=0;i<g.length;i++)for(int t=0;t<g.get(i).length;t++)if(m.getTrackSupport(r,i,t)==com.google.android.exoplayer2.C.FORMAT_HANDLED)n++;
					}
				}catch(Throwable ignored){}
				return n;
			}
			String cgSleepText(){
				if(cgSleepAt[0]<=0L)return "إيقاف";
				long left=Math.max(0L,cgSleepAt[0]-android.os.SystemClock.elapsedRealtime());
				return Math.max(1L,(left+59999L)/60000L)+" دقيقة";
			}
			@Override public void onClick(android.view.View v) {
				if(settingsPopupHolder[0]!=null&&settingsPopupHolder[0].isShowing()){settingsPopupHolder[0].dismiss();return;}
				// CIGRAM v10.68: لوحة إعدادات مفصّلة ومقسّمة — الصورة، الصوت والترجمة، التشغيل، الحلقات.
				final CigramPlayerSheet sheet=new CigramPlayerSheet(PlayerActivity.this,playerHost,"إعدادات المشاهدة");
				final com.google.android.exoplayer2.Format currentFormat=exoPlayer.getVideoFormat();
				final boolean cgHasFormat=currentFormat!=null&&(currentFormat.height>0||currentFormat.width>0);
				if(cgHasFormat&&!CigramQuality.isCopy(currentVideo[0])){
					cgOriginUrl[0]=currentVideo[0];cgOriginSize[0]=currentFormat.height;cgOriginSize[1]=currentFormat.width;
				}
				// أبعاد الملف الأصلي معروفة فقط إذا كان هو نفسه الذي شُغّل آخر مرة
				final int cgOrigH=CigramQuality.originOf(currentVideo[0]).equals(cgOriginUrl[0])?cgOriginSize[0]:0;
				final int cgOrigW=CigramQuality.originOf(currentVideo[0]).equals(cgOriginUrl[0])?cgOriginSize[1]:0;
				String cgQualityNow=cgHasFormat?CigramQuality.label(currentFormat.height,currentFormat.width):cgQualityLabel[0];
				if(cgQualitySelection[0]<0&&cgHasFormat&&!CigramQuality.isCopy(currentVideo[0])&&cgVideoTrackCount()>1)cgQualityNow="تلقائي · "+cgQualityNow;
				
				// ------------------------------------------------------------- الصورة
				sheet.section("الصورة");
				sheet.item("جودة الفيديو",cgQualityNow,new CigramPlayerSheet.Source(){
					com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo mapped;
					final java.util.ArrayList<int[]> tracks=new java.util.ArrayList<int[]>();
					final java.util.ArrayList<String> labels=new java.util.ArrayList<String>();
					final java.util.ArrayList<String> urls=new java.util.ArrayList<String>();
					final java.util.ArrayList<Integer> heights=new java.util.ArrayList<Integer>();
					@Override public CigramPlayerSheet.Options load(){
						tracks.clear();labels.clear();urls.clear();heights.clear();
						mapped=trackSelector.getCurrentMappedTrackInfo();
						// 1) بثّ HLS / DASH: كل الدقات الموجودة داخل البث نفسه
						if(mapped!=null){
							final java.util.ArrayList<int[]> found=new java.util.ArrayList<int[]>();
							for(int r=0;r<mapped.getRendererCount();r++){
								if(mapped.getRendererType(r)!=com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO)continue;
								com.google.android.exoplayer2.source.TrackGroupArray groups=mapped.getTrackGroups(r);
								for(int g=0;g<groups.length;g++)for(int t=0;t<groups.get(g).length;t++){
									if(mapped.getTrackSupport(r,g,t)!=com.google.android.exoplayer2.C.FORMAT_HANDLED)continue;
									com.google.android.exoplayer2.Format f=groups.get(g).getFormat(t);
									found.add(new int[]{r,g,t,Math.max(0,f.height),Math.max(0,f.width),Math.max(0,f.bitrate)});
								}
							}
							if(found.size()>1){
								java.util.Collections.sort(found,new java.util.Comparator<int[]>(){
									@Override public int compare(int[] a,int[] b){return a[3]!=b[3]?b[3]-a[3]:b[5]-a[5];}
								});
								labels.add("تلقائي · موصى به (حسب سرعة الإنترنت)");tracks.add(new int[]{-1,-1,-1});urls.add(null);heights.add(0);
								java.util.HashSet<String> seen=new java.util.HashSet<String>();
								boolean cgLockedAny=false; for(int[] f:found){
									if(!CigramQuality.offered(f[3],f[4]))continue;   // 2026: only 720p / 1080p / 2K / 4K are named
									if(!CigramPlus.isPlus(PlayerActivity.this)&&CigramQuality.classify(f[3],f[4])>=1080){cgLockedAny=true;continue;} String l=CigramQuality.fullLabel(f[3],f[4]);
									if(!seen.add(l))continue;   // نفس الدقة بمعدل بت أقل لا تُعرض مرتين
									labels.add(l);tracks.add(new int[]{f[0],f[1],f[2]});urls.add(null);heights.add(f[3]);
								} if(cgLockedAny){labels.add("🔒 1080p – 4K · ضمن Plus");tracks.add(new int[]{-2,-2,-2});urls.add(null);heights.add(-1);}
								int selected=0;
								for(int i=1;i<tracks.size();i++)if(java.util.Arrays.equals(tracks.get(i),cgQualitySelection))selected=i;
								return new CigramPlayerSheet.Options(labels.toArray(new String[0]),selected);
							}
						}
						// 2) ملف واحد (MP4 / MKV): النسخة الأصلية + نسخ الدقات الأخرى الموجودة بجانبها على الخادم
						String origin=CigramQuality.originOf(currentVideo[0]);
						java.util.List<CigramQuality.Variant> copies=CigramQuality.variants(origin);
						int originClass=CigramQuality.classify(cgOrigH,cgOrigW);
						labels.add("الأصلية"+(cgOrigH>0||cgOrigW>0?" · "+CigramQuality.fullLabel(cgOrigH,cgOrigW):""));
						tracks.add(null);urls.add(origin);heights.add(0);
						boolean cgLocked2=false,cgLowerCopy=false; if(copies!=null)for(CigramQuality.Variant v:copies){
							if(originClass>0&&v.height>=originClass)continue; if(!CigramQuality.offered(v.height,0))continue; if(v.height<1080)cgLowerCopy=true; if(!CigramPlus.isPlus(PlayerActivity.this)&&v.height>=1080){cgLocked2=true;continue;}   // لا نعرض دقة أعلى من الأصل أو مساوية له
							labels.add(CigramQuality.fullLabel(v.height,0));tracks.add(null);urls.add(v.url);heights.add(v.height);
						} if(!CigramPlus.isPlus(PlayerActivity.this)&&originClass>=1080&&cgLowerCopy){labels.set(0,"🔒 "+labels.get(0));heights.set(0,Integer.valueOf(-1));} if(cgLocked2){labels.add("🔒 1080p – 4K · ضمن Plus");tracks.add(null);urls.add(null);heights.add(-1);}
						int selected=0;
						for(int i=0;i<urls.size();i++)if(urls.get(i)!=null&&urls.get(i).equals(currentVideo[0]))selected=i;
						String hint="";
						if(copies==null||CigramQuality.probing(origin)){
							hint="جارٍ البحث عن دقات أخرى لهذا الفيلم… أعد فتح القائمة بعد لحظات";
							CigramQuality.probe(origin,null);
						} else if(urls.size()==1){
							hint="هذا الفيلم متوفر بدقة واحدة فقط"+(originClass>0?" ("+CigramQuality.fullLabel(cgOrigH,cgOrigW)+")":"");
						}
						return new CigramPlayerSheet.Options(labels.toArray(new String[0]),selected,hint);
					}
					@Override public String pick(int which){
						if(which<0||which>=labels.size())return null; if(heights.get(which).intValue()==-1){CigramPlusUI.requirePlus(PlayerActivity.this,"جودة 1080p إلى 4K");return null;}
						String url=urls.get(which);
						if(url!=null){
							// تبديل الملف مع الحفاظ على مكان المشاهدة والترجمة
							if(url.equals(currentVideo[0]))return labels.get(which);
							long cgAt=Math.max(0L,exoPlayer.getCurrentPosition());
							cgQualitySelection[0]=-1;cgQualitySelection[1]=-1;cgQualitySelection[2]=-1;
							cgQualityLabel[0]=which==0?"الأصلية":CigramQuality.label(heights.get(which));
							cgPlayerPrefs.edit().putInt("quality_height",heights.get(which)).apply();
							cgQualityAppliedGen[0]=mediaLoadGeneration[0]+1;
							mediaLoader.load(url,currentSubtitle[0],cgAt);
							return cgQualityLabel[0];
						}
						if(mapped==null)return null;
						int[] choice=tracks.get(which);
						com.google.android.exoplayer2.trackselection.DefaultTrackSelector.Parameters.Builder builder=trackSelector.buildUponParameters();
						// Clear constraints and only video overrides; leave audio/subtitle state intact.
						for(int r=0;r<mapped.getRendererCount();r++)if(mapped.getRendererType(r)==com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO)builder.clearSelectionOverrides(r);
						builder.setMinVideoSize(0,0).setMaxVideoSize(Integer.MAX_VALUE,CigramPlus.maxVideoHeight(PlayerActivity.this))
						.setMinVideoBitrate(0).setMaxVideoBitrate(Integer.MAX_VALUE).setForceLowestBitrate(false).setForceHighestSupportedBitrate(false);
						if(choice[0]>=0)builder.setSelectionOverride(choice[0],mapped.getTrackGroups(choice[0]),
						new com.google.android.exoplayer2.trackselection.DefaultTrackSelector.SelectionOverride(choice[1],choice[2]));
						trackSelector.setParameters(builder);
						System.arraycopy(choice,0,cgQualitySelection,0,3);
						cgQualityLabel[0]=choice[0]<0?"تلقائي":CigramQuality.label(heights.get(which));
						cgPlayerPrefs.edit().putInt("quality_height",choice[0]<0?0:heights.get(which)).apply();
						return cgQualityLabel[0];
					}
				});
				final String[] resizeLabels={"متناسق","ملء الشاشة","تكبير"};
				final int[] resizeModes={com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
					com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM};
				int resizeIndex=0;for(int i=0;i<resizeModes.length;i++)if(exoView.getResizeMode()==resizeModes[i])resizeIndex=i;
				sheet.item("أبعاد الشاشة",resizeLabels[resizeIndex],new CigramPlayerSheet.Source(){
					@Override public CigramPlayerSheet.Options load(){
						int selected=0;for(int i=0;i<resizeModes.length;i++)if(exoView.getResizeMode()==resizeModes[i])selected=i;
						return new CigramPlayerSheet.Options(resizeLabels,selected);
					}
					@Override public String pick(int which){
						exoView.setResizeMode(resizeModes[which]);
						CigramStage1InitProvider.applyPlayerEdgeToEdge(PlayerActivity.this);
						return resizeLabels[which];
					}
				});
				if(cgHasFormat){
					String cgCodec=currentFormat.sampleMimeType==null?"":currentFormat.sampleMimeType.toLowerCase(java.util.Locale.US);
					cgCodec=cgCodec.contains("hevc")||cgCodec.contains("h265")?"H.265":cgCodec.contains("avc")||cgCodec.contains("h264")?"H.264"
					:cgCodec.contains("av01")||cgCodec.contains("av1")?"AV1":cgCodec.contains("vp9")?"VP9":"";
					String cgInfoText=currentFormat.width+"×"+currentFormat.height;
					if(cgCodec.length()>0)cgInfoText+=" · "+cgCodec;
					if(currentFormat.frameRate>0)cgInfoText+=" · "+Math.round(currentFormat.frameRate)+"fps";
					sheet.info("معلومات الفيديو",cgInfoText);
				}
				
				// ---------------------------------------------------- الصوت والترجمة
				sheet.section("الصوت والترجمة");
				final java.util.ArrayList<int[]> cgAudio=new java.util.ArrayList<int[]>();
				final java.util.ArrayList<String> cgAudioLabels=new java.util.ArrayList<String>();
				final com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo cgAudioMap=trackSelector.getCurrentMappedTrackInfo();
				if(cgAudioMap!=null){
					for(int r=0;r<cgAudioMap.getRendererCount();r++){
						if(cgAudioMap.getRendererType(r)!=com.google.android.exoplayer2.C.TRACK_TYPE_AUDIO)continue;
						com.google.android.exoplayer2.source.TrackGroupArray groups=cgAudioMap.getTrackGroups(r);
						for(int g=0;g<groups.length;g++){
							if(cgAudioMap.getTrackSupport(r,g,0)!=com.google.android.exoplayer2.C.FORMAT_HANDLED)continue;
							com.google.android.exoplayer2.Format f=groups.get(g).getFormat(0);
							String lang=f.label!=null&&f.label.length()>0?f.label:(f.language==null||"und".equals(f.language)?"صوت "+(cgAudio.size()+1)
							:new java.util.Locale(f.language).getDisplayLanguage(new java.util.Locale("ar")));
							String ch=f.channelCount>=6?" · 5.1":f.channelCount==2?" · ستيريو":f.channelCount==1?" · أحادي":"";
							cgAudio.add(new int[]{r,g});cgAudioLabels.add(lang+ch);
						}
					}
				}
				if(cgAudio.size()>1){
					int cgAudioNow=0;
					com.google.android.exoplayer2.Format cgAf=exoPlayer.getAudioFormat();
					for(int i=0;i<cgAudio.size()&&cgAf!=null;i++){
						if(cgAf.equals(cgAudioMap.getTrackGroups(cgAudio.get(i)[0]).get(cgAudio.get(i)[1]).getFormat(0)))cgAudioNow=i;
					}
					final int[] cgAudioSel={cgAudioNow};
					sheet.item("مسار الصوت",cgAudioLabels.get(cgAudioNow),new CigramPlayerSheet.Source(){
						@Override public CigramPlayerSheet.Options load(){return new CigramPlayerSheet.Options(cgAudioLabels.toArray(new String[0]),cgAudioSel[0]);}
						@Override public String pick(int which){
							int[] a=cgAudio.get(which);
							com.google.android.exoplayer2.trackselection.DefaultTrackSelector.Parameters.Builder builder=trackSelector.buildUponParameters();
							builder.clearSelectionOverrides(a[0]);
							builder.setSelectionOverride(a[0],cgAudioMap.getTrackGroups(a[0]),
							new com.google.android.exoplayer2.trackselection.DefaultTrackSelector.SelectionOverride(a[1],0));
							trackSelector.setParameters(builder);
							cgAudioSel[0]=which;
							return cgAudioLabels.get(which);
						}
					});
				} else if(cgAudio.size()==1){
					sheet.info("الصوت",cgAudioLabels.get(0));
				}
				sheet.toggle("الترجمة",subtitlesEnabled[0],new CigramPlayerSheet.Toggle(){
					@Override public boolean toggle(){
						subtitlesEnabled[0]=!subtitlesEnabled[0];
						subtitlePrefs.edit().putBoolean("subtitle_enabled",subtitlesEnabled[0]).apply();
						if(subtitleView!=null)subtitleView.setVisibility(subtitlesEnabled[0]&&!currentSubtitle[0].isEmpty()
						?android.view.View.VISIBLE:android.view.View.GONE);
						if(subtitlesEnabled[0])applySubtitleStyle.run();
						return subtitlesEnabled[0];
					}
				});
				final float[] cgSubScales={0.8f,1f,1.25f,1.5f};
				final String[] cgSubLabels={"صغير","متوسط","كبير","أكبر"};
				int cgSubNow=1;float cgSubPref=subtitlePrefs.getFloat("subtitle_scale",1f);
				for(int i=0;i<cgSubScales.length;i++)if(Math.abs(cgSubScales[i]-cgSubPref)<0.01f)cgSubNow=i;
				final int cgSubSel=cgSubNow;
				sheet.item("حجم الترجمة",cgSubLabels[cgSubNow],new CigramPlayerSheet.Source(){
					@Override public CigramPlayerSheet.Options load(){
						int sel=cgSubSel;float now=subtitlePrefs.getFloat("subtitle_scale",1f);
						for(int i=0;i<cgSubScales.length;i++)if(Math.abs(cgSubScales[i]-now)<0.01f)sel=i;
						return new CigramPlayerSheet.Options(cgSubLabels,sel);
					}
					@Override public String pick(int which){
						subtitlePrefs.edit().putFloat("subtitle_scale",cgSubScales[which]).apply();
						applySubtitleStyle.run();
						return cgSubLabels[which];
					}
				});
				// 18.2: «توقيت الترجمة» — the viewer's delay (CigramSubtitleSyncEngine), presets + fine tuning with preview
				if(cgSubEngine[0]!=null&&cgSubEngine[0].isActive()){
					final long[] cgDelayValues={Long.MIN_VALUE,-2000L,-1000L,-500L,-250L,0L,250L,500L,1000L,2000L};
					final String[] cgDelayLabels={"تعديل دقيق مع معاينة…","\u22122 ث","\u22121 ث","\u22120.5 ث","\u22120.25 ث","0 (متزامنة)","+0.25 ث","+0.5 ث","+1 ث","+2 ث"};
					long cgDelayNow=cgSubEngine[0].delayMs();
					sheet.item("توقيت الترجمة",cgDelayNow==0?"متزامنة":com.Cigram.subhub.SubSync.delayLabel(cgDelayNow),new CigramPlayerSheet.Source(){
						@Override public CigramPlayerSheet.Options load(){
							int sel=-1;long now=cgSubEngine[0].delayMs();
							for(int i=1;i<cgDelayValues.length;i++)if(cgDelayValues[i]==now)sel=i;
							return new CigramPlayerSheet.Options(cgDelayLabels,sel,"إذا ظهر النص قبل الكلام اختر «+». يُطبَّق فوراً ويُحفظ لهذا الملف فقط.");
						}
						@Override public String pick(int which){
							if(which==0){
								// dismiss AFTER the sheet finished handling this tap (closeChoices runs right after pick)
								uiHandler.postDelayed(new java.lang.Runnable(){@Override public void run(){
									try{sheet.dismiss();}catch(Throwable ignored){}
									uiHandler.postDelayed(new java.lang.Runnable(){@Override public void run(){CigramSubtitleDelayPanel.show(PlayerActivity.this,cgSubEngine[0]);}},300L);
								}},120L);
								return null;
							}
							cgSubEngine[0].setDelayMs(cgDelayValues[which]);
							long v=cgSubEngine[0].delayMs();
							return v==0?"متزامنة":com.Cigram.subhub.SubSync.delayLabel(v);
						}
					});
				}
				
				// ------------------------------------------------------------ التشغيل
				sheet.section("التشغيل");
				final float[] speedValues={.5f,.75f,1f,1.25f,1.5f,1.75f,2f};
				final String[] speedLabels={"0.5x","0.75x","1x (عادي)","1.25x","1.5x","1.75x","2x"};
				float cgSpeedNow=exoPlayer.getPlaybackParameters().speed;
				String cgSpeedText=cgSpeedNow+"x";
				for(int i=0;i<speedValues.length;i++)if(speedValues[i]==cgSpeedNow)cgSpeedText=speedLabels[i];
				sheet.item("سرعة العرض",cgSpeedText,new CigramPlayerSheet.Source(){
					@Override public CigramPlayerSheet.Options load(){
						int selected=2;for(int i=0;i<speedValues.length;i++)if(speedValues[i]==exoPlayer.getPlaybackParameters().speed)selected=i;
						return new CigramPlayerSheet.Options(speedLabels,selected);
					}
					@Override public String pick(int which){
						exoPlayer.setPlaybackSpeed(speedValues[which]);cgPlayerPrefs.edit().putFloat("speed",speedValues[which]).apply();
						return speedLabels[which];
					}
				});
				final long[] seekSteps={5000L,10000L,15000L,30000L};
				final String[] seekLabels={"5 ث","10 ث","15 ث","30 ث"};
				sheet.item("فترة التمرير",(seekStepMs[0]/1000L)+" ثوانٍ",new CigramPlayerSheet.Source(){
					@Override public CigramPlayerSheet.Options load(){
						int sel=1;for(int i=0;i<seekSteps.length;i++)if(seekSteps[i]==seekStepMs[0])sel=i;
						return new CigramPlayerSheet.Options(seekLabels,sel);
					}
					@Override public String pick(int which){
						seekStepMs[0]=seekSteps[which];cgPlayerPrefs.edit().putLong("seek_step",seekSteps[which]).apply();
						// الرقم داخل زرّي التقديم والترجيع يتبع الفترة الجديدة
						rewindButton.invalidate();forwardButton.invalidate();
						rewindButton.setContentDescription("رجوع "+(seekSteps[which]/1000L)+" ثوانٍ");
						forwardButton.setContentDescription("تقديم "+(seekSteps[which]/1000L)+" ثوانٍ");
						return (seekSteps[which]/1000L)+" ثوانٍ";
					}
				});
				// ------------------------------------------------------------ تعزيز الصوت (فوق الحد الافتراضي)
				sheet.item("تعزيز الصوت",CigramAudioBoost.label(PlayerActivity.this),new CigramPlayerSheet.Source(){
					@Override public CigramPlayerSheet.Options load(){
						return new CigramPlayerSheet.Options(CigramAudioBoost.LABELS,CigramAudioBoost.level(PlayerActivity.this),
						"يرفع مستوى الصوت فوق الأقصى. عند المستويات العالية قد يتشوّه الصوت في المشاهد الصاخبة.");
					}
					@Override public String pick(int which){
						CigramAudioBoost.setLevel(PlayerActivity.this,which);
						try{CigramAudioBoost.sync(PlayerActivity.this,exoPlayer.getAudioSessionId());}catch(Throwable ignored){}
						return CigramAudioBoost.LABELS[which];
					}
				});
				final long[] cgSleepMinutes={0L,15L,30L,45L,60L,-1L};
				final String[] cgSleepLabels={"إيقاف","15 دقيقة","30 دقيقة","45 دقيقة","ساعة","عند نهاية "+(isSeries?"الحلقة":"الفيلم")};
				sheet.item("مؤقت الإيقاف",cgSleepText(),new CigramPlayerSheet.Source(){
					@Override public CigramPlayerSheet.Options load(){
						return new CigramPlayerSheet.Options(cgSleepLabels,cgSleepChoice[0],
						cgSleepAt[0]>0L?"يتوقف التشغيل بعد "+cgSleepText():"");
					}
					@Override public String pick(int which){
						if(cgSleepTask[0]!=null)uiHandler.removeCallbacks(cgSleepTask[0]);
						cgSleepTask[0]=null;cgSleepAt[0]=0L;cgSleepChoice[0]=which;
						long wait=cgSleepMinutes[which]*60000L;
						if(cgSleepMinutes[which]<0L){
							long d=exoPlayer.getDuration(),p=exoPlayer.getCurrentPosition();
							float sp=Math.max(.25f,exoPlayer.getPlaybackParameters().speed);
							wait=d>0L&&d!=com.google.android.exoplayer2.C.TIME_UNSET?(long)((d-p)/sp)-1500L:0L;
							if(wait<=0L){cgSleepChoice[0]=0;android.widget.Toast.makeText(PlayerActivity.this,"مدة الفيديو غير معروفة بعد",android.widget.Toast.LENGTH_SHORT).show();return cgSleepLabels[0];}
						}
						if(wait>0L){
							cgSleepAt[0]=android.os.SystemClock.elapsedRealtime()+wait;
							cgSleepTask[0]=new java.lang.Runnable(){@Override public void run(){
									cgSleepAt[0]=0L;cgSleepChoice[0]=0;cgSleepTask[0]=null;
									if(isFinishing()||isDestroyed())return;
									try{exoPlayer.pause();}catch(Throwable ignored){}
									android.widget.Toast.makeText(PlayerActivity.this,"تم إيقاف التشغيل بواسطة المؤقت",android.widget.Toast.LENGTH_LONG).show();
								}};
							uiHandler.postDelayed(cgSleepTask[0],wait);
						}
						return cgSleepLabels[which];
					}
				});
				if(!skipSegments.isEmpty()) {
					sheet.toggle("تخطي المشاهد المحددة",skipScenesEnabled[0],new CigramPlayerSheet.Toggle(){
						@Override public boolean toggle(){skipScenesEnabled[0]=!skipScenesEnabled[0];return skipScenesEnabled[0];}
					});
				}
				sheet.setOnDismiss(new java.lang.Runnable(){@Override public void run(){settingsPopupHolder[0]=null;}});
				sheet.show();
				settingsPopupHolder[0]=sheet.window();
			}
		});
		
		// SHOW / HIDE CONTROLS
		// Keep controls visible when paused, buffering, or ended.
		// No Player.Listener is registered: the existing Handler checks the state.
		// =====================================================
		
		final android.view.View[] cgControlViews = {
			centerControls,
			bottomControls,
			settingsButton,
			seriesInfo,
			episodesButton
		};
		// v6.0 design 5: «قفل الشاشة» on the left edge, follows the controls' show / hide
		try {
			CigramPlayerLock.attach(PlayerActivity.this, playerHost, cgControlViews);
		} catch (Throwable ignored) {}
		final boolean[] cgControlsVisible = {true};
		
		final java.lang.Runnable hideControls = new java.lang.Runnable() {
			@Override public void run() {
				if (isFinishing() || isDestroyed() || dragging[0]
				|| !exoPlayer.isPlaying()
				|| exoPlayer.getPlaybackState() != com.google.android.exoplayer2.Player.STATE_READY
				|| (settingsPopupHolder[0] != null && settingsPopupHolder[0].isShowing())
				|| (episodesPopupHolder[0] != null && episodesPopupHolder[0].isShowing())) {
					return;
				}
				cgControlsVisible[0] = false;
				for (final android.view.View control : cgControlViews) {
					if (!isSeries && (control == seriesInfo || control == episodesButton)) continue;
					control.animate().cancel();
					control.animate().alpha(0f).setDuration(200L).withEndAction(
					new java.lang.Runnable() {
						@Override public void run() {
							if (!cgControlsVisible[0]) control.setVisibility(android.view.View.GONE);
						}
					}
					).start();
				}
			}
		};
		hideControlsHolder[0] = hideControls;
		
		final java.lang.Runnable showPlayerControls = new java.lang.Runnable() {
			@Override public void run() {
				uiHandler.removeCallbacks(hideControls);
				cgControlsVisible[0] = true;
				for (android.view.View control : cgControlViews) {
					if (!isSeries && (control == seriesInfo || control == episodesButton)) {
						control.setVisibility(android.view.View.GONE);
						continue;
					}
					control.animate().cancel();
					control.setVisibility(android.view.View.VISIBLE);
					control.animate().alpha(1f).setDuration(160L).start();
				}
				if (exoPlayer.isPlaying()
				&& exoPlayer.getPlaybackState() == com.google.android.exoplayer2.Player.STATE_READY) {
					uiHandler.postDelayed(hideControls, 3000L);
				}
			}
		};
		
		final java.lang.Runnable cgControlStateWatcher = new java.lang.Runnable() {
			boolean previousCanHide = false;
			@Override public void run() {
				if (isFinishing() || isDestroyed()) return;
				boolean canHide = exoPlayer.isPlaying()
				&& exoPlayer.getPlaybackState() == com.google.android.exoplayer2.Player.STATE_READY;
				if (canHide != previousCanHide) {
					previousCanHide = canHide;
					if (canHide) {
						uiHandler.removeCallbacks(hideControls);
						if (cgControlsVisible[0]) uiHandler.postDelayed(hideControls, 3000L);
					} else {
						showPlayerControls.run();
					}
				}
				uiHandler.postDelayed(this, 400L);
			}
		};
		uiHandler.post(cgControlStateWatcher);
		
		// =====================================================
		// CIGRAM TV REMOTE (v10.66) — TV only, a phone never enters this block.
		// OK / UP / DOWN bring the controls; LEFT / RIGHT seek while they are hidden; play/pause,
		// fast forward / rewind and next / previous keys work at any time; Back first hides the
		// controls. Every key runs the same action as the matching button on the screen.
		// =====================================================
		try {
			if (CigramTvMode.isTv(PlayerActivity.this)) {
				CigramTvKeys.player(PlayerActivity.this, new CigramTvKeys.PlayerHost() {
					@Override public boolean controlsVisible() { return cgControlsVisible[0]; }
					@Override public void showControls() { showPlayerControls.run(); }
					@Override public void hideControls() {
						uiHandler.removeCallbacks(hideControls);
						hideControls.run();
					}
					@Override public boolean isPlaying() {
						try { return exoPlayer.isPlaying(); } catch (Throwable cgTvError) { return false; }
					}
					@Override public void playPause() { playButton.performClick(); }
					@Override public void rewind() { rewindButton.performClick(); }
					@Override public void forward() { forwardButton.performClick(); }
					@Override public void next() {
						if (nextButton.getVisibility() == android.view.View.VISIBLE && nextButton.isEnabled()) {
							nextButton.performClick();
						}
					}
					@Override public void previous() {
						if (previousButton.getVisibility() == android.view.View.VISIBLE && previousButton.isEnabled()) {
							previousButton.performClick();
						}
					}
					@Override public android.view.View focusTarget() { return playButton; }
				});
			}
		} catch (Throwable cgTvIgnored) { }
		
		final android.view.GestureDetector playerGestureDetector =
		new android.view.GestureDetector(
		PlayerActivity.this,
		new android.view.GestureDetector.SimpleOnGestureListener() {
			
			@Override
			public boolean onDown(
			android.view.MotionEvent event
			) {
				
				return true;
			}
			
			
			@Override
			public boolean onSingleTapConfirmed(
			android.view.MotionEvent event
			) {
				
				uiHandler.removeCallbacks(
				hideControls
				);
				
				
				if (
				centerControls.getVisibility()
				==
				android.view.View.VISIBLE
				) {
					
					hideControls.run();
					
				} else {
					
					showPlayerControls.run();
				}
				
				
				return true;
			}
			
			
			@Override
			public boolean onDoubleTap(
			android.view.MotionEvent event
			) {
				
				if (
				event == null
				) {
					return true;
				}
				
				
				float half =
				exoView.getWidth()
				/
				2f;
				
				
				long current =
				Math.max(
				0L,
				exoPlayer.getCurrentPosition()
				);
				
				
				long duration =
				exoPlayer.getDuration();
				
				
				if (
				event.getX()
				<
				half
				) {
					
					long target =
					Math.max(
					0L,
					current - seekStepMs[0]
					);
					
					
					exoPlayer.seekTo(
					target
					);
					
					
					seekFeedbackController.show(
					"-"
					+
					(seekStepMs[0] / 1000L)
					+
					" ث"
					);
					
				} else {
					
					long target =
					current
					+
					seekStepMs[0];
					
					
					if (
					duration > 0L
					&&
					duration
					!=
					com.google.android.exoplayer2.C.TIME_UNSET
					) {
						
						target =
						Math.min(
						target,
						duration
						);
					}
					
					
					exoPlayer.seekTo(
					Math.max(
					0L,
					target
					)
					);
					
					
					seekFeedbackController.show(
					"+"
					+
					(seekStepMs[0] / 1000L)
					+
					" ث"
					);
				}
				
				
				/*
                 * بعد Double Tap نخلي الأدوات مخفية
                 * حتى تبقى المشاهدة نظيفة.
                 */
				uiHandler.removeCallbacks(
				hideControls
				);
				
				
				return true;
			}
		}
		);
		
		
		exoView.setClickable(
		true
		);
		
		
		exoView.setOnTouchListener(
		new android.view.View.OnTouchListener() {
			
			@Override
			public boolean onTouch(
			android.view.View view,
			android.view.MotionEvent event
			) {
				
				return playerGestureDetector.onTouchEvent(
				event
				);
			}
		}
		);
		
		// =====================================================
		// NO CUSTOM Player.Listener
		// =====================================================
		//
		// مهم في Sketchware:
		// لا نضيف Player.Listener مخصص هنا.
		// بعض بيئات D8/Desugar قد تتعثر مع default callback methods.
		// متابعة الحالة تتم من updateProgress بالـ Handler فقط.
		
		// =====================================================
		// INITIAL LOAD
		// =====================================================
		
		long savedPosition =
		watchProgress.getLong(
		watchProgressKey[0],
		0L
		);
		if (savedPosition <= 0L) {
			savedPosition = continueWatchingStore.savedPositionForCurrent();
			if (savedPosition > 0L) watchProgress.edit().putLong(watchProgressKey[0], savedPosition).apply();
		}
		// v10.62: آخر موضع محفوظ في الحساب (من جهاز آخر) إذا لم يوجد موضع على هذا الجهاز
		if (savedPosition <= 0L) {
			try {
				savedPosition = CigramResume.position(getApplicationContext(), isSeries,
				seriesTitle, mediaTitle, mediaId, currentSeason[0], currentEpisode[0]);
			} catch (Throwable cgResumeError) { savedPosition = 0L; }
		}
		// TV details page: «من البداية» ignores whatever position was saved
		if (getIntent().getBooleanExtra("cigram_start_over", false)) savedPosition = 0L;
		
		if (!currentVideo[0].equals("")) {
			mediaLoader.load(
			currentVideo[0],
			currentSubtitle[0],
			savedPosition
			);
			
		} else {
			android.widget.Toast.makeText(
			getApplicationContext(),
			"رابط الفيديو فارغ",
			android.widget.Toast.LENGTH_LONG
			).show();
		}
		
		// =====================================================
		// IMMERSIVE RESTORE
		// =====================================================
		
		getWindow().getDecorView()
		.setOnSystemUiVisibilityChangeListener(
		new android.view.View.OnSystemUiVisibilityChangeListener() {
			@Override
			public void onSystemUiVisibilityChange(
			int visibility
			) {
				getWindow().getDecorView().setSystemUiVisibility(
				android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
				| android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
				| android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
				| android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
				| android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
				| android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
				);
			}
		}
		);
		
		// =====================================================
		// RELEASE
		// =====================================================
		
		exoView.addOnAttachStateChangeListener(
		new android.view.View.OnAttachStateChangeListener() {
			
			@Override
			public void onViewAttachedToWindow(
			android.view.View view
			) {}
			
			@Override
			public void onViewDetachedFromWindow(
			android.view.View view
			) {
				episodeController.saveCurrentProgress();
				
				uiHandler.removeCallbacksAndMessages(null);
				
				if (subtitleView != null) {
					subtitleView.setVisibility(
					android.view.View.GONE
					);
				}
				
				exoView.setPlayer(null);
				
				try {
					exoPlayer.release();
				} catch (Exception ignored) {}
			}
		}
		);
		
		// The episode cover must not register Player.Listener here.  Sketchware's D8
		// build has previously crashed inside ExoPlayer ListenerSet when a new episode
		// is selected.  A lightweight Handler poll keeps the transition visual while
		// leaving ExoPlayer's listener set untouched.
		// v5.29: no poster + play cover between episodes: the next episode starts playing at once.
		if(false&&getIntent().getBooleanExtra("episode_transition",false)) {
			final android.widget.FrameLayout cgEpisodeCover=new android.widget.FrameLayout(PlayerActivity.this);
			cgEpisodeCover.setBackgroundColor(0xB0111113);
			android.widget.ImageView preview=new android.widget.ImageView(PlayerActivity.this);
			preview.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
			cgEpisodeCover.addView(preview,new android.widget.FrameLayout.LayoutParams(-1,-1));
			String previewUrl=getIntent().getStringExtra("poster");
			if(previewUrl!=null&&!previewUrl.isEmpty())cgImage.load(previewUrl,preview,true);
			android.widget.TextView play=cgGlass.label("▶",38);play.setTextColor(0xFFE21B14);play.setGravity(android.view.Gravity.CENTER);
			cgGlass.bind(play,36,true);android.widget.FrameLayout.LayoutParams pl=new android.widget.FrameLayout.LayoutParams(cgGlass.px(72),cgGlass.px(72));
			pl.gravity=android.view.Gravity.CENTER;cgEpisodeCover.addView(play,pl);
			playerHost.addView(cgEpisodeCover,new android.widget.FrameLayout.LayoutParams(-1,-1));
			
			final boolean[] cgEpisodeCoverClosed={false};
			final long cgEpisodeCoverStarted=android.os.SystemClock.elapsedRealtime();
			final Runnable[] cgEpisodeCoverCheck=new Runnable[1];
			cgEpisodeCoverCheck[0]=new Runnable(){@Override public void run(){
					if(cgEpisodeCoverClosed[0]||isFinishing()||isDestroyed())return;
					boolean started=false,failed=false;
					try {started=exoPlayer.isPlaying();failed=exoPlayer.getPlayerError()!=null;}catch(Exception ignored){}
					boolean timedOut=android.os.SystemClock.elapsedRealtime()-cgEpisodeCoverStarted>=5500L;
					if(started||failed||timedOut){
						cgEpisodeCoverClosed[0]=true;
						cgEpisodeCover.animate().alpha(0f).setDuration(240L).withEndAction(new Runnable(){@Override public void run(){
								if(cgEpisodeCover.getParent()==playerHost)playerHost.removeView(cgEpisodeCover);
							}}).start();
					} else {
						playerHost.postDelayed(cgEpisodeCoverCheck[0],100L);
					}
				}};
			playerHost.postDelayed(cgEpisodeCoverCheck[0],120L);
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
		
		
		final android.content.SharedPreferences cgEpisodeWatchState =
		getSharedPreferences(
		"episode_watch_state",
		MODE_PRIVATE
		);
		
		final java.lang.Runnable cgEpisodeWatchStateWriter =
		new java.lang.Runnable() {
			
			@Override
			public void run() {
				
				if (
				isFinishing()
				||
				(
				android.os.Build.VERSION.SDK_INT >= 17
				&&
				isDestroyed()
				)
				) {
					return;
				}
				
				try {
					
					String key =
					watchProgressKey[0];
					
					if (
					key != null
					&&
					!key.trim().equals("")
					) {
						
						long position =
						Math.max(
						0L,
						exoPlayer.getCurrentPosition()
						);
						
						long duration =
						exoPlayer.getDuration();
						
						boolean hasDuration =
						duration > 0L
						&&
						duration
						!=
						com.google.android.exoplayer2.C.TIME_UNSET;
						
						boolean alreadyWatched =
						cgEpisodeWatchState.getBoolean(
						key + "|watched",
						false
						);
						
						boolean watchedNow =
						alreadyWatched;
						
						if (hasDuration) {
							
							watchedNow =
							watchedNow
							||
							(
							position * 100L
							>=
							duration * 90L
							);
						}
						
						if (
						exoPlayer.getPlaybackState()
						==
						com.google.android.exoplayer2.Player.STATE_ENDED
						) {
							watchedNow = true;
						}
						
						android.content.SharedPreferences.Editor editor =
						cgEpisodeWatchState.edit();
						
						editor.putLong(
						key + "|position",
						position
						);
						
						if (hasDuration) {
							editor.putLong(
							key + "|duration",
							duration
							);
						}
						
						if (watchedNow) {
							editor.putBoolean(
							key + "|watched",
							true
							);
						}
						
						editor.apply();
					}
					
				} catch (Exception ignored) {
				}
				
				uiHandler.postDelayed(
				this,
				1000L
				);
			}
		};
		
		uiHandler.post(
		cgEpisodeWatchStateWriter
		);
		
		
		try {
			
			if (
			android.os.Build.VERSION.SDK_INT
			>=
			android.os.Build.VERSION_CODES.O
			) {
				
				final int CG_PIP_RUNTIME_TAG =
				0x7f0f0c61;
				
				final java.util.HashMap<String, Object> cgPipRuntime =
				new java.util.HashMap<String, Object>();
				
				final android.widget.TextView cgPipButton =
				cgGlass.label(
				"▣",
				17
				);
				
				cgPipButton.setGravity(
				android.view.Gravity.CENTER
				);
				
				cgPipButton.setContentDescription(
				"صورة داخل صورة"
				);
				
				cgGlass.bind(
				cgPipButton,
				22,
				true
				);
				
				cgGlass.press(
				cgPipButton
				);
				
				android.widget.FrameLayout.LayoutParams cgPipLp =
				new android.widget.FrameLayout.LayoutParams(
				cgGlass.px(44),
				cgGlass.px(44)
				);
				
				cgPipLp.gravity =
				android.view.Gravity.TOP
				|
				android.view.Gravity.RIGHT;
				
				// بعد Settings و Episodes.
				cgPipLp.setMargins(
				0,
				cgGlass.px(14),
				cgGlass.px(118),
				0
				);
				
				playerHost.addView(
				cgPipButton,
				cgPipLp
				);
				
				final android.view.View[] cgPipControls =
				new android.view.View[] {
					centerControls,
					bottomControls,
					settingsButton,
					seriesInfo,
					episodesButton,
					cgPipButton
				};
				
				cgPipRuntime.put(
				"player",
				exoPlayer
				);
				
				cgPipRuntime.put(
				"controls",
				cgPipControls
				);
				
				cgPipRuntime.put(
				"pip_button",
				cgPipButton
				);
				
				cgPipRuntime.put(
				"is_series",
				java.lang.Boolean.valueOf(
				isSeries
				)
				);
				
				// نخزن Runtime على android.R.id.content حتى Events الأخرى تقدر توصله.
				playerHost.setTag(
				CG_PIP_RUNTIME_TAG,
				cgPipRuntime
				);
				
				class CigramPipController {
					
					void hideControls() {
						
						try {
							
							for (
							android.view.View control :
							cgPipControls
							) {
								
								if (control != null) {
									
									control.animate()
									.cancel();
									
									control.setVisibility(
									android.view.View.GONE
									);
								}
							}
							
						} catch (Exception ignored) {
						}
					}
					
					void enter() { try { if (!CigramPlusUI.requirePlus(PlayerActivity.this, "الصورة داخل الصورة")) { return; }
							
							if (
							android.os.Build.VERSION.SDK_INT
							<
							android.os.Build.VERSION_CODES.O
							) {
								return;
							}
							
							if (
							isInPictureInPictureMode()
							) {
								return;
							}
							
							long width = 16L;
							long height = 9L;
							
							android.app.PictureInPictureParams.Builder builder =
							new android.app.PictureInPictureParams.Builder()
							.setAspectRatio(
							new android.util.Rational(
							(int) width,
							(int) height
							)
							);
							
							if (
							android.os.Build.VERSION.SDK_INT
							>=
							android.os.Build.VERSION_CODES.S
							) {
								
								builder.setSeamlessResizeEnabled(
								true
								);
							}
							
							hideControls();
							
							boolean entered =
							enterPictureInPictureMode(
							builder.build()
							);
							
							if (!entered) {
								
								for (
								android.view.View control :
								cgPipControls
								) {
									
									if (control != null) {
										
										control.setVisibility(
										android.view.View.VISIBLE
										);
									}
								}
								
								if (!isSeries) {
									
									seriesInfo.setVisibility(
									android.view.View.GONE
									);
									
									episodesButton.setVisibility(
									android.view.View.GONE
									);
								}
							}
							
						} catch (Exception error) {
							
							try {
								
								android.widget.Toast.makeText(
								PlayerActivity.this,
								"تعذر تشغيل صورة داخل صورة",
								android.widget.Toast.LENGTH_SHORT
								).show();
								
							} catch (Exception ignored) {
							}
						}
					}
				}
				
				final CigramPipController cgPipController =
				new CigramPipController();
				
				cgPipButton.setOnClickListener(
				new android.view.View.OnClickListener() {
					
					@Override
					public void onClick(
					android.view.View view
					) {
						
						try {
							
							cgPipController.enter();
							
						} catch (Exception ignored) {
						}
					}
				}
				);
			}
			
		} catch (Exception ignored) {
		}
		
		
		try {
			
			if (
			android.os.Build.VERSION.SDK_INT >=
			android.os.Build.VERSION_CODES.S
			) {
				
				final android.os.Handler cgAutoPipHandler =
				new android.os.Handler(
				android.os.Looper.getMainLooper()
				);
				
				final boolean[] cgLastPipState = { false };
				final boolean[] cgLastAutoState = { false };
				
				final java.lang.Runnable cgAutoPipWatcher =
				new java.lang.Runnable() {
					
					@Override
					public void run() {
						
						try {
							
							android.view.View root =
							findViewById(
							android.R.id.content
							);
							
							Object raw =
							root.getTag(
							0x7f0f0c61
							);
							
							if (
							raw instanceof java.util.HashMap
							) {
								
								java.util.HashMap state =
								(java.util.HashMap) raw;
								
								Object playerRaw =
								state.get("player");
								
								boolean playing = false;
								
								if (
								playerRaw instanceof
								com.google.android.exoplayer2.ExoPlayer
								) {
									
									playing =
									((com.google.android.exoplayer2.ExoPlayer)
									playerRaw)
									.isPlaying();
								}
								
								// Auto-PiP فقط أثناء التشغيل
								if (
								playing != cgLastAutoState[0]
								) {
									
									cgLastAutoState[0] =
									playing;
									
									android.app.PictureInPictureParams params =
									new android.app.PictureInPictureParams.Builder()
									.setAspectRatio(
									new android.util.Rational(
									16,
									9
									)
									)
									.setAutoEnterEnabled( playing && CigramPlus.isPlus(PlayerActivity.this) )
									.setSeamlessResizeEnabled(
									true
									)
									.build();
									
									setPictureInPictureParams(
									params
									);
								}
								
								boolean inPip =
								isInPictureInPictureMode();
								
								if (
								inPip != cgLastPipState[0]
								) {
									
									cgLastPipState[0] =
									inPip;
									
									Object controlsRaw =
									state.get(
									"controls"
									);
									
									if (
									controlsRaw instanceof
									android.view.View[]
									) {
										
										android.view.View[] controls =
										(android.view.View[])
										controlsRaw;
										
										for (
										android.view.View control :
										controls
										) {
											
											if (control == null) {
												continue;
											}
											
											control.setVisibility(
											inPip
											? android.view.View.GONE
											: android.view.View.VISIBLE
											);
										}
										
										if (
										!inPip &&
										!java.lang.Boolean.TRUE.equals(
										state.get(
										"is_series"
										)
										)
										) {
											
											if (
											controls.length > 4
											) {
												
												controls[3]
												.setVisibility(
												android.view.View.GONE
												);
												
												controls[4]
												.setVisibility(
												android.view.View.GONE
												);
											}
										}
									}
								}
							}
							
						} catch (Exception ignored) {
						}
						
						cgAutoPipHandler.postDelayed(
						this,
						500L
						);
					}
				};
				
				cgAutoPipHandler.post(
				cgAutoPipWatcher
				);
			}
			
		} catch (Exception ignored) {
		}
		
		try {
			
			android.view.View cgPipRoot =
			findViewById(android.R.id.content);
			
			if (cgPipRoot != null) {
				
				Object cgPipRaw =
				cgPipRoot.getTag(
				0x7f0f0c61
				);
				
				if (
				cgPipRaw
				instanceof
				java.util.HashMap
				) {
					
					java.util.HashMap cgPipState =
					(java.util.HashMap) cgPipRaw;
					
					Object cgPipButtonRaw =
					cgPipState.get(
					"pip_button"
					);
					
					if (
					cgPipButtonRaw
					instanceof
					android.view.View
					) {
						
						android.view.View cgPipManualButton =
						(android.view.View) cgPipButtonRaw;
						
						cgPipManualButton.setVisibility(
						android.view.View.GONE
						);
						
						cgPipManualButton.setEnabled(
						false
						);
						
						cgPipManualButton.setClickable(
						false
						);
						
						android.view.ViewParent cgPipParent =
						cgPipManualButton.getParent();
						
						if (
						cgPipParent
						instanceof
						android.view.ViewGroup
						) {
							
							((android.view.ViewGroup) cgPipParent)
							.removeView(
							cgPipManualButton
							);
						}
					}
				}
			}
			
		} catch (Exception ignored) {
		}
		
		
		try {
			
			final android.os.Handler cgPipCloseHandler =
			new android.os.Handler(
			android.os.Looper.getMainLooper()
			);
			
			final boolean[] cgPipWasActive = { false };
			final boolean[] cgPipCloseHandled = { false };
			
			final java.lang.Runnable[] cgPipCloseWatcher =
			new java.lang.Runnable[1];
			
			cgPipCloseWatcher[0] =
			new java.lang.Runnable() {
				
				void saveAndClosePlayer() {
					
					if (cgPipCloseHandled[0]) {
						return;
					}
					
					cgPipCloseHandled[0] = true;
					
					try {
						
						long position =
						Math.max(
						0L,
						exoPlayer.getCurrentPosition()
						);
						
						long duration =
						exoPlayer.getDuration();
						
						if (
						watchProgressKey[0] != null
						&&
						!watchProgressKey[0].equals("")
						&&
						position >= 5000L
						) {
							
							watchProgress.edit()
							.putLong(
							watchProgressKey[0],
							position
							)
							.apply();
							
							try {
								
								continueWatchingStore.save(
								position,
								duration
								);
								
							} catch (Exception ignored) {
							}
						}
						
					} catch (Exception ignored) {
					}
					
					// وقف الصوت فوراً.
					try {
						exoPlayer.setPlayWhenReady(false);
					} catch (Exception ignored) {
					}
					
					try {
						exoPlayer.pause();
					} catch (Exception ignored) {
					}
					
					try {
						exoPlayer.stop();
					} catch (Exception ignored) {
					}
					
					try {
						exoView.setPlayer(null);
					} catch (Exception ignored) {
					}
					
					try {
						exoPlayer.clearMediaItems();
					} catch (Exception ignored) {
					}
					
					try {
						exoPlayer.release();
					} catch (Exception ignored) {
					}
					
					// اغلق PlayerActivity فقط، وليس التطبيق كله.
					try {
						
						if (!isFinishing()) {
							finish();
						}
						
					} catch (Exception ignored) {
					}
				}
				
				@Override
				public void run() {
					
					try {
						
						if (
						isFinishing()
						||
						isDestroyed()
						) {
							return;
						}
						
						boolean inPip =
						android.os.Build.VERSION.SDK_INT
						>=
						android.os.Build.VERSION_CODES.O
						&&
						isInPictureInPictureMode();
						
						if (inPip) {
							
							// سجل أن النشاط دخل PiP فعلاً.
							cgPipWasActive[0] =
							true;
							
							cgPipCloseHandled[0] =
							false;
							
						} else if (
						cgPipWasActive[0]
						) {
							
							/*
                         * صار انتقال من:
                         * PiP = true
                         * إلى
                         * PiP = false
                         *
                         * هذا ممكن يكون:
                         * 1) ضغط X وإغلاق PiP.
                         * 2) تكبير PiP والرجوع للتطبيق.
                         *
                         * ننتظر قليلاً:
                         * - إذا رجع التطبيق للواجهة سيصير hasWindowFocus=true
                         *   ولا نغلق المشغل.
                         * - إذا المستخدم ضغط X تبقى الواجهة بلا Focus،
                         *   فنوقف الصوت والفيديو ونغلق PlayerActivity.
                         */
							
							cgPipWasActive[0] =
							false;
							
							cgPipCloseHandler.postDelayed(
							new java.lang.Runnable() {
								
								@Override
								public void run() {
									
									try {
										
										boolean stillInPip =
										android.os.Build.VERSION.SDK_INT
										>=
										android.os.Build.VERSION_CODES.O
										&&
										isInPictureInPictureMode();
										
										boolean hasFocus =
										hasWindowFocus();
										
										if (
										!stillInPip
										&&
										!hasFocus
										&&
										!isFinishing()
										) {
											
											saveAndClosePlayer();
										}
										
									} catch (Exception ignored) {
									}
								}
							},
							650L
							);
						}
						
					} catch (Exception ignored) {
					}
					
					if (
					!cgPipCloseHandled[0]
					&&
					!isFinishing()
					&&
					!isDestroyed()
					) {
						
						cgPipCloseHandler.postDelayed(
						this,
						250L
						);
					}
				}
			};
			
			cgPipCloseHandler.post(
			cgPipCloseWatcher[0]
			);
			
		} catch (Exception ignored) {
		}
		
		
		try {
			
			if (
			isSeries
			&&
			episodesButton != null
			&&
			episodeItems != null
			&&
			!episodeItems.isEmpty()
			) {
				
				final android.content.SharedPreferences cgPanelWatchState =
				getSharedPreferences(
				"episode_watch_state",
				MODE_PRIVATE
				);
				
				// -------------------------------------------------
				// نقرأ IDs الحلقات من JSON الأصلي لأن EpisodeData الحالي
				// لا يخزن id، ونستخدمها لعمل نفس watch key قدر الإمكان.
				// -------------------------------------------------
				class CgEpisodeCanonicalKeys {
					
					final java.util.HashMap<String, String> ids =
					new java.util.HashMap<String, String>();
					
					String text(Object value) {
						
						if (
						value == null
						||
						value == org.json.JSONObject.NULL
						) {
							return "";
						}
						
						return String.valueOf(value).trim();
					}
					
					void collect(
					Object node,
					String inheritedSeason,
					int depth
					) {
						
						if (
						node == null
						||
						depth > 14
						) {
							return;
						}
						
						if (node instanceof String) {
							
							String encoded =
							((String) node).trim();
							
							if (
							encoded.startsWith("[")
							||
							encoded.startsWith("{")
							) {
								
								try {
									
									collect(
									new org.json.JSONTokener(
									encoded
									).nextValue(),
									inheritedSeason,
									depth + 1
									);
									
								} catch (Exception ignored) {
								}
							}
							
							return;
						}
						
						if (node instanceof org.json.JSONArray) {
							
							org.json.JSONArray array =
							(org.json.JSONArray) node;
							
							for (
							int i = 0;
							i < array.length();
							i++
							) {
								
								collect(
								array.opt(i),
								inheritedSeason,
								depth + 1
								);
							}
							
							return;
						}
						
						if (!(node instanceof org.json.JSONObject)) {
							return;
						}
						
						org.json.JSONObject object =
						(org.json.JSONObject) node;
						
						String season =
						object.optString(
						"season",
						object.optString(
						"number",
						inheritedSeason
						)
						);
						
						String episode =
						object.optString(
						"episode",
						object.optString(
						"ep",
						""
						)
						);
						
						String id =
						object.optString(
						"id",
						object.optString(
						"episode_id",
						object.optString(
						"tmdb_episode_id",
						""
						)
						)
						);
						
						if (
						!episode.trim().equals("")
						&&
						!id.trim().equals("")
						) {
							
							ids.put(
							season.trim()
							+ "|"
							+ episode.trim(),
							id.trim()
							);
						}
						
						org.json.JSONArray episodes =
						object.optJSONArray(
						"episodes"
						);
						
						if (episodes != null) {
							
							collect(
							episodes,
							season,
							depth + 1
							);
						}
						
						org.json.JSONArray seasons =
						object.optJSONArray(
						"seasons"
						);
						
						if (seasons != null) {
							
							collect(
							seasons,
							season,
							depth + 1
							);
						}
					}
					
					String key(
					EpisodeData episode
					) {
						
						String season =
						episode.season == null
						? ""
						: episode.season.trim();
						
						String number =
						episode.episode == null
						? ""
						: episode.episode.trim();
						
						if (season.equals("")) {
							season = "1";
						}
						
						if (number.equals("")) {
							number = "1";
						}
						
						String id =
						ids.get(
						season
						+ "|"
						+ number
						);
						
						String identity =
						id != null
						&&
						!id.trim().equals("")
						? id.trim()
						: mediaIdentity;
						
						String raw =
						identity
						+ "|s"
						+ season
						+ "|e"
						+ number;
						
						return progressOwner
						+ "|series_"
						+ Integer.toHexString(
						raw.hashCode()
						);
					}
					
					boolean watched(
					EpisodeData episode
					) {
						
						String key =
						key(episode);
						
						boolean watched =
						cgPanelWatchState.getBoolean(
						key + "|watched",
						false
						);
						
						if (watched) {
							return true;
						}
						
						long position =
						cgPanelWatchState.getLong(
						key + "|position",
						0L
						);
						
						long duration =
						cgPanelWatchState.getLong(
						key + "|duration",
						0L
						);
						
						return duration > 0L
						&&
						position > 0L
						&&
						position * 100L
						>=
						duration * 90L;
					}
				}
				
				final CgEpisodeCanonicalKeys cgEpisodeKeys =
				new CgEpisodeCanonicalKeys();
				
				try {
					
					if (
					episodesJson != null
					&&
					!episodesJson.trim().equals("")
					) {
						
						cgEpisodeKeys.collect(
						new org.json.JSONTokener(
						episodesJson
						).nextValue(),
						"",
						0
						);
					}
					
				} catch (Exception ignored) {
				}
				
				// -------------------------------------------------
				// Canonical key watcher:
				// إذا انتقل المشغل تلقائياً أو من زر Next داخل الكود القديم،
				// نصحح watchProgressKey للحلقة الجديدة.
				// -------------------------------------------------
				final int[] cgLastCanonicalEpisodeIndex = {
					Integer.MIN_VALUE
				};
				
				final java.lang.Runnable cgCanonicalEpisodeWatcher =
				new java.lang.Runnable() {
					
					@Override
					public void run() {
						
						try {
							
							int index =
							currentEpisodeIndex[0];
							
							if (
							index >= 0
							&&
							index < episodeItems.size()
							&&
							index
							!=
							cgLastCanonicalEpisodeIndex[0]
							) {
								
								cgLastCanonicalEpisodeIndex[0] =
								index;
								
								EpisodeData data =
								episodeItems.get(
								index
								);
								
								String canonical =
								cgEpisodeKeys.key(
								data
								);
								
								String previous =
								watchProgressKey[0];
								
								if (
								canonical != null
								&&
								!canonical.equals("")
								&&
								!canonical.equals(previous)
								) {
									
									// ننقل UI state إذا القديم يحتوي بيانات
									// والجديد فارغ.
									try {
										
										if (
										previous != null
										&&
										!previous.equals("")
										) {
											
											long oldPosition =
											cgPanelWatchState.getLong(
											previous + "|position",
											0L
											);
											
											long oldDuration =
											cgPanelWatchState.getLong(
											previous + "|duration",
											0L
											);
											
											boolean oldWatched =
											cgPanelWatchState.getBoolean(
											previous + "|watched",
											false
											);
											
											if (
											oldPosition > 0L
											&&
											cgPanelWatchState.getLong(
											canonical + "|position",
											0L
											) <= 0L
											) {
												
												android.content.SharedPreferences.Editor editor =
												cgPanelWatchState.edit();
												
												editor.putLong(
												canonical + "|position",
												oldPosition
												);
												
												if (
												oldDuration > 0L
												) {
													
													editor.putLong(
													canonical + "|duration",
													oldDuration
													);
												}
												
												if (oldWatched) {
													
													editor.putBoolean(
													canonical + "|watched",
													true
													);
												}
												
												editor.apply();
											}
										}
										
									} catch (Exception ignored) {
									}
									
									watchProgressKey[0] =
									canonical;
									
									long saved =
									watchProgress.getLong(
									canonical,
									0L
									);
									
									if (
									saved <= 0L
									) {
										
										saved =
										cgPanelWatchState.getLong(
										canonical + "|position",
										0L
										);
									}
									
									if (
									saved >= 5000L
									&&
									exoPlayer.getCurrentPosition()
									< 3000L
									) {
										
										try {
											
											exoPlayer.seekTo(
											saved
											);
											
										} catch (Exception ignored) {
										}
									}
								}
							}
							
						} catch (Exception ignored) {
						}
						
						uiHandler.postDelayed(
						this,
						350L
						);
					}
				};
				
				uiHandler.post(
				cgCanonicalEpisodeWatcher
				);
				
				// -------------------------------------------------
				// Listener جديد للحلقات.
				// setOnClickListener هنا يستبدل listener القديم فقط،
				// ولا يغير أي بلوك قديم.
				// -------------------------------------------------
				episodesButton.setOnClickListener(
				new android.view.View.OnClickListener() {
					
					@Override
					public void onClick(
					android.view.View view
					) {
						
						try {
							
							if (
							episodesPopupHolder[0] != null
							&&
							episodesPopupHolder[0].isShowing()
							) { return;
							}
							
							final android.widget.LinearLayout panel =
							new android.widget.LinearLayout(
							PlayerActivity.this
							);
							
							panel.setOrientation(
							android.widget.LinearLayout.VERTICAL
							);
							
							panel.setLayoutDirection(
							android.view.View.LAYOUT_DIRECTION_RTL
							);
							
							panel.setPadding(
							cgGlass.px(16),
							cgGlass.px(12),
							cgGlass.px(16),
							cgGlass.px(14)
							);
							
							// v5.29: opaque panel #0E0E10 (no see-through glass)
							{android.graphics.drawable.GradientDrawable cgPanelBg=new android.graphics.drawable.GradientDrawable();cgPanelBg.setColor(0xFF0E0E10);cgPanelBg.setCornerRadius(cgGlass.px(26));panel.setBackground(cgPanelBg);panel.setClipToOutline(true);}
							
							// Header
							android.widget.LinearLayout header =
							new android.widget.LinearLayout(
							PlayerActivity.this
							);
							
							header.setLayoutDirection(
							android.view.View.LAYOUT_DIRECTION_RTL
							);
							
							header.setGravity(
							android.view.Gravity.CENTER_VERTICAL
							);
							
							android.widget.TextView heading =
							cgGlass.label(
							seriesTitle,
							18
							);
							
							heading.setTypeface(
							cgGlass.font,
							android.graphics.Typeface.BOLD
							);
							
							heading.setMaxLines(1);
							
							heading.setEllipsize(
							android.text.TextUtils.TruncateAt.END
							);
							
							header.addView(
							heading,
							new android.widget.LinearLayout.LayoutParams(
							0,
							cgGlass.px(42),
							1f
							)
							);
							
							android.widget.TextView close =
							cgGlass.label(
							"×",
							28
							);
							
							close.setGravity(
							android.view.Gravity.CENTER
							);
							
							header.addView(
							close,
							new android.widget.LinearLayout.LayoutParams(
							cgGlass.px(42),
							cgGlass.px(42)
							)
							);
							
							header.removeView(close);header.addView(close,0,new android.widget.LinearLayout.LayoutParams(cgGlass.px(42),cgGlass.px(42)));close.setBackground(null);close.setTextColor(0xFFFFFFFF);close.setContentDescription("إغلاق");
							heading.setGravity(android.view.Gravity.RIGHT|android.view.Gravity.CENTER_VERTICAL);
							panel.addView(header);
							
							// Seasons
							final android.widget.HorizontalScrollView seasonScroll =
							new android.widget.HorizontalScrollView(
							PlayerActivity.this
							);
							
							seasonScroll.setHorizontalScrollBarEnabled(
							false
							);
							
							seasonScroll.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);seasonScroll.setFillViewport(true);
							
							final android.widget.LinearLayout seasonRow =
							new android.widget.LinearLayout(
							PlayerActivity.this
							);
							
							seasonRow.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);
							
							seasonRow.setGravity(
							android.view.Gravity.RIGHT
							|
							android.view.Gravity.CENTER_VERTICAL
							);
							
							seasonScroll.addView(
							seasonRow,
							new android.widget.HorizontalScrollView.LayoutParams(
							-2,
							cgGlass.px(52)
							)
							);
							
							panel.addView(
							seasonScroll,
							new android.widget.LinearLayout.LayoutParams(
							-1,
							cgGlass.px(52)
							)
							);
							
							final android.widget.ScrollView listScroll =
							new android.widget.ScrollView(
							PlayerActivity.this
							);
							
							listScroll.setVerticalScrollBarEnabled(
							false
							);
							
							final android.widget.LinearLayout list =
							new android.widget.LinearLayout(
							PlayerActivity.this
							);
							
							list.setOrientation(
							android.widget.LinearLayout.VERTICAL
							);
							
							list.setLayoutDirection(
							android.view.View.LAYOUT_DIRECTION_RTL
							);
							
							listScroll.addView(
							list,
							new android.widget.ScrollView.LayoutParams(
							-1,
							-2
							)
							);
							
							panel.addView(
							listScroll,
							new android.widget.LinearLayout.LayoutParams(
							-1,
							0,
							1f
							)
							);
							
							final java.util.ArrayList<String> seasons =
							new java.util.ArrayList<String>();
							
							for (
							EpisodeData episode :
							episodeItems
							) {
								
								String season =
								episode.season == null
								||
								episode.season.trim().equals("")
								? "1"
								: episode.season.trim();
								
								if (!seasons.contains(season)) {
									
									seasons.add(
									season
									);
								}
							}
							
							java.util.Collections.sort(
							seasons,
							new java.util.Comparator<String>() {
								
								@Override
								public int compare(
								String a,
								String b
								) {
									
									try {
										
										return Integer.compare(
										Integer.parseInt(a),
										Integer.parseInt(b)
										);
										
									} catch (Exception ignored) {
										
										return a.compareTo(b);
									}
								}
							}
							);
							
							final String[] selectedSeason = {
								currentSeason[0] == null
								||
								currentSeason[0].trim().equals("")
								? (
								seasons.isEmpty()
								? "1"
								: seasons.get(0)
								)
								: currentSeason[0].trim()
							};
							
							class CgEpisodePanelRenderer {
								
								android.graphics.drawable.Drawable rowBackground(
								boolean current
								) {
									
									android.graphics.drawable.GradientDrawable glass=new android.graphics.drawable.GradientDrawable();glass.setColor(0xFF1C1C20);glass.setCornerRadius(cgGlass.px(17));
									
									if (!current) {
										return glass;
									}
									
									android.graphics.drawable.GradientDrawable red =
									new android.graphics.drawable.GradientDrawable();
									
									red.setColor(
									0x22E21B14
									);
									
									red.setCornerRadius(
									cgGlass.px(17)
									);
									
									red.setStroke(
									cgGlass.px(1.5f),
									0xFFE21B14
									);
									
									return new android.graphics.drawable.LayerDrawable(
									new android.graphics.drawable.Drawable[] {
										glass,
										red
									}
									);
								}
								
								void render() {
									
									try {
										
										seasonRow.removeAllViews();
										list.removeAllViews();
										
										// Chips
										// laid out left-to-right, so add last-to-first: season 1 sits at the far RIGHT
										for (int cgSi = seasons.size() - 1; cgSi >= 0; cgSi--) { final String season = seasons.get(cgSi);
											
											final boolean selected =
											season.equals(
											selectedSeason[0]
											);
											
											android.widget.TextView chip =
											cgGlass.label(
											"الموسم " + season,
											13
											);
											
											chip.setGravity(
											android.view.Gravity.CENTER
											);
											
											// v5.29: season chips #0E0E10; the chosen one is solid white with black text
											chip.setTextColor(selected ? 0xFF000000 : 0xFFFFFFFF);
											{android.graphics.drawable.GradientDrawable cgChipBg=new android.graphics.drawable.GradientDrawable();cgChipBg.setColor(selected ? 0xFFFFFFFF : 0xFF0E0E10);cgChipBg.setCornerRadius(cgGlass.px(20));if(!selected)cgChipBg.setStroke(Math.max(1,cgGlass.px(1)),0x33FFFFFF);chip.setBackground(cgChipBg);}
											
											cgGlass.press(
											chip
											);
											
											android.widget.LinearLayout.LayoutParams chipLp =
											new android.widget.LinearLayout.LayoutParams(
											-2,
											cgGlass.px(40)
											);
											
											chipLp.setMargins(
											cgGlass.px(3),
											cgGlass.px(6),
											cgGlass.px(3),
											cgGlass.px(6)
											);
											
											seasonRow.addView(
											chip,
											chipLp
											);
											
											chip.setOnClickListener(
											new android.view.View.OnClickListener() {
												
												@Override
												public void onClick(
												android.view.View view
												) {
													
													try {
														
														selectedSeason[0] =
														season;
														
														render();
														
													} catch (Exception ignored) {
													}
												}
											}
											);
										}
										
										// Episodes
										for (
										int i = 0;
										i < episodeItems.size();
										i++
										) {
											
											final int index =
											i;
											
											final EpisodeData episode =
											episodeItems.get(i);
											
											String season =
											episode.season == null
											||
											episode.season.trim().equals("")
											? "1"
											: episode.season.trim();
											
											if (
											!selectedSeason[0].equals(
											season
											)
											) {
												continue;
											}
											
											final boolean current =
											index
											==
											currentEpisodeIndex[0];
											
											final boolean watched =
											cgEpisodeKeys.watched(
											episode
											);
											
											final String canonicalKey =
											cgEpisodeKeys.key(
											episode
											);
											
											android.widget.LinearLayout row =
											new android.widget.LinearLayout(
											PlayerActivity.this
											);
											
											row.setOrientation(
											android.widget.LinearLayout.HORIZONTAL
											);
											
											row.setLayoutDirection(
											android.view.View.LAYOUT_DIRECTION_RTL
											);
											
											row.setGravity(
											android.view.Gravity.CENTER_VERTICAL
											);
											
											row.setPadding(
											cgGlass.px(9),
											cgGlass.px(8),
											cgGlass.px(9),
											cgGlass.px(8)
											);
											
											row.setBackground(
											rowBackground(
											current
											)
											);
											
											row.setClipToOutline(
											true
											);
											
											cgGlass.press(
											row
											);
											
											// Artwork
											android.widget.ImageView art =
											new android.widget.ImageView(
											PlayerActivity.this
											);
											
											art.setScaleType(
											android.widget.ImageView.ScaleType.CENTER_CROP
											);
											
											cgGlass.bind(
											art,
											12,
											false
											);
											
											if (
											episode.poster != null
											&&
											!episode.poster.trim().equals("")
											) {
												
												cgImage.load(
												episode.poster,
												art,
												true
												);
											}
											
											{final android.widget.FrameLayout cgArtBox=new android.widget.FrameLayout(PlayerActivity.this);cgArtBox.setOutlineProvider(new android.view.ViewOutlineProvider(){@Override public void getOutline(android.view.View v,android.graphics.Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),cgGlass.px(12));}});cgArtBox.setClipToOutline(true);cgArtBox.addView(art,new android.widget.FrameLayout.LayoutParams(-1,-1));if(watched)CigramWatchedMark.cover(cgArtBox);row.addView(cgArtBox,new android.widget.LinearLayout.LayoutParams(cgGlass.px(100),cgGlass.px(58)));}
											
											// Texts
											android.widget.LinearLayout info =
											new android.widget.LinearLayout(
											PlayerActivity.this
											);
											
											info.setOrientation(
											android.widget.LinearLayout.VERTICAL
											);
											
											info.setGravity(
											android.view.Gravity.RIGHT
											|
											android.view.Gravity.CENTER_VERTICAL
											);
											
											info.setPadding(
											cgGlass.px(10),
											0,
											cgGlass.px(10),
											0
											);
											
											String number =
											episode.episode == null
											||
											episode.episode.trim().equals("")
											? String.valueOf(
											index + 1
											)
											: episode.episode.trim();
											
											android.widget.TextView episodeNumber =
											cgGlass.label(
											"الحلقة " + number,
											14
											);
											
											episodeNumber.setTypeface(
											cgGlass.font,
											android.graphics.Typeface.BOLD
											);
											
											if (current) {
												
												episodeNumber.setTextColor(
												0xFFE21B14
												);
											}
											
											info.addView(
											episodeNumber
											);
											
											String title =
											episode.title == null
											? ""
											: episode.title.trim();
											
											if (false && !title.equals("")) {
												
												android.widget.TextView episodeTitle =
												cgGlass.label(
												title,
												11
												);
												
												episodeTitle.setAlpha(
												0.68f
												);
												
												episodeTitle.setMaxLines(
												1
												);
												
												episodeTitle.setEllipsize(
												android.text.TextUtils.TruncateAt.END
												);
												
												info.addView(
												episodeTitle
												);
											}
											
											if (false) {
												
												android.widget.TextView watchedText =
												cgGlass.label(
												"تمت مشاهدتها",
												10
												);
												
												watchedText.setTextColor(
												0xFFE7EDF0
												);
												
												watchedText.setAlpha(
												0.85f
												);
												
												info.addView(
												watchedText
												);
											}
											
											row.addView(
											info,
											new android.widget.LinearLayout.LayoutParams(
											0,
											-2,
											1f
											)
											);
											
											if (current) {
												
												android.widget.TextView currentText =
												cgGlass.label(
												"قيد التشغيل",
												10
												);
												
												currentText.setGravity(
												android.view.Gravity.CENTER
												);
												
												currentText.setTextColor(
												0xFFE21B14
												);
												
												row.addView(
												currentText,
												new android.widget.LinearLayout.LayoutParams(
												cgGlass.px(74),
												cgGlass.px(36)
												)
												);
											}
											
											android.widget.LinearLayout.LayoutParams rowLp =
											new android.widget.LinearLayout.LayoutParams(
											-1,
											-2
											);
											
											rowLp.bottomMargin =
											cgGlass.px(8);
											
											list.addView(
											row,
											rowLp
											);
											
											row.setOnClickListener(
											new android.view.View.OnClickListener() {
												
												@Override
												public void onClick(
												android.view.View view
												) {
													
													try {
														
														// يحفظ السابقة داخل Controller القديم.
														episodeController.open(
														index
														);
														
														// صحّح مفتاح الحلقة الجديدة.
														watchProgressKey[0] =
														canonicalKey;
														
														long saved =
														watchProgress.getLong(
														canonicalKey,
														0L
														);
														
														if (
														saved <= 0L
														) {
															
															saved =
															cgPanelWatchState.getLong(
															canonicalKey
															+ "|position",
															0L
															);
														}
														
														final long resumeAt =
														saved;
														
														if (
														resumeAt
														>=
														5000L
														) {
															
															uiHandler.postDelayed(
															new java.lang.Runnable() {
																
																@Override
																public void run() {
																	
																	try {
																		
																		exoPlayer.seekTo(
																		resumeAt
																		);
																		
																	} catch (Exception ignored) {
																	}
																}
															},
															180L
															);
														}
														
														uiHandler.postDelayed(new java.lang.Runnable(){@Override public void run(){try{render();}catch(Exception ignored){}}},350L);
														
													} catch (Exception ignored) {
													}
												}
											}
											);
										}
										
									} catch (Exception ignored) {
									}
								}
							}
							
							final CgEpisodePanelRenderer renderer =
							new CgEpisodePanelRenderer();
							
							renderer.render();
							seasonScroll.post(new java.lang.Runnable(){@Override public void run(){seasonRow.setMinimumWidth(seasonScroll.getWidth());seasonScroll.fullScroll(android.view.View.FOCUS_RIGHT);}});
							
							final CigramGlass.Popup popup =
							cgGlass.popup(
							panel,
							playerHost,
							Math.min(
							cgGlass.px(470),
							(int) (
							getResources()
							.getDisplayMetrics()
							.widthPixels
							*
							0.84f
							)
							),
							(int) (
							getResources()
							.getDisplayMetrics()
							.heightPixels
							*
							0.94f
							),
							android.view.Gravity.RIGHT
							|
							android.view.Gravity.CENTER_VERTICAL
							);
							
							episodesPopupHolder[0] =
							popup;
							popup.setOutsideTouchable(false);popup.setFocusable(CigramTvMode.isTv(PlayerActivity.this));popup.update();
							
							popup.setOnDismissListener(
							new android.widget.PopupWindow.OnDismissListener() {
								
								@Override
								public void onDismiss() {
									
									episodesPopupHolder[0] =
									null;
								}
							}
							);
							
							close.setOnClickListener(
							new android.view.View.OnClickListener() {
								
								@Override
								public void onClick(
								android.view.View view
								) {
									
									try {
										
										popup.dismiss();
										
									} catch (Exception ignored) {
									}
								}
							}
							);
							
						} catch (Exception error) {
							
							try {
								
								android.widget.Toast.makeText(
								PlayerActivity.this,
								"تعذر فتح قائمة الحلقات",
								android.widget.Toast.LENGTH_SHORT
								).show();
								
							} catch (Exception ignored) {
							}
						}
					}
				}
				);
			}
			
		} catch (Exception ignored) {
		}
		
		
		try {
			
			final android.widget.LinearLayout cgSeekPreview =
			new android.widget.LinearLayout(
			PlayerActivity.this
			);
			
			cgSeekPreview.setOrientation(
			android.widget.LinearLayout.VERTICAL
			);
			
			cgSeekPreview.setGravity(
			android.view.Gravity.CENTER
			);
			
			cgSeekPreview.setPadding(
			cgGlass.px(4),
			cgGlass.px(4),
			cgGlass.px(4),
			cgGlass.px(4)
			);
			
			cgSeekPreview.setClickable(false);
			cgSeekPreview.setFocusable(false);
			
			cgGlass.bind(
			cgSeekPreview,
			14,
			true
			);
			
			final android.widget.ImageView cgSeekPreviewImage =
			new android.widget.ImageView(
			PlayerActivity.this
			);
			
			cgSeekPreviewImage.setScaleType(
			android.widget.ImageView.ScaleType.CENTER_CROP
			);
			
			cgSeekPreviewImage.setBackgroundColor(
			0xFF102630
			);
			
			cgSeekPreview.addView(
			cgSeekPreviewImage,
			new android.widget.LinearLayout.LayoutParams(
			cgGlass.px(160),
			cgGlass.px(90)
			)
			);
			
			final android.widget.TextView cgSeekPreviewTime =
			cgGlass.label(
			"00:00",
			12
			);
			
			cgSeekPreviewTime.setGravity(
			android.view.Gravity.CENTER
			);
			
			cgSeekPreviewTime.setTypeface(
			cgGlass.font,
			android.graphics.Typeface.BOLD
			);
			
			cgSeekPreview.addView(
			cgSeekPreviewTime,
			new android.widget.LinearLayout.LayoutParams(
			-1,
			cgGlass.px(24)
			)
			);
			
			android.widget.FrameLayout.LayoutParams cgSeekPreviewLp =
			new android.widget.FrameLayout.LayoutParams(
			cgGlass.px(168),
			cgGlass.px(122)
			);
			
			cgSeekPreviewLp.gravity =
			android.view.Gravity.TOP
			|
			android.view.Gravity.LEFT;
			
			playerHost.addView(
			cgSeekPreview,
			cgSeekPreviewLp
			);
			
			cgSeekPreview.setVisibility(
			android.view.View.GONE
			);
			
			// -------------------------------------------------
			// Preview metadata
			// -------------------------------------------------
			class CgPreviewMeta {
				
				String sprite = "";
				int cols = 0;
				int rows = 0;
				long intervalMs = 0L;
				
				boolean usableSprite() {
					
					return sprite != null
					&&
					!sprite.trim().equals("")
					&&
					cols > 0
					&&
					rows > 0
					&&
					intervalMs > 0L;
				}
			}
			
			class CgPreviewMetaResolver {
				
				final java.util.HashMap<String, CgPreviewMeta> map =
				new java.util.HashMap<String, CgPreviewMeta>();
				
				String value(
				org.json.JSONObject object,
				String... keys
				) {
					
					if (object == null) return "";
					
					for (String key : keys) {
						
						String value =
						object.optString(
						key,
						""
						);
						
						if (
						value != null
						&&
						!value.trim().equals("")
						) {
							
							return value.trim();
						}
					}
					
					return "";
				}
				
				int integer(
				org.json.JSONObject object,
				String... keys
				) {
					
					for (String key : keys) {
						
						try {
							
							if (object.has(key)) {
								
								return Math.max(
								0,
								Integer.parseInt(
								String.valueOf(
								object.opt(key)
								)
								.replaceAll(
								"[^0-9]",
								""
								)
								)
								);
							}
							
						} catch (Exception ignored) {
						}
					}
					
					return 0;
				}
				
				long interval(
				org.json.JSONObject object
				) {
					
					for (
					String key :
					new String[] {
						"sprite_interval_ms",
						"preview_interval_ms"
					}
					) {
						
						try {
							
							if (object.has(key)) {
								
								return Math.max(
								0L,
								Long.parseLong(
								String.valueOf(
								object.opt(key)
								)
								.replaceAll(
								"[^0-9]",
								""
								)
								)
								);
							}
							
						} catch (Exception ignored) {
						}
					}
					
					for (
					String key :
					new String[] {
						"sprite_interval",
						"preview_interval",
						"thumbnail_interval"
					}
					) {
						
						try {
							
							if (object.has(key)) {
								
								long seconds =
								Long.parseLong(
								String.valueOf(
								object.opt(key)
								)
								.replaceAll(
								"[^0-9]",
								""
								)
								);
								
								return Math.max(
								0L,
								seconds * 1000L
								);
							}
							
						} catch (Exception ignored) {
						}
					}
					
					return 0L;
				}
				
				void add(
				org.json.JSONObject object,
				String defaultSeason
				) {
					
					if (object == null) return;
					
					String episode =
					value(
					object,
					"episode",
					"ep"
					);
					
					if (episode.equals("")) {
						return;
					}
					
					String season =
					value(
					object,
					"season"
					);
					
					if (season.equals("")) {
						season = defaultSeason;
					}
					
					if (season.equals("")) {
						season = "1";
					}
					
					CgPreviewMeta meta =
					new CgPreviewMeta();
					
					meta.sprite =
					value(
					object,
					"sprite",
					"sprite_url",
					"preview_sprite",
					"thumbnail_sprite"
					);
					
					meta.cols =
					integer(
					object,
					"sprite_cols",
					"preview_cols"
					);
					
					meta.rows =
					integer(
					object,
					"sprite_rows",
					"preview_rows"
					);
					
					meta.intervalMs =
					interval(
					object
					);
					
					map.put(
					season
					+ "|"
					+ episode,
					meta
					);
				}
				
				void collect(
				Object node,
				String defaultSeason,
				int depth
				) {
					
					if (
					node == null
					||
					depth > 14
					) {
						return;
					}
					
					if (node instanceof String) {
						
						String raw =
						((String) node).trim();
						
						if (
						raw.startsWith("[")
						||
						raw.startsWith("{")
						) {
							
							try {
								
								collect(
								new org.json.JSONTokener(
								raw
								).nextValue(),
								defaultSeason,
								depth + 1
								);
								
							} catch (Exception ignored) {
							}
						}
						
						return;
					}
					
					if (node instanceof org.json.JSONArray) {
						
						org.json.JSONArray array =
						(org.json.JSONArray) node;
						
						for (
						int i = 0;
						i < array.length();
						i++
						) {
							
							collect(
							array.opt(i),
							defaultSeason,
							depth + 1
							);
						}
						
						return;
					}
					
					if (!(node instanceof org.json.JSONObject)) {
						return;
					}
					
					org.json.JSONObject object =
					(org.json.JSONObject) node;
					
					String season =
					value(
					object,
					"season",
					"number"
					);
					
					if (season.equals("")) {
						season = defaultSeason;
					}
					
					add(
					object,
					season
					);
					
					org.json.JSONArray episodes =
					object.optJSONArray(
					"episodes"
					);
					
					if (episodes != null) {
						
						collect(
						episodes,
						season,
						depth + 1
						);
					}
					
					org.json.JSONArray seasons =
					object.optJSONArray(
					"seasons"
					);
					
					if (seasons != null) {
						
						collect(
						seasons,
						season,
						depth + 1
						);
					}
				}
				
				CgPreviewMeta current() {
					
					String season =
					currentSeason[0] == null
					? ""
					: currentSeason[0].trim();
					
					String episode =
					currentEpisode[0] == null
					? ""
					: currentEpisode[0].trim();
					
					CgPreviewMeta found =
					map.get(
					season
					+ "|"
					+ episode
					);
					
					if (found != null) {
						return found;
					}
					
					CgPreviewMeta direct =
					new CgPreviewMeta();
					
					try {
						
						String sprite =
						getIntent().getStringExtra(
						"sprite"
						);
						
						if (
						sprite == null
						||
						sprite.trim().equals("")
						) {
							
							sprite =
							getIntent().getStringExtra(
							"sprite_url"
							);
						}
						
						if (
						sprite == null
						||
						sprite.trim().equals("")
						) {
							
							sprite =
							getIntent().getStringExtra(
							"preview_sprite"
							);
						}
						
						direct.sprite =
						sprite == null
						? ""
						: sprite.trim();
						
						String cols =
						getIntent().getStringExtra(
						"sprite_cols"
						);
						
						String rows =
						getIntent().getStringExtra(
						"sprite_rows"
						);
						
						String interval =
						getIntent().getStringExtra(
						"sprite_interval"
						);
						
						try {
							
							direct.cols =
							Integer.parseInt(
							cols == null
							? "0"
							: cols.replaceAll(
							"[^0-9]",
							""
							)
							);
							
						} catch (Exception ignored) {
						}
						
						try {
							
							direct.rows =
							Integer.parseInt(
							rows == null
							? "0"
							: rows.replaceAll(
							"[^0-9]",
							""
							)
							);
							
						} catch (Exception ignored) {
						}
						
						try {
							
							direct.intervalMs =
							Long.parseLong(
							interval == null
							? "0"
							: interval.replaceAll(
							"[^0-9]",
							""
							)
							)
							*
							1000L;
							
						} catch (Exception ignored) {
						}
						
					} catch (Exception ignored) {
					}
					
					return direct;
				}
			}
			
			final CgPreviewMetaResolver cgPreviewMeta =
			new CgPreviewMetaResolver();
			
			try {
				
				if (
				episodesJson != null
				&&
				!episodesJson.trim().equals("")
				) {
					
					cgPreviewMeta.collect(
					new org.json.JSONTokener(
					episodesJson
					).nextValue(),
					"",
					0
					);
				}
				
			} catch (Exception ignored) {
			}
			
			// -------------------------------------------------
			// Memory cache
			// -------------------------------------------------
			final android.util.LruCache<String, android.graphics.Bitmap>
			cgPreviewCache =
			new android.util.LruCache<String, android.graphics.Bitmap>(
			24 * 1024 * 1024
			) {
				
				@Override
				protected int sizeOf(
				String key,
				android.graphics.Bitmap value
				) {
					
					try {
						
						return value.getByteCount();
						
					} catch (Exception ignored) {
						
						return 1;
					}
				}
			};
			
			final java.util.concurrent.ExecutorService cgPreviewWorker =
			java.util.concurrent.Executors.newSingleThreadExecutor();
			
			final java.lang.Runnable[] cgPreviewPending = {
				null
			};
			
			final int[] cgPreviewGeneration = {
				0
			};
			
			class CgPreviewEngine {
				
				android.graphics.Bitmap scaled(
				android.graphics.Bitmap bitmap
				) {
					
					if (bitmap == null) {
						return null;
					}
					
					try {
						
						return android.graphics.Bitmap.createScaledBitmap(
						bitmap,
						cgGlass.px(160),
						cgGlass.px(90),
						true
						);
						
					} catch (Exception ignored) {
						
						return bitmap;
					}
				}
				
				android.graphics.Bitmap spriteFrame(
				CgPreviewMeta meta,
				long positionMs
				) {
					
					if (
					meta == null
					||
					!meta.usableSprite()
					) {
						return null;
					}
					
					long index =
					positionMs
					/
					Math.max(
					1L,
					meta.intervalMs
					);
					
					long maxFrames =
					(long) meta.cols
					*
					(long) meta.rows;
					
					if (maxFrames <= 0L) {
						return null;
					}
					
					index =
					Math.max(
					0L,
					Math.min(
					maxFrames - 1L,
					index
					)
					);
					
					String key =
					"sprite|"
					+ meta.sprite
					+ "|"
					+ index;
					
					android.graphics.Bitmap cached =
					cgPreviewCache.get(
					key
					);
					
					if (
					cached != null
					&&
					!cached.isRecycled()
					) {
						return cached;
					}
					
					try {
						
						android.graphics.Bitmap sheet =
						cgImage.obtain(
						meta.sprite
						);
						
						if (
						sheet == null
						||
						sheet.isRecycled()
						) {
							return null;
						}
						
						int tileWidth =
						sheet.getWidth()
						/
						meta.cols;
						
						int tileHeight =
						sheet.getHeight()
						/
						meta.rows;
						
						if (
						tileWidth <= 0
						||
						tileHeight <= 0
						) {
							return null;
						}
						
						int column =
						(int) (
						index
						%
						meta.cols
						);
						
						int row =
						(int) (
						index
						/
						meta.cols
						);
						
						int x =
						Math.max(
						0,
						column * tileWidth
						);
						
						int y =
						Math.max(
						0,
						row * tileHeight
						);
						
						if (
						x + tileWidth
						>
						sheet.getWidth()
						||
						y + tileHeight
						>
						sheet.getHeight()
						) {
							return null;
						}
						
						android.graphics.Bitmap frame =
						android.graphics.Bitmap.createBitmap(
						sheet,
						x,
						y,
						tileWidth,
						tileHeight
						);
						
						frame =
						scaled(
						frame
						);
						
						if (frame != null) {
							
							cgPreviewCache.put(
							key,
							frame
							);
						}
						
						return frame;
						
					} catch (Exception ignored) {
						
						return null;
					}
				}
				
				android.graphics.Bitmap retrieverFrame(
				String video,
				long positionMs
				) {
					
					if (
					video == null
					||
					video.trim().equals("")
					) {
						return null;
					}
					
					String lower =
					video.toLowerCase(
					java.util.Locale.US
					);
					
					// MediaMetadataRetriever ليس مناسباً لـ HLS.
					if (
					lower.contains(
					".m3u8"
					)
					) {
						return null;
					}
					
					long bucket =
					Math.max(
					0L,
					positionMs / 5000L
					);
					
					String key =
					"video|"
					+ video
					+ "|"
					+ bucket;
					
					android.graphics.Bitmap cached =
					cgPreviewCache.get(
					key
					);
					
					if (
					cached != null
					&&
					!cached.isRecycled()
					) {
						return cached;
					}
					
					android.media.MediaMetadataRetriever retriever =
					new android.media.MediaMetadataRetriever();
					
					try {
						
						if (
						video.startsWith(
						"http://"
						)
						||
						video.startsWith(
						"https://"
						)
						) {
							
							retriever.setDataSource(
							video,
							new java.util.HashMap<String, String>()
							);
							
						} else {
							
							retriever.setDataSource(
							video
							);
						}
						
						long sampleMs =
						bucket * 5000L;
						
						android.graphics.Bitmap frame =
						retriever.getFrameAtTime(
						sampleMs * 1000L,
						android.media.MediaMetadataRetriever
						.OPTION_CLOSEST_SYNC
						);
						
						frame =
						scaled(
						frame
						);
						
						if (frame != null) {
							
							cgPreviewCache.put(
							key,
							frame
							);
						}
						
						return frame;
						
					} catch (Exception ignored) {
						
						return null;
						
					} finally {
						
						try {
							
							retriever.release();
							
						} catch (Exception ignored) {
						}
					}
				}
				
				void request(
				final long positionMs
				) {
					
					if (
					cgPreviewPending[0] != null
					) {
						
						uiHandler.removeCallbacks(
						cgPreviewPending[0]
						);
					}
					
					final int generation =
					++cgPreviewGeneration[0];
					
					cgPreviewPending[0] =
					new java.lang.Runnable() {
						
						@Override
						public void run() {
							
							try {
								
								cgPreviewWorker.execute(
								new java.lang.Runnable() {
									
									@Override
									public void run() {
										
										android.graphics.Bitmap frame =
										null;
										
										try {
											
											CgPreviewMeta meta =
											cgPreviewMeta.current();
											
											if (
											meta != null
											&&
											meta.usableSprite()
											) {
												
												frame =
												spriteFrame(
												meta,
												positionMs
												);
											}
											
											if (frame == null) {
												
												String video =
												currentVideo[0];
												
												frame =
												retrieverFrame(
												video,
												positionMs
												);
											}
											
										} catch (Exception ignored) {
										}
										
										final android.graphics.Bitmap result =
										frame;
										
										uiHandler.post(
										new java.lang.Runnable() {
											
											@Override
											public void run() {
												
												try {
													
													if (
													generation
													!=
													cgPreviewGeneration[0]
													) {
														return;
													}
													
													if (
													cgSeekPreview.getVisibility()
													!=
													android.view.View.VISIBLE
													) {
														return;
													}
													
													if (
													result != null
													&&
													!result.isRecycled()
													) {
														
														cgSeekPreviewImage.setImageBitmap(
														result
														);
														
														cgSeekPreviewImage.setVisibility(
														android.view.View.VISIBLE
														);
														
													} else {
														
														// إذا الصورة فشلت يبقى الوقت فقط.
														cgSeekPreviewImage.setImageDrawable(
														null
														);
														
														cgSeekPreviewImage.setVisibility(
														android.view.View.GONE
														);
													}
													
												} catch (Exception ignored) {
												}
											}
										}
										);
									}
								}
								);
								
							} catch (Exception ignored) {
							}
						}
					};
					
					// Debounce حتى لا نقرأ Frame بكل Pixel.
					uiHandler.postDelayed(
					cgPreviewPending[0],
					140L
					);
				}
				
				void position(
				android.view.MotionEvent event
				) {
					
					try {
						
						int[] seekLocation =
						new int[2];
						
						int[] rootLocation =
						new int[2];
						
						timeBar.getLocationInWindow(
						seekLocation
						);
						
						playerHost.getLocationInWindow(
						rootLocation
						);
						
						float centerX =
						seekLocation[0]
						-
						rootLocation[0]
						+
						event.getX();
						
						float left =
						centerX
						-
						cgSeekPreview.getWidth()
						/
						2f;
						
						float maxLeft =
						playerHost.getWidth()
						-
						cgSeekPreview.getWidth()
						-
						cgGlass.px(6);
						
						left =
						Math.max(
						cgGlass.px(6),
						Math.min(
						maxLeft,
						left
						)
						);
						
						float top =
						seekLocation[1]
						-
						rootLocation[1]
						-
						cgSeekPreview.getHeight()
						-
						cgGlass.px(8);
						
						top =
						Math.max(
						cgGlass.px(6),
						top
						);
						
						cgSeekPreview.setX(
						left
						);
						
						cgSeekPreview.setY(
						top
						);
						
					} catch (Exception ignored) {
					}
				}
			}
			
			final CgPreviewEngine cgPreviewEngine =
			new CgPreviewEngine();
			
			timeBar.setOnTouchListener(
			new android.view.View.OnTouchListener() {
				
				@Override
				public boolean onTouch(
				android.view.View view,
				android.view.MotionEvent event
				) {
					
					try {
						
						int action =
						event.getActionMasked();
						
						if (
						action
						==
						android.view.MotionEvent.ACTION_DOWN
						||
						action
						==
						android.view.MotionEvent.ACTION_MOVE
						) {
							
							com.google.android.exoplayer2.Player activePlayer =
							exoView.getPlayer();
							
							if (activePlayer == null) {
								activePlayer = exoPlayer;
							}
							
							long duration =
							activePlayer.getDuration();
							
							if (
							duration <= 0L
							||
							duration
							==
							com.google.android.exoplayer2.C.TIME_UNSET
							||
							timeBar.getWidth() <= 0
							) {
								return false;
							}
							
							float ratio =
							event.getX()
							/
							(float) timeBar.getWidth();
							
							ratio =
							Math.max(
							0f,
							Math.min(
							1f,
							ratio
							)
							);
							
							long target =
							(long) (
							duration
							*
							ratio
							);
							
							cgSeekPreviewTime.setText(
							playerTimeEngine.format(
							target
							)
							);
							
							if (
							cgSeekPreview.getVisibility()
							!=
							android.view.View.VISIBLE
							) {
								
								cgSeekPreview.setAlpha(
								0f
								);
								
								cgSeekPreview.setVisibility(
								android.view.View.VISIBLE
								);
								
								cgSeekPreview.animate()
								.alpha(1f)
								.setDuration(100L)
								.start();
							}
							
							cgPreviewEngine.position(
							event
							);
							
							cgPreviewEngine.request(
							target
							);
							
						} else if (
						action
						==
						android.view.MotionEvent.ACTION_UP
						||
						action
						==
						android.view.MotionEvent.ACTION_CANCEL
						) {
							
							++cgPreviewGeneration[0];
							
							if (
							cgPreviewPending[0] != null
							) {
								
								uiHandler.removeCallbacks(
								cgPreviewPending[0]
								);
							}
							
							cgSeekPreview.animate()
							.cancel();
							
							cgSeekPreview.animate()
							.alpha(0f)
							.setDuration(120L)
							.withEndAction(
							new java.lang.Runnable() {
								
								@Override
								public void run() {
									
									try {
										
										cgSeekPreview.setVisibility(
										android.view.View.GONE
										);
										
									} catch (Exception ignored) {
									}
								}
							}
							)
							.start();
						}
						
					} catch (Exception ignored) {
					}
					
					// مهم: false حتى SeekBar القديم يبقى يستلم السحب.
					return false;
				}
			}
			);
			
			cgSeekPreview.addOnAttachStateChangeListener(
			new android.view.View.OnAttachStateChangeListener() {
				
				@Override
				public void onViewAttachedToWindow(
				android.view.View view
				) {
				}
				
				@Override
				public void onViewDetachedFromWindow(
				android.view.View view
				) {
					
					try {
						
						++cgPreviewGeneration[0];
						
						cgPreviewWorker.shutdownNow();
						
					} catch (Exception ignored) {
					}
				}
			}
			);
			
		} catch (Exception ignored) {
		}
		
		
		try {
			
			final com.google.android.gms.cast.framework.CastContext cgCastContext =
			com.google.android.gms.cast.framework.CastContext.getSharedInstance(
			PlayerActivity.this
			);
			
			final boolean[] cgCasting = { false };
			final String[] cgCastLoadedUrl = { "" };
			
			final com.google.android.gms.cast.framework.CastSession[] cgCastSession = {
				cgCastContext.getSessionManager().getCurrentCastSession()
			};
			
			final android.widget.FrameLayout cgCastButtonShell =
			new android.widget.FrameLayout(PlayerActivity.this);
			
			cgGlass.bind(cgCastButtonShell, 22, true);
			
			final androidx.mediarouter.app.MediaRouteButton cgCastButton =
			new androidx.mediarouter.app.MediaRouteButton(PlayerActivity.this);
			
			cgCastButton.setContentDescription("البث على التلفاز");
			
			cgCastButtonShell.addView(
			cgCastButton,
			new android.widget.FrameLayout.LayoutParams(-1, -1)
			);
			
			com.google.android.gms.cast.framework.CastButtonFactory
			.setUpMediaRouteButton(
			PlayerActivity.this,
			cgCastButton
			);
			
			android.widget.FrameLayout.LayoutParams cgCastLp =
			new android.widget.FrameLayout.LayoutParams(
			cgGlass.px(44),
			cgGlass.px(44)
			);
			
			cgCastLp.gravity =
			android.view.Gravity.TOP | android.view.Gravity.RIGHT;
			
			cgCastLp.setMargins(
			0,
			cgGlass.px(14),
			cgGlass.px(170),
			0
			);
			
			playerHost.addView(cgCastButtonShell, cgCastLp);
			
			class CgCastBridge {
				
				com.google.android.gms.cast.framework.media.RemoteMediaClient remote() {
					
					try {
						
						com.google.android.gms.cast.framework.CastSession session =
						cgCastSession[0];
						
						if (
						session != null
						&&
						session.isConnected()
						) {
							
							return session.getRemoteMediaClient();
						}
						
					} catch (Exception ignored) {}
					
					return null;
				}
				
				String contentType(String url) {
					
					if (url == null) return "video/mp4";
					
					String lower =
					url.toLowerCase(java.util.Locale.US);
					
					if (lower.contains(".m3u8")) {
						return "application/x-mpegURL";
					}
					
					if (lower.contains(".webm")) {
						return "video/webm";
					}
					
					return "video/mp4";
				}
				
				com.google.android.gms.cast.MediaInfo mediaInfo(String url) {
					
					com.google.android.gms.cast.MediaMetadata metadata =
					new com.google.android.gms.cast.MediaMetadata(
					com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE
					);
					
					try {
						
						metadata.putString(
						com.google.android.gms.cast.MediaMetadata.KEY_TITLE,
						mediaTitle == null ? "" : mediaTitle
						);
						
					} catch (Exception ignored) {}
					
					try {
						
						if (
						mediaPoster != null
						&&
						!mediaPoster.trim().equals("")
						) {
							
							metadata.addImage(
							new com.google.android.gms.common.images.WebImage(
							android.net.Uri.parse(mediaPoster)
							)
							);
						}
						
					} catch (Exception ignored) {}
					
					return new com.google.android.gms.cast.MediaInfo.Builder(url)
					.setStreamType(
					com.google.android.gms.cast.MediaInfo.STREAM_TYPE_BUFFERED
					)
					.setContentType(contentType(url))
					.setMetadata(metadata)
					.build();
				}
				
				long remotePosition() {
					
					try {
						
						com.google.android.gms.cast.framework.media.RemoteMediaClient r =
						remote();
						
						if (r != null) {
							return Math.max(0L, r.getApproximateStreamPosition());
						}
						
					} catch (Exception ignored) {}
					
					return 0L;
				}
				
				long remoteDuration() {
					
					try {
						
						com.google.android.gms.cast.framework.media.RemoteMediaClient r =
						remote();
						
						if (r != null) {
							return Math.max(0L, r.getStreamDuration());
						}
						
					} catch (Exception ignored) {}
					
					return 0L;
				}
				
				boolean remotePlaying() {
					
					try {
						
						com.google.android.gms.cast.framework.media.RemoteMediaClient r =
						remote();
						
						return r != null && r.isPlaying();
						
					} catch (Exception ignored) {}
					
					return false;
				}
				
				void saveRemoteProgress() {
					
					try {
						
						if (
						!cgCasting[0]
						||
						watchProgressKey[0] == null
						||
						watchProgressKey[0].equals("")
						) return;
						
						long position = remotePosition();
						long duration = remoteDuration();
						
						if (position >= 5000L) {
							
							watchProgress.edit()
							.putLong(watchProgressKey[0], position)
							.apply();
							
							try {
								continueWatchingStore.save(position, duration);
							} catch (Exception ignored) {}
						}
						
						android.content.SharedPreferences state =
						getSharedPreferences(
						"episode_watch_state",
						MODE_PRIVATE
						);
						
						android.content.SharedPreferences.Editor editor =
						state.edit();
						
						editor.putLong(
						watchProgressKey[0] + "|position",
						position
						);
						
						if (duration > 0L) {
							editor.putLong(
							watchProgressKey[0] + "|duration",
							duration
							);
						}
						
						if (
						duration > 0L
						&&
						position * 100L >= duration * 90L
						) {
							editor.putBoolean(
							watchProgressKey[0] + "|watched",
							true
							);
						}
						
						editor.apply();
						
					} catch (Exception ignored) {}
				}
				
				void loadCurrent(long position, boolean autoPlay) {
					
					try {
						
						com.google.android.gms.cast.framework.media.RemoteMediaClient r =
						remote();
						
						if (r == null) return;
						
						String url =
						currentVideo[0] == null
						? ""
						: currentVideo[0].trim();
						
						if (url.equals("")) return;
						
						r.load(
						mediaInfo(url),
						autoPlay,
						Math.max(0L, position)
						);
						
						cgCastLoadedUrl[0] = url;
						
					} catch (Exception error) {
						
						try {
							android.widget.Toast.makeText(
							PlayerActivity.this,
							"تعذر إرسال الفيديو إلى التلفاز",
							android.widget.Toast.LENGTH_SHORT
							).show();
						} catch (Exception ignored) {}
					}
				}
				
				void startCast(
				com.google.android.gms.cast.framework.CastSession session
				) {
					
					try {
						
						if (session == null) return;
						
						cgCastSession[0] = session;
						
						long localPosition =
						Math.max(0L, exoPlayer.getCurrentPosition());
						
						boolean localPlaying =
						exoPlayer.getPlayWhenReady();
						
						episodeController.saveCurrentProgress();
						
						exoPlayer.pause();
						
						cgCasting[0] = true;
						
						uiHandler.removeCallbacks(updateProgress);
						
						loadCurrent(
						localPosition,
						localPlaying
						);
						
					} catch (Exception ignored) {}
				}
				
				void stopCastAndResumeLocal() {
					
					try {
						
						long position = remotePosition();
						boolean shouldPlay = remotePlaying();
						
						saveRemoteProgress();
						
						cgCasting[0] = false;
						cgCastLoadedUrl[0] = "";
						
						exoPlayer.seekTo(Math.max(0L, position));
						exoPlayer.setPlayWhenReady(shouldPlay);
						
						uiHandler.removeCallbacks(updateProgress);
						uiHandler.post(updateProgress);
						
					} catch (Exception ignored) {}
				}
				
				void playPause() {
					
					try {
						
						if (!cgCasting[0]) {
							
							if (exoPlayer.isPlaying()) {
								exoPlayer.pause();
							} else {
								exoPlayer.play();
							}
							
							return;
						}
						
						com.google.android.gms.cast.framework.media.RemoteMediaClient r =
						remote();
						
						if (r == null) return;
						
						if (r.isPlaying()) {
							r.pause();
						} else {
							r.play();
						}
						
					} catch (Exception ignored) {}
				}
				
				void seekBy(long delta) {
					
					try {
						
						if (!cgCasting[0]) {
							
							long target =
							Math.max(
							0L,
							exoPlayer.getCurrentPosition() + delta
							);
							
							long duration = exoPlayer.getDuration();
							
							if (
							duration > 0L
							&&
							duration != com.google.android.exoplayer2.C.TIME_UNSET
							) {
								target = Math.min(target, duration);
							}
							
							exoPlayer.seekTo(target);
							
							return;
						}
						
						com.google.android.gms.cast.framework.media.RemoteMediaClient r =
						remote();
						
						if (r == null) return;
						
						long target =
						Math.max(
						0L,
						remotePosition() + delta
						);
						
						long duration = remoteDuration();
						
						if (duration > 0L) {
							target = Math.min(target, duration);
						}
						
						r.seek(target);
						
					} catch (Exception ignored) {}
				}
				
				void seekTo(long position) {
					
					try {
						
						if (!cgCasting[0]) {
							CigramSeekGuard.seek(exoPlayer, Math.max(0L, position));
							return;
						}
						
						com.google.android.gms.cast.framework.media.RemoteMediaClient r =
						remote();
						
						if (r != null) {
							r.seek(Math.max(0L, position));
						}
						
					} catch (Exception ignored) {}
				}
			}
			
			final CgCastBridge cgCastBridge =
			new CgCastBridge();
			
			final com.google.android.gms.cast.framework.SessionManagerListener<
			com.google.android.gms.cast.framework.CastSession
			> cgSessionListener =
			new com.google.android.gms.cast.framework.SessionManagerListener<
			com.google.android.gms.cast.framework.CastSession
			>() {
				
				@Override
				public void onSessionStarting(
				com.google.android.gms.cast.framework.CastSession session
				) {}
				
				@Override
				public void onSessionStarted(
				com.google.android.gms.cast.framework.CastSession session,
				String sessionId
				) {
					
					try {
						cgCastBridge.startCast(session);
					} catch (Exception ignored) {}
				}
				
				@Override
				public void onSessionStartFailed(
				com.google.android.gms.cast.framework.CastSession session,
				int error
				) {
					
					cgCasting[0] = false;
				}
				
				@Override
				public void onSessionEnding(
				com.google.android.gms.cast.framework.CastSession session
				) {
					
					try {
						cgCastBridge.saveRemoteProgress();
					} catch (Exception ignored) {}
				}
				
				@Override
				public void onSessionEnded(
				com.google.android.gms.cast.framework.CastSession session,
				int error
				) {
					
					try {
						cgCastBridge.stopCastAndResumeLocal();
					} catch (Exception ignored) {}
				}
				
				@Override
				public void onSessionResuming(
				com.google.android.gms.cast.framework.CastSession session,
				String sessionId
				) {}
				
				@Override
				public void onSessionResumed(
				com.google.android.gms.cast.framework.CastSession session,
				boolean wasSuspended
				) {
					
					try {
						cgCastBridge.startCast(session);
					} catch (Exception ignored) {}
				}
				
				@Override
				public void onSessionResumeFailed(
				com.google.android.gms.cast.framework.CastSession session,
				int error
				) {
					
					cgCasting[0] = false;
				}
				
				@Override
				public void onSessionSuspended(
				com.google.android.gms.cast.framework.CastSession session,
				int reason
				) {
					
					try {
						cgCastBridge.saveRemoteProgress();
					} catch (Exception ignored) {}
				}
			};
			
			cgCastContext
			.getSessionManager()
			.addSessionManagerListener(
			cgSessionListener,
			com.google.android.gms.cast.framework.CastSession.class
			);
			
			try {
				
				if (
				cgCastSession[0] != null
				&&
				cgCastSession[0].isConnected()
				) {
					
					cgCastBridge.startCast(
					cgCastSession[0]
					);
				}
				
			} catch (Exception ignored) {}
			
			final java.lang.Runnable cgCastProgress =
			new java.lang.Runnable() {
				
				@Override
				public void run() {
					
					try {
						
						if (cgCasting[0]) {
							
							long position =
							cgCastBridge.remotePosition();
							
							long duration =
							cgCastBridge.remoteDuration();
							
							currentTime.setText(
							playerTimeEngine.format(position)
							);
							
							if (duration > 0L) {
								
								totalTime.setText(
								playerTimeEngine.format(duration)
								);
								
								int max =
								Math.max(
								1,
								(int) Math.min(
								Integer.MAX_VALUE,
								duration / 1000L
								)
								);
								
								timeBar.setMax(max);
								
								if (!dragging[0]) {
									
									timeBar.setProgress(
									Math.max(
									0,
									Math.min(
									max,
									(int) (position / 1000L)
									)
									)
									);
								}
							}
							
							playButton.setImageResource(
							cgCastBridge.remotePlaying()
							? android.R.drawable.ic_media_pause
							: android.R.drawable.ic_media_play
							);
							
							playButton.setColorFilter(
							android.graphics.Color.WHITE,
							android.graphics.PorterDuff.Mode.SRC_IN
							);
							
							cgCastBridge.saveRemoteProgress();
						}
						
					} catch (Exception ignored) {}
					
					uiHandler.postDelayed(this, 500L);
				}
			};
			
			uiHandler.post(cgCastProgress);
			
			final java.lang.Runnable cgCastMediaWatcher =
			new java.lang.Runnable() {
				
				@Override
				public void run() {
					
					try {
						
						if (cgCasting[0]) {
							
							String url =
							currentVideo[0] == null
							? ""
							: currentVideo[0].trim();
							
							if (
							!url.equals("")
							&&
							!url.equals(cgCastLoadedUrl[0])
							) {
								
								cgCastBridge.saveRemoteProgress();
								
								exoPlayer.pause();
								
								long resume =
								watchProgress.getLong(
								watchProgressKey[0],
								0L
								);
								
								cgCastBridge.loadCurrent(
								resume,
								true
								);
							}
						}
						
					} catch (Exception ignored) {}
					
					uiHandler.postDelayed(this, 450L);
				}
			};
			
			uiHandler.post(cgCastMediaWatcher);
			
			playButton.setOnClickListener(
			new android.view.View.OnClickListener() {
				
				@Override
				public void onClick(android.view.View view) {
					
					try {
						playerCenterButtonStyle.press(playButton);
						cgCastBridge.playPause();
					} catch (Exception ignored) {}
				}
			}
			);
			
			rewindButton.setOnClickListener(
			new android.view.View.OnClickListener() {
				
				@Override
				public void onClick(android.view.View view) {
					
					try {
						
						playerCenterButtonStyle.press(rewindButton);
						
						cgCastBridge.seekBy(
						-seekStepMs[0]
						);
						
						seekFeedbackController.show(
						"-"
						+
						(seekStepMs[0] / 1000L)
						+
						" ث"
						);
						
					} catch (Exception ignored) {}
				}
			}
			);
			
			forwardButton.setOnClickListener(
			new android.view.View.OnClickListener() {
				
				@Override
				public void onClick(android.view.View view) {
					
					try {
						
						playerCenterButtonStyle.press(forwardButton);
						
						cgCastBridge.seekBy(
						seekStepMs[0]
						);
						
						seekFeedbackController.show(
						"+"
						+
						(seekStepMs[0] / 1000L)
						+
						" ث"
						);
						
					} catch (Exception ignored) {}
				}
			}
			);
			
			timeBar.setOnSeekBarChangeListener(
			new android.widget.SeekBar.OnSeekBarChangeListener() {
				
				@Override
				public void onProgressChanged(
				android.widget.SeekBar bar,
				int progress,
				boolean fromUser
				) {
					
					if (!fromUser) return;
					
					try {
						
						long duration =
						cgCasting[0]
						? cgCastBridge.remoteDuration()
						: playerTimeEngine.getDurationMs();
						
						if (duration <= 0L) return;
						
						int max =
						Math.max(1, bar.getMax());
						
						long target =
						duration
						*
						Math.max(
						0,
						Math.min(max, progress)
						)
						/
						max;
						
						pendingSeekPosition[0] = target;
						
						currentTime.setText(
						playerTimeEngine.format(target)
						);
						
						if (!cgCasting[0]) CigramSeekPreview.move(target, duration);
						
					} catch (Exception ignored) {}
				}
				
				@Override
				public void onStartTrackingTouch(
				android.widget.SeekBar bar
				) {
					
					dragging[0] = true;
					
					try { if (!cgCasting[0]) CigramSeekPreview.start(PlayerActivity.this, bar, exoPlayer); } catch (Throwable ignored) {}
					
					try {
						
						pendingSeekPosition[0] =
						cgCasting[0]
						? cgCastBridge.remotePosition()
						: playerTimeEngine.getPositionMs();
						
					} catch (Exception ignored) {}
				}
				
				@Override
				public void onStopTrackingTouch(
				android.widget.SeekBar bar
				) {
					
					dragging[0] = false;
					
					CigramSeekPreview.stop();
					
					try {
						
						if (pendingSeekPosition[0] >= 0L) {
							cgCastBridge.seekTo(
							pendingSeekPosition[0]
							);
						}
						
					} catch (Exception ignored) {}
					
					pendingSeekPosition[0] = -1L;
					
					if (hideControlsHolder[0] != null) {
						
						uiHandler.postDelayed(
						hideControlsHolder[0],
						3000L
						);
					}
				}
			}
			);
			
			cgCastButtonShell.addOnAttachStateChangeListener(
			new android.view.View.OnAttachStateChangeListener() {
				
				@Override
				public void onViewAttachedToWindow(
				android.view.View view
				) {}
				
				@Override
				public void onViewDetachedFromWindow(
				android.view.View view
				) {
					
					try {
						
						cgCastBridge.saveRemoteProgress();
						
						uiHandler.removeCallbacks(cgCastProgress);
						uiHandler.removeCallbacks(cgCastMediaWatcher);
						
						cgCastContext
						.getSessionManager()
						.removeSessionManagerListener(
						cgSessionListener,
						com.google.android.gms.cast.framework.CastSession.class
						);
						
					} catch (Exception ignored) {}
				}
			}
			);
			
		} catch (Exception castError) {
			
			try {
				android.util.Log.w(
				"CigramCast",
				"Cast unavailable",
				castError
				);
			} catch (Exception ignored) {}
		}
		
		/* v10.68: disabled - this leftover template block referenced a non-existent view named root (build error)
// كود إضافة WebView (مشغل VidBolt) داخل onCreate في Sketchware Pro
// ملاحظة: تأكد من أن 'root' هو اسم الحاوية (ViewGroup) الأساسية للنشاط الخاص بك.

try {
    // 1. إنشاء المشغل
    android.webkit.WebView vidboltView = new android.webkit.WebView(this);

    // 2. إعدادات المشغل (ضرورية لتشغيل الفيديو والـ iframes)
    vidboltView.getSettings().setJavaScriptEnabled(true);
    vidboltView.getSettings().setDomStorageEnabled(true);
    vidboltView.getSettings().setMediaPlaybackRequiresUserGesture(false); // للسماح بالتشغيل التلقائي

    // 3. ضبط أبعاد المشغل ليملأ الحاوية
    vidboltView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT));

    // 4. إضافة المشغل إلى الحاوية الأساسية للنشاط (استبدل 'root' باسم الحاوية في نشاطك إذا كان مختلفاً)
    root.addView(vidboltView);

    // 5. تحميل كود الـ Iframe الخاص بـ VidBolt
    // يمكنك استبدال الرابط بمتغير ديناميكي حسب الفيلم المختار
    String iframeHtml = "<iframe id=\"vidbolt-player\" " +
                        "src=\"https://vidbolt.pro/movie/1007757\" " +
                        "width=\"100%\" height=\"100%\" frameborder=\"0\" " +
                        "allowfullscreen allow=\"autoplay; encrypted-media; picture-in-picture\"></iframe>";

    vidboltView.loadDataWithBaseURL("https://vidbolt.pro", iframeHtml, "text/html", "utf-8", null);

} catch (Exception e) {
    android.util.Log.e("CigramVid", "خطأ في تحميل مشغل VidBolt", e);
}
*/
		
	}
	
	@Override
	public void onResume() {
		super.onResume();
		// PlayerActivity > Event > onResume > Add Source Directly
		// Fallback لإعادة الكنترول بعد الخروج من PiP.
		// لا يوقف الفيديو.
		
		try {
			CigramStage1InitProvider.applyPlayerEdgeToEdge(PlayerActivity.this);
			final android.view.View cgResumeDecor = getWindow().getDecorView();
			if (cgResumeDecor != null) {
				cgResumeDecor.postDelayed(new java.lang.Runnable() {
					@Override public void run() {
						CigramStage1InitProvider.applyPlayerEdgeToEdge(PlayerActivity.this);
						try {
							android.view.View root = findViewById(android.R.id.content);
							if (root != null && android.os.Build.VERSION.SDK_INT >= 20) {
								root.requestApplyInsets();
							}
						} catch (Throwable ignored) {}
					}
				}, 140L);
			}
		} catch (Throwable ignored) {}
		
		try {
			
			if (
			android.os.Build.VERSION.SDK_INT
			>=
			android.os.Build.VERSION_CODES.O
			&&
			!isInPictureInPictureMode()
			) {
				
				android.view.View root =
				findViewById(
				android.R.id.content
				);
				
				Object raw =
				root.getTag(
				0x7f0f0c61
				);
				
				if (
				raw
				instanceof
				java.util.HashMap
				) {
					
					java.util.HashMap state =
					(java.util.HashMap) raw;
					
					boolean series =
					java.lang.Boolean.TRUE.equals(
					state.get(
					"is_series"
					)
					);
					
					Object controlsRaw =
					state.get(
					"controls"
					);
					
					if (
					controlsRaw
					instanceof
					android.view.View[]
					) {
						
						android.view.View[] controls =
						(android.view.View[]) controlsRaw;
						
						for (
						android.view.View control :
						controls
						) {
							
							if (control != null) {
								
								control.setVisibility(
								android.view.View.VISIBLE
								);
							}
						}
					}
					
					if (!series) {
						
						try {
							
							Object controlsRaw2 =
							state.get(
							"controls"
							);
							
							if (
							controlsRaw2
							instanceof
							android.view.View[]
							) {
								
								android.view.View[] controls =
								(android.view.View[]) controlsRaw2;
								
								// ترتيب المصفوفة:
								// center, bottom, settings, seriesInfo, episodes, pip
								if (controls.length > 4) {
									
									controls[3].setVisibility(
									android.view.View.GONE
									);
									
									controls[4].setVisibility(
									android.view.View.GONE
									);
								}
							}
							
						} catch (Exception ignored) {
						}
					}
				}
			}
			
		} catch (Exception ignored) {
		}
		
	}
	
	@Override
	public void onStop() {
		super.onStop();
		try {
			
			final android.view.View cgStopRoot =
			findViewById(
			android.R.id.content
			);
			
			final android.os.Handler cgStopHandler =
			new android.os.Handler(
			android.os.Looper.getMainLooper()
			);
			
			final int[] cgStopAttempts = { 0 };
			
			final java.lang.Runnable[] cgStopCheck =
			new java.lang.Runnable[1];
			
			cgStopCheck[0] =
			new java.lang.Runnable() {
				
				@Override
				public void run() {
					
					try {
						
						cgStopAttempts[0]++;
						
						boolean inPip =
						android.os.Build.VERSION.SDK_INT
						>=
						android.os.Build.VERSION_CODES.O
						&&
						isInPictureInPictureMode();
						
						if (inPip) {
							
							if (
							cgStopAttempts[0]
							<
							12
							) {
								
								cgStopHandler.postDelayed(
								this,
								250L
								);
							}
							
							return;
						}
						
						if (hasWindowFocus()) {
							return;
						}
						
						if (cgStopRoot == null) {
							return;
						}
						
						Object raw =
						cgStopRoot.getTag(
						0x7f0f0c61
						);
						
						if (
						raw
						instanceof
						java.util.HashMap
						) {
							
							java.util.HashMap state =
							(java.util.HashMap) raw;
							
							Object playerRaw =
							state.get(
							"player"
							);
							
							if (
							playerRaw
							instanceof
							com.google.android.exoplayer2.ExoPlayer
							) {
								
								com.google.android.exoplayer2.ExoPlayer player =
								(com.google.android.exoplayer2.ExoPlayer) playerRaw;
								
								try {
									player.setPlayWhenReady(false);
								} catch (Exception ignored) {
								}
								
								try {
									player.pause();
								} catch (Exception ignored) {
								}
								
								try {
									player.stop();
								} catch (Exception ignored) {
								}
								
								try {
									player.clearMediaItems();
								} catch (Exception ignored) {
								}
								
								try {
									player.release();
								} catch (Exception ignored) {
								}
							}
						}
						
						try {
							
							if (!isFinishing()) {
								finish();
							}
							
						} catch (Exception ignored) {
						}
						
					} catch (Exception ignored) {
					}
				}
			};
			
			cgStopHandler.postDelayed(
			cgStopCheck[0],
			350L
			);
			
		} catch (Exception ignored) {
		}
	}
	public void _loadPlayer() {
		
	}
	
}
