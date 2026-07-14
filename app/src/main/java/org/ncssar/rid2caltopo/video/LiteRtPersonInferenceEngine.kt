package org.ncssar.rid2caltopo.video

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.math.abs

private const val PERSON_MODEL_ASSET =
    "person_relevance/efficientdet_lite0_detection.tflite"
private const val PERSON_MODEL_SHA256 =
    "33a3b622c7cac0762f96089353cd61495f3e993968d133af7871bfc2d5396704"
private const val PERSON_MODEL_REVISION =
    "971d935f3679eabbcce7b4d3733f351d403ff2b9"
private const val PERSON_INPUT_BYTES = 1 * 320 * 320 * 3
private const val PERSON_CLASS_ID = 0
private const val MAX_DETECTIONS = 25

data class PersonModelIdentity(
    val name: String,
    val revision: String,
    val sha256: String,
    val inputTensor: String,
    val inputShape: List<Int>,
    val inputType: PersonTensorType,
    val quantizationScale: Float,
    val quantizationZeroPoint: Int,
    val runtime: String,
    val backend: String,
)

data class PersonNormalizedBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

enum class PersonInferenceStatus {
    PERSON,
    NO_PERSON,
    NEUTRAL,
}

enum class PersonInferenceFailure {
    MODEL_UNAVAILABLE,
    INPUT_INVALID,
    TENSOR_MISMATCH,
    OUTPUT_INVALID,
    RUNTIME_ERROR,
    ENGINE_CLOSED,
}

data class PersonInferenceResult(
    val status: PersonInferenceStatus,
    val personConfidence: Float = 0f,
    val box: PersonNormalizedBox? = null,
    val inferenceTimeUs: Long = 0,
    val failure: PersonInferenceFailure? = null,
) {
    init {
        require(personConfidence.isFinite() && personConfidence in 0f..1f)
        require(inferenceTimeUs >= 0)
        require((status == PersonInferenceStatus.NEUTRAL) == (failure != null))
    }
}

interface PersonInferenceEngine : AutoCloseable {
    val identity: PersonModelIdentity
    val available: Boolean

    fun infer(rgb320x320: ByteBuffer): PersonInferenceResult
}

enum class PersonTensorType {
    UINT8,
    FLOAT32,
    OTHER,
}

internal data class PersonTensorSpec(
    val index: Int,
    val name: String,
    val shape: List<Int>,
    val type: PersonTensorType,
    val quantizationScale: Float = 0f,
    val quantizationZeroPoint: Int = 0,
)

internal enum class PersonOutputRole {
    BOXES,
    CLASSES,
    SCORES,
    COUNT,
}

internal data class ResolvedPersonOutputs(
    val boxes: Int,
    val classes: Int,
    val scores: Int,
    val count: Int,
)

internal class PersonOutputBuffers {
    val boxes = Array(1) { Array(MAX_DETECTIONS) { FloatArray(4) } }
    val classes = Array(1) { FloatArray(MAX_DETECTIONS) }
    val scores = Array(1) { FloatArray(MAX_DETECTIONS) }
    val count = FloatArray(1)

    fun clear() {
        boxes[0].forEach { it.fill(Float.NaN) }
        classes[0].fill(Float.NaN)
        scores[0].fill(Float.NaN)
        count.fill(Float.NaN)
    }
}

internal interface PersonLiteRtSession : AutoCloseable {
    val inputTensors: List<PersonTensorSpec>
    val outputTensors: List<PersonTensorSpec>

    fun run(
        input: ByteBuffer,
        outputs: PersonOutputBuffers,
        resolved: ResolvedPersonOutputs,
    ): Long?
}

internal object PersonTensorContract {
    private val pinnedOutputNames = mapOf(
        "StatefulPartitionedCall:3" to PersonOutputRole.BOXES,
        "StatefulPartitionedCall:2" to PersonOutputRole.CLASSES,
        "StatefulPartitionedCall:1" to PersonOutputRole.SCORES,
        "StatefulPartitionedCall:0" to PersonOutputRole.COUNT,
    )

    fun inputValid(inputs: List<PersonTensorSpec>): Boolean {
        if (inputs.size != 1) return false
        val input = inputs.single()
        return input.name == "serving_default_images:0" &&
            input.shape == listOf(1, 320, 320, 3) &&
            input.type == PersonTensorType.UINT8 &&
            abs(input.quantizationScale - 0.0078125f) <= 0.0000001f &&
            input.quantizationZeroPoint == 127
    }

    fun resolveOutputs(outputs: List<PersonTensorSpec>): ResolvedPersonOutputs? {
        if (outputs.size != 4) return null
        val resolved = mutableMapOf<PersonOutputRole, Int>()
        for (output in outputs) {
            if (output.type != PersonTensorType.FLOAT32) return null
            val role = roleFor(output) ?: return null
            if (resolved.put(role, output.index) != null) return null
        }
        if (resolved.keys != PersonOutputRole.entries.toSet()) return null
        return ResolvedPersonOutputs(
            boxes = resolved.getValue(PersonOutputRole.BOXES),
            classes = resolved.getValue(PersonOutputRole.CLASSES),
            scores = resolved.getValue(PersonOutputRole.SCORES),
            count = resolved.getValue(PersonOutputRole.COUNT),
        )
    }

    private fun roleFor(output: PersonTensorSpec): PersonOutputRole? {
        val pinnedRole = pinnedOutputNames[output.name]
        val semanticRole = when {
            output.name.contains("box", ignoreCase = true) -> PersonOutputRole.BOXES
            output.name.contains("class", ignoreCase = true) -> PersonOutputRole.CLASSES
            output.name.contains("score", ignoreCase = true) -> PersonOutputRole.SCORES
            output.name.contains("count", ignoreCase = true) ||
                output.name.contains("num_detections", ignoreCase = true) ->
                PersonOutputRole.COUNT
            else -> null
        }
        val role = semanticRole ?: pinnedRole ?: return null
        val expectedShape = when (role) {
            PersonOutputRole.BOXES -> listOf(1, MAX_DETECTIONS, 4)
            PersonOutputRole.CLASSES, PersonOutputRole.SCORES ->
                listOf(1, MAX_DETECTIONS)
            PersonOutputRole.COUNT -> listOf(1)
        }
        return role.takeIf { output.shape == expectedShape }
    }
}

class LiteRtPersonInferenceEngine internal constructor(
    private val session: PersonLiteRtSession?,
    override val identity: PersonModelIdentity = defaultPersonModelIdentity(),
    private val initializationFailure: PersonInferenceFailure? = null,
) : PersonInferenceEngine {
    private val outputs = PersonOutputBuffers()
    private val resolved = session?.let {
        if (PersonTensorContract.inputValid(it.inputTensors)) {
            PersonTensorContract.resolveOutputs(it.outputTensors)
        } else {
            null
        }
    }
    private var closed = false

    override val available: Boolean =
        session != null && resolved != null && initializationFailure == null

    override fun infer(rgb320x320: ByteBuffer): PersonInferenceResult {
        if (closed) return neutral(PersonInferenceFailure.ENGINE_CLOSED)
        val activeSession = session ?: return neutral(
            initializationFailure ?: PersonInferenceFailure.MODEL_UNAVAILABLE
        )
        val activeResolved = resolved ?: return neutral(PersonInferenceFailure.TENSOR_MISMATCH)
        if (!rgb320x320.isDirect || rgb320x320.position() != 0 ||
            rgb320x320.remaining() != PERSON_INPUT_BYTES
        ) {
            return neutral(PersonInferenceFailure.INPUT_INVALID)
        }

        outputs.clear()
        val elapsedNs = try {
            activeSession.run(rgb320x320, outputs, activeResolved)
        } catch (_: RuntimeException) {
            return neutral(PersonInferenceFailure.RUNTIME_ERROR)
        } ?: return neutral(PersonInferenceFailure.RUNTIME_ERROR)
        if (elapsedNs < 0) return neutral(PersonInferenceFailure.RUNTIME_ERROR)

        val countValue = outputs.count[0]
        val detectionCount = countValue.toInt()
        if (!countValue.isFinite() || abs(countValue - detectionCount) > 0.001f ||
            detectionCount !in 0..MAX_DETECTIONS
        ) {
            return neutral(PersonInferenceFailure.OUTPUT_INVALID, elapsedNs)
        }

        var bestScore = 0f
        var bestBox: PersonNormalizedBox? = null
        for (index in 0 until detectionCount) {
            val classValue = outputs.classes[0][index]
            val classId = classValue.toInt()
            val score = outputs.scores[0][index]
            val rawBox = outputs.boxes[0][index]
            if (!classValue.isFinite() || abs(classValue - classId) > 0.001f ||
                !score.isFinite() || score !in 0f..1f || rawBox.any { !it.isFinite() }
            ) {
                return neutral(PersonInferenceFailure.OUTPUT_INVALID, elapsedNs)
            }
            val box = normalizedBox(rawBox)
                ?: return neutral(PersonInferenceFailure.OUTPUT_INVALID, elapsedNs)
            if (classId == PERSON_CLASS_ID && score > bestScore) {
                bestScore = score
                bestBox = box
            }
        }

        val inferenceTimeUs = elapsedNs / 1_000
        return if (bestBox != null) {
            PersonInferenceResult(
                status = PersonInferenceStatus.PERSON,
                personConfidence = bestScore,
                box = bestBox,
                inferenceTimeUs = inferenceTimeUs,
            )
        } else {
            PersonInferenceResult(
                status = PersonInferenceStatus.NO_PERSON,
                inferenceTimeUs = inferenceTimeUs,
            )
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        session?.close()
    }

    private fun normalizedBox(raw: FloatArray): PersonNormalizedBox? {
        if (raw.size != 4) return null
        val top = raw[0].coerceIn(0f, 1f)
        val left = raw[1].coerceIn(0f, 1f)
        val bottom = raw[2].coerceIn(0f, 1f)
        val right = raw[3].coerceIn(0f, 1f)
        if (right <= left || bottom <= top) return null
        return PersonNormalizedBox(left, top, right, bottom)
    }

    private fun neutral(
        failure: PersonInferenceFailure,
        elapsedNs: Long = 0,
    ) = PersonInferenceResult(
        status = PersonInferenceStatus.NEUTRAL,
        inferenceTimeUs = elapsedNs.coerceAtLeast(0) / 1_000,
        failure = failure,
    )

    companion object {
        fun create(context: Context, threadCount: Int = 1): PersonInferenceEngine {
            if (threadCount !in 1..2) {
                return LiteRtPersonInferenceEngine(
                    session = null,
                    initializationFailure = PersonInferenceFailure.MODEL_UNAVAILABLE,
                )
            }
            return try {
                val model = mapModelAsset(context)
                if (sha256(model) != PERSON_MODEL_SHA256) {
                    LiteRtPersonInferenceEngine(
                        session = null,
                        initializationFailure = PersonInferenceFailure.MODEL_UNAVAILABLE,
                    )
                } else {
                    LiteRtPersonInferenceEngine(
                        session = AndroidLiteRtSession(model, threadCount),
                        identity = defaultPersonModelIdentity(threadCount),
                    )
                }
            } catch (_: Exception) {
                LiteRtPersonInferenceEngine(
                    session = null,
                    initializationFailure = PersonInferenceFailure.MODEL_UNAVAILABLE,
                )
            } catch (_: LinkageError) {
                LiteRtPersonInferenceEngine(
                    session = null,
                    initializationFailure = PersonInferenceFailure.MODEL_UNAVAILABLE,
                )
            }
        }

        private fun mapModelAsset(context: Context): ByteBuffer =
            context.assets.openFd(PERSON_MODEL_ASSET).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        descriptor.startOffset,
                        descriptor.declaredLength,
                    )
                }
            }

        private fun sha256(buffer: ByteBuffer): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val readOnly = buffer.asReadOnlyBuffer()
            readOnly.position(0)
            val chunk = ByteArray(64 * 1024)
            while (readOnly.hasRemaining()) {
                val size = minOf(readOnly.remaining(), chunk.size)
                readOnly.get(chunk, 0, size)
                digest.update(chunk, 0, size)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

internal class AndroidLiteRtSession(
    model: ByteBuffer,
    threadCount: Int,
) : PersonLiteRtSession {
    private val interpreter = Interpreter(
        model,
        Interpreter.Options()
            .setNumThreads(threadCount.coerceIn(1, 2))
            .setUseXNNPACK(true),
    ).also { it.allocateTensors() }
    private val inputObjects = arrayOfNulls<Any>(1)
    private val outputObjects = HashMap<Int, Any>(4)

    override val inputTensors: List<PersonTensorSpec> =
        (0 until interpreter.inputTensorCount).map { index ->
            interpreter.getInputTensor(index).toPersonSpec(index)
        }
    override val outputTensors: List<PersonTensorSpec> =
        (0 until interpreter.outputTensorCount).map { index ->
            interpreter.getOutputTensor(index).toPersonSpec(index)
        }

    override fun run(
        input: ByteBuffer,
        outputs: PersonOutputBuffers,
        resolved: ResolvedPersonOutputs,
    ): Long? {
        inputObjects[0] = input
        outputObjects.clear()
        outputObjects[resolved.boxes] = outputs.boxes
        outputObjects[resolved.classes] = outputs.classes
        outputObjects[resolved.scores] = outputs.scores
        outputObjects[resolved.count] = outputs.count
        interpreter.runForMultipleInputsOutputs(inputObjects, outputObjects)
        return interpreter.lastNativeInferenceDurationNanoseconds
    }

    override fun close() = interpreter.close()
}

private fun org.tensorflow.lite.Tensor.toPersonSpec(index: Int): PersonTensorSpec {
    val quantization = quantizationParams()
    return PersonTensorSpec(
        index = index,
        name = name(),
        shape = shape().toList(),
        type = when (dataType()) {
            DataType.UINT8 -> PersonTensorType.UINT8
            DataType.FLOAT32 -> PersonTensorType.FLOAT32
            else -> PersonTensorType.OTHER
        },
        quantizationScale = quantization.scale,
        quantizationZeroPoint = quantization.zeroPoint,
    )
}

fun defaultPersonModelIdentity(threadCount: Int = 1) = PersonModelIdentity(
    name = "EfficientDet Lite0 Detection",
    revision = PERSON_MODEL_REVISION,
    sha256 = PERSON_MODEL_SHA256,
    inputTensor = "serving_default_images:0",
    inputShape = listOf(1, 320, 320, 3),
    inputType = PersonTensorType.UINT8,
    quantizationScale = 0.0078125f,
    quantizationZeroPoint = 127,
    runtime = "LiteRT 2.1.5",
    backend = "CPU/XNNPACK (${threadCount.coerceIn(1, 2)} thread)",
)
