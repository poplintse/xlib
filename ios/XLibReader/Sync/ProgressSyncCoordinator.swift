import Foundation
import Observation
import UIKit

@MainActor
@Observable
final class ProgressSyncCoordinator {
    private struct ReaderSession {
        let id: UUID
        let bookID: UUID
        var local: LocalProgressSnapshot
        var comparisonState: ReaderSyncComparisonState
        var promptedRemoteVersion: String?
        var lastObservedSequence: UInt64?
    }

    private var api: SyncAPIClient
    private let vault: SyncCredentialVault
    private let stateStore: SyncStateStore
    private let connectivity: SyncConnectivityMonitor
    private let defaults: UserDefaults
    private let managesLiveAPI: Bool
    private let syncInterval: Duration
    private let healthProbeDelays: [Duration]
    private let now: @Sendable () -> Date
    private let deviceRegistration: SyncDeviceRegistration

    private var credentials: SyncCredentials?
    private var remoteByKey: [SyncBookKey: RemoteProgressSnapshot] = [:]
    private var currentSession: ReaderSession?
    private var syncTimerTask: Task<Void, Never>?
    private var healthProbeTask: Task<Void, Never>?
    private var started = false
    private var appIsActive = true
    private var freshPullCompleted = false

    private(set) var availability: SyncAvailability = .available
    private(set) var lastSuccessAt: Date?
    private(set) var lastFailureMessage: String?
    private(set) var isWorking = false
    private(set) var devices: [SyncDevice] = []
    private(set) var serverAddress: String
    var jumpSuggestion: SyncJumpSuggestion?

    init(
        api: SyncAPIClient? = nil,
        vault: SyncCredentialVault = .live(),
        stateStore: SyncStateStore = SyncStateStore(),
        connectivity: SyncConnectivityMonitor = SyncConnectivityMonitor(),
        defaults: UserDefaults = .standard,
        syncInterval: Duration = .seconds(20),
        healthProbeDelays: [Duration] = [.seconds(30), .seconds(60), .seconds(120), .seconds(300)],
        now: @escaping @Sendable () -> Date = { .now }
    ) {
        let address = SyncServerConfiguration.resolvedAddress(defaults: defaults)
        serverAddress = address
        if let api {
            self.api = api
            managesLiveAPI = false
        } else {
            self.api = .live(address: address)
            managesLiveAPI = true
        }
        self.vault = vault
        self.stateStore = stateStore
        self.connectivity = connectivity
        self.defaults = defaults
        self.syncInterval = syncInterval
        self.healthProbeDelays = healthProbeDelays
        self.now = now
        deviceRegistration = Self.loadDeviceRegistration(defaults: defaults)

        connectivity.setCallback { [weak self] online in
            Task { @MainActor [weak self] in
                await self?.networkChanged(isOnline: online)
            }
        }
    }

    var isServiceConfigured: Bool { api.isConfigured }
    var isSyncEnabled: Bool { credentials != nil }
    var email: String? { credentials?.email }
    var currentDeviceName: String { deviceRegistration.deviceName }
    var currentDeviceID: UUID { deviceRegistration.deviceId }

    var statusTitle: String {
        guard api.isConfigured else { return "服务未配置" }
        guard credentials != nil else { return "未开启" }
        return availability.title
    }

    func start() async {
        guard !started else { return }
        started = true
        let cached = await stateStore.cachedRemote()
        remoteByKey = Dictionary(uniqueKeysWithValues: cached.map { ($0.key, $0) })
        credentials = await vault.load()
        if credentials != nil {
            if let credentialServer = defaults.string(
                forKey: SyncServerConfiguration.credentialServerKey
            ), credentialServer != serverAddress {
                credentials = nil
                await vault.clear()
                remoteByKey.removeAll()
                try? await stateStore.clearRemote()
            } else {
                // Tokens created before server-address configuration are treated as
                // belonging to the currently resolved server, then scoped from here on.
                defaults.set(serverAddress, forKey: SyncServerConfiguration.credentialServerKey)
            }
        }
        guard credentials != nil else { return }
        guard connectivity.isOnline() else {
            availability = .offline
            return
        }
        await pullOnly()
    }

    func appBecameActive() async {
        appIsActive = true
        guard started, credentials != nil else { return }
        guard connectivity.isOnline() else {
            availability = .offline
            return
        }
        freshPullCompleted = false
        await pullOnly()
        await prepareCurrentSessionAfterPull()
    }

    func appEnteredBackground() async {
        appIsActive = false
        syncTimerTask?.cancel()
        syncTimerTask = nil
        await syncCurrentProgress(forceLatest: true)
    }

    func startSync(email: String) async -> Bool {
        guard api.isConfigured else {
            lastFailureMessage = SyncAPIError.notConfigured.localizedDescription
            return false
        }
        guard connectivity.isOnline() else {
            availability = .offline
            lastFailureMessage = "当前网络不可用。"
            return false
        }

        isWorking = true
        lastFailureMessage = nil
        defer { isWorking = false }
        let request = SyncStartRequest(
            email: email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
            device: deviceRegistration
        )
        do {
            let response = try await api.startSync(request)
            credentials = response.credentials
            await vault.save(response.credentials)
            defaults.set(serverAddress, forKey: SyncServerConfiguration.credentialServerKey)
            availability = .available
            freshPullCompleted = false
            await pullOnly()
            return true
        } catch is CancellationError {
            return false
        } catch {
            handle(error)
            return false
        }
    }

    func disableSync() async {
        syncTimerTask?.cancel()
        healthProbeTask?.cancel()
        credentials = nil
        currentSession = nil
        jumpSuggestion = nil
        devices = []
        freshPullCompleted = false
        remoteByKey.removeAll()
        try? await stateStore.clearRemote()
        await vault.clear()
        defaults.removeObject(forKey: SyncServerConfiguration.credentialServerKey)
        availability = .available
        lastFailureMessage = nil
    }

    func saveServerAddress(_ value: String) async -> Bool {
        guard let normalized = SyncServerConfiguration.normalizedAddress(value) else {
            lastFailureMessage = "请输入有效的 HTTPS 服务器地址。"
            return false
        }
        if normalized == serverAddress {
            defaults.set(normalized, forKey: SyncServerConfiguration.storageKey)
            lastFailureMessage = nil
            return true
        }

        await disableSync()
        serverAddress = normalized
        defaults.set(normalized, forKey: SyncServerConfiguration.storageKey)
        if managesLiveAPI { api = .live(address: normalized) }
        lastSuccessAt = nil
        lastFailureMessage = nil
        return true
    }

    func refreshRemoteStates() async {
        guard credentials != nil else { return }
        freshPullCompleted = false
        await pullOnly()
    }

    func deleteCloudProgress() async -> Bool {
        guard credentials != nil else { return false }
        isWorking = true
        defer { isWorking = false }
        do {
            try await authorized { authorization in try await self.api.deleteProgress(authorization) }
            remoteByKey.removeAll()
            freshPullCompleted = true
            try? await stateStore.clearRemote()
            lastSuccessAt = now()
            return true
        } catch {
            handle(error)
            return false
        }
    }

    func loadDevices() async {
        guard credentials != nil else { return }
        do {
            devices = try await authorized { authorization in try await self.api.listDevices(authorization) }
            lastFailureMessage = nil
        } catch {
            handle(error)
        }
    }

    func removeDevice(_ device: SyncDevice) async {
        guard device.deviceId != deviceRegistration.deviceId else { return }
        do {
            try await authorized { authorization in
                try await self.api.deleteDevice(device.deviceId, authorization)
            }
            devices.removeAll { $0.deviceId == device.deviceId }
        } catch {
            handle(error)
        }
    }

    func beginReading(book: Book, fileURL: URL) async {
        syncTimerTask?.cancel()
        jumpSuggestion = nil
        guard credentials != nil, api.isConfigured else {
            currentSession = nil
            return
        }

        let sessionID = UUID()
        currentSession = ReaderSession(
            id: sessionID,
            bookID: book.id,
            local: LocalProgressSnapshot(
                bookID: book.id,
                key: nil,
                offset: book.offset,
                readAtMs: Self.milliseconds(book.updatedAt),
                localSequence: 0
            ),
            comparisonState: .pending,
            promptedRemoteVersion: nil,
            lastObservedSequence: nil
        )

        do {
            let identity = try await stateStore.identity(for: book, fileURL: fileURL)
            guard var session = currentSession, session.id == sessionID else { return }
            session.local = LocalProgressSnapshot(
                bookID: book.id,
                key: identity.key,
                offset: session.local.offset,
                readAtMs: session.local.readAtMs,
                localSequence: session.local.localSequence
            )
            currentSession = session
        } catch is CancellationError {
            return
        } catch {
            guard currentSession?.id == sessionID else { return }
            currentSession?.comparisonState = .unavailable
            lastFailureMessage = "暂时无法计算当前书籍的同步标识。"
            return
        }

        guard connectivity.isOnline() else {
            availability = .offline
            currentSession?.comparisonState = .unavailable
            return
        }
        if !freshPullCompleted { await pullOnly() }
        guard currentSession?.id == sessionID else { return }
        compareCurrentSession()
    }

    func recordLocalProgress(bookID: UUID, offset: Int64, changedAt: Date) {
        guard var session = currentSession, session.bookID == bookID else { return }
        let previous = session.local.readAtMs
        session.local.offset = max(0, min(session.local.key?.fileSize ?? .max, offset))
        session.local.readAtMs = max(Self.milliseconds(changedAt), previous + 1)
        session.local.localSequence &+= 1
        currentSession = session
    }

    @discardableResult
    func resolveJump(useRemote: Bool) -> SyncJumpSuggestion? {
        guard let suggestion = jumpSuggestion,
              var session = currentSession,
              session.bookID == suggestion.bookID else { return nil }
        if useRemote {
            session.local.offset = suggestion.remote.offset
            session.local.readAtMs = suggestion.remote.readAtMs
        }
        session.promptedRemoteVersion = suggestion.remote.version
        session.comparisonState = .completed
        currentSession = session
        jumpSuggestion = nil
        startSyncTimerIfNeeded()
        return suggestion
    }

    func endReading(bookID: UUID) async {
        guard currentSession?.bookID == bookID else { return }
        syncTimerTask?.cancel()
        syncTimerTask = nil
        await syncCurrentProgress(forceLatest: true)
        currentSession = nil
        jumpSuggestion = nil
    }

    static func shouldSuggestJump(
        local: LocalProgressSnapshot,
        remote: RemoteProgressSnapshot,
        currentDeviceID: UUID
    ) -> Bool {
        guard let key = local.key,
              key == remote.key,
              remote.device.deviceId != currentDeviceID,
              remote.readAtMs > local.readAtMs,
              key.fileSize > 0 else { return false }
        let difference = Double(abs(remote.offset - local.offset)) / Double(key.fileSize)
        return difference > 0.000_01
    }

    private func compareCurrentSession() {
        guard var session = currentSession, let key = session.local.key else { return }
        guard freshPullCompleted else {
            session.comparisonState = .unavailable
            currentSession = session
            return
        }
        if let remote = remoteByKey[key],
           Self.shouldSuggestJump(
               local: session.local,
               remote: remote,
               currentDeviceID: deviceRegistration.deviceId
           ),
           session.promptedRemoteVersion != remote.version {
            session.comparisonState = .awaitingJumpDecision
            currentSession = session
            jumpSuggestion = SyncJumpSuggestion(bookID: session.bookID, remote: remote)
            return
        }
        session.comparisonState = .completed
        currentSession = session
        startSyncTimerIfNeeded()
    }

    private func startSyncTimerIfNeeded() {
        syncTimerTask?.cancel()
        guard appIsActive,
              credentials != nil,
              currentSession?.comparisonState == .completed else { return }
        syncTimerTask = Task { [weak self, syncInterval] in
            while !Task.isCancelled {
                do { try await Task.sleep(for: syncInterval) } catch { return }
                guard !Task.isCancelled else { return }
                await self?.syncCurrentProgress(forceLatest: false)
            }
        }
    }

    private func syncCurrentProgress(forceLatest: Bool) async {
        guard credentials != nil,
              availability == .available,
              connectivity.isOnline(),
              var session = currentSession,
              session.comparisonState == .completed,
              let key = session.local.key else { return }
        if !forceLatest, session.lastObservedSequence == session.local.localSequence { return }
        session.lastObservedSequence = session.local.localSequence
        currentSession = session

        let request = ProgressSyncRequest(items: [
            .init(
                bookHash: key.bookHash,
                fileSize: key.fileSize,
                offset: session.local.offset,
                readAtMs: session.local.readAtMs
            )
        ])
        do {
            let response = try await authorized { authorization in
                try await self.api.syncProgress(request, authorization)
            }
            if let state = response.results.first?.state {
                remoteByKey[state.key] = state
                try? await stateStore.replaceRemote(Array(remoteByKey.values))
            }
            availability = .available
            lastSuccessAt = now()
            lastFailureMessage = nil
        } catch is CancellationError {
            return
        } catch {
            handle(error)
        }
    }

    private func pullOnly() async {
        guard credentials != nil, api.isConfigured else { return }
        guard connectivity.isOnline() else {
            availability = .offline
            freshPullCompleted = false
            return
        }
        do {
            let response = try await authorized { authorization in
                try await self.api.pullProgress(authorization)
            }
            remoteByKey = Dictionary(uniqueKeysWithValues: response.items.map { ($0.key, $0) })
            try? await stateStore.replaceRemote(response.items)
            freshPullCompleted = true
            availability = .available
            lastSuccessAt = now()
            lastFailureMessage = nil
            healthProbeTask?.cancel()
            healthProbeTask = nil
        } catch is CancellationError {
            return
        } catch {
            freshPullCompleted = false
            handle(error)
        }
    }

    private func prepareCurrentSessionAfterPull() async {
        guard currentSession != nil else { return }
        compareCurrentSession()
        if currentSession?.comparisonState == .completed {
            await syncCurrentProgress(forceLatest: true)
        }
    }

    private func networkChanged(isOnline: Bool) async {
        guard started else { return }
        if !isOnline {
            availability = .offline
            freshPullCompleted = false
            syncTimerTask?.cancel()
            syncTimerTask = nil
            healthProbeTask?.cancel()
            healthProbeTask = nil
            if currentSession != nil { currentSession?.comparisonState = .unavailable }
            return
        }
        guard credentials != nil else {
            availability = .available
            return
        }
        availability = .available
        await pullOnly()
        await prepareCurrentSessionAfterPull()
    }

    private func authorized<T>(_ operation: (SyncAuthorization) async throws -> T) async throws -> T {
        guard let credentials else {
            throw SyncAPIError.http(
                status: 401,
                code: "SYNC_TOKEN_REQUIRED",
                message: "请重新输入邮箱开启同步。",
                retryable: false
            )
        }
        return try await operation(credentials.authorization)
    }

    private func handle(_ error: Error) {
        if error is CancellationError { return }
        guard let apiError = error as? SyncAPIError else {
            availability = connectivity.isOnline() ? .serviceUnavailable : .offline
            lastFailureMessage = "同步服务暂时不可用。"
            if availability == .serviceUnavailable { startHealthProbes() }
            return
        }
        lastFailureMessage = apiError.localizedDescription
        if apiError.statusCode == 401 || apiError.statusCode == 403 {
            availability = .tokenRequired
            syncTimerTask?.cancel()
        } else if apiError.marksServiceUnavailable {
            availability = connectivity.isOnline() ? .serviceUnavailable : .offline
            syncTimerTask?.cancel()
            if availability == .serviceUnavailable { startHealthProbes() }
        }
    }

    private func startHealthProbes() {
        guard healthProbeTask == nil, credentials != nil, connectivity.isOnline() else { return }
        healthProbeTask = Task { [weak self, healthProbeDelays] in
            var index = 0
            while !Task.isCancelled {
                let delay = healthProbeDelays[min(index, healthProbeDelays.count - 1)]
                do { try await Task.sleep(for: delay) } catch { return }
                guard let self, !Task.isCancelled else { return }
                do {
                    if try await self.api.health() {
                        await self.serviceRecovered()
                        return
                    }
                } catch is CancellationError {
                    return
                } catch {}
                index += 1
            }
        }
    }

    private func serviceRecovered() async {
        healthProbeTask?.cancel()
        healthProbeTask = nil
        availability = .available
        freshPullCompleted = false
        await pullOnly()
        await prepareCurrentSessionAfterPull()
    }

    private static func milliseconds(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1_000).rounded())
    }

    private static func loadDeviceRegistration(defaults: UserDefaults) -> SyncDeviceRegistration {
        let key = "sync.device.id.v1"
        let deviceID: UUID
        if let raw = defaults.string(forKey: key), let existing = UUID(uuidString: raw) {
            deviceID = existing
        } else {
            deviceID = UUID()
            defaults.set(deviceID.uuidString.lowercased(), forKey: key)
        }
        let name = UIDevice.current.name.trimmingCharacters(in: .whitespacesAndNewlines)
        return SyncDeviceRegistration(
            deviceId: deviceID,
            deviceName: name.isEmpty ? "iOS 设备" : name,
            platform: "ios",
            appVersion: AppVersion.displayText
        )
    }
}
