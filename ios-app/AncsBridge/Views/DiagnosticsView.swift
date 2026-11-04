import SwiftUI
import UIKit

struct DiagnosticsView: View {
    @EnvironmentObject var viewModel: BLEViewModel

    // State for test actions
    @State private var pingLoading = false
    @State private var pingResult: String? = nil

    @State private var notificationSent = false

    @State private var callSent = false

    @State private var fileTransferLoading = false
    @State private var fileTransferProgress: Double = 0.0
    @State private var fileTransferResult: String? = nil

    @State private var loopbackLoading = false
    @State private var loopbackResult: String? = nil

    @State private var syncLoading = false
    @State private var syncResult: String? = nil

    @State private var telemetryLoading = false
    @State private var telemetryResult: String? = nil
    @State private var telemetryDetail: HealthTelemetryPayload? = nil

    @State private var mediaQoLLoading = false
    @State private var mediaQoLResult: String? = nil

    @State private var selectedMockBundleId: String = AppBrandRegistry.bundleWhatsApp
    @State private var mockMessageLoading = false
    @State private var mockMessageResult: String? = nil

    @State private var apkTransferLoading = false
    @State private var apkTransferResult: String? = nil
    @ObservedObject private var fileTransferManager = FileTransferManager.shared

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // Hardware Link & Telemetry Banner
                    hardwareStatusSection

                    // Test Harness Action Buttons
                    testHarnessSection

                    // App Brand Registry Visual Catalog
                    appBrandRegistrySection

                    // Live Packet Multiplexer Log
                    livePacketLogSection
                }
                .padding(.horizontal)
                .padding(.vertical, 12)
            }
            .navigationTitle("Diagnostics")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        viewModel.refreshRssi()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
        }
    }

    // MARK: - Hardware Status & Metrics
    private var hardwareStatusSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                let connectedWatches = viewModel.peripheralManager.discoveredWatches.filter { $0.isConnected }
                let watchName = connectedWatches.first?.name ?? "No Watch Connected"
                let isConnected = !connectedWatches.isEmpty

                HStack(spacing: 8) {
                    Circle()
                        .fill(isConnected ? Color.green : Color.orange)
                        .frame(width: 10, height: 10)

                    Text(watchName)
                        .font(.headline)
                        .fontWeight(.bold)
                }

                Spacer()

                Text(isConnected ? "GATT Linked" : "Offline")
                    .font(.caption)
                    .fontWeight(.semibold)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(isConnected ? Color.green.opacity(0.15) : Color.orange.opacity(0.15))
                    .foregroundColor(isConnected ? .green : .orange)
                    .clipShape(Capsule())
            }

            // 3 Hardware Metrics
            HStack(spacing: 8) {
                // MTU Size
                metricTile(
                    title: "Negotiated MTU",
                    value: "\(viewModel.negotiatedMTU) B",
                    icon: "arrow.left.and.right.square.fill",
                    color: .blue
                )

                // RSSI
                metricTile(
                    title: "Signal RSSI",
                    value: viewModel.rssiValue != nil ? "\(viewModel.rssiValue!) dBm" : "-- dBm",
                    icon: "antenna.radiowaves.left.and.right",
                    color: rssiColor(viewModel.rssiValue)
                )

                // BLE Advertising
                metricTile(
                    title: "Peripheral",
                    value: viewModel.serverManager.isAdvertising ? "Broadcasting" : "Standby",
                    icon: "dot.radiowaves.left.and.right",
                    color: viewModel.serverManager.isAdvertising ? .green : .secondary
                )
            }
        }
        .padding(14)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .cornerRadius(14)
        .shadow(color: .black.opacity(0.04), radius: 6, y: 2)
    }

    private func metricTile(title: String, value: String, icon: String, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.caption2)
                    .foregroundColor(color)
                Text(title)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundColor(.secondary)
            }

            Text(value)
                .font(.system(size: 15, weight: .bold, design: .monospaced))
                .foregroundColor(.primary)
                .minimumScaleFactor(0.8)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(color.opacity(0.08))
        .cornerRadius(10)
    }

    private func rssiColor(_ rssi: Int?) -> Color {
        guard let r = rssi else { return .secondary }
        if r >= -65 { return .green }
        if r >= -80 { return .orange }
        return .red
    }

    // MARK: - Test Harness Actions
    private var testHarnessSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "wrench.and.screwdriver.fill")
                    .foregroundColor(.blue)
                Text("Functional Test Harness")
                    .font(.subheadline)
                    .fontWeight(.bold)
                    .foregroundColor(.secondary)
                Spacer()
            }

            VStack(spacing: 10) {
                // 1. Test Ping (0x01)
                testRow(
                    title: "Test Ping (0x01)",
                    subtitle: "Sends timestamp, measures round-trip ACK latency",
                    icon: "stopwatch.fill",
                    color: .teal,
                    isLoading: pingLoading,
                    resultText: pingResult,
                    resultColor: .teal
                ) {
                    pingLoading = true
                    pingResult = nil
                    viewModel.sendTestPing { result in
                        pingLoading = false
                        switch result {
                        case .success(let latency):
                            pingResult = "RTT: \(latency) ms"
                        case .failure(let error):
                            pingResult = "Err: \(error.localizedDescription)"
                        }
                    }
                }

                Divider()

                // 2. Test Notification
                testRow(
                    title: "Test Notification",
                    subtitle: "Triggers local iOS UNNotification + GATT Alert packet",
                    icon: "bell.badge.fill",
                    color: .blue,
                    isLoading: false,
                    resultText: notificationSent ? "ANCS + Alert Sent" : nil,
                    resultColor: .blue
                ) {
                    notificationSent = true
                    viewModel.sendTestNotification(
                        title: "Test Notification",
                        message: "Custom GATT Alert from iPhone Diagnostics"
                    )
                    DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                        notificationSent = false
                    }
                }

                Divider()

                // 3. Test Incoming Call
                testRow(
                    title: "Test Incoming Call",
                    subtitle: "Mock call state packet (Jane Doe, RINGING)",
                    icon: "phone.fill.arrow.down.left",
                    color: .green,
                    isLoading: false,
                    resultText: callSent ? "Call Triggered" : nil,
                    resultColor: .green
                ) {
                    callSent = true
                    viewModel.sendMockIncomingCall(
                        callerName: "Jane Doe",
                        phoneNumber: "+1 (555) 019-2834"
                    )
                    DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                        callSent = false
                    }
                }

                Divider()

                // 4a. High-Throughput Watch Face APK Transfer (0x07)
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        ZStack {
                            Circle()
                                .fill(Color.purple.opacity(0.15))
                                .frame(width: 36, height: 36)
                            Image(systemName: "shippingbox.fill")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundColor(.purple)
                        }

                        VStack(alignment: .leading, spacing: 2) {
                            Text("Test Mock Watch Face APK (0x07)")
                                .font(.subheadline)
                                .fontWeight(.semibold)
                            Text("Windowed streaming (32KB chunks) + SHA-256 verification")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }

                        Spacer()

                        Button {
                            runApkTransferTest()
                        } label: {
                            if apkTransferLoading || fileTransferManager.isTransferring {
                                ProgressView()
                                    .scaleEffect(0.8)
                            } else {
                                Text("Push APK")
                                    .font(.subheadline)
                                    .fontWeight(.bold)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(Color.purple)
                                    .foregroundColor(.white)
                                    .clipShape(Capsule())
                            }
                        }
                        .disabled(apkTransferLoading || fileTransferManager.isTransferring)
                    }

                    if fileTransferManager.isTransferring {
                        VStack(alignment: .leading, spacing: 4) {
                            ProgressView(value: fileTransferManager.transferProgress, total: 1.0)
                                .tint(.purple)
                            HStack {
                                Text(fileTransferManager.transferStatusText)
                                Spacer()
                                Text(String(format: "%.1f KB/s", fileTransferManager.transferSpeedKBps))
                                    .bold()
                            }
                            .font(.caption2)
                            .foregroundColor(.secondary)
                        }
                    } else if let result = apkTransferResult {
                        HStack {
                            Image(systemName: result.contains("Verified") ? "checkmark.seal.fill" : "exclamationmark.triangle.fill")
                                .foregroundColor(result.contains("Verified") ? .green : .red)
                            Text(result)
                                .font(.caption)
                                .fontWeight(.semibold)
                                .foregroundColor(result.contains("Verified") ? .green : .red)
                        }
                    }
                }

                Divider()

                // 4b. Legacy 100KB Random Binary Transfer (0x07)
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        ZStack {
                            Circle()
                                .fill(Color.purple.opacity(0.15))
                                .frame(width: 36, height: 36)
                            Image(systemName: "doc.zipper")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundColor(.purple)
                        }

                        VStack(alignment: .leading, spacing: 2) {
                            Text("Test 100KB Binary Loop (0x07)")
                                .font(.subheadline)
                                .fontWeight(.semibold)
                            Text("Legacy CRC32 throughput benchmark")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }

                        Spacer()

                        Button {
                            runFileTransferTest()
                        } label: {
                            if fileTransferLoading {
                                ProgressView()
                                    .scaleEffect(0.8)
                            } else {
                                Text("Stream")
                                    .font(.subheadline)
                                    .fontWeight(.bold)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(Color.purple.opacity(0.8))
                                    .foregroundColor(.white)
                                    .clipShape(Capsule())
                            }
                        }
                        .disabled(fileTransferLoading)
                    }

                    if fileTransferLoading {
                        ProgressView(value: fileTransferProgress, total: 1.0)
                            .tint(.purple)
                        Text("Streaming: \(Int(fileTransferProgress * 100))%")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    } else if let result = fileTransferResult {
                        Text(result)
                            .font(.caption)
                            .fontWeight(.semibold)
                            .foregroundColor(.purple)
                    }
                }

                Divider()

                // 5a. Send Mock Incoming Message to Watch (0x06)
                VStack(alignment: .leading, spacing: 8) {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach([
                                (AppBrandRegistry.bundleWhatsApp, "WhatsApp"),
                                (AppBrandRegistry.bundleTikTokGlobal, "TikTok"),
                                (AppBrandRegistry.bundleIMessage, "iMessage"),
                                (AppBrandRegistry.bundleTelegram, "Telegram"),
                                (AppBrandRegistry.bundleInstagram, "Instagram"),
                                (AppBrandRegistry.bundleDiscord, "Discord")
                            ], id: \.0) { bundleId, name in
                                let brand = AppBrandRegistry.resolve(bundleId)
                                Button {
                                    selectedMockBundleId = bundleId
                                } label: {
                                    HStack(spacing: 4) {
                                        Image(systemName: brand.sfSymbol)
                                            .font(.system(size: 10))
                                        Text(name)
                                            .font(.caption2)
                                    }
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .background(selectedMockBundleId == bundleId ? brand.primaryColor : Color(.systemGray5))
                                    .foregroundColor(selectedMockBundleId == bundleId ? .white : .primary)
                                    .cornerRadius(8)
                                }
                            }
                        }
                    }

                    testRow(
                        title: "Send Mock Message (\(AppBrandRegistry.resolve(selectedMockBundleId).displayName))",
                        subtitle: "Injects synthetic message from \(selectedMockBundleId) into Watch Room inbox",
                        icon: AppBrandRegistry.resolve(selectedMockBundleId).sfSymbol,
                        color: AppBrandRegistry.resolve(selectedMockBundleId).primaryColor,
                        isLoading: mockMessageLoading,
                        resultText: mockMessageResult,
                        resultColor: .green
                    ) {
                        mockMessageLoading = true
                        mockMessageResult = nil
                        let brand = AppBrandRegistry.resolve(selectedMockBundleId)
                        viewModel.sendMockMessageToWatch(
                            bundleId: selectedMockBundleId,
                            senderName: "Sarah Connor",
                            body: "Test notification via \(brand.displayName) over Channel 0x06"
                        ) { result in
                            mockMessageLoading = false
                            switch result {
                            case .success(let info):
                                mockMessageResult = info
                            case .failure(let err):
                                mockMessageResult = "Err: \(err.localizedDescription)"
                            }
                        }
                    }
                }

                Divider()

                // 5b. Test Reply Loopback & Relay (0x06)
                testRow(
                    title: "Test Reply Loopback (0x06)",
                    subtitle: "Verifies MessageReplyPayload & ACK round-trip status (\(viewModel.replyRelayManager.relayMode.rawValue))",
                    icon: "arrow.counterclockwise.bubble.fill",
                    color: .orange,
                    isLoading: loopbackLoading,
                    resultText: loopbackResult ?? (viewModel.replyRelayManager.lastRelayStatus != "Idle" ? viewModel.replyRelayManager.lastRelayStatus : nil),
                    resultColor: .orange
                ) {
                    loopbackLoading = true
                    loopbackResult = nil
                    viewModel.sendTestReplyLoopback(message: "Diagnostics Echo Test") { result in
                        loopbackLoading = false
                        switch result {
                        case .success(let echo):
                            loopbackResult = "Echo: '\(echo)' (Relay: \(viewModel.replyRelayManager.lastRelayStatus))"
                        case .failure(let err):
                            loopbackResult = "Err: \(err.localizedDescription)"
                        }
                    }
                }

                Divider()

                // 6. Force Sync Contacts & Weather (0x02 & 0x03)
                testRow(
                    title: "Force Sync Contacts & Weather",
                    subtitle: "Sends full address book (0x02) and Open-Meteo forecast (0x03)",
                    icon: "arrow.triangle.2.circlepath.circle.fill",
                    color: .indigo,
                    isLoading: syncLoading,
                    resultText: syncResult,
                    resultColor: .indigo
                ) {
                    syncLoading = true
                    syncResult = nil
                    viewModel.forceSyncContactsAndWeather { result in
                        syncLoading = false
                        switch result {
                        case .success(let info):
                            syncResult = info
                        case .failure(let err):
                            syncResult = "Err: \(err.localizedDescription)"
                        }
                    }
                }

                Divider()

                // 7. Pull Watch Telemetry (0x04)
                VStack(alignment: .leading, spacing: 8) {
                    testRow(
                        title: "Pull Watch Telemetry (0x04)",
                        subtitle: "Requests 24h health metrics (Sleep, SpO2, Stress, Calories)",
                        icon: "heart.text.square.fill",
                        color: .red,
                        isLoading: telemetryLoading,
                        resultText: telemetryResult,
                        resultColor: .red
                    ) {
                        telemetryLoading = true
                        telemetryResult = nil
                        telemetryDetail = nil
                        viewModel.pullWatchHealthTelemetry { result in
                            telemetryLoading = false
                            switch result {
                            case .success(let payload):
                                telemetryDetail = payload
                                telemetryResult = "\(payload.date): \(Int(payload.calories.total)) kcal, SpO2 \(payload.spo2.latest)%, Sleep \(payload.sleep.totalDurationMinutes)m, Stress \(payload.stress.stressScore)"
                            case .failure(let err):
                                telemetryResult = "Err: \(err.localizedDescription)"
                            }
                        }
                    }

                    if let d = telemetryDetail {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text("🔥 \(Int(d.calories.total)) kcal (Active: \(Int(d.calories.active)))")
                                Spacer()
                                Text("🫁 SpO2: \(d.spo2.latest)% (Avg: \(String(format: "%.1f", d.spo2.avg))%)")
                            }
                            HStack {
                                Text("💤 Sleep: \(d.sleep.totalDurationMinutes)m (Score: \(d.sleep.sleepScore))")
                                Spacer()
                                Text("🧘 Stress: \(d.stress.stressScore) (HRV: \(String(format: "%.1f", d.stress.hrvRmssd))ms)")
                            }
                            Text("Deep: \(d.sleep.deepMinutes)m | REM: \(d.sleep.remMinutes)m | Light: \(d.sleep.lightMinutes)m | Awake: \(d.sleep.awakeMinutes)m")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                        .font(.caption)
                        .padding(8)
                        .background(Color.red.opacity(0.08))
                        .cornerRadius(8)
                    }
                }

                Divider()

                // 8. Test Media QoL Push (0x05)
                testRow(
                    title: "Test Media QoL Push (0x05)",
                    subtitle: "Sends mock 96x96 cover art, audio route, and duration over Channel 0x05",
                    icon: "music.note.tv.fill",
                    color: .teal,
                    isLoading: mediaQoLLoading,
                    resultText: mediaQoLResult,
                    resultColor: .teal
                ) {
                    mediaQoLLoading = true
                    mediaQoLResult = nil
                    viewModel.testMediaQoLPush { result in
                        mediaQoLLoading = false
                        switch result {
                        case .success(let info):
                            mediaQoLResult = info
                        case .failure(let err):
                            mediaQoLResult = "Err: \(err.localizedDescription)"
                        }
                    }
                }
            }
        }
        .padding(14)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .cornerRadius(14)
        .shadow(color: .black.opacity(0.04), radius: 6, y: 2)
    }

    private func testRow(
        title: String,
        subtitle: String,
        icon: String,
        color: Color,
        isLoading: Bool,
        resultText: String?,
        resultColor: Color,
        action: @escaping () -> Void
    ) -> some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(color.opacity(0.15))
                    .frame(width: 36, height: 36)
                Image(systemName: icon)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(color)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline)
                    .fontWeight(.semibold)
                Text(subtitle)
                    .font(.caption2)
                    .foregroundColor(.secondary)

                if let res = resultText {
                    Text(res)
                        .font(.caption)
                        .fontWeight(.bold)
                        .foregroundColor(resultColor)
                        .padding(.top, 2)
                }
            }

            Spacer()

            Button(action: action) {
                if isLoading {
                    ProgressView()
                        .scaleEffect(0.8)
                } else {
                    Text("Run")
                        .font(.subheadline)
                        .fontWeight(.bold)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 6)
                        .background(color)
                        .foregroundColor(.white)
                        .clipShape(Capsule())
                }
            }
            .disabled(isLoading)
        }
    }

    private func runApkTransferTest() {
        apkTransferLoading = true
        apkTransferResult = nil

        viewModel.testMockWatchFaceApkTransfer { result in
            apkTransferLoading = false
            switch result {
            case .success(let savedPath):
                apkTransferResult = "Verified: \(URL(fileURLWithPath: savedPath).lastPathComponent)"
            case .failure(let err):
                apkTransferResult = "Failed: \(err.localizedDescription)"
            }
        }
    }

    private func runFileTransferTest() {
        fileTransferLoading = true
        fileTransferProgress = 0.0
        fileTransferResult = nil

        viewModel.sendTestFileTransfer(
            sizeBytes: 100 * 1024,
            onProgress: { progress in
                fileTransferProgress = progress
            },
            completion: { result in
                fileTransferLoading = false
                switch result {
                case .success(let speed):
                    fileTransferResult = "Transfer Speed: \(String(format: "%.1f", speed)) KB/s (CRC32 Verified)"
                case .failure(let err):
                    fileTransferResult = "Failed: \(err.localizedDescription)"
                }
            }
        )
    }

    // MARK: - App Brand Registry Catalog
    private var appBrandRegistrySection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "square.grid.2x2.fill")
                    .foregroundColor(.pink)
                Text("App Brand Registry Mappings")
                    .font(.subheadline)
                    .fontWeight(.bold)
                    .foregroundColor(.secondary)

                Spacer()

                Text("v2.0.0 Spec")
                    .font(.system(size: 9, weight: .bold, design: .monospaced))
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(Color.pink.opacity(0.12))
                    .foregroundColor(.pink)
                    .cornerRadius(4)
            }

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                ForEach([
                    AppBrandRegistry.bundleTikTokGlobal,
                    AppBrandRegistry.bundleTikTokAme,
                    AppBrandRegistry.bundleWhatsApp,
                    AppBrandRegistry.bundleInstagram,
                    AppBrandRegistry.bundleThreads,
                    AppBrandRegistry.bundleTelegram,
                    AppBrandRegistry.bundleMessenger,
                    AppBrandRegistry.bundleDiscord,
                    AppBrandRegistry.bundleSnapchat,
                    AppBrandRegistry.bundleXTwitter,
                    AppBrandRegistry.bundleSpotify,
                    AppBrandRegistry.bundleYouTube,
                    AppBrandRegistry.bundleMusic,
                    AppBrandRegistry.bundleIMessage,
                    AppBrandRegistry.bundlePhone,
                    AppBrandRegistry.bundleFaceTime
                ], id: \.self) { bundleId in
                    let brand = AppBrandRegistry.resolve(bundleId)
                    HStack(spacing: 8) {
                        Circle()
                            .fill(brand.primaryColor.opacity(0.15))
                            .frame(width: 28, height: 28)
                            .overlay(
                                Image(systemName: brand.sfSymbol)
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundColor(brand.primaryColor)
                            )
                        VStack(alignment: .leading, spacing: 1) {
                            Text(brand.displayName)
                                .font(.caption)
                                .fontWeight(.semibold)
                            Text(bundleId.components(separatedBy: ".").last ?? bundleId)
                                .font(.system(size: 9, design: .monospaced))
                                .foregroundColor(.secondary)
                                .lineLimit(1)
                        }
                        Spacer()
                    }
                    .padding(6)
                    .background(Color(uiColor: .tertiarySystemGroupedBackground))
                    .cornerRadius(8)
                }
            }
        }
        .padding(14)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .cornerRadius(14)
        .shadow(color: .black.opacity(0.04), radius: 6, y: 2)
    }

    // MARK: - Live Packet Multiplexer Log
    private var livePacketLogSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "list.bullet.rectangle.fill")
                    .foregroundColor(.indigo)
                Text("Live Packet Log")
                    .font(.subheadline)
                    .fontWeight(.bold)
                    .foregroundColor(.secondary)

                Text("\(viewModel.packetLogs.count)")
                    .font(.caption2)
                    .fontWeight(.bold)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Color.secondary.opacity(0.2))
                    .clipShape(Capsule())

                Spacer()

                Button("Clear") {
                    viewModel.clearPacketLogs()
                }
                .font(.caption)
                .foregroundColor(.red)
            }

            if viewModel.packetLogs.isEmpty {
                HStack {
                    Spacer()
                    VStack(spacing: 6) {
                        Image(systemName: "tray")
                            .font(.title2)
                            .foregroundColor(.secondary)
                        Text("No packets logged yet")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    .padding(.vertical, 24)
                    Spacer()
                }
            } else {
                LazyVStack(spacing: 6) {
                    ForEach(viewModel.packetLogs) { entry in
                        packetRow(entry)
                    }
                }
            }
        }
        .padding(14)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .cornerRadius(14)
        .shadow(color: .black.opacity(0.04), radius: 6, y: 2)
    }

    private func packetRow(_ entry: PacketLogEntry) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                // Direction pill
                Text(entry.direction.rawValue)
                    .font(.system(size: 9, weight: .bold))
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(entry.direction == .tx ? Color.blue : Color.green)
                    .foregroundColor(.white)
                    .cornerRadius(4)

                // Channel pill
                Text(entry.channel.name)
                    .font(.system(size: 10, weight: .semibold))
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(Color.indigo.opacity(0.15))
                    .foregroundColor(.indigo)
                    .cornerRadius(4)

                // Flags
                Text(entry.flags.description)
                    .font(.system(size: 9, weight: .bold, design: .monospaced))
                    .padding(.horizontal, 4)
                    .padding(.vertical, 2)
                    .background(Color.secondary.opacity(0.12))
                    .foregroundColor(.secondary)
                    .cornerRadius(4)

                // Seq & Size
                Text("Seq #\(entry.sequenceNumber) • \(entry.payloadSize)B")
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundColor(.secondary)

                Spacer()

                // Timestamp
                Text(entry.formattedTime)
                    .font(.system(size: 9, design: .monospaced))
                    .foregroundColor(.secondary)
            }

            Text(entry.summary)
                .font(.system(size: 11, design: .monospaced))
                .foregroundColor(.primary)
                .lineLimit(1)
        }
        .padding(8)
        .background(Color(uiColor: .tertiarySystemGroupedBackground))
        .cornerRadius(8)
    }
}

