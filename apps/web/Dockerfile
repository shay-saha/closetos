FROM node:24-bookworm-slim AS build
WORKDIR /build
RUN npm install --global pnpm@10.30.3
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web/package.json apps/web/package.json
RUN pnpm install --frozen-lockfile
COPY apps/web apps/web
RUN pnpm --filter @closetos/web build

FROM node:24-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production
ENV HOSTNAME=0.0.0.0
ENV PORT=3000
COPY --from=build --chown=node:node /build/apps/web/.next/standalone ./
COPY --from=build --chown=node:node /build/apps/web/.next/static ./apps/web/.next/static
COPY --from=build --chown=node:node /build/apps/web/public ./apps/web/public
USER node
EXPOSE 3000
CMD ["node", "apps/web/server.js"]
