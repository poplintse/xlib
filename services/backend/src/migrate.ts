import { readFile, readdir } from "node:fs/promises";
import { resolve } from "node:path";
import pg from "pg";

const { Client } = pg;

function quoteIdentifier(identifier: string): string {
  if (!/^[a-z_][a-z0-9_]*$/.test(identifier)) {
    throw new Error("DB_APP_ROLE must be a lowercase PostgreSQL identifier");
  }
  return `"${identifier}"`;
}

async function main(): Promise<void> {
  const connectionString = process.env.DATABASE_URL?.trim();
  if (!connectionString) throw new Error("DATABASE_URL is required");
  const migrationsDirectory = resolve(process.cwd(), "migrations");
  const client = new Client({ connectionString });
  await client.connect();

  try {
    await client.query(`
      create table if not exists schema_migrations (
        name text primary key,
        applied_at timestamptz not null default now()
      )
    `);
    const migrationNames = (await readdir(migrationsDirectory))
      .filter((name) => /^\d+_[a-z0-9_]+\.sql$/.test(name))
      .sort();

    for (const name of migrationNames) {
      const existing = await client.query<{ exists: boolean }>(
        "select exists(select 1 from schema_migrations where name = $1) as exists",
        [name],
      );
      if (existing.rows[0]?.exists) continue;
      const sql = await readFile(resolve(migrationsDirectory, name), "utf8");
      await client.query("begin");
      try {
        await client.query(sql);
        await client.query("insert into schema_migrations (name) values ($1)", [name]);
        await client.query("commit");
        process.stdout.write(`applied migration ${name}\n`);
      } catch (error) {
        await client.query("rollback");
        throw error;
      }
    }

    const appRole = process.env.DB_APP_ROLE?.trim();
    if (appRole) {
      const role = quoteIdentifier(appRole);
      await client.query(`revoke all on schema public from public`);
      await client.query(`revoke all on all tables in schema public from public`);
      await client.query(`revoke all on all sequences in schema public from public`);
      await client.query(`grant usage on schema public to ${role}`);
      await client.query(`
        grant select, insert, update, delete on
          users, devices, reading_progress
        to ${role}
      `);
      await client.query(`grant usage, select on all sequences in schema public to ${role}`);
      await client.query(
        `alter default privileges in schema public grant select, insert, update, delete on tables to ${role}`,
      );
      await client.query(
        `alter default privileges in schema public grant usage, select on sequences to ${role}`,
      );
    }
  } finally {
    await client.end();
  }
}

main().catch((error: unknown) => {
  process.stderr.write(`migration failed: ${error instanceof Error ? error.message : "unknown error"}\n`);
  process.exitCode = 1;
});
