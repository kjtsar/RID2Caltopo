package org.ncssar.rid2caltopo.data

import android.content.Context
import java.util.Locale
import kotlin.math.round

object VideoThumbnailRefreshPolicy {
    const val DEFAULT_SECONDS = 5.0
    const val MIN_SECONDS = 0.5
    const val MAX_SECONDS = 60.0

    fun normalize(seconds: Double?): Double {
        val finite = seconds?.takeIf { it.isFinite() } ?: DEFAULT_SECONDS
        val clamped = finite.coerceIn(MIN_SECONDS, MAX_SECONDS)
        return round(clamped * 10.0) / 10.0
    }

    fun format(seconds: Double?): String =
        String.format(Locale.US, "%.1f", normalize(seconds))

    fun milliseconds(seconds: Double?): Long =
        (normalize(seconds) * 1_000.0).toLong()
}

object VideoThumbnailRefreshPrefs {
    private const val PREFS = "video_stream_preferences"
    private const val REFRESH_SECONDS = "thumbnail_refresh_seconds"

    @JvmStatic
    fun getSeconds(context: Context?): Double {
        val stored = context
            ?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(REFRESH_SECONDS, null)
            ?.toDoubleOrNull()
        return VideoThumbnailRefreshPolicy.normalize(stored)
    }

    @JvmStatic
    fun setSeconds(context: Context?, seconds: Double): Double {
        val normalized = VideoThumbnailRefreshPolicy.normalize(seconds)
        context
            ?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(REFRESH_SECONDS, VideoThumbnailRefreshPolicy.format(normalized))
            ?.apply()
        return normalized
    }
}
