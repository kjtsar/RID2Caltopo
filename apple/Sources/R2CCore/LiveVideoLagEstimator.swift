import Foundation

/// Mirrors Android's live-video source-clock estimator. The smallest observed
/// source-to-device clock offset is treated as the live edge; subsequent drift
/// behind that edge is reported as playback lag.
public struct LiveVideoLagEstimator: Sendable {
    private var sourceClockOffsetMilliseconds: Int64?
    private var lastSourceTimestampMicroseconds: Int64?

    public init() {}

    public mutating func reset() {
        sourceClockOffsetMilliseconds = nil
        lastSourceTimestampMicroseconds = nil
    }

    public mutating func observe(
        sourceTimestampMicroseconds: Int64?,
        observedAtMilliseconds: Int64,
        resetThresholdMilliseconds: Int64 = 1_000
    ) -> Int64? {
        guard let sourceTimestampMicroseconds, sourceTimestampMicroseconds > 0 else {
            return nil
        }

        let sourceTimestampMilliseconds = sourceTimestampMicroseconds / 1_000
        let lastSourceTimestampMilliseconds = lastSourceTimestampMicroseconds
            .flatMap { $0 > 0 ? $0 / 1_000 : nil }
        let sourceClockReset = lastSourceTimestampMilliseconds.map {
            sourceTimestampMilliseconds + resetThresholdMilliseconds < $0
        } ?? false
        let anchoredOffset = sourceClockReset ? nil : sourceClockOffsetMilliseconds
        let observedOffset = observedAtMilliseconds - sourceTimestampMilliseconds
        let nextOffset = anchoredOffset.map { min($0, observedOffset) } ?? observedOffset
        sourceClockOffsetMilliseconds = nextOffset
        lastSourceTimestampMicroseconds = sourceTimestampMicroseconds
        return max(0, observedAtMilliseconds - (nextOffset + sourceTimestampMilliseconds))
    }

    public static func quantize(milliseconds: Int64) -> Int64 {
        guard milliseconds > 0 else { return 0 }
        let bucket: Int64 = if milliseconds < 1_000 {
            100
        } else if milliseconds < 10_000 {
            250
        } else {
            500
        }
        return max(bucket, ((milliseconds + bucket / 2) / bucket) * bucket)
    }

    public static func label(milliseconds: Int64?) -> String {
        guard let milliseconds else { return "Starting" }
        if milliseconds < 1_000 { return "lag:\(milliseconds)ms" }
        return String(format: "lag:%.1fs", Double(milliseconds) / 1_000)
    }
}

/// Estimates end-to-end live delay when MediaMTX supplies the RTMP publish
/// epoch. Unlike source-clock drift alone, this includes latency already
/// present before the first decoded frame reaches the app.
public struct LiveVideoSessionLagEstimator: Sendable {
    private var publisherStartedAtMilliseconds: Int64?
    private var firstSourceTimestampMicroseconds: Int64?
    private var sourceTimelineStartsWithPublisher = false

    public init() {}

    public mutating func publisherStarted(atMilliseconds: Int64) {
        publisherStartedAtMilliseconds = atMilliseconds
        firstSourceTimestampMicroseconds = nil
        sourceTimelineStartsWithPublisher = false
    }

    public mutating func reset() {
        publisherStartedAtMilliseconds = nil
        firstSourceTimestampMicroseconds = nil
        sourceTimelineStartsWithPublisher = false
    }

    public mutating func observe(
        sourceTimestampMicroseconds: Int64?,
        observedAtMilliseconds: Int64
    ) -> Int64? {
        guard let publisherStartedAtMilliseconds,
              let sourceTimestampMicroseconds,
              sourceTimestampMicroseconds >= 0
        else { return nil }
        if firstSourceTimestampMicroseconds == nil {
            firstSourceTimestampMicroseconds = sourceTimestampMicroseconds
            let sessionElapsedMicroseconds = max(
                0,
                observedAtMilliseconds - publisherStartedAtMilliseconds
            ) * 1_000
            // RTMP publishers normally start their media timestamps at zero.
            // Preserve that useful epoch: the first RTSP frame can already be
            // hundreds of milliseconds into the stream. Normalizing it away
            // incorrectly counts keyframe/join time as permanent playback lag.
            sourceTimelineStartsWithPublisher = sourceTimestampMicroseconds
                <= sessionElapsedMicroseconds + 5_000_000
        }
        guard let firstSourceTimestampMicroseconds else { return nil }
        if sourceTimestampMicroseconds + 1_000_000 < firstSourceTimestampMicroseconds {
            self.firstSourceTimestampMicroseconds = sourceTimestampMicroseconds
        }
        let sourceOrigin = sourceTimelineStartsWithPublisher
            ? 0
            : (self.firstSourceTimestampMicroseconds ?? sourceTimestampMicroseconds)
        let sourceElapsedMilliseconds = max(0, (sourceTimestampMicroseconds - sourceOrigin) / 1_000)
        let sessionElapsedMilliseconds = max(0, observedAtMilliseconds - publisherStartedAtMilliseconds)
        return max(0, sessionElapsedMilliseconds - sourceElapsedMilliseconds)
    }
}
