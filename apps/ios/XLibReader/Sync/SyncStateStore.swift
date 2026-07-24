import CryptoKit
import Foundation

actor SyncStateStore {
    private struct HashEntry: Codable, Sendable {
        let fileSize: Int64
        let modifiedAt: Date
        let hash: String
    }

    private struct State: Codable, Sendable {
        var hashes: [UUID: HashEntry] = [:]
        var remote: [String: RemoteProgressSnapshot] = [:]
    }

    private let url: URL
    private var state: State

    init(root: URL? = nil) {
        let base = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appending(path: "XLibReader", directoryHint: .isDirectory)
            .appending(path: "Sync", directoryHint: .isDirectory)
        url = base.appending(path: "sync-state.json")
        if let data = try? Data(contentsOf: url), let decoded = try? JSONDecoder().decode(State.self, from: data) {
            state = decoded
        } else {
            state = State()
        }
    }

    func identity(for book: Book, fileURL: URL) throws -> SyncBookIdentity {
        if let cached = state.hashes[book.id],
           cached.fileSize == book.fileSize,
           abs(cached.modifiedAt.timeIntervalSince(book.modifiedAt)) < 1 {
            return SyncBookIdentity(
                localBookID: book.id,
                key: SyncBookKey(bookHash: cached.hash, fileSize: book.fileSize)
            )
        }

        let hash = try hashFile(at: fileURL)
        state.hashes[book.id] = HashEntry(fileSize: book.fileSize, modifiedAt: book.modifiedAt, hash: hash)
        try persist()
        return SyncBookIdentity(
            localBookID: book.id,
            key: SyncBookKey(bookHash: hash, fileSize: book.fileSize)
        )
    }

    func replaceRemote(_ items: [RemoteProgressSnapshot]) throws {
        state.remote = Dictionary(uniqueKeysWithValues: items.map { (Self.storageKey($0.key), $0) })
        try persist()
    }

    func cachedRemote() -> [RemoteProgressSnapshot] {
        Array(state.remote.values)
    }

    func clearRemote() throws {
        state.remote.removeAll()
        try persist()
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

    private func persist() throws {
        let directory = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(state)
        try data.write(to: url, options: [.atomic, .completeFileProtection])
    }

    static func storageKey(_ key: SyncBookKey) -> String {
        "\(key.bookHash):\(key.fileSize)"
    }
}
