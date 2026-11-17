package com.my.newproject;

import org.json.JSONObject;

/** Small shared item helpers for the Cx screens. */
final class CxOps2 {

    private CxOps2() {}

    static String videoOf(JSONObject it) {
        if (it == null) return "";
        String v = CxJ.s(it, "video");
        return v.length() > 0 ? v : CxJ.s(it, "link");
    }

    static String posterOf(JSONObject it) {
        if (it == null) return "";
        String p = CxJ.s(it, "poster");
        if (p.length() > 0) return p;
        p = CxJ.s(it, "series_poster");
        return p.length() > 0 ? p : CxJ.s(it, "backdrop");
    }
}
