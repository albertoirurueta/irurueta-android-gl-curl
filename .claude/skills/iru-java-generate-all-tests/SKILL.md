---
name: iru-java-generate-all-tests
description: "/iru-java-generate-all-tests finds every class of a Java/Maven project with no unit test class or under 80% line coverage per iru-java-coverage, and generates or extends tests in the project's own framework and style. Stops immediately if there is no pom.xml."
model: sonnet
allowed-tools: Read Edit Write Bash(mvn *) Bash(git status *) Bash(git diff *) Bash(find *) Bash(grep *) Bash(ls *) Skill Agent AskUserQuestion
---

# Java Generate All Tests

Bring an entire Java/Maven codebase's unit test coverage up to a minimum bar (80% line coverage) in one pass:
find every class with no test class at all or with measured coverage below the bar, then write or extend tests
for each until it clears it. This skill is Java/Maven only — it never runs against, or falls back to, any other
language. It only adds/extends test code; it does not add license headers, Javadoc, or run a code-quality pass
(those are separate skills' jobs), and it only touches production source code if a class is genuinely untestable
as written (see Step 5) — which it reports rather than silently "fixing" by redesigning the class.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 1 — Confirm this is a Java/Maven project

```bash
find . -name pom.xml -not -path "*/target/*"
```

- **None found**: this skill applies only to Java/Maven projects. Tell the user plainly and stop — do not
  attempt any other language's equivalent or partial exploration.
- **Found**: continue. If there are multiple `pom.xml` files (a multi-module build) and no obvious single
  target, scope this run to the whole reactor build from the root `pom.xml`, same convention as `iru-java-test`/
  `iru-java-coverage`.

## Step 2 — Discover every candidate class and the existing test convention

Use the `Explore` agent for this — a repository of any real size has too many source files to read directly one
by one, and this step needs a structural map, not a design review.

- Identify every **main-source** class under `src/main/java` (excluding `target/`, generated sources such as
  annotation-processor output, and `package-info.java`). Use judgment on classes with no real logic to exercise
  (a pure marker interface, an empty `enum`, a plain data-holder `record`/POJO with only generated
  accessors, a `Main` class that only wires up bootstrapping with no branching logic of its own) — note these
  as "excluded, no testable logic" in the final report rather than silently dropping them or forcing trivial
  tests onto them.
- Identify the **existing test source root** (`src/test/java`, matching `pom.xml`'s Surefire configuration) and
  the naming convention it already uses to map a source class to its test class (e.g. `Foo` → `FooTest` or
  `FooTests`, and whether test classes mirror the source package 1:1). If **no test source root exists at
  all** (no `src/test/java` and no test dependencies in `pom.xml`), stop and tell the user: this skill extends
  an existing test setup's coverage, it doesn't scaffold one from nothing — ask (`AskUserQuestion`) whether
  tests actually live somewhere unconventional before concluding there really are none.
- For every candidate class, resolve whether a matching test class file already exists by that convention, and
  its path if so.

Build a plain list: fully-qualified class name → source file path → matching test file path (or "none").

## Step 3 — Measure real coverage for every candidate class in one pass

Delegate this to `iru-java-coverage` via the `iru-gate-runner` agent, so a large `jacoco.csv`/HTML report never lands
directly in this conversation's context, and so the whole suite is only executed once regardless of how many
classes are in scope:

```
Agent({
  description: "Measure coverage for every candidate class",
  subagent_type: "iru-gate-runner",
  prompt: "Invoke Skill({skill: \"iru-java-coverage\", args: \"<TargetClass1,TargetClass2,...>\"}) with no test
    selector, so the whole suite runs once and every class is measured in the same pass. Report back, per target
    class: its exact line coverage percentage; and, ONLY for classes below 80%, the specific uncovered line
    numbers too (from the per-class HTML report). For classes at or above 80%, report just the percentage — do
    not include per-line detail for those, to keep the report compact.",
  run_in_background: false
})
```

If the candidate list from Step 2 is very large (rough guide: more than ~50 classes), split it into a few
batches of target-class arguments passed in the same `iru-java-coverage` call structure above so no single
`iru-gate-runner` report becomes unwieldy — this still only requires one full-suite run per batch, not one per
class.

If a class has no matching test file (Step 2) it will simply show 0% (or be entirely absent from the coverage
CSV) — treat "absent from the report" the same as "0%, no coverage," per `iru-java-coverage`'s own Step 4.

## Step 4 — Classify

From Step 3's results, split every candidate class into:

- **Missing tests**: no matching test file (Step 2), regardless of reported percentage.
- **Below bar**: a matching test file exists, but line coverage is under 80%.
- **Already sufficient**: line coverage is 80% or above — skip these entirely, count them for the final report.

## Step 5 — Generate or extend tests, one class at a time

For every class in the "missing tests" or "below bar" buckets, write (or extend) its unit tests. Since each
class's test file is independent of every other's, dispatch one sub-agent per class **concurrently** (in
batches of a manageable size — a handful at a time rather than dozens at once — if the bucket is large):

```
Agent({
  description: "Write/extend tests for <ClassName>",
  prompt: "Read <source-file-path> in full — this is the class to cover. <If a test file already exists at
    <test-file-path>: read it too; these lines are currently uncovered: <line numbers from Step 3> — extend this
    file with tests that exercise them.> <If no test file exists: create one at <path implied by this project's
    naming/package convention from Step 2>.> Match this project's existing test style exactly: JUnit (4 or 5,
    whichever this repository already uses), Mockito only if it's already used elsewhere, same assertion style
    (JUnit assertions vs. AssertJ/Hamcrest, whichever is already in use). Cover the class's actual behavior,
    including edge cases (null/invalid-argument handling implied by its own validation logic, boundary
    conditions, branches) — not superficial calls that merely execute lines without asserting real behavior. Do
    not modify the class under test unless it is genuinely untestable as written (e.g. a hard dependency on a
    static/non-injectable collaborator with no seam to substitute it) — if so, make the minimal change needed to
    add a seam (e.g. extracting an interface, injecting a dependency via a constructor) and say so explicitly
    rather than reporting it as a plain test addition. Report back: the test file touched (new or extended),
    what was added, and whether the class under test needed a minimal testability change.",
  run_in_background: false
})
```

Collect every sub-agent's report before moving on.

## Step 6 — Re-measure and iterate on shortfalls

Re-run Step 3's delegated `iru-java-coverage` pass, scoped to just the classes touched in Step 5, to confirm each
now meets 80%. For any class still below the bar:

- Identify the specific lines still uncovered (already returned by this re-measurement).
- Dispatch one more Step 5-style agent for just that class, focused on the remaining uncovered lines.
- Re-measure once more.

Cap this at two extension attempts per class. A class still below 80% after that is a genuine residual gap —
report it as such (Step 8), with the exact lines still uncovered, rather than looping indefinitely. A common
legitimate reason: defensive code paths that are difficult or unsafe to trigger from a unit test (e.g. a
caught `OutOfMemoryError`). Name these explicitly if that's what remains uncovered.

## Step 7 — Run the full suite once to confirm no regressions

Delegate to `iru-gate-runner`: `Agent({description: "Run full test suite", subagent_type: "iru-gate-runner", prompt:
"Invoke Skill({skill: \"iru-java-test\"}) (fall back to `mvn test` directly if unavailable). If everything passed,
report back only that all tests passed. If anything failed, report back only the failing test names, the
failure reason, and the stack trace for each."})`. Fix any regression this run's new/extended tests introduced
(a genuine bug in a generated test, not a pre-existing failure), then re-invoke until it reports all tests
passed. A pre-existing failure unrelated to any class this run touched is out of scope — note it in Step 8
rather than fixing it.

## Step 8 — Report

Summarize:

- Total candidate classes found (Step 2), and how many were excluded as having no testable logic, with names.
- How many were already at or above 80% and left untouched.
- For each class in the "missing tests"/"below bar" buckets: before/after line coverage, and whether its test
  file was newly created or extended.
- Any class that required a minimal testability change to the class under test (Step 5), named explicitly.
- Any class still below 80% after Step 6's two attempts, with the exact lines still uncovered and, where
  apparent, why (e.g. an untestable defensive branch).
- The full-suite result from Step 7.

Do not run a code-quality, license-header, or Javadoc pass — those are `iru-java-code-quality`, `iru-check-license`, and
`iru-java-javadoc`'s jobs respectively, not this skill's.
