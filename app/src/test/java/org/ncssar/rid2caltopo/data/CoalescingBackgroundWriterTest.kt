package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CoalescingBackgroundWriterTest {
    @Test
    fun enqueueCoalescesPendingWritesWhileWriteIsInFlight() {
        val dispatched = ArrayDeque<() -> Unit>()
        val written = mutableListOf<String>()
        val writer = CoalescingBackgroundWriter<String>(
            dispatch = { task -> dispatched.addLast(task) },
            write = { value -> written += value }
        )

        writer.enqueue("A")
        writer.enqueue("B")
        writer.enqueue("C")

        assertEquals(1, dispatched.size)
        dispatched.removeFirst().invoke()
        assertEquals(listOf("A"), written)
        assertEquals(1, dispatched.size)

        dispatched.removeFirst().invoke()
        assertEquals(listOf("A", "C"), written)
        assertEquals(0, dispatched.size)
    }

    @Test
    fun failedWriteDoesNotBlockLaterPendingWrite() {
        val dispatched = ArrayDeque<() -> Unit>()
        val written = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val writer = CoalescingBackgroundWriter<String>(
            dispatch = { task -> dispatched.addLast(task) },
            write = { value ->
                if (value == "A") throw IllegalStateException("boom")
                written += value
            },
            onWriteFailed = { value, _ -> failures += value }
        )

        writer.enqueue("A")
        writer.enqueue("B")

        dispatched.removeFirst().invoke()

        assertEquals(listOf("A"), failures)
        assertEquals(1, dispatched.size)

        dispatched.removeFirst().invoke()

        assertEquals(listOf("B"), written)
        assertEquals(0, dispatched.size)
    }
}
