import Foundation
import Contacts
import CryptoKit
import os.log
import Combine

/// Model representing a serialized contact sent over BLE Channel 0x02.
public struct SyncedContactPayload: Codable, Equatable {
    public let fullName: String
    public let givenName: String
    public let familyName: String
    public let phoneNumbers: [String]

    // Alternate keys for JSON compatibility
    public var name: String { fullName }
    public var phones: [String] { phoneNumbers }

    enum CodingKeys: String, CodingKey {
        case fullName
        case givenName
        case familyName
        case phoneNumbers
        case name
        case phones
    }

    public init(fullName: String, givenName: String, familyName: String, phoneNumbers: [String]) {
        self.fullName = fullName
        self.givenName = givenName
        self.familyName = familyName
        self.phoneNumbers = phoneNumbers
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(fullName, forKey: .fullName)
        try container.encode(givenName, forKey: .givenName)
        try container.encode(familyName, forKey: .familyName)
        try container.encode(phoneNumbers, forKey: .phoneNumbers)
        // Also provide name & phones for simple parsers
        try container.encode(fullName, forKey: .name)
        try container.encode(phoneNumbers, forKey: .phones)
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let fName = try? container.decode(String.self, forKey: .fullName)
        let altName = try? container.decode(String.self, forKey: .name)
        self.fullName = fName ?? altName ?? "Unknown"

        self.givenName = (try? container.decode(String.self, forKey: .givenName)) ?? ""
        self.familyName = (try? container.decode(String.self, forKey: .familyName)) ?? ""

        if let list = try? container.decode([String].self, forKey: .phoneNumbers) {
            self.phoneNumbers = list
        } else if let altList = try? container.decode([String].self, forKey: .phones) {
            self.phoneNumbers = altList
        } else {
            self.phoneNumbers = []
        }
    }
}

/// Manages fetching iOS address book contacts via CNContactStore,
/// calculating a deterministic SHA-256 hash to detect changes,
/// and streaming updates over BLE Channel 0x02 to Wear OS.
public class ContactsSyncManager: ObservableObject {

    public static let shared = ContactsSyncManager()

    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "ContactsSyncManager")
    private let store = CNContactStore()
    private let userDefaultsKey = "wearbridge_last_contacts_hash"

    @Published public var isSyncing: Bool = false
    @Published public var syncedContactCount: Int = 0
    @Published public var lastSyncDate: Date? = nil
    @Published public var lastSyncHash: String? = nil

    private init() {
        lastSyncHash = UserDefaults.standard.string(forKey: userDefaultsKey)
    }

    /// Normalizes raw phone numbers by:
    /// 1. Stripping invisible Unicode directional marks (\u{200E}, \u{200F}, \u{202A}–\u{202E}).
    /// 2. Converting Eastern Arabic numerals (٠١٢٣٤٥٦٧٨٩) and Persian numerals to ASCII digits.
    /// 3. Retaining leading international '+' prefix while filtering digits.
    public static func normalizePhoneNumber(_ raw: String) -> String {
        var cleaned = ""
        for char in raw {
            // Strip directional marks
            if char == "\u{200E}" || char == "\u{200F}" || ("\u{202A}"..."\u{202E}").contains(char) {
                continue
            }
            // Convert Eastern Arabic numerals
            switch char {
            case "٠", "۰": cleaned.append("0")
            case "١", "۱": cleaned.append("1")
            case "٢", "۲": cleaned.append("2")
            case "٣", "۳": cleaned.append("3")
            case "٤", "۴": cleaned.append("4")
            case "٥", "۵": cleaned.append("5")
            case "٦", "۶": cleaned.append("6")
            case "٧", "۷": cleaned.append("7")
            case "٨", "۸": cleaned.append("8")
            case "٩", "۹": cleaned.append("9")
            default: cleaned.append(char)
            }
        }

        let trimmed = cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }

        let hasPlus = trimmed.hasPrefix("+")
        let digitsOnly = trimmed.filter { $0.isNumber }

        guard !digitsOnly.isEmpty else { return "" }
        return hasPlus ? "+\(digitsOnly)" : digitsOnly
    }

    /// Request contacts authorization from iOS system.
    public func requestAuthorization(completion: @escaping (Bool) -> Void) {
        let status = CNContactStore.authorizationStatus(for: .contacts)
        switch status {
        case .authorized:
            completion(true)
        case .notDetermined:
            store.requestAccess(for: .contacts) { granted, error in
                DispatchQueue.main.async {
                    if let error = error {
                        self.logger.error("Contacts permission request error: \(error.localizedDescription)")
                    }
                    completion(granted)
                }
            }
        default:
            logger.warning("Contacts access denied or restricted (status=\(status.rawValue))")
            completion(false)
        }
    }

    /// Fetch all contacts, extract names and normalized phone numbers,
    /// sort deterministically, and serialize to compact JSON with SHA-256.
    public func fetchAndSerializeContacts() throws -> (payload: Data, hash: String, count: Int) {
        let keysToFetch: [CNKeyDescriptor] = [
            CNContactGivenNameKey as CNKeyDescriptor,
            CNContactFamilyNameKey as CNKeyDescriptor,
            CNContactPhoneNumbersKey as CNKeyDescriptor
        ]

        let request = CNContactFetchRequest(keysToFetch: keysToFetch)
        request.sortOrder = .userDefault

        var contactList: [SyncedContactPayload] = []

        try store.enumerateContacts(with: request) { contact, _ in
            let given = contact.givenName.trimmingCharacters(in: .whitespacesAndNewlines)
            let family = contact.familyName.trimmingCharacters(in: .whitespacesAndNewlines)

            var nameParts: [String] = []
            if !given.isEmpty { nameParts.append(given) }
            if !family.isEmpty { nameParts.append(family) }
            let full = nameParts.isEmpty ? "Unknown" : nameParts.joined(separator: " ")

            var normalizedPhones: [String] = []
            for labeledValue in contact.phoneNumbers {
                let rawNumber = labeledValue.value.stringValue
                let normalized = Self.normalizePhoneNumber(rawNumber)
                if !normalized.isEmpty && !normalizedPhones.contains(normalized) {
                    normalizedPhones.append(normalized)
                }
            }

            // Only sync contacts that have at least one valid phone number
            if !normalizedPhones.isEmpty {
                contactList.append(SyncedContactPayload(
                    fullName: full,
                    givenName: given,
                    familyName: family,
                    phoneNumbers: normalizedPhones
                ))
            }
        }

        // Sort deterministically by primary phone number and full name
        contactList.sort {
            let p1 = $0.phoneNumbers.first ?? ""
            let p2 = $1.phoneNumbers.first ?? ""
            if p1 == p2 {
                return $0.fullName < $1.fullName
            }
            return p1 < p2
        }

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let jsonData = try encoder.encode(contactList)

        // Compute SHA-256 hash
        let digest = SHA256.hash(data: jsonData)
        let hashString = digest.compactMap { String(format: "%02x", $0) }.joined()

        return (payload: jsonData, hash: hashString, count: contactList.count)
    }

    /// Performs contacts sync. If `force` is false, only transmits if the hash changed.
    public func syncContacts(force: Bool = false, completion: ((Result<Int, Error>) -> Void)? = nil) {
        requestAuthorization { [weak self] authorized in
            guard let self = self else { return }

            guard authorized else {
                let err = NSError(
                    domain: "WearBridge.Contacts",
                    code: 403,
                    userInfo: [NSLocalizedDescriptionKey: "Contacts permission not granted by user"]
                )
                self.logger.warning("Contacts sync skipped: not authorized")
                completion?(.failure(err))
                return
            }

            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                guard let self = self else { return }
                do {
                    self.logger.info("Reading address book contacts...")
                    let (payload, hash, count) = try self.fetchAndSerializeContacts()

                    let storedHash = UserDefaults.standard.string(forKey: self.userDefaultsKey)

                    if !force && storedHash == hash {
                        self.logger.info("Contacts hash unchanged (\(hash.prefix(8))...), skipping transmission")
                        DispatchQueue.main.async {
                            self.syncedContactCount = count
                            completion?(.success(0))
                        }
                        return
                    }

                    self.logger.info("Contacts changed or force sync requested. Sending \(count) contacts (\(payload.count) bytes) over Channel 0x02")

                    DispatchQueue.main.async {
                        self.isSyncing = true
                    }

                    // Dispatch via BLEServerManager
                    BLEServerManager.shared.sendContactsPayload(payload)

                    UserDefaults.standard.set(hash, forKey: self.userDefaultsKey)

                    DispatchQueue.main.async {
                        self.isSyncing = false
                        self.syncedContactCount = count
                        self.lastSyncDate = Date()
                        self.lastSyncHash = hash
                        completion?(.success(count))
                    }

                } catch {
                    self.logger.error("Failed to fetch/serialize contacts: \(error.localizedDescription)")
                    DispatchQueue.main.async {
                        self.isSyncing = false
                        completion?(.failure(error))
                    }
                }
            }
        }
    }
}

