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

    private let bookmarks: [(title: String, url: String)] = [
        ("Wear OS Hub", "https://wearos.google.com/watchfaces"),
        ("WatchMaker", "https://getwatchmaker.com"),
        ("Facer", "https://www.facer.io"),
        ("APKMirror", "https://www.apkmirror.com/apk/google-inc/google-watch-faces/")
    ]

    public init() {}

    public var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Dual Mode Picker
                Picker("Mode", selection: $selectedMode) {
                    Text("Watch Face Hub").tag(0)
                    Text("Browser & Files").tag(1)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                .padding(.vertical, 8)

                if selectedMode == 0 {
                    // Mode A: Curated & Custom Repository Feed
                    storeHubView
                } else {
                    // Mode B: Web Browser & File Importer
                    webBrowserAndFilesView
                }

                // Active File Transfer Progress Bar
                if transferManager.isTransferring {
                    activeTransferBanner
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
                        }
                    } else {
                        Button {
                            showFileImporter = true
                        } label: {
                            Image(systemName: "doc.badge.plus")
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
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Image(systemName: "clock.arrow.2.circlepath")
                            .font(.title2)
                            .foregroundColor(.blue)
                        Text("3rd-Party Watch Faces")
                            .font(.headline)
                        Spacer()
                        if storeManager.isLoadingCatalog {
                            ProgressView()
                                .scaleEffect(0.8)
                        }
                    }
                    Text("Stream Watch Face Format (.wff) and .apk packages to your watch over Channel 0x07.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .padding()
                .background(Color(.secondarySystemBackground))
                .cornerRadius(12)
                .padding(.horizontal)

                // Catalog Cards
                ForEach(storeManager.catalog) { item in
                    watchFaceCard(item)
                }

                // Local Saved Files Section
                if !storeManager.downloadedFiles.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("SAVED ON IPHONE")
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .padding(.horizontal)
                            .padding(.top, 8)

                        ForEach(storeManager.downloadedFiles, id: \.self) { fileUrl in
                            savedFileRow(fileUrl)
                        }
                    }
                }
            }
            .padding(.vertical, 8)
        }
        .refreshable {
            storeManager.loadCatalog()
            storeManager.refreshDownloadedFiles()
        }
    }

    private func watchFaceCard(_ item: WatchFaceItem) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 12) {
                // Watch Face Icon
                ZStack {
                    Circle()
                        .fill(LinearGradient(colors: [.blue.opacity(0.8), .indigo], startPoint: .topLeading, endPoint: .bottomTrailing))
                        .frame(width: 52, height: 52)
                    Image(systemName: "clock.fill")
                        .font(.title3)
                        .foregroundColor(.white)
                }

                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(item.title)
                            .font(.system(.body, design: .rounded))
                            .bold()
                        Spacer()
                        Text(item.formattedSize)
                            .font(.caption2)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.blue.opacity(0.15))
                            .foregroundColor(.blue)
                            .cornerRadius(4)
                    }

                    Text("By \(item.author) • v\(item.version)")
                        .font(.caption)
                        .foregroundColor(.secondary)

                    Text(item.description)
                        .font(.caption2)
                        .foregroundColor(.primary)
                        .lineLimit(2)
                        .padding(.top, 2)
                }
            }

            Divider()

            HStack {
                Label(item.fileName.hasSuffix(".wff") ? "WFF Package" : "APK Installer", systemImage: "shippingbox")
                    .font(.caption2)
                    .foregroundColor(.secondary)

                Spacer()

                Button {
                    sendWatchFaceToWatch(item)
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "arrow.up.circle.fill")
                        Text("Send to Watch")
                    }
                    .font(.footnote)
                    .bold()
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color.blue)
                    .foregroundColor(.white)
                    .cornerRadius(8)
                }
                .disabled(transferManager.isTransferring)
            }
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .cornerRadius(12)
        .padding(.horizontal)
    }

    private func savedFileRow(_ fileUrl: URL) -> some View {
        HStack {
            Image(systemName: "doc.fill")
                .foregroundColor(.blue)
            VStack(alignment: .leading, spacing: 2) {
                Text(fileUrl.lastPathComponent)
                    .font(.footnote)
                    .lineLimit(1)
                if let size = try? fileUrl.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                    Text(String(format: "%.1f KB", Double(size) / 1024.0))
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            }
            Spacer()

            Button("Send (0x07)") {
                sendLocalFileToWatch(fileUrl: fileUrl)
            }
            .font(.caption2)
            .buttonStyle(.borderedProminent)
            .disabled(transferManager.isTransferring)

            Button {
                storeManager.deleteDownloadedFile(url: fileUrl)
            } label: {
                Image(systemName: "trash")
                    .foregroundColor(.red)
            }
            .buttonStyle(.borderless)
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .cornerRadius(10)
        .padding(.horizontal)
    }

    // MARK: - Mode B: Web Browser & Files View
    private var webBrowserAndFilesView: some View {
        VStack(spacing: 0) {
            // URL Bar & Bookmarks
            VStack(spacing: 6) {
                HStack(spacing: 6) {
                    TextField("Enter watch face URL or search", text: $webUrlInput)
                        .textFieldStyle(.roundedBorder)
                        .autocapitalization(.none)
                        .keyboardType(.URL)

                    Button("Go") {
                        loadWebUrl(webUrlInput)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                }
                .padding(.horizontal)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(bookmarks, id: \.url) { b in
                            Button(b.title) {
                                webUrlInput = b.url
                                loadWebUrl(b.url)
                            }
                            .font(.caption2)
                            .buttonStyle(.bordered)
                        }
                    }
                    .padding(.horizontal)
                }
            }
            .padding(.vertical, 6)
            .background(Color(.systemBackground))

            Divider()

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
            HStack {
                Image(systemName: "folder")
                    .foregroundColor(.blue)
                Text("Select custom .apk, .wff, or file")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Spacer()
                Button("Browse Files") {
                    showFileImporter = true
                }
                .font(.caption)
                .buttonStyle(.bordered)
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
            .background(Color(.secondarySystemBackground))
        }
    }

    // MARK: - Active Transfer Banner
    private var activeTransferBanner: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Label("Channel 0x07 Streaming", systemImage: "antenna.radiowaves.left.and.right")
                    .font(.caption)
                    .bold()
                Spacer()
                Text(String(format: "%.1f KB/s", transferManager.transferSpeedKBps))
                    .font(.caption2)
                    .foregroundColor(.secondary)

                Button("Cancel", role: .destructive) {
                    transferManager.cancelTransfer()
                }
                .font(.caption2)
            }

            ProgressView(value: transferManager.transferProgress)
                .progressViewStyle(.linear)

            HStack {
                Text(transferManager.transferStatusText)
                    .font(.caption2)
                    .foregroundColor(.secondary)
                Spacer()
                Text(String(format: "%.0f%%", transferManager.transferProgress * 100))
                    .font(.caption2)
                    .bold()
            }
        }
        .padding()
        .background(Color(.tertiarySystemBackground))
        .cornerRadius(12)
        .padding(.horizontal)
        .padding(.bottom, 6)
        .shadow(radius: 2)
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
                formatted = "https://www.google.com/search?q=" + formatted.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)!
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
                loggerError("Failed to prepare watch face package: \(error.localizedDescription)")
            }
        }
    }

    private func sendLocalFileToWatch(fileUrl: URL) {
        transferManager.startTransfer(fileUrl: fileUrl, fileType: "WATCH_FACE") { result in
            switch result {
            case .success(let path):
                loggerInfo("Watch face delivered and verified on watch: \(path)")
            case .failure(let error):
                loggerError("File transfer failed: \(error.localizedDescription)")
            }
        }
    }

    private func handleFileImportResult(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            guard let selectedUrl = urls.first else { return }
            guard selectedUrl.startAccessingSecurityScopedResource() else { return }
            defer { selectedUrl.stopAccessingSecurityScopedResource() }

            if let saved = storeManager.saveImportedFile(sourceUrl: selectedUrl) {
                sendLocalFileToWatch(fileUrl: saved)
            }
        case .failure(let error):
            loggerError("File import error: \(error.localizedDescription)")
        }
    }

    private func loggerInfo(_ msg: String) {
        print("[WatchFaceBrowserView] \(msg)")
    }

    private func loggerError(_ msg: String) {
        print("[WatchFaceBrowserView ERROR] \(msg)")
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
                    // Intercept download
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

        // WKDownloadDelegate
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
            // Locate saved file in watchFacesDirectory
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
