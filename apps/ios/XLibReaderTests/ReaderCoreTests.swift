import AppleShared
import CoreFoundation
import UIKit
import XCTest
@testable import XLibReader

final class ReaderCoreTests: XCTestCase {
    func testAppVersionDisplayIncludesMarketingAndBuildNumbers() {
        XCTAssertEqual(
            AppVersion.displayText(version: "0.2.1", build: "42"),
            "xLib Reader v0.2.1 build 42"
        )
    }

    @MainActor
    func testReaderUsesControlledSoftPageTurnTiming() {
        let controller = ReaderSoftPageTurnRepresentable.makePageTurnController(
            backgroundColor: .systemBackground
        )

        XCTAssertEqual(controller.view.backgroundColor, .systemBackground)
        XCTAssertEqual(ReaderSoftPageTurnStyle.duration, 0.40, accuracy: 0.001)
        XCTAssertLessThan(ReaderSoftPageTurnStyle.maximumAngle, .pi / 2)
        XCTAssertEqual(
            ReaderSoftPageTurnStyle.anchorPoint(for: .forward),
            CGPoint(x: 0, y: 0.5)
        )
        XCTAssertEqual(
            ReaderSoftPageTurnStyle.anchorPoint(for: .backward),
            CGPoint(x: 1, y: 0.5)
        )
        XCTAssertLessThan(ReaderSoftPageTurnStyle.foldedAngle(for: .forward), 0)
        XCTAssertGreaterThan(ReaderSoftPageTurnStyle.foldedAngle(for: .backward), 0)
    }

    @MainActor
    func testSoftPageTurnPresentsTheRequestedAdjacentPage() async throws {
        let controller = ReaderSoftPageTurnRepresentable.makePageTurnController(
            backgroundColor: .systemBackground
        )
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = controller

        let coordinator = ReaderSoftPageTurnRepresentable.Coordinator()
        let first = page(index: 10)
        let second = page(index: 11)
        let spec = ReaderLayoutSpec(width: 320, height: 540)
        coordinator.update(
            controller: controller,
            page: first,
            spec: spec,
            backgroundColor: .systemBackground,
            textColor: .label,
            direction: .forward,
            completedPageTurns: 0,
            reduceMotion: false,
            previous: {},
            next: {},
            toggleMenu: {}
        )
        window.makeKeyAndVisible()
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(
            controller.currentPageID, first.id
        )

        coordinator.update(
            controller: controller,
            page: second,
            spec: spec,
            backgroundColor: .systemBackground,
            textColor: .label,
            direction: .forward,
            completedPageTurns: 1,
            reduceMotion: false,
            previous: {},
            next: {},
            toggleMenu: {}
        )
        XCTAssertTrue(coordinator.isAnimating)
        XCTAssertEqual(controller.activeTurnDirection, .forward)
        XCTAssertEqual(controller.activeTurnAnchorPoint, CGPoint(x: 0, y: 0.5))
        for _ in 0..<200 {
            if !coordinator.isAnimating,
               controller.currentPageID == second.id {
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertFalse(coordinator.isAnimating)
        XCTAssertEqual(
            controller.currentPageID, second.id
        )

        coordinator.update(
            controller: controller,
            page: first,
            spec: spec,
            backgroundColor: .systemBackground,
            textColor: .label,
            direction: .backward,
            completedPageTurns: 2,
            reduceMotion: false,
            previous: {},
            next: {},
            toggleMenu: {}
        )
        XCTAssertTrue(coordinator.isAnimating)
        XCTAssertEqual(controller.activeTurnDirection, .backward)
        XCTAssertEqual(controller.activeTurnAnchorPoint, CGPoint(x: 1, y: 0.5))
        for _ in 0..<200 {
            if !coordinator.isAnimating, controller.currentPageID == first.id {
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertFalse(coordinator.isAnimating)
        XCTAssertEqual(controller.currentPageID, first.id)

        coordinator.update(
            controller: controller,
            page: second,
            spec: spec,
            backgroundColor: .systemBackground,
            textColor: .label,
            direction: .forward,
            completedPageTurns: 3,
            reduceMotion: true,
            previous: {},
            next: {},
            toggleMenu: {}
        )
        XCTAssertFalse(coordinator.isAnimating)
        XCTAssertEqual(controller.currentPageID, second.id)
        window.isHidden = true
        window.rootViewController = nil
        try await Task.sleep(for: .milliseconds(50))
    }

    func testReaderTapZonesIncludeTheFarLeftEdge() {
        XCTAssertEqual(ReaderPageInteraction.tapAction(x: 0, width: 400), .previousPage)
        XCTAssertEqual(ReaderPageInteraction.tapAction(x: 24, width: 400), .previousPage)
        XCTAssertEqual(ReaderPageInteraction.tapAction(x: 200, width: 400), .toggleMenu)
        XCTAssertEqual(ReaderPageInteraction.tapAction(x: 399, width: 400), .nextPage)
    }

    func testReaderDragReservesTheNativeNavigationEdge() {
        XCTAssertTrue(ReaderPageInteraction.isNavigationEdgeDrag(startX: 0))
        XCTAssertTrue(ReaderPageInteraction.isNavigationEdgeDrag(startX: 24))
        XCTAssertFalse(ReaderPageInteraction.isNavigationEdgeDrag(startX: 24.1))
        XCTAssertFalse(ReaderPageInteraction.isNavigationEdgeDrag(startX: 200))
    }

    func testReaderChromeDisablesAllPageGesturesWhileVisible() {
        XCTAssertTrue(ReaderPageInteraction.allowsPageGesture(
            y: 20, height: 800, menuVisible: false
        ))
        XCTAssertFalse(ReaderPageInteraction.allowsPageGesture(
            y: 40, height: 800, menuVisible: true
        ))
        XCTAssertFalse(ReaderPageInteraction.allowsPageGesture(
            y: 740, height: 800, menuVisible: true
        ))
        XCTAssertFalse(ReaderPageInteraction.allowsPageGesture(
            y: 400, height: 800, menuVisible: true
        ))
    }

    func testProgressSelectionSplitsAndRecombinesTwoDecimalPercentage() {
        let selection = ReaderProgressSelection(progress: 0.7354)
        XCTAssertEqual(selection.wholePercent, 73)
        XCTAssertEqual(selection.fractionalPercent, 54)
        XCTAssertEqual(selection.progress, 0.7354, accuracy: 0.000_001)
    }

    func testProgressSelectionClampsToValidBookRange() {
        XCTAssertEqual(ReaderProgressSelection(progress: -1).progress, 0)
        XCTAssertEqual(ReaderProgressSelection(progress: 2).progress, 1)

        var selection = ReaderProgressSelection(progress: 1)
        selection.fractionalPercent = 99
        XCTAssertEqual(selection.progress, 1)
    }

    @MainActor
    func testCommittedSeekKeepsTheExactDisplayedProgressUntilNavigation() {
        let book = Book(
            id: UUID(), title: "测试", sourceName: "test.txt", author: "", relativePath: "Books/test.txt",
            fileSize: 1_000, modifiedAt: .now, encoding: .utf8, offset: 200, updatedAt: .now,
            schemaVersion: Book.schemaVersion
        )
        let coordinator = ReaderCoordinator(book: book, store: LibraryStore(), persistsProgress: false)

        coordinator.seek(progress: 0.735)
        XCTAssertEqual(coordinator.progress, 0.735, accuracy: 0.000_001)

        coordinator.next()
        XCTAssertEqual(coordinator.progress, 0.20, accuracy: 0.000_001)
    }

    func testPageSlidingWindowMovesExactlyOnePagePerCommand() {
        let pages = (0..<7).map { page(index: $0) }
        var window = PageSlidingWindow(pages: pages, selectedIndex: 3)

        XCTAssertTrue(window.move(.forward))
        XCTAssertEqual(window.currentPage?.id, pages[4].id)
        XCTAssertTrue(window.move(.backward))
        XCTAssertEqual(window.currentPage?.id, pages[3].id)
    }

    func testPageSlidingWindowPrependAndAppendKeepCurrentPageStable() {
        let currentPages = (10..<13).map { page(index: $0) }
        var window = PageSlidingWindow(pages: currentPages, selectedIndex: 1)
        let currentID = window.currentPageID

        window.prepend((7..<10).map { page(index: $0) })
        window.append((13..<16).map { page(index: $0) })

        XCTAssertEqual(window.currentPageID, currentID)
        XCTAssertEqual(window.currentPage?.id, currentPages[1].id)
        XCTAssertEqual(window.pages.map(\.id), (7..<16).map { Int64($0 * 10) })
    }

    func testPageSlidingWindowRejectsDiscontinuousRefillBatch() {
        let currentPages = (10..<13).map { page(index: $0) }
        var window = PageSlidingWindow(pages: currentPages, selectedIndex: 1)
        let originalPages = window.pages
        let discontinuous = [page(index: 13), page(index: 15)]

        window.append(discontinuous)

        XCTAssertEqual(window.pages, originalPages)
        XCTAssertEqual(window.currentPage?.id, currentPages[1].id)
    }

    func testHundredMegabyteCacheBootstrapKeepsOnlyTwoSegments() async throws {
        let url = FileManager.default.temporaryDirectory.appending(path: "\(UUID().uuidString).txt")
        XCTAssertTrue(FileManager.default.createFile(atPath: url.path, contents: nil))
        defer { try? FileManager.default.removeItem(at: url) }
        let size: UInt64 = 100 * 1_024 * 1_024
        let handle = try FileHandle(forWritingTo: url)
        try handle.truncate(atOffset: size)
        try handle.close()

        let cache = CacheCombineStack(
            url: url,
            fileSize: Int64(size),
            encoding: .utf8
        )
        let anchor = try await cache.bootstrap(around: Int64(size / 2))
        let state = await cache.state()

        XCTAssertEqual(state.ranges.count, 2)
        XCTAssertTrue(try XCTUnwrap(state.coveredRange).contains(anchor))
        XCTAssertLessThanOrEqual(
            state.segmentBytesRead,
            Int64(CacheCombineStack.defaultSegmentBytes * 2)
        )
    }

    func testCacheCombineStackSlidesForwardWithoutGap() async throws {
        let data = Data(repeating: 0x61, count: 900 * 1_024)
        let url = FileManager.default.temporaryDirectory.appending(path: "\(UUID().uuidString).txt")
        try data.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let cache = CacheCombineStack(
            url: url,
            fileSize: Int64(data.count),
            encoding: .utf8
        )
        _ = try await cache.bootstrap(around: 300 * 1_024)
        let before = await cache.state()
        let cursor = try XCTUnwrap(before.coveredRange).upperBound - 1

        try await cache.ensureCoverage(direction: .forward, cursor: cursor, reserveBytes: 64 * 1_024)
        let after = await cache.state()

        XCTAssertEqual(after.ranges.count, 2)
        XCTAssertEqual(after.ranges[0].upperBound, after.ranges[1].lowerBound)
        XCTAssertGreaterThan(after.ranges[1].upperBound, try XCTUnwrap(before.coveredRange).upperBound)
    }

    func testUTF16CacheBootstrapBacksOutOfSurrogatePairBoundary() async throws {
        let text = "甲🙂乙\n后续内容"
        let data = try XCTUnwrap(text.data(using: .utf16LittleEndian))
        let url = FileManager.default.temporaryDirectory.appending(path: "\(UUID().uuidString).txt")
        try data.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let cache = CacheCombineStack(
            url: url,
            fileSize: Int64(data.count),
            encoding: .utf16LittleEndian
        )

        let anchor = try await cache.bootstrap(around: 4)

        XCTAssertEqual(anchor, 2)
        let state = await cache.state()
        XCTAssertEqual(state.ranges.first?.upperBound, state.ranges.last?.lowerBound)
    }

    func testReaderEngineCanOpenAtExactEndOfFile() async throws {
        let text = Array(repeating: "文件尾部阅读定位测试🙂。\n", count: 300).joined()
        let data = try XCTUnwrap(text.data(using: .utf8))
        let url = FileManager.default.temporaryDirectory.appending(path: "\(UUID().uuidString).txt")
        try data.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let book = Book(
            id: UUID(), title: "尾部", sourceName: "end.txt", author: "", relativePath: "",
            fileSize: Int64(data.count), modifiedAt: .now, encoding: .utf8,
            offset: Int64(data.count), updatedAt: .now, schemaVersion: Book.schemaVersion
        )

        let window = try await ReaderEngine().buildWindow(
            url: url,
            book: book,
            targetOffset: book.fileSize,
            spec: ReaderLayoutSpec(width: 320, height: 540)
        )

        XCTAssertFalse(window.pages.isEmpty)
        XCTAssertEqual(window.pages[window.selectedIndex].endOffset, book.fileSize)
    }

    func testSlidingWindowCrossesSegmentBoundariesInBothDirections() async throws {
        let line = "跨越缓存边界时正文必须连续，不能重复、缺失或跳页🙂。\n"
        let text = Array(repeating: line, count: 40_000).joined()
        let data = try XCTUnwrap(text.data(using: .utf8))
        let url = FileManager.default.temporaryDirectory.appending(path: "\(UUID().uuidString).txt")
        try data.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let book = Book(
            id: UUID(), title: "跨缓存", sourceName: "segments.txt", author: "", relativePath: "",
            fileSize: Int64(data.count), modifiedAt: .now, encoding: .utf8,
            offset: Int64(data.count / 2), updatedAt: .now, schemaVersion: Book.schemaVersion
        )
        let spec = ReaderLayoutSpec(width: 320, height: 540)
        let engine = ReaderEngine()
        let initial = try await engine.buildWindow(
            url: url,
            book: book,
            targetOffset: book.offset,
            spec: spec
        )
        var sliding = PageSlidingWindow(pages: initial.pages, selectedIndex: initial.selectedIndex)

        for _ in 0..<400 {
            if sliding.pagesAfterCurrent < PageSlidingWindow.warmPagesPerSide,
               let boundary = sliding.pages.last?.endOffset {
                sliding.append(try await engine.pages(
                    direction: .forward,
                    boundaryOffset: boundary,
                    count: PageSlidingWindow.targetPagesPerSide - sliding.pagesAfterCurrent,
                    averageBytesPerPage: sliding.averageBytesPerPage,
                    spec: spec
                ))
                sliding.trim()
            }
            let old = try XCTUnwrap(sliding.currentPage)
            XCTAssertTrue(sliding.move(.forward))
            XCTAssertEqual(old.endOffset, sliding.currentPage?.startOffset)
        }

        for _ in 0..<400 {
            if sliding.pagesBeforeCurrent < PageSlidingWindow.warmPagesPerSide,
               let boundary = sliding.pages.first?.startOffset {
                sliding.prepend(try await engine.pages(
                    direction: .backward,
                    boundaryOffset: boundary,
                    count: PageSlidingWindow.targetPagesPerSide - sliding.pagesBeforeCurrent,
                    averageBytesPerPage: sliding.averageBytesPerPage,
                    spec: spec
                ))
                sliding.trim()
            }
            let old = try XCTUnwrap(sliding.currentPage)
            XCTAssertTrue(sliding.move(.backward))
            XCTAssertEqual(sliding.currentPage?.endOffset, old.startOffset)
        }
    }

    @MainActor
    func testCoordinatorTurnRemainsOnePageWhileBackgroundWindowRefills() async throws {
        let root = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        let books = root.appending(path: "Books", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: books, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let text = Array(repeating: "连续翻页与后台补页测试🙂，每次操作只能移动一页。\n", count: 2_000).joined()
        let data = try XCTUnwrap(text.data(using: .utf8))
        let file = books.appending(path: "reader.txt")
        try data.write(to: file)
        let book = Book(
            id: UUID(), title: "翻页", sourceName: "reader.txt", author: "",
            relativePath: "Books/reader.txt", fileSize: Int64(data.count),
            modifiedAt: .now, encoding: .utf8, offset: Int64(data.count / 2),
            updatedAt: .now, schemaVersion: Book.schemaVersion
        )
        let coordinator = ReaderCoordinator(
            book: book,
            store: LibraryStore(root: root),
            persistsProgress: false
        )
        coordinator.configure(size: CGSize(width: 320, height: 540), settings: ReaderSettings())
        for _ in 0..<200 where coordinator.isLoading {
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTAssertFalse(coordinator.isLoading)
        let originalPages = coordinator.pages
        let originalIndex = coordinator.pageIndex
        let expectedNextID = originalPages[originalIndex + 1].id

        coordinator.next()

        XCTAssertEqual(coordinator.page?.id, expectedNextID)
        XCTAssertEqual(coordinator.lastTurnDirection, .forward)
        XCTAssertEqual(coordinator.completedPageTurns, 1)
        let stableID = coordinator.page?.id
        try await Task.sleep(for: .milliseconds(80))
        XCTAssertEqual(coordinator.page?.id, stableID)

        coordinator.previous()
        XCTAssertEqual(coordinator.page?.id, originalPages[originalIndex].id)
        XCTAssertEqual(coordinator.lastTurnDirection, .backward)
        XCTAssertEqual(coordinator.completedPageTurns, 2)
        coordinator.stop()
    }

    func testByteOffsetMapRoundTripsAllEncodings() throws {
        let text = "A中🙂B"
        for encoding in TextEncoding.allCases {
            let map = try ByteOffsetMap(text: text, encoding: encoding)
            let end = (text as NSString).length
            XCTAssertEqual(try map.utf16Index(forByteOffset: map.totalBytes), end)
            XCTAssertEqual(try map.byteOffset(forUTF16Index: end), map.totalBytes)
            XCTAssertEqual(map.totalBytes, encoding.encodedByteCount(of: text))
        }
    }

    func testOffsetInsideMultibyteCharacterFloorsToBoundary() throws {
        let map = try ByteOffsetMap(text: "甲🙂乙", encoding: .utf8)
        let emojiStart = try map.byteOffset(forUTF16Index: 1)
        XCTAssertEqual(try map.utf16Index(forByteOffset: emojiStart + 1), 1)
        XCTAssertEqual(try map.utf16IndexAtOrAfter(byteOffset: emojiStart + 1), 3)
    }

    func testUTF8DetectionAllowsIncompleteSampleSuffix() throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appending(path: "sample.txt")
        var data = Data(repeating: 0x61, count: 4_095)
        data.append(contentsOf: [0xE4, 0xB8, 0xAD])
        try data.write(to: url)
        XCTAssertEqual(try TextEncoding.detect(at: url), .utf8)
    }

    func testSegmentRejectsTruncatedInvalidSource() throws {
        XCTAssertThrowsError(try TextEncoding.utf8.decodeCompletePrefix(Data([0xFF, 0xFF])))
    }

    func testCoreTextPagesAreContinuousAndRenderable() async throws {
        let text = Array(repeating: "第一章 测试文本🙂，用于验证分页连续性。\n", count: 100).joined()
        let map = try ByteOffsetMap(text: text, encoding: .utf8)
        let snapshot = try CacheSnapshot(
            fileSize: Int64(map.totalBytes),
            startOffset: 0,
            text: text,
            byteMap: map
        )
        let spec = ReaderLayoutSpec(width: 320, height: 540)
        let pages = try await ReaderLayoutService().paginate(snapshot: snapshot, spec: spec)
        XCTAssertGreaterThan(pages.count, 1)
        XCTAssertEqual(pages.first?.startOffset, 0)
        XCTAssertEqual(pages.last?.endOffset, Int64(map.totalBytes))
        for pair in zip(pages, pages.dropFirst()) {
            XCTAssertEqual(pair.0.endOffset, pair.1.startOffset)
        }
        if let first = pages.first {
            let matches = await MainActor.run {
                ReaderLayoutService.visibleRangeMatches(page: first, spec: spec)
            }
            XCTAssertTrue(matches)
        }
    }

    func testBackwardPaginationEndsAtFixedBoundaryAndRemainsContinuous() async throws {
        let text = Array(repeating: "用于反向分页连续性测试的文字🙂。\n", count: 200).joined()
        let map = try ByteOffsetMap(text: text, encoding: .utf8)
        let snapshot = try CacheSnapshot(
            fileSize: Int64(map.totalBytes),
            startOffset: 0,
            text: text,
            byteMap: map
        )
        let spec = ReaderLayoutSpec(width: 320, height: 540)
        let forward = try await ReaderLayoutService().paginate(
            snapshot: snapshot,
            spec: spec,
            maximumPages: 8
        )
        let boundary = try XCTUnwrap(forward.last?.startOffset)
        let prefixUTF16 = try map.utf16Index(forByteOffset: Int(boundary))
        let prefixText = (text as NSString).substring(to: prefixUTF16)
        let prefixMap = try ByteOffsetMap(text: prefixText, encoding: .utf8)
        let prefix = try CacheSnapshot(
            fileSize: Int64(map.totalBytes),
            startOffset: 0,
            text: prefixText,
            byteMap: prefixMap
        )

        let backward = try await ReaderLayoutService().paginateBackward(
            snapshot: prefix,
            spec: spec,
            maximumPages: 4
        )

        XCTAssertEqual(backward.last?.endOffset, boundary)
        for pair in zip(backward, backward.dropFirst()) {
            XCTAssertEqual(pair.0.endOffset, pair.1.startOffset)
        }
    }

    private func page(index: Int) -> ReaderPageDescriptor {
        let start = Int64(index * 10)
        return ReaderPageDescriptor(
            id: start,
            startOffset: start,
            endOffset: start + 10,
            utf16Range: 0..<10,
            text: "第\(index)页内容",
            layoutHash: 1
        )
    }
}
