package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * قسم «خطوطي» (v3.8): صفحة كاملة بزر رجوع + اسم قسم، تتحكم بخط الترجمة وخلفيتها ولون نصّها،
 * وتتيح للمستخدم استيراد خط خاص من جهازه يُحفظ محلياً داخل تخزين التطبيق ويُطبَّق على الترجمة فعلياً.
 *
 * التوافق: نستخدم نفس ملف الإعدادات "subtitle_settings" ونفس مفتاح "subtitle_font" الذي يقرأه المشغّل،
 * ونضيف مفاتيح جديدة (لون الخلفية، لون النص، قائمة الخطوط المستوردة) دون كسر السلوك القديم.
 *
 * ملاحظة حول «الحفظ في الحساب»: الحفظ هنا محلي داخل تخزين التطبيق (يبقى بعد التحديث، ويُحذف عند إزالة التطبيق).
 * مزامنته مع الحساب على السحابة تتطلب نقطة رفع في الـ API الخاص بكم؛ الكود مُهيّأ لذلك عبر uploadHook.
 */
public final class CigramFonts {

    private static final String PREFS = "subtitle_settings";
    private static final String K_FONT = "subtitle_font";
    private static final String K_BG_MODE = "subtitle_background";     // none | black | color
    private static final String K_BG_COLOR = "subtitle_bg_color";
    private static final String K_TEXT_COLOR = "subtitle_text_color";
    private static final String K_PREVIEW = "preview_text";            // the viewer's own preview words
    private static final String K_CUSTOM = "custom_fonts";             // JSONArray [{file,name}]
    private static final String PAGE_TAG = "cigram_fonts_page";
    private static final String SUBFONT_DIR = "subfonts";

    private static final String SAMPLE = "كن صادقاً، ووفياً ياصديقي.";

    // منتقي الملفات يُسجَّل في onCreate (قيد الـ Activity Result) ويُستعمل عند الضغط على «إضافة خط».
    private static Object sPicker;                 // androidx.activity.result.ActivityResultLauncher<String[]>
    private static java.lang.ref.WeakReference<Activity> sActivity =
            new java.lang.ref.WeakReference<Activity>(null);
    private static Runnable sRefresh;

    private CigramFonts() { }

    // ---- الخطوط المدمجة -------------------------------------------------

    /** المفتاح الافتراضي لخط الترجمة (كما كان: مهنّد). */
    static final String DEFAULT_KEY = "lmohanad";

    /** {key, اسم العرض, مسار الملف}. كل ملف هنا موجود في assets/fonts؛ «بيروتي» هو خط واجهة التطبيق. */
    private static final String[][] BUILTIN = {
            {"lmohanad", "مهنّد", "fonts/lmohanad.ttf"},
            {"lmohanadbold", "مهنّد عريض", "fonts/lmohanadbold.ttf"},
            {"lfont_com_alyamamabold", "اليمامة عريض", "fonts/lfont_com_alyamamabold.ttf"},
            {"lfont_com_alyamamasemibold", "اليمامة نصف عريض", "fonts/lfont_com_alyamamasemibold.ttf"},
            {"lfont_com_nsd96generosityotf", "جينيروسِتي", "fonts/lfont_com_nsd96generosityotf.ttf"},
            {"amiri_regular", "أميري", "fonts/amiri_regular.ttf"},
            {"amiri_bold", "أميري عريض", "fonts/amiri_bold.ttf"},
            {"cairo_regular", "القاهرة", "fonts/cairo_regular.ttf"},
            {"cairo_bold", "القاهرة عريض", "fonts/cairo_bold.ttf"},
            {"scheherazade_regular", "شهرزاد", "fonts/scheherazade_regular.ttf"},
            {"scheherazade_bold", "شهرزاد عريض", "fonts/scheherazade_bold.ttf"},
            {"beiruti", "بيروتي", CigramTypefaces.APP_FONT}
    };

    /** مفتاح صالح دائماً: المفتاح المجهول (أو القديم المحذوف) يعود إلى الخط الافتراضي. */
    private static String normalizeKey(String key) {
        if (key == null || key.length() == 0) return DEFAULT_KEY;
        if (key.startsWith("custom:")) return key;
        for (String[] row : BUILTIN) if (row[0].equals(key)) return key;
        return DEFAULT_KEY;
    }

    /** مسار ملف الخط المدمج لمفتاح محفوظ (المفتاح المجهول يعطي مسار الخط الافتراضي بدل السقوط على خط النظام بصمت). */
    static String assetPathForKey(String key) {
        String wanted = normalizeKey(key);
        for (String[] row : BUILTIN) if (row[0].equals(wanted)) return row[2];
        return "fonts/lmohanad.ttf";
    }

    // ---- قيم افتراضية ---------------------------------------------------

    private static final int DEFAULT_TEXT = 0xFFFFFFFF;

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static Typeface appFont(Context c) {
        return CigramStage1InitProvider.cigramFont(c);     // v4.4: shared, loaded once
    }

    // =====================================================================
    //  واجهات يستعملها المشغّل (PlayerActivity)
    // =====================================================================

    /** خط الترجمة النهائي: خط مستورد إن اختير، وإلا الخط المدمج الممرَّر من المشغّل. */
    public static Typeface subtitleTypeface(Context c, Typeface fallback) {
        try {
            String key = prefs(c).getString(K_FONT, DEFAULT_KEY);
            if (key != null && key.startsWith("custom:")) {
                File f = new File(subfontDir(c), key.substring("custom:".length()));
                if (f.isFile() && f.length() > 0) {
                    Typeface t = Typeface.createFromFile(f);
                    if (t != null) return t;
                }
            }
        } catch (Throwable ignored) { }
        return fallback != null ? fallback : Typeface.DEFAULT;
    }

    /** لون خلفية الترجمة اعتماداً على الوضع المحفوظ (يحافظ على القيم القديمة none/black). */
    public static int subtitleBackgroundColor(Context c, String mode) {
        try {
            if (mode == null) mode = prefs(c).getString(K_BG_MODE, "black");
            if ("none".equals(mode)) return Color.TRANSPARENT;
            if ("color".equals(mode)) return prefs(c).getInt(K_BG_COLOR, Color.BLACK);
            return Color.BLACK;
        } catch (Throwable ignored) {
            return Color.BLACK;
        }
    }

    public static int subtitleTextColor(Context c) {
        try {
            return prefs(c).getInt(K_TEXT_COLOR, DEFAULT_TEXT);
        } catch (Throwable ignored) {
            return DEFAULT_TEXT;
        }
    }

    // =====================================================================
    //  تسجيل منتقي الخطوط (يُستدعى من onCreate لصفحة الحساب)
    // =====================================================================

    // نستعمل الانعكاس (reflection) على AndroidX Activity Result حتى لا يرتبط البناء بوجود المكتبة؛
    // إن لم تكن موجودة يفشل التسجيل بهدوء ويبقى بقية القسم يعمل بالخطوط المدمجة.
    public static void installPicker(final Activity activity) {
        if (activity == null) return;
        try {
            Class<?> callerCls = Class.forName("androidx.activity.result.ActivityResultCaller");
            if (!callerCls.isInstance(activity)) return;
            sActivity = new java.lang.ref.WeakReference<Activity>(activity);

            Class<?> openDocCls = Class.forName(
                    "androidx.activity.result.contract.ActivityResultContracts$OpenDocument");
            Object contract = openDocCls.getDeclaredConstructor().newInstance();
            Class<?> contractCls = Class.forName(
                    "androidx.activity.result.contract.ActivityResultContract");
            Class<?> cbCls = Class.forName("androidx.activity.result.ActivityResultCallback");

            Object callback = java.lang.reflect.Proxy.newProxyInstance(
                    cbCls.getClassLoader(),
                    new Class<?>[]{cbCls},
                    new java.lang.reflect.InvocationHandler() {
                        @Override public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                                throws Throwable {
                            String n = method.getName();
                            if ("onActivityResult".equals(n) && args != null && args.length == 1) {
                                Object res = args[0];
                                if (res instanceof Uri) {
                                    Activity host = sActivity.get();
                                    if (host != null && !host.isFinishing()) importFont(host, (Uri) res);
                                }
                                return null;
                            }
                            if ("toString".equals(n)) return "CigramFontsCallback";
                            if ("hashCode".equals(n)) return System.identityHashCode(proxy);
                            if ("equals".equals(n)) return proxy == (args == null ? null : args[0]);
                            return null;
                        }
                    });

            java.lang.reflect.Method reg =
                    callerCls.getMethod("registerForActivityResult", contractCls, cbCls);
            sPicker = reg.invoke(activity, contract, callback);
        } catch (Throwable error) {
            android.util.Log.w("CigramFonts", "installPicker failed", error);
            sPicker = null;
        }
    }

    private static void launchPicker() {
        try {
            if (sPicker == null) {
                Activity a = sActivity.get();
                if (a != null) CigramSilent.toast(a, "تعذّر فتح منتقي الملفات على هذا الجهاز", android.widget.Toast.LENGTH_SHORT);
                return;
            }
            Class<?> launcherCls = Class.forName("androidx.activity.result.ActivityResultLauncher");
            java.lang.reflect.Method launch = launcherCls.getMethod("launch", Object.class);
            launch.invoke(sPicker, (Object) new String[]{
                    "font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf",
                    "application/octet-stream", "*/*"
            });
        } catch (Throwable error) {
            android.util.Log.w("CigramFonts", "launchPicker failed", error);
            Activity a = sActivity.get();
            if (a != null) CigramSilent.toast(a, "تعذّر فتح منتقي الملفات", android.widget.Toast.LENGTH_SHORT);
        }
    }

    // =====================================================================
    //  استيراد خط خاص إلى تخزين التطبيق
    // =====================================================================

    private static File subfontDir(Context c) {
        File dir = new File(c.getFilesDir(), SUBFONT_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static String displayName(Context c, Uri uri) {
        String name = null;
        try {
            android.database.Cursor cur = c.getContentResolver().query(uri, null, null, null, null);
            if (cur != null) {
                try {
                    int idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && cur.moveToFirst()) name = cur.getString(idx);
                } finally {
                    cur.close();
                }
            }
        } catch (Throwable ignored) { }
        if (name == null || name.trim().isEmpty()) name = "font_" + System.currentTimeMillis();
        return name.trim();
    }

    private static void importFont(final Activity activity, Uri uri) {
        try {
            String display = displayName(activity, uri);
            String ext = ".ttf";
            String lower = display.toLowerCase(java.util.Locale.US);
            if (lower.endsWith(".otf")) ext = ".otf";
            String base = display.replaceAll("\\.[A-Za-z0-9]+$", "").replaceAll("[^\\p{L}\\p{N}_-]+", "_");
            if (base.isEmpty()) base = "font";
            String fileName = base + "_" + Integer.toHexString((int) System.currentTimeMillis()) + ext;

            File out = new File(subfontDir(activity), fileName);
            InputStream in = activity.getContentResolver().openInputStream(uri);
            if (in == null) {
                CigramSilent.toast(activity, "تعذّر قراءة الملف", android.widget.Toast.LENGTH_SHORT);
                return;
            }
            OutputStream os = new FileOutputStream(out);
            try {
                byte[] buf = new byte[16384];
                int n;
                long total = 0L;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > 12L * 1024L * 1024L) throw new java.io.IOException("font too large");
                    os.write(buf, 0, n);
                }
                os.flush();
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
                try { os.close(); } catch (Throwable ignored) { }
            }

            // تحقّق أن الملف خط صالح فعلاً
            Typeface test = null;
            try { test = Typeface.createFromFile(out); } catch (Throwable ignored) { }
            if (test == null || test.equals(Typeface.DEFAULT)) {
                // قد تُرجع بعض الأجهزة DEFAULT عند الفشل؛ نتحقق بالحجم كحد أدنى
                if (out.length() < 1024) {
                    out.delete();
                    CigramSilent.toast(activity, "الملف ليس خطاً صالحاً (TTF/OTF)", android.widget.Toast.LENGTH_LONG);
                    return;
                }
            }

            registerCustom(activity, fileName, cleanName(display));
            prefs(activity).edit().putString(K_FONT, "custom:" + fileName).apply();
            uploadHook(activity, out, cleanName(display));
            CigramSilent.toast(activity, "تمت إضافة الخط", android.widget.Toast.LENGTH_SHORT);
            if (sRefresh != null) sRefresh.run();
        } catch (Throwable error) {
            android.util.Log.w("CigramFonts", "importFont failed", error);
            CigramSilent.toast(activity, "تعذّر استيراد الخط", android.widget.Toast.LENGTH_SHORT);
        }
    }

    private static String cleanName(String display) {
        String n = display.replaceAll("\\.[A-Za-z0-9]+$", "").trim();
        return n.isEmpty() ? "خط مخصص" : n;
    }

    private static JSONArray customArray(Context c) {
        try {
            String raw = prefs(c).getString(K_CUSTOM, "[]");
            return new JSONArray(raw == null ? "[]" : raw);
        } catch (Throwable ignored) {
            return new JSONArray();
        }
    }

    private static void registerCustom(Context c, String file, String name) {
        try {
            JSONArray arr = customArray(c);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && file.equals(o.optString("file"))) return;
            }
            JSONObject o = new JSONObject();
            o.put("file", file);
            o.put("name", name);
            arr.put(o);
            prefs(c).edit().putString(K_CUSTOM, arr.toString()).apply();
        } catch (Throwable ignored) { }
    }

    private static void removeCustom(Context c, String file) {
        try {
            JSONArray arr = customArray(c);
            JSONArray keep = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && !file.equals(o.optString("file"))) keep.put(o);
            }
            prefs(c).edit().putString(K_CUSTOM, keep.toString()).apply();
            File f = new File(subfontDir(c), file);
            if (f.exists()) f.delete();
            if (("custom:" + file).equals(prefs(c).getString(K_FONT, ""))) {
                prefs(c).edit().putString(K_FONT, DEFAULT_KEY).apply();
            }
        } catch (Throwable ignored) { }
    }

    /** نقطة ربط اختيارية لمزامنة الخط مع حساب المستخدم على السحابة (تتطلب API خاص بكم). */
    private static void uploadHook(Context c, File fontFile, String name) {
        // متروكة فارغة عمداً: عند توفّر نقطة رفع، تُستدعى CigramSync لرفع fontFile وربطه بالتوكن.
    }

    private static Typeface typefaceForKey(Context c, String key, String assetPath) {
        try {
            if (key != null && key.startsWith("custom:")) {
                File f = new File(subfontDir(c), key.substring("custom:".length()));
                if (f.isFile()) return Typeface.createFromFile(f);
            } else if (assetPath != null) {
                return Typeface.createFromAsset(c.getAssets(), assetPath);
            }
        } catch (Throwable ignored) { }
        return appFont(c);
    }

    // =====================================================================
    //  الصفحة الكاملة
    // =====================================================================

    /** يفتح صفحة «خطوطي» كصفحة كاملة فوق الشاشة الحالية. */
    public static void openPage(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) return;
            final ViewGroup root = (ViewGroup) content;
            if (root.findViewWithTag(PAGE_TAG) != null) return;

            final FrameLayout page = new FrameLayout(activity);
            page.setTag(PAGE_TAG);
            final CigramPalette pagePal = CigramPalette.of(activity);
            page.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF19191C, 0xFF131315, 0xFF0F0F11, 0xFF0B0B0C}));   // the app's dark page (the rows below are drawn for it)
            page.setClickable(true);
            page.setFocusableInTouchMode(true);
            page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            page.addView(column, new FrameLayout.LayoutParams(-1, -1));

            // شريط علوي: رجوع + عنوان
            LinearLayout bar = new LinearLayout(activity);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(dp(activity, 12), dp(activity, 40), dp(activity, 12), dp(activity, 12));

            final View backBox = new View(activity);          // no arrow on the account's sections: system Back closes
            TextView title = CigramUI.label(activity, "الخطوط", 28f, 0xFFFFFFFF, true);
            title.setTypeface(CigramTypefaces.bold() != null ? CigramTypefaces.bold() : appFont(activity), Typeface.BOLD);
            title.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
            LinearLayout titles = new LinearLayout(activity);
            titles.setOrientation(LinearLayout.VERTICAL);
            titles.addView(title, new LinearLayout.LayoutParams(-1, -2));
            TextView under = CigramUI.label(activity, "كل تغيير يُحفظ وحده ويظهر فوراً في المعاينة", 12.5f, 0x99FFFFFF, false);
            titles.addView(under, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
            titleLp.setMarginStart(dp(activity, 8));
            bar.addView(titles, titleLp);
            column.addView(bar, new LinearLayout.LayoutParams(-1, -2));

            ScrollView scroller = new ScrollView(activity);
            scroller.setVerticalScrollBarEnabled(false);
            scroller.setFillViewport(true);
            final LinearLayout body = new LinearLayout(activity);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            body.setPadding(dp(activity, 16), dp(activity, 6), dp(activity, 16), dp(activity, 40));
            scroller.addView(body, new FrameLayout.LayoutParams(-1, -2));
            column.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1f));

            // بطاقة المعاينة
            // LIVE preview: the viewer writes his own words (a name, a line) and they are saved as he types
            final android.widget.EditText preview = new android.widget.EditText(activity);
            preview.setText(prefs(activity).getString(K_PREVIEW, SAMPLE));
            preview.setHint("اكتب اسماً للمعاينة");
            preview.setHintTextColor(0x80FFFFFF);
            preview.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            preview.setMaxLines(2);
            preview.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            preview.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});
            preview.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence t, int a1, int b1, int c1) { }
                @Override public void onTextChanged(CharSequence t, int a1, int b1, int c1) { }
                @Override public void afterTextChanged(android.text.Editable e) {
                    prefs(activity).edit().putString(K_PREVIEW, e.toString()).apply();      // saved by itself
                }
            });
            preview.setTextSize(22);
            preview.setGravity(Gravity.CENTER);
            preview.setPadding(dp(activity, 16), dp(activity, 26), dp(activity, 16), dp(activity, 26));

            final Runnable[] rebuild = new Runnable[1];
            final Runnable updatePreview = new Runnable() {
                @Override public void run() {
                    SharedPreferences p = prefs(activity);
                    preview.setTypeface(subtitleTypeface(activity, appFont(activity)));
                    preview.setTextColor(subtitleTextColor(activity));
                    int bg = subtitleBackgroundColor(activity, p.getString(K_BG_MODE, "black"));
                    GradientDrawable d = new GradientDrawable();
                    d.setColor(bg == Color.TRANSPARENT ? 0x00000000 : bg);
                    d.setCornerRadius(dp(activity, 6));
                    // خلف المعاينة صورة شبيهة بالفيديو (رمادي) حتى تظهر الخلفية الشفافة
                    preview.setBackground(d);
                }
            };

            FrameLayout previewWrap = new FrameLayout(activity);
            GradientDrawable pv = new GradientDrawable();
            pv.setColors(new int[]{0xFF3A3A3F, 0xFF18181B, 0xFF050506});       // a dim film frame, no colour cast
            pv.setOrientation(GradientDrawable.Orientation.TL_BR);
            pv.setCornerRadius(dp(activity, 24));
            pv.setStroke(dp(activity, 1), 0x40FFFFFF);
            previewWrap.setBackground(pv);
            previewWrap.setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10));
            previewWrap.addView(preview, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
            LinearLayout.LayoutParams pwLp = new LinearLayout.LayoutParams(-1, -2);
            pwLp.bottomMargin = dp(activity, 18);
            body.addView(previewWrap, pwLp);

            // الأقسام تُبنى داخل rebuild حتى تتحدّث بعد الاستيراد
            final LinearLayout dynamic = new LinearLayout(activity);
            dynamic.setOrientation(LinearLayout.VERTICAL);
            dynamic.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            body.addView(dynamic, new LinearLayout.LayoutParams(-1, -2));

            rebuild[0] = new Runnable() {
                @Override public void run() {
                    dynamic.removeAllViews();
                    buildFontSection(activity, dynamic, updatePreview, rebuild[0]);
                    buildColorSection(activity, dynamic, "خلفية الترجمة", true, updatePreview);
                    buildColorSection(activity, dynamic, "لون النص", false, updatePreview);
                    updatePreview.run();
                }
            };
            sRefresh = rebuild[0];
            rebuild[0].run();

            root.addView(page, new FrameLayout.LayoutParams(-1, -1));

            // دخول الصفحة
            page.setTranslationX(root.getWidth() > 0 ? root.getWidth() : 1200);
            page.animate().translationX(0f).setDuration(260L)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();

            final Runnable close = new Runnable() {
                @Override public void run() {
                    sRefresh = null;
                    page.animate().translationX(page.getWidth()).setDuration(220L)
                            .setInterpolator(new DecelerateInterpolator())
                            .withEndAction(new Runnable() {
                                @Override public void run() {
                                    if (page.getParent() instanceof ViewGroup) {
                                        ((ViewGroup) page.getParent()).removeView(page);
                                    }
                                }
                            }).start();
                }
            };
            backBox.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { close.run(); }
            });
            page.requestFocus();
            page.setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                        close.run();
                        return true;
                    }
                    return false;
                }
            });
        } catch (Throwable error) {
            android.util.Log.w("CigramFonts", "openPage failed", error);
        }
    }

    private static TextView sectionTitle(Activity a, String text) {
        TextView t = CigramUI.label(a, text, 16f, 0xFFFFFFFF, true);
        t.setTypeface(appFont(a), Typeface.BOLD);
        t.setGravity(Gravity.RIGHT);
        t.setPadding(0, dp(a, 14), 0, dp(a, 10));
        return t;
    }

    private static void buildFontSection(final Activity a, LinearLayout parent,
                                         final Runnable updatePreview, final Runnable rebuild) {
        parent.addView(sectionTitle(a, "خط الترجمة"), new LinearLayout.LayoutParams(-1, -2));

        final SharedPreferences p = prefs(a);
        final String current = normalizeKey(p.getString(K_FONT, DEFAULT_KEY));

        for (int i = 0; i < BUILTIN.length; i++) {
            addFontRow(a, parent, BUILTIN[i][0], BUILTIN[i][1], BUILTIN[i][2], false,
                    current, updatePreview, rebuild);
        }

        JSONArray customs = customArray(a);
        for (int i = 0; i < customs.length(); i++) {
            JSONObject o = customs.optJSONObject(i);
            if (o == null) continue;
            String file = o.optString("file");
            String name = o.optString("name", "خط مخصص");
            if (file.isEmpty()) continue;
            addFontRow(a, parent, "custom:" + file, name, null, true, current, updatePreview, rebuild);
        }

        // زر إضافة خط
        TextView add = CigramUI.label(a, "＋  إضافة خط من الجهاز", 16f, 0xFFFFFFFF, true);
        add.setTypeface(appFont(a), Typeface.BOLD);
        add.setGravity(Gravity.CENTER);
        add.setPadding(0, dp(a, 14), 0, dp(a, 14));
        GradientDrawable ab = new GradientDrawable();
        ab.setColor(0xFF123240);
        ab.setCornerRadius(dp(a, 16));
        ab.setStroke(dp(a, 1), 0x3329A9E0);
        add.setBackground(ab);
        add.setClickable(true);
        add.setFocusable(true);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launchPicker(); }
        });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-1, -2);
        addLp.topMargin = dp(a, 8);
        parent.addView(add, addLp);
    }

    private static void addFontRow(final Activity a, LinearLayout parent, final String key,
                                   String name, String assetPath, boolean removable,
                                   String current, final Runnable updatePreview, final Runnable rebuild) {
        final boolean selected = key.equals(current);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0xFF1B536B : 0xFF262629);
        bg.setCornerRadius(dp(a, 14));
        if (selected) bg.setStroke(dp(a, 1), 0xFFFFFFFF);
        row.setBackground(bg);
        row.setClickable(true);
        row.setFocusable(true);

        TextView sample = new TextView(a);
        sample.setText(SAMPLE);
        sample.setTextSize(18);
        sample.setSingleLine(true);
        sample.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sample.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        sample.setTextColor(selected ? 0xFFFFFFFF : 0xFFD7E0E4);
        sample.setTypeface(typefaceForKey(a, key, assetPath));
        row.addView(sample, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView label = new TextView(a);
        label.setText(name);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(selected ? 0xFF9FE6FF : 0xFF7F95A0);
        label.setTypeface(appFont(a));
        label.setPadding(dp(a, 8), 0, dp(a, 8), 0);
        row.addView(label, new LinearLayout.LayoutParams(-2, -2));

        if (removable) {
            TextView del = new TextView(a);
            del.setText("حذف");
            del.setTextSize(12);
            del.setGravity(Gravity.CENTER);
            del.setTextColor(0xFFFF7A7A);
            del.setTypeface(appFont(a));
            del.setPadding(dp(a, 10), dp(a, 4), dp(a, 10), dp(a, 4));
            del.setClickable(true);
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    removeCustom(a, key.substring("custom:".length()));
                    if (rebuild != null) rebuild.run();
                }
            });
            row.addView(del, new LinearLayout.LayoutParams(-2, -2));
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs(a).edit().putString(K_FONT, key).apply();
                if (rebuild != null) rebuild.run();
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(a, 6);
        parent.addView(row, lp);
    }

    // لوحات الألوان
    private static final int[] BG_COLORS = {
            0xCC000000, 0xFF000000, 0x80000000, 0xCC1C1C1C, 0xCCFFFFFF, 0x66FFFFFF,
            0xCC0B2545, 0xCC2A1A4A, 0xCC3A0B16, 0xCC0A2F22, 0xCC3B2A12, 0xCC10343A, 0xCC3A1036, 0xCC1F2937
    };
    private static final String[] BG_NAMES = {"أسود", "أسود كامل", "ظل خفيف", "رمادي", "أبيض", "زجاجي",
            "كحلي", "بنفسجي", "نبيذي", "أخضر", "قهوة", "بترولي", "توتي", "فحمي"};

    private static final int[] TEXT_COLORS = {
            0xFFFFFFFF, 0xFFE5E5EA, 0xFF000000, 0xFFFFEB3B, 0xFFFFD60A, 0xFF4FE3FF, 0xFF7CFF9B,
            0xFFFF8AE0, 0xFFFFA94D, 0xFFFF6B6B, 0xFFB79CFF, 0xFF9BE7C4, 0xFFF5E6C8
    };
    private static final String[] TEXT_NAMES = {"أبيض", "فضي", "أسود", "أصفر", "ذهبي", "سماوي", "أخضر",
            "وردي", "برتقالي", "مرجاني", "بنفسجي", "نعناعي", "عاجي"};

    private static void buildColorSection(final Activity a, final LinearLayout parent, String heading,
                                          final boolean isBackground, final Runnable updatePreview) {
        parent.addView(sectionTitle(a, heading), new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout rowc = new LinearLayout(a);
        rowc.setOrientation(LinearLayout.HORIZONTAL);
        rowc.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        hs.addView(rowc, new FrameLayout.LayoutParams(-2, -2));

        final SharedPreferences p = prefs(a);

        if (isBackground) {
            // خيار «بدون خلفية»
            addSwatch(a, rowc, "بدون", Color.TRANSPARENT, isNoneSelected(p), new Runnable() {
                @Override public void run() {
                    p.edit().putString(K_BG_MODE, "none").apply();
                    updatePreview.run();
                    refreshSwatches(a, parent);
                }
            });
        }

        int[] colors = isBackground ? BG_COLORS : TEXT_COLORS;
        String[] names = isBackground ? BG_NAMES : TEXT_NAMES;
        for (int i = 0; i < colors.length; i++) {
            final int color = colors[i];
            boolean sel = isBackground
                    ? (!isNoneSelected(p) && currentBg(p) == color)
                    : (subtitleTextColor(a) == color);
            addSwatch(a, rowc, names[i], color, sel, new Runnable() {
                @Override public void run() {
                    if (isBackground) {
                        p.edit().putString(K_BG_MODE, "color").putInt(K_BG_COLOR, color).apply();
                    } else {
                        p.edit().putInt(K_TEXT_COLOR, color).apply();
                    }
                    updatePreview.run();
                    refreshSwatches(a, parent);
                }
            });
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        parent.addView(hs, lp);
    }

    private static boolean isNoneSelected(SharedPreferences p) {
        return "none".equals(p.getString(K_BG_MODE, "black"));
    }

    private static int currentBg(SharedPreferences p) {
        String mode = p.getString(K_BG_MODE, "black");
        if ("color".equals(mode)) return p.getInt(K_BG_COLOR, Color.BLACK);
        return Color.BLACK;
    }

    /** إعادة بناء بسيطة لمجموعات الألوان لتحديث حالة الاختيار. */
    private static void refreshSwatches(Activity a, LinearLayout parent) {
        // الأبسط والأكثر أماناً: نترك المعاينة تعكس التغيير، والاختيار يظهر بعد إعادة فتح القسم.
        // لإظهار الإطار فوراً نعيد بناء القسم الديناميكي بالكامل عبر sRefresh.
        if (sRefresh != null) sRefresh.run();
    }

    private static void addSwatch(Activity a, LinearLayout parent, String name, int color,
                                  boolean selected, final Runnable onPick) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(dp(a, 6), dp(a, 4), dp(a, 6), dp(a, 4));
        col.setClickable(true);
        col.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onPick.run(); }
        });

        View dot = new View(a);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (color == Color.TRANSPARENT) {
            g.setColor(0x00000000);
            g.setStroke(dp(a, selected ? 3 : 1), selected ? 0xFFFFFFFF : 0x55FFFFFF);
        } else {
            g.setColor((color & 0x00FFFFFF) | 0xFF000000);
            g.setStroke(dp(a, selected ? 3 : 1), selected ? 0xFFFFFFFF : 0x33FFFFFF);
        }
        dot.setBackground(g);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(a, 38), dp(a, 38));
        col.addView(dot, dotLp);

        TextView t = new TextView(a);
        t.setText(name);
        t.setTextSize(11);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(selected ? 0xFFFFFFFF : 0xFF8E8E94);
        t.setTypeface(appFont(a));
        t.setPadding(0, dp(a, 4), 0, 0);
        col.addView(t, new LinearLayout.LayoutParams(-2, -2));

        parent.addView(col, new LinearLayout.LayoutParams(-2, -2));
    }
}
