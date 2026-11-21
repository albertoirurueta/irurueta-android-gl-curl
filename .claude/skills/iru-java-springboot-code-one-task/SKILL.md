---
name: iru-java-springboot-code-one-task
description: "Invoked as /iru-java-springboot-code-one-task <task description> (the task's text with its implementation_plan.md checkbox lines) via Skill. Implements one Spring Boot hexagonal-module plan task and its tests, compile-checks, ticks the checkbox with a group-validation-pending note; no baseline or full gates, which iru-java-springboot-code-one-task-group runs."
model: sonnet
allowed-tools: Read Edit Write Bash(mvn *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *)
---

# Implement one plan task (Spring Boot)

Carry out a single task's implementation, unit tests, and checkbox update against a DDD/hexagonal Spring Boot
service — nothing else. This is the narrowest execution unit for a Spring Boot service in the `iru-code`
pipeline, the counterpart to `iru-java-code-one-task` for a plain Java/Maven project.

Two things make it different from that skill, and both matter:

1. **The module boundary is checked before any code is written.** In a hexagonal reactor, the same class in the
   wrong module is a structural defect, not a style problem — and the build enforces it, so getting it wrong
   costs a full build cycle. Step 2 resolves the target module first.
2. **It runs unit tests itself, and only unit tests.** `iru-java-code-one-task` defers all testing to its group
   skill; this one validates its own work with a scoped Surefire run, because `domain` and `application` have no
   framework on their classpath and their tests are near-instant. Integration tests are categorically out of
   scope — see Step 6.

It uses a medium model on purpose: the plan already carries the hard reasoning, and this skill's own architectural
guidance is in `reference/` rather than re-derived per task.

## Step 1 — Read only the reference material this task needs

This skill ships a `reference/` directory next to this file. Read `reference/README.md` first — it is short and
routes you to the rest — then read only what it points at for this task. The routing, restated:

| Read | When |
|---|---|
| `reference/hexagonal-architecture.md` | **Always** — Step 2 depends on it. |
| `reference/mandatory-rules.md` | **Always** — the non-negotiable rules for production and test code (JUnit not AssertJ, Mockito not fakes, test naming and one test class per public class, type witnesses, record defensive copies, `final` parameters, Javadoc on every member, constants, modern idioms, LF line endings, pinned locales, Lombok constructors/loggers). |
| `reference/code-style.md` | **Always** — records, Lombok, MapStruct-versus-manual, `final`, `var`, Javadoc. |
| `reference/ddd-tactical-patterns.md` | The task touches `domain`: an entity, value object, aggregate, domain service, domain event, or domain exception. |
| `reference/solid.md` | The task introduces or changes an interface, adds a branch on type, or grows an existing class's responsibilities. |
| `reference/distributed-patterns.md` | The task involves Kafka, a second datastore, or anything the plan calls eventually consistent, a projection, an outbox, a saga, or a compensating action. |

Don't read the optional files speculatively — for a one-line change inside an existing adapter, the three
always-read files are the whole list. Reading all six on every task is exactly the context waste the split
exists to avoid.

If `reference/` is missing (e.g. the skill directory was copied without it), proceed using this repository's own
`CLAUDE.md` conventions and the surrounding code's existing style, and say so in Step 8's report — don't guess at
what the reference would have said.

## Step 2 — Resolve the target module and its allowed imports

Before editing anything, settle three questions and state the answers to yourself explicitly:

1. **Which module does this code belong in?** Use the task's named file path if it gives one. If it doesn't, use
   the "Where a given piece of work goes" table in `reference/hexagonal-architecture.md` to derive it from what
   the task asks for.
2. **What may that module import?** From the module reference table. `domain` may not import Spring, Spring Data,
   or JPA at all; `application` may not see `infrastructure` or `api`; no `infrastructure` module may see a
   sibling; no `api` module may see `infrastructure`.
3. **Does this task span modules?** Most non-trivial ones do. Follow the order in
   `reference/hexagonal-architecture.md`: `domain` (types and the port interface) → the adapter implementing it →
   `application` (the use case) → `api` (the entry point) → `boot` (only if new cross-module wiring is genuinely
   needed). Writing `domain` last produces a port shaped around a database row instead of a business capability.

If the task's named file path contradicts what the module rules require — it asks for a repository query in
`application`, or a Spring annotation in `domain` — that is a genuine conflict, not something to quietly
"correct". Stop and ask per Step 5, because the answer changes what gets built: either the plan's path is a slip,
or the task means something other than it appears to.

## Step 3 — Re-check the current code state

Read the actual current content of the file(s) the task touches before editing. Don't assume any "current code
state" notes passed in with the task are still accurate — other tasks in the same bucket may be landing
concurrently, or the file may have changed for unrelated reasons.

Also read one or two neighbouring files in the same module. In a multi-module reactor, conventions vary
legitimately between modules (whether `var` is used, whether mappers are MapStruct or hand-written, how adapters
translate exceptions), and matching the module you're editing beats matching the repository average — for
everything except `reference/mandatory-rules.md`, which the code you write follows even where the module's
existing code doesn't (leaving that existing code alone, and naming any violation in Step 8's report).

## Step 4 — Implement exactly what the task specifies

The named file(s), the described class/method/field, the behaviour, the port it implements. Apply
`reference/code-style.md` and whichever pattern files Step 1 selected, plus this repository's own `CLAUDE.md`
conventions where they're more specific.

Non-negotiable, because the build enforces them:

- **No dependency that points outward.** Re-check Step 2's answer against every import you add.
- **`reference/mandatory-rules.md`, in full**, for every line written or rewritten — production and test code.
- **Full Javadoc** on every type, method, constructor, and field (private ones included), with `@param`
  (including every record component), `@return`, and `@throws` for every exception a caller can handle. The `@throws` list is what Step 6's
  tests are written against.
- **No new runtime dependency** unless the task explicitly calls for it. In this reactor, versions live only in
  the root `pom.xml`, so adding one is a cross-module change — if it seems unavoidable, that's a blocker to report
  (Step 5), not a decision to make quietly.
- **No `<version>` in a module pom**, ever. It defeats the reactor's version discipline.

Don't add anything the task didn't ask for — no speculative abstraction, no unrelated cleanup, and specifically
none of the patterns in `reference/distributed-patterns.md` on your own initiative. Those are documented so you
implement one *correctly when the plan asks for it*, not so you introduce one.

## Step 5 — When to interrupt the user

Keep interruptions rare — most tasks should complete unattended. Stop and use `AskUserQuestion` (or plain text if
no real choice is being offered) only when:

- The task is ambiguous in a way that changes correctness or scope and can't be safely inferred — conflicting
  instructions, or a decision only the user can make. Don't ask about anything with an obvious best-practice
  answer.
- The task's approach is infeasible against the real code (it names a type or member that doesn't exist and isn't
  a trivial typo).
- **The task would require violating a module boundary** to implement as written (Step 2's conflict case). This is
  the Spring-Boot-specific reason to stop, and it's worth stopping for: the alternatives are a build failure or a
  silently relaxed architecture rule.
- **The task appears to require a new distributed pattern** — an outbox table, a projection, a saga — to be
  correct, and the plan didn't ask for one. Report the reasoning; don't introduce the pattern unilaterally.

Do not stop merely because the task is nontrivial — implement it. Do not report the task done to work around a
blocker; report it as blocked (Step 8), with enough detail — what was tried, what failed, why — for the caller to
surface it.

## Step 6 — Write and run unit tests only

Write or update unit tests covering the new/changed behaviour, including the edge cases implied by the Javadoc
`@throws` contracts (null/invalid argument, not-found, illegal state transition). Follow
`reference/mandatory-rules.md` — JUnit 5 assertions (never AssertJ), Mockito mocks or spies for every collaborator
(never a hand-written fake or in-memory port implementation), one `<ClassName>Test` per public class (never one
test class shared across several implementations), test methods named
`methodBeingTested_whenCondition_expectedResult`, and full Javadoc on the test class and every member — and the
existing test style in the same module for anything those rules leave open.

What a unit test means here, per module:

- **`domain` and `application`** — plain JUnit 5, Mockito for the ports, **no Spring context at all**. These
  modules have no framework on the classpath, which is exactly what makes this possible and fast. A `@SpringBootTest`
  here is a sign something is in the wrong module.
- **`infrastructure/*` and `api/*`** — unit-test the parts that don't need the real technology: mappers, tag
  derivation, exception translation, request/response conversion. Mock the driver, client, or template. A sliced
  Spring test (`@WebMvcTest`, `@JsonTest`) is acceptable **only if** the module's existing tests already use one
  and it starts no container.
- **`infrastructure/clients/<name>`** — mock the *generated* client class with Mockito and unit-test the adapter's
  own logic around it: the mapping to and from domain types, and the translation of each error the generated
  client can throw. Do **not** start the API mock container (Microcks or WireMock, whichever `stack.apiMock`
  names) — that is a container, so it belongs to the group's integration pass under the rule below, even though
  it starts far faster than a database. If the task adds a new downstream call, check that the adapter reads its
  base URL from configuration (`clients.<name>.base-url`) rather than hardcoding a host: the integration harness
  redirects that property to the mock, and a hardcoded URL silently defeats it.

### Integration tests are out of scope — categorically

Run the unit tests, scoped to what this task touched:

```bash
mvn -pl <module> -am -o test -Dtest='<TestClass1,TestClass2>' -DskipITs
```

`-pl <module>` keeps the run to the module just edited; `-am` builds the modules it depends on. Drop `-o` if
offline resolution fails.

**Never run any of these, and never any variant of them:**

- `mvn verify` — it triggers Failsafe and the whole Testcontainers stack.
- `mvn failsafe:integration-test` / `failsafe:verify`, or anything invoking Failsafe.
- Any `*IT` test class, by any means — `-Dtest=SomethingIT` included.
- Anything that starts Docker, a `ComposeContainer`, or a `@Testcontainers` test.
- `mvn install`, `mvn deploy`, or a bare `mvn` with no phase, since any of those can reach `verify`.

The reasons this is a hard rule rather than a preference: integration tests need Docker and several containers,
they take orders of magnitude longer than the task's own implementation, and — decisively — a task-level run
would start the same container stack once per task, which is precisely the redundancy the group-level validation
exists to avoid. The calling task-group skill owns integration testing, coverage, the full suite, and quality
checks; this skill owns one task's implementation and its unit tests.

If a unit test fails, fix the implementation or the test and re-run until green. Don't hand back a red test. If
making it pass would require an integration test — the behaviour genuinely can't be verified without the real
technology — write the unit tests that *are* meaningful, leave the rest to the group's integration pass, and say
so explicitly in Step 8's report. Do not run the integration test to check, and do not delete the assertion to
make the file pass.

## Step 7 — Check off this task's checkbox now

If this task did not stop on a blocker (Step 5), update `implementation_plan.md` at the repository root before
reporting back — don't leave it for the caller. Re-read the file fresh (not any earlier cached view — other tasks
in the same bucket may be landing concurrently) and locate this task's own checkbox line by the exact text passed
in with the task. Flip its `[ ]` to `[x]`, and do the same for every sub-task checkbox covered by the work just
completed, adding a short note naming the module and the files touched, e.g.:

```
- [x] Task 3. **Add `OrderRepository.findByCustomer`** — port added in domain, implemented in
  infrastructure/database/mongodb; unit tests added; group validation pending.
```

The "group validation pending" wording matches what the calling group skill expects, since coverage, quality, and
integration-test outcomes aren't known until the whole bucket is validated. Edit only this task's own line(s) —
never rewrite surrounding lines or another task's checkbox, since sibling tasks in a parallel bucket may be
editing the same file at nearly the same moment.

This is what lets an interrupted run resume without re-attempting a task whose implementation and unit tests
already landed.

If this task stopped on a blocker instead, leave its checkbox untouched — there's nothing finished to mark done.

## Step 8 — Report the outcome

Hand control back to the caller with a short summary:

- **The module(s) touched** and the file(s) in each — the caller needs this to scope its own validation, and a
  cross-module task is worth naming as such.
- **The port(s) added or implemented**, if any.
- **The unit tests added/updated, and their result.**
- **Anything deliberately left to the group's integration pass**, and why (per Step 6).
- **Whether this task stopped on a blocker**, with enough detail for the caller to surface it.
- **Any deviation from `reference/`** you made on purpose — e.g. a hand-written mapper where MapStruct would have
  done, or matching a module's existing style over this catalog's stated preference — so the caller can see it was
  a decision rather than an oversight.
- **Pre-existing violations of `reference/mandatory-rules.md`** noticed in the files touched (AssertJ, fakes,
  shared test classes, platform line endings, unpinned locales, …) and left alone because they were outside the
  task's scope.

This skill has already checked off its own checkbox with a pending-validation note (Step 7); the caller only needs
to replace that note with the final validation outcome once the whole bucket passes. This skill never captures a
quality baseline, adds license headers, runs coverage or code-quality checks, runs the full suite, or runs any
integration test.
