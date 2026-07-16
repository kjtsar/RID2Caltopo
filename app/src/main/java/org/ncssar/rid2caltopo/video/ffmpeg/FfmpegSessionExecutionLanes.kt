package org.ncssar.rid2caltopo.video.ffmpeg

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class FfmpegSessionExecutionLanes(
    private val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val stopExecutor: ExecutorService = Executors.newFixedThreadPool(2),
) {
    fun executeControl(action: () -> Unit) {
        controlExecutor.execute(action)
    }

    fun executeStop(action: () -> Unit) {
        stopExecutor.execute(action)
    }

    fun shutdownControl() {
        controlExecutor.shutdown()
    }

    fun shutdownStops() {
        stopExecutor.shutdown()
    }
}
