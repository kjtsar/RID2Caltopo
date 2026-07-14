package org.ncssar.rid2caltopo.video

import org.json.JSONArray
import org.json.JSONObject

enum class LocalPlaybackAnnotationVerdict(val wireName: String, val shortLabel: String) {
    Good("good", "Good"),
    Bad("bad", "Bad"),
    Unsure("unsure", "Unsure");

    companion object {
        fun fromWireName(value: String?): LocalPlaybackAnnotationVerdict {
            return entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: Unsure
        }
    }
}

enum class LocalPlaybackAnnotationType(val wireName: String, val shortLabel: String) {
    Person("person", "Person"),
    Team("team", "Team"),
    Tree("tree", "Tree"),
    Vehicle("vehicle", "Vehicle"),
    Artifact("artifact", "Artifact"),
    Unknown("unknown", "Unknown");

    companion object {
        fun fromWireName(value: String?): LocalPlaybackAnnotationType {
            return entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: Unknown
        }
    }
}

enum class LocalPlaybackReviewKind(val wireName: String, val shortLabel: String) {
    MissedTarget("missed_target", "Missed Target"),
    FalsePositive("false_positive", "False Positive"),
    CorrectDetection("correct_detection", "Correct Detection"),
    Unsure("unsure", "Unsure");

    companion object {
        fun fromWireName(value: String?): LocalPlaybackReviewKind {
            return entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: Unsure
        }
    }
}

enum class LocalPlaybackScenario(val wireName: String, val shortLabel: String) {
    EasyTrueTarget("easy_true_target", "Easy True Target"),
    SubtleUnderCanopy("subtle_under_canopy", "Subtle Under-Canopy"),
    TreeFalsePositive("tree_false_positive", "Tree False Positive"),
    GroundFalsePositive("ground_false_positive", "Ground False Positive"),
    VehicleFalsePositive("vehicle_false_positive", "Vehicle False Positive"),
    CameraMotionShift("camera_motion_shift", "Camera Motion Shift"),
    Other("other", "Other");

    companion object {
        fun fromWireName(value: String?): LocalPlaybackScenario? {
            return entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) }
        }
    }
}

data class LocalPlaybackPointAnnotation(
    val xNorm: Float,
    val yNorm: Float,
    val verdict: LocalPlaybackAnnotationVerdict,
    val reviewKind: LocalPlaybackReviewKind = LocalPlaybackReviewKind.Unsure,
    val objectType: LocalPlaybackAnnotationType,
    val scenario: LocalPlaybackScenario? = null,
    val note: String = "",
    val createdAtMs: Long,
    val anomalyDebugSummary: String? = null,
    val box: LocalPlaybackNormalizedBox? = null,
    val personRelevance: LocalPlaybackPersonRelevanceEvidence? = null,
)

data class LocalPlaybackNormalizedBox(
    val xMin: Float,
    val yMin: Float,
    val xMax: Float,
    val yMax: Float,
)

data class LocalPlaybackPersonRelevanceEvidence(
    val modelName: String? = null,
    val modelVersion: String? = null,
    val modelSha256: String? = null,
    val runtime: String? = null,
    val backend: String? = null,
    val rawPersonScore: Float? = null,
    val decisionThreshold: Float? = null,
    val decisionStatus: String? = null,
    val sourceDetectors: List<String>? = null,
    val candidateRelevance: Float? = null,
    val baseRelevance: Float? = null,
    val fusedRelevance: Float? = null,
    val inferenceTimeUs: Long? = null,
)

data class LocalPlaybackFrameReview(
    val sourceTimestampUs: Long,
    val annotations: MutableList<LocalPlaybackPointAnnotation> = mutableListOf(),
)

data class LocalPlaybackReviewFile(
    val schemaVersion: Int = 2,
    val sourceDisplayName: String,
    val originalSourceUri: String? = null,
    val playbackUri: String? = null,
    val annotationSidecarPath: String? = null,
    val updatedAtMs: Long,
    val frames: MutableList<LocalPlaybackFrameReview> = mutableListOf(),
)

data class PendingLocalPlaybackReviewExport(
    val designator: String,
    val suggestedFileName: String,
    val jsonText: String,
)

fun LocalPlaybackReviewFile.toJson(): JSONObject {
    val frameArray = JSONArray()
    frames.sortedBy { it.sourceTimestampUs }.forEach { frame ->
        val annotationArray = JSONArray()
        frame.annotations.forEach { annotation ->
            val annotationJson = JSONObject()
                    .put("x_norm", annotation.xNorm.toDouble())
                    .put("y_norm", annotation.yNorm.toDouble())
                    .put("verdict", annotation.verdict.wireName)
                    .put("review_kind", annotation.reviewKind.wireName)
                    .put("object_type", annotation.objectType.wireName)
                    .put("scenario", annotation.scenario?.wireName ?: JSONObject.NULL)
                    .put("note", annotation.note)
                    .put("created_at_ms", annotation.createdAtMs)
                    .put("anomaly_debug_summary", annotation.anomalyDebugSummary ?: JSONObject.NULL)
            annotation.box?.let { box -> annotationJson.put("box", box.toJson()) }
            annotation.personRelevance?.let { evidence ->
                annotationJson.put("person_relevance", evidence.toJson())
            }
            annotationArray.put(annotationJson)
        }
        frameArray.put(
            JSONObject()
                .put("source_timestamp_us", frame.sourceTimestampUs)
                .put("annotations", annotationArray)
        )
    }
    return JSONObject()
        .put("schema_version", schemaVersion)
        .put("source_display_name", sourceDisplayName)
        .put("original_source_uri", originalSourceUri ?: JSONObject.NULL)
        .put("playback_uri", playbackUri ?: JSONObject.NULL)
        .put("annotation_sidecar_path", annotationSidecarPath ?: JSONObject.NULL)
        .put("updated_at_ms", updatedAtMs)
        .put("frames", frameArray)
}

fun localPlaybackReviewFromJson(json: JSONObject): LocalPlaybackReviewFile {
    val frames = mutableListOf<LocalPlaybackFrameReview>()
    val frameArray = json.optJSONArray("frames") ?: JSONArray()
    for (frameIndex in 0 until frameArray.length()) {
        val frameJson = frameArray.optJSONObject(frameIndex) ?: continue
        val sourceTimestampUs = frameJson.optLong("source_timestamp_us", 0L)
        if (sourceTimestampUs <= 0L) continue
        val annotations = mutableListOf<LocalPlaybackPointAnnotation>()
        val annotationArray = frameJson.optJSONArray("annotations") ?: JSONArray()
        for (annotationIndex in 0 until annotationArray.length()) {
            val annotationJson = annotationArray.optJSONObject(annotationIndex) ?: continue
            annotations += LocalPlaybackPointAnnotation(
                xNorm = annotationJson.optDouble("x_norm", 0.5).toFloat().coerceIn(0f, 1f),
                yNorm = annotationJson.optDouble("y_norm", 0.5).toFloat().coerceIn(0f, 1f),
                verdict = LocalPlaybackAnnotationVerdict.fromWireName(annotationJson.optString("verdict")),
                reviewKind = LocalPlaybackReviewKind.fromWireName(annotationJson.optString("review_kind")),
                objectType = LocalPlaybackAnnotationType.fromWireName(annotationJson.optString("object_type")),
                scenario = LocalPlaybackScenario.fromWireName(annotationJson.optString("scenario")),
                note = annotationJson.optString("note", ""),
                createdAtMs = annotationJson.optLong("created_at_ms", 0L),
                anomalyDebugSummary = annotationJson.optString("anomaly_debug_summary").ifBlank { null },
                box = normalizedBoxFromJson(annotationJson.optJSONObject("box")),
                personRelevance = personRelevanceEvidenceFromJson(
                    annotationJson.optJSONObject("person_relevance")
                ),
            )
        }
        frames += LocalPlaybackFrameReview(
            sourceTimestampUs = sourceTimestampUs,
            annotations = annotations,
        )
    }
    return LocalPlaybackReviewFile(
        schemaVersion = json.optInt("schema_version", 1),
        sourceDisplayName = json.optString("source_display_name", "Captured Video"),
        originalSourceUri = json.optString("original_source_uri").ifBlank { null },
        playbackUri = json.optString("playback_uri").ifBlank { null },
        annotationSidecarPath = json.optString("annotation_sidecar_path").ifBlank { null },
        updatedAtMs = json.optLong("updated_at_ms", 0L),
        frames = frames,
    )
}

private fun LocalPlaybackNormalizedBox.toJson(): JSONObject =
    JSONObject()
        .put("x_min", xMin.toDouble())
        .put("y_min", yMin.toDouble())
        .put("x_max", xMax.toDouble())
        .put("y_max", yMax.toDouble())

private fun normalizedBoxFromJson(json: JSONObject?): LocalPlaybackNormalizedBox? {
    json ?: return null
    val xMin = json.normalizedFloat("x_min") ?: return null
    val yMin = json.normalizedFloat("y_min") ?: return null
    val xMax = json.normalizedFloat("x_max") ?: return null
    val yMax = json.normalizedFloat("y_max") ?: return null
    if (xMin >= xMax || yMin >= yMax) return null
    return LocalPlaybackNormalizedBox(xMin = xMin, yMin = yMin, xMax = xMax, yMax = yMax)
}

private fun LocalPlaybackPersonRelevanceEvidence.toJson(): JSONObject =
    JSONObject().apply {
        putOptional("model_name", modelName)
        putOptional("model_version", modelVersion)
        putOptional("model_sha256", modelSha256)
        putOptional("runtime", runtime)
        putOptional("backend", backend)
        putOptional("raw_person_score", rawPersonScore)
        putOptional("decision_threshold", decisionThreshold)
        putOptional("decision_status", decisionStatus)
        sourceDetectors?.let { detectors -> put("source_detectors", JSONArray(detectors)) }
        putOptional("candidate_relevance", candidateRelevance)
        putOptional("base_relevance", baseRelevance)
        putOptional("fused_relevance", fusedRelevance)
        putOptional("inference_time_us", inferenceTimeUs)
    }

private fun personRelevanceEvidenceFromJson(json: JSONObject?): LocalPlaybackPersonRelevanceEvidence? {
    json ?: return null
    val evidence = LocalPlaybackPersonRelevanceEvidence(
        modelName = json.optionalString("model_name"),
        modelVersion = json.optionalString("model_version"),
        modelSha256 = json.optionalString("model_sha256"),
        runtime = json.optionalString("runtime"),
        backend = json.optionalString("backend"),
        rawPersonScore = json.normalizedFloat("raw_person_score"),
        decisionThreshold = json.normalizedFloat("decision_threshold"),
        decisionStatus = json.optionalString("decision_status"),
        sourceDetectors = json.optionalStringList("source_detectors"),
        candidateRelevance = json.normalizedFloat("candidate_relevance"),
        baseRelevance = json.normalizedFloat("base_relevance"),
        fusedRelevance = json.normalizedFloat("fused_relevance"),
        inferenceTimeUs = json.nonNegativeLong("inference_time_us"),
    )
    return evidence.takeIf { it.hasValidField() }
}

private fun LocalPlaybackPersonRelevanceEvidence.hasValidField(): Boolean =
    modelName != null ||
        modelVersion != null ||
        modelSha256 != null ||
        runtime != null ||
        backend != null ||
        rawPersonScore != null ||
        decisionThreshold != null ||
        decisionStatus != null ||
        sourceDetectors?.isNotEmpty() == true ||
        candidateRelevance != null ||
        baseRelevance != null ||
        fusedRelevance != null ||
        inferenceTimeUs != null

private fun JSONObject.putOptional(key: String, value: Any?) {
    if (value != null) put(key, value)
}

private fun JSONObject.optionalString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().ifBlank { null }
}

private fun JSONObject.normalizedFloat(key: String): Float? {
    if (!has(key) || isNull(key)) return null
    val value = (opt(key) as? Number)?.toDouble() ?: return null
    if (!value.isFinite()) return null
    return value.coerceIn(0.0, 1.0).toFloat()
}

private fun JSONObject.nonNegativeLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key) as? Number ?: return null
    val asDouble = value.toDouble()
    if (!asDouble.isFinite() || asDouble < 0.0 || asDouble > Long.MAX_VALUE.toDouble()) return null
    return value.toLong()
}

private fun JSONObject.optionalStringList(key: String): List<String>? {
    if (!has(key) || isNull(key)) return null
    val array = optJSONArray(key) ?: return null
    return buildList {
        for (index in 0 until array.length()) {
            array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
}

fun buildLocalPlaybackFrameAnnotationSummary(annotations: List<LocalPlaybackPointAnnotation>): String? {
    if (annotations.isEmpty()) return null
    val missedCount = annotations.count { it.reviewKind == LocalPlaybackReviewKind.MissedTarget }
    val falsePositiveCount = annotations.count { it.reviewKind == LocalPlaybackReviewKind.FalsePositive }
    val correctCount = annotations.count { it.reviewKind == LocalPlaybackReviewKind.CorrectDetection }
    val unsureCount = annotations.count { it.reviewKind == LocalPlaybackReviewKind.Unsure }
    val primary = annotations.firstOrNull()?.reviewKind?.shortLabel ?: "Annotation"
    return buildString {
        append("${annotations.size} ")
        append(if (annotations.size == 1) primary else "notes")
        if (missedCount > 0) append(" M$missedCount")
        if (falsePositiveCount > 0) append(" F$falsePositiveCount")
        if (correctCount > 0) append(" C$correctCount")
        if (unsureCount > 0) append(" U$unsureCount")
    }
}
