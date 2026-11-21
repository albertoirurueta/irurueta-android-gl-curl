# Models, the ORM, migrations, and transactions

Read this when the task touches a model, field, relation, constraint, index, manager/QuerySet, migration, query,
transaction, signal, or service function.

## Model shape

```python
class Book(models.Model):
    class Status(models.TextChoices):
        DRAFT = "draft", "Draft"
        PUBLISHED = "published", "Published"

    public_id = models.UUIDField(default=uuid.uuid4, editable=False, unique=True)   # for public URLs
    author = models.ForeignKey(Author, on_delete=models.PROTECT, related_name="books")
    owner = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="books")
    status = models.CharField(max_length=20, choices=Status, default=Status.DRAFT)
    price = models.DecimalField(max_digits=8, decimal_places=2, validators=[MinValueValidator(0)])
    metadata = models.JSONField(default=dict, blank=True)
    created_at = models.DateTimeField(db_default=Now(), editable=False)

    objects = BookQuerySet.as_manager()

    class Meta:
        ordering = ("-created_at", "pk")
        indexes = (models.Index(fields=["status", "-created_at"], name="catalog_book_status_created_idx"),)
        constraints = (models.CheckConstraint(condition=Q(price__gte=0), name="catalog_book_price_gte_0"),)

    def __str__(self) -> str:
        return self.title

    def get_absolute_url(self) -> str:
        return reverse("catalog:book-detail", kwargs={"public_id": self.public_id})
```

- Always `__str__` (Ruff `DJ008`) and `get_absolute_url()` for anything with a page. Inner classes and `Meta` order:
  fields, managers, `Meta`, `__str__`, `save`, `get_absolute_url`, other methods (Ruff `DJ012`).
- Strings: `blank=True` with `""`, never `null=True` (`DJ001`) — except `unique=True, blank=True`. `blank` is
  form-level, `null` is DB-level.
- Money is `DecimalField`; closed sets are `TextChoices`/`IntegerChoices` passed as `choices=Status`; defaults are
  callables (`default=dict`, `default=timezone.now`) or `db_default=Now()` / `GeneratedField` for DB-computed values.
- Leave the primary key alone (`BigAutoField` default since 6.0); expose a `UUIDField` or slug in public URLs
  instead of sequential ids. Don't touch `DEFAULT_AUTO_FIELD` in an existing project — changing it generates
  migrations for every model.
- Abstract bases for shared fields (`TimeStampedModel` in `core`, `Meta: abstract = True`); FKs on abstract bases use
  `related_name="%(app_label)s_%(class)s_…"`. Avoid multi-table inheritance for "kinds of things"; proxy models only
  for a different manager/ordering/admin on the same table. `CompositePrimaryKey` (5.2) only for legacy tables.

## Relations

- `on_delete` deliberately: dependent child → `CASCADE`; parent must survive → `PROTECT` (`RESTRICT` when a cascade
  path should win); child outlives parent → `SET_NULL` with `null=True`. 6.1's `DB_CASCADE`/`DB_SET_NULL` skip
  signals and `delete()` and can't be mixed with Python-level options — only for very large child sets, on purpose.
- Always a `related_name` (plural for the many side); two FKs to the same model need distinct ones.
- Users: `settings.AUTH_USER_MODEL` in fields, `get_user_model()` at runtime — never `django.contrib.auth.models.User`.
- M2M with extra data: `through=` model **plus a `UniqueConstraint` on the pair**.

## Constraints and validation

Invariants that must always hold go in `Meta.constraints` with explicit, prefixed names (`<app>_<model>_<rule>`):
`UniqueConstraint(fields=[...], condition=Q(...), name=...)` for (partial) uniqueness — preferred over
`unique_together`; `CheckConstraint(condition=Q(...), name=..., violation_error_code=...)` (`check=` is gone in 6.0).
`Meta.indexes` for real query patterns only (`index_together` was removed in 5.1).

`save()`, `create()`, `bulk_create()`, `update()`, and `loaddata` **don't validate**. ModelForms and `full_clean()`
do (including `validate_constraints()`), but validation narrows the race, it doesn't close it: wrap writes in
`transaction.atomic()` and translate `IntegrityError` into a `ValidationError`/domain error at the boundary.
Cross-field rules go in `Model.clean()` (raising `ValidationError({"field": msg})`) **and** a constraint.

## Managers and QuerySets

- Reusable filters are `QuerySet` subclass methods returning QuerySets, exposed with `objects = BookQuerySet.as_manager()`
  (or `Manager.from_queryset(BookQuerySet)()` when the manager needs its own methods) — `Book.objects.published().by_author(a)`.
- Declaring any manager removes the automatic `objects`; **the first declared manager is the default** (used by
  `dumpdata`, related managers, admin) — keep the unfiltered one first (soft delete: `objects` first, then `active`).
- A filtered `get_queryset()` on a manager is **not** a security boundary (joins and `_base_manager` bypass it);
  authorization is an explicit `visible_to(user)` call on every access path.
- No module-level QuerySets (they cache results for the life of the process).

## Queries

- **N+1**: `select_related("author")` for FK/one-to-one (always name the fields — the no-argument form is deprecated
  in 6.1), `prefetch_related("genres")` / `Prefetch("reviews", queryset=...)` for many-valued relations. `.filter()` on a
  prefetched relation discards the prefetch. Annotate counts in the query (`annotate(review_count=Count("reviews"))`),
  never `book.reviews.count()` per row. On 6.1, `fetch_mode(models.FETCH_PEERS)` is a safety net, `FETCH_RAISE` a
  guard in tests; code that must run on 5.2 uses select/prefetch only.
- QuerySets are lazy: `exists()` / `count()` / slicing instead of `if qs:` or `len(qs)`.
- `F()` for column math and race-free updates (`.update(stock=F("stock") - 1)`); `Q` for OR/NOT (positional before
  keyword args); `.distinct()` after filtering across many-valued joins; `filter(a, b)` vs `.filter(a).filter(b)` differ
  on multi-valued relations.
- Two multi-valued aggregates in one `annotate()` multiply rows — `distinct=True` on each `Count`, or subqueries.
  Conditional aggregates with `filter=Q(...)`; `Exists(...)` over `Count(...) > 0`; clear `Meta.ordering` with
  `.order_by()` in grouped queries.
- **Never pass user input as field names** to `filter(**…)`, `order_by()`, `values()` — map through an allow-list.
- Big result sets: `.iterator(chunk_size=…)`, `only()`/`defer()` for genuinely large columns.
- Raw SQL is a last resort, always with `params` (`Book.objects.raw("… WHERE id = %s", [pk])`,
  `cursor.execute(sql, params)`), never f-strings or `%`-formatting.

## Transactions and side effects — the service pattern

Verified (pytest-django, SQLite fast tier and PostgreSQL via testcontainers):

```python
class OutOfStockError(Exception):
    """Raised when a reservation asks for more copies than are in stock."""


@transaction.atomic
def reserve_copies(*, book_id: int, quantity: int) -> Book:
    """…Args/Returns/Raises as in code-style.md…"""
    if quantity < 1:
        msg = "quantity must be positive"
        raise ValueError(msg)
    book = Book.objects.select_for_update().get(pk=book_id)
    if book.stock < quantity:
        raise OutOfStockError(book.title)
    Book.objects.filter(pk=book_id).update(stock=F("stock") - quantity)
    book.refresh_from_db(fields=["stock"])
    if book.stock < 2:
        transaction.on_commit(partial(notify_low_stock.enqueue, book_id=book.pk))
    return book
```

- `@transaction.atomic` on service functions that write more than once; nest for savepoints; `atomic(durable=True)`
  for outermost-only units. Catch DB errors **outside** the block.
- Side effects (email, task enqueue, cache invalidation, outgoing HTTP, file deletion) go in
  `transaction.on_commit(...)` — they run after the outermost commit and are discarded on rollback. Bind arguments with
  `functools.partial` (a lambda capturing a loop variable is a bug); pass **ids**, not instances.
- `select_for_update()` only inside `atomic`, only when the logic can't be a single conditional `UPDATE`.
- Prefer explicit `atomic` blocks over `ATOMIC_REQUESTS`. `atomic()` doesn't work in `async def` — put the unit in a
  sync function and call it with `sync_to_async`.
- A rollback doesn't restore Python objects — re-read after a failed block.

## Signals — last resort

Prefer an explicit service call. Use a signal only when the sender can't know the receiver (framework events, a
reusable app). Receivers live in `<app>/signals.py`, are imported in `AppConfig.ready()`, take
`sender, instance, created, raw, **kwargs`, carry a namespaced `dispatch_uid`, skip `raw=True` (fixtures), and defer
side effects with `on_commit`. `update()`, `bulk_create()`, and `DB_*` cascades fire no signals.

## Migrations

- **Generate, don't write**: `<run> python manage.py makemigrations <app_label> --name <descriptive> --settings=<test settings>`,
  then read the file and `sqlmigrate` it if in doubt. Commit it with the model change.
- **Never edit or delete a migration that may be applied anywhere** (shared branch, CI, staging, production) — fix
  forward. Never `--fake` to "fix" an error. Merge conflicts (two leaves) are resolved with `makemigrations --merge`
  by the group, not here.
- **Renames**: `makemigrations` asks "Did you rename …?" — answer yes (`--noinput` answers no and produces
  `RemoveField` + `AddField` = data loss). Check the generated operations.
- **Adding a non-nullable or unique field to a populated table** takes three migrations: add nullable → `RunPython`
  backfill → alter to non-null/unique. A non-null FK likewise.
- **Data migrations**: `makemigrations <app> --empty --name backfill_x`, then `RunPython(forward, reverse)` with
  `apps.get_model("catalog", "Book")` (never import the real model — historical models have no custom methods) and a
  real reverse or `migrations.RunPython.noop`. Separate schema and data migrations; big backfills in batches with
  `atomic = False`. Never `call_command("loaddata")` in a migration.
- **Zero-downtime** (expand/contract): don't drop or rename a column the running code still reads; on large
  PostgreSQL tables use `AddIndexConcurrently` / `AddConstraintNotValid` + `ValidateConstraint` in non-atomic
  migrations — only when the task asks for production-safe schema changes.
- `swappable_dependency(settings.AUTH_USER_MODEL)` when a migration references the user model.
- The group runs `makemigrations --check --dry-run`; a model change without its migration fails the bucket.
