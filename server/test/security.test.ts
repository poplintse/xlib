import { describe, expect, it } from "vitest";
import {
  createSyncToken,
  decryptToken,
  encryptToken,
  hashToken,
  tokenMatchesHash,
} from "../src/security.js";

describe("fixed sync token security", () => {
  it("generates a 256-bit opaque token", () => {
    const token = createSyncToken();
    expect(token).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(Buffer.from(token, "base64url")).toHaveLength(32);
    expect(createSyncToken()).not.toBe(token);
  });

  it("stores and compares a deterministic SHA-256 digest", () => {
    const token = createSyncToken();
    const digest = hashToken(token);
    expect(digest).toHaveLength(32);
    expect(tokenMatchesHash(token, digest)).toBe(true);
    expect(tokenMatchesHash(createSyncToken(), digest)).toBe(false);
  });

  it("round-trips through authenticated AES-256-GCM encryption", () => {
    const key = Buffer.alloc(32, 7);
    const token = createSyncToken();
    const encrypted = encryptToken(token, key, "reader@example.com");
    expect(encrypted.includes(Buffer.from(token, "utf8"))).toBe(false);
    expect(decryptToken(encrypted, key, "reader@example.com")).toBe(token);

    const tampered = Buffer.from(encrypted);
    const lastIndex = tampered.length - 1;
    tampered[lastIndex] = (tampered[lastIndex] ?? 0) ^ 1;
    expect(() => decryptToken(tampered, key, "reader@example.com")).toThrow();
    expect(() => decryptToken(encrypted, Buffer.alloc(32, 8), "reader@example.com")).toThrow();
    expect(() => decryptToken(encrypted, key, "other@example.com")).toThrow();
  });
});
