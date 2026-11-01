import SwiftUI

struct ContentView: View {
    @EnvironmentObject var viewModel: BLEViewModel

    var body: some View {
        TabView {
            NavigationStack {
                List {
                    // Galaxy Watch Battery Status Card
                    watchBatterySection

                    // Watch Health & Activity Section
                    watchHealthSection

                    // Watch Replies Section
                    watchRepliesSection
                }
                .listStyle(.insetGrouped)
                .navigationTitle("WearBridge")
            }
            .tabItem {
                Label("Replies", systemImage: "message.fill")
            }
            .badge(viewModel.incomingReplies.count)

            StatusView()
                .tabItem {
                    Label("Status", systemImage: "antenna.radiowaves.left.and.right")
                }

            SettingsView()
                .tabItem {
                    Label("Settings", systemImage: "gearshape")
                }
        }
        .onAppear {
            HealthKitManager.shared.requestAuthorization()
        }
    }

    // MARK: - Galaxy Watch Battery Status Card
    @ViewBuilder
    private var watchBatterySection: some View {
        Section(header: Text("Galaxy Watch Battery")) {
            HStack(spacing: 16) {
                // Battery Icon with dynamic status tint
                ZStack {
                    Circle()
                        .fill(viewModel.batteryTint.opacity(0.15))
                        .frame(width: 48, height: 48)

                    Image(systemName: viewModel.batterySymbolName)
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundColor(viewModel.batteryTint)
                }

                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        if let level = viewModel.watchBatteryLevel {
                            Text("\(level)%")
                                .font(.title2)
                                .fontWeight(.bold)
                                .foregroundColor(.primary)
                        } else {
                            Text("--%")
                                .font(.title2)
                                .fontWeight(.bold)
                                .foregroundColor(.secondary)
                        }

                        if viewModel.isWatchCharging {
                            HStack(spacing: 4) {
                                Image(systemName: "bolt.fill")
                                    .font(.caption2)
                                Text("Charging")
                                    .font(.caption)
                                    .fontWeight(.semibold)
                            }
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(Color.green.opacity(0.18))
                            .foregroundColor(.green)
                            .clipShape(Capsule())
                        }
                    }

                    Text(viewModel.watchBatteryLevel != nil
                         ? "Live battery telemetry over BLE"
                         : "Awaiting telemetry from watch…")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Spacer()
            }
            .padding(.vertical, 4)
        }
    }

    // MARK: - Watch Health & Activity Section
    @ViewBuilder
    private var watchHealthSection: some View {
        Section(header: Text("Watch Health & Activity")) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 10) {
                    // Steps tile
                    healthTile(
                        icon: "figure.walk",
                        color: .green,
                        value: viewModel.watchSteps > 0 ? "\(viewModel.watchSteps)" : "--",
                        label: "Steps"
                    )

                    // Heart Rate tile
                    healthTile(
                        icon: "heart.fill",
                        color: .red,
                        value: viewModel.watchHeartRate > 0 ? "\(viewModel.watchHeartRate)" : "--",
                        label: "BPM"
                    )

                    // Active Calories tile
                    healthTile(
                        icon: "flame.fill",
                        color: .orange,
                        value: viewModel.watchActiveCalories > 0 ? "\(viewModel.watchActiveCalories)" : "--",
                        label: "Active kcal"
                    )
                }

                HStack(spacing: 6) {
                    Image(systemName: "heart.text.square.fill")
                        .foregroundColor(.pink)
                        .font(.caption)

                    if let syncDate = viewModel.lastHealthSyncDate {
                        Text("Synced to Apple Health at \(syncDate, style: .time)")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    } else {
                        Text("Awaiting sensor data from watch…")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }
            }
            .padding(.vertical, 4)
        }
    }

    private func healthTile(icon: String, color: Color, value: String, label: String) -> some View {
        VStack(spacing: 6) {
            HStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.caption)
                    .foregroundColor(color)
                Text(label)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }

            Text(value)
                .font(.headline)
                .fontWeight(.bold)
                .foregroundColor(.primary)
                .minimumScaleFactor(0.8)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .padding(.horizontal, 6)
        .background(color.opacity(0.10))
        .cornerRadius(10)
    }

    // MARK: - Watch Replies Section
    @ViewBuilder
    private var watchRepliesSection: some View {
        Section(header: Text("Watch Replies")) {
            if viewModel.incomingReplies.isEmpty {
                HStack(spacing: 12) {
                    Image(systemName: "bubble.left.and.bubble.right")
                        .foregroundColor(.secondary)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("No replies received yet")
                            .font(.body)
                        Text("Tap a quick reply on your watch to see it here")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                .padding(.vertical, 4)
            } else {
                ForEach(viewModel.incomingReplies) { reply in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(reply.message)
                                .font(.body)
                                .fontWeight(.semibold)
                            Spacer()
                            Text(reply.timestamp, style: .time)
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }

                        HStack {
                            Text("Answered Notification #\(reply.notificationUid)")
                                .font(.caption)
                                .foregroundColor(.blue)
                            Spacer()
                            Text(reply.timestamp, style: .date)
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
        }
    }
}
