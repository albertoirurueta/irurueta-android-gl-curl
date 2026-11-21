# Persistence: SQLModel / SQLAlchemy 2.0 and Alembic

Read this when the task touches a table model, a query, a session/transaction, a repository/unit of work, or a
migration. Read the **shared rules**, then only the section for the stack Step 1 detected.

| Stack | Detected by |
|---|---|
| SQLModel | `sqlmodel` dependency, `class X(SQLModel, table=True)` |
| Plain SQLAlchemy 2.0 | `DeclarativeBase`, `Mapped[...]`, `mapped_column` |
| Legacy SQLAlchemy 1.x style | `declarative_base()`, `session.query(...)` — follow it inside existing modules, report it, don't spread it |
| Async | `create_async_engine`, `AsyncSession`/`async_sessionmaker`, `asyncpg`/`aiosqlite`/`psycopg` async URLs |
| Sync | `create_engine`, `Session`, `psycopg[binary]`/`psycopg2` URLs, `def` path operations |
| Alembic | `alembic.ini` + `migrations/` or `alembic/` (Full Stack template: `app/alembic/`) |
| Other stores | PyMongo `AsyncMongoClient`, `redis.asyncio`, Elasticsearch — clients from the lifespan (`settings-and-lifespan.md`) |

## Shared rules

- **Engine once per process** (created in the lifespan or a cached provider, never at import), **one session per
  request** from a yield dependency (`routers-and-dependencies.md`), never module-level or shared between requests
  or concurrent tasks.
- **Commit where the unit of work ends** — the service or path operation — then `refresh()` what you return.
  After any failed flush/commit, `rollback()` before reusing the session (else `PendingRollbackError`). Catch the
  specific `IntegrityError` → rollback → domain error → 409; don't treat every `IntegrityError` as "duplicate"
  when the table has several constraints.
- **The database is the real guard** against races: a `unique=True` column, not a read-then-insert check.
- **No N+1**: `selectinload()` for collections, `joinedload()` for many-to-one; in async code eager loading is
  mandatory (a lazy load raises `MissingGreenlet`). Index foreign keys and filter/order columns
  (`Field(foreign_key="author.id", index=True)`).
- **Bounded reads**: every list query has `order_by` on a unique column plus `offset`/`limit` (or keyset); count
  with `select(func.count()).select_from(Book)`. Select only the columns you need for big rows.
- **Bound parameters only**: ORM expressions or `text("... WHERE slug = :slug")` with params — never f-strings or
  `%` formatting into SQL, never a client-supplied column name.
- **Timestamps**: `DateTime(timezone=True)` with `default=utc_now` (a callable returning `datetime.now(UTC)`) or
  `server_default=func.now()` — never `default=datetime.utcnow()` (evaluated once at import; `utcnow` is
  deprecated).
- **Don't return ORM/table objects past the response model** (`validation-and-schemas.md`); don't use them after
  the session closed (`DetachedInstanceError`) — serialize or eager-load first.
- **`create_all()` is for tests and prototypes.** Where Alembic owns the schema, a model change ships with a
  revision in the same task, and the app never runs `create_all()` or `alembic upgrade` at startup.
- Connection URLs come from settings (`URL.create(...)` escapes passwords), never hard-coded or logged
  (`url.render_as_string(hide_password=True)`). Spell the driver: `postgresql+psycopg://`,
  `postgresql+asyncpg://`.

## SQLModel

- Imports: `Field, Relationship, SQLModel, select, col, func` from `sqlmodel`; `Session` (sync) from `sqlmodel`,
  `AsyncSession` from `sqlmodel.ext.asyncio.session`; `selectinload`, `IntegrityError`, `event`, pools from
  `sqlalchemy`.
- `session.exec(select(Book).where(...))` — not `execute()` or the deprecated `query()`. `col(Book.id)` when a
  column needs SQL operators (`.order_by(col(Book.id))`, `.in_(...)`) so mypy sees a column, not an `int | None`.
- Create: `Book.model_validate(data, update={...})` → `add` → `commit` → `refresh`. PATCH:
  `book.sqlmodel_update(changes.model_dump(exclude_unset=True))`. Delete: `delete(book)` → `commit` → 204.
- Relationships: `Relationship(back_populates=..., link_model=BookAuthorLink)` for many-to-many with a link table
  (composite PK FKs, `ondelete="CASCADE"`); `cascade_delete=True` on one-to-many.
- Pin SQLModel like FastAPI (0.0.x). SQLModel 0.0.48 already resolves **SQLAlchemy 2.1** (verified), which no
  longer installs `greenlet`: the async API then fails at import with "requires that the Python 'greenlet' library
  is installed" — the project needs `sqlalchemy[asyncio]` (a dependency change: report it, don't add it quietly).

## Plain SQLAlchemy 2.0

```python
class Base(DeclarativeBase):
    metadata = MetaData(naming_convention=NAMING_CONVENTION)


class Book(Base):
    __tablename__ = "book"
    id: Mapped[int] = mapped_column(primary_key=True)
    title: Mapped[str] = mapped_column(String(200))
    subtitle: Mapped[str | None]
    author_id: Mapped[int] = mapped_column(ForeignKey("author.id"), index=True)
    author: Mapped[Author] = relationship(back_populates="books")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
```

- `Mapped[...]` carries type and nullability; `select()` + `session.scalars(...)`/`session.execute(...)`; never
  `declarative_base()`, `session.query()`, `engine.execute()`.
- Response models read attributes: `ConfigDict(from_attributes=True)` + `BookOut.model_validate(row)`.
- `WriteOnlyMapped` for huge collections; `with_for_update()` for row locks; `begin_nested()` for savepoints;
  bulk `update()`/`delete()` bypass the unit of work and ORM cascades — use them knowingly.

## Async specifics

- `create_async_engine(url, pool_pre_ping=True)` + `async_sessionmaker(engine, class_=AsyncSession,
  expire_on_commit=False)` (without `expire_on_commit=False`, attribute access after commit lazy-loads →
  `MissingGreenlet`). `await` every DB call; `await engine.dispose()` in the lifespan shutdown.
- **One `AsyncSession` per request and per concurrent task**: `asyncio.gather`/a task group over one session
  raises. Never await an outbound HTTP call while a transaction is open.
- Sync helpers (`create_all`, Alembic) via `await conn.run_sync(...)`.
- **Don't mix** a sync session into `async def` code, or sync and async stacks within one router.

## Sync specifics

- `def` path operations and dependencies (FastAPI runs them in the threadpool — 40 threads by default): size
  `pool_size + max_overflow` with the threadpool and the worker count; total connections = workers ×
  (pool_size + max_overflow) < the server's `max_connections`.
- `connect_args={"check_same_thread": False}` only for SQLite URLs.

## Alembic

- **Set the naming convention before the first revision** (`SQLModel.metadata.naming_convention = {...}` before
  any table class is defined, or `MetaData(naming_convention=...)` on the `DeclarativeBase`):
  `{"ix": "ix_%(column_0_label)s", "uq": "uq_%(table_name)s_%(column_0_name)s", "ck":
  "ck_%(table_name)s_%(constraint_name)s", "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
  "pk": "pk_%(table_name)s"}`. Without it constraint names differ per engine and can't be dropped reliably.
- `env.py` essentials (what to check when a revision misbehaves): `target_metadata = SQLModel.metadata` (or
  `Base.metadata`) after importing the models package; URL from the environment/settings
  (`config.set_main_option("sqlalchemy.url", url.replace("%", "%%"))`), never in `alembic.ini`;
  `poolclass=pool.NullPool`; `context.configure(..., compare_type=True, render_as_batch=connection.dialect.name ==
  "sqlite")`; migrations use a **sync** driver (`postgresql+psycopg`) even when the app uses asyncpg (or an async
  `env.py` with `run_sync`). SQLModel projects need `import sqlmodel.sql.sqltypes` in `script.py.mako` (generated
  columns use `sqlmodel.sql.sqltypes.AutoString`; plain `import sqlmodel` fails `mypy --strict` with
  `Module has no attribute "sql"` — verified).
- **A schema change gets a new revision; an applied revision is never edited.** Generate it with
  `<runner> alembic revision --autogenerate -m "<change>"` when a database already at head is reachable without
  starting anything (a running dev database: `alembic current` matches `alembic heads`); otherwise
  `<runner> alembic revision -m "<change>"` (no database needed) and write `upgrade()`/`downgrade()` by hand from
  the model diff. Either way the group's `alembic check` against a fresh PostgreSQL proves the revision matches the
  models.
- **Review every revision**: autogenerate can't see renames (drop + add = data loss), anonymous constraints,
  server-default changes (without `compare_server_default`), enum changes, views/triggers. Every constraint named;
  `downgrade()` reverses `upgrade()`; a NOT NULL column on a populated table is three steps in one revision (add
  nullable → backfill with Core `update()` on a lightweight `sa.table(...)` → `alter_column(nullable=False)`).
  Data migrations never import app models; no f-strings in `op.execute`.
- Run the project's formatter/fixer on the new revision file only (`ruff check --fix <file>` + `ruff format
  <file>` — the generated template uses `Union[...]`/`typing.Sequence`, which `UP` rules flag; verified).
- **One head**: tasks that each add a revision must not run in parallel (two heads); `alembic heads` prints one
  line. If a merge is unavoidable, `alembic merge` — a plan-level decision to report.
- Migrations run once per release as a separate step (Compose one-shot `migrate` service, Kubernetes Job), never
  in the app's lifespan, CMD, or on every replica.
