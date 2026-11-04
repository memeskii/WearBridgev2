import Foundation
import CoreLocation
import os.log
import Combine

/// Hourly forecast slice sent to the watch.
public struct HourlyForecastItem: Codable, Equatable {
    public let time: Int64
    public let temp: Double
    public let code: Int

    public init(time: Int64, temp: Double, code: Int) {
        self.time = time
        self.temp = temp
        self.code = code
    }
}

/// Compact weather payload transmitted over BLE Channel 0x03.
public struct CompactWeatherPayload: Codable, Equatable {
    public let temp: Double
    public let weatherCode: Int
    public let high: Double
    public let low: Double
    public let isDay: Int
    public let cityName: String
    public let hourly: [HourlyForecastItem]
    public let updatedAt: Int64

    public init(
        temp: Double,
        weatherCode: Int,
        high: Double,
        low: Double,
        isDay: Int,
        cityName: String,
        hourly: [HourlyForecastItem],
        updatedAt: Int64
    ) {
        self.temp = temp
        self.weatherCode = weatherCode
        self.high = high
        self.low = low
        self.isDay = isDay
        self.cityName = cityName
        self.hourly = hourly
        self.updatedAt = updatedAt
    }
}

/// Coordinates fetching local weather from the Open-Meteo API using CoreLocation,
/// packing the response into a compact JSON structure, and pushing it over
/// BLE Channel 0x03 every 30 minutes and on watch connection.
public class WeatherSyncService: NSObject, ObservableObject {

    public static let shared = WeatherSyncService()

    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "WeatherSyncService")
    private let locationManager = CLLocationManager()
    private let geocoder = CLGeocoder()

    private var syncTimer: Timer?
    private var pendingCompletion: ((Result<CompactWeatherPayload, Error>) -> Void)?

    private let userDefaultsLatitudeKey = "wearbridge_last_valid_latitude"
    private let userDefaultsLongitudeKey = "wearbridge_last_valid_longitude"
    private let userDefaultsCityNameKey = "wearbridge_last_valid_city_name"

    @Published public var isFetching: Bool = false
    @Published public var latestWeather: CompactWeatherPayload? = nil
    @Published public var lastSyncDate: Date? = nil
    @Published public var locationName: String = "Locating..."

    private var lastLocation: CLLocation?

    override private init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyKilometer

        // Load cached city name if available
        if let cachedCity = UserDefaults.standard.string(forKey: userDefaultsCityNameKey) {
            locationName = cachedCity
        }

        // Start 30-minute recurring timer
        startPeriodicSync()
    }

    public func startPeriodicSync() {
        syncTimer?.invalidate()
        // 30 minutes = 1800 seconds
        syncTimer = Timer.scheduledTimer(withTimeInterval: 1800, repeats: true) { [weak self] _ in
            self?.logger.info("30-minute weather sync timer triggered")
            self?.syncWeather(force: true)
        }
    }

    public func stopPeriodicSync() {
        syncTimer?.invalidate()
        syncTimer = nil
    }

    /// Request location permission if not already granted.
    public func requestAuthorization() {
        let status = locationManager.authorizationStatus
        if status == .notDetermined {
            locationManager.requestWhenInUseAuthorization()
        } else if status == .authorizedWhenInUse || status == .authorizedAlways {
            locationManager.requestLocation()
        }
    }

    public func getCachedLocationAndCity() -> (location: CLLocation, cityName: String)? {
        let defaults = UserDefaults.standard
        if defaults.object(forKey: userDefaultsLatitudeKey) != nil,
           defaults.object(forKey: userDefaultsLongitudeKey) != nil {
            let lat = defaults.double(forKey: userDefaultsLatitudeKey)
            let lon = defaults.double(forKey: userDefaultsLongitudeKey)
            let city = defaults.string(forKey: userDefaultsCityNameKey) ?? "Local"
            return (CLLocation(latitude: lat, longitude: lon), city)
        }
        return nil
    }

    /// Fetch and sync weather data to watch over BLE Channel 0x03.
    public func syncWeather(force: Bool = false, completion: ((Result<CompactWeatherPayload, Error>) -> Void)? = nil) {
        pendingCompletion = completion

        let status = locationManager.authorizationStatus
        if status == .notDetermined {
            locationManager.requestWhenInUseAuthorization()
            return
        }

        // Check if device is locked or location unavailable: immediately fall back to cached coordinates
        if let location = locationManager.location {
            lastLocation = location
            fetchWeatherForLocation(location)
            return
        }

        if let cached = getCachedLocationAndCity() {
            logger.info("Using cached coordinates for background sync: \(cached.cityName) (\(cached.location.coordinate.latitude), \(cached.location.coordinate.longitude))")
            lastLocation = cached.location
            fetchWeatherForLocation(cached.location)
            return
        }

        guard status == .authorizedWhenInUse || status == .authorizedAlways else {
            logger.warning("Location access denied or restricted. Using fallback coordinate.")
            let fallbackLocation = CLLocation(latitude: 37.3318, longitude: -122.0312)
            fetchWeatherForLocation(fallbackLocation)
            return
        }

        // Request a fresh one-shot location update
        locationManager.requestLocation()
    }

    private func fetchWeatherForLocation(_ location: CLLocation) {
        DispatchQueue.main.async {
            self.isFetching = true
        }

        let lat = location.coordinate.latitude
        let lon = location.coordinate.longitude

        // Reverse geocode to get human-readable city name
        geocoder.reverseGeocodeLocation(location) { [weak self] placemarks, _ in
            guard let self = self else { return }
            let city = placemarks?.first?.locality ?? placemarks?.first?.subAdministrativeArea ?? UserDefaults.standard.string(forKey: self.userDefaultsCityNameKey) ?? "Local"
            DispatchQueue.main.async {
                self.locationName = city
            }

            // Cache coordinates and city in UserDefaults
            UserDefaults.standard.set(lat, forKey: self.userDefaultsLatitudeKey)
            UserDefaults.standard.set(lon, forKey: self.userDefaultsLongitudeKey)
            UserDefaults.standard.set(city, forKey: self.userDefaultsCityNameKey)

            self.queryOpenMeteo(latitude: lat, longitude: lon, cityName: city)
        }
    }

    private func queryOpenMeteo(latitude: Double, longitude: Double, cityName: String) {
        let urlString = "https://api.open-meteo.com/v1/forecast?latitude=\(String(format: "%.4f", latitude))&longitude=\(String(format: "%.4f", longitude))&current=temperature_2m,is_day,weather_code&hourly=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=2"

        guard let url = URL(string: urlString) else {
            finishWithFailure(NSError(domain: "WearBridge.Weather", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid Open-Meteo URL"]))
            return
        }

        var request = URLRequest(url: url)
        request.timeoutInterval = 15.0

        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self else { return }

            if let error = error {
                self.finishWithFailure(error)
                return
            }

            guard let data = data else {
                self.finishWithFailure(NSError(domain: "WearBridge.Weather", code: -2, userInfo: [NSLocalizedDescriptionKey: "Empty response from Open-Meteo"]))
                return
            }

            do {
                let compact = try self.parseOpenMeteoResponse(data: data, cityName: cityName)
                self.publishAndTransmitWeather(compact)
            } catch {
                self.logger.error("Failed to parse Open-Meteo JSON: \(error.localizedDescription)")
                self.finishWithFailure(error)
            }
        }.resume()
    }

    private func parseOpenMeteoResponse(data: Data, cityName: String) throws -> CompactWeatherPayload {
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw NSError(domain: "WearBridge.Weather", code: -3, userInfo: [NSLocalizedDescriptionKey: "Malformed root JSON"])
        }

        // 1. Current Weather
        guard let current = json["current"] as? [String: Any] else {
            throw NSError(domain: "WearBridge.Weather", code: -4, userInfo: [NSLocalizedDescriptionKey: "Missing current weather block"])
        }

        let temp = (current["temperature_2m"] as? NSNumber)?.doubleValue ?? 20.0
        let weatherCode = (current["weather_code"] as? NSNumber)?.intValue ?? 0
        let isDay = (current["is_day"] as? NSNumber)?.intValue ?? 1

        // 2. Daily High / Low
        var high = temp
        var low = temp
        if let daily = json["daily"] as? [String: Any] {
            if let maxList = daily["temperature_2m_max"] as? [Double], let firstMax = maxList.first {
                high = firstMax
            }
            if let minList = daily["temperature_2m_min"] as? [Double], let firstMin = minList.first {
                low = firstMin
            }
        }

        // 3. Hourly Forecast (12 hours)
        var hourlyItems: [HourlyForecastItem] = []
        if let hourly = json["hourly"] as? [String: Any],
           let times = hourly["time"] as? [String],
           let temps = hourly["temperature_2m"] as? [Double],
           let codes = hourly["weather_code"] as? [Int] {

            let nowEpoch = Date().timeIntervalSince1970
            let isoFormatter = ISO8601DateFormatter()
            isoFormatter.formatOptions = [.withFullDate, .withTime, .withDashSeparatorInDate, .withColonSeparatorInTime]

            var parsedHours: [(epoch: Int64, temp: Double, code: Int)] = []

            for i in 0..<min(times.count, min(temps.count, codes.count)) {
                let timeStr = times[i]
                var epoch: Int64 = 0

                if let date = isoFormatter.date(from: timeStr) {
                    epoch = Int64(date.timeIntervalSince1970)
                } else {
                    // Fallback simple parsing "YYYY-MM-DDTHH:MM"
                    let df = DateFormatter()
                    df.dateFormat = "yyyy-MM-dd'T'HH:mm"
                    df.timeZone = TimeZone.current
                    if let d = df.date(from: timeStr) {
                        epoch = Int64(d.timeIntervalSince1970)
                    }
                }

                parsedHours.append((epoch: epoch, temp: temps[i], code: codes[i]))
            }

            // Find hours starting from now (or within the last 45 minutes)
            let startIndex = parsedHours.firstIndex { Double($0.epoch) >= (nowEpoch - 2700) } ?? 0
            let endIndex = min(startIndex + 12, parsedHours.count)

            for i in startIndex..<endIndex {
                let item = parsedHours[i]
                hourlyItems.append(HourlyForecastItem(time: item.epoch, temp: item.temp, code: item.code))
            }
        }

        let nowSec = Int64(Date().timeIntervalSince1970)
        return CompactWeatherPayload(
            temp: temp,
            weatherCode: weatherCode,
            high: high,
            low: low,
            isDay: isDay,
            cityName: cityName,
            hourly: hourlyItems,
            updatedAt: nowSec
        )
    }

    private func publishAndTransmitWeather(_ payload: CompactWeatherPayload) {
        do {
            let encoder = JSONEncoder()
            let jsonData = try encoder.encode(payload)

            logger.info("Encoded compact weather payload (\(jsonData.count) bytes) for \(payload.cityName): \(payload.temp)°C, code \(payload.weatherCode)")

            // Transmit over BLE Channel 0x03
            BLEServerManager.shared.sendWeatherPayload(jsonData)

            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.isFetching = false
                self.latestWeather = payload
                self.lastSyncDate = Date()
                self.pendingCompletion?(.success(payload))
                self.pendingCompletion = nil
            }
        } catch {
            finishWithFailure(error)
        }
    }

    private func finishWithFailure(_ error: Error) {
        logger.error("Weather sync failed: \(error.localizedDescription)")
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.isFetching = false
            self.pendingCompletion?(.failure(error))
            self.pendingCompletion = nil
        }
    }
}

// MARK: - CLLocationManagerDelegate
extension WeatherSyncService: CLLocationManagerDelegate {

    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        logger.info("Location authorization changed: \(status.rawValue)")
        if status == .authorizedWhenInUse || status == .authorizedAlways {
            manager.requestLocation()
        }
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        lastLocation = location
        fetchWeatherForLocation(location)
    }

    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        logger.error("CoreLocation didFailWithError: \(error.localizedDescription)")
        if let cached = getCachedLocationAndCity() {
            logger.info("CoreLocation failed (device likely locked). Falling back to cached coordinates: \(cached.cityName)")
            lastLocation = cached.location
            queryOpenMeteo(latitude: cached.location.coordinate.latitude, longitude: cached.location.coordinate.longitude, cityName: cached.cityName)
        } else {
            let fallback = lastLocation ?? CLLocation(latitude: 37.3318, longitude: -122.0312)
            fetchWeatherForLocation(fallback)
        }
    }
}

