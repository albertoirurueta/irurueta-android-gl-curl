# Settings package: `config/settings/{base,local,test,production}.py`

Replaces the generated `config/settings.py`. `manage.py` defaults to `local`, `wsgi.py`/`asgi.py` to `production` (fail safe), pytest and mypy's django-stubs plugin to `test`. Every secret or host-specific value in `production` comes from the environment through `config/env.py` and has no default.

Templates use this skill's tag convention (see SKILL.md, "Template conventions"): a line ending in `# [api: drf]` / `# [database: postgresql]` is kept only for that choice (drop the tag), a line that is only `# [api: ninja]` opens a block kept only for that choice and `# [end]` closes it, and `<placeholders>` are replaced with Step 2's values.

## `config/settings/__init__.py`

```python
"""Settings package: ``base`` plus one module per environment.

``manage.py`` defaults to ``config.settings.local``; ``wsgi.py``/``asgi.py``
default to ``config.settings.production`` (fail safe); pytest uses
``config.settings.test``.
"""
```

## `config/settings/base.py`

```python
"""Settings shared by every environment.

The environment modules (``local``, ``test``, ``production``) star-import this
one and override only what differs. Secrets and host-specific values never
live here.
"""

import os
from pathlib import Path
from typing import Any

import django_stubs_ext

from config import __version__

# Lets generic Django classes be subscripted at run time (ModelAdmin[Book], ...).
django_stubs_ext.monkeypatch()

BASE_DIR = Path(__file__).resolve().parent.parent.parent

APP_VERSION = __version__

DJANGO_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
]
THIRD_PARTY_APPS = ["rest_framework", "drf_spectacular"]  # [api: drf]
THIRD_PARTY_APPS = ["ninja"]  # [api: ninja]
THIRD_PARTY_APPS: list[str] = []  # [api: none]
LOCAL_APPS = [
    "apps.core",
    "apps.accounts",
    "apps.catalog",
]
INSTALLED_APPS = DJANGO_APPS + THIRD_PARTY_APPS + LOCAL_APPS

MIDDLEWARE = [
    "django.middleware.security.SecurityMiddleware",
    "whitenoise.middleware.WhiteNoiseMiddleware",
    "django.contrib.sessions.middleware.SessionMiddleware",
    # Request language (django_language cookie / Accept-Language); LANGUAGE_BIDI
    # then drives <html dir> - the UI tests render right-to-left through it.
    "django.middleware.locale.LocaleMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
    "django.middleware.clickjacking.XFrameOptionsMiddleware",
]

ROOT_URLCONF = "config.urls"
WSGI_APPLICATION = "config.wsgi.application"
ASGI_APPLICATION = "config.asgi.application"

TEMPLATES = [
    {
        "BACKEND": "django.template.backends.django.DjangoTemplates",
        "DIRS": [BASE_DIR / "templates"],
        "APP_DIRS": True,
        "OPTIONS": {
            "context_processors": [
                "django.template.context_processors.request",
                "django.contrib.auth.context_processors.auth",
                "django.contrib.messages.context_processors.messages",
            ],
        },
    },
]

AUTH_USER_MODEL = "accounts.User"

AUTH_PASSWORD_VALIDATORS = [
    {
        "NAME": "django.contrib.auth.password_validation."
        "UserAttributeSimilarityValidator"
    },
    {"NAME": "django.contrib.auth.password_validation.MinimumLengthValidator"},
    {"NAME": "django.contrib.auth.password_validation.CommonPasswordValidator"},
    {"NAME": "django.contrib.auth.password_validation.NumericPasswordValidator"},
]

LANGUAGE_CODE = "en-us"
TIME_ZONE = "UTC"
USE_I18N = True
USE_TZ = True

STATIC_URL = "static/"
STATIC_ROOT = BASE_DIR / "staticfiles"
STORAGES = {
    "default": {"BACKEND": "django.core.files.storage.FileSystemStorage"},
    "staticfiles": {"BACKEND": "django.contrib.staticfiles.storage.StaticFilesStorage"},
}

ADMIN_URL = os.environ.get("DJANGO_ADMIN_URL", "admin/")

MAILERS: dict[str, dict[str, Any]] = {
    "default": {"BACKEND": "django.core.mail.backends.console.EmailBackend"},
}

LOGGING = {
    "version": 1,
    "disable_existing_loggers": False,
    "formatters": {
        "plain": {
            "format": "{asctime} {levelname} {name}: {message}",
            "style": "{",
        },
    },
    "handlers": {
        "console": {"class": "logging.StreamHandler", "formatter": "plain"},
    },
    "root": {"handlers": ["console"], "level": "WARNING"},
    "loggers": {
        "django": {
            "handlers": ["console"],
            "level": os.environ.get("DJANGO_LOG_LEVEL", "INFO"),
            "propagate": False,
        },
        "apps": {
            "handlers": ["console"],
            "level": os.environ.get("DJANGO_LOG_LEVEL", "INFO"),
            "propagate": False,
        },
    },
}
# [api: drf]

REST_FRAMEWORK = {
    "DEFAULT_AUTHENTICATION_CLASSES": [
        "rest_framework.authentication.SessionAuthentication",
    ],
    "DEFAULT_PERMISSION_CLASSES": [
        "rest_framework.permissions.IsAuthenticatedOrReadOnly",
    ],
    "DEFAULT_THROTTLE_CLASSES": [
        "rest_framework.throttling.AnonRateThrottle",
        "rest_framework.throttling.UserRateThrottle",
    ],
    "DEFAULT_THROTTLE_RATES": {"anon": "100/hour", "user": "1000/hour"},
    "DEFAULT_PAGINATION_CLASS": "rest_framework.pagination.PageNumberPagination",
    "PAGE_SIZE": 20,
    "DEFAULT_SCHEMA_CLASS": "drf_spectacular.openapi.AutoSchema",
}

SPECTACULAR_SETTINGS = {
    "TITLE": "<name> API",
    "DESCRIPTION": "<description>",
    "VERSION": APP_VERSION,
    "SERVE_INCLUDE_SCHEMA": False,
}
# [end]
```

## `config/settings/local.py`

```python
"""Settings for a developer machine.

PostgreSQL when ``POSTGRES_HOST`` is set (e.g. ``docker compose up db``),
otherwise a local SQLite file. A random ``SECRET_KEY`` is generated per process
unless ``DJANGO_SECRET_KEY`` is set.
"""

import os

from django.core.management.utils import get_random_secret_key

from config.env import default_database  # [database: postgresql]
from config.env import sqlite_database  # [database: sqlite]

from .base import *  # noqa: F403
from .base import BASE_DIR

DEBUG = True
SECRET_KEY = os.environ.get("DJANGO_SECRET_KEY") or get_random_secret_key()
ALLOWED_HOSTS = ["localhost", "127.0.0.1", "[::1]"]

DATABASES = {"default": default_database(BASE_DIR / "db.sqlite3")}  # [database: postgresql]
DATABASES = {"default": sqlite_database(BASE_DIR / "db.sqlite3")}  # [database: sqlite]
```

## `config/settings/test.py`

```python
"""Settings for the test suite.

The fast tier runs on SQLite in memory; the ``integration`` tier swaps in a
PostgreSQL container from ``tests/conftest.py``. Nothing here is secret.
"""

from .base import *  # noqa: F403

DEBUG = False
SECRET_KEY = "insecure-key-for-the-test-suite-only"  # noqa: S105
ALLOWED_HOSTS = ["testserver", "localhost", "127.0.0.1"]

DATABASES = {
    "default": {"ENGINE": "django.db.backends.sqlite3", "NAME": ":memory:"},
}

PASSWORD_HASHERS = ["django.contrib.auth.hashers.MD5PasswordHasher"]

STORAGES = {
    "default": {"BACKEND": "django.core.files.storage.InMemoryStorage"},
    "staticfiles": {"BACKEND": "django.contrib.staticfiles.storage.StaticFilesStorage"},
}

# No collectstatic in tests: let WhiteNoise look up files lazily instead of warning.
WHITENOISE_AUTOREFRESH = True

CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}

MAILERS = {
    "default": {"BACKEND": "django.core.mail.backends.locmem.EmailBackend"},
}
```

## `config/settings/production.py`

```python
"""Settings for deployed environments: everything sensitive comes from the environment.

Required: ``DJANGO_SECRET_KEY``, ``DJANGO_ALLOWED_HOSTS`` and the database
variables (``POSTGRES_HOST``/``POSTGRES_PASSWORD``, optionally
``POSTGRES_PORT``/``POSTGRES_DB``/``POSTGRES_USER``). A missing one raises
``ImproperlyConfigured`` at start-up instead of running with an insecure default.
"""

from config.env import (
    env,
    env_bool,
    env_int,
    env_list,
    postgresql_database,  # [database: postgresql]
    sqlite_database,  # [database: sqlite]
)

from .base import *  # noqa: F403
from .base import BASE_DIR  # [database: sqlite]

DEBUG = False
SECRET_KEY = env("DJANGO_SECRET_KEY")
SECRET_KEY_FALLBACKS = env_list("DJANGO_SECRET_KEY_FALLBACKS", "")
ALLOWED_HOSTS = env_list("DJANGO_ALLOWED_HOSTS")
CSRF_TRUSTED_ORIGINS = env_list("DJANGO_CSRF_TRUSTED_ORIGINS", "")

DATABASES = {"default": postgresql_database()}  # [database: postgresql]
DATABASES = {"default": sqlite_database(BASE_DIR / "db.sqlite3")}  # [database: sqlite]

STORAGES = {
    "default": {"BACKEND": "django.core.files.storage.FileSystemStorage"},
    "staticfiles": {
        "BACKEND": "whitenoise.storage.CompressedManifestStaticFilesStorage"
    },
}

# HTTPS everywhere. DJANGO_HTTPS=0 only for plain-HTTP smoke runs (compose.yaml).
# Behind a TLS-terminating proxy that always sets (and strips) X-Forwarded-Proto,
# set DJANGO_SECURE_PROXY_SSL_HEADER=1; never enable it otherwise.
HTTPS = env_bool("DJANGO_HTTPS", default=True)
if env_bool("DJANGO_SECURE_PROXY_SSL_HEADER", default=False):
    SECURE_PROXY_SSL_HEADER = ("HTTP_X_FORWARDED_PROTO", "https")
SECURE_SSL_REDIRECT = HTTPS
SECURE_REDIRECT_EXEMPT = [r"^healthz$", r"^readyz$"]
SESSION_COOKIE_SECURE = HTTPS
CSRF_COOKIE_SECURE = HTTPS
SECURE_HSTS_SECONDS = env_int("DJANGO_SECURE_HSTS_SECONDS", default=31536000)
SECURE_HSTS_INCLUDE_SUBDOMAINS = env_bool(
    "DJANGO_SECURE_HSTS_INCLUDE_SUBDOMAINS", default=True
)
SECURE_HSTS_PRELOAD = env_bool("DJANGO_SECURE_HSTS_PRELOAD", default=True)

MAILERS = {
    "default": {
        "BACKEND": "django.core.mail.backends.smtp.EmailBackend",
        "OPTIONS": {
            "host": env("DJANGO_EMAIL_HOST", "localhost"),
            "port": env_int("DJANGO_EMAIL_PORT", default=587),
            "username": env("DJANGO_EMAIL_USER", ""),
            "password": env("DJANGO_EMAIL_PASSWORD", ""),
            "use_tls": env_bool("DJANGO_EMAIL_USE_TLS", default=True),
        },
    },
}
```
