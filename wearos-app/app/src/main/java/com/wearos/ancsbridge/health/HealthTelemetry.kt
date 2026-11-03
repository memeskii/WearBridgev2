package com.wearos.ancsbridge.health

import org.json.JSONObject

/**
 * Standardized Health Telemetry payload schema for cross-device bridge synchronization.
 * Thread-safe model supporting serialization to and deserialization from clean JSON.
 */
data class SleepStages(
    val deep_minutes: Int = 0,
    val light_minutes: Int = 0,
    val rem_minutes: Int = 0,
    val awake_minutes: Int = 0
) {
    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("deep_minutes", deep_minutes)
        json.put("light_minutes", light_minutes)
        json.put("rem_minutes", rem_minutes)
        json.put("awake_minutes", awake_minutes)
        return json
    }

    companion object {
        fun fromJsonObject(json: JSONObject?): SleepStages {
            if (json == null) return SleepStages()
            return SleepStages(
                deep_minutes = json.optInt("deep_minutes", 0),
                light_minutes = json.optInt("light_minutes", 0),
                rem_minutes = json.optInt("rem_minutes", 0),
                awake_minutes = json.optInt("awake_minutes", 0)
            )
        }
    }
}

data class SleepData(
    val status: String = "asleep", // "awake", "light", "deep", "rem", "asleep"
    val duration_minutes: Int = 0,
    val stages: SleepStages = SleepStages(),
    val efficiency: Int = 0 // 0-100%
) {
    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("status", status)
        json.put("duration_minutes", duration_minutes)
        json.put("stages", stages.toJsonObject())
        json.put("efficiency", efficiency)
        return json
    }

    companion object {
        fun fromJsonObject(json: JSONObject?): SleepData {
            if (json == null) return SleepData()
            return SleepData(
                status = json.optString("status", "awake"),
                duration_minutes = json.optInt("duration_minutes", 0),
                stages = SleepStages.fromJsonObject(json.optJSONObject("stages")),
                efficiency = json.optInt("efficiency", 0)
            )
        }
    }
}

data class HealthMetrics(
    val heart_rate: Int = 0,
    val blood_oxygen: Int = 0,
    val stress_level: Int = 0,
    val steps: Int = 0,
    val calories_burnt: Int = 0,
    val sleep_data: SleepData = SleepData(),
    val active_calories: Int = 0,
    val resting_calories: Int = 0
) {
    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("heart_rate", heart_rate)
        json.put("blood_oxygen", blood_oxygen)
        json.put("stress_level", stress_level)
        json.put("steps", steps)
        json.put("calories_burnt", calories_burnt)
        json.put("active_calories", active_calories)
        json.put("resting_calories", resting_calories)
        json.put("sleep_data", sleep_data.toJsonObject())
        return json
    }

    companion object {
        fun fromJsonObject(json: JSONObject?): HealthMetrics {
            if (json == null) return HealthMetrics()
            return HealthMetrics(
                heart_rate = json.optInt("heart_rate", 0),
                blood_oxygen = json.optInt("blood_oxygen", 0),
                stress_level = json.optInt("stress_level", 0),
                steps = json.optInt("steps", 0),
                calories_burnt = json.optInt("calories_burnt", 0),
                active_calories = json.optInt("active_calories", 0),
                resting_calories = json.optInt("resting_calories", 0),
                sleep_data = SleepData.fromJsonObject(json.optJSONObject("sleep_data"))
            )
        }
    }
}

data class HealthTelemetryPayload(
    val timestamp: Long = System.currentTimeMillis() / 1000L,
    val metrics: HealthMetrics = HealthMetrics()
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("timestamp", timestamp)
        json.put("metrics", metrics.toJsonObject())
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): HealthTelemetryPayload {
            val json = JSONObject(jsonStr)
            val ts = json.optLong("timestamp", System.currentTimeMillis() / 1000L)
            val metricsObj = json.optJSONObject("metrics")
            return HealthTelemetryPayload(
                timestamp = ts,
                metrics = HealthMetrics.fromJsonObject(metricsObj)
            )
        }
    }
}
