# Routers, dependencies, status codes, errors

Read this when the task adds or changes an endpoint, an `APIRouter`, a dependency, a status code, an exception
handler, or `include_router` wiring.

## Routers

- **One `APIRouter` per resource**, in its own module: `router = APIRouter(prefix="/books", tags=["books"])`. The
  prefix starts with `/` and doesn't end with one; pick the project's existing trailing-slash convention (`""` vs
  `"/"` for the collection route) and keep it. Tags are how generated clients name services — every route has one.
- **Include it**: `app.include_router(books.router)` in `main.py` (or the template's `api/main.py`), importing the
  router *module* (`from app.routers import books`), not the `router` variable. Versioning is a prefix on the
  include (`prefix="/api/v1"`), not a mounted sub-application.
- **Route order is matching order**: fixed paths (`/books/featured`, `/books/me`) before parameterized ones
  (`/books/{book_id}`), or the fixed path is parsed as an id (422 or wrong data).
- **REST shape**: plural nouns, ids in the path, filters/paging/sort in the query, state changes in bodies; nest
  only for ownership and at most two levels (`/authors/{author_id}/books`). No `Body`/`Form` on `GET`.
- `main.py` stays boring: create the app with its lifespan, include routers, register exception handlers and
  middleware — nothing else.

## Path operation shape

```python
BookId = Annotated[int, Path(ge=1, description="Primary key of a book.")]


@router.post("", status_code=status.HTTP_201_CREATED, response_model=BookPublic)
async def create_book(
    data: BookCreate,
    service: BookServiceDep,
    user_id: CurrentUserId,
    response: Response,
) -> Book:
    """Create a book owned by the caller."""
    book = await service.create(data, owner_id=user_id)
    response.headers["Location"] = f"/books/{book.id}"
    return book
```

- **`status_code` on the decorator**, named constants from `fastapi.status` (`HTTP_201_CREATED`,
  `HTTP_204_NO_CONTENT`, `HTTP_422_UNPROCESSABLE_CONTENT` — the old `..._ENTITY` name is deprecated). FastAPI never
  infers 201 from `POST`. A create returns 201 + the created resource + `Location`; a delete returns 204 with
  `-> None` (empty body); a function parameter named `status_code` would just be a query parameter.
- **Response model**: the return annotation when it tells the truth (`-> BookPublic`, `-> list[BookPublic]`);
  `response_model=` when the function returns rows/table models that must be filtered through a public model
  (above). Ruff `FAST001` flags a `response_model` that merely repeats the annotation. Never a table or
  `...InDB` model as the response model (`validation-and-schemas.md`).
- **Return the data, not a `JSONResponse`**, unless you need a status/headers the decorator can't express: a
  returned `Response` bypasses validation, filtering, OpenAPI, and the fast Rust serializer. Prefer a `response:
  Response` parameter for extra headers/cookies/an alternative success code (filtering still applies) — and don't
  combine it with returning your own `Response` (the parameter's headers are lost).
- **Document every non-2xx a route can return** with `responses={404: {"description": "Book not found"}, ...}`
  (shared dicts like `NOT_FOUND` for reuse). It documents only; it neither produces nor validates.
- `async def` vs `def`: `async-and-concurrency.md`. The docstring is the OpenAPI description.

## Dependencies

- **Always `Annotated[T, Depends(provider)]`**, never `= Depends(...)` defaults (Ruff `FAST002`); pass the callable,
  never `Depends(provider())`. Reusable aliases live in `dependencies.py` (`SessionDep`, `SettingsDep`,
  `CurrentUser`, `BookServiceDep`) and are named for the concept. `Annotated[Pagination, Depends()]` works only for
  classes.
- **Typed returns** from every provider (not `dict`/`Any`), and every provider parameter annotated (an
  unannotated one becomes a required query parameter).
- **One responsibility per node**: extract credentials → authenticate (401) → load user state → authorize (403) →
  load-or-404 the object. Dependencies may raise `HTTPException` (401/403/404); services may not.
- **Per-request cache**: the same provider used twice in one request runs once (handler and `get_current_user`
  share one session). `Depends(fn, use_cache=False)` only for deliberately fresh values.
- **Gates** whose value you don't need go in `dependencies=[Depends(require_admin)]` on the decorator, the router,
  or `include_router` — their return values are discarded. Global `FastAPI(dependencies=[...])` doesn't cover
  `/docs`, `/openapi.json`, or mounted apps; keep it minimal.
- **Parameterized dependencies** are created at import time — a factory returning a checker, or a callable class
  instance — so caching and `dependency_overrides` work; never build a dependable per request, never a lambda.
  Instances stay immutable (no per-request state on `self`).

## Yield dependencies (sessions, clients with teardown)

```python
async def get_session(
    factory: Annotated[async_sessionmaker[AsyncSession], Depends(get_session_factory)],
) -> AsyncIterator[AsyncSession]:
    async with factory() as session:
        try:
            yield session
        except Exception:
            await session.rollback()
            raise
```

- Yield exactly once; **always re-raise** (or convert to `HTTPException`) in `except` — a swallowed exception
  becomes a bare 500 with no traceback.
- **Commit in the path operation or service, not in the teardown**: with the default `scope="request"`, teardown
  runs after the response is sent, so a failing commit there can't change the 200 the client already got.
  `Depends(get_session, scope="function")` tears down before the response (use it when teardown must affect the
  status, or to release connections early); a `"request"`-scoped dependency can't depend on a `"function"` one
  (`DependencyScopeError` at import).
- Wrap context managers inside the generator; don't decorate the dependency with `@contextmanager`.
- **Background tasks and streams own their resources**: pass ids, open a fresh session inside the task
  (`settings-and-lifespan.md`, `streaming-files-and-websockets.md`).

## Errors → status codes

- **Domain errors map to HTTP in one place**, exception handlers in `main.py` — no try/except per route:

```python
@app.exception_handler(BookNotFoundError)
async def book_not_found(request: Request, exc: BookNotFoundError) -> JSONResponse:
    """Map a missing book to 404."""
    return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content={"detail": str(exc)})
```

  Keep the body shape the project already uses (`{"detail": ...}` by default, or its RFC 9457
  `application/problem+json` handlers). A hierarchy can share one handler when errors carry `status_code`/`code`.
  `APIRouter` can't register handlers — only the app (a mounted sub-app needs its own).
- **Raise `HTTPException`, never return it** (returning gives a 200). `HTTPException` is for client errors; never
  `raise HTTPException(500)` for a bug — let it propagate to the generic 500 handler, which must not echo exception
  text (connection strings, paths).
- Handle `starlette.exceptions.HTTPException` (not FastAPI's) in a custom handler so router 404/405s get the same
  format; delegate to `fastapi.exception_handlers.http_exception_handler` to keep default bodies. A custom
  `RequestValidationError` handler must not echo `exc.body`/`input` (passwords) and changes the 422 contract for
  every route — only when the task asks.
- Catch specific exceptions (`IntegrityError` → rollback → domain `Duplicate...Error` → 409), never `except
  Exception` → 400.
