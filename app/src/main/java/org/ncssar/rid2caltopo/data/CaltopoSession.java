package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;
import static java.lang.Thread.sleep;

import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/* Based on www.github.com/ncssar/caltopo_python (Tom Grundy).  The same 
 * caveats apply here.
 *
 *  Developed for Nevada County Sheriff's Search and Rescue
 *  Copyright (c) 2025 Ken Taylor 1SAR7
 *
 *   Caltopo currently does not have a publicly available API;
 *    this code calls the non-publicized API that could change at any time.
 *
 *   This module is intended to provide a simple, API-version-agnostic caltopo
 *    interface to other applications.
 *
 *   This code is in no way supported or maintained by caltopo LLC or the
 *   authors of caltopo.com, but it does make use of the Caltopo Team API:
 *       https://training.caltopo.com/all_users/team-accounts/teamapi
 *
 *  Eventual home:
 *     www.github.com/ncssar/caltopo_java
 *
 *
 *  Contact the author at kjtsar@kjt.us
 *   Attribution, feedback, bug reports and feature requests are appreciated
 *
 *
 * A session instance attempts to maintain a secure connection with a caltopo 
 * server if connectivity is available.  Any operation that involves
 * communication with the caltopo server occurs asynchronously in a
 * background thread and immediately returns a CaltopoOp that can be polled
 * or blocked upon for a return value.  You can use CaltopoOp.syncOpId() to
 * block until the operation completes, but this will cause your app to
 * freeze if network connectivity is unavailable or very poor.
 * Any basic consistency checking of arguments is performed locally on this
 * side of that communication and will raise a RuntimeException on error
 * instead of returning a CaltopoOp.  If the caller to this module receives
 * a CaltopoOp instead of an exception, the message has been queued for
 * transmission.
 *
 * The session user is free to balance latency and overhead by monitoring
 * message execution status.  Consider that the Caltopo server can get busy
 * at times and that network connectivity can be spotty.  Before sending a 
 * new message, check to see if the previous message completed first.  For
 * example, if you're adding segments to a line and the message containing
 * the previous segments hasn't completed yet, hold off on sending the next 
 * message and continue to accumulate points for it until the preceding 
 * message completes.  Reducing the number of messages the server has to
 * process should speed up overall throughput for everyone.
 * 
 *
 * Without connectivity, all updates are queued locally until connectivity
 * is established and any specified map is found.  If mapId is not found 
 * after getting connectivity, permit user to change mapId or wait until
 * the mapId shows up.  Temporary loss of connectivity results in queued 
 * messages that are flushed upon return of connectivity.
 *
 * Examples:
 *    CaltopoConfig config = CaltopoConfig.fromFile("team.ct");
 *    String mapId = "H61AVOG";
 *    CaltopoSession cts = new CaltopoSession(config);
 *    JSONObject rj = cts.openSyncMap(mapId).syncOp()
 *                       .syncOpJSONObject();
 *    logger.info("Connected to caltopo map '" + mapId + "'.");
 *    defaultFolderId = cts.addFolder("DroneTracks")
 *                         .syncOpId();
 *  ...
 *    void newDroneWaypoint(double lng, double lat) {
 *        if (null == pointList) {
 *           pointList = new ArrayList(1000);
 *        }
 *        ArrayList l = new ArrayList(2);
 *        l.add(lng.toString());
 *        l.add(lat.toString());
 *        pointlist.add(l);
 *        if (lastOp && !lastOp.isDone()) return;
 *        if (null == lineId && lastOp && lastOp.isDone()) {
 *          lineId = lastOp.syncOpId();
 *        }
 *        lastOp = cts.AddLine(pointList.clone(), trackName, trackDescription,
 *                             lineId, defaultFolderId, lineProp);
 *        pointList.clear();
 *     } 
 *
 */


public class CaltopoSession {
	public enum CtsMethod_t {
		GET,
		POST,
		DELETE
	}

    private static final String TAG = "CaltopoSession";
    private static final int DEFAULT_TIMEOUT_MS = 2 * 60 * 1000;
	private static ExecutorService ExecutorPool;
	private final CtLineProperty CtLinePropertyDefault = new CtLineProperty();
	private static final String CALTOPO_API_V1 = "/api/v1/map/";

	private static CtLineProperty LiveTrackLineProp =
			new CtLineProperty(2, 1F, "#0000ff", "solid");

	// instance variables:
	private static CaltopoSessionConfig Config;
	private String mapId;
	private CaltopoOp lastOpenMapOp;
    private long lastSyncTimestamp;

    public CaltopoSession(@NonNull CaltopoSessionConfig cfg)  {Config = cfg; }

	public static void Shutdown() {
		if (ExecutorPool != null) {
			ExecutorPool.shutdown();
		}
		ExecutorPool = null;
    }

    /*
     * @param method
     */
    private static String Sign(CtsMethod_t method, String url, long expiresMsec,
			       String payload, String credentialSecret) {
		try {
			// Construct the message
			String message = method + " " + url + "\n"
					+ expiresMsec + "\n"
					+ (payload != null ? payload : "");

			// Decode the authentication key (Base64)
			byte[] secretKey = Base64.getDecoder().decode(credentialSecret);

			// Create a Mac instance with the HMAC-SHA256 algorithm
			Mac hmac = Mac.getInstance("HmacSHA256");
			SecretKeySpec keySpec = new SecretKeySpec(secretKey, "HmacSHA256");
			hmac.init(keySpec);

			// Generate the signature
			byte[] signatureBytes = hmac.doFinal(message.getBytes(StandardCharsets.UTF_8));

			// Return the signature as a Base64-encoded string
			return Base64.getEncoder().encodeToString(signatureBytes);

		} catch (Exception e) {
			throw new RuntimeException("Error while generating HMAC signature", e);
		}
    }

	@NonNull
	public static String EncodeParm(@NonNull String key, @NonNull String val) {
		return key + "=" + Uri.encode(val);
	}

	@NonNull
    public static String EncodeParams(@NonNull Map<String,String> params) {
		StringBuilder paramString = new StringBuilder();
		for (Map.Entry<String,String> entry : params.entrySet()) {
            paramString
					.append(entry.getKey())
					.append("=")
					.append(Uri.encode(entry.getValue()))
					.append("&");
		}
		return paramString.substring(0, paramString.length()-1);
    }

	// this needs to be run in background thread to prevent blocking the app thread.
	private static CaltopoOp BgSendRequest(CaltopoOp op) {
		boolean retry;
		do  {
			retry = false;
			try {
				op.sentTimestampMsec = System.currentTimeMillis();
				long expires = op.sentTimestampMsec + DEFAULT_TIMEOUT_MS;
				String payloadString = (null == op.payload) ? "" : op.payload.toString();
				Map <String, String>params = new HashMap<>();
				// Construct the query string
				String query = "";
				if (!op.goNaked) {
					// Generate the signature
					String signature = Sign(op.method, op.url, expires, payloadString, Config.credentialSecret);
					params.put("signature", signature);
					params.put("id", Config.credentialId);
					params.put("expires", String.valueOf(expires));
				}

				if (op.method == CtsMethod_t.POST && op.payload != null) {
					params.put("json", payloadString);
				} else if (!params.isEmpty()) {
					query = "?" + EncodeParams(params);
				}

				// Construct the full URL
				String fullUrl;
				if (op.goNaked) {
					fullUrl = op.url + query;
				} else {
					fullUrl = "https://" + Config.domainAndPort + op.url + query;
				}

				// Open a connection
				HttpURLConnection connection = (HttpURLConnection) new URL(fullUrl).openConnection();
				connection.setRequestMethod(op.method.toString());
				connection.setRequestProperty("User-Agent", "RID2Caltopo/0.2");
				if (op.method == CtsMethod_t.POST && op.payload != null) {
					String body = EncodeParams(params);
					connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
					connection.setRequestProperty("Content-Length", String.valueOf(body.length()));
					connection.setDoOutput(true);
					OutputStream os = connection.getOutputStream();
					DataOutputStream dos = new DataOutputStream(os);
					dos.writeBytes(body);
					dos.flush();
					dos.close();
					os.close();
				}

				// Get the response code
				op.responseCode = connection.getResponseCode();
				BufferedReader reader;

				// Handle the response stream
				if (op.responseCode == HttpURLConnection.HTTP_OK) {
					reader = new BufferedReader(new InputStreamReader(connection.getInputStream(),
							StandardCharsets.UTF_8));
				} else {
					reader = new BufferedReader(new InputStreamReader(connection.getErrorStream(),
							StandardCharsets.UTF_8));
				}

				// Read the response
				StringBuilder response = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) {
					response.append(line);
				}
				reader.close();
				op.receivedTimestampMsec = System.currentTimeMillis();
				op.response = response.toString();
				boolean opPassed ;
				if (op.responseCode == HttpURLConnection.HTTP_OK) {
					opPassed = true;
					if (!op.response.isEmpty()) try {
						JSONObject responseJson = new JSONObject(op.response);
						op.responseJson = responseJson.getJSONObject("result");
					} catch (JSONException e) {
						CTError(TAG, "parse JSON result raised: ", e);
					}
				} else opPassed = false;
				op.setOperationIsDone(opPassed); // Provide option to handle on main thread.
				CTInfo(TAG, "BgSendRequest(): Normal Completion:\n  " + op);

			} catch (UnknownHostException e) {
				// this happens when no network connection, so retry after some delay period.
				long minRetryDelayInMsec = 3000;
				long maxRetryDelayInMsec = 60000;
				try {
					sleep(minRetryDelayInMsec + (int) (java.lang.Math.random() * (maxRetryDelayInMsec - minRetryDelayInMsec)));
				} catch (InterruptedException e2) {
					CTDebug(TAG, "sleep() interrupted.");
				}
				retry = true;
			} catch (Exception e) {
				op.goodResponse = false;
				op.response = "Exception raised during request:\n  " + e;
				CTError(TAG, "Exception raised during request:", e);
			}
		} while (retry);
		return op;
	}


	// CaltopoSession Instance methods:

	/** posts message to the background executor pool and returns immediately.
	 *
	 * @param op This is the data structure used to keep track of each asynchronous
	 *   communication with caltopo.
	 *
	 * @param method This enum specifies the http operation see <CtsMethod_t></CtsMethod_t>
	 *
	 * @param url url suffix if goNaked false, otherwise the complete url to send to.
	 *
	 * @param payload The JSON structure to be sent as the payload.
	 *
	 * @param goNaked If true, then just perform simple http transfer.  If false, then
	 *                build a credentialed message based on the Caltopo API.
     */
    private CaltopoOp sendRequest(CaltopoOp op, CtsMethod_t method,
								  String url, JSONObject payload, boolean goNaked) {
		// NOTE: only one bg thread to communicate w/caltopo - we are one of many users...
		if (null == ExecutorPool) {
			ExecutorPool = Executors.newFixedThreadPool(1);
		}
		op.goNaked = goNaked;
		op.method = method;
		op.url = url;
		op.payload = payload;
		op.asyncFuture = ExecutorPool.submit(() -> BgSendRequest(op));
		return op;
    }


    /**
     * Open/sync with the specified map.   If mapId is new, then a full
     * dump of the specified map is requested, otherwise only the changes
     * since the previous open/synch.
     *
     * @param mapId string is the identifier for the map we want to interact with.
	 *    Issue
	 *
     *
     * @return CaltopoOp responseJson on success will contain all the map info.
     *  User will likely check it to see if it needs anything.
     */
    public CaltopoOp openMap(String mapId, Runnable optRunnable)
			throws RuntimeException {
	
		if (null == mapId || mapId.isEmpty()) {
			throw new RuntimeException("missing required mapId");
		}
		this.lastSyncTimestamp = 0;
		if (!mapId.equals(this.mapId)) {
			this.mapId = mapId;
		} else if (null != lastOpenMapOp && lastOpenMapOp.isDone() && !lastOpenMapOp.fail()) {
			this.lastSyncTimestamp = lastOpenMapOp.sentTimestampMsec;
		}

        // remove any update key delimiter:
		String urlEnd = CALTOPO_API_V1 + this.mapId + "/since/" +
				Math.max(0, this.lastSyncTimestamp - 500);

		lastOpenMapOp = new CaltopoOp(optRunnable);
		return this.sendRequest(lastOpenMapOp, CtsMethod_t.GET, urlEnd, null, false);
    }

    /** Add a folder.
     * @param folderName - Label for the folder.
     * @param contentsVisible - determines if new objects added to folder will be visible.
     * @param contentLabelsVisible - determines if labels of new objects added to folder will be visible.
     * @return CaltopoOp on success and null if parsing/configuring args
     */
	@Nullable
    CaltopoOp addFolder(@NonNull String folderName, boolean contentsVisible,
			boolean contentLabelsVisible, @Nullable Runnable optRunnable){

		if (folderName.isEmpty()) {
			CTError(TAG, "Folder name must be specified.");
			return null;
		}
		JSONObject prop = new JSONObject();
		JSONObject top = new JSONObject();
		String urlEnd = CALTOPO_API_V1 + this.mapId + "/Folder";
		try {
			prop.put("title", folderName);
			prop.put("visible", contentsVisible ? "true" : "false");
			prop.put("labelVisible", contentLabelsVisible ? "true" : "false");
			top.put("properties", prop);
		} catch (Exception e) {
			CTError(TAG, "addFolder() raised.", e);
			return null;
		}

		CaltopoOp op = new CaltopoOp(optRunnable);
		return sendRequest(op, CtsMethod_t.POST, urlEnd, top, false);
    }
    
    
    /** addLine() - add line to the session's map.
	 *
	 * @param pointArray - array of {lng,lat] arrays.
     * @param lineLabel - text label for line.
     * @param existingLineId - line ID - if already existing.
     * @param folderId - ID of the folder this line s/b created in.
     * @param description - Description text for line.
     * @return CaltopoOp on success and null if configuring/sending msg
     */
    @Nullable
	CaltopoOp addLine(@NonNull JSONArray pointArray, @NonNull String lineLabel, @Nullable String description,
					  @Nullable String existingLineId, @Nullable String folderId,
					  @Nullable CtLineProperty lineProp, @Nullable Runnable optRunnable) {
		if (0 == pointArray.length()) {
			CTError(TAG, "Can't add a line without any points");
			return null;
		}

		JSONObject prop = new JSONObject();
		JSONObject geometry = new JSONObject();
		JSONObject top = new JSONObject();
		String objid = "";
		if (lineProp == null) lineProp = CtLinePropertyDefault;
		try {
			prop.put("class", "Shape");
			prop.put("updated", System.currentTimeMillis());
			prop.put("title", lineLabel);
			prop.put("description", description);
			if (folderId != null && !folderId.isEmpty()) prop.put("folderId", folderId);
			prop.put("stroke-width", lineProp.width);
			prop.put("stroke-opacity", lineProp.opacity);
			prop.put("stroke", lineProp.color);
			prop.put("pattern", lineProp.pattern);

			geometry.put("type", "LineString");
			geometry.put("coordinates", pointArray);
			geometry.put("size", pointArray.length());

			if (existingLineId != null && !existingLineId.isEmpty()) {
				top.put("id", existingLineId);
				objid = "/" + existingLineId;
				geometry.put("incremental", "true");
			}
			top.put("type", "Feature");
			top.put("properties", prop);
			top.put("geometry", geometry);
		} catch (Exception e){
			CTError(TAG, "addLine() .put raised - for no apparent reason", e);
			return null;
		}
		String urlEnd = CALTOPO_API_V1 + this.mapId + "/Shape" + objid;
		CaltopoOp op = new CaltopoOp(optRunnable);
		sendRequest(op, CtsMethod_t.POST, urlEnd, top, false);
		return op;
    }

	@Nullable
	CaltopoOp addMarker(double lat, double lng, @NonNull String markerTitle,
						@Nullable String symbol, @Nullable String folderId,
						@Nullable String existingMarkerId, @Nullable JSONObject extraProperties,  @Nullable Runnable optRunnable) {
		JSONObject prop = new JSONObject();
		JSONObject geometry = new JSONObject();
		JSONObject top = new JSONObject();
		String objid = "";
		try {
			prop.put("class", "Marker");
			prop.put("updated", System.currentTimeMillis());
			prop.put("title", markerTitle);
			prop.put("marker-color", "#FF0000");
			if (null == symbol || symbol.isEmpty()) symbol = "point";
			prop.put("marker-symbol", symbol);
			prop.put("marker-size", "1");
			prop.put("marker-visibility", "visible");
			if (folderId != null && !folderId.isEmpty()) {
				prop.put("folderId", folderId);
			}
			if (null != extraProperties) try {
                for (Iterator<String> it = extraProperties.keys(); it.hasNext(); ) {
                    String key = it.next();
                    prop.put(key, extraProperties.get(key));
                }
			} catch (Exception e) {
				CTError(TAG, "exception processing extraProperties.", e);
			}
			JSONArray points = new JSONArray(String.format(Locale.US, "[%.7f,%.7f]", lng, lat));
			geometry.put("coordinates", points);
			geometry.put("type", "Point");

			top.put("type", "Feature");
			top.put("properties", prop);
			top.put("geometry", geometry);
			if (existingMarkerId != null && !existingMarkerId.isEmpty()) {
				top.put("id", existingMarkerId);
				objid = "/" + existingMarkerId;
			}
		} catch (Exception e) {
			CTError(TAG, "addMarker() raised.", e);
			return null;
		}
		try {
			CTDebug(TAG, "addMarker(): adding:\n" + top.toString(4));
		} catch (Exception e) {
			CTError(TAG, "keeping compiler happy.", e);
		}

		String urlEnd = CALTOPO_API_V1 + this.mapId + "/Marker" + objid;
		CaltopoOp op = new CaltopoOp(optRunnable);
		sendRequest(op, CtsMethod_t.POST, urlEnd, top, false);
		return op;
	}

	@NonNull
	public CaltopoOp deleteShapeWithId(@NonNull String objId, @Nullable Runnable optRunnable) {
		String urlEnd = CALTOPO_API_V1 + this.mapId + "/Shape/" + objId;
		CaltopoOp op = new CaltopoOp(optRunnable);
		sendRequest(op, CtsMethod_t.DELETE, urlEnd, null, false);
		return op;
	}

	@NonNull
	public CaltopoOp deleteMarkerWithId(@NonNull String objId, @Nullable Runnable optRunnable) {
		String urlEnd = CALTOPO_API_V1 + this.mapId + "/Marker/" + objId;
		CaltopoOp op = new CaltopoOp(optRunnable);
		sendRequest(op, CtsMethod_t.DELETE, urlEnd, null, false);
		return op;
	}

	@NonNull
	public CaltopoOp deleteLiveTrackWithId(@NonNull String objId, @Nullable Runnable optRunnable) {

		String urlEnd = CALTOPO_API_V1 + this.mapId + "/LiveTrack/" + objId;
		CaltopoOp op  = new CaltopoOp(optRunnable);
		sendRequest(op, CtsMethod_t.DELETE, urlEnd, null, false);
		return op;
	}

	@NonNull
	public CaltopoOp editObjectWithId(@NonNull String objectType, @NonNull String objId,
									  @NonNull JSONObject featureSet, @Nullable Runnable optRunnable) {

		String urlEnd = CALTOPO_API_V1 + this.mapId + "/" + objectType + "/" + objId;
		CaltopoOp op = new CaltopoOp(optRunnable);
		sendRequest(op, CtsMethod_t.POST, urlEnd, featureSet, false);
		return op;
	}

	@Nullable
	public CaltopoOp startLiveTrack(@NonNull String groupId, @NonNull String deviceId, @NonNull String label,
									@Nullable String folderId, @Nullable String description,
									@Nullable CtLineProperty lineProp, @Nullable Runnable optRunnable) {
		JSONObject prop = new JSONObject();
		if (groupId.isEmpty() || deviceId.isEmpty()) {
			CTError(TAG, "startLiveTrack(): group and device IDs required.");
			return null;
		}
		if (lineProp == null) lineProp = LiveTrackLineProp;
		JSONObject top = new JSONObject();
		try {
			prop.put("title", label);
			prop.put("stroke-width", lineProp.width);
			prop.put("stroke-opacity", lineProp.opacity);
			prop.put("stroke", lineProp.color);
			prop.put("pattern", lineProp.pattern);
			prop.put("marker-symbol", "icon-8T781R60-12-0.5-0.5-tf");
			if (null != description && !description.isEmpty()) prop.put("descripion", description);
			prop.put("class", "LiveTrack");
			if (folderId != null && !folderId.isEmpty()) {
				prop.put("folderId", folderId);
			}
			prop.put("deviceId", String.format(Locale.US, "FLEET:%s-%s", groupId, deviceId));

			top.put("type", "Feature");
			top.put("properties", prop);
		} catch (Exception e) {
			CTError(TAG, "startLiveTrack(): raised for no apparent reason.", e);
			return null;
		}

		String urlEnd = CALTOPO_API_V1 + this.mapId + "/LiveTrack";
		CaltopoOp op = new CaltopoOp(optRunnable);
		sendRequest(op, CtsMethod_t.POST, urlEnd, top, false);
		return op;
	}

	@NonNull
	public CaltopoOp addLiveTrackPoint(@NonNull String groupId, @NonNull String deviceId,
									   double lat, double lng, @Nullable Runnable optRunnable) {
		String latStr = String.format(Locale.US, "%.7f", lat);
		String lngStr = String.format(Locale.US, "%.7f", lng);
		String url = "https://caltopo.com/api/v1/position/report/" + groupId + "?" +
				EncodeParm("id", deviceId) + "&" +
				EncodeParm("lat", latStr) + "&" +
				EncodeParm("lng", lngStr);

		CaltopoOp op = new CaltopoOp(optRunnable);
		sendRequest(op, CtsMethod_t.GET, url, null, true);
		return op;
	}

} // end of CaltopoSession class spec.
