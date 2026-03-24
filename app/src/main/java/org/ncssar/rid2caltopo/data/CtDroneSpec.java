
/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;
import static org.ncssar.rid2caltopo.data.CaltopoClient.TimeDatestampString;

import android.location.Location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.opendroneid.android.data.Util;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CtDroneSpec implements Comparable<CtDroneSpec>, Serializable {

    // telemetry is optional information that some drones might provide.
    public static class PositionTelemetry {
        @Nullable public final Double aircraftAltitudeRateFpm; // (rate of climb in ft/min)
        @Nullable public final Double aircraftGsKnots;         // (speed over the ground in knots)
        @Nullable public final Double aircraftTrackDeg;        // (direction of travel over the ground)


        public PositionTelemetry(
                @Nullable Double aircraftAltitudeRateFpm,
                @Nullable Double aircraftGsKnots,
                @Nullable Double aircraftTrackDeg
        ) {
            this.aircraftAltitudeRateFpm = aircraftAltitudeRateFpm;
            this.aircraftGsKnots = aircraftGsKnots;
            this.aircraftTrackDeg = aircraftTrackDeg;
        }
    }

    public enum AltSourceEnum { BARO, GEODETIC, NONE }

    // Minimum ridHeight (metres) required before a sample contributes to impliedTakeoffAltM.
    // Filters out near-ground packets (ridHeight ≈ 0) where absAlt − ridHeight ≈ absAlt,
    // which would seed the EMA with an inflated value and cause it to converge slowly.
    private static final double MIN_RIDHEIGHT_FOR_ATO_SAMPLE_M = 2.0;  // ~6.5 ft
    // EMA sealing: require this many qualifying samples AND a per-step delta below the
    // threshold before the estimate is considered stable enough to lock in.
    private static final int    IMPLIED_TAKEOFF_STABLE_SAMPLES = 6;
    private static final double IMPLIED_TAKEOFF_STABLE_DELTA_M = 0.4;  // ~1.3 ft

    public enum TransportTypeEnum {
        BT4,
        BT5,
        WIFI,
        WNAN,
        R2C,
        UNKNOWN
    }

    public interface CtDroneSpecListener {
        void mappedIdChanged(@NonNull CtDroneSpec droneSpec, @NonNull String oldVal, @NonNull String newVal);
        void droneTakingOff(@NonNull CtDroneSpec droneSpec);
        void droneLanded(@NonNull CtDroneSpec droneSpec);
    }

    public interface DroneSpecsChangedListener {
        // listener applies to receive bulk notification whenever one or more dronespecs change.
        void onDroneSpecsChanged(@NonNull List<CtDroneSpec> droneSpecs);
    }
    @Serial
    private static final long serialVersionUID = 2L;
    private static final String TAG = "CtDroneSpec";
    static final String ICON_LATENCY_TAG = "RidIconLatency";
    static final float FT_TO_METERS = 0.3048f;
    /** Maximum plausible drone speed in ft/sec (~200 mph). Waypoints implying greater speed are rejected. */
    private static final float MAX_WAYPOINT_SPEED_FPS = 293.3f;
    private static final String EMPTY_STRING = "";
    private static final String RID_FILTER_REGEX = "[^A-Z0-9]";
    private static final String MAPPED_ID_FILTER_REGEX = "[^_a-zA-Z0-9]";
    private static final String CALLSIGN_OPT_MODEL_REGEX = "^([0-9]?[a-zA-Z]+[0-9]+)([_a-zA-Z0-9]{1,8})?$";
    private static final Pattern CallsignOptModelPattern = Pattern.compile(CALLSIGN_OPT_MODEL_REGEX);
    private static long MostRecentWaypointTimestampInMsec = System.currentTimeMillis();
    private static long InvalidWaypointCount = 0;

    @NonNull private final String remoteId;
    private String mappedId;   /* The track label prefix assigned to drone */

    private String org;
    private String owner;
    private String model; /* This is the concise text description of the drone. */
    public transient long mostRecentMsecTimestamp; /* wall-clock time when the most recent good packet was received */
    private transient long startMsecTimestamp; /* timestamp carried by the first accepted waypoint in the current flight */
    private transient long mostRecentFlightMsecTimestamp; /* timestamp carried by the most recent accepted waypoint */
    private transient R2CPeer ownerR2c;
    private transient CtDroneSpecListener myListener;
    private transient CaltopoLiveTrack myLiveTrack;
    private transient int[] transportCount = new int[TransportTypeEnum.values().length];
    private transient int totalCount; // all waypoints, including those with bad coords and altitude.
    private transient String trackLabel;
    public volatile transient double lastLat;  // may get updated by BLE thread
    public volatile transient double lastLng;
    public volatile transient double lastAlt;
    @Nullable
    private transient PositionTelemetry lastPositionTelemetry;

    // Per-flight altitude tracking — transient, cleared on reset().
    @Nullable private volatile transient Double impliedTakeoffAltM = null;
    private transient int     impliedTakeoffSampleCount = 0;
    private transient boolean impliedTakeoffSealed      = false;
    private transient double lastRidHeightM = -1000.0;   // drone-reported ATO or AGL, -1000 if not valid
    private transient boolean lastRidHeightIsAto = false; // true=ATO, false=AGL
    @Nullable private transient AltSourceEnum lastAltSource = null; // detect mid-flight source switch

    /**
     * Per-transport RF signal strength in dBm, indexed by {@link TransportTypeEnum#ordinal()}.
     * 0 = no measurement received yet for that transport (R2C relay never has a real RSSI).
     * Updated by {@link #updateLastRssi} on every accepted packet from an RF transport.
     */
    private transient int[] lastRssiByTransport = new int[TransportTypeEnum.values().length];

    private transient double distanceInFeet;
    private transient int goodCount; // only the number of good waypoints.
    private transient int nonCount;  // bad or duplicate waypoints.
    private boolean okToLog;
    @Nullable private transient Boolean airborne = Boolean.FALSE;

    @NonNull
    public String trackLabel() { return trackLabel;}

    public JSONObject asJSONObject() {
        Util.SafeJSONObject retval = new Util.SafeJSONObject();
        retval.put("remoteId", remoteId);
        retval.put("mappedId", mappedId);
        retval.put("org", org);
        retval.put("owner", owner);
        retval.put("model", model);
        retval.put("startTimeInMsec", startMsecTimestamp);
        retval.put("mostRecentTimeInMsec", mostRecentMsecTimestamp);
        retval.put("mostRecentFlightTimeInMsec", mostRecentFlightMsecTimestamp);
        retval.put("goodCount", goodCount);
        retval.put(TransportTypeEnum.BT4.name(), transportCount[TransportTypeEnum.BT4.ordinal()]);
        retval.put(TransportTypeEnum.BT5.name(), transportCount[TransportTypeEnum.BT5.ordinal()]);
        retval.put(TransportTypeEnum.WIFI.name(), transportCount[TransportTypeEnum.WIFI.ordinal()]);
        retval.put(TransportTypeEnum.WNAN.name(), transportCount[TransportTypeEnum.WNAN.ordinal()]);
        retval.put(TransportTypeEnum.R2C.name(), transportCount[TransportTypeEnum.R2C.ordinal()]);
        return retval;
    }

    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        trackLabel = EMPTY_STRING;
        startMsecTimestamp = 0;
        mostRecentFlightMsecTimestamp = 0;
        distanceInFeet = 0.0F;
        lastLat = 0.0F;
        lastLng = 0.0F;
        okToLog = true;
        impliedTakeoffAltM        = null;
        impliedTakeoffSampleCount = 0;
        impliedTakeoffSealed      = false;
        lastRidHeightM            = -1000.0;
        lastRidHeightIsAto        = false;
        lastAltSource             = null;
        lastRssiByTransport       = new int[TransportTypeEnum.values().length];
        transportCount = new int[TransportTypeEnum.values().length];
    }

    public void bumpTransportCount(TransportTypeEnum tt) {
        if (null == transportCount) transportCount = new int[TransportTypeEnum.values().length];
        transportCount[tt.ordinal()]+=1; totalCount++;
    }

    public int getTransportCount(TransportTypeEnum tt) {
        if (null == transportCount) transportCount = new int[TransportTypeEnum.values().length];
        return transportCount[tt.ordinal()];
    }

    public double getAltitudeInFeet() {return lastAlt / FT_TO_METERS;}


    public void reset() {
        if (trackLabel.isEmpty()) return;
        CTDebug(TAG, "reset(): Advising dronespec inactive: " + trackLabel);
        trackLabel = EMPTY_STRING;
        goodCount = 0;
        totalCount = 0;
        distanceInFeet = 0.0F;
        lastLat = 0.0F;
        lastLng = 0.0F;
        lastPositionTelemetry     = null;
        impliedTakeoffAltM        = null;
        impliedTakeoffSampleCount = 0;
        impliedTakeoffSealed      = false;
        lastRidHeightM            = -1000.0;
        lastRidHeightIsAto        = false;
        lastAltSource             = null;
        int rssiLen = TransportTypeEnum.values().length;
        for (int i = 0; i < rssiLen; i++) lastRssiByTransport[i] = 0;
        okToLog = true;
        airborne = Boolean.FALSE;
        startMsecTimestamp = mostRecentMsecTimestamp = mostRecentFlightMsecTimestamp = 0;
        int length = TransportTypeEnum.values().length;
        for (int i = 0; i < length; i++) transportCount[i] = 0;
        CaltopoClient.DroneSpecStatusChanged(this, false);
    }

    // Track labels should be brief, but they should also identify:
    //  1. Callsign of the rpic.
    //  2. Brief description of the drone.
    //  3. Start time for flight
    //  If 1 is absent from the mappedId, then just use the remote id - guaranteed unique for each drone.
    //  If 1 is present, but 2 is absent, but model is present, then try to extract abbreviated info from the Model field..
    //  If 1 is present, but 2 is absent and model is absent, tack on the remoteID.
    private void updateTrackLabel() {
        String lModel, lMappedId, lCallsign;
        Matcher rexMatch = CallsignOptModelPattern.matcher(mappedId);
        if (!rexMatch.matches()) {
            lMappedId = mappedId; // assume we're adults and there is a reason to not follow protocol.
        } else {
            lCallsign = rexMatch.group(1); // this part required by pattern.
            if (null != lCallsign && !lCallsign.isEmpty()) lCallsign = lCallsign.toUpperCase(Locale.US);
            lModel = rexMatch.group(2);  // this part is optional.
            if (null == lModel || lModel.isEmpty()) lModel = ModelAbbreviator(model);
            lMappedId = lCallsign + lModel;
        }
        trackLabel = String.format(Locale.US, "%s_%s", lMappedId,
                TimeDatestampString(startMsecTimestamp));
    }

    public void setMyLiveTrack(@Nullable CaltopoLiveTrack newTrack) {
        myLiveTrack = newTrack;
    }
    public boolean isActive() {return !trackLabel.isEmpty();}

    public boolean publishingLocally() {return (null != myLiveTrack && myLiveTrack.publishingLocally());}

    public boolean okToLog() {return okToLog;}

    public String getDurationInSecAsString() {
        long durationInMsec = 0;
        if (startMsecTimestamp > 0 && (startMsecTimestamp < mostRecentFlightMsecTimestamp)) {
            durationInMsec = mostRecentFlightMsecTimestamp - startMsecTimestamp;
        }
        return SimpleTimer.DurationAsString(durationInMsec);
    }

    @Nullable
    public PositionTelemetry getLastPositionTelemetry() { return lastPositionTelemetry; }
    public void setLastPositionTelemetry(@Nullable PositionTelemetry telemetry) {
        lastPositionTelemetry = telemetry;
    }
    /**
     * Called by OpenDroneIdDataManager once per accepted Location/Vector message.
     * Maintains per-drone altitude context across a sequence of RID broadcasts:
     *   - Logs any mid-flight baro↔geodetic source switches.
     *   - Tracks the drone's own reported height (ATO or AGL).
     *   - Accumulates an implied takeoff baro altitude when the drone broadcasts
     *     height-above-takeoff: impliedTakeoffAltM = absAlt − ridHeight(ATO).
     *     This is correct whether the drone is on the ground or already airborne
     *     when first observed, and is a stable value throughout the flight.
     *
     * @param absAltM    Selected absolute altitude in metres (−1000 if unavailable).
     * @param source     Which RID field was used (BARO / GEODETIC / NONE).
     * @param ridHeightM Drone-reported height in metres; −1000 if not broadcast/valid.
     * @param isAtoType  true = height is above-takeoff, false = above-ground-level.
     */
    public void updateAltitudeContext(double absAltM, @NonNull AltSourceEnum source,
                                      double ridHeightM, boolean isAtoType) {
        // Log source switches (baro→geodetic or vice-versa) mid-flight.
        if (lastAltSource != null
                && lastAltSource != AltSourceEnum.NONE
                && source != AltSourceEnum.NONE
                && source != lastAltSource) {
            CTDebug(TAG, String.format(Locale.US,
                    "updateAltitudeContext(%s): altitude source changed %s→%s",
                    mappedId, lastAltSource, source));
        }
        lastAltSource = source;

        // Track the drone's own reported relative height.
        if (ridHeightM != -1000.0) {
            lastRidHeightM  = ridHeightM;
            lastRidHeightIsAto = isAtoType;
        }

        // Maintain implied takeoff baro altitude from ATO-typed height reports.
        // absAlt − ridHeight(ATO) = baro altitude at the takeoff site — a constant
        // throughout the flight regardless of the drone's current elevation.
        //
        // Gate: skip packets where ridHeight < 2 m (~6.5 ft).  When the drone has
        // just lifted off, ridHeight ≈ 0 while absAlt already reflects the baro bias
        // of the launch site, so the estimate absAlt − ridHeight ≈ absAlt is inflated
        // and would force many EMA iterations to correct.  Waiting for ridHeight to
        // reach a modest flight altitude gives an accurate first sample immediately.
        if (absAltM != -1000.0 && ridHeightM != -1000.0 && isAtoType
                && ridHeightM >= MIN_RIDHEIGHT_FOR_ATO_SAMPLE_M
                && !impliedTakeoffSealed) {
            double sample = absAltM - ridHeightM;
            if (impliedTakeoffAltM == null) {
                impliedTakeoffAltM = sample;
                impliedTakeoffSampleCount = 1;
            } else {
                // EMA α = 0.25: converges in ~6 samples while still smoothing noise.
                double newVal = impliedTakeoffAltM * 0.75 + sample * 0.25;
                double delta  = Math.abs(newVal - impliedTakeoffAltM);
                impliedTakeoffAltM = newVal;
                impliedTakeoffSampleCount++;
                if (impliedTakeoffSampleCount >= IMPLIED_TAKEOFF_STABLE_SAMPLES
                        && delta < IMPLIED_TAKEOFF_STABLE_DELTA_M) {
                    impliedTakeoffSealed = true;
                    CTDebug(TAG, String.format(Locale.US,
                            "updateAltitudeContext(%s): implied takeoff alt sealed at %.1fm (%.0fft) after %d samples",
                            mappedId, impliedTakeoffAltM,
                            impliedTakeoffAltM * 3.28084, impliedTakeoffSampleCount));
                }
            }
        }
    }

    /** Baro altitude of the drone's takeoff point (metres), derived from
     *  broadcast height-above-takeoff. Null until the first qualifying ATO height
     *  (ridHeight ≥ 2 m) is received. Stable throughout the flight once established. */
    @Nullable
    public Double getImpliedTakeoffAltM() { return impliedTakeoffAltM; }

    /** True once the EMA has accumulated enough stable samples that the implied
     *  takeoff altitude is considered converged.  Once sealed, the EMA stops
     *  updating and MapPane should stop re-seeding the ATO calibration. */
    public boolean isImpliedTakeoffAltSealed() { return impliedTakeoffSealed; }

    /** Drone-reported relative height in metres (−1000 if not valid/broadcast). */
    public double getLastRidHeightM()    { return lastRidHeightM; }

    /** True if the last valid ridHeight was above-takeoff; false if AGL. */
    public boolean isLastRidHeightAto() { return lastRidHeightIsAto; }

    /**
     * Records the most recent RF signal strength for the given transport.
     * R2C-relayed packets carry no real RF measurement (rssi == 0) and are ignored.
     *
     * <p>Each transport maintains its own last-seen RSSI so the UI can reflect per-transport
     * signal quality for drones that broadcast over multiple RF interfaces simultaneously
     * (e.g. a DS154 transmitting on both BT4 and BT5).</p>
     *
     * @param rssi      Signal strength in dBm; 0 means not available (NaN / R2C relay).
     * @param transport Transport type that produced this reading.
     */
    public void updateLastRssi(int rssi, @NonNull TransportTypeEnum transport) {
        // R2C is a relay protocol — rssi=0 conveys no useful RF measurement.
        if (transport == TransportTypeEnum.R2C || rssi == 0) return;
        if (null == lastRssiByTransport)
            lastRssiByTransport = new int[TransportTypeEnum.values().length];
        lastRssiByTransport[transport.ordinal()] = rssi;
    }

    /**
     * Returns the strongest (highest dBm — least negative) RSSI across all RF transports.
     * This is the value used by the signal-strength bar indicator.
     * Returns 0 if no RF measurement has been received on any transport.
     */
    public int getLastRssi() {
        if (null == lastRssiByTransport) return 0;
        int best = 0;
        for (TransportTypeEnum t : TransportTypeEnum.values()) {
            if (t == TransportTypeEnum.R2C || t == TransportTypeEnum.UNKNOWN) continue;
            int v = lastRssiByTransport[t.ordinal()];
            if (v != 0 && (best == 0 || v > best)) best = v;
        }
        return best;
    }

    /**
     * Returns the most recent RSSI reading (dBm) for the specified transport.
     * Returns 0 if no measurement has been received on that transport.
     */
    public int getLastRssi(@NonNull TransportTypeEnum transport) {
        if (null == lastRssiByTransport) return 0;
        return lastRssiByTransport[transport.ordinal()];
    }

    /**
     * Returns the transport type that currently has the strongest (highest dBm) RSSI.
     * Returns {@link TransportTypeEnum#UNKNOWN} if no RF measurement has been received.
     */
    @NonNull
    public TransportTypeEnum getLastRssiTransport() {
        if (null == lastRssiByTransport) return TransportTypeEnum.UNKNOWN;
        int best = 0;
        TransportTypeEnum bestTransport = TransportTypeEnum.UNKNOWN;
        for (TransportTypeEnum t : TransportTypeEnum.values()) {
            if (t == TransportTypeEnum.R2C || t == TransportTypeEnum.UNKNOWN) continue;
            int v = lastRssiByTransport[t.ordinal()];
            if (v != 0 && (best == 0 || v > best)) {
                best = v;
                bestTransport = t;
            }
        }
        return bestTransport;
    }

    public double getDistanceInFeet() { return distanceInFeet;}

    public int getTotalCount() {
        return totalCount;
    }

    public int getGoodCount() {
        return goodCount;
    }

    public CtDroneSpec() throws RuntimeException {
        throw new RuntimeException("Use one of the other constructor methods.");
    }

    public CtDroneSpec(@NonNull String remoteId) throws RuntimeException {
        String idStr = remoteId.replaceAll(RID_FILTER_REGEX, "");
        if (idStr.isEmpty()) {
            throw new RuntimeException("Invalid required remoteId spec.");
        }
        this.okToLog = true;
        this.distanceInFeet = 0.0F;
        this.lastLat = 0.0F;
        this.lastLng = 0.0F;
        this.trackLabel = EMPTY_STRING;
        this.remoteId = idStr;
        this.mappedId = idStr;
        this.org = "";
        this.model = "";
        this.owner = "";
    }

    public CtDroneSpec(JSONObject jo) {
        remoteId = jo.optString("remoteId");
        mappedId = jo.optString("mappedId");
        org = jo.optString("org");
        owner = jo.optString("owner");
        model = jo.optString("model");
        goodCount = jo.optInt("goodCount");
        okToLog = false;
        distanceInFeet = 0.0F;
        lastLat = 0.0F;
        lastLng = 0.0F;
        trackLabel = EMPTY_STRING;
        startMsecTimestamp = jo.optLong("startTimeInMsec");
        mostRecentMsecTimestamp = jo.optLong("mostRecentTimeInMsec");
        mostRecentFlightMsecTimestamp = jo.optLong("mostRecentFlightTimeInMsec", startMsecTimestamp);
        transportCount[TransportTypeEnum.BT4.ordinal()] = jo.optInt(TransportTypeEnum.BT4.name(), 0);
        transportCount[TransportTypeEnum.BT5.ordinal()] = jo.optInt(TransportTypeEnum.BT5.name(), 0);
        transportCount[TransportTypeEnum.WIFI.ordinal()] = jo.optInt(TransportTypeEnum.WIFI.name(), 0);
        transportCount[TransportTypeEnum.WNAN.ordinal()] = jo.optInt(TransportTypeEnum.WNAN.name(), 0);
        transportCount[TransportTypeEnum.R2C.ordinal()] = jo.optInt(TransportTypeEnum.R2C.name(), 0);
        totalCount = transportCount[TransportTypeEnum.BT4.ordinal()] + transportCount[TransportTypeEnum.BT5.ordinal()] +
                transportCount[TransportTypeEnum.WIFI.ordinal()] + transportCount[TransportTypeEnum.WNAN.ordinal()] +
                transportCount[TransportTypeEnum.R2C.ordinal()];
    }

    public CtDroneSpec(@NonNull String remoteIdIn, @NonNull String mappedIdIn, String orgIn, String modelIn, String ownerIn)
            throws RuntimeException {
        if (remoteIdIn.isEmpty()) {
            throw new RuntimeException("missing/invalid required remoteId spec.");
        }
        okToLog = true;
        distanceInFeet = 0.0F;
        lastLat = 0.0F;
        lastLng = 0.0F;
        this.trackLabel = EMPTY_STRING;
        this.remoteId = remoteIdIn;
        if (mappedIdIn.isEmpty()) {
            this.mappedId = remoteIdIn;
        } else this.mappedId = mappedIdIn;

        if (null == orgIn) this.org = "";
        else this.org = orgIn;

        if (null == modelIn) this.model = "";
        else this.model = modelIn;

        if (null == ownerIn) this.owner = "";
        else this.owner = ownerIn;
    }

    public CtDroneSpec copy() {
        return new CtDroneSpec(remoteId, mappedId, org, model, owner);
    }

    public void setDroneSpecListener(@Nullable CtDroneSpecListener myListener) {
        this.myListener = myListener;
    }

    public void setMyR2cOwner(@NonNull R2CPeer newOwnerR2c) {ownerR2c = newOwnerR2c; okToLog=false;}
    public void removeMyR2cOwner() {ownerR2c = null;}

    @Nullable
    public R2CPeer getMyR2cOwner() {return ownerR2c;}

    public static long GetInvalidWaypointCount () {return InvalidWaypointCount;}

    public static void BumpInvalidWaypointCount() {InvalidWaypointCount++;}
    public static double MyLat = 0.0F;
    public static double MyLng = 0.0F;
    // Reject altitude spikes/drops that imply unrealistic vertical rates for small UAS.

    /** checkNewWaypoint()
     * FIXME: Need to include check of change in distance over change in time to see if waypoint
     *        updates are even close to sane.   Assume maximum DD/DT of 100mph or 45m/sec.  The
     *        big question is what to do when we get an update that exceeds that parameter.  Do
     *        we throw it out, smooth it to something realistic what?
     *
     * @param lat new lattitude
     * @param lng new longitude
     * @return returns true if the waypoint is far enough away from the previous waypoint
     *         to be recorded.
     */
    public boolean checkNewWaypoint(double lat, double lng, double altitudeInMeters, long timestampInMilliseconds, long nowWallMsec, @Nullable Boolean airborne, TransportTypeEnum transportType) {
        if (null == trackLabel) trackLabel = EMPTY_STRING;
        // NOTE: do NOT set trackLabel = mappedId here — that must wait until the waypoint passes
        // all validity checks below.  Setting it early causes a "ghost active" state: even a
        // packet with lat=0/lng=0 or all-invalid altitude would make isActive() return true,
        // triggering a repeated terminate-and-revive loop in ProcessSortedCurrentDroneSpecArray.
/***    Some RID modules not doing a good job of differentiating airborne.
        if (null != this.airborne && this.airborne && null != airborne && !airborne) {
            CTDebug(TAG, String.format(Locale.US,
                    "checkNewWaypoint(): %s landed.", mappedId));
            if (null != myListener) {
                myListener.droneLanded(this);
            } else {
                CaltopoClient.DroneSpecStatusChanged(this, true);
            }
            return false;
        }
***/
        // We don't want to waste resources (storage/bandwidth) recording a bunch of waypoints
        // that are right on top of each other, but at the same time we do want to let the world
        // know that we're still active.
        // Note that most remoteID modules will send out dozens of RemoteID updates with the exact
        // same lat,long & timestamp many times while in transit.
        // efficiently filter out bogus or duplicate location updates because there will be a lot of them.
        if (0.0 == lat || 0.0 == lng) {
            if (false && CaltopoClient.CTDebugEnabled(ICON_LATENCY_TAG)) {            // We see a lot of these.
                CaltopoClient.CTDebug(ICON_LATENCY_TAG, String.format(Locale.US,
                        "rid_drop remoteId=%s reason=invalid wall=%d droneTs=%d lat=%.6f lng=%.6f transport=%s",
                        mappedId, nowWallMsec, timestampInMilliseconds, lat, lng, transportType));
            }
            nonCount++;
            return false;
        }

        if (Double.compare(lastLat, lat) == 0 && Double.compare(lastLng, lng) == 0) {
            if ((goodCount == 0) || (System.currentTimeMillis() - mostRecentMsecTimestamp < 3000)) {
                if (false && CaltopoClient.CTDebugEnabled(ICON_LATENCY_TAG)) {            // these too.
                    CaltopoClient.CTDebug(ICON_LATENCY_TAG, String.format(Locale.US,
                            "rid_drop remoteId=%s reason=dedup wall=%d droneTs=%d lat=%.6f lng=%.6f transport=%s",
                            mappedId, nowWallMsec, timestampInMilliseconds, lat, lng, transportType));
                }
                return false;
            } // OK to let dupes thru at a rate of once every three seconds just to keep the wheels on.
        }

        Location myLocation = CaltopoMap.GetMyLocation();
        if (MyLat == 0.0F && null != myLocation && myLocation.hasAccuracy()) {
            MyLat = myLocation.getLatitude();
            MyLng = myLocation.getLongitude();
        }

        // N.B: Autel Evo Max 4N has demonstrated willingness to publish wild coordinates,
        //   resulting in a 30,000mi 15 minute flight, so should filter them out... Once we have
        //   our lat,lon w/reasonable accuracy, compare to incoming lat,lng to see if we're even
        //   in the same ballpark.
        if (MyLat != 0.0F) { // 0.1 degrees about 6 miles at 40 degrees latitude
            if ((Math.abs(MyLat - lat) > 0.1F) || (Math.abs(MyLng - lng) > 0.1F)) {
                CTDebug(TAG, String.format(Locale.US,
                        "checkNewWaypoint(%s/%s) Ignoring spurious waypoint %.7f, %.7f.",
                        mappedId, transportType, lat, lng));
                nonCount++;
                return false;
            }
        }

        float lDistanceInFeet = 0.0F ;
        if (lastLat != 0.0F) {
            float[] dbResult = {Float.NaN};
            Location.distanceBetween(lat, lng, lastLat, lastLng, dbResult);
            lDistanceInFeet = dbResult[0] / FT_TO_METERS;
            // Speed sanity check: rejects implausible coordinate jumps even without a phone GPS fix.
            // Catches startup coordinate bounces (e.g. Autel EVO Max: 30,000 mi in 15 min).
            long elapsedMsec = nowWallMsec - mostRecentMsecTimestamp;
            if (elapsedMsec > 0) {
                float speedFps = lDistanceInFeet / (elapsedMsec / 1000.0f);
                if (speedFps > MAX_WAYPOINT_SPEED_FPS) {
                    CTDebug(TAG, String.format(Locale.US,
                            "checkNewWaypoint(%s/%s) Ignoring implausible speed: %.0f fps (%.0f mph) from %.7f,%.7f to %.7f,%.7f",
                            mappedId, transportType, speedFps, speedFps * 3600.0f / 5280.0f,
                            lastLat, lastLng, lat, lng));
                    nonCount++;
                    return false;
                }
            }
            distanceInFeet += lDistanceInFeet;
        }

        MostRecentWaypointTimestampInMsec = mostRecentMsecTimestamp = nowWallMsec;
        long acceptedWaypointTimestampMsec =
                (timestampInMilliseconds > 0) ? timestampInMilliseconds : nowWallMsec;

        // All validity checks passed — this is an accepted waypoint.
        // Activate the drone (set the trackLabel) only now, so that invalid/filtered packets
        // (lat=0, dedup, spurious coordinates, implausible speed) cannot ghost-activate it
        // and trigger an infinite terminate-and-revive loop in ProcessSortedCurrentDroneSpecArray.
        if (trackLabel.isEmpty()) {
            trackLabel = mappedId;
            // Notify CaltopoClient so it (re)starts UiUpdatePoll and the drone appears in R2CView.
            // Without this, drones that never broadcast airborne=true are silently ignored: the
            // airborne-transition path below is the only other place that calls DroneSpecStatusChanged,
            // so UiUpdatePoll would stay stopped and the drone row would never render.
            CaltopoClient.DroneSpecStatusChanged(this, false);
        }
        bumpTransportCount(transportType);
        goodCount++;
        // The flight starts with the first accepted waypoint that survives the filters above.
        // "airborne" is logged for visibility only and must not drive flight timing.
        if (startMsecTimestamp == 0) {
            startMsecTimestamp = acceptedWaypointTimestampMsec;
            updateTrackLabel();
        }
        if (acceptedWaypointTimestampMsec > mostRecentFlightMsecTimestamp) {
            mostRecentFlightMsecTimestamp = acceptedWaypointTimestampMsec;
        }
        if (null != airborne && airborne != this.airborne) {
            this.airborne = airborne;
            CTDebug(TAG, String.format(Locale.US,
                    "checkNewWaypoint(): %s airborne changed to %s.", mappedId, airborne));
        }
        lastLat = lat; lastLng = lng; lastAlt = altitudeInMeters;
        if (CaltopoClient.CTDebugEnabled(ICON_LATENCY_TAG)) {
            CaltopoClient.CTDebug(ICON_LATENCY_TAG, String.format(Locale.US,
                    "rid_rx remoteId=%s waypoint=%d wall=%d droneTs=%d lat=%.6f lng=%.6f alt=%.1f transport=%s rssi=%d airborne=%s distance: %.3f, totalDistance: %.3f, duration: %s",
                    mappedId, goodCount, System.currentTimeMillis(), timestampInMilliseconds, lat, lng, altitudeInMeters, transportType, getLastRssi(transportType), airborne, lDistanceInFeet, distanceInFeet, getDurationInSecAsString()));
        }

        return true;
    }

    /** idleTimeInMsec()
     *
     * @param currentTimeInMsec current time in milliseconds.
     * @return duration in milliseconds since last good waypoint was received
     *         from this drone.
     */
    public long idleTimeInMsec(long currentTimeInMsec) {
        return (currentTimeInMsec - mostRecentMsecTimestamp);
    }

    /** IdleTimeInMsec()
     *
     * @return Idle time - in Milliseconds since app was started or last
     *         waypoint was received from any drone whichever is least.
     */
    public static long IdleTimeInMsec() {
        return System.currentTimeMillis() - MostRecentWaypointTimestampInMsec;
    }
    public static long LastWaypointUpdateTimestampMsec() {
        return MostRecentWaypointTimestampInMsec;
    }
    // ModelAbbreviator()
    //  Take any descriptive sentence as input and abbreviate it by preserving the
    //  first character of each space separated word and removing any vowels
    //  from the rest of the word, then pasting all the results together. To
    //  limit overall number of characters and assuming the description goes from
    //  general to specific, only the last 10 are preserved.
    //  The following examples produce corresponding results:
    //     Autel Evo Max 4N      : AtlEvMx4n
    //     DJI Mavic 3 Pro       : DjMvc3Pr
    //     DJI Matrice 4td       : DjMtrc4td
    //     DJI Mini 4 Pro        : DjMn4Pr
    //     Autel Evo Max 4n      : AtlEvMx4n
    //     Autel Evo 2 Dual 640T : lEv2Dl640t
    @NonNull
    private static String ModelAbbreviator(@NonNull String modelIn) {
        // (?i) makes pattern case-insensitive
        // (?<=\S)[aeiou] matches vowels preceded by a non-space character (not word-start)
        // | \s+ matches any spaces to be removed
        String result = modelIn.replaceAll("(?i)(?<=\\S)[aeiou]|\\s+", "");
        return result.substring(Math.max(0, result.length() - 10));
    }

    // As of rc1.0.7, we want track labels to be meaningful and hopefully adopt the standard
    // format where <callSign><droneDesc>.   For example:
    //   1SAR7mvc3p, 1P16sx10, 1SAR10m4td
    // This format identifies the RPIC and the drone
    @Nullable
    private String mappedIdForTrackLabel() {
        String lMappedId = null;
        Matcher reMatch = CallsignOptModelPattern.matcher(mappedId);
        if (reMatch.matches()) {
            String lCallsign = reMatch.group(1);
            String lModel = reMatch.group(2);
            if (null != lCallsign) lCallsign = lCallsign.toUpperCase();
            else lCallsign = "";
            if (null != lModel) lModel = lModel.toLowerCase();
            else lModel = "";
            if (!lCallsign.isEmpty()) lMappedId = lCallsign + ( lModel.isEmpty() ? "" : lModel);
        }
        return lMappedId;
    }

    // as of 1.0.7rc1, just checks to see if this contains valid characters and doesn't match
    // the rid.
    public String setMappedId(@NonNull String newMappedId) {
        String oldMappedId= mappedId;
        String newStr = newMappedId.replaceAll(MAPPED_ID_FILTER_REGEX, "");
        if (!newStr.equals(oldMappedId)) {
            mappedId = newStr;
            if (null != ownerR2c) {
                CTDebug(TAG, "Forwarding name change to owner R2C to handle...");
                ownerR2c.updateMappedId(this, newStr);
            } else {
                CTDebug(TAG, String.format(Locale.US, "setMappedId() changed from '%s' to '%s', listener:0x%x",
                        oldMappedId, newStr, System.identityHashCode(myListener)));
                updateTrackLabel();
                if (null != myLiveTrack) myLiveTrack.renameTrack();
            }
            if (null != myListener) {
                myListener.mappedIdChanged(this, oldMappedId, newStr);
            }
        }
        return mappedId;
    }

    @NonNull
    public String getRemoteId() { return remoteId;}
    public String getMappedId() { return mappedId;}
    public String getOrg() { return org;}
    public void setOrg(String newVal) { org = newVal;}
    public String getModel() { return model;}
    public void setModel(String newVal) { model = newVal;}
    public String getOwner() { return owner;}
    public void setOwner(String newVal) { owner = newVal;}


    /** merge a new dronespec into this spec.
     *  Don't override anything other than the default mappedId.
     *
     * @param newSpec Add the contents of newSpec to this spec.
     */
    public void mergeWithNew(CtDroneSpec newSpec) {
        CTInfo(TAG, String.format(Locale.US,
                "Merging new dronespec:%s\n into existing:%s",
                newSpec.toString(), this));
        if (this.model.isEmpty()) this.model = newSpec.model;
        if (this.org.isEmpty()) this.org = newSpec.org;
        if (this.owner.isEmpty()) this.owner = newSpec.owner;
        // one exception is if the mappedId is same as remoteId (default)
        if (this.remoteId.equals(this.mappedId)) {
            setMappedId(newSpec.mappedId);
        }
    }

     @Override
     @NonNull
     public String toString() {
        return String.format(Locale.US,
                "rid:'%s', mid:'%s', org:'%s', model:'%s', owner:'%s' trackLabel:%s",
                remoteId, mappedId, org, model, owner, null == trackLabel ? "":trackLabel);
     }

    /** Default sort
     *   Compares remoteIds which are guaranteed to be unique.
     *
     * @param  other to be compared against.
     * @return returns most recently seen towards end.
     */
    @Override
    public int compareTo(@NonNull CtDroneSpec other) {
        return this.remoteId.compareTo(other.remoteId);
    }

    public int compareToAge(@NonNull CtDroneSpec other) {
        return (int)(this.startMsecTimestamp - other.startMsecTimestamp);
    }

    public boolean isDifferentFrom(@NonNull CtDroneSpec other) {
        if (!other.remoteId.equals(this.remoteId)) return true;
        if (!other.mappedId.equals(this.mappedId)) return true;
        if (!other.org.equals(this.org)) return true;
        if (!other.owner.equals(this.owner)) return true;
        return !other.model.equals(this.model);
    }
 }
