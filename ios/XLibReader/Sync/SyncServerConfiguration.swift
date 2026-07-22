import Foundation

enum SyncServerConfiguration {
    static let defaultAddress = "https://xunit.cc/xlib/backend"
    static let storageKey = "sync.server.address.v1"
    static let credentialServerKey = "sync.credentials.server.address.v1"

    static func resolvedAddress(
        defaults: UserDefaults = .standard,
        bundle: Bundle = .main,
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) -> String {
        let candidates = [
            defaults.string(forKey: storageKey),
            environment["XLIB_SYNC_BASE_URL"],
            bundle.object(forInfoDictionaryKey: "XLibSyncBaseURL") as? String,
            defaultAddress
        ]
        return candidates.compactMap { $0 }.compactMap(normalizedAddress).first ?? defaultAddress
    }

    static func normalizedAddress(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              !trimmed.contains("$("),
              var components = URLComponents(string: trimmed),
              components.scheme?.lowercased() == "https",
              components.host != nil,
              components.user == nil,
              components.password == nil,
              components.query == nil,
              components.fragment == nil else { return nil }

        components.scheme = "https"
        while components.percentEncodedPath.count > 1,
              components.percentEncodedPath.hasSuffix("/") {
            components.percentEncodedPath.removeLast()
        }
        if components.percentEncodedPath == "/" { components.percentEncodedPath = "" }
        return components.url?.absoluteString
    }
}
