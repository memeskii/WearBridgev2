import XCTest
@testable import AncsBridge

final class AncsBridgeTests: XCTestCase {

    // MARK: - CRC32 Tests
    func testCrc32StandardVector() {
        let testString = "123456789"
        let data = testString.data(using: .utf8)!
        let crc = CRC32.checksum(data: data)
        // Standard IEEE 802.3 CRC32 for "123456789" is 0xCBF43926
        XCTAssertEqual(crc, 0xCBF43926, "CRC32 checksum does not match standard test vector")
    }

    func testCrc32EmptyData() {
        let data = Data()
        let crc = CRC32.checksum(data: data)
        XCTAssertEqual(crc, 0x00000000)
    }

    // MARK: - Multiplexer Framing Tests
    func testSingleChunkFraming() {
        let multiplexer = BLEPacketMultiplexer()
        let payload = "Hello, Watch!".data(using: .utf8)!
        let chunks = multiplexer.createChunks(channel: .messageReply, payload: payload, maxChunkSize: 100)

        XCTAssertEqual(chunks.count, 1)
        let chunk = chunks[0]

        // Header verification
        XCTAssertEqual(chunk[0], ChannelID.messageReply.rawValue)
        let flags = PacketFlags(rawValue: chunk[1])
        XCTAssertTrue(flags.contains(.startOfTransfer))
        XCTAssertTrue(flags.contains(.endOfTransfer))
        XCTAssertFalse(flags.contains(.ack))
        XCTAssertFalse(flags.contains(.crcError))

        // Sequence Number 0
        let seq = (UInt16(chunk[2]) << 8) | UInt16(chunk[3])
        XCTAssertEqual(seq, 0)

        // Payload + 4 bytes CRC
        XCTAssertEqual(chunk.count, 4 + payload.count + 4)
    }

    func testMultiChunkFraming() {
        let multiplexer = BLEPacketMultiplexer()
        let largePayload = Data(repeating: 0xAB, count: 100)
        // Max chunk size 30 bytes -> each chunk payload slice = 30 - 4 = 26 bytes
        let chunks = multiplexer.createChunks(channel: .binaryFile, payload: largePayload, maxChunkSize: 30)

        XCTAssertGreaterThan(chunks.count, 1)

        // First chunk must have SOT and not EOT
        let firstFlags = PacketFlags(rawValue: chunks[0][1])
        XCTAssertTrue(firstFlags.contains(.startOfTransfer))
        XCTAssertFalse(firstFlags.contains(.endOfTransfer))

        // Last chunk must have EOT and not SOT
        let lastFlags = PacketFlags(rawValue: chunks.last![1])
        XCTAssertFalse(lastFlags.contains(.startOfTransfer))
        XCTAssertTrue(lastFlags.contains(.endOfTransfer))

        // Sequence numbers must be strictly sequential
        for (index, chunk) in chunks.enumerated() {
            let seq = (UInt16(chunk[2]) << 8) | UInt16(chunk[3])
            XCTAssertEqual(seq, UInt16(index))
        }
    }

    // MARK: - Reassembly & CRC32 Verification Tests
    func testReassemblySuccess() {
        let multiplexer = BLEPacketMultiplexer()
        let originalPayload = "WearBridge Multiplexer Test Payload for Reassembly Verification".data(using: .utf8)!

        var receivedChannel: ChannelID?
        var receivedPayload: Data?

        multiplexer.onPayloadReceived = { channel, data in
            receivedChannel = channel
            receivedPayload = data
        }

        // Chunk with small MTU to force multiple chunks
        let chunks = multiplexer.createChunks(channel: .pingControl, payload: originalPayload, maxChunkSize: 20)
        XCTAssertGreaterThan(chunks.count, 1)

        for chunk in chunks {
            multiplexer.processIncomingChunk(chunk)
        }

        XCTAssertEqual(receivedChannel, .pingControl)
        XCTAssertEqual(receivedPayload, originalPayload)
    }

    func testReassemblyCrcMismatch() {
        let multiplexer = BLEPacketMultiplexer()
        let originalPayload = "Payload to be corrupted".data(using: .utf8)!

        var crcErrorReported = false
        multiplexer.outboundChunkWriter = { chunk in
            let flags = PacketFlags(rawValue: chunk[1])
            if flags.contains(.crcError) {
                crcErrorReported = true
            }
            return true
        }

        var chunks = multiplexer.createChunks(channel: .weatherJson, payload: originalPayload, maxChunkSize: 30)
        XCTAssertGreaterThan(chunks.count, 0)

        // Corrupt one byte of data in the first chunk (after the 4-byte header)
        chunks[0][5] ^= 0xFF

        for chunk in chunks {
            multiplexer.processIncomingChunk(chunk)
        }

        XCTAssertTrue(crcErrorReported, "Multiplexer should have reported CRC Error upon corruption")
    }

    func testAckChunkCreation() {
        let multiplexer = BLEPacketMultiplexer()
        let ackChunk = multiplexer.createAckChunk(channel: .pingControl, sequenceNumber: 42)

        XCTAssertEqual(ackChunk[0], ChannelID.pingControl.rawValue)
        let flags = PacketFlags(rawValue: ackChunk[1])
        XCTAssertTrue(flags.contains(.ack))
        let seq = (UInt16(ackChunk[2]) << 8) | UInt16(ackChunk[3])
        XCTAssertEqual(seq, 42)
    }

    // MARK: - Phase 2 Contacts & Weather Tests
    func testPhoneNumberNormalization() {
        XCTAssertEqual(ContactsSyncManager.normalizePhoneNumber("+1 (555) 123-4567"), "+15551234567")
        XCTAssertEqual(ContactsSyncManager.normalizePhoneNumber("(800) 555-0199"), "8005550199")
        XCTAssertEqual(ContactsSyncManager.normalizePhoneNumber("  +44 20 7946 0958  "), "+442079460958")
        XCTAssertEqual(ContactsSyncManager.normalizePhoneNumber("invalid--chars!"), "")

        // Eastern Arabic numerals and Unicode directional marks
        let arabicWithMarks = "\u{200E}+٩٦٦\u{200F} ٥٠ ١٢٣ ٤٥٦٧\u{202A}"
        XCTAssertEqual(ContactsSyncManager.normalizePhoneNumber(arabicWithMarks), "+966501234567")
        XCTAssertEqual(ContactsSyncManager.normalizePhoneNumber("٠١٢٣٤٥٦٧٨٩"), "0123456789")
    }

    func testContactPayloadEncoding() throws {
        let contact = SyncedContactPayload(
            fullName: "Alice Smith",
            givenName: "Alice",
            familyName: "Smith",
            phoneNumbers: ["+15550100", "+15550101"]
        )

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode([contact])
        let jsonStr = String(data: data, encoding: .utf8)!

        XCTAssertTrue(jsonStr.contains("Alice Smith"))
        XCTAssertTrue(jsonStr.contains("+15550100"))

        let decoder = JSONDecoder()
        let decoded = try decoder.decode([SyncedContactPayload].self, from: data)
        XCTAssertEqual(decoded.count, 1)
        XCTAssertEqual(decoded[0].fullName, "Alice Smith")
        XCTAssertEqual(decoded[0].phoneNumbers, ["+15550100", "+15550101"])
    }

    func testCompactWeatherPayloadSerialization() throws {
        let hourly = [
            HourlyForecastItem(time: 1730650000, temp: 18.5, code: 1),
            HourlyForecastItem(time: 1730653600, temp: 19.0, code: 2)
        ]
        let weather = CompactWeatherPayload(
            temp: 18.5,
            weatherCode: 1,
            high: 22.0,
            low: 12.0,
            isDay: 1,
            cityName: "Cupertino",
            hourly: hourly,
            updatedAt: 1730649600
        )

        let encoder = JSONEncoder()
        let data = try encoder.encode(weather)
        XCTAssertGreaterThan(data.count, 0)

        let decoder = JSONDecoder()
        let decoded = try decoder.decode(CompactWeatherPayload.self, from: data)
        XCTAssertEqual(decoded.cityName, "Cupertino")
        XCTAssertEqual(decoded.temp, 18.5)
        XCTAssertEqual(decoded.weatherCode, 1)
        XCTAssertEqual(decoded.high, 22.0)
        XCTAssertEqual(decoded.low, 12.0)
        XCTAssertEqual(decoded.hourly.count, 2)
    }

    // MARK: - Phase 3 Health Telemetry Tests
    func testHealthTelemetryPayloadDecoding() throws {
        let mockJson = """
        {
          "date": "2026-11-03",
          "timestamp": 1730650000,
          "calories": {
            "total": 2150.4,
            "active": 450.2
          },
          "spo2": {
            "latest": 98,
            "avg": 97.2,
            "min": 94,
            "samples": [
              {"timestamp": 1730640000, "percentage": 95},
              {"timestamp": 1730643600, "percentage": 98}
            ]
          },
          "sleep": {
            "totalDurationMinutes": 445,
            "sleepScore": 86,
            "awakeMinutes": 30,
            "lightMinutes": 210,
            "deepMinutes": 115,
            "remMinutes": 90
          },
          "stress": {
            "stressScore": 32,
            "hrvRmssd": 48.5
          },
          "activity": {
            "steps": 8450,
            "heartRate": 72,
            "restingHeartRate": 58
          }
        }
        """

        let data = mockJson.data(using: .utf8)!
        let decoder = JSONDecoder()
        let payload = try decoder.decode(HealthTelemetryPayload.self, from: data)

        XCTAssertEqual(payload.date, "2026-11-03")
        XCTAssertEqual(payload.calories.total, 2150.4, accuracy: 0.01)
        XCTAssertEqual(payload.calories.active, 450.2, accuracy: 0.01)
        XCTAssertEqual(payload.spo2.latest, 98)
        XCTAssertEqual(payload.spo2.samples.count, 2)
        XCTAssertEqual(payload.sleep.totalDurationMinutes, 445)
        XCTAssertEqual(payload.sleep.sleepScore, 86)
        XCTAssertEqual(payload.stress.stressScore, 32)
        XCTAssertEqual(payload.stress.hrvRmssd, 48.5, accuracy: 0.01)
        XCTAssertEqual(payload.activity.steps, 8450)
    }

    // MARK: - Phase 4 Media QoL Tests
    func testMediaQoLPayloadEncodingAndDecoding() throws {
        let originalPayload = MediaQoLPayload(
            trackKey: "The Weeknd_Blinding Lights",
            durationSeconds: 200.5,
            audioRouteName: "AirPods Pro",
            artworkBase64: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        )

        let encoder = JSONEncoder()
        let data = try encoder.encode(originalPayload)

        let decoder = JSONDecoder()
        let decodedPayload = try decoder.decode(MediaQoLPayload.self, from: data)

        XCTAssertEqual(decodedPayload.trackKey, originalPayload.trackKey)
        XCTAssertEqual(decodedPayload.durationSeconds, originalPayload.durationSeconds, accuracy: 0.001)
        XCTAssertEqual(decodedPayload.audioRouteName, originalPayload.audioRouteName)
        XCTAssertEqual(decodedPayload.artworkBase64, originalPayload.artworkBase64)
    }

    func testMediaFetchArtCommandDecoding() throws {
        let jsonStr = """
        {
            "cmd": "FETCH_ART",
            "title": "Blinding Lights",
            "artist": "The Weeknd"
        }
        """
        let data = jsonStr.data(using: .utf8)!
        let decoder = JSONDecoder()
        let cmd = try decoder.decode(MediaFetchArtCommand.self, from: data)

        XCTAssertEqual(cmd.cmd, "FETCH_ART")
        XCTAssertEqual(cmd.title, "Blinding Lights")
        XCTAssertEqual(cmd.artist, "The Weeknd")
    }

    func testArtworkImageResizingAndCompressionTarget() {
        let manager = MediaQoLManager.shared
        let mockImage = manager.generateMockArtworkImage()
        let compressedData = manager.resizeAndCompress(image: mockImage, targetSize: CGSize(width: 96, height: 96), quality: 0.65)

        XCTAssertNotNil(compressedData)
        guard let data = compressedData else { return }

        // Must be under 8KB (8192 bytes) as specified in requirements
        XCTAssertLessThan(data.count, 8192, "Compressed 96x96 artwork should be < 8KB (was \(data.count) bytes)")
        XCTAssertGreaterThan(data.count, 100, "Compressed artwork data should not be trivially empty")
    }

    func testArabicTrackTermEncoding() {
        let artist = "عمرو دياب"
        let title = "نور العين"
        let term = "\(artist) \(title)".trimmingCharacters(in: .whitespacesAndNewlines)
        let encoded = term.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)

        XCTAssertNotNil(encoded)
        let urlString = "https://itunes.apple.com/search?term=\(encoded!)&entity=song&limit=1"
        let url = URL(string: urlString)
        XCTAssertNotNil(url)
    }

    // MARK: - Phase 5 Message Relay & Inbox Tests
    func testMessageReplyPayloadRoundTrip() throws {
        let replyId = UUID().uuidString
        let payload = MessageReplyPayload(
            replyId: replyId,
            threadKey: "net.whatsapp.WhatsApp:15550192834",
            appBundleId: "net.whatsapp.WhatsApp",
            recipient: "+1 (555) 019-2834",
            senderName: "Sarah Connor",
            replyText: "Mission acknowledged, meeting at rendezvous point.",
            ancsUid: 42,
            timestamp: 1730650000000
        )

        let encoder = JSONEncoder()
        let data = try encoder.encode(payload)

        let decoder = JSONDecoder()
        let decoded = try decoder.decode(MessageReplyPayload.self, from: data)

        XCTAssertEqual(decoded.replyId, replyId)
        XCTAssertEqual(decoded.threadKey, "net.whatsapp.WhatsApp:15550192834")
        XCTAssertEqual(decoded.appBundleId, "net.whatsapp.WhatsApp")
        XCTAssertEqual(decoded.recipient, "+1 (555) 019-2834")
        XCTAssertEqual(decoded.senderName, "Sarah Connor")
        XCTAssertEqual(decoded.replyText, "Mission acknowledged, meeting at rendezvous point.")
        XCTAssertEqual(decoded.ancsUid, 42)
        XCTAssertEqual(decoded.timestamp, 1730650000000)
    }

    func testMessageReplyAckRoundTrip() throws {
        let replyId = UUID().uuidString
        let ack = MessageReplyAckPayload(
            replyId: replyId,
            status: "SENT",
            detail: "Relayed via Webhook (HTTP 200)"
        )

        let encoder = JSONEncoder()
        let data = try encoder.encode(ack)

        let decoder = JSONDecoder()
        let decoded = try decoder.decode(MessageReplyAckPayload.self, from: data)

        XCTAssertEqual(decoded.replyId, replyId)
        XCTAssertEqual(decoded.status, "SENT")
        XCTAssertEqual(decoded.detail, "Relayed via Webhook (HTTP 200)")
    }

    func testSyntheticMessagePayloadGeneration() throws {
        let synthetic = SyntheticMessagePayload(
            cmd: "INJECT_MESSAGE",
            appBundleId: "net.whatsapp.WhatsApp",
            appDisplayName: "WhatsApp",
            senderName: "Sarah Connor",
            senderIdentifier: "+15550192834",
            body: "Need backup immediately."
        )

        let encoder = JSONEncoder()
        let data = try encoder.encode(synthetic)

        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        XCTAssertNotNil(json)
        XCTAssertEqual(json?["cmd"] as? String, "INJECT_MESSAGE")
        XCTAssertEqual(json?["appBundleId"] as? String, "net.whatsapp.WhatsApp")
        XCTAssertEqual(json?["appDisplayName"] as? String, "WhatsApp")
        XCTAssertEqual(json?["senderName"] as? String, "Sarah Connor")
        XCTAssertEqual(json?["body"] as? String, "Need backup immediately.")
    }

    func testRelayDeepLinkFormatting() {
        let recipient = "+1 (555) 019-2834"
        let digitsOnly = recipient.components(separatedBy: CharacterSet.decimalDigits.inverted).joined()
        XCTAssertEqual(digitsOnly, "15550192834")

        let text = "Hello from Watch!"
        let encodedText = text.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)!

        let whatsappUrl = URL(string: "whatsapp://send?phone=\(digitsOnly)&text=\(encodedText)")
        XCTAssertNotNil(whatsappUrl)
        XCTAssertEqual(whatsappUrl?.scheme, "whatsapp")

        let smsUrl = URL(string: "sms:\(recipient)&body=\(encodedText)")
        XCTAssertNotNil(smsUrl)
        XCTAssertEqual(smsUrl?.scheme, "sms")
    }

    func testShortcutsUrlEncoding() {
        let shortcutName = "WearBridgeReply"
        let encodedName = shortcutName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)!
        let mockJson = "{\"replyId\":\"1234\",\"replyText\":\"OK\"}"
        let encodedJson = mockJson.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)!

        let shortcutUrl = URL(string: "shortcuts://run-shortcut?name=\(encodedName)&input=text&text=\(encodedJson)")
        XCTAssertNotNil(shortcutUrl)
        XCTAssertEqual(shortcutUrl?.scheme, "shortcuts")
    }

    // MARK: - Phase 6 File Transfer & Watch Face Tests
    func testFileStartPayloadEncoding() throws {
        let transferId = UUID().uuidString
        let hash = "d7a8fbb307d7809469ca9ab400f20d5b9a8f1783180f97310a192d0c3ec48b3f"
        let startDict: [String: Any] = [
            "cmd": "FILE_START",
            "transferId": transferId,
            "fileName": "cyber_dial.apk",
            "fileSize": 1845200,
            "fileType": "WATCH_FACE",
            "sha256": hash,
            "totalWindows": 57
        ]

        let data = try JSONSerialization.data(withJSONObject: startDict)
        let parsed = try JSONSerialization.jsonObject(with: data) as? [String: Any]

        XCTAssertNotNil(parsed)
        XCTAssertEqual(parsed?["cmd"] as? String, "FILE_START")
        XCTAssertEqual(parsed?["transferId"] as? String, transferId)
        XCTAssertEqual(parsed?["fileName"] as? String, "cyber_dial.apk")
        XCTAssertEqual(parsed?["fileSize"] as? Int, 1845200)
        XCTAssertEqual(parsed?["fileType"] as? String, "WATCH_FACE")
        XCTAssertEqual(parsed?["sha256"] as? String, hash)
        XCTAssertEqual(parsed?["totalWindows"] as? Int, 57)
    }

    func testFileWindowAckPayloadDecoding() throws {
        let jsonStr = """
        {
            "cmd": "FILE_WINDOW_ACK",
            "transferId": "transfer-1234",
            "windowIndex": 3,
            "bytesReceived": 98304,
            "progress": 0.45
        }
        """
        let data = jsonStr.data(using: .utf8)!
        let parsed = try JSONSerialization.jsonObject(with: data) as? [String: Any]

        XCTAssertNotNil(parsed)
        XCTAssertEqual(parsed?["cmd"] as? String, "FILE_WINDOW_ACK")
        XCTAssertEqual(parsed?["transferId"] as? String, "transfer-1234")
        XCTAssertEqual(parsed?["windowIndex"] as? Int, 3)
        XCTAssertEqual(parsed?["bytesReceived"] as? Int, 98304)
        XCTAssertEqual(parsed?["progress"] as? Double, 0.45)
    }

    func testFileCompletePayloadDecoding() throws {
        let jsonStr = """
        {
            "cmd": "FILE_COMPLETE",
            "transferId": "transfer-5678",
            "status": "VERIFIED",
            "savedPath": "/transfers/cyber_dial.apk"
        }
        """
        let data = jsonStr.data(using: .utf8)!
        let parsed = try JSONSerialization.jsonObject(with: data) as? [String: Any]

        XCTAssertNotNil(parsed)
        XCTAssertEqual(parsed?["cmd"] as? String, "FILE_COMPLETE")
        XCTAssertEqual(parsed?["status"] as? String, "VERIFIED")
        XCTAssertEqual(parsed?["savedPath"] as? String, "/transfers/cyber_dial.apk")
    }

    func testSha256ChecksumCalculation() {
        let testString = "The quick brown fox jumps over the lazy dog"
        let data = testString.data(using: .utf8)!
        let computed = FileTransferManager.computeSha256(data: data)
        let expected = "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592"
        XCTAssertEqual(computed.lowercased(), expected)
    }

    func testWatchFaceCatalogJsonParsing() throws {
        let mockCatalogJson = """
        [
            {
                "id": "cyber_dial",
                "title": "Cyber Dial WFF",
                "author": "PixelCraft",
                "version": "2.1.0",
                "description": "Futuristic neon telemetry face.",
                "fileSize": 100352,
                "fileType": "WATCH_FACE",
                "fileName": "cyber_dial.wff",
                "previewUrl": null,
                "downloadUrl": null,
                "isBuiltIn": true,
                "sha256": null
            }
        ]
        """
        let data = mockCatalogJson.data(using: .utf8)!
        let decoder = JSONDecoder()
        let items = try decoder.decode([WatchFaceItem].self, from: data)

        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items.first?.id, "cyber_dial")
        XCTAssertEqual(items.first?.title, "Cyber Dial WFF")
        XCTAssertEqual(items.first?.fileSize, 100352)
        XCTAssertEqual(items.first?.formattedSize, "98.0 KB")
    }

    // MARK: - AppBrandRegistry Tests
    func testAppBrandRegistryResolution() {
        // TikTok variants
        let tiktokGlobal = AppBrandRegistry.resolve(AppBrandRegistry.bundleTikTokGlobal)
        XCTAssertEqual(tiktokGlobal.displayName, "TikTok")
        XCTAssertEqual(tiktokGlobal.sfSymbol, "music.note")

        let tiktokAme = AppBrandRegistry.resolve(AppBrandRegistry.bundleTikTokAme)
        XCTAssertEqual(tiktokAme.displayName, "TikTok")

        let tiktokBd = AppBrandRegistry.resolve(AppBrandRegistry.bundleTikTokBd)
        XCTAssertEqual(tiktokBd.displayName, "TikTok")

        // WhatsApp
        let whatsapp = AppBrandRegistry.resolve(AppBrandRegistry.bundleWhatsApp)
        XCTAssertEqual(whatsapp.displayName, "WhatsApp")
        XCTAssertEqual(whatsapp.sfSymbol, "message.fill")

        // Instagram & Threads
        let instagram = AppBrandRegistry.resolve(AppBrandRegistry.bundleInstagram)
        XCTAssertEqual(instagram.displayName, "Instagram")
        XCTAssertEqual(instagram.sfSymbol, "camera.fill")

        let threads = AppBrandRegistry.resolve(AppBrandRegistry.bundleThreads)
        XCTAssertEqual(threads.displayName, "Threads")
        XCTAssertEqual(threads.sfSymbol, "at")

        // Telegram
        let telegram = AppBrandRegistry.resolve(AppBrandRegistry.bundleTelegram)
        XCTAssertEqual(telegram.displayName, "Telegram")
        XCTAssertEqual(telegram.sfSymbol, "paperplane.fill")

        // Discord
        let discord = AppBrandRegistry.resolve(AppBrandRegistry.bundleDiscord)
        XCTAssertEqual(discord.displayName, "Discord")
        XCTAssertEqual(discord.sfSymbol, "bubble.left.and.bubble.right.fill")

        // iMessage
        let imessage = AppBrandRegistry.resolve(AppBrandRegistry.bundleIMessage)
        XCTAssertEqual(imessage.displayName, "iMessage")
        XCTAssertEqual(imessage.sfSymbol, "message.fill")

        // Fallback for unknown bundle
        let unknown = AppBrandRegistry.resolve("com.example.unknownapp")
        XCTAssertEqual(unknown.displayName, "Unknownapp")
        XCTAssertEqual(unknown.sfSymbol, "bell.fill")
    }

    // MARK: - Watch OTA Manager Tests
    func testWatchOtaVersionComparison() {
        // Newer versions
        XCTAssertTrue(WatchOtaManager.isUpdateAvailable(current: "2.0.0", candidate: "2.1.0"))
        XCTAssertTrue(WatchOtaManager.isUpdateAvailable(current: "2.0.0 (Build 200)", candidate: "v2.1.0"))
        XCTAssertTrue(WatchOtaManager.isUpdateAvailable(current: "2.0.0", candidate: "2.0.1"))
        XCTAssertTrue(WatchOtaManager.isUpdateAvailable(current: "1.9.9", candidate: "2.0.0"))
        XCTAssertTrue(WatchOtaManager.isUpdateAvailable(current: "2.0", candidate: "2.0.1"))

        // Equal or older candidate versions
        XCTAssertFalse(WatchOtaManager.isUpdateAvailable(current: "2.0.0", candidate: "2.0.0"))
        XCTAssertFalse(WatchOtaManager.isUpdateAvailable(current: "2.1.0", candidate: "2.0.0"))
        XCTAssertFalse(WatchOtaManager.isUpdateAvailable(current: "2.0.1", candidate: "2.0.0"))
        XCTAssertFalse(WatchOtaManager.isUpdateAvailable(current: "2.0.0 (Build 200)", candidate: "v2.0.0"))
    }

    func testWatchOtaSha256Checksum() {
        let testText = "The quick brown fox jumps over the lazy dog"
        let data = testText.data(using: .utf8)!
        let expected = "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592"

        let computed = WatchOtaManager.computeSha256(data: data)
        XCTAssertEqual(computed.lowercased(), expected)
    }

    func testWatchOtaCommandPayloadGeneration() {
        let url = "http://192.168.1.150:8080/wearbridge-ota.apk"
        let versionName = "2.1.0"
        let sha256 = "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592"

        let payload = WatchOtaManager.makeOtaCommandJson(url: url, versionName: versionName, sha256: sha256)

        XCTAssertEqual(payload["cmd"] as? String, "OTA_UPDATE_URL")
        XCTAssertEqual(payload["url"] as? String, url)
        XCTAssertEqual(payload["versionName"] as? String, versionName)
        XCTAssertEqual(payload["sha256"] as? String, sha256)

        let jsonData = try? JSONSerialization.data(withJSONObject: payload)
        XCTAssertNotNil(jsonData)
    }

    func testGitHubReleaseInfoFormatting() {
        let release = GitHubReleaseInfo(
            tagName: "v2.1.0",
            name: "WearBridge v2.1.0 OTA Update",
            body: "Bug fixes and OTA self-update engine",
            apkDownloadUrl: "https://github.com/wearos-ancs/WearBridge/releases/download/v2.1.0/WearBridge-WearOS-v2.1.0-release.apk",
            apkFileName: "WearBridge-WearOS-v2.1.0-release.apk",
            apkSize: 3346891,
            publishedAt: "2026-11-04T12:00:00Z"
        )

        XCTAssertEqual(release.id, "v2.1.0")
        XCTAssertEqual(release.formattedSize, "3.2 MB")
    }
}
