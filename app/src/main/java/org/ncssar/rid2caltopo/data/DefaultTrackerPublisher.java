package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;

/**
 * Default production tracker publisher.
 *
 * This preserves the existing tracker upload path while allowing runtimes and
 * tests to depend on a small publishing seam.
 */
public final class DefaultTrackerPublisher implements TrackerPublisher {
    private static final DefaultTrackerPublisher INSTANCE = new DefaultTrackerPublisher();

    private DefaultTrackerPublisher() { }

    @NonNull
    public static DefaultTrackerPublisher getInstance() {
        return INSTANCE;
    }

    @Override
    public int publishGeoJson(@NonNull String geoJsonString) {
        return CaltopoClient.BgPublishGeoJsonStats(geoJsonString);
    }
}
