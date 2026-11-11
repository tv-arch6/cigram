package com.Cigram.vid;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.content.res.*;
import android.graphics.*;
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

public class AdsActivity extends AppCompatActivity {
	
	private AdsBinding binding;
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		binding = AdsBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		initialize(_savedInstanceState);
		
		CigramAdManager.initialize(getApplicationContext()); // self-throttled, background thread, consent-gated
		
		initializeLogic();
	}
	
	private void initialize(Bundle _savedInstanceState) {
	}
	
	private void initializeLogic() {
		{
			final float adsDensity =
			getResources()
			.getDisplayMetrics()
			.density;
			
			
			final android.view.View adsRoot =
			findViewById(
			R.id.linear_ads_root
			);
			
			
			final android.view.View adsToolbar =
			findViewById(
			R.id.linear_ads_toolbar
			);
			
			
			final android.widget.TextView adsTitle =
			(android.widget.TextView)
			findViewById(
			R.id.textview_ads_title
			);
			
			
			final android.widget.ImageView adsBack =
			(android.widget.ImageView)
			findViewById(
			R.id.img_ads_back
			);
			
			
			final android.widget.TextView adsHeading =
			(android.widget.TextView)
			findViewById(
			R.id.textview_ads_heading
			);
			
			
			final android.widget.TextView adsDescription =
			(android.widget.TextView)
			findViewById(
			R.id.textview_ads_description
			);
			
			
			final android.view.View adsCard =
			findViewById(
			R.id.linear_ads_card
			);
			
			
			final android.widget.TextView adsWatchTitle =
			(android.widget.TextView)
			findViewById(
			R.id.textview_ads_watch_title
			);
			
			
			final android.widget.TextView adsWatchInfo =
			(android.widget.TextView)
			findViewById(
			R.id.textview_ads_watch_info
			);
			
			
			final android.widget.TextView adsWatchButton =
			(android.widget.TextView)
			findViewById(
			R.id.button_ads_watch
			);
			
			
			if (adsRoot != null) {
				
				adsRoot.setBackgroundColor(
				android.graphics.Color.parseColor(
				"#00151D"
				)
				);
			}
			
			
			if (adsToolbar != null) {
				
				adsToolbar.setBackgroundColor(
				android.graphics.Color.parseColor(
				"#1A1A1D"
				)
				);
			}
			
			
			if (adsTitle != null) {
				
				adsTitle.setText(
				"المساعدة والدعم"
				);
				
				adsTitle.setTextColor(
				android.graphics.Color.WHITE
				);
				
				adsTitle.setTextSize(
				20
				);
			}
			
			
			if (adsHeading != null) {
				
				adsHeading.setText(
				"دعم تطبيق سيكرام"
				);
				
				adsHeading.setTextColor(
				android.graphics.Color.WHITE
				);
				
				adsHeading.setTextSize(
				22
				);
			}
			
			
			if (adsDescription != null) {
				
				adsDescription.setText(
				"يمكنك دعم استمرار التطبيق بمشاهدة إعلان باختيارك."
				);
				
				adsDescription.setTextColor(
				android.graphics.Color.parseColor(
				"#91A1A9"
				)
				);
				
				adsDescription.setTextSize(
				14
				);
			}
			
			
			if (adsWatchTitle != null) {
				
				adsWatchTitle.setText(
				"شاهد إعلان لدعمنا"
				);
				
				adsWatchTitle.setTextColor(
				android.graphics.Color.WHITE
				);
				
				adsWatchTitle.setTextSize(
				18
				);
			}
			
			
			if (adsWatchInfo != null) {
				
				adsWatchInfo.setText(
				"مشاهدة الإعلان اختيارية وتساعدنا على استمرار تطوير التطبيق."
				);
				
				adsWatchInfo.setTextColor(
				android.graphics.Color.parseColor(
				"#91A1A9"
				)
				);
				
				adsWatchInfo.setTextSize(
				13
				);
			}
			
			
			if (adsCard != null) {
				
				android.graphics.drawable.GradientDrawable adsCardBackground =
				new android.graphics.drawable.GradientDrawable();
				
				adsCardBackground.setShape(
				android.graphics.drawable.GradientDrawable.RECTANGLE
				);
				
				adsCardBackground.setColor(
				android.graphics.Color.parseColor(
				"#1C1C20"
				)
				);
				
				adsCardBackground.setCornerRadius(
				18 * adsDensity
				);
				
				adsCard.setBackground(
				adsCardBackground
				);
			}
			
			
			if (adsWatchButton != null) {
				
				adsWatchButton.setText(
				"مشاهدة إعلان"
				);
				
				adsWatchButton.setTextColor(
				android.graphics.Color.BLACK
				);
				
				adsWatchButton.setTextSize(
				16
				);
				
				adsWatchButton.setGravity(
				android.view.Gravity.CENTER
				);
				
				
				android.graphics.drawable.GradientDrawable watchButtonBackground =
				new android.graphics.drawable.GradientDrawable();
				
				watchButtonBackground.setShape(
				android.graphics.drawable.GradientDrawable.RECTANGLE
				);
				
				watchButtonBackground.setColor(
				android.graphics.Color.WHITE
				);
				
				watchButtonBackground.setCornerRadius(
				14 * adsDensity
				);
				
				adsWatchButton.setBackground(
				watchButtonBackground
				);
				
				
				adsWatchButton.setClickable(
				true
				);
				
				adsWatchButton.setFocusable(
				true
				);
				
				adsWatchButton.setElevation(
				3 * adsDensity
				);
				
				
				adsWatchButton.setOnTouchListener(
				new android.view.View.OnTouchListener() {
					
					@Override
					public boolean onTouch(
					android.view.View view,
					android.view.MotionEvent event
					) {
						
						if (
						event.getAction()
						==
						android.view.MotionEvent.ACTION_DOWN
						) {
							
							view.animate()
							.scaleX(1f)
							.scaleY(1f)
							.setDuration(80L)
							.start();
							
						} else if (
						event.getAction()
						==
						android.view.MotionEvent.ACTION_UP
						||
						event.getAction()
						==
						android.view.MotionEvent.ACTION_CANCEL
						) {
							
							view.animate()
							.scaleX(1f)
							.scaleY(1f)
							.setDuration(100L)
							.start();
						}
						
						
						return false;
					}
				}
				);
			}
			
			
			try {
				
				final android.graphics.Typeface adsFont =
				CigramTypefaces.get(
				getAssets(),
				CigramTypefaces.APP_FONT
				);
				
				
				java.util.ArrayList<android.view.View> adsFontViews =
				new java.util.ArrayList<android.view.View>();
				
				
				android.view.View decorView =
				getWindow()
				.getDecorView();
				
				
				if (decorView != null) {
					
					adsFontViews.add(
					decorView
					);
				}
				
				
				for (
				int adsFontIndex = 0;
				adsFontIndex < adsFontViews.size();
				adsFontIndex++
				) {
					
					android.view.View adsCurrentView =
					adsFontViews.get(
					adsFontIndex
					);
					
					
					if (
					adsCurrentView
					instanceof
					android.widget.TextView
					) {
						
						((android.widget.TextView)
						adsCurrentView
						).setTypeface(
						adsFont
						);
					}
					
					
					if (
					adsCurrentView
					instanceof
					android.view.ViewGroup
					) {
						
						android.view.ViewGroup adsGroup =
						(android.view.ViewGroup)
						adsCurrentView;
						
						
						for (
						int adsChildIndex = 0;
						adsChildIndex <
						adsGroup.getChildCount();
						adsChildIndex++
						) {
							
							android.view.View child =
							adsGroup.getChildAt(
							adsChildIndex
							);
							
							
							if (child != null) {
								
								adsFontViews.add(
								child
								);
							}
						}
					}
				}
				
			} catch (
			Exception ignored
			) {
			}
			
			
			if (adsBack != null) {
				
				adsBack.setOnClickListener(
				new android.view.View.OnClickListener() {
					
					@Override
					public void onClick(
					android.view.View view
					) {
						
						CigramNav.back(AdsActivity.this);
					}
				}
				);
			}
			
			
			java.util.ArrayList<String> missingAdsViews =
			new java.util.ArrayList<String>();
			
			
			if (adsRoot == null) {
				missingAdsViews.add(
				"linear_ads_root"
				);
			}
			
			if (adsToolbar == null) {
				missingAdsViews.add(
				"linear_ads_toolbar"
				);
			}
			
			if (adsTitle == null) {
				missingAdsViews.add(
				"textview_ads_title"
				);
			}
			
			if (adsBack == null) {
				missingAdsViews.add(
				"img_ads_back"
				);
			}
			
			if (adsHeading == null) {
				missingAdsViews.add(
				"textview_ads_heading"
				);
			}
			
			if (adsDescription == null) {
				missingAdsViews.add(
				"textview_ads_description"
				);
			}
			
			if (adsCard == null) {
				missingAdsViews.add(
				"linear_ads_card"
				);
			}
			
			if (adsWatchTitle == null) {
				missingAdsViews.add(
				"textview_ads_watch_title"
				);
			}
			
			if (adsWatchInfo == null) {
				missingAdsViews.add(
				"textview_ads_watch_info"
				);
			}
			
			if (adsWatchButton == null) {
				missingAdsViews.add(
				"button_ads_watch"
				);
			}
			
			
			if (!missingAdsViews.isEmpty()) {
				
				android.widget.Toast.makeText(
				AdsActivity.this,
				"عناصر ناقصة في ads.xml: "
				+
				android.text.TextUtils.join(
				", ",
				missingAdsViews
				),
				android.widget.Toast.LENGTH_LONG
				).show();
			}
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
		
	}
	
}
