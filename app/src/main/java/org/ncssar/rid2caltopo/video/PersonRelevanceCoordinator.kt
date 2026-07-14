package org.ncssar.rid2caltopo.video

import android.content.Context
import java.nio.ByteBuffer

/** Serialized JVM boundary used only by native Person worker threads. */
object PersonRelevanceCoordinator {
    private const val STATUS_NEUTRAL = -1f
    private const val STATUS_NO_PERSON = 0f
    private const val STATUS_PERSON = 1f
    private const val RESULT_SIZE = 7

    private val lock = Any()

    @Volatile
    private var applicationContext: Context? = null
    private var engine: PersonInferenceEngine? = null
    private var engineFactory: (Context) -> PersonInferenceEngine = {
        LiteRtPersonInferenceEngine.create(it, threadCount = 1)
    }

    /** Stores context only. Model mapping and interpreter creation remain lazy. */
    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    @JvmStatic
    fun inferRgb320(rgb320x320: ByteBuffer): FloatArray = synchronized(lock) {
        val activeEngine = engine ?: applicationContext?.let { context ->
            runCatching { engineFactory(context) }.getOrNull()?.also { engine = it }
        } ?: return@synchronized neutralResult()

        val result = runCatching { activeEngine.infer(rgb320x320) }
            .getOrElse { return@synchronized neutralResult() }
        val box = result.box
        floatArrayOf(
            when (result.status) {
                PersonInferenceStatus.PERSON -> STATUS_PERSON
                PersonInferenceStatus.NO_PERSON -> STATUS_NO_PERSON
                PersonInferenceStatus.NEUTRAL -> STATUS_NEUTRAL
            },
            result.personConfidence,
            box?.left ?: 0f,
            box?.top ?: 0f,
            box?.right ?: 0f,
            box?.bottom ?: 0f,
            result.inferenceTimeUs.toFloat(),
        )
    }

    internal fun installEngineForTests(testEngine: PersonInferenceEngine?) = synchronized(lock) {
        engine?.close()
        engine = testEngine
    }

    internal fun closeForTests() = synchronized(lock) {
        engine?.close()
        engine = null
        applicationContext = null
    }

    private fun neutralResult(): FloatArray = FloatArray(RESULT_SIZE).also {
        it[0] = STATUS_NEUTRAL
    }
}
