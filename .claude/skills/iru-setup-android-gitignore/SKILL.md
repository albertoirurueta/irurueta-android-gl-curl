---
name: iru-setup-android-gitignore
description: "Invoked as /iru-setup-android-gitignore with optional key: value args, usually by iru-setup-android-repository. Creates or updates the root and per-module .gitignore files of an Android/Gradle project, never overwriting an existing one without a diff and approval."
model: haiku
---

# Setup Android Gitignore

Create or refresh the root `.gitignore` and each module's own `.gitignore` for an Android (Gradle/Kotlin) project,
using [irurueta-android-glutils](https://github.com/albertoirurueta/irurueta-android-glutils)'s own `.gitignore`,
`lib/.gitignore` and `app/.gitignore` as the concrete reference templates (genericized — the reference repository
has no project-specific names inside these three files to strip in the first place, they're already generic
Android/Gradle boilerplate). Never overwrite an existing `.gitignore` without showing the user exactly what would
change and getting explicit approval first — a `.gitignore` can carry hand-added, project-specific entries that
must not be silently dropped.

This mirrors `iru-setup-java-gitignore`'s diff-and-approve contract for the Android/Gradle ecosystem.

## Step 0 — Resolve inputs

Parse `args` as `key: value` lines, skipping any question already answered:

- `mode: new|existing` — if omitted, detect it: a root `.gitignore` already present at the repository root means
  `existing`; its absence means `new`. `mode: existing` never means "update silently" — every pre-existing file
  still goes through Step 6's Update / Keep / Replace question (unless `on-existing` decides it).
- `on-existing: ask|keep|update|replace` — how Step 6 treats each `.gitignore` that already exists. Default `ask`
  (one question per file). `keep`/`update`/`replace` applies that choice to every pre-existing file without asking
  (the warning is still printed). Only meant for scripted runs; an orchestrator passes it only if its own `args`
  received it.
- `modules: <list>` — comma- or space-separated module directory names to give their own `.gitignore` (e.g. `lib`,
  `app`). If omitted:
  1. If `settings.gradle.kts` (or `settings.gradle`) exists at the repository root, derive the list from every
     `include(":<name>")` (or Groovy `include ':<name>'`) line, stripping the leading `:` and any nested-module
     prefix (`include(":lib")` → `lib`; a nested module like `include(":lib:sub")` → `lib/sub`, and its
     `.gitignore` goes inside that nested directory).
     ```bash
     grep -oE 'include\(?["'"'"']:[^"'"'"']+' settings.gradle.kts settings.gradle 2>/dev/null \
       | sed -E "s/.*:([^\"']+).*/\1/" | tr ':' '/' | sort -u
     ```
  2. If no `settings.gradle*` exists yet (this skill run standalone, ahead of `iru-setup-android-library`/
     `iru-setup-android-app`), default to whichever of `lib`/`app` actually exist as directories at the repository
     root; if neither exists, ask the user via `AskUserQuestion` for the module directory name(s) rather than
     guessing.

## Step 1 — Survey existing `.gitignore` files

- **Root**: read `.gitignore` at the repository root if present (matters for Step 5's merge and to warn the user up
  front that changes will be proposed rather than silently applied).
- **Per module**: for each module resolved in Step 0, read `<module>/.gitignore` if present. Each module's
  `.gitignore` is tracked and merged independently from the root one and from every other module's.
- Tell the user which of the two this run is doing:
  - **Creating from scratch** — nothing exists anywhere (no root `.gitignore`, no module `.gitignore`): the final
    writes in Step 7 happen without needing approval — go straight from composing content to writing it, then
    report in Step 7.
  - **Updating existing** — **anything** already exists (root and/or any module): warn the user up front, listing
    each existing file by path, that Step 6 will ask per file whether to Update, Keep, or Replace it (or apply
    `on-existing` if supplied) rather than overwrite anything silently, and continue. Files absent alongside
    existing ones are still created from scratch.

## Step 2 — The root `.gitignore` base template

Every Android project gets this baseline, adapted verbatim from the reference repository's own root `.gitignore`,
plus additions of this skill's own: `.kotlin/` (the Kotlin 2.x compiler's root-level cache directory, which
every build creates whether or not any static-analysis tooling is wired — the reference predates it), the UI
tests' `.ui-screenshots/` capture directory, and two Antora lines this catalog always wants for a project that also carries Antora documentation (`iru-setup-antora`)
— the reference repository itself has no `docs/` yet:

```gitignore
# Gradle files
.gradle/
build/

# Kotlin compiler cache (created at the repository root by every Kotlin 2.x build)
.kotlin/

# Local configuration file (sdk path, etc)
local.properties

# Log/OS Files
*.log

# Android Studio generated files and folders
captures/
.externalNativeBuild/
.cxx/
*.apk
output.json

# IntelliJ
*.iml
.idea/
misc.xml
deploymentTargetDropDown.xml
render.experimental.xml

# Keystore files
*.jks
*.keystore

# Google Services (e.g. APIs or Firebase)
google-services.json

# Android Profiling
*.hprof

# Antora documentation build output (see iru-setup-antora)
docs/build/
docs/node_modules/

# UI test screenshots (regenerated by every UI test run; docs copy what they use into docs/.../images/ui/)
.ui-screenshots/
```

This section is unconditional — include it verbatim. Notes on specific lines:

- `.ui-screenshots/` is the repository-wide capture directory of the catalog's UI contract — the Robolectric/
  Roborazzi and instrumented UI tests write `<kind>/<Id>/<state>--<direction>--<theme>.png` there, and
  `iru-android-test` clears it at the start of every full UI run, so it never belongs in git (the documentation
  skills commit the few images they use under the Antora module's `images/ui/`). It is unconditional because every
  Android app — and every library repository's sample `app` — renders UI. Never ignore `.ui-quality.yml` (the
  committed accessibility/matrix settings next to it).

- `build/` here is **unanchored** (no leading `/`), exactly as the reference repository has it — it matches any
  directory literally named `build` at any depth, which is what makes every module's own generated output (and
  `docs/build/`, redundantly with the explicit line above) fall out of tracking even before Step 3's per-module
  files are added. Keep it unanchored; don't "fix" it to `/build/` — that would stop matching `lib/build/`,
  `app/build/`, etc. from the root file alone (Step 3's per-module `.gitignore` still covers those directly, but
  the two mechanisms are deliberately redundant, matching the reference).
- `*.jks` / `*.keystore` cover a locally-created release keystore and also the file CI decodes a base64 secret
  into during a release workflow (`app/release.jks`, matching `iru-setup-android-github-workflows`'s `main.yml`
  signing step) — that path is a `*.jks` match already, no separate line needed.
- `google-services.json` (Firebase/Google APIs config, `distribution: internal`/`store` app flavors) is ignored
  unconditionally, matching the reference; if the project intentionally commits a placeholder version for CI to
  overwrite, that's a project-specific call to flag in Step 7's report, not something this template should special
  case.
- `.kotlin/` is unconditional: Kotlin 2.x creates that cache directory at the repository root on every build,
  regardless of whether Detekt/ktlint/Spotless (Step 4) are wired, so it doesn't belong behind a conditional.
- **A vendored JaCoCo CLI directory (`jacoco-<ver>/`, e.g. `jacoco-0.8.13/lib/jacococli.jar`) is deliberately
  never added to this template, and no line here may be broadened to match it.** Neither `iru-setup-android-library`
  nor `iru-setup-android-app` vendors one (AGP's `createDebugUnitTestCoverageReport` emits the XML directly), but
  if the repository vendors one — as the reference repository does, and as `iru-setup-android-github-workflows`'
  opt-in coverage-conversion fallback expects — it must stay tracked: the JaCoCo CLI jars are what convert the AGP
  unit-test coverage `.exec` output to XML in CI without depending on network access at build/CI time.
  Concretely: **do not** add a `*.jar` pattern anywhere in this template
  (the reference `.gitignore` has none, precisely so the vendored jars stay tracked) — before proposing any new
  line in Step 4 that could plausibly be a glob starting with `*.jar`, `**/*.jar`, `jacoco*/`, `lib/`, or `/lib/`,
  check it against `git check-ignore -v jacoco-<ver>/lib/jacococli.jar` in the target repository (or the verified
  pattern below if the directory doesn't exist there yet) and refuse to add it if it would match. No negation
  (`!jacoco-*/`) is needed as long as this rule is followed — negating an ignored path only matters once something
  ignores it, and nothing in this template does.

## Step 3 — Per-module `.gitignore`

For each module resolved in Step 0, its own `.gitignore` is a single line, matching the reference repository's
`lib/.gitignore` and `app/.gitignore` exactly:

```gitignore
/build
```

(Anchored to the module root, unlike the root file's unanchored `build/` — this is the reference's own convention:
a module's `.gitignore` only needs to ignore its own top-level `build/` output directory, not every directory
named `build` nested arbitrarily deep inside its sources.) This line is unconditional for every module in the
list — a Gradle module's build output directory always needs ignoring, and there's no project-specific variant of
this one line to detect.

## Step 4 — Detect additional project-specific root entries

Don't guess; only add a section below when the thing it targets is actually present (or about to be created by a
sibling skill) in the target repository — everything in Step 2 is unconditional, this step is the only place that
varies per project.

- **Antora docs already covered** — Step 2's `docs/build/`/`docs/node_modules/` lines are unconditional (added even
  when `docs/` doesn't exist yet, since `iru-setup-antora` typically runs later in the same bootstrap pipeline);
  don't duplicate them here.
- **Detekt/ktlint/Spotless**: nothing extra to add — Detekt and Spotless keep their caches under each module's
  `build/` (already ignored), and the root-level `.kotlin/` compiler cache is in Step 2's unconditional base
  template rather than gated on static analysis, since every Kotlin 2.x build creates it. Mention in Step 7's
  report that no section was needed for these tools.
- **Fastlane**, if a `fastlane/` directory exists (some app projects add it later for store metadata/screenshots
  automation, outside this catalog's own scaffold skills but sometimes added by hand): add
  ```gitignore
  fastlane/report.xml
  fastlane/Preview.html
  fastlane/screenshots/**/*.png
  fastlane/test_output
  ```
  Skip entirely if no `fastlane/` directory exists — don't preemptively add Fastlane's own template when the
  project has no Fastlane setup to speak of.
- Don't invent additional entries beyond what's actually detected — if the project has no `fastlane/` directory,
  the composed root `.gitignore` should simply not mention it.

## Step 5 — Compose the proposed content

- **Root**: concatenate Step 2's base template with whichever Step 4 sections actually matched, each under its own
  comment header. If Step 1 found an existing root `.gitignore`, merge rather than duplicate: keep any of its lines
  that aren't already covered by the composed template (hand-added project-specific ignores must survive — e.g. a
  project that added its own `fastlane/` block by hand before this skill learned to detect it), and don't repeat a
  line that's already present verbatim. If `mode: existing` and the existing file was itself missing lines this
  template supplies (e.g. it predates the Antora `docs/build/` addition), the merge naturally adds them — that's
  the intended gap-fill behavior, not an overwrite.
- **Per module**: for each module, Step 3's single `/build` line either becomes the whole file (module had no
  `.gitignore` yet) or is merged into whatever the module's existing `.gitignore` already has (added only if
  missing — most module `.gitignore` files that already exist for an Android module will already have this exact
  line, in which case there is nothing to change and this module is skipped from Step 6's writes/diffs entirely).

## Step 6 — If anything already existed, warn and ask Update / Keep / Replace

Skip entirely for any file (root or a given module) that Step 1 found absent — write that one straight away, no
approval needed, since there's nothing to lose.

For every file that did already exist and whose proposed content differs from what's on disk:

- Show the user the proposed **Update** content as a diff against the current file (writing the draft to a temp
  path and running `git diff --no-index <current> <draft>` gives a clean unified diff) — do this for the root
  `.gitignore` and each affected module `.gitignore` together, in one batch. If any file has hand-added lines that
  aren't in this skill's template, also say which lines a **Replace** would drop.
- Unless `on-existing` (Step 0) is `keep`/`update`/`replace` — in which case apply it to every such file, still
  printing the warning and the diff — ask via `AskUserQuestion`, one question per file (batch up to 4 files per
  call; a further call for any beyond that), with these options and consequences in their descriptions:
  - **Update (Recommended)** — "Merge: add the template lines this file is missing (Step 5's composed content);
    keep every hand-added, project-specific line. Your customizations survive."
  - **Keep as is** — "Leave the file untouched. Nothing you customized changes, but the missing lines shown in the
    diff (e.g. `.kotlin/`, `docs/build/`) stay unignored — the report will list them."
  - **Replace** — "Overwrite the file with this skill's template only (Step 2 + Step 4 for the root, Step 3 for a
    module). Matches the house pattern exactly, but every hand-added line is lost (recoverable only from git
    history)."
  If the user wants only some of the proposed lines, let them say so in free text and revise the draft before
  writing.
- Proceed to Step 7's writes only for files whose choice was Update or Replace; for Replace, write the bare
  template (no merged-in existing lines), still applying Step 2's vendored-`jacoco-<ver>/` rule.

## Step 7 — Write and report

Write the composed content (Step 5, incorporating any edits from Step 6's review) to `.gitignore` at the
repository root and to `<module>/.gitignore` for each module in the resolved list. Then summarize:

- Whether the root `.gitignore` was newly created, updated (and which lines were added), or left untouched
  (already matched).
- Same, per module.
- Which conditional Step 4 sections were included versus skipped and why (e.g. "no Fastlane section — no
  `fastlane/` directory"; "Detekt/ktlint/Spotless need no section of their own — `.kotlin/` is in the base
  template").
- Confirmation that no ignore pattern would match a vendored `jacoco-<ver>/` CLI directory (only relevant if the
  repository vendors one, as the reference repository does — if it was found already tracked in the target
  repository, confirm it still shows as tracked via `git check-ignore jacoco-*/lib/jacococli.jar; echo $?`
  returning `1`, i.e. not ignored).
- Whether this run created from scratch or updated existing files, and, per pre-existing file, the action actually
  taken — **updated / replaced / kept as is** — and whether it came from the user's answer or from `on-existing`.
  For each file kept as is, list the template lines it is still missing.

**Warn explicitly**:

- Review every generated/updated `.gitignore` before committing — detection is heuristic (manifest/config-file
  signals), not a guarantee every relevant path was found.
- If the repository already had files tracked under a directory this skill now proposes to ignore, `git rm -r
  --cached` that directory manually after reviewing the diff — writing `.gitignore` alone does not untrack
  already-committed files.
- If the repository vendors `jacoco-<ver>/lib/*.jar` files (as the reference repository does), they must stay
  committed for CI's opt-in coverage-conversion fallback (`iru-setup-android-github-workflows`'s "Convert unit
  tests coverage results" step) to work without network access — never let a future edit to this `.gitignore`
  start matching them.
- Which values were inferred versus verified: the root/per-module templates (Step 2/3) are lifted verbatim from
  irurueta-android-glutils's own `.gitignore` files, which is a real, currently-maintained Android library
  repository — verified as accurate to that source as of this skill's authoring. The `.kotlin/`/Antora/`.ui-screenshots/` lines
  (Step 2) and the Fastlane addition (Step 4) are this catalog's own convention, not sourced from that reference
  repository, and worth a second look on a project with an unusual layout.

## Verification notes

Verified in `$TMPDIR/iru-verify/android/gitignore/`: wrote the composed root `.gitignore` and both module
`.gitignore` files (`lib/.gitignore`, `app/.gitignore`) into a throwaway `git init`-ed directory seeded with
`lib/build/x`, `docs/build/site/index.html`, `jacoco-0.8.13/lib/jacococli.jar`, `local.properties`,
`app/release.jks`, `google-services.json`, and `.idea/x`, then ran `git check-ignore -v` on each path:

| Path | Result |
|---|---|
| `lib/build/x` | ignored — `lib/.gitignore:1:/build` |
| `docs/build/site/index.html` | ignored — `.gitignore:docs/build/` |
| `jacoco-0.8.13/lib/jacococli.jar` | **not ignored** (confirmed — no rule matches it) |
| `local.properties` | ignored — `.gitignore:local.properties` |
| `app/release.jks` | ignored — `.gitignore:*.jks` |
| `google-services.json` | ignored — `.gitignore:google-services.json` |
| `.idea/x` | ignored — `.gitignore:.idea/` |

All seven matched the expected outcome. Re-run after adding the UI-contract line, with `.ui-screenshots/
component/Greeting/default--ltr--light.png` and `.ui-quality.yml` added to the seeded tree:
`.ui-screenshots/…png` is ignored by `.gitignore:.ui-screenshots/`, `.ui-quality.yml` is **not** ignored, and the
seven original paths kept their results. The `modules:` default-detection `grep`/`sed` one-liner in Step 0 was
exercised by hand against the reference repository's own `settings.gradle.kts` (`include(":app")`,
`include(":lib")`) and correctly produced `app`, `lib`.
