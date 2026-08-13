public enum ManagedVideoProbeQueuePolicy {
    public static let maximumBufferedBytes: UInt64 = 256 * 1024
    public static let maximumChunksPerBurst = 4

    public static func maySend(
        chunksSentInBurst: Int,
        bufferedBytes: UInt64
    ) -> Bool {
        chunksSentInBurst < maximumChunksPerBurst &&
            bufferedBytes < maximumBufferedBytes
    }
}
