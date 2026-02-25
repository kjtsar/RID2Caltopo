
/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.data;

import static androidx.core.content.ContextCompat.getSystemService;
import static org.ncssar.rid2caltopo.data.CaltopoClient.LoggingLevelName;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.provider.OpenableColumns;
import android.util.AtomicFile;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.*;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.ncssar.rid2caltopo.BuildConfig;
import org.ncssar.rid2caltopo.R;
import org.ncssar.rid2caltopo.app.MediaMTXService;
import org.ncssar.rid2caltopo.app.R2CActivity;
import org.ncssar.rid2caltopo.app.R2CApplication;
import org.ncssar.rid2caltopo.app.ScanningService;
import com.google.firebase.analytics.FirebaseAnalytics;

import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/*
 * Persistent state management for CaltopoClient
 */
class ClientClassState implements Serializable {
    private static final long SerialVersionUID = 28L; // Serializable version.
    public long minDistanceInFeet;
    public String archivePath;
    public String caltopoTrackFolder;
    public String caltopoDomainAndPort;
    public CaltopoCredentials caltopoCredentials;
    public long newTrackDelayInSeconds;
    public int debugLevel;
    public long maxIdleTimeInMinutes;
    public String incident;
    public String opPeriod;
    public String trackerApiKey;
    public String trackerUrlPfx;
    public Hashtable<String, CtDroneSpec> cachedDroneSpecTable;  // Table to map remoteIDs to their data
    public String configFilesLoaded;
    transient public boolean goLiveFlag;

    transient public Hashtable<String, CtDroneSpec> droneSpecTable; // app lifespan only.
    transient public boolean usePeersFlag; // newline separated list of configfile specs that we're loaded.

    // Default/initial state for the caltopo client:
    ClientClassState() {
        minDistanceInFeet = CaltopoClient.MIN_DISTANCE_IN_FEET;
        archivePath = "";
        caltopoTrackFolder = "Drone Tracks";
        caltopoCredentials = new CaltopoCredentials();
        caltopoDomainAndPort = "caltopo.com";
        goLiveFlag = false;
        usePeersFlag = false;
        newTrackDelayInSeconds = 30;
        maxIdleTimeInMinutes = 120;
        debugLevel = -1; // undefined.
        incident = "Training";
        opPeriod = "1";
        trackerApiKey = "";
        trackerUrlPfx = "";
        configFilesLoaded = "";
        cachedDroneSpecTable = new Hashtable<>(16);
        droneSpecTable = new Hashtable<>(16);
    }
    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        if (null == caltopoDomainAndPort) caltopoDomainAndPort = "";
        if (null == archivePath) archivePath = "";
        if (null == trackerApiKey) trackerApiKey = "";
        if (null == trackerUrlPfx) trackerUrlPfx = "";
        if (null == configFilesLoaded) configFilesLoaded = "";
        if (null == droneSpecTable) droneSpecTable = new Hashtable<>(16);
    }

    @Override
    @NonNull
    public String toString() {
        CaltopoCredentials cred = caltopoCredentials;
        String domainAndPort = "";
        String teamId = "";
        String credId = "";
        String credSecret = "";
        if (null != caltopoDomainAndPort && !caltopoDomainAndPort.isEmpty()) {
            domainAndPort = caltopoDomainAndPort;
        }
        if (null != cred) {
            if (null != cred.teamId && !cred.teamId.isEmpty()) {
                // teamId = cfg.teamId;
                teamId = "###";
            }
            if (null != cred.credentialId && !cred.credentialId.isEmpty()) {
                // credId = cfg.credentialId;
                credId = "######";
            }
            if (null != cred.credentialSecret && !cred.credentialSecret.isEmpty()) {
                // credSecret = cfg.credentialSecret;
                credSecret = "###########";
            }
        }

        return String.format(Locale.US,
                """
                        vers:'%d', minDist:'%d' ft, usePeersFlag:'%s'
                        newTrackDelayInSec:%d, debugLevel:%s, maxIdleTimeInMinutes:%d, incident:%s, opPeriod:%s
                        archivePath: '%s', caltopoTrackFolder: '%s', caltopoDomainAndPort:%s,
                        teamId: '%s', credId: '%s' credSecret: '%s', dronespecs: %s,\n loaded configFiles:\n  %s""",
                SerialVersionUID, minDistanceInFeet, usePeersFlag,
                newTrackDelayInSeconds, LoggingLevelName(debugLevel), maxIdleTimeInMinutes,
                incident, opPeriod, archivePath, caltopoTrackFolder, domainAndPort, teamId, credId, credSecret,
                CaltopoClient.DroneSpecStringRep(cachedDroneSpecTable),
                configFilesLoaded.replaceAll("\\n", "  \n"));
    }
}

public class CaltopoClient implements CtDroneSpec.CtDroneSpecListener {
    public interface ClientSettingsListener {
        void settingsChanged();
    }

    // CaltopoClient CLASS VARS:
    private static ClientSettingsListener SettingsListener = null;

    static final long MIN_DISTANCE_IN_FEET = 2;
    static final long MIN_NEW_TRACK_DELAY_IN_SECONDS = 15;
    static final long MainThreadId = Process.myTid();
    static final long ProcessId = Process.myPid();
    private static final String BASE_URL = "https://caltopo.com/api/v1/position/report/";
    private static final String TAG = "CaltopoClient";
    public static final int DebugLevelError = 0;
    public static final int DebugLevelWarn = 1;
    public static final int DebugLevelDebug = 2;
    public static final int DebugLevelInfo = 3;
    public static int DebugLevel = DebugLevelDebug;
    private static final Object DebugTagFilterLock = new Object();
    private static boolean DebugTagFilterEnabled = false;
    private static final Set<String> DebugTagFilter = new HashSet<>();
    private static boolean DebugTagRegistryInitialized = false;
    private static final int LivePublishThreadPoolSize = 2;
    private static final int GeoJsonStatsThreadPoolSize = 2;
    private static final int ArchiveScanThreadPoolSize = 1;
    private static boolean WarnMissingMapFlag = false;
    private static Hashtable<String, CaltopoClient> ClientMap;
    private static ExecutorService LivePublishExecutorPool = null;
    private static ExecutorService GeoJsonStatsExecutorPool = null;
    private static ExecutorService ArchiveScanExecutorPool = null;
    private static ClientClassState Ccstate = null;
    private static final String LEGACY_STATE_FILE_NAME = TAG + ".ser";
    private static final String SECURE_STATE_FILE_NAME = TAG + ".state";
    private static final String SECURE_STATE_KEY_ALIAS = "RID2Caltopo.StateKey.v1";
    private static final String SECURE_STATE_HEADER = "R2CS2";
    private static final int SECURE_STATE_VERSION = 1;
    private static String LogFilePath;
    private static OutputStream DebugOutputStream;
    private static long BytesWrittenToDebugOutputStream;
    private static final long MAX_SIZE_DEBUG_OUTPUT = 10000000;
    private static ArrayList<CtDroneSpec.DroneSpecsChangedListener> DroneSpecsChangedListeners = new ArrayList<>();
    private static Uri DebugLogPath = null;
    private static DelayedExec AppIdleDelay = new DelayedExec();
    private static FirebaseAnalytics FBAnalytics;
    private static final Object ShutdownLock = new Object();
    private static boolean ShutdownInProgress = false;
    private static boolean AppExitRequested = false;

    // CaltopoClient INSTANCE VARS:=
    private final String remoteId;
    private CtDroneSpec droneSpec;
    private CaltopoLiveTrack liveTrack;

    private final DelayedExec idleTimeoutPoll;
    private static DelayedExec UiUpdatePoll = new DelayedExec();
    ;
    private static long PreviousEarliestAgeOutInMsec = 0;
    private static final ArrayList<CtDroneSpec> DsArray = new ArrayList<>(16);
    private static long DroneSpecsArraySize = DsArray.size();
    private static boolean NotifySettingsChangedFlag;

    public static class PositionTelemetry {
        @Nullable public final Double aircraftAltitudeFt;
        @Nullable public final Double aircraftAltitudeRateFpm;
        @Nullable public final Double aircraftGsKnots;
        @Nullable public final Double aircraftHeadingDeg;
        @Nullable public final Double aircraftTrackDeg;
        @Nullable public final Double aircraftPitchDeg;
        @Nullable public final Double aircraftRollDeg;
        @Nullable public final Double cameraAzimuthDeg;
        @Nullable public final Double cameraTiltDeg;
        @Nullable public final Double cameraFovWidthDeg;
        @Nullable public final Double cameraFovHeightDeg;
        @Nullable public final String cameraExternalUrl;
        @Nullable public final String cameraThumbnailUrl;

        public PositionTelemetry(
                @Nullable Double aircraftAltitudeFt,
                @Nullable Double aircraftAltitudeRateFpm,
                @Nullable Double aircraftGsKnots,
                @Nullable Double aircraftHeadingDeg,
                @Nullable Double aircraftTrackDeg,
                @Nullable Double aircraftPitchDeg,
                @Nullable Double aircraftRollDeg,
                @Nullable Double cameraAzimuthDeg,
                @Nullable Double cameraTiltDeg,
                @Nullable Double cameraFovWidthDeg,
                @Nullable Double cameraFovHeightDeg,
                @Nullable String cameraExternalUrl,
                @Nullable String cameraThumbnailUrl
        ) {
            this.aircraftAltitudeFt = aircraftAltitudeFt;
            this.aircraftAltitudeRateFpm = aircraftAltitudeRateFpm;
            this.aircraftGsKnots = aircraftGsKnots;
            this.aircraftHeadingDeg = aircraftHeadingDeg;
            this.aircraftTrackDeg = aircraftTrackDeg;
            this.aircraftPitchDeg = aircraftPitchDeg;
            this.aircraftRollDeg = aircraftRollDeg;
            this.cameraAzimuthDeg = cameraAzimuthDeg;
            this.cameraTiltDeg = cameraTiltDeg;
            this.cameraFovWidthDeg = cameraFovWidthDeg;
            this.cameraFovHeightDeg = cameraFovHeightDeg;
            this.cameraExternalUrl = cameraExternalUrl;
            this.cameraThumbnailUrl = cameraThumbnailUrl;
        }
    }


    public CaltopoClient(String rid) throws RuntimeException {
        ClientClassState ccs = GetState();

        if (null == rid || rid.isEmpty()) {
            throw new RuntimeException("CaltopoClient() constructor missing/invalid remoteId");
        }
        remoteId = rid;
        droneSpec = ccs.droneSpecTable.get(rid); // Is this one already active?
        if (null == droneSpec) { // no - check for it in our persistent cache next:
            CtDroneSpec cachedDroneSpec = ccs.cachedDroneSpecTable.get(rid);
            if (null != cachedDroneSpec) { // found it.  Make a working copy:
                droneSpec = cachedDroneSpec.copy();
                ccs.droneSpecTable.put(rid, droneSpec);
            }
        }
        if (null == droneSpec) { // new - never seen before - make a working version.
            droneSpec = new CtDroneSpec(rid);
            ccs.droneSpecTable.put(rid, droneSpec);
        }
        droneSpec.setDroneSpecListener(this);
        idleTimeoutPoll = new DelayedExec();
    }

    public static void AddDroneSpecsChangedListener(CtDroneSpec.DroneSpecsChangedListener newListener) {
        DroneSpecsChangedListeners.add(newListener);
        if (false) CTDebug(TAG, String.format(Locale.US,
                "AddDroneSpecsChangedListener() Adding 0x%x:%s, count:%d",
                newListener.hashCode(), newListener.getClass().getName(), DroneSpecsChangedListeners.size()));
        UpdateDroneSpecs();
    }

    @Nullable
    public static CtDroneSpec GetDroneSpec(@NonNull String remoteId) {
        ClientClassState ccs = GetState();
        CtDroneSpec ds = ccs.droneSpecTable.get(remoteId);
        if (null == ds) {
            ds = ccs.cachedDroneSpecTable.get(remoteId);
            if (null != ds) {
                ds = ds.copy();
                ccs.droneSpecTable.put(remoteId, ds);
            }
        }
        return ds;
    }

    public static void SetSettingsListener(@Nullable ClientSettingsListener listener) {
        SettingsListener = listener;
    }

    /**
     * SetDroneSpecOwner()
     *
     * @param dsIn  This is the dronespec received from our peer who has assumed ownership of said drone.
     *              if there is no entry in our table for it, we'll create an entry with the peer's
     *              supplied rid and mappedId, but ignore everything else about it.  If there is
     *              already an existing dronespec, we only update the local dronespec's mappedId with
     *              the peer's mappedId if the peer's mappedId != remoteId.
     * @param owner This is the peer that has assumed ownership of the specified drone.
     */
    public static void SetDroneSpecOwner(@NonNull CtDroneSpec dsIn, @NonNull R2CPeer owner) {
        ClientClassState ccs = GetState();
        String rid = dsIn.getRemoteId();
        String mid = dsIn.getMappedId();
        CtDroneSpec ds = GetDroneSpec(rid);
        if (null == ds) {
            ds = new CtDroneSpec(rid, dsIn.getMappedId(), dsIn.getOrg(), dsIn.getModel(), dsIn.getOwner());
            ccs.droneSpecTable.put(rid, ds);
            ArchiveState("received new dronespec from our peer.");
        } else if (!mid.isEmpty() && !mid.equals(rid) && !mid.equals(ds.getMappedId())) {
            CTDebug(TAG, "SetDroneSpecOwner(): changing mappedId for '" + rid + "' to '" + mid + "'");
            ds.setMappedId(mid);
        }
        ds.setMyR2cOwner(owner);
        dsIn.setMyR2cOwner(owner);
    }

    public static void RemoveDroneSpecOwner(@NonNull CtDroneSpec dsIn) {
        String rid = dsIn.getRemoteId();
        CtDroneSpec ds = GetDroneSpec(rid);
        if (null != ds) ds.removeMyR2cOwner();
    }

    private static void UpdateDroneSpecs() {
        ProcessSortedCurrentDroneSpecArray(true);
    }

    public void mappedIdChanged(@NonNull CtDroneSpec ds, @NonNull String oldval, @NonNull String newval) {
        CTDebug(TAG, String.format(Locale.US,
                "mappedIdChanged(%s): change from '%s' to '%s'", ds.trackLabel(), oldval, newval));
        UpdateDroneSpecs();
    }

    public static String LoggingLevelName(int loggingLevel) {
        return switch (loggingLevel) {
            case DebugLevelError -> "Errors only";
            case DebugLevelWarn -> "Warnings";
            case DebugLevelDebug -> "Debugs";
            case DebugLevelInfo -> "Ludicrous";
            default -> "";
        };
    }

    public static String BumpLoggingLevel() {
        DebugLevel++;
        if (DebugLevel > DebugLevelInfo) DebugLevel = DebugLevelError;
        String retval = LoggingLevelName(DebugLevel);
        ArchiveState("Logging level changed to: " + retval);
        return retval;
    }

    public static String SetLoggingLevel(int loggingLevel) {
        DebugLevel = loggingLevel;
        if (DebugLevel > DebugLevelInfo) DebugLevel = DebugLevelError;
        String retval = LoggingLevelName(DebugLevel);
        ArchiveState("Logging level changed to: " + retval);
        return retval;
    }

    public interface DebugMessageSupplier {
        String get();
    }

    public static boolean CTDebugEnabled(@Nullable String tag) {
        RegisterDebugTag(tag);
        if (DebugLevel < DebugLevelDebug) return false;
        if (!DebugTagFilterEnabled) return true;
        if (tag == null || tag.isEmpty()) return false;
        synchronized (DebugTagFilterLock) {
            return DebugTagFilter.contains(tag);
        }
    }

    public static void ClearDebugTagFilter() {
        synchronized (DebugTagFilterLock) {
            DebugTagFilter.clear();
            DebugTagFilterEnabled = false;
        }
    }

    public static void SetDebugTagFilter(@Nullable String csvTags) {
        synchronized (DebugTagFilterLock) {
            DebugTagFilter.clear();
            if (csvTags != null) {
                String[] tags = csvTags.split(",");
                for (String raw : tags) {
                    String tag = raw.trim();
                    if (!tag.isEmpty()) {
                        RegisterDebugTag(tag);
                        DebugTagFilter.add(tag);
                    }
                }
            }
            DebugTagFilterEnabled = !DebugTagFilter.isEmpty();
        }
    }

    public static void AddDebugTagFilter(@NonNull String tag) {
        String t = tag.trim();
        if (t.isEmpty()) return;
        RegisterDebugTag(t);
        synchronized (DebugTagFilterLock) {
            DebugTagFilter.add(t);
            DebugTagFilterEnabled = true;
        }
    }

    public static void RemoveDebugTagFilter(@NonNull String tag) {
        String t = tag.trim();
        if (t.isEmpty()) return;
        synchronized (DebugTagFilterLock) {
            DebugTagFilter.remove(t);
            DebugTagFilterEnabled = !DebugTagFilter.isEmpty();
        }
    }

    public static boolean IsDebugTagFilterEnabled() {
        synchronized (DebugTagFilterLock) {
            return DebugTagFilterEnabled;
        }
    }

    @NonNull
    public static String GetDebugTagFilterCsv() {
        synchronized (DebugTagFilterLock) {
            if (DebugTagFilter.isEmpty()) return "";
            List<String> sorted = new ArrayList<>(DebugTagFilter);
            sorted.sort(String::compareTo);
            return String.join(",", sorted);
        }
    }

    public static void RegisterDebugTag(@Nullable String tag) {
        InitializeDebugTagRegistry();
        DebugTagRegistry.registerTag(tag);
    }

    public static void RegisterDebugTags(@NonNull List<String> tags) {
        InitializeDebugTagRegistry();
        DebugTagRegistry.registerTags(tags);
    }

    @NonNull
    public static List<String> GetRegisteredDebugTags() {
        InitializeDebugTagRegistry();
        return DebugTagRegistry.getTags();
    }

    private static synchronized void InitializeDebugTagRegistry() {
        if (DebugTagRegistryInitialized) return;
        List<String> builtInTags = Arrays.asList(
                "CaltopoClient",
                "CaltopoHybridBrowser",
                "CaltopoLiveTrack",
                "CaltopoMap",
                "CaltopoMapHierarchy",
                "CaltopoOp",
                "CaltopoSession",
                "CtDroneSpec",
                "DelayedExec",
                "DesignatorIndicator",
                "FfmpegProbeService",
                "MainScreen",
                "MediaMTXService",
                "R2CPeer",
                "R2CView",
                "R2CViewModel",
                "ScanningService",
                "ServerTemplate",
                "StreamPlayer",
                "StreamSessionService",
                "StreamsGrid",
                "StreamsViewModel",
                "WaypointTrack",
                "WsPipe"
        );
        DebugTagRegistry.registerTags(builtInTags);
        DebugTagRegistryInitialized = true;
    }

    @Nullable
    public static Uri GetDebugLogUri() {
        return DebugLogPath;
    }



    public static void SubmitClue(
            CtDroneSpec droneSpec,
            Bitmap clueImage,
            double clueLat,
            double clueLng,
            double clueAlt,
            String clueTitle,
            String clueDescription,
            long clueTimestamp
    ) {
        CTDebug(TAG, String.format(Locale.US, "SubmitClue() received for %s(%s):%s",
                droneSpec.getMappedId(), droneSpec.getRemoteId(), clueTitle));
        CaltopoMap.SubmitClueWithPhoto(droneSpec, clueLat, clueLng, clueAlt, clueTitle, clueDescription, clueTimestamp, clueImage);
    }

    public static void CTLog(String type, String tag, String msg) {
        if (null == DebugOutputStream) return;
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

    public static void CTEvent(@NonNull String tag, @NonNull String eventName, @Nullable Bundle parameters) {
        FirebaseAnalytics fbAnalytics = GetFBAnalytics();
        if (fbAnalytics == null) return;
        String cleanEventName = eventName.replaceAll("[^a-zA-Z0-9]", "_");
        fbAnalytics.logEvent("r2c_" + cleanEventName, parameters);
        CTDebug(tag, String.format(Locale.US, "CTEvent(r2c_%s): %s", cleanEventName, parameters));
    }

    public static void CTInfo(String tag, String msg) {
        RegisterDebugTag(tag);
        if (DebugLevel >= DebugLevelInfo) {
            long myTid = Process.myTid();
            String tidString = "[" + ProcessId + "-" + ((MainThreadId == myTid) ? "main]" : myTid + "]");
            CTLog("INFO" + tidString, tag, msg);
            msg = "CTInfo" + tidString + ": " + msg;
            Log.i(tag, msg);
        }
    }

    public static void CTDebug(String tag, String msg) {
        RegisterDebugTag(tag);
        if (!CTDebugEnabled(tag)) return;
        long myTid = Process.myTid();
        String tidString = "[" + ProcessId + "-" + ((MainThreadId == myTid) ? "main]" : myTid + "]");
        CTLog("DEBUG" + tidString, tag, msg);
        msg = "CTDebug" + tidString + ": " + msg;
        Log.d(tag, msg);
    }

    public static void CTDebug(String tag, DebugMessageSupplier supplier) {
        RegisterDebugTag(tag);
        if (!CTDebugEnabled(tag)) return;
        String msg;
        try {
            msg = supplier.get();
        } catch (Exception e) {
            CTError(tag, "CTDebug supplier raised", e);
            return;
        }
        CTDebug(tag, msg);
    }

    public static void CTError(String tag, String msg) {
        RegisterDebugTag(tag);
        long myTid = Process.myTid();
        String tidString = "[" + ProcessId + "-" + ((MainThreadId == myTid) ? "main]" : myTid + "]");
        CTLog("ERROR" + tidString, tag, msg);
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
        RegisterDebugTag(tag);
        long myTid = Process.myTid();
        String tidString = "[" + ProcessId + "-" + ((MainThreadId == myTid) ? "main]" : myTid + "]");
        StringBuilder str = new StringBuilder();

        str.append(msg);
        str.append("\n  ");
        str.append(ExceptionToString(e));
        CTLog("ERROR" + tidString, tag, str.toString());
        str.insert(0, "CTError: ");
        Log.e(tag, str.toString());
    }

    public static void CTWarn(String tag, String msg) {
        RegisterDebugTag(tag);
        if (DebugLevel >= DebugLevelWarn) {
            long myTid = Process.myTid();
            String tidString = "[" + ProcessId + "-" + ((MainThreadId == myTid) ? "main]" : myTid + "]");
            CTLog("WARN" + tidString, tag, msg);
            msg = "CTWarn" + tidString + ": " + msg;
            Log.w(tag, msg);
        }
    }

    public static void CTWarn(String tag, String msg, Exception e) {
        RegisterDebugTag(tag);
        if (DebugLevel >= DebugLevelWarn) {
            long myTid = Process.myTid();
            String tidString = "[" + ProcessId + "-" + ((MainThreadId == myTid) ? "main]" : myTid + "]");
            StringBuilder str = new StringBuilder();
            str.append(msg);
            str.append("\n  ");
            str.append(ExceptionToString(e));
            CTLog("WARN" + tidString, tag, str.toString());
            str.insert(0, "CTWarn" + tidString + ": ");
            Log.w(tag, msg);
        }
    }

    public static void OpenBufferContentsInBrowser(@NonNull String buffer) {
        DocumentFile trackDir = GetTodaysTrackDir();
        Context ctxt = R2CApplication.getAppCtxt();
        R2CActivity r2CActivity = R2CActivity.getR2CActivity();

        try {
            String filename = "open_map_failed_" + TimeDatestampString(System.currentTimeMillis());
            DocumentFile dataFilepath = trackDir.createFile("text/html", filename);
            ContentResolver resolver = ctxt.getContentResolver();
            OutputStream outputStream = resolver.openOutputStream(dataFilepath.getUri());
            outputStream.write(buffer.getBytes());
            outputStream.flush();
            outputStream.close();
            r2CActivity.openUri(dataFilepath.getUri().toString(), "text/html");
        } catch (Exception e) {
            CTError(TAG, "OpenBufferContentsInBrowser() raised. ", e);
        }
    }

    @Nullable
    public static DocumentFile GetArchiveDir() {
        Uri archiveUri = GetArchiveUri();
        Context ctxt = R2CApplication.getAppCtxt();
        if (null == ctxt || null == archiveUri) return null;
        return DocumentFile.fromTreeUri(ctxt, archiveUri);
    }

    /**
     * Create/find a directory within the ArchiveDir with todays date
     * and return that as the directory to place trackfiles and logs in.
     *
     * @return DocumentFile path to existing directory on success and
     * null on failure.
     */
    @Nullable
    public static DocumentFile GetTodaysTrackDir() {
        Uri archivePath = GetArchiveUri();
        DocumentFile todaysDir = null;
        Context ctxt = R2CApplication.getAppCtxt();
        if (null == ctxt || null == archivePath) return null;
        DocumentFile archiveDir = DocumentFile.fromTreeUri(ctxt, archivePath);
        if (null != archiveDir) try {
            SimpleDateFormat sdf = new SimpleDateFormat("ddMMMyyyy", Locale.US);
            String dirpath = "tracks-" + sdf.format(new Date());
            todaysDir = archiveDir.findFile(dirpath);
            if (null == todaysDir) {
                todaysDir = archiveDir.createDirectory(dirpath);
                if (null == todaysDir) {
                    CTError(TAG, String.format(Locale.US, "GetTodaysTrackDir(): Not able to create '%s'", archiveDir));
                } else {
                    CTDebug(TAG, String.format(Locale.US, "GetTodaysTrackDir(): Created '%s'", archiveDir));
                }
            } else {
                CTDebug(TAG, String.format(Locale.US, "GetTodaysTrackDir(): found existing '%s'", archiveDir));
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
            ArchiveState("Caltopo Track Folder changed.");
        }
    }

    @NonNull
    public static CaltopoCredentials GetCaltopoCredentials() {
        ClientClassState ccs = GetState();
        return ccs.caltopoCredentials;
    }

    public static void SetGoLiveFlag(boolean flag) {
        ClientClassState ccs = GetState();
        if (ccs.goLiveFlag != flag) {
            ccs.goLiveFlag = flag;
            NotifySettingsChanged();
        }
    }

    public static boolean GetGoLiveFlag() {
        ClientClassState ccs = GetState();
        return ccs.goLiveFlag;
    }


    public static void SetUsePeers(boolean flag) {
        ClientClassState ccs = GetState();
        if (ccs.usePeersFlag != flag) {
            ccs.usePeersFlag = flag;
            NotifySettingsChanged();
            ArchiveState("usePeers changed to " + flag);
        }
    }

    public static boolean GetUsePeersFlag() {
        ClientClassState ccs = GetState();
        return ccs.usePeersFlag;
    }

    public static void SetCaltopoCredentials(@NonNull CaltopoCredentials cred)
            throws RuntimeException {
        if (!CaltopoCredentials.sniffTest(cred)) {
            throw new RuntimeException("CaltopoSessionConfig.setCaltopoConfig() bad spec.");
        }

        ClientClassState ccs = GetState();
        if (!CaltopoCredentials.credentialsAreEqual(cred, ccs.caltopoCredentials)) {
            ccs.caltopoCredentials = cred;
        }
    }

    public static void SetCaltopoDomainAndPort(@NonNull String dAndP) {
        ClientClassState ccs = GetState();
        if (null != ccs.caltopoDomainAndPort) {
            if (dAndP.equals(ccs.caltopoDomainAndPort)) {
                CTDebug(TAG, "SetCaltopoDomainAndPort(): No change.");
                return;
            }
            // FIXME: Should probably check valid format...
        }
        ccs.caltopoDomainAndPort = dAndP;
    }

    public static String GetCaltopoDomainAndPort() {
        ClientClassState ccs = GetState();
        if (null == ccs.caltopoDomainAndPort)
            ccs.caltopoDomainAndPort = ""; // shouldn't happen, but play it safe.
        return ccs.caltopoDomainAndPort;
    }

    public static JSONObject ReadJsonFile(Uri uri) {
        StringBuilder stringBuilder = new StringBuilder();
        InputStream is;
        InputStreamReader isr;
        BufferedReader bufferedReader;
        JSONObject retval;

        try {
            Context ctxt = R2CApplication.getAppCtxt();
            ContentResolver resolver  = ctxt.getContentResolver();
            Cursor cursor = resolver.query(uri, null, null, null, null);
            if (cursor != null) {
                // Try reading file metadata to coerce Google Drive to fetch fresh version of
                // the file, rather than pulling from it's cache.
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (cursor.moveToFirst()) {
                    CTDebug(TAG, String.format(Locale.US, "ReadJsonFile(%s) size:%d bytes...",
                            cursor.getString(nameIndex), cursor.getLong(sizeIndex)));
                }
            }
            is = resolver.openInputStream(uri);
            isr = new InputStreamReader(is, StandardCharsets.UTF_8);
            bufferedReader = new BufferedReader(isr);
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                stringBuilder.append(line).append("\n");
            }
            bufferedReader.close();
            isr.close();
            if (null != is) is.close();
        } catch (IOException e) {
            CTError(TAG, String.format(Locale.US, "Not able to read '%s'", uri), e);
            return null;
        }

        try {
            retval = new JSONObject(stringBuilder.toString());
        } catch (JSONException e) {
            CTError(TAG, String.format(Locale.US, "Not able to parse '%s'", uri), e);
            return null;
        }
        return retval;
    }

    public static void ShowToast(String msg) {
        CTWarn(TAG, "showToast():" + msg);
        R2CActivity activity = R2CActivity.getR2CActivity();
        if (null != activity) activity.showToast(msg);
    }

    public static void ShowToast(String msg, Exception e) {
        CTWarn(TAG, "showToast():" + msg, e);
        msg = msg + "\n" + ExceptionToString(e);
        R2CActivity activity = R2CActivity.getR2CActivity();
        if (null != activity) activity.showToast(msg);
    }

    public static void readCredentialsFileContent(JSONObject json)
            throws JSONException {
        String teamId = json.optString("team_id");
        String credentialId = json.optString("credential_id");
        String credentialSecret = json.optString("credential_secret");
        String domainAndPort = json.optString("domain_and_port");
        String trackFolder = json.optString("track_folder");
        String incident = json.optString("incident");
        String opPeriod = json.optString("op_period");
        String trackerApiKey = json.optString("tracker_api_key");
        String trackerUrlPfx = json.optString("tracker_url_pfx");
        if (!trackFolder.isEmpty()) SetTrackFolderName(trackFolder);
        if (!incident.isEmpty()) SetIncident(incident);
        if (!opPeriod.isEmpty()) SetOpPeriod(opPeriod);
        if (!trackerApiKey.isEmpty()) SetTrackerApiKey(trackerApiKey);
        if (!trackerUrlPfx.isEmpty()) SetTrackerUrlPfx(trackerUrlPfx);
        if (!domainAndPort.isEmpty()) SetCaltopoDomainAndPort(domainAndPort);
        if (!teamId.isEmpty() || !credentialId.isEmpty() || !credentialSecret.isEmpty()) {
            SetCaltopoCredentials(new CaltopoCredentials(teamId, credentialId, credentialSecret));
        }
        NotifySettingsChanged();
        ArchiveState("Credentials loaded");
    }

    // readRidmapFileContent():
    // New for v1.0.7rc1: This is now the only way to _add_ dronespecs into the
    // persistent ClientClassState.
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
        CtDroneSpec newDs;
        for (int i = 0; i < mapJson.length(); i++) {
            JSONObject entry = mapJson.getJSONObject(i);
            String rid = entry.optString("remoteId");
            String mid = entry.optString("mappedId");
            String org = entry.optString("org");
            String model = entry.optString("model");
            String owner = entry.optString("owner");
            newDs = new CtDroneSpec(rid, mid, org, model, owner);
            CtDroneSpec existingDs = ccs.cachedDroneSpecTable.get(rid);
            boolean changed = (null == existingDs);
            if (!changed) {
                CTDebug(TAG, "readRidmapFileContent(): Found existing droneSpec for spec: " + existingDs);
                if (existingDs.isDifferentFrom(newDs)) {
                    existingDs.setModel(newDs.getModel());
                    existingDs.setMappedId(newDs.getMappedId());
                    existingDs.setOrg(newDs.getOrg());
                    existingDs.setOwner(newDs.getOwner());
                    CTDebug(TAG, String.format(Locale.US,
                            "readRidmapFileContent(): changed persistent dronespec from:\n    %s\n  to:\n    %s",
                            existingDs, newDs));
                    newDs = existingDs;
                    changed = true;
                } else {
                    CTInfo(TAG, "readRidmapFileContent(): no changes detected for spec: " + existingDs);
                }
            }
            existingDs = mergedTable.get(rid);
            if (null != existingDs) {
                throw new JSONException(String.format(Locale.US,
                        "Illegal duplicate remoteId '%s' at table offset %d - file contents ignored.", rid, i));
            }
            if (changed) changeCount++;
            mergedTable.put(newDs.getRemoteId(), newDs);
        }

        if (0 != changeCount) {
            // Be sure to include any existing mappings that weren't mentioned in the file:
            for (Map.Entry<String, CtDroneSpec> map : ccs.cachedDroneSpecTable.entrySet()) {
                String key = map.getKey();
                if (null == mergedTable.get(key)) {
                    mergedTable.put(key, map.getValue());
                }
            }
            ccs.cachedDroneSpecTable = mergedTable;
            ArchiveState("readRidmapFileContent() merged ridmap with updates/changes.");
        } else {
            CTDebug(TAG, "readRidmapFileContent(): No changes detected.");
        }
    }

    public static boolean LoadConfigFile(Uri uri) {
        if (null == uri) return false;
        try {
            ClientClassState ccs = GetState();

            JSONObject json = ReadJsonFile(uri);
            if (null == json) return false;
            String type = json.optString("type").trim().toLowerCase();
            String fileVersion = json.optString("file_version");
            String updated = json.optString("updated");
            String editor = json.optString("editor");
            CTDebug(TAG, String.format(Locale.US, "Reading v%s %s config file last updated by %s on %s",
                    fileVersion, type, editor, updated));

            // append record to log
            SimpleDateFormat sdf = new SimpleDateFormat("ddMMMyyyy-HHmmss", Locale.US);
            String rec = String.format(Locale.US,
                    "type:%s, editor:%s, dated:%s loaded at %s\n",type, editor, updated, sdf.format(new Date()));;
            ccs.configFilesLoaded += rec;

            if (type.equals("ct_ridmap")) {
                readRidmapFileContent(json);
            } else if (type.equals("ct_credentials")) {
                readCredentialsFileContent(json);
            }
            NotifySettingsChanged();
            ShowToast(String.format(Locale.US, "%s:%s successfully loaded.", type, fileVersion));
        } catch (JSONException e) {
            CTError(TAG, String.format(Locale.US, "Error processing '%s':", uri), e);
            return false;
        }
        return true;
    }

    public static int GetRidmapCount() {
        ClientClassState ccs = GetState();
        return ccs.cachedDroneSpecTable.size();
    }

    public static String GetConfigFilesLoadedRecord() {
        ClientClassState ccs = GetState();
        return String.format(Locale.US, " * %s",
                ccs.configFilesLoaded.trim().replaceAll("\\n", "\n * "));
    }

    public static void CheckUnreportedFiles() {
        if (IsExitRequested()) {
            CTDebug(TAG, "CheckUnreportedFiles(): skipping while app exit is in progress.");
            return;
        }
        try {
            GetArchiveScanExecutorPool().submit(WaypointTrack::BgPollUnreportedTracks);
        } catch (RejectedExecutionException e) {
            CTWarn(TAG, "CheckUnreportedFiles(): archive scan executor rejected task", e);
        }
    }

    public static void DroneSpecStatusChanged(@NonNull CtDroneSpec ds, boolean isActiveFlag) {
        UpdateDroneSpecs();
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
            CTDebug(TAG, String.format(Locale.US,
                    "ClientForRemoteId(): Instantiating client for '%s'", remoteId));
        }

        if (null == client.droneSpec) {
            CTError(TAG, String.format(Locale.US,
                    "ClientForRemoteId(): droneSpec missing for '%s'", remoteId));
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

    // can return null if no stored state available or the app isn't initialized yet.
    @Nullable
    private static ClientClassState RestoreState() {
        Context ctxt = R2CApplication.getAppCtxt();
        if (null == ctxt) return null;

        ClientClassState secureState = restoreSecureState(ctxt);
        if (secureState != null) {
            if (secureState.debugLevel >= 0) DebugLevel = secureState.debugLevel;
            return secureState;
        }

        ClientClassState ccs = restoreLegacyState(ctxt);
        if (ccs != null) {
            // Best-effort migration to encrypted-at-rest state storage.
            archiveSecureState(ctxt, ccs);
            if (ccs.debugLevel >= 0) DebugLevel = ccs.debugLevel;
        }
        return ccs;
    }

    @Nullable
    private static ClientClassState restoreSecureState(@NonNull Context ctxt) {
        AtomicFile stateFile = new AtomicFile(new File(ctxt.getFilesDir(), SECURE_STATE_FILE_NAME));
        byte[] payload;
        try {
            payload = stateFile.readFully();
        } catch (FileNotFoundException e) {
            return null;
        } catch (Exception e) {
            CTError(TAG, "RestoreState() unable to read secure state archive.", e);
            return null;
        }

        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(payload))) {
            String header = dis.readUTF();
            int version = dis.readInt();
            if (!SECURE_STATE_HEADER.equals(header) || version != SECURE_STATE_VERSION) {
                CTWarn(TAG, String.format(Locale.US,
                        "RestoreState() secure state format mismatch. header=%s version=%d", header, version));
                return null;
            }
            int ivLength = dis.readInt();
            if (ivLength < 12 || ivLength > 32) {
                CTWarn(TAG, "RestoreState() invalid secure state IV length: " + ivLength);
                return null;
            }
            byte[] iv = new byte[ivLength];
            dis.readFully(iv);
            int cipherLength = dis.readInt();
            if (cipherLength <= 0 || cipherLength > 32 * 1024 * 1024) {
                CTWarn(TAG, "RestoreState() invalid secure state cipher length: " + cipherLength);
                return null;
            }
            byte[] cipherText = new byte[cipherLength];
            dis.readFully(cipherText);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateStateKey(), new GCMParameterSpec(128, iv));
            byte[] serializedState = cipher.doFinal(cipherText);
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(serializedState))) {
                return (ClientClassState) ois.readObject();
            }
        } catch (InvalidClassException e) {
            CTWarn(TAG, "RestoreState() secure state incompatible version. Resetting.", e);
        } catch (GeneralSecurityException e) {
            CTWarn(TAG, "RestoreState() secure state decryption failed. Resetting.", e);
        } catch (Exception e) {
            CTError(TAG, "RestoreState() secure state decode failed.", e);
        }
        return null;
    }

    @Nullable
    private static ClientClassState restoreLegacyState(@NonNull Context ctxt) {
        try {
            CTDebug(TAG, "RestoreState() Opening legacy state " + LEGACY_STATE_FILE_NAME);
            try (FileInputStream fis = ctxt.openFileInput(LEGACY_STATE_FILE_NAME);
                 ObjectInputStream ois = new ObjectInputStream(fis)) {
                return (ClientClassState) ois.readObject();
            }
        } catch (FileNotFoundException e) {
            CTWarn(TAG, "RestoreState() no archive to restore from:", e);
        } catch (InvalidClassException e) {
            CTWarn(TAG, "RestoreState() not able to restore incompatible legacy state. Resetting.", e);
        } catch (Exception e) {
            CTError(TAG, "RestoreState() legacy decode raised:", e);
        }
        return null;
    }

    private static SecretKey getOrCreateStateKey() throws GeneralSecurityException, IOException {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            KeyStore.Entry entry = keyStore.getEntry(SECURE_STATE_KEY_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            KeyGenParameterSpec keySpec = new KeyGenParameterSpec.Builder(
                    SECURE_STATE_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
            )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build();
            keyGenerator.init(keySpec);
            return keyGenerator.generateKey();
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityException("Unable to create/open Android Keystore key", e);
        }
    }

    private static void archiveSecureState(@NonNull Context ctxt, @NonNull ClientClassState state) {
        AtomicFile stateFile = new AtomicFile(new File(ctxt.getFilesDir(), SECURE_STATE_FILE_NAME));
        FileOutputStream fos = null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeObject(state);
                oos.flush();
            }
            byte[] serializedState = bos.toByteArray();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateStateKey());
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length == 0) {
                throw new GeneralSecurityException("ArchiveState(): secure cipher generated empty IV.");
            }
            byte[] cipherText = cipher.doFinal(serializedState);

            fos = stateFile.startWrite();
            DataOutputStream dos = new DataOutputStream(fos);
            dos.writeUTF(SECURE_STATE_HEADER);
            dos.writeInt(SECURE_STATE_VERSION);
            dos.writeInt(iv.length);
            dos.write(iv);
            dos.writeInt(cipherText.length);
            dos.write(cipherText);
            dos.flush();
            stateFile.finishWrite(fos);
            try {
                ctxt.deleteFile(LEGACY_STATE_FILE_NAME);
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            if (fos != null) {
                stateFile.failWrite(fos);
            }
            CTError(TAG, "ArchiveState() secure write failed:", e);
        }
    }

    private static FirebaseAnalytics GetFBAnalytics() {
        if (null == FBAnalytics) {
            Context context = R2CApplication.getAppCtxt();
            if (null != context) {
                FBAnalytics = FirebaseAnalytics.getInstance(context);
            }
        }
        return FBAnalytics;
    }

    private static void SetFBDefaults() {
        ClientClassState ccs = GetState();
        FirebaseAnalytics fbAnalytics = GetFBAnalytics();
        if (null == fbAnalytics) return;
        try {
            Bundle parameters = new Bundle();
            parameters.putString("r2c_map", CaltopoMap.GetMapName());
            parameters.putBoolean("r2c_ctCred", CaltopoCredentials.sniffTest(ccs.caltopoCredentials));
            parameters.putBoolean("r2c_goLiveFlag", ccs.goLiveFlag);
            parameters.putLong("r2c_newTrackDelayInSeconds", ccs.newTrackDelayInSeconds);
            parameters.putLong("r2c_minDistanceInFeet", ccs.minDistanceInFeet);
            parameters.putBoolean("r2c_usePeers", ccs.usePeersFlag);
            parameters.putLong("r2c_maxIdleTimeInMinutes", ccs.maxIdleTimeInMinutes);
            parameters.putLong("r2c_debugLevel", ccs.debugLevel);
            fbAnalytics.setDefaultEventParameters(parameters);

        } catch (Exception e) {
            CTError(TAG, "setFBDefaults() raised:", e);
        }
    }

    @NonNull
    private static ClientClassState GetState() {
        InitializeDebugTagRegistry();
        if (null == Ccstate) {
            ClientClassState ccs = RestoreState();
            if (null == ccs) ccs = new ClientClassState();
            if (null == ccs.droneSpecTable) ccs.droneSpecTable = new Hashtable<>(16);
            if (null == ccs.cachedDroneSpecTable) ccs.cachedDroneSpecTable = new Hashtable<>(16);
            Ccstate = ccs;
            CTDebug(TAG, "GetState(): " + Ccstate);
            SetFBDefaults();
            if (!ccs.cachedDroneSpecTable.isEmpty()) {
                Bundle parameters = new Bundle();
                ArrayList<String> mappedIds = new ArrayList<>();
                for (CtDroneSpec ds : ccs.cachedDroneSpecTable.values()) {
                    mappedIds.add(ds.getRemoteId() + ":" + ds.getMappedId());
                }
                parameters.putStringArrayList("r2c_mappedIds", mappedIds);
                CTEvent(TAG, "InitialState", parameters);
            }
        }
        return Ccstate;
    }

    private static void ArchiveState(@NonNull String reason) {
        if (null != Ccstate) try {
            // so many contexts to choose from - hopefully one of them works...
            Context ctxt = R2CApplication.getAppCtxt();
            if (null == ctxt) {
                CTDebug(TAG, "ArchiveState(): Missing required app context." );
                return;
            }
            Ccstate.debugLevel = DebugLevel;
            archiveSecureState(ctxt, Ccstate);
            CTDebug(TAG, String.format(Locale.US, "ArchiveState(%s):\n%s", reason, Ccstate));
            SetFBDefaults();
            Bundle parameters = new Bundle();
            parameters.putString("r2c_reason", reason);
            CTEvent(TAG, "ArchiveState", parameters);
        } catch (Exception e) {
            CTError(TAG, "ArchiveState() raised:", e);
        }
    }

    // This gets run by periodic timer.
    private static void ProcessSortedCurrentDroneSpecArray() {
        ProcessSortedCurrentDroneSpecArray(false);
    }

    /**
     * ProcessSortedCurrentDroneSpecArray()
     * Sorts active droneSpecs by flight start Time, so oldest will appear first in
     * list while also checking for timeout and removing aged-out dronespecs that
     * have stopped transmitting RemoteID updates.
     *
     * @param changedFlag True if something has changed and we need to refresh the list.
     *                    If false, then check for inactive dronespecs.
     */

    private static void ProcessSortedCurrentDroneSpecArray(boolean changedFlag) {
        long mostRecentUpdate = CtDroneSpec.LastWaypointUpdateTimestampMsec();

        ClientClassState ccs = GetState();
        long newTrackDelayInMsec = ccs.newTrackDelayInSeconds * 1000;
        long currentTimeInMsec = System.currentTimeMillis();
        long nextAgeOutInMsec = newTrackDelayInMsec;
        if (changedFlag || currentTimeInMsec >= PreviousEarliestAgeOutInMsec) {
            DsArray.clear();
            for (CtDroneSpec ds : ccs.droneSpecTable.values()) {
                long droneSpecIdleInMsec = ds.idleTimeInMsec(currentTimeInMsec);
                if (ds.isActive() && droneSpecIdleInMsec > newTrackDelayInMsec) {
                    CaltopoClient client = (ClientMap != null) ? ClientMap.get(ds.getRemoteId()) : null;
                    String msg = String.format(Locale.US,
                            "ProcessSortedCurrentDroneSpecArray(%s): %s idle for %.3f/%.3f seconds. Finishing track...",
                            changedFlag, ds.trackLabel(),
                            (double) droneSpecIdleInMsec / 1000.0, (double) newTrackDelayInMsec / 1000.0);
                    CTInfo(TAG, msg);
                    if (client != null) {
                        client.terminateTrack(msg, false);
                    } else {
                        CTWarn(TAG, "ProcessSortedCurrentDroneSpecArray(): no CaltopoClient found for " + ds.getRemoteId());
                    }
                    changedFlag = true;
                    continue;
                }
                long currentAgeOutInMsec = newTrackDelayInMsec - droneSpecIdleInMsec;
                if (currentAgeOutInMsec <= 0) continue;
                if (currentAgeOutInMsec < nextAgeOutInMsec) nextAgeOutInMsec = currentAgeOutInMsec;
                CTDebug(TAG, String.format(Locale.US,
                        "ProcessSortedCurrentDroneSpecArray(%s): current age for %s is %.3f, age out in %.3f seconds. next age out in %.3f seconds",
                        changedFlag, ds.getMappedId(), droneSpecIdleInMsec / 1000.0, currentAgeOutInMsec / 1000.0, nextAgeOutInMsec / 1000.0));
                DsArray.add(ds);
            }
            PreviousEarliestAgeOutInMsec = currentTimeInMsec + nextAgeOutInMsec;
            DsArray.sort(CtDroneSpec::compareToAge);
            long newSize = DsArray.size();
            if (!changedFlag && (DroneSpecsArraySize != newSize)) {
                CTDebug(TAG, String.format(Locale.US,
                        "ProcessSortedCurrentDroneSpecArray(): arraySize changed from:%d to :%d", DroneSpecsArraySize, newSize));
                DroneSpecsArraySize = newSize;
            }
        }

        ArrayList<CtDroneSpec> dsArrayClone = (ArrayList<CtDroneSpec>) DsArray.clone();
        if (false) CTDebug(TAG, String.format(Locale.US,
                "ProcessSortedCurrentDroneSpecArray(%s) updating %d listeners",
                changedFlag, DroneSpecsChangedListeners.size()));
        for (CtDroneSpec.DroneSpecsChangedListener listener : DroneSpecsChangedListeners) {
            if (false) CTDebug(TAG, String.format(Locale.US,
                    "ProcessSortedCurrentDroneSpecArray(%s) + Updating 0x%x:%s", changedFlag,
                    listener.hashCode(), listener.getClass().getName()));
            listener.onDroneSpecsChanged(dsArrayClone);
        }
        if (DsArray.size() == 0) {
            UiUpdatePoll.stop();
            CTDebug(TAG, "ProcessSortedCurrentDroneSpecArray(): Stopping UiUpdatePoll.");
        } else if (!UiUpdatePoll.isRunning()) {
            UiUpdatePoll.start(CaltopoClient::ProcessSortedCurrentDroneSpecArray, 1000, 1000);
            CTDebug(TAG, "UpdateDroneSpecs(): Starting UiUpdatePoll...");
        }
    }

    public static long GetNewTrackDelayInSeconds() {
        ClientClassState ccs = GetState();
        return ccs.newTrackDelayInSeconds;
    }

    public static long GetMinDistanceInFeet() {
        ClientClassState ccs = GetState();
        return ccs.minDistanceInFeet;
    }

    @Nullable
    public static Uri GetArchiveUri() {
        ClientClassState ccs = GetState();
        return ccs.archivePath.isEmpty() ? null : Uri.parse(ccs.archivePath);
    }

    @NonNull
    public static String GetTrackerApiKey() {
        ClientClassState ccs = GetState();
        return ccs.trackerApiKey;
    }

    public static void SetTrackerApiKey(@NonNull String apiKey) {
        ClientClassState ccs = GetState();
        if (!ccs.trackerApiKey.equals(apiKey)) {
            ccs.trackerApiKey = apiKey;
        }
    }

    @NonNull
    public static String GetTrackerUrlPfx() {
        ClientClassState ccs = GetState();
        return ccs.trackerUrlPfx;
    }

    public static void SetTrackerUrlPfx(@NonNull String urlPfx) {
        ClientClassState ccs = GetState();
        if (!ccs.trackerUrlPfx.equals(urlPfx)) {
            ccs.trackerUrlPfx = urlPfx;
        }
    }

    public static String GetIncident() {
        ClientClassState ccs = GetState();
        return ccs.incident;
    }

    public static void SetIncident(@NonNull String incident) {
        ClientClassState ccs = GetState();
        if (!ccs.incident.equals(incident)) {
            ccs.incident = incident;
            ArchiveState("incident changed");
        }
    }

    public static String GetOpPeriod() {
        ClientClassState ccs = GetState();
        return ccs.opPeriod;
    }

    public static void SetOpPeriod(@NonNull String opPeriod) {
        ClientClassState ccs = GetState();
        if (!ccs.opPeriod.equals(opPeriod)) {
            ccs.opPeriod = opPeriod;
            ArchiveState("opPeriod changed");
        }
    }

    public static void InitArchiveDir() {
        DocumentFile todaysArchiveDir = GetTodaysTrackDir();
        if (null == todaysArchiveDir) {
            CTError(TAG, "InitArchiveDir(): archive dir is mia.");
            return;
        }
        if (null == DebugOutputStream) try {
            CTDebug(TAG, "InitArchiveDir(): Initializing log stream...");
            String filepath = "Log_" + TimeDatestampString(ScanningService.GetStartTimeInMsec());
            Context ctxt = R2CApplication.getAppCtxt();
            if (null != ctxt) try {
                DocumentFile dataFilepath = todaysArchiveDir.createFile("text/plain", filepath);
                ContentResolver resolver = ctxt.getContentResolver();
                if (null != dataFilepath) {
                    DebugLogPath = dataFilepath.getUri();
                    DebugOutputStream = (resolver.openOutputStream(DebugLogPath));
                }
            } catch (Exception e) {
                CTError(TAG, "InitArchiveDir() raised: ", e);
            }

            if (null != DebugOutputStream) {
                LogFilePath = todaysArchiveDir + "/" + filepath;
                R2CActivity activity = R2CActivity.getR2CActivity();
                String appVers = BuildConfig.BUILD_VERSION;
                final String header = "########################################################################\n";
                CTDebug(TAG, String.format(Locale.US,
                        "Logfile is up on %s @%s\n%s#  RID2Caltopo %s(%s) running on Android OS v%s(%d)\n#  Writing logs to: %s\n%s",
                        R2CActivity.MyDeviceName, R2CPeer.GetMyIpAddresses(), header, appVers,
                        BuildConfig.BUILD_TIME, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, LogFilePath, header));
            }

            CheckUnreportedFiles();
        } catch (Exception e) {
            Log.e(TAG, "CTError: Not able to open DebugOutputStream: " + e);
        }
    }

    public static void SetArchiveUri(@NonNull Uri pathUri) {
        ClientClassState ccs = GetState();
        String pathString = pathUri.toString();
        if (!ccs.archivePath.equals(pathString)) {
            ccs.archivePath = pathString;
            ArchiveState("archivePath changed.");
            InitArchiveDir();
        }
    }

    private static void NotifySettingsChanged() {
        if (!NotifySettingsChangedFlag && null != SettingsListener) {
            CTDebug(TAG, "notifySettingsChanged()");
            NotifySettingsChangedFlag = true;
            SettingsListener.settingsChanged();
            NotifySettingsChangedFlag = false;
        }
    }

    public static void QuitApplication() {
        synchronized (ShutdownLock) {
            AppExitRequested = true;
        }
        Context context = R2CApplication.getAppCtxt();

        // 1. Stop the Scanning Service
        Intent stopScanner = new Intent(context, ScanningService.class);
        stopScanner.setAction("STOP_SERVICE");
        context.startService(stopScanner);

        // 2. Stop the MediaMTX Service
        Intent stopMedia = new Intent(context, MediaMTXService.class);
        stopMedia.setAction("STOP_SERVICE");
        context.startService(stopMedia);

        // 3. Clear any remaining notifications (optional but clean)
        // NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        // notificationManager.cancelAll();

        Activity activity = R2CActivity.getR2CActivity();
        if (null != activity) {
            // 4. Finish the Activity
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                activity.finishAndRemoveTask(); // Removes it from the "Recent Apps" list too
            } else {
                activity.finish();
            }
        }
        ShutdownAsync();
    }


    public static void ShutdownAsync() {
        Thread shutdownThread = new Thread(CaltopoClient::Shutdown, "R2C-Shutdown");
        shutdownThread.start();
    }

    public static void Shutdown() {
        synchronized (ShutdownLock) {
            if (ShutdownInProgress) {
                return;
            }
            ShutdownInProgress = true;
        }
        try {
            WaypointTrack.ArchiveTracks();
            ShutdownExecutorPool(LivePublishExecutorPool, true);
            LivePublishExecutorPool = null;
            ShutdownExecutorPool(GeoJsonStatsExecutorPool, true);
            GeoJsonStatsExecutorPool = null;
            ShutdownExecutorPool(ArchiveScanExecutorPool, true);
            ArchiveScanExecutorPool = null;
            try {
                CaltopoMap.Shutdown();
            } catch (Exception e) {
                CTError(TAG, "CaltopoMap.Shutdown() raised: ", e);
            }
            if (null != UiUpdatePoll) {
                UiUpdatePoll.stop();
                CTDebug(TAG, "Shutdown(): UiUpdatePoll suspended.");
            }
            AppIdleDelay.stop();
            if (null != DebugOutputStream) {
                try {
                    DebugOutputStream.flush();
                    DebugOutputStream.close();
                    DebugOutputStream = null;
                } catch (IOException e) {
                    Log.e(TAG, "CTError: Shutdown raised: " + e);
                }
            }
        } finally {
            synchronized (ShutdownLock) {
                ShutdownInProgress = false;
            }
        }
    }

    public static void MarkAppActive() {
        synchronized (ShutdownLock) {
            AppExitRequested = false;
        }
    }

    public static boolean IsExitRequested() {
        synchronized (ShutdownLock) {
            return AppExitRequested;
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
            UpdateDroneSpecs();
        }
        return ccs.newTrackDelayInSeconds;
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

    /**
     * This is the maximum app idle time.
     *
     */
    public static void SetMaxIdleTimeInMinutes(long timeval) {
        ClientClassState ccs = GetState();
        if (timeval < 0) timeval = 0;
        if (timeval != ccs.maxIdleTimeInMinutes) {
            ccs.maxIdleTimeInMinutes = timeval;
            ArchiveState("maxIdleTimeInMinutes changed");
            CheckIdle();
        }
    }

    /**
     * CheckIdle()
     * Check to see if the app has received _any_ waypoints.   If MaxIdleTimeInMinutes()
     * elapses and no waypoints, then let's assume the user just forgot to quit the app,
     * so try to clean-up and exit to save their battery.   If MaxIdleTimeInMinutes == 0,
     * then this check is disabled.
     */

    public static void CheckIdle() {
        long maxIdleInMinutes = GetMaxIdleTimeInMinutes();
        AppIdleDelay.stop();
        if (maxIdleInMinutes <= 0) return;
        long maxIdleInMsec = maxIdleInMinutes * 1000 * 60;
        long idleInMsec = CtDroneSpec.IdleTimeInMsec();
        if (idleInMsec < maxIdleInMsec) {
            AppIdleDelay.start(CaltopoClient::CheckIdle,
                    maxIdleInMsec - idleInMsec, 0);
            return;
        }
        CaltopoClient.CTEvent(TAG, "MaxIdleExiting", null);
        CTDebug(TAG, String.format(Locale.US,
                "App has been idle for %.3f minutes.  Exiting to save battery.",
                idleInMsec / 60000.0));
        synchronized (ShutdownLock) {
            AppExitRequested = true;
        }
        Activity activity = R2CActivity.getR2CActivity();
        if (null != activity) activity.finishAffinity();
    }

    public static long GetMaxIdleTimeInMinutes() {
        ClientClassState ccs = GetState();
        return ccs.maxIdleTimeInMinutes;
    }

    public static String TimeDatestampString(long epochMsec) {
        // Yes, we really want the timestamp first to make it easier to spot
        // the latest track in caltopo's tiny feature window.
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HHmmssLLLdd");
        Instant instant = Instant.ofEpochMilli(epochMsec);
        LocalDateTime localDateTime = instant.atZone(ZoneId.systemDefault()).toLocalDateTime();
        return localDateTime.format(formatter);
    }

    // CaltopoClient instance methods:

    @Override
    @NonNull
    public String toString() {
        return String.format(Locale.US,
                "  rid:%s, mapped:%s", remoteId, droneSpec.getMappedId());
    }

    private static void appendPositionTelemetryQuery(@NonNull StringBuilder sb,
                                                     @Nullable PositionTelemetry telemetry) {
        if (telemetry == null) return;
        JSONObject aircraft = new JSONObject();
        JSONObject camera = new JSONObject();
        try {
            putFinite(aircraft, "altitude", telemetry.aircraftAltitudeFt);
            putFinite(aircraft, "altitude_rate", telemetry.aircraftAltitudeRateFpm);
            putFinite(aircraft, "gs", telemetry.aircraftGsKnots);
            putFinite(aircraft, "heading", telemetry.aircraftHeadingDeg);
            putFinite(aircraft, "track", telemetry.aircraftTrackDeg);
            putFinite(aircraft, "pitch", telemetry.aircraftPitchDeg);
            putFinite(aircraft, "roll", telemetry.aircraftRollDeg);

            putFinite(camera, "azimuth", telemetry.cameraAzimuthDeg);
            putFinite(camera, "tilt", telemetry.cameraTiltDeg);
            putFinite(camera, "fov_width", telemetry.cameraFovWidthDeg);
            putFinite(camera, "fov_height", telemetry.cameraFovHeightDeg);
            putString(camera, "external_url", telemetry.cameraExternalUrl);
            putString(camera, "thumbnail_url", telemetry.cameraThumbnailUrl);
        } catch (Exception e) {
            CTError(TAG, "appendPositionTelemetryQuery() JSON put raised", e);
            return;
        }

        if (aircraft.length() > 0) {
            sb.append("&aircraft=").append(Uri.encode(aircraft.toString()));
        }
        if (camera.length() > 0) {
            sb.append("&camera=").append(Uri.encode(camera.toString()));
        }
    }

    private static void putFinite(@NonNull JSONObject jo, @NonNull String key,
                                  @Nullable Double value) throws JSONException {
        if (value != null && Double.isFinite(value)) {
            jo.put(key, value);
        }
    }

    private static void putString(@NonNull JSONObject jo, @NonNull String key,
                                  @Nullable String value) throws JSONException {
        if (value != null && !value.isEmpty()) {
            jo.put(key, value);
        }
    }

    /* this is used when Caltopo Session is not used, requiring user to set up the
     * LiveTrack in Caltopo web interface.
     *
     * FIXME: Should we move this to CaltopoLiveTrack and have LiveTrack support
     *  the track writing without a map, but possibly with R2CPeers?  That
     *  would make sense if we can get broadcast/rendezvous working.
     */
    public void bgPublishLive(String deviceId, double lat, double lng, long altitudeInMeters,
                              @Nullable PositionTelemetry telemetry) {
        StringBuilder urlBuilder = new StringBuilder(String.format(Locale.US,
                "%s%s?id=%s&lat=%.6f&lng=%.6f&elevation=%d",
                BASE_URL, deviceId, deviceId, lat, lng, altitudeInMeters));
        appendPositionTelemetryQuery(urlBuilder, telemetry);
        String https_url = urlBuilder.toString();
        try {
            URL url = new URL(https_url);
            HttpsURLConnection httpsConn;
            int responseCode;

            //    Log.i(TAG, "sending to caltopo: " + https_url);
            httpsConn = (HttpsURLConnection) url.openConnection();
            httpsConn.setRequestMethod("GET");
            httpsConn.setRequestProperty("User-Agent", "RID2Caltopo/0.2");
            responseCode = httpsConn.getResponseCode();
            if (HttpsURLConnection.HTTP_OK != responseCode) {
                // FIXME: Examine response.  If we get multiple failures and no successes,
                //        we should stop publishing updates.
                BufferedReader reader = new BufferedReader(new InputStreamReader(httpsConn.getErrorStream(),
                        StandardCharsets.UTF_8));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                String responseString = response.toString();

                CTError(TAG, "https bad response: " + responseCode + " :" + responseString);
                Bundle parameters = new Bundle();
                parameters.putInt("r2c_responseCode", responseCode);
                parameters.putString("r2c_response", responseString);
                CaltopoClient.CTEvent(TAG, "PublishToLivetrackFailed", parameters);
            }
        } catch (IOException e) {
            CTError(TAG, "openConnection() raised:", e);
        }
    }

    public void publishLive(double lat, double lng, long altitudeInMeters,
                            @Nullable PositionTelemetry telemetry) {
        if (IsExitRequested()) return;
        try {
            GetLivePublishExecutorPool().submit(() ->
                    bgPublishLive(droneSpec.getRemoteId(), lat, lng, altitudeInMeters, telemetry));
        } catch (RejectedExecutionException e) {
            CTWarn(TAG, "publishLive(): executor rejected task", e);
        } catch (Exception e) {
            CTError(TAG, "executorPool.submit() raised:", e);
        }
    }

    private void terminateTrack(String msg, boolean updateDroneSpecs) {
        if (droneSpec.isActive()) {
            String trackLabel = droneSpec.trackLabel();
            WaypointTrack.ArchiveTrack(trackLabel);
            if (null != liveTrack) {
                CTDebug(TAG, msg);
                liveTrack.finishTrack(msg);
            }
            droneSpec.reset();
            idleTimeoutPoll.stop();
        } else {
            CTDebug(TAG, "terminateTrack(): Ignoring inactive track.");
        }
        if (updateDroneSpecs) {
            UpdateDroneSpecs();
        }
    }

    // Unfortunately, older versions of Android don't handle IPv4/IPv6 very well,
    // so we're forced to use OkHttpClient in place of the legacy HttpURLConnection
    // to encourage use of IPv4, which is all we ever wanted in the first place...
    public static int BgPublishGeoJsonStats(String geoJsonString) {
        if (IsExitRequested()) {
            return 499;
        }
        ClientClassState ccs = GetState();
        if (ccs.trackerApiKey.isEmpty() || ccs.trackerUrlPfx.isEmpty()) return 8675309;

        String urlStr = String.format(Locale.US, "%s/%s", ccs.trackerUrlPfx, "upload");
        long startStamp = System.currentTimeMillis();

        // Define the Media Type
        MediaType JSON = MediaType.get("application/json; charset=utf-8");

        // Create the Request Body
        RequestBody body = RequestBody.create(geoJsonString, JSON);

        // Build the Request
        Request request = new Request.Builder()
                .url(urlStr)
                .put(body) // This sets the method to PUT
                .header("User-Agent", "RID2Caltopo/0.1")
                .header("X-SAR-Token", ccs.trackerApiKey)
                .build();

        CTDebug(TAG, "BgPublishStats(): uploading " + geoJsonString.length() + " characters to '" + urlStr + "'");

        try (Response response = CaltopoSession.MyOkHttpClient.newCall(request).execute()) {
            int responseCode = response.code();
            long endStamp = System.currentTimeMillis();

            StringBuilder responseLog = new StringBuilder();
            responseLog.append(String.format(Locale.US,
                    "BgPublishStats(%s) completed in %.3f seconds with code %d.\n",
                    ccs.trackerUrlPfx, (endStamp - startStamp) / 1000.0, responseCode));

            // Read the body (Response.body().string() handles stream closing)
            String bodyString = response.body() != null ? response.body().string() : "";
            responseLog.append(bodyString);

            CTDebug(TAG, responseLog.toString());
            return responseCode;

        } catch (IOException e) {
            CTError(TAG, "BgPublishStats() raised IOException (Network/DNS issue):", e);
            return 503; // Service Unavailable / Custom error code
        }
    }

    public static Future<Integer> PublishGeoJsonStats(@NonNull String geoJsonString) {
        if (IsExitRequested()) {
            return CompletableFuture.completedFuture(499);
        }
        return GetGeoJsonStatsExecutorPool().submit(() -> BgPublishGeoJsonStats(geoJsonString));
    }

    @NonNull
    private static synchronized ExecutorService GetLivePublishExecutorPool() {
        if (IsExitRequested()) {
            throw new RejectedExecutionException("App exit in progress");
        }
        if (LivePublishExecutorPool == null || LivePublishExecutorPool.isShutdown() || LivePublishExecutorPool.isTerminated()) {
            LivePublishExecutorPool = Executors.newFixedThreadPool(LivePublishThreadPoolSize);
        }
        return LivePublishExecutorPool;
    }

    @NonNull
    private static synchronized ExecutorService GetGeoJsonStatsExecutorPool() {
        if (IsExitRequested()) {
            throw new RejectedExecutionException("App exit in progress");
        }
        if (GeoJsonStatsExecutorPool == null || GeoJsonStatsExecutorPool.isShutdown() || GeoJsonStatsExecutorPool.isTerminated()) {
            GeoJsonStatsExecutorPool = Executors.newFixedThreadPool(GeoJsonStatsThreadPoolSize);
        }
        return GeoJsonStatsExecutorPool;
    }

    @NonNull
    private static synchronized ExecutorService GetArchiveScanExecutorPool() {
        if (IsExitRequested()) {
            throw new RejectedExecutionException("App exit in progress");
        }
        if (ArchiveScanExecutorPool == null || ArchiveScanExecutorPool.isShutdown() || ArchiveScanExecutorPool.isTerminated()) {
            ArchiveScanExecutorPool = Executors.newFixedThreadPool(ArchiveScanThreadPoolSize);
        }
        return ArchiveScanExecutorPool;
    }

    private static void ShutdownExecutorPool(@Nullable ExecutorService pool, boolean immediate) {
        if (pool == null) return;
        if (immediate) {
            pool.shutdownNow();
        } else {
            pool.shutdown();
        }
    }


    /**
     * newWaypoint() - process a new waypoint from OpenDroneIdDataManager().
     * Note that lat, lng, altitudeInMeters, and droneTimestampInSeconds are all values
     * provided by the drone's remote id module and quality of measurement is going to
     * vary from one source to the next.  Do a basic sanity check on anything before
     * relying on it.
     */
    public boolean newWaypoint(double lat, double lng, long altitudeInMeters, long droneTimestampInMilliseconds,
                               CtDroneSpec.TransportTypeEnum transportType,
                               @Nullable PositionTelemetry telemetry) {
        boolean goLiveFlag = GetGoLiveFlag();

        if (null == droneSpec) {
            CTError(TAG, String.format(Locale.US, "newWaypoint() droneSpec missing for %s", remoteId));
            return false;
        }
        if (!droneSpec.checkNewWaypoint(lat, lng, altitudeInMeters, transportType)) {
            // Keep local map motion responsive even when this waypoint is filtered out
            // for Caltopo publishing/rate control.
            CaltopoLiveTrack.NotifyLocalTrackPoint(droneSpec, lat, lng, altitudeInMeters, droneTimestampInMilliseconds);
            return false;
        }
        long goodCount = droneSpec.getGoodCount();
        CTDebug(TAG, String.format(Locale.US,
                "newWaypoint(%d): adding %.7f, %.7f to %s via %s...",
                goodCount, lat, lng, droneSpec.trackLabel(), transportType));
        WaypointTrack.AddWaypointForTrack(droneSpec, lat, lng, altitudeInMeters, droneTimestampInMilliseconds);
        CaltopoMap.MapStatusListener.mapStatus mapStatus = CaltopoMap.GetMapStatus();
        if (mapStatus == CaltopoMap.MapStatusListener.mapStatus.up) {
            if (null == liveTrack) {
                CTDebug(TAG, "newWaypoint(): starting new liveTrack for: " + droneSpec.trackLabel());
                liveTrack = new CaltopoLiveTrack(droneSpec, lat, lng, altitudeInMeters, droneTimestampInMilliseconds, telemetry);
            } else if (liveTrack.isActive()) {
                liveTrack.publishDirect(lat, lng, altitudeInMeters, droneTimestampInMilliseconds, telemetry);
            } else {
                CTDebug(TAG, "newWaypoint(): restarting liveTrack: " + droneSpec.trackLabel());
                liveTrack.startNewTrack(lat, lng, altitudeInMeters, droneTimestampInMilliseconds, telemetry);
            }

            // Idle-track termination is handled centrally by ProcessSortedCurrentDroneSpecArray().
        }

        // Preserve legacy personal-map behavior: when no Teams map is up,
        // direct LiveTrack updates are controlled exclusively by the LiveUpdates toggle.
        if (mapStatus == CaltopoMap.MapStatusListener.mapStatus.down && goLiveFlag) {
            try {
                publishLive(lat, lng, altitudeInMeters, telemetry);
            } catch (Exception e) {
                CTError(TAG, "publishLive() raised:", e);
            }
        }
        return true;
    }
}
