# API layer: Django REST Framework + drf-spectacular (`api: drf`)

Mounted under `/api/v1/` by `config/urls.py` (schema at `/api/v1/schema/`, Swagger UI at `/api/v1/docs/`); `REST_FRAMEWORK`/`SPECTACULAR_SETTINGS` live in `base.py`. The viewset calls the service; a duplicate ISBN is a 409.

Templates use this skill's tag convention (see SKILL.md, "Template conventions"): a line ending in `# [api: drf]` / `# [database: postgresql]` is kept only for that choice (drop the tag), a line that is only `# [api: ninja]` opens a block kept only for that choice and `# [end]` closes it, and `<placeholders>` are replaced with Step 2's values.

## `apps/catalog/api/__init__.py`

```python
"""REST API of the catalog (Django REST Framework)."""
```

## `apps/catalog/api/serializers.py`

```python
"""Serializers for the catalog API."""

from rest_framework import serializers

from apps.catalog.models import Book
from apps.catalog.services import normalize_isbn


class BookSerializer(serializers.ModelSerializer[Book]):
    """Book representation; ISBN uniqueness is enforced by the service (409)."""

    # Declared explicitly: accepts separators (normalized below) and drops the
    # model's unique validator, so a duplicate becomes the service's 409.
    isbn = serializers.CharField(max_length=20)

    class Meta:
        model = Book
        fields = ["id", "title", "author", "isbn", "published_on", "created_at"]
        read_only_fields = ["id", "created_at"]

    def validate_isbn(self, value: str) -> str:
        """Normalize the ISBN or reject it.

        Args:
            value: ISBN as submitted.

        Returns:
            The normalized ISBN.

        Raises:
            serializers.ValidationError: The ISBN is malformed.
        """
        try:
            return normalize_isbn(value)
        except ValueError as exc:
            raise serializers.ValidationError(str(exc)) from exc
```

## `apps/catalog/api/views.py`

```python
"""Viewsets of the catalog API."""

from django.db.models import QuerySet
from rest_framework import mixins, status, viewsets
from rest_framework.exceptions import APIException
from rest_framework.serializers import BaseSerializer

from apps.catalog.api.serializers import BookSerializer
from apps.catalog.models import Book
from apps.catalog.services import BookAlreadyExistsError, create_book


class BookConflictError(APIException):
    """409 response for a duplicate ISBN."""

    status_code = status.HTTP_409_CONFLICT
    default_detail = "A book with this ISBN already exists."
    default_code = "conflict"


class BookViewSet(
    mixins.ListModelMixin,
    mixins.RetrieveModelMixin,
    mixins.CreateModelMixin,
    viewsets.GenericViewSet[Book],
):
    """List, retrieve and create books; writes need an authenticated user."""

    serializer_class = BookSerializer

    def get_queryset(self) -> QuerySet[Book]:
        """Return every book, ordered by title.

        Returns:
            The queryset the list and detail actions use.
        """
        return Book.objects.order_by("title", "id")

    def perform_create(self, serializer: BaseSerializer[Book]) -> None:
        """Create the book through the service layer.

        Args:
            serializer: The validated serializer.

        Raises:
            BookConflictError: The ISBN already exists.
        """
        try:
            serializer.instance = create_book(**serializer.validated_data)
        except BookAlreadyExistsError as exc:
            raise BookConflictError(str(exc)) from exc
```

## `apps/catalog/api/urls.py`

```python
"""Routes of the catalog API (mounted under ``/api/v1/``)."""

from rest_framework.routers import DefaultRouter

from apps.catalog.api.views import BookViewSet

router = DefaultRouter()
router.register("books", BookViewSet, basename="book")

urlpatterns = router.urls
```

## `tests/catalog/test_api_drf.py`

```python
"""Tests for the catalog REST API (Django REST Framework)."""

import pytest
from django.test import Client
from django.urls import reverse
from pytest_django import DjangoAssertNumQueries

from apps.catalog.models import Book
from tests.factories import BookFactory

pytestmark = pytest.mark.django_db

BOOKS_URL = reverse("book-list")


def test_list_is_public_and_paginated(client: Client) -> None:
    BookFactory.create_batch(3)

    response = client.get(BOOKS_URL)

    assert response.status_code == 200
    body = response.json()
    assert body["count"] == 3
    assert [book["title"] for book in body["results"]] == sorted(
        book["title"] for book in body["results"]
    )


def test_list_runs_a_bounded_number_of_queries(
    client: Client, django_assert_max_num_queries: DjangoAssertNumQueries
) -> None:
    BookFactory.create_batch(5)

    with django_assert_max_num_queries(2):
        client.get(BOOKS_URL)


def test_retrieve_returns_one_book_or_404(client: Client) -> None:
    book = BookFactory.create()

    response = client.get(reverse("book-detail", args=[book.pk]))

    assert response.status_code == 200
    assert response.json()["isbn"] == book.isbn
    assert client.get(reverse("book-detail", args=[book.pk + 1])).status_code == 404


def test_create_requires_authentication(client: Client) -> None:
    payload = {"title": "T", "author": "A", "isbn": "9780306406157"}

    response = client.post(BOOKS_URL, payload, content_type="application/json")

    assert response.status_code == 403
    assert not Book.objects.exists()


def test_create_normalizes_the_isbn(user_client: Client) -> None:
    payload = {"title": "T", "author": "A", "isbn": "978-0-306-40615-7"}

    response = user_client.post(BOOKS_URL, payload, content_type="application/json")

    assert response.status_code == 201
    assert response.json()["isbn"] == "9780306406157"
    assert Book.objects.get().isbn == "9780306406157"


def test_create_rejects_a_malformed_isbn(user_client: Client) -> None:
    payload = {"title": "T", "author": "A", "isbn": "not-an-isbn"}

    response = user_client.post(BOOKS_URL, payload, content_type="application/json")

    assert response.status_code == 400
    assert "isbn" in response.json()


def test_create_reports_a_duplicate_isbn_as_conflict(user_client: Client) -> None:
    BookFactory.create(isbn="9780306406157")
    payload = {"title": "T", "author": "A", "isbn": "978-0-306-40615-7"}

    response = user_client.post(BOOKS_URL, payload, content_type="application/json")

    assert response.status_code == 409
    assert Book.objects.count() == 1


def test_openapi_schema_is_served(client: Client) -> None:
    response = client.get(reverse("schema"))

    assert response.status_code == 200
    assert b"/api/v1/books/" in response.content
```
