package com.Cigram.vid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * مجموعة أيقونات موحدة بنمط Outline (خط 2dp بأطراف دائرية) مرسومة بالكود، فلا حاجة لأي صور أو Drawables.
 * الاستخدام: new AdsIcons.Icon(context, AdsIcons.MEGAPHONE, color)
 */
public final class AdsIcons {

    private AdsIcons() { }

    public static final int MEGAPHONE = 1, CHAT = 2, WHATSAPP = 3, X_LOGO = 4, CHECK_CIRCLE = 5, CROSS_CIRCLE = 6,
            CHEVRON_LEFT = 7, CHEVRON_DOWN = 8, PLUS = 9, MINUS = 10, CLOSE = 11, CHECK = 12, USERS = 13, EYE = 14,
            CLICK = 15, CLOCK = 16, PIN = 17, GLOBE = 18, SHIELD = 19, FILE = 20, CALC = 21, STAR = 22, SEND = 23,
            LOCK = 24, GEAR = 25, FONT = 26, FOLDER = 27, HELP = 28, DOWNLOAD = 29, REFRESH = 30, HEADSET = 31,
            INFO = 32, BELL = 33, TRASH = 34, COPY = 35, REPLY = 36, CLIP = 37, MIC = 38, IMAGE = 39, VIDEO = 40,
            CAMERA = 41, PLAY = 42, PAUSE = 43, DOUBLE_CHECK = 44, ALERT = 45, USER = 46, LOGOUT = 47, RECEIPT = 48,
            TAG = 49, BAN = 50, ARCHIVE = 51, NOTE = 52, FILTER = 53, SEARCH = 54, MORE = 55, BACK = 56, EDIT = 57,
            CALENDAR = 58, CHART = 59, WALLET = 60, STOP = 61, STAR_FILL = 62, BOLT = 63, LINK = 64, PHONE = 65,
            EXPORT = 66, ASSIGN = 67, FLAG = 68, SPARK = 69, SUN = 70, EYE_OFF = 71,
            // 2026 Cigram / Cigram Plus collection (same 24-unit grid, 1.9 stroke, round caps as every icon above)
            SWITCH = 72, BOOKMARK = 73, CROWN = 74, CIGRAM = 75, TV = 76, CAST = 77, QUALITY = 78, SUBTITLES = 79,
            AUDIO = 80, SPEED = 81, APPS = 82, REPLAY10 = 83, FORWARD10 = 84, SKIP_NEXT = 85, PALETTE = 86,
            DOC = 87, SKIP_PREV = 88,
            // 18.2 Private Support
            SMILE = 89,
            // 19.2: the real Telegram paper plane (filled, no disc) for «تابعنا على Telegram»
            TELEGRAM = 90;

    /**
     * The Telegram logo's paper plane, exactly as in the official mark (path of the 24-unit brand icon), enlarged to
     * fill the icon box. Parsed once; null when the path parser is not available (then a plain plane is drawn).
     */
    private static Path TELEGRAM_PLANE;
    private static boolean TELEGRAM_TRIED;

    private static Path telegramPlane() {
        if (TELEGRAM_TRIED) return TELEGRAM_PLANE;
        TELEGRAM_TRIED = true;
        try {
            Path p = androidx.core.graphics.PathParser.createPathFromPathData(
                    "M16.906 7.224c.1-.002.321.023.465.14a.506.506 0 0 1 .171.325c.016.093.036.306.02.472"
                            + "-.18 1.898-.962 6.502-1.36 8.627-.168.9-.499 1.201-.82 1.23-.696.065-1.225-.46-1.9-.902"
                            + "-1.056-.693-1.653-1.124-2.678-1.8-1.185-.78-.417-1.21.258-1.91.177-.184 3.247-2.977 3.307-3.23"
                            + ".007-.032.014-.15-.056-.212s-.174-.041-.249-.024c-.106.024-1.793 1.14-5.061 3.345"
                            + "-.48.33-.913.49-1.302.48-.428-.008-1.252-.241-1.865-.44-.752-.245-1.349-.374-1.297-.789"
                            + ".027-.216.325-.437.893-.663 3.498-1.524 5.83-2.529 6.998-3.014 3.332-1.386 4.025-1.627 4.476-1.635z");
            if (p != null) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postTranslate(-11.4f, -12.6f);
                m.postScale(1.5f, 1.5f);
                m.postTranslate(12f, 12f);
                p.transform(m);
                TELEGRAM_PLANE = p;
            }
        } catch (Throwable ignored) { }
        return TELEGRAM_PLANE;
    }

    public static final class Icon extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rf = new RectF();
        private int kind;
        private int color;

        public Icon(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            this.color = color;
        }

        public void set(int kind, int color) {
            this.kind = kind;
            this.color = color;
            invalidate();
        }

        public void setColor(int color) {
            this.color = color;
            invalidate();
        }

        public int getColor() { return color; }

        private void ln(Canvas c, float a, float b, float x, float y) { c.drawLine(a, b, x, y, p); }
        private void ring(Canvas c, float cx, float cy, float r) { p.setStyle(Paint.Style.STROKE); c.drawCircle(cx, cy, r, p); }
        private void dot(Canvas c, float cx, float cy, float r) { p.setStyle(Paint.Style.FILL); c.drawCircle(cx, cy, r, p); p.setStyle(Paint.Style.STROKE); }
        private void rr(Canvas c, float l, float t, float r, float b, float rad) { rf.set(l, t, r, b); p.setStyle(Paint.Style.STROKE); c.drawRoundRect(rf, rad, rad, p); }
        private void arc(Canvas c, float l, float t, float r, float b, float s, float sw) { rf.set(l, t, r, b); p.setStyle(Paint.Style.STROKE); c.drawArc(rf, s, sw, false, p); }
        private void pth(Canvas c, boolean fill) { p.setStyle(fill ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE); c.drawPath(path, p); p.setStyle(Paint.Style.STROKE); }
        private void txt(Canvas c, String s, float x, float y, float size) {
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(size);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText(s, x, y, p);
            p.setStyle(Paint.Style.STROKE);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h);
            if (s <= 0f) return;
            float u = s / 24f;
            c.save();
            c.translate((w - s) / 2f, (h - s) / 2f);
            c.scale(u, u);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(1.9f);
            path.reset();
            switch (kind) {
                case TELEGRAM: {
                    Path plane = telegramPlane();
                    p.setStyle(Paint.Style.FILL);
                    if (plane != null) c.drawPath(plane, p);
                    else {
                        path.moveTo(3f, 11.2f); path.lineTo(20.6f, 4.2f); path.lineTo(17.6f, 19.8f); path.lineTo(11.4f, 15.2f);
                        path.lineTo(16.2f, 9f); path.lineTo(8.6f, 13.6f); path.close(); c.drawPath(path, p);
                    }
                    p.setStyle(Paint.Style.STROKE);
                    break;
                }
                case MEGAPHONE:
                    path.moveTo(3.5f, 10f); path.lineTo(7.5f, 10f); path.lineTo(15f, 5.5f); path.lineTo(15f, 18.5f);
                    path.lineTo(7.5f, 14f); path.lineTo(3.5f, 14f); path.close(); pth(c, false);
                    ln(c, 7.5f, 14f, 8.8f, 19.5f); arc(c, 12f, 8f, 21f, 16f, -60f, 120f); break;
                case CHAT:
                    rr(c, 3f, 4f, 21f, 17f, 4.5f);
                    path.moveTo(8.5f, 17f); path.lineTo(8.5f, 21f); path.lineTo(13f, 17f); pth(c, false);
                    break;
                case WHATSAPP:
                    arc(c, 3f, 3f, 21f, 21f, -45f, 330f);
                    path.moveTo(4.2f, 20.2f); path.lineTo(5.6f, 15.8f); pth(c, false);
                    path.reset(); path.moveTo(9.2f, 8.2f); path.cubicTo(8.2f, 9.2f, 8.4f, 11.4f, 10.4f, 13.6f);
                    path.cubicTo(12.4f, 15.8f, 14.8f, 16.2f, 15.8f, 15.2f); path.lineTo(15.4f, 13.8f); path.lineTo(13.8f, 13.3f);
                    path.lineTo(13f, 14f); path.cubicTo(11.8f, 13.4f, 10.8f, 12.4f, 10.2f, 11.2f); path.lineTo(10.9f, 10.4f);
                    path.lineTo(10.4f, 8.8f); path.close(); pth(c, false); break;
                case X_LOGO:
                    p.setStrokeWidth(2.4f); ln(c, 5f, 4f, 19f, 20f); p.setStrokeWidth(1.8f); ln(c, 19f, 4f, 5f, 20f); break;
                case CHECK_CIRCLE:
                    ring(c, 12f, 12f, 9f); path.moveTo(7.8f, 12.3f); path.lineTo(10.7f, 15.2f); path.lineTo(16.4f, 9.2f); pth(c, false); break;
                case CROSS_CIRCLE:
                    ring(c, 12f, 12f, 9f); ln(c, 8.8f, 8.8f, 15.2f, 15.2f); ln(c, 15.2f, 8.8f, 8.8f, 15.2f); break;
                case CHEVRON_LEFT:
                    path.moveTo(15f, 5f); path.lineTo(8f, 12f); path.lineTo(15f, 19f); pth(c, false); break;
                case CHEVRON_DOWN:
                    path.moveTo(5f, 9f); path.lineTo(12f, 16f); path.lineTo(19f, 9f); pth(c, false); break;
                case PLUS: ln(c, 12f, 5f, 12f, 19f); ln(c, 5f, 12f, 19f, 12f); break;
                case MINUS: ln(c, 5f, 12f, 19f, 12f); break;
                case CLOSE: ln(c, 6f, 6f, 18f, 18f); ln(c, 18f, 6f, 6f, 18f); break;
                case CHECK: path.moveTo(5f, 12.5f); path.lineTo(10f, 17.5f); path.lineTo(19f, 7f); pth(c, false); break;
                case DOUBLE_CHECK:
                    path.moveTo(2.5f, 12.5f); path.lineTo(7f, 17f); path.lineTo(15f, 7.5f); pth(c, false);
                    path.reset(); path.moveTo(11f, 15.5f); path.lineTo(12.5f, 17f); path.lineTo(21.5f, 7f); pth(c, false); break;
                case USERS:
                    ring(c, 9f, 8f, 3.4f); arc(c, 2.5f, 13.5f, 15.5f, 26f, 180f, 180f);
                    ring(c, 17f, 9f, 2.6f); arc(c, 14f, 14f, 22f, 22f, 200f, 150f); break;
                case USER:
                    ring(c, 12f, 8f, 4f); arc(c, 4f, 14f, 20f, 30f, 180f, 180f); break;
                case EYE:
                    path.moveTo(2f, 12f); path.quadTo(12f, 3f, 22f, 12f); path.quadTo(12f, 21f, 2f, 12f); pth(c, false); ring(c, 12f, 12f, 3f); break;
                case EYE_OFF:
                    path.moveTo(2f, 12f); path.quadTo(12f, 3f, 22f, 12f); path.quadTo(12f, 21f, 2f, 12f); pth(c, false); ln(c, 4f, 4f, 20f, 20f); break;
                case CLICK:
                    path.moveTo(8f, 3.5f); path.lineTo(8f, 17.5f); path.lineTo(11.2f, 14.5f); path.lineTo(14f, 20.5f);
                    path.lineTo(16.4f, 19.4f); path.lineTo(13.7f, 13.6f); path.lineTo(18f, 13.4f); path.close(); pth(c, false); break;
                case CLOCK:
                    ring(c, 12f, 12f, 9f); ln(c, 12f, 7f, 12f, 12f); ln(c, 12f, 12f, 15.5f, 14f); break;
                case PIN:
                    path.moveTo(12f, 21.5f); path.cubicTo(6f, 14.5f, 5f, 11.5f, 5f, 9.5f); path.arcTo(new RectF(5f, 2.5f, 19f, 16.5f), 180f, 180f);
                    path.cubicTo(19f, 11.5f, 18f, 14.5f, 12f, 21.5f); path.close(); pth(c, false); ring(c, 12f, 9.5f, 2.6f); break;
                case GLOBE:
                    ring(c, 12f, 12f, 9f); ln(c, 3f, 12f, 21f, 12f); arc(c, 7.5f, 3f, 16.5f, 21f, 0f, 360f); break;
                case SHIELD:
                    path.moveTo(12f, 3f); path.lineTo(19.5f, 6f); path.lineTo(19.5f, 12f); path.cubicTo(19.5f, 16.5f, 16f, 19.5f, 12f, 21f);
                    path.cubicTo(8f, 19.5f, 4.5f, 16.5f, 4.5f, 12f); path.lineTo(4.5f, 6f); path.close(); pth(c, false);
                    path.reset(); path.moveTo(8.8f, 12f); path.lineTo(11f, 14.2f); path.lineTo(15.4f, 9.6f); pth(c, false); break;
                case FILE:
                    path.moveTo(6f, 3f); path.lineTo(14f, 3f); path.lineTo(19f, 8f); path.lineTo(19f, 21f); path.lineTo(6f, 21f); path.close(); pth(c, false);
                    path.reset(); path.moveTo(14f, 3f); path.lineTo(14f, 8f); path.lineTo(19f, 8f); pth(c, false);
                    ln(c, 9f, 13f, 16f, 13f); ln(c, 9f, 16.5f, 16f, 16.5f); break;
                case CALC:
                    rr(c, 5f, 3f, 19f, 21f, 3f); rr(c, 8f, 6f, 16f, 10f, 1f); dot(c, 9f, 13.5f, 1f); dot(c, 12f, 13.5f, 1f); dot(c, 15f, 13.5f, 1f);
                    dot(c, 9f, 17f, 1f); dot(c, 12f, 17f, 1f); dot(c, 15f, 17f, 1f); break;
                case STAR: case STAR_FILL:
                    path.moveTo(12f, 3f); path.lineTo(14.6f, 9f); path.lineTo(21f, 9.6f); path.lineTo(16.2f, 13.9f);
                    path.lineTo(17.6f, 20.3f); path.lineTo(12f, 17f); path.lineTo(6.4f, 20.3f); path.lineTo(7.8f, 13.9f);
                    path.lineTo(3f, 9.6f); path.lineTo(9.4f, 9f); path.close(); pth(c, kind == STAR_FILL); break;
                case SEND:
                    path.moveTo(21f, 3f); path.lineTo(3f, 10.5f); path.lineTo(10f, 13.5f); path.lineTo(13f, 21f); path.close(); pth(c, false);
                    ln(c, 10f, 13.5f, 21f, 3f); break;
                case LOCK:
                    rr(c, 5f, 11f, 19f, 21f, 3f); arc(c, 8f, 3f, 16f, 15f, 180f, 180f); ln(c, 8f, 9f, 8f, 11f); ln(c, 16f, 9f, 16f, 11f); break;
                case GEAR:
                    ring(c, 12f, 12f, 3f); ring(c, 12f, 12f, 6.8f);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI / 4 * i;
                        float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                        ln(c, 12f + cs * 6.8f, 12f + sn * 6.8f, 12f + cs * 9.3f, 12f + sn * 9.3f);
                    }
                    break;
                case FONT:
                    txt(c, "Aa", 12f, 16.5f, 13f); break;
                case FOLDER:
                    path.moveTo(3f, 6.5f); path.lineTo(9.5f, 6.5f); path.lineTo(11.5f, 9f); path.lineTo(21f, 9f);
                    path.lineTo(21f, 19.5f); path.lineTo(3f, 19.5f); path.close(); pth(c, false); break;
                case HELP:
                    ring(c, 12f, 12f, 9f); txt(c, "?", 12f, 16.6f, 12.5f); break;
                case DOWNLOAD:
                    ln(c, 12f, 4f, 12f, 15f); path.moveTo(7f, 10.5f); path.lineTo(12f, 15.5f); path.lineTo(17f, 10.5f); pth(c, false);
                    path.reset(); path.moveTo(4f, 17f); path.lineTo(4f, 20f); path.lineTo(20f, 20f); path.lineTo(20f, 17f); pth(c, false); break;
                case REFRESH:
                    arc(c, 4f, 4f, 20f, 20f, -60f, 270f); path.moveTo(17.5f, 2.5f); path.lineTo(18.5f, 7.5f); path.lineTo(13.5f, 8.2f); pth(c, false); break;
                case HEADSET:
                    arc(c, 4f, 3f, 20f, 19f, 180f, 180f); rr(c, 3f, 12f, 7f, 18f, 2f); rr(c, 17f, 12f, 21f, 18f, 2f);
                    path.moveTo(19f, 18f); path.quadTo(19f, 21f, 14f, 21f); pth(c, false); break;
                case INFO:
                    ring(c, 12f, 12f, 9f); ln(c, 12f, 11f, 12f, 16.5f); dot(c, 12f, 7.8f, 1.1f); break;
                case ALERT:
                    path.moveTo(12f, 3.5f); path.lineTo(21.5f, 20f); path.lineTo(2.5f, 20f); path.close(); pth(c, false);
                    ln(c, 12f, 10f, 12f, 14.5f); dot(c, 12f, 17.3f, 1.1f); break;
                case BELL:
                    path.moveTo(5.5f, 17f); path.lineTo(18.5f, 17f); path.cubicTo(17f, 15.5f, 16.5f, 14f, 16.5f, 11f);
                    path.cubicTo(16.5f, 7.5f, 14.5f, 5.5f, 12f, 5.5f); path.cubicTo(9.5f, 5.5f, 7.5f, 7.5f, 7.5f, 11f);
                    path.cubicTo(7.5f, 14f, 7f, 15.5f, 5.5f, 17f); path.close(); pth(c, false);
                    arc(c, 10f, 16f, 14f, 21f, 0f, 180f); break;
                case TRASH:
                    ln(c, 4f, 7f, 20f, 7f); path.moveTo(9f, 7f); path.lineTo(9f, 4f); path.lineTo(15f, 4f); path.lineTo(15f, 7f); pth(c, false);
                    path.reset(); path.moveTo(6f, 7f); path.lineTo(7f, 20f); path.lineTo(17f, 20f); path.lineTo(18f, 7f); pth(c, false);
                    ln(c, 10f, 11f, 10f, 16f); ln(c, 14f, 11f, 14f, 16f); break;
                case COPY:
                    rr(c, 8f, 8f, 20f, 21f, 2.5f); path.moveTo(16f, 8f); path.lineTo(16f, 5.5f); path.quadTo(16f, 3.5f, 14f, 3.5f);
                    path.lineTo(6f, 3.5f); path.quadTo(4f, 3.5f, 4f, 5.5f); path.lineTo(4f, 14f); path.quadTo(4f, 16f, 6f, 16f); path.lineTo(8f, 16f); pth(c, false); break;
                case REPLY:
                    path.moveTo(10f, 6f); path.lineTo(4f, 12f); path.lineTo(10f, 18f); pth(c, false);
                    path.reset(); path.moveTo(4.5f, 12f); path.lineTo(14f, 12f); path.cubicTo(18f, 12f, 20f, 14f, 20f, 18.5f); pth(c, false); break;
                case CLIP:
                    path.moveTo(16.5f, 8.5f); path.lineTo(9.5f, 15.5f); path.cubicTo(8.3f, 16.7f, 6.7f, 15.1f, 7.9f, 13.9f);
                    path.lineTo(14.9f, 6.9f); path.cubicTo(17f, 4.8f, 20.2f, 8f, 18.1f, 10.1f); path.lineTo(11.4f, 16.8f);
                    path.cubicTo(8.6f, 19.6f, 4.6f, 15.6f, 7.4f, 12.8f); pth(c, false); break;
                case MIC:
                    rr(c, 9f, 3f, 15f, 14f, 3f); arc(c, 5.5f, 7f, 18.5f, 19f, 0f, 180f); ln(c, 12f, 19f, 12f, 22f); ln(c, 8.5f, 22f, 15.5f, 22f); break;
                case IMAGE:
                    rr(c, 3f, 4f, 21f, 20f, 3f); ring(c, 9f, 9.5f, 1.8f);
                    path.moveTo(4f, 18f); path.lineTo(10f, 12.5f); path.lineTo(14f, 16f); path.lineTo(16.5f, 13.5f); path.lineTo(20f, 17f); pth(c, false); break;
                case VIDEO:
                    rr(c, 3f, 6f, 16f, 18f, 3f); path.moveTo(16f, 10.5f); path.lineTo(21f, 7.5f); path.lineTo(21f, 16.5f); path.lineTo(16f, 13.5f); pth(c, false); break;
                case CAMERA:
                    path.moveTo(3f, 8f); path.lineTo(7f, 8f); path.lineTo(9f, 5f); path.lineTo(15f, 5f); path.lineTo(17f, 8f); path.lineTo(21f, 8f);
                    path.lineTo(21f, 19f); path.lineTo(3f, 19f); path.close(); pth(c, false); ring(c, 12f, 13f, 3.6f); break;
                case PLAY:
                    path.moveTo(7f, 4.5f); path.lineTo(19f, 12f); path.lineTo(7f, 19.5f); path.close(); pth(c, true); break;
                case PAUSE:
                    p.setStyle(Paint.Style.FILL); rf.set(6.5f, 5f, 10.5f, 19f); c.drawRoundRect(rf, 1f, 1f, p);
                    rf.set(13.5f, 5f, 17.5f, 19f); c.drawRoundRect(rf, 1f, 1f, p); p.setStyle(Paint.Style.STROKE); break;
                case STOP:
                    p.setStyle(Paint.Style.FILL); rf.set(6f, 6f, 18f, 18f); c.drawRoundRect(rf, 2f, 2f, p); p.setStyle(Paint.Style.STROKE); break;
                case LOGOUT:
                    path.moveTo(10f, 4f); path.lineTo(4f, 4f); path.lineTo(4f, 20f); path.lineTo(10f, 20f); pth(c, false);
                    ln(c, 9f, 12f, 20f, 12f); path.reset(); path.moveTo(16f, 8f); path.lineTo(20f, 12f); path.lineTo(16f, 16f); pth(c, false); break;
                case RECEIPT:
                    path.moveTo(5f, 3f); path.lineTo(19f, 3f); path.lineTo(19f, 21f); path.lineTo(16.5f, 19f); path.lineTo(14f, 21f); path.lineTo(12f, 19f);
                    path.lineTo(10f, 21f); path.lineTo(7.5f, 19f); path.lineTo(5f, 21f); path.close(); pth(c, false);
                    ln(c, 8.5f, 8f, 15.5f, 8f); ln(c, 8.5f, 12f, 15.5f, 12f); break;
                case TAG:
                    path.moveTo(3f, 12f); path.lineTo(12f, 3f); path.lineTo(21f, 3f); path.lineTo(21f, 12f); path.lineTo(12f, 21f); path.close(); pth(c, false);
                    ring(c, 16.5f, 7.5f, 1.4f); break;
                case BAN:
                    ring(c, 12f, 12f, 9f); ln(c, 5.6f, 5.6f, 18.4f, 18.4f); break;
                case ARCHIVE:
                    rr(c, 3f, 4f, 21f, 9f, 1.5f); path.moveTo(4.5f, 9f); path.lineTo(4.5f, 20f); path.lineTo(19.5f, 20f); path.lineTo(19.5f, 9f); pth(c, false);
                    ln(c, 10f, 13f, 14f, 13f); break;
                case NOTE:
                    rr(c, 4f, 3f, 20f, 21f, 3f); ln(c, 8f, 9f, 16f, 9f); ln(c, 8f, 13f, 16f, 13f); ln(c, 8f, 17f, 12f, 17f); break;
                case FILTER:
                    path.moveTo(3f, 5f); path.lineTo(21f, 5f); path.lineTo(14f, 13f); path.lineTo(14f, 20f); path.lineTo(10f, 18f); path.lineTo(10f, 13f); path.close(); pth(c, false); break;
                case SEARCH:
                    ring(c, 10.5f, 10.5f, 6.5f); ln(c, 15.5f, 15.5f, 20.5f, 20.5f); break;
                case MORE:
                    dot(c, 12f, 5f, 1.8f); dot(c, 12f, 12f, 1.8f); dot(c, 12f, 19f, 1.8f); break;
                case SMILE:
                    ring(c, 12f, 12f, 9f); dot(c, 9f, 10f, 1.2f); dot(c, 15f, 10f, 1.2f); arc(c, 8f, 9.5f, 16f, 17f, 20f, 140f); break;
                case BACK:
                    ln(c, 20f, 12f, 4f, 12f); path.moveTo(10f, 6f); path.lineTo(4f, 12f); path.lineTo(10f, 18f); pth(c, false); break;
                case EDIT:
                    path.moveTo(4f, 20f); path.lineTo(4.8f, 15.5f); path.lineTo(16f, 4.3f); path.lineTo(19.7f, 8f); path.lineTo(8.5f, 19.2f); path.close(); pth(c, false);
                    ln(c, 13.8f, 6.5f, 17.5f, 10.2f); break;
                case CALENDAR:
                    rr(c, 3.5f, 5f, 20.5f, 20.5f, 3f); ln(c, 3.5f, 10f, 20.5f, 10f); ln(c, 8f, 3f, 8f, 7f); ln(c, 16f, 3f, 16f, 7f); break;
                case CHART:
                    ln(c, 4f, 20f, 20f, 20f); rr(c, 5.5f, 11f, 9f, 20f, 1f); rr(c, 10.5f, 5f, 14f, 20f, 1f); rr(c, 15.5f, 14f, 19f, 20f, 1f); break;
                case WALLET:
                    rr(c, 3f, 6f, 21f, 19f, 3.5f); ln(c, 3f, 10f, 21f, 10f); dot(c, 17f, 14.5f, 1.3f); break;
                case BOLT:
                    path.moveTo(13f, 2.5f); path.lineTo(5f, 13.5f); path.lineTo(11f, 13.5f); path.lineTo(10f, 21.5f); path.lineTo(19f, 10f); path.lineTo(13f, 10f); path.close(); pth(c, false); break;
                case LINK:
                    rr(c, 3f, 9f, 12f, 15f, 3f); rr(c, 12f, 9f, 21f, 15f, 3f); ln(c, 9f, 12f, 15f, 12f); break;
                case PHONE:
                    path.moveTo(6.5f, 3.5f); path.lineTo(9.5f, 3.5f); path.lineTo(11f, 8f); path.lineTo(9f, 9.6f);
                    path.cubicTo(10f, 12f, 12f, 14f, 14.4f, 15f); path.lineTo(16f, 13f); path.lineTo(20.5f, 14.5f); path.lineTo(20.5f, 17.5f);
                    path.cubicTo(20.5f, 19f, 19f, 20.5f, 17.5f, 20.5f); path.cubicTo(10f, 20f, 4f, 14f, 3.5f, 6.5f);
                    path.cubicTo(3.5f, 5f, 5f, 3.5f, 6.5f, 3.5f); path.close(); pth(c, false); break;
                case EXPORT:
                    path.moveTo(12f, 15f); path.lineTo(12f, 3.5f); pth(c, false); path.reset(); path.moveTo(7.5f, 8f); path.lineTo(12f, 3.5f); path.lineTo(16.5f, 8f); pth(c, false);
                    path.reset(); path.moveTo(5f, 11f); path.lineTo(5f, 20.5f); path.lineTo(19f, 20.5f); path.lineTo(19f, 11f); pth(c, false); break;
                case ASSIGN:
                    ring(c, 10f, 8f, 3.6f); arc(c, 3f, 14f, 17f, 28f, 180f, 180f); ln(c, 18f, 9f, 18f, 15f); ln(c, 15f, 12f, 21f, 12f); break;
                case FLAG:
                    ln(c, 5f, 3f, 5f, 21f); path.moveTo(5f, 4f); path.lineTo(18f, 4f); path.lineTo(15f, 8.5f); path.lineTo(18f, 13f); path.lineTo(5f, 13f); pth(c, false); break;
                case SPARK:
                    path.moveTo(12f, 3f); path.quadTo(13f, 11f, 21f, 12f); path.quadTo(13f, 13f, 12f, 21f); path.quadTo(11f, 13f, 3f, 12f); path.quadTo(11f, 11f, 12f, 3f); pth(c, false); break;
                case SUN:
                    ring(c, 12f, 12f, 4f);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI / 4 * i;
                        float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                        ln(c, 12f + cs * 7f, 12f + sn * 7f, 12f + cs * 9.5f, 12f + sn * 9.5f);
                    }
                    break;
                case SWITCH:
                    ln(c, 4.5f, 8f, 18.5f, 8f); path.moveTo(15f, 4.5f); path.lineTo(18.5f, 8f); path.lineTo(15f, 11.5f); pth(c, false);
                    path.reset(); ln(c, 19.5f, 16f, 5.5f, 16f); path.moveTo(9f, 12.5f); path.lineTo(5.5f, 16f); path.lineTo(9f, 19.5f); pth(c, false); break;
                case BOOKMARK:
                    path.moveTo(6.5f, 4f); path.lineTo(17.5f, 4f); path.lineTo(17.5f, 20.5f); path.lineTo(12f, 16.5f); path.lineTo(6.5f, 20.5f); path.close(); pth(c, false); break;
                case CROWN:
                    path.moveTo(4f, 17f); path.lineTo(4.8f, 7.5f); path.lineTo(9.2f, 11.5f); path.lineTo(12f, 5.5f); path.lineTo(14.8f, 11.5f); path.lineTo(19.2f, 7.5f); path.lineTo(20f, 17f); path.close(); pth(c, false);
                    ln(c, 5f, 20.5f, 19f, 20.5f); break;
                case CIGRAM:
                    // the Cigram mark: a ring opened on the right, the solid "D" inside
                    arc(c, 3.5f, 3.5f, 20.5f, 20.5f, 28f, 304f);
                    path.moveTo(9.6f, 8.6f); path.lineTo(12.3f, 8.6f); path.quadTo(15.6f, 8.6f, 15.6f, 12f); path.quadTo(15.6f, 15.4f, 12.3f, 15.4f); path.lineTo(9.6f, 15.4f); path.close(); pth(c, true); break;
                case TV:
                    rr(c, 3f, 5f, 21f, 17f, 2.2f); ln(c, 8.5f, 20.5f, 15.5f, 20.5f); ln(c, 12f, 17f, 12f, 20.5f); break;
                case CAST:
                    path.moveTo(3f, 9f); path.lineTo(3f, 6.5f); path.quadTo(3f, 5f, 4.5f, 5f); path.lineTo(19.5f, 5f); path.quadTo(21f, 5f, 21f, 6.5f);
                    path.lineTo(21f, 17.5f); path.quadTo(21f, 19f, 19.5f, 19f); path.lineTo(14.5f, 19f); pth(c, false);
                    arc(c, -1f, 15f, 7f, 23f, 270f, 90f); arc(c, -5f, 11f, 11f, 27f, 270f, 90f); dot(c, 3.6f, 18.4f, 1.3f); break;
                case QUALITY:
                    rr(c, 2.8f, 6f, 21.2f, 18f, 2.6f); txt(c, "HD", 12f, 14.9f, 7.2f); break;
                case SUBTITLES:
                    rr(c, 2.8f, 5f, 21.2f, 19f, 2.6f); ln(c, 6.5f, 12.5f, 10.5f, 12.5f); ln(c, 13f, 12.5f, 17.5f, 12.5f); ln(c, 6.5f, 15.8f, 14.5f, 15.8f); break;
                case AUDIO:
                    path.moveTo(3.5f, 9.5f); path.lineTo(7.5f, 9.5f); path.lineTo(12f, 5.5f); path.lineTo(12f, 18.5f); path.lineTo(7.5f, 14.5f); path.lineTo(3.5f, 14.5f); path.close(); pth(c, false);
                    arc(c, 11.5f, 8.5f, 17.5f, 15.5f, -55f, 110f); arc(c, 11f, 5f, 21.5f, 19f, -55f, 110f); break;
                case SPEED:
                    arc(c, 3f, 5f, 21f, 23f, 195f, 150f); ln(c, 12f, 14f, 16.2f, 9.2f); dot(c, 12f, 14f, 1.5f); break;
                case APPS:
                    rr(c, 3.5f, 3.5f, 10.5f, 10.5f, 2.2f); rr(c, 13.5f, 3.5f, 20.5f, 10.5f, 2.2f);
                    rr(c, 3.5f, 13.5f, 10.5f, 20.5f, 2.2f); rr(c, 13.5f, 13.5f, 20.5f, 20.5f, 2.2f); break;
                case REPLAY10:
                    arc(c, 4f, 4.5f, 20f, 20.5f, 280f, 290f); path.moveTo(12.6f, 1.9f); path.lineTo(9.6f, 4.5f); path.lineTo(12.6f, 7.1f); pth(c, false);
                    txt(c, "10", 12f, 15.3f, 6.6f); break;
                case FORWARD10:
                    arc(c, 4f, 4.5f, 20f, 20.5f, -30f, 290f); path.moveTo(11.4f, 1.9f); path.lineTo(14.4f, 4.5f); path.lineTo(11.4f, 7.1f); pth(c, false);
                    txt(c, "10", 12f, 15.3f, 6.6f); break;
                case SKIP_NEXT:
                    path.moveTo(5.5f, 6f); path.lineTo(14f, 12f); path.lineTo(5.5f, 18f); path.close(); pth(c, false); ln(c, 17.5f, 6f, 17.5f, 18f); break;
                case SKIP_PREV:
                    path.moveTo(18.5f, 6f); path.lineTo(10f, 12f); path.lineTo(18.5f, 18f); path.close(); pth(c, false); ln(c, 6.5f, 6f, 6.5f, 18f); break;
                case PALETTE:
                    path.moveTo(12f, 3.5f); path.quadTo(20.5f, 3.5f, 20.5f, 11f); path.quadTo(20.5f, 15f, 16.5f, 15f); path.lineTo(14.5f, 15f);
                    path.quadTo(12.8f, 15f, 13.3f, 16.8f); path.quadTo(14f, 20.5f, 12f, 20.5f); path.quadTo(3.5f, 20.5f, 3.5f, 12f); path.quadTo(3.5f, 3.5f, 12f, 3.5f); pth(c, false);
                    dot(c, 8f, 11f, 1.3f); dot(c, 11f, 7.5f, 1.3f); dot(c, 15.5f, 8.5f, 1.3f); break;
                case DOC:
                    path.moveTo(6f, 3.5f); path.lineTo(14f, 3.5f); path.lineTo(18.5f, 8f); path.lineTo(18.5f, 20.5f); path.lineTo(6f, 20.5f); path.close(); pth(c, false);
                    ln(c, 9f, 11.5f, 15.5f, 11.5f); ln(c, 9f, 14.5f, 15.5f, 14.5f); ln(c, 9f, 17.5f, 13.5f, 17.5f); break;
                default: break;
            }
            c.restore();
        }
    }
}
