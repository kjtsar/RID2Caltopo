package org.ncssar.rid2caltopo.data;

final class ManagedVideoProbeQueuePolicy {
    static final long MAX_BUFFERED_BYTES = 256L * 1024L;
    static final int MAX_CHUNKS_PER_BURST = 4;

    private ManagedVideoProbeQueuePolicy() { }

    static boolean maySend(int chunksSentInBurst, long bufferedBytes) {
        return chunksSentInBurst < MAX_CHUNKS_PER_BURST
                && bufferedBytes < MAX_BUFFERED_BYTES;
    }
}
