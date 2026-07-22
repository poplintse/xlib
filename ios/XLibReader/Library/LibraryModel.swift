import Foundation
import Observation
import UIKit

@MainActor
@Observable
final class LibraryModel {
    private(set) var books: [Book] = []
    private(set) var booksWithTOC = Set<UUID>()
    private(set) var tocBusyBookIDs = Set<UUID>()
    var isLoading = false
    var errorMessage: String?
    private let store: LibraryStore
    private let tocService = TocService()

    init(store: LibraryStore) { self.store = store }

    func load() async {
        isLoading = true
        defer { isLoading = false }
        do {
            books = try await store.load()
            booksWithTOC = try await store.booksWithCachedTOC(books)
        } catch { errorMessage = error.localizedDescription }
    }

    func importBook(from url: URL) async {
        let backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Import TXT")
        defer {
            if backgroundTask != .invalid { UIApplication.shared.endBackgroundTask(backgroundTask) }
        }
        isLoading = true
        defer { isLoading = false }
        do {
            _ = try await store.importBook(from: url)
            try await reloadLibraryState()
        } catch { errorMessage = error.localizedDescription }
    }

    func update(_ book: Book) async {
        do {
            try await store.updateBook(book)
            try await reloadLibraryState()
        } catch { errorMessage = error.localizedDescription }
    }

    func delete(ids: Set<UUID>) async {
        do {
            try await store.deleteBooks(ids: ids)
            try await reloadLibraryState()
        } catch { errorMessage = error.localizedDescription }
    }

    func generateTOC(ids: Set<UUID>) async {
        let targets = books.filter { ids.contains($0.id) && !booksWithTOC.contains($0.id) }
        guard !targets.isEmpty else { return }
        tocBusyBookIDs.formUnion(targets.map(\.id))
        defer { tocBusyBookIDs.subtract(targets.map(\.id)) }

        var failures: [String] = []
        for book in targets {
            do {
                let url = await store.url(for: book)
                let entries = try await tocService.build(url: url, book: book)
                try await store.saveTOC(entries, for: book)
                booksWithTOC.insert(book.id)
            } catch is CancellationError {
                return
            } catch {
                failures.append(book.title)
            }
        }
        if !failures.isEmpty { errorMessage = tocFailureMessage(action: "生成", titles: failures) }
    }

    func regenerateTOC(ids: Set<UUID>) async {
        let targets = books.filter { ids.contains($0.id) && booksWithTOC.contains($0.id) }
        guard !targets.isEmpty else { return }
        tocBusyBookIDs.formUnion(targets.map(\.id))
        defer { tocBusyBookIDs.subtract(targets.map(\.id)) }

        var failures: [String] = []
        for book in targets {
            do {
                try await store.deleteTOC(for: book.id)
                booksWithTOC.remove(book.id)

                let url = await store.url(for: book)
                let entries = try await tocService.build(url: url, book: book)
                try await store.saveTOC(entries, for: book)
                booksWithTOC.insert(book.id)
            } catch is CancellationError {
                return
            } catch {
                failures.append(book.title)
            }
        }
        if !failures.isEmpty { errorMessage = tocFailureMessage(action: "重新生成", titles: failures) }
    }

    func deleteTOC(ids: Set<UUID>) async {
        let targets = ids.intersection(booksWithTOC)
        guard !targets.isEmpty else { return }
        tocBusyBookIDs.formUnion(targets)
        defer { tocBusyBookIDs.subtract(targets) }

        var failures: [String] = []
        for id in targets {
            do {
                try await store.deleteTOC(for: id)
                booksWithTOC.remove(id)
            } catch {
                failures.append(books.first(where: { $0.id == id })?.title ?? "未知书籍")
            }
        }
        if !failures.isEmpty { errorMessage = tocFailureMessage(action: "删除", titles: failures) }
    }

    private func reloadLibraryState() async throws {
        books = try await store.load()
        booksWithTOC = try await store.booksWithCachedTOC(books)
    }

    private func tocFailureMessage(action: String, titles: [String]) -> String {
        if titles.count == 1 { return "《\(titles[0])》的目录\(action)失败，请稍后重试。" }
        return "有 \(titles.count) 本书的目录\(action)失败，请稍后重试。"
    }
}
