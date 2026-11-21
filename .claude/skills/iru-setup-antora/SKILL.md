---
name: iru-setup-antora
description: "Invoked as /iru-setup-antora, optionally with an args line on-existing: ask|keep|update|replace. Sets up an Antora documentation site (antora.yml, playbook, ROOT module pages, User interface skeleton for UI projects) on the pinned toolchain with Pagefind search; on an existing site it asks Update, Keep or Replace."
model: sonnet
---

# Antora Setup

Bootstrap a minimal, working Antora documentation site for a repository: verify/install the Node.js toolchain,
install the pinned Antora toolchain under `docs/`, write `antora.yml` and `antora-playbook.yml`, create
the `ROOT` module with three starter pages, and confirm the site actually builds. This skill makes no assumptions
about the repository's language or build system — discover the project name, version, and install instructions
fresh each run. Its default update path is additive: if some or all of this already exists (as it does in this very
repository), it announces that, asks how to treat the existing site (Step 2), and on **Update** skips what's
already in place and only fills genuine gaps, without overwriting existing pages or reordering existing nav entries.

## Step 0 — Resolve inputs

This skill takes one optional `args` line, `on-existing: <ask|keep|update|replace>` — passed by an orchestrator
only when its own `args` received it (scripted runs). Default `ask`. Any other `key: value` line (e.g. `sonar`,
`mode`, `visibility`) is ignored — this skill only owns the docs site. When `on-existing` is `keep`/`update`/
`replace`, Step 2 still prints its warning about the existing site but applies that value without asking. `mode:
existing` alone (if an orchestrator happens to pass it) does **not** mean "update silently" — Step 2 still asks
unless `on-existing` says otherwise.

## Step 1 — Verify Node.js is installed

Run `node -v` and `npm -v`.

- **Both print a version**: Node.js is already usable — continue to Step 2. Antora 3.x needs Node.js 20+; if the
  reported Node version is older, warn the user but let them decide whether to upgrade before continuing.
- **Either command fails**: Node.js isn't installed. Show the user the install instructions below and stop —
  don't attempt to install Node.js yourself (it changes shell/global state outside this repository). Ask them to
  install it and re-invoke `/iru-setup-antora` once `node -v` and `npm -v` both work.

  **macOS / Linux** (via `nvm`):
  ```bash
  curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.5/install.sh | bash
  \. "$HOME/.nvm/nvm.sh"
  nvm install 24
  ```

  **Windows** (via Chocolatey, run in an elevated PowerShell):
  ```powershell
  powershell -c "irm https://community.chocolatey.org/install.ps1|iex"
  choco install nodejs --version="24.18.0"
  ```

  Point the user at https://nodejs.org/en/download for other install methods (installers, other package
  managers) if they'd rather not use `nvm`/Chocolatey.

## Step 2 — Survey what already exists

Don't assume a clean slate. Before creating anything, check:

- Does a `docs/` directory already exist at the repository root? If yes, is it already an Antora module (does
  `docs/antora.yml` exist)?
- Does `docs/antora-playbook.yml` (or another playbook file referenced from `docs/package.json` scripts) exist?
- Does `docs/ui-bundle.zip` already exist next to it?
- Does `docs/modules/ROOT/` exist, and if so what pages does `docs/modules/ROOT/nav.adoc` already list?
- Is `docs/package.json` already tracking `antora` and the extensions from Step 3 as dependencies, and at which
  versions? Does the playbook still require `@antora/lunr-extension`, or does `docs/ui-overrides/` already exist?

Build a concrete gap list from this survey (e.g. "antora.yml missing", "playbook exists but has no mermaid
extension", "ROOT module exists with 5 pages already, no page covers installation").

If the existing site is on an Antora older than the pinned version in `reference/toolchain.md`, or still requires
`@antora/lunr-extension`, do not patch versions in place: tell the user to run `/iru-update-antora`, which upgrades
the toolchain, replaces Lunr with Pagefind, fixes the workflows and proves the result with a before/after build diff.
Under **Update** this skill then only fills the gaps that do not concern versions or search.

Then tell the user which of the two this run is doing:

- **None of `docs/antora.yml`, the playbook, or `docs/modules/ROOT/` exists** → "Creating the Antora site **from
  scratch**." Continue to Step 3 with every gap in scope.
- **Any of them exists** → "**Updating an existing Antora site** — found: <paths>." Warn that a prior docs setup is
  present and — unless Step 0's `on-existing` already decided (`keep`/`update`/`replace`, applied without asking) —
  ask with `AskUserQuestion` (header e.g. "Antora docs"), explaining each option's consequence in its description:
  - **Update (Recommended)** — "Fill gaps only: add the missing config, extensions, starter pages and nav entries
    using this skill's template as reference; never overwrite an existing page, existing nav entry, or a correct
    config section. Your customizations survive; only genuinely wrong/missing fields in `antora.yml`/the playbook
    are corrected (shown to you first)." — today's additive behaviour, exactly as Steps 3–12 describe.
  - **Keep as is** — "Leave the docs site untouched (no config, page, or nav change; Step 11's `.gitignore` check
    and Step 12's build still run as a verification). It may stay out of date — e.g. missing an extension or the
    local `ui-bundle.zip` — and the report will list each gap left unfilled." Skip Steps 3–10.
  - **Replace** — "Rewrite `docs/antora.yml` and `docs/antora-playbook.yml` from this skill's template (and
    re-copy `ui-bundle.zip`). Matches this house pattern exactly, but every customization in those two files is lost
    (recoverable only from git history). Existing pages and `nav.adoc` are kept." Only if the user, in a separate
    explicit confirmation, also asks to regenerate the existing starter pages may Steps 7–9 overwrite them — never
    by default, and never pages outside the three starter roles.
  - **Stop** — "End this skill now without changing anything."

Every later step acts only on genuine gaps (or, under **Replace**, on the two config files plus gaps) — a
fully-set-up repository answered with **Update** should result in Step 13 reporting "already set up," not in
duplicated or overwritten files.

## Step 3 — Install the pinned toolchain

Read `reference/toolchain.md` (next to this file): it is the single source of truth for the versions, the Node
minimum, the install command and the `package.json` scripts. Do not restate or invent versions here. If `docs/`
doesn't exist yet, create it (`mkdir docs`). From inside `docs/`:

```bash
cd docs
[ -f package.json ] || npm init -y >/dev/null   # npm otherwise walks up and installs into the ROOT package.json
npm i -E <the pinned set from reference/toolchain.md>
```

- **`docs/package.json` must exist before the first `npm i`** (verified against a pipeline-generated repository): with no manifest in
  `docs/`, npm resolves the nearest ancestor `package.json` — the library/app's own root manifest — and installs
  Antora and the extensions there, polluting the project's real dependencies. The `npm init -y` guard above
  creates a minimal manifest first; set `"private": true` in it afterwards so it can never be published.
- One `npm i -E` command with `name@version` for every package: exact versions (no `^`/`~`), saved in `dependencies`,
  and `package-lock.json` written in the same run.
- The set adds Mermaid diagram rendering (`@sntke/antora-mermaid-extension`), MathJax equation rendering
  (`@djencks/asciidoctor-mathjax`) and the Pagefind CLI (`pagefind`, the search index). Search is Pagefind, not the
  Lunr extension: never install `@antora/lunr-extension`.
- Add `@antora/collector-extension` only when a component descriptor uses `ext.collector` (grep the repository's
  `antora.yml` files); otherwise leave it out.
- Edit `docs/package.json` after the install: `"engines": {"node": ">=20"}` and the `scripts` from
  `reference/toolchain.md` (`build`, `search:index`, `test:nav`, `test:nav:site`, `validate:mermaid`; the two
  `test:nav*` scripts belong with the override and `validate:mermaid` with `scripts/mermaid/`, all copied in Step 5).
- **Commit `docs/package.json` and `docs/package-lock.json`**: CI runs `npm ci` from the lockfile.
- If `docs/package.json` already lists a package from Step 2's survey at the pinned version, skip reinstalling it.
  `npm i -E <name>@<version>` is safe to re-run.

## Step 4 — Write `docs/antora.yml`

Determine three values first:

- **`name`**: a short slug for the project — the Antora *component* name, used in `start_page` references like
  `<name>::index.adoc`. Derive it from the project identifier (Maven `artifactId`, npm package `name`, `pyproject.toml`
  `[project].name`, the root `Cargo.toml` `[package] name`, the last element of the `go.mod` `module` path (skipping a
  `/vN` major-version suffix — `github.com/acme/hello-cli/v2` → `hello-cli`), a Swift package's executable product
  name in `Package.swift`, a .NET solution's `*.slnx`/`*.sln` file name, an XcodeGen `project.yml`'s `name:`, etc.),
  lowercased with no spaces.
- **`title`**: the human-readable project name (e.g. from a README's top heading, `pom.xml`'s `<name>`, or
  `package.json`'s `name` capitalized).
- **`version`**: the current release version, preferring, in order: (1) the latest GitHub release
  (`gh release view --json tagName -q .tagName` or `gh api repos/{owner}/{repo}/releases/latest`), (2) the latest
  git tag (`git tag --sort=-v:refname | head -1`), (3) the project manifest's version with any pre-release suffix
  like `-SNAPSHOT` (or Python's PEP 440 `.dev0`, or the `-dev` suffix .NET's `Directory.Build.props` `<Version>` and
  PHP use) stripped — for an Apple app, `MARKETING_VERSION` in `project.yml`/`Project.swift`/`*.xcconfig`. The
  manifest version of a command-line tool lives in: Rust — `[package] version` in the root `Cargo.toml` (never the
  `xtask` member's `0.0.0`); Go — `var Version = "…"` in `internal/version/version.go` (`go.mod` has no version; a
  module without that file relies on tags only); Swift — `public let toolVersion = "…"` in
  `Sources/<Bin>Kit/Version.swift`; .NET — `<Version>` in `Directory.Build.props`. Each carries a `-dev` suffix on
  the integration branch (e.g. `0.1.0-dev` → `0.1.0`). If none of these resolve, ask the user for a starting version
  rather than guessing.

Write (or, if `antora.yml` already exists and Step 2's answer was **Update**, only correct genuinely wrong fields
rather than rewriting the whole file; under **Replace**, rewrite it from this template, carrying over the existing
`name` unless it's wrong so existing `xref:`s and `start_page` keep resolving):

```yaml
name: <project-slug>
version: <current-release-version>
title: <Human Readable Project Title>
nav:
  - modules/ROOT/nav.adoc
```

Concrete example (`docs/antora.yml`):

```yaml
name: <name-of-repository>
version: 1.3.0
title: <Name-of-repository>
nav:
  - modules/ROOT/nav.adoc
```

## Step 5 — Write `docs/antora-playbook.yml`

The `site`/`content` sections are project-specific; the `ui`/`antora.extensions`/`asciidoc` sections are fixed
boilerplate this skill applies as-is (they wire up the extensions installed in Step 3 and the search/nav overrides) — don't improvise
alternatives for them. If the file already exists and Step 2's answer was **Update**, only add missing pieces (e.g.
an extension block) rather than rewriting sections that already match this shape; under **Replace**, rewrite the
whole file from the template below.

- `site.title`: same human-readable title as Step 4.
- `site.start_page`: `<component-name>::index.adoc`, using the `name` chosen in Step 4.
- `content.sources[0].url`: literally `..` — the playbook lives in `docs/`, one level below the repository root
  that Antora needs as its content source root.
- `content.sources[0].start_path`: literally `docs` — tells Antora the actual module content (this `antora.yml`
  and `modules/`) lives under `docs/` within that content root, not at the repository root itself.
- `ui.bundle.url`: literally `ui-bundle.zip` — a local, relative path resolved from wherever the playbook file
  itself lives. This skill ships its own `ui-bundle.zip` alongside this `SKILL.md`; copy that file into `docs/`
  (next to the `antora-playbook.yml` being written) so the relative path resolves. This avoids depending on the
  upstream GitLab CI artifact URL at build time.

```yaml
site:
  title: <Human Readable Project Title>
  start_page: <project-slug>::index.adoc
  # keys:                          # optional nav knobs, see ui-overrides/README.md
  #   nav_open_groups: 'Group A, Group B'
  #   nav_expand_depth: 1
content:
  sources:
    - url: ..
      branches: HEAD
      start_path: docs
ui:
  bundle:
    url: ui-bundle.zip
    snapshot: true
  supplemental_files:
    - path: ui.yml
      contents: |
        static_files:
        - .nojekyll
    - path: .nojekyll
    - path: partials/article.hbs
      contents: ./ui-overrides/partials/article.hbs
    - path: partials/footer-scripts.hbs
      contents: ./ui-overrides/partials/footer-scripts.hbs
    - path: partials/head-styles.hbs
      contents: ./ui-overrides/partials/head-styles.hbs
    - path: partials/header-content.hbs
      contents: ./ui-overrides/partials/header-content.hbs
    - path: partials/nav-tree.hbs
      contents: ./ui-overrides/partials/nav-tree.hbs
    - path: helpers/nav-item-state.js
      contents: ./ui-overrides/helpers/nav-item-state.js
    - path: css/pagefind-site.css
      contents: ./ui-overrides/css/pagefind-site.css
antora:
  extensions:
    - require: '@sntke/antora-mermaid-extension'
      mermaid_library_url: https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs
      script_stem: header-scripts
      mermaid_initialize_options:
        start_on_load: true
asciidoc:
  attributes:
    mathjax-tex-tags: ams
    mathjax-tex-packages: ams
  extensions:
    - '@djencks/asciidoctor-mathjax'
```

Also, in this step:

- **Remove the Lunr extension**: no `@antora/lunr-extension` entry in `antora.extensions`. Search is the Pagefind index
  that Step 12 writes after the build, served by the `ui.supplemental_files` entries above. Add
  `@antora/collector-extension` to `antora.extensions` only when Step 3 installed it.
- **Copy the overrides and checks into `docs/`**: this skill's `ui-overrides/` directory to `docs/ui-overrides/` and its
  `scripts/` directory to `docs/scripts/`, byte-identical (`cp -R`). Read `ui-overrides/README.md` for what each file does. The
  `test:nav` and `test:nav:site` scripts from Step 3 run the override's unit tests and a built-HTML dead-end check;
  `validate:mermaid` runs `scripts/mermaid/validate-mermaid.mjs` (see `reference/mermaid.md`).
- **Nav knobs**: the optional `site.keys.nav_open_groups` (comma-separated group titles whose children open by default,
  default none) and `site.keys.nav_expand_depth` (default 1) tune the slim nav. With neither set the override is a no-op
  on a small nav, so it is always written; set them only when the nav grows (thresholds in `reference/toolchain.md`).
  A group renamed in `nav.adoc` must be renamed in `nav_open_groups` too.

Concrete example, from this repository (`docs/antora-playbook.yml`), differs from the template above only in the
`site` block (`title: <Name-of-repository>`, `start_page: name-of-repository::index.adoc`) — every other section is copied verbatim, including the local `ui-bundle.zip` copied into `docs/`.

## Step 6 — Create the `ROOT` module skeleton

Create, if not already present:

```
docs/
  modules/
    ROOT/
      nav.adoc
      pages/
        index.adoc
        installation.adoc
        reference.adoc
```

Only create directories/files that Step 2's survey found missing. If a `ROOT` module already exists with
differently-named pages that already cover one of these three roles (e.g. this repository's own
`getting-started.adoc` already plays the "installation" role, and `index.adoc`/`reference.adoc` already exist),
don't create a duplicate page for that role — note in Step 13's report that the role is already covered under a
different name instead.

## Step 7 — Write `index.adoc` (repository overview)

Explore the codebase before writing this page. Read the README in full — it's usually compact and central to
what this page needs. For the top-level source layout and main public entry points/classes/exports, don't read
every file directly: use the `Explore` agent (the same one the `iru-explore` skill uses for this exact kind of broad
structural search) to build a picture of module layout and the small number of top-level public types/exports a
newcomer would actually care about, and read only those specific files directly once identified. This page should
give a newcomer: what the project is, why it exists, the core concept(s) it's built around, and a pointer to the
other two starter pages. Use a Mermaid diagram (`[mermaid]` block, written per `reference/mermaid.md`) if a flow or architecture is genuinely clearer
as a diagram, and a MathJax equation only if the project's core logic involves something worth stating precisely as
math — most projects won't need one, don't force it. Write math as `stem:[...]` inline or a `[stem]` block with
`:stem: latexmath` in the page header (verified in `reference/toolchain.md`, "Verified behaviors"); never write bare `\( \)` or
`\[ \]`, which render as literal text and make no build error.

Every generated page from this skill (`index.adoc`, `installation.adoc`, `reference.adoc`) must carry this exact
disclaimer, placed right after the page's `= Title` heading:

```
NOTE: This documentation was generated with the assistance of AI. Please report any inaccuracies.
```

This repository's own `docs/modules/ROOT/pages/index.adoc` is a concrete example of the shape to aim for: a short
overview, a Mermaid flowchart of the core algorithm, a couple of concept sections, and closing pointers to the
next pages.

### Nav authoring rules

Write these rules into the generated repository's `docs/README.adoc` or `docs/README.md` (create it if missing) and into
its `CLAUDE.md` docs section when one exists, so every later edit of `nav.adoc` follows them:

- Every nav entry is an `xref:` link or a text-only group heading **with children**. Never add an empty group heading.
- Nav entries never link to a `#fragment`: it renders as `href="#..."` and the bundle's script then strips every
  `is-active` class and collapses the default-open groups.
- A group renamed in `nav.adoc` that is listed in `site.keys.nav_open_groups` must be renamed there too.
- Prefer a section `index.adoc` link over a text-only heading for a group whose pages should be reachable from other
  sections: a linked entry's subtree may be pruned, a text-only heading's never is, so it costs HTML on every page of
  its parent.
- Run `npm run test:nav:site` (from `docs/`, after a build) after any `nav.adoc` change.

## Step 8 — Write `installation.adoc` (installation guide)

Discover the actual install path for this project's ecosystem rather than assuming Maven: a Maven/Gradle
dependency snippet (group/artifact/version), an npm/pip/cargo/go-get install command, or build-from-source steps
if the project isn't published as a package. Include the disclaimer note from Step 7. If the project publishes
both a latest release and a latest snapshot/pre-release (check the manifest version's suffix), show both, as this
repository's `getting-started.adoc` does for its Maven coordinates.

## Step 9 — Write `reference.adoc` (links of interest)

Discover what generated reports and external dashboards this repository actually has before listing them — don't
list a report that isn't actually produced anywhere. Check for: a Javadoc/API-doc generation step (Sphinx for Python, built from `docs/api/` and published
under the site's `api/`), a coverage
tool (JaCoCo, Istanbul/nyc, coverage.py) and where its report is published, a test-report plugin (Surefire, JUnit
HTML reporter), a SonarCloud/SonarQube project, and the repository's own GitHub URL. Link only what's confirmed to
exist (check CI config, `pom.xml`/`package.json` plugins, or existing GitHub Pages settings), in a table like this
repository's `reference.adoc`:

```asciidoc
= Reference

NOTE: This documentation was generated with the assistance of AI. Please report any inaccuracies.

These reports are generated during the build.

== Generated Reports

[cols="1,3",options="header"]
|===
|Report
|Description

|<link>
|<description>
|===
```

## Step 9b — User interface skeleton (repositories with a UI only)

Read `reference/ui-docs.md` (next to this file). If §1 says the repository has no user interface, skip this step.
Otherwise create, when missing, the skeleton its §2 describes — and nothing more:

- `pages/ui/index.adoc`: the disclaimer from Step 7, one paragraph on the app's UI, and the screens/components
  tables from §3 filled from a quick inventory (the `Explore` agent: screens/pages/activities/windows and reusable
  components, with the purpose their doc comments state). Link no per-screen page yet.
- `pages/ui/navigation.adoc`: the disclaimer and the navigation graph from the app's navigation definition
  (Mermaid `flowchart` — the playbook from Step 5 always wires Mermaid; any math there follows Step 7's `stem:[...]` rule), without screenshots.
- `images/ui/.gitkeep`, so the committed screenshot tree exists.

Per-screen and per-component pages and every screenshot are `iru-update-docs`' job (its UI step builds the whole
section on its first run on a UI repository) — tell the user to run `/iru-update-docs` after the UI tests have
produced `.ui-screenshots/`. On **Update**, existing `ui/` pages are kept as is like every other page.

## Step 10 — Update `nav.adoc`

Add an `xref:` entry for each page created in Steps 7–9b that doesn't already have one (Step 9b's pages go in
the **User interface** section shape from `reference/ui-docs.md` §2: `* xref:ui/index.adoc[User interface]` with
`** xref:ui/navigation.adoc[Navigation flows]` under it). Preserve the order and
content of any existing entries — append new entries in a sensible position (overview first, then the rest)
rather than reordering what's already there:

```asciidoc
* xref:index.adoc[]
* xref:installation.adoc[]
* xref:reference.adoc[]
```

A command-line tool repository later gains a generated `cli-reference.adoc` page (written from the command tree by
the CLI scaffold's docs generator) and its `* xref:cli-reference.adoc[Command-line reference]` nav entry —
`iru-setup-cli-repository` adds both after this skill runs. Don't create that page or entry here; on **Update**,
an existing one is like any other existing page and nav entry — kept as is.

## Step 11 — Ignore the build output

Check the repository's `.gitignore` for entries covering `docs/build/` **and** `docs/node_modules/`. If either
is missing, add it — every `iru-setup-<stack>-gitignore` skill in this catalog writes both, and CI reinstalls the
toolchain with `cd docs && npm ci` from the committed lockfile. Do **not** gitignore `docs/package.json` or
`docs/package-lock.json` — those must stay committed for `npm ci` to work.

Then check whether `docs/node_modules/` is tracked anyway (`git ls-files docs/node_modules | head -1`). Tracked
dependencies are a defect, not a quirk to leave alone: they bloat the repository and defeat the lockfile. If any file is
tracked, offer `git rm -r --cached docs/node_modules` (index only, the files stay on disk) and apply it only after the
user agrees; never commit it yourself.

## Step 12 — Build the site

From `docs/`, install from the lockfile when it exists, then build, index and check (`reference/toolchain.md` has the
same commands):

```bash
cd docs
npm ci                                  # only when a lockfile exists; otherwise Step 3 already installed
npx antora antora-playbook.yml
npx pagefind --site build/site
node scripts/nav-fix/check-nav-dead-ends.mjs build/site --sample
npm i --no-save mermaid@11 jsdom@31 puppeteer@25 && npm run validate:mermaid   # when any page has a [mermaid] block
```

- **Build succeeds**: the static site lands at `docs/build/site`; note the path to `docs/build/site/index.html`
  for the report. Antora exits with code 2 when a message reaches the failure level: treat that as a failed build.
- **Build fails**: read the Antora/Asciidoctor error (broken `xref:`, bad YAML, malformed AsciiDoc table, invalid
  Mermaid/MathJax syntax) and fix the offending file before finishing — don't report success with a broken build.
- **Pagefind** writes `docs/build/site/pagefind/`; it must finish with exit code 0 and report the pages it indexed.
- **Dead-end check** (exit 0 clean, 1 defects, 2 no nav): fix a defect (empty group heading, broken nav link) in
  `nav.adoc` before finishing.
- **Mermaid check** (`reference/mermaid.md`; exit 0 clean, 1 failures, 2 missing dependency or no diagram): the build
  does not parse Mermaid, so fix every `file:line` it reports before finishing. When puppeteer cannot run, run it
  without `--render` and say so in the report.
- **Search needs HTTP.** It works only when the site is served over HTTP, not from `file://`. Tell the user to try
  `python3 -m http.server 8090 --directory docs/build/site` and open `http://localhost:8090`.

## Step 13 — Report

Start by restating the mode from Step 2 — "created from scratch" or "updated an existing site" — and, for an
existing site, the option applied (**updated** / **kept as is** / **replaced**, and whether it came from the user
or from `on-existing`; or that the user chose **Stop**, in which case report only that). Then summarize, grouped by
what Step 2 found already present vs. what this run created or filled in:

- Node.js/Antora/extensions: already present vs. newly installed, listing the pinned versions installed (from
  `reference/toolchain.md`) and whether `docs/package-lock.json` was written (to be committed). An existing site on an
  older Antora or on Lunr: that `/iru-update-antora` was recommended.
- `docs/ui-overrides/` and `docs/scripts/`: copied, already present, or left out; the Pagefind index and dead-end check
  results from Step 12, the Mermaid check result (or why it was skipped), and the reminder that search needs the site
  served over HTTP.
- `antora.yml` / `antora-playbook.yml`: already present and correct, corrected, or newly written.
- `ui-bundle.zip`: already present at `docs/` vs. newly copied in from this skill's directory.
- Each of the three starter pages: newly created, or already covered by an existing page under a different name
  (name it).
- The **User interface** skeleton from Step 9b: created, already present, or not applicable (no UI).
- `nav.adoc` and `.gitignore`: entries added, if any; whether tracked `docs/node_modules/` was found and untracked.
- The nav authoring rules from Step 7: where they were written.
- Build result from Step 12, with the path to the built `index.html`.
- Under **Keep as is**: every gap from Step 2's list that was left unfilled (missing extension, missing starter
  page role, missing `ui-bundle.zip`, …), so the user can re-run with **Update** later.

Remind the user to review the generated `index.adoc`/`installation.adoc`/`reference.adoc` content themselves
before trusting it, and that `docs/build/` and `docs/node_modules/` should stay untracked while `docs/package.json`,
and `docs/package-lock.json` should be committed.

## Supporting files

| Path | Purpose |
|---|---|
| `reference/toolchain.md` | Pinned versions, Node minimum, install command, scripts, CI snippet, size guard, nav thresholds, Antora 3.2 notes, verified behaviors. Read in Step 3. |
| `reference/ui-docs.md` | User interface section guidance. Read in Step 9b. |
| `reference/mermaid.md` | Writing and validating Mermaid diagrams (tag-like text, size limit, the three checks). Read before writing a diagram and in Step 12. |
| `ui-bundle.zip` | The shared UI bundle, copied to `docs/` in Step 5. |
| `ui-overrides/` | Pagefind search and slim-nav partials, helper and CSS, plus a README; copied to `docs/ui-overrides/` in Step 5. |
| `scripts/nav-html.mjs` | Nav-menu extraction shared by both scripts below; copied to `docs/scripts/` with them. |
| `scripts/nav-fix/` | Nav helper unit tests and the built-HTML dead-end check; copied to `docs/scripts/`. |
| `scripts/site-metrics/` | `measure.mjs` and its tests: site and nav size verdict; copied to `docs/scripts/`. |
| `scripts/mermaid/` | `validate-mermaid.mjs` (static, parse and headless-render checks), `blocks.mjs` and its tests; copied to `docs/scripts/`. |
