Notes specific to this template:

- The prerelease convention is a **patch** bump, not a minor bump (deliberately different from this
  catalog's Java `sync.yml`, which bumps the minor and resets the patch): release `1.3.0` → next dev version
  `1.3.1-dev.0`. This matches the convention `iru-typescript-bump-version` documents and that `iru-release`
  relies on for the npm ecosystem — keep them consistent if either is ever revisited.
  If this repository does minor/major-level releases too, flag that as an open gap in Step 8 rather than
  silently guessing which bump the user wants, same as the Java skill does.
  - `<bump-version-command>`: `npm version "${{ steps.versions.outputs.next_version }}" --no-git-tag-version`
    / `pnpm version "${{ steps.versions.outputs.next_version }}" --no-git-tag-version` /
    `yarn version --new-version "${{ steps.versions.outputs.next_version }}" --no-git-tag-version` — resolved
    from `<package-manager>` in Step 4. Using the package manager's own `version` command (rather than a
    hand-rolled regex over `package.json`, unlike the Java template's `pom.xml` edit) also updates the
    lockfile in the same step, so `sync_versions.py` only has to handle README/Antora prose, not the manifest
    itself.
  - `<lockfile-name>`: `package-lock.json` / `pnpm-lock.yaml` / `yarn.lock`, matching `<package-manager>`.
- The sync branch is named after the **released** version, `sync_<release_version>` (release `1.0.1` →
  `sync_1.0.1`), not the next development version. The merge step has `id: merge`: if `git merge` conflicts it is
  aborted, the branch is recreated from `origin/<stable-branch>` and `conflict=true` is output. Every
  toolchain-setup, bump and commit step is gated on `steps.merge.outputs.conflict == 'false'`; push, label and PR
  stay gated on the guard only, and the PR gets the title "Sync <release> (merge conflicts to resolve)" with a
  body telling the reviewer to keep `<integration-branch>`'s development version. A conflicting (patch) sync
  never bumps, so it cannot lower the integration branch's development version.
- `CHANGELOG.md` is deliberately not touched by this workflow, for the same reason as the Java template: its
  release section and fresh `[Unreleased]` heading are written before the tag is published, and arrive on
  `<integration-branch>` via the merge step above.
- The `permissions:` block is necessary but not sufficient — flag in Step 8 that the repository's Settings →
  Actions → General → "Workflow permissions" must also allow GitHub Actions to create pull requests, or
  `gh pr create` will fail with a permissions error even though the token has the right scopes declared here.
