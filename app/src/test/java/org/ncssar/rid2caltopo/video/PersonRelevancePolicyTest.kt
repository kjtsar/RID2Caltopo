import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.video.anomaly.AnomalyConfig
import org.ncssar.rid2caltopo.video.anomaly.PersonRelevanceMode
import org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridge
import org.ncssar.rid2caltopo.video.ffmpeg.toFfmpegBridgeMode

class PersonRelevancePolicyTest {
    @Test
    fun operatorModes_mapToNativeModes() {
        assertEquals(FfmpegBridge.PersonRelevanceMode.OFF, PersonRelevanceMode.Off.toFfmpegBridgeMode())
        assertEquals(FfmpegBridge.PersonRelevanceMode.SHADOW, PersonRelevanceMode.Evaluate.toFfmpegBridgeMode())
        assertEquals(
            FfmpegBridge.PersonRelevanceMode.POSITIVE_ONLY,
            PersonRelevanceMode.Assist.toFfmpegBridgeMode(),
        )
    }

    @Test
    fun policyDeduplication_includesPersonRelevanceMode() {
        val nativeConfig = AnomalyConfig(enabled = true).toNativeConfig()
        val evaluate = AnomalyPolicyUpdate(
            designator = "1SAR7",
            thermalPaused = false,
            personRelevanceMode = PersonRelevanceMode.Evaluate,
            config = nativeConfig,
        )
        val assist = evaluate.copy(personRelevanceMode = PersonRelevanceMode.Assist)

        assertTrue(anomalyPolicyChanged(null, evaluate))
        assertFalse(anomalyPolicyChanged(evaluate, evaluate.copy()))
        assertTrue(anomalyPolicyChanged(evaluate, assist))
    }
}
