import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { afterEach, expect, it, vi } from "vitest";
import { decideCandidate, effectiveCandidate, type StoredCandidate } from "../src/arbitration.js";
import { progressSyncSchema, startSyncSchema, type ProgressItemInput, type ProgressSyncInput, type StartSyncInput } from "../src/schemas.js";
import { buildApp } from "../src/app.js";
import { AuthService } from "../src/auth-service.js";
import { ProgressService } from "../src/progress-service.js";
import { testConfig } from "./config-fixture.js";

function fixture<T>(name: string): T {
  return JSON.parse(readFileSync(new URL(`../../../contracts/fixtures/${name}.json`, import.meta.url), "utf8")) as T;
}
const wire = fixture<{
  ProgressSyncRequest: ProgressSyncInput;
  StartSyncRequest: StartSyncInput;
  ProgressSyncResponse: Awaited<ReturnType<ProgressService["sync"]>>;
}>("wire");
function validate(schema: string, value: unknown) {
  const result = spawnSync("ruby", [fileURLToPath(new URL("../../../scripts/contract-schema.rb", import.meta.url))], {
    input: JSON.stringify({ schema, value }), encoding: "utf8",
  });
  expect(result.status, result.stderr).toBe(0);
}
afterEach(() => vi.restoreAllMocks());

it("uses the shared time-priority behavior scenarios", () => {
  for (const c of fixture<{ id: string; bookHash: string; fileSize: number; localOffset: number;
    localReadAtMs: number; remoteOffset: number; remoteReadAtMs: number; currentDeviceId: string;
    remoteDeviceId: string; expectedDecision: string }[]>("progress-behavior")) {
    const incoming = effectiveCandidate({ bookHash: c.bookHash, fileSize: c.fileSize,
      offset: c.localOffset, readAtMs: c.localReadAtMs }, 10_000);
    expect(decideCandidate({ offset: c.remoteOffset, readAtMs: c.remoteReadAtMs,
      deviceId: c.remoteDeviceId }, incoming, c.currentDeviceId), c.id).toBe(c.expectedDecision);
  }
});
it("uses service-only tie-breaking and clock-adjustment scenarios", () => {
  const cases: { id: string; stored: StoredCandidate | null; incoming: ProgressItemInput;
    serverTimeMs: number; deviceId: string; decision: string; timeAdjusted: boolean }[] = fixture("arbitration");
  for (const c of cases) {
    const candidate = effectiveCandidate(c.incoming, c.serverTimeMs);
    expect(candidate.timeAdjusted, c.id).toBe(c.timeAdjusted);
    expect(decideCandidate(c.stored ?? undefined, candidate, c.deviceId), c.id).toBe(c.decision);
  }
});
it("validates golden requests with the actual backend schemas", () => {
  expect(progressSyncSchema.parse(wire.ProgressSyncRequest)).toEqual(wire.ProgressSyncRequest);
  expect(startSyncSchema.parse(wire.StartSyncRequest)).toEqual(wire.StartSyncRequest);
  for (const c of fixture<{ id: string; schema: string; value: unknown }[]>("invalid-wire")) {
    if (c.schema === "ProgressSyncRequest") expect(progressSyncSchema.safeParse(c.value).success, c.id).toBe(false);
  }
  const item = wire.ProgressSyncRequest.items[0];
  for (const items of [[{ ...item, fileSize: 100, offset: 101 }], [item, item], [{ ...item, fileSize: 9007199254740992 }]]) {
    expect(progressSyncSchema.safeParse({ items }).success).toBe(false);
  }
});
it("validates actual HTTP response envelopes against OpenAPI", async () => {
  const app = await buildApp(testConfig("postgresql://invalid:invalid@127.0.0.1:1/invalid"));
  try {
    const denied = await app.inject({ method: "GET", url: "/v1/progress" });
    expect(denied.statusCode).toBe(401);
    validate("ErrorEnvelope", denied.json());
    expect(denied.json<{ error: { code: string } }>().error.code).toBe("INVALID_SYNC_TOKEN");
    vi.spyOn(AuthService.prototype, "authenticate").mockResolvedValue({ userId: "1", userPublicId: "user",
      deviceDbId: "2", deviceId: wire.StartSyncRequest.device.deviceId,
      deviceName: "测试设备", platform: "android" });
    const sync = vi.spyOn(ProgressService.prototype, "sync").mockResolvedValue(wire.ProgressSyncResponse);
    const response = await app.inject({ method: "POST", url: "/v1/progress/sync", payload: wire.ProgressSyncRequest });
    expect(response.statusCode).toBe(200);
    expect(sync).toHaveBeenCalled();
    validate("ProgressSyncResponse", response.json());
    expect(response.json()).toEqual(wire.ProgressSyncResponse);
  } finally { await app.close(); }
});
