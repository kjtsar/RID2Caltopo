package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPanePresentationModeTest {
    @Test
    fun mapPaneMarkerScale_isSmallerForInsetMode() {
        assertEquals(1.0f, mapPaneMarkerScale(MapPanePresentationMode.Full), 0.0001f)
        assertEquals(0.55f, mapPaneMarkerScale(MapPanePresentationMode.Inset), 0.0001f)
    }

    @Test
    fun mapPaneLineScale_isThinnerForInsetMode() {
        assertEquals(1.0f, mapPaneLineScale(MapPanePresentationMode.Full), 0.0001f)
        assertEquals(0.65f, mapPaneLineScale(MapPanePresentationMode.Inset), 0.0001f)
    }

    @Test
    fun mapPaneInsetViewportZoom_zoomsOutToPreserveFullMapExtent() {
        assertEquals(
            12.0,
            mapPaneInsetViewportZoom(
                fullWidthPx = 1280,
                fullHeightPx = 720,
                insetWidthPx = 320,
                insetHeightPx = 180,
                fullZoom = 14.0
            ),
            0.0001
        )
    }

    @Test
    fun mapPaneInsetViewportZoom_keepsZoomWhenSizeIsUnknown() {
        assertEquals(
            14.0,
            mapPaneInsetViewportZoom(
                fullWidthPx = null,
                fullHeightPx = 720,
                insetWidthPx = 320,
                insetHeightPx = 180,
                fullZoom = 14.0
            ),
            0.0001
        )
    }

    @Test
    fun shouldFollowFocusedDrone_requiresToggleAndTelemetry() {
        assertFalse(
            shouldFollowFocusedDrone(
                presentationMode = MapPanePresentationMode.Inset,
                followFocusedDroneEnabled = false,
                hasFocusedDroneTelemetry = true,
                operatorAdjustedViewport = false
            )
        )
        assertFalse(
            shouldFollowFocusedDrone(
                presentationMode = MapPanePresentationMode.Inset,
                followFocusedDroneEnabled = true,
                hasFocusedDroneTelemetry = false,
                operatorAdjustedViewport = false
            )
        )
    }

    @Test
    fun shouldFollowFocusedDrone_respectsOperatorMovementOnlyOutsideInset() {
        assertFalse(
            shouldFollowFocusedDrone(
                presentationMode = MapPanePresentationMode.Full,
                followFocusedDroneEnabled = true,
                hasFocusedDroneTelemetry = true,
                operatorAdjustedViewport = true
            )
        )
        assertTrue(
            shouldFollowFocusedDrone(
                presentationMode = MapPanePresentationMode.Inset,
                followFocusedDroneEnabled = true,
                hasFocusedDroneTelemetry = true,
                operatorAdjustedViewport = true
            )
        )
    }
}
