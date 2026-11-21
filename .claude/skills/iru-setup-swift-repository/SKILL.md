---
name: iru-setup-swift-repository
description: "/iru-setup-swift-repository [args] bootstraps a Swift repository end to end, library (SwiftPM package) or app (XcodeGen/Tuist iOS/iPadOS/macOS/watchOS/visionOS): collects flavor, identity and the shared pipeline parameters once (with visibility-aware cost guidance, macOS minutes ~10x Linux), then runs iru-setup-swift-library or iru-setup-apple-app, iru-setup-antora, iru-setup-swift-gitignore, iru-setup-swift-github-workflows (skipped when ci: no), iru-setup-changelog and iru-setup-readme via iru-isolated-skill-executor. Announces from-scratch vs update with a component inventory; key: value args pre-resolve answers. Use to bootstrap or catch up a Swift repository in one pass."
model: sonnet
---

# Setup Swift Repository

Bootstrap a brand-new (or catch up an existing) Swift repository in one pass, for whichever of two flavors it is,
by orchestrating six existing skills: the flavor's own scaffold skill (`iru-setup-swift-library` for a SwiftPM
package, or `iru-setup-apple-app` for a native iOS/iPadOS/macOS/watchOS app driven by an XcodeGen `project.yml`
or a Tuist `Project.swift`), `iru-setup-antora` (Antora documentation site), `iru-setup-swift-gitignore` (root
`.gitignore`), `iru-setup-swift-github-workflows` (CI/CD + security workflows), `iru-setup-changelog` (root
`CHANGELOG.md`), and `iru-setup-readme` (root `README.md`). This skill does not duplicate any of their logic — it
collects the shared parameters once and passes each skill only the `args` keys that skill's own Step 0 actually
accepts, so the user isn't asked the same question repeatedly and no sub-skill sees a key it doesn't recognize.

Each of Steps 5–10 invokes its sub-skill via the `iru-isolated-skill-executor` agent (`subagent_type:
"iru-isolated-skill-executor"`, always naming the full `iru-`-prefixed skill inside the prompt, e.g.
`Skill({skill: "iru-setup-swift-library", args: "..."})`) rather than calling `Skill(...)` directly — every
sub-skill re-derives whatever it needs from the filesystem this orchestrator has just written to, so only a short
completion summary needs to flow back, keeping all six sub-skills' full transcripts (and the two scaffold skills'
very long template listings) out of this orchestrator's own context. Every sub-agent is launched with
`run_in_background: false` — each step depends on the previous one's files being on disk.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Resolve inputs

This skill can be invoked stand-alone (`/iru-setup-swift-repository`) or driven by another orchestrator (the
catalog's `iru-setup-repository` front door), so parse `args` first, as `key: value` lines, one per line, e.g.:

```
flavor: library
branching: gitflow
mode: new
platforms: ios, macos
open-source: yes
publish: yes
sonar: cloud
sonar-organization: example-org-github
sonar-project-key: example-org_my-example-lib
sonar-host-url: https://sonarcloud.io
integration-branch: develop
stable-branch: main
runner: macos-26
visibility: public
ci: yes
ci-tests: full
pages: yes
```

Recognized keys:

- **Flavor and mode**: `flavor` (`library`/`app`), `branching` (`gitflow`/`main-only`), `mode` (`new`/`existing`).
- **Shared pipeline vocabulary**: `open-source` (`yes`/`no`), `publish` (`yes`/`no` — `library` only),
  `distribution` (`none`/`internal`/`store` — `app` only), `sonar` (`cloud`/`self-hosted`/`none`) plus, only when
  set, `sonar-organization`/`sonar-project-key`/`sonar-host-url`, `integration-branch`, `stable-branch`, `runner`
  (`macos-26`/`xcode-27`), `xcode-version`, `release-please` (`yes`/`no` — `library` only), `visibility`
  (`public`/`private`/`internal` — `internal` behaves like `private` for every cost decision), `ci` (`yes`/`no` —
  whether GitHub Actions workflows are generated/updated at all), `ci-tests` (`unit`/`full` — only meaningful when
  `ci: yes`), `pages` (`yes`/`no` — only meaningful when `ci: yes`), `on-existing` (`ask`/`keep`/`update`/
  `replace` — how every component sub-skill treats prior setup it finds; never asked, and forwarded only when it
  arrived here via `args`), `accessibility` (`enforce`/`report`/`off`/`none` — `app` only: the level of the UI tests'
  automated accessibility checks, written by `iru-setup-apple-app` to `.ui-quality.yml`; ignored for `library`).
- **Flavor-specific inputs** named in Step 3: `platforms`, `min-deployment-targets` (`library`),
  `min-deployment-target` (`app`), `generator` (`xcodegen`/`tuist` — `app` only), `signing`
  (`fastlane-match`/`api-key`/`none` — `app` only), `ui` (`swiftui`/`uikit` — `app` only, the iOS/iPadOS target's UI
  framework) and `architecture` (`mvvm`/`mvc` — `app` only).
- **Identity fields** named in Step 2: `package-name` (`library`); `app-name`, `bundle-id-prefix` (`app`); and,
  for both, `license`, `developer-name`, `developer-email`, `organization-url`.
- **Pass-through toggles**, forwarded untouched if supplied but never asked by this orchestrator (see Steps 2, 7
  and 8): `formatter` (`swift-format`/`swiftformat` — `library` only), `package-resolved` (`commit`/`ignore`),
  and the four `security-*` opt-outs (`security-dependency-review`, `security-codeql`, `security-osv`,
  `security-gitleaks`).

Every key found here is resolved — skip the matching question in Steps 1–4. Only keys genuinely missing from
`args` still need asking. If `args` is absent or doesn't look like this format, treat everything as unset and ask
normally.

## Step 1 — Resolve the flavor, branching model and mode

Survey the repository root first — `Package.swift`, `project.yml` (XcodeGen), `Project.swift` (Tuist), and any
`*.xcodeproj`/`*.xcworkspace` — since their presence decides both `flavor` and `mode` without a question in the
common cases.

Unless `flavor` was already supplied via `args`, resolve it:

- `project.yml` or `Project.swift` exists at the root → `app` (an app repository also carries a
  `Packages/Core/Package.swift`, and may carry its own root `Package.swift` — the generator manifest wins, the
  same disambiguation `iru-setup-swift-github-workflows`'s Step 1 and `iru-swift-bump-version`'s Step 2 use).
  A root `Package.swift` with **no** generator manifest → `library`. Confirm the detected value with the user
  rather than assuming it silently.
- Only a hand-maintained `*.xcodeproj`/`*.xcworkspace` exists, with neither `project.yml` nor `Project.swift`:
  this predates any generator — `flavor` is `app`, but tell the user up front that `iru-setup-apple-app` will ask
  whether to adopt XcodeGen/Tuist going forward or stop, and that this orchestrator can't reverse-engineer the
  project for them.
- Nothing on disk yet, or genuinely ambiguous → `AskUserQuestion` with two options: **Library** (a redistributable
  SwiftPM package consumed via a tagged git URL and optionally listed on the Swift Package Index — no app target)
  / **App** (a native iOS/iPadOS/macOS/watchOS/visionOS application — SwiftUI, or UIKit for the iOS/iPadOS target —
  whose Xcode project is generated on demand
  from an XcodeGen or Tuist manifest, distributed via TestFlight/the App Store, never published as a package).

Map the resolved `flavor` to its scaffold skill for the rest of this run:

| `flavor` | Scaffold skill | Scaffold manifest checked in Step 5 |
|---|---|---|
| `library` | `iru-setup-swift-library` | `Package.swift` |
| `app` | `iru-setup-apple-app` | `project.yml` (XcodeGen) or `Project.swift` (Tuist) |

Unless `branching` was already supplied via `args`, resolve it with `AskUserQuestion` (two options):

- **gitflow** (recommended, this catalog's house pattern) — an integration branch (`develop`) plus a stable
  branch (`main`, releases only); `iru-setup-swift-github-workflows` generates `build.yml`/`release.yml`/
  `sync.yml` (+ `.github/scripts/sync_versions.py`), and `sync.yml` opens the post-release version-bump PR back
  into the integration branch.
- **main-only** — a single stable branch; `iru-setup-swift-github-workflows` generates `build.yml`/`release.yml`
  and no `sync.yml`. Step 4's integration-branch question is skipped for this model.

If `mode` wasn't supplied via `args`, don't ask about it as an isolated question — check whether the resolved
flavor's scaffold manifest (`Package.swift` for `library`; `project.yml` or `Project.swift` for `app`) already
exists at the repository root: if it does, default to `existing` and confirm with `AskUserQuestion` (`existing`
pre-selected; `new` as the other option); if it doesn't, default to `new` without asking further. Either way,
`mode` no longer decides what happens to individual files: **any** owned artifact already on disk (not just the
build manifest) makes the run an update *for that component*, and the sub-skill that owns it warns and asks
Update / Keep as is / Replace for it (unless `on-existing` says otherwise).

### Setup mode announcement

Right after `mode` is resolved — before Step 2's questions — print a short **"Setup mode"** statement:

- `mode: new` with nothing owned on disk → "Setting up this repository **from scratch** — no prior setup found."
- Otherwise → "**Updating an existing repository** — prior setup found:" followed by an inventory table of every
  component this run owns, each marked **absent / present / partial** with the evidence (file paths):

  | Component | Evidence checked |
  |---|---|
  | Scaffold manifest | `Package.swift` (library) / `project.yml` or `Project.swift` + `Tuist.swift` (app) |
  | Source layout | `Sources/<Name>/` + `Tests/<Name>Tests/` (library) / `Packages/Core/` + `Targets/<Platform>/` (app) |
  | Lint/format config | `.swiftlint.yml`, `.swift-format`/`.swiftformat` |
  | UI quality settings (`app`) | `.ui-quality.yml` |
  | Sonar config | `sonar-project.properties` |
  | Publishing/distribution config | `.spi.yml` (library) / `fastlane/Fastfile` (app) |
  | Antora docs | `docs/antora.yml`, `docs/antora-playbook.yml`, `docs/modules/ROOT/` |
  | `.gitignore` | root `.gitignore` |
  | Workflows | each of `.github/workflows/build.yml`, `release.yml`, `sync.yml` (+ `.github/scripts/sync_versions.py`), `security.yml` |
  | Dependabot | `.github/dependabot.yml` |
  | `CHANGELOG.md` / `README.md` / `LICENSE` | each at the root |

  Mark a component **partial** when only some of its files exist (e.g. `Package.swift` without `Sources/`, or
  `build.yml` without `security.yml`). Then: "For each component that already exists you'll be asked whether to
  keep it, update it, or replace it." (or, when `on-existing` arrived via `args`: "…each existing component will
  be handled with `on-existing: <value>` without asking.")

## Step 2 — Collect the project's identity

**`mode: existing` with the scaffold manifest already on disk** (`Package.swift` (or the flavor's generator
manifest) at the repository root): skip every identity question in this step — the scaffold sub-skill reads
identity from the existing manifest itself (its Update and Keep paths need nothing new, and its Replace path
offers the existing manifest's values as defaults and asks for anything missing), and `iru-setup-readme` derives
identity from the existing build files, so asking them here is a wasted question (verified against an existing
repository). Resolve `license` only if `args` supplied it, and record in the final report that project identity
was taken from the existing files rather than asked.

For any field Step 0 already resolved from `args`, use that value directly. For everything else, ask the user
directly (plain conversation — these are free-text project-identity fields, not a bounded choice). Only ask what
applies to the resolved `flavor`, using **exactly** the key names the scaffold skill reads:

- **`library`**: **package-name** — the SwiftPM package identity written to `Package(name: ...)`, e.g.
  `my-example-lib` (commonly the repository name, hyphens allowed). Don't ask for the PascalCase `<Name>` used for
  the product/target/directories — `iru-setup-swift-library` derives it itself (`my-example-lib` →
  `MyExampleLib`) and warns against passing the raw hyphenated name to `swift package init`.
- **`app`**: **app-name** — the display name, e.g. `My App` (`iru-setup-apple-app` derives the app-slug and
  directory names from it); **bundle-id-prefix** — reverse-DNS, e.g. `com.example` (the app's bundle id is
  `<bundle-id-prefix>.<app-slug>`; the watchOS companion's is `<bundle-id>.watchkitapp`, never asked).
- **Both**: **developer name**, **developer email**, **organizationUrl**. For a library they only appear in
  generated doc-comment headers (SwiftPM has no `<developers>`/`author` metadata block); for an app they populate
  each Info.plist's `NSHumanReadableCopyright` — still worth collecting once here so `iru-setup-readme` and any
  later `iru-check-license` run see consistent authorship.

Then resolve the **license** with `AskUserQuestion`, unless `args` supplied one (one consistent choice reused
across both flavors, matching the four options every other setup skill in this catalog offers; the two scaffold
skills merely differ on which they recommend first — Apache for a library, MIT for an app — so present the
recommendation that matches the resolved `flavor`):

- Apache License 2.0 (recommended for `library`) — `Apache License 2.0` / `http://www.apache.org/licenses/LICENSE-2.0.txt`
- MIT License (recommended for `app`) — `The MIT License` / `https://opensource.org/license/mit`
- No license (proprietary / all rights reserved)
- Other — ask for the license's display name and URL directly afterward

The library's **`formatter`** toggle (`swift-format`, the toolchain's bundled formatter, or the third-party
`swiftformat`) is **deliberately left unasked here**: nothing downstream needs it as an input
(`iru-setup-swift-github-workflows` always writes a `swift format lint --strict` step and accepts no `formatter`
key), so `iru-setup-swift-library` asks it itself with its `swift-format` default when invoked without it. Pass it
through only if `args` supplied it — and if it resolves to `swiftformat`, flag in Step 11 that the generated
`build.yml`'s lint step still invokes `swift format` and needs a hand edit.

## Step 3 — Collect the flavor-specific inputs

Only ask what applies to the resolved `flavor`. For any field Step 0 already resolved from `args`, use that value
directly.

- **platforms** (both flavors, `AskUserQuestion` multi-select, at least one required) — but with each scaffold's
  own vocabulary, since the two skills read different lists:
  - `library`: `ios`, `ipados`, `macos`, `watchos`, `tvos`, `visionos`, `linux` (seven values — split across two
    questions, Apple platforms first, then *tvOS / visionOS / Linux / none of these*, since `AskUserQuestion`
    allows at most four options). Relay `iru-setup-swift-library`'s own caveats when presenting them: `ios` and
    `ipados` collapse to a single `.iOS(...)` entry, and `linux` adds no `platforms:` entry at all (SwiftPM has no
    `.linux` case — Linux support is implicit whenever the code avoids Apple-only imports).
    **When `platforms` arrived via `args` for the `library` flavor, still ask the second round.** A front door
    such as `iru-setup-repository` collects platforms with the four-option Apple vocabulary shared with the app
    flavor (`ios`/`ipados`/`macos`/`watchos`), so a supplied value covers at most those four — treat it as
    resolving only the first question, and ask the *tvOS / visionOS / Linux / none of these* multi-select as a
    follow-up that **extends** the supplied list (skip the follow-up only when the supplied value already names
    at least one of `tvos`/`visionos`/`linux`, since then the caller evidently spoke the full vocabulary). The
    merged list is what Steps 5 and 8 receive.
  - `app`: `ios`, `ipados`, `macos`, `watchos`, `visionos` (tvOS is not offered — `iru-setup-apple-app` refuses it).
    Ask the first four in one multi-select and *visionOS / none of these* as a follow-up, since `AskUserQuestion`
    allows at most four options. When `platforms` arrived via `args`, don't ask either question — the caller
    (e.g. `iru-setup-repository`, which asks its own visionOS follow-up) already decided, and re-asking would
    double-ask a user who answered "no". Relay
    `iru-setup-apple-app`'s caveats: `ipados` is not a separate target — it widens the iOS target's
    `TARGETED_DEVICE_FAMILY` —, `watchos` without `ios` produces a standalone (companion-less) watch app, visionOS
    builds are verified anywhere but its tests need a visionOS simulator runtime, and a **macOS-only** app is better
    served by `iru-setup-macos-repository` (AppKit, MVC, Developer ID/DMG distribution) — offer to switch when
    `macos` is the only platform the user selected here. Don't offer it when `platforms` arrived via `args`: the
    caller (e.g. `iru-setup-repository`, which routes a macOS desktop app to `iru-setup-macos-repository` itself)
    already chose this orchestrator for that list.
- **`app` only — ui** (only when `ios` or `ipados` is selected; skip when resolved from `args`). **`mode: existing`
  with the iOS target's sources on disk: detect, don't ask fresh** — `Targets/iOS/Sources/SceneDelegate.swift`, or a
  `UIWindowSceneDelegate`/`UIApplicationDelegate` type under `Targets/iOS/Sources/` with no SwiftUI `@main struct … :
  App`, → `uikit`; an `@main struct … : App` → `swiftui` — and confirm the detected value with the user (one
  `AskUserQuestion`, the detected value first and marked "(Detected)"); if nothing decides it, omit `ui` from Step 5's
  `args` so `iru-setup-apple-app`'s own Step 1 detects it. Otherwise (`mode: new`, or no iOS sources yet) —
  `AskUserQuestion`: **SwiftUI (Recommended)** (`swiftui`) / **UIKit** (`uikit` — programmatic UIKit with a scene
  delegate and a `UINavigationController` root, no storyboards). watchOS, visionOS and a macOS target alongside other
  platforms are always SwiftUI — say so when they're selected.
- **`app` only — architecture** (skip when resolved from `args`). **`mode: existing`: detect, don't ask fresh** —
  `Packages/Core/Sources/Core/Controllers/` present → `mvc` (a `ViewModels/` beside it is the scaffolded UIKit-MVC +
  SwiftUI-platforms mix, still `mvc`); only `Packages/Core/Sources/Core/ViewModels/` → `mvvm` — confirm it the same
  way as `ui`, or, when neither folder exists (an older scaffold with a flat placeholder), omit `architecture` from
  Step 5's `args` and let `iru-setup-apple-app`'s Step 1 resolve it. Otherwise: `ui: swiftui` → `mvvm` without asking (SwiftUI has
  no controllers; say why MVC isn't offered); `ui: uikit` → `AskUserQuestion`: **MVC (Recommended)** (`mvc` — Cocoa
  Touch's own pattern: a UI-free controller in `Packages/Core` behind a passive-view protocol, the `UIViewController`
  as its view) / **MVVM** (`mvvm` — `@Observable` view models in `Packages/Core`, bound through UIKit's Observation
  tracking; the scaffold adds the `UIObservationTrackingEnabled` opt-in below iOS 26).
  The same list is passed verbatim to Step 5's scaffold and Step 8's workflows skill, so `Package.swift`'s
  `platforms:` array / the generator manifest's targets and CI's per-platform test destinations always agree.
- **`library` only — min-deployment-targets**: for each selected Apple platform (`ios`/`ipados` counted once,
  `macos`, `watchos`, `tvos`, `visionos`), the minimum OS version, in `iru-setup-swift-library`'s own
  `ios=17.0, macos=14.0` form. There is no universal default — ask directly, offering the current stable major
  minus one per platform as the suggestion if the user has no preference. Never asked for a `linux`-only
  selection. When the platforms follow-up above extended an `args`-supplied list with `tvos`/`visionos`, ask
  the minimum version for just those added platforms and append them to whatever `min-deployment-targets`
  `args` already carried.
- **`app` only — min-deployment-target**: the **iOS** deployment target (e.g. `26.0` or `18.0`), which
  `iru-setup-apple-app` maps **per platform** (its Step 5 table) — identical numbers from 26 on, while `18.0` →
  iOS/iPadOS 18.0, macOS 15, watchOS 11, visionOS 2 — not one value written verbatim for every platform. **Deliberately left unasked** unless `args` supplied it: `iru-setup-apple-app` resolves its default itself from the locally
  installed simulator runtimes (Step 4 there — the bare current SDK major is *not* safe, since installed
  simulator runtimes lag it by one), which this orchestrator can't do better. Pass it through only if supplied.
- **`app` only — generator** (`AskUserQuestion`, two options): **XcodeGen** (recommended for a single-target or
  single-platform-family app — a thin, fast, single-file `project.yml`) / **Tuist** (for a modular app that has or
  will grow multiple internal SwiftPM packages beyond the scaffolded `Packages/Core`, whose module graph and
  caching pay off as packages accumulate). Collected here, not left to the scaffold, because
  `iru-setup-swift-github-workflows` also needs it (Step 8) to write the matching `xcodegen generate`/`tuist
  generate` step — even though that skill could detect it from the manifest afterward, passing it keeps both
  halves in agreement by construction.
- **`app` only — signing**: resolved in Step 4 right after `distribution`, since its recommended default depends
  on that answer.

## Step 4 — Collect the shared pipeline parameters

Ask the user directly, presenting each with its default so a plain "yes"/blank reply accepts it (skip any Step 0
already resolved from `args`, and skip the integration branch entirely when `branching: main-only`):

| Parameter | `args` key | Default | Consumed by |
|---|---|---|---|
| Integration branch | `integration-branch` | `develop` | `iru-setup-swift-github-workflows` (gitflow only) |
| Stable branch | `stable-branch` | `main` | `iru-setup-swift-github-workflows` |
| macOS runner label | `runner` | `macos-26` | `iru-setup-swift-github-workflows` (`runs-on:`) |

Notes on these, so the two halves of the pipeline can't disagree:

- **`runner`** is a CI-only parameter: `macos-26` is GitHub's stable, generally-available image (Xcode up to 26.6);
  `xcode-27` is the newer-Xcode public-preview image label (Xcode 27.x). `iru-setup-apple-app`'s templates were
  verified on Xcode 27.1, so for the `app` flavor offer `xcode-27` (with `xcode-version: 27.1`) next to `macos-26` and
  state the trade-off — GA stability vs. the Xcode the scaffold was verified with; a preview image can change with
  less notice. Neither arm64 image preinstalls SwiftLint (only the Intel `macos-26-intel` image lists it, per the
  October 2026 runner-images READMEs) — the workflows skill runs SwiftLint in a Linux job instead. Both arm64 images
  ship a visionOS simulator runtime, so selected `visionos` targets are tested in CI. `iru-setup-swift-github-workflows` looks
  up the currently available labels at run time — this orchestrator just forwards the choice.
- **`xcode-version`** is *not* asked here: `iru-setup-swift-github-workflows` resolves its default from the
  chosen runner image's own `(default)`-marked Xcode row at run time. Pass it through only if `args` supplied it.
- **`release-please`** (`library` only) is *not* asked here: it defaults to `no` inside
  `iru-setup-swift-github-workflows` because this catalog already has a hand-driven release path
  (`iru-release`/`iru-swift-bump-version`). Pass it through only if `args` supplied it.

Then resolve, with `AskUserQuestion`, per this catalog's shared open-source-driven convention (skip any Step 0
already resolved):

- **open-source** — is this repository open source? Yes / No. Drives the recommended defaults below.
- **visibility** — the GitHub repository's visibility, resolved right after `open-source`: if `args` supplied it,
  use it; else, if the repository has a GitHub remote, run `gh repo view --json visibility -q .visibility`
  (returns `PUBLIC`/`PRIVATE`/`INTERNAL` — lowercase it) and tell the user the detected value; else (no remote yet,
  `gh` unavailable, not GitHub) ask **Public** / **Private**, recommending **Public** when open-source is Yes,
  otherwise **Private**. If open-source is Yes but `visibility` is `private`/`internal`, warn that the two
  contradict (an open-source project's repository is normally public; a private repository forfeits the
  free-for-public-repos pricing below) and ask which one is right. `internal` behaves like `private` from here on.
- **publish** — **`library` only**: does this package get distributed for other projects to depend on (a tagged
  git URL, optionally listed on the Swift Package Index via `.spi.yml`)? Default **Yes** when open-source is Yes;
  otherwise ask with **No** recommended (the Swift Package Index is a public catalog for redistributable
  open-source packages). Say plainly that `no` only omits `.spi.yml` — SwiftPM has no registry publish step to
  gate, so the workflows skill treats `publish` as informational. Never collected or passed for `app`.
- **distribution** — **`app` only**: `none`/`internal`/`store`. Default **`store`** (TestFlight + App Store
  submission lanes) when open-source is Yes; otherwise ask with **`internal`** (TestFlight internal testing only)
  recommended, `none` (local install/manual QA only) as the alternative. Never collected or passed for `library`.
- **signing** — **`app` only**, asked with the `distribution` answer in view (`AskUserQuestion`, three options):
  **`fastlane-match`** (team-shared certificates/profiles in an encrypted private git repo — recommended when more
  than one person/machine produces signed builds; needs `MATCH_GIT_URL`/`MATCH_PASSWORD`), **`api-key`** (Xcode
  automatic signing authenticated by an App Store Connect API key — recommended for a single maintainer or a
  CI-only setup; needs `APP_STORE_CONNECT_API_KEY_ID`/`_ISSUER_ID`/`_KEY_BASE64`), **`none`** (no automated
  signing; only a `test` lane). When `distribution: none`, offer `none` as the default and confirm rather than
  assuming — a signed local archive is still a valid, if unusual, wish. Warn that `signing: none` with a
  non-`none` `distribution` means nothing will produce a distributable build automatically.
- **ci** — "Generate GitHub Actions workflows (CI/CD) for this repository?" State the cost facts per GitHub's
  current pricing (and tell the user to double-check their plan): standard GitHub-hosted runners are **free for
  public repositories**; private/internal repositories consume the account's included minutes — **2,000/month on
  GitHub Free, 3,000 on Pro and Team, 50,000 on Enterprise Cloud** — then are billed per minute. **Stress that
  this stack's CI runs almost entirely on macOS runners, whose minutes cost roughly 10× Linux minutes** ($0.062 vs
  $0.006 per minute) and count ~10× against the included budget: every `build.yml` run (Xcode build, tests,
  SwiftLint, DocC) and every `release.yml` archive/sign/notarize run is macOS — only the library's optional Linux
  leg avoids it — so on a private repository **a few dozen builds a month can exhaust 2,000 included minutes**.
  Public: recommend **Yes** — standard runners are free. Private/internal: still offer **Yes** first (noting
  `ci-tests: unit` below as the main cost lever) and offer **No** as "no workflows — build, test, and release
  locally (re-run `iru-setup-swift-github-workflows` later to add them)". With `ci: no`, Step 8 is skipped entirely
  (no `.github/workflows/*`, no `security.yml`, no `.github/dependabot.yml`, no `sync_versions.py`), and
  `ci-tests`/`pages` are not asked.
- **sonar** — "Configure SonarCloud / SonarQube and send CI analysis and coverage reports to it?" —
  `cloud`/`self-hosted`/`none`. Recommend **`cloud`** only when the repository is public **and** open source;
  otherwise state that SonarCloud (SonarQube Cloud) is free for public/open-source projects but a private project
  needs a paid plan, and recommend **`none`**, offering **self-hosted SonarQube** (Community Build is free, but you
  run the server) as the second option. When `ci: no`, say plainly that nothing would send reports automatically —
  analysis would only run when someone runs the scanner manually with a token — and recommend `none`; if the user
  still picks `cloud`/`self-hosted`, the scaffold writes `sonar-project.properties` only and Step 11 reports that
  no CI step sends reports. When `cloud`/`self-hosted` is chosen, also
  collect `sonar-organization`/`sonar-project-key`/`sonar-host-url` the same way both scaffold skills' own Step 2
  does (SonarCloud: suggest `<owner>-github` / `<owner>_<repo>` / `https://sonarcloud.io`, inferred from
  `git remote get-url origin`; self-hosted: ask for `sonar.host.url` directly, plus `sonar.organization` only if
  that server has organizations enabled, and `sonar.projectKey`).
- **ci-tests** (only when `ci: yes`) — "Which tests should CI run?" **Unit tests only** — "unit-test targets only
  (cheaper: fewer macOS minutes, faster feedback); the app's XCUITest UI-test bundle and any library
  integration-test target stay local" (recommended for private/internal) / **Unit + UI tests** — "unit tests plus
  XCUITest UI tests on every selected simulator/platform destination (complete verification, uses considerably more
  macOS minutes — each UI-test run boots a simulator and launches the app)" (recommended for public).
- **accessibility** (`app` only; skip for `library`) — "Support accessibility and assistive technologies?" —
  **Enforce (Recommended)** (`enforce`: every `performAccessibilityAudit()` issue fails the UI tests) / **Report
  only** (`report`: issues listed, never failing) / **Off** (`off`: the audit isn't run; labels, contrast and Dynamic
  Type are still coded). **None — opt out entirely** ("no accessibility rules, checks or docs"; written as `accessibility: none`). In `mode: existing`, pre-select from `iru-explore`'s `Accessibility:` line per `iru-setup-repository`'s `reference/accessibility-detection.md` (`present` → Enforce, `partial` → Report only with a mixed-compliance warning, `absent` → None) and never silently flip an existing `.ui-quality.yml` value. Written by the scaffold to `.ui-quality.yml`, read by the UI tests and the Swift coding/test
  skills.
- **pages** (only when `ci: yes`; the DocC + Antora site) — public: **`yes`** without asking (state it in Step
  11). Private/internal: ask — **No (Recommended)** — "CI still builds the docs and uploads them as a workflow
  artifact; nothing is published" / **Yes** — "requires GitHub Pro, Team, or Enterprise Cloud (not GitHub Free);
  the site will be PUBLIC on the internet unless you're on Enterprise Cloud with private Pages access control".

These values (`open-source`, `publish`/`distribution`, `signing`, `sonar` + detail keys, plus `visibility`/`ci`
for the scaffold and `visibility`/`ci-tests`/`pages` for the workflows) are passed to Step 5 (the scaffold skill)
and Step 8 (`iru-setup-swift-github-workflows`) so neither sub-skill re-asks them — and so the workflows never contain a Sonar scan step for a repository with no
`sonar-project.properties`, or a TestFlight upload for a `fastlane/Fastfile` with no `beta` lane.

## Step 5 — Run the flavor's scaffold skill

Always invoke the scaffold skill — even in `mode: existing` with the scaffold manifest on disk. It owns the
question of what to do with its pre-existing files: it announces creating vs updating, then warns and asks Update /
Keep as is / Replace (/ Stop) per existing file (or applies `on-existing`), where **Keep** is the old "leave the
existing scaffold untouched" behaviour. This orchestrator therefore never asks that question itself, so it is
never asked twice. Format Steps 2–4's answers as `key: value` lines using **exactly** the keys the resolved
flavor's skill accepts (never an invented key) and invoke via `iru-isolated-skill-executor`:

```
# flavor: library
Agent({
  description: "Run iru-setup-swift-library",
  subagent_type: "iru-isolated-skill-executor",
  prompt: "Invoke Skill({skill: \"iru-setup-swift-library\", args: \"package-name: <package-name>\\n
    platforms: <platforms, comma-separated>\\nmin-deployment-targets: <ios=17.0, macos=14.0 …, omit if linux-only>\\n
    license: <license display name, or 'none'>\\ndeveloper-name: <developer-name>\\n
    developer-email: <developer-email>\\norganization-url: <organization-url>\\nopen-source: <open-source>\\n
    publish: <publish>\\nsonar: <sonar>\\nsonar-organization: <..., if set>\\nsonar-project-key: <..., if set>\\n
    sonar-host-url: <..., if set>\\nformatter: <..., only if supplied>\\nvisibility: <visibility>\\nci: <ci>\\n
    on-existing: <..., only if supplied>\\nmode: <mode>\"}).
    Report back: whether it ran from scratch or as an update, and for each of Package.swift, Sources/<Name>/,
    Tests/<Name>Tests/, .swiftlint.yml, the formatter config, the DocC catalog, .spi.yml and
    sonar-project.properties whether it was created, updated, replaced or kept as is (with any mismatch it listed
    for a kept file), or whether the user chose to stop, the PascalCase <Name> it derived, which formatter it resolved,
    whether the swift-docc-plugin version came from a live lookup versus the recorded fallback, whether its
    swift build / swift test verification passed, and any value it resolved on its own (repository URL,
    inception year).",
  run_in_background: false
})
```

```
# flavor: app
Agent({
  description: "Run iru-setup-apple-app",
  subagent_type: "iru-isolated-skill-executor",
  prompt: "Invoke Skill({skill: \"iru-setup-apple-app\", args: \"app-name: <app-name>\\n
    bundle-id-prefix: <bundle-id-prefix>\\nplatforms: <platforms, comma-separated>\\ngenerator: <generator>\\n
    ui: <ui, when resolved>\\narchitecture: <architecture>\\n
    min-deployment-target: <..., only if supplied>\\ndistribution: <distribution>\\nsigning: <signing>\\n
    license: <license display name, or 'none'>\\ndeveloper-name: <developer-name>\\n
    developer-email: <developer-email>\\norganization-url: <organization-url>\\nopen-source: <open-source>\\n
    sonar: <sonar>\\nsonar-organization: <..., if set>\\nsonar-project-key: <..., if set>\\n
    sonar-host-url: <..., if set>\\nvisibility: <visibility>\\nci: <ci>\\naccessibility: <accessibility>\\n
    on-existing: <..., only if supplied>\\nmode: <mode>\"}).
    Report back: whether it ran from scratch or as an update, and for each of the generator manifest (project.yml,
    or Project.swift + Tuist.swift), Packages/Core, the per-platform Targets/, .swiftlint.yml, .swift-format,
    .ui-quality.yml, fastlane/Fastfile and sonar-project.properties whether it was created, updated, replaced or kept as is (with
    any mismatch it listed for a kept file), or whether the user chose to stop or to adopt a generator over a
    hand-maintained .xcodeproj, the bundle id and min-deployment-target it resolved, which
    Fastfile lanes it wrote as a result of distribution/signing, which generator/tool versions came from a live
    lookup versus the recorded fallback, whether its Packages/Core swift build / swift test verification passed,
    and whether xcodegen/tuist was actually available to run generate locally.",
  run_in_background: false
})
```

Omit every `only if supplied` line whose value wasn't resolved (never send an empty key). Neither scaffold skill
accepts `branching`, `integration-branch`, `stable-branch`, `runner`, `xcode-version`, `release-please`,
`package-resolved`, `ci-tests` or `pages` — those are Steps 7–8's alone, so never send them here. `visibility` and
`ci` are always sent (both scaffolds use them only to shape the Sonar recommendation and the `ci: no` Sonar note);
`on-existing` only when it arrived via this skill's own `args`. `publish` and `formatter` are sent
only for `library`; `generator`, `signing`, `distribution`, `min-deployment-target` and `accessibility` only for
`app`.

Each scaffold skill parses these itself (its own Step 0) and only asks the user about anything genuinely left
out — `AskUserQuestion` surfaces to the user the same way whether invoked directly or from inside this
sub-agent. If it reports that the user chose **Stop** and no scaffold manifest exists on disk, stop this skill
here too — there's no coherent package or project to build Antora docs or CI workflows around yet. If the user
chose Stop but the manifest already exists, treat the scaffold as **kept as is** and continue to Step 6 (the
existing scaffold is presumed valid enough for the rest of the pipeline to build against), noting it in Step 11. For `app`, warn the user
before launching it that `xcodegen`/`tuist` are typically installed via `brew`, and that the scaffold verifies
only the shared `Packages/Core` package with `swift build`/`swift test` when the generator isn't available
locally — a real `.xcodeproj` build is then first exercised in CI.

## Step 6 — Run `iru-setup-antora`

Invoke via `iru-isolated-skill-executor`: `Agent({description: "Run iru-setup-antora", subagent_type:
"iru-isolated-skill-executor", prompt: "Invoke Skill({skill: \"iru-setup-antora\"}) with no args — or, only when `on-existing` arrived via
this skill's own `args`, with args \"on-existing: <on-existing>\" (the only key it accepts) — deriving the Antora
component name, title, and version itself from the repository (the name: in Package.swift or in the
XcodeGen/Tuist manifest, a README heading if one exists, git tags/GitHub releases). Report back: whether it ran
from scratch or as an update, whether the existing site was updated (gaps filled), kept as is, or had
antora.yml + the playbook replaced, which files/pages were created vs. already present, and whether the site build
succeeded.", run_in_background: false})`. When the site already exists, `iru-setup-antora` itself warns and asks
Update / Keep / Replace — this orchestrator doesn't ask that question.

Run this *before* Step 8, not after: `iru-setup-antora` is quick to detect as "already done", and
`iru-setup-swift-github-workflows`'s own survey (Step 1 there) checks whether `docs/antora.yml`/
`docs/antora-playbook.yml` exist before generating its DocC + Antora Pages deploy job — running Antora setup
first means that check finds everything in place instead of flagging a gap. In a repository with no tags or
releases yet, `iru-setup-antora` may ask for a starting version; answer `1.0.0` (the version
`iru-swift-bump-version`/`sync.yml` will later manage) rather than inventing another.

## Step 7 — Run `iru-setup-swift-gitignore`

Invoke via `iru-isolated-skill-executor`: `Agent({description: "Run iru-setup-swift-gitignore", subagent_type:
"iru-isolated-skill-executor", prompt: "Invoke Skill({skill: \"iru-setup-swift-gitignore\", args: \"flavor:
<flavor>\\npackage-resolved: <..., only if supplied>\\non-existing: <..., only if supplied>\"}) — it detects mode
itself from whether a root .gitignore already exists, and detects the generator manifest, fastlane/, the Antora
config and the DocC catalog directly from disk. Report back only whether .gitignore was created, updated,
replaced or kept as is (and whether that choice came from the user or from on-existing), and which
package-resolved choice was applied.",
run_in_background: false})`.

`flavor` is passed so the skill's `Package.resolved` question (commit it for an app, ignore it for a library)
carries the right recommendation without re-detecting the project shape; **`package-resolved` itself is
deliberately left for it to ask** unless `args` supplied it — it's a one-time, single question with a
flavor-informed recommendation, and nothing else in this pipeline depends on the answer. `mode` is deliberately
*not* passed: the skill's own `.gitignore`-presence check is the more precise signal for this file (a `mode: new`
repository can still carry a hand-written `.gitignore` that deserves the diff-and-approve path, never a silent
overwrite). Run this after Steps 5–6, not before: detection depends on the scaffold's `project.yml`/
`Project.swift`/`fastlane/` signals and on the Antora config Step 6 just wrote. Run it *before* Step 8, so
`.build/`, `DerivedData/`, `*.xcresult`, the generated `*.xcodeproj`/`*.xcworkspace`, signing material and the
Antora build output are already ignored before CI-generated reports show up locally.

## Step 8 — Run `iru-setup-swift-github-workflows`

**`ci: no` ⇒ skip this step entirely** — don't invoke the sub-skill at all: no `.github/workflows/*`, no
`security.yml`, no `.github/dependabot.yml`, no `.github/scripts/sync_versions.py`. Record it in Step 11 as
**skipped (ci: no)**, and say there that the shared security block (Dependabot, dependency-review, CodeQL,
OSV-Scanner, gitleaks) was skipped with it and can be added later by running `/iru-setup-swift-github-workflows`
on its own. If workflow files already exist on disk with `ci: no`, leave them untouched and list them in Step 11
(they keep running on GitHub until removed by hand).

Otherwise, format Steps 1–4's answers as `key: value` lines and invoke via `iru-isolated-skill-executor`:

```
Agent({
  description: "Run iru-setup-swift-github-workflows",
  subagent_type: "iru-isolated-skill-executor",
  prompt: "Invoke Skill({skill: \"iru-setup-swift-github-workflows\", args: \"flavor: <flavor>\\n
    platforms: <platforms, comma-separated>\\nbranching: <branching>\\n
    integration-branch: <integration-branch, gitflow only>\\nstable-branch: <stable-branch>\\nrunner: <runner>\\n
    xcode-version: <..., only if supplied>\\ngenerator: <generator, app only>\\nsigning: <signing, app only>\\n
    release-please: <..., library only, only if supplied>\\nopen-source: <open-source>\\n
    publish: <publish, library only>\\ndistribution: <distribution, app only>\\nsonar: <sonar>\\n
    sonar-organization: <..., if set>\\nsonar-project-key: <..., if set>\\nsonar-host-url: <..., if set>\\n
    security-dependency-review: <..., only if supplied>\\nsecurity-codeql: <..., only if supplied>\\n
    security-osv: <..., only if supplied>\\nsecurity-gitleaks: <..., only if supplied>\\n
    visibility: <visibility>\\nci-tests: <ci-tests>\\npages: <pages>\\non-existing: <..., only if supplied>\\n
    mode: <mode>\"}). Report back: whether it ran from scratch or as an update, and for each of build.yml,
    release.yml, sync.yml + .github/scripts/sync_versions.py (gitflow only), security.yml, .github/dependabot.yml
    and .github/scripts/lcov_to_sonar_generic.py (either flavor, sonar only) whether it was created, updated, replaced or kept as is
    (with every mismatch it listed for a kept file), or whether the user chose to stop; how ci-tests and pages
    were applied (UI-test bundles skipped or not, Pages deploy vs. docs artifact); the runner label and
    xcode-version it resolved, any open gap it flagged (missing docs/antora.yml, a Packages/Core without the
    swift-docc-plugin dependency, README/version wording not matching sync_versions.py, an APPLE_TEAM_ID secret
    still to add), and the full required-secrets/environments table it produced for this flavor/branching/options.",
  run_in_background: false
})
```

Omit `integration-branch` when `branching: main-only`, `publish`/`release-please` for `app`, and `generator`/
`signing`/`distribution` for `library` (never send a key the flavor doesn't use). `visibility`, `ci-tests` and
`pages` are always sent here (so the workflows skill never re-asks them); `ci` itself is never sent — invoking
the workflows skill already means CI is wanted. `on-existing` is sent only when it arrived via this skill's own
`args`. The four `security-*` keys are
forwarded only when `args` supplied them (a `security-codeql: no` given to this orchestrator must reach the
workflows skill, or the CodeQL job is generated anyway) — each defaults to `yes` inside
`iru-setup-swift-github-workflows` itself, and this orchestrator never asks about them; note this once in
Step 11's report rather than re-asking per run. The shared `license`/`developer-*`/`organization-url` keys are
likewise never passed here: the workflows skill accepts them only to tolerate a full shared-args set and would
report them as "supplied but unused".

If it reports that the workflow files already existed and the user chose Stop, note that in Step 11's report
rather than treating it as a failure of this skill — the scaffold, the Antora docs, and the `.gitignore` from
Steps 5–7 are still valid on their own.

## Step 9 — Run `iru-setup-changelog`

Invoke via `iru-isolated-skill-executor`: `Agent({description: "Run iru-setup-changelog", subagent_type:
"iru-isolated-skill-executor", prompt: "Invoke Skill({skill: \"iru-setup-changelog\"}) with no args — or, only when
`on-existing` arrived via this skill's own `args`, with args \"on-existing: <on-existing>\" (the only key it
accepts) — working entirely from the repository's own git tag/GitHub Release history. Report back only whether
CHANGELOG.md was created (and the version range reconstructed), updated (which missing releases were appended),
replaced, or kept as is.", run_in_background: false})`.
Position relative to Steps 5–8 doesn't matter functionally — it's independent of all of them. In `mode: new`
(no tags yet) it either bootstraps a minimal `## [Unreleased]`-only file or stops per the user's choice; in
`mode: existing`, real tag/release history may exist to backfill. If `CHANGELOG.md` already exists, it warns
and asks Keep as is (its old skip behaviour) / Update (append only missing releases) / Replace (rebuild from
history) regardless of `mode` — whichever is chosen is just something to note in Step 11's report. Running it before `iru-setup-readme` means the README's Documentation section sees it already
in place, and — for a gitflow library — that `sync.yml`'s `sync_versions.py` has a `CHANGELOG.md` to roll the
`[Unreleased]` heading in after each release.

## Step 10 — Run `iru-setup-readme`

Invoke via `iru-isolated-skill-executor`: `Agent({description: "Run iru-setup-readme", subagent_type:
"iru-isolated-skill-executor", prompt: "Invoke Skill({skill: \"iru-setup-readme\", args: \"sonar: <sonar>\\nci: <ci>\\npages: <pages, only when ci: yes>\\non-existing: <..., only if supplied>\"}) — the four keys it accepts (`sonar: none` suppresses Sonar badges/links even if Sonar config exists on disk, `ci: no` omits CI status badges, `pages: no` keeps it from linking a github.io docs site that won't be published),
deriving everything it needs by exploring the repository directly (Package.swift or the XcodeGen/Tuist manifest,
the Antora docs, .gitignore, the workflows and sonar-project.properties, and CHANGELOG.md). Report back only
which sections were included vs. omitted and why, and — for a library — whether the installation snippet it
wrote uses the SwiftPM .package(url: \\\"<repository-url>\\\", from: \\\"<version>\\\") shape.",
run_in_background: false})`. Run this last, after every other skill, so its badges/status-table/
documentation-links sections see the finished state of Steps 5–9 instead of a sparser mid-run snapshot. In
`mode: new`, most CI/Sonar/changelog-derived sections will still be sparse or absent (no commits/tags/CI runs
yet) — expected; `iru-setup-readme` omits what it can't confirm rather than inventing it. With `ci: no`, send
`ci: no` and omit `pages` (it isn't meaningful without CI — no Pages deploy exists). `README.md` may or may not
already exist depending on `mode`; `iru-setup-readme`'s own Update / Keep / Replace question (shown with a diff)
handles that on its own — nothing further to confirm here.

For a gitflow **library**, the README's dependency snippet is what `sync.yml`'s `sync_versions.py` rewrites after
each release — if `iru-setup-readme` reports a snippet shape other than the `.package(url:from:)` line
`iru-setup-swift-github-workflows` recorded in Step 8 (or no SwiftPM snippet at all), flag the mismatch in
Step 11 so the user reconciles one of the two before the first release.

## Step 11 — Report

Summarize the outcome of all six delegated skills together. Start by repeating the **setup mode** announced in
Step 1 (from scratch, or updating an existing repository) and give a per-component table — scaffold manifest,
source layout, lint/format config, `.ui-quality.yml` (`app`), Sonar config, `.spi.yml`/`fastlane/Fastfile`, Antora
docs, `.gitignore`, each
workflow file, `.github/dependabot.yml`, `CHANGELOG.md`, `README.md` — with the action actually taken: **created /
updated / replaced / kept as is / skipped (ci: no)**, plus every mismatch a sub-skill listed for a component kept
as is. Then the resolved `flavor`, `branching` and `mode`, the project identity and license, the flavor-specific inputs (`platforms`, `min-deployment-targets`/
`min-deployment-target`, `generator`, `signing`), the pipeline parameters used (the Step 4 table with the values
actually applied), and which of the scaffold's own files (`Package.swift` + `Sources/`/`Tests/`, or the generator
manifest + `Packages/Core` + `Targets/` + `fastlane/Fastfile`, plus `.swiftlint.yml`, the formatter config,
`.spi.yml`, `sonar-project.properties`), the Antora docs site, the `.gitignore`, the workflow files (`build.yml`/
`release.yml`/`sync.yml` + `sync_versions.py`, or `build.yml`/`release.yml`, plus `security.yml` and
`.github/dependabot.yml`), `CHANGELOG.md`, and `README.md` ended up (the table above).

State explicitly which of publishing/distribution and Sonar analysis were wired, or deliberately omitted and why:

- **`open-source`**: yes/no, as resolved in Step 4.
- **`visibility`**: `public`/`private`/`internal`, and whether it came from `args`, `gh repo view`, or the user.
- **`ci`**: yes/no. For `no`: the workflows step was skipped, and with it the shared security block (Dependabot,
  dependency-review, CodeQL, OSV-Scanner, gitleaks) — run `/iru-setup-swift-github-workflows` later to add it.
  For `yes` on a private/internal repository: restate the included-minutes budget and that macOS minutes count
  roughly 10× against it.
- **`ci-tests`** (`ci: yes` only): `unit` (UI-test bundles skipped via `-skip-testing:` for an app; any library
  integration-test target skipped) or `full`.
- **`accessibility`** (`app`): the level written to `.ui-quality.yml` (`enforce`/`report`/`off`/`none`) and whether it came
  from `args` or the user; the scaffold's UI tests capture the sample screen into `.ui-screenshots/` (gitignored) and
  CI uploads those captures as a 7-day artifact.
- **`pages`** (`ci: yes` only): `yes` (deployed to GitHub Pages — defaulted without asking for a public
  repository) or `no` (docs built and uploaded as a workflow artifact; the README links no github.io site).
- **`publish`** (`library`): whether `.spi.yml` was written — and restate that SwiftPM has no registry publish
  job, so `release.yml` only validates the semver tag and `swift package dump-package` either way.
- **`distribution`**/**`signing`** (`app`): which Fastfile lanes exist as a result (`test` only; `test`+`build`+
  `beta`; or `test`+`build`+`beta`+`release`, plus `notarize_release` for `macos` with a real signing method) and
  which `release.yml` archive/export/TestFlight/notarization steps were generated; `store`/non-`none` unless the
  user opted out (or the project isn't open source and declined it), stated plainly rather than left implicit.
- **`sonar`**: `cloud`/`self-hosted`/`none`, and whether `sonar-project.properties`, the coverage-conversion
  step (`.github/scripts/lcov_to_sonar_generic.py` for both flavors — an app converts `Packages/Core`'s
  `llvm-cov` lcov, not an `.xcresult`) and the
  `SonarSource/sonarqube-scan-action` step were included — if `none`, note whether that's the expected
  non-open-source/private default or a direct user choice; if Sonar is configured with `ci: no`, state that no CI
  step sends reports (analysis only runs when someone runs the scanner manually).
- **`runner`**: `macos-26` or `xcode-27`, restating the GA-vs-preview tradeoff, and the `xcode-version` the
  workflows skill resolved from it.
- **Toolchain versions**: which came from a live lookup versus the recorded September 2026 fallback, as reported
  by the scaffold (`swift-docc-plugin`, XcodeGen/Tuist) and the workflows skill (action tags, runner image).

Unless Step 8 was skipped (`ci: no`), reproduce the **consolidated required-secrets/settings table**
`iru-setup-swift-github-workflows` reported in Step 8 verbatim (it's already scoped to exactly this flavor/branching/options — don't re-derive or expand it):
`SONAR_TOKEN` for a non-`none` `sonar`; `MATCH_GIT_URL`/`MATCH_PASSWORD` for `signing: fastlane-match`;
`APP_STORE_CONNECT_API_KEY_ID`/`APP_STORE_CONNECT_API_KEY_ISSUER_ID`/`APP_STORE_CONNECT_API_KEY_BASE64` for
`signing: api-key` (and also, regardless of `signing`, for notarization when `macos` is selected); `APPLE_TEAM_ID`
for an app whose `distribution` isn't `none` (`release.yml` fills `DEVELOPMENT_TEAM` and the export plists'
`teamID` from it at run time); `GITHUB_TOKEN` is automatic. Then its repository-settings notes: GitHub Pages source set to **GitHub Actions** for the docs
deploy (only when `pages: yes`), and — gitflow only — "Workflow permissions" allowing GitHub Actions to create pull requests for
`sync.yml`. For an app, add the `APPLE_TEAM_ID` secret the workflows read (no Team ID is committed to any file),
and the `fastlane match` certs repository / App Store Connect API key that must exist before the first
signed build.

List every decision made on the user's behalf: the license question's flavor-dependent recommendation (Step 2);
leaving `formatter` for `iru-setup-swift-library` to ask (Step 2); `min-deployment-target` resolved by
`iru-setup-apple-app` from the local simulator runtimes rather than asked (Step 3); `xcode-version` resolved by the
workflows skill from the runner image and `release-please` left at its `no` default (Step 4); `signing: none`
offered as the default when `distribution: none` (Step 4); `pages: yes` defaulted for a public repository
(Step 4); every pre-existing-file decision left to the owning sub-skill's own Update / Keep / Replace question
(Steps 5–10), with `on-existing` forwarded only when it came in via `args`; `iru-setup-swift-gitignore`'s `mode`
left to its own detection and `package-resolved` left for it to ask (Step 7); and the four `security-*` flags
left at their `yes` default (Step 8).

Finish with an explicit **Warn explicitly** block, repeating each sub-skill's own closing warning:

- **Review every generated/updated file before building, committing, or relying on CI.** Confirm the license
  matches an actual `LICENSE` file at the root if one was chosen (`iru-check-license` can generate one and
  backfill Swift headers), and that `swift build && swift test` (library) or `xcodegen generate`/`tuist generate`
  followed by `xcodebuild test` (app) succeeds locally — the generator itself is typically a `brew install` the
  scaffold never performs.
- Branch names, the Apple Developer Team ID, and the signing/distribution setup in the workflows were inferred
  from the resolved parameters and the scaffold's files — because this pipeline handles signing keys and can
  upload to TestFlight/the App Store, a wrong assumption there signs or ships the wrong thing. Do a
  `workflow_dispatch` dry run before trusting a real release.
- Every secret in the table above, and the matching SonarCloud project / Apple Developer Program membership /
  `fastlane match` certs repository / App Store Connect API key, must already exist before CI can pass — nothing
  in this pipeline creates or stores any account or credential.
- If `formatter: swiftformat` was supplied, the generated `build.yml` still runs `swift format lint --strict` and
  needs a matching hand edit (and a `brew install swiftformat` step) before its lint job passes.
- Every toolchain and action version was resolved via a run-time lookup or its recorded September 2026
  fallback — re-check them (Dependabot's `swift` + `github-actions` entries will start proposing bumps) before
  relying on this scaffold long-term. The real `xcodegen`/`tuist` run, SwiftLint, the Sonar scan, Fastlane's
  `match`/`pilot`, TestFlight/App Store uploads and notarization are never exercised locally by any of the six
  skills — only their YAML and the shared SwiftPM package build are validated.
