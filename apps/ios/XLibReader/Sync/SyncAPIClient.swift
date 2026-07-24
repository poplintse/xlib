import Foundation

struct SyncAPIClient: Sendable {
    let isConfigured: Bool
    var startSync: @Sendable (SyncStartRequest) async throws -> SyncStartResponse
    var pullProgress: @Sendable (SyncAuthorization) async throws -> ProgressPullResponse
    var syncProgress: @Sendable (_ request: ProgressSyncRequest, _ authorization: SyncAuthorization) async throws -> ProgressSyncResponse
    var deleteProgress: @Sendable (SyncAuthorization) async throws -> Void
    var listDevices: @Sendable (SyncAuthorization) async throws -> [SyncDevice]
    var deleteDevice: @Sendable (_ deviceID: UUID, _ authorization: SyncAuthorization) async throws -> Void
    var health: @Sendable () async throws -> Bool

    static func live(bundle: Bundle = .main, session: URLSession = .shared) -> SyncAPIClient {
        live(address: SyncServerConfiguration.resolvedAddress(bundle: bundle), session: session)
    }

    static func live(address: String, session: URLSession = .shared) -> SyncAPIClient {
        guard let normalized = SyncServerConfiguration.normalizedAddress(address),
              let baseURL = URL(string: normalized) else { return .unconfigured }
        let transport = SyncHTTPTransport(baseURL: baseURL, session: session)
        return SyncAPIClient(
            isConfigured: true,
            startSync: { request in
                try await transport.send(method: "POST", path: "v1/auth/start-sync", body: request)
            },
            pullProgress: { authorization in
                try await transport.send(method: "GET", path: "v1/progress", authorization: authorization)
            },
            syncProgress: { request, authorization in
                try await transport.send(
                    method: "POST", path: "v1/progress/sync", body: request, authorization: authorization
                )
            },
            deleteProgress: { authorization in
                try await transport.sendWithoutResponse(
                    method: "DELETE", path: "v1/progress", authorization: authorization
                )
            },
            listDevices: { authorization in
                let response: SyncDeviceListResponse = try await transport.send(
                    method: "GET", path: "v1/devices", authorization: authorization
                )
                return response.items
            },
            deleteDevice: { deviceID, authorization in
                try await transport.sendWithoutResponse(
                    method: "DELETE",
                    path: "v1/devices/\(deviceID.uuidString.lowercased())",
                    authorization: authorization
                )
            },
            health: {
                let response: SyncHealthResponse = try await transport.send(method: "GET", path: "health")
                return response.status == "ok"
            }
        )
    }

    static let unconfigured = SyncAPIClient(
        isConfigured: false,
        startSync: { _ in throw SyncAPIError.notConfigured },
        pullProgress: { _ in throw SyncAPIError.notConfigured },
        syncProgress: { _, _ in throw SyncAPIError.notConfigured },
        deleteProgress: { _ in throw SyncAPIError.notConfigured },
        listDevices: { _ in throw SyncAPIError.notConfigured },
        deleteDevice: { _, _ in throw SyncAPIError.notConfigured },
        health: { throw SyncAPIError.notConfigured }
    )

}

private actor SyncHTTPTransport {
    private let baseURL: URL
    private let session: URLSession
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(baseURL: URL, session: URLSession) {
        self.baseURL = baseURL
        self.session = session
    }

    func send<Response: Decodable & Sendable>(
        method: String,
        path: String,
        authorization: SyncAuthorization? = nil
    ) async throws -> Response {
        try await send(method: method, path: path, bodyData: nil, authorization: authorization)
    }

    func send<Body: Encodable & Sendable, Response: Decodable & Sendable>(
        method: String,
        path: String,
        body: Body,
        authorization: SyncAuthorization? = nil
    ) async throws -> Response {
        let data = try encoder.encode(body)
        return try await send(method: method, path: path, bodyData: data, authorization: authorization)
    }

    func sendWithoutResponse(
        method: String,
        path: String,
        authorization: SyncAuthorization? = nil
    ) async throws {
        _ = try await execute(method: method, path: path, bodyData: nil, authorization: authorization)
    }

    private func send<Response: Decodable & Sendable>(
        method: String,
        path: String,
        bodyData: Data?,
        authorization: SyncAuthorization?
    ) async throws -> Response {
        let data = try await execute(
            method: method, path: path, bodyData: bodyData, authorization: authorization
        )
        do {
            return try decoder.decode(Response.self, from: data)
        } catch {
            throw SyncAPIError.invalidResponse
        }
    }

    private func execute(
        method: String,
        path: String,
        bodyData: Data?,
        authorization: SyncAuthorization?
    ) async throws -> Data {
        let url = path.split(separator: "/").reduce(baseURL) { partial, component in
            partial.appending(path: String(component))
        }
        var request = URLRequest(url: url, timeoutInterval: 12)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(UUID().uuidString.lowercased(), forHTTPHeaderField: "X-Request-Id")
        if let authorization {
            request.setValue("Bearer \(authorization.token)", forHTTPHeaderField: "Authorization")
            request.setValue(authorization.deviceID.uuidString.lowercased(), forHTTPHeaderField: "X-Device-Id")
        }
        request.httpBody = bodyData

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw SyncAPIError.transport(String(describing: error))
        }

        guard let http = response as? HTTPURLResponse else { throw SyncAPIError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            let envelope = try? decoder.decode(SyncAPIErrorEnvelope.self, from: data)
            throw SyncAPIError.http(
                status: http.statusCode,
                code: envelope?.error.code ?? "HTTP_\(http.statusCode)",
                message: envelope?.error.message ?? "同步服务请求失败。",
                retryable: envelope?.error.retryable ?? (http.statusCode >= 500)
            )
        }
        return data
    }
}
