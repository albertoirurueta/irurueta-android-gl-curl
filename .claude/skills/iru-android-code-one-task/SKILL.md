---
name: iru-android-code-one-task
description: "Invoked as /iru-android-code-one-task <task description> (the task's text with its implementation_plan.md checkbox lines) via Skill. Implements one Android/Kotlin (Gradle lib or app module) plan task and its tests, compile-checks, ticks the checkbox with a group-validation-pending note; no baseline or full gates, which iru-android-code-one-task-group runs."
model: sonnet
allowed-tools: Read Edit Write Bash(./gradlew *) Bash(gradle *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *)
---

# Implement one plan task

Carry out a single task's implementation, tests, and checkbox update against the real codebase — nothing else.
This is the narrowest execution unit in the `iru-code` pipeline: `iru-android-code-one-task-group` calls it once
per task in a bucket (potentially many at once, in parallel agents via `iru-isolated-skill-executor`), then
handles license headers, KDoc, and all test/coverage/quality validation itself, once for the whole bucket,
instead of repeating that validation here for every single task. It uses a medium model on purpose — the plan
already carries the hard reasoning; this skill just executes one task's implementation, against code agreements
that live in `reference/` rather than being re-derived per task.

## Step 1 — Detect the module, then read only the reference material this task needs

**Kotlin Multiplatform projects are out of scope.** If the Kotlin Multiplatform plugin (`kotlin("multiplatform")`/`org.jetbrains.kotlin.multiplatform`, directly or via a `libs.versions.toml` alias) is applied anywhere in the build — a KMP app's Android host module included — stop and hand the request to `iru-kmp-code-one-task` instead (or tell the caller to, when it isn't installed); this skill assumes a plain Android `lib`/`app` layout.

An Android scaffold from this catalog (`iru-setup-android-library`/`iru-setup-android-app`) has one or two Gradle
modules: `lib` (the library, `com.android.library`) and, for a library repository, an `app` sample module
(`com.android.application`, typically Compose) that exercises it; an app-only repository has just `app`. Before
reading anything, work out which module this task's file paths or description name:

1. If the task text names a path under `lib/` or `app/` directly, that module wins.
2. Otherwise infer from what the task describes: a public/internal class, function, or `View` meant to be
   consumed by other projects → `lib`; an `Activity`, `Composable` screen, or anything under a package ending in
   `.app` → `app`.
3. If genuinely ambiguous and the repository has only one of the two modules, use that one. If both exist and
   nothing disambiguates, default to `lib` — most tasks in a library repository touch the library, not its
   sample app — and say so in Step 8's report.

This module choice decides the Gradle module qualifier for Step 5's compile-check (`:lib:...`/`:app:...`) and,
together with the task's own package path, where new files are created.

This skill ships a `reference/` directory next to this file, holding this catalog's general Kotlin/Android code
agreements. Read `reference/README.md` first — it is short and routes you to the rest — then read only what it
points at for this task. The routing, restated:

| Read | When |
|---|---|
| `reference/code-style.md` | **Always** — Kotlin official style, `val`/`var`, null-safety, visibility, member ordering, naming, exceptions. |
| `reference/kdoc.md` | The task adds or changes a class, object, interface, function, or public/internal property. |
| `reference/testing.md` | The task writes or updates tests — nearly every task, since Step 4 is part of implementing it. |
| `reference/compose.md` | The task adds or changes a `@Composable` function or other Jetpack Compose UI. |
| `reference/views.md` | The task adds or changes a custom `View` subclass, ViewBinding usage, or XML-layout-backed UI. |
| `reference/library-api.md` | The task changes a public or `internal` declaration in the `lib` module — binary compatibility, `@Jvm*` annotations, consumer ProGuard rules. |
| `reference/ui-contract.md` + `reference/ui-implementation.md` | The task adds or changes a screen, component, list or other UI (Compose or Views) — decomposition, inputs/events, the recycling-collection rule (`RecyclerView` + `ListAdapter` + `DiffUtil` / `Lazy*` with `key`), UI tests, the screenshot matrix and accessibility checks. |

Read only the framework file(s) that actually apply — a task inside `app`'s Compose screens reads `compose.md`
and not `views.md` or `library-api.md`; a task changing `lib`'s public surface reads `library-api.md` in addition
to whichever of `compose.md`/`views.md` its UI (if any) uses. Any task that touches UI also reads `ui-contract.md`
and `ui-implementation.md`. Don't read the optional files speculatively — for a
change confined to the body of one existing non-UI function with no signature change, `code-style.md` plus
`testing.md` is the whole list.

If `reference/` is missing (e.g. the skill directory was copied without it), proceed using this repository's own
`CLAUDE.md` conventions and the surrounding code's existing style, and say so in Step 8's report — don't guess at
what the reference would have said.

## Step 2 — Re-check the current code state

Read the actual current content of the file(s) the task touches before editing — don't assume any "current code
state" notes passed in with the task are still accurate; other tasks in the same bucket may be landing
concurrently, or the file may have changed for unrelated reasons.

Also note what the surrounding file and package already do about the things `reference/` has opinions on:
whether `internal` is actually used for non-public declarations, whether nullable types are handled with `?.`/
`?:` or with `!!`, how members are ordered, how errors are reported. The reference's rules are the default for
new code; a file that consistently does something else wins, and the divergence goes in Step 8's report rather
than becoming an unrequested restyle.

## Step 3 — Implement exactly what the task specifies

The named file(s), the described class/function/property, the behavior, the interface/hook it implements. Apply
`reference/code-style.md` and whichever files Step 1 selected, plus this repository's own `CLAUDE.md`
conventions where they are more specific (Kotlin/AGP version, license header expectations, banned dependencies).

Non-negotiable, because a later gate enforces them:

- **Full KDoc** on every public and `internal` class, object, interface, function, and property, with `@param`
  (including every type parameter), `@property` for primary-constructor properties, `@return`, and `@throws` for
  every exception a caller can handle. The `@throws` list is what Step 4's tests are written against.
- **No `!!`.** A non-null assertion converts a recoverable `null` into an unhandled `NullPointerException` at the
  worst possible moment. Use `?.`, `?:`, a smart-cast guard, or `requireNotNull`/`checkNotNull` with a message
  instead. A pre-existing `!!` outside the task's scope is not this task's problem to fix.
- **`internal` as the default visibility for a non-public declaration in the `lib` module**, `public` only for
  what the module's consumers are meant to call, `private` for everything else — see
  `reference/library-api.md` for what "meant to be called" implies for binary compatibility. In the `app`
  module, where nothing is published, `private`/default (package-level `internal`) is the default instead and
  `public` is rarely needed.
- **`val` over `var`** for every property and local that is never reassigned.
- **No new runtime dependency** unless the task explicitly calls for it. If one seems unavoidable, that's a
  blocker to report (Step 6), not a decision to make quietly.

- **UI tasks: decompose first.** Before writing a screen or component, write its `Screen › Region › Component`
  tree (`reference/ui-contract.md` §2 — inputs, events, visual states, what it reuses) and keep it for Step 8's
  report; build leaves first, collections through the recycling container (`ui-implementation.md`), navigation
  only in the graph.

Don't add anything the task didn't ask for — no speculative abstractions, no unrelated cleanup, no restyling of
code the task doesn't touch.

## Step 4 — Write or update the tests

Follow `reference/testing.md` and the existing test style in the same package (JUnit 4, MockK, Robolectric for
anything touching the Android framework). Cover the new/changed behavior, including the edge cases implied by
the KDoc `@throws` contracts (e.g. invalid-argument or null-handling cases).

For a UI task, also write the UI tests `reference/ui-implementation.md` describes: component/screen tests on
Robolectric for every visual state, with minimal/typical/many-items and right-to-left fixture data, capturing each
state across the `.ui-quality.yml` directions × themes through the module's `testing/UiScreenshots.kt` helper
(create it from `ui-implementation.md` when the module has none) and running the accessibility check at the
configured level; a flow test in `src/androidTest` only when the task adds or changes a flow.

Do not run the test suite yourself — `iru-android-code-one-task-group` runs it once for the whole bucket in its
own consolidated validation pass; this skill only compile-checks (Step 5).

## Step 5 — Compile-check the touched module

Unlike a Maven/`javac` build, an IDE-less Kotlin edit has no cheap way to know it actually compiles until Gradle
says so, and a broken compile in one task blocks every other task in the same parallel bucket from validating.
Run, for the module Step 1 detected:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # or -v 17; the machine's default JDK may be too new for AGP/Gradle
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
./gradlew :<module>:compileDebugKotlin :<module>:compileDebugUnitTestKotlin
```

(`gradle ...` if the repository has no committed wrapper yet.) This compiles both the main source set and the
unit-test source set the task just touched — nothing more: no lint, no full `test`/`testDebugUnitTest`, no
coverage, no `assemble`. Those belong to `iru-android-code-one-task-group`'s consolidated validation, and running
them here would repeat work once per task instead of once per bucket.

- **Compile failure in code this task wrote or edited**: fix it — it's the task's own code, not a validation
  finding to defer.
- **Compile failure caused by something outside the task's scope** (a sibling task's in-flight edit in the same
  parallel bucket, a pre-existing error in a file the task didn't touch): don't fix unrelated code; note it in
  Step 8's report so the caller can tell a real regression from bucket-mate noise, and re-run the compile check
  once more before reporting if it looks like a transient race with a concurrently-writing sibling task.
- If Gradle cannot run at all here (no SDK, no network for the first dependency download, wrapper missing), say
  so in Step 8 as "compile-check unavailable" rather than blocking the task on it — the group-level validation in
  `iru-android-code-one-task-group` will still catch a real compile error when it runs the full suite.

## Step 6 — When to interrupt the user

Keep interruptions rare — most tasks should complete unattended. Stop and use `AskUserQuestion` (or plain text if
no real choice is being offered) only when:

- Implementing the task reveals it's ambiguous in a way that changes correctness or scope and can't be safely
  inferred — conflicting instructions, a choice only the user can make (e.g. which of two APIs to break), or a
  missing decision. Don't ask about things with an obvious best-practice answer.
- The task's described approach appears infeasible against the real code as it stands (e.g. it names a class or
  member that doesn't exist and isn't a trivial typo) — not something you can resolve by writing the code
  slightly differently.
- The module detected in Step 1 is genuinely ambiguous in a way that changes which reference file and
  conventions apply (rare — most tasks name their own file path).

Do not stop merely because the task is nontrivial — implement it. Do not report the task done to work around a
blocker; report it as blocked instead (Step 8), with enough detail — what was tried, what failed, why — for the
caller to surface it. A conflict between `reference/` and the surrounding code is *not* a reason to stop: follow
the surrounding code and report the divergence.

## Step 7 — Check off this task's checkbox now

If this task did not stop on a blocker (Step 6), update `implementation_plan.md` at the repository root before
reporting back — don't leave this for the caller. Re-read the file fresh (not any earlier cached view — other
tasks in the same bucket may be landing concurrently) and locate this task's own checkbox line by the exact text
passed in with the task. Flip its `[ ]` to `[x]`, and do the same for every sub-task checkbox that was part of
the work just completed, adding a short note naming the files touched, e.g. `- [x] Task 2. **Implement
`CameraOrientationResolver`** — implemented, tests added; group validation pending.` — the same "pending" wording
`iru-android-code-one-task-group` uses, since the coverage/quality outcome isn't known until it validates the
whole bucket in its own steps. Edit only this task's own line(s) — never rewrite surrounding lines or other
tasks' checkboxes, since sibling tasks in a parallel bucket may be editing this same file at nearly the same
moment. This is what lets an interrupted run resume without re-attempting a task whose implementation and tests
already landed, even if the interruption happened before `iru-android-code-one-task-group` got to run its own
group-wide validation.

If this task stopped on a blocker instead, leave its checkbox untouched — there's nothing finished to mark done.

## Step 8 — Report the outcome

Hand control back to the caller with a short summary:

- **The module Step 1 detected** (`lib`/`app`) and the reference file(s) actually read.
- **The file(s) touched** and **the tests added/updated** (not run — see Step 4).
- **For a UI task**: the `Screen › Region › Component` decomposition, and the UI tests and screenshot ids
  (`<kind>/<Id>/<state>`) they capture.
- **The compile-check result** from Step 5 (passed / failed on this task's own code and was fixed / failed for a
  reason outside this task's scope / unavailable on this machine).
- **Whether this task stopped on a blocker** per Step 6, with enough detail for the caller to surface it.
- **Any deliberate deviation from `reference/`** — e.g. matching a file that still uses `!!`, keeping a
  declaration `public` because it's genuinely part of the library's consumer-facing API, or adding a member to a
  file whose existing order is already inconsistent — so the caller can see it was a decision rather than an
  oversight. Say so here too if `reference/` was missing entirely (Step 1).

This is what `iru-android-code-one-task-group` records for this task before running its own consolidated
license/KDoc/test/coverage/quality validation across the whole bucket; this skill has already checked off this
task's own checkbox with a pending-validation note (Step 7) — the caller only needs to replace that note with the
final validation outcome once the whole bucket passes, not create the checkbox entry itself. This skill itself
never captures a quality baseline and never runs the full test suite, coverage, lint, or detekt/ktlint checks.
