import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import { testConfig } from "./config-fixture.js";

import { AuthService } from "../src/auth-service.js";
import { ProgressService } from "../src/progress-service.js";

let app: FastifyInstance;

describe("HTTP contract without a database connection", () => {
  beforeAll(async () => {
    app = await buildApp(testConfig("postgresql://invalid:invalid@127.0.0.1:1/invalid"));
  });

  afterAll(async () => app.close());
  afterEach(() => vi.restoreAllMocks());

  it("returns the unified error shape and request headers for malformed JSON", async () => {
    const response = await app.inject({
      method: "POST",
      url: "/v1/auth/start-sync",
      headers: { "content-type": "application/json" },
      payload: "{broken",
    });
    expect(response.statusCode).toBe(400);
    expect(response.headers["cache-control"]).toBe("no-store");
    expect(response.headers["x-request-id"]).toBeTruthy();
    expect(response.json()).toEqual({
      error: {
        code: "INVALID_JSON",
        message: "request body must be valid JSON",
        retryable: false,
        requestId: response.headers["x-request-id"],
      },
    });
  });

  it("rejects invalid start-sync fields before database access", async () => {
    const response = await app.inject({
      method: "POST",
      url: "/v1/auth/start-sync",
      payload: { email: "not-an-email", device: {} },
    });
    expect(response.statusCode).toBe(422);
    expect(response.json()).toMatchObject({ error: { code: "VALIDATION_FAILED" } });
  });

  it("rejects a missing or malformed sync token", async () => {
    const response = await app.inject({ method: "GET", url: "/v1/progress" });
    expect(response.statusCode).toBe(401);
    expect(response.json()).toMatchObject({ error: { code: "INVALID_SYNC_TOKEN" } });
  });

  it("requires X-Device-Id after a structurally valid token", async () => {
    const response = await app.inject({
      method: "GET",
      url: "/v1/progress",
      headers: { authorization: `Bearer ${"A".repeat(43)}` },
    });
    expect(response.statusCode).toBe(400);
    expect(response.json()).toMatchObject({ error: { code: "DEVICE_ID_REQUIRED" } });
  });

  it("authenticates before inspecting an oversized batch", async () => {
    const response = await app.inject({
      method: "POST",
      url: "/v1/progress/sync",
      headers: {
        authorization: "Bearer invalid",
        "x-device-id": "7ce2a4d2-3fdb-4dce-93e7-8bc67e3f9677",
      },
      payload: { items: Array.from({ length: 101 }, () => ({})) },
    });
    expect(response.statusCode).toBe(401);
  });

  it("returns a non-revealing 404", async () => {
    const response = await app.inject({ method: "GET", url: "/v1/unknown" });
    expect(response.statusCode).toBe(404);
    expect(response.json()).toMatchObject({ error: { code: "NOT_FOUND" } });
  });

  it.each(["register", "login", "refresh", "logout"])(
    "does not expose the obsolete auth/%s endpoint",
    async (endpoint) => {
      const response = await app.inject({ method: "POST", url: `/v1/auth/${endpoint}` });
      expect(response.statusCode).toBe(404);
    },
  );
  it.each(["/v1/account", "/v1/progress", "/v1/progress/" + "a".repeat(64)])(
    "does not expose unscoped deletion at %s", async (url) => {
      const response = await app.inject({ method: "DELETE", url });
      expect(response.statusCode).toBe(404);
    },
  );

  it("requires authentication for single-book deletion", async () => {
    const response = await app.inject({
      method: "DELETE", url: `/v1/progress/${"a".repeat(64)}/100`,
    });
    expect(response.statusCode).toBe(401);
  });

  it("passes only the authenticated identity and exact book key to deletion", async () => {
    const auth = { userId: "1", userPublicId: "user", email: "reader@example.com",
      deviceDbId: "2", deviceId: "device", deviceName: "Reader", platform: "ios" as const };
    vi.spyOn(AuthService.prototype, "authenticate").mockResolvedValue(auth);
    const deletion = vi.spyOn(ProgressService.prototype, "deleteBook").mockResolvedValue();
    const bookHash = "a".repeat(64);
    const response = await app.inject({
      method: "DELETE", url: `/v1/progress/${bookHash}/100`,
    });
    expect(response.statusCode).toBe(204);
    expect(response.body).toBe("");
    expect(deletion).toHaveBeenCalledExactlyOnceWith(auth, { bookHash, fileSize: 100 });
  });

  it.each(["0", "-1", "1.5", "1e3", "01", "9007199254740992"])(
    "rejects an invalid file size %s without deleting", async (fileSize) => {
      vi.spyOn(AuthService.prototype, "authenticate").mockResolvedValue({} as never);
      const deletion = vi.spyOn(ProgressService.prototype, "deleteBook").mockResolvedValue();
      const response = await app.inject({
        method: "DELETE", url: `/v1/progress/${"a".repeat(64)}/${fileSize}`,
      });
      expect(response.statusCode).toBe(422);
      expect(deletion).not.toHaveBeenCalled();
    },
  );

});
