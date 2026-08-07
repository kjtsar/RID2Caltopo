package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CtDroneSpecRelayRssiTest {
    @Test
    fun directAndDroneToBridgeMeasurementsRemainIndependent() {
        val drone = CtDroneSpec("RID123")

        drone.updateLastRssi(-61, CtDroneSpec.TransportTypeEnum.BT4)
        drone.updateDroneToBridgeRssi(-78, "WIFI B")

        assertEquals(-61, drone.lastRssi)
        assertEquals(-61, drone.getLastRssi(CtDroneSpec.TransportTypeEnum.BT4))
        assertEquals(-78, drone.lastDroneToBridgeRssi)
        assertEquals("WIFI B", drone.lastDroneScoutReceptionMode)
    }
}
