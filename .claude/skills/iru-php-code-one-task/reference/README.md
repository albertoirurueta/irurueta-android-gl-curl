# PHP development reference

General PHP code agreements for implementing a task in any PHP project this catalog supports — a plain Composer
library, a Laravel application, a WordPress plugin or theme, or a PrestaShop module — plus one file per flavor for
the conventions that only apply there. These files exist so `iru-php-code-one-task` can load **only** what the
task in front of it actually needs, instead of carrying every rule in context on every task.

## Read this much, and no more

Detect the flavor first (`iru-php-code-one-task` Step 1 — the same markers as `iru-explore` and
`iru-php-code-quality`), then read:

| Read | When |
|---|---|
| `code-style.md` | **Always.** Style standard detection (Pint / PHP-CS-Fixer / PHPCS / PER-CS 3.0), `strict_types`, PSR-4, native types, enums/`match`/readonly, `final` by default, errors, PHPDoc only for what types can't say. |
| `testing.md` | The task writes or updates tests — nearly every task. Pest vs. PHPUnit, attributes, `assertSame`, fakes over mocks, data providers, suite split, Pest arch rules. |
| `security.md` | The task touches request input, rendered output, SQL, files/uploads, outgoing HTTP, auth/sessions, or secrets — **always** for a controller, hook callback, form/AJAX/REST handler, or template. |
| exactly one flavor file below | Matching the flavor Step 1 detected — never more than one, none for a plain library. |
| `ui-contract.md` + `ui-implementation.md` | The task adds or changes a screen, component, list or other UI (Blade/Livewire views, blocks, patterns, template parts, `.tpl`/`.twig` templates, their CSS/JS). The contract (verbatim, shared by every UI coding skill) sets the rules — decompose first, composition, inputs/events, paginated collections with one item component, UI tests, the `.ui-screenshots/` matrix, accessibility per `.ui-quality.yml`; `ui-implementation.md` maps them onto Blade/Livewire, blocks, Smarty/Twig, and Playwright + `@axe-core/playwright`. |

| Flavor | Detected by (first match wins) | Flavor file |
|---|---|---|
| `laravel` | `laravel/framework` in `composer.json` `require` **and** an `artisan` file at the root | `laravel.md` |
| `wordpress-plugin` | a root PHP file whose header comment carries `Plugin Name:` | `wordpress.md` |
| `wordpress-theme` | `style.css` with a `Theme Name:` header, and/or `theme.json` | `wordpress.md` |
| `prestashop-module` | root `<module>.php` with a class that `extends Module`/`PaymentModule`/`CarrierModule`, `config.xml`, and/or `prestashop/*` in `composer.json` | `prestashop.md` |
| `library` | anything else with a `composer.json` | *(none — `code-style.md`/`testing.md`/`security.md` are the whole list)* |

A task that changes logic inside one existing method of a plain library, with no I/O and no new public API, needs
`code-style.md` plus `testing.md`, and nothing else. Don't read the rest speculatively.

## The three rules that override everything else

1. **Match the surrounding code.** Every rule here is the default for *new* code. If the file or directory you're
   editing consistently does something else — no `strict_types`, WPCS layout, `$fillable` instead of
   `#[Fillable]`, PHPUnit 9 annotations — follow it and note the divergence in the report rather than converting
   the file as a side effect of an unrelated task. The two exceptions are security rules (`security.md` and the
   flavor file's security section): new code is never written insecurely to match insecure neighbours — note the
   neighbour in the report instead.
2. **A task implements what the task says.** These references tell you *how* to write what was asked for. They
   never license adding an abstraction, a service layer, a new package, a migration, or a "while I'm here"
   refactor the plan didn't ask for.
3. **This skill doesn't validate the group.** It runs only the fast tests it wrote or touched — never coverage,
   code quality, license headers, a PHPDoc audit, the full suite, browser UI tests, or anything needing Docker/a
   database/a WordPress or PrestaShop install. The calling `iru-php-code-one-task-group` does all of that once for the whole
   task group. Write code that will pass those gates.

## Sources

Compiled from the following, cross-checked against each other; the user's own documentation takes precedence where
it is more specific (notably on Laravel 13 conventions and PHPUnit/Pest versions).

- irurueta docs: `programming-languages/php/psr-standards-and-coding-style.adoc`, `types-and-the-type-system.adoc`,
  `enumerations.adoc`, `visibility-readonly-and-asymmetric-visibility.adoc`, `errors-and-exceptions.adoc`,
  `composer-and-packages.adoc`, `static-analysis-and-refactoring.adoc`, `testing-with-phpunit-and-pest.adoc`,
  `cryptography-hashing-and-passwords.adoc`, `whats-new-and-migration.adoc`
- irurueta docs: `web/php-laravel/best-practices.adoc`, `controllers.adoc`, `validation-and-form-requests.adoc`,
  `authorization-gates-and-policies.adoc`, `service-container-providers-and-facades.adoc`, `eloquent-models.adoc`,
  `query-performance-and-n-plus-1.adoc`, `migrations-seeders-and-factories.adoc`, `queues-and-jobs.adoc`,
  `building-apis-and-api-resources.adoc`, `testing.adoc`, `web-security-fundamentals.adoc`,
  `security-csrf-encryption-and-hashing.adoc`, `file-uploads-and-images.adoc`, `databases-with-pdo.adoc`
- PHP-FIG PER Coding Style 3.0: <https://www.php-fig.org/per/coding-style/>, PSR-4: <https://www.php-fig.org/psr/psr-4/>
- PHPUnit manual: <https://docs.phpunit.de/>, Pest docs (incl. architecture testing): <https://pestphp.com/docs/arch-testing>
- Laravel 13 docs: <https://laravel.com/docs/13.x>
- WordPress Plugin Handbook — best practices, uninstall methods, internationalization:
  <https://developer.wordpress.org/plugins/plugin-basics/best-practices/>,
  <https://developer.wordpress.org/plugins/plugin-basics/uninstall-methods/>,
  <https://developer.wordpress.org/plugins/internationalization/how-to-internationalize-your-plugin/>
- WordPress Common APIs, Security — nonces, sanitizing, escaping: <https://developer.wordpress.org/apis/security/nonces/>,
  <https://developer.wordpress.org/apis/security/sanitizing/>, <https://developer.wordpress.org/apis/security/escaping/>
- `wpdb::prepare()`: <https://developer.wordpress.org/reference/classes/wpdb/prepare/>
- REST API Handbook, adding custom endpoints: <https://developer.wordpress.org/rest-api/extending-the-rest-api/adding-custom-endpoints/>
- Theme Handbook — theme structure, global settings and styles (`theme.json`), block patterns:
  <https://developer.wordpress.org/themes/core-concepts/theme-structure/>,
  <https://developer.wordpress.org/themes/global-settings-and-styles/>,
  <https://developer.wordpress.org/themes/features/block-patterns/>
- Block Editor Handbook (block.json, `register_block_type`): <https://developer.wordpress.org/block-editor/>
- WordPress Coding Standards: <https://developer.wordpress.org/coding-standards/wordpress-coding-standards/php/>,
  WPCS: <https://github.com/WordPress/WordPress-Coding-Standards>
- Brain Monkey: <https://giuseppe-mazzapica.gitbook.io/brain-monkey/>, WP_Mock: <https://wp-mock.gitbook.io/>,
  `@wordpress/env`: <https://developer.wordpress.org/block-editor/reference-guides/packages/packages-env/>
- PrestaShop DevDocs (9.x): module tutorial <https://devdocs.prestashop-project.org/9/modules/creation/tutorial/>,
  good practices <https://devdocs.prestashop-project.org/9/modules/creation/good-practices/>, auto-update/upgrade
  scripts <https://devdocs.prestashop-project.org/9/modules/creation/enabling-auto-update/>, Symfony admin
  controllers <https://devdocs.prestashop-project.org/9/modules/concepts/controllers/admin-controllers/>, new
  translation system <https://devdocs.prestashop-project.org/9/modules/creation/module-translation/new-system/>,
  coding standards <https://devdocs.prestashop-project.org/9/development/coding-standards/>, Db component
  <https://devdocs.prestashop-project.org/9/development/components/database/db/>
- PrestaShop 1.7 module changes (front-office Smarty auto-escaping, `nofilter`):
  <https://build.prestashop-project.org/news/2016/module-development-changes-in-17>
- OWASP Top 10: <https://owasp.org/Top10/>
