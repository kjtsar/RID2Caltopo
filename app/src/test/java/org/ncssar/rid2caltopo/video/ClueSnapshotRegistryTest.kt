import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ClueSnapshotRegistryTest {
    @Test
    fun registerClueSnapshotByTitle_indexesSnapshotByTrimmedTitle() {
        val snapshots = linkedMapOf<String, ClueSnapshotRef>()

        val snapshot = registerClueSnapshotByTitle(
            snapshots = snapshots,
            title = "  Battery turnaround  ",
            thumbnail = null,
            fullImage = null
        )

        assertEquals("Battery turnaround", snapshot?.title)
        assertSame(snapshot, clueSnapshotForTitle(snapshots, "Battery turnaround"))
        assertSame(snapshot, clueSnapshotForTitle(snapshots, "  Battery turnaround  "))
    }

    @Test
    fun registerClueSnapshotByTitle_ignoresBlankTitles() {
        val snapshots = linkedMapOf<String, ClueSnapshotRef>()

        val snapshot = registerClueSnapshotByTitle(
            snapshots = snapshots,
            title = "   ",
            thumbnail = null,
            fullImage = null
        )

        assertNull(snapshot)
        assertEquals(emptyMap<String, ClueSnapshotRef>(), snapshots)
    }
}
