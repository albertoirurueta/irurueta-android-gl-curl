Notes specific to this template:

- Same image job shape as this catalog's other GHCR releases (`iru-setup-php-github-workflows`' Laravel image,
  `iru-setup-quarkus-github-workflows`' Docker variant): lowercase owner, `<version>` + `latest` tags, OCI
  labels from `metadata-action` (source repository, revision, version), GHA layer cache shared with
  `build.yml`'s unpushed build check. `build-push-action` attaches its default minimal provenance attestation
  to a pushed image; no explicit `provenance:`/`sbom:` inputs are set, consistent with those skills — add
  `provenance: mode=max` + `sbom: true` only if the consumer verifies attestations.
- The released commit was already tested by `build.yml` on `<stable-branch>`, so no tests run here.
- No image is pushed per `<integration-branch>` push — a registry full of `-dev` tags costs storage on private
  repositories; add one to `build.yml` only if a deployment pipeline consumes it.
- A new GHCR package is **private** by default even on a public repository, and is linked to the repository
  through the `org.opencontainers.image.source` label `metadata-action` writes. Deploying the image (Kubernetes,
  a VM, a PaaS) is the user's own pipeline — out of this skill's scope; list it as a follow-up in Step 8.
- Configuration stays out of the image: database URLs, secrets, `PORT`, and (Next.js) runtime environment
  variables are injected at run time. `NEXT_PUBLIC_*` values are inlined at **build** time, so a Next.js image is
  built per environment if those differ — flag that in Step 8 when the project uses any.
