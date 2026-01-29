package org.ncssar.rid2caltopo.video

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.work.CoroutineWorker
import java.io.File
import androidx.core.graphics.createBitmap

@OptIn(UnstableApi::class)
fun captureSnapshot(
    activity: Activity,
    playerView: PlayerView,
    onResult: (Bitmap?) -> Unit
) {
    val view = playerView.videoSurfaceView ?: run {
        onResult(null)
        return
    }

    val bitmap = createBitmap(view.width, view.height)
    val surfaceView = playerView.videoSurfaceView as? SurfaceView
    if (surfaceView == null) {
        onResult(null)
        return
    }
    PixelCopy.request(
        surfaceView,
        bitmap,
        { result ->
            if (result == PixelCopy.SUCCESS) {
                onResult(bitmap)
            } else {
                onResult(null)
            }
        },
        Handler(Looper.getMainLooper())
    )
}

