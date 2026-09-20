import AppleShared
import CryptoKit
import Foundation
import SQLite3

private let localSyncDeviceNameKey = "sync.device.name.v1"
private let localSyncEmailKey = "sync.email.v1"
private let localSyncStartedKey = "sync.has.started.v1"

enum LocalDatabaseError: LocalizedError {
    case sqlite(String)
    case invalidLegacyData(String)
    case migrationSourceChanged

    var errorDescription: String? {
        switch self {
        case .sqlite(let message): message
        case .invalidLegacyData(let message): message
        case .migrationSourceChanged: "旧版存储在迁移完成后发生变化"
        }
    }
}

/// The single iOS SQLite boundary. TXT content and Keychain credentials stay outside this database.
final class LocalDatabase: @unchecked Sendable {
    static let schemaVersion = 1
    static let migrationID = "ios-local-storage-v1"

    private enum Value {
        case int(Int64)
        case double(Double)
        case text(String)
        case null
    }

    private struct LegacySyncHash: Codable {
        let fileSize: Int64
        let modifiedAt: Date
        let hash: String
    }

    private struct LegacySyncState: Codable {
        var hashes: [UUID: LegacySyncHash] = [:]
        var remote: [String: RemoteProgressSnapshot] = [:]
    }

    private let connection: OpaquePointer
    private let root: URL
    private let defaults: UserDefaults
    private let lock = NSRecursiveLock()
    private var savepointSequence = 0

    static func open(root: URL? = nil, defaults: UserDefaults = .standard) throws -> LocalDatabase {
        let base = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appending(path: "XLibReader", directoryHint: .isDirectory)
        return try LocalDatabase(root: base, defaults: defaults)
    }

    private init(root: URL, defaults: UserDefaults) throws {
        self.root = root
        self.defaults = defaults
        let metadata = root.appending(path: "Metadata", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: metadata, withIntermediateDirectories: true)
        let databaseURL = metadata.appending(path: "xlib.db")
        var handle: OpaquePointer?
        let flags = SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(databaseURL.path, &handle, flags, nil) == SQLITE_OK, let handle else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "无法打开本地数据库"
            if let handle { sqlite3_close(handle) }
            throw LocalDatabaseError.sqlite(message)
        }
        connection = handle
        do {
            try execute("PRAGMA foreign_keys = ON")
            try execute("PRAGMA journal_mode = WAL")
            try createSchema()
            try migrateLegacyIfNeeded()
        } catch {
            throw error
        }
    }

    deinit { sqlite3_close(connection) }

    // MARK: - Library

    func loadBooks() throws -> [Book] {
        try locked {
            try retryPendingFileDeletions()
            var books: [Book] = []
            try query("""
                SELECT b.local_id,b.title,b.source_name,b.author,b.relative_path,b.file_size,
                       b.source_modified_at_ms,b.encoding,p.offset_bytes,p.read_at_ms
                FROM books b
                LEFT JOIN reading_progress p ON p.book_id=b.local_id
                ORDER BY COALESCE(p.read_at_ms,0) DESC
                """) { statement in
                guard let id = UUID(uuidString: text(statement, 0)),
                      let encoding = TextEncoding(rawValue: text(statement, 7)) else { return }
                let book = Book(
                    id: id,
                    title: text(statement, 1),
                    sourceName: text(statement, 2),
                    author: text(statement, 3),
                    relativePath: text(statement, 4),
                    fileSize: sqlite3_column_int64(statement, 5),
                    modifiedAt: date(statement, 6) ?? .distantPast,
                    encoding: encoding,
                    offset: min(sqlite3_column_int64(statement, 8), sqlite3_column_int64(statement, 5)),
                    updatedAt: date(statement, 9) ?? Date(timeIntervalSince1970: 0),
                    schemaVersion: Book.schemaVersion
                )
                if FileManager.default.fileExists(atPath: self.root.appending(path: book.relativePath).path) {
                    books.append(book)
                }
            }
            return books
        }
    }

    func insertBook(_ book: Book) throws {
        try locked {
            try transaction {
                try upsertBook(book)
                try execute("INSERT INTO reading_progress(book_id,offset_bytes,read_at_ms) VALUES(?,?,?)",
                            [.text(key(book.id)), .int(clamp(book.offset, size: book.fileSize)), nullableDate(book.updatedAt)])
            }
        }
    }

    func updateBook(_ book: Book) throws {
        try locked {
            try transaction {
                try upsertBook(book)
                try execute("""
                    INSERT INTO reading_progress(book_id,offset_bytes,read_at_ms) VALUES(?,?,?)
                    ON CONFLICT(book_id) DO UPDATE SET offset_bytes=excluded.offset_bytes,read_at_ms=excluded.read_at_ms
                    """, [.text(key(book.id)), .int(clamp(book.offset, size: book.fileSize)), nullableDate(book.updatedAt)])
            }
        }
    }

    func saveProgress(bookID: UUID, offset: Int64, updatedAt: Date) throws {
        try locked {
            try execute("""
                UPDATE reading_progress SET offset_bytes=MIN(MAX(?,0),(SELECT file_size FROM books WHERE local_id=?)),
                                            read_at_ms=? WHERE book_id=?
                """, [.int(offset), .text(key(bookID)), nullableDate(updatedAt), .text(key(bookID))])
        }
    }

    func deleteBooks(ids: Set<UUID>) throws {
        try locked {
            try transaction {
                for id in ids {
                    var relativePath: String?
                    try query("SELECT relative_path FROM books WHERE local_id=?", [.text(key(id))]) {
                        relativePath = text($0, 0)
                    }
                    if let relativePath {
                        try execute("""
                            INSERT INTO pending_file_deletions(relative_path,requested_at_ms) VALUES(?,?)
                            ON CONFLICT(relative_path) DO UPDATE SET requested_at_ms=excluded.requested_at_ms
                            """, [.text(relativePath), .int(milliseconds(.now))])
                    }
                    try execute("DELETE FROM books WHERE local_id=?", [.text(key(id))])
                }
            }
            try retryPendingFileDeletions()
        }
    }

    // MARK: - Bookmarks and TOC

    func bookmarks(bookID: UUID) throws -> [Bookmark] {
        try locked {
            var result: [Bookmark] = []
            try query("""
                SELECT local_id,offset_bytes,excerpt,created_at_ms FROM bookmarks
                WHERE book_id=? ORDER BY offset_bytes
                """, [.text(key(bookID))]) { statement in
                guard let id = UUID(uuidString: text(statement, 0)) else { return }
                result.append(Bookmark(id: id, bookID: bookID, offset: sqlite3_column_int64(statement, 1),
                                       excerpt: text(statement, 2), createdAt: date(statement, 3) ?? .distantPast))
            }
            return result
        }
    }

    func addBookmark(_ bookmark: Bookmark) throws {
        try locked {
            do {
                try execute("""
                    INSERT INTO bookmarks(local_id,book_id,offset_bytes,excerpt,created_at_ms) VALUES(?,?,?,?,?)
                    """, [.text(key(bookmark.id)), .text(key(bookmark.bookID)), .int(bookmark.offset),
                           .text(bookmark.excerpt), .int(milliseconds(bookmark.createdAt))])
            } catch let error as LocalDatabaseError {
                if case .sqlite(let message) = error, message.contains("UNIQUE") {
                    throw BookmarkError.duplicatePosition
                }
                throw error
            }
        }
    }

    func removeBookmark(id: UUID) throws {
        try locked { try execute("DELETE FROM bookmarks WHERE local_id=?", [.text(key(id))]) }
    }

    func cachedTOC(for book: Book) throws -> [TocEntry]? {
        try locked {
            var found = false
            try query("""
                SELECT source_file_size,source_modified_at_ms,generator_schema_version FROM toc_documents WHERE book_id=?
                """, [.text(key(book.id))]) { statement in
                found = sqlite3_column_int64(statement, 0) == book.fileSize
                    && abs((date(statement, 1) ?? .distantPast).timeIntervalSince(book.modifiedAt)) < 1
                    && sqlite3_column_int(statement, 2) == TocDocument.schemaVersion
            }
            guard found else { return nil }
            var entries: [TocEntry] = []
            try query("""
                SELECT entry_id,title,offset_bytes,level FROM toc_entries WHERE book_id=? ORDER BY ordinal
                """, [.text(key(book.id))]) { statement in
                guard let id = UUID(uuidString: text(statement, 0)) else { return }
                entries.append(TocEntry(id: id, title: text(statement, 1),
                                        offset: sqlite3_column_int64(statement, 2),
                                        level: Int(sqlite3_column_int(statement, 3))))
            }
            return entries
        }
    }

    func booksWithCachedTOC(_ books: [Book]) throws -> Set<UUID> {
        var result = Set<UUID>()
        for book in books where try cachedTOC(for: book) != nil { result.insert(book.id) }
        return result
    }

    func saveTOC(_ document: TocDocument, bookID: UUID) throws {
        try locked {
            try transaction {
                try execute("""
                    INSERT INTO toc_documents(book_id,source_file_size,source_modified_at_ms,encoding,generator_schema_version)
                    VALUES(?,?,?,?,?) ON CONFLICT(book_id) DO UPDATE SET
                    source_file_size=excluded.source_file_size,source_modified_at_ms=excluded.source_modified_at_ms,
                    encoding=excluded.encoding,generator_schema_version=excluded.generator_schema_version
                    """, [.text(key(bookID)), .int(document.fileSize), .int(milliseconds(document.modifiedAt)),
                           .null, .int(Int64(document.schemaVersion))])
                try execute("DELETE FROM toc_entries WHERE book_id=?", [.text(key(bookID))])
                for (ordinal, entry) in document.entries.enumerated() {
                    try execute("""
                        INSERT INTO toc_entries(book_id,ordinal,entry_id,title,offset_bytes,level) VALUES(?,?,?,?,?,?)
                        """, [.text(key(bookID)), .int(Int64(ordinal)), .text(key(entry.id)), .text(entry.title),
                               .int(entry.offset), .int(Int64(entry.level))])
                }
            }
        }
    }

    func deleteTOC(bookID: UUID) throws {
        try locked { try execute("DELETE FROM toc_documents WHERE book_id=?", [.text(key(bookID))]) }
    }

    // MARK: - Settings and sync configuration

    func readerSettings() throws -> ReaderSettings {
        try locked {
            var values: [String: String] = [:]
            try query("SELECT key,value FROM settings") { statement in values[text(statement, 0)] = text(statement, 1) }
            var settings = ReaderSettings()
            if let value = values["reader.theme"], let theme = AppTheme(rawValue: value) { settings.theme = theme }
            if let value = values["reader.font_name"] { settings.fontName = value }
            if let value = values["reader.font_size"], let number = Double(value) { settings.fontSize = number }
            if let value = values["reader.line_spacing"], let number = Double(value) { settings.lineSpacing = number }
            if let value = values["reader.keep_screen_awake"] { settings.keepScreenAwake = value == "true" }
            if let value = values["reader.auto_page_seconds"], let number = Int(value) { settings.autoPageSeconds = number }
            if let value = values["reader.turn_sensitivity"], let number = Double(value) { settings.turnSensitivity = number }
            settings.normalize()
            return settings
        }
    }

    func saveReaderSettings(_ settings: ReaderSettings) throws {
        try locked {
            var normalized = settings
            normalized.normalize()
            try transaction {
                try setSetting("reader.theme", normalized.theme.rawValue)
                try setSetting("reader.font_name", normalized.fontName)
                try setSetting("reader.font_size", String(normalized.fontSize))
                try setSetting("reader.line_spacing", String(normalized.lineSpacing))
                try setSetting("reader.keep_screen_awake", String(normalized.keepScreenAwake))
                try setSetting("reader.auto_page_seconds", String(normalized.autoPageSeconds))
                try setSetting("reader.turn_sensitivity", String(normalized.turnSensitivity))
            }
        }
    }

    func syncString(for storageKey: String) -> String? {
        try? locked {
            var value: String?
            try query("SELECT \(try syncColumn(storageKey)) FROM sync_configuration WHERE singleton_id=1") {
                if sqlite3_column_type($0, 0) != SQLITE_NULL { value = text($0, 0) }
            }
            return value
        }
    }

    func syncBool(for storageKey: String) -> Bool {
        let value = syncString(for: storageKey)
        return value == "true" || value == "1"
    }

    func setSyncString(_ value: String?, for storageKey: String) throws {
        try locked {
            let column = try syncColumn(storageKey)
            let stored = storageKey == localSyncStartedKey
                ? value.map { ($0 == "true" || $0 == "1") ? "1" : "0" }
                : value
            try execute("UPDATE sync_configuration SET \(column)=? WHERE singleton_id=1",
                        [stored.map(Value.text) ?? .null])
        }
    }

    func setSyncBool(_ value: Bool, for storageKey: String) throws {
        try setSyncString(String(value), for: storageKey)
    }

    // MARK: - Sync caches

    func cachedIdentity(for book: Book) throws -> String? {
        try locked {
            var hash: String?
            try query("""
                SELECT book_hash FROM book_hash_cache WHERE book_id=? AND source_file_size=?
                    AND ABS(source_modified_at_ms-?)<1000
                """, [.text(key(book.id)), .int(book.fileSize), .int(milliseconds(book.modifiedAt))]) {
                hash = text($0, 0)
            }
            return hash
        }
    }

    func saveIdentity(_ hash: String, for book: Book) throws {
        try locked {
            try execute("""
                INSERT INTO book_hash_cache(book_id,source_file_size,source_modified_at_ms,book_hash) VALUES(?,?,?,?)
                ON CONFLICT(book_id) DO UPDATE SET source_file_size=excluded.source_file_size,
                source_modified_at_ms=excluded.source_modified_at_ms,book_hash=excluded.book_hash
                """, [.text(key(book.id)), .int(book.fileSize), .int(milliseconds(book.modifiedAt)), .text(hash)])
        }
    }

    func replaceRemote(_ items: [RemoteProgressSnapshot]) throws {
        try locked {
            let scope = currentAccountScope()
            try transaction {
                try execute("DELETE FROM remote_progress_cache")
                guard let scope else { return }
                for item in items {
                    try insertRemote(item, accountScope: scope)
                }
            }
        }
    }

    func cachedRemote() throws -> [RemoteProgressSnapshot] {
        try locked {
            guard let scope = currentAccountScope() else { return [] }
            var result: [RemoteProgressSnapshot] = []
            try query("""
                SELECT book_hash,file_size,offset_bytes,read_at_ms,version,source_device_id,
                       source_device_name,source_platform FROM remote_progress_cache WHERE account_scope=?
                """, [.text(scope)]) { statement in
                guard let deviceID = UUID(uuidString: text(statement, 5)) else { return }
                let size = sqlite3_column_int64(statement, 1)
                let offset = sqlite3_column_int64(statement, 2)
                result.append(RemoteProgressSnapshot(
                    bookHash: text(statement, 0), fileSize: size, offset: offset,
                    progress: size > 0 ? Double(offset) / Double(size) : 0,
                    readAtMs: sqlite3_column_int64(statement, 3), version: text(statement, 4),
                    device: SyncDevice(deviceId: deviceID, deviceName: text(statement, 6), platform: text(statement, 7))
                ))
            }
            return result
        }
    }

    func clearRemote() throws {
        try locked { try execute("DELETE FROM remote_progress_cache") }
    }

    // MARK: - Schema and legacy migration

    private func createSchema() throws {
        try execute("PRAGMA user_version = \(Self.schemaVersion)")
        try execute("""
            CREATE TABLE IF NOT EXISTS books(
                local_id TEXT PRIMARY KEY,title TEXT NOT NULL,source_name TEXT NOT NULL,author TEXT NOT NULL,
                relative_path TEXT NOT NULL UNIQUE,file_size INTEGER NOT NULL CHECK(file_size>=0),
                source_modified_at_ms INTEGER,encoding TEXT NOT NULL)
            """)
        try execute("""
            CREATE TABLE IF NOT EXISTS reading_progress(
                book_id TEXT PRIMARY KEY REFERENCES books(local_id) ON DELETE CASCADE,
                offset_bytes INTEGER NOT NULL CHECK(offset_bytes>=0),read_at_ms INTEGER)
            """)
        try execute("""
            CREATE TABLE IF NOT EXISTS bookmarks(
                local_id TEXT PRIMARY KEY,book_id TEXT NOT NULL REFERENCES books(local_id) ON DELETE CASCADE,
                offset_bytes INTEGER NOT NULL CHECK(offset_bytes>=0),excerpt TEXT NOT NULL,created_at_ms INTEGER NOT NULL,
                UNIQUE(book_id,offset_bytes))
            """)
        try execute("""
            CREATE TABLE IF NOT EXISTS toc_documents(
                book_id TEXT PRIMARY KEY REFERENCES books(local_id) ON DELETE CASCADE,
                source_file_size INTEGER NOT NULL,source_modified_at_ms INTEGER NOT NULL,encoding TEXT,
                generator_schema_version INTEGER NOT NULL)
            """)
        try execute("""
            CREATE TABLE IF NOT EXISTS toc_entries(
                book_id TEXT NOT NULL REFERENCES toc_documents(book_id) ON DELETE CASCADE,ordinal INTEGER NOT NULL,
                entry_id TEXT NOT NULL,title TEXT NOT NULL,offset_bytes INTEGER NOT NULL,level INTEGER NOT NULL,
                PRIMARY KEY(book_id,ordinal))
            """)
        try execute("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        try execute("""
            CREATE TABLE IF NOT EXISTS sync_configuration(
                singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1),configured_email TEXT,authenticated_email TEXT,
                device_id TEXT,device_name TEXT,server_url TEXT,has_started INTEGER NOT NULL DEFAULT 0 CHECK(has_started IN (0,1)),
                credential_server_url TEXT,active_email TEXT,active_device_name TEXT)
            """)
        try execute("INSERT OR IGNORE INTO sync_configuration(singleton_id) VALUES(1)")
        try execute("""
            CREATE TABLE IF NOT EXISTS book_hash_cache(
                book_id TEXT PRIMARY KEY REFERENCES books(local_id) ON DELETE CASCADE,source_file_size INTEGER NOT NULL,
                source_modified_at_ms INTEGER NOT NULL,book_hash TEXT NOT NULL)
            """)
        try execute("""
            CREATE TABLE IF NOT EXISTS remote_progress_cache(
                account_scope TEXT NOT NULL,book_hash TEXT NOT NULL,file_size INTEGER NOT NULL,offset_bytes INTEGER NOT NULL,
                read_at_ms INTEGER NOT NULL,version TEXT NOT NULL,source_device_id TEXT NOT NULL,
                source_device_name TEXT NOT NULL,source_platform TEXT NOT NULL,fetched_at_ms INTEGER,
                PRIMARY KEY(account_scope,book_hash,file_size))
            """)
        try execute("""
            CREATE TABLE IF NOT EXISTS pending_file_deletions(
                relative_path TEXT PRIMARY KEY,requested_at_ms INTEGER NOT NULL)
            """)
        try execute("""
            CREATE TABLE IF NOT EXISTS legacy_migrations(
                migration_id TEXT PRIMARY KEY,source_fingerprint TEXT NOT NULL,completed_at_ms INTEGER NOT NULL,
                imported_rows INTEGER NOT NULL)
            """)
    }

    private func migrateLegacyIfNeeded() throws {
        let fingerprint = try legacyFingerprint()
        var existing: String?
        try query("SELECT source_fingerprint FROM legacy_migrations WHERE migration_id=?",
                  [.text(Self.migrationID)]) { existing = text($0, 0) }
        if let existing {
            guard existing == fingerprint else { throw LocalDatabaseError.migrationSourceChanged }
            return
        }

        let legacy = try loadLegacyData()
        try transaction {
            var imported = 0
            for book in legacy.books {
                try validate(book: book)
                try upsertBook(book)
                try execute("INSERT INTO reading_progress(book_id,offset_bytes,read_at_ms) VALUES(?,?,?)",
                            [.text(key(book.id)), .int(clamp(book.offset, size: book.fileSize)), nullableDate(book.updatedAt)])
                imported += 2
            }
            let bookIDs = Set(legacy.books.map(\.id))
            for bookmark in legacy.bookmarks {
                guard bookIDs.contains(bookmark.bookID) else {
                    throw LocalDatabaseError.invalidLegacyData("书签无法关联现存书籍")
                }
                try execute("INSERT INTO bookmarks(local_id,book_id,offset_bytes,excerpt,created_at_ms) VALUES(?,?,?,?,?)",
                            [.text(key(bookmark.id)), .text(key(bookmark.bookID)), .int(bookmark.offset),
                             .text(bookmark.excerpt), .int(milliseconds(bookmark.createdAt))])
                imported += 1
            }
            for (bookID, document) in legacy.toc where bookIDs.contains(bookID) {
                try? saveTOC(document, bookID: bookID)
            }
            if let settings = legacy.readerSettings { try saveReaderSettings(settings) }
            for (storageKey, value) in legacy.syncConfiguration { try setSyncString(value, for: storageKey) }
            for (bookID, entry) in legacy.syncState.hashes where bookIDs.contains(bookID) {
                try execute("""
                    INSERT INTO book_hash_cache(book_id,source_file_size,source_modified_at_ms,book_hash) VALUES(?,?,?,?)
                    """, [.text(key(bookID)), .int(entry.fileSize), .int(milliseconds(entry.modifiedAt)), .text(entry.hash)])
            }
            if let scope = legacy.accountScope {
                for item in legacy.syncState.remote.values { try insertRemote(item, accountScope: scope) }
            }
            guard try scalar("SELECT COUNT(*) FROM books") == legacy.books.count,
                  try scalar("SELECT COUNT(*) FROM bookmarks") == legacy.bookmarks.count else {
                throw LocalDatabaseError.invalidLegacyData("迁移后的正式数据计数不一致")
            }
            try execute("INSERT INTO legacy_migrations VALUES(?,?,?,?)",
                        [.text(Self.migrationID), .text(fingerprint), .int(milliseconds(.now)), .int(Int64(imported))])
        }
    }

    private struct LegacyData {
        let books: [Book]
        let bookmarks: [Bookmark]
        let toc: [UUID: TocDocument]
        let readerSettings: ReaderSettings?
        let syncConfiguration: [String: String]
        let syncState: LegacySyncState
        let accountScope: String?
    }

    private func loadLegacyData() throws -> LegacyData {
        let metadata = root.appending(path: "Metadata", directoryHint: .isDirectory)
        let snapshotURL = metadata.appending(path: "books.json")
        let lastGoodURL = metadata.appending(path: "books.last-good.json")
        let snapshot: LibrarySnapshot
        if FileManager.default.fileExists(atPath: snapshotURL.path) {
            if let value = decode(LibrarySnapshot.self, at: snapshotURL) { snapshot = value }
            else if let value = decode(LibrarySnapshot.self, at: lastGoodURL) { snapshot = value }
            else { throw LocalDatabaseError.invalidLegacyData("书库主快照与恢复快照均无法读取") }
        } else if FileManager.default.fileExists(atPath: lastGoodURL.path) {
            guard let value = decode(LibrarySnapshot.self, at: lastGoodURL) else {
                throw LocalDatabaseError.invalidLegacyData("书库恢复快照无法读取")
            }
            snapshot = value
        } else {
            snapshot = LibrarySnapshot()
        }
        let visibleBooks = snapshot.books.filter { !snapshot.tombstones.contains($0.id) }
        let bookmarksURL = metadata.appending(path: "bookmarks.json")
        let bookmarks: BookmarksSnapshot
        if FileManager.default.fileExists(atPath: bookmarksURL.path) {
            guard let decoded = decode(BookmarksSnapshot.self, at: bookmarksURL) else {
                throw LocalDatabaseError.invalidLegacyData("书签快照无法读取")
            }
            bookmarks = decoded
        } else {
            bookmarks = BookmarksSnapshot()
        }

        var toc: [UUID: TocDocument] = [:]
        let tocDirectory = root.appending(path: "TOC", directoryHint: .isDirectory)
        for book in visibleBooks {
            if let document = decode(TocDocument.self, at: tocDirectory.appending(path: "\(book.id.uuidString).json")),
               document.fileSize == book.fileSize,
               abs(document.modifiedAt.timeIntervalSince(book.modifiedAt)) < 1 {
                toc[book.id] = document
            }
        }

        var readerSettings: ReaderSettings?
        if let data = defaults.data(forKey: "reader.settings.v1"),
           var value = try? JSONDecoder().decode(ReaderSettings.self, from: data) {
            value.normalize()
            readerSettings = value
        }
        let syncKeys = [
            SyncServerConfiguration.storageKey, SyncServerConfiguration.credentialServerKey,
            localSyncDeviceNameKey, localSyncEmailKey, localSyncStartedKey, "sync.device.id.v1"
        ]
        var configuration: [String: String] = [:]
        for key in syncKeys {
            if let value = defaults.string(forKey: key) { configuration[key] = value }
            else if defaults.object(forKey: key) is Bool { configuration[key] = String(defaults.bool(forKey: key)) }
        }
        let syncStateURL = root.appending(path: "Sync", directoryHint: .isDirectory).appending(path: "sync-state.json")
        let syncState = (try? Data(contentsOf: syncStateURL)).flatMap { try? JSONDecoder().decode(LegacySyncState.self, from: $0) }
            ?? LegacySyncState()
        let accountScope = configuration[localSyncEmailKey]?.lowercased()
        return LegacyData(books: visibleBooks, bookmarks: bookmarks.bookmarks, toc: toc,
                          readerSettings: readerSettings, syncConfiguration: configuration,
                          syncState: syncState, accountScope: accountScope)
    }

    private func legacyFingerprint() throws -> String {
        var hasher = SHA256()
        let paths = ["Metadata/books.json", "Metadata/books.last-good.json", "Metadata/bookmarks.json", "Sync/sync-state.json"]
        for path in paths {
            let url = root.appending(path: path)
            hasher.update(data: Data(path.utf8))
            if let data = try? Data(contentsOf: url) { hasher.update(data: data) }
        }
        let defaultsKeys = ["reader.settings.v1", SyncServerConfiguration.storageKey,
                            SyncServerConfiguration.credentialServerKey, localSyncDeviceNameKey,
                            localSyncEmailKey, localSyncStartedKey, "sync.device.id.v1"]
        for key in defaultsKeys {
            hasher.update(data: Data(key.utf8))
            if let data = defaults.data(forKey: key) { hasher.update(data: data) }
            else if let value = defaults.string(forKey: key) { hasher.update(data: Data(value.utf8)) }
            else if let value = defaults.object(forKey: key) as? NSNumber { hasher.update(data: Data(value.stringValue.utf8)) }
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private func validate(book: Book) throws {
        guard book.fileSize >= 0, book.offset >= 0, book.offset <= book.fileSize,
              !book.relativePath.hasPrefix("/"), !book.relativePath.split(separator: "/").contains(".."),
              book.relativePath.hasPrefix("Books/") else {
            throw LocalDatabaseError.invalidLegacyData("书籍 metadata 不符合本地存储约束")
        }
        let url = root.appending(path: book.relativePath)
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw LocalDatabaseError.invalidLegacyData("书籍正文缺失")
        }
    }

    // MARK: - SQLite helpers

    private func upsertBook(_ book: Book) throws {
        try execute("""
            INSERT INTO books(local_id,title,source_name,author,relative_path,file_size,source_modified_at_ms,encoding)
            VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(local_id) DO UPDATE SET title=excluded.title,
            source_name=excluded.source_name,author=excluded.author,relative_path=excluded.relative_path,
            file_size=excluded.file_size,source_modified_at_ms=excluded.source_modified_at_ms,encoding=excluded.encoding
            """, [.text(key(book.id)), .text(book.title), .text(book.sourceName), .text(book.author),
                   .text(book.relativePath), .int(book.fileSize), .int(milliseconds(book.modifiedAt)),
                   .text(book.encoding.rawValue)])
    }

    private func insertRemote(_ item: RemoteProgressSnapshot, accountScope: String) throws {
        try execute("""
            INSERT INTO remote_progress_cache(account_scope,book_hash,file_size,offset_bytes,read_at_ms,version,
            source_device_id,source_device_name,source_platform,fetched_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?)
            """, [.text(accountScope), .text(item.bookHash), .int(item.fileSize), .int(item.offset),
                   .int(item.readAtMs), .text(item.version), .text(key(item.device.deviceId)),
                   .text(item.device.deviceName), .text(item.device.platform), .int(milliseconds(.now))])
    }

    private func currentAccountScope() -> String? {
        syncString(for: localSyncEmailKey)?.lowercased()
    }

    private func retryPendingFileDeletions() throws {
        var completed: [String] = []
        try query("SELECT relative_path FROM pending_file_deletions") { statement in
            let relativePath = text(statement, 0)
            let file = root.appending(path: relativePath)
            if !FileManager.default.fileExists(atPath: file.path) || (try? FileManager.default.removeItem(at: file)) != nil {
                completed.append(relativePath)
            }
        }
        for relativePath in completed {
            try execute("DELETE FROM pending_file_deletions WHERE relative_path=?", [.text(relativePath)])
        }
    }

    private func setSetting(_ key: String, _ value: String) throws {
        try execute("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                    [.text(key), .text(value)])
    }

    private func syncColumn(_ storageKey: String) throws -> String {
        switch storageKey {
        case SyncServerConfiguration.storageKey: "server_url"
        case SyncServerConfiguration.credentialServerKey: "credential_server_url"
        case localSyncDeviceNameKey: "device_name"
        case localSyncEmailKey: "configured_email"
        case localSyncStartedKey: "has_started"
        case "sync.device.id.v1": "device_id"
        default: throw LocalDatabaseError.sqlite("未知同步配置键")
        }
    }
    private func key(_ id: UUID) -> String { id.uuidString.lowercased() }
    private func clamp(_ offset: Int64, size: Int64) -> Int64 { min(max(offset, 0), max(size, 0)) }
    private func milliseconds(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1_000).rounded()) }
    private func nullableDate(_ date: Date) -> Value { date.timeIntervalSince1970 > 0 ? .int(milliseconds(date)) : .null }
    private func date(_ statement: OpaquePointer, _ column: Int32) -> Date? {
        guard sqlite3_column_type(statement, column) != SQLITE_NULL else { return nil }
        return Date(timeIntervalSince1970: Double(sqlite3_column_int64(statement, column)) / 1_000)
    }
    private func text(_ statement: OpaquePointer, _ column: Int32) -> String {
        guard let value = sqlite3_column_text(statement, column) else { return "" }
        return String(cString: value)
    }

    private func decode<T: Decodable>(_ type: T.Type, at url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(type, from: data)
    }

    private func scalar(_ sql: String) throws -> Int {
        var result = 0
        try query(sql) { result = Int(sqlite3_column_int64($0, 0)) }
        return result
    }

    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    private func transaction(_ body: () throws -> Void) throws {
        if sqlite3_get_autocommit(connection) == 0 {
            savepointSequence += 1
            let name = "xlib_nested_\(savepointSequence)"
            try execute("SAVEPOINT \(name)")
            do {
                try body()
                try execute("RELEASE SAVEPOINT \(name)")
            } catch {
                try? execute("ROLLBACK TO SAVEPOINT \(name)")
                try? execute("RELEASE SAVEPOINT \(name)")
                throw error
            }
            return
        }
        try execute("BEGIN IMMEDIATE")
        do {
            try body()
            try execute("COMMIT")
        } catch {
            try? execute("ROLLBACK")
            throw error
        }
    }

    private func execute(_ sql: String, _ values: [Value] = []) throws {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(connection, sql, -1, &statement, nil) == SQLITE_OK, let statement else {
            throw databaseError()
        }
        defer { sqlite3_finalize(statement) }
        try bind(values, to: statement)
        let result = sqlite3_step(statement)
        guard result == SQLITE_DONE || result == SQLITE_ROW else { throw databaseError() }
    }

    private func query(_ sql: String, _ values: [Value] = [], row: (OpaquePointer) throws -> Void) throws {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(connection, sql, -1, &statement, nil) == SQLITE_OK, let statement else {
            throw databaseError()
        }
        defer { sqlite3_finalize(statement) }
        try bind(values, to: statement)
        while true {
            let result = sqlite3_step(statement)
            if result == SQLITE_DONE { return }
            guard result == SQLITE_ROW else { throw databaseError() }
            try row(statement)
        }
    }

    private func bind(_ values: [Value], to statement: OpaquePointer) throws {
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for (offset, value) in values.enumerated() {
            let index = Int32(offset + 1)
            let result: Int32
            switch value {
            case .int(let value): result = sqlite3_bind_int64(statement, index, value)
            case .double(let value): result = sqlite3_bind_double(statement, index, value)
            case .text(let value): result = sqlite3_bind_text(statement, index, value, -1, transient)
            case .null: result = sqlite3_bind_null(statement, index)
            }
            guard result == SQLITE_OK else { throw databaseError() }
        }
    }

    private func databaseError() -> LocalDatabaseError {
        LocalDatabaseError.sqlite(String(cString: sqlite3_errmsg(connection)))
    }
}
