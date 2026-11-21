# App templates (default shape: `database: postgresql`, `orm: sqlmodel`, `auth: no`)

Every block below is the verified file, copied from a throwaway project that passed `uv run ruff check .`,
`uv run ruff format --check .`, `uv run mypy .` (strict), the fast tier at 100% coverage, the testcontainers
integration tier, `sphinx-build -W`, and a `docker build` + running container (see SKILL.md, Verification notes).
Replace only the placeholders: `<name>` (the distribution name, kebab-case — `importlib.metadata.version()` looks it
up, so it must equal `[project].name`), `<title>` (the human title, e.g. "Orders API"), `<description>`. The sample
resource (`books`) keeps its names unless the user asked for a different first resource — then rename it
consistently (module, class, table, route prefix, tests) and keep the shape. `BookshelfError` (the domain
errors' base class) becomes `<Title-without-spaces>Error`, e.g. `OrdersApiError`.

Layering (irurueta docs: `backend/fastapi/project-structure-and-layered-architecture.adoc`):

| Layer | Modules | Knows about |
|---|---|---|
| Web | `app/main.py`, `app/routers/` | HTTP, the service, the models — never SQL |
| Wiring | `app/dependencies.py` | the only module importing both the web needs and the data adapters |
| Service | `app/services/` (`books.py`, `errors.py`, `ports.py`) | domain rules and errors, the `BookRepository` `Protocol` port — no HTTP, no SQL |
| Data | `app/db.py` (engine, session factory), `app/data/` (adapters implementing the ports) | SQLAlchemy/SQLModel |
| Shared | `app/models/` (the SQLModel family `BookBase`/`Book(table=True)`/`BookCreate`/`BookPublic`/`BookUpdate`), `app/settings.py` | — |

Conventions the files exemplify (the ones `iru-fastapi-code-one-task` enforces): `Annotated[..., Depends()]` aliases
(`SettingsDep`, `SessionmakerDep`, `SessionDep`, `BookServiceDep`); one session = one transaction per request,
committed by `get_session` (`Depends(..., scope="function")`, so a failing commit is still an error response),
rolled back on any exception; repositories flush, never commit; domain errors mapped to 404/409 only in `main.py`;
never return the table model (endpoints declare `-> BookPublic` and validate into it); write models
`extra="forbid"`; PATCH via `model_dump(exclude_unset=True)` + `sqlmodel_update`, explicit `null` rejected; lifespan
(never `on_event`) owns the engine, reading settings through `app.dependency_overrides` because overrides don't apply
inside a lifespan; `/healthz` touches nothing, `/readyz` pings the database with a 2-second timeout and answers 503;
Alembic owns the schema — nothing calls `create_all` outside the tests.

Test conventions: `httpx.AsyncClient(transport=ASGITransport(app=app))` + `@pytest.mark.anyio` (no `TestClient` — on
Starlette 1.8 it raises `StarletteDeprecationWarning: Using httpx with starlette.testclient is deprecated; install
httpx2 instead`, which the strict `filterwarnings` turns into an error; if a project adds `TestClient` tests, add
`httpx2` to the `dev` group); a new app per test via `create_app()`; overrides cleared in teardown; 422s asserted on
`type`/`loc`, never `msg`; fast tier on in-memory SQLite (`StaticPool`, foreign keys on) with `create_all`;
service tests on an in-memory fake (`tests/fakes.py`) instead of mocks; `asgi_lifespan.LifespanManager` where the
real lifespan must run (plain `ASGITransport` doesn't run it and doesn't populate `request.state`).

## Application package (`app/`)

### `app/__init__.py`

```python
"""<description>

The version is read from the installed distribution's metadata, so
``pyproject.toml`` ``[project].version`` stays the single source of truth.
"""

from importlib.metadata import version

__version__ = version("<name>")
```

### `app/settings.py`

```python
"""Application settings, read from ``APP_*`` environment variables (or ``.env``)."""

from functools import lru_cache

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class DatabaseSettings(BaseSettings):
    """The database connection: all that Alembic's ``migrations/env.py`` needs.

    Every field maps to an ``APP_``-prefixed environment variable (``database_url``
    is ``APP_DATABASE_URL``). Secrets are :class:`~pydantic.SecretStr`, so they never
    show up in logs or reprs, and required fields have no default, so a misconfigured
    deployment fails at start-up instead of on the first request.
    """

    model_config = SettingsConfigDict(
        env_prefix="APP_",
        env_file=".env",
        env_nested_delimiter="__",
        extra="ignore",
        frozen=True,
    )

    database_url: SecretStr = Field(
        description=(
            "SQLAlchemy async database URL, e.g. "
            "``postgresql+asyncpg://user:password@host:5432/name``."
        ),
    )
    database_echo: bool = Field(
        default=False, description="Log every SQL statement (development only)."
    )


class Settings(DatabaseSettings):
    """Validated runtime configuration of the whole application."""


@lru_cache
def get_settings() -> Settings:
    """Return the process-wide settings, built and validated once on first use.

    Returns:
        The cached :class:`Settings` instance.
    """
    return Settings()
```

### `app/db.py`

```python
"""Database plumbing: one async engine per process and a session factory per engine."""

from sqlalchemy import event
from sqlalchemy.engine.interfaces import DBAPIConnection
from sqlalchemy.ext.asyncio import AsyncEngine, async_sessionmaker, create_async_engine
from sqlalchemy.pool import ConnectionPoolEntry, StaticPool
from sqlmodel.ext.asyncio.session import AsyncSession


def create_engine(url: str, *, echo: bool = False) -> AsyncEngine:
    """Create the application's async engine.

    PostgreSQL gets ``pool_pre_ping`` so connections dropped by the server or a
    proxy are replaced transparently. SQLite (tests, local runs) gets foreign keys
    switched on to behave like PostgreSQL, and an in-memory database is shared
    through a single connection so every session sees the same tables.

    Args:
        url: SQLAlchemy async URL (``postgresql+asyncpg://...`` or ``sqlite+aiosqlite://...``).
        echo: Whether to log every SQL statement.

    Returns:
        A new engine; dispose of it with ``await engine.dispose()`` on shutdown.
    """
    if not url.startswith("sqlite"):
        return create_async_engine(url, echo=echo, pool_pre_ping=True)
    if url.endswith((":memory:", "://")):
        engine = create_async_engine(url, echo=echo, poolclass=StaticPool)
    else:
        engine = create_async_engine(url, echo=echo)
    event.listen(engine.sync_engine, "connect", _enable_sqlite_foreign_keys)
    return engine


def create_sessionmaker(engine: AsyncEngine) -> async_sessionmaker[AsyncSession]:
    """Create the session factory bound to ``engine``.

    ``expire_on_commit=False`` keeps loaded attributes readable after the commit, which
    async code needs because a lazy reload would require an implicit await.

    Args:
        engine: The engine sessions connect through.

    Returns:
        A factory producing SQLModel :class:`AsyncSession` objects.
    """
    return async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)


def _enable_sqlite_foreign_keys(
    dbapi_connection: DBAPIConnection, _connection_record: ConnectionPoolEntry
) -> None:
    cursor = dbapi_connection.cursor()
    cursor.execute("PRAGMA foreign_keys=ON")
    cursor.close()
```

### `app/models/__init__.py`

```python
"""Data models shared by every layer: the SQLModel table and the API schemas."""

from sqlmodel import SQLModel

# Deterministic constraint names, set before any table is declared (and before the
# first migration): without them PostgreSQL and SQLite name constraints differently
# and Alembic cannot drop them reliably later.
SQLModel.metadata.naming_convention = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}

from app.models.book import Book, BookCreate, BookPublic, BookUpdate  # noqa: E402
from app.models.health import HealthStatus  # noqa: E402

__all__ = ["Book", "BookCreate", "BookPublic", "BookUpdate", "HealthStatus"]
```

### `app/models/book.py`

```python
"""The ``Book`` model family: one table model plus the create/public/update schemas."""

from pydantic import ConfigDict, field_validator
from sqlmodel import Field, SQLModel


class BookBase(SQLModel):
    """Fields shared by every book schema."""

    title: str = Field(min_length=1, max_length=200)
    author: str = Field(min_length=1, max_length=200)
    isbn: str = Field(
        min_length=13,
        max_length=13,
        schema_extra={"pattern": r"^\d{13}$"},
        unique=True,
        description="ISBN-13, digits only.",
    )


class Book(BookBase, table=True):
    """The ``book`` table. Endpoints never return it: they answer `BookPublic`."""

    id: int | None = Field(default=None, primary_key=True)


class BookCreate(BookBase):
    """Request body for creating a book. Unknown fields are rejected."""

    model_config = ConfigDict(extra="forbid")  # type: ignore[assignment]


class BookPublic(BookBase):
    """A book as returned by the API."""

    id: int


class BookUpdate(SQLModel):
    """Partial update: only the fields sent are changed, ``null`` is rejected."""

    model_config = ConfigDict(extra="forbid")  # type: ignore[assignment]

    title: str | None = Field(default=None, min_length=1, max_length=200)
    author: str | None = Field(default=None, min_length=1, max_length=200)

    @field_validator("title", "author", mode="after")
    @classmethod
    def reject_null(cls, value: str | None) -> str:
        """Allow omitting a field, but not sending ``null`` (the columns are NOT NULL).

        Args:
            value: The validated value.

        Returns:
            The value unchanged.

        Raises:
            ValueError: If the client sent an explicit ``null``.
        """
        if value is None:
            msg = "may be omitted but not null"
            raise ValueError(msg)
        return value
```

### `app/models/health.py`

```python
"""Health-check response model."""

from typing import Literal

from pydantic import BaseModel


class HealthStatus(BaseModel):
    """Result of a liveness or readiness probe."""

    status: Literal["ok", "unavailable"]
```

### `app/services/__init__.py`

```python
"""Service layer: use cases, domain errors and ports (no HTTP, no SQL)."""
```

### `app/services/errors.py`

```python
"""Domain errors. They carry no HTTP status: ``app.main`` maps them to responses."""


class BookshelfError(Exception):
    """Base class of every domain error raised by the service layer."""


class BookNotFoundError(BookshelfError):
    """No book has the requested id."""

    def __init__(self, book_id: int) -> None:
        """Create the error.

        Args:
            book_id: The id that was looked up.
        """
        super().__init__(f"Book {book_id} not found")
        self.book_id = book_id


class DuplicateIsbnError(BookshelfError):
    """Another book already has this ISBN."""

    def __init__(self, isbn: str) -> None:
        """Create the error.

        Args:
            isbn: The ISBN that is already taken.
        """
        super().__init__(f"A book with ISBN {isbn} already exists")
        self.isbn = isbn
```

### `app/services/ports.py`

```python
"""Ports: what the service layer needs from the data layer, as structural types."""

from collections.abc import Sequence
from typing import Protocol

from app.models import Book


class BookRepository(Protocol):
    """Persistence operations on books.

    Implementations never commit: the caller owns the transaction (one per request,
    see ``app.dependencies.get_session``).
    """

    async def get(self, book_id: int) -> Book | None:
        """Return the book with this id, or ``None``."""
        ...

    async def get_by_isbn(self, isbn: str) -> Book | None:
        """Return the book with this ISBN, or ``None``."""
        ...

    async def list_page(self, *, offset: int, limit: int) -> Sequence[Book]:
        """Return up to ``limit`` books ordered by id, skipping the first ``offset``."""
        ...

    async def save(self, book: Book) -> Book:
        """Insert or update ``book`` and return it with its generated id.

        Raises:
            DuplicateIsbnError: If the database rejects the ISBN as a duplicate.
        """
        ...

    async def delete(self, book: Book) -> None:
        """Delete ``book``."""
        ...
```

### `app/services/books.py`

```python
"""Book use cases."""

from collections.abc import Sequence

from app.models import Book, BookCreate, BookUpdate
from app.services.errors import BookNotFoundError, DuplicateIsbnError
from app.services.ports import BookRepository


class BookService:
    """Create, read, update and delete books, enforcing the catalogue's rules."""

    def __init__(self, repository: BookRepository) -> None:
        """Create the service.

        Args:
            repository: Where books are stored.
        """
        self._repository = repository

    async def create(self, data: BookCreate) -> Book:
        """Add a book to the catalogue.

        Args:
            data: The validated request body.

        Returns:
            The stored book, with its id.

        Raises:
            DuplicateIsbnError: If another book already has this ISBN.
        """
        if await self._repository.get_by_isbn(data.isbn) is not None:
            raise DuplicateIsbnError(data.isbn)
        return await self._repository.save(Book.model_validate(data))

    async def get(self, book_id: int) -> Book:
        """Return one book.

        Args:
            book_id: The book's id.

        Returns:
            The book.

        Raises:
            BookNotFoundError: If no book has this id.
        """
        book = await self._repository.get(book_id)
        if book is None:
            raise BookNotFoundError(book_id)
        return book

    async def list_page(self, *, offset: int, limit: int) -> Sequence[Book]:
        """Return one page of books ordered by id.

        Args:
            offset: How many books to skip.
            limit: The maximum number of books to return.

        Returns:
            The books on the page (possibly none).
        """
        return await self._repository.list_page(offset=offset, limit=limit)

    async def update(self, book_id: int, changes: BookUpdate) -> Book:
        """Change the fields present in ``changes``, leaving the others untouched.

        Args:
            book_id: The book's id.
            changes: The fields to change.

        Returns:
            The updated book.

        Raises:
            BookNotFoundError: If no book has this id.
        """
        book = await self.get(book_id)
        book.sqlmodel_update(changes.model_dump(exclude_unset=True))
        return await self._repository.save(book)

    async def delete(self, book_id: int) -> None:
        """Remove a book from the catalogue.

        Args:
            book_id: The book's id.

        Raises:
            BookNotFoundError: If no book has this id.
        """
        await self._repository.delete(await self.get(book_id))
```

### `app/data/__init__.py`

```python
"""Data layer: adapters implementing the service layer's ports."""
```

### `app/data/books.py`

```python
"""SQL implementation of :class:`app.services.ports.BookRepository`."""

from collections.abc import Sequence

from sqlalchemy.exc import IntegrityError
from sqlmodel import col, select
from sqlmodel.ext.asyncio.session import AsyncSession

from app.models import Book
from app.services.errors import DuplicateIsbnError


class SqlBookRepository:
    """Stores books through an :class:`AsyncSession`; flushes, never commits."""

    def __init__(self, session: AsyncSession) -> None:
        """Create the repository.

        Args:
            session: The request's session (it owns the transaction).
        """
        self._session = session

    async def get(self, book_id: int) -> Book | None:
        """Return the book with this id, or ``None``."""
        return await self._session.get(Book, book_id)

    async def get_by_isbn(self, isbn: str) -> Book | None:
        """Return the book with this ISBN, or ``None``."""
        result = await self._session.exec(select(Book).where(Book.isbn == isbn))
        return result.first()

    async def list_page(self, *, offset: int, limit: int) -> Sequence[Book]:
        """Return up to ``limit`` books ordered by id, skipping the first ``offset``."""
        statement = select(Book).order_by(col(Book.id)).offset(offset).limit(limit)
        result = await self._session.exec(statement)
        return result.all()

    async def save(self, book: Book) -> Book:
        """Insert or update ``book`` and return it with its generated id.

        Raises:
            DuplicateIsbnError: If the unique ISBN constraint rejects the row (a
                concurrent request inserted the same ISBN after the service checked).
        """
        self._session.add(book)
        try:
            await self._session.flush()
        except IntegrityError as exc:
            raise DuplicateIsbnError(book.isbn) from exc
        await self._session.refresh(book)
        return book

    async def delete(self, book: Book) -> None:
        """Delete ``book``."""
        await self._session.delete(book)
        await self._session.flush()
```

### `app/dependencies.py`

```python
"""Dependency providers and their ``Annotated`` aliases.

This is the only module wiring the web layer to the data layer; tests replace any
provider through ``app.dependency_overrides``.
"""

from collections.abc import AsyncIterator
from typing import Annotated

from fastapi import Depends, Request
from sqlalchemy.ext.asyncio import async_sessionmaker
from sqlmodel.ext.asyncio.session import AsyncSession

from app.data.books import SqlBookRepository
from app.services.books import BookService
from app.services.ports import BookRepository
from app.settings import Settings, get_settings

SettingsDep = Annotated[Settings, Depends(get_settings)]


def get_sessionmaker(request: Request) -> async_sessionmaker[AsyncSession]:
    """Return the session factory the lifespan created for this process.

    Args:
        request: The current request (its state is populated by ``app.main.lifespan``).

    Returns:
        The session factory.
    """
    sessionmaker: async_sessionmaker[AsyncSession] = request.state.sessionmaker
    return sessionmaker


SessionmakerDep = Annotated[async_sessionmaker[AsyncSession], Depends(get_sessionmaker)]


async def get_session(sessionmaker: SessionmakerDep) -> AsyncIterator[AsyncSession]:
    """Open one session (one transaction) per request.

    The transaction is committed when the endpoint returns and rolled back when it
    raises. ``scope="function"`` on the alias below runs this teardown before the
    response is sent, so a failing commit still turns into an error response.

    Args:
        sessionmaker: The process-wide session factory.

    Yields:
        The request's session.
    """
    async with sessionmaker() as session:
        try:
            yield session
        except Exception:
            await session.rollback()
            raise
        await session.commit()


SessionDep = Annotated[AsyncSession, Depends(get_session, scope="function")]


def get_book_repository(session: SessionDep) -> BookRepository:
    """Return the SQL book repository bound to the request's session."""
    return SqlBookRepository(session)


BookRepositoryDep = Annotated[BookRepository, Depends(get_book_repository)]


def get_book_service(repository: BookRepositoryDep) -> BookService:
    """Return the book service for this request."""
    return BookService(repository)


BookServiceDep = Annotated[BookService, Depends(get_book_service)]
```

### `app/routers/__init__.py`

```python
"""Web layer: one ``APIRouter`` per resource (HTTP only, no SQL)."""
```

### `app/routers/health.py`

```python
"""Liveness and readiness probes for orchestrators and load balancers."""

import asyncio

from fastapi import APIRouter, Response, status
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError

from app.dependencies import SessionmakerDep
from app.models import HealthStatus

router = APIRouter(tags=["health"])

READINESS_TIMEOUT_SECONDS = 2.0


@router.get("/healthz")
async def liveness() -> HealthStatus:
    """Report that the process is up. Never touches a dependency (no restart storms)."""
    return HealthStatus(status="ok")


@router.get(
    "/readyz",
    responses={status.HTTP_503_SERVICE_UNAVAILABLE: {"model": HealthStatus}},
)
async def readiness(sessionmaker: SessionmakerDep, response: Response) -> HealthStatus:
    """Report whether the service can take traffic: the database must answer quickly."""
    try:
        async with (
            asyncio.timeout(READINESS_TIMEOUT_SECONDS),
            sessionmaker() as session,
        ):
            connection = await session.connection()
            await connection.execute(text("SELECT 1"))
    except (SQLAlchemyError, OSError):  # TimeoutError is an OSError
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return HealthStatus(status="unavailable")
    return HealthStatus(status="ok")
```

### `app/routers/books.py`

```python
"""``/books`` endpoints: translate HTTP to service calls and back."""

from typing import Annotated, Any

from fastapi import APIRouter, Path, Query, status

from app.dependencies import BookServiceDep
from app.models import BookCreate, BookPublic, BookUpdate

router = APIRouter(prefix="/books", tags=["books"])

BookId = Annotated[int, Path(ge=1, description="Primary key of a book.")]
NOT_FOUND: dict[int | str, dict[str, Any]] = {
    status.HTTP_404_NOT_FOUND: {"description": "No book has this id."}
}


@router.post(
    "",
    status_code=status.HTTP_201_CREATED,
    responses={status.HTTP_409_CONFLICT: {"description": "The ISBN is already taken."}},
)
async def create_book(book: BookCreate, service: BookServiceDep) -> BookPublic:
    """Add a book to the catalogue."""
    return BookPublic.model_validate(await service.create(book))


@router.get("")
async def list_books(
    service: BookServiceDep,
    offset: Annotated[int, Query(ge=0)] = 0,
    limit: Annotated[int, Query(ge=1, le=100)] = 20,
) -> list[BookPublic]:
    """Return one page of books, ordered by id."""
    books = await service.list_page(offset=offset, limit=limit)
    return [BookPublic.model_validate(book) for book in books]


@router.get("/{book_id}", responses=NOT_FOUND)
async def read_book(book_id: BookId, service: BookServiceDep) -> BookPublic:
    """Return one book."""
    return BookPublic.model_validate(await service.get(book_id))


@router.patch("/{book_id}", responses=NOT_FOUND)
async def update_book(
    book_id: BookId, changes: BookUpdate, service: BookServiceDep
) -> BookPublic:
    """Change some fields of a book."""
    return BookPublic.model_validate(await service.update(book_id, changes))


@router.delete(
    "/{book_id}", status_code=status.HTTP_204_NO_CONTENT, responses=NOT_FOUND
)
async def delete_book(book_id: BookId, service: BookServiceDep) -> None:
    """Remove a book from the catalogue."""
    await service.delete(book_id)
```

### `app/main.py`

```python
"""Application factory: routers, exception handlers and the lifespan, nothing else."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import TypedDict

from fastapi import FastAPI, Request, status
from fastapi.responses import JSONResponse
from sqlalchemy.ext.asyncio import AsyncEngine, async_sessionmaker
from sqlmodel.ext.asyncio.session import AsyncSession

from app import __version__
from app.db import create_engine, create_sessionmaker
from app.routers import books, health
from app.services.errors import BookNotFoundError, DuplicateIsbnError
from app.settings import Settings, get_settings


class State(TypedDict):
    """Process-wide resources created by :func:`lifespan` (see ``request.state``)."""

    engine: AsyncEngine
    sessionmaker: async_sessionmaker[AsyncSession]


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[State]:
    """Create the database engine on start-up and dispose of it on shutdown.

    Runs once per worker process. Dependency overrides do not apply inside a
    lifespan, so the settings provider is looked up in ``app.dependency_overrides``
    explicitly (tests override it). Alembic owns the schema: nothing is created here.

    Args:
        app: The application being started.

    Yields:
        The lifespan state.
    """
    settings: Settings = app.dependency_overrides.get(get_settings, get_settings)()
    engine = create_engine(
        settings.database_url.get_secret_value(), echo=settings.database_echo
    )
    try:
        yield {"engine": engine, "sessionmaker": create_sessionmaker(engine)}
    finally:
        await engine.dispose()


def create_app() -> FastAPI:
    """Build the application.

    Returns:
        A new :class:`FastAPI` instance with its routers and exception handlers.
    """
    app = FastAPI(title="<title>", version=__version__, lifespan=lifespan)
    app.include_router(health.router)
    app.include_router(books.router, prefix="/api/v1")

    @app.exception_handler(BookNotFoundError)
    async def book_not_found(_request: Request, exc: BookNotFoundError) -> JSONResponse:
        return JSONResponse(
            status_code=status.HTTP_404_NOT_FOUND, content={"detail": str(exc)}
        )

    @app.exception_handler(DuplicateIsbnError)
    async def duplicate_isbn(
        _request: Request, exc: DuplicateIsbnError
    ) -> JSONResponse:
        return JSONResponse(
            status_code=status.HTTP_409_CONFLICT, content={"detail": str(exc)}
        )

    return app


app = create_app()
```

## Fast tests (`tests/`, no Docker)

`tests/__init__.py`, `tests/unit/__init__.py` and `tests/integration/__init__.py` are empty files (they make `from tests.fakes import ...` importable).

### `tests/conftest.py`

```python
"""Fixtures shared by every test tier."""

from collections.abc import AsyncIterator, Iterator

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import SecretStr
from sqlalchemy.ext.asyncio import AsyncEngine, async_sessionmaker
from sqlmodel import SQLModel
from sqlmodel.ext.asyncio.session import AsyncSession

from app.db import create_engine, create_sessionmaker
from app.dependencies import get_sessionmaker
from app.main import create_app
from app.settings import Settings, get_settings

SQLITE_MEMORY_URL = "sqlite+aiosqlite://"


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


@pytest.fixture
def settings() -> Settings:
    # _env_file=None: never read a developer's .env during tests.
    return Settings(_env_file=None, database_url=SecretStr(SQLITE_MEMORY_URL))


@pytest.fixture
async def engine() -> AsyncIterator[AsyncEngine]:
    """A fresh in-memory SQLite database per test, schema created from the models."""
    engine = create_engine(SQLITE_MEMORY_URL)
    async with engine.begin() as connection:
        await connection.run_sync(SQLModel.metadata.create_all)
    yield engine
    await engine.dispose()


@pytest.fixture
def sessionmaker(engine: AsyncEngine) -> async_sessionmaker[AsyncSession]:
    return create_sessionmaker(engine)


@pytest.fixture
def app(
    settings: Settings, sessionmaker: async_sessionmaker[AsyncSession]
) -> Iterator[FastAPI]:
    """A new application per test, wired to the test database."""
    application = create_app()
    application.dependency_overrides[get_settings] = lambda: settings
    application.dependency_overrides[get_sessionmaker] = lambda: sessionmaker
    yield application
    application.dependency_overrides.clear()


@pytest.fixture
async def client(app: FastAPI) -> AsyncIterator[AsyncClient]:
    """An HTTP client calling the app in-process (the lifespan is not run)."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as http:
        yield http
```

### `tests/fakes.py`

```python
"""In-memory test doubles for the service layer's ports."""

from collections.abc import Sequence

from app.models import Book
from app.services.errors import DuplicateIsbnError


class InMemoryBookRepository:
    """A :class:`app.services.ports.BookRepository` backed by a dict."""

    def __init__(self) -> None:
        self.books: dict[int, Book] = {}

    async def get(self, book_id: int) -> Book | None:
        return self.books.get(book_id)

    async def get_by_isbn(self, isbn: str) -> Book | None:
        return next((b for b in self.books.values() if b.isbn == isbn), None)

    async def list_page(self, *, offset: int, limit: int) -> Sequence[Book]:
        return [self.books[key] for key in sorted(self.books)][offset : offset + limit]

    async def save(self, book: Book) -> Book:
        duplicate = await self.get_by_isbn(book.isbn)
        if duplicate is not None and duplicate is not book:
            raise DuplicateIsbnError(book.isbn)
        if book.id is None:
            book.id = max(self.books, default=0) + 1
        self.books[book.id] = book
        return book

    async def delete(self, book: Book) -> None:
        if book.id is not None:
            del self.books[book.id]
```

### `tests/unit/test_book_service.py`

```python
import pytest

from app.models import BookCreate, BookUpdate
from app.services.books import BookService
from app.services.errors import BookNotFoundError, DuplicateIsbnError
from tests.fakes import InMemoryBookRepository

pytestmark = pytest.mark.anyio

DUNE = BookCreate(title="Dune", author="Frank Herbert", isbn="9780441172719")


@pytest.fixture
def repository() -> InMemoryBookRepository:
    return InMemoryBookRepository()


@pytest.fixture
def service(repository: InMemoryBookRepository) -> BookService:
    return BookService(repository)


async def test_create_assigns_an_id(service: BookService) -> None:
    book = await service.create(DUNE)

    assert book.id == 1
    assert book.title == "Dune"


async def test_create_rejects_a_duplicate_isbn(service: BookService) -> None:
    await service.create(DUNE)

    with pytest.raises(DuplicateIsbnError) as error:
        await service.create(DUNE)

    assert error.value.isbn == DUNE.isbn


async def test_get_raises_when_missing(service: BookService) -> None:
    with pytest.raises(BookNotFoundError) as error:
        await service.get(42)

    assert error.value.book_id == 42


async def test_update_changes_only_the_fields_sent(service: BookService) -> None:
    created = await service.create(DUNE)
    assert created.id is not None

    updated = await service.update(created.id, BookUpdate(title="Dune Messiah"))

    assert updated.title == "Dune Messiah"
    assert updated.author == "Frank Herbert"


async def test_delete_removes_the_book(
    service: BookService, repository: InMemoryBookRepository
) -> None:
    created = await service.create(DUNE)
    assert created.id is not None

    await service.delete(created.id)

    assert repository.books == {}


async def test_list_page_is_ordered_and_bounded(service: BookService) -> None:
    for index in range(3):
        await service.create(
            BookCreate(title=f"Book {index}", author="A", isbn=f"978000000000{index}")
        )

    page = await service.list_page(offset=1, limit=1)

    assert [book.title for book in page] == ["Book 1"]
```

### `tests/unit/test_books_api.py`

```python
from typing import Any

import pytest
from httpx import AsyncClient

pytestmark = pytest.mark.anyio

DUNE = {"title": "Dune", "author": "Frank Herbert", "isbn": "9780441172719"}


async def create(client: AsyncClient, **overrides: str) -> dict[str, Any]:
    response = await client.post("/api/v1/books", json=DUNE | overrides)
    assert response.status_code == 201, response.text
    body: dict[str, Any] = response.json()
    return body


async def test_create_returns_the_public_book(client: AsyncClient) -> None:
    body = await create(client)

    assert body == DUNE | {"id": 1}


async def test_create_rejects_a_duplicate_isbn(client: AsyncClient) -> None:
    await create(client)

    response = await client.post("/api/v1/books", json=DUNE)

    assert response.status_code == 409
    assert "9780441172719" in response.json()["detail"]


@pytest.mark.parametrize(
    ("payload", "error_type", "location"),
    [
        ({"title": "Dune", "author": "F. H."}, "missing", ["body", "isbn"]),
        (DUNE | {"isbn": "12345"}, "string_too_short", ["body", "isbn"]),
        (DUNE | {"isbn": "978044117271X"}, "string_pattern_mismatch", ["body", "isbn"]),
        (DUNE | {"price": 10}, "extra_forbidden", ["body", "price"]),
    ],
)
async def test_create_validates_the_body(
    client: AsyncClient, payload: dict[str, Any], error_type: str, location: list[str]
) -> None:
    response = await client.post("/api/v1/books", json=payload)

    assert response.status_code == 422
    error = response.json()["detail"][0]
    assert (error["type"], error["loc"]) == (error_type, location)


async def test_read_returns_the_book(client: AsyncClient) -> None:
    created = await create(client)

    response = await client.get(f"/api/v1/books/{created['id']}")

    assert response.status_code == 200
    assert response.json() == created


async def test_read_unknown_book_is_404(client: AsyncClient) -> None:
    response = await client.get("/api/v1/books/999")

    assert response.status_code == 404
    assert response.json() == {"detail": "Book 999 not found"}


async def test_read_rejects_a_non_positive_id(client: AsyncClient) -> None:
    response = await client.get("/api/v1/books/0")

    assert response.status_code == 422
    assert response.json()["detail"][0]["loc"] == ["path", "book_id"]


async def test_list_pages_through_books(client: AsyncClient) -> None:
    for index in range(3):
        await create(client, isbn=f"978000000000{index}", title=f"Book {index}")

    response = await client.get("/api/v1/books", params={"offset": 1, "limit": 1})

    assert response.status_code == 200
    assert [book["title"] for book in response.json()] == ["Book 1"]


async def test_list_caps_the_page_size(client: AsyncClient) -> None:
    response = await client.get("/api/v1/books", params={"limit": 101})

    assert response.status_code == 422
    assert response.json()["detail"][0]["type"] == "less_than_equal"


async def test_update_changes_only_the_fields_sent(client: AsyncClient) -> None:
    created = await create(client)

    response = await client.patch(
        f"/api/v1/books/{created['id']}", json={"title": "Dune Messiah"}
    )

    assert response.status_code == 200
    assert response.json() == created | {"title": "Dune Messiah"}


async def test_update_rejects_null(client: AsyncClient) -> None:
    created = await create(client)

    response = await client.patch(
        f"/api/v1/books/{created['id']}", json={"title": None}
    )

    assert response.status_code == 422
    assert response.json()["detail"][0]["loc"] == ["body", "title"]


async def test_update_unknown_book_is_404(client: AsyncClient) -> None:
    response = await client.patch("/api/v1/books/999", json={"title": "X"})

    assert response.status_code == 404


async def test_delete_removes_the_book(client: AsyncClient) -> None:
    created = await create(client)

    response = await client.delete(f"/api/v1/books/{created['id']}")

    assert response.status_code == 204
    assert (await client.get(f"/api/v1/books/{created['id']}")).status_code == 404
```

### `tests/unit/test_book_repository.py`

```python
import pytest
from sqlalchemy.ext.asyncio import async_sessionmaker
from sqlmodel.ext.asyncio.session import AsyncSession

from app.data.books import SqlBookRepository
from app.models import Book
from app.services.errors import DuplicateIsbnError

pytestmark = pytest.mark.anyio


async def test_the_unique_constraint_is_reported_as_a_duplicate(
    sessionmaker: async_sessionmaker[AsyncSession],
) -> None:
    async with sessionmaker() as session:
        repository = SqlBookRepository(session)
        await repository.save(Book(title="Dune", author="F. H.", isbn="9780441172719"))

        with pytest.raises(DuplicateIsbnError):
            await repository.save(
                Book(title="Other", author="Someone", isbn="9780441172719")
            )
```

### `tests/unit/test_health.py`

```python
from pathlib import Path

import pytest
from fastapi import FastAPI
from httpx import AsyncClient

from app.db import create_engine, create_sessionmaker
from app.dependencies import get_sessionmaker

pytestmark = pytest.mark.anyio


async def test_liveness_is_ok(client: AsyncClient) -> None:
    response = await client.get("/healthz")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


async def test_readiness_is_ok_when_the_database_answers(client: AsyncClient) -> None:
    response = await client.get("/readyz")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


async def test_readiness_is_503_when_the_database_is_unreachable(
    app: FastAPI, client: AsyncClient, tmp_path: Path
) -> None:
    unreachable = create_engine(f"sqlite+aiosqlite:///{tmp_path}/missing/dir/db.sqlite")
    app.dependency_overrides[get_sessionmaker] = lambda: create_sessionmaker(
        unreachable
    )

    response = await client.get("/readyz")

    assert response.status_code == 503
    assert response.json() == {"status": "unavailable"}
    await unreachable.dispose()
```

### `tests/unit/test_main.py`

```python
import pytest
from asgi_lifespan import LifespanManager
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text

from app import __version__
from app.dependencies import get_sessionmaker
from app.main import create_app, lifespan


@pytest.mark.anyio
async def test_lifespan_creates_a_working_session_factory(app: FastAPI) -> None:
    async with lifespan(app) as state, state["sessionmaker"]() as session:
        connection = await session.connection()
        result = await connection.execute(text("SELECT 1"))
        assert result.scalar_one() == 1


def test_openapi_documents_the_api() -> None:
    schema = create_app().openapi()

    assert schema["info"]["version"] == __version__
    assert "/api/v1/books/{book_id}" in schema["paths"]
    assert "/healthz" in schema["paths"]


@pytest.mark.anyio
async def test_requests_use_the_lifespan_session_factory(app: FastAPI) -> None:
    del app.dependency_overrides[get_sessionmaker]

    async with LifespanManager(app) as manager:
        transport = ASGITransport(app=manager.app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.get("/readyz")

    assert response.status_code == 200
```

### `tests/unit/test_settings.py`

```python
import pytest
from pydantic import SecretStr, ValidationError

from app.settings import Settings, get_settings


@pytest.fixture(autouse=True)
def fresh_settings_cache() -> object:
    get_settings.cache_clear()
    yield None
    get_settings.cache_clear()


def test_settings_come_from_app_prefixed_variables(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("APP_DATABASE_URL", "sqlite+aiosqlite://")

    settings = get_settings()

    assert settings.database_url.get_secret_value() == "sqlite+aiosqlite://"
    assert settings.database_echo is False
    assert get_settings() is settings


def test_the_database_url_is_required(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("APP_DATABASE_URL", raising=False)

    with pytest.raises(ValidationError) as error:
        Settings(_env_file=None)

    assert error.value.errors()[0]["loc"] == ("database_url",)


def test_secrets_are_masked() -> None:
    settings = Settings(
        _env_file=None, database_url=SecretStr("postgresql+asyncpg://u:pw@db/app")
    )

    assert "pw" not in repr(settings)
```

### `tests/unit/test_db.py`

```python
from pathlib import Path

import pytest
from sqlalchemy import text

from app.db import create_engine

pytestmark = pytest.mark.anyio


async def test_postgresql_engines_pre_ping_their_connections() -> None:
    engine = create_engine("postgresql+asyncpg://user:secret@localhost:5432/app")

    assert engine.pool._pre_ping is True
    await engine.dispose()


async def test_sqlite_file_databases_enforce_foreign_keys(tmp_path: Path) -> None:
    engine = create_engine(f"sqlite+aiosqlite:///{tmp_path}/app.db")

    async with engine.connect() as connection:
        result = await connection.execute(text("PRAGMA foreign_keys"))
        assert result.scalar_one() == 1
    await engine.dispose()
```
