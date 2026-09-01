package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class H264ArchiveAccessUnitAssemblerTest {
    @Test
    fun seiIsPreservedInThePictureAccessUnitAtTheSharedTimestamp() {
        val assembler = H264ArchiveAccessUnitAssembler()
        val sei = avccNal(type = 6, payload = byteArrayOf(0xf5.toByte(), 0x62, 0x09))
        val picture = avccNal(type = 1, payload = byteArrayOf(0x18, 0x22, 0x33))

        assertTrue(assembler.offer(sample(sei, ptsUs = 16_000L, flags = 0)).isEmpty())
        val output = assembler.offer(sample(picture, ptsUs = 16_000L, flags = 1)).single()

        assertEquals(16_000L, output.presentationTimeUs)
        assertEquals(1, output.flags)
        assertArrayEquals(sei + picture, output.data)
        assertEquals(listOf(6, 1), output.data.avccNalUnitTypes())
        assertEquals(1L, assembler.mergedSeiCount)
        assertEquals(0L, assembler.discardedSeiCount)
    }

    @Test
    fun multipleSeiNalsRemainOrderedBeforeTheirPicture() {
        val assembler = H264ArchiveAccessUnitAssembler()
        val firstSei = avccNal(type = 6, payload = byteArrayOf(1))
        val secondSei = avccNal(type = 9, payload = byteArrayOf(0xf0.toByte())) +
            avccNal(type = 6, payload = byteArrayOf(2))
        val idrPicture = avccNal(type = 5, payload = byteArrayOf(3))

        assembler.offer(sample(firstSei, ptsUs = 20_000L, flags = 0))
        assembler.offer(sample(secondSei, ptsUs = 20_000L, flags = 0))
        val output = assembler.offer(sample(idrPicture, ptsUs = 20_000L, flags = 1)).single()

        assertArrayEquals(firstSei + secondSei + idrPicture, output.data)
        assertEquals(listOf(6, 9, 6, 5), output.data.avccNalUnitTypes())
        assertEquals(2L, assembler.mergedSeiCount)
    }

    @Test
    fun seiIsAttachedToFollowingPictureEvenWhenExtractorTimestampsDiffer() {
        val assembler = H264ArchiveAccessUnitAssembler()
        val sei = avccNal(type = 6, payload = byteArrayOf(1))
        val laterPicture = avccNal(type = 1, payload = byteArrayOf(2))

        assembler.offer(sample(sei, ptsUs = 10_000L, flags = 0))
        val output = assembler.offer(sample(laterPicture, ptsUs = 20_000L, flags = 0))

        assertEquals(1, output.size)
        assertArrayEquals(sei + laterPicture, output.single().data)
        assertEquals(20_000L, output.single().presentationTimeUs)
    }

    @Test
    fun trailingSeiIsDiscardedInsteadOfBecomingTimedMp4Sample() {
        val assembler = H264ArchiveAccessUnitAssembler()
        val sei = avccNal(type = 6, payload = byteArrayOf(1))

        assertTrue(assembler.offer(sample(sei, ptsUs = 10_000L, flags = 0)).isEmpty())
        assertTrue(assembler.discardTrailingSei())
        assertTrue(!assembler.discardTrailingSei())
        assertEquals(1L, assembler.discardedSeiCount)
    }

    @Test
    fun malformedAvccSamplePassesThroughWithoutBeingBuffered() {
        val assembler = H264ArchiveAccessUnitAssembler()
        val malformed = byteArrayOf(0, 0, 0, 8, 6, 1)

        val output = assembler.offer(sample(malformed, ptsUs = 1L, flags = 0)).single()

        assertArrayEquals(malformed, output.data)
        assertEquals(null, malformed.avccNalUnitTypes())
    }

    @Test
    fun cadenceProducesExactThirtyFramesPerSecondWithoutAccumulatedDrift() {
        val cadence = H264ArchiveCadence()
        val timestamps = List(31) { cadence.nextPresentationTimeUs(segmentOffsetUs = 500_000L) }

        assertEquals(500_000L, timestamps[0])
        assertEquals(533_333L, timestamps[1])
        assertEquals(566_666L, timestamps[2])
        assertEquals(1_500_000L, timestamps[30])
        assertEquals(1_533_333L, cadence.endOffsetUs(segmentOffsetUs = 500_000L))
    }

    private fun sample(data: ByteArray, ptsUs: Long, flags: Int) =
        H264ArchiveAccessUnitAssembler.Sample(data, ptsUs, flags)

    private fun avccNal(type: Int, payload: ByteArray): ByteArray {
        val nalSize = payload.size + 1
        return byteArrayOf(
            (nalSize ushr 24).toByte(),
            (nalSize ushr 16).toByte(),
            (nalSize ushr 8).toByte(),
            nalSize.toByte(),
            type.toByte(),
        ) + payload
    }
}
