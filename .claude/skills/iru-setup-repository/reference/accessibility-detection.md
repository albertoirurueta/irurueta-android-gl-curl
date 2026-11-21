# Accessibility detection — does the existing code already support assistive technologies?

Used by `iru-explore` (reports the `Accessibility:` line) and `iru-setup-repository` (pre-selects the
`accessibility` question). This file is shared and must stay byte-identical in both skills. Detection is read-only:
it never edits code, it only informs the question.

Applies to UI projects only. Without a UI the result is `not applicable (no UI)`.

## 1. Signals per stack

Search source files (exclude `node_modules`, `build`, `dist`, `Pods`, `.build`, `target`, `vendor`, generated code):

| Stack | Attribute / API signals | Tooling signals |
|-------|-------------------------|-----------------|
| Web (React, Angular, Ionic, Next.js, Hilla) | `aria-`, `role=`, `alt=`, `<label for` / `htmlFor`, semantic landmarks (`<nav`, `<main`, `<button`) | `@axe-core/*`, `jest-axe`, `eslint-plugin-jsx-a11y`, `@angular-eslint` template-accessibility rules, `cypress-axe` |
| Android | `contentDescription`, `semantics {`, `importantForAccessibility`, `stateDescription` | Espresso `AccessibilityChecks`, `enableAccessibilityChecks`, Accessibility Test Framework |
| Apple | `accessibilityLabel`, `.accessibilityLabel(`, `accessibilityHint`, `UIAccessibility` | `performAccessibilityAudit` |
| Windows (WPF, WinUI, WinForms) | `AutomationProperties.`, `AccessibleName`, `AutomationPeer` | `Axe.Windows` |
| React Native | `accessibilityLabel`, `accessibilityRole`, `accessibilityState` | `eslint-plugin-react-native-a11y` |
| PHP / Python templates (Blade, Twig, Smarty, Django templates) | `aria-`, `alt=`, `<label for`, `role=` | `axe`, `pa11y` in CI or test config |
| Any | an existing `.ui-quality.yml` | its `accessibility:` value |

Record every hit group with a file count.

## 2. Coverage heuristic

Sample up to 30 UI files (or all, if fewer), spread across modules/directories. In them count:

- **interactive elements and images**: buttons, links, inputs, `<img>`/`Image`/`ImageView`/`Icon`, clickable
  composables/views, custom controls;
- **accessibility-annotated ones**: those carrying an accessible name/role/alt or inside an associated label.

`coverage = annotated / (interactive + images)`; also note whether the evidence appears in every UI module or only
some.

Counting rules (found necessary in the dry runs of section 5):

- Plain text elements (`Text(`, `<p>`, `<span>`) are self-labelling and are **not** counted as interactive; count
  only controls (buttons, links, inputs, toggles, custom clickable views) and images/icons.
- An image/icon explicitly marked decorative (`contentDescription = null`, `alt=""`, `aria-hidden="true"`,
  `importantForAccessibility="no"`) counts as annotated: it is a deliberate accessibility decision.
- A control whose accessible name comes from visible child text (`<button>Save</button>`, `Button { Text("Save") }`)
  counts as annotated; only icon-only or image-only controls need an explicit label.

## 3. Result

| Result | Condition |
|--------|-----------|
| `present` | coverage ≥ 70 % and evidence in all (or all sampled) UI modules; or a `.ui-quality.yml` with `enforce`/`report` plus tooling signals and coverage ≥ 50 % |
| `partial` | any evidence that does not meet `present` (coverage 10–70 %, or evidence only in some modules, or tooling without attributes) |
| `absent` | no signals, or coverage < 10 % with no tooling |

An existing `.ui-quality.yml` is reported but never decides the result alone. Print the evidence list: signals found
with counts, the sampled coverage figure, modules with and without evidence, and the `.ui-quality.yml` value or
`missing`.

## 4. Pre-selection

| Detection | Recommended `accessibility` | Note to show |
|-----------|-----------------------------|--------------|
| `present` | `enforce` | Existing code already supports it; keep it consistent. |
| `partial` | `report` (ask) | Warn about mixed compliance: new code will follow the rules, old code only partly does; `report` lists violations without failing. |
| `absent` | `none` | Warn that enabling accessibility makes only new code compliant, leaving a half-compliant app. |
| new/empty repository | `enforce` | Default for new projects. |

The user always decides; detection only pre-selects and explains.

## 5. Dry-run verification log

Heuristics exercised on real public repositories (shallow clones in a throwaway directory outside this repository,
2026-10). Figures are approximate hand counts with the greps from section 1.

| Repository | Signals found | Sampled coverage | Result |
|------------|---------------|------------------|--------|
| `vercel/commerce` (Next.js, 45 `.tsx` files) | `alt=` ×7, `aria-label` ×13, `aria-live` ×3, `role=` ×3, `aria-hidden` ×2, evidence in 17 files; no axe/jsx-a11y tooling; no `.ui-quality.yml` | about 20 annotated / 33 controls and images, about 60 % | `partial` |
| `irurueta/irurueta-android-glutils` (Android library + sample app, 2 Kotlin app files) | no `contentDescription`/`semantics`/`importantForAccessibility`, no tooling, no `.ui-quality.yml` | 0 % | `absent` |
| `android/nowinandroid` (Compose, many modules) | `contentDescription` in 20 files (59 assignments, 33 of them `= null` decorative), `semantics {` in 3 files, accessibility-check tooling in 1 file | 59 / about 86 icons and controls, about 69 % with the counting rules above (about 30 % without the decorative rule) | `partial` (borderline `present`; the decorative-image rule above was added because the first pass undercounted it at about 30 %) |

The with-attributes, without-attributes and borderline cases all produced a sensible pre-selection (`report` /
`none` / `report`). Plain `grep` over `*.kt`/`*.tsx` was sufficient; no stack-specific parsing is needed.
