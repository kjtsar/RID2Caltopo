import Testing
@testable import R2CCore

@Test func firstManagedVideoMediaOfferStartsPeer() {
    #expect(ManagedVideoMediaOfferPolicy.decision(
        activeOfferSDP: nil,
        incomingOfferSDP: "offer-one"
    ) == .start)
}

@Test func identicalManagedVideoMediaOfferIsIgnoredAsReplay() {
    #expect(ManagedVideoMediaOfferPolicy.decision(
        activeOfferSDP: "offer-one",
        incomingOfferSDP: "offer-one"
    ) == .ignoreReplay)
}

@Test func changedManagedVideoMediaOfferReplacesActivePeer() {
    #expect(ManagedVideoMediaOfferPolicy.decision(
        activeOfferSDP: "offer-one",
        incomingOfferSDP: "offer-two"
    ) == .replaceActivePeer)
}
