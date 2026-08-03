package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;

/** Browser SDP offer delivered only after the pilot approves a stream. */
public final class VideoMediaOffer {
    @NonNull public final String requestId;
    @NonNull public final String streamSessionId;
    @NonNull public final String sdp;
    @Nullable public final JSONArray iceServers;

    public VideoMediaOffer(
            @NonNull String requestId,
            @NonNull String streamSessionId,
            @NonNull String sdp,
            @Nullable JSONArray iceServers) {
        this.requestId = requestId;
        this.streamSessionId = streamSessionId;
        this.sdp = sdp;
        this.iceServers = iceServers;
    }
}
