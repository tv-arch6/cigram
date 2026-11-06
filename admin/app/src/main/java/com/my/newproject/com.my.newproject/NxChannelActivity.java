package com.my.newproject;

import android.content.Intent;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * New "add channel" screen: ① type (the channel section) → ② link, image and name with a live preview of the
 * card as it will look → add → ③ preview after adding (play test, add another).
 * Creation goes through the channels Worker exactly like the classic screen (POST /channels/add).
 */
public class NxChannelActivity extends CxBase {

    private static final int REQ_LOGO = 7821;

    private final ArrayList<JSONObject> channels = new ArrayList<JSONObject>();
    private boolean listKnown;
    private int step;               // 0 type, 1 details, 2 done
    private String category = "";
    private String name = "";
    private String url = "";
    private String imageUrl = "";
    private Bitmap logo;
    private int imageMode;          // 0 phone, 1 link
    private String probe = "";      // "", "testing", "ok", or a failure code
    private JSONObject result;

    // live preview views
    private ImageView pvLogo;
    private TextView pvName;
    private TextView pvType;
    private TextView pvStatus;
    private TextView addBtn;
    private CxUi.Field nameField;
    private CxUi.Field urlField;
    private CxUi.Field imgField;
    private LinearLayout imageBox;

    @Override
    protected String screenTitle() {
        return "إضافة قناة";
    }

    @Override
    protected void onBuild() {
        initCx(null);
        try {
            bar.view.setBackgroundColor(NxUi.BG);
        } catch (Throwable ignored) { }
        load();
    }

    @Override
    public void onBackPressed() {
        if (step == 1) {
            step = 0;
            render();
            return;
        }
        super.onBackPressed();
    }

    private int dp(float v) {
        return CgUi.dp(this, v);
    }

    // ======================================================================= load

    private void load() {
        page.removeAllViews();
        page.addView(CxUi.skeleton(this, 3));
        setBottomVisible(false);
        exec(new Job<JSONArray>() {
            @Override
            public JSONArray run() throws Exception {
                CgHttp.Res r = CgHttp.call("GET", CgCfg.CHANNELS_API + "/channels", null, false, 20000, null);
                if (r.error != null) throw new CxApi.Failure(CgHttp.describe(r.error), "NETWORK", null, 0);
                if (!r.ok() || r.json == null) throw new CxApi.Failure(CgHttp.friendly(r.message()), "CHANNELS", null, r.code);
                return flatten(r.json);
            }
        }, new Cb<JSONArray>() {
            @Override
            public void ok(JSONArray list) {
                channels.clear();
                for (int i = 0; i < list.length(); i++) channels.add(CxJ.at(list, i));
                listKnown = true;
                render();
            }

            @Override
            public void fail(CxApi.Failure f) {
                page.removeAllViews();
                page.addView(CgUi.errorCard(NxChannelActivity.this, f.getMessage(), new Runnable() {
                    @Override
                    public void run() {
                        load();
                    }
                }));
                TextView offline = NxUi.secondary(NxChannelActivity.this, "المتابعة بدون قائمة الأنواع");
                offline.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        listKnown = false;
                        render();
                    }
                });
                page.addView(offline, NxUi.lp(NxChannelActivity.this, NxUi.MATCH, NxUi.WRAP, 0, 10, 0, 0));
            }
        });
    }

    static JSONArray flatten(JSONObject root) throws Exception {
        JSONArray out = new JSONArray();
        JSONArray cats = root.optJSONArray("categories");
        if (cats == null) cats = root.optJSONArray("sections");
        for (int i = 0; cats != null && i < cats.length(); i++) {
            JSONObject cat = cats.optJSONObject(i);
            if (cat == null) continue;
            String cname = cat.optString("name", "");
            JSONArray arr = cat.optJSONArray("channels");
            if (arr == null) arr = cat.optJSONArray("items");
            for (int k = 0; arr != null && k < arr.length(); k++) {
                JSONObject ch = arr.optJSONObject(k);
                if (ch != null) out.put(one(ch, cname));
            }
            if ((arr == null || arr.length() == 0) && cname.length() > 0) {
                JSONObject empty = new JSONObject();
                empty.put("category", cname);
                empty.put("empty", true);
                out.put(empty);
            }
        }
        if (out.length() == 0) {
            JSONArray flat = root.optJSONArray("channels");
            if (flat == null) flat = root.optJSONArray("items");
            for (int k = 0; flat != null && k < flat.length(); k++) {
                JSONObject ch = flat.optJSONObject(k);
                if (ch != null) out.put(one(ch, ch.optString("category", "")));
            }
        }
        return out;
    }

    private static JSONObject one(JSONObject ch, String category) throws Exception {
        JSONObject c = new JSONObject();
        c.put("id", ch.optString("id", ""));
        c.put("name", ch.optString("name", ""));
        c.put("url", ch.optString("url", ch.optString("stream", "")));
        c.put("image", ch.optString("image", ch.optString("logo", ch.optString("image_url", ch.optString("icon", "")))));
        c.put("category", category.length() > 0 ? category : ch.optString("category", ""));
        return c;
    }

    // ===================================================================== render

    private void render() {
        page.removeAllViews();
        if (step == 0) renderTypes();
        else if (step == 1) renderDetails();
        else renderDone();
    }

    // ------------------------------------------------------------------ ① type

    private void renderTypes() {
        setBottomVisible(false);
        page.addView(NxParts.step(this, 1, "نوع القناة", "القسم الذي تظهر فيه القناة داخل التطبيق", false, true));
        LinkedHashMap<String, int[]> counts = new LinkedHashMap<String, int[]>();
        LinkedHashMap<String, String> logos = new LinkedHashMap<String, String>();
        for (JSONObject c : channels) {
            String cat = CxJ.s(c, "category");
            if (cat.length() == 0) continue;
            int[] n = counts.get(cat);
            if (n == null) {
                n = new int[]{0};
                counts.put(cat, n);
            }
            if (!c.optBoolean("empty")) n[0]++;
            if (!logos.containsKey(cat) && CxJ.s(c, "image").startsWith("http")) logos.put(cat, CxJ.s(c, "image"));
        }
        int screen = getResources().getDisplayMetrics().widthPixels;
        int w = (screen - dp(24) - dp(10)) / 2;
        LinearLayout row = null;
        int i = 0;
        int[] palette = {NxUi.TEAL, NxUi.BLUE, NxUi.VIOLET, NxUi.ORANGE, NxUi.PINK, 0xFF22D3EE};
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            if (i % 2 == 0) {
                row = CgUi.hbox(this);
                page.addView(row, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, i == 0 ? 0 : 10, 0, 0));
            }
            View t = typeTile(e.getKey(), e.getValue()[0], logos.get(e.getKey()), palette[i % palette.length]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, NxUi.WRAP);
            lp.setMargins(i % 2 == 0 ? 0 : dp(10), 0, 0, 0);
            row.addView(t, lp);
            NxUi.enter(t, Math.min(8, i) * 35L);
            i++;
        }
        // new type
        LinearLayout add = CgUi.vbox(this);
        add.setGravity(Gravity.CENTER);
        NxUi.pressable(add, NxUi.dashed(this, NxUi.TEAL, 18), 18);
        add.setPadding(dp(10), dp(18), dp(10), dp(18));
        add.addView(NxUi.icon(this, NxIcons.icon(CgIcons.PLUS, NxUi.TEAL), 0), new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView at = CgUi.text(this, "نوع جديد", 13.5f, NxUi.TEAL, true);
        at.setGravity(Gravity.CENTER);
        add.addView(at, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 6, 0, 0));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUi.input(NxChannelActivity.this, "اسم النوع الجديد", "مثال: رياضة، أخبار، أطفال", "", false, new CgUi.Callback<String>() {
                    @Override
                    public void run(String value) {
                        if (value.trim().length() == 0) return;
                        chooseType(value.trim());
                    }
                });
            }
        });
        if (i % 2 == 0) {
            row = CgUi.hbox(this);
            page.addView(row, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, i == 0 ? 0 : 10, 0, 0));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, NxUi.MATCH);
        lp.setMargins(i % 2 == 0 ? 0 : dp(10), 0, 0, 0);
        row.addView(add, lp);
        if (!listKnown) {
            page.addView(CgUi.text(this, "تعذر تحميل الأنواع الحالية — اكتب اسم النوع كما هو في التطبيق.", 11.5f, NxUi.ORANGE, false),
                    NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 4, 12, 4, 0));
        }
        page.addView(NxParts.step(this, 2, "الرابط والصورة والاسم", "بعد اختيار النوع", false, false));
    }

    private View typeTile(final String cat, int count, String logoUrl, int color) {
        LinearLayout t = CgUi.vbox(this);
        NxUi.pressable(t, NxUi.surface(this, 18), 18);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout top = CgUi.hbox(this);
        ImageView ic = new ImageView(this);
        ic.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ic.setClipToOutline(true);
        if (logoUrl != null && logoUrl.length() > 0) {
            ic.setBackground(NxUi.flat(this, NxUi.SURF_HI, 10, 0));
            CgImg.load(ic, logoUrl, 120);
        } else {
            ic.setImageDrawable(NxIcons.icon(NxIcons.ANTENNA, color));
            ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ic.setPadding(dp(6), dp(6), dp(6), dp(6));
            ic.setBackground(NxUi.tint(this, color, 10));
        }
        top.addView(ic, new LinearLayout.LayoutParams(dp(34), dp(34)));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView n = CgUi.text(this, String.valueOf(count), 12, color, true);
        n.setBackground(CgUi.bg(this, NxUi.alpha(color, 0x1F), 10, 0));
        n.setPadding(dp(8), dp(2), dp(8), dp(2));
        top.addView(n);
        t.addView(top);
        TextView nm = CgUi.text(this, cat, 14.5f, NxUi.TEXT, true);
        nm.setMaxLines(1);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(nm, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 10, 0, 0));
        t.addView(CgUi.text(this, count + " قناة", 11, NxUi.SUB, false));
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseType(cat);
            }
        });
        return t;
    }

    private void chooseType(String cat) {
        category = cat;
        step = 1;
        render();
        scrollToTop();
    }

    // -------------------------------------------------------------- ② details

    private void renderDetails() {
        page.addView(NxParts.step(this, 1, "النوع: " + category, "اضغط هنا لتغييره", true, true));
        View back = page.getChildAt(page.getChildCount() - 1);
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step = 0;
                render();
            }
        });
        page.addView(NxParts.step(this, 2, "الرابط والصورة والاسم", "المعاينة تتحدث مع كل حقل", false, true));

        // live preview: how the card looks in the app
        LinearLayout pv = CgUi.vbox(this);
        pv.setBackground(NxUi.flat(this, 0xFF070C16, 22, NxUi.LINE));
        pv.setPadding(dp(14), dp(12), dp(14), dp(14));
        LinearLayout ph = CgUi.hbox(this);
        ph.addView(NxUi.icon(this, NxIcons.icon(CgIcons.EYE, NxUi.SUB), 0), new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView pl = CgUi.text(this, "معاينة كما تظهر في التطبيق", 11.5f, NxUi.SUB, true);
        LinearLayout.LayoutParams pll = new LinearLayout.LayoutParams(NxUi.WRAP, NxUi.WRAP);
        pll.setMargins(dp(6), 0, 0, 0);
        ph.addView(pl, pll);
        pv.addView(ph);
        LinearLayout cardRow = CgUi.hbox(this);
        cardRow.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout logoWrap = new FrameLayout(this);
        pvLogo = new ImageView(this);
        pvLogo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pvLogo.setBackground(NxUi.flat(this, NxUi.SURF_HI, 18, NxUi.LINE));
        pvLogo.setClipToOutline(true);
        logoWrap.addView(pvLogo, new FrameLayout.LayoutParams(dp(84), dp(84)));
        cardRow.addView(logoWrap, new LinearLayout.LayoutParams(dp(84), dp(84)));
        LinearLayout pcol = CgUi.vbox(this);
        pvName = CgUi.text(this, "", 17, NxUi.TEXT, true);
        pvName.setMaxLines(1);
        pvName.setEllipsize(TextUtils.TruncateAt.END);
        pcol.addView(pvName);
        pvType = NxUi.pill(this, category, NxUi.TEAL);
        pcol.addView(pvType, NxUi.lp(this, NxUi.WRAP, NxUi.WRAP, 0, 6, 0, 0));
        pvStatus = CgUi.text(this, "", 11.5f, NxUi.SUB, true);
        pcol.addView(pvStatus, NxUi.lp(this, NxUi.WRAP, NxUi.WRAP, 0, 6, 0, 0));
        LinearLayout.LayoutParams pcl = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        pcl.setMargins(dp(14), 0, 0, 0);
        cardRow.addView(pcol, pcl);
        pv.addView(cardRow, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));
        page.addView(pv);

        // fields
        LinearLayout form = CgUi.vbox(this);
        form.setBackground(NxUi.surface(this, 20));
        form.setPadding(dp(14), dp(14), dp(14), dp(8));

        urlField = new CxUi.Field(this, "رابط البث", "https://.../playlist.m3u8", CxUi.T_URL, true);
        urlField.set(url);
        urlField.onChange(new Runnable() {
            @Override
            public void run() {
                url = urlField.text();
                probe = "";
                refreshPreview();
            }
        });
        form.addView(urlField.view);
        LinearLayout urow = CgUi.hbox(this);
        TextView paste = NxUi.secondary(this, "لصق");
        paste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String c = NxParts.clipboard(NxChannelActivity.this);
                if (CxJ.isUrl(c)) {
                    urlField.input.setText(c);
                    testStream();
                } else {
                    snack("لا يوجد رابط منسوخ.", CxUi.WARN);
                }
            }
        });
        urow.addView(paste, new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f));
        TextView test = NxUi.secondary(this, "اختبار البث");
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testStream();
            }
        });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        tl.setMargins(dp(8), 0, 0, 0);
        urow.addView(test, tl);
        TextView play = NxUi.secondary(this, "تشغيل");
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!CxJ.isUrl(url)) {
                    urlField.error("أدخل رابطاً صحيحاً أولاً.");
                    return;
                }
                CxMedia.openPlayer(NxChannelActivity.this, name, url, "");
            }
        });
        LinearLayout.LayoutParams pl2 = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        pl2.setMargins(dp(8), 0, 0, 0);
        urow.addView(play, pl2);
        form.addView(urow, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 0, 0, 14));

        TextView il = CgUi.text(this, "صورة القناة *", 12, NxUi.SUB, false);
        il.setPadding(dp(4), 0, dp(4), dp(6));
        form.addView(il);
        form.addView(NxUi.segmented(this, new String[]{"من الجهاز", "برابط"}, new int[]{CgIcons.IMAGE, CgIcons.LINK}, imageMode, new NxUi.SegCb() {
            @Override
            public void run(int index) {
                imageMode = index;
                renderImageBox();
            }
        }));
        imageBox = CgUi.vbox(this);
        form.addView(imageBox, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 10, 0, 12));
        renderImageBox();

        nameField = new CxUi.Field(this, "اسم القناة", "مثال: قناة الرياضة 1", CxUi.T_TEXT, true);
        nameField.set(name);
        nameField.onChange(new Runnable() {
            @Override
            public void run() {
                name = nameField.text();
                refreshPreview();
            }
        });
        form.addView(nameField.view);
        page.addView(form, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));

        bottom.removeAllViews();
        bottom.setVisibility(View.VISIBLE);
        addBtn = NxUi.primary(this, "إضافة القناة");
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(false);
            }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        bl.setMargins(dp(4), dp(2), dp(4), dp(2));
        bottom.addView(addBtn, bl);
        refreshPreview();
    }

    private void renderImageBox() {
        if (imageBox == null) return;
        imageBox.removeAllViews();
        if (imageMode == 0) {
            LinearLayout z = CgUi.hbox(this);
            z.setGravity(Gravity.CENTER_VERTICAL);
            NxUi.pressable(z, NxUi.dashed(this, NxUi.TEAL, 16), 16);
            z.setPadding(dp(14), dp(14), dp(14), dp(14));
            z.addView(NxUi.icon(this, NxIcons.icon(CgIcons.IMAGE, NxUi.TEAL), 0), new LinearLayout.LayoutParams(dp(26), dp(26)));
            TextView t = CgUi.text(this, logo == null ? "اختر صورة من الجهاز (مربعة يفضّل)" : "تغيير الصورة", 13.5f, NxUi.TEXT, true);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
            l.setMargins(dp(10), 0, 0, 0);
            z.addView(t, l);
            z.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        CxMedia.pickImage(NxChannelActivity.this, REQ_LOGO);
                    } catch (Throwable e) {
                        snack("تعذر فتح الصور.", CxUi.ERROR);
                    }
                }
            });
            imageBox.addView(z);
        } else {
            imgField = new CxUi.Field(this, "رابط الصورة", "https://.../logo.png", CxUi.T_URL, false);
            imgField.set(imageUrl);
            imgField.onChange(new Runnable() {
                @Override
                public void run() {
                    final String u = imgField.text();
                    imageUrl = u;
                    if (!CxJ.isUrl(u)) return;
                    ui().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (u.equals(imageUrl)) downloadLogo(u);
                        }
                    }, 600);
                }
            });
            imageBox.addView(imgField.view);
        }
    }

    private void downloadLogo(final String u) {
        if (imgField != null) imgField.message(CxUi.INFO, "جارٍ تحميل الصورة…");
        CgHttp.bg(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = CxMedia.download(u, 600);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!alive() || !u.equals(imageUrl)) return;
                        if (b == null) {
                            if (imgField != null) imgField.error("تعذر تحميل الصورة من هذا الرابط.");
                            return;
                        }
                        logo = b;
                        if (imgField != null) imgField.ok("تم تحميل الصورة " + b.getWidth() + "×" + b.getHeight());
                        refreshPreview();
                    }
                });
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_LOGO || res != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        showLoading("جارٍ تجهيز الصورة...");
        CgHttp.bg(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = CxMedia.decode(NxChannelActivity.this, uri, 600);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!alive()) return;
                        hideLoading();
                        if (b == null) {
                            snack("تعذر قراءة الصورة. اختر صورة أخرى.", CxUi.ERROR);
                            return;
                        }
                        logo = b;
                        renderImageBox();
                        refreshPreview();
                    }
                });
            }
        });
    }

    private void refreshPreview() {
        if (pvName == null) return;
        pvName.setText(name.length() > 0 ? name : "اسم القناة");
        pvName.setTextColor(name.length() > 0 ? NxUi.TEXT : NxUi.DIM);
        if (logo != null) {
            pvLogo.setImageBitmap(logo);
            pvLogo.setPadding(0, 0, 0, 0);
            pvLogo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            pvLogo.setImageDrawable(NxIcons.icon(NxIcons.ANTENNA, NxUi.DIM));
            pvLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            pvLogo.setPadding(dp(22), dp(22), dp(22), dp(22));
        }
        int c;
        String s;
        if ("ok".equals(probe)) {
            c = NxUi.UP;
            s = "● البث يعمل";
        } else if ("testing".equals(probe)) {
            c = NxUi.SUB;
            s = "جارٍ اختبار البث…";
        } else if (probe.length() > 0) {
            c = NxUi.DOWN;
            s = "البث لا يستجيب (" + CgData.checkLabel(probe) + ")";
        } else {
            c = CxJ.isUrl(url) ? NxUi.SUB : NxUi.DIM;
            s = CxJ.isUrl(url) ? "الرابط جاهز — اختبره" : "بانتظار رابط البث";
        }
        pvStatus.setText(s);
        pvStatus.setTextColor(c);
        if (addBtn != null) NxUi.enable(addBtn, name.length() > 0 && CxJ.isUrl(url) && logo != null && category.length() > 0);
    }

    private void testStream() {
        if (!CxJ.isUrl(url)) {
            if (urlField != null) urlField.error("الرابط يجب أن يبدأ بـ http:// أو https://");
            return;
        }
        probe = "testing";
        refreshPreview();
        final String u = url;
        CgHttp.bg(new Runnable() {
            @Override
            public void run() {
                final String[] r = CgChannelsActivity.probe(u);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!alive() || !u.equals(url)) return;
                        probe = r[0];
                        refreshPreview();
                    }
                });
            }
        });
    }

    private JSONObject findDuplicate() {
        if (!listKnown) return null;
        String n = CgData.normalize(name);
        for (JSONObject c : channels) {
            if (c.optBoolean("empty")) continue;
            if (url.equals(CxJ.s(c, "url"))) return c;
            if (n.length() > 0 && n.equals(CgData.normalize(CxJ.s(c, "name"))) && category.equals(CxJ.s(c, "category"))) return c;
        }
        return null;
    }

    private void save(boolean confirmed) {
        if (name.length() == 0 || !CxJ.isUrl(url) || logo == null) {
            snack("أكمل الرابط والصورة والاسم.", CxUi.WARN);
            return;
        }
        if (!confirmed) {
            JSONObject d = findDuplicate();
            if (d != null) {
                CgUi.confirm(this, "قناة مشابهة موجودة", "توجد قناة «" + CxJ.s(d, "name") + "» (" + CxJ.s(d, "category") + ") بنفس "
                        + (url.equals(CxJ.s(d, "url")) ? "الرابط" : "الاسم") + ".\nإضافة هذه رغم ذلك؟", "إضافة", false, new Runnable() {
                    @Override
                    public void run() {
                        save(true);
                    }
                });
                return;
            }
        }
        final String nm = name;
        final String cat = category;
        final String u = url;
        final Bitmap bmp = logo;
        final String pr = probe;
        final CxStages sg = CxStages.show(this, "إضافة القناة", new String[]{"تجهيز الصورة", "إنشاء القناة", "اكتمل"});
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                sg.stage(0, nm);
                byte[] jpg = CxMedia.jpegFlat(bmp, CgCfg.CARD, 90);
                JSONObject body = new JSONObject();
                body.put("category", cat);
                body.put("name", nm);
                body.put("url", u);
                body.put("image_base64", CxMedia.base64(jpg));
                body.put("image_mime", "image/jpeg");
                sg.stage(1, CgUi.sizeText(jpg.length));
                CgHttp.Res r = CgHttp.call("POST", CgCfg.CHANNELS_API + "/channels/add", body, false, 60000,
                        new String[][]{{"X-Admin-Token", CgCfg.TOKEN}});
                if (r.error != null) throw new CxApi.Failure(CgHttp.describe(r.error), "NETWORK", null, 0);
                if (!r.ok() || r.json == null) {
                    String m = r.code == 401 || r.code == 403 ? "رمز الإدارة غير مقبول في Worker القنوات." : CgHttp.friendly(r.message());
                    throw new CxApi.Failure(m, "CHANNEL_ADD", r.json, r.code);
                }
                JSONObject ch = r.json.optJSONObject("channel");
                String id = r.json.optString("id", ch == null ? "" : ch.optString("id", ""));
                String img = r.json.optString("image", r.json.optString("image_url", ""));
                if (img.length() == 0 && ch != null) img = ch.optString("image", ch.optString("image_url", ""));
                JSONObject out = new JSONObject();
                out.put("id", id);
                out.put("image", img);
                // best effort: remember the stream test in the admin overlay (same as the classic screen)
                if (pr.length() > 0 && !"testing".equals(pr) && id.length() > 0) {
                    try {
                        JSONObject f = new JSONObject();
                        f.put("online", "ok".equals(pr));
                        f.put("last_check_at", CgUi.toIso(System.currentTimeMillis()));
                        f.put("last_check_status", pr);
                        JSONObject b = new JSONObject();
                        b.put("key", id);
                        b.put("fields", f);
                        CgHttp.apiPost("/admin/channel-meta/save", b);
                    } catch (Exception ignored) { }
                }
                sg.stage(2, "");
                return out;
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                sg.dismiss();
                result = r;
                CxRecent.push(NxChannelActivity.this, "channel", nm, CxJ.s(r, "image"), cat);
                JSONObject c = new JSONObject();
                CxJ.put(c, "name", nm);
                CxJ.put(c, "url", u);
                CxJ.put(c, "category", cat);
                CxJ.put(c, "image", CxJ.s(r, "image"));
                channels.add(c);
                step = 2;
                render();
                scrollToTop();
            }

            @Override
            public void fail(CxApi.Failure f) {
                sg.fail(f.getMessage(), new Runnable() {
                    @Override
                    public void run() {
                        save(true);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ ③ done

    private void renderDone() {
        page.addView(NxParts.step(this, 3, "تمت الإضافة", "هكذا تظهر القناة الآن في التطبيق", true, true));
        LinearLayout card = CgUi.vbox(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(NxUi.surface(this, 24));
        card.setPadding(dp(16), dp(22), dp(16), dp(18));
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(NxUi.flat(this, NxUi.SURF_HI, 26, NxUi.LINE));
        iv.setClipToOutline(true);
        iv.setElevation(dp(6));
        String img = result == null ? "" : CxJ.s(result, "image");
        if (img.startsWith("http")) CgImg.load(iv, img, 300);
        else if (logo != null) iv.setImageBitmap(logo);
        card.addView(iv, new LinearLayout.LayoutParams(dp(120), dp(120)));
        TextView n = CgUi.text(this, name, 19, NxUi.TEXT, true);
        n.setGravity(Gravity.CENTER);
        card.addView(n, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 14, 0, 0));
        LinearLayout pills = CgUi.hbox(this);
        pills.setGravity(Gravity.CENTER);
        pills.addView(NxUi.pill(this, category, NxUi.TEAL));
        if ("ok".equals(probe)) {
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(NxUi.WRAP, NxUi.WRAP);
            pl.setMargins(dp(6), 0, 0, 0);
            pills.addView(NxUi.pill(this, "● مباشر", NxUi.UP), pl);
        }
        card.addView(pills, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 8, 0, 0));
        TextView u = CgUi.text(this, url, 11, NxUi.DIM, false);
        CgUi.ltr(u);
        u.setGravity(Gravity.CENTER);
        u.setSingleLine(true);
        u.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        card.addView(u, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 8, 0, 0));
        TextView play = NxUi.primary(this, "▶  تشغيل القناة للتجربة");
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CxMedia.openPlayer(NxChannelActivity.this, name, url, "");
            }
        });
        card.addView(play, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 16, 0, 0));
        page.addView(card);
        NxUi.enter(card, 0);

        LinearLayout row = CgUi.hbox(this);
        TextView again = NxUi.secondary(this, "قناة أخرى بنفس النوع");
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                name = "";
                url = "";
                imageUrl = "";
                logo = null;
                probe = "";
                result = null;
                step = 1;
                render();
            }
        });
        row.addView(again, new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f));
        TextView manage = NxUi.secondary(this, "إدارة القنوات");
        manage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(NxChannelActivity.this, CgChannelsActivity.class));
            }
        });
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        ml.setMargins(dp(8), 0, 0, 0);
        row.addView(manage, ml);
        page.addView(row, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));

        bottom.removeAllViews();
        TextView done = NxUi.secondary(this, "تم");
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        bl.setMargins(dp(4), dp(2), dp(4), dp(2));
        bottom.addView(done, bl);
        bottom.setVisibility(View.VISIBLE);
    }
}
