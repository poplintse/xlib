import type { PoolClient } from "pg";
import type { AuthContext } from "./auth-service.js";
import type { EffectiveCandidate } from "./arbitration.js";
import type { ProgressBookKey } from "./schemas.js";

export interface ProgressRow {
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

/** No connection acquisition here: the service supplies its transaction client. */
export class ProgressRepository {
  constructor(private readonly sql: Pick<PoolClient, "query">) {}

  async select(
    userId: string,
    item: ProgressBookKey,
  ): Promise<ProgressRow> {
    const result = await this.sql.query<ProgressRow>(
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

  async list(userId: string): Promise<ProgressRow[]> {
    const result = await this.sql.query<ProgressRow>(
      `select encode(rp.book_hash, 'hex') as book_hash,
              rp.file_size, rp.offset_bytes, rp.read_at, rp.version, rp.device_id,
              d.device_uid, d.device_name, d.platform
       from reading_progress rp
       join devices d on d.id = rp.device_id
       where rp.user_id = $1
       order by rp.book_hash, rp.file_size`,
      [userId],
    );
    return result.rows;
  }

  async count(userId: string): Promise<number> {
    const currentCountResult = await this.sql.query<{ count: string }>(
      "select count(*) as count from reading_progress where user_id = $1",
      [userId],
    );
    return Number(currentCountResult.rows[0]?.count ?? "0");
  }

  async exists(userId: string, item: ProgressBookKey): Promise<boolean> {
    const exists = await this.sql.query<{ exists: boolean }>(
      `select exists(
         select 1 from reading_progress
         where user_id = $1 and book_hash = decode($2, 'hex') and file_size = $3
       ) as exists`,
      [userId, item.bookHash, String(item.fileSize)],
    );
    return exists.rows[0]?.exists ?? false;
  }

  async upsert(auth: AuthContext, item: EffectiveCandidate): Promise<boolean> {
    const upsert = await this.sql.query<{ id: string }>(
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
    return upsert.rows.length > 0;
  }

  async deleteBook(userId: string, book: ProgressBookKey): Promise<void> {
    await this.sql.query(
      "delete from reading_progress where user_id = $1 and book_hash = decode($2, 'hex') and file_size = $3",
      [userId, book.bookHash, String(book.fileSize)],
    );
  }
}
