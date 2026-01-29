package org.ncssar.rid2caltopo.video

import android.graphics.Bitmap
import java.time.Instant

data class ClueSnapshot(
    val bitmap: Bitmap,
    val designator: String,
    val timestamp: Instant
)