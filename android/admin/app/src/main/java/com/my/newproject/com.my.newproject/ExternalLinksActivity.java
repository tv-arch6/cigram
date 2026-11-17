package com.my.newproject;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * "إدارة الروابط الخارجية": the card at the bottom of the user app's account page.
 *   - icon of the app (a link, or a picture from the phone that is uploaded when you save)
 *   - app download link  /  channel link (used when there is no download link)
 *   - a switch: the card shows in the app ONLY when it is on AND a valid https link exists; clearing the link or switching off hides it.
 * Stored in the Plus worker (GET /app/external-link is what the app reads).
 */
public class ExternalLinksActivity extends CgBase {

    private static final int REQ_ICON = 4711;

    private CgUi.Toggle toggle;
    private EditText iconField;
    private EditText appField;
    private EditText channelField;
    private TextView status;
    private ImageView iconPreview;
    private Bitmap pickedIcon;
    private TextView saveBtn;

    @Override
    protected String screenTitle() {
        return "إدارة الروابط الخارجية";
    }

    @Override
    protected void onBuild() {
        LinearLayout c = scrollBody();
        c.addView(CgUi.muted(this, "عنصر يظهر آخر صفحة الحساب في تطبيق المستخدم: رابط تحميل التطبيق، أو رابط قناة تليكرام إذا لا يوجد رابط تحميل."),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 4, 0, 4, 12));

        LinearLayout sw = DxUi.card(this);
        toggle = CgUi.toggle(this, "إظهار العنصر في صفحة الحساب", false);
        toggle.onChange(new Runnable() {
            @Override public void run() { refreshStatus(); }
        });
        sw.addView(toggle);
        status = CgUi.muted(this, "");
        sw.addView(status, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));
        c.addView(sw, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));

        // icon
        LinearLayout ic = DxUi.card(this);
        ic.addView(CgUi.text(this, "أيقونة التطبيق", 15, CgCfg.TEXT, true));
        LinearLayout irow = CgUi.hbox(this);
        irow.setGravity(Gravity.CENTER_VERTICAL);
        iconPreview = new ImageView(this);
        iconPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iconPreview.setBackground(CgUi.bg(this, CgCfg.CARD2, 14, 0));
        irow.addView(iconPreview, new LinearLayout.LayoutParams(CgUi.dp(this, 64), CgUi.dp(this, 64)));
        LinearLayout col = CgUi.vbox(this);
        iconField = CgUi.urlField(this, "https://... رابط صورة الأيقونة");
        col.addView(iconField);
        TextView pick = CgUi.button(this, "اختيار صورة من المعرض", 1);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { CxMedia.pickImage(ExternalLinksActivity.this, REQ_ICON); }
        });
        col.addView(pick, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        cl.setMarginStart(CgUi.dp(this, 12));
        irow.addView(col, cl);
        ic.addView(irow, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        ic.addView(CgUi.muted(this, "الصورة المختارة تُرفع عند الحفظ وتحلّ محل الرابط."), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));
        c.addView(ic, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));

        // links
        LinearLayout links = DxUi.card(this);
        appField = CgUi.urlField(this, "https://... رابط تحميل التطبيق");
        links.addView(CgUi.labeled(this, "رابط تحميل التطبيق", appField));
        channelField = CgUi.urlField(this, "https://t.me/... رابط القناة");
        links.addView(CgUi.labeled(this, "رابط القناة (في حال لا يوجد رابط تحميل)", channelField));
        c.addView(links, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));

        TextWatcher w = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c2) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c2) { refreshStatus(); }
            @Override public void afterTextChanged(Editable s) { }
        };
        appField.addTextChangedListener(w);
        channelField.addTextChangedListener(w);

        saveBtn = CgUi.button(this, "حفظ", 0);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        c.addView(saveBtn, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
        bar.action("تحديث", new Runnable() {
            @Override public void run() { load(); }
        });
        load();
    }

    // ------------------------------------------------------------------ rules (same as the worker)

    /** Same rule as the worker and the app: https with a real host. */
    static boolean validHttps(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.length() < 12 || t.length() > 500 || !t.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) return false;
        try {
            String host = Uri.parse(t).getHost();
            return host != null && host.contains(".");
        } catch (Exception e) {
            return false;
        }
    }

    /** "app" / "channel" / "" : which link the user app would use right now. */
    static String effectiveKind(String app, String channel) {
        if (validHttps(app)) return "app";
        if (validHttps(channel)) return "channel";
        return "";
    }

    private void refreshStatus() {
        if (status == null) return;
        String kind = effectiveKind(appField.getText().toString(), channelField.getText().toString());
        boolean on = toggle.on;
        if (!on) { status.setText("متوقف: العنصر لا يظهر في التطبيق."); status.setTextColor(CgCfg.MUTED); }
        else if (kind.length() == 0) { status.setText("لن يظهر: لا يوجد رابط صالح (يجب أن يبدأ بـ https://)."); status.setTextColor(CgCfg.WARN); }
        else if ("app".equals(kind)) { status.setText("يظهر في التطبيق: رابط تحميل التطبيق."); status.setTextColor(CgCfg.GOOD); }
        else { status.setText("يظهر في التطبيق: رابط القناة (لا يوجد رابط تحميل)."); status.setTextColor(CgCfg.GOOD); }
    }

    // ------------------------------------------------------------------ load / save

    private void load() {
        showLoading("جارٍ التحميل...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override public CgHttp.Res run() throws Exception {
                CgHttp.Res r = PlusApi.call(ExternalLinksActivity.this, "GET", "/admin/app/external-link", null);
                if (!r.ok()) throw new Exception(PlusApi.explain(r));
                return r;
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res r) {
                hideLoading();
                JSONObject j = r.json == null ? new JSONObject() : r.json;
                toggle.silent(j.optBoolean("enabled", false));
                iconField.setText(j.optString("icon", ""));
                appField.setText(j.optString("app_url", ""));
                channelField.setText(j.optString("channel_url", ""));
                pickedIcon = null;
                iconPreview.setImageDrawable(null);
                refreshStatus();
            }

            @Override public void fail(String message) {
                hideLoading();
                CgUi.info(ExternalLinksActivity.this, "تعذر التحميل", message);
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_ICON || res != RESULT_OK || data == null || data.getData() == null) return;
        Bitmap b = CxMedia.decode(this, data.getData(), 512);
        if (b == null) { CgUi.toast(this, "تعذر قراءة الصورة"); return; }
        pickedIcon = b;
        iconPreview.setImageBitmap(b);
    }

    private void save() {
        final String app = appField.getText().toString().trim();
        final String channel = channelField.getText().toString().trim();
        final String iconTyped = iconField.getText().toString().trim();
        if (app.length() > 0 && !validHttps(app)) { CgUi.toast(this, "رابط التحميل يجب أن يبدأ بـ https://"); return; }
        if (channel.length() > 0 && !validHttps(channel)) { CgUi.toast(this, "رابط القناة يجب أن يبدأ بـ https://"); return; }
        if (iconTyped.length() > 0 && !validHttps(iconTyped)) { CgUi.toast(this, "رابط الأيقونة يجب أن يبدأ بـ https://"); return; }
        if (toggle.on && effectiveKind(app, channel).length() == 0) { CgUi.toast(this, "أضف رابط تحميل أو رابط قناة قبل التفعيل"); return; }
        final boolean enabled = toggle.on;
        final Bitmap pick = pickedIcon;
        showLoading(pick != null ? "جارٍ رفع الأيقونة..." : "جارٍ الحفظ...");
        CgHttp.async(this, new CgHttp.Work<JSONObject>() {
            @Override public JSONObject run() throws Exception {
                String icon = iconTyped;
                if (pick != null) {                                               // same upload the carousel uses
                    byte[] data = CxMedia.jpegFlat(pick, 0xFFFFFFFF, 90);
                    CgHttp.Res up = CgHttp.postBytes(CgCfg.API + "/upload-carousel-image", data, "image/jpeg", true, 90000);
                    if (up.error != null) throw new Exception(CgHttp.describe(up.error));
                    if (!up.ok() || up.json == null || !validHttps(up.json.optString("url"))) throw new Exception("تعذر رفع الأيقونة: " + up.message());
                    icon = up.json.optString("url");
                }
                JSONObject b = new JSONObject();
                b.put("enabled", enabled);
                b.put("icon", icon);
                b.put("app_url", app);
                b.put("channel_url", channel);
                CgHttp.Res r = PlusApi.call(ExternalLinksActivity.this, "POST", "/admin/app/external-link", b);
                if (!r.ok()) throw new Exception(PlusApi.explain(r));
                return r.json == null ? new JSONObject() : r.json;
            }
        }, new CgHttp.Done<JSONObject>() {
            @Override public void ok(JSONObject j) {
                hideLoading();
                pickedIcon = null;
                iconField.setText(j.optString("icon", ""));
                refreshStatus();
                CgUi.toast(ExternalLinksActivity.this, "تم الحفظ. يتحدث في التطبيق خلال دقيقة تقريباً.");
            }

            @Override public void fail(String message) {
                hideLoading();
                CgUi.info(ExternalLinksActivity.this, "تعذر الحفظ", message);
            }
        });
    }
}
