package com.Cigram.vid;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/** 19.2 screens on the real Android view system (Robolectric, native graphics). Pictures: build/ui-shots. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NewScreensUiTest {

    @Test
    public void theWatchCalendarOpensAndDrawsAMonth() throws Exception {
        ActivityController<CigramWatchCalendarActivity> ac = Robolectric.buildActivity(CigramWatchCalendarActivity.class).setup();
        for (int k = 0; k < 30; k++) { Thread.sleep(40); ShadowLooper.idleMainLooper(); }
        View root = ac.get().findViewById(android.R.id.content);
        Bitmap shot = ChatKitUiTest.shoot(root, 720, 1520, "watch_calendar");
        assertTrue(ChatKitUiTest.inked(shot, 0xFF000000) > 0.5);
        ac.pause().stop().destroy();
    }

    @Test
    public void theProfileAvatarsAreDrawn() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        android.widget.LinearLayout row = new android.widget.LinearLayout(a);
        row.setBackgroundColor(0xFF111114);
        String[] letters = {"أ", "س", "+"};
        int[] colors = {CigramProfiles.COLORS[0], CigramProfiles.COLORS[1], 0xFF2A2A30};
        for (int i = 0; i < 3; i++) {
            CigramProfiles.Avatar av = new CigramProfiles.Avatar(a, letters[i], colors[i], i == 0);
            row.addView(av, new android.widget.LinearLayout.LayoutParams(160, 160));
        }
        Bitmap shot = ChatKitUiTest.shoot(row, 520, 160, "profile_avatars");
        assertTrue(ChatKitUiTest.inked(shot, 0xFF000000) > 0.3);
    }

    @Test
    public void theCinemaSpotlightIsBuiltForTheTvHome() throws Exception {
        ActivityController<CigramTvActivity> ac = Robolectric.buildActivity(CigramTvActivity.class);
        CigramTvActivity tv;
        try {
            tv = ac.setup().get();
        } catch (Throwable t) {
            tv = null;      // the TV host needs a catalogue / network to finish its own start; the spotlight is built below anyway
        }
        if (tv == null) tv = ac.get();
        List<JSONObject> items = new ArrayList<JSONObject>();
        for (int i = 0; i < 4; i++) {
            JSONObject o = new JSONObject();
            o.put("id", "t" + i).put("type", i % 2 == 0 ? "movie" : "series").put("title", i % 2 == 0 ? "Ghost Soldier" : "كابتن تسوباسا")
                    .put("year", "2026").put("section_name", "أكشن").put("backdrop", "")
                    .put("description", "بعد سنوات من العيش بعيداً عن الأنظار، يضطر جندي سابق للعودة والدفاع عن مدينته.");
            items.add(o);
        }
        View spot = CigramTvSpotlight.build(tv, items);
        Bitmap shot = ChatKitUiTest.shoot(spot, 1280, 0, "tv_cinema_spotlight");
        assertTrue(shot.getHeight() > 300);
        assertTrue(ChatKitUiTest.inked(shot, 0xFF000000) > 0.5);
    }
}
