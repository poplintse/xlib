import type { AppConfig } from "../src/config.js";

export function testConfig(databaseUrl = "postgresql://unused"): AppConfig {
  return {
    nodeEnv: "test",
    host: "127.0.0.1",
    port: 8080,
    databaseUrl,
    databaseSsl: false,
    dbPoolMax: 4,
    tokenEncryptionKey: Buffer.alloc(32, 7),
    enforceHttps: false,
    trustProxy: false,
    logLevel: "silent",
  };
}
