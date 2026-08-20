package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Optional camera properties attached to a CalTopo LiveTrack position report. */
public final class CaltopoCameraMetadata {
    @NonNull public final String externalUrl;
    @Nullable public final String thumbnailUrl;
    @Nullable public final Double azimuthDegrees;
    @Nullable public final Double tiltDegrees;
    @Nullable public final Double horizontalFovDegrees;
    @Nullable public final Double verticalFovDegrees;

    public CaltopoCameraMetadata(
            @NonNull String externalUrl,
            @Nullable String thumbnailUrl) {
        this(externalUrl, thumbnailUrl, null, null, null, null);
    }

    public CaltopoCameraMetadata(
            @NonNull String externalUrl,
            @Nullable String thumbnailUrl,
            @Nullable Double azimuthDegrees,
            @Nullable Double tiltDegrees,
            @Nullable Double horizontalFovDegrees,
            @Nullable Double verticalFovDegrees) {
        this.externalUrl = externalUrl;
        this.thumbnailUrl = thumbnailUrl;
        this.azimuthDegrees = azimuthDegrees;
        this.tiltDegrees = tiltDegrees;
        this.horizontalFovDegrees = horizontalFovDegrees;
        this.verticalFovDegrees = verticalFovDegrees;
    }
}
