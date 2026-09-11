FROM node:24-alpine AS base
WORKDIR /app
RUN corepack enable

FROM base AS dependencies
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

FROM base AS builder
ARG NEXT_PUBLIC_INNOLIVE_SIGNALING_URL
ENV NEXT_PUBLIC_INNOLIVE_SIGNALING_URL=$NEXT_PUBLIC_INNOLIVE_SIGNALING_URL
COPY --from=dependencies /app/node_modules ./node_modules
COPY . .
RUN pnpm run build

FROM base AS runner
ARG INNOLIVE_WEB_REVISION
ENV NODE_ENV=production
ENV PORT=3000
ENV INNOLIVE_WEB_REVISION=$INNOLIVE_WEB_REVISION
LABEL org.opencontainers.image.revision=$INNOLIVE_WEB_REVISION
COPY --from=builder /app/public ./public
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
EXPOSE 3000
CMD ["node", "server.js"]
