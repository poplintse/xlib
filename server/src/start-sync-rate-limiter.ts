import { ApiError } from "./errors.js";

interface Bucket {
  count: number;
  resetAtMs: number;
}

export class StartSyncRateLimiter {
  private readonly ipBuckets = new Map<string, Bucket>();
  private readonly emailBuckets = new Map<string, Bucket>();
  private requestsSinceCleanup = 0;

  constructor(
    private readonly ipLimit = 30,
    private readonly emailLimit = 5,
    private readonly windowMs = 60_000,
  ) {}

  consume(ip: string, normalizedEmailHash: string, nowMs = Date.now()): void {
    this.consumeBucket(this.ipBuckets, ip, this.ipLimit, nowMs);
    this.consumeBucket(this.emailBuckets, normalizedEmailHash, this.emailLimit, nowMs);
    this.requestsSinceCleanup += 1;
    if (this.requestsSinceCleanup >= 256) {
      this.pruneExpired(nowMs);
      this.requestsSinceCleanup = 0;
    }
  }

  private consumeBucket(
    buckets: Map<string, Bucket>,
    key: string,
    limit: number,
    nowMs: number,
  ): void {
    const current = buckets.get(key);
    if (!current || current.resetAtMs <= nowMs) {
      buckets.set(key, { count: 1, resetAtMs: nowMs + this.windowMs });
      return;
    }
    if (current.count >= limit) {
      const retryAfterSeconds = Math.max(1, Math.ceil((current.resetAtMs - nowMs) / 1000));
      throw new ApiError(429, "RATE_LIMITED", "too many requests", true, retryAfterSeconds);
    }
    current.count += 1;
  }

  private pruneExpired(nowMs: number): void {
    for (const [key, bucket] of this.ipBuckets) {
      if (bucket.resetAtMs <= nowMs) this.ipBuckets.delete(key);
    }
    for (const [key, bucket] of this.emailBuckets) {
      if (bucket.resetAtMs <= nowMs) this.emailBuckets.delete(key);
    }
  }
}
