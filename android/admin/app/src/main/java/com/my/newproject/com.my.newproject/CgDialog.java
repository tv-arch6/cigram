package com.my.newproject;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

/**
 * Every dialog of the admin (confirm, input, choose, errors) in the same look: a rounded dark card, white bold title,
 * muted message, the main button in red and the other one quiet. It is an AlertDialog.Builder, so every existing
 * call (setTitle / setMessage / setPositiveButton ... show) keeps working unchanged.
 */
final class CgDialog {

    private CgDialog() {}

    static final class Builder extends AlertDialog.Builder {
        private final Context ctx;

        Builder(Activity a) {
            super(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
            ctx = a;
        }

        @Override
        public AlertDialog create() {
            final AlertDialog d = super.create();
            d.setOnShowListener(new DialogInterface.OnShowListener() {
                @Override
                public void onShow(DialogInterface di) {
                    try {
                        style(ctx, d);
                    } catch (Throwable ignored) { }
                    try {
                        if (d.getWindow() != null) CgUi.applyTree(d.getWindow().getDecorView());
                    } catch (Throwable ignored) { }
                }
            });
            return d;
        }
    }

    private static void style(Context c, AlertDialog d) {
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.InsetDrawable(CgUi.bg(c, CgCfg.CARD, 22, CgCfg.STROKE), CgUi.dp(c, 8), 0, CgUi.dp(c, 8), 0));
            d.getWindow().setDimAmount(0.62f);
        }
        int titleId = c.getResources().getIdentifier("alertTitle", "id", "android");
        View t = titleId != 0 ? d.findViewById(titleId) : null;
        if (t instanceof TextView) {
            TextView tv = (TextView) t;
            tv.setTextColor(CgCfg.TEXT);
            CgUi.applyFont(tv, true);
            tv.setTextSize(18);
            tv.setTextDirection(View.TEXT_DIRECTION_RTL);
            tv.setGravity(android.view.Gravity.START);
        }
        View m = d.findViewById(android.R.id.message);
        if (m instanceof TextView) {
            TextView mv = (TextView) m;
            mv.setTextColor(CgCfg.MUTED);
            CgUi.applyFont(mv, false);
            mv.setTextSize(14);
            mv.setLineSpacing(0f, 1.25f);
            mv.setTextDirection(View.TEXT_DIRECTION_RTL);
        }
        Button pos = d.getButton(AlertDialog.BUTTON_POSITIVE);
        Button neg = d.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button neu = d.getButton(AlertDialog.BUTTON_NEUTRAL);
        boolean dangerText = pos != null && pos.getCurrentTextColor() == CgCfg.BAD;
        if (pos != null) {
            restyle(c, pos, true, dangerText);
        }
        if (neg != null) restyle(c, neg, false, false);
        if (neu != null) restyle(c, neu, false, false);
    }

    private static void restyle(Context c, Button b, boolean primary, boolean danger) {
        CgUi.applyFont(b, true);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMinHeight(CgUi.dp(c, 44));
        b.setMinWidth(CgUi.dp(c, 84));
        if (primary) {
            int fill = danger ? CgCfg.BAD : CgCfg.ACCENT_D;
            b.setTextColor(Color.WHITE);
            b.setBackground(CgUi.bg(c, fill, 12, 0));
        } else {
            b.setTextColor(CgCfg.TEXT);
            b.setBackground(CgUi.bg(c, CgCfg.CARD2, 12, CgCfg.STROKE));
        }
        ViewGroup.LayoutParams lp = b.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) lp).setMargins(CgUi.dp(c, 4), CgUi.dp(c, 4), CgUi.dp(c, 4), CgUi.dp(c, 8));
            b.setLayoutParams(lp);
        }
    }
}
