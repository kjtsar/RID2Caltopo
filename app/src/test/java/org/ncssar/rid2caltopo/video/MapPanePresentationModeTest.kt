package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPanePresentationModeTest {
    @Test
    fun extractedViewportAndStartupConstants_remainSharedNamedDeclarations() {
        assertEquals(500L, INSET_FOLLOW_INTERVAL_MS)
        assertEquals(1.0, INSET_FOLLOW_MIN_MOVE_METERS, 0.0)
        assertEquals(60_000L, STARTUP_MY_LOCATION_FRESH_MS)
        assertEquals(20_000L, STARTUP_MY_LOCATION_WAIT_MS)
        assertEquals(14.0, STARTUP_MY_LOCATION_MIN_ZOOM, 0.0)
        assertEquals(20_037_508.342789244, WEB_MERCATOR_HALF_WORLD_METERS, 0.0)
    }

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
    fun mapPaneInsetViewportZoom_clampsToBaseLayerMaximumZoom() {
        assertEquals(
            19.0,
            mapPaneInsetViewportZoom(
                fullWidthPx = 1280,
                fullHeightPx = 720,
                insetWidthPx = 320,
                insetHeightPx = 180,
                fullZoom = 22.0,
                maxZoom = 19.0
            ),
            0.0001
        )
    }

    @Test
    fun mapPaneInitialViewportZoom_clampsInsetModeToBaseLayerMaximumZoom() {
        assertEquals(
            19.0,
            mapPaneInitialViewportZoom(
                presentationMode = MapPanePresentationMode.Inset,
                restoredZoom = 22.0,
                maxZoom = 19.0
            ),
            0.0001
        )
        assertEquals(
            22.0,
            mapPaneInitialViewportZoom(
                presentationMode = MapPanePresentationMode.Full,
                restoredZoom = 22.0,
                maxZoom = 19.0
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

    @Test
    fun mapPaneArtifactMountPolicy_keepsInsetMapLightweight() {
        assertFalse(
            mapPaneShouldReplayCachedArtifacts(
                presentationMode = MapPanePresentationMode.Inset,
                cachedFeatureCount = 0
            )
        )
        assertFalse(
            mapPaneShouldRequestArtifactRefreshOnMount(
                presentationMode = MapPanePresentationMode.Inset,
                cachedFeatureCount = 0
            )
        )
    }

    @Test
    fun mapPaneArtifactMountPolicy_replaysOnlyWhenFullMapCacheIsEmpty() {
        assertTrue(
            mapPaneShouldReplayCachedArtifacts(
                presentationMode = MapPanePresentationMode.Full,
                cachedFeatureCount = 0
            )
        )
        assertFalse(
            mapPaneShouldReplayCachedArtifacts(
                presentationMode = MapPanePresentationMode.Full,
                cachedFeatureCount = 12
            )
        )
        assertFalse(
            mapPaneShouldRequestArtifactRefreshOnMount(
                presentationMode = MapPanePresentationMode.Full,
                cachedFeatureCount = 0
            )
        )
        assertFalse(
            mapPaneShouldRequestArtifactRefreshOnMount(
                presentationMode = MapPanePresentationMode.Full,
                cachedFeatureCount = 12
            )
        )
    }

    @Test
    fun mapPaneCanZoomToBoundingBox_requiresMeasuredMapAndMultiplePoints() {
        assertFalse(
            mapPaneCanZoomToBoundingBox(
                mapWidthPx = 0,
                mapHeightPx = 720,
                pointCount = 2
            )
        )
        assertFalse(
            mapPaneCanZoomToBoundingBox(
                mapWidthPx = 1280,
                mapHeightPx = 0,
                pointCount = 2
            )
        )
        assertFalse(
            mapPaneCanZoomToBoundingBox(
                mapWidthPx = 1280,
                mapHeightPx = 720,
                pointCount = 1
            )
        )
        assertTrue(
            mapPaneCanZoomToBoundingBox(
                mapWidthPx = 1280,
                mapHeightPx = 720,
                pointCount = 2
            )
        )
    }
}
