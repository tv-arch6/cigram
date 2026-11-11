package com.Cigram.vid;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «المزيد»: every title of one section, as a ranked list of landscape cards (1, 2, 3 ...).
 *
 * INSTANT: the page is built from {@link CigramSectionStore}, which Home filled with the very items it shows. The first frame already
 * has the whole list: no network request, no spinner, no skeleton, no catalogue parse. The only thing that can happen afterwards is a
 * SILENT top-up for a very long section (Home keeps a trimmed copy of those): the missing titles are appended at the END of the list,
 * the visible cards are never replaced, reordered or blinked.
 *
 * Layout (physical order, the app ignores android:supportsRtl): [ info ][ poster ][ rank ], i.e. Arabic reading order from the right:
 * rank, poster, text. The #1 numeral is a large translucent watermark that spills over the poster's inner edge; 2+ are outlined numerals
 * in a fixed-width column (see {@link CigramRankView}).
 *
 * Performance: a RecyclerView (virtualised, view recycling, stable ids), one view hierarchy per card built once, no per-bind inflation,
 * posters through the shared single-flight poster loader (memory + disk cache, skeleton placeholder), entrance animation only for
 * the first screenful and only through property animators (never blocks a scroll).
 */
public class SectionActivity extends AppCompatActivity {

    static final String EXTRA_KEY = "section_key";
    /** A section Home trimmed to this many titles may have more in the catalogue: the page tops it up silently. */
    private static final int TRIMMED_AT = 80;
    private static final int POSTER_W_DP = 92;
    private static final int POSTER_H_DP = 138;
    private static final int CARD_MIN_H_DP = 150;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private RecyclerView list;
    private RowAdapter adapter;
    private TextView emptyView;
    private boolean closed;

    private String sectionId = "";
    private String sectionTitle = "";
    private String sectionType = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        readTarget();

        boolean light = CigramTheme.isLight(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CigramTheme.page(this));
        root.addView(CigramTopBar.create(this, sectionTitle.length() == 0 ? "القسم" : sectionTitle),
                new LinearLayout.LayoutParams(-1, CigramUI.dp(this, CigramTopBar.HEIGHT_DP)));

        FrameLayout body = new FrameLayout(this);
        list = new RecyclerView(this);
        list.setId(View.generateViewId());                      // an id lets the layout manager save / restore the scroll position
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setHasFixedSize(true);
        list.setItemViewCacheSize(8);
        list.setClipToPadding(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setVerticalScrollBarEnabled(false);
        list.setItemAnimator(null);                             // appended rows must not animate over the visible ones
        list.setPadding(0, CigramUI.dp(this, 6), 0, CigramUI.dp(this, 24));
        adapter = new RowAdapter(this, light);
        list.setAdapter(adapter);
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));

        emptyView = new TextView(this);
        emptyView.setText("لا توجد عناصر في هذا القسم حالياً");
        emptyView.setTextColor(light ? 0xFF5F6B73 : 0xFF8E8E94);
        emptyView.setTextSize(15f);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTypeface(CigramUI.font(this));
        emptyView.setVisibility(View.GONE);
        body.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        // the whole list, synchronously, before the first frame
        CigramSectionStore.Page page = resolvePage();
        if (page != null) adapter.submit(page.items);
        emptyView.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);

        if (page == null || (!page.complete && page.items.size() >= TRIMMED_AT) || page.items.isEmpty()) {
            topUpSilently(page);
        }
    }

    // ------------------------------------------------------------------ what to show

    private void readTarget() {
        Intent intent = getIntent();
        sectionId = clean(intent == null ? null : intent.getStringExtra("section_id"));
        sectionTitle = clean(intent == null ? null : intent.getStringExtra("section_title"));
        if (sectionTitle.length() == 0) sectionTitle = clean(intent == null ? null : intent.getStringExtra("section_name"));
        sectionType = clean(intent == null ? null : intent.getStringExtra("section_type")).toLowerCase(Locale.ROOT);

        if (sectionId.length() == 0) {
            String n = CigramStage1InitProvider.cigramNormalize(sectionTitle);
            if (n.equals(CigramStage1InitProvider.cigramNormalize(CigramStage1InitProvider.LATEST_MOVIES_NAME))) {
                sectionId = CigramStage1InitProvider.LATEST_MOVIES_ID;
            } else if (n.equals(CigramStage1InitProvider.cigramNormalize(CigramStage1InitProvider.FEATURED_SERIES_NAME))) {
                sectionId = CigramStage1InitProvider.FEATURED_SERIES_ID;
            } else if (sectionTitle.length() == 0 && "movie".equals(sectionType)) {
                sectionId = CigramStage1InitProvider.LATEST_MOVIES_ID;
            } else if (sectionTitle.length() == 0 && "series".equals(sectionType)) {
                sectionId = CigramStage1InitProvider.FEATURED_SERIES_ID;
            }
        }
        if ("latest_series".equalsIgnoreCase(sectionId)) sectionId = CigramStage1InitProvider.FEATURED_SERIES_ID;
        if (sectionTitle.length() == 0) {
            if (CigramStage1InitProvider.LATEST_MOVIES_ID.equalsIgnoreCase(sectionId)) sectionTitle = CigramStage1InitProvider.LATEST_MOVIES_NAME;
            else if (CigramStage1InitProvider.FEATURED_SERIES_ID.equalsIgnoreCase(sectionId)) sectionTitle = CigramStage1InitProvider.FEATURED_SERIES_NAME;
            else if ("__related__".equals(sectionId)) sectionTitle = "ذات صلة";
        }
    }

    /** Memory first (what Home drew); the saved sections of the last session when this process has not drawn Home yet. Never the network. */
    private CigramSectionStore.Page resolvePage() {
        String key = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_KEY);
        CigramSectionStore.Page page = CigramSectionStore.getByKey(key);
        if (page == null) page = CigramSectionStore.get(sectionId, sectionTitle);
        if (page == null && !"__related__".equals(sectionId)) {
            List<JSONObject> saved = CigramStage1InitProvider.snapshotSectionItems(this, sectionId, sectionTitle);
            if (saved != null && !saved.isEmpty()) page = new CigramSectionStore.Page(sectionId, sectionTitle, sectionType, saved, false);
        }
        return page;
    }

    /**
     * Long sections: Home keeps a trimmed copy, the catalogue has more. Read the rest in the background and APPEND what is new; the cards
     * on screen stay exactly as they are. Also the way back to a list for a restored process that had nothing saved.
     */
    private void topUpSilently(final CigramSectionStore.Page shown) {
        if ("__related__".equals(sectionId)) return;
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                final List<JSONObject> all = CigramStage1InitProvider.fullSectionItems(app, sectionId, sectionTitle);
                if (all.isEmpty()) return;
                final List<JSONObject> added = shown == null ? all : CigramSectionStore.topUp(shown, all);
                if (added.isEmpty()) return;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (closed || isFinishing() || isDestroyed()) return;
                        adapter.append(added);
                        emptyView.setVisibility(View.GONE);
                    }
                });
            }
        }, "cigram-section-topup").start();
    }

    @Override
    protected void onDestroy() {
        closed = true;
        super.onDestroy();
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }

    // ------------------------------------------------------------------ list

    private static final class Row {
        final JSONObject item;
        final long stableId;
        final String title;
        final String meta;
        final String genres;
        final String synopsis;
        final List<Badge> badges;
        Row(JSONObject item, long stableId, String title, String meta, String genres, String synopsis, List<Badge> badges) {
            this.item = item; this.stableId = stableId; this.title = title; this.meta = meta;
            this.genres = genres; this.synopsis = synopsis; this.badges = badges;
        }
    }

    private static final class Badge {
        final int color;
        final String label;
        Badge(int color, String label) { this.color = color; this.label = label; }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final FrameLayout card;
        final ImageView poster;
        final CigramRankView rank;
        final TextView title;
        final TextView meta;
        final TextView genres;
        final TextView synopsis;
        final LinearLayout badges;
        Holder(FrameLayout card, ImageView poster, CigramRankView rank, TextView title, TextView meta, TextView genres,
               TextView synopsis, LinearLayout badges) {
            super(card);
            this.card = card; this.poster = poster; this.rank = rank; this.title = title; this.meta = meta;
            this.genres = genres; this.synopsis = synopsis; this.badges = badges;
        }
    }

    private static final class RowAdapter extends RecyclerView.Adapter<Holder> {
        private final SectionActivity host;
        private final boolean light;
        private final float d;
        private final ArrayList<Row> rows = new ArrayList<Row>();
        private final java.util.HashSet<String> identities = new java.util.HashSet<String>();
        /** Cards bound before the first scroll animate in; after the user scrolls, rows just appear (no animation to wait for). */
        private boolean entrance = true;
        private int animated;
        private final HashMap<String, int[]> episodeStats = new HashMap<String, int[]>();

        RowAdapter(SectionActivity host, boolean light) {
            this.host = host;
            this.light = light;
            this.d = host.getResources().getDisplayMetrics().density;
            setHasStableIds(true);
        }

        void submit(List<JSONObject> items) {
            rows.clear();
            identities.clear();
            append(items, false);
            notifyDataSetChanged();
        }

        /** Adds titles at the END only. The rows already shown keep their place, their rank and their views. */
        void append(List<JSONObject> items) {
            int from = rows.size();
            append(items, true);
            if (rows.size() > from) notifyItemRangeInserted(from, rows.size() - from);
        }

        private void append(List<JSONObject> items, boolean silent) {
            if (items == null) return;
            for (JSONObject item : items) {
                if (item == null) continue;
                String identity = CigramSectionStore.identity(item);
                if (identity.length() > 2 && !identities.add(identity)) continue;     // one card per title
                try {
                    rows.add(prepare(item, identity));
                } catch (Throwable error) {
                    android.util.Log.w("CigramSection", "row skipped", error);        // one bad record never costs the page
                }
            }
        }

        @Override public long getItemId(int position) { return rows.get(position).stableId; }
        @Override public int getItemCount() { return rows.size(); }

        @Override
        public void onViewRecycled(Holder h) {
            h.card.animate().cancel();
            h.card.setAlpha(1f);
            h.card.setTranslationY(0f);
            h.card.setScaleX(1f);
            h.card.setScaleY(1f);
        }

        // ---- row data (strings only; no views, cheap)

        private Row prepare(JSONObject item, String identity) {
            String title = CigramStage1InitProvider.itemText(item, "series_title", "title", "title_ar", "title_en", "original_title", "name");
            if (title.length() == 0) title = "بدون عنوان";

            ArrayList<String> meta = new ArrayList<String>();
            int year = CigramContent.year(item);
            if (year > 0) meta.add(String.valueOf(year));
            double rating = CigramContent.rating(item);
            if (rating > 0d) meta.add("★ " + String.format(Locale.US, "%.1f", rating));
            String length = lengthText(item);
            if (length.length() > 0) meta.add(length);

            List<String> genreList = CigramContent.genreLabels(item);
            StringBuilder genres = new StringBuilder();
            for (int i = 0; i < genreList.size() && i < 3; i++) {
                if (i > 0) genres.append(" • ");
                genres.append(genreList.get(i));
            }

            ArrayList<Badge> badges = new ArrayList<Badge>();
            boolean series = CigramContent.isSeries(item);
            badges.add(new Badge(series ? 0xFF26A5B5 : 0xFF6C63FF, series ? "مسلسل" : "فيلم"));
            String quality = CigramStage1InitProvider.itemText(item, "quality", "resolution", "video_quality");
            if (quality.length() > 0 && quality.length() <= 8) badges.add(new Badge(0xFF3A3A42, quality));
            try {
                for (String entry : CigramHome.cardBadges(item, 2)) {                      // admin badges, «جديد», «حلقة جديدة»
                    String[] parts = entry.split("\\|", 2);
                    if (parts.length == 2) badges.add(new Badge(Integer.parseInt(parts[0]), parts[1]));
                }
            } catch (Throwable ignored) { }

            String synopsis = CigramStage1InitProvider.itemText(item, "description", "overview", "plot", "synopsis").replaceAll("\\s+", " ").trim();
            if (synopsis.length() > 240) synopsis = cut(synopsis, 240);

            long id = identity.hashCode() * 31L + title.hashCode();
            return new Row(item, id, title, join(meta, "  •  "), genres.toString(), synopsis, badges);
        }

        /** "1 س 52 د" style text is kept as the admin wrote it; a bare number is minutes; a series shows its seasons and episodes. */
        private String lengthText(JSONObject item) {
            if (CigramContent.isSeries(item)) {
                int[] st = stats(item);
                if (st[1] == 0) return "";
                String eps = st[1] + " حلقة";
                return st[0] > 1 ? st[0] + " مواسم  •  " + eps : eps;
            }
            String raw = CigramStage1InitProvider.itemText(item, "duration", "runtime");
            if (raw.length() == 0) return "";
            boolean digits = true;
            for (int i = 0; i < raw.length(); i++) if (!Character.isDigit(raw.charAt(i))) { digits = false; break; }
            return digits ? raw + " د" : raw;
        }

        private final Pattern SEASON = Pattern.compile("\"season(?:_number)?\"\\s*:\\s*\"?(\\d+)");
        private final Pattern EPISODE = Pattern.compile("\"(?:episode|episode_number|ep)\"\\s*:");

        /** {seasons, episodes} of a series, from its episode list, without parsing the (possibly large) JSON. Cached per title. */
        private int[] stats(JSONObject item) {
            String key = CigramSectionStore.identity(item);
            int[] hit = episodeStats.get(key);
            if (hit != null) return hit;
            int[] out = new int[2];
            Object raw = item.opt("series_episodes_json");
            if (raw instanceof org.json.JSONArray) {
                out[1] = ((org.json.JSONArray) raw).length();
                java.util.HashSet<String> seasons = new java.util.HashSet<String>();
                for (int i = 0; i < out[1]; i++) {
                    JSONObject e = ((org.json.JSONArray) raw).optJSONObject(i);
                    if (e != null) seasons.add(CigramContent.text(e, "season", "season_number"));
                }
                out[0] = Math.max(1, seasons.size());
            } else if (raw instanceof String && ((String) raw).length() > 2) {
                String text = (String) raw;
                Matcher m = SEASON.matcher(text);
                java.util.HashSet<String> seasons = new java.util.HashSet<String>();
                while (m.find()) seasons.add(m.group(1));
                Matcher e = EPISODE.matcher(text);
                int n = 0;
                while (e.find()) n++;
                out[1] = n;
                out[0] = Math.max(1, seasons.size());
            }
            episodeStats.put(key, out);
            return out;
        }

        private static String cut(String text, int max) {
            if (text.length() <= max) return text;
            int at = text.lastIndexOf(' ', max);
            return text.substring(0, at > max / 2 ? at : max).trim() + "…";
        }

        private static String join(List<String> parts, String glue) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) b.append(glue);
                b.append(parts.get(i));
            }
            return b.toString();
        }

        // ---- views

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            final SectionActivity a = host;
            int ink = light ? 0xFF111418 : 0xFFFFFFFF;
            int soft = light ? 0xFF5F6B73 : 0xFF8E8E94;
            int mid = light ? 0xFF3C474E : 0xFFAAB4B9;

            final FrameLayout card = new FrameLayout(a);
            RecyclerView.LayoutParams clp = new RecyclerView.LayoutParams(-1, -2);
            clp.setMargins(dp(12), dp(6), dp(12), dp(6));
            card.setLayoutParams(clp);
            card.setClipChildren(false);                              // the #1 watermark paints beyond its own box
            card.setClipToPadding(false);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(light ? 0xFFFFFFFF : 0xFF151518);
            bg.setCornerRadius(dp(18));
            bg.setStroke(dp(1), light ? 0xFFDADFE3 : 0xFF26262B);
            card.setBackground(bg);
            card.setMinimumHeight(dp(CARD_MIN_H_DP));
            card.setClickable(true);
            card.setFocusable(true);
            card.setForeground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(light ? 0x22000000 : 0x33FFFFFF), null, roundMask()));

            // physical, left → right: [ info ][ poster ][ rank column ]
            LinearLayout content = new LinearLayout(a);
            content.setOrientation(LinearLayout.HORIZONTAL);
            content.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            content.setGravity(Gravity.CENTER_VERTICAL);
            content.setPadding(dp(12), dp(8), 0, dp(8));
            card.addView(content, new FrameLayout.LayoutParams(-1, -2));

            LinearLayout info = new LinearLayout(a);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setGravity(Gravity.RIGHT);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, -2, 1f);
            ilp.rightMargin = dp(12);
            content.addView(info, ilp);

            TextView title = text(a, 16.5f, ink, true);
            title.setMaxLines(2);
            info.addView(title, new LinearLayout.LayoutParams(-1, -2));

            TextView meta = text(a, 12.5f, mid, false);
            meta.setSingleLine(true);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
            mlp.topMargin = dp(5);
            info.addView(meta, mlp);

            TextView genres = text(a, 12f, soft, false);
            genres.setSingleLine(true);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
            glp.topMargin = dp(3);
            info.addView(genres, glp);

            TextView synopsis = text(a, 12f, soft, false);
            synopsis.setMaxLines(3);
            synopsis.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
            slp.topMargin = dp(6);
            info.addView(synopsis, slp);

            LinearLayout badges = new LinearLayout(a);
            badges.setOrientation(LinearLayout.HORIZONTAL);
            badges.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            badges.setGravity(Gravity.RIGHT);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.topMargin = dp(8);
            info.addView(badges, blp);

            ImageView poster = new ImageView(a);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable pbg = new GradientDrawable();
            pbg.setColor(light ? 0xFFE3E7EA : 0xFF1B1B1F);
            pbg.setCornerRadius(dp(12));
            poster.setBackground(pbg);
            poster.setClipToOutline(true);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(POSTER_W_DP), dp(POSTER_H_DP));
            content.addView(poster, plp);

            View column = new View(a);                                 // the fixed rank column: every card reserves the same width
            content.addView(column, new LinearLayout.LayoutParams(dp(CigramRankView.COLUMN_DP), 1));

            // the numeral is drawn ABOVE the poster, in a box that reaches over the poster's inner edge (the #1 watermark)
            CigramRankView rank = new CigramRankView(a);
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(
                    dp(CigramRankView.COLUMN_DP + CigramRankView.WATERMARK_OVERLAP_DP), -1, Gravity.RIGHT);
            card.addView(rank, rlp);

            attachPressFeedback(card);
            final Holder holder = new Holder(card, poster, rank, title, meta, genres, synopsis, badges);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    int at = holder.getBindingAdapterPosition();
                    if (at == RecyclerView.NO_POSITION || at >= rows.size()) return;
                    CigramStage1InitProvider.openTitleDetails(host, rows.get(at).item, holder.poster);
                }
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            final Row row = rows.get(position);
            final int rankNumber = position + 1;
            h.title.setText(CigramPageKit.rtl(row.title));
            setOrGone(h.meta, row.meta);
            setOrGone(h.genres, row.genres);
            setOrGone(h.synopsis, row.synopsis);
            h.rank.setRank(rankNumber);
            h.card.setContentDescription("المركز " + CigramRankView.format(rankNumber) + "، " + row.title);

            h.badges.removeAllViews();
            h.badges.setVisibility(row.badges.isEmpty() ? View.GONE : View.VISIBLE);
            for (Badge b : row.badges) {
                TextView pill = CigramHome.badgeView(host, b.label, b.color, 10f);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pill.getLayoutParams();
                lp.bottomMargin = 0;
                lp.leftMargin = dp(5);
                pill.setLayoutParams(lp);
                h.badges.addView(pill);
            }

            CigramStage1InitProvider.loadPosterInto(host, row.item, h.poster);
            animateIn(h.card, position);
        }

        /** The first screenful fades and slides in, one card after another. Property animators: nothing here waits for, or delays, a scroll. */
        private void animateIn(View card, int position) {
            if (!entrance || position > 9) { card.setAlpha(1f); card.setTranslationY(0f); return; }
            if (animated >= 10) { entrance = false; return; }
            animated++;
            card.setAlpha(0f);
            card.setTranslationY(dp(18));
            card.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(position, 8) * 40L).setDuration(260L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }

        @Override
        public void onAttachedToRecyclerView(RecyclerView recyclerView) {
            recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrollStateChanged(RecyclerView rv, int newState) {
                    if (newState != RecyclerView.SCROLL_STATE_IDLE) entrance = false;   // the user is scrolling: no entrance from now on
                }
            });
        }

        // ---- small helpers

        private int dp(float v) { return Math.round(v * d); }

        private TextView text(SectionActivity a, float sp, int color, boolean bold) {
            TextView t = new TextView(a);
            t.setTextColor(color);
            t.setTextSize(sp);
            t.setTypeface(CigramUI.font(a), bold ? Typeface.BOLD : Typeface.NORMAL);
            t.setGravity(Gravity.RIGHT);
            t.setTextDirection(View.TEXT_DIRECTION_RTL);
            t.setEllipsize(TextUtils.TruncateAt.END);
            return t;
        }

        private void setOrGone(TextView t, String value) {
            boolean has = value != null && value.length() > 0;
            t.setVisibility(has ? View.VISIBLE : View.GONE);
            if (has) t.setText(CigramPageKit.rtl(value));
        }

        private android.graphics.drawable.Drawable roundMask() {
            GradientDrawable m = new GradientDrawable();
            m.setColor(0xFFFFFFFF);
            m.setCornerRadius(dp(18));
            return m;
        }

        /** A light squeeze while a card is pressed; it never consumes the touch, so scrolling and the click behave as normal. */
        private void attachPressFeedback(final View card) {
            card.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            v.animate().scaleX(0.975f).scaleY(0.975f).setDuration(90L).start();
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            v.animate().scaleX(1f).scaleY(1f).setDuration(140L).start();
                            break;
                        default:
                            break;
                    }
                    return false;
                }
            });
        }
    }
}
