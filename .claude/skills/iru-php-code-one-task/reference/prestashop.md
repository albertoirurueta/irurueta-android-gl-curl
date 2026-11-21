# PrestaShop modules (8.x / 9.x)

Conventions for a PrestaShop module: a root `<modulename>.php` whose class `extends Module` (or `PaymentModule`/
`CarrierModule`), usually with `config.xml`, `composer.json` (`prestashop/*` dev tools), `upgrade/`, `views/`,
`controllers/`, `src/`. Before writing, read the main module class: its `$this->name`, `$this->version`,
`ps_versions_compliancy` (the PrestaShop range you must stay compatible with — `['min' => '8.0.0', 'max' =>
'9.99.99']` means no 9-only API without a fallback), the hooks it registers, and whether it uses the new
translation system (`isUsingNewTranslationSystem()` returning `true`).

## Coding standard

- PSR-2/PSR-12 + Symfony conventions enforced by PHP-CS-Fixer with PrestaShop's config
  (`.php-cs-fixer.dist.php` built on `PrestaShop\CodingStandards\CsFixer\Config`); PHPStan via
  `prestashop/php-dev-tools` (`tests/phpstan/phpstan.neon`).
- **New code is strictly typed**: `declare(strict_types=1);` in every new non-interface class, all parameter and
  return types declared (PrestaShop coding standard). Legacy `ObjectModel`/controller overrides of core methods
  must keep the parent's signature — don't add types the parent doesn't have.
- **Docblocks on classes, properties, and methods regardless of visibility**, PHPStan-style types
  (`array<int, string>`, shapes).
- **License header** (AFL 3.0 for modules by PrestaShop convention, or the module's own) at the top of every file
  — `iru-check-license` handles it at group level; keep the existing one when editing.
- Every PHP file with top-level code starts with the guard `if (!defined('_PS_VERSION_')) { exit; }`; classes under
  `src/` (PSR-4 autoloaded through the module's `composer.json` + `vendor/autoload.php` required from the main
  file) don't need it. Each directory carries the `index.php` redirect stub if the module already follows that
  convention.

## Main class, install/uninstall, hooks, configuration

- Constructor: set `name` (lowercase, matches the folder), `tab`, `version`, `author`, `need_instance = 0`,
  `ps_versions_compliancy`, `bootstrap = true`, then `parent::__construct()`, **then** translated
  `displayName`/`description`/`confirmUninstall` (translations need the parent constructed).
- `install()`: `parent::install() && $this->registerHook([...]) && Configuration::updateValue(...)` plus
  `CREATE TABLE IF NOT EXISTS` for the module's own tables — return `false` on any failure so installation aborts.
  `uninstall()` mirrors it: delete every `Configuration` key, `DROP TABLE IF EXISTS`, admin tabs, then
  `parent::uninstall()`. Multistore: `Shop::isFeatureActive()` → `Shop::setContext(Shop::CONTEXT_ALL)` before
  install-time configuration.
- **Never alter core tables or their schema**; create your own, prefixed with `_DB_PREFIX_` and the module name.
  Prefix every `Configuration` key (`ACMELOANS_DUE_DAYS`), Smarty variable, CSS class, and table.
- **Hooks**: `hookDisplayHeader(array $params)`-style methods named `hook` + hook name; register new hooks on
  install **and** in an upgrade script (existing installs never re-run `install()`). Restrict back-office hooks
  (`hookDisplayBackOfficeHeader`/`hookActionAdminControllerSetMedia`) to the module's own pages so assets don't
  load everywhere. Prefer `action*` hooks for side effects and `display*` hooks returning rendered templates.
- **Configuration**: `Configuration::get('KEY', $idLang, $idShopGroup, $idShop)`/`updateValue()`; values are
  strings — cast on read (`(int) Configuration::get('ACMELOANS_DUE_DAYS')`).
- **Upgrade scripts**: every version that changes schema, configuration, or hooks ships
  `upgrade/upgrade-<x.y.z>.php` defining `function upgrade_module_<x_y_z>($module): bool` (dots → underscores,
  matching the new `$this->version` exactly), guarded by `_PS_VERSION_`, idempotent (`IF NOT EXISTS`, check before
  adding), returning `true` on success. Bump `$this->version` (and `config.xml`'s `<version>`) in the same task.
- Paths via PrestaShop constants (`_PS_MODULE_DIR_`, `$this->getLocalPath()`), never `dirname(__FILE__)` +
  `../..`; caches in `var/cache/<env>/modules/<name>/`, never written into `modules/`. No overrides of core
  classes unless the task explicitly asks (they conflict between modules).

```php
<?php
// upgrade/upgrade-1.3.0.php
if (!defined('_PS_VERSION_')) {
    exit;
}

/**
 * Adds the loan note column and the order-confirmation hook introduced in 1.3.0.
 */
function upgrade_module_1_3_0(Module $module): bool
{
    $table = _DB_PREFIX_ . 'acmeloans_loan';
    $hasColumn = (bool) Db::getInstance()->getValue(
        'SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()'
        . " AND TABLE_NAME = '" . pSQL($table) . "' AND COLUMN_NAME = 'note'"
    );

    return ($hasColumn || Db::getInstance()->execute('ALTER TABLE `' . bqSQL($table) . '` ADD `note` TEXT NULL'))
        && $module->registerHook('displayOrderConfirmation');
}
```

## Input, database, output

- **Input**: `Tools::getValue('key', $default)` returns raw, unvalidated data — **cast or validate immediately**:
  `(int) Tools::getValue('id_loan')`, `Validate::isEmail()`, `Validate::isUnsignedId()`, `Validate::isCleanHtml()`,
  `Validate::isGenericName()`, an allow-list with `in_array(…, true)`. `Tools::isSubmit('submitAcmeLoans')` to
  detect a form post.
- **SQL**: `Db::getInstance()` (`getRow`, `getValue`, `executeS` for reads; `insert`/`update`/`delete` with arrays
  for writes — table name *without* prefix, which is added for you). Every interpolated value: `(int)`/
  `(float)` for numbers, `pSQL($s)` for strings (inside quotes), `bqSQL()` for identifiers, `pSQL($s, true)` only
  for HTML you meant to store. `DbQuery` (`->select()->from('acmeloans_loan', 'l')->where('l.id_customer = ' .
  (int) $id)`) builds structure but **does not escape** — the same casting rules apply inside `where()`/joins.
  Doctrine (Symfony side, PS 8+/9) with parameters where the module already uses it.
- **Output**: **front-office** Smarty templates auto-escape HTML (since 1.7) — don't add a second
  `|escape:'html'`, and use `{$html nofilter}` only for HTML your module built and validated
  (`Validate::isCleanHtml()`), never for request data. **Back-office** Smarty templates (`HelperForm`, legacy admin
  controllers, `getContent()` output) do **not** auto-escape — use `{$var|escape:'html':'UTF-8'}`. Inline JS:
  `|escape:'javascript':'UTF-8'` or `|json_encode`. Twig (Symfony back office) autoescapes — `|raw` only for
  trusted HTML.
- **Tokens and access**: legacy admin controllers check `$this->access('edit')`/the controller token; front
  controllers that change state require a token (`Tools::getToken(false)` / a module-specific token compared with
  `hash_equals`) and the customer context (`$this->context->customer->isLogged()`), and verify the object belongs to
  that customer. Cron/webhook entry points require a secret token.
- Check uploaded file types (`ImageManager::isRealImage()`, `finfo`) and size; store outside `modules/`.

## Controllers, services, forms, templates

- **Front**: `controllers/front/<name>.php` with `class <Module><Name>ModuleFrontController extends
  ModuleFrontController`; `initContent()` assigns Smarty variables and calls
  `$this->setTemplate('module:<module>/views/templates/front/<tpl>.tpl')`. Links via
  `$this->context->link->getModuleLink($this->name, '<name>', [...])`.
- **Back office (Symfony)**: controllers in `src/Controller/Admin/`, PSR-4 namespace in the module's
  `composer.json`; extend `PrestaShopAdminController` on PS 9 (`FrameworkBundleAdminController`, deprecated in
  9.0, on PS 8 — if the module supports both, use the 8 class and note it); register controllers as services in
  `config/services.yml` (service id = FQCN, autowired) and routes in `config/routes.yml`
  (`_controller: 'Acme\Loans\Controller\Admin\LoanController::indexAction'`). Guard every action with
  `#[AdminSecurity("is_granted('read', request.get('_legacy_controller'))")]` (attribute on 9, `@AdminSecurity`
  annotation on 8). Twig templates under `views/templates/admin/` rendered as `@Modules/<module>/views/...`.
  Constructor-inject services; no `$this->get('...')` container lookups in new code.
- **Configuration pages**: Symfony forms (`FormType` + data provider/handler services, PS 8+) where the module
  already uses them; otherwise `getContent()` + `HelperForm`. Either way: validate every field, show
  `displayConfirmation()`/`displayError()`, and keep HTML out of PHP.
- **Templates as components** (`ui-contract.md`, mapped in `ui-implementation.md`): screens assign prepared,
  immutable data and include Smarty partials with explicit parameters (`{include file='module:…' order=$order}`)
  or Twig partials with `{% include … with { … } only %}`/`{% embed %}`; lists come from a bounded page (the
  Grid component in the back office, `ProductListingFrontController` or a `LIMIT`ed `DbQuery` in the front) —
  never an unbounded `executeS()` looped in a template.
- **Translations**: new system — `$this->trans('Loan saved.', [], 'Modules.Acmeloans.Admin')` (domain
  `Modules.<Modulename>.Admin|Shop`, module name with only the first letter capitalized); Smarty
  `{l s='Loan saved.' d='Modules.Acmeloans.Shop'}`; Twig `'Loan saved.'|trans({}, 'Modules.Acmeloans.Admin')`.
  Wordings are literal strings (the scanner can't see variables). Legacy modules use `$this->l('…')` — match the
  module.

## Tests — the honest picture

- Most module logic is only testable without a shop if it lives in **plain classes under `src/`** with
  dependencies injected. Put new logic there (hook methods in the main class become thin adapters) and unit-test
  it with PHPUnit (`tests/Unit`, `PHPUnit\Framework\TestCase`), stubbing the PrestaShop collaborators you
  wrapped behind your own interfaces. That is the fast stage.
- Anything that touches `Db`, `Configuration`, `Context`, `ObjectModel`, Smarty, or the Symfony kernel needs a
  **PrestaShop installation** (the module placed in `modules/`, usually a `prestashop/prestashop` Docker image,
  bootstrap requiring `config/config.inc.php`) — those are integration tests, run by the group skill under its
  lock, or not at all if the project has no shop-backed suite. Don't fake PrestaShop's static classes with
  stub definitions inside unit tests unless the project already has that harness.
- **Browser UI tests** (Playwright + axe, screenshot matrix) run against an already running shop at
  `PS_BASE_URL` and skip without one (`ui-implementation.md`); write them, never run them here.
- Static analysis is the main safety net for code you can't unit-test: write code PHPStan (with
  `_PS_ROOT_DIR_` set) can type-check.
