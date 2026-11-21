---
name: iru-setup-java-springboot-pom
description: "Invoked as `/iru-setup-java-springboot-pom` (args: `stack-file`, `initializr-pom`, `mode`, `on-existing`), normally by `iru-setup-java-springboot`. Generates the multi-module build of a hexagonal Spring Boot service from the manifest: a Maven reactor (Java or Kotlin) or a Gradle Kotlin DSL build with convention plugins (Kotlin)."
model: sonnet
---

# Setup Java Spring Boot Poms

Generate the whole multi-module build for a hexagonal Spring Boot service. Which build depends on two manifest
fields, read in Step 0:

| `project.language` | `project.buildTool` | What this skill writes | Steps |
|---|---|---|---|
| `java` (or absent) | `maven` (or absent) | Maven reactor, Java toolchain (Lombok, MapStruct, Checkstyle/PMD/SpotBugs, Javadoc) | 0–8 as written |
| `kotlin` | `maven` | The same Maven reactor and module poms, with the Kotlin deltas of Step 1K (kotlin-maven-plugin, detekt, ktlint, Dokka) | 0–5, 1K, 6–8 |
| `kotlin` | `gradle` | A Gradle Kotlin DSL multi-project build (Step 5G) instead of any pom | 0, 2, 5G, 6–8 |

The Java path is the default and is unchanged: a manifest without these fields regenerates exactly the reactor
it always did. Two rules govern every path:

1. **Every version lives in one place.** On Maven that is the root `pom.xml`: module poms declare
   `<groupId>`/`<artifactId>` only, with no `<version>`, resolving through the root's `<dependencyManagement>` and
   `<pluginManagement>`. On Gradle it is `gradle/libs.versions.toml`: module build files and convention plugins
   reference catalog aliases only, and the Spring Boot BOM (plus any other BOM) is applied through `platform()`.
   A version anywhere else is a bug, not a shortcut.
2. **Starter and driver artifact names come from the Initializr reference build, never from memory.** They change
   across Boot major versions — in Boot 4.x `spring-boot-starter-web` became `spring-boot-starter-webmvc`, the
   Testcontainers modules became `testcontainers-<engine>`, Jackson moved to `tools.jackson.*`, and each starter
   gained a `spring-boot-starter-<x>-test` companion. Copy what the reference build actually says; the Maven
   coordinates are the same on Gradle.

## Templates

Every file template this skill writes is a file under `templates/<variant>/<path of the generated file>`, holding the exact bytes of that template; the steps below point at them. Read only the files for the variant resolved in Step 0 (`java`/`maven` and `kotlin`/`maven` use `templates/maven/` — Kotlin + Maven adds the fragments in `templates/kotlin-maven/` and `templates/kotlin/`; `kotlin`/`gradle` uses `templates/kotlin-gradle/` plus `templates/kotlin/`), each in full, before filling placeholders; never read the others:

| Generated file | Template | Applies when |
|---|---|---|
| `pom.xml` | `templates/maven/pom.xml` | Java + Maven (written as is); Kotlin + Maven (the base that Step 1K's fragments modify) |
| `pom.xml (Step 1K, Properties)` | `templates/kotlin-maven/pom.xml.properties` | Kotlin + Maven: properties added to the root pom |
| `pom.xml (Step 1K, Sonar properties)` | `templates/kotlin-maven/pom.xml.sonar-properties` | Kotlin + Maven, unless `sonar.mode: none`: added to the root pom's `sonar.*` block |
| `pom.xml (Step 1K, dependencyManagement)` | `templates/kotlin-maven/pom.xml.dependency-management` | Kotlin + Maven: test-scope entries added to the root `dependencyManagement` |
| `pom.xml (Step 1K, root dependencies)` | `templates/kotlin-maven/pom.xml.dependencies` | Kotlin + Maven: replaces the Java project-wide root `<dependencies>` |
| `pom.xml (Step 1K, Compilation)` | `templates/kotlin-maven/pom.xml.compilation` | Kotlin + Maven: build plugins inserted after the enforcer, replacing the `maven-compiler-plugin` entry |
| `pom.xml (Step 1K, Quality and documentation)` | `templates/kotlin-maven/pom.xml.quality` | Kotlin + Maven: detekt, ktlint and Dokka plugins in `<build><plugins>` in place of Checkstyle/PMD/SpotBugs |
| `pom.xml (Step 1K, reporting)` | `templates/kotlin-maven/pom.xml.reporting` | Kotlin + Maven: `<reporting>` plugin entry added to the site configuration |
| `<module>/pom.xml (every module pom)` | `templates/maven/module/pom.xml` | Java + Maven and Kotlin + Maven: every module pom |
| `domain/pom.xml (and the analogous banned-dependencies of the other modules)` | `templates/maven/domain/pom.xml.enforcer` | Java + Maven and Kotlin + Maven: the `banned-dependencies` enforcer `<build>` of `domain` |
| `coverage/pom.xml` | `templates/maven/coverage/pom.xml.body` | Java + Maven and Kotlin + Maven: the `coverage` aggregator module |
| `boot/pom.xml` | `templates/maven/boot/pom.xml.build` | Java + Maven and Kotlin + Maven: the `boot` module's executable-artifact and build-info `<build>` |
| `spotbugs-exclude.xml` | `templates/maven/spotbugs-exclude.xml` | Java + Maven only (the template lines carry the 2-space indent of the list item they sit in; the generated file has no such leading indent) |
| `detekt.yml` | `templates/kotlin/detekt.yml` | Kotlin (Maven and Gradle) (the template lines carry the 2-space indent of the list item they sit in; the generated file has no such leading indent) |
| `.editorconfig` | `templates/kotlin/.editorconfig` | Kotlin (Maven and Gradle) (the template lines carry the 2-space indent of the list item they sit in; the generated file has no such leading indent) |
| `settings.gradle.kts` | `templates/kotlin-gradle/settings.gradle.kts` | Kotlin + Gradle |
| `gradle.properties` | `templates/kotlin-gradle/gradle.properties` | Kotlin + Gradle |
| `gradle/libs.versions.toml` | `templates/kotlin-gradle/gradle/libs.versions.toml` | Kotlin + Gradle |
| `build-logic/settings.gradle.kts` | `templates/kotlin-gradle/build-logic/settings.gradle.kts` | Kotlin + Gradle |
| `build-logic/build.gradle.kts` | `templates/kotlin-gradle/build-logic/build.gradle.kts` | Kotlin + Gradle |
| `build-logic/src/main/kotlin/iru.kotlin-library.gradle.kts` | `templates/kotlin-gradle/build-logic/src/main/kotlin/iru.kotlin-library.gradle.kts` | Kotlin + Gradle |
| `build-logic/src/main/kotlin/iru.spring-boot-app.gradle.kts` | `templates/kotlin-gradle/build-logic/src/main/kotlin/iru.spring-boot-app.gradle.kts` | Kotlin + Gradle |
| `build-logic/src/main/kotlin/iru.quality.gradle.kts` | `templates/kotlin-gradle/build-logic/src/main/kotlin/iru.quality.gradle.kts` | Kotlin + Gradle |
| `build-logic/src/main/kotlin/iru.integration-test.gradle.kts` | `templates/kotlin-gradle/build-logic/src/main/kotlin/iru.integration-test.gradle.kts` | Kotlin + Gradle |
| `build-logic/src/main/kotlin/iru.coverage.gradle.kts` | `templates/kotlin-gradle/build-logic/src/main/kotlin/iru.coverage.gradle.kts` | Kotlin + Gradle |
| `build.gradle.kts` | `templates/kotlin-gradle/build.gradle.kts` | Kotlin + Gradle |
| `domain/build.gradle.kts` | `templates/kotlin-gradle/domain/build.gradle.kts` | Kotlin + Gradle |
| `application/build.gradle.kts` | `templates/kotlin-gradle/application/build.gradle.kts` | Kotlin + Gradle |
| `infrastructure/database/<engine>/build.gradle.kts (ENGINE in the template path stands for `<engine>`)` | `templates/kotlin-gradle/infrastructure/database/ENGINE/build.gradle.kts` | Kotlin + Gradle |
| `boot/build.gradle.kts` | `templates/kotlin-gradle/boot/build.gradle.kts` | Kotlin + Gradle |
| `coverage/build.gradle.kts` | `templates/kotlin-gradle/coverage/build.gradle.kts` | Kotlin + Gradle |

## Step 0 — Resolve inputs

Parse `args` for `key: value` lines:

```
stack-file: springboot-stack.yml
initializr-pom: /path/to/scratchpad/initializr-pom.xml
mode: existing
on-existing: ask
```

- **`stack-file`** — defaults to `springboot-stack.yml` at the repository root. Read it in full; it carries the
  project identity, the concurrency model, every technology choice, the top-level `sonar:` block
  (`mode: cloud | self-hosted | none`, plus `organization`/`projectKey`/`hostUrl`), and `frontend`
  (`none`/`hilla`). If it doesn't exist, stop and tell the user to run `/iru-setup-java-springboot` first — this
  skill deliberately doesn't re-interview. `sonar.mode` decides the four `sonar.*` properties and the
  `sonar-maven-plugin` entry below: `cloud` keeps today's `https://sonarcloud.io` default, `self-hosted`
  substitutes the manifest's `sonar.hostUrl`, and `none` drops all of it. A manifest written before this field
  existed (no `sonar:` key at all) is treated as `cloud` for backward compatibility, so an older
  `springboot-stack.yml` regenerates the same pom it always did. `frontend: hilla` adds the Vaadin/Hilla version
  management described in Step 1's "Frontend (Hilla) version management" note below; `frontend: none` (or the key
  absent, for the same backward-compatibility reason) omits all of it.
- **`project.language` / `project.buildTool` / `project.kotlinVersion`** — from the manifest's `project:` block.
  Absent `language` means `java`, absent `buildTool` means `maven` (manifests written before these fields existed).
  `buildTool: gradle` with `language: java` is invalid — Java services are Maven-only in this catalog — so stop and
  tell the user to fix the manifest via `/iru-setup-java-springboot` rather than guessing. For Kotlin, use
  `kotlinVersion` exactly as recorded: `iru-setup-java-springboot` resolves it to the Kotlin version the chosen
  Spring Boot release manages (`kotlin.version` in `spring-boot-dependencies`, e.g. `2.3.21` for Boot `4.1.1`) —
  never substitute "the latest Kotlin" (a newer Kotlin than Boot manages is untested by Boot). Only if the field is
  missing, read it from `https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/<boot-version>/spring-boot-dependencies-<boot-version>.pom`
  and say so in Step 8.
- **`initializr-pom`** — the reference build. For Java and Kotlin + Maven it is a pom; for Kotlin + Gradle the
  orchestrator passes the starter's `build.gradle.kts` (extracted from `/starter.zip`) under the same key — read
  its `dependencies { }` block the same way (coordinates are identical; Gradle only drops the `<scope>` in favour
  of `implementation`/`runtimeOnly`/`testImplementation`). If it wasn't supplied, fetch it now yourself from the
  ids in the manifest's `initializr.dependencies` — for Kotlin add `-d language=kotlin` so the reference carries
  `kotlin-reflect`, `kotlin-test-junit5` and Initializr's current Kotlin compiler arguments:

  ```bash
  curl -sG https://start.spring.io/pom.xml \
    --data-urlencode 'bootVersion=<project.springBootVersion>' \
    -d type=maven-build -d javaVersion=<project.javaVersion> \
    -d groupId=<project.groupId> -d artifactId=<project.artifactId> \
    -d packageName=<project.basePackage> \
    -d 'dependencies=<comma-separated ids>' -o <scratchpad>/initializr-pom.xml
  ```

  Confirm the response is XML, not a plain-text error, before using it. For Kotlin + Gradle fetch the starter
  instead (the `/build.gradle.kts` endpoint does not exist) and unzip it into the scratchpad — it also carries a
  Gradle wrapper Step 5G may reuse:

  ```bash
  curl -sG https://start.spring.io/starter.zip -d type=gradle-project-kotlin -d language=kotlin \
    --data-urlencode 'bootVersion=<project.springBootVersion>' -d javaVersion=<project.javaVersion> \
    -d groupId=<project.groupId> -d artifactId=<project.artifactId> -d packageName=<project.basePackage> \
    -d 'dependencies=<comma-separated ids>' -o <scratchpad>/starter.zip
  unzip -q -o <scratchpad>/starter.zip -d <scratchpad>/initializr-starter
  ```
- **`mode`** (`new`/`existing`) — informational; scratch-vs-update is still decided per file from what's on disk,
  and `mode: existing` does not mean "update silently".
- **`on-existing`** (`ask`/`keep`/`update`/`replace`, default `ask`) — applied to every pre-existing file below
  without asking (the warning is still printed).

From the reference pom, extract into a working table you'll consult for every module below:

- every starter/driver `groupId:artifactId` and its scope (compile / runtime / test / optional);
- every `<dependencyManagement>` BOM import plus its version property (e.g. `spring-cloud.version`,
  `spring-ai.version`) — these go into the root pom verbatim;
- any build plugin Initializr added and whether the Boot parent already manages its version;
- the `annotationProcessorPaths` entries (Java only — Kotlin uses no annotation processors here: no Lombok, no
  MapStruct, no kapt; drop `lombok` and `spring-boot-configuration-processor` from a Kotlin reference, and mapping
  is hand-written extension functions generated by `iru-setup-java-springboot-modules`).

Then classify every extracted dependency to the module that should own it, using the table in Step 2. A dependency
you can't confidently place goes in the `boot` module (which depends on everything and where a misplacement is
harmless) and gets reported as unplaced in Step 8 — never drop it silently.

Say whether this run is **generating the build from scratch** or **updating an existing one**, listing which of
the files this skill owns already exist. Those are, per path:

- **Java + Maven**: the root pom, the module poms named in `modules:`, `checkstyle.xml`, `spotbugs-exclude.xml`.
- **Kotlin + Maven**: the root pom, the module poms, `detekt.yml`, `.editorconfig`.
- **Kotlin + Gradle**: `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, the root
  `build.gradle.kts`, `build-logic/` (its `settings.gradle.kts`, `build.gradle.kts` and the five convention
  plugins), each module's `build.gradle.kts` (asked as one grouped question, like the module poms), the Gradle
  wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`), `detekt.yml`, `.editorconfig`. A `pom.xml` at the root of
  a repository whose manifest says `buildTool: gradle` (or a Gradle build where the manifest says `maven`) is a
  build-tool switch, not an update — say so and offer Stop (recommended) or Replace (writes the new build; the
  old one is left on disk for the user to delete once satisfied, never deleted by this skill).

For each one that exists, warn and — unless `on-existing` says otherwise —
ask with `AskUserQuestion` (up to 4 files per call, one question each; the module poms may be asked as one
grouped question, "all existing module poms", with a per-module override if the user wants one). Say concretely
where an existing file disagrees with the manifest (e.g. "your root pom has `sonar-maven-plugin` but
`sonar.mode: none`", "it lists `api/grpc-server` but the manifest has no gRPC server"):

- **Update (Recommended)** — merge: add what's missing and correct what this skill owns (versions, managed
  BOMs, plugin configuration, the `sonar.*`/Vaadin wiring per the manifest, `<modules>`) using the templates
  below; keep every dependency, plugin, profile, and property the existing pom has that the template doesn't, and
  list those in Step 8 so the user can confirm each one survived. A customized part this skill owns may be
  rewritten.
- **Keep as is** — leave the file untouched. Customizations survive, but it may stay out of date or disagree with
  the manifest — Step 8 lists each mismatch. Recommend this one instead of Update for `checkstyle.xml` and
  `spotbugs-exclude.xml`, which are the project's own rulesets.
- **Replace** — regenerate from the template; every customization in the file is lost (recoverable only from git
  history).
- **Stop** — end this skill now without writing anything (offered only on the root pom question). An existing
  root pom that isn't `pom`-packaged (a single-module project) can't simply be updated into a reactor — say so,
  and recommend Stop there instead of Update.

If the root pom ends up kept as is (or the run stops), say so prominently in Step 8: `iru-setup-java-springboot`
needs a usable reactor root to continue.

On Gradle the same four answers apply file by file; **Stop** is offered on the `settings.gradle.kts` question
(the Gradle equivalent of the root pom), and **Update** means: add missing `include(...)` lines, catalog entries,
convention-plugin blocks and module dependencies, correct versions and the Sonar/Dokka wiring per the manifest,
and keep every hand-added plugin, dependency, task and catalog entry (listed in Step 8). For `detekt.yml` and
`.editorconfig` recommend **Keep as is**, like `checkstyle.xml` — they are the project's own rulesets.

## Step 1 — Root `pom.xml`

Java + Maven writes this template as is; Kotlin + Maven starts from it and then applies Step 1K; Kotlin + Gradle
writes no pom at all — read Step 2 for placement, then go to Step 5G.

Substitute `<placeholders>` from the manifest. Include only the `<module>` entries for modules the manifest's
`modules:` list actually names.

Template: `templates/maven/pom.xml` — read it in full before filling placeholders

Notes on filling this in:

- `${lombok.version}` and `${spring-boot.version}` are provided by `spring-boot-starter-parent` — don't redeclare
  them. `${maven.multiModuleProjectDirectory}` resolves to the reactor root from any module, which is what makes a
  single shared `checkstyle.xml` and `spotbugs-exclude.xml` work.
- Read `sonar.mode` from the manifest (defaulting to `cloud` when the field is absent, for backward compatibility
  with a manifest written before the `sonar:` block existed):
  - `sonar.mode: none` — drop the whole `sonar.*` properties block (all five properties, not just the identity
    ones) and the `sonar-maven-plugin` entry rather than leaving empty placeholders.
  - `sonar.mode: cloud` — keep today's behaviour: `<sonar.host.url>https://sonarcloud.io</sonar.host.url>`, and
    `<sonar.organization>`/`<sonar.projectKey>` from the manifest's `sonar.organization`/`sonar.projectKey`
    (falling back to `<owner>-github`/`<owner>_<repo>` if the manifest left them unset).
  - `sonar.mode: self-hosted` — write the manifest's `sonar.hostUrl` into `<sonar.host.url>` instead of
    `https://sonarcloud.io`, and `sonar.projectKey` into `<sonar.projectKey>` as usual. **Judgment call**: a
    self-hosted SonarQube instance typically has organizations disabled (that concept is SonarCloud/SonarQube
    Server-multi-tenant specific), so omit the `<sonar.organization>` property unless the manifest actually set
    `sonar.organization` (i.e. the user confirmed that server has organizations enabled in
    `iru-setup-java-springboot`'s Step 3b) — don't emit an empty or guessed value.
  - The manifest's `ci:` block doesn't change any of this: with `ci.enabled: false` and a `sonar.mode` other than
    `none` (the user kept Sonar despite having no CI), still write the properties and plugin exactly as above —
    they're what a manual `mvn sonar:sonar -Dsonar.token=…` run needs — and add an XML comment above the
    `sonar.*` block saying no CI workflow sends reports. `ci.tests: unit` only affects coverage content (the
    aggregate then holds unit-test data in CI), not the report paths.
- Look up the current version for every `<latest>` marker (Maven Central's search API) rather than guessing —
  and record what you resolved, so Step 8 can report it.
- Also write `spotbugs-exclude.xml` at the repository root, excluding the generated-source packages (the
  OpenAPI/protobuf/AVRO output) — SpotBugs analyses bytecode, so `excludeRoots` doesn't help it the way it helps
  PMD, and generated DTOs will otherwise produce a large permanent finding list.
- Read `frontend` from the manifest (absent or `none` means no Hilla wiring, same backward-compatibility rule as
  `sonar.mode`): only when it's `hilla` do the `<vaadin.version>`/`<vaadin.skip>` properties, the `vaadin-bom`
  `dependencyManagement` import, the `vaadin-maven-plugin` `pluginManagement` entry, and the `production` profile
  belong in the pom — omit all four, property for property, when it's `none`. Resolve `<vaadin.version>` from
  `https://repo1.maven.org/maven2/com/vaadin/vaadin-bom/maven-metadata.xml`'s `<versions>` list: use the newest
  entry with no pre-release suffix (`-alpha`/`-beta`/`-rc`), since that metadata's own `<release>`/`<latest>`
  tags can point at a release candidate. This skill owns writing these four root-pom pieces; if it's run again
  after `iru-setup-java-springboot-hilla` already filled them in as a standalone-run fallback (see that skill's
  Step 1), treat the existing values as correct and don't regenerate them from scratch unless they're actually
  wrong. The `production` profile's `vaadin.productionMode=true` and the plugin's own `vaadin.skip` property were
  both confirmed against the plugin's own Maven metadata (`<skip implementation="boolean"
  default-value="false">${vaadin.skip}</skip>`) rather than assumed from documentation.

## Step 1K — Kotlin on Maven: deltas to the root pom

Only when `project.language: kotlin` and `project.buildTool: maven`. Start from Step 1's template and apply the
changes below; everything not mentioned (parent, identity, `<modules>`, BOM imports, codegen `pluginManagement`,
enforcer, Surefire/Failsafe, JaCoCo executions, site, Sonar host/identity, the Hilla pieces) stays exactly as in
Step 1. Every snippet here was built in a throwaway 3-module reactor (`domain`, `application`, `boot` with mixed
Java + Kotlin sources, `coverage`) on Boot 4.1.1 / Kotlin 2.3.21 / JDK 21: `mvn verify` ran unit tests, a
`*IT` through Failsafe, detekt, ktlint and the aggregate report (Kotlin and Java classes, unit + IT data);
`mvn detekt:check ktlint:check` and `mvn compile dokka:dokka` ran standalone; detekt and ktlint each failed the
build on a planted violation.

**Remove** (they have no Kotlin meaning, or are replaced below): the root `<dependencies>` entries for `lombok`,
`mapstruct` and `mockito-junit-jupiter`; the `mapstruct.version` and `lombok-mapstruct-binding.version`
properties and the MapStruct `dependencyManagement` entry; the whole `annotationProcessorPaths`/`compilerArgs`
configuration of `maven-compiler-plugin` (replaced below); the Checkstyle, PMD and SpotBugs plugins in both
`<build>` and `<reporting>`, `maven-javadoc-plugin` in `<reporting>`, and their four version properties; the
`**/*MapperImpl.class` JaCoCo exclude.

**Properties** — add, next to the other plugin/library versions:

Template: `templates/kotlin-maven/pom.xml.properties` — read it in full before filling placeholders

Resolve each `<latest>` from Maven Central's `maven-metadata.xml` (newest version without an
`-alpha`/`-beta`/`-RC`/`-M` suffix): `io/mockk/mockk-jvm`, `com/ninja-squad/springmockk`,
`com/github/ozsie/detekt-maven-plugin`, `com/github/gantsign/maven/ktlint-maven-plugin`,
`org/jetbrains/dokka/dokka-maven-plugin`. As of this writing: 1.14.11, 5.0.1, 1.23.8, 3.7.1, 2.2.0. (detekt
1.23.x embeds its own Kotlin 2.0 compiler to parse sources; it analyses Kotlin 2.3 code fine and ran on JDK 21
and 26. detekt 2.x is still alpha and has no Maven plugin release — don't use it.)

Unless `sonar.mode: none`, add to the `sonar.*` block (all relative paths resolve per module):

Template: `templates/kotlin-maven/pom.xml.sonar-properties` — read it in full before filling placeholders

`sonar.coverage.jacoco.xmlReportPaths` and `sonar.exclusions` stay as in Step 1.

**`dependencyManagement`** — add (scope `test`, like ArchUnit):

Template: `templates/kotlin-maven/pom.xml.dependency-management` — read it in full before filling placeholders

**Root `<dependencies>`** — replace the Java project-wide set with (versions all come from the Boot BOM or the
entries above; nothing Spring-specific, since `domain` inherits it too):

Template: `templates/kotlin-maven/pom.xml.dependencies` — read it in full before filling placeholders

`jackson-module-kotlin` is **not** project-wide — `domain` must stay free of Jackson; Step 2's Kotlin note places
it. Initializr's Kotlin pom lists `lombok` and `spring-boot-configuration-processor` when the Java defaults asked
for them; drop both (no kapt in this build).

**Compilation** — insert, in `<build><plugins>` right after the enforcer, replacing Step 1's
`maven-compiler-plugin` entry. `spring-boot-starter-parent` already manages the `kotlin-maven-plugin` version
and pre-configures `jvmTarget`/`javaParameters` and the two executions — but none of the compiler plugins:

Template: `templates/kotlin-maven/pom.xml.compilation` — read it in full before filling placeholders

Don't set `<sourceDirectory>src/main/kotlin</sourceDirectory>` the way a pure-Kotlin Initializr pom does: it
silently drops `src/main/java` from `javac`, and the mixed-source modules this catalog allows (generated Java
DTOs, a Java adapter) then fail at runtime. Surefire's `**/*Test.java` / Failsafe's `**/*IT.java` patterns stay
as they are — both plugins match them against compiled classes, so Kotlin `*Test`/`*IT` classes run (verified).
JaCoCo's `<excludes>` gains `<exclude>**/generated/**</exclude>` (generated Kotlin/Java packages wherever the
generators put them) and `<exclude>**/*ApplicationKt.class</exclude>` (the file class holding `main`).

**Generated code is never analysed.** Everything the OpenAPI/protobuf/AVRO/GraphQL generators write lands under
`target/generated-sources/**` in packages containing a `generated` segment; the templates below keep it out of
every tool: detekt's `<input>` and ktlint's source roots list only `src/*/kotlin` (plus detekt's
`**/generated/**` exclude), Dokka's `sourceDirectories` lists only `src/main/kotlin`, JaCoCo excludes
`**/generated/**`, and Sonar's `sonar.exclusions` (Step 1) already covers `**/generated-sources/**,**/generated/**`.

**Quality and documentation** — insert in `<build><plugins>` where Step 1 had Checkstyle/PMD/SpotBugs. All three
must be in `<build><plugins>` (not only `pluginManagement`, and not in a profile): the generated CI calls
`mvn detekt:check ktlint:check dokka:dokka` by prefix, and the Dokka Maven plugin additionally refuses to run
(`Not found dokka plugin`) when it is only declared in `pluginManagement` (verified).

Template: `templates/kotlin-maven/pom.xml.quality` — read it in full before filling placeholders

**`<reporting>`** — the site keeps Surefire/Failsafe, JaCoCo and JXR exactly as in Step 1, drops Javadoc,
Checkstyle, PMD and SpotBugs, and adds the ktlint report (the plugin is also a Maven reporting plugin); detekt and
Dokka have no site report, so the site links nothing for them and the CI publishes `target/dokka` directly:

Template: `templates/kotlin-maven/pom.xml.reporting` — read it in full before filling placeholders

## Step 2 — Where each dependency belongs

Use this to place every artifact extracted in Step 0. The point is that a dependency lands in exactly one module,
so the hexagonal boundaries are enforced by the classpath and not merely by convention.

| Module | Owns these dependencies |
|---|---|
| `domain` | Nothing Spring, nothing infrastructural. Plain Java plus `jakarta.validation-api` if the domain uses constraint annotations, plus the shared Lombok/MapStruct/test dependencies inherited from the root. This module defines the **ports** (interfaces) — repositories, event publishers, clients, metrics — and knows no implementation. |
| `application` | `<a>-domain`. Optionally `spring-context`/`spring-tx` if use cases need `@Service`/`@Transactional`; prefer keeping even this out and wiring in `boot` when practical. |
| `infrastructure/database/<engine>` | `<a>-domain` plus that engine's Spring Data starter and driver, plus its migration tooling (Flamingock, Liquibase + the relevant extension, or Flyway) and its Testcontainers module (`test` scope). One engine per module — never two. |
| `infrastructure/configuration` | `<a>-domain`, `spring-boot-starter` (for `@ConfigurationProperties`), `spring-boot-configuration-processor`, and the dynamic-configuration starter the manifest names (Spring Cloud Kubernetes Config, AWS Secrets Manager, or Spring Cloud Config Client + Bus). |
| `infrastructure/clients/<name>` | `<a>-domain` plus the HTTP client starter (`spring-restclient`/`spring-webclient`) for a REST client, the gRPC client starter for a gRPC one, or `spring-boot-starter-graphql` (for `HttpGraphQlClient`/`HttpSyncGraphQlClient`) **and** `io.github.kobylynskyi:graphql-java-codegen` at compile scope for a GraphQL one — that last artifact supplies the base classes the generated request/projection classes extend, and without it the module generates and then fails to compile. A downstream service reached over more than one protocol keeps one module with one generator execution per protocol. Also the OpenAPI, protobuf or GraphQL generator plugin execution for that client's own contract, and — at `test` scope — the Testcontainers module for whichever API mock `stack.apiMock` names (`io.github.microcks:microcks-testcontainers` or the WireMock Testcontainers module), so this module's own `*IT` can start just the mock instead of the whole compose stack. |
| `infrastructure/producers` | `<a>-domain`, `spring-cloud-stream`, `spring-cloud-stream-binder-kafka`, the AVRO serializer (`io.confluent:kafka-avro-serializer`) and `org.apache.avro:avro`, plus the AVRO generator plugin execution. |
| `infrastructure/metrics` | `<a>-domain`, `spring-boot-starter-actuator`, `micrometer-registry-prometheus` (and `micrometer-registry-otlp` if selected). |
| `api/rest-server` | `<a>-application`, `<a>-domain`, `spring-boot-starter-webmvc`/`-webflux` (whatever the reference pom names), `spring-boot-starter-validation`, plus the OpenAPI generator execution for `apis/rest-server/`. Add the Spring Security starters here if the service authorizes incoming HTTP. |
| `api/grpc-server` | `<a>-application`, `<a>-domain`, the gRPC server starter, plus the protobuf generator execution for `apis/grpc-server/`. |
| `api/graphql-server` | `<a>-application`, `<a>-domain`, `spring-boot-starter-graphql`, **and** the web transport starter the reference pom names for `stack.concurrency` (`spring-boot-starter-webmvc`/`-web` or `-webflux`) — the GraphQL starter carries no HTTP transport of its own — plus `spring-boot-starter-validation`, `spring-boot-starter-graphql-test` at `test` scope, the `graphql-codegen-maven-plugin` execution for `apis/graphql-server/`, the `maven-resources-plugin` execution that copies the SDL to `target/classes/graphql/` for runtime schema loading, and the `frontend-maven-plugin` executions that generate the SpectaQL reference (and optional Voyager schema graph) into `target/generated-docs/graphql-server/`. |
| `api/consumers` | `<a>-application`, `<a>-domain`, `spring-cloud-stream`, the Kafka binder, the AVRO serializer, plus the AVRO generator execution. |
| `boot` | Every other module, `spring-boot-starter`, the Spring AI starters if any, and anything Step 0 couldn't place. This is the only module with `spring-boot-maven-plugin` and the only one that produces an executable artifact. |
| `coverage` | Every other module at `test` scope, for JaCoCo `report-aggregate` only. No sources of its own. |

`spring-boot-docker-compose` (runtime, optional) belongs in `boot` — it starts the local compose stack
automatically during development.

**Kotlin (Maven and Gradle)** — the same table, with these additions (scopes map to Gradle configurations as
compile → `implementation`, runtime → `runtimeOnly`, test → `testImplementation`, optional → `compileOnly`):

- `tools.jackson.module:jackson-module-kotlin` (Boot 4 / Jackson 3; `com.fasterxml.jackson.module` on Boot 3) in
  `boot` and in every module that (de)serializes JSON itself — `api/rest-server`, `api/graphql-server`, each REST
  or GraphQL `infrastructure/clients/<name>`, `infrastructure/producers`, `api/consumers`. Never in `domain`.
- `org.jetbrains.kotlinx:kotlinx-coroutines-reactor` (Boot-managed) in every module that bridges Reactor types —
  WebFlux `api/*` modules, reactive Spring Data adapters, WebClient clients — when `stack.concurrency` is
  `reactive` or `both`. (`kotlinx-coroutines-core`/`-test` are project-wide in that case, so `domain` has them.)
- `org.springframework.cloud:spring-cloud-context` (Spring Cloud BOM) in `infrastructure/configuration` whenever
  `stack.dynamicConfig` isn't `none` — `@RefreshScope` lives there.
- `com.ninja-squad:springmockk` (test) in every module with Spring slice or `@SpringBootTest` tests — at least
  `boot` and the `api/*` modules; `com.tngtech.archunit:archunit-junit5` (test) in `boot`, which holds the ArchUnit
  test — on Gradle that test is the **only** boundary check.
- `org.springframework.boot:spring-boot-testcontainers`, `org.testcontainers:testcontainers-junit-jupiter` and the
  engine's `org.testcontainers:testcontainers-<engine>` (Testcontainers 2.x ids, from the reference build) at
  test scope in every module that holds `*IT` tests — each `infrastructure/database/<engine>`, the `api/*`
  modules, `boot`.
- No `lombok`, `mapstruct`, `spring-boot-configuration-processor` anywhere.

## Step 3 — Module pom template

Every module pom follows this shape. Note the complete absence of `<version>` on dependencies.

Template: `templates/maven/module/pom.xml` — read it in full before filling placeholders

- `<relativePath>` is `../pom.xml` for a top-level module, `../../pom.xml` for an `infrastructure/*` or `api/*`
  module, `../../../pom.xml` for an `infrastructure/database/<engine>` or `infrastructure/clients/<name>` module.
- The intermediate aggregators (`infrastructure/pom.xml`, `infrastructure/database/pom.xml`,
  `infrastructure/clients/pom.xml`, `api/pom.xml`) use `<packaging>pom</packaging>`, carry `<modules>` and no
  `<dependencies>`, and are pure grouping.
- **Kotlin + Maven**: module poms are identical — the Kotlin compiler, detekt, ktlint, Dokka, JaCoCo and the
  enforcer all come from the root. The one per-module addition: a jar module that also has Java sources
  (`src/main/java` exists — e.g. a hand-written Java adapter) overrides the root's Sonar source lists, unless
  `sonar.mode: none`:

  ```xml
  <properties>
    <sonar.sources>src/main/kotlin,src/main/java</sonar.sources>
    <sonar.tests>src/test/kotlin</sonar.tests> <!-- add ,src/test/java only if that directory exists -->
  </properties>
  ```

  Generated Java (OpenAPI/protobuf/AVRO output under `target/generated-sources`) needs no override — it is
  compiled through the generator plugins' own source roots and excluded from analysis anyway.

### Enforcing the dependency direction

Add a `banned-dependencies` enforcer execution to each module so a violation of the hexagon fails the build
instead of being caught in review. In `domain/pom.xml`:

Template: `templates/maven/domain/pom.xml.enforcer` — read it in full before filling placeholders

Apply the same idea to the others, banning what each must not see:

- `application` — must not see any `infrastructure-*` or `api-*` module.
- `infrastructure/*` — must not see `application`, `api-*`, or a sibling `infrastructure-*` module.
- `api/*` — must not see any `infrastructure-*` module.
- `boot` — nothing banned; it is the composition root.

Note in Step 8 that banning `org.springframework:*` in `domain` is a deliberately strict DDD stance and the one
rule a team may reasonably want to relax (e.g. to use `spring-context` annotations in domain services); relaxing
it is a one-line change, whereas noticing the leak later is not.

### `coverage/pom.xml`

Template: `templates/maven/coverage/pom.xml.body` — read it in full before filling placeholders

`coverage` must be the **last** `<module>` in the root pom so every other module's `jacoco.exec` already exists
when `report-aggregate` runs. `dataFileIncludes` picks up both the unit (`jacoco.exec`) and integration
(`jacoco-it.exec`) execution data, so the aggregate — which is what Sonar reads — reflects both.

### `boot/pom.xml`

Additionally carries the executable-artifact and build-info plugins:

Template: `templates/maven/boot/pom.xml.build` — read it in full before filling placeholders

`<base-package-path>` is the base package with dots replaced by slashes. The GitHub Actions branch is listed
first because that's what this catalog's generated workflows actually run under; the Jenkins/GitLab fallbacks are
kept from the `iru-setup-java-library` template so the same file works in a mirrored pipeline.

## Step 4 — Write the poms

Write the root pom, every aggregator pom, and every module pom named in the manifest's `modules:` list. Create the
directories as needed. Don't create source files here — that's `iru-setup-java-springboot-modules`; a pom for a
module with no sources yet is valid and builds cleanly.

**Kotlin + Maven**, unless `sonar.mode: none`: also create `src/main/kotlin/` and `src/test/kotlin/` in every
`jar` module, each with an empty `.gitkeep` so the directory survives a fresh clone — the explicit `sonar.sources`
/`sonar.tests` from Step 1K make `mvn sonar:sonar` fail with "The directory … does not exist" for a jar module
missing either one (the scanner only tolerates missing paths in `pom` modules). That is the only non-build file
this step writes; sources still belong to `iru-setup-java-springboot-modules`.

## Step 5 — Write the shared analysis configuration

Java writes `checkstyle.xml` and `spotbugs-exclude.xml`; Kotlin (Maven or Gradle) writes `detekt.yml` and
`.editorconfig` instead (last bullet).

- **`checkstyle.xml`** at the repository root, if absent (an existing one follows Step 0's answer — Keep by
  default). Start from Sun or Google checks with the rules a Spring
  Boot service realistically can't satisfy relaxed (line length raised to 120, `JavadocPackage`/`HideUtilityClassConstructor`
  dropped, `FinalParameters` dropped). A ruleset the build immediately fails on gets disabled by the first
  developer who hits it, which is worse than a lenient one that stays enabled.
- **`spotbugs-exclude.xml`** at the repository root, if absent (an existing one follows Step 0's answer — Keep by
  default; on Update, add the generated-source matches below if missing), excluding the generated-source packages:

  Template: `templates/maven/spotbugs-exclude.xml` — read it in full before filling placeholders

- **Kotlin (Maven and Gradle)**: write these two instead of `checkstyle.xml`/`spotbugs-exclude.xml` — both if
  absent, an existing one following Step 0's answer (Keep by default; on Update add only missing keys).

  `detekt.yml` at the repository root, layered over detekt's defaults (`buildUponDefaultConfig`), relaxing only
  what a Spring service can't reasonably satisfy — the same philosophy as the lenient `checkstyle.xml`:

  Template: `templates/kotlin/detekt.yml` — read it in full before filling placeholders

  (Verified: with this file the `SpreadOperator` finding on the Boot `main` is gone, and a planted magic number
  still fails the build.)

  `.editorconfig` at the repository root — ktlint reads its rules from here (both the Maven plugin and the Gradle
  plugin), and IntelliJ formats to the same rules:

  Template: `templates/kotlin/.editorconfig` — read it in full before filling placeholders

  An existing `.editorconfig` with other sections: on Update add only the `[*.{kt,kts}]` section if it is missing.

## Step 5G — Kotlin on Gradle: the Gradle Kotlin DSL build

Only when `project.language: kotlin` and `project.buildTool: gradle`. This replaces Steps 1, 1K, 3 and 4 — no pom
is written — and is this skill's "every module pom" responsibility in Gradle form. The module directories, the
placement of dependencies (Step 2, including its Kotlin note) and the analysis configuration (Step 5) are the same
as on Maven. Every template below was built in a throwaway `domain`/`application`/`boot` (mixed Java + Kotlin) +
`coverage` project on Gradle 9.7.1 / Boot 4.1.1 / Kotlin 2.3.21 / JDK 21 (Docker `gradle:8-jdk21`, running the
wrapper): `build -x test -x integrationTest`, `test` (the `*IT` excluded), `integrationTest` (only the `*IT`),
`:coverage:testCodeCoverageReport` (unit + integration data, Kotlin and Java classes), `detekt ktlintCheck`
(each failing on a planted violation, and ktlint also checking the `*.gradle.kts` files themselves),
`dokkaGeneratePublicationHtml` (aggregated into `build/dokka/html`), `:boot:bootJar` with `build-info.properties`
inside, and the task list containing `sonar` and `:boot:bootBuildImage`. The `sonar` task itself could not be run
without a Sonar server — its properties are unverified against a live analysis.

Write these files (every one is covered by Step 0's existing-file question):

```
settings.gradle.kts
gradle.properties
gradle/libs.versions.toml
build.gradle.kts                      # root: Dokka aggregation + Sonar
build-logic/settings.gradle.kts
build-logic/build.gradle.kts
build-logic/src/main/kotlin/iru.kotlin-library.gradle.kts
build-logic/src/main/kotlin/iru.spring-boot-app.gradle.kts
build-logic/src/main/kotlin/iru.quality.gradle.kts
build-logic/src/main/kotlin/iru.integration-test.gradle.kts
build-logic/src/main/kotlin/iru.coverage.gradle.kts
<module>/build.gradle.kts             # one per leaf module in `modules:`, coverage included
gradlew, gradlew.bat, gradle/wrapper/gradle-wrapper.jar, gradle/wrapper/gradle-wrapper.properties
```

Aggregator directories (`infrastructure/`, `infrastructure/database/`, `infrastructure/clients/`, `api/`) get **no**
build file — Gradle creates those parent projects implicitly from the `:`-paths. Everything written here must be
ktlint-clean (`ktlint_official` style from `.editorconfig`): ktlint checks every module's `build.gradle.kts` too, and a
single formatting slip fails `ktlintCheck` (verified — a missing blank line before a declaration did).

### Versions

Every `<latest>` below is resolved live, never from memory, newest version without a pre-release suffix, and
recorded for Step 8:

- Gradle plugins — the plugin portal marker metadata,
  `https://plugins.gradle.org/m2/<id with . as />/<id>.gradle.plugin/maven-metadata.xml` (e.g.
  `org/jlleitschuh/gradle/ktlint/org.jlleitschuh.gradle.ktlint.gradle.plugin`), for `org.jlleitschuh.gradle.ktlint`,
  `io.gitlab.arturbosch.detekt`, `org.jetbrains.dokka` and `org.sonarqube` (the codegen and Vaadin plugins are
  resolved by the apis/hilla skills that add them).
- Libraries — Maven Central `maven-metadata.xml` as in Step 1 (`io/mockk/mockk`, `com/ninja-squad/springmockk`
  (5.x for Boot 4), `com/tngtech/archunit/archunit-junit5`, `com/pinterest/ktlint/ktlint-cli` for the ktlint
  engine version, `org/jacoco/jacoco-maven-plugin` for the JaCoCo tool version).
- `kotlin` = the manifest's `project.kotlinVersion` (Step 0) — the Kotlin Gradle plugin version must equal the
  Kotlin version Boot manages. `spring-boot` = `project.springBootVersion`. `java` = `project.javaVersion`.
- BOM versions (`spring-cloud`, `spring-ai`, …) — copied from the reference build, exactly as on Maven.

As of this writing: Gradle 9.8.1, ktlint-gradle 14.2.0 (ktlint 1.8.0), detekt 1.23.8, Dokka 2.2.0, sonarqube
7.5.0.8588, JaCoCo 0.8.13+, MockK 1.14.11, springmockk 5.0.1. detekt's 2.x line (`dev.detekt`) is alpha-only —
stay on 1.23.x. Don't add `io.spring.dependency-management`: the BOM is applied with `platform()`, which also keeps
it out of detekt's own classpath (the dependency-management plugin is known to force detekt's embedded Kotlin to
the project's version and break it).

### `settings.gradle.kts`

Template: `templates/kotlin-gradle/settings.gradle.kts` — read it in full before filling placeholders

Project names are the directory names (`boot`, `rest-server`, …), not the Maven `<a>-<suffix>` artifactIds; the
published jar names follow the project name.

### `gradle.properties`

Template: `templates/kotlin-gradle/gradle.properties` — read it in full before filling placeholders

### `gradle/libs.versions.toml`

Template: `templates/kotlin-gradle/gradle/libs.versions.toml` — read it in full before filling placeholders

Omit every entry the stack doesn't use, exactly as the Maven root pom omits unused properties. The codegen and
frontend pieces are **not** written here: `iru-setup-java-springboot-apis` appends its own versions, libraries and
plugins to this same file (`org.openapi.generator`, `com.google.protobuf` with
`protobuf-protoc`/`grpc-protoc-gen-java`/`grpc-protoc-gen-kotlin`, `com.bakdata.gradle.avro`, …) and declares those
plugins in the root `plugins { }` block with `apply false`; `iru-setup-java-springboot-hilla` does the same for
`com.vaadin` and the Vaadin BOM. On a re-run (Update), keep every entry and root `apply false` line they added.
Any alias you add must not be a dotted prefix of another (`protoc` next to `protoc-gen-…` turns `libs.protoc` into a
group, not a library, and breaks `libs.protoc.get()`).

### `build-logic/` (included build with precompiled script convention plugins)

`build-logic/settings.gradle.kts` — shares the root catalog, so the convention plugins and the modules read one
set of versions:

Template: `templates/kotlin-gradle/build-logic/settings.gradle.kts` — read it in full before filling placeholders

`build-logic/build.gradle.kts`:

Template: `templates/kotlin-gradle/build-logic/build.gradle.kts` — read it in full before filling placeholders

Precompiled script plugins can't use the type-safe `libs` accessor, so each one looks the catalog up by name.

`build-logic/src/main/kotlin/iru.kotlin-library.gradle.kts` — applied by every module (directly or via
`iru.spring-boot-app`); it applies the other three conventions, so a module build file names one plugin:

Template: `templates/kotlin-gradle/build-logic/src/main/kotlin/iru.kotlin-library.gradle.kts` — read it in full before filling placeholders

The toolchain makes `./gradlew` compile and test with JDK `<java-version>` whatever JDK runs Gradle; CI's
`setup-java` should install that same version so no toolchain download is needed. `implementation(platform(...))`
also feeds `testImplementation` and the runtime classpaths; it adds version constraints only, so `domain` gets no
Spring class from it. `io.mockk:mockk` is right on Gradle (it resolves the JVM variant through Gradle metadata;
Maven needs `mockk-jvm`).

`build-logic/src/main/kotlin/iru.spring-boot-app.gradle.kts` — applied to `boot` only:

Template: `templates/kotlin-gradle/build-logic/src/main/kotlin/iru.spring-boot-app.gradle.kts` — read it in full before filling placeholders

`build-logic/src/main/kotlin/iru.quality.gradle.kts`:

Template: `templates/kotlin-gradle/build-logic/src/main/kotlin/iru.quality.gradle.kts` — read it in full before filling placeholders

`./gradlew ktlintFormat` fixes most ktlint findings; detekt findings are fixed by hand (or suppressed with a
reason, never by raising `maxIssues`).

`build-logic/src/main/kotlin/iru.integration-test.gradle.kts`:

Template: `templates/kotlin-gradle/build-logic/src/main/kotlin/iru.integration-test.gradle.kts` — read it in full before filling placeholders

Applying it to every module (through `iru.kotlin-library`) means any module that later gains an `*IT` —
`infrastructure/database/<engine>`, `api/*`, `boot` — already has the task; `./gradlew test` never starts a
container, which is what the unit-only coverage gate and `ci.tests: unit` rely on.

`build-logic/src/main/kotlin/iru.coverage.gradle.kts`:

Template: `templates/kotlin-gradle/build-logic/src/main/kotlin/iru.coverage.gradle.kts` — read it in full before filling placeholders

Like the Maven template, no build-failing coverage threshold is configured here — the 80% gate is enforced by
the coding skills (`iru-java-coverage` reads these reports).

### Root `build.gradle.kts`

Template: `templates/kotlin-gradle/build.gradle.kts` — read it in full before filling placeholders

`id("iru.quality") apply false` is deliberate: it loads the convention plugins' dependencies into the root
classloader once, so every subproject shares one copy of the Kotlin/Dokka/detekt plugins and the root can apply
Dokka without a version (applying `org.jetbrains.dokka` with a version here as well would clash). The Gradle Sonar
plugin finds Kotlin source sets, test sets and binaries by itself — no `sonar.sources` needed. Sonar wiring
follows Step 1's `sonar.mode` rules exactly: `none` — drop the `alias(libs.plugins.sonarqube)` line, the whole
`sonar { }` block and the `sonarqube` catalog entries; `cloud` — `https://sonarcloud.io` and the manifest's
organization/project key; `self-hosted` — the manifest's `sonar.hostUrl`, and `sonar.organization` only when the
manifest set it; `ci.enabled: false` with Sonar kept — add a comment above `sonar { }` saying no CI workflow sends
reports (`./gradlew sonar -Dsonar.token=…` runs it manually).

### Module `build.gradle.kts` files

Every leaf module names one convention plugin and its own dependencies — `project(":...")` for modules of this
project exactly per the module table's "May depend on" column, catalog aliases for everything else, never a
version:

Template: `templates/kotlin-gradle/domain/build.gradle.kts` — read it in full before filling placeholders

Template: `templates/kotlin-gradle/application/build.gradle.kts` — read it in full before filling placeholders

Template: `templates/kotlin-gradle/infrastructure/database/ENGINE/build.gradle.kts` — read it in full before filling placeholders

`boot/build.gradle.kts` additionally carries the executable artifact, the image name and the same
`build-info.properties` the Maven build writes with `groovy-maven-plugin` — generated under `build/` instead of
`src/main/resources`, so the working tree stays clean:

Template: `templates/kotlin-gradle/boot/build.gradle.kts` — read it in full before filling placeholders

`coverage/build.gradle.kts` — no sources; one `jacocoAggregation` line per other leaf module:

Template: `templates/kotlin-gradle/coverage/build.gradle.kts` — read it in full before filling placeholders

`base` (not `java`) keeps `coverage` from being a Kotlin/Java project, so `testCodeCoverageReport` must be
registered explicitly as above. Generated code stays out of every tool on Gradle too: detekt reads only
`src/*/kotlin` and excludes `**/generated/**`, ktlint skips anything under `/generated/` or `/build/`, Dokka
suppresses `build/generated` and any `*.generated.*` package, both JaCoCo reports drop `**/generated/**` classes,
and the root `sonar.exclusions` covers `**/generated/**,**/build/generated/**` (verified: a class in a
`generated` package was absent from the aggregate coverage report and from the Dokka output).

### Gradle wrapper

Write `gradlew`, `gradlew.bat` and `gradle/wrapper/*` for the latest stable Gradle (`https://services.gradle.org/versions/current`,
9.8.1 as of this writing), first source that works:

1. The Initializr starter extracted in Step 0 already contains a wrapper — copy its four files when its
   `distributionUrl` version is the current stable one (or the newest the user accepts).
2. A local `gradle` on `PATH`: `gradle wrapper --gradle-version <version> --distribution-type bin`.
3. Docker: `docker run --rm -v "$PWD":/w -w /w gradle:<any 8.x/9.x jdk tag> gradle wrapper --gradle-version <version> --distribution-type bin`
   (verified with `gradle:8-jdk21` producing a 9.8.1 wrapper).

For 2 and 3, run the command in an **empty scratch directory** holding only an empty `settings.gradle.kts`, then
copy `gradlew`, `gradlew.bat` and `gradle/wrapper/*` into the repository. Run in the repository itself, the
`wrapper` task first configures the whole build — including compiling `build-logic/` — with whatever Gradle runs it,
and an 8.x Gradle fails there (`Unresolved reference: failOnNoDiscoveredTests`, a Gradle 9 API).
4. Otherwise ask the user to run one of the above — never hand-write `gradle-wrapper.jar`.

Then `chmod +x gradlew`. All later commands use `./gradlew`.

## Step 6 — Verify the reactor resolves

Delegate to the `iru-gate-runner` agent (a multi-module Maven log is exactly the kind of output that shouldn't land
in this context) and ask for a compact result:

```bash
mvn -q -N validate            # root pom alone parses and the enforcer rules pass
mvn -q validate               # every module pom parses and the reactor order resolves
mvn dependency:tree -q        # every dependency resolves; no version comes from outside the root pom
```

Scan the `dependency:tree` output for two specific problems and fix them before finishing:

- a **version declared in a module pom** — move it to the root's `<dependencyManagement>`;
- an **unmanaged transitive version conflict** Maven resolved by nearest-wins — pin it in the root's
  `<dependencyManagement>` so the choice is explicit.

If the enforcer's `requireJavaVersion` rule fails because the local JDK is older than the target, that's the rule
working: report it and tell the user which JDK the build needs, rather than lowering the target.

**Kotlin + Maven** — the same three commands, then one more run that actually exercises the Kotlin toolchain on
the (still source-less, or freshly scaffolded) reactor:

```bash
mvn -q verify -DskipITs                      # kotlinc + javac ordering, Surefire, JaCoCo, detekt, ktlint
mvn -q detekt:check ktlint:check             # the prefixes CI calls resolve outside the lifecycle
mvn -q compile dokka:dokka                   # Dokka runs (fails with "Not found dokka plugin" if misdeclared)
```

**Kotlin + Gradle** — via the wrapper, again through `iru-gate-runner`:

```bash
./gradlew help                                       # settings, catalog and build-logic compile; projects resolve
./gradlew build -x test -x integrationTest           # every module compiles; detekt, ktlint (incl. *.gradle.kts) pass
./gradlew :boot:dependencies --configuration runtimeClasspath   # every dependency resolves through the BOM/catalog
./gradlew tasks --all                                 # test, integrationTest, :coverage:testCodeCoverageReport,
                                                      # detekt, ktlintCheck, dokkaGeneratePublicationHtml,
                                                      # :boot:bootBuildImage and (unless sonar: none) sonar exist
```

Scan for the Gradle equivalents of the two Maven problems: a version literal in a module `build.gradle.kts` or a
convention plugin (move it to the catalog), and a `FAILED` resolution or a conflict resolved to an unexpected
version (pin it in the catalog). A toolchain error ("Cannot find a Java installation matching languageVersion")
means the local machine lacks JDK `<java-version>` — report it, don't lower the toolchain.

## Step 7 — Sanity-check the hexagon

Confirm by reading the written poms, not by assuming:

- `domain` depends on no other module of this project and on nothing from `org.springframework*`.
- No `infrastructure/*` module depends on `application`, on `api/*`, or on a sibling infrastructure module.
- No `api/*` module depends on an `infrastructure/*` module.
- `boot` depends on every other module.
- No module pom other than the root declares a `<version>` for a dependency.

On Gradle the same four dependency checks apply to the `project(":...")` lines in each `build.gradle.kts`, plus:
no version literal outside `gradle/libs.versions.toml`, and every leaf module in `modules:` has an `include(...)`
in `settings.gradle.kts`, a `"dokka"(project(...))` line in the root build (except `coverage`), and a
`jacocoAggregation(project(...))` line in `coverage/build.gradle.kts`. There is no enforcer on Gradle — say in
Step 8 that the boundaries are guarded by the declared dependencies plus the ArchUnit test
`iru-setup-java-springboot-modules` writes in `boot`, and nothing else.

Report any violation you had to introduce deliberately (e.g. a starter that drags Spring into `domain`) rather
than quietly relaxing the enforcer rule to make the build pass.

## Step 8 — Report

Summarize: whether the reactor was generated from scratch or updated, and per file the action taken (**created /
updated / replaced / kept as is**), with every mismatch left in a kept file; the reactor's module list in build order; every version pinned in the root pom (separating those copied
from the Initializr reference pom from those looked up on Maven Central); which module each significant dependency
landed in; any dependency that couldn't be placed and therefore went to `boot`; whether `checkstyle.xml` and
`spotbugs-exclude.xml` were created or already present; the result of Step 6's three commands; and any hexagon
violation from Step 7. If an existing root pom was regenerated, list what was carried over from it so the user can
confirm nothing was lost. State how `sonar.mode` was wired: `none` (no `sonar.*` properties, no
`sonar-maven-plugin`), `cloud` (SonarCloud, with the resolved `sonar.organization`/`sonar.projectKey`), or
`self-hosted` (the resolved `sonar.host.url`, and whether `sonar.organization` was included or omitted). If the
manifest's `ci.enabled` is `false` while `sonar.mode` isn't `none`, say that the Sonar properties/plugin are
build-side configuration only — no CI step sends reports, so analysis runs only when someone runs `mvn
sonar:sonar` manually with a token. State how
`frontend` was wired: `none` (no Vaadin properties/BOM/plugin/profile at all) or `hilla` (the resolved
`vaadin.version`, and that the `vaadin-bom` import, `vaadin-maven-plugin` `pluginManagement` entry, `production`
profile, and `vaadin.skip` property were all written — noting that `iru-setup-java-springboot-hilla` is what
actually wires a module against these pins).

For Kotlin, also state: the build tool; the `kotlinVersion` used (and that it is the Boot-managed one); the
resolved detekt/ktlint/Dokka/MockK/springmockk versions (and, on Gradle, the Gradle version and which source the
wrapper came from); whether `detekt.yml` and `.editorconfig` were created or already present; that Lombok,
MapStruct and the configuration processor were deliberately left out; and, on Kotlin + Maven, which modules got
the `src/main/kotlin`/`src/test/kotlin` placeholders and which got a mixed-source `sonar.sources` override. On
Gradle report per file (settings, catalog, root build, each convention plugin, each module build file, wrapper)
the action taken, the Step 6 results, and the commands the rest of the chain uses: `./gradlew build -x test -x
integrationTest`, `./gradlew test`, `./gradlew integrationTest`, `./gradlew :coverage:testCodeCoverageReport`,
`./gradlew detekt ktlintCheck`, `./gradlew dokkaGeneratePublicationHtml`, `./gradlew sonar`.

Close by reminding the user that **the root pom (on Gradle, `gradle/libs.versions.toml`) is the only place a
version may be declared** — adding one to a
module pom or build file silently defeats the build's version discipline — and that `mvn dependency:tree` (on
Gradle, `./gradlew :<module>:dependencies`) is the check for that.
