# TSDoc

**Every exported symbol is fully documented** — function, component, hook, class, interface, type alias, enum,
and native-module facade, without exception. The group skill audits this later via `iru-typescript-tsdoc` (TSDoc
completeness, plus a `typedoc --emit none`-style check when the project configures TypeDoc), so writing it now is
cheaper than fixing it after validation. (The long-form original of these rules is
`iru-typescript-code-one-task/reference/tsdoc.md`, if installed.)

## What every exported declaration needs

- **A summary sentence** first, standing alone: what the thing *is* or *does*, not what its name already says.
- **The contract**: accepted ranges, whether `undefined` is meaningful, whether a returned array/object may be
  mutated, which platforms it works on.
- **`@param name - description`** for every parameter (hyphen after the name — TSDoc, not JSDoc), in order.
- **`@returns`** unless the return is `void`/`Promise<void>`; say what an empty result or `undefined` means.
- **`@throws {ErrorType} description`** for every error a caller can handle — including a rejected promise from a
  native module (name the rejection code). The `@throws` list is what the tests in `testing.md` are written against.
- Then `@remarks`, `@example` (fenced ` ```tsx `), `@see`, `@deprecated` (always with the replacement), `@internal`.

## React Native specifics

- **Components**: document the component function itself (one or two sentences: what it renders and when to use
  it) *and* its `<Component>Props` interface — every prop whose purpose, default, or platform behaviour isn't obvious
  from its name and type gets its own comment:

  ```tsx
  /** Props for {@link OrderRow}. */
  export interface OrderRowProps {
    /** The order to display; only `id`, `title`, and `total` are read. */
    readonly order: OrderSummary;
    /** Called with the order id when the row is pressed. Not called while `disabled`. */
    readonly onPress: (orderId: string) => void;
    /** Disables the press feedback and announces the row as disabled to screen readers. Defaults to `false`. */
    readonly disabled?: boolean;
  }

  /**
   * A pressable list row summarising an order, sized for `FlatList` with a fixed item height.
   *
   * @param props - see {@link OrderRowProps}.
   */
  export function OrderRow({ order, onPress, disabled = false }: OrderRowProps): React.JSX.Element { ... }
  ```

- **Hooks**: what the hook returns (name each piece of a tuple/object), its side effects (subscriptions, timers,
  `AppState`/network listeners, storage reads), when it re-runs (screen focus vs. mount), and cleanup behaviour.
- **Platform-specific behaviour**: say it in the doc (`@remarks iOS only — returns undefined on Android.`). For
  platform files (`Foo.ios.tsx` / `Foo.android.tsx`), document the shared exports identically in each file (or in the
  shared `Foo.types.ts`), so whichever file TypeDoc resolves carries the full contract.
- **Native-module facades and specs**: document the TS facade the app imports (each method, its rejection codes,
  unsupported-platform behaviour). The Codegen spec file (`specs/Native<Name>.ts`) gets a short summary on the `Spec`
  interface and each method — Codegen ignores comments, so they're for readers.
- **Default-exported route files** (Expo Router `app/**`, the CLI template's `App.tsx`): a one-line summary on the
  default-exported component is enough.

## Mechanics that trip up a build

- Don't use JSDoc-only tags (`@augments`, `@memberof`, `@type`) TypeDoc doesn't understand.
- Close every ` ``` ` fence in `@example`; `eslint-plugin-tsdoc` (when configured) flags malformed blocks.
- Keep `@param` names in sync with the signature (destructured props are documented as `props`).
- Private, module-internal helpers need a comment only when the *why* isn't obvious.
