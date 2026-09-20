import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import pg from "pg";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { AuthService, type StartSyncResult } from "../../src/auth-service.js";
import { IdentityRepository } from "../../src/identity-repository.js";
import { ProgressRepository } from "../../src/progress-repository.js";
import { effectiveCandidate } from "../../src/arbitration.js";
import { Database } from "../../src/database.js";
import { ProgressService } from "../../src/progress-service.js";
import { testConfig } from "../config-fixture.js";

const { Client } = pg;
const adminUrl = process.env.TEST_DATABASE_URL;
const appUrl = process.env.TEST_DATABASE_APP_URL;
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

async function newIdentity(label: string) {
  const registration = await authService.startSync({
    email: `${label}@example.com`,
    device: { deviceId: randomUUID(), deviceName: "Integration", platform: "ios", appVersion: "test" },
  });
  return authService.authenticate(`Bearer ${registration.token}`, registration.device.deviceId);
}

async function waitForBlockedTransaction(blocker: pg.PoolClient): Promise<void> {
  const result = await blocker.query<{ pid: number }>("select pg_backend_pid() as pid");
  const pid = result.rows[0]!.pid;
  const deadline = Date.now() + 4000;
  while (Date.now() < deadline) {
    const blocked = await admin.query<{ blocked: boolean }>(
      `select exists(select 1 from pg_stat_activity
         where datname = current_database() and $1 = any(pg_blocking_pids(pid))) as blocked`, [pid]);
    if (blocked.rows[0]?.blocked) return;
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  throw new Error("Expected transaction did not wait on the held identity lock");
}

const integration = adminUrl ? describe : describe.skip;

integration("PostgreSQL service integration", () => {
  beforeAll(async () => {
    if (!adminUrl || !appUrl) throw new Error("Dedicated owner and application test database URLs are required");
    for (const url of [adminUrl, appUrl]) {
      if (!/(?:^|[_-])test(?:[_-]|$)/i.test(new URL(url).pathname.slice(1))) {
        throw new Error("Integration database name must contain a separate test component");
      }
    }
    admin = new Client({ connectionString: adminUrl });
    await admin.connect();
    await admin.query(`create schema ${schema}`);
    await admin.query(`set search_path to ${schema}`);
    for (const name of ["001_initial.sql", "002_require_fixed_sync_schema.sql"]) {
      const migration = await readFile(resolve(process.cwd(), `migrations/${name}`), "utf8");
      await admin.query(migration);
    }
    const application = new Client({ connectionString: appUrl });
    await application.connect();
    let appRole: string;
    try {
      const role = await application.query<{ name: string; privileged: boolean }>(
        `select current_user as name, (rolsuper or rolcreatedb or rolcreaterole or rolbypassrls) as privileged
         from pg_roles where rolname = current_user`);
      expect(role.rows[0]?.privileged).toBe(false);
      appRole = role.rows[0]!.name;
    } finally { await application.end(); }
    const quotedRole = '"' + appRole.replaceAll('"', '""') + '"';
    await admin.query(`grant usage on schema ${schema} to ${quotedRole}`);
    await admin.query(`grant select, insert, update, delete on all tables in schema ${schema} to ${quotedRole}`);
    await admin.query(`grant usage, select on all sequences in schema ${schema} to ${quotedRole}`);
    const config = testConfig(withSearchPath(appUrl));
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
        deviceId: "10000000-0000-4000-8000-000000000000",
        deviceName: "Android One",
        platform: "android",
        appVersion: "1.0.0",
      },
    });
    secondStart = await authService.startSync({
      email: "reader@example.com",
      device: {
        deviceId: "20000000-0000-4000-8000-000000000000",
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
        deviceId: "30000000-0000-4000-8000-000000000000",
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
          deviceId: "40000000-0000-4000-8000-000000000000",
          deviceName: "Concurrent One",
          platform: "android",
          appVersion: "1.0.0",
        },
      }),
      authService.startSync({
        email: "concurrent@example.com",
        device: {
          deviceId: "50000000-0000-4000-8000-000000000000",
          deviceName: "Concurrent Two",
          platform: "ios",
          appVersion: "1.0.0",
        },
      }),
    ]);
    expect(starts[0]?.token).toBe(starts[1]?.token);
    expect(starts[0]?.user.userId).toBe(starts[1]?.user.userId);
  });

  it("deletes only the selected book and keeps other books, identities and devices", async () => {
    const first = await authService.authenticate(`Bearer ${firstStart.token}`, firstStart.device.deviceId);
    const otherStart = await authService.startSync({
      email: "delete-isolation@example.com",
      device: { deviceId: "60000000-0000-4000-8000-000000000000", deviceName: "Other",
        platform: "ios", appVersion: "1.0.0" },
    });
    const other = await authService.authenticate(`Bearer ${otherStart.token}`, otherStart.device.deviceId);
    const book = { bookHash: "a".repeat(64), fileSize: 100 };
    const sibling = { bookHash: "a".repeat(64), fileSize: 101 };
    const differentHash = { bookHash: "b".repeat(64), fileSize: 100 };
    await progressService.sync(first, { items: [book, sibling, differentHash].map(key =>
      ({ ...key, offset: 10, readAtMs: 1000 })) });
    await progressService.sync(other, { items: [{ ...book, offset: 20, readAtMs: 1000 }] });
    const devicesBefore = (await authService.listDevices(first)).items.length;
    await progressService.deleteBook(first, book);
    await progressService.deleteBook(first, book);
    const remaining = (await progressService.list(first)).items;
    expect(remaining.some(item => item.bookHash === book.bookHash && item.fileSize === 100)).toBe(false);
    expect(remaining).toEqual(expect.arrayContaining([
      expect.objectContaining(sibling), expect.objectContaining(differentHash),
    ]));
    expect((await progressService.list(other)).items).toEqual([expect.objectContaining(book)]);
    expect((await authService.listDevices(first)).items).toHaveLength(devicesBefore);
    await expect(authService.authenticate(`Bearer ${firstStart.token}`, firstStart.device.deviceId)).resolves.toMatchObject({ userId: first.userId });
    await authService.revokeDevice(other, other.deviceId);
    await expect(progressService.deleteBook(other, book)).rejects.toMatchObject({ statusCode: 403 });
  });
  it("runs service work as a restricted role without schema creation rights", async () => {
    await expect(database.pool.query(`create table ${schema}.forbidden_ddl (id int)`))
      .rejects.toMatchObject({ code: "42501" });
  });

  it("converges concurrent uploads by reading time and UUID while retaining request order", async () => {
    const first = await newIdentity("parallel-progress");
    const second = await newIdentity("parallel-progress");
    const a = { bookHash: "c".repeat(64), fileSize: 100 };
    const b = { bookHash: "d".repeat(64), fileSize: 100 };
    const outcomes = await Promise.all([
      progressService.sync(first, { items: [{ ...b, offset: 90, readAtMs: 1000 }, { ...a, offset: 90, readAtMs: 1000 }] }),
      progressService.sync(second, { items: [{ ...a, offset: 10, readAtMs: 2000 }, { ...b, offset: 10, readAtMs: 2000 }] }),
    ]);
    expect(outcomes[0].results.map(item => item.state.bookHash)).toEqual([b.bookHash, a.bookHash]);
    expect((await progressService.list(first)).items.every(item => item.offset === 10 && item.readAtMs === 2000)).toBe(true);
    await Promise.all([first, second].map(auth => progressService.sync(auth, {
      items: [{ ...a, offset: auth === first ? 30 : 40, readAtMs: 3000 }],
    })));
    const winner = first.deviceId > second.deviceId ? first : second;
    const final = (await progressService.list(first)).items.find(item => item.bookHash === a.bookHash)!;
    expect(final.device.deviceId).toBe(winner.deviceId);
    const repeated = await progressService.sync(winner, { items: [{ ...a, offset: final.offset, readAtMs: final.readAtMs }] });
    expect(repeated.results[0]?.decision).toBe("unchanged");
    expect(repeated.results[0]?.state.version).toBe(final.version);
  });

  it("rolls back all earlier book writes if a later item violates database constraints", async () => {
    const auth = await newIdentity("rollback");
    await expect(progressService.sync(auth, { items: [
      { bookHash: "a".repeat(64), fileSize: 100, offset: 10, readAtMs: 1000 },
      { bookHash: "b".repeat(64), fileSize: 100, offset: 101, readAtMs: 1000 },
    ] })).rejects.toMatchObject({ code: "23514" });
    expect((await progressService.list(auth)).items).toEqual([]);
  });

  it("serializes concurrent capacity checks and permits updates at the limit", async () => {
    const auth = await newIdentity("capacity");
    await database.pool.query(
      `insert into reading_progress (user_id, book_hash, file_size, offset_bytes, read_at, device_id, device_uid_order)
       select $1, decode(lpad(to_hex(n), 64, '0'), 'hex'), 100, 1, to_timestamp(1), $2, $3
       from generate_series(1, 9999) n`, [auth.userId, auth.deviceDbId, auth.deviceId]);
    const results = await Promise.allSettled(["e", "f"].map(hash => progressService.sync(auth, {
      items: [{ bookHash: hash.repeat(64), fileSize: 100, offset: 2, readAtMs: 2000 }],
    })));
    expect(results.filter(result => result.status === "fulfilled")).toHaveLength(1);
    expect(results.find(result => result.status === "rejected")).toMatchObject({
      reason: { statusCode: 422, code: "PROGRESS_LIMIT_REACHED" },
    });
    expect((await progressService.list(auth)).items).toHaveLength(10000);
    const updated = await progressService.sync(auth, { items: [
      { bookHash: "1".padStart(64, "0"), fileSize: 100, offset: 3, readAtMs: 3000 },
    ] });
    expect(updated.results[0]?.decision).toBe("accepted");
  });

  it("waits for an in-flight upload transaction before deleting exactly that book", async () => {
    const auth = await newIdentity("upload-delete");
    const book = { bookHash: "a".repeat(64), fileSize: 100 };
    const client = await database.pool.connect();
    let deletion: Promise<void> | undefined;
    try {
      await client.query("begin");
      await new IdentityRepository(client).lockActiveRequester(auth);
      await new ProgressRepository(client).upsert(auth, effectiveCandidate({ ...book, offset: 25, readAtMs: 1000 }, 2000));
      deletion = progressService.deleteBook(auth, book);
      await waitForBlockedTransaction(client);
      await client.query("commit");
      await deletion;
      expect((await progressService.list(auth)).items).toEqual([]);
    } finally {
      await client.query("rollback");
      client.release();
      await deletion;
    }
  });

  it.each(["upload", "delete", "revoke"] as const)(
    "rechecks a revoked device after a queued %s obtains the identity lock", async operation => {
      const auth = await newIdentity(`revocation-${operation}`);
      const book = { bookHash: "b".repeat(64), fileSize: 100 };
      await progressService.sync(auth, { items: [{ ...book, offset: 10, readAtMs: 1000 }] });
      const client = await database.pool.connect();
      let pending: Promise<unknown> | undefined;
      try {
        await client.query("begin");
        const repository = new IdentityRepository(client);
        await repository.lockActiveRequester(auth);
        await repository.revokeDevice(auth.userId, auth.deviceId);
        const request = operation === "upload"
          ? progressService.sync(auth, { items: [{ ...book, offset: 20, readAtMs: 2000 }] })
          : operation === "delete" ? progressService.deleteBook(auth, book)
          : authService.revokeDevice(auth, auth.deviceId);
        pending = request.then(() => ({ succeeded: true }), (error: unknown) => error);
        await waitForBlockedTransaction(client);
        await client.query("commit");
        expect(await pending).toMatchObject({ statusCode: 403, code: "DEVICE_FORBIDDEN" });
        const retained = await new ProgressRepository(database.pool).select(auth.userId, book);
        expect(Number(retained.offset_bytes)).toBe(10);
      } finally {
        await client.query("rollback");
        client.release();
        await pending;
      }
    },
  );

});
