/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */


package org.ncssar.rid2caltopo.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.opendroneid.android.data.Util;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedList;

/** CaltopoLiveTrack
 * Creates and reports track waypoints to a CaltopoMap.  When multiple R2C
 * instances are connected to the same map, ownership is coordinated via
 * R2CMqttManager: only the owning instance publishes waypoints to CalTopo.
 * Ownership is assigned by MQTT-based score (proximity + CalTopo RTT);
 * this class simply acts on the result via {@link #setLocalOwner}.
 */

public class CaltopoLiveTrack implements CaltopoMap.MapStatusListener, LiveTrackOwnerDelegate {
    private static final String ICON_LATENCY_TAG = "RidIconLatency";
    public interface LocalTrackListener {
        void onLocalTrackPoint(
                @NonNull String remoteId,
                @NonNull String mappedId,
                double lat,
                double lng,
                double altitudeMeters,
                long timestampMsec
        );
    }
    public interface LocalTrackFinishedListener {
        void onLocalTrackFinished(
                @NonNull String remoteId,
                @NonNull String mappedId,
                @NonNull String reason
        );
    }

    private static final String TAG = "CaltopoLiveTrack";
    private static final Util.SimpleMovingAverage CaltopoRttInMsec = new Util.SimpleMovingAverage(10);
    private static final Hashtable<String, CaltopoLiveTrack> LiveTrackByRemoteId = new Hashtable<>(16);
    private static final LinkedList<LocalTrackListener> LocalTrackListeners = new LinkedList<>();
    private static final LinkedList<LocalTrackFinishedListener> LocalTrackFinishedListeners = new LinkedList<>();
    private CaltopoOp startLiveTrackOp;
    private String liveTrackId;
    private static class QueuedPoint {
        final double lat;
        final double lng;
        final double ele;
        final long timestampMsec;
        @Nullable final CtDroneSpec.PositionTelemetry telemetry;

        QueuedPoint(double lat, double lng, double ele, long timestampMsec,
                    @Nullable CtDroneSpec.PositionTelemetry telemetry) {
            this.lat = lat;
            this.lng = lng;
            this.ele = ele;
            this.timestampMsec = timestampMsec;
            this.telemetry = telemetry;
        }
    }
    private static final double DUPLICATE_COORD_EPSILON = 0.000001;
    private static final double DUPLICATE_ALT_EPSILON_METERS = 0.5;
    private final LinkedList<QueuedPoint> linePoints = new LinkedList<>();
    private int linePointsSentCount;
    private int linePointsConfirmedCount;
    private String folderId;
    private boolean active;
    /** True once R2CMqttManager has confirmed this instance owns this drone. */
    private boolean localOwner = false;
    @NonNull private final R2cRuntime runtime;
    private String myRemoteId;
    private CtDroneSpec droneSpec;
    private boolean shuttingDown = false;
    private int consecutiveUpdateFails = 0;
    public static long GetCaltopoRttInMsec() { return CaltopoRttInMsec.get();}
    private CaltopoMap.MapStatusListener.mapStatus mapStatus;

    public CaltopoLiveTrack(@NonNull CtDroneSpec droneSpec, double lat, double lng, double ele,
                            long droneTimestampInMsec) throws RuntimeException {
        if (droneSpec.trackLabel().isEmpty()) {
            throw new RuntimeException("CaltopoLiveTrack(): trackLabel is required.");
        }
        mapStatus = CaltopoMap.GetMapStatus();
        runtime = R2cRuntimeRegistry.getDefaultRuntime();
        CaltopoMap.AddMapStatusListener(this);
        CaltopoMap.AddLiveTrack(this);
        myRemoteId = droneSpec.getRemoteId();
        LiveTrackByRemoteId.put(myRemoteId, this);
        active = true;
        this.droneSpec = droneSpec;
        droneSpec.setMyLiveTrack(this);
        this.localOwner = false;

        linePoints.add(new QueuedPoint(lat, lng, ele, droneTimestampInMsec, droneSpec.getLastPositionTelemetry()));
        notifyLocalTrackPoint(lat, lng, ele, droneTimestampInMsec);
        linePointsSentCount = linePointsConfirmedCount = consecutiveUpdateFails = 0;

        if (mapStatus != CaltopoMap.MapStatusListener.mapStatus.up) return;

        // Ask R2CMqttManager to determine ownership.  It will call setLocalOwner()
        // (possibly after a brief discovery window) when a decision is reached.
        double distMeters = CaltopoMap.DistanceFromMeInMeters(lat, lng);
        runtime.getPeerCoordinator()
                .onLiveTrackCreated(this, droneSpec, distMeters, droneTimestampInMsec);
    }

    public void startNewTrack(double lat, double lng, double ele, long droneTimestampInMsec) {
        if (shuttingDown) return;

        linePoints.add(new QueuedPoint(lat, lng, ele, droneTimestampInMsec, droneSpec.getLastPositionTelemetry()));
        notifyLocalTrackPoint(lat, lng, ele, droneTimestampInMsec);
        linePointsSentCount = linePointsConfirmedCount = consecutiveUpdateFails = 0;
        startLiveTrackOp = null;
        localOwner = false;

        if (mapStatus != CaltopoMap.MapStatusListener.mapStatus.up) return;

        double distMeters = CaltopoMap.DistanceFromMeInMeters(lat, lng);
        runtime.getPeerCoordinator()
                .onLiveTrackCreated(this, droneSpec, distMeters, droneTimestampInMsec);
    }

    public void mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus mapStatusIn,
                                @Nullable CaltopoNode.MapNode map, @Nullable String emsg) {
        mapStatus = mapStatusIn;
        CTDebug(TAG, String.format(Locale.US, "mapStatusUpdate(%s) %s is %s.  localOwner:%s",
                droneSpec.trackLabel(), CaltopoMap.GetMapId(), mapStatus, localOwner));
        switch (mapStatusIn) {
            case credentialsVerified:
            case connecting: {
                shuttingDown = false;
                active = true;
                break;
            }
            case up: {
                folderId = CaltopoMap.GetFolderId();
                break;
            }
            case down: {
                break;
            }
        }
    }

    public boolean publishingLocally() {
        return (mapStatus == CaltopoMap.MapStatusListener.mapStatus.up && localOwner);
    }

    @NonNull
    public static String buildArchiveDescription(@Nullable CtDroneSpec droneSpec) {
        if (droneSpec == null) return "";
        ArrayList<String> lines = new ArrayList<>(3);
        String owner = droneSpec.getOwner().trim();
        String org = droneSpec.getOrg().trim();
        String model = droneSpec.getModel().trim();
        if (!owner.isEmpty()) lines.add("Pilot Callsign: " + owner);
        if (!org.isEmpty()) lines.add("Pilot Organization: " + org);
        if (!model.isEmpty()) lines.add("Drone Model: " + model);
        return String.join("\n", lines);
    }

    /**
     * Called by R2CMqttManager when ownership is granted or revoked.
     * Gaining ownership starts a new CalTopo livetrack segment for this drone.
     * Losing ownership stops publication immediately.
     */
    @Override
    public @NonNull String getRemoteId() {
        return myRemoteId;
    }

    @Override
    public int getQueuedPointCount() {
        return linePoints.size();
    }

    public boolean ownsLiveTrackId(@Nullable String trackId) {
        return null != trackId && trackId.equals(liveTrackId);
    }

    public boolean matchesFeatureDeviceId(@Nullable String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) return false;
        return deviceId.equalsIgnoreCase("FLEET:DRONE-" + myRemoteId);
    }

    @Override
    public void setLocalOwner(boolean isOwner) {
        if (shuttingDown) return;
        CTDebug(TAG, String.format(Locale.US,
                "setLocalOwner(%s): %s → %s", droneSpec.trackLabel(), localOwner, isOwner));
        boolean wasOwner = localOwner;
        localOwner = isOwner;
        if (isOwner && !wasOwner && mapStatus == CaltopoMap.MapStatusListener.mapStatus.up) {
            backfillQueuedPointsFromWaypointTrackIfNeeded();
            startNewTrack();
        }
    }

    /* Return -1 if no corresponding point */
    public long getFirstTimestamp() {
        if (linePoints.isEmpty()) return -1;
        QueuedPoint point = linePoints.getFirst();
        return point.timestampMsec;
    }

    public static CaltopoLiveTrack GetLiveTrackForRemoteId(@NonNull String remoteId) {
        return LiveTrackByRemoteId.get(remoteId);
    }

    public static void AddLocalTrackListener(@NonNull LocalTrackListener listener) {
        if (!LocalTrackListeners.contains(listener)) {
            LocalTrackListeners.add(listener);
        }
    }

    public static void RemoveLocalTrackListener(@NonNull LocalTrackListener listener) {
        LocalTrackListeners.remove(listener);
    }

    public static void AddLocalTrackFinishedListener(@NonNull LocalTrackFinishedListener listener) {
        if (!LocalTrackFinishedListeners.contains(listener)) {
            LocalTrackFinishedListeners.add(listener);
        }
    }

    public static void RemoveLocalTrackFinishedListener(@NonNull LocalTrackFinishedListener listener) {
        LocalTrackFinishedListeners.remove(listener);
    }

    public static void NotifyLocalTrackPoint(@NonNull CtDroneSpec droneSpec,
                                             double lat, double lng, double altitudeMeters,
                                             long timestampMsec) {
        if (LocalTrackListeners.isEmpty()) {
            CTDebug(ICON_LATENCY_TAG, String.format(Locale.US,
                    "track_notify_skipped remoteId=%s mappedId=%s reason=no_listeners droneTs=%d lat=%.6f lng=%.6f alt=%.1f",
                    droneSpec.getRemoteId(), droneSpec.getMappedId(), timestampMsec, lat, lng, altitudeMeters));
            return;
        }
        String remoteId = droneSpec.getRemoteId();
        String mappedId = droneSpec.getMappedId();
        for (LocalTrackListener listener : LocalTrackListeners) try {
            listener.onLocalTrackPoint(remoteId, mappedId, lat, lng, altitudeMeters, timestampMsec);
        } catch (Exception e) {
            CTError(TAG, "NotifyLocalTrackPoint() listener raised", e);
        }
    }

    public static void NotifyLocalTrackFinished(@NonNull CtDroneSpec droneSpec, @NonNull String reason) {
        String remoteId = droneSpec.getRemoteId();
        String mappedId = droneSpec.getMappedId();
        for (LocalTrackFinishedListener listener : LocalTrackFinishedListeners) try {
            listener.onLocalTrackFinished(remoteId, mappedId, reason);
        } catch (Exception e) {
            CTError(TAG, "NotifyLocalTrackFinished() listener raised", e);
        }
    }

    public void shutdown(long maxWaitInMilliseconds) {
        shuttingDown = true;
        if (active && null != liveTrackId) try {
            CTDebug(TAG, String.format(Locale.US, "shutdown(%d). Terminating '%s'",
                    maxWaitInMilliseconds, droneSpec.trackLabel()));
            boolean wasOwner = localOwner;
            CaltopoMap.RemoveLiveTrack(liveTrackId);
            archiveTrackOnCaltopo(maxWaitInMilliseconds);
            if (wasOwner) runtime.getPeerCoordinator().onDroneLost(myRemoteId);
        } catch (Exception e) {
            CTError(TAG, String.format(Locale.US, "shutdown(%s) failed:", droneSpec.trackLabel()), e);
        }
        localOwner = false;
        active = false;
    }

    /** CaltopoMap periodically checks for updates to map features
     *  and forwards the current label for our track to us so we can see
     *  if the user has requested a different label be used for this drone.
     *
     * @param caltopoTrackLabel Usually of the form <label>_datetimestamp,
     *                          so look at everything before the '_'.
     */
    public void checkCaltopoTrackLabel(@NonNull String caltopoTrackLabel) {
        String trackLabel = droneSpec.trackLabel();
        if (trackLabel.equals(caltopoTrackLabel)) return; // no change.
        String newLabel = caltopoTrackLabel;
        int indexOfChar = caltopoTrackLabel.indexOf('_');
        if (indexOfChar < 0) indexOfChar = caltopoTrackLabel.indexOf('-');
        if (indexOfChar > 0) {
            newLabel = caltopoTrackLabel.substring(0, indexOfChar);
        }
        if (newLabel.isEmpty()) {
            CaltopoClient.CTWarn(TAG, String.format(Locale.US,
                    "checkCaltopoTrackLabel(): empty title observed for remoteId=%s liveTrackId=%s localOwner=%s mappedId='%s' trackLabel='%s' rawTitle='%s'",
                    myRemoteId, liveTrackId, localOwner, droneSpec.getMappedId(), trackLabel, caltopoTrackLabel));
        }
        String dsMappedId = droneSpec.setMappedId(newLabel);
        CTDebug(TAG, String.format(Locale.US,
                        "checkCaltopoTrackLabel(): Changing track name from '%s' to '%s', ds returned: '%s'",
                trackLabel, newLabel, dsMappedId));
    }

    /**  Archive this track segment on Caltopo if we're the owner.
     */
    public void archiveTrackOnCaltopo(long maxWaitInMilliseconds) {
        if (!localOwner) {
            if (liveTrackId != null) {
                CTWarn(TAG, String.format(Locale.US,
                        "archiveTrackOnCaltopo(): no longer owner; deleting orphaned live track '%s' for remoteId=%s",
                        liveTrackId, myRemoteId));
                try {
                    runtime.getCalTopoSessionGateway().deleteLiveTrackWithId(liveTrackId, null, 400, 404);
                } catch (Exception e) {
                    CTError(TAG, "archiveTrackOnCaltopo(): deleteLiveTrackWithId() raised: ", e);
                }
            } else {
                CTDebug(TAG, "archiveTrackOnCaltopo(): not the owner — skipping.");
            }
            resetLiveTrack();
            return;
        }
        String trackLabel = droneSpec.trackLabel();
        int size = linePoints.size();
        if (0 == size || null == liveTrackId) {
            CTDebug(TAG, String.format(Locale.US,
                    "archiveTrackOnCaltopo(%s): w/no waypoints ignored.", trackLabel));
            resetLiveTrack();
            return;
        }
        JSONArray jsonArray = new JSONArray();
        for (int i = 0; i < size; i++) {
            QueuedPoint point = linePoints.get(i);
            JSONArray pointArray = new JSONArray();
            pointArray.put(String.format(Locale.US, "%.7f", point.lng));
            pointArray.put(String.format(Locale.US, "%.7f", point.lat));
            pointArray.put(String.format(Locale.US, "%f", point.ele));
            jsonArray.put(pointArray);
        }
        String archiveFolderId = CaltopoMap.GetArchiveFolderId();
        String archiveDescription = buildArchiveDescription(droneSpec);
        CTDebug(TAG, String.format(Locale.US, "archiveTrackOnCaltopo(%s): Archiving track with %d points.",
                trackLabel, size));
        if (null != startLiveTrackOp && startLiveTrackOp.isDone() && startLiveTrackOp.success()) {
            // convert the LiveTrack to a Shape w/archive properties and add in all the waypoints.
            JSONObject feature = startLiveTrackOp.responseJson;
            JSONObject geometry = new JSONObject();
            try {
                geometry.put("coordinates", jsonArray);
                geometry.put("type", "LineString");
                feature.put("geometry", geometry);
                JSONObject properties = feature.optJSONObject("properties");
                if (properties == null) {
                    properties = new JSONObject();
                    feature.put("properties", properties);
                }
                if (!archiveDescription.isEmpty()) {
                    properties.put("description", archiveDescription);
                }
            } catch (JSONException e) {
                CTError(TAG, "archiveTrackCaltopo() JSONObject.put() raised - for no apparent reason.", e);
            }
            CaltopoMap.ArchiveFeature(feature, "LiveTrack", System.currentTimeMillis(), maxWaitInMilliseconds);
        } else {
            // for some reason, we weren't able to start the live track, so this will likely block as well
            try {
                runtime.getCalTopoSessionGateway()
                        .addLine(jsonArray, trackLabel, archiveDescription, "", archiveFolderId,
                                CaltopoMap.ArchiveLineProp, null);
            } catch (Exception e) {
                CTError(TAG, "archiveTrackCaltopo() addLine() raised - for no apparent reason.", e);
            }
        }
        resetLiveTrack();
    }
    private void resetLiveTrack() {
        boolean isActive = droneSpec.isActive();
        CTDebug(TAG, String.format(Locale.US, "resetLiveTrack(%s): resetting %sactive track",
                droneSpec.trackLabel(), isActive ? "": "in"));
        if (isActive) {
            WaypointTrack.ArchiveTrack(droneSpec.trackLabel());
            droneSpec.reset();
        }
        linePoints.clear();
        liveTrackId = null;
        linePointsSentCount = linePointsConfirmedCount = consecutiveUpdateFails = 0;
        startLiveTrackOp = null;
        active = false;
    }

    public String getTrackLabel() {
        return droneSpec.trackLabel() + (isActive()? "":"(inactive)");
    }

    public CtDroneSpec getDroneSpec() {
        return droneSpec;
    }

    public void renameTrackCompleted(CaltopoOp renameTrackOp) {
        if (renameTrackOp.fail()) {
            CTError(TAG, "renameTrackCompleted(): Failed to rename LiveTrack: " + renameTrackOp.responseString());
        } else {
            CTDebug(TAG, "renameTrackCompleted(): succeeded: " + renameTrackOp.responseString());
        }
    }

    public void renameTrack() {
        // Just edit the current live track - replacing the title.
        // N.B. must continue to use the original track name when publishing tracks...
        if (!active || null == startLiveTrackOp) {
            CTError(TAG, "renameTrack(): received on inactive track.");
            return;
        }
        if (!startLiveTrackOp.isDone()) return;
        try {
            long timeNowInMilliseconds = System.currentTimeMillis();
            String timeString = String.valueOf(timeNowInMilliseconds);
            JSONObject feature = startLiveTrackOp.responseJson;
            JSONObject prop = feature.optJSONObject("properties");
            if (null == prop) {
                prop = new JSONObject();
                feature.put("properties", prop);
            }
            prop.put("title", droneSpec.trackLabel());
            prop.put("updated", timeString);
            prop.put("-updated-on", timeString);
            runtime.getCalTopoSessionGateway()
                    .editObjectWithId("LiveTrack", liveTrackId, feature, this::renameTrackCompleted);
        } catch (Exception e) {
            CTError(TAG, "renameTrack() raised.", e);
        }
    }

    private void startNewTrack() {
        if (null == startLiveTrackOp && localOwner) {
            liveTrackId = null;
            linePointsSentCount = linePointsConfirmedCount = consecutiveUpdateFails = 0;
            active = true;
            if (null == folderId) folderId = CaltopoMap.GetFolderId();
            String trackLabel = droneSpec.trackLabel();
            CTDebug(TAG, String.format(Locale.US,
                    "startNewTrack(DRONE-%s): Starting LiveTrack w/label:%s mappedId='%s' localOwner=%s queuedPoints=%d in folder:%s",
                    myRemoteId, trackLabel, droneSpec.getMappedId(), localOwner, linePoints.size(), folderId));
            try {
                startLiveTrackOp = runtime.getCalTopoSessionGateway()
                        .startLiveTrack(myRemoteId, trackLabel, folderId,
                                null, null, this::startLiveTrackComplete);
            } catch (Exception e) {
                CTError(TAG, "startNewTrack(): startLiveTrack() raised: ", e);
            }
        }
    }

    private void backfillQueuedPointsFromWaypointTrackIfNeeded() {
        if (liveTrackId != null || startLiveTrackOp != null || linePointsSentCount != 0) return;

        List<WaypointTrack.TrackPoint> snapshot = WaypointTrack.GetTrackPointsSnapshot(droneSpec);
        if (snapshot.size() <= linePoints.size()) return;

        linePoints.clear();
        for (WaypointTrack.TrackPoint point : snapshot) {
            linePoints.add(new QueuedPoint(
                    point.lat,
                    point.lng,
                    point.ele,
                    point.timestampMsec,
                    null));
        }
        CTDebug(TAG, String.format(Locale.US,
                "backfillQueuedPointsFromWaypointTrackIfNeeded(%s): queued %d pre-connect point(s).",
                droneSpec.trackLabel(), linePoints.size()));
    }

    public void finishTrack(@NonNull String reason) {
        if (active && null != liveTrackId) try {
            boolean wasOwner = localOwner;
            CTDebug(TAG, String.format(Locale.US, "finishTrack(%s): %s", getTrackLabel(), reason));
            CaltopoMap.RemoveLiveTrack(liveTrackId);
            archiveTrackOnCaltopo(0);
            NotifyLocalTrackFinished(droneSpec, reason);
            if (wasOwner) runtime.getPeerCoordinator().onDroneLost(myRemoteId);
            localOwner = false;
            active = false;
        } catch (Exception e) {
            CTError(TAG, String.format(Locale.US, "finishTrack(%s) '%s' failed:", droneSpec.trackLabel(), reason), e);
        }
    }

    public boolean isActive() {return active; }

    /** Re-register all active live tracks with R2CMqttManager after a map reconnect. */
    public static void ReevalUnknownAndPendingTracks() {
        for (CaltopoLiveTrack liveTrack : LiveTrackByRemoteId.values()) {
            if (!liveTrack.localOwner) {
                double dist = CaltopoMap.DistanceFromMeInMeters(
                        liveTrack.droneSpec.lastLat, liveTrack.droneSpec.lastLng);
                long fts = liveTrack.getFirstTimestamp();
                R2cRuntimeRegistry.getDefaultRuntime().getPeerCoordinator()
                        .onLiveTrackCreated(liveTrack, liveTrack.droneSpec, dist, fts);
            }
        }
    }

    private void startLiveTrackComplete(CaltopoOp op) {
        String trackLabel = droneSpec.trackLabel();
        if (op.fail()) {
            CTError(TAG, String.format(Locale.US,
                    "Not able to open LiveTrack for:'DRONE-%s' - responseCode:%d, response: %s",
                    trackLabel, op.responseCode, op.responseString()));
            finishTrack("Not able to open/write LiveTrack");
        } else try {
            CTDebug(TAG, "startLiveTrackComplete(): succeeded. ResponseCode: " + op.responseCode + " response: " + op.response);
            liveTrackId = op.id();
            CTDebug(TAG, String.format(Locale.US, "startLiveTrackComplete(%s): liveTrackId: '%s'",
                    trackLabel, liveTrackId));
            if (!localOwner || shuttingDown || !active) {
                CTDebug(TAG, String.format(Locale.US,
                        "startLiveTrackComplete(%s): ownership no longer local; ignoring LiveTrack completion and leaving queued points buffered.",
                        trackLabel));
                liveTrackId = null;
                startLiveTrackOp = null;
                return;
            }
            CaltopoMap.AddLiveTrack(liveTrackId, this);
        } catch (Exception e) {
            CTError(TAG, "startLiveTrackComplete(): raised:", e);
        }
        forwardNextWaypoints(null);
    }

    public void publishDirect(double lat, double lng, long altitudeInMeters, long droneTimestampInMillisec) {
        queueWaypoint(
                lat,
                lng,
                altitudeInMeters,
                droneTimestampInMillisec,
                droneSpec.getLastPositionTelemetry(),
                true
        );
    }

    @Override
    public void onPeerWaypoint(
            @NonNull String sourceZoneId,
            double lat,
            double lng,
            double altitudeMeters,
            long timestampMsec,
            @Nullable CtDroneSpec.PositionTelemetry telemetry) {
        CTDebug(TAG, String.format(Locale.US,
                "onPeerWaypoint(%s <- %s): %.6f,%.6f alt=%.1f ts=%d",
                droneSpec.trackLabel(), sourceZoneId, lat, lng, altitudeMeters, timestampMsec));
        queueWaypoint(lat, lng, altitudeMeters, timestampMsec, telemetry, false);
    }

    private void queueWaypoint(
            double lat,
            double lng,
            double altitudeMeters,
            long timestampMsec,
            @Nullable CtDroneSpec.PositionTelemetry telemetry,
            boolean notifyCoordinator) {
        QueuedPoint previousPoint = linePoints.peekLast();
        if (isDuplicateOfPreviousPoint(previousPoint, lat, lng, altitudeMeters, timestampMsec)) {
            CTDebug(TAG, String.format(Locale.US,
                    "queueWaypoint(%s): dropping duplicate point %.6f,%.6f alt=%.1f ts=%d",
                    droneSpec.trackLabel(), lat, lng, altitudeMeters, timestampMsec));
            return;
        }
        linePoints.add(new QueuedPoint(lat, lng, altitudeMeters, timestampMsec, telemetry));
        notifyLocalTrackPoint(lat, lng, altitudeMeters, timestampMsec);
        CTDebug(TAG, String.format(Locale.US,
                "queueWaypoint(%s/localOwner=%s/%s): waypoint queued. size=%d sent=%d confirmed=%d errors=%d notify=%s",
                droneSpec.trackLabel(), localOwner, mapStatus.toString(), linePoints.size(),
                linePointsSentCount, linePointsConfirmedCount, consecutiveUpdateFails, notifyCoordinator));

        if (notifyCoordinator) {
            double distMeters = CaltopoMap.DistanceFromMeInMeters(lat, lng);
            runtime.getPeerCoordinator()
                    .onWaypointReceived(droneSpec, lat, lng, altitudeMeters, distMeters, timestampMsec, telemetry);
        }

        if (mapStatus != CaltopoMap.MapStatusListener.mapStatus.up) return;
        if (!localOwner) {
            return;
        }
        if (null != liveTrackId) forwardNextWaypoints(null);
        else if (null == startLiveTrackOp) startNewTrack();
    }

    /** forwardNextWaypoints():
     *  Pull waypoints off the queue and forward to Caltopo
     */
    public void forwardNextWaypoints(@Nullable CaltopoOp lastOp) {
        if (shuttingDown || !active) {
            CTDebug(TAG, "forwardNextWaypoints(): Not active.");
            return; // Don't send any more waypoints at this time.
        }
        if (!localOwner) {
            CTDebug(TAG, String.format(Locale.US,
                    "forwardNextWaypoints(%s): not local owner; leaving %d queued point(s) buffered.",
                    droneSpec.trackLabel(),
                    Math.max(0, linePoints.size() - linePointsSentCount)));
            return;
        }
        if (null != lastOp && lastOp.isDone()) {
            linePointsConfirmedCount++;
            long rtt = lastOp.roundTripTimeInMsec();
            CaltopoRttInMsec.next(rtt);
            runtime.getPeerCoordinator().updateCaltopoRtt(rtt);
            if (lastOp.fail()) {
                consecutiveUpdateFails++;
                CTError(TAG, "forwardNextWaypoints(): addLiveTrackPoint failed: " + lastOp.response);
                if (consecutiveUpdateFails > 2) {
                    CTError(TAG, "forwardNextWaypoints(): shutting down LiveTrack after several consecutive update failures. ");
                    active = false;
                    return;
                }
            } else {
                consecutiveUpdateFails = 0;
            }
        }
        try {
            int pointCount = linePoints.size();
            while (linePointsSentCount < pointCount) {
                QueuedPoint point = linePoints.get(linePointsSentCount++);
                CTDebug(TAG, String.format(Locale.US, "forwardNextWaypoint(DRONE-%s#%d): adding %.7f,%.7f@%dm to LiveTrack.",
                        myRemoteId, linePointsSentCount, point.lat, point.lng, (long)point.ele));
                runtime.getCalTopoSessionGateway()
                        .addLiveTrackPoint(myRemoteId, point.lat, point.lng, point.ele, point.telemetry,
                                this::forwardNextWaypoints);
            }
        } catch (Exception e) {
            CTError(TAG, "forwardNextWaypoints(): addLiveTrackPoint() raised: ", e);
        }
    }

    private boolean isDuplicateOfPreviousPoint(
            @Nullable QueuedPoint previousPoint,
            double lat,
            double lng,
            double altitudeMeters,
            long timestampMsec) {
        if (previousPoint == null) return false;
        if (previousPoint.timestampMsec != timestampMsec) return false;
        return Math.abs(previousPoint.lat - lat) <= DUPLICATE_COORD_EPSILON &&
                Math.abs(previousPoint.lng - lng) <= DUPLICATE_COORD_EPSILON &&
                Math.abs(previousPoint.ele - altitudeMeters) <= DUPLICATE_ALT_EPSILON_METERS;
    }

    private void notifyLocalTrackPoint(double lat, double lng, double altitudeMeters, long timestampMsec) {
        if (LocalTrackListeners.isEmpty()) {
            CTDebug(ICON_LATENCY_TAG, String.format(Locale.US,
                    "track_notify_skipped remoteId=%s mappedId=%s reason=no_listeners droneTs=%d lat=%.6f lng=%.6f alt=%.1f",
                    myRemoteId, droneSpec.getMappedId(), timestampMsec, lat, lng, altitudeMeters));
            return;
        }
        String mappedId = droneSpec.getMappedId();
        for (LocalTrackListener listener : LocalTrackListeners) try {
            listener.onLocalTrackPoint(myRemoteId, mappedId, lat, lng, altitudeMeters, timestampMsec);
        } catch (Exception e) {
            CTError(TAG, "notifyLocalTrackPoint() listener raised", e);
        }
    }
}
