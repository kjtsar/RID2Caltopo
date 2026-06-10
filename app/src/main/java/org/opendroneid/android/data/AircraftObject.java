/*
 * Copyright (C) 2019 Intel Corporation
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.opendroneid.android.data;

import android.os.Looper;

import androidx.lifecycle.MutableLiveData;
import androidx.annotation.NonNull;

import org.opendroneid.android.Constants;

public class AircraftObject {
    final public MutableLiveData<Connection> connection = new MutableLiveData<>();
    final public MutableLiveData<Identification> identification1 = new MutableLiveData<>();
    final public MutableLiveData<Identification> identification2 = new MutableLiveData<>();
    final public MutableLiveData<Identification> id1Shadow = new MutableLiveData<>();
    final public MutableLiveData<Identification> id2Shadow = new MutableLiveData<>();
    final public MutableLiveData<LocationData> location = new MutableLiveData<>();
    final public MutableLiveData<AuthenticationData> authentication = new MutableLiveData<>();
    final public MutableLiveData<SelfIdData> selfid = new MutableLiveData<>();
    final public MutableLiveData<SystemData> system = new MutableLiveData<>();
    final public MutableLiveData<OperatorIdData> operatorid = new MutableLiveData<>();

    private final long macAddress;
    private volatile Connection connectionData;
    private volatile Identification identification1Data;
    private volatile Identification identification2Data;
    private volatile LocationData locationData;
    private volatile AuthenticationData authenticationData;
    private volatile SelfIdData selfidData;
    private volatile SystemData systemData;
    private volatile OperatorIdData operatoridData;

    public AircraftObject(long macAddress) {
        this.macAddress = macAddress;
    }
    public long getMacAddress() { return macAddress; }

    public Connection getConnection() { return connectionData; }
    public Identification getIdentification1() { return identification1Data; }
    public Identification getIdentification2() { return identification2Data; }
    public LocationData getLocation() { return locationData; }
    public AuthenticationData getAuthentication() { return authenticationData; }
    public SelfIdData getSelfID() { return selfidData; }
    public SystemData getSystem() { return systemData; }
    public OperatorIdData getOperatorID() { return operatoridData; }

    public void updateConnection(Connection value) {
        connectionData = value;
        setLiveDataValue(connection, value);
    }

    public void updateIdentification1(Identification value) {
        identification1Data = value;
        setLiveDataValue(identification1, value);
    }

    public void updateIdentification2(Identification value) {
        identification2Data = value;
        setLiveDataValue(identification2, value);
    }

    public void updateLocation(LocationData value) {
        locationData = value;
        setLiveDataValue(location, value);
    }

    public void updateAuthentication(AuthenticationData value) {
        authenticationData = value;
        setLiveDataValue(authentication, value);
    }

    public void updateSelfID(SelfIdData value) {
        selfidData = value;
        setLiveDataValue(selfid, value);
    }

    public void updateSystem(SystemData value) {
        systemData = value;
        setLiveDataValue(system, value);
    }

    public void updateOperatorID(OperatorIdData value) {
        operatoridData = value;
        setLiveDataValue(operatorid, value);
    }

    public static boolean shouldSetLiveDataSynchronously(Thread currentThread, Thread mainThread) {
        return currentThread == mainThread;
    }

    private static <T> void setLiveDataValue(MutableLiveData<T> liveData, T value) {
        Looper mainLooper = Looper.getMainLooper();
        Thread mainThread = mainLooper == null ? null : mainLooper.getThread();
        if (shouldSetLiveDataSynchronously(Thread.currentThread(), mainThread)) {
            liveData.setValue(value);
        } else {
            liveData.postValue(value);
        }
    }

    // Non-zero authentication data pages do not contain the following fields. Save them for displaying
    private int authLastPageIndexSave;
    private int authLengthSave;
    private long authTimestampSave;

    // Multiple authentication messages are possible, each transmitting a part of the
    // authentication signature. Collect the data into authDataCombined.
    private final byte[] authDataCombined = new byte[Constants.MAX_AUTH_DATA];

    public AuthenticationData combineAuthentication(AuthenticationData newData) {
        AuthenticationData currData = authenticationData;
        if (currData == null)
            currData = new AuthenticationData();

        currData.setMsgCounter(newData.getMsgCounter());
        currData.setTimestamp(newData.getTimestamp());
        currData.setMsgVersion(newData.getMsgVersion());

        int offset = 0;
        int amount = Constants.MAX_AUTH_PAGE_ZERO_SIZE;
        if (newData.getAuthDataPage() == 0)  {
            authLastPageIndexSave = newData.getAuthLastPageIndex();
            authLengthSave = newData.getAuthLength();
            authTimestampSave = newData.getAuthTimestamp();
        } else {
            offset = Constants.MAX_AUTH_PAGE_ZERO_SIZE + (newData.getAuthDataPage() - 1) * Constants.MAX_AUTH_PAGE_NON_ZERO_SIZE;
            amount = Constants.MAX_AUTH_PAGE_NON_ZERO_SIZE;
        }
        for (int i = offset; i < offset + amount; i++)
            authDataCombined[i] = newData.getAuthData()[i];

        currData.setAuthType(newData.getAuthType());
        currData.setAuthLastPageIndex(authLastPageIndexSave);
        currData.setAuthLength(authLengthSave);
        currData.setAuthTimestamp(authTimestampSave);
        currData.setAuthData(authDataCombined);
        return currData;
    }

    private int idToShow = 0;

    // When two different BasicId messages have been received, use this function to force a periodic
    // swap between their uasId in the list view. It is assumed this is called once per second.
    // The change logic is slowed down to once per three seconds.
    public void updateShadowBasicId() {
        switch (idToShow) {
            case 0:
                id1Shadow.setValue(identification1Data);
                idToShow++;
                break;
            case 3:
                Identification id2 = identification2Data;
                if (id2 != null && id2.getIdType() != Identification.IdTypeEnum.None)
                    id2Shadow.setValue(id2);
                idToShow++;
                break;
            case 6:
                idToShow = 0;
                break;
            default:
                idToShow++;
        }
    }

    @Override @NonNull
    public String toString() {
        return "AircraftObject{" +
                "macAddress=" + macAddress +
                ", identification1=" + identification1 +
                ", identification2=" + identification2 +
                '}';
    }
}
