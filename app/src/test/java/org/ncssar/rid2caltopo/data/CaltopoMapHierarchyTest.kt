package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaltopoMapHierarchyTest {
    @Test
    fun parseMapHierarchy_usesUserAccountMapRelFolderWhenMapFeatureIsFolderless() {
        val response = JSONObject()
            .put(
                "accounts",
                JSONArray().put(
                    feature(
                        id = "C15CTG",
                        properties = JSONObject()
                            .put("title", "NCSSAR Training")
                            .put("class", "UserAccount")
                    )
                )
            )
            .put(
                "features",
                JSONArray()
                    .put(
                        feature(
                            id = "UB270KQT",
                            properties = JSONObject()
                                .put("accountId", "C15CTG")
                                .put("title", "Drone Team")
                                .put("class", "UserFolder")
                        )
                    )
                    .put(
                        feature(
                            id = "NLD8SB1",
                            properties = JSONObject()
                                .put("accountId", "5KC6GP")
                                .put("title", "YCSSARMATest2")
                                .put("class", "CollaborativeMap")
                                .put("updated", 1780709504524L)
                        )
                    )
            )
            .put(
                "rels",
                JSONArray().put(
                    feature(
                        id = "709372e7-b633-4922-8580-65730ed10f65",
                        properties = JSONObject()
                            .put("accountId", "C15CTG")
                            .put("mapId", "NLD8SB1")
                            .put("title", "YCSSARMATest2")
                            .put("class", "UserAccountMapRel")
                            .put("folderId", "UB270KQT")
                    )
                )
            )

        val roots = parseMapHierarchy(response)

        val training = roots.singleDirectory("NCSSAR Training")
        val droneTeam = training.children.singleDirectory("Drone Team")
        assertEquals(listOf(CaltopoNode.MapNode("NLD8SB1", "YCSSARMATest2", 1780709504524L)), droneTeam.children)
        assertTrue(training.children.none { it is CaltopoNode.MapNode && it.title == "YCSSARMATest2" })
    }

    private fun List<CaltopoNode>.singleDirectory(title: String): CaltopoNode.Directory =
        filterIsInstance<CaltopoNode.Directory>().single { it.title == title }

    private fun feature(id: String, properties: JSONObject): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "Feature")
            .put("properties", properties)
}
