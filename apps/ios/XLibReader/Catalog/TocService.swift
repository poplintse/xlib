import AppleShared
import Foundation

actor TocService {
    func build(url: URL, book: Book) throws -> [TocEntry] {
        let headingExpression = try NSRegularExpression(pattern: #"^\s*(第[0-9零一二三四五六七八九十百千万两]+[卷章节篇部]|卷[一二三四五六七八九十0-9]+|Chapter\s+\d+)\b.*$"#, options: [.caseInsensitive])
        let volumePrefixExpression = try NSRegularExpression(pattern: #"^\s*(?:第[0-9零一二三四五六七八九十百千万两]+卷|卷[一二三四五六七八九十0-9]+)"#)
        let chapterExpression = try NSRegularExpression(pattern: #"第[0-9零一二三四五六七八九十百千万两]+(?:章|节|篇|部)"#)
        var offset: Int64 = 0
        var entries: [TocEntry] = []
        while offset < book.fileSize {
            try Task.checkCancellation()
            let segment = try readWithBoundaryRecovery(url: url, offset: offset, encoding: book.encoding)
            guard segment.byteCount > 0 else { break }
            let ns = segment.text as NSString
            ns.enumerateSubstrings(in: NSRange(location: 0, length: ns.length), options: [.byLines, .substringNotRequired]) { _, lineRange, _, _ in
                guard entries.count < 10_000,
                      headingExpression.firstMatch(in: ns as String, range: lineRange) != nil,
                      let byte = try? segment.byteMap.byteOffset(forUTF16Index: lineRange.location) else { return }
                let title = ns.substring(with: lineRange).trimmingCharacters(in: .whitespacesAndNewlines)
                let titleRange = NSRange(title.startIndex..., in: title)
                let isVolumeTitle = volumePrefixExpression.firstMatch(in: title, range: titleRange) != nil
                    && chapterExpression.firstMatch(in: title, range: titleRange) == nil
                entries.append(TocEntry(id: UUID(), title: title, offset: segment.startOffset + Int64(byte), level: isVolumeTitle ? 0 : 1))
            }
            offset = segment.endOffset
        }
        return entries
    }

    private func readWithBoundaryRecovery(url: URL, offset: Int64, encoding: TextEncoding) throws -> TextSegment {
        for delta in 0...4 {
            if let segment = try? SegmentSource.read(
                from: url,
                offset: offset + Int64(delta),
                maximumBytes: 128 * 1_024,
                encoding: encoding
            ) {
                return segment
            }
        }
        throw ReaderCoreError.invalidCharacterBoundary
    }
}
