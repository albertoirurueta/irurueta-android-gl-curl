---
name: iru-php-test
description: "/iru-php-test [selector] runs a PHP project's tests via Pest, PHPUnit or artisan test (host php, Sail, ddev or wp-env); selector is a file, directory, --filter, --testsuite or --group, or `ui [<specs>]` for Playwright/Dusk browser UI tests. Missing environments are reported plainly, UI runs without a browser are unverified. Report-only."
model: haiku
allowed-tools: Read Bash(php *) Bash(vendor/bin/*) Bash(./vendor/bin/*) Bash(composer *) Bash(./vendor/bin/sail *) Bash(npx wp-env *) Bash(ddev *) Bash(docker info *) Bash(docker ps *) Bash(command -v *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *) Bash(test *) Bash(mkdir -p build*) Bash(rm -f build/iru-php-test-*) Bash(npx playwright *) Bash(npm run test:e2e*) Bash(npx wp-scripts test-playwright*) Bash(UI_SCREENSHOTS_DIR=*) Bash(node -e *) Bash(curl -sf*) Bash(date *) Bash(mkdir -p .ui-screenshots*) Bash(rm -rf .ui-screenshots) Bash(cp *) Bash(mkdir .integration-test.lock*) Bash(rmdir .integration-test.lock*) Bash(rm -f .integration-test.lock/owner) Write
---

# PHP Test

Run the project's PHP tests through whichever runner and execution environment it actually has configured, scoping
the run to whatever the user asked for, and report a compact pass/fail summary parsed from the runner's own JUnit
XML log. This skill only runs tests and reports results — it does not fix failures, modify source/test code,
install dependencies, or provision databases unless asked to as a follow-up.

## Step 1 — Detect where PHP runs

Never assume host `php` is the right interpreter — many PHP projects only run inside a container. Check, in order:

| Signal | Environment | Command prefix |
|---|---|---|
| `.ddev/config.yaml` exists | **ddev** | `ddev exec <cmd>` (e.g. `ddev exec vendor/bin/phpunit`) |
| `vendor/bin/sail` exists **and** `compose.yaml`/`docker-compose.yml` references `laravel/sail` (or `sail-8.x` runtimes) | **Laravel Sail** | `./vendor/bin/sail <cmd>` (`sail test`, `sail php`, `sail bin pest`) |
| `.wp-env.json`/`.wp-env.test.json` exists, or `@wordpress/env` is in `package.json` devDependencies, **and** the selected suite is a WordPress integration suite (Step 3) | **wp-env** | the project's own script (`npm run test:integration` / `composer test:integration`), else `npx wp-env run cli --config=.wp-env.test.json --env-cwd=<path> <cmd>` (Step 3) |
| `command -v php` succeeds | **host PHP** | none |

Rules:

- Prefer the container environment when it exists **and** either `command -v php` fails or the test database the
  suite needs (Step 3) only resolves inside the container (e.g. `DB_HOST=mysql` in `.env`/`phpunit.xml`). Prefer
  host PHP when the suite is self-contained (in-memory SQLite, pure unit tests) and host `php` satisfies the
  `require.php` constraint in `composer.json` (`php -r 'echo PHP_VERSION;'`).
- Every container environment needs Docker. Before running anything through Sail, ddev, or wp-env, run
  `docker info >/dev/null 2>&1`; if it fails, **stop and report "Docker is not running — <environment> cannot
  start"** instead of letting the runner fail with a socket/connection error. For Sail, also check the containers
  are up (`./vendor/bin/sail ps`); if they aren't, report "Sail containers are not running — start them with
  `./vendor/bin/sail up -d`" and ask before starting them yourself.
- If no environment is usable (no host `php`, no container tooling), stop and report that — do not report "0 tests".

## Step 2 — Detect the runner and confirm it is actually wired

Read `composer.json` (`require-dev`, `scripts`) and look at `vendor/bin/` first:

- `vendor/` missing entirely → **stop and report "dependencies not installed — run `composer install`"** (ask
  before running it yourself). Do not report "no tests".
- **Laravel** (`artisan` at the root and `laravel/framework` in `require`) → `php artisan test` (Sail: `./vendor/bin/sail
  test`; ddev: `ddev exec php artisan test`). It wraps Pest or PHPUnit, whichever the app uses, and forwards
  unknown options (`--filter`, `--testsuite`, `--group`, `--log-junit`, a path) to it.
- **Pest** (`pestphp/pest` in `require-dev` and `vendor/bin/pest` exists) → `vendor/bin/pest`. Once a project uses
  Pest, Pest is its only runner — don't call `vendor/bin/phpunit` on a Pest suite (Pest's `test()`/`it()` files
  aren't PHPUnit classes).
- **PHPUnit** (`phpunit/phpunit` in `require-dev` and `vendor/bin/phpunit` exists) → `vendor/bin/phpunit`.
- **composer `test` script** — read it (`composer run-script --list`, or `scripts.test` in `composer.json`) even when
  you call a binary directly: it reveals the project's real invocation (a non-default `-c phpunit-unit.xml`, extra
  env vars, a `@php` prefix, `--testsuite` defaults, or wp-env wrappers). Mirror its flags in the direct binary
  call rather than running `composer test -- <flags>`, which composer forwards only to the last command of a
  multi-command script.
- None of the above → **stop and report "no test runner wired"** (name what's missing). Never report "0 tests",
  which looks identical to a scoped run that legitimately matched nothing.

**PHPUnit configuration file(s)**: the default lookup is `phpunit.xml`, then `phpunit.xml.dist`, then
`phpunit.dist.xml` in the working directory. Also `find . -maxdepth 3 -name 'phpunit*.xml*' -not -path './vendor/*'`
— WordPress and PrestaShop projects frequently keep separate configs (`phpunit-unit.xml`, `phpunit-integration.xml`,
`tests/phpunit.xml`, `tests/Unit/phpunit.xml`, `tests/php/phpunit.xml`) that need `-c <file>` (`--configuration`).
List the suites each config defines with `grep -o '<testsuite name="[^"]*"' <config>`, or let the runner do it:
`<runner> [-c <config>] --list-suites` and `--list-groups` (PHPUnit and Pest both support these).

## Step 3 — Classify unit vs. integration and check the environment the selection needs

Decide what the requested selection needs before running it:

| Project kind | Unit tests (no external services) | Integration tests (need a real environment) |
|---|---|---|
| Plain library / Composer package | everything, usually | rare — a `Integration` suite or `@group integration` |
| Laravel | `tests/Unit`, and `tests/Feature` with the skeleton's in-memory SQLite (`DB_CONNECTION=sqlite`, `DB_DATABASE=:memory:` in `phpunit.xml`) | Feature tests when `phpunit.xml`/`.env.testing` points at MySQL/PostgreSQL/Redis — that service must be reachable (Sail containers up) |
| WordPress plugin/theme | tests using **Brain Monkey** (`brain/monkey`) or **WP_Mock** (`10up/wp_mock`) that never load WordPress — usually their own suite/config with a bootstrap that only requires `vendor/autoload.php` | tests extending `WP_UnitTestCase` (bootstrap references `WP_TESTS_DIR`, `wordpress-tests-lib`, or `includes/functions.php`) — need the WordPress PHPUnit test library, a **dedicated** MySQL database, and `yoast/phpunit-polyfills` |
| PrestaShop module | `tests/Unit` (pure PHPUnit `TestCase`, dependencies mocked) | tests whose bootstrap requires the shop (`config/config.inc.php`, `_PS_ROOT_DIR_`, `_PS_VERSION_`) — need a PrestaShop installation and its database |

Identify a suite as unit vs. integration by its name (`Unit`, `Integration`, `Feature`, `wp`, …), its directory,
its config's `bootstrap=` file, and the base class its tests extend (`grep -rl 'extends WP_UnitTestCase' tests`).
When the user asked for "unit tests" only, select the unit suite/config/group explicitly (Step 4) rather than
running everything and hitting an unprovisioned integration suite.

Preflight checks for an integration selection — when one fails, **stop and report the missing piece in plain
words** (and the command that would provision it), never a raw fatal error:

- **WordPress via `bin/install-wp-tests.sh`** (generated by `wp scaffold plugin-tests`/`wp scaffold theme-tests`):
  the test library lives in `$WP_TESTS_DIR`, defaulting to `<sys_get_temp_dir()>/wordpress-tests-lib` (normally
  `/tmp/wordpress-tests-lib`). If `$WP_TESTS_DIR/includes/functions.php` doesn't exist, report "WordPress test
  library not installed — run `bin/install-wp-tests.sh <db-name> <db-user> <db-pass> [db-host] [wp-version]`
  (needs a running MySQL; it drops and recreates that database)". Do **not** run the script yourself — it
  deletes the named database's data; ask first. If `vendor/yoast/phpunit-polyfills` is missing (and no
  `WP_TESTS_PHPUNIT_POLYFILLS_PATH` is set), report that too — the WordPress bootstrap aborts without it.
- **WordPress via wp-env**: Docker must be running (Step 1). wp-env ships PHPUnit, Composer and the matching test
  library inside its containers, with `WP_TESTS_DIR` already set. Pick the invocation in this order:
  1. **The project's own scripts** — `npm run test:integration` / `composer test:integration` (read them in
     `package.json`/`composer.json` first and mirror their `--config`, container and `--env-cwd`; append extra
     PHPUnit args after `--`).
  2. **A dedicated test config** (`.wp-env.test.json`, wp-env 11's documented replacement for the deprecated
     built-in tests environment): `npx wp-env run cli --config=.wp-env.test.json
     --env-cwd=wp-content/<plugins|themes>/<dir> vendor/bin/phpunit [-c phpunit-integration.xml.dist] [args]`.
  3. **Only for older projects** whose `.wp-env.json` still relies on the deprecated `testsEnvironment` (no
     `"testsEnvironment": false`, no test config): `npx wp-env run tests-cli --env-cwd=wp-content/plugins/<dir>
     vendor/bin/phpunit [args]`.

  **Never run the integration suite in the development environment's `cli`** (`.wp-env.json` without
  `--config` when `testsEnvironment` is `false`): its `wp-tests-config.php` uses the dev site's database and table
  prefix, and the test installer drops those tables. If the environment isn't started (`npx wp-env run` reports it
  can't find the containers), report "wp-env test environment is not running — start it with `npm run
  test:env:start` (or `npx wp-env start --config=.wp-env.test.json`)" and **ask before starting it** — never start
  the development environment for integration tests.
- **Database reachability** (WordPress, Laravel against MySQL/PostgreSQL, PrestaShop): the typical failures are
  WordPress's `Error establishing a database connection`, PDO's `SQLSTATE[HY000] [2002] Connection refused` /
  `php_network_getaddresses: getaddrinfo ... failed` (host name like `mysql` that only resolves inside Docker), and
  `SQLSTATE[HY000] [1045] Access denied`. Map any of them to "test database unreachable at `<host>` — <reason>".
- **PrestaShop**: if the integration bootstrap requires a shop path (`_PS_ROOT_DIR_`, an env var such as
  `PS_ROOT_DIR`, or a relative `../../config/config.inc.php`) that doesn't exist, report "PrestaShop installation
  not found at `<path>`" — module integration tests only run with the module placed inside a shop (commonly via
  the project's own Docker setup, e.g. a `prestashop/prestashop` image).

## Step 4 — Determine scope from the argument

All three runners share PHPUnit's CLI for these selectors (`php artisan test` forwards them; Pest accepts them):

| What the user wants | PHPUnit | Pest | `php artisan test` |
|---|---|---|---|
| Everything | `vendor/bin/phpunit` | `vendor/bin/pest` | `php artisan test` |
| One file | `vendor/bin/phpunit tests/Unit/FooTest.php` | `vendor/bin/pest tests/Unit/FooTest.php` | `php artisan test tests/Unit/FooTest.php` |
| A directory | `vendor/bin/phpunit tests/Unit/Billing` | `vendor/bin/pest tests/Unit/Billing` | `php artisan test tests/Unit/Billing` |
| Name pattern | `--filter '<pattern>'` (regex over `Class::method` and data-set names, e.g. `FooTest`, `FooTest::testBar`, `'/::testBar( with data set .*)?$/'`) | `--filter '<pattern>'` (matches the `test()`/`it()` description, e.g. `--filter 'rejects negative'`) | `--filter '<pattern>'` |
| A testsuite | `--testsuite Unit` (comma-separate several) | `--testsuite Unit` | `--testsuite Unit` |
| A group | `--group slow` / `--exclude-group slow` (`#[Group('slow')]` or `@group slow`) | `--group=slow` / `--exclude-group=slow` (`->group('slow')`) | `--group slow` |
| A non-default config | `-c phpunit-unit.xml` | `-c phpunit-unit.xml` | `-c phpunit-unit.xml` *(forwarded)* |

Notes:

- Selectors combine: `vendor/bin/phpunit -c phpunit-integration.xml --group ajax --filter Checkout`.
- WordPress core-style suites exclude some groups by default in their config (e.g. `ajax`, `ms-files`,
  `ms-required`); `--group ajax` runs them, and multisite tests usually need their own config
  (`-c tests/phpunit/multisite.xml` or a `WP_MULTISITE=1` env var) — check the project's config before assuming.
- **An unmatched `--filter`/`--group`/`--testsuite` is not reliably a failure.** PHPUnit and Pest print
  `No tests executed!` and the exit code depends on version and on `failOnEmptyTestSuite`; a non-existent file path
  is reported as an error (`Test file "<path>" not found` / `Cannot open file`). Always read the counts from the
  JUnit log (Step 5): 0 tests run is "selector `<x>` matched nothing", never "all passed".
- Exit code **255** means a PHP fatal error (usually during bootstrap: missing test library, missing extension,
  autoload failure) — report the first `PHP Fatal error`/`Error:` line, not a test count.
- **`ui`** as the first word selects the browser UI tests instead of PHPUnit/Pest — Step 7; any following paths
  narrow it to those specs (a *scoped* UI run).
- Don't add `--parallel` (artisan/Pest/ParaTest) unless the user asks — parallel runs need per-process databases
  and obscure failure output.

## Step 5 — Run with a JUnit log and parse it

Keep the runner's normal console output for a human glancing at the log, and add `--log-junit` (supported by
PHPUnit, Pest, and forwarded by `php artisan test`) so this skill parses structured results instead of
screen-scraping. Write it **inside the project directory** so the same relative path works on the host and inside
Sail/ddev/wp-env containers (which mount the project, not your `$TMPDIR`):

```bash
mkdir -p build
<prefix> <runner> [scope] --log-junit build/iru-php-test-junit.xml
```

PHPUnit's JUnit XML shape (also what Pest writes):

```xml
<testsuites>
  <testsuite name="Unit" tests="7" assertions="9" errors="0" failures="1" skipped="0" time="0.02">
    <testsuite name="Tests\Unit\FineCalculatorTest" file="tests/Unit/FineCalculatorTest.php" ...>
      <testcase name="testRejectsNegativeDays" class="Tests\Unit\FineCalculatorTest" file="..." line="42" ...>
        <failure type="PHPUnit\Framework\ExpectationFailedException">Tests\Unit\FineCalculatorTest::testRejectsNegativeDays
Failed asserting that exception of type "InvalidArgumentException" is thrown.

tests/Unit/FineCalculatorTest.php:42</failure>
      </testcase>
```

Parse the outermost `<testsuite>` for the totals and every `<testcase>` with a `<failure>` or `<error>` child for the
failures — with host PHP (or `./vendor/bin/sail php` / `ddev exec php` when PHP only exists in the container):

```bash
php -r '$x = simplexml_load_file($argv[1]); $s = $x->testsuite[0];
  printf("tests=%s assertions=%s failures=%s errors=%s skipped=%s\n", $s["tests"], $s["assertions"], $s["failures"], $s["errors"], $s["skipped"]);
  foreach ($x->xpath("//testcase[failure or error]") as $t) {
    $f = $t->failure ?: $t->error;
    $l = array_values(array_filter(array_map("trim", explode("\n", (string) $f))));
    $n = (string) $t["name"]; $h = array_shift($l) ?? "";
    $h = str_starts_with($h, $n) ? substr($h, strlen($n)) : (str_ends_with($h, "::" . $n) ? "" : $h);
    printf("%s::%s — %s\n", $t["class"] ?: $t["file"], $n, $h !== "" ? $h : ($l[0] ?? ""));
  }' build/iru-php-test-junit.xml
```

The failure body opens with the test's own name — on a line of its own for PHPUnit (`Class::method`), glued to the
start of the message for Pest (`it failsFailed asserting…`) — so the parser strips that prefix and reports the
assertion message that follows; report only that line — never the full stack/diff. If no JUnit file was written at all, the run died before
executing tests (fatal error, missing environment) — report that cause from the console output instead.

Delete the log afterwards (`rm -f build/iru-php-test-junit.xml`; remove `build/` only if this run created it) so it
doesn't get committed as build noise.

## Step 6 — Report results

- State the environment and runner used (e.g. "Pest via Sail", "PHPUnit via wp-env `cli --config=.wp-env.test.json`,
  config `phpunit-integration.xml.dist`") and the counts — don't paste the JUnit XML or the full console log.
- If the selector matched nothing (0 tests in the log, or `No tests executed!`), say so plainly — not as 0 passed.
- If the environment was missing (Docker down, wp-env/Sail not started, WordPress test library absent, database
  unreachable, `vendor/` missing, no shop for PrestaShop integration tests), report exactly what is missing and the
  command that provisions it, and say which tests *could* still run (e.g. "the Brain Monkey `Unit` suite doesn't
  need it: `/iru-php-test --testsuite Unit`").
- On failures, list each failing test (`Class::method`, or the Pest description) with the first line of its
  assertion message — nothing more per failure, and no noise about passing tests. Count PHP errors (`<error>`)
  separately from assertion failures.
- Do not attempt to fix failing tests, modify source/test code, install dependencies, or provision databases unless
  the user asks for that as a next step.

## Step 7 — The `ui` selector: browser UI tests

Browser UI tests render the real pages, so they need a browser plus a running server (Laravel `php artisan serve`
started by Playwright's `webServer`), the wp-env **tests** instance (WordPress, `WP_BASE_URL`, default
`http://localhost:8889`), or a running shop (`PS_BASE_URL`). Steps 1–6 don't apply; do this instead:

1. **Detect the runner.** A root `playwright.config.{ts,js,mjs}` → Playwright: the project's `test:e2e` script if
   `package.json` has one (WordPress: `wp-scripts test-playwright`), else `npx playwright test`. `laravel/dusk`
   in `require-dev` with `tests/Browser/` → `php artisan dusk`; the Pest browser plugin
   (`pestphp/pest-plugin-browser`) → `vendor/bin/pest tests/Browser` — only where the project already uses them.
   None → stop and report **"no UI test setup"** (never "0 tests"). `node_modules/` missing → "dependencies not
   installed — run `npm ci`" (ask first).
2. **Preflight — unverified, not failed, when something is missing**: a Chromium for this Playwright version
   (`npx playwright install --list` shows it; otherwise report "no browser — run `npx playwright install
   chromium`" and ask before installing); WordPress — Docker (`docker info`) and the tests instance answering
   (`npx wp-env run cli --config=.wp-env.test.json wp core version`, or `curl -sfI "$WP_BASE_URL"`) — never start
   the development environment; PrestaShop — `PS_BASE_URL` set and reachable (`curl -sfI "$PS_BASE_URL"`),
   otherwise **unverified (no shop)**; Laravel — `vendor/` present (the `webServer` starts `php artisan serve`).
   Report each as **unverified** with the reason and the command that provisions it.
3. **Lock.** Unless the arguments say `lock: held`, acquire the repository-wide lock first:
   `mkdir .integration-test.lock 2>/dev/null && echo ACQUIRED || echo HELD`. On `ACQUIRED`, write
   `.integration-test.lock/owner` (via `Write`: "iru-php-test ui", the selection, `date -u
   +'%Y-%m-%dT%H:%M:%SZ'`); on `HELD`, report who holds it (`cat .integration-test.lock/owner`) and retry roughly
   every 30 seconds — a lock older than 60 minutes (`find .integration-test.lock -maxdepth 0 -mmin +60`) is stale:
   say so, `rm -f .integration-test.lock/owner && rmdir .integration-test.lock`, and acquire; still held after ~45
   minutes → **unverified (lock held by another run)**. Release with `rm -f .integration-test.lock/owner && rmdir
   .integration-test.lock` on **every** exit path once the run below finishes.
4. **Screenshots directory.** `UI_SCREENSHOTS_DIR` = the caller's value, else `<repository root>/.ui-screenshots`
   (absolute). On a **full** UI run (no spec paths) clear it first (`rm -rf .ui-screenshots` for the default) so it
   holds exactly this run; on a scoped run leave it as is. Read `.ui-quality.yml` at the repository root
   (`accessibility`, `directions`, `themes`; missing file → `enforce`, `[ltr, rtl]`, `[light, dark]`) and record
   the level. Under `none` (opted out entirely) or `off` (rules apply, checks not run) the accessibility check is not applicable: report "accessibility: opted out" (`none`) or "accessibility checks off" (`off`) — never "unverified" and never a failure.
5. **Run** with a JSON report next to the console output (inside the project, like Step 5's JUnit log):
   ```bash
   mkdir -p build
   UI_SCREENSHOTS_DIR="$PWD/.ui-screenshots" PLAYWRIGHT_JSON_OUTPUT_FILE=build/iru-php-test-ui.json \
     npx playwright test [spec paths] --reporter=line,json
   # npm script / WordPress: UI_SCREENSHOTS_DIR=… PLAYWRIGHT_JSON_OUTPUT_FILE=… npm run test:e2e -- [spec paths] --reporter=line,json
   ```
   WordPress: add `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` when Chromium is already installed (`wp-scripts
   test-playwright` otherwise runs `playwright install` for every browser first). Dusk/Pest browser: run them the
   same way with `--log-junit` and parse Step 5's JUnit log.
6. **Parse** counts, failures and the axe results the shared helper attaches (`accessibility-scan-results`):
   ```bash
   node -e 'const r = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")), s = r.stats, rules = {}, fails = [];
     const walk = (su) => { for (const sp of su.specs ?? []) for (const t of sp.tests) for (const res of t.results) {
       if (t.status === "unexpected" && res.status !== "passed") fails.push(`${sp.file} › ${sp.title} — ${(res.error?.message ?? "").replace(/\x1b\[[0-9;]*m/g, "").split("\n")[0]}`);
       for (const a of res.attachments ?? []) if (a.name === "accessibility-scan-results" && a.body)
         for (const v of JSON.parse(Buffer.from(a.body, "base64").toString()).violations) rules[v.id] = (rules[v.id] ?? 0) + v.nodes.length; }
       (su.suites ?? []).forEach(walk); };
     r.suites.forEach(walk);
     console.log(`passed=${s.expected} failed=${s.unexpected} flaky=${s.flaky} skipped=${s.skipped}`);
     console.log(`a11y: ${Object.entries(rules).map(([k, n]) => `${k} × ${n}`).join(", ") || "none"}`); fails.forEach((f) => console.log(`FAIL ${f}`));' build/iru-php-test-ui.json
   ```
   A failure whose first line is a connection error (`net::ERR_CONNECTION_REFUSED`, `webServer` timeout,
   `Executable doesn't exist`) is an environment problem — **unverified**, not a failing test. Skipped specs with
   reason "no shop" mean **unverified (no shop)**. Delete the JSON afterwards (`rm -f build/iru-php-test-ui.json`).
7. **Collect, then validate the screenshots.** Captures a tool left elsewhere — Dusk's `tests/Browser/screenshots/`,
   the Pest browser plugin's screenshot directory — are copied (`cp`) into `$UI_SCREENSHOTS_DIR` when their relative
   path already follows `<kind>/<Id>/<file>.png`; Playwright's own failure screenshots under `test-results/`/
   `artifacts/` are not captures and are ignored. Then count per kind and list malformed names:
   ```bash
   find .ui-screenshots -type f -name '*.png' | sed 's#^\.ui-screenshots/##' | cut -d/ -f1 | sort | uniq -c
   find .ui-screenshots -type f | sed 's#^\.ui-screenshots/##' | grep -Ev \
     '^((component|screen)/[A-Za-z][A-Za-z0-9]*|flow/[a-z0-9]+(-[a-z0-9]+)*)/[a-z0-9]+(-[a-z0-9]+)*--(ltr|rtl)--(light|dark|high-contrast)(--[a-z0-9]+(-[a-z0-9]+)*)*\.png$'
   ```
8. **Report**: runner and target ("Playwright via `npm run test:e2e` against `php artisan serve`", "wp-scripts
   test-playwright against the wp-env tests instance"), full or scoped, passed/failed/flaky/skipped counts and each
   failure's first line, screenshot count per kind (`screen 8, component 24, flow 4`), malformed names (or
   "none"), accessibility violations as **count + rule ids only** (`color-contrast × 3, button-name × 1`) with the
   `.ui-quality.yml` level applied (under `enforce` they are also the failing specs; under `report` they never
   fail; `off` → "accessibility checks off"), and how the lock behaved. Never paste the JSON report or axe output.

## Verification notes

Verified in Docker (`php:8.4-cli` + PCOV, `composer:2`) against PHPUnit 12 and Pest 4 suites with one passing and
one failing test: the JUnit parser above reports the correct totals and the assertion message for both runners;
Pest's `<testcase>` carries `class`, `classname`, and `file` attributes. With `--filter` matching nothing, Pest exited
1 and PHPUnit 12 exited 255 — exit codes for an unmatched selector are therefore not a reliable signal, so treat a
JUnit file with `tests="0"` (or "No tests executed" on the console) as "nothing matched", never as a pass or a crash.
The wp-env path was verified with `iru-setup-php-wordpress`'s scaffold (wp-env 11.17.0, WordPress 7.1.3, PHPUnit
9.6): `npx wp-env start --config=.wp-env.test.json` then `npx wp-env run cli --config=.wp-env.test.json
--env-cwd=wp-content/plugins/<slug> vendor/bin/phpunit -c phpunit-integration.xml.dist` ran a real `WP_UnitTestCase`
suite (plugin and block theme); wp-env 11's `run` accepts `mysql`/`wordpress`/`cli`/`composer`/`phpmyadmin`, and
`tests-cli` exists only while the deprecated `testsEnvironment` is on (it still defaults to `true`). Still
unverified: `php artisan test` and Sail, ddev, and a PrestaShop integration suite.

Step 7 was exercised with `@playwright/test` 1.64.1 and `@axe-core/playwright` 4.14 (Chromium) against a small
server that renders `dir` from a cookie/`Accept-Language`, using the shared `tests/e2e/support/ui-screenshots.ts`
helper from `iru-php-code-one-task`'s `ui-implementation.md`: `PLAYWRIGHT_JSON_OUTPUT_FILE` + `--reporter=line,json`
writes the report, the parser above prints the counts, failing specs and axe rule ids from the attached results
(`enforce` failed the spec, `report` only annotated it), and `UI_SCREENSHOTS_DIR` receives the
`screen/<Id>/<state>--<dir>--<theme>.png` files. Still unverified: `wp-scripts test-playwright` against a live
wp-env tests instance, a PrestaShop shop, `php artisan serve` as the `webServer`, and Dusk/Pest browser capture
collection.
