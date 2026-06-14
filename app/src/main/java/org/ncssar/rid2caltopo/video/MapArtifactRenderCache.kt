package org.ncssar.rid2caltopo.video

import org.json.JSONObject

class MapArtifactRenderCache {
    private data class FeatureChange(
        val version: Long,
        val featureId: String,
        val feature: JSONObject?
    )

    private var mapName: String? = null
    private val featureChanges = ArrayDeque<FeatureChange>()
    val featuresById: LinkedHashMap<String, JSONObject> = LinkedHashMap()
    var featureVersion: Long = 0L
        private set
    var overlayState: Any? = null
        private set

    fun resetIfMapChanged(nextMapName: String?): Boolean {
        if (mapName == nextMapName) return false
        mapName = nextMapName
        featuresById.clear()
        featureChanges.clear()
        featureVersion++
        overlayState = null
        return true
    }

    fun replace(features: Map<String, JSONObject>, overlayState: Any) {
        featuresById.clear()
        featuresById.putAll(features)
        featureChanges.clear()
        featureVersion++
        this.overlayState = overlayState
    }

    fun removeFeature(featureId: String) {
        featuresById.remove(featureId)
        recordFeatureChange(featureId, null)
    }

    fun putFeature(featureId: String, feature: JSONObject) {
        featuresById[featureId] = feature
        recordFeatureChange(featureId, feature)
    }

    fun updateOverlay(overlayState: Any) {
        this.overlayState = overlayState
    }

    fun mergedHydrationFeatures(
        hydratedFeatures: Map<String, JSONObject>,
        hydrationStartVersion: Long
    ): LinkedHashMap<String, JSONObject> {
        val merged = LinkedHashMap<String, JSONObject>()
        merged.putAll(hydratedFeatures)
        featureChanges
            .asSequence()
            .filter { it.version > hydrationStartVersion }
            .forEach { change ->
                if (change.feature == null) {
                    merged.remove(change.featureId)
                } else {
                    merged[change.featureId] = change.feature
                }
            }
        return merged
    }

    private fun recordFeatureChange(featureId: String, feature: JSONObject?) {
        featureVersion++
        featureChanges.addLast(FeatureChange(featureVersion, featureId, feature))
        while (featureChanges.size > MAX_FEATURE_CHANGES) {
            featureChanges.removeFirst()
        }
    }

    private companion object {
        const val MAX_FEATURE_CHANGES = 256
    }
}
