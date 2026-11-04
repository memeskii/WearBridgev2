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
 * - Records heart rate (count/min), active energy burned (kcal), basal resting energy (kcal),
 *   blood oxygen (SpO2 percent), and sleep analysis sessions with stage records.
 */
class HealthKitManager: ObservableObject {

    static let shared = HealthKitManager()

    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "HealthKitManager")
    private let healthStore = HKHealthStore()

    private let userDefaults = UserDefaults.standard
    private let keyLastSyncedDate = "HealthKit_LastSyncedDate"
    private let keyLastSyncedSteps = "HealthKit_LastSyncedSteps"
    private let keyLastSyncedActiveKcal = "HealthKit_LastSyncedActiveKcal"
    private let keyLastSyncedRestingKcal = "HealthKit_LastSyncedRestingKcal"

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
    private fun checkAvailability() {
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
     * Request authorization to write steps, heart rate, active calories, resting calories, SpO2, and sleep.
     * Wrapped gracefully to never crash if sideloaded without paid developer entitlements.
     */
    func requestAuthorization() {
        guard isHealthKitAvailable else {
            logger.warning("Skipping HealthKit auth: Not available on device")
            return
        }

        var writeTypes: Set<HKSampleType> = []
        var readTypes: Set<HKObjectType> = []

        if let stepType = HKQuantityType.quantityType(forIdentifier: .stepCount) {
            writeTypes.insert(stepType)
            readTypes.insert(stepType)
        }
        if let hrType = HKQuantityType.quantityType(forIdentifier: .heartRate) {
            writeTypes.insert(hrType)
            readTypes.insert(hrType)
        }
        if let activeEnergy = HKQuantityType.quantityType(forIdentifier: .activeEnergyBurned) {
            writeTypes.insert(activeEnergy)
            readTypes.insert(activeEnergy)
        }
        if let basalEnergy = HKQuantityType.quantityType(forIdentifier: .basalEnergyBurned) {
            writeTypes.insert(basalEnergy)
            readTypes.insert(basalEnergy)
        }
        if let o2Type = HKQuantityType.quantityType(forIdentifier: .oxygenSaturation) {
            writeTypes.insert(o2Type)
            readTypes.insert(o2Type)
        }
        if let sleepType = HKCategoryType.categoryType(forIdentifier: .sleepAnalysis) {
            writeTypes.insert(sleepType)
            readTypes.insert(sleepType)
        }

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
     * Legacy telemetry update (steps, HR, active calories).
     */
    func recordHealthTelemetry(steps: Int, heartRateBpm: Int, activeKcal: Int) {
        recordFullHealthTelemetry(
            steps: steps,
            heartRateBpm: heartRateBpm,
            bloodOxygen: 0,
            stressLevel: 0,
            activeKcal: activeKcal,
            restingKcal: 0,
            sleepStatus: nil,
            sleepDurationMinutes: nil,
            sleepEfficiency: nil
        )
    }

    /**
     * Record comprehensive incoming health telemetry from the Galaxy Watch:
     * - Step delta
     * - Real-time Heart Rate (BPM)
     * - Blood Oxygen (SpO2 percentage)
     * - Active & Resting Calories deltas
     * - Sleep session analysis
     */
    func recordFullHealthTelemetry(
        steps: Int,
        heartRateBpm: Int,
        bloodOxygen: Int,
        stressLevel: Int,
        activeKcal: Int,
        restingKcal: Int,
        sleepStatus: String?,
        sleepDurationMinutes: Int?,
        sleepEfficiency: Int?
    ) {
        let today = getCurrentDateString()
        let storedDate = userDefaults.string(forKey: keyLastSyncedDate) ?? ""

        var lastSyncedSteps = userDefaults.integer(forKey: keyLastSyncedSteps)
        var lastSyncedActiveKcal = userDefaults.integer(forKey: keyLastSyncedActiveKcal)
        var lastSyncedRestingKcal = userDefaults.integer(forKey: keyLastSyncedRestingKcal)

        // 1. Midnight rollover check: If a new day has started, reset daily sync baselines
        if storedDate != today {
            logger.info("New day detected (\(today)). Resetting HealthKit sync baselines from prior date (\(storedDate))")
            lastSyncedSteps = 0
            lastSyncedActiveKcal = 0
            lastSyncedRestingKcal = 0
            userDefaults.set(today, forKey: keyLastSyncedDate)
            userDefaults.set(0, forKey: keyLastSyncedSteps)
            userDefaults.set(0, forKey: keyLastSyncedActiveKcal)
            userDefaults.set(0, forKey: keyLastSyncedRestingKcal)
        }

        // 2. Commit Step Delta to HealthKit
        let stepDelta = steps - lastSyncedSteps
        if stepDelta > 0 {
            saveStepDelta(delta: stepDelta, dateKey: today)
            userDefaults.set(steps, forKey: keyLastSyncedSteps)
        }

        // 3. Commit Heart Rate to HealthKit
        if heartRateBpm > 0 {
            saveHeartRate(bpm: heartRateBpm)
        }

        // 4. Commit SpO2 Blood Oxygen to HealthKit
        if bloodOxygen in 70...100 {
            saveBloodOxygen(percent: bloodOxygen)
        }

        // 5. Commit Active Calorie Delta to HealthKit
        let activeKcalDelta = activeKcal - lastSyncedActiveKcal
        if activeKcalDelta > 0 {
            saveActiveEnergyDelta(kcalDelta: activeKcalDelta, dateKey: today)
            userDefaults.set(activeKcal, forKey: keyLastSyncedActiveKcal)
        }

        // 6. Commit Resting Calorie Delta to HealthKit
        if restingKcal > 0 {
            let restingKcalDelta = restingKcal - lastSyncedRestingKcal
            if restingKcalDelta > 0 {
                saveRestingEnergyDelta(kcalDelta: restingKcalDelta, dateKey: today)
                userDefaults.set(restingKcal, forKey: keyLastSyncedRestingKcal)
            }
        }

        // 7. Commit Sleep Analysis to HealthKit
        if let duration = sleepDurationMinutes, duration > 0 {
            saveSleepSession(durationMinutes: duration, status: sleepStatus ?? "asleep")
        }

        DispatchQueue.main.async {
            self.lastSyncDate = Date()
            self.lastSyncStatus = "Synced at \(Date().formatted(date: .omitted, time: .standard))"
        }
    }

    private func saveStepDelta(delta: Int, dateKey: String) {
        guard isHealthKitAvailable else { return }
        guard let stepType = HKQuantityType.quantityType(forIdentifier: .stepCount) else { return }

        let now = Date()
        let start = now.addingTimeInterval(-60)
        let quantity = HKQuantity(unit: .count(), doubleValue: Double(delta))

        let syncVersionKey = "HealthKit_Step_SyncVer_\(dateKey)"
        let version = userDefaults.integer(forKey: syncVersionKey) + 1
        userDefaults.set(version, forKey: syncVersionKey)

        var metadata: [String: Any] = [:]
        if #available(iOS 11.0, *) {
            metadata[HKMetadataKeySyncIdentifier] = "wearbridge_step_\(dateKey)_\(version)"
            metadata[HKMetadataKeySyncVersion] = version
        }

        let sample = HKQuantitySample(type: stepType, quantity: quantity, start: start, end: now, metadata: metadata.isEmpty ? nil : metadata)

        healthStore.save(sample) { [weak self] success, error in
            if let error = error {
                self?.logger.warning("Could not write step delta (\(delta)): \(error.localizedDescription)")
            } else {
                self?.logger.info("Successfully committed +\(delta) steps to Apple Health (ver: \(version))")
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

    private func saveBloodOxygen(percent: Int) {
        guard isHealthKitAvailable else { return }
        guard let o2Type = HKQuantityType.quantityType(forIdentifier: .oxygenSaturation) else { return }

        let now = Date()
        let ratio = Double(percent) / 100.0
        let quantity = HKQuantity(unit: .percent(), doubleValue: ratio)
        let sample = HKQuantitySample(type: o2Type, quantity: quantity, start: now, end: now)

        healthStore.save(sample) { [weak self] success, error in
            if let error = error {
                self?.logger.warning("Could not write oxygen saturation (\(percent)%): \(error.localizedDescription)")
            } else {
                self?.logger.info("Successfully committed \(percent)% SpO2 to Apple Health")
            }
        }
    }

    private func saveActiveEnergyDelta(kcalDelta: Int, dateKey: String) {
        guard isHealthKitAvailable else { return }
        guard let energyType = HKQuantityType.quantityType(forIdentifier: .activeEnergyBurned) else { return }

        let now = Date()
        let start = now.addingTimeInterval(-60)
        let quantity = HKQuantity(unit: .kilocalorie(), doubleValue: Double(kcalDelta))

        let syncVersionKey = "HealthKit_ActiveCal_SyncVer_\(dateKey)"
        let version = userDefaults.integer(forKey: syncVersionKey) + 1
        userDefaults.set(version, forKey: syncVersionKey)

        var metadata: [String: Any] = [:]
        if #available(iOS 11.0, *) {
            metadata[HKMetadataKeySyncIdentifier] = "wearbridge_cal_\(dateKey)"
            metadata[HKMetadataKeySyncVersion] = version
        }

        let sample = HKQuantitySample(type: energyType, quantity: quantity, start: start, end: now, metadata: metadata.isEmpty ? nil : metadata)

        healthStore.save(sample) { [weak self] success, error in
            if let error = error {
                self?.logger.warning("Could not write active kcal delta (\(kcalDelta)): \(error.localizedDescription)")
            } else {
                self?.logger.info("Successfully committed +\(kcalDelta) active kcal to Apple Health (ver: \(version))")
            }
        }
    }

    private func saveRestingEnergyDelta(kcalDelta: Int, dateKey: String) {
        guard isHealthKitAvailable else { return }
        guard let energyType = HKQuantityType.quantityType(forIdentifier: .basalEnergyBurned) else { return }

        let now = Date()
        let start = now.addingTimeInterval(-60)
        let quantity = HKQuantity(unit: .kilocalorie(), doubleValue: Double(kcalDelta))

        let syncVersionKey = "HealthKit_RestingCal_SyncVer_\(dateKey)"
        let version = userDefaults.integer(forKey: syncVersionKey) + 1
        userDefaults.set(version, forKey: syncVersionKey)

        var metadata: [String: Any] = [:]
        if #available(iOS 11.0, *) {
            metadata[HKMetadataKeySyncIdentifier] = "wearbridge_basal_cal_\(dateKey)"
            metadata[HKMetadataKeySyncVersion] = version
        }

        let sample = HKQuantitySample(type: energyType, quantity: quantity, start: start, end: now, metadata: metadata.isEmpty ? nil : metadata)

        healthStore.save(sample) { [weak self] success, error in
            if let error = error {
                self?.logger.warning("Could not write basal energy delta (\(kcalDelta)): \(error.localizedDescription)")
            } else {
                self?.logger.info("Successfully committed +\(kcalDelta) resting kcal to Apple Health (ver: \(version))")
            }
        }
    }

    private func saveSleepSession(durationMinutes: Int, status: String) {
        guard isHealthKitAvailable else { return }
        guard let sleepType = HKCategoryType.categoryType(forIdentifier: .sleepAnalysis) else { return }

        let now = Date()
        let start = now.addingTimeInterval(-Double(durationMinutes * 60))

        let categoryValue: Int
        if #available(iOS 16.0, *) {
            switch status.lowercased() {
            case "deep":
                categoryValue = HKCategoryValueSleepAnalysis.asleepDeep.rawValue
            case "rem":
                categoryValue = HKCategoryValueSleepAnalysis.asleepREM.rawValue
            case "light":
                categoryValue = HKCategoryValueSleepAnalysis.asleepCore.rawValue
            case "awake":
                categoryValue = HKCategoryValueSleepAnalysis.awake.rawValue
            default:
                categoryValue = HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue
            }
        } else {
            categoryValue = HKCategoryValueSleepAnalysis.asleep.rawValue
        }

        let sample = HKCategorySample(
            type: sleepType,
            value: categoryValue,
            start: start,
            end: now
        )

        healthStore.save(sample) { [weak self] success, error in
            if let error = error {
                self?.logger.warning("Could not write sleep sample: \(error.localizedDescription)")
            } else {
                self?.logger.info("Successfully committed sleep session (\(durationMinutes) min, status=\(status)) to Apple Health")
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
