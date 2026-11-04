package com.wearos.ancsbridge

import com.wearos.ancsbridge.ble.BLEPacketMultiplexer
import com.wearos.ancsbridge.contacts.ContactRepository
import com.wearos.ancsbridge.weather.HourlyForecast
import com.wearos.ancsbridge.weather.WeatherData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2SyncTest {

    @Test
    fun testPhoneNumberNormalization() {
        assertEquals("+15551234567", ContactRepository.normalizePhoneNumber("+1 (555) 123-4567"))
        assertEquals("8005550199", ContactRepository.normalizePhoneNumber("(800) 555-0199"))
        assertEquals("+442079460958", ContactRepository.normalizePhoneNumber("  +44 20 7946 0958  "))
        assertEquals("5551234", ContactRepository.normalizePhoneNumber("555-1234"))
        assertEquals("", ContactRepository.normalizePhoneNumber("   "))
        assertEquals("", ContactRepository.normalizePhoneNumber("abc---!"))
    }

    @Test
    fun testSignificantSuffixExtraction() {
        assertEquals("5551234567", ContactRepository.extractSignificantSuffix("+15551234567"))
        assertEquals("5551234567", ContactRepository.extractSignificantSuffix("5551234567"))
        assertEquals("1234567", ContactRepository.extractSignificantSuffix("123-4567"))
        assertEquals("1234", ContactRepository.extractSignificantSuffix("1234"))
    }

    @Test
    fun testWeatherDataSerializationRoundTrip() {
        val original = WeatherData(
            temp = 21.5,
            weatherCode = 1,
            high = 24.0,
            low = 15.0,
            isDay = 1,
            cityName = "Cupertino",
            hourly = listOf(
                HourlyForecast(time = 1730649600L, temp = 21.0, code = 1),
                HourlyForecast(time = 1730653200L, temp = 22.5, code = 2)
            ),
            updatedAt = 1730647200L
        )

        val jsonStr = original.toJsonString()
        assertTrue(jsonStr.contains("Cupertino"))
        assertTrue(jsonStr.contains("21.5"))

        val parsed = WeatherData.fromJsonString(jsonStr)
        assertNotNull(parsed)
        assertEquals(original.cityName, parsed!!.cityName)
        assertEquals(original.temp, parsed.temp, 0.001)
        assertEquals(original.weatherCode, parsed.weatherCode)
        assertEquals(original.high, parsed.high, 0.001)
        assertEquals(original.low, parsed.low, 0.001)
        assertEquals(original.isDay, parsed.isDay)
        assertEquals(2, parsed.hourly.size)
        assertEquals(22.5, parsed.hourly[1].temp, 0.001)
        assertEquals("Mainly Clear", parsed.conditionDescription())
    }

    @Test
    fun testWeatherConditionDescriptions() {
        val clearDay = WeatherData(20.0, 0, 20.0, 20.0, 1, "City", emptyList(), 0)
        assertEquals("Clear Sky", clearDay.conditionDescription())

        val clearNight = WeatherData(15.0, 0, 20.0, 15.0, 0, "City", emptyList(), 0)
        assertEquals("Clear Night", clearNight.conditionDescription())

        val rain = WeatherData(12.0, 61, 14.0, 10.0, 1, "City", emptyList(), 0)
        assertEquals("Rain", rain.conditionDescription())

        val snow = WeatherData(-2.0, 71, 0.0, -5.0, 1, "City", emptyList(), 0)
        assertEquals("Snow", snow.conditionDescription())

        val storm = WeatherData(18.0, 95, 20.0, 15.0, 1, "City", emptyList(), 0)
        assertEquals("Thunderstorm", storm.conditionDescription())
    }

    @Test
    fun testMultiplexerChannelsForContactsAndWeather() {
        val multiplexer = BLEPacketMultiplexer()
        val contactsJson = "[{\"fullName\":\"John Doe\",\"phoneNumbers\":[\"+15550192834\"]}]".toByteArray(Charsets.UTF_8)
        val weatherJson = "{\"temp\":20.0,\"weatherCode\":0}".toByteArray(Charsets.UTF_8)

        var receivedContacts: ByteArray? = null
        var receivedWeather: ByteArray? = null

        multiplexer.onPayloadReceived = { channel, payload ->
            when (channel) {
                BLEPacketMultiplexer.CHANNEL_CONTACTS_JSON -> receivedContacts = payload
                BLEPacketMultiplexer.CHANNEL_WEATHER_JSON -> receivedWeather = payload
            }
        }

        val contactChunks = multiplexer.createChunks(BLEPacketMultiplexer.CHANNEL_CONTACTS_JSON, contactsJson, 50)
        for (chunk in contactChunks) {
            multiplexer.processIncomingChunk(chunk)
        }
        assertNotNull(receivedContacts)
        assertEquals(String(contactsJson), String(receivedContacts!!))

        val weatherChunks = multiplexer.createChunks(BLEPacketMultiplexer.CHANNEL_WEATHER_JSON, weatherJson, 50)
        for (chunk in weatherChunks) {
            multiplexer.processIncomingChunk(chunk)
        }
        assertNotNull(receivedWeather)
        assertEquals(String(weatherJson), String(receivedWeather!!))
    }
}

