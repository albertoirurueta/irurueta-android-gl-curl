# Tests

Every task's implementation comes with the tests that prove it. This skill **writes** them and does not **run**
them — the calling `iru-typescript-code-one-task-group` runs the suite, coverage, and quality checks once for the
whole task group. Write tests that will pass and that will hold the coverage gate up; don't invoke the test
runner here.

## Follow the package you're in

Before writing a test, read an existing test in the same directory. Match its runner (Vitest or Jest — Step 1
already detected which), its naming convention, its assertion style, and its use (or non-use) of mocking.
Introducing a second convention in a project — Jest matchers where the rest uses Vitest's, a different file
naming scheme — is a cost paid by every later reader, and it isn't what the task asked for.

Defaults for a project that doesn't yet have a convention: co-located `*.test.ts`/`*.test.tsx` next to the
source file (or a mirrored `test/`/`__tests__/` directory if the project already uses one), Vitest's own
`expect`/`describe`/`it`, `@testing-library/*` for anything rendering UI.

## Vitest vs. Jest — what differs

- **Imports**: Vitest needs `import { describe, it, expect, vi } from "vitest";` (unless `globals: true` is set
  in `vitest.config.ts`, matching Jest's ambient-globals style — check the config before assuming). Jest's
  `describe`/`it`/`expect`/`jest` are ambient globals by default; no import needed.
  - Vitest — `vi.fn()`, `vi.mock()`, `vi.spyOn()`, `vi.useFakeTimers()`.
  - Jest — `jest.fn()`, `jest.mock()`, `jest.spyOn()`, `jest.useFakeTimers()`.
- **An unmatched test-file filter behaves differently**: Vitest exits non-zero ("No test files found") when a
  pattern matches nothing; Jest exits zero with zero tests run. This matters for `iru-typescript-code-one-task-
  group`'s scoped test run, not for this skill — but naming the test file so it's actually picked up by the
  project's configured `testMatch`/`include` glob is this skill's job.
- Both support `describe`/`it` (`test` is a Jest/Vitest-shared alias for `it`) and the same core matcher surface
  (`toBe`, `toEqual`, `toThrow`, `toHaveBeenCalledWith`, …) — write assertions the same way in either.

## What to cover

- **The behavior the task added or changed**, through the public API/exported surface — the happy path plus the
  meaningful variations.
- **Every `@throws`/rejected-promise case in the TSDoc.** Each documented error gets a test asserting it is
  thrown/rejected for the documented condition. This is the single most useful habit here: it keeps the
  documentation honest and it is where most real defects surface.
- **Boundaries**: empty and single-element arrays, zero, negative, the maximum, the first and last valid value,
  `undefined`/`null` where they're meaningful inputs.
- **Not the trivia.** A pure pass-through re-export, a type-only file, or a trivial constant needs no test.
  Coverage is a gate, not a goal; tests that assert nothing meaningful cost maintenance and prove nothing.

## Writing the test

- **One behavior per test (`it`/`test`)**, with a name that says what it asserts, following the package's
  existing naming style (`it("throws when taxRate is negative", …)` reads better than `it("test 3", …)`).
- **Arrange, act, assert**, in that order and visibly separated. Keep the assertion at the end; a test whose
  assertions are scattered through setup is hard to diagnose when it fails.
- **Assert the specific thing**: `expect(actual).toEqual(expected)` over `expect(actual === expected).toBe(true)`
  — the failure diff is the difference between a one-second and a ten-minute diagnosis.
- **`expect(() => fn()).toThrow(SpecificError)`** for sync throws; `await expect(promise).rejects.toThrow(...)`
  for async rejection — assert on the error type/message where it carries information, not just "it threw".
- **`it.each`/`test.each`** with a table of inputs instead of a loop inside one test or several near-identical
  tests — a table-driven case reports which row failed.
- **No shared mutable state between tests.** Fresh fixtures in `beforeEach`, not module-level `let`s reused
  across tests; never rely on the order tests run in.
- **No arbitrary `setTimeout`/`sleep` in a test.** Use the runner's fake timers (`vi.useFakeTimers()` /
  `jest.useFakeTimers()` plus `vi.advanceTimersByTime`/`jest.advanceTimersByTime`), or `await` the actual
  async operation under test — a real sleep is either flaky or slow, and usually both.
- **No snapshot-only tests.** A `toMatchSnapshot()` with no accompanying explicit assertion proves nothing about
  intent and rots silently as the snapshot is blindly updated; a snapshot may *supplement* explicit assertions
  for a large serialized shape, but it never stands alone as the only check.
- **Don't test module-private helpers directly** by exporting them just for the test. Exercise them through the
  exported behavior that uses them; if that's genuinely impossible, the design is telling you the logic wants to
  be its own module — which is a finding to report, not a refactor to perform mid-task.

## Mocking, carefully

- Mock what you don't own or can't afford: a network client, the filesystem, `Date`/timers, a slow collaborator.
  Use the real thing for a plain value/type, or a simple in-memory collaborator — mocking a value object is pure
  ceremony.
- **`vi.mock("./module")` / `jest.mock("./module")`** at the top of the file (hoisted automatically by both
  runners) for a module-level dependency; prefer factory-injected fakes (a function/object passed as a parameter)
  over module mocking wherever the code is structured to allow it — a factory-injected fake doesn't fight the
  runner's hoisting rules and is easier to vary per test.
- **Don't mock the module/component under test**, and don't stub away the exact behavior the task just changed.
- **Verify interactions only when the interaction *is* the behavior** (a callback was invoked with specific
  arguments, an API client was called once). Otherwise assert on the rendered/returned result; over-verified
  tests break on every harmless refactor.
- Reset mocks between tests (`vi.clearAllMocks()`/`jest.clearAllMocks()` in `afterEach`, or the runner's
  `clearMocks: true` config option) so one test's stubbed call count can't leak into the next.

## Testing UI (React / Angular / React Native / Ionic)

- **Testing Library query priority** (`@testing-library/react`, `@testing-library/react-native`, or Angular's
  own harnesses where used): prefer `getByRole` (with an accessible name) first, then `getByLabelText`,
  `getByPlaceholderText`, `getByText`, and reach for `getByTestId` only when nothing accessible-facing
  distinguishes the element — a `data-testid` is a last resort, not a habit.
- **`userEvent` over `fireEvent`** for anything simulating real interaction (`await userEvent.click(button)`,
  `await userEvent.type(input, "value")`) — it dispatches the fuller sequence of events a real user/browser
  would, which `fireEvent` skips.
- **`findBy*` (async) for anything that appears after a state update/async effect**, `getBy*` (sync) only for
  what's present in the initial render. Don't wrap a `getBy*` in `waitFor` when a `findBy*` already does that.
- Query framework-specific files (`.angular.md`, `.react.md`, `.react-native.md`, `.ionic.md`) for the rest of
  their testing conventions (`TestBed`, `HttpTestingController`, RNTL specifics, platform mocking).
- **Behaviour tests are not UI tests.** A jsdom/happy-dom test proves behaviour; it never produces the contract's
  screenshots. Every added/changed component and screen also gets Playwright UI tests — stories per visual state,
  the right-to-left × light/dark matrix captured into `.ui-screenshots/`, axe at the `.ui-quality.yml` level — per
  `ui-contract.md` §6–§8 and `ui-implementation.md`. Name the stories and states after the contract's state
  vocabulary (`default`, `loading`, `empty`, `error`, `disabled`, `selected`, `long-content`, `many-items`).

## What this skill does not do

No running the suite, no coverage report, no quality/lint run, no license headers, no TSDoc audit — all of those
belong to `iru-typescript-code-one-task-group`, once per task group. If a test can't be made to pass without
something outside the task's scope, say so in the report; don't delete the assertion to make the file green.
