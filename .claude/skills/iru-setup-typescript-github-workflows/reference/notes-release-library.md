Notes specific to this template:

- `version:` must run the `changeset` **binary** directly through `<package-manager-exec>` (`npx` / `pnpm exec` /
  bare `yarn`, see Step 4), never `<package-manager-run> changeset version`: `iru-setup-typescript-library`'s
  `package.json` defines no `changeset` npm script, so `npm run changeset version` fails with "Missing script"
  on the very first run.
- This job triggers on a push to the stable branch — not on `release: published` like the `publish` job above
  it — because its purpose is to open the *next* version-bump PR once the current release has landed, not to
  react to the release itself; the top-level `on:` block above only lists `release`/`workflow_dispatch`, so
  add `push: branches: [ <stable-branch> ]` to the workflow's `on:` block when `publish: yes` includes this
  job (both triggers coexist in the same file).
- `changesets/action@v2` needs `.changeset/config.json` to already exist (the `iru-setup-typescript-library`
  scaffold creates it when `publish: yes`) — if it's missing, note this as an open gap rather than generating
  a job that no-ops silently.
