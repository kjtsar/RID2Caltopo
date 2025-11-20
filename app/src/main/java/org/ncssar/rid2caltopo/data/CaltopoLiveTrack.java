package org.ncssar.rid2caltopo.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.ncssar.rid2caltopo.app.R2CActivity;
import org.opendroneid.android.data.Util;

import java.util.Hashtable;
import java.util.Locale;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.R2CRest.R2CRespEnum.okToPublishLocally;
import static org.ncssar.rid2caltopo.data.R2CRest.R2CRespEnum.unknown;

import androidx.annotation.NonNull;

import java.util.LinkedList;

/** CaltopoLiveTrack
 * Used to create and report track waypoints to a CaltopoClientMap.    If there
 * are multiple R2CRest clients active, then we need to engage in a bit of arbitration
 * to see who handles new drones as they pop up in our respective zones.   While
 * administrators should try to position bridges and their corresponding R2C app devices
 * so that search segments don't overlap, there is a chance that someone will start a
 * drone somewhere that is detectable by more than one R2C app instance.  When that
 * happens,  both will attempt to adopt the drone with "add-drone" messages to their
 * peers containing the lat, lng, and timestamp of the first message they received
 * from the new drone and if they both saw the same RID packet, they will compute
 * their respective distances from the drone before deciding who gets to add the
 * drone.
 *
 */

public class CaltopoLiveTrack implements CaltopoClientMap.MapStatusListener {
    private static final String TAG = "CaltopoLiveTrack";
    private static final Util.SimpleMovingAverage CaltopoRttInMsec = new Util.SimpleMovingAverage(10);
    private static final Hashtable<String, CaltopoLiveTrack> LiveTrackByRemoteId = new Hashtable<>(16);
    private CaltopoOp startLiveTrackOp;
    private CaltopoOp renameTrackOp;
    private CaltopoOp liveTrackOp;
    private String liveTrackId;
    private final LinkedList<double[]> linePoints = new LinkedList<>(); // array of arrays of [lat,lng] pairs
    private int linePointsSentCount;
    private String folderId;
    private final CaltopoClientMap myMap;
    private boolean active;
    private R2CRest r2cClient;  // if we're forwarding to a remote, this is not null
    private R2CRest.R2CRespEnum r2cStatus;
    private String myGroupId;
    private String myRemoteId;
    private CtDroneSpec droneSpec;
    private boolean shuttingDown = false;
    private int consecutiveUpdateFails = 0;
    public static long GetCaltopoRttInMsec() { return CaltopoRttInMsec.get();}
    private CaltopoClientMap.MapStatusListener.mapStatus mapStatus;
    public CaltopoLiveTrack(@NonNull CaltopoClientMap map, @NonNull String groupId,
                            @NonNull CtDroneSpec droneSpec, double lat, double lng,long droneTimestampInMsec) throws RuntimeException {
        if (droneSpec.trackLabel().isEmpty() || groupId.isEmpty()) {
            throw new RuntimeException("CaltopoLiveTrack(): trackLabel and groupId are both required.");
        }
        myMap = map;
        mapStatus = map.getMapStatus();
        CaltopoClientMap.SetMapStatusListener(this);
        myMap.addLiveTrack(this);
        myGroupId = groupId;
        myRemoteId = droneSpec.getRemoteId();
        LiveTrackByRemoteId.put(myRemoteId, this);
        active = true;
        this.droneSpec = droneSpec;
        droneSpec.setMyLiveTrack(this);
        this.r2cStatus = unknown;

        double[] point = {lat, lng, (double)droneTimestampInMsec};
        linePoints.add(point);
        linePointsSentCount = 0;

        if (mapStatus != CaltopoClientMap.MapStatusListener.mapStatus.up) return;
        this.r2cStatus = R2CRest.StatusForNewRemoteId(this, droneSpec, point[1], point[0], (long)point[2]);
        startNewTrack();
    }

    /*
     */
    public void startNewTrack(double lat, double lng, long droneTimestampInMsec) {
        if (shuttingDown) return;

        double[] point = {lat, lng, (double)droneTimestampInMsec};
        linePoints.add(point);
        linePointsSentCount = 0;
        startLiveTrackOp = null;
        liveTrackOp = null;
        if (mapStatus != CaltopoClientMap.MapStatusListener.mapStatus.up) {
            r2cStatus = unknown;
            return;
        }
        r2cStatus = R2CRest.StatusForNewRemoteId(this, droneSpec,  lat, lng, droneTimestampInMsec);
        switch (r2cStatus) {
            case forwardToClient: {
                r2cClient = R2CRest.ClientForRemoteId(myRemoteId);
                if (null == r2cClient) {
                    CTError(TAG, "startNewTrack(): no client to forward to - ignoring.");
                }
                break;
            }
            case okToPublishLocally: {
                startNewTrack();
            }
        }
    }

    public void mapStatusUpdate(CaltopoClientMap map, CaltopoClientMap.MapStatusListener.mapStatus mapStatusIn) {
        if (map == myMap) {
            mapStatus = mapStatusIn;
            if (mapStatusIn == CaltopoClientMap.MapStatusListener.mapStatus.up) {
                folderId = myMap.getFolderId();
            }
        }
    }

    public boolean publishingLocally() {
        return (mapStatus == CaltopoClientMap.MapStatusListener.mapStatus.up && r2cStatus == okToPublishLocally);
    }

    /* Return -1 if no corresponding point */
    public long getFirstTimestamp() {
        if (linePoints.isEmpty()) return -1;
        double[] point = linePoints.getFirst();
        return (long)point[2];
    }

    public static CaltopoLiveTrack GetLiveTrackForRemoteId(@NonNull String remoteId) {
        return LiveTrackByRemoteId.get(remoteId);
    }

    public void shutdown(long maxWaitInMilliseconds) {
        shuttingDown = true;
        active = false;
        archiveTrackOnCaltopo(maxWaitInMilliseconds);
    }

    /** CaltopoClientMap periodically checks for updates to map features
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
        }
        JSONArray jsonArray = new JSONArray();
        for (int i = 0; i < size; i++) {
            double[] point = linePoints.get(i);
            JSONArray pointArray = new JSONArray();
            pointArray.put(String.format(Locale.US, "%.7f", point[1]));
            pointArray.put(String.format(Locale.US, "%.7f", point[0]));
            jsonArray.put(pointArray);
        }
        String archiveFolderId = myMap.getArchiveFolderId();
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
            myMap.archiveFeature(feature, "LiveTrack", System.currentTimeMillis(), maxWaitInMilliseconds);
        } else {
            // for some reason, we weren't able to start the live track, so this will likely block as well
            try {
                myMap.session().addLine(jsonArray, trackLabel, "", "", archiveFolderId,
                        CaltopoClientMap.ArchiveLineProp, null);
            } catch (Exception e) {
                CTError(TAG, "archiveTrackCaltopo() addLine() raised - for no apparent reason.", e);
            }
        }
        resetLiveTrack();
    }
    private void resetLiveTrack() {
        linePoints.clear();
        liveTrackId = null;
        linePointsSentCount = 0;
        startLiveTrackOp = null;
        active = false;
    }

    public String getTrackLabel() {
        if (isActive()) return droneSpec.trackLabel();
        return "<not active>";
    }

    public void renameTrackCompleted() {
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
            renameTrackOp = myMap.session().editObjectWithId("LiveTrack", liveTrackId, feature, this::renameTrackCompleted);
        } catch (Exception e) {
            CTError(TAG, "renameTrack() raised.", e);
        }
    }

    private void startNewTrack() {
        if (null == startLiveTrackOp && okToPublishLocally == r2cStatus) {
            liveTrackId = null;
            liveTrackOp = null;
            linePointsSentCount = 0;
            String trackLabel = droneSpec.trackLabel();
            CTDebug(TAG, String.format(Locale.US, "startNewTrack(%s-%s): Starting LiveTrack w/label:%s in folder:%s",
                    myGroupId, myRemoteId, trackLabel, folderId));
            try {
                startLiveTrackOp = myMap.session().startLiveTrack(myGroupId, myRemoteId, trackLabel, folderId,
                        null, null, this::startLiveTrackComplete);
                forwardNextWaypoint(); // We've got at least one waypoint - get it on it's way.
            } catch (Exception e) {
                CTError(TAG, "startNewTrack(): startLiveTrack() raised: ", e);
            }
        }
    }

    public void finishTrack(@NonNull String reason) {
        if (r2cStatus == okToPublishLocally) R2CRest.SendDropDrone(myRemoteId);
        CTDebug(TAG, "finishTrack(): " + reason);
        if (active && null != liveTrackId) try {
            myMap.removeLiveTrack(liveTrackId);
            archiveTrackOnCaltopo(0);
        } catch (Exception e) {
            CTError(TAG, String.format(Locale.US, "finishTrack(%s) '%s' failed:", droneSpec.trackLabel(), reason), e);
        }
    }

    public boolean isActive() {return active; }

    public void updateStatus(R2CRest.R2CRespEnum status) {
        if (shuttingDown) return;
        CTDebug(TAG, String.format(Locale.US,
                "updateStatus() - changing from '%s' to '%s'", r2cStatus.toString(), status.toString()));
        r2cStatus = status;
        if (status == R2CRest.R2CRespEnum.reevaluate) {
            reevaluate();
            CTDebug(TAG, "updateStatus() - reevaluate changed status to: " + r2cStatus.toString());
        }
        switch (r2cStatus) {
            case forwardToClient: r2cClient = R2CRest.ClientForRemoteId(myRemoteId); break;
            case okToPublishLocally: startNewTrack(); break;
        }
    }

    /** reevaluate()
     * This gets invoked by an R2C instance when a peer releases ownership of this drone.
     * That can happen when the R2C instance shuts down or when it hasn't seen the drone
     * in newtrackdelayinseconds.
     */
    private void reevaluate() {
        r2cClient = null;
        if (active) {
            // FIXME: Only want to follow this path if we've seen recent points, otherwise let it be.
            long minAge = System.currentTimeMillis() - (CaltopoClient.GetNewTrackDelayInSeconds() * 1000);

            while (!linePoints.isEmpty()) {
                double[] point;
                point = linePoints.getFirst();
                if ((long) point[2] <= minAge) {
                    linePoints.removeFirst();
                } else break;
            }
            linePointsSentCount = 0;
            if (linePoints.size() > 1) {
                double[] point = linePoints.getFirst();
                r2cStatus = R2CRest.StatusForNewRemoteId(this, droneSpec, point[0], point[1], (long) point[2]);
                CTDebug(TAG, "reevaluate(): " + r2cStatus.toString());
                return;
            }
            active = false; // no current waypoints
        }
        r2cStatus = unknown;
    }

    private void startLiveTrackComplete() {
        String trackLabel = droneSpec.trackLabel();
        if (startLiveTrackOp.fail()) {
            CTError(TAG, String.format(Locale.US, "Not able to open LiveTrack for:'%s-%s':\n  %s",
                    myGroupId, trackLabel, startLiveTrackOp.responseString()));
            finishTrack("Not able to open/write LiveTrack");
        } else try {
            liveTrackId = startLiveTrackOp.id();
            CTDebug(TAG, String.format(Locale.US, "startLiveTrackComplete(%s): liveTrackId: '%s'",
                    trackLabel, liveTrackId));
            myMap.addLiveTrack(liveTrackId, this);
        } catch (Exception e) {
            CTError(TAG, "startLiveTrackComplete(): raised:", e);
        }
        forwardNextWaypoint();
    }

    public void publishDirect(double lat, double lng, long altitudeInMeters, long droneTimestampInMillisec) {
        double[] point = {lat, lng, (double)droneTimestampInMillisec};
        linePoints.add(point);
        CTDebug(TAG, String.format(Locale.US,
                "publishDirect(%s/%s): added waypoint to queue. size is %d",
                droneSpec.trackLabel(), r2cStatus.toString(), linePoints.size()));
        if (mapStatus != CaltopoClientMap.MapStatusListener.mapStatus.up) return;
        switch (r2cStatus) {
            case forwardToClient: {
                if (null == r2cClient) r2cClient = R2CRest.ClientForRemoteId(myRemoteId);
                if (null != r2cClient) {
                    r2cClient.reportSeen(droneSpec, lat, lng, droneTimestampInMillisec);
                } else {
                    CTError(TAG, "publishDirect(): no client to forward to.");
                }
                return;
            }
            case pending:break;
            case unknown: {
                this.r2cStatus = R2CRest.StatusForNewRemoteId(this, droneSpec, point[1], point[0], (long)point[2]);
                break;
            }
            case okToPublishLocally: {
                if (null != liveTrackId) forwardNextWaypoint();
                else if (null == startLiveTrackOp) startNewTrack();
            }
        }
    }

    /** forwardNextWaypoint():
     *  Pull waypoints off the queue and forward to Caltopo
     */
    public void forwardNextWaypoint() {
        if (shuttingDown || !active) {
            CTDebug(TAG, "forwardNextWaypoint(): no longer active - stopping.");
            return; // signals for send no more waypoints.
        }
        if (null == liveTrackOp || liveTrackOp.isDone()) {
            if (null != liveTrackOp && liveTrackOp.isDone()) {
                CaltopoRttInMsec.next(liveTrackOp.roundTripTimeInMsec());
                if (liveTrackOp.fail()) {
                    consecutiveUpdateFails++;
                    CTError(TAG, "forwardNextWaypoint(): addLiveTrackPoint failed: " + liveTrackOp.response);
                    if (consecutiveUpdateFails > 2) {
                        CTError(TAG, "forwardNextWaypoint(): shutting down after several consecutive update failures. ");
                        active = false;
                        return;
                    }
                } else {
                    consecutiveUpdateFails = 0;
                }
            }
            try {
                int pointCount = linePoints.size();
                if (linePointsSentCount < pointCount) {
                    double[] point = linePoints.get(linePointsSentCount++);
                    CTDebug(TAG, String.format(Locale.US, "forwardNextWaypoint(%s-%s#%d): adding %.7f,%.7f to LiveTrack.  Avg rtt is %.3f seconds.",
                            myGroupId, myRemoteId, linePointsSentCount, point[0], point[1], (double)CaltopoRttInMsec.get() / 1000.0));
                    liveTrackOp = myMap.session().addLiveTrackPoint(myGroupId, myRemoteId, point[0], point[1], this::forwardNextWaypoint);
                }
            } catch (Exception e) {
                CTError(TAG, "forwardNextWaypoint(): addLiveTrackPoint() raised: ", e);
            }
        }
    }
}
