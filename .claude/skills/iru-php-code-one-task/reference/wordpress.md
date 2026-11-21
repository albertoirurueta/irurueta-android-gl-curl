# WordPress (plugins and themes)

Conventions for a WordPress plugin (a root PHP file with a `Plugin Name:` header) or theme (`style.css` with
`Theme Name:`, and/or `theme.json`). WordPress has its own coding standard and its own security API; code that
looks idiomatic in Laravel is often wrong here. Read the main plugin file (or `functions.php`/`style.css`) and
`phpcs.xml(.dist)` first: the prefix, text domain, namespace, minimum WordPress/PHP versions (`Requires at least`,
`Requires PHP` headers), and which WPCS rulesets apply are all declared there.

## Coding standard (WPCS)

When `phpcs.xml(.dist)` references `WordPress`/`WordPress-Extra`/`WordPress-Core`, WordPress Coding Standards
replace PER-CS layout and naming from `code-style.md`:

- **Tabs** for indentation, spaces inside parentheses (`foo( $bar )`, `array( 'a' => 1 )` or `[]` per the
  project), **Yoda conditions** (`if ( 'publish' === $status )`), `elseif`, braces always.
- **`snake_case`** functions, variables, and hook names; classes `Capitalized_Words_With_Underscores` and files
  `class-my-thing.php` — **unless** the project is namespaced/PSR-4 (and its `phpcs.xml` excludes
  `WordPress.Files.FileName`), in which case follow it.
- Strict comparisons and `in_array( …, true )` (`WordPress.PHP.StrictInArray`), no `extract()`, no `@`,
  `wp_json_encode()` over `json_encode()`, `wp_remote_get()` over cURL/`file_get_contents()` for HTTP,
  `wp_safe_redirect()` + `exit` over `header( 'Location' )`, `gmdate()` over `date()`, `WP_Filesystem` over raw
  writes where the task writes files.
- Full docblocks with `@since` on functions/classes/hooks (WordPress inline documentation standard) — what
  `iru-php-phpdoc` checks for this flavor.

## Structure, prefixing, loading

- **Every global name is prefixed** (≥ 4–5 chars, never `wp_`/`__`/`WordPress`): functions, classes, constants,
  globals, option names, transient keys, meta keys, custom hook names, script/style handles, AJAX actions, REST
  namespaces, nonce actions, CPT/taxonomy slugs. A **PHP namespace** removes the need to prefix functions/classes
  inside it — options, hooks, handles, and slugs still need the prefix.
- **Direct access guard** at the top of every PHP file with top-level code: `defined( 'ABSPATH' ) || exit;`.
- Main plugin file + `uninstall.php` at the root; everything else under `includes/`, `admin/`, `public/`,
  `src/` (PSR-4), `languages/`, `build/` (compiled block assets).
- Load admin-only code behind `is_admin()` — but `is_admin()` is **not** a permission check.
- Hook everything; run nothing at include time. Register on the right hook: CPTs/taxonomies on `init`, REST
  routes on `rest_api_init`, admin menus on `admin_menu`, settings on `admin_init`, front assets on
  `wp_enqueue_scripts`, admin assets on `admin_enqueue_scripts` (check `$hook_suffix`), blocks on `init`.
- **Lifecycle**: `register_activation_hook( __FILE__, … )` for setup (create tables with `dbDelta()`, store a
  schema-version option, `flush_rewrite_rules()` once after registering CPTs); `register_deactivation_hook` for
  transient state (cron events, rewrite flush) — **never delete data on deactivation**. Data removal belongs in
  `uninstall.php` guarded by `defined( 'WP_UNINSTALL_PLUGIN' ) || exit;` (or `register_uninstall_hook`), deleting
  only what the plugin created (options, transients, tables, user/post meta, cron events).

```php
<?php
/**
 * Plugin Name:       Acme Library Loans
 * Description:       Tracks book loans.
 * Version:           1.2.0
 * Requires at least: 6.5
 * Requires PHP:      8.1
 * Text Domain:       acme-loans
 * License:           GPL-2.0-or-later
 */

defined( 'ABSPATH' ) || exit;

require_once __DIR__ . '/vendor/autoload.php';

add_action( 'init', array( \Acme\Loans\Plugin::class, 'register_post_types' ) );
register_activation_hook( __FILE__, array( \Acme\Loans\Plugin::class, 'activate' ) );
```

## Security: validate/sanitize in, escape out, nonce + capability on every action

- **Every state-changing handler checks both** a nonce (CSRF) **and** a capability (authorization):
  `check_admin_referer( 'acme_save_loan' )` / `check_ajax_referer( 'acme_loan', 'nonce' )` /
  `wp_verify_nonce( $nonce, 'acme_save_loan' )`, **and** `current_user_can( 'edit_post', $post_id )` (a
  meta-capability with the object ID where one exists, not a role name). Nonces are never authorization.
  Forms emit `wp_nonce_field( 'acme_save_loan' )`; links use `wp_nonce_url()`.
- **Input**: read named keys only, `wp_unslash()` first (WordPress adds magic-quote slashes to
  `$_GET`/`$_POST`/`$_COOKIE`/`$_REQUEST`), then validate or sanitize for the type — `absint()`,
  `sanitize_text_field()`, `sanitize_textarea_field()`, `sanitize_email()` + `is_email()`, `sanitize_key()`,
  `sanitize_title()`, `esc_url_raw()`/`sanitize_url()` for stored URLs, `wp_kses_post()` for allowed HTML,
  `rest_sanitize_boolean()`, an allow-list via `in_array( $v, $allowed, true )`. Prefer validation (reject)
  over sanitization (coerce) when the value has a known shape.
- **Output — escape late, at the echo**: `esc_html()`, `esc_attr()`, `esc_url()`, `esc_textarea()`,
  `esc_js()` (inline only; prefer `wp_json_encode()` into a data attribute or `wp_add_inline_script()`),
  `wp_kses()`/`wp_kses_post()` for HTML. Translated output uses the combined forms: `esc_html__()`,
  `esc_html_e()`, `esc_attr__()`, `esc_html_x()`. Even "trusted" values (options, post meta, `get_the_title()`
  in attributes) are escaped.
- **SQL**: use WordPress APIs (`WP_Query`, `get_posts`, `update_post_meta`, options) before `$wpdb`. With
  `$wpdb`: `$wpdb->prepare()` with **unquoted** `%s`/`%d`/`%f` placeholders and `%i` for identifiers (WP 6.2+;
  older floors need an allow-list), `$wpdb->esc_like()` for the user part of a `LIKE` pattern built into the
  argument, `%%` for a literal percent; `$wpdb->insert/update/delete()` with a format array; tables via
  `$wpdb->prefix . 'acme_loans'`. Cache direct-query results (`wp_cache_get/set`) if they run on every request.
- Redirect only with `wp_safe_redirect()` (allowed hosts), then `exit`. Uploads through `wp_handle_upload()` +
  `wp_check_filetype_and_ext()`. Remote URLs from input: `wp_safe_remote_get()` + a host allow-list.

```php
function acme_loans_handle_save(): void {
	check_admin_referer( 'acme_save_loan' );

	$post_id = isset( $_POST['post_id'] ) ? absint( wp_unslash( $_POST['post_id'] ) ) : 0;
	if ( ! $post_id || ! current_user_can( 'edit_post', $post_id ) ) {
		wp_die( esc_html__( 'You are not allowed to do that.', 'acme-loans' ), 403 );
	}

	$note = isset( $_POST['note'] ) ? sanitize_textarea_field( wp_unslash( $_POST['note'] ) ) : '';
	update_post_meta( $post_id, '_acme_loan_note', $note );

	wp_safe_redirect( add_query_arg( 'updated', '1', get_edit_post_link( $post_id, 'url' ) ) );
	exit;
}
add_action( 'admin_post_acme_save_loan', 'acme_loans_handle_save' );
```

## REST API and AJAX

- `register_rest_route( 'acme-loans/v1', '/loans/(?P<id>\d+)', [...] )` inside `rest_api_init`, with
  **`permission_callback` always present** — a `current_user_can()` check, or `'__return_true'` only for a
  deliberately public read endpoint (omitting it raises `_doing_it_wrong` since 5.5). Declare `args` with
  `type`, `required`, `validate_callback`, `sanitize_callback` (or a JSON schema) instead of reading raw params;
  return data or `WP_Error( 'acme_not_found', __( '…', 'acme-loans' ), [ 'status' => 404 ] )`, wrapped in
  `rest_ensure_response()`. Cookie-authenticated JS sends the `wp_rest` nonce as `X-WP-Nonce`
  (`@wordpress/api-fetch` does this).
- Prefer REST over `admin-ajax.php` for new endpoints. If AJAX is required: `wp_ajax_acme_x` (+
  `wp_ajax_nopriv_acme_x` only if guests may call it), `check_ajax_referer()`, capability check,
  `wp_send_json_success()`/`wp_send_json_error( …, 403 )`.

## Hooks, options, settings, transients, i18n, assets

- **Custom hooks** are prefixed (`acme_loans_before_save`, `acme_loans_due_days`), documented with a docblock
  (`@since`, one `@param` per argument) immediately before `do_action()`/`apply_filters()`, and filters always
  return a value of the type they received.
- **Options**: one prefixed option holding an array beats dozens of rows; pass `autoload` `false` for anything not
  needed on every request. **Settings API** for admin forms: `register_setting( 'acme_loans', 'acme_loans_options',
  [ 'type' => 'array', 'sanitize_callback' => … ] )` + `add_settings_section/field`, page renders
  `settings_fields()` + `do_settings_sections()`, page access checked with `current_user_can( 'manage_options' )`.
- **Transients** always have an expiry and a prefixed key; `get_transient()` returns `false` when missing — compare
  with `false ===`, and never store `false` itself. They can vanish at any time (object cache eviction).
- **i18n**: every user-facing string through `__()`, `_e()`, `_x()`, `_n()`, `esc_html__()` … with the
  **literal** text domain matching the slug (`'acme-loans'` — never a variable or constant), `printf`/`sprintf`
  placeholders (numbered `%1$s` when there are several) with a `/* translators: */` comment, no string
  concatenation of translated fragments. JS: `wp_set_script_translations()`.
- **Assets**: `wp_register_script/style()` + enqueue with a version (plugin version or `filemtime()` of the built
  file) and dependencies; `[ 'in_footer' => true, 'strategy' => 'defer' ]` (6.3+); enqueue only on the screens
  that need them. Pass data with `wp_add_inline_script( $handle, 'const acmeLoans = ' . wp_json_encode( $data ),
  'before' )`. Never hard-code `<script>` tags or load jQuery/libraries WordPress already ships from a CDN.
- **Blocks**: one directory per block with `block.json` (`apiVersion` 3), registered with
  `register_block_type( __DIR__ . '/build/loan-card' )`, built by `@wordpress/scripts`. Dynamic blocks render in
  `render.php` — escape there like any template, and use `get_block_wrapper_attributes()`. A block is a UI
  component in the sense of `ui-contract.md`: attributes are its inputs, `setAttributes`/callbacks its events,
  `InnerBlocks`/patterns/template parts its composition — see `ui-implementation.md`, including bounded
  `WP_Query`/Query Loop pagination (never `posts_per_page => -1` on content that grows).

## Block themes

- `theme.json` (`"version": 3` on WP 6.6+; match the existing file's version) owns design tokens and global styles:
  `settings` (color palette, typography, spacing, layout `contentSize`/`wideSize`, `appearanceTools`) and `styles`
  (global, per-element, per-block). Add a token there rather than hard-coding a color/size in CSS or markup.
- `templates/*.html` and `parts/*.html` contain **block markup only** — no PHP. Anything translatable or dynamic
  goes into a **pattern** (`patterns/*.php`, with a header comment carrying at least `Title`, `Slug`
  (`acme-theme/hero`), `Categories`), referenced from a template with `<!-- wp:pattern {"slug":"acme-theme/hero"} /-->`;
  strings in patterns use `esc_html_e()`/`esc_attr_e()` with the theme's text domain, images use
  `esc_url( get_theme_file_uri( 'assets/images/x.webp' ) )`.
- Style variations in `styles/*.json`; block style variations via `register_block_style()` or
  `styles/blocks/*.json` per the floor.
- `functions.php` stays small: enqueue the stylesheet/editor styles, register pattern categories and block styles —
  no business logic (that's plugin territory) and no `add_theme_support()` calls a block theme gets from
  `theme.json`.
- Classic themes: template hierarchy files, `get_template_part()`, `wp_head()`/`wp_footer()`/`body_class()`/
  `wp_body_open()` present, escaping every template tag that returns rather than echoes.

## Tests

Two kinds, usually two configs or suites — find which ones the project has (`iru-php-test` Step 3 logic):

- **Unit (fast)**: tests that never load WordPress, using **Brain Monkey** (`brain/monkey`, with Mockery) or
  **WP_Mock** (`10up/wp_mock`) to stub WordPress functions and assert hooks. Default for logic in classes and
  hook callbacks. These run in the fast stage — no database, no Docker.
- **Integration**: `WP_UnitTestCase` subclasses run against a real WordPress test install and a dedicated MySQL
  database — via `@wordpress/env`'s dedicated test environment (the project's `npm run test:integration`, i.e.
  `npx wp-env run cli --config=.wp-env.test.json --env-cwd=wp-content/plugins/<slug> vendor/bin/phpunit -c
  phpunit-integration.xml.dist`; `tests-cli` only on older projects still using the deprecated
  `testsEnvironment`) or `bin/install-wp-tests.sh`. Use `self::factory()->post->create()`,
  `wp_set_current_user( self::factory()->user->create( [ 'role' => 'editor' ] ) )`, `rest_do_request( new
  WP_REST_Request( 'POST', '/acme-loans/v1/loans' ) )`, `$this->go_to()`. **Write them, don't run them here** —
  the group skill runs them under its integration lock.
- **Browser UI tests** (screens, blocks, flows — screenshot matrix and axe checks) use `wp-scripts test-playwright`
  with `@wordpress/e2e-test-utils-playwright` against the wp-env tests instance (`ui-implementation.md`). Write
  them, never run them here.
- The WordPress test library pins the PHPUnit major it supports (via `yoast/phpunit-polyfills`) — check
  `composer.lock` and use annotations on PHPUnit 9 (`testing.md`).

Brain Monkey unit-test skeleton (`setUp`/`tearDown` wiring is mandatory, or expectations leak between tests):

```php
<?php

declare(strict_types=1);

namespace Acme\Loans\Tests\Unit;

use Acme\Loans\Settings;
use Brain\Monkey;
use Brain\Monkey\Functions;
use Mockery\Adapter\Phpunit\MockeryPHPUnitIntegration;
use PHPUnit\Framework\TestCase;

final class SettingsTest extends TestCase
{
    use MockeryPHPUnitIntegration;

    protected function setUp(): void
    {
        parent::setUp();
        Monkey\setUp();
        Functions\stubTranslationFunctions(); // __(), esc_html__() … return their input
        Functions\stubEscapeFunctions();      // esc_html(), esc_attr() … behave like htmlspecialchars
    }

    protected function tearDown(): void
    {
        Monkey\tearDown();
        parent::tearDown();
    }

    public function test_registers_its_admin_hook(): void
    {
        ( new Settings() )->register();

        self::assertNotFalse( has_action( 'admin_init', [ Settings::class, 'register_settings' ] ) );
    }

    public function test_due_days_falls_back_to_default_and_is_filterable(): void
    {
        Functions\expect( 'get_option' )->once()->with( 'acme_loans_options', [] )->andReturn( [] );
        Monkey\Filters\expectApplied( 'acme_loans_due_days' )->once()->with( 21 )->andReturn( 14 );

        self::assertSame( 14, Settings::due_days() );
    }
}
```

`Functions\when( 'current_user_can' )->justReturn( false )` makes the unauthorized path testable without a user;
`Monkey\Actions\expectAdded( 'init' )` asserts a hook registration made inside the code under test.
