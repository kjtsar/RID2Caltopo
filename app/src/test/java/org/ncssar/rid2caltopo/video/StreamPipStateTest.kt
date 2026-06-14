import org.junit.Assert.assertEquals
import org.junit.Test

class StreamPipStateTest {
    @Test
    fun clampStreamPipInsetFraction_keepsPanelUsable() {
        assertEquals(0.22f, clampStreamPipInsetFraction(0.05f), 0.0001f)
        assertEquals(0.33f, clampStreamPipInsetFraction(0.33f), 0.0001f)
        assertEquals(0.55f, clampStreamPipInsetFraction(0.90f), 0.0001f)
    }

    @Test
    fun nextStreamPipEnabledToggle_exitsEditorWhenTurningOff() {
        val current = StreamPipUiState(
            enabled = true,
            insetFraction = 0.33f,
            editorMode = true
        )

        assertEquals(
            StreamPipUiState(enabled = false, insetFraction = 0.33f, editorMode = false),
            current.withEnabled(false)
        )
    }

    @Test
    fun nextStreamPipEditorLongPress_togglesEditorModeWhenEnabled() {
        val viewing = StreamPipUiState(
            enabled = true,
            insetFraction = 0.33f,
            editorMode = false
        )
        val editing = StreamPipUiState(
            enabled = true,
            insetFraction = 0.33f,
            editorMode = true
        )

        assertEquals(editing, viewing.withEditorLongPress())
        assertEquals(viewing, editing.withEditorLongPress())
    }

    @Test
    fun streamPipUiState_normalizesPersistedInsetFraction() {
        val state = StreamPipUiState.fromPersisted(
            enabled = true,
            insetFraction = Float.NaN
        )

        assertEquals(
            StreamPipUiState(
                enabled = true,
                insetFraction = STREAM_PIP_DEFAULT_INSET_FRACTION,
                editorMode = false
            ),
            state
        )
    }
}
