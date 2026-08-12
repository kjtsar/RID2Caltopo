package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;

/** Browser SDP offer delivered only after the pilot approves a stream. */
public final class VideoMediaOffer {
    @NonNull public final String requestId;
    @NonNull public final String streamSessionId;
    @NonNull public final String requesterEmail;
    @NonNull public final String routeKind;
    public final int selectedWidth;
    public final int selectedHeight;
    public final double selectedFps;
    public final long selectedBitrateBps;
    @NonNull public final String sdp;
    @Nullable public final JSONArray iceServers;

    public VideoMediaOffer(
            @NonNull String requestId,
            @NonNull String streamSessionId,
            @NonNull String requesterEmail,
            @NonNull String routeKind,
            int selectedWidth,
            int selectedHeight,
            double selectedFps,
            long selectedBitrateBps,
            @NonNull String sdp,
            @Nullable JSONArray iceServers) {
        this.requestId = requestId;
        this.streamSessionId = streamSessionId;
        this.requesterEmail = requesterEmail;
        this.routeKind = routeKind;
        this.selectedWidth = selectedWidth;
        this.selectedHeight = selectedHeight;
        this.selectedFps = selectedFps;
        this.selectedBitrateBps = selectedBitrateBps;
        this.sdp = sdp;
        this.iceServers = iceServers;
    }
}
