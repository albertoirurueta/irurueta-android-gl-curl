---
name: iru-typescript-generate-all-tests
description: "/iru-typescript-generate-all-tests - for a TypeScript/npm project only, finds every source file with no unit test or under 80% line coverage (via iru-typescript-coverage) and generates or extends tests; for Playwright-wired web UIs it also generates UI tests for screens and components that have none."
model: sonnet
allowed-tools: Read Edit Write Bash(npm *) Bash(npx *) Bash(pnpm *) Bash(yarn *) Bash(git status *) Bash(git diff *) Bash(find *) Bash(grep *) Bash(ls *) Skill Agent AskUserQuestion
---

# TypeScript Generate All Tests

Bring an entire TypeScript codebase's unit test coverage up to a minimum bar (80% line coverage) in one pass:
find every source file with no test at all or with measured coverage below the bar, then write or extend tests
for each until it clears it. This skill is TypeScript/npm only — it never runs against, or falls back to, any
other language. It only adds/extends test code; it does not add license headers, TSDoc, or run a lint/code-quality
pass (those are separate skills' jobs), and it only touches production source code if a file is genuinely
untestable as written (see Step 5) — which it reports rather than silently "fixing" by redesigning the module.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 1 — Confirm this is a TypeScript/npm project

```bash
find . -maxdepth 2 -name package.json -not -path "*/node_modules/*"
```

- **None found**: this skill applies only to TypeScript/npm projects. Tell the user plainly and stop — do not
  attempt any other language's equivalent or partial exploration.
- **Found but no TypeScript toolchain** (no `typescript` dependency, no `tsconfig.json`): tell the user this
  skill is for TypeScript projects specifically and stop.
- **Found**: continue. If there are multiple `package.json` files (a monorepo/workspaces setup) and no obvious
  single target, ask (`AskUserQuestion`) which workspace to scope this run to rather than guessing.

Detect the project's **flavor** the same way as the rest of this plan's TypeScript skills — inspect
`package.json`: `@vaadin/hilla`/`hilla-spring-boot-starter` in `pom.xml` or a `src/main/frontend/` dir →
`hilla-frontend`; `expo` or `react-native` → `react-native`; `@ionic/angular` or `@ionic/react` +
`@capacitor/core` → `ionic`; `@angular/core` → `angular`; `react` + `vite` → `react`; otherwise `library`. This
drives which testing library Step 5 uses. For `react-native`, also resolve the **toolchain** with the catalog-wide
rule — `expo` in `dependencies` → **expo** (even when `android/`/`ios/` exist as prebuild output); `react-native`
without `expo` → **metro** (bare React Native CLI app) — plus the Jest **preset** (`jest-expo` for expo;
`@react-native/jest-preset`, or the legacy `react-native` preset, for metro) and the installed
`@testing-library/react-native` major (read `version` from
`node_modules/@testing-library/react-native/package.json` — reading the file rather than `require`-ing it, since
some React Native packages' `exports` maps reject `require('<pkg>/package.json')` with
`ERR_PACKAGE_PATH_NOT_EXPORTED`, verified on `@react-native/eslint-config` 0.87.1). Also detect the **test runner**
(`vitest` in devDependencies → Vitest; `jest`/`jest-expo` → Jest — always Jest for React Native; `@angular/build`
with the `unit-test` builder → `ng test`, Vitest runner underneath) and the **package manager** (`packageManager` field or lockfile: `package-lock.json` → npm,
`pnpm-lock.yaml` → pnpm, `yarn.lock` → yarn) — both needed later to invoke `iru-typescript-coverage`/
`iru-typescript-test` consistently, though those skills re-detect them independently too.

## Step 2 — Discover every candidate file and the existing test convention

Use the `Explore` agent for this — a repository of any real size has too many source files to read directly one
by one, and this step needs a structural map, not a design review.

- Identify every **main-source** file under the project's source root (typically `src/`, or
  `src/main/frontend/` for Hilla) with a `.ts`, `.tsx`, `.js`, or `.jsx` extension, excluding:
  - `node_modules/`, `dist/`, `build/`, `out/`, `coverage/`, and any other build output.
  - Type-declaration files (`*.d.ts`).
  - Barrel files that only re-export (`index.ts`/`index.tsx` containing solely `export * from`/`export {...}
    from` statements — read the file to confirm before excluding; an `index.ts` with real logic is a candidate).
  - Config files at any level (`vite.config.ts`, `vitest.config.ts`, `eslint.config.js`, `tsconfig*.json`,
    `tailwind.config.ts`, `playwright.config.ts`, `jest.config.js`, `capacitor.config.ts`, `angular.json`,
    `typedoc.json`, etc.).
  - Bootstrap/entry files with no branching logic of their own (`main.ts`, `main.tsx`, `index.html`'s script
    entry, Angular's `main.ts`/`bootstrapApplication` call, Expo's `App.tsx` when it only mounts a navigator, an
    Expo Router `_layout.tsx` that only declares `<Stack />`/`<Tabs />`, and React Native's root `index.js`/`index.ts`
    — the `AppRegistry.registerComponent`/`registerRootComponent` entry).
  - React Native native and tooling trees: `android/`, `ios/` (committed native projects for **metro**, prebuild
    output for **expo**), `.expo/`, `metro.config.js`, `babel.config.js`, `react-native.config.js`, `app.config.*`,
    and Codegen spec files (`Native*.ts` declaring a `TurboModule` interface — type-only bindings to native code,
    nothing for Jest to execute) — the same exclusions `iru-typescript-coverage` applies, so the two skills agree on
    the candidate set.
  - Generated directories, notably `src/main/frontend/generated/` (Hilla's generated endpoint clients — never
    hand-written, never tested directly) and any other `*/generated/` tree.
  - Storybook files (`*.stories.ts`/`*.stories.tsx`).
  - End-to-end specs (`e2e/**`, `*.e2e.ts`, Playwright/Maestro/Detox/Cypress specs, `.maestro/` flows) and existing unit test files
    themselves (`*.test.ts(x)`, `*.spec.ts(x)`, `test/**`, `__tests__/**`).
  - Pure type-only modules (files that export only `interface`/`type` declarations with no runtime code) and
    pure constant/config data modules with no logic. Use judgment the same way as the Java skill: note these as
    "excluded, no testable logic" in the final report rather than silently dropping them or forcing trivial
    tests onto them.
- Identify the **existing test-file convention** actually in use — do not assume one, detect it from what's on
  disk:
  - **Sibling co-located**: `foo.ts` → `foo.test.ts`/`foo.test.tsx` in the same directory (common for
    library/React/React Native projects using Vitest or Jest).
  - **Sibling `.spec` suffix**: `foo.ts` → `foo.spec.ts` in the same directory (Angular's own convention,
    typically generated by `ng generate`).
  - **Mirrored `test/` directory**: a separate `test/` tree whose structure mirrors `src/` (common for
    library-flavor npm packages, e.g. `src/utils/format.ts` → `test/utils/format.test.ts`).
  - **`__tests__/` directories**: Jest's own convention — a root `__tests__/` (the React Native CLI template ships
    `__tests__/App.test.tsx`) or a `__tests__/` folder beside the source (`src/hooks/__tests__/useFoo.test.ts`).
  - If more than one convention is present, follow whichever the majority of existing tests use; if genuinely
    mixed with no majority, ask (`AskUserQuestion`).
  - If **no test files exist at all** and no test runner is configured (`vitest`/`jest`/`@angular/build`
    unit-test builder absent from `package.json`), stop and tell the user: this skill extends an existing test
    setup's coverage, it doesn't scaffold one from nothing — ask (`AskUserQuestion`) whether tests actually live
    somewhere unconventional before concluding there really are none.
- For every candidate file, resolve whether a matching test file already exists by that convention, and its
  path if so.

Build a plain list: source file path → matching test file path (or "none").

## Step 3 — Measure real coverage for every candidate file in one pass

Delegate this to `iru-typescript-coverage` via the `iru-gate-runner` agent, so a large `lcov.info`/coverage HTML
report never lands directly in this conversation's context, and so the whole suite is only executed once
regardless of how many files are in scope:

```
Agent({
  description: "Measure coverage for every candidate file",
  subagent_type: "iru-gate-runner",
  prompt: "Invoke Skill({skill: \"iru-typescript-coverage\", args: \"<file1,file2,...>\"}) with no test
    selector, so the whole suite runs once and every file is measured in the same pass. Report back, per target
    file: its exact line coverage percentage; and, ONLY for files below 80%, the specific uncovered line
    ranges too (from coverage-summary.json / lcov.info). For files at or above 80%, report just the percentage —
    do not include per-line detail for those, to keep the report compact.",
  run_in_background: false
})
```

If the candidate list from Step 2 is very large (rough guide: more than ~50 files), split it into a few batches
of target-file arguments passed in the same `iru-typescript-coverage` call structure above so no single
`iru-gate-runner` report becomes unwieldy — this still only requires one full-suite run per batch, not one per
file.

If a file has no matching test file (Step 2) it will simply show 0% (or be entirely absent from the coverage
summary) — treat "absent from the report" the same as "0%, no coverage," per `iru-typescript-coverage`'s own
reporting convention.

## Step 4 — Classify

From Step 3's results, split every candidate file into:

- **Missing tests**: no matching test file (Step 2), regardless of reported percentage.
- **Below bar**: a matching test file exists, but line coverage is under 80%.
- **Already sufficient**: line coverage is 80% or above — skip these entirely, count them for the final report.

## Step 5 — Generate or extend tests, one file at a time

For every file in the "missing tests" or "below bar" buckets, write (or extend) its unit tests. Since each
file's test is independent of every other's, dispatch one sub-agent per file **concurrently** (in batches of a
manageable size — a handful at a time rather than dozens at once — if the bucket is large):

```
Agent({
  description: "Write/extend tests for <fileName>",
  prompt: "Read <source-file-path> in full — this is the module to cover. <If a test file already exists at
    <test-file-path>: read it too; these lines are currently uncovered: <line ranges from Step 3> — extend this
    file with tests that exercise them.> <If no test file exists: create one at <path implied by this project's
    naming/directory convention from Step 2>.> Use the project's detected flavor's testing library: plain
    TypeScript/Node logic → Vitest or Jest (whichever this repository already uses) with `vi.fn`/`jest.fn` for
    mocks and `vi.mock`/`jest.mock` for module mocking; React components → `@testing-library/react` +
    `@testing-library/user-event`, jsdom environment, query by role/label/text (not test-id first); React
    Native components and hooks → `@testing-library/react-native` (RNTL) under the project's own preset — <React
    Native rules from Step 5's list below, filled in for this project's toolchain, preset, and RNTL major>; Angular
    components/services → `TestBed`, `HttpTestingController` + `provideHttpClientTesting` for anything calling
    `HttpClient`, run through `ng test --include=<path>` for this file alone; Ionic components → the same
    library as the underlying framework (Angular TestBed or React Testing Library, per Step 1's `framework`
    detection); Hilla frontend views/components → Vitest in browser mode per Vaadin's own Vitest browser-mode
    config, never hand-testing the generated endpoint clients under `generated/` (treat them as a trusted
    external dependency to mock, not code to cover). Match this project's existing test style exactly:
    describe/it vs. test(), same assertion library already in use. Cover the file's actual behavior, including
    edge cases (invalid-prop/invalid-argument handling implied by its own validation logic, boundary conditions,
    conditional branches, error paths) — not superficial calls that merely execute lines without asserting real
    behavior. Never write a snapshot-only test (a bare `toMatchSnapshot()` with no other assertions) — snapshots
    may supplement real assertions but must not be the only coverage a test provides. Do not modify the module
    under test unless it is genuinely untestable as written (e.g. a hard dependency on a non-injectable
    singleton or global with no seam to substitute it) — if so, make the minimal change needed to add a seam
    (e.g. accepting a dependency as a parameter, extracting a hook/service) and say so explicitly rather than
    reporting it as a plain test addition. Report back: the test file touched (new or extended), what was
    added, and whether the module under test needed a minimal testability change.",
  run_in_background: false
})
```

**React Native rules** (substituted into the prompt above for a `react-native` project — both toolchains unless
marked):

- **RNTL major matters.** `@testing-library/react-native` **14** made `render`, `fireEvent.*`, `renderHook`, and
  `rerender` return promises — write `await render(<Screen />)`, `await fireEvent.press(button)`; a bare
  `render(...)` fails the next `screen.*` query with `` `render` function has not been called `` (verified locally on
  14.1.0 under `jest-expo` 57). On 13.x keep the synchronous forms the existing tests use. Prefer
  `userEvent.setup()` + `await user.press(...)` for interactions, and `screen.getByRole`/`getByLabelText`/
  `getByText` queries over `getByTestId`.
- **No RNTL installed** (the React Native CLI template only ships `react-test-renderer` and a smoke test calling
  `ReactTestRenderer.create(<App />)` inside `act`): don't silently add a dependency — ask (`AskUserQuestion`)
  whether to add `@testing-library/react-native` as a devDependency; if declined, follow the existing
  `react-test-renderer` style and say in the report that `react-test-renderer` is deprecated for React 19.
- **expo** (`jest-expo` preset): Expo SDK modules (`expo-constants`, `expo-font`, `expo-linking`, …) are already
  mocked by the preset — don't re-mock them; for Expo Router screens use `renderRouter` from
  `expo-router/testing-library` with an in-memory route map instead of mounting the real `app/` tree, and assert with
  its `toHavePathname`/`toHaveSegments` matchers.
- **metro** (`@react-native/jest-preset`): nothing beyond React Native core is mocked. A third-party native module
  (AsyncStorage, MMKV, `react-native-safe-area-context`, Reanimated, …) needs its library-provided Jest mock
  registered via `jest.mock('<pkg>', () => require('<pkg>/jest/<mock>'))` in the test or the project's
  `setupFiles`; a React Navigation screen renders inside a `NavigationContainer` (or receives mocked
  `navigation`/`route` props for a pure unit test).
- **Never exercise native code from Jest**: a Turbo Module/Expo Module spec is mocked at the `TurboModuleRegistry`
  /`requireNativeModule` boundary (`jest.mock('./specs/NativeFoo', () => ({ __esModule: true, default: { … } }))`) and
  the test covers the TypeScript wrapper's behaviour around it — the Kotlin/Swift/Obj-C side belongs to the
  Android/iOS test suites, not here.
- Animations and timers: `jest.useFakeTimers()` + `act(() => jest.runAllTimers())` (awaited on RNTL 14) instead of
  real waits; platform branches (`Platform.OS`, `*.ios.tsx`/`*.android.tsx`) are covered by setting `Platform.OS`
  in the test or importing the platform file directly, and named as such if one branch stays uncovered.
- If a new test fails with `Jest encountered an unexpected token` from `node_modules/<pkg>`, the package needs to
  join the preset's `transformIgnorePatterns` allow-list — that's a Jest-config change, not test code: report it and
  ask before editing the config.

Collect every sub-agent's report before moving on.

## Step 6 — Re-measure and iterate on shortfalls

Re-run Step 3's delegated `iru-typescript-coverage` pass, scoped to just the files touched in Step 5, to confirm
each now meets 80%. For any file still below the bar:

- Identify the specific lines still uncovered (already returned by this re-measurement).
- Dispatch one more Step 5-style agent for just that file, focused on the remaining uncovered lines.
- Re-measure once more.

Cap this at two extension attempts per file. A file still below 80% after that is a genuine residual gap —
report it as such (Step 8), with the exact lines still uncovered, rather than looping indefinitely. Common
legitimate reasons: defensive code paths that are difficult or unsafe to trigger from a unit test (a caught
"impossible" error, a platform-specific branch that only runs on a device this environment can't emulate), or
framework glue code (e.g. an Angular `NgModule`-free bootstrap file, a Hilla generated-client wrapper) that
carries no real logic to assert against. Name these explicitly if that's what remains uncovered.

## Step 6b — UI tests for existing screens and components (web UI projects)

Only for a `react`/`angular`/`ionic`/`nextjs`/`hilla-frontend` project whose Playwright setup exists
(`@playwright/test` + `playwright.config.*`; for Hilla, in the web module). React Native UI tests belong to
`iru-react-native-code-one-task`'s guidance — skip them here and say so. No Playwright at all → skip this step and
report "UI tests not generated (no Playwright setup — run the scaffold skill or add it)"; never scaffold Playwright
here.

1. **Inventory** the screens (route components/pages/views) and components (exported presentational components)
   and the UI tests that already cover them: a story file next to the component (`*.story.tsx`/`*.story.ts`, Hilla
   under `src/main/frontend/stories/`), a spec that captures `<kind>/<Id>/…` through the helper, or existing
   captures under `.ui-screenshots/<kind>/<Id>/`. Anything without one is a gap. Skip generated code
   (`generated/`), barrels, providers and pure layout shells with no visual state of their own.
2. **Helper and gallery**: if the project's Playwright test directory has no `support/ui-screenshots.ts`, create it
   from the UI contract's shared helper — the copy in `iru-typescript-code-one-task/reference/ui-screenshots.ts`
   (or `iru-nextjs-code-one-task/reference/ui-screenshots.ts`) when that skill is installed — and add
   `@axe-core/playwright` as a devDependency only after asking (`AskUserQuestion`), like any new dependency. If
   there is no `components` project/gallery, ask whether to add one following that skill's
   `reference/ui-implementation.md`; if declined, cover components through the screens that render them.
3. **Generate per gap**, one sub-agent per component/screen (batched like Step 5), each told to follow
   `reference/ui-contract.md` §4–§8 and the framework's section of `reference/ui-implementation.md` from the coding
   skill: decide the component's visual states from its props/inputs and code (`default`, `loading`, `empty`,
   `error`, `disabled`, `selected`, `long-content`, `many-items`, … — only those that apply), write one story per
   state with fixture data (minimal, typical, many-items/long-content, right-to-left Arabic or Hebrew text), then a
   spec that asserts behaviour once (events fire with the right payload, disabled states don't) and calls
   `captureUiMatrix` per state with `accessibility: { testInfo, include: '#root' }`; screens get a screen spec
   against the built app with network faked. Never change the component to make a test pass — an accessibility
   violation found here is reported, not silently fixed, unless it is a one-line label/alt fix the user approves.
4. **Run them once** through `iru-gate-runner`: `Agent({description: "Run generated UI tests", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-typescript-test\", args: \"ui <the generated spec
   files>\"}) with CI=1. Report back only pass/fail per spec with the first error line, environment failures
   (browser missing, web server not starting, port in use) separately, the screenshot count per kind, malformed
   names, accessibility violations as a count plus rule ids, and the .ui-quality.yml level applied.",
   run_in_background: false})`. Fix generated-test mistakes and re-run (two attempts per spec, as in Step 6); an
   environment failure makes the UI tests **unverified** with the reason — never passed.

**Accessibility level `none`/`off`.** If `.ui-quality.yml` says `accessibility: none` (opted out entirely) or `off` (rules apply, checks not run), accessibility checks are not applicable: skip them and report "accessibility: opted out" (`none`) or "accessibility checks off" (`off`) - never "unverified" and never a failure.

## Step 7 — Run the full suite once to confirm no regressions

Delegate to `iru-gate-runner`: `Agent({description: "Run full test suite", subagent_type: "iru-gate-runner", prompt:
"Invoke Skill({skill: \"iru-typescript-test\"}) (fall back to `npm test`/`vitest run`/`jest`/`ng test
--watch=false` directly if unavailable). If everything passed, report back only that all tests passed. If
anything failed, report back only the failing test names, the failure reason, and the stack trace for each."})`.
Fix any regression this run's new/extended tests introduced (a genuine bug in a generated test, not a
pre-existing failure), then re-invoke until it reports all tests passed. A pre-existing failure unrelated to any
file this run touched is out of scope — note it in Step 8 rather than fixing it.

## Step 8 — Report

Summarize:

- Total candidate files found (Step 2), and how many were excluded as having no testable logic, with names.
- The detected flavor, test runner, package manager, and test-file convention this run followed.
- How many were already at or above 80% and left untouched.
- For each file in the "missing tests"/"below bar" buckets: before/after line coverage, and whether its test
  file was newly created or extended.
- Any file that required a minimal testability change to the module under test (Step 5), named explicitly.
- Any file still below 80% after Step 6's two attempts, with the exact lines still uncovered and, where
  apparent, why (e.g. an untestable defensive branch or generated-code wrapper).
- The full-suite result from Step 7.
- For a web UI project (Step 6b): screens and components found, those already covered, UI tests generated per
  component/screen with the states covered, the screenshot count per kind, accessibility violations (rule ids) at
  the `.ui-quality.yml` level applied, and anything left without UI tests — or why Step 6b was skipped/unverified.

Do not run a code-quality, license-header, or TSDoc pass — those are `iru-typescript-code-quality`,
`iru-check-license`, and `iru-typescript-tsdoc`'s jobs respectively, not this skill's.
