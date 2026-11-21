# TSDoc

**Every exported symbol is fully documented.** Every exported function, class, interface, type alias, and enum,
without exception. The `iru-typescript-tsdoc` skill audits this one gate later, in the task group, via
`typedoc --emit none --treatWarningsAsErrors` (library/react) or `compodoc --coverageTest 80` (angular) — so
writing it now is cheaper than fixing it after validation.

## What every exported declaration needs

- **A summary sentence** as the first line of the block, standing alone: say what the thing *is* or *does*, not
  what its name already says. `/** Gets the order id. */` on `getOrderId()` is noise; `/** Identifier assigned by
  the carrier once the shipment is booked. */` is documentation.
- **The contract a caller must satisfy** — accepted ranges, whether `undefined`/`null` is meaningful, whether a
  returned array/object is safe to mutate, whether the function is async-safe to call concurrently.
- **`@param name - description`** for every parameter, including destructured-object parameters (document the
  object itself, then each property inline in the description or via a nested list). State whether `undefined`
  is meaningful for an optional parameter.
- **`@returns`**, unless the function/method returns `void`. Say what an empty array, `undefined`, or a rejected
  promise means.
- **`@throws {ErrorType} description`** for every error a caller can reasonably handle. This is not decoration:
  the `@throws` list is what the tests in `testing.md` are written against.

```ts
/**
 * Computes the total price of an order, including tax.
 *
 * @param order - the order to price; its `items` array is read but never mutated.
 * @param taxRate - the tax rate to apply, expressed as a fraction (e.g. `0.21` for 21%).
 * @returns the order total, in the same currency as `order.items`, rounded to two decimal places.
 * @throws {RangeError} if `taxRate` is negative.
 */
export function computeOrderTotal(order: Order, taxRate: number): number {
  if (taxRate < 0) {
    throw new RangeError("taxRate must not be negative");
  }
  return round2(order.items.reduce((sum, item) => sum + item.price * item.quantity, 0) * (1 + taxRate));
}
```

Order the block tags `@param` (in parameter order), `@returns`, `@throws`, then the rest (`@remarks`,
`@example`, `@see`, `@deprecated`).

## Tag reference

- **`@param name - description`** — a hyphen separates the name from the description (the TSDoc convention;
  differs from JSDoc's bare space). One per parameter, in declaration order.
- **`@returns description`** — omit entirely on a `void`/`Promise<void>` return, never write an empty one.
- **`@throws {ErrorType} description`** — one tag per distinct error type/condition; group multiple conditions
  for the same error type into one tag's description rather than repeating the tag.
- **`@remarks`** — supplementary detail that doesn't belong in the one-sentence summary: performance
  characteristics, thread/concurrency-safety, why an unusual approach was taken. Optional; add it when the
  summary alone would mislead.
- **`@example`** — a short, runnable snippet in a fenced ` ```ts ` block, on every public entry point whose usage
  isn't obvious from its signature alone (a hook, a class's main factory, a non-trivial utility). Skip it on a
  simple getter or a one-line pass-through.
- **`@public` / `@internal`** — mark `@internal` on anything exported only for cross-module use within the
  package but not meant for consumers of the published package (TypeDoc excludes `@internal` symbols from the
  generated docs when configured to). `@public` is the default and usually omitted; add it explicitly only where
  the project already does, for symmetry with `@internal` elsewhere in the same file.
- **`@deprecated description`** — always paired with saying what to use instead (`@deprecated Use {@link
  computeOrderTotal} instead.`). Add it only when the task is actually deprecating something, never speculatively.
- **`{@link Symbol}` / `{@link Symbol.member}`** — for a cross-reference the reader might want to follow (another
  exported symbol, a related type). Use plain backtick-code for a value/literal that isn't itself an exported,
  linkable symbol.

## What must be documented

- Every **exported** function, class, method, interface, type alias, and enum (and every enum member whose name
  doesn't already say everything).
- Every **public** class member (fields and methods) on an exported class; a `private`/`protected` member needs a
  doc comment only when the *why* isn't obvious from the name — see "Private and unexported members" below.
- A React/Angular component's **props/inputs interface** — document the interface itself and, when a prop's
  purpose or default isn't obvious from its name and type, the individual prop.
- A custom **hook**: document what it returns (especially for a tuple/object return, name each piece), any side
  effect it has (subscribes, fetches, sets a timer), and cleanup behavior.

## Private and unexported members

Document a private/unexported member whenever the *why* isn't obvious from the name — an invariant it maintains,
why an obvious-looking simplification would be wrong. A private helper with a self-describing name and a
three-line body needs nothing. Non-exported (module-private) helpers follow the same bar as private class
members, not the exported-symbol bar.

## Mechanics that trip up a build

- TSDoc is **not** JSDoc: don't use JSDoc-only tags TypeDoc/Compodoc don't recognize (`@augments`, `@memberof`)
  unless the project's doc tool explicitly supports them — stick to the tag reference above.
- Don't start a summary with "This function…" or "Returns a…"-style filler that pushes the real content past the
  first sentence.
- A React functional component still gets a doc comment on the function/`const` itself, not only on its props
  interface — TypeDoc/Compodoc render both.
- `@example` code blocks are checked for fenced-code-block syntax by some lint configs (`eslint-plugin-tsdoc`) —
  always close the ` ``` ` fence.
- Keep `@param` names in sync with the actual parameter names; a TSDoc lint rule flags a mismatch and a stale
  name is worse than no name at all.
