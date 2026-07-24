FROM node:22-bookworm-slim AS build
WORKDIR /app
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN corepack enable && pnpm install --frozen-lockfile
COPY tsconfig.json ./
COPY src ./src
RUN pnpm build

FROM node:22-bookworm-slim AS runtime
ENV NODE_ENV=production
WORKDIR /app
RUN groupadd --system --gid 10001 xlib && useradd --system --uid 10001 --gid xlib xlib
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN corepack enable && pnpm install --prod --frozen-lockfile && pnpm store prune
COPY --from=build /app/dist ./dist
COPY migrations ./migrations
USER xlib
EXPOSE 8080
CMD ["node", "dist/src/index.js"]
