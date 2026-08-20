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

    @Test
    fun localMapMarkerForArtifact_matchesTheOverlappingCopyByTitleAndPosition() {
        val markers = listOf(
            localMarker(id = "near", title = "West", lat = 39.1527405, lng = -121.1332937),
            localMarker(id = "far", title = "west", lat = 39.1535000, lng = -121.1340000),
        )

        val match = localMapMarkerForArtifact(
            markers = markers,
            artifactTitle = " west ",
            artifactLat = 39.1527406,
            artifactLng = -121.1332936,
        )

        assertEquals("near", match?.id)
    }

    @Test
    fun localMapMarkerForArtifact_rejectsANameOnlyMatchAtAnotherLocation() {
        val match = localMapMarkerForArtifact(
            markers = listOf(localMarker(id = "far", title = "West")),
            artifactTitle = "West",
            artifactLat = 40.0,
            artifactLng = -122.0,
        )

        assertEquals(null, match)
    }

    private fun localMarker(
        id: String,
        title: String,
        lat: Double = 39.153,
        lng: Double = -121.132,
    ): LocalMapMarker =
        LocalMapMarker(
            id = id,
            lat = lat,
            lng = lng,
            alt = 102.0,
            title = title,
            description = "",
            createdAtMs = 1_000L,
            sourceDesignator = "1SAR7"
        )
}
