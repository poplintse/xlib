import Foundation
import XCTest
@testable import XLibReader

final class SearchAndTOCTests: XCTestCase {
    func testSearchAndTOCUseAbsoluteByteOffsets() async throws {
        let text = "序言\n第一章 开始\n苹果香蕉苹果\n第二章 继续\n结束"
        let url = FileManager.default.temporaryDirectory.appending(path: "\(UUID().uuidString).txt")
        try text.data(using: .utf8)!.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let size = Int64(try Data(contentsOf: url).count)
        let book = Book(id: UUID(), title: "测试", sourceName: "test.txt", author: "", relativePath: "", fileSize: size, modifiedAt: .now, encoding: .utf8, offset: 0, updatedAt: .now, schemaVersion: 1)
        let results = try await SearchService().search(url: url, book: book, query: "苹果", from: 0)
        XCTAssertEqual(results.count, 2)
        XCTAssertLessThan(results[0].offset, results[1].offset)
        let toc = try await TocService().build(url: url, book: book)
        XCTAssertEqual(toc.map(\.title), ["第一章 开始", "第二章 继续"])
        XCTAssertLessThan(toc[0].offset, toc[1].offset)
    }

    func testSearchFindsMatchAcrossSegmentBoundary() async throws {
        let prefix = String(repeating: "a", count: 64 * 1_024 - 1)
        let text = prefix + "苹果" + "结尾"
        let url = FileManager.default.temporaryDirectory.appending(path: "\(UUID().uuidString).txt")
        let data = text.data(using: .utf8)!
        try data.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let book = Book(id: UUID(), title: "边界", sourceName: "boundary.txt", author: "", relativePath: "", fileSize: Int64(data.count), modifiedAt: .now, encoding: .utf8, offset: 0, updatedAt: .now, schemaVersion: 1)
        let results = try await SearchService().search(url: url, book: book, query: "苹果", from: 0)
        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results.first?.offset, Int64(prefix.utf8.count))
    }

    func testTOCRecoversAfterAnInvalidLeadingByte() async throws {
        let url = FileManager.default.temporaryDirectory.appending(path: "\(UUID().uuidString).txt")
        var data = Data([0xFF])
        data.append("第一章 开始\n正文".data(using: .utf8)!)
        try data.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }

        let book = Book(
            id: UUID(), title: "边界恢复", sourceName: "recovery.txt", author: "", relativePath: "",
            fileSize: Int64(data.count), modifiedAt: .now, encoding: .utf8, offset: 0, updatedAt: .now,
            schemaVersion: Book.schemaVersion
        )
        let toc = try await TocService().build(url: url, book: book)

        XCTAssertEqual(toc.map(\.title), ["第一章 开始"])
        XCTAssertEqual(toc.first?.offset, 1)
    }

    func testTOCUsesVolumeOnlyHeadingsForTopLevelStyling() async throws {
        let text = """
        第一卷 启程
        第一卷 第一章 初见
        第八十五章 继续
        第八十六章 卷土重来
        第二卷
        第八十七章 远行
        卷三：归途
        """
        let url = FileManager.default.temporaryDirectory.appending(path: "\(UUID().uuidString).txt")
        let data = try XCTUnwrap(text.data(using: .utf8))
        try data.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }

        let book = Book(
            id: UUID(), title: "层级", sourceName: "levels.txt", author: "", relativePath: "",
            fileSize: Int64(data.count), modifiedAt: .now, encoding: .utf8, offset: 0, updatedAt: .now,
            schemaVersion: Book.schemaVersion
        )
        let toc = try await TocService().build(url: url, book: book)

        XCTAssertEqual(toc.map(\.level), [0, 1, 1, 1, 0, 1, 0])
    }
}
