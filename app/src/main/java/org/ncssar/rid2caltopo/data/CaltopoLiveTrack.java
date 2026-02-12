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

import java.util.Hashtable;
import java.util.Locale;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.R2CPeer.R2CRespEnum.okToPublishLocally;
import static org.ncssar.rid2caltopo.data.R2CPeer.R2CRespEnum.unknown;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedList;

/** CaltopoLiveTrack
 * Used to create and report track waypoints to a CaltopoMap.    If there
 * are multiple R2CPeers active, then we need to engage in a bit of arbitration
 * to see who handles new drones as they pop up in our respective zones.   While
 * planners should try to position bridges and their corresponding R2C app devices
 * so that search segments don't overlap much, there is a chance that someone will
 * start a drone somewhere that is detectable by more than one R2C app instance.
 * When that happens,  both will attempt to adopt the drone with "add-drone"
 * messages to their peers containing the lat, lng, and timestamp of the first
 * message they received from the new drone and if they both saw the same RID
 * packet, they will compute their respective distances from the drone before
 * deciding who gets to add the drone.
 */

public class CaltopoLiveTrack implements CaltopoMap.MapStatusListener {
    private static final String TAG = "CaltopoLiveTrack";
    private static final Util.SimpleMovingAverage CaltopoRttInMsec = new Util.SimpleMovingAverage(10);
    private static final Hashtable<String, CaltopoLiveTrack> LiveTrackByRemoteId = new Hashtable<>(16);
    private CaltopoOp startLiveTrackOp;
    private String liveTrackId;
    private final LinkedList<double[]> linePoints = new LinkedList<>(); // array of arrays of [lat,lng,ele] pairs
    private int linePointsSentCount;
    private int linePointsConfirmedCount;
    private String folderId;
    private boolean active;
    private R2CPeer r2cPeer;  // if we're forwarding to a remote, this is not null
    private R2CPeer.R2CRespEnum r2cStatus;
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
        CaltopoMap.AddMapStatusListener(this);
        CaltopoMap.AddLiveTrack(this);
        myRemoteId = droneSpec.getRemoteId();
        LiveTrackByRemoteId.put(myRemoteId, this);
        active = true;
        this.droneSpec = droneSpec;
        droneSpec.setMyLiveTrack(this);
        this.r2cStatus = unknown;

        double[] point = {lat, lng, ele, (double)droneTimestampInMsec};
        linePoints.add(point);
        linePointsSentCount = linePointsConfirmedCount = consecutiveUpdateFails = 0;

        if (mapStatus != CaltopoMap.MapStatusListener.mapStatus.up) return;
        this.r2cStatus = R2CPeer.StatusForNewRemoteId(this, droneSpec);
        startNewTrack();
    }

    /*
     */
    public void startNewTrack(double lat, double lng, double ele, long droneTimestampInMsec) {
        if (shuttingDown) return;

        double[] point = {lat, lng, ele, (double)droneTimestampInMsec};
        linePoints.add(point);
        linePointsSentCount = linePointsConfirmedCount = consecutiveUpdateFails = 0;
        startLiveTrackOp = null;
        if (mapStatus != CaltopoMap.MapStatusListener.mapStatus.up) {
            r2cStatus = unknown;
            return;
        }
        r2cStatus = R2CPeer.StatusForNewRemoteId(this, droneSpec);
        switch (r2cStatus) {
            case forwardToPeer: {
                r2cPeer = R2CPeer.PeerForRemoteId(myRemoteId);
                if (null == r2cPeer) {
                    CTError(TAG, "startNewTrack(): no peer to forward to - ignoring.");
                }
                break;
            }
            case okToPublishLocally: {
                startNewTrack();
            }
        }
    }

    public void mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus mapStatusIn,
                                @Nullable CaltopoNode.MapNode map, @Nullable String emsg) {
        mapStatus = mapStatusIn;
        CTDebug(TAG, String.format(Locale.US, "mapStatusUpdate(%s) %s is %s.  LiveTrack status is:%s",
                droneSpec.trackLabel(), CaltopoMap.GetMapId(), mapStatus, r2cStatus));
        switch (mapStatusIn) {
            case credentialsVerified: // treat the same.
            case connecting:  {
                shuttingDown = false;
                active = true;
                break;
            }
            case up: {
                folderId = CaltopoMap.GetFolderId();
                break; // FIXME: proactively find out if it's ok to start publishing
                //        instead of waiting for next waypoint to come in.
            }
            case down: {
                break;
            }
        }
    }

    public boolean publishingLocally() {
        return (mapStatus == CaltopoMap.MapStatusListener.mapStatus.up && r2cStatus == okToPublishLocally);
    }

    /* Return -1 if no corresponding point */
    public long getFirstTimestamp() {
        if (linePoints.isEmpty()) return -1;
        double[] point = linePoints.getFirst();
        return (long)point[3];
    }

    public static CaltopoLiveTrack GetLiveTrackForRemoteId(@NonNull String remoteId) {
        return LiveTrackByRemoteId.get(remoteId);
    }

    public void shutdown(long maxWaitInMilliseconds) {
        shuttingDown = true;
        if (active && null != liveTrackId) try {
            CTDebug(TAG, String.format(Locale.US, "shutdown(%d). Terminating '%s'",
                    maxWaitInMilliseconds, droneSpec.trackLabel()));
            if (r2cStatus == okToPublishLocally) R2CPeer.SendDropDrone(myRemoteId);
            CaltopoMap.RemoveLiveTrack(liveTrackId);
            archiveTrackOnCaltopo(maxWaitInMilliseconds);
        } catch (Exception e) {
            CTError(TAG, String.format(Locale.US, "shutdown(%s) failed:", droneSpec.trackLabel()), e);
        }
        r2cStatus = unknown;
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
        String dsMappedId = droneSpec.setMappedId(newLabel);
        CTDebug(TAG, String.format(Locale.US,
                        "checkCaltopoTrackLabel(): Changing track name from '%s' to '%s', ds returned: '%s'",
                trackLabel, newLabel, dsMappedId));
    }

    /**  Archive this track segment on Caltopo if we're the owner.
     */
    public void archiveTrackOnCaltopo( long maxWaitInMilliseconds) {
        if (r2cStatus != okToPublishLocally) {
            // We aren't responsible for writing this drone's tracks to caltopo
            CTDebug(TAG, "archiveTrackOnCaltopo(): attempt to archive a track that is owned by a remote R2C ignored.");
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
            double[] point = linePoints.get(i);
            JSONArray pointArray = new JSONArray();
            pointArray.put(String.format(Locale.US, "%.7f", point[1]));
            pointArray.put(String.format(Locale.US, "%.7f", point[0]));
            pointArray.put(String.format(Locale.US, "%f", point[2]));
            jsonArray.put(pointArray);
        }
        String archiveFolderId = CaltopoMap.GetArchiveFolderId();
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
            } catch (JSONException e) {
                CTError(TAG, "archiveTrackCaltopo() JSONObject.put() raised - for no apparent reason.", e);
            }
            CaltopoMap.ArchiveFeature(feature, "LiveTrack", System.currentTimeMillis(), maxWaitInMilliseconds);
        } else {
            // for some reason, we weren't able to start the live track, so this will likely block as well
            try {
                CaltopoSession.AddLine(jsonArray, trackLabel, "", "", archiveFolderId,
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
            CaltopoSession.EditObjectWithId("LiveTrack", liveTrackId, feature, this::renameTrackCompleted);
        } catch (Exception e) {
            CTError(TAG, "renameTrack() raised.", e);
        }
    }

    private void startNewTrack() {
        if (null == startLiveTrackOp && okToPublishLocally == r2cStatus) {
            liveTrackId = null;
            linePointsSentCount = linePointsConfirmedCount = consecutiveUpdateFails = 0;
            active = true;
            if (null == folderId) folderId = CaltopoMap.GetFolderId();
            String trackLabel = droneSpec.trackLabel();
            CTDebug(TAG, String.format(Locale.US, "startNewTrack(DRONE-%s): Starting LiveTrack w/label:%s in folder:%s",
                    myRemoteId, trackLabel, folderId));
            try {
                startLiveTrackOp = CaltopoSession.StartLiveTrack(myRemoteId, trackLabel, folderId,
                        null, null, this::startLiveTrackComplete);
                forwardNextWaypoints(null); // We've got at least one waypoint - get it on it's way.
            } catch (Exception e) {
                CTError(TAG, "startNewTrack(): startLiveTrack() raised: ", e);
            }
        }
    }

    public void finishTrack(@NonNull String reason) {
        if (r2cStatus == okToPublishLocally) R2CPeer.SendDropDrone(myRemoteId);
        if (active && null != liveTrackId) try {
            CTDebug(TAG, String.format(Locale.US, "finishTrack(%s): %s", getTrackLabel(), reason));
            CaltopoMap.RemoveLiveTrack(liveTrackId);
            archiveTrackOnCaltopo(0);
            r2cStatus = unknown;
            active = false;
        } catch (Exception e) {
            CTError(TAG, String.format(Locale.US, "finishTrack(%s) '%s' failed:", droneSpec.trackLabel(), reason), e);
        }
    }

    public boolean isActive() {return active; }

    public static void ReevalUnknownAndPendingTracks() {
        for (CaltopoLiveTrack liveTrack : LiveTrackByRemoteId.values()) {
            CtDroneSpec ds = liveTrack.droneSpec;
            liveTrack.r2cStatus = R2CPeer.StatusForNewRemoteId(liveTrack, ds);
        }
    }

    public void updateStatus(R2CPeer.R2CRespEnum status) {
        if (shuttingDown) return;
        CTDebug(TAG, String.format(Locale.US,
                "updateStatus() - changing from '%s' to '%s'", r2cStatus.toString(), status.toString()));
        r2cStatus = status;
        if (status == R2CPeer.R2CRespEnum.reevaluate) {
            reevaluate();
            CTDebug(TAG, "updateStatus() - reevaluate changed status to: " + r2cStatus.toString());
        }
        switch (r2cStatus) {
            case forwardToPeer: r2cPeer = R2CPeer.PeerForRemoteId(myRemoteId); break;
            case okToPublishLocally: startNewTrack(); break;
        }
    }

    /** reevaluate()
     * This gets invoked by an R2C instance when a peer releases ownership of this drone.
     * That can happen when the R2C instance shuts down or when it hasn't seen the drone
     * in newtrackdelayinseconds.
     */
    private void reevaluate() {
        r2cPeer = null;
        if (active) {
            // FIXME: Only want to follow this path if we've seen recent points, otherwise let it be.
            long minAge = System.currentTimeMillis() - (CaltopoClient.GetNewTrackDelayInSeconds() * 1000);

            while (!linePoints.isEmpty()) {
                double[] point;
                point = linePoints.getFirst();
                if ((long) point[3] <= minAge) {
                    linePoints.removeFirst();
                } else break;
            }
            linePointsSentCount = linePointsConfirmedCount = consecutiveUpdateFails = 0;
            if (linePoints.size() > 1) {
                r2cStatus = R2CPeer.StatusForNewRemoteId(this, droneSpec);
                CTDebug(TAG, "reevaluate(): " + r2cStatus.toString());
                return;
            }
            active = false; // no current waypoints
        }
        r2cStatus = unknown;
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
            CaltopoMap.AddLiveTrack(liveTrackId, this);
        } catch (Exception e) {
            CTError(TAG, "startLiveTrackComplete(): raised:", e);
        }
        forwardNextWaypoints(null);
    }

    public void publishDirect(double lat, double lng, long altitudeInMeters, long droneTimestampInMillisec) {
        double[] point = {lat, lng, (double)altitudeInMeters, (double)droneTimestampInMillisec};
        linePoints.add(point);
        CTDebug(TAG, String.format(Locale.US,
                "publishDirect(%s/%s/%s): added waypoint to queue. size is %d, sent count is %d, confirm count is %d and consecutive errors is %d",
                droneSpec.trackLabel(), r2cStatus.toString(), mapStatus.toString(), linePoints.size(),
                linePointsSentCount, linePointsConfirmedCount, consecutiveUpdateFails));
        if (mapStatus != CaltopoMap.MapStatusListener.mapStatus.up) return;
        switch (r2cStatus) {
            case forwardToPeer: {
                if (null == r2cPeer) r2cPeer = R2CPeer.PeerForRemoteId(myRemoteId);
                if (null != r2cPeer) {
                    r2cPeer.reportSeen(droneSpec, lat, lng, altitudeInMeters, droneTimestampInMillisec);
                } else {
                    CTError(TAG, "publishDirect(): no peer to forward to.");
                }
                return;
            }
            case pending:break;
            case unknown: {
                this.r2cStatus = R2CPeer.StatusForNewRemoteId(this, droneSpec);
                if (okToPublishLocally != this.r2cStatus) break;
            }
            case okToPublishLocally: {
                if (null != liveTrackId) forwardNextWaypoints(null);
                else if (null == startLiveTrackOp) startNewTrack();
            }
        }
    }

    /** forwardNextWaypoints():
     *  Pull waypoints off the queue and forward to Caltopo
     */
    public void forwardNextWaypoints(@Nullable CaltopoOp lastOp) {
        if (shuttingDown || !active) {
            CTDebug(TAG, "forwardNextWaypoints(): Not active.");
            return; // Don't send any more waypoints at this time.
        }
        if (null != lastOp && lastOp.isDone()) {
            linePointsConfirmedCount++;
            CaltopoRttInMsec.next(lastOp.roundTripTimeInMsec());
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
                double[] point = linePoints.get(linePointsSentCount++);
                CTDebug(TAG, String.format(Locale.US, "forwardNextWaypoint(DRONE-%s#%d): adding %.7f,%.7f@%dm to LiveTrack.",
                        myRemoteId, linePointsSentCount, point[0], point[1], (long)point[2]));
                CaltopoSession.AddLiveTrackPoint(myRemoteId, point[0], point[1], point[2], this::forwardNextWaypoints);
            }
        } catch (Exception e) {
            CTError(TAG, "forwardNextWaypoints(): addLiveTrackPoint() raised: ", e);
        }
    }
}
