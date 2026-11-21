# User interfaces — Laravel, WordPress, PrestaShop

How `ui-contract.md` maps onto server-rendered PHP UI. Read the contract first: it owns the rules (decompose
before building, composition, inputs/events, virtualized collections, UI tests, screenshots, accessibility); this
file only says which real API implements each rule per flavor. A plain Composer library has no UI — skip this file.

## Screens, components and navigation per flavor

| Concept | Laravel | WordPress | PrestaShop |
|---|---|---|---|
| **Screen** | A named route + controller action returning a view (`resources/views/orders/index.blade.php`), or a full-page Livewire component | A block-theme template (`templates/*.html`), a classic template-hierarchy file, an admin page callback (`add_menu_page`) | A front controller + `views/templates/front/*.tpl`; a Symfony admin controller + `views/templates/admin/*.html.twig`; a `display*` hook's template |
| **Component** | Blade component — anonymous (`resources/views/components/order-row.blade.php`) or class (`app/View/Components`); a Livewire component (`app/Livewire`) | A block (`block.json` + `edit.js` + `render.php`), a pattern (`patterns/*.php`), a template part (`parts/*.html`), a `get_template_part()` partial | A Smarty partial (`views/templates/**/_partials/*.tpl`), a Twig partial (`views/templates/admin/_partials/*.html.twig`), a hook template (`views/templates/hook/*.tpl`) |
| **Navigation (one place)** | `routes/web.php` named routes | Rewrite rules/permalinks, the admin-menu registration, the theme's templates | `config/routes.yml` (admin), front controllers + `getModuleLink()` |

Screens prepare data; components render it. The controller/front controller/`render.php` builds **immutable view
data** (a `final readonly class OrderRowData` with a `fromModel()` factory, a plain array shaped by a presenter) with
every derived value already computed — formatted totals, `canCancel`, the URL each action posts to — so templates
contain no queries, no permission logic and no `Model::find()`. A component's "event" in server-rendered HTML is a
link or form submit to a URL the **screen** passes in as an input (or a Livewire/Alpine/Interactivity-API event),
never a route the component hard-codes.

**Doc comments** (contract §4) go at the top of each template: Blade `{{-- --}}`, Twig `{# #}`, Smarty `{* *}`, a
PHPDoc block in `render.php`/component classes, `description` in `block.json` — purpose, inputs, events, states.

## Laravel

- **Blade components**: `@props` declares the inputs (with defaults); `$attributes->class([...])`/`->merge()`
  forwards extra attributes; content comes in through `{{ $slot }}` and named slots (`<x-slot:actions>`).
  Compose with `<x-…>` tags, including layouts as components (`<x-layouts.app>`) — for new screens prefer a layout
  component over `@extends`/`@section` chains, but follow an app that consistently uses `@extends` (README rule 1).
- **Livewire** (a screen or a stateful component): public properties are its state (`#[Locked]` on ids the
  browser must not change), methods are actions (`wire:click="open({{ $row->id }})"`), child→parent events via
  `$this->dispatch('order-opened', id: $id)` and `#[On('order-opened')]`. `wire:model` (`.live.debounce.300ms` for
  search) **only on form fields** — never on a collection or a model. Client-only events from Alpine:
  `x-on:click="$dispatch('order-open', { id: {{ $row->id }} })"`.
- Fast behaviour tests stay PHP: `$this->blade('<x-order-row :order="$order" />', ['order' => $data])->assertSee(…)`,
  `$this->component(OrderRow::class, [...])`, `Livewire::test(OrderList::class)->call('loadMore')->assertSee(…)
  ->assertDispatched('order-opened')`. They prove behaviour; screenshots come only from the browser tests below.

```blade
{{-- OrderRow — one order in OrderList. In: order (OrderRowData), selected, openUrl. Out: open (link).
     States: default, selected, long-content. --}}
@props(['order', 'selected' => false, 'openUrl'])
<li {{ $attributes->class(['order-row', 'order-row--selected' => $selected]) }} wire:key="order-{{ $order->id }}">
    <a href="{{ $openUrl }}" class="order-row__link">{{ $order->number }}</a>
    <span class="ms-auto">{{ $order->totalFormatted }}</span>
    {{ $actions ?? '' }}
</li>
```

**Collections** — server-side paging, one item component, stable keys:

```php
// OrderController@index — bounded, keyset-paginated, mapped to immutable item data.
$orders = Order::query()->whereBelongsTo($request->user())->latest('id')
    ->cursorPaginate(25)->through(fn (Order $o) => OrderRowData::fromModel($o));
return view('orders.index', ['orders' => $orders]);
```

```blade
<ul class="order-list">
    @forelse ($orders as $order)
        <x-order-row :order="$order" :open-url="route('orders.show', $order->id)" />
    @empty
        <x-empty-state :message="__('orders.empty')" />
    @endforelse
</ul>
{{ $orders->links() }}
```

`paginate()` when the UI shows page numbers, `cursorPaginate()` for "next/previous" and infinite lists on large
tables. A Livewire "load more" keeps only the page size in state and re-queries
(`public int $perPage = 25; public function loadMore(): void { $this->perPage += 25; }`, `->paginate($this->perPage)`
or `WithPagination`), renders each item with `wire:key`, and triggers from a button or Alpine's bundled
`x-intersect="$wire.loadMore()"`. Never `Model::all()`/`->get()` on a growing table into a view, never an
unbounded `@foreach` over a relation, never thousands of `<option>`s — a long picker becomes a search endpoint.

## WordPress

- **Blocks are components.** `block.json` `attributes` are the inputs (typed, with defaults); `edit.js` renders
  with `useBlockProps()` and edits through `setAttributes()` — two-way only for the control's own value
  (`<TextControl value={ title } onChange={ ( v ) => setAttributes( { title: v } ) } />`, `RichText`); child React
  components get props plus callbacks (`onSelect( id )`). Front-end interactivity uses the Interactivity API
  (`data-wp-on--click="actions.open"` + `store()`), not ad-hoc scripts.
- **Server-rendered (dynamic) blocks**: `"render": "file:./render.php"`; the file receives `$attributes`,
  `$content`, `$block`, prints `get_block_wrapper_attributes()` and escapes everything (`wordpress.md`).
- **Composition**: `InnerBlocks` (`allowedBlocks`, `template`), patterns (`<!-- wp:pattern {"slug":"…"} /-->`),
  template parts (`<!-- wp:template-part {"slug":"header"} /-->`); classic code passes inputs with
  `get_template_part( 'parts/order-row', null, array( 'order' => $order ) )` (`$args` in the partial).
- **Collections**: `WP_Query` with a bounded `posts_per_page` and `'paged' => max( 1, get_query_var( 'paged' ) )`,
  one item template/block per post, `paginate_links()` (or `the_posts_pagination()`); `'no_found_rows' => true`
  when no pager is shown. Block themes use the Query Loop block (`core/query` + `core/post-template` +
  `core/query-pagination`, `perPage`, `"enhancedPagination": true` for client-side page swaps). Admin lists use
  `WP_List_Table` + `set_pagination_args()`; "load more" calls a REST route with `per_page`/`page` and renders the
  same item markup. **Never** `'posts_per_page' => -1`, `'nopaging' => true` or `'numberposts' => -1` on content
  that grows.
- **Direction**: `is_rtl()`; `wp-scripts build` emits `*-rtl.css` (rtlcss) and blocks registered from `block.json`
  load it automatically; theme/plugin stylesheets add `wp_style_add_data( $handle, 'rtl', 'replace' )`. Use logical
  CSS properties (`margin-inline-start`) everywhere.

## PrestaShop

- **Front office (Smarty)**: the front controller assigns prepared arrays (`$this->context->smarty->assign([...])`)
  and renders `module:<module>/views/templates/front/<screen>.tpl`, which extends the theme layout
  (`{extends file='page.tpl'}` + `{block name='page_content'}` — inheritance the framework demands; keep it to that
  one level). Components are partials included with explicit inputs:
  `{include file='module:acmeloans/views/templates/front/_partials/order-row.tpl' order=$order open_url=$order.url}`.
  Hook output: `$this->fetch('module:acmeloans/views/templates/hook/order-badge.tpl')` after assigning its vars.
- **Back office (Twig)**: partials with an explicit, closed input list —
  `{% include '@Modules/acmeloans/views/templates/admin/_partials/order_row.html.twig' with { order: order } only %}`
  — and `{% embed %}` (overriding a `{% block %}`) where a partial needs slot-like content. Action URLs come from
  the controller (`path('acmeloans_order_show', { orderId: order.id })`) as inputs.
- **Collections**: admin lists use the **Grid component** (grid definition factory + Doctrine query builder applying
  `$searchCriteria->getOffset()`/`getLimit()`), rendered with
  `{% include '@PrestaShop/Admin/Common/Grid/grid_panel.html.twig' with { grid: orderGrid } %}`; legacy
  `HelperList` uses its built-in pagination. Front product lists extend `ProductListingFrontController` (core
  listing: pagination, sort, facets); other front lists query a bounded page (`DbQuery ... ->limit($perPage,
  $offset)`) and render the theme's pagination partial. Never `executeS()` without a `LIMIT` into a template.
- **Direction**: the language's `is_rtl` flag (`{$language.is_rtl}` in Smarty); RTL theme stylesheets come from
  the core RTL generator — use logical properties in module CSS.

## UI tests — browser tests with Playwright

Runner: **Node Playwright** (`@playwright/test` `^1.64.0`) + `@axe-core/playwright` `^4.14.0` for every flavor
(Laravel Dusk or the Pest browser plugin only where the repository already uses them — keep their style and let the
gate copy their screenshots into the contract layout). WordPress adds `@wordpress/e2e-test-utils-playwright`
`^3.0.0` and runs through `wp-scripts test-playwright`. Layout: root `playwright.config.ts`, specs in
`tests/e2e/specs/<Name>.spec.ts`, one helper `tests/e2e/support/ui-screenshots.ts`, npm script
`"test:e2e": "playwright test"` (WordPress: `"wp-scripts test-playwright"`). Adding these dependencies or the
config is a task-level decision (SKILL.md Step 3) — when the project has none, say so instead of scaffolding.

| Flavor | Target | Config essentials |
|---|---|---|
| Laravel | `php artisan serve` on a dedicated SQLite test database seeded for the specs | `testDir: './tests/e2e/specs'`, `webServer: { command: 'php artisan serve --port=8000', url: 'http://127.0.0.1:8000', reuseExistingServer: !process.env.CI }` (with the app's testing env), `use: { baseURL, locale: 'en-US', timezoneId: 'UTC', contextOptions: { reducedMotion: 'reduce' } }` |
| WordPress | the wp-env **tests** instance (`WP_BASE_URL`, default `http://localhost:8889`) | `import baseConfig from '@wordpress/scripts/config/playwright.config.js'; export default defineConfig({ ...baseConfig, testDir: './tests/e2e/specs', webServer: { ...baseConfig.webServer, command: 'npx wp-env start --config=.wp-env.test.json' } })` — never let it start the development environment |
| PrestaShop | an already running shop at `PS_BASE_URL` (no `webServer`) | `test.skip(!process.env.PS_BASE_URL, 'no shop')` in each spec — the gate reports that "unverified (no shop)" |

- **Component tests**: render one component per visual state with fixture data — Laravel/PrestaShop through the
  app's existing UI-fixture/gallery route if it has one (otherwise the component is exercised inside its screen);
  WordPress with the fixtures `editor.insertBlock({ name: 'acme/loan-card', attributes })` in the editor, or
  `requestUtils.createPost({ content: '<!-- wp:acme/loan-card {…} /-->', status: 'publish' })` then
  `page.goto()` for the front end. **Screen tests**: navigate to the route with seeded data per state
  (`minimal-data`, `typical-data`, `many-items`, `empty`, `error`). **Flow tests**: drive the real pages through
  the flow (`kind: 'flow'`, states `01-cart`, `02-payment`).
- Assert behaviour once (LTR, light) with role queries (`getByRole`, `getByLabel`); capture the matrix for every
  state. Determinism: `page.clock.install({ time: new Date('2026-01-15T10:00:00Z') })`, fixed viewport/locale/
  timezone, `page.route()` to fake third-party HTTP, `await page.evaluate(() => document.fonts.ready)` before a
  capture.
- **Direction** must be rendered by the server (`<html dir="rtl" lang="ar">` + RTL styles), so set it **before**
  `page.goto()` and assert it after (`expectDirection`): Laravel — the app's own locale switch (route prefix,
  session/cookie, or `Accept-Language` via `setExtraHTTPHeaders`), with the layout writing `dir` from
  `in_array(app()->getLocale(), ['ar', 'he', 'fa', 'ur'], true)`; WordPress — the `iru_ui_dir=rtl` cookie read by a
  test-only mu-plugin mapped only in `.wp-env.test.json` (`tests/e2e/mu-plugins/iru-ui-test-direction.php`, setting
  `$GLOBALS['wp_locale']->text_direction = 'rtl'` on `setup_theme`); PrestaShop — an RTL language installed in the
  test shop (its `id_lang`/ISO in the URL). Only when the app has no locale mechanism at all, set
  `document.documentElement.dir` after navigation and report it as a limitation.
- **Theme**: `page.emulateMedia({ colorScheme: 'dark' })` where the CSS uses `prefers-color-scheme` (Tailwind's
  default `dark:`); a class-based toggle sets that class instead. One `high-contrast` capture per screen with
  `forcedColors: 'active'`; a `large-text` variant by setting the root font size to 200% where cheap.

The shared helper (one file; the gate and the docs skills rely on its output layout):

```ts
// tests/e2e/support/ui-screenshots.ts
import { existsSync, mkdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import AxeBuilder from '@axe-core/playwright';
import { expect, type Locator, type Page, type TestInfo } from '@playwright/test';

export type Direction = 'ltr' | 'rtl';
export type Theme = 'light' | 'dark' | 'high-contrast';
export interface UiQuality { accessibility: 'enforce' | 'report' | 'off' | 'none'; directions: Direction[]; themes: Theme[] }
const KEBAB = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

function repoRoot(): string {
  for (let dir = process.cwd(); ; dir = dirname(dir)) {
    if (existsSync(join(dir, '.git'))) return dir;
    if (dirname(dir) === dir) return process.cwd();
  }
}

/** `.ui-quality.yml` has three flat keys — parsed without a YAML dependency; a missing file means the defaults. */
export function uiQuality(): UiQuality {
  const q: UiQuality = { accessibility: 'enforce', directions: ['ltr', 'rtl'], themes: ['light', 'dark'] };
  const file = join(repoRoot(), '.ui-quality.yml');
  if (!existsSync(file)) return q;
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = /^(accessibility|directions|themes):\s*([^#]*)/.exec(line);
    if (!m) continue;
    const value = m[2].trim();
    const list = value.replace(/^\[|\]$/g, '').split(',').map((v) => v.trim()).filter(Boolean);
    if (m[1] === 'accessibility') q.accessibility = value as UiQuality['accessibility'];
    else if (m[1] === 'directions') q.directions = list as Direction[];
    else q.themes = list as Theme[];
  }
  return q;
}

export const screenshotsDir = (): string => process.env.UI_SCREENSHOTS_DIR ?? join(repoRoot(), '.ui-screenshots');

/** Before page.goto(): theme by media emulation, direction by the app's own switch — keep only the one it uses. */
export async function applyDirectionAndTheme(page: Page, direction: Direction, theme: Theme): Promise<void> {
  await page.emulateMedia({ colorScheme: theme === 'dark' ? 'dark' : 'light', forcedColors: theme === 'high-contrast' ? 'active' : 'none' });
  const url = process.env.WP_BASE_URL ?? process.env.APP_URL ?? 'http://127.0.0.1:8000';
  await page.context().addCookies([{ name: 'iru_ui_dir', value: direction, url }]);              // WordPress mu-plugin
  await page.setExtraHTTPHeaders({ 'Accept-Language': direction === 'rtl' ? 'ar' : 'en' });      // Laravel locale
}

/** After navigation: proves the server rendered the requested direction. */
export async function expectDirection(page: Page, direction: Direction): Promise<void> {
  await expect.poll(() => page.evaluate(() => getComputedStyle(document.documentElement).direction)).toBe(direction);
}

export async function captureUiState(page: Page, s: { kind: 'component' | 'screen' | 'flow'; id: string; state: string;
  direction: Direction; theme: Theme; variants?: string[]; locator?: Locator }): Promise<string> {
  const variants = s.variants ?? [];
  const idOk = s.kind === 'flow' ? KEBAB.test(s.id) : /^[A-Za-z][A-Za-z0-9]*$/.test(s.id);
  if (!idOk || ![s.state, ...variants].every((v) => KEBAB.test(v)) || !['ltr', 'rtl'].includes(s.direction)
      || !['light', 'dark', 'high-contrast'].includes(s.theme)) throw new Error(`Malformed UI screenshot: ${JSON.stringify(s)}`);
  const path = join(screenshotsDir(), s.kind, s.id, [s.state, s.direction, s.theme, ...variants].join('--') + '.png');
  mkdirSync(dirname(path), { recursive: true });
  const opts = { path, animations: 'disabled', caret: 'hide' } as const;
  // A component passes its element (`locator`) so only it is captured; a screen captures the full page.
  await (s.locator ? s.locator.screenshot(opts) : page.screenshot({ ...opts, fullPage: true }));
  return path;
}

/** axe-core at the `.ui-quality.yml` level: enforce fails the test, report annotates, off and none skip.
 *  `include` scopes the scan to a component's selector, so host-page markup never fails a component test. */
export async function checkAccessibility(page: Page, testInfo: TestInfo, include?: string): Promise<void> {
  const level = uiQuality().accessibility;
  if (level === 'off' || level === 'none') return;
  let builder = new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']);
  if (include) builder = builder.include(include);
  const results = await builder.analyze();
  await testInfo.attach('accessibility-scan-results', { body: JSON.stringify(results, null, 2), contentType: 'application/json' });
  const summary = results.violations.map((v) => `${v.id} × ${v.nodes.length}`);
  if (level === 'enforce') expect(summary).toEqual([]);
  else if (summary.length > 0) {
    testInfo.annotations.push({ type: 'a11y-report', description: summary.join(', ') });
    console.warn(`[a11y report] ${summary.join(', ')}`);
  }
}
```

A screen spec iterates the matrix from `.ui-quality.yml`:

```ts
const { directions, themes } = uiQuality();
for (const direction of directions) for (const theme of themes) {
  test(`OrdersScreen many-items ${direction} ${theme}`, async ({ page }, testInfo) => {
    await applyDirectionAndTheme(page, direction, theme);
    await page.goto('/orders');
    await expectDirection(page, direction);
    await captureUiState(page, { kind: 'screen', id: 'OrdersScreen', state: 'many-items', direction, theme });
    await checkAccessibility(page, testInfo);
  });
}
```

**This skill writes these specs but never runs them** — they need a browser plus a running server, wp-env or
shop. `iru-php-code-one-task-group` runs them through `iru-php-test` with the `ui` selector behind
`.integration-test.lock`, checks the screenshot matrix, and applies the accessibility level.

## Assistive technologies

Applies unless `.ui-quality.yml` says `accessibility: none` (opted out entirely - then omit these attributes and checks). Rules and levels: `reference/ui-contract.md` §8 ("Platform assistive-technology rules", "Visual comfort").

- Prefer native semantic elements (`button`, `a`, `nav`, `main`, `label`/`input`, headings in order) over `div` + `role`; add `aria-*` only where HTML has no equivalent.
- Icon-only controls get `aria-label`; images get `alt` (empty `alt=""` when decorative); dynamic updates use `aria-live`; modals trap and restore focus.
- Honour `prefers-reduced-motion` and `forced-colors`; size text in `rem`, never fixed `px` heights that clip on zoom.
- Laravel Blade/Livewire: semantic landmarks in the layout, `<label for>` on every field, `aria-describedby` for validation errors, `role="alert"` for flash messages.
- WordPress themes/plugins: skip-link, `screen-reader-text` class, `wp_nav_menu` landmarks, accessible-name on block variations; PrestaShop: Smarty templates follow the same markup rules, label every `form_field`.

## Sources

- Laravel 13 — Blade components: <https://laravel.com/docs/13.x/blade#components>, pagination:
  <https://laravel.com/docs/13.x/pagination>, localization: <https://laravel.com/docs/13.x/localization>, view
  testing: <https://laravel.com/docs/13.x/http-tests#testing-views>
- Livewire — properties, actions, events, `wire:model`, pagination, `wire:key`: <https://livewire.laravel.com/docs/properties>,
  <https://livewire.laravel.com/docs/events>, <https://livewire.laravel.com/docs/pagination>; Alpine `$dispatch`/`x-intersect`:
  <https://alpinejs.dev/magics/dispatch>, <https://alpinejs.dev/plugins/intersect>
- Block Editor Handbook — block.json attributes, `useBlockProps`, dynamic blocks, Interactivity API:
  <https://developer.wordpress.org/block-editor/reference-guides/block-api/block-attributes/>,
  <https://developer.wordpress.org/block-editor/getting-started/fundamentals/block-wrapper/>,
  <https://developer.wordpress.org/block-editor/reference-guides/interactivity-api/>; Query Loop and pagination:
  <https://developer.wordpress.org/block-editor/reference-guides/core-blocks/>,
  <https://developer.wordpress.org/reference/functions/paginate_links/>, `WP_Query`:
  <https://developer.wordpress.org/reference/classes/wp_query/>
- WordPress e2e: `@wordpress/e2e-test-utils-playwright` <https://developer.wordpress.org/block-editor/reference-guides/packages/packages-e2e-test-utils-playwright/>,
  `wp-scripts test-playwright` <https://developer.wordpress.org/block-editor/reference-guides/packages/packages-scripts/>
- PrestaShop DevDocs (9.x) — templates/Smarty in modules: <https://devdocs.prestashop-project.org/9/modules/creation/displaying-content-in-front-office/>,
  Grid component: <https://devdocs.prestashop-project.org/9/development/components/grid/>, Twig in modules:
  <https://devdocs.prestashop-project.org/9/modules/concepts/templating/admin-views/>
- Twig `include`/`embed`: <https://twig.symfony.com/doc/3.x/tags/include.html>, <https://twig.symfony.com/doc/3.x/tags/embed.html>;
  Smarty `{include}`: <https://smarty-php.github.io/smarty/stable/designers/language-builtin-functions/language-function-include/>
- Playwright — config/webServer, emulation, clock, screenshots: <https://playwright.dev/docs/test-webserver>,
  <https://playwright.dev/docs/emulation>, <https://playwright.dev/docs/clock>, <https://playwright.dev/docs/screenshots>;
  accessibility testing with `@axe-core/playwright`: <https://playwright.dev/docs/accessibility-testing>
- WCAG 2.2: <https://www.w3.org/TR/WCAG22/>
