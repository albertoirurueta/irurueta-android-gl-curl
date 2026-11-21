# Request and response models, parameters, validation

Read this when the task adds or changes a Pydantic/SQLModel model, a path/query/header/cookie/form parameter,
pagination, or a PATCH. FastAPI 0.142 is **Pydantic v2 only**: a project still on Pydantic v1 syntax
(`@validator`, `.dict()`, `class Config`, `orm_mode`) is a migration (`bump-pydantic`) to report, not something to
continue writing.

## Model families: input, output, and storage are different classes

```python
Isbn = Annotated[str, StringConstraints(pattern=r"^\d{13}$")]


class BookBase(SQLModel):
    title: str = Field(min_length=1, max_length=200)
    isbn: Isbn = Field(unique=True, index=True)


class Book(BookBase, table=True):          # storage — never a response model
    id: int | None = Field(default=None, primary_key=True)
    owner_id: int = Field(index=True)


class BookCreate(BookBase):                # input
    model_config = {"extra": "forbid"}


class BookPublic(BookBase):                # output
    id: int
```

(Plain Pydantic projects: `BookIn`/`BookUpdate`/`BookOut` from `BaseModel`, with `model_config =
ConfigDict(from_attributes=True)` on output models read from ORM objects.)

- **Never return a table model, an `...InDB` model, or anything holding a secret as the response model** — the
  response model is an allow-list; `-> UserInDB` or `response_model=None` leaks `hashed_password`. Prefer dedicated
  output models over `response_model_exclude={...}` deny-lists, which fail open when a column is added. Test that
  the secret key is absent.
- **Write models reject unknown fields**: `extra="forbid"` on create/update/query/form models (default `ignore`
  silently drops typos and is your mass-assignment hole). Server-controlled fields (`id`, `owner_id`, `role`,
  `created_at`) are simply not declared on input models; the service sets them from the authenticated user
  (`Book.model_validate(data, update={"owner_id": user_id})` — table models don't validate on construction, so
  build them through `model_validate`).
- One table model per family; keep the family together in one module; response models one relationship level deep
  (`BookWithAuthors`) to avoid recursion.

## Constraints and validators

- Constraints in `Field(...)` or reusable `Annotated` aliases (`Rating = Annotated[int, Field(ge=1, le=5)]`,
  `Slug = Annotated[str, StringConstraints(strip_whitespace=True, to_lower=True, min_length=3)]`); `pattern=` (never
  `regex=`), anchored `^...$` (it's a search; the Rust engine has no look-around — use an `AfterValidator` for
  that). Not `conint`/`constr`. Constraints don't reach into containers: `list[Annotated[str, Field(pattern=...)]]`.
- `@field_validator("x")` **above** `@classmethod`; `@model_validator(mode="after")` returning `self` for
  cross-field rules; reusable `AfterValidator` in `Annotated`. Validators raise `ValueError` (or
  `PydanticCustomError`) — **never `HTTPException`, never `assert`**, no side effects (after-validators re-run on
  response validation).
- `model_config = ConfigDict(...)` (not `class Config`); `extra="forbid"`, `from_attributes=True`,
  `str_strip_whitespace=True`, `frozen=True` where it fits. `examples=[...]` (not `example=`) for OpenAPI; examples
  aren't validated.
- Types: `Decimal` with `max_digits`/`decimal_places` for money, `UUID`, timezone-aware `datetime`, `EmailStr`,
  `HttpUrl`, `SecretStr`, `Literal[...]`/`StrEnum` for closed sets. `model_copy(update=...)` and `model_construct()`
  **skip validation** — never on untrusted data.

## PUT vs PATCH

- PUT replaces: the same input model as create.
- PATCH: an all-optional update model, then apply **only what the client sent**:

```python
class BookUpdate(SQLModel):
    model_config = {"extra": "forbid"}

    title: str | None = Field(default=None, min_length=1, max_length=200)

    @field_validator("title")
    @classmethod
    def reject_null(cls, value: str | None) -> str | None:
        if value is None:                  # an explicit null would violate NOT NULL
            raise ValueError("title cannot be null")
        return value


book.sqlmodel_update(changes.model_dump(exclude_unset=True))   # SQLModel
# Pydantic: Book.model_validate(stored.model_dump() | changes.model_dump(exclude_unset=True))
```

  (Validators don't run on defaults, so an omitted field stays unset; only a sent `null` is rejected — verified.)
  `exclude_none` is not the same thing: it can't clear a nullable field.

## Parameters

- Required vs optional comes from the **default**, nullability from the **type**: `q: str` required,
  `q: str | None = None` optional. Never `Annotated[str, Query(...)] = ...` (missing param → 500 on 0.142), never
  the default inside the marker (`Query(default=5)` → `AssertionError`).
- `Path` params can't have defaults; reusable aliases (`BookId = Annotated[int, Path(ge=1)]`). A malformed id is the
  framework's 422; a well-formed missing id is your 404 — never 404 for malformed input.
- Lists in the query need an explicit `Query()` (`author_id: Annotated[list[int], Query()] = []`); comma-separated
  values aren't split. `Header()`/`Cookie()` markers are required or the value is read from the query; headers with
  underscores aren't matched (send hyphens). Never `strict=True` on query/form/path params (they arrive as strings).
- Many query params → a query model (`Annotated[BookFilter, Query()]`, `extra="forbid"`), one per operation.
- **Pagination**: `offset: Annotated[int, Query(ge=0)] = 0`, `limit: Annotated[int, Query(ge=1, le=100)] = 20` —
  always capped — with a deterministic `ORDER BY` on a unique column; keyset (`after_id`) for large or live tables.
  `sort` is a `Literal`/`StrEnum` mapped to real columns, never a client string interpolated into SQL.
- JSON bodies need a JSON `Content-Type` (strict since 0.132.0) — `curl -d` without the header gets 422
  `model_attributes_type`. Keep `strict_content_type` on; legacy clients get their own router with it off.
- Reading `Request` directly (`request.query_params`, `await request.json()`) skips validation and OpenAPI — only
  for infrastructure concerns, never for the API contract.
