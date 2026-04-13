package org.ncssar.rid2caltopo.video

import StreamsViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.StatFs
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import org.ncssar.rid2caltopo.ui.MapFoldersDialog
import org.ncssar.rid2caltopo.ui.MapFolderUiState
import org.ncssar.rid2caltopo.ui.MapItemUiState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
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
import org.ncssar.rid2caltopo.BuildConfig
import org.ncssar.rid2caltopo.R
import org.ncssar.rid2caltopo.app.R2CActivity
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebugEnabled
import org.ncssar.rid2caltopo.data.CaltopoLiveTrack
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.MutualAidProfileManager
import org.ncssar.rid2caltopo.notam.NearbyNotam
import org.ncssar.rid2caltopo.notam.NotamCenter
import org.ncssar.rid2caltopo.notam.NotamMapOverlayAdapter
import org.ncssar.rid2caltopo.video.mapcache.CaltopoIconCacheService
import org.ncssar.rid2caltopo.video.mapcache.BadTilePolicy
import org.ncssar.rid2caltopo.video.mapcache.DemElevationService
import org.ncssar.rid2caltopo.video.mapcache.MapCacheDebug
import org.ncssar.rid2caltopo.video.mapcache.MapCachePolicy
import org.ncssar.rid2caltopo.video.mapcache.MapCacheRoot
import org.ncssar.rid2caltopo.video.mapcache.MapCacheRootResolver
import org.ncssar.rid2caltopo.video.mapcache.TileCacheMapProvider
import org.ncssar.rid2caltopo.video.mapcache.TileDiskCacheWriter

// Constants
internal const val MAP_PANE_TAG = "SplitMapPane"
internal const val MAP_PANE_VERBOSE_LOGS = false
internal const val LOCAL_DEVICE_SYMBOL = "radiotower"
internal const val LOCAL_DEVICE_COLOR = "0000FF"
internal const val ICON_LATENCY_TAG = "RidIconLatency"
internal const val AGL_LIMIT_FT = 200.0
internal const val CALIBRATE_ATO_TARGET_FT = 50.0
internal const val RANGE_LIMIT_FT = 5280.0
internal const val AGL_ICON_NEAR_DELTA_FT = 20.0
internal const val FT_TO_METERS = 0.3048
internal const val NEAR_LIMIT_RATIO = 0.90
internal const val NEAR_ALERT_COOLDOWN_MS = 30_000L
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
internal const val DRONE_NAME_LABEL_ANCHOR_Y = 2.05f
internal const val DRONE_STATUS_LABEL_ANCHOR_Y = 3.75f
internal const val LABEL_MAX_ABS_FEET = 1000.0
internal const val DEFAULT_CAMERA_FOV_WIDTH_DEG = 80.0
internal const val OSM_MAX_ZOOM = 19.0
internal const val MAP_CACHE_PREFS_NAME = "map_cache"
internal const val MAP_CACHE_PREWARM_SIG_KEY = "prewarm_signature_v1"
internal const val OSM_TILE_DOWNLOAD_THREADS: Short = 1
internal const val OSM_TILE_DOWNLOAD_MAX_QUEUE: Short = 1000
internal const val TILE_FS_THREADS: Short = 4
internal const val TILE_FS_MAX_QUEUE: Short = 2000
internal const val TILE_IO_ACTIVE_GRACE_MS = 2_000L

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

internal data class DroneMapPoint(
    val designator: String,        // mappedId — display label, may change during a flight
    val remoteId: String,          // stable unique identifier from the Remote ID broadcast
    val lat: Double,
    val lng: Double,
    val altitudeM: Double,
    val timestampMsec: Long,
    val receivedAtMsec: Long? = null,
    val droneSpec: CtDroneSpec? = null
)

// DroneAglState, AtoSeedSource, DroneAltitudeCalibration — defined in DroneAltitudeModels.kt

internal data class DroneComplianceState(
    val aglM: Double?,
    val rangeFromHomeM: Double?,
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

internal fun osmUserAgent(): String =
    "RID2Caltopo v${BuildConfig.VERSION_NAME} (contact: kjtsar@kjt.us)"

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

// SplitMapPane composable - extracted from StreamsScreen.kt (lines 648-2466)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SplitMapPane(
    viewModel: StreamsViewModel,
    modifier: Modifier = Modifier,
    onSingleTapFocus: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val uiScope = rememberCoroutineScope()
    val restoredViewport = viewModel.mapViewportState()
    val baseLayer = viewModel.baseLayer
    var settingsMenuExpanded by remember { mutableStateOf(false) }
    var baseLayerMenuExpanded by remember { mutableStateOf(false) }
    var badTilesMenuExpanded by remember { mutableStateOf(false) }
    var calibrateMenuExpanded by remember { mutableStateOf(false) }
    var showMapFoldersDialog by remember { mutableStateOf(false) }
    val hiddenFolderIds = viewModel.hiddenFolderIds
    val hiddenItemIds = viewModel.hiddenItemIds
    var showBadTilesHowToDialog by remember { mutableStateOf(false) }
    var showOfflinePrepDialog by remember { mutableStateOf(false) }
    var showMutualAidPackageDialog by remember { mutableStateOf(false) }
    var offlinePrepInFlight by remember { mutableStateOf(false) }
    var offlinePrepPreset by remember { mutableStateOf(OFFLINE_PREP_PRESETS[1]) }
    var offlinePrepIncludeDem by remember { mutableStateOf(true) }
    var offlinePrepMaxThroughput by remember { mutableStateOf(false) }
    var offlinePrepAreaMode by remember { mutableStateOf(OfflinePrepAreaMode.Viewport) }
    var offlinePrepBoundaryId by remember { mutableStateOf<String?>(null) }
    var offlinePrepProgress by remember { mutableStateOf(OfflinePrepProgress()) }
    var offlinePrepCancelRequested by remember { mutableStateOf(false) }
    var offlinePrepEstimate by remember { mutableStateOf(OfflinePrepEstimate()) }
    var offlinePrepEstimateRunning by remember { mutableStateOf(false) }
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
    val maximizeThroughputBlockedForOsm = baseLayer == BaseLayerOption.OpenStreetMap
    var predictiveHeadEnabled by remember { mutableStateOf(CaltopoClient.GetPredictiveHeadEnabled()) }
    var autoRemoveBadTiles by remember { mutableStateOf(BadTilePolicy.isAutoRemoveEnabled(context)) }
    var badTileDialogState by remember { mutableStateOf<BadTileDialogState?>(null) }
    var quarantineMatchingHash by remember { mutableStateOf(true) }
    val mapName = viewModel.mapName
    val artifactStoreById = remember { LinkedHashMap<String, JSONObject>() }
    val localTrackPointsByMappedId = remember { mutableStateMapOf<String, MutableList<LocalTrackPoint>>() }
    val managedOverlays = remember { mutableListOf<Overlay>() }
    var artifactOverlayState by remember { mutableStateOf(ArtifactOverlayState()) }
    var lastRenderStats by remember { mutableStateOf("") }
    var lastAlignmentStats by remember { mutableStateOf("") }
    var initialViewportApplied by remember(restoredViewport) { mutableStateOf(restoredViewport != null) }
    var initialViewportArtifactCount by remember { mutableStateOf(-1) }
    val droneMarkerIcon = remember(context) { ContextCompat.getDrawable(context, R.drawable.ic_drone_marker) }
    val symbolMarkerCache = remember { LinkedHashMap<String, Drawable>() }
    val caltopoMarkerCache = remember { mutableStateMapOf<String, Drawable>() }
    val caltopoMarkerPending = remember { HashSet<String>() }
    val unknownSymbolsSeen = remember { LinkedHashSet<String>() }
    val iconCacheService = remember(context) { CaltopoIconCacheService(context) }
    // DemElevationService is owned by the coordinator (created once at ViewModel init).
    val demElevationService = viewModel.altitudeCoordinator.demElevationService
    // Register MapPane as an altitude consumer so the coordinator's update loop stays active.
    DisposableEffect(viewModel) {
        val removeConsumer = viewModel.addAltitudeConsumer()
        onDispose { removeConsumer() }
    }
    val tileCacheWriter = remember(context) { TileDiskCacheWriter(context) }
    val tileMapProvider = remember(context) {
        configureOsmdroid(context)
        TileCacheMapProvider(
            context = context,
            tileSource = OsmStandardTileSource,
            tileWriter = tileCacheWriter
        )
    }
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
    var lastViewportSignature by remember { mutableStateOf<String?>(null) }
    // Auto-download: GeoTIFF tiles already initiated (prevents redundant re-downloads).
    val autoFetchedDemTiles = remember { HashSet<String>() }
    val demAutoFetchClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }
    val renderLatencyKeyByDesignator = remember { mutableStateMapOf<String, String>() }
    val complianceByDesignator = remember { mutableStateMapOf<String, DroneComplianceState>() }
    // Tracks which drone's info-window bubble is open so it can be restored after each overlay rebuild.
    var openBubbleDesignator by remember { mutableStateOf<String?>(null) }
    var lastAlertSeverity by remember { mutableStateOf(AlertSeverity.None) }
    var lastAlertToneAtMs by remember { mutableStateOf(0L) }
    val toneGenerator = remember {
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55)
        } catch (_: Exception) {
            null
        }
    }
    val focusedPath by viewModel.focusedPath.collectAsStateWithLifecycle()
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
            Pair(
                DroneMapPoint(
                    designator = designator,
                    remoteId = state.source?.remoteId ?: designator,
                    lat = lat,
                    lng = lng,
                    altitudeM = altitudeM,
                    timestampMsec = timestampMsec,
                    receivedAtMsec = receivedAtMsec,
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
                        if (notice.intersectsPilotBubble) append("intersects 1 NM operating area")
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
    val offlineBoundaryOptions = remember(artifactOverlayState) {
        buildOfflineBoundaryOptions(artifactOverlayState)
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
    LaunchedEffect(context) {
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
    LaunchedEffect(
        showOfflinePrepDialog,
        offlinePrepAreaMode,
        offlinePrepBoundaryId,
        offlinePrepPreset,
        offlinePrepIncludeDem,
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
            val tileEstimate = estimateTileCountApproximate(
                bounds = estimateBounds,
                minZoom = offlinePrepPreset.minZoom,
                maxZoom = offlinePrepPreset.maxZoom,
                clipBoundary = selectedBoundary
            )
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

    fun calibrateDroneAto50(target: DroneMapPoint) {
        viewModel.altitudeCoordinator.manualCalibrate(target.remoteId, target.altitudeM, target.designator)
    }

    fun selectedTileSource(): org.osmdroid.tileprovider.tilesource.ITileSource {
        return when (baseLayer) {
            BaseLayerOption.OpenStreetMap -> OsmStandardTileSource
            BaseLayerOption.Imagery -> ArcGisWorldImageryTileSource
        }
    }

    fun parseMutualAidPackageExpiry(): Long {
        return runCatching {
            val date = LocalDate.parse(maPackageExpiryDateText.trim(), packageDateFormatter)
            val time = LocalTime.parse(maPackageExpiryTimeText.trim(), packageTimeFormatter)
            LocalDateTime.of(date, time).atZone(packageZoneId).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    val exportMutualAidPackageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val boundary =
            if (offlinePrepAreaMode == OfflinePrepAreaMode.MapBoundary) {
                offlineBoundaryOptions.firstOrNull { it.id == offlinePrepBoundaryId }?.boundary
            } else {
                null
            }
        val prepBounds = boundary?.bounds ?: mapBounds
        if (prepBounds == null) {
            CaltopoClient.ShowToast("Mutual aid package export needs visible map bounds.")
            return@rememberLauncherForActivityResult
        }
        uiScope.launch(Dispatchers.IO) {
            val packageName = buildString {
                append(maPackageIncident.ifBlank { "incident" }.replace(' ', '_'))
                append("_op")
                append(maPackageOpPeriod.ifBlank { "1" })
            }
            val result = MutualAidPackageManager.exportPackage(
                context = context,
                destUri = uri,
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
            withContext(Dispatchers.Main.immediate) {
                CaltopoClient.ShowToast(result.second)
            }
        }
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
        val isOsmDownload = baseLayer == BaseLayerOption.OpenStreetMap
        val maximizeThroughput = offlinePrepMaxThroughput && !isOsmDownload
        // Compute 1° GeoTIFF tile names for the area now (on the main thread, before the IO job).
        val demTileNames = if (includeDem) demTileNamesForBounds(bounds) else emptyList<String>()
        val estimatedTileOps = offlinePrepEstimate.tileEstimate
        val estimatedDemOps = demTileNames.size
        val estimatedTotalOps = (estimatedTileOps + estimatedDemOps).coerceAtLeast(1)
        val tileSource = selectedTileSource()
        offlinePrepJob = uiScope.launch(Dispatchers.IO) {
            val onlineTileSource = tileSource as? OnlineTileSourceBase
            if (onlineTileSource == null) {
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
                    suspend fun processTile(tileIndex: Long) {
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
                                val url = onlineTileSource.getTileURLString(tileIndex)
                                val req = Request.Builder().url(url).build()
                                val call = offlineHttpClient.newCall(req)
                                offlinePrepActiveCalls += call
                                try {
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
                                } finally {
                                    offlinePrepActiveCalls.remove(call)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failureDetail = "ex=${e.javaClass.simpleName} z=$z x=$x y=$y source=${tileSource.name()}"
                                false
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
                        val tileQueue = Channel<Long>(capacity = workerCount * 3)
                        val workers = List(workerCount) {
                            launch {
                                for (tileIndex in tileQueue) {
                                    processTile(tileIndex)
                                }
                            }
                        }
                        forEachTileIndexForBounds(bounds, preset.minZoom, preset.maxZoom, clipBoundary) { tileIndex ->
                            currentCoroutineContext().ensureActive()
                            tileQueue.send(tileIndex)
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
                        val tileQueue = Channel<Long>(capacity = maxWorkers * 4)
                        val demQueue = Channel<String>(capacity = maxWorkers * 3)
                        val tileWorkers = mutableListOf<Job>()
                        val demWorkers = mutableListOf<Job>()

                        val tileProducer = launch {
                            forEachTileIndexForBounds(bounds, preset.minZoom, preset.maxZoom, clipBoundary) { tileIndex ->
                                currentCoroutineContext().ensureActive()
                                tileQueue.send(tileIndex)
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
                                for (tileIndex in tileQueue) {
                                    processTile(tileIndex)
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

    fun hydrateArtifactsFromCaltopoSnapshot(reason: String) {
        val snapshot = CaltopoMap.GetArtifactFeatureSnapshot()
        val shouldReplace = snapshot.isNotEmpty() || artifactStoreById.isEmpty()
        if (!shouldReplace) {
            return
        }
        artifactStoreById.clear()
        snapshot.forEach { feature ->
            val featureId = feature.optString("id")
            if (featureId.isNotBlank()) {
                artifactStoreById[featureId] = feature
                // Auto-hide folders the server marks as not visible, on first encounter.
                // Delegates to ViewModel so the choice persists across navigation.
                val props = feature.optJSONObject("properties")
                if (props?.optString("class") == "Folder") {
                    viewModel.applyCaltopoFolderDefault(featureId, props.optBoolean("visible", true))
                }
            }
        }
        artifactOverlayState = buildArtifactOverlayState(artifactStoreById.values, hiddenFolderIds, hiddenItemIds)
        if (MAP_PANE_VERBOSE_LOGS || snapshot.isNotEmpty() || artifactOverlayState.totalFeatures > 0) {
            if (CTDebugEnabled(MAP_PANE_TAG)) CTDebug(
                MAP_PANE_TAG,
                "Hydrated artifacts from snapshot ($reason): cached=${snapshot.size}, renderable=${artifactOverlayState.totalFeatures}"
            )
        }
    }

    LaunchedEffect(mapName) {
        val persistedViewport = viewModel.mapViewportState()
        hydrateArtifactsFromCaltopoSnapshot("mapName=$mapName")
        localTrackPointsByMappedId.clear()
        lastRenderStats = ""
        lastAlignmentStats = ""
        lastCacheStats = ""
        viewModel.altitudeCoordinator.onMapReconnect()
        renderLatencyKeyByDesignator.clear()
        complianceByDesignator.clear()
        lastAlertSeverity = AlertSeverity.None
        lastAlertToneAtMs = 0L
        initialViewportApplied = persistedViewport != null
        initialViewportArtifactCount = -1
    }

    DisposableEffect(Unit) {
        onDispose {
            tileMapProvider.detach()
            try {
                toneGenerator?.release()
            } catch (_: Exception) {
            }
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
        MapFoldersDialog(
            folders = buildMapFolderUiStates(artifactStoreById),
            hiddenFolderIds = hiddenFolderIds,
            hiddenItemIds = hiddenItemIds,
            onFolderVisibilityChanged = { folderId, visible ->
                if (visible) hiddenFolderIds.remove(folderId) else hiddenFolderIds.add(folderId)
                artifactOverlayState = buildArtifactOverlayState(
                    artifactStoreById.values, hiddenFolderIds, hiddenItemIds)
            },
            onItemVisibilityChanged = { itemId, visible ->
                if (visible) hiddenItemIds.remove(itemId) else hiddenItemIds.add(itemId)
                artifactOverlayState = buildArtifactOverlayState(
                    artifactStoreById.values, hiddenFolderIds, hiddenItemIds)
            },
            onAllItemsToggled = { itemIds, visible ->
                if (visible) hiddenItemIds.removeAll(itemIds.toSet())
                else hiddenItemIds.addAll(itemIds)
                artifactOverlayState = buildArtifactOverlayState(
                    artifactStoreById.values, hiddenFolderIds, hiddenItemIds)
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

    openBubbleDesignator?.let { designator ->
        val point = dronePoints.firstOrNull { it.designator == designator }
        if (point != null) {
            val coordinateDisplayFormat = viewModel.coordinateDisplayFormat
            var coordinateMenuExpanded by remember(designator, coordinateDisplayFormat) { mutableStateOf(false) }
            // AGL, ATO — read from coordinator (same values shown in the map label).
            val bubbleDisplayState = viewModel.droneDisplayStateFor(point.designator)
            val aglFeet  = bubbleDisplayState?.aglFt
            val aglStale = bubbleDisplayState?.aglStale ?: false
            val atoFeet  = bubbleDisplayState?.atoFt
            val myLocation = CaltopoMap.GetMyLocation()
            val rangeFeet = if (myLocation != null &&
                myLocation.latitude.isFinite() &&
                myLocation.longitude.isFinite()
            ) {
                val out = FloatArray(1)
                Location.distanceBetween(
                    myLocation.latitude,
                    myLocation.longitude,
                    point.lat,
                    point.lng,
                    out
                )
                if (out[0].isFinite()) out[0].toDouble() * METERS_TO_FEET else null
            } else {
                null
            }
            val detailLines = buildList {
                add("Location: ${CoordinateFormatter.format(point.lat, point.lng, coordinateDisplayFormat)} (${coordinateDisplayFormat.label})")
                add("AGL: ${aglFeet?.let { "${"%.0f".format(it)}'${if (aglStale) "?" else ""}" } ?: "--"}")
                add("ATO: ${atoFeet?.let { "%.0f'".format(it) } ?: "--"}")
                add("Distance: ${rangeFeet?.let { "%.0f'".format(it) } ?: "--"}")
                val telemetry = point.droneSpec?.lastPositionTelemetry
                telemetry?.aircraftGsKnots?.let { add(String.format(Locale.US, "Speed: %.1f kt", it)) }
                telemetry?.aircraftTrackDeg?.let { add(String.format(Locale.US, "Track: %.1f°", it)) }
                telemetry?.aircraftAltitudeRateFpm?.let { add(String.format(Locale.US, "Climb: %.0f fpm", it)) }
            }
            AlertDialog(
                onDismissRequest = { openBubbleDesignator = null },
                title = { Text(point.designator) },
                text = {
                    Column {
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
                    // Auto-hide folders the server marks as not visible, on first encounter.
                    // Delegates to ViewModel so the choice persists across navigation.
                    val props = feature.optJSONObject("properties")
                    if (props?.optString("class") == "Folder") {
                        viewModel.applyCaltopoFolderDefault(featureId, props.optBoolean("visible", true))
                    }
                }

                artifactOverlayState = buildArtifactOverlayState(artifactStoreById.values, hiddenFolderIds, hiddenItemIds)
                if (CTDebugEnabled(MAP_PANE_TAG)) CTDebug(
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
                val nowWallMsec = System.currentTimeMillis()
                val key = mappedId.ifBlank { "unmapped" }
                val list = localTrackPointsByMappedId.getOrPut(key) { mutableStateListOf() }
                val point = LocalTrackPoint(
                    mappedId = key,
                    lat = lat,
                    lng = lng,
                    altitudeM = altitudeMeters,
                    timestampMsec = timestampMsec,
                    receivedAtMsec = nowWallMsec
                )
                list.add(point)
                if (CTDebugEnabled(ICON_LATENCY_TAG))  CTDebug(
                    ICON_LATENCY_TAG,
                    "track_ingest designator=$key wall=$nowWallMsec droneTs=$timestampMsec " +
                        "lat=${"%.6f".format(Locale.US, lat)} lng=${"%.6f".format(Locale.US, lng)} alt=${"%.1f".format(Locale.US, altitudeMeters)}"
                )
                if (list.size > 500) {
                    list.removeAt(0)
                }
            }
        }
        // Seed localTrackPointsByMappedId from current droneStates so drones already known
        // appear immediately without waiting for the next broadcast notification.
        val seedTimeMs = System.currentTimeMillis()
        viewModel.droneStates.forEach { (key, state) ->
            val seedLat = state.lastLat
            val seedLng = state.lastLng
            if (seedLat.isFinite() && seedLng.isFinite() && !(seedLat == 0.0 && seedLng == 0.0)
                    && state.source.mostRecentMsecTimestamp > 0) {
                val list = localTrackPointsByMappedId.getOrPut(key) { mutableStateListOf() }
                if (list.isEmpty()) {
                    list.add(LocalTrackPoint(
                        mappedId = key,
                        lat = seedLat,
                        lng = seedLng,
                        altitudeM = state.lastAlt,
                        timestampMsec = state.source.mostRecentMsecTimestamp,
                        receivedAtMsec = seedTimeMs
                    ))
                }
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
                configureOsmdroid(context)
                MapView(context).apply {
                    setMultiTouchControls(true)
                    setTileProvider(tileMapProvider)
                    setTileSource(OsmStandardTileSource)
                    setUseDataConnection(true)
                    tileMapProvider.setUseDataConnection(true)
                    setMaxZoomLevel(OSM_MAX_ZOOM)
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
                                viewModel.persistMapViewportState(mapCenter, zoomLevelDouble)
                                return false
                            }

                            override fun onZoom(event: ZoomEvent?): Boolean {
                                viewModel.persistMapViewportState(mapCenter, zoomLevelDouble)
                                return false
                            }
                        }
                    )
                }
            },
            update = { mapView ->
                val uiNowWallMsec = System.currentTimeMillis()
                mapBounds = mapView.boundingBox
                val tileSource = when (baseLayer) {
                    BaseLayerOption.OpenStreetMap -> OsmStandardTileSource
                    BaseLayerOption.Imagery -> ArcGisWorldImageryTileSource
                }
                val maxZoom = if (baseLayer == BaseLayerOption.OpenStreetMap) OSM_MAX_ZOOM else 19.0
                if (mapView.maxZoomLevel != maxZoom) {
                    mapView.setMaxZoomLevel(maxZoom)
                }
                if (mapView.tileProvider.tileSource.name() != tileSource.name()) {
                    mapView.setTileSource(tileSource)
                    tileIoActiveUntilMs = uiNowWallMsec + TILE_IO_ACTIVE_GRACE_MS
                }

                val center = mapView.mapCenter
                val viewportSignature = String.format(
                    Locale.US,
                    "%.5f|%.5f|%.3f|%d|%d|%s",
                    center.latitude,
                    center.longitude,
                    mapView.zoomLevelDouble,
                    mapView.width,
                    mapView.height,
                    tileSource.name()
                )
                if (lastViewportSignature != viewportSignature) {
                    lastViewportSignature = viewportSignature
                    tileIoActiveUntilMs = uiNowWallMsec + TILE_IO_ACTIVE_GRACE_MS
                }
                val suppressLiveMapNetwork = offlinePrepInFlight && baseLayer == BaseLayerOption.OpenStreetMap
                val tileNetworkActive = !suppressLiveMapNetwork && (uiNowWallMsec <= tileIoActiveUntilMs)
                if (mapView.useDataConnection() != tileNetworkActive) {
                    mapView.setUseDataConnection(tileNetworkActive)
                }
                if (tileMapProvider.useDataConnection() != tileNetworkActive) {
                    tileMapProvider.setUseDataConnection(tileNetworkActive)
                }
                tileMapProvider.setCacheLookupEnabled(true)

                if (managedOverlays.isNotEmpty()) {
                    mapView.overlays.removeAll(managedOverlays)
                    managedOverlays.clear()
                }

                val tapOverlay = MapEventsOverlay(
                    object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
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

                artifactOverlayState.polygons.forEach { polygonSpec ->
                    val polygonFill = Polygon(mapView).apply {
                        points = polygonSpec.points
                        title = ""
                        strokeColor = AndroidColor.TRANSPARENT
                        fillColor = polygonSpec.fillColor
                        strokeWidth = 0f
                        setOnClickListener { _, _, _ -> false }
                    }
                    mapView.overlays.add(polygonFill)
                    managedOverlays.add(polygonFill)

                    val polygonBoundary = Polyline(mapView).apply {
                        setPoints(closedPolylinePoints(polygonSpec.points))
                        title = polygonSpec.title
                        color = polygonSpec.strokeColor
                        width = polygonSpec.strokeWidth
                    }
                    mapView.overlays.add(polygonBoundary)
                    managedOverlays.add(polygonBoundary)
                }

                val notamOverlayState = NotamMapOverlayAdapter.build(notamUiState, CaltopoMap.GetMyLocation())
                notamOverlayState.polygons.forEach { polygonSpec ->
                    val polygonFill = Polygon(mapView).apply {
                        points = polygonSpec.points
                        title = ""
                        strokeColor = AndroidColor.TRANSPARENT
                        fillColor = polygonSpec.fillColor
                        strokeWidth = 0f
                        setOnClickListener { _, _, _ -> false }
                    }
                    mapView.overlays.add(polygonFill)
                    managedOverlays.add(polygonFill)

                    val polygonBoundary = Polyline(mapView).apply {
                        setPoints(closedPolylinePoints(polygonSpec.points))
                        title = polygonSpec.title
                        color = polygonSpec.strokeColor
                        width = polygonSpec.strokeWidth
                        polygonSpec.notice?.let { notice ->
                            setOnClickListener { _, _, _ ->
                                selectedNotam = notice
                                true
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
                        color = lineSpec.color
                        width = lineSpec.width
                    }
                    mapView.overlays.add(line)
                    managedOverlays.add(line)
                }

                notamOverlayState.lines.forEach { lineSpec ->
                    val line = Polyline(mapView).apply {
                        setPoints(lineSpec.points)
                        title = lineSpec.title
                        color = lineSpec.color
                        width = lineSpec.width
                        setOnClickListener { _, _, _ ->
                            selectedNotam = lineSpec.notice
                            true
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
                        if (CTDebugEnabled(MAP_PANE_TAG))  CTDebug(MAP_PANE_TAG, "Unknown marker-symbol encountered: '${point.markerSymbol}'")
                    }
                    mapView.overlays.add(marker)
                    managedOverlays.add(marker)
                }

                notamOverlayState.points.forEach { point ->
                    val marker = Marker(mapView).apply {
                        position = point.point
                        icon = buildNotamMarkerIcon(context, point.color)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = point.title
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
                    mapView.overlays.add(marker)
                    managedOverlays.add(marker)
                }

                val myLocation = CaltopoMap.GetMyLocation()
                if (myLocation != null && myLocation.latitude.isFinite() && myLocation.longitude.isFinite()) {
                    val localCacheKey = iconCacheService.cacheKey(LOCAL_DEVICE_SYMBOL, LOCAL_DEVICE_COLOR)
                    val localRemoteIcon = caltopoMarkerCache[localCacheKey]
                    if (localRemoteIcon == null && !caltopoMarkerPending.contains(localCacheKey)) {
                        caltopoMarkerPending.add(localCacheKey)
                        uiScope.launch(Dispatchers.IO) {
                            val loaded = iconCacheService.loadBestAvailableDrawable(
                                resources = context.resources,
                                markerSymbol = LOCAL_DEVICE_SYMBOL,
                                markerColor = LOCAL_DEVICE_COLOR
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
                        icon = (localRemoteIcon?.constantState?.newDrawable(context.resources)?.mutate())
                            ?: markerIconForArtifactSymbol(
                                resources = context.resources,
                                symbol = LOCAL_DEVICE_SYMBOL,
                                colorHex = LOCAL_DEVICE_COLOR,
                                cache = symbolMarkerCache
                            )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        val localDeviceName = R2CActivity.MyDeviceName
                        title = if (localDeviceName.isBlank() || localDeviceName == "<unknown>") {
                            "RID2Caltopo Device"
                        } else {
                            localDeviceName
                        }
                    }
                    mapView.overlays.add(localMarker)
                    managedOverlays.add(localMarker)
                }

                val iconLimitAglM = AGL_LIMIT_FT * FT_TO_METERS
                val nearIconAglM = (AGL_LIMIT_FT - AGL_ICON_NEAR_DELTA_FT) * FT_TO_METERS
                val homeLocation = CaltopoMap.GetMyLocation()
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
                    val labelRangeFeet = if (homeLocation != null && homeLocation.latitude.isFinite() && homeLocation.longitude.isFinite()) {
                        val out = FloatArray(1)
                        Location.distanceBetween(
                            homeLocation.latitude,
                            homeLocation.longitude,
                            renderLat,
                            renderLng,
                            out
                        )
                        if (out[0].isFinite()) out[0].toDouble() * METERS_TO_FEET else null
                    } else {
                        null
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
                        )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        val aglText = labelAglFeet?.let { "${"%.0f".format(it)}'" } ?: "--"
                        val atoText = labelAtoFeet?.let { "${"%.0f".format(it)}'" } ?: "--"
                        val distanceText = labelRangeFeet?.let { "${"%.0f".format(it)}'" } ?: "--"
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
                    mapView.overlays.add(marker)
                    managedOverlays.add(marker)

                    val nameMarker = Marker(mapView).apply {
                        position = GeoPoint(renderLat, renderLng)
                        icon = buildDroneNameLabelDrawable(context.resources, point.designator)
                        setAnchor(Marker.ANCHOR_CENTER, DRONE_NAME_LABEL_ANCHOR_Y)
                        setOnMarkerClickListener { _, _ -> true }
                    }
                    mapView.overlays.add(nameMarker)
                    managedOverlays.add(nameMarker)

                    val aglToken = labelAglFeet
                        ?.takeIf { kotlin.math.abs(it) <= LABEL_MAX_ABS_FEET }
                        ?.let { "${"%.0f".format(it)}${if (labelAglStale) "?" else ""}AGL" } ?: "--AGL"
                    val atoToken = labelAtoFeet
                        ?.takeIf { kotlin.math.abs(it) <= LABEL_MAX_ABS_FEET }
                        ?.let { "%.0fATO".format(it) } ?: "--ATO"
                    val rangeToken = labelRangeFeet?.let { "%.0f".format(it) } ?: "--"
                    val labelText = "$aglToken,$atoToken,$rangeToken"
                    val labelMarker = Marker(mapView).apply {
                        position = GeoPoint(renderLat, renderLng)
                        icon = buildDroneStatusLabelDrawable(context.resources, labelText)
                        setAnchor(Marker.ANCHOR_CENTER, DRONE_STATUS_LABEL_ANCHOR_Y)
                        setOnMarkerClickListener { _, _ -> true }
                    }
                    mapView.overlays.add(labelMarker)
                    managedOverlays.add(labelMarker)
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
                    val rangeM = if (homeLocation != null && homeLocation.latitude.isFinite() && homeLocation.longitude.isFinite()) {
                        val out = FloatArray(1)
                        Location.distanceBetween(
                            homeLocation.latitude,
                            homeLocation.longitude,
                            point.lat,
                            point.lng,
                            out
                        )
                        if (out[0].isFinite()) out[0].toDouble() else null
                    } else {
                        null
                    }
                    val nearAgl  = aglM != null && aglM >= nearAglM
                    val overAgl  = aglM != null && aglM >= limitAglM
                    val nearRange = rangeM != null && rangeM >= nearRangeM
                    val overRange = rangeM != null && rangeM >= limitRangeM
                    complianceByDesignator[point.designator] = DroneComplianceState(
                        aglM = aglM,
                        rangeFromHomeM = rangeM,
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

                val nowMs = System.currentTimeMillis()
                val cooldownMs = if (severity == AlertSeverity.Over) OVER_ALERT_COOLDOWN_MS else NEAR_ALERT_COOLDOWN_MS
                val shouldTone = severity != AlertSeverity.None &&
                    (severity != lastAlertSeverity || nowMs - lastAlertToneAtMs >= cooldownMs)
                if (shouldTone) {
                    val toneType = if (severity == AlertSeverity.Over) {
                        ToneGenerator.TONE_CDMA_HIGH_SS
                    } else {
                        ToneGenerator.TONE_PROP_BEEP
                    }
                    toneGenerator?.startTone(toneType, if (severity == AlertSeverity.Over) 250 else 140)
                    lastAlertToneAtMs = nowMs
                    if (CTDebugEnabled(MAP_PANE_TAG)) CTDebug(MAP_PANE_TAG, "Compliance alert tone: severity=$severity")
                }
                if (severity != lastAlertSeverity) {
                    lastAlertSeverity = severity
                    if (CTDebugEnabled(MAP_PANE_TAG)) CTDebug(MAP_PANE_TAG, "Compliance alert state changed: severity=$severity")
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

                val shouldApplyInitialViewport =
                    !initialViewportApplied ||
                        (
                            initialViewportArtifactCount == 0 &&
                                artifactOverlayState.totalFeatures > 0 &&
                                viewModel.mapViewportState() == null
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
                            viewModel.persistMapViewportState(mapView.mapCenter, mapView.zoomLevelDouble)
                            if (MAP_PANE_VERBOSE_LOGS) {
                                CTDebug(
                                    MAP_PANE_TAG,
                                    "Initial viewport: myLocation=${myLocation != null}, artifactPts=${artifactPoints.size}, mode=bounds"
                                )
                            }
                        }

                        myLocation != null -> {
                            mapView.controller.setCenter(GeoPoint(myLocation.latitude, myLocation.longitude))
                            mapView.controller.setZoom(15.0)
                            viewModel.persistMapViewportState(mapView.mapCenter, mapView.zoomLevelDouble)
                            if (MAP_PANE_VERBOSE_LOGS) {
                                CTDebug(MAP_PANE_TAG, "Initial viewport: centered on MyLocation.")
                            }
                        }

                        focusPoint != null -> {
                            mapView.controller.setCenter(GeoPoint(focusPoint.lat, focusPoint.lng))
                            mapView.controller.setZoom(14.0)
                            viewModel.persistMapViewportState(mapView.mapCenter, mapView.zoomLevelDouble)
                            if (MAP_PANE_VERBOSE_LOGS) {
                                CTDebug(MAP_PANE_TAG, "Initial viewport: fallback to focused drone point.")
                            }
                        }
                    }
                    initialViewportApplied = true
                    initialViewportArtifactCount = artifactOverlayState.totalFeatures
                }

                proximityMapFocusTarget?.let { focusTarget ->
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
                            mapView.controller.setZoom(OSM_MAX_ZOOM)
                        } else {
                            mapView.zoomToBoundingBox(boundingBoxFromPoints(focusPoints), true, 96)
                        }
                        viewModel.persistMapViewportState(mapView.mapCenter, mapView.zoomLevelDouble)
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
                mapView.invalidate()
            }
        )

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
                    baseLayerMenuExpanded = false
                    badTilesMenuExpanded = false
                    calibrateMenuExpanded = false
                }
            ) {
                DropdownMenuItem(
                    text = { Text("Base: ${baseLayer.label}") },
                    onClick = {
                        settingsMenuExpanded = false
                        baseLayerMenuExpanded = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Calibrate 50' ATO...") },
                    onClick = {
                        settingsMenuExpanded = false
                        calibrateMenuExpanded = true
                    },
                    enabled = dronePoints.isNotEmpty()
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
                    text = { Text("Bad Tiles...") },
                    onClick = {
                        settingsMenuExpanded = false
                        badTilesMenuExpanded = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Download Map...") },
                    onClick = {
                        settingsMenuExpanded = false
                        showOfflinePrepDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Map Folders...") },
                    onClick = {
                        settingsMenuExpanded = false
                        showMapFoldersDialog = true
                    },
                    enabled = buildMapFolderUiStates(artifactStoreById).isNotEmpty()
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
            }
            DropdownMenu(
                expanded = calibrateMenuExpanded,
                onDismissRequest = { calibrateMenuExpanded = false }
            ) {
                dronePoints
                    .sortedBy { it.designator }
                    .forEach { point ->
                        DropdownMenuItem(
                            text = { Text(point.designator) },
                            onClick = {
                                calibrateDroneAto50(point)
                                calibrateMenuExpanded = false
                            }
                        )
                    }
            }
        }

        if (showMutualAidPackageDialog) {
            val parsedExpiry = parseMutualAidPackageExpiry()
            AlertDialog(
                onDismissRequest = { showMutualAidPackageDialog = false },
                title = { Text("Export MA Config") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            "Specify the incident details and expiry to embed in the exported MA config package.",
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
                            parsedExpiry > System.currentTimeMillis(),
                        onClick = {
                            showMutualAidPackageDialog = false
                            val suggestedName = buildString {
                                append(maPackageIncident.ifBlank { "incident" }.replace(' ', '_'))
                                append("_op")
                                append(maPackageOpPeriod.ifBlank { "1" })
                                append("_mutual_aid_package.zip")
                            }
                            exportMutualAidPackageLauncher.launch(suggestedName)
                        }
                    ) { Text("Choose File") }
                },
                dismissButton = {
                    TextButton(onClick = { showMutualAidPackageDialog = false }) {
                        Text("Cancel")
                    }
                }
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
                        modifier = Modifier
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState()),
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
                                checked = offlinePrepMaxThroughput,
                                onCheckedChange = { if (!offlinePrepInFlight) offlinePrepMaxThroughput = it },
                                enabled = !offlinePrepInFlight && !maximizeThroughputBlockedForOsm
                            )
                            Text(if (maximizeThroughputBlockedForOsm) "Maximize throughput (Imagery only)" else "Maximize throughput")
                        }
                        if (maximizeThroughputBlockedForOsm) {
                            Text(
                                "OpenStreetMap downloads are allowed, but Maximize throughput is disabled to comply with OSM tile server usage policy.",
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            if (offlinePrepEstimateRunning || !offlinePrepEstimate.ready) {
                                "Estimate: calculating..."
                            } else {
                                "Estimate: " +
                                    "tiles=${offlinePrepEstimate.tileEstimate} (~${"%.1f".format(Locale.US, offlinePrepEstimate.estimatedTileCacheMb)} MB), " +
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
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                if (!CaltopoClient.HasMutualAidTemplate()) {
                                    CaltopoClient.ShowToast("Load ct_mutual_aid_credentials before exporting MA config.")
                                    return@TextButton
                                }
                                maPackageIncident = CaltopoClient.GetIncident()
                                maPackageOpPeriod = CaltopoClient.GetOpPeriod()
                                maPackageMapId = CaltopoMap.GetMapId()
                                maPackageMapTitle = CaltopoMap.GetMapName()
                                val nextMidnight = LocalDateTime.ofInstant(
                                    Instant.ofEpochMilli(MutualAidProfileManager.defaultExpiryAtNextMidnight()),
                                    packageZoneId
                                )
                                maPackageExpiryDateText = nextMidnight.format(packageDateFormatter)
                                maPackageExpiryTimeText = nextMidnight.format(packageTimeFormatter)
                                showMutualAidPackageDialog = true
                            },
                            enabled = !offlinePrepInFlight && (mapBounds != null || selectedBoundary != null)
                        ) { Text("Export MA Config") }
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
        }
    }
}
private fun buildMapFolderUiStates(features: Map<String, JSONObject>): List<MapFolderUiState> {
    val folderItems = mutableMapOf<String, MutableList<MapItemUiState>>()
    val folderMeta = mutableMapOf<String, Pair<String, Boolean>>()  // id -> (title, visible)
    for (feature in features.values) {
        val props = feature.optJSONObject("properties") ?: continue
        val id = feature.optString("id").takeIf { it.isNotBlank() } ?: continue
        val title = props.optString("title").ifBlank { id }
        val className = props.optString("class")
        if (className == "Folder") {
            folderMeta[id] = Pair(title, props.optBoolean("visible", true))
        } else {
            val folderId = props.optString("folderId").takeIf { it.isNotBlank() } ?: continue
            folderItems.getOrPut(folderId) { mutableListOf() }.add(MapItemUiState(id, title))
        }
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

private fun buildArtifactOverlayState(
    features: Collection<JSONObject>,
    hiddenFolderIds: Set<String> = emptySet(),
    hiddenItemIds: Set<String> = emptySet()
): ArtifactOverlayState {
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
        val folderId = properties?.optString("folderId").orEmpty()
        if (folderId.isNotBlank() && folderId in hiddenFolderIds) continue
        if (featureId.isNotBlank() && featureId in hiddenItemIds) continue
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
    val scaledDensity = resources.displayMetrics.scaledDensity
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
    val scaledDensity = resources.displayMetrics.scaledDensity
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
    headingDeg: Double?
): Drawable? {
    if (baseIcon == null) return null
    val density = resources.displayMetrics.density
    val icon = baseIcon.constantState?.newDrawable(resources)?.mutate() ?: baseIcon.mutate()
    if (tint != null) {
        icon.setTint(tint)
    } else {
        icon.clearColorFilter()
    }

    val iconW = if (icon.intrinsicWidth > 0) icon.intrinsicWidth else (18f * density).toInt()
    val iconH = if (icon.intrinsicHeight > 0) icon.intrinsicHeight else (18f * density).toInt()
    val overlayReach = (18f * density).toInt()
    val pad = maxOf((4f * density).toInt(), overlayReach)
    val width = iconW + pad * 2
    val height = iconH + pad * 2
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = width / 2f
    val cy = height / 2f
    val radius = (maxOf(iconW, iconH) / 2f) + (2.5f * density)
    val headingStart = radius + (0.5f * density)
    val headingTip = radius + (9.0f * density)
    val headingPointerLen = 3.8f * density
    val cameraStart = radius + (0.7f * density)
    val cameraEnd = radius + (9.0f * density)

    val haloFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#CCFFFFFF")
        style = Paint.Style.FILL
    }
    val haloStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#99000000")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    val overlayHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#B3000000")
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#FFF8E1")
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val cameraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#7FDBFF")
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
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
