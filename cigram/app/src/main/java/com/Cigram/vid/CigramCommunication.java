package com.Cigram.vid;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * شاشة «أرسل طلبك أو مشكلتك» (communication.xml ← CommunicationActivity) — iOS، أبيض وأسود.
 *
 *   [ طلب فيلم ] [ طلب مسلسل ] [ طلب قناة ]      ← اسم العنوان ثم Gmail (أو إرسال مباشر)، ويُحفظ في «طلباتي»
 *   طلباتي                                        ← كل طلب مع حالته (قيد المراجعة / تمت الإضافة / ...)
 *   [ حل مشكلة ]                                  ← شفاف بخط أبيض، يفتح محادثة الدعم بالمشكلة جاهزة
 *   لاحقاً                                        ← إغلاق الشاشة
 *
 * البريد المستلم: CigramReport.SUPPORT_EMAIL (app.cigram@gmail.com).
 * الاستدعاء من CommunicationActivity.onCreate:  CigramCommunication.install(CommunicationActivity.this);
 */
public final class CigramCommunication {

    private CigramCommunication() { }

    /** «غير ذلك»: يُفتح Gmail بهذا الموضوع ويعدّله المستخدم كما يشاء. */
    private static final String SUBJECT_OTHER = "مشكلة أخرى";
    private static final String TAG_MINE = "cigram_my_requests";

    private static final String[] PROBLEMS = {
            "الفيلم أو المسلسل لا يعمل",
            "الفيديو يتقطع أو بطيء",
            "مشكلة في الصوت أو الترجمة",
            "القناة المباشرة لا تعمل",
            "مشكلة في تسجيل الدخول أو الحساب",
            "التطبيق يتوقف أو يغلق",
    };

    private static final int PURPLE = 0xFF8E7CFF;

    private static View byId(Activity a, String name) {
        int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
        return id == 0 ? null : a.findViewById(id);
    }

    public static void install(final Activity a) {
        if (a == null || a.isFinishing()) return;
        try {
            View root = byId(a, "linear_communication_root");
            if (root != null) root.setBackgroundColor(CigramGlass.bg(a));

            View scroll = byId(a, "vscroll_communication");
            if (scroll instanceof ScrollView) {
                ((ScrollView) scroll).setFillViewport(true);
                scroll.setVerticalScrollBarEnabled(false);
            }

            View title = byId(a, "textview_communication_title");
            if (title instanceof TextView) {
                TextView t = (TextView) title;
                t.setText("أرسل طلبك أو مشكلتك");
                t.setTextColor(0xFFFFFFFF);
                t.setTypeface(CigramUI.font(a), android.graphics.Typeface.BOLD);
            }

            View bodyView = byId(a, "linear_communication_body");
            if (!(bodyView instanceof LinearLayout)) return;
            final LinearLayout body = (LinearLayout) bodyView;
            body.removeAllViews();
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(CigramUI.dp(a, 16), CigramUI.dp(a, 20), CigramUI.dp(a, 16), CigramUI.dp(a, 16));
            ViewGroup.LayoutParams blp = body.getLayoutParams();
            if (blp != null) { blp.height = ViewGroup.LayoutParams.MATCH_PARENT; body.setLayoutParams(blp); }

            // ---- عنوان + وصف
            TextView head = CigramUI.label(a, "أرسل طلبك أو مشكلتك", 26f, CigramUI.TEXT, true);
            head.setTypeface(CigramTypefaces.bold() != null ? CigramTypefaces.bold() : CigramUI.font(a), android.graphics.Typeface.BOLD);
            body.addView(head, new LinearLayout.LayoutParams(-1, -2));
            TextView sub = CigramUI.label(a, "اطلب فيلماً أو مسلسلاً أو قناة وتابع ردّ المدير هنا، أو اكتب لنا مشكلتك.",
                    14f, CigramGlass.muted(a), false);
            sub.setLineSpacing(0f, 1.2f);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
            slp.topMargin = CigramUI.dp(a, 6);
            body.addView(sub, slp);

            // ---- ثلاث أيقونات: فيلم / مسلسل / قناة (الأول من اليمين)
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            row.addView(requestTile(a, body, Pic.FILM, 0xFFFFFFFF, "طلب فيلم", CigramRequests.MOVIE), tileLp(a));
            row.addView(requestTile(a, body, Pic.SERIES, 0xFFFFFFFF, "طلب مسلسل", CigramRequests.SERIES), tileLp(a));
            row.addView(requestTile(a, body, Pic.CHANNEL, 0xFFFFFFFF, "طلب قناة", CigramRequests.CHANNEL), tileLp(a));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.topMargin = CigramUI.dp(a, 24);
            body.addView(row, rlp);

            // ---- طلباتي (يظهر فقط عند وجود طلبات)
            LinearLayout mine = new LinearLayout(a);
            mine.setTag(TAG_MINE);
            mine.setOrientation(LinearLayout.VERTICAL);
            body.addView(mine, new LinearLayout.LayoutParams(-1, -2));
            renderMine(a, mine);

            // ---- فراغ يدفع «حل مشكلة» ولاحقاً إلى الأسفل
            View spacer = new View(a);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 0, 1f);
            sp.topMargin = CigramUI.dp(a, 20);
            body.addView(spacer, sp);

            // ---- حل مشكلة: خلفية شفافة، أيقونة وخط أبيض
            body.addView(problemButton(a), new LinearLayout.LayoutParams(-1, CigramUI.dp(a, 64)));

            // ---- لاحقاً
            TextView later = CigramUI.label(a, "لاحقاً", 15f, CigramGlass.muted(a), true);
            later.setGravity(Gravity.CENTER);
            later.setClickable(true);
            later.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { a.finish(); }
            });
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, CigramUI.dp(a, 52));
            llp.topMargin = CigramUI.dp(a, 8);
            body.addView(later, llp);

            // تحديث الحالات من الخادم والكتالوج ثم إعادة الرسم
            CigramRequests.refresh(a, true, new Runnable() {
                @Override public void run() {
                    if (a.isFinishing()) return;
                    View m = body.findViewWithTag(TAG_MINE);
                    if (m instanceof LinearLayout) renderMine(a, (LinearLayout) m);
                }
            });
        } catch (Throwable error) {
            android.util.Log.w("CigramCommunication", "install failed", error);
        }
    }

    private static LinearLayout.LayoutParams tileLp(Activity a) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, CigramUI.dp(a, 128), 1f);
        lp.setMargins(CigramUI.dp(a, 5), 0, CigramUI.dp(a, 5), 0);
        return lp;
    }

    /** بطاقة أيقونة + اسمها تحتها. */
    private static View requestTile(final Activity a, final LinearLayout body, int kind, int accent, String name,
                                    final String requestKind) {
        LinearLayout tile = new LinearLayout(a);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setClickable(true);
        tile.setFocusable(true);
        CigramGlass.bind(tile, 24, true);
        CigramGlass.press(tile);

        LinearLayout badge = new LinearLayout(a);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(CigramUI.round(0x1AFFFFFF, 0x40FFFFFF, 28, a));     // white line art on a glass disc
        badge.addView(new Pic(a, kind, accent), new LinearLayout.LayoutParams(CigramUI.dp(a, 30), CigramUI.dp(a, 30)));
        tile.addView(badge, new LinearLayout.LayoutParams(CigramUI.dp(a, 56), CigramUI.dp(a, 56)));

        TextView label = CigramUI.label(a, name, 14.5f, CigramUI.TEXT, true);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = CigramUI.dp(a, 12);
        tile.addView(label, tl);

        tile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showRequestSheet(a, body, requestKind); }
        });
        return tile;
    }

    // ------------------------------------------------------------------ request sheet

    private static void showRequestSheet(final Activity a, final LinearLayout body, final String kind) {
        final String label = CigramRequests.kindLabel(kind);
        final EditText input = new EditText(a);
        CigramUI.styleInput(input, CigramRequests.CHANNEL.equals(kind) ? "اسم القناة" : "اسم ال" + label + " (عربي أو إنجليزي)");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);

        CigramUI.sheet(a)
                .title("طلب " + label)
                .message("اكتب الاسم وأرسله. يصل طلبك للمدير فوراً، وتتابع ردّه في «طلباتي».")
                .view(input)
                .primary("إرسال الطلب", 0xFFFFFFFF, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) {
                        String name = input.getText().toString().trim();
                        if (name.length() < 2) { input.setError("اكتب الاسم"); return true; }
                        CigramRequests.add(a, kind, name, "");
                        CigramChannelReports.snack(a, "وصل طلبك للمدير", null, null);
                        refreshMine(a, body);
                        return false;
                    }
                })
                .secondary("إلغاء", null)
                .show();
        input.requestFocus();
    }

    private static void refreshMine(Activity a, LinearLayout body) {
        View m = body.findViewWithTag(TAG_MINE);
        if (m instanceof LinearLayout) renderMine(a, (LinearLayout) m);
    }

    // ------------------------------------------------------------------ طلباتي

    private static void renderMine(final Activity a, final LinearLayout box) {
        box.removeAllViews();
        ArrayList<JSONObject> items = CigramRequests.list(a);
        if (items.isEmpty()) return;

        TextView head = CigramUI.label(a, "طلباتي", 17f, CigramUI.TEXT, true);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = CigramUI.dp(a, 26);
        box.addView(head, hl);

        int shown = 0;
        for (final JSONObject r : items) {
            if (shown++ >= 12) break;
            box.addView(requestRow(a, box, r), rowLp(a));
        }
    }

    private static View requestRow(final Activity a, final LinearLayout box, final JSONObject r) {
        final String status = r.optString("status", CigramRequests.PENDING);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(CigramUI.dp(a, 14), CigramUI.dp(a, 10), CigramUI.dp(a, 14), CigramUI.dp(a, 10));
        row.setBackground(CigramUI.round(CigramUI.BG_SOFT, CigramUI.STROKE, 16, a));

        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = CigramUI.label(a, r.optString("title"), 15f, CigramUI.TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(name, new LinearLayout.LayoutParams(-1, -2));
        String meta = CigramRequests.kindLabel(r.optString("kind"));
        String note = r.optString("admin_note", "");
        boolean ready = CigramRequests.ADDED.equals(status) && r.optString("match_id").length() > 0;
        CharSequence line;
        if (ready) {
            // «تم رفعها، انقر هنا» — the last two words in blue, they open the title itself
            String before = "تم رفعها، ", link = "انقر هنا";
            android.text.SpannableString sp = new android.text.SpannableString(before + link);
            sp.setSpan(new android.text.style.ForegroundColorSpan(0xFF0A84FF), before.length(), sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), before.length(), sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            line = sp;
        } else if (CigramRequests.IN_PROGRESS.equals(status)) {
            line = "المدير وافق، سيتم إضافته قريباً" + (note.length() > 0 ? " · " + note : "");
        } else {
            line = note.length() > 0 ? meta + " · " + note : meta;
        }
        TextView sub = CigramUI.label(a, "", 12.5f, CigramUI.MUTED, false);
        sub.setText(line);
        sub.setMaxLines(2);
        text.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));

        int color = CigramRequests.statusColor(status);
        TextView chip = CigramUI.label(a, CigramRequests.statusLabel(status), 12f, color, true);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setPadding(CigramUI.dp(a, 10), CigramUI.dp(a, 4), CigramUI.dp(a, 10), CigramUI.dp(a, 4));
        chip.setBackground(CigramUI.round(0x14FFFFFF, 0x33FFFFFF, 12, a));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.leftMargin = CigramUI.dp(a, 8);
        row.addView(chip, cl);

        if (CigramRequests.ADDED.equals(status) && r.optString("match_id").length() > 0) {
            row.setClickable(true);
            CigramGlass.press(row);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    CigramLinks.open(a, Uri.parse("cigram://title?type=" + r.optString("match_type", "movie")
                            + "&id=" + CigramApi.enc(r.optString("match_id"))
                            + "&n=" + CigramApi.enc(r.optString("match_title", r.optString("title")))));
                }
            });
        }
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                CigramUI.sheet(a).title("حذف الطلب؟").message("«" + r.optString("title") + "» سيُحذف من هذه القائمة فقط.")
                        .danger("حذف", new CigramUI.Click() {
                            @Override public boolean onClick(CigramUI.Sheet s) {
                                CigramRequests.remove(a, r.optString("local_id"));
                                renderMine(a, box);
                                return false;
                            }
                        })
                        .secondary("إلغاء", null).show();
                return true;
            }
        });
        return row;
    }

    // ------------------------------------------------------------------ حل مشكلة

    /** زر «حل مشكلة»: بلا لون خلفية، إطار وأيقونة ونص أبيض. */
    private static View problemButton(final Activity a) {
        LinearLayout btn = new LinearLayout(a);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setPadding(CigramUI.dp(a, 18), 0, CigramUI.dp(a, 18), 0);

        GradientDrawable outline = new GradientDrawable();
        outline.setColor(0x00000000);
        outline.setCornerRadius(CigramUI.dp(a, 22));
        outline.setStroke(Math.max(2, CigramUI.dp(a, 1.5f)), 0xFFFFFFFF);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(CigramUI.dp(a, 22));
        btn.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), outline, mask));

        btn.addView(new Pic(a, Pic.PROBLEM, 0xFFFFFFFF), new LinearLayout.LayoutParams(CigramUI.dp(a, 28), CigramUI.dp(a, 28)));
        TextView label = CigramUI.label(a, "حل مشكلة", 17f, 0xFFFFFFFF, true);
        label.setPadding(CigramUI.dp(a, 12), 0, CigramUI.dp(a, 12), 0);
        btn.addView(label, new LinearLayout.LayoutParams(0, -1, 1f));
        btn.addView(new Pic(a, Pic.CHEVRON, 0xFFFFFFFF), new LinearLayout.LayoutParams(CigramUI.dp(a, 20), CigramUI.dp(a, 20)));

        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showProblems(a); }
        });
        return btn;
    }

    /** قائمة المشاكل المقترحة. الضغط على واحدة يفتح Gmail وموضوعه نصها نفسه. */
    private static void showProblems(final Activity a) {
        final CigramUI.Sheet sheet = CigramUI.sheet(a)
                .title("ما المشكلة التي تواجهك؟")
                .message("اختر الأقرب لمشكلتك وتُفتح محادثة «الدعم والتواصل» بها جاهزة.");

        final int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.42f);
        ScrollView sv = new ScrollView(a) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
            }
        };
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list, new ViewGroup.LayoutParams(-1, -2));

        for (final String p : PROBLEMS) list.addView(problemRow(a, sheet, p, p, false), rowLp(a));
        list.addView(problemRow(a, sheet, "غير ذلك", SUBJECT_OTHER, true), rowLp(a));

        sheet.view(sv).secondary("إلغاء", null).show();
    }

    private static LinearLayout.LayoutParams rowLp(Activity a) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = CigramUI.dp(a, 8);
        return lp;
    }

    private static View problemRow(final Activity a, final CigramUI.Sheet sheet, String text, final String subject, boolean other) {
        TextView row = CigramUI.label(a, text, 15f, CigramUI.TEXT, other);
        row.setMinHeight(CigramUI.dp(a, 52));
        row.setPadding(CigramUI.dp(a, 16), 0, CigramUI.dp(a, 16), 0);
        row.setBackground(CigramUI.round(CigramUI.BG_SOFT, CigramUI.STROKE, 16, a));
        row.setClickable(true);
        row.setFocusable(true);
        CigramGlass.press(row);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { sheet.dialog.dismiss(); } catch (Throwable ignored) { }
                openProblem(a, subject);
            }
        });
        return row;
    }

    /**
     * The problem goes to the support conversation inside the app (signed-in accounts), written and ready to send.
     * Nothing is handed to an e-mail app unless the viewer has no account to talk from.
     */
    static void openProblem(Activity a, String subject) {
        try {
            if (CigramUserData.isLoggedIn(a)) {
                Intent chat = new Intent(a, AdsChatActivity.class);
                chat.putExtra("prefill", SUBJECT_OTHER.equals(subject) ? "أواجه مشكلة: " : "أواجه مشكلة: " + subject + " — ");
                a.startActivity(chat);
                return;
            }
        } catch (Throwable ignored) { }
        openMail(a, subject);
    }

    /** يفتح Gmail مباشرة: المستلم app.cigram@gmail.com، الموضوع جاهز، والنص فارغ. */
    public static void openMail(Activity a, String subject) {
        Intent mail = new Intent(Intent.ACTION_SENDTO);
        mail.setData(Uri.parse("mailto:"));
        mail.putExtra(Intent.EXTRA_EMAIL, new String[]{CigramReport.SUPPORT_EMAIL});
        mail.putExtra(Intent.EXTRA_SUBJECT, subject);
        try {
            mail.setPackage("com.google.android.gm");
            a.startActivity(mail);
        } catch (Exception gmailMissing) {
            try {
                mail.setPackage(null);
                a.startActivity(Intent.createChooser(mail, "مراسلة الدعم"));
            } catch (Exception noMailApp) {
                Toast.makeText(a, "لا يوجد تطبيق بريد. راسلنا على " + CigramReport.SUPPORT_EMAIL, Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------------ أيقونات خطية (نفس سماكة بقية التطبيق)

    static final class Pic extends View {
        static final int FILM = 1, SERIES = 2, CHANNEL = 3, PROBLEM = 4, CHEVRON = 5;

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rf = new RectF();
        private final int kind;
        private final int color;

        Pic(android.content.Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            this.color = color;
        }

        @Override protected void onDraw(Canvas cv) {
            float s = Math.min(getWidth(), getHeight()) / 24f;
            cv.save();
            cv.translate((getWidth() - 24f * s) / 2f, (getHeight() - 24f * s) / 2f);
            cv.scale(s, s);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.8f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            switch (kind) {
                case FILM:
                    rf.set(3.5f, 4f, 20.5f, 20f);
                    cv.drawRoundRect(rf, 2.5f, 2.5f, p);
                    cv.drawLine(8f, 4f, 8f, 20f, p);
                    cv.drawLine(16f, 4f, 16f, 20f, p);
                    for (float y = 8f; y <= 16f; y += 4f) {
                        cv.drawLine(3.5f, y, 8f, y, p);
                        cv.drawLine(16f, y, 20.5f, y, p);
                    }
                    break;
                case SERIES:
                    rf.set(3f, 7f, 21f, 19f);
                    cv.drawRoundRect(rf, 2.5f, 2.5f, p);
                    path.reset();
                    path.moveTo(9f, 3.5f); path.lineTo(12f, 7f); path.lineTo(15f, 3.5f);
                    cv.drawPath(path, p);
                    path.reset();
                    path.moveTo(10.5f, 10.5f); path.lineTo(14.5f, 13f); path.lineTo(10.5f, 15.5f); path.close();
                    cv.drawPath(path, p);
                    cv.drawLine(8f, 21.5f, 16f, 21.5f, p);
                    break;
                case CHANNEL:
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(12f, 12f, 2f, p);
                    p.setStyle(Paint.Style.STROKE);
                    rf.set(8f, 8f, 16f, 16f);
                    cv.drawArc(rf, 135f, 90f, false, p);
                    cv.drawArc(rf, -45f, 90f, false, p);
                    rf.set(4.5f, 4.5f, 19.5f, 19.5f);
                    cv.drawArc(rf, 135f, 90f, false, p);
                    cv.drawArc(rf, -45f, 90f, false, p);
                    break;
                case PROBLEM:
                    path.reset();
                    path.moveTo(12f, 3.5f); path.lineTo(21.5f, 20f); path.lineTo(2.5f, 20f); path.close();
                    cv.drawPath(path, p);
                    cv.drawLine(12f, 9.5f, 12f, 14.5f, p);
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(12f, 17.2f, 1f, p);
                    break;
                case CHEVRON:
                    path.reset();
                    path.moveTo(14.5f, 6f); path.lineTo(8.5f, 12f); path.lineTo(14.5f, 18f);
                    cv.drawPath(path, p);
                    break;
                default:
                    break;
            }
            cv.restore();
        }
    }
}
