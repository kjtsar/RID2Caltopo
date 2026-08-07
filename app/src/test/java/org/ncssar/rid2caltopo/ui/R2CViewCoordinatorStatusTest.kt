package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class R2CViewCoordinatorStatusTest {
    @Test
    fun coordinatorStatusDisplayText_compactsTrackerStatuses() {
        assertEquals("Tracker OK", coordinatorStatusDisplayText("Tracker link healthy"))
        assertEquals("Tracker degraded", coordinatorStatusDisplayText("Tracker link degraded"))
        assertEquals("Disabled", coordinatorStatusDisplayText("Tracker link disabled"))
        assertEquals("Not configured", coordinatorStatusDisplayText("Tracker link not configured"))
    }

    @Test
    fun coordinatorStatusDisplayText_compactsMqttStatuses() {
        assertEquals("MQTT OK", coordinatorStatusDisplayText("MQTT link healthy"))
        assertEquals("MQTT degraded", coordinatorStatusDisplayText("MQTT link degraded"))
        assertEquals("Unavailable", coordinatorStatusDisplayText("Coordinator unavailable"))
    }

    @Test
    fun coordinatorStatusDisplayText_handlesGenericAndUnknownStatuses() {
        assertEquals("Not configured", coordinatorStatusDisplayText("R2C link not configured"))
        assertEquals("Custom link state", coordinatorStatusDisplayText("Custom link state"))
    }
}
