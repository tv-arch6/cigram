package com.my.newproject;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * New "add movie" screen in two sections:
 *   1. type the name -> TMDB / IMDb results as big posters -> tap one -> poster + information card
 *   2. the movie file: upload from the phone or paste a link; the link is attached to the poster card
 *      automatically (on paste, or as soon as the background upload is verified) -> publish.
 * Writes exactly what the classic screen writes (/admin/item/create after the duplicate and link checks).
 */
public class NxMovieActivity extends CxBase {

    private static final int REQ_UPLOAD = 7811;

    private JSONObject st = new JSONObject();
    private boolean picked;
    private int mode;                 // 0 from the phone, 1 link
    private String video = "";        // attached video link
    private String videoSource = "";  // "upload" | "link"
    private String videoCheck = "";   // "", "checking", "ok", or a failure label
    private String subtitle = "";
    private long uploadSince;
    private String lastUploadSig = "";
    private boolean skipVideoCheck;

    private NxSearch search;
    private NxParts.Hero hero;
    private LinearLayout mediaBox;
    private TextView publishBtn;
    private CxUi.Field linkField;
    private CxUi.Field subField;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!alive()) return;
            watchUploads();
            ui().postDelayed(this, 1500);
        }
    };

    @Override
    protected String screenTitle() {
        return "إضافة فيلم";
    }

    @Override
    protected void onBuild() {
        initCx(null);
        try {
            bar.view.setBackgroundColor(NxUi.BG);
        } catch (Throwable ignored) { }
        ensureData(new Runnable() {
            @Override
            public void run() {
                render();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui().removeCallbacks(poll);
        ui().postDelayed(poll, 600);
    }

    @Override
    protected void onPause() {
        ui().removeCallbacks(poll);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (picked && video.length() == 0) {
            picked = false;
            st = new JSONObject();
            render();
            return;
        }
        if (picked) {
            CgUi.confirm(this, "الخروج بدون نشر؟", "الفيلم لم يُنشر بعد. الرفع الجاري (إن وجد) يكمل في الخلفية ويمكنك ربطه لاحقاً.",
                    "خروج", true, new Runnable() {
                        @Override
                        public void run() {
                            finish();
                        }
                    });
            return;
        }
        super.onBackPressed();
    }

    // ===================================================================== render

    private void render() {
        if (search != null) search.stop();
        page.removeAllViews();
        page.addView(NxParts.step(this, 1, "اختر الفيلم", picked ? "تم الاختيار — يمكنك تغييره من أيقونة البحث" : "اكتب الاسم ثم اضغط على البوستر الصحيح", picked, true));
        if (!picked) {
            search = new NxSearch(this, "movie", new CgUi.Callback<JSONObject>() {
                @Override
                public void run(JSONObject card) {
                    loadDetails(card);
                }
            });
            page.addView(search.view);
            TextView manual = CgUi.text(this, "لا يوجد في TMDB؟ الإدخال اليدوي بالشاشة الكلاسيكية  ‹", 12.5f, NxUi.SUB, true);
            manual.setPadding(dp(6), dp(12), dp(6), dp(4));
            manual.setClickable(true);
            manual.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(NxMovieActivity.this, CxMovieActivity.class));
                }
            });
            page.addView(manual);
        } else {
            hero = NxParts.hero(this, st, false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (video.length() > 0) {
                        CgUi.confirm(NxMovieActivity.this, "اختيار فيلم آخر؟", "سيُفك ربط ملف الفيديو الحالي من هذه البطاقة.", "تغيير", false, new Runnable() {
                            @Override
                            public void run() {
                                resetPick();
                            }
                        });
                    } else {
                        resetPick();
                    }
                }
            }, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editInfo();
                }
            });
            page.addView(hero.view);
            NxUi.enter(hero.view, 0);
            page.addView(NxParts.sections(this, st, false, new NxParts.Picked() {
                @Override
                public void run(String id, String name) {
                    CxJ.put(st, "section_id", id);
                    CxJ.put(st, "section_name", name);
                    render();
                }
            }), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 10, 0, 0));
        }

        page.addView(NxParts.step(this, 2, "ملف الفيلم", picked ? "من الجهاز أو برابط — يُربط بالبوستر تلقائياً" : "يُفتح بعد اختيار الفيلم", video.length() > 0, picked));
        mediaBox = CgUi.vbox(this);
        page.addView(mediaBox);
        renderMedia();
        renderBottom();
    }

    private int dp(float v) {
        return CgUi.dp(this, v);
    }

    private void resetPick() {
        picked = false;
        st = new JSONObject();
        video = "";
        videoSource = "";
        videoCheck = "";
        subtitle = "";
        uploadSince = 0;
        render();
    }

    // ==================================================================== search

    private void loadDetails(final JSONObject card) {
        final String id = String.valueOf(card.optInt("tmdb_id"));
        task("جارٍ جلب معلومات الفيلم...", new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                JSONObject b = new JSONObject();
                b.put("type", "movie");
                b.put("tmdb_id", id);
                return CxApi.post("/admin/tmdb/details", b);
            }
        }, new Ok<JSONObject>() {
            @Override
            public void ok(JSONObject d) {
                JSONArray dups = d.optJSONArray("duplicates");
                if (dups != null && dups.length() > 0 && dups.optJSONObject(0) != null) {
                    existing(dups.optJSONObject(0), d);
                    return;
                }
                apply(d);
            }
        }, new Runnable() {
            @Override
            public void run() {
                loadDetails(card);
            }
        });
    }

    private void apply(JSONObject d) {
        st = new JSONObject();
        CxJ.put(st, "tmdb_id", String.valueOf(d.optInt("tmdb_id")));
        CxJ.put(st, "imdb_id", CxJ.s(d, "imdb_id"));
        CxJ.put(st, "title", CxJ.s(d, "title"));
        CxJ.put(st, "original_title", CxJ.s(d, "original_title"));
        CxJ.put(st, "description", CxJ.s(d, "description"));
        CxJ.put(st, "year", CxJ.s(d, "year"));
        CxJ.put(st, "category", CxJ.s(d, "category"));
        CxJ.put(st, "rating", CxJ.s(d, "rating_text"));
        CxJ.put(st, "poster", CxJ.s(d, "poster"));
        CxJ.put(st, "backdrop", CxJ.s(d, "backdrop"));
        CxJ.put(st, "duration", CxJ.s(d, "duration"));
        CxJ.put(st, "slug", CxJ.s(d, "suggested_slug"));
        CxJ.put(st, "suggested_sections", d.optJSONArray("suggested_sections") == null ? new JSONArray() : d.optJSONArray("suggested_sections"));
        picked = true;
        uploadSince = 0;
        // VidBolt: the player link is built from the TMDB id, so the movie is ready to publish at once
        String vb = CgVidbolt.movie(CxJ.s(st, "tmdb_id"));
        if (vb.length() > 0 && video.length() == 0) {
            video = vb;
            videoSource = "link";
            videoCheck = "ok";
        }
        render();
        scrollToTop();
        if (vb.length() > 0) snack("تم إنشاء رابط VidBolt تلقائياً للفيلم.", CxUi.GOOD);
    }

    /** The title is already in the library: open it, edit it, or add anyway (e.g. another version). */
    private void existing(final JSONObject dup, final JSONObject details) {
        final String id = CxJ.s(dup, "id");
        LinearLayout box = CgUi.hbox(this);
        box.setPadding(dp(18), dp(10), dp(18), 0);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(NxUi.flat(this, NxUi.SURF_HI, 10, 0));
        iv.setClipToOutline(true);
        CgImg.load(iv, CxJ.s(dup, "poster"), 200);
        box.addView(iv, new LinearLayout.LayoutParams(dp(60), dp(90)));
        LinearLayout col = CgUi.vbox(this);
        col.addView(CgUi.text(this, CxJ.s(dup, "title"), 15, NxUi.TEXT, true));
        col.addView(CgUi.text(this, "موجود في المكتبة" + (CxJ.s(dup, "year").length() > 0 ? " • " + CxJ.s(dup, "year") : ""), 12.5f, CgCfg.WARN, true));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        cl.setMargins(dp(12), 0, 0, 0);
        box.addView(col, cl);
        CgUi.dialog(this).setTitle("هذا الفيلم مضاف مسبقاً").setView(box)
                .setPositiveButton("تعديل الموجود", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        CxNav.editMovie(NxMovieActivity.this, id);
                    }
                })
                .setNeutralButton("فتح", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        CxNav.openItem(NxMovieActivity.this, id);
                    }
                })
                .setNegativeButton("إضافة نسخة أخرى", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        apply(details);
                    }
                }).show();
    }

    private void editInfo() {
        CgUi.choose(this, "تعديل", new CharSequence[]{"الاسم", "الوصف", "اسم المجلد في R2 (Slug)"}, new CgUi.IntCb() {
            @Override
            public void run(final int which) {
                final String key = which == 0 ? "title" : which == 1 ? "description" : "slug";
                String label = which == 0 ? "اسم الفيلم" : which == 1 ? "الوصف" : "Slug (حروف إنكليزية وشرطات)";
                CgUi.input(NxMovieActivity.this, label, "", CxJ.s(st, key), which == 1, new CgUi.Callback<String>() {
                    @Override
                    public void run(String value) {
                        String v = value.trim();
                        if (which != 1 && v.length() == 0) return;
                        if (which == 2) v = CgData.slugify(v);
                        CxJ.put(st, key, v);
                        render();
                    }
                });
            }
        });
    }

    // ===================================================================== media

    private void renderMedia() {
        mediaBox.removeAllViews();
        if (hero != null && picked) renderHeroStatus();
        if (!picked) {
            LinearLayout c = CgUi.vbox(this);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            c.setBackground(NxUi.dashed(this, NxUi.DIM, 20));
            c.setPadding(dp(16), dp(26), dp(16), dp(26));
            c.addView(NxUi.icon(this, NxIcons.icon(CgIcons.LOCK, NxUi.DIM), 0), new LinearLayout.LayoutParams(dp(28), dp(28)));
            TextView t = CgUi.text(this, "اختر الفيلم أولاً حتى يُربط الملف ببطاقته", 13, NxUi.DIM, true);
            t.setGravity(Gravity.CENTER);
            c.addView(t, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 8, 0, 0));
            mediaBox.addView(c);
            return;
        }
        if (video.length() > 0) {
            mediaBox.addView(attachedCard());
            mediaBox.addView(subtitleBox(), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 10, 0, 0));
            return;
        }
        mediaBox.addView(NxUi.segmented(this, new String[]{"من الجهاز", "برابط"}, new int[]{NxIcons.PHONE, CgIcons.LINK}, mode, new NxUi.SegCb() {
            @Override
            public void run(int index) {
                mode = index;
                renderMedia();
            }
        }));
        if (mode == 0) {
            CgUploadSession s = currentUpload();
            if (s != null && !s.isFinished()) mediaBox.addView(progressCard(s), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));
            else mediaBox.addView(dropZone(s), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));
        } else {
            mediaBox.addView(linkBox(), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));
        }
    }

    private void renderHeroStatus() {
        if (video.length() > 0) {
            ImageView play = NxUi.icon(this, NxIcons.icon(CgIcons.PLAY, 0xFFFFFFFF), 7);
            NxUi.pressable(play, NxUi.gradient(this, 0xFF10B981, 0xFF059669, 15), 15);
            play.setLayoutParams(new LinearLayout.LayoutParams(dp(30), dp(30)));
            play.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    CxMedia.openPlayer(NxMovieActivity.this, CxJ.s(st, "title"), video, subtitle);
                }
            });
            String extra = "ok".equals(videoCheck) ? " • يعمل" : "checking".equals(videoCheck) ? " • جارٍ الفحص" : videoCheck.length() > 0 ? " • " + videoCheck : "";
            NxParts.status(this, hero.statusStrip, CgIcons.CHECK, NxUi.UP, "الفيديو مرتبط بالبطاقة (" + ("upload".equals(videoSource) ? "مرفوع" : NxParts.host(video)) + ")" + extra, play);
            return;
        }
        CgUploadSession s = currentUpload();
        if (s != null && !s.isFinished()) {
            int pct = s.bytesTotal > 0 ? (int) Math.min(100, s.bytesDone * 100 / s.bytesTotal) : 0;
            NxParts.status(this, hero.statusStrip, CgIcons.UPLOAD, NxUi.BLUE, (s.isActive() ? "جارٍ الرفع " + pct + "%" : "الرفع متوقف " + pct + "%") + " — يُربط تلقائياً عند الانتهاء", null);
            return;
        }
        NxParts.status(this, hero.statusStrip, CgIcons.LINK, NxUi.SUB, "لم يُربط ملف الفيلم بعد", null);
    }

    private View dropZone(final CgUploadSession last) {
        LinearLayout z = CgUi.vbox(this);
        z.setGravity(Gravity.CENTER_HORIZONTAL);
        NxUi.pressable(z, NxUi.dashed(this, NxUi.BLUE, 22), 22);
        z.setPadding(dp(16), dp(28), dp(16), dp(24));
        ImageView ic = NxUi.iconTile(this, NxIcons.icon(CgIcons.UPLOAD, 0xFFFFFFFF), NxUi.gradient(this, 0xFF4F6BFF, 0xFF6C8CFF, 20), 14);
        z.addView(ic, new LinearLayout.LayoutParams(dp(58), dp(58)));
        TextView t = CgUi.text(this, "اختر ملف الفيلم من الجهاز", 15.5f, NxUi.TEXT, true);
        t.setGravity(Gravity.CENTER);
        z.addView(t, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));
        TextView s = CgUi.text(this, "ضغط تلقائي واستكمال عند انقطاع الشبكة، ويكمل في الخلفية. عند انتهاء الرفع يُربط الرابط بالبطاقة وحده.", 11.5f, NxUi.SUB, false);
        s.setGravity(Gravity.CENTER);
        z.addView(s, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 4, 0, 0));
        if (last != null && (CgUploadSession.FAILED.equals(last.state) || CgUploadSession.CANCELLED.equals(last.state))) {
            TextView w = CgUi.text(this, "آخر محاولة لم تكتمل: " + (last.message.length() > 0 ? last.message : last.state), 11.5f, NxUi.DOWN, true);
            w.setGravity(Gravity.CENTER);
            z.addView(w, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 8, 0, 0));
        }
        z.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startUpload();
            }
        });
        return z;
    }

    private View progressCard(final CgUploadSession s) {
        LinearLayout c = CgUi.vbox(this);
        int col = s.isActive() ? NxUi.BLUE : NxUi.ORANGE;
        NxUi.pressable(c, NxUi.tint(this, col, 20), 20);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout top = CgUi.hbox(this);
        top.addView(NxUi.icon(this, NxIcons.icon(CgIcons.UPLOAD, col), 0), new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout mid = CgUi.vbox(this);
        TextView n = CgUi.text(this, s.srcName.length() > 0 ? s.srcName : CxJ.s(st, "title"), 13.5f, NxUi.TEXT, true);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        mid.addView(n);
        mid.addView(CgUi.text(this, s.isActive() ? (s.queued ? "في الانتظار" : stage(s.state)) : "متوقف — اضغط للمتابعة", 11.5f, NxUi.SUB, false));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        ml.setMargins(dp(10), 0, dp(8), 0);
        top.addView(mid, ml);
        int pct = s.bytesTotal > 0 ? (int) Math.min(100, s.bytesDone * 100 / s.bytesTotal) : 0;
        TextView p = CgUi.text(this, pct + "%", 20, col, true);
        CgUi.ltr(p);
        top.addView(p);
        c.addView(top);
        NxChart.Stack bar = new NxChart.Stack(this);
        bar.set(new double[]{Math.max(1, pct), Math.max(0, 100 - pct)}, new int[]{col, 0x00000000});
        c.addView(bar, NxUi.lp(this, NxUi.MATCH, dp(8), 0, 12, 0, 0));
        if (s.bytesTotal > 0) {
            TextView sz = CgUi.text(this, CgUi.sizeText(s.bytesDone) + " / " + CgUi.sizeText(s.bytesTotal), 11, NxUi.DIM, false);
            CgUi.ltr(sz);
            c.addView(sz, NxUi.lp(this, NxUi.WRAP, NxUi.WRAP, 0, 6, 0, 0));
        }
        TextView note = CgUi.text(this, "يمكنك ترك هذه الشاشة؛ عند العودة يُربط الملف تلقائياً بعد التحقق.", 11, NxUi.DIM, false);
        c.addView(note, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 6, 0, 0));
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(NxMovieActivity.this, CgMediaUploadActivity.class);
                i.putExtra("session_id", s.id);
                startActivityForResult(i, REQ_UPLOAD);
            }
        });
        return c;
    }

    private View linkBox() {
        LinearLayout box = CgUi.vbox(this);
        box.setBackground(NxUi.surface(this, 20));
        box.setPadding(dp(14), dp(14), dp(14), dp(10));
        linkField = new CxUi.Field(this, "رابط ملف الفيلم", "https://.../movie.mp4", CxUi.T_URL, true);
        linkField.onChange(new Runnable() {
            @Override
            public void run() {
                final String u = linkField.text();
                if (CxJ.isUrl(u) && u.length() > 12) {
                    ui().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (linkField != null && u.equals(linkField.text()) && video.length() == 0) attach(u, "link");
                        }
                    }, 500);
                }
            }
        });
        box.addView(linkField.view);
        LinearLayout row = CgUi.hbox(this);
        TextView paste = NxUi.secondary(this, "لصق الرابط");
        android.graphics.drawable.Drawable pd = NxIcons.icon(NxIcons.PASTE, NxUi.TEXT);
        pd.setBounds(0, 0, dp(18), dp(18));
        paste.setCompoundDrawablesRelative(pd, null, null, null);
        paste.setCompoundDrawablePadding(dp(6));
        paste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String c = NxParts.clipboard(NxMovieActivity.this);
                if (!CxJ.isUrl(c)) {
                    snack("لا يوجد رابط منسوخ (يجب أن يبدأ بـ http).", CxUi.WARN);
                    return;
                }
                linkField.input.setText(c);
            }
        });
        row.addView(paste, new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f));
        box.addView(row, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 0, 0, 4));
        TextView n = CgUi.text(this, "عند لصق رابط صحيح يُربط بالبطاقة فوراً ويُفحص في الخلفية.", 11, NxUi.DIM, false);
        box.addView(n, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 2, 6, 2, 0));
        return box;
    }

    private View attachedCard() {
        LinearLayout c = CgUi.vbox(this);
        c.setBackground(NxUi.tint(this, NxUi.UP, 20));
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout top = CgUi.hbox(this);
        ImageView ok = NxUi.iconTile(this, NxIcons.icon(CgIcons.CHECK, 0xFF06281D), CgUi.bg(this, NxUi.UP, 14, 0), 6);
        top.addView(ok, new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout mid = CgUi.vbox(this);
        mid.addView(CgUi.text(this, "upload".equals(videoSource) ? "تم رفع الفيلم وربطه بالبطاقة" : "تم ربط الرابط بالبطاقة", 14.5f, NxUi.TEXT, true));
        TextView u = CgUi.text(this, video, 11.5f, NxUi.SUB, false);
        CgUi.ltr(u);
        u.setSingleLine(true);
        u.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        mid.addView(u);
        int cc = "ok".equals(videoCheck) ? NxUi.UP : "checking".equals(videoCheck) || videoCheck.length() == 0 ? NxUi.SUB : NxUi.DOWN;
        String ct = "ok".equals(videoCheck) ? "الرابط يعمل" : "checking".equals(videoCheck) ? "جارٍ فحص الرابط…" : videoCheck.length() == 0 ? "" : "تنبيه: " + videoCheck;
        if (ct.length() > 0) mid.addView(CgUi.text(this, ct, 11.5f, cc, true));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        ml.setMargins(dp(10), 0, 0, 0);
        top.addView(mid, ml);
        c.addView(top);
        LinearLayout row = CgUi.hbox(this);
        TextView play = NxUi.secondary(this, "تشغيل للتجربة");
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CxMedia.openPlayer(NxMovieActivity.this, CxJ.s(st, "title"), video, subtitle);
            }
        });
        row.addView(play, new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f));
        TextView change = NxUi.secondary(this, "تغيير الملف");
        change.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                video = "";
                videoSource = "";
                videoCheck = "";
                uploadSince = System.currentTimeMillis(); // older uploads must not re-attach themselves
                render();
            }
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        cl.setMargins(dp(8), 0, 0, 0);
        row.addView(change, cl);
        c.addView(row, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));
        return c;
    }

    private View subtitleBox() {
        LinearLayout box = CgUi.vbox(this);
        box.setBackground(NxUi.surface(this, 18));
        box.setPadding(dp(14), dp(12), dp(14), dp(10));
        subField = new CxUi.Field(this, "ترجمة (اختياري) .srt / .vtt", "https://.../movie.srt", CxUi.T_URL, false);
        subField.set(subtitle);
        subField.onChange(new Runnable() {
            @Override
            public void run() {
                subtitle = subField.text();
            }
        });
        box.addView(subField.view);
        LinearLayout row = CgUi.hbox(this);
        CgMediaHook.addSubtitleButton(this, row, subField, target());
        box.addView(row);
        return box;
    }

    private CgMediaHook.Target target() {
        return new CgMediaHook.Target() {
            @Override
            public String kind() {
                return "movie";
            }

            @Override
            public String slug() {
                return CxJ.s(st, "slug");
            }

            @Override
            public String title() {
                return CxJ.s(st, "title");
            }

            @Override
            public String poster() {
                return CxJ.s(st, "poster");
            }

            @Override
            public int season() {
                return 0;
            }

            @Override
            public int episode() {
                return 0;
            }
        };
    }

    private void startUpload() {
        String slug = CgMediaHook.resolveSlug(target());
        if (slug.length() == 0) {
            CgUi.info(this, "اسم المجلد مطلوب", "تعذر تكوين اسم مجلد R2 من اسم الفيلم. عدّل الاسم بحروف إنكليزية ثم أعد المحاولة.");
            return;
        }
        if (CxJ.s(st, "slug").length() == 0) CxJ.put(st, "slug", slug);
        uploadSince = System.currentTimeMillis();
        Intent i = new Intent(this, CgMediaUploadActivity.class);
        i.putExtra("kind", "movie");
        i.putExtra("slug", slug);
        i.putExtra("title", CxJ.s(st, "title"));
        i.putExtra("poster", CxJ.s(st, "poster"));
        i.putExtra("season", 0);
        i.putExtra("episode", 0);
        startActivityForResult(i, REQ_UPLOAD);
    }

    /** The newest upload of this movie's folder (started from here, or recently, e.g. after leaving the screen). */
    private CgUploadSession currentUpload() {
        if (!picked) return null;
        try {
            String slug = CgMediaHook.resolveSlug(target());
            if (slug.length() == 0) return null;
            ArrayList<CgUploadSession> all = CgUploadManager.get(this).list();
            CgUploadSession best = null;
            long now = System.currentTimeMillis();
            for (int i = 0; i < all.size(); i++) {
                CgUploadSession s = all.get(i);
                if (s.isEpisode() || !slug.equals(s.slug)) continue;
                if (uploadSince > 0 ? s.createdAt < uploadSince - 120000L : now - s.updatedAt > 24L * 3600000L) continue;
                if (best == null || s.createdAt > best.createdAt) best = s;
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Every 1.5 s: refresh the progress, and attach the link by itself when the upload is verified. */
    private void watchUploads() {
        if (!picked || video.length() > 0) return;
        CgUploadSession s = currentUpload();
        if (s == null) return;
        if (CgUploadSession.COMPLETED.equals(s.state) && s.publicUrl.length() > 0) {
            attach(s.publicUrl, "upload");
            snack("اكتمل الرفع وتم ربط الفيلم بالبطاقة تلقائياً.", CxUi.GOOD);
            return;
        }
        int pct = s.bytesTotal > 0 ? (int) (s.bytesDone * 100 / s.bytesTotal) : 0;
        String sig = s.id + "|" + s.state + "|" + pct + "|" + s.queued;
        if (!sig.equals(lastUploadSig)) {
            lastUploadSig = sig;
            if (mode == 0) renderMedia();
            else if (hero != null) renderHeroStatus();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_UPLOAD) {
            if (res == RESULT_OK && data != null) {
                String url = data.getStringExtra("video_url");
                if (url != null && url.length() > 0) attach(url, "upload");
            } else {
                renderMedia();
            }
            return;
        }
        CgMediaHook.onResult(this, req, res, data);
    }

    private void attach(String url, String source) {
        if (!alive()) return;
        video = url.trim();
        videoSource = source;
        videoCheck = "upload".equals(source) ? "ok" : "checking";
        render();
        if (!"upload".equals(source)) checkLink(video);
    }

    private void checkLink(final String url) {
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                JSONObject b = new JSONObject();
                JSONArray arr = new JSONArray();
                arr.put(new JSONObject().put("id", "v").put("kind", "video").put("url", url));
                b.put("items", arr);
                JSONObject d = CxApi.post("/admin/check-urls", b);
                JSONArray r = d.optJSONArray("results");
                return r == null || r.length() == 0 ? new JSONObject() : r.getJSONObject(0);
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                if (!url.equals(video)) return;
                String s = r.optString("status", "");
                videoCheck = "ok".equals(s) ? "ok" : s.length() > 0 ? CgData.checkLabel(s) : "";
                renderMedia();
            }

            @Override
            public void fail(CxApi.Failure f) {
                if (!url.equals(video)) return;
                videoCheck = "";
                renderMedia();
            }
        });
    }

    // ==================================================================== publish

    private void renderBottom() {
        bottom.removeAllViews();
        bottom.setVisibility(View.VISIBLE);
        publishBtn = NxUi.primary(this, picked && video.length() > 0 ? "نشر الفيلم" : picked ? "اربط ملف الفيلم للنشر" : "اختر الفيلم أولاً");
        android.graphics.drawable.Drawable d = NxIcons.icon(CgIcons.PUBLISH, 0xFFFFFFFF);
        d.setBounds(0, 0, dp(20), dp(20));
        publishBtn.setCompoundDrawablesRelative(d, null, null, null);
        publishBtn.setCompoundDrawablePadding(dp(8));
        NxUi.enable(publishBtn, picked && video.length() > 0);
        publishBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (CxJ.s(st, "section_id").length() == 0) {
                    CgUi.confirm(NxMovieActivity.this, "بدون قسم", "لم تختر قسماً، فلن يظهر الفيلم في صفوف الصفحة الرئيسية (يبقى في البحث). النشر رغم ذلك؟",
                            "نشر", false, new Runnable() {
                                @Override
                                public void run() {
                                    skipVideoCheck = false;
                                    publish();
                                }
                            });
                    return;
                }
                skipVideoCheck = false;
                publish();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        lp.setMargins(dp(4), dp(2), dp(4), dp(2));
        bottom.addView(publishBtn, lp);
    }

    private JSONObject fields() {
        JSONObject f = new JSONObject();
        String[] keys = {"title", "original_title", "description", "year", "category", "poster", "backdrop", "slug",
                "section_id", "section_name", "imdb_id", "duration"};
        for (String k : keys) {
            String v = CxJ.s(st, k);
            if (v.length() > 0) CxJ.put(f, k, v);
        }
        if (CxJ.s(st, "tmdb_id").length() > 0) CxJ.put(f, "tmdb", CxJ.s(st, "tmdb_id"));
        if (CxJ.s(st, "rating").length() > 0) CxJ.put(f, "imdb", CxJ.s(st, "rating"));
        CxJ.put(f, "video", video);
        CxJ.put(f, "link", video);
        if (subtitle.length() > 0) CxJ.put(f, "subtitle", subtitle);
        return f;
    }

    private void publish() {
        if (subtitle.length() > 0 && !CxJ.isUrl(subtitle)) {
            snack("رابط الترجمة يجب أن يبدأ بـ http:// أو https://", CxUi.ERROR);
            return;
        }
        final boolean skip = skipVideoCheck || "ok".equals(videoCheck);
        final JSONObject f = fields();
        final CxStages sg = CxStages.show(this, "نشر الفيلم", new String[]{"فحص التكرار", "التحقق من الرابط", "الحفظ", "التحقق من الحفظ", "اكتمل"});
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                sg.stage(0, "");
                JSONObject q = new JSONObject();
                q.put("type", "movie");
                q.put("tmdb", CxJ.s(st, "tmdb_id"));
                q.put("imdb", CxJ.s(st, "imdb_id"));
                q.put("slug", CxJ.s(st, "slug"));
                q.put("title", CxJ.s(st, "title"));
                q.put("year", CxJ.s(st, "year"));
                JSONArray matches = CxApi.post("/admin/duplicates/check", q).optJSONArray("matches");
                for (int i = 0; matches != null && i < matches.length(); i++) {
                    JSONObject m = matches.getJSONObject(i);
                    JSONArray why = m.optJSONArray("reasons");
                    boolean real = false;
                    for (int k = 0; why != null && k < why.length(); k++) if (!"slug".equals(why.optString(k))) real = true;
                    if (real && !allowDuplicate) {
                        JSONObject extra = new JSONObject();
                        extra.put("existing", m);
                        throw new CxApi.Failure("هذا الفيلم موجود بالفعل.", "DUPLICATE_CONTENT", extra, 409);
                    }
                }
                sg.stage(1, video);
                if (!skip) {
                    JSONObject b = new JSONObject();
                    JSONArray arr = new JSONArray();
                    arr.put(new JSONObject().put("id", "v").put("kind", "video").put("url", video));
                    b.put("items", arr);
                    JSONArray res = CxApi.post("/admin/check-urls", b).optJSONArray("results");
                    if (res != null && res.length() > 0 && !"ok".equals(res.getJSONObject(0).optString("status"))) {
                        throw new CxApi.Failure("رابط الفيديو لا يعمل: " + CgData.checkLabel(res.getJSONObject(0).optString("status")), "VIDEO_BAD", null, 0);
                    }
                }
                sg.stage(2, "");
                JSONObject b = new JSONObject();
                b.put("type", "movie");
                b.put("fields", f);
                JSONObject saved = CxApi.post("/admin/item/create", b);
                JSONObject item = saved.optJSONObject("item");
                if (item == null) throw new CxApi.Failure("لم يؤكد الخادم الحفظ. أعد المحاولة.", "NO_ITEM", null, 0);
                sg.stage(3, "");
                sg.stage(4, "");
                JSONObject out = new JSONObject();
                out.put("item", item);
                out.put("undo_id", saved.optString("undo_id", ""));
                return out;
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                sg.dismiss();
                JSONObject item = r.optJSONObject("item");
                CgData d = CgData.cur();
                if (d != null && item != null) d.replaceItem(item);
                CxRecent.push(NxMovieActivity.this, "movie", CxJ.s(item, "title"), CxJ.s(item, "poster"), CxJ.s(item, "year"));
                CxNav.success(NxMovieActivity.this, "movie", CxJ.s(item, "id"), CxJ.s(item, "title"), CxJ.s(item, "poster"),
                        "created", r.optString("undo_id"), "", 0, 0);
                finish();
            }

            @Override
            public void fail(CxApi.Failure f) {
                if (f.is("VIDEO_BAD")) {
                    sg.dismiss();
                    CgUi.confirm(NxMovieActivity.this, "الفيديو لا يعمل", f.getMessage() + "\nهل تريد النشر رغم ذلك؟", "نشر رغم ذلك", true, new Runnable() {
                        @Override
                        public void run() {
                            skipVideoCheck = true;
                            publish();
                        }
                    });
                } else if (f.is("DUPLICATE_CONTENT")) {
                    sg.dismiss();
                    final JSONObject ex = f.existing();
                    CgUi.confirm(NxMovieActivity.this, "موجود بالفعل", "يوجد في المكتبة «" + CxJ.s(ex, "title") + "». هل تضيف هذه النسخة أيضاً؟",
                            "إضافة رغم ذلك", false, new Runnable() {
                                @Override
                                public void run() {
                                    allowDuplicate = true;
                                    publish();
                                }
                            });
                } else if (f.is("DUPLICATE_SLUG")) {
                    sg.dismiss();
                    CgUi.info(NxMovieActivity.this, "اسم المجلد مستخدم", f.getMessage() + "\nغيّره من أيقونة التعديل على البطاقة (اسم المجلد) ثم انشر.");
                } else {
                    sg.fail(f.getMessage(), new Runnable() {
                        @Override
                        public void run() {
                            publish();
                        }
                    });
                }
            }
        });
    }

    private boolean allowDuplicate;

    private static String stage(String s) {
        if (CgUploadSession.COMPRESSING.equals(s)) return "جارٍ ضغط الفيديو";
        if (CgUploadSession.PREPARING_UPLOAD.equals(s)) return "جارٍ التحضير";
        if (CgUploadSession.UPLOADING.equals(s)) return "جارٍ الرفع";
        if (CgUploadSession.VERIFYING.equals(s)) return "جارٍ التحقق";
        if (CgUploadSession.ANALYZING.equals(s)) return "جارٍ تحليل الملف";
        return "قيد التنفيذ";
    }
}
