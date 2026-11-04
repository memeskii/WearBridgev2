package com.wearos.ancsbridge.weather

import org.json.JSONArray
import org.json.JSONObject

/**
 * Hourly forecast item for watch display.
 */
data class HourlyForecast(
    val time: Long,
    val temp: Double,
    val code: Int
)

/**
 * Complete synchronized weather model parsed from BLE Channel 0x03.
 */
data class WeatherData(
    val temp: Double,
    val weatherCode: Int,
    val high: Double,
    val low: Double,
    val isDay: Int,
    val cityName: String,
    val hourly: List<HourlyForecast>,
    val updatedAt: Long
) {
    /**
     * Human-readable condition description mapped from WMO weather codes.
     */
    fun conditionDescription(): String = when (weatherCode) {
        0 -> if (isDay == 1) "Clear Sky" else "Clear Night"
        1 -> "Mainly Clear"
        2 -> "Partly Cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing Drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing Rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow Grains"
        80, 81, 82 -> "Rain Showers"
        85, 86 -> "Snow Showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm & Hail"
        else -> "Variable"
    }

    /**
     * Converts to JSON string for persistent DataStore storage.
     */
    fun toJsonString(): String {
        val root = JSONObject()
        root.put("temp", temp)
        root.put("weatherCode", weatherCode)
        root.put("high", high)
        root.put("low", low)
        root.put("isDay", isDay)
        root.put("cityName", cityName)
        root.put("updatedAt", updatedAt)

        val hourlyArray = JSONArray()
        for (item in hourly) {
            val h = JSONObject()
            h.put("time", item.time)
            h.put("temp", item.temp)
            h.put("code", item.code)
            hourlyArray.put(h)
        }
        root.put("hourly", hourlyArray)

        return root.toString()
    }

    companion object {
        fun fromJsonString(jsonStr: String): WeatherData? {
            return try {
                val json = JSONObject(jsonStr)
                val temp = json.getDouble("temp")
                val weatherCode = json.getInt("weatherCode")
                val high = json.optDouble("high", temp)
                val low = json.optDouble("low", temp)
                val isDay = json.optInt("isDay", 1)
                val cityName = json.optString("cityName", "Local")
                val updatedAt = json.optLong("updatedAt", System.currentTimeMillis() / 1000)

                val hourlyList = mutableListOf<HourlyForecast>()
                val hourlyArray = json.optJSONArray("hourly")
                if (hourlyArray != null) {
                    for (i in 0 until hourlyArray.length()) {
                        val h = hourlyArray.getJSONObject(i)
                        hourlyList.add(
                            HourlyForecast(
                                time = h.optLong("time", 0),
                                temp = h.optDouble("temp", 0.0),
                                code = h.optInt("code", 0)
                            )
                        )
                    }
                }

                WeatherData(
                    temp = temp,
                    weatherCode = weatherCode,
                    high = high,
                    low = low,
                    isDay = isDay,
                    cityName = cityName,
                    hourly = hourlyList,
                    updatedAt = updatedAt
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

