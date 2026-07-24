export interface AppConfig {
  nodeEnv: "development" | "test" | "production";
  host: string;
  port: number;
  databaseUrl: string;
  databaseSsl: boolean;
  dbPoolMax: number;
  tokenEncryptionKey: Buffer;
  enforceHttps: boolean;
  trustProxy: boolean;
  logLevel: string;
  metricsToken?: string;
}

function envString(name: string, fallback?: string): string {
  const value = process.env[name]?.trim();
  if (value) return value;
  if (fallback !== undefined) return fallback;
  throw new Error(`${name} is required`);
}

function envInteger(name: string, fallback: number, minimum: number): number {
  const raw = process.env[name];
  const value = raw === undefined ? fallback : Number(raw);
  if (!Number.isSafeInteger(value) || value < minimum) {
    throw new Error(`${name} must be an integer greater than or equal to ${minimum}`);
  }
  return value;
}

function envBoolean(name: string, fallback: boolean): boolean {
  const raw = process.env[name]?.trim().toLowerCase();
  if (raw === undefined || raw === "") return fallback;
  if (raw === "true") return true;
  if (raw === "false") return false;
  throw new Error(`${name} must be true or false`);
}

export function loadConfig(): AppConfig {
  const rawEnv = envString("NODE_ENV", "development");
  if (rawEnv !== "development" && rawEnv !== "test" && rawEnv !== "production") {
    throw new Error("NODE_ENV must be development, test, or production");
  }

  const metricsToken = process.env.METRICS_TOKEN?.trim();
  if (rawEnv === "production" && metricsToken !== undefined && metricsToken.length < 32) {
    throw new Error("METRICS_TOKEN must contain at least 32 characters in production");
  }

  const rawEncryptionKey = envString("TOKEN_ENCRYPTION_KEY");
  if (!/^[A-Za-z0-9+/]{43}=$/.test(rawEncryptionKey)) {
    throw new Error("TOKEN_ENCRYPTION_KEY must be a base64-encoded 32-byte key");
  }
  const tokenEncryptionKey = Buffer.from(rawEncryptionKey, "base64");
  if (tokenEncryptionKey.length !== 32) {
    throw new Error("TOKEN_ENCRYPTION_KEY must decode to exactly 32 bytes");
  }

  return {
    nodeEnv: rawEnv,
    host: envString("HOST", "127.0.0.1"),
    port: envInteger("PORT", 8080, 1),
    databaseUrl: envString("DATABASE_URL"),
    databaseSsl: envBoolean("DATABASE_SSL", false),
    dbPoolMax: envInteger("DB_POOL_MAX", 10, 1),
    tokenEncryptionKey,
    enforceHttps: envBoolean("ENFORCE_HTTPS", rawEnv === "production"),
    trustProxy: envBoolean("TRUST_PROXY", rawEnv === "production"),
    logLevel: envString("LOG_LEVEL", "info"),
    ...(metricsToken ? { metricsToken } : {}),
  };
}
