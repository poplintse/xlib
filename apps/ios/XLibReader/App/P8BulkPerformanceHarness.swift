#if XLIB_P8_DEVICE_PERFORMANCE
import AppleShared
import Foundation
import OSLog

@MainActor
enum P8BulkPerformanceHarness {
    private static let targetBytes = 256 * 1_024
    private static let bookCount = 20
    private static let signposter = OSSignposter(
        subsystem: Bundle.main.bundleIdentifier ?? "XLibReader",
        category: "ReaderPerformance"
    )

    static func prepare(mode: String?, root: URL, store: LibraryStore) async throws {
        switch mode {
        case "prepare":
            try generateFixtures(root: root)
        case "import":
            try await importRemaining(root: root, store: store, signpostName: "BulkImport")
        case "interrupted":
            try generateFixtures(root: root)
            let sources = fixtureURLs(root: root)
            for source in sources.prefix(5) {
                _ = try await store.importBook(from: source)
            }
            let books = root.appending(path: "Books", directoryHint: .isDirectory)
            try FileManager.default.createDirectory(at: books, withIntermediateDirectories: true)
            let interrupted = books.appending(path: ".p8-interrupted.importing")
            try FileManager.default.copyItem(at: sources[5], to: interrupted)
            guard try await store.load().count == 5 else { throw HarnessError.unexpectedBookCount }
        case "recover":
            try await importRemaining(root: root, store: store, signpostName: "BulkImportRecovery")
        default:
            throw HarnessError.invalidMode
        }
    }

    private static func importRemaining(
        root: URL,
        store: LibraryStore,
        signpostName: StaticString
    ) async throws {
        let interval = signposter.beginInterval(signpostName)
        defer { signposter.endInterval(signpostName, interval) }

        let existing = try await store.load()
        let importedNames = Set(existing.map(\.sourceName))
        for source in fixtureURLs(root: root) where !importedNames.contains(source.lastPathComponent) {
            _ = try await store.importBook(from: source)
        }
        let finalBooks = try await store.load()
        guard finalBooks.count == bookCount else { throw HarnessError.unexpectedBookCount }
        guard try importingFileCount(root: root) == 0 else { throw HarnessError.retainedTemporaryFile }
    }

    private static func fixtureURLs(root: URL) -> [URL] {
        let sources = root.appending(path: "P8Sources", directoryHint: .isDirectory)
        return (1...bookCount).map { index in
            let encoding = encodingName(for: index)
            return sources.appending(path: String(format: "p8-bulk-%02d-%@.txt", index, encoding))
        }
    }

    private static func generateFixtures(root: URL) throws {
        let sources = root.appending(path: "P8Sources", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: sources, withIntermediateDirectories: true)
        for (position, url) in fixtureURLs(root: root).enumerated() where !FileManager.default.fileExists(atPath: url.path) {
            let book = position + 1
            let encoding = textEncoding(for: book)
            var data = Data()
            if encoding == .utf16LittleEndian { data.append(contentsOf: [0xFF, 0xFE]) }
            var line = 0
            while data.count < targetBytes {
                let chapter = line / 40 + 1
                let marker = line % 17 == 0 ? " P8NEEDLE" : ""
                let text = String(
                    format: "第%06d章 固定样本 %02d\n第%08d行 阅读性能验证文本 ABC123，包含中文标点。%@\n",
                    chapter,
                    book,
                    line,
                    marker
                )
                guard let encoded = text.data(using: encoding.foundationEncoding) else {
                    throw HarnessError.encodingFailed
                }
                data.append(encoded)
                line += 1
            }
            try data.write(to: url, options: .atomic)
        }
    }

    private static func textEncoding(for book: Int) -> TextEncoding {
        switch (book - 1) % 3 {
        case 0: .utf8
        case 1: .utf16LittleEndian
        default: .gb18030
        }
    }

    private static func encodingName(for book: Int) -> String {
        switch textEncoding(for: book) {
        case .utf8: "utf8"
        case .utf16LittleEndian: "utf16le"
        case .gb18030: "gb18030"
        case .utf16BigEndian: "utf16be"
        }
    }

    private static func importingFileCount(root: URL) throws -> Int {
        let books = root.appending(path: "Books", directoryHint: .isDirectory)
        return try FileManager.default.contentsOfDirectory(at: books, includingPropertiesForKeys: nil)
            .filter { $0.lastPathComponent.hasSuffix(".importing") }
            .count
    }

    private enum HarnessError: Error {
        case invalidMode
        case encodingFailed
        case unexpectedBookCount
        case retainedTemporaryFile
    }
}
#endif
