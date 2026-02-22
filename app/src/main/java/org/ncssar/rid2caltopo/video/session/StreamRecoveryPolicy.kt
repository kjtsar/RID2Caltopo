package org.ncssar.rid2caltopo.video.session

data class StreamRecoveryPolicy(
    val rtspMinBufferMs: Int = 750,
    val rtspMaxBufferMs: Int = 2_000,
    val rtspBufferForPlaybackMs: Int = 250,
    val rtspBufferForPlaybackAfterRebufferMs: Int = 500,
    val hlsMinBufferMs: Int = 1_500,
    val hlsMaxBufferMs: Int = 4_500,
    val hlsBufferForPlaybackMs: Int = 500,
    val hlsBufferForPlaybackAfterRebufferMs: Int = 1_000,
    val rtspMaxBufferingMsBeforeRestart: Long = 12_000L,
    val hlsMaxBufferingMsBeforeRestart: Long = 25_000L,
    val startupGraceMs: Long = 15_000L,
    val restartCooldownMs: Long = 8_000L,
    val restartSettleDelayMs: Long = 750L,
)
