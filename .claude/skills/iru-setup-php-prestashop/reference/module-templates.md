# Module templates — `iru-setup-php-prestashop`

The module's own files: main class, `config.xml`, sample domain code under `src/`, the Symfony configuration page
(form + controller + services + route + Twig), the `displayFooter` hook template, `upgrade/`, the `index.php`
directory guard, and the logo. Every template below was generated into a throwaway module, installed into real
`prestashop/prestashop:8` (8.2.8, PHP 8.1) and `prestashop/prestashop:9` (9.2.0, PHP 8.5) shops with
`php bin/console prestashop:module install <module>`, and exercised end to end (install/uninstall/reinstall, the
module manager's **Configure** button through `getContent()`, rendering and saving the form with both a valid and an
invalid value, the hook's output on the storefront) — see SKILL.md "Verification notes".

Copy them verbatim, substituting only the `<placeholder>` values resolved in SKILL.md Steps 2–3:

| Placeholder | Meaning | Example |
|---|---|---|
| `<module>` | technical name: lowercase letters and digits, matches the directory and main file | `acmegreeting` |
| `<ModuleClass>` | main class name: `<module>` with only the first letter uppercased (also the translation-domain segment) | `Acmegreeting` |
| `<MODULE>` | `<module>` uppercased: prefix of every `Configuration` key | `ACMEGREETING` |
| `<Namespace>` | root PSR-4 namespace (`<NamespaceJson>` = the same with every `\` doubled, for JSON) | `Acme\Greeting` |
| `<display-name>`, `<description>` | module name and one-line description shown in the module manager | |
| `<developer-name>`, `<developer-email>` | `$this->author`, header `@author` | |
| `<tab>` | module-manager category (`front_office_features`, `administration`, `payments_gateways`, ...) | |
| `<ps-min>` | `ps_versions_compliancy` minimum: `8.0.0`, or `9.0.0` when the PHP floor is above 8.1 | |
| `<license-header>` | the PHP docblock from "License headers" below | |
| `<license-short-header>` | one-line notice for Twig/Smarty templates | |

The sample feature — a configurable greeting rendered above the storefront footer — exists so every gate has real
code to check right after scaffolding: replace it with the module's first real feature, keeping the split (pure
logic under `src/Settings`/`src/Presenter`, unit-tested; PrestaShop/Symfony glue under `src/Form`/`src/Controller`
and the main class, checked by PHPStan and the Integration suite).

## License headers

`<license-header>` for **AFL-3.0** (PrestaShop's convention for modules), **OSL-3.0**, **MIT**, or another
OSI license — fill `<license-display-name>`/`<license-url>` (`Academic Free License version 3.0` /
`https://opensource.org/licenses/AFL-3.0`; `Open Software License version 3.0` /
`https://opensource.org/licenses/OSL-3.0`; `MIT License` / `https://opensource.org/licenses/MIT`):

```php
/**
 * Copyright (c) <year> <developer-name>
 *
 * NOTICE OF LICENSE
 *
 * This source file is subject to the <license-display-name>
 * that is bundled with this package in the file LICENSE.
 * It is also available through the world-wide-web at this URL:
 * <license-url>
 *
 * @author    <developer-name> <<developer-email>>
 * @copyright Since <year> <developer-name>
 * @license   <license-url> <license-display-name>
 */
```

**Proprietary** (no license — common for paid Addons modules):

```php
/**
 * Copyright (c) <year> <developer-name>. All rights reserved.
 *
 * NOTICE OF LICENSE
 *
 * This source file is proprietary. It may not be copied, modified, or distributed without the written permission
 * of the copyright holder.
 *
 * @author    <developer-name> <<developer-email>>
 * @copyright Since <year> <developer-name>
 * @license   Proprietary
 */
```

`<license-short-header>` (Twig `{# … #}` / Smarty `{* … *}`): `Copyright (c) <year> <developer-name>` on one line,
then `Licensed under the <license-display-name> (<license-url>).` — or `All rights reserved.` when proprietary.

Every PHP file starts `<?php`, **one blank line**, then the header (PrestaShop's PHP-CS-Fixer config enforces
`blank_line_after_opening_tag` — verified: without the blank line all files fail `format:check`), then a blank line
and `declare(strict_types=1);` (not in `index.php` guards or `.php-cs-fixer.dist.php`).

### `<module>.php`

```php
<?php

<license-header>

declare(strict_types=1);

use <Namespace>\Presenter\FooterMessagePresenter;
use <Namespace>\Settings\MessageSettings;
use <Namespace>\Settings\SettingsKeys;

if (!defined('_PS_VERSION_')) {
    exit;
}

require_once __DIR__ . '/vendor/autoload.php';

/**
 * Module entry point: identity, install/uninstall, hook adapters, and the link to the Symfony configuration page.
 */
class <ModuleClass> extends Module
{
    /**
     * Hooks registered on install. A hook added later must also be registered by an upgrade/ script.
     *
     * @var list<string>
     */
    public const HOOKS = ['displayFooter'];

    /**
     * Symfony route of the back-office configuration page (config/routes.yml).
     *
     * @var string
     */
    public const CONFIGURATION_ROUTE = '<module>_configuration';

    /**
     * Declares the module identity and its translated labels.
     */
    public function __construct()
    {
        $this->name = '<module>';
        $this->tab = '<tab>';
        $this->version = '0.1.0-dev';
        $this->author = '<developer-name>';
        $this->need_instance = 0;
        $this->ps_versions_compliancy = ['min' => '<ps-min>', 'max' => '9.99.99'];
        $this->bootstrap = true;

        parent::__construct();

        $this->displayName = $this->trans('<display-name>', [], 'Modules.<ModuleClass>.Admin');
        $this->description = $this->trans('<description>', [], 'Modules.<ModuleClass>.Admin');
        $this->confirmUninstall = $this->trans('Uninstalling removes the greeting settings. Continue?', [], 'Modules.<ModuleClass>.Admin');
    }

    /**
     * Opts into the translation system based on Symfony translation domains.
     *
     * @return bool
     */
    public function isUsingNewTranslationSystem()
    {
        return true;
    }

    /**
     * Registers the hooks and stores the default settings; any failure aborts the installation.
     *
     * @return bool
     */
    public function install()
    {
        if (Shop::isFeatureActive()) {
            Shop::setContext(Shop::CONTEXT_ALL);
        }

        $defaults = MessageSettings::defaults();

        return parent::install()
            && $this->registerHook(self::HOOKS)
            && Configuration::updateValue(SettingsKeys::ENABLED, (int) $defaults->enabled)
            && Configuration::updateValue(SettingsKeys::MESSAGE, $defaults->message);
    }

    /**
     * Removes every configuration key this module owns, then uninstalls it.
     *
     * @return bool
     */
    public function uninstall()
    {
        return Configuration::deleteByName(SettingsKeys::ENABLED)
            && Configuration::deleteByName(SettingsKeys::MESSAGE)
            && parent::uninstall();
    }

    /**
     * Sends the "Configure" button of the module manager to the Symfony configuration page.
     *
     * @return string
     */
    public function getContent()
    {
        Tools::redirectAdmin(
            $this->context->link->getAdminLink('AdminModules', true, ['route' => self::CONFIGURATION_ROUTE])
        );

        return '';
    }

    /**
     * Renders the greeting above the footer; thin adapter over FooterMessagePresenter.
     *
     * @param array<string, mixed> $params hook parameters supplied by PrestaShop
     */
    public function hookDisplayFooter(array $params): string
    {
        $view = (new FooterMessagePresenter())->present($this->loadSettings());
        if (null === $view) {
            return '';
        }

        $this->context->smarty->assign(['<module>' => $view]);

        return $this->fetch('module:<module>/views/templates/hook/footer.tpl');
    }

    /**
     * Reads the stored settings for the current shop context.
     */
    private function loadSettings(): MessageSettings
    {
        return new MessageSettings(
            '1' === (string) Configuration::get(SettingsKeys::ENABLED),
            (string) Configuration::get(SettingsKeys::MESSAGE)
        );
    }
}
```

- No return types on `install()`, `uninstall()`, `getContent()`, `isUsingNewTranslationSystem()`: they override
  untyped core methods (8.x and 9.x); `@return` docblocks carry the types. New methods (`hookDisplayFooter`,
  `loadSettings`) are fully typed.
- `$this->version = '0.1.0-dev'` follows the catalog's `x.y.z-dev` convention maintained by `iru-php-bump-version`.
  **Verified caveat**: PrestaShop stores the installed version in `ps_module.version`, a `VARCHAR(8)` column on 8.x
  and 9.x, so a development shop records `0.1.0-de`; `version_compare()` then ranks the files' `0.1.0-dev` above it
  and the module manager offers an **Upgrade** for the dev build indefinitely (`prestashop:module upgrade` succeeds
  and stores the truncated value again). Release versions (`x.y.z`, at most 8 characters) are unaffected; never
  ship a `-dev` zip to a real shop.
- `getContent()` redirects with `Link::getAdminLink('AdminModules', true, ['route' => ...])` (typed `string`, unlike
  the `$this->get('router')` service lookup, so the stub-based PHPStan run can check it). Verified on 8.x (legacy
  `AdminModules&configure=` → 302 to the route) and 9.x (the module manager's Configure action lands on the route).
- `$this->fetch('module:<module>/views/...')` renders the hook template; front-office Smarty auto-escapes, so the
  template prints `{$<module>.message}` without `|escape`.

### `config.xml`

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<module>
    <name><module></name>
    <displayName><![CDATA[<display-name>]]></displayName>
    <version><![CDATA[0.1.0-dev]]></version>
    <description><![CDATA[<description>]]></description>
    <author><![CDATA[<developer-name>]]></author>
    <tab><![CDATA[<tab>]]></tab>
    <confirmUninstall><![CDATA[Uninstalling removes the greeting settings. Continue?]]></confirmUninstall>
    <is_configurable>1</is_configurable>
    <need_instance>0</need_instance>
    <limited_countries></limited_countries>
</module>
```

Track `config.xml` (the bump skill updates its `<version>`; shipping it lets PrestaShop run upgrade scripts straight
after an uploaded zip is extracted). **Verified**: installing the module into a shop whose `modules/<module>`
directory is the working copy (the Docker dev shop of `tooling-templates.md`) makes PrestaShop **rewrite**
`config.xml` in place (tab indentation, `<limited_countries>` dropped) — review `git diff config.xml` after using the
dev shop and keep this template's form. The per-language `config_<iso>.xml` caches it also writes are ignored by
`iru-setup-php-gitignore`.

### `src/Settings/MessageSettings.php`

```php
<?php

<license-header>

declare(strict_types=1);

namespace <Namespace>\Settings;

/**
 * The greeting settings edited on the configuration page; pure PHP so it is unit-testable without a shop.
 */
final class MessageSettings
{
    /**
     * Maximum greeting length, in characters.
     *
     * @var int
     */
    public const MAX_LENGTH = 255;

    /**
     * @param bool $enabled whether the greeting is shown
     * @param string $message the greeting text (plain text, no markup)
     */
    public function __construct(
        public readonly bool $enabled,
        public readonly string $message,
    ) {
    }

    /**
     * The values stored on install.
     */
    public static function defaults(): self
    {
        return new self(true, 'Welcome to our shop!');
    }

    /**
     * Lists every rule the submitted values break; an empty list means they can be saved.
     *
     * @return list<MessageViolation>
     */
    public static function violations(bool $enabled, string $message): array
    {
        $violations = [];
        $trimmed = trim($message);

        if ($enabled && '' === $trimmed) {
            $violations[] = MessageViolation::Empty;
        }
        if (mb_strlen($trimmed) > self::MAX_LENGTH) {
            $violations[] = MessageViolation::TooLong;
        }
        if ($trimmed !== strip_tags($trimmed)) {
            $violations[] = MessageViolation::Markup;
        }

        return $violations;
    }
}
```

### `src/Settings/MessageViolation.php`

```php
<?php

<license-header>

declare(strict_types=1);

namespace <Namespace>\Settings;

/**
 * A rule a submitted greeting can break; the form layer maps each case to a translated message.
 */
enum MessageViolation: string
{
    case Empty = 'empty';
    case TooLong = 'too_long';
    case Markup = 'markup';
}
```

### `src/Settings/SettingsKeys.php`

```php
<?php

<license-header>

declare(strict_types=1);

namespace <Namespace>\Settings;

/**
 * Configuration keys owned by the module, prefixed with its name so they never collide with core or other modules.
 */
final class SettingsKeys
{
    /**
     * Whether the greeting is shown ("1"/"0").
     *
     * @var string
     */
    public const ENABLED = '<MODULE>_ENABLED';

    /**
     * The greeting text.
     *
     * @var string
     */
    public const MESSAGE = '<MODULE>_MESSAGE';
}
```

`SettingsKeys` has constants only (no private constructor): a constructor would be an uncovered line in the
unit coverage report.
### `src/Presenter/FooterMessagePresenter.php`

```php
<?php

<license-header>

declare(strict_types=1);

namespace <Namespace>\Presenter;

use <Namespace>\Settings\MessageSettings;

/**
 * Builds the template variables of the displayFooter hook from the stored settings.
 */
final class FooterMessagePresenter
{
    /**
     * Returns the template variables, or null when nothing should be rendered.
     *
     * @return array{message: string}|null
     */
    public function present(MessageSettings $settings): ?array
    {
        $message = trim($settings->message);
        if (!$settings->enabled || '' === $message) {
            return null;
        }

        return ['message' => $message];
    }
}
```

## Symfony configuration page (8.x and 9.x)

The official "configuration page (modern)" pattern: a `TranslatorAwareType` form, a `DataConfigurationInterface`
storage class, a `FormDataProviderInterface`, and a core `PrestaShop\PrestaShop\Core\Form\Handler` service wired
in `config/services.yml`, behind a module Symfony controller. Every class and service id used here exists with the
same signature on 8.x and 9.x (verified in both images).

### `src/Form/MessageConfigurationType.php`

```php
<?php

<license-header>

declare(strict_types=1);

namespace <Namespace>\Form;

use <Namespace>\Settings\MessageSettings;
use PrestaShopBundle\Form\Admin\Type\SwitchType;
use PrestaShopBundle\Form\Admin\Type\TranslatorAwareType;
use Symfony\Component\Form\Extension\Core\Type\TextType;
use Symfony\Component\Form\FormBuilderInterface;

/**
 * Symfony form of the configuration page (PrestaShop 8.x and 9.x).
 */
class MessageConfigurationType extends TranslatorAwareType
{
    /**
     * {@inheritdoc}
     *
     * @param FormBuilderInterface<mixed> $builder
     * @param array<string, mixed> $options
     */
    public function buildForm(FormBuilderInterface $builder, array $options): void
    {
        $builder
            ->add('enabled', SwitchType::class, [
                'label' => $this->trans('Show the greeting', 'Modules.<ModuleClass>.Admin'),
                'required' => false,
            ])
            ->add('message', TextType::class, [
                'label' => $this->trans('Greeting', 'Modules.<ModuleClass>.Admin'),
                'help' => $this->trans('Plain text, up to %max% characters.', 'Modules.<ModuleClass>.Admin', ['%max%' => MessageSettings::MAX_LENGTH]),
                'required' => false,
                'attr' => ['maxlength' => MessageSettings::MAX_LENGTH],
            ]);
    }
}
```

### `src/Form/MessageDataConfiguration.php`

```php
<?php

<license-header>

declare(strict_types=1);

namespace <Namespace>\Form;

use <Namespace>\Settings\MessageSettings;
use <Namespace>\Settings\MessageViolation;
use <Namespace>\Settings\SettingsKeys;
use PrestaShop\PrestaShop\Core\Configuration\DataConfigurationInterface;
use PrestaShop\PrestaShop\Core\ConfigurationInterface;
use Symfony\Contracts\Translation\TranslatorInterface;

/**
 * Reads and writes the greeting settings through PrestaShop's configuration service.
 */
final class MessageDataConfiguration implements DataConfigurationInterface
{
    /**
     * @param ConfigurationInterface $configuration PrestaShop configuration storage
     * @param TranslatorInterface $translator translates validation errors
     */
    public function __construct(
        private readonly ConfigurationInterface $configuration,
        private readonly TranslatorInterface $translator,
    ) {
    }

    /**
     * {@inheritdoc}
     *
     * @return array{enabled: bool, message: string}
     */
    public function getConfiguration(): array
    {
        return [
            'enabled' => '1' === (string) $this->configuration->get(SettingsKeys::ENABLED),
            'message' => (string) $this->configuration->get(SettingsKeys::MESSAGE),
        ];
    }

    /**
     * {@inheritdoc}
     *
     * @param array<string, mixed> $configuration
     *
     * @return list<string> translated errors; empty when saved
     */
    public function updateConfiguration(array $configuration): array
    {
        $enabled = (bool) ($configuration['enabled'] ?? false);
        $message = trim((string) ($configuration['message'] ?? ''));

        $errors = array_map(
            fn (MessageViolation $violation): string => $this->describe($violation),
            MessageSettings::violations($enabled, $message)
        );
        if ([] !== $errors) {
            return $errors;
        }

        $this->configuration->set(SettingsKeys::ENABLED, $enabled ? '1' : '0');
        $this->configuration->set(SettingsKeys::MESSAGE, $message);

        return [];
    }

    /**
     * {@inheritdoc}
     *
     * @param array<string, mixed> $configuration
     */
    public function validateConfiguration(array $configuration): bool
    {
        return array_key_exists('message', $configuration);
    }

    /**
     * Maps a violation to its translated wording (literal strings, so the translation scanner finds them).
     */
    private function describe(MessageViolation $violation): string
    {
        return match ($violation) {
            MessageViolation::Empty => $this->translator->trans('Enter a greeting or hide it.', [], 'Modules.<ModuleClass>.Admin'),
            MessageViolation::TooLong => $this->translator->trans('The greeting is too long.', [], 'Modules.<ModuleClass>.Admin'),
            MessageViolation::Markup => $this->translator->trans('The greeting must be plain text.', [], 'Modules.<ModuleClass>.Admin'),
        };
    }
}
```

### `src/Form/MessageFormDataProvider.php`

```php
<?php

<license-header>

declare(strict_types=1);

namespace <Namespace>\Form;

use PrestaShop\PrestaShop\Core\Configuration\DataConfigurationInterface;
use PrestaShop\PrestaShop\Core\Form\FormDataProviderInterface;

/**
 * Feeds the configuration form handler from the data configuration.
 */
final class MessageFormDataProvider implements FormDataProviderInterface
{
    /**
     * @param DataConfigurationInterface $dataConfiguration storage of the form values
     */
    public function __construct(private readonly DataConfigurationInterface $dataConfiguration)
    {
    }

    /**
     * {@inheritdoc}
     *
     * @return array<string, mixed>
     */
    public function getData(): array
    {
        return $this->dataConfiguration->getConfiguration();
    }

    /**
     * {@inheritdoc}
     *
     * @param array<string, mixed> $data
     *
     * @return array<int, string>
     */
    public function setData(array $data): array
    {
        return $this->dataConfiguration->updateConfiguration($data);
    }
}
```

### `src/Controller/Admin/ConfigurationController.php`

```php
<?php

<license-header>

declare(strict_types=1);

namespace <Namespace>\Controller\Admin;

use PrestaShop\PrestaShop\Core\Form\FormHandlerInterface;
use PrestaShopBundle\Controller\Admin\FrameworkBundleAdminController;
use PrestaShopBundle\Security\Annotation\AdminSecurity;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Contracts\Translation\TranslatorInterface;

/**
 * Back-office configuration page.
 *
 * Compatible with PrestaShop 8.x and 9.x: FrameworkBundleAdminController and the AdminSecurity annotation exist on
 * both (deprecated on 9). A module requiring PrestaShop 9.0 or later extends PrestaShopAdminController and uses the
 * AdminSecurity PHP attribute (PrestaShopBundle\Security\Attribute namespace) instead. Never write the annotation
 * name with a leading at-sign in prose: Doctrine parses it as a second, empty security rule.
 */
class ConfigurationController extends FrameworkBundleAdminController
{
    /**
     * @param FormHandlerInterface $formHandler handler of the greeting form (config/services.yml)
     * @param TranslatorInterface $translator injected: the base class's trans() helper relies on a container lookup
     *                                        that a module controller's service locator does not provide on 8.x
     */
    public function __construct(
        private readonly FormHandlerInterface $formHandler,
        private readonly TranslatorInterface $translator,
    ) {
    }

    /**
     * Shows and saves the configuration form.
     *
     * @AdminSecurity("is_granted('update', 'AdminModulesSf')", message="Access denied.")
     */
    public function indexAction(Request $request): Response
    {
        $form = $this->formHandler->getForm();
        $form->handleRequest($request);

        if ($form->isSubmitted() && $form->isValid()) {
            $errors = $this->formHandler->save($form->getData());
            if ([] === $errors) {
                $this->addFlash('success', $this->translator->trans('Successful update.', [], 'Admin.Notifications.Success'));

                return $this->redirectToRoute('<module>_configuration');
            }

            foreach ($errors as $error) {
                $this->addFlash('error', $error);
            }
        }

        return $this->render('@Modules/<module>/views/templates/admin/configure.html.twig', [
            'configurationForm' => $form->createView(),
        ]);
    }
}
```

Verified rules behind this controller — each one broke the page on a real shop before it was fixed:

- **8.x + 9.x support** → `FrameworkBundleAdminController` + the `PrestaShopBundle\Security\Annotation\AdminSecurity`
  docblock annotation. On 9.x the annotation class is kept (deprecated) as a subclass of the new attribute and is
  still read; on 8.x the attribute class does not exist. When `<ps-min>` is `9.0.0`, the 9-only form is
  `PrestaShopAdminController` + `#[AdminSecurity("...")]` (`PrestaShopBundle\Security\Attribute\AdminSecurity`) —
  this skill did not run that variant in a shop; keep the 8+9 form unless the user asks.
- **Never write the annotation name with a leading `@` in prose** (class or method docblock): Doctrine's reader
  parses it as a second `AdminSecurity` with an empty expression and 8.x answers every request with
  `ExpressionLanguage\SyntaxError: Unexpected token "end of expression"` (HTTP 500).
- **Inject `TranslatorInterface`**; don't call the base class's `$this->trans()` (or `flashErrors()`, which calls it
  for array errors): on 8.x it does `$this->container->get('translator')`, and an autoconfigured module controller
  only receives AbstractController's small service locator — a successful save crashed with `Service "translator"
  not found`. Constructor injection without `parent::__construct()` is fine (8.x's constructor is deprecated and
  only triggers a notice).
- **Permission expression names the module manager**, `is_granted('update', 'AdminModulesSf')`, instead of
  `request.get('_legacy_controller')`; see `routes.yml` below for why the route carries no `_legacy_controller`.

### `config/services.yml`

```yaml
services:
  _defaults:
    public: false
    autowire: true
    autoconfigure: true

  <Namespace>\Form\MessageConfigurationType:
    parent: 'form.type.translatable.aware'
    public: true
    autowire: false
    autoconfigure: false
    tags:
      - { name: form.type }

  <Namespace>\Form\MessageDataConfiguration:
    arguments:
      $configuration: '@prestashop.adapter.legacy.configuration'
      $translator: '@translator'

  <Namespace>\Form\MessageFormDataProvider:
    arguments:
      $dataConfiguration: '@<Namespace>\Form\MessageDataConfiguration'

  <module>.form.message_configuration_handler:
    class: 'PrestaShop\PrestaShop\Core\Form\Handler'
    public: true
    autowire: false
    autoconfigure: false
    arguments:
      - '@form.factory'
      - '@prestashop.core.hook.dispatcher'
      - '@<Namespace>\Form\MessageFormDataProvider'
      - '<Namespace>\Form\MessageConfigurationType'
      - '<ModuleClass>Configuration'

  <Namespace>\Controller\Admin\ConfigurationController:
    public: true
    arguments:
      $formHandler: '@<module>.form.message_configuration_handler'
      $translator: '@translator'
    tags:
      - { name: controller.service_arguments }
```

### `config/routes.yml`

```yaml
<module>_configuration:
  path: /<module>/configuration
  methods: [GET, POST]
  defaults:
    _controller: '<Namespace>\Controller\Admin\ConfigurationController::indexAction'
```

- PrestaShop prefixes module routes with `/modules` (`/admin-dev/index.php/modules/<module>/configuration`).
- **Do not set `_legacy_controller` to a core controller name** (`AdminModules`, `AdminModulesSf`, ...). Verified on
  8.x: on install PrestaShop registers a tab for every `_legacy_controller` it finds in a module's routes, so
  `_legacy_controller: AdminModules` created an inactive `AdminModules` tab owned by the module and broke the legacy
  **Configure** page of *every* module ("The controller AdminModules is missing or invalid") until uninstall. A
  module that wants its own menu entry declares a tab with its own class name (`Admin<ModuleClass>Configuration`,
  via the `$tabs` property) and uses that name as `_legacy_controller`.

### `views/templates/admin/configure.html.twig`

```twig
{#
 # <license-short-header>
 #}
{% extends '@PrestaShop/Admin/layout.html.twig' %}

{% block content %}
  {{ form_start(configurationForm) }}
  <div class="card">
    <h3 class="card-header">
      <i class="material-icons">settings</i> {{ 'Greeting'|trans({}, 'Modules.<ModuleClass>.Admin') }}
    </h3>
    <div class="card-body">
      <div class="form-wrapper">
        {{ form_widget(configurationForm) }}
      </div>
    </div>
    <div class="card-footer">
      <div class="d-flex justify-content-end">
        <button class="btn btn-primary float-right" id="save-button">
          {{ 'Save'|trans({}, 'Admin.Actions') }}
        </button>
      </div>
    </div>
  </div>
  {{ form_end(configurationForm) }}
{% endblock %}
```

### `views/templates/hook/footer.tpl`

```smarty
{*
 * <license-short-header>
 *}
<div class="<module>-message">
  <p>{$<module>.message}</p>
</div>
```

## `upgrade/`, `translations/`, directory guards, logo

### `upgrade/README.md`

```markdown
# Upgrade scripts

PrestaShop runs `upgrade/upgrade-<x.y.z>.php` when a shop updates this module from an older version: every script
whose version is newer than the installed one and lower than or equal to the new `$this->version`, in version order.
`install()` never runs again on an existing shop, so anything a release adds to it — a hook, a configuration key, a
table or column — must also be added here.

- File `upgrade/upgrade-1.3.0.php` defines `function upgrade_module_1_3_0(<ModuleClass> $module): bool` (dots become
  underscores; the version matches the release's `$this->version` exactly).
- Start with the `_PS_VERSION_` guard, keep it idempotent (`CREATE TABLE IF NOT EXISTS`, check a column exists
  before adding it, `registerHook()` is safe to repeat), and return `true` only on success.
- Ship it in the same release that bumps `$this->version` and `config.xml` `<version>`.

```php
<?php
if (!defined('_PS_VERSION_')) {
    exit;
}

function upgrade_module_1_3_0(<ModuleClass> $module): bool
{
    return $module->registerHook('displayOrderConfirmation')
        && Configuration::updateValue('<MODULE>_SHOW_ON_CONFIRMATION', 1);
}
```

This README is excluded from the release zip (`.distignore`).
```

`translations/` starts with only its `index.php`: the module uses the new translation system
(`isUsingNewTranslationSystem()` → `true`, domains `Modules.<ModuleClass>.Admin|Shop`, literal wordings only).
Translators export/import catalogues from **International → Translations** in the back office; ship the exported
`translations/<locale>/*.xlf` files when the module provides its own translations.

### `index.php`

```php
<?php

<license-header>
header('Expires: Mon, 26 Jul 1997 05:00:00 GMT');
header('Last-Modified: ' . gmdate('D, d M Y H:i:s') . ' GMT');
header('Cache-Control: no-store, no-cache, must-revalidate');
header('Cache-Control: post-check=0, pre-check=0', false);
header('Pragma: no-cache');
header('Location: ../');
exit;
```

Write this exact file (with the license header) into the module root and **every directory the module ships**:
`config/`, `src/` and each of its subdirectories, `views/` and every subdirectory, `upgrade/`, `translations/`
(PrestaShop Addons' technical checklist requires one per folder; it stops directory listing). Directories excluded
from the zip (`tests/`, `bin/`, `build/`, `docs/`, `vendor/` in the source tree) don't need one — `bin/package.sh`
adds the guard to every directory of the built package that lacks one, `vendor/` included. The coverage `<source>`
excludes `src/**/index.php`.

### `logo.png`

A 32×32 PNG in the module root (shown in the module manager). Generate a solid-colour placeholder — verified to
produce a valid `PNG image data, 32 x 32, 8-bit/color RGB` file — and tell the user to replace it:

```bash
php -r '$w=32;$h=32;$raw="";for($y=0;$y<$h;$y++){$raw.="\0".str_repeat("\x25\xb9\xd7",$w);}$c=function($t,$d){return pack("N",strlen($d)).$t.$d.pack("N",crc32($t.$d));};file_put_contents("logo.png","\x89PNG\r\n\x1a\n".$c("IHDR",pack("NNCCCCC",$w,$h,8,2,0,0,0)).$c("IDAT",gzcompress($raw,9)).$c("IEND",""));'
```

(Without a host PHP: `docker run --rm -v "$PWD":/w -w /w php:8.4-cli php -r '...'`.)
