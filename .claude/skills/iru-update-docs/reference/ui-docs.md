# UI documentation — navigation flows, screens and components, illustrated with UI-test screenshots

How a repository whose projects have a user interface documents it in Antora. This file is repeated verbatim in
every skill that writes Antora pages (`iru-setup-antora`, `iru-update-docs`,
`iru-update-java-springboot-documentation`). It builds on the UI contract the coding skills follow (their
`reference/ui-contract.md`): screens, components and flows are named by their type names in code, their doc
comments list purpose/inputs/events/states, and UI tests leave screenshots under `.ui-screenshots/`.

## 1. When it applies

The repository has a UI when any of these holds: a `.ui-screenshots/` directory or a `.ui-quality.yml` file at
the root; an Android application/library module with Compose or layouts; an Xcode app target (SwiftUI/UIKit/
AppKit); a Kotlin Multiplatform app; a React Native, React, Angular, Ionic, Next.js, Hilla or Lit project; Laravel
Blade/Livewire views, a WordPress block/theme, a PrestaShop module with templates, Django templates; a WPF/WinUI/
Windows Forms project; a command-line tool with a terminal UI (Ratatui, Bubble Tea, Terminal.Gui). A pure library,
API service or plain CLI without a terminal UI has none — skip everything below.

## 2. Page and image layout

Inside the Antora module the site already uses (normally `ROOT`):

```text
pages/ui/index.adoc                    overview: screens table, components table, link to flows
pages/ui/navigation.adoc               every flow: diagram + step-by-step screenshots
pages/ui/screens/<Id>.adoc             one page per screen
pages/ui/components/<Id>.adoc          one page per component
images/ui/<kind>/<Id>/<file>.png       the committed screenshots, same layout as .ui-screenshots/
```

When the repository has several UI apps (e.g. a library's sample app and a separate app, or Android and iOS
hosts rendering different native screens), add an app segment after `ui/` in both trees
(`pages/ui/<app>/screens/…`, `images/ui/<app>/screen/…`) and one overview per app. Screens rendered on several
platforms from one `<Id>` (screenshot `<variant>` = platform) stay on one page with one screenshot block per
platform.

`nav.adoc` gains one **User interface** section:

```asciidoc
* xref:ui/index.adoc[User interface]
** xref:ui/navigation.adoc[Navigation flows]
** Screens
*** xref:ui/screens/OrdersScreen.adoc[Orders]
** Components
*** xref:ui/components/OrderRow.adoc[Order row]
```

Screens are listed in navigation order (start destination first), components alphabetically.

## 3. What each page contains

Every fact comes from the code and its doc comments — never invent behaviour.

**`ui/index.adoc`** — one paragraph on the app's UI; a table of screens (name, purpose, route/entry point); a
table of components (name, purpose, used by); the accessibility level from `.ui-quality.yml` (with `accessibility:
none`, one sentence stating that accessibility and assistive-technology support is opted out for this project instead);
a link to the flows.

**`ui/navigation.adoc`** — the navigation graph from the app's single navigation definition (navigation graph,
router, `NavigationStack` destinations, file-based routes, coordinator, Bubble Tea model switch): a Mermaid
`flowchart` when the playbook has the Mermaid extension (an ordered list of `xref:` links otherwise), then one
section per flow with each step's `flow/<flow>/NN-<step>--ltr--light.png` screenshot and a one-line caption.

**`ui/screens/<Id>.adoc`** and **`ui/components/<Id>.adoc`**:

1. Title (the human name) and purpose (from the doc comment).
2. *Where it is used*: flows/screens that reach it (screens) or the screens/components that compose it
   (components), as `xref:`s.
3. *Inputs* table (name, type, meaning) and *Events* table (name, payload, when it fires).
4. *Composed of*: `xref:`s to the component pages it nests.
5. *States*: one subsection per documented visual state, with the screenshots chosen by §4.
6. *Accessibility*: labels/roles worth knowing, and the automated check result when the UI test run reported it.
   Write this subsection only when `.ui-quality.yml` `accessibility` is not `none`; with `none`, omit it from every
   page (the opt-out sentence on `ui/index.adoc` is the only mention).

Screenshots use the image macro with meaningful alternative text, inside a table so light/dark and LTR/RTL sit
side by side:

```asciidoc
=== Default

[cols="1h,2a,2a",options="header",frame=none,grid=none]
|===
| | Light | Dark
| Left-to-right
| image::ui/screen/OrdersScreen/default--ltr--light.png[Orders screen, default state, left-to-right, light theme]
| image::ui/screen/OrdersScreen/default--ltr--dark.png[Orders screen, default state, left-to-right, dark theme]
| Right-to-left
| image::ui/screen/OrdersScreen/default--rtl--light.png[Orders screen, default state, right-to-left, light theme]
| image::ui/screen/OrdersScreen/default--rtl--dark.png[Orders screen, default state, right-to-left, dark theme]
|===
```

Terminal-UI captures that only exist as `.txt` are embedded as a `[source,text]` block (`include::` of a copy
under `examples/ui/…`, same layout as `images/ui/…`) instead of an image.

## 4. Which screenshots a page uses

- The `default` state: the full matrix the run produced (directions × themes), plus any `high-contrast` and
  `large-text` capture.
- Every other state: its `ltr--light` capture; add `rtl--light` for `long-content` and any state whose layout is
  direction-sensitive, and `ltr--dark` for `error`/`disabled`/`selected` where colour carries the state.
- Flows: each step's `ltr--light` capture.
- With several platform/form-factor variants, repeat the selection per variant.

## 5. Synchronising screenshots

Run this after the pages are written, as one pass:

1. **Fresh captures.** If `.ui-screenshots/` is missing, empty, or older than the latest change to the UI sources
   being documented, say so and offer (`AskUserQuestion`) to run the stack's UI test gate first (the matching
   `iru-<key>-test` with its UI/e2e selector, through the `iru-gate-runner` agent) — or to continue without new
   captures, keeping existing committed images and marking states with no image as "screenshot pending" in the
   page. Never fabricate or hand-draw a screenshot.
2. **Copy what pages reference.** For every `image::ui/…` a page references, copy
   `.ui-screenshots/<kind>/<Id>/<file>` to `images/ui/<kind>/<Id>/<file>` — only when the bytes differ from the
   committed file (compare hashes), so an unchanged component produces no image churn.
3. **Replace changed components wholesale.** For every component/screen whose source changed in the documented
   diff, delete its `images/ui/<kind>/<Id>/` files that the regenerated page no longer references — old images of
   a changed component never linger next to the new ones.
4. **Remove what no longer exists.** A component/screen/flow deleted from the code loses its page, its `nav.adoc`
   entry, and its `images/ui/<kind>/<Id>/` directory; scan `images/ui/` for any file no page references and delete
   it.
   Only image captures (`.png`) and terminal text captures (`.txt` named like a capture) are screenshots; any
   other file a test run leaves in `.ui-screenshots/` — e.g. an accessibility report such as `a11y-report.jsonl`
   or `a11y-report.txt` — is test evidence, never copied into the docs (its findings may feed the page's
   *Accessibility* section).
5. **Discard the rest.** Delete the `.ui-screenshots/` directory once the copy succeeded — it is gitignored test
   output, regenerated by the next UI test run; the committed `images/ui/` files are the only screenshots kept.
6. **Report** images added, replaced, unchanged and deleted, and every state left "screenshot pending".
