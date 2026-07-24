import { describe, expect, it } from "vitest";
import { decideCandidate, effectiveCandidate, progressRatio } from "../src/arbitration.js";

const base = {
  bookHash: "a".repeat(64),
  fileSize: 100,
  offset: 20,
  readAtMs: 1_000,
};

describe("progress arbitration", () => {
  it("accepts the first and later candidate", () => {
    const incoming = effectiveCandidate(base, 2_000);
    expect(decideCandidate(undefined, incoming, "10000000-0000-0000-0000-000000000000")).toBe(
      "accepted",
    );
    expect(
      decideCandidate(
        {
          offset: 10,
          readAtMs: 999,
          deviceId: "10000000-0000-0000-0000-000000000000",
        },
        incoming,
        "10000000-0000-0000-0000-000000000000",
      ),
    ).toBe("accepted");
  });

  it("keeps a later server state", () => {
    const incoming = effectiveCandidate(base, 2_000);
    expect(
      decideCandidate(
        {
          offset: 30,
          readAtMs: 1_001,
          deviceId: "10000000-0000-0000-0000-000000000000",
        },
        incoming,
        "10000000-0000-0000-0000-000000000000",
      ),
    ).toBe("server_kept");
  });

  it("recognizes an idempotent duplicate", () => {
    const incoming = effectiveCandidate(base, 2_000);
    expect(
      decideCandidate(
        {
          offset: 20,
          readAtMs: 1_000,
          deviceId: "10000000-0000-0000-0000-000000000000",
        },
        incoming,
        "10000000-0000-0000-0000-000000000000",
      ),
    ).toBe("unchanged");
  });

  it("uses stable public device UUID ordering for equal timestamps", () => {
    const incoming = effectiveCandidate(base, 2_000);
    expect(
      decideCandidate(
        {
          offset: 30,
          readAtMs: 1_000,
          deviceId: "10000000-0000-0000-0000-000000000000",
        },
        incoming,
        "20000000-0000-0000-0000-000000000000",
      ),
    ).toBe("accepted");
    expect(
      decideCandidate(
        {
          offset: 30,
          readAtMs: 1_000,
          deviceId: "30000000-0000-0000-0000-000000000000",
        },
        incoming,
        "20000000-0000-0000-0000-000000000000",
      ),
    ).toBe("server_kept");
  });

  it("clamps timestamps more than five minutes in the future", () => {
    const serverTimeMs = 10_000;
    expect(effectiveCandidate({ ...base, readAtMs: serverTimeMs + 300_001 }, serverTimeMs)).toEqual(
      expect.objectContaining({ effectiveReadAtMs: serverTimeMs, timeAdjusted: true }),
    );
    expect(effectiveCandidate({ ...base, readAtMs: serverTimeMs + 300_000 }, serverTimeMs)).toEqual(
      expect.objectContaining({ effectiveReadAtMs: serverTimeMs + 300_000, timeAdjusted: false }),
    );
  });

  it("calculates progress without persisted rounding", () => {
    expect(progressRatio(1, 3)).toBe(1 / 3);
    expect(progressRatio(100, 100)).toBe(1);
  });
});
