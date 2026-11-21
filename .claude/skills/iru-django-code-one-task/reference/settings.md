# Settings, configuration, logging, and system checks

Read this when the task reads or adds a setting or environment variable, touches the settings modules, logging,
caches, storages, email, or adds a system check.

## Find where a setting belongs

Step 1 found the layout. The common shapes:

| Layout | Where a new value goes |
|---|---|
| Split package `config/settings/{base,local,test,production}.py` (`dev`/`prod` on some projects) | Shared default in `base.py`; per-environment overrides in the thin modules; anything secret or host-specific read from the environment in `production.py`. |
| One env-driven `settings.py` (twelve-factor; django-environ or a small `env()` helper) | One line reading the environment variable with a safe default — or no default when it's a secret. |
| cookiecutter-django (`config/settings/*` + `.envs/`) | Same as the split package; follow its `env(...)` calls. |

`manage.py` defaults to the local/dev module, `wsgi.py`/`asgi.py` to production (fail safe); tests run on the test
module. Don't restructure any of this as part of a task.

## Rules

- **Secrets and host values come from the environment, never from code**: `SECRET_KEY`, database passwords, API keys,
  `ALLOWED_HOSTS`, `CSRF_TRUSTED_ORIGINS` (with scheme), broker/cache URLs. In production modules they **fail fast**
  (`os.environ["DJANGO_SECRET_KEY"]` → `KeyError`, or `env("DJANGO_SECRET_KEY")` → `ImproperlyConfigured`) — no
  default. Local/test modules may default (`get_random_secret_key()`), never the production one.
- Use the project's helper consistently: django-environ (`env.bool(...)`, `env.list(...)`, `env.db("DATABASE_URL")`) or
  plain `os.environ` — remember plain `os.environ.get("X") == "False"` is a truthy string; parse booleans explicitly
  (`os.environ.get("DJANGO_DEBUG", "0") == "1"`). `DEBUG` defaults to False everywhere but local.
- **Every new environment variable gets an `.env.example` entry** with a placeholder (never a real value), and whatever
  else the project documents variables in (README/Antora page, compose file). `.env` stays git-ignored.
- Your own settings: `UPPER_CASE`, a default in code read **at call time** (`getattr(settings, "CATALOG_PAGE_SIZE",
  20)` inside the function, never a module-level constant captured at import), validated by a registered system check
  when misconfiguration would only show up later.
- `STORAGES` replaces the defaults — always restate both `default` and `staticfiles`; `DEFAULT_FILE_STORAGE`/
  `STATICFILES_STORAGE` are removed (silently ignored).
- Email on 6.1 is `MAILERS` (aliases like `CACHES`); `EMAIL_*` is deprecated — don't add new `EMAIL_*` settings to a
  project already on `MAILERS`. Production SMTP always sets a `timeout`.
- Caches: `django.core.cache.backends.redis.RedisCache` from `REDIS_URL` in production with a `KEY_PREFIX`; locmem in
  local/test. The cache is never storage — any key can vanish.
- PostgreSQL connections: `CONN_MAX_AGE` + `CONN_HEALTH_CHECKS` under WSGI; `OPTIONS["pool"]` with `CONN_MAX_AGE = 0`
  under ASGI (combining both raises `ImproperlyConfigured`).
- Leave `DEFAULT_AUTO_FIELD`, `AUTH_USER_MODEL`, and `SECRET_KEY_FALLBACKS` alone unless the task is about them.
- `SILENCED_SYSTEM_CHECKS` only for an inspected, accepted warning, with a comment saying why — never an error.
- `INSTALLED_APPS` order: Django contrib → third-party → own apps from most basic to most dependent (`daphne` first
  when present). Middleware order is in `views-urls-templates.md`.
- Star imports only in the thin settings modules (`from .base import *  # noqa: F403`); never mutate a list imported
  that way in place (`MIDDLEWARE.insert(...)` changes `base` too) — rebuild it (`MIDDLEWARE = [*MIDDLEWARE, "x"]`).

## Logging

One `LOGGING` dict with `"version": 1, "disable_existing_loggers": False`, console handlers to stdout, level from
`DJANGO_LOG_LEVEL`. Application code just does `logger = logging.getLogger(__name__)` — a task normally adds loggers,
not handlers. Sensitive values never reach logs: `@sensitive_variables` / `@sensitive_post_parameters` on views
handling passwords or card data.

## System checks

A project-specific invariant that should fail `manage.py check` (a required setting, a consistent pair of settings):

```python
# apps/catalog/checks.py — imported from CatalogConfig.ready()
@register("catalog")
def check_page_size(app_configs: Sequence[AppConfig] | None, **kwargs: Any) -> list[CheckMessage]:
    """Validate ``CATALOG_PAGE_SIZE``."""
    size = getattr(settings, "CATALOG_PAGE_SIZE", 20)
    if not isinstance(size, int) or size < 1:
        return [Error("CATALOG_PAGE_SIZE must be a positive integer.", id="catalog.E001")]
    return []
```

`@register(..., deploy=True)` for production-only checks (run by `check --deploy`); `Tags.database` for checks that
open connections. Test them by asserting the returned list under `override_settings` (messages compare by equality).

## Tests for this area

Settings changes through `settings`/`override_settings` (never `DATABASES`/`CACHES` — those come from the test
settings module). Production-only behavior (SSL redirect, HSTS, allowed hosts) is tested with
`override_settings(SECURE_SSL_REDIRECT=True, ...)` and assertions on the response. The group runs `manage.py check`;
a task that adds production-sensitive settings should leave `check --deploy` (production settings, dummy env) clean.
