---
name: iru-build-docs
description: "Invoke as `/iru-build-docs`. Builds a repository's existing Antora documentation site, setting it up first via `iru-setup-antora` if missing. No code exploration and no page edits (that is `iru-update-docs`). Use when the user just wants the docs built or verified, or the built HTML."
model: haiku
---

# Build Docs

Build a repository's Antora documentation site as-is. This skill never edits page content, `nav.adoc`, or
`antora.yml` beyond what bootstrapping requires — if the docs need to be updated to reflect code changes, that's
`iru-update-docs`, not this skill.

## Step 1 — Locate the documentation module

Find `antora.yml` file(s) in the repository (`find . -name antora.yml -not -path '*/node_modules/*'`).

- **None found**: there is no documentation module yet to build. Ask the user whether Antora documentation
  should be set up now (via the `iru-setup-antora` skill) before continuing — don't assume either way.
  - **User agrees**: run the `iru-setup-antora` skill (via the Agent tool, or directly via Skill if already in this
    context) to scaffold the module. If it reports it stopped short (e.g. Node.js isn't installed), relay that
    blocker to the user and stop — there's nothing to build yet.
  - **User declines**: stop this skill entirely — there is nothing for it to build.
- **One or more found**: continue to Step 2 for each one.

## Step 2 — Locate the playbook and toolchain

For the docs module found in Step 1, locate its Antora playbook file (commonly `antora-playbook.yml` or
`site.yml`, usually next to `antora.yml` or at the docs module root) — don't assume a fixed name, check
`docs/package.json` scripts or the repository's CI config (e.g. `.github/workflows/docs.yml`) if it isn't
obvious.

Check Node.js is usable (`node -v` / `npm -v`). If either fails, stop and point the user at `iru-setup-antora`'s
Step 1 install instructions rather than attempting to install Node.js yourself.

## Step 3 — Install dependencies and build

Read `reference/toolchain.md` (next to this file) for the pinned Antora version and the thresholds below. From the
docs module's directory (`<docs>`):

```bash
npm ci
npx antora -v
npx antora <playbook-file>
```

Use `npm ci` when a lockfile is present (matching CI); fall back to `npm install` only if there's no lockfile
yet.

- **Version check**: compare `npx antora -v` with the pinned `antora` in `reference/toolchain.md`. If the installed
  Antora is older, warn the user and point to `/iru-update-antora` (it upgrades the toolchain; this skill does not).
  Still build.
- **Fresh output**: when Step 3b will measure sizes, delete `<docs>/build/site` first (`rm -rf build/site`)
  because Antora and Pagefind never remove stale files, which would skew the numbers.
- **Build succeeds**: the static site lands at the module's build output directory (e.g. `docs/build/site`).
  Note the path to its `index.html`.
- **Build fails**: read the Antora/Asciidoctor error (broken `xref:`, bad YAML, malformed AsciiDoc table,
  invalid Mermaid/MathJax syntax) and report it verbatim to the user along with the offending file — do not fix
  page content yourself; that's `iru-update-docs`'s job, not this skill's.

## Step 3b — Index, check and measure

After a successful build, from `<docs>`:

1. **Search index**: when `pagefind` is a dependency in `package.json`, run `npm run search:index` (or
   `npx pagefind --site build/site` when the script is missing). Report a failure verbatim.
2. **Dead-end check**: when `ui-overrides/partials/nav-tree.hbs` exists in the docs module, run
   `node <path-to>/check-nav-dead-ends.mjs build/site --sample`: use the repository's own `scripts/nav-fix/` copy
   when present, else this skill's `scripts/nav-fix/check-nav-dead-ends.mjs`. Exit 0 is clean, 1 means defects (report
   each one; never fix `nav.adoc` yourself), 2 means no nav.
3. **Size and nav measure**: run `node <path-to>/measure.mjs build/site` (the repository's `scripts/site-metrics/`
   copy, else this skill's). Exit 0 is fine (ok, or the override already in use), 3 means a threshold from
   `reference/toolchain.md` is crossed and the nav override is not installed: recommend `iru-setup-antora`'s
   `ui-overrides/` (via `/iru-update-antora`). Never install it yourself.
4. **Mermaid check**: when any `.adoc` under the module has a `[mermaid]` block, follow `reference/mermaid.md`: run
   `npm i --no-save mermaid@11 jsdom@31 puppeteer@25`, then `node <path-to>/validate-mermaid.mjs modules --render` (the
   repository's `scripts/mermaid/` copy, else this skill's). The Antora build does not parse Mermaid, so this is the
   only check of the diagrams. Exit 1 lists each failing diagram as `file:line` with the failed check: report them
   verbatim, never fix the pages yourself (`/iru-update-docs` does). When puppeteer cannot run, drop `--render` and say
   so.

## Step 4 — Report

State whether the module already existed or was just set up via `iru-setup-antora`, whether the build succeeded or
failed, and the path to the built `index.html` (or the build error). Add, only when they apply: the Pagefind index
result, the dead-end check result, the Mermaid check result, the nav size recommendation, and the outdated-Antora warning. Remind that search needs
the site served over HTTP (`python3 -m http.server 8090 --directory docs/build/site`). Nothing else.
