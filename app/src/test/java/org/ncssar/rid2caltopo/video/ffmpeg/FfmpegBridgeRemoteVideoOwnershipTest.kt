package org.ncssar.rid2caltopo.video.ffmpeg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegBridgeRemoteVideoOwnershipTest {
    @Test
    fun liveShareCanPreemptThumbnail() {
        assertTrue(
            FfmpegBridge.remoteVideoFramePurposeCanReplace(
                FfmpegBridge.RemoteVideoFramePurpose.THUMBNAIL,
                FfmpegBridge.RemoteVideoFramePurpose.LIVE_SHARE,
            )
        )
    }

    @Test
    fun thumbnailCannotReplaceLiveShare() {
        assertFalse(
            FfmpegBridge.remoteVideoFramePurposeCanReplace(
                FfmpegBridge.RemoteVideoFramePurpose.LIVE_SHARE,
                FfmpegBridge.RemoteVideoFramePurpose.THUMBNAIL,
            )
        )
    }

    @Test
    fun equalPriorityCannotReplaceExistingOwner() {
        assertFalse(
            FfmpegBridge.remoteVideoFramePurposeCanReplace(
                FfmpegBridge.RemoteVideoFramePurpose.LIVE_SHARE,
                FfmpegBridge.RemoteVideoFramePurpose.LIVE_SHARE,
            )
        )
    }
}
