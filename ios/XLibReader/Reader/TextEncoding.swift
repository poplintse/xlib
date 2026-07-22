import CoreFoundation
import Foundation

enum TextEncoding: String, Codable, CaseIterable, Sendable {
    case utf8 = "UTF-8"
    case utf16LittleEndian = "UTF-16LE"
    case utf16BigEndian = "UTF-16BE"
    case gb18030 = "GB18030"

    var foundationEncoding: String.Encoding {
        switch self {
        case .utf8:
            return .utf8
        case .utf16LittleEndian:
            return .utf16LittleEndian
        case .utf16BigEndian:
            return .utf16BigEndian
        case .gb18030:
            let cfEncoding = CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)
            return String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(cfEncoding))
        }
    }

    static func detect(at url: URL) throws -> TextEncoding {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let sample = try handle.read(upToCount: 4_097) ?? Data()

        if sample.starts(with: [0xEF, 0xBB, 0xBF]) { return .utf8 }
        if sample.starts(with: [0xFF, 0xFE]) { return .utf16LittleEndian }
        if sample.starts(with: [0xFE, 0xFF]) { return .utf16BigEndian }

        let hasMoreData = sample.count > 4_096
        let inspected = sample.prefix(4_096)
        if String(data: inspected, encoding: .utf8) != nil { return .utf8 }
        if hasMoreData {
            for suffixLength in 1...3 where inspected.count >= suffixLength {
                if String(data: inspected.dropLast(suffixLength), encoding: .utf8) != nil {
                    return .utf8
                }
            }
        }
        return .gb18030
    }

    func decodeCompletePrefix(_ data: Data) throws -> (text: String, bytesConsumed: Int) {
        let maximumTrim = min(4, data.count)
        for trim in 0...maximumTrim {
            let length = data.count - trim
            guard length > 0 || data.isEmpty else { continue }
            let prefix = data.prefix(length)
            guard let text = String(data: prefix, encoding: foundationEncoding),
                  encodedByteCount(of: text) == length else {
                continue
            }
            return (text, length)
        }
        throw ReaderCoreError.invalidCharacterBoundary
    }

    func encodedByteCount(of text: String) -> Int? {
        (text as NSString).data(using: foundationEncoding.rawValue, allowLossyConversion: false)?.count
    }
}

enum ReaderCoreError: Error, Equatable, LocalizedError {
    case invalidCharacterBoundary
    case invalidByteMap
    case invalidRange
    case noTextFitsPage

    var errorDescription: String? {
        switch self {
        case .invalidCharacterBoundary:
            return "文本未从完整字符边界开始"
        case .invalidByteMap:
            return "字符与字节映射不一致"
        case .invalidRange:
            return "文本范围无效"
        case .noTextFitsPage:
            return "当前排版区域无法容纳完整文字"
        }
    }
}
