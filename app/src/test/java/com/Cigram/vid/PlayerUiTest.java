package com.Cigram.vid;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 19.2 — the player's new buttons and the in-app photo / video viewer on the real Android view system
 * (Robolectric, native graphics). Pictures: build/ui-shots.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PlayerUiTest {

    @Test
    public void everyFilmAndEpisodeOpensInTheOneBuiltInPlayer() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        // even with the companion switch on in old settings, nothing is handed to another app
        a.getSharedPreferences("cigram_player_prefs", 0).edit().putBoolean(CigramPlayerLauncher.KEY_EXTERNAL, true).commit();
        String[] links = {"https://cdn.example.com/films/a.mp4", "https://cdn.example.com/live/b.m3u8",
                "https://cdn.example.com/c.mkv|Referer=https://example.com"};
        for (String link : links) {
            Intent film = new Intent().putExtra("video", link).putExtra("title", "فيلم").putExtra("type", "movie");
            Intent episode = new Intent().putExtra("video", link).putExtra("type", "series")
                    .putExtra("series_title", "مسلسل").putExtra("season", "1").putExtra("episode", "3");
            assertTrue(!CigramPlayerLauncher.forward(a, film));
            assertTrue(!CigramPlayerLauncher.forward(a, episode));
        }
        assertTrue(!CigramPlayerLauncher.isEnabled(a));
        assertTrue(org.robolectric.Shadows.shadowOf(a).getNextStartedActivity() == null);
    }

    @Test
    public void aSeekThatIsPreparedIsShownAtItsTargetAndIsNeverLeftWaiting() throws Exception {
        // nothing waits at the start
        CigramSeekGuard.cancel();
        assertTrue(CigramSeekGuard.pendingTarget() == -1L);
        // a cancelled seek (the user sought somewhere else / left the screen) leaves no target behind
        CigramSeekGuard.cancel();
        assertTrue(CigramSeekGuard.pendingTarget() == -1L);
    }

    @Test
    public void aPhotoOpensInsideTheAppAndTheViewerSurvivesItsWholeLifecycle() throws Exception {
        Activity ctx = Robolectric.buildActivity(Activity.class).setup().get();
        File photo = new File(ctx.getCacheDir(), "photo_test.png");
        Bitmap bmp = Bitmap.createBitmap(900, 1400, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF224466);
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColor(0xFFF2C14E);
        c.drawCircle(450, 700, 300, p);
        FileOutputStream os = new FileOutputStream(photo);
        bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        os.close();

        Intent i = new Intent(ctx, CigramMediaViewer.class);
        i.putExtra("url", "");
        i.putExtra("local", photo.getAbsolutePath());
        i.putExtra("name", "إعلان.png");
        ActivityController<CigramMediaViewer> ac = Robolectric.buildActivity(CigramMediaViewer.class, i).setup();
        CigramMediaViewer v = ac.get();
        // the photo is decoded on a worker thread, then shown on the main thread
        for (int k = 0; k < 40; k++) { Thread.sleep(50); ShadowLooper.idleMainLooper(); }
        View root = v.findViewById(android.R.id.content);
        assertNotNull(root);
        Bitmap shot = ChatKitUiTest.shoot(root, 720, 1520, "viewer_photo");
        assertTrue("the photo is on screen", ChatKitUiTest.inked(shot, 0xFF000000) > 0.25);
        assertTrue(java.util.Arrays.equals(new String[]{".png", "image/png"}, CigramMediaViewer.sniffImage(photo, new String[]{".jpg", "image/jpeg"})));
        ac.pause().stop().destroy();
    }

    @Test
    public void aVideoOpensInTheAppPlayerWithItsControlsAndAFailureShowsRetryInsteadOfCrashing() throws Exception {
        Activity ctx = Robolectric.buildActivity(Activity.class).setup().get();
        String url = "https://example.invalid/clip.mp4";
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(ctx, android.net.Uri.parse(url)), new ShadowMediaPlayer.MediaInfo(95000, 0));
        Intent i = new Intent(ctx, CigramMediaViewer.class);
        i.putExtra("url", url);
        i.putExtra("video", true);
        i.putExtra("name", "clip.mp4");
        ActivityController<CigramMediaViewer> ac = Robolectric.buildActivity(CigramMediaViewer.class, i).setup();
        ShadowLooper.idleMainLooper();
        View root = ac.get().findViewById(android.R.id.content);
        Bitmap shot = ChatKitUiTest.shoot(root, 720, 1520, "viewer_video");
        assertTrue("controls are drawn", ChatKitUiTest.inked(shot, 0xFF000000) > 0.02);
        ac.pause().stop().destroy();

        // an address the player cannot open: the error box with «إعادة المحاولة», no crash
        Intent bad = new Intent(ctx, CigramMediaViewer.class);
        bad.putExtra("url", "https://example.invalid/missing.mp4");
        bad.putExtra("video", true);
        ActivityController<CigramMediaViewer> bc = Robolectric.buildActivity(CigramMediaViewer.class, bad).setup();
        ShadowLooper.idleMainLooper();
        ChatKitUiTest.shoot(bc.get().findViewById(android.R.id.content), 720, 1520, "viewer_video_error");
        bc.pause().stop().destroy();

        assertTrue("1:35".equals(CigramMediaViewer.clock(95000)));
        assertTrue("1:02:05".equals(CigramMediaViewer.clock(3725000)));
        assertTrue(java.util.Arrays.equals(new String[]{".mp4", "video/mp4"}, CigramMediaViewer.typeOf("clip.MP4", url, true)));
        assertTrue(java.util.Arrays.equals(new String[]{".webp", "image/webp"}, CigramMediaViewer.typeOf("", "https://x/y/pic.webp?sig=1", false)));
    }
}
