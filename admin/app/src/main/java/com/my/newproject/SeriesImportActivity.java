package com.my.newproject;

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
import androidx.annotation.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.my.newproject.databinding.*;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;

public class SeriesImportActivity extends AppCompatActivity {
	
	private SeriesImportBinding binding;
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		binding = SeriesImportBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		initialize(_savedInstanceState);
		initializeLogic();
	}
	
	private void initialize(Bundle _savedInstanceState) {
	}
	
	private void initializeLogic() {
		
		// CIGRAM ADMIN — Full Series Import
		// TMDB link + R2 slug -> Preview -> review seasons/episodes -> Add.
		// Backend routes:
		// POST /preview-series  (does not save)
		// POST /add-series      (saves idempotently)
		
		{
			final android.widget.LinearLayout ROOT =
			(android.widget.LinearLayout) findViewById(R.id.linear_series_import_root);
			
			final String API_BASE =
			CgCfg.API;
			
			final String ADMIN_TOKEN =
			CgCfg.TOKEN;
			
			final float D = getResources().getDisplayMetrics().density;
			
			final int PAGE = android.graphics.Color.parseColor("#0B0E14");
			final int CARD = android.graphics.Color.parseColor("#141922");
			final int CARD_2 = android.graphics.Color.parseColor("#10151D");
			final int STROKE = android.graphics.Color.parseColor("#263042");
			final int TEXT = android.graphics.Color.parseColor("#F1F4FA");
			final int MUTED = android.graphics.Color.parseColor("#93A0B8");
			final int ACCENT = android.graphics.Color.parseColor("#4F6BFF");
			final int GOOD = android.graphics.Color.parseColor("#35D6A0");
			final int BAD = android.graphics.Color.parseColor("#FF6F7D");
			
			getWindow().setStatusBarColor(PAGE);
			getWindow().setNavigationBarColor(PAGE);
			
			ROOT.setBackgroundColor(PAGE);
			ROOT.setOrientation(android.widget.LinearLayout.VERTICAL);
			ROOT.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
			ROOT.removeAllViews();
			
			final android.graphics.Typeface[] appFont =
			new android.graphics.Typeface[]{null};
			
			try {
				appFont[0] = android.graphics.Typeface.createFromAsset(
				getAssets(),
				"fonts/Cairo-Regular.ttf"
				);
			} catch (Exception ignored) {}
			
			class Ui {
				android.graphics.drawable.GradientDrawable bg(
				int color,
				float radiusDp,
				int strokeColor,
				float strokeDp
				) {
					android.graphics.drawable.GradientDrawable g =
					new android.graphics.drawable.GradientDrawable();
					g.setColor(color);
					g.setCornerRadius(radiusDp * D);
					if (strokeDp > 0f) {
						g.setStroke(
						Math.max(1, (int)(strokeDp * D)),
						strokeColor
						);
					}
					return g;
				}
				
				void font(android.widget.TextView v) {
					if (appFont[0] != null) v.setTypeface(appFont[0]);
				}
				
				android.widget.TextView text(
				String value,
				float size,
				int color,
				boolean bold
				) {
					android.widget.TextView v =
					new android.widget.TextView(SeriesImportActivity.this);
					v.setText(value);
					v.setTextSize(size);
					v.setTextColor(color);
					v.setGravity(
					android.view.Gravity.RIGHT |
					android.view.Gravity.CENTER_VERTICAL
					);
					v.setMaxLines(Integer.MAX_VALUE);
					font(v);
					if (bold) {
						v.setTypeface(
						v.getTypeface(),
						android.graphics.Typeface.BOLD
						);
					}
					return v;
				}
				
				android.widget.EditText edit(String hint) {
					android.widget.EditText e =
					new android.widget.EditText(SeriesImportActivity.this);
					e.setHint(hint);
					e.setHintTextColor(MUTED);
					e.setTextColor(TEXT);
					e.setTextSize(14);
					e.setSingleLine(true);
					e.setGravity(
					android.view.Gravity.RIGHT |
					android.view.Gravity.CENTER_VERTICAL
					);
					e.setPadding(
					(int)(14 * D),
					0,
					(int)(14 * D),
					0
					);
					e.setBackground(bg(CARD_2, 13f, STROKE, 1f));
					font(e);
					return e;
				}
				
				android.widget.TextView button(
				String value,
				boolean primary
				) {
					android.widget.TextView b =
					new android.widget.TextView(SeriesImportActivity.this);
					b.setText(value);
					b.setTextColor(TEXT);
					b.setTextSize(15);
					b.setGravity(android.view.Gravity.CENTER);
					b.setPadding(
					(int)(12 * D),
					0,
					(int)(12 * D),
					0
					);
					b.setBackground(
					bg(
					primary ? ACCENT : CARD_2,
					13f,
					primary ? ACCENT : STROKE,
					1f
					)
					);
					b.setClickable(true);
					b.setFocusable(true);
					font(b);
					b.setTypeface(
					b.getTypeface(),
					android.graphics.Typeface.BOLD
					);
					return b;
				}
				
				android.widget.LinearLayout card() {
					android.widget.LinearLayout c =
					new android.widget.LinearLayout(SeriesImportActivity.this);
					c.setOrientation(android.widget.LinearLayout.VERTICAL);
					c.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
					c.setPadding(
					(int)(14 * D),
					(int)(14 * D),
					(int)(14 * D),
					(int)(14 * D)
					);
					c.setBackground(bg(CARD, 18f, STROKE, 1f));
					c.setElevation(2 * D);
					return c;
				}
				
				android.widget.LinearLayout.LayoutParams full(
				int heightDp,
				int bottomDp
				) {
					android.widget.LinearLayout.LayoutParams lp =
					new android.widget.LinearLayout.LayoutParams(
					android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
					heightDp < 0
					? android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
					: (int)(heightDp * D)
					);
					lp.bottomMargin = (int)(bottomDp * D);
					return lp;
				}
			}
			
			final Ui ui = new Ui();
			
			final android.widget.LinearLayout top =
			new android.widget.LinearLayout(SeriesImportActivity.this);
			top.setOrientation(android.widget.LinearLayout.HORIZONTAL);
			top.setGravity(android.view.Gravity.CENTER_VERTICAL);
			top.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
			top.setPadding(
			(int)(16 * D),
			(int)(10 * D),
			(int)(16 * D),
			(int)(10 * D)
			);
			
			final android.widget.TextView back =
			ui.button("رجوع", false);
			
			android.widget.LinearLayout.LayoutParams backLp =
			new android.widget.LinearLayout.LayoutParams(
			(int)(78 * D),
			(int)(46 * D)
			);
			top.addView(back, backLp);
			
			final android.widget.TextView pageTitle =
			ui.text("إضافة مسلسل كامل", 21, TEXT, true);
			
			android.widget.LinearLayout.LayoutParams titleLp =
			new android.widget.LinearLayout.LayoutParams(
			0,
			(int)(52 * D),
			1f
			);
			titleLp.rightMargin = (int)(12 * D);
			top.addView(pageTitle, titleLp);
			
			ROOT.addView(
			top,
			new android.widget.LinearLayout.LayoutParams(
			-1,
			-2
			)
			);
			
			back.setOnClickListener(
			new android.view.View.OnClickListener() {
				@Override
				public void onClick(android.view.View v) {
					finish();
				}
			}
			);
			
			final android.widget.ScrollView scroll =
			new android.widget.ScrollView(SeriesImportActivity.this);
			scroll.setFillViewport(true);
			scroll.setClipToPadding(false);
			
			final android.widget.LinearLayout body =
			new android.widget.LinearLayout(SeriesImportActivity.this);
			body.setOrientation(android.widget.LinearLayout.VERTICAL);
			body.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
			body.setPadding(
			(int)(16 * D),
			(int)(8 * D),
			(int)(16 * D),
			(int)(28 * D)
			);
			
			scroll.addView(
			body,
			new android.widget.ScrollView.LayoutParams(
			-1,
			-2
			)
			);
			
			ROOT.addView(
			scroll,
			new android.widget.LinearLayout.LayoutParams(
			-1,
			0,
			1f
			)
			);
			
			final android.widget.LinearLayout inputCard = ui.card();
			
			final android.widget.TextView inputTitle =
			ui.text(
			"رابط المسلسل",
			17,
			TEXT,
			true
			);
			inputTitle.setPadding(0, 0, 0, (int)(10 * D));
			inputCard.addView(inputTitle, ui.full(-1, 0));
			
			final android.widget.EditText tmdbInput =
			ui.edit("رابط TMDB للمسلسل، مثال: https://www.themoviedb.org/tv/194583");
			tmdbInput.setInputType(
			android.text.InputType.TYPE_CLASS_TEXT |
			android.text.InputType.TYPE_TEXT_VARIATION_URI
			);
			inputCard.addView(tmdbInput, ui.full(58, 10));
			
			final android.widget.EditText slugInput =
			ui.edit("اسم مجلد R2 (slug)، مثال: the-walking-dead-dead-city");
			slugInput.setInputType(
			android.text.InputType.TYPE_CLASS_TEXT |
			android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
			);
			inputCard.addView(slugInput, ui.full(58, 12));
			
			final android.widget.TextView hint =
			ui.text(
			"الفحص يعرض المواسم والحلقات وبوستر كل موسم قبل الحفظ. لا يضيف المسلسل إلا بعد ضغط زر إضافة المسلسل.",
			12,
			MUTED,
			false
			);
			hint.setPadding(0, 0, 0, (int)(12 * D));
			inputCard.addView(hint, ui.full(-1, 0));
			
			final android.widget.TextView previewButton =
			ui.button("فحص المسلسل", true);
			inputCard.addView(previewButton, ui.full(54, 0));
			
			body.addView(inputCard, ui.full(-1, 14));
			
			final android.widget.TextView status =
			ui.text("", 13, MUTED, false);
			status.setVisibility(android.view.View.GONE);
			status.setPadding(
			(int)(4 * D),
			0,
			(int)(4 * D),
			(int)(10 * D)
			);
			body.addView(status, ui.full(-1, 0));
			
			final android.widget.LinearLayout resultCard = ui.card();
			resultCard.setVisibility(android.view.View.GONE);
			
			final android.widget.LinearLayout hero =
			new android.widget.LinearLayout(SeriesImportActivity.this);
			hero.setOrientation(android.widget.LinearLayout.HORIZONTAL);
			hero.setGravity(android.view.Gravity.TOP);
			hero.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
			
			final android.widget.ImageView seriesPoster =
			new android.widget.ImageView(SeriesImportActivity.this);
			seriesPoster.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
			seriesPoster.setBackground(ui.bg(CARD_2, 12f, STROKE, 1f));
			
			android.widget.LinearLayout.LayoutParams seriesPosterLp =
			new android.widget.LinearLayout.LayoutParams(
			(int)(96 * D),
			(int)(144 * D)
			);
			hero.addView(seriesPoster, seriesPosterLp);
			
			final android.widget.LinearLayout heroText =
			new android.widget.LinearLayout(SeriesImportActivity.this);
			heroText.setOrientation(android.widget.LinearLayout.VERTICAL);
			heroText.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
			heroText.setPadding(
			(int)(14 * D),
			0,
			(int)(14 * D),
			0
			);
			
			final android.widget.TextView seriesTitle =
			ui.text("", 18, TEXT, true);
			final android.widget.TextView seriesMeta =
			ui.text("", 13, MUTED, false);
			final android.widget.TextView seriesOverview =
			ui.text("", 13, MUTED, false);
			
			heroText.addView(seriesTitle, ui.full(-1, 6));
			heroText.addView(seriesMeta, ui.full(-1, 8));
			heroText.addView(seriesOverview, ui.full(-1, 0));
			
			hero.addView(
			heroText,
			new android.widget.LinearLayout.LayoutParams(
			0,
			-2,
			1f
			)
			);
			
			resultCard.addView(hero, ui.full(-1, 16));
			
			final android.widget.TextView seasonsHeader =
			ui.text("المواسم والحلقات", 17, TEXT, true);
			seasonsHeader.setPadding(0, 0, 0, (int)(10 * D));
			resultCard.addView(seasonsHeader, ui.full(-1, 0));
			
			final android.widget.LinearLayout seasonsContainer =
			new android.widget.LinearLayout(SeriesImportActivity.this);
			seasonsContainer.setOrientation(android.widget.LinearLayout.VERTICAL);
			seasonsContainer.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
			resultCard.addView(seasonsContainer, ui.full(-1, 0));
			
			final android.widget.TextView addButton =
			ui.button("إضافة المسلسل", true);
			addButton.setVisibility(android.view.View.GONE);
			resultCard.addView(addButton, ui.full(56, 0));
			
			body.addView(resultCard, ui.full(-1, 0));
			
			final java.util.HashMap<Integer, android.widget.EditText>
			seasonPosterEdits =
			new java.util.HashMap<Integer, android.widget.EditText>();
			
			final java.util.HashMap<Integer, String>
			originalSeasonPosters =
			new java.util.HashMap<Integer, String>();
			
			final org.json.JSONObject[] previewSeries =
			new org.json.JSONObject[]{null};
			
			class Net {
				String read(java.io.InputStream in) throws Exception {
					if (in == null) return "";
					java.io.BufferedReader r =
					new java.io.BufferedReader(
					new java.io.InputStreamReader(in, "UTF-8")
					);
					StringBuilder b = new StringBuilder();
					String line;
					while ((line = r.readLine()) != null) {
						b.append(line);
					}
					r.close();
					return b.toString();
				}
				
				org.json.JSONObject post(
				String route,
				org.json.JSONObject bodyJson
				) throws Exception {
					java.net.HttpURLConnection c = null;
					try {
						c = (java.net.HttpURLConnection)
						new java.net.URL(
						API_BASE + "/" + route
						).openConnection();
						
						c.setRequestMethod("POST");
						c.setConnectTimeout(20000);
						c.setReadTimeout(180000);
						c.setDoOutput(true);
						c.setRequestProperty(
						"Content-Type",
						"application/json; charset=UTF-8"
						);
						c.setRequestProperty(
						"Accept",
						"application/json"
						);
						c.setRequestProperty(
						"Authorization",
						"Bearer " + ADMIN_TOKEN
						);
						c.setRequestProperty(
						"X-Admin-Token",
						ADMIN_TOKEN
						);
						
						byte[] bytes =
						bodyJson.toString().getBytes("UTF-8");
						
						java.io.OutputStream out =
						c.getOutputStream();
						out.write(bytes);
						out.flush();
						out.close();
						
						int code = c.getResponseCode();
						String raw = read(
						code >= 200 && code < 300
						? c.getInputStream()
						: c.getErrorStream()
						);
						
						org.json.JSONObject response =
						raw.trim().length() == 0
						? new org.json.JSONObject()
						: new org.json.JSONObject(raw);
						
						if (code < 200 || code >= 300) {
							String message =
							response.optString(
							"message",
							response.optString(
							"error",
							"HTTP " + code
							)
							);
							throw new Exception(message);
						}
						
						if (!response.optBoolean("ok", true)) {
							throw new Exception(
							response.optString(
							"message",
							response.optString(
							"error",
							"فشل الطلب"
							)
							)
							);
						}
						
						return response;
					} finally {
						if (c != null) c.disconnect();
					}
				}
			}
			
			final Net net = new Net();
			
			class Images {
				void load(
				final String url,
				final android.widget.ImageView view
				) {
					if (
					url == null ||
					!url.startsWith("http")
					) {
						return;
					}
					
					new Thread(
					new Runnable() {
						@Override
						public void run() {
							java.net.HttpURLConnection c = null;
							try {
								c = (java.net.HttpURLConnection)
								new java.net.URL(url)
								.openConnection();
								c.setConnectTimeout(12000);
								c.setReadTimeout(20000);
								c.setInstanceFollowRedirects(true);
								c.connect();
								
								final android.graphics.Bitmap bmp =
								android.graphics.BitmapFactory.decodeStream(
								c.getInputStream()
								);
								
								if (bmp != null) {
									runOnUiThread(
									new Runnable() {
										@Override
										public void run() {
											view.setImageBitmap(bmp);
										}
									}
									);
								}
							} catch (Exception ignored) {
							} finally {
								if (c != null) c.disconnect();
							}
						}
					},
					"cigram-series-image"
					).start();
				}
			}
			
			final Images images = new Images();
			
			class Render {
				String s(org.json.JSONObject o, String key) {
					if (o == null) return "";
					return o.optString(key, "");
				}
				
				void render(final org.json.JSONObject series) {
					previewSeries[0] = series;
					seasonPosterEdits.clear();
					originalSeasonPosters.clear();
					seasonsContainer.removeAllViews();
					
					final String title =
					series.optString("title", "مسلسل");
					final String poster =
					series.optString("poster", "");
					final String overview =
					series.optString("overview", "");
					
					seriesTitle.setText(title);
					
					int year = series.optInt("year", 0);
					double rating = series.optDouble("rating", 0.0);
					int seasonCount = series.optInt("season_count", 0);
					int episodeCount = series.optInt("episode_count", 0);
					int availableCount =
					series.optInt("available_episode_count", 0);
					int missingCount =
					series.optInt("missing_episode_count", 0);
					
					seriesMeta.setText(
					(year > 0 ? year + " • " : "") +
					(rating > 0
					? "التقييم " +
					String.format(
					java.util.Locale.US,
					"%.1f",
					rating
					) +
					" • "
					: "") +
					seasonCount + " موسم • " +
					episodeCount + " حلقة\n" +
					"المتوفر " + availableCount +
					" • الناقص " + missingCount
					);
					
					seriesOverview.setText(
					overview.length() == 0
					? "لا يوجد وصف."
					: overview
					);
					
					seriesPoster.setImageDrawable(
					ui.bg(CARD_2, 12f, STROKE, 1f)
					);
					images.load(poster, seriesPoster);
					
					org.json.JSONArray seasons =
					series.optJSONArray("seasons");
					
					if (seasons == null) {
						seasons = new org.json.JSONArray();
					}
					
					for (int i = 0; i < seasons.length(); i++) {
						final org.json.JSONObject season =
						seasons.optJSONObject(i);
						
						if (season == null) continue;
						
						final int seasonNumber =
						season.optInt("season", 0);
						
						if (seasonNumber <= 0) continue;
						
						final android.widget.LinearLayout seasonCard =
						ui.card();
						
						android.widget.LinearLayout.LayoutParams seasonCardLp =
						ui.full(-1, 12);
						
						final android.widget.LinearLayout seasonTop =
						new android.widget.LinearLayout(
						SeriesImportActivity.this
						);
						seasonTop.setOrientation(
						android.widget.LinearLayout.HORIZONTAL
						);
						seasonTop.setGravity(
						android.view.Gravity.TOP
						);
						seasonTop.setLayoutDirection(
						android.view.View.LAYOUT_DIRECTION_RTL
						);
						
						final android.widget.ImageView seasonPoster =
						new android.widget.ImageView(
						SeriesImportActivity.this
						);
						seasonPoster.setScaleType(
						android.widget.ImageView.ScaleType.CENTER_CROP
						);
						seasonPoster.setBackground(
						ui.bg(CARD_2, 11f, STROKE, 1f)
						);
						
						seasonTop.addView(
						seasonPoster,
						new android.widget.LinearLayout.LayoutParams(
						(int)(82 * D),
						(int)(123 * D)
						)
						);
						
						final android.widget.LinearLayout seasonInfo =
						new android.widget.LinearLayout(
						SeriesImportActivity.this
						);
						seasonInfo.setOrientation(
						android.widget.LinearLayout.VERTICAL
						);
						seasonInfo.setLayoutDirection(
						android.view.View.LAYOUT_DIRECTION_RTL
						);
						seasonInfo.setPadding(
						(int)(12 * D),
						0,
						(int)(12 * D),
						0
						);
						
						final org.json.JSONArray episodes =
						season.optJSONArray("episodes");
						
						int total = episodes == null
						? 0
						: episodes.length();
						
						int available = 0;
						if (episodes != null) {
							for (
							int e = 0;
							e < episodes.length();
							e++
							) {
								org.json.JSONObject ep =
								episodes.optJSONObject(e);
								if (
								ep != null &&
								ep.optBoolean("available", false)
								) {
									available++;
								}
							}
						}
						
						final android.widget.TextView seasonTitle =
						ui.text(
						"الموسم " + seasonNumber,
						17,
						TEXT,
						true
						);
						
						final android.widget.TextView seasonStats =
						ui.text(
						total + " حلقة • " +
						available + " متوفرة • " +
						Math.max(0, total - available) +
						" ناقصة",
						12,
						MUTED,
						false
						);
						
						seasonInfo.addView(
						seasonTitle,
						ui.full(-1, 6)
						);
						seasonInfo.addView(
						seasonStats,
						ui.full(-1, 10)
						);
						
						final android.widget.EditText posterEdit =
						ui.edit(
						"رابط بوستر مخصص للموسم " +
						seasonNumber +
						" (اختياري)"
						);
						
						final String seasonPosterUrl =
						season.optString(
						"season_poster",
						season.optString("poster", "")
						);
						
						if (
						seasonPosterUrl != null &&
						seasonPosterUrl.length() > 0
						) {
							posterEdit.setText(seasonPosterUrl);
							posterEdit.setSelection(
							posterEdit.getText().length()
							);
							images.load(
							seasonPosterUrl,
							seasonPoster
							);
						}
						
						seasonPosterEdits.put(
						seasonNumber,
						posterEdit
						);
						originalSeasonPosters.put(
						seasonNumber,
						seasonPosterUrl == null
						? ""
						: seasonPosterUrl.trim()
						);
						
						seasonInfo.addView(
						posterEdit,
						ui.full(52, 0)
						);
						
						seasonTop.addView(
						seasonInfo,
						new android.widget.LinearLayout.LayoutParams(
						0,
						-2,
						1f
						)
						);
						
						seasonCard.addView(
						seasonTop,
						ui.full(-1, 12)
						);
						
						if (episodes != null) {
							for (
							int e = 0;
							e < episodes.length();
							e++
							) {
								final org.json.JSONObject ep =
								episodes.optJSONObject(e);
								
								if (ep == null) continue;
								
								final int epNumber =
								ep.optInt("ep", e + 1);
								
								final boolean isAvailable =
								ep.optBoolean(
								"available",
								false
								);
								
								final android.widget.LinearLayout epRow =
								new android.widget.LinearLayout(
								SeriesImportActivity.this
								);
								
								epRow.setOrientation(
								android.widget.LinearLayout.HORIZONTAL
								);
								epRow.setGravity(
								android.view.Gravity.CENTER_VERTICAL
								);
								epRow.setLayoutDirection(
								android.view.View.LAYOUT_DIRECTION_RTL
								);
								epRow.setPadding(
								(int)(8 * D),
								(int)(8 * D),
								(int)(8 * D),
								(int)(8 * D)
								);
								epRow.setBackground(
								ui.bg(
								CARD_2,
								12f,
								STROKE,
								1f
								)
								);
								
								final android.widget.ImageView thumb =
								new android.widget.ImageView(
								SeriesImportActivity.this
								);
								
								thumb.setScaleType(
								android.widget.ImageView.ScaleType.CENTER_CROP
								);
								thumb.setBackground(
								ui.bg(
								CARD,
								9f,
								STROKE,
								1f
								)
								);
								
								epRow.addView(
								thumb,
								new android.widget.LinearLayout.LayoutParams(
								(int)(90 * D),
								(int)(56 * D)
								)
								);
								
								String thumbUrl =
								ep.optString("thumb", "");
								
								if (
								thumbUrl == null ||
								thumbUrl.length() == 0
								) {
									thumbUrl = seasonPosterUrl;
								}
								
								images.load(thumbUrl, thumb);
								
								final android.widget.LinearLayout epInfo =
								new android.widget.LinearLayout(
								SeriesImportActivity.this
								);
								epInfo.setOrientation(
								android.widget.LinearLayout.VERTICAL
								);
								epInfo.setLayoutDirection(
								android.view.View.LAYOUT_DIRECTION_RTL
								);
								epInfo.setPadding(
								(int)(10 * D),
								0,
								(int)(10 * D),
								0
								);
								
								String epTitle =
								ep.optString(
								"title",
								"الحلقة " + epNumber
								);
								
								final android.widget.TextView epTitleView =
								ui.text(
								"الحلقة " +
								epNumber +
								" - " +
								epTitle,
								13,
								TEXT,
								true
								);
								
								final android.widget.TextView epState =
								ui.text(
								isAvailable
								? "الفيديو موجود على R2"
								: "الفيديو غير موجود على R2",
								11,
								isAvailable ? GOOD : BAD,
								false
								);
								
								epInfo.addView(
								epTitleView,
								ui.full(-1, 4)
								);
								epInfo.addView(
								epState,
								ui.full(-1, 0)
								);
								
								epRow.addView(
								epInfo,
								new android.widget.LinearLayout.LayoutParams(
								0,
								-2,
								1f
								)
								);
								
								android.widget.LinearLayout.LayoutParams epLp =
								ui.full(-1, 8);
								
								seasonCard.addView(
								epRow,
								epLp
								);
							}
						}
						
						seasonsContainer.addView(
						seasonCard,
						seasonCardLp
						);
					}
					
					resultCard.setVisibility(
					android.view.View.VISIBLE
					);
					addButton.setVisibility(
					android.view.View.VISIBLE
					);
				}
			}
			
			final Render render = new Render();
			
			previewButton.setOnClickListener(
			new android.view.View.OnClickListener() {
				@Override
				public void onClick(android.view.View v) {
					final String tmdb =
					tmdbInput
					.getText()
					.toString()
					.trim();
					
					final String slug =
					slugInput
					.getText()
					.toString()
					.trim();
					
					if (tmdb.length() == 0) {
						tmdbInput.setError(
						"ضع رابط TMDB للمسلسل"
						);
						tmdbInput.requestFocus();
						return;
					}
					
					if (slug.length() == 0) {
						slugInput.setError(
						"اكتب اسم مجلد المسلسل على R2"
						);
						slugInput.requestFocus();
						return;
					}
					
					if (!slug.matches("[A-Za-z0-9._-]+")) {
						slugInput.setError(
						"استخدم أحرف إنجليزية وأرقام و . _ - فقط"
						);
						slugInput.requestFocus();
						return;
					}
					
					previewButton.setEnabled(false);
					previewButton.setAlpha(0.55f);
					previewButton.setText(
					"جاري فحص المسلسل..."
					);
					
					addButton.setVisibility(
					android.view.View.GONE
					);
					resultCard.setVisibility(
					android.view.View.GONE
					);
					
					status.setText(
					"يتم الآن جلب معلومات المسلسل وفحص المواسم والحلقات الموجودة على R2..."
					);
					status.setTextColor(MUTED);
					status.setVisibility(
					android.view.View.VISIBLE
					);
					
					new Thread(
					new Runnable() {
						@Override
						public void run() {
							try {
								org.json.JSONObject bodyJson =
								new org.json.JSONObject();
								
								bodyJson.put(
								"tmdb_url",
								tmdb
								);
								bodyJson.put(
								"slug",
								slug
								);
								
								final org.json.JSONObject response =
								net.post(
								"preview-series",
								bodyJson
								);
								
								final org.json.JSONObject series =
								response.optJSONObject(
								"series"
								);
								
								if (series == null) {
									throw new Exception(
									"الـ Worker لم يرجع بيانات المسلسل."
									);
								}
								
								runOnUiThread(
								new Runnable() {
									@Override
									public void run() {
										previewButton.setEnabled(
										true
										);
										previewButton.setAlpha(
										1f
										);
										previewButton.setText(
										"إعادة الفحص"
										);
										
										status.setText(
										"تم الفحص. راجع المواسم والحلقات ثم اضغط إضافة المسلسل."
										);
										status.setTextColor(
										GOOD
										);
										
										render.render(
										series
										);
									}
								}
								);
								
							} catch (
							final Exception error
							) {
								runOnUiThread(
								new Runnable() {
									@Override
									public void run() {
										previewButton.setEnabled(
										true
										);
										previewButton.setAlpha(
										1f
										);
										previewButton.setText(
										"فحص المسلسل"
										);
										
										status.setText(
										"فشل الفحص: " +
										error.getMessage()
										);
										status.setTextColor(
										BAD
										);
										status.setVisibility(
										android.view.View.VISIBLE
										);
										
										android.widget.Toast
										.makeText(
										SeriesImportActivity.this,
										"فشل الفحص: " +
										error.getMessage(),
										android.widget.Toast.LENGTH_LONG
										)
										.show();
									}
								}
								);
							}
						}
					},
					"cigram-preview-series"
					).start();
				}
			}
			);
			
			addButton.setOnClickListener(
			new android.view.View.OnClickListener() {
				@Override
				public void onClick(android.view.View v) {
					if (previewSeries[0] == null) {
						android.widget.Toast
						.makeText(
						SeriesImportActivity.this,
						"افحص المسلسل أولاً.",
						android.widget.Toast.LENGTH_SHORT
						)
						.show();
						return;
					}
					
					final String tmdb =
					tmdbInput
					.getText()
					.toString()
					.trim();
					
					final String slug =
					slugInput
					.getText()
					.toString()
					.trim();
					
					addButton.setEnabled(false);
					addButton.setAlpha(0.55f);
					addButton.setText(
					"جاري إضافة المسلسل..."
					);
					
					status.setText(
					"جاري حفظ المسلسل ومواسمه وحلقاته..."
					);
					status.setTextColor(MUTED);
					status.setVisibility(
					android.view.View.VISIBLE
					);
					
					final org.json.JSONObject seasonPosters =
					new org.json.JSONObject();
					
					try {
						for (
						java.util.Map.Entry<
						Integer,
						android.widget.EditText
						> entry :
						seasonPosterEdits.entrySet()
						) {
							int seasonNumber =
							entry.getKey();
							
							String current =
							entry
							.getValue()
							.getText()
							.toString()
							.trim();
							
							String original =
							originalSeasonPosters.get(
							seasonNumber
							);
							
							if (original == null) {
								original = "";
							}
							
							if (
							current.startsWith("http") &&
							!current.equals(original)
							) {
								seasonPosters.put(
								String.valueOf(
								seasonNumber
								),
								current
								);
							}
						}
					} catch (Exception ignored) {}
					
					new Thread(
					new Runnable() {
						@Override
						public void run() {
							try {
								org.json.JSONObject bodyJson =
								new org.json.JSONObject();
								
								bodyJson.put(
								"tmdb_url",
								tmdb
								);
								bodyJson.put(
								"slug",
								slug
								);
								
								if (
								seasonPosters.length() > 0
								) {
									bodyJson.put(
									"season_posters",
									seasonPosters
									);
								}
								
								final org.json.JSONObject response =
								net.post(
								"add-series",
								bodyJson
								);
								
								final org.json.JSONObject series =
								response.optJSONObject(
								"series"
								);
								
								runOnUiThread(
								new Runnable() {
									@Override
									public void run() {
										addButton.setEnabled(
										true
										);
										addButton.setAlpha(
										1f
										);
										addButton.setText(
										"تمت الإضافة - مزامنة من جديد"
										);
										
										status.setText(
										response.optString(
										"message",
										"تمت إضافة المسلسل بنجاح."
										)
										);
										status.setTextColor(
										GOOD
										);
										
										if (series != null) {
											render.render(
											series
											);
										}
										
										android.widget.Toast
										.makeText(
										SeriesImportActivity.this,
										"تمت إضافة المسلسل بنجاح.",
										android.widget.Toast.LENGTH_LONG
										)
										.show();
									}
								}
								);
								
							} catch (
							final Exception error
							) {
								runOnUiThread(
								new Runnable() {
									@Override
									public void run() {
										addButton.setEnabled(
										true
										);
										addButton.setAlpha(
										1f
										);
										addButton.setText(
										"إضافة المسلسل"
										);
										
										status.setText(
										"فشلت الإضافة: " +
										error.getMessage()
										);
										status.setTextColor(
										BAD
										);
										
										android.widget.Toast
										.makeText(
										SeriesImportActivity.this,
										"فشلت الإضافة: " +
										error.getMessage(),
										android.widget.Toast.LENGTH_LONG
										)
										.show();
									}
								}
								);
							}
						}
					},
					"cigram-add-series"
					).start();
				}
			}
			);
		}
		
	}
	
}