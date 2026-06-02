package org.ncssar.rid2caltopo.video

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex

class MapPaneArtifactOverlayStateTest {
    @Test
    fun orderedTileIndexesForOfflinePrep_prioritizesTabletMediumZoomTile() {
        val tablet = GeoPoint(38.9000, -120.0000)
        val bounds = BoundingBox(39.0000, -119.9000, 38.8000, -120.1000)
        val ordered = orderedTileIndexesForOfflinePrep(
            bounds = bounds,
            minZoom = 12,
            maxZoom = 16,
            tabletLocation = tablet
        )

        assertEquals(tileIndexForTestPoint(tablet, 14), ordered.first())
    }

    @Test
    fun orderedTileIndexesForOfflinePrep_prioritizesDronePathTilesBeforeAreaSweep() {
        val tablet = GeoPoint(38.9000, -120.0000)
        val droneStart = GeoPoint(38.8850, -120.0700)
        val droneEnd = GeoPoint(38.9500, -119.9300)
        val bounds = BoundingBox(39.0000, -119.9000, 38.8000, -120.1000)
        val ordered = orderedTileIndexesForOfflinePrep(
            bounds = bounds,
            minZoom = 12,
            maxZoom = 16,
            tabletLocation = tablet,
            dronePathPoints = listOf(droneStart, droneEnd)
        )

        assertEquals(tileIndexForTestPoint(tablet, 14), ordered[0])
        assertEquals(tileIndexForTestPoint(droneStart, 14), ordered[1])
        assertEquals(tileIndexForTestPoint(droneEnd, 14), ordered.take(8).last())
    }

    @Test
    fun orderedTileIndexesForOfflinePrep_usesPreferredDynamicZoomWhenProvided() {
        val tablet = GeoPoint(38.9000, -120.0000)
        val bounds = BoundingBox(39.0000, -119.9000, 38.8000, -120.1000)
        val ordered = orderedTileIndexesForOfflinePrep(
            bounds = bounds,
            minZoom = 12,
            maxZoom = 16,
            tabletLocation = tablet,
            preferredZoom = DynamicZoomFactor.High.zoom
        )

        assertEquals(tileIndexForTestPoint(tablet, 16), ordered.first())
    }

    @Test
    fun dynamicDroneTileRequests_ordersAllCurrentDroneTilesBeforeHeadingTiles() {
        val westDrone = dronePoint("drone-west", 38.9000, -120.0000, headingDeg = 90.0)
        val northDrone = dronePoint("drone-north", 38.9300, -119.9600, headingDeg = 0.0)
        val requests = dynamicDroneTileRequests(listOf(westDrone, northDrone), DynamicZoomFactor.Medium.zoom)

        val westCurrent = tileIndexForTestPoint(GeoPoint(westDrone.lat, westDrone.lng), 14)
        val northCurrent = tileIndexForTestPoint(GeoPoint(northDrone.lat, northDrone.lng), 14)

        assertEquals(westCurrent, requests[0].tileIndex)
        assertEquals(false, requests[0].requiresCurrentCached)
        assertEquals(northCurrent, requests[1].tileIndex)
        assertEquals(false, requests[1].requiresCurrentCached)
        assertEquals(true, requests[2].requiresCurrentCached)
        assertEquals(westCurrent, requests[2].currentTileIndex)
        assertEquals(true, requests[3].requiresCurrentCached)
        assertEquals(northCurrent, requests[3].currentTileIndex)
    }

    @Test
    fun buildArtifactOverlayState_ignoresItemsOutsideRepresentedFoldersAndHiddenBuiltIns() {
        val representedFolder = folderFeature("folder-visible", "Search Segment")
        val visibleLine = lineFeature("line-visible", "Represented Line", "folder-visible")
        val caltopoSystemLine = lineFeature("line-system", "System Line", "Lines & Polygons")
        val folderlessLine = lineFeature("line-folderless", "Folderless Line", "")

        val state = buildArtifactOverlayState(
            listOf(representedFolder, visibleLine, caltopoSystemLine, folderlessLine),
            hiddenFolderIds = setOf("__caltopo_lines_polygons__")
        )

        assertEquals(listOf("line-visible"), state.lines.map { it.id })
    }

    @Test
    fun buildArtifactOverlayState_stillHonorsHiddenRepresentedFolders() {
        val representedFolder = folderFeature("folder-hidden", "Archived Tracks")
        val hiddenLine = lineFeature("line-hidden", "Archived Line", "folder-hidden")

        val state = buildArtifactOverlayState(
            listOf(representedFolder, hiddenLine),
            hiddenFolderIds = setOf("folder-hidden")
        )

        assertEquals(0, state.lines.size)
    }

    @Test
    fun buildArtifactOverlayState_rendersFolderlessAssignments() {
        val assignment = assignmentPolygonFeature("assignment-aa", "AA")

        val state = buildArtifactOverlayState(listOf(assignment))

        assertEquals(listOf("assignment-aa"), state.polygons.map { it.id })
        assertEquals(0, state.lines.size)
    }

    @Test
    fun buildArtifactOverlayState_rendersUnnamedItemsInUnlistedFolders() {
        val line = lineFeature("assignment-shape", "", "folder-only-in-feature")

        val state = buildArtifactOverlayState(listOf(line))

        assertEquals(listOf("assignment-shape"), state.lines.map { it.id })
        assertEquals(listOf("Shape:assignment-shape"), state.lines.map { it.title })
    }

    @Test
    fun buildArtifactOverlayState_preservesFolderlessUnknownGeometry() {
        val line = unknownLineFeature("field-geometry", "")

        val state = buildArtifactOverlayState(listOf(line))

        assertEquals(listOf("field-geometry"), state.lines.map { it.id })
        assertEquals(listOf("FieldGeometry:field-geometry"), state.lines.map { it.title })
    }

    @Test
    fun buildMapFolderUiStates_listsUnnamedItemsInUnlistedFolders() {
        val line = lineFeature("assignment-shape", "", "folder-only-in-feature")

        val folders = buildMapFolderUiStates(mapOf("assignment-shape" to line))

        assertEquals(listOf("Unlisted Folder folder-o"), folders.map { it.title })
        assertEquals(true, folders.single().initiallyVisible)
        assertEquals(listOf("Shape:assignment-shape"), folders.single().items.map { it.title })
    }

    @Test
    fun buildMapFolderUiStates_listsFolderlessUnknownGeometry() {
        val line = unknownLineFeature("field-geometry", "")

        val folders = buildMapFolderUiStates(mapOf("field-geometry" to line))

        assertEquals(listOf("Other Map Items"), folders.map { it.title })
        assertEquals(true, folders.single().initiallyVisible)
        assertEquals(listOf("FieldGeometry:field-geometry"), folders.single().items.map { it.title })
    }

    @Test
    fun buildArtifactOverlayState_honorsHiddenAssignmentsGroup() {
        val assignment = assignmentPolygonFeature("assignment-aa", "AA")

        val state = buildArtifactOverlayState(
            listOf(assignment),
            hiddenFolderIds = setOf("__caltopo_assignments__")
        )

        assertEquals(0, state.polygons.size)
    }

    @Test
    fun buildArtifactOverlayState_rendersBuiltInFoldersWhenVisible() {
        val line = lineFeature("line-system", "System Line", "Lines & Polygons")
        val rangeRing = lineFeature("range-ring", "1 mi", "Range Rings")
        val marker = markerFeature("marker-system", "System Marker", "")
        val appTrack = appTrackFeature("app-track", "Tablet Track")

        val state = buildArtifactOverlayState(listOf(line, rangeRing, marker, appTrack))

        assertEquals(listOf("marker-system"), state.points.map { it.id })
        assertEquals(listOf("line-system", "range-ring", "app-track"), state.lines.map { it.id })
    }

    @Test
    fun buildArtifactOverlayState_honorsHiddenBuiltInFolders() {
        val line = lineFeature("line-system", "System Line", "Lines & Polygons")
        val rangeRing = lineFeature("range-ring", "1 mi", "Range Rings")
        val marker = markerFeature("marker-system", "System Marker", "")
        val appTrack = appTrackFeature("app-track", "Tablet Track")

        val state = buildArtifactOverlayState(
            listOf(line, rangeRing, marker, appTrack),
            hiddenFolderIds = setOf(
                "__caltopo_lines_polygons__",
                "__caltopo_range_rings__",
                "__caltopo_markers__",
                "__caltopo_app_tracks__"
            )
        )

        assertEquals(0, state.points.size)
        assertEquals(0, state.lines.size)
    }

    private fun folderFeature(id: String, title: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put(
                "properties",
                JSONObject()
                    .put("class", "Folder")
                    .put("title", title)
            )

    private fun dronePoint(
        designator: String,
        lat: Double,
        lng: Double,
        headingDeg: Double?
    ): DroneMapPoint =
        DroneMapPoint(
            designator = designator,
            remoteId = designator,
            lat = lat,
            lng = lng,
            altitudeM = 0.0,
            timestampMsec = 1L,
            headingDeg = headingDeg
        )

    private fun tileIndexForTestPoint(point: GeoPoint, zoom: Int): Long {
        val maxTile = (1 shl zoom) - 1
        val x = lonToTileXForTest(point.longitude, zoom).coerceIn(0, maxTile)
        val y = latToTileYForTest(point.latitude, zoom).coerceIn(0, maxTile)
        return MapTileIndex.getTileIndex(zoom, x, y)
    }

    private fun lonToTileXForTest(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return kotlin.math.floor((lon + 180.0) / 360.0 * n).toInt()
    }

    private fun latToTileYForTest(lat: Double, zoom: Int): Int {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val latRad = Math.toRadians(clamped)
        val n = 1 shl zoom
        return kotlin.math.floor(
            (1.0 - kotlin.math.ln(kotlin.math.tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / Math.PI) / 2.0 * n
        ).toInt()
    }

    private fun lineFeature(id: String, title: String, folderId: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put(
                "properties",
                JSONObject()
                    .put("class", "Shape")
                    .put("title", title)
                    .put("folderId", folderId)
                    .put("stroke", "#FF5A1F")
            )
            .put(
                "geometry",
                JSONObject()
                    .put("type", "LineString")
                    .put(
                        "coordinates",
                        JSONArray()
                            .put(JSONArray().put(-122.0).put(37.0))
                            .put(JSONArray().put(-122.1).put(37.1))
                    )
            )

    private fun unknownLineFeature(id: String, title: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put(
                "properties",
                JSONObject()
                    .put("class", "FieldGeometry")
                    .put("title", title)
                    .put("stroke", "#FF5A1F")
            )
            .put(
                "geometry",
                JSONObject()
                    .put("type", "LineString")
                    .put(
                        "coordinates",
                        JSONArray()
                            .put(JSONArray().put(-122.0).put(37.0))
                            .put(JSONArray().put(-122.1).put(37.1))
                    )
            )

    private fun assignmentPolygonFeature(id: String, title: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put(
                "properties",
                JSONObject()
                    .put("class", "Assignment")
                    .put("title", title)
                    .put("stroke", "#ff0000")
                    .put("fill", "#ff0000")
                    .put("fill-opacity", 0.2)
            )
            .put(
                "geometry",
                JSONObject()
                    .put("type", "Polygon")
                    .put(
                        "coordinates",
                        JSONArray()
                            .put(
                                JSONArray()
                                    .put(JSONArray().put(-122.0).put(37.0))
                                    .put(JSONArray().put(-122.1).put(37.0))
                                    .put(JSONArray().put(-122.1).put(37.1))
                                    .put(JSONArray().put(-122.0).put(37.0))
                            )
                    )
            )

    private fun markerFeature(id: String, title: String, folderId: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put(
                "properties",
                JSONObject()
                    .put("class", "Marker")
                    .put("title", title)
                    .put("folderId", folderId)
                    .put("marker-color", "#ff0000")
            )
            .put(
                "geometry",
                JSONObject()
                    .put("type", "Point")
                    .put("coordinates", JSONArray().put(-122.0).put(37.0))
            )

    private fun appTrackFeature(id: String, title: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put(
                "properties",
                JSONObject()
                    .put("class", "AppTrack")
                    .put("title", title)
                    .put("stroke", "#00cd00")
            )
            .put(
                "geometry",
                JSONObject()
                    .put("type", "LineString")
                    .put(
                        "coordinates",
                        JSONArray()
                            .put(JSONArray().put(-122.0).put(37.0))
                            .put(JSONArray().put(-122.1).put(37.1))
                    )
            )
}
