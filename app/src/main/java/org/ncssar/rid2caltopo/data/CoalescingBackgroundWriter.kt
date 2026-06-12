package org.ncssar.rid2caltopo.data

internal class CoalescingBackgroundWriter<T>(
    private val dispatch: (() -> Unit) -> Unit,
    private val write: (T) -> Unit,
    private val onWriteComplete: (T) -> Unit = {},
    private val onWriteFailed: (T, Throwable) -> Unit = { _, _ -> }
) {
    private val lock = Any()
    private var inFlight = false
    private var pending: T? = null

    fun enqueue(value: T) {
        val first = synchronized(lock) {
            if (inFlight) {
                pending = value
                null
            } else {
                inFlight = true
                value
            }
        }
        if (first != null) dispatchWrite(first)
    }

    private fun dispatchWrite(value: T) {
        dispatch {
            try {
                write(value)
                onWriteComplete(value)
            } catch (t: Throwable) {
                onWriteFailed(value, t)
            }
            val next = synchronized(lock) {
                val candidate = pending
                pending = null
                if (candidate == null) {
                    inFlight = false
                }
                candidate
            }
            if (next != null) dispatchWrite(next)
        }
    }
}
