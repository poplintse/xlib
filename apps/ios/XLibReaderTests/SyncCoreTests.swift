import Foundation
import XCTest
@testable import XLibReader

final class SyncCoreTests: XCTestCase {
    func testPreparationAndClampedSamePositionNeverCreateReadingTime() {
        let key = SyncBookKey(bookHash: String(repeating: "a", count: 64), fileSize: 100)
        var session = ReadingSyncSession(id: UUID(), bookID: UUID(), local: .init(
            bookID: UUID(), key: key, offset: 100, readAtMs: 123, localSequence: 0),
            comparisonState: .pending, promptedRemoteVersion: nil, lastObservedSequence: nil, positionReady: false)
        session.record(offset: 20, changedAt: Date(timeIntervalSince1970: 1))
        XCTAssertEqual(session.local.readAtMs, 123)
        session.positionReady = true
        XCTAssertTrue(session.isPreparing)
        session.comparisonState = .completed
        session.record(offset: 200, changedAt: Date(timeIntervalSince1970: 1))
        XCTAssertEqual(session.local.offset, 100)
        XCTAssertEqual(session.local.readAtMs, 123)
        XCTAssertEqual(session.local.localSequence, 0)
        session.record(offset: 90, changedAt: Date(timeIntervalSince1970: 0))
        XCTAssertEqual(session.local.readAtMs, 124)
        XCTAssertEqual(session.local.localSequence, 1)
        session.comparisonState = .unavailable
        session.record(offset: 80, changedAt: Date(timeIntervalSince1970: 2))
        XCTAssertEqual(session.local.readAtMs, 2000)
    }

    func testFormalTimeRemainsMonotonicAtMaximum() {
        XCTAssertEqual(FormalReadingProgress.nextMilliseconds(.distantPast, after: .max), .max)
        let previous = Date(timeIntervalSince1970: 100)
        XCTAssertEqual(FormalReadingProgress.nextDate(previous, after: previous), previous.addingTimeInterval(0.001))
    }

    @MainActor
    func testConfigurationUsesDatabaseAndRejectsInvalidChanges() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let database = testDatabase(root: root)
        try database.setSyncString("Old Reader", for: "sync.device.name.v1")
        try database.setSyncString("old@example.com", for: "sync.email.v1")
        let configuration = SyncConfigurationSession(database: database)
        XCTAssertEqual(configuration.configuredEmailValue, "old@example.com")
        XCTAssertNil(configuration.saveConfiguredEmail("invalid"))
        XCTAssertNil(configuration.saveDeviceName(" "))
        XCTAssertEqual(configuration.deviceRegistration.deviceName, "Old Reader")
        XCTAssertEqual(configuration.saveConfiguredEmail(" NEW@EXAMPLE.COM "), true)
        XCTAssertEqual(database.syncString(for: "sync.email.v1"), "new@example.com")
        XCTAssertEqual(configuration.saveConfiguredEmail("new@example.com"), false)
        let generation = configuration.generation
        configuration.invalidate()
        XCTAssertNotEqual(configuration.generation, generation)
        XCTAssertEqual(configuration.configuredEmailValue, "new@example.com")
    }
}
