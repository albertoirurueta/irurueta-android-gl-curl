---
name: iru-setup-swift-gitignore
description: "/iru-setup-swift-gitignore [args] creates or updates the root .gitignore for a Swift package, Apple app or Swift CLI (SwiftPM/Xcode output, generated projects, signing material, Fastlane, UI screenshots, docs). Args: flavor library|app|cli, package-resolved commit|ignore, mode, on-existing. Shows a diff and asks before touching an existing file."
model: haiku
---

# Setup Swift Gitignore

Create or refresh the root `.gitignore` for a Swift package or Apple app project. Never overwrite an existing
`.gitignore` without showing the user exactly what would change and getting explicit approval first — a
`.gitignore` can carry hand-added, project-specific entries that must not be silently dropped. Covers Apple app
projects for iOS, macOS, watchOS and tvOS; mirrors the `iru-setup-java-gitignore`/`iru-setup-typescript-gitignore`/
`iru-setup-android-gitignore` skills' diff-and-approve contract for the Swift/Xcode ecosystem.

## Step 0 — Resolve inputs

Parse `args` as `key: value` lines, skipping any question already answered:

- `mode: new|existing` — if omitted, detect it: a root `.gitignore` already present at the repository root means
  `existing`; its absence means `new`. Either way the file actually on disk decides the path: an existing
  `.gitignore` always goes through Step 5's warn-and-ask (unless `on-existing` says otherwise), even under
  `mode: new`.
- `flavor: library|app|cli` — if omitted, infer it before Step 4 needs it: a `Package.swift` with an
  `.executableTarget` depending on `ArgumentParser` (an `.executable(...)` product) and no `.xcodeproj`/`project.yml`/
  `Project.swift` means `cli` (the `iru-setup-swift-cli` layout); a `Package.swift` whose top-level `Package`
  declares only `.library(...)` products (no `.executable(...)`, and no `.xcodeproj`/`.xcworkspace` for an iOS/macOS
  app target) means `library`; an `.xcodeproj`/`.xcworkspace` at the root, an `Info.plist` with an
  `CFBundleIdentifier`/app-icon asset catalog, or a `Package.swift` declaring any other `.executable(...)`/iOS app
  product means `app` (the `cli` rule is checked first). If no signal is present yet (brand-new, empty repository —
  this skill running ahead of `iru-setup-swift-library`/`iru-setup-apple-app`/`iru-setup-swift-cli`), leave it unresolved and let Step 4's `AskUserQuestion` establish
  it together with `package-resolved`.
- `package-resolved: commit|ignore` — if omitted, resolve it in Step 4 via `AskUserQuestion` (informed by
  `flavor` above when known).
- `on-existing: ask|keep|update|replace` (default `ask`) — how Step 5 treats an existing `.gitignore`: `ask` shows
  the diff and asks; `keep`/`update`/`replace` apply that choice without asking (the warning and the diff are still
  printed). Only meant for scripted runs — orchestrators forward it only when their own `args` received it.

## Step 1 — Survey the target repository

Detect the following before composing content — every one of these gates a Step 3 conditional section:

- `Package.swift` at the root → Swift package (SwiftPM).
- `project.yml` at the root → XcodeGen manifest (the `.xcodeproj` it generates should not be hand-edited or
  tracked).
- `Project.swift` at the root → Tuist manifest (same reasoning — Tuist regenerates the `.xcodeproj`/`.xcworkspace`
  from this file).
- Any `*.xcodeproj` or `*.xcworkspace` directory at the root → an Xcode project/workspace already exists; check
  whether it's currently tracked (`git ls-files -- '*.xcodeproj' '*.xcworkspace'` when a `.gitignore` doesn't
  already exist, before assuming) so Step 3 doesn't propose ignoring something already committed out from under it.
- `fastlane/` directory at the root → Fastlane is in use for this project.
- `scripts/make-dmg.sh` or any root `ExportOptions-*.plist` → a macOS desktop app that archives, exports and packages
  itself (`iru-setup-macos-app`'s layout): its `xcodebuild archive`/`-exportArchive`/DMG/notarization output lands
  under the root `build/`. Also check whether that root `build/` holds anything that must stay tracked:
  `git ls-files -- build` (anything listed is committed) and, when `build/` exists, `ls build` for entries that
  aren't generated output (generated = `dd*`, `docc`, `export*`, `*.xcarchive`, `*.xcresult`, `*.dmg`,
  `*-notarize.zip`, `SourcePackages`) — e.g. a hand-made `build/branding/` icon source.
- `docs/antora.yml` or `docs/antora-playbook.yml` → Antora documentation site (see `iru-setup-antora`).
- Any `*.docc` directory, or `swift-docc-plugin`/`DocCPlugin` referenced in `Package.swift`'s `dependencies`/
  `plugins`, or a `docs/api`-shaped output path configured for `iru-swift-docc` → DocC-generated API reference is
  produced for this project.

Then announce which path this run takes. If no `.gitignore` exists: "**Creating `.gitignore` from scratch**" and
continue straight to Step 2. If one exists: "**Updating the existing `.gitignore`**" — read its current content now
(it matters for Step 4's merge) and warn the user up front that a `.gitignore` already exists and this skill will
propose changes for them to choose from (Step 5) rather than overwrite it silently.

## Step 2 — The universal base template

Every Swift/Xcode project gets this baseline, regardless of package vs. app or manifest tooling:

```gitignore
# Swift Package Manager / Xcode build output
.build/
DerivedData/
*.xcresult
xcuserdata/
*.xcuserstate
.swiftpm/xcode/

# OS noise
.DS_Store

# Repository-wide lock serializing simulator / macOS desktop UI-test runs (iru-swift-code-one-task-group)
.simulator-test.lock/
```

This section is unconditional — include it verbatim. Notes on specific lines:

- `.simulator-test.lock/` is the atomic-`mkdir` lock directory (with its `owner` file) that
  `iru-swift-code-one-task-group` creates around every app-kind `xcodebuild test` run — a simulator destination or,
  for a macOS desktop app, the real desktop session XCUITest drives. Always transient, never committed.

- `.swiftpm/xcode/` is scoped to that one subdirectory, **not** the whole `.swiftpm/`, deliberately: SwiftPM also
  writes `.swiftpm/configuration/` (e.g. per-package `Package.swift` resolution/registry settings) which is
  ordinary, shareable project configuration that should stay trackable — ignoring all of `.swiftpm/` would hide it
  along with the genuinely local, machine-specific `.swiftpm/xcode/` Xcode-integration cache. Never broaden this
  line to `.swiftpm/`.
- `*.xcresult` has no trailing slash even though it's always a directory bundle in practice (`xcodebuild test
  -resultBundlePath` output) — a bare `*.xcresult` matches the bundle either way, so this is a style choice, not a
  functional gap.
- Signing material (`*.p8`/`*.p12`/`*.mobileprovision`/`*.cer`, below in Step 3) is listed unconditionally there
  rather than gated on Fastlane, because a project can generate/download these through Xcode's own automatic
  signing or `fastlane match` without a `fastlane/` directory existing yet.

## Step 3 — Detect project-specific additions

Don't guess; only add a section below when the thing it targets is actually present (or about to be produced by a
sibling skill) in the target repository.

- **Xcode project/workspace generated by a manifest tool** (`project.yml` or `Project.swift` found in Step 1): add
  ```gitignore
  # Xcode project generated by XcodeGen/Tuist — regenerate instead of tracking
  *.xcodeproj/
  *.xcworkspace/
  ```
  Skip this entirely when neither `project.yml` nor `Project.swift` exists — in that case the `.xcodeproj`/
  `.xcworkspace` is the hand-maintained source of truth and **must stay tracked**; do not ignore it. If Step 1 found
  an `.xcodeproj`/`.xcworkspace` already tracked in git while also finding a generator manifest (a project that
  recently adopted XcodeGen/Tuist but hasn't untracked the old generated project yet), still propose the ignore
  entry but flag in the final report that `git rm -r --cached <path>` is needed separately — writing `.gitignore`
  alone never untracks already-committed files.

- **`Package.resolved`** (`Package.swift` found in Step 1, i.e. this is a SwiftPM package/project at all): resolve
  via `args`' `package-resolved` if given; otherwise ask with `AskUserQuestion` (informed by `flavor` when already
  known, otherwise ask both together):
  - **Commit it** (recommended default for `flavor: app` and `flavor: cli`): an application — and a command-line
    tool is one — should pin its exact resolved dependency
    graph for reproducible builds across machines and CI, the same reason a `package-lock.json`/`Package.lock` gets
    committed — don't add a `Package.resolved` entry to the template at all (it stays tracked).
  - **Ignore it** (recommended default for `flavor: library`): a library's own `Package.resolved` only pins
    versions for *its own* development/test builds, never for consumers (SwiftPM re-resolves against the
    consumer's manifest regardless), so committing it just creates merge-conflict and staleness noise without
    benefiting anyone downstream — add:
    ```gitignore
    # Dependency resolution snapshot — this is a library; consumers resolve their own graph
    Package.resolved
    ```
  If `flavor` is unknown and the user has no preference, default to **commit** (the safer, reproducible-build
  choice) and note the assumption in the final report.

- **Fastlane** (`fastlane/` directory found in Step 1): add
  ```gitignore
  # Fastlane
  fastlane/report.xml
  fastlane/screenshots/
  fastlane/test_output/
  ```
  Skip entirely if no `fastlane/` directory exists — don't preemptively add Fastlane's own output paths when the
  project has no Fastlane setup to speak of. `fastlane/Fastfile`, `fastlane/Appfile`, and any other Fastlane
  configuration files stay tracked (not covered by this section).

- **macOS desktop distribution output** (`scripts/make-dmg.sh` or a root `ExportOptions-*.plist` found in Step 1):
  when nothing under the root `build/` is tracked or hand-made (Step 1's check), add
  ```gitignore
  # macOS desktop distribution output — xcodebuild archive/-exportArchive, scripts/make-dmg.sh, notarization
  /build/
  *.xcarchive/
  *.dmg
  *-notarize.zip
  ```
  `/build/` is anchored to the root on purpose: it never matches `docs/build/` (the Antora section's own entry) or
  a nested `App/Resources/build/` folder, and `Packages/Core/.build/` is already covered by `.build/`. When Step 1
  found tracked or hand-made content under `build/`, don't ignore the directory — replace the `/build/` line with
  the generated subfolders only (`/build/dd*/`, `/build/docc/`, `/build/export*/`; archives, result bundles and DMGs
  are covered by `*.xcarchive/`, `*.xcresult` and `*.dmg` wherever they land) and name the kept paths in the report.
  `*-notarize.zip` is the `ditto` zip `iru-setup-macos-github-workflows`' release job submits to `notarytool` (a
  zip can't be stapled, so it is a throwaway). `scripts/make-dmg.sh`, `ExportOptions-*.plist`, `project.yml`,
  `App/**` and `Packages/Core/**` (sources, tests, `Package.swift`, `Package.resolved`) stay tracked. Skip the whole
  section when neither file exists.

- **Command-line tool outputs** (`flavor: cli`, or `scripts/generate-docs.sh` found in Step 1): add
  ```gitignore
  # Command-line tool outputs — scripts/generate-docs.sh (man page, completions, schema.json) and release packaging
  /dist/
  /build/
  /*.tar.gz
  /*.zip
  /SHA256SUMS
  ```
  All five are root-anchored: `/dist/` is `scripts/generate-docs.sh`'s default output directory, `/build/` the one
  the CI docs job passes it (`scripts/generate-docs.sh build/gendocs`, `iru-setup-cli-github-workflows`), and
  `/dist/` is also where release packaging stages `<bin>-<version>-<target>.tar.gz`/`.zip` archives; archives or a `SHA256SUMS` left at the root by
  a local packaging run are covered too, while a zip/tarball fixture under `Tests/` stays tracked. The man page the
  `generate-manual` plugin writes (`.build/plugins/GenerateManual/outputs/…`) and DocC archives are already under
  `.build/`. The generated Antora partial `docs/modules/ROOT/partials/cli-reference.adoc` is **not** ignored — it is
  committed and regenerated by `scripts/generate-docs.sh` so the docs site builds without compiling the tool. Skip
  the section for `library`/`app` flavors without that script.

- **UI test screenshots** (`flavor: app`, or Step 1 found `.ui-quality.yml`, a `UIScreenshots.swift` test helper, or
  a package whose targets import SwiftUI/UIKit/AppKit — never for `flavor: cli`, which has no UI): add
  ```gitignore
  # UI tests' screenshots (UI contract) — regenerated by every UI test run; docs copy what they use into images/ui/
  .ui-screenshots/
  ```
  Unanchored on purpose (a nested package's `swift test` run resolves it next to the nearest `.git` too). Never
  ignore `.ui-quality.yml` — it is the committed accessibility/matrix setting the scaffolds write.

- **Signing material** — unconditional (see Step 2's note on why this isn't gated on Fastlane): add
  ```gitignore
  # Signing material — never commit
  *.p8
  *.p12
  *.mobileprovision
  *.cer
  ```

- **Antora documentation build output** (`docs/antora.yml` or `docs/antora-playbook.yml` found in Step 1): add
  ```gitignore
  docs/build/
  ```
  Skip if there's no Antora docs setup.

- **DocC-generated API reference** (a `*.docc` catalog or a `swift-docc-plugin`/DocC dependency found in Step 1):
  add
  ```gitignore
  docs/api/
  ```
  If Antora is also present and DocC's static-hosting output is configured to land *inside* the Antora tree instead
  (e.g. under `docs/modules/ROOT/assets/api/`, mirroring how the TypeScript/Android workflows skills merge
  API-doc output under the Antora build), use that actual output path from the DocC/`iru-swift-docc` configuration
  instead of the generic `docs/api/` — don't duplicate an entry already covered by `docs/build/` above.

- Don't invent additional entries beyond what's actually detected — if the project has no generator manifest, no
  Fastlane directory, and no docs tooling, the composed `.gitignore` should simply not mention them.

## Step 4 — Compose the proposed content

Concatenate Step 2's base template with whichever Step 3 sections actually matched, each under its own comment
header for readability (already shown inline above). If Step 1 found an existing `.gitignore`, merge rather than
duplicate: keep any of its lines that aren't already covered by the composed template (hand-added project-specific
ignores must survive), and don't repeat a line that's already present verbatim. If `mode: existing` and the
existing file predates one of these additions (e.g. it has no `.swiftpm/xcode/` line yet), the merge naturally adds
it — that's the intended gap-fill behavior, not an overwrite.

## Step 5 — If `.gitignore` already existed, warn and ask before writing

Skip this step entirely if Step 1 found no existing file — proceed straight to Step 6's write.

- Show the user the proposed merged `.gitignore` content (Step 4) as a diff against the current file (writing the
  draft to a temp path and running `git diff --no-index <current> <draft>` gives a clean unified diff). Call out
  any existing line that conflicts with this run's choices (e.g. an existing `Package.resolved` entry when the
  decision was **commit**, or `*.xcodeproj` ignored with no generator manifest present).
- If `on-existing` is `keep`/`update`/`replace`, apply it without asking. Otherwise ask via `AskUserQuestion`,
  stating each consequence in the option descriptions:
  - **Update (Recommended)** — write the merged content shown in the diff: missing entries are added, every
    hand-added project-specific line is kept. Customizations survive; a line this skill owns may be reworded or
    regrouped under its comment header.
  - **Keep as is** — leave the existing `.gitignore` untouched. Nothing changes, but entries this run expects may
    stay missing (e.g. build output or signing material could get committed) — Step 6 lists each missing entry.
  - **Replace** — write only the composed template (Steps 2–3), dropping every hand-added line not in it. Matches
    this house pattern exactly, but those custom ignores are lost (recoverable only from git history) and the
    files they covered may show up as untracked.
  There is no partial-apply option — if the user wants only some of the proposed lines, let them say so in free
  text and revise the draft before writing.
- Only proceed to Step 6's write on Update or Replace; on Keep, skip the write and go to Step 6's report.

## Step 6 — Write `.gitignore` and report

Write the composed content (Step 4, incorporating any edits from Step 5's review) to `.gitignore` at the repository
root. Then summarize:

- The path announced in Step 1 and the action actually taken — **created / updated / replaced / kept as is** —
  and which lines were added (or, for kept as is, which expected entries remain missing; for replaced, which
  hand-added lines were dropped).
- Which conditional Step 3 sections were included versus skipped and why (e.g. "command-line tool outputs —
  `flavor: cli`", "UI test screenshots — `flavor: app`", "no XcodeGen/Tuist section — no
  `project.yml`/`Project.swift` found, so `MyApp.xcodeproj` stays tracked", "no Fastlane section — no `fastlane/`
  directory", "no DocC section — no `*.docc` catalog and no DocC plugin dependency").
- The `Package.resolved` decision: whether it was pre-resolved via `args`, asked via `AskUserQuestion`, or
  defaulted, and which way it went (commit vs. ignore) with the reasoning restated.
- If `.gitignore` already existed: which of Update / Keep as is / Replace was chosen, and whether it came from the
  user or from `on-existing`.

**Warn explicitly**:

- Review the generated `.gitignore` before committing — detection is heuristic (manifest/config-file signals), not
  a guarantee that every relevant path was found.
- If the repository already had an `.xcodeproj`/`.xcworkspace` (or any other directory) tracked that this skill now
  proposes to ignore, `git rm -r --cached` that path manually after reviewing the diff — writing `.gitignore` alone
  does not untrack already-committed files.
- The `Package.resolved` commit-vs-ignore choice affects reproducibility, not just tidiness — revisit it if the
  project's `flavor` changes later (a library that grows an example app, or vice versa).
- Which values were inferred versus verified: the base template (Step 2) and every Step 3 pattern were verified
  locally (see "Known quirks / verification notes" below) against real `git status --porcelain --ignored`/
  `git check-ignore -v` output in a throwaway repository — not merely written from memory of Xcode's default
  template. The DocC-inside-Antora nested-path case and the generator-manifest detection heuristics are this
  catalog's own convention, not sourced from an external reference, and worth a second look on a project with an
  unusual layout.

## Known quirks / verification notes

Verified in `$TMPDIR/iru-verify/swift/gitignore/`: `git init`, wrote the composed template above to `.gitignore`,
then created one dummy file or directory per pattern — `.build/x`, `DerivedData/x`, `test.xcresult/info.plist`
(directory form), `xcuserdata/x`, `.xcuserstate`, `.swiftpm/xcode/x`, `.swiftpm/configuration/x` (must **not** be
ignored), `.DS_Store`, `MyApp.xcodeproj/project.pbxproj`, `MyApp.xcworkspace/contents.xcworkspacedata`,
`Package.resolved`, `fastlane/report.xml`, `fastlane/screenshots/screenshot1.png`,
`fastlane/test_output/log.txt`, `fastlane/Fastfile` (must **not** be ignored), `signing.p8`, `cert.p12`,
`profile.mobileprovision`, `cert.cer`, `docs/build/index.html`, `docs/api/index.html`, `docs/antora.yml` (must
**not** be ignored), `Package.swift`/`Sources/MyLib/MyLib.swift` (must **not** be ignored) — then ran
`git status --porcelain --ignored` and `git check-ignore -v` on the boundary cases:

| Path | Expected | Result |
|---|---|---|
| `.build/x` | ignored | ignored — `.gitignore:.build/` |
| `DerivedData/x` | ignored | ignored |
| `test.xcresult/` | ignored | ignored — `.gitignore:*.xcresult` (matches the directory bundle with no trailing slash) |
| `xcuserdata/x` | ignored | ignored |
| `.xcuserstate` | ignored | ignored |
| `.swiftpm/xcode/x` | ignored | ignored — `.gitignore:.swiftpm/xcode/` |
| `.swiftpm/configuration/x` | **tracked** | **not ignored** (confirmed — scoping to `.swiftpm/xcode/` instead of the whole `.swiftpm/` works as intended) |
| `.DS_Store` | ignored | ignored |
| `MyApp.xcodeproj/` | ignored (generator-manifest case tested) | ignored — `.gitignore:*.xcodeproj/` |
| `MyApp.xcworkspace/` | ignored | ignored — `.gitignore:*.xcworkspace/` |
| `Package.resolved` | ignored (library case tested) | ignored |
| `fastlane/report.xml` | ignored | ignored |
| `fastlane/screenshots/` | ignored | ignored |
| `fastlane/test_output/` | ignored | ignored |
| `fastlane/Fastfile` | **tracked** | **not ignored** |
| `signing.p8` / `cert.p12` / `profile.mobileprovision` / `cert.cer` | ignored | all ignored |
| `docs/build/` | ignored | ignored |
| `docs/api/` | ignored | ignored |
| `docs/antora.yml` | **tracked** | **not ignored** |
| `Package.swift` / `Sources/MyLib/MyLib.swift` | **tracked** | **not ignored** |

All 19 intended-ignore paths were ignored and all 5 intended-tracked paths were left untouched — no
over-matching, no under-matching. One pattern quirk worth flagging for future edits to this template: `*.xcodeproj`
and `*.xcworkspace` are always directories in practice, so the trailing slash on `*.xcodeproj/`/`*.xcworkspace/` is
not strictly required for git to match them (a bare `*.xcodeproj` matches the directory too) — the slash is kept
purely so the pattern can never accidentally also match a stray same-named file, and should not be dropped when
editing this section. Separately, `.swiftpm/xcode/` vs. ignoring the whole `.swiftpm/` is not a cosmetic choice:
SwiftPM writes real, shareable configuration under `.swiftpm/configuration/` (package registry/resolution settings)
that must stay trackable, so any future edit that "simplifies" this to a single `.swiftpm/` line would silently
start hiding that directory — confirmed above that the scoped line does not do so.

**macOS desktop distribution section** — verified on 2026-10-16 in
`$TMPDIR/iru-verify/macos/iru-setup-macos-github-workflows/gitignore-check/` (a copy of an `iru-setup-macos-app`
scaffold after a real `xcodegen generate`, `xcodebuild test`/`archive`/`-exportArchive`, DocC and
`scripts/make-dmg.sh` run, plus the rendered `.github/`), with the base + XcodeGen + signing + this section +
`docs/build/` composed into `.gitignore`:

| Path | Expected | Result |
|---|---|---|
| `build/dd/…`, `build/docc/`, `build/export/MyMacApp.app`, `build/MyMacApp.xcarchive`, `build/MyMacApp-1.0.0.dmg` | ignored | ignored — `.gitignore:/build/` |
| `foo.dmg` (repository root) / `MyMacApp-notarize.zip` | ignored | ignored — `*.dmg` / `*-notarize.zip` |
| `docs/build/site/index.html` / `Packages/Core/.build/…` | ignored | ignored — `docs/build/` / `.build/` (not by `/build/`) |
| `project.yml`, `Packages/Core/{Package.swift,Package.resolved,Sources/**,Tests/**}`, `App/{Sources,Resources,UITests}/**`, `scripts/make-dmg.sh`, `ExportOptions-developer-id.plist`, `sonar-project.properties`, `.github/**`, `docs/antora.yml`, `.swiftlint.yml` | **tracked** | **not ignored** (`git check-ignore` silent; `git add -A` staged 40 files, none under `build/`, no `.dmg`/`.xcarchive`/`.xcodeproj`) |
| Fallback (a tracked `build/branding/icon.png`): `/build/dd*/`, `/build/docc/`, `/build/export*/` + `*.xcarchive/` + `*.dmg` | generated output ignored, `build/branding/icon.png` and `App/Resources/build/keep.txt` tracked | as expected; a root-anchored `/build/` also leaves `App/Resources/build/` untouched |

Not independently re-verified here (already covered by the sibling gitignore skills' own verification, and nothing
Swift-specific changes their behavior): the generic diff/`git diff --no-index`-based approval flow in Step 5, and
`AskUserQuestion`'s prompt/response mechanics in Step 3.

**Command-line tool section (`flavor: cli`)** — verified on 2026-10-16 in
`$TMPDIR/iru-verify/cli/swift/iru-setup-swift-cli/gitignore-check/` (a copy of an `iru-setup-swift-cli` scaffold after
`swift build`, `swift test --enable-code-coverage`, a universal release build and `scripts/generate-docs.sh`), with the
base + CLI section + signing + `docs/build/` composed into `.gitignore` and `Package.resolved` committed:

| Path | Expected | Result |
|---|---|---|
| `.build/…` (incl. `.build/plugins/GenerateManual/outputs/<Bin>/<bin>.1`), `build/gendocs/man/<bin>.1`, `dist/man/<bin>.1`, `dist/completions/_<bin>`, `dist/schema.json`, `<bin>-0.1.0-x86_64-apple-darwin.tar.gz`, `<bin>-0.1.0-x86_64-pc-windows-msvc.zip`, `SHA256SUMS` (root) | ignored | ignored — `.build/` / `/build/` / `/dist/` / `/*.tar.gz` / `/*.zip` / `/SHA256SUMS` |
| `Package.swift`, `Package.resolved`, `Sources/**`, `Tests/**` (incl. a `Tests/Fixtures/sample.zip`), `scripts/generate-docs.sh`, `.swiftlint.yml`, `.swift-format`, `docs/antora.yml`, `docs/modules/ROOT/partials/cli-reference.adoc` | **tracked** | **not ignored** (`git check-ignore` silent) |

