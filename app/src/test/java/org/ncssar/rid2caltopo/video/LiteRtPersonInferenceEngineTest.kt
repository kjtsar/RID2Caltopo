package org.ncssar.rid2caltopo.video

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

class LiteRtPersonInferenceEngineTest {
    @Test
    fun personHitUsesExplicitZeroBasedCocoClassAndReturnsBestBox() {
        val session = FakeSession { outputs ->
            outputs.count[0] = 2f
            outputs.classes[0][0] = 1f
            outputs.scores[0][0] = 0.99f
            outputs.boxes[0][0] = floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f)
            outputs.classes[0][1] = 0f
            outputs.scores[0][1] = 0.82f
            outputs.boxes[0][1] = floatArrayOf(0.2f, 0.3f, 0.8f, 0.7f)
        }
        val engine = LiteRtPersonInferenceEngine(session)

        val result = engine.infer(validInput())

        assertEquals(PersonInferenceStatus.PERSON, result.status)
        assertEquals(0.82f, result.personConfidence)
        assertEquals(PersonNormalizedBox(0.3f, 0.2f, 0.7f, 0.8f), result.box)
        assertEquals(12_345L, result.inferenceTimeUs)
        assertNull(result.failure)
    }

    @Test
    fun noPersonIsValidNeutralEvidenceWithoutABox() {
        val session = FakeSession { outputs ->
            outputs.count[0] = 1f
            outputs.classes[0][0] = 2f
            outputs.scores[0][0] = 0.75f
            outputs.boxes[0][0] = floatArrayOf(0.1f, 0.2f, 0.5f, 0.6f)
        }

        val result = LiteRtPersonInferenceEngine(session).infer(validInput())

        assertEquals(PersonInferenceStatus.NO_PERSON, result.status)
        assertEquals(0f, result.personConfidence)
        assertNull(result.box)
        assertNull(result.failure)
    }

    @Test
    fun malformedOutputFailsClosedInsteadOfReturningPartialPersonEvidence() {
        val session = FakeSession { outputs ->
            outputs.count[0] = 2f
            outputs.classes[0][0] = 0f
            outputs.scores[0][0] = 0.9f
            outputs.boxes[0][0] = floatArrayOf(0.1f, 0.2f, 0.5f, 0.6f)
            outputs.classes[0][1] = Float.NaN
            outputs.scores[0][1] = 0.5f
            outputs.boxes[0][1] = floatArrayOf(0.2f, 0.2f, 0.7f, 0.7f)
        }

        val result = LiteRtPersonInferenceEngine(session).infer(validInput())

        assertEquals(PersonInferenceStatus.NEUTRAL, result.status)
        assertEquals(PersonInferenceFailure.OUTPUT_INVALID, result.failure)
        assertEquals(0f, result.personConfidence)
        assertNull(result.box)
    }

    @Test
    fun malformedInputNeverCallsInterpreter() {
        val session = FakeSession { error("must not run") }
        val engine = LiteRtPersonInferenceEngine(session)

        val heapResult = engine.infer(ByteBuffer.allocate(320 * 320 * 3))
        val shortResult = engine.infer(ByteBuffer.allocateDirect(16))
        val positioned = validInput().apply { position(1) }
        val positionedResult = engine.infer(positioned)

        assertEquals(PersonInferenceFailure.INPUT_INVALID, heapResult.failure)
        assertEquals(PersonInferenceFailure.INPUT_INVALID, shortResult.failure)
        assertEquals(PersonInferenceFailure.INPUT_INVALID, positionedResult.failure)
        assertEquals(0, session.runCount)
    }

    @Test
    fun tensorResolutionUsesNamesAndShapesAndRejectsAmbiguity() {
        val resolved = PersonTensorContract.resolveOutputs(validOutputs())
        assertEquals(0, resolved?.boxes)
        assertEquals(1, resolved?.classes)
        assertEquals(2, resolved?.scores)
        assertEquals(3, resolved?.count)

        val ambiguous = validOutputs().map {
            if (it.index == 2) it.copy(name = "detection_classes_duplicate") else it
        }
        assertNull(PersonTensorContract.resolveOutputs(ambiguous))

        val wrongShape = validOutputs().map {
            if (it.index == 0) it.copy(shape = listOf(1, 10, 4)) else it
        }
        assertNull(PersonTensorContract.resolveOutputs(wrongShape))
    }

    @Test
    fun tensorMismatchAndRuntimeFailureRemainUnavailableOrNeutral() {
        val mismatchedSession = FakeSession(
            outputsSpec = validOutputs().map {
                if (it.index == 3) it.copy(type = PersonTensorType.OTHER) else it
            },
        ) { }
        val mismatchedEngine = LiteRtPersonInferenceEngine(mismatchedSession)
        assertFalse(mismatchedEngine.available)
        assertEquals(
            PersonInferenceFailure.TENSOR_MISMATCH,
            mismatchedEngine.infer(validInput()).failure,
        )

        val failingSession = FakeSession(runFailure = IllegalStateException("runtime")) { }
        val failingResult = LiteRtPersonInferenceEngine(failingSession).infer(validInput())
        assertEquals(PersonInferenceFailure.RUNTIME_ERROR, failingResult.failure)
    }

    @Test
    fun closeIsIdempotentAndSubsequentInferenceIsNeutral() {
        val session = FakeSession { outputs -> outputs.count[0] = 0f }
        val engine = LiteRtPersonInferenceEngine(session)

        engine.close()
        engine.close()

        assertTrue(session.closed)
        assertEquals(PersonInferenceFailure.ENGINE_CLOSED, engine.infer(validInput()).failure)
    }

    @Test
    fun checkedInModelAndIdentityManifestMatchPinnedIdentity() {
        val model = File("src/main/assets/person_relevance/efficientdet_lite0_detection.tflite")
        val manifest = JSONObject(
            File("src/main/assets/person_relevance/efficientdet_lite0_detection.identity.json")
                .readText()
        )
        val identity = defaultPersonModelIdentity()

        assertTrue(model.isFile)
        assertEquals(identity.sha256, model.sha256())
        assertEquals(identity.sha256, manifest.getString("model_sha256"))
        assertEquals(identity.revision, manifest.getString("model_revision"))
        assertEquals(identity.inputTensor, manifest.getJSONObject("input").getString("name"))
        assertEquals(0, manifest.getJSONObject("class_mapping").getInt("person"))
        assertEquals("LiteRT 2.1.5", identity.runtime)
        assertEquals("CPU/XNNPACK (1 thread)", identity.backend)
    }

    private fun validInput() = ByteBuffer.allocateDirect(320 * 320 * 3)

    private fun validInputSpec() = listOf(
        PersonTensorSpec(
            index = 0,
            name = "serving_default_images:0",
            shape = listOf(1, 320, 320, 3),
            type = PersonTensorType.UINT8,
            quantizationScale = 0.0078125f,
            quantizationZeroPoint = 127,
        )
    )

    private fun validOutputs() = listOf(
        PersonTensorSpec(0, "StatefulPartitionedCall:3", listOf(1, 25, 4), PersonTensorType.FLOAT32),
        PersonTensorSpec(1, "StatefulPartitionedCall:2", listOf(1, 25), PersonTensorType.FLOAT32),
        PersonTensorSpec(2, "StatefulPartitionedCall:1", listOf(1, 25), PersonTensorType.FLOAT32),
        PersonTensorSpec(3, "StatefulPartitionedCall:0", listOf(1), PersonTensorType.FLOAT32),
    )

    private inner class FakeSession(
        override val inputTensors: List<PersonTensorSpec> = validInputSpec(),
        private val outputsSpec: List<PersonTensorSpec> = validOutputs(),
        private val runFailure: RuntimeException? = null,
        private val fill: (PersonOutputBuffers) -> Unit,
    ) : PersonLiteRtSession {
        override val outputTensors: List<PersonTensorSpec> = outputsSpec
        var runCount = 0
        var closed = false

        override fun run(
            input: ByteBuffer,
            outputs: PersonOutputBuffers,
            resolved: ResolvedPersonOutputs,
        ): Long {
            runCount++
            runFailure?.let { throw it }
            fill(outputs)
            return 12_345_678L
        }

        override fun close() {
            closed = true
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
