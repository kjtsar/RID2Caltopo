package org.ncssar.rid2caltopo.video

import java.time.Instant

data class ClueSubmission(
    val designator: String,
    val timestamp: Instant,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val description: String,
    val snapshotPath: String
)
