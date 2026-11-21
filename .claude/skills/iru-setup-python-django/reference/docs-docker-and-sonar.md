# Sphinx API docs, container files, and Sonar

Sphinx sources under `docs/api/` (next to — never touching — the Antora `docs/antora.yml` that `iru-setup-antora` owns), the multi-stage `Dockerfile`, `.dockerignore` (excludes `docs/`, so Antora's Node toolchain stays out of the build context), `compose.yaml` (`db` + one-shot `migrate` + `app`), `.env.example`, and `sonar-project.properties` (only when `sonar` ≠ `none`).

Templates use this skill's tag convention (see SKILL.md, "Template conventions"): a line ending in `# [api: drf]` / `# [database: postgresql]` is kept only for that choice (drop the tag), a line that is only `# [api: ninja]` opens a block kept only for that choice and `# [end]` closes it, and `<placeholders>` are replaced with Step 2's values.

## `docs/api/conf.py`

```python
"""Sphinx configuration for the generated API reference (``docs/api``).

Build (from the repository root)::

    uv run --group docs sphinx-build -W --keep-going -b html docs/api build/api-docs

Django is set up with the test settings so autodoc can import models and views
without any environment variable or database.
"""

import os
import sys
from pathlib import Path

import django

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings.test")
django.setup()

from config import __version__  # noqa: E402

project = "<name>"
author = "<developer-name>"
copyright = "%Y, <developer-name>"
release = __version__
version = __version__

extensions = [
    "sphinx.ext.autodoc",
    "sphinx.ext.napoleon",
    "sphinx.ext.viewcode",
    "sphinx_autodoc_typehints",
]

autodoc_default_options = {
    "members": True,
    "show-inheritance": True,
}
autodoc_member_order = "bysource"
napoleon_google_docstring = True
napoleon_numpy_docstring = False
always_use_bars_union = True

html_theme = "alabaster"
html_title = f"<name> {__version__} API reference"
```

## `docs/api/index.rst`

```rst
API reference
=============

Generated from the docstrings of the project's Python modules. The narrative
documentation lives in the Antora site; this reference is published under its
``api/`` path.

.. toctree::
   :maxdepth: 2
   :caption: Modules

   config
   core
   accounts
   catalog
```

## `docs/api/config.rst`

```rst
Configuration
=============

.. automodule:: config

.. automodule:: config.env
```

## `docs/api/core.rst`

```rst
Core
====

.. automodule:: apps.core.models

.. automodule:: apps.core.views
```

## `docs/api/accounts.rst`

```rst
Accounts
========

.. automodule:: apps.accounts.models
```

## `docs/api/catalog.rst`

```rst
Catalog
=======

.. automodule:: apps.catalog.models

.. automodule:: apps.catalog.services

.. automodule:: apps.catalog.api.serializers  # [api: drf]

.. automodule:: apps.catalog.api.views  # [api: drf]

.. automodule:: apps.catalog.schemas  # [api: ninja]

.. automodule:: apps.catalog.api  # [api: ninja]
```

## `Dockerfile`

```dockerfile
# syntax=docker/dockerfile:1

# ---- builder: resolves the locked dependencies into /app/.venv ----
FROM python:<python-version>-slim-trixie AS builder
COPY --from=ghcr.io/astral-sh/uv:<uv-version> /uv /uvx /bin/
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_NO_DEV=1 \
    UV_PYTHON_DOWNLOADS=0
WORKDIR /app

# Dependencies first: this layer is reused until uv.lock or pyproject.toml change.
RUN --mount=type=cache,target=/root/.cache/uv \
    --mount=type=bind,source=uv.lock,target=uv.lock \
    --mount=type=bind,source=pyproject.toml,target=pyproject.toml \
    uv sync --locked --no-install-project

COPY . /app
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --locked

# Static files are collected at build time with throw-away values (never real secrets).
RUN DJANGO_SETTINGS_MODULE=config.settings.production \
    DJANGO_SECRET_KEY=collectstatic-only \
    DJANGO_ALLOWED_HOSTS=localhost \
    POSTGRES_HOST=unused POSTGRES_PASSWORD=unused \
    /app/.venv/bin/python manage.py collectstatic --noinput --verbosity 0

# ---- runtime: same Python, no build tools, non-root ----
FROM python:<python-version>-slim-trixie
ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    DJANGO_SETTINGS_MODULE=config.settings.production
ENV DJANGO_SQLITE_PATH=/data/db.sqlite3  # [database: sqlite]
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home app
RUN mkdir /data && chown 10001:10001 /data  # [database: sqlite]
WORKDIR /app
# Code stays owned by root: the app user can read it but not change it.
COPY --from=builder --chown=root:root /app /app
USER 10001:10001
EXPOSE 8000

# The slim image has no curl: probe liveness with the standard library.
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
  CMD ["python", "-c", "import sys, urllib.request; sys.exit(urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=2).status != 200)"]

# Exec form: Gunicorn is PID 1 and receives SIGTERM. Settings in gunicorn.conf.py.
CMD ["gunicorn"]
```

## `.dockerignore`

```text
.git
.github
.claude
.venv
__pycache__/
*.py[cod]
.env
.env.*
!.env.example
db.sqlite3
staticfiles/
media/
build/
dist/
docs/
.pytest_cache/
.mypy_cache/
.ruff_cache/
.coverage
.coverage.*
.ui-screenshots/
test-results/
compose*.yaml
Dockerfile
.dockerignore
*.log
```

## `compose.yaml`

```yaml
# Local production-like stack: `docker compose up --build`, then http://localhost:8000/healthz.
# Values come from .env (copy .env.example; never commit .env).
name: <name>

x-django: &django
  build: .
  image: <name>:local
  environment:
    DJANGO_SECRET_KEY: ${DJANGO_SECRET_KEY:?set DJANGO_SECRET_KEY in .env}
    DJANGO_ALLOWED_HOSTS: localhost,127.0.0.1
    DJANGO_HTTPS: "0"
    POSTGRES_HOST: db  # [database: postgresql]
    POSTGRES_DB: <package>  # [database: postgresql]
    POSTGRES_USER: <package>  # [database: postgresql]
    POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD in .env}  # [database: postgresql]
  volumes:  # [database: sqlite]
    - sqlite-data:/data  # [database: sqlite]

services:
  # [database: postgresql]
  db:
    image: postgres:18
    environment:
      POSTGRES_DB: <package>
      POSTGRES_USER: <package>
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD in .env}
    ports:
      - "127.0.0.1:5432:5432"
    volumes:
      - db-data:/var/lib/postgresql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U <package> -d <package>"]
      interval: 5s
      timeout: 5s
      retries: 10

  # [end]
  # One-shot: applies migrations, then exits; the app waits for it.
  migrate:
    <<: *django
    command: ["python", "manage.py", "migrate", "--noinput"]
    restart: "no"
    depends_on:  # [database: postgresql]
      db:  # [database: postgresql]
        condition: service_healthy  # [database: postgresql]

  app:
    <<: *django
    ports:
      - "8000:8000"
    depends_on:
      migrate:
        condition: service_completed_successfully

volumes:
  db-data:  # [database: postgresql]
  sqlite-data:  # [database: sqlite]
```

## `.env.example`

```text
# Copy to .env (git-ignored, never baked into an image) and fill in real values.
# Read by `docker compose` and by `uv run --env-file .env python manage.py ...`.
DJANGO_SECRET_KEY=replace-with-a-long-random-value
POSTGRES_PASSWORD=replace-with-a-local-password  # [database: postgresql]
# Point manage.py at the compose database instead of the local SQLite file:  # [database: postgresql]
# POSTGRES_HOST=localhost  # [database: postgresql]
# DJANGO_LOG_LEVEL=DEBUG
```

## `sonar-project.properties`

```properties
sonar.organization=<sonar-organization>
sonar.projectKey=<sonar-project-key>
sonar.host.url=<sonar-host-url>
sonar.projectName=<name>
sonar.sources=apps,config
sonar.tests=tests
sonar.exclusions=**/migrations/**
sonar.coverage.exclusions=**/migrations/**,config/settings/**,config/asgi.py,config/wsgi.py
sonar.python.version=<python-version>
sonar.python.coverage.reportPaths=build/coverage/coverage.xml
sonar.python.xunit.reportPath=build/test-results/pytest*.xml
sonar.python.ruff.reportPaths=build/reports/ruff.json
sonar.sourceEncoding=UTF-8
```
