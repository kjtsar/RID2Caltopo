package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;

import org.json.JSONObject;

final class TrackerConfigCompat {
    private TrackerConfigCompat() { }

    @NonNull
    static String readTrackerUrlPrefix(@NonNull JSONObject json) {
        String trackerUrlPfx = json.optString("tracker_url_pfx");
        if (trackerUrlPfx.isEmpty()) {
            trackerUrlPfx = json.optString("tracker_url_prefix");
        }
        return trackerUrlPfx;
    }
}
