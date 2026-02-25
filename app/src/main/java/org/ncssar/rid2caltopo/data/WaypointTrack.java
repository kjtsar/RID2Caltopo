/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */


package org.ncssar.rid2caltopo.data;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn;
import static org.ncssar.rid2caltopo.data.CaltopoClient.GetTodaysTrackDir;

import org.json.*;
import org.ncssar.rid2caltopo.app.R2CApplication;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.time.ZoneId;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

/*
 * Module for building waypoint-based Caltopo tracks on the fly as waypoints come in.
 * This is to support rapid archival to a geo-json file on application termination.
 *
 * Compare one waypoint to the next to determine if there is significant enough change
 * in location to warrant archiving the new point - per MinDistanceInFeet parameter.
 *
  * Sample Caltopo .json file format:
  * filename: <mappedID><startTimestamp>.json
 * 
 * {
 *   "type": "FeatureCollection",
 *   "features": [
 *     {
 *       "type": "Feature",
 *       "properties": {
 *         "title": "<mappedID><startTimestamp>"
 *       },
 *       "geometry": {
 *         "type": "LineString",
 *         "coordinates": [
 *           [
 *             -121.09279,
 *             39.2966,
 *             358,
 *             1752642725896
 *           ],
 *           [
 *             -121.09279,
 *             39.2966,
 *             358,
 *             1752642726897
 *           ]
 *         ]
 *       }   // geometry feature
 *     }     // Feature
 *   ]       // Feature array   
 * }         // geojson FeatureCollection
 *
 */


public class WaypointTrack {

	public static int WaypointCount = 0;
	private static final String TAG = "WaypointTrack";
    private static final String ReportedFilenames = "r2c_reported.txt";
    private static final int MAX_GEOJSON_STATS_RETRIES = 3;
    private static final long GEOJSON_RETRY_BASE_DELAY_MS = 500;
    private static final long GEOJSON_PUBLISH_TIMEOUT_SECONDS = 20;

    private static final String GEOJSON_MIME_TYPE = "application/geo+json";
	// map trackLabel to WaypointTrack.
	private static HashMap<String, WaypointTrack> TrackMap = new HashMap<>();

    private CtDroneSpec droneSpec;
    
    private String incident;
    private String opPeriod;
    private String mapId;

	private JSONArray coordinates;
	private String trackLabel;
	// startTimeStr is the time the track was started - used in track archive.
	private String startTimeStr;
	private DocumentFile dataFilepath;
    private String fileName;
	private OutputStream outputStream = null;

	public WaypointTrack(@NonNull String trackLabel, @NonNull CtDroneSpec droneSpec) {
		SimpleDateFormat sdf = new SimpleDateFormat("ddMMMyyyy-HHmmss", Locale.US);
		startTimeStr = sdf.format(new Date());
        this.droneSpec = droneSpec;
		this.trackLabel = trackLabel;
		this.coordinates = new JSONArray();
        this.incident = CaltopoClient.GetIncident();
        this.opPeriod = CaltopoClient.GetOpPeriod();
        this.mapId = CaltopoMap.GetMapId();
		setupOutputStream();
		CTDebug(TAG, String.format("AddWaypointForTrack(%s): Starting new track.", trackLabel));
	}


	public static void AddWaypointForTrack(@NonNull CtDroneSpec droneSpec, double lat, double lng,
										   long altitude, long timestampInMillisec) {
		String trackLabel = droneSpec.trackLabel();
		WaypointTrack track = TrackMap.get(trackLabel);
		if (null == track) {
			track = new WaypointTrack(trackLabel, droneSpec);
			TrackMap.put(trackLabel, track);
		}
		track.addWaypoint(lat, lng, altitude, timestampInMillisec);
	}

    public static void ArchiveTrack(@NonNull String trackLabel) {
		WaypointTrack track = TrackMap.remove(trackLabel);
		if (null != track) track.archive();
	}

    public static void ArchiveTracks() {
		if (0 == WaypointCount) {
			CTDebug(TAG, "ArchiveTracks(): no waypoints recorded");
			return;
		}
        ArrayList<String> keys = new ArrayList<>(TrackMap.size());
		keys.addAll(TrackMap.keySet());
		for (String key : keys) {
			WaypointTrack track = TrackMap.remove(key);
			if (null != track) track.archive();
		}
	}

	private void setupOutputStream() {
		Context ctxt = R2CApplication.getAppCtxt();
		DocumentFile todaysArchiveDir = GetTodaysTrackDir();
		if (null == ctxt || null == todaysArchiveDir) return;
		try {
			fileName = trackLabel + ".json";
			dataFilepath = todaysArchiveDir.createFile(GEOJSON_MIME_TYPE, fileName);
			ContentResolver resolver = ctxt.getContentResolver();
			outputStream = resolver.openOutputStream(dataFilepath.getUri());
			CTDebug(TAG, "setupOutputStream(): stream opened for " + dataFilepath.getUri());
		} catch (Exception e) {
			CTError(TAG, "setupOutputStream() raised.", e);
		}
	}

    @Nullable
    public JSONObject getGeoJson() {
        JSONObject joTop = new JSONObject();
        try {
            JSONObject jo = new JSONObject();
            jo.put("type", "Feature");

            JSONObject r2cProp = new JSONObject();
            r2cProp.put("owner", droneSpec.getOwner());
            r2cProp.put("model", droneSpec.getModel());
            r2cProp.put("org", droneSpec.getOrg());
            r2cProp.put("rid", droneSpec.getRemoteId());
            r2cProp.put("mid", droneSpec.getMappedId());
            r2cProp.put("incident", incident);
            r2cProp.put("op_period", opPeriod);
            r2cProp.put("map_id", mapId);
            r2cProp.put("tz_str", ZoneId.systemDefault().getId());
            r2cProp.put("distance_mi",
                    String.format(Locale.US, "%.4f",
                            (float)droneSpec.getDistanceInFeet()/5280.0));

            JSONObject joProp = new JSONObject();
            joProp.put("title", trackLabel);
            joProp.put("start_time", startTimeStr);
            joProp.put("r2c_prop", r2cProp);
            jo.put("properties", joProp);

            JSONObject joGeometry = new JSONObject();
            joGeometry.put("type", "LineString");
            joGeometry.put("coordinates", coordinates);
            jo.put("geometry", joGeometry);

            JSONArray jaFeatures = new JSONArray();
            jaFeatures.put(jo);

            joTop.put("type", "FeatureCollection");
            joTop.put("features", jaFeatures);
        } catch (Exception e) {
            CTError(TAG, "getGeoJson() raised", e);
            return null;
        }
        return joTop;
    }

    @NonNull
    private static HashSet<String> ReadFilenamesFromDocFile(@NonNull DocumentFile reportedFilepath) {
        HashSet<String> filenames = new HashSet<>();
        Context ctxt = R2CApplication.getAppCtxt();
        if (null == ctxt) {
            CTError(TAG, "ReadFilenamesFromDocFile(): context not defined.");
            return filenames;
        }
        try {
            Uri uri = reportedFilepath.getUri();
            InputStream is = ctxt.getContentResolver().openInputStream(uri);
            InputStreamReader isr = new InputStreamReader(is);
            BufferedReader reader = new BufferedReader(isr);
            String filename;
            while ((filename = reader.readLine()) != null) filenames.add(filename);
            reader.close();
        } catch (Exception e) {
            CTError(TAG, "ReadFilenamesFromDocFile() raised.", e);
        }
        return filenames;
    }

    @Nullable
    private static JSONObject ReadGeoJson(DocumentFile waypointFile) throws IOException, JSONException {
        Context ctxt = R2CApplication.getAppCtxt();
        if (null == ctxt) {
            CTError(TAG, "ReadGeoJson(): context not defined.");
            return null;
        }

        JSONObject waypointTrack = null;
        Uri uri = waypointFile.getUri();
        StringBuilder builder = new StringBuilder();
        InputStream is = ctxt.getContentResolver().openInputStream(uri);
        InputStreamReader isr = new InputStreamReader(is);
        BufferedReader reader = new BufferedReader(isr);
        String line;
        while ((line = reader.readLine()) != null) builder.append(line);
        reader.close();
        isr.close();
        if (null != is) is.close();
        // Make sure file contains a self-consistent JSON structure:
        waypointTrack = new JSONObject(builder.toString());
        return waypointTrack;
    }

    static void ReportStatsForFile(@NonNull DocumentFile reportedFilepath, @NonNull String filename) {
        Uri uri = reportedFilepath.getUri();
        Context ctxt = R2CApplication.getAppCtxt();
        if (null == ctxt) {
            CTError(TAG, "ReportStatsForFile(): missing ctxt");
            return;
        }
        ContentResolver resolver = ctxt.getContentResolver();
        if (null == resolver) {
            CTError(TAG, "ReportStatsForFile(): missing required resolver()");
            return;
        }
        try {
            OutputStream os = resolver.openOutputStream(uri, "wa");
            if (null != os) {
                PrintWriter writer = new PrintWriter(os);
                writer.println(filename);
                writer.flush();
                writer.close();
                os.close();
                CTDebug(TAG, "ReportStatsForFile() logged for " + filename);
            }
        } catch (Exception e) {
            CTError(TAG, "ReportStatsForFile() raised", e);
        }
    }

    private static boolean IsTransientStatsResponse(int responseCode) {
        return responseCode == 408 || responseCode == 429 || responseCode >= 500;
    }

    private static int PublishGeoJsonStatsWithRetry(@NonNull String geoJsonString, @NonNull String context) {
        int responseCode = 503;
        for (int attempt = 1; attempt <= MAX_GEOJSON_STATS_RETRIES; attempt++) {
            if (CaltopoClient.IsExitRequested()) {
                return 499;
            }
            Future<Integer> publishFuture = null;
            try {
                publishFuture = CaltopoClient.PublishGeoJsonStats(geoJsonString);
                responseCode = publishFuture.get(GEOJSON_PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                if (publishFuture != null) publishFuture.cancel(true);
                responseCode = 408;
                CTWarn(TAG, context + " publish timeout", e);
            } catch (Exception e) {
                responseCode = 503;
                CTWarn(TAG, context + " publish failed", e);
            }
            if (!IsTransientStatsResponse(responseCode)) {
                return responseCode;
            }
            if (attempt >= MAX_GEOJSON_STATS_RETRIES) {
                break;
            }
            if (CaltopoClient.IsExitRequested()) {
                return 499;
            }
            long delayMs = GEOJSON_RETRY_BASE_DELAY_MS * attempt;
            CTWarn(TAG, String.format(Locale.US,
                    "%s transient response %d (attempt %d/%d), retrying in %.3f seconds",
                    context, responseCode, attempt, MAX_GEOJSON_STATS_RETRIES, delayMs / 1000.0));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                CTWarn(TAG, context + " retry sleep interrupted");
                break;
            }
        }
        return responseCode;
    }


    /* BgPollUnreportedTracks()
     *  Called from CaltopoClient by one of it's background threads if it's been
     *  configured with what looks like a reasonable r2c-tracker server.  This
     *  procedure walks thru all the daily ArchiveDirs in the filesystem and
     *  makes sure each of the Waypoint Tracks has been reported and archived
     *  via CaltopoClient.PublishGeoJsonStats().   The first time
     *  PublishGeoJsonStats() raises, this thread must log the error and
     *  assume that the r2c-tracker server is down or il-configured.
     *
     * Compare the date on the ReportedFilenames file to make sure it's
     *
     */
    public static void BgPollUnreportedTracks() {
        int consecutiveFails = 0;
        DocumentFile archiveDir = CaltopoClient.GetArchiveDir();
        if (null == archiveDir || !archiveDir.isDirectory()) {
            CTDebug(TAG, "BgPollUnreportedTracks(): no archiveDir");
            return;
        }
        // archiveDir is the parent directory of the daily track log directories
        for (DocumentFile trackDir : archiveDir.listFiles()) {
            long reportLastModifiedMillis = System.currentTimeMillis();
            if (!trackDir.isDirectory()) continue;
            if (trackDir.getName().equals("cache")) continue;
            CTInfo(TAG, "BgPollUnreportedTracks() Checking " + trackDir.getName());
            Handler mainThreadHandler = new Handler(Looper.getMainLooper());
            DocumentFile reportedFilepath = trackDir.findFile(ReportedFilenames);
            HashSet<String> reportedFilenames = null;
            if (null == reportedFilepath) {
                reportedFilepath = trackDir.createFile("text/plain", ReportedFilenames);
                if (null == reportedFilepath) {
                    CTError(TAG, String.format(Locale.US, "Couldn't create '%s' in '%s'",
                            ReportedFilenames, trackDir.getUri()));
                    continue;
                }
            } else {
                reportLastModifiedMillis = reportedFilepath.lastModified();
                reportedFilenames = ReadFilenamesFromDocFile(reportedFilepath);
            }
            for (DocumentFile file : trackDir.listFiles()) {
                String filename = file.getName();
                DocumentFile finalReportedFilepath = reportedFilepath;

                if (null == filename || !filename.endsWith(".json")) {
                    CTInfo(TAG, "BgPollUnreportedTracks() skipping non geo-json file " + filename);
                    continue;
                }
                if (reportLastModifiedMillis <= (file.lastModified() + 1000 * 60 * 60)) {
                    CTInfo(TAG, "BgPollUnreportedTracks() skipping file that appears to still be active: " + filename);
                    continue;
                }
                CTInfo(TAG, "BgPollUnreportedTracks() found geo-json file " + filename);
                if ((null != reportedFilenames) && reportedFilenames.contains(filename)) {
                    CTInfo(TAG, "BgPollUnreportedTracks() skipping already reported file " + filename);
                    continue;
                }
                // then filename should contain a geo-json file associated with an unreported track.
                JSONObject waypointTrack = null;
                try {
                    waypointTrack = ReadGeoJson(file);
                } catch (Exception e) {
                    CTWarn(TAG, "Not able to read " + filename, e);
                    // ignore this file during future launches:
                    mainThreadHandler.post(() -> ReportStatsForFile(finalReportedFilepath, filename));
                    continue;
                }
                if (null == waypointTrack) continue;
                String geoJsonString = waypointTrack.toString();
                CTDebug(TAG, "BgPollUnreportedTracks() publishing " + filename);
                int responseCode = PublishGeoJsonStatsWithRetry(
                        geoJsonString,
                        String.format(Locale.US, "BgPollUnreportedTracks(%s)", filename));
                if (responseCode == 408 || responseCode >= 500) { // timeouts may indicate network issues
                    consecutiveFails++;
                    if (consecutiveFails > 2) {
                        CTDebug(TAG, "BgPollUnreportedTracks(): suspending ops due to 2 consecutive failures to publish");
                        break;
                    }
                } else {
                    consecutiveFails = 0;
                    mainThreadHandler.post(() -> ReportStatsForFile(finalReportedFilepath, filename));
                }

                // Take a breather - give main thread and network a chance to do something.
                try {
                    Thread.sleep(500);
                } catch (Exception e) {
                    Thread.currentThread().interrupt(); // restore interrupted status
                }
            }
        }
        CTDebug(TAG, "BgPollUnreportedTracks(): Finished processing archiveDir. Terminating.");
    }


    // To keep track of the tracks in the current directory that have been archived, we
    // append the filename to a ReportedFilenames in the same directory.  This way, if the
    // app is unable to report tracks for any reason (i.e. no network or the server isn't
    // running or the app gets killed before it can report all tracks, then follow-up with
    // BgPollUnreportedTracks() to handle any unreported tracks.
    private void statsReported() {
        DocumentFile todaysArchiveDir = GetTodaysTrackDir();
        DocumentFile reportedFilepath = todaysArchiveDir.findFile(ReportedFilenames);
        if (null == reportedFilepath)
            reportedFilepath = todaysArchiveDir.createFile("text/plain", ReportedFilenames);
        if (null == reportedFilepath) {
            CTError(TAG, String.format(Locale.US,
                    "statsReported(%s): Not able to create %s in %s", fileName,
                    ReportedFilenames, todaysArchiveDir.getUri()));
            return;
        }
        ReportStatsForFile(reportedFilepath, fileName);
    }

    public void archive() {
        long numCoords = coordinates.length();
        if (numCoords <= 0 || null == outputStream) {
            CTDebug(TAG, "archive(): no waypoints.");
            return;
        }
        JSONObject joTop = getGeoJson();
        String geoJsonString = null;
        if (null != joTop) try {
            geoJsonString = joTop.toString();
            outputStream.write(geoJsonString.getBytes());
            outputStream.flush();
            outputStream.close();
            outputStream = null;
            CTDebug(TAG, String.format(Locale.US, "archive(): wrote %d coordinates to %s",
                    numCoords, dataFilepath.getUri()));
            if (null != droneSpec && droneSpec.okToLog()) {
                CTDebug(TAG, String.format(Locale.US,
                        "archive(%s): Publishing...", fileName));
                int responseCode = PublishGeoJsonStatsWithRetry(
                        geoJsonString, String.format(Locale.US, "archive(%s)", fileName));
                CTDebug(TAG, String.format(Locale.US,
                        "archive(%s): server returned %d", fileName, responseCode));
                if (responseCode != 408 && responseCode < 500) statsReported();
            }
            // don't report finished for some kind of timeout:
        } catch (Exception e) {
			CTError(TAG, String.format("archive(%s): raised.", dataFilepath.getUri()), e);
		}
	}

    // returns true if waypoint added
	public void addWaypoint(double lat, double lng,
							long altInMeters, long timestampInMillisec) {

		JSONArray ja = new JSONArray();
		ja.put(String.format(Locale.US, "%.6f", lng));
		ja.put(String.format(Locale.US, "%.6f", lat));
		ja.put(String.format(Locale.US, "%d", altInMeters));
		ja.put(String.format(Locale.US, "%d", timestampInMillisec));
		coordinates.put(ja);
		WaypointCount++;
		if (null == outputStream) setupOutputStream();
	}
}
