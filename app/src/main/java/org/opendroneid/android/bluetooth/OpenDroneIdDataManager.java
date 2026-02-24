/*
 * Copyright (C) 2019 Intel Corporation
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.opendroneid.android.bluetooth;

import android.bluetooth.le.ScanResult;

import org.ncssar.rid2caltopo.data.CtDroneSpec;
import org.opendroneid.android.Constants;
import org.opendroneid.android.data.AircraftObject;
import org.opendroneid.android.data.Connection;
import org.opendroneid.android.data.Identification;
import org.opendroneid.android.data.AuthenticationData;
import org.opendroneid.android.data.LocationData;
import org.opendroneid.android.data.SelfIdData;
import org.opendroneid.android.data.SystemData;
import org.opendroneid.android.data.OperatorIdData;
import org.ncssar.rid2caltopo.data.CaltopoClient;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public class OpenDroneIdDataManager {
    public final ConcurrentHashMap<Long, AircraftObject> aircraft = new ConcurrentHashMap<>();
    private static class LastReportedLocation {
        final long droneTimestampMsec;
        final double lat;
        final double lng;
        final long altitudeMeters;

        LastReportedLocation(long droneTimestampMsec, double lat, double lng, long altitudeMeters) {
            this.droneTimestampMsec = droneTimestampMsec;
            this.lat = lat;
            this.lng = lng;
            this.altitudeMeters = altitudeMeters;
        }
    }
    private static class AltitudeReferenceState {
        Double geodeticMinusRidHeightMeters;
    }
    private final ConcurrentHashMap<String, LastReportedLocation> lastReportedLocationByRemoteId =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AltitudeReferenceState> altitudeReferenceByRemoteId =
            new ConcurrentHashMap<>();

    private static final String TAG = "OpenDroneIdDataManager";

    public android.location.Location receiverLocation;

    private final Callback callback;

    public static class Callback {
        public void onNewAircraft(AircraftObject object) {}
    }

    public OpenDroneIdDataManager(Callback callback) {
        this.callback = callback;
    }

    public ConcurrentHashMap<Long, AircraftObject> getAircraft() {
        return aircraft;
    }

    private static long ridTimestampTenthsToUtcMsec(double locationTimestampTenths, long nowWallMsec) {
        long rawTenths = Math.round(locationTimestampTenths);
        if (rawTenths == 0xFFFFL) {
            return 0L; // invalid/unknown
        }
        if (rawTenths < 0L) {
            rawTenths = 0L;
        }
        if (rawTenths > 36_000L) {
            CaltopoClient.CTError(TAG, String.format(Locale.US,
                    "Received invalid location timestamp tenths:%d", rawTenths));
            rawTenths = rawTenths % 36_000L;
        }
        // Timestamp is time within current UTC hour in 0.1 second units.
        long withinHourMsec = (rawTenths % 36_000L) * 100L;
        long hourMsec = 60L * 60L * 1000L;
        long baseHourMsec = (nowWallMsec / hourMsec) * hourMsec;

        long candidatePrev = baseHourMsec - hourMsec + withinHourMsec;
        long candidateThis = baseHourMsec + withinHourMsec;
        long candidateNext = baseHourMsec + hourMsec + withinHourMsec;

        long best = candidateThis;
        long bestDelta = Math.abs(nowWallMsec - candidateThis);
        long prevDelta = Math.abs(nowWallMsec - candidatePrev);
        if (prevDelta < bestDelta) {
            best = candidatePrev;
            bestDelta = prevDelta;
        }
        long nextDelta = Math.abs(nowWallMsec - candidateNext);
        if (nextDelta < bestDelta) {
            best = candidateNext;
        }
        return best;
    }

    void receiveDataBluetooth(byte[] data, ScanResult result, CtDroneSpec.TransportTypeEnum transportType) {
        String macAddress = result.getDevice().getAddress();
        String macAddressCleaned = macAddress.replace(":", "");
        long macAddressLong = Long.parseLong(macAddressCleaned,16);

        OpenDroneIdParser.Message<?> message =
                OpenDroneIdParser.parseData(data, 6, result.getTimestampNanos(), receiverLocation);
        if (message == null)
            return;
        receiveData(result.getTimestampNanos(), macAddress, macAddressLong, result.getRssi(),
                    message, transportType);
    }

    void receiveDataNaN(byte[] data, int peerHash, long timeNano, CtDroneSpec.TransportTypeEnum transportType) {
        OpenDroneIdParser.Message<?> message =
                OpenDroneIdParser.parseData(data, 1, timeNano, receiverLocation);
        if (message == null) {
            CaltopoClient.CTError(TAG, "Not able to parse NaN data.");
            return;
        }
        CaltopoClient.CTInfo(TAG, "Caltopo: Wireless NaN for NAN ID: " + peerHash);
        receiveData(timeNano, "NaN ID: " + peerHash, peerHash, 0, message, transportType);
    }

    void receiveDataWiFiBeacon(byte[] data, String mac, long macLong, int rssi, long timeNano,
                               CtDroneSpec.TransportTypeEnum transportType) {
        OpenDroneIdParser.Message<?> message =
                OpenDroneIdParser.parseData(data, 1, timeNano, receiverLocation);
        if (message == null)
            return;
        receiveData(timeNano, mac, macLong, rssi, message, transportType);
    }
    void updateCaltopo(AircraftObject ac, CtDroneSpec.TransportTypeEnum transportType) {
        Identification acId = ac.getIdentification1();

        if (null == acId) return;
        String rawStr = acId.getUasIdAsString();
        if (null == rawStr) return;
        // remove nulls and any other garbage from idstr:
        String idStr = rawStr.replaceAll("[^A-Z0-9]", "");
        if (idStr.isEmpty()) {
            CtDroneSpec.BumpInvalidWaypointCount();
            if (CaltopoClient.DebugLevel > CaltopoClient.DebugLevelDebug) {
                CaltopoClient.CTInfo(TAG, String.format(Locale.US,
                        "updateCaltopo(): Ignoring message with invalid id from mac:0x%x transport:%s",
                        ac.getMacAddress(), transportType));
            }
            return;
        }

        CaltopoClient client = CaltopoClient.ClientForRemoteId(idStr);
        LocationData location = ac.getLocation();
        if (null != location) {
            long nowWallMsec = System.currentTimeMillis();
            long timestampInMilliseconds = ridTimestampTenthsToUtcMsec(location.getLocationTimestamp(), nowWallMsec);
            double lat = location.getLatitude();
            double lng = location.getLongitude();
            double altitudeMslMeters = location.getAltitudeGeodetic();
            double ridHeightMeters = location.getHeight();
            LastReportedLocation prior = lastReportedLocationByRemoteId.get(idStr);
            AltitudeReferenceState altitudeRef = altitudeReferenceByRemoteId.computeIfAbsent(
                    idStr, k -> new AltitudeReferenceState()
            );
            boolean ridHeightValid = ridHeightMeters != -1000.0 && Double.isFinite(ridHeightMeters);
            double selectedTrackAltitudeMeters;
            if (ridHeightValid) {
                // Primary source for ATO: RID height (relative to takeoff/ground semantics from aircraft).
                selectedTrackAltitudeMeters = ridHeightMeters;
                if (altitudeMslMeters != -1000.0 && Double.isFinite(altitudeMslMeters)) {
                    altitudeRef.geodeticMinusRidHeightMeters = altitudeMslMeters - ridHeightMeters;
                }
            } else if (altitudeRef.geodeticMinusRidHeightMeters != null &&
                    altitudeMslMeters != -1000.0 && Double.isFinite(altitudeMslMeters)) {
                // Keep continuity if RID height temporarily drops out.
                selectedTrackAltitudeMeters = altitudeMslMeters - altitudeRef.geodeticMinusRidHeightMeters;
            } else {
                // Last resort if we have no relative-height reference yet.
                selectedTrackAltitudeMeters = altitudeMslMeters;
            }
            long altitudeInMeters = Math.round(selectedTrackAltitudeMeters);
            if (prior != null &&
                    prior.droneTimestampMsec == timestampInMilliseconds &&
                    Double.compare(prior.lat, lat) == 0 &&
                    Double.compare(prior.lng, lng) == 0 &&
                    prior.altitudeMeters == altitudeInMeters) {
                return;
            }
            lastReportedLocationByRemoteId.put(
                    idStr,
                    new LastReportedLocation(timestampInMilliseconds, lat, lng, altitudeInMeters)
            );
            CaltopoClient.PositionTelemetry telemetry = null;
            double speedVerticalMps = location.getSpeedVertical();
            double speedGroundMps = location.getSpeedHorizontal();
            double directionDeg = location.getDirection();
            Double aircraftAltitudeFt = (altitudeMslMeters != -1000.0)
                    ? altitudeMslMeters * 3.28084
                    : null;
            Double aircraftAltitudeRateFpm = (speedVerticalMps != 63.0)
                    ? speedVerticalMps * 196.850394
                    : null;
            Double aircraftGsKnots = (speedGroundMps != 255.0)
                    ? speedGroundMps * 1.94384449
                    : null;
            Double aircraftTrackDeg = (directionDeg >= 0.0 && directionDeg <= 360.0)
                    ? directionDeg
                    : null;
            if (aircraftAltitudeFt != null || aircraftAltitudeRateFpm != null ||
                    aircraftGsKnots != null || aircraftTrackDeg != null) {
                // ASTM RID does not provide true nose heading, so use direction as a heading proxy.
                telemetry = new CaltopoClient.PositionTelemetry(
                        aircraftAltitudeFt,
                        aircraftAltitudeRateFpm,
                        aircraftGsKnots,
                        aircraftTrackDeg,
                        aircraftTrackDeg,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );
            }
            /*
            Log.i(TAG, String.format(Locale.US,
                    "Processing new waypoint from %s on transport:%s, " +
                            "TimestampIn:%d, Altitude:%d at %.5f,%.5f",
                    idStr, transportType, timestampInSeconds, altitudeInMeters, lat, lng));
             */
            client.newWaypoint(lat, lng, altitudeInMeters, timestampInMilliseconds, transportType, telemetry);
        }
    }

    @SuppressWarnings("unchecked")
    void receiveData(long timeNano, String macAddress, long macAddressLong, int rssi,
                     OpenDroneIdParser.Message<?> message, CtDroneSpec.TransportTypeEnum transportType) {

        // Handle connection
        boolean newAircraft = false;

        /* FIXME: This assumes that macAddressLong doesn't change.
         *  Unfortunately, the remote mac address for wireless NaN messages changes.  The spec for the interface
         *  says not to rely on it for a unique handle to the remote.   So, we really need to parse the message in
         *  a common data buffer, then use that information to look up the Remote ID (Serial Number) and use that
         *  as the unique handle to reference an aircraft.  Note that some aircraft remote modules broadcast both
         *  Wireless and Bluetooth, so ideally should keep track of transport information for each within a single
         *  aircraft instance.
         */
        AircraftObject ac = aircraft.get(macAddressLong);
        if (ac == null) {
            ac = createNewAircraft(macAddress, macAddressLong);
            newAircraft = true;
        }
        long currentTime = System.currentTimeMillis();
        ac.getConnection().msgDelta = currentTime - ac.getConnection().lastSeen;
        ac.getConnection().lastSeen = currentTime;
        ac.getConnection().rssi = rssi;
        ac.getConnection().transportType = transportType;
        ac.getConnection().setTimestamp(timeNano);
        ac.getConnection().setMsgVersion(message.header.version);
        ac.connection.setValue(ac.connection.getValue());

        if (newAircraft) {
            aircraft.put(macAddressLong, ac);
            if (null != callback) callback.onNewAircraft(ac);
        }

        boolean locationUpdated;
        if (message.header.type == OpenDroneIdParser.Type.MESSAGE_PACK) {
            locationUpdated = handleMessagePack(ac,
                    (OpenDroneIdParser.Message<OpenDroneIdParser.MessagePack>) message,
                    timeNano, message.msgCounter);
        } else {
            locationUpdated = handleMessages(ac, message);
        }

        if (locationUpdated) {
            updateCaltopo(ac, transportType);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean handleMessages(AircraftObject ac, OpenDroneIdParser.Message<?> message) {
        switch (message.header.type) {
            case BASIC_ID:
                handleBasicId(ac, (OpenDroneIdParser.Message<OpenDroneIdParser.BasicId>) message);
                return false;
            case LOCATION:
                handleLocation(ac, (OpenDroneIdParser.Message<OpenDroneIdParser.Location>) message);
                return true;
            case AUTH:
                handleAuthentication(ac, (OpenDroneIdParser.Message<OpenDroneIdParser.Authentication>) message);
                return false;
            case SELFID:
                handleSelfID(ac, (OpenDroneIdParser.Message<OpenDroneIdParser.SelfID>) message);
                return false;
            case SYSTEM:
                handleSystem(ac, (OpenDroneIdParser.Message<OpenDroneIdParser.SystemMsg>) message);
                return false;
            case OPERATOR_ID:
                handleOperatorID(ac, (OpenDroneIdParser.Message<OpenDroneIdParser.OperatorID>) message);
                return false;
            default:
                return false;
        }
    }

    private AircraftObject createNewAircraft(String macAddress, long macAddressLong) {
        AircraftObject ac = new AircraftObject(macAddressLong);
        Connection connection = new Connection();
        connection.firstSeen = System.currentTimeMillis();
        connection.macAddress = macAddress;
        ac.connection.setValue(connection);

        ac.identification1.setValue(new Identification());
        ac.identification2.setValue(new Identification());
        ac.location.setValue(new LocationData());
        ac.authentication.setValue(new AuthenticationData());
        ac.selfid.setValue(new SelfIdData());
        ac.system.setValue(new SystemData());
        ac.operatorid.setValue(new OperatorIdData());
        return ac;
    }

    private void handleBasicId(AircraftObject ac, OpenDroneIdParser.Message<OpenDroneIdParser.BasicId> message) {
        OpenDroneIdParser.BasicId raw = message.payload;
        Identification data = new Identification();
        data.setMsgCounter(message.msgCounter);
        data.setTimestamp(message.timestamp);

        data.setUaType(raw.uaType);
        data.setIdType(raw.idType);
        data.setUasId(raw.uasId);

        // This implementation can receive up-to two different types of Basic ID messages
        // Find a free slot to store the current message in or overwrite old data of same type
        Identification id1 = ac.identification1.getValue();
        Identification id2 = ac.identification2.getValue();
        if (id1 == null || id2 == null)
            return;
        Identification.IdTypeEnum type1 = id1.getIdType();
        Identification.IdTypeEnum type2 = id2.getIdType();
        if (type1 == Identification.IdTypeEnum.None || type1 == data.getIdType()) {
            ac.identification1.setValue(data);
        } else {
            if (type2 == Identification.IdTypeEnum.None || type2 == data.getIdType()) {
                ac.identification2.setValue(data);
            } else {
                CaltopoClient.CTInfo(TAG, "Discarded Basic ID message of type: " + data.getIdType().toString() +
                        ". Already have " + type1.toString() + " and " + type2.toString());
            }
        }
    }

    private void handleLocation(AircraftObject ac, OpenDroneIdParser.Message<OpenDroneIdParser.Location> message) {
        OpenDroneIdParser.Location raw = message.payload;
        LocationData data = new LocationData();
        data.setMsgCounter(message.msgCounter);
        data.setTimestamp(message.timestamp);

        data.setStatus(raw.status);
        data.setHeightType(raw.heightType);
        data.setDirection(raw.getDirection());
        data.setSpeedHorizontal(raw.getSpeedHori());
        data.setSpeedVertical(raw.getSpeedVert());
        data.setLatitude(raw.getLatitude());
        data.setLongitude(raw.getLongitude());
        data.setAltitudePressure(raw.getAltitudePressure());
        data.setAltitudeGeodetic(raw.getAltitudeGeodetic());
        data.setHeight(raw.getHeight());
        data.setHorizontalAccuracy(raw.horizontalAccuracy);
        data.setVerticalAccuracy(raw.verticalAccuracy);
        data.setBaroAccuracy(raw.baroAccuracy);
        data.setSpeedAccuracy(raw.speedAccuracy);
        data.setLocationTimestamp(raw.timestamp);
        data.setTimeAccuracy(raw.getTimeAccuracy());
        data.setDistance(raw.distance);
        ac.location.setValue(data);
    }

    private void handleAuthentication(AircraftObject ac, OpenDroneIdParser.Message<OpenDroneIdParser.Authentication> message) {
        OpenDroneIdParser.Authentication raw = message.payload;
        AuthenticationData data = new AuthenticationData();
        data.setMsgCounter(message.msgCounter);
        data.setTimestamp(message.timestamp);

        data.setAuthType(raw.authType);
        data.setAuthDataPage(raw.authDataPage);
        if (raw.authDataPage == 0) {
            data.setAuthLastPageIndex(raw.authLastPageIndex);
            data.setAuthLength(raw.authLength);
            data.setAuthTimestamp(raw.authTimestamp);
        }
        data.setAuthData(raw.authData);
        ac.authentication.setValue(ac.combineAuthentication(data));
    }

    private void handleSelfID(AircraftObject ac, OpenDroneIdParser.Message<OpenDroneIdParser.SelfID> message) {
        OpenDroneIdParser.SelfID raw = message.payload;
        SelfIdData data = new SelfIdData();
        data.setMsgCounter(message.msgCounter);
        data.setTimestamp(message.timestamp);

        data.setDescriptionType(raw.descriptionType);
        data.setOperationDescription(raw.operationDescription);
        ac.selfid.setValue(data);
    }

    private void handleSystem(AircraftObject ac, OpenDroneIdParser.Message<OpenDroneIdParser.SystemMsg> message) {
        OpenDroneIdParser.SystemMsg raw = message.payload;
        SystemData data = new SystemData();
        data.setMsgCounter(message.msgCounter);
        data.setTimestamp(message.timestamp);

        data.setOperatorLocationType(raw.operatorLocationType);
        data.setClassificationType(raw.classificationType);
        data.setOperatorLatitude(raw.getLatitude());
        data.setOperatorLongitude(raw.getLongitude());
        data.setAreaCount(raw.areaCount);
        data.setAreaRadius(raw.getAreaRadius());
        data.setAreaCeiling(raw.getAreaCeiling());
        data.setAreaFloor(raw.getAreaFloor());
        data.setCategory(raw.category);
        data.setClassValue(raw.classValue);
        data.setOperatorAltitudeGeo(raw.getOperatorAltitudeGeo());
        data.setSystemTimestamp(raw.systemTimestamp);
        ac.system.setValue(data);
    }

    private void handleOperatorID(AircraftObject ac, OpenDroneIdParser.Message<OpenDroneIdParser.OperatorID> message) {
        OpenDroneIdParser.OperatorID raw = message.payload;
        OperatorIdData data = new OperatorIdData();
        data.setMsgCounter(message.msgCounter);
        data.setTimestamp(message.timestamp);

        data.setOperatorIdType(raw.operatorIdType);
        data.setOperatorId(raw.operatorId);
        ac.operatorid.setValue(data);
    }

    private boolean handleMessagePack(AircraftObject ac, OpenDroneIdParser.Message<OpenDroneIdParser.MessagePack> message,
                                      long timestamp, int msgCounter) {
        OpenDroneIdParser.MessagePack raw = message.payload;
        if (raw == null)
            return false;

        if (raw.messageSize != Constants.MAX_MESSAGE_SIZE ||
            raw.messagesInPack <= 0 ||
            raw.messagesInPack > Constants.MAX_MESSAGES_IN_PACK)
            return false;

        boolean locationUpdated = false;
        for (int i = 0; i < raw.messagesInPack; i++) {
            int offset = i*raw.messageSize;
            byte[] data = Arrays.copyOfRange(raw.messages, offset, offset + raw.messageSize);
            OpenDroneIdParser.Message<?> subMessage =
                    OpenDroneIdParser.parseMessage(data, 0, timestamp, receiverLocation, msgCounter);
            if (subMessage == null)
                return locationUpdated;

            if (handleMessages(ac, subMessage)) {
                locationUpdated = true;
            }
        }
        return locationUpdated;
    }
}
