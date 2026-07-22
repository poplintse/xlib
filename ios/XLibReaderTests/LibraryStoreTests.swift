import Foundation
import XCTest
@testable import XLibReader

final class LibraryStoreTests: XCTestCase {
    func testImportEditProgressBookmarkAndDelete() async throws {
        let root = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        let source = FileManager.default.temporaryDirectory.appending(path: "\(UUID().uuidString).txt")
        try "第一章\n你好，世界🙂\n".data(using: .utf8)!.write(to: source)
        defer { try? FileManager.default.removeItem(at: root); try? FileManager.default.removeItem(at: source) }
        let store = LibraryStore(root: root)
        var book = try await store.importBook(from: source)
        let importedBooks = try await store.load()
        XCTAssertEqual(importedBooks.count, 1)
        XCTAssertEqual(book.encoding, .utf8)
        book.title = "新书名"
        try await store.updateBook(book)
        try await store.saveProgress(bookID: book.id, offset: 4)
        let mark = try await store.addBookmark(bookID: book.id, offset: 4, excerpt: "你好")
        let marks = try await store.bookmarks(for: book.id)
        let updatedBooks = try await store.load()
        XCTAssertEqual(marks, [mark])
        XCTAssertEqual(updatedBooks.first?.title, "新书名")
        XCTAssertEqual(updatedBooks.first?.offset, 4)
        try await store.deleteBooks(ids: [book.id])
        let remainingBooks = try await store.load()
        XCTAssertTrue(remainingBooks.isEmpty)
    }

    func testSettingsNormalization() async {
        await MainActor.run {
            let suite = UserDefaults(suiteName: UUID().uuidString)!
            let store = SettingsStore(defaults: suite)
            store.update { $0.fontSize = 100; $0.autoPageSeconds = 1 }
            XCTAssertEqual(store.settings.fontSize, 34)
            XCTAssertEqual(store.settings.autoPageSeconds, 3)
        }
    }

    func testScreenLockIconMatchesEnabledState() {
        var settings = ReaderSettings()
        XCTAssertEqual(settings.screenLockIconName, "lock.open")

        settings.keepScreenAwake = true
        XCTAssertEqual(settings.screenLockIconName, "lock.fill")
    }

    func testSettingsLoadsDataContainingRemovedAutomaticTOCPreference() async throws {
        let legacyJSON = """
        {
          "theme": "dark",
          "fontName": ".AppleSystemUIFont",
          "fontSize": 22,
          "lineSpacing": 4,
          "keepScreenAwake": false,
          "autoBuildTOC": true,
          "autoPageSeconds": 8,
          "turnSensitivity": 0.45
        }
        """

        await MainActor.run {
            let suite = UserDefaults(suiteName: UUID().uuidString)!
            suite.set(Data(legacyJSON.utf8), forKey: "reader.settings.v1")
            let store = SettingsStore(defaults: suite)

            XCTAssertEqual(store.settings.theme, .dark)
            XCTAssertEqual(store.settings.fontSize, 22)
        }
    }

    func testTOCCachePresenceAndDeletion() async throws {
        let root = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        let source = FileManager.default.temporaryDirectory.appending(path: "\(UUID().uuidString).txt")
        try "第一章 开始\n正文\n".data(using: .utf8)!.write(to: source)
        defer { try? FileManager.default.removeItem(at: root); try? FileManager.default.removeItem(at: source) }

        let store = LibraryStore(root: root)
        let book = try await store.importBook(from: source)
        var cachedBookIDs = try await store.booksWithCachedTOC([book])
        XCTAssertTrue(cachedBookIDs.isEmpty)

        try await store.saveTOC([], for: book)
        cachedBookIDs = try await store.booksWithCachedTOC([book])
        let emptyTOC = try await store.cachedTOC(for: book)
        XCTAssertEqual(cachedBookIDs, [book.id])
        XCTAssertEqual(emptyTOC, [])

        try await store.deleteTOC(for: book.id)
        cachedBookIDs = try await store.booksWithCachedTOC([book])
        let deletedTOC = try await store.cachedTOC(for: book)
        XCTAssertTrue(cachedBookIDs.isEmpty)
        XCTAssertNil(deletedTOC)
    }

    @MainActor
    func testLibraryModelGeneratesAndDeletesTOC() async throws {
        let root = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        let source = FileManager.default.temporaryDirectory.appending(path: "\(UUID().uuidString).txt")
        try "第一章 开始\n正文\n第二章 继续\n".data(using: .utf8)!.write(to: source)
        defer { try? FileManager.default.removeItem(at: root); try? FileManager.default.removeItem(at: source) }

        let store = LibraryStore(root: root)
        let book = try await store.importBook(from: source)
        let model = LibraryModel(store: store)
        await model.load()

        await model.generateTOC(ids: [book.id])
        let generatedTOC = try await store.cachedTOC(for: book)
        XCTAssertEqual(model.booksWithTOC, [book.id])
        XCTAssertEqual(generatedTOC?.count, 2)

        await model.deleteTOC(ids: [book.id])
        let deletedTOC = try await store.cachedTOC(for: book)
        XCTAssertTrue(model.booksWithTOC.isEmpty)
        XCTAssertNil(deletedTOC)
    }
}
