# Tooling templates: `pyproject.toml`, `uv` commands, OpenAPI export, Sphinx API docs, `.env.example`

## `uv` commands (Step 4)

Versions are the digest's pins (irurueta docs: `backend/fastapi/index.adoc`, `deployment.adoc`,
`sql-databases-with-sqlmodel.adoc`, `docker-and-kubernetes.adoc`) and what this skill was verified with
(2026-10-14): FastAPI 0.142.4 (`fastapi[standard]>=0.142.2,<0.143.0` — FastAPI is 0.x, pin the minor; 0.143 existed
but was not adopted: upgrade deliberately after reading its release notes), Starlette 1.8 (never pinned), Pydantic
2.14, pydantic-settings 2.16, SQLModel 0.0.47, SQLAlchemy 2.0.54, Alembic 1.20.0, asyncpg 0.32, aiosqlite 0.22,
pytest 9.1, pytest-cov 7.1, AnyIO 4.15, HTTPX 0.28, asgi-lifespan 2.1, testcontainers 4.15, Ruff 0.17, mypy 2.4,
Sphinx 9.1, sphinx-autodoc-typehints 3.13, uv 0.12/0.13, `uv_build` 0.13.

```bash
uv init --bare --name <name> --description "<description>" --python <python-version> --vcs none
uv python pin <python-version>
# Edit [project] now (version, license, authors, requires-python, urls) and add [build-system] +
# [tool.uv.build-backend] from the template below, and create app/__init__.py: `uv add` builds the project
# and fails with "Expected a Python module at: app/__init__.py" otherwise.
uv add "fastapi[standard]>=0.142.2,<0.143.0" "pydantic>=2.13.5,<3.0.0" "pydantic-settings>=2.15,<3" \
       "sqlmodel>=0.0.47,<0.0.48" "sqlalchemy[asyncio]>=2.0.14,<2.1" "alembic>=1.20,<2" "asyncpg>=0.31,<1"
uv add --dev "pytest>=9.1,<10" "pytest-cov>=7,<8" "anyio>=4.15,<5" "httpx>=0.28,<1" "asgi-lifespan>=2.1,<3" \
       "aiosqlite>=0.22,<1" "testcontainers[postgres]>=4.15,<5" "ruff>=0.17,<0.18" "mypy>=2.4,<3"
uv add --group docs "sphinx>=9.1,<10" "sphinx-autodoc-typehints>=3.13,<4"
```

Shape deltas: `orm: sqlalchemy` → leave out `sqlmodel`; `database: sqlite` → `aiosqlite` in the main `uv add`
instead of `asyncpg`, and no `aiosqlite`/`testcontainers` in `dev`; `database: none` → only `fastapi[standard]`,
`pydantic`, `pydantic-settings` (+ the `dev` list without `aiosqlite`/`testcontainers`); `auth: yes` → also
`"pyjwt>=2.15,<3" "pwdlib[argon2]>=0.3,<0.4"`.

Gotchas, all observed:

- **`sqlalchemy[asyncio]` must be declared.** SQLAlchemy 2.0 installs greenlet only for some platform tags — on
  macOS arm64 it doesn't, and every async call (Alembic included) fails with `ValueError: the greenlet library is
  required`. The range follows SQLModel's own pin (`>=2.0.14,<2.1`); 2.1 also stops installing greenlet by default.
- **`[tool.coverage.run] concurrency = ["greenlet", "thread"]`** — SQLAlchemy's async layer resumes coroutines in
  greenlets; without it lines after an awaited database call are reported uncovered (verified:
  `app/data/books.py` at 83% without it, 100% with it).
- `readme = "README.md"` is deliberately absent: `uv_build` fails on a missing readme, and `iru-setup-readme` writes
  it later. Add the key after the README exists (the README skill or the user).
- `uv_build` with `module-root = ""` / `module-name = "app"` builds the flat `app/` package; the wheel contains only
  `app/`, the sdist `app/` + `pyproject.toml` (verified with `uv build`).
- `[tool.ruff] line-length = 88` — Ruff's and Black's default (PEP 8's 79 is the docs' prose rule; 88 is what
  `ruff format` produces with no configuration, keeps diffs stable across editors, and is what this catalog's other
  Python skills assume). It applies to docstrings too (`E501`): wrap them.

### `pyproject.toml`

The verified file for the default shape (`database: postgresql`, `orm: sqlmodel`, `auth: no`) after the Step 4 `uv` commands plus the tool sections appended by hand. `uv add` keeps the dependency lists sorted; never edit them by hand.

```toml
[project]
name = "<name>"
version = "0.1.0.dev0"
description = "<description>"
license = "<license-spdx>"
authors = [{ name = "<developer-name>", email = "<developer-email>" }]
requires-python = ">=3.12"
dependencies = [
    "alembic>=1.20,<2",
    "asyncpg>=0.31,<1",
    "fastapi[standard]>=0.142.2,<0.143.0",
    "pydantic>=2.13.5,<3.0.0",
    "pydantic-settings>=2.15,<3",
    "sqlalchemy[asyncio]>=2.0.14,<2.1",
    "sqlmodel>=0.0.47,<0.0.48",
]

[project.urls]
Homepage = "<organization-url>"

[build-system]
requires = ["uv_build>=0.12.8,<0.14"]
build-backend = "uv_build"

[tool.uv.build-backend]
module-name = "app"
module-root = ""

[dependency-groups]
dev = [
    "aiosqlite>=0.22,<1",
    "anyio>=4.15,<5",
    "asgi-lifespan>=2.1,<3",
    "httpx>=0.28,<1",
    "mypy>=2.4,<3",
    "pytest>=9.1,<10",
    "pytest-cov>=7,<8",
    "ruff>=0.17,<0.18",
    "testcontainers[postgres]>=4.15,<5",
]
docs = [
    "sphinx>=9.1,<10",
    "sphinx-autodoc-typehints>=3.13,<4",
]

[tool.fastapi]
entrypoint = "app.main:app"

[tool.ruff]
line-length = 88
extend-exclude = ["docs/build", "build"]

[tool.ruff.lint]
select = [
    "E", "F", "W", "I", "N", "UP", "B", "S", "C4", "SIM", "RUF", "D", "PT", "ASYNC",
    "FAST",
]

[tool.ruff.lint.pydocstyle]
convention = "google"

[tool.ruff.lint.per-file-ignores]
"tests/**" = ["S101", "D"]
"migrations/versions/**" = ["D"]

[tool.mypy]
strict = true
plugins = ["pydantic.mypy"]
exclude = ["^build/", "^docs/build/"]

[tool.pydantic-mypy]
init_forbid_extra = true
init_typed = true
warn_required_dynamic_aliases = true

[tool.pytest.ini_options]
addopts = "-ra --strict-markers --strict-config"
testpaths = ["tests"]
markers = ["integration: needs Docker (testcontainers)"]
filterwarnings = ["error::DeprecationWarning"]

[tool.coverage.run]
branch = true
source = ["app"]
# SQLAlchemy's async layer resumes coroutines in greenlets: without this, lines after
# an awaited database call are reported as not covered.
concurrency = ["greenlet", "thread"]

[tool.coverage.report]
fail_under = 80
show_missing = true
exclude_also = ["if TYPE_CHECKING:", "if __name__ == .__main__.:"]

[tool.coverage.xml]
output = "build/coverage/coverage.xml"
```

### `pyproject.toml` deltas per shape

```diff
# ---- orm: sqlalchemy ----
--- a/pyproject.toml
+++ b/pyproject.toml
@@ -14,3 +14,2 @@
     "sqlalchemy[asyncio]>=2.0.14,<2.1",
-    "sqlmodel>=0.0.47,<0.0.48",
 ]
# ---- database: sqlite ----
--- a/pyproject.toml
+++ b/pyproject.toml
@@ -8,4 +8,4 @@
 dependencies = [
+    "aiosqlite>=0.22,<1",
     "alembic>=1.20,<2",
-    "asyncpg>=0.31,<1",
     "fastapi[standard]>=0.142.2,<0.143.0",
@@ -30,3 +30,2 @@
 dev = [
-    "aiosqlite>=0.22,<1",
     "anyio>=4.15,<5",
@@ -38,3 +37,2 @@
     "ruff>=0.17,<0.18",
-    "testcontainers[postgres]>=4.15,<5",
 ]
# ---- database: none ----
--- a/pyproject.toml
+++ b/pyproject.toml
@@ -8,4 +8,2 @@
 dependencies = [
-    "alembic>=1.20,<2",
-    "asyncpg>=0.31,<1",
     "fastapi[standard]>=0.142.2,<0.143.0",
@@ -13,4 +11,2 @@
     "pydantic-settings>=2.15,<3",
-    "sqlalchemy[asyncio]>=2.0.14,<2.1",
-    "sqlmodel>=0.0.47,<0.0.48",
 ]
@@ -30,3 +26,2 @@
 dev = [
-    "aiosqlite>=0.22,<1",
     "anyio>=4.15,<5",
@@ -38,3 +33,2 @@
     "ruff>=0.17,<0.18",
-    "testcontainers[postgres]>=4.15,<5",
 ]
@@ -63,3 +57,2 @@
 "tests/**" = ["S101", "D"]
-"migrations/versions/**" = ["D"]
 
@@ -84,5 +77,2 @@
 source = ["app"]
-# SQLAlchemy's async layer resumes coroutines in greenlets: without this, lines after
-# an awaited database call are reported as not covered.
-concurrency = ["greenlet", "thread"]
 
# ---- auth: yes ----
--- a/pyproject.toml
+++ b/pyproject.toml
@@ -11,4 +11,6 @@
     "fastapi[standard]>=0.142.2,<0.143.0",
+    "pwdlib[argon2]>=0.3,<0.4",
     "pydantic>=2.13.5,<3.0.0",
     "pydantic-settings>=2.15,<3",
+    "pyjwt>=2.15,<3",
     "sqlalchemy[asyncio]>=2.0.14,<2.1",
```

## OpenAPI export and Sphinx API docs

`scripts/export_openapi.py` builds the app without starting it (no settings, no database), so CI can publish
`openapi.json` next to the API docs: `uv run python scripts/export_openapi.py build/api-docs/openapi.json`.

The Sphinx sources live in `docs/api/` next to (never inside) the Antora component `iru-setup-antora` owns
(`docs/antora.yml`, `docs/modules/`); this skill never writes an Antora file. Build:
`uv run --group docs sphinx-build -W --keep-going -b html docs/api build/api-docs`. Gotcha, verified: with Sphinx
9.1 + sphinx-autodoc-typehints 3.13, autodoc still feeds the docstrings of names a module merely *imports*
(`AsyncSession`, `Field`, `Request`) to the typehints extension, which turns their reST quirks into ~14 warnings
(fatal under `-W`); the `autodoc-skip-member` hook in `conf.py` skips them. `orm: sqlalchemy`: add
`:exclude-members: metadata` under `.. automodule:: app.models.base` (it otherwise inherits SQLAlchemy's
`MetaData` docstring with an unresolvable `:ref:`). Add one `.. automodule::` per new module (`app.security`,
`app.routers.auth`, `app.models.auth` for `auth: yes`; drop `app.db` for `database: none`). The RST title underline
must be at least as long as the title after substituting `<title>`.

### `scripts/export_openapi.py`

```python
"""Write the application's OpenAPI document to a JSON file.

Usage: ``uv run python scripts/export_openapi.py build/api-docs/openapi.json``

Builds the app without starting it (no database, no settings needed), so CI can publish
the contract next to the API docs.
"""

import json
import sys
from pathlib import Path

from app.main import create_app


def main(argv: list[str]) -> int:
    """Export the OpenAPI document.

    Args:
        argv: Command-line arguments; the first one is the output path.

    Returns:
        The process exit code.
    """
    if len(argv) != 1:
        print("usage: export_openapi.py <output.json>", file=sys.stderr)
        return 2
    output = Path(argv[0])
    output.parent.mkdir(parents=True, exist_ok=True)
    document = create_app().openapi()
    output.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    print(f"OpenAPI {document['openapi']} written to {output}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
```

### `docs/api/conf.py`

```python
"""Sphinx configuration for the Python API reference (published under ``api/``)."""

from typing import Any

from app import __version__

project = "<title>"
author = "<developer-name>"
copyright = "%Y, <developer-name>"
release = __version__
version = __version__

extensions = [
    "sphinx.ext.autodoc",
    "sphinx.ext.napoleon",
    "sphinx_autodoc_typehints",
    "sphinx.ext.viewcode",
]

napoleon_google_docstring = True
napoleon_numpy_docstring = False
autodoc_default_options = {
    "members": True,
    "show-inheritance": True,
}
autodoc_member_order = "bysource"
typehints_fully_qualified = False
always_document_param_types = False

html_theme = "alabaster"
html_title = f"{project} {release} API reference"
exclude_patterns = ["_build"]


def _skip_imported_members(
    _app: Any, what: str, _name: str, obj: Any, skip: bool, _options: Any
) -> bool | None:
    """Skip names a module only imports (FastAPI, SQLAlchemy, Pydantic...).

    Without this, sphinx-autodoc-typehints still processes their third-party
    docstrings and turns their reST quirks into build warnings.
    """
    module = getattr(obj, "__module__", None)
    if what == "module" and isinstance(module, str) and not module.startswith("app"):
        return True
    return True if skip else None


def setup(app: Any) -> None:
    """Register the autodoc hooks."""
    app.connect("autodoc-skip-member", _skip_imported_members)
```

### `docs/api/index.rst`

```rst
<title> — Python API reference
====================================

Generated from the docstrings of the ``app`` package. The HTTP contract itself is the
OpenAPI document (``openapi.json``) published next to this reference.

.. autosummary is not used on purpose: one page per layer keeps cross-references short.

Application
-----------

.. automodule:: app.main

.. automodule:: app.settings

.. automodule:: app.dependencies

.. automodule:: app.db

Web layer
---------

.. automodule:: app.routers.books

.. automodule:: app.routers.health

Service layer
-------------

.. automodule:: app.services.books

.. automodule:: app.services.ports

.. automodule:: app.services.errors

Data layer and models
---------------------

.. automodule:: app.data.books

.. automodule:: app.models.book

.. automodule:: app.models.health
```

### `.env.example` per shape

### `.env.example (database: postgresql)`

```text
# Copy to .env for local development (`uv run fastapi dev`, `docker compose up`).
# Never commit .env: it holds real credentials.

# Application settings (APP_ prefix, see app/settings.py).
APP_DATABASE_URL=postgresql+asyncpg://app:change-me@localhost:5432/app
APP_DATABASE_ECHO=false

# Local PostgreSQL started by compose.yaml.
POSTGRES_PASSWORD=change-me
```

### `.env.example (database: sqlite)`

```text
# Copy to .env for local development (`uv run fastapi dev`). Never commit .env.

# Application settings (APP_ prefix, see app/settings.py).
APP_DATABASE_URL=sqlite+aiosqlite:///./data/app.db
APP_DATABASE_ECHO=false
```

### `.env.example (database: none)`

```text
# Copy to .env for local development (`uv run fastapi dev`). Never commit .env.

# Application settings (APP_ prefix, see app/settings.py).
APP_MAX_BOOKS=10000
```
