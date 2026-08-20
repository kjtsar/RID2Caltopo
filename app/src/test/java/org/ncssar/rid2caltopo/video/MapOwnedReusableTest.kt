package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class MapOwnedReusableTest {
    @Test
    fun repeatedOverlayRebuildsReuseOneValueForTheSameMap() {
        val slot = MapOwnedReusable<Any>()
        val map = Any()
        var creations = 0

        val first = slot.getOrCreate(map) {
            creations += 1
            Any()
        }
        repeat(10_000) {
            assertSame(first, slot.getOrCreate(map) {
                creations += 1
                Any()
            })
        }

        assertEquals(1, creations)
    }

    @Test
    fun aReplacementMapReceivesItsOwnValue() {
        val slot = MapOwnedReusable<Any>()
        val first = slot.getOrCreate(Any()) { Any() }
        val second = slot.getOrCreate(Any()) { Any() }

        assertNotSame(first, second)
    }
}
