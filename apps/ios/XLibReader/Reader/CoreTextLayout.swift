import AppleShared
@preconcurrency import CoreText
import Foundation

struct ReaderLayoutSpec: Hashable, Sendable {
    let width: Double
    let height: Double
    let fontSize: Double
    let lineSpacing: Double
    let fontName: String

    init(
        width: Double,
        height: Double,
        fontSize: Double = 20,
        lineSpacing: Double = 3.6,
        fontName: String = ".AppleSystemUIFont"
    ) {
        self.width = max(1, width)
        self.height = max(1, height)
        self.fontSize = max(1, fontSize)
        self.lineSpacing = max(0, lineSpacing)
        self.fontName = fontName
    }
}

struct ReaderPageDescriptor: Identifiable, Hashable, Sendable {
    let id: Int64
    let startOffset: Int64
    let endOffset: Int64
    let utf16Range: Range<Int>
    let text: String
    let layoutHash: Int
}

actor ReaderLayoutService {
    func paginate(
        snapshot: CacheSnapshot,
        spec: ReaderLayoutSpec,
        maximumPages: Int = .max
    ) throws -> [ReaderPageDescriptor] {
        let attributed = Self.makeAttributedString(text: snapshot.text, spec: spec)
        let framesetter = CTFramesetterCreateWithAttributedString(attributed)
        let path = CGPath(rect: CGRect(x: 0, y: 0, width: spec.width, height: spec.height), transform: nil)
        let sourceLength = attributed.length
        var pages: [ReaderPageDescriptor] = []
        var location = 0

        while location < sourceLength, pages.count < max(0, maximumPages) {
            try Task.checkCancellation()
            let frame = CTFramesetterCreateFrame(
                framesetter,
                CFRange(location: location, length: 0),
                path,
                nil
            )
            let visible = CTFrameGetVisibleStringRange(frame)
            guard visible.location == location, visible.length > 0 else {
                throw ReaderCoreError.noTextFitsPage
            }
            let upperBound = visible.location + visible.length
            let startByte = try snapshot.byteMap.byteOffset(forUTF16Index: visible.location)
            let endByte = try snapshot.byteMap.byteOffset(forUTF16Index: upperBound)
            let pageText = (snapshot.text as NSString).substring(
                with: NSRange(location: visible.location, length: visible.length)
            )
            pages.append(
                ReaderPageDescriptor(
                    id: snapshot.startOffset + Int64(startByte),
                    startOffset: snapshot.startOffset + Int64(startByte),
                    endOffset: snapshot.startOffset + Int64(endByte),
                    utf16Range: visible.location..<upperBound,
                    text: pageText,
                    layoutHash: spec.hashValue
                )
            )
            location = upperBound
        }
        return pages
    }

    /// Produces pages before a fixed byte boundary without repaginating any
    /// page already held by the reader's page window.
    func paginateBackward(
        snapshot: CacheSnapshot,
        spec: ReaderLayoutSpec,
        maximumPages: Int
    ) throws -> [ReaderPageDescriptor] {
        guard maximumPages > 0 else { return [] }
        let attributed = Self.makeAttributedString(text: snapshot.text, spec: spec)
        let framesetter = CTFramesetterCreateWithAttributedString(attributed)
        let path = CGPath(rect: CGRect(x: 0, y: 0, width: spec.width, height: spec.height), transform: nil)
        let boundaries = Self.utf16Boundaries(in: snapshot.text)
        var endBoundaryIndex = boundaries.count - 1
        var reversedPages: [ReaderPageDescriptor] = []

        while endBoundaryIndex > 0, reversedPages.count < maximumPages {
            try Task.checkCancellation()
            let end = boundaries[endBoundaryIndex]
            var low = 0
            var high = endBoundaryIndex

            while low < high {
                let middle = (low + high) / 2
                let start = boundaries[middle]
                if Self.rangeFits(
                    start: start,
                    end: end,
                    framesetter: framesetter,
                    path: path
                ) {
                    high = middle
                } else {
                    low = middle + 1
                }
            }

            let startBoundaryIndex = low
            let start = boundaries[startBoundaryIndex]
            guard start < end else { throw ReaderCoreError.noTextFitsPage }
            let startByte = try snapshot.byteMap.byteOffset(forUTF16Index: start)
            let endByte = try snapshot.byteMap.byteOffset(forUTF16Index: end)
            let pageText = (snapshot.text as NSString).substring(
                with: NSRange(location: start, length: end - start)
            )
            reversedPages.append(
                ReaderPageDescriptor(
                    id: snapshot.startOffset + Int64(startByte),
                    startOffset: snapshot.startOffset + Int64(startByte),
                    endOffset: snapshot.startOffset + Int64(endByte),
                    utf16Range: start..<end,
                    text: pageText,
                    layoutHash: spec.hashValue
                )
            )
            endBoundaryIndex = startBoundaryIndex
        }

        return reversedPages.reversed()
    }

    private nonisolated static func rangeFits(
        start: Int,
        end: Int,
        framesetter: CTFramesetter,
        path: CGPath
    ) -> Bool {
        guard start < end else { return true }
        let frame = CTFramesetterCreateFrame(
            framesetter,
            CFRange(location: start, length: end - start),
            path,
            nil
        )
        let visible = CTFrameGetVisibleStringRange(frame)
        return visible.location == start && visible.length >= end - start
    }

    private nonisolated static func utf16Boundaries(in text: String) -> [Int] {
        var values = [0]
        values.reserveCapacity(text.utf16.count + 1)
        var index = text.startIndex
        while index < text.endIndex {
            index = text.index(after: index)
            values.append(index.utf16Offset(in: text))
        }
        return values
    }

    nonisolated static func makeAttributedString(text: String, spec: ReaderLayoutSpec) -> NSAttributedString {
        let font = CTFontCreateWithName(spec.fontName as CFString, spec.fontSize, nil)
        var spacing = CGFloat(spec.lineSpacing)
        let paragraph = withUnsafePointer(to: &spacing) { pointer in
            var setting = CTParagraphStyleSetting(
                spec: .lineSpacingAdjustment,
                valueSize: MemoryLayout<CGFloat>.size,
                value: pointer
            )
            return CTParagraphStyleCreate(&setting, 1)
        }
        let attributes: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): font,
            NSAttributedString.Key(kCTParagraphStyleAttributeName as String): paragraph,
        ]
        return NSAttributedString(string: text, attributes: attributes)
    }

    @MainActor
    static func visibleRangeMatches(page: ReaderPageDescriptor, spec: ReaderLayoutSpec) -> Bool {
        guard page.layoutHash == spec.hashValue else { return false }
        let attributed = makeAttributedString(text: page.text, spec: spec)
        let framesetter = CTFramesetterCreateWithAttributedString(attributed)
        let path = CGPath(rect: CGRect(x: 0, y: 0, width: spec.width, height: spec.height), transform: nil)
        let frame = CTFramesetterCreateFrame(framesetter, CFRange(location: 0, length: 0), path, nil)
        let visible = CTFrameGetVisibleStringRange(frame)
        return visible.location == 0 && visible.length == attributed.length
    }
}
