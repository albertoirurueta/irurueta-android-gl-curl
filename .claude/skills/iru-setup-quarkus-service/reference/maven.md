# Maven post-processing (single-module)

What Step 8 of the skill applies to the `pom.xml` that `quarkus-maven-plugin:create` wrote. The generated pom is the
starting point — keep its BOM import, `quarkus-maven-plugin` (`<extensions>true</extensions>`), compiler/Surefire/
Failsafe plugins, `skipITs=true` property and `native` profile — and add the blocks below. A hexagonal reactor uses
the same blocks moved into the parent pom (`hexagonal.md`).

Verified with Quarkus 3.40.1 / Maven 3.9 / JDK 25 (release 21): `./mvnw verify -DskipITs=false` (Dev Services
PostgreSQL + Failsafe `@QuarkusIntegrationTest`), the unit-only CI command at the end of this file, `checkstyle:checkstyle
pmd:pmd spotbugs:spotbugs javadoc:javadoc`, `site`, and the Jib image build. Versions below were the latest releases
on Maven Central at the time of writing — re-resolve them (Maven Central `maven-metadata.xml` `<release>`) rather
than trusting this file blindly, and keep `compiler-plugin.version`/`surefire-plugin.version` exactly as `create`
wrote them (they're the ones the platform was tested with).

## Java — the resulting `pom.xml`

`<placeholders>` come from `quarkus-stack.yml`. Everything marked "added by iru-setup-quarkus-service" is new; the
rest is what `create` generated.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId><group-id></groupId>
    <artifactId><artifact-id></artifactId>
    <version><project-version></version>
    <packaging>quarkus</packaging>

    <!-- added by iru-setup-quarkus-service: project identity (read by iru-setup-readme, iru-release, Sonar) -->
    <name><artifact-id></name>
    <description><project-description></description>
    <url>https://<host>/<owner>/<repo></url>
    <inceptionYear><inception-year></inceptionYear>
    <!-- omit <licenses> entirely for "no license" -->
    <licenses>
        <license>
            <name><license-name></name>
            <url><license-url></url>
        </license>
    </licenses>
    <developers>
        <developer>
            <name><developer-name></name>
            <email><developer-email></email>
            <organizationUrl><organization-url></organizationUrl>
        </developer>
    </developers>
    <scm>
        <connection>scm:git:https://<host>/<owner>/<repo>.git</connection>
        <developerConnection>scm:git:git@<host>:<owner>/<repo>.git</developerConnection>
        <url>https://<host>/<owner>/<repo></url>
    </scm>

    <properties>
        <compiler-plugin.version>3.15.0</compiler-plugin.version>
        <maven.compiler.release><java-version></maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
        <quarkus.platform.artifact-id>quarkus-bom</quarkus.platform.artifact-id>
        <quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>
        <quarkus.platform.version><platform-version></quarkus.platform.version>
        <skipITs>true</skipITs>
        <surefire-plugin.version>3.5.6</surefire-plugin.version>
        <!-- iru-setup-quarkus-service: quality, coverage and docs tooling -->
        <jacoco.version>0.8.15</jacoco.version>
        <jacoco.minimum.line.coverage>0.80</jacoco.minimum.line.coverage>
        <checkstyle-plugin.version>3.6.0</checkstyle-plugin.version>
        <checkstyle.version>14.3.0</checkstyle.version>
        <pmd-plugin.version>3.28.0</pmd-plugin.version>
        <spotbugs-plugin.version>4.10.5.0</spotbugs-plugin.version>
        <javadoc-plugin.version>3.12.0</javadoc-plugin.version>
        <site-plugin.version>3.21.0</site-plugin.version>
        <project-info-reports-plugin.version>3.9.0</project-info-reports-plugin.version>
        <jxr-plugin.version>3.6.0</jxr-plugin.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>${quarkus.platform.group-id}</groupId>
                <artifactId>${quarkus.platform.artifact-id}</artifactId>
                <version>${quarkus.platform.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- … the extension dependencies `create` wrote, unchanged (quarkus-rest, quarkus-arc, …) … -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit-component</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit-mockito</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-jacoco</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.rest-assured</groupId>
            <artifactId>rest-assured</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>${quarkus.platform.group-id}</groupId>
                <artifactId>quarkus-maven-plugin</artifactId>
                <version>${quarkus.platform.version}</version>
                <extensions>true</extensions>
            </plugin>
            <plugin>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>${compiler-plugin.version}</version>
                <configuration>
                    <parameters>true</parameters>
                </configuration>
            </plugin>
            <plugin>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>${surefire-plugin.version}</version>
                <configuration>
                    <argLine>@{argLine}</argLine>
                    <systemPropertyVariables>
                        <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
                        <maven.home>${maven.home}</maven.home>
                        <!-- quarkus-jacoco records the classes Quarkus transforms (@QuarkusComponentTest,
                             @QuarkusTest) into target/jacoco-quarkus.exec; without a @QuarkusTest in the run its
                             runtime falls back to this property (default ./jacoco.exec), so point it at the same
                             file. The jacoco-merge execution below folds it into the report and the 80% check. -->
                        <jacoco-agent.destfile>${project.build.directory}/jacoco-quarkus.exec</jacoco-agent.destfile>
                    </systemPropertyVariables>
                </configuration>
            </plugin>
            <plugin>
                <artifactId>maven-failsafe-plugin</artifactId>
                <version>${surefire-plugin.version}</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>integration-test</goal>
                            <goal>verify</goal>
                        </goals>
                    </execution>
                </executions>
                <configuration>
                    <argLine>@{argLine}</argLine>
                    <systemPropertyVariables>
                        <native.image.path>${project.build.directory}/${project.build.finalName}-runner</native.image.path>
                        <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
                        <maven.home>${maven.home}</maven.home>
                    </systemPropertyVariables>
                </configuration>
            </plugin>
            <!-- Coverage: the agent measures plain tests (target/jacoco.exec) and skips the Quarkus class loader;
                 quarkus-jacoco measures component and @QuarkusTest classes (target/jacoco-quarkus.exec). merge →
                 report → 80% line check, all in verify. -->
            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <version>${jacoco.version}</version>
                <executions>
                    <execution>
                        <id>prepare-agent</id>
                        <goals>
                            <goal>prepare-agent</goal>
                        </goals>
                        <configuration>
                            <exclClassLoaders>*QuarkusClassLoader</exclClassLoaders>
                        </configuration>
                    </execution>
                    <execution>
                        <id>merge</id>
                        <phase>verify</phase>
                        <goals>
                            <goal>merge</goal>
                        </goals>
                        <configuration>
                            <fileSets>
                                <fileSet>
                                    <directory>${project.build.directory}</directory>
                                    <includes>
                                        <include>jacoco.exec</include>
                                        <include>jacoco-quarkus.exec</include>
                                    </includes>
                                </fileSet>
                            </fileSets>
                            <destFile>${project.build.directory}/jacoco-merged.exec</destFile>
                        </configuration>
                    </execution>
                    <execution>
                        <id>report</id>
                        <phase>verify</phase>
                        <goals>
                            <goal>report</goal>
                        </goals>
                        <configuration>
                            <dataFile>${project.build.directory}/jacoco-merged.exec</dataFile>
                        </configuration>
                    </execution>
                    <execution>
                        <id>check</id>
                        <phase>verify</phase>
                        <goals>
                            <goal>check</goal>
                        </goals>
                        <configuration>
                            <dataFile>${project.build.directory}/jacoco-merged.exec</dataFile>
                            <rules>
                                <rule>
                                    <element>BUNDLE</element>
                                    <limits>
                                        <limit>
                                            <counter>LINE</counter>
                                            <value>COVEREDRATIO</value>
                                            <minimum>${jacoco.minimum.line.coverage}</minimum>
                                        </limit>
                                    </limits>
                                </rule>
                            </rules>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <!-- Javadoc: every type and member documented; -Xdoclint fails the build on a missing @param -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-javadoc-plugin</artifactId>
                <version>${javadoc-plugin.version}</version>
                <configuration>
                    <show>private</show>
                    <doclint>all</doclint>
                    <failOnWarnings>true</failOnWarnings>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-checkstyle-plugin</artifactId>
                <version>${checkstyle-plugin.version}</version>
                <dependencies>
                    <dependency>
                        <groupId>com.puppycrawl.tools</groupId>
                        <artifactId>checkstyle</artifactId>
                        <version>${checkstyle.version}</version>
                    </dependency>
                </dependencies>
                <configuration>
                    <configLocation>checkstyle.xml</configLocation>
                    <includeTestSourceDirectory>true</includeTestSourceDirectory>
                    <consoleOutput>true</consoleOutput>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-pmd-plugin</artifactId>
                <version>${pmd-plugin.version}</version>
                <configuration>
                    <targetJdk>${maven.compiler.release}</targetJdk>
                    <excludeRoots>
                        <excludeRoot>${project.build.directory}/generated-sources</excludeRoot>
                    </excludeRoots>
                </configuration>
            </plugin>
            <plugin>
                <groupId>com.github.spotbugs</groupId>
                <artifactId>spotbugs-maven-plugin</artifactId>
                <version>${spotbugs-plugin.version}</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-site-plugin</artifactId>
                <version>${site-plugin.version}</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-project-info-reports-plugin</artifactId>
                <version>${project-info-reports-plugin.version}</version>
            </plugin>
        </plugins>
    </build>

    <reporting>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-javadoc-plugin</artifactId>
                <version>${javadoc-plugin.version}</version>
                <configuration>
                    <show>private</show>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-report-plugin</artifactId>
                <version>${surefire-plugin.version}</version>
            </plugin>
            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <version>${jacoco.version}</version>
                <reportSets>
                    <reportSet>
                        <reports>
                            <report>report</report>
                        </reports>
                    </reportSet>
                </reportSets>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-checkstyle-plugin</artifactId>
                <version>${checkstyle-plugin.version}</version>
                <configuration>
                    <configLocation>checkstyle.xml</configLocation>
                </configuration>
                <reportSets>
                    <reportSet>
                        <reports>
                            <report>checkstyle</report>
                        </reports>
                    </reportSet>
                </reportSets>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-pmd-plugin</artifactId>
                <version>${pmd-plugin.version}</version>
            </plugin>
            <plugin>
                <groupId>com.github.spotbugs</groupId>
                <artifactId>spotbugs-maven-plugin</artifactId>
                <version>${spotbugs-plugin.version}</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jxr-plugin</artifactId>
                <version>${jxr-plugin.version}</version>
            </plugin>
        </plugins>
    </reporting>

    <profiles>
        <profile>
            <id>native</id>
            <activation>
                <property>
                    <name>native</name>
                </property>
            </activation>
            <properties>
                <quarkus.package.jar.enabled>false</quarkus.package.jar.enabled>
                <skipITs>false</skipITs>
                <quarkus.native.enabled>true</quarkus.native.enabled>
            </properties>
        </profile>
    </profiles>
</project>
```

Why each addition is there:

- **Test dependencies** — `quarkus-junit-component` (level 2), `quarkus-junit-mockito` (`@InjectMock`; also brings
  `mockito-core` for plain tests), `rest-assured` (level 3/4), `quarkus-jacoco` (makes classes Quarkus transforms —
  component and `@QuarkusTest` subjects — measurable). All versions come from the BOM. Don't add AssertJ.
- **`jacoco-agent.destfile`** — `quarkus-jacoco` (≥ 3.31) records transformed classes into
  `target/jacoco-quarkus.exec` when a `@QuarkusTest` boots, but without one (a pure plain + component run, i.e. the
  `ci-tests: unit` run and the coding skills' unit stage) its runtime falls back to this property, default
  `./jacoco.exec` in the module directory — invisible to the report. Pointing it at the same file makes both cases
  land in one place. Never point it at `target/jacoco.exec`: two writers on one file in one JVM lose data (observed:
  a component-tested class dropped to 0% once a `@QuarkusTest` shared the run).
- **`exclClassLoaders`** — the JaCoCo agent skips the Quarkus class loader so `@QuarkusTest` data only comes from
  `quarkus-jacoco`, never twice.
- **`merge` → `report` → `check`**, all in `verify` — one merged data file, one report
  (`target/site/jacoco/jacoco.xml`, what Sonar reads), and the **80% line gate** on it. `%test.quarkus.jacoco.report=false`
  in `application.properties` turns off `quarkus-jacoco`'s own second report (otherwise it waits 10 s for a file
  and writes an `error.txt`).
- **Javadoc with `doclint=all` + `failOnWarnings`** — the build fails on a missing `@param`/`@return`/default-constructor
  comment, which is what `iru-java-javadoc` audits. Not bound to a phase: `./mvnw javadoc:javadoc` (CI's docs job
  and the gate run it).
- **Checkstyle/PMD/SpotBugs in `<build><plugins>`** (configuration only, no executions) so `iru-java-code-quality`'s
  direct goals (`checkstyle:checkstyle pmd:pmd spotbugs:spotbugs`) use the project's config; the same plugins in
  `<reporting>` feed `mvn site`. They report, they don't fail the build — the coding group skills gate on "no new
  issues versus the baseline".
- **`site`/`project-info-reports` pinned** so `./mvnw site` is reproducible (CI's docs job publishes `target/site`
  as `mvn-site/`).

## Kotlin (`language: kotlin`) — differences from the Java pom

`create` with the `kotlin` extension already sets `<sourceDirectory>src/main/kotlin</sourceDirectory>`/
`<testSourceDirectory>src/test/kotlin</testSourceDirectory>`, adds `quarkus-kotlin`, `kotlin-stdlib-jdk8`, and a
`kotlin-maven-plugin` with all-open only. Then:

1. **Replace the generated `kotlin-maven-plugin`** with the one from `iru-quarkus-code-one-task/reference/kotlin.md`
   ("Maven" section) — same executions, plus `kotlin-maven-noarg`, `RequestScoped` in all-open, and the three no-arg
   JPA annotations. Keep `<jvmTarget><java-version></jvmTarget>` from the generated block.
2. **Test dependencies** — the same four as Java, plus MockK (not in the Quarkus BOM):

   ```xml
   <dependency>
       <groupId>io.mockk</groupId>
       <artifactId>mockk-jvm</artifactId>
       <version>${mockk.version}</version>
       <scope>test</scope>
   </dependency>
   ```
3. **Coverage** — identical: the Surefire `jacoco-agent.destfile` property and the whole `jacoco-maven-plugin` block.
4. **Instead of Checkstyle/PMD/SpotBugs/Javadoc** — detekt, ktlint, Dokka (properties `mockk.version` 1.14.11,
   `detekt.version` 1.23.8, `ktlint-plugin.version` 3.7.1, `dokka.version` 2.2.0, plus `jacoco.version`/
   `jacoco.minimum.line.coverage` as for Java). detekt and ktlint are bound to `verify` and fail the build on a
   finding; their Checkstyle-format reports are what `iru-kotlin-code-quality` and Sonar read:

   ```xml
   <!-- detekt: Checkstyle-format XML for Sonar and iru-kotlin-code-quality; the build fails on any issue -->
   <plugin>
       <groupId>com.github.ozsie</groupId>
       <artifactId>detekt-maven-plugin</artifactId>
       <version>${detekt.version}</version>
       <configuration>
           <config>detekt.yml</config>
           <buildUponDefaultConfig>true</buildUponDefaultConfig>
           <input>${project.basedir}/src</input>
           <report>
               <report>xml:${project.build.directory}/detekt.xml</report>
               <report>html:${project.build.directory}/detekt.html</report>
           </report>
       </configuration>
       <executions>
           <execution>
               <id>detekt-check</id>
               <phase>verify</phase>
               <goals>
                   <goal>check</goal>
               </goals>
           </execution>
       </executions>
   </plugin>
   <plugin>
       <groupId>com.github.gantsign.maven</groupId>
       <artifactId>ktlint-maven-plugin</artifactId>
       <version>${ktlint-plugin.version}</version>
       <configuration>
           <reporters>
               <reporter>
                   <name>plain</name>
               </reporter>
               <reporter>
                   <name>checkstyle</name>
                   <output>${project.build.directory}/ktlint.xml</output>
               </reporter>
           </reporters>
       </configuration>
       <executions>
           <execution>
               <id>ktlint-check</id>
               <phase>verify</phase>
               <goals>
                   <goal>check</goal>
               </goals>
           </execution>
       </executions>
   </plugin>
   <plugin>
       <groupId>org.jetbrains.dokka</groupId>
       <artifactId>dokka-maven-plugin</artifactId>
       <version>${dokka.version}</version>
       <configuration>
           <includes>
               <include>module.md</include>
           </includes>
       </configuration>
   </plugin>
   ```

   No `<reporting>` section (none of the three is a Maven report plugin); CI publishes `target/dokka` as `api/kdoc/`.
   detekt-maven-plugin 1.23.8 bundles its own Kotlin compiler, so it runs fine against a Kotlin 2.4 project
   (verified). Format once with `./mvnw ktlint:format` before the first `verify`.

## Sonar (`sonar` ≠ `none`)

Add to `<properties>` (resolve `<sonar-maven-plugin-version>` from Maven Central —
`org.sonarsource.scanner.maven:sonar-maven-plugin`, 5.8.0.7211 at the time of writing):

```xml
<sonar.organization><sonar-organization></sonar.organization>   <!-- omit for self-hosted without organizations -->
<sonar.projectKey><sonar-project-key></sonar.projectKey>
<sonar.host.url><sonar-host-url></sonar.host.url>
<sonar.coverage.jacoco.xmlReportPaths>${project.build.directory}/site/jacoco/jacoco.xml</sonar.coverage.jacoco.xmlReportPaths>
<!-- Kotlin only -->
<sonar.kotlin.detekt.reportPaths>${project.build.directory}/detekt.xml</sonar.kotlin.detekt.reportPaths>
<sonar.kotlin.ktlint.reportPaths>${project.build.directory}/ktlint.xml</sonar.kotlin.ktlint.reportPaths>
```

and to `<build><plugins>` (no executions — CI calls `sonar:sonar` directly):

```xml
<plugin>
    <groupId>org.sonarsource.scanner.maven</groupId>
    <artifactId>sonar-maven-plugin</artifactId>
    <version><sonar-maven-plugin-version></version>
</plugin>
```

Never a `sonar-project.properties` file on Maven — the plugin reads the pom.

## Container image (`distribution: registry`)

`./mvnw quarkus:add-extension -Dextensions=container-image-jib` (or add `io.quarkus:quarkus-container-image-jib`
without a version). Nothing else in the pom: image coordinates are passed by CI (`-Dquarkus.container-image.*`, see
`iru-setup-quarkus-github-workflows`), so a local `./mvnw package` never builds or pushes an image by accident.
Verified: `./mvnw package -DskipTests -Dquarkus.container-image.build=true` produced
`<group>/<artifact-id>:<tag>` in the local Docker daemon from the default `ubi9/openjdk-<java>-runtime` base.
`imageBuilder: docker` instead keeps the extension out and uses `src/main/docker/Dockerfile.jvm` from CI.

## Native (`native: true`)

Nothing to add — `create`'s `native` profile already sets `quarkus.native.enabled=true` and `skipITs=false`, so
`./mvnw verify -Pnative -Dquarkus.native.container-build=true` builds the executable inside a Mandrel builder
container and runs the `*IT` classes against it. JaCoCo can't measure it; coverage stays with the JVM run.

## Commands the rest of the pipeline uses

```bash
./mvnw verify                                   # unit + @QuarkusTest (Dev Services), coverage merge/report/80% check; ITs skipped (skipITs=true)
./mvnw verify -DskipITs=false                   # + Failsafe @QuarkusIntegrationTest against the packaged jar  (ci-tests: full)
# ci-tests: unit — no containers: Dev Services off, every @QuarkusTest/@QuarkusMainTest class excluded
grep -rlE '^[[:space:]]*@Quarkus(Main)?Test([^A-Za-z0-9_]|$)' --include='*.java' --include='*.kt' . \
  | sed -E 's#^(.*/)?src/test/(java|kotlin)/##; s#\.(java|kt)$#.class#' > "$TMPDIR/quarkus-tests.excludes"
./mvnw verify -DskipITs -Dquarkus.devservices.enabled=false -Dsurefire.excludesFile="$TMPDIR/quarkus-tests.excludes"
./mvnw checkstyle:checkstyle pmd:pmd spotbugs:spotbugs   # Java (after compile)
./mvnw javadoc:javadoc                                   # Java docs gate
./mvnw dokka:dokka                                       # Kotlin docs (target/dokka)
./mvnw site -DskipTests -Djacoco.skip                    # Maven site report (target/site)
```

The grep anchors the annotation at the start of a line on purpose: an unanchored pattern also matches Javadoc/KDoc
that merely *mentions* `@QuarkusTest` and silently drops a plain test from the unit run (seen during verification).
