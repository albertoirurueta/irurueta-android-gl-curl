# FastAPI development reference

Code agreements for implementing a task in a FastAPI service — this catalog's Python agreements condensed and
restated for FastAPI, plus one file per FastAPI concern (routers and dependencies, request/response models, data
and migrations, settings and lifespan, async rules, security, streaming/uploads/WebSockets, the optional
ports-and-adapters layout, tests). These files exist so `iru-fastapi-code-one-task` can load **only** what the task
in front of it actually needs, instead of carrying every rule in context on every task.

## Read this much, and no more

Detect the project's shape first (`iru-fastapi-code-one-task` Step 1 — runner prefix, layout, persistence stack,
sync vs. async, test client style, Python/FastAPI/Pydantic versions), then read:

| Read | When |
|---|---|
| `code-style.md` | **Always.** Ruff/mypy-clean typed Python, `X \| None`, `Annotated`, Google docstrings, errors, logging, no `print` — plus the FastAPI layering (web → service → data) and where code goes in each layout. |
| `testing.md` | **Always** — writing the tests is part of implementing the task. pytest, `TestClient` vs. `httpx.AsyncClient` + `ASGITransport` + `@pytest.mark.anyio`, `dependency_overrides`, SQLite `StaticPool` fast tier, `integration` marker + testcontainers tier, asserting `type`/`loc`. |
| `routers-and-dependencies.md` | The task adds or changes an endpoint, an `APIRouter`, a dependency (`Depends`, yield dependencies, `Annotated` aliases), a status code, an exception handler, or `include_router` wiring. |
| `validation-and-schemas.md` | The task adds or changes a Pydantic/SQLModel request or response model, a path/query/header/cookie/form parameter, pagination, or a PATCH. |
| `persistence.md` | The task touches a table model, a query, a session/transaction, a repository/unit of work, or an Alembic revision (read the shared rules plus the detected stack's section only). |
| `settings-and-lifespan.md` | The task reads or adds a setting/environment variable, a process-wide client/pool/model (lifespan), or a `BackgroundTasks` job. |
| `async-and-concurrency.md` | The task writes an `async def` that does I/O, calls a blocking library, or runs work concurrently — or any time you're unsure whether a function should be `def` or `async def`. |
| `security.md` | The task touches authN/authZ (OAuth2/JWT, API keys, scopes, ownership), passwords, CORS, proxies, secrets — or adds an endpoint that shouldn't be public. |
| `streaming-files-and-websockets.md` | The task adds file uploads/downloads, `StreamingResponse`, JSON Lines, SSE, or a WebSocket endpoint. |
| `hexagonal.md` | Only when Step 1 found ports and adapters (`Protocol` ports, adapters, a unit of work). |

A task that only changes logic inside one existing service function needs `code-style.md` plus `testing.md`, and
nothing else. Don't read the rest speculatively.

## The three rules that override everything else

1. **Match the surrounding code.** Every rule here is the default for *new* code. If the module or package you're
   editing consistently does something else — a sync `Session` stack, `TestClient` instead of `AsyncClient`,
   the Full Stack template's `app/api/routes/` layout, Poetry, absolute instead of relative imports — follow it and
   note the divergence in the report rather than converting it as a side effect of an unrelated task. The
   exception is a rule whose violation is a silent defect, which you don't copy even when a neighbour does: a
   table/`...InDB` model used as a response model (leaks columns), blocking I/O inside `async def` (stalls every
   request), a sync session in an async path, `raise HTTPException` from a service or a validator, `@app.on_event`,
   a client/engine/pool created at import time, `create_all()` where Alembic owns the schema, editing an applied
   Alembic revision, a JWT decoded without an `algorithms=` allow-list, `allow_origins=["*"]` with credentials.
2. **A task implements what the task says.** These references tell you *how* to write what was asked for. They
   never license adding a service layer, a repository/unit of work, a new ORM, an auth scheme, a middleware, or a
   "while I'm here" refactor the plan didn't ask for.
3. **This skill doesn't validate the group.** It writes tests and runs only the fast test files it touched; it
   never runs integration tests, coverage, Ruff/mypy gates, license headers, a docstring audit, Alembic checks
   against a database, or the full suite — the calling `iru-fastapi-code-one-task-group` does all of that once for
   the whole task group. Write code that will pass those gates; don't run them here.

## Sources

Compiled from the following, with this catalog's stated preferences (Ruff, `mypy --strict`, Google docstrings, an
80% coverage gate) filling the gaps the docs leave open. Snippets were exercised in a throwaway uv project
(Python 3.13, FastAPI 0.142.x, Pydantic 2.14, SQLModel 0.0.48 on SQLAlchemy 2.1, Starlette 1.8, pytest 9.1,
testcontainers 4.15, PostgreSQL 18) against `ruff check`, `ruff format --check`, `mypy --strict`, pytest (fast and
`integration` tiers), coverage, and `alembic check`.

- irurueta docs: `backend/fastapi/index.adoc`, `introduction-and-architecture.adoc`, `getting-started.adoc`,
  `python-types-and-annotated.adoc`, `project-structure-and-layered-architecture.adoc`,
  `pydantic-v2-essentials.adoc`, `async-and-concurrency.adoc`, `dependency-injection.adoc`,
  `dependencies-with-yield.adoc`, `settings-lifespan-and-background-tasks.adoc`, `sql-databases-with-sqlmodel.adoc`,
  `nosql-and-other-data-stores.adoc`, `testing.adoc`, `property-based-and-load-testing.adoc`,
  `performance-and-observability.adoc`, `whats-changed-and-migration.adoc`
- irurueta docs: `backend/fastapi/path-parameters.adoc`, `query-parameters.adoc`, `request-body-and-models.adoc`,
  `custom-responses.adoc`, `forms-and-file-uploads.adoc`, `headers-and-cookies.adoc`,
  `request-object-and-content-type.adoc`, `security-fundamentals.adoc`, `oauth2-password-and-jwt.adoc`,
  `oauth2-scopes-and-authorization.adoc`, `middleware-cors-and-proxies.adoc`, `openapi-and-interactive-docs.adoc`,
  `response-models-and-return-types.adoc`, `status-codes-and-error-handling.adoc`,
  `streaming-json-lines-and-sse.adoc`, `websockets.adoc`, `sub-applications-wsgi-and-graphql.adoc`
- irurueta docs: `programming-languages/python/` (`lexical-structure-and-style.adoc`, `type-hints.adoc`,
  `exceptions.adoc`, `docstrings.adoc`, `testing.adoc`, `concurrency-and-async.adoc`,
  `standard-library-tour.adoc`)
- irurueta docs: `backend/flask/flask-sqlalchemy.adoc`, `backend/flask/database-migrations.adoc`,
  `backend/pyramid/sqlalchemy-transactions-and-migrations.adoc`, `backend/tornado/databases-and-async-drivers.adoc`
  (framework-independent SQLAlchemy 2.0 and Alembic guidance)
- FastAPI documentation: <https://fastapi.tiangolo.com/>; Pydantic: <https://docs.pydantic.dev/latest/>;
  SQLModel: <https://sqlmodel.tiangolo.com/>; SQLAlchemy 2.0/2.1: <https://docs.sqlalchemy.org/>;
  Alembic: <https://alembic.sqlalchemy.org/>; testcontainers-python: <https://testcontainers-python.readthedocs.io/>;
  Ruff rules (`FAST`, `ASYNC`, `S`): <https://docs.astral.sh/ruff/rules/>
