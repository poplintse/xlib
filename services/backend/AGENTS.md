# Backend-specific instructions

- Use Node.js 22+, pnpm 11, TypeScript, Fastify, and PostgreSQL 17.
- Run migrations with the database owner and run the API with the restricted application role.
- Keep request/response behavior aligned with `contracts/openapi.yaml`.
- Integration tests may only use a dedicated test database; never point them at production.
- The token encryption key is required for token recovery and must never be logged or committed.
