package com.Cigram.vid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Extra sections of "محتواي" (the existing page keeps "أكمل المشاهدة" and "المفضلة"):
 *   المشاهدة لاحقاً  |  المسلسلات المشترك بها  |  الإعجابات  |  سجل المشاهدة
 * All of them read the existing stores / CigramUserData; nothing is duplicated.
 */
public final class CigramMyContent {

    private static final String TAG = "cigram_v23_mycontent";

    private CigramMyContent() { }

    public static void bind(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            int id = activity.getResources().getIdentifier(
                    "linear_my_content_body", "id", activity.getPackageName());
            View body = id == 0 ? null : activity.findViewById(id);
            if (!(body instanceof LinearLayout)) return;
            LinearLayout host = (LinearLayout) body;

            View old = host.findViewWithTag(TAG);
            if (old != null && old.getParent() instanceof ViewGroup) {
                ((ViewGroup) old.getParent()).removeView(old);
            }

            LinearLayout block = new LinearLayout(activity);
            block.setTag(TAG);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            block.setPadding(0, 0, 0, CigramStage1InitProvider.cigramDp(activity, 24));

            add(activity, block, "المشاهدة لاحقاً",
                    fromStore(CigramStage1InitProvider.cigramListRead(activity, "watch_later")));
            add(activity, block, "المسلسلات المشترك بها",
                    fromStore(CigramStage1InitProvider.cigramListRead(activity, "subscriptions")));
            add(activity, block, "الإعجابات", CigramStage1InitProvider.cigramLikedItems(activity));

            ArrayList<JSONObject> history = new ArrayList<JSONObject>();
            ArrayList<JSONObject> rows = CigramUserData.historyGrouped(activity);
            HashSet<String> seen = new HashSet<String>();
            for (int i = 0; i < rows.size() && history.size() < 400; i++) {
                JSONObject card = resolveHistory(rows.get(i));
                String key = CigramContent.itemKey(card);
                if (key.length() > 0 && !seen.add(key)) continue;
                history.add(card);
            }
            // the viewing history is permanent: it is never cleared by the user (v2.5)
            add(activity, block, "سجل المشاهدة", history);

            if (block.getChildCount() == 0) return;
            host.addView(block, new LinearLayout.LayoutParams(-1, -2));
        } catch (Throwable error) {
            android.util.Log.w("CigramMyContent", "bind failed", error);
        }
    }

    private static boolean add(Activity activity, LinearLayout block, String title,
                               java.util.List<JSONObject> items) {
        if (items == null || items.isEmpty()) return false;
        View section = CigramStage1InitProvider.cigramSectionView(activity, title, items);
        if (section == null) return false;
        block.addView(section, new LinearLayout.LayoutParams(-1, -2));
        return true;
    }

    /** Watch-later / subscription entries -> catalog item when it exists, otherwise the stored stub. */
    private static ArrayList<JSONObject> fromStore(JSONArray stored) {
        ArrayList<JSONObject> out = new ArrayList<JSONObject>();
        HashSet<String> seen = new HashSet<String>();
        for (int i = 0; i < stored.length() && out.size() < 60; i++) {
            JSONObject o = stored.optJSONObject(i);
            if (o == null) continue;
            JSONObject item = o;
            boolean series = o.has("series_key") || CigramContent.isSeries(o);
            try {
                if (o.has("series_key")) {      // subscription stub
                    JSONObject stub = new JSONObject();
                    stub.put("id", CigramContent.text(o, "series_id"));
                    stub.put("series_id", CigramContent.text(o, "series_id"));
                    stub.put("title", CigramContent.text(o, "title"));
                    stub.put("poster", CigramContent.text(o, "poster"));
                    stub.put("type", "series");
                    item = stub;
                }
            } catch (Throwable ignored) { }
            JSONObject cat = CigramContent.find(series ? "series" : "movie",
                    CigramContent.text(item, series ? "series_id" : "id", "id"),
                    CigramContent.title(item));
            if (cat != null) item = cat;
            String key = CigramContent.itemKey(item);
            if (key.length() > 0 && !seen.add(key)) continue;
            out.add(item);
        }
        return out;
    }

    private static JSONObject resolveHistory(JSONObject entry) {
        boolean episode = "episode".equals(entry.optString("type"));
        String title = episode ? entry.optString("series_title") : entry.optString("title");
        JSONObject cat = CigramContent.find(episode ? "series" : "movie",
                episode ? entry.optString("series_id") : entry.optString("id"), title);
        if (cat != null) return cat;
        JSONObject stub = new JSONObject();
        try {
            stub.put("id", episode ? entry.optString("series_id") : entry.optString("id"));
            stub.put("series_id", episode ? entry.optString("series_id") : "");
            stub.put("title", title);
            stub.put("poster", entry.optString("poster"));
            stub.put("type", episode ? "series" : "movie");
            stub.put("category", entry.optString("category"));
            stub.put("year", entry.optString("year"));
        } catch (Throwable ignored) { }
        return stub;
    }
}
