import AppleShared
import CryptoKit
import SQLite3
import XCTest
@testable import XLibReader

final class LocalDatabaseTests: XCTestCase {
    private var root: URL!
    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appending(path: "LocalDatabaseTests-\(UUID().uuidString)")
        suiteName = "com.xlib.local-database-tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
        try FileManager.default.createDirectory(at: root.appending(path: "Books"), withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: root.appending(path: "Metadata"), withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let root { try? FileManager.default.removeItem(at: root) }
        if let suiteName { UserDefaults.standard.removePersistentDomain(forName: suiteName) }
    }

    @MainActor
    func testPersistsFormalDataSettingsAndSyncConfiguration() throws {
        let book = try makeBook(title: "SQLite")
        let bookmark = Bookmark(id: UUID(), bookID: book.id, offset: 2, excerpt: "正文", createdAt: .now)
        var database: LocalDatabase? = try LocalDatabase.open(root: root, defaults: defaults)
        try database?.insertBook(book)
        try database?.addBookmark(bookmark)
        let settingsStore = SettingsStore(database: try XCTUnwrap(database))
        settingsStore.update { $0.theme = .dark; $0.fontSize = 19 }
        let configuration = SyncConfigurationSession(database: try XCTUnwrap(database))
        XCTAssertEqual(configuration.saveConfiguredEmail("new@example.com"), true)

        database = nil
        let reopened = try LocalDatabase.open(root: root, defaults: defaults)
        XCTAssertEqual(try reopened.loadBooks(), [book])
        let bookmarks = try reopened.bookmarks(bookID: book.id)
        XCTAssertEqual(bookmarks.map(\.id), [bookmark.id])
        XCTAssertEqual(bookmarks.first?.bookID, bookmark.bookID)
        XCTAssertEqual(bookmarks.first?.offset, bookmark.offset)
        XCTAssertEqual(bookmarks.first?.excerpt, bookmark.excerpt)
        XCTAssertEqual(
            try XCTUnwrap(bookmarks.first).createdAt.timeIntervalSince1970,
            bookmark.createdAt.timeIntervalSince1970,
            accuracy: 0.001
        )
        XCTAssertEqual(try reopened.readerSettings().theme, .dark)
        XCTAssertEqual(try reopened.readerSettings().fontSize, 19)
        XCTAssertEqual(reopened.syncString(for: SyncConfigurationSession.emailKey), "new@example.com")
    }

    func testFreshDatabaseWithoutMigrationLedgerDoesNotDeleteLegacyData() throws {
        defaults.set(Data("unmigrated".utf8), forKey: "reader.settings.v1")
        let legacy = root.appending(path: "Metadata/books.json")
        try Data("[]".utf8).write(to: legacy)

        _ = try LocalDatabase.open(root: root, defaults: defaults)

        XCTAssertNotNil(defaults.data(forKey: "reader.settings.v1"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: legacy.path))
    }

    func testUpgradesV1AndCleansOnlyAllowlistedLegacyData() throws {
        let book = try makeBook(title: "Cleanup")
        try seedMigratedDatabase(book: book)
        defaults.set(Data("settings".utf8), forKey: "reader.settings.v1")
        defaults.set("reader@example.com", forKey: SyncConfigurationSession.emailKey)
        defaults.set("keep", forKey: "future.unknown.key")
        try writeLegacyFiles()
        try writeLedgerAndDowngrade(fingerprint: legacyFingerprint())

        var database: LocalDatabase? = try LocalDatabase.open(root: root, defaults: defaults)

        XCTAssertNil(defaults.object(forKey: "reader.settings.v1"))
        XCTAssertNil(defaults.object(forKey: SyncConfigurationSession.emailKey))
        XCTAssertEqual(defaults.string(forKey: "future.unknown.key"), "keep")
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appending(path: "Metadata/books.json").path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appending(path: "TOC/legacy.json").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appending(path: book.relativePath).path))
        XCTAssertEqual(try database?.loadBooks().map(\.id), [book.id])
        database = nil
        XCTAssertEqual(try cleanupState(), "completed")

        database = try LocalDatabase.open(root: root, defaults: defaults)
        XCTAssertEqual(try database?.loadBooks().map(\.id), [book.id])
        XCTAssertEqual(defaults.string(forKey: "future.unknown.key"), "keep")
    }

    func testInterruptedCleanupResumesWithoutDeletedSourceValues() throws {
        let book = try makeBook(title: "Resume")
        try seedMigratedDatabase(book: book)
        defaults.set(Data("settings".utf8), forKey: "reader.settings.v1")
        defaults.set("reader@example.com", forKey: SyncConfigurationSession.emailKey)
        let fingerprint = legacyFingerprint()
        try writeLedgerAndDowngrade(fingerprint: fingerprint)
        try withRawDatabase { database in
            try execute(database, """
                CREATE TABLE legacy_cleanup(
                    migration_id TEXT PRIMARY KEY REFERENCES legacy_migrations(migration_id),
                    source_fingerprint TEXT NOT NULL,state TEXT NOT NULL,started_at_ms INTEGER NOT NULL,
                    completed_at_ms INTEGER,removed_items INTEGER NOT NULL DEFAULT 0)
                """)
            try execute(database, "INSERT INTO legacy_cleanup VALUES('\(LocalDatabase.migrationID)','\(fingerprint)','in_progress',1,NULL,0)")
            try execute(database, "PRAGMA user_version=2")
        }
        defaults.removeObject(forKey: "reader.settings.v1")

        _ = try LocalDatabase.open(root: root, defaults: defaults)

        XCTAssertNil(defaults.object(forKey: SyncConfigurationSession.emailKey))
        XCTAssertEqual(try cleanupState(), "completed")
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appending(path: book.relativePath).path))
    }

    func testChangedSourceBlocksCleanupBeforeDeletion() throws {
        let book = try makeBook(title: "Changed")
        try seedMigratedDatabase(book: book)
        defaults.set("original@example.com", forKey: SyncConfigurationSession.emailKey)
        try writeLedgerAndDowngrade(fingerprint: legacyFingerprint())
        defaults.set("changed@example.com", forKey: SyncConfigurationSession.emailKey)

        XCTAssertThrowsError(try LocalDatabase.open(root: root, defaults: defaults)) { error in
            guard case LocalDatabaseError.migrationSourceChanged = error else {
                return XCTFail("unexpected error: \(error)")
            }
        }
        XCTAssertEqual(defaults.string(forKey: SyncConfigurationSession.emailKey), "changed@example.com")
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appending(path: book.relativePath).path))
    }

    func testMissingBookFileBlocksCleanupAndPreservesLegacy() throws {
        let book = try makeBook(title: "Missing")
        try seedMigratedDatabase(book: book)
        defaults.set("reader@example.com", forKey: SyncConfigurationSession.emailKey)
        try writeLedgerAndDowngrade(fingerprint: legacyFingerprint())
        try FileManager.default.removeItem(at: root.appending(path: book.relativePath))

        XCTAssertThrowsError(try LocalDatabase.open(root: root, defaults: defaults))
        XCTAssertEqual(defaults.string(forKey: SyncConfigurationSession.emailKey), "reader@example.com")
    }

    func testFullDatabaseRollsBackTheWholeBookWrite() throws {
        var database: LocalDatabase? = try LocalDatabase.open(root: root, defaults: defaults)
        let original = try makeBook(title: "Original")
        try database?.insertBook(original)
        try database?.constrainStorageToCurrentPagesForTesting()

        var oversized = try makeBook(title: String(repeating: "x", count: 2 * 1_024 * 1_024))
        oversized.offset = 1
        XCTAssertThrowsError(try database?.insertBook(oversized))

        let stored = try XCTUnwrap(database).loadBooks()
        XCTAssertEqual(stored.count, 1)
        XCTAssertEqual(stored.first?.id, original.id)
        XCTAssertEqual(stored.first?.title, original.title)
        database = nil
    }

    private func seedMigratedDatabase(book: Book) throws {
        var database: LocalDatabase? = try LocalDatabase.open(root: root, defaults: defaults)
        try database?.insertBook(book)
        database = nil
    }

    private func writeLedgerAndDowngrade(fingerprint: String) throws {
        try withRawDatabase { database in
            try execute(database, "INSERT INTO legacy_migrations VALUES('\(LocalDatabase.migrationID)','\(fingerprint)',1,2)")
            try execute(database, "DROP TABLE legacy_cleanup")
            try execute(database, "PRAGMA user_version=1")
        }
    }

    private func writeLegacyFiles() throws {
        try FileManager.default.createDirectory(at: root.appending(path: "TOC"), withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: root.appending(path: "Sync"), withIntermediateDirectories: true)
        try Data("books".utf8).write(to: root.appending(path: "Metadata/books.json"))
        try Data("last-good".utf8).write(to: root.appending(path: "Metadata/books.last-good.json"))
        try Data("bookmarks".utf8).write(to: root.appending(path: "Metadata/bookmarks.json"))
        try Data("toc".utf8).write(to: root.appending(path: "TOC/legacy.json"))
        try Data("sync".utf8).write(to: root.appending(path: "Sync/sync-state.json"))
    }

    private func legacyFingerprint() -> String {
        var hasher = SHA256()
        let paths = ["Metadata/books.json", "Metadata/books.last-good.json", "Metadata/bookmarks.json", "Sync/sync-state.json"]
        for path in paths {
            hasher.update(data: Data(path.utf8))
            if let data = try? Data(contentsOf: root.appending(path: path)) { hasher.update(data: data) }
        }
        let keys = ["reader.settings.v1", SyncServerConfiguration.storageKey,
                    SyncServerConfiguration.credentialServerKey, SyncConfigurationSession.deviceNameKey,
                    SyncConfigurationSession.emailKey, SyncConfigurationSession.hasStartedSyncKey, "sync.device.id.v1"]
        for key in keys {
            hasher.update(data: Data(key.utf8))
            if let data = defaults.data(forKey: key) { hasher.update(data: data) }
            else if let value = defaults.string(forKey: key) { hasher.update(data: Data(value.utf8)) }
            else if let value = defaults.object(forKey: key) as? NSNumber {
                hasher.update(data: Data(value.stringValue.utf8))
            }
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private func cleanupState() throws -> String {
        var result = ""
        try withRawDatabase { database in
            var statement: OpaquePointer?
            guard sqlite3_prepare_v2(database, "SELECT state FROM legacy_cleanup WHERE migration_id=?", -1, &statement, nil) == SQLITE_OK,
                  let statement else { throw LocalDatabaseError.sqlite("test query failed") }
            defer { sqlite3_finalize(statement) }
            sqlite3_bind_text(statement, 1, LocalDatabase.migrationID, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
            guard sqlite3_step(statement) == SQLITE_ROW, let value = sqlite3_column_text(statement, 0) else {
                throw LocalDatabaseError.sqlite("cleanup state missing")
            }
            result = String(cString: value)
        }
        return result
    }

    private func withRawDatabase(_ body: (OpaquePointer) throws -> Void) throws {
        var database: OpaquePointer?
        let path = root.appending(path: "Metadata/xlib.db").path
        guard sqlite3_open_v2(path, &database, SQLITE_OPEN_READWRITE, nil) == SQLITE_OK, let database else {
            throw LocalDatabaseError.sqlite("test database open failed")
        }
        defer { sqlite3_close(database) }
        try body(database)
    }

    private func execute(_ database: OpaquePointer, _ sql: String) throws {
        guard sqlite3_exec(database, sql, nil, nil, nil) == SQLITE_OK else {
            throw LocalDatabaseError.sqlite(String(cString: sqlite3_errmsg(database)))
        }
    }

    private func makeBook(title: String) throws -> Book {
        let id = UUID()
        let content = Data("第一章\n正文".utf8)
        let relativePath = "Books/\(id.uuidString).txt"
        try content.write(to: root.appending(path: relativePath))
        return Book(id: id, title: title, sourceName: "source.txt", author: "作者",
                    relativePath: relativePath, fileSize: Int64(content.count),
                    modifiedAt: Date(timeIntervalSince1970: 1_700_000_000), encoding: .utf8,
                    offset: 3, updatedAt: Date(timeIntervalSince1970: 1_700_000_100),
                    schemaVersion: Book.schemaVersion)
    }
}
