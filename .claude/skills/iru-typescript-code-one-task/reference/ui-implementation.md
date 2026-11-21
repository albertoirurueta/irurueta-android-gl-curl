# UI contract on React, Angular, Ionic and Hilla

How `ui-contract.md` maps onto the web front ends this skill covers. Read the contract first; this file only says
*how*, with the APIs each framework actually has. The shared part (tests, helper, accessibility) comes first, then
one section per framework — read the shared part plus **only** your framework's section (Ionic: also React's or
Angular's, matching its flavor; Hilla: also React's). React Native is not here — it has its own skill.

## Shared: what runs where

| Level | Runs | Tool |
|---|---|---|
| Behaviour (logic, hooks, reducers, view models, formatters) | Node, jsdom | the project's unit runner (Vitest/Jest/`ng test`) + Testing Library — unchanged, never screenshots |
| Component test | real Chromium, against a **component gallery** page served by the dev server | Playwright's built-in `mount()` fixture + one *story* per state |
| Screen test | real Chromium; the screen mounted from a story with fake data (no backend), or the built app with `page.route()` fakes | `mount()` or `page.goto()` |
| Flow test (e2e) | real Chromium against the built app (`vite preview`, `ng serve`, the Spring Boot app) with external services faked | `page.goto()` + navigation |

**Component testing model.** `@playwright/experimental-ct-react`/`-vue` are frozen at 1.62 and no longer updated;
since `@playwright/test` 1.62, component testing is a plain Playwright project whose `baseURL` points at a
**gallery** page you own. The built-in `mount(storyId, props?)` fixture navigates there, calls the gallery's
`window.mount({ story, props })`, and returns a `Locator` for `#root` with `update(props)` and `unmount()`.
A **story** is a tiny wrapper in a `*.story.tsx`/`*.story.ts` file next to the component: one named export per
visual state, holding fixture data and recording emitted events into a hidden form the test asserts on
(`toHaveValue`). Props passed to `mount` must be serializable — callbacks live inside the story. This works for
every framework below because only the gallery is framework-specific. Use Cypress only if the repository already
does; then keep its own component runner and still write the contract's screenshots and axe checks.

Layout (one per project, at the Playwright project root — the app root, or the Hilla web module):

```text
playwright.config.ts               # projects: "components" (baseURL = gallery) + "e2e" (baseURL = built app)
playwright/gallery/index.html      # <main id="root"></main>
playwright/gallery/main.tsx|ts     # window.mount / window.unmount (framework-specific, below)
e2e/support/ui-screenshots.ts      # the shared helper — reference/ui-screenshots.ts, verbatim
e2e/components/<Component>.spec.ts # component + screen tests (mount)
e2e/<flow>.spec.ts                 # flow tests (page.goto)
src/**/<Component>.story.tsx       # stories, next to the component (Angular: <name>.story.ts)
```

```ts
// playwright.config.ts — the components project (merge into the existing config; keep its e2e project)
{
  name: 'components',
  testDir: './e2e/components',
  use: { ...devices['Desktop Chrome'], baseURL: 'http://localhost:5173/playwright/gallery/index.html',
         serviceWorkers: 'block', reuseContext: true },
},
// …and a webServer entry for the gallery's dev server (webServer accepts an array):
{ command: 'npm run dev -- --port 5173 --strictPort', url: 'http://localhost:5173/playwright/gallery/index.html',
  reuseExistingServer: !process.env.CI },
```

Give the e2e project `testIgnore: 'components/**'` so each spec runs once. Story ids are the file path under `src/`
without `.story.*`, plus the export name (`features/orders/OrderList/ManyItems`); any unique suffix resolves
(`OrderList/ManyItems`). Type per-test props with `mount<typeof ManyItems>(…)` and `import type` from the story.

**The shared helper** — `e2e/support/ui-screenshots.ts`. If the project has none, create it from this skill's
`reference/ui-screenshots.ts` verbatim (then let the project's Prettier format it). It resolves
`UI_SCREENSHOTS_DIR` else `<repo root>/.ui-screenshots` (root = nearest ancestor with `.git`), reads
`.ui-quality.yml` without a YAML dependency, validates and builds the contract file name, and exports:

```ts
captureUiMatrix(page, { kind: 'component', id: 'OrderList', state: 'many-items' }, () => mount('OrderList/ManyItems'),
  { accessibility: { testInfo, include: '#root' } })   // every direction × theme in .ui-quality.yml, axe per cell
checkAccessibility(page, testInfo, '#root')          // one-off axe check at the .ui-quality.yml level
```

For each matrix cell it calls `page.emulateMedia({ colorScheme, forcedColors, reducedMotion: 'reduce' })`, sets
`dir` on `<html>` through a disposable `page.addInitScript` (so it survives the navigation `mount()`/`goto()` does
and is in place before the framework boots — Angular CDK reads `dir` once at start-up), calls your `render`
callback, waits for `document.fonts.ready`, and writes `<kind>/<Id>/<state>--<dir>--<theme>.png` (the `#root`
locator for a component, a full-page capture for a screen/flow). `high-contrast` in `themes` emulates
`forced-colors: active`; add `variants: ['w1280']`/`['large-text']` for size or zoom captures. Needs
`@playwright/test` ≥ 1.62 (`mount`, disposable init scripts) and `@axe-core/playwright` 4.x.

A typical component spec — behaviour once (LTR, light), then the matrix per state:

```ts
import { expect, test } from '@playwright/test'
import type { TypicalData } from '../../src/features/orders/OrderList.story'
import { captureUiMatrix } from '../support/ui-screenshots'

test('OrderList emits onOpen with the row id', async ({ mount }) => {
  const list = await mount<typeof TypicalData>('OrderList/TypicalData', { count: 3 })
  await list.getByRole('button', { name: /order 2/i }).click()
  await expect(list.getByTestId('opened')).toHaveValue('o2')
})

for (const [state, story] of [['typical-data', 'TypicalData'], ['empty', 'Empty'], ['many-items', 'ManyItems']]) {
  test(`OrderList ${state} matrix`, async ({ page, mount }, testInfo) => {
    await captureUiMatrix(page, { kind: 'component', id: 'OrderList', state }, () => mount(`OrderList/${story}`),
      { accessibility: { testInfo, include: '#root' } })
  })
}
```

Overlays (menus, dialogs, toasts, Vaadin/Ionic popovers) render outside `#root`: open them, then return `page` from
`render` and pass no `include`. Flow captures use `kind: 'flow'`, a kebab-case flow id and `NN-<step>` states.

**Determinism.** Fixed fixture data (no `Math.random`/`Date.now` in stories — inject a clock or pass dates as
props), `reducedMotion: 'reduce'` plus `animations: 'disabled'` (the helper does both), a fixed viewport from the
device preset, network faked with `page.route()` registered **before** `mount()`, fonts self-hosted or awaited.
Right-to-left fixtures carry real Arabic or Hebrew text; `long-content` fixtures carry long unbroken strings.

**Accessibility.** `.ui-quality.yml` decides: `enforce` makes violations fail (soft assertions, so the whole matrix
is still captured), `report` attaches them to the test and adds an `accessibility` annotation, `off` skips axe —
the coding defaults still apply. The helper runs the WCAG 2.0/2.1/2.2 A+AA tags; never disable a rule to get green
— fix the markup or colour token. Lint catches the static part: `eslint-plugin-jsx-a11y` (React/Hilla/Ionic React)
and angular-eslint's template accessibility rules (Angular/Ionic Angular).

**RTL and themes in code.** CSS logical properties only (`margin-inline-start`, `padding-inline`, `inset-inline-end`,
`border-inline-end`, `text-align: start`, `inline-size`/`block-size`); mirror directional icons with
`:dir(rtl) .icon-chevron { transform: scaleX(-1) }`; colours from custom properties redefined under
`@media (prefers-color-scheme: dark)` (or the design system's dark switch) with both palettes meeting 4.5:1;
`:focus-visible` outlines; `rem` font sizes; touch targets ≥ 24×24 CSS px.

## React (Vite)

- **Screen** = a route component (`src/routes/…`/`src/features/<x>/<X>Page.tsx`) owning data (React Query/SWR/the
  project's hook) and navigation; **component** = a function component whose props are its UI state plus
  `on<Intent>` callbacks. Composition through `children` and named `ReactNode` slots (`header`, `actions`), never by
  wrapping-and-overriding another component. Controlled inputs (`value` + `onValueChange`) only for form fields.
- **Collections**: `@tanstack/react-virtual` (or the virtualizer the repository already uses — add the dependency
  only if the task asked for the list), one memoized item component, keys from ids:

```tsx
export function OrderList({ items, hasMore, loading, onOpen, onLoadMore }: OrderListProps): JSX.Element {
  const scrollRef = useRef<HTMLDivElement>(null)
  const virtualizer = useVirtualizer({
    count: items.length, getScrollElement: () => scrollRef.current, estimateSize: () => 48,
    getItemKey: (index) => items[index].id, overscan: 8,   // horizontal lists: horizontal: true, isRtl
  })
  const rows = virtualizer.getVirtualItems()
  const lastIndex = rows.at(-1)?.index ?? -1
  useEffect(() => {
    if (hasMore && !loading && lastIndex >= items.length - 5) onLoadMore()
  }, [hasMore, loading, lastIndex, items.length, onLoadMore])
  return (
    <div ref={scrollRef} className={styles.viewport} aria-busy={loading}>
      <ul role="list" style={{ position: 'relative', blockSize: virtualizer.getTotalSize() }}>
        {rows.map((row) => (
          <li key={row.key} data-index={row.index} ref={virtualizer.measureElement}
              aria-setsize={items.length} aria-posinset={row.index + 1}
              style={{ position: 'absolute', insetInlineStart: 0, inlineSize: '100%',
                       transform: `translateY(${row.start}px)` }}>
            <OrderRow row={items[row.index]} onOpen={onOpen} />
          </li>
        ))}
      </ul>
    </div>
  )
}
export const OrderRow = memo(function OrderRow({ row, onOpen }: OrderRowProps): JSX.Element { /* … */ })
```

  The list's state (`items`, paging flags, selected id) lives in the screen's hook/query cache, keyed by id; the
  query layer (`useInfiniteQuery`, SWR infinite) does the diffing and paging.
- **Stories** (`OrderList.story.tsx`), one export per state; a story records events into a hidden form:

```tsx
export const TypicalData = ({ count = 5 }: { count?: number }) => {
  const [opened, setOpened] = useState('')
  return (<>
    <OrderList items={rows(count)} hasMore={false} loading={false} onOpen={setOpened} onLoadMore={() => {}} />
    <form hidden><input data-testid="opened" readOnly value={opened} /></form>
  </>)
}
export const ManyItems = () => <OrderList items={rows(10_000, 'طلب')} hasMore loading={false} … />
```

- **Gallery** (`playwright/gallery/main.tsx`) — `import.meta.glob('../../src/**/*.story.tsx')`, resolve the id,
  `root ??= createRoot(…)` (reused so `update()` keeps state) and `flushSync(() => root.render(<StrictMode><Story
  {...props} /></StrictMode>))` so a render error rejects `mount()`. Import the app's global CSS and wrap stories in a
  shared decorator (theme, router, query client) inside the story file, not in the gallery. Vite serves the gallery
  from the app's own dev server and `vite build` ignores it. Add `playwright/gallery` and `e2e` to a tsconfig with
  `"moduleResolution": "bundler"`, `"jsx": "react-jsx"`, `"types": ["node", "vite/client"]`.

## Angular

- **Screen** = a routed standalone component (`loadComponent` in `app.routes.ts`) that injects the store/service and
  passes signals down; **component** = a standalone `OnPush` component with `input()`/`input.required()` for its UI
  state and `output()` for events; `model()` only for form-field-like controls. Compose with `<ng-content>`/named
  `select` slots and `ng-template` inputs; never extend another component class.
- **Collections**: `@angular/cdk/scrolling` — `<cdk-virtual-scroll-viewport itemSize="48">` +
  `*cdkVirtualFor="let item of items(); trackBy: trackById"` around one item component; `scrolledIndexChange` drives
  `loadMore`. Small static lists use `@for (item of items(); track item.id)`. Keep the list in a signal store keyed
  by id; replace it immutably so `trackBy` diffs.

```ts
@Component({
  selector: 'app-order-list', imports: [ScrollingModule, OrderRow], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<cdk-virtual-scroll-viewport itemSize="48" class="viewport" (scrolledIndexChange)="onScrolled($event)">
    <app-order-row *cdkVirtualFor="let item of items(); trackBy: trackById" [row]="item" (open)="open.emit($event)" />
  </cdk-virtual-scroll-viewport>`,
})
export class OrderList {
  readonly items = input.required<readonly OrderRowState[]>()
  readonly open = output<string>()
  readonly loadMore = output<void>()
  protected readonly trackById = (_: number, item: OrderRowState): string => item.id
  protected onScrolled(index: number): void { if (index + 20 >= this.items().length) this.loadMore.emit() }
}
```

- **Stories** (`order-list.story.ts`): each export is a standalone wrapper component whose template uses the
  component with fixture inputs and records outputs (`(open)="opened.set($event)"` + a hidden `<input
  data-testid="opened" [value]="opened() ?? ''">`). Story `input()`s receive `mount(id, props)` via `setInput`.
  Exclude `src/**/*.story.ts` from `tsconfig.app.json`.
- **Gallery** — the Angular builder has no `import.meta.glob`, so `playwright/gallery/stories.ts` is a registry
  (one `...storiesOf('app/orders/order-list', orderList)` line per story file — add yours), and
  `playwright/gallery/main.ts` boots `createApplication(appConfig)` (the app's own providers; zoneless is the
  default from Angular 21), then per mount `createComponent(story, { environmentInjector:
  appRef.injector, hostElement })` + `appRef.attachView(ref.hostView)`, reusing the ref while the story id is
  unchanged, `ref.setInput(name, value)` per prop, and `await appRef.whenStable()`. It is served by a `gallery`
  build configuration in `angular.json` (`browser: playwright/gallery/main.ts`, `index:
  playwright/gallery/index.html`, `tsConfig: tsconfig.gallery.json`, dev options) and a `gallery` serve
  configuration: `ng serve --configuration gallery --port 4300`, `baseURL: 'http://localhost:4300/'` (a
  `webServer` entry; `ng e2e` keeps serving the app itself). `e2e/tsconfig.json` needs `"types": ["node"]` for the
  helper. This replaces `@sand4rt/experimental-ct-angular`, which is pinned to the frozen
  `@playwright/experimental-ct-core`.
- Behaviour tests stay on `TestBed` + `ComponentRef.setInput` under `ng test` (`angular.md`).

## Ionic (Angular or React flavor)

- Ionic components (`ion-list`, `ion-item`, `ion-button`) are the building blocks; screens are `IonPage`/`ion-content`
  routes; the rest follows the flavor's section above.
- **Collections**: Ionic has no virtual scroll of its own (`ion-virtual-scroll` was removed in v6). Angular flavor:
  CDK virtual scroll inside `<ion-content [scrollY]="false">`; React flavor: `@tanstack/react-virtual` over a scroll
  container inside `IonContent`. Paging with `<ion-infinite-scroll (ionInfinite)="loadMore($event)">` and
  `event.target.complete()` once the page arrived; pull-to-refresh with `ion-refresher`.
- **Themes and direction**: import the dark palette that follows the system (`@ionic/<flavor>/css/palettes/
  dark.system.css`) so `emulateMedia({ colorScheme })` switches it; Ionic honours `dir="rtl"` on `<html>` and uses
  logical properties itself. Use `ios`/`md` as a `<variant>` when both modes are captured.
- **Gallery**: the flavor's gallery plus Ionic set-up — React: `setupIonicReact()` once and stories wrapped in
  `<IonApp>`; Angular: `provideIonicAngular()` in `createApplication`'s providers. Import Ionic's core CSS bundle in
  the gallery like the app's entry does. Capacitor plugins are mocked inside stories.

## Hilla (Vaadin React)

- **Screen** = a file-router view (`src/main/frontend/views/**`, default export, `config`), owning the generated
  endpoint calls through a hook; **component** = a presentational React component in
  `src/main/frontend/components/` built from `@vaadin/react-components` — props in, `on<Intent>` out.
- **Collections**: `<VirtualList items={…}>{({ item }) => <OrderRow row={item} onOpen={onOpen} />}</VirtualList>`
  for card/row lists, `<Grid>` with a lazy data provider (`dataProvider` / `useGridDataProvider` from
  `@vaadin/hilla-react-crud`) for tabular data paged from the endpoint. `<ComboBox>`/`<Select>` with many items use
  their data provider too. Never map thousands of items into plain JSX. A `<VirtualList>` whose items hold no
  focusable element fails axe's `scrollable-region-focusable` — give it `tabIndex={0}` and an `aria-label` (verified).
- **Stories live outside `views/`** (every `.tsx` under `views/` becomes a route): `src/main/frontend/stories/
  <Component>.story.tsx`, data passed as props — no endpoint calls; stories of a view render it with the endpoint
  module mocked through a fake hook passed as a prop, or test the view as a screen against the running app.
- **Gallery**: Vaadin's generated Vite config sets `root` to `src/main/frontend` and needs a Maven/Gradle
  `prepare-frontend` first, so the gallery runs on its own `playwright/vite.config.ts` (`@vitejs/plugin-react`,
  alias `Frontend` → `src/main/frontend`, port 5174) serving `playwright/gallery/index.html`; the gallery is the React
  one with the glob pointed at `../../src/main/frontend/**/*.story.tsx`.
- **Themes**: Lumo's dark variant is the `theme="dark"` attribute — pass `onVariant: (page, _dir, theme) =>
  page.evaluate((dark) => document.documentElement.setAttribute('theme', dark ? 'dark' : ''), theme === 'dark')`
  to `captureUiMatrix` unless the app already follows `prefers-color-scheme`. Vaadin components honour `dir="rtl"`.
- Screen/flow tests run against the Spring Boot app (`webServer` starting it with the backend's external services
  faked, Testcontainers where it needs them) — the slow, lock-serialized stage of the group skill.

## Assistive technologies

Applies unless `.ui-quality.yml` says `accessibility: none` (opted out entirely - then omit these attributes and checks). Rules and levels: `reference/ui-contract.md` §8 ("Platform assistive-technology rules", "Visual comfort").

- Prefer native semantic elements (`button`, `a`, `nav`, `main`, `label`/`input`, headings in order) over `div` + `role`; add `aria-*` only where HTML has no equivalent.
- Icon-only controls get `aria-label`; images get `alt` (empty `alt=""` when decorative); dynamic updates use `aria-live`; modals trap and restore focus.
- Honour `prefers-reduced-motion` and `forced-colors`; size text in `rem`, never fixed `px` heights that clip on zoom.
- Angular: use `@angular/cdk/a11y` (`LiveAnnouncer`, `FocusTrap`, `FocusMonitor`, `ListKeyManager`); keep `@angular-eslint/template` accessibility rules on.
- React: `eslint-plugin-jsx-a11y`; Ionic: set `aria-label` on `ion-button`/`ion-icon`-only controls; Hilla/Vaadin components expose accessible-name properties - set them.
- React Native components in this key: `accessibilityRole`, `accessibilityLabel`, `accessibilityState`, `accessible`, and `allowFontScaling` left on.

## Sources

- Playwright component testing (stories and gallery, `mount` fixture): <https://playwright.dev/docs/test-components>
- Playwright `page.emulateMedia`, `addInitScript`, screenshots: <https://playwright.dev/docs/api/class-page>
- Playwright accessibility testing with axe: <https://playwright.dev/docs/accessibility-testing>,
  `@axe-core/playwright`: <https://github.com/dequelabs/axe-core-npm/tree/develop/packages/playwright>
- TanStack Virtual: <https://tanstack.com/virtual/latest/docs/framework/react/react-virtual>
- Angular CDK scrolling: <https://material.angular.dev/cdk/scrolling/overview>; Angular components, inputs, outputs,
  content projection: <https://angular.dev/guide/components>; `createApplication`/`createComponent`:
  <https://angular.dev/api/platform-browser/createApplication>, <https://angular.dev/api/core/createComponent>
- Ionic infinite scroll and virtual scroll guidance: <https://ionicframework.com/docs/api/infinite-scroll>,
  <https://ionicframework.com/docs/angular/virtual-scroll>, <https://ionicframework.com/docs/react/virtual-scroll>;
  dark mode: <https://ionicframework.com/docs/theming/dark-mode>; RTL: <https://ionicframework.com/docs/layout/global-stylesheets>
- Vaadin `VirtualList`, `Grid` data providers, Lumo dark variant: <https://vaadin.com/docs/latest/components/virtual-list>,
  <https://vaadin.com/docs/latest/components/grid>, <https://vaadin.com/docs/latest/styling/lumo/lumo-variants>
- eslint-plugin-jsx-a11y: <https://github.com/jsx-eslint/eslint-plugin-jsx-a11y>; angular-eslint template rules:
  <https://github.com/angular-eslint/angular-eslint/tree/main/packages/eslint-plugin-template>
- MDN CSS logical properties: <https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_logical_properties_and_values>
