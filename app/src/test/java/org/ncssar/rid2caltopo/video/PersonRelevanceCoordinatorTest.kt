package org.ncssar.rid2caltopo.video

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonRelevanceCoordinatorTest {
    @After
    fun tearDown() {
        PersonRelevanceCoordinator.closeForTests()
    }

    @Test
    fun unavailableCoordinatorReturnsNeutral() {
        val result = PersonRelevanceCoordinator.inferRgb320(input())
        assertEquals(-1f, result[0])
        assertEquals(7, result.size)
    }

    @Test
    fun personResultCrossesCompactNativeBoundary() {
        PersonRelevanceCoordinator.installEngineForTests(
            FakeEngine(
                PersonInferenceResult(
                    status = PersonInferenceStatus.PERSON,
                    personConfidence = 0.82f,
                    box = PersonNormalizedBox(0.1f, 0.2f, 0.8f, 0.9f),
                    inferenceTimeUs = 1234,
                )
            )
        )
        val result = PersonRelevanceCoordinator.inferRgb320(input())
        assertEquals(1f, result[0])
        assertEquals(0.82f, result[1])
        assertEquals(0.1f, result[2])
        assertEquals(1234f, result[6])
    }

    @Test
    fun interpreterCallsAreSerializedAcrossStreamWorkers() {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val entered = CountDownLatch(2)
        PersonRelevanceCoordinator.installEngineForTests(
            FakeEngine(PersonInferenceResult(PersonInferenceStatus.NO_PERSON)) {
                val now = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, now) }
                entered.countDown()
                Thread.sleep(20)
                active.decrementAndGet()
            }
        )
        val first = Thread { PersonRelevanceCoordinator.inferRgb320(input()) }
        val second = Thread { PersonRelevanceCoordinator.inferRgb320(input()) }
        first.start()
        second.start()
        first.join()
        second.join()
        assertTrue(entered.count == 0L)
        assertEquals(1, maximum.get())
    }

    private fun input(): ByteBuffer = ByteBuffer.allocateDirect(320 * 320 * 3)

    private class FakeEngine(
        private val result: PersonInferenceResult,
        private val beforeResult: () -> Unit = {},
    ) : PersonInferenceEngine {
        override val identity = defaultPersonModelIdentity()
        override val available = true
        override fun infer(rgb320x320: ByteBuffer): PersonInferenceResult {
            beforeResult()
            return result
        }
        override fun close() = Unit
    }
}
