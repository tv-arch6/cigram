package com.Cigram.vid;

import android.animation.*;
import android.app.*;
import android.app.Activity;
import android.content.*;
import android.content.SharedPreferences;
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
import java.util.HashMap;
import java.util.regex.*;
import org.json.*;

public class AccountActivity extends AppCompatActivity {
	
	private AccountBinding binding;
	private HashMap<String, Object> register_params = new HashMap<>();
	private HashMap<String, Object> login_params = new HashMap<>();
	
	private RequestNetwork req_register;
	private RequestNetwork.RequestListener _req_register_request_listener;
	private RequestNetwork req_login;
	private RequestNetwork.RequestListener _req_login_request_listener;
	private SharedPreferences sp_auth;
	private RequestNetwork req_avatar;
	private RequestNetwork.RequestListener _req_avatar_request_listener;
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		binding = AccountBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		initialize(_savedInstanceState);
		
		CigramAdManager.initialize(getApplicationContext()); // self-throttled, background thread, consent-gated
		
		initializeLogic();
	}
	
	private void initialize(Bundle _savedInstanceState) {
		req_register = new RequestNetwork(this);
		req_login = new RequestNetwork(this);
		sp_auth = getSharedPreferences("auth_session", Activity.MODE_PRIVATE);
		req_avatar = new RequestNetwork(this);
		
		binding.buttonLogin.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				String loginEmail = ((android.widget.EditText)
				findViewById(R.id.edittext_login_email))
				.getText().toString().trim();
				
				String loginPassword = ((android.widget.EditText)
				findViewById(R.id.edittext_login_password))
				.getText().toString();
				
				if (loginEmail.isEmpty() || loginPassword.isEmpty()) {
					SketchwareUtil.showMessage(
					getApplicationContext(),
					"أدخل البريد الإلكتروني وكلمة المرور"
					);
					return;
				}
				
				if (!android.util.Patterns.EMAIL_ADDRESS
				.matcher(loginEmail).matches()) {
					SketchwareUtil.showMessage(
					getApplicationContext(),
					"البريد الإلكتروني غير صحيح"
					);
					return;
				}
				
				// CIGRAM: تُحفظ مشفّرة بعد نجاح الدخول فقط لتجديد الجلسة تلقائياً.
				CigramSession.pending(loginEmail, loginPassword);
				
				login_params.clear();
				login_params.put("email", binding.edittextLoginEmail.getText().toString());
				login_params.put("password", binding.edittextLoginPassword.getText().toString());
				req_login.setParams(login_params, RequestNetworkController.REQUEST_PARAM);
				req_login.startRequestNetwork(RequestNetworkController.POST, "https://cigram-auth-api.wwq-mixtv.workers.dev/login", "login", _req_login_request_listener);
			}
		});
		
		binding.buttonRegister.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				String regName = ((android.widget.EditText)
				findViewById(R.id.edittext_register_name))
				.getText().toString().trim();
				
				String regEmail = ((android.widget.EditText)
				findViewById(R.id.edittext_register_email))
				.getText().toString().trim();
				
				String regPassword = ((android.widget.EditText)
				findViewById(R.id.edittext_register_password))
				.getText().toString();
				
				String regConfirm = ((android.widget.EditText)
				findViewById(R.id.edittext_register_confirm_password))
				.getText().toString();
				
				if (regName.isEmpty() || regEmail.isEmpty() ||
				regPassword.isEmpty() || regConfirm.isEmpty()) {
					SketchwareUtil.showMessage(
					getApplicationContext(),
					"يرجى ملء جميع الحقول"
					);
					return;
				}
				
				if (!android.util.Patterns.EMAIL_ADDRESS
				.matcher(regEmail).matches()) {
					SketchwareUtil.showMessage(
					getApplicationContext(),
					"البريد الإلكتروني غير صحيح"
					);
					return;
				}
				
				if (regPassword.length() < 8) {
					SketchwareUtil.showMessage(
					getApplicationContext(),
					"كلمة المرور يجب ألا تقل عن 8 أحرف"
					);
					return;
				}
				
				if (!regPassword.equals(regConfirm)) {
					SketchwareUtil.showMessage(
					getApplicationContext(),
					"كلمتا المرور غير متطابقتين"
					);
					return;
				}
				
				// 2026: no account without an explicit tick on «أوافق على الشروط والأحكام» (never pre-selected)
				if (!CigramTerms.checkBeforeRegister(AccountActivity.this)) return;
				CigramTerms.recordAcceptance(getApplicationContext(), regEmail);
				
				// CIGRAM: تُحفظ مشفّرة بعد نجاح إنشاء الحساب فقط لتجديد الجلسة تلقائياً.
				CigramSession.pending(regEmail, regPassword);
				
				register_params.clear();
				register_params.put("name", binding.edittextRegisterName.getText().toString());
				register_params.put("email", binding.edittextRegisterEmail.getText().toString());
				register_params.put("password", binding.edittextRegisterPassword.getText().toString());
				register_params.put("gender", "unspecified");
				// accepted terms version + time, so the auth worker can keep its own record of the consent
				register_params.put("accept_terms", "true");   // 19.2: without this field the auth worker never stored the consent
				register_params.put("terms_version", String.valueOf(CigramTerms.VERSION));
				register_params.put("terms_accepted_at", String.valueOf(System.currentTimeMillis()));
				req_register.setParams(register_params, RequestNetworkController.REQUEST_PARAM);
				req_register.startRequestNetwork(RequestNetworkController.POST, "https://cigram-auth-api.wwq-mixtv.workers.dev/register", "register", _req_register_request_listener);
			}
		});
		
		binding.linearAccountSubtitleFont.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				try {
					CigramFonts.openPage(AccountActivity.this);
				} catch (Throwable error) {
					android.util.Log.w("CigramFonts", "open page failed", error);
				}
				
			}
		});
		
		binding.buttonOpenLogin.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				// إخفاء واجهة الضيف
				findViewById(R.id.linear_guest_account)
				.setVisibility(android.view.View.GONE);
				
				// إظهار تبويبات تسجيل الدخول / إنشاء الحساب
				findViewById(R.id.linear_auth_tabs)
				.setVisibility(android.view.View.VISIBLE);
				
				// إظهار فورم تسجيل الدخول
				findViewById(R.id.linear_login_form)
				.setVisibility(android.view.View.VISIBLE);
				
				findViewById(R.id.edittext_login_email)
				.setVisibility(android.view.View.VISIBLE);
				
				findViewById(R.id.edittext_login_password)
				.setVisibility(android.view.View.VISIBLE);
				
				findViewById(R.id.textview_forgot_password)
				.setVisibility(android.view.View.VISIBLE);
				
				findViewById(R.id.button_login)
				.setVisibility(android.view.View.VISIBLE);
				
				// إخفاء فورم إنشاء الحساب بالبداية
				findViewById(R.id.linear_register_form)
				.setVisibility(android.view.View.GONE);
				
				findViewById(R.id.edittext_register_name)
				.setVisibility(android.view.View.GONE);
				
				findViewById(R.id.edittext_register_email)
				.setVisibility(android.view.View.GONE);
				
				findViewById(R.id.edittext_register_password)
				.setVisibility(android.view.View.GONE);
				
				findViewById(R.id.edittext_register_confirm_password)
				.setVisibility(android.view.View.GONE);
				
				findViewById(R.id.button_register)
				.setVisibility(android.view.View.GONE);
				
				// إعادة الصفحة للأعلى
				findViewById(R.id.vscroll_account).post(new Runnable() {
					@Override
					public void run() {
						((android.widget.ScrollView)
						findViewById(R.id.vscroll_account))
						.smoothScrollTo(0, 0);
					}
				});
			}
		});
		
		binding.textviewRegisterTab.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				
			}
		});
		
		binding.textviewLoginTab.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				
			}
		});
		
		binding.linearAccountSettings.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				android.content.Intent settingsIntent =
				new android.content.Intent(
				AccountActivity.this,
				SettingsActivity.class
				);
				
				startActivity(settingsIntent);
			}
		});
		
		binding.buttonGenderFemale.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				
			}
		});
		
		binding.buttonGenderMale.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				
			}
		});
		
		binding.textview1.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				android.content.Intent settingsIntent =
				new android.content.Intent(
				AccountActivity.this,
				SettingsActivity.class
				);
				
				startActivity(settingsIntent);
			}
		});
		
		binding.ads.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				android.content.Intent adsIntent =
				new android.content.Intent(
				AccountActivity.this,
				AdsActivity.class
				);
				
				startActivity(adsIntent);
			}
		});
		
		_req_register_request_listener = new RequestNetwork.RequestListener() {
			@Override
			public void onResponse(String _param1, String _param2, HashMap<String, Object> _param3) {
				final String _tag = _param1;
				final String _response = _param2;
				final HashMap<String, Object> _responseHeaders = _param3;
				try {
					org.json.JSONObject root =
					new org.json.JSONObject(_response);
					
					if (!root.optBoolean("success", false)) {
						SketchwareUtil.showMessage(
						getApplicationContext(),
						root.optString("message", "تعذر إنشاء الحساب")
						);
						return;
					}
					
					String token = root.optString("token", "").trim();
					String expiresAt = root.optString("expires_at", "");
					org.json.JSONObject user = root.optJSONObject("user");
					CigramTerms.learn(getApplicationContext(), user, token);   // 19.2: the account's consent is remembered for good
					
					if (token.isEmpty() || user == null) {
						SketchwareUtil.showMessage(
						getApplicationContext(),
						"استجابة إنشاء الحساب ناقصة"
						);
						return;
					}
					
					android.content.SharedPreferences auth =
					getSharedPreferences(
					"auth_session",
					android.app.Activity.MODE_PRIVATE
					);
					
					auth.edit()
					.putBoolean("logged_in", true)
					.putString("token", token)
					.putString("expires_at", expiresAt)
					.putString("user_id", user.optString("id", ""))
					.putString("public_id", user.optString("public_id", ""))
					.putString("name", user.optString("name", ""))
					.putString("email", user.optString("email", ""))
					.putString(
					"gender",
					user.optString("gender", "unspecified")
					)
					.putString("avatar_url", user.optString("avatar_url", ""))
					.remove("avatar_local_path")
					.apply();
					
					CigramSession.commit(getApplicationContext(), user.optString("email", ""));
					
					SketchwareUtil.showMessage(
					getApplicationContext(),
					root.optString("message", "تم إنشاء الحساب بنجاح")
					);
					
					recreate();
					
				} catch (Exception error) {
					SketchwareUtil.showMessage(
					getApplicationContext(),
					"خطأ في قراءة استجابة الخادم"
					);
				}
			}
			
			@Override
			public void onErrorResponse(String _param1, String _param2) {
				final String _tag = _param1;
				final String _message = _param2;
				SketchwareUtil.showMessage(
				getApplicationContext(),
				"تعذر الاتصال بالخادم، حاول مرة ثانية"
				);
			}
		};
		
		_req_login_request_listener = new RequestNetwork.RequestListener() {
			@Override
			public void onResponse(String _param1, String _param2, HashMap<String, Object> _param3) {
				final String _tag = _param1;
				final String _response = _param2;
				final HashMap<String, Object> _responseHeaders = _param3;
				try {
					org.json.JSONObject root =
					new org.json.JSONObject(_response);
					
					if (!root.optBoolean("success", false)) {
						SketchwareUtil.showMessage(
						getApplicationContext(),
						root.optString(
						"message",
						"البريد الإلكتروني أو كلمة المرور غير صحيحة"
						)
						);
						return;
					}
					
					String token = root.optString("token", "").trim();
					String expiresAt = root.optString("expires_at", "");
					org.json.JSONObject user = root.optJSONObject("user");
					CigramTerms.learn(getApplicationContext(), user, token);   // 19.2: the account's consent is remembered for good
					
					if (token.isEmpty() || user == null) {
						SketchwareUtil.showMessage(
						getApplicationContext(),
						"استجابة تسجيل الدخول ناقصة"
						);
						return;
					}
					
					String userId = user.optString("id", "");
					String userPublicId = user.optString("public_id", "");
					String userName = user.optString("name", "");
					String userEmail = user.optString("email", "");
					String userGender = user.optString(
					"gender",
					"unspecified"
					);
					String avatarUrl = user.optString("avatar_url", "");
					
					android.content.SharedPreferences auth =
					getSharedPreferences(
					"auth_session",
					android.app.Activity.MODE_PRIVATE
					);
					
					auth.edit()
					.putBoolean("logged_in", true)
					.putString("token", token)
					.putString("expires_at", expiresAt)
					.putString("user_id", userId)
					.putString("public_id", userPublicId)
					.putString("name", userName)
					.putString("email", userEmail)
					.putString("gender", userGender)
					.putString("avatar_url", avatarUrl)
					.remove("avatar_local_path")
					.apply();
					
					CigramSession.commit(getApplicationContext(), userEmail);
					
					SketchwareUtil.showMessage(
					getApplicationContext(),
					root.optString(
					"message",
					"تم تسجيل الدخول بنجاح"
					)
					);
					
					recreate();
					
				} catch (Exception error) {
					SketchwareUtil.showMessage(
					getApplicationContext(),
					"تعذر قراءة استجابة الخادم"
					);
				}
			}
			
			@Override
			public void onErrorResponse(String _param1, String _param2) {
				final String _tag = _param1;
				final String _message = _param2;
				SketchwareUtil.showMessage(
				getApplicationContext(),
				"تعذر الاتصال بالخادم، حاول مرة ثانية"
				);
			}
		};
		
		_req_avatar_request_listener = new RequestNetwork.RequestListener() {
			@Override
			public void onResponse(String _param1, String _param2, HashMap<String, Object> _param3) {
				final String _tag = _param1;
				final String _response = _param2;
				final HashMap<String, Object> _responseHeaders = _param3;
				
			}
			
			@Override
			public void onErrorResponse(String _param1, String _param2) {
				final String _tag = _param1;
				final String _message = _param2;
				
			}
		};
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
		// This source can be emitted inside Sketchware network listeners, where `this`
		// is the listener itself rather than the Activity.  The application context is
		// available from both scopes.
		final CigramGlass cgGlass = new CigramGlass(getApplicationContext());
		// v5.1: the old bottom-bar copy that used to live here was removed. It ran in addition to the
		// v2 bar built at the end of onCreate, so Account showed two bars (two click handlers, a
		// reserved 82dp strip and a glass snapshot that captured the old bar).
		
		final android.widget.TextView loginTab =
		findViewById(R.id.textview_login_tab);
		
		final android.widget.TextView registerTab =
		findViewById(R.id.textview_register_tab);
		
		final android.widget.EditText loginEmail =
		findViewById(R.id.edittext_login_email);
		
		final android.widget.EditText loginPassword =
		findViewById(R.id.edittext_login_password);
		
		final android.widget.TextView forgotPassword =
		findViewById(R.id.textview_forgot_password);
		
		final android.widget.Button loginButton =
		findViewById(R.id.button_login);
		
		final android.widget.EditText registerName =
		findViewById(R.id.edittext_register_name);
		
		final android.widget.EditText registerEmail =
		findViewById(R.id.edittext_register_email);
		
		final android.widget.EditText registerPassword =
		findViewById(R.id.edittext_register_password);
		
		final android.widget.EditText registerConfirm =
		findViewById(R.id.edittext_register_confirm_password);
		
		final android.widget.Button registerButton =
		findViewById(R.id.button_register);
		
		final android.widget.LinearLayout loginForm =
		findViewById(R.id.linear_login_form);
		
		final android.widget.LinearLayout registerForm =
		findViewById(R.id.linear_register_form);
		
		
		// ========================================
		// البداية: تسجيل الدخول
		// ========================================
		
		loginForm.setVisibility(android.view.View.VISIBLE);
		registerForm.setVisibility(android.view.View.GONE);
		
		loginEmail.setVisibility(android.view.View.VISIBLE);
		loginPassword.setVisibility(android.view.View.VISIBLE);
		forgotPassword.setVisibility(android.view.View.VISIBLE);
		loginButton.setVisibility(android.view.View.VISIBLE);
		
		registerName.setVisibility(android.view.View.GONE);
		registerEmail.setVisibility(android.view.View.GONE);
		registerPassword.setVisibility(android.view.View.GONE);
		registerConfirm.setVisibility(android.view.View.GONE);
		registerButton.setVisibility(android.view.View.GONE);
		
		
		// ========================================
		// شكل تبويب تسجيل الدخول المحدد
		// أبيض + خط أسود + حواف دائرية
		// ========================================
		
		android.graphics.drawable.GradientDrawable loginStartBg =
		new android.graphics.drawable.GradientDrawable();
		
		loginStartBg.setShape(
		android.graphics.drawable.GradientDrawable.RECTANGLE
		);
		
		loginStartBg.setColor(
		android.graphics.Color.parseColor("#FFFFFF")
		);
		
		loginStartBg.setCornerRadius(35f);
		
		loginTab.setBackground(cgGlass.surface(25, true));
		loginTab.setTextColor(android.graphics.Color.WHITE);
		
		
		// التسجيل غير محدد
		registerTab.setBackgroundColor(
		android.graphics.Color.TRANSPARENT
		);
		
		registerTab.setTextColor(
		android.graphics.Color.parseColor("#8A8A8F")
		);
		
		
		// ========================================
		// إعداد أزرار تسجيل / إنشاء
		// ========================================
		
		loginButton.setTextColor(android.graphics.Color.WHITE);
		registerButton.setTextColor(android.graphics.Color.WHITE);
		
		loginButton.setTextSize(16);
		registerButton.setTextSize(16);
		
		loginButton.setAllCaps(false);
		registerButton.setAllCaps(false);
		
		loginButton.setBackgroundColor(
		android.graphics.Color.parseColor("#E91B1B")
		);
		
		registerButton.setBackgroundColor(
		android.graphics.Color.parseColor("#E91B1B")
		);
		
		
		// ========================================
		// الضغط على "تسجيل الدخول"
		// ========================================
		
		loginTab.setOnClickListener(
		new android.view.View.OnClickListener() {
			
			@Override
			public void onClick(android.view.View view) {
				
				// إظهار فورم تسجيل الدخول بالكامل
				loginForm.setVisibility(android.view.View.VISIBLE);
				
				loginEmail.setVisibility(android.view.View.VISIBLE);
				loginPassword.setVisibility(android.view.View.VISIBLE);
				forgotPassword.setVisibility(android.view.View.VISIBLE);
				loginButton.setVisibility(android.view.View.VISIBLE);
				
				
				// إخفاء فورم إنشاء الحساب بالكامل
				registerForm.setVisibility(android.view.View.GONE);
				
				registerName.setVisibility(android.view.View.GONE);
				registerEmail.setVisibility(android.view.View.GONE);
				registerPassword.setVisibility(android.view.View.GONE);
				registerConfirm.setVisibility(android.view.View.GONE);
				registerButton.setVisibility(android.view.View.GONE);
				
				
				// تسجيل الدخول = أبيض + أسود
				android.graphics.drawable.GradientDrawable bg =
				new android.graphics.drawable.GradientDrawable();
				
				bg.setShape(
				android.graphics.drawable.GradientDrawable.RECTANGLE
				);
				
				bg.setColor(
				android.graphics.Color.parseColor("#FFFFFF")
				);
				
				bg.setCornerRadius(35f);
				
				loginTab.setBackground(cgGlass.surface(25, true));
				loginTab.setTextColor(android.graphics.Color.WHITE);
				
				
				// التسجيل = غير محدد
				registerTab.setBackgroundColor(
				android.graphics.Color.TRANSPARENT
				);
				
				registerTab.setTextColor(
				android.graphics.Color.parseColor("#8A8A8F")
				);
			}
		});
		
		
		// ========================================
		// الضغط على "تسجيل (ليس لديك حساب)"
		// ========================================
		
		registerTab.setOnClickListener(
		new android.view.View.OnClickListener() {
			
			@Override
			public void onClick(android.view.View view) {
				
				// إخفاء فورم تسجيل الدخول بالكامل
				loginForm.setVisibility(android.view.View.GONE);
				
				loginEmail.setVisibility(android.view.View.GONE);
				loginPassword.setVisibility(android.view.View.GONE);
				forgotPassword.setVisibility(android.view.View.GONE);
				loginButton.setVisibility(android.view.View.GONE);
				
				
				// إظهار فورم إنشاء الحساب بالكامل
				registerForm.setVisibility(android.view.View.VISIBLE);
				
				registerName.setVisibility(android.view.View.VISIBLE);
				registerEmail.setVisibility(android.view.View.VISIBLE);
				registerPassword.setVisibility(android.view.View.VISIBLE);
				registerConfirm.setVisibility(android.view.View.VISIBLE);
				registerButton.setVisibility(android.view.View.VISIBLE);
				
				
				// التسجيل = أبيض + أسود
				android.graphics.drawable.GradientDrawable bg =
				new android.graphics.drawable.GradientDrawable();
				
				bg.setShape(
				android.graphics.drawable.GradientDrawable.RECTANGLE
				);
				
				bg.setColor(
				android.graphics.Color.parseColor("#FFFFFF")
				);
				
				bg.setCornerRadius(35f);
				
				registerTab.setBackground(cgGlass.surface(25, true));
				registerTab.setTextColor(android.graphics.Color.WHITE);
				
				
				// تسجيل الدخول = غير محدد
				loginTab.setBackgroundColor(
				android.graphics.Color.TRANSPARENT
				);
				
				loginTab.setTextColor(
				android.graphics.Color.parseColor("#8A8A8F")
				);
			}
		});
		// إخفاء شريط التمرير الجانبي
		android.view.View accountScroll =
		findViewById(R.id.vscroll_account);
		
		accountScroll.setVerticalScrollBarEnabled(false);
		accountScroll.setHorizontalScrollBarEnabled(false);
		accountScroll.setScrollbarFadingEnabled(true);
		
		// تطبيق خط sima.ttf على جميع النصوص والأزرار والحقول
		android.graphics.Typeface simaFont =
		CigramTypefaces.get(
		getAssets(),
		CigramTypefaces.APP_FONT
		);
		
		android.view.ViewGroup accountRoot =
		findViewById(R.id.linear_account_content);
		
		java.util.ArrayList<android.view.View> viewsQueue =
		new java.util.ArrayList<>();
		
		viewsQueue.add(accountRoot);
		
		while (!viewsQueue.isEmpty()) {
			android.view.View currentView = viewsQueue.remove(0);
			
			if (currentView instanceof android.widget.TextView) {
				((android.widget.TextView) currentView)
				.setTypeface(simaFont);
			}
			
			if (currentView instanceof android.view.ViewGroup) {
				android.view.ViewGroup group =
				(android.view.ViewGroup) currentView;
				
				for (int i = 0; i < group.getChildCount(); i++) {
					viewsQueue.add(group.getChildAt(i));
				}
			}
		}
		
		// الحقول ذات الخلفية الداكنة
		int[] darkRoundedIds = new int[] {
			R.id.edittext_login_email,
			R.id.edittext_login_password,
			R.id.edittext_register_name,
			R.id.edittext_register_email,
			R.id.edittext_register_password,
			R.id.edittext_register_confirm_password
		};
		
		for (int viewId : darkRoundedIds) {
			android.view.View targetView = findViewById(viewId);
			
			android.graphics.drawable.GradientDrawable darkBackground =
			new android.graphics.drawable.GradientDrawable();
			
			darkBackground.setColor(
			android.graphics.Color.parseColor("#FF1D1D20")
			);
			
			darkBackground.setCornerRadius(50f);
			targetView.setBackground(darkBackground);
		}
		
		// زر تسجيل الدخول
		android.graphics.drawable.GradientDrawable loginBackground =
		new android.graphics.drawable.GradientDrawable();
		
		loginBackground.setColor(
		android.graphics.Color.parseColor("#FFE91B1B")
		);
		loginBackground.setCornerRadius(50f);
		
		findViewById(R.id.button_login)
		.setBackground(loginBackground);
		
		// زر إنشاء الحساب
		android.graphics.drawable.GradientDrawable registerBackground =
		new android.graphics.drawable.GradientDrawable();
		
		registerBackground.setColor(
		android.graphics.Color.parseColor("#FFE91B1B")
		);
		registerBackground.setCornerRadius(50f);
		
		findViewById(R.id.button_register)
		.setBackground(registerBackground);
		
		// الحواف الدائرية لخلفية شريط التبويبات
		android.graphics.drawable.GradientDrawable tabsBackground =
		new android.graphics.drawable.GradientDrawable();
		
		tabsBackground.setColor(
		android.graphics.Color.parseColor("#FF1D1D20")
		);
		tabsBackground.setCornerRadius(50f);
		
		findViewById(R.id.linear_auth_tabs)
		.setBackground(tabsBackground);
		android.content.SharedPreferences auth =
		getSharedPreferences(
		"auth_session",
		android.app.Activity.MODE_PRIVATE
		);
		
		boolean loggedIn =
		auth.getBoolean("logged_in", false);
		
		if (loggedIn) {
			
			// =========================
			// المستخدم مسجل دخول
			// =========================
			
			// إخفاء واجهة الضيف
			findViewById(R.id.linear_guest_account)
			.setVisibility(android.view.View.GONE);
			
			// إخفاء تبويبات الدخول والتسجيل
			findViewById(R.id.linear_auth_tabs)
			.setVisibility(android.view.View.GONE);
			
			// إخفاء تسجيل الدخول
			findViewById(R.id.linear_login_form)
			.setVisibility(android.view.View.GONE);
			
			findViewById(R.id.edittext_login_email)
			.setVisibility(android.view.View.GONE);
			
			findViewById(R.id.edittext_login_password)
			.setVisibility(android.view.View.GONE);
			
			findViewById(R.id.textview_forgot_password)
			.setVisibility(android.view.View.GONE);
			
			findViewById(R.id.button_login)
			.setVisibility(android.view.View.GONE);
			
			// إخفاء إنشاء الحساب
			findViewById(R.id.linear_register_form)
			.setVisibility(android.view.View.GONE);
			
			findViewById(R.id.edittext_register_name)
			.setVisibility(android.view.View.GONE);
			
			findViewById(R.id.edittext_register_email)
			.setVisibility(android.view.View.GONE);
			
			findViewById(R.id.edittext_register_password)
			.setVisibility(android.view.View.GONE);
			
			findViewById(R.id.edittext_register_confirm_password)
			.setVisibility(android.view.View.GONE);
			
			findViewById(R.id.button_register)
			.setVisibility(android.view.View.GONE);
			
			// إظهار الملف الشخصي
			findViewById(R.id.linear_profile)
			.setVisibility(android.view.View.VISIBLE);
			
			// عرض اسم المستخدم
			((android.widget.TextView)
			findViewById(R.id.textview_profile_name))
			.setText(
			auth.getString("name", "المستخدم")
			);
			
			// عرض البريد الإلكتروني
			((android.widget.TextView)
			findViewById(R.id.textview_profile_email))
			.setText(
			auth.getString("email", "")
			);
			
		} else {
			
			// =========================
			// المستخدم غير مسجل دخول
			// =========================
			
			// إظهار واجهة الضيف
			findViewById(R.id.linear_guest_account)
			.setVisibility(android.view.View.VISIBLE);
			
			
			// =========================
			// تصميم زر تسجيل الدخول
			// أبيض + كتابة سوداء + دائري
			// =========================
			
			android.graphics.drawable.GradientDrawable guestBtnBg =
			new android.graphics.drawable.GradientDrawable();
			
			guestBtnBg.setShape(
			android.graphics.drawable.GradientDrawable.RECTANGLE
			);
			
			guestBtnBg.setColor(
			android.graphics.Color.parseColor("#FFFFFF")
			);
			
			guestBtnBg.setCornerRadius(60f);
			
			android.widget.Button guestBtn =
			(android.widget.Button)
			findViewById(R.id.button_open_login);
			
			guestBtn.setBackground(guestBtnBg);
			
			guestBtn.setTextColor(
			android.graphics.Color.parseColor("#000000")
			);
			
			
			// =========================
			// إخفاء واجهة تسجيل الدخول
			// =========================
			
			findViewById(R.id.linear_auth_tabs)
			.setVisibility(android.view.View.GONE);
			
			findViewById(R.id.linear_login_form)
			.setVisibility(android.view.View.GONE);
			
			findViewById(R.id.edittext_login_email)
			.setVisibility(android.view.View.GONE);
			
			findViewById(R.id.edittext_login_password)
			.setVisibility(android.view.View.GONE);
			
			findViewById(R.id.textview_forgot_password)
			.setVisibility(android.view.View.GONE);
			
			findViewById(R.id.button_login)
			.setVisibility(android.view.View.GONE);
			
			
			// =========================
			// إخفاء واجهة إنشاء الحساب
			// =========================
			
			findViewById(R.id.linear_register_form)
			.setVisibility(android.view.View.GONE);
			
			findViewById(R.id.edittext_register_name)
			.setVisibility(android.view.View.GONE);
			
			findViewById(R.id.edittext_register_email)
			.setVisibility(android.view.View.GONE);
			
			findViewById(R.id.edittext_register_password)
			.setVisibility(android.view.View.GONE);
			
			findViewById(R.id.edittext_register_confirm_password)
			.setVisibility(android.view.View.GONE);
			
			findViewById(R.id.button_register)
			.setVisibility(android.view.View.GONE);
			
			
			// إخفاء الملف الشخصي
			findViewById(R.id.linear_profile)
			.setVisibility(android.view.View.GONE);
		}
		android.widget.LinearLayout genderRow =
		findViewById(R.id.linear_gender);
		
		android.view.View maleButton =
		findViewById(R.id.button_gender_male);
		
		android.view.View femaleButton =
		findViewById(R.id.button_gender_female);
		
		genderRow.removeView(maleButton);
		genderRow.removeView(femaleButton);
		
		genderRow.setLayoutDirection(
		android.view.View.LAYOUT_DIRECTION_LTR
		);
		genderRow.setGravity(android.view.Gravity.CENTER);
		
		genderRow.addView(maleButton);
		genderRow.addView(femaleButton);
		final android.widget.TextView profileUsernameRow =
		findViewById(R.id.textview_profile_username_row);
		
		final android.widget.TextView profileGenderRow =
		findViewById(R.id.textview_profile_gender_row);
		
		final android.widget.TextView profileEmailRow =
		findViewById(R.id.textview_profile_email_row);
		
		final android.widget.TextView oldProfileName =
		findViewById(R.id.textview_profile_name);
		
		final android.widget.TextView oldProfileEmail =
		findViewById(R.id.textview_profile_email);
		
		final android.widget.LinearLayout oldGender =
		findViewById(R.id.linear_gender);
		
		// إخفاء العناصر القديمة
		oldProfileName.setVisibility(android.view.View.GONE);
		oldProfileEmail.setVisibility(android.view.View.GONE);
		oldGender.setVisibility(android.view.View.GONE);
		
		// تنسيق المعلومات الجديدة
		profileUsernameRow.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
		profileGenderRow.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
		profileEmailRow.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
		
		profileUsernameRow.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
		profileGenderRow.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
		profileEmailRow.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
		// ==========================================
		// تعبئة معلومات الحساب الجديدة
		// ==========================================
		
		android.content.SharedPreferences cgProfilePrefs =
		getSharedPreferences(
		"auth_session",
		android.app.Activity.MODE_PRIVATE
		);
		
		// البيانات المحفوظة
		String cgName =
		cgProfilePrefs.getString("name", "المستخدم");
		
		String cgEmail =
		cgProfilePrefs.getString("email", "");
		
		String cgGender =
		cgProfilePrefs.getString("gender", "");
		
		// تحويل الجنس للعربي
		String cgGenderArabic = "غير محدد";
		
		if ("male".equalsIgnoreCase(cgGender)) {
			cgGenderArabic = "ذكر";
		}
		else if ("female".equalsIgnoreCase(cgGender)) {
			cgGenderArabic = "أنثى";
		}
		else if ("ذكر".equals(cgGender)) {
			cgGenderArabic = "ذكر";
		}
		else if ("أنثى".equals(cgGender)) {
			cgGenderArabic = "أنثى";
		}
		
		
		// ==========================================
		// العناصر الجديدة
		// ==========================================
		
		android.widget.TextView cgUsernameRow =
		findViewById(R.id.textview_profile_username_row);
		
		android.widget.TextView cgGenderRow =
		findViewById(R.id.textview_profile_gender_row);
		
		android.widget.TextView cgEmailRow =
		findViewById(R.id.textview_profile_email_row);
		
		
		// ==========================================
		// عرض القيم
		// ==========================================
		
		cgUsernameRow.setText(
		cgName
		);
		
		cgGenderRow.setText(
		cgGenderArabic
		);
		
		cgEmailRow.setText(
		cgEmail
		);
		
		
		// ==========================================
		// ترتيب النص
		// ==========================================
		
		android.widget.TextView[] cgProfileRows = {
			cgUsernameRow,
			cgGenderRow,
			cgEmailRow
		};
		
		for (android.widget.TextView cgRow : cgProfileRows) {
			
			cgRow.setGravity(
			android.view.Gravity.RIGHT |
			android.view.Gravity.CENTER_VERTICAL
			);
			
			cgRow.setTextDirection(
			android.view.View.TEXT_DIRECTION_RTL
			);
			
			cgRow.setTextColor(
			android.graphics.Color.WHITE
			);
			
			cgRow.setTextSize(17);
			
			cgRow.setSingleLine(false);
			
			cgRow.setPadding(
			0,
			12,
			0,
			12
			);
		}
		// CIGRAM: الصورة الشخصية للعرض فقط — لا فتح معرض/كاميرا ولا نص تغيير.
		android.widget.LinearLayout swAvatarRowNew =
		(android.widget.LinearLayout)findViewById(R.id.linear_profile_avatar_row);
		
		de.hdodenhof.circleimageview.CircleImageView swAvatarNew =
		(de.hdodenhof.circleimageview.CircleImageView)findViewById(R.id.circleimageview_profile_avatar);
		
		if (swAvatarRowNew != null && swAvatarNew != null) {
			swAvatarRowNew.removeAllViews();
			swAvatarRowNew.setOrientation(android.widget.LinearLayout.VERTICAL);
			swAvatarRowNew.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
			swAvatarRowNew.setGravity(android.view.Gravity.CENTER);
			swAvatarRowNew.setPadding(
			(int)(12 * getResources().getDisplayMetrics().density),
			(int)(12 * getResources().getDisplayMetrics().density),
			(int)(12 * getResources().getDisplayMetrics().density),
			(int)(12 * getResources().getDisplayMetrics().density)
			);
			
			android.widget.LinearLayout.LayoutParams swAvatarParamsNew =
			new android.widget.LinearLayout.LayoutParams(
			(int)(108 * getResources().getDisplayMetrics().density),
			(int)(108 * getResources().getDisplayMetrics().density)
			);
			swAvatarParamsNew.gravity = android.view.Gravity.CENTER;
			swAvatarNew.setLayoutParams(swAvatarParamsNew);
			swAvatarNew.setClickable(false);
			swAvatarNew.setFocusable(false);
			swAvatarNew.setLongClickable(false);
			swAvatarNew.setOnClickListener(null);
			swAvatarRowNew.addView(swAvatarNew);
		}
		// إخفاء العناصر القديمة من صفحة الحساب
		findViewById(R.id.textview_profile_name)
		.setVisibility(android.view.View.GONE);
		
		findViewById(R.id.textview_profile_email)
		.setVisibility(android.view.View.GONE);
		
		findViewById(R.id.linear_gender)
		.setVisibility(android.view.View.GONE);
		android.view.View oldAvatarSection =
		findViewById(R.id.linear_profile_avatar_row);
		
		if (oldAvatarSection != null) {
			oldAvatarSection.setVisibility(
			loggedIn ? android.view.View.VISIBLE : android.view.View.GONE
			);
		}
		final android.content.SharedPreferences fontSectionAuth =
		getSharedPreferences(
		"auth_session",
		MODE_PRIVATE
		);
		
		final String fontSectionToken =
		fontSectionAuth.getString(
		"token",
		""
		);
		
		android.view.View subtitleFontSection =
		findViewById(
		R.id.linear_account_subtitle_font
		);
		
		if (subtitleFontSection != null) {
			
			if (
			fontSectionToken == null ||
			fontSectionToken.trim().isEmpty()
			) {
				
				subtitleFontSection.setVisibility(
				android.view.View.GONE
				);
				
			} else {
				
				subtitleFontSection.setVisibility(
				android.view.View.VISIBLE
				);
			}
		}
		final android.widget.LinearLayout myContentRow =
		(android.widget.LinearLayout) findViewById(
		R.id.linear_my_content
		);
		
		final android.content.SharedPreferences myContentAuth =
		getSharedPreferences(
		"auth_session",
		MODE_PRIVATE
		);
		
		final boolean myContentLoggedIn =
		myContentAuth.getBoolean(
		"logged_in",
		false
		);
		
		final String myContentToken =
		myContentAuth.getString(
		"token",
		""
		);
		
		
		// يظهر محتوياتي فقط للمستخدم المسجل
		if (
		myContentLoggedIn
		&&
		myContentToken != null
		&&
		!myContentToken.trim().equals("")
		) {
			
			myContentRow.setVisibility(
			android.view.View.VISIBLE
			);
			
		} else {
			
			myContentRow.setVisibility(
			android.view.View.GONE
			);
		}
		
		
		// عند الضغط يفتح صفحة محتوياتي
		myContentRow.setOnClickListener(
		new android.view.View.OnClickListener() {
			
			@Override
			public void onClick(
			android.view.View view
			) {
				
				boolean loggedIn =
				myContentAuth.getBoolean(
				"logged_in",
				false
				);
				
				String token =
				myContentAuth.getString(
				"token",
				""
				);
				
				if (
				loggedIn
				&&
				token != null
				&&
				!token.trim().equals("")
				) {
					
					android.content.Intent intent =
					new android.content.Intent(
					AccountActivity.this,
					MyContentActivity.class
					);
					
					intent.putExtra(
					"token",
					token
					);
					
					startActivity(
					intent
					);
				}
			}
		}
		);
		final android.view.View helpSupportRow =
		findViewById(
		R.id.linear_help_support
		);
		
		final android.content.SharedPreferences supportAuth =
		getSharedPreferences(
		"auth_session",
		MODE_PRIVATE
		);
		
		boolean supportLoggedIn =
		supportAuth.getBoolean(
		"logged_in",
		false
		);
		
		String supportToken =
		supportAuth.getString(
		"token",
		""
		);
		
		if (
		supportLoggedIn
		&&
		supportToken != null
		&&
		!supportToken.trim().equals("")
		) {
			
			helpSupportRow.setVisibility(
			android.view.View.VISIBLE
			);
			
		} else {
			
			helpSupportRow.setVisibility(
			android.view.View.GONE
			);
		}
		// Two independent cards: fixed avatar first, values immediately underneath.
		final android.widget.LinearLayout cgAvatarCard=(android.widget.LinearLayout)findViewById(R.id.linear_profile_avatar_row);
		final android.widget.LinearLayout cgInfoCard=(android.widget.LinearLayout)findViewById(R.id.linear_profile_info);
		final android.view.View cgAvatar=findViewById(R.id.circleimageview_profile_avatar);
		if(cgAvatarCard!=null&&cgAvatar!=null){
			cgAvatarCard.setOrientation(android.widget.LinearLayout.VERTICAL);
			cgAvatarCard.setGravity(android.view.Gravity.CENTER);
			cgAvatarCard.setPadding(cgGlass.px(18),cgGlass.px(18),cgGlass.px(18),cgGlass.px(18));
			cgGlass.bind(cgAvatarCard,28,true);
			android.widget.LinearLayout.LayoutParams cgAvatarCardLp=new android.widget.LinearLayout.LayoutParams(-1,-2);
			cgAvatarCardLp.setMargins(cgGlass.px(12),cgGlass.px(12),cgGlass.px(12),cgGlass.px(8));
			cgAvatarCard.setLayoutParams(cgAvatarCardLp);
			android.widget.LinearLayout.LayoutParams cgAvatarLp=new android.widget.LinearLayout.LayoutParams(cgGlass.px(108),cgGlass.px(108));
			cgAvatarLp.gravity=android.view.Gravity.CENTER;
			cgAvatar.setLayoutParams(cgAvatarLp);
			cgAvatar.setClickable(false);
			cgAvatar.setFocusable(false);
			cgAvatar.setLongClickable(false);
			cgAvatar.setOnClickListener(null);
		}
		cgInfoCard.setOrientation(1);cgInfoCard.setGravity(android.view.Gravity.RIGHT);cgInfoCard.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
		cgInfoCard.setPadding(cgGlass.px(20),cgGlass.px(12),cgGlass.px(20),cgGlass.px(12));cgGlass.bind(cgInfoCard,25,true);
		android.widget.LinearLayout.LayoutParams cgInfoLp=new android.widget.LinearLayout.LayoutParams(-1,-2);
		cgInfoLp.setMargins(cgGlass.px(12),cgGlass.px(8),cgGlass.px(12),cgGlass.px(16));cgInfoCard.setLayoutParams(cgInfoLp);
		android.widget.TextView[] cgValueViews={
			(android.widget.TextView)findViewById(R.id.textview_profile_username_row),
			(android.widget.TextView)findViewById(R.id.textview_profile_gender_row),
			(android.widget.TextView)findViewById(R.id.textview_profile_email_row)};
		for(int i=0;i<cgValueViews.length;i++) {
			android.widget.TextView text=cgValueViews[i];text.setTextSize(i==0?22:15);text.setTypeface(cgGlass.font);
			text.setTextColor(i==0?0xFFFFFFFF:0xCCFFFFFF);text.setPadding(0,cgGlass.px(8),0,cgGlass.px(8));
			text.setGravity(android.view.Gravity.RIGHT|android.view.Gravity.CENTER_VERTICAL);
			text.setTextDirection(android.view.View.TEXT_DIRECTION_FIRST_STRONG);text.setMaxLines(i==2?2:1);
			text.setLayoutParams(new android.widget.LinearLayout.LayoutParams(-1,-2));
		}
		int[] cgAccountButtons={R.id.linear_account_settings,R.id.linear_account_subtitle_font,R.id.linear_my_content,
			R.id.linear_help_support,R.id.button_open_login,R.id.button_login,R.id.button_register};
		for(int id:cgAccountButtons) {android.view.View view=findViewById(id);cgGlass.bind(view,24,true);cgGlass.press(view);if(view instanceof android.widget.TextView)((android.widget.TextView)view).setTextColor(0xFFFFFFFF);}
		findViewById(R.id.linear_account_content).setBackgroundColor(0xFF111113);
		if (!getIntent().getBooleanExtra("cigram_nav_reveal", false)) {
			cgAvatarCard.setAlpha(0f);cgInfoCard.setAlpha(0f);cgAvatarCard.animate().alpha(1f).setDuration(250L).start();cgInfoCard.animate().alpha(1f).setStartDelay(70L).setDuration(250L).start();
		}
		
		
		// CIGRAM: clean profile avatar, no blue border/ring.
		try {
			de.hdodenhof.circleimageview.CircleImageView cleanProfileAvatar =
			(de.hdodenhof.circleimageview.CircleImageView)
			findViewById(R.id.circleimageview_profile_avatar);
			if (cleanProfileAvatar != null) {
				cleanProfileAvatar.setBorderWidth(0);
				cleanProfileAvatar.setBorderColor(android.graphics.Color.TRANSPARENT);
				cleanProfileAvatar.setCircleBackgroundColor(android.graphics.Color.TRANSPARENT);
				cleanProfileAvatar.setPadding(0,0,0,0);
			}
		} catch (Exception ignored) {}
		
		
		// CIGRAM: "تواصل معنا" — consistent row + dark glass dialog.
		try {
			final android.widget.LinearLayout cgContactParent =
			(android.widget.LinearLayout) findViewById(
			R.id.linear_account_content
			);
			
			if (
			cgContactParent != null
			&& cgContactParent.findViewWithTag(
			"cigram_contact_us"
			) == null
			) {
				final android.widget.LinearLayout cgContactRow =
				new android.widget.LinearLayout(
				AccountActivity.this
				);
				
				cgContactRow.setTag("cigram_contact_us");
				cgContactRow.setOrientation(
				android.widget.LinearLayout.HORIZONTAL
				);
				cgContactRow.setLayoutDirection(
				android.view.View.LAYOUT_DIRECTION_LTR
				);
				cgContactRow.setGravity(
				android.view.Gravity.CENTER_VERTICAL
				);
				cgContactRow.setPadding(
				cgGlass.px(10), 0,
				cgGlass.px(14), 0
				);
				cgGlass.bind(cgContactRow, 24, true);
				cgGlass.press(cgContactRow);
				
				android.widget.ImageView cgContactArrow =
				new android.widget.ImageView(
				AccountActivity.this
				);
				cgContactArrow.setImageResource(
				R.drawable.hsi
				);
				cgContactArrow.setScaleType(
				android.widget.ImageView.ScaleType.CENTER_INSIDE
				);
				cgContactArrow.setAlpha(0.82f);
				cgContactArrow.setContentDescription(
				"تواصل معنا"
				);
				
				cgContactRow.addView(
				cgContactArrow,
				new android.widget.LinearLayout.LayoutParams(
				cgGlass.px(30),
				cgGlass.px(30)
				)
				);
				
				android.widget.TextView cgContactLabel =
				cgGlass.label("تواصل معنا", 18);
				cgContactLabel.setGravity(
				android.view.Gravity.RIGHT
				| android.view.Gravity.CENTER_VERTICAL
				);
				cgContactLabel.setTextColor(0xFFFFFFFF);
				cgContactLabel.setPadding(
				cgGlass.px(8), 0,
				cgGlass.px(8), 0
				);
				cgContactRow.addView(
				cgContactLabel,
				new android.widget.LinearLayout.LayoutParams(
				0, -1, 1f
				)
				);
				
				android.widget.LinearLayout.LayoutParams cgContactLp =
				new android.widget.LinearLayout.LayoutParams(
				-1,
				cgGlass.px(56)
				);
				cgContactLp.setMargins(
				cgGlass.px(12),
				cgGlass.px(6),
				cgGlass.px(12),
				cgGlass.px(6)
				);
				
				android.view.View cgHelpRow =
				findViewById(
				R.id.linear_help_support
				);
				
				int cgContactIndex =
				cgContactParent.getChildCount();
				
				if (
				cgHelpRow != null
				&& cgHelpRow.getParent()
				== cgContactParent
				) {
					cgContactIndex =
					cgContactParent.indexOfChild(
					cgHelpRow
					) + 1;
				}
				
				cgContactParent.addView(
				cgContactRow,
				Math.min(
				Math.max(0, cgContactIndex),
				cgContactParent.getChildCount()
				),
				cgContactLp
				);
				
				cgContactRow.setOnClickListener(
				new android.view.View.OnClickListener() {
					@Override
					public void onClick(android.view.View v) {
						// CIGRAM: صفحة «تواصل معنا» الجديدة (communication.xml)
						AccountActivity.this.startActivity(
						new android.content.Intent(AccountActivity.this, CommunicationActivity.class));
					}
				}
				);
			}
		} catch (Exception ignored) {}
		
		
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
		
		
		// v3.8: تسجيل منتقي الخط الخاص (يجب أن يكون في onCreate قبل بدء النشاط)
		try { CigramFonts.installPicker(AccountActivity.this); } catch (Throwable ignored) {}
		
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
			}
		}
		
		
		// CIGRAM v2.5: footer group (التحقق من التحديثات + تواصل معنا) always at the bottom, in this order.
		try {
			CigramAccountExtras.arrange(AccountActivity.this);
			final android.os.Handler cgFooterHandler = new android.os.Handler(android.os.Looper.getMainLooper());
			final int[] cgFooterDelays = {250, 700, 1500};
			for (int cgDelay : cgFooterDelays) {
				cgFooterHandler.postDelayed(new Runnable() {
					@Override public void run() {
						if (!isFinishing()) CigramAccountExtras.arrange(AccountActivity.this);
					}
				}, cgDelay);
			}
		} catch (Throwable cgFooterError) {
			android.util.Log.w("CigramAccount", "footer failed", cgFooterError);
		}
		
	}
	
}
