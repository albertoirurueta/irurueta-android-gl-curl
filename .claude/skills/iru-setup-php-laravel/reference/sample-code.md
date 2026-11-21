# Sample code and tests (Step 9)

A deliberately small vertical slice that follows `iru-php-code-one-task/reference/laravel.md`: thin controller →
Form Request (validation) → route-level policy check (authorization) → action (work) → `JsonResource` (response),
plus feature tests covering success, validation, authorization and authentication. It reuses the skeleton's `User`
model, factory and migration, so it adds no migration. Verified with Laravel 13.36, Pest 5.3 / PHPUnit 12.5,
Larastan 3.13 at level 9, Pint 1.32: all tests pass, 100% line coverage of `app/`, zero PHPStan errors,
`pint --test` clean. Files keep the skeleton's style (no `declare(strict_types=1);`, non-final classes) — Pint's
`laravel` preset and the arch presets accept it.

## Production code

`app/Actions/RenameUser.php`:

```php
<?php

namespace App\Actions;

use App\Models\User;

class RenameUser
{
    /**
     * @param  array{name: string}  $data
     */
    public function handle(User $user, array $data): User
    {
        $user->update(['name' => $data['name']]);

        return $user;
    }
}
```

`app/Http/Requests/RenameUserRequest.php`:

```php
<?php

namespace App\Http\Requests;

use Illuminate\Contracts\Validation\ValidationRule;
use Illuminate\Foundation\Http\FormRequest;

class RenameUserRequest extends FormRequest
{
    /**
     * Authorization runs on the route (`->can('update', 'user')`), before this request validates.
     */
    public function authorize(): bool
    {
        return true;
    }

    /**
     * @return array<string, ValidationRule|array<mixed>|string>
     */
    public function rules(): array
    {
        return [
            'name' => ['required', 'string', 'max:255'],
        ];
    }
}
```

`app/Http/Resources/UserResource.php`:

```php
<?php

namespace App\Http\Resources;

use App\Models\User;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

/**
 * @mixin User
 */
class UserResource extends JsonResource
{
    /**
     * @return array<string, mixed>
     */
    public function toArray(Request $request): array
    {
        return [
            'id' => $this->id,
            'name' => $this->name,
        ];
    }
}
```

`app/Policies/UserPolicy.php` (auto-discovered for `App\Models\User`):

```php
<?php

namespace App\Policies;

use App\Models\User;

class UserPolicy
{
    /**
     * A user may only change their own profile.
     */
    public function update(User $user, User $model): bool
    {
        return $user->is($model);
    }
}
```

`app/Http/Controllers/UserController.php`:

```php
<?php

namespace App\Http\Controllers;

use App\Actions\RenameUser;
use App\Http\Requests\RenameUserRequest;
use App\Http\Resources\UserResource;
use App\Models\User;

class UserController extends Controller
{
    public function update(RenameUserRequest $request, User $user, RenameUser $renameUser): UserResource
    {
        /** @var array{name: string} $data */
        $data = $request->validated();

        return new UserResource($renameUser->handle($user, $data));
    }
}
```

(The `@var` narrows `validated()`'s `array<string, mixed>` for PHPStan level 9 — without it Larastan reports
`argument.type` on the `handle()` call.)

Append to `routes/web.php` (keep the skeleton's `/` route; add the `use` line at the top). The `can:` middleware
runs before the Form Request resolves, so an unauthorized user gets 403, not 422:

```php
use App\Http\Controllers\UserController;

Route::patch('/users/{user}', [UserController::class, 'update'])
    ->middleware(['auth', 'can:update,user'])
    ->name('users.update');
```

The route lives in `web.php` because the skeleton has no `routes/api.php` until `php artisan install:api`
(Sanctum). JSON requests in tests get 401 from `auth` and skip CSRF (Laravel disables it while running tests).

## Pest tests (`test-framework: pest`)

`tests/Pest.php` — replace the boilerplate `pest --init` writes (it carries an example `toBeOne` expectation and a
`something()` helper):

```php
<?php

use Tests\TestCase;

/*
|--------------------------------------------------------------------------
| Test Case
|--------------------------------------------------------------------------
|
| Feature tests boot the application through Tests\TestCase. Unit tests stay
| on PHPUnit's plain TestCase: no container, no database.
|
*/

pest()->extend(TestCase::class)->in('Feature');
```

With an integration suite (Step 10) the last line becomes `->in('Feature', 'Integration');`.

`tests/Unit/ArchTest.php`:

```php
<?php

arch()->preset()->php();

arch()->preset()->security();

arch()->preset()->laravel();

arch('actions expose a single handle() entry point')
    ->expect('App\Actions')
    ->toHaveMethod('handle');
```

`tests/Feature/RenameUserTest.php`:

```php
<?php

use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;

uses(RefreshDatabase::class);

it('lets a user rename themselves', function () {
    $user = User::factory()->create(['name' => 'Ada']);

    $this->actingAs($user)
        ->patchJson(route('users.update', $user), ['name' => 'Ada Lovelace'])
        ->assertOk()
        ->assertJsonPath('data.name', 'Ada Lovelace');

    $this->assertDatabaseHas('users', ['id' => $user->id, 'name' => 'Ada Lovelace']);
});

it('rejects an empty name', function () {
    $user = User::factory()->create();

    $this->actingAs($user)
        ->patchJson(route('users.update', $user), ['name' => ''])
        ->assertUnprocessable()
        ->assertJsonValidationErrors(['name']);
});

it('forbids renaming someone else', function () {
    $other = User::factory()->create(['name' => 'Grace']);

    $this->actingAs(User::factory()->create())
        ->patchJson(route('users.update', $other), ['name' => 'Mallory'])
        ->assertForbidden();

    $this->assertDatabaseHas('users', ['id' => $other->id, 'name' => 'Grace']);
});

it('requires authentication', function () {
    $user = User::factory()->create();

    $this->patchJson(route('users.update', $user), ['name' => 'Anonymous'])
        ->assertUnauthorized();
});
```

`tests/Feature/HomePageTest.php`:

```php
<?php

it('serves the home page', function () {
    $this->get('/')->assertOk();
});
```

`tests/Unit/RenameUserRequestTest.php` (no container — `rules()` is plain PHP):

```php
<?php

use App\Http\Requests\RenameUserRequest;

it('requires a short string name', function () {
    expect((new RenameUserRequest)->rules())
        ->toBe(['name' => ['required', 'string', 'max:255']]);
});
```

## PHPUnit tests (`test-framework: phpunit`)

No arch tests (Pest-only feature). `tests/Feature/RenameUserTest.php`:

```php
<?php

namespace Tests\Feature;

use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use PHPUnit\Framework\Attributes\Test;
use Tests\TestCase;

class RenameUserTest extends TestCase
{
    use RefreshDatabase;

    #[Test]
    public function a_user_can_rename_themselves(): void
    {
        $user = User::factory()->create(['name' => 'Ada']);

        $this->actingAs($user)
            ->patchJson(route('users.update', $user), ['name' => 'Ada Lovelace'])
            ->assertOk()
            ->assertJsonPath('data.name', 'Ada Lovelace');

        $this->assertDatabaseHas('users', ['id' => $user->id, 'name' => 'Ada Lovelace']);
    }

    #[Test]
    public function an_empty_name_is_rejected(): void
    {
        $user = User::factory()->create();

        $this->actingAs($user)
            ->patchJson(route('users.update', $user), ['name' => ''])
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['name']);
    }

    #[Test]
    public function renaming_someone_else_is_forbidden(): void
    {
        $other = User::factory()->create(['name' => 'Grace']);

        $this->actingAs(User::factory()->create())
            ->patchJson(route('users.update', $other), ['name' => 'Mallory'])
            ->assertForbidden();

        $this->assertDatabaseHas('users', ['id' => $other->id, 'name' => 'Grace']);
    }

    #[Test]
    public function guests_must_authenticate(): void
    {
        $user = User::factory()->create();

        $this->patchJson(route('users.update', $user), ['name' => 'Anonymous'])
            ->assertUnauthorized();
    }

    #[Test]
    public function the_home_page_is_served(): void
    {
        $this->get('/')->assertOk();
    }
}
```

`tests/Unit/RenameUserRequestTest.php`:

```php
<?php

namespace Tests\Unit;

use App\Http\Requests\RenameUserRequest;
use PHPUnit\Framework\Attributes\Test;
use PHPUnit\Framework\TestCase;

class RenameUserRequestTest extends TestCase
{
    #[Test]
    public function the_name_must_be_a_short_string(): void
    {
        $this->assertSame(['name' => ['required', 'string', 'max:255']], (new RenameUserRequest)->rules());
    }
}
```

## Integration test (Step 10 only)

`tests/Integration/UserPersistenceTest.php` (Pest form; with PHPUnit, the same two tests as a `Tests\TestCase`
subclass using `RefreshDatabase` and `$this->expectException(...)`). Verified against MySQL 8.4:

```php
<?php

use App\Models\User;
use Illuminate\Database\UniqueConstraintViolationException;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;

uses(RefreshDatabase::class);

it('runs against the configured database server, not in-memory SQLite', function () {
    expect(DB::connection()->getDriverName())->not->toBe('sqlite');
});

it('enforces the unique e-mail index', function () {
    User::factory()->create(['email' => 'ada@example.com']);

    User::factory()->create(['email' => 'ada@example.com']);
})->throws(UniqueConstraintViolationException::class);
```

Import the exception class (`use Illuminate\Database\UniqueConstraintViolationException;`) rather than writing
it fully qualified inline — Pint's `fully_qualified_strict_types` fixer flags the inline form.

## Browser UI tests (Step 9)

Playwright + axe-core against `php artisan serve`, following the catalog's UI contract (screens/components captured
per state × direction × theme into `.ui-screenshots/`, accessibility at the `.ui-quality.yml` level). Verified
(2026-10-16) on a fresh `laravel/laravel` skeleton (`laravel/framework` 13.37) with `@playwright/test` 1.64.1,
`@axe-core/playwright` 4.14, Chromium: 6 passed, five PNGs under `.ui-screenshots/screen/WelcomePage/`,
`UI_SCREENSHOTS_DIR` honoured, `report` and `off` levels pass with the stock page's violations listed / not run, and
`tsc --strict` clean (Playwright transpiles the TypeScript itself; no `tsconfig.json` is needed).

`.ui-quality.yml` (repository root; `accessibility` from Step 0/Step 2):

```yaml
# .ui-quality.yml — at the repository root; written by the iru-setup-* scaffolds, read by the coding and test skills.
accessibility: enforce      # enforce: violations fail the UI tests | report: listed, never failing | off: not run
directions: [ltr, rtl]      # screenshot matrix directions
themes: [light, dark]       # screenshot matrix themes
```

`package.json` additions (the rest of the skeleton's file stays):

```json
"scripts": {
    "test:e2e": "playwright test"
},
"devDependencies": {
    "@axe-core/playwright": "^4.14.0",
    "@playwright/test": "^1.64.0"
}
```

`playwright.config.ts`:

```ts
import { defineConfig, devices } from '@playwright/test';

// Browser UI tests (tests/e2e/specs) against `php artisan serve` with the app's .env (SQLite by default).
// Screenshots go to .ui-screenshots/ through tests/e2e/support/ui-screenshots.ts, not to test-results/.
const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:8000';

export default defineConfig({
  testDir: './tests/e2e/specs',
  outputDir: 'test-results',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL,
    locale: 'en-US',
    timezoneId: 'UTC',
    reducedMotion: 'reduce',
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } },
    },
  ],
  webServer: {
    command: 'php artisan serve --host=127.0.0.1 --port=8000',
    url: `${baseURL}/up`,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
```

`webServer` serves the app with its own `.env` (the skeleton's SQLite database, migrated; `php artisan serve` passes
only a few variables such as `APP_ENV` through to the PHP server, so configure the database in `.env`, not here).
`E2E_BASE_URL` points the tests at an already running server instead (another port, a container).

`tests/e2e/support/ui-screenshots.ts` — the one shared helper; every spec goes through it. Same API as the helper
`iru-php-code-one-task` documents (`uiQuality`, `screenshotsDir`, `applyDirectionAndTheme`, `expectDirection`,
`captureUiState`, `checkAccessibility`), plus the client-side direction fallback the skeleton needs and a wait for
finite CSS transitions (the welcome page's entrance fades ignore `prefers-reduced-motion`, and axe measured
mid-fade colours before it was added):

```ts
/**
 * Shared helper for the browser UI tests: reads `.ui-quality.yml`, switches direction and theme, captures
 * screenshots into the repository-wide `.ui-screenshots/` layout and runs the axe-core accessibility check.
 *
 * Screenshot path: `<dir>/<kind>/<id>/<state>--<direction>--<theme>[--<variant>…].png`, where `<dir>` is
 * `UI_SCREENSHOTS_DIR` or `<repository root>/.ui-screenshots`.
 */
import { existsSync, mkdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import AxeBuilder from '@axe-core/playwright';
import { expect, type Page, type TestInfo } from '@playwright/test';

export type Kind = 'component' | 'screen' | 'flow';
export type Direction = 'ltr' | 'rtl';
export type AccessibilityLevel = 'enforce' | 'report' | 'off' | 'none';

export interface UiQuality {
  accessibility: AccessibilityLevel;
  directions: Direction[];
  themes: string[];
}

export interface UiState {
  kind: Kind;
  /** Component/screen type name as in code (`WelcomePage`), or a kebab-case flow name. */
  id: string;
  /** Kebab-case visual state (`default`, `empty`, `long-content`), or `NN-<step>` for a flow. */
  state: string;
  direction: Direction;
  theme: string;
  /** Optional extra segments: platform, then form factor or size (`desktop`, `w1280`, `large-text`). */
  variants?: string[];
}

const THEMES = ['light', 'dark', 'high-contrast'];
const KEBAB = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const TYPE_NAME = /^[A-Za-z][A-Za-z0-9]*$/;
const RTL_LANGUAGE = 'ar';
const LTR_LANGUAGE = 'en';

let cachedQuality: UiQuality | undefined;

/** The directory holding `.git`, walking up from the current directory; the current directory if none. */
export function repositoryRoot(): string {
  let dir = process.cwd();
  for (;;) {
    if (existsSync(join(dir, '.git'))) return dir;
    const parent = dirname(dir);
    if (parent === dir) return process.cwd();
    dir = parent;
  }
}

function listValue(raw: string): string[] {
  return raw
    .replace(/^\[|\]$/g, '')
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

/** `.ui-quality.yml` at the repository root; a missing file or key means the contract defaults. */
export function uiQuality(): UiQuality {
  if (cachedQuality) return cachedQuality;
  const quality: UiQuality = { accessibility: 'enforce', directions: ['ltr', 'rtl'], themes: ['light', 'dark'] };
  const file = join(repositoryRoot(), '.ui-quality.yml');
  if (existsSync(file)) {
    for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
      const match = /^([a-z]+):\s*([^#]*)/.exec(line.trim());
      if (!match) continue;
      const [, key, value] = match;
      if (key === 'accessibility' && ['enforce', 'report', 'off', 'none'].includes(value.trim())) {
        quality.accessibility = value.trim() as AccessibilityLevel;
      } else if (key === 'directions') {
        quality.directions = listValue(value.trim()).filter((d): d is Direction => d === 'ltr' || d === 'rtl');
      } else if (key === 'themes') {
        quality.themes = listValue(value.trim()).filter((t) => THEMES.includes(t));
      }
    }
  }
  cachedQuality = quality;
  return quality;
}

/** `UI_SCREENSHOTS_DIR` (absolute path), else `<repository root>/.ui-screenshots`. */
export function screenshotsDir(): string {
  return process.env.UI_SCREENSHOTS_DIR || join(repositoryRoot(), '.ui-screenshots');
}

/**
 * Call before `page.goto()`. Emulates the colour scheme (and forced colours for `high-contrast`), asks the
 * server for a right-to-left locale through `Accept-Language`, and — only when the server-rendered `<html>`
 * does not carry the requested `dir` (no locale-selection mechanism yet) — sets `dir` client-side as a fallback,
 * marking the document with `data-ui-dir-fallback`.
 */
export async function applyDirectionAndTheme(page: Page, direction: Direction, theme: string): Promise<void> {
  if (!THEMES.includes(theme)) throw new Error(`Unknown theme "${theme}"`);
  await page.emulateMedia({
    colorScheme: theme === 'dark' ? 'dark' : 'light',
    forcedColors: theme === 'high-contrast' ? 'active' : 'none',
  });
  await page.setExtraHTTPHeaders({ 'Accept-Language': direction === 'rtl' ? RTL_LANGUAGE : LTR_LANGUAGE });
  await page.addInitScript((dir: string) => {
    document.addEventListener('DOMContentLoaded', () => {
      const html = document.documentElement;
      if (html.getAttribute('dir') !== dir) {
        html.setAttribute('dir', dir);
        html.dataset.uiDirFallback = 'true';
      }
    });
  }, direction);
}

/** After navigation: asserts the page renders in the requested direction (server-rendered, or the fallback). */
export async function expectDirection(page: Page, direction: Direction): Promise<void> {
  await expect
    .poll(() => page.evaluate(() => getComputedStyle(document.documentElement).direction))
    .toBe(direction);
}

/** Waits for web fonts and for finite CSS animations/transitions (e.g. entrance fades) to finish. */
async function settle(page: Page): Promise<void> {
  await page.evaluate(async () => {
    await document.fonts.ready;
    const finite = document
      .getAnimations()
      .filter((animation) => animation.effect?.getComputedTiming().endTime !== Infinity);
    await Promise.all(finite.map((animation) => animation.finished.catch(() => undefined)));
  });
}

/** Captures the current page as `<kind>/<id>/<state>--<direction>--<theme>[--<variant>…].png`; returns the path. */
export async function captureUiState(page: Page, ui: UiState): Promise<string> {
  const variants = ui.variants ?? [];
  if (!['component', 'screen', 'flow'].includes(ui.kind)) throw new Error(`Unknown kind "${ui.kind}"`);
  if (!(ui.kind === 'flow' ? KEBAB : TYPE_NAME).test(ui.id)) throw new Error(`Invalid ${ui.kind} id "${ui.id}"`);
  if (!KEBAB.test(ui.state)) throw new Error(`State "${ui.state}" must be kebab-case`);
  if (ui.direction !== 'ltr' && ui.direction !== 'rtl') throw new Error(`Unknown direction "${ui.direction}"`);
  if (!THEMES.includes(ui.theme)) throw new Error(`Unknown theme "${ui.theme}"`);
  for (const variant of variants) {
    if (!KEBAB.test(variant)) throw new Error(`Variant "${variant}" must be kebab-case`);
  }
  const name = [ui.state, ui.direction, ui.theme, ...variants].join('--');
  const path = join(screenshotsDir(), ui.kind, ui.id, `${name}.png`);
  mkdirSync(dirname(path), { recursive: true });
  await settle(page);
  await page.screenshot({ path, fullPage: true, animations: 'disabled', caret: 'hide' });
  return path;
}

/** Runs axe-core (WCAG 2.x A/AA) at the `.ui-quality.yml` level: enforce fails, report annotates, off skips. */
export async function checkAccessibility(page: Page, testInfo: TestInfo): Promise<void> {
  const level = uiQuality().accessibility;
  if (level === 'off' || level === 'none') return;
  await settle(page);
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze();
  await testInfo.attach('accessibility-scan-results', {
    body: JSON.stringify(results, null, 2),
    contentType: 'application/json',
  });
  const violations = results.violations.map((v) => `${v.id} × ${v.nodes.length}`);
  if (level === 'enforce') {
    expect(violations, 'axe-core accessibility violations (rule id × nodes)').toEqual([]);
  } else if (violations.length > 0) {
    const description = violations.join(', ');
    testInfo.annotations.push({ type: 'accessibility', description });
    console.warn(`[accessibility: report] ${testInfo.title}: ${description}`);
  }
}
```

`tests/e2e/specs/WelcomePage.spec.ts` — the skeleton's welcome page as the sample screen:

```ts
import { expect, test } from '@playwright/test';
import {
  applyDirectionAndTheme,
  captureUiState,
  checkAccessibility,
  expectDirection,
  uiQuality,
} from '../support/ui-screenshots';

const quality = uiQuality();

test.describe('WelcomePage', () => {
  // Behaviour is asserted once (left-to-right, light); the matrix below only captures.
  test('renders the welcome page', async ({ page }) => {
    await applyDirectionAndTheme(page, 'ltr', 'light');
    const response = await page.goto('/');
    expect(response?.ok()).toBe(true);
    await expect(page.locator('main')).toBeVisible();
    await expect(page).toHaveTitle(/.+/);
  });

  for (const direction of quality.directions) {
    for (const theme of quality.themes) {
      test(`default state, ${direction}, ${theme}`, async ({ page }, testInfo) => {
        await applyDirectionAndTheme(page, direction, theme);
        await page.goto('/');
        await expectDirection(page, direction);
        await captureUiState(page, { kind: 'screen', id: 'WelcomePage', state: 'default', direction, theme });
        await checkAccessibility(page, testInfo);
      });
    }
  }

  test('default state, ltr, high-contrast', async ({ page }) => {
    await applyDirectionAndTheme(page, 'ltr', 'high-contrast');
    await page.goto('/');
    await captureUiState(page, {
      kind: 'screen',
      id: 'WelcomePage',
      state: 'default',
      direction: 'ltr',
      theme: 'high-contrast',
    });
  });
});
```

`resources/views/welcome.blade.php` — the `<html>` tag writes the direction from the locale, and the three underlined
text links drop the brand red that fails WCAG AA contrast on white (3.95:1) for the page's own text colour:

```blade
<html lang="{{ str_replace('_', '-', app()->getLocale()) }}" dir="{{ in_array(app()->getLocale(), ['ar', 'he', 'fa', 'ur'], true) ? 'rtl' : 'ltr' }}">
```

```bash
sed -i.bak 's/underline-offset-4 text-\[#f53003\] dark:text-\[#FF4433\]/underline-offset-4 text-[#1b1b18] dark:text-[#FF4433]/' resources/views/welcome.blade.php && rm resources/views/welcome.blade.php.bak
grep -c 'underline-offset-4 text-\[#1b1b18\]' resources/views/welcome.blade.php    # expect 3
```

`text-[#1b1b18]` already exists in the page's inline fallback stylesheet (used before `npm run build`), so the fix
works with and without built assets. A new screen's spec follows the same shape: behaviour once, then a loop over
`uiQuality().directions` × `uiQuality().themes` per visual state (`default`, `empty`, `error`, `long-content`, …)
with `expectDirection()` + `captureUiState()` + `checkAccessibility()`.
