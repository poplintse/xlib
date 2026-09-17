import AppleShared
import Foundation

struct SearchResult: Identifiable, Hashable, Sendable {
    let id: Int64
    let offset: Int64
    let excerpt: String
    let highlight: Range<Int>
}

actor SearchService {
    func search(url: URL, book: Book, query: String, from startOffset: Int64, limit: Int = 200) throws -> [SearchResult] {
        try searchBatch(url: url, book: book, query: query, from: startOffset, limit: limit).results
    }

    struct Batch: Sendable {
        let results: [SearchResult]
        let nextOffset: Int64
        let exhausted: Bool
    }

    func searchBatch(url: URL, book: Book, query: String, from startOffset: Int64, to endOffset: Int64? = nil, limit: Int = 200) throws -> Batch {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (2...32).contains(needle.count) else { throw SearchError.invalidQuery }
        let end = min(book.fileSize, max(0, endOffset ?? book.fileSize))
        let batchLimit = min(200, max(1, limit))
        var offset = max(0, startOffset)
        var results: [SearchResult] = []
        var carry = ""
        var nextMatchOffset = offset
        while offset < end {
            try Task.checkCancellation()
            let segment = try SegmentSource.read(from: url, offset: offset, maximumBytes: 64 * 1_024, encoding: book.encoding)
            guard segment.byteCount > 0 else { break }
            let carryBytes = book.encoding.encodedByteCount(of: carry) ?? 0
            let combinedStart = segment.startOffset - Int64(carryBytes)
            let combined = carry + segment.text
            let combinedMap = try ByteOffsetMap(text: combined, encoding: book.encoding)
            let text = combined as NSString
            // Delay the trailing query-sized region until the next segment so
            // a match split across reads wins before any overlapping match.
            let suffix = String(combined.suffix(needle.utf16.count))
            let safeEnd = segment.endOffset >= end ? text.length : text.length - suffix.utf16.count
            var range = NSRange(location: 0, length: text.length)
            while range.length > 0 {
                try Task.checkCancellation()
                let found = text.range(of: needle, options: [.caseInsensitive], range: range)
                if found.location == NSNotFound { break }
                if found.location >= safeEnd { break }
                let byte = try combinedMap.byteOffset(forUTF16Index: found.location)
                let absoluteOffset = combinedStart + Int64(byte)
                let overlapsPrevious = absoluteOffset < nextMatchOffset
                let matchEnd = combinedStart + Int64(try combinedMap.byteOffset(forUTF16Index: found.location + found.length))
                if absoluteOffset >= end { break }
                let contextStart = max(0, found.location - 45)
                let contextEnd = min(text.length, found.location + found.length + 45)
                let context = text.rangeOfComposedCharacterSequences(for: NSRange(location: contextStart, length: contextEnd - contextStart))
                let excerpt = text.substring(with: context)
                if absoluteOffset >= nextMatchOffset && matchEnd <= end {
                    results.append(SearchResult(id: absoluteOffset, offset: absoluteOffset, excerpt: excerpt, highlight: (found.location - context.location)..<(found.location - context.location + found.length)))
                    nextMatchOffset = matchEnd
                    if results.count == batchLimit {
                        return Batch(results: results, nextOffset: matchEnd, exhausted: matchEnd >= end)
                    }
                }
                // Overlap retained from an earlier segment may begin before
                // the last accepted match; do not let it skip the next match.
                let next = overlapsPrevious
                    ? found.location + 1 : found.location + max(1, found.length)
                range = NSRange(location: next, length: text.length - next)
            }
            carry = suffix
            offset = segment.endOffset
        }
        return Batch(results: results, nextOffset: end, exhausted: true)
    }
}

enum SearchError: LocalizedError {
    case invalidQuery
    var errorDescription: String? { "请输入 2–32 个字符" }
}
