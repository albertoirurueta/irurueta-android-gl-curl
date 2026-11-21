# Code style

This catalog's Python agreements, condensed for FastAPI, plus the layering rules that keep a FastAPI service
testable. Above all: **match the surrounding code.** These are the defaults for new code.

## The project's own tools decide the details

Read `pyproject.toml` (or `ruff.toml`/`.ruff.toml`, `mypy.ini`/`setup.cfg`, `pyrightconfig.json`) before writing a
line, and write code those tools would leave untouched:

| Config | What it changes |
|---|---|
| `[tool.ruff]` `line-length` | The limit (this catalog's scaffold: **88**, Ruff/Black's default — PEP 8's 79 is the docs' baseline, 88 trades a little width for far fewer wrapped signatures with `Annotated`). |
| `[tool.ruff.lint] select` | Which rule families fire. The scaffold selects `E,F,W,I,N,UP,B,S,C4,SIM,RUF,D,PT,ASYNC,FAST`; `FAST001` flags a redundant `response_model`, `FAST002` a dependency without `Annotated`, `ASYNC251` `time.sleep` in `async def`, `S` the Bandit checks. |
| `[tool.ruff.lint.pydocstyle] convention` | Docstring style: `google` (scaffold default), `numpy`, or `pep257`. |
| `[tool.mypy] strict` / `plugins` | Strictness; `pydantic.mypy` plugin. Pyright (`typeCheckingMode`) if that's what the project runs. |
| `[tool.black]`, `flake8`, `isort` configs | Older toolchains — follow them instead of Ruff when they're what the project runs. |

Format only the files you touched (`<runner> ruff format <files>` and `<runner> ruff check --fix <files>` when Ruff
is configured) — never the whole project.

## Python agreements

- **Type everything new**: parameters, returns (`-> None` included), class attributes. Built-in generics
  (`list[int]`, `dict[str, int]`), `X | None` / `A | B` — never `Optional`, `Union`, `List`, `Dict`. `Annotated` for
  every FastAPI marker (`Query`, `Path`, `Header`, `Cookie`, `Body`, `Form`, `File`, `Depends`, `Security`).
  `collections.abc` for `Iterator`/`AsyncIterator`/`Sequence`/`Callable`. No bare `Any` where a type exists;
  `typing.Protocol` for structural contracts.
- **Write `mypy --strict`-clean code.** No `# type: ignore` to silence a real mismatch; when one is unavoidable
  (an untyped third-party API), give the code (`# type: ignore[no-untyped-call]`) and a reason. With the
  `pydantic.mypy` plugin, `Settings()` with required fields from the environment needs **no** ignore — an unused
  one fails `warn_unused_ignores` under strict.
- **PEP 8 names**: `snake_case` functions/variables/modules, `PascalCase` classes, `UPPER_CASE` constants, `_name`
  for module-private helpers. Imports at the top, grouped stdlib → third-party → local (Ruff `I` sorts them); pick
  the package's existing style of absolute (`from app.models import Book`) or relative imports and stay with it.
- **Docstrings on every public module, class, and function** (Ruff `D`; `iru-python-docstrings` audits them at
  group level): a one-line summary, then `Args:`, `Returns:`/`Yields:`, `Raises:` in Google style (or the
  configured convention). `Raises:` lists every exception a caller can see, including `HTTPException` raised by a
  dependency — the tests are written against it. A path operation's docstring becomes the OpenAPI description; a
  one-line summary is enough there, and developer-only sections go after a `\f` line (FastAPI truncates the
  OpenAPI text there; Pydantic model docstrings likewise become schema descriptions).
- **Errors**: raise specific exceptions, never bare `except:` or `except Exception:` that swallows; chain with
  `raise NewError(...) from exc` (or `from None` to hide an irrelevant cause); custom exceptions subclass one project
  base (`BookshelfError(Exception)`). `assert` only for internal invariants (stripped by `-O`; Ruff `S101` flags it
  outside tests).
- **No `print`**: `logger = logging.getLogger(__name__)`; lazy `%`-style arguments (`logger.info("saved %s",
  book_id)`), `logger.exception(...)` inside `except`. Never log tokens, passwords, request bodies, or query strings.
- **Data shapes**: Pydantic models at the HTTP boundary; `@dataclass(frozen=True)`/`slots=True` for internal value
  objects; `enum.StrEnum`/`Literal` for closed sets; `Decimal` for money; timezone-aware datetimes
  (`datetime.now(UTC)`, never `utcnow()`); `pathlib.Path` over `os.path`.
- **No mutable default arguments**; no module-level mutable state shared between requests; no work at import time
  (no engine, client, connection, or settings read in a module body).
- **No new runtime dependency** unless the task explicitly calls for it — `uv add`/`poetry add` is a blocker to
  report, not a decision to make quietly. Same for a new dev dependency or a `pyproject.toml` tool-config change.
- Short single-purpose functions, named constants for meaningful values, no dead or commented-out code, no
  `breakpoint()` left behind.

## Layering inside a FastAPI service

Dependencies point one way — **web → service → data**, with models as the shared vocabulary beside them:

- **Web layer** (`routers/`, path operations, `dependencies.py`): HTTP only — parameters, status code, response
  model, call one service function or a dependency. No SQL, no business rules.
- **Service layer** (`services/`): use cases in plain Python. Takes models/primitives, returns models or rows,
  **raises domain errors** (`BookNotFoundError`, `DuplicateIsbnError`) — never `HTTPException`, never `Request`.
  Domain errors become status codes only in exception handlers registered in `main.py`
  (`routers-and-dependencies.md`).
- **Data layer** (`db.py`, `data/`, repositories): sessions, queries, adapters.
- **Wiring** lives in `dependencies.py` (the only module importing both web needs and data adapters) as
  `Annotated[T, Depends(provider)]` aliases. Services never import routers or adapters; nothing imports `app.main`
  from below (import cycles).

A flat CRUD service (routers talking to the session directly, as in the official SQL tutorial or the Full Stack
template's `crud.py`) is fine when that's what the project does — add a service layer only when the task's business
rules need one, never as a side effect.

### Where code goes, per detected layout

| Layout (Step 1) | Endpoint | Logic | Models | Wiring / settings |
|---|---|---|---|---|
| Layered `app/` (docs default) | `app/routers/<resource>.py` | `app/services/<resource>.py`, `errors.py` | `app/models/<resource>.py` | `app/dependencies.py`, `app/settings.py`, `app/db.py` |
| Full Stack template | `app/api/routes/<resource>.py`, `app/api/main.py` | `app/crud.py` | `app/models.py` | `app/api/deps.py`, `app/core/config.py`, `app/core/db.py`, `app/core/security.py` |
| `src/<pkg>/` | `src/<pkg>/api/` (or `routers/`) | `src/<pkg>/domain/`/`services/` | `src/<pkg>/models/`/`schemas/` | `src/<pkg>/core/`, `dependencies.py` |
| Ports and adapters | see `hexagonal.md` | | | |

A new router is a new module exporting `router = APIRouter(...)`, included from `main.py` (or the template's
`api/main.py`) — the include is part of the task; a forgotten `include_router` is a silent 404.
