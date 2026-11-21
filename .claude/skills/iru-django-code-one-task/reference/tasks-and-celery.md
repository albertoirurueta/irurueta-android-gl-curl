# Background work and management commands

Read this when the task enqueues background work, adds a `django.tasks` task or a Celery task, or adds a management
command. Use what the project already has — **never introduce Celery** (or a `django.tasks` production backend) as a
side effect of a task; that's a plan-level infrastructure decision.

| Project has | Use |
|---|---|
| A `TASKS` setting / `from django.tasks import task` (Django 6.0+; `django_tasks` backport on 5.2) | `@task` functions in `<app>/tasks.py`, `.enqueue()` |
| Celery (`config/celery.py`, `@shared_task`) | `@shared_task` in `<app>/tasks.py`, `.delay_on_commit()` |
| Neither | Ask (SKILL.md Step 4) — or, if the work only needs a clock, a management command run by cron/the platform scheduler |

## Rules for any task, whatever runs it

- **Enqueue on commit**, from the service, inside its transaction: `transaction.on_commit(partial(notify_low_stock.enqueue,
  book_id=book.pk))` (verified on 6.1) or Celery's `notify_low_stock.delay_on_commit(book.pk)`. Enqueuing before the
  commit lets the worker run before the row exists — or after a rollback.
- **Pass ids, re-fetch inside**, and handle "row gone". Arguments must be JSON-serializable: no instances, `datetime`,
  `Decimal`, `UUID`, or sets (`django.tasks` raises `TypeError` at enqueue; tuples become lists).
- **Idempotent**: a task may run twice — guard with a state column (`notified_at`), a unique constraint, a conditional
  `update()` that claims the row, or a provider idempotency key.
- **Thin bodies** calling the same service the views use; short, single-purpose, bounded (time limits / retries the
  backend supports). `logger.exception(...)` then re-raise — never swallow.
- No `request` in a task (build URLs from settings or `Site`); activate the right language/time zone explicitly
  (`translation.override(...)`). From async code: `await task.aenqueue(...)`, never a fire-and-forget
  `asyncio.create_task()`.

## `django.tasks`

```python
@task
def notify_low_stock(book_id: int) -> None:
    """Tell staff that a book is running low."""
    ...
```

Module-level functions only (nested functions/lambdas/methods are rejected); `@task(queue_name="emails",
takes_context=True)` when the task needs `context` (first parameter named `context`). Every queue a task uses must be
listed in the backend's `QUEUES`. The built-in `ImmediateBackend` (runs inline) and `DummyBackend` are dev/test only;
production needs the project's third-party backend (`django_tasks_db.DatabaseBackend` + `manage.py db_worker
--queue-name '*'`, RQ, …) — check it supports what you use (`priority`, `run_after`, retries).

## Celery

`@shared_task` in `<app>/tasks.py`; `bind=True` when the task needs `self.request`/`self.retry`; bounded retries for
transient errors only — `autoretry_for=(ConnectionError, TimeoutError), retry_backoff=True, retry_jitter=True,
max_retries=5`; `acks_late=True` only for idempotent tasks; `ignore_result=True` when nobody reads the result. Never
`result.get()` in a view or another task. New `CELERY_*` settings are misspelt silently — check the name. Routing
(`CELERY_TASK_ROUTES`) and beat schedules are settings changes: only when the task asks.

## Management commands

```python
class Command(BaseCommand):
    help = "Re-send low-stock notifications for books under the threshold."

    def add_arguments(self, parser: CommandParser) -> None:
        parser.add_argument("--threshold", type=int, default=2)
        parser.add_argument("--dry-run", action="store_true")

    def handle(self, *args: Any, **options: Any) -> None:
        count = services.renotify_low_stock(threshold=options["threshold"], dry_run=options["dry_run"])
        if options["verbosity"] > 0:
            self.stdout.write(self.style.SUCCESS(f"Queued {count} notifications."))
```

`<app>/management/commands/<name>.py` (packages with `__init__.py`); `handle()` parses, calls a service, prints via
`self.stdout.write` (never `print`); `raise CommandError(...)` on failure; idempotent and wrapped in `atomic()` when it
writes several rows; secrets from the environment, never CLI arguments; don't shadow a built-in command name.

## Tests for this area

- The caller's test asserts the enqueue happened after commit (`django_capture_on_commit_callbacks(execute=True)` plus
  the `DummyBackend` results, or a mocked `.delay_on_commit`) — see `testing.md`.
- The task body is tested as a plain function call (`notify_low_stock.func(book_id=…)` / Celery `task.run(…)`),
  including the "row gone" and "already done" paths.
- Commands with `call_command("renotify_low_stock", threshold=1, stdout=out)` (keyword options skip the parser — pass
  correctly typed values), asserting database state and `out.getvalue()`; `pytest.raises(CommandError)` for bad input.
- A real worker (`start_worker`, `pytest-celery`) is integration-tier, never fast.
