Notes specific to this template:

- Only ONE of the two `Merge documentation` `mkdir`/`cp` lines applies per repository — keep the one matching
  the resolved `docs-tool` (`typedoc`/`compodoc` → `docs/api`; `storybook` → a `docs/build/site/storybook`
  sibling path, since Storybook is a component gallery, not an API reference, and merging it under `api/`
  would be misleading) and drop the other, plus the whole `Build API docs`/merge block when `docs-tool: none`.
  Likewise keep only ONE of the two `Build API docs` `run:` lines: `<package-manager-run> docs` for
  `typedoc`/`compodoc`, `<package-manager-run> build-storybook` for `storybook` (`iru-setup-react-web` Step 7 /
  `iru-setup-angular-web` Step 12 rely on Storybook's own installer, which writes `storybook`/`build-storybook`
  scripts and nothing named `docs`).
- The Antora steps assume `iru-setup-antora`'s layout — `docs/package.json` listing `antora` plus the
  mermaid/mathjax extensions and `pagefind`, and a committed `docs/package-lock.json` — so `npm ci` resolves every
  extension the playbook names from `docs/node_modules/` and `setup-node` can cache it. Never install the
  toolchain into some other directory (e.g. an ad hoc `docs-site/`): `antora` resolves playbook extensions
  relative to the playbook's own directory, so they'd be "not found" at build time.
- The docs steps run `npm ci`, `npx antora -v`, the build and `npx pagefind --site build/site` in `docs/` (the CI snippet in
  `iru-setup-antora`'s `reference/toolchain.md`); the Pagefind index covers the Antora pages only, since the API
  reference is merged afterwards. The Node used must be 20 or later. The commented "Check site size" step is an optional
  guard against GitHub Pages' 1 GB limit.
- `actions/upload-pages-artifact`/`actions/deploy-pages` need the repository's Settings → Pages → "Build and
  deployment" → Source set to **GitHub Actions** (not "Deploy from a branch") — `deploy-pages` fails outright
  if that setting still points at a branch. Call this out in Step 7/8.
- **`pages: no`** keeps every docs build step (so a broken page or a TypeDoc warning still fails CI) but swaps
  `Upload Pages artifact` for `Upload docs artifact` and drops `deploy-docs` entirely. The artifact is kept for
  `retention-days` (30 here) and can be downloaded from the run's summary page. Nothing in this file then needs
  the Pages setting, the `github-pages` environment, or a paid plan on a private repository.
- **`ci-tests: unit`** drops only the four Playwright steps (`Install Playwright browsers`, `Run e2e tests`,
  `Upload Playwright report`, `Upload UI screenshots`); lint/format/typecheck/unit tests with coverage and the optional Sonar scan stay
  unchanged. Browser installation alone is a noticeable share of a web flavor's build time, so this is the
  main minutes saving on private repositories.
- The `concurrency` group on `deploy-pages`-style jobs matters: without it, two pushes to the stable branch in
  quick succession can race and leave Pages showing an older deploy that finished last. The `build` job's own
  `concurrency` group (workflow-level, keyed on `ref`) already serializes runs on the same branch, which is
  sufficient here since `deploy-docs` only runs from the stable branch's own `build.yml` run.
- `<package-manager-run>`: `npm run` / `pnpm run` / `yarn run` (or bare `yarn <script>` for Yarn) — resolve
  once in Step 4 and reuse for every `run:` line above.
- `setup-node`'s `cache: <package-manager>` fails outright if the matching lockfile doesn't exist yet
  ("Dependencies lock file is not found") — never generate this workflow before the scaffold skill has run
  `npm install`/`pnpm install`/`yarn install` at least once and committed the lockfile.
