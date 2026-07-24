import AppleShared
import Foundation

struct TextSegment: Sendable {
    let startOffset: Int64
    let text: String
    let byteCount: Int
    let byteMap: ByteOffsetMap

    var endOffset: Int64 { startOffset + Int64(byteCount) }
}

struct CacheSnapshot: Sendable {
    let fileSize: Int64
    let startOffset: Int64
    let text: String
    let byteMap: ByteOffsetMap

    var endOffset: Int64 { startOffset + Int64(byteMap.totalBytes) }

    init(fileSize: Int64, startOffset: Int64, text: String, byteMap: ByteOffsetMap) throws {
        guard startOffset >= 0,
              endOffsetValue(startOffset: startOffset, bytes: byteMap.totalBytes) <= fileSize else {
            throw ReaderCoreError.invalidRange
        }
        self.fileSize = fileSize
        self.startOffset = startOffset
        self.text = text
        self.byteMap = byteMap
    }
}

private func endOffsetValue(startOffset: Int64, bytes: Int) -> Int64 {
    startOffset + Int64(bytes)
}

enum SegmentSource {
    static func read(
        from url: URL,
        offset: Int64,
        maximumBytes: Int,
        encoding: TextEncoding
    ) throws -> TextSegment {
        let values = try url.resourceValues(forKeys: [.fileSizeKey])
        let fileSize = Int64(values.fileSize ?? 0)
        let safeOffset = max(0, min(offset, fileSize))
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(safeOffset))
        let requested = min(max(0, maximumBytes), Int(max(0, fileSize - safeOffset)))
        let data = try handle.read(upToCount: requested) ?? Data()
        let decoded = try encoding.decodeCompletePrefix(data)
        let map = try ByteOffsetMap(text: decoded.text, encoding: encoding)
        guard map.totalBytes == decoded.bytesConsumed else {
            throw ReaderCoreError.invalidByteMap
        }
        return TextSegment(
            startOffset: safeOffset,
            text: decoded.text,
            byteCount: decoded.bytesConsumed,
            byteMap: map
        )
    }
}
