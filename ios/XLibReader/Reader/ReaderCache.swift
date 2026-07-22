import Foundation

enum ReaderDirection: Equatable, Sendable {
    case backward
    case forward
}

struct CacheCombineState: Equatable, Sendable {
    let ranges: [Range<Int64>]
    let segmentBytesRead: Int64

    var coveredRange: Range<Int64>? {
        guard let first = ranges.first, let last = ranges.last else { return nil }
        return first.lowerBound..<last.upperBound
    }
}

/// A bounded, two-segment byte window. Segment replacement never changes the
/// page currently displayed by the reader.
actor CacheCombineStack {
    static let defaultSegmentBytes = 256 * 1_024

    private let url: URL
    private let fileSize: Int64
    private let encoding: TextEncoding
    private let segmentBytes: Int
    private var segments: [TextSegment] = []
    private var segmentBytesRead: Int64 = 0

    init(
        url: URL,
        fileSize: Int64,
        encoding: TextEncoding,
        segmentBytes: Int = CacheCombineStack.defaultSegmentBytes
    ) {
        self.url = url
        self.fileSize = max(0, fileSize)
        self.encoding = encoding
        self.segmentBytes = max(16 * 1_024, segmentBytes)
    }

    /// Loads the leading and trailing segments around a stable character/line
    /// boundary near the requested offset.
    func bootstrap(around requestedOffset: Int64) throws -> Int64 {
        try Task.checkCancellation()
        segments.removeAll(keepingCapacity: true)
        segmentBytesRead = 0
        let target = min(fileSize, max(0, requestedOffset))
        let anchor = try safeBoundary(atOrBefore: target)

        if anchor > 0, let leading = try readPrevious(endingAt: anchor) {
            segments.append(leading)
        }
        if anchor < fileSize, let trailing = try readForward(startingAt: anchor) {
            segments.append(trailing)
        }

        if segments.count == 1, let only = segments.first {
            if only.startOffset > 0, let previous = try readPrevious(endingAt: only.startOffset) {
                segments.insert(previous, at: 0)
            } else if only.endOffset < fileSize, let next = try readForward(startingAt: only.endOffset) {
                segments.append(next)
            }
        }
        normalizeSegments()
        return anchor
    }

    func ensureCoverage(
        direction: ReaderDirection,
        cursor: Int64,
        reserveBytes: Int
    ) throws {
        try Task.checkCancellation()
        guard !segments.isEmpty else { return }
        let reserve = Int64(min(
            max(segmentBytes / 10, reserveBytes),
            segmentBytes * 9 / 10
        ))

        switch direction {
        case .forward:
            guard let last = segments.last,
                  last.endOffset < fileSize,
                  last.endOffset - cursor <= reserve else { return }
            if segments.count == 2, let first = segments.first, cursor < first.endOffset {
                return
            }
            guard let next = try readForward(startingAt: last.endOffset) else { return }
            segments.append(next)
            if segments.count > 2 { segments.removeFirst(segments.count - 2) }

        case .backward:
            guard let first = segments.first,
                  first.startOffset > 0,
                  cursor - first.startOffset <= reserve else { return }
            if segments.count == 2, let last = segments.last, cursor > last.startOffset {
                return
            }
            guard let previous = try readPrevious(endingAt: first.startOffset) else { return }
            segments.insert(previous, at: 0)
            if segments.count > 2 { segments.removeLast(segments.count - 2) }
        }
        normalizeSegments()
    }

    func snapshotForward(from startOffset: Int64, maximumBytes: Int) throws -> CacheSnapshot {
        guard let end = segments.last?.endOffset else { throw ReaderCoreError.invalidRange }
        return try snapshot(
            requestedRange: startOffset..<min(end, startOffset + Int64(max(1, maximumBytes)))
        )
    }

    func snapshotBackward(endingAt endOffset: Int64, maximumBytes: Int) throws -> CacheSnapshot {
        guard let start = segments.first?.startOffset else { throw ReaderCoreError.invalidRange }
        return try snapshot(
            requestedRange: max(start, endOffset - Int64(max(1, maximumBytes)))..<endOffset
        )
    }

    func state() -> CacheCombineState {
        CacheCombineState(
            ranges: segments.map { $0.startOffset..<$0.endOffset },
            segmentBytesRead: segmentBytesRead
        )
    }

    private func snapshot(requestedRange: Range<Int64>) throws -> CacheSnapshot {
        guard requestedRange.lowerBound >= 0,
              requestedRange.lowerBound < requestedRange.upperBound else {
            throw ReaderCoreError.invalidRange
        }
        var combined = ""
        var absoluteStart: Int64?
        var absoluteEnd: Int64?

        for segment in segments {
            let lower = max(requestedRange.lowerBound, segment.startOffset)
            let upper = min(requestedRange.upperBound, segment.endOffset)
            guard lower < upper else { continue }

            let localLower = Int(lower - segment.startOffset)
            let localUpper = Int(upper - segment.startOffset)
            let lowerUTF16 = try segment.byteMap.utf16IndexAtOrAfter(byteOffset: localLower)
            let upperUTF16 = try segment.byteMap.utf16Index(forByteOffset: localUpper)
            guard lowerUTF16 < upperUTF16 else { continue }
            let lowerByte = try segment.byteMap.byteOffset(forUTF16Index: lowerUTF16)
            let upperByte = try segment.byteMap.byteOffset(forUTF16Index: upperUTF16)
            let pieceStart = segment.startOffset + Int64(lowerByte)
            let pieceEnd = segment.startOffset + Int64(upperByte)

            if let absoluteEnd {
                guard absoluteEnd == pieceStart else { throw ReaderCoreError.invalidRange }
            } else {
                absoluteStart = pieceStart
            }
            combined += (segment.text as NSString).substring(
                with: NSRange(location: lowerUTF16, length: upperUTF16 - lowerUTF16)
            )
            absoluteEnd = pieceEnd
        }

        guard let absoluteStart, let absoluteEnd, absoluteStart < absoluteEnd else {
            throw ReaderCoreError.invalidRange
        }
        let map = try ByteOffsetMap(text: combined, encoding: encoding)
        guard absoluteStart + Int64(map.totalBytes) == absoluteEnd else {
            throw ReaderCoreError.invalidByteMap
        }
        return try CacheSnapshot(
            fileSize: fileSize,
            startOffset: absoluteStart,
            text: combined,
            byteMap: map
        )
    }

    private func readForward(startingAt start: Int64) throws -> TextSegment? {
        guard start < fileSize else { return nil }
        for delta in 0...4 {
            let candidate = start + Int64(delta)
            guard candidate < fileSize else { break }
            if let segment = try? SegmentSource.read(
                from: url,
                offset: candidate,
                maximumBytes: segmentBytes,
                encoding: encoding
            ), segment.byteCount > 0 {
                segmentBytesRead += Int64(segment.byteCount)
                return segment
            }
        }
        throw ReaderCoreError.invalidCharacterBoundary
    }

    private func readPrevious(endingAt end: Int64) throws -> TextSegment? {
        guard end > 0 else { return nil }
        let desired = max(0, end - Int64(segmentBytes))
        let safeStart = try safeBoundary(atOrBefore: desired)
        for delta in 0...4 {
            let candidate = safeStart + Int64(delta)
            guard candidate < end else { break }
            let length = Int(end - candidate)
            if let segment = try? SegmentSource.read(
                from: url,
                offset: candidate,
                maximumBytes: length,
                encoding: encoding
            ), segment.byteCount > 0, segment.endOffset == end {
                segmentBytesRead += Int64(segment.byteCount)
                return segment
            }
        }
        throw ReaderCoreError.invalidCharacterBoundary
    }

    private func safeBoundary(atOrBefore tentative: Int64) throws -> Int64 {
        guard tentative > 0 else { return 0 }
        switch encoding {
        case .utf16LittleEndian, .utf16BigEndian:
            let aligned = tentative - tentative % 2
            for backtrack in stride(from: 0, through: 4, by: 2) {
                let candidate = max(0, aligned - Int64(backtrack))
                let count = min(32, Int(fileSize - candidate))
                if (try? SegmentSource.read(
                    from: url,
                    offset: candidate,
                    maximumBytes: count,
                    encoding: encoding
                )) != nil {
                    return candidate
                }
            }
            throw ReaderCoreError.invalidCharacterBoundary
        case .utf8, .gb18030:
            for backtrack in 0...4 {
                let candidate = max(0, tentative - Int64(backtrack))
                let count = min(32, Int(fileSize - candidate))
                if (try? SegmentSource.read(
                    from: url,
                    offset: candidate,
                    maximumBytes: count,
                    encoding: encoding
                )) != nil {
                    return candidate
                }
            }
            throw ReaderCoreError.invalidCharacterBoundary
        }
    }

    private func normalizeSegments() {
        segments.sort { $0.startOffset < $1.startOffset }
        if segments.count > 2 {
            segments = Array(segments.suffix(2))
        }
    }
}

struct PageSlidingWindow: Sendable {
    static let warmPagesPerSide = 8
    static let targetPagesPerSide = 16
    static let maximumPages = 33

    private(set) var pages: [ReaderPageDescriptor] = []
    private(set) var currentPageID: Int64?

    init() {}

    init(pages: [ReaderPageDescriptor], selectedIndex: Int) {
        self.pages = pages
        if pages.indices.contains(selectedIndex) {
            currentPageID = pages[selectedIndex].id
        }
    }

    var currentIndex: Int? {
        guard let currentPageID else { return nil }
        return pages.firstIndex { $0.id == currentPageID }
    }

    var currentPage: ReaderPageDescriptor? {
        guard let currentIndex else { return nil }
        return pages[currentIndex]
    }

    var pagesBeforeCurrent: Int { currentIndex ?? 0 }
    var pagesAfterCurrent: Int {
        guard let currentIndex else { return 0 }
        return pages.count - currentIndex - 1
    }

    var averageBytesPerPage: Int {
        guard !pages.isEmpty else { return 2 * 1_024 }
        let total = pages.reduce(into: Int64(0)) { $0 += max(0, $1.endOffset - $1.startOffset) }
        return max(1, Int(total / Int64(pages.count)))
    }

    mutating func move(_ direction: ReaderDirection) -> Bool {
        guard let currentIndex else { return false }
        let destination = direction == .forward ? currentIndex + 1 : currentIndex - 1
        guard pages.indices.contains(destination) else { return false }
        currentPageID = pages[destination].id
        return true
    }

    mutating func prepend(_ newPages: [ReaderPageDescriptor]) {
        guard !newPages.isEmpty else { return }
        let existingIDs = Set(pages.map(\.id))
        let unique = newPages.filter { !existingIDs.contains($0.id) }
        guard !unique.isEmpty, Self.isContinuous(unique) else { return }
        if let first = pages.first, unique.last?.endOffset != first.startOffset { return }
        pages.insert(contentsOf: unique, at: 0)
    }

    mutating func append(_ newPages: [ReaderPageDescriptor]) {
        guard !newPages.isEmpty else { return }
        let existingIDs = Set(pages.map(\.id))
        let unique = newPages.filter { !existingIDs.contains($0.id) }
        guard !unique.isEmpty, Self.isContinuous(unique) else { return }
        if let last = pages.last, unique.first?.startOffset != last.endOffset { return }
        pages.append(contentsOf: unique)
    }

    mutating func trim() {
        while pages.count > Self.maximumPages, let currentIndex {
            if currentIndex > Self.targetPagesPerSide {
                pages.removeFirst()
            } else if pages.count - currentIndex - 1 > Self.targetPagesPerSide {
                pages.removeLast()
            } else if currentIndex >= pages.count - currentIndex - 1 {
                pages.removeFirst()
            } else {
                pages.removeLast()
            }
        }
    }

    private static func isContinuous(_ values: [ReaderPageDescriptor]) -> Bool {
        zip(values, values.dropFirst()).allSatisfy { previous, next in
            previous.endOffset == next.startOffset
        }
    }
}
