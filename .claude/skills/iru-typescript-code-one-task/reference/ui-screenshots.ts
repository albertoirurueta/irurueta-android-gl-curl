/**
 * Shared UI-test helper (UI contract §6–§8): the screenshot matrix and the automated accessibility check.
 *
 * Screenshots go to `$UI_SCREENSHOTS_DIR` (absolute) or `<repository root>/.ui-screenshots`, as
 * `<kind>/<Id>/<state>--<direction>--<theme>[--<variant>…].png`. `.ui-quality.yml` at the repository root narrows
 * the matrix (`directions`, `themes`) and sets the accessibility level (`enforce` | `report` | `off` | `none`).
 */
import { existsSync, mkdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import AxeBuilder from '@axe-core/playwright'
import { expect, type Locator, type Page, type TestInfo } from '@playwright/test'

export type UiKind = 'component' | 'screen' | 'flow'
export type UiDirection = 'ltr' | 'rtl'
export type UiTheme = 'light' | 'dark' | 'high-contrast'
export type AccessibilityLevel = 'enforce' | 'report' | 'off' | 'none'

/** The repository's `.ui-quality.yml` settings. */
export interface UiQuality {
  readonly accessibility: AccessibilityLevel
  readonly directions: readonly UiDirection[]
  readonly themes: readonly UiTheme[]
}

/** One captured state: `<kind>/<id>/<state>--<direction>--<theme>[--<variant>…].png`. */
export interface CaptureSpec {
  /** `component`, `screen` or `flow`. */
  readonly kind: UiKind
  /** Component/screen type name exactly as in code (`OrderRow`), or a kebab-case flow name (`checkout`). */
  readonly id: string
  /** Kebab-case visual state (`default`, `empty`, `many-items`), or `NN-<step>` for a flow. */
  readonly state: string
  /** Optional extra segments: platform, then form factor/size (`web`, `w1280`, `large-text`). */
  readonly variants?: readonly string[]
}

const DEFAULTS: UiQuality = {
  accessibility: 'enforce',
  directions: ['ltr', 'rtl'],
  themes: ['light', 'dark'],
}
const KEBAB = /^[a-z0-9]+(?:-[a-z0-9]+)*$/
const TYPE_NAME = /^[A-Za-z][A-Za-z0-9]*$/

/**
 * Finds the repository root: the nearest ancestor of `start` holding `.git`.
 *
 * @param start - directory to start from; defaults to the working directory.
 * @returns the repository root, or the working directory when no `.git` is found.
 */
export function repositoryRoot(start: string = process.cwd()): string {
  let dir = path.resolve(start)
  while (!existsSync(path.join(dir, '.git'))) {
    const parent = path.dirname(dir)
    if (parent === dir) return process.cwd()
    dir = parent
  }
  return dir
}

/**
 * Reads `.ui-quality.yml` (three flat keys, so no YAML dependency). A missing file or key means the default.
 *
 * @param root - repository root; defaults to {@link repositoryRoot}.
 * @returns the accessibility level and the matrix directions/themes.
 */
export function readUiQuality(root: string = repositoryRoot()): UiQuality {
  const file = path.join(root, '.ui-quality.yml')
  if (!existsSync(file)) return DEFAULTS
  const values = new Map<string, string>()
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const match = /^([a-z]+):\s*([^#]*)/.exec(line)
    if (match) values.set(match[1], match[2].trim())
  }
  const list = (key: string): readonly string[] | undefined => {
    const items = values
      .get(key)
      ?.replace(/^\[|\]$/g, '')
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean)
    return items && items.length > 0 ? items : undefined
  }
  const level = values.get('accessibility')
  return {
    accessibility: level === 'report' || level === 'off' || level === 'none' ? level : 'enforce',
    directions: (list('directions') as readonly UiDirection[] | undefined) ?? DEFAULTS.directions,
    themes: (list('themes') as readonly UiTheme[] | undefined) ?? DEFAULTS.themes,
  }
}

/**
 * Resolves the screenshot directory: `UI_SCREENSHOTS_DIR`, else `<repository root>/.ui-screenshots`.
 *
 * @returns an absolute directory path.
 */
export function screenshotsDir(): string {
  return process.env['UI_SCREENSHOTS_DIR'] ?? path.join(repositoryRoot(), '.ui-screenshots')
}

/**
 * Builds the contract file name for one matrix cell.
 *
 * @param spec - what is captured.
 * @param direction - `ltr` or `rtl`.
 * @param theme - `light`, `dark` or `high-contrast`.
 * @returns the absolute PNG path.
 * @throws Error when the id, state or a variant does not follow the contract's naming.
 */
export function screenshotPath(spec: CaptureSpec, direction: UiDirection, theme: UiTheme): string {
  const idPattern = spec.kind === 'flow' ? KEBAB : TYPE_NAME
  if (!idPattern.test(spec.id)) throw new Error(`Invalid ${spec.kind} id "${spec.id}"`)
  for (const segment of [spec.state, ...(spec.variants ?? [])]) {
    if (!KEBAB.test(segment))
      throw new Error(`Invalid state/variant segment "${segment}" (kebab-case)`)
  }
  const name = [spec.state, direction, theme, ...(spec.variants ?? [])].join('--')
  return path.join(screenshotsDir(), spec.kind, spec.id, `${name}.png`)
}

/**
 * Applies one matrix cell: colour scheme, forced colours and reduced motion for the page, and `dir` on `<html>`
 * both now and on every later navigation (component `mount()` and `page.goto()` navigate).
 *
 * @param page - the test page.
 * @param direction - `ltr` or `rtl`.
 * @param theme - `light`, `dark` or `high-contrast`.
 * @returns a disposer that removes the direction init script.
 */
export async function applyUiVariant(
  page: Page,
  direction: UiDirection,
  theme: UiTheme,
): Promise<() => Promise<void>> {
  await page.emulateMedia({
    colorScheme: theme === 'dark' ? 'dark' : 'light',
    forcedColors: theme === 'high-contrast' ? 'active' : 'none',
    reducedMotion: 'reduce',
  })
  const setDir = (dir: string): void => {
    const apply = (): void => document.documentElement.setAttribute('dir', dir)
    if (document.documentElement) apply()
    else document.addEventListener('readystatechange', apply, { once: true })
  }
  const initScript = await page.addInitScript(setDir, direction)
  await page.evaluate(setDir, direction)
  return () => initScript.dispose()
}

/** Options for {@link captureUiMatrix}. */
export interface CaptureOptions {
  /** Runs after rendering each cell, for app-specific theming (e.g. Lumo's `theme="dark"` attribute). */
  readonly onVariant?: (page: Page, direction: UiDirection, theme: UiTheme) => Promise<void>
  /** Also run {@link checkAccessibility} in every cell (contrast differs per theme), scoped to `include`. */
  readonly accessibility?: { readonly testInfo: TestInfo; readonly include?: string }
}

/**
 * Captures one state across the `.ui-quality.yml` matrix (directions × themes). `render` is called once per cell,
 * after the cell is applied, and returns what to capture: the `mount()` locator for a component, or the page.
 *
 * @param page - the test page.
 * @param spec - what is captured.
 * @param render - renders the state (mounts the story, or navigates) and returns the capture target.
 * @param options - optional per-cell hook.
 */
export async function captureUiMatrix(
  page: Page,
  spec: CaptureSpec,
  render: () => Promise<Locator | Page>,
  options: CaptureOptions = {},
): Promise<void> {
  const quality = readUiQuality()
  for (const direction of quality.directions) {
    for (const theme of quality.themes) {
      const dispose = await applyUiVariant(page, direction, theme)
      try {
        const target = await render()
        await options.onVariant?.(page, direction, theme)
        await page.evaluate(() => document.fonts.ready.then(() => undefined))
        const file = screenshotPath(spec, direction, theme)
        mkdirSync(path.dirname(file), { recursive: true })
        if ('goto' in target) {
          await target.screenshot({
            path: file,
            fullPage: true,
            animations: 'disabled',
            caret: 'hide',
          })
        } else {
          await target.screenshot({ path: file, animations: 'disabled', caret: 'hide' })
        }
        if (options.accessibility) {
          const { testInfo, include } = options.accessibility
          await checkAccessibility(page, testInfo, include, `${spec.state}--${direction}--${theme}`)
        }
      } finally {
        await dispose()
      }
    }
  }
  await page.emulateMedia({ colorScheme: 'light', forcedColors: 'none', reducedMotion: 'reduce' })
}

/**
 * Runs axe-core (WCAG 2.x A/AA rules) on the page — or only inside `include` — at the `.ui-quality.yml` level:
 * `enforce` fails the test on any violation, `report` attaches and annotates them, `off` skips the check, and `none`
 * (the project opted out of accessibility support entirely) skips it too.
 *
 * Violations are asserted softly, so a matrix run still captures every cell before the test fails.
 *
 * @param page - the test page, already showing the state to check.
 * @param testInfo - the running test's info, for the attachment and annotation.
 * @param include - optional CSS selector to scope the check (e.g. `#root` for a mounted component).
 * @param label - optional label recorded with the results (e.g. the matrix cell).
 */
export async function checkAccessibility(
  page: Page,
  testInfo: TestInfo,
  include?: string,
  label = 'page',
): Promise<void> {
  const { accessibility } = readUiQuality()
  if (accessibility === 'off' || accessibility === 'none') return
  let builder = new AxeBuilder({ page }).withTags([
    'wcag2a',
    'wcag2aa',
    'wcag21a',
    'wcag21aa',
    'wcag22aa',
  ])
  if (include) builder = builder.include(include)
  const { violations } = await builder.analyze()
  const summary = violations.map(
    (violation) => `${violation.id} (${violation.impact ?? 'n/a'}) ×${violation.nodes.length}`,
  )
  if (violations.length > 0) {
    // Same attachment name as Playwright's accessibility-testing guide, so the test gate can count rule ids.
    const results = {
      label,
      violations: violations.map(({ id, impact, nodes }) => ({
        id,
        impact,
        nodes: nodes.map(({ target }) => ({ target })),
      })),
    }
    await testInfo.attach('accessibility-scan-results', {
      body: JSON.stringify(results, null, 2),
      contentType: 'application/json',
    })
  }
  if (accessibility === 'enforce') {
    expect
      .soft(
        violations.map((violation) => violation.id),
        `accessibility violations (${label}): ${summary.join(', ')}`,
      )
      .toEqual([])
  } else if (violations.length > 0) {
    testInfo.annotations.push({
      type: 'accessibility',
      description: `${label}: ${summary.join(', ')}`,
    })
  }
}
