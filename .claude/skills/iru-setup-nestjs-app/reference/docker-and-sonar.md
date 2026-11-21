# Container image, `.env.example`, and Sonar

## Dockerfile

Written at the repository root for every run (`distribution: internal` additionally makes the workflows push it to
GHCR; with `none` it is still built locally/in CI). Multi-stage, `node:<node-version>-slim`, production
dependencies only, non-root `node` user, root-owned application files, exec-form `CMD`, and a `HEALTHCHECK` on
`/health/live` using Node's `fetch` (the slim image has no `curl`). Verified: `docker build` + `docker run` reported
`healthy` for the no-ORM, Prisma, and TypeORM variants (the latter two against a PostgreSQL container on a user
network, `/health/ready` reporting the database `up`, `POST /books` → `201`, an unknown property → `400`).

### `Dockerfile`

```dockerfile
# syntax=docker/dockerfile:1

# ---- build stage: full dependency tree, compiles TypeScript ----
FROM node:24-slim AS build
WORKDIR /usr/src/app

# Manifests first, so the dependency layer is reused until they change.
COPY package.json package-lock.json ./
RUN npm ci

COPY nest-cli.json tsconfig.json tsconfig.build.json ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

# ---- production stage: runtime files only ----
FROM node:24-slim AS production
ENV NODE_ENV=production
WORKDIR /usr/src/app

# Copied without --chown: the files stay owned by root and are only readable by the "node" user.
COPY --from=build /usr/src/app/package.json ./
COPY --from=build /usr/src/app/node_modules ./node_modules
COPY --from=build /usr/src/app/dist ./dist

# The official node images ship an unprivileged "node" user (uid 1000).
USER node
EXPOSE 3000

# The slim image has no curl: probe liveness with Node's own fetch.
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
  CMD ["node", "-e", "fetch('http://127.0.0.1:3000/health/live').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))"]

# Exec form: node is PID 1 and receives SIGTERM directly, so the shutdown hooks run.
CMD ["node", "dist/main.js"]
```

### `.dockerignore`

```text
node_modules
dist
coverage
docs
.git
.github
.claude
*.log
.env*
!.env.example
test
src/generated
```

### Deltas

- **`orm: prisma`** — between `RUN npm ci` and the `nest-cli.json` copy, add (the client is generated, then
  compiled with `src/`; `.dockerignore` keeps a stale local `src/generated` out):

  ```dockerfile
  # The Prisma client is generated into src/generated/ (not committed), then compiled with the rest of src/.
  COPY prisma.config.ts ./
  COPY prisma ./prisma
  RUN npx prisma generate
  ```

- **Migrations are not run by the image** (`persistence.md`: migrations run outside the app). Run them as a
  deployment step: `npx prisma migrate deploy` (Prisma), `npm run migration:run` from a build with dev
  dependencies (TypeORM), `npx drizzle-kit migrate` (Drizzle) — verified for Prisma by running
  `prisma migrate deploy` from a `node:24-slim` container on the same network before starting the app.
- **`package-manager: pnpm`** — copy `pnpm-lock.yaml` instead of `package-lock.json`, and replace the two install
  commands with `RUN corepack enable && pnpm install --frozen-lockfile` and
  `RUN pnpm run build && pnpm prune --prod`. **`yarn`** — `yarn.lock`, `RUN corepack enable && yarn install
  --immutable`, `RUN yarn build && yarn workspaces focus --production` (Yarn Berry) or
  `yarn install --production --frozen-lockfile` (Yarn 1). Neither was verified here; npm was.
- **`node-version`** — the base image tag of both stages (`node:<node-version>-slim`; default `24`, the active LTS).

## `.env.example`

Committed; `.env` itself never is. ConfigModule reads `.env` by default for local runs.

```text
# Copy to .env for local development. Never commit real secrets.
NODE_ENV=development
PORT=3000
# Only with a database (orm != none):
DATABASE_URL=postgresql://app:app@localhost:5432/app
```

(`mongodb://localhost:27017/app` for Mongoose; `mysql://app:app@localhost:3306/app` for MySQL.)

## `sonar-project.properties` (only when `sonar` ≠ `none`)

```properties
sonar.organization=<sonar-organization>
sonar.projectKey=<sonar-project-key>
sonar.host.url=<sonar-host-url>
sonar.sources=src
sonar.tests=src,test
sonar.test.inclusions=**/*.spec.ts,**/*.e2e-spec.ts
sonar.exclusions=src/generated/**,src/database/migrations/**
sonar.coverage.exclusions=**/*.module.ts,src/main.ts,src/database/data-source.ts,test/**
sonar.javascript.lcov.reportPaths=coverage/lcov.info
```

Omit `sonar.organization` for a self-hosted server without organizations. `sonar.tests=src,test` because unit
specs sit next to the source and e2e specs live in `test/`; `sonar.test.inclusions` is what tells Sonar which files
under `src` are tests. The coverage exclusions mirror the Vitest/Jest config so Sonar and the gate agree.
