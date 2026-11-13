import SwiftUI
import Charts

public struct DashboardView: View {
    @EnvironmentObject var viewModel: BLEViewModel
    @ObservedObject private var weatherService = WeatherSyncService.shared
    @ObservedObject private var healthManager = HealthTelemetryManager.shared

    // Quick Action States
    @State private var isPinging = false
    @State private var pingResult: String?
    @State private var isSyncingAll = false
    @State private var syncResult: String?
    @State private var isPullingTelemetry = false
    @State private var telemetryMessage: String?

    public init() {}

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    // 1. Hero Interactive Watch Status Card (Galaxy Watch Bezel + Battery Ring + Quick Actions)
                    heroWatchStatusCard

                    // 2. Active App Bridges Strip
                    activeAppBridgesStrip

                    // 3. Weather Bento Card (Sky Gradient + 12h Forecast Pills)
                    weatherBentoCard

                    // 4. Health Telemetry Header
                    healthTelemetryHeader

                    // 5. 2x2 Bento Grid + SwiftCharts Health Cards
                    healthBentoGrid
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
            }
            .navigationTitle("Dashboard")
            .background(WearBridgeTheme.obsidianBackground.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        pullTelemetry()
                    } label: {
                        if isPullingTelemetry {
                            ProgressView()
                                .tint(WearBridgeTheme.accentTeal)
                                .scaleEffect(0.8)
                        } else {
                            Image(systemName: "arrow.triangle.2.circlepath")
                                .foregroundColor(WearBridgeTheme.accentTeal)
                        }
                    }
                    .disabled(isPullingTelemetry)
                }
            }
        }
    }

    // MARK: - 1. Hero Interactive Watch Status Card
    private var heroWatchStatusCard: some View {
        let connectedWatches = viewModel.peripheralManager.discoveredWatches.filter { $0.isConnected }
        let isConnected = !connectedWatches.isEmpty
        let watchName = connectedWatches.first?.name ?? (isConnected ? "Galaxy Watch" : "Wear OS Watch")
        let battery = viewModel.watchBatteryLevel ?? 84
        let isCharging = viewModel.isWatchCharging
        let rssi = viewModel.rssiValue ?? connectedWatches.first?.rssi ?? -68

        return VStack(spacing: 16) {
            HStack(spacing: 18) {
                // Galaxy Watch Bezel Illustration + Battery Ring
                ZStack {
                    // Outer Bezel Ring with Ticks
                    Circle()
                        .strokeBorder(
                            AngularGradient(
                                colors: [
                                    Color.white.opacity(0.25),
                                    Color.white.opacity(0.08),
                                    Color.white.opacity(0.20),
                                    Color.white.opacity(0.05),
                                    Color.white.opacity(0.25)
                                ],
                                center: .center
                            ),
                            lineWidth: 7
                        )
                        .frame(width: 98, height: 98)

                    // Bezel Rotary Ticks
                    ForEach(0..<12) { i in
                        Rectangle()
                            .fill(i % 3 == 0 ? WearBridgeTheme.accentCyan.opacity(0.8) : Color.white.opacity(0.25))
                            .frame(width: i % 3 == 0 ? 2 : 1.2, height: i % 3 == 0 ? 6 : 4)
                            .offset(y: -44)
                            .rotationEffect(.degrees(Double(i) * 30))
                    }

                    // Inner Display Shadow Circle
                    Circle()
                        .fill(Color(red: 0.04, green: 0.05, blue: 0.07))
                        .frame(width: 82, height: 82)

                    // Battery Progress Ring
                    Circle()
                        .stroke(Color.white.opacity(0.08), lineWidth: 4)
                        .frame(width: 74, height: 74)

                    Circle()
                        .trim(from: 0.0, to: Double(battery) / 100.0)
                        .stroke(
                            AngularGradient(
                                colors: isCharging ?
                                    [WearBridgeTheme.accentGreen, WearBridgeTheme.accentTeal] :
                                    (battery > 20 ? [WearBridgeTheme.accentCyan, WearBridgeTheme.accentTeal] : [Color.orange, Color.red]),
                                center: .center
                            ),
                            style: StrokeStyle(lineWidth: 4, lineCap: .round)
                        )
                        .frame(width: 74, height: 74)
                        .rotationEffect(.degrees(-90))

                    // Center Content (Battery % & Charging / Online Glyph)
                    VStack(spacing: 2) {
                        Image(systemName: isCharging ? "bolt.fill" : (isConnected ? "applewatch.radiowaves.left.and.right" : "applewatch.slash"))
                            .font(.system(size: 15, weight: .bold))
                            .foregroundColor(isCharging ? WearBridgeTheme.accentGreen : (isConnected ? WearBridgeTheme.accentTeal : Color.orange))

                        HStack(spacing: 1) {
                            Text("\(battery)")
                                .font(.system(size: 15, weight: .heavy, design: .rounded))
                                .foregroundColor(WearBridgeTheme.textPrimary)
                            Text("%")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundColor(WearBridgeTheme.textSecondary)
                        }
                    }
                }
                .frame(width: 100, height: 100)

                // Watch Details & Link State
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 6) {
                        Text(watchName)
                            .font(.system(size: 17, weight: .bold, design: .rounded))
                            .foregroundColor(WearBridgeTheme.textPrimary)
                            .lineLimit(1)

                        Circle()
                            .fill(isConnected ? WearBridgeTheme.accentGreen : Color.orange)
                            .frame(width: 8, height: 8)
                            .shadow(color: (isConnected ? WearBridgeTheme.accentGreen : Color.orange).opacity(0.6), radius: 4)
                    }

                    // RSSI Pill + Protocol Badge
                    HStack(spacing: 8) {
                        // RSSI Pill
                        HStack(spacing: 4) {
                            Image(systemName: "antenna.radiowaves.left.and.right")
                                .font(.system(size: 9))
                            Text("\(rssi) dBm")
                                .font(.system(size: 10, weight: .bold, design: .monospaced))
                        }
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(rssiColor(rssi).opacity(0.18))
                        .foregroundColor(rssiColor(rssi))
                        .cornerRadius(6)

                        // Multiplexer Pill
                        Text("BLE 0x01-0x07")
                            .font(.system(size: 9, weight: .bold, design: .monospaced))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(WearBridgeTheme.accentCyan.opacity(0.15))
                            .foregroundColor(WearBridgeTheme.accentCyan)
                            .cornerRadius(6)
                    }

                    // Status Text
                    Text(isConnected ? "GATT Linked • All Channels Operational" : "Disconnected • Scanning for Watch...")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundColor(WearBridgeTheme.textSecondary)
                        .lineLimit(1)
                }

                Spacer()
            }

            Divider()
                .background(WearBridgeTheme.strokeSubtle)

            // Quick-Action Pills: "Ping Watch", "Sync All", "Pull Health"
            HStack(spacing: 10) {
                // Action 1: Ping Watch
                quickActionPill(
                    title: isPinging ? "Pinging..." : (pingResult ?? "Ping Watch"),
                    icon: "stopwatch.fill",
                    color: WearBridgeTheme.accentTeal,
                    isLoading: isPinging
                ) {
                    triggerPing()
                }

                // Action 2: Sync All
                quickActionPill(
                    title: isSyncingAll ? "Syncing..." : (syncResult ?? "Sync All"),
                    icon: "arrow.triangle.2.circlepath",
                    color: WearBridgeTheme.accentCyan,
                    isLoading: isSyncingAll
                ) {
                    triggerSyncAll()
                }

                // Action 3: Pull Health
                quickActionPill(
                    title: isPullingTelemetry ? "Pulling..." : "Pull Health",
                    icon: "heart.fill",
                    color: WearBridgeTheme.accentPink,
                    isLoading: isPullingTelemetry
                ) {
                    pullTelemetry()
                }
            }
        }
        .glassCard(cornerRadius: 22, padding: 18)
    }

    private func quickActionPill(
        title: String,
        icon: String,
        color: Color,
        isLoading: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if isLoading {
                    ProgressView()
                        .tint(color)
                        .scaleEffect(0.7)
                } else {
                    Image(systemName: icon)
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(color)
                }
                Text(title)
                    .font(.system(size: 11, weight: .semibold, design: .rounded))
                    .foregroundColor(WearBridgeTheme.textPrimary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .padding(.horizontal, 6)
            .background(color.opacity(0.12))
            .cornerRadius(10)
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke(color.opacity(0.35), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .disabled(isLoading)
    }

    // MARK: - 2. Active App Bridges Strip
    private var activeAppBridgesStrip: some View {
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

        return VStack(alignment: .leading, spacing: 9) {
            HStack {
                Label("ACTIVE APP BRIDGES", systemImage: "app.badge.checkmark.fill")
                    .font(.system(size: 10, weight: .heavy, design: .monospaced))
                    .foregroundColor(WearBridgeTheme.textTertiary)
                Spacer()
                Text("ANCS + CH 0x06")
                    .font(.system(size: 9, weight: .bold, design: .monospaced))
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(WearBridgeTheme.accentGreen.opacity(0.15))
                    .foregroundColor(WearBridgeTheme.accentGreen)
                    .cornerRadius(4)
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(highlightBundles, id: \.self) { bundleId in
                        let brand = AppBrandRegistry.resolve(bundleId)
                        HStack(spacing: 6) {
                            Image(systemName: brand.sfSymbol)
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(brand.primaryColor)
                            Text(brand.displayName)
                                .font(.system(size: 11, weight: .semibold, design: .rounded))
                                .foregroundColor(WearBridgeTheme.textPrimary)
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(WearBridgeTheme.cardSurface)
                        .cornerRadius(12)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(brand.primaryColor.opacity(0.30), lineWidth: 1)
                        )
                    }
                }
            }
        }
        .glassCard(cornerRadius: 18, padding: 14)
    }

    // MARK: - 3. Weather Bento Card
    private var weatherBentoCard: some View {
        let weather = weatherService.lastWeatherData
        let currentTemp = weather?.temp ?? 21.0
        let high = weather?.high ?? 24.0
        let low = weather?.low ?? 15.0
        let cityName = weather?.cityName ?? "Cupertino"
        let code = weather?.weatherCode ?? 1
        let hourly = weather?.hourly ?? []

        return VStack(alignment: .leading, spacing: 14) {
            // Header Row: City, Condition, Temp
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 5) {
                        Image(systemName: "cloud.sun.fill")
                            .foregroundColor(WearBridgeTheme.accentCyan)
                        Text("WEATHER BRIDGE (0x03)")
                            .font(.system(size: 10, weight: .heavy, design: .monospaced))
                            .foregroundColor(WearBridgeTheme.accentCyan)
                    }

                    Text(cityName)
                        .font(.system(size: 20, weight: .bold, design: .rounded))
                        .foregroundColor(WearBridgeTheme.textPrimary)

                    Text(weatherConditionText(code: code))
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(WearBridgeTheme.textSecondary)
                }

                Spacer()

                HStack(alignment: .top, spacing: 2) {
                    Text(String(format: "%.0f", currentTemp))
                        .font(.system(size: 44, weight: .light, design: .rounded))
                        .foregroundColor(WearBridgeTheme.textPrimary)
                    Text("°")
                        .font(.system(size: 26, weight: .light))
                        .foregroundColor(WearBridgeTheme.accentCyan)
                }
            }

            // 12-Hour Forecast Pills Strip
            if !hourly.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(Array(hourly.prefix(12).enumerated()), id: \.offset) { _, hour in
                            VStack(spacing: 5) {
                                Text(formatHour(timestamp: hour.time))
                                    .font(.system(size: 10, weight: .semibold, design: .monospaced))
                                    .foregroundColor(WearBridgeTheme.textSecondary)

                                Image(systemName: weatherSymbol(code: hour.code, isDay: 1))
                                    .font(.system(size: 15))
                                    .foregroundColor(WearBridgeTheme.accentOrange)

                                Text(String(format: "%.0f°", hour.temp))
                                    .font(.system(size: 11, weight: .bold, design: .rounded))
                                    .foregroundColor(WearBridgeTheme.textPrimary)
                            }
                            .padding(.vertical, 8)
                            .padding(.horizontal, 10)
                            .background(WearBridgeTheme.cardSurface.opacity(0.85))
                            .cornerRadius(12)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(WearBridgeTheme.strokeSubtle, lineWidth: 1)
                            )
                        }
                    }
                }
            }

            // Footer
            HStack {
                Text(String(format: "High: %.0f°  •  Low: %.0f°", high, low))
                    .font(.system(size: 11, weight: .medium))
                    .foregroundColor(WearBridgeTheme.textSecondary)

                Spacer()

                Text("Channel 0x03 Synced")
                    .font(.system(size: 10, weight: .semibold, design: .monospaced))
                    .foregroundColor(WearBridgeTheme.accentCyan)
            }
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(WearBridgeTheme.weatherGradientDay)
                .background(
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .fill(WearBridgeTheme.cardBackground.opacity(0.85))
                )
        )
        .overlay(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .stroke(WearBridgeTheme.accentCyan.opacity(0.25), lineWidth: 1)
        )
        .shadow(color: Color.black.opacity(0.35), radius: 14, x: 0, y: 7)
    }

    // MARK: - 4. Health Telemetry Header
    private var healthTelemetryHeader: some View {
        HStack {
            Label("HEALTH TELEMETRY (0x04)", systemImage: "heart.text.square.fill")
                .font(.system(size: 10, weight: .heavy, design: .monospaced))
                .foregroundColor(WearBridgeTheme.textTertiary)

            Spacer()

            if let msg = telemetryMessage {
                Text(msg)
                    .font(.system(size: 10, weight: .semibold, design: .rounded))
                    .foregroundColor(WearBridgeTheme.accentTeal)
            }
        }
        .padding(.horizontal, 4)
        .padding(.top, 4)
    }

    // MARK: - 5. 2x2 Bento Grid + SwiftCharts Health Cards
    private var healthBentoGrid: some View {
        let telemetry = healthManager.lastTelemetry

        return LazyVGrid(columns: [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)], spacing: 14) {
            // Card 1: Sleep Stages SwiftCharts Breakdown
            sleepBentoCard(telemetry)

            // Card 2: Stress & HRV Autonomic Gauge
            stressBentoCard(telemetry)

            // Card 3: Blood Oxygen (SpO2 Trend SwiftChart)
            spo2BentoCard(telemetry)

            // Card 4: Daily Activity Calories Burned
            caloriesBentoCard(telemetry)
        }
    }

    // Card 1: Sleep Stages
    private func sleepBentoCard(_ telemetry: HealthTelemetryPayload?) -> some View {
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
            SleepStageItem(stage: "Deep", minutes: deep, color: WearBridgeTheme.accentPurple),
            SleepStageItem(stage: "REM", minutes: rem, color: WearBridgeTheme.accentCyan),
            SleepStageItem(stage: "Light", minutes: light, color: WearBridgeTheme.accentTeal),
            SleepStageItem(stage: "Awake", minutes: awake, color: WearBridgeTheme.accentOrange)
        ]

        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("Sleep", systemImage: "bed.double.fill")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(WearBridgeTheme.accentPurple)

                Spacer()

                // Sleep Score Mini Ring
                ZStack {
                    Circle()
                        .stroke(WearBridgeTheme.accentPurple.opacity(0.2), lineWidth: 3)
                        .frame(width: 28, height: 28)
                    Circle()
                        .trim(from: 0, to: Double(score) / 100.0)
                        .stroke(WearBridgeTheme.accentPurple, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .frame(width: 28, height: 28)
                        .rotationEffect(.degrees(-90))
                    Text("\(score)")
                        .font(.system(size: 9, weight: .heavy, design: .rounded))
                        .foregroundColor(WearBridgeTheme.accentPurple)
                }
            }

            VStack(alignment: .leading, spacing: 1) {
                Text("\(totalMin / 60)h \(totalMin % 60)m")
                    .font(.system(size: 18, weight: .bold, design: .rounded))
                    .foregroundColor(WearBridgeTheme.textPrimary)
                Text("Architecture")
                    .font(.system(size: 10))
                    .foregroundColor(WearBridgeTheme.textSecondary)
            }

            // SwiftChart: Stacked Bar
            Chart(stages) { item in
                BarMark(
                    x: .value("Minutes", item.minutes)
                )
                .foregroundStyle(item.color)
            }
            .frame(height: 14)
            .chartXAxis(.hidden)
            .chartYAxis(.hidden)
            .cornerRadius(4)

            // Mini Legend
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Circle().fill(WearBridgeTheme.accentPurple).frame(width: 5, height: 5)
                    Text("Deep \(deep)m").font(.system(size: 9)).foregroundColor(WearBridgeTheme.textSecondary)
                    Circle().fill(WearBridgeTheme.accentCyan).frame(width: 5, height: 5)
                    Text("REM \(rem)m").font(.system(size: 9)).foregroundColor(WearBridgeTheme.textSecondary)
                }
                HStack(spacing: 6) {
                    Circle().fill(WearBridgeTheme.accentTeal).frame(width: 5, height: 5)
                    Text("Light \(light)m").font(.system(size: 9)).foregroundColor(WearBridgeTheme.textSecondary)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard(cornerRadius: 18, padding: 14)
    }

    // Card 2: Stress & HRV
    private func stressBentoCard(_ telemetry: HealthTelemetryPayload?) -> some View {
        let score = telemetry?.stress.stressScore ?? 32
        let hrv = telemetry?.stress.hrvRmssd ?? 48.5

        let levelText: String
        let levelColor: Color
        switch score {
        case 0..<25:
            levelText = "Restful"
            levelColor = WearBridgeTheme.accentTeal
        case 25..<50:
            levelText = "Low Stress"
            levelColor = WearBridgeTheme.accentGreen
        case 50..<75:
            levelText = "Moderate"
            levelColor = WearBridgeTheme.accentOrange
        default:
            levelText = "High Stress"
            levelColor = WearBridgeTheme.accentPink
        }

        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("Stress", systemImage: "sparkles")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(levelColor)

                Spacer()

                // Stress Circular Gauge
                ZStack {
                    Circle()
                        .stroke(Color.white.opacity(0.08), lineWidth: 3)
                        .frame(width: 28, height: 28)
                    Circle()
                        .trim(from: 0, to: Double(score) / 100.0)
                        .stroke(levelColor, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .frame(width: 28, height: 28)
                        .rotationEffect(.degrees(-90))
                    Text("\(score)")
                        .font(.system(size: 9, weight: .heavy, design: .rounded))
                        .foregroundColor(levelColor)
                }
            }

            VStack(alignment: .leading, spacing: 1) {
                Text(levelText)
                    .font(.system(size: 16, weight: .bold, design: .rounded))
                    .foregroundColor(levelColor)
                Text("Autonomic Index")
                    .font(.system(size: 10))
                    .foregroundColor(WearBridgeTheme.textSecondary)
            }

            Spacer(minLength: 0)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Image(systemName: "waveform.path.ecg")
                        .font(.system(size: 10))
                        .foregroundColor(WearBridgeTheme.accentTeal)
                    Text("HRV RMSSD")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundColor(WearBridgeTheme.textSecondary)
                }
                Text("\(String(format: "%.1f", hrv)) ms")
                    .font(.system(size: 14, weight: .heavy, design: .monospaced))
                    .foregroundColor(WearBridgeTheme.textPrimary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard(cornerRadius: 18, padding: 14)
    }

    // Card 3: Blood Oxygen (SpO2 Trend)
    private func spo2BentoCard(_ telemetry: HealthTelemetryPayload?) -> some View {
        let latest = telemetry?.spo2.latest ?? 98
        let avg = telemetry?.spo2.avg ?? 97.4

        struct SpO2ChartPoint: Identifiable {
            let id = UUID()
            let timeString: String
            let val: Int
        }

        let points: [SpO2ChartPoint] = (telemetry?.spo2.samples.isEmpty == false) ?
            telemetry!.spo2.samples.enumerated().map { i, s in
                SpO2ChartPoint(timeString: String(format: "+%dh", i * 2), val: s.percentage)
            } : [
                SpO2ChartPoint(timeString: "00", val: 96),
                SpO2ChartPoint(timeString: "04", val: 95),
                SpO2ChartPoint(timeString: "08", val: 98),
                SpO2ChartPoint(timeString: "12", val: 99),
                SpO2ChartPoint(timeString: "16", val: 97),
                SpO2ChartPoint(timeString: "20", val: latest)
            ]

        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("SpO2", systemImage: "lungs.fill")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(WearBridgeTheme.accentTeal)

                Spacer()

                Text("Avg \(String(format: "%.0f", avg))%")
                    .font(.system(size: 10, weight: .semibold, design: .monospaced))
                    .foregroundColor(WearBridgeTheme.textSecondary)
            }

            VStack(alignment: .leading, spacing: 1) {
                HStack(alignment: .firstTextBaseline, spacing: 2) {
                    Text("\(latest)")
                        .font(.system(size: 20, weight: .heavy, design: .rounded))
                        .foregroundColor(WearBridgeTheme.textPrimary)
                    Text("%")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(WearBridgeTheme.accentTeal)
                }
                Text("Blood Oxygen")
                    .font(.system(size: 10))
                    .foregroundColor(WearBridgeTheme.textSecondary)
            }

            // SwiftChart: SpO2 Line Chart
            Chart {
                ForEach(points) { p in
                    LineMark(
                        x: .value("Time", p.timeString),
                        y: .value("SpO2", p.val)
                    )
                    .foregroundStyle(WearBridgeTheme.accentTeal)
                    .interpolationMethod(.catmullRom)

                    AreaMark(
                        x: .value("Time", p.timeString),
                        yStart: .value("Base", 90),
                        yEnd: .value("SpO2", p.val)
                    )
                    .foregroundStyle(
                        LinearGradient(
                            colors: [WearBridgeTheme.accentTeal.opacity(0.35), WearBridgeTheme.accentTeal.opacity(0.0)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                }

                RuleMark(y: .value("Target", 95))
                    .foregroundStyle(Color.white.opacity(0.2))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
            }
            .frame(height: 48)
            .chartYScale(domain: 90...100)
            .chartXAxis(.hidden)
            .chartYAxis(.hidden)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard(cornerRadius: 18, padding: 14)
    }

    // Card 4: Daily Activity Calories
    private func caloriesBentoCard(_ telemetry: HealthTelemetryPayload?) -> some View {
        let total = telemetry?.calories.total ?? 2150.0
        let active = telemetry?.calories.active ?? 450.0
        let targetActive = 500.0
        let activeFraction = min(1.0, active / targetActive)

        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("Calories", systemImage: "flame.fill")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(WearBridgeTheme.accentOrange)

                Spacer()

                Text("\(Int(activeFraction * 100))%")
                    .font(.system(size: 10, weight: .heavy, design: .rounded))
                    .foregroundColor(WearBridgeTheme.accentOrange)
            }

            VStack(alignment: .leading, spacing: 1) {
                Text("\(Int(total))")
                    .font(.system(size: 20, weight: .heavy, design: .rounded))
                    .foregroundColor(WearBridgeTheme.textPrimary)
                Text("Total kcal burned")
                    .font(.system(size: 10))
                    .foregroundColor(WearBridgeTheme.textSecondary)
            }

            Spacer(minLength: 0)

            // Active Progress Bar
            VStack(alignment: .leading, spacing: 4) {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule()
                            .fill(Color.white.opacity(0.08))
                            .frame(height: 7)

                        Capsule()
                            .fill(
                                LinearGradient(
                                    colors: [WearBridgeTheme.accentOrange, WearBridgeTheme.accentPink],
                                    startPoint: .leading,
                                    endPoint: .trailing
                                )
                            )
                            .frame(width: geo.size.width * CGFloat(activeFraction), height: 7)
                    }
                }
                .frame(height: 7)

                HStack {
                    Text("Active: \(Int(active)) kcal")
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundColor(WearBridgeTheme.accentOrange)
                    Spacer()
                    Text("Goal: \(Int(targetActive))")
                        .font(.system(size: 9))
                        .foregroundColor(WearBridgeTheme.textTertiary)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard(cornerRadius: 18, padding: 14)
    }

    // MARK: - Actions
    private func triggerPing() {
        isPinging = true
        pingResult = nil
        viewModel.sendTestPing { result in
            isPinging = false
            switch result {
            case .success(let ms):
                pingResult = "\(ms)ms RTT"
            case .failure:
                pingResult = "Err"
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
                pingResult = nil
            }
        }
    }

    private func triggerSyncAll() {
        isSyncingAll = true
        syncResult = nil
        viewModel.forceSyncContactsAndWeather { result in
            isSyncingAll = false
            switch result {
            case .success:
                syncResult = "Synced"
            case .failure:
                syncResult = "Failed"
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
                syncResult = nil
            }
        }
    }

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

    // MARK: - Helpers & Formatters
    private func rssiColor(_ rssi: Int) -> Color {
        if rssi >= -65 { return WearBridgeTheme.accentGreen }
        if rssi >= -80 { return WearBridgeTheme.accentOrange }
        return WearBridgeTheme.accentPink
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
