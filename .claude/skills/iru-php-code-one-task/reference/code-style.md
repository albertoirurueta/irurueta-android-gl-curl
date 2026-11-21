# PHP code style

The defaults for new PHP code in any flavor this skill supports. The flavor file (`laravel.md`, `wordpress.md`,
`prestashop.md`) overrides a rule here where its ecosystem's own standard says otherwise — WordPress Coding
Standards in particular differ on layout and naming — and the surrounding code overrides both (README rule 1).

## Which style standard applies

Look for the project's formatter config before writing a line, and write code it would leave untouched:

| Config present | Standard in force | Notes |
|---|---|---|
| `pint.json`, or `laravel/pint` in `require-dev` | Pint, `laravel` preset unless `pint.json` says `per`/`psr12` | Laravel skeleton default. |
| `.php-cs-fixer.dist.php` / `.php-cs-fixer.php` | Whatever rule sets it lists (`@PER-CS`, `@Symfony`, PrestaShop's `PrestaShop\CodingStandards\CsFixer\Config`) | Read the rule list; `declare_strict_types`, `final_class`, `ordered_imports` change what you write. |
| `phpcs.xml(.dist)` / `.phpcs.xml(.dist)` | The standards it references (`WordPress*`, `PSR12`, a PER ruleset, Slevomat) | WordPress projects: see `wordpress.md` — tabs, spaces inside parentheses, `snake_case`. |
| none | **PER Coding Style 3.0** | Supersedes PSR-12; covers attributes, enums, `match`, readonly, property hooks. |

PER-CS essentials when nothing more specific exists: 4-space indent, LF line endings, one blank line after the
`namespace` and after the `use` block, opening brace of classes/methods on its own line, of control structures on
the same line, visibility on every property/constant/method, one `use` per imported symbol (grouped by class,
function, const), trailing commas in multi-line argument/parameter/array lists, `elseif` not `else if`.

## File shape

- **`<?php` and no closing `?>`** in any file that only contains PHP — a trailing newline after `?>` becomes output
  (and breaks headers). Templates that mix HTML and PHP are the exception.
- **`declare(strict_types=1);`** directly after `<?php` (after the license header block if the project puts one
  there) — **when the project uses it**. Check two or three sibling files: plain libraries and PrestaShop (its
  coding standard requires it in every new non-interface class) use it; the Laravel skeleton's own files don't,
  and many WordPress codebases don't (core's callbacks pass loosely typed values — e.g. `get_option()` returns
  `false` or a string — so strict mode turns sloppy-but-working calls into `TypeError`s). Follow the directory
  you're in; a Pest arch rule `toUseStrictTypes()` or a fixer's `declare_strict_types` rule settles it.
- **One class/interface/trait/enum per file**, file named after it, path matching the PSR-4 map in
  `composer.json` (`autoload.psr-4`; tests under `autoload-dev`). A new top-level namespace needs a new PSR-4
  entry — that is a `composer.json` change, so only when the task asks for it.
- **No side effects in a file that declares symbols** (PSR-1): a class file declares the class, nothing else
  runs. WordPress main plugin files and PrestaShop main module files are bootstraps by design.

## Types

- **Native types everywhere they can go**: parameters, returns (`: void`, `: never`, `: static`), properties,
  class constants (`const string NAME = …;`, PHP 8.3). Use `?T` or `T|null` explicitly — implicitly nullable
  parameters (`T $x = null`) are deprecated in PHP 8.4.
- **`mixed` and untyped `array` are last resorts.** An `array` parameter or return gets a PHPDoc shape
  (`list<Book>`, `array<string, int>`, `array{id: int, title: string}`); a structure with a name in the domain
  usually wants to be a small `final readonly class` instead.
- **Enums for closed sets** (backed enums when the value is stored or serialized), not class constants or magic
  strings. Add methods to the enum rather than `switch`ing on it elsewhere.
- **`match` over `switch`** for value-producing branches: strict comparison, no fall-through, and an
  `\UnhandledMatchError` instead of a silent default.
- **Comparisons are strict** (`===`, `!==`, `in_array($x, $list, true)`, `array_search(…, true)`). Loose
  comparison is the classic PHP defect; WordPressCS and PHPStan both flag it.
- Respect the minimum PHP version in `composer.json` `require.php` (or the plugin header's `Requires PHP`, or
  `ps_versions_compliancy` + the shop's PHP floor). Don't use property hooks (8.4), asymmetric visibility (8.4),
  `#[\Override]`/typed class constants (8.3) or readonly classes (8.2) below that floor.

## Classes

- **`final` by default** for new concrete classes; open a class for extension only when the design calls for it.
  Exceptions: Laravel models, controllers, jobs, and anything the framework or a test double must extend or proxy
  (Mockery/PHPUnit can't double a `final` class) — match the directory.
- **`readonly` for value objects and DTOs** (`final readonly class Money`), constructor-promoted properties,
  immutable "withers" (`withAmount(): static` returning a clone) instead of setters.
- **Constructor injection**, dependencies typed against interfaces you own where there is more than one
  implementation or a test needs a fake. No `new` of collaborators inside business logic, no static service
  locators, no global state (`global $x` only where WordPress forces it, e.g. `$wpdb`).
- **Small public surface**: `private` by default, `protected` only for a real extension point.
- Mark overrides with `#[\Override]` where PHP ≥ 8.3 and the project already uses it.

## Errors

- **Throw exceptions, don't return `false`/`null` to signal failure** in new code (WordPress returns `WP_Error`
  by convention — see `wordpress.md`). Throw the most specific SPL type (`InvalidArgumentException`,
  `DomainException`, `RuntimeException`, `LogicException`) or a project exception that extends one.
- **Never swallow**: an empty `catch` or `catch (\Throwable)` that only logs and continues hides real failures.
  Catch where you can handle or translate (adapter boundary → domain exception), and chain the cause
  (`new FooException('…', previous: $e)`).
- No `@` error suppression; no `die`/`exit` outside the guards and entry points the platform requires.

## PHPDoc — only for what native types can't say

- **Don't repeat native types.** `/** @param int $id */` on `int $id` is noise and drifts.
- **Do** document: generics and shapes (`list<T>`, `array<K, V>`, `array{…}`, `class-string<T>`,
  `non-empty-string`, `positive-int`, `@template`/`@extends`/`@implements`), `@throws` for every exception a
  caller can reasonably handle, and *why* — intent, invariants, units — in a one-line summary.
- WordPress and PrestaShop require fuller docblocks (summary on everything, `@since` on WordPress, every
  member regardless of visibility on PrestaShop) — see their flavor files. `iru-php-phpdoc` audits this at group
  level; write it now so it has nothing to backfill.
- Deprecate with `#[\Deprecated]` (PHP 8.4) where the floor allows, else `@deprecated` + `trigger_error(…,
  E_USER_DEPRECATED)`.

## Naming and small things

- Classes `StudlyCaps`, methods/properties `camelCase`, constants `UPPER_SNAKE` (PSR-1) — WordPress procedural
  code uses `snake_case` functions/variables (see `wordpress.md`).
- Named arguments for boolean or easily-confused parameters (`new Money(amount: 100, currency: 'EUR')`); first-class
  callable syntax (`$this->handle(...)`) over string callables where the API accepts a `Closure`.
- `//` line comments, not `#`; no commented-out code; no `dd()`, `dump()`, `var_dump()`, `print_r()`, `ray()`,
  `error_log()` debugging left behind.
- Dates through `DateTimeImmutable` (or Carbon in Laravel), with an explicit time zone; time, randomness and I/O
  injected (PSR-20 `ClockInterface`, `Random\Randomizer`) so tests are deterministic.
- No new Composer dependency unless the task asks for it — that's a blocker to report, not a quiet decision.
