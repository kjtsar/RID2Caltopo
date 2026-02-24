package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import android.graphics.drawable.Drawable
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.IRegisterReceiver
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.MapTileRequestState
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck
import org.osmdroid.tileprovider.modules.MapTileApproximater
import org.osmdroid.tileprovider.modules.MapTileAssetsProvider
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider
import org.osmdroid.tileprovider.modules.MapTileFileStorageProviderBase
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.modules.NetworkAvailabliltyCheck
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.MapTileAreaBorderComputer
import org.osmdroid.util.MapTileAreaZoomComputer
import org.osmdroid.util.MapTileIndex
import java.util.concurrent.atomic.AtomicReference

class TileCacheMapProvider private constructor(
    context: Context,
    tileSource: ITileSource,
    private val tileWriter: IFilesystemCache,
    private val registerReceiver: SimpleRegisterReceiver
) : MapTileProviderArray(tileSource, registerReceiver) {
    constructor(
        context: Context,
        tileSource: ITileSource = TileSourceFactory.DEFAULT_TILE_SOURCE,
        tileWriter: IFilesystemCache
    ) : this(context, tileSource, tileWriter, SimpleRegisterReceiver(context))

    private val networkAvailabilityCheck: INetworkAvailablityCheck = NetworkAvailabliltyCheck(context)
    private val cacheProvider: TileCacheStorageProvider
    private val downloaderProvider: MapTileDownloader
    private val approximater: MapTileApproximater

    init {
        val assetsProvider = MapTileAssetsProvider(
            registerReceiver,
            context.assets,
            tileSource
        )
        cacheProvider = TileCacheStorageProvider(registerReceiver, tileSource, tileWriter)
        val archiveProvider = MapTileFileArchiveProvider(registerReceiver, tileSource)

        mTileProviderList.add(assetsProvider)
        mTileProviderList.add(cacheProvider)
        mTileProviderList.add(archiveProvider)

        approximater = MapTileApproximater().apply {
            addProvider(assetsProvider)
            addProvider(cacheProvider)
            addProvider(archiveProvider)
        }
        mTileProviderList.add(approximater)

        downloaderProvider = MapTileDownloader(
            tileSource,
            tileWriter,
            networkAvailabilityCheck,
            Configuration.getInstance().tileDownloadThreads.toInt(),
            Configuration.getInstance().tileDownloadMaxQueueSize.toInt()
        )
        mTileProviderList.add(downloaderProvider)

        getTileCache().protectedTileComputers.add(MapTileAreaZoomComputer(-1))
        getTileCache().protectedTileComputers.add(MapTileAreaBorderComputer(1))
        getTileCache().setAutoEnsureCapacity(false)
        getTileCache().setStressedMemory(false)

        getTileCache().preCache.addProvider(assetsProvider)
        getTileCache().preCache.addProvider(cacheProvider)
        getTileCache().preCache.addProvider(archiveProvider)
        getTileCache().preCache.addProvider(downloaderProvider)
        getTileCache().protectedTileContainers.add(this)

        setOfflineFirst(true)
    }

    override fun getTileWriter(): IFilesystemCache = tileWriter

    override fun detach() {
        tileWriter.onDetach()
        super.detach()
    }

    override fun isDowngradedMode(pMapTileIndex: Long): Boolean {
        if (!networkAvailabilityCheck.networkAvailable || !useDataConnection()) {
            return true
        }
        var zoomMin = -1
        var zoomMax = -1
        for (provider in mTileProviderList) {
            if (provider.usesDataConnection) {
                val min = provider.minimumZoomLevel
                val max = provider.maximumZoomLevel
                zoomMin = if (zoomMin == -1 || zoomMin > min) min else zoomMin
                zoomMax = if (zoomMax == -1 || zoomMax < max) max else zoomMax
            }
        }
        if (zoomMin == -1 || zoomMax == -1) return true
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        return zoom < zoomMin || zoom > zoomMax
    }

    fun setOfflineFirst(offlineFirst: Boolean): Boolean {
        var downloaderIndex = -1
        var approximationIndex = -1
        mTileProviderList.forEachIndexed { index, provider ->
            if (provider === downloaderProvider) downloaderIndex = index
            if (provider === approximater) approximationIndex = index
        }
        if (downloaderIndex == -1 || approximationIndex == -1) return false
        if (approximationIndex < downloaderIndex && offlineFirst) return true
        if (approximationIndex > downloaderIndex && !offlineFirst) return true
        mTileProviderList[downloaderIndex] = approximater
        mTileProviderList[approximationIndex] = downloaderProvider
        return true
    }
}

private class TileCacheStorageProvider(
    pRegisterReceiver: IRegisterReceiver,
    pTileSource: ITileSource,
    private val tileWriter: IFilesystemCache
) : MapTileFileStorageProviderBase(
    pRegisterReceiver,
    Configuration.getInstance().tileFileSystemThreads.toInt(),
    Configuration.getInstance().tileFileSystemMaxQueueSize.toInt()
) {
    private val tileSourceRef = AtomicReference(pTileSource)
    private val tileLoader = LocalTileLoader()

    override fun getUsesDataConnection(): Boolean = false

    override fun getName(): String = "R2C Tile Cache Provider"

    override fun getThreadGroupName(): String = "r2cTileCache"

    override fun getTileLoader(): TileLoader = tileLoader

    override fun getMinimumZoomLevel(): Int =
        tileSourceRef.get()?.minimumZoomLevel ?: org.osmdroid.tileprovider.constants.OpenStreetMapTileProviderConstants.MINIMUM_ZOOMLEVEL

    override fun getMaximumZoomLevel(): Int =
        tileSourceRef.get()?.maximumZoomLevel ?: org.osmdroid.util.TileSystem.getMaximumZoomLevel()

    override fun setTileSource(tileSource: ITileSource) {
        tileSourceRef.set(tileSource)
    }

    inner class LocalTileLoader : TileLoader() {
        override fun loadTile(pMapTileIndex: Long): Drawable? {
            val source = tileSourceRef.get() ?: return null
            return tileWriter.loadTile(source, pMapTileIndex)
        }

        override fun tileLoaded(state: MapTileRequestState, drawable: Drawable) {
            removeTileFromQueues(state.mapTile)
            state.callback.mapTileRequestCompleted(state, drawable)
        }
    }
}
