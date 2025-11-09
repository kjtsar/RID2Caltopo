/*
 caltopo module:
   Provide UI to specify groupID and edit the RID map

 caltopo supports Live Tracking via Fleet option:
   https://caltopo.com/api/v1/postion/report/<group>?id=<id>@lat=36.47375&lng=-118.85302
   # organization identifer - No Spaces & up to 12 characters
   setGroupid(groupid='NCSSAR')

   It also provides an API of sorts that allows limited direct viewing/editing of maps:
       https://training.caltopo.com/all_users/team-accounts/teamapi

  use:
       // specify either Live Track (false) or Direct Track (true)
       CaltopoClient.useDirectFlag(useDirectFlag);
       if (useDirectFlag) {
           CaltopoClient.setCaltopoSessionConfig(cfg);
       }

       CaltopoClient client = CaltopoClient.clientForRemoteId(String remoteID);
       client.newWaypoint(lat,lng);
    #


*/
package org.ncssar.rid2caltopo.data;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.*;
import java.util.function.Function;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.ncssar.rid2caltopo.BuildConfig;
import org.ncssar.rid2caltopo.R;
import org.ncssar.rid2caltopo.app.R2CActivity;



/*
 * Persistent state management for CaltopoClient
 */
class ClientClassState implements Serializable {
    private static final long SerialVersionUID = 18L; // Serializable version.
    public long minDistanceInFeet;
    public String groupId;
    public String archivePath;
    public String caltopoTrackFolder;
    public CaltopoSessionConfig caltopoSessionConfig;
    public String mapId;
    public boolean useDirectFlag;
    public long newTrackDelayInSeconds;
    public long maxDisplayAgeInSeconds;
    public int debugLevel;
    public Hashtable<String, CtDroneSpec> droneSpecTable;  // Table to map remoteIDs to their data

    // Default/initial state for the caltopo client:
    ClientClassState() {
        minDistanceInFeet = CaltopoClient.MIN_DISTANCE_IN_FEET;
        groupId = "";
        archivePath = null;
        caltopoTrackFolder = "Drone Tracks";
        caltopoSessionConfig = new CaltopoSessionConfig();
        mapId = "";
        useDirectFlag = false;
        newTrackDelayInSeconds = 20;
        maxDisplayAgeInSeconds = 60 ;
        debugLevel = -1; // undefined.
        droneSpecTable = new Hashtable<>(16);
    }

    @Override
    @NonNull
    public String toString() {
        CaltopoSessionConfig cfg = caltopoSessionConfig;
        String domainAndPort = "";
        String teamId = "";
        String credId = "";
        String credSecret = "";
        if (null != cfg.domainAndPort && !cfg.domainAndPort.isEmpty()) {
            domainAndPort = cfg.domainAndPort;
        }
        if (null != cfg.teamId && !cfg.teamId.isEmpty()) {
            // teamId = cfg.teamId;
            teamId = "###";
        }
        if (null != cfg.credentialId && !cfg.credentialId.isEmpty()) {
            // credId = cfg.credentialId;
            credId = "######";
        }
        if (null != cfg.credentialSecret && !cfg.credentialSecret.isEmpty()) {
            // credSecret = cfg.credentialSecret;
            credSecret = "###########";
        }

        return String.format(Locale.US,
                "vers:'%d', minDist:'%d' ft, groupId:'%s', mapId:'%s', useDirectFlag:'%s'\n" +
                        "newTrackDelayInSec:%d, maxDisplayAgeInSec:%d, debugLevel:%d, " +
                        "archivePath: '%s', \n caltopoTrackFolder: '%s', caltopoDomainAndPort:%s, \n" +
                        "teamId: '%s', credId: '%s' credSecret: '%s', ht: %s",
                SerialVersionUID, minDistanceInFeet, groupId, mapId, useDirectFlag,
                newTrackDelayInSeconds, maxDisplayAgeInSeconds, debugLevel,
                (archivePath == null) ? "" : archivePath,
                caltopoTrackFolder, domainAndPort, teamId, credId, credSecret,
                CaltopoClient.DroneSpecStringRep(droneSpecTable));
    }
}

public class CaltopoClient implements CtDroneSpec.CtDroneSpecListener {
    public interface CtDroneSpecArrayMonitor {
        void droneSpecArrayChanged();
    }


    // CaltopoClient CLASS VARS:
    static final long MIN_DISTANCE_IN_FEET = 2;
    static final long MIN_NEW_TRACK_DELAY_IN_SECONDS = 15;
    static final long MainThreadId = android.os.Process.myTid();
    private static final String BASE_URL = "https://caltopo.com/api/v1/position/report/";
    private static final String TAG = "CaltopoClient";
    public static final String LoadConfigFileMessage = "Open Caltopo Configuration File";
    public static final int DebugLevelError = 0;
    public static final int DebugLevelDebug = 1;
    public static final int DebugLevelInfo = 2;
    public static int DebugLevel = DebugLevelDebug;
    private static final int ThreadPoolSize = 1;
    private static boolean WarnMissingGroupId = false;
    private static boolean WarnMissingMapFlag = false;
    private static boolean WarnConnectingToMapFlag = false;
    private static CtAlertDialog MapIdChangeDialog;
    private static Hashtable<String, CaltopoClient> ClientMap;
    private static ExecutorService ExecutorPool = null;
    private static ClientClassState Ccstate = null;
    private static AppCompatActivity AppActivity = null;
    private static Context AppContext = null;
//    private static final String MyStateFileName = TAG + BuildConfig.BUILD_TIME + ".ser";
    private static final String MyStateFileName = TAG + ".ser";
    private static String LogFilePath;
    private static ActivityResultLauncher<Intent> QueryArchivePath;
    private static ActivityResultLauncher<Intent> LoadConfigFileLauncher;
    private static OutputStream DebugOutputStream;
    private static long BytesWrittenToDebugOutputStream;
    private static final long MAX_SIZE_DEBUG_OUTPUT = 10000000;
    private static CtDroneSpec.DroneSpecsChangedListener DroneSpecsChangedListener;
    private boolean recordingToMap;
    private String trackLabel;
    private static CaltopoClientMap MyCaltopoClientMap = null;
    private static Uri DebugLogPath = null;

    // CaltopoClient INSTANCE VARS:=
    private final String remoteId;
    private CtDroneSpec droneSpec;
    private CaltopoLiveTrack liveTrack;

    private final DelayedExec idleTimeoutPoll;
    private static DelayedExec UiUpdatePoll;
    private static long PreviousEarliestAgeOutInSeconds = 0;
    private static final ArrayList <CtDroneSpec>DsArray = new ArrayList<>(16);
    private static long DroneSpecsArraySize = DsArray.size();


    public CaltopoClient(String rid) throws RuntimeException {
        ClientClassState ccs = GetState();

        if (null == rid || rid.isEmpty()) {
            throw new RuntimeException("CaltopoClient() constructor missing/invalid remoteId");
        }
        remoteId = rid;
        droneSpec = ccs.droneSpecTable.get(rid);  // try archived value first.
        if (null == droneSpec) {
            droneSpec = new CtDroneSpec(rid);
            ccs.droneSpecTable.put(rid, droneSpec);
            ArchiveState("dronespec changed for " + rid);
        }
        droneSpec.setDroneSpecListener(this);
        idleTimeoutPoll = new DelayedExec();
    }
    public static void SetDroneSpecsChangedListener(CtDroneSpec.DroneSpecsChangedListener newListener) {
        DroneSpecsChangedListener = newListener;
    }

    public static int GetDebugLevel() {return DebugLevel;}

    @Nullable
    public static CtDroneSpec GetDroneSpec(@NonNull String remoteId) {
        ClientClassState ccs = GetState();
        return (ccs.droneSpecTable.get(remoteId));
    }

    /**  SetDroneSpecOwner()
     * @param dsIn This is the dronespec received from our peer who has assumed ownership of said drone.
     *             if there is no entry in our table for it, we'll create an entry with the peer's
     *             supplied rid and mappedId, but ignore everything else about it.  If there is
     *             already an existing dronespec, we only update the local dronespec's mappedId with
     *             the peer's mappedId if the peer's mappedId != remoteId.
     * @param owner This is the peer that has assumed ownership of the specified drone.
     */
    public static void SetDroneSpecOwner(@NonNull CtDroneSpec dsIn, @NonNull R2CRest owner) {
        ClientClassState ccs = GetState();
        String rid = dsIn.getRemoteId();
        String mid = dsIn.getMappedId();
        CtDroneSpec ds = ccs.droneSpecTable.get(rid);
        if (null == ds) {
            ds = new CtDroneSpec(rid, dsIn.getMappedId(), dsIn.getOrg(), dsIn.getModel(), dsIn.getOwner());
            ccs.droneSpecTable.put(rid, ds);
            ArchiveState("received new dronespec from our peer.");
        } else if (!mid.isEmpty() && !mid.equals(rid) && !mid.equals(ds.getMappedId())) {
            CTDebug(TAG, "SetDroneSpecOwner(): changing mappedId for '" + rid + "' to '" + mid + "'");
            ds.setMappedId(mid);
        }
        ds.setMyR2cOwner(owner);
        ds.setMyLiveTrack(null);
        dsIn.setMyR2cOwner(owner);
    }
    public static void RemoveDroneSpecOwner(@NonNull CtDroneSpec dsIn) {
        ClientClassState ccs = GetState();
        String rid = dsIn.getRemoteId();
        CtDroneSpec ds = ccs.droneSpecTable.get(rid);
        if (null != ds) ds.removeMyR2cOwner();
    }

    public static void UpdateDroneSpecs() {
        if (null != DroneSpecsChangedListener) {
            ArrayList<CtDroneSpec> droneSpecArray = GetSortedCurrentDroneSpecArray();
            DroneSpecsChangedListener.onDroneSpecsChanged(droneSpecArray);
            if (null == UiUpdatePoll) {
                UiUpdatePoll = new DelayedExec();
                DroneSpecsChangedListener.onDroneSpecsChanged(GetSortedCurrentDroneSpecArray());
                UiUpdatePoll.start(() -> {
                    DroneSpecsChangedListener.onDroneSpecsChanged(GetSortedCurrentDroneSpecArray());
                }, 1000, 1000);
            }
        }
    }
    public void mappedIdChanged(@NonNull CtDroneSpec ds, @NonNull String oldval, @NonNull String newval) {
        CTDebug(TAG, String.format(Locale.US,
                "mappedIdChanged(%s): change from '%s' to '%s'", trackLabel, oldval, newval));
        trackLabel = newTrackLabel();
        if (null != liveTrack) liveTrack.renameTrack(trackLabel);
        ArchiveState(String.format(Locale.US, "mappedIdChanged from '%s' to '%s'", oldval, newval));
        UpdateDroneSpecs();
    }
    public static String LoggingLevelName(int loggingLevel) {
        String retval;
        switch (loggingLevel) {
            case DebugLevelError: retval = "Errors only"; break;
            case DebugLevelDebug: retval = "Debugs"; break;
            case DebugLevelInfo: retval = "Info"; break;
            default: retval = "<undefined>";
        };
        return retval;
    }
    public static String BumpLoggingLevel() {
        DebugLevel++;
        if (DebugLevel > DebugLevelInfo) DebugLevel = DebugLevelError;
        String retval = LoggingLevelName(DebugLevel);
        ArchiveState("Logging level changed to: " + retval);
        return retval;
    }

    @Nullable
    public static Uri GetDebugLogPath() {return DebugLogPath;}

    public static void CTLog(String type, String tag, String msg) {
        if (null == AppActivity || null == AppContext || null == DebugOutputStream) {
            // attempt no logging to file without app context (caused by
            // logging from ScanningService during screen rotate).
            return;
        }
        if (BytesWrittenToDebugOutputStream >= MAX_SIZE_DEBUG_OUTPUT) return;

        try {
            if (null != type && null != tag) {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("ddLLLHHmmss.SSS");
                msg = String.format(Locale.US, "%s %s: %s %s\n  ", type,
                        LocalDateTime.now().format(formatter), tag, msg);
            }
            byte[] bytes = msg.getBytes();
            BytesWrittenToDebugOutputStream += bytes.length;
            DebugOutputStream.write(bytes);
            DebugOutputStream.flush();
        } catch (IOException e) {
            Log.e(TAG, String.format(Locale.US, "CTError: CTLog(): Not able to write '%s' - %s", LogFilePath, e));
        }
        if (BytesWrittenToDebugOutputStream >= MAX_SIZE_DEBUG_OUTPUT) {
            Log.e(TAG, "CTError: CTLog(): Sorry.  Maximum debugging output file size reached.  Future bits will be tossed on the floor.");
        }
    }

    public static void CTInfo(String tag, String msg){
        if ( DebugLevel >= DebugLevelInfo) {
            long myTid = android.os.Process.myTid();
            String tidString = (MainThreadId == myTid) ? "[main]" : "[" + myTid + "]";
            CTLog("INFO" + tidString, tag, msg);
            msg = "CTInfo" + tidString +  ": " + msg;
            Log.i(tag, msg);
        }
    }

    public static void CTDebug(String tag, String msg){
        if (DebugLevel >= DebugLevelDebug) {
            long myTid = android.os.Process.myTid();
            String tidString = (MainThreadId == myTid) ? "[main]" : "[" + myTid + "]";
            CTLog("DEBUG" + tidString, tag, msg);
            msg = "CTDebug" + tidString + ": " + msg;
            Log.d(tag, msg);
        }
    }

    public static void CTError(String tag, String msg) {
        long myTid = android.os.Process.myTid();
        String tidString = (MainThreadId == myTid) ? "[main]" : "[" + myTid + "]";
        CTLog("ERROR" + tidString,  tag, msg);
        msg = "CTError" + tidString + ": " + msg;
        Log.e(tag, msg);
    }

    public static String ExceptionToString(Exception e) {
        StringBuilder str = new StringBuilder();
        str.append(e);
        StackTraceElement[] stackTrace = e.getStackTrace();
        for (StackTraceElement element : stackTrace) {
            str.append("\n    ");
            str.append(element);
        }
        return str.toString();
    }

    public static void CTError(String tag, String msg, Exception e) {
        StringBuilder str = new StringBuilder();

        str.append(msg);
        str.append("\n  ");
        str.append(ExceptionToString(e));
        CTLog("ERROR", tag, str.toString());
        str.insert(0, "CTError: ");
        Log.e(tag, str.toString());
    }

    /**
     * Create/find a directory within the ArchiveDir with todays date
     * and return that as the directory to place trackfiles and logs in.
     *
     * @return DocumentFile path to existing directory on success and
     * null on failure.
     */
    public static DocumentFile GetTodaysTrackDir() {
        String archivePath = GetArchivePath();
        DocumentFile todaysDir = null;
        if (null != archivePath && null != AppContext) try {
            ContentResolver contentResolver = AppContext.getContentResolver();
            Uri treeUri = Uri.parse(archivePath);
            contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            DocumentFile archiveDir = DocumentFile.fromTreeUri(AppContext, treeUri);
            if (null != archiveDir) {
                SimpleDateFormat sdf = new SimpleDateFormat("ddMMMyyyy", Locale.US);
                String dirpath = "tracks-" + sdf.format(new Date());
                todaysDir = archiveDir.findFile(dirpath);
                if (null == todaysDir) {
                    todaysDir = archiveDir.createDirectory(dirpath);
                    if (null == todaysDir) {
                        Log.e(TAG, String.format(Locale.US, "CTError: GetTodaysTrackDir(): Not able to create '%s'", archiveDir));
                    } else {
                        Log.d(TAG, String.format(Locale.US, "CTDebug: GetTodaysTrackDir(): Created '%s'", archiveDir));
                    }
                } else {
                    Log.i(TAG, String.format(Locale.US, "CTDebug: GetTodaysTrackDir(): found existing '%s'", archiveDir));
                }
            }
        } catch (Exception e) {
            CTError(TAG, "Not able to create today's archive dir", e);
        }
        return todaysDir;
    }

    public static String GetTrackFolderName() {
        ClientClassState ccs = GetState();
        return ccs.caltopoTrackFolder;
    }

    /**
     * Setting a null or empty track folder name is OK!.  That tells the client
     * to put the tracks in the default track directory.
     *
     * @param folderName Folder to put tracks into - may be null or empty.
     */
    public static void SetTrackFolderName(String folderName) {
        ClientClassState ccs = GetState();
        boolean stateChanged = false;
        if (null != folderName && null != ccs.caltopoTrackFolder) {
            if (!folderName.equals(ccs.caltopoTrackFolder)) {
                stateChanged = true;
            }
        } else if (null != ccs.caltopoTrackFolder || null != folderName) {
            stateChanged = true;
        }

        if (stateChanged) {
            ccs.caltopoTrackFolder = folderName;
            ArchiveState( "Caltopo Track Folder changed.");
        }
    }

    @NonNull
    public static CaltopoSessionConfig GetCaltopoConfig() {
        ClientClassState ccs = GetState();
        return ccs.caltopoSessionConfig;
    }

    static void confirmMapIdChange(@NonNull String newMapId) {
        if (MapIdChangeDialog.getResponse()) {
            ClientClassState ccs = GetState();
            ccs.mapId = newMapId;
            ArchiveState("user changed mapId");
            CheckBringUpMap();
        }
    }

    /* This is the mapid portion of the caltopo map's URL. The
     * 'H61AV0G' portion of the following map URL:
     *      https://caltopo.com/m/H61AV0G
     * User can change from empty "" to some non-empty value.
     * This is usually the case where they're beginning a connection.
     *
     * User can also change from non-empty to different non-empty -
     * This is likely to be the case if they mis-typed the value to
     * start or opened the wrong map.  In this scenario, we want to
     * cleanly close the existing map and open a new map.  Any
     * outstanding track writes are either blocked on previous map
     * not working or will have thrown away their waypoints sent to
     * date and need to restart once the new map is up.
     *
     * User can also change from non-empty to empty.  In this case,
     * the user is saying they don't want to write any more waypoints
     * to the existing map.  Similar to above, sent waypoints are
     * already sent, but any new waypoints will merely be archived
     * until a new map is started.
     */
    @NonNull
    public static String SetMapId(@NonNull String newMapId) throws RuntimeException {
        ClientClassState ccs = GetState();
        final String trimmedMapId = newMapId.trim().replaceAll("[^a-zA-Z0-9]", "");
        if (!trimmedMapId.equals(ccs.mapId)) {
            if (null != MyCaltopoClientMap) {
                MapIdChangeDialog = new CtAlertDialog("Terminate map connection",
                        "Do you want to terminate the existing map connection?",
                        () -> confirmMapIdChange(trimmedMapId));
            } else {
                String msg = String.format(Locale.US, "mapId changed from '%s' to '%s'",
                        ccs.mapId, trimmedMapId);
                ccs.mapId = trimmedMapId;
                ArchiveState(msg);
                CheckBringUpMap();
            }
        }
        return ccs.mapId;
    }

    @NonNull
    public static String GetMapId() {
        ClientClassState ccs = GetState();
        return ccs.mapId;
    }

    public static void SetUseDirect(boolean flag) {
        ClientClassState ccs = GetState();
        if (ccs.useDirectFlag != flag) {
            ccs.useDirectFlag = flag;
            ArchiveState("useDirect changed to " + flag);
            CheckBringUpMap();
        }
    }

    public static boolean GetUseDirectFlag() {
        ClientClassState ccs = GetState();
        return ccs.useDirectFlag;
    }

    @NonNull
    public static CaltopoSessionConfig GetCaltopoSessionConfig() {
        ClientClassState ccs = GetState();
        return ccs.caltopoSessionConfig;
    }

    public static void SetCaltopoSessionConfig(@NonNull CaltopoSessionConfig cfg)
            throws RuntimeException {
        if (!CaltopoSessionConfig.sniffTest(cfg)) {
            throw new RuntimeException("CaltopoSessionConfig.setCaltopoConfig() bad spec.");
        }

        ClientClassState ccs = GetState();
        if (!CaltopoSessionConfig.configSpecsAreEqual(cfg, ccs.caltopoSessionConfig)) {
            ccs.caltopoSessionConfig = cfg;
            ArchiveState( "SessionConfigChanged to " + cfg);
        }
    }

    public static JSONObject ReadJsonFile(Uri uri) {
        StringBuilder stringBuilder = new StringBuilder();
        InputStream is;
        InputStreamReader isr;
        BufferedReader bufferedReader;
        JSONObject retval;

        try {
            is = AppActivity.getContentResolver().openInputStream(uri);
            isr = new InputStreamReader(is);
            bufferedReader = new BufferedReader(isr);
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                stringBuilder.append(line).append("\n");
            }
        } catch (IOException e) {
            ShowToast(String.format(Locale.US, "Not able to read '%s'", uri), e );
            return null;
        }

        try {
            retval = new JSONObject(stringBuilder.toString());
        } catch (JSONException e) {
            ShowToast(String.format(Locale.US, "Not able to parse '%s'", uri) , e);
            return null;
        }
        return retval;
    }
    public static void ShowToast(String msg) {
        CTError(TAG, "showToast():" + msg);
        ((R2CActivity)AppActivity).showToast(msg);
    }
    public static void ShowToast(String msg, Exception e) {
        CTError(TAG, "showToast():" + msg, e);
        msg = msg + "\n" + ExceptionToString(e);
        ((R2CActivity)AppActivity).showToast(msg);
    }

    public static void readCredentialsFileContent(JSONObject json)
            throws JSONException {
        String teamId = json.optString("team_id");
        String credentialId = json.optString("credential_id");
        String credentialSecret = json.optString("credential_secret");
        String trackFolder = json.optString("track_folder");
        String mapid = json.optString("map_id");
        String groupid = json.optString("group_id");
        if (!trackFolder.isEmpty()) SetTrackFolderName(trackFolder);
        if (!mapid.isEmpty()) SetMapId(mapid);
        if (!groupid.isEmpty()) SetGroupId(groupid);
        SetCaltopoSessionConfig(new CaltopoSessionConfig(teamId, credentialId, credentialSecret));
        boolean useDirectFlag = json.optBoolean("use_direct_flag");
        CTDebug(TAG, "readCredentialsFileContent(): read useDirectFlag as: " + useDirectFlag);
        SetUseDirect(useDirectFlag);
        CheckBringUpMap();
    }
    public static void readRidmapFileContent(JSONObject json) throws JSONException {
        JSONArray mapJson;
        int changeCount = 0;
        boolean replaceFlag = false;
        try {
            mapJson = json.optJSONArray("map");
            if (json.optString("load_type").equals("replace")) {
                replaceFlag = true;
            }
            CTDebug(TAG, "readRidmapFileContent(): setting load_type to " +
                    (replaceFlag ? "replace" : "merge"));
        } catch (NullPointerException e) {
            mapJson = null;
        }
        if (null == mapJson) {
            ShowToast("No map specified in file.");
            return;
        }
        ClientClassState ccs = GetState();
        Hashtable<String, CtDroneSpec> mergedTable = new Hashtable<>(16);
        CtDroneSpec ds;
        for (int i = 0; i < mapJson.length(); i++) {
            JSONObject entry = mapJson.getJSONObject(i);
            String rid = entry.optString("remoteId");
            String mid = entry.optString("mappedId");
            String org = entry.optString("org");
            String model = entry.optString("model");
            String owner = entry.optString("owner");
            ds = new CtDroneSpec(rid, mid, org, model, owner);
            CtDroneSpec existingDs = ccs.droneSpecTable.get(rid);
            if (null == existingDs) {
                changeCount++;
            } else {
                CTDebug(TAG, "readRidmapFileContent(): Found existing droneSpec for spec: " + existingDs);
                if (replaceFlag) {
                    if (existingDs.isDifferentFrom(ds)) {
                        changeCount++;
                        existingDs.setMappedId(ds.getMappedId());
                        existingDs.setOrg(ds.getOrg());
                        existingDs.setModel(ds.getModel());
                        existingDs.setOwner(ds.getOwner());
                        ds = existingDs;
                    } else {
                        CTDebug(TAG, "readRidmapFileContent(): no changes detected for spec: " + existingDs);
                    }
                } else {
                    existingDs.mergeWithNew(ds);
                    if (existingDs.isDifferentFrom(ds)) changeCount++;
                }
                CTDebug(TAG, "readRidmapFileContent(): updated ridspec: " + existingDs);
            }
            existingDs = mergedTable.get(rid);
            if (null != existingDs) {
                throw new JSONException(String.format(Locale.US,
                        "Illegal duplicate remoteId '%s' at table offset %d - file contents ignored.", rid, i));
            }
            CTDebug(TAG, String.format(Locale.US, "Adding dronespec:%s", ds));
            mergedTable.put(ds.getRemoteId(), ds);
        }

        // Be sure to include any existing mappings that weren't mentioned in the file:
        for (Map.Entry<String, CtDroneSpec> map : ccs.droneSpecTable.entrySet()) {
            String key = map.getKey();
            if (null == mergedTable.get(key)) {
                mergedTable.put(key, map.getValue());
            }
        }
        if (0 != changeCount) {
            ccs.droneSpecTable = mergedTable;
            ArchiveState("merged ridmap with updates/changes.");
            UpdateDroneSpecs();
        }
    }

    // yes, I know it always returns null, but that's required by Function<T, R> interface
    // and besides, the return value is unused.
    public static String LoadConfigFile(Uri uri) {
        if (null == uri) return null;
        try {
            JSONObject json = ReadJsonFile(uri);
            if (null == json) return null;
            String type = json.optString("type").trim().toLowerCase();
            String fileVersion = json.optString("file_version");
            String updated = json.optString("updated");
            String editor = json.optString("editor");
            CTDebug(TAG, String.format(Locale.US, "Reading v%s %s config file last updated by %s on %s",
                    fileVersion, type, editor, updated));

            if (type.equals("ct_ridmap")) {
                readRidmapFileContent(json);
            } else if (type.equals("ct_credentials")) {
                readCredentialsFileContent(json);
            }
        } catch (JSONException e) {
            CTError(TAG, String.format(Locale.US,"Error processing '%s':", uri), e);
        }
        return null;
    }

    public static void RequestLoadConfigFile() {
        RequestConfigFile(LoadConfigFileMessage, LoadConfigFileLauncher);
    }

    public static void RequestConfigFile(String requestMessage, ActivityResultLauncher<Intent> launcher) {
        Intent requestFileIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        requestFileIntent.addCategory(Intent.CATEGORY_OPENABLE);
        requestFileIntent.setType("application/json");

        try {
            launcher.launch(requestFileIntent);
        } catch (Exception e) {
            CTError(TAG, String.format(Locale.US, "RequestConfigFile(%s).launcher() raised:", requestMessage), e);
        }
    }

    public static void QueryUserForArchiveDir() {
        final String EXTERNAL_STORAGE_PROVIDER_AUTHORITY = "com.android.externalstorage.documents";

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.putExtra(Intent.EXTRA_TITLE, "Select directory to archive drone tracks");
        Uri downloadsUri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_PROVIDER_AUTHORITY, "primary:Downloads");
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadsUri);
        CTDebug(TAG, String.format(Locale.US, "In QueryUserForArchiveDir(%s)", intent));
        try {
            QueryArchivePath.launch(intent);
        } catch (Exception e) {
            CTError(TAG, "queryUserForArchiveDir() raised:", e);
        }
    }
    private static ActivityResultLauncher<Intent> InitLauncherForConfigFile(String requestMessage, Function<Uri, String> fileProcessor) {
        return AppActivity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    CTDebug(TAG, String.format(Locale.US, "In InitLauncherForConfigFile(%s):onActivityResult(%s)",
                            requestMessage, result.toString()));
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Intent data = result.getData();
                        if (null != data) {
                            Uri jsonUri = data.getData();
                            // Persist the URI for later use (e.g., in SharedPreferences)
                            // Now you have a Uri representing the selected file:
                            fileProcessor.apply(jsonUri);
                        }
                    }
                });
    }


    public static ActivityResultLauncher<Intent> InitLauncherForArchiveDir() {
        return AppActivity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    CTDebug(TAG, String.format(Locale.US, "In queryArchivePath:onActivityResult(%s)", result.toString()));
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Intent data = result.getData();
                        if (null != data) {
                            Uri treeUri = data.getData();
                            if (null != treeUri) {
                                // Persist the URI for later use (e.g., in SharedPreferences)
                                AppActivity.getContentResolver().takePersistableUriPermission(treeUri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                                // Now you have a Uri representing the selected directory tree, which likely includes Downloads
                                // You can use DocumentFile to work with files within this directory
                                SetArchivePath(treeUri.toString());
                            }
                        }
                    }
                });
    }

    public static void ConnectToMap() {
        String mapId = GetMapId();
        if (!GetUseDirectFlag()) return;
        if (null != MyCaltopoClientMap) {
            String existingMapId = MyCaltopoClientMap.getMapId();
            if (!existingMapId.equals(mapId)) {
                CTDebug(TAG, String.format(Locale.US,
                        "ConnectToMap() changing from '%s' map to '%s' map.", existingMapId, mapId));
                MyCaltopoClientMap.setMapId(mapId);
            }
        } else try {
            CTDebug(TAG, "connectToMap(): connecting to map " + mapId);
            MyCaltopoClientMap = new CaltopoClientMap(GetCaltopoConfig(), mapId, GetTrackFolderName());
        } catch (RuntimeException e) {
            ShowToast("could not open map: ", e);
        }
    }

    public static void PermissionsGrantedWeShouldBeGoodToGo() {
        InitArchiveDir();
        CheckBringUpMap();
    }
    public static void InitializeForActivityAndContext(AppCompatActivity activity, Context ctxt) {
        AppActivity = activity;
        AppContext = ctxt;
        GetState();
        CTDebug(TAG, "InitializeForActivityAndContext()");
        try {
            QueryArchivePath = InitLauncherForArchiveDir();
            LoadConfigFileLauncher = InitLauncherForConfigFile(LoadConfigFileMessage, CaltopoClient::LoadConfigFile);
        } catch (Exception e) {
            CTError(TAG, "InitializeForActivityAndContext() raised:", e);
        }
    }

    @NonNull
    public static CaltopoClient ClientForRemoteId(@NonNull String remoteId)
            throws RuntimeException {
        if (null == ClientMap) {
            ClientMap = new Hashtable<>(16);
        }
        if (remoteId.isEmpty()) {
            throw new RuntimeException("CaltopoClient.ClientForRemoteId(): Invalid remoteId");
        }
        CaltopoClient client = ClientMap.get(remoteId);
        if (null == client) {
            client = new CaltopoClient(remoteId);
            ClientMap.put(remoteId, client);
        }
        return client;
    }

    @NonNull
    public static String DroneSpecStringRep(@NonNull Hashtable<String, CtDroneSpec> ht) {
        int count = ht.size();
        StringBuilder retval = new StringBuilder(String.format(Locale.US, "%d k/v pairs:", count));

        for (Map.Entry<String, CtDroneSpec> map : ht.entrySet()) {
            CtDroneSpec ds = map.getValue();
            retval.append("\n  ");
            retval.append(ds);
        }
        return retval.toString();
    }

    // can return null if no stored state available.
    @Nullable
    private static ClientClassState RestoreState() {
        ClientClassState ccs;
        if (null == AppContext) return null;
        try {
            FileInputStream fis = AppContext.openFileInput(MyStateFileName);
            ObjectInputStream ois = new ObjectInputStream(fis);
            ccs = (ClientClassState) ois.readObject();
            ois.close();
        } catch (FileNotFoundException e) {
            Log.e(TAG, "CTError: restoreState() no archive to restore from:", e);
            ccs = null;
        } catch (InvalidClassException e) {
            Log.e(TAG, "CTError: restoreState() not able to restore incompatible version of state:", e);
            ccs = null;
        } catch (Exception e) {
            Log.e(TAG, "CTError: restoreState() raised:", e);
            ccs = null;
        }
        if (null != ccs && ccs.debugLevel >= 0) DebugLevel = ccs.debugLevel;
        return ccs;
    }

    @NonNull
    private static ClientClassState GetState() {
        if (null == Ccstate) {
            ClientClassState ccs = RestoreState();
            if (null == ccs) ccs = new ClientClassState();
            Ccstate = ccs;
            Log.d(TAG, "CTDebug: GetState(): " + Ccstate);
        }
        return Ccstate;
    }

    private static void ArchiveState(@NonNull String reason) {
        if (null != Ccstate) try {
            if (null == AppContext) return;
            Ccstate.debugLevel = DebugLevel;
            FileOutputStream fos = AppContext.openFileOutput(MyStateFileName, 0);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(Ccstate);
            oos.flush();
            oos.close();
            CTDebug(TAG, String.format(Locale.US, "ArchiveState(%s):\n%s", reason, Ccstate));

            //  Files.move(Paths.get(MyTemporaryStateFileName), Paths.get(MyStateFileName), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            CTError(TAG, "ArchiveState() raised:", e);
        }
    }

    /**
     * @return Return an array of CtDroneSpecs for drones that have been seen
     * * within the previous ageInSeconds (or all if ageInSeconds == 0).
     */
    @NonNull
    public static ArrayList<CtDroneSpec> GetSortedCurrentDroneSpecArray() {
        ClientClassState ccs = GetState();
        long retirementAgeInSeconds = GetMaxDisplayAgeInSeconds();
        return GetSortedCurrentDroneSpecArray(retirementAgeInSeconds);
    }

    @NonNull
    public static ArrayList<CtDroneSpec> GetSortedCurrentDroneSpecArray(long retirementAgeInSeconds) {
        ClientClassState ccs = GetState();
        long newSize = ccs.droneSpecTable.size();
        boolean arrayChangedFlag = newSize != DroneSpecsArraySize;

        DroneSpecsArraySize = newSize;
        if (retirementAgeInSeconds > 0) {
            long currentTimeInSeconds = (System.currentTimeMillis() / 1000);
            long nextAgeOutInSecondsFromNow = retirementAgeInSeconds;

            if (arrayChangedFlag || currentTimeInSeconds >= PreviousEarliestAgeOutInSeconds) {
                CTDebug(TAG, "GetSortedCurrentDroneSpecArray(): rebuilding array.");
                DsArray.clear();
                for (CtDroneSpec ds : ccs.droneSpecTable.values()) {
                    if (0 != ds.mostRecentTimeInSeconds) {
                        long currentAgeInSeconds = currentTimeInSeconds - ds.mostRecentTimeInSeconds;
                        long currentAgeOutInSeconds = retirementAgeInSeconds - currentAgeInSeconds;
                        if (currentAgeOutInSeconds > 0) {
                            if (currentAgeOutInSeconds < nextAgeOutInSecondsFromNow) nextAgeOutInSecondsFromNow = currentAgeOutInSeconds;
                            CTDebug(TAG, String.format(Locale.US,
                                    "  current age for %s is %d, age out in %d seconds. next age out in %d seconds", ds.getMappedId(),
                                    currentAgeInSeconds, currentAgeOutInSeconds, nextAgeOutInSecondsFromNow));
                            DsArray.add(ds);
                        }
                    }
                }
                PreviousEarliestAgeOutInSeconds = currentTimeInSeconds + nextAgeOutInSecondsFromNow;
            }
            DsArray.sort(null);
        } else if (arrayChangedFlag) {
            CTDebug(TAG, "GetSortedCurrentDroneSpecArray() Adding all drones to the list");
            DsArray.clear();
            for (Map.Entry<String, CtDroneSpec> map : ccs.droneSpecTable.entrySet()) {
                CtDroneSpec ds = map.getValue();
                CTDebug(TAG, String.format(Locale.US,
                        "  Adding %s to list", ds.getMappedId()));
                DsArray.add(ds);
            }
            DsArray.sort(null);
        } else {
            CTDebug(TAG, "GetSortedCurrentDroneSpecArray() resubmitting: " + DsArray);
        }
        return (ArrayList<CtDroneSpec>) DsArray.clone();
    }

    public static String GetGroupId() {
        ClientClassState ccs = GetState();
        return ccs.groupId;
    }

    public static long GetNewTrackDelayInSeconds() {
        ClientClassState ccs = GetState();
        return ccs.newTrackDelayInSeconds;
    }

    public static long GetMaxDisplayAgeInSeconds() {
        ClientClassState ccs = GetState();
        return ccs.maxDisplayAgeInSeconds;
    }
    public static long GetMinDistanceInFeet() {
        ClientClassState ccs = GetState();
        return ccs.minDistanceInFeet;
    }

    public static String GetArchivePath() {
        ClientClassState ccs = GetState();
        return ccs.archivePath;
    }

    public static void CheckBringUpMap() {
        String groupId = GetGroupId();
        String mapId = GetMapId();
        if (!GetUseDirectFlag() || null != MyCaltopoClientMap ||
                null == DebugOutputStream || null == groupId || groupId.isEmpty() || mapId.isEmpty()) return;
        CTDebug(TAG, "CheckBringUpMap()...");
        ConnectToMap();
    }

    public static void InitArchiveDir() {
        if (null == DebugOutputStream && null != GetArchivePath()) try {
            DocumentFile todaysArchiveDir = GetTodaysTrackDir();
            String filepath = "Log_" + TimeDatestampString();
            if (null != todaysArchiveDir) try {
                DocumentFile dataFilepath = todaysArchiveDir.createFile("text/plain", filepath);
                ContentResolver resolver = AppContext.getContentResolver();
                if (null != dataFilepath) {
                    DebugLogPath = dataFilepath.getUri();
                    DebugOutputStream = (resolver.openOutputStream(DebugLogPath));
                }
            } catch (Exception e) {
                CTError(TAG, "InitArchiveDir() raised: ", e);
            }

            if (null != DebugOutputStream) {
                LogFilePath = todaysArchiveDir + "/" + filepath;
                Resources resources = AppActivity.getResources();
                String appVers = resources.getString(R.string.app_version);
                final String header = "########################################################################\n";
                CTDebug(TAG, String.format(Locale.US,
                        "Logfile is up on %s @%s\n%s#  RID2Caltopo %s(%s) running on Android OS v%s(%d)\n#  Writing logs to: %s\n%s",
                        R2CActivity.MyDeviceName, R2CRest.GetMyIpAddresses().toString(), header, appVers,
                        BuildConfig.BUILD_TIME, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, LogFilePath, header));
                CheckBringUpMap();
            }

        } catch (Exception e) {
            Log.e(TAG, "CTError: Not able to open DebugOutputStream: " + e);
        }
    }
    public static void SetArchivePath(String path)
            throws RuntimeException {
        if (null == path || path.isEmpty()) {
            throw new RuntimeException("CaltopoClient.SetArchivePath() invalid path.");
        }
        ClientClassState ccs = GetState();
        ccs.archivePath = path;
        ArchiveState("archivePath changed.");
        InitArchiveDir();
    }

    /* SetGroupId():
     * Changing groupId only affects legacy LiveTracks (specified w/Caltopo web GUI).
     * Caltopo Direct LiveTracks only look at the groupId when they begin, so any
     * currently active tracks will continue on oblivious to any groupId changes.
     */
    @NonNull
    public static String SetGroupId(@NonNull String gid) {
        ClientClassState ccs = GetState();
        String oldGid = ccs.groupId;
        final String trimmedGid = gid.replaceAll("[^A-Z0-9]", "");
        if (oldGid.equals(trimmedGid)) return ccs.groupId;

        ccs.groupId = trimmedGid;
        WarnMissingGroupId = false;
        ArchiveState("groupId changed."); // save any time there is a chg.
        CheckBringUpMap();
        return ccs.groupId;
    }

    public static void Shutdown() {
        if (ExecutorPool != null) {
            ExecutorPool.shutdownNow();
        }
        if (null != MyCaltopoClientMap) {
            CaltopoClientMap.Shutdown();
        }
        if (null != DebugOutputStream) {
            try {
                DebugOutputStream.flush();
                DebugOutputStream.close();
                DebugOutputStream = null;
            } catch (IOException e) {
                Log.e(TAG, "CTError: Shutdown raised: " + e);
            }
            if (null != UiUpdatePoll) UiUpdatePoll.stop();
        }
    }


    /* Maybe a better name would be MaxIdleTimeInSeconds.   If the delay between
     * waypoints exceeds this value, the track is terminated and a new track is
     * started.
     */
    public static long SetNewTrackDelayInSeconds(long delayInSeconds) {
        ClientClassState ccs = GetState();

        if (delayInSeconds < MIN_NEW_TRACK_DELAY_IN_SECONDS) {
            delayInSeconds = MIN_NEW_TRACK_DELAY_IN_SECONDS;
        }

        if (ccs.newTrackDelayInSeconds != delayInSeconds) {
            ccs.newTrackDelayInSeconds = delayInSeconds;
            ArchiveState("newTrackDelayInSeconds changed");
        }
        return ccs.newTrackDelayInSeconds;
    }

    /* FIXME: Is this necessary/useful?
     */
    public static void SetMaxDisplayAgeInSeconds(long delayInSeconds) {
        ClientClassState ccs = GetState();
        if (ccs.maxDisplayAgeInSeconds != delayInSeconds) {
            ccs.maxDisplayAgeInSeconds = delayInSeconds;
            ArchiveState("maxDisplayAgeInSeconds changed.");
        }
    }

    /* minimum distance in feet between waypoints necessary to
     * record a new waypoint.
     */
    public static long setMinDistanceInFeet(long minDistance) {
        ClientClassState ccs = GetState();
        if (minDistance < MIN_DISTANCE_IN_FEET) {
            minDistance = MIN_DISTANCE_IN_FEET;
        }
        if (ccs.minDistanceInFeet != minDistance) {
            ccs.minDistanceInFeet = minDistance;
            ArchiveState("minDistanceInFeet changed");
        }
        return ccs.minDistanceInFeet;
    }

    public static String TimeDatestampString() {
        // Yes, we really want the timestamp first to make it easier to spot
        // the latest track in caltopo's tiny feature window.
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HHmmssLLLdd");
        return LocalDateTime.now().format(formatter);
    }

    // CaltopoClient instance methods:

    @Override
    @NonNull
    public String toString() {
        return String.format(Locale.US,
                "  rid:%s, mapped:%s", remoteId, droneSpec.getMappedId());
    }


    @NonNull
    public String newTrackLabel() {
        return droneSpec.getMappedId() + "_" + TimeDatestampString();
    }

    /* this is used when Caltopo Session is not used, requiring user to set up the
     * LiveTrack in Caltopo web interface.
     */
    public void bgPublishLive(String groupId, String deviceId, double lat, double lng) {
        String https_url = String.format(Locale.US, "%s%s?id=%s&lat=%.6f&lng=%.6f",
                BASE_URL, groupId, deviceId, lat, lng);
        try {
            URL url = new URL(https_url);
            HttpsURLConnection httpsConn;
            int responseCode;

            //    Log.i(TAG, "sending to caltopo: " + https_url);
            httpsConn = (HttpsURLConnection) url.openConnection();
            httpsConn.setRequestMethod("GET");
            httpsConn.setRequestProperty("User-Agent", "RID2Caltopo/0.1");
            responseCode = httpsConn.getResponseCode();
            if (HttpsURLConnection.HTTP_OK != responseCode) {
                CTError(TAG, "https bad response: " + responseCode);
            }
        } catch (IOException e) {
            CTError(TAG, "openConnection() raised:", e);
            ExecutorPool.shutdown();
            SetGroupId(""); // prevent new attempts til problem resolved.
        }
    }

    public long unsavedMsgCount() {
        if (null == trackLabel) return 0;
        return WaypointTrack.UnsavedMsgCountForTrack(trackLabel);
    }

    public void publishLive(double lat, double lng, String groupId) {
        try {
            if (null == ExecutorPool) {
                ExecutorPool = Executors.newFixedThreadPool(ThreadPoolSize);
            }
            ExecutorPool.submit(() -> bgPublishLive(groupId, droneSpec.getRemoteId(), lat, lng));
        } catch (Exception e) {
            CTError(TAG, "executorPool.submit() raised:", e);
            if (null != ExecutorPool) {
                ExecutorPool.shutdown();
            }
            SetGroupId(""); // prevent further messages to caltopo til problem resolved.
        }
    }

    public void terminateTrack(String msg) {
        if (null != trackLabel) {
            if (null != liveTrack && liveTrack.isActive()) {
                CTDebug(TAG, msg);
                liveTrack.finishTrack(msg);
            }
            trackLabel = null; // this tells us this track is done.
            idleTimeoutPoll.stop();
        }
    }

    // Make sure we catch idle drone tracks and archive them as soon as they are declared dormant.
    public void checkIdleTime() {
        if ( null != liveTrack && liveTrack.isActive() ) {
            long idleDurationInMilliseconds;
            long maxIdleDelayInMilliseconds = 1000 * GetNewTrackDelayInSeconds();
            long currentTimeInMilliseconds = System.currentTimeMillis();

            if (0 == droneSpec.mostRecentTimeInSeconds) {
                idleDurationInMilliseconds = 0;
            } else {
                idleDurationInMilliseconds = currentTimeInMilliseconds - (droneSpec.mostRecentTimeInSeconds * 1000);
            }
            if (null != trackLabel) {
                String msg = String.format(Locale.US,
                        "checkIdleTime(%s) has been idle for %.3f/%.3f seconds.", trackLabel,
                        (double) idleDurationInMilliseconds / 1000.0, (double) maxIdleDelayInMilliseconds / 1000.0);
                CTInfo(TAG, msg);

                if (idleDurationInMilliseconds > maxIdleDelayInMilliseconds) {
                    msg = msg + "  Finishing track...";
                    terminateTrack(msg);
                } else {
                    long delayInMsec = maxIdleDelayInMilliseconds - idleDurationInMilliseconds;
                    CTInfo(TAG, String.format(Locale.US, "checkIdleTime(%s) Restarting timer for another %.3f seconds.",
                            trackLabel, delayInMsec / 1000.0));
                    idleTimeoutPoll.start(this::checkIdleTime, delayInMsec, 0);
                }
            }
        }
    }
    private boolean recordingToMapStatusChanged() {
        return (null != trackLabel && (recordingToMap != (null != MyCaltopoClientMap && MyCaltopoClientMap.getMapIsUp())));
    }

    /** newWaypoint() - process a new waypoint from OpenDroneIdDataManager().
     *  Note that lat, lng, altitudeInMeters, and droneTimestampInSeconds are all values
     *  provided by the drone's remote id module and quality of measurement is going to
     *  vary from one source to the next.  Do a basic sanity check on anything before
     *  relying on it.
     */
    public boolean newWaypoint(double lat, double lng, long altitudeInMeters, long droneTimestampInMilliseconds, CtDroneSpec.TransportTypeEnum transportType) {
        boolean useDirectFlag = GetUseDirectFlag();

        if (recordingToMapStatusChanged()) {
            // We're currently in the middle of recording a track and user changed configuration on us...
            terminateTrack("recordingToMapStatusChanged()");
            recordingToMap = !recordingToMap;
            if (recordingToMap) ConnectToMap();
        }
        if (null == trackLabel) {
            trackLabel = newTrackLabel();
            droneSpec.startTimer();
            UpdateDroneSpecs();
        }
        if (null != droneSpec) droneSpec.bumpTransportCount(transportType);
        if (-1000 == altitudeInMeters || (0.0 == lat && 0.0 == lng)) {
            CTInfo(TAG, String.format(Locale.US,
                    "newWaypoint(%s/%s) w/Invalid altitude %d and/or coordinates %.7f, %.7f - ignoring.",
                    trackLabel, transportType, altitudeInMeters, lat, lng));
            return false; // only interested in recording real waypoints thank-you very much
        }
        boolean archived = WaypointTrack.AddWaypointForTrack(trackLabel, lat, lng, altitudeInMeters, droneTimestampInMilliseconds, transportType);
        if (archived) {
            if (null != droneSpec) droneSpec.mostRecentTimeInSeconds = System.currentTimeMillis() / 1000;
            String groupId = GetGroupId();
            if (groupId.isEmpty()) {
                if (!WarnMissingGroupId) {
                    ShowToast("Can't forward waypoint to caltopo - 'groupId' not specified in Caltopo Config panel.");
                    WarnMissingGroupId = true;
                }
                return true;
            } else WarnMissingGroupId = false;

            if (useDirectFlag) {
                String mapId = GetMapId();
                if (mapId.isEmpty()) {
                    if (!WarnMissingMapFlag) {
                        ShowToast("Can't forward waypoint to caltopo - 'mapId' not specified in Caltopo Config panel.");
                        WarnMissingMapFlag = true;
                    }
                    return true;
                } else WarnMissingMapFlag = false;

                if (null == MyCaltopoClientMap || !MyCaltopoClientMap.getMapIsUp()) {
                    if (!WarnConnectingToMapFlag) {
                        ConnectToMap();
                        ShowToast("Connecting to map...");
                        WarnConnectingToMapFlag = true;
                    }
                    return true;
                } else WarnConnectingToMapFlag = false;
                recordingToMap = true;
                if (null == liveTrack) {
                    liveTrack = new CaltopoLiveTrack(MyCaltopoClientMap, trackLabel, GetGroupId(), droneSpec, lat, lng, droneTimestampInMilliseconds);
                } else if (!liveTrack.isActive()) {
                    liveTrack.startNewTrack(trackLabel, lat, lng, droneTimestampInMilliseconds);
                }
                liveTrack.publishDirect(lat, lng, altitudeInMeters, droneTimestampInMilliseconds);

                // Use the idleTimeoutPoll to identify dead tracks.
                if (!idleTimeoutPoll.isRunning()) {
                    idleTimeoutPoll.start(this::checkIdleTime,
                            GetNewTrackDelayInSeconds() * 1000, 0);
                }
            } else {
                try {
                    publishLive(lat, lng, groupId);
                } catch (Exception e) {
                    CTError(TAG, "publishLive() raised:", e);
                }
            }
        }
        return archived;
    }
}
