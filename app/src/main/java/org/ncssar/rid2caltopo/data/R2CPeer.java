/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.ShowToast;

import org.ncssar.rid2caltopo.app.R2CActivity;
import org.ncssar.rid2caltopo.app.ScanningService;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The purpose of this class is to maintain the lifecycle of peer-to-peer
 * network connectivity between this R2C app and it's peers over the network.
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
 *  o Networking delays/blackouts (ala starlink w/limited visibility).
 */

public class R2CPeer implements WsPipe.WsMsgListener {
    /** R2CRespEnum
     * The current status of a drone from shared R2C perspective.
     */
    public enum R2CRespEnum {
        unknown,             // idle or not yet evaluated.
        pending,             // discovering - reviewing status with peers.
        okToPublishLocally,  // Owned by the local instance - ref OurDroneLiveTracks.
        forwardToPeer,       // A peer instance owns it - ref. PeerRidMap.
        reevaluate,          // Previous owner has given it up, so it's free game.
    }
    private static final String TAG = "R2CPeer";

    // similar to PeerIdMap, this maps remoteUUID to clients that are in the process of establishing their connections.
    private static final Hashtable<String, R2CPeer>ActivePeers = new Hashtable<>(16);

    // maps remoteUUID to the client that we use to communicate with it.
    private static final Hashtable<String, R2CPeer> PeerIdMap = new Hashtable<>(16);
    private static PeerListChangedListener peerListChangedListener;
    // maps remote id to the client that owns it:
    private static final Hashtable<String, R2CPeer> PeerRidMap = new Hashtable<>(16);

    private static final Hashtable<String, Integer>OutstandingAcksByRid = new Hashtable<>(16);

    // maps the remote id of drones we own to their corresponding livetracks.
    private static final Hashtable<String, CaltopoLiveTrack> OurDroneLiveTracks = new Hashtable<>(16);

    private static final DelayedExec StatusUpdatePoll = new DelayedExec();

    // instance variables:
    private final ArrayList <CaltopoLiveTrack> liveTracksUsingThisPeer = new ArrayList<>(16);
    private static final JSONArray MyIpAddresses = new JSONArray();
    private static int R2CPeerCount = 0; // track active instancess
    private final int R2CPeerId;
    private JSONArray remoteIpAddrs; // may be more than one to choose from (cell, wireless)

    // Local RTTs are measurements of the rtt for messages that originate locally and are reported
    // from this endpoint.  Conversely, remote RTTs are measured and reported by our peer:
    private final Util.SimpleMovingAverage localR2cRttAvgMsec = new Util.SimpleMovingAverage(10);
    private final Util.SimpleMovingAverage remoteCtRttAvgMsec = new Util.SimpleMovingAverage(10);
    private final Util.SimpleMovingAverage remoteR2cRttAvgMsec = new Util.SimpleMovingAverage(10);
    private String currentAttemptIpAddr;
    private String remoteIpAddr;     // null until we find one that works.
    private String remoteUUID;       // pulled from R2C Marker on Caltopo or from inbound conn.
    private int sendMsgCount = 0;
    private int recvMsgCount = 0;
    private R2CListener peerListener;
    private String peerName;
    public WsPipe wsPipe;
    private CtDroneSpec.DroneSpecsChangedListener remoteDroneSpecMonitor;
    public SimpleTimer remoteUptimeTimer = new SimpleTimer();
    public String remoteAppVersion = "<unknown>";
    private remoteUpdateListener remoteUpdateListener;

    // Table to map remoteIDs owned by this peer to their corresponding data.
    private final Hashtable<String, CtDroneSpec> droneSpecTable = new Hashtable<>(4);
    private boolean outstandingSeen = false;

    public interface remoteUpdateListener {
        void onRemoteAppConfig(String remoteAppVers);
        void onRemoteStartTime(long remoteStartTimeInMsec);
    }

    // FIXME: Is this even necessary?  We're calling CaltopoMap.Add/RemovePeer()
    //  directly, so this seems redundant.
    public interface R2CListener {
        enum r2cState {
            down,   // connection closing - for whatever reason.
            up,     // connection established - all is well.
            failed, // connection couldn't be established.
        }

        /** peerStatusChange()
         * Delivered when the R2CPeer instance establishes or looses
         * connectivity per the state parameter.   Note that when state is down or failed
         * recipient should break all ties with this peer as it is closing for business.
         * If status is failed, then peer connection couldn't be established.
         * @param peer affected r2cPeer.
         * @param state new state.
         */
        void peerStatusChange(R2CPeer peer, r2cState state);
    }


    public void setRemoteUpdateListener(remoteUpdateListener remoteUpdateListener) {
        this.remoteUpdateListener = remoteUpdateListener;
        if (null == remoteUpdateListener) return;
        if (null != remoteUptimeTimer) {
            remoteUpdateListener.onRemoteAppConfig(remoteAppVersion);
            remoteUpdateListener.onRemoteStartTime(remoteUptimeTimer.getStartTimeInMsec());
        } else {
            CTDebug(TAG, "setRemoteUpdateListener(): no remoteUptimeTimer.");
        }
    }

    public interface PeerListChangedListener {
        void onPeerListChanged(@NonNull List<R2CPeer> peerList);
    }

    public static void SetPeerListChangedListener(@NonNull PeerListChangedListener listener) {
        peerListChangedListener = listener;
    }

    /** The constructor for new inbound connections from
     */
    public R2CPeer(@NonNull WsPipe wsPipe) {
        R2CPeerCount++;
        R2CPeerId = R2CPeerCount;
        CTDebug(TAG, String.format(Locale.US, "R2CPeer(inbound) instance %d created.", R2CPeerId));
        this.wsPipe = wsPipe;
        wsPipe.setNewMsgListener(this);
        // New pipe, but no other remote info - yet.  Need to wait for
        // hello before adding to PeerIdMap.
    }

    /** R2CPeer
     * Local interface to a remote R2C Instance.
     * RemoteR2cSpec comes from Caltopo Marker info and is of the form:
     * {
     *      ipaddrs:[{"ipaddr":"<ipaddr1>","intf":"intf"},...],
     *      name: "<deviceName>",
     *      lat: <lat>,
     *      lng: <lng>,
     *      id: <marker_uuid>
     * }
     */
    public R2CPeer(@NonNull JSONObject remoteR2cSpec, @Nullable R2CListener listener)  {
        R2CPeerCount++;
        R2CPeerId = R2CPeerCount;
        CTDebug(TAG, String.format(Locale.US, "R2CPeer(outbound) instance %d created.", R2CPeerId));
        remoteIpAddrs = remoteR2cSpec.optJSONArray("ipaddrs");
        peerName = remoteR2cSpec.optString("name");
        remoteUUID = remoteR2cSpec.optString("id");
        ActivePeers.put(remoteUUID, this);
        peerListener = listener;
        R2CPeer peer = PeerIdMap.get(remoteUUID);
        if (null != peer) {
            CTDebug(TAG, "R2CPeer(): already found this peer in my map - This can happen if remote connected to us first.");
            // In either case, this new instance is redundant and needs to shutdown.
            shutdown(R2CListener.r2cState.down);
        } else {
            try {
                CTDebug(TAG, "Trying to connect to remote: " + remoteR2cSpec.toString(4));
            } catch (JSONException e) {
                CTError(TAG,"toString() raised: ", e);
            }
            tryConnect();
        }
    }

    // Returns a concise summary of this peer's status:
    @NonNull
    public String stats() {
        StringBuilder builder = new StringBuilder();
        for (CaltopoLiveTrack myTrack : OurDroneLiveTracks.values()) {
            builder.append("\n  ");
            builder.append(myTrack.getTrackLabel());
        }
        return String.format(Locale.US, "tx:%d rx:%d rtt:%.3fs crtt:%.3fs%s",
                sendMsgCount, recvMsgCount, (double)localR2cRttAvgMsec.get() / 1000.0,
                (double)CaltopoLiveTrack.GetCaltopoRttInMsec() / 1000.0, builder);
    }

    public static ArrayList<R2CPeer>GetPeerList() {
        ArrayList<R2CPeer> r2cPeers = new ArrayList<>(PeerIdMap.size());
        r2cPeers.addAll(PeerIdMap.values());
        return r2cPeers;
    }

    public void updateMappedId(@NonNull CtDroneSpec droneSpec, @NonNull String newId) {
        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        jo.put("remoteId", droneSpec.getRemoteId());
        jo.put("mappedId", newId);
        jo.put("type", "name-change");
        wsPipe.sendMessage(jo, 0, true);
    }

    public void handleNameChange(int seqnum, @NonNull JSONObject payload) {
        String rid = payload.optString("remoteId");
        String mid = payload.optString("mappedId");
        if (rid.isEmpty() || mid.isEmpty()) {
            wsPipe.sendResponse(seqnum, errorResponsePayload(
                    "handleNameChange(): missing required 'remoteId' or 'mappedId' parameter.", payload));
            sendMsgCount++;
            return;
        }
        CtDroneSpec ds = CaltopoClient.GetDroneSpec(rid);
        if (null != ds) ds.setMappedId(mid);
        try {
            payload.put("type", "name-change-ack");
        } catch (Exception e) {
            CTError(TAG, "put() raised", e);
        }
        wsPipe.sendResponse(seqnum, payload);
    }
    public void setRemoteDroneSpecMonitor(CtDroneSpec.DroneSpecsChangedListener remoteDroneSpecMonitor) {
        this.remoteDroneSpecMonitor = remoteDroneSpecMonitor;
    }

    @NonNull
    public ArrayList<CtDroneSpec> getRemoteDroneSpecs() {
        ArrayList<CtDroneSpec>droneSpecs = new ArrayList<>(droneSpecTable.size());
        droneSpecs.addAll(droneSpecTable.values());
        return droneSpecs;
    }


    @NonNull
    public String getRemoteUUID() { return remoteUUID;}

    @NonNull
    public static Hashtable<String, R2CPeer>GetCloneOfPeerHashtable() {
        return (Hashtable<String, R2CPeer>)PeerIdMap.clone();
    }

    /** kvStringResponsePayload()
     *    For the simple case where all we want to send is key/value pair strings.
     * @param keyValuePairs Array of string pairs.
     * @return Returns JSONObject as payload.
     */
    private JSONObject kvStringResponsePayload(@NonNull String[] keyValuePairs) {
        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        for (int i=0; i < keyValuePairs.length; i+=2) {
            String key = keyValuePairs[i];
            String value = keyValuePairs[i+1];
            jo.put(key, value);
        }
        return jo;
    }

    public JSONObject errorResponsePayload(@NonNull String emsg, @NonNull JSONObject payload) {
        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        jo.put("type", "error");
        jo.put("diag", emsg);
        String payStr = payload.toString();
        CTError(TAG, emsg + ":\n" + payStr);
        return jo;
    }

    private static void AddPeer(@NonNull R2CPeer peer) {
        PeerIdMap.put(peer.getRemoteUUID(), peer);
        CaltopoMap.AddPeer(peer);
        if (null != peerListChangedListener) {
            peerListChangedListener.onPeerListChanged(GetPeerList());
        }
        if (!StatusUpdatePoll.isRunning()) {
            StatusUpdatePoll.start(R2CPeer::PublishStatus, 15 * 1000, 15 * 1000);
        }
    }
    private static void RemovePeer(@NonNull R2CPeer peer) {
        String uuid = peer.getRemoteUUID();
        PeerIdMap.remove(uuid);
        ActivePeers.remove(uuid);
        CTDebug(TAG, String.format(Locale.US,
                "RemoveClient(%s-%s): ClientIdMap:%d, ActiveClients:%d", uuid,
                peer.getPeerName(), PeerIdMap.size(), ActivePeers.size()));
        for (R2CPeer aClient : ActivePeers.values()) {
            CTDebug(TAG, "RemoveClient(): client: " +
                    aClient.getPeerName() + "uuid:" + aClient.remoteUUID);
        }
        CaltopoMap.RemovePeer(peer, R2CListener.r2cState.down);
        if (null != peerListChangedListener) {
            peerListChangedListener.onPeerListChanged(GetPeerList());
        }
        if (PeerIdMap.isEmpty()) StatusUpdatePoll.stop();
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
            R2CPeer peer = PeerIdMap.get(remoteUUID);
            if (null != peer) {
                wsPipe.sendResponse(seqnum, errorResponsePayload(
                        "handleHello(): We already have a connection. Bye.", payload));
                sendMsgCount++;
                shutdown(R2CListener.r2cState.down);
                return;
            }
        }
        peerName = wsPipe.getPeerName();
        remoteUUID = id;
        AddPeer(this);
        remoteAppVersion = payload.optString("app-vers");
        remoteUptimeTimer.setStartTimeInMsec(payload.optLong("start-timestamp"));
        if (null != remoteUpdateListener) {
            remoteUpdateListener.onRemoteAppConfig(remoteAppVersion);
            long startTime = remoteUptimeTimer.getStartTimeInMsec();
            remoteUpdateListener.onRemoteStartTime(startTime);
            CTDebug(TAG, String.format(Locale.US, "handleHello(): startTime:%d, runTime:%s", startTime, remoteUptimeTimer.durationAsString()));
        } else {
            CTDebug(TAG, "handleHello(): no remoteUpdateListener.");
        }
        JSONArray activeDroneList = MyActiveDronelist();
        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        jo.put("type", "hello-ack");
        jo.put("my-active-dronelist", activeDroneList);
        jo.put("ct-rtt", CaltopoLiveTrack.GetCaltopoRttInMsec());
        jo.put("my-id", CaltopoMap.GetMyUUID());
        jo.put("app-vers", R2CActivity.getMyAppVersion());
        jo.put("start-timestamp", ScanningService.ScannerUptime.getStartTimeInMsec());

        wsPipe.sendResponse(seqnum, jo);
        sendMsgCount++;
    }

    public static void PublishStatus() {
        if (PeerIdMap.isEmpty()) return;  // nobody to send to.
        JSONArray myDroneArray = MyActiveDronelist();
        for (R2CPeer peer : PeerIdMap.values()) {
            Util.SafeJSONObject jo = new Util.SafeJSONObject();
            jo.put("type", "drone-status");
            jo.put("my-active-dronelist", myDroneArray);
            peer.wsPipe.sendMessage(jo, 0, true);
        }
        if (myDroneArray.length() == 0) StatusUpdatePoll.stop();
    }

    public void addLocallyOwnedDrone(@NonNull String rid, @NonNull CaltopoLiveTrack liveTrack) {
        CTDebug(TAG, String.format(Locale.US,
                "Adding locally owned drone '%s' with peer:'%s'", rid,
                getPeerName()));
        OurDroneLiveTracks.put(rid, liveTrack);
        liveTrack.updateStatus(R2CRespEnum.okToPublishLocally);
        if (!StatusUpdatePoll.isRunning()) {
            StatusUpdatePoll.start(R2CPeer::PublishStatus, 15 * 1000, 15 * 1000);
        }
    }

    public void handleStatus(@NonNull Integer seqnum, @NonNull JSONObject payload) {
        JSONArray remoteDroneList = payload.optJSONArray("my-active-dronelist");
        if (null == remoteDroneList) {
            CTError(TAG, "handleStatus() missing required 'my-active-dronelist' parameter");
            return;
        }
        for (int i = 0; i < remoteDroneList.length(); i++) {
            try {
                Object o = remoteDroneList.get(i);
                CtDroneSpec ds;
                if (o instanceof JSONObject) {
                    ds = new CtDroneSpec((JSONObject) o);
                } else {
                    ds = new CtDroneSpec((String) o);
                }
                addDroneSpecForOurPeer(ds);
            } catch (JSONException e) {
                CTError(TAG, "get() raised.", e);
            }
        }
        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        jo.put("type", "drone-status-ack");
        updateDroneSpecListener();
        wsPipe.sendResponse(seqnum, jo);
        sendMsgCount++;
    }

    public static JSONArray MyActiveDronelist() {
                JSONArray ja = new JSONArray();
        for (String rid : OurDroneLiveTracks.keySet()) {
            CtDroneSpec ds = CaltopoClient.GetDroneSpec(rid);
            if (null != ds)
                ja.put(ds.asJSONObject());
            else
                ja.put(rid);  // Should not be able to get this far and not have a corresponding ds.
        }
        return ja;
    }

    @NonNull
    public String getPeerName() {
        if (null != peerName) return peerName;
        return wsPipe.getPeerName();
    }

    public void handleSeenAck(JSONObject payload) {
        outstandingSeen = false;
        long r2cRtt = payload.optLong("r2c-rtt");
        if (r2cRtt != 0) remoteR2cRttAvgMsec.next(r2cRtt);
    }

    /** handleSeenNack()
     *  This could happen when the peer we previously thought of as owner is not actually
     *  the owner of the drone.  That could mean there is another owner that we didn't
     *  see or network delay prevented us from seeing the "drop-drone" before we had
     *  already sent the corresponding "seen".   In either case, we need to stop sending
     *  "seen" updates and treat this like a "drop-drone" to remove this peer as the
     *  owner and permit the discovery process to restart.
     * @param payload payload for "seen-ack".
     */
    public void handleSeenNack(JSONObject payload) {
        String ridString = payload.optString("rid");
        if (ridString.isEmpty()) {
            CTError(TAG, "handleSeenNack(): Missing/invalid 'rid'");
            return;
        }
        removeDroneSpecFromOurPeer(ridString);
    }

    /* When I'm connecting to remote:
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
        AddPeer(this);

        CaltopoMap.GetMyUUID();
        JSONArray remoteDroneList = payload.optJSONArray("my-active-dronelist");
        if (null == remoteDroneList) {
            CTError(TAG, "handleHelloAck() missing required 'my-active-dronelist' parameter");
            return;
        }
        String peerName = wsPipe.getPeerName();
        for (int i = 0; i < remoteDroneList.length(); i++) {
            try {
                Object o = remoteDroneList.get(i);
                CtDroneSpec ds;
                if (o instanceof JSONObject) {
                    ds = new CtDroneSpec((JSONObject) o);
                } else {
                    ds = new CtDroneSpec((String) o);
                }
                String rid = ds.getRemoteId();
                addDroneSpecForOurPeer(ds);
                CTDebug(TAG, String.format(Locale.US,
                        "handleHelloAck(): Added '%s' to the list of drones owned by %s", rid, peerName));
            } catch (JSONException e) {
                CTError(TAG, "get() raised.", e);
            }
        }
        remoteAppVersion = payload.optString("app-vers");
        remoteUptimeTimer.setStartTimeInMsec(payload.optLong("start-timestamp"));
        if (null != remoteUpdateListener) {
            remoteUpdateListener.onRemoteAppConfig(remoteAppVersion);
            long startTime = remoteUptimeTimer.getStartTimeInMsec();
            remoteUpdateListener.onRemoteStartTime(startTime);
            CTDebug(TAG, String.format(Locale.US, "handleHelloAck(): startTime:%d, runTime:%s", startTime, remoteUptimeTimer.durationAsString()));
        } else {
            CTDebug(TAG, "handleHelloAck(): no remoteUpdateListener.");
        }
        if (null != peerListener) {
            peerListener.peerStatusChange(this, R2CListener.r2cState.up);
        } else {
            CaltopoLiveTrack.ReevalUnknownAndPendingTracks();
        }
        updateDroneSpecListener();
    }

    public void removeDroneSpecFromOurPeer(@NonNull String rid) {
        CtDroneSpec ds = droneSpecTable.remove(rid);
        if (null != ds) CaltopoClient.RemoveDroneSpecOwner(ds);
        PeerRidMap.remove(rid);
        CTDebug(TAG, String.format(Locale.US,
                "removeDroneSpecForOurPeer(): Removed '%s' from the list of drones owned by %s", rid, peerName));
        updateDroneSpecListener();
        CaltopoLiveTrack liveTrack = CaltopoLiveTrack.GetLiveTrackForRemoteId(rid);
        if (null != liveTrack) {
            // We are tracking this drone as well, so now that it is back on the market,
            // we should reevaluate ownership.
            // FIXME: if NewTrackDelays are different between this R2C and our peer, then
            //        we could be more lenient with our time between waypoints... Otherwise
            //        this path shouldn't be very easy to hit as we should probably time-
            //        out about the same time or earlier.
            liveTrack.updateStatus(R2CRespEnum.reevaluate);
        }
    }

    /* Peer has received ownership of this drone, so if it changes the MappedId, we
     * will change our mappedId to match.
     */
    public void addDroneSpecForOurPeer(@NonNull CtDroneSpec dsIn) {
        String rid = dsIn.getRemoteId();
        R2CPeer peer = PeerRidMap.get(rid);
        if (null == peer) {
            CTDebug(TAG, String.format(Locale.US,
                    "addDroneSpecForOurPeer(): Added '%s' to the list of drones owned by %s", rid, peerName));
            CaltopoClient.SetDroneSpecOwner(dsIn, this);
            PeerRidMap.put(rid, this);
        }
        if (dsIn.getMappedId().equals(dsIn.getRemoteId())) {
            CtDroneSpec ds = CaltopoClient.GetDroneSpec(rid);
            if (null != ds && !ds.getMappedId().equals(ds.getRemoteId())) dsIn = ds;
        }
        droneSpecTable.put(rid, dsIn);
    }

    public void updateDroneSpecListener() {
        if (null != remoteDroneSpecMonitor) {
            ArrayList<CtDroneSpec> dss = new ArrayList<>(droneSpecTable.size());
            dss.addAll(droneSpecTable.values());
            remoteDroneSpecMonitor.onDroneSpecsChanged(dss);
        }
    }

    public void handleAddDroneAck(@NonNull JSONObject payload) {
        CtDroneSpec ds = droneSpecFromRidString(payload.optString("rid"));
        if (null == ds) {
            CTError(TAG, "handleAddDroneAck(): missing/invalid required 'rid' parameter." + payload);
            return;
        }
        String rid = ds.getRemoteId();
        int count = DecrementAckCountForRid(rid);
        CTDebug(TAG, String.format(Locale.US, "handleAddDroneAck(): Received ack for %s. Count is %d", rid, count));
        if (count <= 0) {
            // The last ack is in.  If we didn't receive a nack, it's ours.
            CaltopoLiveTrack liveTrack = CaltopoLiveTrack.GetLiveTrackForRemoteId(rid);
            R2CPeer r2cPeer = PeerRidMap.get(rid);
            CTDebug(TAG, String.format(Locale.US, "handleAddDroneAck(): Received last ack. liveTrack:%x, r2cPeer:%x.",
                    System.identityHashCode(liveTrack), System.identityHashCode(r2cPeer)));
            if (null == r2cPeer) {
                CTDebug(TAG, "handleAddDroneAck(): we have assumed ownership of " + rid);
                addLocallyOwnedDrone(rid, liveTrack);
            } else {
                liveTrack.updateStatus(R2CRespEnum.forwardToPeer);
                CTDebug(TAG, String.format(Locale.US,
                        "handleAddDroneAck(): ownership of %s transferred to %s",
                        rid, r2cPeer.getPeerName()));
            }
        } else {
            CTDebug(TAG, String.format(Locale.US, "handleAddDroneAck(): waiting for %d more ack", count));
        }
    }

    /** droneSpecFromRidString():
     *   With the latest release, the "rid" parameter of a message can either contain
     *   the plain Remote Identifier or it can contain a JSON dronespec archive.
     *   Handle either.
     *
     * @param ridString Either a JSON archive of a dronespec or just a remote id.
     * @return  Returns a dronespec on success and null on failure.
     */
    @Nullable
    private CtDroneSpec droneSpecFromRidString(@NonNull String ridString) {
        CtDroneSpec ds;
        if (ridString.startsWith("{")) {
            try {
                ds = new CtDroneSpec(new JSONObject(ridString));
            } catch (Exception e) {
                CTError(TAG, "Not able to parse " + ridString, e);
                ds = null;
            }
        } else {
            ds = new CtDroneSpec(ridString);
        }
        return ds;
    }

    public void handleAddDroneNack(@NonNull JSONObject payload) {
        CtDroneSpec ds = droneSpecFromRidString(payload.optString("rid"));
        if (null == ds) {
            CTError(TAG, "handleAddDroneNack(): missing/invalid required 'rid' parameter." + payload);
            return;
        }

        String remoteId = ds.getRemoteId();
        DecrementAckCountForRid(remoteId);
        // At least one other peer claims the rid, so mark accordingly
        addDroneSpecForOurPeer(ds);
        CaltopoLiveTrack liveTrack = CaltopoLiveTrack.GetLiveTrackForRemoteId(remoteId);
        if (null != liveTrack) liveTrack.updateStatus(R2CRespEnum.forwardToPeer);
        updateDroneSpecListener();
    }

    public String getRttString() {
        return String.format(Locale.US, "%.3f",
                (double)(localR2cRttAvgMsec.get())/1000.0);
    }

    public String getRemoteCtRttString() {
        return String.format(Locale.US, "%.3f",
                (double)(remoteCtRttAvgMsec.get())/1000.0);
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

    /** We receive this message when a peer that currently owns a drone
     *  determines that the drone has stopped transmitting waypoints or,
     *  if super generous, is disconnecting from the hive and shutting
     *  down.
     *
     * @param seqnum  // inelegant at best, this is required to identify the response.
     * @param payload // incoming message payload.
     */
    public void handleDropDrone(@NonNull Integer seqnum, @NonNull JSONObject payload) {
        String remoteId = payload.optString("rid");
        if (remoteId.isEmpty()) {
            CTError(TAG, "handleAddDroneNack(): missing/invalid required 'rid' parameter." + payload);
            return;
        }
        removeDroneSpecFromOurPeer(remoteId);

        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        jo.put("type", "drop-drone-ack");
        wsPipe.sendResponse(seqnum, jo);
        sendMsgCount++;
    }

    private void handleAddDrone(@NonNull Integer seqnum, @NonNull JSONObject payload) {
        CtDroneSpec ds = droneSpecFromRidString(payload.optString("rid"));
        if (null == ds) {
            CTError(TAG, "handleAddDrone(): missing/invalid required 'rid' parameter." + payload);
            return;
        }
        String rid = ds.getRemoteId();
        String dsString = ds.asJSONObject().toString();
        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        CaltopoLiveTrack liveTrack = OurDroneLiveTracks.get(rid);
        if (null != liveTrack) {
            // possibly due to a race condition on start or some sort of communications hickup.
            jo.put("type", "add-drone-nack");
            jo.put("note", "FIXME: This shouldn't happen: Already in my list of owned.");
            jo.put("rid", dsString);
            wsPipe.sendResponse(seqnum, jo);
            sendMsgCount++;
            return;
        }

        liveTrack = CaltopoLiveTrack.GetLiveTrackForRemoteId(rid);
        if (null == liveTrack) { // haven't seen it before.
            jo.put("type", "add-drone-ack");
            jo.put("note", "all yours bro.");
            jo.put("rid", dsString);
            addDroneSpecForOurPeer(ds);
        } else { // then also one we've seen and probably already published our own "add-drone"
            double lat = payload.optDouble("lat");
            double lng = payload.optDouble("lng");
            long ts = payload.optLong("drone-timestamp-ms");
            long fts = liveTrack.getFirstTimestamp();
            if (fts > 0 && fts < ts) {
                jo.put("type", "add-drone-nack");
                jo.put("note", String.format(Locale.US, "I have an earlier waypoint at %d vs your %d.", fts, ts));
                jo.put("rid", dsString);
                addLocallyOwnedDrone(rid, liveTrack);
                // FIXME: If there are more than two R2Cs involved in this process, then
                //    we might end up with two instances "owning" & reporting the same drone- at least for a while.
            } else if (ts > 0 && ts < fts) {
                jo.put("type", "add-drone-ack");
                jo.put("note", String.format(Locale.US, "You have an earlier waypoint at %d vs mine %d.", ts, fts));
                jo.put("rid", dsString);
                liveTrack.updateStatus(R2CRespEnum.forwardToPeer);
                addDroneSpecForOurPeer(ds);
            } else {
                // on to the tie breaker:
                double dfm = CaltopoMap.DistanceFromMeInMeters(lat, lng);
                double dfy = payload.optDouble("distance-from-me");
                if (dfm < dfy) {
                    jo.put("type", "add-drone-nack");
                    jo.put("note", String.format(Locale.US, "All else being equal, it's %.3fm closer to me.", dfy -dfm));
                    jo.put("rid", ds.asJSONObject());
                    addLocallyOwnedDrone(rid, liveTrack);
                } else if (dfm > dfy) {
                    jo.put("type", "add-drone-ack");
                    jo.put("note", "Not closer to me.");
                    jo.put("rid", dsString);
                    liveTrack.updateStatus(R2CRespEnum.forwardToPeer);
                    addDroneSpecForOurPeer(ds);
                } else { // tie-breaker - always guaranteed to be different:
                    if (CaltopoMap.GetMyUUID().compareTo(remoteUUID) < 0) {
                        jo.put("type", "add-drone-nack");
                        jo.put("note", "My uuid is less than yours.");
                        jo.put("rid", dsString);
                        addLocallyOwnedDrone(rid, liveTrack);
                    } else {
                        jo.put("type", "add-drone-ack");
                        jo.put("note", "My uuid is greater than yours.");
                        jo.put("rid", dsString);
                        liveTrack.updateStatus(R2CRespEnum.forwardToPeer);
                        addDroneSpecForOurPeer(ds);
                    }
                }
            }
        }
        updateDroneSpecListener();
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
        CtDroneSpec ds = droneSpecFromRidString(payload.optString("rid"));
        if (null == ds) {
            wsPipe.sendResponse(seqnum, errorResponsePayload(
                    "handleSeen(): missing required 'rid' parameter.", payload));
            sendMsgCount++;
            return;
        }
        long remoteCtRtt = payload.optLong("ct_rtt");
        if (remoteCtRtt > 0) remoteCtRttAvgMsec.next(remoteCtRtt);
        long remoteR2cRtt = payload.optLong("r2c_rtt");
        if (remoteR2cRtt > 0) remoteR2cRttAvgMsec.next(remoteR2cRtt);

        String rid = ds.getRemoteId();
        CaltopoLiveTrack liveTrack = OurDroneLiveTracks.get(rid);
        if (null == liveTrack) {
            String[] kvPayload =  {"type", "seen-nack", "msg", "not my drone", "rid", rid};
            wsPipe.sendResponse(seqnum, kvStringResponsePayload(kvPayload));
            sendMsgCount++;
            return;
        }
        CaltopoClient ctClient = CaltopoClient.ClientForRemoteId(rid);
        long ts = payload.optLong("ts");
        double lat = payload.optDouble("lat");
        double lng = payload.optDouble("lng");
        long altitude = payload.optLong("alt");
        boolean archived = ctClient.newWaypoint(lat, lng, altitude, ts, CtDroneSpec.TransportTypeEnum.R2C, null);

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
        CTDebug(TAG, "R2CPeer: Received new inbound connection: " + System.identityHashCode(wsPipe));
        this.wsPipe = wsPipe;
    }

    public void pipeIsClosing(@NonNull WsPipe wsPipe) {
        if (null != remoteIpAddr) {  // then this is an established connection closing:
            CTError(TAG, "R2CPeer: received pipeIsClosing().  Shutting down connection to " + wsPipe.getPeerName());
            shutdown(R2CListener.r2cState.down);
        } else { // else we're just trying to establish a connection - see if we have another addr to try:
            tryConnect();
        }
    }

    private void handleLeaving(@NonNull Integer seqnum) {
        String peerName = wsPipe.getPeerName();
        ArrayList<String> ridList = new ArrayList<>();

        for (Map.Entry<String, R2CPeer>map : PeerRidMap.entrySet()) {
            if (this.equals(map.getValue())) {
                ridList.add(map.getKey());
            }
        }

        for (String rid : ridList) {
            CTDebug(TAG, String.format(Locale.US,
                    "handleLeaving(): Removing '%s' from the list of drones owned by %s", rid, peerName));
            PeerRidMap.remove(rid);
        }
        this.shutdown(R2CListener.r2cState.down);
        // don't bother replying - remote is on it's way out and we're lucky to be notified.
    }

    public void inboundMessage(@NonNull WsPipe wsPipe, @NonNull Integer seqnum, @NonNull JSONObject payload) {
        recvMsgCount++;
        switch (payload.optString("type")) {
            case "hello": handleHello(seqnum, payload); break;
            case "leaving": handleLeaving(seqnum); break;
            case "add-drone": handleAddDrone(seqnum, payload); break;
            case "drop-drone": handleDropDrone(seqnum, payload); break;
            case "name-change": handleNameChange(seqnum, payload); break;
            case "seen": handleSeen(seqnum, payload); break;
            case "drone-status": handleStatus(seqnum, payload); break;
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
            case "hello-ack": handleHelloAck(payload); break;
            case "seen-ack":  handleSeenAck(payload); break;
            case "seen-nack":  handleSeenNack(payload); break;
            case "add-drone-ack": handleAddDroneAck(payload); break;
            case "add-drone-nack": handleAddDroneNack(payload); break;
            case "name-change-ack":
            case "drone-status-ack":
            case "drop-drone-ack": break; /* ok to just ignore */
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
                    CTDebug(TAG, String.format(Locale.US, "tryConnect(%s): Trying to connect via: '%s'", peerName, obj));
                    wsPipe = new WsPipe(ipaddr, this);
                    currentAttemptIpAddr = ipaddr;
                    sayHello();
                    return;
                }
            }
        }
        if ((null == remoteIpAddrs || 0 == remoteIpAddrs.length()) && (null == remoteIpAddr)) {
            CTError(TAG, String.format(Locale.US,
                    "tryConnect(%s): Not able to connect via any supplied address.", peerName));
            this.shutdown(R2CListener.r2cState.failed);
        }
    }

    /**
     * Allows CaltopoLiveTrack instance to report a position update to a peer when it
     * sees one of it's drones.   Timing is of the essence to be useful, so if we
     * haven't received an ack for the last "seen", then discard this update.
     *
     * @param droneSpec This is the data describing the drone that was seen.
     * @param lat Latitude of the last waypoint update received.
     * @param lng Longitude of the last waypoint update received.
     * @param droneTimestampInMsec timestamp of last update received.
     */
    public void reportSeen(CtDroneSpec droneSpec, double lat, double lng, long altitudeInMeters, long droneTimestampInMsec) throws RuntimeException {
        if (outstandingSeen) return;
        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        jo.put("type", "seen");
        jo.put("ct-rtt", CaltopoLiveTrack.GetCaltopoRttInMsec());
        jo.put("r2c-rtt", localR2cRttAvgMsec.get());
        jo.put("rid", droneSpec.getRemoteId());
        jo.put("lat", lat);
        jo.put("lng", lng);
        jo.put("alt", altitudeInMeters);
        jo.put("ts", droneTimestampInMsec);
        wsPipe.sendMessage(jo, 0, false);
        outstandingSeen = true;
        sendMsgCount++;

    }

    /** PeerForRemoteR2c() - invoked from CaltopoMap to establish
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
    public static R2CPeer PeerForRemoteR2c(@NonNull JSONObject remoteR2cSpec, @Nullable R2CListener listener) {
        R2CPeer R2CPeer = PeerIdMap.get(remoteR2cSpec.optString("id"));
        if (null == R2CPeer) return new R2CPeer(remoteR2cSpec, listener);
        return R2CPeer;
    }

    public static void SendDropDrone(String remoteId) {

        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        OurDroneLiveTracks.remove(remoteId);
        jo.put("type","drop-drone");
        jo.put("rid", remoteId);
        jo.put("ct-rtt", CaltopoLiveTrack.GetCaltopoRttInMsec());
        for (R2CPeer peer : PeerIdMap.values()) {
            peer.wsPipe.sendMessage(jo, 0, true);
            peer.sendMsgCount++;
        }
    }

    public static void AddDroneForLiveTrack(@NonNull CtDroneSpec droneSpec, @NonNull CaltopoLiveTrack liveTrack)
    {
        // This one is a new drone or old drone going active again, so check with
        // peers before claiming.  Note that we use current waypoint lat,lng in
        // calculating distance from us, but for comparing timestamps, we want to
        // compare the timestamps of the first waypoints each endpoint has
        // managed to collect.

        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        jo.put("type", "add-drone");
        jo.put("rid", droneSpec.getRemoteId());
        jo.put("drone-timestamp-ms", liveTrack.getFirstTimestamp());
        jo.put("lat", droneSpec.lastLat);
        jo.put("lng", droneSpec.lastLng);
        jo.put("distance-from-me", CaltopoMap.DistanceFromMeInMeters(droneSpec.lastLat, droneSpec.lastLng));
        jo.put("ct-rtt", CaltopoLiveTrack.GetCaltopoRttInMsec());

        String rid = droneSpec.getRemoteId();
        for (R2CPeer peer : ActivePeers.values()) {
            IncrementAckCountForRid(rid);
            peer.sendAddWithPayload(jo);
        }
    }

    /** Start here for initial lookup of a new drone.
     *  Assumption is that all R2CInstances that are known have been instantiated,
     *  though may not yet have established connectivity.
     *  If the peer isn't found in any of the existing maps, we need to make
     *  sure some other R2C instance hasn't adopted it first.
     *
     * @param droneSpec our dronespec for the drone
     * @return Response indicates current status for the specified drone.
     */
    public static R2CRespEnum StatusForNewRemoteId(@NonNull CaltopoLiveTrack liveTrack, @NonNull CtDroneSpec droneSpec) {
        String remoteId = droneSpec.getRemoteId();
        R2CRespEnum status;

        if (PeerIdMap.isEmpty() && ActivePeers.isEmpty()) {
            OurDroneLiveTracks.put(remoteId, liveTrack);
            status = R2CRespEnum.okToPublishLocally;
        } else if (null != OurDroneLiveTracks.get(remoteId)) {
            status = R2CRespEnum.okToPublishLocally;
        } else {
            R2CPeer peer = PeerRidMap.get(remoteId);
            if (null != peer) {
                peer.liveTracksUsingThisPeer.add(liveTrack);
                status = R2CRespEnum.forwardToPeer;
            } else {
                AddDroneForLiveTrack(droneSpec, liveTrack);
                status = R2CRespEnum.pending;
            }
        }
        CTDebug(TAG, String.format(Locale.US,
                "StatusForNewRemoteId(): peerMap:%d, ActiveClients:%d, status:%s",
                PeerIdMap.size(), ActivePeers.size(), status));
        return status;
    }

    private void sendAddWithPayload(JSONObject payload) {
        if (null == wsPipe && null != ActivePeers.get(remoteUUID)) {
            // We're still bringing up the connection.
            CTDebug(TAG, "sendAddWithPayload() pending: " + payload);
            DelayedExec.RunAfterDelayInMsec(() -> sendAddWithPayload(payload), 1000);
            return;
        }
        wsPipe.sendMessage(payload, 0, false);
        sendMsgCount++;
    }

    /** Find peer for specified remote id.  This only returns a peer
     *  if one has been assigned to the remote id.  This is not the way
     *  to find out if a peer has been assigned - use StatusForRemoteId
     *  to find out if one has been assigned to a peer first.
     *
     * @param remoteId remote id string
     * @return  Returns peer if one exists, null otherwise.
     */
    @Nullable
    public static R2CPeer PeerForRemoteId(@NonNull String remoteId) {
        return PeerRidMap.get(remoteId);
    }

    public void sayHello() {
        if (0 != MyIpAddresses.length()) {
            Util.SafeJSONObject payload = new Util.SafeJSONObject();
            payload.put("type", "hello");
            payload.put("my-id", CaltopoMap.GetMyUUID());
            payload.put("my-addrs", MyIpAddresses);
            payload.put("app-vers", R2CActivity.getMyAppVersion());
            payload.put("start-timestamp", ScanningService.ScannerUptime.getStartTimeInMsec());
            wsPipe.sendMessage(payload, 0, false);
            sendMsgCount++;
        }
    }

    static class ServerTemplate implements WsPipe.WsMsgListener {
        private static final String TAG = "ServerTemplate";

        /* N.B. We don't have the caller's remoteUUID yet, so we don't know
         * if we've already established an outgoing connection to them or not, so
         * Go ahead and create a new R2CPeer instance.   Once we get the
         * remoteUUID and try to add it to PeerIdMap, that's when we'll figure
         * out the connection already exists and shutdown this one if it does.
         */
        public void newInboundConnection(@NonNull WsPipe wsPipe) {
            CTDebug(TAG, "new inbound connection");
            new R2CPeer(wsPipe);
        }

        public void pipeIsClosing(@NonNull WsPipe wsPipe) {
            CTError(TAG, "pipeIsClosing() in server template.");
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

    @NonNull
    public static String GetMyIpAddress(boolean tunnelFlag) {
        JSONArray addresses = GetMyIpAddresses();
        for (int i=0; i< addresses.length(); i++) {
            try {
                JSONObject map = addresses.getJSONObject(i);
                String intf = map.getString("intf");
                if (intf.startsWith("tun") && tunnelFlag) {
                    return map.getString("ipaddr");
                } else if (!tunnelFlag) {
                    return map.getString("ipaddr");
                }
            } catch (Exception e) {
                CTError(TAG, "GetMyIpAddress(): bad record");
            }
        }
        CTError(TAG, "GetMyIpAddress(): No address found");
        return "";
    }

    @NonNull
    public static JSONArray GetMyIpAddresses() {
        HashMap<String,JSONObject> map = new HashMap<>();
        boolean tunnelFound = false;

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
                    String key = netName + ":" + ipaddr;
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
        //  front of the list, because it's likely to be our private subnet connection
        //  to our peer.
        Set<String> keySet = map.keySet();
        String[] keyList = keySet.toArray(new String[0]);
        String[] remainderKeys = new String[keyList.length];
        int remainderCount = 0;
        for (String key : keyList) {
            if (key.startsWith("tun")) {
                tunnelFound = true;
                MyIpAddresses.put(map.get(key));
            } else {
                remainderKeys[remainderCount++] = key;
            }
        }
        for (int i=0; i< remainderCount; i++) {
            MyIpAddresses.put(map.get(remainderKeys[i]));
        }
        if (!tunnelFound) {
        //    ShowToast("No VPN tunnel found - ZeroTier network is down.");
        }
        return MyIpAddresses;
    }

    public void shutdown(R2CListener.r2cState state) {
        CTDebug(TAG, "Shutting down connection to " + peerName);
        RemovePeer(this);
        if (null != peerListener) {
            peerListener.peerStatusChange(this, state);
            peerListener = null;
        }
        R2CPeer r2cPeer = PeerIdMap.get(remoteUUID);
        if (null != r2cPeer) {
            // Then we have an established connection to leave. Be polite and say goodbye.
            JSONObject jo = new JSONObject();
            try {
                jo.put("type", "leaving");
            } catch (Exception e) {
                CTError(TAG, "argh!", e);
            }
            wsPipe.sendMessage(jo, 0, true);
            sendMsgCount++;
            wsPipe.closeSocket(1000, "'leaving'.");
        }

        String[] ridKeyArray = new String[PeerRidMap.size()];
        int i = 0;
        for (Map.Entry<String,R2CPeer>ridEntry : PeerRidMap.entrySet()) {
            if (ridEntry.getValue() == this) ridKeyArray[i++] = ridEntry.getKey();
        }
        for (int j=0; j<i; j++) PeerRidMap.remove(ridKeyArray[j]);

        for (CaltopoLiveTrack liveTrack : liveTracksUsingThisPeer) {
            liveTrack.updateStatus(R2CRespEnum.reevaluate);
        }
        this.remoteUpdateListener = null;
        this.remoteDroneSpecMonitor = null;
    }

    public static void Shutdown() {
        try {
            StatusUpdatePoll.stop();
            Set<String> keys = PeerIdMap.keySet();
            String[] keyArray = keys.toArray(new String[0]);
            for (String key : keyArray) {
                // Try to notify each of my peers that I'm leaving the group.
                R2CPeer peer = PeerIdMap.get(key);
                if (null != peer) peer.shutdown(R2CListener.r2cState.down);
            }
            WsPipe.Shutdown();
        } catch (Exception e) {
            CTError(TAG, "Shutdown() raised.", e);
        }
    }

    protected void finalize() {
        CTDebug(TAG, String.format(Locale.US, "finalize() instance %d releasing.", R2CPeerId));
    }
}
