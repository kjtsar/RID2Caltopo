import Foundation
import R2CCore

actor AppleTerrainElevationService {
    private struct CacheEntry: Codable {
        let elevationMeters: Double
        let fetchedAt: Date
    }

    private let session: URLSession
    private let cacheDirectory: URL
    private let localDEM: GeoTiffElevationSource
    private let maximumFreshAge: TimeInterval = 365 * 24 * 60 * 60

    init(session: URLSession = .shared) {
        self.session = session
        let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        cacheDirectory = root.appendingPathComponent("RID2Caltopo/Terrain", isDirectory: true)
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        localDEM = GeoTiffElevationSource(directory: support.appendingPathComponent("RID2Caltopo/DEM", isDirectory: true))
    }

    func sample(latitude: Double, longitude: Double) async -> OperationalTerrainSample? {
        guard latitude.isFinite, longitude.isFinite,
              (-90 ... 90).contains(latitude), (-180 ... 180).contains(longitude)
        else { return nil }
        let coordinate = OperationalAltitudeCoordinator.Coordinate(latitude: latitude, longitude: longitude)
        let key = OperationalAltitudeCoordinator.terrainCacheKey(coordinate)
        let localDEM = self.localDEM
        if let elevation = await Task.detached(priority: .utility, operation: {
            localDEM.sampleElevationMeters(latitude: latitude, longitude: longitude)
        }).value {
            return OperationalTerrainSample(elevationMeters: elevation)
        }
        let cached = load(key: key)
        if let cached, Date().timeIntervalSince(cached.fetchedAt) <= maximumFreshAge {
            return OperationalTerrainSample(elevationMeters: cached.elevationMeters)
        }

        do {
            var components = URLComponents(string: "https://epqs.nationalmap.gov/v1/json")!
            components.queryItems = [
                URLQueryItem(name: "x", value: String(longitude)),
                URLQueryItem(name: "y", value: String(latitude)),
                URLQueryItem(name: "units", value: "Meters"),
                URLQueryItem(name: "wkid", value: "4326"),
            ]
            var request = URLRequest(url: components.url!)
            request.timeoutInterval = 6
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200,
                  let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let value = Self.elevation(from: object), value.isFinite,
                  (-500 ... 10_000).contains(value)
            else { throw URLError(.badServerResponse) }
            save(CacheEntry(elevationMeters: value, fetchedAt: Date()), key: key)
            return OperationalTerrainSample(elevationMeters: value)
        } catch {
            if let cached {
                return OperationalTerrainSample(elevationMeters: cached.elevationMeters, stale: true)
            }
            AppleLog.warning(
                "Terrain",
                String(format: "DEM unavailable lat=%.5f lng=%.5f error=%@", latitude, longitude, error.localizedDescription)
            )
            return nil
        }
    }

    private static func elevation(from object: [String: Any]) -> Double? {
        if let value = object["value"] as? NSNumber { return value.doubleValue }
        if let value = object["value"] as? String { return Double(value) }
        if let usgs = object["USGS_Elevation_Point_Query_Service"] as? [String: Any],
           let query = usgs["Elevation_Query"] as? [String: Any] {
            if let value = query["Elevation"] as? NSNumber { return value.doubleValue }
            if let value = query["Elevation"] as? String { return Double(value) }
        }
        return nil
    }

    private func url(for key: String) -> URL {
        cacheDirectory.appendingPathComponent(key.replacingOccurrences(of: "|", with: "_") + ".json")
    }

    private func load(key: String) -> CacheEntry? {
        guard let data = try? Data(contentsOf: url(for: key)) else { return nil }
        return try? JSONDecoder().decode(CacheEntry.self, from: data)
    }

    private func save(_ entry: CacheEntry, key: String) {
        do {
            try FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
            try JSONEncoder().encode(entry).write(to: url(for: key), options: .atomic)
        } catch {
            AppleLog.warning("Terrain", "DEM cache write failed: \(error.localizedDescription)")
        }
    }
}
