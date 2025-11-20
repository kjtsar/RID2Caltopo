/*
 * Copyright (C) 2019 Intel Corporation
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.opendroneid.android.bluetooth;

import android.bluetooth.le.ScanResult;
import android.util.Log;

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

import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public class OpenDroneIdDataManager {
    public final ConcurrentHashMap<Long, AircraftObject> aircraft = new ConcurrentHashMap<>();

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
            long timestampInTenthsOfASecond = (long)location.getLocationTimestamp();
            /* timestampInSeconds from UAS is for the current hour based on gps, so accurate
               w/in the current hour only.  Here's the problem: Rx UAS timestamp of 3599.9
               (i.e. .1 second before the next hour).  With delays in transmitting/receiving the
               UAS timestamp, it arrives here after the hour.  So if we blindly add it to the current
               hour, we're going to occasionally see a big discontinuity in the flow of timestamps.
               One way to prevent this is to check the arriving timestamp and if it's close to
               rolling over, then subtract 60 seconds (more than worst-case delay) from our epoch
               timestamp before calculating the seconds for the hour.
             */
            long timestampInMilliseconds;
            if (timestampInTenthsOfASecond != 0xffff) {
                Instant currentInstant = Instant.now();
                // Get the epoch second (seconds since 1970-01-01T00:00:00Z)
                long epochSecond = currentInstant.getEpochSecond();
                long epochSecondHr;
                timestampInMilliseconds = timestampInTenthsOfASecond * 100;
                if (timestampInMilliseconds >= (60 * 60 * 1000)) {
                    CaltopoClient.CTError(TAG, String.format(Locale.US,
                            "Received invalid TimestampInTenthsOfASecond:%d", timestampInTenthsOfASecond));
                    timestampInMilliseconds = timestampInMilliseconds % (60 * 60 * 100);
                }
                if (timestampInMilliseconds > (59*60*1000)) {
                    epochSecondHr = ((epochSecond - 60) / (60 * 60)) * (60 * 60);
                } else {
                    epochSecondHr = (epochSecond / (60 * 60)) * (60 * 60);
                }
                // Log.d(TAG, String.format(Locale.US, "TimestampIn:%f, epochSeconds:%d, epochSecondsHr:%d, timestampOut:%f",
                //        timestampInSeconds, epochSecond, epochSecondHr, (double)epochSecondHr + timestampInSeconds));
                timestampInMilliseconds += epochSecondHr * 1000;
            } else {
                timestampInMilliseconds = 0; // not yet valid, so just set to zero.
            }
            double lat = location.getLatitude();
            double lng = location.getLongitude();
            long altitudeInMeters = (long)location.getAltitudeGeodetic();
            /*
            Log.i(TAG, String.format(Locale.US,
                    "Processing new waypoint from %s on transport:%s, " +
                            "TimestampIn:%d, Altitude:%d at %.5f,%.5f",
                    idStr, transportType, timestampInSeconds, altitudeInMeters, lat, lng));
             */
            client.newWaypoint(lat, lng, altitudeInMeters, timestampInMilliseconds, transportType);
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

        if (message.header.type == OpenDroneIdParser.Type.MESSAGE_PACK)
            handleMessagePack(ac, (OpenDroneIdParser.Message<OpenDroneIdParser.MessagePack>) message, timeNano, message.msgCounter);
        else
            handleMessages(ac, message);

        updateCaltopo(ac, transportType);
    }

    @SuppressWarnings("unchecked")
    private void handleMessages(AircraftObject ac, OpenDroneIdParser.Message<?> message) {
        switch (message.header.type) {
            case BASIC_ID:
                handleBasicId(ac, (OpenDroneIdParser.Message<OpenDroneIdParser.BasicId>) message);
                break;
            case LOCATION:
                handleLocation(ac, (OpenDroneIdParser.Message<OpenDroneIdParser.Location>) message);
                break;
            case AUTH:
                handleAuthentication(ac, (OpenDroneIdParser.Message<OpenDroneIdParser.Authentication>) message);
                break;
            case SELFID:
                handleSelfID(ac, (OpenDroneIdParser.Message<OpenDroneIdParser.SelfID>) message);
                break;
            case SYSTEM:
                handleSystem(ac, (OpenDroneIdParser.Message<OpenDroneIdParser.SystemMsg>) message);
                break;
            case OPERATOR_ID:
                handleOperatorID(ac, (OpenDroneIdParser.Message<OpenDroneIdParser.OperatorID>) message);
                break;
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

    private void handleMessagePack(AircraftObject ac, OpenDroneIdParser.Message<OpenDroneIdParser.MessagePack> message,
                                   long timestamp, int msgCounter) {
        OpenDroneIdParser.MessagePack raw = message.payload;
        if (raw == null)
            return;

        if (raw.messageSize != Constants.MAX_MESSAGE_SIZE ||
            raw.messagesInPack <= 0 ||
            raw.messagesInPack > Constants.MAX_MESSAGES_IN_PACK)
            return;

        for (int i = 0; i < raw.messagesInPack; i++) {
            int offset = i*raw.messageSize;
            byte[] data = Arrays.copyOfRange(raw.messages, offset, offset + raw.messageSize);
            OpenDroneIdParser.Message<?> subMessage =
                    OpenDroneIdParser.parseMessage(data, 0, timestamp, receiverLocation, msgCounter);
            if (subMessage == null)
                return;

            handleMessages(ac, subMessage);
        }
    }
}
