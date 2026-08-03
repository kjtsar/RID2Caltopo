package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;

public final class ManagedVideoStreamAdvertisement {
    @NonNull public final String sessionId;
    @NonNull public final String droneDesignator;
    public final int sourceWidth;
    public final int sourceHeight;
    public final double sourceFps;
    public final long sourceBitrateBps;
    @NonNull public final String sourceCodec;

    public ManagedVideoStreamAdvertisement(
            @NonNull String sessionId,
            @NonNull String droneDesignator,
            int sourceWidth,
            int sourceHeight,
            double sourceFps,
            long sourceBitrateBps,
            @NonNull String sourceCodec) {
        this.sessionId = sessionId;
        this.droneDesignator = droneDesignator;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.sourceFps = sourceFps;
        this.sourceBitrateBps = sourceBitrateBps;
        this.sourceCodec = sourceCodec;
    }
}
