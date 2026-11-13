import SwiftUI
import WebKit
import UniformTypeIdentifiers

public struct WatchFaceBrowserView: View {
    @StateObject private var storeManager = WatchFaceStoreManager.shared
    @StateObject private var transferManager = FileTransferManager.shared
    @EnvironmentObject var bleViewModel: BLEViewModel

    @State private var selectedMode = 0 // 0: Store Hub, 1: Web Browser & Files
    @State private var showSettingsSheet = false
    @State private var showFileImporter = false
    @State private var webUrlInput = "https://www.google.com/search?q=wear+os+watch+face+apk"
    @State private var interceptedDownload: URL?
    @State private var showDownloadPushAlert = false
    @State private var alertMessage = ""
    @State private var transferError: String?

    private let bookmarks: [(title: String, url: String)] = [
        ("Wear OS Hub", "https://wearos.google.com/watchfaces"),
        ("WatchMaker", "https://getwatchmaker.com"),
        ("Facer", "https://www.facer.io"),
        ("APKMirror", "https://www.apkmirror.com/apk/google-inc/google-watch-faces/")
    ]

    public init() {}

    public var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                VStack(spacing: 0) {
                    // Dual Mode Segmented Picker
                    Picker("Mode", selection: $selectedMode) {
                        Text("Watch Face Hub").tag(0)
                        Text("Browser & Files").tag(1)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(WearBridgeTheme.obsidianBackground)

                    if selectedMode == 0 {
                        // Mode A: Curated & Custom Repository Feed
                        storeHubView
                    } else {
                        // Mode B: Web Browser & File Importer
                        webBrowserAndFilesView
                    }
                }
                .background(WearBridgeTheme.obsidianBackground.ignoresSafeArea())

                // Floating Dynamic Island-Style Transfer Banner (Bottom Overlay)
                if transferManager.isTransferring {
                    floatingDynamicIslandBanner
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .animation(.spring(response: 0.35, dampingFraction: 0.75), value: transferManager.isTransferring)
                }
            }
            .navigationTitle("Watch Faces")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    if selectedMode == 0 {
                        Button {
                            showSettingsSheet = true
                        } label: {
                            Image(systemName: "gearshape")
                                .foregroundColor(WearBridgeTheme.accentTeal)
                        }
                    } else {
                        Button {
                            showFileImporter = true
                        } label: {
                            Image(systemName: "doc.badge.plus")
                                .foregroundColor(WearBridgeTheme.accentTeal)
                        }
                    }
                }
            }
            .sheet(isPresented: $showSettingsSheet) {
                repositorySettingsSheet
            }
            .fileImporter(
                isPresented: $showFileImporter,
                allowedContentTypes: [.item, .data, UTType(filenameExtension: "apk") ?? .data, UTType(filenameExtension: "wff") ?? .data],
                allowsMultipleSelection: false
            ) { result in
                handleFileImportResult(result)
            }
            .alert("Watch Face Error", isPresented: Binding(
                get: { transferError != nil },
                set: { if !$0 { transferError = nil } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(transferError ?? "")
            }
            .alert("Watch Face Downloaded", isPresented: $showDownloadPushAlert, presenting: interceptedDownload) { downloadUrl in
                Button("Push to Watch (0x07)") {
                    sendLocalFileToWatch(fileUrl: downloadUrl)
                }
                Button("Save Only", role: .cancel) {}
            } message: { downloadUrl in
                Text(alertMessage)
            }
        }
    }

    // MARK: - Mode A: Store Hub View
    private var storeHubView: some View {
        ScrollView {
            LazyVStack(spacing: 14) {
                // Header Banner
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        ZStack {
                            Circle()
                                .fill(WearBridgeTheme.accentTeal.opacity(0.18))
                                .frame(width: 36, height: 36)
                            Image(systemName: "clock.arrow.2.circlepath")
                                .font(.system(size: 17, weight: .bold))
                                .foregroundColor(WearBridgeTheme.accentTeal)
                        }

                        VStack(alignment: .leading, spacing: 2) {
                            Text("3rd-Party Watch Faces")
                                .font(.system(size: 16, weight: .bold, design: .rounded))
                                .foregroundColor(WearBridgeTheme.textPrimary)
                            Text("Channel 0x07 Turbo Streaming")
                                .font(.system(size: 10, weight: .heavy, design: .monospaced))
                                .foregroundColor(WearBridgeTheme.accentTeal)
                        }

                        Spacer()

                        if storeManager.isLoadingCatalog {
                            ProgressView()
                                .tint(WearBridgeTheme.accentTeal)
                                .scaleEffect(0.8)
                        }
                    }

                    Text("Stream Watch Face Format (.wff) and .apk packages to your watch over high-throughput windowed BLE.")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundColor(WearBridgeTheme.textSecondary)
                }
                .glassCard(cornerRadius: 18, padding: 14)
                .padding(.horizontal, 16)

                // Catalog Cards
                ForEach(storeManager.catalog) { item in
                    watchFaceCard(item)
                }

                // Local Saved Files Section
                if !storeManager.downloadedFiles.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("SAVED PACKAGES ON IPHONE")
                            .font(.system(size: 10, weight: .heavy, design: .monospaced))
                            .foregroundColor(WearBridgeTheme.textTertiary)
                            .padding(.horizontal, 20)
                            .padding(.top, 8)

                        ForEach(storeManager.downloadedFiles, id: \.self) { fileUrl in
                            savedFileRow(fileUrl)
                        }
                    }
                }
            }
            .padding(.vertical, 10)
            .padding(.bottom, transferManager.isTransferring ? 80 : 20)
        }
        .refreshable {
            storeManager.loadCatalog()
            storeManager.refreshDownloadedFiles()
        }
    }

    private func watchFaceCard(_ item: WatchFaceItem) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 14) {
                // Watch Face Glowing Icon
                ZStack {
                    Circle()
                        .fill(
                            LinearGradient(
                                colors: [WearBridgeTheme.accentTeal, WearBridgeTheme.accentPurple],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 52, height: 52)
                        .shadow(color: WearBridgeTheme.accentTeal.opacity(0.35), radius: 8)

                    Image(systemName: "clock.fill")
                        .font(.system(size: 24, weight: .semibold))
                        .foregroundColor(.white)
                }

                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(item.title)
                            .font(.system(size: 16, weight: .bold, design: .rounded))
                            .foregroundColor(WearBridgeTheme.textPrimary)

                        Spacer()

                        Text(item.formattedSize)
                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(WearBridgeTheme.accentTeal.opacity(0.15))
                            .foregroundColor(WearBridgeTheme.accentTeal)
                            .cornerRadius(5)
                    }

                    Text("By \(item.author) • v\(item.version)")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundColor(WearBridgeTheme.textSecondary)

                    Text(item.description)
                        .font(.system(size: 11))
                        .foregroundColor(WearBridgeTheme.textTertiary)
                        .lineLimit(2)
                        .padding(.top, 2)
                }
            }

            Divider()
                .background(WearBridgeTheme.strokeSubtle)

            HStack {
                HStack(spacing: 5) {
                    Image(systemName: item.fileName.hasSuffix(".wff") ? "doc.plaintext" : "shippingbox.fill")
                        .font(.system(size: 11))
                        .foregroundColor(WearBridgeTheme.accentCyan)
                    Text(item.fileName.hasSuffix(".wff") ? "WFF Package" : "APK Package")
                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                        .foregroundColor(WearBridgeTheme.accentCyan)
                }

                Spacer()

                Button {
                    sendWatchFaceToWatch(item)
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.system(size: 13, weight: .bold))
                        Text("Send to Watch")
                            .font(.system(size: 12, weight: .bold, design: .rounded))
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 7)
                    .background(
                        LinearGradient(
                            colors: [WearBridgeTheme.accentTeal, WearBridgeTheme.accentCyan],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .foregroundColor(WearBridgeTheme.obsidianBackground)
                    .clipShape(Capsule())
                    .shadow(color: WearBridgeTheme.accentTeal.opacity(0.3), radius: 6)
                }
                .disabled(transferManager.isTransferring)
            }
        }
        .glassCard(cornerRadius: 18, padding: 14)
        .padding(.horizontal, 16)
    }

    private func savedFileRow(_ fileUrl: URL) -> some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(WearBridgeTheme.accentTeal.opacity(0.15))
                    .frame(width: 36, height: 36)
                Image(systemName: "doc.fill")
                    .font(.system(size: 15))
                    .foregroundColor(WearBridgeTheme.accentTeal)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(fileUrl.lastPathComponent)
                    .font(.system(size: 12, weight: .semibold, design: .rounded))
                    .foregroundColor(WearBridgeTheme.textPrimary)
                    .lineLimit(1)

                if let size = try? fileUrl.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                    Text(String(format: "%.1f KB", Double(size) / 1024.0))
                        .font(.system(size: 10, weight: .medium, design: .monospaced))
                        .foregroundColor(WearBridgeTheme.textSecondary)
                }
            }

            Spacer()

            Button("Send (0x07)") {
                sendLocalFileToWatch(fileUrl: fileUrl)
            }
            .font(.system(size: 11, weight: .bold, design: .rounded))
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(WearBridgeTheme.accentCyan.opacity(0.18))
            .foregroundColor(WearBridgeTheme.accentCyan)
            .cornerRadius(8)
            .disabled(transferManager.isTransferring)

            Button {
                storeManager.deleteDownloadedFile(url: fileUrl)
            } label: {
                Image(systemName: "trash")
                    .font(.system(size: 13))
                    .foregroundColor(WearBridgeTheme.accentPink)
            }
            .buttonStyle(.plain)
        }
        .glassCard(cornerRadius: 14, padding: 12)
        .padding(.horizontal, 16)
    }

    // MARK: - Mode B: Web Browser & Files View
    private var webBrowserAndFilesView: some View {
        VStack(spacing: 0) {
            // URL Bar & Bookmarks
            VStack(spacing: 8) {
                HStack(spacing: 8) {
                    HStack(spacing: 6) {
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 12))
                            .foregroundColor(WearBridgeTheme.textTertiary)

                        TextField("Enter watch face URL or search", text: $webUrlInput)
                            .font(.system(size: 13))
                            .foregroundColor(WearBridgeTheme.textPrimary)
                            .autocapitalization(.none)
                            .keyboardType(.URL)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .background(WearBridgeTheme.cardSurface)
                    .cornerRadius(10)
                    .overlay(
                        RoundedRectangle(cornerRadius: 10)
                            .stroke(WearBridgeTheme.strokeSubtle, lineWidth: 1)
                    )

                    Button("Go") {
                        loadWebUrl(webUrlInput)
                    }
                    .font(.system(size: 12, weight: .bold))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(WearBridgeTheme.accentTeal)
                    .foregroundColor(WearBridgeTheme.obsidianBackground)
                    .cornerRadius(10)
                }
                .padding(.horizontal, 16)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(bookmarks, id: \.url) { b in
                            Button(b.title) {
                                webUrlInput = b.url
                                loadWebUrl(b.url)
                            }
                            .font(.system(size: 11, weight: .medium))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(WearBridgeTheme.cardSurface)
                            .foregroundColor(WearBridgeTheme.textPrimary)
                            .cornerRadius(8)
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(WearBridgeTheme.strokeSubtle, lineWidth: 1)
                            )
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
            .padding(.vertical, 8)
            .background(WearBridgeTheme.obsidianBackground)

            Divider()
                .background(WearBridgeTheme.strokeSubtle)

            // Embedded Web View with download interception
            WatchFaceWebView(
                urlString: $webUrlInput,
                onInterceptedDownload: { localUrl, sha in
                    self.interceptedDownload = localUrl
                    self.alertMessage = "Downloaded \(localUrl.lastPathComponent)\nSHA-256: \(sha.prefix(12))...\nPush to watch now?"
                    self.showDownloadPushAlert = true
                }
            )

            // Local File Importer Bar
            HStack(spacing: 10) {
                Image(systemName: "folder.fill")
                    .font(.system(size: 14))
                    .foregroundColor(WearBridgeTheme.accentTeal)

                Text("Select custom .apk, .wff, or file")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundColor(WearBridgeTheme.textSecondary)

                Spacer()

                Button("Browse Files") {
                    showFileImporter = true
                }
                .font(.system(size: 11, weight: .bold))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(WearBridgeTheme.cardElevated)
                .foregroundColor(WearBridgeTheme.accentTeal)
                .cornerRadius(8)
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(WearBridgeTheme.accentTeal.opacity(0.3), lineWidth: 1)
                )
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(WearBridgeTheme.cardBackground)
        }
    }

    // MARK: - Floating Dynamic Island-Style Transfer Banner
    private var floatingDynamicIslandBanner: some View {
        let transfer = transferManager.currentTransfer
        let windowText = "Window \(transfer?.currentWindow ?? 0) / \(transfer?.totalWindows ?? 1) (32KB)"
        let speedText = String(format: "%.1f KB/s", transferManager.transferSpeedKBps)
        let hashStatus = transfer?.sha256 != nil ? "SHA-256 Verified" : "SHA-256 Hashing..."
        let progress = transferManager.transferProgress

        return HStack(spacing: 12) {
            // Circular Progress Ring
            ZStack {
                Circle()
                    .stroke(WearBridgeTheme.strokeColor, lineWidth: 3.5)
                    .frame(width: 38, height: 38)

                Circle()
                    .trim(from: 0, to: CGFloat(progress))
                    .stroke(
                        LinearGradient(
                            colors: [WearBridgeTheme.accentTeal, WearBridgeTheme.accentCyan],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ),
                        style: StrokeStyle(lineWidth: 3.5, lineCap: .round)
                    )
                    .frame(width: 38, height: 38)
                    .rotationEffect(.degrees(-90))

                Text(String(format: "%.0f%%", progress * 100))
                    .font(.system(size: 9, weight: .heavy, design: .rounded))
                    .foregroundColor(WearBridgeTheme.textPrimary)
            }

            // Transfer Metadata
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(transfer?.fileName ?? "Channel 0x07 Streaming")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(WearBridgeTheme.textPrimary)
                        .lineLimit(1)

                    Text("0x07")
                        .font(.system(size: 8, weight: .heavy, design: .monospaced))
                        .padding(.horizontal, 4)
                        .padding(.vertical, 1)
                        .background(WearBridgeTheme.accentPurple.opacity(0.2))
                        .foregroundColor(WearBridgeTheme.accentPurple)
                        .cornerRadius(3)
                }

                HStack(spacing: 6) {
                    Text(windowText)
                        .font(.system(size: 10, weight: .semibold, design: .monospaced))
                        .foregroundColor(WearBridgeTheme.accentTeal)

                    Text("•")
                        .foregroundColor(WearBridgeTheme.textTertiary)

                    Text(speedText)
                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                        .foregroundColor(WearBridgeTheme.accentCyan)
                }

                Text(hashStatus)
                    .font(.system(size: 9, weight: .medium))
                    .foregroundColor(WearBridgeTheme.accentGreen)
            }

            Spacer()

            // Cancel Button
            Button {
                transferManager.cancelTransfer()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 24))
                    .foregroundColor(WearBridgeTheme.accentPink)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(
            Capsule()
                .fill(Color(red: 0.05, green: 0.06, blue: 0.09).opacity(0.92))
                .background(
                    Capsule()
                        .fill(.ultraThinMaterial)
                )
        )
        .overlay(
            Capsule()
                .stroke(
                    LinearGradient(
                        colors: [WearBridgeTheme.accentTeal.opacity(0.6), WearBridgeTheme.accentPurple.opacity(0.4), WearBridgeTheme.strokeColor],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: 1.2
                )
        )
        .shadow(color: WearBridgeTheme.accentTeal.opacity(0.25), radius: 16, x: 0, y: 6)
        .shadow(color: Color.black.opacity(0.6), radius: 12, x: 0, y: 4)
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
    }

    // MARK: - Repository Settings Sheet
    private var repositorySettingsSheet: some View {
        NavigationStack {
            Form {
                Section(header: Text("3rd-Party Catalog Repository")) {
                    TextField("Repository Feed URL", text: $storeManager.repositoryUrl)
                        .autocapitalization(.none)
                        .keyboardType(.URL)

                    Button("Reset to Default Showcase Feed") {
                        storeManager.repositoryUrl = WatchFaceStoreManager.defaultRepoUrl
                        storeManager.loadCatalog()
                    }
                }

                Section(header: Text("Manual Refresh")) {
                    Button("Reload Catalog Now") {
                        storeManager.loadCatalog()
                        showSettingsSheet = false
                    }
                }
            }
            .navigationTitle("Catalog Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        showSettingsSheet = false
                        storeManager.loadCatalog()
                    }
                }
            }
        }
    }

    // MARK: - Helper Actions
    private func loadWebUrl(_ input: String) {
        var formatted = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if !formatted.hasPrefix("http://") && !formatted.hasPrefix("https://") {
            if formatted.contains(".") && !formatted.contains(" ") {
                formatted = "https://" + formatted
            } else {
                var search = URLComponents()
                search.scheme = "https"
                search.host = "www.google.com"
                search.path = "/search"
                search.queryItems = [URLQueryItem(name: "q", value: formatted)]
                guard let url = search.url else { return }
                formatted = url.absoluteString
            }
        }
        webUrlInput = formatted
    }

    private func sendWatchFaceToWatch(_ item: WatchFaceItem) {
        storeManager.downloadOrPreparePackage(for: item) { result in
            switch result {
            case .success(let localUrl):
                sendLocalFileToWatch(fileUrl: localUrl)
            case .failure(let error):
                transferError = error.localizedDescription
            }
        }
    }

    private func sendLocalFileToWatch(fileUrl: URL) {
        transferManager.startTransfer(fileUrl: fileUrl, fileType: "WATCH_FACE") { result in
            switch result {
            case .success(let path):
                loggerInfo("Watch face delivered and verified on watch: \(path)")
            case .failure(let error):
                if transferManager.currentTransfer?.status != .cancelled {
                    transferError = error.localizedDescription
                }
            }
        }
    }

    private func handleFileImportResult(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            guard let selectedUrl = urls.first else { return }
            let accessed = selectedUrl.startAccessingSecurityScopedResource()
            defer { if accessed { selectedUrl.stopAccessingSecurityScopedResource() } }

            if let saved = storeManager.saveImportedFile(sourceUrl: selectedUrl) {
                sendLocalFileToWatch(fileUrl: saved)
            } else {
                transferError = "Could not copy the selected watch face file."
            }
        case .failure(let error):
            transferError = error.localizedDescription
        }
    }

    private func loggerInfo(_ msg: String) {
        print("[WatchFaceBrowserView] \(msg)")
    }

}

// MARK: - Embedded Web View with Download Interception
struct WatchFaceWebView: UIViewRepresentable {
    @Binding var urlString: String
    var onInterceptedDownload: (URL, String) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        context.coordinator.webView = webView

        if let url = URL(string: urlString) {
            webView.load(URLRequest(url: url))
        }
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        if let current = uiView.url?.absoluteString, current != urlString, let newUrl = URL(string: urlString) {
            uiView.load(URLRequest(url: newUrl))
        }
    }

    class Coordinator: NSObject, WKNavigationDelegate, WKDownloadDelegate {
        var parent: WatchFaceWebView
        weak var webView: WKWebView?

        init(_ parent: WatchFaceWebView) {
            self.parent = parent
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, preferences: WKWebpagePreferences, decisionHandler: @escaping (WKNavigationActionPolicy, WKWebpagePreferences) -> Void) {
            if let url = navigationAction.request.url {
                let pathExt = url.pathExtension.lowercased()
                if pathExt == "apk" || pathExt == "wff" || pathExt == "watch" {
                    decisionHandler(.download, preferences)
                    return
                }
            }
            decisionHandler(.allow, preferences)
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
            if let response = navigationResponse.response as? HTTPURLResponse,
               let mime = response.mimeType?.lowercased() {
                if mime.contains("application/vnd.android.package-archive") ||
                   response.url?.pathExtension.lowercased() == "apk" ||
                   response.url?.pathExtension.lowercased() == "wff" {
                    decisionHandler(.download)
                    return
                }
            }
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
            download.delegate = self
        }

        func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
            download.delegate = self
        }

        func download(_ download: WKDownload, decideDestinationUsing response: URLResponse, suggestedFilename: String, completionHandler: @escaping (URL?) -> Void) {
            let destination = WatchFaceStoreManager.shared.watchFacesDirectory.appendingPathComponent(suggestedFilename)
            if FileManager.default.fileExists(atPath: destination.path) {
                try? FileManager.default.removeItem(at: destination)
            }
            completionHandler(destination)
        }

        func downloadDidFinish(_ download: WKDownload) {
            WatchFaceStoreManager.shared.refreshDownloadedFiles()
            if let latest = WatchFaceStoreManager.shared.downloadedFiles.first {
                let sha = FileTransferManager.computeSha256(url: latest) ?? ""
                DispatchQueue.main.async {
                    self.parent.onInterceptedDownload(latest, sha)
                }
            }
        }

        func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
            print("[WKDownload ERROR] \(error.localizedDescription)")
        }
    }
}
