import Foundation
import Security

struct SyncCredentialVault: Sendable {
    var load: @Sendable () async -> SyncCredentials?
    var save: @Sendable (SyncCredentials) async -> Void
    var clear: @Sendable () async -> Void

    static func live(service: String = "com.xlib.txtreader.progress-sync") -> SyncCredentialVault {
        let box = KeychainCredentialBox(service: service)
        return SyncCredentialVault(
            load: { await box.load() },
            save: { await box.save($0) },
            clear: { await box.clear() }
        )
    }
}

private actor KeychainCredentialBox {
    private let service: String
    private let account = "sync-token"

    init(service: String) { self.service = service }

    func load() -> SyncCredentials? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return try? JSONDecoder().decode(SyncCredentials.self, from: data)
    }

    func save(_ credentials: SyncCredentials) {
        guard let data = try? JSONEncoder().encode(credentials) else { return }
        let attributes = [kSecValueData as String: data]
        let status = SecItemUpdate(baseQuery as CFDictionary, attributes as CFDictionary)
        guard status == errSecItemNotFound else { return }
        var query = baseQuery
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(query as CFDictionary, nil)
    }

    func clear() {
        SecItemDelete(baseQuery as CFDictionary)
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }
}
