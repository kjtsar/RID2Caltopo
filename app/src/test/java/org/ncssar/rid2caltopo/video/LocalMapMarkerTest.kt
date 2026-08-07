import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMapMarkerTest {
    @Test
    fun removeLocalMapMarkerById_removesOnlyMatchingMarker() {
        val markers = mutableListOf(
            localMarker(id = "one", title = "Turnaround"),
            localMarker(id = "two", title = "Landing zone"),
        )

        val removed = removeLocalMapMarkerById(markers, "one")

        assertTrue(removed)
        assertEquals(listOf("two"), markers.map { it.id })
        assertEquals("Landing zone", markers.single().title)
    }

    @Test
    fun removeLocalMapMarkerById_returnsFalseWhenMarkerIsMissing() {
        val markers = mutableListOf(localMarker(id = "one", title = "Turnaround"))

        val removed = removeLocalMapMarkerById(markers, "two")

        assertFalse(removed)
        assertEquals(listOf("one"), markers.map { it.id })
    }

    private fun localMarker(id: String, title: String): LocalMapMarker =
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
