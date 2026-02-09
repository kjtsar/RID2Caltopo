/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn;
import static org.ncssar.rid2caltopo.data.CaltopoClient.ShowToast;
import static org.ncssar.rid2caltopo.data.CaltopoCredentials.credentialsAreEqual;
import static org.ncssar.rid2caltopo.data.CaltopoMapHierarchyKt.parseMapHierarchy;
import static org.ncssar.rid2caltopo.data.SimpleTimer.DurationAsString;

import org.ncssar.rid2caltopo.app.R2CActivity;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.location.Location;
import android.os.Bundle;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.ncssar.rid2caltopo.app.R2CApplication;
import org.ncssar.rid2caltopo.ui.R2CViewModel;


import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** CaltopoMap class
 *   Support bringing up the map session and creating the drone track folders (if not
 * already present).
 * The archive folder starts with the drone track folder name and ends with the
 * current date (i.e. 25Sep).
 *   Additionally, if there are any peer R2C Instances present on the map, then
 * attempt to bring up their corresponding R2CPeers before indicating the
 * map is up.
 * FIXME:  Are there going to be scenarios where we want to skip the R2CPeer
 *     connectivity?  Maybe a slider in the settings panel that says "ignore
 *     peers" or some such and just deal with the duplicated track points...
 */
public class CaltopoMap implements R2CPeer.R2CListener {
    public interface MapStatusListener {
        enum mapStatus {
            down,
            credentialsVerified,
            connecting,
            up
        }

        /** mapStatusUpdate():
         *
         * @param status  Sent any time map status changes.
         * @param mapNode The map who's status has changed - if known.
         * @param optErrmsg If map processing encountered an error this may qualify the status.
         */
        void mapStatusUpdate(mapStatus status, @Nullable CaltopoNode.MapNode mapNode,  @Nullable String optErrmsg);
    }
    private static final String TAG = "CaltopoMap";
    private static CaltopoSession Csp;
    private static String MyUUID = null;
    public static android.location.Location MyLocation;
    private static final long FirstMapUpdateTimeInSeconds = 15;
    private static final long RepeatMapUpdateTimeInSeconds = 90;
    public static final CtLineProperty ArchiveLineProp =
            new CtLineProperty(2, 0.5F, "#ff00ff", "solid");
    private static final int MAX_MAP_STARTUP_DELAY_IN_SECONDS = 45;

    // my peers by UUID:
    private static final Hashtable<String, R2CPeer>PeerIdMap = new Hashtable<>(16);

    // poll the map to see if there are any new r2c peers or user has changed one of our track labels
    private static final DelayedExec MapCheckerDelay = new DelayedExec();
    private static String FolderId;
    private static CaltopoOp MyMarkerOp;
    private static String ArchiveFolderId;
    private static CaltopoNode.MapNode MapNode;
    private static String FolderName;
    private static MapStatusListener.mapStatus MapStatus = MapStatusListener.mapStatus.down;
    private static int WaitForGpsAccuracy;
    private static boolean UsePeersFlag;
    private static JSONArray R2cPeers; // list of r2cPeerSpecs for peers listed on our map.
    private static long LastMapSync;

    // liveTracks that we are writing into the map keyed by their map ID:
    private static final Hashtable<String, CaltopoLiveTrack> LiveTracksById = new Hashtable<>();

    // All liveTracks, including those not yet/ever writing to the map:
    private static final ArrayList<CaltopoLiveTrack> liveTracks = new ArrayList<>(16);

    private static final ArrayList<JSONObject> RogueFeaturesPendingDeletes = new ArrayList<>();

    private static final HashSet<MapStatusListener> MapListeners = new HashSet<>();

    private static JSONArray MyLiveTracksInThisMap;   // Actual 'LiveTrack' objects in the current map
    private static String LastErrorString = null;
    private static CaltopoCredentials MyCaltopoCredentials;
    private static String DomainAndPort;
    private static List<CaltopoNode> SessionNodeMap = null;
    private static CaltopoMap MyInstance = null; // keep around just to serve as listener.

    private static CaltopoOp VerifyOp = null;
    private static DelayedExec VerifyTimer = new DelayedExec();
    private static final long VERIFY_TIMEOUT_MS = 10 * 1000L;
    public static List<CaltopoNode>GetSessionNodeMap() { return SessionNodeMap;}

    public static void SessionVerifyCallback(@NonNull CaltopoOp verifyOp) {
        VerifyTimer.stop();
        VerifyOp = null;
        if (verifyOp.success()) {
            CTDebug(TAG, String.format(Locale.US,
                    "SessionVerifyCallback(): Parsing team data. queued:%d, sent:%d, received:%d",
                    verifyOp.queuedTimestampMsec, verifyOp.sentTimestampMsec, verifyOp.receivedTimestampMsec ));
            if (CaltopoClient.DebugLevel >= CaltopoClient.DebugLevelInfo) {
                DumpJsonStructure(verifyOp.responseJson, "account");
            }
            SessionNodeMap = parseMapHierarchy(verifyOp.responseJson);
            CTDebug(TAG, String.format(Locale.US,
                    "parseMapHierarchy() returned list with %d top-level items", SessionNodeMap.size()));
            SetMapStatus(MapStatusListener.mapStatus.credentialsVerified, null);
        } else {
            String emsg = String.format(Locale.US,
                    "Not able to verify credentials - code:%s, reason:%s",
                    verifyOp.responseCode, verifyOp.response);
            LastErrorString = emsg;
            ShowToast(emsg);
            SetMapStatus(MapStatusListener.mapStatus.down, emsg);
            SessionNodeMap = null;
        }
    }

    /***
     *
     * @return non-null diagnostic string immediately if not able to proceed, otherwise
     *         kicks off asynchronous connection verification process and returns null.
     */
    @Nullable
    public static String Init() {
        CTDebug(TAG, "Init()");
        if (null == MyInstance)
            MyInstance = new CaltopoMap(); // needed to receive notifications only.

        CaltopoCredentials sessionCredentials = CaltopoClient.GetCaltopoCredentials();
        if (!CaltopoCredentials.sniffTest(sessionCredentials)) {
            return "Missing required credentials";
        }
        DomainAndPort = CaltopoClient.GetCaltopoDomainAndPort();
        if (null == DomainAndPort || DomainAndPort.isEmpty()) {
            DomainAndPort = "caltopo.com";
        }

        MyCaltopoCredentials = sessionCredentials;
        SessionNodeMap = null;
        VerifyOp = null;
        CTDebug(TAG, "Init(): Initializing session...");
        CaltopoSession.Init(MyCaltopoCredentials, DomainAndPort);
        CTDebug(TAG, "Init(): Verifying Credentials...");
        VerifyTimer.start(CaltopoMap::VerifyTimeout, VERIFY_TIMEOUT_MS, 0);
        VerifyOp = CaltopoSession.VerifyAccount(CaltopoMap::SessionVerifyCallback);
        UsePeersFlag = CaltopoClient.GetUsePeersFlag();
        if (UsePeersFlag) R2CPeer.Init();
        FolderName = CaltopoClient.GetTrackFolderName();
        if (FolderName.isEmpty()) FolderName = "DroneTracks";
        if (null == MyUUID) GetMyUUID();
        return null;
    }
    private static void VerifyTimeout() {
        LastErrorString = "Timeout waiting for Caltopo response... Is the network down?";
        SetMapStatus(MapStatusListener.mapStatus.down, LastErrorString);
    }

    CaltopoMap() {
        if (null != MyInstance) {
            throw new RuntimeException("CaltopoMap instances not supported");
        }
        MyInstance = this;
    }

    public static CaltopoNode.MapNode GetMapNode() {
        return MapNode;
    }

    public static void LogJsonStructure(String keyName, Object value, int depth) {
        String indent = new String(new char[depth * 2]).replace("\0", "  ");

        if (value instanceof JSONObject obj) {
            // Log that we are entering an object
            CTInfo(TAG, indent + "OBJ: " + keyName);

            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object child = obj.opt(key);
                LogJsonStructure(key, child, depth + 1);
            }
        }
        else if (value instanceof JSONArray array) {
            CTInfo(TAG, indent + "ARR: " + keyName + " [Len: " + array.length() + "]");

            if (array.length() > 0) {
                Object firstChild = array.opt(0);
                // We only recurse into the first element to see the "shape" of the array items
                // We use "[0]" to indicate we are looking at the structure of an array member
                LogJsonStructure(keyName + "[0]", firstChild, depth + 1);
            }
        }
        else if (value != null) {
            // This is a leaf node (String, Long, Boolean, etc.)
            CTInfo(TAG, indent + "VAL: " + keyName + " (" + value.getClass().getSimpleName() + " = " + value.toString() + ")");
        }
    }

    public static void DumpJsonStructure(JSONObject jo, String name) {
        CTInfo(TAG, "--- start " + name + " structure graph");
        LogJsonStructure("ROOT", jo, 0);
        CTInfo(TAG, "--- end " + name + " structure graph");
    }

    /***
     *
     * @param mapNode  : If null, just reset map connection.
     */
    public static void OpenMap(CaltopoNode.MapNode mapNode) {
        if (MapNode != null) {
            // don't wait around for reset operations to complete:
            ResetMapConnection(0);
        }
        MapNode = mapNode;
        if (null == MapNode) {
            CTDebug(TAG, "OpenMap(): Map connection reset.");
            SetMapStatus(MapStatusListener.mapStatus.down, "Disconnect request.");
            return;
        }

        SetMapStatus(MapStatusListener.mapStatus.connecting, null);
        try {
            CTDebug(TAG, String.format(Locale.US, "Connecting to map '%s'(%s)'", MapNode.getTitle(), MapNode.getId()));
            CaltopoSession.OpenMap(MapNode, 0, CaltopoMap::OpenMapFinished);

        } catch (Exception e) {
            String emsg = "OpenMap(): CaltopoSession.OpenMap() barfed";
            CTError(TAG, emsg, e);
            SetMapStatus(MapStatusListener.mapStatus.down, emsg);
        }
    }

    public static void SetMapStatusListener(@NonNull MapStatusListener listener) {
        MapListeners.add(listener);
    }

    public static void RemoveMapStatusListener(@NonNull MapStatusListener listener) {
        MapListeners.remove(listener);
    }

    public static void AddLiveTrack(@NonNull CaltopoLiveTrack track) {
        liveTracks.add(track);
    }

    public static void AddLiveTrack(@NonNull String trackId, @NonNull CaltopoLiveTrack track) {
        CTDebug(TAG, "addLiveTrack(): adding liveTrack with id: " + trackId);
        LiveTracksById.put(trackId, track);
    }
    public static void RemoveLiveTrack(@NonNull String trackId) {
        CaltopoLiveTrack liveTrack = LiveTracksById.remove(trackId);
        if (null != liveTrack) {
            CTDebug(TAG, String.format(Locale.US,
                    "removeLiveTrack(%s): removing liveTrack %s",
                    GetMapId(), liveTrack.getTrackLabel()));
        }
    }

    public static float DistanceFromMeInMeters(double lat, double lng) {
        float[] dbResult = {Float.NaN};
        if (null == MyLocation || !MyLocation.hasAccuracy()) return Float.NaN;
        Location.distanceBetween(lat, lng, MyLocation.getLatitude(), MyLocation.getLongitude(), dbResult);
        return dbResult[0];
    }

    /**
     *  Asynch feedback from peer that the connection to a peer was either successful or failed.
     *
     */
    public void peerStatusChange(R2CPeer peer, r2cState state) {
        CTDebug(TAG, String.format(Locale.US,
                "Received peerStatusChange(%s) from %s.", state, peer.getPeerName()));

        if (state == r2cState.up) {
            AddPeer(peer);
        } else {
            RemovePeer(peer, state);
            if (r2cState.failed == state) {
                // FIXME: Couldn't make contact with specified peer.   If it's marker hasn't
                //  been updated in a while, we should probably archive/remove it.
            }
        }
        //  any LiveTracks that might have popped up while we were bringing up the map.
        CaltopoLiveTrack.ReevalUnknownAndPendingTracks();
    }

    @NonNull
    public static String GetMyUUID() {
        if (null != MyUUID) return MyUUID;
        Context ctxt = R2CApplication.getAppCtxt();
        if (null == ctxt) {
            DelayedExec.RunAfterDelayInMsec(CaltopoMap::GetMyUUID, 1000);
            CTDebug(TAG, "GetMyUUID() waiting for app to initialize...");
            return "";
        }
        ContentResolver contentResolver = ctxt.getContentResolver();
        // android studio warns: "Using 'GetString' to get device identifiers is not recommended",
        // but nothing in the method spec mentions this...  They might be concerned we're using
        // this for advertising, but no - just to uniquely identify the device to Caltopo Server.
        String androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID);
        UUID deviceUuid = UUID.nameUUIDFromBytes(androidId.getBytes(StandardCharsets.UTF_8));
        MyUUID = deviceUuid.toString();
        return MyUUID;
    }


    public static void ResetMapConnection(long maxWaitInMilliseconds) {
        if (MapStatus == MapStatusListener.mapStatus.down) return;
        MapCheckerDelay.stop();
        long startTime = System.currentTimeMillis();
        CaltopoOp deleteOp = null;
        if (UsePeersFlag) {
            deleteOp = CaltopoSession.DeleteMarkerWithId(MyUUID, null);
        }
        for (CaltopoLiveTrack track : LiveTracksById.values()) {
            track.shutdown(maxWaitInMilliseconds);
            if (0 != maxWaitInMilliseconds)
                maxWaitInMilliseconds = (maxWaitInMilliseconds - (System.currentTimeMillis() - startTime));
        }
        LiveTracksById.clear();
        if (UsePeersFlag) {
            ArrayList<R2CPeer> peers = new ArrayList<>(PeerIdMap.size());
            peers.addAll(PeerIdMap.values());
            for (R2CPeer peer : peers) {
                CTDebug(TAG, String.format(Locale.US,
                        "resetMapConnection(%d) shutting down connection to %s due to map closure.",
                        maxWaitInMilliseconds, peer.getPeerName()));
                peer.shutdown(R2CPeer.R2CListener.r2cState.down);
            }
        }
        PeerIdMap.clear();
        FolderId = null;
        ArchiveFolderId = null;
        LastErrorString = null;
        if (UsePeersFlag && null != deleteOp) {
            if (maxWaitInMilliseconds > 0) {
                try {
                    deleteOp.syncOp(maxWaitInMilliseconds + 250);
                } catch (Exception e) {
                    CTWarn(TAG, String.format(Locale.US,
                            "resetMapConnection(%d): my marker delete raised:",
                            maxWaitInMilliseconds), e);
                }
            }
        }
    }

    @NonNull
    public static String GetMapId() {
        return (null != MapNode) ? MapNode.getId(): "";
    }
    private static void CreateArchiveDirFinished(CaltopoOp archiveFolderIdOp) {
        if (archiveFolderIdOp.fail()) {

            ShowToast(String.format(Locale.US,
                    "Could not create archive folder in map '%s' - check mapId/permissions:\n  %s",
                    GetMapId(), archiveFolderIdOp.responseString()));
            return;
        }
        ArchiveFolderId = archiveFolderIdOp.id();
        CTDebug(TAG, String.format(Locale.US, "archive folder id is %s", ArchiveFolderId));
        LookForExistingLiveTracks();
    }

    private static void CreateTrackDirFinished(CaltopoOp folderIdOp) {
        if (folderIdOp.fail()) {
            ShowToast(String.format(Locale.US,
                    "Could not create track folder in map '%s' - check mapId/permissions:\n  %s",
                    GetMapId(), folderIdOp.responseString()));
            return;
        }
        FolderId = folderIdOp.id();
        CTDebug(TAG, String.format(Locale.US, "track folder id is %s", FolderId));
    }

    /** parseMapUpdate()
     * Different from parseMap() in that we already have an established map w/DroneTracks and
     * Archive directories.  Now we're just interested in taking a peek at the contents of
     * the DroneTracks directory to see if there are any new Markers for R2C instances we
     * don't know about yet that haven't initiated contact with us.   This can happen if
     * there was a race condition at start-up and we both looked at the map and determined
     * that there were no other R2C instances out there when we started.
     * @param state  This contains all the map changes since our last visit.
     */
    private static void ParseMapUpdate(JSONObject state) {
        if (state == null) {
            CTDebug(TAG, "parseMapUpdate(): response missing required state.");
            return;
        }
        JSONArray features = state.optJSONArray("features");
        int count = (null == features) ? 0: features.length();
        int newCount = 0, ignoreCount = 0;
        for (int i = 0; i < count; i++) {
            JSONObject feature = features.optJSONObject(i);
            JSONObject prop = feature.optJSONObject("properties");
            if (null == prop) {
                CTDebug(TAG, "parseMapUpdate(): Feature missing required properties parameter.");
                ignoreCount++;
                continue;
            }
            String thisFolderId = prop.optString("folderId");
            String classStr = prop.optString("class");
            if (!FolderId.equals(thisFolderId) && !classStr.equals("Folder")) {
                if (CaltopoClient.DebugLevel > CaltopoClient.DebugLevelDebug) {
                    CTInfo(TAG, String.format(Locale.US, "parseMapUpdate(): feature folder id '%s' doesn't match our folder id '%s': %s",
                            thisFolderId, FolderId, feature));
                }
                ignoreCount++;
                continue;
            }
            String classString = prop.optString("class");
            String idString = feature.optString("id");
            if ("LiveTrack".equals(classString)) {
                String title = prop.optString("title");
                CTDebug(TAG, "parseMapUpdate(): Found liveTrack '" + title + "' with id: " + idString);
                CaltopoLiveTrack liveTrack = LiveTracksById.get(idString);
                if (null != liveTrack) {
                    CTDebug(TAG, "parseMapUpdate(): liveTrack is one of ours - checking");
                    liveTrack.checkCaltopoTrackLabel(title);
                } else {
                    CTDebug(TAG, "parseMapUpdate(): liveTrack is not one of ours - ignoring");
                    ignoreCount++;
                }
            } else if ("Marker".equals(classString)) {
                if (!UsePeersFlag || MyUUID.equals(idString) || PeerIdMap.containsKey(idString)) continue;
                String ipAddrsString = prop.optString("r2c-ipaddrs");
                if (!ipAddrsString.isEmpty()) {
                    newCount++;
                    CTDebug(TAG, "parseMapUpdate(): found new peer: " + feature);
                    JSONObject peerMarkerSpec;
                    try {
                        JSONArray ipAddrsObj = new JSONArray(ipAddrsString);
                        peerMarkerSpec = ParseR2cMarker(ipAddrsObj, feature, prop);
                        R2CPeer peer = R2CPeer.PeerForRemoteR2c(peerMarkerSpec, MyInstance);
                        AddPeer(peer);
                    } catch (Exception e) {
                        CTError(TAG, "Error parsing ipaddrstring", e);
                    }
                } else {
                    CTDebug(TAG, "parseMapUpdate(): ignoring non-R2C marker in our folder:\n" + feature);
                }
            }
        }
        if (newCount > 0 || ignoreCount > 0) {
            CTDebug(TAG, String.format(Locale.US,
                    "parseMapUpdate() ignored %d new features.\n  Found %d new peers since my last visit.",
                    ignoreCount, newCount));
        }
    }

    /* Parse the feature set returned by the openMap()
     * to look for our track directory and it's companion archive dir.
     * Also make a list of all other LiveTracks that might be leftover old
     * tracks in need of archival.
     * TODO: It seems like this could take a long time on a multi-op period search, so
     *  we might want to hand this off to a background thread.   Need to instrument the
     *  entire process (i.e. fetch map, parse map, connect to peers) for a big map to see
     *  how much time is required.  Also consider running this app on faster devices.
     */
    private static void ParseMap(JSONObject state)
            throws RuntimeException, JSONException {

        MyLiveTracksInThisMap = new JSONArray();
        JSONArray markerFeatures = new JSONArray();
        SimpleDateFormat sdf = new SimpleDateFormat("ddMMM", Locale.US);
        String archiveFolderName = FolderName + sdf.format(new Date());

        CTInfo(TAG, String.format(Locale.US,
                "parseMap() Checking map for folders: '%s' and '%s'",
                FolderName, archiveFolderName));

        JSONArray features = state.getJSONArray("features");

        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.getJSONObject(i);
            CTInfo(TAG, "parseMap(): Parsing returned feature:\n" + feature.toString(2));
            JSONObject prop = feature.optJSONObject("properties");
            if (null == prop) {
                CTError(TAG, "parseMap(): feature missing 'properties' - skipping:" + feature);
                continue;
            }
            String title = prop.optString("title");
            if (title.isEmpty()) {
                // This happened a few times during debug with Caltopo's v1 API.
                // No way to delete these runt features w/in the GUI, so we add them to a
                // list and wait until after we have a folderId and can confirm that the
                // feature is within either of our directories before deleting.  We don't
                // delete anything that we didn't create.
                CTDebug(TAG, "parseMap(): found rogue feature missing title: " + feature.toString(4));
                RogueFeaturesPendingDeletes.add(feature);
                continue;
            }

            String classProp = prop.optString("class", "");
            switch (classProp) {
                case "": CTError(TAG, "parseMap(): feature missing class: " + feature.toString(4)); break;
                case "Marker": markerFeatures.put(feature); break;
                case "LiveTrack":
                    // collect the list of features that might be ours - don't know if they
                    // are in our folder yet, because we may not even have folders.
                        MyLiveTracksInThisMap.put(feature);
                        break;
                case "Folder": {
                    if (FolderId == null && title.equals(FolderName)) {
                        FolderId = feature.getString("id");
                        CTDebug(TAG, String.format(Locale.US,
                                "Found existing folder '%s' with id %s", FolderName, FolderId));
                    } else if (null == ArchiveFolderId && title.equals(archiveFolderName)) {
                        ArchiveFolderId = feature.getString("id");
                        CTDebug(TAG, String.format(Locale.US,
                                "Found existing folder '%s' with id %s", archiveFolderName, ArchiveFolderId));
                    }
                    break;
                }
            }
        }

        // Request the directories to be created if they weren't found in the existing map:
        if (null == FolderId) {
            CTInfo(TAG, String.format(Locale.US,
                    "parseMap() '%s' folder not found - creating...", FolderName));
            CaltopoSession.AddFolder(FolderName, true, true, CaltopoMap::CreateTrackDirFinished);
        }
        if (UsePeersFlag) FindR2cPeers(markerFeatures);

        if (null == ArchiveFolderId) {
            CTInfo(TAG, String.format(Locale.US,
                    "parseMap() '%s' folder not found - creating...", archiveFolderName));
            CaltopoSession.AddFolder(archiveFolderName, false, false, CaltopoMap::CreateArchiveDirFinished);
        } else LookForExistingLiveTracks();
    }

    private static void PollMapUpdates() {
        // Our marker feature s/b/ valid at this point, so start polling for updates...
        CTInfo(TAG, "PollMapUpdates(): updating map connection()");
        long mapSync = System.currentTimeMillis();
        CaltopoSession.OpenMap(MapNode, LastMapSync, CaltopoMap::UpdateMapFinished);
        LastMapSync = mapSync;
    }

    private static void UpdateMapFinished(CaltopoOp updateMapOp) {
        CTInfo(TAG, "updateMapFinished()");
        if (updateMapOp == null || updateMapOp.fail()) {
            CTError(TAG, String.format(Locale.US, "Not able to update map '%s':\n  %s",
                    GetMapId(), updateMapOp));
            MapCheckerDelay.stop();
            return;
        }

        if (CaltopoClient.DebugLevel > CaltopoClient.DebugLevelDebug) {
            CTInfo(TAG, "updateMapFinished() dumping map updates to logfile...");
            CTInfo(TAG, updateMapOp.responseString());
        }
        if (null != updateMapOp.responseJson) {
            JSONObject stateObj = updateMapOp.responseJson.optJSONObject("state");
            if (null != stateObj) try {
                ParseMapUpdate(stateObj);
            } catch (Exception e) {
                CTError(TAG, "updateMapFinished(): parseMapUpdate() raised. ", e);
            }
        }

        SetMapStatus(MapStatusListener.mapStatus.up, null);
    }

    public static void SubmitClue(
            CtDroneSpec droneSpec,
            double clueLat, double clueLng, double clueAlt,
            String clueTitle,
            String clueDescription,
            long clueTimestamp,
            Bitmap clueImage) {
        CTDebug(TAG, "SubmitClue()...");
    }
    private static void SetMapStatus(MapStatusListener.mapStatus mapStatus, @Nullable String optEmsg) {

        Bundle parameters = new Bundle();
        parameters.putString("r2c_mapId", MapNode != null ? MapNode.getTitle(): "");
        parameters.putInt("r2c_listenerCount", MapListeners.size());
        parameters.putInt("r2c_featDeletePending", RogueFeaturesPendingDeletes.size());
        CaltopoClient.CTEvent(TAG, "MapIs_" + mapStatus.toString(), parameters);
        MapStatus = mapStatus;
        if (!MapListeners.isEmpty()) {
            for (MapStatusListener Listener : MapListeners) Listener.mapStatusUpdate(MapStatus, MapNode, optEmsg);
        }
        if (mapStatus == MapStatusListener.mapStatus.up) {
            while (!RogueFeaturesPendingDeletes.isEmpty()) {
                JSONObject feature = RogueFeaturesPendingDeletes.remove(0);
                JSONObject prop = feature.optJSONObject("properties");
                if (null != prop) {
                    String featureFolderId = prop.optString("folderId");
                    if (featureFolderId.equals(FolderId) || featureFolderId.equals(ArchiveFolderId)) {
                        // then it's a feature fragment that we created, so we can/should delete it.
                        String featureId = feature.optString("id");
                        CaltopoSession.DeleteShapeWithId(featureId, null);
                    }
                }
            }
        }
    }

    /**
     * Called when openMapOp completed.
     * o Parse the returned map, look for existing TrackDir and ArchiveDir.
     * o Also look for any old live tracks that didn't get archived (happens
     * when the app was terminated mid-record).
     * o Create TrackDir and ArchiveDir if they weren't already present.
     */
    private static void OpenMapFinished(CaltopoOp lOpenMapOp) {
        if (null == MapNode) return;
        if (lOpenMapOp.fail()) {
            if (null != lOpenMapOp.response && lOpenMapOp.response.startsWith("<!DOCTYPE html>")) {
                // Not going to figure this out on our own - write response to a file and open it with a browser:
                CaltopoClient.OpenBufferContentsInBrowser(lOpenMapOp.response);
            } else {
                LastErrorString = String.format(Locale.US, "OpenMapFinished(): Not able to open map '%s'. code:%d, reason:%s",
                        MapNode != null ? MapNode.getId(): "", lOpenMapOp.responseCode, lOpenMapOp.response);
                ShowToast(LastErrorString);
            }
            MapNode = null;
            return;
        }

        JSONObject responseJson = lOpenMapOp.responseJson;
        JSONObject state = (responseJson != null) ? responseJson.optJSONObject("state") : null;
        if (null == state) {
            CTError(TAG, "OpenMapFinished(): state missing from response: " + lOpenMapOp.response );
        } else try {
            ParseMap(state);
        } catch (Exception e) {
            CTError(TAG, "OpenMapFinished(): parseMap() raised:", e);
        }
        if (!MapCheckerDelay.isRunning()) {
            CTDebug(TAG, "openMapFinished(): starting map checker delay...");
            MapCheckerDelay.start(
                    CaltopoMap::PollMapUpdates, FirstMapUpdateTimeInSeconds * 1000,
                    RepeatMapUpdateTimeInSeconds * 1000);
        }
    }

    private static JSONObject ParseR2cMarker(JSONArray ipAddrsObj, JSONObject feature, JSONObject prop) throws JSONException {
        JSONObject marker = new JSONObject();
        marker.put("ipaddrs", ipAddrsObj);
        marker.put("name", prop.optString("r2c-name"));
        marker.put("id", feature.optString("id"));
        marker.put("feature", feature);
        JSONObject geometry = feature.optJSONObject("geometry");
        if (null != geometry) {
            JSONArray coordinates = geometry.optJSONArray("coordinates");
            if (null != coordinates && coordinates.length() > 1) {
                marker.put("lat", coordinates.optString(1));
                marker.put("lng", coordinates.optString(0));
            }
        }
        return marker;
    }


    /* Returns null if the map isn't up and/or the track folder isn't yet known,
     * otherwise returns an array of any host entries that were found, each of the form:
     *   {
     *      ipaddrs: [{"ipaddr":"<ipaddr1>","intf":"<intf>"},...],
     *      name: <deviceName>,
     *      lat: <lat>,
     *      lng: <lng>,
     *      id: <marker_uuid>
     *   }
     */
    public static void FindR2cPeers(@NonNull JSONArray markerFeatures) {
        R2cPeers = new JSONArray();
        try {
            for (int i = 0; i < markerFeatures.length(); i++) {
                JSONObject feature = markerFeatures.optJSONObject(i);
                JSONObject prop = feature.optJSONObject("properties");
                if (null == prop) continue;
                String featureFolderId = prop.optString("folderId");
                if (featureFolderId.equals(FolderId)) {
                    String ipAddrsString = prop.optString("r2c-ipaddrs");
                    if (!ipAddrsString.isEmpty()) {
                        long peerUpdateTimestamp = prop.optLong("updated", 0);
                        if (peerUpdateTimestamp > 0) {
                            long ageInMsecs = System.currentTimeMillis() - peerUpdateTimestamp;
                            if (ageInMsecs > 2.0*RepeatMapUpdateTimeInSeconds*1000) {
                                CTWarn(TAG, String.format(Locale.US,
                                        "Found %s old stale peer marker in our folder - removing: %s",
                                        DurationAsString(ageInMsecs), feature));
                                CaltopoSession.DeleteMarkerWithId(feature.optString("id"), null);
                                continue;
                            }
                        }
                        JSONArray ipAddrsObj = new JSONArray(ipAddrsString);
                        R2cPeers.put(ParseR2cMarker(ipAddrsObj, feature, prop));
                    } else {
                        CTDebug(TAG, "Ignoring non-R2C marker in our folder:\n" + feature.toString(4));
                    }
                }
            }
            CTDebug(TAG, "getR2cPeer() found peers: " + R2cPeers.toString(4));
        } catch (Exception e) {
            CTError(TAG, "getR2cPeers(): Error parsing map.", e);
        }
        ProcessPeerList();
    }

    /** UpdateMyLocation()
     *  Pay attention to ongoing periodic location updates.  If the accuracy of measurement
     *  has improved, then update our marker's location.   If the distance between our
     *  previous marker location and this new location is greater than 2.5x the least
     *  accurate of our respective markers, then assume we've moved.
     * @param location  The latest location update from the GPS system.
     */
    public static void UpdateMyLocation(@NonNull android.location.Location location) {
        boolean updateNeeded = ( null == MyLocation || !MyLocation.hasAccuracy() );
        double distanceInMeters = 0F;
        if (null != MyLocation && MyLocation.hasAccuracy() && location.hasAccuracy()) {
            distanceInMeters = DistanceFromMeInMeters(location.getLatitude(), location.getLongitude());
            if ((location.getAccuracy() < MyLocation.getAccuracy()) ||
                    (distanceInMeters > (2.5*location.getAccuracy()))) updateNeeded = true;
        }

        if (updateNeeded) {
            if (null == MyLocation) MyLocation = location;
            CTDebug(TAG, String.format(Locale.US,
                    """
                              UpdateMyLocation()
                                new: lat:%.7f, lng:%.7f, accuracy:%.3fm
                                old: lat:%.7f, lng:%.7f, accuracy:%.3fm, distanceInMeters:%.3fm""",
                    location.getLatitude(), location.getLongitude(), location.getAccuracy(),
                    MyLocation.getLatitude(), MyLocation.getLongitude(), MyLocation.getAccuracy(),
                    distanceInMeters));
            MyLocation = location;
            if (UsePeersFlag) UpdateMyMarker(null);
        }
    }

    /* Could not establish a connection to the specified peer or it
     * stopped responding or it sent a message saying it was going away.
     * We need to remove it's marker from our map if we couldn't connect.
     */
    public static void RemovePeer(@NonNull R2CPeer peer, r2cState state) {
        String idString = peer.getRemoteUUID();
        R2CPeer mappedPeer = PeerIdMap.remove(idString);
        if (null != mappedPeer) {
            String mapId = GetMapId();
            CTDebug(TAG, String.format(Locale.US, "Removed R2C peer '%s' from map '%s'",
                    peer.getPeerName(), mapId ));
            if (state == r2cState.failed) {
                // FIXME: is this just a networking problem?   Maybe we should check the age of the artifacts
                //  in our directory and use that to decide if the peer is really not home or if we just have
                //  network connectivity problem.... I don't like deleting other people's stuff.
                CTDebug(TAG, String.format(Locale.US, "Removing %s peer from %s", mappedPeer.getPeerName(), mapId));
                CaltopoSession.DeleteMarkerWithId(mappedPeer.getRemoteUUID(), null);
            }
        }
    }

    @NonNull
    public static String GetLastErrorString() {
        return (null != LastErrorString) ? LastErrorString : "";
    }

    static void AddPeer(@NonNull R2CPeer peer) {
        String idString = peer.getRemoteUUID();
        if (!PeerIdMap.containsKey(idString)) {
            CTDebug(TAG, "Adding R2C peer to our map: " + idString);
            PeerIdMap.put(idString, peer);
        }
    }

    @NonNull public static String GetMapName() {
        return (MapNode != null) ? MapNode.getTitle() : "";
    }
    private static void ProcessPeerList() {
        JSONObject myMarker = null;
        JSONArray myIpAddresses = R2CPeer.GetMyIpAddresses();
        double accuracyInMeters;

        if (null == FolderId || null == ArchiveFolderId) {
            CTDebug(TAG, "processPeerList(): waiting for map processing to complete...");
            DelayedExec.RunAfterDelayInMsec(CaltopoMap::ProcessPeerList, 500);
            return;
        }
        if (0 == myIpAddresses.length() && WaitForGpsAccuracy++ < 5) {
            CTDebug(TAG, "ProcessPeerList(): waiting for internet connectivity...");
            DelayedExec.RunAfterDelayInMsec(CaltopoMap::ProcessPeerList, 500);
            return;
        }
        if (null == MyLocation && WaitForGpsAccuracy++ < MAX_MAP_STARTUP_DELAY_IN_SECONDS) {
            CTDebug(TAG, "ProcessPeerList(): No Location yet...retrying");
            DelayedExec.RunAfterDelayInMsec(CaltopoMap::ProcessPeerList, 1000);
            WaitForGpsAccuracy++;
            return;
        }
        if (null != MyLocation) {
            accuracyInMeters = MyLocation.getAccuracy();
            CTDebug(TAG, String.format(Locale.US, "ProcessPeerList(): My location is %.7f,%.7f w/in %.3f meters. My UUID is %s",
                    MyLocation.getLatitude(), MyLocation.getLongitude(), accuracyInMeters, null == MyUUID ? "unknown":"good"));
        } else {
            CTWarn(TAG, "ProcessPeerList(): bad/no gps - I have no idea where I am.");
        }

        // look for my Marker in the list of peers and fire-off peer connections for the others:
        for (int i=0; i<R2cPeers.length(); i++) {
            JSONObject peer = R2cPeers.optJSONObject(i);
            String peerUUID = peer.optString("id");
            if (peerUUID.equals(MyUUID)) {
                myMarker = peer;
                CTDebug(TAG, "Found marker with my UUID: " + MyUUID);
            } else {
                PeerIdMap.put(peerUUID, R2CPeer.PeerForRemoteR2c(peer, MyInstance));
            }
        }

        long timeNowInMilliseconds = System.currentTimeMillis();
        String timeString = String.valueOf(timeNowInMilliseconds);
        if (null != myMarker) {
            // Not a clean shutdown previously - this can happen when app is terminated while internet is down.
            JSONObject updateFeature = myMarker.optJSONObject("feature");
            UpdateMyMarker(updateFeature);

        } else { // we get to create our marker from scratch - yipee!
            CTDebug(TAG, String.format(Locale.US,
                    "Didn't find our existing marker in %d peers, so adding a new one:", R2cPeers.length()));
            JSONObject prop = new JSONObject();
            try {
                String myAddrs = myIpAddresses.toString();
                prop.put("updated", timeString);
                prop.put("-updated-on", timeString);
                prop.put("r2c-ipaddrs", myAddrs);
                prop.put("r2c-name", R2CActivity.MyDeviceName);
                prop.put("marker-color", "#0000FF");
                if (!myAddrs.contains("tun")) prop.put("description", myAddrs);
            } catch (Exception e) {
                CTError(TAG, "put() raised.", e);
            }
            if (null != MyLocation) {
                MyMarkerOp = CaltopoSession.AddMarker(MyLocation.getLatitude(), MyLocation.getLongitude(),
                        "R2C: " + R2CActivity.MyDeviceName, "radiotower", FolderId, MyUUID, prop, CaltopoMap::MyMarkerCompleted);
            }
        }
        if (!MapCheckerDelay.isRunning()) {
            CTDebug(TAG, "processPeerList(): starting map checker delay...");
            MapCheckerDelay.start(
                    CaltopoMap::PollMapUpdates,
                    FirstMapUpdateTimeInSeconds * 1000,
                    RepeatMapUpdateTimeInSeconds * 1000
            );
        }
    }

    private static void MyMarkerCompleted(CaltopoOp lMyMarkerOp) {
        if (!lMyMarkerOp.isDone() || lMyMarkerOp.fail()) {
            CTError(TAG, "myMarkerCompleted(): Not able to create marker: " + lMyMarkerOp.response);
        } else {
            CTDebug(TAG, "myMarkerCompleted(): marker added.");
        }
    }

    /**  updateMyMarker()
     *    Called whenever our location has changed as well as periodically, just to
     *    update our marker's timestamp to make sure peers know we're "not dead yet".
     * @param feature if non-null, then we are in startup and found our
     *                existing marker on the map.  In this case, just update.
     *                if null, then called during periodic poll to see if
     *                we need to update our marker location.
     */
    private static void UpdateMyMarker(@Nullable JSONObject feature) {
        if (null == feature) {
            if (null == MyMarkerOp || !MyMarkerOp.isDone() || !MyMarkerOp.success()) return;
            feature = MyMarkerOp.getResponse();
            if (null == feature) {
                CTDebug(TAG, "updateMyMarker(): missing marker feature.");
                return;
            }
        }

        if (GetMapId().isEmpty()) {
            CTDebug(TAG, "UpdateMyMarker(): Ignoring spurious update w/o map.");
            return;
        }

        JSONObject geometry = feature.optJSONObject("geometry");
        if (null != MyLocation) try {
            JSONArray coordinates;
            if (null == geometry) {
                geometry = new JSONObject();
                coordinates = new JSONArray();
                geometry.put("coordinates", coordinates);
                feature.put("geometry", geometry);
            } else {
                coordinates = geometry.optJSONArray("coordinates");
                if (null == coordinates) {
                    coordinates = new JSONArray();
                    geometry.put("coordinates", coordinates);
                }
            }
            coordinates.put(0, MyLocation.getLongitude());
            coordinates.put(1, MyLocation.getLatitude());
        } catch (Exception e) {
            CTError(TAG, "updateMyMarker() raised. ", e);
        }
        try {
            JSONObject prop = feature.optJSONObject("properties");
            if (null == prop) {
                prop = new JSONObject();
                feature.put("properties", prop);
            }
            long timeNowInMilliseconds = System.currentTimeMillis();
            String timeString = String.valueOf(timeNowInMilliseconds);
            if (UsePeersFlag && (CaltopoClient.DebugLevel > CaltopoClient.DebugLevelError))
                prop.put("description", R2cPeerConnectionStats());
            else
                prop.put("description", "");
            prop.put("updated", timeString);
            prop.put("-updated-on", timeString);
            CaltopoSession.EditObjectWithId("Marker", MyUUID, feature, null);
        } catch (Exception e) {
            CTError(TAG, "updateMyMarker() raised.", e);
        }
    }

    @NonNull
    public static String R2cPeerConnectionStats() {
        if (CaltopoClient.DebugLevel < CaltopoClient.DebugLevelDebug) return "";

        StringBuilder builder = new StringBuilder();
        for (R2CPeer r2cPeer : R2CPeer.GetCloneOfPeerHashtable().values()) {
            String peerName = r2cPeer.getPeerName();
            builder.append(peerName)
                    .append(":")
                    .append(r2cPeer.stats())
                    .append("\n");
        }

        if (builder.isEmpty()) { // then we're still on our own.  Display our addr's and drones:
            builder.append(R2CPeer.GetMyIpAddresses().toString());
            for (CaltopoLiveTrack liveTrack : LiveTracksById.values()) {
                if (liveTrack.isActive()) {
                    CtDroneSpec ds = liveTrack.getDroneSpec();
                    builder.append("\n  ")
                            .append(ds.trackLabel())
                            .append("(")
                            .append(ds.getGoodCount())
                            .append("/")
                            .append(ds.getTotalCount())
                            .append(")");
                }
            }
        }
        return builder.toString();
    }

    private static void LookForExistingLiveTracks() {
        if (null == FolderId || null == ArchiveFolderId) return;
        long timeNowInMilliseconds = System.currentTimeMillis();
        long maxTrackAgeInMilliseconds = CaltopoClient.GetNewTrackDelayInSeconds() * 1000;

        CTInfo(TAG, String.format(Locale.US,
                "Parsing %d liveTracks to check for idle items in the drone folder",
                MyLiveTracksInThisMap.length()));

        while (0 != MyLiveTracksInThisMap.length()) {
            JSONObject feature = (JSONObject)MyLiveTracksInThisMap.remove(0);
            JSONObject prop = feature.optJSONObject("properties");
            if (null == prop) continue;

            // only interested in features w/in the drone tracks directory:
            String featureFolderId = prop.optString("folderId", "");
            if (!featureFolderId.equals(FolderId)) continue;

            // found a feature in the drone folder.
            String featureClass = prop.optString("class", null);
            String title = prop.optString("title");
            CTDebug(TAG, String.format(Locale.US, "Found a %s:%s in drone folder", featureClass, title));
            String lastUpdatedStr = prop.optString("updated", "");
            long lastUpdatedInMilliseconds = Long.parseLong(lastUpdatedStr);
            long trackAgeInMilliseconds = timeNowInMilliseconds - lastUpdatedInMilliseconds;

            if (trackAgeInMilliseconds < maxTrackAgeInMilliseconds) {
                CTDebug(TAG, String.format(Locale.US,
                        "%s:%s last update was only %.3f seconds ago - ignoring",
                        featureClass, title, (double)trackAgeInMilliseconds / 1000.0));
                continue;
            }

            // found a feature in the drone folder old enough to archive.
            CTDebug(TAG, String.format(Locale.US, "%s:%s last updated %.3f seconds ago - archiving.",
                    featureClass, title, (double)trackAgeInMilliseconds/1000.0));
            ArchiveFeature(feature, featureClass, timeNowInMilliseconds, 0);
        }
        PollMapUpdates();
    }

    /* feature is the complete feature description.   featureClass is the type of feature
     * that is being archived.  All are archived ultimately as 'Shape' class, but if
     * specified feature is a LiveTrack, the LiveTrack is deleted after archiving it's
     * state as a Shape.   That's the way the Caltopo v1 API wants it to happen.
     */
    public static void ArchiveFeature(@NonNull JSONObject feature, @NonNull String featureClass,
                               long timeNowInMilliseconds, long maxWaitInMilliseconds) {
        String timeString = String.valueOf(timeNowInMilliseconds);
        if (null == ArchiveFolderId) {
            CTError(TAG, "archiveFeature(): can't archive - folder not created yet.");
            return;
        }
        try {
            String trackId = feature.optString("id", "");
            if (trackId.isEmpty()) {
                CTError(TAG, "archiveFeature(): id for feature is empty - this shouldn't happen.\n  " +
                        feature.toString(4));
                return;
            }
            JSONObject prop = feature.optJSONObject("properties");
            if (null == prop) {
                prop = new JSONObject();
                feature.put("properties", prop);
            }
            prop.put("stroke", ArchiveLineProp.color);
            prop.put("stroke-width", ArchiveLineProp.width);
            prop.put("stroke-opacity", ArchiveLineProp.opacity);
            prop.put("pattern", ArchiveLineProp.pattern);
            prop.put("folderId", ArchiveFolderId);
            prop.put("updated", timeString);
            prop.put("-updated-on", timeString);
            prop.put("class", "Shape");  // convert from LiveTrack to shape.
            CaltopoOp op = CaltopoSession.EditObjectWithId("Shape", trackId, feature, null);
            if (maxWaitInMilliseconds > 0) {
                op.syncOp(maxWaitInMilliseconds);
                maxWaitInMilliseconds = maxWaitInMilliseconds - (System.currentTimeMillis() - timeNowInMilliseconds);
            }
            if (featureClass.equals("LiveTrack")) {
                CTInfo(TAG, String.format(Locale.US, "archiveFeature(): Stopping liveTrack %s....", trackId));
                op = CaltopoSession.DeleteLiveTrackWithId(trackId, null);  // Then delete LiveTrack.
                if (maxWaitInMilliseconds > 0) op.syncOp(maxWaitInMilliseconds);
            }
        } catch (Exception e) {
            CTError(TAG, "archiveFeature() raised:", e);
        }
    }

    public static MapStatusListener.mapStatus GetMapStatus() {return (MapStatus);}

    /* N.B. map can be up, but folders not yet created, in which case these
     * will return null... patience.
     */
    @Nullable
    public static String GetFolderId() { return FolderId; }
    @Nullable
    public static String GetArchiveFolderId() { return ArchiveFolderId; }

    public static void Shutdown() {
        try {
            if (UsePeersFlag) R2CPeer.Shutdown();
            CaltopoSession.Shutdown();
        } catch (Exception e) {
            CTError(TAG, "Shutdown() raised: ", e);
        }
    }
}
