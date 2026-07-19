import Combine
import Foundation
@preconcurrency import Network
import R2CCore

/// Receives normalized Remote ID JSON or compact raw ASTM messages over UDP.
/// This is the hardware-independent fallback when iOS cannot passively expose
/// ASTM Wi-Fi Beacon or NAN advertisements to an App Store application.
@MainActor
public final class ExternalRIDUDPReceiver: ObservableObject, RidObservationProvider {
    public enum State: Equatable, Sendable {
        case idle
        case starting
        case listening(UInt16)
        case failed(String)
    }

    public nonisolated let observations: AsyncStream<RidObservation>
    @Published public private(set) var state: State = .idle
    @Published public private(set) var observationCount = 0
    @Published public private(set) var rejectedDatagramCount = 0
    @Published public private(set) var lastAircraftID: String?
    @Published public private(set) var lastDecodeError: String?

    public let port: UInt16

    private nonisolated let continuation: AsyncStream<RidObservation>.Continuation
    private let queue = DispatchQueue(label: "org.ncssar.rid2caltopo.external-rid", qos: .userInitiated)
    private let rawAssembler = OpenDroneIDTrackAssembler()
    private var listener: NWListener?
    private var connections: [ObjectIdentifier: NWConnection] = [:]
    private var transmitterIDs: [ObjectIdentifier: UUID] = [:]

    public init(port: UInt16 = 7_654) {
        self.port = port
        let pair = AsyncStream<RidObservation>.makeStream(bufferingPolicy: .bufferingNewest(256))
        observations = pair.stream
        continuation = pair.continuation
    }

    deinit {
        continuation.finish()
    }

    public func start() async throws {
        guard listener == nil else { return }
        guard let networkPort = NWEndpoint.Port(rawValue: port) else {
            state = .failed("Invalid UDP port")
            return
        }
        let listener = try NWListener(using: .udp, on: networkPort)
        listener.stateUpdateHandler = { [weak self] newState in
            Task { @MainActor [weak self] in self?.handle(listenerState: newState) }
        }
        listener.newConnectionHandler = { [weak self] connection in
            Task { @MainActor [weak self] in self?.accept(connection) }
        }
        self.listener = listener
        state = .starting
        listener.start(queue: queue)
    }

    public func stop() async {
        listener?.cancel()
        listener = nil
        connections.values.forEach { $0.cancel() }
        connections.removeAll()
        transmitterIDs.removeAll()
        await rawAssembler.removeAllState()
        state = .idle
    }

    private func handle(listenerState: NWListener.State) {
        switch listenerState {
        case .setup, .waiting:
            state = .starting
        case .ready:
            state = .listening(port)
        case let .failed(error):
            listener = nil
            lastDecodeError = error.localizedDescription
            state = .failed(error.localizedDescription)
        case .cancelled:
            if listener == nil { state = .idle }
        @unknown default:
            state = .failed("Unknown network listener state")
        }
    }

    private func accept(_ connection: NWConnection) {
        let id = ObjectIdentifier(connection)
        connections[id] = connection
        transmitterIDs[id] = UUID()
        connection.stateUpdateHandler = { [weak self] connectionState in
            guard case .failed = connectionState else {
                if case .cancelled = connectionState {
                    Task { @MainActor [weak self] in self?.removeConnection(id) }
                }
                return
            }
            Task { @MainActor [weak self] in self?.removeConnection(id) }
        }
        connection.start(queue: queue)
        receiveNext(on: connection)
    }

    private func receiveNext(on connection: NWConnection) {
        connection.receiveMessage { [weak self] data, _, _, error in
            Task { @MainActor [weak self] in
                guard let self else { return }
                if let data, !data.isEmpty {
                    do {
                        if let observation = try await decodeDatagram(
                            data,
                            transmitterID: transmitterIDs[ObjectIdentifier(connection)] ?? UUID()
                        ) {
                            observationCount += 1
                            lastAircraftID = observation.aircraftId
                            continuation.yield(observation)
                        }
                    } catch {
                        rejectedDatagramCount += 1
                        lastDecodeError = String(describing: error)
                    }
                }
                if error == nil {
                    receiveNext(on: connection)
                } else {
                    connection.cancel()
                    removeConnection(ObjectIdentifier(connection))
                }
            }
        }
    }

    private func decodeDatagram(_ data: Data, transmitterID: UUID) async throws -> RidObservation? {
        let firstContentByte = data.first { byte in
            byte != 0x20 && byte != 0x09 && byte != 0x0A && byte != 0x0D
        }
        if firstContentByte == 0x7B {
            return try ExternalRIDObservationDecoder.decode(data)
        }

        let advertisement = try OpenDroneIDParser.parseExternalDatagram(data)
        return await rawAssembler.ingest(
            advertisement,
            transmitterID: transmitterID,
            source: .externalReceiver,
            receivedAt: Date(),
            signalStrengthDbm: nil
        )
    }

    private func removeConnection(_ id: ObjectIdentifier) {
        connections.removeValue(forKey: id)
        guard let transmitterID = transmitterIDs.removeValue(forKey: id) else { return }
        Task { await rawAssembler.removeState(for: transmitterID) }
    }
}
