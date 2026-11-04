# Changelog

All notable changes to the WearBridge ecosystem (iOS Companion & Wear OS Watch Client) are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [2.0.0] - 2026-11-04

### Major Overhaul: The Complete iOS-to-WearOS Bridge Ecosystem

#### Added
- **Channel 0x01: Ping & Multiplexer Control Protocol**
  - Custom 4-byte framing protocol header (`[ChannelID: 1B][Flags: 1B][SeqNo: 2B (BE)][Payload: N bytes][CRC32: 4B]`) with MTU-aware chunking and reassembly.
  - Zero-overhead round-trip ping mechanism with RSSI health monitoring and automatic MTU negotiation up to 512 bytes.

- **Channel 0x02: Two-Way Contact Synchronization & Caller ID Engine**
  - Auto-syncs iPhone Contacts via `ContactsSyncManager.swift` (`CNContactStore`) to Wear OS Room database (`ContactEntity`, `ContactDao`, `ContactRepository`).
  - E.164 phone number normalization supporting national, international, and Eastern Arabic numerals (`+٩٦٦...`).
  - Instant local caller ID lookup for incoming calls and messaging threads.

- **Channel 0x03: Live Open-Meteo Weather Synchronization**
  - GPS-based weather fetching on iOS via `WeatherSyncService.swift` with Open-Meteo API integration.
  - Periodic 30-minute updates pushed over BLE to Wear OS `WeatherManager.kt` and `WeatherCard.kt`.
  - Hourly forecast caching, dynamic weather icons, temperature highs/lows, and offline fallback.

- **Channel 0x04: Continuous Bi-Directional Health Telemetry**
  - `HealthKitManager.swift` and `HealthTelemetryManager.swift` bridge HealthKit to Wear OS.
  - Wear OS `HealthSyncManager.kt` captures real-time steps, heart rate (BPM), HRV (RMSSD), SpO2 %, stress scores (0-100), active/total calories, and multi-stage sleep breakdowns (Deep, REM, Light, Awake).
  - Bi-directional sync writes Wear OS biometric logs directly into Apple Health Store.

- **Channel 0x05: Media Quality of Life (QoL) & Artwork Delivery**
  - Integrated Apple Media Service (AMS) GATT client in `AmsMediaManager.kt` subscribing to Remote Command, Entity Update, and Entity Attribute characteristics.
  - Out-of-band Channel 0x05 transfers high-quality 96x96 PNG album artwork thumbnails with automatic debounce (350ms) to avoid redundant requests during fast track skipping.
  - Real-time track duration, playback progress, volume sync, and output route reporting.

- **Channel 0x06: Persistent Wear OS Message Inbox & Multi-Mode Outbound Replies**
  - Room database inbox (`MessageEntity`, `MessageDao`, `MessageSyncManager.kt`) indexing conversations by deterministic `threadKey`.
  - ANCS interception for iMessage, WhatsApp, WhatsApp Business, Telegram, Messenger, Instagram Direct, and TikTok.
  - Wear OS message compose interface with voice dictation, quick replies, and on-screen keyboard.
  - Multi-mode iOS Outbound Reply Relay (`ReplyRelayManager.swift`):
    - **Webhook Mode**: Silent HTTP POST to personal automations / home server.
    - **Apple Shortcuts Mode**: Triggers native iOS Shortcuts with parameter payloads.
    - **Deep-Link Mode**: Instant scheme launching (`whatsapp://send`, `sms://`).
  - Duplicate notification replay suppression across BLE reconnects (60-second window / ANCS UID deduplication).
  - Explicit Room `MIGRATION_1_2` safeguarding contact databases during app upgrades.

- **Channel 0x07: High-Throughput Binary File Transfer & Watch Face Engine**
  - Multi-stage binary streaming engine with windowed flow control (`FILE_START`, `FILE_WINDOW` 32KB windows, `FILE_WINDOW_ACK`, `FILE_END`).
  - Direct file streaming to disk cache via buffered `FileOutputStream` (avoiding RAM exhaustion).
  - SHA-256 and IEEE 802.3 CRC32 end-to-end cryptographic checksum verification.
  - Wear OS Watch Face installer for `.apk` (via Android `PackageInstaller` Session API) and Watch Face Format `.wff`.
  - iOS 3rd-Party Watch Face Hub & Store Browser with built-in catalog, WebView explorer, and direct file import.

- **Native App Brand Registry & Dynamic Iconography**
  - Centralized `AppBrandRegistry` across Wear OS (`AppBrandRegistry.kt`) and iOS (`AppBrandRegistry.swift`).
  - 25+ pixel-crisp custom Android Vector Drawables (`ic_app_*.xml`) and matching Apple SF Symbols.
  - Official brand color schemes and display name formatting.
  - Complete regional bundle ID support for TikTok (`com.zhiliaoapp.musically`, `com.ss.iphone.ugc.Ame`, `com.bd.iphone.tiktok`), Meta suite (WhatsApp, Instagram, Threads, Messenger, Facebook), Discord, Snapchat, X (Twitter), Spotify, YouTube, and iOS system apps.

- **Complete Modern UI/UX Overhaul**
  - **iOS Companion App (SwiftUI)**: Dark-mode-first glassmorphic design across 4 tabs (`DashboardView`, `WatchFaceHubView`, `DeviceSyncView`, `DiagnosticsView`) with live `SwiftCharts` for sleep, SpO2, stress, and calories.
  - **Wear OS Client (Horologist & Jetpack Compose)**: Material Wear Compose (`AppScaffold`, `ScreenScaffold`, `ScalingLazyColumn`) optimized for round displays, Rotary input (crown scrolling), rotary snapping, and high-contrast ambient modes.

- **Comprehensive Hardware Diagnostics & Test Harness**
  - Full diagnostic suite in `DiagnosticsView.swift` testing round-trip Ping, mock notifications, mock calls, weather push, telemetry pull, media QoL push, mock message injection, and file transfers.
  - Live packet log monitoring TX/RX directions, channel IDs, sequence numbers, and flag states in real time.

---

## [1.2.0] - 2026-10-15

### Added
- Incoming call overlay integrated with Android Telecom `ConnectionService`.
- Basic quick reply support over BLE custom characteristic.
- Preliminary watch battery level reporting over GATT.

---

## [1.0.0] - 2026-09-01

### Added
- Initial public release of WearBridge.
- Core ANCS notification mirroring over Bluetooth Low Energy.
- Local GATT client connection and automatic notification attribute fetching.

