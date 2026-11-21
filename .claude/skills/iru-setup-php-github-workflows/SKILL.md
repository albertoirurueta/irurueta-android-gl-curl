---
name: iru-setup-php-github-workflows
description: "Invoked as /iru-setup-php-github-workflows with optional key: value args (flavor, branches, ci-tests, pages, distribution, sonar, security-*, on-existing), usually by iru-setup-php-repository. Creates or updates build, release, sync and security workflows plus Dependabot for a Laravel, WordPress plugin/theme or PrestaShop module repository."
model: sonnet
allowed-tools: Read Write Edit AskUserQuestion Bash(gh *) Bash(git *) Bash(curl *) Bash(docker *) Bash(actionlint *) Bash(python3 *) Bash(composer *) Bash(php *) Bash(npm *) Bash(npx *) Bash(mkdir *) Bash(ls *) Bash(cat *) Bash(grep *) Bash(find *) Bash(sed -n *) Bash(chmod *)
---

# Setup PHP GitHub Workflows

Scaffold (or update) the CI/CD and security pipeline for a PHP repository:

| File | Trigger | Does |
|---|---|---|
| `.github/workflows/build.yml` | push to `<integration-branch>`/`<stable-branch>`, pull requests to them, `workflow_dispatch` | validate + audit, static analysis, style, fast tests with coverage on a PHP matrix, integration (`ci-tests: full`), optional Sonar, docs (Pages from `<stable-branch>` or artifact) |
| `.github/workflows/release.yml` | GitHub Release published — **not written with `distribution: none`** | WordPress/PrestaShop: release zip attached to the release (+ WordPress.org deploy / Addons instructions with `store`); Laravel: container image to GHCR |
| `.github/workflows/sync.yml` + `.github/scripts/php_version.py` + `.github/scripts/sync_versions.py` | GitHub Release published from `<stable-branch>` | PR merging the release back into `<integration-branch>` with the next `-dev` version |
| `.github/workflows/security.yml` + `.github/psalm/*` | pushes, pull requests, weekly | dependency review, CodeQL (`actions` + JS/TS when present), Psalm taint analysis (SARIF), OSV-Scanner, gitleaks |
| `.github/dependabot.yml` | — | weekly grouped updates for Composer, the actions, npm (when present), the Laravel `Dockerfile`, the docs toolchain |

Used by `iru-setup-php-repository`; also runs standalone, where it resolves `visibility` itself and asks `sonar`/`ci-tests`/`pages`/`distribution` with visibility-aware cost notes (it never asks `ci`).

It fits the catalog's release flow: `iru-release` cuts `release_x.y.z` from `<integration-branch>`, bumps the version
with `iru-php-bump-version` (`x.y.z-dev` → `x.y.z`, `Stable tag:` included for WordPress), and opens a PR into
`<stable-branch>`; merging it runs `build.yml` there (docs to Pages); publishing the GitHub Release (tag `x.y.z`) runs
`release.yml` and `sync.yml` (next `x.(y+1).0-dev` back on `<integration-branch>`).

Every action reference uses the major (or, where the publisher has no floating major tag, the exact release) current
when this skill was written, checked against the GitHub API: `actions/checkout@v7`, `shivammathur/setup-php@v2`,
`actions/cache@v6`, `actions/setup-node@v7`, `actions/setup-python@v7`, `actions/upload-artifact@v7`,
`actions/download-artifact@v8`, `actions/configure-pages@v6`, `actions/upload-pages-artifact@v5`,
`actions/deploy-pages@v5`, `SonarSource/sonarqube-scan-action@v8`, `10up/action-wordpress-plugin-deploy@2.3.0` (no
major tag exists; its README's `@stable` is a moving branch), `docker/setup-buildx-action@v4`,
`docker/login-action@v4`, `docker/metadata-action@v6`, `docker/build-push-action@v7`, `github/codeql-action/*@v4`,
`actions/dependency-review-action@v5`, `google/osv-scanner-action/...@v2.7.0` (that repository publishes **no** `v2`
ref — `@v2` does not resolve), `gitleaks/gitleaks-action@v3`. When updating an old pipeline re-check them —
`gh api repos/<owner>/<repo>/tags` (not `releases/latest`, which returns `codeql-bundle-*` for CodeQL) or
`git ls-remote --tags https://github.com/<owner>/<repo>`; Dependabot's `github-actions` entry keeps them current
afterwards.

**Templates.** The workflow, script, Psalm and Dependabot templates of Step 3 are files under this skill's `templates/` directory, not inline text. Read only the template files for the `flavor`/`distribution`/`ci-tests` variant resolved in Step 1 (the index at the top of Step 3 says which applies); never read the others.

## Step 1 — Resolve inputs and survey the repository

Parse `args` first (`key: value` lines); every key found is resolved and its survey/question below is skipped:

```
flavor: wordpress-plugin
integration-branch: develop
stable-branch: main
php-version: 8.3
open-source: yes
visibility: public
ci-tests: full
pages: yes
distribution: store
sonar: cloud
sonar-organization: example-github
sonar-project-key: example_acme-loans
sonar-host-url: https://sonarcloud.io
mode: new
on-existing: ask
```

Ignore any other key (`ci` included — invoking this skill means CI is wanted). `mode` is informational; `on-existing`
pre-answers Step 2. The `security-*` keys (`security-dependency-review`/`security-codeql`/`security-taint`/`security-osv`/`security-gitleaks`, each `yes`/`no`) default to `yes`.

Then survey what isn't resolved:

- **Flavor** — the same markers as `iru-php-code-quality` (first match wins): `laravel/framework` in `composer.json`
  `require` + `artisan` → `laravel`; a root `*.php` with a `Plugin Name:` header → `wordpress-plugin`; `style.css`
  with `Theme Name:` → `wordpress-theme`; a root class that `extends Module`/`PaymentModule`/`CarrierModule` →
  `prestashop-module`. Anything else (a plain Composer library) is outside this skill's templates: say so and ask
  (`AskUserQuestion`) **Stop** / **Continue anyway** (every flavor-specific step becomes an open gap).
- **`<slug>`** — WordPress: the main plugin file's basename / the theme's text domain (the WordPress.org slug);
  PrestaShop: `$this->name` (the module's technical name, which is also its directory name in a shop); Laravel: the
  `composer.json` `name` after the `/` (the image name). Confirm when ambiguous.
- **Composer scripts** — read `composer.json` `scripts`. The templates call `test:coverage`, `test:integration`,
  `lint`, `lint:report`, `format:check` (and PrestaShop's `package`, `shop:up`, `shop:install`, `shop:down`,
  `lint:shop`) exactly as the `iru-setup-php-*` scaffolds define them. A missing script is never invented in the
  workflow: drop the step that would call it and list the gap in Step 7 (e.g. no `test:integration` → no integration
  job; no `lint:report` → Sonar gets no PHPStan import).
- **PHP versions** — `<php-floor>` = `php-version` from `args`, else `composer.json` `config.platform.php`
  (major.minor), else the lowest version `require.php` allows. `<php-latest>` and the matrix come from php.net's
  supported branches: `curl -s https://www.php.net/releases/active.php` (JSON; its keys under `"8"` are the branches
  still receiving fixes — `8.2`…`8.5` when this skill was written). `<php-matrix>` = every supported branch from
  `<php-floor>` to `<php-latest>`, quoted (`'8.3', '8.4', '8.5'`). Offline → ask, offering floor..`8.5`.
- **WordPress** — `<wp-floor>` from the main file's / `style.css`'s `Requires at least:` header (major.minor);
  `<wp-content-dir>` = `plugins` or `themes`. Confirm `.wp-env.test.json` and the npm scripts `test:env:start`/
  `test:integration` exist (the scaffold's dedicated test environment); without them the integration job is an open
  gap. Current WordPress: `curl -s https://api.wordpress.org/core/version-check/1.7/` (`offers[0].version`).
- **PrestaShop** — `<ps-versions>`: the majors in `ps_versions_compliancy` whose images can run `<php-floor>` —
  PrestaShop 8.x supports PHP ≤ 8.1, so with a floor above 8.1 only `'9'` is tested (say so); `'8', '9'` otherwise.
  Confirm `tests/Integration/docker-compose.yml` and `bin/package.sh` exist.
- **Laravel** — the integration database from `phpunit.integration.xml`/the scaffold's choice (`mysql` or `pgsql`);
  with `distribution: internal`, the `Dockerfile`: one final stage (FrankenPHP → one image) or two targets `app` +
  `web` (PHP-FPM + Nginx → two images, `<slug>` and `<slug>-web`).
- **JS/TS present** — a `package.json` with sources to analyse (WordPress blocks, Laravel Vite `resources/js`) →
  CodeQL also runs `javascript-typescript`; a WordPress `package.json` that only carries `@wordpress/env` adds nothing
  to analyse but still gets a Dependabot `npm` entry.
- **Docs** — `docs/antora.yml` + `docs/antora-playbook.yml` + `docs/package-lock.json` (`iru-setup-antora`); missing →
  recommend running it first (the docs job would have nothing to build). phpDocumentor only when `phpdoc.dist.xml`/
  `phpdoc.xml` exists — never add one here.
- **Branches** — confirm against `git branch -a` (or ask); `sync.yml` needs both.
- **README/Antora wording** for `sync_versions.py` — `grep -n -i "current development version\|latest release"
  README.md`; note deviations from the default row labels.

**Cost-aware choices** (only those still unresolved, in this order, `AskUserQuestion`; prices per GitHub's/
SonarSource's current pricing — tell the user to double-check their plan):

1. **`visibility`** — `gh repo view --json visibility -q .visibility` (lowercased) when there's a GitHub remote;
   otherwise ask **Public** / **Private** (Public recommended when open source). Open source + private is
   contradictory — say so. `internal` = `private`. Public repositories run standard runners free; private ones
   consume included minutes (2,000/month Free, 3,000 Pro/Team, 50,000 Enterprise Cloud), then ~$0.006/min Linux.
   Everything here is Linux; the PHP matrix multiplies the test job, and the integration jobs (wp-env downloads
   WordPress, a PrestaShop auto-install) are the expensive part.
2. **`sonar`** — "Configure SonarCloud / SonarQube and send CI analysis and coverage reports to it?" — **SonarCloud**
   only for a public open-source repository; otherwise **None** recommended, **self-hosted** second.
3. **`ci-tests`** — **Unit only** (`unit`; recommended for private) — "fast suites only (Pest/PHPUnit on SQLite
   `:memory:`, Brain Monkey, PrestaShop unit tests): no containers". **Full** (`full`; recommended for public) — "also
   the integration job: Laravel against MySQL/PostgreSQL, WordPress in wp-env on the floor and latest WordPress,
   PrestaShop in a real shop — complete verification, considerably more minutes".
4. **`pages`** — public: `yes` without asking. Private: **No (Recommended)** — "docs still built, uploaded as an
   artifact" — vs **Yes** — "needs Pro/Team/Enterprise Cloud; the site is public unless Enterprise Cloud private
   Pages".
5. **`distribution`** — WordPress/PrestaShop: **none** / **internal** (zip attached to the GitHub Release) / **store**
   (WordPress: zip + WordPress.org deploy — needs an approved slug, a directory account, and a GPL-compatible
   license; PrestaShop: zip + Addons upload instructions); default `store` for open-source WordPress, else `none`.
   Laravel: **none** / **internal** (image to GHCR — free for public repositories, storage/transfer quotas for
   private ones); `store` is never valid for Laravel.

**Accessibility opt-out.** Read the repository's `.ui-quality.yml` `accessibility` level (UI contract §8). When it is
`none` (opted out entirely), emit no accessibility report artifact, upload or step: do not collect `a11y-report.txt`
or other accessibility reports into the `ui-screenshots` artifacts, and drop any accessibility-only job or step;
the UI test run and the screenshot upload are unaffected. With `enforce`, `report` or `off` the workflows are
generated exactly as below (`off` runs no automated checks, so no accessibility report exists to upload either).

## Step 2 — Announce scratch vs update, decide per existing file

Check `build.yml`, `release.yml`, `sync.yml` + its two scripts (one unit — partial if not all exist),
`security.yml` + `.github/psalm/` (one unit), `.github/dependabot.yml`. None → "creating the CI/CD pipeline **from
scratch**". Any → "**updating an existing CI/CD setup**", list each as present/absent/partial, read each existing one,
and name every concrete contradiction with this run's choices ("`build.yml` has an integration job but `ci-tests:
unit`", "it deploys to Pages but `pages: no`", "it has a Sonar job but `sonar: none`", "there's a `release.yml` but
`distribution: none`", "`release.yml` deploys to WordPress.org but `distribution: internal`"). Unless `on-existing`
decides, ask per unit (`AskUserQuestion`, ≤ 4 per call):

- **Update (Recommended)** — merge: add/correct what this skill owns (jobs, gating, action versions, commands), keep
  every other step/job/entry; diff shown before writing.
- **Keep as is** — untouched; mismatches listed in Step 7.
- **Replace** — regenerate from the template; customizations lost (git history keeps them).
- **Stop** (first question only) — end without changing anything.

With `distribution: none`, an existing `release.yml` is offered for removal (ask; never delete silently).

## Step 3 — Templates

**Templates.** Every template below is a file under `templates/<variant>/<path of the generated file>`, holding the exact bytes of that generated file's template. Read only the files for the variant resolved in Step 1, each in full, before filling placeholders:

| Generated file | Template | Applies when |
|---|---|---|
| `.github/workflows/build.yml` | `templates/common/.github/workflows/build.yml` | all flavors (`laravel`, `wordpress-plugin`, `wordpress-theme`, `prestashop-module`; gates inside the template and the gating summary decide what stays) |
| `.github/workflows/build.yml` | `templates/laravel/.github/workflows/build.yml.integration-job` | `ci-tests: full` with `laravel` (job appended under `jobs:` of the build template) |
| `.github/workflows/build.yml` | `templates/wordpress/.github/workflows/build.yml.integration-job` | `ci-tests: full` with `wordpress-plugin` / `wordpress-theme` (job appended under `jobs:` of the build template) |
| `.github/workflows/build.yml` | `templates/prestashop-module/.github/workflows/build.yml.integration-job` | `ci-tests: full` with `prestashop-module` (job appended under `jobs:` of the build template) |
| `.github/workflows/release.yml` | `templates/wordpress/.github/workflows/release.yml` | `wordpress-plugin` / `wordpress-theme` with `distribution: internal \| store` |
| `.github/workflows/release.yml` | `templates/prestashop-module/.github/workflows/release.yml.jobs` | `prestashop-module` with `distribution: internal \| store` (the `jobs:` section; same `on:`/`permissions:` header as the WordPress release template) |
| `.github/workflows/release.yml` | `templates/laravel/.github/workflows/release.yml` | `laravel` with `distribution: internal` (the scaffold's `Dockerfile` to GHCR) |
| `.github/workflows/sync.yml` | `templates/common/.github/workflows/sync.yml` | all flavors (written together with the two scripts below) |
| `.github/scripts/php_version.py` | `templates/common/.github/scripts/php_version.py` | whenever `sync.yml` or a release workflow is written |
| `.github/scripts/sync_versions.py` | `templates/common/.github/scripts/sync_versions.py` | written together with `sync.yml` |
| `.github/workflows/security.yml` | `templates/common/.github/workflows/security.yml` | at least one `security-*` flag is not `no` |
| `.github/psalm/psalm-taint.xml` | `templates/common/.github/psalm/psalm-taint.xml` | `security-taint: yes` (all flavors; `<project-files>`, `<stubs>` and `<globals>` per flavor as described above the template) |
| `.github/psalm/wordpress-taint.phpstub` | `templates/wordpress/.github/psalm/wordpress-taint.phpstub` | `security-taint: yes` with `wordpress-plugin` / `wordpress-theme` |
| `.github/psalm/prestashop-taint.phpstub` | `templates/prestashop-module/.github/psalm/prestashop-taint.phpstub` | `security-taint: yes` with `prestashop-module` |
| `.github/dependabot.yml` | `templates/common/.github/dependabot.yml` | always (no opt-out) |

Resolve every `<placeholder>` from Step 1. Comments marked `# laravel`, `# wordpress`, `# prestashop`,
`# ci-tests: full`, `# pages: yes|no`, `# sonar`, `# store`, `# phpdoc` are gates: keep what applies and **drop the
rest together with these guidance comments** — a step that can never run is dead YAML.

### `build.yml`

Template: `templates/common/.github/workflows/build.yml` — read it in full before filling placeholders

**`ci-tests: full`** — append exactly one of the three `integration` jobs below under `jobs:`. They never count
towards the coverage gate (that's `tests`, fast suites only — the same rule the coding skills' group stage applies).

`laravel` — the scaffold's `Integration` suite (`phpunit.integration.xml` defaults `DB_CONNECTION`/`DB_DATABASE` to
the `testing` database; CI supplies host, port and credentials):

Template: `templates/laravel/.github/workflows/build.yml.integration-job` — read it in full before filling placeholders

`wordpress-plugin` / `wordpress-theme` — the scaffold's `WP_UnitTestCase` suite inside its dedicated wp-env test
environment (`.wp-env.test.json`, never the development site's `.wp-env.json`), on the `Requires at least:` floor
with the PHP floor and on the latest WordPress with the newest PHP. wp-env mounts the repository as
`wp-content/<wp-content-dir>/<directory basename>` and the scaffold's `test:integration` script assumes that basename
is `<slug>` — on a runner the checkout directory is the *repository* name, so the job checks out into `<slug>/`:

Template: `templates/wordpress/.github/workflows/build.yml.integration-job` — read it in full before filling placeholders

`hashFiles()` and `upload-artifact`'s `path` resolve from the workspace root, not from `defaults.run.working-directory`
— hence the `<slug>/` prefix there. The browser tests reuse the environment this job already starts: no extra job,
runner, or WordPress matrix entry.

`prestashop-module` — the scaffold's Docker shop (`tests/Integration/docker-compose.yml`, `PS_VERSION=8|9`): the
`Integration` suite plus PHPStan against the real shop (`lint:shop`, which the stub-based `lint` can't cover), and —
because this job already has a running shop (`PS_DOMAIN` `localhost:${PS_PORT:-8080}`) — the scaffold's Playwright
browser UI tests against it through `PS_BASE_URL` (they skip without a shop):

Template: `templates/prestashop-module/.github/workflows/build.yml.integration-job` — read it in full before filling placeholders

Gating summary for `build.yml`: drop "Prepare the Laravel environment" for WordPress/PrestaShop; drop the
`PHPStan report for Sonar`/`Upload the PHPStan report` steps and the `sonar` job for `sonar: none` (and the
`SONAR_HOST_URL` line unless self-hosted); drop the two phpDocumentor steps and the `api/` merge line unless
`phpdoc.dist.xml` exists; keep exactly one of "Upload Pages artifact"/"Upload documentation" and drop `deploy-docs`
with `pages: no`; drop the whole `docs`/`deploy-docs` pair when there is no Antora site and no phpDocumentor (report
it); with `ci-tests: full` append the flavor's `integration` job (Laravel: keep the service/port/user/extension
alternative for the scaffold's database), keeping its browser-UI-test steps (self-guarded on `playwright.config.*`;
drop them only when the project has no `test:e2e` npm script). With `ci-tests: unit` no job runs the browser UI tests
— they stay local (`npm run test:e2e`, behind `.integration-test.lock`); say so in Step 7.

### `release.yml` (not written with `distribution: none`)

`wordpress-plugin` / `wordpress-theme`, `distribution: internal | store`:

Template: `templates/wordpress/.github/workflows/release.yml` — read it in full before filling placeholders

`prestashop-module`, `distribution: internal | store` (same `on:`/`permissions:` header):

Template: `templates/prestashop-module/.github/workflows/release.yml.jobs` — read it in full before filling placeholders

`laravel`, `distribution: internal` — the scaffold's `Dockerfile` to GHCR:

Template: `templates/laravel/.github/workflows/release.yml` — read it in full before filling placeholders

Notes: the image carries no `.env` — `APP_KEY`, `APP_VERSION`, database credentials are runtime environment
variables (`config('app.version')` reads `APP_VERSION`; `docker/metadata-action` already labels the image with the
version). No image per `<integration-branch>` push — add one only if a deployment pipeline consumes it. WordPress
`Stable tag:` is checked by "Check the version matches the tag": WordPress.org serves whatever tag `Stable tag:` names,
so a release whose readme still names the previous version must fail here, not after the SVN commit. PrestaShop:
never upload a `-dev` zip to a real shop or to Addons — `ps_module.version` is `VARCHAR(8)`, so `1.5.0-dev` is
stored as `1.5.0-de` and the module manager keeps offering an upgrade (only release zips come out of this workflow).

### `sync.yml`

Template: `templates/common/.github/workflows/sync.yml` — read it in full before filling placeholders

The sync branch is `sync_<released version>` (release `1.0.1` → `sync_1.0.1`), so a patch release still gets its own PR. If merging `<stable-branch>` conflicts, the job aborts the merge, points the branch at the release commit and opens the PR titled "merge conflicts to resolve" without bumping, so a conflicting (patch) sync never lowers `<integration-branch>`'s development version.

The bump is always minor (`1.3.0` → `1.4.0-dev`), matching `iru-php-bump-version`'s Step 5 and `iru-release`'s
default "next upcoming version"; flag it in Step 7 if the team releases patches from `<integration-branch>` too.
`CHANGELOG.md` isn't touched — its release section arrives through the merge. PrestaShop: the sync PR's `-dev`
version shows truncated (`1.4.0-de`) in a dev shop's module manager — expected, see the release notes above.

### `.github/scripts/php_version.py`

The exact helper embedded in `iru-php-bump-version`'s Step 2 (keep the two identical — the release workflows'
version check and `sync.yml` both rely on its `--list` output format `<file>:<line>: <value>    [<location>]`):

Template: `templates/common/.github/scripts/php_version.py` — read it in full before filling placeholders

### `.github/scripts/sync_versions.py`

Template: `templates/common/.github/scripts/sync_versions.py` — read it in full before filling placeholders

### `security.yml`

The shared security block every workflows skill in this catalog embeds, adapted to PHP: **CodeQL has no PHP
extractor**, so CodeQL covers the workflows themselves (`actions`) and any JS/TS, and Psalm's taint analysis is the
PHP code-scanning substitute — its SARIF lands in the same Security → Code scanning view. Drop a job's whole entry
when its `security-*` flag is `no`; if all are `no`, don't write the file (say so in Step 7).

Template: `templates/common/.github/workflows/security.yml` — read it in full before filling placeholders

`security-taint` gates `psalm-taint` (and the `.github/psalm/` files); `security-codeql: no` drops `codeql`, and with
no JS/TS the matrix is just `[ actions ]`. OSV-Scanner reads `composer.lock` (plus `package-lock.json`s) — no setup.

**`.github/psalm/psalm-taint.xml`** — a dedicated config so the project's own Psalm setup (if any) isn't touched;
`resolveFromConfigFile="false"` makes paths relative to the repository root. `<project-files>` per flavor:
Laravel `<directory name="app"/>` (+ `routes`); WordPress plugin `<file name="<slug>.php"/>`, `<directory
name="src"/>` (+ `includes`/`admin`/`public` when present) and `uninstall.php`; theme `functions.php`, `src`,
`patterns`; PrestaShop `<file name="<slug>.php"/>`, `src`, `controllers`, `upgrade`. Drop the `<stubs>` element for
Laravel (PrestaShop points it at `prestashop-taint.phpstub`); `<globals>` is WordPress only.

Template: `templates/common/.github/psalm/psalm-taint.xml` — read it in full before filling placeholders

**`.github/psalm/wordpress-taint.phpstub`** — Psalm doesn't know WordPress's escaping API, so without these
annotations every correctly escaped `echo esc_html( … )` would be reported. Stubs are read by Psalm only:

Template: `templates/wordpress/.github/psalm/wordpress-taint.phpstub` — read it in full before filling placeholders

**`.github/psalm/prestashop-taint.phpstub`** — the shop's classes aren't installed in this job, so the stub also
declares the input source and the SQL sinks:

Template: `templates/prestashop-module/.github/psalm/prestashop-taint.phpstub` — read it in full before filling placeholders

Limits to state in Step 7: taint analysis sees PHP only — Blade, Smarty and Twig templates (where most output
happens in Laravel/PrestaShop) aren't analysed; for Laravel, Psalm knows superglobals but not `Request::input()` —
adding `psalm/plugin-laravel` to `require-dev` and `<plugins><pluginClass class="Psalm\LaravelPlugin\Plugin"/></plugins>`
to the config makes request input a taint source (a project decision, not made here). Findings are alerts, not a
failed build; triage them in Code scanning.

### `.github/dependabot.yml` (always written — no opt-out)

Template: `templates/common/.github/dependabot.yml` — read it in full before filling placeholders

Composer updates respect `config.platform.php`, so Dependabot never proposes a version the floor can't run. When
updating an existing `dependabot.yml`, keep entries for ecosystems this skill doesn't manage.

## Step 4 — Fill and gate

Substitute every placeholder from Step 1, apply every gate above, and strip the guidance comments. Never invent a
value: an unresolved fact (no `test:integration` script, no Antora playbook, no Sonar project key, no `Dockerfile`
for Laravel `internal` — `iru-setup-php-laravel` writes it) is either asked or reported as an open gap in Step 7.
Also:

- **`sonar-project.properties`** missing with `sonar` ≠ `none` → ask for the organization/project key (and host URL
  for self-hosted) and write the flavor scaffold's own template (`iru-setup-php-laravel`/`-wordpress`/`-prestashop`
  own it); every flavor reads `sonar.php.coverage.reportPaths=build/logs/clover.xml`,
  `sonar.php.tests.reportPath=build/logs/junit.xml`, `sonar.php.phpstan.reportPaths=build/logs/phpstan.json`.
- `.github/scripts/php_version.py` is written whenever `sync.yml` or a WordPress/PrestaShop `release.yml` is (both use
  it); `chmod +x` both scripts.

## Step 5 — Validate

- Run `actionlint` on every generated workflow — `actionlint` if installed, else
  `docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:latest -color` — and fix every finding (shellcheck
  findings in `run:` blocks included). Parse `.github/dependabot.yml` with
  `python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" .github/dependabot.yml` and the Psalm config with
  `python3 -c "import xml.dom.minidom,sys; xml.dom.minidom.parse(sys.argv[1])" .github/psalm/psalm-taint.xml`.
- Run the commands the workflows contain locally first, through `iru-gate-runner` agents (compact results):
  `composer validate --strict`, `composer audit --locked`, `composer lint`, `composer format:check`, `composer
  test:coverage`, and `python3 .github/scripts/php_version.py --list` (every location must agree and match the
  `-dev` convention). With `ci-tests: full` and Docker available, also the flavor's integration commands; Docker
  unavailable → the integration job is **unverified**, not failed. The browser UI test steps likewise: run
  `npm run test:e2e` locally behind `.integration-test.lock` when Node, a browser and the flavor's server (Laravel's
  `php artisan serve`, wp-env) are available, else **unverified**. A workflow whose commands were never run is a
  guess.
- Never trigger `release.yml` or `sync.yml` for real as a test. Recommend a throwaway pre-release on a fork, or
  review of the first real release run.

## Step 6 — Write the files

Write (or, per Step 2, update/replace — and skip kept units) the workflows, the two scripts (only together with
`sync.yml`/`release.yml` as above), `security.yml` with `.github/psalm/`, and `.github/dependabot.yml`. With
`distribution: none`, write no `release.yml`.

## Step 7 — Report: secrets, settings, choices, warnings

Required secrets (Settings → Secrets and variables → Actions) — list only the rows that apply:

| Secret | Purpose | Needed when |
|---|---|---|
| `SONAR_TOKEN` | SonarCloud/SonarQube token for the scan | `sonar` is `cloud` or `self-hosted` (cloud on a private repository also needs a paid SonarCloud plan) |
| `SVN_USERNAME` / `SVN_PASSWORD` | WordPress.org account (SVN password from the profile's Account & Security page) committing to the plugin/theme SVN | `distribution: store`, WordPress |

`GITHUB_TOKEN` needs no setup and covers release uploads, GHCR, Pages, Sync and every `security.yml` job (gitleaks needs
`GITLEAKS_LICENSE` only for organization accounts on its paid tier — mention it). Repository settings to check:

- **Settings → Actions → General → Workflow permissions** must allow GitHub Actions to create pull requests, or
  `sync.yml` fails on `gh pr create` despite its `permissions:` block.
- **Pages** (`pages: yes`): Source = **GitHub Actions**. Private repositories need Pro/Team/Enterprise Cloud, and the
  site is public unless Enterprise Cloud private Pages.
- **Code scanning** must be enabled for the CodeQL and Psalm SARIF uploads; **Dependency graph** for dependency review
  (on by default for public repositories).
- **WordPress.org** (`store`): the plugin/theme must already be approved (first submission is manual, via the upload
  form); an optional `wordpress-org` environment with required reviewers gates every deploy.
- **GHCR** (Laravel `internal`): the first push creates the package as *private*; link it to the repository and change
  its visibility if needed.
- **PrestaShop Addons** (`store`): uploads stay manual — the release's job summary lists the steps.

Then summarize: scratch vs update and, per unit, **created / updated / replaced / kept as is** (with mismatches) or
stopped; flavor, `<slug>`, PHP floor and matrix (and where they came from); `visibility` (and its source) with the
minutes note for private repositories; `ci-tests` (and for `full`, which integration job — database, WordPress
matrix, PrestaShop majors and why a major was left out — and whether it runs the browser UI tests and uploads
`.ui-screenshots/`, or why not); `pages`; `sonar` (+ keys) or why omitted; `distribution`
(or why `release.yml` wasn't written); which security jobs were included, that CodeQL can't analyse PHP and Psalm
taint analysis substitutes for it (with its limits above); and every open gap (missing composer scripts, no Antora
setup, README wording `sync_versions.py` won't match, patch releases the minor-bump default doesn't handle, a
repository name that differs from the WordPress slug — handled by the integration job's checkout path, but worth
knowing for local runs).

Warn explicitly: **review every workflow before pushing it.** Branch names, versions, slugs and image coordinates were
inferred from this repository and may need correcting; `release.yml` publishes to GitHub Releases, WordPress.org or
GHCR, and `sync.yml` pushes branches and opens pull requests, so verify both against the first real release. Nothing
in these files contains a credential, and it must stay that way.

## Verification notes

Verified in `$TMPDIR/iru-verify/php-setup/workflows/`:

- **actionlint** (`rhysd/actionlint`, Docker) clean on every workflow rendered from these templates for four variants —
  `laravel` (MySQL integration, `internal` FPM two-image release, Sonar, Pages), `wordpress-plugin` (`store`, wp-env
  integration matrix, Sonar, phpDocumentor, Pages), `wordpress-theme` (`internal` + theme SVN deploy job, `pages: no`),
  `prestashop-module` (`store`, shop integration, no Sonar) — including `sync.yml` and `security.yml`.
- **Action refs** checked against the GitHub API on the day of writing; `google/osv-scanner-action` has no `v2` ref
  (tags `v2.0.0`…`v2.7.0` only), so this skill pins `v2.7.0`.
- **Version helper**: identical to `iru-php-bump-version`'s (diffed); its fixture runs are listed there. The release
  workflows' tag check (`grep -v -F ": $VERSION "` over `--list`) was run against those fixtures: it passes on a
  released plugin and fails when `Stable tag:` still names the previous release.
- **wp-env**: `@wordpress/env` 11.17.0 on the host Docker, a plugin with a `.wp-env.test.json` (`testsEnvironment:
  false`): `WP_ENV_CORE=WordPress/WordPress#6.5 WP_ENV_PHP_VERSION=8.2 wp-env start --config=.wp-env.test.json` gave
  WordPress 6.5 on PHP 8.2.34 and a `WP_UnitTestCase` suite passed via `wp-env run cli --config=… --env-cwd=…
  vendor/bin/phpunit`; without `WP_ENV_CORE` the latest release (7.1.3) on PHP 8.4 passed too.
- **Zip assembly**: `rsync -a --exclude-from=.distignore` + `zip` produced a `<slug>/` archive without the excluded
  anchored and unanchored paths while keeping `vendor/`.
- **Psalm 6.20 taint analysis** (`psalm.phar`, `php:8.4-cli`) with the configs above: exit 2 + valid SARIF 2.1.0 on
  findings; with the WordPress stub, raw `echo $_GET[…]` and a concatenated `$wpdb->query()` were reported while
  `esc_html()`/`esc_attr( sanitize_text_field() )`/`$wpdb->prepare()`/`esc_url_raw()` paths were not; with the
  PrestaShop stub, `Db::execute()` with raw `Tools::getValue()` was reported while `(int)` and `pSQL()` paths were not.
- `composer audit --locked` exists in Composer 2 (`composer:2` image).

Not verified locally: a run on GitHub itself (service containers, PCOV via setup-php, GHCR push, Pages deploy, SARIF
upload), the WordPress.org plugin/theme SVN deploys (need real credentials and an approved slug), the PrestaShop shop
integration job (its composer scripts were verified by `iru-setup-php-prestashop`; this workflow only sequences
them), and SonarCloud ingestion of the reports.
