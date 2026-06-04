import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMapMarkerTest {
    @Test
    fun removeLocalMapMarkerById_removesOnlyMatchingMarker() {
        val markers = mutableListOf(
            localMarker(id = 1L, title = "Turnaround"),
            localMarker(id = 2L, title = "Landing zone"),
        )

        val removed = removeLocalMapMarkerById(markers, 1L)

        assertTrue(removed)
        assertEquals(listOf(2L), markers.map { it.id })
        assertEquals("Landing zone", markers.single().title)
    }

    @Test
    fun removeLocalMapMarkerById_returnsFalseWhenMarkerIsMissing() {
        val markers = mutableListOf(localMarker(id = 1L, title = "Turnaround"))

        val removed = removeLocalMapMarkerById(markers, 2L)

        assertFalse(removed)
        assertEquals(listOf(1L), markers.map { it.id })
    }

    private fun localMarker(id: Long, title: String): LocalMapMarker =
        LocalMapMarker(
            id = id,
            lat = 39.153,
            lng = -121.132,
            alt = 102.0,
            title = title,
            description = "",
            createdAtMs = 1_000L,
            sourceDesignator = "1SAR7"
        )
}
