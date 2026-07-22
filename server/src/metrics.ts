import type { FastifyInstance, FastifyRequest } from "fastify";
import { collectDefaultMetrics, Gauge, Histogram, Registry } from "prom-client";
import type { AppConfig } from "./config.js";
import { Database } from "./database.js";
import { ApiError } from "./errors.js";
import { constantTimeTokenEquals } from "./security.js";

export function registerMetrics(
  app: FastifyInstance,
  database: Database,
  config: AppConfig,
): void {
  const registry = new Registry();
  collectDefaultMetrics({ register: registry, prefix: "xlib_sync_" });
  const latency = new Histogram({
    name: "xlib_sync_http_request_duration_seconds",
    help: "HTTP request latency in seconds",
    labelNames: ["method", "route", "status"] as const,
    buckets: [0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5],
    registers: [registry],
  });
  const databaseHealth = new Gauge({
    name: "xlib_sync_database_health",
    help: "Whether the latest database health query succeeded",
    registers: [registry],
  });
  const poolConnections = new Gauge({
    name: "xlib_sync_database_pool_connections",
    help: "PostgreSQL pool connections by state",
    labelNames: ["state"] as const,
    collect() {
      this.set({ state: "total" }, database.pool.totalCount);
      this.set({ state: "idle" }, database.pool.idleCount);
      this.set({ state: "waiting" }, database.pool.waitingCount);
    },
    registers: [registry],
  });
  void poolConnections;

  const started = new WeakMap<FastifyRequest, bigint>();
  app.addHook("onRequest", (request, _reply, done) => {
    started.set(request, process.hrtime.bigint());
    done();
  });
  app.addHook("onResponse", (request, reply, done) => {
    const start = started.get(request);
    if (start) {
      const route = request.routeOptions.url ?? "unmatched";
      latency.observe(
        { method: request.method, route, status: String(reply.statusCode) },
        Number(process.hrtime.bigint() - start) / 1_000_000_000,
      );
    }
    done();
  });

  app.get("/metrics", async (request, reply) => {
    if (!config.metricsToken) throw new ApiError(404, "NOT_FOUND", "resource was not found");
    const supplied = request.headers.authorization?.replace(/^Bearer /, "") ?? "";
    if (!constantTimeTokenEquals(supplied, config.metricsToken)) {
      throw new ApiError(404, "NOT_FOUND", "resource was not found");
    }
    reply.header("Content-Type", registry.contentType);
    return registry.metrics();
  });

  app.get("/health", async (_request, reply) => {
    try {
      await database.pool.query("select 1");
      databaseHealth.set(1);
      return { status: "ok" };
    } catch {
      databaseHealth.set(0);
      return reply.status(503).send({ status: "unavailable" });
    }
  });
}
