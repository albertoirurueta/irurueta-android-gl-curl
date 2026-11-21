# API layer: Django Ninja (`api: ninja`)

One `NinjaAPI` in `config/api.py` (mounted at `/api/v1/` by `config/urls.py`; Swagger UI at `/api/v1/docs`, schema at `/api/v1/openapi.json`, exported by `manage.py export_openapi_schema --api config.api.api`), one `Router` per app. Note `apps/catalog/api.py` is a module here, while DRF uses an `apps/catalog/api/` package — never both.

Templates use this skill's tag convention (see SKILL.md, "Template conventions"): a line ending in `# [api: drf]` / `# [database: postgresql]` is kept only for that choice (drop the tag), a line that is only `# [api: ninja]` opens a block kept only for that choice and `# [end]` closes it, and `<placeholders>` are replaced with Step 2's values.

## `config/api.py`

```python
"""The versioned Django Ninja API: one ``NinjaAPI`` instance, one router per app."""

from ninja import NinjaAPI

from apps.catalog.api import router as books_router
from config import __version__

api = NinjaAPI(
    title="<name> API",
    version=__version__,
    description="<description>",
    urls_namespace="api-v1",
)
api.add_router("/books/", books_router)
```

## `apps/catalog/schemas.py`

```python
"""Request and response schemas of the catalog API (Django Ninja)."""

from datetime import date

from ninja import ModelSchema, Schema
from pydantic import field_validator

from apps.catalog.models import Book
from apps.catalog.services import normalize_isbn


class BookIn(Schema):
    """Payload to create a book."""

    title: str
    author: str
    isbn: str
    published_on: date | None = None

    @field_validator("isbn")
    @classmethod
    def _normalize_isbn(cls, value: str) -> str:
        return normalize_isbn(value)


class BookOut(ModelSchema):
    """Book representation."""

    class Meta:
        model = Book
        fields = ["id", "title", "author", "isbn", "published_on", "created_at"]


class Message(Schema):
    """Error message body."""

    detail: str
```

## `apps/catalog/api.py`

```python
"""Catalog endpoints (Django Ninja router mounted at ``/api/v1/books/``)."""

from django.db.models import QuerySet
from django.http import HttpRequest
from django.shortcuts import get_object_or_404
from ninja import Router, Status
from ninja.pagination import PageNumberPagination, paginate
from ninja.security import django_auth

from apps.catalog.models import Book
from apps.catalog.schemas import BookIn, BookOut, Message
from apps.catalog.services import BookAlreadyExistsError, create_book

router = Router(tags=["books"])


@router.get("/", response=list[BookOut])
@paginate(PageNumberPagination)
def list_books(request: HttpRequest) -> QuerySet[Book]:
    """List books ordered by title."""
    return Book.objects.order_by("title", "id")


@router.get("/{book_id}", response=BookOut)
def get_book(request: HttpRequest, book_id: int) -> Book:
    """Return one book (404 when it doesn't exist)."""
    return get_object_or_404(Book, pk=book_id)


@router.post("/", response={201: BookOut, 409: Message}, auth=django_auth)
def add_book(request: HttpRequest, payload: BookIn) -> Status[Book | Message]:
    """Create a book (authenticated users only; 409 on a duplicate ISBN)."""
    try:
        book = create_book(**payload.model_dump())
    except BookAlreadyExistsError as exc:
        return Status(409, Message(detail=str(exc)))
    return Status(201, book)
```

## `tests/catalog/test_api_ninja.py`

```python
"""Tests for the catalog API (Django Ninja), through the full Django stack."""

import pytest
from django.test import Client
from pytest_django import DjangoAssertNumQueries

from apps.catalog.models import Book
from tests.factories import BookFactory

pytestmark = pytest.mark.django_db

BOOKS_URL = "/api/v1/books/"


def test_list_is_public_and_paginated(client: Client) -> None:
    BookFactory.create_batch(3)

    response = client.get(BOOKS_URL)

    assert response.status_code == 200
    body = response.json()
    assert body["count"] == 3
    assert len(body["items"]) == 3


def test_list_runs_a_bounded_number_of_queries(
    client: Client, django_assert_max_num_queries: DjangoAssertNumQueries
) -> None:
    BookFactory.create_batch(5)

    with django_assert_max_num_queries(2):
        client.get(BOOKS_URL)


def test_get_returns_one_book_or_404(client: Client) -> None:
    book = BookFactory.create()

    response = client.get(f"{BOOKS_URL}{book.pk}")

    assert response.status_code == 200
    assert response.json()["isbn"] == book.isbn
    assert client.get(f"{BOOKS_URL}{book.pk + 1}").status_code == 404


def test_create_requires_authentication(client: Client) -> None:
    payload = {"title": "T", "author": "A", "isbn": "9780306406157"}

    response = client.post(BOOKS_URL, payload, content_type="application/json")

    assert response.status_code == 401
    assert not Book.objects.exists()


def test_create_normalizes_the_isbn(user_client: Client) -> None:
    payload = {"title": "T", "author": "A", "isbn": "978-0-306-40615-7"}

    response = user_client.post(BOOKS_URL, payload, content_type="application/json")

    assert response.status_code == 201
    assert response.json()["isbn"] == "9780306406157"


def test_create_rejects_a_malformed_isbn(user_client: Client) -> None:
    payload = {"title": "T", "author": "A", "isbn": "not-an-isbn"}

    response = user_client.post(BOOKS_URL, payload, content_type="application/json")

    assert response.status_code == 422


def test_create_reports_a_duplicate_isbn_as_conflict(user_client: Client) -> None:
    BookFactory.create(isbn="9780306406157")
    payload = {"title": "T", "author": "A", "isbn": "978-0-306-40615-7"}

    response = user_client.post(BOOKS_URL, payload, content_type="application/json")

    assert response.status_code == 409
    assert Book.objects.count() == 1


def test_openapi_schema_is_served(client: Client) -> None:
    response = client.get("/api/v1/openapi.json")

    assert response.status_code == 200
    assert "/api/v1/books/" in response.json()["paths"]
```
