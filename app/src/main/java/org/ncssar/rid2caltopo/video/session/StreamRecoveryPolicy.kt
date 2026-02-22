package org.ncssar.rid2caltopo.video.session

data class StreamRecoveryPolicy(
    val minBufferMs: Int = 2_000,
    val maxBufferMs: Int = 5_000,
    val bufferForPlaybackMs: Int = 500,
    val bufferForPlaybackAfterRebufferMs: Int = 500,
    val maxBufferingMsBeforeRestart: Long = 12_000L,
    val startupGraceMs: Long = 15_000L,
    val restartCooldownMs: Long = 8_000L,
    val restartSettleDelayMs: Long = 750L,
)
