import { randomUUID } from "node:crypto";
import rateLimit from "@fastify/rate-limit";
import Fastify, { LogController, type FastifyInstance, type FastifyRequest } from "fastify";
import { ZodError, type ZodType } from "zod";
import { AuthService } from "./auth-service.js";
import type { AppConfig } from "./config.js";
import { Database } from "./database.js";
import { ApiError, sendApiError } from "./errors.js";
import { registerMetrics } from "./metrics.js";
import { ProgressService } from "./progress-service.js";
import {
  deviceIdParamsSchema,
  firstValidationMessage,
  progressSyncSchema,
  startSyncSchema,
} from "./schemas.js";
import { hashToken } from "./security.js";
import { StartSyncRateLimiter } from "./start-sync-rate-limiter.js";

function parse<T>(schema: ZodType<T>, value: unknown): T {
  try {
    return schema.parse(value);
  } catch (error) {
    if (error instanceof ZodError) {
      throw new ApiError(422, "VALIDATION_FAILED", firstValidationMessage(error));
    }
    throw error;
  }
}

function parseProgressSync(value: unknown) {
  if (
    typeof value === "object" &&
    value !== null &&
    "items" in value &&
    Array.isArray(value.items) &&
    value.items.length > 100
  ) {
    throw new ApiError(413, "BATCH_TOO_LARGE", "a progress batch can contain at most 100 items");
  }
  return parse(progressSyncSchema, value);
}

async function requireAuth(request: FastifyRequest, authService: AuthService) {
  return authService.authenticate(request.headers.authorization, request.headers["x-device-id"]);
}

function startSyncEmailHash(request: FastifyRequest): string {
  const body = request.body;
  const email =
    typeof body === "object" && body !== null && "email" in body && typeof body.email === "string"
      ? body.email.trim().toLowerCase()
      : "";
  return hashToken(email).toString("hex");
}

function businessRateKey(request: FastifyRequest): string {
  const token = request.headers.authorization?.replace(/^Bearer /, "") ?? "";
  const rawDeviceId = request.headers["x-device-id"];
  const deviceId = Array.isArray(rawDeviceId) ? rawDeviceId.join(",") : (rawDeviceId ?? "");
  return hashToken(`${token}:${deviceId}`).toString("hex");
}

const businessLimit = (max: number) => ({
  max,
  timeWindow: "1 minute",
  keyGenerator: businessRateKey,
});

export async function buildApp(
  config: AppConfig,
  database = new Database(config),
): Promise<FastifyInstance> {
  const app = Fastify({
    logger: {
      level: config.logLevel,
      redact: ["req.headers.authorization", "headers.authorization", "body.email"],
    },
    trustProxy: config.trustProxy,
    bodyLimit: 256 * 1024,
    genReqId: () => randomUUID(),
    logController: new LogController({ disableRequestLogging: true }),
  });
  const authService = new AuthService(database, config);
  const progressService = new ProgressService(database);
  const startSyncLimiter = new StartSyncRateLimiter();

  await app.register(rateLimit, {
    global: false,
    errorResponseBuilder: (request) => ({
      error: {
        code: "RATE_LIMITED",
        message: "too many requests",
        retryable: true,
        requestId: request.id,
      },
    }),
  });

  app.addHook("onRequest", async (request, reply) => {
    reply.header("X-Request-Id", request.id);
    if (request.url.startsWith("/v1")) reply.header("Cache-Control", "no-store");
    if (
      config.enforceHttps &&
      request.url !== "/health" &&
      request.url !== "/metrics" &&
      request.protocol !== "https"
    ) {
      throw new ApiError(400, "HTTPS_REQUIRED", "HTTPS is required");
    }
  });

  app.addHook("onResponse", async (request, reply) => {
    request.log.info(
      {
        requestId: request.id,
        method: request.method,
        route: request.routeOptions.url,
        statusCode: reply.statusCode,
        responseTimeMs: reply.elapsedTime,
      },
      "request completed",
    );
  });

  registerMetrics(app, database, config);

  app.post(
    "/v1/auth/start-sync",
    {
      preHandler: (request, _reply, done) => {
        try {
          startSyncLimiter.consume(request.ip, startSyncEmailHash(request));
          done();
        } catch (error) {
          done(error instanceof Error ? error : new Error("start-sync rate limiter failed"));
        }
      },
    },
    async (request) => authService.startSync(parse(startSyncSchema, request.body)),
  );

  app.get(
    "/v1/devices",
    { config: { rateLimit: businessLimit(120) } },
    async (request) => authService.listDevices(await requireAuth(request, authService)),
  );

  app.delete(
    "/v1/devices/:deviceId",
    { config: { rateLimit: businessLimit(30) } },
    async (request, reply) => {
      const auth = await requireAuth(request, authService);
      const params = parse(deviceIdParamsSchema, request.params);
      await authService.revokeDevice(auth, params.deviceId);
      return reply.status(204).send();
    },
  );

  app.delete(
    "/v1/account",
    { config: { rateLimit: businessLimit(10) } },
    async (request, reply) => {
      await authService.deleteAccount(await requireAuth(request, authService));
      return reply.status(204).send();
    },
  );

  app.get(
    "/v1/progress",
    { config: { rateLimit: businessLimit(120) } },
    async (request) => progressService.list(await requireAuth(request, authService)),
  );

  app.post(
    "/v1/progress/sync",
    { config: { rateLimit: businessLimit(120) } },
    async (request) => {
      const auth = await requireAuth(request, authService);
      return progressService.sync(auth, parseProgressSync(request.body));
    },
  );

  app.delete(
    "/v1/progress",
    { config: { rateLimit: businessLimit(10) } },
    async (request, reply) => {
      await progressService.deleteAll(await requireAuth(request, authService));
      return reply.status(204).send();
    },
  );

  app.setNotFoundHandler((request, reply) => {
    sendApiError(new ApiError(404, "NOT_FOUND", "resource was not found"), request, reply);
  });

  app.setErrorHandler((error, request, reply) => {
    if (error instanceof ApiError) {
      sendApiError(error, request, reply);
      return;
    }
    const errorStatus =
      typeof error === "object" && error !== null && "statusCode" in error
        ? error.statusCode
        : undefined;
    const errorCode =
      typeof error === "object" && error !== null && "code" in error ? error.code : undefined;
    if (errorStatus === 429) {
      const retryAfter = Number(reply.getHeader("retry-after") ?? 60);
      sendApiError(
        new ApiError(429, "RATE_LIMITED", "too many requests", true, retryAfter),
        request,
        reply,
      );
      return;
    }
    if (errorCode === "FST_ERR_CTP_INVALID_JSON_BODY" || error instanceof SyntaxError) {
      sendApiError(
        new ApiError(400, "INVALID_JSON", "request body must be valid JSON"),
        request,
        reply,
      );
      return;
    }
    if (errorCode === "FST_ERR_CTP_INVALID_MEDIA_TYPE") {
      sendApiError(
        new ApiError(400, "INVALID_CONTENT_TYPE", "Content-Type must be application/json"),
        request,
        reply,
      );
      return;
    }
    if (errorCode === "FST_ERR_CTP_BODY_TOO_LARGE") {
      sendApiError(
        new ApiError(413, "REQUEST_TOO_LARGE", "request body is too large"),
        request,
        reply,
      );
      return;
    }
    request.log.error({ err: error, requestId: request.id }, "request failed");
    sendApiError(
      new ApiError(500, "INTERNAL_ERROR", "an internal error occurred", true),
      request,
      reply,
    );
  });

  app.addHook("onClose", async () => database.close());
  return app;
}
