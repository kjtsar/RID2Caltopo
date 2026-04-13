package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for runtime-owned peer coordination.
 *
 * This keeps tests off the real MQTT manager while still letting them model
 * started/stopped state, peer-list updates, ownership, and inbound callbacks.
 */
public final class FakePeerCoordinator implements PeerCoordinator {

    public static final class Event {
        @NonNull public final String kind;
        @NonNull public final String summary;

        Event(@NonNull String kind, @NonNull String summary) {
            this.kind = kind;
            this.summary = summary;
        }
    }

    @NonNull private final String runtimeLabel;
    @NonNull private final List<Event> events = Collections.synchronizedList(new ArrayList<>());
    @NonNull private final List<R2CMqttManager.PeerState> peers = Collections.synchronizedList(new ArrayList<>());
    @NonNull private final Set<String> locallyOwnedRemoteIds = ConcurrentHashMap.newKeySet();

    @Nullable private volatile R2CMqttManager.PeerListChangedListener peerListChangedListener;
    @Nullable private volatile String startedMapId;
    @Nullable private volatile String startedGuid;
    @Nullable private volatile String startedName;
    @Nullable private volatile String startedBrokerUri;
    private volatile boolean started;

    public FakePeerCoordinator(@NonNull String runtimeLabel) {
        this.runtimeLabel = runtimeLabel;
    }

    @Override
    public void start(@NonNull String mapId, @NonNull String guid, @NonNull String name, @Nullable String brokerUri) {
        started = true;
        startedMapId = mapId;
        startedGuid = guid;
        startedName = name;
        startedBrokerUri = brokerUri;
        record("start", String.format(Locale.US, "%s/%s", mapId, guid));
    }

    @Override
    public void stop() {
        started = false;
        record("stop", runtimeLabel);
    }

    @Override
    public void onLiveTrackCreated(@NonNull CaltopoLiveTrack liveTrack,
                                   @NonNull CtDroneSpec droneSpec,
                                   double distMeters,
                                   long firstSeenTs) {
        record("onLiveTrackCreated", droneSpec.getRemoteId() + " dist=" + distMeters);
    }

    @Override
    public void onWaypointReceived(@NonNull String remoteId,
                                   double droneLat,
                                   double droneLon,
                                   double droneAlt,
                                   double distMeters) {
        record("onWaypointReceived",
                String.format(Locale.US, "%s %.6f,%.6f d=%.1f",
                        remoteId, droneLat, droneLon, distMeters));
    }

    @Override
    public void onDroneLost(@NonNull String remoteId) {
        record("onDroneLost", remoteId);
    }

    @Override
    public boolean isLocalOwner(@NonNull String remoteId) {
        return locallyOwnedRemoteIds.contains(remoteId);
    }

    @Override
    public void updateCaltopoRtt(long rttMs) {
        record("updateCaltopoRtt", Long.toString(rttMs));
    }

    @Override
    public void updateMyPosition(double lat, double lon) {
        record("updateMyPosition", String.format(Locale.US, "%.6f,%.6f", lat, lon));
    }

    @Override
    public void setPeerListChangedListener(@Nullable R2CMqttManager.PeerListChangedListener listener) {
        peerListChangedListener = listener;
    }

    @NonNull
    @Override
    public List<R2CMqttManager.PeerState> getPeerList() {
        synchronized (peers) {
            return new ArrayList<>(peers);
        }
    }

    public boolean isStarted() {
        return started;
    }

    @Nullable
    public String getStartedMapId() {
        return startedMapId;
    }

    @Nullable
    public String getStartedGuid() {
        return startedGuid;
    }

    @Nullable
    public String getStartedName() {
        return startedName;
    }

    @Nullable
    public String getStartedBrokerUri() {
        return startedBrokerUri;
    }

    @NonNull
    public List<Event> snapshotEvents() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    public int countEvents(@NonNull String kind) {
        int count = 0;
        synchronized (events) {
            for (Event event : events) {
                if (kind.equals(event.kind)) count++;
            }
        }
        return count;
    }

    public void setLocalOwnership(@NonNull String remoteId, boolean isOwner) {
        if (isOwner) {
            locallyOwnedRemoteIds.add(remoteId);
        } else {
            locallyOwnedRemoteIds.remove(remoteId);
        }
    }

    public void setPeerList(@NonNull List<R2CMqttManager.PeerState> peerStates) {
        synchronized (peers) {
            peers.clear();
            peers.addAll(peerStates);
        }
        R2CMqttManager.PeerListChangedListener listener = peerListChangedListener;
        if (listener != null) {
            listener.onPeerListChanged(getPeerList());
        }
    }

    @NonNull
    public R2CMqttManager.PeerState newPeerState(@NonNull String guid, @NonNull String name) {
        R2CMqttManager.PeerState ps = new R2CMqttManager.PeerState(guid);
        ps.name = name;
        ps.online = true;
        ps.lastSeenMs = System.currentTimeMillis();
        return ps;
    }

    public void clear() {
        events.clear();
        peers.clear();
        locallyOwnedRemoteIds.clear();
        started = false;
        startedMapId = null;
        startedGuid = null;
        startedName = null;
        startedBrokerUri = null;
        peerListChangedListener = null;
    }

    private void record(@NonNull String kind, @NonNull String summary) {
        events.add(new Event(kind, summary));
    }
}
