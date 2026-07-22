import type { FastifyReply, FastifyRequest } from "fastify";

export class ApiError extends Error {
  constructor(
    public readonly statusCode: number,
    public readonly code: string,
    message: string,
    public readonly retryable = false,
    public readonly retryAfterSeconds?: number,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export function sendApiError(
  error: ApiError,
  request: FastifyRequest,
  reply: FastifyReply,
): void {
  if (error.retryAfterSeconds !== undefined) {
    reply.header("Retry-After", String(error.retryAfterSeconds));
  }
  void reply.status(error.statusCode).send({
    error: {
      code: error.code,
      message: error.message,
      retryable: error.retryable,
      requestId: request.id,
    },
  });
}
