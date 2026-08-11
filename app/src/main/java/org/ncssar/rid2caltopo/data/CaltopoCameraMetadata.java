package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Optional camera properties attached to a CalTopo LiveTrack position report. */
public final class CaltopoCameraMetadata {
    @NonNull public final String externalUrl;
    @Nullable public final String thumbnailUrl;

    public CaltopoCameraMetadata(
            @NonNull String externalUrl,
            @Nullable String thumbnailUrl) {
        this.externalUrl = externalUrl;
        this.thumbnailUrl = thumbnailUrl;
    }
}
