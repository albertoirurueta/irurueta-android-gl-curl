# `def` or `async def`, and concurrency

Read this when the task writes an `async def` that does I/O, calls a possibly blocking library, or runs work
concurrently — or whenever you're unsure which kind of function to write.

## The decision rule

| The function… | Write |
|---|---|
| awaits async libraries (`AsyncSession`, `httpx.AsyncClient`, `redis.asyncio`, `AsyncMongoClient`, `asyncpg`) | `async def` |
| calls blocking I/O without async support (sync SQLAlchemy `Session`, `requests`, `boto3`, `pandas.read_sql`, most SDKs) | `def` — FastAPI runs it in the AnyIO threadpool (40 threads by default) |
| does short pure computation | `async def` |
| is CPU-heavy (seconds of pure-Python work) | offload: a `ProcessPoolExecutor` created in the lifespan (`await loop.run_in_executor(pool, fn, arg)`), or the project's task queue |
| **you're not sure** | `def` |

This applies to path operations **and dependencies** (a `def` dependency also runs in the threadpool). Your own
helper functions are called as-is — a blocking helper called from `async def` blocks the loop. Mixing `def` and
`async def` across routes is fine; follow the project's stack (an async stack keeps `async def` end to end).

## Never block the event loop

One blocking call inside `async def` stalls **every** request on that worker. The usual offenders, each flagged by
Ruff's `ASYNC` rules where it can see them (`ASYNC251` for `time.sleep`, `ASYNC210`-family for blocking HTTP):

- `time.sleep` → `await anyio.sleep(...)` / `asyncio.sleep`.
- `requests`/`urllib` → one `httpx.AsyncClient` from the lifespan (with `timeout=httpx.Timeout(...)`), or keep the
  whole handler `def`.
- A sync DB session, `open()`/`shutil` on large files, `boto3`, password hashing (Argon2 takes tens of ms and 64 MiB
  — `security.md`), template rendering of big pages, pandas/NumPy work → make the handler `def`, or wrap the one
  call: `await run_in_threadpool(fn, *args)` (`fastapi.concurrency`) / `await anyio.to_thread.run_sync(fn)` /
  `await asyncio.to_thread(fn, ...)`.
- Every outbound call has a timeout (driver `command_timeout`/`pool_timeout`, `httpx.Timeout`, plus an
  `anyio.fail_after` backstop where needed) — a hung dependency otherwise starves the threadpool.

## Concurrency inside a handler

- Sequential `await`s give no concurrency. For independent awaits use an AnyIO task group (`async with
  anyio.create_task_group() as tg: tg.start_soon(...)`) or `asyncio.TaskGroup` (3.11+) — structured, cancels
  siblings on failure. Avoid fire-and-forget `asyncio.create_task` (exceptions vanish, the task can be collected).
- **One `AsyncSession` per concurrent task** — never share a session across a task group or `gather`.
- Bound everything: `asyncio.Queue(maxsize=...)`, `anyio.Semaphore`/`CapacityLimiter` for fan-out, and answer
  `503`/`429` when full rather than queueing unboundedly.
- Shared mutable state touched from `def` handlers runs on several threads — protect it with a `threading.Lock`, or
  better keep it in Redis/the database. Per-worker in-memory state is never shared across workers.

## Pitfalls

- A missing `await` returns a coroutine object (500 at serialization); `mypy --strict` catches most of them.
- `functools.lru_cache`/`cache` on an `async def` caches the coroutine ("cannot reuse already awaited coroutine") —
  cache the awaited value instead.
- Cancellation arrives as `asyncio.CancelledError` at the current `await`: clean up in `finally` and re-raise it if
  caught.
- Async isn't faster per se; it helps I/O-bound concurrency. Don't convert a working sync stack to async as a side
  effect of a task.
