package com.wearos.ancsbridge.weather

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

val Context.weatherDataStore: DataStore<Preferences> by preferencesDataStore(name = "wearbridge_weather")

/**
 * Repository persisting synchronized weather data using Jetpack DataStore.
 * Exposes a reactive Flow for UI components and offline access for Wear OS Tiles.
 */
class WeatherRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WeatherRepository"
        val KEY_WEATHER_JSON = stringPreferencesKey("latest_weather_json")

        @Volatile
        private var INSTANCE: WeatherRepository? = null

        fun getInstance(context: Context): WeatherRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = WeatherRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val weatherFlow: Flow<WeatherData?> = context.weatherDataStore.data.map { preferences ->
        val json = preferences[KEY_WEATHER_JSON]
        if (!json.isNullOrEmpty()) {
            WeatherData.fromJsonString(json)
        } else {
            null
        }
    }

    val weatherState: StateFlow<WeatherData?> = weatherFlow.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    /**
     * Get synchronous snapshot of latest weather, useful for Wear OS Tile rendering.
     */
    fun getLatestWeather(): WeatherData? {
        weatherState.value?.let { return it }
        return runBlocking(Dispatchers.IO) {
            val prefs = context.weatherDataStore.data.firstOrNull()
            val json = prefs?.get(KEY_WEATHER_JSON)
            if (!json.isNullOrEmpty()) WeatherData.fromJsonString(json) else null
        }
    }

    /**
     * Save weather payload received from Channel 0x03.
     */
    fun saveWeather(payload: ByteArray, onComplete: ((Boolean) -> Void)? = null) {
        val jsonStr = String(payload, Charsets.UTF_8)
        saveWeatherJson(jsonStr)
    }

    /**
     * Save weather JSON string into DataStore and notify Tile.
     */
    fun saveWeatherJson(jsonStr: String) {
        scope.launch {
            try {
                val parsed = WeatherData.fromJsonString(jsonStr)
                if (parsed != null) {
                    context.weatherDataStore.edit { preferences ->
                        preferences[KEY_WEATHER_JSON] = jsonStr
                    }
                    Log.i(TAG, "Saved weather for ${parsed.cityName}: ${parsed.temp}°C, ${parsed.conditionDescription()}")

                    // Request update for Wear OS Tile
                    try {
                        WeatherTileService.requestUpdate(context)
                    } catch (e: Exception) {
                        Log.d(TAG, "Tile update request: ${e.message}")
                    }
                } else {
                    Log.w(TAG, "Failed to parse incoming weather JSON string")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving weather to DataStore: ${e.message}")
            }
        }
    }
}

