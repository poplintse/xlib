import AppleShared
import XCTest
@testable import XLibReader

@MainActor
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

    func testMigratesFormalDataSettingsAndSyncConfigurationIdempotently() throws {
        let bookID = UUID()
        let bookmarkID = UUID()
        let modifiedAt = Date(timeIntervalSince1970: 1_700_000_000)
        let readAt = Date(timeIntervalSince1970: 1_700_000_100)
        let content = Data("第一章\n正文".utf8)
        try content.write(to: root.appending(path: "Books/\(bookID.uuidString).txt"))
        let book = Book(
            id: bookID, title: "迁移测试", sourceName: "legacy.txt", author: "作者",
            relativePath: "Books/\(bookID.uuidString).txt", fileSize: Int64(content.count),
            modifiedAt: modifiedAt, encoding: .utf8, offset: 3, updatedAt: readAt,
            schemaVersion: Book.schemaVersion
        )
        try write(LibrarySnapshot(books: [book]), to: root.appending(path: "Metadata/books.json"))
        let bookmark = Bookmark(id: bookmarkID, bookID: bookID, offset: 2, excerpt: "正文", createdAt: readAt)
        try write(BookmarksSnapshot(bookmarks: [bookmark]), to: root.appending(path: "Metadata/bookmarks.json"))

        var settings = ReaderSettings()
        settings.theme = .dark
        settings.fontSize = 25
        defaults.set(try JSONEncoder().encode(settings), forKey: "reader.settings.v1")
        defaults.set("reader@example.com", forKey: SyncConfigurationSession.emailKey)
        defaults.set(true, forKey: SyncConfigurationSession.hasStartedSyncKey)

        var database: LocalDatabase? = try LocalDatabase.open(root: root, defaults: defaults)
        XCTAssertEqual(try database?.loadBooks(), [book])
        XCTAssertEqual(try database?.bookmarks(bookID: bookID), [bookmark])
        XCTAssertEqual(try database?.readerSettings().theme, .dark)
        XCTAssertEqual(try database?.readerSettings().fontSize, 25)
        XCTAssertEqual(database?.syncString(for: SyncConfigurationSession.emailKey), "reader@example.com")
        XCTAssertEqual(database?.syncBool(for: SyncConfigurationSession.hasStartedSyncKey), true)
        let settingsStore = SettingsStore(defaults: defaults, database: database)
        settingsStore.update { $0.fontSize = 19 }
        XCTAssertEqual(try database?.readerSettings().fontSize, 19)
        let unchangedLegacySettings = try JSONDecoder().decode(
            ReaderSettings.self,
            from: try XCTUnwrap(defaults.data(forKey: "reader.settings.v1"))
        )
        XCTAssertEqual(unchangedLegacySettings.fontSize, 25)
        let configuration = SyncConfigurationSession(defaults: defaults, database: database)
        XCTAssertEqual(configuration.saveConfiguredEmail("new@example.com"), true)
        XCTAssertEqual(database?.syncString(for: SyncConfigurationSession.emailKey), "new@example.com")
        XCTAssertEqual(defaults.string(forKey: SyncConfigurationSession.emailKey), "reader@example.com")

        database = nil
        let reopened = try LocalDatabase.open(root: root, defaults: defaults)
        XCTAssertEqual(try reopened.loadBooks().count, 1)
        XCTAssertEqual(try reopened.bookmarks(bookID: bookID).count, 1)
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appending(path: "Metadata/books.json").path))
    }

    func testRejectsOrphanBookmarkWithoutPartialMigration() throws {
        let orphan = Bookmark(id: UUID(), bookID: UUID(), offset: 1, excerpt: "orphan", createdAt: .now)
        try write(BookmarksSnapshot(bookmarks: [orphan]), to: root.appending(path: "Metadata/bookmarks.json"))

        XCTAssertThrowsError(try LocalDatabase.open(root: root, defaults: defaults))
    }

    func testUsesLastGoodSnapshotWhenPrimarySnapshotIsCorrupt() throws {
        let bookID = UUID()
        let content = Data("正文".utf8)
        try content.write(to: root.appending(path: "Books/\(bookID.uuidString).txt"))
        let book = Book(id: bookID, title: "恢复", sourceName: "restore.txt", author: "",
                        relativePath: "Books/\(bookID.uuidString).txt", fileSize: Int64(content.count),
                        modifiedAt: Date(timeIntervalSince1970: 1_700_000_000), encoding: .utf8,
                        offset: 0, updatedAt: Date(timeIntervalSince1970: 0), schemaVersion: Book.schemaVersion)
        try Data("not-json".utf8).write(to: root.appending(path: "Metadata/books.json"))
        try write(LibrarySnapshot(books: [book]), to: root.appending(path: "Metadata/books.last-good.json"))

        let database = try LocalDatabase.open(root: root, defaults: defaults)
        XCTAssertEqual(try database.loadBooks().map(\.id), [bookID])
    }

    func testRejectsChangedLegacySourceAfterCompletedMigration() throws {
        var database: LocalDatabase? = try LocalDatabase.open(root: root, defaults: defaults)
        database = nil
        defaults.set("changed@example.com", forKey: SyncConfigurationSession.emailKey)

        XCTAssertThrowsError(try LocalDatabase.open(root: root, defaults: defaults)) { error in
            guard case LocalDatabaseError.migrationSourceChanged = error else {
                return XCTFail("unexpected error: \(error)")
            }
        }
    }

    private func write<T: Encodable>(_ value: T, to url: URL) throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        try encoder.encode(value).write(to: url)
    }
}
