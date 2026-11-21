# Tests

Every task's implementation comes with the tests that prove it. This skill writes them and runs **only the fast test
files it touched** (SKILL.md Step 6) — never coverage, never the full suite, never the integration tier. The calling
`iru-django-code-one-task-group` owns all of that.

## pytest-django or `manage.py test` — follow the project

| Signal | Write tests as |
|---|---|
| `pytest-django` in the dev dependencies and `DJANGO_SETTINGS_MODULE` in the pytest config | **pytest functions** with fixtures (`client`, `rf`, `settings`, `django_assert_num_queries`, `django_capture_on_commit_callbacks`, `mailoutbox`) — or `TestCase` classes if the touched app's tests are classes (pytest-django runs both). |
| No pytest-django | **`django.test.TestCase`** classes, `setUpTestData()`, `self.client`, `self.assertNumQueries`, `self.captureOnCommitCallbacks`, run by `manage.py test`. |
| `rest_framework` | `APIClient` (pytest) / `APITestCase` (built-in runner) for API endpoints. |
| `ninja` | `ninja.testing.TestClient(router)` for operation logic; the Django test client end to end when CSRF/sessions/middleware matter (the Ninja client bypasses middleware). |

Test placement follows the project: `<app>/tests/test_<concern>.py` (a package with `__init__.py`), a single
`<app>/tests.py`, or a root `tests/` tree; factories in `<app>/tests/factories.py`. Discovery patterns come from
`python_files` (pytest) or `test*.py` (built-in runner) — a file named outside them never runs.

## Database access

- pytest-django blocks the database by default: mark with `pytestmark = pytest.mark.django_db` (module) or
  `@pytest.mark.django_db` (test), or request the `db` fixture from a fixture that creates rows. Each test runs in a
  rolled-back transaction (= `TestCase`).
- `django_db(transaction=True)` / `TransactionTestCase` **only** for real commits: `select_for_update` semantics, code
  that must see `on_commit` fire without capture, `live_server`. It is slower.
- `SimpleTestCase` / no marker for pure logic (forms without models, URL resolving, template tags, validators) — it
  refuses queries, which proves the code doesn't make any.
- Never hard-code primary keys or paths: `reverse("catalog:book-detail", args=[book.pk])`.

## Data: factories, not fixtures

factory_boy (`DjangoModelFactory`), one factory per model, minimal defaults; scenario data lives in the test.
Verified with factory_boy 3.3.3 under mypy strict:

```python
class UserFactory(factory.django.DjangoModelFactory[User]):
    class Meta:
        model = get_user_model()
        skip_postgeneration_save = True          # avoids factory_boy's DeprecationWarning (warnings are errors)

    username = factory.Sequence(lambda n: f"user{n}")
    password = factory.django.Password("unused-in-tests")


class BookFactory(factory.django.DjangoModelFactory[Book]):
    class Meta:
        model = Book

    title = factory.Sequence(lambda n: f"Book {n}")   # Sequence for unique columns — Faker collides
    author = factory.SubFactory(AuthorFactory)
    owner = factory.SubFactory(UserFactory)
    status = Book.Status.PUBLISHED
```

Use `BookFactory.create(...)` / `.build()` (no DB) / `.create_batch(n)` — typed returns. Override related fields with
`author__name=...`; `class Params: draft = factory.Trait(status=Book.Status.DRAFT)` for variants. If the project uses
model_bakery instead, `baker.make(Book, _quantity=3)` / `baker.prepare(...)`. Never JSON fixture files for test data;
never literal passwords for real logins (`secrets.token_urlsafe(16)`), and `force_login()` / `force_authenticate()`
for everything that isn't a login test.

## What to cover

- **The behavior the task added**, through the public surface (service function, view, endpoint), plus meaningful
  variants and boundaries (empty list, zero, max page size).
- **Every exception in the service's `Raises:`** — one test each — and that nothing changed afterwards
  (`refresh_from_db()`).
- **For a state-changing view/endpoint**: the status/redirect, **the database state afterwards**, the validation
  failure (form error / 400 / 422 with the field named), and the denial paths — anonymous (302 to login / 401 / 403),
  another user's object (**404**), wrong method (405), hidden/owner fields in the payload ignored.
- **A query budget on every list** the task adds or changes:

```python
def test_list_is_paginated_without_n_plus_one(
    api_client: APIClient, django_assert_num_queries: DjangoAssertNumQueries
) -> None:
    BookFactory.create_batch(5)

    with django_assert_num_queries(2):  # COUNT + one page with authors joined
        response = api_client.get(reverse("catalog-api:book-list"))

    assert response.status_code == 200
    assert response.json()["count"] == 5
```

  (`self.assertNumQueries(2)` in a `TestCase`.) Create several rows — a budget measured with one row proves nothing.
  On Django 6.1 a test can also pin a hot path with `.fetch_mode(models.FETCH_RAISE)` and expect
  `django.core.exceptions.FieldFetchBlocked` on any lazy load.
- **Side effects deferred with `on_commit`**: they don't fire inside a test transaction unless captured —

```python
def test_low_stock_notification_runs_on_commit(
    django_capture_on_commit_callbacks: DjangoCaptureOnCommitCallbacks,
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO, logger="apps.catalog.services")
    book = BookFactory.create(stock=2)

    with django_capture_on_commit_callbacks(execute=True) as callbacks:
        services.reserve_copies(book_id=book.pk, quantity=1)

    assert len(callbacks) == 1
    assert "low on stock" in caplog.text
```

- **Email**: `mailoutbox` fixture / `django.core.mail.outbox` (the runner swaps every mailer to locmem).
- **`django.tasks`**: the default `ImmediateBackend` runs the task inline (fine for asserting its effect); to assert
  *that* something was enqueued, switch to the dummy backend and read its results (verified on 6.1):

```python
def test_low_stock_enqueues_notification(
    settings: Settings, django_capture_on_commit_callbacks: DjangoCaptureOnCommitCallbacks
) -> None:
    settings.TASKS = {"default": {"BACKEND": "django.tasks.backends.dummy.DummyBackend"}}
    book = BookFactory.create(stock=2)

    with django_capture_on_commit_callbacks(execute=True):
        services.reserve_copies(book_id=book.pk, quantity=1)

    backend = task_backends["default"]          # from django.tasks — not default_task_backend, a proxy
    assert isinstance(backend, DummyBackend)
    [result] = backend.results
    assert result.kwargs == {"book_id": book.pk}
```

  Celery: mock `.delay_on_commit` in the caller's test, and test the task body by calling `.run(...)` directly.
- **Migrations you hand-wrote** (data migrations): only when the task asks — `MigrationExecutor` tests are slow and
  belong to the integration tier.
- **A screen, component or list the task added or changed**: browser UI tests on top of the template-rendering
  tests — every visual state, minimal/typical/many-items data, the LTR/RTL × light/dark screenshot matrix and the
  accessibility check (`ui-implementation.md` §5–§7).
- Not trivia: Django's own behavior (that `CharField(max_length=200)` rejects 201 chars), generated admin, a
  `__str__`. Coverage is an 80% gate at group level, not a goal.

## Writing the test

- **One behavior per test**, named for it (`test_drafts_of_other_users_are_404`); arrange / act / assert visibly
  separated; plain `assert` under pytest.
- `@pytest.mark.parametrize` for input tables (form validation needs no DB); `pytest.raises(X, match="…")`.
- Mock only at edges you own, patched **where the name is looked up** (`mocker.patch("apps.catalog.covers.urlopen")`
  or `unittest.mock.patch`); no real network calls; time via `timezone.now` patched to an aware value.
- Fixtures typed (`def user() -> User:`), pytest-django fixture types imported from `pytest_django`
  (`DjangoAssertNumQueries`, `DjangoCaptureOnCommitCallbacks`, `Settings`) when the project type-checks tests.
- Settings changes through the `settings` fixture or `@override_settings` — never by mutating `django.conf.settings`
  directly, and never `DATABASES`/`CACHES` (DRF caches its settings at import — throttle rates don't change with
  `override_settings`; clear the cache in setup instead).
- Warnings are usually errors (`filterwarnings = ["error::DeprecationWarning"]`): don't add an ignore to make a test
  pass — fix the deprecated call.

## Fast vs. integration

Fast tests need nothing but the interpreter (SQLite in memory via the test settings). A test goes to the integration
tier — marked with the project's marker (`pytestmark = [pytest.mark.integration, pytest.mark.django_db(transaction=True)]`)
or tag (`@tag("integration")`) and placed where the project keeps those tests — when it needs PostgreSQL-specific
behavior (row locks, `contrib.postgres` fields and lookups, full-text search, real constraint races, `AddIndexConcurrently`),
Redis, a Celery worker, a live server, or a browser (browser UI tests carry the `ui` marker instead and live in `tests/ui/` —
`ui-implementation.md` §5; an older project may use its own `browser` marker — follow it). Follow the
project's existing integration fixture (often a `conftest.py` that starts a testcontainers PostgreSQL); don't build a
new one — that's a plan-level change. If the project's pattern is to swap the default database inside
`django_db_modify_db_settings`, the dict must be mutated in place and the cached `connections["default"]` dropped
(verified: reassigning `settings.DATABASES["default"]` leaves the tests on SQLite).

The coverage gate counts only fast tests, so the logic of a feature needs fast tests even when an integration test
also covers it — keep the PostgreSQL-only assertion in the integration test and everything else in fast ones.

## What to run here

Only the fast test files this task created or changed:

- pytest-django: `<run> pytest <paths> -m "not integration"` (`--no-cov` if `addopts` contains `--cov`; a node id
  `path::test_name` to re-run one test).
- Built-in runner: `<run> python manage.py test <dotted.labels> --settings=<test settings> --exclude-tag=integration --noinput`.

Never the integration marker/tag, never the `ui` (browser) marker, never coverage, never `-n auto`/`--parallel`,
never the whole suite. Browser UI tests are written here and run by the group behind `.integration-test.lock`.
