# Django development reference

Code agreements for implementing a task in a Django project — this catalog's Python agreements restated for Django,
plus one file per Django concern (models and the ORM, views/URLs/templates, forms, DRF, Ninja, admin, settings,
background work, auth, security). These files exist so `iru-django-code-one-task` can load **only** what the task in
front of it actually needs, instead of carrying every rule in context on every task.

## Read this much, and no more

Detect the project's shape first (`iru-django-code-one-task` Step 1 — runner prefix, Django version, settings
modules, app layout, API style, test runner, fast/integration split), then read:

| Read | When |
|---|---|
| `code-style.md` | **Always.** Python version floor, Ruff/Black, typing with django-stubs (the annotations that type-check and the ones that don't), docstrings, errors, logging, layering (view → service → model/QuerySet). |
| `testing.md` | **Always** — writing the tests is part of the task. pytest-django vs. `manage.py test`, `django_db`, factory_boy/model_bakery, query-count assertions, `on_commit` capture, the fast/integration split, what to run. |
| `models-and-orm.md` | The task adds/changes a model, field, relation, constraint, index, manager/QuerySet, migration, query, transaction, signal, or service function. |
| `views-urls-templates.md` | The task adds/changes a view (FBV, CBV, async), URL pattern, template, template tag/filter, middleware, or htmx fragment. |
| `ui-contract.md` + `ui-implementation.md` | The task adds or changes a screen, component, list or other UI. The catalog-wide contract (decompose first, composition, inputs/events, paginated collections, UI tests, the `.ui-screenshots/` matrix, accessibility via `.ui-quality.yml`), then its Django mapping: page templates, inclusion tags and 6.0 partials, HTMX fragments, one item partial per row, pytest-playwright + `live_server` UI tests, the `tests/ui/ui_screenshots.py` helper, axe-playwright-python. |
| `forms-and-validation.md` | The task adds/changes a `Form`, `ModelForm`, formset, validator, or model `clean()`. |
| `drf.md` | API style is Django REST Framework and the task touches a serializer, view/viewset, router, permission, throttle, pagination, filter, or the OpenAPI schema. |
| `ninja.md` | API style is Django Ninja and the task touches an API, router, operation, or schema. |
| `admin.md` | The task touches `admin.py`, a `ModelAdmin`, an inline, an admin action, or an admin template. |
| `settings.md` | The task reads or adds a setting or environment variable, touches the settings modules, logging, caches, storages, email (`MAILERS`), or adds a system check. |
| `tasks-and-celery.md` | The task enqueues background work, adds a `django.tasks` or Celery task, or adds a management command. |
| `auth.md` | The task touches the user model, login/logout/sign-up/password flows, password hashing, permissions, groups, or object-level access. |
| `security.md` | The task touches request input, rendered output, raw SQL, uploads, redirects, CORS/CSP/CSRF, outgoing email or HTTP, or secrets — **always** for a new view or API endpoint. |

A task that only changes logic inside one existing service function needs `code-style.md`, `testing.md`, and
`models-and-orm.md`, and nothing else. Don't read the rest speculatively.

## The three rules that override everything else

1. **Match the surrounding code.** Every rule here is the default for *new* code. If the app you're editing
   consistently does something else — `TestCase` classes instead of pytest functions, fat models instead of
   `services.py`, `APIView` instead of viewsets, a single `settings.py`, no type annotations — follow it and note the
   divergence in the report rather than converting the app as a side effect of an unrelated task. The exceptions are
   rules whose violation is a security hole or data loss: `fields = "__all__"` on user input, unscoped querysets over
   private rows, `csrf_exempt` on a browser view, `mark_safe` on user data, f-string SQL, side effects outside
   `on_commit`, an edited applied migration. Don't copy those; report the neighbour instead.
2. **A task implements what the task says.** These references tell you *how* to write what was asked for. They
   never license adding a service layer to an app that has none, a repository layer over the ORM, a new package, a
   settings restructure, Celery, or a "while I'm here" refactor the plan didn't ask for.
3. **This skill doesn't validate the group.** It writes tests and runs only the fast test files it touched; it never
   runs coverage, mypy, the Django system checks, the integration tier, license headers, a docstring audit, or the
   full suite — the calling `iru-django-code-one-task-group` does all of that once for the whole task group. Write
   code that will pass those gates; don't run them here.

## Sources

Compiled from the following; the author's own documentation wins where it is more specific (Django 6.1, DRF 3.18,
Ninja 1.7, pytest-django 4.14, factory_boy 3.3, `django.tasks` vs. Celery 5.6). Where those pages are silent — they
prescribe no linter, type checker, or stubs — this catalog's Python choices apply (Ruff, mypy strict with
django-stubs). Every snippet marked "verified" was exercised in a throwaway uv project (Python 3.13, Django 6.1.2,
DRF 3.18.3, drf-spectacular 0.30.0, django-ninja 1.7.1, pytest 9.1.1, pytest-django 4.14.0, factory-boy 3.3.3,
testcontainers 4.15.0, Ruff 0.17.0, mypy 2.4.0 with django-stubs 6.1.2 and djangorestframework-stubs 3.18.1):
`ruff check`, `ruff format --check`, `mypy` (strict), the fast pytest tier with coverage, a testcontainers
PostgreSQL integration test, `manage.py check`, `makemigrations --check --dry-run`, and
`spectacular --validate --fail-on-warn` all passed.

- irurueta docs: `web/django/index.adoc`, `introduction-and-architecture.adoc`, `getting-started.adoc`,
  `project-structure-and-apps.adoc`, `settings-and-configuration.adoc`, `best-practices.adoc`, `testing.adoc`,
  `pytest-factories-and-browser-tests.adoc`, `django-rest-framework.adoc`, `django-ninja.adoc`,
  `celery-integration.adoc`, `tasks-framework.adoc`, `async-support.adoc`, `migrations.adoc`,
  `postgresql-and-database-backends.adoc`, `security.adoc`, `logging-and-error-reporting.adoc`,
  `custom-user-model-and-backends.adoc`, `performance-and-optimization.adoc`, `whats-changed-and-upgrading.adoc`
- irurueta docs: `web/django/admin-customization.adoc`, `admin-site.adoc`, `aggregation-expressions-and-functions.adoc`,
  `authentication.adoc`, `caching.adoc`, `class-based-views.adoc`, `constraints-indexes-and-composite-keys.adoc`,
  `content-security-policy.adoc`, `custom-template-tags-and-filters.adoc`, `email.adoc`,
  `file-uploads-and-storage.adoc`, `fixtures-serialization-and-initial-data.adoc`, `form-rendering-and-widgets.adoc`,
  `form-validation-and-validators.adoc`, `forms.adoc`, `function-based-views-and-shortcuts.adoc`,
  `generic-views.adoc`, `internationalization-and-translation.adoc`, `localization-and-time-zones.adoc`
- irurueta docs: `web/django/making-queries.adoc`, `management-commands-and-shell.adoc`,
  `managers-and-custom-querysets.adoc`, `middleware.adoc`, `model-forms-and-formsets.adoc`,
  `model-inheritance-and-meta-api.adoc`, `models-and-fields.adoc`, `pagination.adoc`, `passwords.adoc`,
  `permissions-and-authorization.adoc`, `query-optimization-and-fetch-modes.adoc`, `relationships.adoc`,
  `request-response-cycle.adoc`, `responses-streaming-csv-and-pdf.adoc`, `sessions-cookies-messages-and-signing.adoc`,
  `signals.adoc`, `static-files-and-front-end-assets.adoc`, `system-checks.adoc`, `template-partials-and-htmx.adoc`,
  `templates.adoc`, `transactions-raw-sql-and-multiple-databases.adoc`, `urls-and-routing.adoc`
- irurueta docs: `programming-languages/python/type-hints.adoc`, `docstrings.adoc`, `testing.adoc`,
  `exceptions.adoc`, `functions.adoc`, `standard-library-tour.adoc`, `lexical-structure-and-style.adoc`,
  `debugging-and-tooling.adoc`
- Django documentation: <https://docs.djangoproject.com/en/6.1/>
- UI tests: pytest-playwright <https://playwright.dev/python/docs/test-runners>, axe-playwright-python
  <https://github.com/pamelafox/axe-playwright-python> (`ui-implementation.md` lists the versions it was verified with)
- Django REST Framework: <https://www.django-rest-framework.org/>; drf-spectacular: <https://drf-spectacular.readthedocs.io/>
- Django Ninja: <https://django-ninja.dev/>
- pytest-django: <https://pytest-django.readthedocs.io/>; factory_boy: <https://factoryboy.readthedocs.io/>
- django-stubs: <https://github.com/typeddjango/django-stubs>; djangorestframework-stubs:
  <https://github.com/typeddjango/djangorestframework-stubs>
- Ruff rules (`DJ`, `S`, `D`): <https://docs.astral.sh/ruff/rules/>
- testcontainers-python: <https://testcontainers-python.readthedocs.io/>
- OWASP Top 10: <https://owasp.org/Top10/>
