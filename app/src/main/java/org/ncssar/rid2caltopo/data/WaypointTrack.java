package org.ncssar.rid2caltopo.data;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.GetTodaysTrackDir;

import org.json.*;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
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
	public long lastArchiveLength;
	public String trackLabel;
	// startTimeStr is the time the track was started - used in track archive.
	public String startTimeStr;

	public WaypointTrack(@NonNull String trackLabel) {
		SimpleDateFormat sdf = new SimpleDateFormat("ddMMMyyyy-HHmmss", Locale.US);
		startTimeStr = sdf.format(new Date());
		this.trackLabel = trackLabel;
		this.coordinates = new JSONArray();
		CTDebug(TAG, String.format("AddWaypointForTrack(%s): Starting new track.", trackLabel));
	}

	// returns true if waypoint meets requirements and is added to track.
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

	public static void ArchiveTrack(@NonNull String trackLabel, Context ctxt) {
		WaypointTrack track = TrackMap.get(trackLabel);
		if (null != track && track.coordinates.length() > 0) {
			DocumentFile todaysArchiveDir = GetTodaysTrackDir();
			if (null != todaysArchiveDir) track.archive(ctxt, todaysArchiveDir);
		}
	}

	public static void ArchiveTracks(Context ctxt) {
		if (0 == WaypointCount) {
			CTDebug(TAG, "ArchiveTracks(): no waypoints recorded");
			return;
		}
		DocumentFile todaysArchiveDir = GetTodaysTrackDir();
		if (null == todaysArchiveDir) return;
		for (Map.Entry<String, WaypointTrack> map : TrackMap.entrySet()) {
			WaypointTrack track = map.getValue();
			track.archive(ctxt, todaysArchiveDir);
		}
	}

	public void archive(Context ctxt, DocumentFile archiveDir) {
		long numCoords = coordinates.length();
		if (0 == numCoords || numCoords == lastArchiveLength) {
			CTDebug(TAG, String.format(Locale.US, "archive(%s): No new coordinates to archive.", trackLabel));
			return;
		}
		CTDebug(TAG, String.format(Locale.US, "archive(%s): writing %d coordinates.",
				trackLabel, numCoords));

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
			if (0 != lastArchiveLength) {
				// FIXME: better to rename/move, then delete after the new file is written
				DocumentFile dataFilepath = archiveDir.findFile(trackLabel);
				if (null != dataFilepath) {
					dataFilepath.delete();
				}
			}
			DocumentFile dataFilepath = archiveDir.createFile("application/geo+json",	trackLabel);

			try {
				CTDebug(TAG, "archiving track to file: " + dataFilepath);
				ContentResolver resolver = ctxt.getContentResolver();
				OutputStream os = resolver.openOutputStream(dataFilepath.getUri());
				os.write(joTop.toString(4).getBytes());
				os.flush();
				os.close();
				lastArchiveLength = numCoords;
			} catch (IOException e) {
				CTError(TAG, String.format("archive(%s):%s raised:\n%s.", dataFilepath,
						trackLabel, e));
			}
			CTDebug(TAG, String.format("archive(%s):%s.", archiveDir, trackLabel));
		} catch (JSONException e) {
			CTError(TAG, String.format("archive(%s):%s raised:\n%s.", archiveDir,
					trackLabel, e));
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
	}
}
