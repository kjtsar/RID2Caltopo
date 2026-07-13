package org.ncssar.rid2caltopo.video

import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SerializedTaskQueueTest {
    @Test
    fun laterTaskWaitsForPendingTaskInsteadOfReplacingIt() = runBlocking {
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val applied = mutableListOf<String>()
        val queue = SerializedTaskQueue(this, EmptyCoroutineContext)

        val first = queue.submit {
            releaseFirst.await()
            applied += "first"
        }
        val second = queue.submit {
            secondStarted.complete(Unit)
            applied += "second"
        }

        yield()
        assertFalse(secondStarted.isCompleted)

        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf("first", "second"), applied)
    }
}
