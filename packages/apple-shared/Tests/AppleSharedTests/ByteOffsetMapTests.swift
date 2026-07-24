import Foundation
import Testing
@testable import AppleShared

@Test
func byteOffsetsPreserveUnicodeBoundaries() throws {
    let map = try ByteOffsetMap(text: "甲🙂乙", encoding: .utf8)

    #expect(try map.byteOffset(forUTF16Index: 1) == 3)
    #expect(try map.utf16Index(forByteOffset: 4) == 1)
    #expect(map.totalBytes == 10)
}

@Test
func detectsUTF8WithIncompleteSampleSuffix() throws {
    let url = FileManager.default.temporaryDirectory
        .appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: url) }

    var data = Data(repeating: 0x61, count: 4_095)
    data.append(contentsOf: [0xE4, 0xB8, 0xAD])
    try data.write(to: url)

    #expect(try TextEncoding.detect(at: url) == .utf8)
}
