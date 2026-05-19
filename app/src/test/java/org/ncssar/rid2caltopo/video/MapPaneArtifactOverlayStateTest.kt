package org.ncssar.rid2caltopo.video

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MapPaneArtifactOverlayStateTest {
    @Test
    fun buildArtifactOverlayState_ignoresItemsOutsideRepresentedFolders() {
        val representedFolder = folderFeature("folder-visible", "Search Segment")
        val visibleLine = lineFeature("line-visible", "Represented Line", "folder-visible")
        val caltopoSystemLine = lineFeature("line-system", "System Line", "Lines & Polygons")
        val folderlessLine = lineFeature("line-folderless", "Folderless Line", "")

        val state = buildArtifactOverlayState(
            listOf(representedFolder, visibleLine, caltopoSystemLine, folderlessLine)
        )

        assertEquals(listOf("line-visible"), state.lines.map { it.id })
    }

    @Test
    fun buildArtifactOverlayState_stillHonorsHiddenRepresentedFolders() {
        val representedFolder = folderFeature("folder-hidden", "Archived Tracks")
        val hiddenLine = lineFeature("line-hidden", "Archived Line", "folder-hidden")

        val state = buildArtifactOverlayState(
            listOf(representedFolder, hiddenLine),
            hiddenFolderIds = setOf("folder-hidden")
        )

        assertEquals(0, state.lines.size)
    }

    private fun folderFeature(id: String, title: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put(
                "properties",
                JSONObject()
                    .put("class", "Folder")
                    .put("title", title)
            )

    private fun lineFeature(id: String, title: String, folderId: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put(
                "properties",
                JSONObject()
                    .put("class", "Shape")
                    .put("title", title)
                    .put("folderId", folderId)
                    .put("stroke", "#FF5A1F")
            )
            .put(
                "geometry",
                JSONObject()
                    .put("type", "LineString")
                    .put(
                        "coordinates",
                        JSONArray()
                            .put(JSONArray().put(-122.0).put(37.0))
                            .put(JSONArray().put(-122.1).put(37.1))
                    )
            )
}
