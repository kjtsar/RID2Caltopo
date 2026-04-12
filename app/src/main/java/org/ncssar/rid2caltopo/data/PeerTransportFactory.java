package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;

public interface PeerTransportFactory {
    @NonNull
    PeerTransport create(@NonNull String brokerUri, @NonNull String clientId);
}
