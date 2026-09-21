FROM node:24-alpine AS base
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
RUN corepack enable && corepack prepare pnpm@11.17.0 --activate

FROM base AS dependencies
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

FROM base AS builder
ARG NEXT_PUBLIC_INNOLIVE_SERVER_URL
ENV NEXT_PUBLIC_INNOLIVE_SERVER_URL=$NEXT_PUBLIC_INNOLIVE_SERVER_URL
COPY --from=dependencies /app/node_modules ./node_modules
COPY . .
RUN test -n "$NEXT_PUBLIC_INNOLIVE_SERVER_URL" && pnpm run build

FROM base AS runner
ARG INNOLIVE_WEB_REVISION
ARG NEXT_PUBLIC_INNOLIVE_SERVER_URL
ENV NODE_ENV=production
ENV PORT=3000
ENV INNOLIVE_WEB_REVISION=$INNOLIVE_WEB_REVISION
ENV NEXT_PUBLIC_INNOLIVE_SERVER_URL=$NEXT_PUBLIC_INNOLIVE_SERVER_URL
LABEL org.opencontainers.image.revision=$INNOLIVE_WEB_REVISION
COPY --from=builder /app ./
EXPOSE 3000
CMD ["node", "node_modules/next/dist/bin/next", "start"]
