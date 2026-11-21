# Settings, lifespan, background tasks

Read this when the task reads or adds a setting/environment variable, a process-wide resource (HTTP client, DB
engine/pool, cache client, ML model, executor), or a `BackgroundTasks` job.

## What goes where

| Thing | Home |
|---|---|
| A value that differs between environments | a field on the `BaseSettings` class, read through `SettingsDep` |
| A secret | a `SecretStr` field with **no default**, from the environment / `secrets_dir` / a secret store |
| A process-wide client, pool, engine, model | the lifespan, exposed through a typed getter dependency |
| A per-request resource (DB session) | a yield dependency (`routers-and-dependencies.md`) |
| Small post-response work | `BackgroundTasks` |
| Durable, retried, scheduled, or heavy work | the project's task queue (Celery, arq, a broker) — never invent one in a task |

## Settings (pydantic-settings)

```python
class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="APP_", env_file=".env", env_nested_delimiter="__", extra="ignore"
    )

    database_url: str = "sqlite+aiosqlite:///./bookshelf.db"
    jwt_secret_key: SecretStr            # required: the app fails at startup without it
    access_token_expire_minutes: int = 30


@lru_cache
def get_settings() -> Settings:
    return Settings()


SettingsDep = Annotated[Settings, Depends(get_settings)]
```

- **A new setting is a field in the same task**, with its type (the env string is coerced) and a default — or no
  default when it's required. Keep the project's `env_prefix` (`APP_` in the scaffold; the docs' examples use a
  project prefix) so `DEBUG`/`PORT` can't collide. Lists/dicts in env vars are JSON (`'["a","b"]'`), or
  `Annotated[list[str], NoDecode]` + a `mode="before"` validator splitting commas.
- Add the variable to `.env.example` (or the project's equivalent documentation) when one exists — never to a
  committed `.env`, never a real secret anywhere in the repository. `extra="ignore"` because dotenv keys default
  to `forbid`.
- **Read settings through the dependency**, never `os.environ` in application code and never `Settings()` at
  import time; `.get_secret_value()` only at the point of use (signing, connecting). Never log settings or return
  them in a response.
- Choose implementations with a settings field read inside a dependency (`task_backend: Literal["inline",
  "broker"]`), not `if ENV == "prod"` branches or env-var module switching; no `ProductionSettings` subclass trees.
- Tests override the dependency (`app.dependency_overrides[get_settings] = lambda: Settings(_env_file=None, ...)`)
  or, when code reads the cached function directly, `monkeypatch.setenv` + `get_settings.cache_clear()` before and
  after.

## Lifespan

```python
class State(TypedDict):
    session_factory: async_sessionmaker[AsyncSession]


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[State]:
    settings = app.dependency_overrides.get(get_settings, get_settings)()
    engine = make_engine(settings.database_url)
    try:
        yield {"session_factory": make_session_factory(engine)}
    finally:
        await engine.dispose()


app = FastAPI(title="Bookshelf API", lifespan=lifespan)


def get_session_factory(request: Request) -> async_sessionmaker[AsyncSession]:
    factory: async_sessionmaker[AsyncSession] = request.state.session_factory
    return factory
```

- **Never `@app.on_event("startup"/"shutdown")`** (ignored once `lifespan=` is set); never create clients/pools
  at import time (wrong event loop, shared across tests and workers).
- Prefer **yielded `TypedDict` state** (shallow-copied onto each `request.state`) over `app.state`; expose each value
  through a typed getter dependency (`HttpDep = Annotated[httpx.AsyncClient, Depends(get_http)]`) so tests override
  the getter instead of running the lifespan. Mutate shared objects; rebinding `request.state.x` is per-request.
- `dependency_overrides` don't apply inside the lifespan — read them explicitly as above when the lifespan needs
  settings.
- Fail fast: ping/create indexes in the lifespan so a misconfiguration stops startup ("Application startup
  failed"). Close everything after `yield` (`await client.aclose()`, `await engine.dispose()`).
- The lifespan runs **once per worker process**: pool size × workers = connections; in-memory caches, rate
  limiters, and fan-out queues are per worker — shared state belongs in Redis/the database.
- Adding a resource to an existing lifespan: combine it in the same function (or an `AsyncExitStack`); a mounted
  sub-application's lifespan never runs.
- Raise the AnyIO thread limit (`anyio.to_thread.current_default_thread_limiter().total_tokens = N`) only
  deliberately, here, sized with the DB pool.

## Background tasks

- Inject `background_tasks: BackgroundTasks` (path operation or dependency — the same object) and
  `add_task(fn, book_id, ...)`. Tasks run after the response, sequentially, in the same process; they're dropped if
  the handler raises, and a failing task only logs.
- **Pass ids and plain data, never a session, ORM object, or `Request`**; the task opens its own session (from the
  session factory) and catches/logs its own errors.
- `def` tasks run in the threadpool; `async def` tasks on the loop — never block in them.
- No durability, retries, or scheduling: if the task needs those, the plan needed a queue — report it. If the
  client needs the outcome, return `202` + a job id + a status endpoint.
