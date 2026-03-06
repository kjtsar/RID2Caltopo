package org.ncssar.rid2caltopo.video.session

data class StreamRecoveryPolicy(
    val rtspMinBufferMs: Int = 750,
    val rtspMaxBufferMs: Int = 2_000,
    val rtspBufferForPlaybackMs: Int = 250,
    val rtspBufferForPlaybackAfterRebufferMs: Int = 500,
    val rtspTimeoutMs: Int = 4_000,
    val hlsMinBufferMs: Int = 2_500,
    val hlsMaxBufferMs: Int = 8_000,
    val hlsBufferForPlaybackMs: Int = 1_000,
    val hlsBufferForPlaybackAfterRebufferMs: Int = 2_000,
    val rtspMaxBufferingMsBeforeRestart: Long = 12_000L,
    val hlsMaxBufferingMsBeforeRestart: Long = 3_000L,
    val rtspStartupGraceMs: Long = 15_000L,
    val hlsStartupGraceMs: Long = 25_000L,
    val restartCooldownMs: Long = 8_000L,
    val restartSettleDelayMs: Long = 500L,
)
