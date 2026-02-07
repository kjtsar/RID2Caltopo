
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
import static java.lang.Thread.sleep;

import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dns;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

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
	private static ExecutorService ExecutorPool = null;
	private static final CtLineProperty CtLinePropertyDefault = new CtLineProperty();
	private static final String CALTOPO_API_V1 = "/api/v1/map/";

	private static final CtLineProperty LiveTrackLineProp =
			new CtLineProperty(2, 1F, "#0000ff", "solid");
    private static CaltopoCredentials Cred;
    private static String DomainAndPort;
	// instance variables:
	private static String MapId;

    public static void Init(@NonNull CaltopoCredentials cred, @NonNull String domainAndPort) {
        Cred = cred;
        DomainAndPort = domainAndPort;
    }
    /*** Initiializes and returns a session with information used for all subsequent interactions.
     *   Caller must specify onComplete procedure to receive the team
     */
    public CaltopoSession()  {
        throw new RuntimeException("CaltopoSession(): No instances permitted.");
    }

    public static CaltopoOp VerifyAccount(@Nullable Consumer<CaltopoOp> onComplete) {
        CaltopoOp op = new CaltopoOp(onComplete);
        String urlEnd = "/api/v1/acct/" + Cred.teamId + "/since/0";
        return SendRequest(op, CtsMethod_t.GET, urlEnd, null, false);
    }

	public static void Shutdown() {
		if (ExecutorPool != null) {
			ExecutorPool.shutdown();
		}
		ExecutorPool = null;
    }

    private static String Sign(CtsMethod_t method, String url, long expiresMsec,
                               String payload, String credentialSecret) {
        try {
            // Construct the message
            String message = method + " " + url + "\n" + expiresMsec + "\n";
            if (payload != null && !payload.isEmpty()) {
                message += payload;
            }

            // Use android.util.Base64 for maximum compatibility across all Android versions
            // and to avoid Java 8 compatibility issues on older tablets.
            byte[] secretKey = android.util.Base64.decode(credentialSecret, android.util.Base64.DEFAULT);

            // Create a Mac instance with the HMAC-SHA256 algorithm
            Mac hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secretKey, "HmacSHA256");
            hmac.init(keySpec);

            // Generate the signature
            byte[] signatureBytes = hmac.doFinal(message.getBytes(StandardCharsets.UTF_8));

            // IMPORTANT: Use NO_WRAP to prevent the encoder from adding newlines
            // (which java.util.Base64 sometimes does for long strings)
            return android.util.Base64.encodeToString(signatureBytes, android.util.Base64.NO_WRAP);

        } catch (Exception e) {
            throw new RuntimeException("Error while generating HMAC signature", e);
        }
    }

	@NonNull
	private static String EncodeParm(@NonNull String key, @NonNull String val) {
//        return key + "=" + URLEncoder.encode(val, StandardCharsets.UTF_8);
        return key + "=" + Uri.encode(val);
	}
// Danger Danger Will Robinson: URLEncoder.encode() hangs on Android 10
	@NonNull
    private static String EncodeParams(@NonNull Map<String,String> params) {
		StringBuilder paramString = new StringBuilder();
		for (Map.Entry<String,String> entry : params.entrySet()) {
            paramString
                    .append(entry.getKey())
					.append("=")
//                     .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                    .append(Uri.encode(entry.getValue()))
					.append("&");
		}
		return paramString.substring(0, paramString.length()-1);
    }

	// this needs to be run in background thread to prevent blocking the app thread.
	private static CaltopoOp BgSendRequest(CaltopoOp op) {
		boolean retry;
        boolean goodResponse;
        do {
            retry = false;
            goodResponse = false;

            try {
                op.sentTimestampMsec = System.currentTimeMillis();
                long expires = op.sentTimestampMsec + DEFAULT_TIMEOUT_MS;
                String payloadString = (null == op.payload) ? "" : op.payload.toString();

                // 1. Prepare Base Parameters
                Map<String, String> params = new HashMap<>();
                if (!op.goNaked) {
                    String signature = Sign(op.method, op.url, expires, payloadString, Cred.credentialSecret);
                    params.put("id", Cred.credentialId);
                    params.put("expires", String.valueOf(expires));
                    params.put("signature", signature);
                    params.put("json", "");
                }

                // 2. Build the URL and Request Body
                HttpUrl.Builder urlBuilder;
                if (op.goNaked) {
                    urlBuilder = HttpUrl.parse(op.url).newBuilder();
                } else {
                    urlBuilder = new HttpUrl.Builder()
                            .scheme("https")
                            .host(DomainAndPort.split(":")[0]) // Handle domain vs domain:port
                            .addPathSegments(op.url.startsWith("/") ? op.url.substring(1) : op.url);

                    // Port handling if present in DomainAndPort
                    if (DomainAndPort.contains(":")) {
                        urlBuilder.port(Integer.parseInt(DomainAndPort.split(":")[1]));
                    }
                }

                RequestBody requestBody = null;

                if (op.method == CtsMethod_t.POST && op.payload != null) {
                    // For Caltopo POSTs, the 'json' param contains the payload
                    params.put("json", payloadString);

                    FormBody.Builder formBuilder = new FormBody.Builder();
                    for (Map.Entry<String, String> entry : params.entrySet()) {
                        formBuilder.add(entry.getKey(), entry.getValue());
                    }
                    requestBody = formBuilder.build();
                } else {
                    // For GET/other, params go into the Query String
                    for (Map.Entry<String, String> entry : params.entrySet()) {
                        urlBuilder.addQueryParameter(entry.getKey(), entry.getValue());
                    }
                }

                HttpUrl finalUrl = urlBuilder.build();
                CTInfo(TAG, String.format(Locale.US, "BgSendRequest(%s): fullUrl: '%s'", op.method, finalUrl));

                // 3. Build and Execute Request
                Request.Builder requestBuilder = new Request.Builder()
                        .url(finalUrl)
                        .header("User-Agent", "RID2Caltopo/0.2")
                        .method(op.method.toString(), requestBody);

                try (Response response = CaltopoClient.OkHttpClient.newCall(requestBuilder.build()).execute()) {
                    op.responseCode = response.code();
                    op.receivedTimestampMsec = System.currentTimeMillis();

                    // Response.body().string() handles the stream reading and closing automatically
                    op.response = (response.body() != null) ? response.body().string() : "";

                    if (response.isSuccessful()) {
                        goodResponse = true;
                        if (!op.response.isEmpty()) {
                            try {
                                JSONObject responseJson = new JSONObject(op.response);
                                op.responseJson = responseJson.getJSONObject("result");
                            } catch (JSONException e) {
                                CTError(TAG, "parse JSON result raised: ", e);
                            }
                        }
                    } else {
                        CTError(TAG, "BgSendRequest() failed w/code " + op.responseCode + ":\n" + op.response);
                        Bundle eventParams = new Bundle();
                        eventParams.putInt("r2c_responseCode", op.responseCode);
                        eventParams.putString("r2c_response", op.response);
                        eventParams.putString("r2c_url", op.url);
                        eventParams.putString("r2c_method", op.method.toString());
                        CaltopoClient.CTEvent(TAG, "CaltopoOpFailed", eventParams);
                    }
                    CTInfo(TAG, "BgSendRequest(): Normal Completion:\n  " + op);
                }

            } catch (UnknownHostException e) {
                // This logic remains the same for network retries
                long retryDelay = 3000 + (long) (java.lang.Math.random() * 57000);
                CTDebug(TAG, String.format(Locale.US, "BgSendRequest(): DNS fail, retrying in %.3f seconds...", retryDelay / 1000.0));
                try {
                    Thread.sleep(retryDelay);
                } catch (InterruptedException e2) {
                    CTDebug(TAG, "sleep() interrupted.");
                }
                retry = true;
            } catch (Exception e) {
                op.response = "Exception raised during request:\n  " + e;
                CTError(TAG, "Exception raised during request:", e);
            }
        } while (retry);

        op.goodResponse = goodResponse;
        op.setOperationIsDone(goodResponse);
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
    @NonNull
    private static CaltopoOp SendRequest(CaltopoOp op, CtsMethod_t method,
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
     * @param mapNode contains the identifier for the map we want to interact with.
	 *
     * @param lastSyncTimestamp set to zero to bring in entire map.
	 *
     *
     * @return CaltopoOp responseJson on success will contain all the map info.
     *  User will likely check it to see if it needs anything.
     */
    static public CaltopoOp OpenMap(@NonNull CaltopoNode.MapNode mapNode, long lastSyncTimestamp, @Nullable Consumer<CaltopoOp> onComplete)
            throws RuntimeException {

        if (MapId != null && !MapId.equals(mapNode.getId())) {
            CTDebug(TAG, "openMap(): changing mapId from " + MapId + " to " + mapNode.getId());
        }
        MapId = mapNode.getId();

        // remove any update key delimiter:
        String urlEnd = CALTOPO_API_V1 + MapId + "/since/" +
                Math.max(0, lastSyncTimestamp - 500);

        CaltopoOp op = new CaltopoOp(onComplete);
        return SendRequest(op, CtsMethod_t.GET, urlEnd, null, false);
    }

    /** Add a folder.
     * @param folderName - Label for the folder.
     * @param contentsVisible - determines if new objects added to folder will be visible.
     * @param contentLabelsVisible - determines if labels of new objects added to folder will be visible.
     * @return CaltopoOp on success and null if parsing/configuring args
     */
	@Nullable
    public static CaltopoOp AddFolder(@NonNull String folderName, boolean contentsVisible,
			boolean contentLabelsVisible, @Nullable Consumer<CaltopoOp> onComplete){
        if (null == MapId)
            throw new RuntimeException("AddFolder(): Map not specified - call OpenMap() first");

        if (folderName.isEmpty()) {
			CTError(TAG, "Folder name must be specified.");
			return null;
		}
		JSONObject prop = new JSONObject();
		JSONObject top = new JSONObject();
		String urlEnd = CALTOPO_API_V1 + MapId + "/Folder";
		try {
			prop.put("title", folderName);
			prop.put("visible", contentsVisible ? "true" : "false");
			prop.put("labelVisible", contentLabelsVisible ? "true" : "false");
			top.put("properties", prop);
		} catch (Exception e) {
			CTError(TAG, "addFolder() raised.", e);
			return null;
		}

		CaltopoOp op = new CaltopoOp(onComplete);
		return SendRequest(op, CtsMethod_t.POST, urlEnd, top, false);
    }


    /** AddLine() - add line to the session's selected map.
	 *
	 * @param pointArray - array of {lng,lat] arrays.
     * @param lineLabel - text label for line.
     * @param existingLineId - line ID - if already existing.
     * @param folderId - ID of the folder this line s/b created in.
     * @param description - Description text for line.
     * @return CaltopoOp on success and null if configuring/sending msg failed.
     */
    @NonNull
	public static CaltopoOp AddLine(@NonNull JSONArray pointArray, @NonNull String lineLabel, @Nullable String description,
                                    @Nullable String existingLineId, @Nullable String folderId,
                                    @Nullable CtLineProperty lineProp, @Nullable Consumer<CaltopoOp> onComplete) {
        if (null == MapId)
            throw new RuntimeException("AddLine(): Map not specified - call OpenMap() first");

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
		String urlEnd = CALTOPO_API_V1 + MapId + "/Shape" + objid;
		CaltopoOp op = new CaltopoOp(onComplete);
		SendRequest(op, CtsMethod_t.POST, urlEnd, top, false);
		return op;
    }

	@Nullable
	public static CaltopoOp AddMarker(double lat, double lng, @NonNull String markerTitle,
                                      @Nullable String symbol, @Nullable String folderId,
                                      @Nullable String existingMarkerId, @Nullable JSONObject extraProperties,
                                      @Nullable Consumer<CaltopoOp> onComplete) {
        if (null == MapId)
            throw new RuntimeException("AddMarker(): Map not specified - call OpenMap() first");

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

		String urlEnd = CALTOPO_API_V1 + MapId + "/Marker" + objid;
		CaltopoOp op = new CaltopoOp(onComplete);
		SendRequest(op, CtsMethod_t.POST, urlEnd, top, false);
		return op;
	}

	@NonNull
	public static CaltopoOp DeleteShapeWithId(@NonNull String objId, @Nullable Consumer<CaltopoOp> onComplete) {
        if (null == MapId)
            throw new RuntimeException("DeleteShapeWithId(): Map not specified - call OpenMap() first");

        String urlEnd = CALTOPO_API_V1 + MapId + "/Shape/" + objId;
		CaltopoOp op = new CaltopoOp(onComplete);
		SendRequest(op, CtsMethod_t.DELETE, urlEnd, null, false);
		return op;
	}

	@NonNull
	public static CaltopoOp DeleteMarkerWithId(@NonNull String objId, @Nullable Consumer<CaltopoOp> onComplete) {
        if (null == MapId)
            throw new RuntimeException("DeleteMarkerWithId(): Map not specified - call OpenMap() first");

        String urlEnd = CALTOPO_API_V1 + MapId + "/Marker/" + objId;
		CaltopoOp op = new CaltopoOp(onComplete);
		SendRequest(op, CtsMethod_t.DELETE, urlEnd, null, false);
		return op;
	}

	@NonNull
	public static CaltopoOp DeleteLiveTrackWithId(@NonNull String objId, @Nullable Consumer<CaltopoOp> onComplete) {
        if (null == MapId)
            throw new RuntimeException("DeleteLiveTrackWithId(): Map not specified - call OpenMap() first");

        String urlEnd = CALTOPO_API_V1 + MapId + "/LiveTrack/" + objId;
		CaltopoOp op  = new CaltopoOp(onComplete);
		SendRequest(op, CtsMethod_t.DELETE, urlEnd, null, false);
		return op;
	}

	@NonNull
	public static CaltopoOp EditObjectWithId(@NonNull String objectType, @NonNull String objId,
                                             @NonNull JSONObject featureSet, @Nullable Consumer<CaltopoOp> onComplete) {
        if (null == MapId)
            throw new RuntimeException("EditObjectWithId(): Map not specified - call OpenMap() first");

        String urlEnd = CALTOPO_API_V1 + MapId + "/" + objectType + "/" + objId;
		CaltopoOp op = new CaltopoOp(onComplete);
		SendRequest(op, CtsMethod_t.POST, urlEnd, featureSet, false);
		return op;
	}

	@NonNull
	public static CaltopoOp StartLiveTrack(@NonNull String deviceId, @NonNull String label,
                                           @Nullable String folderId, @Nullable String description,
                                           @Nullable CtLineProperty lineProp, @Nullable Consumer<CaltopoOp> onComplete) {
        if (null == MapId)
            throw new RuntimeException("StartLiveTrack(): Map not specified - call OpenMap() first");
        JSONObject prop = new JSONObject();

		if (lineProp == null) lineProp = LiveTrackLineProp;
		JSONObject top = new JSONObject();
		try {
			prop.put("title", label);
			prop.put("stroke-width", lineProp.width);
			prop.put("stroke-opacity", lineProp.opacity);
			prop.put("stroke", lineProp.color);
			prop.put("pattern", lineProp.pattern);
			prop.put("marker-symbol", "Drone");
			if (null != description && !description.isEmpty()) prop.put("descripion", description);
			prop.put("class", "LiveTrack");
			if (folderId != null && !folderId.isEmpty()) {
				prop.put("folderId", folderId);
			}
			prop.put("deviceId", String.format(Locale.US, "FLEET:DRONE-%s",  deviceId));

			top.put("type", "Feature");
			top.put("properties", prop);
		} catch (Exception e) {
			CTError(TAG, "startLiveTrack(): raised.", e);
			return null;
		}

		String urlEnd = CALTOPO_API_V1 + MapId + "/LiveTrack";
		CaltopoOp op = new CaltopoOp(onComplete);
		SendRequest(op, CtsMethod_t.POST, urlEnd, top, false);
		return op;
	}

	@NonNull
	public static CaltopoOp AddLiveTrackPoint(@NonNull String deviceId,
									   double lat, double lng, double eleMeters, @Nullable Consumer<CaltopoOp> onComplete) {
        if (null == MapId)
            throw new RuntimeException("AddLiveTrackPoint(): Map not specified - call OpenMap() first");

        String latStr = String.format(Locale.US, "%.7f", lat);
		String lngStr = String.format(Locale.US, "%.7f", lng);
		Long ele = (long)eleMeters;
		String url = "https://" + DomainAndPort + "/api/v1/position/report/DRONE?" +
				EncodeParm("id", deviceId) + "&" +
				EncodeParm("lat", latStr) + "&" +
				EncodeParm("lng", lngStr) + "&" +
				EncodeParm("elevation", ele.toString());

		CaltopoOp op = new CaltopoOp(onComplete);
		SendRequest(op, CtsMethod_t.GET, url, null, true);
		return op;
	}

} // end of CaltopoSession class spec.
