---
name: iru-setup-typescript-library
description: "/iru-setup-typescript-library [args] generates the build/test/lint/docs toolchain for a new npm/TypeScript library at the repository root: package.json, tsconfig, Vitest, ESLint, Prettier, Typedoc, optional Sonar/Changesets, and a src/test skeleton. Asks identity, publish and sonar options unless passed as args by iru-setup-typescript-repository; asks Update/Keep/Replace on existing files."
model: sonnet
---

# Setup TypeScript Library

Generate the standard npm/TypeScript library toolchain at the repository root: `package.json`, `tsconfig.json`,
`vitest.config.ts`, `eslint.config.js`, `.prettierrc`/`.prettierignore`, `typedoc.json`, and, when opted in,
`sonar-project.properties` and `.changeset/config.json` — plus a minimal `src/index.ts` + `test/index.test.ts`
skeleton — using explicit example templates embedded in this skill (Step 5) that are genericized (no real
repo/org/person names, `<placeholder>` markers resolved from Steps 2–4). Every devDependency version is looked up
from the npm registry at run time; the versions recorded in this file are only the fallback for when that lookup
fails.

## Step 0 — Resolve inputs

This skill can be invoked stand-alone (`/iru-setup-typescript-library`) or as a step inside another skill (e.g. a
future `iru-setup-typescript-library-repository` or the catalog's `iru-setup-repository` front door), which
resolves these same inputs itself and passes them through `args` as `key: value` lines, one per line, e.g.:

```
package-name: my-library
scope: example-org
description: A small example TypeScript library.
license: MIT
developer-name: Jane Doe
developer-email: jane@example.com
organization-url: https://github.com/example-org
open-source: yes
publish: yes
sonar: cloud
sonar-organization: example-org-github
sonar-project-key: example-org_my-library
sonar-host-url: https://sonarcloud.io
integration-branch: develop
visibility: public
ci: yes
on-existing: ask
mode: new
```

Parse any such lines from `args` now. Every field found here is resolved — skip asking about it in Step 2. Only
fields genuinely missing from `args` still need a question. If `args` is absent or doesn't look like this format,
treat everything as unset and ask normally.

Recognized keys: `package-name`, `scope` (the npm scope without the leading `@`, e.g. `example-org` — optional;
when unset the package publishes unscoped), `description`, `license`, `developer-name`, `developer-email`,
`organization-url`, `open-source` (`yes`/`no`), `publish` (`yes`/`no` — whether `package.json` is configured to
publish to the public npm registry), `sonar` (`cloud`/`self-hosted`/`none`) plus, only when `sonar` is `cloud` or
`self-hosted`, `sonar-organization`/`sonar-project-key`/`sonar-host-url`, `integration-branch` (the branch
Changesets compares against in `.changeset/config.json`'s `baseBranch` — only consulted when `publish: yes`;
default `main`, but an orchestrator running this catalog's `develop`/`main` branching model passes `develop` so
the config agrees with the workflows `iru-setup-typescript-github-workflows` generates), `visibility`
(`public`/`private`/`internal` — the GitHub repository's visibility, used only to key Step 2's Sonar
recommendation), `ci` (`yes`/`no` — whether the run invoking this skill generates GitHub Actions workflows at all;
only consulted by Step 2's Sonar question), `on-existing` (`ask`/`keep`/`update`/`replace`, default `ask` — how
Step 1 treats each pre-existing file this skill owns; meant for scripted runs), and `mode` (`new`/`existing`).

`mode: existing` is this catalog's shared signal (set by a front door or orchestrator that already knows this is an
established repository) that this run is an **update**: Step 1 announces it as such, but it still warns and asks
per pre-existing file (Update / Keep / Replace) unless `on-existing` says otherwise — `mode: existing` alone never
means "update silently". `mode: new` or an unset `mode` follows the same survey; the mode only changes the
announcement when no owned file exists yet.

## Step 1 — Survey the repository and decide per existing file

Check, at the repository root, which of this skill's files already exist: `package.json`, `tsconfig.json`,
`vitest.config.ts`, `eslint.config.js`, `.prettierrc`, `.prettierignore`, `typedoc.json`, `sonar-project.properties`,
`.changeset/config.json`, `src/index.ts`, `test/index.test.ts`.

Announce which of the two situations this is before asking anything else:

- **None exist**: say "Creating the TypeScript library toolchain **from scratch** — none of this skill's files
  exist yet." and skip straight to Step 2. There is nothing to preserve.
- **At least one exists** (most commonly `package.json` from a prior run or a hand-started repository): say
  "**Updating an existing setup** — found: <list of files>." and warn that each of them will be handled
  individually. Then resolve one action per existing file:
  - **`on-existing` is `keep`/`update`/`replace`** (from Step 0): apply that action to every existing file without
    asking — the warning above is still printed.
  - **Otherwise** (`ask`, the default): ask with `AskUserQuestion`, batching up to 4 files per call (one question
    each), with these options — explain the consequence of each in its description, and name any concrete
    contradiction with this run's choices in the question (e.g. "your `package.json` has `publishConfig` but you
    chose `publish: no`"):
    - **Update (Recommended)** — merge: add what's missing and correct outdated parts using Step 5's template as
      reference (e.g. for `package.json`: missing scripts/devDependencies/`engines`/`exports`, `publishConfig` vs
      `"private": true` per `publish`; for config files: missing options/ignores), keep everything this skill
      doesn't own (extra scripts, dependencies, ESLint rules, TypeDoc options). The user reviews a diff;
      customizations survive, but a customized part this skill owns may be rewritten.
    - **Keep as is** — leave the file untouched. Nothing customized changes, but it may stay out of date or
      disagree with this run's choices (e.g. a `sonar-project.properties` left behind after `sonar: none`) — Step
      8's report lists each mismatch.
    - **Replace** — regenerate from Step 5's template. Matches this house pattern exactly, but every customization
      in the file is lost (recoverable only from git history).
    - **Stop** (first batch only) — end this skill now without changing anything; report this and end here.

    For the placeholder source files (`src/index.ts`, `test/index.test.ts`) recommend **Keep as is** instead —
    they are real library code once the project exists, Update has nothing meaningful to merge, and Replace would
    overwrite actual source with the `add()` placeholder (say so in the question).
  - Ask about `sonar-project.properties` and `.changeset/config.json` only once Step 2 has resolved `sonar` and
    `publish` (a second, small batch after Step 2), so the question can say whether this run still wants the file:
    with `sonar: none` / `publish: no`, **Update**/**Replace** mean *removing* the file and **Keep** leaves it in
    place as a reported mismatch.

  When any file is to be updated or replaced, read the existing `package.json` (if present) first and use its
  current `name`, `description`, `license`, `author`, and any already-resolved `open-source`-style hints (e.g. an
  existing `publishConfig` implies `publish: yes`) as the *defaults* offered for whichever Step 2 question Step 0
  didn't already resolve via `args` — a value supplied via `args` always wins.

## Step 2 — Collect the required inputs

For any field Step 0 already resolved from `args`, use that value directly and don't ask about it again. For
everything else, ask the user directly (plain conversation, pre-filling defaults found in Step 1 if updating or
replacing):

When an existing `package.json` (or app config such as `app.json`/`angular.json`) already supplies an identity
value Step 0 didn't — typical in `mode: existing`, where orchestrators omit identity keys — use it as resolved and
confirm it in one line instead of asking.

- **package-name** — the unscoped npm package name, e.g. `my-library`.
- **scope** — the npm scope without the leading `@`, e.g. `example-org` (optional — ask whether the package should
  be scoped at all before asking for the scope itself; an unscoped name is a legitimate choice). The published
  name is `@<scope>/<package-name>` when a scope is given, otherwise plain `<package-name>`.
- **description** — one line, used in `package.json`'s `description` field.
- **Developer name**, **developer email**, **organizationUrl** — populate `package.json`'s `author` object.

Then, unless Step 0 already resolved a `license` value from `args`, ask about the **license** with
`AskUserQuestion` (small bounded choice):

- MIT License (recommended for npm packages) — SPDX id `MIT`
- Apache License 2.0 — SPDX id `Apache-2.0`
- No license (proprietary / all rights reserved) — SPDX id `UNLICENSED`, and `"private": true` is added to
  `package.json` regardless of the `publish` answer below, since an unlicensed package should never be published
  to the public registry
- Other — ask for the SPDX identifier directly afterward

If a license is chosen (anything but "No license"), remind the user in Step 8 to also add a matching `LICENSE`
file at the repository root if one doesn't exist yet — the `iru-check-license` skill can verify/backfill file
headers against it once it's there, and this skill's own `eslint.config.js` (Step 5) enforces a header comment
via `@tony.ganchev/eslint-plugin-header` once a license is chosen.

Then resolve `open-source`, `publish`, and `sonar` — skip any of the three Step 0 already resolved from `args`.
When invoked stand-alone with none of them pre-resolved, ask `open-source` *first* (`AskUserQuestion`: yes/no) and
derive the recommended defaults for the other two from that answer, per this catalog's shared convention:

- **open-source** — is this repository open source? Yes / No.
- **publish** (to the public npm registry) — default **Yes** when open-source is Yes; when not open source, ask
  with **No** recommended, explaining that publishing to the public registry is meant for redistributable packages
  other projects depend on, which usually isn't appropriate for closed-source code. `No` sets `"private": true` in
  Step 5's template and omits `publishConfig` and `.changeset/config.json` entirely. `Yes` adds `publishConfig:
  {access: "public", provenance: true}` and the `.changeset/config.json` scaffold — note for the user that
  `provenance: true` only takes effect during `npm publish` in CI with OIDC trusted publishing configured (it does
  **not** affect or break a local `npm install`, confirmed while building this skill).
- **sonar** — whether to wire up a SonarQube/SonarCloud scan (`sonar-project.properties`, typically consumed by a
  CI workflow such as `iru-setup-typescript-github-workflows`, so it's worth setting up here even if that workflow
  comes later). The recommendation keys on both `open-source` and `visibility`. If `visibility` wasn't supplied via
  `args`, resolve it only now (only when this question is actually asked): `gh repo view --json visibility -q
  .visibility` when the repository has a GitHub remote (lowercase the result and tell the user the detected
  value), otherwise ask **Public** / **Private** with `AskUserQuestion` (Public recommended when open source,
  Private otherwise); `internal` counts as private. If `open-source: yes` but `visibility` is `private`/`internal`,
  warn that the two contradict each other and ask which one is right. Then recommend **SonarCloud** (`cloud`) only
  when the repository is public **and** open source; otherwise state that SonarCloud is free only for public
  open-source projects (per its current pricing — a private project needs a paid plan) and recommend **None**
  (`none`), offering **self-hosted SonarQube** (free Community Build, but you run the server) as the second option.
  If `ci: no` was supplied via `args`, say plainly that no CI workflow would send analysis or coverage reports —
  analysis would only run when someone runs the scanner manually with a token — and recommend **None**; if the
  user still picks `cloud`/`self-hosted`, write `sonar-project.properties` anyway and say in Step 8 that no CI step
  sends reports:
  - `cloud` — ask for the `sonar.organization` key, offering `<owner>-github` (the owner parsed in Step 3) as the
    suggested default. Default `sonar.projectKey` to `<owner>_<repo>` (also from Step 3) and `sonar.host.url` to
    `https://sonarcloud.io`, confirming both with the user rather than assuming silently.
  - `self-hosted` — ask for `sonar.host.url` directly (no sensible default), plus `sonar.organization` only if
    that server has organizations enabled, and `sonar.projectKey`.
  - `none` — skip `sonar-project.properties` entirely.

## Step 3 — Infer repository information

Don't ask for these — derive them from the current repository, the same way `iru-setup-java-library` does:

- **Owner/repo and host**: parse `git remote get-url origin` (handles both `git@host:owner/repo.git` and
  `https://host/owner/repo.git` forms). Use the parsed host/owner/repo for `package.json`'s `repository`,
  `homepage`, and `bugs.url` fields (Step 5). If there's no `origin` remote yet, ask the user for the intended
  repository URL instead of leaving it blank.
- **Description fallback**: if Step 2's `description` question was skipped because `args` supplied nothing and the
  user gave none, and the `gh` CLI is available and authenticated, try `gh repo view --json description -q
  .description` before falling back to an empty string (never invent one).

## Step 4 — Look up dependency versions

Every devDependency version in Step 5's `package.json` template must be looked up at run time with `npm view
<package> version` (or, if that's unavailable — offline, registry unreachable — fall back to the version recorded
in the table below, which is what this skill's author actually observed on the npm registry in September 2026 while
building and verifying this skill). Note in Step 8's report whenever a fallback was used instead of a live lookup.

| Package | npm view command | September 2026 fallback |
|---|---|---|
| `typescript` | see the caveat below — **do not** just take `npm view typescript version` | `6.0.3` |
| `tsup` | `npm view tsup version` | `8.5.1` |
| `vitest` | `npm view vitest version` | `5.0.1` |
| `@vitest/coverage-v8` | `npm view @vitest/coverage-v8 version` | `5.0.1` (keep in lockstep with `vitest`'s own version) |
| `eslint` | `npm view eslint version` | `10.10.0` |
| `@eslint/js` | `npm view @eslint/js version` | `10.0.1` |
| `typescript-eslint` | `npm view typescript-eslint version` | `8.70.0` |
| `eslint-config-prettier` | `npm view eslint-config-prettier version` | `10.1.8` |
| `@tony.ganchev/eslint-plugin-header` (only when a license was chosen) | `npm view @tony.ganchev/eslint-plugin-header version` | `3.4.4` |
| `prettier` | `npm view prettier version` | `3.9.7` |
| `typedoc` | `npm view typedoc version` | `0.28.20` |
| `@types/node` | `npm view @types/node@<engines.node major> version` (pin to the major matching `engines.node`'s floor — see caveat below) | `22.20.3` |
| `@changesets/cli` (only when `publish: yes`) | `npm view @changesets/cli version` | `3.0.3` |

**TypeScript version caveat (discovered verifying this skill in September 2026):** TypeScript's own `latest`
dist-tag had already moved to the `7.x` native/Go-based compiler line (`7.0.2` observed), but both
`typescript-eslint@8.70.0` (`peerDependencies.typescript: ">=4.8.4 <6.1.0"`) and `typedoc@0.28.20`
(`peerDependencies.typescript`, capped at the `6.0.x` line) had not yet added support for it — `npm install`
fails with an `ERESOLVE` peer-dependency conflict if `typescript` is pinned to the `7.x` line. Resolve the actual
`typescript` version to install like this instead of trusting `npm view typescript version` blindly:

1. `npm view typescript-eslint peerDependencies.typescript` and `npm view typedoc peerDependencies.typescript` —
   read off the highest version each range actually allows.
2. `npm view typescript versions --json` — pick the newest published stable version (no `-dev`/`-rc`/`-beta`
   suffix) that satisfies *both* ranges from step 1.
3. If that resolved version differs from `npm view typescript version` (the raw `latest` tag), say so explicitly
   in Step 8's report — this is exactly the kind of drift that will eventually resolve itself once the ecosystem
   catches up, and a future run of this skill may pick a newer `typescript` once it does.

**`@types/node` major caveat:** `@types/node`'s own `latest` tag tracks the newest Node.js major, which can run
ahead of the minimum Node version this template's `engines.node` field promises (`>=22`). Pin the installed
`@types/node` to the major matching that floor (`npm view @types/node@22 version` rather than the bare `npm view
@types/node version`) so its ambient types don't advertise APIs unavailable on the oldest Node version the package
still claims to support.

## Step 5 — Reference templates

These are genericized example templates — verified together end-to-end while building this skill (`npm install`,
`build`, `test`, `coverage`, `lint`, `typecheck`, `docs` all run clean against them) — kept verbatim below except
for the placeholders. Only substitute `<placeholder>` values using Steps 2–4; every other line, script, and
compiler option is copied as shown.

### `package.json`

```json
{
  "name": "<package-full-name>",
  "version": "1.0.0-dev.0",
  "description": "<description>",
  "type": "module",
  "license": "<license-spdx-id>",
  "author": {
    "name": "<developer-name>",
    "email": "<developer-email>",
    "url": "<organization-url>"
  },
  "repository": {
    "type": "git",
    "url": "git+<repository-url>.git"
  },
  "homepage": "<repository-url>#readme",
  "bugs": {
    "url": "<repository-url>/issues"
  },
  "engines": {
    "node": ">=22"
  },
  "files": [
    "dist"
  ],
  "main": "./dist/index.js",
  "types": "./dist/index.d.ts",
  "exports": {
    ".": {
      "types": "./dist/index.d.ts",
      "import": "./dist/index.js"
    }
  },
  "sideEffects": false,
  "scripts": {
    "build": "tsup src/index.ts --format esm --dts",
    "test": "vitest run",
    "coverage": "vitest run --coverage",
    "lint": "eslint .",
    "format": "prettier --check .",
    "typecheck": "tsc --noEmit",
    "docs": "typedoc"
  },
  "devDependencies": {
    "@eslint/js": "<eslint-js-version>",
    "@tony.ganchev/eslint-plugin-header": "<eslint-plugin-header-version>",
    "@types/node": "<types-node-version>",
    "@vitest/coverage-v8": "<vitest-version>",
    "eslint": "<eslint-version>",
    "eslint-config-prettier": "<eslint-config-prettier-version>",
    "prettier": "<prettier-version>",
    "tsup": "<tsup-version>",
    "typedoc": "<typedoc-version>",
    "typescript": "<typescript-version>",
    "typescript-eslint": "<typescript-eslint-version>",
    "vitest": "<vitest-version>"
  }
}
```

- **`publish: yes`**: add, alongside `"sideEffects": false`, a `"publishConfig": {"access": "public",
  "provenance": true}` entry, and add `"@changesets/cli": "<changesets-cli-version>"` to `devDependencies`
  (confirmed this does not break `npm install` even without CI's OIDC trusted-publishing set up — it only matters
  at actual `npm publish` time). **`publish: no`**: add `"private": true` instead (right after `"license"`), and
  omit both `publishConfig` and `@changesets/cli`.
- **License "No license" (`UNLICENSED`)**: always add `"private": true` regardless of the `publish` answer, per
  Step 2.
- **`scope` unset**: `<package-full-name>` is just `<package-name>`. **`scope` set**: `<package-full-name>` is
  `@<scope>/<package-name>`.
- `<repository-url>` is `https://<host>/<owner>/<repo>` from Step 3.

### `tsconfig.json`

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2022"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "ignoreDeprecations": "6.0",
    "declaration": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "forceConsistentCasingInFileNames": true,
    "isolatedModules": true,
    "noUncheckedIndexedAccess": true,
    "outDir": "dist"
  },
  "include": ["src", "test"]
}
```

**`ignoreDeprecations: "6.0"` is not optional** — this is the single most important quirk found verifying this
skill: `tsup`'s `--dts` build step (`node_modules/tsup/dist/rollup.js`, its bundled `rollup-plugin-dts` invocation)
unconditionally passes a `baseUrl` compiler option to the TypeScript compiler API even when `tsconfig.json` never
sets one itself. Starting with the TypeScript `6.0`/`7.0` deprecation cycle, an implicit `baseUrl` now fails the
build outright with `error TS5101: Option 'baseUrl' is deprecated and will stop functioning in TypeScript 7.0`
unless `ignoreDeprecations: "6.0"` is set. Without this line, `npm run build`'s `DTS Build` step fails even though
the plain ESM build succeeds. Keep this line even once the project's own `typescript` version moves past `6.0.x` —
`ignoreDeprecations` is itself deprecated by TypeScript 7 in favor of a different mechanism, so revisit this
template once `tsup`/its `dts` bundler ship a fix upstream and `typescript`/`typescript-eslint`/`typedoc` all
support TypeScript 7 (see Step 4's caveat).

### `vitest.config.ts`

```ts
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    coverage: {
      provider: "v8",
      reporter: ["text", "lcov"],
      reportsDirectory: "coverage",
      thresholds: {
        lines: 80,
      },
    },
  },
});
```

Confirmed: `vitest run --coverage` writes `coverage/lcov.info` (from the `lcov` reporter) with correct per-file
`SF:`/`DA:` records, plus an HTML report under `coverage/lcov-report/` — both must be excluded from `eslint`'s and
`prettier`'s scans (see below) since they're generated output, not source.

### `eslint.config.js`

```js
import js from "@eslint/js";
import tseslint from "typescript-eslint";
import prettier from "eslint-config-prettier";
import header from "@tony.ganchev/eslint-plugin-header";

export default tseslint.config(
  js.configs.recommended,
  tseslint.configs.recommended,
  prettier,
  {
    files: ["src/**/*.ts", "test/**/*.ts"],
    plugins: {
      "@tony.ganchev": header,
    },
    rules: {
      "@tony.ganchev/header": [
        "error",
        {
          header: {
            commentType: "block",
            lines: ["\n * Copyright (c) <copyright-year> <developer-name>\n * Licensed under the <license-display-name>.\n "],
          },
        },
      ],
    },
  },
  {
    ignores: ["dist/**", "coverage/**", "docs/**", "node_modules/**"],
  },
);
```

- **No license chosen (`UNLICENSED`)**: drop the `header` import, the `plugins` block, and the
  `"@tony.ganchev/header"` rule entirely — keep only the three base configs plus the trailing `ignores` block.
- `<copyright-year>` — the current year. `<developer-name>` — from Step 2. `<license-display-name>` — `"MIT
  License"` / `"Apache License, Version 2.0"` / the custom license's display name from Step 2.
- Confirmed working end-to-end: `eslint.config.js` itself must carry the same header (source files are linted
  by `files: ["src/**/*.ts", "test/**/*.ts"]` only, so config/build files are exempt — no header needed on them).
  Verified the rule actually fires (not a silent no-op) by stripping the header from a test file and re-running
  `eslint`: it reports `header line longer than expected` and offers `--fix`. ESLint 10's flat config format (no
  `.eslintrc*`, no `.eslintignore` — ESLint 9+ removed `.eslintignore` support; use the trailing `ignores` object
  in the flat config array instead, as shown) is required — this template targets ESLint 10 only.
- `typescript-eslint`'s `tseslint.configs.recommended` used here is the *non* type-checked preset (no
  `parserOptions.project` needed) — fast, and sufficient for this template's scope/lint rules. Don't add the
  type-checked variant (`recommendedTypeChecked`) without also wiring `languageOptions.parserOptions.projectService`,
  which this template deliberately leaves out to keep `eslint .` fast on a fresh scaffold.

### `.prettierrc`

```json
{
  "semi": true,
  "singleQuote": false,
  "trailingComma": "all",
  "printWidth": 100
}
```

### `.prettierignore`

```
dist
coverage
docs
.changeset
.github
.claude
CHANGELOG.md
README.md
```

**Required, not optional** — without it, `prettier --check .` recurses into `dist/`, `coverage/` (including the
generated `coverage/lcov-report/*.html`), and `docs/api/` (TypeDoc's generated site) and fails on every generated
file. `docs` (the whole Antora tree — `antora-playbook.yml`, `antora.yml`, `.adoc` pages, and `docs/api/`),
`.github` (the workflow YAML `iru-setup-typescript-github-workflows` writes), `.claude`, and the generated `CHANGELOG.md`/
`README.md` are excluded too, because `build.yml`'s own "Check formatting" step runs this exact command on a fresh
clone and none of those files are produced by Prettier — verified against a pipeline-generated repository, whose first CI run
would otherwise fail on `build.yml`, `security.yml`, `sync.yml` and
`docs/antora-playbook.yml` before ever reaching the tests. `.claude` covers the Markdown of any Claude Code skills
installed in the consuming repository (the same smoke test showed 100+ `.claude/skills/**/*.md` findings once this
catalog was copied in). The original four entries alone were confirmed against a freshly built/tested/documented
scaffold: 13 false-positive findings before this file was added, zero after. `node_modules` needs no explicit entry
— Prettier ignores it by default.

### `typedoc.json`

```json
{
  "$schema": "https://typedoc.org/schema.json",
  "entryPoints": ["src/index.ts"],
  "out": "docs/api",
  "treatWarningsAsErrors": true,
  "excludePrivate": true,
  "excludeInternal": true
}
```

Confirmed `npm run docs` (`typedoc`, no args — it reads this file automatically) writes the generated site to
`docs/api/`, matching `.prettierignore`/a future `.gitignore` entry.

### `sonar-project.properties` (only when `sonar` ≠ `none`)

```
sonar.organization=<sonar-organization>
sonar.projectKey=<sonar-project-key>
sonar.sources=src
sonar.tests=test
sonar.javascript.lcov.reportPaths=coverage/lcov.info
sonar.exclusions=**/dist/**
```

Omit the `sonar.organization` line entirely for `sonar: self-hosted` servers without organizations enabled (per
Step 2). `<sonar-project-key>` still applies either way.

### `.changeset/config.json` (only when `publish: yes`)

```json
{
  "$schema": "https://unpkg.com/@changesets/config@3.0.0/schema.json",
  "changelog": "@changesets/cli/changelog",
  "commit": false,
  "fixed": [],
  "linked": [],
  "access": "public",
  "baseBranch": "<integration-branch>",
  "updateInternalDependencies": "patch",
  "ignore": []
}
```

- `<integration-branch>` — Step 0's `integration-branch` key when supplied (e.g. `develop` from
  `iru-setup-typescript-repository`, which collects it once for the workflows and forwards it here); when
  invoked stand-alone with `publish: yes` and no such key, ask once (offer `main`, or `develop` if `git branch
  -a` shows one) rather than silently defaulting — a `baseBranch` that doesn't match the branch pull requests
  actually target makes `changeset status` report every change as unreleased.
- Sanity-checked with `npx changeset status`: it correctly loads and validates this config (it only fails with a
  git-history error — `Failed to find where HEAD diverged from "<baseBranch>"` — when run outside a real git
  history synced with that branch, which is expected for a config-only check and not a defect in the file).

### `src/index.ts`

```ts
/*
 * Copyright (c) <copyright-year> <developer-name>
 * Licensed under the <license-display-name>.
 */

/**
 * Adds two numbers together.
 *
 * @param a - The first addend.
 * @param b - The second addend.
 * @returns The sum of `a` and `b`.
 * @example
 * ```ts
 * add(2, 3); // 5
 * ```
 */
export function add(a: number, b: number): number {
  return a + b;
}
```

Drop the leading comment block entirely if "No license" was chosen in Step 2 (matches `eslint.config.js` omitting
the header rule in that case, so nothing requires it). This is a placeholder export meant to be replaced by the
library's real first module — keep it minimal but real (it exists so `npm run build`/`test`/`coverage`/`lint`/
`typecheck`/`docs` all have something concrete to succeed against immediately after scaffolding, exactly as
verified while building this skill).

### `test/index.test.ts`

```ts
/*
 * Copyright (c) <copyright-year> <developer-name>
 * Licensed under the <license-display-name>.
 */

import { describe, expect, it } from "vitest";
import { add } from "../src/index.js";

describe("add", () => {
  it("adds two positive numbers", () => {
    expect(add(2, 3)).toBe(5);
  });

  it("adds a negative and a positive number", () => {
    expect(add(-2, 3)).toBe(1);
  });
});
```

Import the sibling source module with a `.js` extension (`../src/index.js`), not `.ts` — required by
`moduleResolution: bundler` + `"type": "module"` for the path to also resolve correctly once compiled, even though
Vitest's own transform accepts either spelling. Confirmed this skeleton yields 100% line coverage on `src/index.ts`
out of the box.

## Step 6 — Write the files

Apply Step 1's per-file decision:

- **File absent** — create it from Step 5's template.
- **Update** — merge Step 5's template into the existing file as described in Step 1 (add missing
  keys/scripts/devDependencies/options, correct outdated values this skill owns, keep everything else), and show
  the user the diff before writing it.
- **Keep as is** — leave the file untouched; record any mismatch with this run's choices for Step 8.
- **Replace** — overwrite it with Step 5's template.

This applies to `package.json`, `tsconfig.json`, `vitest.config.ts`, `eslint.config.js`, `.prettierrc`,
`.prettierignore`, `typedoc.json`, `src/index.ts`, `test/index.test.ts`, and:

- `sonar-project.properties` — written only when `sonar` ≠ `none`; with `sonar: none`, an existing file is removed
  on Update/Replace and left in place on Keep.
- `.changeset/config.json` — written only when `publish: yes`; with `publish: no`, an existing file is removed on
  Update/Replace and left in place on Keep.
- Create `src/` and `test/` directories as needed — don't touch any other file already inside them.

## Step 7 — Install dependencies

Run `npm install` at the repository root. This resolves every version from Step 4/5 against the lockfile and
installs `node_modules/`. If it fails with an `ERESOLVE` peer-dependency error mentioning `typescript`, re-check
Step 4's TypeScript version caveat — it means a too-new `typescript` was picked despite the lookup instructions.

If a different package manager is clearly already in use in this repository (a `pnpm-lock.yaml` or `yarn.lock`
already present, or `packageManager` already set in an existing `package.json` that was kept or updated), use
that package manager's install command instead (`pnpm install` / `yarn install`) rather than introducing a second
lockfile — but default to `npm install` for a genuinely new scaffold, matching this skill's own template scripts.

## Step 8 — Report

Start by repeating the mode announced in Step 1 (**created from scratch** or **updated an existing setup**).
Then summarize what was generated: the resolved package name (with scope, if any), version (`1.0.0-dev.0`), license (or
"none"/`UNLICENSED`), and the repository info inferred in Step 3.

State explicitly what was included versus omitted, and why:

- **`open-source`**: yes/no, as resolved in Step 2.
- **`publish`**: yes/no — if `yes`, `publishConfig`, `.changeset/config.json`, and the `@changesets/cli`
  devDependency were included; if `no`, `"private": true` was set and all three were omitted.
- **`sonar`**: `cloud`/`self-hosted`/`none` — if `cloud`/`self-hosted`, `sonar-project.properties` was written with
  the resolved `sonar.organization`/`sonar.projectKey`; if `none`, note that this is expected when the repository
  isn't public and open source (SonarCloud is free only for public open-source projects) unless the user explicitly
  chose it. State the `visibility` used and whether it came from `args`, `gh`, or the user. If `ci: no` was
  supplied and Sonar was still configured, say that no CI step sends reports — the scanner has to be run manually.
- Which devDependency versions came from a live `npm view` lookup versus this skill's recorded fallback, and
  whether the TypeScript-version caveat (Step 4) resulted in a `typescript` version older than the registry's bare
  `latest` tag — if so, say so plainly and name the two packages (`typescript-eslint`, `typedoc`) whose peer ranges
  forced that choice, so the user can re-run this skill later once the ecosystem catches up to TypeScript 7.

Then report, per file, the action actually taken — **created / updated / replaced / kept as is / removed** — and
whether `npm install` succeeded. For every kept file, list each mismatch with this run's choices (e.g. a
`publishConfig` still present after `publish: no`, a `sonar-project.properties` left after `sonar: none`). If any
file was replaced, explicitly list what could have been lost — any script, dependency, or config option beyond what
Step 5's templates show — and tell the user to check `git diff` for anything they need to re-add.

Finish with an explicit **Warn explicitly** block:

- **The user must review every generated file before building, committing, or publishing.** Confirm the license
  choice matches an actual `LICENSE` file (or that none is intended — suggest the `iru-check-license` skill to
  generate one and backfill source headers otherwise), that the inferred repository URL is correct, and that `npm
  run build && npm test && npm run typecheck` succeed before relying on this scaffold.
- If `sonar: cloud` or `sonar: self-hosted` was chosen, a `SONAR_TOKEN` repository secret and an actual
  SonarCloud/SonarQube project matching `sonar.projectKey` still need to exist before a CI-driven Sonar scan will
  succeed — this skill only writes `sonar-project.properties`, it does not run a scan itself.
- If `publish: yes` was chosen, npm registry publishing credentials (or OIDC trusted publishing configured on
  npmjs.com) still need to exist before `npm publish` (typically from a generated CI workflow) will succeed —
  `provenance: true` alone does not publish anything on its own, and does not affect local `npm install`.
- No `.gitignore` was written by this skill — `node_modules/`, `dist/`, `coverage/`, and `docs/api/` should be
  ignored before the first commit; the `iru-setup-typescript-gitignore` skill covers this.
- Package name/version/dependency versions were resolved via a run-time registry lookup (Step 4) and may already
  be stale by the time this report is read — re-run `npm outdated` before a first real release.
