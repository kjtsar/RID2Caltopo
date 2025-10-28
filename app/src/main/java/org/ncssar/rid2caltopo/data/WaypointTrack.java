package org.ncssar.rid2caltopo.data;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;

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
import android.util.Log;

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
	private static final boolean PromiscuousMode = false;

	// map trackLabel to WaypointTrack.
	public static HashMap<String, WaypointTrack> TrackMap = new HashMap<>();

	public JSONArray coordinates;
	public long lastArchiveLength;
	public String trackLabel;
	// startTimeStr is the time the track was started - used in track archive.
	public String startTimeStr;
	public double lastLat;
	public double lastLng;
	public long lastTimestampInMillisec;

	public WaypointTrack(@NonNull String trackLabel) {
		SimpleDateFormat sdf = new SimpleDateFormat("ddMMMyyyy-HHmmss", Locale.US);
		startTimeStr = sdf.format(new Date());
		this.trackLabel = trackLabel;
		this.coordinates = new JSONArray();
		this.lastLat = this.lastLng = 0.0;
		CTDebug(TAG, String.format("AddWaypointForTrack(%s): Starting new track.", trackLabel));
	}

	// Rough distance measurement based on Equirectangular Distance Approximation.
	private static long RoughLatLongDeltaInFeet(double lat1, double lng1, double lat2, double lng2) {
		double retval = 0.0;
		if (lat1 != lat2 || lng1 != lng2) {
			final double EARTH_RADIUS_IN_FEET = 2.093e+7;
			double lat1rad = Math.toRadians(lat1);
			double lng1rad = Math.toRadians(lng1);
			double lat2rad = Math.toRadians(lat2);
			double lng2rad = Math.toRadians(lng2);
			double x = (lng2rad - lng1rad) * Math.cos((lat1rad + lat2rad) / 2);
			double y = (lat2rad - lat1rad);
			retval = Math.sqrt(x * x + y * y) * EARTH_RADIUS_IN_FEET;
		}
	//	Log.d(TAG, String.format("RoughLatLongDeltaInFeet(1:%.7f,%.7f 2:%.7f,%.7f) diff:%f feet",
	//			lat1, lng1, lat2, lng2, retval));
		return (long)retval;
	}

	public static long UnsavedMsgCountForTrack(String trackLabel) {
		WaypointTrack track = TrackMap.get(trackLabel);
		if (null == track) return 0;
		long numCoords = track.coordinates.length();
		if (0 == numCoords || numCoords == track.lastArchiveLength) return 0;

		return numCoords - track.lastArchiveLength;
	}

	// returns true if waypoint meets requirements and is added to track.
	public static boolean AddWaypointForTrack(@NonNull String trackLabel, double lat, double lng,
											  long altAboveLaunchInMeters, long timestampInMillisec,
											  CtDroneSpec.TransportTypeEnum transportType) {
		WaypointTrack track = TrackMap.get(trackLabel);
		if (null == track) {
			track = new WaypointTrack(trackLabel);
			TrackMap.put(trackLabel, track);
		}
		return track.addWaypoint(lat, lng, altAboveLaunchInMeters, timestampInMillisec, transportType);
	}

	public static void ArchiveTracks(Context ctxt) {
		if (0 == WaypointCount) {
			CTDebug(TAG, "ArchiveTracks(): no waypoints recorded");
			return;
		}
		DocumentFile todaysArchiveDir = CaltopoClient.GetTodaysTrackDir();
		if (null == todaysArchiveDir) return;
		for (Map.Entry<String, WaypointTrack> map : TrackMap.entrySet()) {
			WaypointTrack track = map.getValue();
			track.archive(ctxt, todaysArchiveDir);
		}
	}

	public void archive(Context ctxt, DocumentFile archiveDir) {
		long numCoords = coordinates.length();
		if (0 == numCoords || numCoords == lastArchiveLength) {
			Log.i(TAG, "archive(): No new coordinates to archive.");
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
	public boolean addWaypoint(double lat, double lng,
							   long altInMeters, long timestampInMillisec, CtDroneSpec.TransportTypeEnum transportType) {
		long distanceInFeet = 0;

		if ((lastTimestampInMillisec != 0) && (timestampInMillisec <= lastTimestampInMillisec)) {
			// have to handle this case because one drone can advertise on both Bluetooth and WiFi
			// and we can receive multiple updates for the same drone.  We also never want to
			// update more often than once per second.
			return false;
		}

		if (PromiscuousMode) {
			// even in promiscuous mode, we don't want to record redundant waypoints.
			if (lat == lastLat && lng == lastLng) return false;

		} else {
			long minDistanceInFeet = CaltopoClient.GetMinDistanceInFeet();
			distanceInFeet = RoughLatLongDeltaInFeet(lat, lng, lastLat, lastLng);
			if (distanceInFeet < minDistanceInFeet) return false;
		}

		if (lat == 0.0 && lng == 0.0) {
			CTInfo(TAG, String.format("addWaypoint(%s):  lat/lng both zero.", trackLabel));
			return false;
		}

		JSONArray ja = new JSONArray();
		ja.put(String.format(Locale.US, "%.6f", lng));
		ja.put(String.format(Locale.US, "%.6f", lat));
		ja.put(String.format(Locale.US, "%d", altInMeters));
		ja.put(String.format(Locale.US, "%d", timestampInMillisec));
		coordinates.put(ja);
		WaypointCount++;
		lastLat = lat;
		lastLng = lng;
		long deltaTimeInMillisec = (0 == lastTimestampInMillisec) ? 0 : timestampInMillisec - lastTimestampInMillisec;
		lastTimestampInMillisec = timestampInMillisec;
		if (PromiscuousMode) {
			CTDebug(TAG, String.format(Locale.US,
					"addWaypoint(%s/%s): promiscuous mode (any change) %.3f seconds, adding %.7f,%.7f",
					trackLabel, transportType, (double)deltaTimeInMillisec/1000.0, lat, lng));
		} else {
			CTDebug(TAG, String.format(Locale.US,
					"addWaypoint(%s/%s): delta %d feet after %.3f seconds, adding %.7f,%.7f",
					trackLabel, transportType, distanceInFeet, (double)deltaTimeInMillisec/1000.0, lat, lng));
		}
		return true;
	}
}
