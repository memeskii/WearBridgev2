import SwiftUI

public struct DeviceSyncView: View {
    @EnvironmentObject var viewModel: BLEViewModel
    @ObservedObject private var mediaManager = MediaQoLManager.shared
    @ObservedObject private var relayManager = ReplyRelayManager.shared
    @ObservedObject private var otaManager = WatchOtaManager.shared

    @State private var isSyncingContacts = false
    @State private var contactsSyncMessage: String?
    @State private var isPlayingMockMedia = false
    @State private var isShowingApkPicker = false
    @State private var useBleFallback = false
    @State private var isDownloadingGithubApk = false

    public init() {}

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // 1. Now Playing Media Card
                    nowPlayingCard

                    // 2. Contacts Auto-Sync Status Card
                    contactsSyncCard

                    // 3. Outbound Message Reply Relay Configuration
                    messageReplyRelayCard

                    // 4. Watch App Firmware / OTA Self-Update Card
                    watchOtaUpdateCard
                }
                .padding(.horizontal)
                .padding(.vertical, 12)
            }
            .navigationTitle("Device Sync & Media")
            .background(Color(.systemGroupedBackground))
            .fileImporter(
                isPresented: $isShowingApkPicker,
                allowedContentTypes: [.init(filenameExtension: "apk") ?? .data],
                allowsMultipleSelection: false
            ) { result in
                handleApkPicked(result)
            }
        }
    }

    // MARK: - 1. Now Playing Media Card
    private var nowPlayingCard: some View {
        let media = mediaManager.activeMediaItem

        return VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label("NOW PLAYING (AMS + 0x05)", systemImage: "music.note")
                    .font(.caption2)
                    .fontWeight(.bold)
                    .foregroundColor(.teal)

                Spacer()

                // Audio Route Badge
                HStack(spacing: 4) {
                    Image(systemName: "airplayaudio")
                    Text(media?.audioRoute ?? "iPhone Speaker")
                }
                .font(.caption2)
                .fontWeight(.medium)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(Color.teal.opacity(0.15))
                .foregroundColor(.teal)
                .cornerRadius(6)
            }

            HStack(spacing: 14) {
                // Album Artwork
                ZStack {
                    RoundedRectangle(cornerRadius: 10)
                        .fill(LinearGradient(colors: [.teal.opacity(0.8), .indigo], startPoint: .topLeading, endPoint: .bottomTrailing))
                        .frame(width: 64, height: 64)

                    if let artBase64 = media?.artworkBase64,
                       let artData = Data(base64Encoded: artBase64),
                       let uiImage = UIImage(data: artData) {
                        Image(uiImage: uiImage)
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                            .frame(width: 64, height: 64)
                            .cornerRadius(10)
                    } else {
                        Image(systemName: "music.note.list")
                            .font(.title2)
                            .foregroundColor(.white)
                    }
                }

                VStack(alignment: .leading, spacing: 3) {
                    Text(media?.title ?? "Blinding Lights")
                        .font(.headline)
                        .lineLimit(1)

                    Text(media?.artist ?? "The Weeknd")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .lineLimit(1)

                    Text(media?.album ?? "After Hours")
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }

                Spacer()
            }

            // Track Progress Bar & Time
            VStack(spacing: 4) {
                ProgressView(value: 0.42)
                    .tint(.teal)

                HStack {
                    Text("1:28")
                        .font(.system(size: 10))
                        .foregroundColor(.secondary)
                    Spacer()
                    Text("3:20")
                        .font(.system(size: 10))
                        .foregroundColor(.secondary)
                }
            }

            Divider()

            // Playback Controls
            HStack {
                Spacer()
                Button {
                    viewModel.testMediaQoLPush { _ in }
                } label: {
                    Image(systemName: "backward.fill")
                        .font(.title3)
                        .foregroundColor(.primary)
                }

                Spacer()

                Button {
                    viewModel.testMediaQoLPush { _ in }
                    isPlayingMockMedia.toggle()
                } label: {
                    ZStack {
                        Circle()
                            .fill(Color.teal)
                            .frame(width: 44, height: 44)
                        Image(systemName: isPlayingMockMedia ? "pause.fill" : "play.fill")
                            .font(.headline)
                            .foregroundColor(.white)
                    }
                }

                Spacer()

                Button {
                    viewModel.testMediaQoLPush { _ in }
                } label: {
                    Image(systemName: "forward.fill")
                        .font(.title3)
                        .foregroundColor(.primary)
                }
                Spacer()
            }
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(16)
        .shadow(color: Color.black.opacity(0.04), radius: 6, x: 0, y: 3)
    }

    // MARK: - 2. Contacts Auto-Sync Status Card
    private var contactsSyncCard: some View {
        let lastSyncDate = UserDefaults.standard.object(forKey: "wearbridge_contacts_last_synced") as? Date

        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("CONTACTS AUTO-SYNC (0x02)", systemImage: "person.2.fill")
                    .font(.caption2)
                    .fontWeight(.bold)
                    .foregroundColor(.blue)

                Spacer()

                Text("SHA-256 Delta")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }

            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Watch Address Book")
                        .font(.headline)

                    if let date = lastSyncDate {
                        Text("Last synced: \(formatSyncDate(date))")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    } else {
                        Text("Syncs on connect & hash changes")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }

                    if let msg = contactsSyncMessage {
                        Text(msg)
                            .font(.caption2)
                            .foregroundColor(.blue)
                            .bold()
                            .padding(.top, 2)
                    }
                }

                Spacer()

                Button {
                    syncContactsNow()
                } label: {
                    if isSyncingContacts {
                        ProgressView()
                            .scaleEffect(0.8)
                    } else {
                        HStack(spacing: 4) {
                            Image(systemName: "arrow.triangle.2.circlepath")
                            Text("Force Sync")
                        }
                        .font(.footnote)
                        .bold()
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(Color.blue)
                        .foregroundColor(.white)
                        .cornerRadius(8)
                    }
                }
                .disabled(isSyncingContacts)
            }
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(16)
        .shadow(color: Color.black.opacity(0.04), radius: 6, x: 0, y: 3)
    }

    // MARK: - 3. Outbound Message Reply Relay Configuration
    private var messageReplyRelayCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("OUTBOUND MESSAGE RELAY (0x06)", systemImage: "arrowshape.turn.up.left.fill")
                .font(.caption2)
                .fontWeight(.bold)
                .foregroundColor(.green)

            VStack(alignment: .leading, spacing: 4) {
                Text("Relay Outbound Watch Replies")
                    .font(.headline)
                Text("Select how replies transmitted over Channel 0x06 are delivered to iMessage, WhatsApp, or automation services.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Picker("Relay Mode", selection: $relayManager.currentRelayMode) {
                Text("Webhook").tag(RelayMode.webhook)
                Text("Shortcuts").tag(RelayMode.shortcuts)
                Text("Deep-Link").tag(RelayMode.deepLink)
            }
            .pickerStyle(.segmented)

            // Dynamic Mode Configuration Fields
            switch relayManager.currentRelayMode {
            case .webhook:
                VStack(alignment: .leading, spacing: 6) {
                    Text("Webhook HTTP Endpoint (Silent Background)")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    TextField("https://your-server.com/api/reply", text: $relayManager.webhookUrl)
                        .textFieldStyle(.roundedBorder)
                        .autocapitalization(.none)
                        .keyboardType(.URL)
                }

            case .shortcuts:
                VStack(alignment: .leading, spacing: 6) {
                    Text("Apple Shortcuts Automation Name")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    TextField("WearBridgeReply", text: $relayManager.shortcutName)
                        .textFieldStyle(.roundedBorder)

                    Toggle(isOn: $relayManager.isBackgroundActionEnabled) {
                        Text("Post actionable notification when locked")
                            .font(.footnote)
                    }
                }

            case .deepLink:
                VStack(alignment: .leading, spacing: 4) {
                    Text("Direct Scheme Launching")
                        .font(.footnote)
                        .bold()
                    Text("Directly opens whatsapp:// or sms: with draft populated when reply arrives.")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            }

            Divider()

            VStack(alignment: .leading, spacing: 8) {
                Text("Supported Messaging Apps")
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .bold()

                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                    ForEach([
                        AppBrandRegistry.bundleIMessage,
                        AppBrandRegistry.bundleWhatsApp,
                        AppBrandRegistry.bundleTelegram,
                        AppBrandRegistry.bundleMessenger,
                        AppBrandRegistry.bundleInstagram,
                        AppBrandRegistry.bundleTikTokGlobal
                    ], id: \.self) { bundleId in
                        let brand = AppBrandRegistry.resolve(bundleId)
                        HStack(spacing: 8) {
                            Circle()
                                .fill(brand.primaryColor.opacity(0.15))
                                .frame(width: 26, height: 26)
                                .overlay(
                                    Image(systemName: brand.sfSymbol)
                                        .font(.system(size: 11, weight: .semibold))
                                        .foregroundColor(brand.primaryColor)
                                )
                            Text(brand.displayName)
                                .font(.caption2)
                                .fontWeight(.medium)
                            Spacer()
                        }
                    }
                }
            }
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(16)
        .shadow(color: Color.black.opacity(0.04), radius: 6, x: 0, y: 3)
    }

    // MARK: - Actions
    private func syncContactsNow() {
        isSyncingContacts = true
        contactsSyncMessage = "Fetching contacts..."
        ContactsSyncManager.shared.syncContacts(force: true)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            self.isSyncingContacts = false
            self.contactsSyncMessage = "Contacts synchronized over Channel 0x02!"
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
                    .font(.caption2)
                    .fontWeight(.bold)
                    .foregroundColor(.purple)

                Spacer()

                Text("Wi-Fi / Hotspot Turbo")
                    .font(.system(size: 9, weight: .bold, design: .monospaced))
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Color.purple.opacity(0.12))
                    .foregroundColor(.purple)
                    .cornerRadius(4)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Self-Update Watch App")
                    .font(.headline)
                Text("Wirelessly update com.wearos.ancsbridge on your watch without a PC or ADB.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            // Version status row
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Installed on Watch")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    Text(otaManager.installedWatchVersion)
                        .font(.subheadline)
                        .bold()
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 2) {
                    Text("Latest Available")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    Text(otaManager.latestRelease?.tagName ?? "v2.0.0")
                        .font(.subheadline)
                        .bold()
                        .foregroundColor(.purple)
                }
            }
            .padding(10)
            .background(Color(.tertiarySystemGroupedBackground))
            .cornerRadius(10)

            // Status message
            HStack(spacing: 6) {
                Circle()
                    .fill(otaManager.otaStatusMessage.contains("failed") ? Color.red : Color.green)
                    .frame(width: 8, height: 8)
                Text(otaManager.otaStatusMessage)
                    .font(.caption2)
                    .foregroundColor(.secondary)
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
                            .foregroundColor(.purple)
                    }
                    Text(url)
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundColor(.secondary)
                }
                .padding(8)
                .background(Color.purple.opacity(0.08))
                .cornerRadius(8)
            } else if case .streamingOverBle(let progress) = otaManager.otaState {
                VStack(alignment: .leading, spacing: 4) {
                    ProgressView(value: progress, total: 1.0)
                        .tint(.purple)
                    Text("Streaming over BLE: \(Int(progress * 100))%")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            } else if case .downloadingToPhone(let progress) = otaManager.otaState {
                VStack(alignment: .leading, spacing: 4) {
                    ProgressView(value: progress, total: 1.0)
                        .tint(.blue)
                    Text("Downloading APK to iPhone: \(Int(progress * 100))%")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            } else if case .watchInstalling = otaManager.otaState {
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(.green)
                    Text("Update transferred! Tap 'Update' on watch screen.")
                        .font(.caption)
                        .bold()
                }
                .padding(8)
                .background(Color.green.opacity(0.1))
                .cornerRadius(8)
            }

            // Fallback toggle
            Toggle(isOn: $useBleFallback) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Stream over BLE (Channel 0x07)")
                        .font(.caption)
                        .bold()
                    Text("Fallback if Watch has no Wi-Fi / Hotspot connection")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            }

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
                    .padding(.vertical, 10)
                    .background(Color(.systemGray5))
                    .foregroundColor(.primary)
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
                    .padding(.vertical, 10)
                    .background(Color.purple)
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
                    .background(Color.green)
                    .foregroundColor(.white)
                    .cornerRadius(10)
                }
                .disabled(isDownloadingGithubApk)
            }
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(16)
        .shadow(color: Color.black.opacity(0.04), radius: 6, x: 0, y: 3)
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

