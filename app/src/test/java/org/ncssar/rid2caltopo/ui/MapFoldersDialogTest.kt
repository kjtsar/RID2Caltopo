package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MapFoldersDialogTest {
    @Test
    fun filterMapFoldersForSearch_blankQueryReturnsOriginalFolders() {
        val folders = listOf(
            MapFolderUiState(
                folderId = "assignments",
                title = "Assignments",
                initiallyVisible = true,
                items = listOf(MapItemUiState("aa", "Assignment AA"))
            )
        )

        assertSame(folders, filterMapFoldersForSearch(folders, "   "))
    }

    @Test
    fun filterMapFoldersForSearch_matchesFolderTitleCaseInsensitive() {
        val folder = MapFolderUiState(
            folderId = "assignments",
            title = "Search Assignments",
            initiallyVisible = false,
            items = listOf(
                MapItemUiState("aa", "AA"),
                MapItemUiState("bb", "BB")
            )
        )

        val result = filterMapFoldersForSearch(listOf(folder), "assign")

        assertEquals(listOf("Search Assignments"), result.map { it.title })
        assertEquals(listOf("AA", "BB"), result.single().items.map { it.title })
    }

    @Test
    fun filterMapFoldersForSearch_matchesShapeTitleInsideHiddenFolder() {
        val folders = listOf(
            MapFolderUiState(
                folderId = "op5",
                title = "Lines & Polygons",
                initiallyVisible = false,
                items = listOf(
                    MapItemUiState("aa", "Assignment AA"),
                    MapItemUiState("bb", "Assignment BB")
                )
            )
        )

        val result = filterMapFoldersForSearch(folders, "bb")

        assertEquals(listOf("Lines & Polygons"), result.map { it.title })
        assertEquals(listOf("Assignment BB"), result.single().items.map { it.title })
    }
}
