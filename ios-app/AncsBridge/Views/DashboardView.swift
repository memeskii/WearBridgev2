import SwiftUI
import Charts

public struct DashboardView: View {
    @EnvironmentObject var viewModel: BLEViewModel
    @ObservedObject private var weatherService = WeatherSyncService.shared
    @ObservedObject private var healthManager = HealthTelemetryManager.shared

    @State private var isPullingTelemetry = false
    @State private var telemetryMessage: String?

    public init() {}

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // 1. Connection Status Pill
                    connectionStatusPill

                    // 1b. Active App Bridges Strip
                    activeAppBridgesSection

                    // 2. Synced Weather Summary Card
                    weatherSummaryCard

                    // 3. Health Telemetry Section Header with Pull Action
                    telemetryHeader

                    // 4. Sleep Stages SwiftCharts Breakdown
                    sleepStagesChartCard

                    // 5. SpO2 Trend SwiftCharts Card
                    spo2TrendChartCard

                    // 6. Stress Score & HRV Gauge Card
                    stressScoreCard

                    // 7. Active vs Total Calories Burned Card
                    caloriesBurnedCard
                }
                .padding(.horizontal)
                .padding(.vertical, 12)
            }
            .navigationTitle("Dashboard")
            .background(Color(.systemGroupedBackground))
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        pullTelemetry()
                    } label: {
                        if isPullingTelemetry {
                            ProgressView()
                                .scaleEffect(0.8)
                        } else {
                            Image(systemName: "arrow.triangle.2.circlepath")
                        }
                    }
                    .disabled(isPullingTelemetry)
                }
            }
        }
    }

    // MARK: - 1. Connection Status Pill
    private var connectionStatusPill: some View {
        let connectedWatches = viewModel.peripheralManager.discoveredWatches.filter { $0.isConnected }
        let isConnected = !connectedWatches.isEmpty
        let watchName = connectedWatches.first?.name ?? "Wear OS Watch"
        let battery = viewModel.watchBatteryLevel
        let isCharging = viewModel.isWatchCharging
        let rssi = connectedWatches.first?.rssi ?? -70

        return HStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(isConnected ? Color.green.opacity(0.15) : Color.orange.opacity(0.15))
                    .frame(width: 44, height: 44)
                Image(systemName: isConnected ? "applewatch.radiowaves.left.and.right" : "applewatch.slash")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundColor(isConnected ? .green : .orange)
            }

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(isConnected ? watchName : "Searching for Watch...")
                        .font(.system(.subheadline, design: .rounded))
                        .fontWeight(.bold)
                    Circle()
                        .fill(isConnected ? Color.green : Color.orange)
                        .frame(width: 8, height: 8)
                }

                HStack(spacing: 8) {
                    if let batt = battery {
                        HStack(spacing: 3) {
                            Image(systemName: isCharging ? "battery.100.bolt" : "battery.75")
                                .foregroundColor(isCharging ? .green : .primary)
                            Text("\(batt)%")
                        }
                        .font(.caption2)
                    }

                    if isConnected {
                        HStack(spacing: 3) {
                            Image(systemName: "antenna.radiowaves.left.and.right")
                            Text("\(rssi) dBm")
                        }
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    }

                    Text("BLE 0x01-0x07")
                        .font(.system(size: 9, weight: .bold, design: .monospaced))
                        .padding(.horizontal, 4)
                        .padding(.vertical, 1)
                        .background(Color.blue.opacity(0.15))
                        .foregroundColor(.blue)
                        .cornerRadius(3)
                }
            }

            Spacer()

            if isConnected {
                VStack(alignment: .trailing, spacing: 2) {
                    Text("ONLINE")
                        .font(.system(size: 10, weight: .heavy))
                        .foregroundColor(.green)
                    Text("Auto-Syncing")
                        .font(.system(size: 9))
                        .foregroundColor(.secondary)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(16)
        .shadow(color: Color.black.opacity(0.04), radius: 6, x: 0, y: 3)
    }

    // MARK: - 1b. Active App Bridges Strip
    private var activeAppBridgesSection: some View {
        let highlightBundles: [String] = [
            AppBrandRegistry.bundleTikTokGlobal,
            AppBrandRegistry.bundleWhatsApp,
            AppBrandRegistry.bundleIMessage,
            AppBrandRegistry.bundleInstagram,
            AppBrandRegistry.bundleTelegram,
            AppBrandRegistry.bundleDiscord,
            AppBrandRegistry.bundleSpotify,
            AppBrandRegistry.bundlePhone
        ]

        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label("ACTIVE APP BRIDGES", systemImage: "app.badge.checkmark.fill")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(.secondary)
                Spacer()
                Text("ANCS + CH 0x06")
                    .font(.system(size: 9, weight: .bold, design: .monospaced))
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(Color.green.opacity(0.12))
                    .foregroundColor(.green)
                    .cornerRadius(4)
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(highlightBundles, id: \.self) { bundleId in
                        let brand = AppBrandRegistry.resolve(bundleId)
                        HStack(spacing: 6) {
                            Image(systemName: brand.sfSymbol)
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundColor(brand.primaryColor)
                            Text(brand.displayName)
                                .font(.system(size: 11, weight: .medium, design: .rounded))
                                .foregroundColor(.primary)
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Color(.secondarySystemGroupedBackground))
                        .cornerRadius(12)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(brand.primaryColor.opacity(0.25), lineWidth: 1)
                        )
                    }
                }
            }
        }
    }

    // MARK: - 2. Synced Weather Summary Card
    private var weatherSummaryCard: some View {
        let weather = weatherService.lastWeatherData

        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Label("SYNCED WEATHER (0x03)", systemImage: "cloud.sun.fill")
                        .font(.caption2)
                        .fontWeight(.bold)
                        .foregroundColor(.blue)

                    Text(weather?.cityName ?? "Cupertino")
                        .font(.title2)
                        .bold()

                    Text(weatherConditionText(code: weather?.weatherCode ?? 1))
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }

                Spacer()

                HStack(alignment: .top, spacing: 2) {
                    Text(String(format: "%.0f", weather?.temp ?? 21.0))
                        .font(.system(size: 42, weight: .thin, design: .rounded))
                    Text("°")
                        .font(.title)
                        .foregroundColor(.secondary)
                }
            }

            if let w = weather, !w.hourly.isEmpty {
                Divider()
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 14) {
                        ForEach(Array(w.hourly.prefix(6).enumerated()), id: \.offset) { _, hour in
                            VStack(spacing: 4) {
                                Text(formatHour(timestamp: hour.time))
                                    .font(.system(size: 10))
                                    .foregroundColor(.secondary)
                                Image(systemName: weatherSymbol(code: hour.code, isDay: 1))
                                    .font(.caption)
                                    .foregroundColor(.blue)
                                Text(String(format: "%.0f°", hour.temp))
                                    .font(.caption2)
                                    .bold()
                            }
                        }
                    }
                }
            }

            HStack {
                Text(String(format: "H: %.0f°  L: %.0f°", weather?.high ?? 24.0, weather?.low ?? 15.0))
                    .font(.caption2)
                    .foregroundColor(.secondary)
                Spacer()
                Text("Synced via Channel 0x03")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(16)
        .shadow(color: Color.black.opacity(0.04), radius: 6, x: 0, y: 3)
    }

    // MARK: - 3. Telemetry Header
    private var telemetryHeader: some View {
        HStack {
            Text("HEALTH TELEMETRY")
                .font(.caption)
                .fontWeight(.bold)
                .foregroundColor(.secondary)
            Spacer()
            if let msg = telemetryMessage {
                Text(msg)
                    .font(.caption2)
                    .foregroundColor(.blue)
            }
        }
        .padding(.horizontal, 4)
        .padding(.top, 4)
    }

    // MARK: - 4. Sleep Stages SwiftCharts Breakdown
    private var sleepStagesChartCard: some View {
        let telemetry = healthManager.lastTelemetry
        let deep = telemetry?.sleep.deepMinutes ?? 115
        let rem = telemetry?.sleep.remMinutes ?? 90
        let light = telemetry?.sleep.lightMinutes ?? 210
        let awake = telemetry?.sleep.awakeMinutes ?? 30
        let totalMin = telemetry?.sleep.totalDurationMinutes ?? 445
        let score = telemetry?.sleep.sleepScore ?? 86

        struct SleepStageItem: Identifiable {
            let id = UUID()
            let stage: String
            let minutes: Int
            let color: Color
        }

        let stages = [
            SleepStageItem(stage: "Deep", minutes: deep, color: .indigo),
            SleepStageItem(stage: "REM", minutes: rem, color: .purple),
            SleepStageItem(stage: "Light", minutes: light, color: .blue),
            SleepStageItem(stage: "Awake", minutes: awake, color: .orange)
        ]

        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Label("Sleep Architecture", systemImage: "bed.double.fill")
                        .font(.footnote)
                        .fontWeight(.semibold)
                        .foregroundColor(.indigo)
                    Text("\(totalMin / 60)h \(totalMin % 60)m")
                        .font(.title3)
                        .bold()
                }

                Spacer()

                ZStack {
                    Circle()
                        .stroke(Color.indigo.opacity(0.2), lineWidth: 4)
                        .frame(width: 44, height: 44)
                    Circle()
                        .trim(from: 0, to: Double(score) / 100.0)
                        .stroke(Color.indigo, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                        .frame(width: 44, height: 44)
                        .rotationEffect(.degrees(-90))
                    Text("\(score)")
                        .font(.system(size: 13, weight: .bold, design: .rounded))
                        .foregroundColor(.indigo)
                }
            }

            // SwiftChart: Horizontal Stacked Bar
            Chart(stages) { item in
                BarMark(
                    x: .value("Minutes", item.minutes)
                )
                .foregroundStyle(item.color)
            }
            .frame(height: 22)
            .chartXAxis(.hidden)
            .chartYAxis(.hidden)
            .cornerRadius(6)

            // Legend
            HStack(spacing: 12) {
                ForEach(stages) { item in
                    HStack(spacing: 4) {
                        Circle()
                            .fill(item.color)
                            .frame(width: 6, height: 6)
                        Text("\(item.stage): \(item.minutes)m")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }
            }
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(16)
        .shadow(color: Color.black.opacity(0.04), radius: 6, x: 0, y: 3)
    }

    // MARK: - 5. SpO2 Trend SwiftCharts Card
    private var spo2TrendChartCard: some View {
        let telemetry = healthManager.lastTelemetry
        let latest = telemetry?.spo2.latest ?? 98
        let avg = telemetry?.spo2.avg ?? 97.4
        let minVal = telemetry?.spo2.min ?? 94

        struct SpO2ChartPoint: Identifiable {
            let id = UUID()
            let timeString: String
            let val: Int
        }

        let points: [SpO2ChartPoint] = (telemetry?.spo2.samples.isEmpty == false) ?
            telemetry!.spo2.samples.enumerated().map { i, s in
                SpO2ChartPoint(timeString: String(format: "+%dh", i * 2), val: s.percentage)
            } : [
                SpO2ChartPoint(timeString: "00:00", val: 96),
                SpO2ChartPoint(timeString: "04:00", val: 95),
                SpO2ChartPoint(timeString: "08:00", val: 98),
                SpO2ChartPoint(timeString: "12:00", val: 99),
                SpO2ChartPoint(timeString: "16:00", val: 97),
                SpO2ChartPoint(timeString: "20:00", val: latest)
            ]

        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Label("Blood Oxygen (SpO2)", systemImage: "waveform.path.ecg")
                        .font(.footnote)
                        .fontWeight(.semibold)
                        .foregroundColor(.teal)
                    Text("\(latest)%")
                        .font(.title3)
                        .bold()
                }

                Spacer()

                HStack(spacing: 8) {
                    VStack(alignment: .trailing, spacing: 1) {
                        Text("Avg: \(String(format: "%.1f", avg))%")
                            .font(.caption2)
                        Text("Min: \(minVal)%")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }
            }

            Chart {
                ForEach(points) { p in
                    LineMark(
                        x: .value("Time", p.timeString),
                        y: .value("SpO2", p.val)
                    )
                    .foregroundStyle(Color.teal)
                    .interpolationMethod(.catmullRom)

                    AreaMark(
                        x: .value("Time", p.timeString),
                        yStart: .value("Baseline", 90),
                        yEnd: .value("SpO2", p.val)
                    )
                    .foregroundStyle(LinearGradient(colors: [.teal.opacity(0.3), .clear], startPoint: .top, endPoint: .bottom))

                    PointMark(
                        x: .value("Time", p.timeString),
                        y: .value("SpO2", p.val)
                    )
                    .foregroundStyle(Color.teal)
                }

                RuleMark(y: .value("Target", 95))
                    .foregroundStyle(Color.secondary.opacity(0.4))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
                    .annotation(position: .top, alignment: .trailing) {
                        Text("95% Normal Threshold")
                            .font(.system(size: 8))
                            .foregroundColor(.secondary)
                    }
            }
            .frame(height: 100)
            .chartYScale(domain: 90...100)
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(16)
        .shadow(color: Color.black.opacity(0.04), radius: 6, x: 0, y: 3)
    }

    // MARK: - 6. Stress Score & HRV Gauge Card
    private var stressScoreCard: some View {
        let telemetry = healthManager.lastTelemetry
        let score = telemetry?.stress.stressScore ?? 32
        let hrv = telemetry?.stress.hrvRmssd ?? 48.5

        let levelText: String
        let levelColor: Color
        switch score {
        case 0..<25:
            levelText = "Restful"
            levelColor = .blue
        case 25..<50:
            levelText = "Low Stress"
            levelColor = .green
        case 50..<75:
            levelText = "Moderate"
            levelColor = .orange
        default:
            levelText = "High Stress"
            levelColor = .red
        }

        return HStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                Label("Stress & Autonomic Balance", systemImage: "sparkles")
                    .font(.footnote)
                    .fontWeight(.semibold)
                    .foregroundColor(levelColor)

                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text("\(score)")
                        .font(.title)
                        .bold()
                    Text(levelText)
                        .font(.subheadline)
                        .foregroundColor(levelColor)
                        .fontWeight(.medium)
                }

                Text("HRV RMSSD: \(String(format: "%.1f", hrv)) ms")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }

            Spacer()

            ZStack {
                Circle()
                    .stroke(Color.secondary.opacity(0.15), lineWidth: 8)
                    .frame(width: 58, height: 58)
                Circle()
                    .trim(from: 0, to: Double(score) / 100.0)
                    .stroke(levelColor, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                    .frame(width: 58, height: 58)
                    .rotationEffect(.degrees(-90))
                Image(systemName: "figure.mind.and.body")
                    .font(.system(size: 20))
                    .foregroundColor(levelColor)
            }
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(16)
        .shadow(color: Color.black.opacity(0.04), radius: 6, x: 0, y: 3)
    }

    // MARK: - 7. Active vs Total Calories Burned Card
    private var caloriesBurnedCard: some View {
        let telemetry = healthManager.lastTelemetry
        let total = telemetry?.calories.total ?? 2150.0
        let active = telemetry?.calories.active ?? 450.0
        let targetActive = 500.0
        let activeFraction = min(1.0, active / targetActive)

        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Label("Calories Burned", systemImage: "flame.fill")
                        .font(.footnote)
                        .fontWeight(.semibold)
                        .foregroundColor(.red)

                    Text("\(Int(total)) kcal")
                        .font(.title2)
                        .bold()
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 2) {
                    Text("Active: \(Int(active)) kcal")
                        .font(.subheadline)
                        .bold()
                        .foregroundColor(.red)
                    Text("Goal: \(Int(targetActive)) kcal")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            }

            ProgressView(value: activeFraction)
                .tint(.red)

            HStack {
                Text(String(format: "%.0f%% of active daily goal", activeFraction * 100))
                    .font(.caption2)
                    .foregroundColor(.secondary)
                Spacer()
                Text("Channel 0x04")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(16)
        .shadow(color: Color.black.opacity(0.04), radius: 6, x: 0, y: 3)
    }

    // MARK: - Actions & Formatters
    private func pullTelemetry() {
        isPullingTelemetry = true
        telemetryMessage = "Requesting telemetry..."
        viewModel.pullWatchHealthTelemetry { result in
            isPullingTelemetry = false
            switch result {
            case .success(let payload):
                telemetryMessage = "Updated \(payload.date)"
            case .failure(let err):
                telemetryMessage = "Failed: \(err.localizedDescription)"
            }
        }
    }

    private func weatherConditionText(code: Int) -> String {
        switch code {
        case 0: return "Clear Sky"
        case 1, 2: return "Partly Cloudy"
        case 3: return "Overcast"
        case 45, 48: return "Fog"
        case 51...55: return "Drizzle"
        case 61...65: return "Rain"
        case 71...77: return "Snow"
        case 80...82: return "Rain Showers"
        case 95...99: return "Thunderstorm"
        default: return "Mostly Clear"
        }
    }

    private func weatherSymbol(code: Int, isDay: Int) -> String {
        switch code {
        case 0: return isDay == 1 ? "sun.max.fill" : "moon.stars.fill"
        case 1, 2: return isDay == 1 ? "cloud.sun.fill" : "cloud.moon.fill"
        case 3: return "cloud.fill"
        case 51...65, 80...82: return "cloud.rain.fill"
        case 71...77: return "cloud.snow.fill"
        case 95...99: return "cloud.bolt.rain.fill"
        default: return "cloud.sun.fill"
        }
    }

    private func formatHour(timestamp: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(timestamp))
        let formatter = DateFormatter()
        formatter.dateFormat = "ha"
        return formatter.string(from: date).lowercased()
    }
}

