package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test double for tracker uploads.
 */
public final class FakeTrackerPublisher implements TrackerPublisher {

    public static final class Publication {
        @NonNull public final String payload;

        Publication(@NonNull String payload) {
            this.payload = payload;
        }
    }

    @NonNull private final String runtimeLabel;
    @NonNull private final List<Publication> publications =
            Collections.synchronizedList(new ArrayList<>());

    public FakeTrackerPublisher(@NonNull String runtimeLabel) {
        this.runtimeLabel = runtimeLabel;
    }

    @Override
    public int publishGeoJson(@NonNull String geoJsonString) {
        publications.add(new Publication(geoJsonString));
        return 200;
    }

    public int countPublications() {
        return publications.size();
    }

    @NonNull
    public List<Publication> snapshotPublications() {
        synchronized (publications) {
            return new ArrayList<>(publications);
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "FakeTrackerPublisher(" + runtimeLabel + ")";
    }
}
