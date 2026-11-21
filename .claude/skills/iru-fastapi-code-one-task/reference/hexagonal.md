# Ports and adapters in a FastAPI service (optional)

Read this **only** when Step 1 found that the repository clearly follows ports and adapters. The docs' default is
the layered `app/` package with ports only where real business rules exist; most FastAPI services are flatter
still. Don't impose this layout on them.

## Recognizing it

At least two of these, consistently:

- `typing.Protocol` (or `abc.ABC`) ports in `services/ports.py`, `domain/ports.py`, or `application/ports/` —
  `BookRepository`, `UnitOfWork`, `CoverStorage`, `Clock` — implemented by adapters elsewhere
  (`SqlBookRepository`, `S3CoverStorage`).
- Packages named `domain/`, `application/` (or `use_cases/`), `adapters/` or `infrastructure/`, often under
  `src/<pkg>/`; domain modules that import nothing from `fastapi`, `sqlmodel`/`sqlalchemy`, or `pydantic_settings`.
- A unit of work (`with self.uow: ... self.uow.commit()` / `async with uow:`) owning the transaction, and in-memory
  fakes of the ports in `tests/fakes.py`, with contract tests parametrized over fake and real adapters.
- An import-boundary check (`import-linter` contracts in `pyproject.toml`/`.importlinter`, `tach`, a
  `pytest-archon` test).

If only one feature looks like this, follow that feature's shape when the task is inside it and the plain layout
elsewhere.

## Rules when it applies

- **Dependencies point inward**: domain imports nothing from the framework, the ORM, settings, or other layers;
  application (use cases/services) imports the domain and the ports; adapters and the web layer import inward and
  never each other.
- **Ports are small `Protocol`s** owned by the side that uses them, typed with domain types (not ORM rows), and
  async when the stack is async:

```python
class BookRepository(Protocol):
    async def by_author(self, author_id: int) -> list[Book]: ...


class SqlBookRepository:
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def by_author(self, author_id: int) -> list[Book]:
        result = await self._session.scalars(
            select(Book)
            .where(Book.author_id == author_id)
            .options(selectinload(Book.author))
            .order_by(Book.id)
        )
        return list(result)
```

  Structural typing means the adapter doesn't inherit the port; `mypy --strict` checks conformance wherever an
  adapter is passed as the port (verified).
- **Services take ports in the constructor**, own the transaction through the unit of work (commit once per use
  case; the UoW's exit always rolls back uncommitted work), raise domain errors, and know nothing of HTTP or SQL.
- **Wiring only in `dependencies.py`** (or the project's composition root): providers build adapters from
  lifespan resources and settings, then the service — `get_unit_of_work` → `get_book_service` → `BookServiceDep`.
  Tests swap a port with `app.dependency_overrides[get_unit_of_work] = lambda: fake_uow`; production
  implementations are chosen by a settings field inside a provider, never by env-var module switching.
- **Validation at the edge**: request models live with the web layer and are mapped to domain inputs; domain
  invariants are enforced in domain constructors/factories; persistence models (table classes) are mapped to and
  from domain types inside the adapter when the project keeps them separate.
- Order of work for a task spanning layers: domain type + port → adapter → service → dependency wiring → router.
- Don't add a port "for testability" when a fake session or `dependency_overrides` already isolates the code —
  ceremony layers are the docs' named anti-pattern.

## Testing in this layout

Domain and services: plain pytest with in-memory fakes of the ports — no FastAPI, no database, milliseconds each.
Adapters: contract tests that run the same assertions against the fake and the real adapter (SQLite in the fast
tier; PostgreSQL via testcontainers in the `integration` tier). Routers: the client fixture with the service or
unit of work overridden (`testing.md`).
