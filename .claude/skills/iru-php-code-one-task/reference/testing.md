# Tests

Every task's implementation comes with the tests that prove it. This skill writes them and runs **only the fast
ones it just wrote or touched** (SKILL.md Step 6) — never coverage, never the full suite, never anything that
needs Docker, a database server, a WordPress test install, or a PrestaShop shop. The calling
`iru-php-code-one-task-group` owns all of that.

## Pest or PHPUnit — follow the project

| Signal | Write tests as |
|---|---|
| `pestphp/pest` in `require-dev` (and a `tests/Pest.php`) | **Pest** — `test()`/`it()` closures with `expect()`. Once Pest is in, `vendor/bin/pest` is the only runner; existing PHPUnit classes still run under it, so match the style of the directory you're adding to. |
| `phpunit/phpunit` only | **PHPUnit** classes extending `PHPUnit\Framework\TestCase` (or the project's own base `TestCase`). |
| WordPress integration suite | `WP_UnitTestCase` subclasses (see `wordpress.md`) — never a Pest closure. |

PHPUnit version decides syntax: **10+ uses attributes** (`#[Test]`, `#[DataProvider('name')]`, `#[CoversClass]`,
`#[TestWith]`, `#[Group]`), and **12 removed docblock annotations** (`@test`, `@dataProvider`) entirely. Check
`composer.lock` (or `vendor/bin/phpunit --version`) before writing metadata; a project still on PHPUnit 9
(common with WordPress's `yoast/phpunit-polyfills` setups) needs annotations instead.

## Where tests go and what kind they are

- **One test class (or one Pest file) per production class**, mirroring its namespace under the test PSR-4 root:
  `src/Billing/FineCalculator.php` → `tests/Unit/Billing/FineCalculatorTest.php`. Never one test class shared by
  several implementations. Pest files keep the `*Test.php` suffix so the configured `<directory suffix="Test.php">`
  picks them up.
- **Unit** (`tests/Unit`): no framework boot, no database, no network, no filesystem outside a temp dir. Laravel's
  `tests/Unit` extends `PHPUnit\Framework\TestCase` — not Laravel's — and has no `app()`.
- **Feature / integration**: see the flavor file. Name the suite the test goes into explicitly; a test placed in
  the wrong suite either never runs in CI or drags a database into the fast stage.
- Respect the project's split. A typical `phpunit.xml(.dist)`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<phpunit xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:noNamespaceSchemaLocation="vendor/phpunit/phpunit/phpunit.xsd"
         bootstrap="vendor/autoload.php"
         colors="true"
         failOnRisky="true"
         failOnWarning="true">
    <testsuites>
        <testsuite name="Unit">
            <directory suffix="Test.php">tests/Unit</directory>
        </testsuite>
        <testsuite name="Integration">
            <directory suffix="Test.php">tests/Integration</directory>
        </testsuite>
    </testsuites>
    <source>
        <include>
            <directory>src</directory>
        </include>
    </source>
</phpunit>
```

`--testsuite Unit` then selects the fast tests only. Don't edit `phpunit.xml` unless the task asks for it.

## What to cover

- **The behavior the task added or changed**, through the public API — the happy path plus meaningful variants.
- **Every `@throws`** (and every `WP_Error` a WordPress function can return): one test per documented failure.
- **Boundaries**: empty/one-element arrays, zero, negative, max, `null` where meaningful, empty string.
- **For anything handling input or rendering output** (see `security.md`): a test that hostile input is
  rejected or escaped, and that the unauthorized path is refused (403/`false`/no side effect).
- **Not trivia** — a getter, a pass-through, an enum with no methods. Coverage is an 80% gate at group level,
  not a goal; meaningless tests cost maintenance and prove nothing.

## Writing the test

- **One behavior per test**, named for the behavior (`rejectsNegativeDays`, `it('rejects negative days')`) in the
  project's existing naming style.
- **Arrange, act, assert**, visibly separated; fresh fixtures in `setUp()`/`beforeEach()`, never shared mutable
  state or order dependence.
- **`assertSame()` over `assertEquals()`** (strict `===`); `toBe()` over `toEqual()` in Pest. Use `assertEquals`
  only for value-object/array comparison where identity doesn't matter, `assertEqualsWithDelta` for floats.
- **Exceptions**: `$this->expectException(Foo::class)` + `expectExceptionMessage()` *before* the call;
  Pest `->throws(Foo::class, 'message')`.
- **Data providers** for tables of inputs: `public static`, returning `iterable<string, array{…}>` with
  descriptive keys (PHPUnit 10+ rejects non-static providers); Pest `->with([...])` datasets.

```php
<?php

declare(strict_types=1);

namespace Tests\Unit;

use Library\FineCalculator;
use PHPUnit\Framework\Attributes\CoversClass;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\Attributes\Test;
use PHPUnit\Framework\TestCase;

#[CoversClass(FineCalculator::class)]
final class FineCalculatorTest extends TestCase
{
    private FineCalculator $calculator;

    protected function setUp(): void
    {
        $this->calculator = new FineCalculator(centsPerDay: 25, capCents: 1000);
    }

    /** @return iterable<string, array{int, int}> */
    public static function lateReturns(): iterable
    {
        yield 'one day' => [1, 25];
        yield 'capped' => [100, 1000];
    }

    #[Test]
    #[DataProvider('lateReturns')]
    public function finesGrowPerDayUpToTheCap(int $days, int $expected): void
    {
        self::assertSame($expected, $this->calculator->fineFor($days));
    }

    #[Test]
    public function rejectsNegativeDays(): void
    {
        $this->expectException(\InvalidArgumentException::class);

        $this->calculator->fineFor(-1);
    }
}
```

## Test doubles: fakes and stubs over mocks

- **Prefer a small hand-written fake** (an in-memory repository, a recording mailer) or a **stub**
  (`createStub()`, canned returns) over a strict mock that verifies every call — strict mocks break on every
  refactor and test the implementation rather than the behavior.
- **Mock only interfaces you own.** Wrap a third-party client behind your own interface and fake that.
- Use `createMock()` + `expects()` only when the *call itself* is the behavior (a message was sent once).
- **Framework fakes first** where they exist: Laravel `Queue::fake()`, `Http::fake()` … (`laravel.md`); Brain
  Monkey/WP_Mock for WordPress functions (`wordpress.md`).
- `final` classes can't be doubled by PHPUnit or Mockery — that's a signal to depend on an interface, not a
  reason to drop `final`.
- Freeze time and randomness through injected clocks/randomizers (or Laravel's `freezeTime()`/`travel()`), never
  `sleep()`.

## Browser UI tests

A task that adds or changes UI also gets browser UI tests — Playwright + `@axe-core/playwright` specs in
`tests/e2e/specs/` through the shared `tests/e2e/support/ui-screenshots.ts` helper (WordPress: `wp-scripts
test-playwright` with `@wordpress/e2e-test-utils-playwright`), covering every visual state, the contract's data
sets, the left-to-right/right-to-left × light/dark screenshot matrix and the accessibility check — per
`ui-contract.md` §6–§8 and `ui-implementation.md`. Behaviour of a Blade/Livewire component can additionally be
tested fast in PHP (`$this->blade()`, `Livewire::test()`), but those produce no screenshots. **This skill writes
the browser specs and never runs them** — the group runs them through `iru-php-test`'s `ui` selector behind the
integration lock.

## Pest architecture rules

If the project has `arch()` tests (usually `tests/ArchTest.php` or `tests/Unit/ArchTest.php`), new code must pass
them — read them before writing. Don't add new arch rules unless the task asks. Typical rules:

```php
<?php

arch('source files use strict types')->expect('App')->toUseStrictTypes();
arch('no debugging helpers are left behind')->expect(['dd', 'dump', 'var_dump', 'ray'])->not->toBeUsed();
arch('value objects are final and readonly')->expect('App\ValueObjects')->classes()->toBeFinal()->toBeReadonly();
arch()->preset()->php();
arch()->preset()->security();
```

(`arch()->preset()->laravel()` and `->strict()` also exist; `strict()` requires `final` classes, strict types,
strict equality, and no `protected` methods — a project that enables it has decided rule-of-thumb defaults for you.)
`not->toBeUsed()` only detects functions that exist in the project: `dd`/`dump` are caught in a Laravel app (or with
`symfony/var-dumper` installed) but silently pass in a plain library without them — `var_dump`/`print_r` are always
caught, and `preset()->php()` covers them too.
