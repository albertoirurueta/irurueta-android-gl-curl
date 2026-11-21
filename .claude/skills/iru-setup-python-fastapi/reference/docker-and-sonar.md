# Container image, local stack, and Sonar

## `Dockerfile` (always written), `.dockerignore`, `compose.yaml`

Multi-stage per the docs (irurueta docs: `backend/fastapi/docker-and-kubernetes.adoc`,
`backend/flask/docker-and-kubernetes.adoc`, `backend/docker/image-best-practices-and-security.adoc`): uv copied
from a pinned `ghcr.io/astral-sh/uv` image, `uv sync --locked --no-install-project` (cached, dependencies only)
then the project `--no-editable`, so the runtime stage gets only `/app/.venv` (+ `alembic.ini`/`migrations/` for the
one-shot migration job); `python:<python-version>-slim` in both stages (the venv is not relocatable — same path in
both), numeric non-root UID 10001, exec-form `CMD` running one `fastapi run` (Uvicorn) process with
`--proxy-headers`, a stdlib `HEALTHCHECK` on `/healthz` (slim has no curl). Replace `0.13.1` with the uv version
resolved in Step 3. Verified (Docker 29.8): image 267 MB; `alembic upgrade head` from the image against
`postgres:18` on a user network, then the app container reported `healthy`, `/readyz` → `{"status":"ok"}`,
`POST /api/v1/books` → `201`, `id` → `uid=10001(app)`.

`.dockerignore` excludes `docs/` (the Antora Node toolchain and the Sphinx sources never enter the build context),
`tests/`, `scripts/`, caches, `.env*` except `.env.example`.

`compose.yaml` (local stack only): `postgres:18` with a `pg_isready` health check and a named volume at
`/var/lib/postgresql` (the PostgreSQL 18 layout), a one-shot `migrate` service (`alembic upgrade head`,
`restart: "no"`), and `api` starting only after `service_completed_successfully`; the password comes from `.env`
(`${POSTGRES_PASSWORD:?...}`), ports bind to `127.0.0.1`. Validated with `POSTGRES_PASSWORD=x docker compose config
-q`. For `auth: yes`, `api` also gets `APP_JWT_SECRET`/`APP_ADMIN_PASSWORD_HASH` (diff in `auth-templates.md`).

### `Dockerfile`

```dockerfile
# syntax=docker/dockerfile:1

# ---- build stage: resolve the locked dependencies into /app/.venv with uv ----
FROM python:<python-version>-slim AS builder
COPY --from=ghcr.io/astral-sh/uv:0.13.1 /uv /uvx /bin/
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_NO_DEV=1 \
    UV_PYTHON_DOWNLOADS=never
WORKDIR /app

# Dependencies first: this layer is reused until pyproject.toml or uv.lock change.
RUN --mount=type=cache,target=/root/.cache/uv \
    --mount=type=bind,source=uv.lock,target=uv.lock \
    --mount=type=bind,source=pyproject.toml,target=pyproject.toml \
    uv sync --locked --no-install-project

# Then the project itself, installed non-editable so the runtime needs only the venv.
COPY pyproject.toml uv.lock ./
COPY app ./app
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --locked --no-editable

# ---- runtime stage: the venv, the migrations, an unprivileged user ----
FROM python:<python-version>-slim AS runtime
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PATH="/app/.venv/bin:$PATH"
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
# The venv path must be identical in both stages (virtual environments are not relocatable).
COPY --from=builder /app/.venv /app/.venv
# Only for the one-shot migration job: `alembic upgrade head` (see compose.yaml).
COPY alembic.ini ./
COPY migrations ./migrations

USER 10001:10001
EXPOSE 8000

# python:*-slim has no curl: probe liveness with the standard library.
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=2)"]

# Exec form: the server is PID 1 and receives SIGTERM, so the lifespan shutdown runs.
# One Uvicorn process per container; scale with replicas. Migrations never run here.
CMD ["fastapi", "run", "--entrypoint", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers"]
```

### `.dockerignore`

```text
.git
.github
.claude
.venv
**/__pycache__
**/*.py[cod]
.pytest_cache
.mypy_cache
.ruff_cache
.coverage*
build
dist
docs
tests
scripts
.env
.env.*
!.env.example
secrets
compose.yaml
Dockerfile
*.db
```

### `compose.yaml`

```yaml
# Local stack: PostgreSQL, a one-shot migration job, then the API.
#   cp .env.example .env   # set POSTGRES_PASSWORD
#   docker compose up --build
services:
  db:
    image: postgres:18
    environment:
      POSTGRES_USER: app
      POSTGRES_DB: app
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD in .env}
    volumes:
      - pgdata:/var/lib/postgresql
    ports:
      - "127.0.0.1:5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]
      interval: 5s
      timeout: 5s
      retries: 10
      start_period: 10s

  migrate:
    build: .
    command: ["alembic", "upgrade", "head"]
    environment: &app-env
      APP_DATABASE_URL: postgresql+asyncpg://app:${POSTGRES_PASSWORD}@db:5432/app
    depends_on:
      db:
        condition: service_healthy
    restart: "no"

  api:
    build: .
    environment: *app-env
    ports:
      - "127.0.0.1:8000:8000"
    depends_on:
      migrate:
        condition: service_completed_successfully
    restart: unless-stopped
    stop_grace_period: 30s

volumes:
  pgdata:
```

### Deltas for `database: sqlite` and `database: none`

```diff
# ---- database: sqlite ----
--- a/Dockerfile
+++ b/Dockerfile
@@ -37,2 +37,6 @@
 
+# The SQLite database lives on a volume mounted here (see compose.yaml).
+RUN mkdir -p /app/data && chown 10001:10001 /app/data
+VOLUME ["/app/data"]
+
 USER 10001:10001
# ---- database: sqlite ----
--- a/.dockerignore
+++ b/.dockerignore
@@ -22 +22,2 @@
 *.db
+data
# ---- database: none ----
--- a/Dockerfile
+++ b/Dockerfile
@@ -23,3 +23,3 @@
 
-# ---- runtime stage: the venv, the migrations, an unprivileged user ----
+# ---- runtime stage: the venv and an unprivileged user ----
 FROM python:<python-version>-slim AS runtime
@@ -33,5 +33,2 @@
 COPY --from=builder /app/.venv /app/.venv
-# Only for the one-shot migration job: `alembic upgrade head` (see compose.yaml).
-COPY alembic.ini ./
-COPY migrations ./migrations
 
@@ -45,3 +42,3 @@
 # Exec form: the server is PID 1 and receives SIGTERM, so the lifespan shutdown runs.
-# One Uvicorn process per container; scale with replicas. Migrations never run here.
+# One Uvicorn process per container; scale with replicas.
 CMD ["fastapi", "run", "--entrypoint", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers"]
# ---- database: none ----
--- a/.dockerignore
+++ b/.dockerignore
@@ -19,3 +19,3 @@
 secrets
-compose.yaml
+
 Dockerfile
```

### `compose.yaml (database: sqlite)`

`database: none` writes no `compose.yaml` (nothing to orchestrate: `docker build` + `docker run -p 8000:8000` is enough).

```yaml
# Local stack: a one-shot migration job, then the API, sharing the SQLite volume.
#   docker compose up --build
services:
  migrate:
    build: .
    command: ["alembic", "upgrade", "head"]
    environment: &app-env
      APP_DATABASE_URL: sqlite+aiosqlite:////app/data/app.db
    volumes:
      - appdata:/app/data
    restart: "no"

  api:
    build: .
    environment: *app-env
    volumes:
      - appdata:/app/data
    ports:
      - "127.0.0.1:8000:8000"
    depends_on:
      migrate:
        condition: service_completed_successfully
    restart: unless-stopped
    stop_grace_period: 30s

volumes:
  appdata:
```

## `sonar-project.properties` (only when `sonar` ≠ `none`)

Omit `sonar.organization` for a self-hosted server without organizations. Report paths are the contract's:
coverage from the fast tier (`build/coverage/coverage.xml`), JUnit from both tiers (`build/test-results/pytest*.xml`),
and the Ruff JSON report `iru-setup-python-github-workflows` writes to `build/reports/ruff.json`. `database: none`:
`sonar.exclusions=build/**` (there is no `migrations/`).

### `sonar-project.properties`

```properties
sonar.organization=<sonar-organization>
sonar.projectKey=example-org_<name>
sonar.host.url=<sonar-host-url>
sonar.sources=app
sonar.tests=tests
sonar.exclusions=migrations/**,build/**
sonar.python.version=<python-version>
sonar.python.coverage.reportPaths=build/coverage/coverage.xml
sonar.python.xunit.reportPath=build/test-results/pytest*.xml
sonar.python.ruff.reportPaths=build/reports/ruff.json
```
