
/*
 *
 */
package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;
import static org.ncssar.rid2caltopo.data.CaltopoClient.TimeDatestampString;
import static org.ncssar.rid2caltopo.data.CaltopoClient.UpdateDroneSpecs;

import android.location.Location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.opendroneid.android.data.Util;

import java.io.Serializable;
import java.util.List;
import java.util.Locale;

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
    private static final long Version = 1L;
    private static final String TAG = "CtDroneSpec";

    @NonNull private String remoteId;
    private String mappedId;   /* The track label prefix assigned to drone */

    private String org;
    private String owner;
    private String model; /* This is the concise text description of the drone. */
    private transient long mostRecentMsecTimestamp; /* timestamp of most recent packet received */
    private transient R2CRest ownerR2c;
    private transient CtDroneSpecListener myListener;
    private transient CaltopoLiveTrack myLiveTrack;
    private transient int[] transportCount = new int[TransportTypeEnum.values().length];
    private transient int totalCount; // all waypoints, including those with bad coords and altitude.
    private transient SimpleTimer flightSimpleTimer = new SimpleTimer();
    private transient String trackLabel = "";
    public transient double lastLat;
    public transient double lastLng;
    private transient int goodCount; // only the number of good waypoints.

    @NonNull
    public String trackLabel() { return trackLabel;}

    public JSONObject asJSONObject() {
        Util.SafeJSONObject retval = new Util.SafeJSONObject();
        retval.put("remoteId", remoteId);
        retval.put("mappedId", mappedId);
        retval.put("org", org);
        retval.put("owner", owner);
        retval.put("model", model);
        retval.put("startTimeInMsec", flightSimpleTimer.getStartTimeInMsec());
        retval.put(TransportTypeEnum.BT4.name(), transportCount[TransportTypeEnum.BT4.ordinal()]);
        retval.put(TransportTypeEnum.BT5.name(), transportCount[TransportTypeEnum.BT5.ordinal()]);
        retval.put(TransportTypeEnum.WIFI.name(), transportCount[TransportTypeEnum.WIFI.ordinal()]);
        retval.put(TransportTypeEnum.WNAN.name(), transportCount[TransportTypeEnum.WNAN.ordinal()]);
        retval.put(TransportTypeEnum.R2C.name(), transportCount[TransportTypeEnum.R2C.ordinal()]);
        return retval;
    }

    public void bumpTransportCount(TransportTypeEnum tt) {
        if (null == transportCount) transportCount = new int[TransportTypeEnum.values().length];
        transportCount[tt.ordinal()]+=1; totalCount++;
    }

    public int getTransportCount(TransportTypeEnum tt) {
        if (null == transportCount) transportCount = new int[TransportTypeEnum.values().length];
        return transportCount[tt.ordinal()];
    }

    public void reset() { trackLabel = ""; }

    public void start() {
        if (null == flightSimpleTimer) flightSimpleTimer = new SimpleTimer();
        else flightSimpleTimer.restartTimer();
        updateTrackLabel();
    }

    private void updateTrackLabel() {
        trackLabel = mappedId + "_" + TimeDatestampString(flightSimpleTimer.getStartTimeInMsec());
    }

    public void setMyLiveTrack(@Nullable CaltopoLiveTrack newTrack) {
        myLiveTrack = newTrack;
    }
    public boolean isActive() {return !trackLabel.isEmpty();}

    public boolean publishingLocally() {return (null != myLiveTrack && myLiveTrack.publishingLocally());}

    public boolean notPublishing() {return (null == myLiveTrack);}

    public String getDurationInSecAsString() {
        return (null != flightSimpleTimer) ? flightSimpleTimer.durationAsString() : "";
    }

    public int getTotalCount() {
        return totalCount;
    }

    public CtDroneSpec() throws RuntimeException {
        throw new RuntimeException("Use one of the other constructor methods.");
    }
    public CtDroneSpec(@NonNull String remoteId) throws RuntimeException {
        if (remoteId.isEmpty()) {
            throw new RuntimeException("Invalid required remoteId spec.");
        }
        this.remoteId = remoteId;
        this.mappedId = remoteId;
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
        flightSimpleTimer = new SimpleTimer(jo.optLong("startTimeInMsec"));
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

    public void setDroneSpecListener(@Nullable CtDroneSpecListener myListener) {
        this.myListener = myListener;
    }

    public void setMyR2cOwner(@NonNull R2CRest newOwnerR2c) {ownerR2c = newOwnerR2c;}
    public void removeMyR2cOwner() {ownerR2c = null;}

    @Nullable
    public R2CRest getMyR2cOwner() {return ownerR2c;}

    /** checkNewWaypoint()
     *
     * @param lat new lattitude
     * @param lng new longitude
     * @return returns true if the waypoint is far enough away from the previous waypoint
     *         to be recorded.
     */
    public boolean checkNewWaypoint(double lat, double lng, long altitudeInMeters, TransportTypeEnum transportType) {
        if (trackLabel.isEmpty()) {
            start();
            UpdateDroneSpecs();
        }
        bumpTransportCount(transportType);
        if (-1000 == altitudeInMeters || (0.0 == lat && 0.0 == lng)) {
            CTInfo(TAG, String.format(Locale.US,
                    "checkNewWaypoint(%s/%s) w/Invalid altitude %d and/or coordinates %.7f, %.7f - ignoring.",
                    trackLabel, transportType, altitudeInMeters, lat, lng));
            return false; // only interested in recording real waypoints thank-you very much
        }

        final float feetPerMeter = 3.28084f;
        float[] dbResult = {Float.NaN};
        Location.distanceBetween(lat, lng, lastLat, lastLng, dbResult);
        double distanceInFeet = dbResult[0] * feetPerMeter;
        if (distanceInFeet < CaltopoClient.GetMinDistanceInFeet()) return false;
        lastLat = lat; lastLng = lng; goodCount++;
        mostRecentMsecTimestamp = System.currentTimeMillis();
        return true;
    }

    /** idleTimeInMsec()
     *
     * @param currentTimeInMsec current time in milliseconds.
     * @return duration in milliseconds since last good waypoint was received.
     */
    public long idleTimeInMsec(long currentTimeInMsec) {
        return (currentTimeInMsec - mostRecentMsecTimestamp);
    }

    public String setMappedId(@NonNull String newMappedId) {
        String oldString= mappedId;
        String newStr = newMappedId.replaceAll("[^a-zA-Z0-9]", "");
        if (!newStr.isEmpty() && !newStr.equals(oldString)) {
            mappedId = newStr;
            if (null != ownerR2c) {
                CTDebug(TAG, "Forwarding name change to owner R2C to handle...");
                ownerR2c.updateMappedId(this, newStr);
            } else {
                CTDebug(TAG, String.format(Locale.US, "setMappedId() changed from '%s' to '%s', listener:0x%x",
                        oldString, newStr, System.identityHashCode(myListener)));
                updateTrackLabel();
                if (null != myLiveTrack) myLiveTrack.renameTrack();
            }
            if (null != myListener) {
                myListener.mappedIdChanged(this, oldString, newStr);
            }
        }
        return mappedId;
    }

    @NonNull
    public String getRemoteId() { return remoteId;}
    public String getMappedId() { return mappedId;}
    public String getOrg() { return org;}
    public String setOrg(String newVal) { return org = newVal;}
    public String getModel() { return model;}
    public String setModel(String newVal) { return model = newVal;}
    public String getOwner() { return owner;}
    public String setOwner(String newVal) { return owner = newVal;}


    /** merge a new dronespec into this spec.
     *  Don't override anything other than the default mappedId.
     *
     * @param newSpec Add the contents of newSpec to this spec.
     */
    public void mergeWithNew(CtDroneSpec newSpec) {
        CTInfo(TAG, String.format(Locale.US,
                "Merging new dronespec:%s\n into existing:%s",
                newSpec.toString(), this));
        // one exception is if the mappedId is same as remoteId (default)
        if (!this.remoteId.equals(this.mappedId)) {
            this.mappedId = newSpec.mappedId;
        }
        if (this.model.isEmpty()) this.model = newSpec.model;
        if (this.org.isEmpty()) this.org = newSpec.org;
        if (this.owner.isEmpty()) this.owner = newSpec.owner;
    }

     @Override
     @NonNull
     public String toString() {
        return String.format(Locale.US,
                "rid:'%s', mid:'%s', org:'%s', model:'%s', owner:'%s'",
                remoteId, mappedId, org, model, owner);
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

    public boolean sameAs(@NonNull CtDroneSpec other) {
        if (!other.remoteId.equals(this.remoteId)) return false;
        if (!other.mappedId.equals(this.mappedId)) return false;
        if (!other.org.equals(this.org)) return false;
        if (!other.owner.equals(this.owner)) return false;
        return other.model.equals(this.model);
    }

    @NonNull
    public CtDroneSpec copy(@Nullable CtDroneSpec specToCopy) {
        if (null == specToCopy) return new CtDroneSpec(remoteId, mappedId, org, model, owner);
        specToCopy.remoteId = remoteId;
        specToCopy.mappedId = mappedId;
        specToCopy.org = org;
        specToCopy.model = model;
        specToCopy.owner = owner;
        return specToCopy;
    }
    public boolean isDifferentFrom(@NonNull CtDroneSpec other) {
        if (!other.remoteId.equals(this.remoteId)) return true;
        if (!other.mappedId.equals(this.mappedId)) return true;
        if (!other.org.equals(this.org)) return true;
        if (!other.owner.equals(this.owner)) return true;
        return !other.model.equals(this.model);
    }
 }

