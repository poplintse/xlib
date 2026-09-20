import Foundation
import Observation
import UIKit

/// Identity configuration survives reader navigation; it does not own a reading session.
@MainActor
@Observable
final class SyncConfigurationSession {
    let defaults: UserDefaults
    let database: LocalDatabase?
    var deviceRegistration: SyncDeviceRegistration
    var configuredEmailValue: String?
    var credentials: SyncCredentials?
    var serverAddress: String
    var generation = UUID()

    init(defaults: UserDefaults, database: LocalDatabase? = nil) {
        self.defaults = defaults
        self.database = database
        serverAddress = database?.syncString(for: SyncServerConfiguration.storageKey)
            .flatMap(SyncServerConfiguration.normalizedAddress)
            ?? SyncServerConfiguration.resolvedAddress(defaults: defaults)
        deviceRegistration = Self.loadDeviceRegistration(defaults: defaults, database: database)
        configuredEmailValue = Self.loadConfiguredEmail(defaults: defaults, database: database)
    }

    func invalidate() {
        generation = UUID()
        credentials = nil
        removeValue(for: SyncServerConfiguration.credentialServerKey)
    }

    /// nil rejects invalid input; false means the persisted value is unchanged.
    func saveConfiguredEmail(_ value: String) -> Bool? {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let parts = normalized.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count == 2,
              !parts[0].isEmpty,
              parts[1].contains("."),
              !parts[1].hasPrefix("."),
              !parts[1].hasSuffix(".") else { return nil }
        let changed = normalized != configuredEmailValue
        configuredEmailValue = normalized
        set(normalized, for: Self.emailKey)
        return changed
    }

    /// nil rejects invalid input; false means the persisted value is unchanged.
    func saveDeviceName(_ value: String) -> Bool? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= Self.maximumDeviceNameLength else { return nil }
        let changed = trimmed != deviceRegistration.deviceName
        deviceRegistration.deviceName = trimmed
        set(trimmed, for: Self.deviceNameKey)
        return changed
    }

    static func loadDeviceRegistration(defaults: UserDefaults, database: LocalDatabase? = nil) -> SyncDeviceRegistration {
        let key = "sync.device.id.v1"
        let deviceID: UUID
        if let raw = database?.syncString(for: key) ?? defaults.string(forKey: key),
           let existing = UUID(uuidString: raw) {
            deviceID = existing
        } else {
            deviceID = UUID()
            if let database { try? database.setSyncString(deviceID.uuidString.lowercased(), for: key) }
            else { defaults.set(deviceID.uuidString.lowercased(), forKey: key) }
        }
        let storedName = (database?.syncString(for: deviceNameKey) ?? defaults.string(forKey: deviceNameKey))?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let defaultName = UIDevice.current.name.trimmingCharacters(in: .whitespacesAndNewlines)
        return SyncDeviceRegistration(
            deviceId: deviceID,
            deviceName: storedName.flatMap({ $0.isEmpty ? nil : $0 })
                ?? (defaultName.isEmpty ? "iOS 设备" : defaultName),
            platform: "ios",
            appVersion: AppVersion.displayText
        )
    }

    static let deviceNameKey = "sync.device.name.v1"
    static let emailKey = "sync.email.v1"
    static let hasStartedSyncKey = "sync.has.started.v1"
    static let maximumDeviceNameLength = 20

    static func loadConfiguredEmail(defaults: UserDefaults, database: LocalDatabase? = nil) -> String? {
        let value = (database?.syncString(for: emailKey) ?? defaults.string(forKey: emailKey))?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return value.flatMap { $0.isEmpty ? nil : $0 }
    }

    func string(for key: String) -> String? {
        database?.syncString(for: key) ?? defaults.string(forKey: key)
    }

    func bool(for key: String) -> Bool {
        database?.syncBool(for: key) ?? defaults.bool(forKey: key)
    }

    func set(_ value: String, for key: String) {
        if let database { try? database.setSyncString(value, for: key) }
        else { defaults.set(value, forKey: key) }
    }

    func set(_ value: Bool, for key: String) {
        if let database { try? database.setSyncBool(value, for: key) }
        else { defaults.set(value, forKey: key) }
    }

    func removeValue(for key: String) {
        if let database { try? database.setSyncString(nil, for: key) }
        else { defaults.removeObject(forKey: key) }
    }
}
