import Testing
@testable import R2CCore

@Test func thumbnailRefreshIntervalDefaultsClampsAndFormatsDecimalSeconds() {
    #expect(OperationalThumbnailRefreshInterval.normalized(nil) == 5.0)
    #expect(OperationalThumbnailRefreshInterval.normalized(.nan) == 5.0)
    #expect(OperationalThumbnailRefreshInterval.normalized(0.1) == 0.5)
    #expect(OperationalThumbnailRefreshInterval.normalized(90.0) == 60.0)
    #expect(OperationalThumbnailRefreshInterval.normalized(2.26) == 2.3)
    #expect(OperationalThumbnailRefreshInterval.formatted(5.0) == "5.0")
}

@Test func thumbnailRefreshStepperUsesTenthsThroughOneSecondThenHalfSeconds() {
    #expect(OperationalThumbnailRefreshInterval.decremented(1.5) == 1.0)
    #expect(OperationalThumbnailRefreshInterval.decremented(1.0) == 0.9)
    #expect(OperationalThumbnailRefreshInterval.decremented(0.9) == 0.8)
    #expect(OperationalThumbnailRefreshInterval.decremented(0.6) == 0.5)
    #expect(OperationalThumbnailRefreshInterval.decremented(0.5) == 0.5)

    #expect(OperationalThumbnailRefreshInterval.incremented(0.5) == 0.6)
    #expect(OperationalThumbnailRefreshInterval.incremented(0.9) == 1.0)
    #expect(OperationalThumbnailRefreshInterval.incremented(1.0) == 1.5)
    #expect(OperationalThumbnailRefreshInterval.incremented(60.0) == 60.0)
}
