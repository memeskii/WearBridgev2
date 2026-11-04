<p align="center">
  <img src="master-docs/AppleToWearOS/icon-light.png" width="130" alt="WearBridge Logo" />
</p>

# WearBridge v2.0.0

<p align="center">
  <strong>The Ultimate Standalone iOS-to-WearOS Companion Ecosystem</strong><br>
  <em>Direct BLE Communication • Apple Notification Center Service (ANCS) • Apple Media Service (AMS) • 7-Channel Multiplexing Engine</em>
</p>

<p align="center">
  <a href="https://github.com/wearos-ancs/WearBridge/releases"><img src="https://img.shields.io/badge/Release-v2.0.0--Build200-blue.svg?style=flat-square" alt="Version" /></a>
  <a href="https://developer.apple.com/ios/"><img src="https://img.shields.io/badge/iOS-16.0%2B-black.svg?style=flat-square&logo=apple" alt="iOS 16+" /></a>
  <a href="https://developer.android.com/wear"><img src="https://img.shields.io/badge/Wear%20OS-4.0%2B%20(API%2033%2B)-green.svg?style=flat-square&logo=android" alt="Wear OS 4+" /></a>
  <a href="https://swift.org"><img src="https://img.shields.io/badge/Swift-5.9-F05138.svg?style=flat-square&logo=swift" alt="Swift 5.9" /></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF.svg?style=flat-square&logo=kotlin" alt="Kotlin 2.0" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square" alt="License: MIT" /></a>
</p>

---

## 🌟 Overview

**WearBridge** is an ultra-low-latency, zero-cloud bridge that liberates **Wear OS smartwatches** (Google Pixel Watch 1/2/3, Samsung Galaxy Watch 4/5/6/7/Ultra, OnePlus Watch 2, Xiaomi Watch 2) to pair and operate directly with an **Apple iPhone**.

Unlike fragile cloud webhooks or bridge apps requiring a secondary Android phone, WearBridge functions 100% locally over **Bluetooth Low Energy (BLE)** using Apple's native **Apple Notification Center Service (ANCS)**, **Apple Media Service (AMS)**, and our high-throughput **4-Byte Binary Framing Packet Multiplexer (Channels 0x01–0x07)**.

Your messages, biometric telemetry, contacts, and media controls remain strictly on your devices — private, secure, and instantaneous.

---

## 🚀 Key Feature Matrix

### 1. Apple Notification Center Service (ANCS) & Media Controls (AMS)
- **Direct GATT Central/Client**: Wear OS connects directly to iOS ANCS (`7905F431-B5CE-4E99-A40F-4B1E122D00D0`), pulling real-time notification attributes with zero cloud relays.
- **Apple Media Service (AMS)**: Control track playback, volume up/down, seek, and browse now-playing information on Spotify, Apple Music, and YouTube directly from the watch crown and playback card.
- **Album Artwork Thumbnails**: Out-of-band Channel 0x05 automatically streams 96x96 PNG cover art with a 350ms debounce for rapid track skipping.
- **Haptic Signatures**: Categorized vibration cadences (50ms sharp pulses for messaging, double-tap alerts for urgent system notices).

### 2. Full-Screen Incoming Call Overlay & Telecom Integration
- **Android Telecom ConnectionService**: System-priority incoming call screen that overlays watch lockscreens and ambient displays during cellular, FaceTime, and WhatsApp audio calls.
- **Audio Handover & Call Actions**: Answer directly over Bluetooth, route to connected AirPods/iPhone earpiece, or decline with automatic canned SMS/WhatsApp replies.

### 3. Continuous Bi-Directional Health Telemetry (Channel 0x04)
- **Apple HealthKit Sync**: Streams live steps, continuous Heart Rate (BPM), Heart Rate Variability (HRV RMSSD), and active calories from Wear OS Health Services directly into Apple Health Store.
- **Sleep & SpO2 Diagnostics**: Records multi-stage sleep architecture (Deep, REM, Light, Awake), blood oxygen saturation (SpO2), and stress gauges visualized with native `SwiftCharts` in the iOS companion dashboard.

### 4. Two-Way Contact Synchronization & Local Caller ID (Channel 0x02)
- **High-Speed Contacts Ingestion**: Syncs iPhone `CNContactStore` entries over Channel 0x02 into the watch's Room SQLite database (`synced_contacts`).
- **E.164 Normalization Engine**: Resolves national, international, and Eastern Arabic numerals (`+٩٦٦...`) so incoming notifications and callers display verified address book names and photos instantly.

### 5. Live Open-Meteo Weather Synchronization (Channel 0x03)
- **GPS-Based Atmospheric Push**: Companion iOS service polls Open-Meteo GPS forecasts every 30 minutes and pushes high-precision hourly temperature, weather condition codes, and high/low ranges over Channel 0x03.
- **Offline Watch Caching**: Persistent offline display ensures current conditions and rain probabilities are always visible on ambient watch faces.

### 6. Persistent Wear OS Message Inbox & Multi-Mode Outbound Replies (Channel 0x06)
- **Room Message Inbox**: Intercepts and archives incoming conversations from iMessage, WhatsApp, Telegram, Facebook Messenger, Instagram Direct, and TikTok into indexed chat threads (`threadKey`).
- **On-Screen Composition**: Reply via Voice Dictation (Speech-to-Text), customizable Quick Reply chips, or on-screen QWERTY/T9 input.
- **Multi-Mode iOS Reply Relay (`ReplyRelayManager`)**:
  - **Webhook Mode**: Silent HTTP POST for relay servers, Matrix, or personal automations.
  - **Shortcuts Mode**: Fires custom Apple Shortcuts automations with dynamic arguments.
  - **Deep-Link Mode**: Automatically launches `whatsapp://send` or `sms://` with pre-filled drafts.
- **Replay Suppression**: Suppresses duplicate notification replays from iOS Notification Center upon BLE reconnection.

### 7. High-Throughput Binary File Transfer & Watch Face Engine (Channel 0x07)
- **Windowed Flow Control**: Streams `.apk` and Watch Face Format (`.wff`) binaries in 32KB windows with CoreBluetooth backpressure monitoring (`peripheralManagerIsReady`).
- **Disk-Buffered Streaming**: Direct-to-disk `FileOutputStream` reassembly prevents memory exhaustion on RAM-constrained smartwatches.
- **Cryptographic Integrity**: Full IEEE 802.3 CRC32 chunk validation and SHA-256 end-to-end payload verification before invoking Android's `PackageInstaller` Session API.
- **In-App Watch Face Store**: iOS companion app provides a curated watch face catalog and custom file importer.

### 8. Native App Brand Registry & Dynamic Iconography
- **Centralized Brand System**: 25+ pixel-perfect custom Android Vector Drawables (`ic_app_*.xml`) paired with official SF Symbols and brand hex colors.
- **Regional Bundle Mapping**: First-class support for TikTok (`com.zhiliaoapp.musically`, `com.ss.iphone.ugc.Ame`, `com.bd.iphone.tiktok`), Meta (WhatsApp, Instagram, Threads, Messenger, Facebook), Discord, Snapchat, X (Twitter), Spotify, YouTube, and native iOS system apps.

---

## 🏗 Architecture & Protocol Specification

### BLE Network Topology

```mermaid
graph LR
    subgraph iPhone [iPhone (iOS 16+)]
        ANCS[Apple Notification Center Service]
        AMS[Apple Media Service]
        CompanionPeripheral[WearBridge Peripheral Service\nUUID: A0E3D5B1-4C6F-4E8A-9B2D-7F1E3A5C8D0B]
        MultiplexerTX[BLE Packet Multiplexer TX]
        HealthKit[HealthKit Engine]
        WeatherSvc[Weather Sync Service]
        RelayMgr[Reply Relay Manager]
        FileTransferTX[File Transfer Engine]
    end

    subgraph WearOS [Wear OS Smartwatch (Wear OS 4+)]
        BLECentral[BLE Central / GATT Client]
        AncsParser[ANCS Protocol Parser]
        AmsManager[AMS Media Manager]
        MultiplexerRX[BLE Packet Multiplexer RX]
        RoomDB[(Room SQLite DB\nContacts & Messages)]
        TelecomService[Android Telecom Service]
        HealthService[Wear Health Services]
        FileTransferRX[File Transfer & APK Installer]
    end

    ANCS -.->|GATT Notifications| AncsParser
    AMS -.->|Track Updates & Controls| AmsManager
    CompanionPeripheral <===>|Multiplexed BLE Packets\nChannels 0x01-0x07| BLECentral

    HealthKit <-->|0x04 Health Telemetry| HealthService
    WeatherSvc -->|0x03 Weather JSON| MultiplexerRX
    MultiplexerRX -->|0x02 Contacts JSON| RoomDB
    RelayMgr <-->|0x06 Message Replies| RoomDB
    FileTransferTX -->|0x07 Binary Stream| FileTransferRX
```

### Packet Framing Format (Channels 0x01–0x07)

All multi-channel communication utilizes a 4-byte framing protocol with automatic MTU-aware chunking and trailing IEEE 802.3 CRC32 verification:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Channel ID   |     Flags     |       Sequence Number         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|                   Payload Data (Up to MTU - 4)                |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                   Trailing CRC32 (Only if EOT flag set)       |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

- **Byte 0: Channel ID** (0x01 to 0x07)
- **Byte 1: Flags**
  - `0x01` (`Bit 0`): Start of Transfer (SOT)
  - `0x02` (`Bit 1`): End of Transfer (EOT)
  - `0x04` (`Bit 2`): Acknowledgment (ACK)
  - `0x08` (`Bit 3`): CRC / Checksum Error
- **Bytes 2–3: Sequence Number** (16-bit Big-Endian incrementing counter)
- **Payload**: Up to negotiated BLE ATT MTU - 4 bytes
- **Trailing 4 Bytes**: IEEE 802.3 CRC32 (appended exclusively to the `EOT` packet)

### Channel Allocation Table

| Channel | Identifier | Direction | Payload Type | Description |
|:-------:|:-----------|:---------:|:-------------|:------------|
| `0x01` | **Ping / Control** | Bidirectional | Binary (4B) | Heartbeat, link latency measurement, and MTU negotiation |
| `0x02` | **Contacts Sync** | iOS -> Watch | JSON / vCard | Incremental address book sync for local caller ID & names |
| `0x03` | **Weather Sync** | iOS -> Watch | JSON | 30-min Open-Meteo GPS weather conditions and hourly forecasts |
| `0x04` | **Health Telemetry** | Bidirectional | JSON | Real-time steps, HR, HRV, SpO2, stress, sleep stages, & calories |
| `0x05` | **Media QoL** | iOS -> Watch | Binary / JSON | 96x96 PNG album art thumbnails, track duration, & output route |
| `0x06` | **Message Replies** | Bidirectional | JSON | Outbound watch replies & synthetic inbox diagnostics |
| `0x07` | **Binary File Transfer**| iOS -> Watch | Binary Stream| High-speed windowed stream for `.apk` and `.wff` watch faces |

---

## 📱 Hardware & Compatibility Matrix

| Hardware Device | Operating System | Support Level | Notes |
|:----------------|:-----------------|:-------------:|:------|
| **Google Pixel Watch 1 / 2 / 3** | Wear OS 4 / 5 (Android 13 / 14) | ✅ Tier 1 (Reference) | Rotary crown scrolling, full haptics, health services |
| **Samsung Galaxy Watch 4 / 5 / 6 / 7 / Ultra** | One UI Watch 5 / 6 (Wear OS 4 / 5) | ✅ Tier 1 (Reference) | Touch bezel navigation, Telecom call overlay verified |
| **OnePlus Watch 2 / 2R** | Wear OS 4 | ✅ Verified | Dual-engine architecture tested |
| **Xiaomi Watch 2 / Pro** | Wear OS 4 | ✅ Verified | Full ANCS + AMS compatibility |
| **Apple iPhone 12 through 17 Pro Max** | iOS 16.0 – iOS 26.0+ | ✅ Supported | Native ANCS/AMS and companion app integration |

---

## 🛠 Step-by-Step Installation & Setup

### Part 1: Wear OS Watch Sideloading (Wireless ADB)

1. **Enable Developer Options on Watch**:
   - Go to **Settings > System > About > Versions**.
   - Tap **Build Number** 7 times until you see `"You are now a developer!"`.
2. **Enable Wireless Debugging**:
   - Go to **Settings > Developer Options**.
   - Enable **ADB Debugging** and **Wireless Debugging**.
   - Note the **IP address and port** displayed (e.g., `192.168.1.50:5555`).
3. **Install the APK via ADB**:
   ```bash
   # Connect to your watch over Wi-Fi
   adb connect 192.168.1.50:5555

   # Install the WearBridge release APK
   adb install -r WearBridge-WearOS-v2.0.0-debug.apk
   ```
4. **Grant Required Permissions**:
   ```bash
   adb shell pm grant com.wearos.ancsbridge android.permission.POST_NOTIFICATIONS
   adb shell pm grant com.wearos.ancsbridge android.permission.BLUETOOTH_CONNECT
   adb shell pm grant com.wearos.ancsbridge android.permission.BLUETOOTH_SCAN
   adb shell pm grant com.wearos.ancsbridge android.permission.BODY_SENSORS
   adb shell pm grant com.wearos.ancsbridge android.permission.READ_CONTACTS
   ```

### Part 2: iOS Companion App Sideloading

The iOS companion app enhances WearBridge with contacts sync, live weather push, Apple Health integration, and the watch face store browser:

#### Option A: SideStore / AltStore (Recommended, No Mac Required)
1. Download `AncsBridge-unsigned.ipa` from the [Latest Releases](https://github.com/wearos-ancs/WearBridge/releases).
2. Open **SideStore** or **AltStore** on your iPhone.
3. Tap **My Apps > (+) > Select AncsBridge-unsigned.ipa** to sign and install.

#### Option B: Xcode Direct Build
1. Clone the repository:
   ```bash
   git clone https://github.com/wearos-ancs/WearBridge.git
   cd WearBridge/ios-app
   ```
2. Open `AncsBridge.xcodeproj` in **Xcode 15 or 16**.
3. Select your personal development team in **Signing & Capabilities**.
4. Connect your iPhone and click **Run** (Command + R).

---

## 🔗 Initial Pairing & Bluetooth Bonding

1. Open **WearBridge** on your Wear OS watch.
2. Tap **Scan for iPhone**.
3. Select your iPhone from the discovered BLE devices list.
4. **Accept Bluetooth Pairing Request**:
   - An iOS system prompt will appear on your iPhone asking to pair with your watch. Tap **Pair**.
5. **Allow Notification Sharing**:
   - An iOS prompt will ask: *"Allow Pixel Watch / Galaxy Watch to display iPhone notifications?"*. Tap **Allow**.
6. Open the **WearBridge iOS Companion App** to activate automatic contact sync, weather updates, and health telemetry.

---

## 💻 Building from Source

### Wear OS Client
```bash
cd wearos-app
./gradlew testDebugUnitTest  # Execute full test suite
./gradlew assembleDebug      # Produce debug APK at app/build/outputs/apk/debug/
```

### iOS Companion App
```bash
cd ios-app
# Optional: regenerate project using XcodeGen
xcodegen generate

# Build command line
xcodebuild -project AncsBridge.xcodeproj -scheme AncsBridge -configuration Debug -sdk iphoneos build
```

---

## 🔒 Privacy, Security & Data Governance

- **100% On-Device & Zero Cloud Telemetry**: WearBridge contains no analytics trackers, advertising SDKs, or cloud relays.
- **Hardware Encryption**: All communication is secured via Bluetooth Low Energy LE Secure Connections with AES-128 cryptographic bonding.
- **Local Storage Integrity**: Contacts and messages are persisted exclusively in a protected local Room SQLite database with scoped app permissions.

---

## 📄 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.
