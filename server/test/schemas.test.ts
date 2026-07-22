import { describe, expect, it } from "vitest";
import { MAX_TIMESTAMP_MS, progressSyncSchema, startSyncSchema } from "../src/schemas.js";

function item(overrides: Record<string, unknown> = {}) {
  return {
    bookHash: "d8f2b4873f0b71b6fdfca1f55f65e17100f15b06cb74cb90cdabf936c18c4f2a",
    fileSize: 100,
    offset: 50,
    readAtMs: 1_000,
    ...overrides,
  };
}

describe("request validation", () => {
  it("normalizes email and device fields", () => {
    const parsed = startSyncSchema.parse({
      email: " Reader@Example.COM ",
      device: {
        deviceId: "7CE2A4D2-3FDB-4DCE-93E7-8BC67E3F9677",
        deviceName: "Phone",
        platform: "android",
        appVersion: "1.0.0",
      },
    });
    expect(parsed.email).toBe("reader@example.com");
    expect(parsed.device.deviceId).toBe("7ce2a4d2-3fdb-4dce-93e7-8bc67e3f9677");
  });

  it("rejects local progress in start-sync", () => {
    expect(
      startSyncSchema.safeParse({
        email: "reader@example.com",
        device: {
          deviceId: "7ce2a4d2-3fdb-4dce-93e7-8bc67e3f9677",
          deviceName: "Phone",
          platform: "android",
          appVersion: "1.0.0",
        },
        items: [item()],
      }).success,
    ).toBe(false);
  });

  it.each(["not-an-email", "reader @example.com", "", "a".repeat(250) + "@x.com"])(
    "rejects invalid email %s",
    (email) => {
      expect(
        startSyncSchema.safeParse({
          email,
          device: {
            deviceId: "7ce2a4d2-3fdb-4dce-93e7-8bc67e3f9677",
            deviceName: "Phone",
            platform: "android",
            appVersion: "1.0.0",
          },
        }).success,
      ).toBe(false);
    },
  );

  it.each([
    item({ bookHash: "A".repeat(64) }),
    item({ bookHash: "a".repeat(63) }),
    item({ fileSize: 0 }),
    item({ offset: -1 }),
    item({ offset: 101 }),
    item({ readAtMs: -1 }),
    item({ readAtMs: MAX_TIMESTAMP_MS + 1 }),
    item({ fileSize: Number.MAX_SAFE_INTEGER + 1 }),
  ])("rejects an invalid progress item", (invalid) => {
    expect(progressSyncSchema.safeParse({ items: [invalid] }).success).toBe(false);
  });

  it("rejects duplicate book keys atomically", () => {
    expect(progressSyncSchema.safeParse({ items: [item(), item({ offset: 75 })] }).success).toBe(
      false,
    );
  });

  it("accepts exactly 100 distinct items and rejects 101", () => {
    const items = Array.from({ length: 101 }, (_, index) =>
      item({ bookHash: index.toString(16).padStart(64, "0") }),
    );
    expect(progressSyncSchema.safeParse({ items: items.slice(0, 100) }).success).toBe(true);
    expect(progressSyncSchema.safeParse({ items }).success).toBe(false);
  });
});
