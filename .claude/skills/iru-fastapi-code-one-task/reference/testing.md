# Tests

Every task's implementation comes with the tests that prove it. This skill writes fast and integration tests, runs
**only the fast test files it touched**, and never runs the `integration` tier, coverage, or the full suite — the
calling `iru-fastapi-code-one-task-group` does that once for the whole group.

## Follow the project's style

| Detected (Step 1) | Client | Test functions |
|---|---|---|
| Sync stack, or `TestClient` already in `tests/` | `fastapi.testclient.TestClient` | plain `def test_…` (works for `async def` endpoints too) |
| Async stack with `httpx.AsyncClient` in `tests/` | `AsyncClient(transport=ASGITransport(app=app), base_url="http://test")` | `async def` + `@pytest.mark.anyio` (module-level `pytestmark`) and an `anyio_backend` fixture returning `"asyncio"` |
| `pytest-asyncio` in dev deps | as above | `@pytest.mark.asyncio` / its `asyncio_mode` — never mix it with the AnyIO marker in one suite |

Layout: `tests/` beside `app/` (not inside the package), mirroring module names (`tests/test_books.py`,
`tests/routers/test_books.py`), shared fixtures in `tests/conftest.py`, hand-written fakes in `tests/fakes.py`;
container-backed tests under `tests/integration/` (or wherever the project keeps them) and marked
`@pytest.mark.integration`. `--strict-markers` means an unregistered marker is an error — use the markers
`[tool.pytest.ini_options] markers` already declares.

## The client fixture: overrides, cleared every time

Everything environmental is a dependency, so tests swap it with `app.dependency_overrides` — never by patching
module globals or setting environment variables that are read at import:

```python
@pytest.fixture(name="settings")
def settings_fixture() -> Settings:
    return Settings(
        _env_file=None,
        database_url="sqlite+aiosqlite://",
        jwt_secret_key=SecretStr(secrets.token_hex(32)),
    )


@pytest.fixture(name="session_factory")
async def session_factory_fixture() -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    engine = create_async_engine(
        "sqlite+aiosqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    async with engine.begin() as conn:
        await conn.run_sync(SQLModel.metadata.create_all)
    yield async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    await engine.dispose()


@pytest.fixture(name="client")
async def client_fixture(
    settings: Settings, session_factory: async_sessionmaker[AsyncSession]
) -> AsyncIterator[AsyncClient]:
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_session_factory] = lambda: session_factory
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        yield client
    app.dependency_overrides.clear()
```

- **The override key is the exact function object** used in `Depends(...)`. Overriding a yield dependency with a
  plain function skips its setup/teardown. Always `.clear()` in the fixture teardown — leftover overrides leak
  between tests. A mounted sub-app has its own `dependency_overrides`.
- **Lifespan**: `with TestClient(app) as client:` runs it; `TestClient(app)` without `with` and `ASGITransport` do
  **not** (use `asgi_lifespan.LifespanManager(app)` or `app.router.lifespan_context(app)` when a test needs it).
  `dependency_overrides` don't apply inside the lifespan unless the lifespan reads them
  (`settings-and-lifespan.md`).
- **Secrets are random per test** (`secrets.token_hex(32)`), never literals that look real; `Settings(_env_file=None,
  ...)` so a developer's `.env` can't change the result.
- **Auth**: mint tokens with the app's own `create_access_token(...)`; hit `/token` only in login-flow tests
  (Argon2 is slow); override `get_current_user` only when identity is irrelevant — and still test the real checker.
- **`TestClient` + httpx**: Starlette ≥ 1.2 warns `StarletteDeprecationWarning: Using httpx with
  starlette.testclient is deprecated` unless `httpx2` is installed (verified; the warning is a `UserWarning`, so
  `filterwarnings = ["error::DeprecationWarning"]` doesn't catch it, but `"error"` does). Adding `httpx2` is a
  dependency change — if the warning breaks the suite, report it rather than adding the package.
- Never call `TestClient` inside an `async def` test; create loop-bound objects (engines, clients) inside async
  fixtures/tests, not at module level.

## What to assert

- **Status first, then body.** `201` + `Location` for creates, `204` and an empty body for deletes, `404` for a
  missing *or someone else's* object, `409` for a unique violation, `401` + `WWW-Authenticate` for missing/invalid
  credentials, `403` for insufficient scope/permission, `422` for malformed input.
- **422s by `type` and `loc`, never `msg`**: `assert error["type"] == "extra_forbidden"` and `error["loc"] ==
  ["body", "owner_id"]` (Pydantic v2 types: `missing`, `int_parsing`, `string_too_short`, `greater_than_equal`,
  `value_error`, `json_invalid`, `model_attributes_type`).
- **Response contracts**: secret/internal fields absent (`"hashed_password" not in body`, `"owner_id" not in body`).
- **Every `Raises:` in the docstrings** of what the task added gets a test, plus boundaries (empty list, page limits
  via `@pytest.mark.parametrize`) and each branch the task added.
- Unit-test services with **fakes** (an in-memory implementation of a port, a SQLite session) rather than
  `MagicMock`; when a mock is right, `create_autospec(BookService, instance=True)`. Don't call path functions
  directly — go through the client.
- No assertion-free tests; no `time.sleep` (await events, use frozen/injected clocks); no real network.

## The two tiers

- **Fast tier** (the coverage gate counts only these): in-memory SQLite with `StaticPool` per test
  (`sqlite://` sync / `sqlite+aiosqlite://` async), fakes for Redis/HTTP (`fakeredis`, a fake client via
  `dependency_overrides`), `PRAGMA foreign_keys=ON` through an engine `connect` event when FK behavior matters.
  SQLite gaps: no JSONB/arrays, lenient types and lengths, naive datetimes even for `DateTime(timezone=True)`
  (verified), different constraint errors, no concurrency.
- **Integration tier** (`@pytest.mark.integration`): the real engine, started by the test suite with
  testcontainers — `from testcontainers.community.postgres import PostgresContainer` (the old
  `testcontainers.postgres` path emits a `DeprecationWarning` that fails collection under warnings-as-errors —
  verified), one container per module/session (`scope="module"`), migrations applied with
  `alembic.command.upgrade(Config("alembic.ini"), "head")` instead of `create_all`. Put dialect-sensitive behavior
  here: unique/FK violations → 409, JSONB queries, ordering, the "models match migrations" check:

```python
@pytest.fixture(scope="module")
def postgres_url(monkeypatch_module: pytest.MonkeyPatch) -> Iterator[str]:
    with PostgresContainer("postgres:18", driver="psycopg") as pg:
        url = pg.get_connection_url()
        monkeypatch_module.setenv("APP_MIGRATIONS_DATABASE_URL", url)  # what env.py reads
        command.upgrade(Config("alembic.ini"), "head")
        yield url


def test_migrations_match_models(postgres_url: str) -> None:
    engine = create_engine(postgres_url)
    with engine.connect() as conn:
        context = MigrationContext.configure(conn, opts={"compare_type": True})
        assert compare_metadata(context, SQLModel.metadata) == []
    engine.dispose()
```

  Write these when the task needs them; **don't run them** — the group runs the `integration` selection once,
  behind the repository-wide lock. A project without testcontainers and without an `integration` marker gets no
  new container test infrastructure from a task: write the fast tests, and report the gap.

Property-based (Hypothesis) and schema-driven (Schemathesis `schemathesis.openapi.from_asgi`) tests are welcome where
the project already uses them; module-level `TestClient` with Hypothesis (function-scoped fixtures trip
`FailedHealthCheck`), `deadline=None` for HTTP.

## What to run here

Only the fast test files this task created or changed, through the detected runner prefix, excluding the
integration tier and coverage:

```bash
uv run --frozen pytest tests/test_books.py tests/services/test_books.py -m "not integration" --no-cov
# poetry run pytest … / pdm run pytest … / pipenv run pytest … / .venv/bin/python -m pytest …
```

(`--frozen` so uv never relocks and dirties `uv.lock`; `-m "not integration"` only when the project declares that
marker — otherwise just don't pass any path under `tests/integration/`.)

(`--no-cov` only when pytest-cov is installed — it neutralizes a `--cov`/`--cov-fail-under` in `addopts`, which
would otherwise fail a scoped run on total coverage.) Never `-m integration`, never a bare `pytest` without paths,
never `--cov`, never anything that starts Docker or connects to a real database server.
