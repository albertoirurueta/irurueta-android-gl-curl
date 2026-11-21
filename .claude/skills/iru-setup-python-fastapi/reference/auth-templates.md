# Auth templates (`auth: yes`)

A first-party OAuth2 password flow protecting the sample's write endpoints (irurueta docs:
`backend/fastapi/oauth2-password-and-jwt.adoc`): **PyJWT** (HS256, `algorithms=[...]` allow-list, `exp`+`sub`
required) and **pwdlib[argon2]** (`PasswordHash.recommended()`), never python-jose/passlib. The account is a single
admin configured by settings (`APP_ADMIN_USERNAME`, default `admin`; `APP_ADMIN_PASSWORD_HASH`, an Argon2 hash),
so no user table or migration is added; reads stay public. `POST /token` is a `def` endpoint because hashing is
blocking; unknown usernames are verified against a dummy hash so both failures take the same time and answer the
same 401 with `WWW-Authenticate: Bearer`. Tests mint tokens with the app's own `create_access_token` (an expired one
via `now=`), hash the test password once per session (Argon2 is slow on purpose), and use `anonymous_client` for the
401 paths.

Dependencies: `uv add "pyjwt>=2.15,<3" "pwdlib[argon2]>=0.3,<0.4"`. New settings (required, no default):
`APP_JWT_SECRET` (`openssl rand -hex 32`) and `APP_ADMIN_PASSWORD_HASH`
(`uv run python -c "import app.security as s; print(s.hash_password(input()))"`). In `.env` the hash **must be
single-quoted** (`APP_ADMIN_PASSWORD_HASH='$argon2id$v=19$...'`) or Compose interpolates its `$` signs (verified:
quoted, `docker compose config` shows it intact). Add both to `.env.example` with placeholder values.

The blocks are the SQLModel variant (verified: 43 tests incl. integration, 100% coverage, Sphinx, image built,
container: write without token → 401, `/token` → bearer → `POST /api/v1/books` → 201). With `orm: sqlalchemy` the
same files apply with that shape's imports (`AsyncSession` from `sqlalchemy.ext.asyncio`, `Base.metadata` instead of
`SQLModel.metadata` in the two conftest files) — also verified. `migrations/env.py` is unaffected: it builds
`DatabaseSettings`, not `Settings`, so the CI migration job still needs only `APP_DATABASE_URL`.

### `app/settings.py (auth: yes)`

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

    jwt_secret: SecretStr = Field(
        description="HS256 signing key: 32+ random bytes (openssl rand -hex 32)."
    )
    jwt_algorithm: str = "HS256"
    access_token_minutes: int = Field(default=30, ge=1)
    admin_username: str = "admin"
    admin_password_hash: SecretStr = Field(
        description="Argon2 hash of the admin password (see app.security)."
    )


@lru_cache
def get_settings() -> Settings:
    """Return the process-wide settings, built and validated once on first use.

    Returns:
        The cached :class:`Settings` instance.
    """
    return Settings()
```

### `app/security.py (auth: yes)`

```python
"""Password hashing (pwdlib, Argon2) and access tokens (PyJWT).

Generate the admin password hash for ``APP_ADMIN_PASSWORD_HASH`` with::

    uv run python -c "import app.security as s; print(s.hash_password(input()))"
"""

from datetime import UTC, datetime, timedelta

import jwt
from pwdlib import PasswordHash

from app.settings import Settings

_password_hash = PasswordHash.recommended()
# Verified against for unknown usernames so both failure paths take the same time.
_DUMMY_HASH = _password_hash.hash("not-a-real-password")


class InvalidTokenError(Exception):
    """The bearer token is malformed, expired, or not signed by this service."""


def hash_password(password: str) -> str:
    """Hash a password with Argon2id.

    Args:
        password: The plain-text password.

    Returns:
        The encoded hash, safe to store.
    """
    return _password_hash.hash(password)


def authenticate(username: str, password: str, settings: Settings) -> bool:
    """Check credentials against the configured admin account.

    Hashing is CPU- and memory-heavy: call this from a ``def`` endpoint (threadpool).

    Args:
        username: The submitted username.
        password: The submitted password.
        settings: The application settings holding the admin account.

    Returns:
        Whether the credentials are valid.
    """
    if username != settings.admin_username:
        _password_hash.verify(password, _DUMMY_HASH)
        return False
    return _password_hash.verify(
        password, settings.admin_password_hash.get_secret_value()
    )


def create_access_token(
    subject: str, settings: Settings, *, now: datetime | None = None
) -> str:
    """Sign a short-lived access token.

    Args:
        subject: The ``sub`` claim (the username).
        settings: The application settings holding the signing key.
        now: The issue time (tests pass one to build expired tokens).

    Returns:
        The encoded JWT.
    """
    issued_at = now or datetime.now(UTC)
    claims = {
        "sub": subject,
        "iat": issued_at,
        "exp": issued_at + timedelta(minutes=settings.access_token_minutes),
    }
    return jwt.encode(
        claims,
        settings.jwt_secret.get_secret_value(),
        algorithm=settings.jwt_algorithm,
    )


def decode_subject(token: str, settings: Settings) -> str:
    """Verify a token and return its subject.

    Args:
        token: The encoded JWT.
        settings: The application settings holding the signing key.

    Returns:
        The ``sub`` claim.

    Raises:
        InvalidTokenError: If the signature, expiry, or required claims are invalid.
    """
    try:
        claims = jwt.decode(
            token,
            settings.jwt_secret.get_secret_value(),
            algorithms=[settings.jwt_algorithm],
            options={"require": ["exp", "sub"]},
        )
    except jwt.InvalidTokenError as exc:
        raise InvalidTokenError(str(exc)) from exc
    subject: str = claims["sub"]
    return subject
```

### `app/models/auth.py (auth: yes)`

```python
"""OAuth2 token response model."""

from typing import Literal

from pydantic import BaseModel


class Token(BaseModel):
    """An OAuth2 bearer token (field names fixed by RFC 6749)."""

    access_token: str
    token_type: Literal["bearer"] = "bearer"  # noqa: S105 (not a secret)
```

### `app/routers/auth.py (auth: yes)`

```python
"""``POST /token``: the OAuth2 password flow for the first-party admin account."""

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.security import OAuth2PasswordRequestForm

from app.dependencies import SettingsDep
from app.models import Token
from app.security import authenticate, create_access_token

router = APIRouter(tags=["auth"])


@router.post(
    "/token",
    responses={status.HTTP_401_UNAUTHORIZED: {"description": "Wrong credentials."}},
)
def login(
    form: Annotated[OAuth2PasswordRequestForm, Depends()], settings: SettingsDep
) -> Token:
    """Exchange a username and password (form-encoded) for a bearer token."""
    if not authenticate(form.username, form.password, settings):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return Token(access_token=create_access_token(form.username, settings))
```

### `app/routers/books.py (auth: yes)`

```python
"""``/books`` endpoints: translate HTTP to service calls and back."""

from typing import Annotated, Any

from fastapi import APIRouter, Depends, Path, Query, status

from app.dependencies import BookServiceDep, get_current_user
from app.models import BookCreate, BookPublic, BookUpdate

router = APIRouter(prefix="/books", tags=["books"])
# Reads are public; every write needs a bearer token from POST /token.
WRITE = [Depends(get_current_user)]
UNAUTHORIZED: dict[int | str, dict[str, Any]] = {
    status.HTTP_401_UNAUTHORIZED: {"description": "Missing or invalid bearer token."}
}

BookId = Annotated[int, Path(ge=1, description="Primary key of a book.")]
NOT_FOUND: dict[int | str, dict[str, Any]] = {
    status.HTTP_404_NOT_FOUND: {"description": "No book has this id."}
}


@router.post(
    "",
    status_code=status.HTTP_201_CREATED,
    dependencies=WRITE,
    responses={
        **UNAUTHORIZED,
        status.HTTP_409_CONFLICT: {"description": "The ISBN is already taken."},
    },
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


@router.patch("/{book_id}", dependencies=WRITE, responses=NOT_FOUND | UNAUTHORIZED)
async def update_book(
    book_id: BookId, changes: BookUpdate, service: BookServiceDep
) -> BookPublic:
    """Change some fields of a book."""
    return BookPublic.model_validate(await service.update(book_id, changes))


@router.delete(
    "/{book_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    dependencies=WRITE,
    responses=NOT_FOUND | UNAUTHORIZED,
)
async def delete_book(book_id: BookId, service: BookServiceDep) -> None:
    """Remove a book from the catalogue."""
    await service.delete(book_id)
```

### `app/dependencies.py (auth: yes)`

```python
"""Dependency providers and their ``Annotated`` aliases.

This is the only module wiring the web layer to the data layer; tests replace any
provider through ``app.dependency_overrides``.
"""

from collections.abc import AsyncIterator
from typing import Annotated

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import OAuth2PasswordBearer
from sqlalchemy.ext.asyncio import async_sessionmaker
from sqlmodel.ext.asyncio.session import AsyncSession

from app.data.books import SqlBookRepository
from app.security import InvalidTokenError, decode_subject
from app.services.books import BookService
from app.services.ports import BookRepository
from app.settings import Settings, get_settings

SettingsDep = Annotated[Settings, Depends(get_settings)]


oauth2_scheme = OAuth2PasswordBearer(tokenUrl="token")


def get_current_user(
    token: Annotated[str, Depends(oauth2_scheme)], settings: SettingsDep
) -> str:
    """Authenticate the request's bearer token.

    Args:
        token: The bearer token (a missing one is a 401 from ``oauth2_scheme``).
        settings: The application settings holding the signing key.

    Returns:
        The authenticated username.

    Raises:
        HTTPException: 401 if the token is invalid or expired.
    """
    try:
        return decode_subject(token, settings)
    except InvalidTokenError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Could not validate credentials",
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc


CurrentUser = Annotated[str, Depends(get_current_user)]


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

### `tests/conftest.py (auth: yes)`

```python
"""Fixtures shared by every test tier."""

import secrets
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
from app.security import create_access_token, hash_password
from app.settings import Settings, get_settings

SQLITE_MEMORY_URL = "sqlite+aiosqlite://"
ADMIN_PASSWORD = secrets.token_urlsafe(16)
ADMIN_PASSWORD_HASH = hash_password(ADMIN_PASSWORD)  # once: Argon2 is slow on purpose


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


@pytest.fixture
def settings() -> Settings:
    # _env_file=None: never read a developer's .env during tests.
    return Settings(
        _env_file=None,
        database_url=SecretStr(SQLITE_MEMORY_URL),
        jwt_secret=SecretStr(secrets.token_hex(32)),
        admin_password_hash=SecretStr(ADMIN_PASSWORD_HASH),
    )


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
async def anonymous_client(app: FastAPI) -> AsyncIterator[AsyncClient]:
    """An HTTP client calling the app in-process (the lifespan is not run)."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as http:
        yield http


@pytest.fixture
def client(anonymous_client: AsyncClient, settings: Settings) -> AsyncClient:
    """The same client, authenticated with a token minted by the app's own signer."""
    token = create_access_token(settings.admin_username, settings)
    anonymous_client.headers["Authorization"] = f"Bearer {token}"
    return anonymous_client
```

### `tests/integration/conftest.py (auth: yes)`

```python
"""Integration tier: a real PostgreSQL started by testcontainers (needs Docker)."""

import secrets
from collections.abc import AsyncIterator, Iterator
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from asgi_lifespan import LifespanManager
from httpx import ASGITransport, AsyncClient
from pydantic import SecretStr
from sqlalchemy import text
from testcontainers.community.postgres import PostgresContainer

from app.db import create_engine
from app.main import create_app
from app.security import create_access_token, hash_password
from app.settings import Settings, get_settings

ALEMBIC_INI = Path(__file__).resolve().parents[2] / "alembic.ini"
POSTGRES_IMAGE = "postgres:18-alpine"


@pytest.fixture(scope="session")
def database_url() -> Iterator[str]:
    """One PostgreSQL container per test run, migrated to head with Alembic."""
    with PostgresContainer(POSTGRES_IMAGE, driver="asyncpg") as postgres:
        url = postgres.get_connection_url()
        config = Config(str(ALEMBIC_INI))
        config.set_main_option("sqlalchemy.url", url.replace("%", "%%"))
        command.upgrade(config, "head")
        yield url


@pytest.fixture
async def pg_client(database_url: str) -> AsyncIterator[AsyncClient]:
    """The real application (real lifespan, no overrides but settings) on PostgreSQL."""
    engine = create_engine(database_url)
    async with engine.begin() as connection:
        await connection.execute(text("TRUNCATE TABLE book RESTART IDENTITY"))
    await engine.dispose()

    app = create_app()
    settings = Settings(
        _env_file=None,
        database_url=SecretStr(database_url),
        jwt_secret=SecretStr(secrets.token_hex(32)),
        admin_password_hash=SecretStr(hash_password("unused")),
    )
    app.dependency_overrides[get_settings] = lambda: settings
    async with LifespanManager(app) as manager:
        transport = ASGITransport(app=manager.app)
        token = create_access_token(settings.admin_username, settings)
        headers = {"Authorization": f"Bearer {token}"}
        async with AsyncClient(
            transport=transport, base_url="http://test", headers=headers
        ) as client:
            yield client
```

### `tests/unit/test_auth.py (auth: yes)`

```python
from datetime import UTC, datetime, timedelta

import pytest
from httpx import AsyncClient

from app.security import create_access_token
from app.settings import Settings
from tests.conftest import ADMIN_PASSWORD

pytestmark = pytest.mark.anyio

DUNE = {"title": "Dune", "author": "Frank Herbert", "isbn": "9780441172719"}


async def test_login_returns_a_working_token(anonymous_client: AsyncClient) -> None:
    response = await anonymous_client.post(
        "/token", data={"username": "admin", "password": ADMIN_PASSWORD}
    )

    assert response.status_code == 200
    token = response.json()["access_token"]
    created = await anonymous_client.post(
        "/api/v1/books", json=DUNE, headers={"Authorization": f"Bearer {token}"}
    )
    assert created.status_code == 201


@pytest.mark.parametrize(
    ("username", "password"), [("admin", "wrong"), ("nobody", ADMIN_PASSWORD)]
)
async def test_login_rejects_bad_credentials(
    anonymous_client: AsyncClient, username: str, password: str
) -> None:
    response = await anonymous_client.post(
        "/token", data={"username": username, "password": password}
    )

    assert response.status_code == 401
    assert response.headers["WWW-Authenticate"] == "Bearer"


async def test_writes_need_a_token(anonymous_client: AsyncClient) -> None:
    response = await anonymous_client.post("/api/v1/books", json=DUNE)

    assert response.status_code == 401
    assert response.json() == {"detail": "Not authenticated"}


async def test_reads_are_public(anonymous_client: AsyncClient) -> None:
    assert (await anonymous_client.get("/api/v1/books")).status_code == 200


async def test_expired_tokens_are_rejected(
    anonymous_client: AsyncClient, settings: Settings
) -> None:
    issued = datetime.now(UTC) - timedelta(minutes=settings.access_token_minutes + 1)
    token = create_access_token("admin", settings, now=issued)

    response = await anonymous_client.post(
        "/api/v1/books", json=DUNE, headers={"Authorization": f"Bearer {token}"}
    )

    assert response.status_code == 401
    assert response.json() == {"detail": "Could not validate credentials"}
```

### `tests/unit/test_settings.py (auth: yes)`

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
    monkeypatch.setenv("APP_JWT_SECRET", "test-signing-key")
    monkeypatch.setenv("APP_ADMIN_PASSWORD_HASH", "$argon2id$placeholder")

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
        _env_file=None,
        database_url=SecretStr("postgresql+asyncpg://u:pw@db/app"),
        jwt_secret=SecretStr("signing-key"),
        admin_password_hash=SecretStr("hash"),
    )

    assert "pw" not in repr(settings)
    assert "signing-key" not in repr(settings)
```

### Small edits (shown as diffs against the base templates)

```diff
--- a/app/models/__init__.py
+++ b/app/models/__init__.py
@@ -15,2 +15,3 @@
 
+from app.models.auth import Token  # noqa: E402
 from app.models.book import Book, BookCreate, BookPublic, BookUpdate  # noqa: E402
@@ -18,2 +19,2 @@
 
-__all__ = ["Book", "BookCreate", "BookPublic", "BookUpdate", "HealthStatus"]
+__all__ = ["Book", "BookCreate", "BookPublic", "BookUpdate", "HealthStatus", "Token"]
--- a/app/main.py
+++ b/app/main.py
@@ -13,3 +13,3 @@
 from app.db import create_engine, create_sessionmaker
-from app.routers import books, health
+from app.routers import auth, books, health
 from app.services.errors import BookNotFoundError, DuplicateIsbnError
@@ -57,2 +57,3 @@
     app.include_router(health.router)
+    app.include_router(auth.router)
     app.include_router(books.router, prefix="/api/v1")
--- a/docs/api/index.rst
+++ b/docs/api/index.rst
@@ -19,2 +19,4 @@
 
+.. automodule:: app.security
+
 Web layer
@@ -25,2 +27,4 @@
 .. automodule:: app.routers.health
+
+.. automodule:: app.routers.auth
 
@@ -42,2 +46,4 @@
 
+.. automodule:: app.models.auth
+
 .. automodule:: app.models.health
--- a/compose.yaml
+++ b/compose.yaml
@@ -33,3 +33,6 @@
     build: .
-    environment: *app-env
+    environment:
+      <<: *app-env
+      APP_JWT_SECRET: ${APP_JWT_SECRET:?set APP_JWT_SECRET in .env}
+      APP_ADMIN_PASSWORD_HASH: ${APP_ADMIN_PASSWORD_HASH:?set APP_ADMIN_PASSWORD_HASH in .env}
     ports:
--- a/.env.example
+++ b/.env.example
@@ -9 +9,8 @@
 POSTGRES_PASSWORD=change-me
+
+# auth: yes — required, no defaults. Generate the secret with `openssl rand -hex 32` and the hash with
+# uv run python -c "import app.security as s; print(s.hash_password(input()))"
+# Keep the hash single-quoted: Compose would otherwise interpolate its $ signs.
+APP_JWT_SECRET=change-me
+APP_ADMIN_USERNAME=admin
+APP_ADMIN_PASSWORD_HASH='$argon2id$change-me'
```
