import Foundation
import CoreBluetooth
import Combine
import UIKit
import os.log

/// Model representing a quick reply received from the Wear OS watch.
struct WatchReply: Identifiable, Hashable {
    let id: UUID
    let notificationUid: Int
    let message: String
    let timestamp: Date

    init(id: UUID = UUID(), notificationUid: Int, message: String, timestamp: Date = Date()) {
        self.id = id
        self.notificationUid = notificationUid
        self.message = message
        self.timestamp = timestamp
    }
}

/// Consolidated BLE Peripheral Server Manager for WearBridge iOS Companion App.
///
/// Serves as the single, authoritative CBPeripheralManager GATT server in the app:
/// - Advertises companion service A0E3D5B1-4C6F-4E8A-9B2D-7F1E3A5C8D0B
/// - Exposes Status Characteristic A0E3D5B2-... (read, notify)
/// - Exposes Custom Characteristic 7F8A0003-... (write):
///     * Handles Quick Replies: "REPLY:<UID>:<TEXT>"
///     * Handles Live Battery Telemetry: "BATT:<level>:<1|0>"
/// - Exposes Call State Characteristic A0E3D5B4-... (read, notify)
class BLEServerManager: NSObject, ObservableObject {

    static let shared = BLEServerManager()

    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "BLEServerManager")

    // Service & Characteristic UUIDs
    static let companionServiceUUID = CBUUID(string: "A0E3D5B1-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
    static let connectionStatusUUID = CBUUID(string: "A0E3D5B2-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
    static let quickReplyCharacteristicUUID = CBUUID(string: "7F8A0003-9B3C-4D2A-B9E1-8C5F6D7E8A01")
    static let callStateCharacteristicUUID = CBUUID(string: "A0E3D5B4-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
    static let advertisingLocalName = "WearBridge"

    private var peripheralManager: CBPeripheralManager!
    private var statusCharacteristic: CBMutableCharacteristic?
    private var quickReplyCharacteristic: CBMutableCharacteristic?
    private var callStateCharacteristic: CBMutableCharacteristic?
    private var serviceAdded = false

    // MARK: - Published Properties
    @Published var incomingReplies: [WatchReply] = []
    @Published var watchBatteryLevel: Int? = nil
    @Published var isWatchCharging: Bool = false
    @Published var watchSteps: Int = 0
    @Published var watchHeartRate: Int = 0
    @Published var watchActiveCalories: Int = 0
    @Published var lastHealthSyncDate: Date? = nil
    @Published var isAdvertising = false
    @Published var bluetoothState: CBManagerState = .unknown

    override init() {
        super.init()
        HealthKitManager.shared.requestAuthorization()
        peripheralManager = CBPeripheralManager(
            delegate: self,
            queue: nil,
            options: [
                CBPeripheralManagerOptionRestoreIdentifierKey: BLEConstants.restoreIdentifier
            ]
        )
    }

    private func setupServices() {
        guard !serviceAdded else { return }

        statusCharacteristic = CBMutableCharacteristic(
            type: Self.connectionStatusUUID,
            properties: [.read, .notify],
            value: nil,
            permissions: [.readable]
        )

        quickReplyCharacteristic = CBMutableCharacteristic(
            type: Self.quickReplyCharacteristicUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )

        callStateCharacteristic = CBMutableCharacteristic(
            type: Self.callStateCharacteristicUUID,
            properties: [.read, .notify],
            value: nil,
            permissions: [.readable]
        )

        let service = CBMutableService(type: Self.companionServiceUUID, primary: true)
        service.characteristics = [
            statusCharacteristic!,
            quickReplyCharacteristic!,
            callStateCharacteristic!
        ]

        peripheralManager.add(service)
        serviceAdded = true
        logger.info("Configured consolidated GATT service with Status, 7F8A0003, and Call State characteristics")
    }

    // MARK: - Advertising Controls

    func startAdvertising() {
        guard peripheralManager.state == .poweredOn else {
            logger.warning("Cannot advertise: Bluetooth not powered on")
            return
        }

        if peripheralManager.isAdvertising {
            isAdvertising = true
            return
        }

        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [Self.companionServiceUUID],
            CBAdvertisementDataLocalNameKey: Self.advertisingLocalName
        ])
        logger.info("Requested advertising for companion service: \(Self.companionServiceUUID)")
    }

    func stopAdvertising() {
        peripheralManager.stopAdvertising()
        isAdvertising = false
        logger.info("Stopped companion advertising")
    }

    // MARK: - Call State Push

    func notifyCallState(_ state: UInt8) {
        guard let char = callStateCharacteristic else { return }
        let data = Data([state])
        let sent = peripheralManager.updateValue(data, for: char, onSubscribedCentrals: nil)
        logger.info("Call state notify: \(state), sent: \(sent)")
    }
}

// MARK: - CBPeripheralManagerDelegate
extension BLEServerManager: CBPeripheralManagerDelegate {

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        bluetoothState = peripheral.state
        logger.info("Peripheral state updated: \(peripheral.state.rawValue)")

        if peripheral.state == .poweredOn {
            setupServices()
        } else {
            isAdvertising = false
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error {
            logger.error("Failed to add companion service: \(error.localizedDescription)")
            return
        }

        logger.info("Companion service added. Starting advertising...")
        startAdvertising()
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error {
            logger.error("Failed to start advertising: \(error.localizedDescription)")
            isAdvertising = false
        } else {
            isAdvertising = true
            logger.info("Consolidated BLE advertising active for \(Self.companionServiceUUID)")
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        if request.characteristic.uuid == Self.connectionStatusUUID {
            request.value = Data([0x01])
            peripheral.respond(to: request, withResult: .success)
        } else if request.characteristic.uuid == Self.callStateCharacteristicUUID {
            request.value = Data([0x00])
            peripheral.respond(to: request, withResult: .success)
        } else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard let firstRequest = requests.first else { return }
        var result: CBATTError.Code = .success

        for request in requests {
            if request.characteristic.uuid == Self.quickReplyCharacteristicUUID {
                if let data = request.value,
                   let payload = String(data: data, encoding: .utf8) {
                    logger.info("Received payload on 7F8A0003: \(payload)")

                    // 1. Check for Quick Reply: "REPLY:<UID>:<TEXT>"
                    if payload.hasPrefix("REPLY:") {
                        let content = String(payload.dropFirst("REPLY:".count))
                        let parts = content.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)

                        if parts.count == 2, let uid = Int(parts[0]) {
                            let message = String(parts[1])
                            let reply = WatchReply(
                                notificationUid: uid,
                                message: message,
                                timestamp: Date()
                            )

                            DispatchQueue.main.async {
                                self.incomingReplies.insert(reply, at: 0)
                                self.logger.info("Watch reply added for UID #\(uid): \(message)")
                            }
                        }
                        handleQuickReplyShortcut(payload)
                    }
                    // 2. Check for Battery Telemetry: "BATT:<level>:<1|0>"
                    else if payload.hasPrefix("BATT:") {
                        let content = String(payload.dropFirst("BATT:".count))
                        let parts = content.split(separator: ":")

                        if parts.count >= 2,
                           let level = Int(parts[0]),
                           let chargingInt = Int(parts[1]) {
                            let isCharging = (chargingInt == 1)

                            DispatchQueue.main.async {
                                self.watchBatteryLevel = level
                                self.isWatchCharging = isCharging
                                self.logger.info("Live Watch Battery updated: \(level)% (charging: \(isCharging))")
                            }
                        }
                    }
                    // 3. Check for Health Telemetry: "HEALTH:<steps>:<heartRateBpm>:<activeKcal>"
                    else if payload.hasPrefix("HEALTH:") {
                        let content = String(payload.dropFirst("HEALTH:".count))
                        let parts = content.split(separator: ":")

                        if parts.count >= 3,
                           let steps = Int(parts[0]),
                           let bpm = Int(parts[1]),
                           let kcal = Int(parts[2]) {
                            DispatchQueue.main.async {
                                self.watchSteps = steps
                                self.watchHeartRate = bpm
                                self.watchActiveCalories = kcal
                                self.lastHealthSyncDate = Date()
                                self.logger.info("Live Watch Health updated: \(steps) steps, \(bpm) BPM, \(kcal) kcal")
                            }
                            HealthKitManager.shared.recordHealthTelemetry(steps: steps, heartRateBpm: bpm, activeKcal: kcal)
                        }
                    } else {
                        // Legacy payload fallback: "callerName|replyMessage"
                        handleQuickReplyShortcut(payload)
                    }
                }
            } else {
                result = .attributeNotFound
                break
            }
        }

        // Respond once per Apple CoreBluetooth specification
        peripheral.respond(to: firstRequest, withResult: result)
    }

    private func handleQuickReplyShortcut(_ payload: String) {
        let encoded = payload.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? payload
        let urlString = "shortcuts://run-shortcut?name=WearBridge%20Reply&input=text&text=\(encoded)"
        if let url = URL(string: urlString) {
            DispatchQueue.main.async {
                UIApplication.shared.open(url) { _ in }
            }
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        logger.info("Restoring consolidated peripheral manager state")
        if let services = dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService] {
            serviceAdded = !services.isEmpty
        }
        if peripheral.isAdvertising {
            isAdvertising = true
        }
    }
}
