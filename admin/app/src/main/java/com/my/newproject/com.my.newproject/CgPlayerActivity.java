package com.my.newproject;

import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;
import android.media.MediaPlayer;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Video + subtitle test screen: link classification, file info, subtitle encoding/Arabic check,
 * episode match warning and an on-demand playback preview with subtitle overlay. Nothing runs automatically.
 */
public class CgPlayerActivity extends CgBase {

    private JSONObject item;
    private LinearLayout videoResult;
    private LinearLayout infoResult;
    private LinearLayout subResult;
    private LinearLayout subExtra;
    private LinearLayout previewHolder;

    private VideoView videoView;
    private android.webkit.WebView vbWeb;
    private View vbCustomView;
    private android.webkit.WebChromeClient.CustomViewCallback vbCustomCb;
    private TextView overlay;
    private ArrayList<long[]> cueTimes = new ArrayList<long[]>();
    private ArrayList<String> cueTexts = new ArrayList<String>();
    private Runnable ticker;

    @Override
    protected String screenTitle() {
        return "اختبار الفيديو والترجمة";
    }

    @Override
    protected void onBuild() {
        ensureData(new Runnable() {
            @Override
            public void run() {
                build();
            }
        });
    }

    @Override
    public void onBackPressed() {
        // Fullscreen VidBolt WebView: the default Activity back finishes cleanly,
        // returning to the previous screen without navigating the embedded iframe.
        if (vbCustomView != null) {
            hideVbCustom();
            return;
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        stopPreview();
        super.onDestroy();
    }

    private void build() {
        CgData d = CgData.cur();
        String id = getIntent().getStringExtra("id");
        item = d.byId(id == null ? "" : id);
        if (item == null) {
            // Unsaved content from the new add screens (draft preview): url extras only, nothing is stored.
            String vu = getIntent().getStringExtra("video_url");
            String su = getIntent().getStringExtra("subtitle_url");
            if ((vu != null && vu.length() > 0) || (su != null && su.length() > 0)) {
                item = new JSONObject();
                try {
                    item.put("id", "");
                    item.put("type", "movie");
                    String tt = getIntent().getStringExtra("title");
                    item.put("title", tt == null ? "معاينة قبل النشر" : tt);
                    item.put("video", vu == null ? "" : vu);
                    item.put("subtitle", su == null ? "" : su);
                } catch (Exception ignored) { }
            }
        }
        LinearLayout c = scrollBody();
        if (item == null) {
            c.addView(CgUi.empty(this, "العنصر غير موجود", ""));
            return;
        }
        bar.subtitle(CgRows.displayTitle(d, item));
        final String video = CgOps.videoUrl(item);
        // VidBolt URLs are iframe player URLs, not direct media files.
        // Keep this diagnostics screen visible; HTTP 403 from automated probes
        // must not be interpreted as a broken VidBolt video.
        final String sub = CgData.s(item, "subtitle");

        LinearLayout head = CgUi.card(this);
        head.addView(CgUi.text(this, CgRows.displayTitle(d, item), 15, CgCfg.TEXT, true));
        TextView vu = CgUi.muted(this, "الفيديو: " + (video.length() > 0 ? video : "— غير موجود —"));
        CgUi.ltr(vu);
        head.addView(vu);
        TextView su = CgUi.muted(this, "الترجمة: " + (sub.length() > 0 ? sub : "— غير موجودة —"));
        CgUi.ltr(su);
        head.addView(su);
        head.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        c.addView(head);

        if (CgVidbolt.is(video)) {
            // VidBolt = iframe player: no HTTP probe, no file info, no separate subtitle. The player IS the test.
            LinearLayout vc = CgUi.card(this);
            vc.addView(CgUi.text(this, "مشغّل VidBolt", 14, CgCfg.TEXT, true));
            vc.addView(CgUi.muted(this, "الرابط مشغّل iframe وليس ملف MP4، فلا يوجد فحص HTTP ولا ترجمة منفصلة (المشغّل يحمل ترجماته بنفسه). ظهور الفيديو وتشغيله هنا هو الاختبار الحقيقي."));
            vc.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
            c.addView(vc);
            addVidboltPlayer(vc, video);
            return;
        }

        // video test
        LinearLayout vc = CgUi.card(this);
        vc.addView(CgUi.text(this, "1) اختبار رابط الفيديو", 14, CgCfg.TEXT, true));
        vc.addView(CgUi.muted(this, "يعمل / 403 / 404 / Timeout / ليس فيديو / فارغ — اختبار خفيف (بدون تنزيل الملف)."));
        TextView tv = CgUi.button(this, "اختبر الفيديو", 0);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runCheck("video", video, videoResult);
            }
        });
        vc.addView(tv, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        videoResult = CgUi.vbox(this);
        vc.addView(videoResult);
        vc.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        c.addView(vc);
        showSaved("video", videoResult);

        // file info
        LinearLayout ic = CgUi.card(this);
        ic.addView(CgUi.text(this, "2) معلومات ملف الفيديو", 14, CgCfg.TEXT, true));
        ic.addView(CgUi.muted(this, "الدقة، المدة، الحجم، النوع، الترميز، معدل البت، FPS — تُعرض المتوفر فقط."));
        TextView ib = CgUi.button(this, "اقرأ معلومات الملف", 1);
        ib.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                readInfo(video);
            }
        });
        ic.addView(ib, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        infoResult = CgUi.vbox(this);
        ic.addView(infoResult);
        ic.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        c.addView(ic);

        // subtitle
        LinearLayout sc = CgUi.card(this);
        sc.addView(CgUi.text(this, "3) اختبار الترجمة", 14, CgCfg.TEXT, true));
        sc.addView(CgUi.muted(this, "الوصول، الترميز UTF-8، عدد الأسطر، ظهور العربية، ومطابقة رقم الحلقة."));
        TextView sb = CgUi.button(this, "اختبر الترجمة", 0);
        sb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testSubtitle(sub);
            }
        });
        sc.addView(sb, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        subResult = CgUi.vbox(this);
        sc.addView(subResult);
        subExtra = CgUi.vbox(this);
        sc.addView(subExtra);
        sc.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        c.addView(sc);
        showSaved("subtitle", subResult);

        // preview
        LinearLayout pc = CgUi.card(this);
        pc.addView(CgUi.text(this, "4) معاينة التشغيل مع الترجمة", 14, CgCfg.TEXT, true));
        pc.addView(CgUi.muted(this, "تشغيل الفيديو داخل المعاينة مع إظهار الترجمة فوقه للتأكد من التوقيت والترميز."));
        TextView pb = CgUi.button(this, "ابدأ المعاينة", 1);
        pb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPreview(video, sub);
            }
        });
        pc.addView(pb, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        previewHolder = CgUi.vbox(this);
        pc.addView(previewHolder);
        pc.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        c.addView(pc);
        addConvertCard(c);
    }

    // ------------------------------------------------------------ VidBolt

    /** vidbolt.pro link for this item from its TMDB id (movie, or series TMDB + season + episode). */
    private String vidboltFor(JSONObject it) {
        int kind = CgData.kind(it);
        if (kind == CgData.K_EPISODE) {
            JSONObject h = CgData.cur() == null ? null : CgData.cur().headerOf(it);
            String tmdb = CgVidbolt.tmdbOf(h);
            if (tmdb.length() == 0) tmdb = CgData.s(it, "tmdb");
            return CgVidbolt.tv(tmdb, CgData.n(it, "season"), CgData.n(it, "episode"));
        }
        if (kind == CgData.K_SERIES) return "";
        return CgVidbolt.movie(CgData.s(it, "tmdb"));
    }

    /** For a saved item that still uses a direct file: one tap switches it to its VidBolt player link. */
    private void addConvertCard(LinearLayout c) {
        final String id = CgData.s(item, "id");
        if (id.length() == 0) return;
        final String url = vidboltFor(item);
        if (url.length() == 0) return;
        LinearLayout cc = CgUi.card(this);
        cc.addView(CgUi.text(this, "5) تحويل إلى VidBolt", 14, CgCfg.TEXT, true));
        cc.addView(CgUi.muted(this, "يستبدل رابط الفيديو الحالي برابط مشغّل VidBolt المبني من TMDB ID:"));
        TextView u = CgUi.muted(this, url);
        CgUi.ltr(u);
        cc.addView(u);
        TextView b = CgUi.button(this, "🎬  استخدم رابط VidBolt لهذا العنصر", 1);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUi.confirm(CgPlayerActivity.this, "تحويل إلى VidBolt؟", "سيُحفظ " + url + " مكان رابط الفيديو الحالي، ويمكنك التراجع بعد الحفظ.",
                        "تحويل", false, new Runnable() {
                            @Override
                            public void run() {
                                JSONObject ch = new JSONObject();
                                try {
                                    ch.put("video", url);
                                    ch.put("link", url);
                                } catch (Exception ignored) { }
                                CgOps.updateItem(CgPlayerActivity.this, id, ch, "تم ربط VidBolt", new Runnable() {
                                    @Override
                                    public void run() {
                                        recreate();
                                    }
                                });
                            }
                        });
            }
        });
        cc.addView(b, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        cc.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        c.addView(cc);
    }

    private String vidboltPage(String url) {
        return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"referrer\" content=\"no-referrer\">"
                + "<style>html,body{margin:0;padding:0;height:100%;background:#000}iframe{border:0;width:100%;height:100%}</style></head>"
                + "<body>" + CgVidbolt.iframe(url) + "</body></html>";
    }

    private void hideVbCustom() {
        try {
            if (vbCustomView != null && vbCustomView.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) vbCustomView.getParent()).removeView(vbCustomView);
            }
            if (vbCustomCb != null) vbCustomCb.onCustomViewHidden();
        } catch (Exception ignored) { }
        vbCustomView = null;
        vbCustomCb = null;
    }

    /** The VidBolt WebView + status line + action buttons, added to {@code holder}. Replaces any previous player. */
    private void addVidboltPlayer(LinearLayout holder, final String video) {
        stopPreview();
        final String embed = CgVidbolt.embed(video);
        final TextView status = CgUi.muted(this, "جارٍ تحميل مشغّل VidBolt...");
        final android.webkit.WebView web = new android.webkit.WebView(this);
        android.webkit.WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setSupportMultipleWindows(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        ws.setUserAgentString("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(0xFF000000);
        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(android.webkit.WebView v, String u) {
                // stay inside VidBolt: a page trying to navigate the whole view elsewhere (ads / popups) is ignored
                return !(u == null || CgVidbolt.is(u) || u.startsWith("about:") || u.startsWith("data:"));
            }

            @Override
            public void onPageFinished(android.webkit.WebView v, String u) {
                CgUi.set(status, "تم تحميل مشغّل VidBolt — اضغط تشغيل داخل الإطار. إذا بقي أسود فالمصدر نفسه غير متاح لهذا العنوان.");
                status.setTextColor(CgCfg.GOOD);
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(android.webkit.WebView v, int code, String desc, String failing) {
                CgUi.set(status, "✖ تعذر تحميل المشغّل (" + (desc == null ? code : desc) + "). تأكد من الإنترنت ثم أعد التحميل.");
                status.setTextColor(CgCfg.BAD);
            }
        });
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onShowCustomView(View view, android.webkit.WebChromeClient.CustomViewCallback cb) {
                if (vbCustomView != null) {
                    cb.onCustomViewHidden();
                    return;
                }
                vbCustomView = view;
                vbCustomCb = cb;
                ((android.view.ViewGroup) getWindow().getDecorView()).addView(view,
                        new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.MATCH));
            }

            @Override
            public void onHideCustomView() {
                hideVbCustom();
            }
        });
        vbWeb = web;
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF000000);
        frame.addView(web, new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.dp(this, 230)));
        holder.addView(frame, CgUi.lp(this, CgUi.MATCH, CgUi.dp(this, 230), 0, 10, 0, 0));
        holder.addView(status);

        LinearLayout row = CgUi.hbox(this);
        TextView reload = CgUi.smallButton(this, "↻ إعادة التحميل", 1);
        reload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUi.set(status, "جارٍ تحميل مشغّل VidBolt...");
                status.setTextColor(CgCfg.MUTED);
                web.loadDataWithBaseURL(CgVidbolt.EMBED, vidboltPage(embed), "text/html", "UTF-8", null);
            }
        });
        row.addView(reload, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        TextView direct = CgUi.smallButton(this, "فتح الصفحة مباشرة", 1);
        direct.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUi.set(status, "جارٍ فتح صفحة المشغّل مباشرة...");
                status.setTextColor(CgCfg.MUTED);
                web.loadUrl(embed);
            }
        });
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        dl.setMargins(CgUi.dp(this, 8), 0, 0, 0);
        row.addView(direct, dl);
        holder.addView(row, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 8, 0, 0));

        LinearLayout row2 = CgUi.hbox(this);
        TextView ext = CgUi.smallButton(this, "فتح في المتصفح", 1);
        ext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(embed)));
                } catch (Exception e) {
                    CgUi.set(status, "✖ لا يوجد متصفح لفتح الرابط.");
                    status.setTextColor(CgCfg.BAD);
                }
            }
        });
        row2.addView(ext, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        TextView copy = CgUi.smallButton(this, "نسخ الرابط", 1);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("VidBolt", embed));
                    CgUi.set(status, "تم نسخ الرابط.");
                    status.setTextColor(CgCfg.GOOD);
                } catch (Exception ignored) { }
            }
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        cl.setMargins(CgUi.dp(this, 8), 0, 0, 0);
        row2.addView(copy, cl);
        holder.addView(row2, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 6, 0, 0));

        web.loadDataWithBaseURL(CgVidbolt.EMBED, vidboltPage(embed), "text/html", "UTF-8", null);
    }

    // ------------------------------------------------------------ helpers

    private void line(LinearLayout box, String text, int color) {
        TextView t = CgUi.text(this, text, 13, color, false);
        t.setPadding(0, CgUi.dp(this, 3), 0, 0);
        box.addView(t);
    }

    private void showSaved(String kind, LinearLayout box) {
        JSONObject c = CgData.cur().checkOf(CgData.s(item, "id"), kind);
        if (c == null) return;
        String st = c.optString("status", "");
        line(box, "آخر فحص: " + CgData.checkLabel(st) + (c.optInt("http") > 0 ? " (HTTP " + c.optInt("http") + ")" : "")
                + " • " + CgUi.ago(c.optString("at", "")), "ok".equals(st) ? CgCfg.GOOD : CgCfg.BAD);
    }

    private void runCheck(final String kind, final String url, final LinearLayout box) {
        box.removeAllViews();
        if (url.length() == 0) {
            line(box, "✖ الرابط مفقود.", CgCfg.BAD);
            return;
        }
        if ("video".equals(kind) && CgVidbolt.is(url)) {
            box.removeAllViews();
            line(box, "ℹ رابط VidBolt معروف — لا يمكن الحكم عليه بطلب HTTP مباشر.", CgCfg.GOOD);
            line(box, "قد يعيد خادم VidBolt الحالة 403 لطلبات الفحص الآلي. هذا لا يعني أن الفيلم متوقف.", CgCfg.WARN);
            line(box, "اضغط «ابدأ المعاينة» أدناه لفتح مشغل VidBolt داخل التطبيق والتحقق من ظهور الفيديو.", CgCfg.MUTED);
            return;
        }
        line(box, "جارٍ الاختبار...", CgCfg.MUTED);
        final ArrayList<CgOps.CheckReq> reqs = new ArrayList<CgOps.CheckReq>();
        reqs.add(new CgOps.CheckReq(CgData.s(item, "id"), kind, url));
        CgHttp.async(this, new CgHttp.Work<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                JSONObject b = new JSONObject();
                org.json.JSONArray arr = new org.json.JSONArray();
                JSONObject o = new JSONObject();
                o.put("id", reqs.get(0).id);
                o.put("kind", kind);
                o.put("url", url);
                arr.put(o);
                b.put("items", arr);
                CgHttp.Res r = CgHttp.require(CgHttp.apiPost("/admin/check-urls", b));
                JSONObject res = r.json.optJSONArray("results").optJSONObject(0);
                JSONObject save = new JSONObject();
                org.json.JSONArray sa = new org.json.JSONArray();
                sa.put(res);
                save.put("results", sa);
                if (CgData.s(item, "id").length() > 0) CgHttp.apiPost("/admin/save-checks", save);
                return res;
            }
        }, new CgHttp.Done<JSONObject>() {
            @Override
            public void ok(JSONObject res) {
                if (CgData.s(item, "id").length() > 0) CgData.cur().setCheckLocal(CgData.s(item, "id"), kind, res);
                box.removeAllViews();
                String st = res.optString("status");
                boolean good = "ok".equals(st);
                line(box, (good ? "✔ " : "✖ ") + CgData.checkLabel(st), good ? CgCfg.GOOD : CgCfg.BAD);
                if (res.optInt("http") > 0) line(box, "HTTP: " + res.optInt("http"), CgCfg.MUTED);
                if (res.optInt("ms") > 0) line(box, "زمن الاستجابة: " + res.optInt("ms") + " ms", CgCfg.MUTED);
                if (res.optLong("size") > 0) line(box, "الحجم: " + CgUi.sizeText(res.optLong("size")), CgCfg.MUTED);
                if (res.optString("type").length() > 0) line(box, "النوع: " + res.optString("type"), CgCfg.MUTED);
                if (res.optString("detail").length() > 0) line(box, res.optString("detail"), CgCfg.WARN);
                if ("forbidden".equals(st)) line(box, "السبب المرجّح: صلاحيات R2/Cloudflare أو حماية الرابط.", CgCfg.WARN);
                if ("not_found".equals(st)) line(box, "السبب المرجّح: الملف غير مرفوع أو الاسم/المسار خطأ.", CgCfg.WARN);
                JSONObject si = res.optJSONObject("subtitle");
                if (si != null) subtitleDetails(box, si);
            }

            @Override
            public void fail(String message) {
                box.removeAllViews();
                line(box, "✖ " + message, CgCfg.BAD);
            }
        });
    }

    private void subtitleDetails(LinearLayout box, JSONObject si) {
        line(box, "الأسطر الزمنية (cues): " + si.optInt("cues"), CgCfg.MUTED);
        line(box, "الترميز: " + (si.optBoolean("utf8", true) ? "UTF-8 سليم" : "ليس UTF-8"), si.optBoolean("utf8", true) ? CgCfg.GOOD : CgCfg.BAD);
        line(box, "أحرف عربية: " + si.optInt("arabic_chars") + (si.optBoolean("mojibake") ? " — يبدو النص مشوّهاً (Mojibake)" : ""),
                si.optBoolean("mojibake") ? CgCfg.BAD : CgCfg.MUTED);
        if (si.optBoolean("bom")) line(box, "يحتوي BOM (مقبول)", CgCfg.MUTED);
        if (si.optBoolean("webvtt")) line(box, "الملف بصيغة WebVTT وليس SRT.", CgCfg.WARN);
    }

    // ---------------------------------------------------------- file info

    private void readInfo(final String url) {
        infoResult.removeAllViews();
        if (url.length() == 0) {
            line(infoResult, "✖ لا يوجد رابط فيديو.", CgCfg.BAD);
            return;
        }
        if (CgVidbolt.is(url)) {
            line(infoResult, "هذا رابط مشغّل VidBolt وليس رابط ملف MP4/MKV مباشر.", CgCfg.WARN);
            line(infoResult, "الدقة والمدة والحجم والترميز وFPS لا يمكن استخراجها من رابط iframe. تحقق من التشغيل عبر «ابدأ المعاينة».", CgCfg.MUTED);
            return;
        }
        line(infoResult, "جارٍ قراءة الملف (قد يستغرق حتى 20 ثانية)...", CgCfg.MUTED);
        CgHttp.async(this, new CgHttp.Work<HashMap<String, String>>() {
            @Override
            public HashMap<String, String> run() throws Exception {
                HashMap<String, String> m = new HashMap<String, String>();
                // size / type from a ranged request
                CgHttp.Res head = CgHttp.call("GET", url, null, false, 12000, new String[][]{{"Range", "bytes=0-1"}});
                if (head.error == null) {
                    if (head.contentType.length() > 0) m.put("type", head.contentType.split(";")[0]);
                    if (head.code == 200 && head.contentLength > 0) m.put("size", String.valueOf(head.contentLength));
                }
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(url, new HashMap<String, String>());
                    put(m, "width", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    put(m, "height", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    put(m, "duration", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                    put(m, "bitrate", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
                    put(m, "mime", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE));
                    if (Build.VERSION.SDK_INT >= 23) {
                        put(m, "fps", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE));
                    }
                    if (Build.VERSION.SDK_INT >= 28) {
                        put(m, "frames", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT));
                    }
                } catch (Exception e) {
                    m.put("error", e.getMessage() == null ? "تعذر فتح الملف" : e.getMessage());
                } finally {
                    try { r.release(); } catch (Exception ignored) { }
                }
                return m;
            }
        }, new CgHttp.Done<HashMap<String, String>>() {
            @Override
            public void ok(HashMap<String, String> m) {
                infoResult.removeAllViews();
                int shownCount = 0;
                if (m.containsKey("width") && m.containsKey("height")) {
                    line(infoResult, "الدقة: " + m.get("width") + "×" + m.get("height"), CgCfg.TEXT);
                    shownCount++;
                }
                if (m.containsKey("duration")) {
                    line(infoResult, "المدة: " + CgUi.durationText(parseLong(m.get("duration"))), CgCfg.TEXT);
                    shownCount++;
                }
                if (m.containsKey("size")) {
                    line(infoResult, "الحجم: " + CgUi.sizeText(parseLong(m.get("size"))), CgCfg.TEXT);
                    shownCount++;
                }
                if (m.containsKey("type") || m.containsKey("mime")) {
                    line(infoResult, "النوع: " + (m.containsKey("mime") ? m.get("mime") : m.get("type")), CgCfg.TEXT);
                    shownCount++;
                }
                if (m.containsKey("bitrate")) {
                    line(infoResult, "معدل البت: " + (parseLong(m.get("bitrate")) / 1000) + " kbps", CgCfg.TEXT);
                    shownCount++;
                }
                if (m.containsKey("fps")) {
                    line(infoResult, "FPS: " + m.get("fps"), CgCfg.TEXT);
                    shownCount++;
                }
                if (m.containsKey("frames")) {
                    line(infoResult, "عدد الإطارات: " + m.get("frames"), CgCfg.TEXT);
                    shownCount++;
                }
                if (m.containsKey("error")) line(infoResult, "تعذر قراءة الترميز/الدقة على الجهاز: " + m.get("error"), CgCfg.WARN);
                if (shownCount == 0) line(infoResult, "لا تتوفر معلومات لهذا الملف.", CgCfg.MUTED);
                line(infoResult, "ملاحظة: الترميز (Codec) لا يتوفر من واجهة الأندرويد مباشرة، وتُعرض القيم المتاحة فقط.", CgCfg.MUTED);
            }

            @Override
            public void fail(String message) {
                infoResult.removeAllViews();
                line(infoResult, "✖ " + message, CgCfg.BAD);
            }
        });
    }

    private static void put(HashMap<String, String> m, String k, String v) {
        if (v != null && v.trim().length() > 0) m.put(k, v.trim());
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }

    // ----------------------------------------------------------- subtitle

    private static final Pattern EP_PATTERN = Pattern.compile("[sS](\\d{1,2})[ ._-]*[eE](\\d{1,3})");
    private static final Pattern EP_PATTERN2 = Pattern.compile("(\\d{1,2})x(\\d{1,3})");

    private void testSubtitle(final String url) {
        subResult.removeAllViews();
        subExtra.removeAllViews();
        if (url.length() == 0) {
            line(subExtra, "✖ لا يوجد رابط ترجمة لهذا العنصر.", CgCfg.BAD);
            return;
        }
        runCheck("subtitle", url, subResult);
        // episode match from the file name
        if (CgData.kind(item) == CgData.K_EPISODE) {
            String name = url.substring(url.lastIndexOf('/') + 1);
            try { name = java.net.URLDecoder.decode(name, "UTF-8"); } catch (Exception ignored) { }
            Matcher m = EP_PATTERN.matcher(name);
            if (!m.find()) {
                m = EP_PATTERN2.matcher(name);
                if (!m.find()) m = null;
            }
            if (m != null) {
                int s = Integer.parseInt(m.group(1));
                int e = Integer.parseInt(m.group(2));
                boolean ok = s == CgData.n(item, "season") && e == CgData.n(item, "episode");
                line(subExtra, ok ? "✔ اسم الملف يطابق الحلقة (S" + s + "E" + e + ")"
                                : "✖ اسم الملف S" + s + "E" + e + " لا يطابق هذه الحلقة " + CgData.epCode(item),
                        ok ? CgCfg.GOOD : CgCfg.BAD);
            } else {
                line(subExtra, "تعذر استنتاج رقم الحلقة من اسم الملف — راجع المعاينة يدوياً.", CgCfg.MUTED);
            }
        }
        // sample cues for Arabic rendering
        CgHttp.async(this, new CgHttp.Work<String>() {
            @Override
            public String run() throws Exception {
                CgHttp.Res r = CgHttp.call("GET", url, null, false, 15000, new String[][]{{"Range", "bytes=0-65535"}});
                if (r.error != null) throw r.error;
                if (r.code < 200 || r.code >= 300) return "";
                return r.body;
            }
        }, new CgHttp.Done<String>() {
            @Override
            public void ok(String text) {
                ArrayList<long[]> times = new ArrayList<long[]>();
                ArrayList<String> texts = new ArrayList<String>();
                parseSrt(text, times, texts);
                if (texts.isEmpty()) return;
                line(subExtra, "عيّنة من النص (للتأكد من ظهور العربية):", CgCfg.MUTED);
                for (int i = 0; i < Math.min(4, texts.size()); i++) {
                    TextView t = CgUi.text(CgPlayerActivity.this, texts.get(i), 14, CgCfg.TEXT, false);
                    t.setBackground(CgUi.bg(CgPlayerActivity.this, CgCfg.CARD2, 8, CgCfg.STROKE));
                    t.setPadding(CgUi.dp(CgPlayerActivity.this, 8), CgUi.dp(CgPlayerActivity.this, 6), CgUi.dp(CgPlayerActivity.this, 8), CgUi.dp(CgPlayerActivity.this, 6));
                    subExtra.addView(t, CgUi.lp(CgPlayerActivity.this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));
                }
            }

            @Override
            public void fail(String message) { }
        });
    }

    static void parseSrt(String text, ArrayList<long[]> times, ArrayList<String> texts) {
        if (text == null) return;
        if (text.length() > 0 && text.charAt(0) == '﻿') text = text.substring(1);
        String[] blocks = text.replace("\r", "").split("\n\n+");
        Pattern p = Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})");
        for (int i = 0; i < blocks.length; i++) {
            String[] lines = blocks[i].split("\n");
            for (int j = 0; j < lines.length; j++) {
                Matcher m = p.matcher(lines[j]);
                if (!m.find()) continue;
                long a = ms(m.group(1), m.group(2), m.group(3), m.group(4));
                long b = ms(m.group(5), m.group(6), m.group(7), m.group(8));
                StringBuilder sb = new StringBuilder();
                for (int k = j + 1; k < lines.length; k++) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(lines[k].replaceAll("<[^>]+>", ""));
                }
                if (sb.length() > 0) {
                    times.add(new long[]{a, b});
                    texts.add(sb.toString());
                }
                break;
            }
        }
    }

    private static long ms(String h, String m, String s, String f) {
        while (f.length() < 3) f = f + "0";
        return Long.parseLong(h) * 3600000L + Long.parseLong(m) * 60000L + Long.parseLong(s) * 1000L + Long.parseLong(f.substring(0, 3));
    }

    // ------------------------------------------------------------ preview

    private void startPreview(final String video, final String sub) {
        stopPreview();
        previewHolder.removeAllViews();
        if (video.length() == 0) {
            line(previewHolder, "✖ لا يوجد رابط فيديو.", CgCfg.BAD);
            return;
        }
        if (CgVidbolt.is(video)) {
            addVidboltPlayer(previewHolder, video);
            return;
        }
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(0xFF000000);
        videoView = new VideoView(this);
        box.addView(videoView, new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.dp(this, 220), Gravity.CENTER));
        overlay = CgUi.text(this, "", 15, 0xFFFFFFFF, true);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(0x99000000);
        overlay.setPadding(CgUi.dp(this, 8), CgUi.dp(this, 3), CgUi.dp(this, 8), CgUi.dp(this, 3));
        overlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP, Gravity.BOTTOM);
        olp.bottomMargin = CgUi.dp(this, 36);
        box.addView(overlay, olp);
        previewHolder.addView(box, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        final TextView status = CgUi.muted(this, "جارٍ تحميل الفيديو...");
        previewHolder.addView(status);

        MediaController mc = new MediaController(this);
        mc.setAnchorView(videoView);
        videoView.setMediaController(mc);
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                status.setText("تم التحميل — المدة " + CgUi.durationText(mp.getDuration()));
                status.setTextColor(CgCfg.GOOD);
                videoView.start();
            }
        });
        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                CgUi.set(status, "✖ تعذر تشغيل الفيديو (رمز " + what + "/" + extra + "). جرّب «اختبر الفيديو» لمعرفة السبب.");
                status.setTextColor(CgCfg.BAD);
                return true;
            }
        });
        try {
            videoView.setVideoURI(Uri.parse(video));
        } catch (Exception e) {
            CgUi.set(status, "✖ " + e.getMessage());
            status.setTextColor(CgCfg.BAD);
        }

        if (sub.length() > 0) {
            CgHttp.async(this, new CgHttp.Work<String>() {
                @Override
                public String run() throws Exception {
                    CgHttp.Res r = CgHttp.call("GET", sub, null, false, 15000, null);
                    if (r.error != null) throw r.error;
                    if (r.code < 200 || r.code >= 300) throw new Exception("HTTP " + r.code);
                    return r.body;
                }
            }, new CgHttp.Done<String>() {
                @Override
                public void ok(String text) {
                    cueTimes.clear();
                    cueTexts.clear();
                    parseSrt(text, cueTimes, cueTexts);
                    line(previewHolder, "تم تحميل الترجمة: " + cueTexts.size() + " سطراً.", cueTexts.isEmpty() ? CgCfg.BAD : CgCfg.GOOD);
                    startTicker();
                }

                @Override
                public void fail(String message) {
                    line(previewHolder, "✖ تعذر تحميل الترجمة: " + message, CgCfg.BAD);
                }
            });
        } else {
            line(previewHolder, "لا توجد ترجمة لهذا العنصر.", CgCfg.WARN);
        }
    }

    private void startTicker() {
        ticker = new Runnable() {
            @Override
            public void run() {
                if (!alive() || videoView == null) return;
                try {
                    long pos = videoView.getCurrentPosition();
                    String show = "";
                    for (int i = 0; i < cueTimes.size(); i++) {
                        long[] t = cueTimes.get(i);
                        if (pos >= t[0] && pos <= t[1]) {
                            show = cueTexts.get(i);
                            break;
                        }
                    }
                    if (show.length() == 0) {
                        overlay.setVisibility(View.GONE);
                    } else {
                        overlay.setText(show);
                        overlay.setVisibility(View.VISIBLE);
                    }
                } catch (Exception ignored) { }
                ui().postDelayed(this, 250);
            }
        };
        ui().post(ticker);
    }

    private void stopPreview() {
        if (ticker != null) ui().removeCallbacks(ticker);
        ticker = null;
        try {
            if (videoView != null) videoView.stopPlayback();
        } catch (Exception ignored) { }
        videoView = null;
        hideVbCustom();
        try {
            if (vbWeb != null) {
                vbWeb.stopLoading();
                vbWeb.loadUrl("about:blank");
                vbWeb.destroy();
            }
        } catch (Exception ignored) { }
        vbWeb = null;
    }
}
