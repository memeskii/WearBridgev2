import SwiftUI

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

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // Hardware Link & Telemetry Banner
                    hardwareStatusSection

                    // Test Harness Action Buttons
                    testHarnessSection

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

                // 4. Test 100KB File Transfer (0x07)
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
                            Text("Test 100KB File Transfer (0x07)")
                                .font(.subheadline)
                                .fontWeight(.semibold)
                            Text("Streams 100KB with trailing CRC32 and verifies watch ACK")
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
                                    .background(Color.purple)
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

                // 5. Test Reply Loopback (0x06)
                testRow(
                    title: "Test Reply Loopback (0x06)",
                    subtitle: "Instructs watch to echo back a mock reply payload",
                    icon: "arrow.counterclockwise.bubble.fill",
                    color: .orange,
                    isLoading: loopbackLoading,
                    resultText: loopbackResult,
                    resultColor: .orange
                ) {
                    loopbackLoading = true
                    loopbackResult = nil
                    viewModel.sendTestReplyLoopback(message: "Diagnostics Echo Test") { result in
                        loopbackLoading = false
                        switch result {
                        case .success(let echo):
                            loopbackResult = "Echo: '\(echo)'"
                        case .failure(let err):
                            loopbackResult = "Err: \(err.localizedDescription)"
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

