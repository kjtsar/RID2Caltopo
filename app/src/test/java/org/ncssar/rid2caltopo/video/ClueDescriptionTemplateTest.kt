import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class ClueDescriptionTemplateTest {
    @Test
    fun buildClueDescriptionTemplate_formatsLocalTimeAndPlaceholders() {
        val timestampMs = 1_744_764_645_000L

        val template = buildClueDescriptionTemplate(
            timestampMs = timestampMs,
            zoneId = ZoneId.of("America/Los_Angeles"),
        )

        assertEquals(
            "time: 17:50:45\nfound by: \nreported to IC: yes|no\n",
            template
        )
    }
}
