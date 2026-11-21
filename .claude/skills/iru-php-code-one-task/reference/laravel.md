# Laravel

Conventions for a Laravel application (`laravel/framework` + `artisan`). Read `laravel/framework`'s version from
`composer.lock` first: the attribute forms below (`#[Fillable]`, `#[Authorize]`, `#[Tries]`, `#[Bind]`, …) are
Laravel 13; on 11/12 use the property/method equivalents named next to each, and on ≤ 10 (HTTP `Kernel`,
`$casts` property) follow what the app already does. If the repository ships Laravel Boost guidelines
(`CLAUDE.md`/`.ai/guidelines`), they are the project's own rules and win over this file.

## Where code goes

| The task asks for | Put it in | Notes |
|---|---|---|
| An HTTP endpoint | Route in `routes/web.php`/`routes/api.php`, a controller in `app/Http/Controllers` | Controller method: read the request, call one thing, return a response. |
| Input validation | A Form Request (`php artisan make:request` shape) in `app/Http/Requests` | Inline `$request->validate()` only for one or two fields. |
| Authorization | A policy in `app/Policies` (auto-discovered for `App\Models\X` → `XPolicy`) | Gates only for actions with no model. |
| Business logic | An action (`app/Actions/AddBook::handle()`) or service class | Callable from controllers, jobs, commands, and tests alike. |
| API response shape | A `JsonResource` in `app/Http/Resources` | Never return a model's `toArray()` from an API. |
| Slow or unreliable work | A queued job in `app/Jobs` (`ShouldQueue` + `Queueable`) | Mail, third-party calls, imports, image processing. |
| Schema change | A new migration | Never edit one that has run outside your machine. |
| Wiring | A service provider, or `bootstrap/app.php` for middleware/exceptions/routing | No HTTP `Kernel.php` on Laravel 11+. |

Use the `make:*` generators' file shapes (and `--test`/`--pest` to get a test stub) — but write the files yourself
if `php artisan` isn't runnable here; don't create directories the skeleton doesn't have unless the task needs them.

## Controllers, requests, policies

- **Thin controllers.** Validation → Form Request; authorization → policy; work → action/service; response →
  redirect, view, or resource.
- **Form Requests**: `rules()` with array syntax (`['required', 'string', 'max:255']`), `Rule::enum()`,
  `Rule::exists()`/`Rule::unique()->ignore($model)`; use `$request->validated()` or
  `$request->safe()->only([...])` — **never `$request->all()` or `$request->input()` for writes**. Custom rules
  implement `Illuminate\Contracts\Validation\ValidationRule`. Laravel 13: `#[FailOnUnknownFields]` rejects
  unexpected keys.
- **Authorize on the route or action**, so a forgotten check is impossible: `->can('update', 'book')` route
  middleware, `#[Authorize('update', 'book')]` (Laravel 13), or `Gate::authorize('update', $book)` in the method.
  Not `$this->authorize()` in new apps (the base controller no longer has `AuthorizesRequests`). **Order
  matters**: route middleware and `#[Authorize]` run *before* the Form Request is resolved, but
  `Gate::authorize()` inside the method runs *after* it validated — an unauthorized user sending invalid data then
  gets 422 with field errors instead of 403. With a Form Request, authorize in middleware/`#[Authorize]` or in the
  Form Request's own `authorize()` (which runs before `rules()`); keep `Gate::authorize()` in the method for
  actions without one. Don't check the same ability in two places that can drift.
- **Route model binding** (`Book $book`), scoped bindings for nested resources (`->scopeBindings()`).

```php
<?php

namespace App\Http\Controllers;

use App\Actions\AddBook;
use App\Http\Requests\StoreBookRequest;
use App\Http\Resources\BookResource;
use App\Models\Book;
use Illuminate\Routing\Attributes\Controllers\Authorize;

class BookController extends Controller
{
    #[Authorize('create', Book::class)]                     // BookPolicy::create(), runs before the Form Request
    public function store(StoreBookRequest $request, AddBook $addBook): BookResource
    {
        return new BookResource($addBook->handle($request->validated()));
    }
}
```

## Container and configuration

- **Constructor or method injection**; let the container autowire. Facades are fine in application code;
  inject when the class is reused outside a request or needs a fake.
- Bind an interface only where there are two implementations or a test needs one: provider `bind()`/`singleton()`,
  or Laravel 13 `#[Bind(Impl::class)]` on the interface. **Under Octane, request-scoped state must be `scoped()`
  (`#[Scoped]`), never `singleton()`.**
- **`env()` only inside `config/*.php`**; everywhere else `config('services.catalogue.url')` — after
  `config:cache`, `env()` returns `null`. A new setting = a config key + an `.env.example` entry (no real value).

## Eloquent and the database

- **Mass assignment**: `#[Fillable([...])]` (Laravel 13) or `protected $fillable = [...]`; never
  `$guarded = []`/`#[Unguarded]` on a model that receives request data.
- **Casts** via the `casts(): array` method (Laravel 11+): enums, `datetime`/`immutable_datetime`, `encrypted`,
  `AsCollection`, custom casts.
- **N+1**: eager-load what's used (`with()`, `withCount()`, `load()`); the app should run
  `Model::shouldBeStrict(! app()->isProduction())` (lazy loading, silently discarded attributes, and missing
  attributes throw). A test that passes only because strict mode is off is a defect.
- **Large sets**: `chunkById()`/`lazyById()`/`cursor()`, paginate lists — never `Model::all()` on a growing table.
- **Multi-step writes in `DB::transaction()`**; remember query-builder `update()`/`delete()` skip model events.
- **Migrations**: anonymous class (`return new class extends Migration`), `foreignId('author_id')->constrained()`
  with an explicit `cascadeOnDelete()`/`restrictOnDelete()` choice, indexes on filtered/sorted columns, a working
  `down()`. `->change()` must restate every modifier you want to keep. Never edit a migration that already ran
  in a shared environment — add a new one.
- Factories for every new model (`HasFactory`), with states for meaningful variants; seeders don't replace them in
  tests.

## Queues, events, scheduling

- **Jobs are idempotent** (a job can run more than once — check before acting, use unique keys/upserts) and
  **bounded**: `#[Tries(3)]`, `#[Backoff([1, 5])]`, `#[Timeout(10)]` (Laravel 13) or the `$tries`/`backoff()`/
  `$timeout` members; `$this->fail($e)` for errors retrying can't fix.
- **Dispatch after commit**: `->afterCommit()` (or `ShouldDispatchAfterCommit`/`after_commit` on the
  connection) when dispatching inside a transaction — otherwise the worker can run before the row exists.
- Pass models (serialized as IDs) not large payloads; `ShouldBeUnique`/`WithoutOverlapping` where two copies must
  not run concurrently. HTTP client retries multiply with job tries — keep one of them small.
- Events for side effects others react to, not to hide the main flow. Scheduled tasks: `onOneServer()`,
  `withoutOverlapping()`.

## Views and UI

Blade components, Livewire, and paginated lists follow `ui-contract.md` as mapped in `ui-implementation.md`: decompose
the screen first, compose `<x-…>` components with `@props`/slots, keep queries and authorization out of views (the
controller passes immutable view data), `wire:model` only on form fields, and every list a `paginate()`/
`cursorPaginate()` query rendered through one item component with `wire:key` — never `Model::all()` into a view.
Browser UI tests (Playwright + axe, screenshot matrix) are described there; this skill writes them but never runs
them.

## Security specifics

Blade `{{ }}` escapes; `{!! !!}` only for HTML the app built. CSRF middleware stays on (`@csrf` in forms;
excluding a route needs a reason in the task). `Hash::make()`; Sanctum token abilities and expiry; signed URLs
(`URL::temporarySignedRoute`) for login-less links; named rate limiters on auth and public API routes;
`Storage::disk('private')` + an authorized download route or `temporaryUrl()` for uploads; `Http::timeout()` on
every outgoing call. `DB::raw`/`whereRaw` only with bindings.

## Tests

- **Feature tests through HTTP** for each route: success, validation failure (`assertInvalid(['title'])` /
  422 + `assertJsonValidationErrors`), and authorization (403 for the wrong user, redirect/401 for guests).
- **Database**: `RefreshDatabase` (or `LazilyRefreshDatabase`), factories, `assertDatabaseHas`/`Missing`/
  `Count`, `assertModelExists`. The skeleton's `phpunit.xml` runs on **SQLite `:memory:`** — those feature tests
  are fast tests. If this app's `phpunit.xml`/`.env.testing` points at MySQL/PostgreSQL/Redis, they need the
  running service and the group runs them in its container stage; don't try to start Sail from this skill.
- **Fake the edges**: `Queue::fake()`/`Bus::fake()` + `assertPushed`, `Event::fake([...])`, `Mail::fake()`,
  `Notification::fake()`, `Storage::fake('private')`, `Http::fake([...])` with `Http::preventStrayRequests()`,
  `Process::fake()`. Time: `$this->freezeTime()`/`freezeSecond()`, `$this->travel(5)->days()`.
- `$this->mock(Foo::class)`/`spy()` (Mockery, bound into the container) only for classes you own and only
  where no framework fake exists.
- Unit tests in `tests/Unit` extend `PHPUnit\Framework\TestCase` — no container there.
- Views: `$this->blade(...)`/`$this->component(...)` and `Livewire::test(...)` for fast behaviour tests; screenshots
  and accessibility checks come from the browser UI tests in `ui-implementation.md`.

```php
<?php

use App\Jobs\SyncBookWithCatalogue;
use App\Models\Author;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Queue;

uses(RefreshDatabase::class);

it('lets a librarian add a book and queues the catalogue sync', function () {
    Queue::fake();
    $librarian = User::factory()->librarian()->create();

    $this->actingAs($librarian)
        ->postJson('/api/books', ['title' => 'Kindred', 'author_id' => Author::factory()->create()->id])
        ->assertCreated()
        ->assertJsonPath('data.title', 'Kindred');

    $this->assertDatabaseHas('books', ['title' => 'Kindred']);
    Queue::assertPushed(SyncBookWithCatalogue::class);
});

it('rejects a member', function () {
    $this->actingAs(User::factory()->create())
        ->postJson('/api/books', ['title' => 'Kindred'])
        ->assertForbidden();
});
```

(`User::factory()->librarian()` stands for whatever factory state the app defines — use the real one.)
