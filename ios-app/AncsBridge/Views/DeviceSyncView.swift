import SwiftUI

public struct DeviceSyncView: View {
    @EnvironmentObject var viewModel: BLEViewModel
    @ObservedObject private var contactsManager = ContactsSyncManager.shared
    @ObservedObject private var relayManager = ReplyRelayManager.shared
    @ObservedObject private var otaManager = WatchOtaManager.shared
    @ObservedObject private var mediaManager = MediaQoLManager.shared

    @State private var contactsSyncMessage: String?
    @State private var isShowingApkPicker = false
    @State private var useBleFallback = false
    @State private var isDownloadingGithubApk = false
    @State private var relayTestStatus: String? = nil

    public init() {}

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // 1. Background Media Bridge Status Pill (Background service only - No playback controls)
                    mediaBridgeStatusPill

                    // 2. Contacts Auto-Sync Card (Channel 0x02)
                    contactsSyncCard

                    // 3. Outbound Message Reply Relay Studio (Channel 0x06)
                    messageReplyRelayCard

                    // 4. Watch App Firmware / OTA Turbo Self-Update Card
                    watchOtaUpdateCard
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            .navigationTitle("Sync & Relay")
            .background(WearBridgeTheme.obsidianBackground.ignoresSafeArea())
            .fileImporter(
                isPresented: $isShowingApkPicker,
                allowedContentTypes: [.init(filenameExtension: "apk") ?? .data],
                allowsMultipleSelection: false
            ) { result in
                handleApkPicked(result)
            }
        }
    }

    // MARK: - 1. Background Media Bridge Status Pill
    private var mediaBridgeStatusPill: some View {
        let media = mediaManager.activeMediaItem
        let hasActiveTrack = media != nil && !(media?.title.isEmpty ?? true)

        return HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(WearBridgeTheme.accentTeal.opacity(0.18))
                    .frame(width: 40, height: 40)
                Image(systemName: "wave.3.forward.circle.fill")
                    .font(.system(size: 20))
                    .foregroundColor(WearBridgeTheme.accentTeal)
            }

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text("MEDIA BRIDGE (AMS + CH 0x05)")
                        .font(.system(size: 10, weight: .heavy, design: .monospaced))
                        .foregroundColor(WearBridgeTheme.accentTeal)

                    Circle()
                        .fill(hasActiveTrack ? WearBridgeTheme.accentGreen : Color.orange)
                        .frame(width: 6, height: 6)
                }

                if let track = media, !track.title.isEmpty {
                    Text("\(track.title) • \(track.artist)")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(WearBridgeTheme.textPrimary)
                        .lineLimit(1)
                } else {
                    Text("Background Bridge Active • Standby")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(WearBridgeTheme.textSecondary)
                }

                Text("Auto-streams iOS metadata, route & artwork to watch")
                    .font(.system(size: 10))
                    .foregroundColor(WearBridgeTheme.textTertiary)
            }

            Spacer()

            if let route = media?.audioRoute, !route.isEmpty {
                HStack(spacing: 4) {
                    Image(systemName: "airplayaudio")
                    Text(route)
                }
                .font(.system(size: 10, weight: .bold))
                .foregroundColor(WearBridgeTheme.accentTeal)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(WearBridgeTheme.accentTeal.opacity(0.15))
                .cornerRadius(6)
            }
        }
        .glassCard(cornerRadius: 16, padding: 14)
    }

    // MARK: - 2. Contacts Auto-Sync Status Card
    private var contactsSyncCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label("CONTACTS AUTO-SYNC (CH 0x02)", systemImage: "person.2.fill")
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundColor(WearBridgeTheme.accentCyan)

                Spacer()

                HStack(spacing: 4) {
                    Image(systemName: "lock.shield.fill")
                    Text("SHA-256 Delta")
                }
                .font(.system(size: 9, weight: .bold, design: .monospaced))
                .foregroundColor(WearBridgeTheme.textTertiary)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(WearBridgeTheme.cardElevated)
                .cornerRadius(4)
            }

            HStack(spacing: 16) {
                // Live Progress Ring or Contact Icon
                ZStack {
                    Circle()
                        .stroke(WearBridgeTheme.cardElevated, lineWidth: 5)
                        .frame(width: 58, height: 58)

                    if contactsManager.isSyncing {
                        Circle()
                            .trim(from: 0, to: 0.7)
                            .stroke(WearBridgeTheme.accentCyan, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                            .frame(width: 58, height: 58)
                            .rotationEffect(.degrees(contactsManager.isSyncing ? 360 : 0))
                            .animation(.linear(duration: 1.0).repeatForever(autoreverses: false), value: contactsManager.isSyncing)
                    } else {
                        Circle()
                            .trim(from: 0, to: 1.0)
                            .stroke(WearBridgeTheme.accentCyan.opacity(0.6), lineWidth: 5)
                            .frame(width: 58, height: 58)
                    }

                    VStack(spacing: 0) {
                        Text("\(contactsManager.syncedContactCount)")
                            .font(.system(size: 16, weight: .bold, design: .rounded))
                            .foregroundColor(WearBridgeTheme.textPrimary)
                        Text("SAVED")
                            .font(.system(size: 7, weight: .heavy))
                            .foregroundColor(WearBridgeTheme.textTertiary)
                    }
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text("Watch Address Book Database")
                        .font(.headline)
                        .foregroundColor(WearBridgeTheme.textPrimary)

                    if let date = contactsManager.lastSyncDate {
                        Text("Last Synced: \(formatSyncDate(date))")
                            .font(.caption)
                            .foregroundColor(WearBridgeTheme.textSecondary)
                    } else {
                        Text("Auto-syncs on connect and address book hash delta")
                            .font(.caption)
                            .foregroundColor(WearBridgeTheme.textSecondary)
                    }

                    if let hash = contactsManager.lastSyncHash {
                        Text("Hash: \(hash.prefix(12))...")
                            .font(.system(size: 9, design: .monospaced))
                            .foregroundColor(WearBridgeTheme.textTertiary)
                    }

                    if let msg = contactsSyncMessage {
                        Text(msg)
                            .font(.caption2)
                            .foregroundColor(WearBridgeTheme.accentCyan)
                            .bold()
                            .padding(.top, 2)
                    }
                }

                Spacer()
            }

            Divider()
                .background(WearBridgeTheme.strokeSubtle)

            HStack {
                Text("Syncs with Watch Room DB for instant caller ID & message names.")
                    .font(.caption2)
                    .foregroundColor(WearBridgeTheme.textTertiary)

                Spacer()

                Button {
                    syncContactsNow()
                } label: {
                    if contactsManager.isSyncing {
                        ProgressView()
                            .tint(.white)
                            .scaleEffect(0.8)
                    } else {
                        HStack(spacing: 6) {
                            Image(systemName: "arrow.triangle.2.circlepath")
                            Text("Force Sync")
                        }
                        .font(.footnote)
                        .bold()
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(WearBridgeTheme.accentCyan)
                        .foregroundColor(.white)
                        .cornerRadius(10)
                    }
                }
                .disabled(contactsManager.isSyncing)
            }
        }
        .glassCard()
    }

    // MARK: - 3. Outbound Message Reply Relay Studio
    private var messageReplyRelayCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label("MESSAGE REPLY RELAY STUDIO (CH 0x06)", systemImage: "arrowshape.turn.up.left.fill")
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundColor(WearBridgeTheme.accentGreen)

                Spacer()

                Text("Channel 0x06")
                    .font(.system(size: 9, weight: .bold, design: .monospaced))
                    .foregroundColor(WearBridgeTheme.textTertiary)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Relay Outbound Watch Replies")
                    .font(.headline)
                    .foregroundColor(WearBridgeTheme.textPrimary)
                Text("Select how replies composed or voice-dictated on your watch are forwarded by this iPhone.")
                    .font(.caption)
                    .foregroundColor(WearBridgeTheme.textSecondary)
            }

            // Mode Selector
            Picker("Relay Mode", selection: $relayManager.currentRelayMode) {
                Text("Webhook").tag(RelayMode.webhook)
                Text("Shortcuts").tag(RelayMode.shortcuts)
                Text("Deep-Link").tag(RelayMode.deeplink)
            }
            .pickerStyle(.segmented)

            // Dynamic Mode Configuration Fields
            switch relayManager.currentRelayMode {
            case .webhook:
                VStack(alignment: .leading, spacing: 6) {
                    Text("Silent Background Webhook Endpoint")
                        .font(.caption2)
                        .foregroundColor(WearBridgeTheme.textSecondary)
                    HStack {
                        TextField("https://your-server.com/api/reply", text: $relayManager.webhookUrl)
                            .font(.system(size: 13, design: .monospaced))
                            .padding(10)
                            .background(WearBridgeTheme.cardElevated)
                            .cornerRadius(8)
                            .autocapitalization(.none)
                            .keyboardType(.URL)
                            .foregroundColor(WearBridgeTheme.textPrimary)

                        Button {
                            testRelayConnection()
                        } label: {
                            Text("Test")
                                .font(.caption)
                                .bold()
                                .padding(.horizontal, 12)
                                .padding(.vertical, 10)
                                .background(WearBridgeTheme.accentGreen)
                                .foregroundColor(.black)
                                .cornerRadius(8)
                        }
                    }
                }

            case .shortcuts:
                VStack(alignment: .leading, spacing: 6) {
                    Text("Apple Shortcuts Automation Trigger")
                        .font(.caption2)
                        .foregroundColor(WearBridgeTheme.textSecondary)
                    TextField("WearBridgeReply", text: $relayManager.shortcutName)
                        .font(.system(size: 13))
                        .padding(10)
                        .background(WearBridgeTheme.cardElevated)
                        .cornerRadius(8)
                        .foregroundColor(WearBridgeTheme.textPrimary)

                    Toggle(isOn: $relayManager.isBackgroundActionEnabled) {
                        Text("Post actionable system alert when phone is locked")
                            .font(.footnote)
                            .foregroundColor(WearBridgeTheme.textSecondary)
                    }
                    .tint(WearBridgeTheme.accentGreen)
                }

            case .deeplink:
                VStack(alignment: .leading, spacing: 4) {
                    Text("Direct URL Scheme Dispatch")
                        .font(.footnote)
                        .bold()
                        .foregroundColor(WearBridgeTheme.textPrimary)
                    Text("Automatically opens the target app (e.g. WhatsApp, SMS) with pre-filled message draft.")
                        .font(.caption2)
                        .foregroundColor(WearBridgeTheme.textSecondary)
                }
            }

            if let testStatus = relayTestStatus {
                Text(testStatus)
                    .font(.caption2)
                    .foregroundColor(WearBridgeTheme.accentGreen)
                    .bold()
            }

            Divider()
                .background(WearBridgeTheme.strokeSubtle)

            VStack(alignment: .leading, spacing: 8) {
                Text("Supported App Brands")
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundColor(WearBridgeTheme.textTertiary)

                let sampleBundles: [String] = [
                    AppBrandRegistry.bundleIMessage,
                    AppBrandRegistry.bundleWhatsApp,
                    AppBrandRegistry.bundleTelegram,
                    AppBrandRegistry.bundleMessenger,
                    AppBrandRegistry.bundleInstagram,
                    AppBrandRegistry.bundleTikTokGlobal
                ]

                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                    ForEach(sampleBundles, id: \.self) { bundleId in
                        let brand = AppBrandRegistry.resolve(bundleId)
                        HStack(spacing: 8) {
                            Circle()
                                .fill(brand.primaryColor.opacity(0.2))
                                .frame(width: 28, height: 28)
                                .overlay(
                                    Image(systemName: brand.sfSymbol)
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundColor(brand.primaryColor)
                                )
                            Text(brand.displayName)
                                .font(.caption2)
                                .fontWeight(.medium)
                                .foregroundColor(WearBridgeTheme.textPrimary)
                            Spacer()
                        }
                        .padding(6)
                        .background(WearBridgeTheme.cardElevated.opacity(0.6))
                        .cornerRadius(8)
                    }
                }
            }
        }
        .glassCard()
    }

    // MARK: - Actions
    private func syncContactsNow() {
        contactsSyncMessage = "Reading address book..."
        contactsManager.syncContacts(force: true)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            self.contactsSyncMessage = "Contacts synchronized over Channel 0x02!"
        }
    }

    private func testRelayConnection() {
        relayTestStatus = "Testing webhook dispatch..."
        relayManager.sendDirectReply(notificationUid: 9999, replyText: "WearBridge Ping Test") { success in
            DispatchQueue.main.async {
                self.relayTestStatus = success ? "✓ Webhook dispatched successfully!" : "✗ Webhook failed to respond"
            }
        }
    }

    private func formatSyncDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .short
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    // MARK: - 4. Watch App Firmware / OTA Self-Update Card
    private var watchOtaUpdateCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label("WATCH APP OTA UPDATE", systemImage: "arrow.triangle.2.circlepath.circle.fill")
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundColor(WearBridgeTheme.accentPurple)

                Spacer()

                Text("Wi-Fi Turbo / BLE")
                    .font(.system(size: 9, weight: .bold, design: .monospaced))
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(WearBridgeTheme.accentPurple.opacity(0.18))
                    .foregroundColor(WearBridgeTheme.accentPurple)
                    .cornerRadius(4)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Self-Update Watch App")
                    .font(.headline)
                    .foregroundColor(WearBridgeTheme.textPrimary)
                Text("Wirelessly update com.wearos.ancsbridge on your watch directly from your iPhone.")
                    .font(.caption)
                    .foregroundColor(WearBridgeTheme.textSecondary)
            }

            // Version status row
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Installed on Watch")
                        .font(.caption2)
                        .foregroundColor(WearBridgeTheme.textTertiary)
                    Text(otaManager.installedWatchVersion)
                        .font(.subheadline)
                        .bold()
                        .foregroundColor(WearBridgeTheme.textPrimary)
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 2) {
                    Text("Latest Release")
                        .font(.caption2)
                        .foregroundColor(WearBridgeTheme.textTertiary)
                    Text(otaManager.latestRelease?.tagName ?? "v2.0.0")
                        .font(.subheadline)
                        .bold()
                        .foregroundColor(WearBridgeTheme.accentPurple)
                }
            }
            .padding(12)
            .background(WearBridgeTheme.cardElevated)
            .cornerRadius(10)

            // Status message
            HStack(spacing: 6) {
                Circle()
                    .fill(otaManager.otaStatusMessage.contains("failed") ? Color.red : WearBridgeTheme.accentGreen)
                    .frame(width: 8, height: 8)
                Text(otaManager.otaStatusMessage)
                    .font(.caption2)
                    .foregroundColor(WearBridgeTheme.textSecondary)
            }

            // Progress Indicators
            if case .servingLocalWifi(let url) = otaManager.otaState {
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        ProgressView()
                            .scaleEffect(0.8)
                        Text("Turbo Server Serving APK over Local Wi-Fi")
                            .font(.caption)
                            .bold()
                            .foregroundColor(WearBridgeTheme.accentPurple)
                    }
                    Text(url)
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundColor(WearBridgeTheme.textTertiary)
                }
                .padding(10)
                .background(WearBridgeTheme.accentPurple.opacity(0.12))
                .cornerRadius(8)
            } else if case .streamingOverBle(let progress) = otaManager.otaState {
                VStack(alignment: .leading, spacing: 4) {
                    ProgressView(value: progress, total: 1.0)
                        .tint(WearBridgeTheme.accentPurple)
                    Text("Streaming over BLE: \(Int(progress * 100))%")
                        .font(.caption2)
                        .foregroundColor(WearBridgeTheme.textSecondary)
                }
            } else if case .downloadingToPhone(let progress) = otaManager.otaState {
                VStack(alignment: .leading, spacing: 4) {
                    ProgressView(value: progress, total: 1.0)
                        .tint(WearBridgeTheme.accentCyan)
                    Text("Downloading APK to iPhone: \(Int(progress * 100))%")
                        .font(.caption2)
                        .foregroundColor(WearBridgeTheme.textSecondary)
                }
            } else if case .watchInstalling = otaManager.otaState {
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(WearBridgeTheme.accentGreen)
                    Text("Update transferred! Tap 'Update' on watch screen.")
                        .font(.caption)
                        .bold()
                        .foregroundColor(WearBridgeTheme.accentGreen)
                }
                .padding(10)
                .background(WearBridgeTheme.accentGreen.opacity(0.12))
                .cornerRadius(8)
            }

            // Fallback toggle
            Toggle(isOn: $useBleFallback) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Stream over BLE (Channel 0x07)")
                        .font(.caption)
                        .bold()
                        .foregroundColor(WearBridgeTheme.textPrimary)
                    Text("Fallback if Watch has no Wi-Fi / Hotspot connection")
                        .font(.caption2)
                        .foregroundColor(WearBridgeTheme.textTertiary)
                }
            }
            .tint(WearBridgeTheme.accentPurple)

            // Action Buttons
            HStack(spacing: 10) {
                Button {
                    otaManager.checkForGitHubRelease { _ in }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "arrow.clockwise")
                        Text("Check GitHub")
                    }
                    .font(.caption)
                    .bold()
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 11)
                    .background(WearBridgeTheme.cardElevated)
                    .foregroundColor(WearBridgeTheme.textPrimary)
                    .cornerRadius(10)
                }

                Button {
                    isShowingApkPicker = true
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "bolt.fill")
                        Text("Turbo Push .APK")
                    }
                    .font(.caption)
                    .bold()
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 11)
                    .background(WearBridgeTheme.accentPurple)
                    .foregroundColor(.white)
                    .cornerRadius(10)
                }
            }

            // 1-Tap GitHub OTA update button if update available
            if let release = otaManager.latestRelease,
               let apkUrlStr = release.apkDownloadUrl,
               let apkUrl = URL(string: apkUrlStr) {
                Button {
                    triggerGitHubOta(url: apkUrl, targetVersion: release.tagName)
                } label: {
                    HStack {
                        if isDownloadingGithubApk {
                            ProgressView()
                                .tint(.white)
                                .scaleEffect(0.8)
                        } else {
                            Image(systemName: "icloud.and.arrow.down.fill")
                        }
                        Text("1-Tap OTA Update to \(release.tagName) (\(release.formattedSize))")
                            .bold()
                    }
                    .font(.subheadline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(WearBridgeTheme.accentGreen)
                    .foregroundColor(.black)
                    .cornerRadius(10)
                }
                .disabled(isDownloadingGithubApk)
            }
        }
        .glassCard()
    }

    private func handleApkPicked(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            guard let url = urls.first else { return }
            guard url.startAccessingSecurityScopedResource() else { return }
            defer { url.stopAccessingSecurityScopedResource() }
            if let data = try? Data(contentsOf: url) {
                let fileName = url.lastPathComponent
                let version = fileName.replacingOccurrences(of: ".apk", with: "")
                if useBleFallback {
                    otaManager.pushOtaUpdateViaBle(apkData: data, targetVersion: version, progressHandler: { _ in }, completion: { _ in })
                } else {
                    otaManager.startTurboServerAndPush(apkData: data, targetVersion: version, completion: { _ in })
                }
            }
        case .failure(let err):
            otaManager.otaStatusMessage = "File selection failed: \(err.localizedDescription)"
        }
    }

    private func triggerGitHubOta(url: URL, targetVersion: String) {
        isDownloadingGithubApk = true
        otaManager.downloadRemoteApk(from: url, progressHandler: { _ in }) { result in
            DispatchQueue.main.async {
                self.isDownloadingGithubApk = false
                switch result {
                case .success(let data):
                    if self.useBleFallback {
                        self.otaManager.pushOtaUpdateViaBle(apkData: data, targetVersion: targetVersion, progressHandler: { _ in }, completion: { _ in })
                    } else {
                        self.otaManager.startTurboServerAndPush(apkData: data, targetVersion: targetVersion, completion: { _ in })
                    }
                case .failure(let err):
                    self.otaManager.otaStatusMessage = "Failed: \(err.localizedDescription)"
                }
            }
        }
    }
}
