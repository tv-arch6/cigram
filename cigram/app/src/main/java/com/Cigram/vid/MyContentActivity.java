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

public class MyContentActivity extends AppCompatActivity {
	
	private MyContentBinding binding;
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		binding = MyContentBinding.inflate(getLayoutInflater());
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
			void choose(android.view.View anchor,String title,final String[] options,int chosen,
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
				int height=Math.min(context.getResources().getDisplayMetrics().heightPixels*3/4,px(84+options.length*57));
				holder[0]=popup(panel,anchor,-1,height,android.view.Gravity.BOTTOM);
			}
		}
		// This block also runs inside network listeners, where `this`
		// is the listener itself rather than the Activity.  The application context is
		// available from both scopes.
		final CigramGlass cgGlass = new CigramGlass(getApplicationContext());
		final android.content.SharedPreferences auth =
		getSharedPreferences(
		"auth_session",
		MODE_PRIVATE
		);
		
		final boolean loggedIn =
		auth.getBoolean(
		"logged_in",
		false
		);
		
		final String token =
		auth.getString(
		"token",
		""
		);
		
		if (
		!loggedIn
		||
		token == null
		||
		token.trim().equals("")
		) {
			
			android.widget.Toast.makeText(
			MyContentActivity.this,
			"يجب تسجيل الدخول أولاً",
			android.widget.Toast.LENGTH_SHORT
			).show();
			
			finish();
			return;
		}
		
		
		final float dp =
		getResources()
		.getDisplayMetrics()
		.density;
		
		
		// ========================================
		// VIEWS
		// ========================================
		
		final android.widget.LinearLayout root =
		(android.widget.LinearLayout) findViewById(
		R.id.linear_my_content_root
		);
		
		final android.widget.LinearLayout header =
		(android.widget.LinearLayout) findViewById(
		R.id.linear_my_content_header
		);
		
		final android.widget.ImageView back =
		(android.widget.ImageView) findViewById(
		R.id.imageview_my_content_back
		);
		
		final android.widget.TextView pageTitle =
		(android.widget.TextView) findViewById(
		R.id.textview_my_content_title
		);
		
		final android.widget.TextView continueTitle =
		(android.widget.TextView) findViewById(
		R.id.textview_continue_title
		);
		
		final android.widget.TextView favoritesTitle =
		(android.widget.TextView) findViewById(
		R.id.textview_favorites_title
		);
		
		final android.widget.TextView myListTitle =
		(android.widget.TextView) findViewById(
		R.id.textview_my_list_title
		);
		
		final android.widget.LinearLayout continueBox =
		(android.widget.LinearLayout) findViewById(
		R.id.linear_continue_watching
		);
		
		final android.widget.LinearLayout favoritesBox =
		(android.widget.LinearLayout) findViewById(
		R.id.linear_favorites
		);
		
		final android.widget.LinearLayout myListBox =
		(android.widget.LinearLayout) findViewById(
		R.id.linear_my_list
		);
		
		final android.widget.HorizontalScrollView continueScroll =
		(android.widget.HorizontalScrollView) findViewById(
		R.id.hscroll_continue_watching
		);
		
		final android.widget.HorizontalScrollView favoritesScroll =
		(android.widget.HorizontalScrollView) findViewById(
		R.id.hscroll_favorites
		);
		
		final android.widget.HorizontalScrollView myListScroll =
		(android.widget.HorizontalScrollView) findViewById(
		R.id.hscroll_my_list
		);
		
		
		// ========================================
		// PAGE SETUP
		// ========================================
		
		if (root != null) {
			root.setLayoutDirection(
			android.view.View.LAYOUT_DIRECTION_RTL
			);
		}
		
		if (header != null) {
			header.setLayoutDirection(
			android.view.View.LAYOUT_DIRECTION_RTL
			);
		}
		
		android.widget.LinearLayout[] rtlRows =
		new android.widget.LinearLayout[] {
			continueBox,
			favoritesBox,
			myListBox
		};
		
		for (
		android.widget.LinearLayout row :
		rtlRows
		) {
			if (row != null) {
				row.setLayoutDirection(
				android.view.View.LAYOUT_DIRECTION_RTL
				);
				
				row.setGravity(
				android.view.Gravity.RIGHT
				|
				android.view.Gravity.CENTER_VERTICAL
				);
			}
		}
		
		android.widget.HorizontalScrollView[] rtlScrolls =
		new android.widget.HorizontalScrollView[] {
			continueScroll,
			favoritesScroll,
			myListScroll
		};
		
		for (
		android.widget.HorizontalScrollView scroll :
		rtlScrolls
		) {
			if (scroll != null) {
				scroll.setLayoutDirection(
				android.view.View.LAYOUT_DIRECTION_RTL
				);
				
				scroll.setFillViewport(
				true
				);
				
				scroll.setHorizontalScrollBarEnabled(
				false
				);
			}
		}
		
		if (pageTitle != null) {
			pageTitle.setText("محتوياتي");
			pageTitle.setGravity(
			android.view.Gravity.RIGHT
			|
			android.view.Gravity.CENTER_VERTICAL
			);
			pageTitle.setTextDirection(
			android.view.View.TEXT_DIRECTION_RTL
			);
		}
		
		if (continueTitle != null) {
			continueTitle.setText("أكمل المشاهدة");
			continueTitle.setGravity(
			android.view.Gravity.RIGHT
			);
			continueTitle.setTextDirection(
			android.view.View.TEXT_DIRECTION_RTL
			);
		}
		
		if (favoritesTitle != null) {
			favoritesTitle.setText("المفضلة");
			favoritesTitle.setGravity(
			android.view.Gravity.RIGHT
			);
			favoritesTitle.setTextDirection(
			android.view.View.TEXT_DIRECTION_RTL
			);
		}
		
		if (myListTitle != null) {
			myListTitle.setText("قائمتي");
			myListTitle.setGravity(
			android.view.Gravity.RIGHT
			);
			myListTitle.setTextDirection(
			android.view.View.TEXT_DIRECTION_RTL
			);
		}
		
		
		try {
			
			android.graphics.Typeface sima =
			CigramTypefaces.get(
			getAssets(),
			CigramTypefaces.APP_FONT
			);
			
			if (pageTitle != null) {
				pageTitle.setTypeface(
				sima,
				android.graphics.Typeface.BOLD
				);
			}
			
			if (continueTitle != null) {
				continueTitle.setTypeface(
				sima,
				android.graphics.Typeface.BOLD
				);
			}
			
			if (favoritesTitle != null) {
				favoritesTitle.setTypeface(
				sima,
				android.graphics.Typeface.BOLD
				);
			}
			
			if (myListTitle != null) {
				myListTitle.setTypeface(
				sima,
				android.graphics.Typeface.BOLD
				);
			}
			
		} catch (Exception ignored) {
		}
		
		
		if (back != null) {
			
			back.setOnClickListener(
			new android.view.View.OnClickListener() {
				
				@Override
				public void onClick(
				android.view.View view
				) {
					
					CigramNav.back(MyContentActivity.this);
				}
			}
			);
		}
		
		
		// ========================================
		// HELPERS
		// ========================================
		
		class MyContentUi {
			
			void showEmpty(
			android.widget.LinearLayout parent,
			String message
			) {
				
				if (parent == null) {
					return;
				}
				
				parent.removeAllViews();
				
				final boolean actionState =
				parent == favoritesBox
				||
				parent == myListBox;
				
				if (!actionState) {
					android.widget.TextView empty =
					new android.widget.TextView(MyContentActivity.this);
					empty.setText(message);
					empty.setTextColor(0xFF8E8E94);
					empty.setTextSize(14);
					empty.setGravity(android.view.Gravity.CENTER);
					empty.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
					empty.setPadding((int)(16f * dp),0,(int)(16f * dp),0);
					try {
						empty.setTypeface(
						CigramTypefaces.get(
						getAssets(),
						CigramTypefaces.APP_FONT
						)
						);
					} catch (Exception ignored) {}
					parent.addView(
					empty,
					new android.widget.LinearLayout.LayoutParams(
					(int)(210f * dp),
					-1
					)
					);
					return;
				}
				
				final android.widget.LinearLayout state =
				new android.widget.LinearLayout(MyContentActivity.this);
				state.setOrientation(android.widget.LinearLayout.VERTICAL);
				state.setGravity(android.view.Gravity.CENTER);
				state.setPadding(
				(int)(18f * dp),
				(int)(16f * dp),
				(int)(18f * dp),
				(int)(16f * dp)
				);
				
				android.graphics.drawable.GradientDrawable stateBg =
				new android.graphics.drawable.GradientDrawable(
				android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
				new int[] { 0xFF102833, 0xFF071A23 }
				);
				stateBg.setCornerRadius(20f * dp);
				state.setBackground(stateBg);
				
				android.widget.ImageView icon =
				new android.widget.ImageView(MyContentActivity.this);
				icon.setImageResource(android.R.drawable.ic_media_play);
				icon.setColorFilter(
				android.graphics.Color.WHITE,
				android.graphics.PorterDuff.Mode.SRC_IN
				);
				icon.setPadding(
				(int)(12f * dp),
				(int)(12f * dp),
				(int)(12f * dp),
				(int)(12f * dp)
				);
				android.graphics.drawable.GradientDrawable iconBg =
				new android.graphics.drawable.GradientDrawable();
				iconBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
				iconBg.setColor(0xFFE51B16);
				icon.setBackground(iconBg);
				
				state.addView(
				icon,
				new android.widget.LinearLayout.LayoutParams(
				(int)(52f * dp),
				(int)(52f * dp)
				)
				);
				
				android.widget.TextView label =
				new android.widget.TextView(MyContentActivity.this);
				label.setText(message);
				label.setTextColor(0xFFD7E1E6);
				label.setTextSize(14);
				label.setGravity(android.view.Gravity.CENTER);
				label.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
				label.setPadding(0,(int)(10f * dp),0,(int)(10f * dp));
				
				android.widget.TextView cta =
				new android.widget.TextView(MyContentActivity.this);
				cta.setText("تصفح الأفلام");
				cta.setTextColor(android.graphics.Color.WHITE);
				cta.setTextSize(14);
				cta.setGravity(android.view.Gravity.CENTER);
				cta.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
				cta.setClickable(true);
				
				android.graphics.drawable.GradientDrawable ctaBg =
				new android.graphics.drawable.GradientDrawable();
				ctaBg.setColor(0xFFE51B16);
				ctaBg.setCornerRadius(22f * dp);
				cta.setBackground(ctaBg);
				
				try {
					android.graphics.Typeface sima =
					CigramTypefaces.get(
					getAssets(),
					CigramTypefaces.APP_FONT
					);
					label.setTypeface(sima);
					cta.setTypeface(sima, android.graphics.Typeface.BOLD);
				} catch (Exception ignored) {}
				
				state.addView(
				label,
				new android.widget.LinearLayout.LayoutParams(-1,-2)
				);
				
				android.widget.LinearLayout.LayoutParams ctaLp =
				new android.widget.LinearLayout.LayoutParams(
				(int)(138f * dp),
				(int)(42f * dp)
				);
				state.addView(cta, ctaLp);
				
				cta.setOnClickListener(
				new android.view.View.OnClickListener() {
					@Override
					public void onClick(android.view.View view) {
						android.content.Intent browse =
						new android.content.Intent(
						MyContentActivity.this,
						MainActivity.class
						);
						browse.addFlags(
						android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
						);
						startActivity(browse);
						overridePendingTransition(
						android.R.anim.fade_in,
						android.R.anim.fade_out
						);
					}
				}
				);
				
				android.widget.LinearLayout.LayoutParams stateLp =
				new android.widget.LinearLayout.LayoutParams(
				(int)(230f * dp),
				(int)(210f * dp)
				);
				stateLp.setMargins(
				(int)(8f * dp),
				(int)(8f * dp),
				(int)(8f * dp),
				(int)(8f * dp)
				);
				parent.addView(state, stateLp);
			}
			
			
			String formatTime(
			long ms
			) {
				
				if (ms <= 0L) {
					return "00:00";
				}
				
				return android.text.format.DateUtils
				.formatElapsedTime(
				ms / 1000L
				);
			}
			
			
			void loadImage(
			final android.widget.ImageView image,
			final String rawUrl
			) {
				
				if (image == null) {
					return;
				}
				
				android.graphics.drawable.GradientDrawable placeholder =
				new android.graphics.drawable.GradientDrawable(
				android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
				new int[] { 0xFF182A34, 0xFF0A171E }
				);
				placeholder.setCornerRadius(14f * dp);
				image.setBackground(placeholder);
				image.setClipToOutline(true);
				image.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
				image.setImageResource(android.R.drawable.ic_menu_gallery);
				image.setColorFilter(
				0x66FFFFFF,
				android.graphics.PorterDuff.Mode.SRC_IN
				);
				int phPad = (int)(28f * dp);
				image.setPadding(phPad, phPad, phPad, phPad);
				
				if (
				rawUrl == null
				||
				rawUrl.trim().equals("")
				) {
					return;
				}
				
				final String imageUrl = rawUrl.trim();
				final Object imageToken = new Object();
				image.setTag(imageToken);
				
				new java.lang.Thread(
				new java.lang.Runnable() {
					@Override
					public void run() {
						java.net.HttpURLConnection connection = null;
						java.io.InputStream input = null;
						try {
							java.net.URL url = new java.net.URL(imageUrl);
							connection = (java.net.HttpURLConnection) url.openConnection();
							connection.setConnectTimeout(10000);
							connection.setReadTimeout(15000);
							connection.setInstanceFollowRedirects(true);
							connection.setRequestProperty(
							"User-Agent",
							"Mozilla/5.0 (Android) Cigram/1.0"
							);
							connection.connect();
							if (
							connection.getResponseCode() < 200
							||
							connection.getResponseCode() >= 300
							) {
								return;
							}
							input = new java.io.BufferedInputStream(
							connection.getInputStream()
							);
							final android.graphics.Bitmap bitmap =
							android.graphics.BitmapFactory.decodeStream(input);
							if (bitmap == null) {
								return;
							}
							runOnUiThread(
							new java.lang.Runnable() {
								@Override
								public void run() {
									if (image.getTag() != imageToken) {
										return;
									}
									image.clearColorFilter();
									image.setPadding(0,0,0,0);
									image.setScaleType(
									android.widget.ImageView.ScaleType.CENTER_CROP
									);
									image.setImageBitmap(bitmap);
								}
							}
							);
						} catch (Exception ignored) {
						} finally {
							if (input != null) {
								try { input.close(); } catch (Exception ignored) {}
							}
							if (connection != null) {
								connection.disconnect();
							}
						}
					}
				}
				).start();
			}
			
			
			void addContinueCard(
			android.widget.LinearLayout parent,
			final org.json.JSONObject item
			) {
				
				if (parent == null || item == null) {
					return;
				}
				
				final String title = item.optString("title", "");
				final String seriesTitle = item.optString("series_title", "");
				final String type = item.optString("type", "movie");
				final String poster = item.optString("poster", "");
				final String video = item.optString("video", "");
				final String subtitle = item.optString("subtitle", "");
				final String season = item.optString("season", "");
				final String episode = item.optString("episode", "");
				final String id = item.optString("id", "");
				final String episodesJson = item.optString("series_episodes_json", "");
				
				final android.widget.FrameLayout card =
				new android.widget.FrameLayout(MyContentActivity.this);
				
				android.graphics.drawable.GradientDrawable rounded =
				new android.graphics.drawable.GradientDrawable();
				rounded.setColor(0xFF0D2029);
				rounded.setCornerRadius(14f * dp);
				card.setBackground(rounded);
				card.setClipToOutline(true);
				card.setClickable(true);
				card.setFocusable(true);
				card.setElevation(2f * dp);
				
				final android.widget.ImageView posterView =
				new android.widget.ImageView(MyContentActivity.this);
				posterView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
				posterView.setAdjustViewBounds(false);
				card.addView(
				posterView,
				new android.widget.FrameLayout.LayoutParams(-1,-1)
				);
				
				android.view.View overlay =
				new android.view.View(MyContentActivity.this);
				overlay.setBackgroundColor(0x66000000);
				card.addView(
				overlay,
				new android.widget.FrameLayout.LayoutParams(-1,-1)
				);
				
				final android.widget.ImageView play =
				new android.widget.ImageView(MyContentActivity.this);
				play.setImageResource(android.R.drawable.ic_media_play);
				play.setColorFilter(
				android.graphics.Color.WHITE,
				android.graphics.PorterDuff.Mode.SRC_IN
				);
				play.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
				play.setPadding(
				(int)(12f * dp),
				(int)(12f * dp),
				(int)(12f * dp),
				(int)(12f * dp)
				);
				android.graphics.drawable.GradientDrawable playBg =
				new android.graphics.drawable.GradientDrawable();
				playBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
				playBg.setColor(0xDDE51B16);
				play.setBackground(playBg);
				
				android.widget.FrameLayout.LayoutParams playLp =
				new android.widget.FrameLayout.LayoutParams(
				(int)(48f * dp),
				(int)(48f * dp)
				);
				playLp.gravity = android.view.Gravity.CENTER;
				card.addView(play, playLp);
				// CIGRAM v2.5: shared distinctive poster look (title, tag, time left, progress, sima font)
				CigramContinueStyle.decorate(
				MyContentActivity.this,
				card,
				item,
				true,
				overlay,
				play
				);
				
				android.widget.LinearLayout.LayoutParams cardLp =
				new android.widget.LinearLayout.LayoutParams(
				(int)(132f * dp),
				(int)(196f * dp)
				);
				cardLp.setMargins(
				(int)(5f * dp),
				(int)(5f * dp),
				(int)(5f * dp),
				(int)(5f * dp)
				);
				parent.addView(card, 0, cardLp);
				
				loadImage(posterView, poster);
				
				card.setOnTouchListener(
				new android.view.View.OnTouchListener() {
					@Override
					public boolean onTouch(
					android.view.View view,
					android.view.MotionEvent event
					) {
						if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
							view.animate().scaleX(1f).scaleY(1f).setDuration(70L).start();
						} else if (
						event.getAction() == android.view.MotionEvent.ACTION_UP
						||
						event.getAction() == android.view.MotionEvent.ACTION_CANCEL
						) {
							view.animate().scaleX(1f).scaleY(1f).setDuration(110L).start();
						}
						return false;
					}
				}
				);
				
				card.setOnClickListener(
				new android.view.View.OnClickListener() {
					@Override
					public void onClick(android.view.View view) {
						if (video == null || video.trim().equals("")) {
							android.widget.Toast.makeText(
							MyContentActivity.this,
							"رابط الفيديو غير متوفر",
							android.widget.Toast.LENGTH_SHORT
							).show();
							return;
						}
						android.content.Intent intent =
						new android.content.Intent(
						MyContentActivity.this,
						PlayerActivity.class
						);
						intent.putExtra("id", id);
						intent.putExtra("title", title);
						intent.putExtra("series_title", seriesTitle);
						intent.putExtra("type", type);
						intent.putExtra("poster", poster);
						intent.putExtra("video", video);
						intent.putExtra("movie_url", video);
						intent.putExtra("link", video);
						intent.putExtra("subtitle", subtitle);
						intent.putExtra("season", season);
						intent.putExtra("episode", episode);
						intent.putExtra("series_episodes_json", episodesJson);
						startActivity(intent);
						overridePendingTransition(
						android.R.anim.fade_in,
						android.R.anim.fade_out
						);
					}
				}
				);
			}
			
			void addFavoriteCard(
			android.widget.LinearLayout parent,
			final org.json.JSONObject item
			) {
				
				if (
				parent == null
				||
				item == null
				) {
					return;
				}
				
				
				final String title =
				item.optString(
				"title",
				""
				);
				
				final String seriesTitle =
				item.optString(
				"series_title",
				""
				);
				
				final String type =
				item.optString(
				"type",
				"movie"
				);
				
				final String poster =
				item.optString(
				"poster",
				""
				);
				
				final String backdrop =
				item.optString(
				"backdrop",
				""
				);
				
				final String video =
				item.optString(
				"video",
				""
				);
				
				final String subtitle =
				item.optString(
				"subtitle",
				""
				);
				
				final String season =
				item.optString(
				"season",
				""
				);
				
				final String episode =
				item.optString(
				"episode",
				""
				);
				
				final String id =
				item.optString(
				"id",
				""
				);
				
				final String episodesJson =
				item.optString(
				"series_episodes_json",
				""
				);
				
				
				final android.widget.LinearLayout card =
				new android.widget.LinearLayout(
				MyContentActivity.this
				);
				
				card.setOrientation(
				android.widget.LinearLayout.VERTICAL
				);
				
				card.setGravity(
				android.view.Gravity.CENTER
				);
				
				card.setLayoutDirection(
				android.view.View.LAYOUT_DIRECTION_RTL
				);
				
				card.setPadding(
				(int)(6f * dp),
				(int)(6f * dp),
				(int)(6f * dp),
				(int)(7f * dp)
				);
				
				
				android.graphics.drawable.GradientDrawable cardBg =
				new android.graphics.drawable.GradientDrawable();
				
				cardBg.setColor(
				0xFF0A2631
				);
				
				cardBg.setCornerRadius(
				14f * dp
				);
				
				card.setBackground(
				cardBg
				);
				
				card.setClickable(
				true
				);
				
				
				final android.widget.ImageView posterView =
				new android.widget.ImageView(
				MyContentActivity.this
				);
				
				posterView.setBackgroundColor(
				0xFF14262E
				);
				
				posterView.setScaleType(
				android.widget.ImageView.ScaleType.FIT_CENTER
				);
				
				posterView.setAdjustViewBounds(
				false
				);
				
				
				final android.widget.FrameLayout favoritePosterFrame=new android.widget.FrameLayout(MyContentActivity.this);
				cgGlass.bind(favoritePosterFrame,14,false);
				posterView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
				favoritePosterFrame.addView(posterView,new android.widget.FrameLayout.LayoutParams(-1,-1));
				if(parent==favoritesBox){
					android.widget.ImageView favoriteBadge=new android.widget.ImageView(MyContentActivity.this);
					favoriteBadge.setImageResource(R.drawable.fav_fill);favoriteBadge.setColorFilter(0xFFFFFFFF);
					favoriteBadge.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
					favoriteBadge.setPadding(cgGlass.px(5),cgGlass.px(5),cgGlass.px(5),cgGlass.px(5));cgGlass.bind(favoriteBadge,16,true);
					android.widget.FrameLayout.LayoutParams badgeLp=new android.widget.FrameLayout.LayoutParams(cgGlass.px(28),cgGlass.px(28));
					badgeLp.gravity=android.view.Gravity.TOP|android.view.Gravity.RIGHT;badgeLp.setMargins(0,cgGlass.px(5),cgGlass.px(5),0);
					favoritePosterFrame.addView(favoriteBadge,badgeLp);
				}
				card.addView(favoritePosterFrame,new android.widget.LinearLayout.LayoutParams(cgGlass.px(112),cgGlass.px(168)));
				
				
				
				final android.widget.TextView titleView =
				new android.widget.TextView(
				MyContentActivity.this
				);
				
				String displayTitle =
				title;
				
				if (
				displayTitle == null
				||
				displayTitle.trim().equals("")
				) {
					displayTitle =
					seriesTitle;
				}
				
				if (
				displayTitle == null
				||
				displayTitle.trim().equals("")
				) {
					displayTitle =
					"بدون عنوان";
				}
				
				titleView.setText(
				displayTitle
				);
				
				titleView.setTextColor(
				android.graphics.Color.WHITE
				);
				
				titleView.setTextSize(
				14
				);
				
				titleView.setSingleLine(
				true
				);
				
				titleView.setEllipsize(
				android.text.TextUtils.TruncateAt.END
				);
				
				titleView.setGravity(
				android.view.Gravity.RIGHT
				|
				android.view.Gravity.CENTER_VERTICAL
				);
				
				titleView.setTextDirection(
				android.view.View.TEXT_DIRECTION_RTL
				);
				
				try {
					
					android.graphics.Typeface sima =
					CigramTypefaces.get(
					getAssets(),
					CigramTypefaces.APP_FONT
					);
					
					titleView.setTypeface(
					sima,
					android.graphics.Typeface.BOLD
					);
					
				} catch (Exception ignored) {
				}
				
				
				android.widget.LinearLayout.LayoutParams titleLp =
				new android.widget.LinearLayout.LayoutParams(
				(int)(112f * dp),
				(int)(34f * dp)
				);
				
				titleLp.topMargin =
				(int)(5f * dp);
				
				card.addView(
				titleView,
				titleLp
				);
				
				
				final android.widget.TextView typeView =
				new android.widget.TextView(
				MyContentActivity.this
				);
				
				String typeText =
				"series".equalsIgnoreCase(
				type
				)
				? "مسلسل"
				: "فيلم";
				
				if (
				"series".equalsIgnoreCase(type)
				&&
				(
				!season.equals("")
				||
				!episode.equals("")
				)
				) {
					
					typeText += "  ";
					
					if (!season.equals("")) {
						typeText +=
						"م"
						+
						season;
					}
					
					if (!episode.equals("")) {
						
						if (!season.equals("")) {
							typeText += " • ";
						}
						
						typeText +=
						"ح"
						+
						episode;
					}
				}
				
				typeView.setText(
				typeText
				);
				
				typeView.setTextColor(
				0xFF9FB0B8
				);
				
				typeView.setTextSize(
				12
				);
				
				typeView.setGravity(
				android.view.Gravity.RIGHT
				|
				android.view.Gravity.CENTER_VERTICAL
				);
				
				typeView.setTextDirection(
				android.view.View.TEXT_DIRECTION_RTL
				);
				
				card.addView(
				typeView,
				new android.widget.LinearLayout.LayoutParams(
				(int)(112f * dp),
				(int)(24f * dp)
				)
				);
				
				
				android.widget.LinearLayout.LayoutParams cardLp =
				new android.widget.LinearLayout.LayoutParams(
				(int)(126f * dp),
				(int)(214f * dp)
				);
				
				cardLp.setMargins(
				(int)(4f * dp),
				0,
				(int)(4f * dp),
				0
				);
				
				parent.addView(
				card,
				0,
				cardLp
				);
				
				
				loadImage(
				posterView,
				poster
				);
				
				card.setOnTouchListener(
				new android.view.View.OnTouchListener() {
					@Override public boolean onTouch(
					android.view.View view,
					android.view.MotionEvent event
					) {
						int action = event.getActionMasked();
						if (action == android.view.MotionEvent.ACTION_DOWN) {
							CigramStage1InitProvider.preloadDetailsImages(
							MyContentActivity.this,
							poster,
							backdrop
							);
							view.animate().scaleX(1f).scaleY(1f).setDuration(80L).start();
						} else if (
						action == android.view.MotionEvent.ACTION_UP
						|| action == android.view.MotionEvent.ACTION_CANCEL
						) {
							view.animate().scaleX(1f).scaleY(1f).setDuration(120L).start();
						}
						return false;
					}
				}
				);
				
				card.setOnClickListener(
				new android.view.View.OnClickListener() {
					
					@Override
					public void onClick(
					android.view.View view
					) {
						CigramStage1InitProvider.openTitleDetails(
						MyContentActivity.this, item, posterView
						);
					}
				}
				);
			}
			
		}
		
		
		final MyContentUi myContentUi =
		new MyContentUi();
		
		
		// ========================================
		// CONTINUE WATCHING
		// ========================================
		
		// CIGRAM v2.5: stable per-account store + permanent history (survives re-login / new device)
		final org.json.JSONArray cgContinueAll =
		CigramUserData.continueItems(
		getApplicationContext()
		);
		
		boolean hasContinueItems =
		false;
		
		
		if (continueBox != null) {
			
			continueBox.removeAllViews();
			
			try {
				
				org.json.JSONArray items =
				cgContinueAll;
				
				for (
				int i = 0;
				i < items.length();
				i++
				) {
					
					org.json.JSONObject item =
					items.optJSONObject(
					i
					);
					
					if (item == null) {
						continue;
					}
					
					long position =
					item.optLong(
					"position",
					0L
					);
					
					long duration =
					item.optLong(
					"duration",
					0L
					);
					
					if (
					position < 5000L
					) {
						continue;
					}
					
					if (
					duration > 0L
					&&
					duration - position <= 15000L
					) {
						continue;
					}
					
					myContentUi.addContinueCard(
					continueBox,
					item
					);
					
					hasContinueItems =
					true;
				}
				
			} catch (Exception ignored) {
			}
		}
		
		
		if (
		!hasContinueItems
		) {
			
			myContentUi.showEmpty(
			continueBox,
			"لا توجد مشاهدات غير مكتملة"
			);
		}
		
		
		if (
		continueScroll != null
		&&
		continueBox != null
		) {
			
			continueScroll.post(
			new java.lang.Runnable() {
				
				@Override
				public void run() {
					
					continueBox.setMinimumWidth(
					continueScroll.getWidth()
					);
					
					continueBox.setGravity(
					android.view.Gravity.RIGHT
					|
					android.view.Gravity.CENTER_VERTICAL
					);
					
					continueScroll.fullScroll(
					android.view.View.FOCUS_RIGHT
					);
				}
			}
			);
		}
		
		
		// ========================================
		// FAVORITES
		// ========================================
		
		final android.content.SharedPreferences favoritePrefs =
		getSharedPreferences(
		"favorites",
		MODE_PRIVATE
		);
		
		final String favoriteEmail =
		auth.getString(
		"email",
		""
		);
		
		final String favoriteAccountIdentity =
		(
		favoriteEmail != null
		&&
		!favoriteEmail.trim().equals("")
		)
		? favoriteEmail.trim().toLowerCase(
		java.util.Locale.US
		)
		: token.trim();
		
		final String favoriteItemsKey =
		"items_"
		+
		Integer.toHexString(
		favoriteAccountIdentity.hashCode()
		);
		
		boolean hasFavoriteItems =
		false;
		
		
		if (favoritesBox != null) {
			
			favoritesBox.removeAllViews();
			
			try {
				
				String rawFavorites =
				favoritePrefs.getString(
				favoriteItemsKey,
				"[]"
				);
				
				org.json.JSONArray favoriteItems =
				new org.json.JSONArray(
				rawFavorites == null
				? "[]"
				: rawFavorites
				);
				
				for (
				int i = 0;
				i < favoriteItems.length();
				i++
				) {
					
					org.json.JSONObject item =
					favoriteItems.optJSONObject(
					i
					);
					
					if (item == null) {
						continue;
					}
					
					myContentUi.addFavoriteCard(
					favoritesBox,
					item
					);
					
					hasFavoriteItems =
					true;
				}
				
			} catch (Exception ignored) {
			}
		}
		
		
		if (
		!hasFavoriteItems
		) {
			
			myContentUi.showEmpty(
			favoritesBox,
			"لا توجد عناصر في المفضلة"
			);
		}
		
		
		// ========================================
		// MY LIST
		// ========================================
		
		final android.content.SharedPreferences myListPrefs =
		getSharedPreferences(
		"my_list",
		MODE_PRIVATE
		);
		
		boolean hasMyListItems = false;
		
		if (myListBox != null) {
			myListBox.removeAllViews();
			try {
				String rawMyList =
				myListPrefs.getString(
				favoriteItemsKey,
				"[]"
				);
				org.json.JSONArray myListItems =
				new org.json.JSONArray(
				rawMyList == null ? "[]" : rawMyList
				);
				for (int i = 0; i < myListItems.length(); i++) {
					org.json.JSONObject item = myListItems.optJSONObject(i);
					if (item == null) {
						continue;
					}
					myContentUi.addFavoriteCard(myListBox, item);
					hasMyListItems = true;
				}
			} catch (Exception ignored) {}
		}
		
		if (!hasMyListItems) {
			myContentUi.showEmpty(
			myListBox,
			"قائمتك فارغة"
			);
		}
		
		
		if (
		favoritesScroll != null
		&&
		favoritesBox != null
		) {
			
			favoritesScroll.post(
			new java.lang.Runnable() {
				
				@Override
				public void run() {
					
					favoritesBox.setMinimumWidth(
					favoritesScroll.getWidth()
					);
					
					favoritesBox.setGravity(
					android.view.Gravity.RIGHT
					|
					android.view.Gravity.CENTER_VERTICAL
					);
					
					favoritesScroll.fullScroll(
					android.view.View.FOCUS_RIGHT
					);
				}
			}
			);
		}
		
		
		if (
		myListScroll != null
		&&
		myListBox != null
		) {
			
			myListScroll.post(
			new java.lang.Runnable() {
				
				@Override
				public void run() {
					
					myListBox.setMinimumWidth(
					myListScroll.getWidth()
					);
					
					myListBox.setGravity(
					android.view.Gravity.RIGHT
					|
					android.view.Gravity.CENTER_VERTICAL
					);
					
					myListScroll.fullScroll(
					android.view.View.FOCUS_RIGHT
					);
				}
			}
			);
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
		
		binding.textviewMyContentTitle.setTypeface(CigramTypefaces.get(getAssets(),CigramTypefaces.APP_FONT), 0);
		binding.textviewContinueTitle.setTypeface(CigramTypefaces.get(getAssets(),CigramTypefaces.APP_FONT), 0);
		binding.textviewFavoritesTitle.setTypeface(CigramTypefaces.get(getAssets(),CigramTypefaces.APP_FONT), 0);
		binding.textviewAdsTitle.setTypeface(CigramTypefaces.get(getAssets(),CigramTypefaces.APP_FONT), 0);
		binding.textviewMyListTitle.setTypeface(CigramTypefaces.get(getAssets(),CigramTypefaces.APP_FONT), 0);
	}
	
}
