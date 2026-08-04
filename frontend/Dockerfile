# =============================================================================
# CityPulse OS frontend
#
# Uses Next.js standalone output, which traces the exact dependency set the
# server needs. The runtime image therefore carries no node_modules tree and no
# build toolchain.
# =============================================================================

FROM node:22-alpine AS deps

WORKDIR /app

# Dependencies install from the lockfile alone, so this layer is reused
# whenever application source changes but dependencies do not.
COPY package.json package-lock.json ./
RUN npm ci

# -----------------------------------------------------------------------------

FROM node:22-alpine AS build

WORKDIR /app

COPY --from=deps /app/node_modules ./node_modules
COPY . .

# NEXT_PUBLIC_* values are inlined into the client bundle at build time, so this
# must be a build argument. Only non-secret configuration belongs here — anything
# passed as NEXT_PUBLIC_* is readable by anyone loading the page.
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
ENV NEXT_TELEMETRY_DISABLED=1

RUN npm run build

# -----------------------------------------------------------------------------

FROM node:22-alpine AS runtime

WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

RUN addgroup -S citypulse && adduser -S -G citypulse citypulse

# The standalone bundle already contains the traced dependencies; static assets
# are copied separately because the tracer does not include them.
COPY --from=build --chown=citypulse:citypulse /app/.next/standalone ./
COPY --from=build --chown=citypulse:citypulse /app/.next/static ./.next/static
COPY --from=build --chown=citypulse:citypulse /app/public ./public

USER citypulse

EXPOSE 3000

CMD ["node", "server.js"]
