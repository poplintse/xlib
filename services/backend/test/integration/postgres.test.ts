import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import pg from "pg";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { AuthService, type StartSyncResult } from "../../src/auth-service.js";
import { Database } from "../../src/database.js";
import { ProgressService } from "../../src/progress-service.js";
import { testConfig } from "../config-fixture.js";

const { Client } = pg;
const adminUrl = process.env.TEST_DATABASE_URL;
const schema = `xlib_test_${process.pid}_${Date.now()}`;
let admin: pg.Client;
let database: Database;
let authService: AuthService;
let progressService: ProgressService;
let firstStart: StartSyncResult;
let secondStart: StartSyncResult;

function withSearchPath(url: string): string {
  const parsed = new URL(url);
  parsed.searchParams.set("options", `-c search_path=${schema}`);
  return parsed.toString();
}

const integration = adminUrl ? describe : describe.skip;

integration("PostgreSQL service integration", () => {
  beforeAll(async () => {
    admin = new Client({ connectionString: adminUrl });
    await admin.connect();
    await admin.query(`create schema ${schema}`);
    await admin.query(`set search_path to ${schema}`);
    for (const name of ["001_initial.sql", "002_require_fixed_sync_schema.sql"]) {
      const migration = await readFile(resolve(process.cwd(), `migrations/${name}`), "utf8");
      await admin.query(migration);
    }
    const config = testConfig(withSearchPath(adminUrl as string));
    database = new Database(config);
    authService = new AuthService(database, config);
    progressService = new ProgressService(database);
  });

  afterAll(async () => {
    await database?.close();
    if (admin) {
      await admin.query("set search_path to public");
      await admin.query(`drop schema ${schema} cascade`);
      await admin.end();
    }
  });

  it("creates a fixed token, restores the same token, and registers a new device", async () => {
    firstStart = await authService.startSync({
      email: "reader@example.com",
      device: {
        deviceId: "10000000-0000-0000-0000-000000000000",
        deviceName: "Android One",
        platform: "android",
        appVersion: "1.0.0",
      },
    });
    secondStart = await authService.startSync({
      email: "reader@example.com",
      device: {
        deviceId: "20000000-0000-0000-0000-000000000000",
        deviceName: "iPhone",
        platform: "ios",
        appVersion: "1.0.0",
      },
    });

    expect(secondStart.token).toBe(firstStart.token);
    expect(secondStart.user.userId).toBe(firstStart.user.userId);
    const storage = await database.pool.query<{ plaintext_absent: boolean; hash_length: number }>(
      `select position(convert_to($1, 'UTF8') in token_ciphertext) = 0 as plaintext_absent,
              octet_length(token_hash)::int as hash_length
       from users where email_normalized = $2`,
      [firstStart.token, "reader@example.com"],
    );
    expect(storage.rows[0]).toEqual({ plaintext_absent: true, hash_length: 32 });
    const first = await authService.authenticate(
      `Bearer ${firstStart.token}`,
      firstStart.device.deviceId,
    );
    expect((await authService.listDevices(first)).items).toHaveLength(2);
  });

  it("performs deterministic, idempotent progress arbitration across shared-token devices", async () => {
    const first = await authService.authenticate(
      `Bearer ${firstStart.token}`,
      firstStart.device.deviceId,
    );
    const second = await authService.authenticate(
      `Bearer ${secondStart.token}`,
      secondStart.device.deviceId,
    );
    const bookHash = "d8f2b4873f0b71b6fdfca1f55f65e17100f15b06cb74cb90cdabf936c18c4f2a";

    const initial = await progressService.sync(first, {
      items: [{ bookHash, fileSize: 100, offset: 10, readAtMs: 1_000 }],
    });
    expect(initial.results[0]?.decision).toBe("accepted");
    expect(initial.results[0]?.state.version).toBe("1");
    expect((await progressService.list(second)).items).toHaveLength(1);

    const duplicate = await progressService.sync(first, {
      items: [{ bookHash, fileSize: 100, offset: 10, readAtMs: 1_000 }],
    });
    expect(duplicate.results[0]?.decision).toBe("unchanged");
    expect(duplicate.results[0]?.state.version).toBe("1");

    const tieWinner = await progressService.sync(second, {
      items: [{ bookHash, fileSize: 100, offset: 20, readAtMs: 1_000 }],
    });
    expect(tieWinner.results[0]?.decision).toBe("accepted");
    expect(tieWinner.results[0]?.state.device.deviceId).toBe(second.deviceId);
    expect(tieWinner.results[0]?.state.version).toBe("2");

    const older = await progressService.sync(first, {
      items: [{ bookHash, fileSize: 100, offset: 5, readAtMs: 999 }],
    });
    expect(older.results[0]?.decision).toBe("server_kept");
    expect((await progressService.list(first)).items).toHaveLength(1);
  });

  it("enforces device revocation, re-enables via start-sync, and isolates emails", async () => {
    const first = await authService.authenticate(
      `Bearer ${firstStart.token}`,
      firstStart.device.deviceId,
    );
    await authService.revokeDevice(first, secondStart.device.deviceId);
    await expect(
      authService.authenticate(`Bearer ${firstStart.token}`, secondStart.device.deviceId),
    ).rejects.toMatchObject({ statusCode: 403 });

    const reenabled = await authService.startSync({
      email: "reader@example.com",
      device: {
        deviceId: secondStart.device.deviceId,
        deviceName: "iPhone Restored",
        platform: "ios",
        appVersion: "1.0.1",
      },
    });
    expect(reenabled.token).toBe(firstStart.token);
    const reenabledAuth = await authService.authenticate(
      `Bearer ${reenabled.token}`,
      reenabled.device.deviceId,
    );
    expect(reenabledAuth).toMatchObject({ deviceName: "iPhone Restored" });
    expect((await progressService.list(reenabledAuth)).items).toHaveLength(1);

    const other = await authService.startSync({
      email: "other@example.com",
      device: {
        deviceId: "30000000-0000-0000-0000-000000000000",
        deviceName: "Other Phone",
        platform: "android",
        appVersion: "1.0.0",
      },
    });
    const otherAuth = await authService.authenticate(`Bearer ${other.token}`, other.device.deviceId);
    expect((await progressService.list(otherAuth)).items).toEqual([]);
    await expect(
      authService.authenticate(`Bearer ${other.token}`, firstStart.device.deviceId),
    ).rejects.toMatchObject({ statusCode: 403 });
  });

  it("converges concurrent first use of one email on one fixed token", async () => {
    const starts = await Promise.all([
      authService.startSync({
        email: "concurrent@example.com",
        device: {
          deviceId: "40000000-0000-0000-0000-000000000000",
          deviceName: "Concurrent One",
          platform: "android",
          appVersion: "1.0.0",
        },
      }),
      authService.startSync({
        email: "concurrent@example.com",
        device: {
          deviceId: "50000000-0000-0000-0000-000000000000",
          deviceName: "Concurrent Two",
          platform: "ios",
          appVersion: "1.0.0",
        },
      }),
    ]);
    expect(starts[0]?.token).toBe(starts[1]?.token);
    expect(starts[0]?.user.userId).toBe(starts[1]?.user.userId);
  });

  it("deletes progress and sync identity using only the fixed token authorization", async () => {
    const first = await authService.authenticate(
      `Bearer ${firstStart.token}`,
      firstStart.device.deviceId,
    );
    await progressService.deleteAll(first);
    expect((await progressService.list(first)).items).toEqual([]);
    await authService.deleteAccount(first);
    await expect(
      authService.authenticate(`Bearer ${firstStart.token}`, firstStart.device.deviceId),
    ).rejects.toMatchObject({ statusCode: 401 });
  });
});
