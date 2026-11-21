# Shared tooling templates (plugin and theme)

Files both flavors get. Placeholders are listed in SKILL.md Step 5; `<code-paths>` differs per flavor:

| Placeholder | `flavor: plugin` | `flavor: theme` |
|---|---|---|
| `<composer-type>` | `wordpress-plugin` | `wordpress-theme` |
| `<phpstan-paths>` | `<slug>.php`, `uninstall.php`, `src`, `tests` | `functions.php`, `src`, `patterns`, `tests` |
| `<wp-env-key>` | `plugins` | `themes` |
| `<wp-content-dir>` | `wp-content/plugins` | `wp-content/themes` |
| `<sonar-sources>` | `src,<slug>.php,uninstall.php` | `src,functions.php,patterns` |

All of it was generated with these exact templates and verified (see SKILL.md "Verification notes").

## `composer.json`

```json
{
    "name": "<vendor>/<slug>",
    "description": "<description>",
    "type": "<composer-type>",
    "license": "<license-spdx>",
    "authors": [
        {
            "name": "<developer-name>",
            "email": "<developer-email>",
            "homepage": "<organization-url>"
        }
    ],
    "require": {
        "php": ">=<php-floor>"
    },
    "require-dev": {
        "brain/monkey": "^2.7",
        "dealerdirect/phpcodesniffer-composer-installer": "^1.2",
        "php-stubs/wordpress-tests-stubs": "^7.1",
        "phpcompatibility/phpcompatibility-wp": "^2.1",
        "phpstan/extension-installer": "^1.4",
        "phpstan/phpstan": "^2.3",
        "phpunit/phpunit": "^9.6",
        "szepeviktor/phpstan-wordpress": "^2.0",
        "wp-coding-standards/wpcs": "^3.4",
        "yoast/phpunit-polyfills": "^4.0"
    },
    "autoload": {
        "psr-4": {
            "<namespace-json>\\": "src/"
        }
    },
    "autoload-dev": {
        "psr-4": {
            "<namespace-json>\\Tests\\": "tests/"
        }
    },
    "config": {
        "platform": {
            "php": "<php-floor>.0"
        },
        "sort-packages": true,
        "optimize-autoloader": true,
        "allow-plugins": {
            "dealerdirect/phpcodesniffer-composer-installer": true,
            "phpstan/extension-installer": true
        }
    },
    "scripts": {
        "test": "phpunit",
        "test:coverage": "phpunit --coverage-clover build/logs/clover.xml --coverage-text",
        "test:integration": "npm run test:integration",
        "lint": "phpstan analyse --memory-limit=1G",
        "lint:report": [
            "@php -r \"is_dir('build/logs') || mkdir('build/logs', 0777, true);\"",
            "@php vendor/bin/phpstan analyse --no-progress --memory-limit=1G --error-format=json > build/logs/phpstan.json"
        ],
        "format": "phpcbf",
        "format:check": "phpcs"
    },
    "scripts-descriptions": {
        "test": "Runs the fast unit suite (Brain Monkey, no WordPress loaded).",
        "test:coverage": "Runs the unit suite with coverage (needs PCOV or Xdebug) and writes build/logs/clover.xml.",
        "test:integration": "Runs the WP_UnitTestCase suite inside @wordpress/env (needs Docker and a started test environment).",
        "lint": "Runs PHPStan (phpstan-wordpress, level 6).",
        "lint:report": "Writes the PHPStan result as JSON to build/logs/phpstan.json (read by Sonar).",
        "format": "Fixes WordPress Coding Standards violations PHPCBF can fix.",
        "format:check": "Checks WordPress Coding Standards, PHPCompatibilityWP, i18n and prefixes."
    }
}
```

- Each `require-dev` constraint becomes `^<major>.<minor>` of the version Step 4 resolved — **except
  `phpunit/phpunit`, which stays `^9.6`** (Step 4's PHPUnit caveat) — and `yoast/phpunit-polyfills` must keep a
  major that supports PHPUnit 9 (4.x does).
- `<namespace-json>` is the namespace with every `\` doubled (`Acme\\Loans`).
- `config.platform.php` pins resolution to the floor so a newer local/CI PHP never locks a dependency the floor
  can't run. No `"version"` field — the plugin header / `style.css` carries the version (catalog convention).
- No `require` beyond `php`: the scaffold ships no runtime Composer dependency. See SKILL.md Step 5's "Composer
  dependencies inside WordPress" before adding one.
- `phpcbf` exits `1` when it fixed something, so `composer format` reports a "failure" after a successful fix —
  re-run `composer format:check` to confirm it's clean.
- **License "No license" (proprietary)**: `"license": "proprietary"`.

## `phpcs.xml.dist`

```xml
<?xml version="1.0"?>
<ruleset name="<name>">
	<description>WordPress Coding Standards for <name>.</description>

	<file>.</file>

	<exclude-pattern>/vendor/*</exclude-pattern>
	<exclude-pattern>/node_modules/*</exclude-pattern>
	<exclude-pattern>/build/*</exclude-pattern>
	<exclude-pattern>/artifacts/*</exclude-pattern>
	<exclude-pattern>/docs/*</exclude-pattern>
	<exclude-pattern>/.claude/*</exclude-pattern>

	<arg name="extensions" value="php"/>
	<arg name="basepath" value="."/>
	<arg name="parallel" value="8"/>
	<arg value="sp"/>

	<!-- Lowest PHP and WordPress versions supported: keep in sync with the Requires PHP / Requires at least headers. -->
	<config name="testVersion" value="<php-floor>-"/>
	<config name="minimum_wp_version" value="<wp-floor>"/>

	<rule ref="WordPress-Extra"/>
	<rule ref="WordPress-Docs"/>
	<rule ref="PHPCompatibilityWP"/>

	<rule ref="WordPress.WP.I18n">
		<properties>
			<property name="text_domain" type="array">
				<element value="<slug>"/>
			</property>
		</properties>
	</rule>

	<rule ref="WordPress.NamingConventions.PrefixAllGlobals">
		<properties>
			<property name="prefixes" type="array">
				<element value="<prefix>"/>
				<element value="<namespace>"/>
			</property>
		</properties>
	</rule>

	<!-- PSR-4: class files are named after their class, not class-*.php. -->
	<rule ref="WordPress.Files.FileName">
		<exclude-pattern>/src/*</exclude-pattern>
		<exclude-pattern>/tests/*</exclude-pattern>
	</rule>

	<!-- Tests are not shipped and document themselves through their names. -->
	<rule ref="Squiz.Commenting">
		<exclude-pattern>/tests/*</exclude-pattern>
	</rule>
	<rule ref="Generic.Commenting.DocComment">
		<exclude-pattern>/tests/*</exclude-pattern>
	</rule>
	<!-- Tests fake request input, fire core hooks and use plain PHP file functions; production code keeps these rules. -->
	<rule ref="WordPress.Security.NonceVerification">
		<exclude-pattern>/tests/*</exclude-pattern>
	</rule>
	<rule ref="WordPress.Security.EscapeOutput">
		<exclude-pattern>/tests/*</exclude-pattern>
	</rule>
	<rule ref="WordPress.NamingConventions.PrefixAllGlobals">
		<exclude-pattern>/tests/*</exclude-pattern>
	</rule>
	<rule ref="WordPress.WP.AlternativeFunctions">
		<exclude-pattern>/tests/*</exclude-pattern>
	</rule>
</ruleset>
```

- WordPress-Extra already contains WordPress-Core; WordPress-Docs adds the inline-documentation sniffs
  `iru-php-phpdoc` relies on. PHPCompatibilityWP 2.x still runs on PHPCompatibility 9.3.x, which predates the newest
  PHP releases: `testVersion` above its known range is accepted (verified with `8.4-`, no warning) but adds no
  checks for syntax it doesn't know yet — PHPStan (which reads the `composer.json` floor) covers that gap.
- WPCS 3.x pins PHP_CodeSniffer 3.x — `composer install` resolves 3.13.x even though PHPCS 4 exists.
- Tests follow the same layout rules (tabs, Yoda, `array()`, `snake_case` methods) — only the documentation,
  security and prefix sniffs are relaxed for `/tests/`, never for shipped code.
- Browser UI tests: `extensions=php` already keeps the Playwright TypeScript under `tests/e2e/` out of PHPCS, and
  `/artifacts/*` (wp-scripts' Playwright output) is excluded explicitly. The one PHP file there, the test-only
  mu-plugin `tests/e2e/mu-plugins/iru-ui-test-direction.php`, **is** checked and passes as written (verified).

## `phpstan.neon.dist`

```neon
# szepeviktor/phpstan-wordpress is registered by phpstan/extension-installer.
parameters:
    level: 6
    paths:
        - <slug>.php
        - uninstall.php
        - src
        - tests
    excludePaths:
        - tests/Integration/bootstrap.php
    scanFiles:
        # WP_UnitTestCase and its factories, for the integration suite.
        - vendor/php-stubs/wordpress-tests-stubs/wordpress-tests-stubs.php
```

`paths` per `<phpstan-paths>` above (theme: `functions.php`, `src`, `patterns`, `tests`). The `.dist` name is
deliberate: PHPStan reads `phpstan.neon` first, so a developer can keep an untracked local override. PHPStan only
reads `.php` files, so `tests/e2e/` contributes just the mu-plugin, which analyses clean at level 6. `blocks/`
(plugin with `blocks: yes`) is deliberately **not** analysed: block `render.php` files read `$attributes`/`$block`
variables WordPress injects, which PHPStan can't see.

## `phpunit.xml.dist` (unit suite — the default for `composer test` and the coverage gate)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<phpunit xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:noNamespaceSchemaLocation="vendor/phpunit/phpunit/phpunit.xsd"
         bootstrap="tests/Unit/bootstrap.php"
         colors="true"
         beStrictAboutOutputDuringTests="true"
         failOnRisky="true"
         failOnWarning="true">
    <testsuites>
        <testsuite name="unit">
            <directory suffix="Test.php">tests/Unit</directory>
        </testsuite>
    </testsuites>
    <coverage processUncoveredFiles="true">
        <include>
            <directory suffix=".php">src</directory>
        </include>
    </coverage>
    <logging>
        <junit outputFile="build/logs/junit.xml"/>
    </logging>
</phpunit>
```

## `phpunit-integration.xml.dist` (integration suite — WordPress loaded, run inside wp-env)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<phpunit xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:noNamespaceSchemaLocation="vendor/phpunit/phpunit/phpunit.xsd"
         bootstrap="tests/Integration/bootstrap.php"
         colors="true"
         failOnRisky="true"
         failOnWarning="true">
    <testsuites>
        <testsuite name="integration">
            <directory suffix="Test.php">tests/Integration</directory>
        </testsuite>
    </testsuites>
    <coverage processUncoveredFiles="true">
        <include>
            <directory suffix=".php">src</directory>
        </include>
    </coverage>
    <logging>
        <junit outputFile="build/logs/junit-integration.xml"/>
    </logging>
</phpunit>
```

**Why two files instead of two suites in one:** a PHPUnit config has exactly one `bootstrap`. The unit suite must
never load WordPress (Brain Monkey/Patchwork can't redefine a function WordPress already declared), and the
integration suite must load it before any test class — so each suite gets its own config. `iru-php-test` /
`iru-php-coverage` select them with `-c phpunit-integration.xml.dist` (or `--testsuite unit` on the default one).
PHPUnit 9 schema (`<coverage processUncoveredFiles>`, `<logging>`) — not the PHPUnit 10+ `<source>` element —
because the WordPress test library still requires PHPUnit 9 (SKILL.md Step 4). PHPUnit creates `build/logs/` itself
(verified on a clean checkout); the result cache stays the default `.phpunit.result.cache` at the root.

## `tests/Unit/bootstrap.php`

```php
<?php
/**
 * Unit-suite bootstrap: Composer autoloader only, WordPress is never loaded.
 *
 * Brain Monkey (loaded through the autoloader) stubs the WordPress functions each test needs.
 *
 * @package <namespace>
 */

require_once dirname( __DIR__, 2 ) . '/vendor/autoload.php';
```

## `tests/Unit/TestCase.php`

```php
<?php
/**
 * Base class of the unit suite.
 *
 * @package <namespace>
 */

namespace <namespace>\Tests\Unit;

use Brain\Monkey;
use Brain\Monkey\Functions;
use Mockery\Adapter\Phpunit\MockeryPHPUnitIntegration;
use Yoast\PHPUnitPolyfills\TestCases\TestCase as PolyfillTestCase;

/**
 * Sets up and tears down Brain Monkey around every test.
 */
abstract class TestCase extends PolyfillTestCase {

	use MockeryPHPUnitIntegration;

	/**
	 * Starts Brain Monkey and stubs the translation and escaping functions.
	 */
	protected function set_up(): void {
		parent::set_up();
		Monkey\setUp();
		Functions\stubTranslationFunctions();
		Functions\stubEscapeFunctions();
	}

	/**
	 * Resets Brain Monkey so expectations never leak between tests.
	 */
	protected function tear_down(): void {
		Monkey\tearDown();
		parent::tear_down();
	}
}
```

The polyfills' `set_up()`/`tear_down()` (snake_case, WPCS-friendly) are the same hooks `WP_UnitTestCase` uses, so
unit and integration tests read alike. PHPUnit 9 means **annotations** (`@covers`, `@dataProvider`), not attributes.

## `.wp-env.json` (development site, http://localhost:8888)

```json
{
	"$schema": "https://schemas.wp.org/trunk/wp-env.json",
	"core": null,
	"phpVersion": "<php-floor>",
	"<wp-env-key>": [ "." ],
	"testsEnvironment": false
}
```

## `.wp-env.test.json` (dedicated integration-test environment, http://localhost:8889)

```json
{
	"$schema": "https://schemas.wp.org/trunk/wp-env.json",
	"core": null,
	"phpVersion": "<php-floor>",
	"<wp-env-key>": [ "." ],
	"port": 8889,
	"testsEnvironment": false,
	"config": {
		"WP_DEBUG": true
	},
	"mappings": {
		"wp-content/mu-plugins/iru-ui-test-direction.php": "./tests/e2e/mu-plugins/iru-ui-test-direction.php"
	}
}
```

- `mappings` mounts the browser UI tests' right-to-left switch (see "Browser UI tests" below) as a must-use plugin
  **only in this test environment** — never in `.wp-env.json` (the development site) and never in the release zip.
  It does nothing unless the request carries the `iru_ui_dir=rtl` cookie, so the PHPUnit integration suite (CLI,
  no cookies) is unaffected.

- **Container naming (wp-env 11.x, checked against its README and source):** `wp-env run` accepts `mysql`,
  `wordpress`, `cli`, `composer`, `phpmyadmin`; the old built-in `tests-wordpress`/`tests-cli` pair only exists while
  the **deprecated** `testsEnvironment` option is on — and it still defaults to `true`, which is why both files set
  it to `false`. The documented replacement is a separate config passed with `--config`, which gets its own
  containers and database. Every container carries `WP_TESTS_DIR=/wordpress-phpunit` (the WordPress PHPUnit library
  matching the installed core) plus PHPUnit and Composer.
- **Never run the integration suite against the development config:** wp-env's `wp-tests-config.php` is derived
  from the site's own `wp-config.php` (same database, same `wp_` prefix), and the test installer drops those tables
  — the dedicated `.wp-env.test.json` environment is the one it is allowed to wipe.
- `core: null` = latest WordPress release. To test the floor too, a CI matrix can override it per job
  (`WP_ENV_CORE=WordPress/WordPress#<wp-floor>`) — that's `iru-setup-php-github-workflows`' concern.
- wp-env names the mapped directory after the **repository directory's basename**; the `--env-cwd` path in
  `package.json` assumes it equals `<slug>` (SKILL.md Step 1 warns when it doesn't).

## `package.json` (wp-env, the browser UI tests, and the block build when `blocks: yes`)

```json
{
	"name": "<slug>",
	"version": "<dev-version>",
	"private": true,
	"description": "Local WordPress environments and browser UI tests for <name>.",
	"license": "<license-spdx>",
	"scripts": {
		"env:start": "wp-env start",
		"env:stop": "wp-env stop",
		"test:env:start": "wp-env start --config=.wp-env.test.json",
		"test:env:stop": "wp-env stop --config=.wp-env.test.json",
		"test:integration": "wp-env run cli --config=.wp-env.test.json --env-cwd=<wp-content-dir>/<slug> vendor/bin/phpunit -c phpunit-integration.xml.dist",
		"test:e2e": "wp-scripts test-playwright"
	},
	"devDependencies": {
		"@axe-core/playwright": "^4.14.0",
		"@playwright/test": "^1.64.0",
		"@wordpress/e2e-test-utils-playwright": "^3.0.0",
		"@wordpress/env": "^11.17.0",
		"@wordpress/scripts": "^36.1.0"
	}
}
```

- `"private": true` always — this file only drives tooling; nothing is published to npm. `<dev-version>` is the
  same `x.y.z-dev` as the plugin header / `style.css` (`iru-php-bump-version` keeps it in sync via npm).
- `@wordpress/env` constraint: `^<version>` from Step 4. Integration tests run **inside** the `cli` container with
  the host's `vendor/` (mounted with the plugin/theme), so run `composer install` on the host first; `config.platform`
  keeps that `vendor/` loadable by the container's `<php-floor>` PHP.
- npm 11 prints `install-scripts … fs-ext-extra-prebuilt` after `npm install`: that native add-on belongs to wp-env's
  optional WordPress Playground runtime and isn't needed for the Docker runtime used here — leave it unapproved.
- `test:e2e` runs the browser UI tests ("Browser UI tests" below) through `wp-scripts test-playwright`, which
  needs `@wordpress/scripts` even without blocks; `@playwright/test`, `@axe-core/playwright` and
  `@wordpress/e2e-test-utils-playwright` take `^<major>.<minor>.0` of Step 4's versions. `wp-scripts
  test-playwright` runs `playwright install` (every browser) before each run unless `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`
  is set: install Chromium once with `npx playwright install chromium` (`--with-deps` on CI) and run
  `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm run test:e2e`.
- Plugin with `blocks: yes`: see `plugin.md` "Blocks" for the extra scripts and dependencies.

## Browser UI tests (`.ui-quality.yml`, `playwright.config.ts`, `tests/e2e/`)

The catalog's UI contract: every component and screen is rendered in a real browser (Playwright, Chromium) against
the wp-env **tests** instance (`http://localhost:8889`, `.wp-env.test.json`, the same one the integration suite uses),
captured as a PNG per state × direction × theme into `<repo root>/.ui-screenshots/<kind>/<Id>/<state>--<direction>--<theme>.png`
(`UI_SCREENSHOTS_DIR` overrides; gitignored by `iru-setup-php-gitignore`), and checked with axe-core at the
`.ui-quality.yml` level. The flavor's sample spec is in `plugin.md` / `theme.md`. Layout:

```text
.ui-quality.yml                                  # accessibility level + screenshot matrix (repository root)
playwright.config.ts                             # wp-scripts defaults + testDir + the tests environment
tests/e2e/support/ui-screenshots.ts              # the one shared helper every spec uses
tests/e2e/mu-plugins/iru-ui-test-direction.php   # test-only RTL switch, mapped only by .wp-env.test.json
tests/e2e/specs/<Id>.spec.ts                     # one spec per component/screen, named after its Id
```

### `.ui-quality.yml`

```yaml
# .ui-quality.yml — at the repository root; written by the iru-setup-* scaffolds, read by the coding and test skills.
accessibility: enforce      # enforce: violations fail the UI tests | report: listed, never failing | off: not run
directions: [ltr, rtl]      # screenshot matrix directions
themes: [light, dark]       # screenshot matrix themes
```

Exactly the contract's shape: write `enforce` as shown, or replace that one word with Step 0/2's `accessibility`
(`report` / `off`). A missing file means these defaults; `off` disables only the automated axe check.

### `playwright.config.ts`

```ts
/**
 * Browser UI tests (`npm run test:e2e` → wp-scripts test-playwright) against the wp-env tests instance
 * (http://localhost:8889, `.wp-env.test.json`). Extends the @wordpress/scripts defaults (REST login in a global
 * setup, artifacts/ output, one worker) with this project's spec directory and test environment.
 */
import { defineConfig, type PlaywrightTestConfig } from '@playwright/test';

// @wordpress/scripts ships this config as CommonJS without type declarations.
// eslint-disable-next-line @typescript-eslint/no-require-imports
const baseConfig: PlaywrightTestConfig = require( '@wordpress/scripts/config/playwright.config.js' );

export default defineConfig( {
	...baseConfig,
	testDir: './tests/e2e/specs',
	webServer: {
		...baseConfig.webServer,
		// The default `npm run wp-env start` would start the development site; tests use .wp-env.test.json.
		command: 'npm run test:env:start',
		port: 8889,
	},
} );
```

- `wp-scripts test-playwright` uses a project's own `playwright.config.ts` when one exists; spreading the package's
  config keeps its global setup (REST login as the wp-env `admin`, storage state under `artifacts/`), one worker,
  `reducedMotion: 'reduce'`, a fixed 960×700 viewport and `en-US` locale. Its default `testDir` is `./specs` and its
  `webServer` runs `npm run wp-env start` — the **development** site — so both are overridden: specs live in
  `tests/e2e/specs/`, and a missing tests instance is started with `npm run test:env:start` (port 8889; an already
  running one is reused).
- `require()` with a type annotation instead of `import`: the package ships the config as CommonJS without type
  declarations (`import` fails `tsc` with TS7016). Verified with `tsc --noEmit` (strict) and `playwright test --list`.

### `tests/e2e/support/ui-screenshots.ts`

```ts
/**
 * Shared UI-test helper (catalog UI contract): reads `.ui-quality.yml`, switches direction and theme, captures
 * `<repo root>/.ui-screenshots/<kind>/<Id>/<state>--<direction>--<theme>[--<variant>…].png` and runs axe-core.
 * Every spec goes through this file — never hard-code screenshot paths in a spec.
 */
import { existsSync, mkdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import AxeBuilder from '@axe-core/playwright';
import { expect, type Locator, type Page, type TestInfo } from '@playwright/test';

export type Direction = 'ltr' | 'rtl';
export type Theme = 'light' | 'dark' | 'high-contrast';
export type AccessibilityLevel = 'enforce' | 'report' | 'off' | 'none';
export type UiKind = 'component' | 'screen' | 'flow';

export interface UiQuality {
	accessibility: AccessibilityLevel;
	directions: Direction[];
	themes: Theme[];
}

export interface UiCapture {
	kind: UiKind;
	/** Component/screen type name exactly as in code (`ContentNotice`), or a kebab-case flow name. */
	id: string;
	/** Kebab-case visual state (`default`, `empty`, `long-content`, …); `NN-<step>` for a flow. */
	state: string;
	direction: Direction;
	theme: Theme;
	/** Optional extra segments: platform, then form factor/size (`web`, `desktop`, `large-text`). */
	variants?: string[];
	/** Capture only this element (a component) instead of the full page (a screen or flow step). */
	locator?: Locator;
}

const DEFAULTS: UiQuality = { accessibility: 'enforce', directions: [ 'ltr', 'rtl' ], themes: [ 'light', 'dark' ] };
const KEBAB = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const TYPE_NAME = /^[A-Za-z][A-Za-z0-9]*$/;
const WCAG_TAGS = [ 'wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa' ];

/** Cookie read by the test-only mu-plugin `tests/e2e/mu-plugins/iru-ui-test-direction.php`. */
export const DIRECTION_COOKIE = 'iru_ui_dir';

/** Repository root: the closest ancestor of the working directory holding `.git`, else the working directory. */
export function repoRoot(): string {
	let dir = resolve( process.cwd() );
	for ( ;; ) {
		if ( existsSync( join( dir, '.git' ) ) ) {
			return dir;
		}
		const parent = dirname( dir );
		if ( parent === dir ) {
			return resolve( process.cwd() );
		}
		dir = parent;
	}
}

function listValue( value: string ): string[] {
	return value
		.replace( /^\[|\]$/g, '' )
		.split( ',' )
		.map( ( item ) => item.trim().replace( /^['"]|['"]$/g, '' ) )
		.filter( ( item ) => item !== '' );
}

/** `.ui-quality.yml` at the repository root (tiny line parser, no YAML dependency); a missing file means defaults. */
export function uiQuality(): UiQuality {
	const file = join( repoRoot(), '.ui-quality.yml' );
	const quality: UiQuality = { ...DEFAULTS };
	if ( ! existsSync( file ) ) {
		return quality;
	}
	for ( const raw of readFileSync( file, 'utf8' ).split( /\r?\n/ ) ) {
		const line = raw.replace( /(^|\s)#.*$/, '' ).trim();
		const match = /^(accessibility|directions|themes)\s*:\s*(.+)$/.exec( line );
		if ( ! match ) {
			continue;
		}
		const [ , key, value ] = match;
		if ( key === 'accessibility' && [ 'enforce', 'report', 'off', 'none' ].includes( value ) ) {
			quality.accessibility = value as AccessibilityLevel;
		} else if ( key === 'directions' ) {
			quality.directions = listValue( value ).filter( ( d ): d is Direction => d === 'ltr' || d === 'rtl' );
		} else if ( key === 'themes' ) {
			quality.themes = listValue( value ).filter( ( t ): t is Theme =>
				[ 'light', 'dark', 'high-contrast' ].includes( t )
			);
		}
	}
	return quality;
}

/** `UI_SCREENSHOTS_DIR` (absolute path) when set, else `<repo root>/.ui-screenshots`. */
export function screenshotsDir(): string {
	return process.env.UI_SCREENSHOTS_DIR || join( repoRoot(), '.ui-screenshots' );
}

/**
 * Switches colour scheme / forced colours and the layout direction. Call it **before** `page.goto()`: WordPress
 * renders the right-to-left page on the server (the test-only mu-plugin reads the `iru_ui_dir` cookie, so
 * `is_rtl()`, `<html dir="rtl">` and the `-rtl.css` stylesheets all apply), it is not flipped client-side.
 */
export async function applyDirectionAndTheme( page: Page, direction: Direction, theme: Theme ): Promise< void > {
	await page.emulateMedia( {
		colorScheme: theme === 'dark' ? 'dark' : 'light',
		forcedColors: theme === 'high-contrast' ? 'active' : 'none',
	} );
	const context = page.context();
	await context.clearCookies( { name: DIRECTION_COOKIE } );
	if ( direction === 'rtl' ) {
		const url = process.env.WP_BASE_URL || 'http://localhost:8889';
		await context.addCookies( [ { name: DIRECTION_COOKIE, value: 'rtl', url } ] );
	}
}

/** Asserts the server rendered the requested direction (`<html dir="rtl">`; WordPress omits `dir` for left-to-right). */
export async function expectDirection( page: Page, direction: Direction ): Promise< void > {
	const html = page.locator( 'html' );
	if ( direction === 'rtl' ) {
		await expect( html ).toHaveAttribute( 'dir', 'rtl' );
	} else {
		await expect( html ).not.toHaveAttribute( 'dir', 'rtl' );
	}
}

/** Writes one PNG of the contract's matrix and returns its path. */
export async function captureUiState( page: Page, capture: UiCapture ): Promise< string > {
	const { kind, id, state, direction, theme, variants = [], locator } = capture;
	if ( ! [ 'component', 'screen', 'flow' ].includes( kind ) ) {
		throw new Error( `Unknown UI kind "${ kind }"` );
	}
	if ( ! ( kind === 'flow' ? KEBAB : TYPE_NAME ).test( id ) ) {
		throw new Error( `Invalid ${ kind } id "${ id }"` );
	}
	for ( const segment of [ state, ...variants ] ) {
		if ( ! KEBAB.test( segment ) ) {
			throw new Error( `"${ segment }" is not kebab-case` );
		}
	}
	if ( ! [ 'ltr', 'rtl' ].includes( direction ) || ! [ 'light', 'dark', 'high-contrast' ].includes( theme ) ) {
		throw new Error( `Invalid direction/theme "${ direction }"/"${ theme }"` );
	}
	const name = [ state, direction, theme, ...variants ].join( '--' ) + '.png';
	const path = join( screenshotsDir(), kind, id, name );
	mkdirSync( dirname( path ), { recursive: true } );
	const options = { path, animations: 'disabled' as const, caret: 'hide' as const };
	if ( locator ) {
		await locator.screenshot( options );
	} else {
		await page.screenshot( { ...options, fullPage: true } );
	}
	return path;
}

/**
 * Runs axe-core (WCAG 2.0/2.1/2.2 A and AA) at the `.ui-quality.yml` level: `enforce` fails on any violation,
 * `report` lists `rule id × nodes` as an annotation, `off` skips. `include` scopes the scan to a component.
 */
export async function checkAccessibility(
	page: Page,
	testInfo: TestInfo,
	options: { include?: string | string[] } = {}
): Promise< void > {
	const level = uiQuality().accessibility;
	if ( level === 'off' || level === 'none' ) {
		return;
	}
	let builder = new AxeBuilder( { page } ).withTags( WCAG_TAGS );
	for ( const selector of ( [] as string[] ).concat( options.include ?? [] ) ) {
		builder = builder.include( selector );
	}
	const results = await builder.analyze();
	await testInfo.attach( 'axe-results.json', {
		body: JSON.stringify( results.violations, null, 2 ),
		contentType: 'application/json',
	} );
	const summary = results.violations.map( ( violation ) => `${ violation.id } × ${ violation.nodes.length }` );
	if ( level === 'enforce' ) {
		expect( summary, 'axe-core accessibility violations' ).toEqual( [] );
	} else if ( summary.length > 0 ) {
		testInfo.annotations.push( { type: 'accessibility', description: summary.join( ', ' ) } );
		console.warn( `[accessibility:report] ${ testInfo.title }: ${ summary.join( ', ' ) }` );
	}
}
```

- One helper per repository, shared by every spec: `uiQuality()` (tiny `.ui-quality.yml` line parser, no YAML
  dependency), `screenshotsDir()`, `applyDirectionAndTheme()`, `expectDirection()`, `captureUiState()` (validates
  every name segment; `locator` captures one component instead of the full page) and `checkAccessibility()`
  (WCAG 2.0/2.1/2.2 A + AA tags; `include` scopes the scan to the component under test, so the active theme's own
  markup never fails a plugin's component test).
- Call `applyDirectionAndTheme()` **before** `page.goto()`: the direction is a server-side switch (the cookie
  below), the theme is `emulateMedia()` (`prefers-color-scheme`; `high-contrast` → `forcedColors: 'active'`).
- Behaviour is asserted once; the screenshot matrix covers `.ui-quality.yml`'s directions × themes.

### `tests/e2e/mu-plugins/iru-ui-test-direction.php`

```php
<?php
/**
 * Test-only must-use plugin: renders the site right-to-left when the `iru_ui_dir=rtl` cookie is present, so the
 * browser UI tests capture the real server-rendered RTL page (`is_rtl()`, `<html dir="rtl">`, `-rtl.css`).
 *
 * Mapped into wp-content/mu-plugins/ by .wp-env.test.json only — never shipped (tests/ is in .distignore).
 *
 * @package <namespace>
 */

defined( 'ABSPATH' ) || exit;

if ( isset( $_COOKIE['iru_ui_dir'] ) && 'rtl' === sanitize_key( wp_unslash( $_COOKIE['iru_ui_dir'] ) ) ) {
	$iru_ui_test_force_rtl = static function (): void {
		global $wp_locale;

		if ( $wp_locale instanceof WP_Locale ) {
			$wp_locale->text_direction = 'rtl';
		}
	};

	// $wp_locale is created right before after_setup_theme; switch_to_locale() re-initializes it (change_locale).
	add_action( 'after_setup_theme', $iru_ui_test_force_rtl, 0 );
	add_action( 'change_locale', $iru_ui_test_force_rtl );
}
```

- Renders the real right-to-left page — `is_rtl()` true, `<html dir="rtl">` from `language_attributes()`, every
  `-rtl.css` variant registered with `wp_style_add_data( …, 'rtl', … )` — instead of flipping an attribute in the
  browser. Hook timing (checked in `wp-settings.php` of WordPress 7.1): `setup_theme` fires **before**
  `$wp_locale = new WP_Locale()`, so the switch runs on `after_setup_theme` (priority 0), the first hook after the
  locale object exists and before any stylesheet is registered; `change_locale` re-applies it after
  `switch_to_locale()` rebuilds the locale. Locale strings stay English — feed right-to-left *content* (Arabic or
  Hebrew fixtures) from the spec.
- Lives under `tests/` (excluded from the release zip by `.distignore`) and is mounted only by `.wp-env.test.json`.
  `php -l`, PHPCS (the scaffold's ruleset) and PHPStan level 6 pass on it as written.

## `sonar-project.properties` (only when `sonar` ≠ `none`)

```properties
sonar.organization=<sonar-organization>
sonar.projectKey=<sonar-project-key>
sonar.sources=<sonar-sources>
sonar.tests=tests
sonar.php.coverage.reportPaths=build/logs/clover.xml
sonar.php.tests.reportPath=build/logs/junit.xml
sonar.php.phpstan.reportPaths=build/logs/phpstan.json
sonar.exclusions=vendor/**,node_modules/**,build/**,languages/**
```

- Omit `sonar.organization` for a self-hosted server without organizations.
- The three report paths come from `composer test:coverage` (Clover + JUnit) and `composer lint:report` (PHPStan
  JSON) — the same script names `iru-setup-php-laravel` uses, so `iru-setup-php-github-workflows` runs one command
  set for every flavor.
- Plugin with `blocks: yes`: add `blocks` to `sonar.sources`, and `sonar.javascript.lcov.reportPaths` only if JS
  tests are added later.
