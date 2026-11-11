package com.Cigram.vid;

import android.app.Activity;
import android.os.Bundle;

/**
 * 18.2: the old «أعلن هنا» page (packages, prices, policies and the order form) was removed from the app.
 *
 * The class stays registered so every existing link (account row, TV tile, notifications, old shortcuts) keeps
 * working: it opens Cigram Private Support (الدعم الخاص), the private conversation with the Cigram developer, and closes.
 * The advertising engine itself (AdMob, in-house campaigns, statistics, «حملاتي» for existing advertisers) is untouched.
 */
public class AdvertiseActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CigramSupport.open(this);
        finish();
        overridePendingTransition(0, 0);
    }
}
