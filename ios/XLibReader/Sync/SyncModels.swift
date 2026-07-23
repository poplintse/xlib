import Foundation

struct SyncBookKey: Codable, Hashable, Sendable {
    let bookHash: String
    let fileSize: Int64
}

struct SyncBookIdentity: Codable, Hashable, Sendable {
    let localBookID: UUID
    let key: SyncBookKey
}

struct LocalProgressSnapshot: Equatable, Sendable {
    let bookID: UUID
    let key: SyncBookKey?
    var offset: Int64
    var readAtMs: Int64
    var localSequence: UInt64

    var progress: Double {
        guard let key, key.fileSize > 0 else { return 0 }
        return min(1, max(0, Double(offset) / Double(key.fileSize)))
    }
}

struct SyncDevice: Codable, Equatable, Identifiable, Sendable {
    let deviceId: UUID
    let deviceName: String
    let platform: String

    var id: UUID { deviceId }
}

struct RemoteProgressSnapshot: Codable, Equatable, Sendable {
    let bookHash: String
    let fileSize: Int64
    let offset: Int64
    let progress: Double
    let readAtMs: Int64
    let version: String
    let device: SyncDevice

    var key: SyncBookKey { SyncBookKey(bookHash: bookHash, fileSize: fileSize) }
}

struct SyncJumpSuggestion: Identifiable, Equatable, Sendable {
    let bookID: UUID
    let remote: RemoteProgressSnapshot

    var id: String { "\(bookID.uuidString):\(remote.version)" }

    func message(now: Date = .now) -> String {
        let percent = remote.progress.formatted(.percent.precision(.fractionLength(2)))
        return "位置：\(remote.offset.formatted())（\(percent)）\n进度于\(Self.relativeTime(from: remote.readAtMs, now: now))保存。"
    }

    private static func relativeTime(from milliseconds: Int64, now: Date) -> String {
        let date = Date(timeIntervalSince1970: Double(milliseconds) / 1_000)
        let totalMinutes = max(0, Int(now.timeIntervalSince(date) / 60))
        if totalMinutes < 1 { return "刚刚" }
        let days = totalMinutes / (24 * 60)
        let hours = totalMinutes % (24 * 60) / 60
        let minutes = totalMinutes % 60
        var parts: [String] = []
        if days > 0 { parts.append("\(days)天") }
        if hours > 0 { parts.append("\(hours)小时") }
        if minutes > 0 || parts.isEmpty { parts.append("\(minutes)分钟") }
        return parts.joined() + "前"
    }
}

enum SyncAvailability: String, Codable, Equatable, Sendable {
    case available
    case offline
    case serviceUnavailable
    case tokenRequired

    var title: String {
        switch self {
        case .available: "已连接"
        case .offline: "离线"
        case .serviceUnavailable: "服务暂不可用"
        case .tokenRequired: "需要重新开启"
        }
    }
}

enum ReaderSyncComparisonState: Equatable, Sendable {
    case pending
    case awaitingJumpDecision
    case completed
    case unavailable
}

struct SyncCredentials: Codable, Equatable, Sendable {
    let token: String
    let userID: UUID
    let email: String
    let device: SyncDevice

    var authorization: SyncAuthorization {
        SyncAuthorization(token: token, deviceID: device.deviceId)
    }
}

struct SyncDeviceRegistration: Codable, Equatable, Sendable {
    let deviceId: UUID
    var deviceName: String
    let platform: String
    let appVersion: String
}

struct SyncAuthorization: Equatable, Sendable {
    let token: String
    let deviceID: UUID
}

struct SyncStartRequest: Encodable, Sendable {
    let email: String
    let device: SyncDeviceRegistration
}

struct SyncStartResponse: Decodable, Sendable {
    struct User: Decodable, Sendable {
        let userId: UUID
        let email: String
    }

    let token: String
    let user: User
    let device: SyncDevice
    let serverTimeMs: Int64

    var credentials: SyncCredentials {
        SyncCredentials(
            token: token,
            userID: user.userId,
            email: user.email,
            device: device
        )
    }
}

struct ProgressPullResponse: Decodable, Sendable {
    let serverTimeMs: Int64
    let items: [RemoteProgressSnapshot]
}

struct ProgressSyncRequest: Encodable, Sendable {
    struct Item: Encodable, Sendable {
        let bookHash: String
        let fileSize: Int64
        let offset: Int64
        let readAtMs: Int64
    }

    let items: [Item]
}

struct ProgressSyncResponse: Decodable, Sendable {
    struct Result: Decodable, Sendable {
        let decision: String
        let timeAdjusted: Bool
        let state: RemoteProgressSnapshot
    }

    let serverTimeMs: Int64
    let results: [Result]
}

struct SyncDeviceListResponse: Decodable, Sendable {
    let items: [SyncDevice]
}

struct SyncHealthResponse: Decodable, Sendable {
    let status: String
}

struct SyncEmptyResponse: Sendable {}

struct SyncAPIErrorEnvelope: Decodable, Sendable {
    struct Detail: Decodable, Sendable {
        let code: String
        let message: String
        let retryable: Bool
        let requestId: String?
    }

    let error: Detail
}

enum SyncAPIError: Error, LocalizedError, Sendable {
    case notConfigured
    case invalidResponse
    case transport(String)
    case http(status: Int, code: String, message: String, retryable: Bool)

    var errorDescription: String? {
        switch self {
        case .notConfigured:
            "同步服务尚未配置。"
        case .invalidResponse:
            "同步服务返回了无法识别的数据。"
        case .transport:
            "暂时无法连接同步服务。"
        case .http(_, _, let message, _):
            message
        }
    }

    var statusCode: Int? {
        if case .http(let status, _, _, _) = self { return status }
        return nil
    }

    var marksServiceUnavailable: Bool {
        switch self {
        case .transport, .invalidResponse:
            true
        case .http(let status, _, _, _):
            status == 429 || status >= 500
        case .notConfigured:
            false
        }
    }
}
