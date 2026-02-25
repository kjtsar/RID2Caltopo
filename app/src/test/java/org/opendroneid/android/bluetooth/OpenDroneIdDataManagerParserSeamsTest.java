package org.opendroneid.android.bluetooth;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
}
