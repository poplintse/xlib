import {
  createCipheriv,
  createDecipheriv,
  createHash,
  randomBytes,
  randomUUID,
  timingSafeEqual,
} from "node:crypto";

const TOKEN_BYTES = 32;
const NONCE_BYTES = 12;
const TAG_BYTES = 16;
const CIPHERTEXT_VERSION = 1;

export function hashToken(token: string): Buffer {
  return createHash("sha256").update(token, "utf8").digest();
}

export function createSyncToken(): string {
  return randomBytes(TOKEN_BYTES).toString("base64url");
}

export function newPublicId(): string {
  return randomUUID();
}

export function encryptToken(token: string, key: Buffer, context: string): Buffer {
  if (key.length !== 32) throw new Error("token encryption key must be 32 bytes");
  const nonce = randomBytes(NONCE_BYTES);
  const cipher = createCipheriv("aes-256-gcm", key, nonce);
  cipher.setAAD(Buffer.from(context, "utf8"));
  const ciphertext = Buffer.concat([cipher.update(token, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return Buffer.concat([Buffer.from([CIPHERTEXT_VERSION]), nonce, tag, ciphertext]);
}

export function decryptToken(encrypted: Buffer, key: Buffer, context: string): string {
  if (key.length !== 32) throw new Error("token encryption key must be 32 bytes");
  if (encrypted.length <= 1 + NONCE_BYTES + TAG_BYTES || encrypted[0] !== CIPHERTEXT_VERSION) {
    throw new Error("unsupported token ciphertext");
  }
  const nonce = encrypted.subarray(1, 1 + NONCE_BYTES);
  const tag = encrypted.subarray(1 + NONCE_BYTES, 1 + NONCE_BYTES + TAG_BYTES);
  const ciphertext = encrypted.subarray(1 + NONCE_BYTES + TAG_BYTES);
  const decipher = createDecipheriv("aes-256-gcm", key, nonce);
  decipher.setAAD(Buffer.from(context, "utf8"));
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(ciphertext), decipher.final()]).toString("utf8");
}

export function constantTimeTokenEquals(left: string, right: string): boolean {
  const leftHash = hashToken(left);
  const rightHash = hashToken(right);
  return timingSafeEqual(leftHash, rightHash);
}

export function tokenMatchesHash(token: string, expectedHash: Buffer): boolean {
  const actualHash = hashToken(token);
  return expectedHash.length === actualHash.length && timingSafeEqual(actualHash, expectedHash);
}
