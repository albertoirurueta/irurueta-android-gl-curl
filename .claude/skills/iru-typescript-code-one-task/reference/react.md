# React

Conventions specific to a React (Vite + React 19, `iru-setup-react-web`) project. Everything in `code-style.md`,
`tsdoc.md`, and `testing.md` still applies; this file adds what's specific to components, hooks, and state.

## Function components only

- **Function components, never class components.** No exceptions — this catalog's scaffold ships nothing that
  needs a class component (no legacy error-boundary requirement; see "Error boundaries" below for the one
  remaining case that still needs a class).
- **Named export, `PascalCase` function**, props typed with an interface named `<Component>Props`:

  ```tsx
  export interface OrderSummaryProps {
    readonly order: Order;
    readonly onCheckout: (orderId: string) => void;
  }

  /**
   * Displays an order's line items and total, with a checkout action.
   *
   * @param props - see {@link OrderSummaryProps}.
   */
  export function OrderSummary({ order, onCheckout }: OrderSummaryProps): JSX.Element {
    return (
      <section>
        <h2>{order.id}</h2>
        <button onClick={() => onCheckout(order.id)}>Checkout</button>
      </section>
    );
  }
  ```

- Destructure props in the function signature; don't pass around a `props` object.
- **Keys**: a stable, unique id from the data (never the array index, unless the list is static and never
  reordered/filtered).
- **Presentational vs. screen** (UI contract §3–§4): a presentational component's props are its UI state plus
  `on<Intent>` callbacks carrying typed payloads (`onCheckout(orderId)`, never the raw click event); data fetching
  and navigation stay in the route/screen component. A data-driven or long list is a collection component over a
  virtualizer plus one memoized item component — never a bare `items.map` over unbounded data. See
  `ui-implementation.md` (React section) for the virtualized list, stories and component tests.

## Rules of hooks

- Call hooks only at the top level of a component or another hook — never inside a condition, loop, or nested
  function. `eslint-plugin-react-hooks` (`react-hooks/rules-of-hooks`) enforces this; write code that already
  satisfies it.
- **Exhaustive dependency arrays** (`react-hooks/exhaustive-deps`): every reactive value the effect/callback/memo
  reads belongs in its dependency array. Don't suppress the lint rule to silence a warning — fix the dependency,
  or restructure so the value doesn't need to be a dependency (a ref for something intentionally read-but-not-
  reactive, a function moved inside the effect, a stable value hoisted outside the component).
- **Custom hooks** start with `use`, return either a single value, a tuple (`[value, setValue]` style, like
  `useState`), or a small readonly object — document each returned piece in the TSDoc when a tuple/object return
  isn't self-explanatory.
- Prefer `useCallback`/`useMemo` only where there's a measured or structurally clear reason (a dependency of
  another hook, a prop passed to a memoized child) — not as a reflexive wrapper on every function/value.

## State colocation

- **State lives as close to where it's used as possible.** Lift it only as far up the tree as the components that
  actually need to share it — not to a top-level store by default.
- **Derive, don't duplicate.** A value computable from existing props/state (a filtered list, a total) is
  computed at render time (optionally memoized), not stored as its own `useState`.
- **Server/remote state is not component state.** Data fetched from an API belongs in a dedicated data-fetching
  layer (React Query/SWR, or the project's existing convention) — reserve `useState`/`useReducer` for genuinely
  local UI state (a form field, a toggle, a selected tab).
- **`useReducer` over several related `useState` calls** once a component's state transitions depend on each
  other (a wizard's step, a form's field-plus-validity-plus-submitting state) — a reducer makes the valid
  transitions explicit instead of implicit in the order `setX` calls happen to run.

## Suspense and error boundaries

- **`<Suspense fallback={...}>`** around a subtree that reads data via a Suspense-compatible source (`React.lazy`,
  a Suspense-enabled data-fetching hook) — don't hand-roll a `loading` boolean around code that already suspends.
- **Error boundaries still require a class component** (`static getDerivedStateFromError`, `componentDidCatch`)
  — React has no Suspense-style hook equivalent for catching a rendering error as of this catalog's baseline.
  Use the project's existing error-boundary component (or a small shared one, `<AppErrorBoundary>`) rather than
  writing a new one per feature; wrap it around a route/feature boundary, not around every component.
- Place a `Suspense` boundary and its matching error boundary together, at the same granularity — a route/feature
  boundary usually, occasionally a slow individual widget.

## File layout: `src/features/<name>/`

This catalog's React scaffold organizes by feature, not by technical layer:

```
src/
  features/
    orders/
      OrderSummary.tsx
      OrderSummary.test.tsx
      useOrderTotal.ts
      useOrderTotal.test.ts
      types.ts
      index.ts          # re-exports the feature's public surface
    checkout/
      ...
  components/            # shared, cross-feature UI (buttons, layout primitives)
  hooks/                 # shared, cross-feature hooks
  lib/                   # framework-agnostic utilities, API clients
```

- A new component/hook for one feature goes inside that feature's directory, not in the shared `components/`/
  `hooks/` — promote something to `components/`/`hooks/`/`lib/` only when a second feature actually needs it, not
  preemptively.
- `index.ts` in a feature directory re-exports only what other features/routes are meant to import — everything
  else in the directory is that feature's private implementation detail, even though TypeScript can't enforce
  that boundary; don't reach into another feature's internals via a deep import path.
- Co-locate a component's test next to it (`OrderSummary.test.tsx` beside `OrderSummary.tsx`), matching
  `testing.md`'s default.

## Styling and accessibility

- Use the project's existing styling approach (CSS Modules, Tailwind, styled-components) — don't introduce a
  second one for a single task.
- Every interactive element has an accessible name (visible text, `aria-label`, or `aria-labelledby`) — this is
  also what makes `testing.md`'s `getByRole` query priority possible in the first place.
- **CSS logical properties** (`margin-inline-start`, `padding-inline`, `inset-inline-end`, `text-align: start`) — never
  `left`/`right` — and colours from tokens redefined for dark mode, so the right-to-left × light/dark screenshot
  matrix of `ui-implementation.md` renders correctly. `eslint-plugin-jsx-a11y` flags the static accessibility
  mistakes when the project has it.
