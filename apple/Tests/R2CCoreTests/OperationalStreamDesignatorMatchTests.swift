import Testing
@testable import R2CCore

@Test func missingManufacturerTokenSuggestsConfiguredDroneDesignator() {
    let closest = OperationalStreamDesignatorMatch.closestCandidateID(
        streamDesignator: "1sar1001mtrc4td",
        candidates: [
            .init(id: "RID-01", designator: "1sar1001djmtrc4td"),
            .init(id: "RID-02", designator: "1sar1002djmtrc4td"),
            .init(id: "RID-03", designator: "1sar7djmn4pr"),
        ]
    )

    #expect(closest == "RID-01")
}

@Test func closestDesignatorMatchingIsCaseInsensitiveAndTrimmed() {
    let closest = OperationalStreamDesignatorMatch.closestCandidateID(
        streamDesignator: "  1SAR1001MTRC4TD ",
        candidates: [
            .init(id: "RID-01", designator: "1sar1001mtrc4td"),
            .init(id: "RID-02", designator: "1sar1002mtrc4td"),
        ]
    )

    #expect(closest == "RID-01")
}

@Test func equallyCloseDesignatorsDoNotCreateArbitrarySuggestion() {
    let closest = OperationalStreamDesignatorMatch.closestCandidateID(
        streamDesignator: "1sar1001djmtrc4td",
        candidates: [
            .init(id: "RID-01", designator: "1sar1002djmtrc4td"),
            .init(id: "RID-02", designator: "1sar1003djmtrc4td"),
        ]
    )

    #expect(closest == nil)
}

@Test func confirmedDroneTargetsOnlyUnambiguousLiveUnpairedStream() {
    #expect(OperationalStreamDesignatorMatch.automaticPairingStreamID(
        confirmedCandidateID: "RID-01",
        activeCandidateIDs: ["RID-01"],
        liveUnpairedStreamIDs: ["1sar7mn4pr"]
    ) == "1sar7mn4pr")
    #expect(OperationalStreamDesignatorMatch.automaticPairingStreamID(
        confirmedCandidateID: "RID-01",
        activeCandidateIDs: ["RID-01", "RID-02"],
        liveUnpairedStreamIDs: ["1sar7mn4pr"]
    ) == nil)
    #expect(OperationalStreamDesignatorMatch.automaticPairingStreamID(
        confirmedCandidateID: "RID-01",
        activeCandidateIDs: ["RID-01"],
        liveUnpairedStreamIDs: ["stream-one", "stream-two"]
    ) == nil)
}
