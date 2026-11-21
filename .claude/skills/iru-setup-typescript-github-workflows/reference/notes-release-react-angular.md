Notes specific to this template:

- `<web-dist-dir>` is **not** `dist/` for both flavors: Vite writes the deployable `index.html` straight into
  `dist/`, but Angular's application builder writes it to `dist/<project>/browser/` (with a sibling `server/`
  when SSR is on and a `3rdpartylicenses.txt` beside them) — uploading the bare `dist/` on Angular publishes a
  Pages root with no `index.html`. Resolve it from `angular.json` per Step 1's survey / Step 4's table.

- **Decision made (no ambiguity worth blocking on, `pages: yes` only):** this job deploys the built app to the *same* GitHub
  Pages site `build.yml`'s `deploy-docs` job publishes the Antora/API docs to, and each workflow's own Pages
  deploy fully replaces whatever the other last published — there is no single-site "merge" between the two
  workflows across separate runs. Recommend the app deploy publish under the site root and treat the docs
  deploy as informational only (or vice-versa, published under a `/docs` subpath by copying the merged Antora
  site into `dist/docs/` before this job's `Upload Pages artifact` step) if the repository genuinely needs
  both live at once; flag this explicitly in Step 8's report as a design choice the user should confirm rather
  than something this skill resolves silently.
- `actions/upload-artifact` here is a convenience for downloading the exact bundle that was deployed (e.g. for
  a manual smoke test) — it isn't consumed by any later job in this workflow. With `pages: no` it becomes the
  release's only output.
- **`pages: no` on a private/internal repository** (the recommended choice there): explain to the user that
  GitHub Pages is the only host this skill wires up, that a Pages site from a private repository needs a paid
  plan and is publicly reachable unless they're on Enterprise Cloud with private Pages access control, and
  that the release therefore stops at a built, downloadable bundle. Hosting it is their call — e.g. adding a
  deploy step for Netlify/Vercel/Cloudflare Pages/S3+CloudFront/an internal server to this same job after
  `Upload build artifact`. That step and its credentials are out of this skill's scope; list it as a follow-up
  in Step 8 rather than improvising one. The decision about sharing one Pages site with `build.yml`'s docs
  (next bullet) no longer applies in that case.
