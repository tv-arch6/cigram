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
import androidx.asynclayoutinflater.*;
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

public class SectionActivity extends AppCompatActivity {
	
	private SectionBinding binding;
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		binding = SectionBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		initialize(_savedInstanceState);
		
		CigramAdManager.initialize(getApplicationContext()); // self-throttled, background thread, consent-gated
		
		initializeLogic();
	}
	
	private void initialize(Bundle _savedInstanceState) {
	}
	
	private void initializeLogic() {
		class CigramGlass {
			final android.content.Context context;
			final float density;
			final android.graphics.Typeface font;
			
			CigramGlass(android.content.Context value) {
				context = value;
				density = value.getResources().getDisplayMetrics().density;
				
				android.graphics.Typeface loaded =
				android.graphics.Typeface.DEFAULT;
				try {
					loaded = CigramTypefaces.get(
					value.getAssets(), "fonts/sima.ttf"
					);
				} catch (Exception ignored) {}
				font = loaded;
			}
			
			int px(float value) {
				return Math.round(value * density);
			}
			
			class Skin extends android.graphics.drawable.Drawable {
				final float radius;
				final boolean outlined;
				final android.graphics.Paint paint =
				new android.graphics.Paint(3);
				int alpha = 255;
				
				Skin(float radiusDp, boolean showOutline) {
					radius = px(radiusDp);
					outlined = showOutline;
				}
				
				@Override public void draw(
				android.graphics.Canvas canvas
				) {
					android.graphics.RectF rect =
					new android.graphics.RectF(getBounds());
					rect.inset(density / 2f, density / 2f);
					
					int layer = canvas.saveLayerAlpha(rect, alpha);
					paint.setStyle(android.graphics.Paint.Style.FILL);
					paint.setShader(null);
					paint.setColor(0xB005141F);
					canvas.drawRoundRect(rect, radius, radius, paint);
					
					paint.setShader(new android.graphics.LinearGradient(
					rect.left, rect.top,
					rect.right, rect.bottom,
					new int[] {
						0x34FFFFFF, 0x09FFFFFF,
						0x0005141F, 0x16FFFFFF
					},
					new float[] {0f, .28f, .65f, 1f},
					android.graphics.Shader.TileMode.CLAMP
					));
					canvas.drawRoundRect(rect, radius, radius, paint);
					paint.setShader(null);
					
					if (outlined) {
						paint.setStyle(android.graphics.Paint.Style.STROKE);
						paint.setStrokeWidth(density);
						paint.setColor(0x60FFFFFF);
						canvas.drawRoundRect(rect, radius, radius, paint);
						paint.setStyle(android.graphics.Paint.Style.FILL);
					}
					canvas.restoreToCount(layer);
				}
				
				@Override public void setAlpha(int value) {
					alpha = value;
					invalidateSelf();
				}
				
				@Override public void setColorFilter(
				android.graphics.ColorFilter filter
				) {}
				
				@Override public int getOpacity() {
					return android.graphics.PixelFormat.TRANSLUCENT;
				}
				
				@Override public void getOutline(
				android.graphics.Outline outline
				) {
					outline.setRoundRect(getBounds(), radius);
				}
			}
			
			void bind(
			android.view.View view,
			float radius,
			boolean outlined
			) {
				if (view == null) return;
				view.setBackground(new Skin(radius, outlined));
				view.setElevation(0f);
				view.setClipToOutline(true);
			}
			
			void press(final android.view.View view) {
				if (view == null) return;
				
				view.setOnTouchListener(
				new android.view.View.OnTouchListener() {
					@Override public boolean onTouch(
					android.view.View v,
					android.view.MotionEvent event
					) {
						int action = event.getActionMasked();
						
						if (
						action
						== android.view.MotionEvent.ACTION_DOWN
						) {
							v.animate()
							.scaleX(1f)
							.scaleY(1f)
							.setDuration(100L)
							.start();
						} else if (
						action
						== android.view.MotionEvent.ACTION_UP
						|| action
						== android.view.MotionEvent.ACTION_CANCEL
						) {
							v.animate()
							.scaleX(1f)
							.scaleY(1f)
							.setDuration(190L)
							.start();
						}
						return false;
					}
				}
				);
			}
			
			android.widget.TextView label(
			String value,
			float size
			) {
				android.widget.TextView label =
				new android.widget.TextView(context);
				
				label.setText(value);
				label.setTextSize(size);
				label.setTextColor(0xFFFFFFFF);
				label.setTypeface(font);
				label.setGravity(
				android.view.Gravity.RIGHT
				| android.view.Gravity.CENTER_VERTICAL
				);
				label.setTextDirection(
				android.view.View.TEXT_DIRECTION_FIRST_STRONG
				);
				return label;
			}
		}
		
		final CigramGlass cgGlass =
		new CigramGlass(SectionActivity.this);
		
		class CigramCatalog {
			String text(
			org.json.JSONObject object,
			String... keys
			) {
				if (object == null) return "";
				
				for (String key : keys) {
					Object value = object.opt(key);
					if (
					value != null
					&& value != org.json.JSONObject.NULL
					&& !String.valueOf(value).trim().isEmpty()
					) {
						return String.valueOf(value).trim();
					}
				}
				return "";
			}
			
			String normalize(String value) {
				if (value == null) return "";
				
				return java.text.Normalizer.normalize(
				value,
				java.text.Normalizer.Form.NFKD
				)
				.replaceAll("\\p{M}+", "")
				.replace("ـ", "")
				.toLowerCase(java.util.Locale.ROOT)
				.replace('أ', 'ا')
				.replace('إ', 'ا')
				.replace('آ', 'ا')
				.replace('ٱ', 'ا')
				.replace('ى', 'ي')
				.replace('ة', 'ه')
				.replace('ؤ', 'و')
				.replace('ئ', 'ي')
				.replaceAll("[^\\p{L}\\p{N}]+", " ")
				.trim()
				.replaceAll("\\s+", " ");
			}
			
			String type(
			org.json.JSONObject object,
			String inherited
			) {
				String value = normalize(
				text(
				object,
				"type",
				"content_type",
				"media_type"
				)
				);
				
				if (
				value.equals("series")
				|| value.equals("tv")
				|| value.equals("show")
				|| value.contains("مسلسل")
				) {
					return "series";
				}
				
				if (
				value.equals("movie")
				|| value.equals("film")
				|| value.equals("فيلم")
				) {
					return "movie";
				}
				
				if (
				!text(
				object,
				"season",
				"episode",
				"series_title",
				"series_id"
				).isEmpty()
				|| object.has("seasons")
				|| object.has("episodes")
				|| object.has("series_episodes_json")
				) {
					return "series";
				}
				
				return "series".equals(inherited)
				? "series" : "movie";
			}
			
			void episodes(
			Object node,
			org.json.JSONObject parent,
			String season,
			org.json.JSONArray output,
			int depth
			) throws org.json.JSONException {
				if (
				node == null
				|| node == org.json.JSONObject.NULL
				|| depth > 12
				) {
					return;
				}
				
				if (node instanceof String) {
					String encoded = ((String) node).trim();
					if (
					encoded.startsWith("[")
					|| encoded.startsWith("{")
					) {
						episodes(
						new org.json.JSONTokener(
						encoded
						).nextValue(),
						parent,
						season,
						output,
						depth + 1
						);
					}
					return;
				}
				
				if (node instanceof org.json.JSONArray) {
					org.json.JSONArray array =
					(org.json.JSONArray) node;
					
					for (int i = 0; i < array.length(); i++) {
						episodes(
						array.opt(i),
						parent,
						season,
						output,
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
				
				String currentSeason = text(
				object,
				"season",
				"season_number"
				);
				if (currentSeason.isEmpty()) {
					currentSeason = season;
				}
				
				if (object.has("seasons")) {
					episodes(
					object.opt("seasons"),
					parent,
					currentSeason,
					output,
					depth + 1
					);
					return;
				}
				
				if (object.has("episodes")) {
					if (currentSeason.isEmpty()) {
						currentSeason = text(object, "number");
					}
					episodes(
					object.opt("episodes"),
					parent,
					currentSeason,
					output,
					depth + 1
					);
					return;
				}
				
				org.json.JSONObject episode =
				new org.json.JSONObject(
				object.toString()
				);
				
				for (String key : new String[] {
					"poster",
					"series_poster",
					"backdrop",
					"category",
					"year",
					"imdb",
					"description"
				}) {
					if (
					text(episode, key).isEmpty()
					&& !text(parent, key).isEmpty()
					) {
						episode.put(
						key,
						parent.opt(key)
						);
					}
				}
				
				episode.put("type", "series");
				episode.put(
				"series_title",
				text(
				parent,
				"series_title",
				"title",
				"name"
				)
				);
				episode.put(
				"series_id",
				text(
				parent,
				"series_id",
				"id"
				)
				);
				
				if (text(episode, "season").isEmpty()) {
					episode.put(
					"season",
					currentSeason.isEmpty()
					? "1" : currentSeason
					);
				}
				
				if (text(episode, "episode").isEmpty()) {
					episode.put(
					"episode",
					text(
					episode,
					"episode_number",
					"number"
					)
					);
				}
				
				if (text(episode, "video").isEmpty()) {
					episode.put(
					"video",
					text(
					episode,
					"link",
					"url",
					"movie_url",
					"video_url"
					)
					);
				}
				
				output.put(episode);
			}
			
			void collect(
			Object node,
			String inherited,
			org.json.JSONArray output,
			int depth
			) throws org.json.JSONException {
				if (
				node == null
				|| node == org.json.JSONObject.NULL
				|| depth > 12
				) {
					return;
				}
				
				if (node instanceof org.json.JSONArray) {
					org.json.JSONArray array =
					(org.json.JSONArray) node;
					
					for (int i = 0; i < array.length(); i++) {
						collect(
						array.opt(i),
						inherited,
						output,
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
				
				String[] containers = {
					"movies",
					"series",
					"items",
					"data",
					"results",
					"content",
					"sections",
					"catalog"
				};
				
				boolean collection = false;
				for (String key : containers) {
					Object child = object.opt(key);
					
					if (
					child instanceof org.json.JSONArray
					|| child instanceof org.json.JSONObject
					) {
						collection = true;
					} else if (child instanceof String) {
						String encoded =
						((String) child).trim();
						
						if (
						encoded.startsWith("[")
						|| encoded.startsWith("{")
						) {
							collection = true;
						}
					}
				}
				
				boolean record =
				!collection
				&& !text(
				object,
				"title",
				"name",
				"series_title"
				).isEmpty()
				&& (
				object.has("id")
				|| object.has("poster")
				|| object.has("video")
				|| object.has("link")
				|| object.has("type")
				|| object.has("episodes")
				|| object.has("seasons")
				);
				
				if (record) {
					org.json.JSONObject item =
					new org.json.JSONObject(
					object.toString()
					);
					
					String kind = type(
					item,
					inherited
					);
					item.put("type", kind);
					
					item.put(
					"title",
					kind.equals("series")
					? text(
					item,
					"series_title",
					"title",
					"name"
					)
					: text(
					item,
					"title",
					"name"
					)
					);
					
					if (text(item, "poster").isEmpty()) {
						item.put(
						"poster",
						text(
						item,
						"series_poster",
						"season_poster",
						"image",
						"backdrop"
						)
						);
					}
					
					if (text(item, "video").isEmpty()) {
						item.put(
						"video",
						text(
						item,
						"link",
						"url",
						"video_url",
						"movie_url"
						)
						);
					}
					
					if (text(item, "category").isEmpty()) {
						item.put(
						"category",
						text(
						item,
						"genre",
						"genres"
						)
						);
					}
					
					if (text(item, "imdb").isEmpty()) {
						item.put(
						"imdb",
						text(item, "rating")
						);
					}
					
					if (kind.equals("series")) {
						org.json.JSONArray list =
						new org.json.JSONArray();
						
						for (String key : new String[] {
							"series_episodes_json",
							"episodes",
							"seasons"
						}) {
							if (item.has(key)) {
								episodes(
								item.opt(key),
								item,
								"",
								list,
								0
								);
							}
						}
						
						if (list.length() > 0) {
							item.put(
							"series_episodes_json",
							list.toString()
							);
						}
					}
					
					output.put(item);
					return;
				}
				
				for (String key : containers) {
					Object child = object.opt(key);
					
					if (child instanceof String) {
						String encoded =
						((String) child).trim();
						
						if (
						encoded.startsWith("[")
						|| encoded.startsWith("{")
						) {
							child = new org.json.JSONTokener(
							encoded
							).nextValue();
						}
					}
					
					if (child != null) {
						collect(
						child,
						key.equals("series")
						? "series"
						: inherited,
						output,
						depth + 1
						);
					}
				}
			}
			
			String key(org.json.JSONObject item) {
				if (
				text(item, "type").equals("series")
				) {
					String id =
					text(item, "series_id");
					
					return "series|" + (
					id.isEmpty()
					? normalize(
					text(
					item,
					"series_title",
					"title"
					)
					)
					: id
					);
				}
				
				String id = text(item, "id");
				
				return "movie|" + (
				id.isEmpty()
				? normalize(
				text(item, "title")
				)
				+ "|"
				+ text(item, "year")
				: id
				);
			}
			
			org.json.JSONArray parse(String json)
			throws org.json.JSONException {
				org.json.JSONArray flat =
				new org.json.JSONArray();
				
				Object root =
				new org.json.JSONTokener(
				json
				).nextValue();
				
				if (root instanceof org.json.JSONObject) {
					org.json.JSONObject object =
					(org.json.JSONObject) root;
					
					if (
					!object.optBoolean("ok", true)
					) {
						throw new org.json.JSONException(
						"Catalog request failed"
						);
					}
					
					boolean known =
					!text(
					object,
					"title",
					"name",
					"series_title"
					).isEmpty();
					
					for (String container : new String[] {
						"movies",
						"series",
						"items",
						"data",
						"results",
						"content",
						"sections",
						"catalog"
					}) {
						known =
						known
						|| object.has(container);
					}
					
					if (!known) {
						throw new org.json.JSONException(
						"Unrecognized catalog"
						);
					}
				} else if (
				!(root instanceof org.json.JSONArray)
				) {
					throw new org.json.JSONException(
					"Invalid catalog"
					);
				}
				
				collect(root, "", flat, 0);
				
				java.util.LinkedHashMap<
				String,
				org.json.JSONObject
				> unique = new java.util.LinkedHashMap<
				String,
				org.json.JSONObject
				>();
				
				java.util.LinkedHashMap<
				String,
				java.util.LinkedHashMap<
				String,
				org.json.JSONObject
				>
				> episodeGroups =
				new java.util.LinkedHashMap<
				String,
				java.util.LinkedHashMap<
				String,
				org.json.JSONObject
				>
				>();
				
				for (
				int i = 0;
				i < flat.length();
				i++
				) {
					org.json.JSONObject item =
					flat.getJSONObject(i);
					
					String itemKey = key(item);
					org.json.JSONObject existing =
					unique.get(itemKey);
					
					if (existing == null) {
						org.json.JSONObject copy = new org.json.JSONObject();
						java.util.Iterator<String> copyKeys = item.keys();
						while (copyKeys.hasNext()) {
							String copyKey = copyKeys.next();
							copy.put(copyKey, item.opt(copyKey));
						}
						unique.put(itemKey, copy);
					} else {
						java.util.Iterator<String> names =
						item.keys();
						
						while (names.hasNext()) {
							String name =
							names.next();
							
							if (
							text(existing, name)
							.isEmpty()
							) {
								existing.put(
								name,
								item.opt(name)
								);
							}
						}
					}
					
					if (
					!text(item, "type")
					.equals("series")
					) {
						continue;
					}
					
					if (
					!episodeGroups
					.containsKey(itemKey)
					) {
						episodeGroups.put(
						itemKey,
						new java.util.LinkedHashMap<
						String,
						org.json.JSONObject
						>()
						);
					}
					
					org.json.JSONArray list =
					new org.json.JSONArray();
					
					String nested = text(
					item,
					"series_episodes_json"
					);
					
					if (!nested.isEmpty()) {
						list =
						new org.json.JSONArray(
						nested
						);
					}
					
					if (
					!text(item, "episode")
					.isEmpty()
					|| (
					!text(item, "video")
					.isEmpty()
					&& list.length() == 0
					)
					) {
						list.put(item);
					}
					
					for (
					int n = 0;
					n < list.length();
					n++
					) {
						org.json.JSONObject episode =
						list.optJSONObject(n);
						
						if (episode == null) {
							continue;
						}
						
						String episodeKey =
						text(
						episode,
						"season"
						)
						+ "|"
						+ text(
						episode,
						"episode"
						)
						+ "|"
						+ text(
						episode,
						"video",
						"link"
						);
						
						episodeGroups
						.get(itemKey)
						.put(
						episodeKey,
						episode
						);
					}
				}
				
				org.json.JSONArray result =
				new org.json.JSONArray();
				
				for (
				java.util.Map.Entry<
				String,
				org.json.JSONObject
				> entry
				: unique.entrySet()
				) {
					java.util.LinkedHashMap<
					String,
					org.json.JSONObject
					> group = episodeGroups.get(
					entry.getKey()
					);
					
					if (
					group != null
					&& !group.isEmpty()
					) {
						org.json.JSONArray list =
						new org.json.JSONArray();
						
						for (
						org.json.JSONObject episode
						: group.values()
						) {
							list.put(episode);
						}
						
						entry.getValue().put(
						"series_episodes_json",
						list.toString()
						);
					}
					
					result.put(
					entry.getValue()
					);
				}
				
				return result;
			}
		}
		
		final CigramCatalog cgCatalog =
		new CigramCatalog();
		
		class CigramImage {
			final android.content.Context app;
			
			final android.os.Handler main =
			new android.os.Handler(
			android.os.Looper.getMainLooper()
			);
			
			final java.util.concurrent.Executor workers =
			android.os.AsyncTask.THREAD_POOL_EXECUTOR;
			
			final java.util.LinkedHashMap<
			String,
			android.graphics.Bitmap
			> memory = new java.util.LinkedHashMap<
			String,
			android.graphics.Bitmap
			>(18, .75f, true) {
				@Override
				protected boolean removeEldestEntry(
				java.util.Map.Entry<
				String,
				android.graphics.Bitmap
				> entry
				) {
					return size() > 16;
				}
			};
			
			CigramImage(
			android.content.Context value
			) {
				app = value.getApplicationContext();
			}
			
			String clean(String value) {
				return value == null
				? ""
				: value.trim();
			}
			
			String cacheName(String url) {
				try {
					byte[] bytes =
					java.security.MessageDigest
					.getInstance("SHA-256")
					.digest(
					url.getBytes("UTF-8")
					);
					
					StringBuilder name =
					new StringBuilder();
					
					for (byte value : bytes) {
						name.append(
						String.format(
						java.util.Locale.US,
						"%02x",
						value & 0xff
						)
						);
					}
					
					return name.toString()
					+ ".img";
				} catch (Exception ignored) {
					return Integer.toHexString(
					url.hashCode()
					) + ".img";
				}
			}
			
			java.io.File file(String url) {
				java.io.File folder =
				new java.io.File(
				app.getCacheDir(),
				"cigram_image_cache"
				);
				
				if (!folder.exists()) {
					folder.mkdirs();
				}
				
				return new java.io.File(
				folder,
				cacheName(url)
				);
			}
			
			android.graphics.Bitmap decode(
			java.io.File source
			) {
				try {
					android.graphics.BitmapFactory.Options bounds =
					new android.graphics.BitmapFactory.Options();
					
					bounds.inJustDecodeBounds =
					true;
					
					android.graphics.BitmapFactory.decodeFile(
					source.getAbsolutePath(),
					bounds
					);
					
					if (
					bounds.outWidth <= 0
					|| bounds.outHeight <= 0
					) {
						return null;
					}
					
					int largest = Math.max(
					bounds.outWidth,
					bounds.outHeight
					);
					
					int sample = 1;
					
					while (
					largest / sample > 960
					) {
						sample *= 2;
					}
					
					android.graphics.BitmapFactory.Options options =
					new android.graphics.BitmapFactory.Options();
					
					options.inSampleSize =
					sample;
					
					options.inPreferredConfig =
					android.graphics.Bitmap.Config.RGB_565;
					
					return android.graphics.BitmapFactory.decodeFile(
					source.getAbsolutePath(),
					options
					);
				} catch (Exception ignored) {
					return null;
				}
			}
			
			android.graphics.Bitmap obtain(
			String url
			) {
				synchronized (memory) {
					android.graphics.Bitmap cached =
					memory.get(url);
					
					if (
					cached != null
					&& !cached.isRecycled()
					) {
						return cached;
					}
				}
				
				java.io.File target =
				file(url);
				
				android.graphics.Bitmap bitmap =
				null;
				
				if (
				target.isFile()
				&& target.length() > 0
				) {
					bitmap = decode(target);
				}
				
				if (
				bitmap == null
				&& (
				url.startsWith("http://")
				|| url.startsWith("https://")
				)
				) {
					java.net.HttpURLConnection connection =
					null;
					java.io.InputStream input =
					null;
					java.io.FileOutputStream output =
					null;
					
					java.io.File temporary =
					new java.io.File(
					target.getParentFile(),
					target.getName()
					+ ".part"
					);
					
					try {
						connection =
						(java.net.HttpURLConnection)
						new java.net.URL(
						url
						).openConnection();
						
						connection.setConnectTimeout(
						10000
						);
						connection.setReadTimeout(
						15000
						);
						connection.setInstanceFollowRedirects(
						true
						);
						
						int status =
						connection.getResponseCode();
						
						if (
						status < 200
						|| status >= 300
						) {
							throw new java.io.IOException(
							"HTTP " + status
							);
						}
						
						input =
						connection.getInputStream();
						
						output =
						new java.io.FileOutputStream(
						temporary,
						false
						);
						
						byte[] buffer =
						new byte[16384];
						
						int read;
						long total = 0L;
						
						while (
						(read = input.read(buffer))
						!= -1
						) {
							total += read;
							
							if (
							total > 12582912L
							) {
								throw new java.io.IOException(
								"Image too large"
								);
							}
							
							output.write(
							buffer,
							0,
							read
							);
						}
						
						output.flush();
						output.close();
						output = null;
						
						if (target.exists()) {
							target.delete();
						}
						
						if (
						temporary.renameTo(
						target
						)
						) {
							bitmap =
							decode(target);
						} else {
							bitmap =
							decode(temporary);
						}
					} catch (Exception error) {
						android.util.Log.w(
						"CigramImage",
						"Image loading failed",
						error
						);
					} finally {
						try {
							if (input != null) {
								input.close();
							}
						} catch (Exception ignored) {}
						
						try {
							if (output != null) {
								output.close();
							}
						} catch (Exception ignored) {}
						
						if (connection != null) {
							connection.disconnect();
						}
						
						if (temporary.exists()) {
							temporary.delete();
						}
					}
				}
				
				if (
				bitmap == null
				&& url.startsWith("file://")
				) {
					bitmap = decode(
					new java.io.File(
					android.net.Uri
					.parse(url)
					.getPath()
					)
					);
				}
				
				if (bitmap != null) {
					synchronized (memory) {
						memory.put(
						url,
						bitmap
						);
					}
				}
				
				return bitmap;
			}
			
			void load(
			final String raw,
			final android.widget.ImageView view
			) {
				if (view == null) {
					return;
				}
				
				final String url =
				clean(raw);
				
				final String token =
				"cigram-image:" + url;
				
				view.setTag(token);
				view.setScaleType(
				android.widget.ImageView.ScaleType.CENTER_CROP
				);
				android.graphics.drawable.GradientDrawable cgPlaceholder =
				new android.graphics.drawable.GradientDrawable();
				cgPlaceholder.setColor(0xFF101820);
				cgPlaceholder.setCornerRadius(14f * app.getResources().getDisplayMetrics().density);
				view.setImageDrawable(null);
				view.setBackground(cgPlaceholder);
				
				if (url.isEmpty()) {
					return;
				}
				
				android.graphics.Bitmap cached;
				
				synchronized (memory) {
					cached = memory.get(url);
				}
				
				if (
				cached != null
				&& !cached.isRecycled()
				) {
					view.setImageBitmap(
					cached
					);
					return;
				}
				
				workers.execute(
				new Runnable() {
					@Override public void run() {
						final android.graphics.Bitmap bitmap =
						obtain(url);
						
						if (bitmap == null) {
							return;
						}
						
						main.post(
						new Runnable() {
							@Override
							public void run() {
								if (
								token.equals(
								String.valueOf(
								view.getTag()
								)
								)
								) {
									view.setImageBitmap(
									bitmap
									);
								}
							}
						}
						);
					}
				}
				);
			}
		}
		
		final CigramImage cgImage =
		new CigramImage(
		SectionActivity.this
		);
		
		final android.widget.LinearLayout cgSectionRoot =
		(android.widget.LinearLayout)
		findViewById(
		R.id.linear_section_root
		);
		
		final android.widget.LinearLayout cgSectionItems =
		(android.widget.LinearLayout)
		findViewById(
		R.id.linear_section_items
		);
		
		final android.widget.TextView cgSectionTitle =
		(android.widget.TextView)
		findViewById(
		R.id.textview_section_title
		);
		
		final String cgSectionId =
		getIntent()
		.getStringExtra("section_id")
		== null
		? ""
		: getIntent()
		.getStringExtra(
		"section_id"
		);
		
		final String cgSectionName =
		getIntent()
		.getStringExtra("section_title")
		== null
		? "القسم"
		: getIntent()
		.getStringExtra(
		"section_title"
		);
		
		final String cgSectionType =
		getIntent()
		.getStringExtra("section_type")
		== null
		? ""
		: getIntent()
		.getStringExtra(
		"section_type"
		);
		
		final String cgSectionCategory =
		getIntent()
		.getStringExtra("section_category")
		== null
		? ""
		: getIntent()
		.getStringExtra("section_category")
		.trim();
		
		final String cgSectionExcludeId =
		getIntent()
		.getStringExtra("section_exclude_id")
		== null
		? ""
		: getIntent()
		.getStringExtra("section_exclude_id")
		.trim();
		
		cgSectionRoot.setBackgroundColor(
		0xFF00121A
		);
		cgSectionRoot.setLayoutDirection(
		android.view.View.LAYOUT_DIRECTION_RTL
		);
		cgSectionItems.setOrientation(
		android.widget.LinearLayout.VERTICAL
		);
		cgSectionItems.setPadding(
		cgGlass.px(8),
		cgGlass.px(10),
		cgGlass.px(8),
		cgGlass.px(20)
		);
		
		cgSectionTitle.setText(
		cgSectionName
		);
		cgSectionTitle.setTypeface(
		cgGlass.font
		);
		cgSectionTitle.setTextColor(
		0xFFFFFFFF
		);
		
		cgGlass.bind(
		findViewById(
		R.id.linear_section_header
		),
		22,
		false
		);
		
		findViewById(
		R.id.imageview_section_back
		).setOnClickListener(
		new android.view.View.OnClickListener() {
			@Override public void onClick(
			android.view.View view
			) {
				finish();
				overridePendingTransition(
				android.R.anim.fade_in,
				android.R.anim.fade_out
				);
			}
		}
		);
		
		
		class CigramPosterView extends android.widget.ImageView {
			CigramPosterView(android.content.Context context) {
				super(context);
			}
			
			@Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
				int width = android.view.View.MeasureSpec.getSize(widthMeasureSpec);
				if (width <= 0) {
					super.onMeasure(widthMeasureSpec, heightMeasureSpec);
					width = getMeasuredWidth();
				}
				int height = Math.max(1, Math.round(width * 1.5f));
				setMeasuredDimension(width, height);
			}
		}
		
		class CigramSection {
			String shown = "";
			final java.util.ArrayList<org.json.JSONObject> visible =
			new java.util.ArrayList<org.json.JSONObject>();
			int rendered = 0;
			boolean scrollHooked = false;
			
			String extra(String key) {
				String value = getIntent().getStringExtra(key);
				return value == null ? "" : value.trim();
			}
			
			boolean relatedMode() {
				String mode = extra("section_mode");
				return "__related__".equalsIgnoreCase(cgSectionId)
				|| "recommendations".equalsIgnoreCase(cgSectionId)
				|| "related".equalsIgnoreCase(mode);
			}
			
			String kind(org.json.JSONObject item) {
				String value = cgCatalog.text(item, "type").trim().toLowerCase(java.util.Locale.ROOT);
				return value.isEmpty() ? "movie" : value;
			}
			
			String identity(org.json.JSONObject item) {
				return cgCatalog.key(item);
			}
			
			boolean belongsToSection(org.json.JSONObject item) {
				if (item == null) return false;
				
				String itemType = kind(item);
				
				if (relatedMode()) {
					if (!cgSectionType.isEmpty() && !cgSectionType.equalsIgnoreCase(itemType)) {
						return false;
					}
					
					String excluded = extra("related_exclude_key");
					if (excluded.isEmpty()) excluded = cgSectionExcludeId;
					
					if (!excluded.isEmpty()) {
						String itemKey = identity(item);
						String itemId = cgCatalog.text(item, "series_id", "id");
						
						if (excluded.equalsIgnoreCase(itemKey)
						|| excluded.equalsIgnoreCase(itemId)) {
							return false;
						}
					}
					
					String wantedCategory = extra("related_category");
					if (wantedCategory.isEmpty()) wantedCategory = cgSectionCategory;
					if (wantedCategory.isEmpty()) return true;
					
					String wanted = cgCatalog.normalize(wantedCategory);
					String candidate = cgCatalog.normalize(
					cgCatalog.text(item, "category", "genre", "genres")
					);
					
					if (wanted.isEmpty()) return true;
					if (candidate.contains(wanted) || wanted.contains(candidate)) return true;
					
					String[] parts = wanted.split("[,،/| ]+");
					for (String part : parts) {
						if (part != null && part.length() > 1 && candidate.contains(part)) {
							return true;
						}
					}
					return false;
				}
				
				if ("latest_movies".equalsIgnoreCase(cgSectionId)) {
					return "movie".equals(itemType);
				}
				
				if ("featured_series".equalsIgnoreCase(cgSectionId)
				|| "latest_series".equalsIgnoreCase(cgSectionId)) {
					return "series".equals(itemType);
				}
				
				if (!cgSectionType.isEmpty()
				&& !cgSectionType.equalsIgnoreCase(itemType)) {
					return false;
				}
				
				String itemSectionId = cgCatalog.text(item, "section_id", "sectionId");
				String itemSectionName = cgCatalog.text(item, "section_name", "section");
				
				if (!itemSectionId.isEmpty() || !itemSectionName.isEmpty()) {
					boolean sameId = !cgSectionId.isEmpty()
					&& cgSectionId.equalsIgnoreCase(itemSectionId);
					
					boolean sameName = !cgSectionName.isEmpty()
					&& cgSectionName.equalsIgnoreCase(itemSectionName);
					
					return sameId || sameName;
				}
				
				return cgSectionId.isEmpty();
			}
			
			boolean hasRating(String value) {
				if (value == null) return false;
				String text = value.trim();
				if (text.isEmpty()) return false;
				
				try {
					return Double.parseDouble(text.replace(',', '.')) > 0.0001d;
				} catch (Exception ignored) {
					return !text.equals("0") && !text.equals("0.0");
				}
			}
			
			String firstCategory(String value) {
				if (value == null) return "";
				String text = value.trim();
				if (text.isEmpty()) return "";
				
				String[] parts = text.split("[,،|/]");
				return parts.length == 0 ? text : parts[0].trim();
			}
			
			android.graphics.drawable.GradientDrawable rounded(int color, float radiusDp) {
				android.graphics.drawable.GradientDrawable drawable =
				new android.graphics.drawable.GradientDrawable();
				drawable.setColor(color);
				drawable.setCornerRadius(cgGlass.px(radiusDp));
				return drawable;
			}
			
			android.widget.TextView text(
			String value,
			float size,
			int color,
			boolean bold
			) {
				android.widget.TextView view = new android.widget.TextView(SectionActivity.this);
				view.setText(value == null ? "" : value);
				view.setTextSize(size);
				view.setTextColor(color);
				view.setTypeface(
				cgGlass.font,
				bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL
				);
				view.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
				view.setTextDirection(android.view.View.TEXT_DIRECTION_FIRST_STRONG);
				view.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
				return view;
			}
			
			android.view.View makeCard(final org.json.JSONObject item) {
				final android.widget.LinearLayout card =
				new android.widget.LinearLayout(SectionActivity.this);
				
				card.setOrientation(android.widget.LinearLayout.VERTICAL);
				card.setGravity(android.view.Gravity.RIGHT);
				card.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
				card.setBackgroundColor(android.graphics.Color.TRANSPARENT);
				card.setClickable(true);
				card.setFocusable(true);
				
				final CigramPosterView poster =
				new CigramPosterView(SectionActivity.this);
				
				poster.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
				poster.setBackground(rounded(0xFF0B2733, 14));
				
				if (android.os.Build.VERSION.SDK_INT >= 21) {
					final float radius = cgGlass.px(14);
					poster.setOutlineProvider(
					new android.view.ViewOutlineProvider() {
						@Override public void getOutline(
						android.view.View view,
						android.graphics.Outline outline
						) {
							outline.setRoundRect(
							0,
							0,
							view.getWidth(),
							view.getHeight(),
							radius
							);
						}
					}
					);
					poster.setClipToOutline(true);
				}
				
				card.addView(
				poster,
				new android.widget.LinearLayout.LayoutParams(
				-1,
				-2
				)
				);
				
				final String posterUrl =
				cgCatalog.text(item, "poster", "series_poster", "season_poster", "image", "backdrop");
				
				final String backdropUrl =
				cgCatalog.text(item, "backdrop", "landscape_poster", "source_backdrop");
				
				cgImage.load(posterUrl, poster);
				
				String ratingValue =
				cgCatalog.text(item, "imdb", "rating", "imdb_rating", "vote_average", "score");
				
				android.widget.LinearLayout ratingRow =
				new android.widget.LinearLayout(SectionActivity.this);
				
				ratingRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
				ratingRow.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);
				ratingRow.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
				
				android.widget.LinearLayout.LayoutParams ratingRowParams =
				new android.widget.LinearLayout.LayoutParams(
				-1,
				cgGlass.px(24)
				);
				ratingRowParams.topMargin = cgGlass.px(6);
				
				card.addView(ratingRow, ratingRowParams);
				
				android.widget.ImageView imdb =
				new android.widget.ImageView(SectionActivity.this);
				
				imdb.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
				
				int imdbId = getResources().getIdentifier(
				"ic_imdb",
				"drawable",
				getPackageName()
				);
				
				if (imdbId == 0) {
					imdbId = getResources().getIdentifier(
					"imdb",
					"drawable",
					getPackageName()
					);
				}
				
				if (imdbId != 0) {
					imdb.setImageResource(imdbId);
				}
				
				ratingRow.addView(
				imdb,
				new android.widget.LinearLayout.LayoutParams(
				cgGlass.px(36),
				cgGlass.px(18)
				)
				);
				
				android.widget.TextView rating =
				text(
				ratingValue,
				12,
				0xFFFFFFFF,
				true
				);
				
				android.widget.LinearLayout.LayoutParams ratingParams =
				new android.widget.LinearLayout.LayoutParams(
				-2,
				cgGlass.px(24)
				);
				ratingParams.leftMargin = cgGlass.px(5);
				
				ratingRow.addView(rating, ratingParams);
				ratingRow.setVisibility(
				hasRating(ratingValue)
				? android.view.View.VISIBLE
				: android.view.View.GONE
				);
				
				android.widget.TextView title =
				text(
				cgCatalog.text(item, "series_title", "title", "name"),
				14,
				0xFFFFFFFF,
				true
				);
				
				title.setSingleLine(true);
				title.setEllipsize(android.text.TextUtils.TruncateAt.END);
				title.setTextDirection(android.view.View.TEXT_DIRECTION_FIRST_STRONG);
				
				android.widget.LinearLayout.LayoutParams titleParams =
				new android.widget.LinearLayout.LayoutParams(
				-1,
				-2
				);
				titleParams.topMargin = cgGlass.px(4);
				
				card.addView(title, titleParams);
				
				String category = firstCategory(
				cgCatalog.text(item, "category", "genre", "genres")
				);
				
				String year = cgCatalog.text(item, "year", "release_year");
				
				String metaText = "";
				if (!category.isEmpty() && !year.isEmpty()) {
					metaText = category + "," + year;
				} else if (!category.isEmpty()) {
					metaText = category;
				} else if (!year.isEmpty()) {
					metaText = year;
				}
				
				android.widget.TextView meta =
				text(
				metaText,
				12,
				0xFF8FA0A8,
				false
				);
				
				meta.setSingleLine(true);
				meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
				meta.setVisibility(
				metaText.isEmpty()
				? android.view.View.GONE
				: android.view.View.VISIBLE
				);
				
				android.widget.LinearLayout.LayoutParams metaParams =
				new android.widget.LinearLayout.LayoutParams(
				-1,
				-2
				);
				metaParams.topMargin = cgGlass.px(2);
				
				card.addView(meta, metaParams);
				
				card.setOnTouchListener(
				new android.view.View.OnTouchListener() {
					@Override public boolean onTouch(
					android.view.View view,
					android.view.MotionEvent event
					) {
						int action = event.getActionMasked();
						
						if (action == android.view.MotionEvent.ACTION_DOWN) {
							CigramStage1InitProvider.preloadDetailsImages(
							SectionActivity.this,
							posterUrl,
							backdropUrl
							);
							
							view.animate()
							.scaleX(1f)
							.scaleY(1f)
							.setDuration(90L)
							.start();
						} else if (
						action == android.view.MotionEvent.ACTION_UP
						|| action == android.view.MotionEvent.ACTION_CANCEL
						) {
							view.animate()
							.scaleX(1f)
							.scaleY(1f)
							.setDuration(150L)
							.start();
						}
						
						return false;
					}
				}
				);
				
				card.setOnClickListener(
				new android.view.View.OnClickListener() {
					@Override public void onClick(android.view.View view) {
						CigramStage1InitProvider.openTitleDetails(
						SectionActivity.this,
						item,
						poster
						);
					}
				}
				);
				
				return card;
			}
			
			void renderNextBatch() {
				if (rendered >= visible.size()) return;
				
				int end = Math.min(
				visible.size(),
				rendered + 12   // 4 rows per step: 30 cards in one frame made every batch a visible hitch
				);
				
				while (rendered < end) {
					android.widget.LinearLayout row =
					new android.widget.LinearLayout(SectionActivity.this);
					
					row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
					// v10.36: the app has no supportsRtl, so an RTL row was drawn left-to-right and the
					// newest item landed at the far LEFT. Explicit LTR row, filled from the right:
					// column 0 (the newest / first item) is the right-most cell.
					row.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);
					row.setGravity(android.view.Gravity.TOP);
					
					android.view.View[] cells = new android.view.View[3];
					for (int column = 0; column < 3; column++) {
						if (rendered < end && rendered < visible.size()) {
							org.json.JSONObject item = visible.get(rendered);
							cells[column] = makeCard(item);
							rendered++;
						} else {
							android.widget.Space filler =
							new android.widget.Space(SectionActivity.this);
							
							filler.setVisibility(android.view.View.INVISIBLE);
							cells[column] = filler;
						}
					}
					
					for (int column = 2; column >= 0; column--) {
						android.widget.LinearLayout.LayoutParams cellParams =
						new android.widget.LinearLayout.LayoutParams(
						0,
						-2,
						1f
						);
						
						cellParams.leftMargin = cgGlass.px(4);
						cellParams.rightMargin = cgGlass.px(4);
						
						row.addView(
						cells[column],
						cellParams
						);
					}
					
					android.widget.LinearLayout.LayoutParams rowParams =
					new android.widget.LinearLayout.LayoutParams(
					-1,
					-2
					);
					
					rowParams.bottomMargin = cgGlass.px(16);
					
					cgSectionItems.addView(
					row,
					rowParams
					);
				}
			}
			
			void ensureScrollHook() {
				if (scrollHooked) return;
				
				final android.widget.ScrollView scroll =
				(android.widget.ScrollView)
				findViewById(R.id.vscroll_section);
				
				if (scroll == null) return;
				
				scrollHooked = true;
				
				scroll.getViewTreeObserver().addOnScrollChangedListener(
				new android.view.ViewTreeObserver.OnScrollChangedListener() {
					@Override public void onScrollChanged() {
						if (scroll.getChildCount() == 0) return;
						
						android.view.View child =
						scroll.getChildAt(0);
						
						int trigger =
						scroll.getScrollY()
						+ scroll.getHeight()
						+ cgGlass.px(900);   // start the next small batch earlier so the user never reaches the end
						
						if (trigger >= child.getBottom()) {
							renderNextBatch();
						}
					}
				}
				);
			}
			
			void showEmpty(String message) {
				cgSectionItems.removeAllViews();
				
				android.widget.TextView empty =
				text(
				message,
				15,
				0xFFB5C0C6,
				false
				);
				
				empty.setGravity(android.view.Gravity.CENTER);
				empty.setPadding(
				cgGlass.px(12),
				cgGlass.px(28),
				cgGlass.px(12),
				cgGlass.px(20)
				);
				
				cgSectionItems.addView(
				empty,
				new android.widget.LinearLayout.LayoutParams(
				-1,
				-2
				)
				);
			}
			
			java.util.ArrayList<org.json.JSONObject> select(String raw)
			throws org.json.JSONException {
				org.json.JSONArray items =
				cgCatalog.parse(raw);
					
					java.util.LinkedHashMap<
					String,
					org.json.JSONObject
					> unique =
					new java.util.LinkedHashMap<
					String,
					org.json.JSONObject
					>();
					
					final java.util.HashMap<String, Long> newest =
					new java.util.HashMap<String, Long>();
					
					for (int i = 0; i < items.length(); i++) {
						org.json.JSONObject item =
						items.optJSONObject(i);
						
						if (item == null || !belongsToSection(item)) {
							continue;
						}
						
						String itemKey = identity(item);
						
						if (itemKey.isEmpty()) {
							itemKey =
							kind(item)
							+ "|"
							+ cgCatalog.normalize(
							cgCatalog.text(
							item,
							"series_title",
							"title",
							"name"
							)
							)
							+ "|"
							+ cgCatalog.text(item, "year");
						}
						
						long itemRank = CigramStage1InitProvider.itemRecency(item);
						Long bestRank = newest.get(itemKey);
						if (bestRank == null || itemRank > bestRank.longValue()) {
							newest.put(itemKey, Long.valueOf(itemRank));
						}
						
						if (!unique.containsKey(itemKey)) {
							unique.put(itemKey, item);
						}
					}
					
					// v10.36: newest first, like the home rows (the server order is not guaranteed);
					// an item added later shows at the start (far right). Equal ranks keep server order.
					java.util.ArrayList<String> orderedKeys =
					new java.util.ArrayList<String>(unique.keySet());
					java.util.Collections.sort(
					orderedKeys,
					new java.util.Comparator<String>() {
						@Override public int compare(String a, String b) {
							long ra = newest.get(a).longValue();
							long rb = newest.get(b).longValue();
							return ra == rb ? 0 : (ra < rb ? 1 : -1);
						}
					}
					);
					
					java.util.ArrayList<org.json.JSONObject> ordered =
				new java.util.ArrayList<org.json.JSONObject>();
				for (String orderedKey : orderedKeys) {
					ordered.add(unique.get(orderedKey));
				}
				return ordered;
			}

			/** v11: builds the grid on the UI thread from an already-parsed list (parsing stays off the main thread). */
			void bind(String raw, java.util.ArrayList<org.json.JSONObject> ordered) {
				if (raw == null || ordered == null) return;
				if (
				raw.equals(shown)
				&& !visible.isEmpty()
				&& cgSectionItems.getChildCount() > 0
				) {
					return;
				}
				visible.clear();
				visible.addAll(ordered);
				shown = raw;
				rendered = 0;
				cgSectionItems.removeAllViews();
				if (visible.isEmpty()) {
					showEmpty("لا يوجد محتوى في هذا القسم حاليًا");
					return;
				}
				renderNextBatch();
				ensureScrollHook();
			}

			/** Convenience: parse + bind on the current thread. Call off the main thread for large catalogues. */
			void render(String raw) {
				if (raw == null || raw.trim().isEmpty()) return;
				if (raw.equals(shown) && !visible.isEmpty() && cgSectionItems.getChildCount() > 0) return;
				try {
					bind(raw, select(raw));
				} catch (Exception error) {
					android.util.Log.e("CigramSection", "Failed to render section", error);
					showEmpty("تعذر عرض محتوى القسم");
				}
			}
		}
		
		final CigramSection cgSection =
		new CigramSection();
		
		// v11: no synchronous catalogue read/parse on the UI thread here — it froze «المزيد» on open.
		// cgRequest.load() shows the loading label and renders cache-first off the main thread.
		
		class CigramRequest {
			boolean loading = false;

			String sectionCacheFirst() {
				try {
					String cached =
					CigramStage1InitProvider.cachedCatalogJson(SectionActivity.this);
					if (cached == null || cached.trim().isEmpty()) return null;
					final String cachedRaw = CigramTitles.normalizeCatalog(cached);
					if (cachedRaw.equals(cgSection.shown)) return cachedRaw;
					final java.util.ArrayList<org.json.JSONObject> ordered =
					cgSection.select(cachedRaw);
					runOnUiThread(new Runnable() {
						@Override public void run() {
							if (!isFinishing() && !isDestroyed()) {
								cgSection.bind(cachedRaw, ordered);
							}
						}
					});
					return cachedRaw;
				} catch (Throwable ignored) {
					return null;
				}
			}
			
			void load() {
				if (loading) {
					return;
				}
				
				loading = true;
				
				if (
				cgSection.shown.isEmpty()
				) {
					cgSectionItems.removeAllViews();
					
					android.widget.TextView message =
					cgGlass.label(
					"جارٍ تحميل محتوى القسم…",
					15
					);
					
					message.setPadding(
					cgGlass.px(12),
					cgGlass.px(28),
					cgGlass.px(12),
					cgGlass.px(12)
					);
					
					cgSectionItems.addView(
					message
					);
				}
				
				new Thread(
				new Runnable() {
					@Override public void run() {
						java.net.HttpURLConnection connection =
						null;

						// v11: cache-first — paint the locally cached catalogue immediately (parsed on this
						// background thread) so «المزيد» opens instantly; the network refresh below only
						// reparses + rebinds when the content actually changed.
						final String cachedRaw = sectionCacheFirst();

						try {
							// Sections reuse Home's hardened catalogue load: a network fetch that retries on
							// transient failures, then the cached copy as a safety net, so a momentary blip
							// never leaves the section blank. (connection stays null; the finally below no-ops.)
							final String source =
							CigramStage1InitProvider.loadCatalogJsonBlocking(
							SectionActivity.this
							);
							
							if (source == null || source.trim().isEmpty()) {
								throw new java.io.IOException(
								"no catalog"
								);
							}
							
							final String raw =
							CigramTitles.normalizeCatalog(source);
							
							CigramStage1InitProvider.prepareAndStoreCatalogAsync(
							SectionActivity.this,
							raw
							);

							// Only reparse + rebind when the server content differs from the cache we already
							// painted. Unchanged content avoids a full grid rebuild.
							if (cachedRaw != null && raw.equals(cachedRaw)) {
								runOnUiThread(new Runnable() {
									@Override public void run() { loading = false; }
								});
							} else {
								final java.util.ArrayList<org.json.JSONObject> ordered =
								cgSection.select(raw);
								runOnUiThread(new Runnable() {
									@Override public void run() {
										loading = false;
										if (!isFinishing() && !isDestroyed()) {
											cgSection.bind(raw, ordered);
										}
									}
								});
							}
						} catch (
						final Exception error
						) {
							android.util.Log.e(
							"CigramSection",
							"Catalog request failed",
							error
							);
							
							runOnUiThread(
							new Runnable() {
								@Override
								public void run() {
									loading =
									false;
									
									if (
									isFinishing()
									|| isDestroyed()
									) {
										return;
									}
									
									// إذا يوجد محتوى محفوظ، أبقه ظاهرًا.
									if (
									!cgSection.shown
									.isEmpty()
									) {
										return;
									}
									
									cgSectionItems.removeAllViews();
									
									android.widget.LinearLayout errorBox =
									new android.widget.LinearLayout(
									SectionActivity.this
									);
									
									errorBox.setOrientation(
									android.widget.LinearLayout.VERTICAL
									);
									
									errorBox.setGravity(
									android.view.Gravity.CENTER
									);
									
									errorBox.setPadding(
									cgGlass.px(12),
									cgGlass.px(28),
									cgGlass.px(12),
									cgGlass.px(12)
									);
									
									android.widget.TextView message =
									cgGlass.label(
									"تعذر تحميل القسم. تحقق من الاتصال",
									15
									);
									
									message.setGravity(
									android.view.Gravity.CENTER
									);
									
									errorBox.addView(
									message,
									new android.widget.LinearLayout.LayoutParams(
									-1,
									-2
									)
									);
									
									android.widget.TextView retry =
									cgGlass.label(
									"إعادة المحاولة",
									16
									);
									
									retry.setGravity(
									android.view.Gravity.CENTER
									);
									
									cgGlass.bind(
									retry,
									16,
									true
									);
									
									cgGlass.press(
									retry
									);
									
									android.widget.LinearLayout.LayoutParams retryParams =
									new android.widget.LinearLayout.LayoutParams(
									cgGlass.px(170),
									cgGlass.px(48)
									);
									
									retryParams.topMargin =
									cgGlass.px(16);
									
									errorBox.addView(
									retry,
									retryParams
									);
									
									retry.setOnClickListener(
									new android.view.View.OnClickListener() {
										@Override
										public void onClick(
										android.view.View view
										) {
											load();
										}
									}
									);
									
									cgSectionItems.addView(
									errorBox
									);
								}
							}
							);
						} finally {
							if (
							connection != null
							) {
								connection.disconnect();
							}
						}
					}
				},
				"cigram-section"
				).start();
			}
		}
		
		final CigramRequest cgRequest =
		new CigramRequest();
		
		cgRequest.load();
		
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
