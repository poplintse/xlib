import Foundation
import Observation
import SwiftUI
import UIKit

struct ReaderWindow: Sendable {
    let pages: [ReaderPageDescriptor]
    let selectedIndex: Int
}

actor ReaderEngine {
    private let layout = ReaderLayoutService()
    private let paginationBytes = 192 * 1_024
    private var cache: CacheCombineStack?

    func buildWindow(
        url: URL,
        book: Book,
        targetOffset: Int64,
        spec: ReaderLayoutSpec
    ) async throws -> ReaderWindow {
        try Task.checkCancellation()
        let cache = CacheCombineStack(
            url: url,
            fileSize: book.fileSize,
            encoding: book.encoding
        )
        self.cache = cache
        let anchor = try await cache.bootstrap(around: targetOffset)
        let initialPages: [ReaderPageDescriptor]
        if anchor >= book.fileSize {
            let initialSnapshot = try await cache.snapshotBackward(
                endingAt: book.fileSize,
                maximumBytes: paginationBytes
            )
            initialPages = try await layout.paginateBackward(
                snapshot: initialSnapshot,
                spec: spec,
                maximumPages: PageSlidingWindow.maximumPages
            )
        } else {
            let initialSnapshot = try await cache.snapshotForward(
                from: anchor,
                maximumBytes: paginationBytes
            )
            initialPages = try await layout.paginate(
                snapshot: initialSnapshot,
                spec: spec,
                maximumPages: PageSlidingWindow.maximumPages
            )
        }
        guard !initialPages.isEmpty else { throw ReaderCoreError.noTextFitsPage }

        let target = min(book.fileSize, max(0, targetOffset))
        let selected = initialPages.lastIndex {
            $0.startOffset <= target && ($0.endOffset > target || target == book.fileSize)
        } ?? 0
        let lower = max(0, selected - PageSlidingWindow.warmPagesPerSide)
        let upper = min(
            initialPages.count,
            selected + PageSlidingWindow.warmPagesPerSide + 1
        )
        var pageWindow = PageSlidingWindow(
            pages: Array(initialPages[lower..<upper]),
            selectedIndex: selected - lower
        )

        if pageWindow.pagesBeforeCurrent < PageSlidingWindow.warmPagesPerSide,
           let first = pageWindow.pages.first,
           first.startOffset > 0 {
            let count = PageSlidingWindow.warmPagesPerSide - pageWindow.pagesBeforeCurrent
            pageWindow.prepend(try await pages(
                direction: .backward,
                boundaryOffset: first.startOffset,
                count: count,
                averageBytesPerPage: pageWindow.averageBytesPerPage,
                spec: spec
            ))
        }

        if pageWindow.pagesAfterCurrent < PageSlidingWindow.warmPagesPerSide,
           let last = pageWindow.pages.last,
           last.endOffset < book.fileSize {
            let count = PageSlidingWindow.warmPagesPerSide - pageWindow.pagesAfterCurrent
            pageWindow.append(try await pages(
                direction: .forward,
                boundaryOffset: last.endOffset,
                count: count,
                averageBytesPerPage: pageWindow.averageBytesPerPage,
                spec: spec
            ))
        }

        guard let selectedIndex = pageWindow.currentIndex else {
            throw ReaderCoreError.invalidRange
        }
        return ReaderWindow(pages: pageWindow.pages, selectedIndex: selectedIndex)
    }

    func pages(
        direction: ReaderDirection,
        boundaryOffset: Int64,
        count: Int,
        averageBytesPerPage: Int,
        spec: ReaderLayoutSpec
    ) async throws -> [ReaderPageDescriptor] {
        guard count > 0, let cache else { return [] }
        try Task.checkCancellation()
        let reserveBytes = max(
            CacheCombineStack.defaultSegmentBytes / 10,
            averageBytesPerPage * PageSlidingWindow.warmPagesPerSide
        )
        try await cache.ensureCoverage(
            direction: direction,
            cursor: boundaryOffset,
            reserveBytes: reserveBytes
        )
        try Task.checkCancellation()
        let requestedBytes = min(
            paginationBytes,
            max(64 * 1_024, averageBytesPerPage * (count + 4))
        )

        switch direction {
        case .forward:
            let snapshot = try await cache.snapshotForward(
                from: boundaryOffset,
                maximumBytes: requestedBytes
            )
            guard snapshot.startOffset == boundaryOffset else {
                throw ReaderCoreError.invalidCharacterBoundary
            }
            return try await layout.paginate(
                snapshot: snapshot,
                spec: spec,
                maximumPages: count
            )

        case .backward:
            let snapshot = try await cache.snapshotBackward(
                endingAt: boundaryOffset,
                maximumBytes: requestedBytes
            )
            guard snapshot.endOffset == boundaryOffset else {
                throw ReaderCoreError.invalidCharacterBoundary
            }
            return try await layout.paginateBackward(
                snapshot: snapshot,
                spec: spec,
                maximumPages: count
            )
        }
    }

}

@MainActor
@Observable
final class ReaderCoordinator {
    struct ProgressEvent: Equatable {
        let offset: Int64
        let changedAt: Date
        let sequence: UInt64
    }

    private enum RebuildProgressChange {
        case local(Date)
        case remote(Date)
    }

    private var pageWindow = PageSlidingWindow()
    private(set) var isLoading = true
    var errorMessage: String?
    var menuVisible = false
    var autoPaging = false
    private(set) var lastTurnDirection: ReaderDirection = .forward
    private(set) var completedPageTurns = 0
    private let engine = ReaderEngine()
    private let store: LibraryStore
    private let persistsProgress: Bool
    private var book: Book
    private var url: URL?
    private var spec: ReaderLayoutSpec?
    private var loadTask: Task<Void, Never>?
    private var maintenanceTask: Task<Void, Never>?
    private var saveTask: Task<Void, Never>?
    private var autoTask: Task<Void, Never>?
    private var requestedProgress: Double?
    private var sessionGeneration = 0
    private var progressSequence: UInt64 = 0
    private var lastProgressDate: Date
    private(set) var progressEvent: ProgressEvent?

    init(book: Book, store: LibraryStore, persistsProgress: Bool = true) {
        self.book = book
        self.store = store
        self.persistsProgress = persistsProgress
        lastProgressDate = book.updatedAt
    }

    var pages: [ReaderPageDescriptor] { pageWindow.pages }
    var pageIndex: Int { pageWindow.currentIndex ?? 0 }
    var page: ReaderPageDescriptor? { pageWindow.currentPage }
    var offset: Int64 { page?.startOffset ?? book.offset }
    var progress: Double { requestedProgress ?? actualProgress }

    private var actualProgress: Double {
        book.fileSize > 0 ? min(1, max(0, Double(offset) / Double(book.fileSize))) : 0
    }

    func configure(size: CGSize, settings: ReaderSettings) {
        let nextSpec = ReaderLayoutSpec(
            width: size.width,
            height: size.height,
            fontSize: settings.fontSize,
            lineSpacing: settings.lineSpacing,
            fontName: settings.fontName
        )
        guard nextSpec != spec else { return }
        spec = nextSpec
        rebuildWindow(at: offset, progressChange: nil)
    }

    func rebuild(at target: Int64) {
        requestedProgress = nil
        rebuildWindow(at: target, progressChange: .local(.now))
    }

    func applyRemoteProgress(offset: Int64, readAt: Date) {
        requestedProgress = nil
        rebuildWindow(at: offset, progressChange: .remote(readAt))
    }

    private func rebuildWindow(at target: Int64, progressChange: RebuildProgressChange?) {
        guard let spec else { return }
        sessionGeneration += 1
        let generation = sessionGeneration
        loadTask?.cancel()
        maintenanceTask?.cancel()
        maintenanceTask = nil
        isLoading = true
        errorMessage = nil

        loadTask = Task { [weak self] in
            guard let self else { return }
            do {
                let resolvedURL: URL
                if let url = self.url {
                    resolvedURL = url
                } else {
                    resolvedURL = await self.store.url(for: self.book)
                }
                let window = try await self.engine.buildWindow(
                    url: resolvedURL,
                    book: self.book,
                    targetOffset: target,
                    spec: spec
                )
                try Task.checkCancellation()
                guard generation == self.sessionGeneration else { return }
                self.url = resolvedURL
                self.pageWindow = PageSlidingWindow(
                    pages: window.pages,
                    selectedIndex: window.selectedIndex
                )
                self.isLoading = false
                self.loadTask = nil
                if let progressChange {
                    switch progressChange {
                    case .local(let changedAt):
                        self.commitProgressChange(changedAt: changedAt, publishesEvent: true)
                    case .remote(let changedAt):
                        self.commitProgressChange(changedAt: changedAt, publishesEvent: false)
                    }
                }
                self.schedulePageMaintenance()
            } catch is CancellationError {
                if generation == self.sessionGeneration { self.loadTask = nil }
            } catch {
                guard generation == self.sessionGeneration else { return }
                self.loadTask = nil
                self.isLoading = false
                self.errorMessage = error.localizedDescription
            }
        }
    }

    @discardableResult
    func next() -> Bool {
        turn(.forward)
    }

    @discardableResult
    func previous() -> Bool {
        turn(.backward)
    }

    private func turn(_ direction: ReaderDirection) -> Bool {
        guard !menuVisible else { return false }
        requestedProgress = nil
        guard pageWindow.move(direction) else {
            schedulePageMaintenance()
            return false
        }
        lastTurnDirection = direction
        completedPageTurns &+= 1
        commitProgressChange(changedAt: .now, publishesEvent: true)
        schedulePageMaintenance()
        return true
    }

    func seek(progress: Double) {
        let target = min(1, max(0, progress))
        requestedProgress = target
        rebuildWindow(at: Int64(Double(book.fileSize) * target), progressChange: .local(.now))
    }

    func toggleMenu() {
        withAnimation(.easeInOut(duration: 0.2)) { menuVisible.toggle() }
    }

    func toggleAutoPaging(seconds: Int) {
        autoPaging.toggle()
        autoTask?.cancel()
        guard autoPaging else { return }
        autoTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(seconds))
                guard !Task.isCancelled else { return }
                self?.next()
            }
        }
    }

    func flush() async {
        saveTask?.cancel()
        guard persistsProgress else { return }
        try? await store.saveProgress(bookID: book.id, offset: offset, updatedAt: lastProgressDate)
    }

    func stop() {
        loadTask?.cancel()
        maintenanceTask?.cancel()
        saveTask?.cancel()
        autoTask?.cancel()
        UIApplication.shared.isIdleTimerDisabled = false
    }

    private func schedulePageMaintenance() {
        guard !isLoading,
              maintenanceTask == nil,
              let spec,
              let first = pageWindow.pages.first,
              let last = pageWindow.pages.last else { return }

        let needsBackward = pageWindow.pagesBeforeCurrent < PageSlidingWindow.targetPagesPerSide
            && first.startOffset > 0
        let needsForward = pageWindow.pagesAfterCurrent < PageSlidingWindow.targetPagesPerSide
            && last.endOffset < book.fileSize
        guard needsBackward || needsForward else { return }

        let generation = sessionGeneration
        let backwardCount = needsBackward
            ? PageSlidingWindow.targetPagesPerSide - pageWindow.pagesBeforeCurrent
            : 0
        let forwardCount = needsForward
            ? PageSlidingWindow.targetPagesPerSide - pageWindow.pagesAfterCurrent
            : 0
        let averageBytesPerPage = pageWindow.averageBytesPerPage
        let firstOffset = first.startOffset
        let lastOffset = last.endOffset

        maintenanceTask = Task { [weak self] in
            guard let self else { return }
            do {
                let backwardPages = try await self.engine.pages(
                    direction: .backward,
                    boundaryOffset: firstOffset,
                    count: backwardCount,
                    averageBytesPerPage: averageBytesPerPage,
                    spec: spec
                )
                let forwardPages = try await self.engine.pages(
                    direction: .forward,
                    boundaryOffset: lastOffset,
                    count: forwardCount,
                    averageBytesPerPage: averageBytesPerPage,
                    spec: spec
                )
                try Task.checkCancellation()
                guard generation == self.sessionGeneration else { return }

                let oldCount = self.pageWindow.pages.count
                self.pageWindow.prepend(backwardPages)
                self.pageWindow.append(forwardPages)
                self.pageWindow.trim()
                let madeProgress = self.pageWindow.pages.count != oldCount
                self.maintenanceTask = nil
                if madeProgress { self.schedulePageMaintenance() }
            } catch is CancellationError {
                if generation == self.sessionGeneration { self.maintenanceTask = nil }
            } catch {
                if generation == self.sessionGeneration { self.maintenanceTask = nil }
            }
        }
    }

    private func commitProgressChange(changedAt: Date, publishesEvent: Bool) {
        let monotonicDate = max(changedAt, lastProgressDate.addingTimeInterval(0.001))
        lastProgressDate = monotonicDate
        book.offset = offset
        book.updatedAt = monotonicDate
        if publishesEvent {
            progressSequence &+= 1
            progressEvent = ProgressEvent(offset: offset, changedAt: monotonicDate, sequence: progressSequence)
        }
        scheduleProgressSave(changedAt: monotonicDate)
    }

    private func scheduleProgressSave(changedAt: Date) {
        guard persistsProgress else { return }
        saveTask?.cancel()
        let value = offset
        saveTask = Task { [store, book] in
            try? await Task.sleep(for: .milliseconds(650))
            try? await store.saveProgress(bookID: book.id, offset: value, updatedAt: changedAt)
        }
    }
}
