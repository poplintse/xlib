import type { PoolClient } from "pg";
import type { AppConfig } from "./config.js";
import { Database } from "./database.js";
import { ApiError } from "./errors.js";
import { deviceIdHeaderSchema, type DeviceInput, type StartSyncInput } from "./schemas.js";
import {
  createSyncToken,
  decryptToken,
  encryptToken,
  hashToken,
  newPublicId,
  tokenMatchesHash,
} from "./security.js";

interface UserRow {
  id: string;
  public_id: string;
  email_normalized: string;
  token_hash: Buffer;
  token_ciphertext: Buffer;
  status: "active" | "disabled" | "deleting";
}

interface AuthenticatedUserRow {
  id: string;
  public_id: string;
  status: UserRow["status"];
}

interface DeviceRow {
  id: string;
  device_uid: string;
  device_name: string;
  platform: "ios" | "android";
  app_version: string;
  last_seen_at: Date;
  revoked_at: Date | null;
  created_at: Date;
}

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
      const found = await this.database.pool.query<UserRow>(
        `select id, public_id, email_normalized, token_hash, token_ciphertext, status
         from users where email_normalized = $1`,
        [input.email],
      );
      const existing = found.rows[0];
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
          const locked = await client.query<UserRow>(
            `select id, public_id, email_normalized, token_hash, token_ciphertext, status
             from users where id = $1 and email_normalized = $2 for update`,
            [existing.id, input.email],
          );
          const user = locked.rows[0];
          if (!user) return null;
          if (user.status !== "active") {
            throw new ApiError(403, "SYNC_UNAVAILABLE", "sync identity is not available");
          }
          if (!user.token_ciphertext.equals(existing.token_ciphertext)) return null;
          const device = await this.upsertAuthorizedDevice(client, user.id, input.device);
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
          const inserted = await client.query<UserRow>(
            `insert into users (
               public_id, email_normalized, token_hash, token_ciphertext
             ) values ($1, $2, $3, $4)
             returning id, public_id, email_normalized, token_hash, token_ciphertext, status`,
            [newPublicId(), input.email, tokenHash, tokenCiphertext],
          );
          const user = inserted.rows[0] as UserRow;
          const device = await this.insertDevice(client, user.id, input.device);
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

    const userResult = await this.database.pool.query<AuthenticatedUserRow>(
      `select id, public_id, status from users where token_hash = $1`,
      [hashToken(match[1])],
    );
    const user = userResult.rows[0];
    if (!user) throw new ApiError(401, "INVALID_SYNC_TOKEN", "sync token is invalid");
    if (user.status !== "active") {
      throw new ApiError(403, "SYNC_UNAVAILABLE", "sync identity is not available");
    }

    const deviceResult = await this.database.pool.query<DeviceRow>(
      `select id, device_uid, device_name, platform, app_version,
              last_seen_at, revoked_at, created_at
       from devices where user_id = $1 and device_uid = $2`,
      [user.id, parsedDeviceId.data],
    );
    const device = deviceResult.rows[0];
    if (!device || device.revoked_at) {
      throw new ApiError(403, "DEVICE_FORBIDDEN", "device is not registered or has been revoked");
    }
    await this.database.pool.query(
      `update devices set last_seen_at = now()
       where id = $1 and user_id = $2 and last_seen_at < now() - interval '5 minutes'`,
      [device.id, user.id],
    );
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
    const result = await this.database.pool.query<DeviceRow>(
      `select id, device_uid, device_name, platform, app_version, last_seen_at, revoked_at, created_at
       from devices where user_id = $1 order by created_at, device_uid`,
      [auth.userId],
    );
    return {
      serverTimeMs: Date.now(),
      items: result.rows.map((device) => ({
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
      await this.lockActiveRequester(client, auth);
      const device = await client.query<{ id: string }>(
        `update devices set revoked_at = coalesce(revoked_at, now())
         where user_id = $1 and device_uid = $2 returning id`,
        [auth.userId, deviceUid],
      );
      if (!device.rows[0]) throw new ApiError(404, "DEVICE_NOT_FOUND", "device was not found");
    });
  }

  async deleteAccount(auth: AuthContext): Promise<void> {
    await this.database.transaction(async (client) => {
      await this.lockActiveRequester(client, auth);
      await client.query(
        "update users set status = 'deleting', updated_at = now() where id = $1",
        [auth.userId],
      );
      await client.query("delete from users where id = $1", [auth.userId]);
    });
  }

  private async lockActiveRequester(client: PoolClient, auth: AuthContext): Promise<void> {
    const requester = await client.query<{ id: string }>(
      `select u.id
       from users u
       join devices d on d.user_id = u.id and d.id = $2 and d.device_uid = $3
       where u.id = $1 and u.status = 'active' and d.revoked_at is null
       for update of u`,
      [auth.userId, auth.deviceDbId, auth.deviceId],
    );
    if (!requester.rows[0]) {
      throw new ApiError(403, "DEVICE_FORBIDDEN", "sync identity or device is not available");
    }
  }

  private async insertDevice(
    client: PoolClient,
    userId: string,
    input: DeviceInput,
  ): Promise<DeviceRow> {
    const result = await client.query<DeviceRow>(
      `insert into devices (user_id, device_uid, device_name, platform, app_version)
       values ($1, $2, $3, $4, $5)
       returning id, device_uid, device_name, platform, app_version,
                 last_seen_at, revoked_at, created_at`,
      [userId, input.deviceId, input.deviceName, input.platform, input.appVersion],
    );
    return result.rows[0] as DeviceRow;
  }

  private async upsertAuthorizedDevice(
    client: PoolClient,
    userId: string,
    input: DeviceInput,
  ): Promise<DeviceRow> {
    const result = await client.query<DeviceRow>(
      `insert into devices (user_id, device_uid, device_name, platform, app_version)
       values ($1, $2, $3, $4, $5)
       on conflict (user_id, device_uid) do update set
         device_name = excluded.device_name,
         platform = excluded.platform,
         app_version = excluded.app_version,
         last_seen_at = now(),
         revoked_at = null
       returning id, device_uid, device_name, platform, app_version,
                 last_seen_at, revoked_at, created_at`,
      [userId, input.deviceId, input.deviceName, input.platform, input.appVersion],
    );
    return result.rows[0] as DeviceRow;
  }
}
