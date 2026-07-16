package org.ncssar.rid2caltopo.video.ffmpeg

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegSessionExecutionLanesTest {
    @Test
    fun blockedNativeStop_doesNotStarveRenderControl() {
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val controlCompleted = CountDownLatch(1)
        val lanes = FfmpegSessionExecutionLanes(
            controlExecutor = Executors.newSingleThreadExecutor(),
            stopExecutor = Executors.newSingleThreadExecutor(),
        )

        try {
            lanes.executeStop {
                stopEntered.countDown()
                releaseStop.await(5, TimeUnit.SECONDS)
            }
            assertTrue("Stop action did not begin", stopEntered.await(1, TimeUnit.SECONDS))

            lanes.executeControl { controlCompleted.countDown() }

            assertTrue(
                "Render control was starved by blocked native teardown",
                controlCompleted.await(1, TimeUnit.SECONDS),
            )
        } finally {
            releaseStop.countDown()
            lanes.shutdownControl()
            lanes.shutdownStops()
        }
    }
}
