package org.ncssar.rid2caltopo.video

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaybackReviewSerializationTest {
    @Test
    fun schemaV1PointAnnotationParsesAndRoundTripsWithoutV2Evidence() {
        val legacyJson = JSONObject(
            """
            {
              "schema_version": 1,
              "source_display_name": "Legacy clip",
              "original_source_uri": "content://legacy/source",
              "playback_uri": null,
              "annotation_sidecar_path": "/tmp/legacy.review.json",
              "updated_at_ms": 1234,
              "frames": [{
                "source_timestamp_us": 4567,
                "annotations": [{
                  "x_norm": 0.25,
                  "y_norm": 0.75,
                  "verdict": "good",
                  "review_kind": "correct_detection",
                  "object_type": "person",
                  "scenario": "easy_true_target",
                  "note": "legacy point",
                  "created_at_ms": 1200,
                  "anomaly_debug_summary": null
                }]
              }]
            }
            """.trimIndent()
        )

        val parsed = localPlaybackReviewFromJson(legacyJson)
        val annotation = parsed.frames.single().annotations.single()

        assertEquals(1, parsed.schemaVersion)
        assertEquals(0.25f, annotation.xNorm)
        assertEquals(0.75f, annotation.yNorm)
        assertEquals(LocalPlaybackAnnotationType.Person, annotation.objectType)
        assertNull(annotation.box)
        assertNull(annotation.personRelevance)

        val roundTripJson = parsed.toJson()
        assertEquals(1, roundTripJson.getInt("schema_version"))
        val roundTripAnnotation = roundTripJson.annotationAt(0)
        assertFalse(roundTripAnnotation.has("box"))
        assertFalse(roundTripAnnotation.has("person_relevance"))
        assertEquals(parsed, localPlaybackReviewFromJson(roundTripJson))
    }

    @Test
    fun schemaV2BoxAndPersonEvidenceRoundTrip() {
        val review = LocalPlaybackReviewFile(
            sourceDisplayName = "Review clip",
            updatedAtMs = 9000,
            frames = mutableListOf(
                LocalPlaybackFrameReview(
                    sourceTimestampUs = 8000,
                    annotations = mutableListOf(
                        annotation(
                            box = LocalPlaybackNormalizedBox(0.1f, 0.2f, 0.4f, 0.8f),
                            personRelevance = LocalPlaybackPersonRelevanceEvidence(
                                modelName = "person-detector",
                                modelVersion = "1.2.3",
                                modelSha256 = "a".repeat(64),
                                runtime = "ONNX Runtime 1.20",
                                backend = "NNAPI",
                                rawPersonScore = 0.81f,
                                decisionThreshold = 0.65f,
                                decisionStatus = "person",
                                sourceDetectors = listOf("target_color", "color_outlier"),
                                candidateRelevance = 0.45f,
                                baseRelevance = 0.50f,
                                fusedRelevance = 0.70f,
                                inferenceTimeUs = 12_345L,
                            ),
                        )
                    ),
                )
            ),
        )

        val json = review.toJson()
        val evidenceJson = json.annotationAt(0).getJSONObject("person_relevance")

        assertEquals(2, json.getInt("schema_version"))
        assertEquals("person-detector", evidenceJson.getString("model_name"))
        assertEquals(2, evidenceJson.getJSONArray("source_detectors").length())
        assertEquals(review, localPlaybackReviewFromJson(json))
    }

    @Test
    fun malformedValuesAreDroppedAndOutOfRangeNormalizedValuesAreClamped() {
        val json = reviewJsonWithAnnotations(
            """
            [{
              "x_norm": 0.5,
              "y_norm": 0.5,
              "verdict": "unsure",
              "review_kind": "unsure",
              "object_type": "unknown",
              "created_at_ms": 1,
              "box": {"x_min": -0.2, "y_min": 0.1, "x_max": 1.4, "y_max": 0.9},
              "person_relevance": {
                "raw_person_score": 1.7,
                "decision_threshold": -0.2,
                "candidate_relevance": "invalid",
                "inference_time_us": -4
              }
            }, {
              "x_norm": 0.5,
              "y_norm": 0.5,
              "verdict": "unsure",
              "review_kind": "unsure",
              "object_type": "unknown",
              "created_at_ms": 2,
              "box": {"x_min": 0.8, "y_min": 0.1, "x_max": 0.2}
            }]
            """.trimIndent()
        )

        val annotations = localPlaybackReviewFromJson(json).frames.single().annotations
        val normalized = annotations[0]

        assertEquals(LocalPlaybackNormalizedBox(0f, 0.1f, 1f, 0.9f), normalized.box)
        assertEquals(1f, normalized.personRelevance?.rawPersonScore)
        assertEquals(0f, normalized.personRelevance?.decisionThreshold)
        assertNull(normalized.personRelevance?.candidateRelevance)
        assertNull(normalized.personRelevance?.inferenceTimeUs)
        assertNull(annotations[1].box)
    }

    @Test
    fun absentGeometryAndEvidenceStayAbsent() {
        val review = LocalPlaybackReviewFile(
            sourceDisplayName = "Point only",
            updatedAtMs = 2,
            frames = mutableListOf(
                LocalPlaybackFrameReview(1, mutableListOf(annotation()))
            ),
        )

        val annotationJson = review.toJson().annotationAt(0)
        assertFalse(annotationJson.has("box"))
        assertFalse(annotationJson.has("person_relevance"))

        val parsed = localPlaybackReviewFromJson(review.toJson()).frames.single().annotations.single()
        assertNull(parsed.box)
        assertNull(parsed.personRelevance)
        assertTrue(review.toJson().toString().contains("\"schema_version\":2"))
    }

    @Test
    fun emptyAndWhollyMalformedPersonEvidenceParseAsAbsent() {
        val json = reviewJsonWithAnnotations(
            """
            [{
              "x_norm": 0.5,
              "y_norm": 0.5,
              "verdict": "unsure",
              "review_kind": "unsure",
              "object_type": "unknown",
              "created_at_ms": 1,
              "person_relevance": {}
            }, {
              "x_norm": 0.5,
              "y_norm": 0.5,
              "verdict": "unsure",
              "review_kind": "unsure",
              "object_type": "unknown",
              "created_at_ms": 2,
              "person_relevance": {
                "model_name": "  ",
                "raw_person_score": "invalid",
                "decision_threshold": null,
                "source_detectors": "invalid",
                "candidate_relevance": {},
                "inference_time_us": -1
              }
            }]
            """.trimIndent()
        )

        val parsed = localPlaybackReviewFromJson(json)
        assertNull(parsed.frames.single().annotations[0].personRelevance)
        assertNull(parsed.frames.single().annotations[1].personRelevance)
        assertFalse(parsed.toJson().annotationAt(0).has("person_relevance"))
        assertFalse(parsed.toJson().annotationAt(1).has("person_relevance"))
    }

    private fun annotation(
        box: LocalPlaybackNormalizedBox? = null,
        personRelevance: LocalPlaybackPersonRelevanceEvidence? = null,
    ) = LocalPlaybackPointAnnotation(
        xNorm = 0.3f,
        yNorm = 0.6f,
        verdict = LocalPlaybackAnnotationVerdict.Good,
        reviewKind = LocalPlaybackReviewKind.CorrectDetection,
        objectType = LocalPlaybackAnnotationType.Person,
        createdAtMs = 7,
        box = box,
        personRelevance = personRelevance,
    )

    private fun reviewJsonWithAnnotations(annotations: String): JSONObject = JSONObject(
        """
        {
          "schema_version": 2,
          "source_display_name": "Normalization",
          "updated_at_ms": 2,
          "frames": [{"source_timestamp_us": 1, "annotations": $annotations}]
        }
        """.trimIndent()
    )

    private fun JSONObject.annotationAt(index: Int): JSONObject =
        getJSONArray("frames").getJSONObject(0).getJSONArray("annotations").getJSONObject(index)
}
