public enum ManagedVideoProbeQueuePolicy {
    // The former 256 KiB window left the TURN/SCTP path in slow start for the
    // entire four-second probe and substantially under-reported fast links.
    // Keep the queue bounded, but allow enough data in flight to fill a routed
    // path with a meaningful bandwidth-delay product.
    public static let maximumBufferedBytes: UInt64 = 1024 * 1024
    public static let maximumChunksPerBurst = 16

    public static func maySend(
        chunksSentInBurst: Int,
        bufferedBytes: UInt64
    ) -> Bool {
        chunksSentInBurst < maximumChunksPerBurst &&
            bufferedBytes < maximumBufferedBytes
    }
}
