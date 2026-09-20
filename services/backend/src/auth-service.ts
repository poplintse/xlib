import { IdentityRepository, type UserRow, type DeviceRow } from "./identity-repository.js";
import type { AppConfig } from "./config.js";
import { Database } from "./database.js";
import { ApiError } from "./errors.js";
import { deviceIdHeaderSchema, type StartSyncInput } from "./schemas.js";
import {
  createSyncToken,
  decryptToken,
  encryptToken,
  hashToken,
  newPublicId,
  tokenMatchesHash,
} from "./security.js";

export interface AuthContext {
  userId: string;
  userPublicId: string;
  deviceDbId: string;
  deviceId: string;
  deviceName: string;
  platform: "ios" | "android";
}

export interface StartSyncResult {
  token: string;
  user: { userId: string; email: string };
  device: { deviceId: string; deviceName: string; platform: "ios" | "android" };
  serverTimeMs: number;
}

function startSyncResponse(
  user: UserRow,
  device: DeviceRow,
  token: string,
  serverTimeMs: number,
): StartSyncResult {
  return {
    token,
    user: { userId: user.public_id, email: user.email_normalized },
    device: {
      deviceId: device.device_uid,
      deviceName: device.device_name,
      platform: device.platform,
    },
    serverTimeMs,
  };
}

function isUniqueViolation(error: unknown): boolean {
  return typeof error === "object" && error !== null && "code" in error && error.code === "23505";
}

export class AuthService {
  constructor(
    private readonly database: Database,
    private readonly config: AppConfig,
  ) {}

  async startSync(input: StartSyncInput): Promise<StartSyncResult> {
    for (let attempt = 0; attempt < 3; attempt += 1) {
      const existing = await new IdentityRepository(this.database.pool).findByEmail(input.email);
      if (existing) {
        if (existing.status !== "active") {
          throw new ApiError(403, "SYNC_UNAVAILABLE", "sync identity is not available");
        }
        const token = decryptToken(
          existing.token_ciphertext,
          this.config.tokenEncryptionKey,
          existing.email_normalized,
        );
        if (!tokenMatchesHash(token, existing.token_hash)) {
          throw new Error("stored sync token failed integrity verification");
        }
        const result = await this.database.transaction(async (client) => {
          const repository = new IdentityRepository(client);
          const user = await repository.lockUser(existing.id, input.email);
          if (!user) return null;
          if (user.status !== "active") {
            throw new ApiError(403, "SYNC_UNAVAILABLE", "sync identity is not available");
          }
          if (!user.token_ciphertext.equals(existing.token_ciphertext)) return null;
          const device = await repository.upsertAuthorizedDevice(user.id, input.device);
          return startSyncResponse(user, device, token, Date.now());
        });
        if (result) return result;
        continue;
      }

      const token = createSyncToken();
      const tokenHash = hashToken(token);
      const tokenCiphertext = encryptToken(token, this.config.tokenEncryptionKey, input.email);
      try {
        return await this.database.transaction(async (client) => {
          const repository = new IdentityRepository(client);
          const user = await repository.insertUser(
            newPublicId(),
            input.email,
            tokenHash,
            tokenCiphertext,
          );
          if (!user) throw new Error("user insert did not produce a row");
          const device = await repository.insertDevice(user.id, input.device);
          return startSyncResponse(user, device, token, Date.now());
        });
      } catch (error: unknown) {
        if (isUniqueViolation(error)) continue;
        throw error;
      }
    }
    throw new ApiError(503, "SYNC_TEMPORARILY_UNAVAILABLE", "sync is temporarily unavailable", true);
  }

  async authenticate(
    authorization: string | undefined,
    rawDeviceId: string | string[] | undefined,
  ): Promise<AuthContext> {
    const match = authorization?.match(/^Bearer ([A-Za-z0-9_-]{43})$/);
    if (!match?.[1]) throw new ApiError(401, "INVALID_SYNC_TOKEN", "sync token is invalid");
    const parsedDeviceId = deviceIdHeaderSchema.safeParse(rawDeviceId);
    if (!parsedDeviceId.success) {
      throw new ApiError(400, "DEVICE_ID_REQUIRED", "X-Device-Id must be a valid UUID");
    }

    const repository = new IdentityRepository(this.database.pool);
    const user = await repository.findByTokenHash(hashToken(match[1]));
    if (!user) throw new ApiError(401, "INVALID_SYNC_TOKEN", "sync token is invalid");
    if (user.status !== "active") {
      throw new ApiError(403, "SYNC_UNAVAILABLE", "sync identity is not available");
    }

    const device = await repository.findDevice(user.id, parsedDeviceId.data);
    if (!device || device.revoked_at) {
      throw new ApiError(403, "DEVICE_FORBIDDEN", "device is not registered or has been revoked");
    }
    await repository.touchDevice(device.id, user.id);
    return {
      userId: user.id,
      userPublicId: user.public_id,
      deviceDbId: device.id,
      deviceId: device.device_uid,
      deviceName: device.device_name,
      platform: device.platform,
    };
  }

  async listDevices(auth: AuthContext) {
    const devices = await new IdentityRepository(this.database.pool).listDevices(auth.userId);
    return {
      serverTimeMs: Date.now(),
      items: devices.map((device) => ({
        deviceId: device.device_uid,
        deviceName: device.device_name,
        platform: device.platform,
        appVersion: device.app_version,
        lastSeenAtMs: device.last_seen_at.getTime(),
        createdAtMs: device.created_at.getTime(),
        revokedAtMs: device.revoked_at?.getTime() ?? null,
        current: device.id === auth.deviceDbId,
      })),
    };
  }

  async revokeDevice(auth: AuthContext, deviceUid: string): Promise<void> {
    await this.database.transaction(async (client) => {
      const repository = new IdentityRepository(client);
      await repository.lockActiveRequester(auth);
      const revoked = await repository.revokeDevice(auth.userId, deviceUid);
      if (!revoked) throw new ApiError(404, "DEVICE_NOT_FOUND", "device was not found");
    });
  }
}
