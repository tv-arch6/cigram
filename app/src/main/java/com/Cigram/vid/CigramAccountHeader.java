package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Account page (v5.29) — "cinematic header" for a logged-in user.
 *
 *   ┌────────────────────────────────────────────┐
 *   │  the user's cover (landscape image or muted │
 *   │  video), else the last watched title blurred │
 *   │  light fade into the page colour (#00121A)  │
 *   │                                name     (●) │
 *   └────────────────────────────────────────────┘
 *
 * It replaces the two old glass cards (avatar card + info card), which are hidden, not removed:
 * the Logic blocks still fill them, and this header reads the name and avatar from them, so they
 * always match what the Logic shows. Gender and e-mail are not shown here (the e-mail is in
 * Settings → account). Safe to call on every resume.
 */
public final class CigramAccountHeader {

    static final String TAG_HEADER = "cigram_account_header";
    static final String TAG_SECTION = "cigram_account_section_";
    /** Original top padding of linear_account_content (given back on the guest page). */
    private static final java.util.WeakHashMap<View, Integer> HOST_PAD_TOP =
            new java.util.WeakHashMap<View, Integer>();

    /** The decoded cover of the last visit: the header paints it on its first frame. */
    private static final Object COVER_LOCK = new Object();
    private static String coverKey = "";
    private static android.graphics.Bitmap coverBitmap;

    private static final int PAGE = 0xFF00121A;
    private static final int SECTION_TEXT = 0xD9FFFFFF;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CigramAccountHeader() { }

    /** The page colour of the current mode with the given alpha. */
    private static int fadeEdge(Context c, int alpha) {
        return (alpha << 24) | (CigramTheme.page(c) & 0xFFFFFF);
    }

    private static View byId(Activity a, String name) {
        int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
        return id == 0 ? null : a.findViewById(id);
    }

    private static boolean alive(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    // ------------------------------------------------------------------ header

    /** Builds or refreshes the header; removes it when nobody is logged in. */
    public static void install(final Activity a) {
        if (!alive(a)) return;
        if (CigramTvMode.isTv(a)) {
            installTv(a);   // v10.66: TV only — the profile moves to a panel beside the menu
            return;
        }
        View profileView = byId(a, "linear_profile");
        if (!(profileView instanceof LinearLayout)) return;
        LinearLayout profile = (LinearLayout) profileView;

        View existing = profile.findViewWithTag(TAG_HEADER);
        boolean logged = CigramUserData.isLoggedIn(a);
        fitToTop(a, logged);
        if (!logged) {
            if (existing != null) profile.removeView(existing);
            return;
        }

        setGone(byId(a, "linear_profile_avatar_row"));
        setGone(byId(a, "linear_profile_info"));

        Header header;
        if (existing instanceof Header) {
            header = (Header) existing;
        } else {
            if (existing != null) profile.removeView(existing);
            header = new Header(a);
            header.setTag(TAG_HEADER);
            profile.addView(header, 0, new LinearLayout.LayoutParams(-1, CigramUI.dp(a, 250)));
        }
        header.bindProfile(a);
        header.bindMedia(a);
    }

    // ------------------------------------------------------------------ v10.66 TV panel

    static final String TAG_HEADER_TV = "cigram_account_header_tv";
    static final String TAG_GUEST_TV = "cigram_account_guest_tv";

    /**
     * TV only: the side panel (between the rail and the menu) shows the profile — the cover, a big
     * avatar, the name, the e-mail and the watched count — and the page keeps only the menu. For a
     * guest the panel welcomes them, the sign-in stays on the page.
     */
    private static void installTv(final Activity a) {
        boolean logged = CigramUserData.isLoggedIn(a);
        fitToTop(a, false);
        View profileView = byId(a, "linear_profile");
        if (profileView instanceof LinearLayout) {
            View phoneHeader = ((LinearLayout) profileView).findViewWithTag(TAG_HEADER);
            if (phoneHeader != null) ((LinearLayout) profileView).removeView(phoneHeader);
        }
        FrameLayout panel = CigramTvRail.panel(a, true);
        if (panel == null) return;   // the rail is not there yet: the next call builds it
        View current = panel.getChildCount() > 0 ? panel.getChildAt(0) : null;
        if (logged) {
            setGone(byId(a, "linear_profile_avatar_row"));
            setGone(byId(a, "linear_profile_info"));
            Header header;
            if (current instanceof Header) {
                header = (Header) current;
            } else {
                panel.removeAllViews();
                header = new Header(a, true);
                header.setTag(TAG_HEADER_TV);
                panel.addView(header, new FrameLayout.LayoutParams(-1, -1));
            }
            header.bindProfile(a);
            header.bindMedia(a);
        } else if (current == null || !TAG_GUEST_TV.equals(current.getTag())) {
            panel.removeAllViews();
            panel.addView(guestPanel(a), new FrameLayout.LayoutParams(-1, -1));
        }
    }

    private static View guestPanel(Activity a) {
        FrameLayout box = new FrameLayout(a);
        box.setTag(TAG_GUEST_TV);
        box.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF4A1418, 0xFF0B2936, PAGE}));
        View glow = new View(a);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.5f, 0.38f);
        g.setGradientRadius(CigramUI.dp(a, 170));
        g.setColors(new int[]{0x66E21B14, 0x1AE21B14, 0x00E21B14});
        glow.setBackground(g);
        box.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = CigramUI.dp(a, 22);
        col.setPadding(side, 0, side, 0);
        ImageView logo = new ImageView(a);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int res = a.getResources().getIdentifier("hoo", "drawable", a.getPackageName());
        if (res != 0) logo.setImageResource(res);
        col.addView(logo, new LinearLayout.LayoutParams(CigramUI.dp(a, 86), CigramUI.dp(a, 86)));
        TextView title = CigramUI.label(a, "أهلاً بك في سيكرام", 22f, 0xFFFFFFFF, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = CigramUI.dp(a, 18);
        col.addView(title, tl);
        TextView hint = CigramUI.label(a,
                "سجّل دخولك لتحفظ قائمتك وتكمل المشاهدة من حيث توقفت، على كل أجهزتك.", 13f, 0xB3FFFFFF, false);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(CigramUI.dp(a, 3), 1f);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = CigramUI.dp(a, 8);
        col.addView(hint, hl);
        box.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        return box;
    }

    /**
     * The layout gives linear_account_content a 35dp top padding (made for the old cards). The
     * cover must start right under the status bar, so that padding is dropped while the header is
     * shown and given back for the guest page.
     */
    private static void fitToTop(Activity a, boolean headerShown) {
        View host = byId(a, "linear_account_content");
        if (host == null) return;
        Integer saved = HOST_PAD_TOP.get(host);
        if (saved == null) {
            saved = Integer.valueOf(host.getPaddingTop());
            HOST_PAD_TOP.put(host, saved);
        }
        int top = headerShown ? 0 : saved.intValue();
        if (host.getPaddingTop() != top) {
            host.setPadding(host.getPaddingLeft(), top, host.getPaddingRight(), host.getPaddingBottom());
        }
    }

    private static void setGone(View v) {
        if (v != null) v.setVisibility(View.GONE);
    }

    private static String textOf(Activity a, String id) {
        View v = byId(a, id);
        if (!(v instanceof TextView)) return "";
        CharSequence t = ((TextView) v).getText();
        return t == null ? "" : t.toString().trim();
    }

    static final class Header extends FrameLayout {
        private final ImageView backdrop;
        private final VideoLayer video;
        private final ImageView avatar;
        private FrameLayout avatarRing;
        private android.graphics.drawable.Drawable plainRingBg;
        private int plainRingPad;
        private final TextView name;
        /** v10.66: TV panel only (null on a phone). */
        private final TextView email;
        private final TextView stats;
        private final boolean tv;
        /** What the media layer shows now: "banner:<version>" or "history:<url>". */
        private String mediaKey = "";
        /** Phone header: the public id chip under the name (null on the TV panel). */
        private View idChip;

        Header(final Activity a) {
            this(a, false);
        }

        /** tv = the tall panel of the TV account page (v10.66); false = the phone header. */
        Header(final Activity a, boolean tv) {
            super(a);
            this.tv = tv;
            setBackgroundColor(CigramTheme.page(a));
            setClipChildren(true);

            backdrop = new ImageView(a);
            backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
            backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(backdrop, new LayoutParams(-1, -1));

            video = new VideoLayer(a);
            video.setVisibility(View.GONE);
            addView(video, new LayoutParams(-1, -1));

            if (tv) {
                // ---- v10.66 TV panel: the cover fills the panel, the profile sits in its middle
                View shade = new View(a);
                shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{fadeEdge(a, 0x80), fadeEdge(a, 0xA6), fadeEdge(a, 0xD9), CigramTheme.page(a)}));
                addView(shade, new LayoutParams(-1, -1));
                View glow = new View(a);
                GradientDrawable g = new GradientDrawable();
                g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
                g.setGradientCenter(0.5f, 0.40f);
                g.setGradientRadius(CigramUI.dp(a, 190));
                g.setColors(new int[]{0x59E21B14, 0x1FE21B14, 0x00E21B14});
                glow.setBackground(g);
                addView(glow, new LayoutParams(-1, -1));

                TextView cover = CigramUI.label(a, "الغلاف", 12f, 0xFFFFFFFF, false);
                cover.setGravity(Gravity.CENTER);
                cover.setPadding(CigramUI.dp(a, 12), 0, CigramUI.dp(a, 12), 0);
                GradientDrawable coverBg = new GradientDrawable();
                coverBg.setColor(0x73000000);
                coverBg.setCornerRadius(CigramUI.dp(a, 15));
                coverBg.setStroke(Math.max(1, CigramUI.dp(a, 1)), 0x4DFFFFFF);
                cover.setBackground(coverBg);
                cover.setClickable(true);
                cover.setFocusable(true);
                cover.setContentDescription("تغيير غلاف الحساب");
                cover.setOnClickListener(new OnClickListener() {
                    @Override public void onClick(View v) { onCoverClicked(a); }
                });
                LayoutParams cl = new LayoutParams(-2, CigramUI.dp(a, 30), Gravity.TOP | Gravity.RIGHT);
                cl.setMargins(0, CigramUI.dp(a, 16), CigramUI.dp(a, 16), 0);
                addView(cover, cl);

                LinearLayout col = new LinearLayout(a);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER_HORIZONTAL);
                int side = CigramUI.dp(a, 18);
                col.setPadding(side, 0, side, 0);

                // avatar: white ring, soft red halo around it
                FrameLayout halo = new FrameLayout(a);
                GradientDrawable haloBg = new GradientDrawable();
                haloBg.setShape(GradientDrawable.OVAL);
                haloBg.setColor(0x00000000);
                haloBg.setStroke(Math.max(1, CigramUI.dp(a, 6)), 0x40E21B14);
                halo.setBackground(haloBg);
                int haloPad = CigramUI.dp(a, 7);
                halo.setPadding(haloPad, haloPad, haloPad, haloPad);
                FrameLayout ring = new FrameLayout(a);
                GradientDrawable ringBg = new GradientDrawable();
                ringBg.setShape(GradientDrawable.OVAL);
                ringBg.setColor(0xFFFFFFFF);
                ring.setBackground(ringBg);
                int border = CigramUI.dp(a, 3);
                ring.setPadding(border, border, border, border);
                avatarRing = ring; plainRingBg = ringBg; plainRingPad = border;
                avatar = new ImageView(a);
                avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
                avatar.setBackgroundColor(0xFF0B2936);
                avatar.setOutlineProvider(new ViewOutlineProvider() {
                    @Override public void getOutline(View view, Outline outline) {
                        outline.setOval(0, 0, view.getWidth(), view.getHeight());
                    }
                });
                avatar.setClipToOutline(true);
                avatar.setContentDescription("الصورة الشخصية");
                ring.addView(avatar, new FrameLayout.LayoutParams(-1, -1));
                halo.addView(ring, new FrameLayout.LayoutParams(-1, -1));
                int size = CigramUI.dp(a, 104) + 2 * haloPad;
                col.addView(halo, new LinearLayout.LayoutParams(size, size));

                float shadow = CigramUI.dp(a, 6);
                name = CigramUI.label(a, "", 23f, 0xFFFFFFFF, true);
                name.setGravity(Gravity.CENTER);
                name.setMaxLines(2);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setShadowLayer(shadow, 0, CigramUI.dp(a, 1), 0x99000000);
                LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
                nl.topMargin = CigramUI.dp(a, 14);
                col.addView(name, nl);

                email = CigramUI.label(a, "", 13f, 0xB3FFFFFF, false);
                email.setGravity(Gravity.CENTER);
                email.setSingleLine(true);
                email.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                email.setTextDirection(View.TEXT_DIRECTION_LTR);
                LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-1, -2);
                el.topMargin = CigramUI.dp(a, 4);
                col.addView(email, el);

                stats = CigramUI.label(a, "", 12.5f, 0xE6FFFFFF, true);
                stats.setGravity(Gravity.CENTER);
                stats.setPadding(CigramUI.dp(a, 14), CigramUI.dp(a, 6), CigramUI.dp(a, 14), CigramUI.dp(a, 6));
                GradientDrawable chip = new GradientDrawable();
                chip.setColor(0x33FFFFFF);
                chip.setCornerRadius(CigramUI.dp(a, 16));
                chip.setStroke(Math.max(1, CigramUI.dp(a, 1)), 0x33FFFFFF);
                stats.setBackground(chip);
                stats.setVisibility(View.GONE);
                LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
                sl.topMargin = CigramUI.dp(a, 16);
                col.addView(stats, sl);

                addView(col, new LayoutParams(-1, -2, Gravity.CENTER));
                return;
            }
            email = null;
            stats = null;

            // light fade at the bottom only: the cover stays clear, the text sits on the page colour
            View fade = new View(a);
            fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{fadeEdge(a, 0x00), fadeEdge(a, 0x00), fadeEdge(a, 0x59), fadeEdge(a, 0xD9), CigramTheme.page(a)}));
            addView(fade, new LayoutParams(-1, -1));

            TextView cover = CigramUI.label(a, "الغلاف", 12f, 0xFFFFFFFF, false);
            cover.setGravity(Gravity.CENTER);
            cover.setPadding(CigramUI.dp(a, 12), 0, CigramUI.dp(a, 12), 0);
            GradientDrawable coverBg = new GradientDrawable();
            coverBg.setColor(0x73000000);
            coverBg.setCornerRadius(CigramUI.dp(a, 15));
            coverBg.setStroke(Math.max(1, CigramUI.dp(a, 1)), 0x4DFFFFFF);
            cover.setBackground(coverBg);
            cover.setClickable(true);
            cover.setFocusable(true);
            cover.setContentDescription("تغيير غلاف الحساب");
            cover.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { onCoverClicked(a); }
            });
            LayoutParams cl = new LayoutParams(-2, CigramUI.dp(a, 30), Gravity.TOP | Gravity.LEFT);
            cl.setMargins(CigramUI.dp(a, 16), CigramUI.dp(a, 14), 0, 0);
            addView(cover, cl);

            // bottom row, physical order (LTR): [texts] [avatar] → avatar on the right
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int side = CigramUI.dp(a, 20);
            row.setPadding(side, 0, side, CigramUI.dp(a, 16));

            LinearLayout texts = new LinearLayout(a);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            float shadow = CigramUI.dp(a, 6);
            name = CigramUI.label(a, "", 22f, 0xFFFFFFFF, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setShadowLayer(shadow, 0, CigramUI.dp(a, 1), 0x99000000);
            texts.addView(name, new LinearLayout.LayoutParams(-1, -2));
            try {
                idChip = CigramPublicId.chip(a);               // "C48213" + copy icon, right under the name
                LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-2, -2);
                il.topMargin = CigramUI.dp(a, 6);
                texts.addView(idChip, il);
            } catch (Throwable t) {
                android.util.Log.w("CigramAccountHeader", "id chip failed", t);
                idChip = null;
            }
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
            tl.rightMargin = CigramUI.dp(a, 14);
            row.addView(texts, tl);

            // avatar: white 3dp ring around a round picture
            FrameLayout ring = new FrameLayout(a);
            GradientDrawable ringBg = new GradientDrawable();
            ringBg.setShape(GradientDrawable.OVAL);
            ringBg.setColor(0xFFFFFFFF);
            ring.setBackground(ringBg);
            int border = CigramUI.dp(a, 3);
            ring.setPadding(border, border, border, border);
                avatarRing = ring; plainRingBg = ringBg; plainRingPad = border;
            ring.setElevation(CigramUI.dp(a, 8));   // v10.67: the photo floats above the cover (real shadow)
            avatar = new ImageView(a);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setBackgroundColor(0xFF0B2936);
            avatar.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            avatar.setClipToOutline(true);
            avatar.setContentDescription("الصورة الشخصية");
            ring.addView(avatar, new FrameLayout.LayoutParams(-1, -1));
            int size = CigramUI.dp(a, 78);
            row.addView(ring, new LinearLayout.LayoutParams(size, size));

            addView(row, new LayoutParams(-1, -2, Gravity.BOTTOM));
        }

        void bindProfile(final Activity a) {
            String n = textOf(a, "textview_profile_username_row");
            name.setText(n.length() == 0 ? "المستخدم" : n);
            if (idChip != null) {
                final View chipRef = idChip;
                CigramPublicId.paintChip(chipRef);
                CigramPublicId.ensure(a, new Runnable() {
                    @Override public void run() { if (alive(a)) CigramPublicId.paintChip(chipRef); }
                });
            }
            if (tv) bindTvExtras(a);
            applyFrame(a);
            copyAvatar(a);
            // the Logic loads the picture asynchronously: copy it again once it has arrived
            MAIN.postDelayed(new Runnable() { @Override public void run() { copyAvatar(a); } }, 600L);
            MAIN.postDelayed(new Runnable() { @Override public void run() { copyAvatar(a); } }, 1800L);
        }

        /** Verification ring while Plus is active; the plain white ring otherwise. */
        private void applyFrame(Activity a) {
            try {
                if (avatarRing == null) return;
                boolean on = CigramAvatarFrame.selfFramed(a);
                // no badge on the profile photo itself: only beside the name (and on the bottom-bar avatar)
                CigramAvatarFrame.decorate(a, avatarRing, false, 0);
                // the same badge right after the name
                android.graphics.drawable.Drawable seal = on ? CigramAvatarFrame.badgeDrawable(a, CigramUI.dp(a, 18)) : null;
                name.setCompoundDrawablesRelative(null, null, seal, null);
                name.setCompoundDrawablePadding(CigramUI.dp(a, 5));
                CigramPlus.refresh(a, false, null);
            } catch (Throwable ignored) { }
        }

        /** v10.66 (TV panel): the e-mail and how many titles were watched. */
        private void bindTvExtras(final Activity a) {
            if (email == null || stats == null) return;
            String mail = "";
            try { mail = CigramUserData.email(a); } catch (Throwable ignored) { }
            email.setText(mail == null ? "" : mail.trim());
            email.setVisibility(mail == null || mail.trim().length() == 0 ? View.GONE : View.VISIBLE);
            final Context app = a.getApplicationContext();
            new Thread(new Runnable() {
                @Override public void run() {
                    int watched = 0;
                    try { watched = CigramUserData.historyGrouped(app).size(); } catch (Throwable ignored) { }
                    final int count = watched;
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            if (!alive(a)) return;
                            if (count <= 0) {
                                stats.setVisibility(View.GONE);
                            } else {
                                stats.setText(count == 1 ? "شاهدت عنواناً واحداً"
                                        : count == 2 ? "شاهدت عنوانين" : "شاهدت " + count + " عنوان");
                                stats.setVisibility(View.VISIBLE);
                            }
                        }
                    });
                }
            }, "cigram-tv-account-stats").start();
        }

        private void copyAvatar(Activity a) {
            if (!alive(a)) return;
            if (CigramAvatar.applyTo(avatar, true, false)) return;   // v10.67: the saved photo, first frame, no flicker
            View src = byId(a, "circleimageview_profile_avatar");
            if (!(src instanceof ImageView)) return;
            Drawable d = ((ImageView) src).getDrawable();
            if (d == null) return;
            // a Drawable has one owner: give the header its own instance of the same picture
            Drawable.ConstantState state = d.getConstantState();
            avatar.setImageDrawable(state != null ? state.newDrawable(getResources()) : d);
        }

        /** The user's own cover when there is one, otherwise the last watched title (blurred). */
        void bindMedia(final Activity a) {
            final CigramBanner.Banner banner = CigramBanner.current(a);
            if (banner != null) {
                String key = "banner:" + banner.version;
                if (key.equals(mediaKey)) return;
                mediaKey = key;
                clearBlur();
                backdrop.setAlpha(1f);
                if (banner.isVideo()) {
                    backdrop.setImageDrawable(null);
                    video.setVisibility(View.VISIBLE);
                    video.play(banner.file);
                } else {
                    video.stop();
                    video.setVisibility(View.GONE);
                    android.graphics.Bitmap kept;
                    synchronized (COVER_LOCK) { kept = key.equals(coverKey) ? coverBitmap : null; }
                    if (kept != null && !kept.isRecycled()) backdrop.setImageBitmap(kept);   // first frame, no flash
                    else loadCoverImage(a, banner.file, key);
                }
                return;
            }
            video.stop();
            video.setVisibility(View.GONE);
            loadHistoryBackdrop(a);
        }

        private void clearBlur() {
            if (Build.VERSION.SDK_INT >= 31) backdrop.setRenderEffect(null);
        }

        private void applyBlur(Activity a) {
            if (Build.VERSION.SDK_INT >= 31) {
                backdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        CigramUI.dp(a, 18), CigramUI.dp(a, 18), android.graphics.Shader.TileMode.CLAMP));
                backdrop.setAlpha(0.85f);
            } else {
                // no blur before Android 12: keep the picture quiet so the text stays readable
                backdrop.setAlpha(0.45f);
            }
        }

        private void loadCoverImage(final Activity a, final java.io.File file, final String key) {
            final int targetWidth = Math.max(1, a.getResources().getDisplayMetrics().widthPixels);
            new Thread(new Runnable() {
                @Override public void run() {
                    android.graphics.Bitmap bmp = null;
                    try {
                        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), o);
                        int sample = 1;
                        while (o.outWidth / (sample * 2) >= targetWidth) sample *= 2;
                        android.graphics.BitmapFactory.Options d = new android.graphics.BitmapFactory.Options();
                        d.inSampleSize = sample;
                        bmp = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), d);
                    } catch (Throwable ignored) { }
                    final android.graphics.Bitmap result = bmp;
                    if (result != null) {
                        synchronized (COVER_LOCK) { coverKey = key; coverBitmap = result; }
                    }
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            if (!alive(a) || !key.equals(mediaKey)) return;
                            if (result != null) backdrop.setImageBitmap(result);
                        }
                    });
                }
            }, "cigram-cover-image").start();
        }

        /** Backdrop of the most recently watched title (account history), read off the UI thread. */
        private void loadHistoryBackdrop(final Activity a) {
            final Context app = a.getApplicationContext();
            new Thread(new Runnable() {
                @Override public void run() {
                    String url = "";
                    try {
                        ArrayList<JSONObject> rows = CigramUserData.historyGrouped(app);
                        for (int i = 0; i < rows.size() && url.length() == 0; i++) {
                            JSONObject e = rows.get(i);
                            url = e.optString("backdrop", "").trim();
                            if (url.length() == 0) url = e.optString("poster", "").trim();
                        }
                    } catch (Throwable ignored) { }
                    final String found = url;
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            String key = "history:" + found;
                            if (!alive(a) || key.equals(mediaKey)) return;
                            if (CigramBanner.current(a) != null) return; // a cover was set meanwhile
                            mediaKey = key;
                            applyBlur(a);
                            if (found.length() == 0) {
                                // nothing watched yet: a calm brand gradient instead of a picture
                                backdrop.setImageDrawable(new GradientDrawable(
                                        GradientDrawable.Orientation.TL_BR,
                                        new int[]{0xFF5A1A1A, 0xFF0B2936}));
                            } else {
                                CigramStage1InitProvider.cigramLoadPoster(a, found, backdrop);
                            }
                        }
                    });
                }
            }, "cigram-account-header").start();
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            // the video only plays while the account page is on screen
            if (visibility == View.VISIBLE) video.resume();
            else video.stop();
        }

        @Override protected void onDetachedFromWindow() {
            video.stop();
            super.onDetachedFromWindow();
        }
    }

    /** «الغلاف»: pick a cover, or change / remove the current one. */
    private static void onCoverClicked(final Activity a) {
        final boolean hasCover = CigramBanner.current(a) != null;
        final boolean hasLast = CigramBanner.hasLast(a);
        if (!hasCover && !hasLast) {
            pickCover(a);
            return;
        }
        final android.app.Dialog dialog = new android.app.Dialog(a);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        box.setPadding(0, CigramUI.dp(a, 8), 0, CigramUI.dp(a, 8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0B2936);
        bg.setCornerRadius(CigramUI.dp(a, 22));
        box.setBackground(bg);
        box.addView(dialogRow(a, hasCover ? "تغيير الغلاف" : "وضع غلاف جديد", 0xFFFFFFFF, new Runnable() {
            @Override public void run() { dialog.dismiss(); pickCover(a); }
        }));
        if (hasLast) {
            // v10.67: the last cover of the account is saved: one tap brings it back
            box.addView(dialogRow(a, "استعادة آخر غلاف", 0xFF7CE0A8, new Runnable() {
                @Override public void run() {
                    dialog.dismiss();
                    CigramBanner.restoreLast(a);
                    install(a);
                }
            }));
        }
        if (hasCover) {
            box.addView(dialogRow(a, "إزالة الغلاف", 0xFFFF5252, new Runnable() {
                @Override public void run() {
                    dialog.dismiss();
                    CigramBanner.remove(a);
                    install(a);
                }
            }));
        }
        box.addView(dialogRow(a, "إلغاء", 0xB3FFFFFF, new Runnable() {
            @Override public void run() { dialog.dismiss(); }
        }));
        dialog.setContentView(box);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            dialog.getWindow().setLayout(Math.round(a.getResources().getDisplayMetrics().widthPixels * 0.82f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        CigramTvFocus.attachDialog(dialog);   // v10.66: TV remote (TV only)
    }

    private static TextView dialogRow(Context c, String text, int color, final Runnable action) {
        TextView t = CigramUI.label(c, text, 16f, color, false);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setPadding(CigramUI.dp(c, 22), 0, CigramUI.dp(c, 22), 0);
        t.setMinHeight(CigramUI.dp(c, 52));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return t;
    }

    private static void pickCover(Activity a) {
        try {
            a.startActivity(new Intent(a, CigramBannerActivity.class));
        } catch (Throwable error) {
            android.util.Log.w("CigramAccountHeader", "open cover picker failed", error);
        }
    }

    /**
     * Muted, looping, centre-cropped playback of the cover video (TextureView + MediaPlayer).
     * Plays only while attached and visible; every stop releases the player completely.
     */
    static final class VideoLayer extends android.view.TextureView
            implements android.view.TextureView.SurfaceTextureListener {
        private java.io.File file;
        private android.media.MediaPlayer player;
        private android.view.Surface surface;
        private boolean wanted;

        VideoLayer(Context c) {
            super(c);
            setSurfaceTextureListener(this);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void play(java.io.File f) {
            stop();
            file = f;
            wanted = true;
            start();
        }

        void resume() {
            if (file == null || player != null) return;
            wanted = true;
            start();
        }

        void stop() {
            wanted = false;
            if (player != null) {
                try { player.release(); } catch (Throwable ignored) { }
                player = null;
            }
            if (surface != null) {
                surface.release();
                surface = null;
            }
        }

        private void start() {
            if (!wanted || file == null || player != null || !isAvailable()
                    || getVisibility() != View.VISIBLE) return;
            try {
                surface = new android.view.Surface(getSurfaceTexture());
                final android.media.MediaPlayer mp = new android.media.MediaPlayer();
                player = mp;
                mp.setDataSource(file.getAbsolutePath());
                mp.setSurface(surface);
                mp.setLooping(true);
                mp.setVolume(0f, 0f);
                mp.setOnVideoSizeChangedListener(new android.media.MediaPlayer.OnVideoSizeChangedListener() {
                    @Override public void onVideoSizeChanged(android.media.MediaPlayer p, int w, int h) {
                        crop(w, h);
                    }
                });
                mp.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
                    @Override public void onPrepared(android.media.MediaPlayer p) {
                        if (player == p && wanted) p.start();
                    }
                });
                mp.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
                    @Override public boolean onError(android.media.MediaPlayer p, int what, int extra) {
                        android.util.Log.w("CigramAccountHeader", "cover video error " + what + "/" + extra);
                        stop();
                        return true;
                    }
                });
                mp.prepareAsync();
            } catch (Throwable error) {
                android.util.Log.w("CigramAccountHeader", "cover video start failed", error);
                stop();
            }
        }

        /** Centre-crop: fill the whole cover, cutting the overflow evenly on both sides. */
        private void crop(int videoW, int videoH) {
            int vw = getWidth();
            int vh = getHeight();
            if (videoW <= 0 || videoH <= 0 || vw <= 0 || vh <= 0) return;
            float scale = Math.max(vw / (float) videoW, vh / (float) videoH);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(videoW * scale / vw, videoH * scale / vh, vw / 2f, vh / 2f);
            setTransform(m);
        }

        @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
            start();
        }

        @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {
            if (player != null) {
                try { crop(player.getVideoWidth(), player.getVideoHeight()); } catch (Throwable ignored) { }
            }
        }

        @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
            boolean keep = wanted;
            stop();
            wanted = keep; // comes back when the surface does
            return true;
        }

        @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) { }
    }

    // ------------------------------------------------------------------ section titles

    /** Section title in the app style: bold 14sp, red accent mark on the right. */
    static TextView sectionTitle(Context c, String text, String key) {
        TextView t = CigramUI.label(c, text, 14f, CigramTheme.isLight(c) ? 0xD9111418 : SECTION_TEXT, true);
        t.setTag(TAG_SECTION + key);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setPadding(CigramUI.dp(c, 20), CigramUI.dp(c, 18), CigramUI.dp(c, 20), CigramUI.dp(c, 8));
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(CigramUI.RED);
        bar.setCornerRadius(CigramUI.dp(c, 2));
        bar.setBounds(0, 0, CigramUI.dp(c, 3), CigramUI.dp(c, 13));
        t.setCompoundDrawables(null, null, bar, null);
        t.setCompoundDrawablePadding(CigramUI.dp(c, 8));
        t.setTypeface(CigramUI.font(c), Typeface.BOLD);
        return t;
    }

    /**
     * Puts the title tagged key directly above anchor (same parent), creating it once.
     * The title follows the anchor's visibility, so an empty group never shows a title.
     */
    static void titleAbove(Context c, View anchor, String text, String key) {
        if (anchor == null || !(anchor.getParent() instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) anchor.getParent();
        View root = parent.getRootView();
        View title = root == null ? null : root.findViewWithTag(TAG_SECTION + key);
        if (title == null) {
            title = sectionTitle(c, text, key);
        } else if (title.getParent() instanceof ViewGroup) {
            ((ViewGroup) title.getParent()).removeView(title);
        }
        parent.addView(title, parent.indexOfChild(anchor),
                new LinearLayout.LayoutParams(-1, -2));
        title.setVisibility(anchor.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
    }
}
