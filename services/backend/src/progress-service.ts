import { ProgressRepository, type ProgressRow } from "./progress-repository.js";
import { IdentityRepository } from "./identity-repository.js";
import { effectiveCandidate, progressRatio, type ProgressDecision } from "./arbitration.js";
import type { AuthContext } from "./auth-service.js";
import { Database } from "./database.js";
import { ApiError } from "./errors.js";
import type { ProgressBookKey, ProgressSyncInput } from "./schemas.js";

const MAX_PROGRESS_PER_EMAIL = 10_000;

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

export class ProgressService {
  constructor(private readonly database: Database) {}

  async list(auth: AuthContext): Promise<{ serverTimeMs: number; items: ProgressState[] }> {
    const serverTimeMs = Date.now();
    const rows = await new ProgressRepository(this.database.pool).list(auth.userId);
    return { serverTimeMs, items: rows.map(stateFromRow) };
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
      await new IdentityRepository(client).lockActiveRequester(auth);
      const repository = new ProgressRepository(client);
      let projectedCount = await repository.count(auth.userId);
      for (const { item } of indexed) {
        if (!await repository.exists(auth.userId, item)) projectedCount += 1;
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
        const accepted = await repository.upsert(auth, item);
        const finalRow = await repository.select(auth.userId, item);
        let decision: ProgressDecision;
        if (accepted) {
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

  async deleteBook(auth: AuthContext, book: ProgressBookKey): Promise<void> {
    await this.database.transaction(async (client) => {
      await new IdentityRepository(client).lockActiveRequester(auth);
      await new ProgressRepository(client).deleteBook(auth.userId, book);
    });
  }
}
