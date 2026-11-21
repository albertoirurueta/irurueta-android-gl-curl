---
name: iru-update-antora
description: "Invoke as `/iru-update-antora [<version>]`. Upgrades the Antora setup of a repository that already has one: pinned exact versions from reference/toolchain.md (or the given Antora version, resolved with npm view; `latest` for the newest), Lunr to Pagefind, npm ci workflows, slim-nav override, lockfile, untracked node_modules. Proves the result with a before/after build diff. Never edits pages. Use on sites still on Antora 3.1 or older."
model: sonnet
---

# Update Antora

Upgrade the documentation toolchain of a repository that already builds an Antora site, and prove the upgrade changed
nothing but the toolchain. It never edits page content, `nav.adoc` or `antora.yml` beyond what the upgrade requires
(for example quoting a YAML value Antora 3.2 reads differently), and it asks before every change. It makes no assumption
about the repository: discover the layout, the CI and the docs checks fresh each run.

Agent dispatch: this skill dispatches no catalog agent. Everything below runs in the current session.

## Step 0 — Resolve the target

Read `reference/toolchain.md` (next to this file): the pinned set, the Node minimum, the CI snippet, the nav thresholds
and the Antora 3.2 upgrade notes.

- **No argument**: the target is the pinned set in `reference/toolchain.md`.
- **`<version>`** (an Antora version): resolve it with `npm view antora@<version> version` (use `npm view antora version`
  when the argument is `latest`), keep the pinned extension versions unless the user asks otherwise, and say that this
  set was not tested by the catalog.

## Step 1 — Find each Antora toolchain

A repository can hold several (a docs-only repository has one at its root). Find:

- every `package.json` that depends on `antora` (`grep -l '"antora"' $(git ls-files '*package.json')`, ignoring
  `node_modules`): usually `docs/`, or the root;
- its playbook (`antora-playbook.yml` or `site.yml`, named in `package.json` scripts or the workflows);
- the workflows that build it (`grep -l 'npx antora' .github/workflows/*`, plus any other CI the repository has).

None found: stop and point to `/iru-setup-antora`. Run the rest of the steps once per toolchain.

## Step 2 — Report and confirm

Fetch the Antora changelog at run time (`https://gitlab.com/antora/antora/-/raw/main/CHANGELOG.adoc`, or the release
notes with WebFetch), never from memory, and show the user:

- current and target versions of Antora and of each extension, and what is installed versus declared;
- the Node.js minimum (20 for the pinned set) against `node -v`;
- the changelog entries between the two versions that touch playbook keys, extensions, `ext` keys in `antora.yml` or the
  UI model;
- what the repository uses that changed in Antora 3.2 (list in `reference/toolchain.md`): `ext` keys no longer
  camelCased, exit code 2 at the failure level, no trailing slash on `site.url`, unquoted YAML attribute values that
  YAML reads as another type. Check each against the actual `antora.yml`, playbook and pages.

Ask with `AskUserQuestion` before changing anything. Stop if the user declines.

## Step 3 — Baseline build

Before touching anything, build the current site into a scratch directory so Step 7 can diff against it. From the toolchain directory:

```bash
npm ci || npm install        # install what the repository currently declares
rm -rf build/site && npx antora <playbook> > "$SCRATCH/baseline.log" 2>&1; echo $? > "$SCRATCH/baseline.exit"
cp -R build/site "$SCRATCH/baseline-site"
```

`$SCRATCH` is a new directory under the session scratchpad. Keep the log and the exit code. If the baseline fails,
report it and ask whether to continue: a diff against a broken baseline proves nothing.

## Step 4 — Upgrade

In the toolchain directory:

- `npm i -E` the target set as one command (exact versions; the collector only when an `antora.yml` uses `ext.collector`;
  `pagefind` per Step 5);
- remove packages the playbook no longer requires (for example a commented-out `@antora/lunr-extension`) with
  `npm uninstall`;
- add `"engines": {"node": ">=20"}` and the `build` and `search:index` scripts from `reference/toolchain.md` when missing;
- when any page has a `[mermaid]` block: copy this skill's `scripts/mermaid/` into the toolchain directory's `scripts/`
  and add the `validate:mermaid` script from `reference/toolchain.md` (its three packages are installed on demand with
  `--no-save`, never added to `package.json`; `reference/mermaid.md` explains why);
- regenerate `package-lock.json` (the install does) and tell the user to commit it;
- if `node_modules/` is tracked although ignored (`git ls-files node_modules | head -1`), offer
  `git rm -r --cached node_modules` and make sure `.gitignore` lists it; never commit.

## Step 5 — Search

If the playbook requires `@antora/lunr-extension`, ask whether to switch to Pagefind. The recommended answer is yes:

- remove the Lunr extension from the playbook and `package.json`;
- add the `pagefind` dependency (pinned) and the `search:index` script;
- copy this skill's `ui-overrides/` to `<toolchain>/ui-overrides/` and register the files in the playbook's
  `ui.supplemental_files` exactly as `ui-overrides/README.md` shows (keep entries the playbook already has; ask when one
  collides with an override);
- add the Pagefind step to the workflows in Step 6.

If the site already uses Pagefind overrides, compare them with this skill's copies and offer to update the older ones.
Search is checked in Step 7 over HTTP.

## Step 6 — Workflows

For each workflow found in Step 1, after showing the diff and getting approval:

- replace per-package installs, `mkdir docs` and `docs-toolchain` (see "Verified behaviors" in `reference/toolchain.md`)
  with `npm ci` in the toolchain directory (`working-directory`), as in the CI snippet;
- add `npx antora -v` and the Pagefind step (placement rule in `reference/toolchain.md`);
- set `actions/setup-node` to the new Node minimum or later (the snippet uses 24) with the npm cache pointed at the
  lockfile;
- keep every other step as it is: `.nojekyll`, API-doc merges, deploys.

## Step 7 — Verify

From the toolchain directory:

1. `rm -rf build/site && npx antora <playbook>`, log kept in the scratch directory, then `npx pagefind --site build/site`
   when Step 5 applied.
2. Diff `build/site` against `$SCRATCH/baseline-site`, ignoring the `generator` meta tag and the sitemap's `lastmod`
   (`diff -r` after normalizing those two). Search additions (`pagefind/`, the override markup) are expected when Step 5
   or Step 8 applied; every other difference must be explained, or reported as unexplained.
3. Compare the logs: no new warnings, exit code 0 (Antora 3.2 exits with 2 at the failure level).
4. Run the repository's own docs checks, as its `CLAUDE.md` or README names them, and the Mermaid check when pages
   have `[mermaid]` blocks: `npm i --no-save mermaid@11 jsdom@31 puppeteer@25 && npm run validate:mermaid`
   (`reference/mermaid.md`). Run it against the baseline too: a diagram that already failed before the upgrade is
   reported as pre-existing, not as a regression.
5. Test search over HTTP: `python3 -m http.server 8090 --directory build/site`, load a page and run a query (Browser
   tools when available), then stop the server. Search does not work from `file://`.

## Step 8 — UI bundle and nav override

**UI bundle.** Compare the bundle's partials and the playbook's `ui.supplemental_files` overrides with the UI-model
changes the changelog lists. If the changelog shows a rebuilt bundle is required, stop and tell the user: the bundle is
supplied by them, never generated by this skill. The shared bundle is unchanged for 3.2.2.

**Nav override.** Measure the built site with `node scripts/site-metrics/measure.mjs build/site` (copy this skill's
`scripts/` temporarily or run it from the skill directory).

- Exit 3 (a threshold in `reference/toolchain.md` is crossed) and no `ui-overrides/partials/nav-tree.hbs`: offer to
  install the override (`ui-overrides/` and `scripts/` copied into the toolchain directory, `ui.supplemental_files`
  entries and the `test:nav`, `test:nav:site` scripts from `reference/toolchain.md`), and recommend it.
- An existing `nav-tree.hbs` older than this skill's copy (no text-only-group rule, or a hard-coded group title such as
  a named "open" group): offer to replace it and `helpers/nav-item-state.js`, and move the hard-coded title to
  `site.keys.nav_open_groups`. Show the diff first.
- Before and after any override change, compare the nav HTML (`nav.nav-menu`) of a sample of pages (the home page and one
  per nav section) and show what differs; only pruned subtrees may differ. Then run
  `node scripts/nav-fix/check-nav-dead-ends.mjs build/site --sample` (must exit 0) and the unit tests
  (`npm run test:nav`).

## Step 9 — Docs and report

- Update `README.md`, `CLAUDE.md` and Antora pages that state the Antora version or the install commands (search the
  repository for `npm i`, `lunr`, `antora@` and the old versions). This is the only content the skill edits, and only
  those statements, never other page text.
- Report: what changed, before and after versions, the build comparison from Step 7 (differences and their
  explanations), the Pagefind result, the measure verdict and the Mermaid check result, the files to commit (`package.json`, `package-lock.json`,
  playbook, workflows, overrides) and the untracking offer's outcome.
- List anything left unverified (a workflow that could not run locally, search not exercised, an unexplained diff).

## Supporting files

| Path | Purpose |
|---|---|
| `reference/toolchain.md` | Pinned versions, Node minimum, CI snippet, thresholds, 3.2 notes, verified behaviors. Read in Step 0. |
| `reference/mermaid.md` | Writing and validating Mermaid diagrams. Read in Steps 4 and 7. |
| `ui-overrides/` | Pagefind and slim-nav overrides plus README, copied into a toolchain in Steps 5 and 8. |
| `scripts/nav-html.mjs` | Nav-menu extraction shared by both scripts below (copy it with them). |
| `scripts/nav-fix/` | Nav helper tests and the built-HTML dead-end check. |
| `scripts/site-metrics/` | `measure.mjs` and its tests: the size and nav verdict. |
| `scripts/mermaid/` | `validate-mermaid.mjs` (static, parse and headless-render checks), `blocks.mjs` and its tests. |

These files are byte-identical copies of `iru-setup-antora`'s: change them there and copy them here.
