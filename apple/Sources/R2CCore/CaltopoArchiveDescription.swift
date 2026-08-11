import Foundation

public enum CaltopoArchiveDescription {
    public static func build(capturedVideoURL: URL?) -> String {
        guard let capturedVideoURL else { return "" }
        return "Video Stream: \(capturedVideoURL.absoluteString)"
    }
}
