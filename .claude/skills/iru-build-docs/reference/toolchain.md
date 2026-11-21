# Antora toolchain (single source of truth)

The exact tool versions every catalog skill and workflow template uses for an Antora documentation site. This file is
kept byte-identical in `iru-setup-antora`, `iru-build-docs`, `iru-update-docs` and `iru-update-antora`; change one,
copy it to all. Workflow templates do not carry versions: they run `npm ci` from the committed lockfile and take the
Node version and the CI snippet below.

## Tested set

Pin every package exactly (no `^` or `~`), so a rebuild a year later produces the same site.

| Package | Version | When |
|---|---|---|
| `antora` | 3.2.2 | always |
| `@antora/collector-extension` | 1.0.3 | only when an `antora.yml` uses `ext.collector` |
| `@sntke/antora-mermaid-extension` | 0.0.13 | always (Mermaid diagrams) |
| `@djencks/asciidoctor-mathjax` | 0.0.9 | always (MathJax) |
| `pagefind` | 1.6.0 | always (search index; replaces the Lunr extension) |

Node: minimum 20 (`"engines": {"node": ">=20"}` in `docs/package.json`), Node 24 in CI.

Install (one command, `-E` so versions are saved exactly; drop the collector when it is not used):

```bash
cd docs && npm i -E antora@3.2.2 @antora/collector-extension@1.0.3 @sntke/antora-mermaid-extension@0.0.13 @djencks/asciidoctor-mathjax@0.0.9 pagefind@1.6.0
```

Commit `docs/package.json` and `docs/package-lock.json`; never commit `docs/node_modules/` (it belongs in `.gitignore`).

`docs/package.json` scripts:

```json
{
  "scripts": {
    "build": "antora antora-playbook.yml",
    "search:index": "pagefind --site build/site",
    "test:nav": "node --test \"scripts/nav-fix/*.test.mjs\" \"scripts/site-metrics/*.test.mjs\" \"scripts/mermaid/*.test.mjs\"",
    "test:nav:site": "node scripts/nav-fix/check-nav-dead-ends.mjs build/site",
    "validate:mermaid": "node scripts/mermaid/validate-mermaid.mjs modules --render"
  },
  "engines": { "node": ">=20" }
}
```

`test:nav` and `test:nav:site` exist only when the nav override and its scripts are installed in the repository.
`validate:mermaid` exists when `scripts/mermaid/` is installed (every site that uses Mermaid). Its three dependencies are
validation-only and stay out of `package.json` and the lockfile; install them on demand, without saving:

```bash
cd docs && npm i --no-save mermaid@11 jsdom@31 puppeteer@25 && npm run validate:mermaid
```

`mermaid@11` matches the `mermaid_library_url` the playbook loads in the browser (`mermaid@11`). Puppeteer downloads a
headless Chrome on install; the `--render` check needs it. Guidance: `reference/mermaid.md`.

## CI snippet

Every step runs in `docs/`. No per-package install, no `mkdir docs`, no `docs-toolchain`.

Prerequisite: a committed `docs/package-lock.json`. `cache: npm` keys the setup-node cache on it and fails the job at
that step when it is missing, before `npm ci` runs. A workflow skill that writes this snippet checks the lockfile
first: with `docs/` but no lockfile (a site from the old per-package install) it points to `/iru-update-antora`, with
no `docs/` to `iru-setup-antora`.

```yaml
      - uses: actions/setup-node@v4
        with:
          node-version: 24
          cache: npm
          cache-dependency-path: docs/package-lock.json
      - name: Install docs toolchain
        working-directory: docs
        run: npm ci
      - name: Show Antora version
        working-directory: docs
        run: npx antora -v
      - name: Build documentation
        working-directory: docs
        run: npx antora antora-playbook.yml
      - name: Index the site for search
        working-directory: docs
        run: npx pagefind --site build/site
```

Where Pagefind runs: right after the Antora build when the published site is only the Antora output. When API docs
(Javadoc, Dokka, DocC, rustdoc, ...) are copied into `build/site` afterwards, run Pagefind after that merge only if
the API pages should be searchable; otherwise keep it before, so the index stays small.

Optional size guard (GitHub Pages rejects a site over 1 GB), placed after the last step that writes into `build/site`:

```yaml
      - name: Check site size
        working-directory: docs
        run: |
          size=$(du -sk build/site | cut -f1)
          echo "build/site is ${size} KiB"
          if [ "$size" -gt 960000 ]; then echo "::error::site over 960,000 KiB"; exit 1; fi
          if [ "$size" -gt 900000 ]; then echo "::warning::site over 900,000 KiB"; fi
```

Warn above 900,000 KiB, fail above 960,000 KiB.

Mermaid check, for a site with `scripts/mermaid/` (place it before the build, so a broken diagram fails fast):

```yaml
      - name: Validate Mermaid diagrams
        working-directory: docs
        run: npm i --no-save mermaid@11 jsdom@31 puppeteer@25 && npm run validate:mermaid
```

## When the nav needs the slimming override

Recommend the generic nav-tree override (`ui-overrides/`, see `ui-overrides/README.md`) when any of these holds:

- more than about 300 nav entries;
- the nav is more than about 30% of a typical page's HTML;
- `du -sk build/site` is over 500,000 KiB.

A new site gets the override anyway; its defaults are a no-op on a small nav.

## Upgrading to Antora 3.2

- Node 20 or later is required.
- Antora exits with code 2 when a message reaches the configured failure level (`runtime.log.failure_level`); a build
  that only warned before may now fail CI.
- `ext` keys in `antora.yml` (for example `ext.collector`) are no longer camelCased.
- `site.url` has no trailing slash.
- Quote YAML attribute values that YAML would otherwise parse as another type (`'1.0'`, `'yes'`, `'on'`).
- Keys of the playbook's `site.keys` are still camelCased in the UI model (see Verified behaviors).

## Verified behaviors

Observed in a scratch build, not assumed from documentation.

- Antora 3.2.2 camelCases the keys of the playbook's `site.keys` in the UI model: `nav_open_groups` is read in
  templates as `@root.site.keys.navOpenGroups` and `nav_expand_depth` as `navExpandDepth`. `@root` resolves at any
  partial depth; a key that is not set is `undefined`; a number stays a number.
- Math: with the playbook this catalog generates (`@djencks/asciidoctor-mathjax` 0.0.9 in `asciidoc.extensions`, the
  `mathjax-tex-*` attributes, no client-side MathJax script), bare `\( ... \)` and `\[ ... \]` do **not** render: they
  stay literal text in the page (and in the Pagefind index). `stem:[...]` and a `[stem]` block render, and so does
  `latexmath:[...]`; the extension pre-renders them at build time to inline SVG (`span.steminline`, `div.stemblock`),
  so no script is loaded in the browser. The `:stem: latexmath` page attribute is not required for
  these to render (without it `stem:[x^2]` also rendered), but set it so the content is read as LaTeX rather than
  AsciiMath. Guidance to quote: "Write math with `stem:[...]` inline and a `[stem]` block, with
  `:stem: latexmath` in the page header; never write bare `\( \)` or `\[ \]`, which render as literal text."
- Pagefind 1.6.0 over `build/site` with `data-pagefind-body` on the article (as `ui-overrides/partials/article.hbs`
  writes it): "Found a data-pagefind-body element on the site. Ignoring pages without this tag". Of 7 HTML files (5 pages,
  the 404 page and the root redirect) 5 were indexed; the extracted fragments contain only title and article text, with no
  navigation, header or footer text and no page without the attribute. The hidden component-filter span adds the
  `component` filter. Pagefind prints a notice that the Default UI (`pagefind-ui.js`) is supported but that the Component
  UI is recommended for new integrations; it is not a warning of the run and the exit code is 0.
- Mermaid: a diagram can pass `mermaid.parse()` and still fail in the browser. The 721-line dependency graph of this
  catalog's home page (31 subgraphs, 428 edges, Mermaid 11.17.2) parsed, but `mermaid.render()` threw "Cannot set
  properties of undefined (setting 'order')" in the dagre layout, so the page showed Mermaid's error graphic while the
  Antora build and a parse-only check were clean. Removing any one of several subgraphs, or all of them, made it render:
  it is a size limit, not a syntax error. `validate-mermaid.mjs --render` reports it; keep each flowchart small (see
  `reference/mermaid.md`).
- The old `docs-toolchain` CI pattern (`mkdir -p docs-toolchain && cd docs-toolchain`, `npm i` the packages there, then
  `cd docs && npx antora antora-playbook.yml`) does not work as a toolchain. `npx` run in `docs/` does not see
  `docs-toolchain/node_modules` (a sibling is not on the module resolution path), so it downloads `antora` from the
  registry into the npx cache (`~/.npm/_npx/...`) whatever version is `latest` that day (3.2.2 when tested, which only
  matched by coincidence: nothing pins it), and then `require: '@sntke/antora-mermaid-extension'` fails with the fatal
  "Cannot find module ... at path <docs dir> or <npx cache>/@antora/site-generator/lib" and exit code 1; the same
  happens for every other extension. The Java variant `mkdir docs && cd docs` fails earlier on a repository that
  already has `docs/` (`mkdir: docs: File exists`). Installing in `docs/` from the committed lockfile (`npm ci`) avoids
  both.
