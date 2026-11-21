---
name: iru-swift-code-one-task-group
description: "Invoked as /iru-swift-code-one-task-group <bucket text> (group tasks, Parallelizable verdict, code state) via Skill. Implements one Swift (package or Apple app) task bucket: one baseline, iru-swift-code-one-task per task (parallel when allowed), then validates once (license, DocC, tests, 80% coverage, quality, simulator-locked UI stage); finalizes checkboxes."
model: sonnet
allowed-tools: Read Edit Write Bash(swift *) Bash(xcodebuild *) Bash(xcrun *) Bash(xcodegen *) Bash(tuist *) Bash(mkdir *) Bash(rmdir *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *) Skill Agent
---

# Implement one task-group bucket (Swift)

Carry out every Swift task in one plan-group bucket end to end: capture one quality baseline for the whole bucket
up front, implement each task (in parallel where safe), then validate everything the bucket touched in a single
consolidated pass instead of once per task. It is the Swift Package Manager / Apple-app counterpart to
`iru-java-code-one-task-group`/`iru-typescript-code-one-task-group`/`iru-android-code-one-task-group` — same
shape, but built on `iru-swift-code-quality`, `iru-swift-test`, `iru-swift-coverage`, and `iru-swift-docc`, and
aware that an app target's test/coverage/full-suite run needs a Simulator that only one run may drive at a time.
It uses a medium model on purpose: the plan already carries the hard reasoning; this skill drives execution of it.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 1 — Detect the project shape and touched target(s), then capture one pre-change quality baseline

Before implementing anything, detect the project shape once, the same way `iru-swift-code-one-task`'s own Step 1
does: `Package.swift` alone (no `project.yml`/`Project.swift`/`*.xcodeproj`/`*.xcworkspace` nearby) → **package**;
any of those found → **app**. For an app, also resolve, once, the same way `iru-swift-test`'s Step 3 does: the
**scheme(s)** (`xcodebuild -list`, preferring one that doesn't contain "Tests"/"UITests" in its name) and each
scheme's **destination**. With `iru-setup-apple-app`'s per-platform schemes (`<AppName>-iOS`, `<AppName>-macOS`,
`<AppName>-watchOS`, `<AppName>-visionOS`; ignore XcodeGen's `Core` package scheme) the bucket's schemes are those of
the platforms whose `Targets/<Platform>/` files it touches — plus, for the full-suite run (Step 3.6), every platform
scheme. Destinations come from `iru-swift-test`'s `resolve_destination`: `platform=macOS` for a macOS scheme, else
`platform=<iOS|watchOS|visionOS> Simulator,name=<device>,OS=<runtime version>` — the newest installed runtime the
selected Xcode supports, **always with `OS=`** because device names repeat across runtimes (`xcrun simctl list` can
be slow; give it a generous timeout). A platform with no installed runtime — typically **visionOS**, whose runtime is
a separate Xcode download (`xcrun simctl list runtimes` shows none) — gets no destination: record that scheme as
**"unverified (no visionOS simulator runtime installed)"**, compile-check it with `xcodebuild build-for-testing
-scheme <AppName>-visionOS -destination 'generic/platform=visionOS Simulator'`, and never download a runtime. Record
project kind + the scheme→destination map once and reuse it for every gate below via `kind:`/`scheme:`/`destination:`
lines (one gate call per scheme), instead of re-deriving them per gate call. Also check now whether a full Xcode install is active
(`xcode-select -p` points at `.../Xcode.app/Contents/Developer`, not just the Command Line Tools) — an app-kind
bucket on a Command-Line-Tools-only machine will need Step 3's test/coverage/full-suite stages reported as
unverified rather than a false pass.

A **package** whose `Package.swift` declares an `.executableTarget` depending on `ArgumentParser` is a
**command-line tool** (`iru-explore`'s `Project type: cli-tool (swift)`; the `iru-setup-swift-cli` layout): record the
executable target `<Bin>`, its logic library `<Bin>Kit`, the in-process test target `<Bin>KitTests` and the end-to-end
test target `<Bin>CLITests` (it runs the built binary through `Process`). It is package-kind in every respect below —
`swift test`, no simulator, no `.simulator-test.lock`, no containers — and its 80% coverage bar is measured on the
`Sources/<Bin>Kit/` files (plus any `Sources/<Bin>/` file the bucket touched, which the spawned binaries cover).

Then determine which target(s) this bucket's tasks touch, using the same heuristic `iru-swift-code-one-task`'s own
Step 1 uses per task: a path under `Sources/<Target>/` or `Packages/Core/Sources/<Target>/` wins; a path under a
per-platform app target directory names that target; otherwise infer from what each task describes. Record this
target set once and reuse it for Step 3's DocC/coverage/quality scoping.

Collect the set of file(s)/type(s) every task in this bucket is expected to touch (from each task's own
description). Capture a single pre-change quality baseline covering all of them by delegating to the
`iru-gate-runner` agent rather than running `iru-swift-code-quality` directly: `Agent({description: "Capture
pre-change quality baseline for task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill:
\"iru-swift-code-quality\", args: \"<file1,file2,...>\"}), then report back only the list of issues found for these
files, broken down by tool (SwiftLint/formatter/Periphery) and whether each tool was wired."})`. Record the
returned issues as this bucket's pre-change baseline — an empty baseline if every file is new. This is what Step
3.8's quality check compares against. Skip this baseline capture if `iru-swift-code-quality` is unavailable.

## Step 2 — Implement each task via `iru-swift-code-one-task`

For every task in the bucket, invoke `iru-swift-code-one-task` — it detects the task's own project shape and
target, implements exactly what the task specifies, writes/updates its tests, and compile-checks the target (via
`swift build --build-tests` or `xcodebuild build-for-testing`, which builds but never boots a simulator to run
anything — so it never needs Step 3's simulator lock); license headers, DocC, and all further validation live here.

- **Bucket marked `Parallelizable: yes`**: invoke `iru-swift-code-one-task` for every task in the bucket
  concurrently — issue all of the `Agent` calls below together, in the same response:
  ```
  Agent({
    description: "Implement <task N> via swift-code-one-task",
    subagent_type: "iru-isolated-skill-executor",
    prompt: "Invoke Skill({skill: \"iru-swift-code-one-task\", args: \"<the task's full text, including its exact
      implementation_plan.md checkbox line(s) for itself and its sub-tasks, its sub-tasks' own text, and any
      relevant Current code state context>\"}). Report back: the project shape/target detected, the files touched,
      the tests added/updated, the compile-check result, and whether the task stopped on a blocker instead of
      finishing.",
    run_in_background: false
  })
  ```
- **Bucket marked `Parallelizable: no`**: invoke them one at a time, in the plan's order, waiting for each to
  finish before starting the next.

`iru-swift-code-one-task` already checks off that task's own checkbox (and its sub-tasks') in
`implementation_plan.md` itself, with a "group validation pending" note, before it reports back. As each task's
agent reports back, re-read `implementation_plan.md` and confirm that box is actually checked; if it isn't (a rare
concurrent-write race), flip it yourself now with the same note. Notify the user that this specific task's
implementation and tests landed.

If a task's agent reports it stopped on a blocker, record it and skip validating that specific task's code in
Step 3 — surface it in Step 5's report without blocking the rest of the bucket's already-finished tasks.

## Step 3 — Validate the whole bucket once

Once every task that didn't block has been implemented, run the following once for the entire bucket:

1. **License headers**, for every file the bucket added or modified, via `iru-gate-runner`: `Agent({description:
   "Add license headers for task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill:
   \"iru-check-license\", args: \"<file1,file2,...>\"}) scoped to every file this bucket's tasks added or modified.
   Report back only which files were missing a header vs. fixed vs. already compliant, and — if no header
   convention existed anywhere in the repo — whether the user chose to skip or generate one."})`. Respect a skip
   choice for the rest of the run. Skip if `iru-check-license` isn't installed.
2. **DocC**, for every type/file the bucket added or modified, via `iru-gate-runner`: `Agent({description: "Update
   DocC for task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-swift-docc\", args:
   \"<Type1,Type2,...>\"}) scoped to every type this bucket's tasks added or modified. Report back only which
   members were documented/completed and in which files, whether any target's DocC catalog landing page had to be
   created or extended, and whether the symbol-graph audit and doc-build verification passed."})`. If it reports
   the build failed, fix and re-invoke until it reports success. Skip if `iru-swift-docc` isn't installed.
3. **Acquire the simulator lock** — app-kind buckets only; a package needs no simulator and skips 3a/3d entirely
   (a command-line tool included: its `<Bin>CLITests` spawn the tool as a plain child process — no simulator, no
   desktop session, nothing shared to serialize).
   `xcodebuild test` (scoped, coverage, and full-suite runs below all use it) drives a Simulator destination that
   only one run may occupy at a time — two overlapping `xcodebuild test` runs against the same destination collide
   on boot state and produce results that are unreliable rather than merely slow, the same reasoning
   `iru-java-springboot-code-one-task-group`'s integration-test lock uses for its shared compose stack, applied
   here to a shared simulator instead. A **macOS app** bucket (`platform=macOS` destination, e.g. a `macos-app`)
   takes the same lock even though it boots no simulator: its XCUITest run drives the real desktop session —
   keyboard focus, mouse and menu bar — so two overlapping runs (or a UI run racing a unit-test run that also
   launches the app) steal each other's focus and fail unpredictably:
   ```bash
   mkdir .simulator-test.lock 2>/dev/null && echo ACQUIRED || echo HELD
   ```
   - **`ACQUIRED`**: immediately write `.simulator-test.lock/owner` (via the `Write` tool) with this bucket's
     identifying text (task numbers/titles, target, scheme), and `date -u +'%Y-%m-%dT%H:%M:%SZ'`. Ensure
     `.simulator-test.lock/` is listed in the repository's root `.gitignore`, adding it if missing — the lock is
     transient local state and must never be committed.
   - **`HELD`**: another task group is running simulator tests right now. Tell the user this bucket is waiting
     (naming the holder from `cat .simulator-test.lock/owner` if readable), then poll: re-run the `mkdir` roughly
     every 30 seconds. Do not proceed and do not run `xcodebuild test` anyway.
   - **Stale lock**: `find .simulator-test.lock -maxdepth 0 -mmin +60` returning the directory means its holder has
     been running over an hour — for a simulator run that almost certainly means it died without releasing. Tell
     the user what the owner file says, break it (`rm -f .simulator-test.lock/owner && rmdir .simulator-test.lock`),
     and acquire fresh. Say so in Step 5.
   - **Wait timeout**: still held after roughly 45 minutes of polling and not yet stale — stop waiting, report
     Steps 3.4/3.5/3.6/3.7's app-kind stages as **unverified — blocked by a concurrent simulator run**, and continue to
     Step 3.9 (quality needs no simulator; Step 3.8's lock release is a no-op when the lock was never acquired). Name it in Step 5.
4. **Scoped tests**, for every test affected across the bucket, via `iru-gate-runner`: `Agent({description: "Run
   tests for task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-swift-test\", args:
   \"<selector covering every test affected by this bucket>\\nkind: <package|app>\\nscheme: <scheme>\\ndestination:
   <destination>\"}) (fall back to `swift test --filter '<pattern>'` / `xcodebuild test -only-testing:<...>
   -destination '<destination>'` directly if the swift-test skill is unavailable). If everything passes, report
   back only that all tests passed. If anything fails, report back only the failing test names, the failure
   reason, and the first message/stack-trace line for each."})` (omit `scheme:`/`destination:` for a package). If
   failures are reported, identify which task's code they trace back to, fix that task's implementation and/or
   tests, and re-invoke this same check — repeat until it reports all tests passed.
   For a command-line tool, the selector covers both layers: the `<Bin>KitTests` suites for the changed Kit types and
   the `<Bin>CLITests` cases for every command the bucket added or changed (`swift test` builds the executable first
   because `<Bin>CLITests` depends on it).
   With several in-scope schemes, make one call per scheme (each with its own `scheme:`/`destination:`), all under
   the same lock; a scheme recorded "unverified (no … simulator runtime installed)" in Step 1 is skipped here and
   reported as such — never as passed.
5. **Coverage**, for every changed/new file/type across the bucket, via `iru-gate-runner`: `Agent({description:
   "Check coverage for task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill:
   \"iru-swift-coverage\", args: \"<File1.swift,Type2,...>\\nkind: <package|app>\\nscheme: <scheme>\\ndestination:
   <destination>\"}). Report back, per target file/type, its unit-test line coverage percentage and function
   coverage percentage; branch coverage is unavailable for Swift and must be reported as such, never as 0%."})`.
   In an app project, files under a local package (`Packages/Core/...`) go in a **separate** call with
   `package-path: Packages/Core` instead of `kind`/`scheme`/`destination` — the app scheme's `xccov` report can't
   see a statically linked local package, so that call measures it with `swift test` + `llvm-cov` (no simulator, no
   lock needed). For a macOS desktop app (`iru-setup-macos-app` layout) `Packages/Core` is where the 80% bar applies;
   app-target UI code (views, view controllers, `App` scenes) is reported "not measured" — never 0%, and never part of the bar.
   For a **command-line tool**, scope the call to the bucket's `Sources/<Bin>Kit/` and `Sources/<Bin>/` files with
   `kind: package` and ask for the full suite (no filter) with **every** test binary — `<Bin>KitTests` first and
   `<Bin>CLITests` via `-object` — so `llvm-cov` sees the profiles of the binaries the CLI tests spawn (the scaffold's
   `ToolProcess` forwards `LLVM_PROFILE_FILE`; without that forwarding the executable target reads 0% and a stray
   `default.profraw` lands in the package root — report it rather than counting the thin commands as uncovered). The
   bar applies to the Kit files; a `Sources/<Bin>/` command file below it is closed with a `<Bin>CLITests` case, never
   by moving logic out of the Kit.
   The bar is **80% line coverage**; branch coverage is never part of it. For anything under 80%, add tests for its
   uncovered lines (via the task that owns it, from the ranges `iru-swift-coverage` reports), re-run Step 3.4 to
   confirm, then re-check coverage here.
6. **Full suite**, to catch regressions elsewhere, via `iru-gate-runner`:
   - **Package** (a command-line tool's `<Bin>CLITests` end-to-end target is part of this run — never filter it out):
     `Agent({description: "Run full test suite", subagent_type: "iru-gate-runner", prompt: "Run
     `swift test` with no filter. If everything passes, report back only that all tests passed. If anything fails,
     report back only the failing test names, the failure reason, and the first message/stack-trace line for
     each."})`.
   - **App**: `Agent({description: "Run full test suite", subagent_type: "iru-gate-runner", prompt: "Run `rm -rf
     <path>.xcresult && xcodebuild test -scheme '<scheme>' -destination '<destination>' -resultBundlePath
     <path>.xcresult`, then parse it via `xcrun xcresulttool get test-results summary --path <path>.xcresult`. If
     everything passes, report back only that all tests passed. If anything fails, report back only the failing
     test names and the first line of each failure's message."})` — still under the lock acquired in 3.3. With
     per-platform schemes, run it once per platform scheme from Step 1's map (each with its own `OS=`-pinned
     destination and result bundle); a scheme without a runtime is reported "unverified (no visionOS simulator
     runtime installed)" after its `build-for-testing` compile check.
   Fix any regression, then re-invoke until it reports all tests passed.
7. **UI stage** — only when the bucket added or changed a screen, component or list (SwiftUI/UIKit/AppKit views, view
   controllers, their UI tests; never for a command-line tool, which has no UI), still under the lock from 3.3 for an
   app. Read `.ui-quality.yml` at the repository root (missing (new UI project) = `accessibility: enforce`, `directions: [ltr, rtl]`,
   `themes: [light, dark]`), then via `iru-gate-runner`: `Agent({description: "Run UI tests for task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-swift-test\", args: \"ui <the bucket's
   component-test suites and UI-test classes>\\nkind: <package|app>\\nscheme: <scheme>\\ndestination:
   <destination>\\nlock: held\"}). Report back only pass/fail with failing test names, the screenshot count per
   kind, malformed screenshot names, the accessibility issues (count + audit-type rule ids) and the `.ui-quality.yml`
   level applied."})` — a scoped run, so it never clears `.ui-screenshots/`. Then check the captures yourself
   (`find .ui-screenshots/<kind>/<Id> -name '*.png'`): every screen/component the bucket added or changed has a file for
   each of its visual states (the decomposition each task reported) × every configured direction × theme (watchOS:
   `dark` only; a `--<platform>` variant per platform in a multiplatform app). A missing cell is a gap in that task's
   UI tests — add the test (via the owning task) and re-run this stage. Accessibility: `enforce` → every audit issue
   fails the group like a test failure (fix the view, re-run); `report` → list the issues in Step 5, never failing;
   `off` → note that the audit didn't run. No bootable simulator runtime for a scheme, a macOS UI run without the
   Automation/Accessibility permission or a logged-in desktop session, a lock wait timeout, or a non-macOS host →
   this stage is **unverified** with the reason (never skipped silently, never passed); component captures from a
   package's `swift test` still run on any macOS host.
8. **Release the simulator lock** — app-kind buckets only, as soon as Step 3.7 (or the last fix-and-re-run of
   3.4–3.7) has finished: `rm -f .simulator-test.lock/owner && rmdir .simulator-test.lock`. Release on every exit
   path — tests passed, they failed, a task turned out blocked, or this skill is about to report a hard stop. A
   lock left behind blocks every other app-kind bucket for a full hour until Step 3.3's staleness escape fires.
9. **Code quality**, for the same file/type set as Step 1, via `iru-gate-runner`: `Agent({description: "Check for
   new quality issues in task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill:
   \"iru-swift-code-quality\", args: \"<file1,file2,...>\\nbaseline: <baseline from Step 1>\"}). Report back only the
   issues that are newly appearing (not present in the baseline), broken down by tool (SwiftLint/formatter/
   Periphery)."})`. Any issue reported is a regression introduced by this bucket — trace it to the task that owns
   the affected file, fix it (then re-run 3.4–3.7 to confirm the fix broke nothing), and re-check until none
   remain. Leave a new issue in place only if fixing it would contradict what its task explicitly specifies —
   record that, and why, for Step 5. Skip if `iru-swift-code-quality` isn't installed.

**Accessibility opt-out (`accessibility: none`).** When `.ui-quality.yml` says `none`, the user opted out of accessibility support entirely (see `reference/ui-contract.md` §8, "Opting out and existing code"): do not apply the accessibility rules, do not run or require the accessibility checks (axe, ATF/semantics audit, `performAccessibilityAudit()`, Axe.Windows, a11y lint rules, TUI asserted checks), and report them as "accessibility: opted out" in the group report - never "unverified", and never a failure. `off` differs: the rules still apply when writing code, only the automated checks are not run.

**Missing `.ui-quality.yml` in a repository that already has UI code.** The level was never chosen, so don't assume the `enforce` default — that default is only for a new UI project with no UI code yet. Before the first UI task of the bucket, resolve it once per `reference/ui-contract.md` §8 ("Opting out and existing code"): reuse the `Accessibility:` line of an `iru-explore` report in the plan's "Current code state" or this conversation (otherwise apply the detection rules of `iru-explore`'s `reference/accessibility-detection.md`), ask the user via `AskUserQuestion` with the setup's pre-selection (`present` → Enforce, `partial` → Report only with the mixed-compliance warning, `absent` → None with the half-compliance warning), and write `.ui-quality.yml` with the chosen level and the default `directions`/`themes`, so the per-task skills and the test gates read the same value. This is a decision only the user can make, so it is an allowed pause.

## Step 4 — Finalize progress notes and notify the user

Once Step 3 passes clean for the bucket (or leaves only explicitly-accepted issues), update each task's note in
`implementation_plan.md` — replacing the "group validation pending" placeholder — with the final outcome: line
coverage achieved and code-quality result for that task's own file(s) — plus, for a UI task, the Step 3.7 outcome
(captures per state × direction × theme, the accessibility result at the configured level, or "UI stage unverified:
<reason>") — e.g. `- [x] Task 2. **Implement `RouteValidator`** — tests in RouteValidatorTests (94% line coverage),
no new SwiftLint/format issues.` or `… — OrderRow: 12 captures (3 states × ltr/rtl × light/dark), accessibility audit
clean (enforce).` If any
stage was unverified (Command-Line-Tools-only machine, lock wait timeout, no visionOS simulator runtime installed),
say so in the note rather than implying it passed. Save the file, then notify the user that group validation completed and summarize the bucket-wide
result. Before writing these notes, confirm the simulator lock is released (Step 3.8) if it was ever taken.

If any task was recorded as blocked in Step 2, leave its checkbox unchecked and its blocker note in place.

## Step 5 — Report the bucket's outcome

Hand control back to the caller (`iru-code-one-task-group`) with a summary covering the whole bucket: per task, the
target it lives in, the files touched, tests added/updated, line coverage achieved, and code-quality outcome —
including whether a new issue was left in place as unavoidable and why, whether license-header/DocC-catalog work
was needed, whether any stage was reported unverified (and why — no full Xcode install, lock wait timeout, a
platform scheme with no simulator runtime such as visionOS), how the
simulator lock behaved whenever it was anything other than an immediate uncontended acquire (wait time, a stale
lock broken, confirmation it was released), the UI stage outcome when it ran (screenshot counts per kind, missing
matrix cells, accessibility issues by rule id at the `.ui-quality.yml` level, or why it was unverified), and which
task(s), if any, stopped on a blocker.
`implementation_plan.md`'s checkboxes are already checked and finalized (Steps 2 and 4) and the user-facing
notifications have already gone out — the caller does not need to redo that bookkeeping for this bucket.

**Warn explicitly**: an app-kind bucket needs a full Xcode install (not just Command Line Tools) and, for a
simulator destination, a matching runtime already downloaded — a missing one fails at destination resolution,
before any test runs, and looks unrelated to the code itself (a macOS app bucket needs no simulator runtime, but its
UI tests need the test runner's Accessibility/automation permission and an unlocked, logged-in desktop session —
without them the UI-test stage is reported unverified, not passed; on a non-macOS host every app stage is
"unverified (needs macOS)"); SwiftLint/the third-party SwiftFormat/Periphery may
be entirely absent on a machine with no Homebrew/Mint, in which case `iru-swift-code-quality` reports them as not
wired rather than clean, and this skill's own quality diff in Step 3.9 reflects only whichever tools actually ran.
This skill's own author did not run any of the `swift`/`xcodebuild` commands above against a real scaffold — no
verification sub-task was assigned for writing this skill itself — so every gate command and report path named
above is **unverified locally by this skill's author**; each is exercised instead by the underlying gate skill's
own verification (see `iru-swift-test`/`iru-swift-coverage`/`iru-swift-code-quality`/`iru-swift-docc`'s own "Known
quirks" sections), not by a dry run of this orchestration skill.
