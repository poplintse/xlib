import Foundation

struct ByteOffsetMap: Sendable {
    private let text: String
    private let encoding: TextEncoding
    private let characterAnchors: [Int]
    private let byteAnchors: [Int]

    init(text: String, encoding: TextEncoding, stride: Int = 128) throws {
        self.text = text
        self.encoding = encoding
        let safeStride = max(16, stride)
        let source = text as NSString
        var characters = [0]
        var bytes = [0]
        var previousCharacter = 0
        var byteOffset = 0

        while previousCharacter < source.length {
            var nextCharacter = min(source.length, previousCharacter + safeStride)
            if nextCharacter < source.length,
               Self.isLowSurrogate(source.character(at: nextCharacter)),
               nextCharacter > previousCharacter {
                nextCharacter -= 1
            }
            if nextCharacter <= previousCharacter {
                nextCharacter = min(source.length, previousCharacter + 1)
            }
            guard let length = encoding.encodedByteCount(
                of: source.substring(with: NSRange(location: previousCharacter, length: nextCharacter - previousCharacter))
            ) else {
                throw ReaderCoreError.invalidByteMap
            }
            byteOffset += length
            characters.append(nextCharacter)
            bytes.append(byteOffset)
            previousCharacter = nextCharacter
        }

        characterAnchors = characters
        byteAnchors = bytes
    }

    var utf16Length: Int { (text as NSString).length }
    var totalBytes: Int { byteAnchors.last ?? 0 }

    func byteOffset(forUTF16Index index: Int) throws -> Int {
        let source = text as NSString
        var safeIndex = max(0, min(index, source.length))
        if safeIndex > 0,
           safeIndex < source.length,
           Self.isLowSurrogate(source.character(at: safeIndex)) {
            safeIndex -= 1
        }
        let anchor = Self.floorIndex(in: characterAnchors, target: safeIndex)
        let range = NSRange(location: characterAnchors[anchor], length: safeIndex - characterAnchors[anchor])
        guard let delta = encoding.encodedByteCount(of: source.substring(with: range)) else {
            throw ReaderCoreError.invalidByteMap
        }
        return byteAnchors[anchor] + delta
    }

    func utf16Index(forByteOffset offset: Int) throws -> Int {
        let target = max(0, min(offset, totalBytes))
        let anchor = Self.floorIndex(in: byteAnchors, target: target)
        let source = text as NSString
        var characterIndex = characterAnchors[anchor]
        var currentByte = byteAnchors[anchor]

        while characterIndex < source.length {
            let nextCharacter = nextUTF16Boundary(in: source, after: characterIndex)
            let value = source.substring(
                with: NSRange(location: characterIndex, length: nextCharacter - characterIndex)
            )
            guard let length = encoding.encodedByteCount(of: value) else {
                throw ReaderCoreError.invalidByteMap
            }
            if currentByte + length > target { break }
            currentByte += length
            characterIndex = nextCharacter
        }
        return characterIndex
    }

    /// Returns the first complete UTF-16 character boundary whose encoded byte
    /// offset is greater than or equal to `offset`.
    func utf16IndexAtOrAfter(byteOffset offset: Int) throws -> Int {
        let target = max(0, min(offset, totalBytes))
        let floor = try utf16Index(forByteOffset: target)
        let floorByte = try byteOffset(forUTF16Index: floor)
        guard floorByte < target else { return floor }
        return nextUTF16Boundary(in: text as NSString, after: floor)
    }

    private func nextUTF16Boundary(in source: NSString, after index: Int) -> Int {
        guard index + 1 < source.length else { return source.length }
        let current = source.character(at: index)
        let next = source.character(at: index + 1)
        if Self.isHighSurrogate(current), Self.isLowSurrogate(next) {
            return index + 2
        }
        return index + 1
    }

    private static func floorIndex(in values: [Int], target: Int) -> Int {
        var low = 0
        var high = values.count - 1
        while low <= high {
            let middle = (low + high) / 2
            if values[middle] <= target {
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return max(0, high)
    }

    private static func isHighSurrogate(_ value: unichar) -> Bool {
        (0xD800...0xDBFF).contains(value)
    }

    private static func isLowSurrogate(_ value: unichar) -> Bool {
        (0xDC00...0xDFFF).contains(value)
    }
}
