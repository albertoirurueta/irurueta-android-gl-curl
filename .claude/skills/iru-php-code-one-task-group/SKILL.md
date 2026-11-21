---
name: iru-php-code-one-task-group
description: "Invoked as /iru-php-code-one-task-group <bucket text> (group tasks, Parallelizable verdict, code state) via Skill. Implements one PHP task bucket: one baseline, iru-php-code-one-task per task (parallel when allowed), then validates once (license, PHPDoc, fast tests, 80% coverage, static analysis, locked integration/UI); finalizes checkboxes."
model: sonnet
allowed-tools: Read Edit Write Bash(php *) Bash(vendor/bin/*) Bash(./vendor/bin/*) Bash(composer *) Bash(npx wp-env *) Bash(ddev *) Bash(docker *) Bash(command -v *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *) Bash(date *) Bash(mkdir .integration-test.lock*) Bash(rmdir .integration-test.lock*) Bash(rm -f .integration-test.lock/owner) Skill Agent
---

# Implement one task-group bucket (PHP)

Carry out every PHP task in one plan-group bucket end to end: one quality baseline up front, implement each task
(in parallel where safe), then validate everything the bucket touched in a single consolidated pass instead of once
per task. It is the PHP counterpart to `iru-typescript-code-one-task-group`, with the integration-stage discipline of
`iru-java-springboot-code-one-task-group`, built on the four PHP gate skills — `iru-php-test`, `iru-php-coverage`,
`iru-php-code-quality`, `iru-php-phpdoc` — each invoked through an `iru-gate-runner` agent so its report never lands
in this context. Prefer those skills over a hand-written runner command wherever they're installed: they already know
the environments (host PHP, Sail, ddev, wp-env), the Pest/PHPUnit/`artisan test` differences, the coverage-driver
pitfalls, and how to read each report. Fall back to an explicit command only when a skill isn't installed in the
consuming repository.

Three things shape the validation order:

1. **Fast vs. integration is decided by what a suite needs, not by its name.** A Laravel `tests/Feature` suite on
   SQLite `:memory:` is fast (milliseconds per test, no service); the same suite pointed at MySQL through Sail is
   integration. Brain Monkey/WP_Mock tests are fast; `WP_UnitTestCase` tests need a WordPress test install and a
   database. Step 1 classifies once, and every later step uses that classification.
2. **The coverage gate is measured from fast tests only.** It's the number the bucket's tasks control, it's
   available whether or not Docker is, and a class whose coverage arrives only through a container-backed suite has
   no fast feedback loop — a finding to surface, not a pass to hide behind.
3. **Containers last, and one at a time.** Everything cheaper — headers, docs, manifest checks, tests, coverage,
   static analysis — has passed before anything starts wp-env or touches a shared database, and only one such run
   may be in flight repository-wide (Step 3.8a).

It uses a medium model on purpose: the plan carries the hard reasoning; this skill drives execution of it.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Read this before running anything container-backed

**Only one integration-test run may be in flight against this repository at any moment.** wp-env runs one
WordPress + MySQL stack per checkout; `bin/install-wp-tests.sh` setups share one test database; Sail runs one
MySQL/PostgreSQL/Redis set; a PrestaShop test shop is one database. Two overlapping runs see each other's rows,
options, and caches — tests fail for reasons nothing in the code explains, or pass because the other run left the
right data behind, and both outcomes look like real results. This holds:

1. **Across buckets/groups** — enforced by the lock in Step 3.8a, regardless of what the caller does.
2. **Within this bucket** — Step 2's per-task agents never run integration tests (`iru-php-code-one-task` forbids
   it, and never runs browser UI tests), so Steps 3.8–3.9 are the only container- or server-backed runs.
3. **Within the run itself** — never `--parallel` (ParaTest, `artisan test --parallel`, Pest `--parallel`) for the
   integration suite.

## Step 1 — Detect the toolchain once, classify suites, capture one baseline

Detect once — and reuse in every gate prompt below rather than letting each gate re-derive it — the facts
`iru-php-test`/`-coverage`/`-code-quality`/`-phpdoc` each detect on their own:

- **Flavor**: `laravel` (`laravel/framework` + `artisan`) / `wordpress-plugin` (root PHP file with `Plugin Name:`) /
  `wordpress-theme` (`style.css` `Theme Name:` and/or `theme.json`) / `prestashop-module` (class `extends Module`,
  `config.xml`, `prestashop/*`) / `library` — first match wins, applied to the directory the bucket's files live in.
- **Environment**: host `php` (version satisfying `require.php`), else Sail / ddev, per `iru-php-test` Step 1.
- **Runner**: Pest / PHPUnit / `php artisan test`; the PHPUnit config file(s) and their `<testsuite>` names
  (`grep -o '<testsuite name="[^"]*"' <config>` for each `phpunit*.xml*` outside `vendor/`); any `composer test`
  script's real flags.
- **Fast vs. integration suites**, recorded as two concrete selectors (`--testsuite Unit,Feature`, `-c
  phpunit-unit.xml`, `--exclude-group integration`, …):

  | Flavor | Fast | Integration |
  |---|---|---|
  | library | everything, unless an `Integration` suite/group needs a service | that suite/group, if it exists |
  | laravel | `tests/Unit`, **plus `tests/Feature` when `phpunit.xml` sets `DB_CONNECTION=sqlite` + `DB_DATABASE=:memory:`** and no `.env.testing` overrides it to a server | `tests/Feature` (or the suites) whose DB/Redis is a real server — needs Sail/containers |
  | wordpress-plugin/theme | Brain Monkey / WP_Mock suites (bootstrap requires only `vendor/autoload.php`) | `WP_UnitTestCase` suites (bootstrap references `WP_TESTS_DIR`/`wordpress-tests-lib`), run via wp-env or `install-wp-tests.sh` |
  | prestashop-module | `tests/Unit` with mocked collaborators | bootstraps requiring `config/config.inc.php`/`_PS_ROOT_DIR_` — need a shop |

  A suite that can't be classified with confidence is treated as integration (it gets the lock) — say so in Step 5.
- **The browser UI stage** (only matters if the bucket touches UI): a root `playwright.config.*` (WordPress: also
  `@wordpress/scripts` + `@wordpress/e2e-test-utils-playwright` in `package.json`), or Dusk (`laravel/dusk`) / the
  Pest browser plugin (`pestphp/pest-plugin-browser`) where the project already uses them; the server it targets
  (`webServer` running `php artisan serve`, the wp-env tests instance, or `PS_BASE_URL` for a shop); and
  `.ui-quality.yml` at the repository root (missing (new UI project) → `accessibility: enforce`, `[ltr, rtl]`, `[light, dark]`).
  No browser-test setup → Step 3.9 reports "no UI test setup" for a UI-touching bucket rather than scaffolding one.
- **Whether the integration stage exists at all and can run**: `docker info` (record availability now), and for
  wp-env whether `.wp-env.json`/`@wordpress/env` is present. No integration suite → Step 3.8 is skipped, and
  Step 5 says so rather than reporting a stage that never existed.

Then collect the files every task in the bucket is expected to touch (from the task descriptions) and capture one
pre-change baseline:

```
Agent({
  description: "Capture pre-change quality baseline for task group",
  subagent_type: "iru-gate-runner",
  prompt: "Invoke Skill({skill: \"iru-php-code-quality\", args: \"<file1,file2,...>\"}). Project flavor:
    <flavor>; environment: <host php|sail|ddev>. Report back only the issues found for these files, itemized per
    tool and rule id (file:line, rule id, message), with Rector findings listed separately.",
  run_in_background: false
})
```

Record the result as this bucket's baseline — empty if every file is new. Step 3.7 diffs against it so only
issues this bucket introduces are flagged, not the repository's backlog (or what the project's own
`phpstan-baseline.neon` already suppresses). Skip if `iru-php-code-quality` isn't installed, and say so in Step 5.

Don't touch the integration lock here — a lock held through Step 2's implementation would block every other group
for the whole bucket. It's acquired in Step 3.8a, immediately before the run that needs it.

**Accessibility opt-out (`accessibility: none`).** When `.ui-quality.yml` says `none`, the user opted out of accessibility support entirely (see `reference/ui-contract.md` §8, "Opting out and existing code"): do not apply the accessibility rules, do not run or require the accessibility checks (axe, ATF/semantics audit, `performAccessibilityAudit()`, Axe.Windows, a11y lint rules, TUI asserted checks), and report them as "accessibility: opted out" in the group report - never "unverified", and never a failure. `off` differs: the rules still apply when writing code, only the automated checks are not run.

**Missing `.ui-quality.yml` in a repository that already has UI code.** The level was never chosen, so don't assume the `enforce` default — that default is only for a new UI project with no UI code yet. Before the first UI task of the bucket, resolve it once per `reference/ui-contract.md` §8 ("Opting out and existing code"): reuse the `Accessibility:` line of an `iru-explore` report in the plan's "Current code state" or this conversation (otherwise apply the detection rules of `iru-explore`'s `reference/accessibility-detection.md`), ask the user via `AskUserQuestion` with the setup's pre-selection (`present` → Enforce, `partial` → Report only with the mixed-compliance warning, `absent` → None with the half-compliance warning), and write `.ui-quality.yml` with the chosen level and the default `directions`/`themes`, so the per-task skills and the test gates read the same value. This is a decision only the user can make, so it is an allowed pause.

## Step 2 — Implement each task via `iru-php-code-one-task`

For every task in the bucket, invoke `iru-php-code-one-task` — it implements exactly what the task specifies,
writes its tests, runs only the fast tests it touched, and checks off its own checkbox.

- **Bucket marked `Parallelizable: yes`**: issue all the `Agent` calls together, in the same response:
  ```
  Agent({
    description: "Implement <task N> via iru-php-code-one-task",
    subagent_type: "iru-isolated-skill-executor",
    prompt: "Invoke Skill({skill: \"iru-php-code-one-task\", args: \"<the task's full text, including its exact
      implementation_plan.md checkbox line(s) for itself and its sub-tasks, its sub-tasks' own text, and any
      relevant Current code state context>\"}). Report back: the files touched, the fast tests added/updated and
      their result, the integration tests written but not run, any deviation from the skill's reference/
      guidance, any insecure neighbouring code noticed, for UI work the Screen › Region › Component tree and the
      UI specs written (screens/components and states, unrun), and whether the task stopped on a blocker. Fast tests
      only, scoped to the files you touched — no coverage, no full suite, no wp-env, no WP_UnitTestCase, nothing
      needing MySQL/PostgreSQL/Redis or a PrestaShop shop, no --parallel: sibling agents run concurrently and
      the integration stack is serialized by the group.",
    run_in_background: false
  })
  ```
- **Bucket marked `Parallelizable: no`**: invoke them one at a time in the plan's order, waiting for each.

Even in a parallelizable bucket, run sequentially any two tasks that edit the same file — a Laravel route file, a
WordPress main plugin file that registers hooks, a PrestaShop main module class (`install()`/hook list), a
`composer.json`, or a `theme.json` — since concurrent edits to one file clobber each other. Note the deviation in
Step 5.

As each agent reports back, re-read `implementation_plan.md` and confirm its box is checked; if not (a rare
concurrent-write race), flip it yourself with the same "group validation pending" note. Notify the user that this
task's implementation and fast tests landed. If a task reports a blocker, record it and exclude its code from
Step 3 — surface it in Step 5 without blocking the rest of the bucket.

## Step 3 — Validate the whole bucket once

First derive the scope: the union of the files each agent reported, cross-checked against `git status`, `git diff
--name-only`, and `git diff --name-only --cached` run now (a task can change a file as a side effect — a service
provider, a hook registration, `composer.json`). Production files and test files are listed separately: gates 1, 2
and 7 take production files (plus test files for 1), gates 4 and 5 take test selectors and target classes/files.
The bucket **touched UI** when the scope includes `*.blade.php`, `resources/views/**`, `app/Livewire/**`,
`app/View/Components/**`, `resources/css|js/**`, a block directory (`block.json`, `edit.js`, `render.php`, block
`*.scss`/`*.css`), `patterns/**`, `parts/**`, `templates/**`, `theme.json`, `*.tpl`, `*.twig`, `views/css|js/**`, or
`tests/e2e/**` — record the added/changed screens and components from the per-task decomposition trees for 3.9.

Run the following once for the whole bucket, in this order. **Steps 3.1–3.7 are the gates; 3.3, 3.8 and 3.9 add
information** (3.8 and 3.9 also fail the bucket on a genuine assertion failure — 3.9 also on an `enforce`
accessibility violation or a missing screenshot — but their absence never does). A bucket can be
fully gated on a machine with no Docker; only the integration result is lost.

1. **License headers**, via `iru-gate-runner`: `Agent({description: "Add license headers for task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-check-license\", args:
   \"<file1,file2,...>\"}) scoped to every file this bucket added or modified (PHP sources, tests, templates).
   Report back only which files were missing a header vs. fixed vs. already compliant, and — if no header
   convention existed anywhere in the repo — whether the user chose to skip or generate one."})`. Respect a skip
   choice for the rest of the run. PrestaShop modules carry an AFL/OSL-style header block on every file, WordPress
   plugins usually only on the main file — the skill follows whatever the repository already does. Skip if
   `iru-check-license` isn't installed.

2. **PHPDoc**, via `iru-gate-runner`: `Agent({description: "Update PHPDoc for task group", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-php-phpdoc\", args: \"<production file1,file2,...>\"}).
   Project flavor: <flavor>. Report back only which symbols (and, for WordPress, which hook invocations) were
   documented or completed, in which files, and whether the verification (PHPStan/Psalm, PHPCS docs sniffs,
   phpDocumentor where configured) passed."})`. If verification failed, fix and re-invoke until it passes. Skip if
   `iru-php-phpdoc` isn't installed. The per-task skill already writes docblocks; a lot of backfilling here means
   it was bypassed.

3. **Composer manifest checks — informational, never a gate.** Only when the project has a `composer.json`
   (many WordPress plugins and themes don't — note "no composer.json" and move on). Via `iru-gate-runner`:
   ```
   Agent({
     description: "Composer validate and audit for task group",
     subagent_type: "iru-gate-runner",
     prompt: "In <project dir>, run `composer validate --no-check-publish --no-interaction` and
       `composer audit --no-interaction --format=json --locked` (through <environment prefix> if host composer
       is unavailable). Report back only: whether composer.json is valid and any errors/warnings (one line each,
       noting a lock file out of date with composer.json), and the audit result as a count of advisories by
       severity plus one line per advisory (package, version, advisory id/CVE, title) and any abandoned packages.
       Don't paste the JSON.",
     run_in_background: false
   })
   ```
   Record the result for Step 5. Act on it only when **this bucket** caused it — a `composer.json` the bucket edited
   that no longer validates, or a lock file the bucket left out of sync (fix, re-run 3.4). Pre-existing advisories
   are reported, never fixed here: bumping a dependency is a plan-level change.

4. **Scoped fast tests**, via `iru-gate-runner`: `Agent({description: "Run fast tests for task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-php-test\", args: \"<the fast selector
   from Step 1, narrowed to every test file affected by this bucket — paths, plus -c <config> if the fast suite
   has its own config>\"}). Environment: <...>; runner: <...>. Fast tests only — do not select <integration
   selector>, do not run wp-env or any WP_UnitTestCase suite, nothing needing a database server or a shop, no
   --parallel. If everything passes, report back only that and the test count. If anything fails, report back
   only the failing test names and the first line of each failure; treat 'No tests executed'/0 tests as the
   selector matching nothing, not a pass."})`. Include the project's Pest `arch()` test file(s) in the selection if
   any exist — new code must satisfy them. Fix whichever task's code a failure traces to and re-invoke until green.

5. **Fast-test coverage** — the 80% gate. Via `iru-gate-runner`:
   ```
   Agent({
     description: "Check fast-test coverage for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Invoke Skill({skill: \"iru-php-coverage\", args: \"<fast selector covering the bucket's affected
       tests — prefer the whole fast suite or the containing directories over single files, since a class is often
       exercised from several test files> <TargetClass1,TargetClass2,...>\"}). Environment: <...>; runner: <...>.
       The measurement must come from FAST tests only: never the integration suite/config, never wp-env, never a
       database server. For procedural files (WordPress hook callbacks, templates, PrestaShop main module files)
       report per file instead of per class. Report back, per target, its line coverage % and uncovered line
       ranges, which coverage driver was used, and confirm the run used only fast tests. If no coverage driver is
       available, say so — never report 0%.",
     run_in_background: false
   })
   ```
   For any target under 80%, add **fast** tests for the uncovered lines via the task that owns that code, re-run
   3.4, and re-check here until it clears.

   **An integration test never closes this gap.** If a class sits under 80% and the argument for accepting it is
   that a `WP_UnitTestCase` or MySQL-backed test exercises it, that's the case this gate exists to surface: either
   unit-test the logic (usually by moving it out of the hook callback/controller/module class into a plain class
   with injected dependencies, where it is trivially testable), or record it in Step 5 as an accepted exception
   with that reasoning. Genuine exceptions, each named in Step 5: a thin hook-registration or bootstrap file with no
   branching logic, a template (`*.blade.php`, theme template parts, `render.php`, `*.tpl`), a migration or upgrade
   script, and generated code. **No coverage driver** (neither PCOV nor Xdebug) → the gate is **unverified**, said
   plainly in Steps 4 and 5 — never treated as a pass.

6. **Full fast suite**, via `iru-gate-runner`: `Agent({description: "Run full fast test suite", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-php-test\", args: \"<the fast selector from Step 1 with
   no path narrowing>\"}). Same environment and runner. Fast tests only — the integration suite is excluded.
   If everything passes, report back only that and the count. If anything fails, report back only the failing
   test names and the first line of each failure."})`. Fix any regression and re-invoke until green.

7. **Code quality** — over the same production files as Step 1, diffed against that baseline. Via
   `iru-gate-runner`: `Agent({description: "Check for new quality issues in task group", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-php-code-quality\", args: \"<file1,file2,...>\"}).
   Compare against this pre-change baseline: <baseline from Step 1>. Report back only issues newly appearing
   (not in the baseline), each with tool, rule id/identifier, file, and line — and list Rector findings
   separately as informational. Note any tool that failed to run (memory exhaustion, missing _PS_ROOT_DIR_,
   missing PHPCS standard) as a tooling problem, not a code issue."})`.

   Any new PHPStan/Psalm/Pint/PHP-CS-Fixer/PHPCS issue is a defect this bucket introduced — trace it to its task,
   fix it, re-run 3.4 and 3.6, and re-check. Treat **`WordPress.Security.*`, `WordPress.DB.*` (prepared SQL),
   Psalm `Tainted*`, and Pest/PHPStan security findings** as the most serious: they describe exploitable code, not
   style. Leave an issue only if fixing it would contradict what its task explicitly specifies; record that and
   why. **Rector findings never gate**: they describe code Rector *would* modernize; list the new ones in Step 5 for
   information, and never apply them here. Never add an entry to `phpstan-baseline.neon`/`psalm-baseline.xml` or
   an `ignoreErrors`/`phpcs:ignore` to make a new issue disappear unless the task says so. Skip if
   `iru-php-code-quality` isn't installed.

8a. **Acquire the repository-wide integration-test lock** — immediately before 3.8, never earlier.

   Skip 3.8a–3.8b entirely (no lock, no run) when Step 1 found **no integration suite**, or when the bucket's
   changes can't affect it (e.g. only a Pest arch rule or a pure value object a WordPress integration suite never
   loads — state the reasoning in Step 5; when unsure, run it). If **Docker is unavailable**, report the stage as
   **unverified (Docker unavailable)** and continue to Step 4 — never as passed.

   The lock is a directory at the repository root, because `mkdir` either creates it or fails, atomically — a lock
   *file* written with `Write` has a read-then-write window and must not be used instead:

   ```bash
   mkdir .integration-test.lock 2>/dev/null && echo ACQUIRED || echo HELD
   ```

   - **`ACQUIRED`** — write `.integration-test.lock/owner` (via `Write`) with this bucket's task numbers/titles, the
     integration selector, and `date -u +'%Y-%m-%dT%H:%M:%SZ'`. Ensure `.integration-test.lock/` is in the root
     `.gitignore`, adding the line if missing.
   - **`HELD`** — tell the user this bucket is waiting (naming the holder from `cat .integration-test.lock/owner`),
     then re-run the `mkdir` roughly every 30 seconds. Don't run the integration suite anyway, not even "one quick
     test" — a single `WP_UnitTestCase` boots the same shared database.
   - **Stale lock**: if `find .integration-test.lock -maxdepth 0 -mmin +60` returns the directory, its holder almost
     certainly died. Tell the user what the owner file says, break it (`rm -f .integration-test.lock/owner &&
     rmdir .integration-test.lock`), acquire fresh, and say so in Step 5.
   - **Wait timeout**: still held after ~45 minutes and not stale → report **unverified — blocked by a concurrent
     integration run** and continue to Step 4.

8. **Integration tests** — only now, with everything above clean and the lock held. First make sure the
   environment is up **without starting anything the user hasn't agreed to**:

   - **wp-env**: check the **test** environment — `npx wp-env run cli --config=.wp-env.test.json wp core version`
     when `.wp-env.test.json` exists (`tests-cli` without `--config` only for older projects still using the
     deprecated `testsEnvironment`) — succeeds → running. If not, ask once (`AskUserQuestion`: start it with the
     project's `npm run test:env:start`, else `npx wp-env start --config=.wp-env.test.json` / leave the stage
     unverified). **Never start or test against the development environment** (`npx wp-env start` with the plain
     `.wp-env.json`): its test config shares the dev site's database, which the installer wipes. Starting wp-env
     pulls images and takes minutes; never `wp-env destroy`/`clean`.
   - **`bin/install-wp-tests.sh` setups**: the test library at `$WP_TESTS_DIR` (default
     `/tmp/wordpress-tests-lib`) and its MySQL must already exist — never run the script yourself (it drops the
     named database).
   - **Sail**: `./vendor/bin/sail ps` shows the services up → running. If not, ask once (start with
     `./vendor/bin/sail up -d` / leave unverified). Never `sail down` or `docker compose down` a stack this skill
     didn't start.
   - **PrestaShop**: the shop the integration bootstrap points at (`_PS_ROOT_DIR_`/the project's compose service)
     must exist and be reachable — otherwise **unverified (no PrestaShop installation)**. Never install a shop here.

   Declined, unreachable, or missing → **unverified** with that reason; release the lock (3.8b) and continue.
   Otherwise:
   ```
   Agent({
     description: "Run integration tests for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Invoke Skill({skill: \"iru-php-test\", args: \"<integration selector from Step 1, e.g. -c
       phpunit-integration.xml, or --testsuite Integration>\"}) through <the project's test:integration script / wp-env cli --config=.wp-env.test.json (tests-cli only on
       legacy testsEnvironment projects) / Sail / ddev / host>.
       Run exactly this one invocation — no --parallel, nothing else concurrently: the suite shares one database.
       It is slow; wait for it. Report back only: whether all integration tests passed; for any failure, the test
       name and first failure line; and separately whether any failure was an environment problem (database
       unreachable, WordPress test library missing, container not started, image pull failure) rather than an
       assertion failure. Do not dump the console output.",
     run_in_background: false
   })
   ```
   Run this agent on its own, never alongside another test-running agent, and never in the background — the lock
   is only meaningful while this skill waits for the run.

   - **Environment failure** → unverified, with the cause; don't spend the bucket debugging infrastructure.
   - **Assertion failure** → trace it to its task, fix, re-run. Keep the lock for a quick fix; for a long one,
     release (3.8b), fix, re-acquire (3.8a). If the fix changed production code, re-run 3.4–3.7 afterwards. An
     integration failure the fast tests missed is a finding worth reporting even once fixed.

8b. **Release the lock** as soon as 3.8 finishes (and any fix-and-re-run cycle with it):

   ```bash
   rm -f .integration-test.lock/owner && rmdir .integration-test.lock
   ```

   On **every** exit path — passed, failed, environment failure, user declined, blocked task, interruption, hard
   stop. A lock left behind blocks every later group for an hour. If you took it, you release it before returning.
   When 3.9 will run, keep the lock through it instead and release here once 3.9 finishes.

9. **Browser UI tests, screenshot matrix, accessibility** — only when the bucket touched UI (scope above); skip
   otherwise and say "no UI changes" in Step 5. No browser-test setup (Step 1) → report **no UI test setup** for
   the UI the bucket changed, never a pass. The run starts a server, needs wp-env's tests instance or a shop, so it
   holds `.integration-test.lock` exactly like 3.8 — still held from 3.8, or acquired now with 3.8a's procedure,
   released with 3.8b's on every exit path. Preflight as 3.8 does (wp-env tests instance up, `PS_BASE_URL`
   reachable, Docker for wp-env); missing → **unverified** with the reason. Then:
   ```
   Agent({
     description: "Run browser UI tests for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Invoke Skill({skill: \"iru-php-test\", args: \"ui <spec paths for the bucket's screens/components, or
       nothing for the full UI run>\"}). Flavor: <flavor>; target: <php artisan serve webServer | wp-env tests
       instance | PS_BASE_URL>. The lock is already held by the caller (lock: held). Report back only: passed/failed
       counts with the failing spec names and first failure line; screenshot count per kind and any malformed
       names; accessibility violations as count + rule ids and the .ui-quality.yml level applied; and whether any
       failure was environmental (no browser, server/wp-env/shop unreachable) rather than an assertion.",
     run_in_background: false
   })
   ```
   - **Assertion failure** → trace to its task, fix, re-run (as in 3.8). **Environment failure** → **unverified**
     with the cause (`no Chromium`, `wp-env tests instance not running`, `no shop at PS_BASE_URL`).
   - **Screenshot matrix**: read `.ui-quality.yml` (`directions`, `themes`; defaults when missing). For every
     screen and component the bucket added or changed, and every visual state its decomposition lists,
     `.ui-screenshots/<kind>/<Id>/<state>--<direction>--<theme>[--<variant>…].png` must exist for each configured
     direction × theme (`ls .ui-screenshots/<kind>/<Id>/`). A missing capture is a defect — the owning task adds
     it, then re-run this stage.
   - **Accessibility**: `enforce` → any violation fails the specs; fix the markup/contrast (trace to its task) and
     re-run. `report` → list rule ids × count in Step 5, never failing. `off` → note "accessibility checks off".

Integration-test coverage is never measured or reported as a gate figure here; the only coverage number this skill
quotes is 3.5's fast-test figure.

## Step 4 — Finalize progress notes and notify the user

Once Step 3 passes clean (or leaves only explicitly-accepted issues), replace each task's "group validation pending"
note in `implementation_plan.md` with the final outcome — fast-test coverage, quality, and the integration result:

```
- [x] Task 2. **Add `acme_loans_handle_save` admin-post handler** — Brain Monkey tests (91% line coverage,
  fast tests only); no new PHPCS/PHPStan issues; WP_UnitTestCase LoanFormTest passed under wp-env.
```

If the integration stage was unverified, say so with the reason (`integration tests unverified (Docker
unavailable)`, `… (wp-env not started — user declined)`, `… (lock held by another group)`) — silence reads as a
pass. For a bucket that touched UI, add the UI outcome the same way (`UI tests passed, 12 screenshots (screen 4,
component 8), axe enforce clean`, `UI tests unverified (no shop at PS_BASE_URL)`, `no UI test setup`). Same for an unverified coverage gate (`coverage unverified (no PCOV/Xdebug)`). Then notify the user that group
validation completed, summarizing the bucket-wide result.

Before writing these notes, confirm the lock is released (3.8b); if a failure path skipped it, release it now.

If any task was recorded as blocked in Step 2, leave its checkbox unchecked with its blocker note.

## Step 5 — Report the bucket's outcome

Hand control back to `iru-code-one-task-group` with a summary covering the whole bucket:

- **The detected flavor, environment, runner, and the fast/integration selectors** used — and any suite treated as
  integration because it couldn't be classified with confidence.
- **Per task**: files touched, tests added/updated (fast vs. integration), **fast-test line coverage**, and the
  code-quality outcome.
- **Security-relevant findings** (Step 3.7's `WordPress.Security.*`/`WordPress.DB.*`/`Tainted*` and similar, fixed
  or accepted) and any insecure neighbouring code the per-task agents reported — these are the findings most worth
  escalating.
- **Composer manifest checks** (Step 3.3): validity, advisories by severity, abandoned packages — or "no
  composer.json".
- **Rector findings** (informational) the bucket's new code would trigger.
- **Any coverage exception accepted**, with the reasoning, and any class whose behavior is only exercised by the
  integration suite.
- **The integration-test result** — passed, failed-and-fixed, **unverified** with the reason, or "no integration
  suite" — and how the lock behaved whenever it wasn't an immediate uncontended acquire (wait time, stale lock
  broken and what its owner file said, timeout), plus confirmation it was released.
- **The UI stage** (3.9): passed/failed-and-fixed/**unverified** with the reason/"no UI test setup"/"no UI
  changes"; screenshot count per kind and any missing or malformed captures; accessibility violations (count + rule
  ids) and the `.ui-quality.yml` level applied; the decomposition trees the tasks reported.
- Whether a new quality issue was left in place as unavoidable and why; whether license-header generation was
  skipped by the user; any sequential-ordering deviation applied in Step 2; and which task(s), if any, stopped on a
  blocker, with enough detail for the caller to surface it further up.

`implementation_plan.md`'s checkboxes are already checked and finalized (Steps 2 and 4) and the user-facing
notifications have gone out — the caller doesn't need to redo that bookkeeping for this bucket.
