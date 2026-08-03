package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;

public final class VideoStreamViewRequest {
    @NonNull public final String requestId;
    @NonNull public final String requesterEmail;
    @NonNull public final String streamSessionId;
    @NonNull public final String incidentName;
    @NonNull public final String droneDesignator;
    public final int sourceWidth;
    public final int sourceHeight;
    public final double sourceFps;
    public final long sourceBitrateBps;
    @NonNull public final String sourceCodec;
    @NonNull public final String expiresAt;
    public final boolean consentRequired;

    public VideoStreamViewRequest(
            @NonNull String requestId,
            @NonNull String requesterEmail,
            @NonNull String streamSessionId,
            @NonNull String incidentName,
            @NonNull String droneDesignator,
            int sourceWidth,
            int sourceHeight,
            double sourceFps,
            long sourceBitrateBps,
            @NonNull String sourceCodec,
            @NonNull String expiresAt,
            boolean consentRequired) {
        this.requestId = requestId;
        this.requesterEmail = requesterEmail;
        this.streamSessionId = streamSessionId;
        this.incidentName = incidentName;
        this.droneDesignator = droneDesignator;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.sourceFps = sourceFps;
        this.sourceBitrateBps = sourceBitrateBps;
        this.sourceCodec = sourceCodec;
        this.expiresAt = expiresAt;
        this.consentRequired = consentRequired;
    }
}
