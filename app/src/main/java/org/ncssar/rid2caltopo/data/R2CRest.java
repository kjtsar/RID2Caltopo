package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import org.opendroneid.android.data.Util;

import java.net.NetworkInterface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * R2CPeer would have been a better name.  The purpose of this class is
 * to maintain the lifecycle of peer-to-peer network connectivity
 * between this R2C app and it's peers over the network.
 *
 * Test Cases:
 *  o For all test cases, need to track Round Trip Time (rtt) from when
 *    the waypoint is received until Caltopo confirms received.
 *  o Track rtt for all communications links (R2C->Caltopo, R2C->R2C)
 *  o Take off in one zone, land in another. Wait for track to finish,
 *    then head to third zone, wait for finish, then back to first.
 *  o Start a track in one zone, enter another drone, then shut down
 *    the owning R2C instance - verify the smooth transfer to other
 *    instance.
 *  o Starting and shutting down multiple R2C instances at the same
 *    time.
 ** FIXME:  When an RTC instance doesn't respond or a connection timeout occurs the
 *          pipe we're talking to it on will fail and we'll either look for another
 *          address to try (if we haven't yet talked to it), or if it was an established
 *          connection, we'll shut down that link and release any drones it might have
 *          had ownership over.
 */

public class R2CRest implements WsPipe.WsMsgListener {
    /** R2CRespEnum
     * The current status of a drone from shared R2C perspective.
     */
    public enum R2CRespEnum {
        unknown,             // idle or not yet evaluated.
        pending,             // discovering instance reviewing status with peers.
        okToPublishLocally,  // Owned by the local instance - ref OurDroneLiveTracks.
        forwardToClient,     // A peer instance owns it - ref. ClientRidMap.
        reevaluate,          // Previous owner has given it up, so it's free game.
    }
    private static final String TAG = "R2CRest";

    // similar to ClientIdMap, this maps remoteUUID to clients that are in the process of establishing their connections.
    private static final Hashtable<String, R2CRest>ActiveClients = new Hashtable<>(16);

    // maps remoteUUID to the client that we use to communicate with it.
    private static final Hashtable<String, R2CRest> ClientIdMap = new Hashtable<>(16);

    // maps remote id to the client that owns it:
    private static final Hashtable<String, R2CRest> ClientRidMap = new Hashtable<>(16);

    private static final Hashtable<String, Integer>OutstandingAcksByRid = new Hashtable<>(16);

    // maps the remote id of drones we own to their corresponding livetracks.
    private static final Hashtable<String, CaltopoLiveTrack> OurDroneLiveTracks = new Hashtable<>(16);

    // instance variables:
    private final ArrayList <CaltopoLiveTrack> liveTracksUsingThisPeer = new ArrayList<>(16);
    private static final JSONArray MyIpAddresses = new JSONArray();
    private static int R2CRestCount = 0; // track active instancess
    private JSONArray remoteIpAddrs; // may be more than one to choose from (cell, wireless)

    // Local RTTs are measurements of the rtt for messages that originate locally and are reported
    // from this endpoint.  Conversely, remote RTTs are measured and reported by our peer:
    private Util.SimpleMovingAverage localR2cRttAvgMsec = new Util.SimpleMovingAverage(10);
    private Util.SimpleMovingAverage remoteCtRttAvgMsec = new Util.SimpleMovingAverage(10);
    private Util.SimpleMovingAverage remoteR2cRttAvgMsec = new Util.SimpleMovingAverage(10);
    private String currentAttemptIpAddr;
    private String remoteIpAddr;     // null until we find one that works.
    private String remoteUUID;       // pulled from R2C Marker on Caltopo or from inbound conn.
    private int sendMsgCount = 0;
    private int recvMsgCount = 0;
    private R2CListener clientListener;
    private String peerName;
    private WsPipe wsPipe;

    /** The constructor for new inbound connections from
     */
    public R2CRest(@NonNull WsPipe wsPipe) {
        R2CRestCount++;
        CTDebug(TAG, "XYZZY: R2CRestCount: " + R2CRestCount);
        this.wsPipe = wsPipe;
        wsPipe.setNewMsgListener(this);
        // New pipe, but no other remote info - yet.  Need to wait for
        // hello before adding to ClientIdMap.
    }

    /** R2CRest
     * Local interface to a remote R2C Instance.
     *
     * RemoteR2cSpec comes from Caltopo Marker info and is of the form:
     * {
     *      ipaddrs:[{"ipaddr":"<ipaddr1>","intf":"intf"},...],
     *      name: "<deviceName>",
     *      lat: <lat>,
     *      lng: <lng>,
     *      id: <marker_uuid>
     * }
     */
    public R2CRest(@NonNull JSONObject remoteR2cSpec, @Nullable R2CListener listener)  {
        R2CRestCount++;
        CTDebug(TAG, "XYZZY: R2CRestCount: " + R2CRestCount);
        remoteIpAddrs = remoteR2cSpec.optJSONArray("ipaddrs");
        peerName = remoteR2cSpec.optString("name");
        remoteUUID = remoteR2cSpec.optString("id");
        ActiveClients.put(remoteUUID, this);
        clientListener = listener;
        R2CRest client = ClientIdMap.get(remoteUUID);
        if (null != client) {
            CTDebug(TAG, "R2CRest(): already found this client in my map - This can happen if remote connected to us first.");
            // In either case, this new instance is redundant and needs to shutdown.
            shutdown();
        } else {
            try {
                CTDebug(TAG, "Trying to connect to remote: " + remoteR2cSpec.toString(4));
            } catch (JSONException e) {
                CTError(TAG,"toString() raised: ", e);
            }
            tryConnect();
        }
    }

    // Returns a concise summary of this client's status:
    @NonNull
    public String stats() {
        return String.format(Locale.US, "tx:%d rx:%d rtt:%.3fs crtt:%.3fs",
                sendMsgCount, recvMsgCount, (double)localR2cRttAvgMsec.get() / 1000.0,
                (double)remoteCtRttAvgMsec.get() / 1000.0);
    }

    @NonNull
    public String getRemoteUUID() { return remoteUUID;}

    public void setClientListener(@Nullable R2CListener listener) {
        this.clientListener = listener;
    }

    @NonNull
    public static Hashtable<String, R2CRest>GetCloneOfPeerHashtable() {
        return (Hashtable<String, R2CRest>)ClientIdMap.clone();
    }

    public JSONObject errorResponsePayload(@NonNull String emsg, @NonNull JSONObject payload) {
        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        jo.put("type", "error");
        jo.put("diag", emsg);
        String payStr = payload.toString();
        CTError(TAG, emsg + ":\n" + payStr);
        return jo;
    }

    private void handleHello(@NonNull Integer seqnum, @NonNull JSONObject payload) {
        String id = payload.optString("my-id");
        if (id.isEmpty()) {
            wsPipe.sendResponse(seqnum, errorResponsePayload(
                    "handleHello(): missing required 'my-id' parameter.", payload));
            sendMsgCount++;
            return;
        }

        if (null != remoteUUID) {
            if (!id.equals(remoteUUID)) {
                CTError(TAG, String.format(Locale.US, "handleHello(): remoteUUID:'%s' doesn't match my expectation:'%s'.",
                        id, remoteUUID));
                return;
            }
            R2CRest client = ClientIdMap.get(remoteUUID);
            if (null != client) {
                wsPipe.sendResponse(seqnum, errorResponsePayload(
                        "handleHello(): We already have a connection. Bye.", payload));
                sendMsgCount++;
                shutdown();
                return;
            }
        }
        peerName = wsPipe.getPeerName();
        remoteUUID = id;
        ClientIdMap.put(remoteUUID, this);
        CaltopoClientMap.AddClient(this);
        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        JSONArray ja = new JSONArray();
        for (String rid : OurDroneLiveTracks.keySet()) ja.put(rid);
        jo.put("type", "hello-ack");
        jo.put("my-active-dronelist", ja);
        jo.put("ct-rtt", CaltopoLiveTrack.GetCaltopoRttInMsec());
        jo.put("my-id", CaltopoClientMap.GetMyUUID());
        wsPipe.sendResponse(seqnum, jo);
        sendMsgCount++;
    }

    @NonNull
    public String getPeerName() {
        if (null != peerName) return peerName;
        return wsPipe.getPeerName();
    }

    public void handleSeenAck(JSONObject payload) {
        long r2cRtt = payload.optLong("r2c-rtt");
        if (r2cRtt != 0) remoteR2cRttAvgMsec.next(r2cRtt);
    }

    /* When I'm client connecting to remote:
     */
    public void handleHelloAck(JSONObject payload) {
        remoteIpAddr = currentAttemptIpAddr; // found a working address for remote - yipee!!

        String id = payload.optString("my-id");
        if (id.isEmpty()) {
            CTError(TAG, "handleHelloAck(): missing required 'my-id' parameter: " + payload);
            return;
        }
        CTDebug(TAG, String.format(Locale.US,
                "handleHelloAck(): Connected to %s at %s", id, remoteIpAddr));
        if (null != remoteUUID) {
            if (!id.equals(remoteUUID)) {
                CTError(TAG, String.format(Locale.US, "handleHelloAck(): remoteUUID:'%s' doesn't match my expectation:'%s'.",
                        id, remoteUUID));
            }
        }
        remoteUUID = id;
        ClientIdMap.put(remoteUUID, this);  // now that client connection is real we can add it to our map.

        CaltopoClientMap.GetMyUUID();
        JSONArray remoteDroneList = payload.optJSONArray("my-active-dronelist");
        if (null == remoteDroneList) {
            CTError(TAG, "handleHelloAck() missing required 'my-active-dronelist' parameter");
            return;
        }
        String peerName = wsPipe.getPeerName();
        for (int i = 0; i < remoteDroneList.length(); i++) {
            try {
                String rid = (String)remoteDroneList.get(i);
                ClientRidMap.put(rid, this);
                CTDebug(TAG, String.format(Locale.US,
                        "handleHelloAck(): Added '%s' to the list of drones owned by %s", rid, peerName));
            } catch (JSONException e) {
                CTError(TAG, "get() raised.", e);
            }
        }
        if (null != clientListener) clientListener.clientStatusChange(this, true);
    }

    public void handleAddDroneAck(@NonNull JSONObject payload) {
        String rid = payload.optString("rid");
        if (rid.isEmpty()) {
            CTError(TAG, "handleAddDroneAck(): missing required 'rid' parameter." + payload);
            return;
        }
        int count = DecrementAckCountForRid(rid);
        CTDebug(TAG, String.format(Locale.US, "handleAddDroneAck(): Received ack for %s. Count is %d", rid, count));
        if (count <= 0) {

            // The last ack is in.  If we didn't receive a nack, it's ours.
            CaltopoLiveTrack liveTrack = CaltopoLiveTrack.GetLiveTrackForRemoteId(rid);
            R2CRest r2cClient = ClientRidMap.get(rid);
            CTDebug(TAG, String.format(Locale.US, "handleAddDroneAck(): Received last ack. liveTrack:%x, r2cClient:%x.",
                    System.identityHashCode(liveTrack), System.identityHashCode(r2cClient)));
            if (null == r2cClient) {
                OurDroneLiveTracks.put(rid, liveTrack);
                CTDebug(TAG, "handleAddDroneAck(): we have assumed ownership of " + rid);
                liveTrack.updateStatus(R2CRespEnum.okToPublishLocally);
            } else {
                liveTrack.updateStatus(R2CRespEnum.forwardToClient);
                CTDebug(TAG, String.format(Locale.US,
                        "handleAddDroneAck(): ownership of %s transferred to %s",
                        rid, r2cClient.getPeerName()));
            }
        } else {
            CTDebug(TAG, String.format(Locale.US, "handleAddDroneAck(): waiting for %d more ack", count));
        }
    }

    public void handleAddDroneNack(@NonNull JSONObject payload) {
        String rid = payload.optString("rid");
        if (rid.isEmpty()) {
            CTError(TAG, "handleAddDroneNack(): missing required 'rid' parameter." + payload);
            return;
        }
        DecrementAckCountForRid(rid);
        // At least one other client claims the rid, so mark accordingly
        ClientRidMap.putIfAbsent(rid, this);
        CaltopoLiveTrack liveTrack = CaltopoLiveTrack.GetLiveTrackForRemoteId(rid);
        liveTrack.updateStatus(R2CRespEnum.forwardToClient);
    }


    private static int IncrementAckCountForRid(@NonNull String remoteId) {
        Integer currentVal = OutstandingAcksByRid.get(remoteId);
        if (null == currentVal) currentVal = 0;
        currentVal++;
        OutstandingAcksByRid.put(remoteId, currentVal);
        return currentVal;
    }

    private static int DecrementAckCountForRid(String remoteId) {
        Integer currentVal = OutstandingAcksByRid.get(remoteId);
        if (null == currentVal || currentVal < 1) currentVal = 1;
        currentVal--;
        OutstandingAcksByRid.put(remoteId, currentVal);
        return currentVal;
    }


    public void handleDropDrone(@NonNull Integer seqnum, @NonNull JSONObject payload) {
        String rid = payload.optString("rid");
        ClientRidMap.remove(rid);
        CaltopoLiveTrack liveTrack = CaltopoLiveTrack.GetLiveTrackForRemoteId(rid);
        if (null != liveTrack) {
            liveTrack.updateStatus(R2CRespEnum.reevaluate);
        }

        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        jo.put("type", "drop-drone-ack");
        wsPipe.sendResponse(seqnum, jo);
        sendMsgCount++;
    }

    private void handleAddDrone(@NonNull Integer seqnum, @NonNull JSONObject payload) {
        String rid = payload.optString("rid");
        if (rid.isEmpty()) {
            wsPipe.sendResponse(seqnum, errorResponsePayload(
                    "handleAddDrone(): missing required 'rid' parameter.", payload));
            sendMsgCount++;
            return;
        }
        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        jo.put("rid", rid);
        CaltopoLiveTrack liveTrack = OurDroneLiveTracks.get(rid);
        if (null != liveTrack) {
            jo.put("type", "add-drone-nack");
            jo.put("note", "FIXME: This shouldn't happen: Already in my list of owned.");
            wsPipe.sendResponse(seqnum, jo);
            sendMsgCount++;
            return;
        }

        liveTrack = CaltopoLiveTrack.GetLiveTrackForRemoteId(rid);
        if (null == liveTrack) { // haven't seen it before.
            jo.put("type", "add-drone-ack");
            jo.put("note", "all yours bro.");
            ClientRidMap.put(rid, this);
        } else { // then also one we've seen and probably already published our own "add-drone"
            double lat = payload.optDouble("lat");
            double lng = payload.optDouble("lng");
            long ts = payload.optLong("drone-timestamp-ms");
            long fts = liveTrack.getFirstTimestamp();
            if (fts > 0 && fts < ts) {
                jo.put("type", "add-drone-nack");
                jo.put("note", String.format(Locale.US, "I have an earlier waypoint at %d vs your %d.", fts, ts));
                OurDroneLiveTracks.put(rid, liveTrack);
                // FIXME: If there are more than two R2Cs involved in this process, then
                //    we might end up with two instances "owning" & reporting the same drone.
                liveTrack.updateStatus(R2CRespEnum.okToPublishLocally);
            } else if (ts > 0 && ts < fts) {
                jo.put("type", "add-drone-ack");
                jo.put("note", String.format(Locale.US, "You have an earlier waypoint at %d vs mine %d.", ts, fts));
                ClientRidMap.put(rid, this);
                liveTrack.updateStatus(R2CRespEnum.forwardToClient);
            } else {
                // on to the tie breaker:
                double dfm = CaltopoClientMap.DistanceFromMeInMeters(lat, lng);
                double dfy = payload.optDouble("distance-from-me");
                if (dfm < dfy) {
                    jo.put("type", "add-drone-nack");
                    jo.put("note", String.format(Locale.US, "All else being equal, it's %.3fm closer to me.", dfy -dfm));
                    OurDroneLiveTracks.put(rid, liveTrack);
                    liveTrack.updateStatus(R2CRespEnum.okToPublishLocally);
                } else if (dfm > dfy) {
                    jo.put("type", "add-drone-ack");
                    jo.put("note", "Not closer to me.");
                    ClientRidMap.put(rid, this);
                    liveTrack.updateStatus(R2CRespEnum.forwardToClient);
                } else { // tie-breaker - always guaranteed to be different:
                    if (CaltopoClientMap.GetMyUUID().compareTo(remoteUUID) < 0) {
                        jo.put("type", "add-drone-nack");
                        jo.put("note", "My uuid is less than yours.");
                        OurDroneLiveTracks.put(rid, liveTrack);
                        liveTrack.updateStatus(R2CRespEnum.okToPublishLocally);
                    } else {
                        jo.put("type", "add-drone-ack");
                        jo.put("note", "My uuid is more than yours.");
                        ClientRidMap.put(rid, this);
                        liveTrack.updateStatus(R2CRespEnum.forwardToClient);
                    }
                }
            }
        }
        wsPipe.sendResponse(seqnum, jo);
        sendMsgCount++;
    }
    /** handleSeen()
     * FIXME: This is where we could make things really complicated.  Currently not doing
     * any reporting handoff to peers, but we could.  If one of our peers is consistently
     * seeing waypoints from our drone that we aren't _and_/or their ct_rtt is better
     * than our current ct_rtt + r2c_rtt combined, then it would make sense to delegate
     * reporting for this drone.
     */
    private void handleSeen(@NonNull Integer seqnum, @NonNull JSONObject payload) {
        String rid = payload.optString("rid");
        if (rid.isEmpty()) {
            wsPipe.sendResponse(seqnum, errorResponsePayload(
                    "handleSeen(): missing required 'rid' parameter.", payload));
            sendMsgCount++;
            return;
        }

        CaltopoLiveTrack liveTrack = OurDroneLiveTracks.get(rid);
        if (null == liveTrack) {
            wsPipe.sendResponse(seqnum, errorResponsePayload(
                    "handleSeen(): not my drone.", payload));
            sendMsgCount++;
            return;
        }
        CaltopoClient ctClient = liveTrack.getCaltopoClient();
        long ts = payload.optLong("ts");
        double lat = payload.optDouble("lat");
        double lng = payload.optDouble("lng");
        boolean archived = ctClient.newWaypoint(lat, lng, 0, ts, CtDroneSpec.TransportTypeEnum.R2C);
        long remoteCtRtt = payload.optLong("ct_rtt");
        if (remoteCtRtt > 0) remoteCtRttAvgMsec.next(remoteCtRtt);
        long remoteR2cRtt = payload.optLong("r2c_rtt");
        if (remoteR2cRtt > 0) remoteR2cRttAvgMsec.next(remoteR2cRtt);
        JSONObject retPayload = new JSONObject();
        try {
            retPayload.put("type", "seen-ack");
            retPayload.put("ct_rtt", CaltopoLiveTrack.GetCaltopoRttInMsec());
            retPayload.put("r2c_rtt", localR2cRttAvgMsec.get());
            retPayload.put("archived", archived);
        } catch (Exception e) {
            CTError(TAG, "put() raised.", e);
        }
        wsPipe.sendResponse(seqnum, retPayload);
        sendMsgCount++;
    }


    public void newInboundConnection(@NonNull WsPipe wsPipe) {
        CTDebug(TAG, "R2CRest: Received new inbound connection: " + System.identityHashCode(wsPipe));
        this.wsPipe = wsPipe;
    }

    public void pipeIsClosing(@NonNull WsPipe wsPipe) {
        if (null != remoteIpAddr) {  // then this is an established connection closing:
            CTError(TAG, "R2CRest: received pipeIsClosing().  Shutting down connection to " + wsPipe.getPeerName());
            this.wsPipe = null;
            shutdown();
        } else { // else we're just trying to establish a connection - see if we have another addr to try:
            tryConnect();
        }
    }

    private void handleLeaving(@NonNull Integer seqnum) {
        String peerName = wsPipe.getPeerName();
        ArrayList<String> ridList = new ArrayList<>();

        for (Map.Entry<String, R2CRest>map : ClientRidMap.entrySet()) {
            if (this.equals(map.getValue())) {
                ridList.add(map.getKey());
            }
        }

        for (String rid : ridList) {
            CTDebug(TAG, String.format(Locale.US,
                    "handleLeaving(): Removing '%s' from the list of drones owned by %s", rid, peerName));
            ClientRidMap.remove(rid);
        }
        this.shutdown();
        if (null != clientListener) {
            clientListener.clientStatusChange(this, false);
            clientListener = null;
        }
        // don't bother replying - remote is on it's way out and we're lucky to be notified.
    }

    public void inboundMessage(@NonNull WsPipe wsPipe, @NonNull Integer seqnum, @NonNull JSONObject payload) {
        recvMsgCount++;
        switch (payload.optString("type")) {
            case "hello": {
                handleHello(seqnum, payload);
            }
            case "leaving": handleLeaving(seqnum);
            case "add-drone": handleAddDrone(seqnum, payload);
            case "drop-drone": handleDropDrone(seqnum, payload);
            case "seen": handleSeen(seqnum, payload);
            default: {
                wsPipe.sendResponse(seqnum, errorResponsePayload(
                        "inboundMessage() missing supported type", payload));
                sendMsgCount++;
            }
        }
    }

    public void outboundResponse(@NonNull JSONObject payload, int tag, long rttInMsec) {
        String typeString = payload.optString("type");
        long remoteRtt = payload.optLong("ct-rtt", 0);
        if (0 != remoteRtt) remoteCtRttAvgMsec.next(remoteRtt);
        remoteRtt = payload.optLong("r2c-rtt", 0);
        if (0 != remoteRtt) remoteR2cRttAvgMsec.next(remoteRtt);
        localR2cRttAvgMsec.next(rttInMsec);
        CTDebug(TAG, String.format(Locale.US,
                "%s response received with rtt of %.3f seconds",
                typeString, (double)rttInMsec/1000.0));
        switch (typeString) {
            case "hello-ack": handleHelloAck(payload);
            case "seen-ack":  handleSeenAck(payload);
            case "add-drone-ack": handleAddDroneAck(payload);
            case "add-drone-nack": handleAddDroneNack(payload);
            case "drop-drone-ack": { /* ok to just ignore */}
            default: CTError(TAG, "received unexpected response from remote: " + payload);
        }
    }

    /** tryConnect()
     * Try to connect to a new peer.  We start with a list of ip addresses and try each
     * one in sequence until we find one that gets us our hello-ack.
     */
    private void tryConnect() {
        if (null != remoteIpAddrs) {
            JSONObject obj = (JSONObject) remoteIpAddrs.remove(0);
            if (null != obj) {
                String ipaddr = obj.optString("ipaddr");
                if (!ipaddr.isEmpty()) {
                    CTDebug(TAG, "tryConnect(): Trying to connect to: " + obj);
                    wsPipe = new WsPipe(ipaddr, this);
                    currentAttemptIpAddr = ipaddr;
                    sayHello();
                    return;
                }
            }
        }
        if (null == remoteIpAddrs || 0 == remoteIpAddrs.length()) {
            CTError(TAG, "tryConnect(): Not able to connect via any supplied address.");
            if (null != clientListener) {
                clientListener.clientStatusChange(this, false);
            } else {
                CaltopoClientMap.RemoveClient(this);
            }
        }
    }

    /**
     * Allows CaltopoLiveTrack instance to report a position update to a peer when it
     * sees one of it's drones.
     * @param droneSpec This is the data describing the drone that was seen.
     * @param lat Latitude of the last waypoint update received.
     * @param lng Longitude of the last waypoint update received.
     * @param droneTimestampInMsec timestamp of last update received.
     */
    public void reportSeen(CtDroneSpec droneSpec, double lat, double lng, long droneTimestampInMsec) throws RuntimeException {
        if (null == wsPipe) {
            CaltopoLiveTrack liveTrack = CaltopoLiveTrack.GetLiveTrackForRemoteId(droneSpec.getRemoteId());
            CTError(TAG, String.format(Locale.US, "reportSeen(): ignoring attempt to write on a closed pipe to %s.  Shutting down client connection.", peerName));
            shutdown();
            if (null != liveTrack) liveTrack.updateStatus(R2CRespEnum.reevaluate);
            return;
        }
        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        jo.put("type", "seen");
        jo.put("rid", droneSpec.getRemoteId());
        jo.put("lat", lat);
        jo.put("lng", lng);
        jo.put("ts", droneTimestampInMsec);
        jo.put("ct-rtt", CaltopoLiveTrack.GetCaltopoRttInMsec());
        jo.put("r2c-rtt", localR2cRttAvgMsec.get());
        wsPipe.sendMessage(jo, 0, false);
        sendMsgCount++;
    }

    public interface R2CListener {
        /** clientStatusChange()
         * Delivered when the R2C client instance establishes or looses
         * connectivity per the isNowUpFlag.   Note that when isNowUpFlag
         * is false, recipient should break all ties with this client as
         * it is closing for business.
         *
         * @param client
         * @param isNowUpFlag
         */
        void clientStatusChange(R2CRest client, boolean isNowUpFlag);
    }

    /** ClientForRemoteR2c() - invoked from CaltopoClientMap to establish
     * connection with a peer specified by a Marker in Caltopo.
     * Use that info to look up an existing connection or establish a new one:
     *
     * @param remoteR2cSpec JSON structure has the following form:{
     *      ipaddrs:[{"ipaddr":"<ipaddr1>","intf":"intf"},...],
     *      name: "<deviceName>",
     *      lat: <lat>,
     *      lng: <lng>,
     *      id: <marker_uuid>
     * }
     * @return  Returns a connection to specified peer.  Note that new
     *          connections may take a while to establish, so this will serve
     *          as a placeholder until then.   Use isConnected() to poll if the
     *          connection is established or specify a listener to get up/down
     *          notification as they occure.
     */
    @NonNull
    public static R2CRest ClientForRemoteR2c(@NonNull JSONObject remoteR2cSpec, @Nullable R2CListener listener) {
        R2CRest r2cRest = ClientIdMap.get(remoteR2cSpec.optString("id"));
        if (null == r2cRest) return new R2CRest(remoteR2cSpec, listener);
        return r2cRest;
    }

    public static void SendDropDrone(String remoteId) {
        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        OurDroneLiveTracks.remove(remoteId);
        jo.put("type","drop-drone");
        jo.put("rid", remoteId);
        jo.put("ct-rtt", CaltopoLiveTrack.GetCaltopoRttInMsec());
        for (Map.Entry<String,R2CRest> map : ClientIdMap.entrySet()) {
            R2CRest client = map.getValue();
            client.wsPipe.sendMessage(jo, 0, true);
            client.sendMsgCount++;
        }
    }

    /** Start here for initial lookup of a new drone.
     *  If the client isn't found in any of the maps, we need to make sure some other
     *  R2C instance hasn't adopted it first.
     *
     * @param droneSpec dronespec for the drone
     * @param lat latitude of the first reported waypoint
     * @param lng longitude of the first reported waypoint
     * @param droneTimestampInMsec timestamp from the drone's first reported waypoint.
     * @return Response indicates current status for the specified drone.
     */
    public static R2CRespEnum StatusForNewRemoteId(@NonNull CaltopoLiveTrack liveTrack, @NonNull CtDroneSpec droneSpec, double lat, double lng, long droneTimestampInMsec) {
        String remoteId = droneSpec.getRemoteId();
        if (ClientIdMap.isEmpty() && ActiveClients.isEmpty()) {
            OurDroneLiveTracks.put(remoteId, liveTrack);
            return R2CRespEnum.okToPublishLocally;
        }

        if (null != OurDroneLiveTracks.get(remoteId)) return R2CRespEnum.okToPublishLocally;

        R2CRest client = ClientRidMap.get(remoteId);
        if (null != client) {
            client.liveTracksUsingThisPeer.add(liveTrack);
            return R2CRespEnum.forwardToClient;
        }

        // This one is a new drone or old drone going active again,
        // so check with peers before claiming.  Note that we use current waypoint
        // lat,lng in calculating distance from us, but for comparing timestamps, I
        // want to compare the timestamps of the first waypoints each endpoint has
        // managed to collect.
        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        jo.put("type", "add-drone");
        jo.put("rid", droneSpec.getRemoteId());
        jo.put("drone-timestamp-ms", liveTrack.getFirstTimestamp());
        jo.put("lat", lat);
        jo.put("lng", lng);
        jo.put("distance-from-me", CaltopoClientMap.DistanceFromMeInMeters(lat, lng));
        jo.put("ct-rtt", CaltopoLiveTrack.GetCaltopoRttInMsec());

        String rid = droneSpec.getRemoteId();
        for (Map.Entry<String,R2CRest> map : ActiveClients.entrySet()) {
            client = map.getValue();
            IncrementAckCountForRid(rid);
            client.sendAddWithPayload(jo);
        }
        return R2CRespEnum.pending;
    }

    @Nullable public static R2CRest ClientForUUID(@Nullable String UUID) {
        if (null != UUID) return ActiveClients.get(UUID);
        return null;
    }

    private void sendAddWithPayload(JSONObject payload) {
        if (null == wsPipe && null != ActiveClients.get(remoteUUID)) {
            // We're still bringing up the connection.
            CTDebug(TAG, "sendAddWithPayload() pending: " + payload);
            DelayedExec.RunAfterDelayInMsec(() -> sendAddWithPayload(payload), 1000);
            return;
        } else if (null == wsPipe) shutdown();
        wsPipe.sendMessage(payload, 0, false);
        sendMsgCount++;
    }

    private void handleAddResponse(@NonNull JSONObject payload) {
        R2CRest client;
        CaltopoLiveTrack liveTrack;
        for (Map.Entry<String,R2CRest> map : ClientIdMap.entrySet()) {
            client = map.getValue();
        }
    }

    /** Find client for specified remote id.  This only returns a client
     *  if one has been assigned to the remote id.  This is not the way
     *  to find out if a client has been assigned - use StatusForRemoteId
     *  to find out if one has been assigned to a client first.
     *
     * @param remoteId remote id string
     * @return  Returns client handle if one exists, null otherwise.
     */
    @Nullable
    public static R2CRest ClientForRemoteId(@NonNull String remoteId) {
        return ClientRidMap.get(remoteId);
    }

    @Nullable
    public void sayHello() {
        if (0 != MyIpAddresses.length()) {
            Util.SafeJSONObject payload = new Util.SafeJSONObject();
            payload.put("type", "hello");
            payload.put("my-id", CaltopoClientMap.GetMyUUID());
            payload.put("my-addrs", MyIpAddresses);
            wsPipe.sendMessage(payload, 0, false);
            sendMsgCount++;
        }
    }

    static class ServerTemplate implements WsPipe.WsMsgListener {
        private static final String TAG = "ServerTemplate";
        private WsPipe wsPipe;

        /* N.B. We don't have the caller's remoteUUID yet, so we don't know
         * if we've already established an outgoing connection to them or not, so
         * Go ahead and create a new R2CRest instance.   Once we get the
         * remoteUUID and try to add it to ClientIdMap, that's when we'll figure
         * out the connection already exists and shutdown this one.
         */
        public void newInboundConnection(@NonNull WsPipe wsPipe) {
            CTDebug(TAG, "new inbound connection");
            new R2CRest(wsPipe);
        }
        public void pipeIsClosing(@NonNull WsPipe wsPipe) {
            CTError(TAG, "pipeIsClosing() in server template.");
            this.wsPipe = null;
        }
        public void inboundMessage(@NonNull WsPipe wsPipe, @NonNull Integer seqnum, @NonNull JSONObject payload) {
            CTError(TAG, "inboundMessage() to servers template not handled.");
        }
        public void outboundResponse(@NonNull JSONObject payload, int tag, long rttInMsec) {
            CTError(TAG, "outboundResponse() to servers template not handled.");
        }
    }
    public static void Init() {
        try {
            WsPipe.Init();
            ServerTemplate template = new ServerTemplate();
            WsPipe.StartServer(template);
        } catch (Exception e) {
            CTError(TAG, "Init(): WsPipe.StartServer() raised.", e);
        }
        GetMyIpAddresses();
    }

        /*
            KeyManagerFactory keyManagerFactory = null;
            KeyStore keyStore = null;
            try {
                SSLContext context = SSLContext.getDefault();
                SSLParameters sslParameters = context.getSupportedSSLParameters();
                CTDebug(TAG, "Supported TLS/SSL Application Protocols: " + Arrays.toString(sslParameters.getApplicationProtocols()));
                CTDebug(TAG,"Enabled TLS/SSL Protocols: " + Arrays.toString(sslParameters.getProtocols()));

                String storePassword = BuildConfig.STORE_PASSWORD;
                InputStream inputStream;
                Context appContext = DebugActivity.getAppContext();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    inputStream = appContext.getResources().openRawResource(R.raw.keystore);
                    keyStore = KeyStore.getInstance("PKCS12");
                    keyStore.load(inputStream, storePassword.toCharArray());
                    inputStream.close();
                    keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                    keyManagerFactory.init(keyStore, storePassword.toCharArray());
                    port = R2CRest.Server.HTTPS_PORT;
                    CTDebug(TAG, "HTTP.Server() listening for tls/ssl connections on port " + port);
                } else {
                    // Can't figure out how to get earlier versions of Android to load the keystore:
                    CTDebug(TAG, "HTTP.Server() listening for cleartext connections on port " + port);
                }
            } catch (Exception e) {
                CTError(TAG, "Init(): raised...", e);
            }

            Security.addProvider(new BouncyCastleProvider());
         */

    public static JSONArray GetMyIpAddresses() {
        HashMap<String,JSONObject> map = new HashMap<>();
        String key;
        if (0 != MyIpAddresses.length()) return MyIpAddresses;
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                String netName = networkInterface.getName();
                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress inetAddress = inetAddresses.nextElement();
                    String ipaddr = inetAddress.getHostAddress();
                    // Check for valid, non-loopback IPv4 address
                    if (inetAddress.isLoopbackAddress() || !(inetAddress instanceof java.net.Inet4Address)) continue;
                    key = netName + ":" + ipaddr;
                    JSONObject obj = new JSONObject();
                    obj.put("intf", netName);
                    obj.put("ipaddr", ipaddr);
                    map.put(key, obj);
                    CTDebug(TAG, "GetMyIpAddresses() found new address: " + "'" + key + "'" );
                }
            }
        } catch (Exception e) {
            CTError(TAG, "GetMyIpAddreses() raised. ", e);
        }

        // FIXME: If we find an address on a tun* interface, we want to move it to the
        //  front of the list, because it's likely to be our private connection to our peer.
        Set<String> keySet = map.keySet();
        String[] keyList = keySet.toArray(new String[0]);
        String[] remainderKeys = new String[keyList.length];
        int remainderCount = 0;
        for (int i = 0; i < keyList.length; i++) {
            key = keyList[i];
            if (key.startsWith("tun")) {
                MyIpAddresses.put(map.get(key));
            } else {
                remainderKeys[remainderCount++] = key;
            }
        }
        for (int i=0; i< remainderCount; i++) {
            key = remainderKeys[i];
            MyIpAddresses.put(map.get(key));
        }
        return MyIpAddresses;
    }

    public void shutdown() {
        if (null != wsPipe) {
            // then handling shutdown from this end - be polite and say goodbye.
            JSONObject jo = new JSONObject();
            try {
                jo.put("type", "leaving");
            } catch (Exception e) {
                CTError(TAG, "argh!", e);
            }
            wsPipe.sendMessage(jo, 0, true);
            sendMsgCount++;
            wsPipe = null;  // this indicates we're done with the pipe.
        }
        R2CRest r2cClient = ClientIdMap.get(remoteUUID);
        if (null != r2cClient && this != r2cClient) {
            CTError(TAG, "shutdown(): This shouldn't happen.");
            return;
        }
        /** FIXME: now the idea is to remove all references to this R2CRest instance
         *         so it can go away quietly and others can take over reporting.
         */
        ClientIdMap.remove(remoteUUID);
        ActiveClients.remove(remoteUUID);
        if (null != clientListener) {
            clientListener.clientStatusChange(this, false);
            clientListener = null;
        }
        String[] ridKeyArray = new String[ClientRidMap.size()];
        int i = 0;
        for (Map.Entry<String,R2CRest>ridEntry : ClientRidMap.entrySet()) {
            if (ridEntry.getValue() == this) ridKeyArray[i++] = ridEntry.getKey();
        }
        for (int j=0; j<i; j++) ClientRidMap.remove(ridKeyArray[j]);

        for (CaltopoLiveTrack liveTrack : liveTracksUsingThisPeer) {
            liveTrack.updateStatus(R2CRespEnum.reevaluate);
        }
    }

    public static void Shutdown() {
        Set <String> keys = ClientIdMap.keySet();
        String[] keyArray = keys.toArray(new String[0]);
        for (String key : keyArray) {
            // Try to notify each of my peers that I'm leaving the group.
            R2CRest client = ClientIdMap.get(key);
            if (null != client) client.shutdown();
        }
        WsPipe.Shutdown();
    }

    protected void finalize() {
        R2CRestCount--;
        CTDebug(TAG, "XYZZY: R2CRestCount: " + R2CRestCount);
    }

}
