package com.my.newproject;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Success screen with buttons that depend on what was just added. */
public class CxSuccessActivity extends CxBase {

    private String kind = "";
    private String id = "";
    private String title = "";
    private String poster = "";
    private String note = "";
    private String undoId = "";
    private String seriesId = "";
    private int season;
    private int episode;

    @Override
    protected String screenTitle() {
        return "تم بنجاح";
    }

    private String ex(String k) {
        String v = getIntent().getStringExtra(k);
        return v == null ? "" : v;
    }

    @Override
    protected void onBuild() {
        kind = ex("kind");
        id = ex("id");
        title = ex("title");
        poster = ex("poster");
        note = ex("note");
        undoId = ex("undo_id");
        seriesId = ex("series_id");
        season = getIntent().getIntExtra("season", 0);
        episode = getIntent().getIntExtra("episode", 0);
        initCx(null);
        setBottomVisible(false);
        render();
        if (undoId.length() > 0) {
            showUndo(headline(), undoId, new Runnable() {
                @Override
                public void run() {
                    CxNav.home(CxSuccessActivity.this);
                    finish();
                }
            });
        }
    }

    private String headline() {
        boolean upd = "updated".equals(note);
        if ("movie".equals(kind)) return upd ? "تم تحديث الفيلم" : "تمت إضافة الفيلم";
        if ("series".equals(kind)) return "تمت إضافة المسلسل";
        if ("episode".equals(kind)) return upd ? "تم تحديث الحلقة" : "تمت إضافة الحلقة";
        if ("season".equals(kind)) return "تم نشر الموسم";
        if ("carousel".equals(kind)) return "تم حفظ الشريحة";
        if ("channel".equals(kind)) return "تمت إضافة القناة";
        return "تم بنجاح";
    }

    private void render() {
        LinearLayout box = CgUi.card(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView check = CgUi.text(this, "✔", 34, CgCfg.GOOD, true);
        check.setGravity(Gravity.CENTER);
        check.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.GOOD, 0x26), 40, CgCfg.GOOD));
        box.addView(check, new LinearLayout.LayoutParams(CgUi.dp(this, 72), CgUi.dp(this, 72)));
        TextView h = CgUi.text(this, headline(), 19, CgCfg.TEXT, true);
        h.setGravity(Gravity.CENTER);
        box.addView(h, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 12, 0, 4));
        if (CxJ.isUrl(poster)) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(CgUi.bg(this, CgCfg.CARD2, 10, 0));
            boolean wide = "carousel".equals(kind);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.dp(this, wide ? 220 : 110), CgUi.dp(this, wide ? 110 : 165));
            lp.setMargins(0, CgUi.dp(this, 8), 0, CgUi.dp(this, 8));
            box.addView(iv, lp);
            CgImg.load(iv, poster, 360);
        }
        if (title.length() > 0) {
            TextView t = CgUi.text(this, title, 16, CgCfg.TEXT, true);
            t.setGravity(Gravity.CENTER);
            box.addView(t, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        }
        String sub = subline();
        if (sub.length() > 0) {
            TextView s = CgUi.muted(this, sub);
            s.setGravity(Gravity.CENTER);
            box.addView(s, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
        }
        box.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 14));
        page.addView(box);

        if ("movie".equals(kind)) {
            action("فتح الفيلم", 0, new Runnable() {
                @Override
                public void run() {
                    CxNav.openItem(CxSuccessActivity.this, id);
                }
            });
            action("إضافة إلى الكاروسيل", 1, new Runnable() {
                @Override
                public void run() {
                    CxNav.carouselAdd(CxSuccessActivity.this, id);
                }
            });
            action("إضافة فيلم آخر", 1, new Runnable() {
                @Override
                public void run() {
                    CxNav.addMovie(CxSuccessActivity.this);
                    finish();
                }
            });
        } else if ("series".equals(kind)) {
            action("إضافة حلقة", 0, new Runnable() {
                @Override
                public void run() {
                    CxNav.addEpisode(CxSuccessActivity.this, seriesId.length() > 0 ? seriesId : id, 1, 1);
                    finish();
                }
            });
            action("إضافة موسم كامل", 1, new Runnable() {
                @Override
                public void run() {
                    CxNav.addSeason(CxSuccessActivity.this, seriesId.length() > 0 ? seriesId : id, 1);
                    finish();
                }
            });
            action("إضافة إلى الكاروسيل", 1, new Runnable() {
                @Override
                public void run() {
                    CxNav.carouselAdd(CxSuccessActivity.this, id);
                }
            });
            action("فتح المسلسل", 3, new Runnable() {
                @Override
                public void run() {
                    CxNav.openItem(CxSuccessActivity.this, seriesId.length() > 0 ? seriesId : id);
                }
            });
        } else if ("episode".equals(kind)) {
            action("إضافة الحلقة التالية", 0, new Runnable() {
                @Override
                public void run() {
                    CxNav.addEpisode(CxSuccessActivity.this, seriesId, Math.max(1, season), episode + 1);
                    finish();
                }
            });
            action("فتح المسلسل", 1, new Runnable() {
                @Override
                public void run() {
                    CxNav.openItem(CxSuccessActivity.this, seriesId.length() > 0 ? seriesId : id);
                }
            });
            action("إضافة موسم كامل", 3, new Runnable() {
                @Override
                public void run() {
                    CxNav.addSeason(CxSuccessActivity.this, seriesId, season + 1);
                    finish();
                }
            });
        } else if ("season".equals(kind)) {
            action("فتح المسلسل", 0, new Runnable() {
                @Override
                public void run() {
                    CxNav.openItem(CxSuccessActivity.this, seriesId.length() > 0 ? seriesId : id);
                }
            });
            action("إضافة الموسم التالي", 1, new Runnable() {
                @Override
                public void run() {
                    CxNav.addSeason(CxSuccessActivity.this, seriesId, season + 1);
                    finish();
                }
            });
            action("إضافة حلقة منفردة", 3, new Runnable() {
                @Override
                public void run() {
                    CxNav.addEpisode(CxSuccessActivity.this, seriesId, season, 0);
                    finish();
                }
            });
        } else if ("carousel".equals(kind)) {
            action("إدارة الكاروسيل", 0, new Runnable() {
                @Override
                public void run() {
                    CxNav.carouselManage(CxSuccessActivity.this);
                    finish();
                }
            });
            action("إضافة شريحة أخرى", 1, new Runnable() {
                @Override
                public void run() {
                    CxNav.carouselAdd(CxSuccessActivity.this, "");
                    finish();
                }
            });
        } else if ("channel".equals(kind)) {
            action("إضافة قناة أخرى", 0, new Runnable() {
                @Override
                public void run() {
                    CxNav.channel(CxSuccessActivity.this);
                    finish();
                }
            });
            action("عرض القنوات", 1, new Runnable() {
                @Override
                public void run() {
                    startActivity(new Intent(CxSuccessActivity.this, CgChannelsActivity.class));
                }
            });
        }
        action("العودة إلى إدارة المحتوى", 3, new Runnable() {
            @Override
            public void run() {
                CxNav.home(CxSuccessActivity.this);
                finish();
            }
        });
    }

    private String subline() {
        if ("episode".equals(kind) && season > 0 && episode > 0) return "الموسم " + season + " • الحلقة " + episode;
        if ("season".equals(kind) && season > 0) return "الموسم " + season + (note.length() > 0 && !"created".equals(note) ? " • " + note : "");
        if ("channel".equals(kind) && note.length() > 0) return note;
        if ("carousel".equals(kind) && note.length() > 0) return note;
        return "";
    }

    private void action(String label, int style, final Runnable r) {
        TextView b = CgUi.button(this, label, style);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        page.addView(b, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
    }

    @Override
    public void onBackPressed() {
        CxNav.home(this);
        finish();
    }
}
