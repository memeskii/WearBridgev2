import Foundation
import SwiftData

// MARK: - Codable Payload Structs for BLE Channel 0x04

public struct SpO2SamplePayload: Codable, Equatable, Hashable {
    public let timestamp: Int64
    public let percentage: Int

    public init(timestamp: Int64, percentage: Int) {
        self.timestamp = timestamp
        self.percentage = percentage
    }
}

public struct SpO2Payload: Codable, Equatable {
    public let latest: Int
    public let avg: Double
    public let min: Int
    public let samples: [SpO2SamplePayload]

    public init(latest: Int, avg: Double, min: Int, samples: [SpO2SamplePayload] = []) {
        self.latest = latest
        self.avg = avg
        self.min = min
        self.samples = samples
    }
}

public struct CaloriesPayload: Codable, Equatable {
    public let total: Double
    public let active: Double

    public init(total: Double, active: Double) {
        self.total = total
        self.active = active
    }
}

public struct SleepPayload: Codable, Equatable {
    public let totalDurationMinutes: Int
    public let sleepScore: Int
    public let awakeMinutes: Int
    public let lightMinutes: Int
    public let deepMinutes: Int
    public let remMinutes: Int

    public init(
        totalDurationMinutes: Int,
        sleepScore: Int,
        awakeMinutes: Int,
        lightMinutes: Int,
        deepMinutes: Int,
        remMinutes: Int
    ) {
        self.totalDurationMinutes = totalDurationMinutes
        self.sleepScore = sleepScore
        self.awakeMinutes = awakeMinutes
        self.lightMinutes = lightMinutes
        self.deepMinutes = deepMinutes
        self.remMinutes = remMinutes
    }
}

public struct StressPayload: Codable, Equatable {
    public let stressScore: Int
    public let hrvRmssd: Double

    public init(stressScore: Int, hrvRmssd: Double) {
        self.stressScore = stressScore
        self.hrvRmssd = hrvRmssd
    }
}

public struct ActivityPayload: Codable, Equatable {
    public let steps: Int
    public let heartRate: Int
    public let restingHeartRate: Int

    public init(steps: Int, heartRate: Int, restingHeartRate: Int) {
        self.steps = steps
        self.heartRate = heartRate
        self.restingHeartRate = restingHeartRate
    }
}

/// Consolidated 24-hour health telemetry payload received from Wear OS over Channel 0x04.
public struct HealthTelemetryPayload: Codable, Equatable {
    public let date: String
    public let timestamp: Int64
    public let calories: CaloriesPayload
    public let spo2: SpO2Payload
    public let sleep: SleepPayload
    public let stress: StressPayload
    public let activity: ActivityPayload

    public init(
        date: String,
        timestamp: Int64,
        calories: CaloriesPayload,
        spo2: SpO2Payload,
        sleep: SleepPayload,
        stress: StressPayload,
        activity: ActivityPayload
    ) {
        self.date = date
        self.timestamp = timestamp
        self.calories = calories
        self.spo2 = spo2
        self.sleep = sleep
        self.stress = stress
        self.activity = activity
    }
}

// MARK: - SwiftData Persistence Models (iOS 17+)

@available(iOS 17.0, *)
@Model
public final class DailyHealthRecord {
    @Attribute(.unique) public var dateKey: String // "yyyy-MM-dd"
    public var timestamp: Int64
    public var steps: Int
    public var heartRate: Int
    public var restingHeartRate: Int
    public var caloriesTotal: Double
    public var caloriesActive: Double
    public var spo2Latest: Int
    public var spo2Avg: Double
    public var spo2Min: Int
    public var sleepDurationMinutes: Int
    public var sleepScore: Int
    public var stressScore: Int
    public var hrvRmssd: Double

    @Relationship(deleteRule: .cascade) public var sleepStages: SleepStageRecord?
    @Relationship(deleteRule: .cascade) public var spo2Samples: [SpO2SampleRecord] = []

    public init(
        dateKey: String,
        timestamp: Int64,
        steps: Int,
        heartRate: Int,
        restingHeartRate: Int,
        caloriesTotal: Double,
        caloriesActive: Double,
        spo2Latest: Int,
        spo2Avg: Double,
        spo2Min: Int,
        sleepDurationMinutes: Int,
        sleepScore: Int,
        stressScore: Int,
        hrvRmssd: Double,
        sleepStages: SleepStageRecord? = nil,
        spo2Samples: [SpO2SampleRecord] = []
    ) {
        self.dateKey = dateKey
        self.timestamp = timestamp
        self.steps = steps
        self.heartRate = heartRate
        self.restingHeartRate = restingHeartRate
        self.caloriesTotal = caloriesTotal
        self.caloriesActive = caloriesActive
        self.spo2Latest = spo2Latest
        self.spo2Avg = spo2Avg
        self.spo2Min = spo2Min
        self.sleepDurationMinutes = sleepDurationMinutes
        self.sleepScore = sleepScore
        self.stressScore = stressScore
        self.hrvRmssd = hrvRmssd
        self.sleepStages = sleepStages
        self.spo2Samples = spo2Samples
    }
}

@available(iOS 17.0, *)
@Model
public final class SleepStageRecord {
    public var awakeMinutes: Int
    public var lightMinutes: Int
    public var deepMinutes: Int
    public var remMinutes: Int

    public init(awakeMinutes: Int, lightMinutes: Int, deepMinutes: Int, remMinutes: Int) {
        self.awakeMinutes = awakeMinutes
        self.lightMinutes = lightMinutes
        self.deepMinutes = deepMinutes
        self.remMinutes = remMinutes
    }
}

@available(iOS 17.0, *)
@Model
public final class SpO2SampleRecord {
    public var timestamp: Int64
    public var percentage: Int

    public init(timestamp: Int64, percentage: Int) {
        self.timestamp = timestamp
        self.percentage = percentage
    }
}

