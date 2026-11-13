import SwiftUI

@main
struct AncsBridgeApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var bleViewModel = BLEViewModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(bleViewModel)
                .onChange(of: scenePhase, initial: true) { _, phase in
                    guard phase == .active else { return }
                    WeatherSyncService.shared.refreshIfDue()
                    MediaQoLManager.shared.pollAndPushMediaState(force: true)
                }
        }
    }
}
