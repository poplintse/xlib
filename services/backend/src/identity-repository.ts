import type { PoolClient } from "pg";
import type { AuthContext } from "./auth-service.js";
import type { DeviceInput } from "./schemas.js";
import { ApiError } from "./errors.js";

export interface UserRow {
  id: string;
  public_id: string;
  email_normalized: string;
  token_hash: Buffer;
  token_ciphertext: Buffer;
  status: "active" | "disabled" | "deleting";
}

export interface AuthenticatedUserRow {
  id: string;
  public_id: string;
  status: UserRow["status"];
}

export interface DeviceRow {
  id: string;
  device_uid: string;
  device_name: string;
  platform: "ios" | "android";
  app_version: string;
  last_seen_at: Date;
  revoked_at: Date | null;
  created_at: Date;
}

/** Bound to the service's pool for reads or its transaction client for mutations. */
export class IdentityRepository {
  constructor(private readonly sql: Pick<PoolClient, "query">) {}

  async lockActiveRequester(auth: AuthContext): Promise<void> {
    // Acquire the shared per-identity mutation lock first. The device check must
    // be a NEW statement after waiting: a joined pre-lock snapshot can miss revocation.
    const user = await this.sql.query<{ id: string }>(
      "select id from users where id = $1 and status = 'active' for update",
      [auth.userId],
    );
    const device = user.rows[0] ? await this.sql.query<{ id: string }>(
      `select id from devices where user_id = $1 and id = $2 and device_uid = $3
       and revoked_at is null`,
      [auth.userId, auth.deviceDbId, auth.deviceId],
    ) : undefined;
    if (!device?.rows[0]) {
      throw new ApiError(403, "DEVICE_FORBIDDEN", "sync identity or device is not available");
    }
  }

  async findByEmail(email: string): Promise<UserRow | undefined> {
    const result = await this.sql.query<UserRow>(
      `select id, public_id, email_normalized, token_hash, token_ciphertext, status
       from users where email_normalized = $1`,
      [email],
    );
    return result.rows[0];
  }

  async lockUser(id: string, email: string): Promise<UserRow | undefined> {
    const result = await this.sql.query<UserRow>(
      `select id, public_id, email_normalized, token_hash, token_ciphertext, status
       from users where id = $1 and email_normalized = $2 for update`,
      [id, email],
    );
    return result.rows[0];
  }

  async insertUser(
    publicId: string,
    email: string,
    tokenHash: Buffer,
    tokenCiphertext: Buffer,
  ): Promise<UserRow | undefined> {
    const result = await this.sql.query<UserRow>(
      `insert into users (
         public_id, email_normalized, token_hash, token_ciphertext
       ) values ($1, $2, $3, $4)
       returning id, public_id, email_normalized, token_hash, token_ciphertext, status`,
      [publicId, email, tokenHash, tokenCiphertext],
    );
    return result.rows[0];
  }

  async findByTokenHash(tokenHash: Buffer): Promise<AuthenticatedUserRow | undefined> {
    const result = await this.sql.query<AuthenticatedUserRow>(
      `select id, public_id, status from users where token_hash = $1`,
      [tokenHash],
    );
    return result.rows[0];
  }

  async findDevice(userId: string, deviceId: string): Promise<DeviceRow | undefined> {
    const result = await this.sql.query<DeviceRow>(
      `select id, device_uid, device_name, platform, app_version,
              last_seen_at, revoked_at, created_at
       from devices where user_id = $1 and device_uid = $2`,
      [userId, deviceId],
    );
    return result.rows[0];
  }

  async touchDevice(id: string, userId: string): Promise<void> {
    await this.sql.query(
      `update devices set last_seen_at = now()
       where id = $1 and user_id = $2 and last_seen_at < now() - interval '5 minutes'`,
      [id, userId],
    );
  }

  async listDevices(userId: string): Promise<DeviceRow[]> {
    const result = await this.sql.query<DeviceRow>(
      `select id, device_uid, device_name, platform, app_version, last_seen_at, revoked_at, created_at
       from devices where user_id = $1 order by created_at, device_uid`,
      [userId],
    );
    return result.rows;
  }

  async revokeDevice(userId: string, deviceUid: string): Promise<boolean> {
    const result = await this.sql.query<{ id: string }>(
      `update devices set revoked_at = coalesce(revoked_at, now())
       where user_id = $1 and device_uid = $2 returning id`,
      [userId, deviceUid],
    );
    return result.rows.length > 0;
  }

  async insertDevice(
    userId: string,
    input: DeviceInput,
  ): Promise<DeviceRow> {
    const result = await this.sql.query<DeviceRow>(
      `insert into devices (user_id, device_uid, device_name, platform, app_version)
       values ($1, $2, $3, $4, $5)
       returning id, device_uid, device_name, platform, app_version,
                 last_seen_at, revoked_at, created_at`,
      [userId, input.deviceId, input.deviceName, input.platform, input.appVersion],
    );
    return result.rows[0] as DeviceRow;
  }

  async upsertAuthorizedDevice(
    userId: string,
    input: DeviceInput,
  ): Promise<DeviceRow> {
    const result = await this.sql.query<DeviceRow>(
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
