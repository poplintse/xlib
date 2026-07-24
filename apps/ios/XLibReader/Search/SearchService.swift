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
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (2...32).contains(needle.count) else { return [] }
        var offset = max(0, startOffset)
        var results: [SearchResult] = []
        var carry = ""
        var lastResultOffset: Int64 = -1
        while offset < book.fileSize && results.count < limit {
            try Task.checkCancellation()
            let segment = try SegmentSource.read(from: url, offset: offset, maximumBytes: 64 * 1_024, encoding: book.encoding)
            guard segment.byteCount > 0 else { break }
            let carryBytes = book.encoding.encodedByteCount(of: carry) ?? 0
            let combinedStart = segment.startOffset - Int64(carryBytes)
            let combined = carry + segment.text
            let combinedMap = try ByteOffsetMap(text: combined, encoding: book.encoding)
            let text = combined as NSString
            var range = NSRange(location: 0, length: text.length)
            while range.length > 0 && results.count < limit {
                let found = text.range(of: needle, options: [.caseInsensitive], range: range)
                if found.location == NSNotFound { break }
                let byte = try combinedMap.byteOffset(forUTF16Index: found.location)
                let absoluteOffset = combinedStart + Int64(byte)
                let contextStart = max(0, found.location - 45)
                let contextEnd = min(text.length, found.location + found.length + 45)
                let excerpt = text.substring(with: NSRange(location: contextStart, length: contextEnd - contextStart))
                if absoluteOffset > lastResultOffset {
                    results.append(SearchResult(id: absoluteOffset, offset: absoluteOffset, excerpt: excerpt, highlight: (found.location - contextStart)..<(found.location - contextStart + found.length)))
                    lastResultOffset = absoluteOffset
                }
                let next = found.location + max(1, found.length)
                range = NSRange(location: next, length: text.length - next)
            }
            carry = String(segment.text.suffix(64))
            offset = segment.endOffset
        }
        return results
    }
}
