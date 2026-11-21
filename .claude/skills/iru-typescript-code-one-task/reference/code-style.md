# Code style

This catalog's general TypeScript code agreements. They apply to any npm/TypeScript project — library, React,
Angular, React Native, Ionic, or a Hilla frontend — the framework-specific files layer additional rules on top,
never replace these.

Above all: **match the surrounding code.** These are the defaults for new code. If the file you are editing
consistently does something else, follow it and note the divergence in the report — don't restyle a file as a
side effect of an unrelated task.

## Strict typing: no `any`

Every project this catalog scaffolds runs with `"strict": true` (and usually `noUncheckedIndexedAccess`) in
`tsconfig.json`. Write to that bar even where a file predates it.

- **Never write `any`.** Use `unknown` plus a type guard when the shape genuinely isn't known yet, a generic
  parameter when the function is shape-agnostic, or a proper union/discriminated union when it's one of a few
  known shapes.

  ```ts
  // Avoid
  function parse(input: any): any { ... }

  // Prefer
  function parse(input: unknown): ParsedConfig {
    if (!isRawConfig(input)) {
      throw new Error("invalid config shape");
    }
    return toParsedConfig(input);
  }
  ```

- **Discriminated unions over optional-field grab-bags.** A type with several optional fields that are only
  valid in certain combinations invites impossible states; a tagged union makes the impossible state
  unrepresentable and lets the compiler narrow for you.

  ```ts
  // Avoid
  interface FetchState {
    loading?: boolean;
    data?: Order;
    error?: string;
  }

  // Prefer
  type FetchState =
    | { readonly status: "idle" }
    | { readonly status: "loading" }
    | { readonly status: "success"; readonly data: Order }
    | { readonly status: "error"; readonly error: string };

  function render(state: FetchState): string {
    switch (state.status) {
      case "idle":
        return "Idle";
      case "loading":
        return "Loading…";
      case "success":
        return state.data.id; // narrowed, no cast needed
      case "error":
        return state.error;
    }
  }
  ```

- **No non-null assertions (`!`) as a substitute for narrowing.** Narrow with a guard, an `if`, or `??` instead;
  an assertion is a promise to the compiler that can go stale silently.
- **No type assertions (`as`) to paper over a mismatch.** An `as` is legitimate only when narrowing a genuinely
  wider type you already validated (e.g. `unknown` after a guard) — never to force two unrelated shapes together.
- **Avoid enums; prefer a union of string literals** (`type Status = "pending" | "active" | "closed";`) unless the
  surrounding code already uses TypeScript `enum`/`const enum` — they don't tree-shake as cleanly and don't
  narrow as well as literal unions.

## `readonly` and immutability by default

- **`readonly` on interface/type properties** that the holder must not mutate — which, for a value passed around
  rather than owned, is almost always all of them.
- **`ReadonlyArray<T>` / `readonly T[]`** for a parameter or return type the caller must not mutate; `ReadonlyMap`
  / `ReadonlySet` likewise.
- **`const` over `let`** for every binding never reassigned — which should be nearly all of them. `let` signals
  "this changes"; reserve it for an actual mutable loop accumulator or similar.
- **Prefer returning a new object/array over mutating one in place** (`[...items, next]`, `{ ...state, count }`)
  except inside a narrowly-scoped, performance-justified loop.
- Mark a class field `readonly` unless the class genuinely reassigns it after construction.

## Named exports, ESM

- **Named exports** for every module this catalog scaffolds — `export function computeTotal(...)`, `export class
  OrderRepository`, `export interface OrderView`. No `export default` on new modules, because a default export
  has no fixed name at the import site and doesn't show up in "find references" the way a named one does.
  Framework files that require a default export at a specific location (an Expo Router route module, an Angular
  schematic entry point) are the documented exception — use it there, and only there, and say so in the report.
- **ESM throughout**: `import`/`export`, never `require`/`module.exports`, in any file this catalog scaffolds
  (`"type": "module"` in `package.json`). Use explicit file extensions in relative imports only where the
  project's `moduleResolution` requires it (`bundler` usually doesn't; `node16`/`nodenext` does).
- **One default export per file is at most one; most files have zero.** A file exporting a component/class *and*
  its supporting types uses named exports for all of them.
- Group imports: external packages, then internal absolute/aliased imports, then relative imports, each group
  separated by a blank line if the project's ESLint `import`/`simple-import-sort` config enforces it — otherwise
  match the existing file.

## Naming

- Types, interfaces, classes, enums: `UpperCamelCase` nouns. No `I` prefix on interfaces (`OrderRepository`, not
  `IOrderRepository`).
- Functions, methods, variables: `lowerCamelCase`, verbs for functions, nouns for values. Boolean names read as a
  predicate (`isActive`, `hasItems`, `canRetry`).
- Constants that are truly fixed (module-level, never reassigned, primitive or frozen): `SCREAMING_SNAKE_CASE`
  only for genuinely global constants (`MAX_RETRIES`); a `const` holding a computed or object value keeps
  `lowerCamelCase`.
- React/Angular component files and their default-exported identifier: `PascalCase` (`OrderSummary.tsx`,
  component `OrderSummary`). Hooks: `useCamelCase`, always starting with `use`.
- Type parameters: single capital letter (`T`, `K`, `V`, `R`) or a short `UpperCamelCase` word when several
  parameters would otherwise be indistinguishable.
- Tests: follow the existing convention in the same directory — don't introduce a second one.

## Error handling

- **Throw `Error` (or a subclass) — never a string, a number, or a plain object** — so `instanceof Error` and
  `.stack` keep working for every catcher up the chain.
- **Prefer a specific error class over reusing a generic `Error`** when callers need to distinguish the failure
  (`class ValidationError extends Error { constructor(message: string, readonly field: string) { super(message);
  this.name = "ValidationError"; } }`). Set `.name` in the constructor so logs and `instanceof` both work.
- **Don't catch and swallow.** A bare `catch { }` — or one that only `console.log`s — turns a failure into silent
  wrong behavior. Either handle it meaningfully, translate it into a more specific error (setting `{ cause }`),
  or let it propagate.
- **`{ cause }` on a re-thrown error** (`throw new FetchError("failed to load order", { cause: err })`) instead
  of losing the original error.
- **Validate at the boundary** — a public function's entry, a component's props, an API handler's request body —
  so an invalid value never propagates far enough to fail somewhere confusing.
- **No `console.log`/`console.debug`** left behind in code the task adds; use the project's logger if one exists,
  and `console.error`/`console.warn` only where the project already establishes that as its logging strategy.

## Async

- **`async`/`await` over raw `.then()` chains** for anything beyond a single trivial call — chained `.then()`
  is harder to add error handling and control flow to.
- **Always handle rejection.** An `async` function called without `await` and without a `.catch` produces an
  unhandled rejection; either `await` it, return the promise to a caller that will, or attach `.catch` explicitly.
- **`Promise.all` for independent concurrent work**, not a sequential loop of `await`s, unless the calls
  genuinely depend on each other's results or must be rate-limited.
- **No floating promises.** If a lint rule (`@typescript-eslint/no-floating-promises`) is configured, write code
  that satisfies it: `void doSomethingFireAndForget();` when a promise's result is genuinely not awaited, with a
  comment saying why.
- **`AbortController`/`AbortSignal`** for a fetch or async operation that a component/caller may need to cancel
  (unmount, navigation away, a newer request superseding an older one).

## Other conventions

- **No new runtime dependency** unless the task explicitly calls for it. If one seems unavoidable, that's a
  blocker to report, not a decision to make silently.
- **Don't leave dead code, commented-out code, or `TODO`s** behind for work the task itself covers.
- **Keep functions short and single-purpose.** If a function needs a comment to explain its sections, those
  sections are the functions you should have extracted — but extract only within the task's scope.
- **Use the standard library / platform APIs** before writing a helper (`Array.prototype` methods, `Intl`,
  `structuredClone`, `URL`, `Map`/`Set`). Don't add a utility that duplicates one of them.
- **Template literals** for string interpolation, never `+` concatenation of more than two pieces.
- **No magic numbers/strings** for anything with meaning beyond its immediate use — name it as a `const`.
