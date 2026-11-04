import SwiftUI

struct ContentView: View {
    @EnvironmentObject var viewModel: BLEViewModel

    var body: some View {
        TabView {
            // Tab 1: Dashboard
            DashboardView()
                .tabItem {
                    Label("Dashboard", systemImage: "waveform.path.ecg")
                }

            // Tab 2: Watch Faces & Files
            WatchFaceHubView()
                .tabItem {
                    Label("Watch Faces", systemImage: "clock.arrow.2.circlepath")
                }

            // Tab 3: Device Sync & Media
            DeviceSyncView()
                .tabItem {
                    Label("Sync & Media", systemImage: "music.note.list")
                }

            // Tab 4: Settings & Diagnostics
            DiagnosticsView()
                .tabItem {
                    Label("Diagnostics", systemImage: "wrench.and.screwdriver")
                }
        }
        .onAppear {
            HealthKitManager.shared.requestAuthorization()
        }
    }
}
