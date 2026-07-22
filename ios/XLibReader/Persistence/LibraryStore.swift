import Foundation

actor LibraryStore {
    struct Paths: Sendable {
        let root: URL
        var books: URL { root.appending(path: "Books", directoryHint: .isDirectory) }
        var metadata: URL { root.appending(path: "Metadata", directoryHint: .isDirectory) }
        var toc: URL { root.appending(path: "TOC", directoryHint: .isDirectory) }
        var snapshot: URL { metadata.appending(path: "books.json") }
        var lastGoodSnapshot: URL { metadata.appending(path: "books.last-good.json") }
        var bookmarks: URL { metadata.appending(path: "bookmarks.json") }
    }

    private let paths: Paths
    private var snapshot = LibrarySnapshot()
    private var bookmarkSnapshot = BookmarksSnapshot()
    private var loaded = false

    init(root: URL? = nil) {
        if let root {
            paths = Paths(root: root)
        } else {
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            paths = Paths(root: base.appending(path: "XLibReader", directoryHint: .isDirectory))
        }
    }

    func load() throws -> [Book] {
        try prepareIfNeeded()
        return visibleBooks()
    }

    func url(for book: Book) -> URL { paths.root.appending(path: book.relativePath) }

    func importBook(from sourceURL: URL) throws -> Book {
        try prepareIfNeeded()
        let scoped = sourceURL.startAccessingSecurityScopedResource()
        defer { if scoped { sourceURL.stopAccessingSecurityScopedResource() } }

        let id = UUID()
        let temporary = paths.books.appending(path: ".\(id.uuidString).importing")
        let destination = paths.books.appending(path: "\(id.uuidString).txt")
        try coordinatedCopy(from: sourceURL, to: temporary)
        do {
            let encoding = try TextEncoding.detect(at: temporary)
            let values = try temporary.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
            try FileManager.default.moveItem(at: temporary, to: destination)
            var book = Book(
                id: id,
                title: sourceURL.deletingPathExtension().lastPathComponent,
                sourceName: sourceURL.lastPathComponent,
                author: "",
                relativePath: "Books/\(id.uuidString).txt",
                fileSize: Int64(values.fileSize ?? 0),
                modifiedAt: values.contentModificationDate ?? .now,
                encoding: encoding,
                offset: 0,
                updatedAt: .now,
                schemaVersion: Book.schemaVersion
            )
            book.title = book.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "未命名书籍" : book.title
            snapshot.books.append(book)
            do { try persistSnapshot() } catch {
                try? FileManager.default.removeItem(at: destination)
                snapshot.books.removeAll { $0.id == id }
                throw error
            }
            return book
        } catch {
            try? FileManager.default.removeItem(at: temporary)
            throw error
        }
    }

    func updateBook(_ book: Book) throws {
        try prepareIfNeeded()
        guard let index = snapshot.books.firstIndex(where: { $0.id == book.id }) else { return }
        snapshot.books[index] = book
        try persistSnapshot()
    }

    func saveProgress(bookID: UUID, offset: Int64, updatedAt: Date = .now) throws {
        try prepareIfNeeded()
        guard let index = snapshot.books.firstIndex(where: { $0.id == bookID }) else { return }
        snapshot.books[index].offset = min(snapshot.books[index].fileSize, max(0, offset))
        snapshot.books[index].updatedAt = updatedAt
        try persistSnapshot()
    }

    func deleteBooks(ids: Set<UUID>) throws {
        try prepareIfNeeded()
        snapshot.tombstones.append(contentsOf: ids.filter { !snapshot.tombstones.contains($0) })
        try persistSnapshot()
        for id in ids {
            if let book = snapshot.books.first(where: { $0.id == id }) {
                try? FileManager.default.removeItem(at: url(for: book))
            }
            try? FileManager.default.removeItem(at: paths.toc.appending(path: "\(id.uuidString).json"))
        }
        bookmarkSnapshot.bookmarks.removeAll { ids.contains($0.bookID) }
        snapshot.books.removeAll { ids.contains($0.id) }
        snapshot.tombstones.removeAll { ids.contains($0) }
        try persistBookmarks()
        try persistSnapshot()
    }

    func bookmarks(for bookID: UUID) throws -> [Bookmark] {
        try prepareIfNeeded()
        return bookmarkSnapshot.bookmarks.filter { $0.bookID == bookID }.sorted { $0.offset < $1.offset }
    }

    func addBookmark(bookID: UUID, offset: Int64, excerpt: String) throws -> Bookmark {
        try prepareIfNeeded()
        let bookmark = Bookmark(id: UUID(), bookID: bookID, offset: offset, excerpt: excerpt, createdAt: .now)
        bookmarkSnapshot.bookmarks.append(bookmark)
        try persistBookmarks()
        return bookmark
    }

    func removeBookmark(id: UUID) throws {
        try prepareIfNeeded()
        bookmarkSnapshot.bookmarks.removeAll { $0.id == id }
        try persistBookmarks()
    }

    func cachedTOC(for book: Book) throws -> [TocEntry]? {
        try prepareIfNeeded()
        let tocURL = tocURL(for: book.id)
        guard let document = try decode(TocDocument.self, primary: tocURL, fallback: nil),
              tocDocument(document, matches: book) else { return nil }
        return document.entries
    }

    func booksWithCachedTOC(_ books: [Book]) throws -> Set<UUID> {
        try prepareIfNeeded()
        return Set(books.compactMap { book in
            guard let document = try? decode(TocDocument.self, primary: tocURL(for: book.id), fallback: nil),
                  tocDocument(document, matches: book) else { return nil }
            return book.id
        })
    }

    func saveTOC(_ entries: [TocEntry], for book: Book) throws {
        try prepareIfNeeded()
        let document = TocDocument(
            schemaVersion: TocDocument.schemaVersion,
            fileSize: book.fileSize,
            modifiedAt: book.modifiedAt,
            entries: entries
        )
        try atomicWrite(document, to: tocURL(for: book.id))
    }

    func deleteTOC(for bookID: UUID) throws {
        try prepareIfNeeded()
        let url = tocURL(for: bookID)
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        try FileManager.default.removeItem(at: url)
    }

    private func prepareIfNeeded() throws {
        guard !loaded else { return }
        try FileManager.default.createDirectory(at: paths.books, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: paths.metadata, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: paths.toc, withIntermediateDirectories: true)
        try? (paths.toc as NSURL).setResourceValue(true, forKey: .isExcludedFromBackupKey)
        snapshot = try decode(LibrarySnapshot.self, primary: paths.snapshot, fallback: paths.lastGoodSnapshot) ?? LibrarySnapshot()
        bookmarkSnapshot = try decode(BookmarksSnapshot.self, primary: paths.bookmarks, fallback: nil) ?? BookmarksSnapshot()
        loaded = true
        try repairInterruptedWork()
    }

    private func repairInterruptedWork() throws {
        let contents = try FileManager.default.contentsOfDirectory(at: paths.books, includingPropertiesForKeys: nil)
        for url in contents where url.lastPathComponent.hasSuffix(".importing") { try? FileManager.default.removeItem(at: url) }
        if !snapshot.tombstones.isEmpty { try deleteBooks(ids: Set(snapshot.tombstones)) }
    }

    private func visibleBooks() -> [Book] {
        snapshot.books.filter { !snapshot.tombstones.contains($0.id) }.sorted { $0.updatedAt > $1.updatedAt }
    }

    private func persistSnapshot() throws {
        if FileManager.default.fileExists(atPath: paths.snapshot.path) {
            try? FileManager.default.removeItem(at: paths.lastGoodSnapshot)
            try FileManager.default.copyItem(at: paths.snapshot, to: paths.lastGoodSnapshot)
        }
        try atomicWrite(snapshot, to: paths.snapshot)
    }

    private func persistBookmarks() throws { try atomicWrite(bookmarkSnapshot, to: paths.bookmarks) }

    private func tocURL(for bookID: UUID) -> URL {
        paths.toc.appending(path: "\(bookID.uuidString).json")
    }

    private func tocDocument(_ document: TocDocument, matches book: Book) -> Bool {
        document.schemaVersion == TocDocument.schemaVersion
            && document.fileSize == book.fileSize
            && abs(document.modifiedAt.timeIntervalSince(book.modifiedAt)) < 1
    }

    private func atomicWrite<T: Encodable>(_ value: T, to url: URL) throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(value)
        let temp = url.deletingLastPathComponent().appending(path: ".\(UUID().uuidString).tmp")
        try data.write(to: temp, options: [.atomic, .completeFileProtection])
        if FileManager.default.fileExists(atPath: url.path) {
            _ = try FileManager.default.replaceItemAt(url, withItemAt: temp)
        } else {
            try FileManager.default.moveItem(at: temp, to: url)
        }
    }

    private func decode<T: Decodable>(_ type: T.Type, primary: URL, fallback: URL?) throws -> T? {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        for url in [primary, fallback].compactMap({ $0 }) where FileManager.default.fileExists(atPath: url.path) {
            if let value = try? decoder.decode(type, from: Data(contentsOf: url)) { return value }
        }
        return nil
    }

    private func coordinatedCopy(from source: URL, to destination: URL) throws {
        var coordinationError: NSError?
        var copyError: Error?
        NSFileCoordinator().coordinate(readingItemAt: source, options: [], error: &coordinationError) { coordinatedURL in
            do {
                let input = try FileHandle(forReadingFrom: coordinatedURL)
                FileManager.default.createFile(atPath: destination.path, contents: nil)
                let output = try FileHandle(forWritingTo: destination)
                defer { try? input.close(); try? output.close() }
                while true {
                    let chunk = try input.read(upToCount: 256 * 1_024) ?? Data()
                    if chunk.isEmpty { break }
                    try output.write(contentsOf: chunk)
                }
                try output.synchronize()
            } catch { copyError = error }
        }
        if let coordinationError { throw coordinationError }
        if let copyError { throw copyError }
    }
}
