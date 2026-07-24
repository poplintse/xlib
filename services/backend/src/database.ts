import pg, { type PoolClient } from "pg";
import type { AppConfig } from "./config.js";

const { Pool } = pg;

export class Database {
  readonly pool: pg.Pool;

  constructor(config: AppConfig) {
    this.pool = new Pool({
      connectionString: config.databaseUrl,
      max: config.dbPoolMax,
      ssl: config.databaseSsl ? { rejectUnauthorized: true } : false,
      idleTimeoutMillis: 30_000,
      connectionTimeoutMillis: 5_000,
      application_name: "xlib-sync-api",
    });
  }

  async transaction<T>(work: (client: PoolClient) => Promise<T>): Promise<T> {
    const client = await this.pool.connect();
    try {
      await client.query("begin");
      const result = await work(client);
      await client.query("commit");
      return result;
    } catch (error) {
      await client.query("rollback");
      throw error;
    } finally {
      client.release();
    }
  }

  async close(): Promise<void> {
    await this.pool.end();
  }
}
