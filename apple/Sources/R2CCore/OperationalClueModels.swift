import Foundation

public enum OperationalClueUploadState: String, Codable, Sendable, CaseIterable {
    case localOnly
    case pending
    case uploading
    case published
    case failed
}

public struct OperationalClueRecord: Codable, Sendable, Equatable, Identifiable {
    public let id: UUID
    public let capturedAt: Date
    public let aircraftID: String
    public let designator: String
    public let droneLatitude: Double
    public let droneLongitude: Double
    public let droneAltitudeMeters: Double?
    public let clueLatitude: Double
    public let clueLongitude: Double
    public let clueAltitudeMeters: Double?
    public let headingDegrees: Double?
    public let aglMeters: Double?
    public let atoMeters: Double?
    public let gimbalAngleDegrees: Double
    public var title: String
    public var clueDescription: String
    public let imageFilename: String
    public let thumbnailFilename: String
    public var uploadState: OperationalClueUploadState
    public var uploadAttempts: Int
    public var lastUploadError: String?
    public var caltopoMarkerID: String?
    public let caltopoMediaID: UUID

    public init(
        id: UUID = UUID(),
        capturedAt: Date,
        aircraftID: String,
        designator: String,
        droneLatitude: Double,
        droneLongitude: Double,
        droneAltitudeMeters: Double?,
        clueLatitude: Double,
        clueLongitude: Double,
        clueAltitudeMeters: Double?,
        headingDegrees: Double?,
        aglMeters: Double?,
        atoMeters: Double?,
        gimbalAngleDegrees: Double,
        title: String,
        clueDescription: String,
        imageFilename: String,
        thumbnailFilename: String,
        uploadState: OperationalClueUploadState,
        uploadAttempts: Int = 0,
        lastUploadError: String? = nil,
        caltopoMarkerID: String? = nil,
        caltopoMediaID: UUID = UUID()
    ) {
        self.id = id
        self.capturedAt = capturedAt
        self.aircraftID = aircraftID
        self.designator = designator
        self.droneLatitude = droneLatitude
        self.droneLongitude = droneLongitude
        self.droneAltitudeMeters = droneAltitudeMeters
        self.clueLatitude = clueLatitude
        self.clueLongitude = clueLongitude
        self.clueAltitudeMeters = clueAltitudeMeters
        self.headingDegrees = headingDegrees
        self.aglMeters = aglMeters
        self.atoMeters = atoMeters
        self.gimbalAngleDegrees = gimbalAngleDegrees
        self.title = title
        self.clueDescription = clueDescription
        self.imageFilename = imageFilename
        self.thumbnailFilename = thumbnailFilename
        self.uploadState = uploadState
        self.uploadAttempts = uploadAttempts
        self.lastUploadError = lastUploadError
        self.caltopoMarkerID = caltopoMarkerID
        self.caltopoMediaID = caltopoMediaID
    }
}

public struct OperationalClueProjection: Sendable, Equatable {
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double?

    public init(latitude: Double, longitude: Double, altitudeMeters: Double?) {
        self.latitude = latitude
        self.longitude = longitude
        self.altitudeMeters = altitudeMeters
    }
}

public enum OperationalClueGeometry {
    public static func project(
        droneLatitude: Double,
        droneLongitude: Double,
        droneAltitudeMeters: Double?,
        headingDegrees: Double?,
        aglMeters: Double?,
        gimbalAngleDegrees: Double
    ) -> OperationalClueProjection {
        let groundAltitude = if let droneAltitudeMeters, let aglMeters {
            droneAltitudeMeters - aglMeters
        } else {
            droneAltitudeMeters
        }
        guard let headingDegrees, headingDegrees.isFinite,
              let aglMeters, aglMeters.isFinite, aglMeters > 0
        else {
            return OperationalClueProjection(
                latitude: droneLatitude,
                longitude: droneLongitude,
                altitudeMeters: groundAltitude
            )
        }
        let angle = min(0, max(-90, gimbalAngleDegrees))
        let downFromHorizon = abs(angle)
        guard downFromHorizon < 89.9, downFromHorizon > 0.1 else {
            return OperationalClueProjection(
                latitude: droneLatitude,
                longitude: droneLongitude,
                altitudeMeters: groundAltitude
            )
        }
        let distance = aglMeters / tan(downFromHorizon * .pi / 180)
        guard distance.isFinite, distance > 0 else {
            return OperationalClueProjection(
                latitude: droneLatitude,
                longitude: droneLongitude,
                altitudeMeters: groundAltitude
            )
        }
        let destination = destinationPoint(
            latitude: droneLatitude,
            longitude: droneLongitude,
            bearingDegrees: headingDegrees,
            distanceMeters: min(distance, 2_500)
        )
        return OperationalClueProjection(
            latitude: destination.latitude,
            longitude: destination.longitude,
            altitudeMeters: groundAltitude
        )
    }

    private static func destinationPoint(
        latitude: Double,
        longitude: Double,
        bearingDegrees: Double,
        distanceMeters: Double
    ) -> (latitude: Double, longitude: Double) {
        let angularDistance = distanceMeters / 6_371_008.8
        let bearing = bearingDegrees * .pi / 180
        let latitude1 = latitude * .pi / 180
        let longitude1 = longitude * .pi / 180
        let latitude2 = asin(
            sin(latitude1) * cos(angularDistance)
                + cos(latitude1) * sin(angularDistance) * cos(bearing)
        )
        let longitude2 = longitude1 + atan2(
            sin(bearing) * sin(angularDistance) * cos(latitude1),
            cos(angularDistance) - sin(latitude1) * sin(latitude2)
        )
        return (latitude2 * 180 / .pi, longitude2 * 180 / .pi)
    }
}

public struct CaltopoPhotoClue: Sendable, Equatable {
    public let markerID: UUID
    public let mediaID: UUID
    public let latitude: Double
    public let longitude: Double
    public let title: String
    public let description: String
    public let createdMilliseconds: Int64
    public let jpegData: Data
    public let teamID: String
    public let folderID: String?

    public init(
        markerID: UUID = UUID(),
        mediaID: UUID = UUID(),
        latitude: Double,
        longitude: Double,
        title: String,
        description: String,
        createdMilliseconds: Int64,
        jpegData: Data,
        teamID: String,
        folderID: String? = nil
    ) {
        self.markerID = markerID
        self.mediaID = mediaID
        self.latitude = latitude
        self.longitude = longitude
        self.title = title
        self.description = description
        self.createdMilliseconds = createdMilliseconds
        self.jpegData = jpegData
        self.teamID = teamID
        self.folderID = folderID
    }
}
