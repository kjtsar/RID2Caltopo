package org.opendroneid.android.bluetooth;

import org.ncssar.rid2caltopo.data.CtDroneSpec;
import org.opendroneid.android.Constants;
import org.opendroneid.android.data.AircraftObject;
import org.opendroneid.android.data.Identification;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

public class OpenDroneIdDataManagerParserSeamsTest {

    @Test
    public void ridTimestamp_invalidUnknown_returnsZero() {
        long nowWallMsec = 1_700_000_000_000L;
        assertEquals(0L, OpenDroneIdDataManager.ridTimestampTenthsToUtcMsec(0xFFFF, nowWallMsec));
    }

    @Test
    public void ridTimestamp_nearHourBoundary_choosesNearestHour() {
        long nowWallMsec = (13L * 60L * 60L * 1000L) - 100L; // 12:59:59.900 UTC within a synthetic day
        long expected = (13L * 60L * 60L * 1000L) + 200L; // 13:00:00.200

        assertEquals(expected, OpenDroneIdDataManager.ridTimestampTenthsToUtcMsec(2.0, nowWallMsec));
    }

    @Test
    public void selectTrackAltitude_ridAndMslValid_prefersRidAndUpdatesReference() {
        OpenDroneIdDataManager.SelectedTrackAltitude selected =
                OpenDroneIdDataManager.selectTrackAltitudeMeters(43.6, 1043.6, null);

        assertEquals(44L, selected.roundedMeters);
        assertEquals(1000.0, selected.geodeticMinusRidHeightMeters, 0.000001);
    }

    @Test
    public void selectTrackAltitude_ridInvalid_usesReferenceAgainstMsl() {
        OpenDroneIdDataManager.SelectedTrackAltitude selected =
                OpenDroneIdDataManager.selectTrackAltitudeMeters(
                        OpenDroneIdDataManager.RID_INVALID_ALTITUDE_METERS,
                        1510.2,
                        1000.0
                );

        assertEquals(510L, selected.roundedMeters);
        assertEquals(1000.0, selected.geodeticMinusRidHeightMeters, 0.000001);
    }

    @Test
    public void selectTrackAltitude_noReference_fallsBackToMsl() {
        OpenDroneIdDataManager.SelectedTrackAltitude selected =
                OpenDroneIdDataManager.selectTrackAltitudeMeters(
                        OpenDroneIdDataManager.RID_INVALID_ALTITUDE_METERS,
                        120.6,
                        null
                );

        assertEquals(121L, selected.roundedMeters);
        assertNull(selected.geodeticMinusRidHeightMeters);
    }

    @Test
    public void liveDataUpdates_backgroundRidIngestThread_postsAsynchronously() {
        Thread mainThread = Thread.currentThread();
        Thread ridIngestThread = new Thread();

        assertEquals(false,
                OpenDroneIdDataManager.shouldSetLiveDataSynchronously(ridIngestThread, mainThread));
    }

    @Test
    public void receiveData_backgroundThread_createsAircraftWithoutLiveDataThreadCrash() throws Exception {
        byte[] payload = new byte[Constants.MAX_MESSAGE_SIZE];
        payload[0] = 0x02; // BASIC_ID, protocol version 2
        payload[1] = 0x12; // serial number, multirotor
        byte[] idBytes = "RID2CALTOPO12345".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(idBytes, 0, payload, 2, idBytes.length);
        OpenDroneIdParser.Message<?> message =
                OpenDroneIdParser.parseMessage(payload, 0, 1234L, null, 7);
        OpenDroneIdDataManager manager = new OpenDroneIdDataManager(null);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread ingestThread = new Thread(() -> {
            try {
                manager.receiveData(1234L, "AA:BB:CC:DD:EE:FF", 0xAABBCCDDEEFFL, -48,
                        message, CtDroneSpec.TransportTypeEnum.BT4);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "RID-ingest-test");
        ingestThread.start();
        ingestThread.join();

        assertNull(failure.get());
        AircraftObject aircraft = manager.getAircraft().get(0xAABBCCDDEEFFL);
        assertNotNull(aircraft);
        assertEquals(Identification.IdTypeEnum.Serial_Number,
                aircraft.getIdentification1().getIdType());
    }

    @Test
    public void ridIngestFailureLogging_onlyLogsFirstTenOccurrences() {
        assertEquals(true, OpenDroneIdDataManager.shouldLogLimitedOccurrence(1, 10));
        assertEquals(true, OpenDroneIdDataManager.shouldLogLimitedOccurrence(10, 10));
        assertEquals(false, OpenDroneIdDataManager.shouldLogLimitedOccurrence(11, 10));
        assertEquals(false, OpenDroneIdDataManager.shouldLogLimitedOccurrence(100, 10));
    }
}
