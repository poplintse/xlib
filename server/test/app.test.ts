import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import { testConfig } from "./config-fixture.js";

let app: FastifyInstance;

describe("HTTP contract without a database connection", () => {
  beforeAll(async () => {
    app = await buildApp(testConfig("postgresql://invalid:invalid@127.0.0.1:1/invalid"));
  });

  afterAll(async () => app.close());

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
});
