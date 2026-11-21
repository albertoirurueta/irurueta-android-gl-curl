# Tooling templates — `iru-setup-php-prestashop`

Composer manifest and scripts, PHPUnit, the unit and integration tests, PHPStan (stub-based unit config + the
`prestashop/php-dev-tools` shop config), PHP-CS-Fixer, the Docker dev shop, the browser UI tests (Playwright +
axe-core, the catalog's UI contract), packaging, and Sonar. Same placeholders
as `module-templates.md`, plus `<php-version>` (the PHP floor, `major.minor`), `<license-spdx>` (`AFL-3.0`,
`OSL-3.0`, `MIT`, ..., or `proprietary`), `<organization-url>`, `<sonar-organization>`, `<sonar-project-key>`.
Every command below ran against the generated module (SKILL.md "Verification notes").

### `composer.json`

```json
{
    "name": "<vendor>/<module>",
    "description": "<description>",
    "type": "prestashop-module",
    "license": "<license-spdx>",
    "authors": [
        {
            "name": "<developer-name>",
            "email": "<developer-email>",
            "homepage": "<organization-url>"
        }
    ],
    "require": {
        "php": ">=<php-version>"
    },
    "autoload": {
        "psr-4": {
            "<NamespaceJson>\\": "src/"
        },
        "classmap": [
            "<module>.php"
        ],
        "exclude-from-classmap": []
    },
    "autoload-dev": {
        "psr-4": {
            "<NamespaceJson>\\Tests\\": "tests/"
        }
    },
    "config": {
        "platform": {
            "php": "<php-version>.0"
        },
        "preferred-install": "dist",
        "prepend-autoloader": false,
        "sort-packages": true
    },
    "scripts": {
        "test": "phpunit --testsuite Unit",
        "test:coverage": "phpunit --testsuite Unit --coverage-clover build/logs/clover.xml --log-junit build/logs/junit.xml",
        "lint": "phpstan analyse --memory-limit=1G",
        "lint:report": [
            "@php -r \"is_dir('build/logs') || mkdir('build/logs', 0777, true);\"",
            "phpstan analyse --memory-limit=1G --no-progress --error-format=json > build/logs/phpstan.json"
        ],
        "lint:shop": "docker compose -f tests/Integration/docker-compose.yml exec -T -e _PS_ROOT_DIR_=/var/www/html -w /var/www/html/modules/<module> prestashop php vendor/bin/phpstan analyse -c tests/phpstan/phpstan.neon --memory-limit=1G",
        "format": "php-cs-fixer fix",
        "format:check": "php-cs-fixer check --diff",
        "shop:up": "docker compose -f tests/Integration/docker-compose.yml up -d --wait",
        "shop:install": "docker compose -f tests/Integration/docker-compose.yml exec -T -u www-data prestashop php bin/console prestashop:module install <module>",
        "test:integration": "docker compose -f tests/Integration/docker-compose.yml exec -T -e _PS_ROOT_DIR_=/var/www/html -w /var/www/html/modules/<module> prestashop php vendor/bin/phpunit --testsuite Integration",
        "shop:down": "docker compose -f tests/Integration/docker-compose.yml down -v",
        "package": "bash bin/package.sh"
    },
    "scripts-descriptions": {
        "test": "Run the fast unit suite (no shop needed).",
        "test:coverage": "Unit suite with Clover/JUnit reports under build/logs (needs PCOV or Xdebug).",
        "lint": "PHPStan with the unit configuration (PrestaShop stubs, no shop needed).",
        "lint:report": "PHPStan (unit configuration) as JSON in build/logs/phpstan.json, for Sonar.",
        "lint:shop": "PHPStan with prestashop/php-dev-tools against the shop started by shop:up.",
        "format": "Apply the PrestaShop PHP-CS-Fixer rules.",
        "format:check": "Check the PrestaShop PHP-CS-Fixer rules without changing files.",
        "shop:up": "Start PrestaShop + MariaDB in Docker with this module mounted (PS_VERSION=8|9, default 9).",
        "shop:install": "Install the module in the running shop.",
        "test:integration": "Run the Integration suite inside the running shop.",
        "shop:down": "Stop the shop and delete its database volume.",
        "package": "Build build/<module>-<version>.zip for upload (Back Office or PrestaShop Addons)."
    },
    "require-dev": {
        "friendsofphp/php-cs-fixer": "^3.95",
        "phpstan/phpstan": "^2.3",
        "phpunit/phpunit": "^10.5",
        "prestashop/php-dev-tools": "^5.0"
    }
}
```

- No `require-dev` in the template: Step 7 adds it with `composer require --dev` so Composer picks the newest
  versions installable on `config.platform.php` (verified with a `8.1.0` floor: `phpunit/phpunit ^10.5`,
  `phpstan/phpstan ^2.3`, `friendsofphp/php-cs-fixer ^3.95`, `prestashop/php-dev-tools ^5.0`; a higher floor gets
  PHPUnit 11/12 automatically).
- `type: prestashop-module`, `classmap` of the main file and `prepend-autoloader: false` follow PrestaShop's
  "Composer in modules" guidance: with the default prepend, the module's dependencies would shadow the core's.
  Production dependencies (`require`) ship inside the zip — keep them few and never ones the core already provides
  (Symfony, Doctrine, Guzzle).
- `prestashop/php-dev-tools` 5.x requires neither PHP-CS-Fixer nor PHPStan (they are only `require-dev`/`suggest`
  of the package) — they are required explicitly. Its companions `prestashop/autoindex` and
  `prestashop/header-stamp` were deliberately left out: their `symfony/console` ≤ 6 constraints are a resolution
  risk next to current PHP-CS-Fixer, and this skill writes `index.php` guards and headers itself.
- **Script names are shared by every PHP flavor of this catalog** (the workflows skill calls them blindly):
  `test` (fast unit suite), `test:coverage` (Clover `build/logs/clover.xml` + JUnit `build/logs/junit.xml`),
  `test:integration` (shop-backed suite), `lint` (PHPStan), `lint:report` (PHPStan JSON to
  `build/logs/phpstan.json`), `format`, `format:check`, and `package` (the distributable zip). Keep these names when
  updating an existing `composer.json`; the `shop:*` and `lint:shop` scripts are PrestaShop extras.
- `composer.lock` is committed (the package is built from it).
- The `shop:*`, `lint:shop`, and `test:integration` scripts drive the Docker shop of
  `tests/Integration/docker-compose.yml`; `PS_VERSION=8 composer shop:up` targets 8.x, default 9. They run inside
  the shop container as root; a console command that writes the shop's cache must run as `www-data`
  (`docker compose ... exec -T -u www-data prestashop php bin/console ...`) — verified: running `bin/console` as root
  left root-owned files in `var/logs`, after which every back-office request answered HTTP 500 until
  `chown -R www-data:www-data var`. `composer shop:install` is the one console script; it passes `-u www-data`.

### `phpunit.xml.dist`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<phpunit xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:noNamespaceSchemaLocation="vendor/phpunit/phpunit/phpunit.xsd"
         bootstrap="tests/bootstrap.php"
         cacheDirectory=".phpunit.cache"
         colors="true"
         failOnRisky="true"
         failOnWarning="true">
    <testsuites>
        <testsuite name="Unit">
            <directory suffix="Test.php">tests/Unit</directory>
        </testsuite>
        <testsuite name="Integration">
            <directory suffix="Test.php">tests/Integration</directory>
        </testsuite>
    </testsuites>
    <source>
        <include>
            <directory suffix=".php">src</directory>
        </include>
        <exclude>
            <directory suffix=".php">src/Controller</directory>
            <directory suffix=".php">src/Form</directory>
            <directory suffix="index.php">src</directory>
        </exclude>
    </source>
</phpunit>
```

### `tests/bootstrap.php`

```php
<?php

<license-header>

declare(strict_types=1);

// Integration tests run inside a shop (composer test:integration sets _PS_ROOT_DIR_): boot PrestaShop first so its
// autoloader and constants exist. The Unit suite never needs it.
$psRootDir = getenv('_PS_ROOT_DIR_');
if (is_string($psRootDir) && '' !== $psRootDir) {
    if (!is_file($psRootDir . '/config/config.inc.php')) {
        fwrite(STDERR, sprintf('PrestaShop installation not found at %s' . PHP_EOL, $psRootDir));
        exit(1);
    }
    require_once $psRootDir . '/config/config.inc.php';
}

require_once dirname(__DIR__) . '/vendor/autoload.php';
```

### `tests/Unit/Settings/MessageSettingsTest.php`

```php
<?php

<license-header>

declare(strict_types=1);

namespace <Namespace>\Tests\Unit\Settings;

use <Namespace>\Settings\MessageSettings;
use <Namespace>\Settings\MessageViolation;
use PHPUnit\Framework\Attributes\CoversClass;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\Attributes\Test;
use PHPUnit\Framework\TestCase;

/**
 * Unit tests of MessageSettings.
 */
#[CoversClass(MessageSettings::class)]
final class MessageSettingsTest extends TestCase
{
    /**
     * The install defaults are valid settings.
     */
    #[Test]
    public function defaultsAreValid(): void
    {
        $defaults = MessageSettings::defaults();

        self::assertTrue($defaults->enabled);
        self::assertSame([], MessageSettings::violations($defaults->enabled, $defaults->message));
    }

    /**
     * @return iterable<string, array{bool, string, list<MessageViolation>}>
     */
    public static function submissions(): iterable
    {
        yield 'plain text' => [true, 'Hello!', []];
        yield 'empty while hidden' => [false, '', []];
        yield 'empty while shown' => [true, '   ', [MessageViolation::Empty]];
        yield 'too long' => [true, str_repeat('a', MessageSettings::MAX_LENGTH + 1), [MessageViolation::TooLong]];
        yield 'exactly the maximum' => [true, str_repeat('a', MessageSettings::MAX_LENGTH), []];
        yield 'markup' => [true, '<script>alert(1)</script>', [MessageViolation::Markup]];
    }

    /**
     * Each submission reports exactly the rules it breaks.
     *
     * @param list<MessageViolation> $expected
     */
    #[Test]
    #[DataProvider('submissions')]
    public function reportsTheRulesASubmissionBreaks(bool $enabled, string $message, array $expected): void
    {
        self::assertSame($expected, MessageSettings::violations($enabled, $message));
    }
}
```

### `tests/Unit/Presenter/FooterMessagePresenterTest.php`

```php
<?php

<license-header>

declare(strict_types=1);

namespace <Namespace>\Tests\Unit\Presenter;

use <Namespace>\Presenter\FooterMessagePresenter;
use <Namespace>\Settings\MessageSettings;
use PHPUnit\Framework\Attributes\CoversClass;
use PHPUnit\Framework\Attributes\Test;
use PHPUnit\Framework\TestCase;

/**
 * Unit tests of FooterMessagePresenter.
 */
#[CoversClass(FooterMessagePresenter::class)]
final class FooterMessagePresenterTest extends TestCase
{
    /**
     * An enabled greeting is exposed trimmed.
     */
    #[Test]
    public function presentsAnEnabledGreeting(): void
    {
        $view = (new FooterMessagePresenter())->present(new MessageSettings(true, '  Hello!  '));

        self::assertSame(['message' => 'Hello!'], $view);
    }

    /**
     * A disabled greeting renders nothing.
     */
    #[Test]
    public function rendersNothingWhenDisabled(): void
    {
        self::assertNull((new FooterMessagePresenter())->present(new MessageSettings(false, 'Hello!')));
    }

    /**
     * A blank greeting renders nothing even when enabled.
     */
    #[Test]
    public function rendersNothingForABlankGreeting(): void
    {
        self::assertNull((new FooterMessagePresenter())->present(new MessageSettings(true, ' ')));
    }
}
```

### `tests/Integration/ModuleInstallTest.php`

```php
<?php

<license-header>

declare(strict_types=1);

namespace <Namespace>\Tests\Integration;

use <Namespace>\Settings\SettingsKeys;
use PHPUnit\Framework\Attributes\Test;
use PHPUnit\Framework\TestCase;

/**
 * Runs inside a shop where the module is installed (composer shop:up, shop:install, test:integration).
 */
final class ModuleInstallTest extends TestCase
{
    /**
     * Installation registered the hooks and stored the default settings.
     */
    #[Test]
    public function installRegistersHooksAndDefaults(): void
    {
        if (!defined('_PS_VERSION_')) {
            self::markTestSkipped('PrestaShop not booted: run through composer test:integration (sets _PS_ROOT_DIR_).');
        }

        $module = \Module::getInstanceByName('<module>');

        self::assertInstanceOf(\<ModuleClass>::class, $module);
        self::assertTrue(\Module::isInstalled('<module>'));
        foreach (\<ModuleClass>::HOOKS as $hook) {
            self::assertNotFalse($module->isRegisteredInHook($hook), $hook);
        }
        self::assertSame('1', (string) \Configuration::get(SettingsKeys::ENABLED));
        self::assertNotSame('', (string) \Configuration::get(SettingsKeys::MESSAGE));
    }
}
```

Verified: the Unit suite (10 tests) gives 100% line coverage of `src/` minus the excluded `src/Controller`,
`src/Form`, and `index.php` guards (framework glue that needs a shop; PHPStan and the Integration suite cover it),
and the Integration suite passes inside both shops after `shop:install`.

### `tests/Integration/docker-compose.yml`

```yaml
# Local/CI shop for the Integration suite and lint:shop. PS_VERSION=8 or 9 (default 9).
services:
  mariadb:
    image: mariadb:11
    environment:
      MARIADB_ROOT_PASSWORD: prestashop
      MARIADB_DATABASE: prestashop
    healthcheck:
      test: ["CMD", "healthcheck.sh", "--connect", "--innodb_initialized"]
      interval: 5s
      timeout: 5s
      retries: 30

  prestashop:
    image: prestashop/prestashop:${PS_VERSION:-9}
    depends_on:
      mariadb:
        condition: service_healthy
    environment:
      DB_SERVER: mariadb
      DB_USER: root
      DB_PASSWD: prestashop
      DB_NAME: prestashop
      PS_INSTALL_AUTO: "1"
      PS_DOMAIN: localhost:${PS_PORT:-8080}
      PS_FOLDER_ADMIN: admin-dev
      PS_FOLDER_INSTALL: install-dev
      PS_DEV_MODE: "1"
    ports:
      - "${PS_PORT:-8080}:80"
    volumes:
      - ../..:/var/www/html/modules/<module>
    healthcheck:
      test: ["CMD-SHELL", "test -f /var/www/html/app/config/parameters.php && curl -fsS -o /dev/null http://localhost/"]
      interval: 10s
      timeout: 10s
      retries: 60
      start_period: 60s
```

Verified with `docker compose up -d --wait` for `PS_VERSION=8` and `9`: the image's auto-install
(`PS_INSTALL_AUTO=1`) finishes in under a minute after MariaDB is healthy, and the healthcheck only passes once
`app/config/parameters.php` exists. It does not install the module — `composer shop:install` does. The image
publishes a back office at `http://localhost:<PS_PORT>/admin-dev/` with the image's own default demo employee
(`ADMIN_MAIL`/`ADMIN_PASSWD` environment variables of the container) — local throwaway credentials, never a real
shop's. Pick a free `PS_PORT` when 8080 is taken.

## Browser UI tests (`package.json`, `playwright.config.ts`, `.ui-quality.yml`, `tests/e2e/`)

The catalog's UI contract for the storefront output: each component the module renders is opened in a real browser
(Playwright, Chromium) on a **running shop with the module installed** — the Docker shop above (`composer shop:up`,
`composer shop:install`) or any test shop — captured per state × direction × theme into
`<repo root>/.ui-screenshots/<kind>/<Id>/<state>--<direction>--<theme>.png` (`UI_SCREENSHOTS_DIR` overrides;
gitignored by `iru-setup-php-gitignore`) and checked with axe-core at the `.ui-quality.yml` level. There is no
`webServer`: the specs read the storefront URL from `PS_BASE_URL` and **skip themselves when it is unset**, so a
plain `npm run test:e2e` on a machine without a shop reports "skipped", which the gate reports as unverified (no
shop) — never as passed. Node is only test tooling here; nothing of it ships (`.distignore`).

```text
package.json                         # Playwright + axe-core only, private, never packaged
playwright.config.ts                 # baseURL = PS_BASE_URL, specs in tests/e2e/specs
.ui-quality.yml                      # accessibility level + screenshot matrix (repository root)
tests/e2e/support/ui-screenshots.ts  # the one shared helper every spec uses
tests/e2e/specs/<Id>.spec.ts         # one spec per component/screen, named after its Id
```

### `package.json`

```json
{
    "name": "<module>",
    "private": true,
    "description": "Browser UI tests for <display-name> (Playwright + axe-core); never shipped in the module zip.",
    "license": "<license-spdx>",
    "scripts": {
        "test:e2e": "playwright test"
    },
    "devDependencies": {
        "@axe-core/playwright": "^4.14.0",
        "@playwright/test": "^1.64.0",
        "@types/node": "^24.0.0"
    }
}
```

- `"private": true` and no `version` (the module's version lives in `<module>.php`/`config.xml`; nothing is published
  to npm). `license: proprietary` → `"UNLICENSED"` (npm's spelling).
- Versions: `@playwright/test` `^1.64.0` (1.64.1 resolved), `@axe-core/playwright` `^4.14.0` (4.14.0), `@types/node`
  `^24.0.0` (Node 24 LTS; only for `tsc`/editors — Playwright runs without it). Resolve the newest with
  `npm view <package> version` when writing it, then `npm install` (commits `package-lock.json`) and
  `npx playwright install chromium` (CI adds `--with-deps`).

### `playwright.config.ts`

```ts
/**
 * Browser UI tests (`npm run test:e2e`) against an already running shop with this module installed — the Docker
 * shop of tests/Integration/docker-compose.yml (`composer shop:up && composer shop:install`) or any test shop.
 * PS_BASE_URL is its storefront URL (e.g. http://localhost:8080); unset, every spec skips itself.
 */
import { defineConfig, devices } from '@playwright/test';

export default defineConfig( {
	testDir: './tests/e2e/specs',
	outputDir: './test-results',
	forbidOnly: !! process.env.CI,
	workers: 1,
	reporter: process.env.CI ? [ [ 'github' ], [ 'list' ] ] : 'list',
	use: {
		baseURL: process.env.PS_BASE_URL,
		locale: 'en-US',
		timezoneId: 'UTC',
		contextOptions: { reducedMotion: 'reduce' },
		trace: 'retain-on-failure',
		screenshot: 'only-on-failure',
	},
	projects: [
		{
			name: 'chromium',
			use: { ...devices[ 'Desktop Chrome' ], viewport: { width: 1280, height: 800 } },
		},
	],
} );
```

One worker (one shop, one database), fixed viewport/locale/time zone and reduced motion for deterministic captures.

### `.ui-quality.yml`

```yaml
# .ui-quality.yml — at the repository root; written by the iru-setup-* scaffolds, read by the coding and test skills.
accessibility: enforce      # enforce: violations fail the UI tests | report: listed, never failing | off: not run
directions: [ltr, rtl]      # screenshot matrix directions
themes: [light, dark]       # screenshot matrix themes
```

Exactly the contract's shape: write `enforce` as shown, or replace that one word with Step 0/2's `accessibility`
(`report` / `off`). A missing file means these defaults; `off` disables only the automated axe check.

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
 * Switches colour scheme / forced colours. Call it before `gotoInDirection()`, which applies the direction: a
 * PrestaShop storefront is right-to-left when it is served in a right-to-left shop language.
 */
export async function applyDirectionAndTheme( page: Page, direction: Direction, theme: Theme ): Promise< void > {
	await page.emulateMedia( {
		colorScheme: theme === 'dark' ? 'dark' : 'light',
		forcedColors: theme === 'high-contrast' ? 'active' : 'none',
	} );
	void direction; // Direction is a shop language, chosen by the URL in gotoInDirection().
}

/**
 * Opens a storefront path in the requested direction. With `PS_RTL_LANG` (the ISO code of a right-to-left language
 * installed in the test shop, e.g. `ar` or `he`) the right-to-left page is the shop's own (`/<iso>/<path>`, RTL
 * stylesheets, `body.lang-rtl`); without it the documented fallback sets `<html dir="rtl">` client-side and
 * annotates the test, since no RTL stylesheet is loaded then.
 */
export async function gotoInDirection(
	page: Page,
	path: string,
	direction: Direction,
	testInfo?: TestInfo
): Promise< void > {
	const rtlLanguage = process.env.PS_RTL_LANG;
	const relative = path.replace( /^\/+/, '' );
	await page.goto( direction === 'rtl' && rtlLanguage ? `/${ rtlLanguage }/${ relative }` : `/${ relative }` );
	if ( direction === 'rtl' && ! rtlLanguage ) {
		await page.evaluate( () => document.documentElement.setAttribute( 'dir', 'rtl' ) );
		testInfo?.annotations.push( {
			type: 'direction',
			description: 'PS_RTL_LANG unset: dir="rtl" set client-side, no RTL stylesheet loaded',
		} );
	}
}

/** Asserts the page is in the requested direction (`<html dir="rtl">` or PrestaShop's `body.lang-rtl`). */
export async function expectDirection( page: Page, direction: Direction ): Promise< void > {
	const rtl = page.locator( 'html[dir="rtl"], body.lang-rtl' );
	if ( direction === 'rtl' ) {
		await expect( rtl.first() ).toBeAttached();
	} else {
		await expect( rtl ).toHaveCount( 0 );
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

- The same helper as every PHP flavor of this catalog (`uiQuality()`, `screenshotsDir()`, `applyDirectionAndTheme()`,
  `expectDirection()`, `captureUiState()` with an optional component `locator`, `checkAccessibility()` with an
  optional `include` scope), plus PrestaShop's direction switch `gotoInDirection()`.
- **Direction**: a storefront is right-to-left when served in a right-to-left shop language. Install one in the test
  shop (International → Localization → Languages, e.g. Arabic `ar` or Hebrew `he`; the Docker shop has only the
  install language) and export `PS_RTL_LANG=<iso>`: the right-to-left captures are then the shop's real
  `/<iso>/…` pages with the theme's RTL stylesheet (`body.lang-rtl`). Without it the documented fallback sets
  `<html dir="rtl">` client-side and annotates the test — layout direction only, no RTL stylesheet.
- **Dark**: `emulateMedia( { colorScheme: 'dark' } )`; the classic theme has no dark styles, so dark captures match
  light ones until the module's CSS (or the shop's theme) adds a `prefers-color-scheme: dark` block.

### `tests/e2e/specs/FooterMessage.spec.ts`

```ts
/**
 * Component UI test of FooterMessage — the greeting the displayFooter hook renders from
 * views/templates/hook/footer.tpl (variables from FooterMessagePresenter) on every storefront page. Behaviour is
 * asserted once; the state is captured across `.ui-quality.yml` directions × themes.
 */
import { expect, test } from '@playwright/test';
import {
	applyDirectionAndTheme,
	captureUiState,
	checkAccessibility,
	expectDirection,
	gotoInDirection,
	uiQuality,
} from '../support/ui-screenshots';

test.skip( ! process.env.PS_BASE_URL, 'PS_BASE_URL is not set: start a shop with the module installed first' );

test.describe( 'FooterMessage', () => {
	test( 'renders the default state', async ( { page }, testInfo ) => {
		const { directions, themes } = uiQuality();
		for ( const direction of directions ) {
			for ( const theme of themes ) {
				await applyDirectionAndTheme( page, direction, theme );
				await gotoInDirection( page, '/', direction, testInfo );
				const message = page.locator( '.<module>-message' );
				await expect( message ).toBeVisible();
				await expect( message ).not.toHaveText( '' );
				await expectDirection( page, direction );
				await captureUiState( page, {
					kind: 'component',
					id: 'FooterMessage',
					state: 'default',
					direction,
					theme,
					locator: message,
				} );
				if ( direction === directions[ 0 ] && theme === themes[ 0 ] ) {
					await checkAccessibility( page, testInfo, { include: '.<module>-message' } );
				}
			}
		}
	} );
} );
```

- Component Id `FooterMessage`: the greeting block `views/templates/hook/footer.tpl` renders (variables from
  `FooterMessagePresenter`) through `hookDisplayFooter`. It appears on every storefront page, so the spec opens the
  home page; the element screenshot and the `include`-scoped axe check keep the theme's own markup out of the
  component's result. State `default` = the install defaults (`composer shop:install`); add a state per variation
  the template renders (e.g. `long-content` with a long message saved in the configuration page).
- Run: `composer shop:up && composer shop:install`, then
  `PS_BASE_URL=http://localhost:${PS_PORT:-8080} npm run test:e2e` (optionally `PS_RTL_LANG=ar`). Verified against a
  static stand-in page (all 4 captures written, axe clean) and skipped without `PS_BASE_URL`; not run against a shop.

### `phpstan.dist.neon`

```yaml
# Unit-level static analysis: no shop needed. PrestaShop's legacy classes come from minimal stubs
# (tests/phpstan/stubs/); Symfony-bound code (src/Controller, src/Form) is analysed by tests/phpstan/phpstan.neon
# against a real shop (composer lint:shop).
parameters:
  level: 6
  paths:
    - <module>.php
    - src
    - tests/Unit
    - upgrade
  excludePaths:
    - src/Controller
    - src/Form
  bootstrapFiles:
    - tests/phpstan/stubs/constants.php
  scanFiles:
    - tests/phpstan/stubs/prestashop.stub.php
  dynamicConstantNames:
    - _PS_VERSION_
```

### `tests/phpstan/phpstan.neon`

```yaml
# Shop-level static analysis with prestashop/php-dev-tools: needs a PrestaShop installation.
#   _PS_ROOT_DIR_=/path/to/prestashop vendor/bin/phpstan analyse -c tests/phpstan/phpstan.neon --memory-limit=1G
# (composer lint:shop runs it inside the Docker shop of tests/Integration/docker-compose.yml.)
# Paths are relative to this file.
includes:
  - %currentWorkingDirectory%/vendor/prestashop/php-dev-tools/phpstan/ps-module-extension.neon

parameters:
  level: 6
  paths:
    - ../../<module>.php
    - ../../src
    - ../../upgrade
  reportUnmatchedIgnoredErrors: false
```

- **Two configs, both level 6.** `phpstan.dist.neon` (root, picked up by `iru-php-code-quality` and
  `composer lint` with no shop) analyses the main class, `src/` minus the Symfony glue, `tests/Unit`, and `upgrade/`
  against the minimal stubs below. `tests/phpstan/phpstan.neon` is `prestashop/php-dev-tools`' module extension
  against a real shop (`_PS_ROOT_DIR_`), covering the glue too. Both report no errors on the scaffold (unit config
  also clean at `max`; the shop config at `max` reports 13 errors from the core's loosely typed APIs, e.g. `mixed`
  form data — raise the level only together with fixes).
- `php vendor/bin/prestashop-coding-standards phpstan:init` would generate a config with `excludes_analyse` (rejected
  by PHPStan 2.x) and `paths: [./]` relative to `tests/phpstan/` — don't use it; this template replaces it.
- The shop config needs the shop's sources: run it with `composer lint:shop` (inside the Docker shop) or
  `_PS_ROOT_DIR_=/path/to/prestashop vendor/bin/phpstan analyse -c tests/phpstan/phpstan.neon --memory-limit=1G`.
  The extension's bootstrap exits with `[ERROR] Define _PS_ROOT_DIR_` when it is unset.

### `tests/phpstan/stubs/constants.php`

```php
<?php

<license-header>

declare(strict_types=1);

// Constants PrestaShop defines at boot, for the unit-level PHPStan run only (phpstan.dist.neon).
define('_PS_VERSION_', '9.0.0');
define('_DB_PREFIX_', 'ps_');
define('_PS_MODULE_DIR_', '/var/www/html/modules/');
```

### `tests/phpstan/stubs/prestashop.stub.php`

```php
<?php

<license-header>

// Minimal signatures of the PrestaShop legacy classes the module calls, for the unit-level PHPStan run only
// (phpstan.dist.neon scanFiles: never loaded at runtime). Mirror the real 8.x/9.x signatures; add a member here
// when the module starts calling it. tests/phpstan/phpstan.neon checks against the real classes.

class Smarty
{
    /**
     * @param array<string, mixed>|string $tpl_var
     * @param mixed $value
     *
     * @return static
     */
    public function assign($tpl_var, $value = null, $nocache = false)
    {
        return $this;
    }
}

class Link
{
    /**
     * @param string $controller
     * @param bool $withToken
     * @param array<string, mixed> $sfRouteParams
     * @param array<string, mixed> $params
     *
     * @return string
     */
    public function getAdminLink($controller, $withToken = true, $sfRouteParams = [], $params = [])
    {
        return '';
    }
}

class Context
{
    /** @var Smarty */
    public $smarty;

    /** @var Link */
    public $link;
}

class Shop
{
    public const CONTEXT_ALL = 4;

    /** @return bool */
    public static function isFeatureActive()
    {
        return false;
    }

    /**
     * @param int $type
     * @param int|null $id
     *
     * @return void
     */
    public static function setContext($type, $id = null)
    {
    }
}

class Configuration
{
    /**
     * @param string $key
     *
     * @return string|false
     */
    public static function get($key, $idLang = null, $idShopGroup = null, $idShop = null, $default = false)
    {
        return false;
    }

    /**
     * @param string $key
     * @param string|int|bool|array<int, string> $values
     *
     * @return bool
     */
    public static function updateValue($key, $values, $html = false, $idShopGroup = null, $idShop = null)
    {
        return true;
    }

    /**
     * @param string $key
     *
     * @return bool
     */
    public static function deleteByName($key)
    {
        return true;
    }
}

class Tools
{
    /**
     * @param string $url
     *
     * @return void
     */
    public static function redirectAdmin($url)
    {
    }
}

class Db
{
    /** @return static */
    public static function getInstance($master = true)
    {
        return new static();
    }

    /**
     * @param string $sql
     *
     * @return bool
     */
    public function execute($sql, $use_cache = true)
    {
        return true;
    }

    /**
     * @param string $sql
     *
     * @return string|false|null
     */
    public function getValue($sql, $use_cache = true)
    {
        return false;
    }
}

abstract class Module
{
    /** @var string */
    public $name;

    /** @var string */
    public $tab;

    /** @var string */
    public $version;

    /** @var string */
    public $author;

    /** @var int */
    public $need_instance;

    /** @var array{min: string, max: string} */
    public $ps_versions_compliancy;

    /** @var bool */
    public $bootstrap;

    /** @var string */
    public $displayName;

    /** @var string */
    public $description;

    /** @var string */
    public $confirmUninstall;

    /** @var Context */
    protected $context;

    /**
     * @param string|null $name
     */
    public function __construct($name = null, ?Context $context = null)
    {
    }

    /** @return bool */
    public function install()
    {
        return true;
    }

    /** @return bool */
    public function uninstall()
    {
        return true;
    }

    /**
     * @param string|array<int, string> $hook_name
     * @param array<int, int>|null $shop_list
     *
     * @return bool
     */
    public function registerHook($hook_name, $shop_list = null)
    {
        return true;
    }

    /**
     * @param string $hook
     *
     * @return bool
     */
    public function isRegisteredInHook($hook)
    {
        return true;
    }

    /**
     * @param string $templatePath
     * @param string|null $cache_id
     * @param string|null $compile_id
     *
     * @return string
     */
    public function fetch($templatePath, $cache_id = null, $compile_id = null)
    {
        return '';
    }

    /**
     * @param string $id
     * @param array<string, mixed> $parameters
     * @param string|null $domain
     * @param string|null $locale
     *
     * @return string
     */
    public function trans($id, array $parameters = [], $domain = null, $locale = null)
    {
        return $id;
    }

    /**
     * @param string $module_name
     *
     * @return Module|false
     */
    public static function getInstanceByName($module_name)
    {
        return false;
    }
}
```

The stubs mirror the real 8.x/9.x signatures of exactly the members the module calls; extend them when the module
starts calling more (PHPStan reports `class.notFound`/`method.notFound` in the unit run otherwise). They are scanned,
never loaded at runtime, and `tests/` is excluded from the zip.

### `.php-cs-fixer.dist.php`

```php
<?php

$config = new PrestaShop\CodingStandards\CsFixer\Config();

/** @var Symfony\Component\Finder\Finder $finder */
$finder = $config->setUsingCache(true)->getFinder();
$finder->in(__DIR__)->exclude(['vendor', 'build', 'node_modules', 'docs']);

return $config;
```

Same shape as `vendor/bin/prestashop-coding-standards cs-fixer:init` writes (PrestaShop's ruleset: `@Symfony` plus
PrestaShop's overrides, risky rules allowed), with generated directories excluded. PHP-CS-Fixer ran clean on PHP 8.4
without `PHP_CS_FIXER_IGNORE_ENV`.

### `.distignore`

```text
# Paths left out of the release zip built by bin/package.sh (rsync --exclude-from patterns; a leading / anchors
# at the module root). vendor/ is rebuilt with --no-dev inside the package.
/.git
/.github
/.gitignore
/.gitattributes
/.distignore
/.editorconfig
/.php-cs-fixer.dist.php
/.php-cs-fixer.cache
/.phpunit.cache
/.phpunit.result.cache
/.integration-test.lock
/.claude
/.idea
/.vscode
/bin
/build
/docs
/node_modules
/tests
/vendor
/test-results
/playwright-report
/blob-report
/.ui-screenshots
/.ui-quality.yml
/package.json
/package-lock.json
/playwright.config.ts
/phpstan.dist.neon
/phpunit.xml.dist
/sonar-project.properties
/CHANGELOG.md
/README.md
/CLAUDE.md
/implementation_plan.md
/.archive
/upgrade/README.md
*.zip
.DS_Store
```

### `bin/package.sh`

```bash
#!/usr/bin/env bash
# Builds build/<module>-<version>.zip: the module directory as PrestaShop expects it inside the archive
# (<module>/...), without the paths in .distignore, with production-only Composer dependencies, and with the
# index.php directory guard in every folder (PrestaShop Addons requirement). Needs bash, rsync, zip, composer.
set -euo pipefail

MODULE="<module>"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$(sed -n "s/.*\$this->version = '\([^']*\)'.*/\1/p" "$ROOT/$MODULE.php" | head -n 1)"
if [ -z "$VERSION" ]; then
  echo "Could not read \$this->version from $MODULE.php" >&2
  exit 1
fi

for tool in rsync zip composer; do
  command -v "$tool" >/dev/null 2>&1 || { echo "$tool is required to package the module" >&2; exit 1; }
done

STAGE="$ROOT/build/package"
TARGET="$STAGE/$MODULE"
ZIP="$ROOT/build/$MODULE-$VERSION.zip"

rm -rf "$STAGE" "$ZIP"
mkdir -p "$TARGET"
rsync -a --exclude-from="$ROOT/.distignore" "$ROOT/" "$TARGET/"

composer install --working-dir="$TARGET" --no-dev --optimize-autoloader --no-interaction --no-progress --quiet

# Every directory gets the redirect guard (vendor/ included); existing ones are kept.
find "$TARGET" -type d | while IFS= read -r dir; do
  [ -f "$dir/index.php" ] || cp "$ROOT/index.php" "$dir/index.php"
done

(cd "$STAGE" && zip -qr -X "$ZIP" "$MODULE")
echo "Built $ZIP"
```

Verified (in `composer:2` with `rsync` added): produces `build/<module>-<version>.zip` containing `<module>/` with
the main file, `config.xml`, `logo.png`, `composer.json`/`composer.lock`, `config/`, `src/`, `views/`, `upgrade/`
(without its README), `translations/`, and a `--no-dev` `vendor/`, an `index.php` in every directory — and none of
the tests, tooling configs, `docs/`, or `bin/` — nor, since the browser UI tests, `package.json`/`package-lock.json`,
`node_modules/`, `playwright.config.ts`, `tests/e2e/`, Playwright output or `.ui-screenshots/` (verified with
`rsync -a --exclude-from=.distignore`). That zip, unzipped into `modules/` of a clean 9.x shop and installed
with `bin/console`, passed the same back-office and storefront checks as the development copy. `chmod +x
bin/package.sh` after writing it.

### `sonar-project.properties`

```properties
sonar.organization=<sonar-organization>
sonar.projectKey=<sonar-project-key>
sonar.sources=<module>.php,src,upgrade,views,config
sonar.tests=tests
sonar.exclusions=**/index.php,vendor/**,build/**
sonar.php.coverage.reportPaths=build/logs/clover.xml
sonar.php.tests.reportPath=build/logs/junit.xml
sonar.php.phpstan.reportPaths=build/logs/phpstan.json
```

Only when `sonar` ≠ `none`; drop the `sonar.organization` line for a self-hosted server without organizations.
`build/logs/phpstan.json` is written by `composer lint:report` (verified: valid PHPStan JSON, exit 0 on the
scaffold), which CI runs before the scanner; Clover/JUnit come from `composer test:coverage`.
