package com.Cigram.vid;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.View;

import org.json.JSONObject;

/**
 * CIGRAM TV EDITION: what a TV screen offers to the shared card / row / grid builders
 * ({@link CigramTvCards}), so the home, the grids, the search and the details page all draw the
 * same cards without each one carrying its own copy of the code.
 */
interface CigramTvHost {
    /** The Activity used as the Context for new views. */
    Activity activity();

    /** dp to px, in the TV-scaled configuration. */
    int dp(float value);

    /** The app font. */
    Typeface font();

    /** A card was chosen with the remote: open its details page. */
    void openDetails(JSONObject item, View card);
}
