package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;

interface TrackerCoordinationTransportFactory {
    @NonNull TrackerCoordinationTransport create();
}
