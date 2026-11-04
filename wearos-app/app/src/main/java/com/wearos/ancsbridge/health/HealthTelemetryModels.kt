package com.wearos.ancsbridge.health

import org.json.JSONArray
import org.json.JSONObject

data class SpO2Sample(
    val timestamp: Long,
    val percentage: Int
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("percentage", percentage)
    }

    companion object {
        fun fromJsonObject(json: JSONObject): SpO2Sample = SpO2Sample(
            timestamp = json.optLong("timestamp", 0L),
            percentage = json.optInt("percentage", 0)
        )
    }
}

data class SpO2Data(
    val latest: Int,
    val avg: Double,
    val min: Int,
    val samples: List<SpO2Sample> = emptyList()
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("latest", latest)
        put("avg", avg)
        put("min", min)
        val arr = JSONArray()
        for (sample in samples) {
            arr.put(sample.toJsonObject())
        }
        put("samples", arr)
    }

    companion object {
        fun fromJsonObject(json: JSONObject?): SpO2Data {
            if (json == null) return SpO2Data(0, 0.0, 0)
            val sampleList = mutableListOf<SpO2Sample>()
            val arr = json.optJSONArray("samples")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    sampleList.add(SpO2Sample.fromJsonObject(arr.getJSONObject(i)))
                }
            }
            return SpO2Data(
                latest = json.optInt("latest", 0),
                avg = json.optDouble("avg", 0.0),
                min = json.optInt("min", 0),
                samples = sampleList
            )
        }
    }
}

data class CaloriesData(
    val total: Double,
    val active: Double
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("total", total)
        put("active", active)
    }

    companion object {
        fun fromJsonObject(json: JSONObject?): CaloriesData {
            if (json == null) return CaloriesData(0.0, 0.0)
            return CaloriesData(
                total = json.optDouble("total", 0.0),
                active = json.optDouble("active", 0.0)
            )
        }
    }
}

data class SleepSessionData(
    val totalDurationMinutes: Int,
    val sleepScore: Int,
    val awakeMinutes: Int,
    val lightMinutes: Int,
    val deepMinutes: Int,
    val remMinutes: Int
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("totalDurationMinutes", totalDurationMinutes)
        put("sleepScore", sleepScore)
        put("awakeMinutes", awakeMinutes)
        put("lightMinutes", lightMinutes)
        put("deepMinutes", deepMinutes)
        put("remMinutes", remMinutes)
    }

    companion object {
        fun fromJsonObject(json: JSONObject?): SleepSessionData {
            if (json == null) return SleepSessionData(0, 0, 0, 0, 0, 0)
            return SleepSessionData(
                totalDurationMinutes = json.optInt("totalDurationMinutes", 0),
                sleepScore = json.optInt("sleepScore", 0),
                awakeMinutes = json.optInt("awakeMinutes", 0),
                lightMinutes = json.optInt("lightMinutes", 0),
                deepMinutes = json.optInt("deepMinutes", 0),
                remMinutes = json.optInt("remMinutes", 0)
            )
        }
    }
}

data class StressData(
    val stressScore: Int,
    val hrvRmssd: Double
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("stressScore", stressScore)
        put("hrvRmssd", hrvRmssd)
    }

    companion object {
        fun fromJsonObject(json: JSONObject?): StressData {
            if (json == null) return StressData(0, 0.0)
            return StressData(
                stressScore = json.optInt("stressScore", 0),
                hrvRmssd = json.optDouble("hrvRmssd", 0.0)
            )
        }
    }
}

data class ActivityData(
    val steps: Int,
    val heartRate: Int,
    val restingHeartRate: Int
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("steps", steps)
        put("heartRate", heartRate)
        put("restingHeartRate", restingHeartRate)
    }

    companion object {
        fun fromJsonObject(json: JSONObject?): ActivityData {
            if (json == null) return ActivityData(0, 0, 0)
            return ActivityData(
                steps = json.optInt("steps", 0),
                heartRate = json.optInt("heartRate", 0),
                restingHeartRate = json.optInt("restingHeartRate", 0)
            )
        }
    }
}

/**
 * Standardized Phase 3 health telemetry payload synced over BLE Channel 0x04.
 */
data class DailyHealthTelemetryPayload(
    val date: String,
    val timestamp: Long,
    val calories: CaloriesData,
    val spo2: SpO2Data,
    val sleep: SleepSessionData,
    val stress: StressData,
    val activity: ActivityData
) {
    fun toJson(): String = toJsonString()

    fun toJsonString(): String {
        val root = JSONObject()
        root.put("date", date)
        root.put("timestamp", timestamp)
        root.put("calories", calories.toJsonObject())
        root.put("spo2", spo2.toJsonObject())
        root.put("sleep", sleep.toJsonObject())
        root.put("stress", stress.toJsonObject())
        root.put("activity", activity.toJsonObject())
        return root.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): DailyHealthTelemetryPayload? = fromJsonString(jsonStr)

        fun fromJsonString(jsonStr: String): DailyHealthTelemetryPayload? {
            return try {
                val json = JSONObject(jsonStr)
                DailyHealthTelemetryPayload(
                    date = json.optString("date", ""),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis() / 1000),
                    calories = CaloriesData.fromJsonObject(json.optJSONObject("calories")),
                    spo2 = SpO2Data.fromJsonObject(json.optJSONObject("spo2")),
                    sleep = SleepSessionData.fromJsonObject(json.optJSONObject("sleep")),
                    stress = StressData.fromJsonObject(json.optJSONObject("stress")),
                    activity = ActivityData.fromJsonObject(json.optJSONObject("activity"))
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
