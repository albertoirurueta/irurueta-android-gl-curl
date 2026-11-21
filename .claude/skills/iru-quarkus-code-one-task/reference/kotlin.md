# Kotlin in a Quarkus module

Two parts: the **build check** that must pass before any Kotlin code is written, and the **mandatory rules** for
every line of Kotlin production and test code this skill writes (they take precedence over "match the
surrounding code" elsewhere in `reference/`). The rules match this catalog's Kotlin Spring Boot agreements so a
team moving between the two stacks sees one style.

## Part 1 — Build check (run before writing code)

Kotlin classes are `final` and have no no-arg constructor unless told otherwise. Quarkus needs both for some
classes, and the `quarkus-kotlin` extension does **not** add the compiler plugins for you — a missing one shows up
as a build-time "unproxyable bean" error at best, or a confusing runtime failure.

### What's required, by what the task adds

| The task adds or relies on | Required in the module's Kotlin compiler config |
|---|---|
| Any normal-scoped bean, resource with a scope, or intercepted bean (always, in practice) | **all-open** for `jakarta.enterprise.context.ApplicationScoped`, `jakarta.enterprise.context.RequestScoped`, `jakarta.ws.rs.Path`, `io.quarkus.test.junit.QuarkusTest` |
| A Hibernate `@Entity` (Panache or not) | all-open for `jakarta.persistence.Entity` (lazy-loading proxies; the code.quarkus.io template has it) |
| An `@Entity`/`@Embeddable`/`@MappedSuperclass` with **constructor parameters** (`class Book(var title: String)`) | **no-arg** for `jakarta.persistence.Entity`, `jakarta.persistence.Embeddable`, `jakarta.persistence.MappedSuperclass` |
| Any Kotlin code at all | the `quarkus-kotlin` extension, `kotlin-stdlib`; `javaParameters = true` (`-java-parameters`) so REST/config parameter names survive |

An `@ApplicationScoped` bean that isn't opened is reported as unproxyable; a `@Singleton`/`@Dependent` bean with
no interceptor needs nothing. A Panache entity with only `lateinit var` properties and no constructor parameters
compiles without no-arg; add it the first time an entity takes constructor parameters.

### How to check

Read the module's effective build: its own `pom.xml` / `build.gradle(.kts)` plus anything inherited (parent pom
`<pluginManagement>`, Gradle convention plugins in `buildSrc`/`build-logic`, the version catalog):

```bash
grep -n -E '<plugin>(all-open|no-arg)</plugin>|(all-open|no-arg):annotation=|kotlin-maven-(allopen|noarg)' pom.xml
grep -n -E 'plugin\.allopen|plugin\.noarg|allopen|noarg|allOpen|noArg|annotation\(' build.gradle.kts build.gradle 2>/dev/null
```

If something the task needs is missing, **add it now, before writing code**, in the place the project centralises
build configuration, keep the change minimal, and record it for the report — every module inheriting it is
affected. Then compile once (Maven `-pl <module> -am test-compile -q`, Gradle `:<module>:compileTestKotlin`) so a
broken plugin config fails here, not in the test run. Anything beyond this plumbing (MockK, coroutines,
`quarkus-junit-mockito`, `quarkus-jacoco`) is not added here — a missing test library is a blocker to report.

### Maven (`kotlin-maven-plugin`)

Add to the existing plugin (don't declare a second one); the version comes from `${kotlin.version}`:

```xml
<plugin>
    <groupId>org.jetbrains.kotlin</groupId>
    <artifactId>kotlin-maven-plugin</artifactId>
    <version>${kotlin.version}</version>
    <executions>
        <execution><id>compile</id><goals><goal>compile</goal></goals></execution>
        <execution><id>test-compile</id><goals><goal>test-compile</goal></goals></execution>
    </executions>
    <dependencies>
        <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-maven-allopen</artifactId>
            <version>${kotlin.version}</version>
        </dependency>
        <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-maven-noarg</artifactId>
            <version>${kotlin.version}</version>
        </dependency>
    </dependencies>
    <configuration>
        <javaParameters>true</javaParameters>
        <compilerPlugins>
            <plugin>all-open</plugin>
            <plugin>no-arg</plugin>
        </compilerPlugins>
        <pluginOptions>
            <option>all-open:annotation=jakarta.ws.rs.Path</option>
            <option>all-open:annotation=jakarta.enterprise.context.ApplicationScoped</option>
            <option>all-open:annotation=jakarta.enterprise.context.RequestScoped</option>
            <option>all-open:annotation=jakarta.persistence.Entity</option>
            <option>all-open:annotation=io.quarkus.test.junit.QuarkusTest</option>
            <option>no-arg:annotation=jakarta.persistence.Entity</option>
            <option>no-arg:annotation=jakarta.persistence.Embeddable</option>
            <option>no-arg:annotation=jakarta.persistence.MappedSuperclass</option>
        </pluginOptions>
    </configuration>
</plugin>
```

### Gradle (Kotlin DSL; Groovy uses the same blocks with `id 'org.jetbrains.kotlin.plugin.allopen'`)

Use the Kotlin version the build already declares (catalog alias or `kotlin("jvm") version "…"`):

```kotlin
plugins {
    kotlin("jvm") version "<kotlin-version>"
    kotlin("plugin.allopen") version "<kotlin-version>"
    kotlin("plugin.noarg") version "<kotlin-version>"
    id("io.quarkus")
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.enterprise.context.RequestScoped")
    annotation("jakarta.persistence.Entity")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

noArg {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.Embeddable")
    annotation("jakarta.persistence.MappedSuperclass")
}
```

## Part 2 — Mandatory rules

### Tests

- **JUnit 5**, assertions consistent with the module — `org.junit.jupiter.api.Assertions` or `kotlin.test`, never
  both in one class; never add AssertJ/Kotest/Strikt (the Quarkus docs' AssertJ examples don't apply).
- **MockK for plain unit tests** (`mockk<T>()`, `every`/`verify`, `coEvery`/`coVerify` for `suspend`,
  `mockkObject(Entity)` for a Panache companion). Strict mocks over `relaxed = true`. Never a hand-written fake.
- **Quarkus-managed mocks are Mockito**: `@InjectMock` in `@QuarkusComponentTest`/`@QuarkusTest` gives a Mockito
  mock — stub it with `` `when`(...) `` / `org.mockito.kotlin` if present. Use the Quarkiverse
  `quarkus-junit5-mockk` `@InjectMock` only if the module already has it. Don't introduce Mockito into a module's
  *plain* unit tests.
- **Names**: backtick sentences by default (`` fun `greet returns hello in the afternoon`() ``); if the module's
  tests use `method_whenCondition_expectedResult`, match that.
- **One test class per class**, same package; `*Test` for everything Surefire/`test` runs, `*IT` only for
  `@QuarkusIntegrationTest`. `@Inject lateinit var` / `@InjectMock lateinit var` for injected test fields.
- **Coroutines**: `= runTest { }`, never `runBlocking`, in tests.
- **KDoc on test classes and test functions** (scenario + expected outcome), unless the module's detekt config
  excludes tests from documentation rules and its tests carry none.

### Style

- **Constructor injection** via the primary constructor (`class OrderService(private val repo: OrderRepository)`);
  no `@Inject` needed on a single constructor. Field injection only where the module uses it — then
  `@Inject lateinit var` **without** `private`.
- `val` over `var`; immutable collections in public signatures (`List`, not `MutableList`).
- `data class` for DTOs, commands, value objects, events — **never** for `@Entity` classes. `@JvmInline value
  class` for single-value wrappers where the module uses them. `sealed` hierarchies for closed result types.
- Null safety: no `!!` in new code; model absence with `?` and handle it (`?:`, `?.let`, `requireNotNull` with a
  message). Panache `findById` returns `T?` — handle it.
- `@ConfigMapping` is an `interface` with `fun` members, not a data class.
- Named constants (`private const val` in a companion or top level) for magic numbers/strings.
- Stdlib idioms over manual loops (`any`, `none`, `firstOrNull`, `associateBy`, `buildList`); no redundant
  `this.`; `when` over `if`/`else` chains on one value.
- LF line endings in produced output (`"\n"`, never `System.lineSeparator()`); `Locale.ROOT` (or the module's
  pinned locale) for `lowercase`/`format` of machine-readable text.
- Coroutines vs Mutiny: one per service (`reactive-mutiny.md`).

### KDoc

Every public and internal declaration (classes, functions, properties, constructor parameters via `@property`/
`@param`), plus private members whose purpose isn't obvious: summary sentence, `@param`, `@return` (non-`Unit`),
`@throws` for every exception a caller can handle. Module/package docs (`module.md`) where the build wires Dokka.
The group skill audits this with `iru-kotlin-kdoc`.
