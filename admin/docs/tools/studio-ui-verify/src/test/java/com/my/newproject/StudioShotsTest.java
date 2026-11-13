package com.my.newproject;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import org.json.JSONObject;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StudioShotsTest {

    static Bitmap shoot(View v, int w, int h, String name) throws Exception {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), h > 0
                ? View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY) : View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(Math.max(1, v.getMeasuredWidth()), Math.max(1, v.getMeasuredHeight()), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(StudioUi.INK);
        v.draw(c);
        File dir = new File("shots");
        dir.mkdirs();
        FileOutputStream os = new FileOutputStream(new File(dir, name + ".png"));
        b.compress(Bitmap.CompressFormat.PNG, 100, os);
        os.close();
        System.out.println("SHOT " + name + " " + b.getWidth() + "x" + b.getHeight());
        return b;
    }

    private static void fakeCatalogue() throws Exception {
        CgData d = new CgData();
        String[] rows = {
            "{\"id\":\"m1\",\"type\":\"movie\",\"title\":\"الرجل الأخير\",\"video\":\"https://x.test/m1.mp4\",\"subtitle\":\"https://x.test/m1.srt\",\"year\":\"2024\"}",
            "{\"id\":\"m2\",\"type\":\"movie\",\"title\":\"ليلة في المدينة\",\"video\":\"https://x.test/m2.mp4\",\"year\":\"2023\"}",
            "{\"id\":\"m3\",\"type\":\"movie\",\"title\":\"Little Lorraine\",\"video\":\"https://x.test/m3.mp4\",\"year\":\"2022\"}",
            "{\"id\":\"series_a\",\"type\":\"series\",\"series_id\":\"series_a\",\"title\":\"مسلسل الليل الطويل\"}",
            "{\"id\":\"a_s1e1\",\"type\":\"episode\",\"series_id\":\"series_a\",\"season\":\"1\",\"episode\":\"1\",\"title\":\"الحلقة 1\",\"video\":\"https://x.test/a1.mp4\"}",
            "{\"id\":\"a_s1e2\",\"type\":\"episode\",\"series_id\":\"series_a\",\"season\":\"1\",\"episode\":\"2\",\"title\":\"الحلقة 2\",\"video\":\"https://x.test/a2.mp4\"}",
        };
        for (String r : rows) d.items.add(new JSONObject(r));
        Method m = CgData.class.getDeclaredMethod("reindex");
        m.setAccessible(true);
        m.invoke(d);
        Field f = CgData.class.getDeclaredField("cur");
        f.setAccessible(true);
        f.set(null, d);
    }

    private static View column(CgBase a) {
        ViewGroup sv = (ViewGroup) a.body.getChildAt(0);
        return sv.getChildAt(0);
    }

    @Test
    public void home() throws Exception {
        fakeCatalogue();
        ActivityController<DxHomeActivity> c = Robolectric.buildActivity(DxHomeActivity.class).create().start().visible();
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
        shoot(column(c.get()), 720, 0, "home");
    }

    @Test
    public void rooms() throws Exception {
        fakeCatalogue();
        for (int i = 0; i < StudioMap.ROOMS.length; i++) {
            Intent in = new Intent(org.robolectric.RuntimeEnvironment.getApplication(), StudioRoomActivity.class).putExtra(StudioRoomActivity.EXTRA_ROOM, i);
            ActivityController<StudioRoomActivity> c = Robolectric.buildActivity(StudioRoomActivity.class, in).create();
            ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
            shoot(column(c.get()), 720, 0, "room_" + i);
        }
    }

    @Test
    public void search() throws Exception {
        fakeCatalogue();
        ActivityController<StudioSearchActivity> c = Robolectric.buildActivity(StudioSearchActivity.class).create();
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
        shoot(c.get().body, 720, 1400, "search_empty");
        EditText f = null;
        ViewGroup body = c.get().body;
        for (int i = 0; i < body.getChildCount(); i++) if (body.getChildAt(i) instanceof EditText) f = (EditText) body.getChildAt(i);
        f.setText("ترجمة");
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS);
        shoot(c.get().body, 720, 1400, "search_tarjama");
        f.setText("ليلة");
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS);
        shoot(c.get().body, 720, 1400, "search_title");
    }

    @Test
    public void fields() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        android.widget.LinearLayout col = CgUi.vbox(a);
        col.setPadding(30, 30, 30, 30);
        col.setBackgroundColor(StudioUi.INK);
        EditText plain = CgUi.field(a, "الحقل الأصلي بالتلميح", false);
        col.addView(plain, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        EditText plainText = CgUi.field(a, "x", false);
        plainText.setText("الحقل الأصلي بنص");
        col.addView(plainText, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        EditText mine = CgUi.field(a, "نسختي بالتلميح", false);
        mine.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
        mine.setTextDirection(View.TEXT_DIRECTION_RTL);
        col.addView(mine, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        EditText mineText = CgUi.field(a, "x", false);
        mineText.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
        mineText.setTextDirection(View.TEXT_DIRECTION_RTL);
        mineText.setText("نسختي بنص");
        col.addView(mineText, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        shoot(col, 720, 0, "fields");
    }
}
