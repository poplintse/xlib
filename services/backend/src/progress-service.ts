import type { PoolClient } from "pg";
import { effectiveCandidate, progressRatio, type ProgressDecision } from "./arbitration.js";
import type { AuthContext } from "./auth-service.js";
import { Database } from "./database.js";
import { ApiError } from "./errors.js";
import type { ProgressItemInput, ProgressSyncInput } from "./schemas.js";

const MAX_PROGRESS_PER_EMAIL = 10_000;

interface ProgressRow {
  book_hash: string;
  file_size: string;
  offset_bytes: string;
  read_at: Date;
  version: string;
  device_id: string;
  device_uid: string;
  device_name: string;
  platform: "ios" | "android";
}

interface ProgressState {
  bookHash: string;
  fileSize: number;
  offset: number;
  progress: number;
  readAtMs: number;
  version: string;
  device: {
    deviceId: string;
    deviceName: string;
    platform: "ios" | "android";
  };
}

interface ProgressResult {
  decision: ProgressDecision;
  timeAdjusted: boolean;
  state: ProgressState;
}

function stateFromRow(row: ProgressRow): ProgressState {
  const fileSize = Number(row.file_size);
  const offset = Number(row.offset_bytes);
  return {
    bookHash: row.book_hash,
    fileSize,
    offset,
    progress: progressRatio(offset, fileSize),
    readAtMs: row.read_at.getTime(),
    version: row.version,
    device: {
      deviceId: row.device_uid,
      deviceName: row.device_name,
      platform: row.platform,
    },
  };
}

async function selectProgress(
  client: PoolClient,
  userId: string,
  item: ProgressItemInput,
): Promise<ProgressRow> {
  const result = await client.query<ProgressRow>(
    `select encode(rp.book_hash, 'hex') as book_hash,
            rp.file_size, rp.offset_bytes, rp.read_at, rp.version, rp.device_id,
            d.device_uid, d.device_name, d.platform
     from reading_progress rp
     join devices d on d.id = rp.device_id
     where rp.user_id = $1 and rp.book_hash = decode($2, 'hex') and rp.file_size = $3`,
    [userId, item.bookHash, String(item.fileSize)],
  );
  const row = result.rows[0];
  if (!row) throw new Error("progress upsert did not produce a final row");
  return row;
}

export class ProgressService {
  constructor(private readonly database: Database) {}

  async list(auth: AuthContext): Promise<{ serverTimeMs: number; items: ProgressState[] }> {
    const serverTimeMs = Date.now();
    const result = await this.database.pool.query<ProgressRow>(
      `select encode(rp.book_hash, 'hex') as book_hash,
              rp.file_size, rp.offset_bytes, rp.read_at, rp.version, rp.device_id,
              d.device_uid, d.device_name, d.platform
       from reading_progress rp
       join devices d on d.id = rp.device_id
       where rp.user_id = $1
       order by rp.book_hash, rp.file_size`,
      [auth.userId],
    );
    return { serverTimeMs, items: result.rows.map(stateFromRow) };
  }

  async sync(
    auth: AuthContext,
    input: ProgressSyncInput,
  ): Promise<{ serverTimeMs: number; results: ProgressResult[] }> {
    const serverTimeMs = Date.now();
    const indexed = input.items.map((item, index) => ({
      item: effectiveCandidate(item, serverTimeMs),
      index,
    }));
    indexed.sort(
      (left, right) => {
        if (left.item.bookHash < right.item.bookHash) return -1;
        if (left.item.bookHash > right.item.bookHash) return 1;
        return left.item.fileSize - right.item.fileSize;
      },
    );

    return this.database.transaction(async (client) => {
      const activeDevice = await client.query<{ id: string }>(
        `select d.id
         from users u
         join devices d on d.user_id = u.id and d.id = $2 and d.device_uid = $3
         where u.id = $1 and u.status = 'active'
           and d.revoked_at is null
         for update of u`,
        [auth.userId, auth.deviceDbId, auth.deviceId],
      );
      if (!activeDevice.rows[0]) {
        throw new ApiError(403, "DEVICE_FORBIDDEN", "sync identity or device is not available");
      }
      const currentCountResult = await client.query<{ count: string }>(
        "select count(*) as count from reading_progress where user_id = $1",
        [auth.userId],
      );
      let projectedCount = Number(currentCountResult.rows[0]?.count ?? "0");
      for (const { item } of indexed) {
        const exists = await client.query<{ exists: boolean }>(
          `select exists(
             select 1 from reading_progress
             where user_id = $1 and book_hash = decode($2, 'hex') and file_size = $3
           ) as exists`,
          [auth.userId, item.bookHash, String(item.fileSize)],
        );
        if (!exists.rows[0]?.exists) projectedCount += 1;
      }
      if (projectedCount > MAX_PROGRESS_PER_EMAIL) {
        throw new ApiError(
          422,
          "PROGRESS_LIMIT_REACHED",
          `an email can store at most ${MAX_PROGRESS_PER_EMAIL} progress records`,
        );
      }

      const results = new Array<ProgressResult>(input.items.length);
      for (const { item, index } of indexed) {
        const upsert = await client.query<{ id: string }>(
          `insert into reading_progress (
             user_id, book_hash, file_size, offset_bytes, read_at,
             device_id, device_uid_order, version, received_at, updated_at
           ) values ($1, decode($2, 'hex'), $3, $4, $5, $6, $7, 1, now(), now())
           on conflict (user_id, book_hash, file_size) do update set
             offset_bytes = excluded.offset_bytes,
             read_at = excluded.read_at,
             device_id = excluded.device_id,
             device_uid_order = excluded.device_uid_order,
             version = reading_progress.version + 1,
             received_at = now(),
             updated_at = now()
           where excluded.read_at > reading_progress.read_at
              or (
                excluded.read_at = reading_progress.read_at
                and excluded.device_uid_order > reading_progress.device_uid_order
              )
           returning id`,
          [
            auth.userId,
            item.bookHash,
            String(item.fileSize),
            String(item.offset),
            new Date(item.effectiveReadAtMs),
            auth.deviceDbId,
            auth.deviceId,
          ],
        );
        const finalRow = await selectProgress(client, auth.userId, item);
        let decision: ProgressDecision;
        if (upsert.rows.length > 0) {
          decision = "accepted";
        } else if (
          finalRow.device_id === auth.deviceDbId &&
          Number(finalRow.offset_bytes) === item.offset &&
          finalRow.read_at.getTime() === item.effectiveReadAtMs
        ) {
          decision = "unchanged";
        } else {
          decision = "server_kept";
        }
        results[index] = {
          decision,
          timeAdjusted: item.timeAdjusted,
          state: stateFromRow(finalRow),
        };
      }
      return { serverTimeMs, results };
    });
  }

  async deleteAll(auth: AuthContext): Promise<void> {
    await this.database.transaction(async (client) => {
      const activeDevice = await client.query<{ id: string }>(
        `select d.id
         from users u
         join devices d on d.user_id = u.id and d.id = $2 and d.device_uid = $3
         where u.id = $1 and u.status = 'active' and d.revoked_at is null
         for update of u`,
        [auth.userId, auth.deviceDbId, auth.deviceId],
      );
      if (!activeDevice.rows[0]) {
        throw new ApiError(403, "DEVICE_FORBIDDEN", "sync identity or device is not available");
      }
      await client.query("delete from reading_progress where user_id = $1", [auth.userId]);
    });
  }
}
