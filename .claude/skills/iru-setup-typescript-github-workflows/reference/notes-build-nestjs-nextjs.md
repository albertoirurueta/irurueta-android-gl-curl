Notes specific to this template:

- **Step order** is cheapest-first, matching the coding skills' group validation: static checks → unit tests
  with coverage (the only coverage Sonar sees — the e2e suites never feed the 80% gate) → build → e2e → image.
  The Next.js e2e needs the build first (Playwright drives the production server); the NestJS e2e boots its
  own `Test.createTestingModule` app and doesn't, but running after `build` costs nothing.
- **`ci-tests: unit`** drops `Run e2e tests (Testcontainers)` (nestjs) or the four Playwright steps, including
  `Upload UI screenshots` (nextjs);
  everything else stays. For nestjs that also skips pulling the database image, the main minutes saving.
- **Testcontainers on GitHub runners**: no `services:` block and no `DOCKER_HOST` tweak — Testcontainers finds
  the runner's Docker socket itself, and Ryuk cleans up. A self-hosted runner without Docker fails this step:
  say so in Step 8 if the user mentioned self-hosted runners.
- **`<package-manager-exec> prisma generate`** uses the project's pinned `prisma` devDependency (never a bare
  `npx prisma@latest`); it needs no database. Migrations are not applied here — the e2e suite's own setup
  applies them to its throwaway container.
- **Image build check** uses the GitHub Actions cache backend (`type=gha`) so `release.yml`'s push build
  reuses the layers. `<image-name>:ci` is a local tag only — nothing is pushed or logged in to. Drop the two
  Docker steps for `distribution: none` and for `nextjs` with `output: static` (no image).
- **`nextjs` with `output: static` and `pages: yes`** shares one Pages site between `deploy-docs` (this file)
  and the app deploy (`release.yml`), exactly like `react`/`angular` — the same "Decision made" note under the
  react/angular `release.yml` template applies, including the option of shipping the docs under `out/docs/`.
