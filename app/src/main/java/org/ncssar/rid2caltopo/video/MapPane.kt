package org.ncssar.rid2caltopo.video

import StreamsViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.os.StatFs
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import org.ncssar.rid2caltopo.ui.MapFoldersDialog
import org.ncssar.rid2caltopo.ui.MapFolderUiState
import org.ncssar.rid2caltopo.ui.MapItemUiState
import org.ncssar.rid2caltopo.ui.MutualAidPackageShareDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import androidx.documentfile.provider.DocumentFile
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.infowindow.InfoWindow
import org.ncssar.rid2caltopo.BuildConfig
import org.ncssar.rid2caltopo.R
import org.ncssar.rid2caltopo.app.R2CActivity
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebugEnabled
import org.ncssar.rid2caltopo.data.CaltopoLiveTrack
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.WaypointTrack
import org.ncssar.rid2caltopo.data.PeerCoordinator
import org.ncssar.rid2caltopo.data.R2cRuntimeRegistry
import org.ncssar.rid2caltopo.data.MutualAidExportCoordinator
import org.ncssar.rid2caltopo.data.MutualAidPackageManager
import org.ncssar.rid2caltopo.data.MutualAidPackageTransferManager
import org.ncssar.rid2caltopo.data.MutualAidProfileManager
import org.ncssar.rid2caltopo.data.DEFAULT_ACTIVE_TRACK_COLOR
import org.ncssar.rid2caltopo.data.DEFAULT_ARCHIVE_TRACK_COLOR
import org.ncssar.rid2caltopo.data.PilotDisplayPreference
import org.ncssar.rid2caltopo.data.PilotDisplayPrefs
import org.ncssar.rid2caltopo.data.normalizePilotCallsign
import org.ncssar.rid2caltopo.data.sanitizeTrackColor
import org.ncssar.rid2caltopo.notam.NearbyNotam
import org.ncssar.rid2caltopo.notam.NotamCenter
import org.ncssar.rid2caltopo.notam.NotamMapOverlayAdapter
import org.ncssar.rid2caltopo.video.mapcache.CaltopoIconCacheService
import org.ncssar.rid2caltopo.video.mapcache.BadTilePolicy
import org.ncssar.rid2caltopo.video.mapcache.DemElevationService
import org.ncssar.rid2caltopo.video.mapcache.MapCacheDebug
import org.ncssar.rid2caltopo.video.mapcache.MapCachePolicy
import org.ncssar.rid2caltopo.video.mapcache.MapCacheSettings
import org.ncssar.rid2caltopo.video.mapcache.MapCacheRoot
import org.ncssar.rid2caltopo.video.mapcache.MapCacheRootResolver
import org.ncssar.rid2caltopo.video.mapcache.TileCacheMapProvider
import org.ncssar.rid2caltopo.video.mapcache.TileDiskCacheWriter
import org.ncssar.rid2caltopo.video.mapcache.TileFetchPriorityScheduler

// Constants
internal const val MAP_PANE_TAG = "SplitMapPane"

internal enum class MapPanePresentationMode {
    Full,
    Inset
}

internal fun mapPaneMarkerScale(mode: MapPanePresentationMode): Float =
    when (mode) {
        MapPanePresentationMode.Full -> 1.0f
        MapPanePresentationMode.Inset -> 0.55f
    }

internal fun mapPaneLineScale(mode: MapPanePresentationMode): Float =
    when (mode) {
        MapPanePresentationMode.Full -> 1.0f
        MapPanePresentationMode.Inset -> 0.65f
    }

internal fun mapPaneInsetViewportZoom(
    fullWidthPx: Int?,
    fullHeightPx: Int?,
    insetWidthPx: Int,
    insetHeightPx: Int,
    fullZoom: Double
): Double {
    if (!fullZoom.isFinite()) return fullZoom
    val fullWidth = fullWidthPx?.takeIf { it > 0 } ?: return fullZoom
    val fullHeight = fullHeightPx?.takeIf { it > 0 } ?: return fullZoom
    if (insetWidthPx <= 0 || insetHeightPx <= 0) return fullZoom
    val widthRatio = fullWidth.toDouble() / insetWidthPx.toDouble()
    val heightRatio = fullHeight.toDouble() / insetHeightPx.toDouble()
    val scaleRatio = maxOf(widthRatio, heightRatio).takeIf { it.isFinite() && it > 1.0 } ?: return fullZoom
    return (fullZoom - kotlin.math.ln(scaleRatio) / kotlin.math.ln(2.0)).coerceAtLeast(0.0)
}

internal fun shouldFollowFocusedDrone(
    presentationMode: MapPanePresentationMode,
    followFocusedDroneEnabled: Boolean,
    hasFocusedDroneTelemetry: Boolean,
    operatorAdjustedViewport: Boolean
): Boolean {
    if (!followFocusedDroneEnabled || !hasFocusedDroneTelemetry) return false
    return presentationMode == MapPanePresentationMode.Inset || !operatorAdjustedViewport
}

internal fun cachedArtifactOverlayState(overlayState: Any?): ArtifactOverlayState =
    overlayState as? ArtifactOverlayState ?: ArtifactOverlayState()

internal const val MAP_PANE_VERBOSE_LOGS = false
private const val INSET_FOLLOW_INTERVAL_MS = 500L
private const val INSET_FOLLOW_MIN_MOVE_METERS = 1.0
internal const val LOCAL_DEVICE_SYMBOL = "radiotower"
internal const val LOCAL_DEVICE_COLOR_HEALTHY = "0000FF"
internal const val LOCAL_DEVICE_COLOR_STARTING = "808080"
internal const val LOCAL_DEVICE_COLOR_DEGRADED = "FFA500"
internal const val LOCAL_DEVICE_COLOR_UNCONFIGURED = "FF0000"
internal const val ICON_LATENCY_TAG = "RidIconLatency"
internal const val AGL_LIMIT_FT = 200.0
internal const val RANGE_LIMIT_FT = 5280.0
internal const val AGL_ICON_NEAR_DELTA_FT = 20.0
internal const val FT_TO_METERS = 0.3048
internal const val NEAR_LIMIT_RATIO = 0.90
internal const val NEAR_ALERT_COOLDOWN_MS = 30_000L
private const val STARTUP_MY_LOCATION_FRESH_MS = 60_000L
private const val STARTUP_MY_LOCATION_WAIT_MS = 20_000L
private const val STARTUP_MY_LOCATION_MIN_ZOOM = 14.0
internal const val OVER_ALERT_COOLDOWN_MS = 12_000L
internal const val METERS_TO_FEET = 3.28084
internal const val DEM_RETRY_INTERVAL_MS = 2_000L
internal const val PREDICTIVE_HEAD_MIN_AGE_MS = 600L
internal const val PREDICTIVE_HEAD_MAX_AGE_MS = 5_000L
internal const val PREDICTIVE_HEAD_MAX_LOOKAHEAD_MS = 2_000L
internal const val PREDICTIVE_HEAD_MAX_SPEED_MPS = 45.0
internal const val PREDICTIVE_HEAD_MAX_VERTICAL_SPEED_MPS = 15.0
internal const val PREDICTIVE_HEAD_MAX_DISTANCE_M = 90.0
internal const val SIGNIFICANT_HEADING_MOVE_M = 16.0 * FT_TO_METERS
internal const val LABEL_MAX_ABS_FEET = 1000.0
internal const val DEFAULT_CAMERA_FOV_WIDTH_DEG = 80.0
internal const val OSM_MAX_ZOOM = 19.0
internal const val MAP_DISPLAY_MAX_ZOOM = 22.0
internal const val MAP_CACHE_PREFS_NAME = "map_cache"
internal const val MAP_CACHE_PREWARM_SIG_KEY = "prewarm_signature_v1"
internal const val OSM_TILE_DOWNLOAD_THREADS: Short = 1
internal const val OSM_TILE_DOWNLOAD_MAX_QUEUE: Short = 1000
internal const val OSM_OFFLINE_PREP_REQUEST_DELAY_MS = 1_250L
internal const val TILE_FS_THREADS: Short = 4
internal const val TILE_FS_MAX_QUEUE: Short = 2000
internal const val TILE_IO_ACTIVE_GRACE_MS = 2_000L
private const val WEB_MERCATOR_HALF_WORLD_METERS = 20_037_508.342789244

// Enums
internal enum class BaseLayerOption(val label: String) {
    OpenStreetMap("OpenStreetMap"),
    Imagery("Imagery")
}

internal enum class OfflinePrepAreaMode(val label: String) {
    Viewport("Current visible map"),
    MapBoundary("Selected map shape")
}

internal enum class AlertSeverity {
    None,
    Near,
    Over
}

// Data classes
internal data class OfflinePrepPreset(
    val label: String,
    val minZoom: Int,
    val maxZoom: Int,
    val demStepMeters: Double
)

internal val OFFLINE_PREP_PRESETS = listOf(
    OfflinePrepPreset(label = "Overview (z8-z12)", minZoom = 8, maxZoom = 12, demStepMeters = 500.0),
    OfflinePrepPreset(label = "Ops (z12-z16)", minZoom = 12, maxZoom = 16, demStepMeters = 250.0),
    OfflinePrepPreset(label = "Full detail (z8-z19)", minZoom = 8, maxZoom = 19, demStepMeters = 120.0)
)

internal fun demSamplingSummary(stepMeters: Double): String {
    if (stepMeters <= 0.0) return "DEM sampling disabled"
    val stepFeet = stepMeters * METERS_TO_FEET
    val densityPerKm2 = 1_000_000.0 / (stepMeters * stepMeters)
    return String.format(
        Locale.US,
        "DEM sample spacing ~%.0f m (%.0f ft), ~%.1f samples/km²",
        stepMeters,
        stepFeet,
        densityPerKm2
    )
}

internal data class OfflinePrepProgress(
    val phase: String = "Idle",
    val total: Int = 0,
    val completed: Int = 0,
    val tileTotal: Int = 0,
    val tileCompleted: Int = 0,
    val demTotal: Int = 0,
    val demCompleted: Int = 0,
    val demHits: Int = 0,
    val demFetched: Int = 0,
    val hits: Int = 0,
    val fetched: Int = 0,
    val failed: Int = 0,
    val demFailed: Int = 0,
    val totalFailed: Int = 0,
    val opsPerSec: Double = 0.0,
    val etaSeconds: Long? = null
)

internal data class GeoBoundary(
    val ring: List<GeoPoint>,
    val bounds: BoundingBox
)

internal data class OfflineBoundaryOption(
    val id: String,
    val label: String,
    val boundary: GeoBoundary
)

internal data class OfflinePrepEstimate(
    val tileEstimate: Int = 0,
    val demEstimate: Int = 0,
    val estimatedTileCacheMb: Double = 0.0,
    val estimatedDemCacheMb: Double = 0.0,
    val ready: Boolean = false
)

internal data class OfflinePrepCacheStatus(
    val checked: Boolean = false,
    val tileMissing: Int = 0,
    val demMissing: Int = 0
) {
    val readyForPackage: Boolean
        get() = checked && tileMissing == 0 && demMissing == 0
}

internal data class MapPaneBackgroundWorkStatus(
    val label: String,
    val completed: Int = 0,
    val total: Int = 0
) {
    val progress: Float?
        get() = if (total > 0) (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
}

internal data class LiveTileRequest(
    val tileIndex: Long,
    val currentTileIndex: Long,
    val requiresCurrentCached: Boolean
)

internal data class DroneMapPoint(
    val designator: String,        // mappedId — display label, may change during a flight
    val remoteId: String,          // stable unique identifier from the Remote ID broadcast
    val lat: Double,
    val lng: Double,
    val altitudeM: Double,
    val timestampMsec: Long,
    val receivedAtMsec: Long? = null,
    val headingDeg: Double? = null,
    val droneSpec: CtDroneSpec? = null
)

internal data class LabelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int
        get() = right - left
    val height: Int
        get() = bottom - top
    val centerX: Int
        get() = left + width / 2
    val centerY: Int
        get() = top + height / 2

    fun intersects(other: LabelRect): Boolean =
        left < other.right &&
            right > other.left &&
            top < other.bottom &&
            bottom > other.top
}

internal data class LabelLeaderLine(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int
)

internal data class DroneLabelLayoutInput(
    val designator: String,
    val anchorX: Int,
    val anchorY: Int,
    val nameWidth: Int,
    val nameHeight: Int,
    val statusWidth: Int,
    val statusHeight: Int
)

internal data class DroneLabelLayout(
    val designator: String,
    val bounds: LabelRect,
    val nameBounds: LabelRect,
    val statusBounds: LabelRect,
    val leaderLine: LabelLeaderLine?
)

// DroneAglState, AtoSeedSource, DroneAltitudeCalibration — defined in DroneAltitudeModels.kt

internal data class DroneComplianceState(
    val aglM: Double?,
    val rangeFromTakeoffM: Double?,
    val nearAgl: Boolean,
    val nearRange: Boolean,
    val overAgl: Boolean,
    val overRange: Boolean,
    val staleDem: Boolean
)

internal data class ArtifactPointSpec(
    val id: String,
    val lat: Double,
    val lng: Double,
    val title: String,
    val markerSymbol: String,
    val markerColor: String?
)

internal data class ArtifactLineSpec(
    val id: String,
    val points: List<GeoPoint>,
    val color: Int,
    val width: Float,
    val title: String
)

internal data class ArtifactPolygonSpec(
    val id: String,
    val points: List<GeoPoint>,
    val strokeColor: Int,
    val fillColor: Int,
    val strokeWidth: Float,
    val title: String
)

internal data class ArtifactOverlayState(
    val totalFeatures: Int = 0,
    val ignoredTrackLikeFeatures: Int = 0,
    val points: List<ArtifactPointSpec> = emptyList(),
    val lines: List<ArtifactLineSpec> = emptyList(),
    val polygons: List<ArtifactPolygonSpec> = emptyList()
)

internal data class ArtifactHydrationProgress(
    val completed: Int,
    val total: Int
)

internal data class ArtifactFolderDefault(
    val folderId: String,
    val initiallyVisible: Boolean
)

internal data class ArtifactHydrationResult(
    val featuresById: LinkedHashMap<String, JSONObject>,
    val overlayState: ArtifactOverlayState,
    val folderDefaults: List<ArtifactFolderDefault>,
    val serverHiddenFolderIds: Set<String>
)

internal data class LocalTrackPoint(
    val mappedId: String,
    val lat: Double,
    val lng: Double,
    val altitudeM: Double,
    val timestampMsec: Long,
    val receivedAtMsec: Long
)

internal data class PredictedHead(
    val lat: Double,
    val lng: Double
)

internal data class BadTileDialogState(
    val tileIndex: Long,
    val zoom: Int,
    val x: Int,
    val y: Int,
    val hash: String
)

internal data class PilotDisplaySettingsState(
    val pilotKey: String,
    val displayName: String,
    val preference: PilotDisplayPreference
)

private enum class PilotDisplayColorSlot {
    Active,
    Archive
}

private data class PilotColorPickerTarget(
    val settings: PilotDisplaySettingsState,
    val slot: PilotDisplayColorSlot
)

private fun localTrackDesignator(mappedId: String): String = mappedId.ifBlank { "unmapped" }

private const val LOCAL_TRACK_RECENT_POINT_LIMIT = 500
private const val LOCAL_TRACK_FLIGHT_POINT_LIMIT = 10_000
private const val LOCAL_TRACK_DUPLICATE_COORD_EPSILON = 0.000001
private const val LOCAL_TRACK_DUPLICATE_ALT_EPSILON_METERS = 0.5
private val PILOT_DISPLAY_COLOR_PALETTE = listOf(
    DEFAULT_ACTIVE_TRACK_COLOR,
    DEFAULT_ARCHIVE_TRACK_COLOR,
    "#E53935",
    "#FB8C00",
    "#FDD835",
    "#43A047",
    "#00ACC1",
    "#3949AB",
    "#8E24AA",
    "#6D4C41",
    "#FFFFFF",
    "#212121"
)

internal fun seedLocalTrackPointsFromSnapshot(
    mappedId: String,
    snapshot: List<WaypointTrack.TrackPoint>,
    receivedAtMsec: Long,
    recentPoints: MutableList<LocalTrackPoint>,
    flightPoints: MutableList<LocalTrackPoint>
): Boolean {
    val key = localTrackDesignator(mappedId)
    val snapshotPoints = snapshot.mapNotNull { point ->
        if (!point.lat.isFinite() || !point.lng.isFinite()) return@mapNotNull null
        if (point.lat == 0.0 && point.lng == 0.0) return@mapNotNull null
        LocalTrackPoint(
            mappedId = key,
            lat = point.lat,
            lng = point.lng,
            altitudeM = point.ele,
            timestampMsec = point.timestampMsec,
            receivedAtMsec = receivedAtMsec
        )
    }
    if (snapshotPoints.isEmpty()) return false

    var changed = false
    snapshotPoints.forEach { point ->
        if (flightPoints.none { existing -> existing.isSameTrackPoint(point) }) {
            flightPoints.add(point)
            changed = true
        }
    }
    while (flightPoints.size > LOCAL_TRACK_FLIGHT_POINT_LIMIT) {
        flightPoints.removeAt(0)
        changed = true
    }

    if (recentPoints.isEmpty()) {
        recentPoints.add(snapshotPoints.last())
        changed = true
    }
    while (recentPoints.size > LOCAL_TRACK_RECENT_POINT_LIMIT) {
        recentPoints.removeAt(0)
        changed = true
    }

    return changed
}

internal fun shouldSeedLocalTrackSnapshotForDesignator(
    mappedId: String,
    snapshot: List<WaypointTrack.TrackPoint>,
    lastSeededTimestampByMappedId: MutableMap<String, Long>
): Boolean {
    val key = localTrackDesignator(mappedId)
    val newestTimestamp = snapshot.asSequence()
        .filter { point ->
            point.lat.isFinite() &&
                point.lng.isFinite() &&
                !(point.lat == 0.0 && point.lng == 0.0)
        }
        .map { it.timestampMsec }
        .maxOrNull()
        ?: return false
    val lastSeededTimestamp = lastSeededTimestampByMappedId[key]
    if (lastSeededTimestamp != null && newestTimestamp <= lastSeededTimestamp) {
        return false
    }
    lastSeededTimestampByMappedId[key] = newestTimestamp
    return true
}

private fun LocalTrackPoint.isSameTrackPoint(other: LocalTrackPoint): Boolean {
    if (timestampMsec != other.timestampMsec) return false
    if (kotlin.math.abs(lat - other.lat) > LOCAL_TRACK_DUPLICATE_COORD_EPSILON) return false
    if (kotlin.math.abs(lng - other.lng) > LOCAL_TRACK_DUPLICATE_COORD_EPSILON) return false
    return kotlin.math.abs(altitudeM - other.altitudeM) <= LOCAL_TRACK_DUPLICATE_ALT_EPSILON_METERS
}

internal fun layoutDroneLabelGroups(
    labels: List<DroneLabelLayoutInput>,
    viewportWidth: Int,
    viewportHeight: Int
): List<DroneLabelLayout> {
    val placedBounds = mutableListOf<LabelRect>()
    val viewport = LabelRect(0, 0, viewportWidth, viewportHeight)
    return labels.map { label ->
        val candidates = droneLabelCandidates(label)
        val preferred = candidates.firstOrNull { candidate ->
            candidate.group.fitsWithin(viewport) && placedBounds.none { it.intersects(candidate.group) }
        } ?: candidates.minWithOrNull(
            compareBy<DroneLabelCandidate> { candidate ->
                placedBounds.sumOf { candidate.group.overlapArea(it) }
            }.thenBy { candidate ->
                candidate.group.outsideArea(viewport)
            }
        ) ?: droneLabelCandidate(label, offsetX = 0, offsetY = 28)
        placedBounds.add(preferred.group)
        preferred.toLayout(label)
    }
}

private data class DroneLabelCandidate(
    val group: LabelRect,
    val name: LabelRect,
    val status: LabelRect,
    val isDefault: Boolean
) {
    fun toLayout(label: DroneLabelLayoutInput): DroneLabelLayout =
        DroneLabelLayout(
            designator = label.designator,
            bounds = group,
            nameBounds = name,
            statusBounds = status,
            leaderLine = if (isDefault) {
                null
            } else {
                LabelLeaderLine(
                    startX = label.anchorX,
                    startY = label.anchorY,
                    endX = group.centerX,
                    endY = group.centerY
                )
            }
        )
}

private fun droneLabelCandidates(label: DroneLabelLayoutInput): List<DroneLabelCandidate> =
    with(droneLabelGroupSize(label)) {
        val sideOffsetX = width / 2 + 44
        val farSideOffsetX = width / 2 + 92
        val centeredOffsetY = -(height / 2)
        listOf(
            droneLabelCandidate(label, offsetX = 0, offsetY = 28, isDefault = true),
            droneLabelCandidate(label, offsetX = 0, offsetY = -(height + 28)),
            droneLabelCandidate(label, offsetX = sideOffsetX, offsetY = centeredOffsetY),
            droneLabelCandidate(label, offsetX = -sideOffsetX, offsetY = centeredOffsetY),
            droneLabelCandidate(label, offsetX = sideOffsetX, offsetY = 34),
            droneLabelCandidate(label, offsetX = -sideOffsetX, offsetY = 34),
            droneLabelCandidate(label, offsetX = 0, offsetY = height + 34),
            droneLabelCandidate(label, offsetX = farSideOffsetX, offsetY = centeredOffsetY),
            droneLabelCandidate(label, offsetX = -farSideOffsetX, offsetY = centeredOffsetY)
        )
    }

private data class DroneLabelGroupSize(
    val width: Int,
    val height: Int
)

private fun droneLabelGroupSize(label: DroneLabelLayoutInput): DroneLabelGroupSize =
    DroneLabelGroupSize(
        width = maxOf(label.nameWidth, label.statusWidth),
        height = label.nameHeight + 3 + label.statusHeight
    )

private fun droneLabelCandidate(
    label: DroneLabelLayoutInput,
    offsetX: Int,
    offsetY: Int,
    isDefault: Boolean = false
): DroneLabelCandidate {
    val gap = 3
    val groupSize = droneLabelGroupSize(label)
    val left = label.anchorX + offsetX - groupSize.width / 2
    val top = label.anchorY + offsetY
    val group = LabelRect(left, top, left + groupSize.width, top + groupSize.height)
    val nameLeft = group.left + (group.width - label.nameWidth) / 2
    val name = LabelRect(nameLeft, group.top, nameLeft + label.nameWidth, group.top + label.nameHeight)
    val statusLeft = group.left + (group.width - label.statusWidth) / 2
    val statusTop = name.bottom + gap
    val status = LabelRect(statusLeft, statusTop, statusLeft + label.statusWidth, statusTop + label.statusHeight)
    return DroneLabelCandidate(group = group, name = name, status = status, isDefault = isDefault)
}

private fun LabelRect.fitsWithin(container: LabelRect): Boolean =
    left >= container.left &&
        top >= container.top &&
        right <= container.right &&
        bottom <= container.bottom

private fun LabelRect.overlapArea(other: LabelRect): Int {
    val overlapWidth = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0)
    val overlapHeight = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0)
    return overlapWidth * overlapHeight
}

private fun LabelRect.outsideArea(container: LabelRect): Int {
    val horizontal = (container.left - left).coerceAtLeast(0) + (right - container.right).coerceAtLeast(0)
    val vertical = (container.top - top).coerceAtLeast(0) + (bottom - container.bottom).coerceAtLeast(0)
    return horizontal * height + vertical * width
}

internal fun fullFlightTrackMappedIds(
    dronePoints: List<DroneMapPoint>,
    eligibleMappedIds: Set<String>,
    mappedIdsByRemoteId: Map<String, Set<String>> = emptyMap()
): Set<String> {
    val mappedIds = LinkedHashSet<String>()
    dronePoints.forEach { point ->
        val currentMappedId = localTrackDesignator(point.designator)
        if (currentMappedId !in eligibleMappedIds) return@forEach
        mappedIds.add(currentMappedId)
        mappedIdsByRemoteId[point.remoteId].orEmpty().forEach { alias ->
            mappedIds.add(localTrackDesignator(alias))
        }
    }
    return mappedIds
}

internal fun confirmedCurrentFlightMappedIds(dronePoints: List<DroneMapPoint>): Set<String> =
    dronePoints
        .asSequence()
        .filter { point -> CaltopoClient.IsCurrentPeerDroneConfirmed(point.remoteId) }
        .map { point -> localTrackDesignator(point.designator) }
        .toSet()

internal fun pilotDisplayPreferencesByMappedId(
    dronePoints: List<DroneMapPoint>,
    mappedIdsByRemoteId: Map<String, Set<String>> = emptyMap(),
    preferenceForPilotKey: (String?) -> PilotDisplayPreference
): Map<String, PilotDisplayPreference> {
    val byMappedId = LinkedHashMap<String, PilotDisplayPreference>()
    dronePoints.forEach { point ->
        val pilotKey = normalizePilotCallsign(point.droneSpec?.owner)
        val preference = preferenceForPilotKey(pilotKey)
        val currentMappedId = localTrackDesignator(point.designator)
        byMappedId[currentMappedId] = preference
        mappedIdsByRemoteId[point.remoteId].orEmpty().forEach { alias ->
            byMappedId[localTrackDesignator(alias)] = preference
        }
    }
    return byMappedId
}

private fun trackColorInt(rawColor: String?, fallback: String): Int =
    AndroidColor.parseColor(sanitizeTrackColor(rawColor, fallback))

private fun closedPolylinePoints(points: List<GeoPoint>): List<GeoPoint> {
    if (points.isEmpty()) return points
    val first = points.first()
    val last = points.last()
    return if (
        first.latitude == last.latitude &&
        first.longitude == last.longitude
    ) {
        points
    } else {
        points + GeoPoint(first.latitude, first.longitude)
    }
}

@Suppress("DEPRECATION")
private fun applyPolygonStyle(
    polygon: Polygon,
    strokeColor: Int,
    fillColor: Int,
    strokeWidth: Float
) {
    polygon.strokeColor = strokeColor
    polygon.fillColor = fillColor
    polygon.strokeWidth = strokeWidth
}

@Suppress("DEPRECATION")
private fun applyPolylineStyle(
    polyline: Polyline,
    color: Int,
    width: Float
) {
    polyline.color = color
    polyline.width = width
}

@Composable
private fun PilotDisplaySettingsContent(
    settings: PilotDisplaySettingsState,
    onPreferenceChange: (PilotDisplaySettingsState, PilotDisplayPreference) -> Unit,
    onPickColor: (PilotDisplayColorSlot) -> Unit,
    onReset: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pilot Display: ${settings.displayName}", style = MaterialTheme.typography.titleSmall)
        PilotDisplayColorRow(
            label = "Active",
            colorHex = settings.preference.activeTrackColor,
            onClick = { onPickColor(PilotDisplayColorSlot.Active) }
        )
        PilotDisplayColorRow(
            label = "Archive",
            colorHex = settings.preference.archiveTrackColor,
            onClick = { onPickColor(PilotDisplayColorSlot.Archive) }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = settings.preference.bearingEnabled,
                onCheckedChange = { enabled ->
                    onPreferenceChange(
                        settings,
                        settings.preference.copy(bearingEnabled = enabled)
                    )
                }
            )
            Text("Bearing")
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onReset) { Text("Reset") }
        }
    }
}

@Composable
private fun PilotDisplayColorRow(
    label: String,
    colorHex: String,
    onClick: () -> Unit
) {
    val sanitized = sanitizeTrackColor(colorHex, DEFAULT_ACTIVE_TRACK_COLOR)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clip(CircleShape)
                .background(Color(trackColorInt(sanitized, DEFAULT_ACTIVE_TRACK_COLOR)))
        )
        Spacer(Modifier.width(12.dp))
        Text(label)
        Spacer(Modifier.width(12.dp))
        Text(sanitized, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PilotTrackColorPickerDialog(
    target: PilotColorPickerTarget,
    onDismiss: () -> Unit,
    onColorSelected: (String) -> Unit
) {
    val currentColor = when (target.slot) {
        PilotDisplayColorSlot.Active -> target.settings.preference.activeTrackColor
        PilotDisplayColorSlot.Archive -> target.settings.preference.archiveTrackColor
    }
    val title = when (target.slot) {
        PilotDisplayColorSlot.Active -> "Active Track Color"
        PilotDisplayColorSlot.Archive -> "Archive Track Color"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PILOT_DISPLAY_COLOR_PALETTE.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { colorHex ->
                            val selected = sanitizeTrackColor(currentColor, DEFAULT_ACTIVE_TRACK_COLOR) == colorHex
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        shape = CircleShape
                                    )
                                    .padding(3.dp)
                                    .clip(CircleShape)
                                    .background(Color(trackColorInt(colorHex, DEFAULT_ACTIVE_TRACK_COLOR)))
                                    .clickable { onColorSelected(colorHex) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private class LocalMarkerInfoWindow(
    mapView: MapView,
    private val titleText: String,
    private val descriptionText: String,
    private val thumbnail: Bitmap?,
    private val onOpenSnapshot: (() -> Unit)?,
    private val markerId: Long?,
    private val onDelete: ((Long) -> Unit)?
) : InfoWindow(R.layout.map_local_marker_info_window, mapView) {
    override fun onOpen(item: Any?) {
        mView.findViewById<TextView>(R.id.local_marker_title)?.text = titleText
        mView.findViewById<TextView>(R.id.local_marker_description)?.apply {
            text = descriptionText
            visibility = if (descriptionText.isBlank()) View.GONE else View.VISIBLE
        }
        mView.findViewById<ImageView>(R.id.local_marker_snapshot)?.apply {
            if (thumbnail != null) {
                setImageBitmap(thumbnail)
                visibility = View.VISIBLE
                isClickable = onOpenSnapshot != null
                setOnClickListener {
                    onOpenSnapshot?.invoke()
                }
            } else {
                setImageDrawable(null)
                visibility = View.GONE
                setOnClickListener(null)
            }
        }
        mView.findViewById<Button>(R.id.local_marker_delete)?.apply {
            if (markerId != null && onDelete != null) {
                visibility = View.VISIBLE
                setOnClickListener {
                    close()
                    onDelete.invoke(markerId)
                }
            } else {
                visibility = View.GONE
                setOnClickListener(null)
            }
        }
    }

    override fun onClose() {
        mView.findViewById<ImageView>(R.id.local_marker_snapshot)?.setOnClickListener(null)
        mView.findViewById<Button>(R.id.local_marker_delete)?.setOnClickListener(null)
    }
}

internal enum class MarkerInfoWindowTapAction {
    Show,
    Close
}

internal fun markerInfoWindowTapAction(isInfoWindowShown: Boolean): MarkerInfoWindowTapAction =
    if (isInfoWindowShown) MarkerInfoWindowTapAction.Close else MarkerInfoWindowTapAction.Show

private fun consumeInsetMarkerTaps(marker: Marker, isInsetMode: Boolean) {
    if (!isInsetMode) return
    marker.infoWindow = null
    marker.setOnMarkerClickListener { _, _ -> true }
}

private data class DroneLabelDrawSpec(
    val designator: String,
    val position: GeoPoint,
    val nameDrawable: Drawable,
    val statusDrawable: Drawable
)

private class DroneLabelOverlay(
    private val labels: List<DroneLabelDrawSpec>
) : Overlay() {
    private val leaderHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#AA000000")
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val leaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#FFFFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        strokeCap = Paint.Cap.ROUND
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || labels.isEmpty()) return
        val projection = mapView.projection
        val layoutInputs = labels.map { label ->
            val point = projection.toPixels(label.position, null)
            DroneLabelLayoutInput(
                designator = label.designator,
                anchorX = point.x,
                anchorY = point.y,
                nameWidth = label.nameDrawable.intrinsicWidth.coerceAtLeast(1),
                nameHeight = label.nameDrawable.intrinsicHeight.coerceAtLeast(1),
                statusWidth = label.statusDrawable.intrinsicWidth.coerceAtLeast(1),
                statusHeight = label.statusDrawable.intrinsicHeight.coerceAtLeast(1)
            )
        }
        val layouts = layoutDroneLabelGroups(
            labels = layoutInputs,
            viewportWidth = mapView.width.takeIf { it > 0 } ?: canvas.width,
            viewportHeight = mapView.height.takeIf { it > 0 } ?: canvas.height
        )
        layouts.zip(labels).forEach { (layout, label) ->
            layout.leaderLine?.let { line ->
                canvas.drawLine(
                    line.startX.toFloat(),
                    line.startY.toFloat(),
                    line.endX.toFloat(),
                    line.endY.toFloat(),
                    leaderHaloPaint
                )
                canvas.drawLine(
                    line.startX.toFloat(),
                    line.startY.toFloat(),
                    line.endX.toFloat(),
                    line.endY.toFloat(),
                    leaderPaint
                )
            }
            label.nameDrawable.bounds = layout.nameBounds.toAndroidRect()
            label.nameDrawable.draw(canvas)
            label.statusDrawable.bounds = layout.statusBounds.toAndroidRect()
            label.statusDrawable.draw(canvas)
        }
    }
}

private fun LabelRect.toAndroidRect(): Rect =
    Rect(left, top, right, bottom)

private fun openClueSnapshotInExternalViewer(context: Context, title: String, bitmap: Bitmap?) {
    if (bitmap == null) {
        CaltopoClient.ShowToast("No clue snapshot available.")
        return
    }
    try {
        val snapshotDir = File(context.cacheDir, "clue-snapshots").apply { mkdirs() }
        val fileName = sanitizeClueSnapshotFileName(title)
        val snapshotFile = File(snapshotDir, fileName)
        FileOutputStream(snapshotFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            snapshotFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        CTError(MAP_PANE_TAG, "openClueSnapshotInExternalViewer(): failed", e)
        CaltopoClient.ShowToast("No app found to open clue snapshot.")
    }
}

private fun sanitizeClueSnapshotFileName(title: String): String {
    val safeTitle = title.trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "clue_snapshot" }
    return "$safeTitle.jpg"
}

// Tile source objects
internal object ArcGisWorldImageryTileSource : OnlineTileSourceBase(
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

internal object OsmStandardTileSource : OnlineTileSourceBase(
    "OSM-Standard",
    0,
    19,
    256,
    ".png",
    arrayOf("https://tile.openstreetmap.org/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl$zoom/$x/$y${imageFilenameEnding()}"
    }
}

internal object UsgsContoursTileSource : OnlineTileSourceBase(
    "USGS-Contours",
    0,
    MAP_DISPLAY_MAX_ZOOM.toInt(),
    256,
    ".png",
    arrayOf("https://carto.nationalmap.gov/arcgis/rest/services/contours/MapServer/export")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        val tilesPerSide = 1 shl zoom
        val span = (WEB_MERCATOR_HALF_WORLD_METERS * 2.0) / tilesPerSide.toDouble()
        val minX = -WEB_MERCATOR_HALF_WORLD_METERS + (x * span)
        val maxX = minX + span
        val maxY = WEB_MERCATOR_HALF_WORLD_METERS - (y * span)
        val minY = maxY - span
        val bbox = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", minX, minY, maxX, maxY)
        return "$baseUrl?bbox=$bbox&bboxSR=3857&imageSR=3857&size=256,256&format=png32&transparent=true&f=image"
    }
}

internal fun osmUserAgent(): String =
    "RID2Caltopo v${BuildConfig.VERSION_NAME} (contact: kjtsar@kjt.us)"

internal fun buildOfflineTileRequest(
    tileSource: OnlineTileSourceBase,
    url: String
): Request {
    val builder = Request.Builder().url(url)
    if (tileSource.name() == OsmStandardTileSource.name()) {
        val cfg = Configuration.getInstance()
        builder.header(cfg.userAgentHttpHeader, cfg.userAgentValue)
    }
    return builder.build()
}

internal fun tileSourceForBaseLayer(baseLayer: BaseLayerOption): OnlineTileSourceBase =
    when (baseLayer) {
        BaseLayerOption.OpenStreetMap -> OsmStandardTileSource
        BaseLayerOption.Imagery -> ArcGisWorldImageryTileSource
    }

internal fun offlinePrepTileSources(
    baseLayer: BaseLayerOption,
    includeContours: Boolean
): List<OnlineTileSourceBase> {
    val sources = mutableListOf<OnlineTileSourceBase>(tileSourceForBaseLayer(baseLayer))
    if (includeContours) sources += UsgsContoursTileSource
    return sources
}

internal fun offlinePrepTileOperationCount(baseTileCount: Int, includeContours: Boolean): Int {
    val sourceCount = if (includeContours) 2 else 1
    return (baseTileCount.toLong() * sourceCount.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal fun needsBaseTileProviderRestart(
    currentSourceName: String?,
    desiredTileSource: ITileSource
): Boolean =
    currentSourceName != desiredTileSource.name()

internal fun needsViewportTileProviderRestart(
    previousTileZoom: Int,
    currentTileZoom: Int,
    baseSourceChanged: Boolean
): Boolean =
    false

internal fun visibleTileNetworkActive(suppressLiveMapNetwork: Boolean): Boolean =
    !suppressLiveMapNetwork

internal fun offlineFirstForVisibleTiles(tileNetworkActive: Boolean): Boolean =
    !tileNetworkActive

internal fun isUsableMapViewportState(latitude: Double, longitude: Double, zoom: Double): Boolean {
    if (!latitude.isFinite() || !longitude.isFinite() || !zoom.isFinite()) return false
    if (latitude !in -85.0..85.0 || longitude !in -180.0..180.0) return false
    return !(kotlin.math.abs(latitude) < 0.000001 && kotlin.math.abs(longitude) < 0.000001)
}

private fun prefetchMapTileIfMissing(
    tileSource: org.osmdroid.tileprovider.tilesource.ITileSource,
    tileIndex: Long,
    tileWriter: TileDiskCacheWriter,
    httpClient: OkHttpClient,
    reason: String = "prefetch"
): Boolean {
    val onlineTileSource = tileSource as? OnlineTileSourceBase ?: return false
    val z = MapTileIndex.getZoom(tileIndex)
    val x = MapTileIndex.getX(tileIndex)
    val y = MapTileIndex.getY(tileIndex)
    if (tileWriter.exists(tileSource, tileIndex)) {
        MapCacheDebug.debug(
            MapCacheDebug.TAG_TILE,
            "$reason tile hit source=${tileSource.name()} z=$z x=$x y=$y"
        )
        return true
    }
    try {
        MapCacheDebug.debug(
            MapCacheDebug.TAG_TILE,
            "$reason tile miss source=${tileSource.name()} z=$z x=$x y=$y"
        )
        val url = onlineTileSource.getTileURLString(tileIndex)
        val request = buildOfflineTileRequest(onlineTileSource, url)
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                MapCacheDebug.debug(
                    MapCacheDebug.TAG_TILE,
                    "$reason tile fetch-http source=${tileSource.name()} z=$z x=$x y=$y http=${response.code}"
                )
                return false
            }
            val body = response.body
            if (body == null) {
                MapCacheDebug.debug(
                    MapCacheDebug.TAG_TILE,
                    "$reason tile fetch-empty source=${tileSource.name()} z=$z x=$x y=$y"
                )
                return false
            }
            val bytes = body.bytes()
            val saved = tileWriter.saveFile(tileSource, tileIndex, ByteArrayInputStream(bytes), null)
            MapCacheDebug.debug(
                MapCacheDebug.TAG_TILE,
                "$reason tile fetch-ok source=${tileSource.name()} z=$z x=$x y=$y bytes=${bytes.size} saved=$saved"
            )
            return saved
        }
    } catch (e: Exception) {
        if (MapCacheDebug.isDebugEnabled()) {
            MapCacheDebug.debug(
                MapCacheDebug.TAG_TILE,
                "$reason tile fetch-failed source=${tileSource.name()} z=$z x=$x y=$y err=${e.javaClass.simpleName}:${e.message}"
            )
        }
    }
    return false
}

internal fun configureOsmdroid(context: Context) {
    val cfg = Configuration.getInstance()
    val tileCacheMaxBytes = MapCachePolicy.tileCacheMaxBytes(context)
    cfg.load(context, context.getSharedPreferences("osmdroid", 0))
    cfg.userAgentValue = osmUserAgent()
    cfg.tileDownloadThreads = OSM_TILE_DOWNLOAD_THREADS
    cfg.tileDownloadMaxQueueSize = OSM_TILE_DOWNLOAD_MAX_QUEUE
    cfg.tileFileSystemThreads = TILE_FS_THREADS
    cfg.tileFileSystemMaxQueueSize = TILE_FS_MAX_QUEUE
    cfg.isDebugMapTileDownloader = MapCacheDebug.isLudicrousEnabled()
    cfg.tileFileSystemCacheMaxBytes = tileCacheMaxBytes
    cfg.tileFileSystemCacheTrimBytes = (tileCacheMaxBytes * 9L) / 10L
    cfg.expirationOverrideDuration = MapCachePolicy.TILE_TTL_MS
}

private fun buildTileMapProvider(
    context: Context,
    tileSource: ITileSource,
    tileWriter: TileDiskCacheWriter
): TileCacheMapProvider {
    configureOsmdroid(context)
    return TileCacheMapProvider(
        context = context,
        tileSource = tileSource,
        tileWriter = tileWriter
    ).apply {
        setUseDataConnection(true)
        setOfflineFirst(false)
    }
}

private fun restartTileProviderForViewportIntent(
    mapView: MapView,
    context: Context,
    tileSource: ITileSource,
    tileWriter: TileDiskCacheWriter,
    reason: String,
    zoom: Int
): TileCacheMapProvider {
    val nextProvider = buildTileMapProvider(context, tileSource, tileWriter)
    mapView.setTileProvider(nextProvider)
    mapView.setUseDataConnection(true)
    nextProvider.setUseDataConnection(true)
    MapCacheDebug.debug(
        MapCacheDebug.TAG_TILE,
        "tile provider restarted reason=$reason zoom=$zoom source=${tileSource.name()}"
    )
    return nextProvider
}

// SplitMapPane composable - extracted from StreamsScreen.kt (lines 648-2466)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SplitMapPane(
    viewModel: StreamsViewModel,
    modifier: Modifier = Modifier,
    onSingleTapFocus: (() -> Unit)? = null,
    presentationMode: MapPanePresentationMode = MapPanePresentationMode.Full
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val uiScope = rememberCoroutineScope()
    val isInsetMode = presentationMode == MapPanePresentationMode.Inset
    val markerScale = mapPaneMarkerScale(presentationMode)
    val lineScale = mapPaneLineScale(presentationMode)
    val restoredViewport = viewModel.mapViewportState()?.takeIf {
        isUsableMapViewportState(it.latitude, it.longitude, it.zoom)
    }
    val followFocusedDroneEnabled = viewModel.followFocusedDroneEnabled
    fun persistFullMapViewport(mapView: MapView) {
        if (isInsetMode) return
        if (mapView.width <= 0 || mapView.height <= 0) return
        viewModel.persistMapViewportState(
            center = mapView.mapCenter,
            zoom = mapView.zoomLevelDouble,
            widthPx = mapView.width,
            heightPx = mapView.height
        )
    }
    val baseLayer = viewModel.baseLayer
    var settingsMenuExpanded by remember { mutableStateOf(false) }
    var mapManagementMenuExpanded by remember { mutableStateOf(false) }
    var baseLayerMenuExpanded by remember { mutableStateOf(false) }
    var badTilesMenuExpanded by remember { mutableStateOf(false) }
    var currentMapView by remember { mutableStateOf<MapView?>(null) }
    var mapReloadInFlight by remember { mutableStateOf(false) }
    var showMapCacheSizeDialog by remember { mutableStateOf(false) }
    var showMapTileAgeDialog by remember { mutableStateOf(false) }
    var mapCacheSizeInput by remember {
        mutableStateOf(
            String.format(
                Locale.US,
                "%.1f",
                MapCacheSettings.maxCacheBytes(context).toDouble() / 1_000_000_000.0
            )
        )
    }
    var mapTileAgeDaysInput by remember {
        mutableStateOf(MapCacheSettings.maxTileAgeDays(context).toString())
    }
    var showMapFoldersDialog by remember { mutableStateOf(false) }
    val hiddenFolderIds = viewModel.hiddenFolderIds
    val hiddenItemIds = viewModel.hiddenItemIds
    var showBadTilesHowToDialog by remember { mutableStateOf(false) }
    var showOfflinePrepDialog by remember { mutableStateOf(false) }
    var showMutualAidPackageDialog by remember { mutableStateOf(false) }
    var offlinePrepInFlight by remember { mutableStateOf(false) }
    var offlinePrepPreset by remember { mutableStateOf(OFFLINE_PREP_PRESETS[1]) }
    var offlinePrepIncludeDem by remember { mutableStateOf(true) }
    var offlinePrepIncludeContours by remember { mutableStateOf(false) }
    var offlinePrepMaxThroughput by remember { mutableStateOf(false) }
    var offlinePrepAreaMode by remember { mutableStateOf(OfflinePrepAreaMode.Viewport) }
    var offlinePrepBoundaryId by remember { mutableStateOf<String?>(null) }
    var offlinePrepProgress by remember { mutableStateOf(OfflinePrepProgress()) }
    var offlinePrepCancelRequested by remember { mutableStateOf(false) }
    var offlinePrepEstimate by remember { mutableStateOf(OfflinePrepEstimate()) }
    var offlinePrepEstimateRunning by remember { mutableStateOf(false) }
    var offlinePrepCacheStatus by remember { mutableStateOf(OfflinePrepCacheStatus()) }
    var offlinePrepCompletedSelectionKey by remember { mutableStateOf<String?>(null) }
    var offlinePrepAvailableBytes by remember { mutableStateOf<Long?>(null) }
    var offlinePrepTileCacheCapBytes by remember { mutableStateOf(MapCachePolicy.tileCacheMaxBytes(context)) }
    var offlinePrepJob by remember { mutableStateOf<Job?>(null) }
    var offlinePrepAutoCloseJob by remember { mutableStateOf<Job?>(null) }
    val offlinePrepActiveCalls = remember { ConcurrentHashMap.newKeySet<Call>() }
    var mapBounds by remember { mutableStateOf<BoundingBox?>(null) }
    val packageZoneId = remember { ZoneId.systemDefault() }
    val packageDateFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd") }
    val packageTimeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val defaultPackageExpiry = remember {
        LocalDateTime.ofInstant(
            Instant.ofEpochMilli(MutualAidProfileManager.defaultExpiryAtNextMidnight()),
            packageZoneId
        )
    }
    var maPackageDisplayName by remember { mutableStateOf("") }
    var maPackageIncident by remember { mutableStateOf(CaltopoClient.GetIncident()) }
    var maPackageOpPeriod by remember { mutableStateOf(CaltopoClient.GetOpPeriod()) }
    var maPackageMapId by remember { mutableStateOf(CaltopoMap.GetMapId()) }
    var maPackageMapTitle by remember { mutableStateOf(CaltopoMap.GetMapName()) }
    var maPackageExpiryDateText by remember { mutableStateOf(defaultPackageExpiry.format(packageDateFormatter)) }
    var maPackageExpiryTimeText by remember { mutableStateOf(defaultPackageExpiry.format(packageTimeFormatter)) }
    var maPackageUseMapPaneExtents by remember { mutableStateOf(true) }
    val exportRequestId by MutualAidExportCoordinator.requestId.collectAsState()
    var lastHandledExportRequestId by remember { mutableStateOf(0L) }
    val activeShareSession by MutualAidPackageTransferManager.shareSession.collectAsState()
    var preparingMutualAidShare by remember { mutableStateOf(false) }
    val maximizeThroughputBlockedForOsm = baseLayer == BaseLayerOption.OpenStreetMap
    var predictiveHeadEnabled by remember { mutableStateOf(CaltopoClient.GetPredictiveHeadEnabled()) }
    var contourOverlayEnabled by remember { mutableStateOf(MapCacheSettings.contourOverlayEnabled(context)) }
    var autoRemoveBadTiles by remember { mutableStateOf(BadTilePolicy.isAutoRemoveEnabled(context)) }
    var badTileDialogState by remember { mutableStateOf<BadTileDialogState?>(null) }
    var quarantineMatchingHash by remember { mutableStateOf(true) }
    val mapName = viewModel.mapName
    val artifactRenderCache = viewModel.mapArtifactRenderCache
    val artifactStoreById = artifactRenderCache.featuresById
    fun cachedOverlayState(): ArtifactOverlayState = cachedArtifactOverlayState(artifactRenderCache.overlayState)
    val localTrackPointsByMappedId = remember { mutableStateMapOf<String, MutableList<LocalTrackPoint>>() }
    val currentFlightTrackPointsByMappedId = remember { mutableStateMapOf<String, MutableList<LocalTrackPoint>>() }
    val localTrackMappedIdsByRemoteId = remember { mutableStateMapOf<String, MutableSet<String>>() }
    val localTrackLastSeededTimestampByMappedId = remember { mutableMapOf<String, Long>() }
    var trackOverlayRefreshToken by remember { mutableIntStateOf(0) }
    val pilotDisplayPrefsByKey = remember { mutableStateMapOf<String, PilotDisplayPreference>() }
    var pilotDisplayRefreshToken by remember { mutableIntStateOf(0) }
    var colorPickerTarget by remember { mutableStateOf<PilotColorPickerTarget?>(null) }
    var localDeviceRefreshToken by remember { mutableIntStateOf(0) }
    val managedOverlays = remember { mutableListOf<Overlay>() }
    var artifactOverlayState: ArtifactOverlayState by remember {
        mutableStateOf(cachedOverlayState())
    }
    var mapBackgroundWorkStatus by remember { mutableStateOf<MapPaneBackgroundWorkStatus?>(null) }
    var artifactHydrationJob by remember { mutableStateOf<Job?>(null) }
    var artifactHydrationRunId by remember { mutableIntStateOf(0) }
    var artifactOverlayRebuildJob by remember { mutableStateOf<Job?>(null) }
    var artifactOverlayRebuildRunId by remember { mutableIntStateOf(0) }
    var lastRenderStats by remember { mutableStateOf("") }
    var lastAlignmentStats by remember { mutableStateOf("") }
    var initialViewportApplied by remember { mutableStateOf(restoredViewport != null) }
    var insetRestoredViewportApplied by remember { mutableStateOf(false) }
    var initialViewportArtifactCount by remember { mutableStateOf(-1) }
    var restoredViewportStartupCheckComplete by remember { mutableStateOf(true) }
    var restoredViewportStartupWaitLogged by remember { mutableStateOf(false) }
    var restoredViewportStartupCheckStartedAtMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var operatorAdjustedViewport by remember { mutableStateOf(false) }
    var lastLocalDeviceMarkerStats by remember { mutableStateOf("") }
    var localDeviceLocationMissingLogged by remember { mutableStateOf(false) }
    var localDeviceViewportRescueApplied by remember { mutableStateOf(false) }
    val droneMarkerIcon = remember(context) { ContextCompat.getDrawable(context, R.drawable.ic_drone_marker) }
    val symbolMarkerCache = remember { LinkedHashMap<String, Drawable>() }
    val caltopoMarkerCache = remember { mutableStateMapOf<String, Drawable>() }
    val scaledRemoteMarkerCache = remember { mutableStateMapOf<String, Drawable>() }
    val caltopoMarkerPending = remember { HashSet<String>() }
    val unknownSymbolsSeen = remember { LinkedHashSet<String>() }
    val iconCacheService = remember(context) { CaltopoIconCacheService(context) }
    // DemElevationService is owned by the coordinator (created once at ViewModel init).
    val demElevationService = viewModel.altitudeCoordinator.demElevationService
    val liveTilePriorityJobRef = remember { AtomicReference<Job?>(null) }
    val liveTilePriorityGeneration = remember { AtomicLong(0L) }
    // Register MapPane as an altitude consumer so the coordinator's update loop stays active.
    DisposableEffect(viewModel) {
        val removeConsumer = viewModel.addAltitudeConsumer()
        onDispose { removeConsumer() }
    }
    DisposableEffect(Unit) {
        onDispose {
            liveTilePriorityJobRef.getAndSet(null)?.cancel()
        }
    }
    val tileCacheWriter = remember(context) { TileDiskCacheWriter(context) }
    val tileFetchPriorityScheduler = remember { TileFetchPriorityScheduler() }
    val baseTileSource = tileSourceForBaseLayer(baseLayer)
    val latestBaseTileSource by rememberUpdatedState(baseTileSource)
    var tileMapProvider by remember(context) {
        mutableStateOf(buildTileMapProvider(context, baseTileSource, tileCacheWriter))
    }
    val contourTileMapProvider = remember(context) {
        buildTileMapProvider(context, UsgsContoursTileSource, tileCacheWriter)
    }
    val latestTileMapProvider by rememberUpdatedState(tileMapProvider)
    val latestTileFetchPriorityScheduler by rememberUpdatedState(tileFetchPriorityScheduler)
    val offlineHttpClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(14, TimeUnit.SECONDS)
            .build()
    }
    var lastCacheStats by remember { mutableStateOf("") }
    var nextCacheStatsLogAtMs by remember { mutableStateOf(0L) }
    var cacheStatsQueryInFlight by remember { mutableStateOf(false) }
    var prevIconHits by remember { mutableStateOf(0L) }
    var prevIconMisses by remember { mutableStateOf(0L) }
    var prevTileHits by remember { mutableStateOf(0L) }
    var prevTileMisses by remember { mutableStateOf(0L) }
    var prevDemHits by remember { mutableStateOf(0L) }
    var prevDemMisses by remember { mutableStateOf(0L) }
    var tileIoActiveUntilMs by remember { mutableStateOf(System.currentTimeMillis() + 12_000L) }
    var visibleTileZoom by remember { mutableIntStateOf(14) }
    var firstLiveTilePriorityPassComplete by remember { mutableStateOf(false) }
    var lastViewportSignature by remember { mutableStateOf<String?>(null) }
    // Auto-download: GeoTIFF tiles already initiated (prevents redundant re-downloads).
    val autoFetchedDemTiles = remember { HashSet<String>() }
    val autoPrefetchedMapTiles = remember { Collections.synchronizedSet(HashSet<String>()) }
    val demAutoFetchClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }
    val renderLatencyKeyByDesignator = remember { mutableStateMapOf<String, String>() }
    val complianceByDesignator = remember { mutableStateMapOf<String, DroneComplianceState>() }
    var fullArtifactHydrationQueued by remember { mutableStateOf(false) }
    // Tracks which drone's info-window bubble is open so it can be restored after each overlay rebuild.
    var openBubbleDesignator by remember { mutableStateOf<String?>(null) }
    val focusedPath by viewModel.focusedPath.collectAsStateWithLifecycle()
    var lastInsetFollowAtMs by remember { mutableStateOf(0L) }
    var lastInsetFollowDesignator by remember { mutableStateOf<String?>(null) }
    var lastInsetFollowPoint by remember { mutableStateOf<GeoPoint?>(null) }
    val notamUiState by NotamCenter.uiState.collectAsStateWithLifecycle()
    val proximityMapFocusTarget by viewModel.proximityMapFocusTarget.collectAsStateWithLifecycle()
    val staleTrackCutoffMs = System.currentTimeMillis() - (CaltopoClient.GetNewTrackDelayInSeconds() * 1000L)
    var selectedNotam by remember { mutableStateOf<NearbyNotam?>(null) }
    var selectedNotamGroup by remember { mutableStateOf<List<NearbyNotam>?>(null) }
    val dronePointEntries = viewModel.droneStates.mapNotNull { (designator, state) ->
        val stateTs = state.source.mostRecentMsecTimestamp
        var lat = state.lastLat
        var lng = state.lastLng
        var altitudeM = state.lastAlt
        var timestampMsec = stateTs
        var receivedAtMsec: Long? = null
        var usingLocalTail = false

        localTrackPointsByMappedId[designator]?.lastOrNull()?.let { localTail ->
            val localTailIsUsable =
                localTail.receivedAtMsec >= staleTrackCutoffMs &&
                    localTail.lat.isFinite() &&
                    localTail.lng.isFinite() &&
                    !(localTail.lat == 0.0 && localTail.lng == 0.0)
            if (localTailIsUsable) {
                lat = localTail.lat
                lng = localTail.lng
                altitudeM = localTail.altitudeM
                timestampMsec = localTail.timestampMsec
                receivedAtMsec = localTail.receivedAtMsec
                usingLocalTail = true
            }
        }

        if ((lat == 0.0 && lng == 0.0) || timestampMsec <= staleTrackCutoffMs) {
            null
        } else {
            val headingDeg = viewModel.droneDisplayStateFor(designator)?.headingDeg?.takeIf { it.isFinite() }
            Pair(
                DroneMapPoint(
                    designator = designator,
                    remoteId = state.source?.remoteId ?: designator,
                    lat = lat,
                    lng = lng,
                    altitudeM = altitudeM,
                    timestampMsec = timestampMsec,
                    receivedAtMsec = receivedAtMsec,
                    headingDeg = headingDeg,
                    droneSpec = state.source
                ),
                usingLocalTail
            )
        }
    }

    selectedNotam?.let { notice ->
        AlertDialog(
            onDismissRequest = { selectedNotam = null },
            title = { Text("NOTAM Detail") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (notice.proximityText.isNotBlank()) {
                        Text(notice.proximityText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(notice.title, style = MaterialTheme.typography.titleMedium)
                    notice.rawReference.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val metaText = buildString {
                        if (notice.intersectsPilotBubble) append("intersects 1 mi operating area")
                        if (notice.effectiveText.isNotBlank()) {
                            if (isNotBlank()) append(" • ")
                            append(notice.effectiveText)
                        }
                    }
                    if (metaText.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(metaText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (notice.summary.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(notice.summary)
                    }
                    if (notice.details.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(notice.details)
                    }
                    if (notice.rawText.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "FAA text: ${notice.rawTitle.ifBlank { notice.rawText }}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (notice.rawText.isNotBlank() && notice.rawText != notice.rawTitle) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Translation: ${notice.rawText}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedNotam = null }) { Text("Close") }
            },
            dismissButton = {}
        )
    }
    selectedNotamGroup?.let { notices ->
        AlertDialog(
            onDismissRequest = { selectedNotamGroup = null },
            title = {
                Text(if (notices.size == 1) "NOTAM Here" else "NOTAMs Here")
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    notices.forEach { notice ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedNotamGroup = null
                                    selectedNotam = notice
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            if (notice.proximityText.isNotBlank()) {
                                Text(
                                    notice.proximityText,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(notice.title, style = MaterialTheme.typography.titleMedium)
                            notice.effectiveText.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedNotamGroup = null }) { Text("Close") }
            },
            dismissButton = {}
        )
    }
    val dronePoints = dronePointEntries.map { it.first }
    val localTailHeadOverrideCount = dronePointEntries.count { it.second }
    // GeoTIFF tile names covering all currently-visible drone positions.
    // Used to trigger proactive background downloads so DEM lookups are served locally.
    val neededDemTileNames: Set<String> = remember(dronePoints) {
        dronePoints.mapTo(LinkedHashSet()) { tileNameForLocation(it.lat, it.lng) }
    }
    var offlineBoundaryOptions by remember { mutableStateOf<List<OfflineBoundaryOption>>(emptyList()) }
    LaunchedEffect(artifactOverlayState) {
        if (artifactOverlayState.totalFeatures == 0) {
            offlineBoundaryOptions = emptyList()
            return@LaunchedEffect
        }
        mapBackgroundWorkStatus = MapPaneBackgroundWorkStatus("Preparing map boundaries")
        val computed = withContext(Dispatchers.Default) {
            buildOfflineBoundaryOptions(artifactOverlayState)
        }
        offlineBoundaryOptions = computed
        if (mapBackgroundWorkStatus?.label == "Preparing map boundaries") {
            mapBackgroundWorkStatus = null
        }
    }
    LaunchedEffect(offlineBoundaryOptions) {
        val selectedStillExists = offlineBoundaryOptions.any { it.id == offlinePrepBoundaryId }
        if (!selectedStillExists) {
            offlinePrepBoundaryId = offlineBoundaryOptions.firstOrNull()?.id
        }
        if (offlineBoundaryOptions.isEmpty() && offlinePrepAreaMode == OfflinePrepAreaMode.MapBoundary) {
            offlinePrepAreaMode = OfflinePrepAreaMode.Viewport
        }
    }
    LaunchedEffect(baseLayer) {
        if (baseLayer == BaseLayerOption.OpenStreetMap) {
            offlinePrepMaxThroughput = false
        }
        tileIoActiveUntilMs = System.currentTimeMillis() + TILE_IO_ACTIVE_GRACE_MS
    }
    LaunchedEffect(autoRemoveBadTiles) {
        BadTilePolicy.setAutoRemoveEnabled(context, autoRemoveBadTiles)
    }
    LaunchedEffect(context, firstLiveTilePriorityPassComplete) {
        if (!firstLiveTilePriorityPassComplete) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val appContext = context.applicationContext
                val prefs = appContext.getSharedPreferences(MAP_CACHE_PREFS_NAME, 0)
                val signature =
                    "tile=${MapCachePolicy.TILE_CACHE_VERSION}|icon=${MapCachePolicy.ICON_CACHE_VERSION}|dem=v1|root=${mapCacheRootSignature(appContext)}"
                if (prefs.getString(MAP_CACHE_PREWARM_SIG_KEY, null) == signature) return@withContext
                val startMs = System.currentTimeMillis()
                tileCacheWriter.prewarm()
                iconCacheService.prewarm()
                prefs.edit().putString(MAP_CACHE_PREWARM_SIG_KEY, signature).apply()
                val elapsedMs = System.currentTimeMillis() - startMs
                MapCacheDebug.log("prewarm auto complete elapsedMs=$elapsedMs sig=$signature")
            } catch (e: Exception) {
                MapCacheDebug.log("prewarm auto failed err=${e.javaClass.simpleName}:${e.message}")
            }
        }
    }
    // Proactive DEM tile download + block pre-decode from device GPS.
    // Fires once at startup (after a brief GPS-lock delay) so that by the time the user opens
    // Live View — or the first drone appears — the .f32raw block file for the current location
    // is already on disk and elevation queries are instant (O(1) seek, no LZW/pred3).
    LaunchedEffect(Unit) {
        delay(3_000L) // give FusedLocationProvider a moment to deliver its first fix
        val loc = CaltopoMap.GetMyLocation() ?: return@LaunchedEffect
        if (!loc.latitude.isFinite() || !loc.longitude.isFinite()) return@LaunchedEffect
        val lat = loc.latitude
        val lng = loc.longitude
        val tileName = tileNameForLocation(lat, lng)
        withContext(Dispatchers.IO) {
            if (autoFetchedDemTiles.add(tileName)) {
                // Download or verify tile; refreshGeoTiffCatalog() is called inside when done.
                autoDownloadDemTile(tileName, context, demAutoFetchClient, demElevationService)
            }
            // Pre-decode the TIFF block(s) covering our GPS position into the persistent
            // .f32raw cache.  Runs after the tile is confirmed available so the decode
            // succeeds.  Subsequent elevation queries during the flight hit the block file
            // directly — no further LZW/pred3 work needed.
            demElevationService.prewarmForLocation(lat, lng)
        }
    }
    // Proactive DEM tile download keyed on active drone positions. Fires whenever the set of
    // required 1° tiles changes (new drone, drone crossing a tile boundary). Each unique tile
    // is downloaded at most once per session; already-present files are skipped quickly.
    LaunchedEffect(neededDemTileNames) {
        for (tileName in neededDemTileNames) {
            if (!autoFetchedDemTiles.add(tileName)) continue
            uiScope.launch(Dispatchers.IO) {
                autoDownloadDemTile(tileName, context, demAutoFetchClient, demElevationService)
            }
        }
    }
    LaunchedEffect(baseLayer, visibleTileZoom, localDeviceRefreshToken, dronePoints, offlinePrepInFlight) {
        val generation = liveTilePriorityGeneration.incrementAndGet()
        val passZoom = visibleTileZoom
        val passBaseLayer = baseLayer
        val passDronePoints = dronePoints
        val previousJob = liveTilePriorityJobRef.getAndSet(
            uiScope.launch(Dispatchers.IO) {
                val source = tileSourceForBaseLayer(passBaseLayer)
                val tabletLocation = CaltopoMap.GetMyLocation()?.takeIf {
                    it.latitude.isFinite() && it.longitude.isFinite()
                }?.let { GeoPoint(it.latitude, it.longitude) }
                val requests = liveTilePriorityRequests(
                    tabletLocation = tabletLocation,
                    dronePoints = passDronePoints,
                    visibleZoom = passZoom
                )
                if (requests.isEmpty()) {
                    withContext(Dispatchers.Main.immediate) {
                        if (liveTilePriorityGeneration.get() == generation) {
                            firstLiveTilePriorityPassComplete = true
                        }
                    }
                    return@launch
                }
                val passStartMs = System.currentTimeMillis()
                var attemptedRequests = 0
                var availableRequests = 0
                MapCacheDebug.debug(
                    MapCacheDebug.TAG_TILE,
                    "live-priority pass start zoom=$passZoom requests=${requests.size} source=${source.name()}"
                )
                try {
                    for (request in requests) {
                        currentCoroutineContext().ensureActive()
                        if (request.requiresCurrentCached && !tileCacheWriter.exists(source, request.currentTileIndex)) {
                            continue
                        }
                        val key = "${source.name()}:${request.tileIndex}"
                        if (key in autoPrefetchedMapTiles) continue
                        attemptedRequests++
                        val taskStartMs = System.currentTimeMillis()
                        val available = tileFetchPriorityScheduler.highPriority {
                            prefetchMapTileIfMissing(
                                source,
                                request.tileIndex,
                                tileCacheWriter,
                                offlineHttpClient,
                                reason = "live-priority"
                            )
                        }
                        val taskElapsedMs = System.currentTimeMillis() - taskStartMs
                        if (taskElapsedMs >= 1_000L) {
                            MapCacheDebug.warn(
                                MapCacheDebug.TAG_TILE,
                                "live-priority tile slow zoom=$passZoom source=${source.name()} " +
                                    "tile=${request.tileIndex} elapsedMs=$taskElapsedMs"
                            )
                        }
                        if (available) {
                            autoPrefetchedMapTiles.add(key)
                            availableRequests++
                        }
                    }
                } catch (e: CancellationException) {
                    val elapsedMs = System.currentTimeMillis() - passStartMs
                    MapCacheDebug.debug(
                        MapCacheDebug.TAG_TILE,
                        "live-priority pass cancelled zoom=$passZoom requests=${requests.size} " +
                            "attempted=$attemptedRequests elapsedMs=$elapsedMs"
                    )
                    throw e
                } finally {
                    val elapsedMs = System.currentTimeMillis() - passStartMs
                    if (elapsedMs >= 1_000L) {
                        MapCacheDebug.warn(
                            MapCacheDebug.TAG_TILE,
                            "live-priority pass slow zoom=$passZoom requests=${requests.size} " +
                                "attempted=$attemptedRequests available=$availableRequests elapsedMs=$elapsedMs"
                        )
                    } else {
                        MapCacheDebug.debug(
                            MapCacheDebug.TAG_TILE,
                            "live-priority pass done zoom=$passZoom requests=${requests.size} elapsedMs=$elapsedMs"
                        )
                    }
                    if (currentCoroutineContext().isActive) {
                        withContext(Dispatchers.Main.immediate) {
                            if (liveTilePriorityGeneration.get() == generation) {
                                firstLiveTilePriorityPassComplete = true
                            }
                        }
                    }
                }
            }
        )
        previousJob?.cancel()
    }
    LaunchedEffect(
        showOfflinePrepDialog,
        offlinePrepAreaMode,
        offlinePrepBoundaryId,
        offlinePrepPreset,
        offlinePrepIncludeDem,
        offlinePrepIncludeContours,
        mapBounds,
        offlineBoundaryOptions
    ) {
        if (!showOfflinePrepDialog) return@LaunchedEffect
        offlinePrepEstimateRunning = true
        offlinePrepEstimate = OfflinePrepEstimate(ready = false)
        val selectedBoundary =
            if (offlinePrepAreaMode == OfflinePrepAreaMode.MapBoundary) {
                offlineBoundaryOptions.firstOrNull { it.id == offlinePrepBoundaryId }?.boundary
            } else {
                null
            }
        val estimateBounds = selectedBoundary?.bounds ?: mapBounds
        if (estimateBounds == null) {
            offlinePrepEstimateRunning = false
            offlinePrepEstimate = OfflinePrepEstimate(ready = false)
            return@LaunchedEffect
        }
        val computed = withContext(Dispatchers.Default) {
            val baseTileEstimate = estimateTileCountApproximate(
                bounds = estimateBounds,
                minZoom = offlinePrepPreset.minZoom,
                maxZoom = offlinePrepPreset.maxZoom,
                clipBoundary = selectedBoundary
            )
            val tileEstimate = offlinePrepTileOperationCount(baseTileEstimate, offlinePrepIncludeContours)
            // DEM download fetches whole USGS 1° GeoTIFF tiles, not EPQS point samples.
            val demEstimate = if (offlinePrepIncludeDem) demTileNamesForBounds(estimateBounds).size else 0
            val tileCacheMb = (tileEstimate.toLong() * 20_000L) / (1024.0 * 1024.0)
            // Each USGS 1° GeoTIFF tile is ~25–54 MB; use 54 MB as a conservative upper bound.
            val demCacheMb = demEstimate * 54.0
            OfflinePrepEstimate(
                tileEstimate = tileEstimate,
                demEstimate = demEstimate,
                estimatedTileCacheMb = tileCacheMb,
                estimatedDemCacheMb = demCacheMb,
                ready = true
            )
        }
        offlinePrepEstimate = computed
        offlinePrepEstimateRunning = false
        offlinePrepAvailableBytes = withContext(Dispatchers.IO) {
            queryAvailableCacheBytes(context)
        }
        offlinePrepTileCacheCapBytes = withContext(Dispatchers.IO) {
            MapCachePolicy.tileCacheMaxBytes(context)
        }
    }
    LaunchedEffect(
        showOfflinePrepDialog,
        offlinePrepAreaMode,
        offlinePrepBoundaryId,
        offlinePrepPreset,
        offlinePrepIncludeDem,
        offlinePrepIncludeContours,
        baseLayer,
        mapBounds,
        offlineBoundaryOptions,
        offlinePrepInFlight,
        offlinePrepProgress.phase
    ) {
        if (!showOfflinePrepDialog) {
            offlinePrepCacheStatus = OfflinePrepCacheStatus()
            return@LaunchedEffect
        }
        val selectedBoundary =
            if (offlinePrepAreaMode == OfflinePrepAreaMode.MapBoundary) {
                offlineBoundaryOptions.firstOrNull { it.id == offlinePrepBoundaryId }?.boundary
            } else {
                null
            }
        val prepBounds = selectedBoundary?.bounds ?: mapBounds
        if (prepBounds == null) {
            offlinePrepCacheStatus = OfflinePrepCacheStatus()
            return@LaunchedEffect
        }
        offlinePrepCacheStatus = OfflinePrepCacheStatus(checked = false)
        val tileSources = offlinePrepTileSources(baseLayer, offlinePrepIncludeContours)
        val includeDem = offlinePrepIncludeDem
        val computed = withContext(Dispatchers.IO) {
            var tileMissing = 0
            forEachTileIndexForBounds(
                bounds = prepBounds,
                minZoom = offlinePrepPreset.minZoom,
                maxZoom = offlinePrepPreset.maxZoom,
                clipBoundary = selectedBoundary
            ) { tileIndex ->
                for (tileSource in tileSources) {
                    if (!tileCacheWriter.exists(tileSource, tileIndex)) {
                        tileMissing++
                    }
                }
            }
            var demMissing = 0
            if (includeDem) {
                val archiveRoot = CaltopoClient.GetArchiveDir()
                val demDir = archiveRoot?.findFile("cache")?.findFile("dem")
                for (tileName in demTileNamesForBounds(prepBounds)) {
                    val fileName = "USGS_1_$tileName.tif"
                    val demFile = demDir?.findFile(fileName)
                    if (demFile?.isFile != true) {
                        demMissing++
                    }
                }
            }
            OfflinePrepCacheStatus(
                checked = true,
                tileMissing = tileMissing,
                demMissing = demMissing
            )
        }
        offlinePrepCacheStatus = computed
    }

    fun selectedTileSource(): org.osmdroid.tileprovider.tilesource.ITileSource {
        return tileSourceForBaseLayer(baseLayer)
    }

    fun parseMutualAidPackageExpiry(): Long {
        return runCatching {
            val date = LocalDate.parse(maPackageExpiryDateText.trim(), packageDateFormatter)
            val time = LocalTime.parse(maPackageExpiryTimeText.trim(), packageTimeFormatter)
            LocalDateTime.of(date, time).atZone(packageZoneId).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    fun currentOfflinePrepSelectionKey(): String? {
        val boundary =
            if (offlinePrepAreaMode == OfflinePrepAreaMode.MapBoundary) {
                offlineBoundaryOptions.firstOrNull { it.id == offlinePrepBoundaryId }?.boundary
            } else {
                null
            }
        val bounds = boundary?.bounds ?: mapBounds ?: return null
        val areaKey =
            if (offlinePrepAreaMode == OfflinePrepAreaMode.MapBoundary) {
                "boundary:${offlinePrepBoundaryId ?: "none"}"
            } else {
                "viewport"
            }
        return listOf(
            "base=${baseLayer.name}",
            "preset=${offlinePrepPreset.label}",
            "dem=$offlinePrepIncludeDem",
            "contours=$offlinePrepIncludeContours",
            areaKey,
            "n=${"%.5f".format(Locale.US, bounds.latNorth)}",
            "s=${"%.5f".format(Locale.US, bounds.latSouth)}",
            "w=${"%.5f".format(Locale.US, bounds.lonWest)}",
            "e=${"%.5f".format(Locale.US, bounds.lonEast)}"
        ).joinToString("|")
    }

    val offlinePrepReadyByCompletion = remember(
        offlinePrepInFlight,
        offlinePrepCompletedSelectionKey,
        offlinePrepAreaMode,
        offlinePrepBoundaryId,
        offlinePrepPreset,
        offlinePrepIncludeDem,
        offlinePrepIncludeContours,
        baseLayer,
        mapBounds,
        offlineBoundaryOptions
    ) {
        !offlinePrepInFlight && offlinePrepCompletedSelectionKey != null &&
            offlinePrepCompletedSelectionKey == currentOfflinePrepSelectionKey()
    }
    val offlinePackageReady = offlinePrepCacheStatus.readyForPackage || offlinePrepReadyByCompletion

    fun startMutualAidShare() {
        val boundary =
            if (!maPackageUseMapPaneExtents && offlinePrepAreaMode == OfflinePrepAreaMode.MapBoundary) {
                offlineBoundaryOptions.firstOrNull { it.id == offlinePrepBoundaryId }?.boundary
            } else {
                null
            }
        val prepBounds = boundary?.bounds ?: mapBounds
        if (prepBounds == null) {
            CaltopoClient.ShowToast("Mutual aid package export needs visible map bounds.")
            return
        }
        preparingMutualAidShare = true
        uiScope.launch(Dispatchers.IO) {
            val packageName = buildString {
                append(maPackageIncident.ifBlank { "incident" }.replace(' ', '_'))
                append("_op")
                append(maPackageOpPeriod.ifBlank { "1" })
            }
            val exportResult = MutualAidPackageManager.exportPackageToTempFile(
                context = context,
                packageName = packageName,
                displayName = maPackageDisplayName.trim(),
                incident = maPackageIncident.trim(),
                opPeriod = maPackageOpPeriod.trim(),
                targetMapId = maPackageMapId.trim(),
                targetMapTitle = maPackageMapTitle.trim(),
                expiresAtEpochMs = parseMutualAidPackageExpiry(),
                bounds = prepBounds,
                minZoom = offlinePrepPreset.minZoom,
                maxZoom = offlinePrepPreset.maxZoom,
                tileSource = selectedTileSource(),
                includeDem = offlinePrepIncludeDem,
                clipBoundary = boundary
            )
            val result = if (exportResult.first && exportResult.second != null) {
                MutualAidPackageTransferManager.startShareSession(context, exportResult.second!!, packageName)
            } else {
                false to "Failed to build MA package."
            }
            withContext(Dispatchers.Main.immediate) {
                preparingMutualAidShare = false
                CaltopoClient.ShowToast(result.second)
            }
        }
    }

    LaunchedEffect(exportRequestId) {
        if (exportRequestId <= 0L || exportRequestId == lastHandledExportRequestId) return@LaunchedEffect
        lastHandledExportRequestId = exportRequestId
        maPackageIncident = CaltopoClient.GetIncident()
        maPackageOpPeriod = CaltopoClient.GetOpPeriod()
        maPackageMapId = CaltopoMap.GetMapId()
        maPackageMapTitle = CaltopoMap.GetMapName()
        maPackageUseMapPaneExtents = true
        val nextMidnight = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(MutualAidProfileManager.defaultExpiryAtNextMidnight()),
            packageZoneId
        )
        maPackageExpiryDateText = nextMidnight.format(packageDateFormatter)
        maPackageExpiryTimeText = nextMidnight.format(packageTimeFormatter)
        showMutualAidPackageDialog = true
    }

    fun startOfflinePrep(bounds: BoundingBox, clipBoundary: GeoBoundary?) {
        if (offlinePrepInFlight) return
        offlinePrepAutoCloseJob?.cancel()
        offlinePrepAutoCloseJob = null
        offlinePrepActiveCalls.clear()
        offlinePrepCancelRequested = false
        offlinePrepInFlight = true
        offlinePrepProgress = OfflinePrepProgress(phase = "Preparing", total = 0, completed = 0)
        val preset = offlinePrepPreset
        val includeDem = offlinePrepIncludeDem
        val includeContours = offlinePrepIncludeContours
        val tileSources = offlinePrepTileSources(baseLayer, includeContours)
        val isOsmDownload = baseLayer == BaseLayerOption.OpenStreetMap
        val maximizeThroughput = offlinePrepMaxThroughput && !isOsmDownload
        // Compute 1° GeoTIFF tile names for the area now (on the main thread, before the IO job).
        val demTileNames = if (includeDem) demTileNamesForBounds(bounds) else emptyList<String>()
        val estimatedTileOps = offlinePrepEstimate.tileEstimate
        val estimatedDemOps = demTileNames.size
        val estimatedTotalOps = (estimatedTileOps + estimatedDemOps).coerceAtLeast(1)
        val selectionKey = currentOfflinePrepSelectionKey()
        val tabletLocation = CaltopoMap.GetMyLocation()?.takeIf {
            it.latitude.isFinite() && it.longitude.isFinite()
        }?.let { GeoPoint(it.latitude, it.longitude) }
        val dronePathPoints = dronePoints.mapNotNull { point ->
            if (point.lat.isFinite() && point.lng.isFinite()) GeoPoint(point.lat, point.lng) else null
        }
        offlinePrepJob = uiScope.launch(Dispatchers.IO) {
            if (tileSources.isEmpty()) {
                withContext(Dispatchers.Main.immediate) {
                    offlinePrepInFlight = false
                    offlinePrepJob = null
                    CaltopoClient.ShowToast("Selected base layer does not support map download.")
                }
                return@launch
            }
            // Resolve the GeoTIFF DEM storage directory once for this download job.
            // archiveDemDir is null when no archive directory is configured.
            val archiveDemDir: DocumentFile? = if (includeDem) {
                val archiveRoot = CaltopoClient.GetArchiveDir()
                val cacheDir = archiveRoot?.findFile("cache")
                cacheDir?.findFile("dem") ?: cacheDir?.createDirectory("dem")
            } else null
            // Dedicated HTTP client for large file downloads (USGS GeoTIFF tiles are 25–54 MB each).
            val geoTiffHttpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .build()
            val completed = AtomicInteger(0)
            val tileCompleted = AtomicInteger(0)
            val demCompleted = AtomicInteger(0)
            val hits = AtomicInteger(0)
            val fetched = AtomicInteger(0)
            val tileFailed = AtomicInteger(0)
            val demFailed = AtomicInteger(0)
            val demHits = AtomicInteger(0)
            val demFetched = AtomicInteger(0)
            val totalFailed = AtomicInteger(0)
            val tileFailureLogCount = AtomicInteger(0)
            val demFailureLogCount = AtomicInteger(0)
            val startedAt = System.currentTimeMillis()
            var lastUiUpdateMs = 0L
            var phase = "Downloading map tiles"
            suspend fun pushProgress(force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastUiUpdateMs < 400L) return
                lastUiUpdateMs = now
                val done = completed.get()
                val tileDone = tileCompleted.get()
                val demDone = demCompleted.get()
                val demHit = demHits.get()
                val demFetch = demFetched.get()
                val hit = hits.get()
                val fetch = fetched.get()
                val tileFail = tileFailed.get()
                val demFail = demFailed.get()
                val failTotal = totalFailed.get()
                val elapsedSec = ((now - startedAt).coerceAtLeast(1L)).toDouble() / 1000.0
                val rate = done.toDouble() / elapsedSec
                val displayTotal = maxOf(estimatedTotalOps, done)
                val displayTileTotal = maxOf(estimatedTileOps, tileDone)
                val displayDemTotal = maxOf(estimatedDemOps, demDone)
                val remaining = (displayTotal - done).coerceAtLeast(0)
                val eta = if (rate > 0.05) kotlin.math.ceil(remaining / rate).toLong() else null
                withContext(Dispatchers.Main.immediate) {
                    offlinePrepProgress = OfflinePrepProgress(
                        phase = phase,
                        total = displayTotal,
                        completed = done,
                        tileTotal = displayTileTotal,
                        tileCompleted = tileDone,
                        demTotal = displayDemTotal,
                        demCompleted = demDone,
                        demHits = demHit,
                        demFetched = demFetch,
                        hits = hit,
                        fetched = fetch,
                        failed = tileFail,
                        demFailed = demFail,
                        totalFailed = failTotal,
                        opsPerSec = rate,
                        etaSeconds = eta
                    )
                }
            }

            try {
                coroutineScope {
                    val progressTicker = launch {
                        while (isActive) {
                            pushProgress(force = true)
                            delay(500L)
                        }
                    }
                    suspend fun processTile(tileSource: OnlineTileSourceBase, tileIndex: Long) {
                        ensureActive()
                        val exists = tileCacheWriter.exists(tileSource, tileIndex)
                        if (exists) {
                            hits.incrementAndGet()
                        } else {
                            val z = MapTileIndex.getZoom(tileIndex)
                            val x = MapTileIndex.getX(tileIndex)
                            val y = MapTileIndex.getY(tileIndex)
                            var failureDetail = ""
                            val ok = try {
                                val url = tileSource.getTileURLString(tileIndex)
                                val req = buildOfflineTileRequest(tileSource, url)
                                val call = offlineHttpClient.newCall(req)
                                offlinePrepActiveCalls += call
                                try {
                                    tileFetchPriorityScheduler.lowPriority {
                                        call.execute().use { resp ->
                                            if (!resp.isSuccessful) {
                                                failureDetail = "http=${resp.code} z=$z x=$x y=$y source=${tileSource.name()}"
                                                return@use false
                                            }
                                            val body = resp.body ?: return@use false
                                            val bytes = body.bytes()
                                            val saved = tileCacheWriter.saveFile(
                                                tileSource,
                                                tileIndex,
                                                ByteArrayInputStream(bytes),
                                                null
                                            )
                                            if (!saved && failureDetail.isBlank()) {
                                                val rejection = tileCacheWriter.describeRejectedWrite(tileSource, tileIndex, bytes)
                                                failureDetail =
                                                    (rejection ?: "save-rejected") + " z=$z x=$x y=$y source=${tileSource.name()}"
                                            }
                                            saved
                                        }
                                    }
                                } finally {
                                    offlinePrepActiveCalls.remove(call)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failureDetail = "ex=${e.javaClass.simpleName} z=$z x=$x y=$y source=${tileSource.name()}"
                                false
                            }
                            if (tileSource.name() == OsmStandardTileSource.name()) {
                                delay(OSM_OFFLINE_PREP_REQUEST_DELAY_MS)
                            }
                            if (ok) {
                                fetched.incrementAndGet()
                            } else {
                                tileFailed.incrementAndGet()
                                totalFailed.incrementAndGet()
                                val n = tileFailureLogCount.incrementAndGet()
                                if (n <= 12 || (n % 50) == 0) {
                                    if (CTDebugEnabled(MAP_PANE_TAG))  CTDebug(MAP_PANE_TAG, "DownloadMap tile failure#$n $failureDetail")
                                    MapCacheDebug.log("download tile failure#$n $failureDetail")
                                }
                            }
                        }
                        tileCompleted.incrementAndGet()
                        completed.incrementAndGet()
                    }
                    suspend fun processDem(lat: Double, lng: Double) {
                        ensureActive()
                        val wasCached = demElevationService.hasCachedSample(lat, lng)
                        var failureDetail = "no-sample"
                        val sample = try {
                            demElevationService.sampleElevationMeters(lat, lng)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failureDetail = "ex=${e.javaClass.simpleName}:${e.message}"
                            null
                        }
                        if (sample == null) {
                            demFailed.incrementAndGet()
                            totalFailed.incrementAndGet()
                            val n = demFailureLogCount.incrementAndGet()
                            if (n <= 12 || (n % 50) == 0) {
                                val msg = "download dem failure#$n lat=${"%.5f".format(Locale.US, lat)} lng=${"%.5f".format(Locale.US, lng)} reason=$failureDetail"
                                CTError(MAP_PANE_TAG, "DownloadMap DEM $msg")
                                MapCacheDebug.warn(MapCacheDebug.TAG_DEM, msg)
                            }
                        }
                        if (sample != null) {
                            if (wasCached) demHits.incrementAndGet() else demFetched.incrementAndGet()
                        }
                        demCompleted.incrementAndGet()
                        completed.incrementAndGet()
                    }

                    // Downloads a single USGS 1° GeoTIFF tile to archiveDir/cache/dem/.
                    // Skips tiles that are already present on disk (> 5 MB = clearly not truncated).
                    // Streams the response body directly to disk to avoid loading 25–54 MB into RAM.
                    suspend fun processGeoTiffTile(tileName: String) {
                        ensureActive()
                        val demDir = archiveDemDir
                        if (demDir == null) {
                            demFailed.incrementAndGet()
                            totalFailed.incrementAndGet()
                            demCompleted.incrementAndGet()
                            completed.incrementAndGet()
                            return
                        }
                        val fileName = "USGS_1_$tileName.tif"
                        val existing = demDir.findFile(fileName)
                        if (existing != null && existing.isFile && existing.length() > 5_000_000L) {
                            demHits.incrementAndGet()
                            demCompleted.incrementAndGet()
                            completed.incrementAndGet()
                            MapCacheDebug.log("geotiff dem hit tile=$tileName bytes=${existing.length()}")
                            return
                        }
                        val url = "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/1/TIFF/current/$tileName/USGS_1_$tileName.tif"
                        var failureDetail = "unknown"
                        val ok = try {
                            val req = Request.Builder().url(url).build()
                            val call = geoTiffHttpClient.newCall(req)
                            offlinePrepActiveCalls += call
                            try {
                                call.execute().use { resp ->
                                    if (!resp.isSuccessful) {
                                        failureDetail = "http=${resp.code}"
                                        return@use false
                                    }
                                    val body = resp.body ?: run { failureDetail = "no-body"; return@use false }
                                    val destFile = demDir.findFile(fileName) ?: demDir.createFile("image/tiff", fileName)
                                    if (destFile == null) { failureDetail = "create-failed"; return@use false }
                                    context.contentResolver.openOutputStream(destFile.uri, "wt")?.use { out ->
                                        body.byteStream().copyTo(out)
                                    } ?: run { failureDetail = "stream-open-failed"; return@use false }
                                    MapCacheDebug.log("geotiff dem fetched tile=$tileName uri=${destFile.uri}")
                                    true
                                }
                            } finally {
                                offlinePrepActiveCalls.remove(call)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failureDetail = "ex=${e.javaClass.simpleName}:${e.message}"
                            false
                        }
                        if (ok) {
                            demFetched.incrementAndGet()
                        } else {
                            demFailed.incrementAndGet()
                            totalFailed.incrementAndGet()
                            val n = demFailureLogCount.incrementAndGet()
                            if (n <= 12 || (n % 50) == 0) {
                                val msg = "GeoTIFF download failure#$n tile=$tileName reason=$failureDetail"
                                CTError(MAP_PANE_TAG, "DownloadMap DEM $msg")
                                MapCacheDebug.warn(MapCacheDebug.TAG_DEM, msg)
                            }
                        }
                        demCompleted.incrementAndGet()
                        completed.incrementAndGet()
                    }

                    if (!maximizeThroughput) {
                        val workerCount = if (isOsmDownload) 1 else 3
                        val tileQueue = Channel<Pair<OnlineTileSourceBase, Long>>(capacity = workerCount * 3)
                        val workers = List(workerCount) {
                            launch {
                                for ((source, tileIndex) in tileQueue) {
                                    processTile(source, tileIndex)
                                }
                            }
                        }
                        val orderedTileIndexes = orderedTileIndexesForOfflinePrep(
                            bounds = bounds,
                            minZoom = preset.minZoom,
                            maxZoom = preset.maxZoom,
                            clipBoundary = clipBoundary,
                            tabletLocation = tabletLocation,
                            dronePathPoints = dronePathPoints
                        )
                        for (tileIndex in orderedTileIndexes) {
                            currentCoroutineContext().ensureActive()
                            for (source in tileSources) {
                                tileQueue.send(source to tileIndex)
                            }
                        }
                        tileQueue.close()
                        workers.forEach { it.join() }

                        if (includeDem) {
                            if (archiveDemDir == null) {
                                withContext(Dispatchers.Main.immediate) {
                                    CaltopoClient.ShowToast("DEM tile download requires a configured archive directory.")
                                }
                            } else {
                                phase = "Downloading DEM tiles"
                                for (tileName in demTileNames) {
                                    currentCoroutineContext().ensureActive()
                                    processGeoTiffTile(tileName)
                                }
                                if (demFetched.get() > 0) demElevationService.refreshGeoTiffCatalog()
                            }
                        }
                    } else {
                        val maxWorkers = 16
                        val minWorkers = 2
                        val tileQueue = Channel<Pair<OnlineTileSourceBase, Long>>(capacity = maxWorkers * 4)
                        val demQueue = Channel<String>(capacity = maxWorkers * 3)
                        val tileWorkers = mutableListOf<Job>()
                        val demWorkers = mutableListOf<Job>()

                        val tileProducer = launch {
                            val orderedTileIndexes = orderedTileIndexesForOfflinePrep(
                                bounds = bounds,
                                minZoom = preset.minZoom,
                                maxZoom = preset.maxZoom,
                                clipBoundary = clipBoundary,
                                tabletLocation = tabletLocation,
                                dronePathPoints = dronePathPoints
                            )
                            for (tileIndex in orderedTileIndexes) {
                                currentCoroutineContext().ensureActive()
                                for (source in tileSources) {
                                    tileQueue.send(source to tileIndex)
                                }
                            }
                            tileQueue.close()
                        }
                        val demProducer = if (includeDem && archiveDemDir != null) {
                            launch {
                                for (tileName in demTileNames) {
                                    currentCoroutineContext().ensureActive()
                                    demQueue.send(tileName)
                                }
                                demQueue.close()
                            }
                        } else {
                            if (includeDem && archiveDemDir == null) {
                                withContext(Dispatchers.Main.immediate) {
                                    CaltopoClient.ShowToast("DEM tile download requires a configured archive directory.")
                                }
                            }
                            demQueue.close()
                            null
                        }

                        fun addTileWorker() {
                            tileWorkers += launch {
                                for ((source, tileIndex) in tileQueue) {
                                    processTile(source, tileIndex)
                                }
                            }
                        }
                        fun addDemWorker() {
                            demWorkers += launch {
                                for (tileName in demQueue) {
                                    processGeoTiffTile(tileName)
                                }
                            }
                        }
                        fun removeTileWorker() {
                            val worker = tileWorkers.removeLastOrNull() ?: return
                            worker.cancel()
                        }
                        fun removeDemWorker() {
                            val worker = demWorkers.removeLastOrNull() ?: return
                            worker.cancel()
                        }

                        var initialTileWorkers = if (includeDem) 6 else 10
                        // GeoTIFF tiles are large (25–54 MB each); 2 concurrent downloads is plenty.
                        var initialDemWorkers = if (includeDem && archiveDemDir != null) 2 else 0
                        if (initialTileWorkers + initialDemWorkers > maxWorkers) {
                            initialTileWorkers = maxWorkers - initialDemWorkers
                        }
                        repeat(initialTileWorkers) { addTileWorker() }
                        repeat(initialDemWorkers) { addDemWorker() }

                        var priorRate = 0.0
                        var priorCompleted = 0
                        var ramping = true
                        val adaptiveManager = launch {
                            while (isActive) {
                                delay(8_000L)
                                val done = completed.get()
                                val delta = (done - priorCompleted).coerceAtLeast(0)
                                priorCompleted = done
                                val currentRate = delta / 8.0
                                val totalWorkers = tileWorkers.size + demWorkers.size
                                val demRemaining = (estimatedDemOps - demCompleted.get()).coerceAtLeast(0)
                                val tileRemaining = (estimatedTileOps - tileCompleted.get()).coerceAtLeast(0)

                                if (demRemaining <= 0) {
                                    while (demWorkers.isNotEmpty()) removeDemWorker()
                                    while (tileWorkers.size < maxWorkers) addTileWorker()
                                    phase = "Downloading map tiles"
                                    continue
                                }

                                phase = if (demRemaining > 0) "Downloading map + DEM" else "Downloading map tiles"
                                if (totalWorkers < minWorkers) {
                                    if (tileWorkers.isEmpty()) addTileWorker()
                                    else if (includeDem && demWorkers.isEmpty()) addDemWorker()
                                }

                                if (ramping && totalWorkers < maxWorkers) {
                                    if (priorRate > 0.0 && currentRate < priorRate * 1.03) {
                                        ramping = false
                                        if (demWorkers.size > 1) removeDemWorker() else if (tileWorkers.size > 1) removeTileWorker()
                                    } else {
                                        if (tileRemaining >= demRemaining) addTileWorker() else addDemWorker()
                                    }
                                } else if (!ramping && totalWorkers > minWorkers) {
                                    if (priorRate > 0.0 && currentRate < priorRate * 0.92) {
                                        if (demWorkers.size > 1) removeDemWorker() else if (tileWorkers.size > 1) removeTileWorker()
                                    }
                                }
                                priorRate = currentRate
                            }
                        }

                        tileProducer.join()
                        tileWorkers.toList().forEach { it.join() }
                        if (includeDem && demProducer != null) {
                            phase = "Downloading DEM tiles"
                            demProducer.join()
                            demWorkers.toList().forEach { it.join() }
                            if (demFetched.get() > 0) demElevationService.refreshGeoTiffCatalog()
                        }
                        adaptiveManager.cancel()
                    }
                    progressTicker.cancel()
                }

                val elapsedMs = System.currentTimeMillis() - startedAt
                val failTotal = totalFailed.get()
                phase = if (failTotal > 0) "Complete with failures" else "Complete"
                pushProgress(force = true)
                val hit = hits.get()
                val fetch = fetched.get()
                val tileFail = tileFailed.get()
                val demFail = demFailed.get()
                withContext(Dispatchers.Main.immediate) {
                    offlinePrepInFlight = false
                    offlinePrepJob = null
                    offlinePrepCompletedSelectionKey = selectionKey
                    offlinePrepActiveCalls.clear()
                    offlinePrepCancelRequested = false
                    val doneMsg =
                        if (failTotal > 0) {
                            "Download map finished with failures: hit=$hit fetched=$fetch tileFail=$tileFail demFail=$demFail totalFail=$failTotal in ${elapsedMs}ms"
                        } else {
                            "Download map done: hit=$hit fetched=$fetch tileFail=$tileFail demFail=$demFail totalFail=$failTotal in ${elapsedMs}ms"
                        }
                    CaltopoClient.ShowToast(doneMsg)
                    MapCacheDebug.log(doneMsg)
                    offlinePrepAutoCloseJob?.cancel()
                    if (failTotal == 0) {
                        offlinePrepAutoCloseJob = uiScope.launch {
                            delay(1500L)
                            if (!offlinePrepInFlight && offlinePrepProgress.phase == "Complete") {
                                showOfflinePrepDialog = false
                            }
                        }
                    } else {
                        offlinePrepAutoCloseJob = null
                    }
                }
            } catch (_: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    phase = "Cancelled"
                    offlinePrepInFlight = false
                    offlinePrepJob = null
                    offlinePrepActiveCalls.clear()
                    offlinePrepCancelRequested = false
                    offlinePrepAutoCloseJob?.cancel()
                    offlinePrepAutoCloseJob = null
                    offlinePrepProgress = offlinePrepProgress.copy(phase = "Cancelled")
                    CaltopoClient.ShowToast("Download map cancelled.")
                }
                MapCacheDebug.log("download map cancelled at completed=${completed.get()}")
            } catch (e: Exception) {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    phase = "Failed"
                    offlinePrepInFlight = false
                    offlinePrepJob = null
                    offlinePrepActiveCalls.clear()
                    offlinePrepCancelRequested = false
                    offlinePrepAutoCloseJob?.cancel()
                    offlinePrepAutoCloseJob = null
                    offlinePrepProgress = offlinePrepProgress.copy(phase = "Failed")
                    CaltopoClient.ShowToast("Download map failed: ${e.javaClass.simpleName}")
                }
                MapCacheDebug.log("download map failed err=${e.javaClass.simpleName}:${e.message}")
            }
        }
    }

    fun startArtifactHydration(
        reason: String,
        replaceWhenSnapshotEmpty: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        artifactHydrationJob?.cancel()
        val runId = artifactHydrationRunId + 1
        artifactHydrationRunId = runId
        val replaceIfEmpty = artifactStoreById.isEmpty()
        val hydrationStartVersion = artifactRenderCache.featureVersion
        val hiddenFoldersSnapshot = hiddenFolderIds.toSet()
        val hiddenItemsSnapshot = hiddenItemIds.toSet()
        val appContext = context.applicationContext
        mapBackgroundWorkStatus = MapPaneBackgroundWorkStatus("Reading map items")
        artifactHydrationJob = uiScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val snapshot = CaltopoMap.GetArtifactFeatureSnapshot()
                    val shouldReplace = snapshot.isNotEmpty() || replaceIfEmpty || replaceWhenSnapshotEmpty
                    if (!shouldReplace) {
                        return@withContext null
                    }
                    buildArtifactHydrationResult(
                        snapshot = snapshot,
                        hiddenFolderIds = hiddenFoldersSnapshot,
                        hiddenItemIds = hiddenItemsSnapshot,
                        pilotArchiveTrackColorForCallsign = { pilotKey ->
                            PilotDisplayPrefs.load(appContext, pilotKey).archiveTrackColor
                        }
                    ) { progress ->
                        uiScope.launch(Dispatchers.Main.immediate) {
                            if (artifactHydrationRunId == runId) {
                                mapBackgroundWorkStatus = MapPaneBackgroundWorkStatus(
                                    label = "Hydrating map items",
                                    completed = progress.completed,
                                    total = progress.total
                                )
                            }
                        }
                    }
                }
                if (artifactHydrationRunId != runId) return@launch
                if (result != null) {
                    val autoHiddenMovedMarkerIds = movedDroneFolderMarkerIds(
                        previousFeatures = artifactStoreById,
                        incomingFeatures = result.featuresById,
                        expectedDroneFolderId = CaltopoMap.GetFolderId()
                    )
                    if (autoHiddenMovedMarkerIds.isNotEmpty()) {
                        hiddenItemIds.addAll(autoHiddenMovedMarkerIds)
                        if (CTDebugEnabled(MAP_PANE_TAG)) CTDebug(
                            MAP_PANE_TAG,
                            "Auto-hid moved Drone Tracks marker(s) from full snapshot: $autoHiddenMovedMarkerIds"
                        )
                    }
                    val overlayHiddenFolderIds = hiddenFoldersSnapshot + result.serverHiddenFolderIds
                    val overlayHiddenItemIds = hiddenItemsSnapshot + autoHiddenMovedMarkerIds
                    val cacheChangedDuringHydration = artifactRenderCache.featureVersion != hydrationStartVersion
                    val mergedFeatures = artifactRenderCache.mergedHydrationFeatures(
                        hydratedFeatures = result.featuresById,
                        hydrationStartVersion = hydrationStartVersion
                    )
                    val mergedOverlayState = if (cacheChangedDuringHydration || autoHiddenMovedMarkerIds.isNotEmpty()) {
                        withContext(Dispatchers.Default) {
                            buildArtifactOverlayState(
                                mergedFeatures.values.toList(),
                                overlayHiddenFolderIds,
                                overlayHiddenItemIds,
                                pilotArchiveTrackColorForCallsign = { pilotKey ->
                                    PilotDisplayPrefs.load(appContext, pilotKey).archiveTrackColor
                                }
                            )
                        }
                    } else {
                        result.overlayState
                    }
                    artifactRenderCache.replace(mergedFeatures, mergedOverlayState)
                    result.folderDefaults.forEach { folderDefault ->
                        viewModel.applyCaltopoFolderDefault(
                            folderDefault.folderId,
                            folderDefault.initiallyVisible
                        )
                    }
                    artifactOverlayState = mergedOverlayState
                    if (MAP_PANE_VERBOSE_LOGS || mergedFeatures.isNotEmpty() || artifactOverlayState.totalFeatures > 0) {
                        if (CTDebugEnabled(MAP_PANE_TAG)) CTDebug(
                            MAP_PANE_TAG,
                            "Hydrated artifacts from snapshot ($reason): cached=${mergedFeatures.size}, " +
                                "renderable=${artifactOverlayState.totalFeatures} mergedDeltas=$cacheChangedDuringHydration"
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MapCacheDebug.log("artifact hydration failed reason=$reason err=${e.javaClass.simpleName}:${e.message}")
            } finally {
                if (artifactHydrationRunId == runId && mapBackgroundWorkStatus?.label?.contains("map items") == true) {
                    mapBackgroundWorkStatus = null
                }
                if (artifactHydrationRunId == runId) {
                    artifactHydrationJob = null
                    onComplete?.invoke()
                    artifactHydrationRunId = runId + 1
                }
            }
        }
    }

    fun startArtifactOverlayRebuild(reason: String) {
        artifactOverlayRebuildJob?.cancel()
        val runId = artifactOverlayRebuildRunId + 1
        artifactOverlayRebuildRunId = runId
        val featuresSnapshot = artifactStoreById.values.toList()
        val hiddenFoldersSnapshot = hiddenFolderIds.toSet()
        val hiddenItemsSnapshot = hiddenItemIds.toSet()
        val appContext = context.applicationContext
        mapBackgroundWorkStatus = MapPaneBackgroundWorkStatus("Updating map display")
        artifactOverlayRebuildJob = uiScope.launch {
            try {
                val computed = withContext(Dispatchers.Default) {
                    buildArtifactOverlayState(
                        featuresSnapshot,
                        hiddenFoldersSnapshot,
                        hiddenItemsSnapshot,
                        pilotArchiveTrackColorForCallsign = { pilotKey ->
                            PilotDisplayPrefs.load(appContext, pilotKey).archiveTrackColor
                        }
                    )
                }
                if (artifactOverlayRebuildRunId != runId) return@launch
                artifactRenderCache.updateOverlay(computed)
                artifactOverlayState = computed
                if (CaltopoClient.DebugLevel >= CaltopoClient.DebugLevelInfo) CTInfo(
                    MAP_PANE_TAG,
                    "Artifact overlay rebuilt reason=$reason total=${artifactOverlayState.totalFeatures} " +
                        "points=${artifactOverlayState.points.size} lines=${artifactOverlayState.lines.size} " +
                        "polygons=${artifactOverlayState.polygons.size} ignoredTrackLike=${artifactOverlayState.ignoredTrackLikeFeatures}"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MapCacheDebug.log("artifact overlay rebuild failed reason=$reason err=${e.javaClass.simpleName}:${e.message}")
            } finally {
                if (artifactOverlayRebuildRunId == runId && mapBackgroundWorkStatus?.label == "Updating map display") {
                    mapBackgroundWorkStatus = null
                }
                if (artifactOverlayRebuildRunId == runId) {
                    artifactOverlayRebuildJob = null
                }
            }
        }
    }

    LaunchedEffect(mapName) {
        if (artifactRenderCache.resetIfMapChanged(mapName)) {
            artifactOverlayState = cachedOverlayState()
        } else {
            val cached = cachedOverlayState()
            if (artifactOverlayState.totalFeatures == 0 && cached.totalFeatures > 0) {
                artifactOverlayState = cached
            }
        }
        val persistedViewport = viewModel.mapViewportState()?.takeIf {
            isUsableMapViewportState(it.latitude, it.longitude, it.zoom)
        }
        localTrackPointsByMappedId.clear()
        currentFlightTrackPointsByMappedId.clear()
        localTrackMappedIdsByRemoteId.clear()
        localTrackLastSeededTimestampByMappedId.clear()
        trackOverlayRefreshToken++
        lastRenderStats = ""
        lastAlignmentStats = ""
        lastCacheStats = ""
        lastLocalDeviceMarkerStats = ""
        insetRestoredViewportApplied = false
        localDeviceViewportRescueApplied = false
        viewModel.altitudeCoordinator.onMapReconnect()
        renderLatencyKeyByDesignator.clear()
        complianceByDesignator.clear()
        initialViewportApplied = persistedViewport != null
        initialViewportArtifactCount = -1
        restoredViewportStartupCheckComplete = true
        restoredViewportStartupWaitLogged = false
        restoredViewportStartupCheckStartedAtMs = System.currentTimeMillis()
        operatorAdjustedViewport = false
        localDeviceLocationMissingLogged = false
        startArtifactHydration("mapName=$mapName")
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(2_000L)
            localDeviceRefreshToken++
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            artifactHydrationJob?.cancel()
            artifactOverlayRebuildJob?.cancel()
            currentMapView?.let(::persistFullMapViewport)
            currentMapView = null
            latestTileMapProvider.detach()
            latestTileFetchPriorityScheduler.close()
        }
    }

    fun pilotDisplayPreferenceFor(pilotKey: String?): PilotDisplayPreference {
        val normalized = normalizePilotCallsign(pilotKey) ?: return PilotDisplayPreference()
        return pilotDisplayPrefsByKey[normalized] ?: PilotDisplayPrefs.load(context, normalized).also {
            pilotDisplayPrefsByKey[normalized] = it
        }
    }

    fun updatePilotDisplayPreference(
        settings: PilotDisplaySettingsState,
        preference: PilotDisplayPreference
    ) {
        val normalized = normalizePilotCallsign(settings.pilotKey) ?: return
        val sanitized = preference.copy(
            activeTrackColor = sanitizeTrackColor(preference.activeTrackColor, DEFAULT_ACTIVE_TRACK_COLOR),
            archiveTrackColor = sanitizeTrackColor(preference.archiveTrackColor, DEFAULT_ARCHIVE_TRACK_COLOR)
        )
        if (PilotDisplayPrefs.save(context, normalized, sanitized)) {
            pilotDisplayPrefsByKey[normalized] = sanitized
            colorPickerTarget = colorPickerTarget?.takeIf { it.settings.pilotKey == normalized }
                ?.copy(settings = settings.copy(preference = sanitized))
            pilotDisplayRefreshToken++
            startArtifactOverlayRebuild("pilot-display-preference")
        }
    }

    fun resetPilotDisplayPreference(settings: PilotDisplaySettingsState) {
        val normalized = normalizePilotCallsign(settings.pilotKey) ?: return
        if (PilotDisplayPrefs.reset(context, normalized)) {
            val defaults = PilotDisplayPreference()
            pilotDisplayPrefsByKey[normalized] = defaults
            colorPickerTarget = null
            pilotDisplayRefreshToken++
            startArtifactOverlayRebuild("pilot-display-reset")
        }
    }

    badTileDialogState?.let { dlg ->
        AlertDialog(
            onDismissRequest = { badTileDialogState = null },
            title = { Text("Remove Bad Tile?") },
            text = {
                Column {
                    Text("Tile z=${dlg.zoom} x=${dlg.x} y=${dlg.y}")
                    Text("Hash: ${dlg.hash.take(12)}...")
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = quarantineMatchingHash,
                            onCheckedChange = { quarantineMatchingHash = it }
                        )
                        Text("Also quarantine same-hash tiles")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val source = tileMapProvider.tileSource
                        tileCacheWriter.remove(source, dlg.tileIndex)
                        if (quarantineMatchingHash) {
                            BadTilePolicy.addBlockedHash(context, dlg.hash)
                            CaltopoClient.ShowToast("Tile removed and hash quarantined.")
                        } else {
                            CaltopoClient.ShowToast("Tile removed from cache.")
                        }
                        badTileDialogState = null
                    }
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { badTileDialogState = null }) { Text("Cancel") }
            }
        )
    }

    if (showMapFoldersDialog) {
        val mapFolderUiStates = buildMapFolderUiStates(artifactStoreById)
        MapFoldersDialog(
            folders = mapFolderUiStates,
            hiddenFolderIds = hiddenFolderIds,
            hiddenItemIds = hiddenItemIds,
            onFolderVisibilityChanged = { folderId, visible ->
                if (visible) hiddenFolderIds.remove(folderId) else hiddenFolderIds.add(folderId)
                startArtifactOverlayRebuild("folder-visibility")
            },
            onItemVisibilityChanged = { itemId, visible ->
                if (visible) hiddenItemIds.remove(itemId) else hiddenItemIds.add(itemId)
                startArtifactOverlayRebuild("item-visibility")
            },
            onAllItemsToggled = { itemIds, visible ->
                if (visible) hiddenItemIds.removeAll(itemIds.toSet())
                else hiddenItemIds.addAll(itemIds)
                startArtifactOverlayRebuild("bulk-item-visibility")
            },
            onDismiss = { showMapFoldersDialog = false }
        )
    }

    if (showBadTilesHowToDialog) {
        AlertDialog(
            onDismissRequest = { showBadTilesHowToDialog = false },
            title = { Text("Bad Tiles How To") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Use this when map tiles show a cached error page such as OpenStreetMap's \"Access blocked\" tile.")
                    Text("1. Turn on Auto Remove Bad Tiles if you want quarantined tiles removed automatically when encountered.")
                    Text("2. Long-press a bad tile on the map.")
                    Text("3. In the Remove Bad Tile dialog, leave \"Also quarantine same-hash tiles\" checked and press Remove.")
                    Text("4. The selected tile is removed from cache, and matching bad tiles can be suppressed across the map.")
                    Text("Clear Bad Tile Flags removes the quarantine list only. It does not remove tiles already cached.")
                    Text("Export Bad Tile Hashes saves the quarantined hashes for troubleshooting or sharing.")
                }
            },
            confirmButton = {
                TextButton(onClick = { showBadTilesHowToDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    if (showMapCacheSizeDialog) {
        AlertDialog(
            onDismissRequest = { showMapCacheSizeDialog = false },
            title = { Text("Max Cache Size") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the maximum tile cache size in decimal GB.")
                    OutlinedTextField(
                        value = mapCacheSizeInput,
                        onValueChange = { mapCacheSizeInput = it },
                        label = { Text("Decimal GB") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val gb = mapCacheSizeInput.toDoubleOrNull()
                        if (gb == null || gb <= 0.0) {
                            CaltopoClient.ShowToast("Enter a positive cache size in GB.")
                            return@TextButton
                        }
                        val bytes = (gb * 1_000_000_000.0).toLong()
                        MapCacheSettings.setMaxCacheBytes(context, bytes)
                        offlinePrepTileCacheCapBytes = MapCachePolicy.tileCacheMaxBytes(context)
                        mapCacheSizeInput = String.format(
                            Locale.US,
                            "%.1f",
                            MapCacheSettings.maxCacheBytes(context).toDouble() / 1_000_000_000.0
                        )
                        showMapCacheSizeDialog = false
                        CaltopoClient.ShowToast("Map cache size saved. Startup cache maintenance will use the new limit next launch.")
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMapCacheSizeDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showMapTileAgeDialog) {
        AlertDialog(
            onDismissRequest = { showMapTileAgeDialog = false },
            title = { Text("Maximum Tile Age") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the maximum tile retention age in days.")
                    OutlinedTextField(
                        value = mapTileAgeDaysInput,
                        onValueChange = { mapTileAgeDaysInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Days") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val days = mapTileAgeDaysInput.toLongOrNull()
                        if (days == null || days <= 0L) {
                            CaltopoClient.ShowToast("Enter a positive tile age in days.")
                            return@TextButton
                        }
                        MapCacheSettings.setMaxTileAgeDays(context, days)
                        mapTileAgeDaysInput = MapCacheSettings.maxTileAgeDays(context).toString()
                        showMapTileAgeDialog = false
                        CaltopoClient.ShowToast("Maximum tile age saved. Startup cache maintenance will use the new limit next launch.")
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMapTileAgeDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    colorPickerTarget?.let { target ->
        PilotTrackColorPickerDialog(
            target = target,
            onDismiss = { colorPickerTarget = null },
            onColorSelected = { selectedColor ->
                val updatedPreference = when (target.slot) {
                    PilotDisplayColorSlot.Active -> target.settings.preference.copy(activeTrackColor = selectedColor)
                    PilotDisplayColorSlot.Archive -> target.settings.preference.copy(archiveTrackColor = selectedColor)
                }
                updatePilotDisplayPreference(target.settings, updatedPreference)
                colorPickerTarget = null
            }
        )
    }

    openBubbleDesignator?.takeIf { colorPickerTarget == null }?.let { designator ->
        val point = dronePoints.firstOrNull { it.designator == designator }
        if (point != null) {
            val coordinateDisplayFormat = viewModel.coordinateDisplayFormat
            var coordinateMenuExpanded by remember(designator, coordinateDisplayFormat) { mutableStateOf(false) }
            // AGL, ATO — read from coordinator (same values shown in the map label).
            val bubbleDisplayState = viewModel.droneDisplayStateFor(point.designator)
            val aglFeet  = bubbleDisplayState?.aglFt
            val aglStale = bubbleDisplayState?.aglStale ?: false
            val atoFeet  = bubbleDisplayState?.atoFt
            val rangeFeet = distanceFeetFromTakeoff(point)
            val telemetry = point.droneSpec?.lastPositionTelemetry
            val headingDeg = bubbleDisplayState?.headingDeg ?: telemetry?.aircraftTrackDeg
            val detailLines = droneDetailLines(
                locationText = CoordinateFormatter.format(point.lat, point.lng, coordinateDisplayFormat),
                coordinateFormatLabel = coordinateDisplayFormat.label,
                atoFeet = atoFeet,
                aglFeet = aglFeet,
                aglStale = aglStale,
                rangeFeet = rangeFeet,
                headingDeg = headingDeg,
                speedKnots = telemetry?.aircraftGsKnots,
                climbFpm = telemetry?.aircraftAltitudeRateFpm
            )
            val pilotKey = normalizePilotCallsign(point.droneSpec?.owner)
            val pilotSettings = pilotKey?.let {
                PilotDisplaySettingsState(
                    pilotKey = it,
                    displayName = point.droneSpec?.owner?.trim()?.takeIf { owner -> owner.isNotBlank() } ?: it,
                    preference = pilotDisplayPreferenceFor(it)
                )
            }
            AlertDialog(
                onDismissRequest = { openBubbleDesignator = null },
                title = { Text(point.designator) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = detailLines.first(),
                            modifier = Modifier.clickable { coordinateMenuExpanded = true }
                        )
                        DropdownMenu(
                            expanded = coordinateMenuExpanded,
                            onDismissRequest = { coordinateMenuExpanded = false }
                        ) {
                            CoordinateDisplayFormat.values().forEach { format ->
                                DropdownMenuItem(
                                    text = { Text(format.label) },
                                    onClick = {
                                        coordinateMenuExpanded = false
                                        viewModel.setCoordinateDisplayFormat(format)
                                    }
                                )
                            }
                        }
                        detailLines.drop(1).forEach { line ->
                            Text(line)
                        }
                        pilotSettings?.let { settings ->
                            Spacer(Modifier.height(12.dp))
                            PilotDisplaySettingsContent(
                                settings = settings,
                                onPreferenceChange = ::updatePilotDisplayPreference,
                                onPickColor = { slot ->
                                    colorPickerTarget = PilotColorPickerTarget(settings, slot)
                                },
                                onReset = {
                                    resetPilotDisplayPreference(settings)
                                }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { openBubbleDesignator = null }) { Text("Close") }
                },
                dismissButton = {}
            )
        }
    }

    DisposableEffect(Unit) {
        startArtifactHydration("listener-init")
        val listener = CaltopoMap.ArtifactListener { feature, source, _ ->
            if (source == "full") {
                uiScope.launch(Dispatchers.Main.immediate) {
                    if (fullArtifactHydrationQueued) {
                        return@launch
                    }
                    fullArtifactHydrationQueued = true
                    startArtifactHydration("ingest-full", replaceWhenSnapshotEmpty = true) {
                        fullArtifactHydrationQueued = false
                    }
                }
                return@ArtifactListener
            }
            uiScope.launch(Dispatchers.Main.immediate) {
                val featureId = feature.optString("id")
                if (featureId.isBlank()) {
                    return@launch
                }

                if (isArtifactDelete(feature)) {
                    artifactRenderCache.removeFeature(featureId)
                } else {
                    val previousFeature = artifactRenderCache.featuresById[featureId]
                    val autoHiddenMovedMarkerIds = movedDroneFolderMarkerIds(
                        previousFeatures = if (previousFeature == null) emptyMap() else mapOf(featureId to previousFeature),
                        incomingFeatures = mapOf(featureId to feature),
                        expectedDroneFolderId = CaltopoMap.GetFolderId()
                    )
                    if (autoHiddenMovedMarkerIds.isNotEmpty()) {
                        hiddenItemIds.addAll(autoHiddenMovedMarkerIds)
                        if (CTDebugEnabled(MAP_PANE_TAG)) CTDebug(
                            MAP_PANE_TAG,
                            "Auto-hid moved Drone Tracks marker(s) from artifact delta: $autoHiddenMovedMarkerIds"
                        )
                    }
                    artifactRenderCache.putFeature(featureId, feature)
                    // Auto-hide folders the server marks as not visible, on first encounter.
                    // Delegates to ViewModel so the choice persists across navigation.
                    val props = feature.optJSONObject("properties")
                    if (props?.optString("class") == "Folder") {
                        viewModel.applyCaltopoFolderDefault(featureId, props.optBoolean("visible", true))
                    } else {
                        applySyntheticArtifactFolderDefault(props, viewModel)
                    }
                }

                startArtifactOverlayRebuild("ingest-$source")
                if (CaltopoClient.DebugLevel >= CaltopoClient.DebugLevelInfo) CTInfo(
                    MAP_PANE_TAG,
                    "Artifact ingest source=$source ${artifactLogSummary(feature)} queued overlay rebuild"
                )
            }
        }

        CaltopoMap.AddArtifactListener(listener)
        CaltopoMap.RequestMapRefreshNow()
        onDispose {
            CaltopoMap.RemoveArtifactListener(listener)
        }
    }

    fun seedActiveLocalTrackSnapshots(seedTimeMs: Long, reason: String) {
        viewModel.droneStates.forEach { (key, state) ->
            val snapshot = WaypointTrack.GetTrackPointsSnapshot(state.source)
            if (snapshot.isEmpty()) return@forEach
            if (!shouldSeedLocalTrackSnapshotForDesignator(
                    key,
                    snapshot,
                    localTrackLastSeededTimestampByMappedId
                )) {
                return@forEach
            }
            val list = localTrackPointsByMappedId.getOrPut(key) { mutableStateListOf() }
            val flightList = currentFlightTrackPointsByMappedId.getOrPut(key) { mutableStateListOf() }
            if (seedLocalTrackPointsFromSnapshot(key, snapshot, seedTimeMs, list, flightList)) {
                trackOverlayRefreshToken++
                if (CTDebugEnabled(ICON_LATENCY_TAG)) CTDebug(
                    ICON_LATENCY_TAG,
                    "track_seed_snapshot designator=$key points=${snapshot.size} reason=$reason"
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow {
            viewModel.droneStates.map { (key, state) ->
                listOf(
                    key,
                    state.mappedId,
                    state.flightStartMsec.toString(),
                    state.lastTimestamp,
                    state.lastLat.toString(),
                    state.lastLng.toString()
                ).joinToString(":")
            }.sorted()
        }.collect {
            seedActiveLocalTrackSnapshots(System.currentTimeMillis(), "state")
        }
    }

    DisposableEffect(Unit) {
        val localTrackListener = CaltopoLiveTrack.LocalTrackListener { remoteId, mappedId, lat, lng, altitudeMeters, timestampMsec ->
            uiScope.launch(Dispatchers.Main.immediate) {
                if (!lat.isFinite() || !lng.isFinite()) return@launch
                if (lat == 0.0 && lng == 0.0) return@launch
                val nowWallMsec = System.currentTimeMillis()
                val key = localTrackDesignator(mappedId)
                localTrackMappedIdsByRemoteId.getOrPut(remoteId) { mutableSetOf() }.add(key)
                val list = localTrackPointsByMappedId.getOrPut(key) { mutableStateListOf() }
                val flightList = currentFlightTrackPointsByMappedId.getOrPut(key) { mutableStateListOf() }
                val point = LocalTrackPoint(
                    mappedId = key,
                    lat = lat,
                    lng = lng,
                    altitudeM = altitudeMeters,
                    timestampMsec = timestampMsec,
                    receivedAtMsec = nowWallMsec
                )
                list.add(point)
                flightList.add(point)
                trackOverlayRefreshToken++
                if (CTDebugEnabled(ICON_LATENCY_TAG))  CTDebug(
                    ICON_LATENCY_TAG,
                    "track_ingest designator=$key wall=$nowWallMsec droneTs=$timestampMsec " +
                        "lat=${"%.6f".format(Locale.US, lat)} lng=${"%.6f".format(Locale.US, lng)} alt=${"%.1f".format(Locale.US, altitudeMeters)}"
                )
                if (list.size > LOCAL_TRACK_RECENT_POINT_LIMIT) {
                    list.removeAt(0)
                }
                if (flightList.size > LOCAL_TRACK_FLIGHT_POINT_LIMIT) {
                    flightList.removeAt(0)
                }
            }
        }
        val localTrackFinishedListener = CaltopoLiveTrack.LocalTrackFinishedListener { remoteId, mappedId, _ ->
            uiScope.launch(Dispatchers.Main.immediate) {
                val mappedIds = localTrackMappedIdsByRemoteId.remove(remoteId).orEmpty() +
                    localTrackDesignator(mappedId)
                mappedIds.forEach { key ->
                    localTrackPointsByMappedId.remove(key)
                    currentFlightTrackPointsByMappedId.remove(key)
                    localTrackLastSeededTimestampByMappedId.remove(key)
                }
                trackOverlayRefreshToken++
            }
        }
        // Seed from the active WaypointTrack so reopening MapPane mid-flight preserves
        // points collected while this composable was not active.
        seedActiveLocalTrackSnapshots(System.currentTimeMillis(), "mount")
        viewModel.droneStates.forEach { (key, state) ->
            val snapshot = WaypointTrack.GetTrackPointsSnapshot(state.source)
            if (snapshot.isNotEmpty()) {
                return@forEach
            }

            // Fall back to the current drone state if no active local track snapshot exists yet.
            val seedLat = state.lastLat
            val seedLng = state.lastLng
            if (seedLat.isFinite() && seedLng.isFinite() && !(seedLat == 0.0 && seedLng == 0.0)
                    && state.source.mostRecentMsecTimestamp > 0) {
                val list = localTrackPointsByMappedId.getOrPut(key) { mutableStateListOf() }
                val flightList = currentFlightTrackPointsByMappedId.getOrPut(key) { mutableStateListOf() }
                if (list.isEmpty()) {
                    val point = LocalTrackPoint(
                        mappedId = key,
                        lat = seedLat,
                        lng = seedLng,
                        altitudeM = state.lastAlt,
                        timestampMsec = state.source.mostRecentMsecTimestamp,
                        receivedAtMsec = System.currentTimeMillis()
                    )
                    list.add(point)
                    if (flightList.isEmpty()) {
                        flightList.add(point)
                    }
                    trackOverlayRefreshToken++
                }
            }
        }
        CaltopoLiveTrack.AddLocalTrackListener(localTrackListener)
        CaltopoLiveTrack.AddLocalTrackFinishedListener(localTrackFinishedListener)
        onDispose {
            CaltopoLiveTrack.RemoveLocalTrackListener(localTrackListener)
            CaltopoLiveTrack.RemoveLocalTrackFinishedListener(localTrackFinishedListener)
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
                configureOsmdroid(context)
                MapView(context).apply {
                    currentMapView = this
                    setMultiTouchControls(!isInsetMode)
                    setTileProvider(tileMapProvider)
                    setUseDataConnection(true)
                    tileMapProvider.setUseDataConnection(true)
                    setMaxZoomLevel(MAP_DISPLAY_MAX_ZOOM)
                    setOnTouchListener { _, event ->
                        when (event?.actionMasked) {
                            MotionEvent.ACTION_DOWN,
                            MotionEvent.ACTION_POINTER_DOWN,
                            MotionEvent.ACTION_MOVE -> {
                                if (!isInsetMode && !operatorAdjustedViewport) {
                                    operatorAdjustedViewport = true
                                    CTDebug(MAP_PANE_TAG, "Map viewport operator-adjusted; suppressing startup recenter")
                                }
                            }
                        }
                        false
                    }
                    val initialViewport = restoredViewport
                    if (initialViewport != null) {
                        controller.setCenter(GeoPoint(initialViewport.latitude, initialViewport.longitude))
                        controller.setZoom(initialViewport.zoom)
                    } else {
                        controller.setZoom(14.0)
                    }
                    addMapListener(
                        object : MapListener {
                            override fun onScroll(event: ScrollEvent?): Boolean {
                                if (!isInsetMode) {
                                    persistFullMapViewport(this@apply)
                                }
                                return false
                            }

                            override fun onZoom(event: ZoomEvent?): Boolean {
                                if (!isInsetMode) {
                                    persistFullMapViewport(this@apply)
                                }
                                val eventZoom = event?.zoomLevel ?: zoomLevelDouble
                                val eventTileZoom = TileSystem.getInputTileZoomLevel(eventZoom)
                                if (visibleTileZoom != eventTileZoom) {
                                    val previousTileZoom = visibleTileZoom
                                    visibleTileZoom = eventTileZoom
                                    if (needsViewportTileProviderRestart(
                                            previousTileZoom = previousTileZoom,
                                            currentTileZoom = eventTileZoom,
                                            baseSourceChanged = false
                                        )
                                    ) {
                                        tileMapProvider = restartTileProviderForViewportIntent(
                                            mapView = this@apply,
                                            context = context,
                                            tileSource = latestBaseTileSource,
                                            tileWriter = tileCacheWriter,
                                            reason = "zoom-change",
                                            zoom = eventTileZoom
                                        )
                                    }
                                }
                                return false
                            }
                        }
                    )
                }
            },
            update = { mapView ->
                trackOverlayRefreshToken
                pilotDisplayRefreshToken
                localDeviceRefreshToken
                val uiNowWallMsec = System.currentTimeMillis()
                mapBounds = mapView.boundingBox
                val tileSource = baseTileSource
                val maxZoom = MAP_DISPLAY_MAX_ZOOM
                if (mapView.maxZoomLevel != maxZoom) {
                    mapView.setMaxZoomLevel(maxZoom)
                }
                val currentTileZoom = TileSystem.getInputTileZoomLevel(mapView.zoomLevelDouble)
                val baseSourceChanged = needsBaseTileProviderRestart(
                    currentSourceName = mapView.tileProvider.tileSource.name(),
                    desiredTileSource = tileSource
                )
                if (baseSourceChanged) {
                    tileMapProvider = restartTileProviderForViewportIntent(
                        mapView = mapView,
                        context = context,
                        tileSource = tileSource,
                        tileWriter = tileCacheWriter,
                        reason = "base-layer-change",
                        zoom = currentTileZoom
                    )
                    tileIoActiveUntilMs = uiNowWallMsec + TILE_IO_ACTIVE_GRACE_MS
                }
                if (visibleTileZoom != currentTileZoom) {
                    val previousTileZoom = visibleTileZoom
                    visibleTileZoom = currentTileZoom
                    if (needsViewportTileProviderRestart(
                            previousTileZoom = previousTileZoom,
                            currentTileZoom = currentTileZoom,
                            baseSourceChanged = baseSourceChanged
                        )
                    ) {
                        tileMapProvider = restartTileProviderForViewportIntent(
                            mapView = mapView,
                            context = context,
                            tileSource = tileSource,
                            tileWriter = tileCacheWriter,
                            reason = "viewport-update",
                            zoom = currentTileZoom
                        )
                    }
                }

                val center = mapView.mapCenter
                val viewportSignature = String.format(
                    Locale.US,
                    "%.5f|%.5f|%.3f|%d|%d|%s|contours=%s",
                    center.latitude,
                    center.longitude,
                    mapView.zoomLevelDouble,
                    mapView.width,
                    mapView.height,
                    tileSource.name(),
                    contourOverlayEnabled
                )
                if (lastViewportSignature != viewportSignature) {
                    lastViewportSignature = viewportSignature
                    tileIoActiveUntilMs = uiNowWallMsec + TILE_IO_ACTIVE_GRACE_MS
                }
                val suppressLiveMapNetwork = offlinePrepInFlight && baseLayer == BaseLayerOption.OpenStreetMap
                val tileNetworkActive = visibleTileNetworkActive(suppressLiveMapNetwork)
                if (mapView.useDataConnection() != tileNetworkActive) {
                    mapView.setUseDataConnection(tileNetworkActive)
                }
                if (tileMapProvider.useDataConnection() != tileNetworkActive) {
                    tileMapProvider.setUseDataConnection(tileNetworkActive)
                }
                tileMapProvider.setOfflineFirst(offlineFirstForVisibleTiles(tileNetworkActive))
                tileMapProvider.setCacheLookupEnabled(true)
                contourTileMapProvider.setUseDataConnection(tileNetworkActive)
                contourTileMapProvider.setOfflineFirst(offlineFirstForVisibleTiles(tileNetworkActive))
                contourTileMapProvider.setCacheLookupEnabled(true)

                if (managedOverlays.isNotEmpty()) {
                    mapView.overlays.removeAll(managedOverlays)
                    managedOverlays.clear()
                }

                val tapOverlay = MapEventsOverlay(
                    object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            InfoWindow.closeAllInfoWindowsOn(mapView)
                            onSingleTapFocus?.invoke()
                            return false
                        }

                        override fun longPressHelper(p: GeoPoint?): Boolean {
                            val press = p ?: return false
                            val zoom = TileSystem.getInputTileZoomLevel(mapView.zoomLevelDouble)
                            val tx = lonToTileX(press.longitude, zoom)
                            val ty = latToTileY(press.latitude, zoom)
                            val tileIndex = MapTileIndex.getTileIndex(zoom, tx, ty)
                            val source = tileMapProvider.tileSource
                            val hash = tileCacheWriter.tileHash(source, tileIndex)
                            if (hash == null) {
                                CaltopoClient.ShowToast("Selected tile is not cached yet.")
                                return false
                            }
                            quarantineMatchingHash = true
                            badTileDialogState = BadTileDialogState(
                                tileIndex = tileIndex,
                                zoom = zoom,
                                x = tx,
                                y = ty,
                                hash = hash
                            )
                            return true
                        }
                    }
                )
                mapView.overlays.add(tapOverlay)
                managedOverlays.add(tapOverlay)

                if (contourOverlayEnabled) {
                    val contourOverlay = TilesOverlay(contourTileMapProvider, context).apply {
                        setLoadingDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
                        setUseDataConnection(tileNetworkActive)
                    }
                    mapView.overlays.add(contourOverlay)
                    managedOverlays.add(contourOverlay)
                }

                artifactOverlayState.polygons.forEach { polygonSpec ->
                    val polygonFill = Polygon(mapView).apply {
                        points = polygonSpec.points
                        title = ""
                        applyPolygonStyle(
                            polygon = this,
                            strokeColor = AndroidColor.TRANSPARENT,
                            fillColor = polygonSpec.fillColor,
                            strokeWidth = 0f
                        )
                        setOnClickListener { _, _, _ -> false }
                    }
                    mapView.overlays.add(polygonFill)
                    managedOverlays.add(polygonFill)

                    val polygonBoundary = Polyline(mapView).apply {
                        setPoints(closedPolylinePoints(polygonSpec.points))
                        title = polygonSpec.title
                        applyPolylineStyle(this, polygonSpec.strokeColor, polygonSpec.strokeWidth * lineScale)
                    }
                    mapView.overlays.add(polygonBoundary)
                    managedOverlays.add(polygonBoundary)
                }

                val notamOverlayState = NotamMapOverlayAdapter.build(notamUiState, CaltopoMap.GetMyLocation())
                notamOverlayState.polygons.forEach { polygonSpec ->
                    val polygonFill = Polygon(mapView).apply {
                        points = polygonSpec.points
                        title = ""
                        applyPolygonStyle(
                            polygon = this,
                            strokeColor = AndroidColor.TRANSPARENT,
                            fillColor = polygonSpec.fillColor,
                            strokeWidth = 0f
                        )
                        setOnClickListener { _, _, _ -> false }
                    }
                    mapView.overlays.add(polygonFill)
                    managedOverlays.add(polygonFill)

                    val polygonBoundary = Polyline(mapView).apply {
                        setPoints(closedPolylinePoints(polygonSpec.points))
                        title = polygonSpec.title
                        applyPolylineStyle(this, polygonSpec.strokeColor, polygonSpec.strokeWidth * lineScale)
                        polygonSpec.notice?.let { notice ->
                            if (!isInsetMode) {
                                setOnClickListener { _, _, _ ->
                                    selectedNotam = notice
                                    true
                                }
                            }
                        }
                    }
                    mapView.overlays.add(polygonBoundary)
                    managedOverlays.add(polygonBoundary)
                }

                artifactOverlayState.lines.forEach { lineSpec ->
                    val line = Polyline(mapView).apply {
                        setPoints(lineSpec.points)
                        title = lineSpec.title
                        applyPolylineStyle(this, lineSpec.color, lineSpec.width * lineScale)
                    }
                    mapView.overlays.add(line)
                    managedOverlays.add(line)
                }

                notamOverlayState.lines.forEach { lineSpec ->
                    val line = Polyline(mapView).apply {
                        setPoints(lineSpec.points)
                        title = lineSpec.title
                        applyPolylineStyle(this, lineSpec.color, lineSpec.width * lineScale)
                        if (!isInsetMode) {
                            setOnClickListener { _, _, _ ->
                                selectedNotam = lineSpec.notice
                                true
                            }
                        }
                    }
                    mapView.overlays.add(line)
                    managedOverlays.add(line)
                }

                val trackPointMinTimestampMs = System.currentTimeMillis() - (CaltopoClient.GetNewTrackDelayInSeconds() * 1000L)
                val expiredTrackIds = mutableListOf<String>()
                localTrackPointsByMappedId.forEach { (mappedId, points) ->
                    while (points.isNotEmpty() && points.first().receivedAtMsec <= trackPointMinTimestampMs) {
                        points.removeAt(0)
                    }
                    if (points.isEmpty()) {
                        expiredTrackIds += mappedId
                    }
                }
                expiredTrackIds.forEach { localTrackPointsByMappedId.remove(it) }

                val confirmedCurrentFlightMappedIds = confirmedCurrentFlightMappedIds(dronePoints)
                val fullFlightTrackMappedIds = fullFlightTrackMappedIds(
                    dronePoints = dronePoints,
                    eligibleMappedIds = confirmedCurrentFlightMappedIds,
                    mappedIdsByRemoteId = localTrackMappedIdsByRemoteId
                )
                val pilotPreferencesByMappedId = pilotDisplayPreferencesByMappedId(
                    dronePoints = dronePoints,
                    mappedIdsByRemoteId = localTrackMappedIdsByRemoteId,
                    preferenceForPilotKey = ::pilotDisplayPreferenceFor
                )
                currentFlightTrackPointsByMappedId.forEach { (mappedId, points) ->
                    if (mappedId !in fullFlightTrackMappedIds) return@forEach
                    if (points.size < 2) return@forEach
                    val trackColor = trackColorInt(
                        pilotPreferencesByMappedId[mappedId]?.archiveTrackColor,
                        DEFAULT_ARCHIVE_TRACK_COLOR
                    )
                    val line = Polyline(mapView).apply {
                        setPoints(points.map { GeoPoint(it.lat, it.lng) })
                        title = "Flight track: $mappedId (${points.size})"
                        applyPolylineStyle(this, trackColor, 2.0f * lineScale)
                    }
                    mapView.overlays.add(line)
                    managedOverlays.add(line)
                }

                localTrackPointsByMappedId.forEach { (mappedId, points) ->
                    if (points.size < 2) return@forEach
                    val trackColor = trackColorInt(
                        pilotPreferencesByMappedId[mappedId]?.activeTrackColor,
                        DEFAULT_ACTIVE_TRACK_COLOR
                    )
                    val line = Polyline(mapView).apply {
                        setPoints(points.map { GeoPoint(it.lat, it.lng) })
                        title = "Local track: $mappedId (${points.size})"
                        applyPolylineStyle(this, trackColor, 4.0f * lineScale)
                    }
                    mapView.overlays.add(line)
                    managedOverlays.add(line)
                }

                viewModel.localMapMarkers.forEach { point ->
                    val markerTitle = "Local: ${point.title}"
                    val markerSnippet = point.description.ifBlank {
                        "Local R2C marker from ${point.sourceDesignator}"
                    }
                    val snapshot = viewModel.clueSnapshotForTitle(point.title)
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(point.lat, point.lng)
                        icon = markerIconForArtifactSymbol(
                            resources = context.resources,
                            symbol = "clue",
                            colorHex = "#F9A825",
                            cache = symbolMarkerCache,
                            scale = markerScale
                        )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = markerTitle
                        snippet = markerSnippet
                        if (!isInsetMode) {
                            setOnMarkerClickListener { tappedMarker, _ ->
                                when (markerInfoWindowTapAction(tappedMarker.isInfoWindowShown)) {
                                    MarkerInfoWindowTapAction.Close -> tappedMarker.closeInfoWindow()
                                    MarkerInfoWindowTapAction.Show -> tappedMarker.showInfoWindow()
                                }
                                true
                            }
                        }
                    }
                    if (!isInsetMode) {
                        marker.infoWindow = LocalMarkerInfoWindow(
                            mapView = mapView,
                            titleText = markerTitle,
                            descriptionText = markerSnippet,
                            thumbnail = snapshot?.thumbnail,
                            onOpenSnapshot = snapshot?.fullImage?.let { fullImage ->
                                { openClueSnapshotInExternalViewer(context, snapshot.title, fullImage) }
                            },
                            markerId = point.id,
                            onDelete = { markerId ->
                                if (viewModel.deleteLocalMapMarker(markerId)) {
                                    mapView.invalidate()
                                }
                            }
                        )
                    }
                    consumeInsetMarkerTaps(marker, isInsetMode)
                    mapView.overlays.add(marker)
                    managedOverlays.add(marker)
                }

                artifactOverlayState.points.forEach { point ->
                    val remoteCacheKey = iconCacheService.cacheKey(point.markerSymbol, point.markerColor)
                    val remoteIcon = caltopoMarkerCache[remoteCacheKey]
                    if (remoteIcon == null && !caltopoMarkerPending.contains(remoteCacheKey)) {
                        caltopoMarkerPending.add(remoteCacheKey)
                        uiScope.launch(Dispatchers.IO) {
                            val loaded = iconCacheService.loadBestAvailableDrawable(
                                resources = context.resources,
                                markerSymbol = point.markerSymbol,
                                markerColor = point.markerColor
                            )
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
                        icon = remoteIcon?.let { icon ->
                            cachedScaledRemoteMarkerDrawable(
                                resources = context.resources,
                                source = icon,
                                cache = scaledRemoteMarkerCache,
                                cacheKey = remoteCacheKey,
                                scale = markerScale
                            )
                        }
                            ?: markerIconForArtifactSymbol(
                                resources = context.resources,
                                symbol = point.markerSymbol,
                                colorHex = point.markerColor,
                                cache = symbolMarkerCache,
                                scale = markerScale
                            )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = point.title
                    }
                    if (!isInsetMode) viewModel.clueSnapshotForTitle(point.title)?.let { snapshot ->
                        marker.infoWindow = LocalMarkerInfoWindow(
                            mapView = mapView,
                            titleText = point.title,
                            descriptionText = marker.snippet ?: "",
                            thumbnail = snapshot.thumbnail,
                            onOpenSnapshot = snapshot.fullImage?.let { fullImage ->
                                { openClueSnapshotInExternalViewer(context, snapshot.title, fullImage) }
                            },
                            markerId = null,
                            onDelete = null
                        )
                        marker.setOnMarkerClickListener { tappedMarker, _ ->
                            when (markerInfoWindowTapAction(tappedMarker.isInfoWindowShown)) {
                                MarkerInfoWindowTapAction.Close -> tappedMarker.closeInfoWindow()
                                MarkerInfoWindowTapAction.Show -> tappedMarker.showInfoWindow()
                            }
                            true
                        }
                    }
                    consumeInsetMarkerTaps(marker, isInsetMode)
                    if (!isKnownArtifactSymbol(point.markerSymbol) && unknownSymbolsSeen.add(point.markerSymbol)) {
                        if (CTDebugEnabled(MAP_PANE_TAG))  CTDebug(MAP_PANE_TAG, "Unknown marker-symbol encountered: '${point.markerSymbol}'")
                    }
                    mapView.overlays.add(marker)
                    managedOverlays.add(marker)
                }

                notamOverlayState.points.forEach { point ->
                    val marker = Marker(mapView).apply {
                        position = point.point
                        icon = scaleDrawableBitmap(
                            resources = context.resources,
                            drawable = buildNotamMarkerIcon(context, point.color),
                            scale = markerScale
                        )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = point.title
                        if (!isInsetMode) {
                            setOnMarkerClickListener { _, _ ->
                                if (point.notices.size == 1) {
                                    selectedNotamGroup = null
                                    selectedNotam = point.notices.first()
                                } else {
                                    selectedNotam = null
                                    selectedNotamGroup = point.notices
                                }
                                true
                            }
                        }
                    }
                    consumeInsetMarkerTaps(marker, isInsetMode)
                    mapView.overlays.add(marker)
                    managedOverlays.add(marker)
                }

                val myLocation = CaltopoMap.GetMyLocation()
                if (myLocation != null && myLocation.latitude.isFinite() && myLocation.longitude.isFinite()) {
                    CaltopoMap.EnsureStandaloneTrackerCoordinationStarted()
                    val localDeviceColor = localDeviceMarkerColor()
                    val localCacheKey = iconCacheService.cacheKey(LOCAL_DEVICE_SYMBOL, localDeviceColor)
                    val localRemoteIcon = caltopoMarkerCache[localCacheKey]
                    if (localRemoteIcon == null && !caltopoMarkerPending.contains(localCacheKey)) {
                        caltopoMarkerPending.add(localCacheKey)
                        uiScope.launch(Dispatchers.IO) {
                            val loaded = iconCacheService.loadBestAvailableDrawable(
                                resources = context.resources,
                                markerSymbol = LOCAL_DEVICE_SYMBOL,
                                markerColor = localDeviceColor
                            )
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
                        icon = localRemoteIcon?.let { icon ->
                            cachedScaledRemoteMarkerDrawable(
                                resources = context.resources,
                                source = icon,
                                cache = scaledRemoteMarkerCache,
                                cacheKey = localCacheKey,
                                scale = markerScale
                            )
                        }
                            ?: markerIconForArtifactSymbol(
                                resources = context.resources,
                                symbol = LOCAL_DEVICE_SYMBOL,
                                colorHex = localDeviceColor,
                                cache = symbolMarkerCache,
                                scale = markerScale
                            )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        val localDeviceName = R2CActivity.MyDeviceName
                        title = if (localDeviceName.isBlank() || localDeviceName == "<unknown>") {
                            "RID2Caltopo Device"
                        } else {
                            localDeviceName
                        }
                        val statusLines = localDeviceStatusLines()
                        snippet = statusLines.firstOrNull().orEmpty()
                        setSubDescription(statusLines.drop(1).joinToString("<br/>"))
                    }
                    consumeInsetMarkerTaps(localMarker, isInsetMode)
                    mapView.overlays.add(localMarker)
                    managedOverlays.add(localMarker)
                    val mapCenter = mapView.mapCenter
                    val localDeviceVisible = mapView.boundingBox.containsLocation(myLocation)
                    val defaultViewportCenter =
                        kotlin.math.abs(mapCenter.latitude) < 0.000001 &&
                            kotlin.math.abs(mapCenter.longitude) < 0.000001
                    val operationalContentPresent =
                        dronePoints.isNotEmpty() || artifactOverlayState.totalFeatures > 0
                    if (!isInsetMode &&
                        !localDeviceViewportRescueApplied &&
                        !localDeviceVisible &&
                        defaultViewportCenter &&
                        !operationalContentPresent &&
                        !operatorAdjustedViewport
                    ) {
                        mapView.controller.setCenter(GeoPoint(myLocation.latitude, myLocation.longitude))
                        mapView.controller.setZoom(STARTUP_MY_LOCATION_MIN_ZOOM)
                        if (!isInsetMode) {
                            persistFullMapViewport(mapView)
                        }
                        initialViewportApplied = true
                        restoredViewportStartupCheckComplete = true
                        localDeviceViewportRescueApplied = true
                        CTDebug(
                            MAP_PANE_TAG,
                            String.format(
                                Locale.US,
                                "Local device viewport rescue: lat=%.6f lng=%.6f previousCenter=%.6f,%.6f zoom=%.2f",
                                myLocation.latitude,
                                myLocation.longitude,
                                mapCenter.latitude,
                                mapCenter.longitude,
                                mapView.zoomLevelDouble
                            )
                        )
                    }
                    val localDeviceMarkerStats = String.format(
                        Locale.US,
                        "lat=%.6f lng=%.6f center=%.6f,%.6f zoom=%.2f contains=%s",
                        myLocation.latitude,
                        myLocation.longitude,
                        mapView.mapCenter.latitude,
                        mapView.mapCenter.longitude,
                        mapView.zoomLevelDouble,
                        mapView.boundingBox.containsLocation(myLocation)
                    )
                    if (localDeviceMarkerStats != lastLocalDeviceMarkerStats) {
                        lastLocalDeviceMarkerStats = localDeviceMarkerStats
                        localDeviceLocationMissingLogged = false
                        if (CTDebugEnabled(MAP_PANE_TAG)) {
                            CTDebug(
                                MAP_PANE_TAG,
                                "Local device marker: $localDeviceMarkerStats ageMs=${locationAgeMs(myLocation, uiNowWallMsec)}"
                            )
                        }
                    }
                } else if (!localDeviceLocationMissingLogged) {
                    localDeviceLocationMissingLogged = true
                    CTDebug(MAP_PANE_TAG, "Local device marker skipped: CaltopoMap.GetMyLocation() unavailable.")
                }

                val iconLimitAglM = AGL_LIMIT_FT * FT_TO_METERS
                val nearIconAglM = (AGL_LIMIT_FT - AGL_ICON_NEAR_DELTA_FT) * FT_TO_METERS
                val droneLabelSpecs = mutableListOf<DroneLabelDrawSpec>()
                dronePoints.forEach { point ->
                    val pointLatencyKey =
                        "${point.timestampMsec}|${"%.6f".format(Locale.US, point.lat)}|${"%.6f".format(Locale.US, point.lng)}|${"%.1f".format(Locale.US, point.altitudeM)}"
                    if (renderLatencyKeyByDesignator[point.designator] != pointLatencyKey) {
                        val renderWallMsec = System.currentTimeMillis()
                        val ingestToRenderMs = point.receivedAtMsec?.let { renderWallMsec - it }
                        if (CTDebugEnabled(ICON_LATENCY_TAG)) CTDebug(
                            ICON_LATENCY_TAG,
                            "icon_render designator=${point.designator} wall=$renderWallMsec droneTs=${point.timestampMsec} " +
                                "lat=${"%.6f".format(Locale.US, point.lat)} lng=${"%.6f".format(Locale.US, point.lng)} " +
                                "alt=${"%.1f".format(Locale.US, point.altitudeM)} " +
                                "trackToRenderMs=${ingestToRenderMs?.toString() ?: "n/a"}"
                        )
                        renderLatencyKeyByDesignator[point.designator] = pointLatencyKey
                    }
                    val predictedHead = if (predictiveHeadEnabled) {
                        predictedHeadPoint(
                            designator = point.designator,
                            nowWallMsec = uiNowWallMsec,
                            dronePointTimestampMsec = point.timestampMsec,
                            tracksByMappedId = localTrackPointsByMappedId
                        )
                    } else {
                        null
                    }
                    val renderLat = predictedHead?.lat ?: point.lat
                    val renderLng = predictedHead?.lng ?: point.lng
                    // AGL, ATO, heading — all computed by DroneAltitudeCoordinator.
                    val displayState = viewModel.droneDisplayStateFor(point.designator)
                    val headingDeg  = displayState?.headingDeg
                    val labelAglFeet = displayState?.aglFt
                    val labelAglStale = displayState?.aglStale ?: false
                    val labelAtoFeet = displayState?.atoFt
                    val labelRangeFeet = distanceFeetFromTakeoff(point, renderLat, renderLng)
                    val pilotKey = normalizePilotCallsign(point.droneSpec?.owner)
                    val pilotPreference = pilotDisplayPreferenceFor(pilotKey)
                    if (pilotPreference.bearingEnabled) {
                        val markerGeoPoint = GeoPoint(renderLat, renderLng)
                        val startPoint = Point()
                        mapView.projection.toPixels(markerGeoPoint, startPoint)
                        if (startPoint.x in 0..mapView.width && startPoint.y in 0..mapView.height) {
                            val bearingLine = bearingLineToViewportEdge(
                                startX = startPoint.x.toDouble(),
                                startY = startPoint.y.toDouble(),
                                headingDeg = headingDeg,
                                viewportWidth = mapView.width,
                                viewportHeight = mapView.height
                            )
                            val endPoint = bearingLine?.let {
                                mapView.projection.fromPixels(
                                    it.endX.roundToInt(),
                                    it.endY.roundToInt()
                                )
                            }
                            if (endPoint != null &&
                                endPoint.latitude.isFinite() &&
                                endPoint.longitude.isFinite()
                            ) {
                                val bearingOverlay = Polyline(mapView).apply {
                                    setPoints(
                                        listOf(
                                            markerGeoPoint,
                                            GeoPoint(endPoint.latitude, endPoint.longitude)
                                        )
                                    )
                                    title = "Bearing: ${point.designator}"
                                    applyPolylineStyle(
                                        this,
                                        trackColorInt(pilotPreference.activeTrackColor, DEFAULT_ACTIVE_TRACK_COLOR),
                                        2.0f * lineScale
                                    )
                                }
                                mapView.overlays.add(bearingOverlay)
                                managedOverlays.add(bearingOverlay)
                            }
                        }
                    }
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(renderLat, renderLng)
                        val effectiveAglM = labelAglFeet?.let { it / METERS_TO_FEET }
                            ?: Double.NEGATIVE_INFINITY
                        val markerTint = when {
                            effectiveAglM >= iconLimitAglM -> AndroidColor.parseColor("#D32F2F")
                            effectiveAglM >= nearIconAglM -> AndroidColor.parseColor("#FBC02D")
                            else -> null
                        }
                        icon = buildDroneMarkerDrawable(
                            resources = context.resources,
                            baseIcon = droneMarkerIcon,
                            tint = markerTint,
                            headingDeg = headingDeg,
                            scale = markerScale
                        )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        if (!isInsetMode) {
                            setOnMarkerClickListener { tappedMarker, _ ->
                                if (focusedPath != point.designator) {
                                    viewModel.toggleFocus(point.designator)
                                }
                                if (tappedMarker.isInfoWindowShown) {
                                    tappedMarker.closeInfoWindow()
                                    openBubbleDesignator = null
                                } else {
                                    openBubbleDesignator = point.designator
                                }
                                true
                            }
                        }
                    }
                    consumeInsetMarkerTaps(marker, isInsetMode)
                    mapView.overlays.add(marker)
                    managedOverlays.add(marker)

                    if (!isInsetMode) {
                        val labelText = droneStatusLabelText(
                            atoFeet = labelAtoFeet,
                            aglFeet = labelAglFeet,
                            aglStale = labelAglStale,
                            rangeFeet = labelRangeFeet,
                            headingDeg = headingDeg
                        )
                        val nameDrawable = buildDroneNameLabelDrawable(context.resources, point.designator)
                        val statusDrawable = buildDroneStatusLabelDrawable(context.resources, labelText)
                        droneLabelSpecs.add(
                            DroneLabelDrawSpec(
                                designator = point.designator,
                                position = GeoPoint(renderLat, renderLng),
                                nameDrawable = nameDrawable,
                                statusDrawable = statusDrawable
                            )
                        )
                    }
                }
                if (droneLabelSpecs.isNotEmpty()) {
                    val labelOverlay = DroneLabelOverlay(droneLabelSpecs)
                    mapView.overlays.add(labelOverlay)
                    managedOverlays.add(labelOverlay)
                }

                val limitAglM = AGL_LIMIT_FT * FT_TO_METERS
                val limitRangeM = RANGE_LIMIT_FT * FT_TO_METERS
                val nearAglM = limitAglM * NEAR_LIMIT_RATIO
                val nearRangeM = limitRangeM * NEAR_LIMIT_RATIO

                val activeDesignators = dronePoints.map { it.designator }.toSet()
                complianceByDesignator.keys.toList().forEach { key ->
                    if (!activeDesignators.contains(key)) {
                        complianceByDesignator.remove(key)
                    }
                }
                dronePoints.forEach { point ->
                    val compDisplayState = viewModel.droneDisplayStateFor(point.designator)
                    val aglM    = compDisplayState?.aglFt?.let { it / METERS_TO_FEET }
                    val staleDem = compDisplayState?.aglStale ?: false
                    val rangeM = distanceFeetFromTakeoff(point)?.let { it * FT_TO_METERS }
                    val altitudeAlarmEnabled = point.droneSpec?.isLocalArchiveOnly != true
                    val nearAgl = altitudeAlarmEnabled && aglM != null && aglM >= nearAglM
                    val overAgl = altitudeAlarmEnabled && aglM != null && aglM >= limitAglM
                    val nearRange = rangeM != null && rangeM >= nearRangeM
                    val overRange = rangeM != null && rangeM >= limitRangeM
                    complianceByDesignator[point.designator] = DroneComplianceState(
                        aglM = aglM,
                        rangeFromTakeoffM = rangeM,
                        nearAgl = nearAgl,
                        nearRange = nearRange,
                        overAgl = overAgl,
                        overRange = overRange,
                        staleDem = staleDem
                    )
                }

                val focusedCompliance = focusedPath?.let { complianceByDesignator[it] }
                val anyOver = complianceByDesignator.values.any { it.overAgl || it.overRange }
                val anyNear = complianceByDesignator.values.any { it.nearAgl || it.nearRange }
                val severity = when {
                    focusedCompliance != null && (focusedCompliance.overAgl || focusedCompliance.overRange) -> AlertSeverity.Over
                    focusedCompliance != null && (focusedCompliance.nearAgl || focusedCompliance.nearRange) -> AlertSeverity.Near
                    anyOver -> AlertSeverity.Over
                    anyNear -> AlertSeverity.Near
                    else -> AlertSeverity.None
                }

                val renderStats =
                    "features=${artifactOverlayState.totalFeatures} points=${artifactOverlayState.points.size} " +
                        "lines=${artifactOverlayState.lines.size} polygons=${artifactOverlayState.polygons.size} " +
                        "ignoredTrackLike=${artifactOverlayState.ignoredTrackLikeFeatures} " +
                        "localTracks=${localTrackPointsByMappedId.size} drones=${dronePoints.size} " +
                        "localHeadOverrides=$localTailHeadOverrideCount"
                if (MAP_PANE_VERBOSE_LOGS && renderStats != lastRenderStats) {
                    lastRenderStats = renderStats
                    if (CTDebugEnabled(MAP_PANE_TAG)) CTDebug(MAP_PANE_TAG, "Artifact render stats: $renderStats")
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
                    if (MAP_PANE_VERBOSE_LOGS && alignStats != lastAlignmentStats) {
                        lastAlignmentStats = alignStats
                        if (CTDebugEnabled(MAP_PANE_TAG)) CTDebug(MAP_PANE_TAG, "Drone alignment: $alignStats")
                    }
                }

                val restoredStartupViewport = restoredViewport
                if (!isInsetMode && !restoredViewportStartupCheckComplete && restoredStartupViewport != null) {
                    val startupMyLocation = CaltopoMap.GetMyLocation()
                    val startupLocationValid =
                        startupMyLocation != null &&
                            startupMyLocation.latitude.isFinite() &&
                            startupMyLocation.longitude.isFinite()
                    val startupLocationAgeMs =
                        startupMyLocation?.let { locationAgeMs(it, uiNowWallMsec) } ?: Long.MAX_VALUE
                    val startupLocationFresh =
                        startupLocationValid && startupLocationAgeMs <= STARTUP_MY_LOCATION_FRESH_MS
                    val operationalContentPresent =
                        dronePoints.isNotEmpty() || artifactOverlayState.totalFeatures > 0
                    val viewportContainsMyLocation =
                        startupMyLocation?.let { mapView.boundingBox.containsLocation(it) } ?: false
                    val restoredViewportUsefulForMyLocation =
                        viewportContainsMyLocation && mapView.zoomLevelDouble >= STARTUP_MY_LOCATION_MIN_ZOOM
                    val timedOut =
                        uiNowWallMsec - restoredViewportStartupCheckStartedAtMs >= STARTUP_MY_LOCATION_WAIT_MS
                    var action: String? = null

                    when {
                        operationalContentPresent -> {
                            action = "kept-operational-content"
                            restoredViewportStartupCheckComplete = true
                        }

                        startupLocationFresh && restoredViewportUsefulForMyLocation -> {
                            action = "kept-my-location-visible"
                            restoredViewportStartupCheckComplete = true
                        }

                        operatorAdjustedViewport -> {
                            action = "kept-operator-adjusted-viewport"
                            restoredViewportStartupCheckComplete = true
                        }

                        startupLocationValid && !restoredViewportUsefulForMyLocation -> {
                            mapView.controller.setCenter(GeoPoint(startupMyLocation.latitude, startupMyLocation.longitude))
                            mapView.controller.setZoom(STARTUP_MY_LOCATION_MIN_ZOOM)
                            if (!isInsetMode) {
                                persistFullMapViewport(mapView)
                            }
                            initialViewportApplied = true
                            initialViewportArtifactCount = artifactOverlayState.totalFeatures
                            action = if (startupLocationFresh) {
                                "centered-on-my-location"
                            } else {
                                "centered-on-stale-my-location"
                            }
                            restoredViewportStartupCheckComplete = true
                        }

                        timedOut -> {
                            action = "kept-restored-no-fresh-location"
                            restoredViewportStartupCheckComplete = true
                        }

                        !restoredViewportStartupWaitLogged -> {
                            action = "waiting-for-fresh-location"
                            restoredViewportStartupWaitLogged = true
                        }
                    }

                    action?.let {
                        CTDebug(
                            MAP_PANE_TAG,
                            String.format(
                                Locale.US,
                                "Startup viewport check: restoredLat=%.6f restoredLng=%.6f restoredZoom=%.2f " +
                                    "myLocation=%s locationAgeMs=%d locationFresh=%s operationalContent=%s " +
                                    "viewportContainsMyLocation=%s restoredUseful=%s currentZoom=%.2f action=%s",
                                restoredStartupViewport.latitude,
                                restoredStartupViewport.longitude,
                                restoredStartupViewport.zoom,
                                startupMyLocation?.let { loc ->
                                    String.format(Locale.US, "%.6f,%.6f", loc.latitude, loc.longitude)
                                } ?: "none",
                                startupLocationAgeMs,
                                startupLocationFresh,
                                operationalContentPresent,
                                viewportContainsMyLocation,
                                restoredViewportUsefulForMyLocation,
                                mapView.zoomLevelDouble,
                                it
                            )
                        )
                    }
                }

                val shouldApplyInitialViewport =
                    !isInsetMode && !operatorAdjustedViewport && (
                        !initialViewportApplied ||
                        (
                            initialViewportArtifactCount == 0 &&
                                artifactOverlayState.totalFeatures > 0 &&
                                viewModel.mapViewportState() == null
                        )
                    )
                if (shouldApplyInitialViewport) {
                    val myLocation = CaltopoMap.GetMyLocation()
                    val artifactPoints = allArtifactGeoPoints(artifactOverlayState)
                    val viewportPoints = ArrayList<GeoPoint>(artifactPoints.size + 1).apply {
                        addAll(artifactPoints)
                        if (myLocation != null) add(GeoPoint(myLocation.latitude, myLocation.longitude))
                    }
                    when {
                        viewportPoints.size >= 2 -> {
                            val bounds = boundingBoxFromPoints(viewportPoints)
                            mapView.zoomToBoundingBox(bounds, true, 96)
                            if (!isInsetMode) {
                                persistFullMapViewport(mapView)
                            }
                            CTDebug(
                                MAP_PANE_TAG,
                                String.format(
                                    Locale.US,
                                    "Initial viewport: mode=bounds myLocation=%s artifactPts=%d center=%.6f,%.6f zoom=%.2f",
                                    myLocation != null,
                                    artifactPoints.size,
                                    mapView.mapCenter.latitude,
                                    mapView.mapCenter.longitude,
                                    mapView.zoomLevelDouble
                                )
                            )
                        }

                        myLocation != null -> {
                            mapView.controller.setCenter(GeoPoint(myLocation.latitude, myLocation.longitude))
                            mapView.controller.setZoom(15.0)
                            if (!isInsetMode) {
                                persistFullMapViewport(mapView)
                            }
                            CTDebug(
                                MAP_PANE_TAG,
                                String.format(
                                    Locale.US,
                                    "Initial viewport: mode=my-location lat=%.6f lng=%.6f ageMs=%d center=%.6f,%.6f zoom=%.2f",
                                    myLocation.latitude,
                                    myLocation.longitude,
                                    locationAgeMs(myLocation, uiNowWallMsec),
                                    mapView.mapCenter.latitude,
                                    mapView.mapCenter.longitude,
                                    mapView.zoomLevelDouble
                                )
                            )
                        }

                        focusPoint != null -> {
                            mapView.controller.setCenter(GeoPoint(focusPoint.lat, focusPoint.lng))
                            mapView.controller.setZoom(14.0)
                            if (!isInsetMode) {
                                persistFullMapViewport(mapView)
                            }
                            CTDebug(
                                MAP_PANE_TAG,
                                String.format(
                                    Locale.US,
                                    "Initial viewport: mode=focused-drone lat=%.6f lng=%.6f center=%.6f,%.6f zoom=%.2f",
                                    focusPoint.lat,
                                    focusPoint.lng,
                                    mapView.mapCenter.latitude,
                                    mapView.mapCenter.longitude,
                                    mapView.zoomLevelDouble
                                )
                            )
                        }
                    }
                    initialViewportApplied = true
                    initialViewportArtifactCount = artifactOverlayState.totalFeatures
                }

                if (!isInsetMode) proximityMapFocusTarget?.let { focusTarget ->
                    if (mapView.width > 0 && mapView.height > 0) {
                        val focusPoints = listOf(
                            GeoPoint(focusTarget.firstLat, focusTarget.firstLng),
                            GeoPoint(focusTarget.secondLat, focusTarget.secondLng)
                        )
                        val samePoint =
                            kotlin.math.abs(focusTarget.firstLat - focusTarget.secondLat) < 1e-7 &&
                                kotlin.math.abs(focusTarget.firstLng - focusTarget.secondLng) < 1e-7
                        if (samePoint) {
                            mapView.controller.setCenter(focusPoints.first())
                            mapView.controller.setZoom(MAP_DISPLAY_MAX_ZOOM)
                        } else {
                            mapView.zoomToBoundingBox(boundingBoxFromPoints(focusPoints), true, 96)
                        }
                        if (!isInsetMode) {
                            persistFullMapViewport(mapView)
                        }
                        initialViewportApplied = true
                        viewModel.clearProximityMapFocus(focusTarget.requestId)
                    }
                }

                val now = System.currentTimeMillis()
                if (!cacheStatsQueryInFlight && now >= nextCacheStatsLogAtMs) {
                    cacheStatsQueryInFlight = true
                    nextCacheStatsLogAtMs = now + 15_000L
                    uiScope.launch(Dispatchers.IO) {
                        val iconStats = iconCacheService.statsSnapshot()
                        val tileStats = tileCacheWriter.statsSnapshot()
                        val demStats = demElevationService.statsSnapshot()
                        val statsLine =
                            "CacheStats icon(hit=${iconStats.hits} miss=${iconStats.misses} stale=${iconStats.staleServed} " +
                                "evict=${iconStats.evictions} bytes=${iconStats.bytesUsed}) " +
                                "tile(hit=${tileStats.hits} miss=${tileStats.misses} stale=${tileStats.staleServed} " +
                                "evict=${tileStats.evictions} bytes=${tileStats.bytesUsed}) " +
                                "dem(hit=${demStats.hits} miss=${demStats.misses} stale=${demStats.staleServed} " +
                                "evict=${demStats.evictions} bytes=${demStats.bytesUsed})"
                        withContext(Dispatchers.Main.immediate) {
                            if (statsLine != lastCacheStats) {
                                lastCacheStats = statsLine
                                CTDebug(MAP_PANE_TAG, statsLine)
                            }
                            if (MapCacheDebug.isLudicrousEnabled()) {
                                val iconDeltaHits = (iconStats.hits - prevIconHits).coerceAtLeast(0L)
                                val iconDeltaMisses = (iconStats.misses - prevIconMisses).coerceAtLeast(0L)
                                val tileDeltaHits = (tileStats.hits - prevTileHits).coerceAtLeast(0L)
                                val tileDeltaMisses = (tileStats.misses - prevTileMisses).coerceAtLeast(0L)
                                val demDeltaHits = (demStats.hits - prevDemHits).coerceAtLeast(0L)
                                val demDeltaMisses = (demStats.misses - prevDemMisses).coerceAtLeast(0L)

                                fun formatHitRate(hits: Long, misses: Long): String {
                                    val total = hits + misses
                                    if (total <= 0L) return "--"
                                    val pct = (hits.toDouble() * 100.0) / total.toDouble()
                                    return "%.1f%%".format(Locale.US, pct)
                                }

                                MapCacheDebug.log(
                                    "summary/15s tile(hit=$tileDeltaHits miss=$tileDeltaMisses rate=${formatHitRate(tileDeltaHits, tileDeltaMisses)}) " +
                                        "icon(hit=$iconDeltaHits miss=$iconDeltaMisses rate=${formatHitRate(iconDeltaHits, iconDeltaMisses)}) " +
                                        "dem(hit=$demDeltaHits miss=$demDeltaMisses rate=${formatHitRate(demDeltaHits, demDeltaMisses)})"
                                )
                            }
                            prevIconHits = iconStats.hits
                            prevIconMisses = iconStats.misses
                            prevTileHits = tileStats.hits
                            prevTileMisses = tileStats.misses
                            prevDemHits = demStats.hits
                            prevDemMisses = demStats.misses
                            cacheStatsQueryInFlight = false
                        }
                    }
                }
                if (isInsetMode && !insetRestoredViewportApplied && restoredViewport != null) {
                    val insetWidth = mapView.width
                    val insetHeight = mapView.height
                    if (insetWidth > 0 && insetHeight > 0) {
                        mapView.controller.setCenter(GeoPoint(restoredViewport.latitude, restoredViewport.longitude))
                        mapView.controller.setZoom(
                            mapPaneInsetViewportZoom(
                                fullWidthPx = restoredViewport.widthPx,
                                fullHeightPx = restoredViewport.heightPx,
                                insetWidthPx = insetWidth,
                                insetHeightPx = insetHeight,
                                fullZoom = restoredViewport.zoom
                            )
                        )
                        insetRestoredViewportApplied = true
                    }
                }
                val focusDesignator = focusedPath
                val followFocusPoint = focusDesignator?.let { focus ->
                    dronePoints.firstOrNull { it.designator == focus }
                }
                val shouldFollowFocusedDrone = shouldFollowFocusedDrone(
                    presentationMode = presentationMode,
                    followFocusedDroneEnabled = followFocusedDroneEnabled,
                    hasFocusedDroneTelemetry = followFocusPoint != null,
                    operatorAdjustedViewport = operatorAdjustedViewport
                )
                if (!shouldFollowFocusedDrone) {
                    lastInsetFollowDesignator = null
                    lastInsetFollowPoint = null
                }
                if (shouldFollowFocusedDrone && followFocusPoint != null) {
                    val nowMs = System.currentTimeMillis()
                    val focusChanged = lastInsetFollowDesignator != focusDesignator
                    if (focusChanged || nowMs - lastInsetFollowAtMs >= INSET_FOLLOW_INTERVAL_MS) {
                        val target = GeoPoint(followFocusPoint.lat, followFocusPoint.lng)
                        val previous = lastInsetFollowPoint.takeUnless { focusChanged }
                        val movedEnough = previous == null ||
                            previous.distanceToAsDouble(target) >= INSET_FOLLOW_MIN_MOVE_METERS
                        if (movedEnough) {
                            mapView.controller.setCenter(target)
                            if (restoredViewport == null && mapView.zoomLevelDouble < 14.0) {
                                mapView.controller.setZoom(14.0)
                            }
                            if (!isInsetMode) {
                                persistFullMapViewport(mapView)
                            }
                            lastInsetFollowDesignator = focusDesignator
                            lastInsetFollowPoint = target
                        }
                        lastInsetFollowAtMs = nowMs
                    }
                }
                mapView.invalidate()
            }
        )

        mapBackgroundWorkStatus?.let { status ->
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .fillMaxWidth(0.58f)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val countText = if (status.total > 0) {
                    " ${status.completed}/${status.total}"
                } else {
                    ""
                }
                Text(
                    text = status.label + countText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val progress = status.progress
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        if (!isInsetMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                IconButton(
                    onClick = { settingsMenuExpanded = true },
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Map settings"
                    )
                }
                DropdownMenu(
                expanded = settingsMenuExpanded,
                onDismissRequest = {
                    settingsMenuExpanded = false
                    mapManagementMenuExpanded = false
                    baseLayerMenuExpanded = false
                    badTilesMenuExpanded = false
                }
            ) {
                DropdownMenuItem(
                    text = { Text("Layer: ${baseLayer.label}") },
                    onClick = {
                        settingsMenuExpanded = false
                        baseLayerMenuExpanded = true
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (predictiveHeadEnabled) "Predictive Head: On" else "Predictive Head: Off") },
                    onClick = {
                        predictiveHeadEnabled = !predictiveHeadEnabled
                        CaltopoClient.SetPredictiveHeadEnabled(predictiveHeadEnabled)
                        settingsMenuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Download Map...") },
                    onClick = {
                        offlinePrepIncludeContours = contourOverlayEnabled
                        settingsMenuExpanded = false
                        showOfflinePrepDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Map Folders...") },
                    onClick = {
                        CTInfo(
                            MAP_PANE_TAG,
                            "Map Folders opened: " + mapFolderUiDebugSummary(
                                folders = buildMapFolderUiStates(artifactStoreById),
                                hiddenFolderIds = hiddenFolderIds,
                                hiddenItemIds = hiddenItemIds
                            )
                        )
                        settingsMenuExpanded = false
                        showMapFoldersDialog = true
                    },
                    enabled = buildMapFolderUiStates(artifactStoreById).isNotEmpty()
                )
                DropdownMenuItem(
                    text = { Text("Map Management...") },
                    onClick = {
                        settingsMenuExpanded = false
                        mapManagementMenuExpanded = true
                    }
                )
            }
            DropdownMenu(
                expanded = mapManagementMenuExpanded,
                onDismissRequest = { mapManagementMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(if (followFocusedDroneEnabled) "Follow Focused Drone: On" else "Follow Focused Drone: Off") },
                    onClick = {
                        val enabled = !followFocusedDroneEnabled
                        if (enabled) {
                            operatorAdjustedViewport = false
                        }
                        viewModel.setFollowFocusedDroneEnabled(enabled)
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (mapReloadInFlight) "Reload Map..." else "Reload Map") },
                    onClick = {
                        mapManagementMenuExpanded = false
                        if (mapReloadInFlight) {
                            return@DropdownMenuItem
                        }
                        mapReloadInFlight = true
                        CaltopoClient.ShowToast("Reloading map artifacts...")
                        CaltopoMap.ReloadMapArtifactsNow(
                            Runnable {
                                uiScope.launch(Dispatchers.Main.immediate) {
                                    startArtifactHydration("manual-reload") {
                                        mapReloadInFlight = false
                                        CaltopoClient.ShowToast("Map reloaded.")
                                    }
                                }
                            }
                        )
                        uiScope.launch {
                            delay(15_000L)
                            if (mapReloadInFlight) {
                                mapReloadInFlight = false
                            }
                        }
                    },
                    enabled = mapName != null && !mapReloadInFlight
                )
                DropdownMenuItem(
                    text = { Text("Bad Tiles...") },
                    onClick = {
                        mapManagementMenuExpanded = false
                        badTilesMenuExpanded = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Max Cache Size: ${MapCacheSettings.formatDecimalGb(MapCacheSettings.maxCacheBytes(context))}") },
                    onClick = {
                        mapManagementMenuExpanded = false
                        mapCacheSizeInput = String.format(
                            Locale.US,
                            "%.1f",
                            MapCacheSettings.maxCacheBytes(context).toDouble() / 1_000_000_000.0
                        )
                        showMapCacheSizeDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Maximum Tile Age: ${MapCacheSettings.formatTileAge(MapCacheSettings.maxTileAgeDays(context))}") },
                    onClick = {
                        mapManagementMenuExpanded = false
                        mapTileAgeDaysInput = MapCacheSettings.maxTileAgeDays(context).toString()
                        showMapTileAgeDialog = true
                    }
                )
            }
            DropdownMenu(
                expanded = badTilesMenuExpanded,
                onDismissRequest = { badTilesMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("How To") },
                    onClick = {
                        badTilesMenuExpanded = false
                        showBadTilesHowToDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (autoRemoveBadTiles) "Auto Remove Bad Tiles: On" else "Auto Remove Bad Tiles: Off") },
                    onClick = {
                        autoRemoveBadTiles = !autoRemoveBadTiles
                        badTilesMenuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Clear Bad Tile Flags (${BadTilePolicy.blockedHashCount(context)})") },
                    onClick = {
                        BadTilePolicy.clearBlockedHashes(context)
                        CaltopoClient.ShowToast("Bad tile flags cleared.")
                        badTilesMenuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Export Bad Tile Hashes") },
                    onClick = {
                        val exportedTo = exportBadTileHashes(context)
                        if (exportedTo != null) {
                            CaltopoClient.ShowToast("Exported bad tile hashes to $exportedTo")
                        } else {
                            CaltopoClient.ShowToast("Bad tile hash export failed.")
                        }
                        badTilesMenuExpanded = false
                    }
                )
            }
            DropdownMenu(
                expanded = baseLayerMenuExpanded,
                onDismissRequest = { baseLayerMenuExpanded = false }
            ) {
                BaseLayerOption.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            val selected = if (option == baseLayer) " \u2713" else ""
                            Text("${option.label}$selected")
                        },
                        onClick = {
                            viewModel.setBaseLayer(option)
                            baseLayerMenuExpanded = false
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (contourOverlayEnabled) "Contours: On" else "Contours: Off") },
                    onClick = {
                        contourOverlayEnabled = !contourOverlayEnabled
                        MapCacheSettings.setContourOverlayEnabled(context, contourOverlayEnabled)
                        baseLayerMenuExpanded = false
                    }
                )
            }
        }
        }

        if (showMutualAidPackageDialog) {
            val parsedExpiry = parseMutualAidPackageExpiry()
            AlertDialog(
                onDismissRequest = { showMutualAidPackageDialog = false },
                title = { Text("Export MA Package") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            "Export a mutual-aid package from the current map using already-cached imagery and DEM data only.",
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Source org: ${CaltopoClient.GetMutualAidSourceLabel().ifBlank { "Not configured in ct_mutual_aid_credentials" }}")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = maPackageDisplayName,
                            onValueChange = { maPackageDisplayName = it },
                            label = { Text("Display name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = maPackageIncident,
                            onValueChange = { maPackageIncident = it },
                            label = { Text("Incident") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = maPackageOpPeriod,
                            onValueChange = { maPackageOpPeriod = it },
                            label = { Text("Op period") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = maPackageMapId,
                            onValueChange = { maPackageMapId = it },
                            label = { Text("Map ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = maPackageMapTitle,
                            onValueChange = { maPackageMapTitle = it },
                            label = { Text("Map title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = maPackageUseMapPaneExtents,
                                onCheckedChange = { maPackageUseMapPaneExtents = it }
                            )
                            Text("Use MapPane extents")
                        }
                        Text(
                            if (maPackageUseMapPaneExtents) {
                                "Export uses the current MapPane viewport instead of the offline-prep boundary selection."
                            } else {
                                "Export uses the offline-prep boundary selection when one is active; otherwise it uses the current MapPane viewport."
                            },
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = maPackageExpiryDateText,
                                onValueChange = { maPackageExpiryDateText = it },
                                label = { Text("Expiry date") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = maPackageExpiryTimeText,
                                onValueChange = { maPackageExpiryTimeText = it },
                                label = { Text("Expiry time") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (parsedExpiry <= System.currentTimeMillis()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Expiry must be a future local date/time in yyyy-MM-dd and HH:mm format.",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = CaltopoClient.GetMutualAidSourceLabel().isNotBlank() &&
                            maPackageIncident.isNotBlank() &&
                            maPackageOpPeriod.isNotBlank() &&
                            maPackageMapId.isNotBlank() &&
                            parsedExpiry > System.currentTimeMillis() &&
                            !preparingMutualAidShare,
                        onClick = {
                            showMutualAidPackageDialog = false
                            startMutualAidShare()
                        }
                    ) { Text("Start Sharing") }
                },
                dismissButton = {
                    TextButton(onClick = { showMutualAidPackageDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (preparingMutualAidShare) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Preparing MA Package") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("Packaging cached map and DEM data for transfer…")
                    }
                },
                confirmButton = {}
            )
        }

        activeShareSession?.let { session ->
            MutualAidPackageShareDialog(
                session = session,
                onDone = { MutualAidPackageTransferManager.stopShareSession() }
            )
        }

        if (showOfflinePrepDialog) {
            val selectedBoundary =
                if (offlinePrepAreaMode == OfflinePrepAreaMode.MapBoundary) {
                    offlineBoundaryOptions.firstOrNull { it.id == offlinePrepBoundaryId }?.boundary
                } else {
                    null
                }
            val effectiveBoundary = selectedBoundary
            val bounds = effectiveBoundary?.bounds ?: mapBounds
            AlertDialog(
                onDismissRequest = {
                    if (!offlinePrepInFlight) showOfflinePrepDialog = false
                },
                title = { Text("Download Map") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (offlinePrepInFlight || offlinePrepProgress.phase != "Idle") {
                            val pct = if (offlinePrepProgress.total > 0) {
                                (offlinePrepProgress.completed.toDouble() * 100.0 / offlinePrepProgress.total.toDouble())
                                    .coerceIn(0.0, 100.0)
                            } else if (offlinePrepProgress.phase == "Complete" || offlinePrepProgress.phase == "Complete with failures") {
                                100.0
                            } else {
                                0.0
                            }
                            val progressFraction = (pct / 100.0).toFloat()
                            val etaText = offlinePrepProgress.etaSeconds?.let { formatDurationShort(it) } ?: "--:--"
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "${String.format(Locale.US, "%.0f", pct)}% complete",
                                    fontSize = 18.sp
                                )
                                if (offlinePrepInFlight && offlinePrepProgress.total <= 0) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                } else {
                                    LinearProgressIndicator(
                                        progress = { progressFraction },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                Text(
                                    "Progress: ${offlinePrepProgress.phase} ${offlinePrepProgress.completed}/${offlinePrepProgress.total} " +
                                        "(${String.format(Locale.US, "%.2f", pct)}%) " +
                                        "rate=${String.format(Locale.US, "%.1f", offlinePrepProgress.opsPerSec)}/s " +
                                        "ETA=$etaText",
                                    fontSize = 12.sp
                                )
                                Text(
                                    "Tiles: ${offlinePrepProgress.tileCompleted}/${offlinePrepProgress.tileTotal} " +
                                        "(hit=${offlinePrepProgress.hits} fetched=${offlinePrepProgress.fetched} failed=${offlinePrepProgress.failed})",
                                    fontSize = 12.sp
                                )
                                if (offlinePrepProgress.demTotal > 0) {
                                    Text(
                                        "DEM: ${offlinePrepProgress.demCompleted}/${offlinePrepProgress.demTotal} " +
                                            "(hit=${offlinePrepProgress.demHits} fetched=${offlinePrepProgress.demFetched} failed=${offlinePrepProgress.demFailed})",
                                        fontSize = 12.sp
                                    )
                                }
                                if (offlinePrepProgress.totalFailed > 0) {
                                    Text(
                                        "Total failures: ${offlinePrepProgress.totalFailed}",
                                        fontSize = 12.sp
                                    )
                                }
                                if (!offlinePrepInFlight && offlinePrepProgress.phase == "Complete") {
                                    Text(
                                        "Closing automatically...",
                                        fontSize = 11.sp
                                    )
                                }
                                if (!offlinePrepInFlight && offlinePrepProgress.phase == "Complete with failures") {
                                    Text(
                                        "Download finished with failures. Review the counts above before retrying.",
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                        val offlinePrepOptionsScrollState = rememberScrollState()
                        var offlinePrepOptionsViewportHeightPx by remember { mutableIntStateOf(0) }
                        Box(
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .fillMaxWidth()
                                .clipToBounds()
                                .onSizeChanged { offlinePrepOptionsViewportHeightPx = it.height }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(offlinePrepOptionsScrollState)
                                    .padding(end = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                        Text("Area")
                        OfflinePrepAreaMode.entries.forEach { area ->
                            val enabled = area != OfflinePrepAreaMode.MapBoundary || offlineBoundaryOptions.isNotEmpty()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = enabled && !offlinePrepInFlight) {
                                        offlinePrepAreaMode = area
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = offlinePrepAreaMode == area,
                                    onCheckedChange = {
                                        if (enabled && !offlinePrepInFlight && it == true) {
                                            offlinePrepAreaMode = area
                                        }
                                    },
                                    enabled = enabled && !offlinePrepInFlight
                                )
                                val label = if (area == OfflinePrepAreaMode.MapBoundary && offlineBoundaryOptions.isEmpty()) {
                                    "${area.label} (no polygons/lines in map)"
                                } else {
                                    area.label
                                }
                                Text(label)
                            }
                        }
                        if (offlinePrepAreaMode == OfflinePrepAreaMode.MapBoundary && offlineBoundaryOptions.isNotEmpty()) {
                            Text("Boundary shape", fontSize = 12.sp)
                            offlineBoundaryOptions.forEach { option ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !offlinePrepInFlight) {
                                            offlinePrepBoundaryId = option.id
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = offlinePrepBoundaryId == option.id,
                                        onCheckedChange = {
                                            if (!offlinePrepInFlight && it == true) {
                                                offlinePrepBoundaryId = option.id
                                            }
                                        },
                                        enabled = !offlinePrepInFlight
                                    )
                                    Text(option.label, fontSize = 12.sp)
                                }
                            }
                        }
                        OFFLINE_PREP_PRESETS.forEach { preset ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !offlinePrepInFlight) { offlinePrepPreset = preset },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = offlinePrepPreset == preset,
                                    onCheckedChange = {
                                        if (!offlinePrepInFlight && it == true) offlinePrepPreset = preset
                                    },
                                    enabled = !offlinePrepInFlight
                                )
                                Column {
                                    Text(preset.label)
                                    Text(
                                        demSamplingSummary(preset.demStepMeters),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                        Text(
                            "DEM spacing above reflects runtime query granularity. Downloading fetches whole USGS 1° GeoTIFF tiles.",
                            fontSize = 11.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = offlinePrepIncludeDem,
                                onCheckedChange = { if (!offlinePrepInFlight) offlinePrepIncludeDem = it },
                                enabled = !offlinePrepInFlight
                            )
                            Text("Include DEM tiles (USGS 1° GeoTIFF, ~25–54 MB/tile)")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = offlinePrepIncludeContours,
                                onCheckedChange = { if (!offlinePrepInFlight) offlinePrepIncludeContours = it },
                                enabled = !offlinePrepInFlight
                            )
                            Text("Include contour tiles")
                        }
                        if (maximizeThroughputBlockedForOsm) {
                            Text(
                                "OpenStreetMap offline prep uses a conservative single-request mode with the app's OSM user agent.",
                                fontSize = 11.sp
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = offlinePrepMaxThroughput,
                                    onCheckedChange = { if (!offlinePrepInFlight) offlinePrepMaxThroughput = it },
                                    enabled = !offlinePrepInFlight
                                )
                                Text("Maximize throughput")
                            }
                        }
                        Text(
                            if (offlinePrepEstimateRunning || !offlinePrepEstimate.ready) {
                                "Estimate: calculating..."
                            } else {
                                "Estimate: " +
                                    "tiles=${offlinePrepEstimate.tileEstimate}" +
                                    (if (offlinePrepIncludeContours) " incl. contours" else "") +
                                    " (~${"%.1f".format(Locale.US, offlinePrepEstimate.estimatedTileCacheMb)} MB), " +
                                    "dem=${offlinePrepEstimate.demEstimate} tile(s) (~${"%.0f".format(Locale.US, offlinePrepEstimate.estimatedDemCacheMb)} MB)"
                            },
                            fontSize = 12.sp
                        )
                        Text(
                            "Note: GeoTIFF tiles provide instant local DEM lookups with no network queries for covered areas.",
                            fontSize = 11.sp
                        )
                        if (offlinePrepEstimate.ready) {
                            val cacheCapMb = offlinePrepTileCacheCapBytes.toDouble() / (1024.0 * 1024.0)
                            if (offlinePrepEstimate.estimatedTileCacheMb > cacheCapMb) {
                                Text(
                                    "Warning: estimate exceeds tile cache cap (~${"%.0f".format(Locale.US, cacheCapMb)} MB). Older tiles may be evicted.",
                                    fontSize = 11.sp
                                )
                            }
                            val availableMb = offlinePrepAvailableBytes?.toDouble()?.div(1024.0 * 1024.0)
                            if (availableMb != null) {
                                Text(
                                    "Available storage: ~${"%.0f".format(Locale.US, availableMb)} MB",
                                    fontSize = 11.sp
                                )
                                val totalEstimateMb = offlinePrepEstimate.estimatedTileCacheMb + offlinePrepEstimate.estimatedDemCacheMb
                                if (totalEstimateMb > (availableMb * 0.95)) {
                                    Text(
                                        "Warning: estimated download may exceed available storage.",
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                        Text(
                            when {
                                !offlinePrepCacheStatus.checked -> "Offline package readiness: checking cached tiles..."
                                offlinePrepCacheStatus.readyForPackage -> "Offline package readiness: selected map data is fully cached."
                                offlinePrepReadyByCompletion -> buildString {
                                    append("Offline package readiness: enabled from latest Start run")
                                    if (offlinePrepCacheStatus.tileMissing > 0 || offlinePrepCacheStatus.demMissing > 0) {
                                        append(" (still missing ")
                                        if (offlinePrepCacheStatus.tileMissing > 0) {
                                            append("${offlinePrepCacheStatus.tileMissing} tile")
                                            if (offlinePrepCacheStatus.tileMissing != 1) append("s")
                                        }
                                        if (offlinePrepCacheStatus.tileMissing > 0 && offlinePrepCacheStatus.demMissing > 0) {
                                            append(", ")
                                        }
                                        if (offlinePrepCacheStatus.demMissing > 0) {
                                            append("${offlinePrepCacheStatus.demMissing} DEM")
                                            if (offlinePrepCacheStatus.demMissing != 1) append("s")
                                        }
                                        append(")")
                                    }
                                }
                                else -> buildString {
                                    append("Offline package readiness: run Start first")
                                    if (offlinePrepCacheStatus.tileMissing > 0 || offlinePrepCacheStatus.demMissing > 0) {
                                        append(" (missing ")
                                        if (offlinePrepCacheStatus.tileMissing > 0) {
                                            append("${offlinePrepCacheStatus.tileMissing} tile")
                                            if (offlinePrepCacheStatus.tileMissing != 1) append("s")
                                        }
                                        if (offlinePrepCacheStatus.tileMissing > 0 && offlinePrepCacheStatus.demMissing > 0) {
                                            append(", ")
                                        }
                                        if (offlinePrepCacheStatus.demMissing > 0) {
                                            append("${offlinePrepCacheStatus.demMissing} DEM")
                                            if (offlinePrepCacheStatus.demMissing != 1) append("s")
                                        }
                                        append(")")
                                    }
                                }
                            },
                            fontSize = 11.sp,
                            color = if (offlinePrepCacheStatus.checked && !offlinePackageReady) {
                                MaterialTheme.colorScheme.error
                            } else if (offlinePrepReadyByCompletion && !offlinePrepCacheStatus.readyForPackage) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                            }
                            val maxScroll = offlinePrepOptionsScrollState.maxValue
                            if (maxScroll > 0 && offlinePrepOptionsViewportHeightPx > 0) {
                                val density = LocalDensity.current
                                val viewportDp = with(density) { offlinePrepOptionsViewportHeightPx.toDp() }
                                val surfaceColor = MaterialTheme.colorScheme.surface
                                if (offlinePrepOptionsScrollState.value > 0) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .fillMaxWidth()
                                            .height(18.dp)
                                            .background(
                                                Brush.verticalGradient(
                                                    colors = listOf(
                                                        surfaceColor,
                                                        Color.Transparent
                                                    )
                                                )
                                            )
                                    )
                                }
                                if (offlinePrepOptionsScrollState.value < maxScroll) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .fillMaxWidth()
                                            .height(22.dp)
                                            .background(
                                                Brush.verticalGradient(
                                                    colors = listOf(
                                                        Color.Transparent,
                                                        surfaceColor
                                                    )
                                                )
                                            )
                                    )
                                }
                                val estimatedContentHeightPx = offlinePrepOptionsViewportHeightPx + maxScroll
                                val thumbHeightPx = (
                                    offlinePrepOptionsViewportHeightPx.toFloat() *
                                        offlinePrepOptionsViewportHeightPx.toFloat() /
                                        estimatedContentHeightPx.toFloat()
                                    ).toInt().coerceAtLeast(with(density) { 36.dp.roundToPx() })
                                    .coerceAtMost(offlinePrepOptionsViewportHeightPx)
                                val thumbOffsetPx = if (maxScroll <= 0) {
                                    0
                                } else {
                                    ((offlinePrepOptionsViewportHeightPx - thumbHeightPx).toFloat() *
                                        offlinePrepOptionsScrollState.value.toFloat() /
                                        maxScroll.toFloat()).toInt()
                                }
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .width(6.dp)
                                        .height(viewportDp)
                                        .background(
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                                            CircleShape
                                        )
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(y = with(density) { thumbOffsetPx.toDp() })
                                        .width(6.dp)
                                        .height(with(density) { thumbHeightPx.toDp() })
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.82f),
                                            CircleShape
                                        )
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                val currentBounds = mapBounds
                                val boundary =
                                    if (offlinePrepAreaMode == OfflinePrepAreaMode.MapBoundary) {
                                        offlineBoundaryOptions.firstOrNull { it.id == offlinePrepBoundaryId }?.boundary
                                    } else {
                                        null
                                    }
                                val prepBounds = boundary?.bounds ?: currentBounds
                                if (prepBounds == null) {
                                    CaltopoClient.ShowToast("Offline prep needs visible map bounds.")
                                    return@TextButton
                                }
                                if (offlinePrepEstimate.ready) {
                                    val estimateMb = offlinePrepEstimate.estimatedTileCacheMb + offlinePrepEstimate.estimatedDemCacheMb
                                    val estimateBytes = (estimateMb * 1024.0 * 1024.0).toLong()
                                    val available = offlinePrepAvailableBytes
                                    if (available != null && estimateBytes > (available * 95L / 100L)) {
                                        CaltopoClient.ShowToast("Estimated download exceeds available storage. Pick a smaller area/zoom.")
                                        return@TextButton
                                    }
                                }
                                startOfflinePrep(prepBounds, boundary)
                            },
                            enabled = !offlinePrepInFlight && (mapBounds != null || selectedBoundary != null)
                        ) { Text("Start") }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            if (offlinePrepInFlight) {
                                if (offlinePrepCancelRequested) return@TextButton
                                offlinePrepCancelRequested = true
                                offlinePrepProgress = offlinePrepProgress.copy(phase = "Cancelling")
                                offlinePrepActiveCalls.forEach { it.cancel() }
                                offlinePrepJob?.cancel()
                            } else {
                                offlinePrepAutoCloseJob?.cancel()
                                offlinePrepCancelRequested = false
                                showOfflinePrepDialog = false
                            }
                        },
                        enabled = !offlinePrepCancelRequested || !offlinePrepInFlight
                    ) {
                        Text(
                            when {
                                offlinePrepInFlight && offlinePrepCancelRequested -> "Cancelling..."
                                offlinePrepInFlight -> "Cancel"
                                else -> "Close"
                            }
                        )
                    }
                }
            )
        }

        if (isInsetMode) {
            Text(
                text = if (contourOverlayEnabled) "© OSM · USGS" else "© OSM",
                fontSize = 6.sp,
                lineHeight = 6.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 3.dp, bottom = 3.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.56f))
                    .padding(horizontal = 2.dp, vertical = 1.dp)
                    .clickable {
                        uriHandler.openUri("https://www.openstreetmap.org/copyright")
                    }
            )
        } else {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "© OpenStreetMap contributors",
                    fontSize = 8.sp,
                    lineHeight = 8.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://www.openstreetmap.org/copyright")
                    }
                )
                Text(
                    text = "DEM: USGS National Geospatial Program",
                    fontSize = 8.sp,
                    lineHeight = 8.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (contourOverlayEnabled) {
                    Text(
                        text = "Contours: USGS The National Map",
                        fontSize = 8.sp,
                        lineHeight = 8.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private const val CALTOPO_ASSIGNMENTS_FOLDER_ID = "__caltopo_assignments__"
private const val CALTOPO_ASSIGNMENTS_FOLDER_TITLE = "Assignments"
private const val CALTOPO_RANGE_RINGS_FOLDER_ID = "__caltopo_range_rings__"
private const val CALTOPO_RANGE_RINGS_FOLDER_TITLE = "Range Rings"
private const val CALTOPO_MARKERS_FOLDER_ID = "__caltopo_markers__"
private const val CALTOPO_MARKERS_FOLDER_TITLE = "Markers"
private const val CALTOPO_LINES_POLYGONS_FOLDER_ID = "__caltopo_lines_polygons__"
private const val CALTOPO_LINES_POLYGONS_FOLDER_TITLE = "Lines & Polygons"
private const val CALTOPO_APP_TRACKS_FOLDER_ID = "__caltopo_app_tracks__"
private const val CALTOPO_APP_TRACKS_FOLDER_TITLE = "App Tracks"
private const val CALTOPO_OTHER_MAP_ITEMS_FOLDER_ID = "__caltopo_other_map_items__"
private const val CALTOPO_OTHER_MAP_ITEMS_FOLDER_TITLE = "Other Map Items"

private data class SyntheticArtifactFolder(
    val id: String,
    val title: String,
    val initiallyVisible: Boolean
)

private val syntheticArtifactFoldersById = listOf(
    SyntheticArtifactFolder(CALTOPO_ASSIGNMENTS_FOLDER_ID, CALTOPO_ASSIGNMENTS_FOLDER_TITLE, true),
    SyntheticArtifactFolder(CALTOPO_RANGE_RINGS_FOLDER_ID, CALTOPO_RANGE_RINGS_FOLDER_TITLE, false),
    SyntheticArtifactFolder(CALTOPO_MARKERS_FOLDER_ID, CALTOPO_MARKERS_FOLDER_TITLE, false),
    SyntheticArtifactFolder(CALTOPO_LINES_POLYGONS_FOLDER_ID, CALTOPO_LINES_POLYGONS_FOLDER_TITLE, false),
    SyntheticArtifactFolder(CALTOPO_APP_TRACKS_FOLDER_ID, CALTOPO_APP_TRACKS_FOLDER_TITLE, false),
    SyntheticArtifactFolder(CALTOPO_OTHER_MAP_ITEMS_FOLDER_ID, CALTOPO_OTHER_MAP_ITEMS_FOLDER_TITLE, true)
).associateBy { it.id }

internal fun buildMapFolderUiStates(features: Map<String, JSONObject>): List<MapFolderUiState> {
    val folderItems = mutableMapOf<String, MutableList<MapItemUiState>>()
    val folderMeta = mutableMapOf<String, Pair<String, Boolean>>()  // id -> (title, visible)
    for (feature in features.values) {
        val props = feature.optJSONObject("properties") ?: continue
        val id = feature.optString("id").takeIf { it.isNotBlank() } ?: continue
        val className = props.optString("class")
        if (className == "Folder") {
            val title = artifactDisplayTitle(props, id, className)
            folderMeta[id] = Pair(title, props.optBoolean("visible", true))
        } else {
            val folderId = effectiveArtifactFolderId(props, className).takeIf { it.isNotBlank() } ?: continue
            val title = artifactDisplayTitle(props, id, className)
            folderItems.getOrPut(folderId) { mutableListOf() }.add(MapItemUiState(id, title))
        }
    }
    for (folder in syntheticArtifactFoldersById.values) {
        if (folderItems.containsKey(folder.id)) {
            folderMeta.putIfAbsent(folder.id, Pair(folder.title, folder.initiallyVisible))
        }
    }
    for (folderId in folderItems.keys) {
        folderMeta.putIfAbsent(folderId, Pair(orphanFolderTitle(folderId), true))
    }
    return folderMeta.entries
        .sortedBy { it.value.first }
        .map { (folderId, meta) ->
            MapFolderUiState(
                folderId = folderId,
                title = meta.first,
                initiallyVisible = meta.second,
                items = (folderItems[folderId] ?: emptyList()).sortedBy { it.title }
            )
        }
}

internal fun mapFolderUiDebugSummary(
    folders: List<MapFolderUiState>,
    hiddenFolderIds: Set<String>,
    hiddenItemIds: Set<String>
): String {
    val folderSummaries = folders.map { folder ->
        val hiddenItemCount = folder.items.count { it.featureId in hiddenItemIds }
        val sampleItems = folder.items.take(5).joinToString(separator = ",") { item ->
            "${item.title}(${item.featureId})"
        }
        "${folder.title} id=${folder.folderId} hidden=${folder.folderId in hiddenFolderIds} " +
            "defaultVisible=${folder.initiallyVisible} items=${folder.items.size} " +
            "hiddenItems=$hiddenItemCount sample=[$sampleItems]"
    }
    return (listOf("folders=${folders.size}") + folderSummaries).joinToString(separator = " | ")
}

internal fun buildArtifactOverlayState(
    features: Collection<JSONObject>,
    hiddenFolderIds: Set<String> = emptySet(),
    hiddenItemIds: Set<String> = emptySet(),
    pilotArchiveTrackColorForCallsign: (String) -> String? = { null }
): ArtifactOverlayState {
    val featuresById = features.mapNotNull { feature ->
        feature.optString("id").takeIf { it.isNotBlank() }?.let { it to feature }
    }.toMap()
    val points = mutableListOf<ArtifactPointSpec>()
    val lines = mutableListOf<ArtifactLineSpec>()
    val polygons = mutableListOf<ArtifactPolygonSpec>()
    var ignoredTrackLikeFeatures = 0
    val representedFolderIds = features.mapNotNull { feature ->
        val props = feature.optJSONObject("properties") ?: return@mapNotNull null
        val className = props.optString("class")
        if (className == "Folder") {
            feature.optString("id").takeIf { it.isNotBlank() }
        } else {
            effectiveArtifactFolderId(props, className).takeIf { it.isNotBlank() }
        }
    }.toSet()

    for (feature in features) {
        val geometry = feature.optJSONObject("geometry") ?: continue
        val properties = feature.optJSONObject("properties")
        val className = properties?.optString("class").orEmpty()
        if (className == "Folder") continue

        val featureId = feature.optString("id")
        val folderId = effectiveArtifactFolderId(properties, className)
        if (folderId.isBlank() || folderId !in representedFolderIds) continue
        if (folderId.isNotBlank() && folderId in hiddenFolderIds) continue
        if (featureId.isNotBlank() && featureId in hiddenItemIds) continue
        if (isMediaObjectWithHiddenParent(properties, featuresById, hiddenFolderIds, hiddenItemIds)) continue
        val featureTitle = artifactDisplayTitle(properties, featureId, className)
        val markerSymbol = properties?.optString("marker-symbol", "point").orEmpty().ifBlank { "point" }
        val markerColor = properties?.optString("marker-color")
        val trackLikeFeature = isTrackLikeFeature(properties, className)
        val defaultStrokeHex = markerColor?.takeIf { it.isNotBlank() } ?: "#FF5A1F"

        val pilotArchiveColor = archivedDroneTrackPilotCallsign(properties)
            ?.let(pilotArchiveTrackColorForCallsign)
        val strokeColor = colorFromHex(
            pilotArchiveColor ?: properties?.optString("stroke", defaultStrokeHex),
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

internal fun buildArtifactHydrationResult(
    snapshot: Collection<JSONObject>,
    hiddenFolderIds: Set<String> = emptySet(),
    hiddenItemIds: Set<String> = emptySet(),
    progressInterval: Int = 100,
    pilotArchiveTrackColorForCallsign: (String) -> String? = { null },
    onProgress: (ArtifactHydrationProgress) -> Unit = {}
): ArtifactHydrationResult {
    val featuresById = LinkedHashMap<String, JSONObject>()
    val folderDefaultsById = LinkedHashMap<String, ArtifactFolderDefault>()
    val serverHiddenFolderIds = LinkedHashSet<String>()
    val total = snapshot.size
    val checkpoint = progressInterval.coerceAtLeast(1)
    snapshot.forEachIndexed { index, feature ->
        val featureId = feature.optString("id")
        if (featureId.isNotBlank()) {
            featuresById[featureId] = feature
            val props = feature.optJSONObject("properties")
            if (props?.optString("class") == "Folder") {
                if (!props.optBoolean("visible", true)) {
                    serverHiddenFolderIds.add(featureId)
                }
                folderDefaultsById.putIfAbsent(
                    featureId,
                    ArtifactFolderDefault(featureId, props.optBoolean("visible", true))
                )
            } else {
                syntheticArtifactFolderDefault(props)?.let { folderDefault ->
                    folderDefaultsById.putIfAbsent(folderDefault.folderId, folderDefault)
                }
            }
        }
        val completed = index + 1
        if (completed == total || completed % checkpoint == 0) {
            onProgress(ArtifactHydrationProgress(completed = completed, total = total))
        }
    }
    if (total == 0) {
        onProgress(ArtifactHydrationProgress(completed = 0, total = 0))
    }
    val folderDefaults = folderDefaultsById.values.toList()
    return ArtifactHydrationResult(
        featuresById = featuresById,
        overlayState = buildArtifactOverlayState(
            featuresById.values,
            hiddenFolderIds + serverHiddenFolderIds,
            hiddenItemIds,
            pilotArchiveTrackColorForCallsign
        ),
        folderDefaults = folderDefaults,
        serverHiddenFolderIds = serverHiddenFolderIds
    )
}

internal fun movedDroneFolderMarkerIds(
    previousFeatures: Map<String, JSONObject>,
    incomingFeatures: Map<String, JSONObject>,
    expectedDroneFolderId: String?
): Set<String> {
    val expectedFolder = expectedDroneFolderId?.takeIf { it.isNotBlank() } ?: return emptySet()
    return incomingFeatures.mapNotNull { (featureId, incomingFeature) ->
        if (featureId.isBlank()) return@mapNotNull null
        val previousFeature = previousFeatures[featureId] ?: return@mapNotNull null
        val previousProperties = previousFeature.optJSONObject("properties")
        val incomingProperties = incomingFeature.optJSONObject("properties")
        if (!isDroneTrackMarker(previousProperties) || !isDroneTrackMarker(incomingProperties)) {
            return@mapNotNull null
        }
        val previousFolder = effectiveArtifactFolderId(
            previousProperties,
            previousProperties?.optString("class").orEmpty()
        )
        val incomingFolder = effectiveArtifactFolderId(
            incomingProperties,
            incomingProperties?.optString("class").orEmpty()
        )
        if (previousFolder == expectedFolder && incomingFolder.isNotBlank() && incomingFolder != expectedFolder) {
            featureId
        } else {
            null
        }
    }.toSet()
}

private fun isDroneTrackMarker(properties: JSONObject?): Boolean {
    if (properties?.optString("class") != "Marker") return false
    return !properties.has("r2c-guid")
}

private fun isMediaObjectWithHiddenParent(
    properties: JSONObject?,
    featuresById: Map<String, JSONObject>,
    hiddenFolderIds: Set<String>,
    hiddenItemIds: Set<String>
): Boolean {
    if (properties?.optString("class") != "MapMediaObject") return false
    val parentMarkerId = properties.optString("parentId")
        .takeIf { it.startsWith("Marker:") }
        ?.removePrefix("Marker:")
        ?.takeIf { it.isNotBlank() }
        ?: return false
    if (parentMarkerId in hiddenItemIds) return true
    val parentProperties = featuresById[parentMarkerId]?.optJSONObject("properties") ?: return false
    val parentClassName = parentProperties.optString("class")
    val parentFolderId = effectiveArtifactFolderId(parentProperties, parentClassName)
    return parentFolderId.isNotBlank() && parentFolderId in hiddenFolderIds
}

private fun effectiveArtifactFolderId(properties: JSONObject?, className: String): String {
    val folderId = properties?.optString("folderId").orEmpty()
    val syntheticFolderId = syntheticArtifactFolderId(properties, className)
    if (syntheticFolderId.isNotBlank()) return syntheticFolderId
    if (folderId.isNotBlank()) return folderId
    return ""
}

private fun syntheticArtifactFolderId(properties: JSONObject?, className: String): String {
    val folderId = properties?.optString("folderId").orEmpty()
    return when {
        className == "Assignment" -> CALTOPO_ASSIGNMENTS_FOLDER_ID
        className == "RangeRing" || folderId == CALTOPO_RANGE_RINGS_FOLDER_TITLE -> CALTOPO_RANGE_RINGS_FOLDER_ID
        className == "Marker" && folderId.isBlank() -> CALTOPO_MARKERS_FOLDER_ID
        folderId == CALTOPO_MARKERS_FOLDER_TITLE -> CALTOPO_MARKERS_FOLDER_ID
        className == "Shape" && (folderId.isBlank() || folderId == CALTOPO_LINES_POLYGONS_FOLDER_TITLE) ->
            CALTOPO_LINES_POLYGONS_FOLDER_ID
        className == "AppTrack" && folderId.isBlank() -> CALTOPO_APP_TRACKS_FOLDER_ID
        folderId == CALTOPO_APP_TRACKS_FOLDER_TITLE -> CALTOPO_APP_TRACKS_FOLDER_ID
        folderId.isBlank() -> CALTOPO_OTHER_MAP_ITEMS_FOLDER_ID
        else -> ""
    }
}

private fun artifactDisplayTitle(properties: JSONObject?, featureId: String, className: String): String {
    properties?.optString("title")?.takeIf { it.isNotBlank() }?.let { return it }
    val typeLabel = className.ifBlank { "Map item" }
    return if (featureId.isBlank()) typeLabel else "$typeLabel:$featureId"
}

private fun archivedDroneTrackPilotCallsign(properties: JSONObject?): String? {
    val r2cProp = properties?.optJSONObject("r2c_prop")
    normalizePilotCallsign(r2cProp?.optString("owner"))?.let { return it }
    val description = properties?.optString("description").orEmpty()
    description.lineSequence().forEach { line ->
        val parts = line.split(":", limit = 2)
        if (parts.size == 2 && parts[0].trim().equals("Pilot Callsign", ignoreCase = true)) {
            return normalizePilotCallsign(parts[1])
        }
    }
    return null
}

private fun orphanFolderTitle(folderId: String): String {
    val suffix = folderId.take(8).ifBlank { "unknown" }
    return "Unlisted Folder $suffix"
}

private fun applySyntheticArtifactFolderDefault(
    properties: JSONObject?,
    viewModel: StreamsViewModel
) {
    syntheticArtifactFolderDefault(properties)?.let {
        viewModel.applyCaltopoFolderDefault(it.folderId, it.initiallyVisible)
    }
}

private fun syntheticArtifactFolderDefault(properties: JSONObject?): ArtifactFolderDefault? {
    val className = properties?.optString("class").orEmpty()
    val folderId = syntheticArtifactFolderId(properties, className)
    val folder = syntheticArtifactFoldersById[folderId] ?: return null
    return ArtifactFolderDefault(folder.id, folder.initiallyVisible)
}

private fun estimateTileCountForBounds(
    bounds: BoundingBox,
    minZoom: Int,
    maxZoom: Int,
    clipBoundary: GeoBoundary? = null
): Int {
    val north = bounds.latNorth.coerceIn(-85.05112878, 85.05112878)
    val south = bounds.latSouth.coerceIn(-85.05112878, 85.05112878)
    val west = bounds.lonWest
    val east = bounds.lonEast
    val loLat = minOf(north, south)
    val hiLat = maxOf(north, south)
    val loLon = minOf(west, east)
    val hiLon = maxOf(west, east)
    var total = 0L
    for (z in minZoom..maxZoom) {
        val maxTile = (1 shl z) - 1
        val minX = lonToTileX(loLon, z).coerceIn(0, maxTile)
        val maxX = lonToTileX(hiLon, z).coerceIn(0, maxTile)
        val minY = latToTileY(hiLat, z).coerceIn(0, maxTile)
        val maxY = latToTileY(loLat, z).coerceIn(0, maxTile)
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                if (tileIndexInsideBoundary(z, x, y, clipBoundary)) {
                    total++
                }
            }
        }
    }
    return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal fun estimateTileCountApproximate(
    bounds: BoundingBox,
    minZoom: Int,
    maxZoom: Int,
    clipBoundary: GeoBoundary? = null
): Int {
    val north = bounds.latNorth.coerceIn(-85.05112878, 85.05112878)
    val south = bounds.latSouth.coerceIn(-85.05112878, 85.05112878)
    val west = bounds.lonWest
    val east = bounds.lonEast
    val loLat = minOf(north, south)
    val hiLat = maxOf(north, south)
    val loLon = minOf(west, east)
    val hiLon = maxOf(west, east)

    var total = 0.0
    for (z in minZoom..maxZoom) {
        val maxTile = (1 shl z) - 1
        val minX = lonToTileX(loLon, z).coerceIn(0, maxTile)
        val maxX = lonToTileX(hiLon, z).coerceIn(0, maxTile)
        val minY = latToTileY(hiLat, z).coerceIn(0, maxTile)
        val maxY = latToTileY(loLat, z).coerceIn(0, maxTile)
        val xCount = (maxX - minX + 1).coerceAtLeast(0)
        val yCount = (maxY - minY + 1).coerceAtLeast(0)
        total += xCount.toDouble() * yCount.toDouble()
    }
    val coverage = boundaryCoverageRatio(bounds, clipBoundary)
    return kotlin.math.max(1, kotlin.math.round(total * coverage).toInt())
}

private suspend fun forEachTileIndexForBounds(
    bounds: BoundingBox,
    minZoom: Int,
    maxZoom: Int,
    clipBoundary: GeoBoundary? = null,
    block: suspend (Long) -> Unit
) {
    val north = bounds.latNorth.coerceIn(-85.05112878, 85.05112878)
    val south = bounds.latSouth.coerceIn(-85.05112878, 85.05112878)
    val west = bounds.lonWest
    val east = bounds.lonEast
    val loLat = minOf(north, south)
    val hiLat = maxOf(north, south)
    val loLon = minOf(west, east)
    val hiLon = maxOf(west, east)
    for (z in minZoom..maxZoom) {
        val maxTile = (1 shl z) - 1
        val minX = lonToTileX(loLon, z).coerceIn(0, maxTile)
        val maxX = lonToTileX(hiLon, z).coerceIn(0, maxTile)
        val minY = latToTileY(hiLat, z).coerceIn(0, maxTile)
        val maxY = latToTileY(loLat, z).coerceIn(0, maxTile)
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                if (!tileIndexInsideBoundary(z, x, y, clipBoundary)) continue
                block(MapTileIndex.getTileIndex(z, x, y))
            }
        }
    }
}

internal fun orderedTileIndexesForOfflinePrep(
    bounds: BoundingBox,
    minZoom: Int,
    maxZoom: Int,
    clipBoundary: GeoBoundary? = null,
    tabletLocation: GeoPoint? = null,
    dronePathPoints: List<GeoPoint> = emptyList()
): List<Long> {
    val allTileIndexes = collectTileIndexesForBounds(bounds, minZoom, maxZoom, clipBoundary)
    if (allTileIndexes.size <= 1 || minZoom > maxZoom) return allTileIndexes

    val available = allTileIndexes.toHashSet()
    val ordered = ArrayList<Long>(allTileIndexes.size)
    val added = HashSet<Long>()

    fun addIfAvailable(tileIndex: Long) {
        if (tileIndex in available && added.add(tileIndex)) {
            ordered += tileIndex
        }
    }

    val mediumZoom = ((minZoom + maxZoom) / 2).coerceIn(minZoom, maxZoom)
    tabletLocation?.let { point ->
        tileIndexForPoint(point, mediumZoom)?.let(::addIfAvailable)
    }
    dronePathTileIndexes(dronePathPoints, mediumZoom).forEach(::addIfAvailable)

    for (zoom in minZoom..maxZoom) {
        if (zoom == mediumZoom) continue
        tabletLocation?.let { point ->
            tileIndexForPoint(point, zoom)?.let(::addIfAvailable)
        }
    }
    for (zoom in minZoom..maxZoom) {
        if (zoom == mediumZoom) continue
        dronePathTileIndexes(dronePathPoints, zoom).forEach(::addIfAvailable)
    }

    for (tileIndex in allTileIndexes) {
        if (added.add(tileIndex)) ordered += tileIndex
    }
    return ordered
}

internal fun liveTilePriorityRequests(
    tabletLocation: GeoPoint?,
    dronePoints: List<DroneMapPoint>,
    visibleZoom: Int
): List<LiveTileRequest> {
    val tileZoom = visibleZoom.coerceIn(0, OSM_MAX_ZOOM.toInt())
    val requests = ArrayList<LiveTileRequest>()
    tabletLocation?.let { location ->
        tileIndexForPoint(location, tileZoom)?.let { tileIndex ->
            requests += LiveTileRequest(
                tileIndex = tileIndex,
                currentTileIndex = tileIndex,
                requiresCurrentCached = false
            )
        }
    }
    requests += droneTilePriorityRequests(dronePoints, tileZoom, existingTileIndexes = requests.map { it.tileIndex }.toSet())
    return requests
}

internal fun droneTilePriorityRequests(
    dronePoints: List<DroneMapPoint>,
    zoom: Int,
    existingTileIndexes: Set<Long> = emptySet()
): List<LiveTileRequest> {
    val currentRequests = ArrayList<LiveTileRequest>()
    val headingRequests = ArrayList<LiveTileRequest>()
    val addedCurrent = existingTileIndexes.toHashSet()
    val addedHeading = HashSet<Long>()
    val currentTileByPoint = LinkedHashMap<DroneMapPoint, Long>()

    for (point in dronePoints) {
        val location = GeoPoint(point.lat, point.lng)
        val currentTileIndex = tileIndexForPoint(location, zoom) ?: continue
        currentTileByPoint[point] = currentTileIndex
        if (addedCurrent.add(currentTileIndex)) {
            currentRequests += LiveTileRequest(
                tileIndex = currentTileIndex,
                currentTileIndex = currentTileIndex,
                requiresCurrentCached = false
            )
        }
    }

    for ((point, currentTileIndex) in currentTileByPoint) {
        val location = GeoPoint(point.lat, point.lng)
        val headingTileIndex = nextTileIndexForHeading(location, zoom, point.headingDeg)
        if (headingTileIndex != null &&
            headingTileIndex != currentTileIndex &&
            headingTileIndex !in addedCurrent &&
            addedHeading.add(headingTileIndex)
        ) {
            headingRequests += LiveTileRequest(
                tileIndex = headingTileIndex,
                currentTileIndex = currentTileIndex,
                requiresCurrentCached = true
            )
        }
    }
    return currentRequests + headingRequests
}

private fun collectTileIndexesForBounds(
    bounds: BoundingBox,
    minZoom: Int,
    maxZoom: Int,
    clipBoundary: GeoBoundary? = null
): List<Long> {
    if (minZoom > maxZoom) return emptyList()
    val north = bounds.latNorth.coerceIn(-85.05112878, 85.05112878)
    val south = bounds.latSouth.coerceIn(-85.05112878, 85.05112878)
    val west = bounds.lonWest
    val east = bounds.lonEast
    val loLat = minOf(north, south)
    val hiLat = maxOf(north, south)
    val loLon = minOf(west, east)
    val hiLon = maxOf(west, east)
    val tileIndexes = ArrayList<Long>()
    for (z in minZoom..maxZoom) {
        val maxTile = (1 shl z) - 1
        val minX = lonToTileX(loLon, z).coerceIn(0, maxTile)
        val maxX = lonToTileX(hiLon, z).coerceIn(0, maxTile)
        val minY = latToTileY(hiLat, z).coerceIn(0, maxTile)
        val maxY = latToTileY(loLat, z).coerceIn(0, maxTile)
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                if (!tileIndexInsideBoundary(z, x, y, clipBoundary)) continue
                tileIndexes += MapTileIndex.getTileIndex(z, x, y)
            }
        }
    }
    return tileIndexes
}

private fun tileIndexForPoint(point: GeoPoint, zoom: Int): Long? {
    if (!point.latitude.isFinite() || !point.longitude.isFinite()) return null
    val maxTile = (1 shl zoom) - 1
    val x = lonToTileX(point.longitude, zoom).coerceIn(0, maxTile)
    val y = latToTileY(point.latitude.coerceIn(-85.05112878, 85.05112878), zoom).coerceIn(0, maxTile)
    return MapTileIndex.getTileIndex(zoom, x, y)
}

private fun nextTileIndexForHeading(point: GeoPoint, zoom: Int, headingDeg: Double?): Long? {
    val heading = headingDeg?.takeIf { it.isFinite() } ?: return null
    val maxTile = (1 shl zoom) - 1
    val currentX = lonToTileX(point.longitude, zoom).coerceIn(0, maxTile)
    val currentY = latToTileY(point.latitude.coerceIn(-85.05112878, 85.05112878), zoom).coerceIn(0, maxTile)
    val normalized = ((heading % 360.0) + 360.0) % 360.0
    val radians = Math.toRadians(normalized)
    val dx = kotlin.math.round(kotlin.math.sin(radians)).toInt()
    val dy = kotlin.math.round(-kotlin.math.cos(radians)).toInt()
    if (dx == 0 && dy == 0) return null
    val nextX = (currentX + dx).coerceIn(0, maxTile)
    val nextY = (currentY + dy).coerceIn(0, maxTile)
    if (nextX == currentX && nextY == currentY) return null
    return MapTileIndex.getTileIndex(zoom, nextX, nextY)
}

private fun dronePathTileIndexes(points: List<GeoPoint>, zoom: Int): List<Long> {
    if (points.isEmpty()) return emptyList()
    val tileCoordinates = points.mapNotNull { point ->
        if (!point.latitude.isFinite() || !point.longitude.isFinite()) {
            null
        } else {
            val maxTile = (1 shl zoom) - 1
            lonToTileX(point.longitude, zoom).coerceIn(0, maxTile) to
                latToTileY(point.latitude.coerceIn(-85.05112878, 85.05112878), zoom).coerceIn(0, maxTile)
        }
    }
    if (tileCoordinates.isEmpty()) return emptyList()

    val tileIndexes = LinkedHashSet<Long>()
    var previous: Pair<Int, Int>? = null
    for (coordinate in tileCoordinates) {
        val line = previous?.let { tileLineBetween(it, coordinate) } ?: listOf(coordinate)
        for ((x, y) in line) {
            tileIndexes += MapTileIndex.getTileIndex(zoom, x, y)
        }
        previous = coordinate
    }
    return tileIndexes.toList()
}

private fun tileLineBetween(start: Pair<Int, Int>, end: Pair<Int, Int>): List<Pair<Int, Int>> {
    val points = ArrayList<Pair<Int, Int>>()
    var x = start.first
    var y = start.second
    val endX = end.first
    val endY = end.second
    val dx = kotlin.math.abs(endX - x)
    val dy = kotlin.math.abs(endY - y)
    val sx = if (x < endX) 1 else -1
    val sy = if (y < endY) 1 else -1
    var err = dx - dy
    while (true) {
        points += x to y
        if (x == endX && y == endY) break
        val e2 = 2 * err
        if (e2 > -dy) {
            err -= dy
            x += sx
        }
        if (e2 < dx) {
            err += dx
            y += sy
        }
    }
    return points
}

private fun estimateDemSamplesForBounds(
    bounds: BoundingBox,
    stepMeters: Double,
    clipBoundary: GeoBoundary? = null
): Int {
    if (stepMeters <= 0.0) return 0
    val north = bounds.latNorth
    val south = bounds.latSouth
    val west = bounds.lonWest
    val east = bounds.lonEast
    val loLat = minOf(north, south)
    val hiLat = maxOf(north, south)
    val loLon = minOf(west, east)
    val hiLon = maxOf(west, east)
    val centerLat = (loLat + hiLat) / 2.0
    val latStepDeg = stepMeters / 111_320.0
    val lonMetersAtLat = 111_320.0 * kotlin.math.cos(Math.toRadians(centerLat)).coerceAtLeast(0.1)
    val lonStepDeg = stepMeters / lonMetersAtLat
    var total = 0
    var lat = loLat
    while (lat <= hiLat) {
        var lon = loLon
        while (lon <= hiLon) {
            if (clipBoundary == null || pointInPolygon(lat, lon, clipBoundary.ring)) {
                total++
            }
            lon += lonStepDeg
        }
        lat += latStepDeg
    }
    return total
}

internal fun estimateDemSamplesApproximate(
    bounds: BoundingBox,
    stepMeters: Double,
    clipBoundary: GeoBoundary? = null
): Int {
    if (stepMeters <= 0.0) return 0
    val effectiveArea = if (clipBoundary != null) {
        polygonAreaMeters2(clipBoundary.ring).coerceAtLeast(0.0)
    } else {
        boundsAreaMeters2(bounds).coerceAtLeast(0.0)
    }
    if (effectiveArea <= 0.0) return 0
    val sampleArea = stepMeters * stepMeters
    return kotlin.math.max(1, kotlin.math.ceil(effectiveArea / sampleArea).toInt())
}

private suspend fun forEachDemSamplePointForBounds(
    bounds: BoundingBox,
    stepMeters: Double,
    clipBoundary: GeoBoundary? = null,
    block: suspend (Double, Double) -> Unit
) {
    if (stepMeters <= 0.0) return
    val north = bounds.latNorth
    val south = bounds.latSouth
    val west = bounds.lonWest
    val east = bounds.lonEast
    val loLat = minOf(north, south)
    val hiLat = maxOf(north, south)
    val loLon = minOf(west, east)
    val hiLon = maxOf(west, east)
    val centerLat = (loLat + hiLat) / 2.0
    val latStepDeg = stepMeters / 111_320.0
    val lonMetersAtLat = 111_320.0 * kotlin.math.cos(Math.toRadians(centerLat)).coerceAtLeast(0.1)
    val lonStepDeg = stepMeters / lonMetersAtLat
    var lat = loLat
    while (lat <= hiLat) {
        var lon = loLon
        while (lon <= hiLon) {
            if (clipBoundary == null || pointInPolygon(lat, lon, clipBoundary.ring)) {
                block(lat, lon)
            }
            lon += lonStepDeg
        }
        lat += latStepDeg
    }
}

/** Returns the USGS 3DEP 1° tile name (e.g. "n40w122") that covers the given coordinate. */
private fun tileNameForLocation(lat: Double, lng: Double): String {
    val tileNorth = kotlin.math.floor(lat).toInt() + 1
    val tileLonBlock = kotlin.math.floor(lng).toInt()
    val latPart = if (tileNorth >= 0) "n%02d".format(tileNorth) else "s%02d".format(-tileNorth)
    val lonPart = if (tileLonBlock < 0) "w%03d".format(-tileLonBlock) else "e%03d".format(tileLonBlock + 1)
    return "$latPart$lonPart"
}

/**
 * Background helper: downloads one USGS 3DEP 1° GeoTIFF tile into the archive DEM cache if it
 * is not already present (or is incomplete). After a successful download the GeoTiffDemSource
 * catalog is invalidated so subsequent DEM queries are served from the new local file.
 *
 * Must be called from an IO coroutine. Swallows non-cancellation exceptions.
 */
private suspend fun autoDownloadDemTile(
    tileName: String,
    context: Context,
    client: OkHttpClient,
    service: DemElevationService
) {
    val archiveRoot = CaltopoClient.GetArchiveDir() ?: run {
        MapCacheDebug.log("auto-dem: no archive dir, skipping tile=$tileName")
        return
    }
    val cacheDir = archiveRoot.findFile("cache") ?: archiveRoot.createDirectory("cache") ?: return
    val demDir = cacheDir.findFile("dem") ?: cacheDir.createDirectory("dem") ?: return
    val fileName = "USGS_1_$tileName.tif"
    val existing = demDir.findFile(fileName)
    if (existing != null && existing.isFile && existing.length() > 5_000_000L) {
        MapCacheDebug.log("auto-dem: already present tile=$tileName bytes=${existing.length()}")
        service.refreshGeoTiffCatalog()
        return
    }
    val url = "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/1/TIFF/current/$tileName/USGS_1_$tileName.tif"
    CTDebug(MAP_PANE_TAG, "auto-dem: downloading tile=$tileName")
    try {
        val ok = client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                CTError(MAP_PANE_TAG, "auto-dem http-fail code=${resp.code} tile=$tileName")
                return@use false
            }
            val body = resp.body ?: run { CTError(MAP_PANE_TAG, "auto-dem no-body tile=$tileName"); return@use false }
            val destFile = demDir.findFile(fileName) ?: demDir.createFile("image/tiff", fileName)
                ?: run { CTError(MAP_PANE_TAG, "auto-dem create-failed tile=$tileName"); return@use false }
            context.contentResolver.openOutputStream(destFile.uri, "wt")?.use { out ->
                body.byteStream().copyTo(out)
            } ?: run { CTError(MAP_PANE_TAG, "auto-dem stream-open-failed tile=$tileName"); return@use false }
            true
        }
        if (ok) {
            CTDebug(MAP_PANE_TAG, "auto-dem: complete tile=$tileName")
            service.refreshGeoTiffCatalog()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CTError(MAP_PANE_TAG, "auto-dem ex tile=$tileName: ${e.javaClass.simpleName}:${e.message}")
    }
}

private fun demTileNamesForBounds(bounds: BoundingBox): List<String> {
    val latMin = minOf(bounds.latNorth, bounds.latSouth)
    val latMax = maxOf(bounds.latNorth, bounds.latSouth)
    val lonMin = minOf(bounds.lonWest, bounds.lonEast)
    val lonMax = maxOf(bounds.lonWest, bounds.lonEast)
    // USGS 3DEP 1° tile naming: "n40w122" means NW corner at 40°N, 122°W → covers 39–40°N, 121–122°W.
    val latSouthBlock = kotlin.math.floor(latMin).toInt()
    val latNorthBlock = kotlin.math.ceil(latMax).toInt() - 1
    val lonWestBlock = kotlin.math.floor(lonMin).toInt()
    val lonEastBlock = kotlin.math.ceil(lonMax).toInt() - 1
    val names = mutableListOf<String>()
    for (latBlock in latSouthBlock..latNorthBlock) {
        val tileNorth = latBlock + 1
        val latPart = if (tileNorth >= 0) "n%02d".format(tileNorth) else "s%02d".format(-tileNorth)
        for (lonBlock in lonWestBlock..lonEastBlock) {
            val lonPart = if (lonBlock < 0) "w%03d".format(-lonBlock) else "e%03d".format(lonBlock + 1)
            names += "$latPart$lonPart"
        }
    }
    return names
}

private fun lonToTileX(lon: Double, zoom: Int): Int {
    val n = 1 shl zoom
    val x = ((lon + 180.0) / 360.0 * n.toDouble())
    return kotlin.math.floor(x).toInt()
}

private fun latToTileY(lat: Double, zoom: Int): Int {
    val latRad = Math.toRadians(lat)
    val n = 1 shl zoom
    val y = (1.0 - kotlin.math.ln(kotlin.math.tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / Math.PI) / 2.0 * n.toDouble()
    return kotlin.math.floor(y).toInt()
}

private fun tileXToLon(x: Int, zoom: Int): Double {
    val n = 1 shl zoom
    return x.toDouble() / n.toDouble() * 360.0 - 180.0
}

private fun tileYToLat(y: Int, zoom: Int): Double {
    val n = 1 shl zoom
    val m = Math.PI * (1.0 - 2.0 * y.toDouble() / n.toDouble())
    return Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(m)))
}

private fun tileIndexInsideBoundary(
    zoom: Int,
    x: Int,
    y: Int,
    clipBoundary: GeoBoundary?
): Boolean {
    val boundary = clipBoundary ?: return true
    val west = tileXToLon(x, zoom)
    val east = tileXToLon(x + 1, zoom)
    val north = tileYToLat(y, zoom)
    val south = tileYToLat(y + 1, zoom)

    if (east < boundary.bounds.lonWest || west > boundary.bounds.lonEast) return false
    if (north < boundary.bounds.latSouth || south > boundary.bounds.latNorth) return false

    val centerLat = (north + south) * 0.5
    val centerLon = (west + east) * 0.5
    if (pointInPolygon(centerLat, centerLon, boundary.ring)) return true

    val corners = arrayOf(
        doubleArrayOf(north, west),
        doubleArrayOf(north, east),
        doubleArrayOf(south, east),
        doubleArrayOf(south, west)
    )
    for (corner in corners) {
        if (pointInPolygon(corner[0], corner[1], boundary.ring)) return true
    }

    boundary.ring.forEach { point ->
        if (point.latitude in south..north && point.longitude in west..east) return true
    }
    return false
}

private fun pointInPolygon(lat: Double, lon: Double, ring: List<GeoPoint>): Boolean {
    if (ring.size < 3) return false
    var inside = false
    var j = ring.lastIndex
    for (i in ring.indices) {
        val yi = ring[i].latitude
        val xi = ring[i].longitude
        val yj = ring[j].latitude
        val xj = ring[j].longitude
        val denom = (yj - yi).takeIf { kotlin.math.abs(it) > 1e-12 } ?: 1e-12
        val intersects = ((yi > lat) != (yj > lat)) &&
            (lon < (xj - xi) * (lat - yi) / denom + xi)
        if (intersects) inside = !inside
        j = i
    }
    return inside
}

private fun boundaryCoverageRatio(bounds: BoundingBox, clipBoundary: GeoBoundary?): Double {
    if (clipBoundary == null) return 1.0
    val bboxArea = boundsAreaMeters2(bounds)
    if (bboxArea <= 1.0) return 1.0
    val polygonArea = polygonAreaMeters2(clipBoundary.ring)
    if (polygonArea <= 0.0) return 1.0
    return (polygonArea / bboxArea).coerceIn(0.02, 1.0)
}

private fun boundsAreaMeters2(bounds: BoundingBox): Double {
    val north = bounds.latNorth
    val south = bounds.latSouth
    val west = bounds.lonWest
    val east = bounds.lonEast
    val latCenter = ((north + south) * 0.5)
    val latMetersPerDeg = 111_320.0
    val lonMetersPerDeg = 111_320.0 * kotlin.math.cos(Math.toRadians(latCenter)).coerceAtLeast(0.1)
    val widthM = kotlin.math.abs(east - west) * lonMetersPerDeg
    val heightM = kotlin.math.abs(north - south) * latMetersPerDeg
    return widthM * heightM
}

private fun polygonAreaMeters2(ring: List<GeoPoint>): Double {
    if (ring.size < 3) return 0.0
    val centerLat = ring.map { it.latitude }.average()
    val centerLon = ring.map { it.longitude }.average()
    val latMetersPerDeg = 111_320.0
    val lonMetersPerDeg = 111_320.0 * kotlin.math.cos(Math.toRadians(centerLat)).coerceAtLeast(0.1)
    var twiceArea = 0.0
    for (i in ring.indices) {
        val j = (i + 1) % ring.size
        val x1 = (ring[i].longitude - centerLon) * lonMetersPerDeg
        val y1 = (ring[i].latitude - centerLat) * latMetersPerDeg
        val x2 = (ring[j].longitude - centerLon) * lonMetersPerDeg
        val y2 = (ring[j].latitude - centerLat) * latMetersPerDeg
        twiceArea += (x1 * y2) - (x2 * y1)
    }
    return kotlin.math.abs(twiceArea) * 0.5
}

private fun formatDurationShort(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0L)
    val h = safe / 3600L
    val m = (safe % 3600L) / 60L
    val s = safe % 60L
    return if (h > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

internal fun queryAvailableCacheBytes(context: android.content.Context): Long? {
    return try {
        when (val root = MapCacheRootResolver.resolveRoot(context.applicationContext)) {
            is MapCacheRoot.FileBacked -> {
                val stat = StatFs(root.dir.absolutePath)
                stat.availableBytes
            }
            is MapCacheRoot.SafBacked -> {
                null
            }
        }
    } catch (_: Exception) {
        null
    }
}

internal fun mapCacheRootSignature(context: android.content.Context): String {
    return try {
        when (val root = MapCacheRootResolver.resolveRoot(context.applicationContext)) {
            is MapCacheRoot.FileBacked -> "file:${root.dir.absolutePath}"
            is MapCacheRoot.SafBacked -> "saf:${root.dir.uri}"
        }
    } catch (_: Exception) {
        "unknown"
    }
}

private fun exportBadTileHashes(context: android.content.Context): String? {
    return try {
        val hashes = BadTilePolicy.blockedHashesSorted(context)
        val header = "# RID2Caltopo bad tile hashes\n# count=${hashes.size}\n"
        val body = if (hashes.isEmpty()) "# (none)\n" else hashes.joinToString(separator = "\n", postfix = "\n")
        val payload = (header + body).toByteArray(Charsets.UTF_8)
        when (val root = MapCacheRootResolver.resolveRoot(context.applicationContext)) {
            is MapCacheRoot.FileBacked -> {
                val out = File(root.dir, "bad_tile_hashes.txt")
                out.writeBytes(payload)
                out.absolutePath
            }
            is MapCacheRoot.SafBacked -> {
                val existing = root.dir.findFile("bad_tile_hashes.txt")
                val file = existing ?: root.dir.createFile("text/plain", "bad_tile_hashes.txt")
                if (file == null) return null
                context.applicationContext.contentResolver.openOutputStream(file.uri, "w")?.use { out ->
                    out.write(payload)
                    out.flush()
                } ?: return null
                file.uri.toString()
            }
        }
    } catch (e: Exception) {
        MapCacheDebug.log("bad-hash export failed err=${e.javaClass.simpleName}:${e.message}")
        null
    }
}

internal fun buildOfflineBoundaryOptions(state: ArtifactOverlayState): List<OfflineBoundaryOption> {
    val options = mutableListOf<OfflineBoundaryOption>()
    state.polygons.forEachIndexed { index, polygon ->
        val boundary = geoBoundaryFromPoints(polygon.points) ?: return@forEachIndexed
        options += OfflineBoundaryOption(
            id = "poly:${polygon.id}:$index",
            label = "[Polygon] ${polygon.title}",
            boundary = boundary
        )
    }
    state.lines.forEachIndexed { index, line ->
        val boundary = geoBoundaryFromPoints(line.points) ?: return@forEachIndexed
        options += OfflineBoundaryOption(
            id = "line:${line.id}:$index",
            label = "[Line] ${line.title}",
            boundary = boundary
        )
    }
    return options
}

private fun geoBoundaryFromPoints(points: List<GeoPoint>): GeoBoundary? {
    if (points.size < 3) return null
    val ring = if (points.first().latitude == points.last().latitude && points.first().longitude == points.last().longitude) {
        points
    } else {
        points + points.first()
    }
    return GeoBoundary(
        ring = ring,
        bounds = boundingBoxFromPoints(ring)
    )
}

private fun isTrackLikeFeature(properties: JSONObject?, className: String): Boolean {
    // Active drone tracks (className="LiveTrack") are rendered by the drone tracking system;
    // suppress them here to avoid double-rendering.
    if (className == "LiveTrack") return true
    // Features in the active track folder (not yet archived) are also managed by the drone
    // tracking system.  The archive folder is intentionally excluded: completed/archived drone
    // tracks are static line features that should be renderable via the Map Folders dialog when
    // the user enables their folder.  Their default-hidden state is handled by hiddenFolderIds.
    val folderId = properties?.optString("folderId").orEmpty()
    val mapTrackFolderId = CaltopoMap.GetFolderId().orEmpty()
    if (folderId.isNotBlank() && folderId == mapTrackFolderId) return true
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
            if (trackLikeFeature) {
                ignoredTrackLike++
                return ignoredTrackLike
            }
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

private fun artifactLogSummary(feature: JSONObject): String {
    val featureId = feature.optString("id").ifBlank { "?" }
    val props = feature.optJSONObject("properties")
    val className = props?.optString("class").orEmpty().ifBlank { "unknown" }
    val title = props?.optString("title").orEmpty().ifBlank { "<untitled>" }
    val description = props?.optString("description").orEmpty().trim()
    val descriptionSummary = when {
        description.isBlank() -> ""
        description.length <= 48 -> " desc=\"$description\""
        else -> " desc=\"${description.take(45)}...\""
    }
    return "id=$featureId class=$className title=\"$title\"$descriptionSummary"
}

private fun localDeviceMarkerColor(): String {
    val state = R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator.coordinationIndicatorState
    if (CaltopoMap.IsInitialDeviceMarkerPublishPending() &&
        state != PeerCoordinator.CoordinationIndicatorState.HEALTHY &&
        state != PeerCoordinator.CoordinationIndicatorState.IDLE) {
        return LOCAL_DEVICE_COLOR_STARTING
    }
    return when (state) {
        PeerCoordinator.CoordinationIndicatorState.HEALTHY -> LOCAL_DEVICE_COLOR_HEALTHY
        PeerCoordinator.CoordinationIndicatorState.IDLE -> LOCAL_DEVICE_COLOR_HEALTHY
        PeerCoordinator.CoordinationIndicatorState.DEGRADED -> LOCAL_DEVICE_COLOR_DEGRADED
        PeerCoordinator.CoordinationIndicatorState.UNCONFIGURED -> LOCAL_DEVICE_COLOR_UNCONFIGURED
    }
}

private fun localDeviceStatusLines(): List<String> {
    val coordinator = R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator
    return buildList {
        add(coordinator.coordinationStatusText)
        addAll(coordinator.coordinationDiagnosticLines)
    }
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

private fun BoundingBox.containsLocation(location: Location): Boolean {
    val minLat = minOf(latNorth, latSouth)
    val maxLat = maxOf(latNorth, latSouth)
    val minLon = minOf(lonWest, lonEast)
    val maxLon = maxOf(lonWest, lonEast)
    return location.latitude in minLat..maxLat && location.longitude in minLon..maxLon
}

private fun locationAgeMs(location: Location, nowMs: Long): Long {
    val locationTimeMs = location.time
    return if (locationTimeMs > 0L) {
        (nowMs - locationTimeMs).coerceAtLeast(0L)
    } else {
        Long.MAX_VALUE
    }
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

private fun distanceFeetFromTakeoff(
    point: DroneMapPoint,
    lat: Double = point.lat,
    lng: Double = point.lng
): Double? {
    val spec = point.droneSpec ?: return null
    if (!spec.hasTakeoffLocation()) return null
    val takeoffLat = spec.takeoffLat
    val takeoffLng = spec.takeoffLng
    if (!takeoffLat.isFinite() || !takeoffLng.isFinite()) return null
    if (!lat.isFinite() || !lng.isFinite()) return null
    val result = FloatArray(1)
    Location.distanceBetween(takeoffLat, takeoffLng, lat, lng, result)
    return if (result[0].isFinite()) result[0].toDouble() * METERS_TO_FEET else null
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


private fun predictedHeadPoint(
    designator: String,
    nowWallMsec: Long,
    dronePointTimestampMsec: Long,
    tracksByMappedId: Map<String, List<LocalTrackPoint>>
): PredictedHead? {
    val points = tracksByMappedId[designator] ?: return null
    val p2 = points.lastOrNull() ?: return null
    val p1 = points.asReversed().drop(1).firstOrNull() ?: return null

    val deltaByDroneTsMsec = p2.timestampMsec - p1.timestampMsec
    val deltaByReceiveMsec = p2.receivedAtMsec - p1.receivedAtMsec
    val deltaMsec = when {
        deltaByDroneTsMsec > 0L -> deltaByDroneTsMsec
        deltaByReceiveMsec > 0L -> deltaByReceiveMsec
        else -> return null
    }

    val distanceAndBearing = FloatArray(2)
    Location.distanceBetween(
        p1.lat, p1.lng,
        p2.lat, p2.lng,
        distanceAndBearing
    )
    val segmentDistanceM = distanceAndBearing[0].toDouble()
    if (!segmentDistanceM.isFinite() || segmentDistanceM <= 0.0) return null

    val speedMps = (segmentDistanceM / deltaMsec.toDouble() * 1000.0)
        .coerceAtMost(PREDICTIVE_HEAD_MAX_SPEED_MPS)
    if (speedMps <= 0.0) return null

    val ageMsec = nowWallMsec - dronePointTimestampMsec
    if (ageMsec < PREDICTIVE_HEAD_MIN_AGE_MS || ageMsec > PREDICTIVE_HEAD_MAX_AGE_MS) return null

    val projectionMsec = ageMsec.coerceAtMost(PREDICTIVE_HEAD_MAX_LOOKAHEAD_MS)
    val projectionDistanceM = (speedMps * projectionMsec.toDouble() / 1000.0)
        .coerceAtMost(PREDICTIVE_HEAD_MAX_DISTANCE_M)
    if (projectionDistanceM <= 0.0) return null

    val predictedGeoPoint = destinationPoint(
        startLat = p2.lat,
        startLng = p2.lng,
        bearingDeg = distanceAndBearing[1].toDouble(),
        distanceM = projectionDistanceM
    )
    return PredictedHead(
        lat = predictedGeoPoint.latitude,
        lng = predictedGeoPoint.longitude
    )
}

private fun destinationPoint(
    startLat: Double,
    startLng: Double,
    bearingDeg: Double,
    distanceM: Double
): GeoPoint {
    val earthRadiusM = 6_371_000.0
    val angularDistance = distanceM / earthRadiusM
    val bearing = Math.toRadians(bearingDeg)
    val lat1 = Math.toRadians(startLat)
    val lon1 = Math.toRadians(startLng)

    val sinLat1 = kotlin.math.sin(lat1)
    val cosLat1 = kotlin.math.cos(lat1)
    val sinAngular = kotlin.math.sin(angularDistance)
    val cosAngular = kotlin.math.cos(angularDistance)

    val lat2 = kotlin.math.asin(
        sinLat1 * cosAngular + cosLat1 * sinAngular * kotlin.math.cos(bearing)
    )
    val lon2 = lon1 + kotlin.math.atan2(
        kotlin.math.sin(bearing) * sinAngular * cosLat1,
        cosAngular - sinLat1 * kotlin.math.sin(lat2)
    )

    return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
}

private fun normalizeDegrees(value: Double): Double {
    val normalized = value % 360.0
    return if (normalized < 0.0) normalized + 360.0 else normalized
}

internal data class ScreenLine(
    val startX: Double,
    val startY: Double,
    val endX: Double,
    val endY: Double
)

internal fun droneStatusLabelText(
    atoFeet: Double?,
    aglFeet: Double?,
    aglStale: Boolean,
    rangeFeet: Double?,
    headingDeg: Double?
): String {
    val ato = atoFeet
        ?.takeIf { kotlin.math.abs(it) <= LABEL_MAX_ABS_FEET }
        ?.let { String.format(Locale.US, "%.0f", it) }
        ?: "--"
    val agl = aglFeet
        ?.takeIf { kotlin.math.abs(it) <= LABEL_MAX_ABS_FEET }
        ?.let { String.format(Locale.US, "%.0f%s", it, if (aglStale) "?" else "") }
        ?: "--"
    val range = rangeFeet
        ?.let { String.format(Locale.US, "%.0f", it) }
        ?: "--"
    val heading = headingDeg
        ?.takeIf { it.isFinite() }
        ?.let { String.format(Locale.US, "%.0f", normalizeDegrees(it)) }
        ?: "--"
    return "ATO:$ato' AGL:$agl' RNG:$range' HDG:$heading°"
}

internal fun droneDetailLines(
    locationText: String,
    coordinateFormatLabel: String,
    atoFeet: Double?,
    aglFeet: Double?,
    aglStale: Boolean,
    rangeFeet: Double?,
    headingDeg: Double?,
    speedKnots: Double? = null,
    climbFpm: Double? = null
): List<String> = buildList {
    val ato = atoFeet
        ?.takeIf { kotlin.math.abs(it) <= LABEL_MAX_ABS_FEET }
        ?.let { String.format(Locale.US, "%.0f'", it) }
        ?: "--"
    val agl = aglFeet
        ?.takeIf { kotlin.math.abs(it) <= LABEL_MAX_ABS_FEET }
        ?.let { String.format(Locale.US, "%.0f%s'", it, if (aglStale) "?" else "") }
        ?: "--"
    val range = rangeFeet
        ?.let { String.format(Locale.US, "%.0f'", it) }
        ?: "--"
    val heading = headingDeg
        ?.takeIf { it.isFinite() }
        ?.let { String.format(Locale.US, "%.0f°", normalizeDegrees(it)) }
        ?: "--"
    add("Location: $locationText ($coordinateFormatLabel)")
    add("ATO: $ato")
    add("AGL: $agl")
    add("RNG: $range")
    add("HDG: $heading")
    speedKnots?.let { add(String.format(Locale.US, "Speed: %.1f kt", it)) }
    climbFpm?.let { add(String.format(Locale.US, "Climb: %.0f fpm", it)) }
}

internal fun bearingLineToViewportEdge(
    startX: Double,
    startY: Double,
    headingDeg: Double?,
    viewportWidth: Int,
    viewportHeight: Int
): ScreenLine? {
    val heading = headingDeg?.takeIf { it.isFinite() } ?: return null
    if (viewportWidth <= 0 || viewportHeight <= 0) return null
    val radians = Math.toRadians(normalizeDegrees(heading))
    val dx = kotlin.math.sin(radians)
    val dy = -kotlin.math.cos(radians)
    val candidates = mutableListOf<Double>()
    if (dx > 0.0) candidates += (viewportWidth.toDouble() - startX) / dx
    if (dx < 0.0) candidates += (0.0 - startX) / dx
    if (dy > 0.0) candidates += (viewportHeight.toDouble() - startY) / dy
    if (dy < 0.0) candidates += (0.0 - startY) / dy
    val distance = candidates
        .filter { it > 0.0 && it.isFinite() }
        .minOrNull()
        ?: return null
    return ScreenLine(
        startX = startX,
        startY = startY,
        endX = startX + dx * distance,
        endY = startY + dy * distance
    )
}

private fun polarPoint(
    cx: Float,
    cy: Float,
    distancePx: Float,
    bearingDeg: Double
): Pair<Float, Float> {
    val radians = Math.toRadians(bearingDeg - 90.0)
    val x = cx + (kotlin.math.cos(radians) * distancePx).toFloat()
    val y = cy + (kotlin.math.sin(radians) * distancePx).toFloat()
    return x to y
}

private fun buildDroneStatusLabelDrawable(
    resources: android.content.res.Resources,
    text: String
): Drawable {
    val density = resources.displayMetrics.density
    val scaledDensity = density * resources.configuration.fontScale
    val textSizePx = 13f * scaledDensity
    val cornerPx = 5f * density
    val horizontalPaddingPx = 5f * density
    val verticalPaddingPx = 2.5f * density

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#FFFFFF")
        textSize = textSizePx
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        style = Paint.Style.FILL
    }
    val haloPaint = Paint(fillPaint).apply {
        color = AndroidColor.parseColor("#CC000000")
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeJoin = Paint.Join.ROUND
    }
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#66000000")
        style = Paint.Style.FILL
    }
    val fm = fillPaint.fontMetrics
    val textWidth = fillPaint.measureText(text)
    val textHeight = fm.descent - fm.ascent
    val width = maxOf(1, (textWidth + (horizontalPaddingPx * 2f)).toInt())
    val height = maxOf(1, (textHeight + (verticalPaddingPx * 2f)).toInt())
    val baselineY = verticalPaddingPx - fm.ascent
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), cornerPx, cornerPx, bgPaint)
    canvas.drawText(text, horizontalPaddingPx, baselineY, haloPaint)
    canvas.drawText(text, horizontalPaddingPx, baselineY, fillPaint)
    return BitmapDrawable(resources, bitmap)
}

private fun buildDroneNameLabelDrawable(
    resources: android.content.res.Resources,
    text: String
): Drawable {
    val density = resources.displayMetrics.density
    val scaledDensity = density * resources.configuration.fontScale
    val textSizePx = 16f * scaledDensity
    val cornerPx = 6f * density
    val horizontalPaddingPx = 6f * density
    val verticalPaddingPx = 3f * density

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#FFFFFF")
        textSize = textSizePx
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        style = Paint.Style.FILL
    }
    val haloPaint = Paint(fillPaint).apply {
        color = AndroidColor.parseColor("#CC000000")
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeJoin = Paint.Join.ROUND
    }
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#66000000")
        style = Paint.Style.FILL
    }
    val fm = fillPaint.fontMetrics
    val textWidth = fillPaint.measureText(text)
    val textHeight = fm.descent - fm.ascent
    val width = maxOf(1, (textWidth + (horizontalPaddingPx * 2f)).toInt())
    val height = maxOf(1, (textHeight + (verticalPaddingPx * 2f)).toInt())
    val baselineY = verticalPaddingPx - fm.ascent
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), cornerPx, cornerPx, bgPaint)
    canvas.drawText(text, horizontalPaddingPx, baselineY, haloPaint)
    canvas.drawText(text, horizontalPaddingPx, baselineY, fillPaint)
    return BitmapDrawable(resources, bitmap)
}

private fun buildDroneMarkerDrawable(
    resources: android.content.res.Resources,
    baseIcon: Drawable?,
    tint: Int?,
    headingDeg: Double?,
    scale: Float = 1.0f
): Drawable? {
    if (baseIcon == null) return null
    val safeScale = drawableScaleOrDefault(scale)
    val density = resources.displayMetrics.density
    val icon = baseIcon.constantState?.newDrawable(resources)?.mutate() ?: baseIcon.mutate()
    if (tint != null) {
        icon.setTint(tint)
    } else {
        icon.clearColorFilter()
    }

    val iconW = if (icon.intrinsicWidth > 0) {
        scaledDimension(icon.intrinsicWidth, safeScale)
    } else {
        (18f * density * safeScale).roundToInt().coerceAtLeast(1)
    }
    val iconH = if (icon.intrinsicHeight > 0) {
        scaledDimension(icon.intrinsicHeight, safeScale)
    } else {
        (18f * density * safeScale).roundToInt().coerceAtLeast(1)
    }
    val overlayReach = (18f * density * safeScale).roundToInt().coerceAtLeast(1)
    val pad = maxOf((4f * density * safeScale).roundToInt().coerceAtLeast(1), overlayReach)
    val width = iconW + pad * 2
    val height = iconH + pad * 2
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = width / 2f
    val cy = height / 2f
    val scaledDensity = density * safeScale
    val radius = (maxOf(iconW, iconH) / 2f) + (2.5f * scaledDensity)
    val headingStart = radius + (0.5f * scaledDensity)
    val headingTip = radius + (9.0f * scaledDensity)
    val headingPointerLen = 3.8f * scaledDensity

    val haloFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#CCFFFFFF")
        style = Paint.Style.FILL
    }
    val haloStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#99000000")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * scaledDensity
    }
    val overlayHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#B3000000")
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * scaledDensity
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#FFF8E1")
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * scaledDensity
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    headingDeg?.takeIf { it.isFinite() }?.let { bearing ->
        val normalizedBearing = normalizeDegrees(bearing)
        val (startX, startY) = polarPoint(cx, cy, headingStart, normalizedBearing)
        val (tipX, tipY) = polarPoint(cx, cy, headingTip, normalizedBearing)
        val (leftX, leftY) = polarPoint(tipX, tipY, headingPointerLen, normalizedBearing - 150.0)
        val (rightX, rightY) = polarPoint(tipX, tipY, headingPointerLen, normalizedBearing + 150.0)
        canvas.drawLine(startX, startY, tipX, tipY, overlayHalo)
        canvas.drawLine(leftX, leftY, tipX, tipY, overlayHalo)
        canvas.drawLine(rightX, rightY, tipX, tipY, overlayHalo)
        canvas.drawLine(startX, startY, tipX, tipY, headingPaint)
        canvas.drawLine(leftX, leftY, tipX, tipY, headingPaint)
        canvas.drawLine(rightX, rightY, tipX, tipY, headingPaint)
    }

    canvas.drawCircle(cx, cy, radius, haloFill)
    canvas.drawCircle(cx, cy, radius, haloStroke)

    icon.setBounds(pad, pad, pad + iconW, pad + iconH)
    icon.draw(canvas)
    return BitmapDrawable(resources, bitmap)
}

private fun buildNotamMarkerIcon(
    context: Context,
    fillColor: Int
): Drawable {
    val sizePx = 88
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = sizePx * 0.22f
    val center = sizePx / 2f
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = AndroidColor.BLACK
        strokeWidth = 6f
    }
    canvas.drawCircle(center, center, radius, fill)
    canvas.drawCircle(center, center, radius, border)
    return BitmapDrawable(context.resources, bitmap)
}

private fun isKnownArtifactSymbol(symbol: String): Boolean {
    return symbolGlyphForMarkerSymbol(symbol) != null
}

private fun markerIconForArtifactSymbol(
    resources: android.content.res.Resources,
    symbol: String,
    colorHex: String?,
    cache: MutableMap<String, Drawable>,
    scale: Float = 1.0f
): Drawable {
    val normalizedSymbol = symbol.ifBlank { "point" }
    val normalizedColor = normalizeMarkerColor(colorHex, normalizedSymbol)
    val safeScale = drawableScaleOrDefault(scale)
    val cacheKey = "$normalizedSymbol|$normalizedColor|${"%.3f".format(Locale.US, safeScale)}"
    val cached = cache[cacheKey]
    if (cached != null) {
        return cached.constantState?.newDrawable(resources)?.mutate() ?: cached
    }

    val icon = scaleDrawableBitmap(
        resources = resources,
        drawable = buildCaltopoLikeSymbolDrawable(resources, normalizedSymbol, normalizedColor),
        scale = safeScale
    )
    cache[cacheKey] = icon
    return icon.constantState?.newDrawable(resources)?.mutate() ?: icon
}

private fun drawableScaleOrDefault(scale: Float): Float =
    if (scale.isFinite() && scale > 0f) scale else 1.0f

private fun scaledDimension(value: Int, scale: Float): Int =
    (value * scale).roundToInt().coerceAtLeast(1)

private fun scaleDrawableBitmap(
    resources: android.content.res.Resources,
    drawable: Drawable,
    scale: Float
): Drawable {
    if (scale == 1.0f) return drawable
    val width = scaledDimension(drawable.intrinsicWidth.takeIf { it > 0 } ?: 1, scale)
    val height = scaledDimension(drawable.intrinsicHeight.takeIf { it > 0 } ?: 1, scale)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val previousBounds = Rect(drawable.bounds)
    drawable.setBounds(0, 0, width, height)
    drawable.draw(canvas)
    drawable.setBounds(previousBounds)
    return BitmapDrawable(resources, bitmap)
}

private fun cachedScaledRemoteMarkerDrawable(
    resources: android.content.res.Resources,
    source: Drawable,
    cache: MutableMap<String, Drawable>,
    cacheKey: String,
    scale: Float
): Drawable {
    val safeScale = drawableScaleOrDefault(scale)
    val scaledCacheKey = "$cacheKey|${"%.3f".format(Locale.US, safeScale)}"
    val cached = cache[scaledCacheKey]
    if (cached != null) {
        return cached.constantState?.newDrawable(resources)?.mutate() ?: cached
    }
    val scaled = scaleDrawableBitmap(
        resources = resources,
        drawable = source.constantState?.newDrawable(resources)?.mutate() ?: source.mutate(),
        scale = safeScale
    )
    cache[scaledCacheKey] = scaled
    return scaled.constantState?.newDrawable(resources)?.mutate() ?: scaled
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
