package com.Cigram.vid;

import android.Manifest;
import android.animation.*;
import android.app.*;
import android.content.*;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import androidx.asynclayoutinflater.*;
import androidx.coordinatorlayout.*;
import androidx.core.*;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
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
import androidx.legacy.coreui.*;
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
import androidx.slidingpanelayout.*;
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

public class DetailsActivity extends AppCompatActivity {
	
	private DetailsBinding binding;
	
	private Intent intent_player = new Intent();
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		binding = DetailsBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		initialize(_savedInstanceState);
		
		CigramAdManager.initialize(getApplicationContext()); // self-throttled, background thread, consent-gated
		
		
		// Play policy: no storage permission prompt (nothing here reads shared storage).
		initializeLogic();
	}
	
	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == 1000) {
			initializeLogic();
		}
	}
	
	private void initialize(Bundle _savedInstanceState) {
		
		binding.buttonWatchNow.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				
			}
		});
		
		binding.buttonSubscribe.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				
			}
		});
	}
	
	private void initializeLogic() {
		binding.buttonWatchNow.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				intent_player.setClass(getApplicationContext(), PlayerActivity.class);
				intent_player.putExtra("video", getIntent().getStringExtra("video"));
				FileUtil.writeFile(FileUtil.getPackageDataDir(getApplicationContext()).concat("/video_url.txt"), getIntent().getStringExtra("video"));
				intent_player.putExtra("title", binding.textviewDetailsTitle.getText().toString());
				intent_player.putExtra("subtitle", getIntent().getStringExtra("subtitle"));
				startActivity(intent_player);
			}
		});
		binding.buttonWatchNow.setBackground(new GradientDrawable() { public GradientDrawable getIns(int a, int b) { this.setCornerRadius(a); this.setColor(b); return this; } }.getIns((int)50, 0xFFE21B14));
		binding.buttonSubscribe.setBackground(new GradientDrawable() { public GradientDrawable getIns(int a, int b) { this.setCornerRadius(a); this.setColor(b); return this; } }.getIns((int)50, 0xFFE21B14));
		android.util.Log.w("CigramDetails", "Title Details Screen first-frame bind");
		try {
			CigramStage1InitProvider.bindTitleDetailsNow(DetailsActivity.this);
		} catch (Throwable error) {
			CigramStage1InitProvider.detailsBlockFailed(DetailsActivity.this, "9021", error);
		}
		
		// v10.62: زر «مشاركة» (رابط / بطاقة صورة)
		try { CigramShare.installDetails(DetailsActivity.this); } catch (Throwable cgShareError) { }
		
	}
	
	@Override
	public void onResume() {
		super.onResume();
		android.util.Log.w("CigramDetails", "onResume refresh check begin");
		try {
			final android.content.SharedPreferences cgUiState =
			getSharedPreferences("cigram_ui_state", MODE_PRIVATE);
			
			boolean needsRefresh =
			cgUiState.getBoolean("details_needs_refresh", false);
			
			if (needsRefresh) {
				cgUiState.edit()
				.putBoolean("details_needs_refresh", false)
				.apply();
				
				android.view.View root = findViewById(R.id.linear_movie_data);
				Object refreshAction =
				root == null ? null : root.getTag(R.id.linear_movie_data);
				
				if (refreshAction instanceof java.lang.Runnable) {
					((java.lang.Runnable) refreshAction).run();
				} else {
					android.util.Log.w(
					"CigramDetails",
					"No episode refresh action registered; recreate skipped"
					);
				}
			}
		} catch (Throwable error) {
			android.util.Log.w("CigramDetails", "onResume refresh failed", error);
		}
		
		// v10.62: زر «مشاركة» (رابط / بطاقة صورة)
		try { CigramShare.installDetails(DetailsActivity.this); } catch (Throwable cgShareError) { }
		
	}
}