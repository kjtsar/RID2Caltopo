import CryptoKit
import Foundation
import MapKit
import R2CCore
import SwiftUI
import UIKit

enum AppleMapCachePaths {
    static var root: URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("RID2Caltopo/MapTiles", isDirectory: true)
    }

    static var demRoot: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("RID2Caltopo/DEM", isDirectory: true)
    }

    static func tile(_ tile: OperationalOfflineTile, layerKey: String, fileExtension: String) -> URL {
        root.appendingPathComponent(layerKey, isDirectory: true)
            .appendingPathComponent(String(tile.zoom), isDirectory: true)
            .appendingPathComponent(String(tile.x), isDirectory: true)
            .appendingPathComponent("\(tile.y).\(fileExtension)")
    }
}

enum AppleMapTileRequest {
    static func contourURL(zoom: Int, x: Int, y: Int) -> URL {
        let halfWorld = 20_037_508.342789244
        let span = halfWorld * 2 / pow(2, Double(zoom))
        let minX = -halfWorld + Double(x) * span
        let maxX = minX + span
        let maxY = halfWorld - Double(y) * span
        let minY = maxY - span
        var components = URLComponents(string: "https://carto.nationalmap.gov/arcgis/rest/services/contours/MapServer/export")!
        components.queryItems = [
            .init(name: "bbox", value: String(format: "%.6f,%.6f,%.6f,%.6f", minX, minY, maxX, maxY)),
            .init(name: "bboxSR", value: "3857"), .init(name: "imageSR", value: "3857"),
            .init(name: "size", value: "256,256"), .init(name: "format", value: "png32"),
            .init(name: "transparent", value: "true"), .init(name: "f", value: "image"),
        ]
        return components.url!
    }
}

enum AppleBadTilePolicy {
    private static let hashesKey = "map.badTileHashes"

    static func hashes(defaults: UserDefaults = .standard) -> Set<String> {
        Set(defaults.stringArray(forKey: hashesKey) ?? [])
    }

    static func isBlocked(_ data: Data, defaults: UserDefaults = .standard) -> Bool {
        hashes(defaults: defaults).contains(AppleMapOfflineManager.hash(data))
    }

    static func record(_ data: Data, defaults: UserDefaults = .standard) {
        guard !data.isEmpty else { return }
        record(hash: AppleMapOfflineManager.hash(data), defaults: defaults)
    }

    static func record(hash: String, defaults: UserDefaults = .standard) {
        guard !hash.isEmpty else { return }
        var values = hashes(defaults: defaults)
        values.insert(hash)
        defaults.set(values.sorted(), forKey: hashesKey)
    }

    static func clear(defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: hashesKey)
    }
}

struct AppleCachedMapTileSelection: Identifiable {
    let zoom: Int
    let x: Int
    let y: Int
    let hash: String
    let url: URL

    var id: String { "\(zoom)/\(x)/\(y)" }
}

@MainActor
final class AppleMapOfflineManager: ObservableObject {
    static let shared = AppleMapOfflineManager()

    private struct DEMDownload: Hashable {
        let url: URL
        let fileName: String
        let expectedBytes: Int64?
    }
    struct ProgressState: Equatable {
        var phase = "Idle"
        var completed = 0
        var total = 0
        var tileCompleted = 0
        var tileTotal = 0
        var tileCacheHits = 0
        var tileDownloaded = 0
        var tileFailed = 0
        var demCompleted = 0
        var demTotal = 0
        var demCacheHits = 0
        var demDownloaded = 0
        var demFailed = 0
        var startedAt = Date()

        var fraction: Double { total > 0 ? Double(completed) / Double(total) : 0 }
        var cacheHits: Int { tileCacheHits + demCacheHits }
        var downloaded: Int { tileDownloaded + demDownloaded }
        var failed: Int { tileFailed + demFailed }
        var operationsPerSecond: Double {
            guard completed > 0 else { return 0 }
            return Double(completed) / max(0.001, Date().timeIntervalSince(startedAt))
        }
        var etaSeconds: Int? {
            let rate = operationsPerSecond
            guard rate > 0.05 else { return nil }
            return Int(ceil(Double(max(0, total - completed)) / rate))
        }
    }

    struct CacheStats: Equatable {
        var bytes: Int64 = 0
        var files = 0
        var oldest: Date?
    }

    @Published private(set) var progress = ProgressState()
    @Published private(set) var cacheStats = CacheStats()
    @Published private(set) var status = "Ready"
    @Published private(set) var isRunning = false
    @Published private(set) var activeSelectionDescription = ""
    @Published var maximumCacheGB: Double
    @Published var maximumTileAgeDays: Int
    @Published var autoRemoveBadTiles: Bool

    private let defaults: UserDefaults
    private var downloadTask: Task<Void, Never>?

    var downloadMenuStatus: String? {
        Self.downloadMenuStatus(isRunning: isRunning, progress: progress)
    }

    nonisolated static func downloadMenuStatus(isRunning: Bool, progress: ProgressState) -> String? {
        guard isRunning else { return nil }
        guard progress.total > 0 else { return progress.phase }
        let percent = Int((progress.fraction * 100).rounded(.down))
        return "\(percent)%"
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        maximumCacheGB = max(0.1, min(64, defaults.object(forKey: "map.maximumCacheGB") as? Double ?? 1))
        maximumTileAgeDays = max(1, min(3_650, defaults.object(forKey: "map.maximumTileAgeDays") as? Int ?? 365))
        autoRemoveBadTiles = defaults.object(forKey: "map.autoRemoveBadTiles") as? Bool ?? true
        refreshStats()
    }

    var badTileCount: Int { badHashes.count }

    func estimate(
        bounds: OperationalMapBounds,
        preset: OperationalOfflinePreset,
        includeContours: Bool,
        includeDEM: Bool,
        demResolution: OperationalDEMResolution = .standard30m
    ) -> (tiles: Int, dem: Int, bytes: Int64) {
        let tiles = OperationalOfflineMapPlanner.tileCount(
            bounds: bounds,
            minimumZoom: preset.minimumZoom,
            maximumZoom: preset.maximumZoom
        )
        let dem = includeDEM ? OperationalOfflineMapPlanner.estimatedDEMTileCount(bounds: bounds, resolution: demResolution) : 0
        let mapBytes = OperationalOfflineMapPlanner.estimatedBytes(
            tileCount: tiles,
            includeContours: includeContours,
            demTileCount: 0
        )
        let demBytes = includeDEM
            ? OperationalOfflineMapPlanner.estimatedDEMBytes(bounds: bounds, resolution: demResolution)
            : 0
        return (tiles, dem, mapBytes + demBytes)
    }

    func start(
        bounds: OperationalMapBounds,
        preset: OperationalOfflinePreset,
        baseLayer: OperationalMapBaseLayer,
        includeContours: Bool,
        includeDEM: Bool,
        demResolution: OperationalDEMResolution = .standard30m,
        selectionDescription: String = "Selected map area"
    ) {
        guard !isRunning else { return }
        guard let tiles = OperationalOfflineMapPlanner.tiles(
            bounds: bounds,
            minimumZoom: preset.minimumZoom,
            maximumZoom: preset.maximumZoom
        ) else {
            status = "Selection exceeds the 250,000-tile safety limit. Choose a smaller area or preset."
            return
        }
        let estimatedDEMCount = includeDEM
            ? OperationalOfflineMapPlanner.estimatedDEMTileCount(bounds: bounds, resolution: demResolution)
            : 0
        isRunning = true
        activeSelectionDescription = selectionDescription
        let tileOperationCount = tiles.count * (includeContours ? 2 : 1)
        let operationCount = tileOperationCount + estimatedDEMCount
        progress = ProgressState(
            phase: "Preparing map tiles",
            completed: 0,
            total: operationCount,
            tileCompleted: 0,
            tileTotal: tileOperationCount,
            demCompleted: 0,
            demTotal: estimatedDEMCount,
            startedAt: Date()
        )
        status = "Preparing \(operationCount) offline items"
        downloadTask = Task { [weak self] in
            guard let self else { return }
            let demDownloads: [DEMDownload]
            do {
                demDownloads = includeDEM
                    ? try await self.resolveDEMDownloads(bounds: bounds, resolution: demResolution)
                    : []
            } catch {
                self.isRunning = false
                self.progress.phase = "Failed"
                self.status = "DEM planning failed: \(error.localizedDescription)"
                self.downloadTask = nil
                return
            }
            self.progress.demTotal = demDownloads.count
            self.progress.total = tileOperationCount + demDownloads.count
            for tile in tiles {
                guard !Task.isCancelled else { break }
                await self.fetchTile(tile, baseLayer: baseLayer)
                if includeContours, !Task.isCancelled { await self.fetchContour(tile) }
            }
            if !demDownloads.isEmpty, !Task.isCancelled {
                self.progress.phase = "Preparing DEM tiles"
            }
            for download in demDownloads where !Task.isCancelled {
                await self.fetchDEM(download)
            }
            let cancelled = Task.isCancelled
            self.isRunning = false
            self.progress.phase = cancelled
                ? "Cancelled"
                : (self.progress.failed > 0 ? "Complete with failures" : "Complete")
            self.status = cancelled
                ? "Offline preparation cancelled"
                : "Offline preparation complete: \(self.progress.downloaded) downloaded, \(self.progress.cacheHits) cached, \(self.progress.failed) failed"
            self.downloadTask = nil
            self.refreshStats()
            if !cancelled { self.runMaintenance() }
        }
    }

    func cancel() {
        progress.phase = "Cancelling"
        downloadTask?.cancel()
    }

    func saveSettings() {
        maximumCacheGB = max(0.1, min(64, maximumCacheGB))
        maximumTileAgeDays = max(1, min(3_650, maximumTileAgeDays))
        defaults.set(maximumCacheGB, forKey: "map.maximumCacheGB")
        defaults.set(maximumTileAgeDays, forKey: "map.maximumTileAgeDays")
        defaults.set(autoRemoveBadTiles, forKey: "map.autoRemoveBadTiles")
        status = "Map cache settings saved"
    }

    func clearBadTileFlags() {
        AppleBadTilePolicy.clear(defaults: defaults)
        objectWillChange.send()
        status = "Bad tile flags cleared"
    }

    func cachedTileSelection(
        zoom: Int,
        x: Int,
        y: Int,
        baseLayer: OperationalMapBaseLayer
    ) async -> AppleCachedMapTileSelection? {
        let tile = OperationalOfflineTile(zoom: zoom, x: x, y: y)
        let url = AppleMapCachePaths.tile(
            tile,
            layerKey: baseLayer.cacheKey,
            fileExtension: baseLayer.fileExtension
        )
        guard let data = await Task.detached(priority: .userInitiated, operation: {
            try? Data(contentsOf: url)
        }).value else { return nil }
        return AppleCachedMapTileSelection(
            zoom: zoom,
            x: x,
            y: y,
            hash: Self.hash(data),
            url: url
        )
    }

    func removeCachedTile(_ selection: AppleCachedMapTileSelection, quarantineMatchingHash: Bool) {
        do {
            try FileManager.default.removeItem(at: selection.url)
            if quarantineMatchingHash {
                AppleBadTilePolicy.record(hash: selection.hash, defaults: defaults)
                status = "Tile removed and hash quarantined"
            } else {
                status = "Tile removed from cache"
            }
            refreshStats()
        } catch {
            status = "Bad tile removal failed: \(error.localizedDescription)"
        }
    }

    func runMaintenance() {
        saveSettings()
        let maximumBytes = Int64(maximumCacheGB * 1_000_000_000)
        let cutoff = Date().addingTimeInterval(-Double(maximumTileAgeDays) * 86_400)
        status = "Maintaining map cache…"
        Task { [weak self] in
            let result = await Task.detached(priority: .utility) {
                Self.maintainCache(maximumBytes: maximumBytes, cutoff: cutoff)
            }.value
            guard let self else { return }
            self.status = "Cache maintenance removed \(result.removedFiles) files (\(Self.formatBytes(result.removedBytes)))"
            self.refreshStats()
        }
    }

    func refreshStats() {
        Task { [weak self] in
            let stats = await Task.detached(priority: .utility) { Self.scanCache() }.value
            self?.cacheStats = stats
        }
    }

    func exportBadTileHashes() -> URL? {
        let destination = FileManager.default.temporaryDirectory.appendingPathComponent("RID2Caltopo-bad-tile-hashes.txt")
        let lines = ["# RID2Caltopo bad tile hashes", "# count=\(badHashes.count)"] + badHashes.sorted()
        do {
            try (lines.joined(separator: "\n") + "\n").write(to: destination, atomically: true, encoding: .utf8)
            return destination
        } catch {
            status = "Bad tile hash export failed: \(error.localizedDescription)"
            return nil
        }
    }

    static func formatBytes(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    static func formatDuration(_ seconds: Int?) -> String {
        guard let seconds else { return "--:--" }
        let clamped = max(0, seconds)
        let hours = clamped / 3_600
        let minutes = (clamped % 3_600) / 60
        let remainder = clamped % 60
        return hours > 0
            ? String(format: "%d:%02d:%02d", hours, minutes, remainder)
            : String(format: "%02d:%02d", minutes, remainder)
    }

    nonisolated static func dataIsUsableTile(_ data: Data) -> Bool {
        data.count > 100 && UIImage(data: data) != nil
    }

    nonisolated static func cachedTileIsUsable(
        at url: URL,
        blockedHashes: Set<String>
    ) -> Bool {
        guard let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
              size > 100
        else { return false }
        // Both MapPane and the offline downloader validate image data before
        // committing it to this app-owned cache. Avoid re-reading and decoding
        // every known-good file during each preparation pass. Hashing remains
        // necessary when the operator has quarantined matching tile payloads.
        guard !blockedHashes.isEmpty else { return true }
        guard let data = try? Data(contentsOf: url) else { return false }
        return !blockedHashes.contains(hash(data))
    }

    nonisolated static func hash(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private var badHashes: Set<String> {
        AppleBadTilePolicy.hashes(defaults: defaults)
    }

    private func fetchTile(_ tile: OperationalOfflineTile, baseLayer: OperationalMapBaseLayer) async {
        let destination = AppleMapCachePaths.tile(tile, layerKey: baseLayer.cacheKey, fileExtension: baseLayer.fileExtension)
        guard let url = baseLayer.tileURL(zoom: tile.zoom, x: tile.x, y: tile.y) else {
            recordFailure(kind: .tile)
            return
        }
        await fetchImage(url: url, destination: destination)
    }

    private func fetchContour(_ tile: OperationalOfflineTile) async {
        await fetchImage(
            url: AppleMapTileRequest.contourURL(zoom: tile.zoom, x: tile.x, y: tile.y),
            destination: AppleMapCachePaths.tile(tile, layerKey: "usgsContours", fileExtension: "png")
        )
    }

    private func fetchImage(url: URL, destination: URL) async {
        let blockedHashes = badHashes
        let cached = await Task.detached(priority: .utility) {
            Self.cachedTileIsUsable(at: destination, blockedHashes: blockedHashes)
        }.value
        if cached {
            recordCacheHit(kind: .tile)
            return
        }
        progress.phase = "Downloading map tiles"
        do {
            var request = URLRequest(url: url)
            request.timeoutInterval = 20
            request.setValue("RID2Caltopo/Apple (contact: kjt@uas4sar.com)", forHTTPHeaderField: "User-Agent")
            let (data, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200, Self.dataIsUsableTile(data) else {
                recordBadTile(data)
                recordFailure(kind: .tile)
                return
            }
            let hash = Self.hash(data)
            guard !badHashes.contains(hash) else {
                recordFailure(kind: .tile)
                return
            }
            try await Task.detached(priority: .utility) {
                try FileManager.default.createDirectory(
                    at: destination.deletingLastPathComponent(),
                    withIntermediateDirectories: true
                )
                try data.write(to: destination, options: .atomic)
            }.value
            recordDownload(kind: .tile)
        } catch is CancellationError {
            return
        } catch {
            AppleLog.warning("MapOffline", "Tile download failed \(url.absoluteString): \(error.localizedDescription)")
            recordFailure(kind: .tile)
        }
    }

    private func resolveDEMDownloads(
        bounds: OperationalMapBounds,
        resolution: OperationalDEMResolution
    ) async throws -> [DEMDownload] {
        if resolution != .maximum1m {
            let product = resolution == .enhanced10m ? "13" : "1"
            return OperationalOfflineMapPlanner.demTileNames(bounds: bounds).compactMap { name in
                let fileName = "USGS_\(product)_\(name).tif"
                guard let url = URL(string: "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/\(product)/TIFF/current/\(name)/\(fileName)") else { return nil }
                return DEMDownload(url: url, fileName: fileName, expectedBytes: nil)
            }
        }

        // Preserve complete 10 m coverage underneath project-based 1 m tiles. The terrain
        // sampler selects the finest valid overlap and falls through on 1 m NoData cells.
        var downloads: [DEMDownload] = OperationalOfflineMapPlanner.demTileNames(bounds: bounds).compactMap { name in
            let fileName = "USGS_13_\(name).tif"
            guard let url = URL(string: "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/13/TIFF/current/\(name)/\(fileName)") else { return nil }
            return DEMDownload(url: url, fileName: fileName, expectedBytes: nil)
        }
        var offset = 0
        repeat {
            var components = URLComponents(string: "https://tnmaccess.nationalmap.gov/api/v1/products")!
            components.queryItems = [
                .init(name: "bbox", value: "\(bounds.west),\(bounds.south),\(bounds.east),\(bounds.north)"),
                .init(name: "prodFormats", value: "GeoTIFF"),
                .init(name: "outputFormat", value: "JSON"),
                .init(name: "datasets", value: "Digital Elevation Model (DEM) 1 meter"),
                .init(name: "max", value: "100"), .init(name: "offset", value: String(offset)),
            ]
            let (data, response) = try await URLSession.shared.data(from: components.url!)
            guard (response as? HTTPURLResponse)?.statusCode == 200,
                  let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let items = object["items"] as? [[String: Any]]
            else { throw URLError(.badServerResponse) }
            for item in items {
                guard let text = item["downloadURL"] as? String, let url = URL(string: text) else { continue }
                let bytes = (item["sizeInBytes"] as? NSNumber)?.int64Value
                let fileName: String
                if let box = item["boundingBox"] as? [String: Any],
                   let minX = (box["minX"] as? NSNumber)?.doubleValue,
                   let maxX = (box["maxX"] as? NSNumber)?.doubleValue,
                   let minY = (box["minY"] as? NSNumber)?.doubleValue,
                   let maxY = (box["maxY"] as? NSNumber)?.doubleValue {
                    fileName = "R2C_1M_\(Int64((minY * 100_000).rounded()))_\(Int64((maxY * 100_000).rounded()))_"
                        + "\(Int64((minX * 100_000).rounded()))_\(Int64((maxX * 100_000).rounded()))_\(url.lastPathComponent)"
                } else {
                    fileName = url.lastPathComponent
                }
                downloads.append(DEMDownload(url: url, fileName: fileName, expectedBytes: bytes))
            }
            offset += items.count
            let total = (object["total"] as? NSNumber)?.intValue ?? downloads.count
            if items.isEmpty || offset >= total { break }
        } while offset < 2_000
        return Array(Set(downloads)).sorted { $0.fileName < $1.fileName }
    }

    private func fetchDEM(_ download: DEMDownload) async {
        let destination = AppleMapCachePaths.demRoot.appendingPathComponent(download.fileName)
        let cached = await Task.detached(priority: .utility) {
            guard let size = (try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize) else { return false }
            if let expected = download.expectedBytes { return Int64(size) >= max(100_000, expected * 95 / 100) }
            return size > 5_000_000
        }.value
        if cached {
            recordCacheHit(kind: .dem)
            return
        }
        progress.phase = "Downloading DEM tiles"
        do {
            let (temporary, response) = try await URLSession.shared.download(from: download.url)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else {
                recordFailure(kind: .dem)
                return
            }
            try await Task.detached(priority: .utility) {
                try FileManager.default.createDirectory(
                    at: destination.deletingLastPathComponent(),
                    withIntermediateDirectories: true
                )
                if FileManager.default.fileExists(atPath: destination.path) {
                    try FileManager.default.removeItem(at: destination)
                }
                try FileManager.default.moveItem(at: temporary, to: destination)
            }.value
            recordDownload(kind: .dem)
        } catch is CancellationError {
            return
        } catch {
            AppleLog.warning("MapOffline", "DEM download failed \(download.fileName): \(error.localizedDescription)")
            recordFailure(kind: .dem)
        }
    }

    private func recordBadTile(_ data: Data) {
        AppleBadTilePolicy.record(data, defaults: defaults)
    }

    private enum OperationKind {
        case tile
        case dem
    }

    private func recordCacheHit(kind: OperationKind) {
        switch kind {
        case .tile:
            progress.tileCacheHits += 1
            progress.tileCompleted += 1
        case .dem:
            progress.demCacheHits += 1
            progress.demCompleted += 1
        }
        progress.completed += 1
    }

    private func recordDownload(kind: OperationKind) {
        switch kind {
        case .tile:
            progress.tileDownloaded += 1
            progress.tileCompleted += 1
        case .dem:
            progress.demDownloaded += 1
            progress.demCompleted += 1
        }
        progress.completed += 1
    }

    private func recordFailure(kind: OperationKind) {
        switch kind {
        case .tile:
            progress.tileFailed += 1
            progress.tileCompleted += 1
        case .dem:
            progress.demFailed += 1
            progress.demCompleted += 1
        }
        progress.completed += 1
    }

    private nonisolated static func scanCache() -> CacheStats {
        var result = CacheStats()
        for root in [AppleMapCachePaths.root, AppleMapCachePaths.demRoot] {
            guard let enumerator = FileManager.default.enumerator(
                at: root,
                includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]
            ) else { continue }
            for case let url as URL in enumerator {
                guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]),
                      values.isRegularFile == true else { continue }
                result.files += 1
                result.bytes += Int64(values.fileSize ?? 0)
                if let date = values.contentModificationDate, result.oldest == nil || date < result.oldest! { result.oldest = date }
            }
        }
        return result
    }

    private nonisolated static func maintainCache(maximumBytes: Int64, cutoff: Date) -> (removedFiles: Int, removedBytes: Int64) {
        var files: [(url: URL, bytes: Int64, date: Date)] = []
        guard let enumerator = FileManager.default.enumerator(
            at: AppleMapCachePaths.root,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]
        ) else { return (0, 0) }
        for case let url as URL in enumerator {
            guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]),
                  values.isRegularFile == true else { continue }
            files.append((url, Int64(values.fileSize ?? 0), values.contentModificationDate ?? .distantPast))
        }
        var removedFiles = 0
        var removedBytes: Int64 = 0
        for file in files where file.date < cutoff {
            if (try? FileManager.default.removeItem(at: file.url)) != nil {
                removedFiles += 1; removedBytes += file.bytes
            }
        }
        var remaining = files.filter { $0.date >= cutoff }.sorted { $0.date < $1.date }
        var bytes = remaining.reduce(Int64(0)) { $0 + $1.bytes }
        let trimTarget = maximumBytes * 9 / 10
        while bytes > maximumBytes, !remaining.isEmpty {
            let file = remaining.removeFirst()
            if (try? FileManager.default.removeItem(at: file.url)) != nil {
                removedFiles += 1; removedBytes += file.bytes; bytes -= file.bytes
            }
            if bytes <= trimTarget { break }
        }
        return (removedFiles, removedBytes)
    }
}

struct AppleOfflineMapPreparationView: View {
    struct BoundaryOption: Identifiable {
        let id: String
        let title: String
        let coordinates: [MapCoordinate]
    }

    @ObservedObject var manager: AppleMapOfflineManager
    let viewportBounds: OperationalMapBounds
    let boundaries: [BoundaryOption]
    let baseLayer: OperationalMapBaseLayer
    let contoursInitiallyEnabled: Bool

    @Environment(\.dismiss) private var dismiss
    @State private var preset = OperationalOfflinePreset.operations
    @State private var selectedBoundaryID = ""
    @State private var includeContours: Bool
    @State private var includeDEM = true
    @State private var demResolution = OperationalDEMResolution.standard30m

    init(
        manager: AppleMapOfflineManager,
        viewportBounds: OperationalMapBounds,
        boundaries: [BoundaryOption],
        baseLayer: OperationalMapBaseLayer,
        contoursInitiallyEnabled: Bool
    ) {
        self.manager = manager
        self.viewportBounds = viewportBounds
        self.boundaries = boundaries
        self.baseLayer = baseLayer
        self.contoursInitiallyEnabled = contoursInitiallyEnabled
        _includeContours = State(initialValue: contoursInitiallyEnabled)
    }

    private var bounds: OperationalMapBounds {
        guard let polygon = boundaries.first(where: { $0.id == selectedBoundaryID }) else { return viewportBounds }
        return OperationalMapBounds(coordinates: polygon.coordinates)
    }

    private var estimate: (tiles: Int, dem: Int, bytes: Int64) {
        manager.estimate(
            bounds: bounds, preset: preset, includeContours: includeContours,
            includeDEM: includeDEM, demResolution: demResolution
        )
    }

    private var selectionDescription: String {
        let area = boundaries.first(where: { $0.id == selectedBoundaryID })?.title ?? "Current visible map"
        let contents = [
            includeContours ? "contours" : nil,
            includeDEM ? "DEM \(demResolution.label)" : nil,
        ].compactMap { $0 }.joined(separator: ", ")
        return "\(area) · \(preset.label)" + (contents.isEmpty ? "" : " · \(contents)")
    }

    private var tileProgressText: String {
        let progress = manager.progress
        return "Tiles: \(progress.tileCompleted)/\(progress.tileTotal) "
            + "(hit=\(progress.tileCacheHits) fetched=\(progress.tileDownloaded) failed=\(progress.tileFailed))"
    }

    private var demProgressText: String {
        let progress = manager.progress
        return "DEM: \(progress.demCompleted)/\(progress.demTotal) "
            + "(hit=\(progress.demCacheHits) fetched=\(progress.demDownloaded) failed=\(progress.demFailed))"
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Area") {
                    Picker("Boundary", selection: $selectedBoundaryID) {
                        Text("Current visible map").tag("")
                        ForEach(boundaries) { Text($0.title).tag($0.id) }
                    }
                    Picker("Detail", selection: $preset) {
                        ForEach(OperationalOfflinePreset.all) { Text($0.label).tag($0) }
                    }
                }
                .disabled(manager.isRunning)
                Section("Contents") {
                    Toggle("Include contour tiles", isOn: $includeContours)
                    Toggle("Include DEM tiles", isOn: $includeDEM)
                    if includeDEM {
                        Picker("DEM detail", selection: $demResolution) {
                            ForEach(OperationalDEMResolution.allCases) { Text($0.label).tag($0) }
                        }
                        Text(demResolution.explanation)
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    LabeledContent("Map tiles", value: estimate.tiles.formatted())
                    LabeledContent("DEM tiles", value: estimate.dem.formatted())
                    LabeledContent("Conservative estimate", value: AppleMapOfflineManager.formatBytes(estimate.bytes))
                    if estimate.bytes > Int64(manager.maximumCacheGB * 1_000_000_000) {
                        Text("Selection exceeds the configured map-cache limit. Reduce the area/detail or raise the cache limit.")
                            .font(.footnote).foregroundStyle(.red)
                    }
                    Text("The estimate is conservative. One-metre availability is resolved from the USGS catalog when preparation starts.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                .disabled(manager.isRunning)
                if manager.isRunning || manager.progress.phase != "Idle" {
                    Section("Progress") {
                        if !manager.activeSelectionDescription.isEmpty {
                            Text(manager.activeSelectionDescription)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                        let percent = manager.progress.fraction * 100
                        Text("\(percent, specifier: "%.0f")% complete")
                            .font(.title3)
                        ProgressView(value: manager.progress.fraction)
                        Text(String(
                            format: "Progress: %@ %d/%d (%.2f%%) rate=%.1f/s ETA=%@",
                            manager.progress.phase,
                            manager.progress.completed,
                            manager.progress.total,
                            percent,
                            manager.progress.operationsPerSecond,
                            AppleMapOfflineManager.formatDuration(manager.progress.etaSeconds)
                        ))
                        .font(.caption.monospaced())
                        Text(tileProgressText)
                        .font(.caption.monospaced())
                        if manager.progress.demTotal > 0 {
                            Text(demProgressText)
                            .font(.caption.monospaced())
                        }
                        if manager.progress.failed > 0 {
                            Text("Total failures: \(manager.progress.failed)")
                                .font(.caption.monospaced())
                                .foregroundStyle(.red)
                        }
                        Text(manager.status).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Download Map")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if manager.isRunning {
                        Button("Cancel", role: .destructive) {
                            manager.cancel()
                        }
                    } else {
                        Button("Start") {
                            manager.start(
                                bounds: bounds,
                                preset: preset,
                                baseLayer: baseLayer,
                                includeContours: includeContours,
                                includeDEM: includeDEM,
                                demResolution: demResolution,
                                selectionDescription: selectionDescription
                            )
                        }
                        .disabled(estimate.tiles > 250_000
                            || estimate.bytes > Int64(manager.maximumCacheGB * 1_000_000_000))
                    }
                }
            }
        }
    }
}

struct AppleMapCacheManagementView: View {
    @ObservedObject var manager: AppleMapOfflineManager
    @Binding var offlineOnly: Bool
    @Binding var followFocusedDrone: Bool
    let canReloadMap: Bool
    let mapReloadInFlight: Bool
    let mapReloadStatus: String
    let onReloadMap: () -> Void
    let onExportMutualAid: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                // Keep these controls in the same order as Android's Map Management menu.
                Section("Map Management") {
                    Toggle(
                        followFocusedDrone ? "Follow Focused Drone: On" : "Follow Focused Drone: Off",
                        isOn: $followFocusedDrone
                    )
                    Button(action: onReloadMap) {
                        HStack {
                            Text(mapReloadInFlight ? "Reloading Map…" : "Reload Map")
                            if mapReloadInFlight { ProgressView() }
                        }
                    }
                    .disabled(!canReloadMap || mapReloadInFlight)
                    Text(mapReloadStatus)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    NavigationLink("Bad Tiles…") {
                        AppleBadTileManagementView(manager: manager)
                    }
                    Stepper(
                        "Max Cache Size: \(manager.maximumCacheGB, specifier: "%.1f") GB",
                        value: $manager.maximumCacheGB,
                        in: 0.1 ... 64,
                        step: 0.1
                    )
                    Stepper(
                        "Maximum Tile Age: \(manager.maximumTileAgeDays) days",
                        value: $manager.maximumTileAgeDays,
                        in: 1 ... 3_650
                    )
                    Button("Export MA Package…", systemImage: "shippingbox") {
                        dismiss()
                        onExportMutualAid()
                    }
                }
                Section("Additional Cache Controls") {
                    Toggle("Offline Tiles Only", isOn: $offlineOnly)
                    LabeledContent("Cached files", value: manager.cacheStats.files.formatted())
                    LabeledContent("Cache size", value: AppleMapOfflineManager.formatBytes(manager.cacheStats.bytes))
                    Button("Run Cache Maintenance") { manager.runMaintenance() }
                    Text(manager.status).font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Map Management")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { manager.saveSettings(); dismiss() }
                }
            }
            .onAppear { manager.refreshStats() }
        }
    }
}

private struct AppleBadTileManagementView: View {
    @ObservedObject var manager: AppleMapOfflineManager
    @State private var badHashExport: URL?

    var body: some View {
        Form {
            // Keep these controls in the same order as Android's Bad Tiles menu.
            Section("Bad Tiles") {
                NavigationLink("How To") {
                    AppleBadTileHowToView()
                }
                Toggle(
                    manager.autoRemoveBadTiles ? "Auto Remove Bad Tiles: On" : "Auto Remove Bad Tiles: Off",
                    isOn: $manager.autoRemoveBadTiles
                )
                Button("Clear Bad Tile Flags (\(manager.badTileCount))") {
                    manager.clearBadTileFlags()
                }
                if let export = badHashExport {
                    ShareLink(item: export) {
                        Label("Export Bad Tile Hashes", systemImage: "square.and.arrow.up")
                    }
                } else {
                    Button("Export Bad Tile Hashes") {
                        badHashExport = manager.exportBadTileHashes()
                    }
                }
            }
        }
        .navigationTitle("Bad Tiles")
    }
}

private struct AppleBadTileHowToView: View {
    var body: some View {
        Form {
            Section {
                Text("Use this when map tiles show a cached error page such as OpenStreetMap's ‘Access blocked’ tile.")
                Text("1. Turn on Auto Remove Bad Tiles if you want quarantined tiles removed automatically when encountered.")
                Text("2. Long-press a bad tile on the map.")
                Text("3. In the Remove Bad Tile dialog, leave ‘Also quarantine same-hash tiles’ checked and press Remove.")
                Text("4. The selected tile is removed from cache, and matching bad tiles can be suppressed across the map.")
                Text("Clear Bad Tile Flags removes the quarantine list only. It does not remove tiles already cached.")
                Text("Export Bad Tile Hashes saves the quarantined hashes for troubleshooting or sharing.")
            }
        }
        .navigationTitle("Bad Tiles How To")
    }
}
