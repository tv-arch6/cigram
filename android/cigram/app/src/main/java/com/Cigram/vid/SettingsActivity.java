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

public class SettingsActivity extends AppCompatActivity {
	
	private SettingsBinding binding;
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		binding = SettingsBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		initialize(_savedInstanceState);
		
		CigramAdManager.initialize(getApplicationContext()); // self-throttled, background thread, consent-gated
		
		initializeLogic();
	}
	
	private void initialize(Bundle _savedInstanceState) {
		
		binding.linear2.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				
			}
		});
		
		binding.linearSettingsEditProfile.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				
			}
		});
		
		binding.textviewSettingsEditProfile.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				{
					final android.content.SharedPreferences editAuth =
					getSharedPreferences("auth_session", MODE_PRIVATE);
					
					final String savedToken =
					editAuth.getString("token", "");
					
					final String editToken =
					savedToken == null ? "" : savedToken.trim();
					
					if (!editAuth.getBoolean("logged_in", false)
					|| editToken.isEmpty()) {
						CigramUtil.showMessage(
						getApplicationContext(),
						"سجّل الدخول أولاً"
						);
						return;
					}
					
					final float editD =
					getResources().getDisplayMetrics().density;
					
					final android.app.Dialog editDialog =
					new android.app.Dialog(SettingsActivity.this);
					
					editDialog.requestWindowFeature(
					android.view.Window.FEATURE_NO_TITLE
					);
					
					final android.widget.LinearLayout editBox =
					new android.widget.LinearLayout(SettingsActivity.this);
					
					editBox.setOrientation(
					android.widget.LinearLayout.VERTICAL
					);
					editBox.setLayoutDirection(
					android.view.View.LAYOUT_DIRECTION_RTL
					);
					editBox.setPadding(
					(int) (22 * editD),
					(int) (20 * editD),
					(int) (22 * editD),
					(int) (18 * editD)
					);
					
					android.graphics.drawable.GradientDrawable editBoxBg =
					new android.graphics.drawable.GradientDrawable();
					
					editBoxBg.setColor(0xFF1A1A1D);
					editBoxBg.setCornerRadius(22 * editD);
					editBox.setBackground(editBoxBg);
					
					final android.widget.TextView editTitle =
					new android.widget.TextView(SettingsActivity.this);
					
					editTitle.setText("تعديل الحساب");
					editTitle.setTextSize(21);
					editTitle.setTextColor(android.graphics.Color.WHITE);
					editTitle.setGravity(android.view.Gravity.RIGHT);
					editBox.addView(
					editTitle,
					new android.widget.LinearLayout.LayoutParams(-1, -2)
					);
					
					final android.widget.TextView nameLabel =
					new android.widget.TextView(SettingsActivity.this);
					
					nameLabel.setText("اسم المستخدم");
					nameLabel.setTextColor(0xFF9AA9B0);
					nameLabel.setTextSize(13);
					nameLabel.setGravity(android.view.Gravity.RIGHT);
					nameLabel.setPadding(
					0, (int) (18 * editD),
					0, (int) (7 * editD)
					);
					editBox.addView(nameLabel);
					
					final android.widget.EditText nameInput =
					new android.widget.EditText(SettingsActivity.this);
					
					String savedName = editAuth.getString("name", "");
					nameInput.setText(savedName == null ? "" : savedName);
					nameInput.setHint("اسم المستخدم");
					nameInput.setSingleLine(true);
					nameInput.setTextSize(16);
					nameInput.setTextColor(android.graphics.Color.WHITE);
					nameInput.setHintTextColor(0xFF71838C);
					nameInput.setGravity(
					android.view.Gravity.RIGHT
					| android.view.Gravity.CENTER_VERTICAL
					);
					nameInput.setPadding(
					(int) (14 * editD), 0,
					(int) (14 * editD), 0
					);
					
					android.graphics.drawable.GradientDrawable nameBg =
					new android.graphics.drawable.GradientDrawable();
					
					nameBg.setColor(0xFF262629);
					nameBg.setCornerRadius(14 * editD);
					nameInput.setBackground(nameBg);
					
					android.widget.LinearLayout.LayoutParams nameParams =
					new android.widget.LinearLayout.LayoutParams(
					-1, (int) (52 * editD)
					);
					
					nameParams.bottomMargin = (int) (18 * editD);
					editBox.addView(nameInput, nameParams);
					
					final android.widget.TextView genderLabel =
					new android.widget.TextView(SettingsActivity.this);
					
					genderLabel.setText("الجنس");
					genderLabel.setTextColor(0xFF9AA9B0);
					genderLabel.setTextSize(13);
					genderLabel.setGravity(android.view.Gravity.RIGHT);
					genderLabel.setPadding(
					0, 0, 0, (int) (7 * editD)
					);
					editBox.addView(genderLabel);
					
					final android.widget.LinearLayout genderBox =
					new android.widget.LinearLayout(SettingsActivity.this);
					
					genderBox.setOrientation(
					android.widget.LinearLayout.HORIZONTAL
					);
					genderBox.setLayoutDirection(
					android.view.View.LAYOUT_DIRECTION_LTR
					);
					
					final android.widget.TextView femaleBtn =
					new android.widget.TextView(SettingsActivity.this);
					
					final android.widget.TextView maleBtn =
					new android.widget.TextView(SettingsActivity.this);
					
					femaleBtn.setText("أنثى");
					maleBtn.setText("ذكر");
					femaleBtn.setTextSize(15);
					maleBtn.setTextSize(15);
					femaleBtn.setGravity(android.view.Gravity.CENTER);
					maleBtn.setGravity(android.view.Gravity.CENTER);
					
					String savedGender = editAuth.getString(
					"gender", "unspecified"
					);
					
					if ("أنثى".equals(savedGender)) {
						savedGender = "female";
					} else if ("ذكر".equals(savedGender)) {
						savedGender = "male";
					}
					
					final String[] selectedGender = {
						savedGender == null ? "unspecified" : savedGender
					};
					
					class EditGenderStyle {
						void update() {
							android.graphics.drawable.GradientDrawable femaleBg =
							new android.graphics.drawable.GradientDrawable();
							
							android.graphics.drawable.GradientDrawable maleBg =
							new android.graphics.drawable.GradientDrawable();
							
							femaleBg.setCornerRadius(12 * editD);
							maleBg.setCornerRadius(12 * editD);
							
							boolean female =
							"female".equals(selectedGender[0]);
							
							boolean male =
							"male".equals(selectedGender[0]);
							
							femaleBg.setColor(
							female ? 0xFFFFFFFF : 0xFF2D2D30
							);
							maleBg.setColor(
							male ? 0xFFFFFFFF : 0xFF2D2D30
							);
							
							femaleBtn.setTextColor(
							female ? 0xFF000000 : 0xFFFFFFFF
							);
							maleBtn.setTextColor(
							male ? 0xFF000000 : 0xFFFFFFFF
							);
							
							femaleBtn.setBackground(femaleBg);
							maleBtn.setBackground(maleBg);
						}
					}
					
					final EditGenderStyle genderStyle =
					new EditGenderStyle();
					
					genderStyle.update();
					
					femaleBtn.setOnClickListener(
					new android.view.View.OnClickListener() {
						@Override
						public void onClick(android.view.View view) {
							selectedGender[0] = "female";
							genderStyle.update();
						}
					}
					);
					
					maleBtn.setOnClickListener(
					new android.view.View.OnClickListener() {
						@Override
						public void onClick(android.view.View view) {
							selectedGender[0] = "male";
							genderStyle.update();
						}
					}
					);
					
					android.widget.LinearLayout.LayoutParams femaleParams =
					new android.widget.LinearLayout.LayoutParams(
					0, (int) (48 * editD), 1f
					);
					
					femaleParams.rightMargin = (int) (6 * editD);
					
					android.widget.LinearLayout.LayoutParams maleParams =
					new android.widget.LinearLayout.LayoutParams(
					0, (int) (48 * editD), 1f
					);
					
					maleParams.leftMargin = (int) (6 * editD);
					
					genderBox.addView(femaleBtn, femaleParams);
					genderBox.addView(maleBtn, maleParams);
					
					android.widget.LinearLayout.LayoutParams genderBoxParams =
					new android.widget.LinearLayout.LayoutParams(-1, -2);
					
					genderBoxParams.bottomMargin = (int) (22 * editD);
					editBox.addView(genderBox, genderBoxParams);
					
					final android.widget.LinearLayout editButtons =
					new android.widget.LinearLayout(SettingsActivity.this);
					
					editButtons.setOrientation(
					android.widget.LinearLayout.HORIZONTAL
					);
					editButtons.setLayoutDirection(
					android.view.View.LAYOUT_DIRECTION_LTR
					);
					
					final android.widget.TextView cancelBtn =
					new android.widget.TextView(SettingsActivity.this);
					
					cancelBtn.setText("إلغاء");
					cancelBtn.setTextSize(15);
					cancelBtn.setTextColor(android.graphics.Color.WHITE);
					cancelBtn.setGravity(android.view.Gravity.CENTER);
					
					android.graphics.drawable.GradientDrawable cancelBg =
					new android.graphics.drawable.GradientDrawable();
					
					cancelBg.setColor(0xFF2D2D30);
					cancelBg.setCornerRadius(12 * editD);
					cancelBtn.setBackground(cancelBg);
					
					final android.widget.TextView saveBtn =
					new android.widget.TextView(SettingsActivity.this);
					
					saveBtn.setText("حفظ");
					saveBtn.setTextSize(15);
					saveBtn.setTextColor(android.graphics.Color.WHITE);
					saveBtn.setGravity(android.view.Gravity.CENTER);
					
					android.graphics.drawable.GradientDrawable saveBg =
					new android.graphics.drawable.GradientDrawable();
					
					saveBg.setColor(0xFFE91B23);
					saveBg.setCornerRadius(12 * editD);
					saveBtn.setBackground(saveBg);
					
					android.widget.LinearLayout.LayoutParams cancelParams =
					new android.widget.LinearLayout.LayoutParams(
					0, (int) (48 * editD), 1f
					);
					
					cancelParams.rightMargin = (int) (6 * editD);
					
					android.widget.LinearLayout.LayoutParams saveParams =
					new android.widget.LinearLayout.LayoutParams(
					0, (int) (48 * editD), 1f
					);
					
					saveParams.leftMargin = (int) (6 * editD);
					
					editButtons.addView(cancelBtn, cancelParams);
					editButtons.addView(saveBtn, saveParams);
					editBox.addView(editButtons);
					
					try {
						android.graphics.Typeface editFont =
						CigramTypefaces.get(
						getAssets(),
						CigramTypefaces.APP_FONT
						);
						
						editTitle.setTypeface(editFont);
						nameLabel.setTypeface(editFont);
						nameInput.setTypeface(editFont);
						genderLabel.setTypeface(editFont);
						femaleBtn.setTypeface(editFont);
						maleBtn.setTypeface(editFont);
						cancelBtn.setTypeface(editFont);
						saveBtn.setTypeface(editFont);
					} catch (Exception ignored) {
					}
					
					editDialog.setContentView(editBox);
					
					cancelBtn.setOnClickListener(
					new android.view.View.OnClickListener() {
						@Override
						public void onClick(android.view.View view) {
							editDialog.dismiss();
						}
					}
					);
					
					saveBtn.setOnClickListener(
					new android.view.View.OnClickListener() {
						@Override
						public void onClick(android.view.View view) {
							final String newName =
							nameInput.getText().toString().trim();
							
							final String newGender =
							selectedGender[0];
							
							if (newName.length() < 2
							|| newName.length() > 60) {
								CigramUtil.showMessage(
								getApplicationContext(),
								"الاسم يجب أن يكون من 2 إلى 60 حرفاً"
								);
								return;
							}
							
							String currentToken =
							editAuth.getString("token", "");
							
							if (!editAuth.getBoolean(
							"logged_in", false
							)
							|| !editToken.equals(currentToken)) {
								editDialog.dismiss();
								CigramUtil.showMessage(
								getApplicationContext(),
								"تغيّرت جلسة الحساب؛ افتح الصفحة مجددًا"
								);
								return;
							}
							
							saveBtn.setEnabled(false);
							cancelBtn.setEnabled(false);
							editDialog.setCancelable(false);
							saveBtn.setText("جاري الحفظ...");
							
							new Thread(
							new Runnable() {
								@Override
								public void run() {
									java.net.HttpURLConnection editCon =
									null;
									
									try {
										java.net.URL editUrl =
										new java.net.URL(
										"https://cigram-auth-api.wwq-mixtv.workers.dev/profile/update"
										);
										
										editCon =
										(java.net.HttpURLConnection)
										editUrl.openConnection();
										
										editCon.setRequestMethod("POST");
										editCon.setConnectTimeout(15000);
										editCon.setReadTimeout(15000);
										editCon.setDoOutput(true);
										editCon.setUseCaches(false);
										
										editCon.setRequestProperty(
										"Authorization",
										"Bearer " + editToken
										);
										editCon.setRequestProperty(
										"Content-Type",
										"application/json; charset=UTF-8"
										);
										editCon.setRequestProperty(
										"Accept",
										"application/json"
										);
										
										org.json.JSONObject editBody =
										new org.json.JSONObject();
										
										editBody.put("name", newName);
										editBody.put("gender", newGender);
										
										java.io.OutputStream output =
										editCon.getOutputStream();
										
										try {
											output.write(
											editBody.toString()
											.getBytes("UTF-8")
											);
											output.flush();
										} finally {
											output.close();
										}
										
										int code =
										editCon.getResponseCode();
										
										java.io.InputStream responseStream =
										code >= 200 && code < 300
										? editCon.getInputStream()
										: editCon.getErrorStream();
										
										StringBuilder result =
										new StringBuilder();
										
										if (responseStream != null) {
											java.io.BufferedReader reader =
											new java.io.BufferedReader(
											new java.io.InputStreamReader(
											responseStream,
											"UTF-8"
											)
											);
											
											try {
												String line;
												
												while ((line = reader.readLine())
												!= null) {
													result.append(line);
													
													if (result.length()
													> 65536) {
														throw new java.io.IOException(
														"Response too large"
														);
													}
												}
											} finally {
												reader.close();
											}
										}
										
										org.json.JSONObject response =
										new org.json.JSONObject(
										result.toString()
										);
										
										final boolean success =
										code >= 200
										&& code < 300
										&& response.optBoolean(
										"success", false
										);
										
										String serverMessage =
										response.optString(
										"message", ""
										).trim();
										
										final String message =
										serverMessage.isEmpty()
										? "تعذر تعديل الحساب"
										: serverMessage;
										
										org.json.JSONObject user =
										response.optJSONObject("user");
										
										String returnedName =
										user == null
										? ""
										: user.optString(
										"name", ""
										).trim();
										
										String returnedGender =
										user == null
										? ""
										: user.optString(
										"gender", ""
										).trim();
										
										final String confirmedName =
										returnedName.isEmpty()
										? newName
										: returnedName;
										
										final String confirmedGender =
										returnedGender.isEmpty()
										? newGender
										: returnedGender;
										
										runOnUiThread(
										new Runnable() {
											@Override
											public void run() {
												String currentToken =
												editAuth.getString(
												"token", ""
												);
												
												if (!editAuth.getBoolean(
												"logged_in", false
												)
												|| !editToken.equals(
												currentToken
												)) {
													editDialog.dismiss();
													return;
												}
												
												if (success) {
													editAuth.edit()
													.putString(
													"name",
													confirmedName
													)
													.putString(
													"gender",
													confirmedGender
													)
													.apply();
													
													editDialog.dismiss();
													
													CigramUtil.showMessage(
													getApplicationContext(),
													"تم تحديث الحساب"
													);
													
													android.content.Intent intent =
													new android.content.Intent(
													SettingsActivity.this,
													AccountActivity.class
													);
													
													intent.addFlags(
													android.content.Intent
													.FLAG_ACTIVITY_CLEAR_TOP
													);
													
													startActivity(intent);
													finish();
												} else {
													saveBtn.setEnabled(true);
													cancelBtn.setEnabled(true);
													editDialog.setCancelable(true);
													saveBtn.setText("حفظ");
													
													CigramUtil.showMessage(
													getApplicationContext(),
													message
													);
												}
											}
										}
										);
										
									} catch (Exception error) {
										runOnUiThread(
										new Runnable() {
											@Override
											public void run() {
												if (!editDialog.isShowing()) {
													return;
												}
												
												// v10.67: no connection -> the edit is kept on the device and sent later
												if (CigramProfile.saveLocalEdit(
												getApplicationContext(),
												newName,
												newGender)) {
													editDialog.dismiss();
													CigramUtil.showMessage(
													getApplicationContext(),
													"تم حفظ التعديل على جهازك وسيُرسل عند توفر الاتصال"
													);
													android.content.Intent keptIntent =
													new android.content.Intent(
													SettingsActivity.this,
													AccountActivity.class
													);
													keptIntent.addFlags(
													android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
													);
													startActivity(keptIntent);
													finish();
													return;
												}
												
												saveBtn.setEnabled(true);
												cancelBtn.setEnabled(true);
												editDialog.setCancelable(true);
												saveBtn.setText("حفظ");
												
												CigramUtil.showMessage(
												getApplicationContext(),
												"تعذر الاتصال بالخادم أو قراءة استجابته"
												);
											}
										}
										);
									} finally {
										if (editCon != null) {
											editCon.disconnect();
										}
									}
								}
							},
							"cigram-edit-profile"
							).start();
						}
					}
					);
					
					editDialog.show();
					
					if (editDialog.getWindow() != null) {
						editDialog.getWindow().setBackgroundDrawable(
						new android.graphics.drawable.ColorDrawable(
						android.graphics.Color.TRANSPARENT
						)
						);
						
						int dialogWidth =
						(int) (
						getResources()
						.getDisplayMetrics()
						.widthPixels
						* 0.90f
						);
						
						editDialog.getWindow().setLayout(
						dialogWidth,
						android.view.WindowManager.LayoutParams.WRAP_CONTENT
						);
					}
				}
			}
		});
		
		binding.textviewSettingsLogout.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				final android.content.SharedPreferences auth =
				getSharedPreferences("auth_session", MODE_PRIVATE);
				
				final String savedToken = auth.getString("token", "");
				final String token = savedToken == null ? "" : savedToken.trim();
				
				if (token.isEmpty()) {
					CigramUtil.showMessage(
					getApplicationContext(),
					"أنت غير مسجل دخول"
					);
					return;
				}
				
				final float d = getResources().getDisplayMetrics().density;
				
				final android.app.Dialog logoutDialog =
				new android.app.Dialog(SettingsActivity.this);
				
				logoutDialog.requestWindowFeature(
				android.view.Window.FEATURE_NO_TITLE
				);
				
				final android.widget.LinearLayout logoutBox =
				new android.widget.LinearLayout(SettingsActivity.this);
				
				logoutBox.setOrientation(android.widget.LinearLayout.VERTICAL);
				logoutBox.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
				logoutBox.setPadding(
				(int)(22 * d),
				(int)(22 * d),
				(int)(22 * d),
				(int)(18 * d)
				);
				
				android.graphics.drawable.GradientDrawable boxBg =
				new android.graphics.drawable.GradientDrawable();
				
				boxBg.setColor(0xFF1A1A1D);
				boxBg.setCornerRadius(22 * d);
				logoutBox.setBackground(boxBg);
				
				final android.widget.TextView title =
				new android.widget.TextView(SettingsActivity.this);
				
				title.setText("تسجيل الخروج");
				title.setTextColor(android.graphics.Color.WHITE);
				title.setTextSize(20);
				title.setGravity(android.view.Gravity.RIGHT);
				title.setIncludeFontPadding(false);
				
				logoutBox.addView(
				title,
				new android.widget.LinearLayout.LayoutParams(-1, -2)
				);
				
				final android.widget.TextView message =
				new android.widget.TextView(SettingsActivity.this);
				
				message.setText("هل تريد تسجيل الخروج من حسابك؟");
				message.setTextColor(0xFF9CAAB1);
				message.setTextSize(14);
				message.setGravity(android.view.Gravity.RIGHT);
				message.setIncludeFontPadding(false);
				message.setPadding(0, (int)(12 * d), 0, (int)(20 * d));
				
				logoutBox.addView(
				message,
				new android.widget.LinearLayout.LayoutParams(-1, -2)
				);
				
				final android.widget.LinearLayout buttonsRow =
				new android.widget.LinearLayout(SettingsActivity.this);
				
				buttonsRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
				buttonsRow.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);
				
				final android.widget.TextView cancelBtn =
				new android.widget.TextView(SettingsActivity.this);
				
				cancelBtn.setText("إلغاء");
				cancelBtn.setTextColor(android.graphics.Color.WHITE);
				cancelBtn.setTextSize(15);
				cancelBtn.setGravity(android.view.Gravity.CENTER);
				cancelBtn.setIncludeFontPadding(false);
				
				android.graphics.drawable.GradientDrawable cancelBg =
				new android.graphics.drawable.GradientDrawable();
				
				cancelBg.setColor(0xFF2D2D30);
				cancelBg.setCornerRadius(13 * d);
				cancelBtn.setBackground(cancelBg);
				
				final android.widget.TextView logoutBtn =
				new android.widget.TextView(SettingsActivity.this);
				
				logoutBtn.setText("تسجيل الخروج");
				logoutBtn.setTextColor(android.graphics.Color.WHITE);
				logoutBtn.setTextSize(15);
				logoutBtn.setGravity(android.view.Gravity.CENTER);
				logoutBtn.setIncludeFontPadding(false);
				
				android.graphics.drawable.GradientDrawable logoutBg =
				new android.graphics.drawable.GradientDrawable();
				
				logoutBg.setColor(0xFFE91B23);
				logoutBg.setCornerRadius(13 * d);
				logoutBtn.setBackground(logoutBg);
				
				android.widget.LinearLayout.LayoutParams cancelParams =
				new android.widget.LinearLayout.LayoutParams(
				0, (int)(50 * d), 1f
				);
				
				cancelParams.rightMargin = (int)(6 * d);
				
				android.widget.LinearLayout.LayoutParams logoutParams =
				new android.widget.LinearLayout.LayoutParams(
				0, (int)(50 * d), 1f
				);
				
				logoutParams.leftMargin = (int)(6 * d);
				
				buttonsRow.addView(cancelBtn, cancelParams);
				buttonsRow.addView(logoutBtn, logoutParams);
				
				logoutBox.addView(
				buttonsRow,
				new android.widget.LinearLayout.LayoutParams(-1, -2)
				);
				
				try {
					android.graphics.Typeface font =
					CigramTypefaces.get(
					getAssets(),
					CigramTypefaces.APP_FONT
					);
					
					title.setTypeface(font);
					message.setTypeface(font);
					cancelBtn.setTypeface(font);
					logoutBtn.setTypeface(font);
				} catch (Exception ignored) {
				}
				
				cancelBtn.setOnClickListener(
				new android.view.View.OnClickListener() {
					@Override
					public void onClick(android.view.View view) {
						logoutDialog.dismiss();
					}
				}
				);
				
				logoutBtn.setOnClickListener(
				new android.view.View.OnClickListener() {
					@Override
					public void onClick(android.view.View view) {
						logoutBtn.setEnabled(false);
						cancelBtn.setEnabled(false);
						logoutBtn.setText("جاري تسجيل الخروج...");
						
						new Thread(new Runnable() {
							@Override
							public void run() {
								java.net.HttpURLConnection con = null;
								
								try {
									java.net.URL url = new java.net.URL(
									"https://cigram-auth-api.wwq-mixtv.workers.dev/logout"
									);
									
									con = (java.net.HttpURLConnection)
									url.openConnection();
									
									con.setRequestMethod("POST");
									con.setConnectTimeout(15000);
									con.setReadTimeout(15000);
									con.setDoOutput(true);
									con.setUseCaches(false);
									
									con.setRequestProperty(
									"Authorization",
									"Bearer " + token
									);
									
									con.setRequestProperty(
									"Content-Type",
									"application/json; charset=UTF-8"
									);
									
									con.setRequestProperty(
									"Accept",
									"application/json"
									);
									
									java.io.OutputStream out =
									con.getOutputStream();
									
									try {
										out.write("{}".getBytes("UTF-8"));
										out.flush();
									} finally {
										out.close();
									}
									
									int responseCode = con.getResponseCode();
									
									java.io.InputStream stream =
									responseCode >= 200 &&
									responseCode < 300
									? con.getInputStream()
									: con.getErrorStream();
									
									StringBuilder responseText =
									new StringBuilder();
									
									if (stream != null) {
										java.io.BufferedReader reader =
										new java.io.BufferedReader(
										new java.io.InputStreamReader(
										stream,
										"UTF-8"
										)
										);
										
										try {
											String line;
											
											while (
											(line = reader.readLine()) != null
											) {
												responseText.append(line);
												
												if (responseText.length() > 100000) {
													throw new java.io.IOException(
													"رد الخادم كبير جداً"
													);
												}
											}
										} finally {
											reader.close();
										}
									}
									
									boolean apiSuccess =
									responseCode >= 200 &&
									responseCode < 300;
									
									String apiMessage =
									"تعذر تسجيل الخروج";
									
									if (responseText.length() > 0) {
										try {
											org.json.JSONObject json =
											new org.json.JSONObject(
											responseText.toString()
											);
											
											if (json.has("success")) {
												apiSuccess =
												apiSuccess &&
												json.optBoolean(
												"success",
												false
												);
											}
											
											apiMessage =
											json.optString(
											"message",
											apiMessage
											);
										} catch (Exception ignored) {
										}
									}
									
									final boolean success = apiSuccess;
									final String finalMessage = apiMessage;
									
									runOnUiThread(new Runnable() {
										@Override
										public void run() {
											String activeToken =
											auth.getString("token", "");
											
											if (
											activeToken == null ||
											!token.equals(activeToken)
											) {
												logoutDialog.dismiss();
												return;
											}
											
											if (success) {
												try {
													java.io.File localAvatar =
													new java.io.File(
													getFilesDir(),
													"cigram_profile_avatar.jpg"
													);
													
													if (localAvatar.exists()) {
														localAvatar.delete();
													}
												} catch (Exception ignored) {
												}
												
												CigramSession.forget(getApplicationContext());
												auth.edit().clear().apply();
												logoutDialog.dismiss();
												
												CigramUtil.showMessage(
												getApplicationContext(),
												"تم تسجيل الخروج"
												);
												
												android.content.Intent intent =
												new android.content.Intent(
												SettingsActivity.this,
												AccountActivity.class
												);
												
												intent.addFlags(
												android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP |
												android.content.Intent.FLAG_ACTIVITY_NEW_TASK |
												android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
												);
												
												startActivity(intent);
												finish();
											} else {
												logoutBtn.setEnabled(true);
												cancelBtn.setEnabled(true);
												logoutBtn.setText("تسجيل الخروج");
												
												CigramUtil.showMessage(
												getApplicationContext(),
												finalMessage
												);
											}
										}
									});
									
								} catch (Exception error) {
									runOnUiThread(new Runnable() {
										@Override
										public void run() {
											String activeToken =
											auth.getString("token", "");
											
											if (
											activeToken == null ||
											!token.equals(activeToken)
											) {
												logoutDialog.dismiss();
												return;
											}
											
											logoutBtn.setEnabled(true);
											cancelBtn.setEnabled(true);
											logoutBtn.setText("تسجيل الخروج");
											
											CigramUtil.showMessage(
											getApplicationContext(),
											"تعذر الاتصال بالخادم"
											);
										}
									});
									
								} finally {
									if (con != null) {
										con.disconnect();
									}
								}
							}
						}, "cigram-logout").start();
					}
				}
				);
				
				logoutDialog.setContentView(logoutBox);
				logoutDialog.setCancelable(true);
				logoutDialog.show();
				
				if (logoutDialog.getWindow() != null) {
					logoutDialog.getWindow().setBackgroundDrawable(
					new android.graphics.drawable.ColorDrawable(
					android.graphics.Color.TRANSPARENT
					)
					);
					
					logoutDialog.getWindow().setLayout(
					(int)(
					getResources()
					.getDisplayMetrics()
					.widthPixels * 0.92f
					),
					android.view.WindowManager.LayoutParams.WRAP_CONTENT
					);
				}
			}
		});
		
		binding.textviewSettingsDeleteAccount.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				// CIGRAM v2.5: full delete-account flow (password confirmation, server call, complete local wipe).
				CigramDeleteAccount.show(SettingsActivity.this);
			}
		});
	}
	
	private void initializeLogic() {
		{
			final float settingsDensity =
			getResources().getDisplayMetrics().density;
			
			final android.view.View settingsRoot =
			findViewById(R.id.linear_settings_root);
			final android.view.View settingsToolbar =
			findViewById(R.id.linear_settings_toolbar);
			final android.widget.TextView settingsTitle =
			(android.widget.TextView) findViewById(
			R.id.textview_settings_title
			);
			final android.widget.TextView accountSection =
			(android.widget.TextView) findViewById(
			R.id.textview_account_section
			);
			final android.widget.TextView dangerSection =
			(android.widget.TextView) findViewById(
			R.id.textview_danger_section
			);
			
			final android.view.View accountCard =
			findViewById(R.id.linear_settings_account_card);
			final android.view.View editRow =
			findViewById(R.id.linear_settings_edit_profile);
			final android.view.View logoutRow =
			findViewById(R.id.linear_settings_logout);
			final android.view.View dangerRow =
			findViewById(R.id.linear2);
			
			final android.widget.TextView editProfileText =
			(android.widget.TextView) findViewById(
			R.id.textview_settings_edit_profile
			);
			final android.widget.TextView logoutText =
			(android.widget.TextView) findViewById(
			R.id.textview_settings_logout
			);
			final android.widget.TextView deleteAvatarText =
			(android.widget.TextView) findViewById(
			R.id.textview_settings_delete_avatar
			);
			final android.widget.TextView deleteAccountText =
			(android.widget.TextView) findViewById(
			R.id.textview_settings_delete_account
			);
			
			settingsRoot.setBackgroundColor(
			android.graphics.Color.parseColor("#0E0E10")
			);
			settingsToolbar.setBackgroundColor(
			android.graphics.Color.parseColor("#1A1A1D")
			);
			
			android.graphics.Typeface settingsFont = null;
			try {
				settingsFont =
				CigramTypefaces.get(
				getAssets(),
				CigramTypefaces.APP_FONT
				);
			} catch (Exception ignored) {}
			
			android.widget.TextView[] settingsTexts = {
				settingsTitle,
				accountSection,
				dangerSection,
				editProfileText,
				logoutText,
				deleteAvatarText,
				deleteAccountText
			};
			
			for (
			android.widget.TextView text :
			settingsTexts
			) {
				if (text == null) continue;
				text.setTextColor(0xFFFFFFFF);
				if (settingsFont != null) {
					text.setTypeface(settingsFont);
				}
			}
			
			settingsTitle.setTextSize(20);
			accountSection.setTextSize(19);
			dangerSection.setTextSize(19);
			editProfileText.setTextSize(16);
			logoutText.setTextSize(16);
			deleteAvatarText.setTextSize(15);
			
			dangerSection.setTextColor(0xFFFF7A76);
			deleteAccountText.setTextColor(0xFFFF5C57);
			deleteAccountText.setTextSize(16);
			
			class CigramSettingsShape {
				android.graphics.drawable.GradientDrawable bg(
				int color,
				float radius,
				int strokeColor
				) {
					android.graphics.drawable.GradientDrawable drawable =
					new android.graphics.drawable.GradientDrawable();
					drawable.setColor(color);
					drawable.setCornerRadius(
					radius * settingsDensity
					);
					if (
					android.graphics.Color.alpha(strokeColor) > 0
					) {
						drawable.setStroke(
						Math.max(
						1,
						(int) settingsDensity
						),
						strokeColor
						);
					}
					return drawable;
				}
				
				void card(
				android.view.View view,
				int color,
				float radius,
				int strokeColor
				) {
					if (view == null) return;
					view.setBackground(
					bg(
					color,
					radius,
					strokeColor
					)
					);
					view.setPadding(
					(int) (18 * settingsDensity),
					(int) (12 * settingsDensity),
					(int) (18 * settingsDensity),
					(int) (12 * settingsDensity)
					);
				}
				
				void margin(
				android.view.View view,
				int topDp,
				int bottomDp
				) {
					if (
					view == null
					|| !(
					view.getLayoutParams()
					instanceof
					android.widget.LinearLayout.LayoutParams
					)
					) {
						return;
					}
					android.widget.LinearLayout.LayoutParams lp =
					(android.widget.LinearLayout.LayoutParams)
					view.getLayoutParams();
					lp.setMargins(
					(int) (14 * settingsDensity),
					(int) (topDp * settingsDensity),
					(int) (14 * settingsDensity),
					(int) (bottomDp * settingsDensity)
					);
					view.setLayoutParams(lp);
				}
			}
			
			CigramSettingsShape settingsShape =
			new CigramSettingsShape();
			
			settingsShape.card(
			accountCard,
			0xFF1C1C20,
			20,
			0x22FFFFFF
			);
			settingsShape.margin(
			accountCard,
			8,
			8
			);
			
			settingsShape.card(
			logoutRow,
			0xFF1C1C20,
			20,
			0x1AFFFFFF
			);
			settingsShape.margin(
			logoutRow,
			8,
			16
			);
			
			settingsShape.card(
			dangerRow,
			0x26FF4D47,
			20,
			0x66FF5C57
			);
			settingsShape.margin(
			dangerRow,
			8,
			18
			);
			
			if (editRow != null) {
				editRow.setPadding(
				(int) (8 * settingsDensity),
				(int) (10 * settingsDensity),
				(int) (8 * settingsDensity),
				(int) (10 * settingsDensity)
				);
				editRow.setOnClickListener(
				new android.view.View.OnClickListener() {
					@Override
					public void onClick(
					android.view.View view
					) {
						editProfileText.performClick();
					}
				}
				);
			}
			
			if (logoutRow != null) {
				logoutRow.setOnClickListener(
				new android.view.View.OnClickListener() {
					@Override
					public void onClick(
					android.view.View view
					) {
						logoutText.performClick();
					}
				}
				);
			}
			
			if (dangerRow != null) {
				dangerRow.setOnClickListener(
				new android.view.View.OnClickListener() {
					@Override
					public void onClick(
					android.view.View view
					) {
						deleteAccountText.performClick();
					}
				}
				);
			}
			
			android.view.View deleteAvatarRow =
			findViewById(
			R.id.linear_settings_delete_avatar
			);
			if (deleteAvatarRow != null) {
				deleteAvatarRow.setVisibility(
				android.view.View.GONE
				);
			}
		}
		
		// CIGRAM SETTINGS: equal action-row heights and unrestricted text lines.
		{
			final float cgRowDensity =
			getResources().getDisplayMetrics().density;
			final int cgRowHeight =
			Math.round(58f * cgRowDensity);
			
			final android.view.View cgEditCard =
			findViewById(R.id.linear_settings_account_card);
			final android.view.View cgEditRow =
			findViewById(R.id.linear_settings_edit_profile);
			final android.view.View cgLogoutRow =
			findViewById(R.id.linear_settings_logout);
			final android.view.View cgDeleteRow =
			findViewById(R.id.linear2);
			
			android.view.View[] cgRows = {
				cgEditCard,
				cgLogoutRow,
				cgDeleteRow
			};
			
			for (android.view.View cgRow : cgRows) {
				if (cgRow == null || cgRow.getLayoutParams() == null) {
					continue;
				}
				android.view.ViewGroup.LayoutParams cgLp =
				cgRow.getLayoutParams();
				cgLp.height = cgRowHeight;
				cgRow.setLayoutParams(cgLp);
				cgRow.setMinimumHeight(cgRowHeight);
			}
			
			if (cgEditCard != null) {
				cgEditCard.setPadding(0, 0, 0, 0);
			}
			
			if (cgEditRow != null) {
				android.view.ViewGroup.LayoutParams cgEditLp =
				cgEditRow.getLayoutParams();
				if (cgEditLp != null) {
					cgEditLp.height = cgRowHeight;
					cgEditRow.setLayoutParams(cgEditLp);
				}
				cgEditRow.setPadding(0, 0, 0, 0);
			}
			
			android.widget.TextView[] cgActionTexts = {
				(android.widget.TextView) findViewById(
				R.id.textview_settings_edit_profile
				),
				(android.widget.TextView) findViewById(
				R.id.textview_settings_logout
				),
				(android.widget.TextView) findViewById(
				R.id.textview_settings_delete_account
				)
			};
			
			for (android.widget.TextView cgText : cgActionTexts) {
				if (cgText == null) continue;
				cgText.setSingleLine(false);
				cgText.setMaxLines(Integer.MAX_VALUE);
				cgText.setEllipsize(null);
				cgText.setGravity(
				android.view.Gravity.RIGHT
				| android.view.Gravity.CENTER_VERTICAL
				);
			}
		}
		
		// CIGRAM: reliable profile-image picker using Android's document provider.
		// GetContent grants read access to the selected item, so no broad storage
		// permission is needed on Android 6 through Android 16.
		{
			final float avatarDp =
			getResources().getDisplayMetrics().density;
			
			final android.widget.LinearLayout avatarLayout =
			(android.widget.LinearLayout) findViewById(
			R.id.linear3
			);
			
			final android.widget.ImageView profileAvatar =
			(android.widget.ImageView) findViewById(
			R.id.circleimageview_profile_avatar
			);
			
			final android.graphics.Typeface avatarFont;
			android.graphics.Typeface avatarFontTemp = null;
			try {
				avatarFontTemp =
				CigramTypefaces.get(
				getAssets(),
				CigramTypefaces.APP_FONT
				);
			} catch (Exception ignored) {}
			avatarFont = avatarFontTemp;
			
			if (
			avatarLayout != null
			&& profileAvatar != null
			) {
				android.view.ViewParent oldParent =
				profileAvatar.getParent();
				
				if (
				oldParent
				instanceof android.view.ViewGroup
				) {
					((android.view.ViewGroup) oldParent)
					.removeView(profileAvatar);
				}
				
				avatarLayout.removeAllViews();
				avatarLayout.setOrientation(
				android.widget.LinearLayout.VERTICAL
				);
				avatarLayout.setGravity(
				android.view.Gravity.CENTER
				);
				avatarLayout.setLayoutDirection(
				android.view.View.LAYOUT_DIRECTION_RTL
				);
				avatarLayout.setPadding(
				(int) (14 * avatarDp),
				(int) (16 * avatarDp),
				(int) (14 * avatarDp),
				(int) (14 * avatarDp)
				);
				
				android.graphics.drawable.GradientDrawable avatarCardBg =
				new android.graphics.drawable.GradientDrawable();
				avatarCardBg.setColor(0xFF18181B);
				avatarCardBg.setCornerRadius(
				24 * avatarDp
				);
				avatarCardBg.setStroke(
				Math.max(1, (int) avatarDp),
				0x26FFFFFF
				);
				avatarLayout.setBackground(
				avatarCardBg
				);
				
				android.view.ViewGroup.LayoutParams rowParams =
				avatarLayout.getLayoutParams();
				
				if (rowParams != null) {
					rowParams.width = -1;
					rowParams.height =
					(int) (184 * avatarDp);
					avatarLayout.setLayoutParams(
					rowParams
					);
				}
				
				android.widget.LinearLayout.LayoutParams avatarParams =
				new android.widget.LinearLayout.LayoutParams(
				(int) (104 * avatarDp),
				(int) (104 * avatarDp)
				);
				avatarParams.gravity =
				android.view.Gravity.CENTER;
				avatarParams.bottomMargin =
				(int) (10 * avatarDp);
				
				profileAvatar.setLayoutParams(
				avatarParams
				);
				profileAvatar.setScaleType(
				android.widget.ImageView.ScaleType.CENTER_CROP
				);
				profileAvatar.setClickable(true);
				profileAvatar.setFocusable(true);
				profileAvatar.setLongClickable(false);
				
				try {
					java.lang.reflect.Method m =
					profileAvatar.getClass()
					.getMethod(
					"setBorderWidth",
					int.class
					);
					m.invoke(
					profileAvatar,
					0
					);
				} catch (Exception ignored) {}
				
				avatarLayout.addView(
				profileAvatar,
				avatarParams
				);
				
				final android.widget.TextView changeAvatar =
				new android.widget.TextView(
				SettingsActivity.this
				);
				changeAvatar.setText(
				"تغيير الصورة"
				);
				changeAvatar.setTextColor(
				0xFFFFFFFF
				);
				changeAvatar.setTextSize(15);
				changeAvatar.setSingleLine(false);
				changeAvatar.setMaxLines(Integer.MAX_VALUE);
				if (avatarFont != null) {
					changeAvatar.setTypeface(
					avatarFont,
					android.graphics.Typeface.BOLD
					);
				}
				changeAvatar.setGravity(
				android.view.Gravity.CENTER
				);
				changeAvatar.setPadding(
				(int) (18 * avatarDp),
				0,
				(int) (18 * avatarDp),
				0
				);
				
				android.graphics.drawable.GradientDrawable changeBg =
				new android.graphics.drawable.GradientDrawable();
				changeBg.setColor(0xFF1C1C20);
				changeBg.setCornerRadius(
				20 * avatarDp
				);
				changeBg.setStroke(
				Math.max(1, (int) avatarDp),
				0x2AFFFFFF
				);
				changeAvatar.setBackground(
				changeBg
				);
				
				android.widget.LinearLayout.LayoutParams changeLp =
				new android.widget.LinearLayout.LayoutParams(
				-2,
				(int) (40 * avatarDp)
				);
				changeLp.gravity =
				android.view.Gravity.CENTER;
				avatarLayout.addView(
				changeAvatar,
				changeLp
				);
				
				final android.content.SharedPreferences avatarPrefs =
				SettingsActivity.this.getSharedPreferences(
				"auth_session",
				android.content.Context.MODE_PRIVATE
				);
				
				String avatarLocalPath =
				avatarPrefs.getString(
				"avatar_local_path",
				""
				);
				
				if (
				avatarLocalPath == null
				|| avatarLocalPath.trim().isEmpty()
				) {
					java.io.File fallbackAvatar =
					new java.io.File(
					SettingsActivity.this.getFilesDir(),
					"cigram_profile_avatar.jpg"
					);
					if (fallbackAvatar.exists()) {
						avatarLocalPath =
						fallbackAvatar.getAbsolutePath();
					}
				}
				
				if (
				avatarLocalPath != null
				&& !avatarLocalPath.trim().isEmpty()
				) {
					try {
						android.graphics.Bitmap currentAvatar =
						android.graphics.BitmapFactory.decodeFile(
						avatarLocalPath.trim()
						);
						if (currentAvatar != null) {
							profileAvatar.setImageBitmap(
							currentAvatar
							);
						}
					} catch (Exception ignored) {}
				}
				
				final androidx.activity.result.ActivityResultLauncher<String>
				avatarLauncher =
				SettingsActivity.this.registerForActivityResult(
				new androidx.activity.result.contract
				.ActivityResultContracts.GetContent(),
				new androidx.activity.result
				.ActivityResultCallback<android.net.Uri>() {
					@Override
					public void onActivityResult(
					android.net.Uri uri
					) {
						if (uri == null) {
							return;
						}
						
						java.io.File temp = null;
						java.io.InputStream input = null;
						java.io.FileOutputStream tempOut = null;
						java.io.FileOutputStream avatarOut = null;
						
						try {
							temp =
							new java.io.File(
							SettingsActivity.this.getCacheDir(),
							"cigram_avatar_pick.tmp"
							);
							
							input =
							SettingsActivity.this
							.getContentResolver()
							.openInputStream(uri);
							
							if (input == null) {
								throw new java.io.IOException(
								"Could not open selected image"
								);
							}
							
							tempOut =
							new java.io.FileOutputStream(
							temp,
							false
							);
							
							byte[] buffer =
							new byte[16384];
							int count;
							long total = 0L;
							
							while (
							(count = input.read(buffer))
							!= -1
							) {
								total += count;
								
								if (
								total
								> 25L * 1024L * 1024L
								) {
									throw new java.io.IOException(
									"Image too large"
									);
								}
								
								tempOut.write(
								buffer,
								0,
								count
								);
							}
							
							tempOut.flush();
							
							android.graphics.BitmapFactory.Options bounds =
							new android.graphics.BitmapFactory.Options();
							bounds.inJustDecodeBounds =
							true;
							
							android.graphics.BitmapFactory.decodeFile(
							temp.getAbsolutePath(),
							bounds
							);
							
							if (
							bounds.outWidth <= 0
							|| bounds.outHeight <= 0
							) {
								throw new java.io.IOException(
								"Unsupported image"
								);
							}
							
							int sample = 1;
							int maxSide =
							Math.max(
							bounds.outWidth,
							bounds.outHeight
							);
							
							while (
							maxSide / sample > 1600
							) {
								sample *= 2;
							}
							
							android.graphics.BitmapFactory.Options decode =
							new android.graphics.BitmapFactory.Options();
							decode.inSampleSize =
							Math.max(1, sample);
							decode.inPreferredConfig =
							android.graphics.Bitmap.Config.ARGB_8888;
							
							android.graphics.Bitmap selectedBitmap =
							android.graphics.BitmapFactory.decodeFile(
							temp.getAbsolutePath(),
							decode
							);
							
							if (
							selectedBitmap == null
							) {
								throw new java.io.IOException(
								"Unsupported image"
								);
							}
							
							java.io.File avatarFile =
							new java.io.File(
							SettingsActivity.this.getFilesDir(),
							"cigram_profile_avatar.jpg"
							);
							
							avatarOut =
							new java.io.FileOutputStream(
							avatarFile,
							false
							);
							
							boolean saved =
							selectedBitmap.compress(
							android.graphics.Bitmap
							.CompressFormat.JPEG,
							92,
							avatarOut
							);
							
							avatarOut.flush();
							
							if (!saved) {
								throw new java.io.IOException(
								"Could not save avatar"
								);
							}
							
							avatarPrefs.edit()
							.putString(
							"avatar_local_path",
							avatarFile
							.getAbsolutePath()
							)
							.apply();
							
							profileAvatar.setImageBitmap(
							selectedBitmap
							);
							
							CigramSilent.toast(
							SettingsActivity.this,
							"تم تحديث الصورة", android.widget.Toast.LENGTH_SHORT);
							
						} catch (Exception error) {
							android.util.Log.e(
							"CigramAvatar",
							"Could not update avatar",
							error
							);
							
							CigramSilent.toast(
							SettingsActivity.this,
							"تعذر تحديث الصورة", android.widget.Toast.LENGTH_SHORT);
							
						} finally {
							try {
								if (input != null) {
									input.close();
								}
							} catch (Exception ignored) {}
							
							try {
								if (tempOut != null) {
									tempOut.close();
								}
							} catch (Exception ignored) {}
							
							try {
								if (avatarOut != null) {
									avatarOut.close();
								}
							} catch (Exception ignored) {}
							
							try {
								if (
								temp != null
								&& temp.exists()
								) {
									temp.delete();
								}
							} catch (Exception ignored) {}
						}
					}
				}
				);
				
				final android.view.View.OnClickListener
				pickAvatarClick =
				new android.view.View.OnClickListener() {
					@Override
					public void onClick(
					android.view.View view
					) {
						try {
							avatarLauncher.launch(
							"image/*"
							);
						} catch (Exception error) {
							android.util.Log.e(
							"CigramAvatar",
							"Could not open image picker",
							error
							);
							
							CigramSilent.toast(
							SettingsActivity.this,
							"تعذر فتح معرض الصور", android.widget.Toast.LENGTH_SHORT);
						}
					}
				};
				
				profileAvatar.setOnClickListener(
				pickAvatarClick
				);
				changeAvatar.setOnClickListener(
				pickAvatarClick
				);
			}
			
			android.view.View deleteAvatarRow =
			findViewById(
			R.id.linear_settings_delete_avatar
			);
			if (deleteAvatarRow != null) {
				deleteAvatarRow.setVisibility(
				android.view.View.GONE
				);
			}
		}
		{
			final android.graphics.Typeface appFontGlobal =
			CigramTypefaces.get(
			getAssets(),
			CigramTypefaces.APP_FONT
			);
			
			java.util.ArrayList<android.view.View> fontViews =
			new java.util.ArrayList<android.view.View>();
			
			fontViews.add(
			getWindow().getDecorView()
			);
			
			for (int fontIndex = 0;
			fontIndex < fontViews.size();
			fontIndex++) {
				
				android.view.View currentView =
				fontViews.get(fontIndex);
				
				// TextView + Button + EditText
				// لأن Button و EditText أساساً يرثون من TextView
				if (currentView instanceof android.widget.TextView) {
					
					((android.widget.TextView) currentView)
					.setTypeface(appFontGlobal);
				}
				
				// الدخول لكل العناصر الموجودة داخل Layout
				if (currentView instanceof android.view.ViewGroup) {
					
					android.view.ViewGroup group =
					(android.view.ViewGroup) currentView;
					
					for (int childIndex = 0;
					childIndex < group.getChildCount();
					childIndex++) {
						
						fontViews.add(
						group.getChildAt(childIndex)
						);
					}
				}
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
		
		
		// CIGRAM v2.5: new settings page (profile card + grouped sections), built on top of the views above.
		try {
			CigramSettings.bind(SettingsActivity.this);
			CigramSettings.runRequestedAction(SettingsActivity.this);   // the Account page's «تعديل الملف الشخصي» / «تسجيل الخروج»
		} catch (Throwable cigramSettingsError) {
			android.util.Log.w("CigramSettings", "bind failed", cigramSettingsError);
		}
		
	}
	
}
