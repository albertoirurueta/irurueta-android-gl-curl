---
name: iru-setup-java-library-repository
description: "Invoke as `/iru-setup-java-library-repository`, optionally with `args` (key: value lines such as `mode`, `open-source`, `visibility`, `ci`, `publish`, `sonar`, `on-existing`) to skip matching questions. End-to-end bootstrap of a Java/Maven library repository: collects identity, pipeline and Sonar/publishing choices once, then runs `iru-setup-java-library`, `iru-setup-antora`, `iru-setup-java-gitignore`, `iru-setup-java-github-workflows`, `iru-setup-changelog` and `iru-setup-readme` in order. Use when bootstrapping or catching up a Java library repository in one pass."
model: sonnet
---

# Setup Java Library Repository

Bootstrap a brand-new Java/Maven library repository — or catch up an existing one — in one pass by orchestrating six existing skills:
`iru-setup-java-library` (writes `pom.xml` and the `src/main/java|resources`/`src/test/java` folders),
`iru-setup-antora` (scaffolds the Antora documentation site), `iru-setup-java-gitignore` (writes or updates the root
`.gitignore`), `iru-setup-java-github-workflows` (writes `develop.yml`/`main.yml`), `iru-setup-changelog` (bootstraps a
root `CHANGELOG.md`), and `iru-setup-readme` (bootstraps a root `README.md`). This skill does not duplicate their
logic — it collects the shared parameters once and passes each skill only what it needs, so the user isn't asked
the same question repeatedly.

Each of Steps 3–8 invokes its sub-skill via the `iru-isolated-skill-executor` agent rather than calling `Skill(...)`
directly. Every sub-skill already re-derives whatever it needs straight from the filesystem this orchestrator has
just written to (as each step below notes), so this orchestrator only ever needs a short completion summary back
— nothing is lost by keeping each sub-skill's own exploration, file reads, and internal reasoning out of this
orchestrator's own context. Without this, all six sub-skills' full transcripts would accumulate in one
conversation over the course of a single bootstrap run.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Resolve inputs

This skill can be invoked stand-alone (`/iru-setup-java-library-repository`) or driven by another orchestrator, so
parse `args` first, as `key: value` lines, one per line, e.g.:

```
mode: new
open-source: yes
publish: yes
sonar: cloud
sonar-organization: example-github
sonar-project-key: example_my-library
sonar-host-url: https://sonarcloud.io
visibility: public
ci: yes
ci-tests: full
pages: yes
on-existing: ask
```

Recognized keys: `mode` (`new` or `existing`), `open-source` (`yes`/`no`), `visibility` (`public`/`private`/
`internal`), `ci` (`yes`/`no`), `ci-tests` (`unit`/`full`), `pages` (`yes`/`no`), `publish` (`yes`/`no`), `sonar`
(`cloud`/`self-hosted`/`none`), and — only when `sonar` is `cloud` or `self-hosted` — `sonar-organization`,
`sonar-project-key`, `sonar-host-url`, plus `on-existing` (`ask`/`keep`/`update`/`replace` — how every component
sub-skill treats prior setup it finds; scripted runs only). Every key found here is resolved — skip the matching
question in Step 2. Only keys genuinely missing from `args` still need asking. If `args` is absent or doesn't look
like this format, treat everything as unset and ask normally. Never ask `on-existing` yourself: if it didn't come in
via `args`, don't forward it — each sub-skill then asks per artifact (default `ask`).

If `mode` wasn't supplied via `args`, don't ask about it as an isolated question — check whether `pom.xml` already
exists at the repository root: if it does, default to `existing` and confirm with `AskUserQuestion` (`existing`
pre-selected; `new` as the other option, noting that "new" treats the repository as blank — `iru-setup-java-library`
will still warn and ask before touching the existing `pom.xml`); if it doesn't, default to `new` without asking
further. Even in `mode: new`, any other owned artifact already on disk (below) means that component is an update.

### Setup mode announcement

Right after resolving `mode`, before asking anything else, survey the components this run owns and print a short
**Setup mode** statement:

- `mode: new` and nothing below present → "Setting up this repository **from scratch** — no prior setup found."
- Otherwise → "**Updating an existing repository** — prior setup found:" followed by an inventory table with one
  row per component, each marked **absent / present / partial** with the evidence (file paths):

  | Component | Evidence to check |
  |---|---|
  | Build manifest | `pom.xml` |
  | Source layout | `src/main/java/`, `src/main/resources/`, `src/test/java/` |
  | Sonar config | `sonar-maven-plugin`/`sonar.*` properties in `pom.xml`, a stray `sonar-project.properties` |
  | Antora docs | `docs/antora.yml`, `docs/antora-playbook.yml`, `docs/modules/ROOT/` (partial if only some exist) |
  | `.gitignore` | `.gitignore` |
  | Workflows | each of `.github/workflows/develop.yml`, `main.yml`, `sync.yml` (+ `.github/scripts/sync_versions.py`; partial if only one of the pair), `security.yml` |
  | Dependabot | `.github/dependabot.yml` |
  | Changelog | `CHANGELOG.md` |
  | README | `README.md` |
  | License | `LICENSE` / `LICENSE.txt` / `LICENSE.md` (not written by this run — listed so a mismatch with the chosen license is visible) |

  Then: "For each component that already exists you'll be asked whether to keep it, update it, or replace it." (If
  `on-existing` came in via `args`, say instead which choice will be applied to every existing component.)

The asking itself happens inside each sub-skill (Steps 3–8) — this orchestrator never asks a keep/update/replace
question of its own, so nothing is asked twice.

## Step 1 — Collect the project's identity

Ask the user directly (plain conversation — these are free-text project-identity fields, not a bounded choice):

- **groupId** — e.g. `com.example`.
- **artifactId** — e.g. `my-library`.
- **Base package** — the library's base Java package (e.g. `com.example.mylibrary`); need not equal `groupId`.
- **Developer name**, **developer email**, **organizationUrl**.

Then resolve the **license** with `AskUserQuestion` (mirrors `iru-setup-java-library`'s own choice, resolved once
here so it isn't asked again):

- Apache License 2.0 (recommended) — `The Apache License, Version 2.0` /
  `http://www.apache.org/licenses/LICENSE-2.0.txt`
- MIT License — `The MIT License` / `https://opensource.org/license/mit`
- No license (proprietary / all rights reserved)
- Other — ask for the license's display name and URL directly afterward

Leave `version` unset here — `iru-setup-java-library` applies its own `1.0.0-SNAPSHOT` default and isn't worth asking
about twice at this level.

## Step 2 — Collect the pipeline parameters

Ask the user directly, presenting each with its default so a plain "yes"/blank reply accepts it:

| Parameter | Default           |
|---|-------------------|
| Integration branch | `develop`         |
| Java version | `21`              |
| Publishing server id | `central`         |
| Extras profile id | `build-extras`    |
| Sign profile id | `sign`            |
| Maven settings file | `mvnsettings.xml` |

These map directly onto `iru-setup-java-github-workflows`'s own six placeholders — same names, same defaults — so
there's nothing to translate before Step 4. The Java version additionally feeds `iru-setup-java-library` in Step 3
(as its `java-version` arg, whose default is the same `21`), so `pom.xml`'s `maven.compiler.source`/`target` and
the JDK the workflows set up always agree.

Then resolve the remaining choices with `AskUserQuestion`, in this order, skipping any already resolved by Step 0's
`args`:

| Parameter | Default |
|---|---|
| Open source (`open-source`) | asked directly, no default |
| Visibility (`visibility`) | detected via `gh repo view`; otherwise asked, `public` recommended when open source, else `private` |
| CI workflows (`ci`) | asked, `yes` recommended (with the minutes budget stated for private repositories) |
| Sonar (`sonar`) | `cloud` when open source **and** public; otherwise asked, `none` recommended |
| CI test scope (`ci-tests`) | only when `ci: yes`; asked, `full` recommended for public, `unit` for private |
| GitHub Pages (`pages`) | only when `ci: yes`; `yes` without asking for public; asked for private, `no` recommended |
| Publish to Maven Central (`publish`) | `yes` when open source; otherwise asked, `no` recommended |

When stating costs, say "per GitHub's current pricing" and tell the user to double-check their plan.

- **open-source** — is this repository open source? Yes / No. This answer drives the recommended defaults for the
  questions below, per this catalog's shared convention.
- **visibility** — if the repository has a GitHub remote, run `gh repo view --json visibility -q .visibility`
  (returns `PUBLIC`/`PRIVATE`/`INTERNAL`; lowercase it) and tell the user the detected value; otherwise (no remote
  yet, `gh` unavailable, not GitHub) ask **Public** / **Private**, recommending Public when open source, otherwise
  Private. If open-source is Yes but visibility is `private`/`internal`, warn that this is contradictory (an
  open-source project's repository is normally public; a private repository forfeits the free-for-public pricing
  below) and ask which is right. `internal` behaves like `private` for every cost decision.
- **ci** — "Generate GitHub Actions workflows (CI/CD) for this repository?" Public: recommend **Yes** — standard
  GitHub-hosted runners are free for public repositories. Private/internal: still offer Yes first, but state that
  the repository consumes the account's included minutes (2,000/month on GitHub Free, 3,000 on Pro and Team, 50,000
  on Enterprise Cloud), then per-minute billing — this pipeline runs on Linux runners, the cheapest rate (about
  $0.006/minute for 2-core; Windows ~1.7×, macOS ~10× aren't used here). Offer **No** as "no workflows — build,
  test, and release locally (re-run `iru-setup-java-github-workflows` later to add them)". `ci: no` skips Step 6
  entirely (no `.github/workflows/*`, no `security.yml`, no `.github/dependabot.yml`, no sync script) and skips the
  `ci-tests`/`pages` questions.
- **sonar** — "Configure SonarCloud / SonarQube and send CI analysis and coverage reports to it?" Recommend
  **SonarCloud** (`cloud`) only when open-source is Yes **and** visibility is public; otherwise state that
  SonarCloud (SonarQube Cloud) is free only for public/open-source projects (a private project needs a paid plan)
  and recommend **None** (`none`), offering **self-hosted SonarQube** as the second option (Community Build is free,
  but you run the server). When `ci: no`, say plainly that nothing would send reports automatically — analysis
  would only run when someone runs `mvn sonar:sonar` manually with a token — and recommend `none`; if the user still
  picks cloud/self-hosted, only the `pom.xml` side is configured (Step 3) and the report says no CI step sends
  reports. When `cloud` or `self-hosted` is chosen, also
  collect `sonar-organization`/`sonar-project-key`/`sonar-host-url` the same way `iru-setup-java-library`'s own
  Step 2 does (SonarCloud: suggest `<owner>-github` / `<owner>_<repo>` / `https://sonarcloud.io`; self-hosted: ask
  for `sonar.host.url` directly, plus `sonar.organization` only if that server has organizations enabled, and
  `sonar.projectKey`).
- **ci-tests** (only when `ci: yes`) — "Which tests should CI run?" **Unit only** (`unit`) — "Surefire `*Test` unit
  tests only (cheaper: fewer minutes, faster feedback); Failsafe `*IT` integration tests stay local" — recommended
  for private/internal repositories; **Full** (`full`) — "Surefire unit tests plus Failsafe `*IT` integration tests
  (complete verification, uses more minutes)" — recommended for public repositories.
- **pages** (only when `ci: yes`) — public: default **yes** without asking (state it in Step 9's report).
  Private/internal: ask — **No** (Recommended) — "CI still builds the Antora docs and Maven site report and uploads
  them as a workflow artifact; nothing is published" — vs **Yes** — "requires GitHub Pro, Team, or Enterprise Cloud
  (not GitHub Free); the site will be PUBLIC on the internet unless the organization is on Enterprise Cloud with
  private Pages access control".
- **publish** (Maven Central) — default **Yes** when open-source is Yes; when not open source, ask with **No**
  recommended, explaining that Maven Central is meant for publishing redistributable artifacts other projects
  depend on, which usually isn't appropriate for closed-source code.

These values are forwarded only to the sub-skills that accept them: `open-source`/`publish`/`sonar` (+ detail
keys) to Step 3 (`iru-setup-java-library`, together with `visibility` and `ci`) and Step 6
(`iru-setup-java-github-workflows`, together with `visibility`/`ci-tests`/`pages`/`mode`), `sonar`/`ci`/`pages` to
Step 8 (`iru-setup-readme`), and `on-existing` — only when it came in via Step 0's `args` — to every component
sub-skill that writes files (Steps 3–8). No sub-skill re-asks any of them.

## Step 3 — Run `iru-setup-java-library`

Always invoke `iru-setup-java-library`, whatever `mode` is — it no longer gets skipped when `pom.xml` exists. If
`pom.xml` is already there, `iru-setup-java-library` itself announces the update, warns, and asks whether to Update
it (merge), Keep it as is (what the old `mode: existing` skip used to do — only missing source folders get created), Replace it
from its template, or Stop (unless a forwarded `on-existing` decides). Don't ask that question here as well. Format
Step 1's answers — plus the Java version and the `open-source`/`publish`/`sonar` (+ detail keys) answers from Step
2, so `pom.xml`'s compiler level and the CI pipeline Step 6 generates can't end up targeting different Java versions
or disagreeing about publishing/Sonar, and `visibility`/`ci` so its Sonar handling matches — as `key: value` lines
(add `on-existing: <value>` only if it came in via Step 0's `args`) and invoke via `iru-isolated-skill-executor`:

```
Agent({
  description: "Run setup-java-library",
  subagent_type: "iru-isolated-skill-executor",
  prompt: "Invoke Skill({skill: \"iru-setup-java-library\", args: \"groupId: <group-id>\\nartifactId: <artifact-id>\\n
    package: <base-package>\\ndeveloper-name: <developer-name>\\ndeveloper-email: <developer-email>\\n
    organization-url: <organization-url>\\nlicense: <license-name, or 'none'>\\n
    java-version: <java-version>\\nopen-source: <open-source>\\npublish: <publish>\\nsonar: <sonar>\\n
    sonar-organization: <sonar-organization, if set>\\nsonar-project-key: <sonar-project-key, if set>\\n
    sonar-host-url: <sonar-host-url, if set>\\nvisibility: <visibility>\\nci: <ci>\\n
    on-existing: <on-existing, only if supplied via args>\"}). Report back: whether pom.xml was created, updated,
    replaced, kept as is, or the user chose to stop (and any mismatch it listed for a kept file), which source
    folders were created vs already present, and any value it resolved on its own (e.g. version, repository info)
    worth noting in the final summary.",
  run_in_background: false
})
```

`iru-setup-java-library` parses these itself (its own Step 0) and only asks the user about anything genuinely left
out (e.g. `version`, or repository info it infers from git directly) — `AskUserQuestion` surfaces to the user the
same way whether invoked directly or from inside this sub-agent. If it reports that `pom.xml` already existed and
the user chose to stop, stop this skill here too — there's no coherent project identity to build Antora docs or
CI workflows around yet. If the user chose Keep, continue: the existing `pom.xml` is presumed valid enough for the
rest of the pipeline to build against, and its mismatches go into Step 9's report.

## Step 4 — Run `iru-setup-antora`

Invoke via `iru-isolated-skill-executor`: `Agent({description: "Run setup-antora", subagent_type:
"iru-isolated-skill-executor", prompt: "Invoke Skill({skill: \"iru-setup-antora\"}) with no args (or, only if Step 0's args carried it,
args \"on-existing: <on-existing>\") — it derives the Antora component name, title, and version straight from
the repository's pom.xml. Report back: whether it set up from scratch or updated an existing site, the
keep/update/replace choice made for an existing site, which files/pages were created vs. already present, and
whether the site build succeeded.", run_in_background: false})`. If `docs/antora.yml`/the playbook/module already
exist, `iru-setup-antora` itself warns and asks Update (fill gaps) / Keep / Replace — don't ask here too.

Run this *before* Step 6, not after, even though the user described these two in the other order: `iru-setup-antora`
is quick to detect as "already done" and `iru-setup-java-github-workflows`'s own survey (Step 1 there) checks whether
`docs/antora.yml`/`docs/antora-playbook.yml` already exist — running Antora setup first means that check finds
everything in place instead of flagging a gap it would otherwise ask about.

## Step 5 — Run `iru-setup-java-gitignore`

Invoke via `iru-isolated-skill-executor`: `Agent({description: "Run setup-java-gitignore", subagent_type:
"iru-isolated-skill-executor", prompt: "Invoke Skill({skill: \"iru-setup-java-gitignore\"}) with no args (or, only if Step 0's args carried
it, args \"on-existing: <on-existing>\" — the only key it accepts), deriving everything else it needs by
exploring the repository directly. Report back only whether .gitignore was created, updated, replaced, or kept as
is, and what categories of entries it added.", run_in_background: false})`. Run this after
Steps 3–4, not before: `iru-setup-java-gitignore` detects the build tool and any generated build-info file from the
`pom.xml` Step 3 just wrote, and detects the Antora docs build output from the `docs/antora-playbook.yml` Step 4
just scaffolded — running it earlier would miss both and produce a thinner `.gitignore` than the repository's
actual shape supports. Run it *before* Step 6 (`iru-setup-java-github-workflows`), so `target/`, `.idea/`, and the
Antora build output are already ignored before CI config and any generated reports show up locally. In `mode: new`,
`.gitignore` typically won't already exist, so `iru-setup-java-gitignore`'s own approval step is skipped and it
writes directly. If it already exists — expected in `mode: existing`, not an error — `iru-setup-java-gitignore`
warns, shows the proposed diff, and asks Update / Keep / Replace itself (unless a forwarded `on-existing`
decides); this orchestrator doesn't ask about it.

## Step 6 — Run `iru-setup-java-github-workflows`

**Skip this step entirely when `ci: no`** (Step 2): don't invoke the sub-skill, write no `.github/workflows/*`,
`security.yml`, `.github/dependabot.yml`, or sync script, and leave any existing workflow files untouched. Record
for Step 9 that the workflows — and with them the shared security block (Dependabot, dependency-review, CodeQL,
OSV-Scanner, gitleaks) — were **skipped (ci: no)**, and that running `/iru-setup-java-github-workflows` later adds
them.

Otherwise, format Step 2's answers, plus the `groupId`/`artifactId` already collected in Step 1, `visibility`,
`ci-tests`, `pages`, `mode`, and (only if it came in via Step 0's `args`) `on-existing`, as `key: value` lines and
invoke via `iru-isolated-skill-executor`:

```
Agent({
  description: "Run setup-java-github-workflows",
  subagent_type: "iru-isolated-skill-executor",
  prompt: "Invoke Skill({skill: \"iru-setup-java-github-workflows\", args: \"integration-branch: <integration-branch>\\n
    java-version: <java-version>\\npublishing-server-id: <publishing-server-id>\\n
    extras-profile-id: <extras-profile-id>\\nsign-profile-id: <sign-profile-id>\\n
    settings-file: <settings-file>\\ngroup-id: <group-id>\\nartifact-id: <artifact-id>\\n
    open-source: <open-source>\\npublish: <publish>\\nsonar: <sonar>\\n
    sonar-organization: <sonar-organization, if set>\\nsonar-project-key: <sonar-project-key, if set>\\n
    sonar-host-url: <sonar-host-url, if set>\\nvisibility: <visibility>\\nci-tests: <ci-tests>\\n
    pages: <pages>\\nmode: <mode>\\non-existing: <on-existing, only if supplied via args>\"}). Report back: per
    file (develop.yml/main.yml/sync.yml/security.yml/.github/dependabot.yml) whether it was created, updated,
    replaced, or kept as is (with any mismatch it listed), or whether the user chose to stop, the required GitHub
    secrets it listed, and any open gap it flagged (missing Central plugin, missing signing profile, missing Antora
    setup, README/Antora wording mismatch, ci-tests having no effect without Failsafe executions, etc.).",
  run_in_background: false
})
```

Passing `open-source`/`publish`/`sonar` (+ detail keys) through here keeps this workflow generation in agreement
with what Step 3 wrote into `pom.xml` — e.g. it never generates a `Deploy to maven central` step for a `pom.xml`
that has no `central-publishing-maven-plugin`, or a `Run SonarCloud analysis` step for one with no
`sonar-maven-plugin`.

Passing `group-id`/`artifact-id` through here lets `iru-setup-java-github-workflows` skip re-reading them from
`pom.xml` in its own Step 1, since this orchestrator already collected both in its own Step 1.
`iru-setup-java-github-workflows` parses these itself (its own Step 1) and only surveys/asks about facts these
parameters don't cover (static-analysis plugins, SonarQube config, branch-model confirmation, etc.); per existing
workflow file it asks Update / Keep / Replace itself (its Step 2), so don't ask here. If it reports that the user
chose to stop, note that in Step 9's report rather than
treating it as a failure of this skill — `pom.xml`, the Antora docs, and the `.gitignore` from Steps 3–5 are still
valid on their own.

## Step 7 — Run `iru-setup-changelog`

Invoke via `iru-isolated-skill-executor`: `Agent({description: "Run setup-changelog", subagent_type:
"iru-isolated-skill-executor", prompt: "Invoke Skill({skill: \"iru-setup-changelog\"}) with no args (or, only if Step 0's args carried it,
args \"on-existing: <on-existing>\"), working entirely from the repository's own git tag/GitHub Release history.
Report back only whether CHANGELOG.md was created (and the version range reconstructed), updated (releases
appended), replaced, or kept as is.", run_in_background: false})`.
Run this before `iru-setup-readme` (Step 8): it's independent of `pom.xml`, the Antora docs, the `.gitignore`, and the
workflows, so its position relative to them doesn't matter functionally. In `mode: new` (no tags yet) it's the
fastest to resolve — it either bootstraps a minimal `## [Unreleased]`-only file or stops per the user's choice, per
its own Step 2. In `mode: existing`, real tag/release history may already exist, so it can backfill more than a
placeholder. If `CHANGELOG.md` already exists, it warns and asks Keep (leave it) / Update (append missing
releases, never rewriting existing entries) / Replace (rebuild from history) itself, regardless of `mode` — don't
ask here; a Keep is not a failure of this skill, just something to note in Step 9's report. Running it before `iru-setup-readme` means the README's own Documentation section (which links to
`CHANGELOG.md` if present) sees it already in place.

## Step 8 — Run `iru-setup-readme`

Invoke via `iru-isolated-skill-executor`: `Agent({description: "Run setup-readme", subagent_type:
"iru-isolated-skill-executor", prompt: "Invoke Skill({skill: \"iru-setup-readme\", args: \"sonar: <sonar>\\nci: <ci>\\npages: <pages, or no when ci: no>\\non-existing: <on-existing, only if supplied via args>\"}) — with `sonar: none` it suppresses Sonar badges/links even if Sonar config exists on disk, with `ci: no` it omits CI badges, and with `pages: no` it doesn't link a github.io documentation site — deriving
everything else it needs by exploring the repository directly (pom.xml, the Antora docs, .gitignore, the workflows and
Sonar config, and CHANGELOG.md). Report back only whether README.md was created, updated, replaced, or kept as is,
and which sections were included vs. omitted and why.",
run_in_background: false})`. Run this last, after every other skill: `iru-setup-readme`'s badges, documentation links,
and project-status table are only as complete as what already exists on disk when it runs, so giving it the
finished state of Steps 3–7 to explore produces a fuller README than running it earlier would. In `mode: new`,
most of the CI/Sonar/changelog-derived sections will still be sparse or absent at this point (no commits/tags/CI
runs yet) — that's expected; `iru-setup-readme` omits what it can't confirm rather than inventing it, and the
README can be regenerated later once the repository has real history. In `mode: existing`, real history typically
already exists, so more sections should populate. `README.md` may or may not already exist depending on `mode`;
`iru-setup-readme` announces scratch vs update and, for an existing file, shows the diff and asks Update / Keep /
Replace itself — nothing further to confirm here.

## Step 9 — Report

Summarize the outcome of all six delegated skills together: the resolved project identity and license, the
pipeline parameters used, and the setup mode — repeat Step 0's statement (from scratch, or updating an existing
repository) — then a per-component table (build manifest, source layout, Antora docs, `.gitignore`, each workflow
file — `develop.yml`/`main.yml`/`sync.yml`+script/`security.yml` — `.github/dependabot.yml`, `CHANGELOG.md`,
`README.md`) with the action actually taken: **created / updated / replaced / kept as is / skipped (ci: no)** (or
stopped, per a stop choice in Step 3 or 6), plus any mismatch a sub-skill listed for a kept file, and the required
GitHub secrets `iru-setup-java-github-workflows` listed (none when `ci: no`).

State explicitly which of Maven Central publishing and Sonar analysis were wired, or deliberately omitted and why:

- **`open-source`** / **`visibility`**: as resolved in Step 2 (and where visibility came from — `args`, `gh repo
  view`, or the user).
- **`ci`**: yes/no — if `no`, state that no workflows were generated, that the shared security block (Dependabot,
  dependency-review, CodeQL, OSV-Scanner, gitleaks) was skipped with them, and that `/iru-setup-java-github-workflows`
  adds them later. For a private/internal repository with `ci: yes`, repeat the included-minutes note (per
  GitHub's current pricing — double-check your plan).
- **`ci-tests`**: `unit` (CI runs `-DskipITs`, integration tests stay local) or `full` — omitted when `ci: no`.
- **`pages`**: `yes` (defaulted for a public repository, or chosen) / `no` (docs uploaded as a workflow artifact,
  no github.io site) — omitted when `ci: no`.
- **`publish`**: whether `central-publishing-maven-plugin`/the `sign` profile were included in `pom.xml` and the
  `Deploy to maven central` step in the workflows — `yes` unless the user opted out (or the project isn't open
  source and declined it), in which case say so plainly rather than leaving it implicit.
- **`sonar`**: `cloud`/`self-hosted`/`none`, and whether the `sonar.*` properties/`sonar-maven-plugin` and the
  `Run SonarCloud analysis` workflow step were included — if `none`, note that this is expected default behavior
  for a private or non-open-source project (SonarCloud requires a paid plan otherwise) unless the user explicitly
  asked for self-hosted SonarQube instead. If Sonar was configured with `ci: no`, say that no CI step sends
  reports — analysis only runs when someone runs `mvn sonar:sonar` manually.

Note which README sections `iru-setup-readme` omitted for lack of material (expected in `mode: new`, or for a
repository still short on history). Repeat its closing warning here too: **review every generated file before
building, committing, or relying on CI** — `mvn validate` should succeed, the license should match an actual
`LICENSE` file if one was chosen, the workflow's inferred branch/profile/publishing values should be double checked
before the first real push or release, and the README's "How It Works" example should be verified once real source
code exists.
