
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
    }

    public interface DroneSpecsChangedListener {
        // listener applies to receive bulk notification whenever one or more dronespecs change.
        void onDroneSpecsChanged(@NonNull List<CtDroneSpec> droneSpecs);
    }
    @Serial
    private static final long serialVersionUID = 2L;
    private static final String TAG = "CtDroneSpec";
    private static final String EMPTY_STRING = "";
    private static final String RID_FILTER_REGEX = "[^A-Z0-9]";
    private static final String MAPPED_ID_FILTER_REGEX = "[^_a-zA-Z0-9]";
    private static final String CALLSIGN_OPT_MODEL_REGEX = "^([0-9]?[a-zA-Z]+[0-9]+)([_a-zA-Z0-9]{1,8})?$";
    private static final Pattern CallsignOptModelPattern = Pattern.compile(CALLSIGN_OPT_MODEL_REGEX);
    private static final float MAX_SPEED_METERS_PER_SECOND = 45f;
    private static long MostRecentWaypointTimestampInMsec = System.currentTimeMillis();
    private static long InvalidWaypointCount = 0;

    @NonNull private final String remoteId;
    private String mappedId;   /* The track label prefix assigned to drone */

    private String org;
    private String owner;
    private String model; /* This is the concise text description of the drone. */
    private transient long mostRecentMsecTimestamp; /* timestamp of most recent good packet received */
    private transient long startMsecTimestamp;
    private transient R2CPeer ownerR2c;
    private transient CtDroneSpecListener myListener;
    private transient CaltopoLiveTrack myLiveTrack;
    private transient int[] transportCount = new int[TransportTypeEnum.values().length];
    private transient int totalCount; // all waypoints, including those with bad coords and altitude.
    private transient String trackLabel;
    public transient double lastLat;
    public transient double lastLng;
    public transient double lastAlt;
    private transient double distanceInFeet;
    private transient int goodCount; // only the number of good waypoints.
    private boolean okToLog = true;

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
        distanceInFeet = 0.0F;
        lastLat = 0.0F;
        lastLng = 0.0F;
        okToLog = true;
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

    public void reset() {
        if (trackLabel.isEmpty()) return;
        CTDebug(TAG, "checkNewWaypoint(): Advising dronespec inactive: " + trackLabel);
        trackLabel = EMPTY_STRING;
        goodCount = 0;
        totalCount = 0;
        distanceInFeet = 0.0F;
        lastLat = 0.0F;
        lastLng = 0.0F;
        okToLog = true;
        startMsecTimestamp = mostRecentMsecTimestamp = 0;
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
        String lModel, lMappedId = "", lCallsign = null;
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

    public long getStartMsecTimestamp() { return startMsecTimestamp; }
    public String getDurationInSecAsString() {
        long durationInMsec = 0;
        if (startMsecTimestamp > 0 && (startMsecTimestamp < mostRecentMsecTimestamp)) {
            durationInMsec = mostRecentMsecTimestamp - startMsecTimestamp;
        }
        return SimpleTimer.DurationAsString(durationInMsec);
    }

    public double getLastLat() {return lastLat;}
    public double getLastLng() {return lastLng;}
    public double getLastAlt() {return lastAlt;}
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
    public boolean checkNewWaypoint(double lat, double lng, long altitudeInMeters, TransportTypeEnum transportType) {
        bumpTransportCount(transportType);
        if (null == trackLabel) trackLabel = EMPTY_STRING;
        if (trackLabel.isEmpty()) {
            trackLabel = mappedId;
        }
        if (-1000 == altitudeInMeters || (0.0 == lat && 0.0 == lng)) {
            InvalidWaypointCount++;
            if (CaltopoClient.DebugLevel > CaltopoClient.DebugLevelDebug) {
                CTInfo(TAG, String.format(Locale.US,
                        "checkNewWaypoint(%s/%s) w/Invalid altitude %d and/or coordinates %.7f, %.7f - ignoring.",
                        trackLabel, transportType, altitudeInMeters, lat, lng));
            }
            return false; // only interested in recording real waypoints thank-you very much
        }

        if (MyLat == 0.0F && null != CaltopoMap.MyLocation && CaltopoMap.MyLocation.hasAccuracy()) {
            MyLat = CaltopoMap.MyLocation.getLatitude();
            MyLng = CaltopoMap.MyLocation.getLongitude();
        }
        // N.B: Autel Evo Max 4N has demonstrated willingness to publish bogus coordinates,
        //   resulting in a 30,000mi 15 minute flight, so should filter them out... Once we have
        //   our lat,lon w/reasonable accuracy, compare to incoming lat,lng to see if we're even
        //   in the same ballpark.
        if (MyLat != 0.0F) { // 0.1 degrees about 6 miles at 40 degrees latitude
            if ((Math.abs(MyLat - lat) > 0.1F) || (Math.abs(MyLng - lng) > 0.1F)) {
                if (CaltopoClient.DebugLevel > CaltopoClient.DebugLevelDebug) {
                    CTInfo(TAG, String.format(Locale.US,
                            "checkNewWaypoint(%s/%s) Ignoring spurious waypoint %.7f, %.7f.",
                            trackLabel, transportType, lat, lng));
                }
                return false; // only interested in recording real waypoints thank-you very much
            }
        }

        // We don't want to waste resources (storage/bandwidth) recording a bunch of waypoints
        // that are right on top of each other, but at the same time we do want to let the world
        // know that we're still active.
        // Note that some remoteID modules will send out dozens of RemoteID updates with the exact same
        // lat & long & timestamp even though they are moving.
        final float FeetPerMeter = 3.28084f;
        final long MinMsecInterval = 1000 * 3;
        float[] dbResult = {Float.NaN};
        float lDistanceInFeet = 0.0F ;
        long msecTimestamp = System.currentTimeMillis();
        if (lastLat != 0.0F) {
            Location.distanceBetween(lat, lng, lastLat, lastLng, dbResult);
            lDistanceInFeet = dbResult[0] * FeetPerMeter;
            if (lDistanceInFeet < CaltopoClient.GetMinDistanceInFeet() &&
                    (msecTimestamp - mostRecentMsecTimestamp) < MinMsecInterval) return false;
            distanceInFeet += lDistanceInFeet;
        }
        MostRecentWaypointTimestampInMsec = mostRecentMsecTimestamp = msecTimestamp;
        goodCount++;
        if (goodCount == 1) {  // Start the flight duration clock with first 'good' waypoint.
            startMsecTimestamp = msecTimestamp;
            updateTrackLabel();
            CTDebug(TAG, "checkNewWaypoint(): Advising dronespec active: " + trackLabel);
            CaltopoClient.DroneSpecStatusChanged(this, true);
        }
        lastLat = lat; lastLng = lng; lastAlt = altitudeInMeters;
        if (CaltopoClient.DebugLevel >= CaltopoClient.DebugLevelInfo) {
            CTInfo(TAG, String.format(Locale.US, "Distance in feet: %.3f, total:%.3f",
                    lDistanceInFeet, distanceInFeet));
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
     * @return Idle time since last waypoint was received from any drone -
     *         in Milliseconds.
     */
    public static long IdleTimeInMsec() {
        return System.currentTimeMillis() - MostRecentWaypointTimestampInMsec;
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

    public boolean isDifferentFrom(@NonNull CtDroneSpec other) {
        if (!other.remoteId.equals(this.remoteId)) return true;
        if (!other.mappedId.equals(this.mappedId)) return true;
        if (!other.org.equals(this.org)) return true;
        if (!other.owner.equals(this.owner)) return true;
        return !other.model.equals(this.model);
    }
 }
