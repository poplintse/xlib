import AppleShared
import Foundation

enum BookmarkError: LocalizedError {
    case duplicatePosition
    var errorDescription: String? { "当前位置已有书签" }
}

actor LibraryStore {
    struct Paths: Sendable {
        let root: URL
        var books: URL { root.appending(path: "Books", directoryHint: .isDirectory) }
        var metadata: URL { root.appending(path: "Metadata", directoryHint: .isDirectory) }
    }

    private let paths: Paths
    private let database: LocalDatabase
    private var loaded = false

    init(root: URL? = nil, database: LocalDatabase) {
        self.database = database
        if let root {
            paths = Paths(root: root)
        } else {
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            paths = Paths(root: base.appending(path: "XLibReader", directoryHint: .isDirectory))
        }
    }

    func load() throws -> [Book] {
        try prepareIfNeeded()
        return try database.loadBooks()
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
                // Importing is not a reading event; retain the existing Date storage format.
                updatedAt: Date(timeIntervalSince1970: 0),
                schemaVersion: Book.schemaVersion
            )
            book.title = book.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "未命名书籍" : book.title
            do { try database.insertBook(book) }
            catch {
                try? FileManager.default.removeItem(at: destination)
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
        try database.updateBook(book)
    }

    func saveProgress(bookID: UUID, offset: Int64, updatedAt: Date = .now) throws {
        try prepareIfNeeded()
        try database.saveProgress(bookID: bookID, offset: offset, updatedAt: updatedAt)
    }

    func deleteBooks(ids: Set<UUID>) throws {
        try prepareIfNeeded()
        try database.deleteBooks(ids: ids)
    }

    func bookmarks(for bookID: UUID) throws -> [Bookmark] {
        try prepareIfNeeded()
        return try database.bookmarks(bookID: bookID)
    }

    func addBookmark(bookID: UUID, offset: Int64, excerpt: String) throws -> Bookmark {
        try prepareIfNeeded()
        let bookmark = Bookmark(id: UUID(), bookID: bookID, offset: offset, excerpt: excerpt, createdAt: .now)
        try database.addBookmark(bookmark)
        return bookmark
    }

    func removeBookmark(id: UUID) throws {
        try prepareIfNeeded()
        try database.removeBookmark(id: id)
    }

    func cachedTOC(for book: Book) throws -> [TocEntry]? {
        try prepareIfNeeded()
        return try database.cachedTOC(for: book)
    }

    func booksWithCachedTOC(_ books: [Book]) throws -> Set<UUID> {
        try prepareIfNeeded()
        return try database.booksWithCachedTOC(books)
    }

    func saveTOC(_ entries: [TocEntry], for book: Book) throws {
        try prepareIfNeeded()
        let document = TocDocument(
            schemaVersion: TocDocument.schemaVersion,
            fileSize: book.fileSize,
            modifiedAt: book.modifiedAt,
            entries: entries
        )
        try database.saveTOC(document, bookID: book.id)
    }

    func deleteTOC(for bookID: UUID) throws {
        try prepareIfNeeded()
        try database.deleteTOC(bookID: bookID)
    }

    private func prepareIfNeeded() throws {
        guard !loaded else { return }
        try FileManager.default.createDirectory(at: paths.books, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: paths.metadata, withIntermediateDirectories: true)
        loaded = true
        let contents = try FileManager.default.contentsOfDirectory(at: paths.books, includingPropertiesForKeys: nil)
        for url in contents where url.lastPathComponent.hasSuffix(".importing") { try? FileManager.default.removeItem(at: url) }
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
