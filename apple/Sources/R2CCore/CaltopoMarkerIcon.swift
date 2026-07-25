import Foundation

public enum CaltopoMarkerIcon {
    public static func url(symbol: String, colorHex: String?) -> URL? {
        let normalizedSymbol = symbol.trimmingCharacters(in: .whitespacesAndNewlines)
        let effectiveSymbol = normalizedSymbol.isEmpty ? "point" : normalizedSymbol
        let normalizedColor = colorHex?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "#", with: "")
        let color = normalizedColor.flatMap {
            !$0.isEmpty && !$0.caseInsensitiveCompare("null").isOrderedSame ? $0 : nil
        }
        let configuration = color.map { "\(effectiveSymbol),\($0)" } ?? effectiveSymbol
        var components = URLComponents(string: "https://caltopo.com/icon@2x.png")
        components?.queryItems = [URLQueryItem(name: "cfg", value: configuration)]
        return components?.url
    }
}

private extension ComparisonResult {
    var isOrderedSame: Bool { self == .orderedSame }
}
