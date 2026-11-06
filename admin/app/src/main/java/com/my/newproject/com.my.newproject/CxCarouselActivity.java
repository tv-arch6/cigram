package com.my.newproject;

import android.content.ClipData;
import android.content.Intent;
import android.os.Build;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Carousel management: ordered slide list, drag-and-drop (plus up/down buttons), status, edit, remove. */
public class CxCarouselActivity extends CxBase {

    private final ArrayList<JSONObject> slides = new ArrayList<JSONObject>();
    private final ArrayList<String> originalOrder = new ArrayList<String>();
    private boolean dirty;
    private boolean loadedOnce;
    private int loadToken;
    private int dragFrom = -1;
    private LinearLayout listBox;
    private LinearLayout headBox;

    @Override
    protected String screenTitle() {
        return "إدارة الكاروسيل";
    }

    @Override
    protected void onBuild() {
        initCx(null);
        bar.action("+ شريحة", new Runnable() {
            @Override
            public void run() {
                CxNav.carouselAdd(CxCarouselActivity.this, "");
            }
        });
        setBottomVisible(false);
        headBox = CgUi.vbox(this);
        page.addView(headBox);
        listBox = CgUi.vbox(this);
        page.addView(listBox);
        listBox.addView(CxUi.skeleton(this, 3));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!dirty) load();
    }

    private void load() {
        final int token = ++loadToken;
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                return CxApi.get("/admin/carousel");
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                if (token != loadToken || dirty) return;
                loadedOnce = true;
                slides.clear();
                originalOrder.clear();
                JSONArray arr = CxJ.arr(r, "items");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = CxJ.at(arr, i);
                    slides.add(o);
                    originalOrder.add(idOf(o));
                }
                dirty = false;
                setBottomVisible(false);
                render();
            }

            @Override
            public void fail(CxApi.Failure f) {
                if (token != loadToken || dirty) return;
                listBox.clearAnimation();
                listBox.removeAllViews();
                listBox.addView(CgUi.errorCard(CxCarouselActivity.this, f.getMessage(), new Runnable() {
                    @Override
                    public void run() {
                        listBox.removeAllViews();
                        listBox.addView(CxUi.skeleton(CxCarouselActivity.this, 3));
                        load();
                    }
                }));
            }
        });
    }

    private static String idOf(JSONObject o) {
        return CxJ.firstNonEmpty(CxJ.s(o, "content_id"), CxJ.s(o, "id"));
    }

    private void render() {
        headBox.removeAllViews();
        listBox.clearAnimation();
        listBox.removeAllViews();
        int active = 0;
        for (int i = 0; i < slides.size(); i++) if ("active".equals(CxJ.s(slides.get(i), "status"))) active++;
        headBox.addView(CgUi.muted(this, slides.size() + " شريحة • " + active + " نشطة الآن"));
        if (slides.size() > 1) {
            headBox.addView(CxUi.notice(this, CxUi.INFO, "اسحب الأيقونة ≡ لإعادة الترتيب، أو استخدم الأسهم. الترتيب الأول يظهر أولاً في التطبيق."));
        }
        headBox.addView(CgUi.space(this, 4));
        if (slides.isEmpty()) {
            listBox.addView(CgUi.empty(this, "لا توجد شرائح في الكاروسيل", "أضف شريحة من فيلم أو مسلسل موجود."));
            TextView add = CgUi.button(this, "+ إضافة شريحة", 0);
            add.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    CxNav.carouselAdd(CxCarouselActivity.this, "");
                }
            });
            listBox.addView(add, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
            return;
        }
        for (int i = 0; i < slides.size(); i++) listBox.addView(row(i));
    }

    private View row(final int index) {
        final JSONObject s = slides.get(index);
        final String id = idOf(s);
        final LinearLayout card = CgUi.vbox(this);
        card.setBackground(CgUi.bg(this, CgCfg.CARD, 16, CgCfg.STROKE));
        card.setPadding(CgUi.dp(this, 10), CgUi.dp(this, 10), CgUi.dp(this, 10), CgUi.dp(this, 10));

        LinearLayout top = CgUi.hbox(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView grip = CgUi.text(this, "≡", 26, CgCfg.MUTED, true);
        grip.setGravity(Gravity.CENTER);
        grip.setBackground(CgUi.bg(this, CgCfg.CARD2, 10, CgCfg.STROKE));
        grip.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN && slides.size() > 1) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    dragFrom = index;
                    ClipData cd = ClipData.newPlainText("slide", id);
                    View.DragShadowBuilder sb = new View.DragShadowBuilder(card);
                    card.setAlpha(0.45f);
                    if (Build.VERSION.SDK_INT >= 24) card.startDragAndDrop(cd, sb, card, 0);
                    else card.startDrag(cd, sb, card, 0);
                    return true;
                }
                return false;
            }
        });
        top.addView(grip, new LinearLayout.LayoutParams(CgUi.dp(this, 34), CgUi.dp(this, 54)));

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(CgUi.bg(this, CgCfg.CARD2, 8, 0));
        String img = CxJ.s(s, "landscape_poster");
        if (CxJ.isUrl(img)) CgImg.load(iv, img, 300);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(CgUi.dp(this, 98), CgUi.dp(this, 54));
        ilp.setMargins(CgUi.dp(this, 8), 0, CgUi.dp(this, 8), 0);
        top.addView(iv, ilp);

        LinearLayout col = CgUi.vbox(this);
        TextView title = CgUi.text(this, (index + 1) + ". " + CxJ.s(s, "title"), 14.5f, CgCfg.TEXT, true);
        title.setMaxLines(2);
        col.addView(title);
        LinearLayout badges = CgUi.hbox(this);
        String type = CxJ.s(s, "type");
        badges.addView(CgUi.badge(this, "series".equals(type) ? "مسلسل" : "فيلم", CgCfg.ACCENT));
        String status = CxJ.s(s, "status");
        int sc = "active".equals(status) ? CgCfg.GOOD : "scheduled".equals(status) ? CgCfg.WARN : CgCfg.GRAY;
        String st = "active".equals(status) ? "نشطة" : "scheduled".equals(status) ? "مجدولة" : "منتهية";
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        blp.setMargins(CgUi.dp(this, 6), 0, 0, 0);
        badges.addView(CgUi.badge(this, st, sc), blp);
        col.addView(badges, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 4, 0, 0));
        top.addView(col, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        card.addView(top);

        StringBuilder d = new StringBuilder();
        if (CxJ.s(s, "start_at").length() > 0) d.append("تبدأ: ").append(CgUi.localText(CxJ.s(s, "start_at")));
        if (CxJ.s(s, "end_at").length() > 0) {
            if (d.length() > 0) d.append("  •  ");
            d.append("تنتهي: ").append(CgUi.localText(CxJ.s(s, "end_at")));
        }
        if (d.length() > 0) card.addView(CgUi.muted(this, d.toString()), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));
        if (!s.optBoolean("content_exists", true)) {
            card.addView(CxUi.notice(this, CxUi.WARN, "المحتوى الأصلي لهذه الشريحة لم يعد موجوداً."));
        }

        LinearLayout actions = CgUi.hbox(this);
        TextView up = CgUi.smallButton(this, "▲", 1);
        up.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                move(index, index - 1);
            }
        });
        TextView down = CgUi.smallButton(this, "▼", 1);
        down.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                move(index, index + 1);
            }
        });
        CxUi.setEnabled(up, index > 0);
        CxUi.setEnabled(down, index < slides.size() - 1);
        TextView edit = CgUi.smallButton(this, "تعديل", 0);
        edit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (dirty) {
                    snack("احفظ الترتيب أولاً أو ألغِه قبل التعديل.", CxUi.WARN);
                    return;
                }
                Intent in = new Intent(CxCarouselActivity.this, CxCarouselEditActivity.class);
                in.putExtra("content_id", id);
                in.putExtra("edit", true);
                startActivity(in);
            }
        });
        TextView open = CgUi.smallButton(this, "فتح المحتوى", 1);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgData dd = CgData.cur();
                if (dd != null && dd.byId(id) != null) CxNav.openItem(CxCarouselActivity.this, id);
                else snack("المحتوى غير موجود.", CxUi.WARN);
            }
        });
        TextView rm = CgUi.smallButton(this, "إزالة", 2);
        rm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                remove(s);
            }
        });
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        a.setMargins(CgUi.dp(this, 4), 0, CgUi.dp(this, 4), 0);
        actions.addView(up, a);
        actions.addView(down, a);
        actions.addView(edit, a);
        actions.addView(open, a);
        actions.addView(rm, a);
        card.addView(actions, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));

        card.setOnDragListener(new View.OnDragListener() {
            @Override
            public boolean onDrag(View v, DragEvent e) {
                switch (e.getAction()) {
                    case DragEvent.ACTION_DRAG_STARTED:
                        return true;
                    case DragEvent.ACTION_DRAG_ENTERED:
                        if (index != dragFrom) v.setBackground(CgUi.bg(CxCarouselActivity.this, CgCfg.CARD2, 16, CgCfg.ACCENT));
                        return true;
                    case DragEvent.ACTION_DRAG_EXITED:
                        v.setBackground(CgUi.bg(CxCarouselActivity.this, CgCfg.CARD, 16, CgCfg.STROKE));
                        return true;
                    case DragEvent.ACTION_DRAG_LOCATION:
                        autoScroll(v, e);
                        return true;
                    case DragEvent.ACTION_DROP:
                        final int from = dragFrom;
                        dragFrom = -1;
                        ui().post(new Runnable() {
                            @Override
                            public void run() {
                                move(from, index);
                            }
                        });
                        return true;
                    case DragEvent.ACTION_DRAG_ENDED:
                        v.setAlpha(1f);
                        v.setBackground(CgUi.bg(CxCarouselActivity.this, CgCfg.CARD, 16, CgCfg.STROKE));
                        dragFrom = -1;
                        return true;
                    default:
                        return true;
                }
            }
        });
        card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        return card;
    }

    private void autoScroll(View v, DragEvent e) {
        if (scroller() == null) return;
        int[] rowLoc = new int[2];
        int[] scLoc = new int[2];
        v.getLocationOnScreen(rowLoc);
        scroller().getLocationOnScreen(scLoc);
        float y = rowLoc[1] + e.getY() - scLoc[1];
        int h = scroller().getHeight();
        int edge = Math.max(CgUi.dp(this, 56), h / 7);
        if (y < edge) scroller().scrollBy(0, -CgUi.dp(this, 14));
        else if (y > h - edge) scroller().scrollBy(0, CgUi.dp(this, 14));
    }

    private void move(int from, int to) {
        if (from < 0 || to < 0 || from >= slides.size() || to >= slides.size() || from == to) {
            render();
            return;
        }
        JSONObject x = slides.remove(from);
        slides.add(to, x);
        markDirty();
        render();
    }

    private void markDirty() {
        boolean changed = false;
        for (int i = 0; i < slides.size(); i++) {
            if (i >= originalOrder.size() || !idOf(slides.get(i)).equals(originalOrder.get(i))) {
                changed = true;
                break;
            }
        }
        dirty = changed;
        bottom.removeAllViews();
        if (!dirty) {
            setBottomVisible(false);
            return;
        }
        setBottomVisible(true);
        bottomButton("إلغاء", 1, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetOrder();
            }
        });
        bottomButton("حفظ الترتيب", 0, 2f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveOrder();
            }
        });
    }

    private void resetOrder() {
        ArrayList<JSONObject> copy = new ArrayList<JSONObject>(slides);
        slides.clear();
        for (int i = 0; i < originalOrder.size(); i++) {
            for (int k = 0; k < copy.size(); k++) {
                if (idOf(copy.get(k)).equals(originalOrder.get(i))) {
                    slides.add(copy.get(k));
                    break;
                }
            }
        }
        dirty = false;
        setBottomVisible(false);
        render();
    }

    private void saveOrder() {
        final JSONArray order = new JSONArray();
        for (int i = 0; i < slides.size(); i++) order.put(idOf(slides.get(i)));
        task("جارٍ حفظ الترتيب...", new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                return CxApi.post("/admin/carousel/reorder", CxJ.put(new JSONObject(), "order", order));
            }
        }, new Ok<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                dirty = false;
                setBottomVisible(false);
                showUndo("تم حفظ ترتيب الكاروسيل", r.optString("undo_id"), new Runnable() {
                    @Override
                    public void run() {
                        load();
                    }
                });
                load();
            }
        }, new Runnable() {
            @Override
            public void run() {
                saveOrder();
            }
        });
    }

    private void remove(final JSONObject s) {
        if (dirty) {
            snack("احفظ الترتيب أولاً أو ألغِه قبل الإزالة.", CxUi.WARN);
            return;
        }
        final String id = idOf(s);
        CgUi.confirm(this, "إزالة من الكاروسيل", "ستُزال «" + CxJ.s(s, "title") + "» من الكاروسيل فقط. لن يُحذف الفيلم أو المسلسل نفسه. يمكنك التراجع بعدها.",
                "إزالة", true, new Runnable() {
                    @Override
                    public void run() {
                        task("جارٍ الإزالة...", new Job<JSONObject>() {
                            @Override
                            public JSONObject run() throws Exception {
                                return CxApi.post("/admin/carousel/remove", CxJ.put(new JSONObject(), "content_id", id));
                            }
                        }, new Ok<JSONObject>() {
                            @Override
                            public void ok(JSONObject r) {
                                showUndo("تمت الإزالة من الكاروسيل", r.optString("undo_id"), new Runnable() {
                                    @Override
                                    public void run() {
                                        load();
                                    }
                                });
                                load();
                            }
                        }, new Runnable() {
                            @Override
                            public void run() {
                                remove(s);
                            }
                        });
                    }
                });
    }

    @Override
    public void onBackPressed() {
        if (dirty) {
            CgUi.confirm(this, "تغييرات غير محفوظة", "لم تحفظ الترتيب الجديد. هل تريد الخروج وتجاهله؟", "خروج", true, new Runnable() {
                @Override
                public void run() {
                    dirty = false;
                    finish();
                }
            });
            return;
        }
        super.onBackPressed();
    }
}
