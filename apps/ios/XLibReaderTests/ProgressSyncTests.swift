import Foundation
import XCTest
@testable import XLibReader

final class ProgressSyncTests: XCTestCase {
    @MainActor
    func testFailedPullKeepsLocalReadingAvailableWithoutUploading() async throws {
        let fixture = try makeFixture()
        defer { fixture.cleanup() }
        let spy = SyncAPISpy(pullItems: [])
        let coordinator = makeCoordinator(fixture: fixture, api: await spy.client(), syncInterval: .milliseconds(10))
        await coordinator.start()
        await spy.failPull()
        await coordinator.beginReading(book: fixture.book, fileURL: fixture.fileURL)
        XCTAssertFalse(coordinator.isPreparingReading(bookID: fixture.book.id))
        coordinator.recordLocalProgress(bookID: fixture.book.id, offset: 200, changedAt: .now)
        await coordinator.appEnteredBackground()
        await coordinator.endReading(bookID: fixture.book.id)
        let counts = await spy.counts()
        XCTAssertEqual(counts.sync, 0)
    }

    @MainActor
    func testFailedDeletionDoesNotPauseOrClearLocalReadingSession() async throws {
        let fixture = try makeFixture()
        defer { fixture.cleanup() }
        let spy = SyncAPISpy(pullItems: [])
        let coordinator = makeCoordinator(fixture: fixture, api: await spy.client())
        await coordinator.start()
        await coordinator.beginReading(book: fixture.book, fileURL: fixture.fileURL)
        await spy.failDeletion()
        let deleted = await coordinator.deleteCloudProgress(book: fixture.book, fileURL: fixture.fileURL)
        XCTAssertFalse(deleted)
        XCTAssertNotNil(coordinator.lastFailureMessage)
        _ = await coordinator.syncRefresh()
        await coordinator.appEnteredBackground()
        let counts = await spy.counts()
        XCTAssertEqual(counts.sync, 1)
    }

    @MainActor
    func testPositioningGatesUploadsAndDoesNotChangeReadingTime() async throws {
        let fixture = try makeFixture()
        defer { fixture.cleanup() }
        let spy = SyncAPISpy(pullItems: [])
        let coordinator = makeCoordinator(fixture: fixture, api: await spy.client(), syncInterval: .milliseconds(15))
        await coordinator.start()
        await coordinator.beginReading(book: fixture.book, fileURL: fixture.fileURL, positionReady: false)
        coordinator.recordLocalProgress(bookID: fixture.book.id, offset: 900, changedAt: .now)
        try await Task.sleep(for: .milliseconds(60))
        let before = await spy.counts()
        XCTAssertEqual(before.sync, 0)
        coordinator.readingPositionReady(bookID: fixture.book.id)
        await coordinator.appEnteredBackground()
        let items = await spy.uploadedItems()
        XCTAssertEqual(items.last?.offset, fixture.book.offset)
        XCTAssertEqual(items.last?.readAtMs, milliseconds(fixture.book.updatedAt))
    }

    @MainActor
    func testRemoteChoiceWaitsForPositionAndShowsBothProgressValues() async throws {
        let fixture = try makeFixture()
        defer { fixture.cleanup() }
        let stateStore = SyncStateStore(root: fixture.stateRoot)
        let key = try await stateStore.identity(for: fixture.book, fileURL: fixture.fileURL).key
        let remote = Self.remote(key: key, offset: 50, readAtMs: milliseconds(fixture.book.updatedAt) + 10,
                                 device: .init(deviceId: UUID(), deviceName: "另一设备", platform: "ios"), version: "new")
        let spy = SyncAPISpy(pullItems: [remote])
        let coordinator = makeCoordinator(fixture: fixture, api: await spy.client(), stateStore: stateStore)
        await coordinator.start()
        await coordinator.beginReading(book: fixture.book, fileURL: fixture.fileURL)
        let suggestion = try XCTUnwrap(coordinator.jumpSuggestion)
        XCTAssertEqual(suggestion.localOffset, 100)
        XCTAssertTrue(suggestion.message().contains("当前阅读进度"))
        XCTAssertTrue(suggestion.message().contains("云端阅读进度"))
        coordinator.resolveJump(useRemote: true)
        await coordinator.appEnteredBackground()
        let before = await spy.counts()
        XCTAssertEqual(before.sync, 0)
        coordinator.readingPositionReady(bookID: fixture.book.id)
        await coordinator.appEnteredBackground()
        let items = await spy.uploadedItems()
        XCTAssertEqual(items.last?.offset, 50, "更近时间的回读位置也要保留")
        XCTAssertEqual(items.last?.readAtMs, remote.readAtMs)
    }

    @MainActor
    func testOldStartResponseCannotRestoreCredentialsAfterConfigurationChange() async throws {
        let fixture = try makeFixture()
        defer { fixture.cleanup() }
        let gate = SyncTestGate()
        let spy = SyncAPISpy(pullItems: [], startCredentials: fixture.credentials)
        await spy.setStartGate(gate)
        let vault = CredentialVaultSpy(initial: nil)
        let coordinator = makeCoordinator(fixture: fixture, api: await spy.client(), vault: await vault.client())
        await coordinator.start()
        let starting = Task { await coordinator.startSync(email: fixture.credentials.email) }
        await gate.waitUntilEntered()
        _ = await coordinator.saveConfiguredEmail("changed@example.com")
        await gate.release()
        let result = await starting.value
        XCTAssertFalse(result)
        XCTAssertFalse(coordinator.isSyncEnabled)
        XCTAssertEqual(coordinator.configuredEmail, "changed@example.com")
        let saved = await vault.saved()
        XCTAssertNil(saved)
    }

    @MainActor
    func testOldPullCannotRestoreCloudStateAfterServerChange() async throws {
        let fixture = try makeFixture()
        defer { fixture.cleanup() }
        let spy = SyncAPISpy(pullItems: [])
        let coordinator = makeCoordinator(fixture: fixture, api: await spy.client())
        await coordinator.start()
        let gate = SyncTestGate()
        await spy.setPullGate(gate)
        let pulling = Task { await coordinator.syncRefresh() }
        await gate.waitUntilEntered()
        _ = await coordinator.saveServerAddress("https://new.example.com")
        await gate.release()
        let result = await pulling.value
        XCTAssertFalse(result)
        XCTAssertFalse(coordinator.isSyncEnabled)
        XCTAssertNil(coordinator.lastSuccessAt)
        XCTAssertNil(coordinator.jumpSuggestion)
    }

    @MainActor
    func testDeletionWaitsForUploadAndPauseSurvivesRefreshAndConfiguration() async throws {
        let fixture = try makeFixture()
        defer { fixture.cleanup() }
        let spy = SyncAPISpy(pullItems: [], startCredentials: fixture.credentials)
        let coordinator = makeCoordinator(fixture: fixture, api: await spy.client())
        let readingID = UUID()
        await coordinator.start()
        await coordinator.beginReading(book: fixture.book, fileURL: fixture.fileURL, sessionID: readingID)
        let gate = SyncTestGate()
        await spy.setSyncGate(gate)
        let upload = Task { await coordinator.appEnteredBackground() }
        await gate.waitUntilEntered()
        let deleting = Task { await coordinator.deleteCloudProgress(book: fixture.book, fileURL: fixture.fileURL) }
        try await Task.sleep(for: .milliseconds(20))
        let before = await spy.events()
        XCTAssertFalse(before.contains("delete"))
        await gate.release()
        await upload.value
        let deleted = await deleting.value
        XCTAssertTrue(deleted)
        let events = await spy.events()
        XCTAssertEqual(events.suffix(2), ["upload", "delete"])
        _ = await coordinator.syncRefresh()
        await coordinator.appEnteredBackground()
        _ = await coordinator.saveDeviceName("新设备名")
        _ = await coordinator.startConfiguredSync()
        await coordinator.appEnteredBackground()
        let paused = await spy.counts()
        XCTAssertEqual(paused.sync, 1)
        await coordinator.endReading(bookID: fixture.book.id)
        await coordinator.beginReading(book: fixture.book, fileURL: fixture.fileURL, sessionID: readingID)
        await coordinator.appEnteredBackground()
        let sameReading = await spy.counts()
        XCTAssertEqual(sameReading.sync, 1)
        await coordinator.endReading(bookID: fixture.book.id)
        await coordinator.beginReading(book: fixture.book, fileURL: fixture.fileURL)
        await coordinator.appEnteredBackground()
        let reopened = await spy.counts()
        XCTAssertEqual(reopened.sync, 2)
    }

    @MainActor
    func testOfflineRecoveryComparesLatestLocalStateBeforeUploading() async throws {
        let fixture = try makeFixture()
        defer { fixture.cleanup() }
        let spy = SyncAPISpy(pullItems: [])
        let monitor = SyncConnectivityMonitor(started: false, initialOnline: false)
        let coordinator = ProgressSyncCoordinator(api: await spy.client(), vault: .constant(fixture.credentials),
            stateStore: SyncStateStore(root: fixture.stateRoot), connectivity: monitor, defaults: fixture.defaults)
        await coordinator.start()
        await coordinator.beginReading(book: fixture.book, fileURL: fixture.fileURL)
        let readAt = fixture.book.updatedAt.addingTimeInterval(30)
        coordinator.recordLocalProgress(bookID: fixture.book.id, offset: 250, changedAt: readAt)
        let gate = SyncTestGate()
        await spy.setPullGate(gate)
        monitor.setOnlineForTesting(true)
        await gate.waitUntilEntered()
        let before = await spy.counts()
        XCTAssertEqual(before.sync, 0)
        coordinator.recordLocalProgress(bookID: fixture.book.id, offset: 300, changedAt: readAt.addingTimeInterval(1))
        await gate.release()
        try await Task.sleep(for: .milliseconds(40))
        let items = await spy.uploadedItems()
        XCTAssertEqual(items.last?.offset, 300)
        XCTAssertEqual(items.last?.readAtMs, milliseconds(readAt.addingTimeInterval(1)))
        await coordinator.endReading(bookID: fixture.book.id)
    }

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

    @MainActor
    func testSyncRefreshStartsConfiguredSyncAndRejectsIncompleteConfiguration() async throws {
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
        let incomplete = await coordinator.syncRefresh()
        XCTAssertFalse(incomplete)
        XCTAssertEqual(coordinator.lastFailureMessage, "请先完成同步配置。")
        let emailConfigured = await coordinator.saveConfiguredEmail("  Reader@Example.COM ")
        let longDeviceNameSaved = await coordinator.saveDeviceName(String(repeating: "a", count: 21))
        let deviceNameSaved = await coordinator.saveDeviceName("  我的 iPhone  ")
        XCTAssertTrue(emailConfigured)
        XCTAssertFalse(longDeviceNameSaved)
        XCTAssertTrue(deviceNameSaved)
        XCTAssertEqual(coordinator.configuredEmail, "reader@example.com")
        XCTAssertEqual(coordinator.currentDeviceName, "我的 iPhone")

        let started = await coordinator.syncRefresh()
        let startedEmail = await spy.startedEmail()
        XCTAssertTrue(started)
        XCTAssertTrue(coordinator.isSyncEnabled)
        XCTAssertEqual(startedEmail, "reader@example.com")
    }

    @MainActor
    func testDeviceRemovalUsesAuthorizedDeviceIDToProtectCurrentDevice() async throws {
        let fixture = try makeFixture()
        defer { fixture.cleanup() }
        let authorizedDevice = SyncDevice(deviceId: UUID(), deviceName: "当前 iPhone", platform: "ios")
        let remoteDevice = SyncDevice(deviceId: UUID(), deviceName: "iPad", platform: "ios")
        let credentials = SyncCredentials(
            token: fixture.credentials.token,
            userID: fixture.credentials.userID,
            email: fixture.credentials.email,
            device: authorizedDevice
        )
        let spy = SyncAPISpy(pullItems: [], devices: [authorizedDevice, remoteDevice])
        let coordinator = makeCoordinator(
            fixture: fixture,
            api: await spy.client(),
            vault: .constant(credentials)
        )

        await coordinator.start()
        await coordinator.loadDevices()

        XCTAssertEqual(coordinator.currentDeviceID, authorizedDevice.deviceId)
        let protectedCurrentDevice = await coordinator.removeDevice(authorizedDevice)
        let removedRemoteDevice = await coordinator.removeDevice(remoteDevice)
        let deletedDeviceIDs = await spy.deletedDeviceIDs()
        XCTAssertFalse(protectedCurrentDevice)
        XCTAssertTrue(removedRemoteDevice)
        XCTAssertEqual(deletedDeviceIDs, [remoteDevice.deviceId])
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

    func testDeleteDeviceSendsValidEmptyJSONBody() async throws {
        DeleteRequestURLProtocol.store.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [DeleteRequestURLProtocol.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let client = SyncAPIClient.live(address: "https://sync.example.com", session: session)
        let authorization = SyncAuthorization(token: "test-token", deviceID: UUID())

        try await client.deleteDevice(UUID(), authorization)

        let request = try XCTUnwrap(DeleteRequestURLProtocol.store.request)
        XCTAssertEqual(request.httpMethod, "DELETE")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertEqual(DeleteRequestURLProtocol.store.body, Data("{}".utf8))
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
    func testChangingLocalSyncConfigurationClearsCurrentSyncInformation() async throws {
        let fixture = try makeFixture()
        defer { fixture.cleanup() }
        let vault = CredentialVaultSpy(initial: nil)
        let cloud = Self.remote(
            key: SyncBookKey(bookHash: "book", fileSize: 100),
            offset: 20,
            readAtMs: 1_000,
            device: SyncDevice(deviceId: UUID(), deviceName: "iPad", platform: "ios"),
            version: "v1"
        )
        let spy = SyncAPISpy(pullItems: [cloud], startCredentials: fixture.credentials)
        let stateStore = SyncStateStore(root: fixture.stateRoot)
        let coordinator = makeCoordinator(
            fixture: fixture,
            api: await spy.client(),
            vault: await vault.client(),
            stateStore: stateStore,
            syncInterval: .milliseconds(20)
        )

        await coordinator.start()
        let started = await coordinator.startSync(email: fixture.credentials.email)
        XCTAssertTrue(started)
        XCTAssertTrue(coordinator.isSyncEnabled)
        let cachedBeforeConfigurationChange = await stateStore.cachedRemote()
        XCTAssertFalse(cachedBeforeConfigurationChange.isEmpty)

        let saved = await coordinator.saveConfiguredEmail("new@example.com")
        XCTAssertTrue(saved)

        XCTAssertEqual(coordinator.configuredEmail, "new@example.com")
        XCTAssertFalse(coordinator.isSyncEnabled)
        XCTAssertNil(coordinator.lastSuccessAt)
        let cachedAfterConfigurationChange = await stateStore.cachedRemote()
        let savedCredentials = await vault.saved()
        XCTAssertTrue(cachedAfterConfigurationChange.isEmpty)
        XCTAssertNil(savedCredentials)

        try await Task.sleep(for: .milliseconds(80))

        let calls = await spy.counts()
        let automaticallyStartedEmail = await spy.startedEmail()
        XCTAssertEqual(calls.start, 2)
        XCTAssertTrue(coordinator.isSyncEnabled)
        XCTAssertEqual(automaticallyStartedEmail, "new@example.com")
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
        XCTAssertGreaterThanOrEqual(callsAfterReading.sync, 2)
        await coordinator.endReading(bookID: fixture.book.id)
    }

    @MainActor
    func testEnteringBackgroundUploadsUnchangedReadingProgress() async throws {
        let fixture = try makeFixture()
        defer { fixture.cleanup() }
        let spy = SyncAPISpy(pullItems: [])
        let coordinator = makeCoordinator(
            fixture: fixture,
            api: await spy.client(),
            syncInterval: .seconds(60)
        )

        await coordinator.start()
        await coordinator.beginReading(book: fixture.book, fileURL: fixture.fileURL)
        await coordinator.appEnteredBackground()

        let calls = await spy.counts()
        XCTAssertEqual(calls.sync, 1)
    }

    @MainActor
    func testScheduledSyncNeverUploadsStoredProgressWithoutAnActiveReader() async throws {
        let fixture = try makeFixture()
        defer { fixture.cleanup() }
        let libraryStore = LibraryStore(root: fixture.root.appending(path: "library"))
        let book = try await libraryStore.importBook(from: fixture.fileURL)
        try await libraryStore.saveProgress(bookID: book.id, offset: 42)
        let spy = SyncAPISpy(pullItems: [])
        let coordinator = makeCoordinator(
            fixture: fixture,
            api: await spy.client(),
            syncInterval: .milliseconds(20)
        )

        await coordinator.start()
        try await Task.sleep(for: .milliseconds(60))

        let calls = await spy.counts()
        XCTAssertEqual(calls.sync, 0)
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

    func testBookDeletionTargetsOnlyTheExactBook() async throws {
        DeleteRequestURLProtocol.store.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [DeleteRequestURLProtocol.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let client = SyncAPIClient.live(address: "https://sync.example.com", session: session)
        let key = SyncBookKey(bookHash: String(repeating: "a", count: 64), fileSize: 100)
        let authorization = SyncAuthorization(token: "test-token", deviceID: UUID())
        try await client.deleteBookProgress(key, authorization)
        let request = try XCTUnwrap(DeleteRequestURLProtocol.store.request)
        XCTAssertEqual(request.httpMethod, "DELETE")
        XCTAssertEqual(request.url?.path, "/v1/progress/\(key.bookHash)/100")
        XCTAssertEqual(DeleteRequestURLProtocol.store.body, Data("{}".utf8))
        DeleteRequestURLProtocol.store.reset()
        do {
            try await client.deleteBookProgress(.init(bookHash: "../account", fileSize: 100), authorization)
            XCTFail("Invalid identity must not send a deletion")
        } catch {
            XCTAssertNil(DeleteRequestURLProtocol.store.request)
        }
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

private final class DeleteRequestStore: @unchecked Sendable {
    private let lock = NSLock()
    private var storedRequest: URLRequest?
    private var storedBody: Data?

    var request: URLRequest? {
        lock.withLock { storedRequest }
    }

    var body: Data? {
        lock.withLock { storedBody }
    }

    func record(_ request: URLRequest, body: Data?) {
        lock.withLock {
            storedRequest = request
            storedBody = body
        }
    }

    func reset() {
        lock.withLock {
            storedRequest = nil
            storedBody = nil
        }
    }
}

private final class DeleteRequestURLProtocol: URLProtocol {
    static let store = DeleteRequestStore()

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        Self.store.record(request, body: Self.readBody(from: request))
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: 204,
            httpVersion: nil,
            headerFields: nil
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data())
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    private static func readBody(from request: URLRequest) -> Data? {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }

        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1_024)
        while true {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
    }
}

private actor SyncAPISpy {
    private let pullItems: [RemoteProgressSnapshot]
    private let devices: [SyncDevice]
    private let syncError: SyncAPIError?
    private let startCredentials: SyncCredentials?
    private var startCallCount = 0
    private var pullCallCount = 0
    private var syncCallCount = 0
    private var requestedEmail: String?
    private var deletedDeviceIDsValue: [UUID] = []
    private var startGate: SyncTestGate?
    private var pullGate: SyncTestGate?
    private var syncGate: SyncTestGate?
    private var recordedItems: [ProgressSyncRequest.Item] = []
    private var recordedEvents: [String] = []
    private var pullFails = false
    private var deletionFails = false

    func setStartGate(_ gate: SyncTestGate) { startGate = gate }
    func setPullGate(_ gate: SyncTestGate) { pullGate = gate }
    func setSyncGate(_ gate: SyncTestGate) { syncGate = gate }
    func uploadedItems() -> [ProgressSyncRequest.Item] { recordedItems }
    func events() -> [String] { recordedEvents }
    func failPull() { pullFails = true }
    func failDeletion() { deletionFails = true }
    private func deleteBook() throws {
        if deletionFails { throw SyncAPIError.http(status: 400, code: "test", message: "删除失败", retryable: false) }
        recordedEvents.append("delete")
    }

    init(
        pullItems: [RemoteProgressSnapshot],
        syncError: SyncAPIError? = nil,
        startCredentials: SyncCredentials? = nil,
        devices: [SyncDevice] = []
    ) {
        self.pullItems = pullItems
        self.syncError = syncError
        self.startCredentials = startCredentials
        self.devices = devices
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
                return try await self.pull()
            },
            syncProgress: { [weak self] request, _ in
                guard let self else { throw CancellationError() }
                return try await self.sync(request)
            },
            deleteBookProgress: { [weak self] _, _ in try await self?.deleteBook() },
            listDevices: { [weak self] _ in
                guard let self else { throw CancellationError() }
                return await self.listDevices()
            },
            deleteDevice: { [weak self] deviceID, _ in
                guard let self else { throw CancellationError() }
                await self.deleteDevice(deviceID)
            },
            health: { true }
        )
    }

    func counts() -> (start: Int, pull: Int, sync: Int) {
        (startCallCount, pullCallCount, syncCallCount)
    }

    func startedEmail() -> String? {
        requestedEmail
    }

    func deletedDeviceIDs() -> [UUID] {
        deletedDeviceIDsValue
    }

    private func start(_ request: SyncStartRequest) async throws -> SyncStartResponse {
        startCallCount += 1
        requestedEmail = request.email
        if let startGate { await startGate.enter() }
        guard let credentials = startCredentials else { throw SyncAPIError.invalidResponse }
        return SyncStartResponse(
            token: credentials.token,
            user: .init(userId: credentials.userID, email: credentials.email),
            device: credentials.device,
            serverTimeMs: 2_000_000_000_000
        )
    }

    private func pull() async throws -> ProgressPullResponse {
        pullCallCount += 1
        if let pullGate { await pullGate.enter() }
        if pullFails { throw SyncAPIError.transport("test failure") }
        return ProgressPullResponse(serverTimeMs: 2_000_000_000_000, items: pullItems)
    }

    private func listDevices() -> [SyncDevice] {
        devices
    }

    private func deleteDevice(_ deviceID: UUID) {
        deletedDeviceIDsValue.append(deviceID)
    }

    private func sync(_ request: ProgressSyncRequest) async throws -> ProgressSyncResponse {
        syncCallCount += 1
        recordedItems.append(contentsOf: request.items)
        if let syncGate { await syncGate.enter() }
        recordedEvents.append("upload")
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

private actor SyncTestGate {
    private var entered = false
    private var released = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var entryWaiters: [CheckedContinuation<Void, Never>] = []

    func enter() async {
        entered = true
        for waiter in entryWaiters { waiter.resume() }
        entryWaiters = []
        if !released { await withCheckedContinuation { waiters.append($0) } }
    }

    func waitUntilEntered() async {
        if !entered { await withCheckedContinuation { entryWaiters.append($0) } }
    }

    func release() {
        released = true
        for waiter in waiters { waiter.resume() }
        waiters = []
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
