package org.ncssar.rid2caltopo.video

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import DroneSpecState
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.DesignatorState

class DesignatorIndicatorTest {
    @Test
    fun formatLiveState_reportsLiveUntilLagIsMaterial() {
        assertEquals("Starting", formatLiveState(null))
        assertEquals("lag:450ms", formatLiveState(450L))
    }

    @Test
    fun formatLiveState_formatsSubsecondAndSecondLag() {
        assertEquals("lag:750ms", formatLiveState(750L))
        assertEquals("lag:2.3s", formatLiveState(2_250L))
    }

    @Test
    fun indicatorPalette_usesComplementaryOutlineColors() {
        assertEquals(
            IndicatorPalette(Color(0xFFFF0000), Color(0xFF00D4FF)),
            indicatorPaletteFor(DesignatorState.Red)
        )
        assertEquals(
            IndicatorPalette(Color(0xFFFFFF00), Color(0xFF1F4BFF)),
            indicatorPaletteFor(DesignatorState.Yellow(emptyMap()))
        )
        assertEquals(
            IndicatorPalette(Color(0xFF00FF00), Color(0xFFFF4FD8)),
            indicatorPaletteFor(
                DesignatorState.Green(
                    DroneSpecState(CtDroneSpec("testRemoteId"))
                )
            )
        )
    }

    @Test
    fun designatorDetailText_omitsLongPressPromptWhenInteractionsAreDisabled() {
        assertEquals(
            "Long-press to match telemetry (mapStatus:Standalone)",
            designatorDetailText(
                designatorState = DesignatorState.Yellow(emptyMap()),
                mapStatus = "Standalone",
                interactionEnabled = true
            )
        )
        assertEquals(
            "Telemetry not attached (mapStatus:Standalone)",
            designatorDetailText(
                designatorState = DesignatorState.Yellow(emptyMap()),
                mapStatus = "Standalone",
                interactionEnabled = false
            )
        )
    }
}
