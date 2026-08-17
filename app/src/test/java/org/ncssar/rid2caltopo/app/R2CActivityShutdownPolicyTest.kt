package org.ncssar.rid2caltopo.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R2CActivityShutdownPolicyTest {
    @Test
    fun taskRemovalShutsDownWithoutExplicitQuitRequest() {
        assertTrue(
            shouldShutdownOnActivityDestroy(
                isPrimaryActivity = true,
                isFinishing = true,
                isChangingConfigurations = false,
            )
        )
    }

    @Test
    fun configurationRecreationDoesNotShutDownApplication() {
        assertFalse(
            shouldShutdownOnActivityDestroy(
                isPrimaryActivity = true,
                isFinishing = true,
                isChangingConfigurations = true,
            )
        )
    }

    @Test
    fun transientOrSecondaryActivityDestructionDoesNotShutDownApplication() {
        assertFalse(
            shouldShutdownOnActivityDestroy(
                isPrimaryActivity = true,
                isFinishing = false,
                isChangingConfigurations = false,
            )
        )
        assertFalse(
            shouldShutdownOnActivityDestroy(
                isPrimaryActivity = false,
                isFinishing = true,
                isChangingConfigurations = false,
            )
        )
    }
}
