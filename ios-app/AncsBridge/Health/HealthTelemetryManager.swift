import Foundation
import SwiftData
import os.log
import Combine

/// Manages incoming Wear OS 24-hour health telemetry (Channel 0x04),
/// persisting records in SwiftData keyed by calendar day (yyyy-MM-dd)
/// and maintaining a lightweight snapshot in UserDefaults and HealthKit.
public class HealthTelemetryManager: ObservableObject {

    public static let shared = HealthTelemetryManager()

    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "HealthTelemetryManager")
    private let userDefaultsKey = "wearbridge_latest_health_telemetry"

    @Published public var latestTelemetry: HealthTelemetryPayload? = nil
    public var lastTelemetry: HealthTelemetryPayload? { latestTelemetry }
    @Published public var isPulling: Bool = false
    @Published public var lastSyncDate: Date? = nil

    // SwiftData container & context (iOS 17+)
    private var modelContainer: Any? = nil

    private init() {
        loadCachedTelemetry()
        setupSwiftDataContainer()
    }

    private func setupSwiftDataContainer() {
        if #available(iOS 17.0, *) {
            do {
                let schema = Schema([
                    DailyHealthRecord.self,
                    SleepStageRecord.self,
                    SpO2SampleRecord.self
                ])
                let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: false)
                modelContainer = try ModelContainer(for: schema, configurations: [config])
                logger.info("SwiftData ModelContainer initialized successfully for DailyHealthRecord")
            } catch {
                logger.error("Failed to initialize SwiftData ModelContainer: \(error.localizedDescription)")
            }
        }
    }

    private func loadCachedTelemetry() {
        guard let data = UserDefaults.standard.data(forKey: userDefaultsKey) else { return }
        do {
            let decoder = JSONDecoder()
            let payload = try decoder.decode(HealthTelemetryPayload.self, from: data)
            self.latestTelemetry = payload
            logger.info("Loaded cached health telemetry for \(payload.date)")
        } catch {
            logger.warning("Could not decode cached health telemetry: \(error.localizedDescription)")
        }
    }

    /// Handles reassembled Channel 0x04 JSON payload from watch.
    @MainActor
    public func handleIncomingTelemetry(data: Data) {
        do {
            let decoder = JSONDecoder()
            let payload = try decoder.decode(HealthTelemetryPayload.self, from: data)

            logger.info("Successfully decoded Channel 0x04 telemetry for \(payload.date): Calories=\(payload.calories.total), SpO2=\(payload.spo2.latest)%, Sleep=\(payload.sleep.totalDurationMinutes)m, Stress=\(payload.stress.stressScore)")

            // 1. Cache latest in UserDefaults
            UserDefaults.standard.set(data, forKey: userDefaultsKey)

            // 2. Update Published state
            self.latestTelemetry = payload
            self.lastSyncDate = Date()
            self.isPulling = false

            // 3. Persist / Upsert into SwiftData (iOS 17+)
            persistToSwiftData(payload)

            // 4. Record to HealthKit
            HealthKitManager.shared.recordFullHealthTelemetry(
                steps: payload.activity.steps,
                heartRateBpm: payload.activity.heartRate,
                bloodOxygen: payload.spo2.latest,
                stressLevel: payload.stress.stressScore,
                activeKcal: Int(payload.calories.active),
                restingKcal: Int(payload.calories.total - payload.calories.active),
                sleepStatus: "asleep",
                sleepDurationMinutes: payload.sleep.totalDurationMinutes,
                sleepEfficiency: payload.sleep.sleepScore
            )

        } catch {
            logger.error("Failed to decode Channel 0x04 health telemetry JSON: \(error.localizedDescription)")
            self.isPulling = false
        }
    }

    private func persistToSwiftData(_ payload: HealthTelemetryPayload) {
        if #available(iOS 17.0, *) {
            guard let container = modelContainer as? ModelContainer else { return }
            let context = ModelContext(container)

            let dateKey = payload.date
            let fetchDescriptor = FetchDescriptor<DailyHealthRecord>(
                predicate: #Predicate { $0.dateKey == dateKey }
            )

            do {
                let existingRecords = try context.fetch(fetchDescriptor)
                let record: DailyHealthRecord

                if let existing = existingRecords.first {
                    record = existing
                    logger.info("Updating existing SwiftData record for \(dateKey)")

                    // Cleanly delete previous child SpO2SampleRecord and SleepStageRecord instances
                    if let oldStages = record.sleepStages {
                        context.delete(oldStages)
                        record.sleepStages = nil
                    }
                    for oldSample in record.spo2Samples {
                        context.delete(oldSample)
                    }
                    record.spo2Samples.removeAll()
                } else {
                    record = DailyHealthRecord(
                        dateKey: dateKey,
                        timestamp: payload.timestamp,
                        steps: payload.activity.steps,
                        heartRate: payload.activity.heartRate,
                        restingHeartRate: payload.activity.restingHeartRate,
                        caloriesTotal: payload.calories.total,
                        caloriesActive: payload.calories.active,
                        spo2Latest: payload.spo2.latest,
                        spo2Avg: payload.spo2.avg,
                        spo2Min: payload.spo2.min,
                        sleepDurationMinutes: payload.sleep.totalDurationMinutes,
                        sleepScore: payload.sleep.sleepScore,
                        stressScore: payload.stress.stressScore,
                        hrvRmssd: payload.stress.hrvRmssd
                    )
                    context.insert(record)
                    logger.info("Inserted new SwiftData record for \(dateKey)")
                }

                // Update mutable values
                record.timestamp = payload.timestamp
                record.steps = payload.activity.steps
                record.heartRate = payload.activity.heartRate
                record.restingHeartRate = payload.activity.restingHeartRate
                record.caloriesTotal = payload.calories.total
                record.caloriesActive = payload.calories.active
                record.spo2Latest = payload.spo2.latest
                record.spo2Avg = payload.spo2.avg
                record.spo2Min = payload.spo2.min
                record.sleepDurationMinutes = payload.sleep.totalDurationMinutes
                record.sleepScore = payload.sleep.sleepScore
                record.stressScore = payload.stress.stressScore
                record.hrvRmssd = payload.stress.hrvRmssd

                // Upsert sleep stages
                record.sleepStages = SleepStageRecord(
                    awakeMinutes: payload.sleep.awakeMinutes,
                    lightMinutes: payload.sleep.lightMinutes,
                    deepMinutes: payload.sleep.deepMinutes,
                    remMinutes: payload.sleep.remMinutes
                )

                // Upsert SpO2 samples
                record.spo2Samples = payload.spo2.samples.map {
                    SpO2SampleRecord(timestamp: $0.timestamp, percentage: $0.percentage)
                }

                try context.save()
                logger.info("Saved SwiftData context for date \(dateKey)")
            } catch {
                logger.error("Error saving SwiftData record: \(error.localizedDescription)")
            }
        }
    }

    /// Triggers pulling health telemetry from the watch over Channel 0x04.
    public func pullTelemetry(completion: @escaping (Result<HealthTelemetryPayload, Error>) -> Void) {
        DispatchQueue.main.async {
            self.isPulling = true
        }

        BLEServerManager.shared.sendPullTelemetryCommand { [weak self] result in
            DispatchQueue.main.async {
                self?.isPulling = false
                switch result {
                case .success(let payload):
                    self?.latestTelemetry = payload
                    self?.lastSyncDate = Date()
                    completion(.success(payload))
                case .failure(let error):
                    completion(.failure(error))
                }
            }
        }
    }
}

