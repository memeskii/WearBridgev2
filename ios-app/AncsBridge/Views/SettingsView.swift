import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var viewModel: BLEViewModel
    @State private var liveCallStatusEnabled: Bool = false

    var body: some View {
        NavigationStack {
            List {
                // Features
                Section {
                    NavigationLink {
                        QuickReplySettingsView()
                    } label: {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Quick Reply Messages")
                                Text("Decline calls with a preset message")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        } icon: {
                            Image(systemName: "text.bubble")
                                .foregroundColor(.blue)
                        }
                    }

                    Toggle(isOn: $liveCallStatusEnabled) {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Live Call Status")
                                Text("Push real-time call state to watch via BLE. Requires this app to be running.")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        } icon: {
                            Image(systemName: "phone.connection")
                                .foregroundColor(.green)
                        }
                    }
                    .onChange(of: liveCallStatusEnabled) { newValue in
                        viewModel.peripheralManager.settingsStore.liveCallStatus = newValue
                        viewModel.peripheralManager.updateCallStateObserving()
                    }

                    NavigationLink {
                        DiagnosticsView()
                    } label: {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Hardware Diagnostics")
                                Text("Test multiplexer, ping latency, file transfer & logs")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        } icon: {
                            Image(systemName: "wrench.and.screwdriver")
                                .foregroundColor(.purple)
                        }
                    }

                    NavigationLink {
                        WatchFaceBrowserView()
                    } label: {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Watch Face Hub & Browser")
                                Text("Browse 3rd-party watch faces, import APKs & stream over 0x07")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        } icon: {
                            Image(systemName: "clock.arrow.2.circlepath")
                                .foregroundColor(.blue)
                        }
                    }
                }

                // Data Sync
                Section {
                    Button {
                        viewModel.forceSyncContactsAndWeather { _ in }
                    } label: {
                        HStack {
                            Label {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Sync Contacts & Weather")
                                    Text("Pushes phone contacts and local forecast to watch")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                            } icon: {
                                Image(systemName: "arrow.triangle.2.circlepath")
                                    .foregroundColor(.indigo)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                    }
                } header: {
                    Text("Auto-Sync Pipelines")
                }

                // Watch OTA Firmware Updates
                Section {
                    HStack {
                        Text("Installed Watch Version")
                        Spacer()
                        Text(WatchOtaManager.shared.installedWatchVersion)
                            .foregroundColor(.secondary)
                    }

                    HStack {
                        Text("GitHub Repository")
                        Spacer()
                        TextField("owner/repo", text: Binding(
                            get: { "\(WatchOtaManager.shared.githubRepoOwner)/\(WatchOtaManager.shared.githubRepoName)" },
                            set: { val in
                                let parts = val.split(separator: "/")
                                if parts.count == 2 {
                                    WatchOtaManager.shared.githubRepoOwner = String(parts[0])
                                    WatchOtaManager.shared.githubRepoName = String(parts[1])
                                }
                            }
                        ))
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.trailing)
                    }

                    Button {
                        WatchOtaManager.shared.checkForGitHubRelease { _ in }
                    } label: {
                        HStack {
                            Label("Check for Watch Updates", systemImage: "arrow.clockwise.circle")
                            Spacer()
                            if let release = WatchOtaManager.shared.latestRelease {
                                Text(release.tagName)
                                    .font(.caption)
                                    .foregroundColor(.purple)
                            }
                        }
                    }
                } header: {
                    Text("Watch OTA Firmware Updates")
                }

                // Phase 5: Outbound Message Reply Relay
                Section {
                    Picker("Relay Mode", selection: Binding(
                        get: { viewModel.replyRelayManager.relayMode },
                        set: { viewModel.replyRelayManager.updateRelayMode($0) }
                    )) {
                        ForEach(ReplyRelayMode.allCases) { mode in
                            Text(mode.displayName).tag(mode)
                        }
                    }

                    if viewModel.replyRelayManager.relayMode == .webhook {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Webhook Bridge URL")
                                .font(.caption)
                                .foregroundColor(.secondary)
                            TextField("http://...", text: Binding(
                                get: { viewModel.replyRelayManager.webhookUrl },
                                set: { viewModel.replyRelayManager.updateWebhookUrl($0) }
                            ))
                            .textFieldStyle(.roundedBorder)
                            .autocapitalization(.none)
                            .disableAutocorrection(true)
                        }
                    } else if viewModel.replyRelayManager.relayMode == .shortcuts {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("iOS Shortcut Name")
                                .font(.caption)
                                .foregroundColor(.secondary)
                            TextField("WearBridgeReply", text: Binding(
                                get: { viewModel.replyRelayManager.shortcutName },
                                set: { viewModel.replyRelayManager.updateShortcutName($0) }
                            ))
                            .textFieldStyle(.roundedBorder)
                        }
                    }
                } header: {
                    Text("Outbound Message Reply Relay (0x06)")
                } footer: {
                    Text("Determines how outgoing replies from the Watch are dispatched on iPhone.")
                }

                // About
                Section {
                    NavigationLink {
                        HowItWorksView()
                    } label: {
                        Label("How it Works", systemImage: "questionmark.circle")
                    }

                    HStack {
                        Text("Version")
                        Spacer()
                        Text(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                            .foregroundColor(.secondary)
                    }
                } header: {
                    Text("About")
                } footer: {
                    Text("WearBridge uses Apple's ANCS to forward notifications over Bluetooth. All data stays local.")
                }
            }
            .navigationTitle("Settings")
            .onAppear {
                liveCallStatusEnabled = viewModel.peripheralManager.settingsStore.liveCallStatus
            }
        }
    }
}
