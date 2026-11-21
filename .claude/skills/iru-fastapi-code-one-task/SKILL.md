---
name: iru-fastapi-code-one-task
description: "Invoked as /iru-fastapi-code-one-task <task description> (the task's text with its implementation_plan.md checkbox lines) via Skill. Implements one FastAPI (any layout, Alembic revisions included) plan task and its tests, compile-checks, ticks the checkbox with a group-validation-pending note; no baseline or full gates, which iru-fastapi-code-one-task-group runs."
model: sonnet
allowed-tools: Read Edit Write Bash(uv run *) Bash(poetry run *) Bash(pdm run *) Bash(pipenv run *) Bash(.venv/bin/*) Bash(python *) Bash(python3 *) Bash(command -v *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *)
---

# Implement one plan task (FastAPI)

Carry out a single task's implementation, tests, and checkbox update against a FastAPI service — nothing else.
This is the narrowest execution unit for a FastAPI project in the `iru-code` pipeline:
`iru-fastapi-code-one-task-group` calls it once per task in a bucket (potentially many at once, in parallel agents),
then handles license headers, docstrings, coverage, Ruff/mypy, the full fast suite, the Alembic checks, and the
container-backed `integration` tier itself, once for the whole bucket.

Two things shape this skill:

1. **FastAPI punishes some mistakes silently.** A table model used as the response model leaks columns; blocking
   I/O inside `async def` stalls every request; a sync session in an async path, an `HTTPException` raised from a
   validator, an `@app.on_event` hook, an engine created at import time, a forgotten `include_router` — each one
   imports fine, and several pass the tests a task would naturally write. The reference files exist mostly to
   prevent those.
2. **It runs the fast test files it touched, and only those.** FastAPI tests on in-memory SQLite run in
   milliseconds, so validating one task's own work is cheap; everything heavier — testcontainers-backed tests,
   coverage, the full suite, Alembic against PostgreSQL — belongs to the group.

It uses a medium model on purpose: the plan already carries the hard reasoning, and the guidance lives in
`reference/` rather than being re-derived per task.

## Step 1 — Detect the project's shape, then read only the reference material this task needs

Detect once, from files rather than assumptions (the same facts the group skill and the `iru-python-*` gates
detect, so a task never disagrees with its validator):

1. **FastAPI app root** — the `pyproject.toml` (or `requirements*.txt`) listing `fastapi` (the repository root, or a
   subdirectory such as `backend/` in a polyglot repository), and the app object: `[tool.fastapi] entrypoint` if
   declared, else `grep -rn "FastAPI(" --include=*.py` outside `tests/` and `.venv/`.
2. **Runner prefix** — first match wins: `uv.lock` → `uv run --frozen`; `poetry.lock` → `poetry run`; `pdm.lock` →
   `pdm run`; `Pipfile.lock` → `pipenv run`; `.venv/bin/python` → `.venv/bin/python -m`; else plain `python -m`
   (say so in Step 7). Confirm the tool exists (`command -v uv`). Never `pip install` anything, never relock.
3. **Versions** — `requires-python`; FastAPI and Pydantic from the lockfile/manifest. Pydantic v1 syntax in the
   codebase (or `pydantic<2`) is a blocker to report, not a style to continue (FastAPI 0.126+ removed v1 support).
   Note the FastAPI minor before using recent features (`fastapi.sse` 0.135, JSON Lines by `yield` 0.134,
   `Depends(scope=...)` 0.121, `app.frontend()` 0.138).
4. **Layout** — layered `app/` (`routers/`, `services/`, `models/`, `dependencies.py`, `settings.py`, `db.py`); the
   Full Stack template (`app/api/routes/`, `app/api/deps.py`, `app/core/`, `app/models.py`, `app/crud.py`); a
   `src/<pkg>/` package; or **ports and adapters** only if `reference/hexagonal.md`'s recognition criteria hold.
   Also the import style (absolute `app.…` vs. relative).
5. **Persistence** — SQLModel, plain SQLAlchemy 2.0 (`DeclarativeBase`/`Mapped`), legacy 1.x style, another store
   (PyMongo, Redis, Elasticsearch), or none; **Alembic** (`alembic.ini`, its `script_location`, how `env.py` gets
   its URL); **sync vs. async** (`create_async_engine`/`AsyncSession` vs. `create_engine`/`Session`, and whether
   path operations are `async def` or `def`).
6. **Tests** — `TestClient` vs. `httpx.AsyncClient` + `ASGITransport`; AnyIO marker vs. pytest-asyncio; the
   fixtures in `tests/conftest.py` (client, session, settings, auth); the `[tool.pytest.ini_options]` markers
   (an `integration` marker, `--strict-markers`, `filterwarnings`, `addopts` with `--cov`); testcontainers in the
   dev dependencies; where container-backed tests live (`tests/integration/`).
7. **Lint/type config** — Ruff `line-length`, `select`, `pydocstyle.convention`; mypy `strict`/plugins or pyright;
   or the older Black/flake8/isort tools.

Then read `reference/README.md` first — it is short and routes you to the rest — then read only what it points at
for this task:

| Read | When |
|---|---|
| `reference/code-style.md` | **Always.** |
| `reference/testing.md` | **Always** — writing the tests is part of the task. |
| `reference/routers-and-dependencies.md` | The task adds/changes an endpoint, router, dependency, status code, exception handler, or router wiring. |
| `reference/validation-and-schemas.md` | The task adds/changes a request/response model, a path/query/header/cookie/form parameter, pagination, or a PATCH. |
| `reference/persistence.md` | The task touches a table model, query, session/transaction, repository/unit of work, or Alembic revision (shared rules plus the detected stack's section only). |
| `reference/settings-and-lifespan.md` | The task reads/adds a setting, a lifespan-managed resource, or a background task. |
| `reference/async-and-concurrency.md` | The task writes `async def` code doing I/O, calls a blocking library, or runs work concurrently. |
| `reference/security.md` | The task touches authN/authZ, passwords/tokens/API keys, ownership, CORS/proxies, secrets — or adds an endpoint that shouldn't be public. |
| `reference/streaming-files-and-websockets.md` | The task adds uploads/downloads, streaming, JSON Lines, SSE, or a WebSocket. |
| `reference/hexagonal.md` | Only when item 4 detected ports and adapters. |

Don't read the optional files speculatively — for a change inside one existing service function,
`code-style.md` and `testing.md` are the whole list.

If `reference/` is missing (e.g. the skill directory was copied without it), proceed using this repository's own
`CLAUDE.md` conventions and the surrounding code's existing style, and say so in Step 7's report — don't guess at
what the reference would have said.

## Step 2 — Re-check the current code state

Read the actual current content of every file the task touches — plus `dependencies.py` (or `api/deps.py`) and the
app module (`main.py`) whenever the task adds a router, dependency, exception handler, middleware, or lifespan
resource, and `models/__init__.py` plus the latest Alembic revision whenever it changes a table. Don't trust any
"current code state" notes passed in with the task; other tasks in the same bucket may be landing concurrently.

Note what the surrounding code already does about the things `reference/` has opinions on — sync or async, where
errors become status codes, model family naming, `TestClient` or `AsyncClient`, absolute or relative imports. The
reference's rules are the default for new code; a module that consistently does something else wins, and the
divergence goes in Step 7's report — except for the silent-defect rules `reference/README.md` lists, which you
don't copy.

## Step 3 — Implement exactly what the task specifies

The named modules, the described router/dependency/service/model/revision, the behavior. Apply the reference files
from Step 1, plus this repository's own `CLAUDE.md` where it is more specific.

- **New router**: a module exporting `router = APIRouter(prefix=..., tags=[...])`, included where the project
  includes the others. The `include_router` line is part of the task.
- **Schema change with Alembic**: a **new** revision in the project's `script_location` — `alembic revision
  --autogenerate` only when a database already at head is reachable without starting anything, otherwise
  `alembic revision -m "<change>"` and hand-written operations from the model diff (`reference/persistence.md`).
  Review it, make `downgrade()` reverse `upgrade()`, and never edit an existing revision. Without Alembic, a schema
  change the project can't migrate is a blocker to report (Step 5).
- **Format the files you touched** with the project's tools when configured — `<runner> ruff format <files>` and
  `<runner> ruff check --fix <files>` (or Black/isort) — only those files, never the whole project.

Non-negotiable, because a later gate (or production) enforces them:

- **Types on everything new, `mypy --strict`-clean**, `Annotated` for every FastAPI marker, `X | None`, no
  `Optional`/`List`, no unexplained `# type: ignore`.
- **Docstrings** on every public module, class, and function in the project's convention (Google by default), with
  `Args:`/`Returns:`/`Raises:` — `Raises:` names every exception a caller can see.
- **No table/`...InDB` model as a response model, no blocking call in `async def`, no `HTTPException` from services
  or validators, no `@app.on_event`, no engine/client/settings at import time, no `create_all()` where Alembic owns
  the schema.**
- **Write models with `extra="forbid"`**; server-controlled fields (owner, role, ids) come from the authenticated
  user, never the body.
- **No new runtime or dev dependency, no `pyproject.toml` tool-config change, no lockfile change** unless the task
  explicitly calls for it — a blocker to report (Step 5), not a decision to make quietly (`sqlalchemy[asyncio]`,
  `httpx2`, `python-multipart` included).
- **No `print`, no `breakpoint()`, no secrets or real-looking credentials** anywhere, tests included.

Don't add anything the task didn't ask for — no speculative abstractions, no unrelated cleanup, no restyling of
code the task doesn't touch.

## Step 4 — Write the tests, and run only the fast test files you touched

Follow `reference/testing.md` and the existing tests next to the code:

- **Fast tests** for everything the task added or changed — through the client fixture for endpoints, plain pytest
  with fakes or a SQLite session for services — covering the new behavior, every `Raises:`, the 401/403/404/409/422
  paths of anything handling input, and the boundaries. Assert 422s by `type`/`loc`.
- **Integration tests** (`@pytest.mark.integration`, wherever the project keeps them) when the task's correctness
  depends on the real engine — PostgreSQL constraint behavior, JSONB, a new Alembic revision (the "models match
  migrations" check). **Write them; don't run them.**

Run the fast test files this task created or changed — nothing else — with the detected prefix:

```bash
uv run --frozen pytest tests/test_books.py tests/services/test_books.py -m "not integration" --no-cov
# poetry run pytest … / pdm run pytest … / pipenv run pytest … / .venv/bin/python -m pytest …
```

(`--no-cov` when pytest-cov is installed, so a `--cov` in `addopts` can't fail a scoped run on total coverage;
`-m "not integration"` only when that marker is declared.)

**Never run**: `-m integration`, anything under `tests/integration/`, a bare `pytest` without paths, `--cov`,
`alembic upgrade`/`downgrade`/`check` against any database, `docker`, testcontainers, `ruff`/`mypy` as a gate, or
anything that connects to a real database server. Several of these agents run concurrently and the group's
integration stage is serialized behind a repository-wide lock; a container run here would bypass it.

If a fast test fails, fix the implementation or the test and re-run until green — don't hand back a red test. If
some behavior can only be verified against the real engine, write that integration test, leave it to the group,
and say so in Step 7. Do not delete an assertion to make a file pass.

## Step 5 — When to interrupt the user

Keep interruptions rare — most tasks should complete unattended. Stop and use `AskUserQuestion` (or plain text if no
real choice is being offered) only when:

- The task is ambiguous in a way that changes correctness or scope and can't be safely inferred — in particular,
  **whether a new endpoint is public**, which scope/permission may call it, or whether an object is visible to
  non-owners.
- The described approach is infeasible against the real code (it names a router, model, dependency, or table that
  doesn't exist and isn't a trivial typo), or needs a new dependency, a `pyproject.toml` tool-config change, or test
  infrastructure (testcontainers, an `integration` marker) the task didn't ask for.
- **The task as written would require insecure code** — an unauthenticated write endpoint, a token decoded without
  an algorithm allow-list, SQL built from request strings, a table model returned to clients, a wildcard CORS with
  credentials. Report it with the secure alternative; don't implement the insecure version, and don't silently
  implement something different either.
- Step 1's detection is genuinely ambiguous in a way that changes the code you'd write (sync and async stacks mixed
  in the same feature, two ORMs, two Alembic environments).

Don't stop merely because the task is nontrivial. Don't report the task done to work around a blocker; report it as
blocked (Step 7) with what was tried, what failed, and why. A conflict between `reference/` and the surrounding code
is *not* a reason to stop: follow the surrounding code and report the divergence.

## Step 6 — Check off this task's checkbox now

If this task did not stop on a blocker, update `implementation_plan.md` at the repository root before reporting
back. Re-read the file fresh (other tasks in the same bucket may be landing concurrently), locate this task's own
checkbox line by the exact text passed in with the task, flip `[ ]` to `[x]` for it and for every sub-task covered by
the work just completed, and add a short note naming the files touched, e.g. `- [x] Task 2. **Add `POST /books`** —
app/routers/books.py, app/services/books.py, app/models/book.py, Alembic revision 3f2a…; fast tests added
(passing), integration test added (unrun); group validation pending.` The "group validation pending" wording is
what `iru-fastapi-code-one-task-group` replaces with the final outcome. Edit only this task's own line(s) — never
surrounding lines or other tasks' checkboxes.

If this task stopped on a blocker, leave its checkbox untouched.

## Step 7 — Report the outcome

Hand control back to the caller with a short summary:

- **The shape Step 1 detected** — runner prefix, layout, persistence stack, sync/async, Alembic, test style — and
  the reference files actually read.
- **The files touched**, including `main.py`/`dependencies.py` wiring and any Alembic revision (its id, whether it
  was autogenerated or hand-written, and that it was reviewed).
- **The fast tests added/updated and their result**, and **the integration tests written but not run** (the group
  must run them), and whether they need Docker.
- **Whether this task stopped on a blocker**, with enough detail for the caller to surface it.
- **Any deliberate deviation from `reference/`** (a sync stack kept, `TestClient` instead of `AsyncClient`, the
  Full Stack template's `crud.py` style, python-jose kept because the module uses it, …), and any
  **silent-defect pattern found in surrounding code** and left alone because it was out of scope — or say so if
  `reference/` was missing entirely, or the runner fell back to a bare interpreter.

This skill has already checked off its own checkbox with a pending-validation note (Step 6). It never captures a
quality baseline, adds license headers, runs a docstring audit, coverage, Ruff/mypy as a gate, the full suite, any
Alembic command against a database, or any integration test.
