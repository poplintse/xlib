import Foundation

struct ReadingSyncSession {
    let id: UUID
    let bookID: UUID
    var local: LocalProgressSnapshot
    var comparisonState: ReaderSyncComparisonState
    var promptedRemoteVersion: String?
    var lastObservedSequence: UInt64?
    var positionReady: Bool
    var isPreparing: Bool {
        !positionReady || comparisonState == .pending || comparisonState == .awaitingJumpDecision
    }

    mutating func record(offset: Int64, changedAt: Date) {
        guard positionReady, comparisonState == .completed || comparisonState == .unavailable else { return }
        let safeOffset = max(0, min(local.key?.fileSize ?? .max, offset))
        guard safeOffset != local.offset else { return }
        local.offset = safeOffset
        local.readAtMs = FormalReadingProgress.nextMilliseconds(changedAt, after: local.readAtMs)
        local.localSequence &+= 1
    }

    static func shouldSuggestJump(local: LocalProgressSnapshot, remote: RemoteProgressSnapshot,
                                  currentDeviceID: UUID) -> Bool {
        guard let key = local.key, key == remote.key,
              remote.device.deviceId != currentDeviceID,
              remote.readAtMs > local.readAtMs, key.fileSize > 0 else { return false }
        return Double(abs(remote.offset - local.offset)) / Double(key.fileSize) > 0.000_01
    }
}
