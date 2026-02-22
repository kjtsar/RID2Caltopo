package org.ncssar.rid2caltopo.video

import StreamsViewModel
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.location.Location
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.ncssar.rid2caltopo.R
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoLiveTrack
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.MediaMTXStatus
import org.ncssar.rid2caltopo.data.R2CPeer
import org.ncssar.rid2caltopo.ui.ClueSubmissionSheet
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

private const val MAP_PANE_TAG = "SplitMapPane"
private const val LOCAL_DEVICE_SYMBOL = "radiotower"
private const val LOCAL_DEVICE_COLOR = "0000FF"

private enum class ScreenLayoutMode {
    Both,
    Streams,
    Map
}

private enum class BaseLayerOption {
    OpenStreetMap,
    Imagery
}

private data class DroneMapPoint(
    val designator: String,
    val lat: Double,
    val lng: Double,
    val altitudeM: Double
)

private data class ArtifactPointSpec(
    val id: String,
    val lat: Double,
    val lng: Double,
    val title: String,
    val markerSymbol: String,
    val markerColor: String?
)

private data class ArtifactLineSpec(
    val id: String,
    val points: List<GeoPoint>,
    val color: Int,
    val width: Float,
    val title: String
)

private data class ArtifactPolygonSpec(
    val id: String,
    val points: List<GeoPoint>,
    val strokeColor: Int,
    val fillColor: Int,
    val strokeWidth: Float,
    val title: String
)

private data class ArtifactOverlayState(
    val totalFeatures: Int = 0,
    val ignoredTrackLikeFeatures: Int = 0,
    val points: List<ArtifactPointSpec> = emptyList(),
    val lines: List<ArtifactLineSpec> = emptyList(),
    val polygons: List<ArtifactPolygonSpec> = emptyList()
)

private data class LocalTrackPoint(
    val mappedId: String,
    val lat: Double,
    val lng: Double,
    val altitudeM: Double,
    val timestampMsec: Long
)

private object ArcGisWorldImageryTileSource : OnlineTileSourceBase(
    "ArcGIS-WorldImagery",
    0,
    19,
    256,
    ".jpg",
    arrayOf("https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl$zoom/$y/$x${imageFilenameEnding()}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamsScreen(
    viewModel: StreamsViewModel = viewModel(),
    onBack: () -> Unit,
) {
    val serverStatus = MediaMTXStatus.serverStatus
    val mapName = viewModel.mapName
    val mapStatus by remember(mapName) {
        derivedStateOf {
            if (mapName != null) {
                "Connected to $mapName"
            } else {
                "No map connection"
            }
        }
    }

    var splitFraction by remember { mutableFloatStateOf(0.5f) }
    var layoutMode by remember { mutableStateOf(ScreenLayoutMode.Both) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "$serverStatus - $mapStatus",
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LayoutToggleChip(
                            label = "Both",
                            selected = layoutMode == ScreenLayoutMode.Both,
                            onClick = {
                                layoutMode = ScreenLayoutMode.Both
                                if (splitFraction !in 0.1f..0.9f) {
                                    splitFraction = 0.5f
                                }
                            }
                        )
                        LayoutToggleChip(
                            label = "Streams",
                            selected = layoutMode == ScreenLayoutMode.Streams,
                            onClick = { layoutMode = ScreenLayoutMode.Streams }
                        )
                        LayoutToggleChip(
                            label = "Map",
                            selected = layoutMode == ScreenLayoutMode.Map,
                            onClick = { layoutMode = ScreenLayoutMode.Map }
                        )
                    }
                }
            )

            Box(Modifier.fillMaxSize()) {
                when (layoutMode) {
                    ScreenLayoutMode.Both -> {
                        SplitStreamsAndMap(
                            viewModel = viewModel,
                            splitFraction = splitFraction,
                            onSplitFractionChange = { splitFraction = it }
                        )
                    }

                    ScreenLayoutMode.Streams -> {
                        StreamsGrid(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                    }

                    ScreenLayoutMode.Map -> {
                        SplitMapPane(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                    }
                }

                viewModel.pendingClue?.let {
                    ClueSubmissionSheet(
                        pendingClue = it,
                        onTitleChanged = viewModel::updateClueTitle,
                        onDescriptionChanged = viewModel::updateClueDescription,
                        onSubmit = viewModel::submitClue,
                        onCancel = viewModel::clearPendingClue,
                    )
                }
            }
        }
    }
}

@Composable
private fun SplitStreamsAndMap(
    viewModel: StreamsViewModel,
    splitFraction: Float,
    onSplitFractionChange: (Float) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isPortrait = maxHeight > maxWidth
        val dividerThickness = 24.dp
        val dividerLineThickness = 4.dp
        val clamped = splitFraction.coerceIn(0f, 1f)
        val maxHeightPx = maxHeight.value
        val maxWidthPx = maxWidth.value
        val density = LocalDensity.current
        val dividerThicknessDp = dividerThickness
        val dividerHalfDp = dividerThicknessDp / 2
        val dividerLineDp = dividerLineThickness

        if (isPortrait) {
            val dividerOffsetDp = with(density) { (clamped * maxHeightPx).dp - dividerHalfDp }
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(clamped.coerceIn(0f, 1f))
                        .align(Alignment.TopStart)
                ) {
                    StreamsGrid(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight((1f - clamped).coerceIn(0f, 1f))
                        .align(Alignment.BottomStart)
                ) {
                    SplitMapPane(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .offset(y = dividerOffsetDp)
                        .fillMaxWidth()
                        .height(dividerThicknessDp)
                        .background(Color.Transparent)
                        .pointerInput(Unit) {
                            detectDragGestures { _, dragAmount ->
                                val delta = if (maxHeightPx > 0f) dragAmount.y / maxHeightPx else 0f
                                onSplitFractionChange((splitFraction + delta).coerceIn(0f, 1f))
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .height(dividerLineDp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                    )
                }
            }
        } else {
            val dividerOffsetDp = with(density) { (clamped * maxWidthPx).dp - dividerHalfDp }
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(clamped.coerceIn(0f, 1f))
                        .align(Alignment.CenterStart)
                ) {
                    StreamsGrid(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((1f - clamped).coerceIn(0f, 1f))
                        .align(Alignment.CenterEnd)
                ) {
                    SplitMapPane(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .offset(x = dividerOffsetDp)
                        .fillMaxHeight()
                        .width(dividerThicknessDp)
                        .background(Color.Transparent)
                        .pointerInput(Unit) {
                            detectDragGestures { _, dragAmount ->
                                val delta = if (maxWidthPx > 0f) dragAmount.x / maxWidthPx else 0f
                                onSplitFractionChange((splitFraction + delta).coerceIn(0f, 1f))
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxHeight()
                            .width(dividerLineDp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                    )
                }
            }
        }
    }
}

@Composable
private fun LayoutToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SplitMapPane(
    viewModel: StreamsViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    var baseLayer by remember { mutableStateOf(BaseLayerOption.OpenStreetMap) }
    var layerMenuExpanded by remember { mutableStateOf(false) }
    val mapName = viewModel.mapName
    val artifactStoreById = remember { LinkedHashMap<String, JSONObject>() }
    val localTrackPointsByMappedId = remember { LinkedHashMap<String, MutableList<LocalTrackPoint>>() }
    val managedOverlays = remember { mutableListOf<Overlay>() }
    var artifactOverlayState by remember { mutableStateOf(ArtifactOverlayState()) }
    var lastRenderStats by remember { mutableStateOf("") }
    var lastAlignmentStats by remember { mutableStateOf("") }
    var initialViewportApplied by remember { mutableStateOf(false) }
    var initialViewportArtifactCount by remember { mutableStateOf(-1) }
    val droneMarkerIcon = remember(context) { ContextCompat.getDrawable(context, R.drawable.ic_drone_marker) }
    val symbolMarkerCache = remember { LinkedHashMap<String, Drawable>() }
    val caltopoMarkerCache = remember { mutableStateMapOf<String, Drawable>() }
    val caltopoMarkerPending = remember { HashSet<String>() }
    val unknownSymbolsSeen = remember { LinkedHashSet<String>() }
    val focusedPath by viewModel.focusedPath.collectAsStateWithLifecycle()
    val dronePoints = viewModel.droneStates.mapNotNull { (designator, state) ->
        val lat = state.lastLat
        val lng = state.lastLng
        if (lat == 0.0 && lng == 0.0) {
            null
        } else {
            DroneMapPoint(
                designator = designator,
                lat = lat,
                lng = lng,
                altitudeM = state.lastAlt
            )
        }
    }

    fun hydrateArtifactsFromCaltopoSnapshot(reason: String) {
        val snapshot = CaltopoMap.GetArtifactFeatureSnapshot()
        val shouldReplace = snapshot.isNotEmpty() || artifactStoreById.isEmpty()
        if (!shouldReplace) {
            CTDebug(
                MAP_PANE_TAG,
                "Skipping snapshot hydrate ($reason): snapshot empty while local artifact store has ${artifactStoreById.size} features."
            )
            return
        }
        artifactStoreById.clear()
        snapshot.forEach { feature ->
            val featureId = feature.optString("id")
            if (featureId.isNotBlank()) {
                artifactStoreById[featureId] = feature
            }
        }
        artifactOverlayState = buildArtifactOverlayState(artifactStoreById.values)
        CTDebug(
            MAP_PANE_TAG,
            "Hydrated artifacts from snapshot ($reason): cached=${snapshot.size}, renderable=${artifactOverlayState.totalFeatures}"
        )
    }

    LaunchedEffect(mapName) {
        hydrateArtifactsFromCaltopoSnapshot("mapName=$mapName")
        localTrackPointsByMappedId.clear()
        lastRenderStats = ""
        lastAlignmentStats = ""
        initialViewportApplied = false
        initialViewportArtifactCount = -1
    }

    DisposableEffect(Unit) {
        hydrateArtifactsFromCaltopoSnapshot("listener-init")
        val listener = CaltopoMap.ArtifactListener { feature, source, _ ->
            uiScope.launch(Dispatchers.Main.immediate) {
                val featureId = feature.optString("id")
                if (featureId.isBlank()) {
                    return@launch
                }

                if (isArtifactDelete(feature)) {
                    artifactStoreById.remove(featureId)
                } else {
                    artifactStoreById[featureId] = feature
                }

                artifactOverlayState = buildArtifactOverlayState(artifactStoreById.values)
                CTDebug(
                    MAP_PANE_TAG,
                    "Artifact ingest source=$source id=$featureId total=${artifactOverlayState.totalFeatures} " +
                        "points=${artifactOverlayState.points.size} lines=${artifactOverlayState.lines.size} " +
                        "polygons=${artifactOverlayState.polygons.size} ignoredTrackLike=${artifactOverlayState.ignoredTrackLikeFeatures}"
                )
            }
        }

        CaltopoMap.AddArtifactListener(listener)
        CaltopoMap.RequestMapRefreshNow()
        onDispose {
            CaltopoMap.RemoveArtifactListener(listener)
        }
    }

    DisposableEffect(Unit) {
        val localTrackListener = CaltopoLiveTrack.LocalTrackListener { _, mappedId, lat, lng, altitudeMeters, timestampMsec ->
            uiScope.launch(Dispatchers.Main.immediate) {
                if (!lat.isFinite() || !lng.isFinite()) return@launch
                if (lat == 0.0 && lng == 0.0) return@launch
                val key = mappedId.ifBlank { "unmapped" }
                val list = localTrackPointsByMappedId.getOrPut(key) { mutableListOf() }
                val point = LocalTrackPoint(key, lat, lng, altitudeMeters, timestampMsec)
                list.add(point)
                if (list.size > 500) {
                    list.removeAt(0)
                }
                CTDebug(
                    MAP_PANE_TAG,
                    "Local track ingest: mappedId=$key points=${list.size} ts=$timestampMsec"
                )
            }
        }
        CaltopoLiveTrack.AddLocalTrackListener(localTrackListener)
        onDispose {
            CaltopoLiveTrack.RemoveLocalTrackListener(localTrackListener)
        }
    }

    Box(
        modifier = modifier
            .background(Color(0xFF1E222A))
            .clipToBounds()
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            factory = {
                Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))
                Configuration.getInstance().userAgentValue = context.packageName
                MapView(context).apply {
                    setMultiTouchControls(true)
                    setTileSource(TileSourceFactory.MAPNIK)
                    controller.setZoom(14.0)
                }
            },
            update = { mapView ->
                val tileSource = when (baseLayer) {
                    BaseLayerOption.OpenStreetMap -> TileSourceFactory.MAPNIK
                    BaseLayerOption.Imagery -> ArcGisWorldImageryTileSource
                }
                if (mapView.tileProvider.tileSource.name() != tileSource.name()) {
                    mapView.setTileSource(tileSource)
                }

                if (managedOverlays.isNotEmpty()) {
                    mapView.overlays.removeAll(managedOverlays)
                    managedOverlays.clear()
                }

                artifactOverlayState.polygons.forEach { polygonSpec ->
                    val polygon = Polygon(mapView).apply {
                        points = polygonSpec.points
                        title = polygonSpec.title
                        strokeColor = polygonSpec.strokeColor
                        fillColor = polygonSpec.fillColor
                        strokeWidth = polygonSpec.strokeWidth
                    }
                    mapView.overlays.add(polygon)
                    managedOverlays.add(polygon)
                }

                artifactOverlayState.lines.forEach { lineSpec ->
                    val line = Polyline(mapView).apply {
                        setPoints(lineSpec.points)
                        title = lineSpec.title
                        color = lineSpec.color
                        width = lineSpec.width
                    }
                    mapView.overlays.add(line)
                    managedOverlays.add(line)
                }

                localTrackPointsByMappedId.forEach { (mappedId, points) ->
                    if (points.size < 2) return@forEach
                    val line = Polyline(mapView).apply {
                        setPoints(points.map { GeoPoint(it.lat, it.lng) })
                        title = "Local track: $mappedId (${points.size})"
                        color = AndroidColor.parseColor("#1E88E5")
                        width = 4.0f
                    }
                    mapView.overlays.add(line)
                    managedOverlays.add(line)
                }

                artifactOverlayState.points.forEach { point ->
                    val remoteCacheKey = caltopoMarkerCacheKey(point.markerSymbol, point.markerColor)
                    val remoteIcon = caltopoMarkerCache[remoteCacheKey]
                    if (remoteIcon == null && !caltopoMarkerPending.contains(remoteCacheKey)) {
                        caltopoMarkerPending.add(remoteCacheKey)
                        val iconUrl = caltopoIconUrl(point.markerSymbol, point.markerColor)
                        uiScope.launch(Dispatchers.IO) {
                            val loaded = tryLoadRemoteIconDrawable(context.resources, iconUrl)
                            withContext(Dispatchers.Main.immediate) {
                                if (loaded != null) {
                                    caltopoMarkerCache[remoteCacheKey] = loaded
                                }
                                caltopoMarkerPending.remove(remoteCacheKey)
                            }
                        }
                    }
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(point.lat, point.lng)
                        icon = (remoteIcon?.constantState?.newDrawable(context.resources)?.mutate())
                            ?: markerIconForArtifactSymbol(
                                resources = context.resources,
                                symbol = point.markerSymbol,
                                colorHex = point.markerColor,
                                cache = symbolMarkerCache
                            )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = point.title
                    }
                    if (!isKnownArtifactSymbol(point.markerSymbol) && unknownSymbolsSeen.add(point.markerSymbol)) {
                        CTDebug(MAP_PANE_TAG, "Unknown marker-symbol encountered: '${point.markerSymbol}'")
                    }
                    mapView.overlays.add(marker)
                    managedOverlays.add(marker)
                }

                val myLocation = CaltopoMap.MyLocation
                if (myLocation != null && myLocation.latitude.isFinite() && myLocation.longitude.isFinite()) {
                    val localCacheKey = caltopoMarkerCacheKey(LOCAL_DEVICE_SYMBOL, LOCAL_DEVICE_COLOR)
                    val localRemoteIcon = caltopoMarkerCache[localCacheKey]
                    if (localRemoteIcon == null && !caltopoMarkerPending.contains(localCacheKey)) {
                        caltopoMarkerPending.add(localCacheKey)
                        val iconUrl = caltopoIconUrl(LOCAL_DEVICE_SYMBOL, LOCAL_DEVICE_COLOR)
                        uiScope.launch(Dispatchers.IO) {
                            val loaded = tryLoadRemoteIconDrawable(context.resources, iconUrl)
                            withContext(Dispatchers.Main.immediate) {
                                if (loaded != null) {
                                    caltopoMarkerCache[localCacheKey] = loaded
                                }
                                caltopoMarkerPending.remove(localCacheKey)
                            }
                        }
                    }
                    val localMarker = Marker(mapView).apply {
                        position = GeoPoint(myLocation.latitude, myLocation.longitude)
                        icon = (localRemoteIcon?.constantState?.newDrawable(context.resources)?.mutate())
                            ?: markerIconForArtifactSymbol(
                                resources = context.resources,
                                symbol = LOCAL_DEVICE_SYMBOL,
                                colorHex = LOCAL_DEVICE_COLOR,
                                cache = symbolMarkerCache
                            )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "RID2Caltopo Device"
                    }
                    mapView.overlays.add(localMarker)
                    managedOverlays.add(localMarker)
                }

                dronePoints.forEach { point ->
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(point.lat, point.lng)
                        icon = droneMarkerIcon?.constantState?.newDrawable()?.mutate()
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "${point.designator} alt=${"%.0f".format(point.altitudeM)}m"
                    }
                    mapView.overlays.add(marker)
                    managedOverlays.add(marker)
                }

                val renderStats =
                    "features=${artifactOverlayState.totalFeatures} points=${artifactOverlayState.points.size} " +
                        "lines=${artifactOverlayState.lines.size} polygons=${artifactOverlayState.polygons.size} " +
                        "ignoredTrackLike=${artifactOverlayState.ignoredTrackLikeFeatures} " +
                        "localTracks=${localTrackPointsByMappedId.size} drones=${dronePoints.size}"
                if (renderStats != lastRenderStats) {
                    lastRenderStats = renderStats
                    CTDebug(MAP_PANE_TAG, "Artifact render stats: $renderStats")
                }

                val focusPoint = dronePoints.firstOrNull { it.designator == focusedPath }
                    ?: dronePoints.firstOrNull()
                if (focusPoint != null) {
                    val nearestArtifactMeters =
                        nearestDistanceMeters(focusPoint, allArtifactGeoPoints(artifactOverlayState))
                    val tailDeltaMeters = nearestLocalTrackTailDistanceMeters(focusPoint, localTrackPointsByMappedId)
                    val alignStats = if (nearestArtifactMeters != null) {
                        "focus=${focusPoint.designator} lat=${"%.6f".format(focusPoint.lat)} " +
                            "lng=${"%.6f".format(focusPoint.lng)} nearestArtifactM=${"%.1f".format(nearestArtifactMeters)} " +
                            "localTailDeltaM=${tailDeltaMeters?.let { "%.1f".format(it) } ?: "n/a"} " +
                            "artifactFeatures=${artifactOverlayState.totalFeatures}"
                    } else {
                        "focus=${focusPoint.designator} lat=${"%.6f".format(focusPoint.lat)} " +
                            "lng=${"%.6f".format(focusPoint.lng)} nearestArtifactM=n/a " +
                            "localTailDeltaM=${tailDeltaMeters?.let { "%.1f".format(it) } ?: "n/a"} " +
                            "artifactFeatures=${artifactOverlayState.totalFeatures}"
                    }
                    if (alignStats != lastAlignmentStats) {
                        lastAlignmentStats = alignStats
                        CTDebug(MAP_PANE_TAG, "Drone alignment: $alignStats")
                    }
                }

                val shouldApplyInitialViewport =
                    !initialViewportApplied ||
                        (initialViewportArtifactCount == 0 && artifactOverlayState.totalFeatures > 0)
                if (shouldApplyInitialViewport) {
                    val myLocation = CaltopoMap.MyLocation
                    val artifactPoints = allArtifactGeoPoints(artifactOverlayState)
                    val viewportPoints = ArrayList<GeoPoint>(artifactPoints.size + 1).apply {
                        addAll(artifactPoints)
                        if (myLocation != null) add(GeoPoint(myLocation.latitude, myLocation.longitude))
                    }
                    when {
                        viewportPoints.size >= 2 -> {
                            val bounds = boundingBoxFromPoints(viewportPoints)
                            mapView.zoomToBoundingBox(bounds, true, 96)
                            CTDebug(
                                MAP_PANE_TAG,
                                "Initial viewport: myLocation=${myLocation != null}, artifactPts=${artifactPoints.size}, mode=bounds"
                            )
                        }

                        myLocation != null -> {
                            mapView.controller.setCenter(GeoPoint(myLocation.latitude, myLocation.longitude))
                            mapView.controller.setZoom(15.0)
                            CTDebug(MAP_PANE_TAG, "Initial viewport: centered on MyLocation.")
                        }

                        focusPoint != null -> {
                            mapView.controller.setCenter(GeoPoint(focusPoint.lat, focusPoint.lng))
                            mapView.controller.setZoom(14.0)
                            CTDebug(MAP_PANE_TAG, "Initial viewport: fallback to focused drone point.")
                        }
                    }
                    initialViewportApplied = true
                    initialViewportArtifactCount = artifactOverlayState.totalFeatures
                }
                mapView.invalidate()
            }
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                .padding(8.dp)
        ) {
            Text(
                text = CaltopoMap.GetMapName().ifBlank { "Local Incident Map" },
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { layerMenuExpanded = true }) {
                    Text(
                        when (baseLayer) {
                            BaseLayerOption.OpenStreetMap -> "Base: OpenStreetMap"
                            BaseLayerOption.Imagery -> "Base: Imagery"
                        }
                    )
                }
                DropdownMenu(
                    expanded = layerMenuExpanded,
                    onDismissRequest = { layerMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("OpenStreetMap") },
                        onClick = {
                            baseLayer = BaseLayerOption.OpenStreetMap
                            layerMenuExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Imagery") },
                        onClick = {
                            baseLayer = BaseLayerOption.Imagery
                            layerMenuExpanded = false
                        }
                    )
                }
            }
            Text(
                text = "Live RID markers refresh with local telemetry.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Caltopo artifacts rendered: ${artifactOverlayState.totalFeatures}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun buildArtifactOverlayState(features: Collection<JSONObject>): ArtifactOverlayState {
    val points = mutableListOf<ArtifactPointSpec>()
    val lines = mutableListOf<ArtifactLineSpec>()
    val polygons = mutableListOf<ArtifactPolygonSpec>()
    var ignoredTrackLikeFeatures = 0

    for (feature in features) {
        val geometry = feature.optJSONObject("geometry") ?: continue
        val properties = feature.optJSONObject("properties")
        val className = properties?.optString("class").orEmpty()
        if (className == "Folder") continue

        val featureId = feature.optString("id")
        val featureTitle = properties?.optString("title")
            ?.takeIf { it.isNotBlank() }
            ?: "$className:$featureId"
        val markerSymbol = properties?.optString("marker-symbol", "point").orEmpty().ifBlank { "point" }
        val markerColor = properties?.optString("marker-color")
        val trackLikeFeature = isTrackLikeFeature(properties, className)

        val strokeColor = colorFromHex(
            properties?.optString("stroke", properties?.optString("marker-color", "#FF5A1F")),
            "#FF5A1F",
            properties?.optDouble("stroke-opacity", 1.0) ?: 1.0
        )
        val strokeWidth = (properties?.optDouble("stroke-width", 3.0) ?: 3.0).toFloat()
        val fillColor = colorFromHex(
            properties?.optString("fill", "#33FF5A1F"),
            "#33FF5A1F",
            properties?.optDouble("fill-opacity", 0.20) ?: 0.20
        )

        ignoredTrackLikeFeatures += appendGeometryArtifact(
            featureId = featureId,
            featureTitle = featureTitle,
            geometry = geometry,
            strokeColor = strokeColor,
            fillColor = fillColor,
            strokeWidth = strokeWidth,
            markerSymbol = markerSymbol,
            markerColor = markerColor,
            trackLikeFeature = trackLikeFeature,
            pointsOut = points,
            linesOut = lines,
            polygonsOut = polygons
        )
    }

    return ArtifactOverlayState(
        totalFeatures = features.size,
        ignoredTrackLikeFeatures = ignoredTrackLikeFeatures,
        points = points,
        lines = lines,
        polygons = polygons
    )
}

private fun isTrackLikeFeature(properties: JSONObject?, className: String): Boolean {
    if (className == "LiveTrack") return true
    val folderId = properties?.optString("folderId").orEmpty()
    val mapTrackFolderId = CaltopoMap.GetFolderId().orEmpty()
    val mapArchiveFolderId = CaltopoMap.GetArchiveFolderId().orEmpty()
    if (folderId.isNotBlank() && (folderId == mapTrackFolderId || folderId == mapArchiveFolderId)) {
        return true
    }
    return false
}

private fun appendGeometryArtifact(
    featureId: String,
    featureTitle: String,
    geometry: JSONObject,
    strokeColor: Int,
    fillColor: Int,
    strokeWidth: Float,
    markerSymbol: String,
    markerColor: String?,
    trackLikeFeature: Boolean,
    pointsOut: MutableList<ArtifactPointSpec>,
    linesOut: MutableList<ArtifactLineSpec>,
    polygonsOut: MutableList<ArtifactPolygonSpec>
): Int {
    var ignoredTrackLike = 0
    when (geometry.optString("type")) {
        "Point" -> {
            val coords = geometry.optJSONArray("coordinates") ?: return 0
            val geoPoint = geoPointFromLngLat(coords) ?: return 0
            pointsOut += ArtifactPointSpec(
                id = featureId,
                lat = geoPoint.latitude,
                lng = geoPoint.longitude,
                title = featureTitle,
                markerSymbol = markerSymbol,
                markerColor = markerColor
            )
        }

        "LineString" -> {
            if (trackLikeFeature) {
                ignoredTrackLike++
            } else {
                val coords = geometry.optJSONArray("coordinates") ?: return ignoredTrackLike
                val geoPoints = geoPointsFromLine(coords)
                if (geoPoints.isNotEmpty()) {
                    linesOut += ArtifactLineSpec(featureId, geoPoints, strokeColor, strokeWidth, featureTitle)
                }
            }
        }

        "MultiLineString" -> {
            val lineGroups = geometry.optJSONArray("coordinates") ?: return ignoredTrackLike
            for (i in 0 until lineGroups.length()) {
                val lineCoords = lineGroups.optJSONArray(i) ?: continue
                if (trackLikeFeature) {
                    ignoredTrackLike++
                    continue
                }
                val geoPoints = geoPointsFromLine(lineCoords)
                if (geoPoints.isNotEmpty()) {
                    linesOut += ArtifactLineSpec(featureId, geoPoints, strokeColor, strokeWidth, "$featureTitle[$i]")
                }
            }
        }

        "Polygon" -> {
            val coords = geometry.optJSONArray("coordinates") ?: return ignoredTrackLike
            val outerRing = coords.optJSONArray(0) ?: return ignoredTrackLike
            val geoPoints = geoPointsFromLine(outerRing)
            if (geoPoints.isNotEmpty()) {
                polygonsOut += ArtifactPolygonSpec(
                    featureId,
                    geoPoints,
                    strokeColor,
                    fillColor,
                    strokeWidth,
                    featureTitle
                )
            }
        }

        "MultiPolygon" -> {
            val polygonGroups = geometry.optJSONArray("coordinates") ?: return ignoredTrackLike
            for (i in 0 until polygonGroups.length()) {
                val polygonCoords = polygonGroups.optJSONArray(i) ?: continue
                val outerRing = polygonCoords.optJSONArray(0) ?: continue
                val geoPoints = geoPointsFromLine(outerRing)
                if (geoPoints.isNotEmpty()) {
                    polygonsOut += ArtifactPolygonSpec(
                        featureId,
                        geoPoints,
                        strokeColor,
                        fillColor,
                        strokeWidth,
                        "$featureTitle[$i]"
                    )
                }
            }
        }

        "GeometryCollection" -> {
            val geometries = geometry.optJSONArray("geometries") ?: return ignoredTrackLike
            for (i in 0 until geometries.length()) {
                val nested = geometries.optJSONObject(i) ?: continue
                ignoredTrackLike += appendGeometryArtifact(
                    featureId = featureId,
                    featureTitle = "$featureTitle[$i]",
                    geometry = nested,
                    strokeColor = strokeColor,
                    fillColor = fillColor,
                    strokeWidth = strokeWidth,
                    markerSymbol = markerSymbol,
                    markerColor = markerColor,
                    trackLikeFeature = trackLikeFeature,
                    pointsOut = pointsOut,
                    linesOut = linesOut,
                    polygonsOut = polygonsOut
                )
            }
        }
    }
    return ignoredTrackLike
}

private fun isArtifactDelete(feature: JSONObject): Boolean {
    if (feature.optBoolean("deleted", false)) return true
    val props = feature.optJSONObject("properties")
    if (props == null) {
        return feature.has("id") && !feature.has("geometry")
    }
    if (props.optBoolean("deleted", false)) return true
    val action = props.optString("action")
    return action.equals("delete", ignoreCase = true) || action.equals("removed", ignoreCase = true)
}

private fun geoPointsFromLine(coords: JSONArray): List<GeoPoint> {
    val points = mutableListOf<GeoPoint>()
    for (i in 0 until coords.length()) {
        val pair = coords.optJSONArray(i) ?: continue
        val geoPoint = geoPointFromLngLat(pair) ?: continue
        points += geoPoint
    }
    return points
}

private fun geoPointFromLngLat(coords: JSONArray): GeoPoint? {
    if (coords.length() < 2) return null
    val lng = coords.optDouble(0, Double.NaN)
    val lat = coords.optDouble(1, Double.NaN)
    if (!lat.isFinite() || !lng.isFinite()) return null
    return GeoPoint(lat, lng)
}

private fun colorFromHex(colorHex: String?, fallbackHex: String, opacity: Double): Int {
    val base = try {
        AndroidColor.parseColor(colorHex ?: fallbackHex)
    } catch (_: IllegalArgumentException) {
        AndroidColor.parseColor(fallbackHex)
    }
    val alpha = (opacity.coerceIn(0.0, 1.0) * 255.0).toInt()
    return AndroidColor.argb(alpha, AndroidColor.red(base), AndroidColor.green(base), AndroidColor.blue(base))
}

private fun allArtifactGeoPoints(state: ArtifactOverlayState): List<GeoPoint> {
    val points = mutableListOf<GeoPoint>()
    state.points.forEach { points += GeoPoint(it.lat, it.lng) }
    state.lines.forEach { line -> points += line.points }
    state.polygons.forEach { polygon -> points += polygon.points }
    return points
}

private fun boundingBoxFromPoints(points: List<GeoPoint>): BoundingBox {
    var minLat = Double.POSITIVE_INFINITY
    var maxLat = Double.NEGATIVE_INFINITY
    var minLon = Double.POSITIVE_INFINITY
    var maxLon = Double.NEGATIVE_INFINITY
    points.forEach { p ->
        minLat = minOf(minLat, p.latitude)
        maxLat = maxOf(maxLat, p.latitude)
        minLon = minOf(minLon, p.longitude)
        maxLon = maxOf(maxLon, p.longitude)
    }
    return BoundingBox(maxLat, maxLon, minLat, minLon)
}

private fun nearestDistanceMeters(dronePoint: DroneMapPoint, artifactPoints: List<GeoPoint>): Double? {
    if (artifactPoints.isEmpty()) return null
    var best = Double.POSITIVE_INFINITY
    val result = FloatArray(1)
    for (artifactPoint in artifactPoints) {
        Location.distanceBetween(
            dronePoint.lat,
            dronePoint.lng,
            artifactPoint.latitude,
            artifactPoint.longitude,
            result
        )
        if (result[0].isFinite()) {
            best = minOf(best, result[0].toDouble())
        }
    }
    return if (best.isFinite()) best else null
}

private fun nearestLocalTrackTailDistanceMeters(
    dronePoint: DroneMapPoint,
    tracksByMappedId: Map<String, List<LocalTrackPoint>>
): Double? {
    var best: Double? = null
    tracksByMappedId.values.forEach { points ->
        val tail = points.lastOrNull() ?: return@forEach
        val result = FloatArray(1)
        Location.distanceBetween(
            dronePoint.lat,
            dronePoint.lng,
            tail.lat,
            tail.lng,
            result
        )
        if (result[0].isFinite()) {
            val value = result[0].toDouble()
            best = if (best == null) value else minOf(best!!, value)
        }
    }
    return best
}

private fun isKnownArtifactSymbol(symbol: String): Boolean {
    return symbolGlyphForMarkerSymbol(symbol) != null
}

private fun markerIconForArtifactSymbol(
    resources: android.content.res.Resources,
    symbol: String,
    colorHex: String?,
    cache: MutableMap<String, Drawable>
): Drawable {
    val normalizedSymbol = symbol.ifBlank { "point" }
    val normalizedColor = normalizeMarkerColor(colorHex, normalizedSymbol)
    val cacheKey = "$normalizedSymbol|$normalizedColor"
    val cached = cache[cacheKey]
    if (cached != null) {
        return cached.constantState?.newDrawable(resources)?.mutate() ?: cached
    }

    val icon = buildCaltopoLikeSymbolDrawable(resources, normalizedSymbol, normalizedColor)
    cache[cacheKey] = icon
    return icon.constantState?.newDrawable(resources)?.mutate() ?: icon
}

private fun caltopoMarkerCacheKey(symbol: String, colorHex: String?): String {
    return "${symbol.ifBlank { "point" }}|${colorHex?.trim().orEmpty()}"
}

private fun caltopoIconUrl(symbol: String, colorHex: String?): String {
    val normalizedSymbol = symbol.ifBlank { "point" }
    val color = colorHex
        ?.trim()
        ?.removePrefix("#")
        ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    val cfg = if (color != null) "$normalizedSymbol,$color" else normalizedSymbol
    val encodedCfg = URLEncoder.encode(cfg, StandardCharsets.UTF_8.toString())
    return "https://caltopo.com/icon@2x.png?cfg=$encodedCfg"
}

private fun tryLoadRemoteIconDrawable(
    resources: android.content.res.Resources,
    iconUrl: String
): Drawable? {
    return try {
        URL(iconUrl).openStream().use { stream ->
            val bitmap = BitmapFactory.decodeStream(stream) ?: return null
            BitmapDrawable(resources, bitmap)
        }
    } catch (_: Exception) {
        null
    }
}

private fun symbolGlyphForMarkerSymbol(symbol: String): String? {
    return when (symbol.lowercase()) {
        "point" -> "\u2022"
        "c:ring" -> "\u25cb"
        "c:target1" -> "1"
        "c:target2" -> "2"
        "c:target3" -> "3"
        "cp" -> "CP"
        "clue" -> "?"
        "heatsource" -> "HS"
        "fire-hotspot" -> "HOT"
        "medevac-site" -> "+"
        "hut" -> "\u2302"
        "camping" -> "CAMP"
        "radiotower" -> "RT"
        "waterfalls" -> "WF"
        "fuel" -> "F"
        "automobile" -> "CAR"
        "4wd" -> "4W"
        else -> null
    }
}

private fun fallbackGlyphForSymbol(symbol: String): String {
    val compact = symbol.replace("[^A-Za-z0-9]".toRegex(), "").uppercase()
    return when {
        compact.length >= 2 -> compact.substring(0, 2)
        compact.isNotEmpty() -> compact
        else -> "?"
    }
}

private fun normalizeMarkerColor(colorHex: String?, symbol: String): Int {
    val raw = colorHex?.trim().orEmpty()
    if (raw.isEmpty() || raw.equals("null", ignoreCase = true)) {
        return when (symbol.lowercase()) {
            "cp", "clue", "medevac-site" -> AndroidColor.parseColor("#2D4FAE")
            "heatsource", "fire-hotspot", "c:ring", "c:target1", "c:target2", "c:target3", "point" ->
                AndroidColor.parseColor("#FF1B1B")
            else -> AndroidColor.parseColor("#111111")
        }
    }
    val prefixed = if (raw.startsWith("#")) raw else "#$raw"
    return try {
        AndroidColor.parseColor(prefixed)
    } catch (_: IllegalArgumentException) {
        AndroidColor.parseColor("#111111")
    }
}

private fun buildCaltopoLikeSymbolDrawable(
    resources: android.content.res.Resources,
    symbol: String,
    fillColor: Int
): Drawable {
    val sizePx = 56
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = sizePx / 2f
    val cy = sizePx / 2f

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = fillColor
        strokeWidth = 4f
    }
    val whiteStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = AndroidColor.WHITE
        strokeWidth = 4f
    }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val black = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AndroidColor.BLACK
    }
    val blackStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = AndroidColor.BLACK
        strokeWidth = 3f
    }

    when (symbol.lowercase()) {
        "point" -> canvas.drawCircle(cx, cy, 8f, fill)
        "c:ring" -> canvas.drawCircle(cx, cy, 10f, stroke)
        "c:target1" -> {
            canvas.drawCircle(cx, cy, 11f, stroke)
            canvas.drawCircle(cx, cy, 2.5f, fill)
        }
        "c:target2" -> {
            canvas.drawCircle(cx, cy, 11f, stroke)
            canvas.drawLine(cx - 16, cy, cx + 16, cy, stroke)
            canvas.drawLine(cx, cy - 16, cx, cy + 16, stroke)
        }
        "c:target3" -> {
            canvas.drawCircle(cx, cy, 8f, stroke)
            canvas.drawCircle(cx, cy, 14f, stroke)
            canvas.drawLine(cx - 16, cy, cx + 16, cy, stroke)
            canvas.drawLine(cx, cy - 16, cx, cy + 16, stroke)
        }
        "cp" -> {
            val p = Path().apply {
                moveTo(cx - 12, cy - 12)
                lineTo(cx + 12, cy - 12)
                lineTo(cx + 12, cy + 12)
                lineTo(cx - 12, cy + 12)
                close()
            }
            canvas.drawPath(p, fill)
            canvas.drawLine(cx - 10, cy + 10, cx + 10, cy - 10, whiteStroke)
        }
        "clue" -> {
            canvas.drawCircle(cx, cy, 11.5f, fill)
            text.textSize = 22f
            val bounds = Rect()
            text.getTextBounds("?", 0, 1, bounds)
            canvas.drawText("?", cx, cy + bounds.height() / 2f, text)
        }
        "heatsource" -> {
            canvas.drawCircle(cx, cy, 11.5f, stroke)
            canvas.drawLine(cx - 8, cy - 8, cx + 8, cy + 8, stroke)
            canvas.drawLine(cx + 8, cy - 8, cx - 8, cy + 8, stroke)
        }
        "fire-hotspot" -> {
            canvas.drawCircle(cx, cy, 11.5f, stroke)
            canvas.drawCircle(cx, cy, 4.5f, fill)
        }
        "medevac-site" -> {
            stroke.color = AndroidColor.parseColor("#2D4FAE")
            canvas.drawCircle(cx, cy, 11.5f, stroke)
            fill.color = AndroidColor.parseColor("#E61E2B")
            canvas.drawRect(cx - 2, cy - 7, cx + 2, cy + 7, fill)
            canvas.drawRect(cx - 7, cy - 2, cx + 7, cy + 2, fill)
        }
        "hut" -> {
            val roof = Path().apply {
                moveTo(cx - 11, cy + 2)
                lineTo(cx, cy - 10)
                lineTo(cx + 11, cy + 2)
                close()
            }
            canvas.drawPath(roof, black)
            canvas.drawRect(cx - 9, cy + 2, cx + 9, cy + 12, black)
            val door = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE }
            canvas.drawRect(cx - 2, cy + 6, cx + 2, cy + 12, door)
        }
        "camping" -> {
            val tent = Path().apply {
                moveTo(cx - 12, cy + 10)
                lineTo(cx - 1, cy - 10)
                lineTo(cx + 12, cy + 10)
                close()
            }
            canvas.drawPath(tent, blackStroke)
            canvas.drawLine(cx - 2, cy + 10, cx + 3, cy + 2, blackStroke)
        }
        "radiotower" -> {
            canvas.drawLine(cx, cy - 12, cx - 5, cy + 12, blackStroke)
            canvas.drawLine(cx, cy - 12, cx + 5, cy + 12, blackStroke)
            canvas.drawLine(cx - 4, cy + 2, cx + 4, cy + 2, blackStroke)
            canvas.drawLine(cx - 6, cy + 12, cx + 6, cy + 12, blackStroke)
            canvas.drawArc(cx - 14, cy - 12, cx - 2, cy, -70f, 140f, false, blackStroke)
            canvas.drawArc(cx + 2, cy - 12, cx + 14, cy, 110f, 140f, false, blackStroke)
        }
        "waterfalls" -> {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
            }
            canvas.drawLine(cx - 8, cy - 10, cx - 8, cy + 6, p)
            canvas.drawLine(cx - 2, cy - 10, cx - 2, cy + 4, p)
            canvas.drawLine(cx + 4, cy - 10, cx + 4, cy + 7, p)
            canvas.drawArc(cx - 12, cy + 2, cx + 10, cy + 16, 200f, 140f, false, p)
        }
        "fuel" -> {
            canvas.drawRect(cx - 8, cy - 10, cx + 4, cy + 10, blackStroke)
            canvas.drawLine(cx + 4, cy - 8, cx + 10, cy - 8, blackStroke)
            canvas.drawLine(cx + 10, cy - 8, cx + 10, cy + 4, blackStroke)
            canvas.drawLine(cx + 10, cy + 4, cx + 6, cy + 4, blackStroke)
            canvas.drawLine(cx - 10, cy + 12, cx + 10, cy + 12, blackStroke)
        }
        "automobile", "4wd" -> {
            val y = cy + 4
            val body = Path().apply {
                moveTo(cx - 12, y)
                lineTo(cx - 7, y - 6)
                lineTo(cx + 5, y - 6)
                lineTo(cx + 12, y)
                lineTo(cx + 12, y + 5)
                lineTo(cx - 12, y + 5)
                close()
            }
            canvas.drawPath(body, black)
            canvas.drawCircle(cx - 7, y + 6, 3f, black)
            canvas.drawCircle(cx + 7, y + 6, 3f, black)
            if (symbol.lowercase() == "4wd") {
                canvas.drawRect(cx - 2, y - 12, cx + 2, y - 6, black)
            }
        }
        else -> {
            val glyph = symbolGlyphForMarkerSymbol(symbol) ?: fallbackGlyphForSymbol(symbol)
            canvas.drawCircle(cx, cy, sizePx * 0.38f, fill)
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = AndroidColor.WHITE
                strokeWidth = 3f
            }
            canvas.drawCircle(cx, cy, sizePx * 0.38f, border)
            text.textSize = if (glyph.length > 2) 14f else 18f
            val bounds = Rect()
            text.getTextBounds(glyph, 0, glyph.length, bounds)
            canvas.drawText(glyph, cx, cy + bounds.height() / 2f, text)
        }
    }

    return BitmapDrawable(resources, bitmap)
}

fun <T> List<T>.padTo(size: Int): List<T?> =
    this + List(size - this.size) { null }

@Composable
private fun StreamsGrid(
    viewModel: StreamsViewModel,
    modifier: Modifier = Modifier
) {
    val tag = "StreamsGrid"
    val streams by viewModel.streams.collectAsStateWithLifecycle()
    val streamEntries = streams.entries.toList()
    val mapName = viewModel.mapName
    val mapStatus by remember(mapName) {
        derivedStateOf {
            if (mapName != null) {
                "Connected to $mapName"
            } else {
                "No map connection"
            }
        }
    }
    val focusedPath by viewModel.focusedPath.collectAsStateWithLifecycle()
    val visibleEntries =
        if (focusedPath != null) {
            streamEntries.filter { it.key == focusedPath }
        } else {
            streamEntries
        }

    if (visibleEntries.isEmpty()) {
        CTDebug(tag, "No streams to show.")
        EmptyStreamsView(mapStatus = mapStatus, modifier = modifier)
        return
    }

    if (focusedPath == null && visibleEntries.count() == 1) {
        viewModel.toggleFocus(visibleEntries[0].key)
    }

    val columns = if (visibleEntries.size <= 2) 1 else 2
    val rows = when (visibleEntries.size) {
        0, 1 -> 1
        2 -> 2
        else -> 2
    }

    BoxWithConstraints(modifier = modifier) {
        val cellHeight = maxHeight / rows

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = false,
            contentPadding = PaddingValues(4.dp)
        ) {
            items(
                items = visibleEntries,
                key = { it.key }
            ) { (path, info) ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cellHeight)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CTDebug(tag, "Rendering stream $path...")
                    StreamTile(
                        viewModel = viewModel,
                        streamDesignator = path,
                        streamState = info.state,
                        onToggleFocus = {
                            viewModel.toggleFocus(path)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyStreamsView(mapStatus: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val myIpAddress: String = R2CPeer.GetMyIpAddress(false)
            Text("Waiting for drone to attach at rtmp://$myIpAddress/<droneDesig>")
            Text(
                text = mapStatus,
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}
