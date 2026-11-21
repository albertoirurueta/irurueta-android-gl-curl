# CDI and beans (ArC)

Quarkus's CDI container, ArC, resolves the whole bean graph **at build time**. Most "it compiles but doesn't
work" defects in Quarkus code are a bean ArC never discovered, removed as unused, or couldn't proxy. Read this
before writing any class that is injected or injects something.

## Discovery: only bean-defining annotations count

A class is a bean only if it carries a **bean-defining annotation**: a scope (`@ApplicationScoped`,
`@RequestScoped`, `@Singleton`, `@Dependent`, `@SessionScoped`), a stereotype, or an interceptor/decorator
annotation. There is no `beans.xml` "scan everything" mode. Consequences:

- A plain class with `@Inject` fields but no scope annotation is **not a bean** — injecting it fails the build
  with an unsatisfied dependency. Give every new service/adapter an explicit scope.
- `@Path` resource classes, `@Provider` classes, `@ConfigMapping` interfaces, REST Client interfaces
  (`@RegisterRestClient`), Panache repositories, and `@Scheduled`/messaging methods' declaring classes are made
  beans by their extension — they don't need a scope added just to be discovered (add one only when you need its
  semantics; see the table below).
- A bean in a **separate module without a Jandex index** is invisible to ArC. In a multi-module build, a library
  module whose beans are not found needs the `jandex-maven-plugin` / `org.kordamp.gradle.jandex` index (or
  `quarkus.index-dependency.*`). If the module you're adding a bean to already has neither and its existing beans
  are found, it's indexed some other way — don't add one. If its beans are *not* found, that's a build change to
  report (Step 6 of the skill), not something to work around with `CDI.current()`.

## Unused beans are removed

ArC removes beans that nothing injects. A bean only ever looked up dynamically
(`CDI.current().select(...)`, `Instance<T>` with a runtime-computed qualifier, reflection from a library) is
removed unless kept:

- Mark it `@io.quarkus.arc.Unremovable`, or
- inject it through `Instance<T>`/`@All List<T>` so ArC sees the injection point.

Prefer real injection points over dynamic lookup in new code. Never disable removal globally
(`quarkus.arc.remove-unused-beans=none`) to make one bean appear.

## Scopes and proxies

| Scope | Instances | Proxy | Use for |
|---|---|---|---|
| `@ApplicationScoped` | one, created lazily | **client proxy** | The default for services, adapters, repositories. |
| `@Singleton` | one, created eagerly on first injection | none | Hot-path beans where proxy cost matters, or a class that must stay `final` (Java) / non-opened (Kotlin). Cannot be mocked with `@InjectMock` in `@QuarkusTest` (needs a normal scope). |
| `@RequestScoped` | one per request context | client proxy | Request-bound mutable state. |
| `@Dependent` | one per injection point | none | Small helpers owned by one bean; the default when no scope is present on a producer. |

- A **normal-scoped** (`@ApplicationScoped`, `@RequestScoped`) bean, and any bean with an interceptor binding
  (`@Transactional`, `@Timed`, `@Retry`, `@CacheResult`, …), is subclassed. In Java it must not be `final` and
  its intercepted methods must not be `final` or `private`. In Kotlin it must be **open** — see `kotlin.md`'s
  all-open check.
- `@Path` classes default to `@Singleton` when unannotated. Add `@ApplicationScoped` only when the resource needs
  a proxy (an interceptor on the class) or the module's other resources use it.
- Self-invocation (`this.otherMethod()`) bypasses interceptors — a `@Transactional` method called from the same
  bean runs without its own transaction semantics. Move the boundary to the caller or another bean.
- Don't keep request-specific state in an `@ApplicationScoped`/`@Singleton` field; it is shared by every
  concurrent request.

## Injection style

Both styles are supported; match the module, then apply these defaults for new code:

- **Constructor injection** for new beans (`private final` fields in Java, `private val` constructor properties in
  Kotlin). A bean with a single constructor needs no `@Inject` on it in Quarkus. It makes the bean trivially
  unit-testable with `new`/MockK/Mockito and no container.
- **Field injection, when the module already uses it**: declare the field **package-private** (Java) or
  `lateinit var` without `private` (Kotlin) — ArC injects non-private members without reflection and logs a
  warning (and, in native, needs reflection) for `private @Inject` fields. Never add `private` to an injected field.
- Package-private visibility generally is the Quarkus-friendly default for members ArC or a framework must reach
  (injected fields, `@Observes` methods, producer methods), and it keeps tests in the same package able to use
  them.
- Qualifiers: use the existing qualifier annotations; for one-off disambiguation, `@Named` is acceptable only if
  the module already uses it. `@Identifier` (SmallRye) is the Quarkus-native choice for extension-provided beans.

## Producers, alternatives, events, startup

- `@Produces` methods for third-party types you can't annotate; give the producer method an explicit scope.
- `@Alternative` + `@Priority`, or `@io.quarkus.arc.profile.IfBuildProfile` / `@LookupIfProperty` /
  `@IfBuildProperty`, to swap implementations — **build-time** decisions; they cannot flip at runtime.
- `@io.quarkus.arc.DefaultBean` for a fallback another module may override.
- Events: `Event<T>.fire(...)` / `@Observes`; `@ObservesAsync` for asynchronous delivery. Startup work:
  `@Startup` beans or `void onStart(@Observes StartupEvent ev)`. Keep startup observers fast — they delay
  readiness.

## No runtime registration

The graph is closed once built. Don't register beans at runtime, don't build a service locator around
`CDI.current()`, and don't rely on classpath scanning (`ServiceLoader` over app classes, reflection over
packages) — it neither works with removal nor in native mode. If a task genuinely needs plugin-style discovery,
inject `@All List<Plugin>` or `Instance<Plugin>`.

## Testing implication

A bean with constructor injection and no Quarkus API in its logic is a plain JUnit test. A bean whose behaviour
depends on CDI (interceptors, `Instance<T>`, config injection, events) is a `@QuarkusComponentTest`. See
`testing.md`.
