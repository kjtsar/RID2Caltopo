package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RidMappingScannerTest {
    @Test
    fun extractsAndNormalizesPrintedRemoteId() {
        assertEquals(
            listOf("1581F6Z9C24BH0036EJL"),
            extractRemoteIdCandidates("S/N: 1581f6z9c24bh0036ejl")
        )
    }

    @Test
    fun prefersTwentyCharacterRemoteIdOverOtherLabelValues() {
        assertEquals(
            "1581F6Z9C24BH0036EJL",
            extractRemoteIdCandidates(
                "Model 12345678\nRemote ID 1581F6Z9C24BH0036EJL"
            ).first()
        )
    }
}
