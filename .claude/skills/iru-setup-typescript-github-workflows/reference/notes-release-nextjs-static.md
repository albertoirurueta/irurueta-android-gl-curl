Notes specific to this template:

- **`basePath`**: a project site lives at `https://<owner>.github.io/<repository>/`, so every `/_next/...`
  asset and every `<Link>` must carry `/<repository>`. The workflow doesn't hardcode it: `configure-pages`
  computes it (and keeps working after a repository rename or a custom domain), and `next.config` reads
  `process.env.PAGES_BASE_PATH` (Step 1's `output` survey). Don't use `configure-pages`' own
  `static_site_generator: next` option instead — it rewrites a `next.config.js` in place and can't edit the
  `next.config.ts` the scaffold writes.
- `.nojekyll` isn't needed: `actions/deploy-pages` publishes the artifact as-is without running Jekyll, so the
  `_next/` directory is served.
- Dynamic features (`next start`, route handlers with request data, server actions, middleware, ISR) don't
  exist in an export; a build that uses them fails here — that's the scaffold's `output` decision, not
  something this workflow can paper over.
- The `react`/`angular` notes on sharing one Pages site with `build.yml`'s `deploy-docs`, and on `pages: no`
  meaning "nothing is hosted", apply unchanged.
