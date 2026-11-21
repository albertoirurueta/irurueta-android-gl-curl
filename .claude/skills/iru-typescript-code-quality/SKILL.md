---
name: iru-typescript-code-quality
description: "/iru-typescript-code-quality [path-or-glob] - runs the project's ESLint (or Oxlint/Biome), tsc --noEmit, Prettier --check and Stylelint on a TypeScript project, optionally scoped to a glob, and reports issues per tool and rule id with an accessibility subtotal; with a baseline, only the new-vs-baseline diff."
model: haiku
---

# TypeScript Code Quality

Run ESLint, `tsc --noEmit`, Prettier, and (when configured) Stylelint, Oxlint, and Biome directly — whichever of
them the project actually lints with, which for NestJS 12 means Oxlint + Prettier and for Next.js means ESLint or
Biome plus `next typegen` before `tsc` — and report every issue they find, grouped by tool and by rule id. This skill only runs static analysis/formatting checks and reports
results — it does not fix issues unless asked to as a follow-up.

## Step 1 — Detect the toolchain, verify it is actually wired, then run each tool

**Framework detection** (also used by `iru-typescript-test`/`-coverage`/`-tsdoc`/`-code-one-task*`, kept
consistent across all of them): inspect `package.json` — `@vaadin/hilla`/`hilla-spring-boot-starter` in a
Maven `pom.xml` or a `src/main/frontend/` directory → `hilla-frontend`; `expo` or `react-native` in dependencies
→ `react-native`; `@ionic/angular` or `@ionic/react` + `@capacitor/core` → `ionic`; `@nestjs/core` → `nestjs`;
`next` → `nextjs`; `@angular/core` → `angular`; `react` + `vite` → `react`; otherwise → `library`. This decides
which rule-id prefixes matter (Step 3) and, for Angular, NestJS, Next.js, and React Native, how lint and
type-checking are invoked (below). For `react-native`, also resolve the **toolchain** with the rule every React
Native skill in this catalog shares: `expo` in `dependencies` → **expo** (even when `android/`/`ios/` exist as
`npx expo prebuild` output); `react-native` without `expo` → **metro** (bare React Native CLI app; Expo also
bundles with Metro, the name only distinguishes the two toolchains).

**What "the project's linter" is, per framework** — read the `lint` script in `package.json` and run what it runs
(with this skill's JSON/report flags added), rather than assuming ESLint:

- **NestJS 12** scaffolds `"lint": "oxlint --type-aware src/ test/"` with a `.oxlintrc.json`, `oxlint` +
  `oxlint-tsgolint` devDependencies (the latter is what `--type-aware` needs), and Prettier (`.prettierrc`,
  `"format": "prettier --write \"src/**/*.ts\" \"test/**/*.ts\""`) — no ESLint at all (verified on a fresh
  `@nestjs/cli` 12 scaffold). There, **Oxlint is the primary linter**, not the bonus check described for Vite
  scaffolds below. Older Nest projects (pre-v12, and CommonJS ones migrated forward) use ESLint
  (`eslint.config.mjs` with `typescript-eslint`, or a legacy `.eslintrc.js`) + Prettier — handle them like any
  other ESLint project.
- **Next.js 16** removed `next lint` (and `next build` no longer lints): `"lint": "eslint"` with an
  `eslint.config.mjs` flat config spreading `eslint-config-next/core-web-vitals` and `eslint-config-next/typescript`
  plus `globalIgnores([".next/**", "out/**", "build/**", "next-env.d.ts"])`. Run `npx eslint` directly. Verified
  locally on Next 16.4: `npx next lint` fails with `Invalid project directory provided, no such directory:
  <project>/lint` (the CLI treats `lint` as a `next dev` directory) — never run it on 16+. **Next.js 15 or older**
  may still have `"lint": "next lint"` with a legacy `.eslintrc.json` extending `next/core-web-vitals`; there
  prefer `npx eslint` when a flat config exists, and otherwise `npx next lint --format json --output-file
  eslint-report.json` (same ESLint JSON shape; **unverified locally** — only Next 16 was scaffolded). A project
  created with `create-next-app --biome` has `@biomejs/biome`, a `biome.json`, and `"lint": "biome check"`
  instead of ESLint — see Biome below.
- **Type-checking a Next.js project** needs the generated route types first: App Router files use the global
  `PageProps<'/route'>`, `LayoutProps<'/route'>`, and `RouteContext<'/route'>` helpers, which only exist once
  `next dev`, `next build`, or `next typegen` has written them under `.next/types` (included via `tsconfig.json`'s
  `include`). Run `npx next typegen` before `tsc` (Step 1.2) — verified locally: on a fresh Next 16.4 scaffold
  with `.next/types` removed, `tsc --noEmit` failed with `src/app/layout.tsx(20,50): error TS2304: Cannot find
  name 'LayoutProps'.`, and after `next typegen` the same command exited `0`. A `TS2304` for those names is a
  missing-typegen artifact, never a real finding. `next typegen` exists from Next 15.5; on older versions there
  are no such helper types and plain `tsc --noEmit` is correct.
- **React Native, `metro` toolchain** (bare React Native CLI app) — verified on a fresh React Native CLI 20.2 /
  React Native 0.87.1 TypeScript template: `"lint": "eslint ."`, ESLint **8.57** with a **legacy `.eslintrc.js`**
  (`module.exports = { root: true, extends: '@react-native' };`, i.e. `@react-native/eslint-config`), Prettier
  **2.8.8** with `.prettierrc.js`, and a `tsconfig.json` extending `@react-native/typescript-config` (which sets
  `"types": ["jest"]`). Run `npx eslint --format json --no-error-on-unmatched-pattern -o eslint-report.json .`
  exactly as item 1 below — ESLint 8 picks up `.eslintrc.js` on its own (verified: it linted `App.tsx`,
  `__tests__/App.test.tsx`, `index.js`, and the root config files, exit `0`). Projects that migrated to ESLint 9
  use `@react-native/eslint-config/flat` (the package's `exports` map has `"./flat"`) from an `eslint.config.js`;
  handle them like any flat-config project, but verified locally that ESLint 9.39 with that flat export (0.87.1)
  **crashes** on `.js` files — `TypeError: Error while loading rule 'ft-flow/define-flow-type':
  context.getAllComments is not a function` (an `eslint-plugin-ft-flow` incompatibility), exit `2` — report that as
  "ESLint crashed: plugin incompatible with ESLint 9", never as zero issues or as a lint finding. The native
  `android/` and `ios/` projects contain no lintable TypeScript; if ESLint ever reports files there, they're
  Gradle/CocoaPods helper scripts, not app code.
- **React Native, `expo` toolchain** — the blank `create-expo-app` template ships **no** linter, formatter, or lint
  script at all; `npx expo lint` (verified, Expo SDK 57) installs `eslint` 9 + `eslint-config-expo`, writes an
  `eslint.config.js` (`require("eslint-config-expo/flat")` inside `defineConfig([...])`, `ignores: ["dist/*"]`) and
  adds `"lint": "expo lint"`. **Don't run `expo lint` itself for this skill**: per `npx expo lint --help` it lints
  only `src/`, `app/`, and `components/` by default, so a root `App.tsx` (the blank template's only component) is
  silently skipped — run plain `npx eslint --format json --no-error-on-unmatched-pattern -o eslint-report.json .`
  instead (verified: it linted `App.tsx`, `index.ts`, and `eslint.config.js`, and an unresolved import surfaced as
  `import/no-unresolved`). Older Expo projects (SDK ≤ 52) may still have a legacy `.eslintrc.js` with
  `extends: 'expo'` — the same `npx eslint` command handles it. No `eslint.config.*`/`.eslintrc*` → "ESLint: not
  configured, skipped" (and say `npx expo lint` would set it up).
- **Type-checking a React Native project** is plain `npx tsc --noEmit -p tsconfig.json --pretty false` for both
  toolchains (Expo's `tsconfig.json` extends `expo/tsconfig.base`, which already sets `noEmit`). One verified trap
  for **expo**: TypeScript 6 changed the default of `compilerOptions.types` to `[]`, and `expo/tsconfig.base` doesn't
  set it, so once Jest tests exist `tsc` reports `error TS2593: Cannot find name 'test'` / `TS2304: Cannot find name
  'expect'` in every test file even with `@types/jest` installed (verified on TypeScript 6.0.3); adding
  `"types": ["jest"]` to the app's `tsconfig.json` cleared them. Report those as one "tsconfig `types` lacks `jest`"
  configuration finding rather than N separate type errors. The metro template's `@react-native/typescript-config`
  already sets `"types": ["jest"]`.
- **Prettier on React Native** — always pass an explicit source glob, `npx prettier --list-different
  "**/*.{js,jsx,ts,tsx}"`, rather than `.`: the metro template has a `.prettierrc.js` but no `.prettierignore`, so
  `prettier --list-different .` flagged `ios/<App>/Images.xcassets/**/Contents.json` and `package.json` on the
  untouched scaffold (verified, exit `1`), while the scoped glob exited `0` (Prettier skips `node_modules/` on its
  own). The blank Expo template has no Prettier at all — "Prettier: not configured" unless the project added it.

**Package manager**: from `packageManager` in `package.json`, else the lockfile present —
`package-lock.json` → npm, `pnpm-lock.yaml` → pnpm, `yarn.lock` → yarn. Run every command below through it:
npm → `npx <tool> ...`; pnpm → `pnpm exec <tool> ...`; yarn (classic or Berry) → `yarn <tool> ...` — all three
resolve the project's own locally-installed binary, which matters in a pnpm/Yarn PnP workspace where a bare
`npx`/global install may not see it. Examples below use the npm form; substitute per the detected manager.

**Verify each tool is actually wired before trusting a zero result from it** — the whole point of this step is
that "no issues found" must mean the tool ran against real rules, not that it had nothing to check:

- **ESLint**: an `eslint.config.{js,mjs,cjs,ts}` (flat config, ESLint ≥ 9) at the project root, OR — Angular
  only — an `angular.json` `architect.<project>.lint` target (the `@angular-eslint/builder` or
  `@angular/build:lint` builder). A legacy `.eslintrc*` means an ESLint 8-era project; the commands below still
  work (flat-config-only flags like `--no-error-on-unmatched-pattern` do too — they aren't flat-config-specific)
  but confirm the project hasn't already migrated before assuming a config shape.
- **tsc**: `tsconfig.json` must exist. If it is a **solution-style** file (a bare `"files": []` plus
  `"references": [...]`, common in Vite/Angular scaffolds that split `tsconfig.app.json`/`tsconfig.spec.json`/
  `tsconfig.node.json`), running `tsc --noEmit -p tsconfig.json` directly is a trap: it reports **zero errors
  unconditionally** because there is nothing in `files` for it to check and project references aren't followed
  in plain `--noEmit` mode — verified locally: a solution `tsconfig.json` with a type error in the referenced
  project's source exits `0` with no output under plain `-p`, but the same run under `tsc -b --noEmit` (or
  `tsc --noEmit -p tsconfig.app.json` against the concrete project file directly) reports it. Detect this shape
  (`"files": []` with no `"include"`, plus `"references"`) and use `tsc -b --noEmit -p tsconfig.json` in that
  case, or enumerate and run `--noEmit` against each referenced project file when `-b` isn't desired (e.g. to
  scope to just one project).
- **Prettier**: a `.prettierrc*`/`prettier.config.*` file, or a `"prettier"` key in `package.json`. Without one,
  Prettier still runs (it has built-in defaults) but "no issues" would just mean nothing violates the defaults,
  not that the project's own style is enforced — report this distinction if no config is found rather than
  treating the run as equivalent to a configured one.
- **Stylelint**: only relevant, and only run, when the project has `.css`/`.scss` files *and* a
  `stylelint.config.*`/`.stylelintrc*` file or a `"stylelint"` key in `package.json` exists. No config → skip it
  and say so explicitly; don't report a clean Stylelint pass for a project that never wired it.
- **Oxlint**: only run when `oxlint` is a `package.json` dependency, or an `.oxlintrc.json`/`oxlintrc.json`
  exists (some Vite/React scaffolds ship it as a fast pre-check alongside, not instead of, ESLint — see
  `iru-setup-react-web`). Skip silently otherwise; in those projects Oxlint is a bonus signal, not a substitute for
  a missing ESLint config. The exception is a project whose `lint` script *is* Oxlint (NestJS 12, above) — there a
  missing ESLint config is expected and must not be reported as "ESLint: not configured"; say "ESLint: not used
  (project lints with Oxlint)" instead.
- **Biome**: only run when `@biomejs/biome` is a dependency and a `biome.json`/`biome.jsonc` exists (e.g. a
  `create-next-app --biome` project). Biome is both linter and formatter — when it's the project's tool, it
  replaces ESLint *and* Prettier, so don't report those two as "not configured" findings.

If a tool's config is missing, **skip running it and say so in the report** ("ESLint: not configured, skipped")
instead of silently omitting it or reporting a misleading "0 issues".

**Run each configured tool, scoped to `[scope]` when given** (a file, directory, or glob):

1. **ESLint** —
   `npx eslint --format json --no-error-on-unmatched-pattern -o eslint-report.json [scope or default dirs]`.
   Verified locally against ESLint 10 (flat config): stdout carries the JSON array directly (also confirmed via
   `-o <file>`, which writes the identical JSON to disk instead — prefer `-o` so nothing else on stdout/stderr
   gets mixed in), one entry per linted file:
   `[{"filePath":"...","messages":[{"ruleId":"@typescript-eslint/no-unused-vars","severity":2,"message":"...",
   "line":2,"column":7,...}],"errorCount":4,"warningCount":0,...}]` — a file with `"messages":[]` was linted
   clean, it is still listed. `severity` is `1` (warn) or `2` (error). Exit code is `1` when any error-severity
   message exists (`0` if only warnings or none); a glob matching no files exits `2` with an "Oops!" message
   instead of JSON unless `--no-error-on-unmatched-pattern` is passed (verified: with the flag, a no-match run
   prints `[]` and exits `0` — always pass it so an empty scope reads as "nothing to check", not a crash).
   `--max-warnings <n>` fails the run once warning-severity messages exceed `<n>` (errors always fail regardless
   of this flag) — pass `--max-warnings 0` only if the caller wants warnings treated as failures too; otherwise
   omit it and just report warning counts separately from error counts.
   - **Angular**: prefer `npx ng lint --format json` (or `ng lint` if the Angular CLI is installed globally) over
     a direct `eslint` invocation when the `lint` architect target exists — it resolves the per-project ESLint
     config(s) and any Angular-specific include/exclude Angular's own schematic set up, which a bare `eslint .`
     may not match exactly in a multi-project workspace. `ng lint`'s `--format json` output is the same ESLint
     JSON array shape above (`ng lint` wraps `@angular-eslint/builder`, which wraps ESLint) — **unverified
     locally** (`ng`/Angular CLI isn't installed on this machine; only confirm this against a real Angular
     scaffold, e.g. right after `iru-setup-angular-web` runs).
   - **Oxlint**, when configured: `npx oxlint --format json [scope]` — add `--type-aware` when the project's own
     `lint` script uses it (NestJS 12: `npx oxlint --type-aware --format json src/ test/`, or the `[scope]` in place
     of `src/ test/`), since type-aware rules such as `typescript(no-floating-promises)` only fire with it. Verified locally: output is a single JSON
     **object** (not an array like ESLint) — `{"diagnostics":[{"message":"...","code":"eslint(no-unused-vars)",
     "severity":"warning","filename":"src/index.ts","labels":[{"span":{"line":2,"column":7,...}}]}],
     "number_of_files":1,...}` — `code` is `<plugin>(<rule-name>)`, no `@typescript-eslint/` prefix: `eslint(...)`
     for core rules, and for TypeScript rules `typescript-eslint(...)` on older Oxlint or `typescript(...)` on
     current releases (verified on Oxlint 1.88 in the NestJS 12 scaffold: `typescript(no-floating-promises)` at
     `"severity":"error"` alongside `eslint(no-unused-vars)` at `"warning"`; other plugins appear as `unicorn(...)`,
     `import(...)`, `oxc(...)`, `jest(...)`/`vitest(...)`). Exit code is `0` with only warning-severity
     diagnostics by default and `1` once any error-severity one exists (verified); pass `--deny-warnings` to make
     warnings fail the run too (verified: exit `1` then). Report Oxlint's findings as a
     separate line item from ESLint's — they're two different linters even when scoped to the same files, and a
     project running both typically uses Oxlint as a fast subset check, not a replacement.
2. **tsc** — `npx tsc --noEmit -p tsconfig.json --pretty false` (or the solution-file handling above). Verified
   locally: output is plain lines on stdout, one per diagnostic, `<file>(<line>,<col>): error TS<code>: <message>`
   e.g. `src/broken.ts(2,3): error TS2322: Type 'string' is not assignable to type 'number'.` — `--pretty false`
   is what keeps this to one line per diagnostic instead of the default multi-line colored/source-snippet format,
   which is much harder to parse reliably. Exit code is non-zero (`1` or `2` depending on invocation form — don't
   rely on the exact number, treat any non-zero exit as "errors were found" and any diagnostic line as one issue)
   when errors exist, `0` when clean. `tsc` has no glob/path argument to scope a single run to a subset of
   files under a project — it always type-checks the whole project graph `tsconfig.json` resolves; when `[scope]`
   is given, run the full command unscoped and then filter the reported lines to ones whose `<file>` prefix
   matches `[scope]`, the same "run whole, report scoped" pattern `iru-java-code-quality` uses for Checkstyle/PMD.
3. **Prettier** — `npx prettier --list-different [scope or "."]` (React Native: `[scope or
   "**/*.{js,jsx,ts,tsx}"]`, see above — never `.`, which reaches into `ios/`/`android/`). Verified locally: with `--check` (the more
   commonly documented flag), the human-readable progress line (`Checking formatting...`) goes to **stdout** but
   the actual `[warn] <file>` lines and the summary (`Code style issues found in the above file. Run Prettier
   with --write to fix.`) go to **stderr** — easy to lose if only stdout is captured. `--list-different` avoids
   that split: it prints just the relative paths of unformatted files, one per line, on stdout, with nothing else
   — prefer it. Exit code `1` when any file needs reformatting, `0` when all match, `2` when the glob matches no
   files at all (no equivalent of ESLint's `--no-error-on-unmatched-pattern` — verified, so treat exit `2` here
   as "nothing to check" rather than a real failure only after confirming the message says "No files matching").
   Prettier has no rule ids — every finding is filed under a single `formatting` bucket in Step 3.
4. **Next.js type-check** replaces item 2's plain `tsc` call: `npx next typegen && npx tsc --noEmit --pretty false`
   (same output format and parsing as item 2). If `next typegen` itself fails (e.g. an invalid route segment),
   report that as a `next typegen` failure and don't run `tsc` — its results would be meaningless without the
   generated types.
5. **Biome**, only when configured (Step 1) — `npx biome check --reporter=json [scope or "."] > biome-report.json`.
   Verified locally against Biome 2.5: JSON goes to stdout (a note that the `json` reporter "is experimental and may
   change in patch releases" goes to stderr — capture stdout only), as one object:
   `{"summary":{"errors":2,"warnings":3,...},"diagnostics":[{"severity":"warning","message":"Unexpected any. ...",
   "category":"lint/suspicious/noExplicitAny","location":{"path":"src/a.ts","start":{"line":1,"column":22},...}}]}`.
   Formatting problems appear as diagnostics with `"category":"format"` (one per unformatted file, no line), so
   `biome check` covers what Prettier covers in other projects. Exit code `1` when any error-severity diagnostic
   exists. Because the reporter is marked experimental, re-check the field names against the first real report.
6. **Stylelint**, only when configured (Step 1) and CSS/SCSS files are in scope —
   `npx stylelint "**/*.{css,scss}" --formatter json -o stylelint-report.json` (scope the glob to `[scope]`
   when given, e.g. `"<scope>/**/*.{css,scss}"`). Verified locally: the JSON formatter writes to **stderr** by
   default even though `--format json` writes it as a genuine JSON array — always pass `-o <file>` rather than
   trying to capture stdout, exactly like ESLint above. Shape:
   `[{"source":"...","errored":true,"warnings":[{"line":2,"column":10,"rule":"color-hex-length",
   "severity":"error","text":"Expected \"#FFFFFF\" to be \"#FFF\" (color-hex-length)"}]}]`. Exit code `2` when any
   warning has `severity: "error"`, `0` when clean.

If any tool's underlying build/compile step fails outright for a reason unrelated to lint/type rules (e.g. a
missing dependency, a syntax error severe enough that ESLint can't parse the file), report that and stop for that
tool — its report wasn't meaningfully generated, so don't read a stale or empty one.

## Step 2 — Read each generated report, scoped to the request if given

Every tool above (unlike Java's Checkstyle/PMD/SpotBugs) accepts a glob directly, so `[scope]` should already
have narrowed what ran wherever the tool supports it (ESLint, Prettier, Stylelint, Oxlint, Biome file arguments). Only
`tsc` always runs the whole project graph regardless of scope (Step 1.2) — for it, filter the parsed diagnostic
lines to ones whose leading `<file>` path matches `[scope]` before reporting, the same way `iru-java-code-quality`
extracts a matching `<file>`/`classname` block from a whole-project Checkstyle/PMD/SpotBugs report instead of
re-running the tool per class.

**With no argument** (project-wide): report every parsed entry from every tool that ran.

**With a `[scope]` argument**: for ESLint/Oxlint/Biome, only report entries whose `filePath`/`filename`/
`location.path` falls under
`[scope]`; for Prettier/Stylelint, only report files under `[scope]`; for `tsc`, filter as above. An empty result
for a tool after filtering means that tool found nothing in `[scope]`, not that it didn't run — say so rather
than omitting the tool from the report.

## Step 3 — Classify by rule id, diff against a baseline if given, and report

Bucket every issue by rule id, grouped first by which linter/checker reported it (a scan bucketed only by rule id
without stating its source is ambiguous — `no-unused-vars` exists in both ESLint's core rules and Oxlint's):

- **ESLint** — bucket by the exact `ruleId` (`@typescript-eslint/no-unused-vars`, `@typescript-eslint/no-explicit-any`,
  `react-hooks/rules-of-hooks`, `@angular-eslint/no-empty-lifecycle-method`, Next.js's `@next/next/*` rules from
  `eslint-config-next` such as `@next/next/no-img-element`/`@next/next/no-html-link-for-pages` plus the
  `jsx-a11y/*`, `react/*`, and `import/*` rules that config enables (verified on Next 16.4: an `<img>` without
  `alt` produced `@next/next/no-img-element` and `jsx-a11y/alt-text`, both severity `1`), React Native's
  `@react-native/eslint-config` ids (`react-native/no-inline-styles`, `@react-native/no-deep-imports`,
  `ft-flow/*`, `jest/*`, `react-hooks/*`, `eslint-comments/*`) and `eslint-config-expo`'s (`expo/*` such as
  `expo/no-env-var-destructuring`, plus `import/*` — verified: an unresolved import produced `import/no-unresolved`),
  bare ESLint-core ids like `prefer-const`/`eqeqeq` with no prefix). A `null` `ruleId` (parsing errors ESLint itself can't attribute to a
  rule) gets its own `<parse-error>` bucket.
- **Oxlint** — bucket by `code` verbatim (`eslint(no-unused-vars)`, `typescript-eslint(no-explicit-any)`,
  `typescript(no-floating-promises)`) — report these separately from ESLint's buckets even when the rule name
  overlaps; they're different tools' runs.
- **Biome** — bucket by `category` verbatim (`lint/suspicious/noExplicitAny`, `lint/style/useConst`,
  `lint/correctness/noUnusedVariables`); `format` diagnostics go in the same `formatting` bucket Prettier uses.
- **tsc** — bucket by the `TS<code>` diagnostic code (`TS2322`, `TS2345`, `TS6133`, …), parsed out of each
  `error TS<code>:` line. For Next.js, say whether `next typegen` ran first.
- **Prettier** — every finding is one `formatting` bucket (Prettier doesn't have rule ids); count of files listed
  by `--list-different`.
- **Stylelint** — bucket by the `rule` field (`color-hex-length`, `selector-class-pattern`, …).
- **Accessibility lint** (UI projects — React/Vite, Next.js, Ionic, Hilla, Angular) — no separate run: these are
  ESLint rule ids already in the buckets above, also summed into one `accessibility` subtotal so the UI contract's
  accessibility level can be read at a glance: `jsx-a11y/*` (`eslint-plugin-jsx-a11y`, which `eslint-config-next`
  enables) and angular-eslint's template accessibility rules (`angular.configs.templateAccessibility` —
  `@angular-eslint/template/alt-text`, `click-events-have-key-events`, `elements-content`,
  `interactive-supports-focus`, `label-has-associated-control`, `mouse-events-have-key-events`, `no-autofocus`,
  `no-distracting-elements`, `role-has-required-aria`, `table-scope`, `valid-aria`). When a UI project's ESLint
  config enables neither plugin, say "accessibility lint not configured" in the report (and that the UI tests'
  axe check is then the only automated accessibility check) — never add the plugin or edit the config here.

**Report**: per tool, a total issue count and the config-not-found/skipped note if applicable; within each tool,
one line per rule-id bucket giving its count and, for a scope smaller than the whole project, the file:line list
too. State an overall total across all tools at the top, and — for a UI project — the `accessibility` subtotal (or
"accessibility lint not configured") right below it.

**Baseline diff** — when the prompt that invoked this skill supplies a baseline (a prior run's per-rule-id counts
or issue list, e.g. `iru-typescript-code-one-task-group` capturing one before a task group's changes and asking
for the post-change result compared against it, the same contract `iru-java-code-one-task-group` uses for
`iru-java-code-quality`): compute the diff yourself — for each tool/rule-id bucket, report only issues that are
new since the baseline (a rule id whose count increased, or one that appears now but didn't in the baseline; a
specific new file:line entry when the baseline was itemized rather than just counts) — never report the full
current list next to the full baseline for the caller to diff themselves. A rule id whose count decreased or
stayed the same is not a regression and is omitted from this diff entirely, even though it's still reported in
the plain (non-baseline) count above it. State explicitly when a tool couldn't be diffed because it wasn't run in
the baseline (skipped/not configured then) — treat every one of its current issues as new in that case.

Do not attempt to fix any of the issues found, or modify ESLint/Oxlint/Biome/tsc/Prettier/Stylelint configuration — that's a
follow-up the user drives explicitly.
