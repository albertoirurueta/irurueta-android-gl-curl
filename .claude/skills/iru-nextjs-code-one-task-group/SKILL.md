---
name: iru-nextjs-code-one-task-group
description: "Invoked as /iru-nextjs-code-one-task-group <bucket text> (group tasks, Parallelizable verdict, code state) via Skill. Implements one Next.js task bucket: one baseline, iru-nextjs-code-one-task per task (parallel when allowed), then validates once (license, TSDoc, tests, 80% coverage, lint/tsc, next build routes, locked Playwright); finalizes checkboxes."
model: sonnet
allowed-tools: Read Edit Write Bash(npm *) Bash(npx *) Bash(pnpm *) Bash(yarn *) Bash(node -p *) Bash(perl *) Bash(lsof *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *) Bash(date *) Bash(mkdir .integration-test.lock*) Bash(rmdir .integration-test.lock*) Bash(rm -f .integration-test.lock/owner) Skill Agent
---

# Implement one task-group bucket (Next.js)

Carry out every Next.js task in one plan-group bucket end to end: one quality baseline up front, implement each task
(in parallel where safe), then validate everything the bucket touched in a single consolidated pass instead of once
per task.

It is built on the same TypeScript gate skills as `iru-typescript-code-one-task-group` — `iru-typescript-test`,
`iru-typescript-coverage`, `iru-typescript-code-quality`, and `iru-typescript-tsdoc`, all of which already detect
Next.js (`next typegen` before `tsc`, flat-config ESLint on 16, Playwright only on an explicit `e2e` selector) —
each invoked through an `iru-gate-runner` agent so its report never lands in this context. Prefer those skills over
a hand-written command wherever they're installed; fall back to an explicit command only when one isn't.

Three things differ from the plain TypeScript group skill, and they shape the whole validation order:

1. **The production build is a gate.** Type-checking and unit tests can't see what Next.js decides at build time:
   whether a route prerenders, whether a Cache Components route has request data outside `<Suspense>` (a build
   error), whether a Server Component imports a client-only module. Step 3.7 runs `next build` once and reads its
   route table, because a route silently turning from static to dynamic is a regression no other check reports.
2. **The coverage gate is measured from unit tests alone.** Playwright drives a browser against a production
   server and produces no coverage for the source files — it can never satisfy the gate. Unit coverage is the
   number the bucket's own tasks control, it is available whether or not a browser is, and a file reachable only
   through e2e is a file with no fast feedback loop, which is a finding to surface rather than hide.
3. **The build and e2e stages are serialized.** `next build` refuses a second concurrent run in the same project
   (`⨯ Another next build process is already running.`), and Playwright's `webServer` builds and serves the same
   `.next/` directory on a fixed port. Two groups doing either at once fail or — worse — test each other's build.

It uses a medium model on purpose: the plan carries the hard reasoning; this skill drives execution of it.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Read this before running `next build` or Playwright

**Only one `next build` or Playwright run may be in flight against this project at any moment.** Both write
`.next/`; Playwright's `webServer` (typically `npm run build && npm run start -- --port <port>`) also binds a port,
and with `reuseExistingServer: !process.env.CI` it will silently test whatever is already listening there — another
group's server, or a developer's `next dev` — and report its result as yours. The outcomes are indistinguishable
from real results, so this is a serialization rule, not a performance note.

It applies at three levels:

1. **Across buckets/groups**: enforced by the `.integration-test.lock` directory (Step 3.7a), shared with every other
   catalog group skill that runs servers or containers in this repository.
2. **Within this bucket**: Step 2's per-task agents never run tests, builds, or servers (the per-task skill forbids
   it), so the only build and e2e runs are Steps 1 (optional baseline build), 3.7, and 3.8.
3. **Within the run**: never start a build and an e2e run in parallel agents, and never add Playwright `--workers`
   or sharding flags the project's own `test:e2e` script doesn't use.

## Step 1 — Detect the profile once, capture the baselines

**Detect once** and reuse for every step below, instead of letting each gate re-derive it: the Next.js version
(`node -p "require('next/package.json').version"`, else the `package.json` range), the router(s) (`app/`/`src/app/`,
`pages/`/`src/pages/`), `cacheComponents` in `next.config.*` (→ profile `14`, `15`, `16-prev`, or `16-cc`), `output`
(`export`/`standalone`), the unit runner (Vitest config, or Jest via `next/jest`), Playwright (`@playwright/test` +
`playwright.config.*`: its `testDir`, its `webServer` command and port, and whether `reuseExistingServer` is set), the
project's `build` and `test:e2e` scripts, and the package manager. Name these facts in every gate prompt below.

**Quality baseline.** Collect the file(s) every task in this bucket is expected to touch and capture one baseline:

```
Agent({
  description: "Capture pre-change quality baseline for task group",
  subagent_type: "iru-gate-runner",
  prompt: "Invoke Skill({skill: \"iru-typescript-code-quality\", args: \"<file1,file2,...>\"}). This is a Next.js
    <version> project (<profile>): run `next typegen` before `tsc --noEmit` as that skill documents. Report back only
    the list of issues found for these files, by tool and rule id.",
  run_in_background: false
})
```

Record it as this bucket's baseline (empty if every file is new). Skip if `iru-typescript-code-quality` isn't
installed, and say so in Step 5.

**Route-table baseline.** Map the bucket's expected files to the routes they affect (see "Mapping files to routes"
under Step 3.7). If the bucket **modifies existing** routes — an existing `page`/`layout`/`route` file, a layout above
existing routes, a data module those routes import, `next.config.*`, or `proxy.ts`/`middleware.ts` — capture the
pre-change build table now, while the tree is still pre-change: acquire the lock exactly as in Step 3.7a, run the
build agent of Step 3.7 against the unchanged tree, record the parsed rows, release the lock (Step 3.8b). If the
build itself fails on the unchanged tree, record "baseline build fails: <first error>" — the bucket can't be blamed
for it, and Step 3.7 then compares against task intent only. If the bucket only **adds** new routes and touches
nothing shared, skip the baseline build: there is nothing to compare except intent, which Step 2's reports supply.

Also probe, now rather than after the implementation work: whether Playwright is configured at all (no config → Step
3.8 is "not configured", not "unverified"), and whether its browsers are present (`npx playwright --version` works
offline; a missing browser shows up as `Executable doesn't exist at ...` at run time — never run
`npx playwright install` yourself, it downloads browser binaries).

## Step 2 — Implement each task via `iru-nextjs-code-one-task`

- **Bucket marked `Parallelizable: yes`**: issue all the `Agent` calls together, in the same response:
  ```
  Agent({
    description: "Implement <task N> via iru-nextjs-code-one-task",
    subagent_type: "iru-isolated-skill-executor",
    prompt: "Invoke Skill({skill: \"iru-nextjs-code-one-task\", args: \"<the task's full text, including its exact
      implementation_plan.md checkbox line(s) for itself and its sub-tasks, its sub-tasks' own text, and any relevant
      Current code state context>\"}). Report back: the detected profile, the files touched, the tests added/updated,
      the expected next build classification of every route the task touched (and any mode change, with the
      reason), the files reachable only through e2e, any deviation from the skill's reference/ guidance, and whether
      the task stopped on a blocker. Do not run tests, next build, next dev, next start, or Playwright: sibling agents
      run concurrently and the group validates once.",
    run_in_background: false
  })
  ```
- **Bucket marked `Parallelizable: no`**: one at a time, in the plan's order, waiting for each.

**One ordering constraint regardless of the verdict**: when one task creates a module another imports — a Server
Action used by another task's form, a data-layer function used by another task's page, a shared layout — the
provider lands first. Run those two sequentially even in a parallel bucket and note it in Step 5.

As each agent reports back, re-read `implementation_plan.md` and confirm its box is checked; if not (a concurrent-
write race), flip it yourself with the same "group validation pending" note. Notify the user that this task's
implementation and tests landed. Keep each task's **expected route classification** — Step 3.7 compares against it.

If a task reports a blocker, record it and exclude its code from Step 3; surface it in Step 5 without blocking the
rest of the bucket.

## Step 3 — Validate the whole bucket once

Derive the scope first: the union of the files each agent reported, cross-checked against a fresh `git status`,
`git diff --name-only`, and `git diff --name-only --cached` (a side-effect edit to a barrel file, `next.config`, or a
shared layout must not escape validation). Run the gates in this order — cheapest first, and everything that can
gate the bucket before anything that builds or starts a server. **Steps 3.1–3.7 are gates; 3.8 is a gate when it can
run and an explicit "unverified" when it can't.**

1. **License headers** via `iru-gate-runner`: `Agent({description: "Add license headers for task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-check-license\", args:
   \"<file1,file2,...>\"}) scoped to every file this bucket added or modified. Report back only which files were
   missing a header vs. fixed vs. already compliant, and — if no header convention existed anywhere in the repo —
   whether the user chose to skip or generate one."})`. Respect a skip for the rest of the run. Skip if not installed.

2. **TSDoc** via `iru-gate-runner`: `Agent({description: "Update TSDoc for task group", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-typescript-tsdoc\", args: \"<file1,file2,...>\"}) scoped
   to every non-test file this bucket added or modified. This is a Next.js project: default-exported page/layout
   components need only a one-line summary. Report back only which exported symbols were documented/completed and
   in which files, and whether the doc build ran (TypeDoc, after next typegen) or the run was audit-only."})`. Fix and
   re-invoke until it passes. Skip if not installed.

3. **Scoped unit tests** via `iru-gate-runner`: `Agent({description: "Run unit tests for task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-typescript-test\", args: \"<file/directory
   selector covering every unit test affected by this bucket>\"}) — the unit suite only, no `e2e` selector (fall
   back to `npx vitest run <selector>` / `npx jest <selector>` if the skill isn't installed). If everything passes,
   report back only that. If anything fails, report back only the failing test names, the failure reason, and the
   assertion message for each."})`. Trace each failure to the task that owns the file, fix, re-invoke until green.

4. **Unit-test coverage — the 80% gate** via `iru-gate-runner`:
   ```
   Agent({
     description: "Check unit-test coverage for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Invoke Skill({skill: \"iru-typescript-coverage\", args: \"<selector covering the bucket's affected
       unit tests, plus every non-test source file this bucket changed or added>\"}). This is a Next.js project:
       coverage comes from the <Vitest|Jest> unit suite only — never run or count Playwright. Exclude **/*.d.ts,
       next.config.*, next-env.d.ts and .next/**. Report back, per target file, its line and branch coverage
       percentage, and flag any default-exported async page/layout file at 0%.",
     run_in_background: false
   })
   ```
   For any file under 80%, add **unit** tests through the task that owns it, re-run 3.3, re-check here.

   **Playwright never closes this gap**, and "an e2e test covers it" is not an argument for accepting a file under
   80% — that is exactly the case the gate exists to surface. Server Actions, Route Handlers, data-layer modules,
   Client Components, hooks, utilities, and `proxy.ts` logic are all directly unit-testable (`reference/testing.md`
   of the per-task skill shows how): no exceptions for them.

   The genuine exceptions — each named in Step 5 with its reasoning, never silently passed — mirror the "thin
   adapter" rule of the other group skills:
   - **A thin async Server Component** (`page`/`layout`/`template`/`default`) that only awaits params, calls one
     data function, and composes JSX, with no branching of its own — React Testing Library can't render async
     components, and calling it as a function only works for leaves. Accept it when its data function is
     unit-tested **and** it is either exercised by an e2e spec or the bucket's route classification for it is
     confirmed by Step 3.7. A page **with** branching (auth decisions, `notFound()` conditions, data shaping) is not
     thin: extract the logic into a tested function, or call the leaf as a function in a unit test.
   - **Framework wiring with no logic**: the root layout's font/metadata setup, static `loading`/`not-found` markup,
     `opengraph-image`/`icon` files, `instrumentation.ts` registration, a `proxy.ts` that only exports a `config`
     matcher (its function is tested).
   - **Generated code** (`.next/types`, `next-env.d.ts`) — appearing here means the coverage include/exclude globs
     need fixing, which is a configuration finding, not a coverage one.

5. **Full unit suite** via `iru-gate-runner`: `Agent({description: "Run full unit test suite", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-typescript-test\"}) with no selector (the unit suite only,
   never e2e), or fall back to `<npm|pnpm|yarn> test` if it isn't installed. If everything passes, report back only
   that. If anything fails, report back only the failing test names, the failure reason, and the assertion message
   for each."})`. Fix any regression and re-invoke until green.

6. **Code quality** — ESLint (or Biome) plus `next typegen && tsc --noEmit`, diffed against Step 1's baseline, via
   `iru-gate-runner`: `Agent({description: "Check for new quality issues in task group", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-typescript-code-quality\", args: \"<file1,file2,...>\"}).
   This is a Next.js <version> project: lint with the project's own linter (plain `eslint` on 16, never `next lint`
   on 16+), and run `next typegen` before `tsc --noEmit`. Compare the reported issues against this pre-change
   baseline: <baseline from Step 1>. Report back only the issues that are newly appearing, each with its tool, rule
   id, file, and line. A TS2304 for PageProps/LayoutProps/RouteContext means typegen didn't run — re-run it rather
   than reporting the error."})`.

   Any new issue is a defect this bucket introduced — trace, fix, re-run 3.3 and 3.5, re-check. Treat
   `@next/next/*` and `react-hooks/*` rules as defects, not style. Leave an issue only if fixing it would contradict
   what its task explicitly specifies; record why. Skip if not installed, and say so in Step 5.

7a. **Acquire the repository-wide lock**, immediately before 3.7 and never earlier:

   ```bash
   mkdir .integration-test.lock 2>/dev/null && echo ACQUIRED || echo HELD
   ```

   - **`ACQUIRED`** — write `.integration-test.lock/owner` (via `Write`) with this bucket's task numbers/titles, the
     stage (`next build + Playwright`), and `date -u +'%Y-%m-%dT%H:%M:%SZ'`. Ensure `.integration-test.lock/` is in
     the root `.gitignore` (add it if missing).
   - **`HELD`** — tell the user this bucket is waiting (naming the holder from `cat .integration-test.lock/owner`),
     then re-run the `mkdir` about every 30 seconds. Don't run the build "just to check" — it would collide.
   - **Stale lock**: if `find .integration-test.lock -maxdepth 0 -mmin +60` returns it, tell the user what the owner
     file says, break it (`rm -f .integration-test.lock/owner && rmdir .integration-test.lock`), acquire fresh, and
     note it in Step 5.
   - **Wait timeout**: still held after about 45 minutes and not stale → report the build and e2e stages as
     **unverified — blocked by a concurrent run**, skip to Step 4, name it in Step 5.

   A `mkdir` directory is used because it is atomic; a lock *file* written with `Write` has a read-then-write window
   and must not be used instead.

7. **`next build` and the route table** — the gate the other checks can't replace:
   ```
   Agent({
     description: "Run next build for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Run the project's production build exactly once: `<npm|pnpm|yarn> run build` when the build script
       wraps `next build` (it may run codegen first), otherwise `npx next build`, writing the full output to
       .next-build.log in the project root (create, then delete it before reporting). Do not run it twice and do not
       start a server. Then extract the route table with:
       perl -CSD -ne 'print \"$1 $2 $3\\n\" if /^[\\s\\x{2500}-\\x{257F}]*([\\x{25CB}\\x{25CF}\\x{0192}\\x{25D0}])\\s+(\\/\\S*)\\s*(.*?)\\s*$/' .next-build.log
       which prints one `<symbol> <route> <revalidate/expire columns>` line per row of the `Route (app)` and
       `Route (pages)` tables (○ static, ● SSG, ƒ dynamic, ◐ partial prerender), including the indented ● children of
       a generateStaticParams parent, and skips the legend, the bare parent row, and the `ƒ Proxy (Middleware)` row.
       Report back: whether the build succeeded; on failure, the first error block verbatim (for Cache Components
       errors such as `Route \"/x\": Next.js encountered uncached or runtime data during prerendering` or `Route
       segment config \"revalidate\" is not compatible with nextConfig.cacheComponents`, re-run once with
       `next build --debug-prerender` and include the file:line it names) and whether it is an environment failure
       (missing env var, unreachable database/API during prerender, `Another next build process is already running`)
       rather than a code error; on success, the parsed rows for these routes: <route list> plus any row whose symbol
       differs from this baseline: <baseline rows, or 'none'>. Do not dump the build log.",
     run_in_background: false
   })
   ```

   **Mapping files to routes** (for the route list): strip `src/` and the `app/` prefix, drop route groups
   `(name)`, parallel slots `@name`, and the trailing `page.tsx`/`route.ts` (root → `/`); `pages/x/index.tsx` →
   `/x`, `pages/api/**` → `/api/**`. A `layout.tsx` affects every route below it; a data module affects every route
   importing it (`grep -rl` for its import path); `next.config.*` and `proxy.ts`/`middleware.ts` affect all routes.

   Judge the result:
   - **Build failure from code** — a gate failure: trace it to the task, fix (`<Suspense>` around request data or
     `'use cache'` with `cacheLife` under Cache Components; remove a segment config the profile rejects; move a
     client-only import out of a Server Component), re-run 3.3–3.6 if production code changed, then re-run this
     step. Never "fix" a Cache Components blocking error with `export const instant = false` unless the task said
     the route may block.
   - **Build failure from the environment** — report the build **unverified** with the cause; Step 3.8 can't run
     either (its web server builds the same app). This doesn't undo 3.1–3.6.
   - **Rendering-mode comparison**, for every route in the list: compare the parsed symbol with the baseline row (if
     Step 1 captured one) and with the classification the owning task declared in Step 2. Flag as **unexpected**
     any route that changed symbol without its task declaring that change — typically `○`/`●` → `ƒ` (a request API
     or `no-store` fetch reached the route, often through a layout or a shared data module), `◐` → `ƒ` (an
     `instant = false` or a request read pushed above `<Suspense>`), `ƒ` → `○` (per-user data now prerendered — a
     potential data leak, treat it as the most serious), or a new or changed `Revalidate`/`Expire` lifetime. An
     unexpected change is a defect: trace it, fix it, re-build. Accept it only if the task text actually requires
     the new mode, and record it. Under `output: "export"` any `ƒ` or `◐` row is a failure.
   - Note any **untouched** route whose symbol differs from the baseline: that's this bucket's side effect on
     someone else's route, and it is reported even when it looks harmless.

8. **Playwright end-to-end tests** — still holding the lock. Skip with an explicit note when Step 1 found no
   Playwright configuration (Cypress-only projects: say "Cypress present, not run"), or when 3.7 was unverified.
   Before running, check the `webServer` port: `lsof -nP -iTCP:<port> -sTCP:LISTEN`. If something already listens
   there, don't kill it (it may be the developer's `next dev`) — report e2e **unverified (port <port> in use by
   <process>)**. Then:
   ```
   Agent({
     description: "Run Playwright e2e for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Invoke Skill({skill: \"iru-typescript-test\", args: \"e2e <spec files covering this bucket's flows, or
       nothing for the whole e2e suite>\"}) with CI=1 set in the environment so Playwright starts its own production
       server instead of reusing whatever listens on the port (fall back to `CI=1 npx playwright test <specs>
       --reporter=list,json` with PLAYWRIGHT_JSON_OUTPUT_NAME set if the skill isn't installed). Its webServer builds
       and starts the app itself: wait for it, never run it in the background, never run a second instance. Report
       back only: whether all specs passed; for each failure the spec, test title, and error message; any test that
       passed only on retry (flaky); and separately whether a failure was an environment problem — browser executable
       missing (`Executable doesn't exist at ...` — never run `npx playwright install`, it downloads binaries), web
       server failed to start, port in use, a build error before any test ran, an upstream service unreachable —
       rather than an assertion failure.",
     run_in_background: false
   })
   ```
   Run it on its own, never alongside another build- or server-running agent; `run_in_background: false` is what
   keeps the lock meaningful. Check **every** `webServer` port the config lists (the component gallery's Vite server,
   e.g. 3100, as well as the production server) before running.

   **UI stage** — when the bucket added or changed a page, layout, component, story, style or UI spec, this same run
   is the UI contract's stage (`iru-nextjs-code-one-task`'s `reference/ui-contract.md` §6–§8); also ask the agent
   above to report the screenshot count per kind, malformed screenshot names, accessibility violations (count + rule
   ids) and the `.ui-quality.yml` level the gate applied. Then, still holding the lock:
   - **Matrix check** — for every page and component the tasks' decomposition trees name as added/changed, and each
     visual state their stories/specs cover, `.ui-screenshots/<kind>/<Id>/<state>--<direction>--<theme>*.png` must
     exist for every direction × theme in `.ui-quality.yml` (defaults `[ltr, rtl]` × `[light, dark]`). A missing cell
     is a gap: add the story/spec through the owning task and re-run.
   - **Accessibility** — `enforce`: every violation is a failure to fix (markup, label, contrast token) and re-run;
     `report`: listed in Steps 4–5, not failing; `off`: say so.
   - No component project/gallery in the config (only page-level e2e) → component captures are **unverified (no
     component gallery)**; no Playwright at all → the whole UI stage is **unverified (no Playwright)** — never passed.

   - **Environment failure** → **unverified** with the reason (for a missing browser, tell the user the exact
     `npx playwright install <browser>` they can run). Don't debug infrastructure on the bucket's time.
   - **Assertion failure** → trace to the task, fix, re-run. If the fix changes production code, re-run 3.3–3.7
     (the build must be re-checked too) before re-running e2e. Keep holding the lock across a quick fix; release it
     (3.8b) during a long debugging session and re-acquire before re-running. A failure unit tests missed is usually
     a real wiring defect (action not bound, redirect swallowed, wrong revalidation tag) — report it even once fixed.
   - **Flaky** (passed on retry under `CI=1`) → report it; don't count it as a clean pass.

8b. **Release the lock** as soon as 3.8 has reported back (or was skipped) and any fix-and-re-run cycle is done:

   ```bash
   rm -f .integration-test.lock/owner && rmdir .integration-test.lock
   ```

   On **every** exit path — passed, failed, unverified, a blocked task, a user interruption, a hard stop. A lock left
   behind blocks every other group for an hour. If you took it, you release it before returning. Also delete
   `.next-build.log` if a failed agent left it behind.

**Accessibility opt-out (`accessibility: none`).** When `.ui-quality.yml` says `none`, the user opted out of accessibility support entirely (see `reference/ui-contract.md` §8, "Opting out and existing code"): do not apply the accessibility rules, do not run or require the accessibility checks (axe, ATF/semantics audit, `performAccessibilityAudit()`, Axe.Windows, a11y lint rules, TUI asserted checks), and report them as "accessibility: opted out" in the group report - never "unverified", and never a failure. `off` differs: the rules still apply when writing code, only the automated checks are not run.

**Missing `.ui-quality.yml` in a repository that already has UI code.** The level was never chosen, so don't assume the `enforce` default — that default is only for a new UI project with no UI code yet. Before the first UI task of the bucket, resolve it once per `reference/ui-contract.md` §8 ("Opting out and existing code"): reuse the `Accessibility:` line of an `iru-explore` report in the plan's "Current code state" or this conversation (otherwise apply the detection rules of `iru-explore`'s `reference/accessibility-detection.md`), ask the user via `AskUserQuestion` with the setup's pre-selection (`present` → Enforce, `partial` → Report only with the mixed-compliance warning, `absent` → None with the half-compliance warning), and write `.ui-quality.yml` with the chosen level and the default `directions`/`themes`, so the per-task skills and the test gates read the same value. This is a decision only the user can make, so it is an allowed pause.

## Step 4 — Finalize progress notes and notify the user

Once Step 3 passes clean (or leaves only explicitly accepted issues), replace each task's "group validation pending"
note in `implementation_plan.md` with the final outcome — unit-only coverage, quality result, and the route
classification the build confirmed:

```
- [x] Task 2. **Add `addTodo` Server Action and form** — tests in actions.test.ts / add-todo-form.test.tsx
  (100% line coverage, unit-only); no new ESLint/tsc issues; build: /todos ƒ (as declared), / ○ unchanged;
  e2e todos.spec.ts passed.
```

For a task that touched UI, add the UI outcome to its note (`UI: OrderList 3 states × 2 dirs × 2 themes captured,
axe clean (enforce)` or `UI unverified (no component gallery)`). If the build or e2e stage was unverified, say so with the reason (`e2e unverified (Playwright browser missing)`,
`build unverified (DATABASE_URL not set)`, `lock held by another group`) — silence reads as a pass. Confirm the lock
is released before writing these notes; if a failure path skipped the release, release it now. Then notify the user
that group validation completed, summarizing the bucket-wide result. Leave blocked tasks unchecked with their
blocker note.

## Step 5 — Report the bucket's outcome

Hand control back to `iru-code-one-task-group` with a summary covering the whole bucket:

- **The detected profile** (Next version, router(s), `14`/`15`/`16-prev`/`16-cc`, `output` mode, runners).
- **Per task**: files touched, tests added/updated, **unit-only coverage**, code-quality outcome.
- **The route table result** (Step 3.7): the parsed rows for the bucket's routes, every unexpected mode change and
  how it was resolved, every accepted mode change with the task text that required it, and any untouched route whose
  symbol moved. A route that became `ƒ → ○` with per-user content, or an `instant = false` added to make the build
  pass, is the outcome most worth escalating.
- **Coverage exceptions accepted** (Step 3.4), each with its reasoning — thin async page/layout (naming the unit test
  of its data function and the e2e spec or build row that covers it), framework wiring, or a misconfigured coverage
  glob.
- **The e2e result** (Step 3.8): passed, failed-and-fixed, flaky, not configured, or **unverified** with the reason.
  Never let "unverified" read as "passed".
- **The UI stage** (Step 3.8), for UI tasks: each task's `Screen › Region › Component` tree, the screenshot count per
  kind, matrix cells still missing (should be none), malformed names, and accessibility violations (rule ids) at the
  `.ui-quality.yml` level applied — or **unverified** with the reason.
- **How the lock behaved** whenever it wasn't an immediate uncontended acquire: wait time, a broken stale lock (and
  its owner text), a timeout — and confirmation it was released. Build+e2e is the pipeline's serial stage; several
  Next.js groups in a plan means wall-clock time the caller can't parallelize away.
- Whether a quality issue was left in place and why; whether license-header generation was skipped by the user;
  whether a gate skill wasn't installed; any provider-before-consumer ordering applied in Step 2; and which task(s)
  stopped on a blocker, with enough detail to surface further up.

`implementation_plan.md`'s checkboxes are already checked and finalized (Steps 2 and 4) and the user-facing
notifications have gone out — the caller doesn't need to redo that bookkeeping for this bucket.

## Verification notes

Exercised against `create-next-app` 16.4.0 scaffolds (React 19.3, Node 24, Vitest 5.0.4, Playwright 1.63) in a
throwaway directory, in both caching models:

- Route-table parsing: the `perl` one-liner above returned exactly the route rows for a previous-model build
  (`○ /`, `ƒ /api/todos/[id]`, `● /posts/hello`, `● /posts/world`, `ƒ /todos` — the bare `/posts/[slug]` parent row
  and the legend skipped) and for a Cache Components build including the lifetime columns (`○ /api/stock 1h 1d`,
  `◐ /shop 1h 1d`). On 16 the table has no size columns; `Revalidate`/`Expire` appear only when some route has a
  lifetime. macOS `sed` without a UTF-8 locale does not match the symbols — hence `perl -CSD`.
- A second concurrent `next build` in the same project exited 1 with `Another next build process is already
  running.`; under `cacheComponents` a top-level `await cookies()` failed the build with the blocking-prerender
  error and `export const revalidate = 60` with the segment-config incompatibility error, as described above.
- Vitest unit tests (Client Component with `useActionState`, Server Action called directly with mocked session and
  `next/cache`, a Route Handler called with a `RouteContext` Promise, an async leaf page called as a function,
  `next/headers` mocked), ESLint flat config, `next typegen && tsc --noEmit`, and a Playwright run against
  `npm run build && npm run start` (form submit through a real Server Action plus an API-request test) all passed.
- **Not verified locally**: Jest via `next/jest`, Next 14 and 15 projects, Pages Router builds with ISR, Biome,
  `output: "export"` builds, and contention behaviour of the lock across two real concurrent groups. Playwright
  1.64 failed until browsers were installed (`Executable doesn't exist`) — exactly the environment failure Step 3.8
  reports as unverified.
