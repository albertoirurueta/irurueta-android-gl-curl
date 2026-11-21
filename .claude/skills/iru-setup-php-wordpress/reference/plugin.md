# Plugin templates (`flavor: plugin`)

Everything below was generated with these exact templates (placeholders from SKILL.md Step 5 filled with a sample
identity), then `composer install`, `composer test`, `composer test:coverage` (95.6% lines on `src/`),
`composer lint` (PHPStan level 6, 0 errors), `composer format:check` (WPCS + PHPCompatibilityWP, 0 issues), and
`npm run test:env:start && npm run test:integration` (wp-env 11.17, WordPress 7.1.3, 4/4 integration tests) all
passed. Copy every file verbatim except for the placeholders.

The sample is deliberately small but real — each class demonstrates one WordPress rule `iru-php-code-one-task`'s
`reference/wordpress.md` enforces, so the first real task has a correct pattern next to it:

| File | Demonstrates |
|---|---|
| `<slug>.php` | full plugin header, `ABSPATH` guard, `<PREFIX>_VERSION` constant, Composer autoloader with a missing-`vendor/` admin notice, composition root on `plugins_loaded` |
| `src/Plugin.php` | composition root, hook registration only, `load_plugin_textdomain()` on `init`, admin-only services behind `is_admin()` |
| `src/Options.php` | one prefixed option holding an array, `autoload` off, type-checked reads |
| `src/Frontend/ContentNotice.php` | a filter callback, a documented custom hook (`<prefix>_notice`), late escaping |
| `src/Admin/SettingsPage.php` | settings screen + `admin-post.php` handler: nonce **and** capability, `wp_unslash()` + sanitize, `wp_safe_redirect()` + `exit` |
| `src/Rest/StatusController.php` | `register_rest_route()` on `rest_api_init` with an explicit `permission_callback` |
| `uninstall.php` | data removal only on uninstall, guarded by `WP_UNINSTALL_PLUGIN` |
| `tests/e2e/specs/ContentNotice.spec.ts` | a browser UI test per the catalog's UI contract: states × directions × themes captured, axe check |

Rename `ContentNotice`/`SettingsPage`/`StatusController` freely once real features replace them — they exist so
every gate has something concrete to pass against on day one.

## Main plugin file and uninstall

### `<slug>.php`

```php
<?php
/**
 * Plugin Name:       <name>
 * Plugin URI:        <repository-url>
 * Description:       <description>
 * Version:           <dev-version>
 * Requires at least: <wp-floor>
 * Requires PHP:      <php-floor>
 * Author:            <developer-name>
 * Author URI:        <organization-url>
 * License:           <license-spdx>
 * License URI:       <license-uri>
 * Text Domain:       <slug>
 * Domain Path:       /languages
 * Update URI:        <repository-url>
 *
 * @package <namespace>
 */

defined( 'ABSPATH' ) || exit;

/**
 * Current plugin version, kept in sync with the `Version:` header above by the release tooling.
 *
 * @since <first-release>
 */
define( '<PREFIX>_VERSION', '<dev-version>' );

if ( ! is_readable( __DIR__ . '/vendor/autoload.php' ) ) {
	add_action(
		'admin_notices',
		static function (): void {
			printf(
				'<div class="notice notice-error"><p>%s</p></div>',
				esc_html__( '<name> is missing its Composer autoloader. Run "composer install" in the plugin directory or install a release build.', '<slug>' )
			);
		}
	);
	return;
}

require_once __DIR__ . '/vendor/autoload.php';

add_action(
	'plugins_loaded',
	static function (): void {
		( new \<namespace>\Plugin( __FILE__, <PREFIX>_VERSION ) )->register();
	}
);
```

- The header block is what WordPress parses (first 8 KB): keep `Version:` and the `<PREFIX>_VERSION` constant
  identical — `iru-php-bump-version` rewrites both (it matches `define( '<PREFIX>_VERSION', '…' )`).
- `Plugin URI`/`Author URI`/`Update URI` come from Step 3's repository URL and `<organization-url>`. **`Update URI`
  is only written when `distribution` is not `store`**: it tells WordPress (5.8+) never to replace this plugin with a
  same-slug plugin from the WordPress.org directory. A plugin hosted on WordPress.org must **not** carry it (drop the
  line for `distribution: store`).
- **License "No license"**: `License: Proprietary` and drop `License URI`; GPL-3.0-or-later: `License URI:
  https://www.gnu.org/licenses/gpl-3.0.html`.
- `vendor/autoload.php` only maps the plugin's own `src/` (no runtime dependencies): the release zip ships `vendor/`
  built with `composer install --no-dev --classmap-authoritative`.

### `uninstall.php`

```php
<?php
/**
 * Removes everything the plugin stored when it is deleted from the Plugins screen.
 *
 * Deactivation keeps data; only an explicit uninstall removes it.
 *
 * @package <namespace>
 * @since   <first-release>
 */

defined( 'WP_UNINSTALL_PLUGIN' ) || exit;

delete_option( '<prefix>_options' );
```

## Sources (`src/`)

### `src/Plugin.php`

```php
<?php
/**
 * Plugin composition root.
 *
 * @package <namespace>
 */

namespace <namespace>;

use <namespace>\Admin\SettingsPage;
use <namespace>\Frontend\ContentNotice;
use <namespace>\Rest\StatusController;

/**
 * Wires every service of the plugin into WordPress.
 *
 * @since <first-release>
 */
final class Plugin {

	/**
	 * Absolute path of the main plugin file.
	 *
	 * @var string
	 */
	private string $file;

	/**
	 * Plugin version.
	 *
	 * @var string
	 */
	private string $version;

	/**
	 * Creates the composition root.
	 *
	 * @since <first-release>
	 *
	 * @param string $file    Absolute path of the main plugin file.
	 * @param string $version Plugin version.
	 */
	public function __construct( string $file, string $version ) {
		$this->file    = $file;
		$this->version = $version;
	}

	/**
	 * Registers the hooks of every service.
	 *
	 * @since <first-release>
	 */
	public function register(): void {
		add_action( 'init', array( $this, 'load_textdomain' ) );

		( new ContentNotice() )->register();
		( new StatusController( $this->version ) )->register();

		if ( is_admin() ) {
			( new SettingsPage() )->register();
		}
	}

	/**
	 * Loads the translations bundled in the plugin's languages/ directory.
	 *
	 * @since <first-release>
	 */
	public function load_textdomain(): void {
		load_plugin_textdomain( '<slug>', false, dirname( plugin_basename( $this->file ) ) . '/languages' );
	}
}
```

### `src/Options.php`

```php
<?php
/**
 * Access to the plugin's single option.
 *
 * @package <namespace>
 */

namespace <namespace>;

/**
 * Reads and writes the `<prefix>_options` option, which holds every setting in one array.
 *
 * @since <first-release>
 */
final class Options {

	/**
	 * Name of the option row.
	 *
	 * @since <first-release>
	 */
	public const OPTION_NAME = '<prefix>_options';

	/**
	 * Returns the notice text appended to single posts, or an empty string when none is set.
	 *
	 * @since <first-release>
	 *
	 * @return string The stored notice text.
	 */
	public static function get_notice(): string {
		$options = get_option( self::OPTION_NAME, array() );

		if ( ! is_array( $options ) || ! isset( $options['notice'] ) || ! is_string( $options['notice'] ) ) {
			return '';
		}

		return $options['notice'];
	}

	/**
	 * Stores the notice text.
	 *
	 * @since <first-release>
	 *
	 * @param string $notice Already sanitized notice text.
	 */
	public static function save_notice( string $notice ): void {
		update_option( self::OPTION_NAME, array( 'notice' => $notice ), false );
	}
}
```

### `src/Frontend/ContentNotice.php`

```php
<?php
/**
 * Front-end notice appended to post content.
 *
 * @package <namespace>
 */

namespace <namespace>\Frontend;

use <namespace>\Options;

/**
 * Appends the configured notice to the content of single posts.
 *
 * @since <first-release>
 */
final class ContentNotice {

	/**
	 * Registers the content filter.
	 *
	 * @since <first-release>
	 */
	public function register(): void {
		add_filter( 'the_content', array( $this, 'append_notice' ) );
	}

	/**
	 * Appends the notice to the main query's single post content.
	 *
	 * @since <first-release>
	 *
	 * @param string $content Post content.
	 * @return string Content with the notice appended, or unchanged.
	 */
	public function append_notice( $content ) {
		if ( ! is_singular() || ! in_the_loop() || ! is_main_query() ) {
			return $content;
		}

		/**
		 * Filters the notice appended to single post content.
		 *
		 * @since <first-release>
		 *
		 * @param string $notice Notice text stored in the plugin settings.
		 */
		$notice = (string) apply_filters( '<prefix>_notice', Options::get_notice() );

		if ( '' === $notice ) {
			return $content;
		}

		return $content . '<p class="<slug>-notice">' . esc_html( $notice ) . '</p>';
	}
}
```

### `src/Admin/SettingsPage.php`

```php
<?php
/**
 * Settings screen and its admin-post handler.
 *
 * @package <namespace>
 */

namespace <namespace>\Admin;

use <namespace>\Options;

/**
 * Adds a settings page under Settings and saves it through admin-post.php.
 *
 * @since <first-release>
 */
final class SettingsPage {

	/**
	 * Admin-post action and nonce action of the settings form.
	 *
	 * @since <first-release>
	 */
	public const ACTION = '<prefix>_save_settings';

	/**
	 * Capability required to view and save the settings.
	 *
	 * @since <first-release>
	 */
	public const CAPABILITY = 'manage_options';

	/**
	 * Registers the menu entry and the admin-post handler.
	 *
	 * @since <first-release>
	 */
	public function register(): void {
		add_action( 'admin_menu', array( $this, 'add_page' ) );
		add_action( 'admin_post_' . self::ACTION, array( $this, 'handle_save' ) );
	}

	/**
	 * Adds the settings page to the Settings menu.
	 *
	 * @since <first-release>
	 */
	public function add_page(): void {
		add_options_page(
			__( '<name>', '<slug>' ),
			__( '<name>', '<slug>' ),
			self::CAPABILITY,
			'<slug>',
			array( $this, 'render' )
		);
	}

	/**
	 * Renders the settings form.
	 *
	 * @since <first-release>
	 */
	public function render(): void {
		if ( ! current_user_can( self::CAPABILITY ) ) {
			return;
		}
		?>
		<div class="wrap">
			<h1><?php echo esc_html( get_admin_page_title() ); ?></h1>
			<form method="post" action="<?php echo esc_url( admin_url( 'admin-post.php' ) ); ?>">
				<input type="hidden" name="action" value="<?php echo esc_attr( self::ACTION ); ?>" />
				<?php wp_nonce_field( self::ACTION ); ?>
				<label for="<slug>-notice"><?php esc_html_e( 'Notice shown below single posts', '<slug>' ); ?></label>
				<input type="text" class="regular-text" id="<slug>-notice" name="notice" value="<?php echo esc_attr( Options::get_notice() ); ?>" />
				<?php submit_button(); ?>
			</form>
		</div>
		<?php
	}

	/**
	 * Handles the form submission: nonce, capability, sanitize, save, redirect.
	 *
	 * @since <first-release>
	 */
	public function handle_save(): void {
		$this->save_from_request();

		wp_safe_redirect( add_query_arg( 'updated', '1', admin_url( 'options-general.php?page=<slug>' ) ) );
		exit;
	}

	/**
	 * Validates the request and stores the submitted notice.
	 *
	 * @since <first-release>
	 */
	public function save_from_request(): void {
		check_admin_referer( self::ACTION );

		if ( ! current_user_can( self::CAPABILITY ) ) {
			wp_die( esc_html__( 'You are not allowed to change these settings.', '<slug>' ), '', array( 'response' => 403 ) );
		}

		$notice = isset( $_POST['notice'] ) ? sanitize_text_field( wp_unslash( $_POST['notice'] ) ) : '';

		Options::save_notice( $notice );
	}
}
```

`handle_save()` keeps only redirect + `exit` so the testable part (`save_from_request()`) carries the nonce,
capability, unslash/sanitize, and persistence — the unit suite covers both the allowed and the refused path.

### `src/Rest/StatusController.php`

```php
<?php
/**
 * REST endpoint exposing the plugin status.
 *
 * @package <namespace>
 */

namespace <namespace>\Rest;

use <namespace>\Options;
use WP_REST_Response;

/**
 * Registers `GET /<slug>/v1/status`, readable by administrators only.
 *
 * @since <first-release>
 */
final class StatusController {

	/**
	 * REST namespace of the plugin.
	 *
	 * @since <first-release>
	 */
	public const REST_NAMESPACE = '<slug>/v1';

	/**
	 * Plugin version reported by the endpoint.
	 *
	 * @var string
	 */
	private string $version;

	/**
	 * Creates the controller.
	 *
	 * @since <first-release>
	 *
	 * @param string $version Plugin version reported by the endpoint.
	 */
	public function __construct( string $version ) {
		$this->version = $version;
	}

	/**
	 * Registers the routes on `rest_api_init`.
	 *
	 * @since <first-release>
	 */
	public function register(): void {
		add_action( 'rest_api_init', array( $this, 'register_routes' ) );
	}

	/**
	 * Registers the status route.
	 *
	 * @since <first-release>
	 */
	public function register_routes(): void {
		register_rest_route(
			self::REST_NAMESPACE,
			'/status',
			array(
				'methods'             => 'GET',
				'callback'            => array( $this, 'get_status' ),
				'permission_callback' => array( $this, 'can_read_status' ),
			)
		);
	}

	/**
	 * Only users who can manage the settings may read the status.
	 *
	 * @since <first-release>
	 *
	 * @return bool Whether the current user may read the status.
	 */
	public function can_read_status(): bool {
		return current_user_can( 'manage_options' );
	}

	/**
	 * Returns the plugin status.
	 *
	 * @since <first-release>
	 *
	 * @return WP_REST_Response The status response.
	 */
	public function get_status(): WP_REST_Response {
		return rest_ensure_response(
			array(
				'version' => $this->version,
				'notice'  => Options::get_notice(),
			)
		);
	}
}
```

`get_status()` declares `WP_REST_Response` (not `|WP_Error`): phpstan-wordpress narrows `rest_ensure_response()`
for a non-error argument, and level 6 reports an unused `WP_Error` in the union (`return.unusedType`).

## Unit tests (`tests/Unit/`, Brain Monkey — no WordPress loaded)

`tests/Unit/bootstrap.php` and `tests/Unit/TestCase.php` come from `tooling.md`.

### `tests/Unit/PluginTest.php`

```php
<?php
/**
 * Tests for Plugin.
 *
 * @package <namespace>
 */

namespace <namespace>\Tests\Unit;

use <namespace>\Admin\SettingsPage;
use <namespace>\Frontend\ContentNotice;
use <namespace>\Plugin;
use <namespace>\Rest\StatusController;
use Brain\Monkey\Functions;

/**
 * @covers \<namespace>\Plugin
 */
final class PluginTest extends TestCase {

	public function test_register_wires_front_end_and_rest_services(): void {
		Functions\when( 'is_admin' )->justReturn( false );
		$plugin = new Plugin( '/path/<slug>/<slug>.php', '1.2.3' );

		$plugin->register();

		self::assertNotFalse( has_action( 'init', array( $plugin, 'load_textdomain' ) ) );
		self::assertNotFalse( has_filter( 'the_content', ContentNotice::class . '->append_notice()' ) );
		self::assertNotFalse( has_action( 'rest_api_init', StatusController::class . '->register_routes()' ) );
		self::assertFalse( has_action( 'admin_menu', SettingsPage::class . '->add_page()' ) );
	}

	public function test_register_adds_the_settings_page_in_the_admin(): void {
		Functions\when( 'is_admin' )->justReturn( true );

		( new Plugin( '/path/<slug>/<slug>.php', '1.2.3' ) )->register();

		self::assertNotFalse( has_action( 'admin_menu', SettingsPage::class . '->add_page()' ) );
	}

	public function test_load_textdomain_points_at_the_languages_directory(): void {
		Functions\when( 'plugin_basename' )->justReturn( '<slug>/<slug>.php' );
		Functions\expect( 'load_plugin_textdomain' )
			->once()
			->with( '<slug>', false, '<slug>/languages' );

		( new Plugin( '/path/<slug>/<slug>.php', '1.2.3' ) )->load_textdomain();
	}
}
```

### `tests/Unit/OptionsTest.php`

```php
<?php
/**
 * Tests for Options.
 *
 * @package <namespace>
 */

namespace <namespace>\Tests\Unit;

use <namespace>\Options;
use Brain\Monkey\Functions;

/**
 * @covers \<namespace>\Options
 */
final class OptionsTest extends TestCase {

	public function test_get_notice_returns_the_stored_text(): void {
		Functions\when( 'get_option' )->justReturn( array( 'notice' => 'Return books on time.' ) );

		self::assertSame( 'Return books on time.', Options::get_notice() );
	}

	/**
	 * @dataProvider data_unusable_option_values
	 *
	 * @param mixed $stored Value returned by get_option().
	 */
	public function test_get_notice_falls_back_to_an_empty_string( $stored ): void {
		Functions\when( 'get_option' )->justReturn( $stored );

		self::assertSame( '', Options::get_notice() );
	}

	/**
	 * @return array<string, array{mixed}>
	 */
	public static function data_unusable_option_values(): array {
		return array(
			'missing option'  => array( false ),
			'no notice key'   => array( array() ),
			'non-string text' => array( array( 'notice' => 42 ) ),
		);
	}

	public function test_save_notice_stores_a_non_autoloaded_option(): void {
		Functions\expect( 'update_option' )
			->once()
			->with( '<prefix>_options', array( 'notice' => 'Hello' ), false );

		Options::save_notice( 'Hello' );
	}
}
```

### `tests/Unit/Frontend/ContentNoticeTest.php`

```php
<?php
/**
 * Tests for ContentNotice.
 *
 * @package <namespace>
 */

namespace <namespace>\Tests\Unit\Frontend;

use <namespace>\Frontend\ContentNotice;
use <namespace>\Tests\Unit\TestCase;
use Brain\Monkey\Filters;
use Brain\Monkey\Functions;

/**
 * @covers \<namespace>\Frontend\ContentNotice
 */
final class ContentNoticeTest extends TestCase {

	private function on_single_post_in_the_main_loop(): void {
		Functions\when( 'is_singular' )->justReturn( true );
		Functions\when( 'in_the_loop' )->justReturn( true );
		Functions\when( 'is_main_query' )->justReturn( true );
	}

	public function test_register_hooks_the_content_filter(): void {
		$notice = new ContentNotice();

		$notice->register();

		self::assertNotFalse( has_filter( 'the_content', array( $notice, 'append_notice' ) ) );
	}

	public function test_appends_the_escaped_notice_on_single_posts(): void {
		$this->on_single_post_in_the_main_loop();
		Functions\when( 'get_option' )->justReturn( array( 'notice' => '<b>Due in 14 days</b>' ) );

		$result = ( new ContentNotice() )->append_notice( '<p>Body</p>' );

		self::assertSame(
			'<p>Body</p><p class="<slug>-notice">&lt;b&gt;Due in 14 days&lt;/b&gt;</p>',
			$result
		);
	}

	public function test_the_notice_is_filterable(): void {
		$this->on_single_post_in_the_main_loop();
		Functions\when( 'get_option' )->justReturn( array( 'notice' => 'Stored' ) );
		Filters\expectApplied( '<prefix>_notice' )->once()->with( 'Stored' )->andReturn( 'Filtered' );

		$result = ( new ContentNotice() )->append_notice( 'Body' );

		self::assertSame( 'Body<p class="<slug>-notice">Filtered</p>', $result );
	}

	public function test_leaves_content_untouched_without_a_notice(): void {
		$this->on_single_post_in_the_main_loop();
		Functions\when( 'get_option' )->justReturn( array() );

		self::assertSame( 'Body', ( new ContentNotice() )->append_notice( 'Body' ) );
	}

	public function test_leaves_content_untouched_outside_single_posts(): void {
		Functions\when( 'is_singular' )->justReturn( false );
		Functions\expect( 'get_option' )->never();

		self::assertSame( 'Body', ( new ContentNotice() )->append_notice( 'Body' ) );
	}
}
```

### `tests/Unit/Admin/SettingsPageTest.php`

```php
<?php
/**
 * Tests for SettingsPage.
 *
 * @package <namespace>
 */

namespace <namespace>\Tests\Unit\Admin;

use <namespace>\Admin\SettingsPage;
use <namespace>\Tests\Unit\TestCase;
use Brain\Monkey\Functions;
use RuntimeException;

/**
 * @covers \<namespace>\Admin\SettingsPage
 */
final class SettingsPageTest extends TestCase {

	/**
	 * Backup of $_POST, restored after each test.
	 *
	 * @var array<string, mixed>
	 */
	private array $post_backup;

	protected function set_up(): void {
		parent::set_up();
		$this->post_backup = $_POST;
		Functions\when( 'wp_unslash' )->returnArg();
		Functions\when( 'sanitize_text_field' )->alias( 'trim' );
		Functions\when( 'wp_die' )->alias(
			static function ( $message ): void {
				throw new RuntimeException( (string) $message );
			}
		);
	}

	protected function tear_down(): void {
		$_POST = $this->post_backup;
		parent::tear_down();
	}

	public function test_register_hooks_the_menu_and_the_admin_post_handler(): void {
		$page = new SettingsPage();

		$page->register();

		self::assertNotFalse( has_action( 'admin_menu', array( $page, 'add_page' ) ) );
		self::assertNotFalse( has_action( 'admin_post_<prefix>_save_settings', array( $page, 'handle_save' ) ) );
	}

	public function test_add_page_requires_the_manage_options_capability(): void {
		$page = new SettingsPage();
		Functions\expect( 'add_options_page' )
			->once()
			->with( '<name>', '<name>', 'manage_options', '<slug>', array( $page, 'render' ) );

		$page->add_page();
	}

	public function test_render_outputs_a_nonce_protected_form(): void {
		Functions\when( 'current_user_can' )->justReturn( true );
		Functions\when( 'get_admin_page_title' )->justReturn( '<name>' );
		Functions\when( 'admin_url' )->alias(
			static function ( string $path ): string {
				return 'https://example.org/wp-admin/' . $path;
			}
		);
		Functions\when( 'get_option' )->justReturn( array( 'notice' => 'Be "nice"' ) );
		Functions\expect( 'wp_nonce_field' )->once()->with( '<prefix>_save_settings' );
		Functions\expect( 'submit_button' )->once();

		ob_start();
		( new SettingsPage() )->render();
		$html = (string) ob_get_clean();

		self::assertStringContainsString( 'action="https://example.org/wp-admin/admin-post.php"', $html );
		self::assertStringContainsString( 'value="<prefix>_save_settings"', $html );
		self::assertStringContainsString( 'value="Be &quot;nice&quot;"', $html );
	}

	public function test_render_outputs_nothing_without_the_capability(): void {
		Functions\when( 'current_user_can' )->justReturn( false );

		ob_start();
		( new SettingsPage() )->render();

		self::assertSame( '', ob_get_clean() );
	}

	public function test_save_checks_the_nonce_and_stores_the_sanitized_notice(): void {
		$_POST['notice'] = '  Bring your card  ';
		Functions\expect( 'check_admin_referer' )->once()->with( '<prefix>_save_settings' );
		Functions\when( 'current_user_can' )->justReturn( true );
		Functions\expect( 'update_option' )
			->once()
			->with( '<prefix>_options', array( 'notice' => 'Bring your card' ), false );

		( new SettingsPage() )->save_from_request();
	}

	public function test_save_refuses_users_without_the_capability(): void {
		$_POST['notice'] = 'Hijacked';
		Functions\when( 'check_admin_referer' )->justReturn( 1 );
		Functions\when( 'current_user_can' )->justReturn( false );
		Functions\expect( 'update_option' )->never();

		$this->expectException( RuntimeException::class );
		$this->expectExceptionMessage( 'You are not allowed to change these settings.' );

		( new SettingsPage() )->save_from_request();
	}
}
```

### `tests/Unit/Rest/StatusControllerTest.php`

```php
<?php
/**
 * Tests for StatusController.
 *
 * @package <namespace>
 */

namespace <namespace>\Tests\Unit\Rest;

use <namespace>\Rest\StatusController;
use <namespace>\Tests\Unit\TestCase;
use Brain\Monkey\Functions;
use Mockery;

/**
 * @covers \<namespace>\Rest\StatusController
 */
final class StatusControllerTest extends TestCase {

	public function test_register_hooks_rest_api_init(): void {
		$controller = new StatusController( '1.2.3' );

		$controller->register();

		self::assertNotFalse( has_action( 'rest_api_init', array( $controller, 'register_routes' ) ) );
	}

	public function test_the_route_always_declares_a_permission_callback(): void {
		$controller = new StatusController( '1.2.3' );
		Functions\expect( 'register_rest_route' )
			->once()
			->with(
				'<slug>/v1',
				'/status',
				Mockery::on(
					static function ( array $args ) use ( $controller ): bool {
						return 'GET' === $args['methods']
							&& array( $controller, 'can_read_status' ) === $args['permission_callback'];
					}
				)
			);

		$controller->register_routes();
	}

	public function test_only_administrators_can_read_the_status(): void {
		Functions\expect( 'current_user_can' )->once()->with( 'manage_options' )->andReturn( false );

		self::assertFalse( ( new StatusController( '1.2.3' ) )->can_read_status() );
	}

	public function test_get_status_reports_version_and_notice(): void {
		$response = Mockery::mock( 'WP_REST_Response' );
		Functions\when( 'get_option' )->justReturn( array( 'notice' => 'Hi' ) );
		Functions\expect( 'rest_ensure_response' )
			->once()
			->with(
				array(
					'version' => '1.2.3',
					'notice'  => 'Hi',
				)
			)
			->andReturn( $response );

		self::assertSame( $response, ( new StatusController( '1.2.3' ) )->get_status() );
	}
}
```

Brain Monkey notes confirmed while verifying: `has_action()`/`has_filter()` work against Brain Monkey's hook
registry without stubbing; a class WordPress would provide (`WP_REST_Response`) is doubled with
`Mockery::mock( 'WP_REST_Response' )`; `wp_die()` is aliased to throw so the refused path is assertable; class
constants such as `WP_REST_Server::READABLE` are **not** available in the unit suite — the controller uses the
literal `'GET'` for that reason.

## Integration tests (`tests/Integration/`, `WP_UnitTestCase` inside wp-env)

### `tests/Integration/bootstrap.php`

```php
<?php
/**
 * Integration-suite bootstrap: loads the WordPress PHPUnit test library and the plugin.
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
		require dirname( __DIR__, 2 ) . '/<slug>.php';
	}
);

require $<prefix>_tests_dir . '/includes/bootstrap.php';
```

### `tests/Integration/PluginTest.php`

```php
<?php
/**
 * Integration tests for the plugin running inside WordPress.
 *
 * @package <namespace>
 */

namespace <namespace>\Tests\Integration;

use <namespace>\Options;
use WP_REST_Request;
use WP_UnitTestCase;

/**
 * Exercises the plugin against a real WordPress install and database.
 */
final class PluginTest extends WP_UnitTestCase {

	public function test_plugin_is_loaded_and_hooked(): void {
		self::assertMatchesRegularExpression( '/^\d+\.\d+\.\d+/', <PREFIX>_VERSION );
		self::assertNotFalse( has_action( 'rest_api_init' ) );
	}

	public function test_options_round_trip_through_the_database(): void {
		Options::save_notice( 'Return within 14 days' );

		self::assertSame( 'Return within 14 days', Options::get_notice() );
	}

	public function test_status_endpoint_rejects_anonymous_users(): void {
		wp_set_current_user( 0 );

		$response = rest_do_request( new WP_REST_Request( 'GET', '/<slug>/v1/status' ) );

		self::assertSame( 401, $response->get_status() );
	}

	public function test_status_endpoint_answers_administrators(): void {
		wp_set_current_user( self::factory()->user->create( array( 'role' => 'administrator' ) ) );

		$response = rest_do_request( new WP_REST_Request( 'GET', '/<slug>/v1/status' ) );

		self::assertSame( 200, $response->get_status() );
		self::assertSame( <PREFIX>_VERSION, $response->get_data()['version'] );
	}
}
```

The bootstrap requires the Composer autoloader first (that loads `yoast/phpunit-polyfills`, which the WordPress
test bootstrap insists on), then loads the plugin on `muplugins_loaded`. It is excluded from PHPStan (it reads
`WP_TESTS_DIR` at runtime); the tests themselves are analysed against `php-stubs/wordpress-tests-stubs`.

## Browser UI test (`tests/e2e/specs/`, Playwright against the wp-env tests instance)

`.ui-quality.yml`, `playwright.config.ts`, the helper `tests/e2e/support/ui-screenshots.ts` and the RTL mu-plugin
come from `tooling.md` "Browser UI tests". The sample component is `ContentNotice` (Id = its class name): the notice
`src/Frontend/ContentNotice.php` appends below single posts. The plugin has no collection to virtualize; when one is
added, render it server-side paginated (`posts_per_page` from a setting or 10–20, `paginate_links()`, or a REST
"load more"), never `posts_per_page => -1`.

### `tests/e2e/specs/ContentNotice.spec.ts`

```ts
/**
 * Component UI test of ContentNotice (src/Frontend/ContentNotice.php): the settings notice appended below a
 * single post. Behaviour is asserted once; every state is captured across `.ui-quality.yml` directions × themes.
 */
import { expect, test } from '@wordpress/e2e-test-utils-playwright';
import {
	applyDirectionAndTheme,
	captureUiState,
	checkAccessibility,
	expectDirection,
	uiQuality,
} from '../support/ui-screenshots';

/** Visual states and their fixture notice text (long-content mixes Arabic and Latin script). */
const STATES: Record< string, string > = {
	default: 'Return books within 14 days.',
	'long-content':
		'يرجى إعادة الكتب خلال أربعة عشر يومًا — please return every borrowed book within fourteen days; a late fee applies to each additional day.',
};

test.describe( 'ContentNotice', () => {
	let postUrl: string;

	test.beforeAll( async ( { requestUtils } ) => {
		const post = await requestUtils.createPost( {
			title: 'UI test post',
			content: '<!-- wp:paragraph --><p>Body</p><!-- /wp:paragraph -->',
			status: 'publish',
			date_gmt: '2026-01-15T10:00:00',
		} );
		postUrl = post.link;
	} );

	test.afterAll( async ( { requestUtils } ) => {
		await requestUtils.deleteAllPosts();
	} );

	for ( const [ state, notice ] of Object.entries( STATES ) ) {
		test( `renders the ${ state } state`, async ( { admin, page }, testInfo ) => {
			await admin.visitAdminPage( 'options-general.php', 'page=<slug>' );
			await page.getByLabel( 'Notice shown below single posts' ).fill( notice );
			await page.getByRole( 'button', { name: 'Save Changes' } ).click();
			await expect( page.getByLabel( 'Notice shown below single posts' ) ).toHaveValue( notice );

			const { directions, themes } = uiQuality();
			for ( const direction of directions ) {
				for ( const theme of themes ) {
					await applyDirectionAndTheme( page, direction, theme );
					await page.goto( postUrl );
					const component = page.locator( '.<slug>-notice' );
					await expect( component ).toHaveText( notice );
					await expectDirection( page, direction );
					await captureUiState( page, {
						kind: 'component',
						id: 'ContentNotice',
						state,
						direction,
						theme,
						locator: component,
					} );
					if ( direction === directions[ 0 ] && theme === themes[ 0 ] ) {
						await checkAccessibility( page, testInfo, { include: '.<slug>-notice' } );
					}
				}
			}
		} );
	}
} );
```

- The notice is set through the real settings screen (`admin` fixture, logged in by wp-scripts' global setup) and the
  post is created over the REST API (`requestUtils.createPost`, fixed `date_gmt` for deterministic output);
  `requestUtils.deleteAllPosts()` only ever runs against the tests instance.
- It writes `.ui-screenshots/component/ContentNotice/{default,long-content}--{ltr,rtl}--{light,dark}.png` (8 files
  with the default `.ui-quality.yml`); the element screenshot keeps the admin bar and the active theme's chrome out
  of the component image, and the axe check is scoped to the component for the same reason.
- Dark captures follow the **active theme**: wp-env's default theme has no `prefers-color-scheme: dark` styles, so
  the plugin's dark images equal the light ones until the plugin or the site's theme adds dark styles — give the
  plugin's own CSS a `@media (prefers-color-scheme: dark)` block when it styles front-end output.
- Run: `npm run test:env:start` (or let the config start it), `npx playwright install chromium` once, then
  `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm run test:e2e`.

## Distribution files

### `readme.txt`

```text
=== <name> ===
Contributors: <wporg-username>
Tags: loans, library
Requires at least: <wp-floor>
Tested up to: <wp-tested>
Requires PHP: <php-floor>
Stable tag: <first-release>
License: <readme-license>
License URI: <license-uri>

<description>

== Description ==

<description>

== Installation ==

1. Upload the plugin folder to `/wp-content/plugins/`, or install the release zip from Plugins → Add New → Upload Plugin.
2. Activate the plugin through the Plugins screen.
3. Configure it under Settings → <name>.

== Frequently Asked Questions ==

= Where are the settings? =

Under Settings → <name>.

== Changelog ==

= <first-release> =
* Initial release.

== Upgrade Notice ==

= <first-release> =
Initial release.
```

- WordPress.org `readme.txt` format (validate with https://wordpress.org/plugins/developers/readme-validator/).
  `Stable tag:` starts at the first **release** version (`<first-release>`, normally `<dev-version>` without
  `-dev`) and is only ever moved to release versions — never `-dev` (catalog convention; `iru-php-bump-version`
  enforces it). `Contributors:` takes WordPress.org usernames: ask for them when `distribution: store`, otherwise
  keep `<developer-name>`'s WordPress.org username if they gave one, or drop the line.
- `Tested up to:` is the latest WordPress `major.minor` (Step 4). `License:` mirrors the header (`GPLv2 or later` /
  `GPLv3 or later`).

### `languages/.gitkeep (empty)`

```text

```

### `.distignore`

```text
# Excluded from the release zip (wp dist-archive, 10up/action-wordpress-plugin-deploy).
# vendor/ is shipped: build it first with `composer install --no-dev --classmap-authoritative`.
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

`.distignore` is read by `wp dist-archive` and by `rsync --exclude-from` in deploy actions (verified with
`rsync -a --exclude-from=.distignore`: the copy kept the main file, `uninstall.php`, `src/`, `vendor/`, `languages/`,
`readme.txt`, `build/` block assets, and dropped `build/logs/`, tests, and every config file). The browser UI tests add nothing to
the zip: `tests/e2e/` (specs, helper, mu-plugin) is under `/tests`, and their config, output (`artifacts/`,
`test-results/`, `.ui-screenshots/`) and `.ui-quality.yml` are listed explicitly. Only `/build/logs` is
excluded, not `/build`, because `@wordpress/scripts` writes the shipped block assets to `build/`.

## Blocks (`blocks: yes` only)

When the plugin builds blocks (or other JS/CSS assets), add `@wordpress/scripts`. Its default source directory is
`src/`, which this layout already uses for PHP, so blocks live under `blocks/<block-name>/` and compile to
`build/<block-name>/` with a `build/blocks-manifest.php`. Verified end to end: `npm run build`, `npm run lint:js`,
`npm run lint:css`, the PHP gates, and an integration test asserting the block is registered.

1. Add to `package.json` — scripts **before** the wp-env ones (`@wordpress/scripts` is already a `devDependency` for
   the browser UI tests), and the editor
   packages the generated block imports to `dependencies` (they are WordPress-provided externals at runtime, but
   `wp-scripts lint-js` fails `import/no-unresolved`/`import/no-extraneous-dependencies` without them):

   ```json
   "build": "wp-scripts build --webpack-src-dir=blocks --output-path=build --blocks-manifest",
   "start": "wp-scripts start --webpack-src-dir=blocks --output-path=build --blocks-manifest",
   "lint:js": "wp-scripts lint-js blocks",
   "lint:css": "wp-scripts lint-style \"blocks/**/*.scss\"",
   ```

   `npm install --save @wordpress/i18n @wordpress/block-editor @wordpress/blocks`.
2. Generate the first block (the only interactive-free form; `--textdomain` keeps it on the plugin's text domain):

   ```bash
   npx @wordpress/create-block@<create-block-version> <block-name> --no-plugin --target-dir=blocks/<block-name> \
     --namespace=<slug> --title="<Block Title>" --textdomain=<slug> --variant=dynamic
   npx wp-scripts lint-js blocks --fix
   ```

   `--fix` is required: the generated JS fails the bundled Prettier rules until reformatted. Then replace the
   generated `render.php` (its `echo get_block_wrapper_attributes()` fails `WordPress.Security.EscapeOutput`) with:

### `blocks/<block-name>/render.php`

```php
<?php
/**
 * Front-end markup of the Loan Card block.
 *
 * WordPress exposes $attributes (array), $content (string) and $block (WP_Block) to this file.
 *
 * @package <namespace>
 * @since   <first-release>
 */

defined( 'ABSPATH' ) || exit;
?>
<p <?php echo wp_kses_data( get_block_wrapper_attributes() ); ?>>
	<?php esc_html_e( 'Loan Card – hello from a dynamic block!', '<slug>' ); ?>
</p>
```

3. Register the built collection — add to `src/Plugin.php`'s `register()` the line
   `add_action( 'init', array( $this, 'register_blocks' ) );` and the method below.
   `wp_register_block_types_from_metadata_collection()` needs WordPress **6.8+**; with a lower `<wp-floor>`, loop
   instead: `foreach ( glob( $build_dir . '/*/block.json' ) ?: array() as $block_json ) { register_block_type(
   dirname( $block_json ) ); }`.

   ```php
	/**
	 * Registers every block built by @wordpress/scripts into build/ (one directory per block.json).
	 *
	 * @since <first-release>
	 */
	public function register_blocks(): void {
		$build_dir = dirname( $this->file ) . '/build';

		if ( ! is_readable( $build_dir . '/blocks-manifest.php' ) ) {
			return;
		}

		wp_register_block_types_from_metadata_collection( $build_dir, $build_dir . '/blocks-manifest.php' );
	}
   ```

4. Tests — append to `tests/Unit/PluginTest.php`:

   ```php
	public function test_register_blocks_registers_the_built_block_collection(): void {
		$plugin_dir = sys_get_temp_dir() . '/<slug>-' . uniqid();
		mkdir( $plugin_dir . '/build', 0777, true );
		touch( $plugin_dir . '/build/blocks-manifest.php' );
		Functions\expect( 'wp_register_block_types_from_metadata_collection' )
			->once()
			->with( $plugin_dir . '/build', $plugin_dir . '/build/blocks-manifest.php' );

		( new Plugin( $plugin_dir . '/<slug>.php', '1.2.3' ) )->register_blocks();

		unlink( $plugin_dir . '/build/blocks-manifest.php' );
		rmdir( $plugin_dir . '/build' );
		rmdir( $plugin_dir );
	}

	public function test_register_blocks_does_nothing_before_the_first_build(): void {
		Functions\expect( 'wp_register_block_types_from_metadata_collection' )->never();

		( new Plugin( '/nonexistent/<slug>/<slug>.php', '1.2.3' ) )->register_blocks();
	}
   ```

   and to `tests/Integration/PluginTest.php` (the integration suite needs `npm run build` first — `build/` is
   mounted into wp-env with the plugin):

   ```php
	public function test_built_blocks_are_registered(): void {
		self::assertTrue( \WP_Block_Type_Registry::get_instance()->is_registered( '<slug>/<block-name>' ) );
	}
   ```

5. Browser UI test — add `tests/e2e/specs/<BlockName>.spec.ts`, where `<BlockName>` (the component Id) is the
   `block.json` `name`'s block part in PascalCase (`<slug>/loan-card` → `LoanCard`); the block is rendered on a
   front-end post created over the REST API, so run `npm run build` before `npm run test:e2e`:

   ```ts
   import { expect, test } from '@wordpress/e2e-test-utils-playwright';
   import {
   	applyDirectionAndTheme,
   	captureUiState,
   	checkAccessibility,
   	expectDirection,
   	uiQuality,
   } from '../support/ui-screenshots';

   test.describe( '<BlockName>', () => {
   	let postUrl: string;

   	test.beforeAll( async ( { requestUtils } ) => {
   		const post = await requestUtils.createPost( {
   			title: 'Block UI test',
   			content: '<!-- wp:<slug>/<block-name> /-->',
   			status: 'publish',
   			date_gmt: '2026-01-15T10:00:00',
   		} );
   		postUrl = post.link;
   	} );

   	test.afterAll( async ( { requestUtils } ) => {
   		await requestUtils.deleteAllPosts();
   	} );

   	test( 'renders the default state', async ( { page }, testInfo ) => {
   		const { directions, themes } = uiQuality();
   		for ( const direction of directions ) {
   			for ( const theme of themes ) {
   				await applyDirectionAndTheme( page, direction, theme );
   				await page.goto( postUrl );
   				const block = page.locator( '.wp-block-<slug>-<block-name>' );
   				await expect( block ).toBeVisible();
   				await expectDirection( page, direction );
   				await captureUiState( page, { kind: 'component', id: '<BlockName>', state: 'default', direction, theme, locator: block } );
   				if ( direction === directions[ 0 ] && theme === themes[ 0 ] ) {
   					await checkAccessibility( page, testInfo, { include: '.wp-block-<slug>-<block-name>' } );
   				}
   			}
   		}
   	} );
   } );
   ```

   Add a state (`long-content`, `empty`, …) per attribute combination the block renders differently.
6. `blocks/` stays in the release zip (WordPress.org requires the human-readable source of built assets) and in
   PHPCS; it's excluded from PHPStan (`tooling.md`). CI must run `npm ci && npm run build` before packaging.
