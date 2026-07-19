import Foundation

/// Platform radio adapters expose observations through this contract so the
/// tracking pipeline does not depend on CoreBluetooth or a particular Wi-Fi API.
public protocol RidObservationProvider: Sendable {
    var observations: AsyncStream<RidObservation> { get }

    func start() async throws
    func stop() async
}
