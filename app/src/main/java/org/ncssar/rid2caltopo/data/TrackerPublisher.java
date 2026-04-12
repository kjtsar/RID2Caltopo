package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;

/**
 * Small publishing seam for tracker uploads.
 *
 * Production can continue using the existing CaltopoClient tracker path for
 * now; tests can inject fake publishers to validate routing.
 */
public interface TrackerPublisher {
    int publishGeoJson(@NonNull String geoJsonString);
}
