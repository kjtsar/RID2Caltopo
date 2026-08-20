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

public struct OperationalClueHeadingSelection: Sendable, Equatable {
    public let degrees: Double?
    public let sourceLabel: String?

    public init(degrees: Double?, sourceLabel: String?) {
        self.degrees = degrees
        self.sourceLabel = sourceLabel
    }
}

public struct OperationalDJIVideoPosition: Sendable, Equatable {
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double

    public init(latitude: Double, longitude: Double, altitudeMeters: Double) {
        self.latitude = latitude
        self.longitude = longitude
        self.altitudeMeters = altitudeMeters
    }
}

public enum OperationalClueGeometry {
    public static func djiAbsoluteCameraAzimuthDegrees(
        seiCameraAzimuthDegrees: Double?,
        magneticDeclinationDegrees: Double?
    ) -> Double? {
        guard let seiCameraAzimuthDegrees, seiCameraAzimuthDegrees.isFinite,
              let magneticDeclinationDegrees, magneticDeclinationDegrees.isFinite
        else { return nil }
        // DJI encodes this angle counter-clockwise from magnetic east. CalTopo
        // requires a clockwise bearing from true north.
        return RidHeading.normalized(90 - seiCameraAzimuthDegrees + magneticDeclinationDegrees)
    }

    /// Matrice 4TD calibration: raw -90 is down; controlled raw -14.5625 is horizontal.
    public static func djiCalibratedTiltDegrees(rawTiltDegrees: Double?) -> Double? {
        guard let rawTiltDegrees, rawTiltDegrees.isFinite else { return nil }
        let rawHorizontalReferenceDegrees = -14.5625
        let scale = 90.0 / (rawHorizontalReferenceDegrees - (-90.0))
        return min(90, max(-90, (rawTiltDegrees - rawHorizontalReferenceDegrees) * scale))
    }

    public static func djiVideoPosition(
        tag4AnglesDegrees: [Double]
    ) -> OperationalDJIVideoPosition? {
        guard tag4AnglesDegrees.count == 9 else { return nil }
        func signedAngle(_ value: Double) -> Double {
            value >= 180 ? value - 360 : value
        }
        let latitude = signedAngle(tag4AnglesDegrees[6]) / 2
        let longitude = signedAngle(tag4AnglesDegrees[7])
        let altitude = -(signedAngle(tag4AnglesDegrees[8]) / 360 * 4_294_967_296) / 1_000
        guard latitude.isFinite, longitude.isFinite, altitude.isFinite,
              (-90 ... 90).contains(latitude),
              (-180 ... 180).contains(longitude),
              (-1_000 ... 30_000).contains(altitude),
              abs(latitude) > 0.00000001 || abs(longitude) > 0.00000001
        else { return nil }
        return OperationalDJIVideoPosition(
            latitude: latitude,
            longitude: longitude,
            altitudeMeters: altitude
        )
    }

    public static func djiVideoAglMeters(
        mslAltitudeMeters: Double?,
        groundElevationMeters: Double?
    ) -> Double? {
        guard let mslAltitudeMeters, mslAltitudeMeters.isFinite,
              let groundElevationMeters, groundElevationMeters.isFinite
        else { return nil }
        let agl = mslAltitudeMeters - groundElevationMeters
        return agl.isFinite && (0 ... 10_000).contains(agl) ? agl : nil
    }

    public static func selectedGimbalAngleDegrees(
        streamPitchDegrees: Double?,
        fallbackDegrees: Double = -90
    ) -> Double {
        guard let streamPitchDegrees, streamPitchDegrees.isFinite else {
            return min(90, max(-90, fallbackDegrees))
        }
        return min(90, max(-90, streamPitchDegrees))
    }

    public static func selectedHeading(
        cameraAzimuthDegrees: Double? = nil,
        videoCourseDegrees: Double? = nil,
        cameraYawDegrees: Double?,
        streamHeadingDegrees: Double?,
        ridHeadingDegrees: Double?,
        derivedHeadingDegrees: Double? = nil
    ) -> OperationalClueHeadingSelection {
        if let cameraAzimuth = RidHeading.normalized(cameraAzimuthDegrees) {
            return OperationalClueHeadingSelection(
                degrees: cameraAzimuth,
                sourceLabel: "DJI camera azimuth"
            )
        }
        if let videoCourse = RidHeading.normalized(videoCourseDegrees) {
            return OperationalClueHeadingSelection(
                degrees: videoCourse,
                sourceLabel: "DJI video-derived course"
            )
        }
        if let derivedHeading = RidHeading.normalized(derivedHeadingDegrees) {
            return OperationalClueHeadingSelection(
                degrees: derivedHeading,
                sourceLabel: "Derived drone heading"
            )
        }
        if let cameraYaw = RidHeading.normalized(cameraYawDegrees) {
            return OperationalClueHeadingSelection(
                degrees: cameraYaw,
                sourceLabel: "Camera yaw"
            )
        }
        if let streamHeading = RidHeading.normalized(streamHeadingDegrees) {
            return OperationalClueHeadingSelection(
                degrees: streamHeading,
                sourceLabel: "Stream heading"
            )
        }
        if let ridHeading = RidHeading.normalized(ridHeadingDegrees) {
            return OperationalClueHeadingSelection(
                degrees: ridHeading,
                sourceLabel: "RID aircraft track"
            )
        }
        return OperationalClueHeadingSelection(degrees: nil, sourceLabel: nil)
    }

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
        let angle = min(90, max(-90, gimbalAngleDegrees))
        guard angle < -0.1 else {
            return OperationalClueProjection(
                latitude: droneLatitude,
                longitude: droneLongitude,
                altitudeMeters: groundAltitude
            )
        }
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

    public static func projectWithTerrain(
        droneLatitude: Double,
        droneLongitude: Double,
        droneAltitudeMeters: Double?,
        headingDegrees: Double?,
        aglMeters: Double?,
        gimbalAngleDegrees: Double,
        sampleElevationMeters: @Sendable (Double, Double) async -> Double?
    ) async -> OperationalClueProjection {
        let flatProjection = project(
            droneLatitude: droneLatitude,
            droneLongitude: droneLongitude,
            droneAltitudeMeters: droneAltitudeMeters,
            headingDegrees: headingDegrees,
            aglMeters: aglMeters,
            gimbalAngleDegrees: gimbalAngleDegrees
        )
        guard let droneAltitudeMeters, droneAltitudeMeters.isFinite,
              let headingDegrees, headingDegrees.isFinite,
              let aglMeters, aglMeters.isFinite, aglMeters > 0
        else { return flatProjection }

        let angle = min(90, max(-90, gimbalAngleDegrees))
        guard angle < -0.1 else { return flatProjection }
        let tiltFromHorizon = max(0.1, min(90, abs(angle)))
        guard tiltFromHorizon < 89.9 else { return flatProjection }
        let slopeDown = tan(tiltFromHorizon * .pi / 180)
        guard slopeDown.isFinite, slopeDown > 0 else { return flatProjection }
        let flatDistance = aglMeters / slopeDown
        guard flatDistance.isFinite, flatDistance > 0 else { return flatProjection }

        let flatGround = droneAltitudeMeters - aglMeters
        let droneDEM = await sampleElevationMeters(droneLatitude, droneLongitude)
        let scale = inferDEMScaleToMeters(
            droneAltitudeMeters: droneAltitudeMeters,
            knownGroundMeters: flatGround,
            droneDEM: droneDEM
        )
        let maximumDistance = min(2_500, max(60, max(flatDistance * 3, flatDistance + 250)))
        let step = maximumDistance <= 180 ? 10.0 : (maximumDistance <= 600 ? 20.0 : 30.0)

        var previousDistance = 0.0
        var previousGround = flatGround
        var distance = step
        while distance <= maximumDistance + 0.001 {
            let candidate = destinationPoint(
                latitude: droneLatitude,
                longitude: droneLongitude,
                bearingDegrees: headingDegrees,
                distanceMeters: distance
            )
            let candidateDEM = await sampleElevationMeters(candidate.latitude, candidate.longitude)
            let ground = normalizedDEMGroundMeters(
                candidateDEM: candidateDEM,
                droneDEM: droneDEM,
                flatGroundMeters: flatGround,
                scaleToMeters: scale
            ) ?? previousGround
            let rayAltitude = droneAltitudeMeters - slopeDown * distance
            if rayAltitude <= ground {
                var lowDistance = previousDistance
                var lowGround = previousGround
                var highDistance = distance
                var highPoint = candidate
                var highGround = ground
                for _ in 0 ..< 6 {
                    let midDistance = (lowDistance + highDistance) / 2
                    let midPoint = destinationPoint(
                        latitude: droneLatitude,
                        longitude: droneLongitude,
                        bearingDegrees: headingDegrees,
                        distanceMeters: midDistance
                    )
                    let midDEM = await sampleElevationMeters(midPoint.latitude, midPoint.longitude)
                    let midGround = normalizedDEMGroundMeters(
                        candidateDEM: midDEM,
                        droneDEM: droneDEM,
                        flatGroundMeters: flatGround,
                        scaleToMeters: scale
                    ) ?? ((lowGround + highGround) / 2)
                    if droneAltitudeMeters - slopeDown * midDistance <= midGround {
                        highDistance = midDistance
                        highPoint = midPoint
                        highGround = midGround
                    } else {
                        lowDistance = midDistance
                        lowGround = midGround
                    }
                }
                return OperationalClueProjection(
                    latitude: highPoint.latitude,
                    longitude: highPoint.longitude,
                    altitudeMeters: highGround
                )
            }
            previousDistance = distance
            previousGround = ground
            distance += step
        }
        return flatProjection
    }

    private static func inferDEMScaleToMeters(
        droneAltitudeMeters: Double,
        knownGroundMeters: Double,
        droneDEM: Double?
    ) -> Double {
        guard let droneDEM, droneDEM.isFinite else { return 1 }
        let directError = abs(droneDEM - knownGroundMeters) + abs(droneDEM - droneAltitudeMeters)
        let converted = droneDEM * 0.3048
        let feetError = abs(converted - knownGroundMeters) + abs(converted - droneAltitudeMeters)
        return feetError < directError ? 0.3048 : 1
    }

    private static func normalizedDEMGroundMeters(
        candidateDEM: Double?,
        droneDEM: Double?,
        flatGroundMeters: Double,
        scaleToMeters: Double
    ) -> Double? {
        guard let candidateDEM, candidateDEM.isFinite else { return nil }
        guard let droneDEM, droneDEM.isFinite else { return candidateDEM * scaleToMeters }
        return flatGroundMeters + (candidateDEM - droneDEM) * scaleToMeters
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
