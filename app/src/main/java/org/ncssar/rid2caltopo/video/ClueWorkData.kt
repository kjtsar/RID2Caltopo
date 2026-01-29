package org.ncssar.rid2caltopo.video

import androidx.work.Data
import androidx.work.workDataOf
import java.time.Instant

fun ClueSubmission.toWorkData(): Data =
    workDataOf(
        "designator" to designator,
        "timestamp" to timestamp.toEpochMilli(),
        "lat" to latitude,
        "lon" to longitude,
        "alt" to altitudeMeters,
        "desc" to description,
        "path" to snapshotPath
    )

fun Data.toClueSubmission(): ClueSubmission =
    ClueSubmission(
        designator = getString("designator")!!,
        timestamp = Instant.ofEpochMilli(getLong("timestamp", 0)),
        latitude = getDouble("lat", 0.0),
        longitude = getDouble("lon", 0.0),
        altitudeMeters = getDouble("alt", Double.NaN).takeIf { !it.isNaN() },
        description = getString("desc")!!,
        snapshotPath = getString("path")!!
    )
