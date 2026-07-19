import Combine
@preconcurrency import CoreBluetooth
import Foundation
import R2CCore

@MainActor
public final class BluetoothRIDScanner: NSObject, ObservableObject, RidObservationProvider {
    public enum State: Equatable, Sendable {
        case idle
        case waitingForBluetooth
        case scanning
        case unavailable(String)
    }

    public nonisolated let observations: AsyncStream<RidObservation>

    @Published public private(set) var state: State = .idle
    @Published public private(set) var observationCount = 0
    @Published public private(set) var rejectedAdvertisementCount = 0
    @Published public private(set) var lastAircraftID: String?
    @Published public private(set) var lastDecodeError: String?

    private nonisolated let continuation: AsyncStream<RidObservation>.Continuation
    private let assembler = OpenDroneIDTrackAssembler()
    private var centralManager: CBCentralManager?
    private var scanRequested = false

    private static let serviceUUID = CBUUID(string: OpenDroneIDParser.bluetoothServiceUUID)

    public override init() {
        let pair = AsyncStream<RidObservation>.makeStream(bufferingPolicy: .bufferingNewest(256))
        observations = pair.stream
        continuation = pair.continuation
        super.init()
    }

    deinit {
        continuation.finish()
    }

    public func start() async throws {
        scanRequested = true
        if centralManager == nil {
            state = .waitingForBluetooth
            centralManager = CBCentralManager(delegate: self, queue: nil)
        } else {
            startScanWhenReady()
        }
    }

    public func stop() async {
        scanRequested = false
        centralManager?.stopScan()
        if case .scanning = state {
            state = .idle
        }
    }

    private func startScanWhenReady() {
        guard scanRequested, let centralManager else { return }
        guard centralManager.state == .poweredOn else {
            updateState(for: centralManager.state)
            return
        }
        guard !centralManager.isScanning else {
            state = .scanning
            return
        }

        centralManager.scanForPeripherals(
            withServices: [Self.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
        state = .scanning
    }

    private func updateState(for bluetoothState: CBManagerState) {
        switch bluetoothState {
        case .poweredOn:
            if scanRequested {
                startScanWhenReady()
            } else {
                state = .idle
            }
        case .poweredOff:
            state = .unavailable("Bluetooth is off")
        case .unauthorized:
            state = .unavailable("Bluetooth permission denied")
        case .unsupported:
            state = .unavailable("Bluetooth scanning is unsupported")
        case .resetting:
            state = .waitingForBluetooth
        case .unknown:
            state = .waitingForBluetooth
        @unknown default:
            state = .unavailable("Unknown Bluetooth state")
        }
    }

    private func ingest(
        serviceData: Data,
        transmitterID: UUID,
        rssi: Int
    ) {
        let source: RidObservation.Source = serviceData.count > 27
            ? .bluetoothExtended
            : .bluetoothLegacy

        Task {
            do {
                let advertisement = try OpenDroneIDParser.parseBluetoothServiceData(serviceData)
                if let observation = await assembler.ingest(
                    advertisement,
                    transmitterID: transmitterID,
                    source: source,
                    receivedAt: Date(),
                    signalStrengthDbm: rssi
                ) {
                    observationCount += 1
                    lastAircraftID = observation.aircraftId
                    continuation.yield(observation)
                }
            } catch {
                rejectedAdvertisementCount += 1
                lastDecodeError = String(describing: error)
            }
        }
    }
}

extension BluetoothRIDScanner: @preconcurrency CBCentralManagerDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        updateState(for: central.state)
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard let serviceData = advertisementData[CBAdvertisementDataServiceDataKey]
            as? [CBUUID: Data],
            let remoteIDData = serviceData[Self.serviceUUID]
        else {
            return
        }

        ingest(
            serviceData: remoteIDData,
            transmitterID: peripheral.identifier,
            rssi: RSSI.intValue
        )
    }
}
