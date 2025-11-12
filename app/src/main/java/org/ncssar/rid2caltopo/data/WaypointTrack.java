package org.ncssar.rid2caltopo.data;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.GetTodaysTrackDir;

import org.json.*;
import org.ncssar.rid2caltopo.app.R2CActivity;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import android.content.ContentResolver;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;

/*
 * Module for building waypoint-based Caltopo tracks on the fly as waypoints come in.
 * This is to support rapid archival to a geojson file on application termination.
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

	// map trackLabel to WaypointTrack.
	public static HashMap<String, WaypointTrack> TrackMap = new HashMap<>();

	public JSONArray coordinates;
	public String trackLabel;
	// startTimeStr is the time the track was started - used in track archive.
	public String startTimeStr;
	private DocumentFile dataFilepath;
	OutputStream outputStream = null;

	public WaypointTrack(@NonNull String trackLabel) {
		SimpleDateFormat sdf = new SimpleDateFormat("ddMMMyyyy-HHmmss", Locale.US);
		startTimeStr = sdf.format(new Date());
		this.trackLabel = trackLabel;
		this.coordinates = new JSONArray();
		setupOutputStream();
		CTDebug(TAG, String.format("AddWaypointForTrack(%s): Starting new track.", trackLabel));
	}


	public static void AddWaypointForTrack(@NonNull CtDroneSpec droneSpec, double lat, double lng,
										   long altAboveLaunchInMeters, long timestampInMillisec) {
		String trackLabel = droneSpec.trackLabel();
		WaypointTrack track = TrackMap.get(trackLabel);
		if (null == track) {
			track = new WaypointTrack(trackLabel);
			TrackMap.put(trackLabel, track);
		}
		track.addWaypoint(lat, lng, altAboveLaunchInMeters, timestampInMillisec);
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
		Context ctxt = R2CActivity.getAppContext();
		DocumentFile todaysArchiveDir = GetTodaysTrackDir();
		if (null == ctxt || null == todaysArchiveDir) return;
		try {
			String filename = trackLabel + ".json";
			dataFilepath = todaysArchiveDir.createFile("application/geo+json", filename);
			ContentResolver resolver = ctxt.getContentResolver();
			outputStream = resolver.openOutputStream(dataFilepath.getUri());
			CTDebug(TAG, "setupOutputStream(): stream opened for " + dataFilepath.getUri());
		} catch (Exception e) {
			CTError(TAG, "setupOutputStream() raised.", e);
		}
	}

	public void archive() {
		long numCoords = coordinates.length();
		if (numCoords > 0 && null == outputStream) {
			CTDebug(TAG, "archive(): outputStream already archived.");
			return;
		}

		CTDebug(TAG, String.format(Locale.US, "archive(): writing %d coordinates to %s",
				numCoords, dataFilepath.getUri()));
		try {
			JSONObject jo = new JSONObject();
			jo.put("type", "Feature");

			JSONObject joTitle = new JSONObject();
			joTitle.put("title", trackLabel);
			joTitle.put("start_time", startTimeStr);
			jo.put("properties", joTitle);

			JSONObject joGeometry = new JSONObject();
			joGeometry.put("type", "LineString");
			joGeometry.put("coordinates", coordinates);
			jo.put("geometry", joGeometry);

			JSONArray jaFeatures = new JSONArray();
			jaFeatures.put(jo);

			JSONObject joTop = new JSONObject();
			joTop.put("type", "FeatureCollection");
			joTop.put("features", jaFeatures);

			try {
				outputStream.write(joTop.toString().getBytes());
				outputStream.flush();
				outputStream.close();
				outputStream = null;
			} catch (IOException e) {
				CTError(TAG, String.format(Locale.US, "archive(%s): raised.",
						dataFilepath.getUri()), e);
			}
			CTDebug(TAG, String.format("archive(): %s written.", dataFilepath.getUri()));
		} catch (JSONException e) {
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
