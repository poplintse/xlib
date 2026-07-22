import Foundation

struct Book: Codable, Identifiable, Hashable, Sendable {
    static let schemaVersion = 1

    let id: UUID
    var title: String
    var sourceName: String
    var author: String
    var relativePath: String
    var fileSize: Int64
    var modifiedAt: Date
    var encoding: TextEncoding
    var offset: Int64
    var updatedAt: Date
    var schemaVersion: Int

    var progress: Double {
        guard fileSize > 0 else { return 0 }
        return min(1, max(0, Double(offset) / Double(fileSize)))
    }

    var displayAuthor: String { author.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "佚名" : author }
}

struct Bookmark: Codable, Identifiable, Hashable, Sendable {
    let id: UUID
    let bookID: UUID
    let offset: Int64
    let excerpt: String
    let createdAt: Date
}

struct TocEntry: Codable, Identifiable, Hashable, Sendable {
    let id: UUID
    let title: String
    let offset: Int64
    let level: Int
}

struct TocDocument: Codable, Sendable {
    static let schemaVersion = 2

    let schemaVersion: Int
    let fileSize: Int64
    let modifiedAt: Date
    let entries: [TocEntry]
}

struct LibrarySnapshot: Codable, Sendable {
    var schemaVersion = 1
    var books: [Book] = []
    var tombstones: [UUID] = []
}

struct BookmarksSnapshot: Codable, Sendable {
    var schemaVersion = 1
    var bookmarks: [Bookmark] = []
}
