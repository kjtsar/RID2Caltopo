package org.ncssar.rid2caltopo.video

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapOfflinePrepCoordinatorTest {
    @After
    fun tearDown() {
        MapOfflinePrepRuntime.resetForTesting()
    }

    @Test
    fun runtimeCancelInvokesDurableOwner() {
        var cancelled = false
        MapOfflinePrepRuntime.begin { hideDialog -> cancelled = hideDialog }

        assertTrue(MapOfflinePrepRuntime.isActive())
        MapOfflinePrepRuntime.cancelActive()

        assertTrue(cancelled)
    }

    @Test
    fun menuStatusShowsPreparingAndPercentOnlyWhileActive() {
        assertNull(offlinePrepMenuStatus(false, OfflinePrepProgress()))
        assertEquals(
            "Preparing",
            offlinePrepMenuStatus(true, OfflinePrepProgress(phase = "Preparing"))
        )
        assertEquals(
            "37%",
            offlinePrepMenuStatus(
                true,
                OfflinePrepProgress(phase = "Downloading map tiles", total = 100, completed = 37)
            )
        )
        MapOfflinePrepRuntime.finish()
        assertFalse(MapOfflinePrepRuntime.isActive())
    }
}
