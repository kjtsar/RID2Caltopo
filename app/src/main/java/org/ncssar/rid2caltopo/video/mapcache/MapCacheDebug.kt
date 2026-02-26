package org.ncssar.rid2caltopo.video.mapcache

import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal object MapCacheDebug {
    private const val TAG = "MapCacheDebug"
    private val enabled = AtomicBoolean(false)
    private val eventCount = AtomicLong(0L)

    fun isEnabled(): Boolean = enabled.get()

    fun setEnabled(value: Boolean) {
        val changed = enabled.getAndSet(value) != value
        if (changed) {
            eventCount.set(0L)
            CTDebug(TAG, "Cache debug ${if (value) "enabled" else "disabled"}.")
        }
    }

    fun log(message: String) {
        if (!enabled.get()) return
        val seq = eventCount.incrementAndGet()
        CTDebug(TAG, "#$seq $message")
    }
}
