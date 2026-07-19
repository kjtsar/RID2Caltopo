import Foundation

public struct RidAircraftIdentity: Sendable, Equatable {
    public let remoteID: String
    public let organization: String
    public let pilotCallsign: String
    public let droneDescription: String
    public let mappedIDOverride: String?

    public init(
        remoteID: String,
        organization: String,
        pilotCallsign: String,
        droneDescription: String,
        mappedIDOverride: String? = nil
    ) {
        self.remoteID = remoteID.trimmingCharacters(in: .whitespacesAndNewlines)
        self.organization = organization.trimmingCharacters(in: .whitespacesAndNewlines)
        self.pilotCallsign = pilotCallsign.trimmingCharacters(in: .whitespacesAndNewlines)
        self.droneDescription = droneDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        self.mappedIDOverride = mappedIDOverride?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    public var isComplete: Bool {
        !remoteID.isEmpty && !organization.isEmpty && !pilotCallsign.isEmpty && !droneDescription.isEmpty
    }

    public var mappedID: String {
        if let mappedIDOverride, !mappedIDOverride.isEmpty { return mappedIDOverride }
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_") )
        let callsign = pilotCallsign.unicodeScalars
            .filter { allowed.contains($0) }
            .map(String.init)
            .joined()
        guard !callsign.isEmpty else { return remoteID }
        return callsign + Self.modelAbbreviation(droneDescription)
    }

    public static func modelAbbreviation(_ value: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        if trimmed.caseInsensitiveCompare("Potensic Atom LT") == .orderedSame {
            return "PtnscAtm2lt"
        }

        let vowels = CharacterSet(charactersIn: "AEIOUaeiou")
        var result = ""
        for word in trimmed.split(whereSeparator: { $0.isWhitespace }) {
            for (index, scalar) in word.unicodeScalars.enumerated() {
                if index == 0 {
                    result.append(contentsOf: String(scalar).uppercased())
                } else if !vowels.contains(scalar) {
                    result.append(contentsOf: String(scalar).lowercased())
                }
            }
        }
        return String(result.suffix(10))
    }
}
