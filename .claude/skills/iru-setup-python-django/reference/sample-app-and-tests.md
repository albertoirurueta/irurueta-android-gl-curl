# Sample `catalog` app and the test suite

The model → service → admin slice every `api` choice shares, the factories and fixtures, the fast-tier tests (SQLite in memory) and — `database: postgresql` only — the `integration` tier on a testcontainers PostgreSQL. The API layer and its tests are in `api-drf.md` / `api-ninja.md`. Tests are typed (mypy strict): use `XFactory.create()`/`.build()` (typed through factory_boy's generics) rather than calling the factory class.

Templates use this skill's tag convention (see SKILL.md, "Template conventions"): a line ending in `# [api: drf]` / `# [database: postgresql]` is kept only for that choice (drop the tag), a line that is only `# [api: ninja]` opens a block kept only for that choice and `# [end]` closes it, and `<placeholders>` are replaced with Step 2's values.

## `apps/catalog/__init__.py`

```python
"""Sample app: a small book catalog showing the model → service → API layering."""
```

## `apps/catalog/apps.py`

```python
"""App configuration for ``apps.catalog``."""

from django.apps import AppConfig


class CatalogConfig(AppConfig):
    """Book catalog."""

    name = "apps.catalog"
    label = "catalog"
    verbose_name = "Catalog"
```

## `apps/catalog/models.py`

```python
"""Catalog models."""

from django.db import models

from apps.core.models import TimeStampedModel


class Book(TimeStampedModel):
    """A book identified by its normalized ISBN."""

    title = models.CharField(max_length=200)
    author = models.CharField(max_length=200)
    isbn = models.CharField("ISBN", max_length=13, unique=True)
    published_on = models.DateField(null=True, blank=True)

    class Meta:
        ordering = ["title", "id"]

    def __str__(self) -> str:
        """Return the title."""
        return self.title
```

## `apps/catalog/services.py`

```python
"""Catalog business rules; views, API endpoints and commands all call these."""

import re
from datetime import date

from django.db import IntegrityError, transaction

from apps.catalog.models import Book

_ISBN_RE = re.compile(r"^(?:\d{9}[\dX]|\d{13})$")


class BookAlreadyExistsError(Exception):
    """A book with the same ISBN is already in the catalog."""

    def __init__(self, isbn: str) -> None:
        """Store the conflicting ISBN.

        Args:
            isbn: The normalized ISBN that already exists.
        """
        super().__init__(f"A book with ISBN {isbn} already exists.")
        self.isbn = isbn


def normalize_isbn(raw: str) -> str:
    """Strip separators from an ISBN-10/13 and validate its shape.

    Args:
        raw: ISBN as typed, possibly with hyphens or spaces.

    Returns:
        The ISBN without separators, upper-cased.

    Raises:
        ValueError: The value is not 10 or 13 characters of digits (ISBN-10 may
            end in ``X``).
    """
    isbn = re.sub(r"[\s-]", "", raw).upper()
    if not _ISBN_RE.fullmatch(isbn):
        msg = "Enter a valid ISBN-10 or ISBN-13."
        raise ValueError(msg)
    return isbn


def create_book(
    *, title: str, author: str, isbn: str, published_on: date | None = None
) -> Book:
    """Add a book to the catalog.

    Args:
        title: Book title.
        author: Author name.
        isbn: ISBN-10 or ISBN-13, separators allowed.
        published_on: Publication date, if known.

    Returns:
        The saved book.

    Raises:
        ValueError: The ISBN is malformed.
        BookAlreadyExistsError: A book with the same ISBN exists.
    """
    normalized = normalize_isbn(isbn)
    try:
        with transaction.atomic():
            return Book.objects.create(
                title=title.strip(),
                author=author.strip(),
                isbn=normalized,
                published_on=published_on,
            )
    except IntegrityError as exc:
        raise BookAlreadyExistsError(normalized) from exc
```

## `apps/catalog/admin.py`

```python
"""Admin registration for the catalog."""

from django.contrib import admin

from apps.catalog.models import Book


@admin.register(Book)
class BookAdmin(admin.ModelAdmin[Book]):
    """Searchable book list."""

    list_display = ["title", "author", "isbn", "published_on"]
    search_fields = ["title", "author", "isbn"]
    ordering = ["title"]
```

## `tests/__init__.py`

```python
"""Tests."""
```

## `tests/conftest.py`

```python
"""Shared fixtures.

The fast tier runs on SQLite in memory (``config.settings.test``). When the
selected tests include any marked ``integration``, the whole session's test
database is created on a throwaway PostgreSQL container instead
(testcontainers; needs Docker).
"""

# [database: postgresql]
from collections.abc import Iterator

# [end]
import pytest
from django.conf import settings  # [database: postgresql]
from django.db import connections  # [database: postgresql]
from django.test import Client

from apps.accounts.models import User
from tests.factories import UserFactory

# [database: postgresql]
POSTGRES_IMAGE = "postgres:18-alpine"


@pytest.fixture(scope="session")
def django_db_modify_db_settings(
    request: pytest.FixtureRequest,
    django_db_modify_db_settings_parallel_suffix: None,
) -> Iterator[None]:
    """Point the test database at PostgreSQL when integration tests are selected."""
    if not any(
        item.get_closest_marker("integration") for item in request.session.items
    ):
        yield
        return

    from testcontainers.community.postgres import PostgresContainer

    with PostgresContainer(POSTGRES_IMAGE, driver=None) as postgres:
        settings.DATABASES["default"].update(
            {
                "ENGINE": "django.db.backends.postgresql",
                "HOST": postgres.get_container_host_ip(),
                "PORT": str(postgres.get_exposed_port(5432)),
                "NAME": postgres.dbname,
                "USER": postgres.username,
                "PASSWORD": postgres.password,
            }
        )
        # Drop the connection opened on the SQLite settings so the next query
        # builds a PostgreSQL one from the updated dictionary.
        connections["default"].close()
        del connections["default"]
        yield

# [end]

@pytest.fixture
def user(db: None) -> User:
    """A saved regular user."""
    return UserFactory.create()


@pytest.fixture
def user_client(client: Client, user: User) -> Client:
    """A test client logged in as ``user``."""
    client.force_login(user)
    return client
```

## `tests/factories.py`

```python
"""factory_boy factories, one per model."""

import factory

from apps.accounts.models import User
from apps.catalog.models import Book


class UserFactory(factory.django.DjangoModelFactory[User]):
    """Regular active user with an unusable password (use ``force_login``)."""

    class Meta:
        model = User
        skip_postgeneration_save = True

    username = factory.Sequence(lambda n: f"user{n}")
    email = factory.LazyAttribute(lambda user: f"{user.username}@example.com")
    password = factory.django.Password(None)


class BookFactory(factory.django.DjangoModelFactory[Book]):
    """Book with a unique, valid ISBN-13."""

    class Meta:
        model = Book

    title = factory.Sequence(lambda n: f"Book {n}")
    author = "Ada Lovelace"
    isbn = factory.Sequence(lambda n: f"978{n:010d}")
```

## `tests/config/__init__.py`

```python
"""Tests."""
```

## `tests/config/test_env.py`

```python
"""Tests for the environment helpers used by the settings modules."""

from pathlib import Path

import pytest
from django.core.exceptions import ImproperlyConfigured

from config.env import (
    default_database,
    env,
    env_bool,
    env_int,
    env_list,
    postgresql_database,
    sqlite_database,
)


def test_env_returns_value_or_default(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("IRU_VALUE", "set")
    monkeypatch.delenv("IRU_MISSING", raising=False)

    assert env("IRU_VALUE") == "set"
    assert env("IRU_MISSING", "fallback") == "fallback"


def test_env_without_default_fails_fast(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("IRU_MISSING", raising=False)

    with pytest.raises(ImproperlyConfigured, match="IRU_MISSING"):
        env("IRU_MISSING")


@pytest.mark.parametrize(
    ("raw", "expected"),
    [("1", True), ("true", True), ("ON", True), ("0", False), ("no", False)],
)
def test_env_bool_parses_values(
    monkeypatch: pytest.MonkeyPatch, raw: str, expected: bool
) -> None:
    monkeypatch.setenv("IRU_FLAG", raw)

    assert env_bool("IRU_FLAG", default=not expected) is expected


def test_env_bool_uses_default_and_rejects_garbage(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("IRU_FLAG", raising=False)
    assert env_bool("IRU_FLAG", default=True) is True

    monkeypatch.setenv("IRU_FLAG", "maybe")
    with pytest.raises(ImproperlyConfigured, match="boolean"):
        env_bool("IRU_FLAG", default=False)


def test_env_int(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("IRU_NUMBER", raising=False)
    assert env_int("IRU_NUMBER", default=7) == 7

    monkeypatch.setenv("IRU_NUMBER", "42")
    assert env_int("IRU_NUMBER", default=7) == 42

    monkeypatch.setenv("IRU_NUMBER", "forty-two")
    with pytest.raises(ImproperlyConfigured, match="integer"):
        env_int("IRU_NUMBER", default=7)


def test_env_list_splits_and_trims(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("IRU_HOSTS", " a.example.com, ,b.example.com ")

    assert env_list("IRU_HOSTS") == ["a.example.com", "b.example.com"]
    assert env_list("IRU_UNSET_LIST", "") == []


def test_default_database_is_sqlite_without_postgres_host(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.delenv("POSTGRES_HOST", raising=False)
    monkeypatch.delenv("DJANGO_SQLITE_PATH", raising=False)

    database = default_database(tmp_path / "db.sqlite3")

    assert database["ENGINE"] == "django.db.backends.sqlite3"
    assert database["NAME"] == str(tmp_path / "db.sqlite3")


def test_default_database_is_postgresql_with_postgres_host(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setenv("POSTGRES_HOST", "db")
    monkeypatch.setenv("POSTGRES_PASSWORD", "secret")
    monkeypatch.delenv("POSTGRES_PORT", raising=False)

    database = default_database(tmp_path / "db.sqlite3")

    assert database["ENGINE"] == "django.db.backends.postgresql"
    assert database["HOST"] == "db"
    assert database["PORT"] == "5432"


def test_postgresql_database_requires_password(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("POSTGRES_HOST", "db")
    monkeypatch.delenv("POSTGRES_PASSWORD", raising=False)

    with pytest.raises(ImproperlyConfigured, match="POSTGRES_PASSWORD"):
        postgresql_database()


def test_sqlite_database_path_can_be_overridden(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setenv("DJANGO_SQLITE_PATH", "/data/db.sqlite3")

    assert sqlite_database(tmp_path / "db.sqlite3")["NAME"] == "/data/db.sqlite3"
```

## `tests/core/__init__.py`

```python
"""Tests."""
```

## `tests/core/test_health.py`

```python
"""Tests for the health probes."""

from unittest.mock import patch

import pytest
from django.db import OperationalError
from django.test import Client
from django.urls import reverse

from config import __version__


def test_healthz_reports_ok_without_touching_the_database(client: Client) -> None:
    response = client.get(reverse("healthz"))

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "version": __version__}
    assert "no-cache" in response["Cache-Control"]


def test_healthz_rejects_post(client: Client) -> None:
    assert client.post(reverse("healthz")).status_code == 405


@pytest.mark.django_db
def test_readyz_reports_ok_when_the_database_answers(client: Client) -> None:
    response = client.get(reverse("readyz"))

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


@pytest.mark.django_db
def test_readyz_reports_503_when_the_database_is_down(client: Client) -> None:
    with patch("apps.core.views.connection.cursor", side_effect=OperationalError):
        response = client.get(reverse("readyz"))

    assert response.status_code == 503
    assert response.json() == {"status": "unavailable"}
```

## `tests/accounts/__init__.py`

```python
"""Tests."""
```

## `tests/accounts/test_models.py`

```python
"""Tests for the custom user model."""

import pytest
from django.contrib.auth import get_user_model
from django.test import Client
from django.urls import reverse

from apps.accounts.models import User
from tests.factories import UserFactory


def test_auth_user_model_is_the_custom_user() -> None:
    assert get_user_model() is User


def test_str_is_the_username() -> None:
    assert str(UserFactory.build(username="ada")) == "ada"


@pytest.mark.django_db
def test_admin_lists_users(admin_client: Client) -> None:
    UserFactory.create(username="grace")

    response = admin_client.get(reverse("admin:accounts_user_changelist"))

    assert response.status_code == 200
    assert b"grace" in response.content
```

## `tests/catalog/__init__.py`

```python
"""Tests."""
```

## `tests/catalog/test_services.py`

```python
"""Tests for the catalog services (fast tier, SQLite in memory)."""

from datetime import date

import pytest
from django.test import Client
from django.urls import reverse

from apps.catalog.models import Book
from apps.catalog.services import BookAlreadyExistsError, create_book, normalize_isbn
from tests.factories import BookFactory


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("978-0-306-40615-7", "9780306406157"),
        ("0 306 40615 2", "0306406152"),
        ("080442957x", "080442957X"),
    ],
)
def test_normalize_isbn_strips_separators(raw: str, expected: str) -> None:
    assert normalize_isbn(raw) == expected


@pytest.mark.parametrize("raw", ["", "12345", "978-0-306-40615-7-1", "ISBN"])
def test_normalize_isbn_rejects_malformed_values(raw: str) -> None:
    with pytest.raises(ValueError, match="valid ISBN"):
        normalize_isbn(raw)


@pytest.mark.django_db
def test_create_book_saves_normalized_values() -> None:
    book = create_book(
        title="  Notes on the Analytical Engine ",
        author="Ada Lovelace",
        isbn="978-0-306-40615-7",
        published_on=date(1843, 10, 1),
    )

    saved = Book.objects.get(pk=book.pk)
    assert saved.title == "Notes on the Analytical Engine"
    assert saved.isbn == "9780306406157"
    assert str(saved) == "Notes on the Analytical Engine"


@pytest.mark.django_db
def test_create_book_rejects_a_duplicate_isbn() -> None:
    BookFactory.create(isbn="9780306406157")

    with pytest.raises(BookAlreadyExistsError) as excinfo:
        create_book(title="Copy", author="Someone", isbn="978-0306406157")

    assert excinfo.value.isbn == "9780306406157"
    assert Book.objects.count() == 1


@pytest.mark.django_db
def test_admin_searches_books(admin_client: Client) -> None:
    BookFactory.create(title="Sketch of the Engine")

    response = admin_client.get(
        reverse("admin:catalog_book_changelist"), {"q": "Sketch"}
    )

    assert response.status_code == 200
    assert b"Sketch of the Engine" in response.content
```

## `tests/integration/__init__.py`

```python
"""Tests."""
```

## `tests/integration/test_catalog_postgresql.py`

```python
"""Integration tier: the catalog against a real PostgreSQL (testcontainers).

Run with ``uv run pytest -m integration`` (needs Docker). The session's test
database is created on the container by ``tests/conftest.py``, with every
migration applied, so these tests also prove the migrations run on PostgreSQL.
"""

import pytest
from django.db import connection
from django.test import Client
from django.urls import reverse

from apps.catalog.models import Book
from apps.catalog.services import BookAlreadyExistsError, create_book

pytestmark = [pytest.mark.integration, pytest.mark.django_db]


def test_runs_on_postgresql() -> None:
    assert connection.vendor == "postgresql"


def test_unique_isbn_is_enforced_by_the_database() -> None:
    create_book(title="Original", author="A", isbn="9780306406157")

    with pytest.raises(BookAlreadyExistsError):
        create_book(title="Copy", author="B", isbn="978-0-306-40615-7")

    assert Book.objects.count() == 1


def test_readyz_reaches_the_database(client: Client) -> None:
    assert client.get(reverse("readyz")).status_code == 200
```
