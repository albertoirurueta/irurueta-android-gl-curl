# Block theme templates (`flavor: theme`)

Everything below was generated with these exact templates (placeholders from SKILL.md Step 5 filled with a sample
identity), then `composer install`, `composer test`, `composer test:coverage` (100% lines on `src/`),
`composer lint` (PHPStan level 6, 0 errors), `composer format:check` (0 issues), and `npm run test:env:start && npm
run test:integration` (wp-env 11.17, WordPress 7.1.3, PHP 8.2 container, 4/4 integration tests: theme active and
recognized as a block theme, `theme.json` palette loaded, both patterns registered, stylesheet enqueued) all passed.
Copy every file verbatim except for the placeholders.

A block theme is markup and design tokens first: `theme.json` (schema **version 3**, WordPress 6.6+ — so
`<wp-floor>` is never below `6.6` for a theme) owns settings and styles, `templates/` and `parts/` hold block markup
only (no PHP), anything translatable lives in `patterns/*.php`, and PHP behavior goes through namespaced classes in
`src/` that `functions.php` requires explicitly. The theme needs **no `vendor/` at runtime** — Composer is only the
dev toolchain (and the unit suite's autoloader), so the release zip excludes `vendor/` (unlike the plugin).

## Theme header and design tokens

### `style.css`

```css
/*
Theme Name: <name>
Theme URI: <repository-url>
Author: <developer-name>
Author URI: <organization-url>
Description: <description>
Requires at least: <wp-floor>
Tested up to: <wp-tested>
Requires PHP: <php-floor>
Version: <dev-version>
License: <license-display-name>
License URI: <license-uri>
Text Domain: <slug>
Domain Path: /languages
Tags: block-patterns, full-site-editing, wide-blocks
*/

/* Design tokens and global styles live in theme.json; keep this file for rules theme.json cannot express. */

/* Dark colour scheme: theme.json has no media queries, so the palette is re-pointed here (contrast ≥ 4.5:1). */
@media (prefers-color-scheme: dark) {
	html:root {
		color-scheme: dark;
		--wp--preset--color--base: #1e1e1e;
		--wp--preset--color--contrast: #f0f0f0;
		--wp--preset--color--accent: #8ea2ff;
	}
}
```

- `Version:` is the theme version `iru-php-bump-version` rewrites (`x.y.z-dev` during development).
- **License "No license"**: `License: Proprietary`, drop `License URI` (and the theme can't be listed on
  WordPress.org); GPL-3.0-or-later: `GNU General Public License v3 or later` /
  `https://www.gnu.org/licenses/gpl-3.0.html`.
- `Tags:` must come from the WordPress.org theme tag list when `distribution: store`.
- The dark block follows the visitor's `prefers-color-scheme` (the UI contract's dark theme, captured by the
  browser UI test through `emulateMedia`). `html:root` outranks the `:root` rule of WordPress's global-styles inline
  CSS whatever the load order; text/base 14.6:1, accent/base 7.0:1 (light: 16.7:1 and 5.6:1). Drop it only if the
  design is deliberately light-only, and then narrow `.ui-quality.yml`'s `themes` to `[light]`.
- A `screenshot.png` (1200×900) is required by the theme directory and shown in Appearance → Themes — this skill
  doesn't generate one; Step 9 reminds the user.

### `theme.json`

```json
{
	"$schema": "https://schemas.wp.org/trunk/theme.json",
	"version": 3,
	"settings": {
		"appearanceTools": true,
		"useRootPaddingAwareAlignments": true,
		"layout": {
			"contentSize": "720px",
			"wideSize": "1200px"
		},
		"color": {
			"defaultPalette": false,
			"palette": [
				{ "slug": "base", "color": "#ffffff", "name": "Base" },
				{ "slug": "contrast", "color": "#1e1e1e", "name": "Contrast" },
				{ "slug": "accent", "color": "#3858e9", "name": "Accent" }
			]
		},
		"typography": {
			"fluid": true,
			"defaultFontSizes": false,
			"fontFamilies": [
				{
					"slug": "system",
					"name": "System",
					"fontFamily": "-apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, Oxygen-Sans, Ubuntu, Cantarell, \"Helvetica Neue\", sans-serif"
				}
			],
			"fontSizes": [
				{ "slug": "small", "size": "0.875rem", "name": "Small" },
				{ "slug": "medium", "size": "1rem", "name": "Medium" },
				{ "slug": "large", "size": "1.5rem", "name": "Large" },
				{ "slug": "x-large", "size": "2.25rem", "name": "Extra large" }
			]
		},
		"spacing": {
			"units": [ "px", "em", "rem", "%", "vh", "vw" ]
		}
	},
	"styles": {
		"color": {
			"background": "var:preset|color|base",
			"text": "var:preset|color|contrast"
		},
		"typography": {
			"fontFamily": "var:preset|font-family|system",
			"fontSize": "var:preset|font-size|medium",
			"lineHeight": "1.6"
		},
		"spacing": {
			"padding": {
				"left": "1.5rem",
				"right": "1.5rem"
			}
		},
		"elements": {
			"link": {
				"color": { "text": "var:preset|color|accent" }
			},
			"h1": {
				"typography": { "fontSize": "var:preset|font-size|x-large" }
			}
		}
	},
	"templateParts": [
		{ "name": "header", "title": "Header", "area": "header" },
		{ "name": "footer", "title": "Footer", "area": "footer" }
	]
}
```

## Templates, parts, patterns

### `templates/index.html`

```html
<!-- wp:template-part {"slug":"header","tagName":"header"} /-->

<!-- wp:group {"tagName":"main","layout":{"type":"constrained"}} -->
<main class="wp-block-group">
	<!-- wp:query {"queryId":1,"query":{"perPage":10,"pages":0,"offset":0,"postType":"post","order":"desc","orderBy":"date","inherit":true}} -->
	<div class="wp-block-query">
		<!-- wp:post-template -->
			<!-- wp:post-title {"isLink":true} /-->
			<!-- wp:post-excerpt /-->
		<!-- /wp:post-template -->

		<!-- wp:query-pagination -->
			<!-- wp:query-pagination-previous /-->
			<!-- wp:query-pagination-numbers /-->
			<!-- wp:query-pagination-next /-->
		<!-- /wp:query-pagination -->

		<!-- wp:query-no-results -->
			<!-- wp:pattern {"slug":"<slug>/no-results"} /-->
		<!-- /wp:query-no-results -->
	</div>
	<!-- /wp:query -->
</main>
<!-- /wp:group -->

<!-- wp:template-part {"slug":"footer","tagName":"footer"} /-->
```

### `parts/header.html`

```html
<!-- wp:group {"layout":{"type":"constrained"}} -->
<div class="wp-block-group">
	<!-- wp:group {"layout":{"type":"flex","justifyContent":"space-between"}} -->
	<div class="wp-block-group">
		<!-- wp:site-title /-->
		<!-- wp:navigation /-->
	</div>
	<!-- /wp:group -->
</div>
<!-- /wp:group -->
```

### `parts/footer.html`

```html
<!-- wp:group {"layout":{"type":"constrained"}} -->
<div class="wp-block-group">
	<!-- wp:pattern {"slug":"<slug>/footer-credits"} /-->
</div>
<!-- /wp:group -->
```

### `patterns/footer-credits.php`

```php
<?php
/**
 * Title: Footer credits
 * Slug: <slug>/footer-credits
 * Categories: <slug>, footer
 * Inserter: no
 *
 * @package <namespace>
 * @since   <first-release>
 */

defined( 'ABSPATH' ) || exit;
?>
<!-- wp:paragraph {"align":"center","fontSize":"small"} -->
<p class="has-text-align-center has-small-font-size"><?php esc_html_e( 'Proudly powered by WordPress', '<slug>' ); ?></p>
<!-- /wp:paragraph -->
```

### `patterns/no-results.php`

```php
<?php
/**
 * Title: No results
 * Slug: <slug>/no-results
 * Categories: <slug>
 * Inserter: no
 *
 * @package <namespace>
 * @since   <first-release>
 */

defined( 'ABSPATH' ) || exit;
?>
<!-- wp:paragraph -->
<p><?php esc_html_e( 'Nothing was found. Try a search instead.', '<slug>' ); ?></p>
<!-- /wp:paragraph -->

<!-- wp:search {"label":"<?php echo esc_attr_x( 'Search', 'search form label', '<slug>' ); ?>","showLabel":false,"buttonText":"<?php echo esc_attr_x( 'Search', 'search button text', '<slug>' ); ?>"} /-->
```

Pattern headers (`Title`, `Slug` = `<slug>/<name>`, `Categories`, `Inserter`) are parsed from the file by
WordPress 6.0+, which registers every `patterns/*.php` automatically; templates reference them with
`<!-- wp:pattern {"slug":"…"} /-->`. Translatable strings use the escaping i18n functions with the literal text
domain; the `ABSPATH` guard follows the catalog rule for PHP files with top-level code.

## PHP (`functions.php` + `src/`)

### `functions.php`

```php
<?php
/**
 * Theme bootstrap: loads the namespaced classes and registers their hooks.
 *
 * Block themes get their supports from theme.json; this file only wires PHP behavior.
 *
 * @package <namespace>
 */

defined( 'ABSPATH' ) || exit;

// Namespaced classes (src/ is PSR-4 for Composer's dev autoloader; the theme itself needs no vendor/ at runtime).
require_once __DIR__ . '/src/Theme.php';

( new \<namespace>\Theme( (string) wp_get_theme()->get( 'Version' ) ) )->register();
```

### `src/Theme.php`

```php
<?php
/**
 * Theme setup.
 *
 * @package <namespace>
 */

namespace <namespace>;

/**
 * Registers the theme's stylesheets, translations and pattern category.
 *
 * @since <first-release>
 */
final class Theme {

	/**
	 * Theme version, used to bust stylesheet caches.
	 *
	 * @var string
	 */
	private string $version;

	/**
	 * Creates the theme setup.
	 *
	 * @since <first-release>
	 *
	 * @param string $version Theme version from the style.css header.
	 */
	public function __construct( string $version ) {
		$this->version = $version;
	}

	/**
	 * Registers the theme hooks.
	 *
	 * @since <first-release>
	 */
	public function register(): void {
		add_action( 'after_setup_theme', array( $this, 'setup' ) );
		add_action( 'wp_enqueue_scripts', array( $this, 'enqueue_styles' ) );
		add_action( 'init', array( $this, 'register_pattern_categories' ) );
	}

	/**
	 * Loads bundled translations and the editor stylesheet.
	 *
	 * @since <first-release>
	 */
	public function setup(): void {
		load_theme_textdomain( '<slug>', get_template_directory() . '/languages' );
		add_editor_style( 'style.css' );
	}

	/**
	 * Enqueues the theme stylesheet on the front end.
	 *
	 * @since <first-release>
	 */
	public function enqueue_styles(): void {
		wp_enqueue_style( '<slug>-style', get_stylesheet_uri(), array(), $this->version );
	}

	/**
	 * Registers the pattern category used by the theme's patterns.
	 *
	 * @since <first-release>
	 */
	public function register_pattern_categories(): void {
		register_block_pattern_category(
			'<slug>',
			array( 'label' => __( '<name>', '<slug>' ) )
		);
	}
}
```

Add each new `src/` class to `functions.php` with its own `require_once` (the theme has no runtime autoloader);
Composer's PSR-4 map covers the same classes for the unit suite. Business logic (custom post types, REST endpoints,
settings) belongs in a plugin, not the theme.

## Unit tests (`tests/Unit/`, Brain Monkey)

`tests/Unit/bootstrap.php` and `tests/Unit/TestCase.php` come from `tooling.md`.

### `tests/Unit/ThemeTest.php`

```php
<?php
/**
 * Tests for Theme.
 *
 * @package <namespace>
 */

namespace <namespace>\Tests\Unit;

use <namespace>\Theme;
use Brain\Monkey\Functions;

/**
 * @covers \<namespace>\Theme
 */
final class ThemeTest extends TestCase {

	public function test_register_hooks_setup_styles_and_patterns(): void {
		$theme = new Theme( '1.2.3' );

		$theme->register();

		self::assertNotFalse( has_action( 'after_setup_theme', array( $theme, 'setup' ) ) );
		self::assertNotFalse( has_action( 'wp_enqueue_scripts', array( $theme, 'enqueue_styles' ) ) );
		self::assertNotFalse( has_action( 'init', array( $theme, 'register_pattern_categories' ) ) );
	}

	public function test_setup_loads_bundled_translations_and_editor_styles(): void {
		Functions\when( 'get_template_directory' )->justReturn( '/themes/<slug>' );
		Functions\expect( 'load_theme_textdomain' )->once()->with( '<slug>', '/themes/<slug>/languages' );
		Functions\expect( 'add_editor_style' )->once()->with( 'style.css' );

		( new Theme( '1.2.3' ) )->setup();
	}

	public function test_enqueue_styles_versions_the_stylesheet(): void {
		Functions\when( 'get_stylesheet_uri' )->justReturn( 'https://example.org/wp-content/themes/<slug>/style.css' );
		Functions\expect( 'wp_enqueue_style' )
			->once()
			->with( '<slug>-style', 'https://example.org/wp-content/themes/<slug>/style.css', array(), '1.2.3' );

		( new Theme( '1.2.3' ) )->enqueue_styles();
	}

	public function test_registers_the_theme_pattern_category(): void {
		Functions\expect( 'register_block_pattern_category' )
			->once()
			->with( '<slug>', array( 'label' => '<name>' ) );

		( new Theme( '1.2.3' ) )->register_pattern_categories();
	}
}
```

## Integration tests (`tests/Integration/`, `WP_UnitTestCase` inside wp-env)

### `tests/Integration/bootstrap.php`

```php
<?php
/**
 * Integration-suite bootstrap: loads the WordPress PHPUnit test library with this theme active.
 *
 * Runs inside @wordpress/env, which sets WP_TESTS_DIR; elsewhere, point WP_TESTS_DIR at a test library
 * installed with bin/install-wp-tests.sh.
 *
 * @package <namespace>
 */

require_once dirname( __DIR__, 2 ) . '/vendor/autoload.php';

$<prefix>_tests_dir = getenv( 'WP_TESTS_DIR' );
if ( false === $<prefix>_tests_dir || '' === $<prefix>_tests_dir ) {
	$<prefix>_tests_dir = rtrim( sys_get_temp_dir(), '/\\' ) . '/wordpress-tests-lib';
}

if ( ! is_readable( $<prefix>_tests_dir . '/includes/functions.php' ) ) {
	fwrite( STDERR, "WordPress test library not found in {$<prefix>_tests_dir}: run this suite through `npm run test:integration` (wp-env) or set WP_TESTS_DIR.\n" ); // phpcs:ignore WordPress.WP.AlternativeFunctions.file_system_operations_fwrite
	exit( 1 );
}

require_once $<prefix>_tests_dir . '/includes/functions.php';

tests_add_filter(
	'muplugins_loaded',
	static function (): void {
		$theme_dir  = dirname( __DIR__, 2 );
		$theme_root = dirname( $theme_dir );
		$stylesheet = basename( $theme_dir );

		register_theme_directory( $theme_root );
		add_filter(
			'pre_option_template',
			static function () use ( $stylesheet ): string {
				return $stylesheet;
			}
		);
		add_filter(
			'pre_option_stylesheet',
			static function () use ( $stylesheet ): string {
				return $stylesheet;
			}
		);
	}
);

require $<prefix>_tests_dir . '/includes/bootstrap.php';
```

### `tests/Integration/ThemeTest.php`

```php
<?php
/**
 * Integration tests for the theme running inside WordPress.
 *
 * @package <namespace>
 */

namespace <namespace>\Tests\Integration;

use WP_Block_Patterns_Registry;
use WP_UnitTestCase;

/**
 * Exercises the theme against a real WordPress install.
 */
final class ThemeTest extends WP_UnitTestCase {

	public function test_the_theme_is_active_and_a_block_theme(): void {
		self::assertSame( '<slug>', get_stylesheet() );
		self::assertTrue( wp_is_block_theme() );
	}

	public function test_theme_json_palette_is_loaded(): void {
		$palette = wp_get_global_settings( array( 'color', 'palette', 'theme' ) );

		self::assertContains( 'accent', array_column( $palette, 'slug' ) );
	}

	public function test_patterns_are_registered(): void {
		$registry = WP_Block_Patterns_Registry::get_instance();

		self::assertTrue( $registry->is_registered( '<slug>/footer-credits' ) );
		self::assertTrue( $registry->is_registered( '<slug>/no-results' ) );
	}

	public function test_the_stylesheet_is_enqueued_on_the_front_end(): void {
		do_action( 'wp_enqueue_scripts' );

		self::assertTrue( wp_style_is( '<slug>-style', 'enqueued' ) );
	}
}
```

The bootstrap is the `wp scaffold theme-tests` approach: register the parent directory as a theme root and force
`template`/`stylesheet` to this theme via `pre_option_*` filters on `muplugins_loaded`, so the test install runs
with the theme active without touching the database first.

## Browser UI test (`tests/e2e/specs/`, Playwright against the wp-env tests instance)

`.ui-quality.yml`, `playwright.config.ts`, the helper `tests/e2e/support/ui-screenshots.ts` and the RTL mu-plugin
come from `tooling.md` "Browser UI tests". The sample screen is the front page rendered by `templates/index.html`;
its screen Id is the template slug in PascalCase, `Index` (a block theme has no class per screen — name further
screens the same way: `templates/single.html` → `Single`, `parts/header.html` as a component → `Header`). Its post
list is already a paginated collection (`wp:query` with `perPage: 10` + `wp:query-pagination`), which is the
server-rendered form of the contract's virtualized list — keep every new query loop paginated.

### `tests/e2e/specs/Index.spec.ts`

```ts
/**
 * Screen UI test of the front page (`templates/index.html`, screen Id `Index`): the latest-posts query loop with its
 * pagination and the no-results pattern. Behaviour is asserted once; every state is captured across
 * `.ui-quality.yml` directions × themes, as a logged-out visitor (no admin bar).
 */
import { expect, test } from '@wordpress/e2e-test-utils-playwright';
import {
	applyDirectionAndTheme,
	captureUiState,
	checkAccessibility,
	expectDirection,
	uiQuality,
} from '../support/ui-screenshots';

/** Visual states and the post titles each one publishes (typical-data mixes Latin and Arabic script). */
const STATES: Record< string, string[] > = {
	empty: [],
	'typical-data': [ 'Opening hours', 'New arrivals this week', 'مواعيد العمل في المكتبة' ],
};

test.describe( 'Index', () => {
	test.use( { storageState: { cookies: [], origins: [] } } );

	test.beforeAll( async ( { requestUtils } ) => {
		await requestUtils.activateTheme( '<slug>' );
	} );

	test.afterAll( async ( { requestUtils } ) => {
		await requestUtils.deleteAllPosts();
	} );

	for ( const [ state, titles ] of Object.entries( STATES ) ) {
		test( `renders the ${ state } state`, async ( { page, requestUtils }, testInfo ) => {
			await requestUtils.deleteAllPosts();
			for ( const [ index, title ] of titles.entries() ) {
				await requestUtils.createPost( {
					title,
					content: '<!-- wp:paragraph --><p>Excerpt text.</p><!-- /wp:paragraph -->',
					status: 'publish',
					date_gmt: `2026-01-1${ index + 1 }T10:00:00`,
				} );
			}

			const { directions, themes } = uiQuality();
			for ( const direction of directions ) {
				for ( const theme of themes ) {
					await applyDirectionAndTheme( page, direction, theme );
					await page.goto( '/' );
					const main = page.getByRole( 'main' );
					if ( titles.length === 0 ) {
						await expect( main.getByText( 'Nothing was found. Try a search instead.' ) ).toBeVisible();
					} else {
						await expect( main.getByRole( 'heading', { level: 2 } ) ).toHaveCount( titles.length );
					}
					await expectDirection( page, direction );
					await captureUiState( page, { kind: 'screen', id: 'Index', state, direction, theme } );
					if ( direction === directions[ 0 ] && theme === themes[ 0 ] ) {
						await checkAccessibility( page, testInfo );
					}
				}
			}
		} );
	}
} );
```

- `requestUtils.activateTheme( '<slug>' )` activates the theme wp-env mounted (its directory name — SKILL.md Step 1
  warns when it differs from the slug). Posts are created and deleted over the REST API of the **tests** instance
  only, with fixed dates so captures are deterministic.
- `test.use( { storageState: … } )` renders as a logged-out visitor (no admin bar in the screen captures); the REST
  helpers keep their own admin session.
- It writes `.ui-screenshots/screen/Index/{empty,typical-data}--{ltr,rtl}--{light,dark}.png` (8 full-page files with
  the default `.ui-quality.yml`) and runs axe-core on the whole page once per state.
- Run: `npm run test:env:start` (or let the config start it), `npx playwright install chromium` once, then
  `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm run test:e2e`.

## Distribution files

### `readme.txt`

```text
=== <name> ===
Contributors: <wporg-username>
Requires at least: <wp-floor>
Tested up to: <wp-tested>
Requires PHP: <php-floor>
License: <readme-license>
License URI: <license-uri>

<description>

== Description ==

<description>

== Changelog ==

= <first-release> =
* Initial release.

== Copyright ==

<name> WordPress Theme, (C) <copyright-year> <developer-name>.
<name> is distributed under the terms of the GNU GPL v2 or later.
```

Theme `readme.txt` (WordPress.org theme format): no `Stable tag:` — themes take their version from `style.css` —
and a `== Copyright ==` section naming the theme's own copyright plus any bundled third-party resources (fonts,
images) with their licenses, which the theme review team checks.

### `languages/.gitkeep (empty)`

```text

```

### `.distignore`

```text
# Excluded from the release zip (wp dist-archive). The theme needs no vendor/ at runtime.
/vendor
/.git
/.github
/.claude
/.wordpress-org
/.integration-test.lock
/build/logs
/docs
/node_modules
/tests
/bin
/artifacts
/test-results
/playwright-report
/blob-report
/.ui-screenshots
.distignore
.editorconfig
.gitattributes
.gitignore
.wp-env.json
.wp-env.test.json
.wp-env.override.json
.wp-env.test.override.json
.phpunit.result.cache
.ui-quality.yml
composer.json
composer.lock
package.json
package-lock.json
playwright.config.ts
phpcs.xml.dist
phpstan.neon.dist
phpunit.xml.dist
phpunit-integration.xml.dist
sonar-project.properties
CHANGELOG.md
README.md
*.zip
```
