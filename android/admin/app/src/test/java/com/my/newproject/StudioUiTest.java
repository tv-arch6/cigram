package com.my.newproject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.LinearLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Studio 3.0 on the REAL Android view system (Robolectric, native graphics): the new components and the screens that
 * do not need the network are created, laid out and drawn; pictures go to build/ui-shots.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StudioUiTest {

    static Bitmap shoot(View v, int w, int h, String name) throws Exception {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), h > 0
                ? View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY) : View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(Math.max(1, v.getMeasuredWidth()), Math.max(1, v.getMeasuredHeight()), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(StudioUi.INK);
        v.draw(c);
        File dir = new File("build/ui-shots");
        dir.mkdirs();
        FileOutputStream os = new FileOutputStream(new File(dir, name + ".png"));
        b.compress(Bitmap.CompressFormat.PNG, 100, os);
        os.close();
        return b;
    }

    static double inked(Bitmap b, int background) {
        int n = 0, total = 0;
        for (int y = 0; y < b.getHeight(); y += 3) for (int x = 0; x < b.getWidth(); x += 3) { total++; if (b.getPixel(x, y) != background) n++; }
        return n / (double) total;
    }

    @Test
    public void theStudioComponentsAreBuiltAndDrawn() throws Exception {
        final Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout page = CgUi.vbox(a);
        int p = CgUi.dp(a, 14);
        page.setPadding(p, p, p, p);
        Runnable nothing = new Runnable() { @Override public void run() { } };
        page.addView(StudioUi.pair(a,
                StudioUi.deck(a, CgIcons.SEND, "إضافة سريعة", "الصق رابطاً وانشر بخطوة واحدة", StudioUi.RED, nothing),
                StudioUi.deck(a, CgIcons.UPLOAD, "معالج الرفع الجماعي", "موسم كامل أو عدة أفلام دفعة واحدة", StudioUi.ORANGE, nothing)));
        page.addView(StudioUi.pair(a,
                StudioUi.deck(a, CgIcons.FILE, "مختبر الترجمة", "مزامنة · إصلاح · ترميز · تنظيف · معاينة", StudioUi.SKY, nothing),
                StudioUi.deck(a, CgIcons.CHAT, "صندوق المطوّر", "محادثات الدعم الخاص والمعلنين", StudioUi.VIOLET, nothing)));
        page.addView(StudioUi.line(a, StudioUi.stat(a, "أفلام", "151", CgCfg.TEXT), StudioUi.stat(a, "مسلسلات", "65", CgCfg.TEXT), StudioUi.stat(a, "حلقات", "5,469", StudioUi.GOLD)));
        page.addView(StudioUi.label(a, "الترجمة"));
        LinearLayout g = StudioUi.group(a);
        StudioUi.row(g, CgIcons.FILE, "مختبر الترجمة", "Auto Sync · تأخير · انحراف · إصلاح SRT · ترميز", "جديد", StudioUi.SKY, nothing);
        StudioUi.row(g, CgIcons.SHIELD, "مركز الترجمة العربية", "العناوين بلا ترجمة: بحث جماعي، مراجعة، اعتماد ونشر", "", StudioUi.MINT, nothing);
        StudioUi.row(g, CgIcons.DRAFT, "المسودات", "ما بدأت به ولم تكمله", "3", StudioUi.GOLD, nothing);
        page.addView(g);
        assertEquals("three rows and two hairlines", 5, g.getChildCount());
        final int[] tab = {-1};
        StudioUi.Dock dock = new StudioUi.Dock(a, new String[]{"الاستوديو", "المحتوى", "البث", "التواصل", "النظام"},
                new int[]{CgIcons.BOX, CgIcons.FILM, CgIcons.SIGNAL, CgIcons.CHAT, CgIcons.GEAR}, new StudioUi.OnTab() {
            @Override public void onTab(int index) { tab[0] = index; }
        });
        dock.select(0);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2);
        dl.topMargin = CgUi.dp(a, 16);
        page.addView(dock.view, dl);
        CgUi.applyTree(page);
        Bitmap shot = shoot(page, 720, 0, "studio_components");
        assertTrue(shot.getHeight() > 900);
        assertTrue(inked(shot, StudioUi.INK) > 0.30);
        // the first workspace is the right-most item of the dock; tapping the left-most one selects «النظام»
        dock.view.getChildAt(0).performClick();
        assertEquals(4, tab[0]);
        assertEquals(4, dock.selected());
    }

    @Test
    public void theBulkWizardOpensWithoutANetwork() throws Exception {
        ActivityController<BulkWizardActivity> wiz = Robolectric.buildActivity(BulkWizardActivity.class).setup();
        ShadowLooper.idleMainLooper();
        shoot(wiz.get().findViewById(android.R.id.content), 720, 1300, "bulk_wizard");
        wiz.pause().stop().destroy();
    }

    @Test
    public void theTokenSurvivesSaveAndLoadWithOrWithoutAKeyStore() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        CgCfg.saveToken(a, "  secret-token-123  ");
        assertEquals("secret-token-123", CgCfg.TOKEN);
        CgCfg.TOKEN = "";
        CgCfg.loadToken(a);
        assertEquals("secret-token-123", CgCfg.TOKEN);
        // exactly one copy is stored: sealed where the platform has a key store, plain otherwise — never both
        android.content.SharedPreferences p = a.getSharedPreferences(CgCfg.PREFS, 0);
        boolean sealed = p.getString("admin_token_sealed", "").length() > 0, plain = p.getString("admin_token", "").length() > 0;
        assertTrue(sealed ^ plain);
        if (sealed) assertTrue(!p.getString("admin_token_sealed", "").contains("secret-token-123"));
        // an older install: a plain token is still read (and sealed when possible)
        p.edit().clear().putString("admin_token", "old-plain").commit();
        CgCfg.loadToken(a);
        assertEquals("old-plain", CgCfg.TOKEN);
        CgCfg.saveToken(a, "");
        CgCfg.loadToken(a);
        assertEquals("", CgCfg.TOKEN);
        assertEquals(null, CgVault.open("v1:not-base64:zz"));
        assertEquals(null, CgVault.open("plain"));
    }
}
