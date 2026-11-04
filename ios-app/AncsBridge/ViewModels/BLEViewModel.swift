import SwiftUI
import CoreBluetooth
import Combine

class BLEViewModel: ObservableObject {
    let peripheralManager = PeripheralManager()
    let serverManager = BLEServerManager.shared

    /// Live collection of incoming quick replies received from the watch
    @Published var incomingReplies: [WatchReply] = []

    /// Live watch battery level (0-100) received via custom BLE telemetry
    @Published var watchBatteryLevel: Int? = nil

    /// Live watch charging state
    @Published var isWatchCharging: Bool = false

    /// Live daily step count from watch sensors
    @Published var watchSteps: Int = 0

    /// Live heart rate BPM from watch sensors
    @Published var watchHeartRate: Int = 0

    /// Estimated active calories burned
    @Published var watchActiveCalories: Int = 0

    /// Estimated resting calories burned
    @Published var watchRestingCalories: Int = 0

    /// Total calories burned (active + resting)
    @Published var watchTotalCalories: Int = 0

    /// Live SpO2 blood oxygen saturation percentage
    @Published var watchBloodOxygen: Int? = nil

    /// Live stress level index (0-100)
    @Published var watchStressLevel: Int? = nil

    /// Live sleep session status
    @Published var watchSleepStatus: String? = nil

    /// Total sleep duration in minutes
    @Published var watchSleepDurationMinutes: Int? = nil

    /// Sleep efficiency percentage
    @Published var watchSleepEfficiency: Int? = nil

    /// Timestamp of most recent health sync
    @Published var lastHealthSyncDate: Date? = nil

    /// Live negotiated MTU size
    @Published var negotiatedMTU: Int = 512

    /// Live watch RSSI (signal strength)
    @Published var rssiValue: Int? = nil

    /// Live packet log entries from BLEPacketMultiplexer
    @Published var packetLogs: [PacketLogEntry] = []

    private var cancellables = Set<AnyCancellable>()

    init() {
        // Observe bluetooth state changes
        peripheralManager.$bluetoothState
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.objectWillChange.send()
            }
            .store(in: &cancellables)

        peripheralManager.$discoveredWatches
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.objectWillChange.send()
            }
            .store(in: &cancellables)

        serverManager.$isAdvertising
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.objectWillChange.send()
            }
            .store(in: &cancellables)

        // Wire incoming quick replies
        serverManager.$incomingReplies
            .receive(on: DispatchQueue.main)
            .sink { [weak self] replies in
                self?.incomingReplies = replies
            }
            .store(in: &cancellables)

        // Wire live battery level
        serverManager.$watchBatteryLevel
            .receive(on: DispatchQueue.main)
            .sink { [weak self] level in
                self?.watchBatteryLevel = level
            }
            .store(in: &cancellables)

        // Wire live charging state
        serverManager.$isWatchCharging
            .receive(on: DispatchQueue.main)
            .sink { [weak self] isCharging in
                self?.isWatchCharging = isCharging
            }
            .store(in: &cancellables)

        // Wire live health telemetry
        serverManager.$watchSteps
            .receive(on: DispatchQueue.main)
            .sink { [weak self] steps in
                self?.watchSteps = steps
            }
            .store(in: &cancellables)

        serverManager.$watchHeartRate
            .receive(on: DispatchQueue.main)
            .sink { [weak self] bpm in
                self?.watchHeartRate = bpm
            }
            .store(in: &cancellables)

        serverManager.$watchActiveCalories
            .receive(on: DispatchQueue.main)
            .sink { [weak self] kcal in
                self?.watchActiveCalories = kcal
            }
            .store(in: &cancellables)

        serverManager.$watchRestingCalories
            .receive(on: DispatchQueue.main)
            .sink { [weak self] kcal in
                self?.watchRestingCalories = kcal
            }
            .store(in: &cancellables)

        serverManager.$watchTotalCalories
            .receive(on: DispatchQueue.main)
            .sink { [weak self] kcal in
                self?.watchTotalCalories = kcal
            }
            .store(in: &cancellables)

        serverManager.$watchBloodOxygen
            .receive(on: DispatchQueue.main)
            .sink { [weak self] spo2 in
                self?.watchBloodOxygen = spo2
            }
            .store(in: &cancellables)

        serverManager.$watchStressLevel
            .receive(on: DispatchQueue.main)
            .sink { [weak self] stress in
                self?.watchStressLevel = stress
            }
            .store(in: &cancellables)

        serverManager.$watchSleepStatus
            .receive(on: DispatchQueue.main)
            .sink { [weak self] status in
                self?.watchSleepStatus = status
            }
            .store(in: &cancellables)

        serverManager.$watchSleepDurationMinutes
            .receive(on: DispatchQueue.main)
            .sink { [weak self] duration in
                self?.watchSleepDurationMinutes = duration
            }
            .store(in: &cancellables)

        serverManager.$watchSleepEfficiency
            .receive(on: DispatchQueue.main)
            .sink { [weak self] eff in
                self?.watchSleepEfficiency = eff
            }
            .store(in: &cancellables)

        serverManager.$lastHealthSyncDate
            .receive(on: DispatchQueue.main)
            .sink { [weak self] date in
                self?.lastHealthSyncDate = date
            }
            .store(in: &cancellables)

        serverManager.$negotiatedMTU
            .receive(on: DispatchQueue.main)
            .sink { [weak self] mtu in
                self?.negotiatedMTU = mtu
            }
            .store(in: &cancellables)

        serverManager.$rssiValue
            .receive(on: DispatchQueue.main)
            .sink { [weak self] rssi in
                self?.rssiValue = rssi
            }
            .store(in: &cancellables)

        serverManager.multiplexer.$packetLogs
            .receive(on: DispatchQueue.main)
            .sink { [weak self] logs in
                self?.packetLogs = logs
            }
            .store(in: &cancellables)
    }

    /// Dynamic SF Symbol matching battery percentage and charging state
    var batterySymbolName: String {
        let level = watchBatteryLevel ?? 100
        if isWatchCharging {
            if level >= 88 { return "battery.100.bolt" }
            if level >= 63 { return "battery.75.bolt" }
            if level >= 38 { return "battery.50.bolt" }
            if level >= 13 { return "battery.25.bolt" }
            return "battery.0.bolt"
        } else {
            if level >= 88 { return "battery.100" }
            if level >= 63 { return "battery.75" }
            if level >= 38 { return "battery.50" }
            if level >= 13 { return "battery.25" }
            return "battery.0"
        }
    }

    var batteryTint: Color {
        guard let level = watchBatteryLevel else { return .secondary }
        if level > 50 { return .green }
        if level > 20 { return .orange }
        return .red
    }

    func toggleAdvertising() {
        if serverManager.isAdvertising {
            serverManager.stopAdvertising()
        } else {
            serverManager.startAdvertising()
        }
    }

    // MARK: - Diagnostics Test Actions
    func sendTestPing(completion: @escaping (Result<Int, Error>) -> Void) {
        serverManager.sendTestPing(completion: completion)
    }

    func sendTestNotification(title: String = "Test Notification", message: String = "Custom GATT Alert from iPhone") {
        serverManager.sendTestNotification(title: title, message: message)
    }

    func sendMockIncomingCall(callerName: String = "Jane Doe", phoneNumber: String = "+1 (555) 019-2834") {
        serverManager.sendMockIncomingCall(callerName: callerName, phoneNumber: phoneNumber)
    }

    func sendTestFileTransfer(
        sizeBytes: Int = 100 * 1024,
        onProgress: @escaping (Double) -> Void,
        completion: @escaping (Result<Double, Error>) -> Void
    ) {
        serverManager.sendTestFileTransfer(sizeBytes: sizeBytes, onProgress: onProgress, completion: completion)
    }

    func sendTestReplyLoopback(
        message: String = "Hello from iPhone Diagnostics!",
        completion: @escaping (Result<String, Error>) -> Void
    ) {
        serverManager.sendTestReplyLoopback(message: message, completion: completion)
    }

    // MARK: - Contacts & Weather Sync
    let contactsManager = ContactsSyncManager.shared
    let weatherService = WeatherSyncService.shared

    func forceSyncContactsAndWeather(completion: @escaping (Result<String, Error>) -> Void) {
        let group = DispatchGroup()
        var contactsCount = 0
        var weatherCity = ""
        var syncError: Error?

        group.enter()
        contactsManager.syncContacts(force: true) { result in
            switch result {
            case .success(let count):
                contactsCount = count
            case .failure(let err):
                syncError = err
            }
            group.leave()
        }

        group.enter()
        weatherService.syncWeather(force: true) { result in
            switch result {
            case .success(let payload):
                weatherCity = payload.cityName
            case .failure(let err):
                if syncError == nil { syncError = err }
            }
            group.leave()
        }

        group.notify(queue: .main) {
            if let error = syncError {
                completion(.failure(error))
            } else {
                let msg = "Synced \(contactsCount) contacts & weather (\(weatherCity))"
                completion(.success(msg))
            }
        }
    }

    // MARK: - Phase 3 Health Telemetry
    let healthTelemetryManager = HealthTelemetryManager.shared

    func pullWatchHealthTelemetry(completion: @escaping (Result<HealthTelemetryPayload, Error>) -> Void) {
        healthTelemetryManager.pullTelemetry(completion: completion)
    }

    // MARK: - Phase 4 Media QoL
    let mediaQoLManager = MediaQoLManager.shared

    func testMediaQoLPush(completion: @escaping (Result<String, Error>) -> Void) {
        mediaQoLManager.sendMockMediaQoLPayload(completion: completion)
    }

    // MARK: - Phase 5 Message Relay & Inbox Injection
    let replyRelayManager = ReplyRelayManager.shared

    func sendMockMessageToWatch(
        bundleId: String = "net.whatsapp.WhatsApp",
        senderName: String = "Sarah Connor",
        body: String = "Are you ready for the deployment?",
        completion: @escaping (Result<String, Error>) -> Void
    ) {
        let brand = AppBrandRegistry.resolve(bundleId)
        replyRelayManager.sendSyntheticMessageToWatch(
            appBundleId: bundleId,
            appDisplayName: brand.displayName,
            senderName: senderName,
            senderIdentifier: "+1 (555) 019-2834",
            body: body,
            completion: completion
        )
    }

    // MARK: - Phase 6 High-Throughput File Transfer & Watch Face Engine
    let fileTransferManager = FileTransferManager.shared
    let watchFaceStoreManager = WatchFaceStoreManager.shared

    func testMockWatchFaceApkTransfer(completion: @escaping (Result<String, Error>) -> Void) {
        var data = Data()
        data.append(contentsOf: [0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00])
        let manifest = "{\"package\": \"com.wearbridge.watchface.mock\", \"version\": \"1.0.0\"}"
        if let mData = manifest.data(using: .utf8) {
            data.append(mData)
        }
        let total = 150 * 1024
        let remaining = max(0, total - data.count)
        var filler = [UInt8](repeating: 0, count: remaining)
        for i in 0..<remaining {
            filler[i] = UInt8((i * 41 + 19) & 0xFF)
        }
        data.append(contentsOf: filler)

        fileTransferManager.startTransfer(
            fileData: data,
            fileName: "diagnostics_test.apk",
            fileType: "WATCH_FACE",
            completion: completion
        )
    }

    func clearPacketLogs() {
        serverManager.multiplexer.clearLogs()
    }

    func refreshRssi() {
        peripheralManager.refreshConnectedWatches()
    }
}
