import CryptoKit
import Foundation

actor SyncStateStore {
    private let database: LocalDatabase

    init(database: LocalDatabase) {
        self.database = database
    }

    func identity(for book: Book, fileURL: URL) throws -> SyncBookIdentity {
        if let hash = try database.cachedIdentity(for: book) {
            return SyncBookIdentity(localBookID: book.id, key: SyncBookKey(bookHash: hash, fileSize: book.fileSize))
        }

        let hash = try hashFile(at: fileURL)
        try database.saveIdentity(hash, for: book)
        return SyncBookIdentity(
            localBookID: book.id,
            key: SyncBookKey(bookHash: hash, fileSize: book.fileSize)
        )
    }

    func replaceRemote(_ items: [RemoteProgressSnapshot]) throws {
        try database.replaceRemote(items)
    }

    func cachedRemote() -> [RemoteProgressSnapshot] {
        (try? database.cachedRemote()) ?? []
    }

    func clearRemote() throws {
        try database.clearRemote()
    }

    private func hashFile(at fileURL: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }
        var hasher = SHA256()
        while true {
            try Task.checkCancellation()
            let data = try handle.read(upToCount: 256 * 1_024) ?? Data()
            if data.isEmpty { break }
            hasher.update(data: data)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    static func storageKey(_ key: SyncBookKey) -> String {
        "\(key.bookHash):\(key.fileSize)"
    }
}
