package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerTabletLinkTest {
    @Test
    fun shortUrl_matchesCrossPlatformContract() {
        assertEquals(
            "https://r2c-tracker.com/t/Bz2DZg",
            TrackerTabletLink.shortUrl(
                "https://r2c-tracker.com/ncssar/",
                "Kjt A5 Pro",
            ),
        )
    }

    @Test
    fun markerDescription_containsOnlyTabletLinkLine() {
        assertEquals(
            "R2C tablet: https://r2c-tracker.com/t/Bz2DZg",
            TrackerTabletLink.markerDescription(
                "https://r2c-tracker.com/ncssar/",
                "Kjt A5 Pro",
            ),
        )
        assertEquals("", TrackerTabletLink.markerDescription("", "Kjt A5 Pro"))
        assertEquals(
            "",
            TrackerTabletLink.markerDescription(
                "https://r2c-tracker.com/ncssar/",
                "Kjt A5 Pro",
                false,
            ),
        )
    }

    @Test
    fun organizationDesignator_usesScopedTrackerPrefix() {
        assertEquals(
            "ncssar",
            TrackerTabletLink.organizationDesignator("https://r2c-tracker.com/NCSSAR/"),
        )
    }

    @Test
    fun thumbnailUrl_matchesTrackerCapabilityContract() {
        assertEquals(
            "https://r2c-tracker.com/r2c-thumbnail/Bz2DZg/00000000-0000-0000-0000-000000000001.jpg",
            TrackerTabletLink.thumbnailUrl(
                "https://r2c-tracker.com/ncssar/",
                "Kjt A5 Pro",
                "00000000-0000-0000-0000-000000000001",
            ),
        )
    }

    @Test
    fun streamShortUrl_matchesCrossPlatformCapturedStreamContract() {
        assertEquals(
            "https://r2c-tracker.com/s/QHkyEQ",
            TrackerTabletLink.streamShortUrl(
                "https://r2c-tracker.com/ncssar/",
                "Kjt A5 Pro",
                "NCS1m3",
            ),
        )
    }

    @Test
    fun markerPalette_usesGreenYellowBlueHealthContract() {
        assertEquals(
            "#2E7D32",
            CaltopoMap.localDeviceMarkerColorForState(
                PeerCoordinator.CoordinationIndicatorState.HEALTHY,
                false,
            ),
        )
        assertEquals(
            "#2E7D32",
            CaltopoMap.localDeviceMarkerColorForState(
                PeerCoordinator.CoordinationIndicatorState.IDLE,
                false,
            ),
        )
        assertEquals(
            "#F9A825",
            CaltopoMap.localDeviceMarkerColorForState(
                PeerCoordinator.CoordinationIndicatorState.DEGRADED,
                false,
            ),
        )
        assertEquals(
            "#1976D2",
            CaltopoMap.localDeviceMarkerColorForState(
                PeerCoordinator.CoordinationIndicatorState.UNCONFIGURED,
                false,
            ),
        )
        assertEquals(
            "#F9A825",
            CaltopoMap.localDeviceMarkerColorForState(
                PeerCoordinator.CoordinationIndicatorState.UNCONFIGURED,
                true,
            ),
        )
    }
}
