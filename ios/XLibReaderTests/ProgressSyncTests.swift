import Foundation
import XCTest
@testable import XLibReader

final class ProgressSyncTests: XCTestCase {
    @MainActor
    func testJumpThresholdIsStrictlyGreaterThanPointZeroZeroOnePercent() {
        let localDeviceID = UUID()
        let remoteDevice = SyncDevice(deviceId: UUID(), deviceName: "iPad", platform: "ios")
        let key = SyncBookKey(bookHash: "hash", fileSize: 100_000)
        let local = LocalProgressSnapshot(
            bookID: UUID(), key: key, offset: 10_000, readAtMs: 1_000, localSequence: 0
        )

        let exactlyAtThreshold = Self.remote(
            key: key, offset: 10_001, readAtMs: 2_000, device: remoteDevice, version: "v1"
        )
        let aboveThreshold = Self.remote(
            key: key, offset: 10_002, readAtMs: 2_000, device: remoteDevice, version: "v2"
        )

        XCTAssertFalse(ProgressSyncCoordinator.shouldSuggestJump(
            local: local, remote: exactlyAtThreshold, currentDeviceID: localDeviceID
        ))
        XCTAssertTrue(ProgressSyncCoordinator.shouldSuggestJump(
            local: local, remote: aboveThreshold, currentDeviceID: localDeviceID
        ))
    }

    @MainActor
    func testJumpIsNotSuggestedForOlderCloudStateOrCurrentDevice() {
        let currentDeviceID = UUID()
        let key = SyncBookKey(bookHash: "hash", fileSize: 100_000)
        let local = LocalProgressSnapshot(
            bookID: UUID(), key: key, offset: 10_000, readAtMs: 2_000, localSequence: 0
        )
        let older = Self.remote(
            key: key,
            offset: 50_000,
            readAtMs: 1_999,
            device: SyncDevice(deviceId: UUID(), deviceName: "Android", platform: "android"),
            version: "v1"
        )
        let sameDevice = Self.remote(
            key: key,
            offset: 50_000,
            readAtMs: 3_000,
            device: SyncDevice(deviceId: currentDeviceID, deviceName: "本机", platform: "ios"),
            version: "v2"
        )

        XCTAssertFalse(ProgressSyncCoordinator.shouldSuggestJump(
            local: local, remote: older, currentDeviceID: currentDeviceID
        ))
        XCTAssertFalse(ProgressSyncCoordinator.shouldSuggestJump(
            local: local, remote: sameDevice, currentDeviceID: currentDeviceID
        ))
    }

    @MainActor
    func testStartupOnlyPullsAndNeverUploadsLocalProgress() async throws {
        let fixture = try makeFixture()
        defer { fixture.cleanup() }
        let spy = SyncAPISpy(pullItems: [])
        let coordinator = makeCoordinator(fixture: fixture, api: await spy.client())

        await coordinator.start()

        let calls = await spy.counts()
        XCTAssertEqual(calls.pull, 1)
        XCTAssertEqual(calls.sync, 0)
    }

    @MainActor
    func testEmailStartsSyncStoresReturnedTokenAndPullsCloudState() async throws {
        let fixture = try makeFixture()
        defer { fixture.cleanup() }
        let vault = CredentialVaultSpy(initial: nil)
        let spy = SyncAPISpy(pullItems: [], startCredentials: fixture.credentials)
        let coordinator = makeCoordinator(
            fixture: fixture,
            api: await spy.client(),
            vault: await vault.client()
        )

        await coordinator.start()
        XCTAssertFalse(coordinator.isSyncEnabled)

        let started = await coordinator.startSync(email: "  Tester@Example.COM ")

        XCTAssertTrue(started)
        XCTAssertEqual(coordinator.email, "tester@example.com")
        let calls = await spy.counts()
        XCTAssertEqual(calls.start, 1)
        XCTAssertEqual(calls.pull, 1)
        XCTAssertEqual(calls.sync, 0)
        let savedToken = await vault.saved()?.token
        let startedEmail = await spy.startedEmail()
        XCTAssertEqual(savedToken, fixture.credentials.token)
        XCTAssertEqual(startedEmail, "tester@example.com")
    }

    func testServerAddressDefaultsToXUnitAndNormalizesSavedValue() {
        let suiteName = "ProgressSyncServerAddress.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        XCTAssertEqual(
            SyncServerConfiguration.resolvedAddress(defaults: defaults, environment: [:]),
            "https://xunit.cc/xlib/backend"
        )
        XCTAssertEqual(
            SyncServerConfiguration.normalizedAddress("  https://sync.example.com/api///  "),
            "https://sync.example.com/api"
        )
        XCTAssertNil(SyncServerConfiguration.normalizedAddress("http://sync.example.com"))
        XCTAssertNil(SyncServerConfiguration.normalizedAddress("https://sync.example.com/api?token=secret"))
    }

    @MainActor
    func testChangingServerAddressClearsOldServerToken() async throws {
        let fixture = try makeFixture()
        defer { fixture.cleanup() }
        let coordinator = makeCoordinator(
            fixture: fixture,
            api: await SyncAPISpy(pullItems: []).client()
        )

        fixture.defaults.set(
            SyncServerConfiguration.defaultAddress,
            forKey: SyncServerConfiguration.credentialServerKey
        )
        await coordinator.start()
        XCTAssertTrue(coordinator.isSyncEnabled)

        let saved = await coordinator.saveServerAddress("https://example.com/custom/backend/")

        XCTAssertTrue(saved)
        XCTAssertEqual(coordinator.serverAddress, "https://example.com/custom/backend")
        XCTAssertFalse(coordinator.isSyncEnabled)
        XCTAssertEqual(
            fixture.defaults.string(forKey: SyncServerConfiguration.storageKey),
            "https://example.com/custom/backend"
        )
    }

    @MainActor
    func testCurrentBookComparesBeforeAnyUploadAndPromptsForNewerCloudProgress() async throws {
        let fixture = try makeFixture(fileByteCount: 100_000)
        defer { fixture.cleanup() }
        let stateStore = SyncStateStore(root: fixture.stateRoot)
        let identity = try await stateStore.identity(for: fixture.book, fileURL: fixture.fileURL)
        let cloud = Self.remote(
            key: identity.key,
            offset: fixture.book.offset + 2,
            readAtMs: milliseconds(fixture.book.updatedAt) + 1_000,
            device: SyncDevice(deviceId: UUID(), deviceName: "iPad Pro", platform: "ios"),
            version: "cloud-v1"
        )
        let spy = SyncAPISpy(pullItems: [cloud])
        let coordinator = makeCoordinator(
            fixture: fixture,
            api: await spy.client(),
            stateStore: stateStore,
            syncInterval: .milliseconds(20)
        )

        await coordinator.start()
        await coordinator.beginReading(book: fixture.book, fileURL: fixture.fileURL)

        XCTAssertEqual(coordinator.jumpSuggestion?.remote.version, "cloud-v1")
        let callsBeforeDecision = await spy.counts()
        XCTAssertEqual(callsBeforeDecision.sync, 0, "进度比较完成前不得上传本机状态")

        coordinator.resolveJump(useRemote: false)
        coordinator.recordLocalProgress(
            bookID: fixture.book.id,
            offset: fixture.book.offset + 100,
            changedAt: fixture.book.updatedAt.addingTimeInterval(2)
        )
        try await Task.sleep(for: .milliseconds(80))

        let callsAfterReading = await spy.counts()
        XCTAssertEqual(callsAfterReading.sync, 1)
        await coordinator.endReading(bookID: fixture.book.id)
    }

    @MainActor
    func testFailedUploadIsNotRepeatedUntilRecovery() async throws {
        let fixture = try makeFixture(fileByteCount: 10_000)
        defer { fixture.cleanup() }
        let spy = SyncAPISpy(pullItems: [], syncError: .transport("offline"))
        let coordinator = makeCoordinator(
            fixture: fixture,
            api: await spy.client(),
            syncInterval: .milliseconds(10),
            healthProbeDelays: [.seconds(60)]
        )

        await coordinator.start()
        await coordinator.beginReading(book: fixture.book, fileURL: fixture.fileURL)
        coordinator.recordLocalProgress(
            bookID: fixture.book.id,
            offset: fixture.book.offset + 10,
            changedAt: fixture.book.updatedAt.addingTimeInterval(1)
        )
        try await Task.sleep(for: .milliseconds(100))

        let calls = await spy.counts()
        XCTAssertEqual(calls.sync, 1)
        XCTAssertEqual(coordinator.availability, .serviceUnavailable)
    }

    func testBookIdentityUsesSHA256AndPersistsCachedSnapshot() async throws {
        let fixture = try makeFixture(contents: Data("abc".utf8))
        defer { fixture.cleanup() }
        let store = SyncStateStore(root: fixture.stateRoot)

        let identity = try await store.identity(for: fixture.book, fileURL: fixture.fileURL)
        XCTAssertEqual(
            identity.key.bookHash,
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        )
        XCTAssertEqual(identity.key.fileSize, 3)

        let snapshot = Self.remote(
            key: identity.key,
            offset: 2,
            readAtMs: 1_000,
            device: SyncDevice(deviceId: UUID(), deviceName: "Pixel", platform: "android"),
            version: "v1"
        )
        try await store.replaceRemote([snapshot])
        let reloaded = SyncStateStore(root: fixture.stateRoot)
        let cached = await reloaded.cachedRemote()
        XCTAssertEqual(cached, [snapshot])
    }

    @MainActor
    private func makeCoordinator(
        fixture: Fixture,
        api: SyncAPIClient,
        vault: SyncCredentialVault? = nil,
        stateStore: SyncStateStore? = nil,
        syncInterval: Duration = .seconds(20),
        healthProbeDelays: [Duration] = [.seconds(60)]
    ) -> ProgressSyncCoordinator {
        ProgressSyncCoordinator(
            api: api,
            vault: vault ?? .constant(fixture.credentials),
            stateStore: stateStore ?? SyncStateStore(root: fixture.stateRoot),
            connectivity: SyncConnectivityMonitor(started: false, initialOnline: true),
            defaults: fixture.defaults,
            syncInterval: syncInterval,
            healthProbeDelays: healthProbeDelays,
            now: { Date(timeIntervalSince1970: 2_000_000_000) }
        )
    }

    private func makeFixture(
        fileByteCount: Int = 1_000,
        contents: Data? = nil
    ) throws -> Fixture {
        let root = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        let fileURL = root.appending(path: "book.txt")
        let stateRoot = root.appending(path: "sync-state")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let data = contents ?? Data(repeating: 0x61, count: fileByteCount)
        try data.write(to: fileURL)
        let changedAt = Date(timeIntervalSince1970: 1_900_000_000)
        let book = Book(
            id: UUID(),
            title: "测试书籍",
            sourceName: "book.txt",
            author: "",
            relativePath: "book.txt",
            fileSize: Int64(data.count),
            modifiedAt: changedAt,
            encoding: .utf8,
            offset: min(10_000, Int64(data.count / 10)),
            updatedAt: changedAt,
            schemaVersion: Book.schemaVersion
        )
        let device = SyncDevice(deviceId: UUID(), deviceName: "测试 iPhone", platform: "ios")
        let credentials = SyncCredentials(
            token: "test-sync-token",
            userID: UUID(),
            email: "tester@example.com",
            device: device
        )
        let suiteName = "ProgressSyncTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.set(device.deviceId.uuidString, forKey: "sync.device.id.v1")
        return Fixture(
            root: root,
            fileURL: fileURL,
            stateRoot: stateRoot,
            book: book,
            credentials: credentials,
            defaults: defaults,
            defaultsSuiteName: suiteName
        )
    }

    private static func remote(
        key: SyncBookKey,
        offset: Int64,
        readAtMs: Int64,
        device: SyncDevice,
        version: String
    ) -> RemoteProgressSnapshot {
        RemoteProgressSnapshot(
            bookHash: key.bookHash,
            fileSize: key.fileSize,
            offset: offset,
            progress: Double(offset) / Double(key.fileSize),
            readAtMs: readAtMs,
            version: version,
            device: device
        )
    }

    private func milliseconds(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1_000).rounded())
    }
}

private struct Fixture {
    let root: URL
    let fileURL: URL
    let stateRoot: URL
    let book: Book
    let credentials: SyncCredentials
    let defaults: UserDefaults
    let defaultsSuiteName: String

    func cleanup() {
        try? FileManager.default.removeItem(at: root)
        defaults.removePersistentDomain(forName: defaultsSuiteName)
    }
}

private actor SyncAPISpy {
    private let pullItems: [RemoteProgressSnapshot]
    private let syncError: SyncAPIError?
    private let startCredentials: SyncCredentials?
    private var startCallCount = 0
    private var pullCallCount = 0
    private var syncCallCount = 0
    private var requestedEmail: String?

    init(
        pullItems: [RemoteProgressSnapshot],
        syncError: SyncAPIError? = nil,
        startCredentials: SyncCredentials? = nil
    ) {
        self.pullItems = pullItems
        self.syncError = syncError
        self.startCredentials = startCredentials
    }

    func client() -> SyncAPIClient {
        SyncAPIClient(
            isConfigured: true,
            startSync: { [weak self] request in
                guard let self else { throw CancellationError() }
                return try await self.start(request)
            },
            pullProgress: { [weak self] _ in
                guard let self else { throw CancellationError() }
                return await self.pull()
            },
            syncProgress: { [weak self] request, _ in
                guard let self else { throw CancellationError() }
                return try await self.sync(request)
            },
            deleteProgress: { _ in },
            listDevices: { _ in [] },
            deleteDevice: { _, _ in },
            health: { true }
        )
    }

    func counts() -> (start: Int, pull: Int, sync: Int) {
        (startCallCount, pullCallCount, syncCallCount)
    }

    func startedEmail() -> String? {
        requestedEmail
    }

    private func start(_ request: SyncStartRequest) throws -> SyncStartResponse {
        startCallCount += 1
        requestedEmail = request.email
        guard let credentials = startCredentials else { throw SyncAPIError.invalidResponse }
        return SyncStartResponse(
            token: credentials.token,
            user: .init(userId: credentials.userID, email: credentials.email),
            device: credentials.device,
            serverTimeMs: 2_000_000_000_000
        )
    }

    private func pull() -> ProgressPullResponse {
        pullCallCount += 1
        return ProgressPullResponse(serverTimeMs: 2_000_000_000_000, items: pullItems)
    }

    private func sync(_ request: ProgressSyncRequest) throws -> ProgressSyncResponse {
        syncCallCount += 1
        if let syncError { throw syncError }
        guard let item = request.items.first else {
            return ProgressSyncResponse(serverTimeMs: 2_000_000_000_000, results: [])
        }
        let state = RemoteProgressSnapshot(
            bookHash: item.bookHash,
            fileSize: item.fileSize,
            offset: item.offset,
            progress: Double(item.offset) / Double(item.fileSize),
            readAtMs: item.readAtMs,
            version: "synced-\(syncCallCount)",
            device: SyncDevice(deviceId: UUID(), deviceName: "测试设备", platform: "ios")
        )
        return ProgressSyncResponse(
            serverTimeMs: 2_000_000_000_000,
            results: [.init(decision: "client_kept", timeAdjusted: false, state: state)]
        )
    }
}

private actor CredentialVaultSpy {
    private var credentials: SyncCredentials?

    init(initial: SyncCredentials?) {
        credentials = initial
    }

    func client() -> SyncCredentialVault {
        SyncCredentialVault(
            load: { [weak self] in await self?.credentials },
            save: { [weak self] credentials in await self?.store(credentials) },
            clear: { [weak self] in await self?.store(nil) }
        )
    }

    func saved() -> SyncCredentials? {
        credentials
    }

    private func store(_ credentials: SyncCredentials?) {
        self.credentials = credentials
    }
}

private extension SyncCredentialVault {
    static func constant(_ credentials: SyncCredentials) -> SyncCredentialVault {
        SyncCredentialVault(
            load: { credentials },
            save: { _ in },
            clear: {}
        )
    }
}
