# Python code style for Django

The defaults for new Python code in a Django project. The concern files (`models-and-orm.md`, `drf.md`, …) add
Django-specific rules; the surrounding code overrides both (README rule 1).

## Which tools are in force

Look for the project's configuration before writing a line, and write code its tools would leave untouched:

| Config present | In force | Notes |
|---|---|---|
| `[tool.ruff]` / `ruff.toml` | Ruff lint + `ruff format` | Read `select`/`ignore`: `DJ` (Django rules), `S` (Bandit), `D` (docstrings), `N`, `UP`, `B`, `SIM`, `RUF` change what you write. `line-length` (88 in this catalog's scaffold; the docs' PEP 8 says 79 — follow the config). |
| `[tool.black]`, `.flake8`, `[tool.isort]` | Black + flake8 + isort | Older projects; same idea — the formatter decides layout. |
| `[tool.mypy]` with `plugins = ["mypy_django_plugin.main"]` (+ `mypy_drf_plugin.main`) | mypy with django-stubs | `strict = true` means every new function is fully annotated. `[tool.django-stubs] django_settings_module` names the settings mypy imports. |
| `pyrightconfig.json` / `[tool.pyright]` | pyright | No Django plugin — model field access is less precise; annotate return types explicitly. |
| nothing | PEP 8, 4-space indent, imports grouped stdlib → third-party → local | Still annotate new code; it costs nothing and the group's quality gate may add mypy later. |

Format only the files you touched (`<run> ruff format <files>` / `<run> black <files>`); never reformat the project.

## Version floor

Respect `requires-python` in `pyproject.toml` (and the Django version from Step 1): no `type X = …` aliases or
`class Box[T]:` generics (3.12) below 3.12, no `except*` below 3.11, no `match` below 3.10. The catalog's scaffold is
Python 3.13 / `requires-python = ">=3.12"` / Django 6.1; Django 5.2 LTS projects can't use 6.x APIs
(`django.tasks`, CSP middleware, `{% partialdef %}`, fetch modes, `MAILERS`).

## Layering — where code goes

| The task asks for | Put it in | Notes |
|---|---|---|
| HTTP in/out (page) | `views.py` (FBV or CBV) + `urls.py` with `app_name` | Thin: parse via form, call one thing, render/redirect. `views-urls-templates.md`. |
| HTTP in/out (API) | `api/` package or `api.py` (DRF serializers + views, or a Ninja router) | Thin: validate via serializer/schema, call a service, serialize. `drf.md` / `ninja.md`. |
| A filter over one model | A `QuerySet` subclass method, exposed with `as_manager()` | Must return a QuerySet; chainable. |
| Behavior of one row | A model method or property | No side effects, no other models' writes. |
| Multi-model work, invariants, side effects | A **service function** in the app's `services.py` | `@transaction.atomic`, keyword-only args, domain exceptions, `on_commit` for side effects, **no `django.http` import**. |
| Input validation | Form / ModelForm / serializer / Ninja schema with explicit fields | Plus a DB constraint for anything that must always hold. |
| Background work | `tasks.py` (`django.tasks` or Celery) calling the same service | `tasks-and-celery.md`. |
| Maintenance / cron | `management/commands/<name>.py` calling a service | `tasks-and-celery.md`. |

Views, viewsets, Ninja operations, admin actions, commands, and tasks all call the **same** service — that's the
point of having one. Don't create a service for a one-line `create()`, and don't add a repository layer over the
ORM. Models never import services or views; the `core` app (if any) imports nothing from other apps; app
dependencies stay acyclic (string FKs `"catalog.Book"` break import cycles).

## Typing with django-stubs

Annotate parameters and returns on everything new; use built-in generics (`list[int]`), `X | None`, and
`from __future__ import annotations` if the module or project already does (it lets a QuerySet method return its own
class). Verified under mypy strict + django-stubs 6.1 + djangorestframework-stubs 3.18:

```python
class BookQuerySet(models.QuerySet["Book"]):
    def visible_to(self, user: AbstractBaseUser | AnonymousUser) -> BookQuerySet:
        published = Q(status=Book.Status.PUBLISHED)
        if not user.is_authenticated:          # request.user may be AnonymousUser — never use it as an owner then
            return self.filter(published)
        return self.filter(published | Q(owner_id=user.pk))

class Book(models.Model):
    objects = BookQuerySet.as_manager()        # no annotation: the plugin infers the manager type
    class Meta:
        ordering = ("title", "pk")             # tuples, so Ruff RUF012 doesn't flag a mutable class attribute
```

- `request.user` is typed `AbstractBaseUser | AnonymousUser` — a function taking "the current user" accepts that
  union (from `django.contrib.auth.base_user` and `django.contrib.auth.models`), checks `is_authenticated`, and uses
  `user.pk`. Import the concrete user class only where you need its fields (`from apps.accounts.models import User`
  in tests, or `get_user_model()` at runtime).
- Querysets: `QuerySet[Book]` (from `django.db.models`) as a return type; the plugin types `Book.objects.filter(...)`.
- DRF generics: `serializers.ModelSerializer[Book]`, `serializers.Serializer[None]` for input-only serializers,
  `viewsets.ModelViewSet[Book]`, `generics.ListAPIView[Book]`; `perform_create(self, serializer:
  BaseSerializer[Book]) -> None` overrides without a `# type: ignore`.
- Ninja: an operation returning `Status(...)` is annotated `-> Status[Book | Message]` (`Status` is generic).
- **Django's own generic classes aren't subscriptable at runtime.** `admin.ModelAdmin[Book]`, `forms.ModelForm[Book]`,
  `UpdateView[Book, BookForm]`, `ListView[Book]` satisfy mypy strict but raise `TypeError: … is not subscriptable` at
  import unless the settings call `django_stubs_ext.monkeypatch()` (verified both ways). Check `grep -rn
  "django_stubs_ext" <settings>` first: present → subscript; absent → write them unsubscripted and match how the
  neighbouring code satisfies mypy (a per-module `disallow_any_generics = false` override, a scoped
  `# type: ignore[type-arg]`) — adding the monkeypatch is a settings change only when the task asks.
  `QuerySet["Book"]`, DRF's `ModelSerializer[Book]`/`ModelViewSet[Book]`, Ninja's `Status[...]`, and factory_boy's
  `DjangoModelFactory[Book]` are subscriptable at runtime without it.
- Stub gaps exist — e.g. django-stubs 6.1.2 types `fetch_mode()` on `QuerySet` but not on the manager, so write
  `Book.objects.all().fetch_mode(models.FETCH_RAISE)`. Prefer a form that type-checks over a `# type: ignore`; when an
  ignore is unavoidable, scope it (`# type: ignore[attr-defined]`) and say why in the report.
- factory_boy 3.3 is generic: `class BookFactory(DjangoModelFactory[Book])`, then `BookFactory.create(...)` returns
  `Book`. Calling `BookFactory(...)` directly returns an untyped value — use `.create()`/`.build()` in typed tests.

## Docstrings

Every new public module, class, function, and method gets a docstring in the project's convention (Ruff
`pydocstyle.convention`, Google if unset): one-line summary in the imperative mood, then `Args:`, `Returns:`,
`Raises:` for anything a caller can handle. Django's inner `class Meta:` is commonly exempted (`D106` ignored) — follow
the config. Test modules are usually exempt (`per-file-ignores` with `D`). `iru-python-docstrings` audits this at
group level; write it now so there is nothing to backfill.

```python
@transaction.atomic
def reserve_copies(*, book_id: int, quantity: int) -> Book:
    """Reserve ``quantity`` copies of a book.

    Args:
        book_id: Primary key of the book.
        quantity: Number of copies to reserve; must be positive.

    Returns:
        The book with its refreshed stock.

    Raises:
        ValueError: If ``quantity`` is not positive.
        OutOfStockError: If fewer than ``quantity`` copies are in stock.
        Book.DoesNotExist: If no book has that primary key.
    """
```

## Errors

- Services raise **domain exceptions** (`class OutOfStockError(Exception)`, under one project base exception where the
  project has one) or `ValidationError` for rule violations; the boundary translates them (a view adds a form error, a
  DRF view raises `ValidationError`/returns 409, a Ninja operation returns `Status(409, …)`). Services never return
  `HttpResponse`s or raise `Http404`.
- Catch specific exceptions; never bare `except:`; chain with `raise X(...) from err`; never swallow an error inside an
  `atomic()` block and keep querying (`TransactionManagementError`) — catch **outside** the block.
- `get_object_or_404` belongs in views only; services use `.get()` and let `DoesNotExist` propagate (documented in
  `Raises:`).
- `assert` is for internal invariants only (stripped by `-O`), never input validation. Ruff `S101` flags it outside
  tests. Error messages assigned to a variable first (`msg = "…"; raise ValueError(msg)`) satisfy Ruff `EM`/`TRY`
  when the project selects them.

## Logging

`logger = logging.getLogger(__name__)` at module level; lazy `%s` arguments (`logger.info("Book %s low", pk)`), never
f-strings in log calls and never `print()`; `logger.exception(...)` inside `except` (then re-raise unless the error
is genuinely handled). Never log passwords, tokens, full request bodies, or personal data.

## Small things

- Never a mutable default argument (`items: list[str] | None = None`); model defaults are callables (`default=list`,
  `default=timezone.now` — no parentheses).
- `timezone.now()`, never `datetime.now()`/`utcnow()` (`USE_TZ = True`); `zoneinfo`, never `pytz`.
- `Decimal` for money (`Decimal("9.99")`, never from a float); `pathlib.Path` over `os.path`.
- f-strings for ordinary formatting — **not** in log calls, not in SQL, not in translatable strings
  (`_("%(n)s books") % {"n": n}`), not for email addresses.
- Keyword-only arguments (`*,`) on service functions; named arguments for booleans.
- No `print`, `breakpoint()`, `pdb`, commented-out code, or `TODO` without a ticket reference.
- No new dependency (`uv add`, `poetry add`, editing `[project] dependencies`) unless the task asks for it — a
  blocker to report, not a quiet decision.
