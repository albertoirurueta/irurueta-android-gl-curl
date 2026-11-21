---
name: iru-typescript-test
description: "/iru-typescript-test [e2e|ui] [path|dir|-t \"name\"] - runs a TypeScript/npm project's unit tests through its wired runner (Vitest, Jest, ng test); the optional e2e/ui prefix runs end-to-end or UI suites (NestJS, Next.js, React Native, Playwright). Report-only; never edits code."
model: haiku
---

# TypeScript Test

Run the project's unit tests through whichever test runner it actually has configured, scoping the run to
whatever the user asked for, and report a compact pass/fail summary parsed from the runner's own JSON reporter.
This skill only runs tests and reports results — it does not fix failures or modify source/test code unless
asked to as a follow-up.

## Step 0 — Parse the arguments

The argument string is a selector (Step 2) plus optional `key: value` lines from a calling skill:

- **`lock: held`** — the caller (e.g. `iru-react-native-code-one-task-group`) already holds the repository-wide
  `.integration-test.lock`; a React Native `e2e` run must not acquire or release it here (Step 2, React Native e2e).
  Ignored for every other selector.
- **`configuration: <name>`** — React Native `e2e` with Detox only: the `.detoxrc.js` configuration to run
  (`ios.sim.debug`, `android.emu.debug`, …). Asked/derived in Step 2 when absent.

A leading `ui` keyword in the selector means exactly the same as `e2e` (Step 2) — it exists so callers can say "the
UI tests" for a web app or a React Native app without caring which runner drives them.

## Step 1 — Detect the runner, the package manager, and confirm it is actually wired

Never assume a runner from the framework alone — read `package.json` (and `angular.json` when present) first:

- **Vitest**: `vitest` appears in `dependencies`/`devDependencies` and a `vitest.config.ts`/`.js`/`.mts` file (or
  a `test`/`vitest` block in `vite.config.ts`) exists.
- **Jest**: `jest` or `jest-expo` appears in `dependencies`/`devDependencies` and a `jest.config.*` file, or a
  `"jest"` key in `package.json`, exists.
- **Angular (`ng test`, Vitest runner)**: `@angular/build` is a devDependency and `angular.json`'s `test`
  architect target for the project uses builder `@angular/build:unit-test`. This builder's `runner` option
  defaults to `"vitest"` (confirmed via `ng test --help` on Angular CLI 22.1 — choices are `"karma"`/`"vitest"`,
  default `"vitest"`); if the target's `options.runner` is explicitly set to `"karma"`, this skill's Vitest-shaped
  commands below don't apply — report that and stop rather than guessing at Karma's CLI.
- If none of the above is found (no matching devDependency and no config file), **stop and report "no test
  runner wired"** — do not report "0 tests" as if a scoped run legitimately found nothing; those look identical
  in a broken CI job and must not be conflated.

**Framework detection** (shared with `iru-typescript-coverage`/`-code-quality`/`-tsdoc`): `@nestjs/core` in
`dependencies` → **nestjs**; `next` in `dependencies` → **nextjs**; `react-native` or `expo` in `dependencies` →
**react-native**; otherwise, `@playwright/test` in `devDependencies` plus a `playwright.config.{ts,js,mjs}` in a
web app (React/Vite, Angular CLI, Ionic) → **web-ui**. All of them still run on Vitest or Jest (or `ng test`),
detected exactly as above — the framework only changes which suite counts as "unit", how failures are explained, and
how the opt-in `e2e`/`ui` selector (Step 2) is resolved:

- **NestJS** — two flavors, both with unit tests as `*.spec.ts` next to the source and end-to-end tests as
  `test/*.e2e-spec.ts`, kept apart by two separate configs so the default run never picks up e2e files:
  - **ESM (Nest 12's default scaffold)**: `"type": "module"` in `package.json`, Vitest, `vitest.config.ts`
    (`include: ['**/*.spec.ts']`) for unit and `vitest.config.e2e.ts` (`include: ['**/*.e2e-spec.ts']`) for e2e;
    scripts `test` = `vitest run`, `test:e2e` = `vitest run --config ./vitest.config.e2e.ts`. Relative imports
    ending in `.js` are correct here (nodenext resolution) — never report them as a problem.
  - **CommonJS (pre-v12 projects, or Nest 12 with CommonJS chosen)**: no `"type"` field, Jest 30 + `ts-jest`,
    `jest.config.ts` (or a `"jest"` key in `package.json`, `testRegex: '.*\\.spec\\.ts$'`) for unit and
    `test/jest-e2e.json` (`testRegex: ".e2e-spec.ts$"`) for e2e; `test:e2e` = `jest --config ./test/jest-e2e.json`.
    Some scaffolds run Jest as `node --experimental-vm-modules ./node_modules/jest/bin/jest.js` in their scripts —
    when the project's `test`/`test:e2e` script does, invoke Jest the same way (that prefix replaces `npx jest`
    below) so the run behaves identically.
  - Always read the project's actual `test:e2e` script to find the e2e config path rather than assuming the
    scaffold's default location; if there is no `test:e2e` script and no e2e config file, an `e2e` selector is
    reported as "NestJS e2e suite not wired", not as zero tests.
- **Next.js** — unit/component tests run on Vitest (`vitest.config.mts`, `.mts` so it's ESM regardless of the
  package `"type"`) or on Jest configured through `next/jest` (`jest.config.ts` importing `next/jest.js`), both
  detected exactly as above. End-to-end tests are **Playwright**: `@playwright/test` in `devDependencies` plus a
  `playwright.config.{ts,js,mjs}`. Its `webServer` block typically runs `next build` + `next start` before any test
  executes, so an e2e run is slow (minutes) and can fail on a build error unrelated to the tests — never run it
  unless the `e2e` selector asks for it. Cypress (`cypress.config.*`) is out of scope for this skill — report it as
  "Cypress e2e present, not run" if the user asks for e2e and only Cypress is wired.
- **Web UI (React/Vite, Angular, Ionic)** — unit/component tests stay on the unit runner (a jsdom/happy-dom run is
  behaviour only and never produces screenshots); the browser UI tests are **Playwright** (`playwright test`, the
  project's own `e2e`/`test:e2e`/`test:ui` script names the config — run every Playwright config those scripts name,
  e.g. a separate `playwright-ct.config.ts` for Playwright component testing). Same environment-failure rules as
  Next.js below (browsers missing, `webServer` not starting). Cypress is out of scope here (report "Cypress present,
  not run").
- **React Native** — two toolchains, both unit-tested with **Jest** (never Vitest — the React Native runtime and its
  Flow-typed sources need Babel through `babel-jest`). Resolve the **toolchain** first (same rule every React Native
  skill in this catalog uses): `expo` in `dependencies` → **expo**, even when `android/`/`ios/` exist (they're
  `npx expo prebuild` / Continuous Native Generation output); `react-native` without `expo`, plus a
  `metro.config.js`, committed `android/` + `ios/`, or `@react-native-community/cli` → **metro** (a bare React
  Native CLI app). Expo bundles with Metro too — "metro" names the toolchain users pick, not the only one that uses
  Metro. Then read the Jest **preset** (`preset` in `jest.config.{js,ts}`, or `jest.preset` in `package.json`):
  - **expo** → `jest-expo` (or a platform variant: `jest-expo/ios`, `jest-expo/android`, `jest-expo/web`,
    `jest-expo/universal`). The blank `create-expo-app` template ships **no** test setup at all — `jest-expo` +
    `jest` + `@types/jest` are added by `iru-setup-react-native-app` (or `npx expo install --dev jest-expo jest
    @types/jest`); without them, Step 1's "no test runner wired" rule applies. `jest-expo/universal` runs every
    test once per platform project, so totals are a multiple of the test count — say so rather than reporting
    "4× as many tests as files suggest".
  - **metro** → `@react-native/jest-preset` on current templates (verified: the React Native CLI 20.2 /
    React Native 0.87.1 TypeScript template writes `module.exports = { preset: '@react-native/jest-preset' };` and
    there is no `react-native/jest-preset.js` any more), or `preset: 'react-native'` on templates from before the
    preset moved into its own package. Both are the same preset; treat either as wired.
  - A preset that contradicts the toolchain (e.g. `react-native` preset in an Expo app) still runs — mention it in
    the report, since Expo SDK modules then aren't auto-mocked and native-module failures (below) become likely.

  Unit tests live in `__tests__/` (the metro template's `__tests__/App.test.tsx`) or next to the source as
  `*.test.ts(x)`; device end-to-end flows live in `.maestro/` (Maestro) or `e2e/` (Detox) and are never picked up
  by Jest's default `testMatch` — only the `e2e` selector (Step 2) runs them.

  **React Native–specific unit failures** — recognize these in the parsed results (Step 3) and report them by cause,
  never as unexplained test failures, and never "fix" them (report-only):
  - **Untranspiled package** — a whole suite fails to run with `Jest encountered an unexpected token` /
    `SyntaxError: Cannot use import statement outside a module`, pointing into `node_modules/<pkg>/`. The package
    ships untranspiled ESM/Flow and isn't in the preset's `transformIgnorePatterns` allow-list
    (`@react-native/jest-preset`: `node_modules/(?!((jest-)?react-native|@react-native(-community)?)/)`; `jest-expo`
    additionally allows `expo`, `@expo`, `react-navigation`, `@react-navigation`, `@sentry/react-native`, … —
    both read from the installed presets). Report "suite `<file>` failed to run: `<pkg>` must be added to
    `transformIgnorePatterns`". Verified locally by importing an ESM-only fake package from a test in the metro app.
  - **Native module without a Jest mock** — `Invariant Violation: TurboModuleRegistry.getEnforcing(...): '<Name>'
    could not be found`, `NativeModule: <Name> is null`, or `Cannot read properties of undefined` thrown from a
    library's native bridge. Jest runs no native code; the library's own mock (e.g. its `jest/` setup file) must be
    registered with `jest.mock(...)` in a `setupFiles`/`jest.setup.js`. Report "native module `<Name>` not mocked".
    Verified locally: `TurboModuleRegistry.getEnforcing('RNFakeModule')` fails with exactly that first line.
  - **React Native Testing Library 14** (`@testing-library/react-native` ≥ 14) made `render`, `fireEvent.*`, and
    `renderHook` return promises — a test that calls `render(<App />)` without `await` fails with
    `` `render` function has not been called `` from the next `screen.*` query (verified locally on 14.1.0). Report
    "RNTL 14: `render` not awaited" rather than a component bug.

Determine the package manager from `packageManager` in `package.json`, or the lockfile present:
`package-lock.json` → npm, `pnpm-lock.yaml` → pnpm, `yarn.lock` → yarn. Invoke the runner's binary directly
(`npx <bin>` / `pnpm exec <bin>` / `yarn <bin>`) rather than through the project's own `test` npm script — the
script frequently omits `run` (Vitest) or hard-codes its own flags, and forwarding extra flags through `npm test
--` / `pnpm test --` is inconsistent across package managers. Calling the binary directly keeps the exact flags
below predictable regardless of package manager:

| Package manager | Invocation prefix |
|---|---|
| npm | `npx <bin> ...` *(verified locally)* |
| pnpm | `pnpm exec <bin> ...` |
| yarn (classic or berry) | `yarn <bin> ...` |

Only the npm form was exercised on this machine; the pnpm/yarn forms are standard, well-documented invocation
syntax but are marked **unverified locally**.

## Step 2 — Determine scope from the argument

| What the user wants | Vitest | Jest | `ng test` (Vitest runner) |
|---|---|---|---|
| Everything | `vitest run` | `jest` | `ng test --watch=false` |
| One file | `vitest run path/to/file.test.ts` | `jest path/to/file.test.ts` | `ng test --watch=false --include=path/to/file.spec.ts` |
| A directory | `vitest run path/to/dir` | `jest path/to/dir` | `ng test --watch=false --include="path/to/dir/**"` |
| Name pattern | `vitest run -t "<pattern>"` | `jest -t "<pattern>"` (alias of `--testNamePattern`) | `ng test --watch=false --filter="<pattern>"` |
| File/dir + name pattern | `vitest run path/to/file.test.ts -t "<pattern>"` | `jest path/to/file.test.ts -t "<pattern>"` | `ng test --watch=false --include=path/to/file.spec.ts --filter="<pattern>"` |

Notes, all confirmed by running both scenarios locally (Vitest 5.0.1, Jest 30.x, Angular CLI 22.1.8 with its
built-in Vitest 4.0.8 runner) in `$TMPDIR/iru-verify/typescript/test/{vitest-proj,jest-proj,ng-app}`:

- **An unmatched file/directory selector is a hard failure for every runner tested, not a silent zero.**
  - `vitest run <unmatched-path>` prints `No test files found, exiting with code 1` and exits **1**.
  - `jest <unmatched-path>` prints `No tests found, exiting with code 1` (plus a hint about
    `--passWithNoTests`) and exits **1** by default — the plain `jest` CLI does *not* pass silently on zero
    matched files unless the project's own config sets `passWithNoTests: true` or the flag is passed explicitly.
    Treat a non-zero exit here as "selector matched nothing", the same as Vitest, rather than assuming Jest is
    lenient — it only is if the project opted in.
  - `ng test --watch=false --include=<unmatched-glob>` fails before any tests run, with `Error: No tests found
    matching the following patterns: - Included: <glob>` and exits **1** (same underlying Vitest runner).
- **An unmatched `-t`/`--filter` *name* pattern, against files that *do* match, is not a failure for any runner
  tested.** All three report every test in the matched file(s) as **skipped** and exit **0**
  (`Test Files 1 skipped (1)` / `Tests 2 skipped (2)` for Vitest and `ng test`; `Test Suites: 1 skipped, 0 of 1
  total` / `Tests: 2 skipped, 2 total` for Jest). Report a 0-matched-by-name run as "0 of N tests matched the
  name filter — nothing ran", not as "all passed", since a typoed `-t` value silently runs nothing but still
  exits 0.
- `ng test`'s selector flags differ in name from the other two runners: use `--include=<glob>` (not a bare
  positional path) for file/directory scope, and `--filter=<pattern>` (not `-t`) for the name pattern — `--filter`
  is a regular expression matched against suite + test names, per `ng test --help`.
- `CI=true` in the environment is worth setting defensively alongside the exact invocations above, in case a
  project's own tooling (wrapper scripts, `react-scripts`-style setups) keys off it to force non-watch/run-once
  behavior; it is not required for the `vitest run` / `jest` / `ng test --watch=false` forms themselves, since
  each already runs once and exits by explicit flag/subcommand rather than by CI auto-detection.

### The `e2e` selector (NestJS, Next.js, React Native, and Playwright-wired web UIs only)

Without a leading `e2e` keyword, every row above runs the **unit** suite only — for NestJS that is the default
`vitest.config.ts`/`jest.config.ts`, which matches `*.spec.ts` and never `*.e2e-spec.ts`; for Next.js it is the
Vitest/Jest suite, never Playwright; for React Native it is the Jest suite, never Maestro/Detox. `unit` as a leading
keyword is accepted and means the same as no keyword.
A leading `e2e` keyword switches to the end-to-end suite, with the rest of the selector scoping it the same way:

| What the user wants | NestJS, Vitest (ESM) | NestJS, Jest (CommonJS) | Next.js, Playwright |
|---|---|---|---|
| Whole e2e suite | `vitest run --config ./vitest.config.e2e.ts` | `jest --config ./test/jest-e2e.json` | `playwright test` (also the web-ui form) |
| One file | `vitest run --config ./vitest.config.e2e.ts test/books.e2e-spec.ts` | `jest --config ./test/jest-e2e.json test/books.e2e-spec.ts` | `playwright test e2e/books.spec.ts` |
| Name pattern | `... -t "<pattern>"` | `... -t "<pattern>"` | `playwright test -g "<pattern>"` |

(Use the config path the project's own `test:e2e` script names, per Step 1, in place of the scaffold defaults
shown.) Web-ui projects use the Playwright column unchanged. React Native's e2e forms are in their own subsection
below. `e2e` on a project that is none of NestJS, Next.js, React Native, or web-ui is not supported — say so and stop
rather than guessing at a runner. Every Playwright and React Native e2e run is also a **UI run** — see "UI runs"
below, which wraps the command with the screenshot directory and the accessibility summary.

E2E runs depend on things unit tests don't, and a failure caused by them is an **environment failure**, not a test
failure — report it as such, naming the cause, and never as failing tests:

- **NestJS**: e2e specs boot the whole `AppModule` (`createNestApplication()` + `app.init()`), so they need whatever
  the module connects to at start-up — a database, Redis, a broker. Projects commonly provide it with Testcontainers
  (`testcontainers`/`@testcontainers/*` in `devDependencies`), which needs a running Docker/Podman daemon:
  `Could not find a working container runtime strategy`, `connect ECONNREFUSED` to a DB port, `getaddrinfo
  ENOTFOUND`, a TypeORM/Prisma/Mongoose connection error thrown from `beforeAll`, or a `beforeAll` hook timeout all
  belong here. Check `docker info` first when the project uses Testcontainers; if the daemon isn't reachable, report
  "NestJS e2e not run: Docker unavailable" instead of running and reporting every spec as failed. Container-backed
  runs share databases, so a caller running several of them (e.g. a `*-code-one-task-group` skill) is responsible
  for serializing them — this skill runs exactly one invocation and never in parallel with itself.
- **Next.js / Playwright**: the `webServer` command (`next build && next start`) must succeed and the browsers must
  be installed (`npx playwright install` — this skill never runs it unprompted, since it downloads browser
  binaries). `Executable doesn't exist at ...` (browsers missing), `Error: Timed out waiting ... from config.webServer`,
  `Error: Process from config.webServer was not able to start`, or a `next build` compile error printed before any
  test ran are environment/build failures, not test failures.

NestJS and Next.js e2e runs are serialized by their callers (`iru-nestjs-code-one-task-group`/
`iru-nextjs-code-one-task-group` take `.integration-test.lock` around this skill's invocation); React Native e2e
takes the lock itself unless `lock: held` says the caller already did, because it also starts Metro on a fixed port
and drives a shared simulator/emulator — two concurrent runs would fight over both.

### React Native e2e (Maestro or Detox)

**Pick the framework**: a `.detoxrc.js`/`.detoxrc.json` (or a `"detox"` key in `package.json`) plus `detox` in
`devDependencies` → **Detox**; otherwise a `.maestro/` directory of flow YAML files → **Maestro** (this catalog's
default for both toolchains; `iru-setup-react-native-app` writes `.maestro/smoke.yaml`). Neither → report "React
Native e2e suite not wired", not zero tests.

| What the user wants | Maestro | Detox (`<cfg>` = `configuration:` arg or chosen below) |
|---|---|---|
| Whole e2e suite | `maestro test --format junit --output <path>.xml .maestro/` | `detox build -c <cfg>` then `detox test -c <cfg> --json --outputFile=<path>.json` |
| One flow / file | `maestro test --format junit --output <path>.xml .maestro/login.yaml` | `detox test -c <cfg> e2e/login.test.ts --json --outputFile=<path>.json` |
| Name/tag filter | `maestro test --include-tags=<tag> ... .maestro/` (Maestro filters by flow `tags:`, not by name) | `detox test -c <cfg> --testNamePattern "<pattern>" ...` |

- **Detox configuration**: read the `configurations` keys of `.detoxrc.js` (e.g. `ios.sim.debug`,
  `ios.sim.release`, `android.emu.debug`, `android.att.debug`). With no `configuration:` arg, prefer the single
  configuration if there is one, else a `*.debug` one for the current platform (iOS only on macOS); ask when still
  ambiguous. `detox build` compiles the native app (minutes) — run it once per invocation, before `detox test`.
  Unknown `detox test` options are forwarded to its Jest runner, which is why the Jest `--json`/`--outputFile`/
  `--testNamePattern` flags above work there (**unverified locally** — `detox test --help` was read from Detox
  20.51.4, but no Detox suite was run).
- **Maestro** runs against an app that is **already installed** on the device — a debug/development build
  (`npx react-native run-ios`/`run-android` for metro, `npx expo run:ios`/`run:android` for an Expo development
  build). This skill never builds or installs the app unless asked; a flow failing on `launchApp` because the
  `appId` isn't installed is an environment failure.

**Preconditions, checked in this order — any miss makes the run "unverified (<reason>)", never passed, never N
failed tests, never silently skipped:**

1. **Tool installed**: `command -v maestro` (installed outside npm — `curl -Ls "https://get.maestro.mobile.dev" |
   bash` per Maestro's docs; this skill never installs it) or `npx detox --version`.
2. **A device**: iOS — `xcrun simctl list devices booted` lists a `(Booted)` simulator (macOS only); Android —
   `adb devices` (from `$ANDROID_HOME/platform-tools`) lists a line ending in `device`. Maestro needs one already
   booted. Detox boots the simulator/AVD its configuration names itself, so for Detox check instead that it exists
   (`xcrun simctl list devices` contains the configured `device.type`; `emulator -list-avds` contains the configured
   `avdName`). None → "unverified (no booted simulator/emulator)".
3. **The repository lock** (below), unless `lock: held`.
4. **Metro on port 8081** — needed by every debug/development build (Detox `*.release` configurations embed the JS
   bundle and skip this): `curl -s http://localhost:8081/status` returns `packager-status:running` when a bundler
   is up (verified locally for both toolchains). If it isn't, start it in the background from the app directory —
   `npx react-native start --port 8081` (metro) or `npx expo start --port 8081` (expo; set `CI=1` so it doesn't
   prompt) — and poll `/status` for up to ~90 seconds (both reached `packager-status:running` within that window
   locally). Something else listening on 8081 without that status line → "unverified (port 8081 busy)". Only stop
   a Metro this run started (`pkill -f "react-native start --port 8081"` / `pkill -f "expo start --port 8081"`),
   and stop it before releasing the lock.

**Lock protocol** (same directory, owner file, staleness rule, and release discipline as every other
container/server/device stage in this catalog — `iru-python-test`'s Step 4, the `*-code-one-task-group` skills'
Step 3 lock): at the repository root (`git rev-parse --show-toplevel`, else the current directory),

```bash
mkdir .integration-test.lock 2>/dev/null && echo ACQUIRED || echo HELD
```

- **`ACQUIRED`** — write `.integration-test.lock/owner` (via `Write`) with "iru-typescript-test react-native e2e",
  the selector, and `date -u +'%Y-%m-%dT%H:%M:%SZ'`. If `.integration-test.lock/` isn't in the root `.gitignore`,
  say so in the report (this skill doesn't edit `.gitignore`).
- **`HELD`** — say the run is waiting (naming the holder from `cat .integration-test.lock/owner`), re-run the `mkdir`
  roughly every 30 seconds, and never start Metro or the device run meanwhile.
- **Stale lock** — `find .integration-test.lock -maxdepth 0 -mmin +60` returns the directory: report what the owner
  file said, break it (`rm -f .integration-test.lock/owner && rmdir .integration-test.lock`), acquire fresh, say so.
- **Wait timeout** — still held after ~45 minutes and not stale → "unverified — blocked by a concurrent
  integration run".
- **Release on every exit path** (passed, failed, environment failure, interruption), after stopping any Metro this
  run started: `rm -f .integration-test.lock/owner && rmdir .integration-test.lock`. Never release a lock taken
  under `lock: held`.

**Environment failures, not test failures**: `detox build` compile/Gradle/CocoaPods errors, `DetoxRuntimeError`
about a missing/unbootable device, Metro `Unable to resolve module` red-screen errors caught before the first
assertion, and Maestro failing to connect to a device. Device e2e results never count toward the 80% unit coverage
gate (`iru-typescript-coverage` measures the Jest suite only).

None of the Maestro/Detox commands above were run locally (no simulator/emulator was booted, Maestro isn't
installed here) — they follow the tools' documented CLIs and are **unverified locally**; the Metro start/status
probe and the lock commands are the verified parts.

### UI runs — screenshots and accessibility (Playwright, Maestro, Detox)

The catalog's UI contract (each UI coding skill's `reference/ui-contract.md` §6–§8) makes every UI test capture its
states as PNGs under one directory and run an automated accessibility check at the level the repository chose. This
skill owns the run-level half of that; the tests themselves own the captures.

1. **Resolve the settings** at the repository root (`git rev-parse --show-toplevel`, else the current directory):
   - `UI_SCREENSHOTS_DIR` from the environment (must be absolute) or `<repo root>/.ui-screenshots`.
   - `.ui-quality.yml`: `accessibility` (`enforce`/`report`/`off`/`none`), `directions`, `themes` — a missing file or key
     means `enforce`, `[ltr, rtl]`, `[light, dark]`. Read the three keys with a line match
     (`^accessibility:\s*(\w+)`, `^directions:\s*\[(.*)\]`, `^themes:\s*\[(.*)\]`); anything else is reported
     as "unparsed `.ui-quality.yml`, defaults applied".
2. **Clear the directory only for a full UI run** — the `e2e`/`ui` selector with no file, directory, name pattern or
   tag. A scoped run adds to what is there. Clear its *contents*, and only when the resolved path's last segment
   is `.ui-screenshots` or it came from `UI_SCREENSHOTS_DIR` (never `/`, the repository root or `$HOME`):
   `mkdir -p "$DIR" && find "$DIR" -mindepth 1 -delete`. If `.ui-screenshots/` isn't in the root `.gitignore`, say so
   in the report (this skill doesn't edit `.gitignore`).
3. **Run with the directory exported** — `UI_SCREENSHOTS_DIR="$DIR"` in the runner's environment for every form:
   - **Playwright** — the project's helper (e.g. `e2e/support/ui-screenshots.ts`) writes straight into it; nothing to
     collect. The accessibility results come from the JSON report (Step 3): attachments named
     `accessibility-scan-results` (the name Playwright's accessibility-testing guide attaches `@axe-core/playwright`
     results under) whose JSON body has axe's `violations[]` — count them and their `id`s; failed tests whose first
     error line names axe violations count too (the `enforce` level).
   - **Maestro** — add `-e UI_SCREENSHOTS_DIR="$DIR" -e UI_THEME=<theme>` to the `maestro test` command (the
     project's `.maestro/subflows/ui-capture.yaml` builds the file name; gallery flows in `.maestro/ui/` iterate
     directions and themes themselves through the gallery link). Record the device's current appearance first
     (`xcrun simctl ui <udid> appearance` / `adb -s <serial> shell cmd uimode night`), run the suite with the device in
     the first theme of `themes`, then — only when flows tagged `ui-flow` exist — switch the device to each further
     light/dark theme (`xcrun simctl ui <udid> appearance dark` / `adb -s <serial> shell cmd uimode night yes`) and
     re-run `maestro test --include-tags=ui-flow` with that `UI_THEME`; restore the recorded appearance afterwards on
     every exit path. `high-contrast` is never switched here (a test-level variant).
   - **Detox** — the project's `e2e/support/ui-screenshots.ts` copies `device.takeScreenshot()` output into `$DIR`.
4. **Collect strays** into the contract layout: PNGs whose path ends in
   `<component|screen|flow>/<Id>/<name>.png` but landed elsewhere — Maestro's `--test-output-dir`/
   `~/.maestro/tests/<latest run>/` or the app directory (a flow run without `-e UI_SCREENSHOTS_DIR` writes
   `undefined/<kind>/…` relative to it), Detox's `artifacts/` — are moved under `$DIR/<kind>/<Id>/`. Never touch
   Playwright's own `test-results/`/`playwright-report/` (pixel baselines and traces stay in the tool's location).
5. **Validate every file name** under `$DIR` against
   `^(component|screen|flow)/[A-Za-z0-9][A-Za-z0-9-]*/[a-z0-9]+(-[a-z0-9]+)*--(ltr|rtl)--(light|dark|high-contrast|no-color)(--[a-z0-9]+(-[a-z0-9]+)*)*\.(png|txt)$`
   (relative path). Report each malformed path (first 10, then a count), plus captures whose direction/theme is
   outside `.ui-quality.yml`'s lists (kept, listed as "outside the configured matrix").
6. **Accessibility on React Native** — Maestro/Detox have no audit, so the automated check is the
   `eslint-plugin-react-native-a11y` lint: when the plugin is in `devDependencies` and `accessibility` isn't `off`,
   run `npx eslint . --format json --output-file <scratch>.json` (respecting the project's ignores) and count the
   messages whose `ruleId` starts with `react-native-a11y/` (count + rule ids only). State that native audits
   (contrast, touch-target size) are not automated on React Native.
7. **Apply the level** — `enforce`: any violation makes the UI run **failed** (named as an accessibility failure,
   separate from assertion failures); `report`: violations listed, the run's pass/fail unaffected; `off`: say the
   automated check is off and don't count.

**Accessibility level `none`/`off`.** If `.ui-quality.yml` says `accessibility: none` (opted out entirely) or `off` (rules apply, checks not run), accessibility checks are not applicable: skip them and report "accessibility: opted out" (`none`) or "accessibility checks off" (`off`) - never "unverified" and never a failure.

## Step 3 — Run with a JSON reporter and parse it

Always keep the runner's normal console reporter for a human glancing at the log, and add a JSON reporter written
to a scratch file so this skill parses structured results instead of screen-scraping. Flag names and output shape
differ per runner — do not mix them up:

| Runner | Command | JSON flag(s) |
|---|---|---|
| Vitest | `npx vitest run [scope] --reporter=default --reporter=json --outputFile=<path>.json` | `--reporter=json` (repeatable, camelCase `--outputFile`) |
| Jest | `npx jest [scope] --json --outputFile=<path>.json` | `--json` is a boolean flag, not a reporter name; `--outputFile` still writes the file while the normal console summary still prints |
| `ng test` | `npx ng test --watch=false [scope] --reporters=default --reporters=json --output-file=<path>.json` | `--reporters` (plural, repeatable) and kebab-case `--output-file`; verified both `default,json` and `json,default` orderings keep the console summary *and* write the file (the `--help` text warns `--output-file` "applies only to the first reporter", but in practice the JSON reporter still received the file regardless of its position in the two orderings tried) |

The NestJS e2e configs take the same Vitest/Jest flags (append `--config <e2e-config>`). Verified locally against a
fresh `@nestjs/cli` 12 ESM scaffold (Vitest 4.1) in `$TMPDIR/iru-verify/typescript/nest-next/nest-esm`: the
default `vitest run` with the JSON reporter picked up only `src/app.controller.spec.ts`, and `vitest run --config
./vitest.config.e2e.ts` with the same flags picked up only `test/app.e2e-spec.ts`, both writing the shape below.
A Next.js 16.4 scaffold with a `vitest.config.mts` (`@vitejs/plugin-react`, `jsdom`, `resolve.tsconfigPaths`)
wrote the same shape too, resolving the `@/*` alias without a plugin. React Native, both toolchains, verified in
`$TMPDIR/iru-verify/react-native/{BareApp,expo-app}`: `npx jest --json --outputFile=<path>.json` on a fresh React
Native CLI 20.2 / React Native 0.87.1 app (Jest 29.7, `@react-native/jest-preset`) and on a `create-expo-app`
blank-typescript SDK 57 app with `jest-expo` 57 + `@testing-library/react-native` 14.1 wrote the same shape.

**Playwright** (Next.js and web-ui `e2e`/`ui` only) has its own JSON shape: `npx playwright test [scope] --reporter=list,json` with
`PLAYWRIGHT_JSON_OUTPUT_NAME=<path>.json` set in the environment (the `json` reporter otherwise writes to stdout,
interleaved with `list`). Parse `stats.expected` (passed), `stats.unexpected` (failed), `stats.flaky`, and
`stats.skipped` for the headline counts, then walk `suites[]` recursively (suites nest under `suites[].suites[]`)
to each `specs[].tests[]` whose `status` is `"unexpected"`: its name is the enclosing suite titles plus
`specs[].title`, its message the first line of `results[-1].error.message` (strip ANSI color codes). Accessibility
results (UI runs) are in `results[].attachments[]` entries named `accessibility-scan-results` with
`contentType: "application/json"` — the JSON is inline in `body` (base64) or in the file at `path`; read only
`violations[].id` and `violations[].nodes.length`. **Unverified
locally** — the Playwright browsers weren't installed on this machine; confirm the field names against the first
real report before relying on them.

All three runners (Jest, Vitest, and `ng test`'s Vitest runner) emit a **structurally compatible** JSON shape,
confirmed by inspecting the actual files produced above:

```jsonc
{
  "numTotalTests": 2,
  "numPassedTests": 1,
  "numFailedTests": 1,
  "success": false,
  "testResults": [
    {
      "name": "test/sample.test.ts",          // absolute/relative file path
      "status": "failed",
      "assertionResults": [
        {
          "fullName": "sample passes",
          "title": "passes",
          "status": "passed",
          "failureMessages": []
        },
        {
          "fullName": "sample fails",
          "title": "fails",
          "status": "failed",
          "failureMessages": ["AssertionError: expected 2 to be 3 // Object.is equality\n    at ..."]
        }
      ]
    }
  ]
}
```

Jest's JSON additionally nests each `failureMessages` entry's structured data under `failureDetails` and carries
extra bookkeeping fields (`invocations`, `numPassingAsserts`, `location`, `retryMessages`, …); ignore anything not
listed below.

Parse:
- `numTotalTests`, `numPassedTests`, `numFailedTests` for the headline counts.
- For each `testResults[].assertionResults[]` entry with `status === "failed"`, take `fullName` (or
  `ancestorTitles.join(" > ") + " > " + title` if `fullName` is absent) as the failing test's name, and the
  **first line** of `failureMessages[0]` as its assertion message — never dump the full stack trace.
- **A suite that failed to run** (a syntax/transform error, a throwing import, a missing module) has
  `testResults[].status === "failed"` with an **empty** `assertionResults` and its error in `testResults[].message`
  — its tests are never counted, so `numTotalTests` can be `0` and `numFailedTests` `0` while the run still exits
  non-zero (Jest also sets `numRuntimeErrorTestSuites`). Report each such file as "suite `<name>` failed to run:
  <first non-blank line of `message` after the `● Test suite failed to run` header>" — never as a clean or empty
  run. Verified locally (Jest 29.7, `@react-native/jest-preset` 0.87.1): an untranspiled `node_modules` import
  produced `numTotalTests: 0` with `Jest encountered an unexpected token` in that suite's `message`.

**Maestro** (React Native `e2e`) writes JUnit XML with `--format junit --output <path>.xml`: one `<testcase>` per
flow (its `name` is the flow file/name), a `<failure>` child on failure. Count `testcase` elements for the total and
those with a `<failure>`/`<error>` child for failures; the failing flow's message is the first line of that child's
`message` attribute or text. **Detox** forwards `--json --outputFile` to Jest, so its report has the Jest shape
above. Both are **unverified locally** (no device run) — confirm the element/field names against the first real
report before relying on them.

Delete the scratch JSON output file after parsing (or write it under a `.gitignore`d/scratch location) so it
doesn't get committed as build noise.

## Step 4 — Report results

- If everything passed (or the run legitimately matched zero because the user's scope intentionally excludes
  something), state the counts briefly — don't paste the JSON or the full console log.
- If the selector matched no files at all (Step 2's non-zero-exit case for every runner), report that plainly as
  "selector `<x>` matched no test files" — not as 0 passed/0 failed, since that reads as a clean run.
- If a `-t`/`--filter` name pattern matched no individual tests within otherwise-matched files (Step 2's
  exit-0-but-all-skipped case), report that plainly too — it is easy to mistake for "all passed".
- State which suite ran (unit, or NestJS/Next.js/React Native `e2e`) whenever the project is NestJS, Next.js, or
  React Native, so "all passed" on the unit suite is never read as e2e coverage. An e2e environment failure (Step 2)
  is reported as "e2e not run/aborted: <cause>" with the first line of the error — never as N failed tests. For a
  React Native project also state the toolchain (`expo`/`metro`) and Jest preset detected, and for an `e2e` run the
  framework (Maestro/Detox and configuration), the device used, whether Metro was already running or started (and
  stopped) by this run, and whether the lock was acquired here, held by the caller, or broken as stale. A missing
  device/tool is "e2e unverified: <reason>".
- Name each React Native–specific failure by its cause (Step 1): untranspiled package (`transformIgnorePatterns`),
  native module not mocked, RNTL 14 call not awaited.
- For every UI run (Playwright, Maestro, Detox — Step 2 "UI runs"): the screenshot directory used and whether it was
  cleared (full run) or added to (scoped run); the screenshot count per kind (`component`/`screen`/`flow`) and the
  number of distinct `<Id>`s; strays collected; malformed names; captures outside the configured matrix; the
  `.ui-quality.yml` level applied (or "missing file — defaults"); accessibility violations as a count plus rule ids
  (never raw axe/ESLint output); for Maestro, the themes the device was switched through and confirmation the
  original appearance was restored. A UI run that couldn't start is "UI run unverified: <reason>".
- On failures, list each failing test's name (`fullName`) and the first line of its assertion message
  (`failureMessages[0]`'s first line) — nothing more per failure, and no noise about the passing tests.
- Do not attempt to fix failing tests or modify source/test code unless the user asks for that as a next step.
