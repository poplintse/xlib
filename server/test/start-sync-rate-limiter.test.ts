import { describe, expect, it } from "vitest";
import { ApiError } from "../src/errors.js";
import { StartSyncRateLimiter } from "../src/start-sync-rate-limiter.js";

describe("start-sync dual rate limiter", () => {
  it("limits repeated requests for the same normalized email across IPs", () => {
    const limiter = new StartSyncRateLimiter(10, 2, 60_000);
    limiter.consume("192.0.2.1", "email-hash", 1_000);
    limiter.consume("192.0.2.2", "email-hash", 1_001);
    expect(() => limiter.consume("192.0.2.3", "email-hash", 1_002)).toThrowError(ApiError);
  });

  it("limits one IP spraying different emails", () => {
    const limiter = new StartSyncRateLimiter(2, 10, 60_000);
    limiter.consume("192.0.2.1", "email-a", 1_000);
    limiter.consume("192.0.2.1", "email-b", 1_001);
    expect(() => limiter.consume("192.0.2.1", "email-c", 1_002)).toThrowError(ApiError);
  });

  it("resets both dimensions after the window", () => {
    const limiter = new StartSyncRateLimiter(1, 1, 1_000);
    limiter.consume("192.0.2.1", "email-hash", 1_000);
    expect(() => limiter.consume("192.0.2.1", "email-hash", 1_999)).toThrow();
    expect(() => limiter.consume("192.0.2.1", "email-hash", 2_000)).not.toThrow();
  });
});
