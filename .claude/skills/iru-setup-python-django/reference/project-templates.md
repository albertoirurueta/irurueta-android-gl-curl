# Project files: `pyproject.toml`, entry points, core and accounts apps

The `[project]` header and tool configuration merged into the `uv init --bare` manifest (Step 5), the `config/` package outside `settings/`, `manage.py`, `gunicorn.conf.py`, and the two apps every project gets before its first migration: `apps.core` (abstract `TimeStampedModel`, `/healthz` + `/readyz`) and `apps.accounts` (custom user).

Templates use this skill's tag convention (see SKILL.md, "Template conventions"): a line ending in `# [api: drf]` / `# [database: postgresql]` is kept only for that choice (drop the tag), a line that is only `# [api: ninja]` opens a block kept only for that choice and `# [end]` closes it, and `<placeholders>` are replaced with Step 2's values.

## `pyproject.toml` — `[project]` header, build backend

```toml
[project]
name = "<name>"
version = "0.1.0.dev0"
description = "<description>"
license = "<license>"
authors = [{ name = "<developer-name>", email = "<developer-email>" }]
requires-python = ">=3.12"

[project.urls]
Homepage = "<organization-url>"

[build-system]
requires = ["uv_build>=0.12,<0.14"]
build-backend = "uv_build"

[tool.uv.build-backend]
module-name = ["apps", "config"]
module-root = ""
```

## `pyproject.toml` — tool configuration (appended)

```toml
[tool.ruff]
line-length = 88
target-version = "py312"

[tool.ruff.lint]
select = [
    "E", "F", "W", "I", "N", "UP", "B", "S", "C4", "SIM", "RUF", "D", "PT", "ASYNC",
    "DJ",
]
# Django and DRF declare configuration as class attributes (Meta.ordering, ModelAdmin.list_display,
# serializer Meta.fields): mutable by design (RUF012), and Meta needs no docstring (D106).
ignore = ["D106", "RUF012"]

[tool.ruff.lint.pydocstyle]
convention = "google"

[tool.ruff.lint.isort]
known-first-party = ["apps", "config", "tests"]

[tool.ruff.lint.per-file-ignores]
"tests/**" = ["S101", "D"]
"**/migrations/**" = ["D", "E501", "RUF012", "I"]

[tool.ruff.format]
exclude = ["**/migrations/**"]

[tool.mypy]
strict = true
plugins = ["mypy_django_plugin.main", "mypy_drf_plugin.main"]  # [api: drf]
plugins = ["mypy_django_plugin.main", "pydantic.mypy"]  # [api: ninja]
plugins = ["mypy_django_plugin.main"]  # [api: none]
exclude = ['(^|/)migrations/', '^build/', '^docs/']

# factory_boy ships partial annotations: allow its re-exports and untyped calls in tests.
[[tool.mypy.overrides]]
module = ["factory", "factory.*"]
implicit_reexport = true

# axe-playwright-python (UI tier) ships no type information.
[[tool.mypy.overrides]]
module = ["axe_playwright_python", "axe_playwright_python.*"]
ignore_missing_imports = true

[[tool.mypy.overrides]]
module = ["tests.*"]
disallow_untyped_calls = false

[tool.django-stubs]
django_settings_module = "config.settings.test"

[tool.pytest.ini_options]
DJANGO_SETTINGS_MODULE = "config.settings.test"
testpaths = ["tests"]
python_files = ["test_*.py"]
addopts = "-ra --strict-markers --strict-config"
markers = [
    "integration: needs Docker (testcontainers)",
    "ui: browser UI tests (Playwright + live server); run with -m ui",
]
filterwarnings = ["error::DeprecationWarning"]

[tool.coverage.run]
branch = true
source = ["apps", "config"]
omit = [
    "*/migrations/*",
    "config/asgi.py",
    "config/wsgi.py",
    "config/settings/*",
    "manage.py",
]

[tool.coverage.report]
fail_under = 80
show_missing = true

[tool.coverage.xml]
output = "build/coverage/coverage.xml"
```

## `config/__init__.py`

```python
"""Project package: settings, URL configuration and the WSGI/ASGI entry points.

``__version__`` is read from the installed distribution metadata, so
``pyproject.toml`` ``[project].version`` stays the single source of truth.
"""

from importlib.metadata import PackageNotFoundError, version

try:
    __version__ = version("<name>")
except PackageNotFoundError:  # pragma: no cover - project not installed
    __version__ = "0.0.0"
```

## `config/env.py`

```python
"""Typed access to environment variables for the settings modules.

Every value that differs between environments (secrets, hosts, database
credentials) is read through these helpers, so a missing variable fails fast
with ``ImproperlyConfigured`` instead of silently falling back to an insecure
default.
"""

import os
from pathlib import Path
from typing import Any

from django.core.exceptions import ImproperlyConfigured

_TRUE = frozenset({"1", "true", "yes", "on"})
_FALSE = frozenset({"0", "false", "no", "off"})


def env(name: str, default: str | None = None) -> str:
    """Return an environment variable, failing fast when it is required.

    Args:
        name: Variable name.
        default: Value used when the variable is unset; ``None`` makes it required.

    Returns:
        The variable's value, or ``default``.

    Raises:
        ImproperlyConfigured: The variable is unset and has no default.
    """
    value = os.environ.get(name, default)
    if value is None:
        msg = f"Set the {name} environment variable."
        raise ImproperlyConfigured(msg)
    return value


def env_bool(name: str, *, default: bool) -> bool:
    """Return a boolean environment variable (``1``/``true``/``yes``/``on`` or not).

    Args:
        name: Variable name.
        default: Value used when the variable is unset or empty.

    Returns:
        The parsed boolean.

    Raises:
        ImproperlyConfigured: The value is not a recognised boolean.
    """
    raw = os.environ.get(name, "").strip().lower()
    if not raw:
        return default
    if raw in _TRUE:
        return True
    if raw in _FALSE:
        return False
    msg = f"{name} must be a boolean (1/0, true/false, yes/no, on/off), got {raw!r}."
    raise ImproperlyConfigured(msg)


def env_int(name: str, *, default: int) -> int:
    """Return an integer environment variable.

    Args:
        name: Variable name.
        default: Value used when the variable is unset or empty.

    Returns:
        The parsed integer.

    Raises:
        ImproperlyConfigured: The value is not an integer.
    """
    raw = os.environ.get(name, "").strip()
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError as exc:
        msg = f"{name} must be an integer, got {raw!r}."
        raise ImproperlyConfigured(msg) from exc


def env_list(name: str, default: str | None = None) -> list[str]:
    """Return a comma-separated environment variable as a list of trimmed items.

    Args:
        name: Variable name.
        default: Comma-separated value used when the variable is unset; ``None``
            makes it required.

    Returns:
        The non-empty items.
    """
    return [item.strip() for item in env(name, default).split(",") if item.strip()]


def postgresql_database() -> dict[str, Any]:
    """Build the PostgreSQL ``DATABASES`` entry from ``POSTGRES_*`` variables.

    Returns:
        A ``DATABASES["default"]`` dictionary for psycopg 3.

    Raises:
        ImproperlyConfigured: ``POSTGRES_HOST`` or ``POSTGRES_PASSWORD`` is unset.
    """
    return {
        "ENGINE": "django.db.backends.postgresql",
        "HOST": env("POSTGRES_HOST"),
        "PORT": env("POSTGRES_PORT", "5432"),
        "NAME": env("POSTGRES_DB", "<package>"),
        "USER": env("POSTGRES_USER", "<package>"),
        "PASSWORD": env("POSTGRES_PASSWORD"),
        "CONN_MAX_AGE": env_int("DJANGO_CONN_MAX_AGE", default=0),
        "CONN_HEALTH_CHECKS": True,
    }


def sqlite_database(default_path: Path) -> dict[str, Any]:
    """Build a SQLite ``DATABASES`` entry (``DJANGO_SQLITE_PATH`` overrides the file).

    Args:
        default_path: Database file used when ``DJANGO_SQLITE_PATH`` is unset.

    Returns:
        A ``DATABASES["default"]`` dictionary.
    """
    return {
        "ENGINE": "django.db.backends.sqlite3",
        "NAME": env("DJANGO_SQLITE_PATH", str(default_path)),
        "OPTIONS": {"timeout": 20, "transaction_mode": "IMMEDIATE"},
    }


def default_database(sqlite_path: Path) -> dict[str, Any]:
    """Use PostgreSQL when ``POSTGRES_HOST`` is set, otherwise a local SQLite file.

    Args:
        sqlite_path: SQLite file used when no PostgreSQL host is configured.

    Returns:
        A ``DATABASES["default"]`` dictionary.
    """
    if os.environ.get("POSTGRES_HOST"):
        return postgresql_database()
    return sqlite_database(sqlite_path)
```

## `config/urls.py`

```python
"""Root URL configuration: admin, health probes and the versioned API."""

from django.conf import settings
from django.contrib import admin
from django.urls import include, path  # [api: drf]
from django.urls import path  # [api: ninja]
from django.urls import path  # [api: none]
from drf_spectacular.views import (  # [api: drf]
    SpectacularAPIView,  # [api: drf]
    SpectacularSwaggerView,  # [api: drf]
)  # [api: drf]

from apps.core.views import healthz, readyz
from config.api import api  # [api: ninja]

urlpatterns = [
    path(settings.ADMIN_URL, admin.site.urls),
    path("healthz", healthz, name="healthz"),
    path("readyz", readyz, name="readyz"),
    # [api: drf]
    path("api/v1/", include("apps.catalog.api.urls")),
    path(
        "api/v1/schema/",
        SpectacularAPIView.as_view(api_version="v1"),
        name="schema",
    ),
    path(
        "api/v1/docs/",
        SpectacularSwaggerView.as_view(url_name="schema"),
        name="swagger-ui",
    ),
    # [end]
    path("api/v1/", api.urls),  # [api: ninja]
]
```

## `config/asgi.py`

```python
"""ASGI entry point (``config.asgi:application``).

Defaults to the production settings so a server started without
``DJANGO_SETTINGS_MODULE`` fails safe.
"""

import os

from django.core.asgi import get_asgi_application

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings.production")

application = get_asgi_application()
```

## `config/wsgi.py`

```python
"""WSGI entry point (``config.wsgi:application``).

Defaults to the production settings so a server started without
``DJANGO_SETTINGS_MODULE`` fails safe.
"""

import os

from django.core.wsgi import get_wsgi_application

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings.production")

application = get_wsgi_application()
```

## `manage.py`

```python
#!/usr/bin/env python
"""Django's command-line utility for administrative tasks."""

import os
import sys


def main() -> None:
    """Run administrative tasks (defaults to the local settings)."""
    os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings.local")
    try:
        from django.core.management import execute_from_command_line
    except ImportError as exc:
        msg = (
            "Couldn't import Django. Run commands through `uv run` (or activate "
            "the project's virtual environment)."
        )
        raise ImportError(msg) from exc
    execute_from_command_line(sys.argv)


if __name__ == "__main__":
    main()
```

## `gunicorn.conf.py`

```python
"""Gunicorn configuration (read automatically from the working directory).

No secrets here: everything environment-specific comes from variables.
"""

import os

wsgi_app = "config.wsgi:application"  # [api: drf]
worker_class = "gthread"  # [api: drf]
threads = int(os.environ.get("GUNICORN_THREADS", "2"))  # [api: drf]
wsgi_app = "config.wsgi:application"  # [api: none]
worker_class = "gthread"  # [api: none]
threads = int(os.environ.get("GUNICORN_THREADS", "2"))  # [api: none]
wsgi_app = "config.asgi:application"  # [api: ninja]
worker_class = "uvicorn_worker.UvicornWorker"  # [api: ninja]
bind = os.environ.get("GUNICORN_BIND", "0.0.0.0:8000")
# Containers: a few workers each, scale with replicas (never cpu_count() here).
workers = int(os.environ.get("WEB_CONCURRENCY", "2"))
timeout = 30
graceful_timeout = 25
max_requests = 1000
max_requests_jitter = 100
forwarded_allow_ips = os.environ.get("FORWARDED_ALLOW_IPS", "127.0.0.1,::1")
control_socket_disable = True
accesslog = "-"
errorlog = "-"
```

## `apps/__init__.py`

```python
"""Django apps of this project (``apps.<name>``, label ``<name>``)."""
```

## `apps/core/__init__.py`

```python
"""Shared building blocks: abstract models and health probes; imports no other app."""
```

## `apps/core/apps.py`

```python
"""App configuration for ``apps.core``."""

from django.apps import AppConfig


class CoreConfig(AppConfig):
    """Shared helpers used by every other app."""

    name = "apps.core"
    label = "core"
    verbose_name = "Core"
```

## `apps/core/models.py`

```python
"""Abstract base models shared by the other apps."""

from django.db import models


class TimeStampedModel(models.Model):
    """Adds ``created_at``/``updated_at`` columns maintained by Django."""

    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        abstract = True
```

## `apps/core/views.py`

```python
"""Health probes for orchestrators and load balancers.

``/healthz`` (liveness) touches no dependency; ``/readyz`` (readiness) checks
the database and answers 503 while it is unreachable.
"""

import logging

from django.db import DatabaseError, connection
from django.http import HttpRequest, JsonResponse
from django.views.decorators.cache import never_cache
from django.views.decorators.http import require_GET

from config import __version__

logger = logging.getLogger(__name__)


@never_cache
@require_GET
def healthz(request: HttpRequest) -> JsonResponse:
    """Report that the process is alive.

    Args:
        request: The incoming request.

    Returns:
        ``{"status": "ok", "version": ...}`` with status 200.
    """
    return JsonResponse({"status": "ok", "version": __version__})


@never_cache
@require_GET
def readyz(request: HttpRequest) -> JsonResponse:
    """Report whether the service can take traffic (database reachable).

    Args:
        request: The incoming request.

    Returns:
        Status 200 when ``SELECT 1`` succeeds, 503 otherwise.
    """
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT 1")
    except DatabaseError:
        logger.exception("Readiness check failed: database unavailable")
        return JsonResponse({"status": "unavailable"}, status=503)
    return JsonResponse({"status": "ok"})
```

## `apps/accounts/__init__.py`

```python
"""User accounts: the project's custom user model."""
```

## `apps/accounts/apps.py`

```python
"""App configuration for ``apps.accounts``."""

from django.apps import AppConfig


class AccountsConfig(AppConfig):
    """Custom user model and authentication helpers."""

    name = "apps.accounts"
    label = "accounts"
    verbose_name = "Accounts"
```

## `apps/accounts/models.py`

```python
"""The custom user model (``AUTH_USER_MODEL = "accounts.User"``)."""

from django.contrib.auth.models import AbstractUser


class User(AbstractUser):
    """Project user.

    Defined before the first migration so fields can be added later without a
    painful swap. Refer to it through ``settings.AUTH_USER_MODEL`` in foreign
    keys and ``get_user_model()`` at run time, never by importing it from
    ``django.contrib.auth``.
    """

    def __str__(self) -> str:
        """Return the username."""
        return self.username
```

## `apps/accounts/admin.py`

```python
"""Admin registration for the custom user model."""

from django.contrib import admin
from django.contrib.auth.admin import UserAdmin as DjangoUserAdmin

from apps.accounts.models import User


@admin.register(User)
class UserAdmin(DjangoUserAdmin[User]):
    """Django's stock user admin, bound to the custom model."""
```
