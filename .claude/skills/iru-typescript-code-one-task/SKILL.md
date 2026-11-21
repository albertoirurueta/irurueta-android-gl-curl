---
name: iru-typescript-code-one-task
description: "Invoked as /iru-typescript-code-one-task <task description> (the task's text with its implementation_plan.md checkbox lines) via Skill. Implements one TypeScript (library, React, Angular, Ionic, Hilla) plan task and its tests, ticks its checkbox (validation pending); React Native goes to iru-react-native-code-one-task; no baseline or full gates, which iru-typescript-code-one-task-group runs."
model: sonnet
allowed-tools: Read Edit Write Skill Bash(npm *) Bash(npx *) Bash(pnpm *) Bash(yarn *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *)
---

# Implement one plan task

Carry out a single task's implementation, tests, and checkbox update against the real codebase — nothing else.
This is the narrowest execution unit in the `iru-code` pipeline: `iru-typescript-code-one-task-group` calls it
once per task in a bucket (potentially many at once, in parallel agents), then handles license headers, TSDoc,
and all test/coverage/quality validation itself, once for the whole bucket, instead of repeating that validation
here for every single task. It uses a medium model on purpose — the plan already carries the hard reasoning;
this skill just executes one task's implementation, against code agreements that live in `reference/` rather
than being re-derived per task.

## Step 1 — Detect the framework, then read only the reference material this task needs

This skill ships a `reference/` directory next to this file, holding this catalog's general TypeScript code
agreements plus one file per supported framework. Before reading anything, detect which framework this project
is (needed even for a task that only touches one existing file, because it decides which single extra reference
file to read):

1. Read `package.json` at the project root (and, for a Maven-hosted frontend, the root `pom.xml` too).
2. Classify, in this order — first match wins:
   - `@vaadin/hilla` or `hilla-spring-boot-starter` in `pom.xml`, or a `src/main/frontend/` directory present →
     **hilla-frontend**.
   - `expo` or `react-native` in `package.json` dependencies → **react-native**. React Native has its own key now:
     if `iru-react-native-code-one-task` is installed (`.claude/skills/iru-react-native-code-one-task/SKILL.md` at
     the repository root, or listed among the available skills), invoke `Skill({skill:
     "iru-react-native-code-one-task", args: "<this task's full text, unchanged>"})`, relay its report as this
     skill's Step 7 report (noting the hand-off and that the plan should tag such tasks `react-native`), and stop —
     don't continue with the steps below. Only when it isn't installed, continue with
     `reference/react-native.md`'s minimal fallback.
   - `@ionic/angular` or `@ionic/react` together with `@capacitor/core` → **ionic**.
   - `@angular/core` in dependencies → **angular**.
   - `react` together with `vite` in dependencies/devDependencies → **react**.
   - Otherwise → **library** (a plain npm/TypeScript package with no UI framework).
3. Also note the test runner (`vitest` in devDependencies → Vitest; `jest`/`jest-expo` → Jest; `@angular/build`
   with the `unit-test` builder → `ng test`, itself backed by the Vitest runner) and the package manager
   (`packageManager` field or lockfile: `package-lock.json` → npm, `pnpm-lock.yaml` → pnpm, `yarn.lock` → yarn) —
   `reference/testing.md` and Step 4 need both, and Step 3's shell examples assume whichever manager the lockfile
   says.

Then read `reference/README.md` first — it is short and routes you to the rest — then read only what it points
at for this task and the detected framework. The routing, restated:

| Read | When |
|---|---|
| `reference/code-style.md` | **Always** — strict typing, `readonly`, discriminated unions, no `any`, named exports, ESM, error handling, async. |
| `reference/tsdoc.md` | The task adds or changes an exported function, class, interface, type, or enum. |
| `reference/testing.md` | The task writes or updates tests — nearly every task, since Step 4 is part of implementing it. |
| `reference/react.md` | Detected framework is **react**. |
| `reference/angular.md` | Detected framework is **angular**. |
| `reference/react-native.md` | Detected framework is **react-native** *and* `iru-react-native-code-one-task` isn't installed (otherwise the task was already handed over above) — a minimal fallback. |
| `reference/ionic.md` | Detected framework is **ionic**. |
| `reference/hilla-frontend.md` | Detected framework is **hilla-frontend**. |
| `reference/ui-contract.md` + `reference/ui-implementation.md` | The task adds or changes a screen, component, list or other UI (any framework but **library**). `reference/ui-screenshots.ts` is the shared screenshot/accessibility helper to copy when the project has none. |

Read exactly one framework file — the one matching Step 1's classification — never more than one, and none at
all when the classification is **library**. The UI pair is read in addition to it, not instead of it. Don't read the optional files speculatively: for a change inside one
existing function in a plain library with no exported-symbol change, `code-style.md` plus `testing.md` is the
whole list.

If `reference/` is missing (e.g. the skill directory was copied without it), proceed using this repository's own
`CLAUDE.md` conventions and the surrounding code's existing style, and say so in Step 7's report — don't guess at
what the reference would have said.

## Step 2 — Re-check the current code state

Read the actual current content of the file(s) the task touches before editing — don't assume any "current code
state" notes passed in with the task are still accurate; other tasks in the same bucket may be landing
concurrently, or the file may have changed for unrelated reasons.

Also note what the surrounding file and package already do about the things `reference/` has opinions on:
whether types are strict, how errors are surfaced, how components are structured, where state lives. The
reference's rules are the default for new code; a file that consistently does something else wins, and the
divergence goes in Step 7's report rather than becoming an unrequested restyle.

## Step 3 — Implement exactly what the task specifies

The named file(s), the described component/function/type, the behavior, the hook/interface it implements. Apply
`reference/code-style.md`, `reference/tsdoc.md`, and whichever single framework file Step 1 selected, plus this
repository's own `CLAUDE.md` conventions where they are more specific (TypeScript/Node version, license header
expectations, banned dependencies).

Non-negotiable, because a later gate enforces them:

- **Full TSDoc** on every exported function, class, interface, type alias, and enum, with `@param`, `@returns`,
  and `@throws` for every error a caller can handle. The `@throws` list is what Step 4's tests are written
  against.
- **No `any`.** Use `unknown` plus a narrowing guard, a generic, or a proper union/discriminated union instead.
  A pre-existing `any` outside the task's scope is not this task's problem to fix.
- **`readonly` and immutability as the defaults for new code** — `readonly` properties, `ReadonlyArray`/`readonly
  T[]` parameters and return types where the caller must not mutate what it receives, `const` over `let`
  wherever the binding is never reassigned.
- **Named exports** — no default exports for new modules (framework files that require them, e.g. a Next.js/Expo
  Router page or an Angular schematic entry point, are the documented exception; note it in Step 7 if used).
- **No new runtime dependency** unless the task explicitly calls for it. If one seems unavoidable, that's a
  blocker to report (Step 5), not a decision to make quietly.

For a task that adds or changes UI, also:

- **Decompose first** (`ui-contract.md` §2): before writing a component, write the `Screen › Region › Component`
  tree for what the task touches — inputs, events, visual states, and which existing component each one reuses —
  and keep it for Step 7's report. Build leaves first, then collections, then screens.
- Presentational components take their UI state as props/`input()`s and emit typed events; screens own data and
  navigation; collections go through the framework's virtualizing container (`ui-implementation.md`), never a plain
  `map`/`@for` over unbounded data; CSS uses logical properties so right-to-left works.

Don't add anything the task didn't ask for — no speculative abstractions, no unrelated cleanup, no restyling of
code the task doesn't touch.

## Step 4 — Write or update the tests

Follow `reference/testing.md`, the detected framework's own file (for component/hook testing conventions), and
the existing test style in the same directory (Vitest or Jest, Testing Library query priority, mocking with
`vi.mock`/`jest.mock` only where already used). Cover the new/changed behavior, including the edge cases implied
by the TSDoc `@throws` contracts (e.g. invalid-argument or rejected-promise cases). Do not run the test suite
yourself — `iru-typescript-code-one-task-group` runs it once for the whole bucket in its own consolidated
validation pass.

For UI, also write UI tests per `ui-contract.md` §6–§8 and `ui-implementation.md`: a story per visual state next to
each added/changed component (fixture sets for minimal, typical, many-items/long-content and right-to-left data), a
Playwright component spec (behaviour once, then `captureUiMatrix` per state with the accessibility check), a screen
spec for a changed screen, and a flow spec for a new user flow — all using the project's
`e2e/support/ui-screenshots.ts` helper (created from `reference/ui-screenshots.ts` if missing, with
`@axe-core/playwright` as a dev dependency — the one dependency this permits). If the project has no Playwright
setup at all, write the behaviour tests only and report the missing UI-test setup as a deviation rather than
scaffolding Playwright mid-task. Don't run them either.

## Step 5 — When to interrupt the user

Keep interruptions rare — most tasks should complete unattended. Stop and use `AskUserQuestion` (or plain text if
no real choice is being offered) only when:

- Implementing the task reveals it's ambiguous in a way that changes correctness or scope and can't be safely
  inferred — conflicting instructions, a choice only the user can make (e.g. which of two APIs to break), or a
  missing decision. Don't ask about things with an obvious best-practice answer.
- The task's described approach appears infeasible against the real code as it stands (e.g. it names a component,
  hook, or exported symbol that doesn't exist and isn't a trivial typo) — not something you can resolve by
  writing the code slightly differently.
- The framework detection in Step 1 is genuinely ambiguous (e.g. `package.json` carries both `@angular/core` and
  an unrelated stray `react` devDependency) in a way that changes which reference file and conventions apply.

Do not stop merely because the task is nontrivial — implement it. Do not report the task done to work around a
blocker; report it as blocked instead (Step 7), with enough detail — what was tried, what failed, why — for the
caller to surface it. A conflict between `reference/` and the surrounding code is *not* a reason to stop: follow
the surrounding code and report the divergence.

## Step 6 — Check off this task's checkbox now

If this task did not stop on a blocker (Step 5), update `implementation_plan.md` at the repository root before
reporting back — don't leave this for the caller. Re-read the file fresh (not any earlier cached view — other
tasks in the same bucket may be landing concurrently) and locate this task's own checkbox line by the exact text
passed in with the task. Flip its `[ ]` to `[x]`, and do the same for every sub-task checkbox that was part of
the work just completed, adding a short note naming the files touched, e.g. `- [x] Task 2. **Implement
`useOrderTotal`** — implemented, tests added; group validation pending.` — the same "pending" wording
`iru-typescript-code-one-task-group` uses, since the coverage/quality outcome isn't known until it validates the
whole bucket in its own Step 3/4. Edit only this task's own line(s) — never rewrite surrounding lines or other
tasks' checkboxes, since sibling tasks in a parallel bucket may be editing this same file at nearly the same
moment. This is what lets an interrupted run resume without re-attempting a task whose implementation and tests
already landed, even if the interruption happened before `iru-typescript-code-one-task-group` got to run its own
group-wide validation.

If this task stopped on a blocker instead, leave its checkbox untouched — there's nothing finished to mark done.

## Step 7 — Report the outcome

Hand control back to the caller with a short summary:

- **The framework Step 1 detected** (library/react/angular/react-native/ionic/hilla-frontend) and the reference
  file(s) actually read — for react-native, whether the task was handed to `iru-react-native-code-one-task` or the
  minimal fallback was used.
- **The file(s) touched** and **the tests added/updated** (not run — see Step 4).
- **For UI work**: the `Screen › Region › Component` decomposition (Step 3), the stories and UI specs added, and the
  screenshot states they will capture (`<kind>/<Id>/<state>`), so the group skill can check the matrix.
- **Whether this task stopped on a blocker** per Step 5, with enough detail for the caller to surface it.
- **Any deliberate deviation from `reference/`** — e.g. matching a file that still uses `any`, keeping a default
  export because the framework requires one, or adding a member to a file whose existing structure is already
  inconsistent — so the caller can see it was a decision rather than an oversight. Say so here too if
  `reference/` was missing entirely (Step 1).

This is what `iru-typescript-code-one-task-group` records for this task before running its own consolidated
license/TSDoc/test/coverage/quality validation across the whole bucket; this skill has already checked off this
task's own checkbox with a pending-validation note (Step 6) — the caller only needs to replace that note with the
final validation outcome once the whole bucket passes, not create the checkbox entry itself. This skill itself
never captures a quality baseline and never runs tests/coverage/quality checks.
