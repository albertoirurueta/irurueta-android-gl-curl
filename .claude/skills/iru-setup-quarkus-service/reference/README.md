# iru-setup-quarkus-service templates

Verified templates for Step 8 of `iru-setup-quarkus-service`. Load **only** the files the manifest's
`language`/`buildTool`/`layout` combination needs — they are long because they carry whole, runnable files.

## Read this much, and no more

| Read | When |
|---|---|
| `maven.md` | `buildTool: maven` and `layout: single`. Also for `layout: hexagonal` on Maven — its blocks are what the parent pom in `hexagonal.md` is made of, and its Kotlin/Sonar/container sections apply unchanged. |
| `gradle.md` | `buildTool: gradle` and `layout: single`. Also for `layout: hexagonal` on Gradle (the Java build's tool blocks, the Sonar block, container image, commands). |
| `hexagonal.md` | `layout: hexagonal` — module table, parent/root build, module builds, Jandex, ArchUnit, sample code per module. Replaces `sample-code.md` for that layout. |
| `quality-config.md` | Always — `checkstyle.xml` (Java) or `detekt.yml` + `.editorconfig` + `module.md` (Kotlin). |
| `sample-code.md` | `layout: single` — the greeting feature at the four test levels, plus the `application.properties` template (hexagonal builds also take the properties template from here). |

## What "verified" means here

Every build file, configuration file and sample class in these files was generated with
`quarkus-maven-plugin:3.40.1:create`, post-processed exactly as shown, and run in a throwaway directory outside any
repository: Maven 3.9 / JDK 25 (release 21) locally, Gradle 9.7.0 (the generated wrapper) on JDK 21 in the
`gradle:8-jdk21` image with the host Docker socket mounted, Dev Services on Docker. Combinations run end to end:

| Language | Build | Layout | Ran |
|---|---|---|---|
| Java | Maven | single | `verify -DskipITs=false`, unit-only command, Checkstyle/PMD/SpotBugs, Javadoc, `site`, Jib image |
| Java | Gradle | single | `check quarkusIntTest`, unit-only command, Checkstyle/PMD/SpotBugs, Javadoc, Sonar plugin config |
| Kotlin | Gradle | single | `check quarkusIntTest`, unit-only command, detekt, ktlint, Dokka |
| Kotlin | Maven | single | `verify -DskipITs=false`, detekt, ktlint, `dokka:dokka` |
| Java | Maven | hexagonal | `verify -DskipITs=false`, unit-only command, quality + Javadoc, `site site:stage`, ArchUnit negative test |
| Kotlin | Gradle | hexagonal | `check :app:quarkusIntTest :dokkaGeneratePublicationHtml`, detekt, ktlint |

Not run: Java/Gradle and Kotlin/Maven *hexagonal* builds (assembled from verified pieces), native builds (minutes and
gigabytes; the `native` profile/flags are `create`'s own), a real Sonar analysis, and pushing an image to a registry.

Versions pinned in these files are what was current at the time of writing (Quarkus platform 3.40.1, JaCoCo 0.8.15,
Checkstyle 14.3.0, PMD 7.28.0, SpotBugs plugin 4.10.5.0 / Gradle 6.5.13, detekt 1.23.8, ktlint Gradle 14.2.0 / Maven
3.7.1, Dokka 2.2.0, MockK 1.14.11, ArchUnit 1.5.1, Jandex Maven 3.6.0 / Gradle 2.3.0, Sonar Maven 5.8.0.7211 /
Gradle 7.5.0.8588). The skill re-resolves the platform from code.quarkus.io every run; re-resolve the plugin versions
from Maven Central / the Gradle Plugin Portal too, and re-run Step 9's gates whenever one moves.

## Sources

- irurueta docs: `backend/quarkus/build-tooling-and-languages.adoc`, `getting-started.adoc`, `testing.adoc`,
  `java-or-kotlin.adoc`, `configuration.adoc`, `native-executables.adoc`, `cdi-and-the-programming-model.adoc`
- This catalog: `iru-quarkus-code-one-task/reference/` (the conventions generated code must satisfy),
  `iru-setup-java-springboot` (module table), `iru-setup-java-library` (Java quality/report plugin set)
- Quarkus guides: <https://quarkus.io/guides/maven-tooling>, <https://quarkus.io/guides/gradle-tooling>,
  <https://quarkus.io/guides/tests-with-coverage>, <https://quarkus.io/guides/testing-components>,
  <https://quarkus.io/guides/kotlin>, <https://quarkus.io/guides/container-image>, <https://quarkus.io/guides/cdi-reference#bean_discovery>
- code.quarkus.io API: <https://code.quarkus.io/api/streams>, <https://code.quarkus.io/api/extensions>
