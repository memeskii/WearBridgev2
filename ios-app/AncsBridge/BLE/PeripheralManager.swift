import CoreBluetooth
import Combine
import UIKit
import os.log

/// Manages central BLE watch discovery for the WearBridge companion app.
///
/// Delegates all peripheral GATT server and advertising duties to `BLEServerManager.shared`
/// to guarantee only ONE CBPeripheralManager runs in the app.
class PeripheralManager: NSObject, ObservableObject {

    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "PeripheralManager")

    // Single consolidated GATT peripheral manager
    let serverManager = BLEServerManager.shared

    private var centralManager: CBCentralManager!
    private var cancellables = Set<AnyCancellable>()

    @Published var discoveredWatches: [DiscoveredWatch] = []
    @Published var bluetoothState: CBManagerState = .unknown
    @Published var rssiValue: Int? = nil
    @Published var mtuSize: Int = 512

    // Forwarded properties from serverManager
    var isAdvertising: Bool { serverManager.isAdvertising }
    var incomingReplies: [WatchReply] { serverManager.incomingReplies }
    var watchBatteryLevel: Int? { serverManager.watchBatteryLevel }
    var isWatchCharging: Bool { serverManager.isWatchCharging }
    var negotiatedMTU: Int { serverManager.negotiatedMTU }

    let settingsStore = SettingsStore()
    private var scanTimer: Timer?

    /// Monitors phone call state via CallKit for live call status sync
    let callStateObserver = CallStateObserver()

    /// Standard BLE service UUIDs to probe for connected devices.
    private static let probeServiceUUIDs: [CBUUID] = [
        CBUUID(string: "180A"),  // Device Information
        CBUUID(string: "180F"),  // Battery Service
        CBUUID(string: "1800"),  // Generic Access
        CBUUID(string: "1805"),  // Current Time Service
    ]

    /// Known Wear OS watch name patterns
    private static let watchNamePatterns = [
        "pixel watch", "galaxy watch", "ticwatch", "fossil",
        "oppo watch", "oneplus watch", "mobvoi", "wear os",
        "ancs"  // Our own app's advertised name during pairing
    ]

    override init() {
        super.init()

        // Only instantiate CBCentralManager for watch discovery — no duplicate CBPeripheralManager!
        centralManager = CBCentralManager(
            delegate: self,
            queue: nil,
            options: [
                CBCentralManagerOptionShowPowerAlertKey: false
            ]
        )

        // Wire up call state observer to push BLE notifications via consolidated serverManager
        callStateObserver.onCallStateChanged = { [weak self] state in
            self?.serverManager.notifyCallState(state)
        }
        updateCallStateObserving()

        // Sync changes from serverManager to trigger objectWillChange
        serverManager.objectWillChange
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.objectWillChange.send()
            }
            .store(in: &cancellables)
    }

    /// Start or stop call state observing based on the current setting.
    func updateCallStateObserving() {
        if settingsStore.liveCallStatus {
            callStateObserver.startObserving()
        } else {
            callStateObserver.stopObserving()
        }
    }

    // MARK: - Advertising Controls (Delegated to BLEServerManager)

    func startAdvertising() {
        serverManager.startAdvertising()
    }

    func stopAdvertising() {
        serverManager.stopAdvertising()
    }

    // MARK: - Connected Watch Discovery

    func refreshConnectedWatches() {
        guard centralManager.state == .poweredOn else { return }

        var found: [UUID: CBPeripheral] = [:]

        for serviceUUID in Self.probeServiceUUIDs {
            let peripherals = centralManager.retrieveConnectedPeripherals(withServices: [serviceUUID])
            for peripheral in peripherals {
                found[peripheral.identifier] = peripheral
            }
        }

        var connectedWatchIds = Set<String>()
        var result: [DiscoveredWatch] = []

        for peripheral in found.values {
            guard let name = peripheral.name, !name.isEmpty else { continue }
            let lower = name.lowercased()
            let isWatch = Self.watchNamePatterns.contains { lower.contains($0) }
            let savedWatches = loadPersistedWatches()
            let wasPreviouslySeen = savedWatches.contains { $0.id == peripheral.identifier.uuidString }

            guard isWatch || wasPreviouslySeen else { continue }

            savePersistedWatch(PersistedWatch(id: peripheral.identifier.uuidString, name: name))
            connectedWatchIds.insert(peripheral.identifier.uuidString)

            peripheral.delegate = self
            peripheral.readRSSI()
            let mtu = peripheral.maximumWriteValueLength(for: .withoutResponse)
            if mtu > 0 {
                self.mtuSize = mtu
                self.serverManager.negotiatedMTU = mtu
            }

            result.append(DiscoveredWatch(
                id: peripheral.identifier,
                name: name,
                isConnected: true,
                peripheral: peripheral
            ))
        }

        for saved in loadPersistedWatches() {
            if !connectedWatchIds.contains(saved.id), let uuid = UUID(uuidString: saved.id) {
                result.append(DiscoveredWatch(
                    id: uuid,
                    name: saved.name,
                    isConnected: false,
                    peripheral: nil
                ))
            }
        }

        DispatchQueue.main.async {
            self.discoveredWatches = result.sorted { $0.isConnected && !$1.isConnected }
            let connected = result.filter { $0.isConnected }.count
            self.logger.info("Watches: \(connected) connected, \(result.count - connected) offline")
        }
    }

    // MARK: - Watch Persistence

    private static let persistedWatchesKey = "persistedWatches"

    private func loadPersistedWatches() -> [PersistedWatch] {
        guard let data = UserDefaults.standard.data(forKey: Self.persistedWatchesKey),
              let watches = try? JSONDecoder().decode([PersistedWatch].self, from: data) else {
            return []
        }
        return watches
    }

    private func savePersistedWatch(_ watch: PersistedWatch) {
        var watches = loadPersistedWatches()
        if let index = watches.firstIndex(where: { $0.id == watch.id }) {
            watches[index] = watch
        } else {
            watches.append(watch)
        }
        if let data = try? JSONEncoder().encode(watches) {
            UserDefaults.standard.set(data, forKey: Self.persistedWatchesKey)
        }
    }

    func startWatchRefresh() {
        refreshConnectedWatches()
        scanTimer?.invalidate()
        scanTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            self?.refreshConnectedWatches()
        }
    }

    func stopWatchRefresh() {
        scanTimer?.invalidate()
        scanTimer = nil
    }
}

// MARK: - CBCentralManagerDelegate
extension PeripheralManager: CBCentralManagerDelegate {

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        bluetoothState = central.state
        logger.info("Central Bluetooth state: \(central.state.rawValue)")

        if central.state == .poweredOn {
            startWatchRefresh()
        } else {
            stopWatchRefresh()
            DispatchQueue.main.async {
                self.discoveredWatches = []
            }
        }
    }
}

// MARK: - CBPeripheralDelegate
extension PeripheralManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {
        if error == nil {
            DispatchQueue.main.async {
                self.rssiValue = RSSI.intValue
                self.serverManager.rssiValue = RSSI.intValue
            }
        }
    }
}

// MARK: - DiscoveredWatch Model
struct DiscoveredWatch: Identifiable {
    let id: UUID
    let name: String
    let isConnected: Bool
    let peripheral: CBPeripheral?
}

// MARK: - Persisted Watch (for offline display)
struct PersistedWatch: Codable {
    let id: String // UUID string
    let name: String
}
