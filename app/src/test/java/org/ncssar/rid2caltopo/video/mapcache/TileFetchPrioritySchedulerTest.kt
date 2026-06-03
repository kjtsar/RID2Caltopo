package org.ncssar.rid2caltopo.video.mapcache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TileFetchPrioritySchedulerTest {
    @Test
    fun highPriorityRunsQuicklyWhenTileIoIsIdle() {
        newScheduler().use { scheduler ->
            val high = scheduler.submitHighPriority(Callable { "high-priority" })

            assertEquals("high-priority", high.get(250, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun highPriorityStartsWhileLowPriorityFetchIsBlocked() {
        newScheduler().use { scheduler ->
            val lowStarted = CountDownLatch(1)
            val releaseLow = CountDownLatch(1)
            val highStarted = CountDownLatch(1)
            val completionOrder = AtomicInteger(0)
            val highCompletionOrder = AtomicInteger(0)

            val low = scheduler.submitLowPriority(Callable {
                lowStarted.countDown()
                assertTrue(releaseLow.await(5, TimeUnit.SECONDS))
                completionOrder.incrementAndGet()
            })
            assertTrue("low-priority fetch should enter the simulated long load", lowStarted.await(1, TimeUnit.SECONDS))

            val high = scheduler.submitHighPriority(Callable {
                highCompletionOrder.set(completionOrder.incrementAndGet())
                highStarted.countDown()
                "high-priority"
            })

            assertTrue(
                "high-priority fetch should start without waiting for the blocked low-priority fetch",
                highStarted.await(250, TimeUnit.MILLISECONDS)
            )
            assertEquals("high-priority", high.get(1, TimeUnit.SECONDS))
            assertEquals("high-priority fetch should complete first", 1, highCompletionOrder.get())

            releaseLow.countDown()
            assertEquals("low-priority fetch should finish after it is released", 2, low.get(1, TimeUnit.SECONDS))
        }
    }

    private fun newScheduler(): TileFetchPriorityScheduler =
        TileFetchPriorityScheduler(
            highExecutor = Executors.newSingleThreadExecutor(),
            lowExecutor = Executors.newSingleThreadExecutor()
        )
}
