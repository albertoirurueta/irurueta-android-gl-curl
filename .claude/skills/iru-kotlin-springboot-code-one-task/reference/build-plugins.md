# Kotlin + Spring Boot build plugins

Read this **before writing any code** — the skill's Step 2 checks the build against it. Kotlin classes and their
members are `final` by default and have no no-arg constructor; Spring subclasses beans to proxy them, and Hibernate
subclasses entities to lazy-load them and instantiates them reflectively. Without the right compiler plugins the
code compiles fine and then fails at runtime — or worse, doesn't fail: a `final` `@Transactional` method on a
CGLIB-proxied bean is silently called **without** a transaction.

## The required set

| Piece | Why | Needed when |
|---|---|---|
| `kotlin-spring` (all-open preset) | Opens classes annotated `@Component` (and therefore `@Service`/`@Repository`/`@Controller`/`@Configuration`), `@Transactional`, `@Async`, `@Cacheable`, `@SpringBootTest`, plus their members, so CGLIB can proxy them. | Always, in every module that declares a Spring bean. |
| `kotlin-jpa` (no-arg preset) | Generates a synthetic no-arg constructor for `@Entity`, `@MappedSuperclass`, `@Embeddable`, so Hibernate can instantiate them. | The first JPA entity/embeddable/mapped superclass in a module. |
| `all-open` for `jakarta.persistence.Entity`/`MappedSuperclass`/`Embeddable` | Hibernate's lazy-loading proxies subclass the entity; a `final` entity can't be proxied, so `LAZY` `@ManyToOne` silently degrades to eager loading. | Same time as `kotlin-jpa`. Kotlin **2.3.20+**'s `jpa` preset opens these itself (verified: 2.3.10 leaves the entity `final`, 2.3.20 opens it) — declare it explicitly anyway: it's harmless on new compilers and required on older ones. |
| `-Xjsr305=strict` | Makes Spring's JSR-305 `@Nullable`/`@NonNull` (and JSpecify, on Spring 7) annotations real Kotlin nullability instead of platform types. | Always. |
| `kotlin-reflect` | Spring uses Kotlin reflection for constructor binding, default parameter values, nullability, and `@ConfigurationProperties`. | Always (runtime dependency). |
| `jackson-module-kotlin` | Lets Jackson construct data classes through their primary constructor and honour default values and nullability. Spring Boot auto-registers it when present. | Any Kotlin class (de)serialized as JSON. Spring Boot **4** (Jackson 3): `tools.jackson.module:jackson-module-kotlin`. Spring Boot **3** (Jackson 2): `com.fasterxml.jackson.module:jackson-module-kotlin`. Never hardcode a version — the Boot BOM manages it. |
| `kotlinx-coroutines-core` (+ `-reactor` for WebFlux/R2DBC) | `suspend` handlers and repositories. | Only when the task introduces coroutines — see `coroutines-and-webflux.md`. |

Versions come from the Spring Boot BOM (`kotlin.version`, `jackson-bom.version`, `kotlin-coroutines.version`) and,
in Gradle, the Kotlin plugin version must match the Kotlin version Boot manages unless the project deliberately
overrides it. Never put a `<version>` in a module pom when the parent/BOM manages it.

## How to check (Step 2 of the skill)

- **Maven**: in the module's effective configuration (its own `pom.xml`, then the parent/root `pom.xml`'s
  `<pluginManagement>`/`<plugins>`), find `kotlin-maven-plugin` and read `<compilerPlugins>`, `<pluginOptions>`,
  `<args>`, and the plugin's `<dependencies>` (`kotlin-maven-allopen`, `kotlin-maven-noarg`). `spring-boot-starter-parent`
  only pre-configures `jvmTarget`, `javaParameters`, and the compile/test-compile executions — **none** of the
  compiler plugins. `mvn -q help:effective-pom -pl <module>` resolves inheritance when in doubt.
- **Gradle**: the module's `build.gradle.kts`/`build.gradle`, the root `subprojects {}`/`allprojects {}` blocks,
  convention plugins in `buildSrc/` or `build-logic/`, and `gradle/libs.versions.toml` aliases
  (`kotlin-spring`, `kotlin-jpa`). Look for `kotlin("plugin.spring")` / `org.jetbrains.kotlin.plugin.spring`,
  `kotlin("plugin.jpa")`, an `allOpen {}` block, and `freeCompilerArgs`.

## Adding what's missing

Add a missing piece **only when the task needs it** — the first `@Entity` in a module needs `jpa` + `all-open`; a
module with no entities doesn't. Put it where the project already centralises build configuration (root pom
`<pluginManagement>`, a convention plugin, the version catalog) rather than duplicating it per module, and name the
change in the report: it affects every module that inherits it. A missing piece that isn't Kotlin/Spring baseline
plumbing (a new library, a coroutine dependency the task didn't ask for) is still a blocker to report, not
something to add quietly.

### Maven

```xml
<!-- Declared before maven-compiler-plugin. Versions/executions inherited from spring-boot-starter-parent. -->
<plugin>
  <groupId>org.jetbrains.kotlin</groupId>
  <artifactId>kotlin-maven-plugin</artifactId>
  <configuration>
    <args>
      <arg>-Xjsr305=strict</arg>
    </args>
    <compilerPlugins>
      <plugin>spring</plugin>
      <plugin>jpa</plugin>
      <plugin>all-open</plugin>
    </compilerPlugins>
    <pluginOptions>
      <option>all-open:annotation=jakarta.persistence.Entity</option>
      <option>all-open:annotation=jakarta.persistence.MappedSuperclass</option>
      <option>all-open:annotation=jakarta.persistence.Embeddable</option>
    </pluginOptions>
  </configuration>
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
</plugin>
```

```xml
<dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-reflect</artifactId></dependency>
<dependency><groupId>tools.jackson.module</groupId><artifactId>jackson-module-kotlin</artifactId></dependency>
<!-- Spring Boot 3: com.fasterxml.jackson.module:jackson-module-kotlin -->
```

A **pure-Kotlin** module may set `<sourceDirectory>src/main/kotlin</sourceDirectory>` and
`<testSourceDirectory>src/test/kotlin</testSourceDirectory>`. A **mixed Java + Kotlin** module must not — that
override silently drops `src/main/java` from `javac`, and the build passes until a Kotlin class touches a Java one
at runtime (`NoClassDefFoundError`). For mixed modules, list both roots in the Kotlin executions and move `javac`
after `kotlinc`:

```xml
<plugin>
  <groupId>org.jetbrains.kotlin</groupId>
  <artifactId>kotlin-maven-plugin</artifactId>
  <!-- configuration + dependencies as above -->
  <executions>
    <execution>
      <id>compile</id>
      <phase>compile</phase>
      <goals><goal>compile</goal></goals>
      <configuration>
        <sourceDirs>
          <sourceDir>${project.basedir}/src/main/kotlin</sourceDir>
          <sourceDir>${project.basedir}/src/main/java</sourceDir>
        </sourceDirs>
      </configuration>
    </execution>
    <execution>
      <id>test-compile</id>
      <phase>test-compile</phase>
      <goals><goal>test-compile</goal></goals>
      <configuration>
        <sourceDirs>
          <sourceDir>${project.basedir}/src/test/kotlin</sourceDir>
          <sourceDir>${project.basedir}/src/test/java</sourceDir>
        </sourceDirs>
      </configuration>
    </execution>
  </executions>
</plugin>
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <executions>
    <execution><id>default-compile</id><phase>none</phase></execution>
    <execution><id>default-testCompile</id><phase>none</phase></execution>
    <execution><id>java-compile</id><phase>compile</phase><goals><goal>compile</goal></goals></execution>
    <execution><id>java-test-compile</id><phase>test-compile</phase><goals><goal>testCompile</goal></goals></execution>
  </executions>
</plugin>
```

Declaring `kotlin-maven-plugin` before `maven-compiler-plugin` is **not enough** on its own: the lifecycle-bound
`default-compile` still runs first, and Java code referencing a Kotlin class fails with `cannot find symbol`
(verified). The `phase>none` re-binding above is what fixes it.

### Gradle (Kotlin DSL)

```kotlin
plugins {
    kotlin("jvm") version "<kotlin version Boot manages>"
    kotlin("plugin.spring") version "<same>"   // all-open preset for Spring
    kotlin("plugin.jpa") version "<same>"      // no-arg preset for JPA
    id("org.springframework.boot") version "<boot version>"
    id("io.spring.dependency-management") version "<version>"
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")   // Boot 3: com.fasterxml.jackson.module
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
```

Gradle needs no compile-order step for mixed sources: `compileKotlin` reads `src/main/java` and runs before
`compileJava` automatically. In a Groovy DSL build the same pieces are `id 'org.jetbrains.kotlin.plugin.spring'`,
`id 'org.jetbrains.kotlin.plugin.jpa'`, and `allOpen { annotation 'jakarta.persistence.Entity' }`. Plugins applied
through a version catalog look like `alias(libs.plugins.kotlin.spring)` — add the alias to
`gradle/libs.versions.toml` rather than a literal version in the module.

## Quick self-check after adding

`javap -p <classes dir>/<pkg>/<Entity>.class` should show `public class` (not `public final class`) and a
`public <Entity>()` constructor (the synthetic no-arg one is public in bytecode, invisible to Kotlin callers). The
classes dir is `target/classes` (Maven) or `build/classes/kotlin/main` (Gradle).
