import org.junit.Assert.assertEquals
import org.junit.Test
import org.ncssar.rid2caltopo.video.StreamInfo
import org.ncssar.rid2caltopo.video.StreamState

class StreamsViewModelResyncTest {
    @Test
    fun chooseResyncSnapshot_prefersLastSyncedMapWhenFlowSnapshotIsMomentarilyEmpty() {
        val lastSynced = mapOf(
            "1SAR7n" to StreamInfo(
                designator = "1SAR7n",
                sourcePath = "1SAR7n",
                state = StreamState.LIVE,
                revision = 2L,
            )
        )

        val chosen = chooseResyncSnapshot(
            lastSyncedStreams = lastSynced,
            latestFlowValue = emptyMap(),
        )

        assertEquals(lastSynced, chosen)
    }

    @Test
    fun chooseResyncSnapshot_usesLatestFlowSnapshotWhenItIsAvailable() {
        val lastSynced = mapOf(
            "1SAR7n" to StreamInfo(
                designator = "1SAR7n",
                sourcePath = "1SAR7n",
                state = StreamState.LIVE,
                revision = 2L,
            )
        )
        val latestFlow = mapOf(
            "1SAR7n" to StreamInfo(
                designator = "1SAR7n",
                sourcePath = "1SAR7n",
                state = StreamState.LIVE,
                revision = 3L,
            )
        )

        val chosen = chooseResyncSnapshot(
            lastSyncedStreams = lastSynced,
            latestFlowValue = latestFlow,
        )

        assertEquals(latestFlow, chosen)
    }
}
