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

    /// Timestamp of most recent health sync
    @Published var lastHealthSyncDate: Date? = nil

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

        serverManager.$lastHealthSyncDate
            .receive(on: DispatchQueue.main)
            .sink { [weak self] date in
                self?.lastHealthSyncDate = date
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
}
