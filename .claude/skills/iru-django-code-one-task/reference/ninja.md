# Django Ninja

Read this when the API style is Django Ninja (1.7, Pydantic 2) and the task touches an API, router, operation, or
schema. Ninja is typed, async-capable, and generates its own OpenAPI document.

## Layout

One `NinjaAPI(title=..., version=..., urls_namespace=...)` (typically `config/api.py`), **one `Router(tags=[...])`
per app** (`<app>/api.py`), schemas in `<app>/schemas.py`, wired with `api.add_router("/books/", router)` and mounted
with `path("api/v1/", api.urls)`. Versions are separate `NinjaAPI` instances with different prefixes/namespaces.

## Schemas

```python
class BookOut(ModelSchema):
    """Public representation of a book."""

    class Meta:
        model = Book
        fields = ("id", "title", "status", "stock")      # explicit — never "__all__"


class ReserveIn(Schema):
    """Input of the reserve endpoint."""

    quantity: int = Field(ge=1, le=100)


class Message(Schema):
    """Error payload."""

    detail: str
```

- Separate input (`BookIn`, with `author_id`, `Field(...)` constraints, `@field_validator`) and output (`BookOut`)
  schemas; never accept server-side fields (`owner`) in an input schema.
- PATCH: an input schema with all fields optional and `payload.model_dump(exclude_unset=True)`.
- Pydantic 2 idioms only — no `class Config`, no `orm_mode`.
- Ninja doesn't run `full_clean()`: DB constraints still fire — catch `IntegrityError` and return a 409/422.

## Operations

Verified (mypy strict, `ninja.testing.TestClient`):

```python
router = Router(tags=["books"], auth=django_auth)


@router.post("/{book_id}/reserve", response={200: BookOut, 409: Message})
def reserve(request: HttpRequest, book_id: int, payload: ReserveIn) -> Status[Book | Message]:
    """Reserve copies of a book the requesting user can see."""
    book = get_object_or_404(Book.objects.visible_to(request.user), pk=book_id)
    try:
        book = services.reserve_copies(book_id=book.pk, quantity=payload.quantity)
    except services.OutOfStockError:
        return Status(409, Message(detail="Not enough copies in stock."))
    return Status(200, book)
```

- Path params from `{name}`, simple arguments are query params, a `Schema` argument is the JSON body.
- Declare every status in `response={...}` and return `Status(code, value)` (1.6+; tuple returns are deprecated) or
  `raise HttpError(code, msg)`. Response validation is strict — a missing field is a 500, so the schema must match.
  Validation errors are **422**.
- `auth=` on the API, router, or operation (`django_auth` → cookie session with CSRF enforced; `HttpBearer`,
  `APIKeyHeader` subclasses returning `None` for a bad key). A public operation (`auth=None`) needs the task to say so.
- Scope every lookup to the user (`get_object_or_404(<scoped qs>, …)`) — the same IDOR rule as everywhere.
- Async operations (`async def`) only under ASGI and only with the async ORM (`aget`, `acreate`,
  `aget_object_or_404`); sync ORM inside raises `SynchronousOnlyOperation`, and `atomic()` must be wrapped in
  `sync_to_async`.
- Pagination: `@paginate(PageNumberPagination)` **below** the route decorator; cap `LimitOffsetPagination` with
  `NINJA_PAGINATION_MAX_LIMIT`/`max_limit`; `CursorPagination` (1.6) for feeds. Nested schemas need
  `select_related`/`prefetch_related` in the queryset (N+1 otherwise).
- Route order: literal routes (`/isbn/{isbn}`) before `/{book_id}`, or the generic one swallows them with a 422.

## Tests for this area

`ninja.testing.TestClient(router)` (or `TestAsyncClient`) with `json=`, `headers=`, `user=` for operation logic —
it bypasses middleware, so use the Django `client` end to end for CSRF/session behavior. Cover the declared statuses
(200/201, 404 for another user's row, 409, **422** for invalid input, 401/403 without auth) and the database effect.
If `"ninja"` is in `INSTALLED_APPS`, `manage.py export_openapi_schema` can produce the schema; Ninja has no
`--validate` equivalent to drf-spectacular, so the response declarations are the contract to test.
