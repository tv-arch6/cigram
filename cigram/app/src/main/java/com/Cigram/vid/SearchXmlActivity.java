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
import android.text.Editable;
import android.text.TextWatcher;
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
import java.util.HashMap;
import java.util.regex.*;
import org.json.*;

public class SearchXmlActivity extends AppCompatActivity {
	
	/** One background thread for parsing the search index (never the main thread). */
	private static final java.util.concurrent.ExecutorService CG_SEARCH_PARSE = CigramPool.fixed(1, "search-parse");
	
	private SearchXmlBinding binding;
	private HashMap<String, Object> search_source = new HashMap<>();
	private HashMap<String, Object> search_results = new HashMap<>();
	private String search_json = "";
	
	private RequestNetwork request_search;
	private RequestNetwork.RequestListener _request_search_request_listener;
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		binding = SearchXmlBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		initialize(_savedInstanceState);
		
		CigramAdManager.initialize(getApplicationContext()); // self-throttled, background thread, consent-gated
		
		initializeLogic();
		
		// Focus must have somewhere to go besides the search field, otherwise clearFocus() hands it straight back.
		try {
			View cgContent = findViewById(android.R.id.content);
			if (cgContent != null) {
				cgContent.setFocusable(true);
				cgContent.setFocusableInTouchMode(true);
			}
		} catch (Throwable ignored) { }
	}
	
	/** Any tap outside the search field drops its focus and hides the soft keyboard; the tap itself still reaches its target. */
	@Override
	public boolean dispatchTouchEvent(MotionEvent ev) {
		if (ev != null && ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
			try {
				View focused = getCurrentFocus();
				if (focused instanceof EditText) {
					int[] at = new int[2];
					focused.getLocationOnScreen(at);
					float x = ev.getRawX();
					float y = ev.getRawY();
					boolean inside = x >= at[0] && x <= at[0] + focused.getWidth()
					&& y >= at[1] && y <= at[1] + focused.getHeight();
					if (!inside) cgReleaseSearchFocus(focused);
				}
			} catch (Throwable ignored) { }
		}
		return super.dispatchTouchEvent(ev);
	}
	
	private void cgReleaseSearchFocus(View focused) {
		try {
			android.view.inputmethod.InputMethodManager imm =
			(android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
			if (imm != null) imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
		} catch (Throwable ignored) { }
		focused.clearFocus();
		View content = findViewById(android.R.id.content);
		if (content != null) content.requestFocus();
	}
	
	@Override
	protected void onPause() {
		super.onPause();
		try {
			View focused = getCurrentFocus();
			if (focused instanceof EditText) cgReleaseSearchFocus(focused);
		} catch (Throwable ignored) { }
	}
	
	private void initialize(Bundle _savedInstanceState) {
		request_search = new RequestNetwork(this);
		
		binding.edittextSearch.addTextChangedListener(new TextWatcher() {
			@Override
			public void onTextChanged(CharSequence _param1, int _param2, int _param3, int _param4) {
				final String _charSeq = _param1.toString();
				// A single TextWatcher is installed by CigramSearch in initializeLogic.
				
			}
			
			@Override
			public void beforeTextChanged(CharSequence _param1, int _param2, int _param3, int _param4) {
				
			}
			
			@Override
			public void afterTextChanged(Editable _param1) {
				
			}
		});
		
		_request_search_request_listener = new RequestNetwork.RequestListener() {
			@Override
			public void onResponse(String _param1, String _param2, HashMap<String, Object> _param3) {
				final String _tag = _param1;
				final String _response = _param2;
				final HashMap<String, Object> _responseHeaders = _param3;
				// CIGRAM: network callback completed; search data is now ready or cache fallback will be rendered.
				{android.view.ViewGroup root=(android.view.ViewGroup)findViewById(R.id.linear_root_search);if(root!=null){android.view.View loading=root.findViewWithTag("cigram_search_loading_state");if(loading!=null)root.removeView(loading);}}
				class CigramCatalog {
					String text(org.json.JSONObject o,String... keys) {
						if(o==null)return "";
						for(String key:keys) {
							Object v=o.opt(key);
							if(v!=null && v!=org.json.JSONObject.NULL && !String.valueOf(v).trim().isEmpty())return String.valueOf(v).trim();
						}
						return "";
					}
					String normalize(String s) { return CigramSmartSearch.normalize(s); }
					String type(org.json.JSONObject o,String inherited) {
						String t=normalize(text(o,"type","content_type","media_type"));
						if(t.equals("series")||t.equals("tv")||t.equals("show")||t.contains("مسلسل"))return "series";
						if(t.equals("movie")||t.equals("film")||t.equals("فيلم"))return "movie";
						if(!text(o,"season","episode","series_title","series_id").isEmpty()
						||o.has("seasons")||o.has("episodes")||o.has("series_episodes_json"))return "series";
						return inherited.equals("series")?"series":"movie";
					}
					void episodes(Object node,org.json.JSONObject parent,String season,org.json.JSONArray out,int depth) throws org.json.JSONException {
						if(node==null||node==org.json.JSONObject.NULL||depth>12)return;
						if(node instanceof String) {
							String s=((String)node).trim();
							if(s.startsWith("[")||s.startsWith("{"))episodes(new org.json.JSONTokener(s).nextValue(),parent,season,out,depth+1);
						} else if(node instanceof org.json.JSONArray) {
							org.json.JSONArray a=(org.json.JSONArray)node;
							for(int i=0;i<a.length();i++)episodes(a.opt(i),parent,season,out,depth+1);
						} else if(node instanceof org.json.JSONObject) {
							org.json.JSONObject o=(org.json.JSONObject)node;
							String sn=text(o,"season","season_number"); if(sn.isEmpty())sn=season;
							if(o.has("seasons"))episodes(o.opt("seasons"),parent,sn,out,depth+1);
							else if(o.has("episodes")) {
								if(sn.isEmpty())sn=text(o,"number");
								episodes(o.opt("episodes"),parent,sn,out,depth+1);
							} else {
								org.json.JSONObject e=new org.json.JSONObject(o.toString());
								for(String key:new String[]{"poster","series_poster","backdrop","category","year","imdb","description"})
								if(text(e,key).isEmpty()&&!text(parent,key).isEmpty())e.put(key,parent.opt(key));
								e.put("type","series"); e.put("series_title",text(parent,"series_title","title","name"));
								e.put("series_id",text(parent,"series_id","id"));
								if(text(e,"season").isEmpty())e.put("season",sn.isEmpty()?"1":sn);
								if(text(e,"episode").isEmpty())e.put("episode",text(e,"episode_number","number"));
								if(text(e,"video").isEmpty())e.put("video",text(e,"link","url","movie_url","video_url"));
								out.put(e);
							}
						}
					}
					void collect(Object node,String inherited,org.json.JSONArray out,int depth) throws org.json.JSONException {
						if(node==null||node==org.json.JSONObject.NULL||depth>12)return;
						if(node instanceof org.json.JSONArray) {
							org.json.JSONArray a=(org.json.JSONArray)node;
							for(int i=0;i<a.length();i++)collect(a.opt(i),inherited,out,depth+1);
							return;
						}
						if(!(node instanceof org.json.JSONObject))return;
						org.json.JSONObject o=(org.json.JSONObject)node;
						boolean collection=false;
						for(String key:new String[]{"movies","series","items","data","results","content","sections","catalog"}) {
							Object value=o.opt(key);
							collection=collection||value instanceof org.json.JSONArray||value instanceof org.json.JSONObject;
							if(value instanceof String) {
								String encoded=((String)value).trim();
								collection=collection||encoded.startsWith("[")||encoded.startsWith("{");
							}
						}
						boolean record=!collection&&!text(o,"title","name","series_title").isEmpty()
						&& (o.has("id")||o.has("poster")||o.has("video")||o.has("link")||o.has("type")||o.has("episodes")||o.has("seasons"));
						if(record) {
							org.json.JSONObject item=new org.json.JSONObject(o.toString());
							String kind=type(item,inherited); item.put("type",kind);
							String title=kind.equals("series")?text(item,"series_title","title","name"):text(item,"title","name");
							item.put("title",title);
							if(text(item,"poster").isEmpty())item.put("poster",text(item,"series_poster","season_poster","image","backdrop"));
							if(text(item,"video").isEmpty())item.put("video",text(item,"link","url","video_url","movie_url"));
							if(text(item,"category").isEmpty())item.put("category",text(item,"genre","genres"));
							if(text(item,"imdb").isEmpty())item.put("imdb",text(item,"rating"));
							org.json.JSONArray eps=new org.json.JSONArray();
							if(kind.equals("series")) {
								for(String key:new String[]{"series_episodes_json","episodes","seasons"})
								if(item.has(key))episodes(item.opt(key),item,"",eps,0);
								if(eps.length()>0)item.put("series_episodes_json",eps.toString());
							}
							out.put(item); return;
						}
						for(String key:new String[]{"movies","series","items","data","results","content","sections","catalog"}) {
							Object child=o.opt(key);
							if(child instanceof String) {
								String s=((String)child).trim();
								if(s.startsWith("[")||s.startsWith("{"))child=new org.json.JSONTokener(s).nextValue();
							}
							if(child!=null)collect(child,key.equals("series")?"series":inherited,out,depth+1);
						}
					}
					String key(org.json.JSONObject item) {
						String type=text(item,"type");
						if(type.equals("series")) {
							String id=text(item,"series_id");
							return "series|"+(id.isEmpty()?normalize(text(item,"series_title","title")):id);
						}
						String id=text(item,"id");
						return "movie|"+(id.isEmpty()?normalize(text(item,"title"))+"|"+text(item,"year"):id);
					}
					org.json.JSONArray parse(String json) throws org.json.JSONException {
						org.json.JSONArray flat=new org.json.JSONArray();
						Object root=new org.json.JSONTokener(json).nextValue();
						if(root instanceof org.json.JSONObject) {
							org.json.JSONObject object=(org.json.JSONObject)root;
							if(!object.optBoolean("ok",true))throw new org.json.JSONException("Catalog request failed");
							boolean known=!text(object,"title","name","series_title").isEmpty();
							for(String key:new String[]{"movies","series","items","data","results","content","sections","catalog"})known=known||object.has(key);
							if(!known)throw new org.json.JSONException("Unrecognized catalog");
						} else if(!(root instanceof org.json.JSONArray))throw new org.json.JSONException("Invalid catalog");
						collect(root,"",flat,0);
						java.util.LinkedHashMap<String,org.json.JSONObject> unique=new java.util.LinkedHashMap<String,org.json.JSONObject>();
						java.util.LinkedHashMap<String,java.util.LinkedHashMap<String,org.json.JSONObject>> eps=
						new java.util.LinkedHashMap<String,java.util.LinkedHashMap<String,org.json.JSONObject>>();
						for(int i=0;i<flat.length();i++) {
							org.json.JSONObject item=flat.getJSONObject(i); String k=key(item);
							org.json.JSONObject existing=unique.get(k);
							if(existing==null)unique.put(k,new org.json.JSONObject(item.toString()));
							else {
								java.util.Iterator<String> keys=item.keys();
								while(keys.hasNext()){String name=keys.next();if(text(existing,name).isEmpty())existing.put(name,item.opt(name));}
							}
							if(text(item,"type").equals("series")) {
								if(!eps.containsKey(k))eps.put(k,new java.util.LinkedHashMap<String,org.json.JSONObject>());
								org.json.JSONArray list=new org.json.JSONArray();
								String nested=text(item,"series_episodes_json");
								if(!nested.isEmpty())list=new org.json.JSONArray(nested);
								if(!text(item,"episode").isEmpty()||(!text(item,"video").isEmpty()&&list.length()==0))list.put(item);
								for(int n=0;n<list.length();n++) {
									org.json.JSONObject e=list.optJSONObject(n);if(e==null)continue;
									String ek=text(e,"season")+"|"+text(e,"episode")+"|"+text(e,"video","link");
									eps.get(k).put(ek,e);
								}
							}
						}
						org.json.JSONArray result=new org.json.JSONArray();
						for(java.util.Map.Entry<String,org.json.JSONObject> entry:unique.entrySet()) {
							if(eps.containsKey(entry.getKey())&&!eps.get(entry.getKey()).isEmpty()) {
								org.json.JSONArray list=new org.json.JSONArray();
								for(org.json.JSONObject e:eps.get(entry.getKey()).values())list.put(e);
								entry.getValue().put("series_episodes_json",list.toString());
							}
							result.put(entry.getValue());
						}
						return result;
					}
					boolean matches(org.json.JSONObject item,String query) {
						String q=normalize(query);if(q.isEmpty())return true;
						StringBuilder searchable=new StringBuilder();
						for(String key:new String[]{"title","series_title","original_title","original_name","title_ar","title_en","name","aliases","alternative_titles","alt_titles","category","genre","genres","year","description","overview","actors","cast","stars","director"})
						searchable.append(' ').append(text(item,key));
						searchable.append(type(item,"").equals("series")?" مسلسل مسلسلات series":" فيلم افلام movie");
						String haystack=normalize(searchable.toString());
						for(String token:q.split(" "))if(!haystack.contains(token))return false;
						return true;
					}
					int score(org.json.JSONObject item,String query) {
						String nq=normalize(query);if(nq.isEmpty())return 0;
						String title=normalize(text(item,"series_title","title","name","title_ar","title_en"));
						String alt=normalize(text(item,"original_title","original_name","aliases","alternative_titles","alt_titles"));
						int s=0;
						if(title.equals(nq))s=1000;
						else if(title.startsWith(nq))s=800;
						else if(title.contains(nq))s=600;
						else {
							boolean all=true;
							for(String tk:nq.split(" "))if(!title.contains(tk)){all=false;break;}
							if(all)s=450;
						}
						if(s==0)s=alt.contains(nq)?300:100;
						try{s+=(int)Math.min(9f,Float.parseFloat(text(item,"imdb").replace(',','.')));}catch(Exception ignored){}
						return s;
					}
				}
				final CigramCatalog cgCatalog=new CigramCatalog();
				
				try {
					String incoming=CigramTitles.normalizeCatalog(_response==null?"":_response.trim());
					cgCatalog.parse(incoming);
					search_json=incoming;
					CigramStage1InitProvider.prepareAndStoreCatalogAsync(SearchXmlActivity.this,incoming);
				} catch(Exception error) {
					android.widget.Toast.makeText(getApplicationContext(),"تعذر قراءة بيانات البحث الجديدة",0).show();
				}
				android.view.View input=findViewById(R.id.edittext_search);
				if(input!=null && input.getTag() instanceof Runnable)((Runnable)input.getTag()).run();
				
			}
			
			@Override
			public void onErrorResponse(String _param1, String _param2) {
				final String _tag = _param1;
				final String _message = _param2;
				// CIGRAM: network callback completed; search data is now ready or cache fallback will be rendered.
				{android.view.ViewGroup root=(android.view.ViewGroup)findViewById(R.id.linear_root_search);if(root!=null){android.view.View loading=root.findViewWithTag("cigram_search_loading_state");if(loading!=null)root.removeView(loading);}}
				
				android.view.View input=findViewById(R.id.edittext_search);
				if(input!=null && input.getTag() instanceof Runnable)((Runnable)input.getTag()).run();
				android.widget.Toast.makeText(getApplicationContext(),search_json==null||search_json.trim().isEmpty()?"تعذر تحميل البحث. تحقق من الاتصال":"تعذر التحديث؛ البحث يستخدم آخر بيانات محفوظة",0).show();
				
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
		class CigramCatalog {
			String text(org.json.JSONObject o,String... keys) {
				if(o==null)return "";
				for(String key:keys) {
					Object v=o.opt(key);
					if(v!=null && v!=org.json.JSONObject.NULL && !String.valueOf(v).trim().isEmpty())return String.valueOf(v).trim();
				}
				return "";
			}
			String normalize(String s) { return CigramSmartSearch.normalize(s); }
			String type(org.json.JSONObject o,String inherited) {
				String t=normalize(text(o,"type","content_type","media_type"));
				if(t.equals("series")||t.equals("tv")||t.equals("show")||t.contains("مسلسل"))return "series";
				if(t.equals("movie")||t.equals("film")||t.equals("فيلم"))return "movie";
				if(!text(o,"season","episode","series_title","series_id").isEmpty()
				||o.has("seasons")||o.has("episodes")||o.has("series_episodes_json"))return "series";
				return inherited.equals("series")?"series":"movie";
			}
			void episodes(Object node,org.json.JSONObject parent,String season,org.json.JSONArray out,int depth) throws org.json.JSONException {
				if(node==null||node==org.json.JSONObject.NULL||depth>12)return;
				if(node instanceof String) {
					String s=((String)node).trim();
					if(s.startsWith("[")||s.startsWith("{"))episodes(new org.json.JSONTokener(s).nextValue(),parent,season,out,depth+1);
				} else if(node instanceof org.json.JSONArray) {
					org.json.JSONArray a=(org.json.JSONArray)node;
					for(int i=0;i<a.length();i++)episodes(a.opt(i),parent,season,out,depth+1);
				} else if(node instanceof org.json.JSONObject) {
					org.json.JSONObject o=(org.json.JSONObject)node;
					String sn=text(o,"season","season_number"); if(sn.isEmpty())sn=season;
					if(o.has("seasons"))episodes(o.opt("seasons"),parent,sn,out,depth+1);
					else if(o.has("episodes")) {
						if(sn.isEmpty())sn=text(o,"number");
						episodes(o.opt("episodes"),parent,sn,out,depth+1);
					} else {
						org.json.JSONObject e=new org.json.JSONObject(o.toString());
						for(String key:new String[]{"poster","series_poster","backdrop","category","year","imdb","description"})
						if(text(e,key).isEmpty()&&!text(parent,key).isEmpty())e.put(key,parent.opt(key));
						e.put("type","series"); e.put("series_title",text(parent,"series_title","title","name"));
						e.put("series_id",text(parent,"series_id","id"));
						if(text(e,"season").isEmpty())e.put("season",sn.isEmpty()?"1":sn);
						if(text(e,"episode").isEmpty())e.put("episode",text(e,"episode_number","number"));
						if(text(e,"video").isEmpty())e.put("video",text(e,"link","url","movie_url","video_url"));
						out.put(e);
					}
				}
			}
			void collect(Object node,String inherited,org.json.JSONArray out,int depth) throws org.json.JSONException {
				if(node==null||node==org.json.JSONObject.NULL||depth>12)return;
				if(node instanceof org.json.JSONArray) {
					org.json.JSONArray a=(org.json.JSONArray)node;
					for(int i=0;i<a.length();i++)collect(a.opt(i),inherited,out,depth+1);
					return;
				}
				if(!(node instanceof org.json.JSONObject))return;
				org.json.JSONObject o=(org.json.JSONObject)node;
				boolean collection=false;
				for(String key:new String[]{"movies","series","items","data","results","content","sections","catalog"}) {
					Object value=o.opt(key);
					collection=collection||value instanceof org.json.JSONArray||value instanceof org.json.JSONObject;
					if(value instanceof String) {
						String encoded=((String)value).trim();
						collection=collection||encoded.startsWith("[")||encoded.startsWith("{");
					}
				}
				boolean record=!collection&&!text(o,"title","name","series_title").isEmpty()
				&& (o.has("id")||o.has("poster")||o.has("video")||o.has("link")||o.has("type")||o.has("episodes")||o.has("seasons"));
				if(record) {
					org.json.JSONObject item=new org.json.JSONObject(o.toString());
					String kind=type(item,inherited); item.put("type",kind);
					String title=kind.equals("series")?text(item,"series_title","title","name"):text(item,"title","name");
					item.put("title",title);
					if(text(item,"poster").isEmpty())item.put("poster",text(item,"series_poster","season_poster","image","backdrop"));
					if(text(item,"video").isEmpty())item.put("video",text(item,"link","url","video_url","movie_url"));
					if(text(item,"category").isEmpty())item.put("category",text(item,"genre","genres"));
					if(text(item,"imdb").isEmpty())item.put("imdb",text(item,"rating"));
					org.json.JSONArray eps=new org.json.JSONArray();
					if(kind.equals("series")) {
						for(String key:new String[]{"series_episodes_json","episodes","seasons"})
						if(item.has(key))episodes(item.opt(key),item,"",eps,0);
						if(eps.length()>0)item.put("series_episodes_json",eps.toString());
					}
					out.put(item); return;
				}
				for(String key:new String[]{"movies","series","items","data","results","content","sections","catalog"}) {
					Object child=o.opt(key);
					if(child instanceof String) {
						String s=((String)child).trim();
						if(s.startsWith("[")||s.startsWith("{"))child=new org.json.JSONTokener(s).nextValue();
					}
					if(child!=null)collect(child,key.equals("series")?"series":inherited,out,depth+1);
				}
			}
			String key(org.json.JSONObject item) {
				String type=text(item,"type");
				if(type.equals("series")) {
					String id=text(item,"series_id");
					return "series|"+(id.isEmpty()?normalize(text(item,"series_title","title")):id);
				}
				String id=text(item,"id");
				return "movie|"+(id.isEmpty()?normalize(text(item,"title"))+"|"+text(item,"year"):id);
			}
			org.json.JSONArray parse(String json) throws org.json.JSONException {
				org.json.JSONArray flat=new org.json.JSONArray();
				Object root=new org.json.JSONTokener(json).nextValue();
				if(root instanceof org.json.JSONObject) {
					org.json.JSONObject object=(org.json.JSONObject)root;
					if(!object.optBoolean("ok",true))throw new org.json.JSONException("Catalog request failed");
					boolean known=!text(object,"title","name","series_title").isEmpty();
					for(String key:new String[]{"movies","series","items","data","results","content","sections","catalog"})known=known||object.has(key);
					if(!known)throw new org.json.JSONException("Unrecognized catalog");
				} else if(!(root instanceof org.json.JSONArray))throw new org.json.JSONException("Invalid catalog");
				collect(root,"",flat,0);
				java.util.LinkedHashMap<String,org.json.JSONObject> unique=new java.util.LinkedHashMap<String,org.json.JSONObject>();
				java.util.LinkedHashMap<String,java.util.LinkedHashMap<String,org.json.JSONObject>> eps=
				new java.util.LinkedHashMap<String,java.util.LinkedHashMap<String,org.json.JSONObject>>();
				for(int i=0;i<flat.length();i++) {
					org.json.JSONObject item=flat.getJSONObject(i); String k=key(item);
					org.json.JSONObject existing=unique.get(k);
					if(existing==null)unique.put(k,new org.json.JSONObject(item.toString()));
					else {
						java.util.Iterator<String> keys=item.keys();
						while(keys.hasNext()){String name=keys.next();if(text(existing,name).isEmpty())existing.put(name,item.opt(name));}
					}
					if(text(item,"type").equals("series")) {
						if(!eps.containsKey(k))eps.put(k,new java.util.LinkedHashMap<String,org.json.JSONObject>());
						org.json.JSONArray list=new org.json.JSONArray();
						String nested=text(item,"series_episodes_json");
						if(!nested.isEmpty())list=new org.json.JSONArray(nested);
						if(!text(item,"episode").isEmpty()||(!text(item,"video").isEmpty()&&list.length()==0))list.put(item);
						for(int n=0;n<list.length();n++) {
							org.json.JSONObject e=list.optJSONObject(n);if(e==null)continue;
							String ek=text(e,"season")+"|"+text(e,"episode")+"|"+text(e,"video","link");
							eps.get(k).put(ek,e);
						}
					}
				}
				org.json.JSONArray result=new org.json.JSONArray();
				for(java.util.Map.Entry<String,org.json.JSONObject> entry:unique.entrySet()) {
					if(eps.containsKey(entry.getKey())&&!eps.get(entry.getKey()).isEmpty()) {
						org.json.JSONArray list=new org.json.JSONArray();
						for(org.json.JSONObject e:eps.get(entry.getKey()).values())list.put(e);
						entry.getValue().put("series_episodes_json",list.toString());
					}
					result.put(entry.getValue());
				}
				return result;
			}
			// The searchable text of a title never changes while the catalog snapshot lives, so it is folded once
			// (it used to be rebuilt and re-folded for every item on every keystroke, twice).
			final java.util.Map<org.json.JSONObject,String> cgHaystacks=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<org.json.JSONObject,String>());
			String haystackOf(org.json.JSONObject item) {
				String haystack=cgHaystacks.get(item);
				if(haystack!=null)return haystack;
				StringBuilder searchable=new StringBuilder();
				for(String key:new String[]{"title","series_title","original_title","original_name","title_ar","title_en","name","aliases","alternative_titles","alt_titles","category","genre","genres","year","description","overview","actors","cast","stars","director"})
				searchable.append(' ').append(text(item,key));
				searchable.append(type(item,"").equals("series")?" مسلسل مسلسلات series":" فيلم افلام movie");
				haystack=normalize(searchable.toString());
				cgHaystacks.put(item,haystack);
				return haystack;
			}
			boolean matches(org.json.JSONObject item,String query) {
				String q=normalize(query);if(q.isEmpty())return true;
				String haystack=haystackOf(item);
				for(String token:q.split(" "))if(!haystack.contains(token))return false;
				return true;
			}
			int score(org.json.JSONObject item,String query) {
				String nq=normalize(query);if(nq.isEmpty())return 0;
				String title=normalize(text(item,"series_title","title","name","title_ar","title_en"));
				String alt=normalize(text(item,"original_title","original_name","aliases","alternative_titles","alt_titles"));
				int s=0;
				if(title.equals(nq))s=1000;
				else if(title.startsWith(nq))s=800;
				else if(title.contains(nq))s=600;
				else {
					boolean all=true;
					for(String tk:nq.split(" "))if(!title.contains(tk)){all=false;break;}
					if(all)s=450;
				}
				if(s==0)s=alt.contains(nq)?300:100;
				try{s+=(int)Math.min(9f,Float.parseFloat(text(item,"imdb").replace(',','.')));}catch(Exception ignored){}
				return s;
			}
		}
		final CigramCatalog cgCatalog=new CigramCatalog();
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
		final android.widget.EditText cgInput=(android.widget.EditText)findViewById(R.id.edittext_search);
		final android.widget.LinearLayout cgResults=(android.widget.LinearLayout)findViewById(R.id.linear_search_results);
		final android.widget.LinearLayout cgRoot=(android.widget.LinearLayout)findViewById(R.id.linear_root_search);
		final android.widget.LinearLayout cgTabs=(android.widget.LinearLayout)findViewById(R.id.linear_search_tabs);
		final android.view.View cgFilter=findViewById(R.id.linear_filter_box);
		final android.widget.TextView cgEmpty=(android.widget.TextView)findViewById(R.id.textview_empty_search);
		final android.widget.ImageView cgEmptyIcon=(android.widget.ImageView)findViewById(R.id.imageview_empty_search);
		final android.content.SharedPreferences cgFilters=getSharedPreferences("cigram_search_filters",MODE_PRIVATE);
		final android.content.SharedPreferences cgRecent=getSharedPreferences("cigram_search_recent",MODE_PRIVATE);
		final android.os.Handler cgSearchHandler=new android.os.Handler(android.os.Looper.getMainLooper());
		final String[] cgType={"all"};
		final android.widget.TextView[] cgTabViews={
			(android.widget.TextView)findViewById(R.id.textview_tab_all),
			(android.widget.TextView)findViewById(R.id.textview_tab_movies),
			(android.widget.TextView)findViewById(R.id.textview_tab_series)};
		cgRoot.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
		cgRoot.setBackgroundColor(0xFF111113);
		cgTabs.setBackgroundColor(0x00000000); cgTabs.setDividerDrawable(null);
		cgTabs.setShowDividers(android.widget.LinearLayout.SHOW_DIVIDER_NONE);
		cgTabs.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
		cgTabs.setVisibility(android.view.View.GONE);
		cgGlass.bind(findViewById(R.id.linear_search_top),28,true);
		cgInput.setBackgroundColor(0x00000000); cgInput.setTextColor(0xFFFFFFFF); cgInput.setHintTextColor(0x88FFFFFF);
		cgInput.setSingleLine(true); cgInput.setTypeface(cgGlass.font); cgInput.setTextSize(16);
		cgInput.setGravity(android.view.Gravity.RIGHT|android.view.Gravity.CENTER_VERTICAL);
		cgInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
		cgEmptyIcon.setVisibility(android.view.View.GONE); cgEmpty.setTextColor(0xAAFFFFFF);
		cgResults.setOrientation(1); cgResults.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
		cgResults.setPadding(cgGlass.px(8),cgGlass.px(12),cgGlass.px(8),cgGlass.px(20));
		findViewById(R.id.linear_search_empty).getLayoutParams().height=-2;
		findViewById(R.id.linear_search_empty).setVisibility(android.view.View.VISIBLE);
		cgGlass.bind(cgFilter,24,true); cgGlass.press(cgFilter);
		((android.widget.ImageView)findViewById(R.id.imageview_filter)).setColorFilter(0xFFFFFFFF);
		
		class CigramSearch implements java.lang.Runnable {
			String source="";
			org.json.JSONArray items=new org.json.JSONArray();
			int generation;
			boolean failed;
			/** 2026: a large index is parsed on a background thread (it froze the page on entry and after every
			 *  catalogue refresh); the previous results stay on screen and the search re-runs when it is ready. */
			String pending=null;
			void reload() {
				String value=search_json==null?"":search_json.trim();
				if(value.equals(source)||value.equals(pending))return;
				if(value.length()<24000){
					try { items=cgCatalog.parse(value); source=value; failed=false; }
					catch(Exception error){failed=true;}
					return;
				}
				pending=value;
				final String job=value;
				CG_SEARCH_PARSE.execute(new java.lang.Runnable(){@Override public void run(){
					org.json.JSONArray parsed=null;
					try{parsed=cgCatalog.parse(job);}catch(Throwable error){parsed=null;}
					final org.json.JSONArray result=parsed;
					cgSearchHandler.post(new java.lang.Runnable(){@Override public void run(){
						if(isFinishing()||isDestroyed()||!job.equals(pending))return;
						pending=null;
						if(result!=null){items=result;source=job;failed=false;}else failed=true;
						queue();
					}});
				}});
			}
			boolean advanced() {
				return CigramSearchFilter.active(cgFilters);
			}
			// v10.68: number of titles a filter choice would show (live count on «عرض النتائج»)
			int countWith(String type,String year,String category,String ratingText) {
				reload();
				String query=cgInput.getText().toString().trim();
				float rating=number(ratingText);
				int n=0;
				java.util.HashSet<String> seen=new java.util.HashSet<String>();
				for(int i=0;i<items.length();i++) {
					org.json.JSONObject item=items.optJSONObject(i); if(item==null)continue;
					if(!type.equals("all")&&!type.equals(cgCatalog.text(item,"type")))continue;
					if(!CigramSearchFilter.yearMatches(year,cgCatalog.text(item,"year")))continue;
					if(!category.equals("all")&&!cgCatalog.normalize(cgCatalog.text(item,"category")).contains(cgCatalog.normalize(category)))continue;
					if(number(cgCatalog.text(item,"imdb"))<rating||!cgCatalog.matches(item,query))continue;
					if(!seen.add(cgCatalog.text(item,"type")+"|"+cgCatalog.normalize(cgCatalog.text(item,"series_title","title"))+"|"+cgCatalog.text(item,"year")))continue;
					n++;
				}
				return n;
			}
			float number(String s) {try{return Float.parseFloat(s.replace(',','.'));}catch(Exception e){return 0f;}}
			void tabs() {
				String[] types={"all","movie","series"};
				for(int i=0;i<cgTabViews.length;i++) {
					android.widget.TextView tab=cgTabViews[i]; boolean selected=types[i].equals(cgType[0]);
					tab.setTextColor(selected?0xFFFFFFFF:0xAAFFFFFF); tab.setTypeface(cgGlass.font);
					tab.setTextSize(14); tab.setGravity(android.view.Gravity.CENTER);
					tab.setBackgroundTintList(null); tab.setStateListAnimator(null); tab.setElevation(0);
					// No stroke or default ripple: neither edge can flash white.
					tab.setBackground(selected?cgGlass.surface(24,false):new android.graphics.drawable.ColorDrawable(0));
					tab.setSelected(selected); cgGlass.press(tab);
				}
				cgFilter.setContentDescription(advanced()?"الفلاتر: يوجد تحديد، اضغط للتعديل":"فلترة البحث");
				((android.widget.ImageView)findViewById(R.id.imageview_filter)).setColorFilter(advanced()?0xFF5DE8A4:0xFFFFFFFF);
			}
			void queue() {
				generation++; cgResults.animate().cancel(); cgResults.setAlpha(1f); cgResults.setTranslationY(0f);
				cgSearchHandler.removeCallbacks(this);
				if(cgInput.getText().toString().trim().isEmpty()) {
					final int token=generation;
					cgResults.animate().alpha(0f).translationY(cgGlass.px(8)).setDuration(160L)
					.withEndAction(new java.lang.Runnable(){@Override public void run(){if(token==generation)CigramSearch.this.run();}}).start();
				} else cgSearchHandler.postDelayed(this,200L);
			}
			void open(
			org.json.JSONObject item,
			android.widget.ImageView sharedPoster,
			String sourceImageUrl
			) {
				try {
					org.json.JSONArray recent=new org.json.JSONArray(cgRecent.getString("items","[]"));
					org.json.JSONArray next=new org.json.JSONArray(); next.put(item);
					for(int i=0;i<recent.length()&&next.length()<12;i++) {
						org.json.JSONObject old=recent.optJSONObject(i);
						if(old!=null&&!cgCatalog.key(old).equals(cgCatalog.key(item)))next.put(old);
					}
					cgRecent.edit().putString("items",next.toString()).apply();
				}catch(Exception ignored){}
				CigramStage1InitProvider.openTitleDetails(
				SearchXmlActivity.this, item, sharedPoster
				);
			}
			void card(final org.json.JSONObject item) {
				// Same visual language as SectionActivity ("المزيد").
				// v4.4: explicit LTR + physical order [details][poster] → poster on the RIGHT (the
				// reading start in Arabic) whether or not the app honours RTL (it has no supportsRtl).
				android.widget.LinearLayout row =
				new android.widget.LinearLayout(SearchXmlActivity.this);
				row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
				row.setGravity(android.view.Gravity.CENTER_VERTICAL);
				row.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);
				row.setPadding(
				cgGlass.px(14),
				cgGlass.px(9),
				cgGlass.px(9),
				cgGlass.px(9)
				);
				cgGlass.bind(row,18,true);
				cgGlass.press(row);
				
				final android.widget.ImageView poster =
				new android.widget.ImageView(SearchXmlActivity.this);
				poster.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
				cgGlass.bind(poster,14,false);
				CigramDesign.posterFrame(poster,14);
				
				android.widget.LinearLayout.LayoutParams posterParams =
				new android.widget.LinearLayout.LayoutParams(
				cgGlass.px(90),
				cgGlass.px(135)
				);
				posterParams.leftMargin = cgGlass.px(14);
				
				final String cgOpenPosterUrl = cgCatalog.text(item,"poster","series_poster","image");
				final String cgOpenBackdropUrl = cgCatalog.text(item,"backdrop","landscape_poster","source_backdrop");
				cgImage.load(
				cgOpenPosterUrl,
				poster,
				true
				);
				
				android.widget.LinearLayout detail =
				new android.widget.LinearLayout(SearchXmlActivity.this);
				detail.setOrientation(android.widget.LinearLayout.VERTICAL);
				detail.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
				detail.setGravity(android.view.Gravity.CENTER_VERTICAL);
				row.addView(
				detail,
				new android.widget.LinearLayout.LayoutParams(0,-1,1f)
				);
				row.addView(poster,posterParams);
				
				android.widget.TextView title =
				cgGlass.label(
				cgCatalog.text(item,"title","series_title","name"),
				16.5f
				);
				title.setGravity(android.view.Gravity.RIGHT|android.view.Gravity.CENTER_VERTICAL);
				title.setMaxLines(2);
				title.setEllipsize(android.text.TextUtils.TruncateAt.END);
				title.setTypeface(cgGlass.font,android.graphics.Typeface.BOLD);
				detail.addView(
				title,
				new android.widget.LinearLayout.LayoutParams(-1,-2)
				);
				
				String year = cgCatalog.text(item,"year");
				String category = cgCatalog.text(item,"category");
				String cgKind = cgCatalog.text(item,"type");
				cgKind = "series".equals(cgKind) ? "مسلسل" : ("movie".equals(cgKind) ? "فيلم" : "");
				String meta =
				year
				+ (year.isEmpty() || category.isEmpty() ? "" : " · ")
				+ category;
				if (!cgKind.isEmpty()) meta = cgKind + (meta.isEmpty() ? "" : " · " + meta);
				
				if (!meta.isEmpty()) {
					android.widget.TextView info = cgGlass.label(meta,12.5f);
					info.setGravity(android.view.Gravity.RIGHT|android.view.Gravity.CENTER_VERTICAL);
					info.setTextColor(CigramDesign.TEXT_2);
					info.setMaxLines(2);
					info.setEllipsize(android.text.TextUtils.TruncateAt.END);
					android.widget.LinearLayout.LayoutParams infoLp =
					new android.widget.LinearLayout.LayoutParams(-1,-2);
					infoLp.topMargin = cgGlass.px(8);
					detail.addView(info,infoLp);
				}
				
				String rating = cgCatalog.text(item,"imdb");
				if (!rating.isEmpty() && !"0".equals(rating) && !"0.0".equals(rating)) {
					// v4.4: same IMDb yellow as the poster cards, as a compact pill on the right
					android.widget.LinearLayout.LayoutParams scoreLp =
					new android.widget.LinearLayout.LayoutParams(-1,-2);
					scoreLp.topMargin = cgGlass.px(10);
					detail.addView(CigramDesign.alignRight(SearchXmlActivity.this,
					CigramDesign.imdbPill(SearchXmlActivity.this, rating)),scoreLp);
				}
				
				android.widget.LinearLayout.LayoutParams lp =
				new android.widget.LinearLayout.LayoutParams(-1,-2);
				lp.bottomMargin = cgGlass.px(10);
				cgResults.addView(row,lp);
				
				row.setOnTouchListener(
				new android.view.View.OnTouchListener() {
					@Override public boolean onTouch(android.view.View v,android.view.MotionEvent event) {
						int action=event.getActionMasked();
						if(action==android.view.MotionEvent.ACTION_DOWN) {
							CigramStage1InitProvider.preloadDetailsImages(
							SearchXmlActivity.this,cgOpenPosterUrl,cgOpenBackdropUrl
							);
							v.animate().scaleX(1f).scaleY(1f).setDuration(100L).start();
						} else if(action==android.view.MotionEvent.ACTION_UP||action==android.view.MotionEvent.ACTION_CANCEL) {
							v.animate().scaleX(1f).scaleY(1f).setDuration(190L).start();
						}
						return false;
					}
				}
				);
				row.setOnClickListener(
				new android.view.View.OnClickListener() {
					@Override
					public void onClick(android.view.View v) {
						open(item,poster,cgOpenPosterUrl);
					}
				}
				);
			}
			
			// v5.1 (Grouped results, RTL): in the "all" tab the hits become two horizontal rails,
			// movies and series; the movies/series tabs keep the full vertical list.
			android.view.View railCard(final org.json.JSONObject item) {
				android.widget.LinearLayout col=new android.widget.LinearLayout(SearchXmlActivity.this);
				col.setOrientation(android.widget.LinearLayout.VERTICAL);
				col.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
				final android.widget.ImageView poster=new android.widget.ImageView(SearchXmlActivity.this);
				poster.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
				cgGlass.bind(poster,14,false);
				CigramDesign.posterFrame(poster,14);
				col.addView(poster,new android.widget.LinearLayout.LayoutParams(-1,cgGlass.px(156)));
				final String posterUrl=cgCatalog.text(item,"poster","series_poster","image");
				final String backdropUrl=cgCatalog.text(item,"backdrop","landscape_poster","source_backdrop");
				cgImage.load(posterUrl,poster,true);
				android.widget.TextView title=cgGlass.label(cgCatalog.text(item,"title","series_title","name"),12.5f);
				title.setGravity(android.view.Gravity.RIGHT|android.view.Gravity.TOP);
				title.setMaxLines(2);
				title.setEllipsize(android.text.TextUtils.TruncateAt.END);
				title.setTypeface(cgGlass.font,android.graphics.Typeface.BOLD);
				android.widget.LinearLayout.LayoutParams titleLp=new android.widget.LinearLayout.LayoutParams(-1,-2);
				titleLp.topMargin=cgGlass.px(6);
				col.addView(title,titleLp);
				String year=cgCatalog.text(item,"year");
				String rating=cgCatalog.text(item,"imdb");
				boolean hasRating=!rating.isEmpty()&&!"0".equals(rating)&&!"0.0".equals(rating);
				String meta=year+(year.isEmpty()||!hasRating?"":"  ·  ")+(hasRating?"★ "+rating:"");
				if(!meta.isEmpty()) {
					android.widget.TextView info=cgGlass.label(meta,11.5f);
					info.setGravity(android.view.Gravity.RIGHT);
					info.setTextColor(CigramDesign.TEXT_3);
					info.setSingleLine(true);
					info.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);
					android.widget.LinearLayout.LayoutParams infoLp=new android.widget.LinearLayout.LayoutParams(-1,-2);
					infoLp.topMargin=cgGlass.px(2);
					col.addView(info,infoLp);
				}
				col.setOnTouchListener(new android.view.View.OnTouchListener() {
					@Override public boolean onTouch(android.view.View v,android.view.MotionEvent event) {
						int action=event.getActionMasked();
						if(action==android.view.MotionEvent.ACTION_DOWN) {
							CigramStage1InitProvider.preloadDetailsImages(SearchXmlActivity.this,posterUrl,backdropUrl);
							v.animate().scaleX(1f).scaleY(1f).setDuration(100L).start();
						} else if(action==android.view.MotionEvent.ACTION_UP||action==android.view.MotionEvent.ACTION_CANCEL) {
							v.animate().scaleX(1f).scaleY(1f).setDuration(190L).start();
						}
						return false;
					}
				});
				col.setOnClickListener(new android.view.View.OnClickListener() {
					@Override public void onClick(android.view.View v) { open(item,poster,posterUrl); }
				});
				return col;
			}
			void rail(String title,final String type,java.util.ArrayList<org.json.JSONObject> list) {
				if(list.isEmpty())return;
				android.widget.TextView more=null;
				if(!type.isEmpty()) {
					more=new android.widget.TextView(SearchXmlActivity.this);
					more.setText("عرض الكل");
					more.setClickable(true);
					more.setOnClickListener(new android.view.View.OnClickListener() {
						@Override public void onClick(android.view.View v) {
							cgType[0]=type; CigramSearch.this.tabs(); CigramSearch.this.queue();
						}
					});
				}
				cgResults.addView(CigramDesign.sectionHeader(SearchXmlActivity.this,title+" ("+list.size()+")",more),
				new android.widget.LinearLayout.LayoutParams(-1,cgGlass.px(48)));
				final android.widget.HorizontalScrollView scroll=new android.widget.HorizontalScrollView(SearchXmlActivity.this);
				scroll.setHorizontalScrollBarEnabled(false);
				scroll.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
				scroll.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);
				final android.widget.LinearLayout strip=new android.widget.LinearLayout(SearchXmlActivity.this);
				strip.setOrientation(android.widget.LinearLayout.HORIZONTAL);
				strip.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);
				strip.setGravity(android.view.Gravity.RIGHT);
				strip.setPadding(cgGlass.px(6),0,cgGlass.px(6),cgGlass.px(4));
				int shown=Math.min(list.size(),24);
				for(int i=shown-1;i>=0;i--) {
					android.widget.LinearLayout.LayoutParams lp=new android.widget.LinearLayout.LayoutParams(cgGlass.px(108),-2);
					lp.leftMargin=cgGlass.px(10);
					strip.addView(railCard(list.get(i)),lp);
				}
				scroll.addView(strip,new android.widget.HorizontalScrollView.LayoutParams(-2,-2));
				strip.setMinimumWidth(SearchXmlActivity.this.getResources().getDisplayMetrics().widthPixels-cgGlass.px(16));
				scroll.post(new java.lang.Runnable(){@Override public void run(){scroll.scrollTo(Math.max(0,strip.getWidth()-scroll.getWidth()),0);}});
				android.widget.LinearLayout.LayoutParams scrollLp=new android.widget.LinearLayout.LayoutParams(-1,-2);
				scrollLp.bottomMargin=cgGlass.px(10);
				cgResults.addView(scroll,scrollLp);
			}
			int grouped(java.util.ArrayList<org.json.JSONObject> hits) {
				java.util.ArrayList<org.json.JSONObject> movies=new java.util.ArrayList<org.json.JSONObject>();
				java.util.ArrayList<org.json.JSONObject> series=new java.util.ArrayList<org.json.JSONObject>();
				java.util.ArrayList<org.json.JSONObject> other=new java.util.ArrayList<org.json.JSONObject>();
				boolean seriesFirst=false, decided=false;
				for(org.json.JSONObject hit:hits) {
					String kind=cgCatalog.text(hit,"type");
					if("series".equals(kind)) { series.add(hit); if(!decided){seriesFirst=true;decided=true;} }
					else if("movie".equals(kind)) { movies.add(hit); if(!decided){decided=true;} }
					else other.add(hit);
				}
				if(seriesFirst) { rail("مسلسلات","series",series); rail("أفلام","movie",movies); }
				else { rail("أفلام","movie",movies); rail("مسلسلات","series",series); }
				rail("نتائج أخرى","",other);
				return hits.size();
			}
			@Override public void run() {
				final long cgT0=android.os.SystemClock.elapsedRealtime();
				reload(); tabs(); cgEmptyIcon.setVisibility(android.view.View.GONE);
				cgEmpty.animate().cancel();cgEmpty.setVisibility(android.view.View.GONE);
				cgResults.removeAllViews();cgResults.setAlpha(1f);cgResults.setTranslationY(0f);
				if(pending!=null&&items.length()==0){   // the index is still being parsed in the background
					android.widget.TextView cgWait=new android.widget.TextView(SearchXmlActivity.this);
					cgWait.setText("\u200Fجارٍ تجهيز البحث…");cgWait.setTextColor(0xAAFFFFFF);cgWait.setTypeface(cgGlass.font);
					cgWait.setGravity(android.view.Gravity.CENTER);cgWait.setPadding(0,cgGlass.px(40),0,0);
					cgResults.addView(cgWait,new android.widget.LinearLayout.LayoutParams(-1,-2));
					return;
				}
				try{CigramRequestPrompt.hide(findViewById(R.id.linear_search_empty));}catch(Throwable cgReqIgnored){}
				String query=cgInput.getText().toString().trim();
				boolean browse=query.isEmpty()&&(!cgType[0].equals("all")||advanced());
				if(query.isEmpty()&&!browse) {
					cgTabs.setVisibility(android.view.View.GONE);
					try {
						org.json.JSONArray recent=new org.json.JSONArray(cgRecent.getString("items","[]"));
						if(recent.length()>0) {
							// v4.4: same section header as the home page (title + red accent bar)
							cgResults.addView(CigramDesign.sectionHeader(SearchXmlActivity.this,"آخر ما شاهدته في البحث",null),
							new android.widget.LinearLayout.LayoutParams(-1,cgGlass.px(48)));
							for(int i=0;i<recent.length();i++) {
								org.json.JSONObject old=recent.optJSONObject(i); if(old==null)continue;
								// Resolve history against the fresh catalog to avoid opening stale records.
								for(int j=0;j<items.length();j++) {
									org.json.JSONObject current=items.optJSONObject(j);
									if(current!=null&&cgCatalog.key(current).equals(cgCatalog.key(old))){card(current);break;}
								}
							}
						}
					}catch(Exception ignored){}
					cgResults.setVisibility(android.view.View.VISIBLE);
					return;
				}
				String year=cgFilters.getString("year","all"), category=cgFilters.getString("category","all");
				float rating=number(cgFilters.getString("imdb_min","0"));
				int found=0;
				boolean anyQueryMatch=false;
				final java.util.ArrayList<org.json.JSONObject> cgHits=new java.util.ArrayList<org.json.JSONObject>();
				final java.util.HashSet<String> cgDedupe=new java.util.HashSet<String>();
				for(int i=0;i<items.length();i++) {
					org.json.JSONObject item=items.optJSONObject(i); if(item==null)continue;
					final boolean cgMatch=cgCatalog.matches(item,query);   // true for an empty query
					if(!query.isEmpty()&&cgMatch)anyQueryMatch=true;
					if(!cgType[0].equals("all")&&!cgType[0].equals(cgCatalog.text(item,"type")))continue;
					if(!CigramSearchFilter.yearMatches(year,cgCatalog.text(item,"year")))continue;
					if(!category.equals("all")&&!cgCatalog.normalize(cgCatalog.text(item,"category")).contains(cgCatalog.normalize(category)))continue;
					if(number(cgCatalog.text(item,"imdb"))<rating||!cgMatch)continue;
					String cgDupKey=cgCatalog.text(item,"type")+"|"+cgCatalog.normalize(cgCatalog.text(item,"series_title","title"))+"|"+cgCatalog.text(item,"year");
					if(!cgDedupe.add(cgDupKey))continue;
					cgHits.add(item);
				}
				if(!query.isEmpty()) {
					final java.util.IdentityHashMap<org.json.JSONObject,Integer> cgScores=new java.util.IdentityHashMap<org.json.JSONObject,Integer>();
					for(org.json.JSONObject hit:cgHits)cgScores.put(hit,Integer.valueOf(cgCatalog.score(hit,query)));
					java.util.Collections.sort(cgHits,new java.util.Comparator<org.json.JSONObject>(){
						@Override public int compare(org.json.JSONObject a,org.json.JSONObject b){
							return cgScores.get(b).intValue()-cgScores.get(a).intValue();
						}
					});
				}
				try { CigramSearchFilter.sort(cgHits,cgFilters); } catch(Throwable cgSortIgnored) {}
				try {
					android.view.View cgSummary=CigramSearchFilter.summaryView(SearchXmlActivity.this,cgFilters,new java.lang.Runnable(){
						@Override public void run(){CigramSearch.this.tabs();CigramSearch.this.queue();}
					});
					if(cgSummary!=null&&!cgHits.isEmpty())cgResults.addView(cgSummary);
				} catch(Throwable cgSummaryIgnored) {}
				if(cgType[0].equals("all")) { found=grouped(cgHits); }
				else {
					// Perf: browsing one type used to build a card for EVERY match on the main thread (hundreds).
					// The first screenful is built now, the rest in small batches (and dropped if a new search starts).
					final int cgFirst=Math.min(cgHits.size(),16);
					for(int cgI=0;cgI<cgFirst;cgI++){card(cgHits.get(cgI));found++;}
					if(cgHits.size()>cgFirst) {
						final Object cgToken=new Object();
						cgResults.setTag(cgToken);
						final int[] cgNext={cgFirst};
						final java.util.ArrayList<org.json.JSONObject> cgAll=cgHits;
						final android.os.Handler cgH=new android.os.Handler(android.os.Looper.getMainLooper());
						cgH.postDelayed(new java.lang.Runnable(){@Override public void run(){
							if(isFinishing()||isDestroyed()||cgResults.getTag()!=cgToken)return;
							int cgEnd=Math.min(cgAll.size(),cgNext[0]+6);
							for(;cgNext[0]<cgEnd;cgNext[0]++){try{card(cgAll.get(cgNext[0]));}catch(Throwable cgIgnored){}}
							if(cgNext[0]<cgAll.size())cgH.postDelayed(this,32L);
						}},120L);
					}
					found+=cgHits.size()-cgFirst;
				}
				try {
					if(!query.isEmpty()) CigramAnalytics.trackRateLimited(SearchXmlActivity.this,"search",1500L,
					found>0?"search_performed":"search_no_results","results",String.valueOf(found),
					"qlen",String.valueOf(query.length()),"ms",String.valueOf(android.os.SystemClock.elapsedRealtime()-cgT0));
				} catch(Throwable cgIgnored) {}
				cgResults.setVisibility(found>0?android.view.View.VISIBLE:android.view.View.GONE);
				cgTabs.setVisibility(
				(browse || (!query.isEmpty() && anyQueryMatch))
				? android.view.View.VISIBLE
				: android.view.View.GONE
				);
				// v10.62: البحث الذكي — أخطاء إملائية + عربي/إنجليزي، فقط عندما لا توجد نتيجة
				if(found==0&&!query.isEmpty()&&!source.isEmpty()) {
					try {
						java.util.ArrayList<org.json.JSONObject> cgSmart=CigramSmartSearch.fuzzy(items,query,cgType[0],year,category,rating);
						if(!cgSmart.isEmpty()) {
							cgResults.addView(CigramDesign.sectionHeader(SearchXmlActivity.this,"ربما تقصد",null),
							new android.widget.LinearLayout.LayoutParams(-1,cgGlass.px(48)));
							for(org.json.JSONObject cgHit:cgSmart){card(cgHit);found++;}
							cgResults.setVisibility(android.view.View.VISIBLE);
							try{cgResults.addView(CigramRequestPrompt.row(SearchXmlActivity.this,query,cgType[0]));}catch(Throwable cgReqIgnored){}
							try { CigramAnalytics.track(SearchXmlActivity.this,"search_smart_hit","results",String.valueOf(cgSmart.size())); } catch(Throwable cgIgnored) {}
						}
					} catch(Throwable cgSmartError) {}
				}
				if(found==0) {
					cgEmpty.setText(source.isEmpty()?"جارٍ جلب بيانات البحث…":failed?"تعذر تحديث البيانات؛ جرّب فتح البحث مجددًا":(query.isEmpty()?"لا توجد نتائج مطابقة للفلاتر المحددة":"لا توجد نتائج لـ «"+query+"»\nجرّب كلمة أخرى أو غيّر الفلاتر"));
					cgEmpty.setVisibility(android.view.View.VISIBLE);cgEmpty.setAlpha(0f);cgEmpty.animate().alpha(1f).setDuration(180L).start();
					if(!query.isEmpty()&&!source.isEmpty()&&!failed){try{CigramRequestPrompt.show(SearchXmlActivity.this,findViewById(R.id.linear_search_empty),cgEmpty,query,cgType[0]);}catch(Throwable cgReqIgnored){}}
				} else {cgResults.setAlpha(0f);cgResults.animate().alpha(1f).setDuration(190L).start();}
			}
			void filter() {
				// v10.68: لوحة فلترة واحدة واضحة (النوع، التصنيف، السنة، التقييم، الترتيب) مع عدد النتائج المباشر
				reload();
				CigramSearchFilter.show(SearchXmlActivity.this,items,cgFilters,cgType[0],new CigramSearchFilter.Host(){
					@Override public int count(String type,String year,String category,String rating){
						return countWith(type,year,category,rating);
					}
					@Override public void apply(String type){
						cgType[0]=type;tabs();queue();
					}
				});
			}
		}
		final CigramSearch cgSearch=new CigramSearch();
		// One render callback shared by network response, typing, tabs and voice input.
		cgInput.setTag(cgSearch);
		search_json=CigramCatalogPrefs.of(this).getString("json",search_json==null?"":search_json);
		cgFilter.setOnClickListener(new android.view.View.OnClickListener(){@Override public void onClick(android.view.View v){cgSearch.filter();}});
		findViewById(R.id.imageview_filter).setOnClickListener(new android.view.View.OnClickListener(){@Override public void onClick(android.view.View v){cgSearch.filter();}});
		for(int i=0;i<cgTabViews.length;i++) {
			final int index=i;
			cgTabViews[i].setOnClickListener(new android.view.View.OnClickListener(){
				@Override public void onClick(android.view.View v){cgType[0]=new String[]{"all","movie","series"}[index];cgSearch.tabs();cgSearch.queue();}
			});
		}
		cgInput.addTextChangedListener(new android.text.TextWatcher(){
			@Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
			@Override public void onTextChanged(CharSequence s,int start,int before,int count){cgSearch.queue();}
			@Override public void afterTextChanged(android.text.Editable s){}
		});
		cgInput.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener(){
			@Override public boolean onEditorAction(android.widget.TextView v,int action,android.view.KeyEvent event){cgSearch.run();return true;}
		});
		findViewById(R.id.imageview_search_icon).setOnClickListener(new android.view.View.OnClickListener(){@Override public void onClick(android.view.View v){cgSearch.run();}});
		cgRoot.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener(){
			@Override public void onViewAttachedToWindow(android.view.View v){}
			@Override public void onViewDetachedFromWindow(android.view.View v){cgSearchHandler.removeCallbacksAndMessages(null);cgResults.animate().cancel();}
		});
		// The search page must appear instantly like the other tabs. The very first render calls
		// reload(), which parses the whole (multi-MB) catalogue — doing that here, in onCreate, froze
		// the first frame so the page showed up late. Defer it one frame: the field, keyboard and tabs
		// draw immediately, then the default list fills in.
		cgRoot.post(new java.lang.Runnable(){ @Override public void run(){ try { cgSearch.run(); } catch (Throwable ignored) {} }});
		
		// =====================================================
		// CIGRAM VOICE SEARCH - FULL CODE v2
		// SearchXmlActivity > onCreate > add source directly
		//
		// يعتمد فقط على:
		// imageview_voice
		// edittext_search
		//
		// الصور المستخدمة:
		// icon_mic_none_round = الحالة العادية
		// icon_mic_round      = أثناء الانتظار والاستماع
		//
		// عند نجاح التعرف الصوتي:
		// يضع النص داخل edittext_search.
		// وإذا عندك TextWatcher للبحث، النتائج تتحدث تلقائياً.
		// =====================================================
		
		final android.widget.ImageView cigramVoiceButton =
		(android.widget.ImageView) findViewById(
		R.id.imageview_voice
		);
		
		final android.widget.EditText cigramVoiceSearchInput =
		(android.widget.EditText) findViewById(
		R.id.edittext_search
		);
		
		final android.os.Handler cigramVoiceHandler =
		new android.os.Handler(
		android.os.Looper.getMainLooper()
		);
		
		
		/*
 * نخزن SpeechRecognizer داخل Array
 * حتى يبقى متاح داخل الـ listeners.
 */
		final android.speech.SpeechRecognizer[] cigramRecognizer =
		new android.speech.SpeechRecognizer[] {
			null
		};
		
		
		final boolean[] cigramIsListening =
		new boolean[] {
			false
		};
		
		
		final boolean[] cigramWaitingPermission =
		new boolean[] {
			false
		};
		
		// Busy includes starting, listening, and processing the final result.
		final boolean[] cigramVoiceBusy = {false};
		final java.lang.Runnable[] cigramPendingStart = {null};
		
		
		final android.content.Intent cigramRecognizerIntent =
		new android.content.Intent(
		android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH
		);
		
		
		cigramRecognizerIntent.putExtra(
		android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
		android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
		);
		
		cigramRecognizerIntent.putExtra(
		android.speech.RecognizerIntent.EXTRA_LANGUAGE,
		"ar-IQ"
		);
		
		cigramRecognizerIntent.putExtra(
		android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
		"ar-IQ"
		);
		
		cigramRecognizerIntent.putExtra(
		android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS,
		true
		);
		
		cigramRecognizerIntent.putExtra(
		android.speech.RecognizerIntent.EXTRA_MAX_RESULTS,
		5
		);
		
		cigramRecognizerIntent.putExtra(
		android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE,
		getPackageName()
		);
		
		
		/*
 * =========================================
 * شكل زر المايك بالحالات المختلفة
 * =========================================
 */
		class CigramVoiceUi {
			
			void idle() {
				
				cigramIsListening[0] =
				false;
				cigramVoiceBusy[0] = false;
				
				if (cigramVoiceButton == null) {
					return;
				}
				
				/* الحالة العادية */
				cigramVoiceButton.setImageResource(
				R.drawable.icon_mic_none_round
				);
				
				cigramVoiceButton.animate()
				.cancel();
				
				cigramVoiceButton.animate()
				.alpha(1f)
				.scaleX(1f)
				.scaleY(1f)
				.rotation(0f)
				.setDuration(160L)
				.start();
			}
			
			
			void waiting() {
				
				cigramVoiceBusy[0] = true;
				if (cigramVoiceButton == null) {
					return;
				}
				
				/* بمجرد الضغط يتحول إلى صورة المايك الثانية */
				cigramVoiceButton.setImageResource(
				R.drawable.icon_mic_round
				);
				
				cigramVoiceButton.animate()
				.cancel();
				
				cigramVoiceButton.animate()
				.alpha(0.78f)
				.scaleX(1.10f)
				.scaleY(1.10f)
				.setDuration(130L)
				.start();
			}
			
			
			void listening() {
				
				cigramIsListening[0] =
				true;
				
				if (cigramVoiceButton == null) {
					return;
				}
				
				/* أثناء الاستماع يبقى المايك الثاني */
				cigramVoiceButton.setImageResource(
				R.drawable.icon_mic_round
				);
				
				cigramVoiceButton.animate()
				.cancel();
				
				cigramVoiceButton.animate()
				.alpha(0.65f)
				.scaleX(1.15f)
				.scaleY(1.15f)
				.setDuration(120L)
				.start();
			}
			
			
			void pulse(
			float rms
			) {
				
				if (
				cigramVoiceButton == null
				||
				!cigramIsListening[0]
				) {
					return;
				}
				
				float normalized =
				Math.max(
				0f,
				Math.min(
				1f,
				rms / 12f
				)
				);
				
				float scale =
				1.06f
				+
				(
				normalized
				*
				0.12f
				);
				
				cigramVoiceButton.setScaleX(
				scale
				);
				
				cigramVoiceButton.setScaleY(
				scale
				);
			}
		}
		
		
		final CigramVoiceUi cigramVoiceUi =
		new CigramVoiceUi();
		
		/* عند فتح الصفحة يبدأ بصورة المايك العادية */
		cigramVoiceUi.idle();
		
		
		/*
 * =========================================
 * التحكم بالتعرف الصوتي
 * =========================================
 */
		class CigramVoiceController {
			
			void ensureRecognizer() {
				
				if (
				cigramRecognizer[0] != null
				) {
					return;
				}
				
				if (
				!android.speech.SpeechRecognizer
				.isRecognitionAvailable(
				SearchXmlActivity.this
				)
				) {
					return;
				}
				
				
				cigramRecognizer[0] =
				android.speech.SpeechRecognizer
				.createSpeechRecognizer(
				SearchXmlActivity.this
				);
				
				
				cigramRecognizer[0]
				.setRecognitionListener(
				new android.speech.RecognitionListener() {
					
					@Override
					public void onReadyForSpeech(
					android.os.Bundle params
					) {
						
						cigramVoiceUi.listening();
					}
					
					
					@Override
					public void onBeginningOfSpeech() {
						
						cigramVoiceUi.listening();
					}
					
					
					@Override
					public void onRmsChanged(
					float rmsdB
					) {
						
						cigramVoiceUi.pulse(
						rmsdB
						);
					}
					
					
					@Override
					public void onBufferReceived(
					byte[] buffer
					) {
					}
					
					
					@Override
					public void onEndOfSpeech() {
						
						cigramVoiceUi.waiting();
					}
					
					
					@Override
					public void onError(
					int error
					) {
						
						cigramVoiceUi.idle();
					}
					
					
					@Override
					public void onResults(
					android.os.Bundle results
					) {
						
						java.util.ArrayList<String> matches =
						results == null
						?
						null
						:
						results.getStringArrayList(
						android.speech.SpeechRecognizer
						.RESULTS_RECOGNITION
						);
						
						
						if (
						matches != null
						&&
						!matches.isEmpty()
						) {
							
							String bestResult =
							matches.get(0);
							
							if (bestResult == null) {
								bestResult = "";
							}
							
							bestResult =
							bestResult.trim();
							
							
							if (
							!bestResult.equals("")
							&&
							cigramVoiceSearchInput != null
							) {
								
								cigramVoiceSearchInput.setText(
								bestResult
								);
								
								cigramVoiceSearchInput.setSelection(
								cigramVoiceSearchInput.length()
								);
								
								/*
								 * TextWatcher الموجود بمحرك البحث
								 * ينفذ البحث تلقائياً.
								 */
								cigramVoiceSearchInput.requestFocus();
								
								
								android.view.inputmethod.InputMethodManager imm =
								(android.view.inputmethod.InputMethodManager)
								getSystemService(
								android.content.Context
								.INPUT_METHOD_SERVICE
								);
								
								if (imm != null) {
									
									imm.hideSoftInputFromWindow(
									cigramVoiceSearchInput
									.getWindowToken(),
									0
									);
								}
							}
						}
						
						
						cigramVoiceUi.idle();
					}
					
					
					@Override
					public void onPartialResults(
					android.os.Bundle partialResults
					) {
						
						java.util.ArrayList<String> partial =
						partialResults == null
						?
						null
						:
						partialResults.getStringArrayList(
						android.speech.SpeechRecognizer
						.RESULTS_RECOGNITION
						);
						
						
						if (
						partial != null
						&&
						!partial.isEmpty()
						&&
						cigramVoiceSearchInput != null
						) {
							
							String liveText =
							partial.get(0);
							
							if (liveText == null) {
								liveText = "";
							}
							
							liveText =
							liveText.trim();
							
							
							if (
							!liveText.equals("")
							) {
								
								cigramVoiceSearchInput.setText(
								liveText
								);
								
								cigramVoiceSearchInput.setSelection(
								cigramVoiceSearchInput.length()
								);
							}
						}
					}
					
					
					@Override
					public void onEvent(
					int eventType,
					android.os.Bundle params
					) {
					}
				}
				);
			}
			
			
			boolean hasAudioPermission() {
				
				if (
				android.os.Build.VERSION.SDK_INT
				<
				android.os.Build.VERSION_CODES.M
				) {
					return true;
				}
				
				return
				checkSelfPermission(
				android.Manifest.permission.RECORD_AUDIO
				)
				==
				android.content.pm.PackageManager
				.PERMISSION_GRANTED;
			}
			
			
			void startListeningNow() {
				
				if (cigramVoiceBusy[0]) return;
				if (
				!android.speech.SpeechRecognizer
				.isRecognitionAvailable(
				SearchXmlActivity.this
				)
				) {
					
					cigramVoiceUi.idle();
					
					return;
				}
				
				
				ensureRecognizer();
				
				
				if (
				cigramRecognizer[0] == null
				) {
					
					cigramVoiceUi.idle();
					
					return;
				}
				
				
				/*
		 * نخفي الكيبورد قبل الاستماع.
		 */
				if (
				cigramVoiceSearchInput != null
				) {
					
					android.view.inputmethod.InputMethodManager imm =
					(android.view.inputmethod.InputMethodManager)
					getSystemService(
					android.content.Context
					.INPUT_METHOD_SERVICE
					);
					
					if (imm != null) {
						
						imm.hideSoftInputFromWindow(
						cigramVoiceSearchInput
						.getWindowToken(),
						0
						);
					}
				}
				
				
				cigramVoiceUi.waiting();
				cigramPendingStart[0] = new java.lang.Runnable() {
					@Override public void run() {
						cigramPendingStart[0] = null;
						if (!cigramVoiceBusy[0] || isFinishing()
						|| cigramRecognizer[0] == null) return;
						try {
							cigramRecognizer[0].startListening(cigramRecognizerIntent);
						} catch (Exception ignored) {
							cigramVoiceUi.idle();
						}
					}
				};
				cigramVoiceHandler.postDelayed(cigramPendingStart[0],120L);
			}
			
			
			void requestPermissionAndStart() {
				
				if (
				hasAudioPermission()
				) {
					
					startListeningNow();
					
					return;
				}
				
				
				if (
				android.os.Build.VERSION.SDK_INT
				>=
				android.os.Build.VERSION_CODES.M
				) {
					
					if (
					cigramWaitingPermission[0]
					) {
						return;
					}
					
					
					cigramWaitingPermission[0] =
					true;
					
					
					// 2026: explanation first, settings offer after a permanent refusal (CigramPermissions)
					CigramPermissions.ensure(SearchXmlActivity.this,
					android.Manifest.permission.RECORD_AUDIO, 8902,
					"يحتاج Cigram إلى الميكروفون للبحث الصوتي فقط، أثناء ضغطك على زر الميكروفون.");
					
					
					/*
			 * ننتظر المستخدم، وإذا وافق نبدأ تلقائياً.
			 */
					final java.lang.Runnable permissionWatcher =
					new java.lang.Runnable() {
						
						int tries =
						0;
						
						
						@Override
						public void run() {
							
							tries++;
							
							
							if (
							hasAudioPermission()
							) {
								
								cigramWaitingPermission[0] =
								false;
								
								startListeningNow();
								
								return;
							}
							
							
							/*
						 * تقريباً 15 ثانية كحد أقصى.
						 */
							if (
							tries < 75
							) {
								
								cigramVoiceHandler.postDelayed(
								this,
								200L
								);
								
							} else {
								
								cigramWaitingPermission[0] =
								false;
								
								cigramVoiceUi.idle();
							}
						}
					};
					
					
					cigramVoiceHandler.postDelayed(
					permissionWatcher,
					300L
					);
				}
			}
			
			
			void stopListening() {
				cigramVoiceBusy[0] = false;
				if (cigramPendingStart[0] != null) {
					cigramVoiceHandler.removeCallbacks(cigramPendingStart[0]);
					cigramPendingStart[0] = null;
				}
				try {
					if (cigramRecognizer[0] != null) cigramRecognizer[0].cancel();
				} catch (Exception ignored) {}
				cigramVoiceUi.idle();
			}
		}
		
		
		final CigramVoiceController cigramVoiceController =
		new CigramVoiceController();
		
		
		/*
 * =========================================
 * زر المايك
 * =========================================
 */
		if (cigramVoiceButton != null) {
			
			if (
			!android.speech.SpeechRecognizer
			.isRecognitionAvailable(
			SearchXmlActivity.this
			)
			) {
				
				cigramVoiceButton.setAlpha(
				0.35f
				);
				
				cigramVoiceButton.setClickable(
				false
				);
				
			} else {
				
				cigramVoiceButton.setClickable(
				true
				);
				
				cigramVoiceButton.setFocusable(
				true
				);
				
				
				cigramVoiceButton.setOnClickListener(
				new android.view.View.OnClickListener() {
					
					@Override
					public void onClick(
					android.view.View view
					) {
						
						/*
					 * إذا هو يستمع حالياً:
					 * الضغطة الثانية توقف الاستماع.
					 */
						if (
						cigramVoiceBusy[0]
						) {
							
							cigramVoiceController
							.stopListening();
							
						} else {
							
							cigramVoiceController
							.requestPermissionAndStart();
						}
					}
				}
				);
			}
		}
		
		
		/*
 * =========================================
 * تنظيف SpeechRecognizer عند غلق الصفحة
 * =========================================
 */
		final android.view.View cigramVoiceRoot =
		findViewById(
		R.id.linear_root_search
		);
		
		if (
		cigramVoiceRoot != null
		) {
			
			cigramVoiceRoot.addOnAttachStateChangeListener(
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
					
					cigramVoiceBusy[0] = false;
					cigramPendingStart[0] = null;
					cigramVoiceHandler.removeCallbacksAndMessages(
					null
					);
					
					
					try {
						
						if (
						cigramRecognizer[0] != null
						) {
							
							cigramRecognizer[0]
							.cancel();
							
							cigramRecognizer[0]
							.destroy();
							
							cigramRecognizer[0] =
							null;
						}
						
					} catch (Exception ignored) {
					}
					
					
					cigramVoiceUi.idle();
				}
			}
			);
		}
		// CIGRAM SEARCH LOADING STATE
		{
			android.view.ViewGroup root=(android.view.ViewGroup)findViewById(R.id.linear_root_search);
			if(root!=null&&root.findViewWithTag("cigram_search_loading_state")==null){
				final float d=getResources().getDisplayMetrics().density;
				android.widget.LinearLayout box=new android.widget.LinearLayout(SearchXmlActivity.this);
				box.setTag("cigram_search_loading_state");box.setOrientation(android.widget.LinearLayout.VERTICAL);box.setGravity(android.view.Gravity.CENTER);
				box.setPadding(0,(int)(24*d),0,(int)(24*d));
				android.widget.ProgressBar bar=new android.widget.ProgressBar(SearchXmlActivity.this);
				box.addView(bar,new android.widget.LinearLayout.LayoutParams((int)(38*d),(int)(38*d)));
				android.widget.TextView label=new android.widget.TextView(SearchXmlActivity.this);label.setText("جارٍ تجهيز البحث…");label.setTextColor(0xCCFFFFFF);label.setTextSize(13);label.setGravity(android.view.Gravity.CENTER);
				label.setPadding(0,(int)(10*d),0,0);box.addView(label,new android.widget.LinearLayout.LayoutParams(-1,-2));
				root.addView(box,new android.view.ViewGroup.LayoutParams(-1,-2));
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
		
		// Search filters the catalogue ALREADY saved in memory/preferences. A new download is started only when
		// nothing is saved yet or the saved copy is older than 10 minutes; opening the page, typing and tapping
		// never trigger a request, and there is no loading / prep screen while a usable copy exists.
		{
			android.content.SharedPreferences cgCatalogPrefs = CigramCatalogPrefs.of(this);
			long cgSavedAt = cgCatalogPrefs.getLong("updated_at", 0L);
			boolean cgHasSaved = search_json != null && search_json.trim().length() > 2;
			boolean cgFresh = cgHasSaved && cgSavedAt > 0L
			&& System.currentTimeMillis() - cgSavedAt < 10L * 60L * 1000L;
			if (cgFresh) {
				android.view.ViewGroup cgLoadRoot = (android.view.ViewGroup) findViewById(R.id.linear_root_search);
				if (cgLoadRoot != null) {
					android.view.View cgLoad = cgLoadRoot.findViewWithTag("cigram_search_loading_state");
					if (cgLoad != null) cgLoadRoot.removeView(cgLoad);
				}
			} else {
				request_search.startRequestNetwork(RequestNetworkController.GET, "https://cigram-admin-api.wwq-mixtv.workers.dev/movies", "search_data", _request_search_request_listener);
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
			}
		}
		
	}
	
}
