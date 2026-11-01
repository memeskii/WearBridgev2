import Foundation
import HealthKit
import Combine
import os.log

/**
 * Manages Apple HealthKit integration for WearBridge.
 *
 * Responsibilities:
 * - Safely verifies HealthKit availability (graceful fallback for free Apple IDs lacking entitlements).
 * - Tracks daily step baselines and commits ONLY positive step deltas to prevent duplicate counting in Apple Health.
 * - Records heart rate (count/min) and incremental active energy burned (kcal).
 */
class HealthKitManager: ObservableObject {

    static let shared = HealthKitManager()

    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "HealthKitManager")
    private let healthStore = HKHealthStore()

    private let userDefaults = UserDefaults.standard
    private let keyLastSyncedDate = "HealthKit_LastSyncedDate"
    private let keyLastSyncedSteps = "HealthKit_LastSyncedSteps"
    private let keyLastSyncedKcal = "HealthKit_LastSyncedKcal"

    @Published var isHealthKitAvailable: Bool = false
    @Published var isAuthorized: Bool = false
    @Published var lastSyncDate: Date? = nil
    @Published var lastSyncStatus: String = "Ready"

    private init() {
        checkAvailability()
    }

    /**
     * Verify whether HealthKit is supported on this device.
     */
    private func checkAvailability() {
        guard HKHealthStore.isHealthDataAvailable() else {
            isHealthKitAvailable = false
            logger.warning("HealthKit is not available on this device")
            lastSyncStatus = "HealthKit Unavailable"
            return
        }
        isHealthKitAvailable = true
        logger.info("HealthKit is available on this device")
    }

    /**
     * Request authorization to write steps, heart rate, and active calories.
     * Wrapped gracefully to never crash if sideloaded without paid developer entitlements.
     */
    func requestAuthorization() {
        guard isHealthKitAvailable else {
            logger.warning("Skipping HealthKit auth: Not available on device")
            return
        }

        guard let stepType = HKQuantityType.quantityType(forIdentifier: .stepCount),
              let hrType = HKQuantityType.quantityType(forIdentifier: .heartRate),
              let energyType = HKQuantityType.quantityType(forIdentifier: .activeEnergyBurned) else {
            logger.error("Failed to construct HealthKit quantity types")
            return
        }

        let writeTypes: Set<HKSampleType> = [stepType, hrType, energyType]
        let readTypes: Set<HKObjectType> = [stepType, hrType, energyType]

        healthStore.requestAuthorization(toShare: writeTypes, read: readTypes) { [weak self] success, error in
            DispatchQueue.main.async {
                if let error = error {
                    self?.logger.warning("HealthKit authorization warning: \(error.localizedDescription). Proceeding with in-app telemetry.")
                    self?.isAuthorized = false
                    self?.lastSyncStatus = "Auth Pending / Limited"
                } else {
                    self?.isAuthorized = success
                    self?.lastSyncStatus = success ? "Authorized" : "Denied"
                    self?.logger.info("HealthKit authorization completed. Success: \(success)")
                }
            }
        }
    }

    /**
     * Record incoming health telemetry from the Galaxy Watch:
     * - Computes step delta for the current calendar day and saves sample.
     * - Records instantaneous heart rate (BPM).
     * - Computes active calorie delta and saves sample.
     */
    func recordHealthTelemetry(steps: Int, heartRateBpm: Int, activeKcal: Int) {
        let today = getCurrentDateString()
        let storedDate = userDefaults.string(forKey: keyLastSyncedDate) ?? ""

        var lastSyncedSteps = userDefaults.integer(forKey: keyLastSyncedSteps)
        var lastSyncedKcal = userDefaults.integer(forKey: keyLastSyncedKcal)

        // 1. Midnight rollover check: If a new day has started, reset daily sync baseline
        if storedDate != today {
            logger.info("New day detected (\(today)). Resetting HealthKit sync baselines from prior date (\(storedDate))")
            lastSyncedSteps = 0
            lastSyncedKcal = 0
            userDefaults.set(today, forKey: keyLastSyncedDate)
            userDefaults.set(0, forKey: keyLastSyncedSteps)
            userDefaults.set(0, forKey: keyLastSyncedKcal)
        }

        // 2. Commit Step Delta to HealthKit
        let stepDelta = steps - lastSyncedSteps
        if stepDelta > 0 {
            saveStepDelta(delta: stepDelta)
            userDefaults.set(steps, forKey: keyLastSyncedSteps)
        }

        // 3. Commit Heart Rate to HealthKit
        if heartRateBpm > 0 {
            saveHeartRate(bpm: heartRateBpm)
        }

        // 4. Commit Active Calorie Delta to HealthKit
        let kcalDelta = activeKcal - lastSyncedKcal
        if kcalDelta > 0 {
            saveActiveEnergyDelta(kcalDelta: kcalDelta)
            userDefaults.set(activeKcal, forKey: keyLastSyncedKcal)
        }

        DispatchQueue.main.async {
            self.lastSyncDate = Date()
            self.lastSyncStatus = "Synced at \(Date().formatted(date: .omitted, time: .standard))"
        }
    }

    private func saveStepDelta(delta: Int) {
        guard isHealthKitAvailable else { return }
        guard let stepType = HKQuantityType.quantityType(forIdentifier: .stepCount) else { return }

        let now = Date()
        let start = now.addingTimeInterval(-60) // 1 minute window for incremental delta
        let quantity = HKQuantity(unit: .count(), doubleValue: Double(delta))
        let sample = HKQuantitySample(type: stepType, quantity: quantity, start: start, end: now)

        healthStore.save(sample) { [weak self] success, error in
            if let error = error {
                self?.logger.warning("Could not write step delta (\(delta)): \(error.localizedDescription)")
            } else {
                self?.logger.info("Successfully committed +\(delta) steps to Apple Health")
            }
        }
    }

    private func saveHeartRate(bpm: Int) {
        guard isHealthKitAvailable else { return }
        guard let hrType = HKQuantityType.quantityType(forIdentifier: .heartRate) else { return }

        let now = Date()
        let countPerMinute = HKUnit.count().unitDivided(by: .minute())
        let quantity = HKQuantity(unit: countPerMinute, doubleValue: Double(bpm))
        let sample = HKQuantitySample(type: hrType, quantity: quantity, start: now, end: now)

        healthStore.save(sample) { [weak self] success, error in
            if let error = error {
                self?.logger.warning("Could not write heart rate sample (\(bpm) bpm): \(error.localizedDescription)")
            } else {
                self?.logger.info("Successfully committed \(bpm) BPM to Apple Health")
            }
        }
    }

    private func saveActiveEnergyDelta(kcalDelta: Int) {
        guard isHealthKitAvailable else { return }
        guard let energyType = HKQuantityType.quantityType(forIdentifier: .activeEnergyBurned) else { return }

        let now = Date()
        let start = now.addingTimeInterval(-60)
        let quantity = HKQuantity(unit: .kilocalorie(), doubleValue: Double(kcalDelta))
        let sample = HKQuantitySample(type: energyType, quantity: quantity, start: start, end: now)

        healthStore.save(sample) { [weak self] success, error in
            if let error = error {
                self?.logger.warning("Could not write active kcal delta (\(kcalDelta)): \(error.localizedDescription)")
            } else {
                self?.logger.info("Successfully committed +\(kcalDelta) kcal to Apple Health")
            }
        }
    }

    private func getCurrentDateString() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter.string(from: Date())
    }
}
