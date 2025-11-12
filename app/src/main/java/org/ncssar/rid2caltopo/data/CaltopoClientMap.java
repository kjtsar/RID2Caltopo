package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.ShowToast;
import org.ncssar.rid2caltopo.app.R2CActivity;
import android.content.ContentResolver;
import android.content.Context;
import android.location.Location;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** CaltopoClientMap class
 *   Support bringing up the map session and creating the drone track folders (if not
 * already present).
 * The archive folder starts with the drone track folder name and ends with the
 * current date (i.e. 25Sep).
 *   Additionally, if there are any peer R2C Instances present on the map, then
 * attempt to bring up their corresponding R2CRest clients before indicating the
 * map is up.
 *
 * FIXME:  I'm not yet sure if the caltopo map needs to keep track of all the peer
 *     connectivity issues.   It seems like it should be enough that it minds it's
 *     own marker on Caltopo and cranks-up the server (R2CRest.Init() - which it
 *     does prior to connecting to it's own map) to listen for inbound connections
 *     on.   The caltopo map makes sense for facilitating the rendezvous, but after
 *     that, I'd like to see the individual app instances keep track of their peer
 *     connectivity.
 */
public class CaltopoClientMap implements R2CRest.R2CListener {
    public interface MapStatusListener {
        void mapStatusUpdate(CaltopoClientMap map, boolean mapIsUpFlag);
    }
    private static final String TAG = "CaltopoClientMap";
    private static CaltopoSession Csp;
    private static String MyUUID = null;
    private static android.location.Location MyLocation;
    private static final ArrayList<CaltopoClientMap> Maps = new ArrayList<>(16);

    // poll the map to see if there are any new r2c clients or user has changed one of our track labels
    private final DelayedExec mapCheckerDelay = new DelayedExec();
    private static final long FirstMapUpdateTimeInSeconds = 15;
    private static final long RepeatMapUpdateTimeInSeconds = 45;

    private static final long MAX_CALTOPO_OP_DURATION_IN_SECONDS = 20;

    private static CaltopoClientMap CurrentMap = null;
    public static final CtLineProperty ArchiveLineProp =
            new CtLineProperty(2, 0.5F, "#ff00ff", "solid");
    private static final int MAX_MAP_STARTUP_DELAY_IN_SECONDS = 45;

    // my peers by UUID:
    private final Hashtable<String, R2CRest>clientIdMap = new Hashtable<>(16);
    private CaltopoOp openMapOp;
    private CaltopoOp updateMapOp;
    private String folderId;
    private CaltopoOp folderIdOp;
    private CaltopoOp myMarkerOp;
    private String archiveFolderId;
    private CaltopoOp archiveFolderIdOp;
    private boolean mapDumpedToLog;
    private final CaltopoSessionConfig sessionConfig;
    private String  mapId;
    private final String folderName;
    private String openMapFailedMsg;
    private boolean mapIsUp;
    private int waitForGpsAccuracy;
    private JSONArray r2cPeers; // list of r2cPeerSpecs for peers listed on our map.

    // liveTracks that we are writing into the map keyed by their map ID:
    private final Hashtable<String, CaltopoLiveTrack> liveTracksById = new Hashtable<>();

    // All liveTracks, including those not yet/ever writing to the map:
    private final ArrayList<CaltopoLiveTrack> liveTracks = new ArrayList<>(16);

    private final ArrayList<JSONObject> rogueFeaturesPendingDeletes = new ArrayList<>();

    private static MapStatusListener MapStatusListener;

    private JSONArray myLiveTracksInThisMap;   // Actual 'LiveTrack' objects in the current map
    public CaltopoClientMap(@NonNull CaltopoSessionConfig config, @NonNull String mapId, @NonNull String folderName)
            throws RuntimeException {
        R2CRest.Init();  // start server.
        sessionConfig = config;
        if (mapId.isEmpty())
            throw new RuntimeException("CaltopoClientMap(): mapId must be specified.");
        if (folderName.isEmpty()) folderName = "DroneTracks";
        this.folderName = folderName;
        if (null == MyUUID) GetMyUUID();
        Maps.add(this);
        CurrentMap = this;
        startMapConnection(mapId);
    }

    public static void SetMapStatusListener(@Nullable MapStatusListener listener) {MapStatusListener = listener;}

    public void addLiveTrack(@NonNull CaltopoLiveTrack track) {
        liveTracks.add(track);
    }

    public void addLiveTrack(@NonNull String trackId, @NonNull CaltopoLiveTrack track) {
        CTDebug(TAG, "addLiveTrack(): adding liveTrack with id: " + trackId);
        liveTracksById.put(trackId, track);
    }
    public void removeLiveTrack(@NonNull String trackId) {
        CTDebug(TAG, "removeLiveTrack(): removing liveTrack with id: " + trackId);
        liveTracksById.remove(trackId);
    }

    public static float DistanceFromMeInMeters(double lat, double lng) {
        float[] dbResult = {Float.NaN};
        if (null == MyLocation || !MyLocation.hasAccuracy()) return Float.NaN;
        Location.distanceBetween(lat, lng, MyLocation.getLatitude(), MyLocation.getLongitude(), dbResult);
        return dbResult[0];
    }

    public void clientStatusChange(R2CRest client, boolean isNowUpFlag) {
        CTDebug(TAG, String.format(Locale.US,
                "Received clientStatusChange(%s) from %s.", isNowUpFlag,client.getPeerName()));
        if (isNowUpFlag) {
            AddClient(client);
        } else {
            RemoveClient(client);
        }
    }

    @NonNull
    public static String GetMyUUID() {
        if (null != MyUUID) return MyUUID;
        Context ctxt = R2CActivity.getAppContext();
        if (null == ctxt) {
            DelayedExec.RunAfterDelayInMsec(CaltopoClientMap::GetMyUUID, 1000);
            CTDebug(TAG, "GetMyUUID() waiting for app to initialize...");
            return "";
        }
        ContentResolver contentResolver = ctxt.getContentResolver();
        // android studio warns: "Using 'GetString' to get device identifiers is not recommended",
        // but nothing in the method spec mentions this...
        String androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID);
        UUID deviceUuid = UUID.nameUUIDFromBytes(androidId.getBytes(StandardCharsets.UTF_8));
        MyUUID = deviceUuid.toString();
        return MyUUID;
    }

    public void setMapId(@NonNull String newMapId) {
        if (newMapId.isEmpty()) {
            // user wants to shut down the map connection;
            resetMapConnection();

        } else if (!mapId.isEmpty() && !newMapId.equals(mapId)) {
            // archive tracks on existing map before switching:
            CTDebug(TAG, String.format(Locale.US, "setMapId() changing from '%s' to '%s'.",
                    mapId, newMapId));
            for (CaltopoLiveTrack track : liveTracks) {
                track.finishTrack("Map change");
            }
            // Then delete our marker before switching over to the new map.
            CaltopoOp op = Csp.deleteMarkerWithId(MyUUID, null);
            try {
                op.syncOpJSONObject(3);
                if (op.success()) {
                    CTDebug(TAG, String.format(Locale.US, "Marker removed in %.3f seconds",
                            (double) op.roundTripTimeInMsec() / 1000.0));
                }
            } catch (Exception e) {
                CTError(TAG, "setMapId(): deleteMarkerWithId() raised:", e);
            }
        } else if (!mapId.isEmpty()) {
            CTDebug(TAG, "setMapId(): ignoring attempt to change to existing map.");
            return;
        }
        startMapConnection(newMapId);
    }

    private void resetMapConnection() {
        setMapState(false);
        mapCheckerDelay.stop();
        if (!clientIdMap.isEmpty()) {
            ArrayList<String> uuidList = new ArrayList<>(clientIdMap.size());
            for (Map.Entry<String,R2CRest>map : clientIdMap.entrySet()) {
                R2CRest r2cClient = map.getValue();
                CTDebug(TAG, String.format(Locale.US,
                        "Shutting down connection to %s due to map change: ", r2cClient.getPeerName()));
                r2cClient.shutdown();
                uuidList.add(map.getKey());
            }
            for (String uuid : uuidList) clientIdMap.remove(uuid);
        }
        openMapOp = null;
        updateMapOp = null;
        folderIdOp = null;
        folderId = null;
        archiveFolderIdOp = null;
        archiveFolderId = null;
        mapDumpedToLog = false;
        openMapFailedMsg = null;
    }

    private void startMapConnection(@NonNull String newMapId) {
        resetMapConnection();
        mapId = newMapId;

        if (null == Csp) {
            Csp = new CaltopoSession(sessionConfig);
            CTInfo(TAG, "startMapConnection() created session.");
        }
        try {
            CTDebug(TAG, String.format(Locale.US, "Opening map '%s'", mapId));
            openMapOp = Csp.openMap(mapId, this::openMapFinished);
        } catch (Exception e) {
            CTError(TAG, "startMapConnection(): csp.openMap() raised:", e);
        }
    }


    private void createArchiveDirFinished() {
        if (archiveFolderIdOp.fail()) {
            ShowToast(String.format(Locale.US,
                    "Could not create archive folder in map '%s' - check mapId/permissions:\n  %s",
                    mapId, archiveFolderIdOp.responseString()));
            return;
        }
        archiveFolderId = archiveFolderIdOp.id();
        CTDebug(TAG, String.format(Locale.US, "archive folder id is %s", archiveFolderId));
        lookForExistingLiveTracks();
    }

    private void createTrackDirFinished() {
        if (folderIdOp.fail()) {
            ShowToast(String.format(Locale.US,
                    "Could not create track folder in map '%s' - check mapId/permissions:\n  %s",
                    mapId, folderIdOp.responseString()));
            return;
        }
        folderId = folderIdOp.id();
        CTDebug(TAG, String.format(Locale.US, "track folder id is %s", folderId));
    }

    /** parseMapUpdate()
     * Different from parseMap() in that we already have an established map w/DroneTracks and
     * Archive directories.  Now we're just interested in taking a peek at the contents of
     * the DroneTracks directory to see if there are any new Markers for R2C instances we
     * don't know about yet that haven't initiated contact with us.   This can happen if
     * there was a race condition at start-up and we both looked at the map and determined
     * that there were no other R2C instances out there.
     *
     * FIXME: Check to see if any of our LiveTracks have had a Caltopo user change their
     *        track labels.
     * @param state
     * @throws RuntimeException
     * @throws JSONException
     */
    private void parseMapUpdate(JSONObject state)
            throws RuntimeException, JSONException {
        if (state == null) throw new RuntimeException("parseMapUpdate(): response missing required state.");
        JSONArray features = state.getJSONArray("features");
        int count = features.length();
        CTDebug(TAG, String.format(Locale.US, "parseMapUpdate(): found %d features", count));
        int newCount = 0;
        for (int i = 0; i < count; i++) {
            JSONObject feature = features.getJSONObject(i);
            JSONObject prop = feature.optJSONObject("properties");
            if (null == prop) {
                CTDebug(TAG, "parseMapUpdate(): Feature missing required properties parameter.");
                continue;
            }
            String thisFolderId = prop.optString("folderId");
            if (!folderId.equals(thisFolderId)) {
                CTDebug(TAG, String.format(Locale.US, "parseMapUpdate(): feature folder id '%s' doesn't match our folder id '%s': %s",
                        thisFolderId, folderId, feature));
                continue;
            }
            CTDebug(TAG, "parseMapUpdate(): found feature in our folder: " + feature);
            String classString = prop.optString("class");
            String idString = feature.optString("id");
            if ("LiveTrack".equals(classString)) {
                String title = prop.optString("title");
                CTDebug(TAG, "parseMapUpdate(): Found liveTrack '" + title + "' with id: " + idString);
                CaltopoLiveTrack liveTrack = liveTracksById.get(idString);
                if (null != liveTrack) {
                    CTDebug(TAG, "parseMapUpdate(): liveTrack is one of ours - checking");
                    liveTrack.checkCaltopoTrackLabel(title);
                } else {
                    CTDebug(TAG, "parseMapUpdate(): liveTrack is not one of ours - ignoring");
                }
            } else if ("Marker".equals(classString)) {
                if (MyUUID.equals(idString) || clientIdMap.contains(idString)) continue;
                newCount++;
                String ipAddrsString = prop.optString("r2c-ipaddrs");
                if (!ipAddrsString.isEmpty()) {
                    CTDebug(TAG, "parseMapUpdate(): found new peer: " + feature);
                    JSONArray ipAddrsObj = new JSONArray(ipAddrsString);
                    JSONObject peerMarkerSpec = parseR2cMarker(ipAddrsObj, feature, prop);
                    clientIdMap.put(idString, R2CRest.ClientForRemoteR2c(peerMarkerSpec, this));
                } else {
                    CTDebug(TAG, "parseMapUpdate(): ignoring non-R2C marker in our folder:\n" + feature.toString(4));
                }
            }
        }
        CTDebug(TAG, String.format(Locale.US, "parseMapUpdate() found %d new features since my last visit.", newCount));
    }

    /* Parse the feature set returned by the openMap()
     * to look for our track directory and it's companion archive dir.
     * Also make a list of all other LiveTracks that might be leftover old
     * tracks in need of archival.
     * FIXME: Seems like this could take a long time on a multi-op period search, so
     *  might want to be able to do this in a background thread.
     */
    private void parseMap(JSONObject state)
            throws RuntimeException, JSONException {

        myLiveTracksInThisMap = new JSONArray();
        JSONArray markerFeatures = new JSONArray();
        SimpleDateFormat sdf = new SimpleDateFormat("ddMMM", Locale.US);
        String archiveFolderName = folderName + sdf.format(new Date());

        CTInfo(TAG, String.format(Locale.US,
                "parseMap() Checking map for folders: '%s' and '%s'",
                folderName, archiveFolderName));

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
                CTError(TAG, "parseMap(): found rogue feature missing title: " + feature.toString(4));
                rogueFeaturesPendingDeletes.add(feature);
                continue;
            }

            String classProp = prop.optString("class", "");
            switch (classProp) {
                case "": CTError(TAG, "parseMap(): feature missing class: " + feature.toString(4)); break;
                case "Marker": markerFeatures.put(feature); break;
                case "LiveTrack":
                    // collect the list of features that might be ours - don't know if they
                    // are in our folder yet, because we may not even have folders.
                        myLiveTracksInThisMap.put(feature);
                        break;
                case "Folder": {
                    if (folderId == null && title.equals(folderName)) {
                        folderId = feature.getString("id");
                        CTDebug(TAG, String.format(Locale.US,
                                "Found existing folder '%s' with id %s", folderName, folderId));
                    } else if (null == archiveFolderId && title.equals(archiveFolderName)) {
                        archiveFolderId = feature.getString("id");
                        CTDebug(TAG, String.format(Locale.US,
                                "Found existing folder '%s' with id %s", archiveFolderName, archiveFolderId));
                    }
                    break;
                }
            }
        }

        // Request the directories to be created if they weren't found in the existing map:
        if (null == folderId) {
            CTInfo(TAG, String.format(Locale.US,
                    "parseMap() '%s' folder not found - creating...", folderName));
            folderIdOp = Csp.addFolder(folderName, true, true, this::createTrackDirFinished);
        }
        findR2cPeers(markerFeatures);

        if (null == archiveFolderId) {
            CTInfo(TAG, String.format(Locale.US,
                    "parseMap() '%s' folder not found - creating...", archiveFolderName));
            archiveFolderIdOp = Csp.addFolder(archiveFolderName, false, false, this::createArchiveDirFinished);
        } else lookForExistingLiveTracks();
    }

    private void updateMapFinished() {
        if (updateMapOp.fail()) {
            CTError(TAG, String.format(Locale.US, "Not able to update map '%s':\n  %s",
                    mapId, updateMapOp));
            return;
        }

        if (CaltopoClient.DebugLevel > CaltopoClient.DebugLevelDebug) {
            CTInfo(TAG, "updateMapFinished() dumping map to logfile...");
            CTInfo(TAG, updateMapOp.responseString());
        }
        if (null != updateMapOp.responseJson) {
            JSONObject stateObj = updateMapOp.responseJson.optJSONObject("state");
            if (null != stateObj) try {
                parseMapUpdate(stateObj);
            } catch (Exception e) {
                CTError(TAG, "updateMapFinished(): parseMapUpdate() raised. ", e);
            }
        }
        this.updateMapOp = null;
        setMapState(true);
    }

    private void setMapState(boolean mapIsUp) {
        if (mapIsUp != this.mapIsUp) {
            this.mapIsUp = mapIsUp;
            if (null != MapStatusListener) MapStatusListener.mapStatusUpdate(this, mapIsUp);
            CTDebug(TAG, "setMapState(): map is " + (mapIsUp ? "up." : "down."));
            if (mapIsUp) {
                while (!rogueFeaturesPendingDeletes.isEmpty()) {
                    JSONObject feature = rogueFeaturesPendingDeletes.remove(0);
                    JSONObject prop = feature.optJSONObject("properties");
                    if (null != prop) {
                        String featureFolderId = prop.optString("folderId");
                        if (featureFolderId.equals(folderId) || featureFolderId.equals(archiveFolderId)) {
                            // then it's a feature fragment that we created, so we can/should delete it.
                            String featureId = feature.optString("id");
                            Csp.deleteShapeWithId(featureId, null);
                        }
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
    private void openMapFinished() {
        if (mapId.isEmpty()) return;
        if (openMapOp.fail()) {
            openMapFailedMsg = String.format(Locale.US, "Not able to open map '%s':\n  %s",
                    mapId, openMapOp);
            ShowToast(openMapFailedMsg);
            mapId = "";
            return;
        }

        if (CaltopoClient.DebugLevel > CaltopoClient.DebugLevelDebug && !mapDumpedToLog) {
            CTInfo(TAG, "openMapFinished() dumping map to logfile...");
            CTInfo(TAG, openMapOp.responseString());
            mapDumpedToLog = true; // this means map is up.
        }

        try {
            parseMap(openMapOp.responseJson.getJSONObject("state"));
        } catch (Exception e) {
            CTError(TAG, "openMapFinished(): parseMap raised:", e);
        }
    }

    private JSONObject parseR2cMarker(JSONArray ipAddrsObj, JSONObject feature, JSONObject prop) throws JSONException {
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
    public void findR2cPeers(@NonNull JSONArray markerFeatures) {
        r2cPeers = new JSONArray();
        try {
            for (int i = 0; i < markerFeatures.length(); i++) {
                JSONObject feature = markerFeatures.optJSONObject(i);
                JSONObject prop = feature.optJSONObject("properties");
                if (null == prop) continue;
                String featureFolderId = prop.optString("folderId");
                if (featureFolderId.equals(folderId)) {
                    String ipAddrsString = prop.optString("r2c-ipaddrs");
                    if (!ipAddrsString.isEmpty()) {
                        JSONArray ipAddrsObj = new JSONArray(ipAddrsString);
                        r2cPeers.put(parseR2cMarker(ipAddrsObj, feature, prop));
                    } else {
                        CTDebug(TAG, "Ignoring non-R2C marker in our folder:\n" + feature.toString(4));
                    }
                }
            }
            CTDebug(TAG, "getR2cPeer() found peers: " + r2cPeers.toString(4));
        } catch (Exception e) {
            CTError(TAG, "getR2cPeers(): Error parsing map.", e);
        }
        processPeerList();
    }


    public static void UpdateMyLocation(@NonNull android.location.Location location) {
        if ( null == MyLocation || !MyLocation.hasAccuracy() ||
                (location.hasAccuracy() && location.getAccuracy() < MyLocation.getAccuracy())) {
            CTDebug(TAG, String.format(Locale.US, "UpdateMyLocation(): lat:%.7f, lng:%.7f, accuracy:%.3fm",
                    location.getLatitude(), location.getLongitude(), location.getAccuracy()));
            MyLocation = location;
            for (CaltopoClientMap map : Maps) map.updateMyMarker(null);
        }
    }

    /* Could not establish a connection to the specified client or it
     * stopped responding or it sent a message saying it was going away.
     * Hopefully it will remove it's marker from the map
     */
    public static void RemoveClient(@NonNull R2CRest client) {
        for (CaltopoClientMap map : Maps) {  // remove the specified client from all our maps:
            R2CRest mappedClient = map.clientIdMap.remove(client.getRemoteUUID());
            if (null != mappedClient) {
                CTDebug(TAG, String.format(Locale.US, "Removed %s client from %s", mappedClient.getPeerName(), map.mapId));
            }
        }
    }

    public static void AddClient(@NonNull R2CRest client) {
        if (null != CurrentMap) CurrentMap.addClient(client);
    }

    private void addClient(@NonNull R2CRest client) {
        clientIdMap.put(client.getRemoteUUID(), client);
    }

    private void processPeerList() {
        JSONObject myMarker = null;
        JSONArray myIpAddresses = R2CRest.GetMyIpAddresses();
        double accuracyInMeters;

        if (null == folderId || null == archiveFolderId) {
            CTDebug(TAG, "processPeerList(): waiting for map processing to complete...");
            DelayedExec.RunAfterDelayInMsec(this::processPeerList, 1000);
            return;
        }
        if (0 == myIpAddresses.length() && waitForGpsAccuracy++ < 5) {
            CTDebug(TAG, "processPeerList(): waiting for internet connectivity...");
            DelayedExec.RunAfterDelayInMsec(this::processPeerList, 1000);
            return;
        }
        if (null == MyLocation && waitForGpsAccuracy++ < MAX_MAP_STARTUP_DELAY_IN_SECONDS) {
            CTDebug(TAG, "processPeerList(): No Location yet...retrying");
            DelayedExec.RunAfterDelayInMsec(this::processPeerList, 1000);
            waitForGpsAccuracy++;
            return;
        }
        if (null != MyLocation) {
            accuracyInMeters = MyLocation.getAccuracy();
            if ((!MyLocation.hasAccuracy() || accuracyInMeters > 30.0) && waitForGpsAccuracy++ < MAX_MAP_STARTUP_DELAY_IN_SECONDS) {
                ShowToast(String.format(Locale.US, "Location accuracy of %.3f meters isn't great - waiting for better accuracy.",
                        accuracyInMeters));
                DelayedExec.RunAfterDelayInMsec(this::processPeerList, 1000);
                waitForGpsAccuracy++;
                return;
            }

            CTDebug(TAG, String.format(Locale.US, "My location is %.7f,%.7f w/in %.3f meters. My UUID is %s",
                    MyLocation.getLatitude(), MyLocation.getLongitude(), accuracyInMeters, MyUUID));
        } else {
            CTError(TAG, "processPeerList(): bad/no gps - I have no idea where I am.");
        }

        // look for my Marker in the list of peers and fire-off client connections for the others:
        for (int i=0; i<r2cPeers.length(); i++) {
            JSONObject peer = r2cPeers.optJSONObject(i);
            String peerUUID = peer.optString("id");
            if (peerUUID.equals(MyUUID)) {
                myMarker = peer;
                CTDebug(TAG, "Found marker with my UUID: " + MyUUID);
            } else {
                clientIdMap.put(peerUUID, R2CRest.ClientForRemoteR2c(peer, this));
            }
        }

        long timeNowInMilliseconds = System.currentTimeMillis();
        String timeString = String.valueOf(timeNowInMilliseconds);
        if (null != myMarker) {
            // Not a clean shutdown previously - this can happen when app is terminated while internet is down.
            JSONObject updateFeature = myMarker.optJSONObject("feature");
            updateMyMarker(updateFeature);

        } else { // we get to create our marker from scratch - yipee!
            CTDebug(TAG, String.format(Locale.US,
                    "Didn't find our existing marker in %d peers, so adding a new one:", r2cPeers.length()));
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
                myMarkerOp = Csp.addMarker(MyLocation.getLatitude(), MyLocation.getLongitude(),
                        "R2C: " + R2CActivity.MyDeviceName, "radiotower", folderId, MyUUID, prop, this::myMarkerCompleted);
            }
        }
        if (!mapCheckerDelay.isRunning()) mapCheckerDelay.start(() ->
                        updateMyMarker(null), FirstMapUpdateTimeInSeconds * 1000,
                RepeatMapUpdateTimeInSeconds * 1000);
    }

    private void myMarkerCompleted() {
        if (!myMarkerOp.isDone() || myMarkerOp.fail()) {
            CTError(TAG, "Not able to create marker: " + myMarkerOp.response);
        } else {
            CTDebug(TAG, "marker added.");
        }
    }

    private void updateMyMarker(@Nullable JSONObject feature) {
        if (null == feature) {
            if (null == myMarkerOp || !myMarkerOp.isDone() || !myMarkerOp.success()) return;
            feature = myMarkerOp.getResponse();
            if (null == feature) return;
        }
        if (null == updateMapOp) {
            CTDebug(TAG, "updating map connection()");
            updateMapOp = Csp.openMap(mapId, this::updateMapFinished);
        }

        JSONObject geometry = feature.optJSONObject("geometry");
        if (null != MyLocation && MyLocation.hasAccuracy()) try {
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
            prop.put("description", r2cPeerConnectionStats());
            prop.put("updated", timeString);
            prop.put("-updated-on", timeString);
            Csp.editObjectWithId("Marker", MyUUID, feature, null);
        } catch (Exception e) {
            CTError(TAG, "updateMyMarker() raised.", e);
        }
    }

    @NonNull
    public String r2cPeerConnectionStats() {
        StringBuilder builder = new StringBuilder();
        for (R2CRest r2cClient : R2CRest.GetCloneOfPeerHashtable().values()) {
            String peerName = r2cClient.getPeerName();
            builder.append(peerName)
                    .append(":")
                    .append(r2cClient.stats())
                    .append("\n");
        }
        if (builder.length() > 0) return builder.toString();
        return R2CRest.GetMyIpAddresses().toString();
    }

    private void lookForExistingLiveTracks() {
        if (null == folderId || null == archiveFolderId) return;
        long timeNowInMilliseconds = System.currentTimeMillis();
        long maxTrackAgeInMilliseconds = CaltopoClient.GetNewTrackDelayInSeconds() * 1000;

        CTInfo(TAG, String.format(Locale.US,
                "Parsing %d liveTracks to check for idle items in the drone folder",
                myLiveTracksInThisMap.length()));

        while (0 != myLiveTracksInThisMap.length()) {
            JSONObject feature = (JSONObject)myLiveTracksInThisMap.remove(0);
            JSONObject prop = feature.optJSONObject("properties");
            if (null == prop) continue;

            // only interested in features w/in the drone tracks directory:
            String featureFolderId = prop.optString("folderId", "");
            if (!featureFolderId.equals(folderId)) continue;

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
            archiveFeature(feature, featureClass, timeNowInMilliseconds);
        }
    }

    /* feature is the complete feature description.   featureClass is the type of feature
     * that is being archived.  All are archived ultimately as 'Shape' class, but if
     * specified feature is a LiveTrack, the LiveTrack is deleted after archiving it's
     * state as a Shape.   That's the way the Caltopo v1 API wants it to happen.
     */
    public void archiveFeature(@NonNull JSONObject feature, @NonNull String featureClass,
                               long timeNowInMilliseconds) {
        String timeString = String.valueOf(timeNowInMilliseconds);
        if (null == archiveFolderId) {
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
            prop.put("folderId", archiveFolderId);
            prop.put("updated", timeString);
            prop.put("-updated-on", timeString);
            prop.put("class", "Shape");  // convert from LiveTrack to shape.
            Csp.editObjectWithId("Shape", trackId, feature, null);
            if (featureClass.equals("LiveTrack")) {
                CTDebug(TAG, String.format(Locale.US, "archiveFeature(): Stopping liveTrack %s....", trackId));
                Csp.deleteLiveTrackWithId(trackId, null);  // Then delete LiveTrack.
            }
        } catch (Exception e) {
            CTError(TAG, "archiveFeature() raised:", e);
        }
    }

    @NonNull
    public String getMapId(){return mapId;}

    public boolean getMapIsUp() {return (this.mapIsUp);}

    /* N.B. map can be up, but folders not yet created, in which case these
     * will return null... patience.
     */
    @Nullable
    public String getFolderId() { return folderId; }
    @Nullable
    public String getArchiveFolderId() { return archiveFolderId; }

    /* Use with caution.
     * With great power comes great responsibility...
     * FIXME: Consider moving user functionality directly into the map or the session.
     */
    public  CaltopoSession session() {return Csp;}

    public static void Shutdown() {
        if (null != Csp) try {
            for (CaltopoClientMap map : Maps) {
                map.mapCheckerDelay.stop();
                for (CaltopoLiveTrack track : map.liveTracks) {
                    CTDebug(TAG, "ShutDown() - shutting down track: " + track.getTrackLabel());
                    track.shutdown();
                }
            }
            CaltopoOp op = Csp.deleteMarkerWithId(MyUUID, null);
            R2CRest.Shutdown();
            op.syncOpJSONObject(MAX_CALTOPO_OP_DURATION_IN_SECONDS);
            if (op.success()) {
                CTDebug(TAG, String.format(Locale.US, "Marker removed in %.3f seconds",
                        (double)op.roundTripTimeInMsec() / 1000.0));
            }
        } catch (Exception e) {
            CTError(TAG, "Attempting to remove my Marker from caltopo raised: ", e);
        }
        CaltopoSession.Shutdown();
    }

}
