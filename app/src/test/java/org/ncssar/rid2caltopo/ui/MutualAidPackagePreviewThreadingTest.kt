package org.ncssar.rid2caltopo.ui

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MutualAidPackagePreviewThreadingTest {
    @Test
    fun readMutualAidPackagePreviewOffMainRunsReaderOnSuppliedDispatcher() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ma-preview-worker")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            var readerThreadName = ""
            val result = readMutualAidPackagePreviewOffMain(dispatcher) {
                readerThreadName = Thread.currentThread().name
                "preview"
            }

            assertEquals("preview", result)
            assertTrue(readerThreadName.startsWith("ma-preview-worker"))
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
