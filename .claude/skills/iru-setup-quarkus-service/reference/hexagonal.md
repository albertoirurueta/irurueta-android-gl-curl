# Hexagonal (DDD / ports-and-adapters) multi-module layout

Used when `quarkus-stack.yml` has `layout: hexagonal`. It mirrors `iru-setup-java-springboot`'s module table, mapped
onto Quarkus's build model — and onto the module names `iru-quarkus-code-one-task/reference/hexagonal-architecture.md`
expects, so the coding skills find what they look for. The rules themselves (what may import what, where a piece of
work goes) live in that reference file; this one is only about *generating* the layout.

Verified on Quarkus 3.40.1 with a PostgreSQL + Flyway sample spanning every layer: **Java on Maven** (`./mvnw verify
-DskipITs=false`, the unit-only command, Checkstyle/PMD/SpotBugs/Javadoc, `site site:stage`, and an ArchUnit negative
test — a class in `..api..` touching `..infrastructure..` failed the build) and **Kotlin on Gradle**
(`./gradlew check :app:quarkusIntTest :dokkaGeneratePublicationHtml`, detekt + ktlint clean). In both, beans,
resources, and a Panache **entity** living in library modules were discovered by the app module only thanks to the
Jandex index. Java on Gradle and Kotlin on Maven are assembled from the same, individually verified pieces
(`gradle.md`/`maven.md` blocks) but were not run as a hexagonal build — say so in the report when generating them.

## Module table

`<a>` = artifactId. Create only the modules the stack calls for (`domain`, `application`, `app`, `coverage` always).

| Directory | Maven artifactId (packaging) | Gradle path | May depend on | Jandex index |
|---|---|---|---|---|
| `.` | `<a>-parent` (`pom`) | root project | — declares every version | — |
| `domain/` | `<a>-domain` (`jar`) | `:domain` | nothing in this project — plain Java/Kotlin, no Quarkus/Jakarta | no |
| `application/` | `<a>-application` (`jar`) | `:application` | `domain` — framework-free use cases, produced as beans by `app` | no |
| `infrastructure/` | `<a>-infrastructure` (`pom`) | (directory only) | — aggregator | — |
| `infrastructure/database/<engine>/` | `<a>-infrastructure-database-<engine>` | `:infrastructure:database:<engine>` | `domain` | **yes** (entities + repositories) |
| `infrastructure/configuration/` | `<a>-infrastructure-configuration` | `:infrastructure:configuration` | `domain` | **yes** |
| `infrastructure/clients/<name>/` | `<a>-infrastructure-clients-<name>` | `:infrastructure:clients:<name>` | `domain` | **yes** |
| `infrastructure/messaging/` | `<a>-infrastructure-messaging` | `:infrastructure:messaging` | `domain` (outgoing `@Channel Emitter`) | **yes** |
| `api/` | `<a>-api` (`pom`) | (directory only) | — aggregator | — |
| `api/rest/` | `<a>-api-rest` | `:api:rest` | `application`, `domain` | **yes** |
| `api/grpc/` | `<a>-api-grpc` | `:api:grpc` | `application`, `domain` | **yes** |
| `api/messaging/` | `<a>-api-messaging` | `:api:messaging` | `application`, `domain` (`@Incoming`) | **yes** |
| `app/` | `<a>-app` (`quarkus`) | `:app` | every module — the only one applying `quarkus-maven-plugin` / `id("io.quarkus")`; owns `application.properties`, `db/migration`, CDI producers, `src/main/docker/`, the ArchUnit test and every `@QuarkusTest`/`@QuarkusIntegrationTest` | no (it's augmented) |
| `coverage/` | `<a>-coverage` (`pom`) | — (Maven only) | every module, report-aggregate only | — |

Packages: `<base-package>.domain.<feature>`, `<base-package>.application.<feature>`,
`<base-package>.infrastructure.<adapter>[.<engine|name>]`, `<base-package>.api.<entry>`, and `<base-package>` for
the app module (producers). Extensions go to the module that uses them (`quarkus-hibernate-orm-panache` → the
database module, `quarkus-rest` → `api/rest`), the JDBC driver/Flyway/health/container-image extensions → `app`.

Why Jandex: only the app module runs Quarkus build-time augmentation; beans, `@Path` resources, `@ConfigMapping`s
and `@Entity` classes in a dependency jar are invisible to it unless that jar carries `META-INF/jandex.idx`. A module
that "loses" its beans at build time is almost always a missing index.

## Maven

### Parent `pom.xml`

The single-module pom's blocks (`maven.md`) moved here: BOM import, pinned plugin versions, the shared test stack,
coverage (`merge`/`report`/`check` in every module), quality plugins, reporting, the `native` profile; plus
`dependencyManagement` entries for every module, `jandex-maven-plugin` in `pluginManagement`, and a
`distributionManagement/site` URL — `site:stage` (CI's docs job) refuses to run without one. Add the identity block
(`name`/`description`/`url`/`licenses`/`developers`/`scm`) and Sonar properties/plugin exactly as in `maven.md`.


`pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId><group-id></groupId>
    <artifactId><artifact-id>-parent</artifactId>
    <version><project-version></version>
    <packaging>pom</packaging>
    <name><artifact-id></name>
    <description><project-description></description>

    <modules>
        <module>domain</module>
        <module>application</module>
        <module>infrastructure</module>
        <module>api</module>
        <module>app</module>
        <module>coverage</module>
    </modules>

    <!-- Only used by `mvn site:stage` to lay out the multi-module Maven site under target/staging (CI docs job). -->
    <distributionManagement>
        <site>
            <id>project-site</id>
            <url>https://<owner>.github.io/<repo>/mvn-site/</url>
        </site>
    </distributionManagement>

    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
        <quarkus.platform.artifact-id>quarkus-bom</quarkus.platform.artifact-id>
        <quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>
        <quarkus.platform.version><platform-version></quarkus.platform.version>
        <skipITs>true</skipITs>
        <compiler-plugin.version>3.15.0</compiler-plugin.version>
        <surefire-plugin.version>3.5.6</surefire-plugin.version>
        <jandex-plugin.version>3.6.0</jandex-plugin.version>
        <archunit.version>1.5.1</archunit.version>
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
        <!-- every module resolves checkstyle.xml from the reactor root -->
        <checkstyle.config.location>${maven.multiModuleProjectDirectory}/checkstyle.xml</checkstyle.config.location>
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
            <dependency>
                <groupId><group-id></groupId>
                <artifactId><artifact-id>-domain</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId><group-id></groupId>
                <artifactId><artifact-id>-application</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId><group-id></groupId>
                <artifactId><artifact-id>-infrastructure-configuration</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId><group-id></groupId>
                <artifactId><artifact-id>-infrastructure-database-postgresql</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId><group-id></groupId>
                <artifactId><artifact-id>-api-rest</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId><group-id></groupId>
                <artifactId><artifact-id>-app</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.tngtech.archunit</groupId>
                <artifactId>archunit-junit5</artifactId>
                <version>${archunit.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <!-- Test stack shared by every module: JUnit 5 (via quarkus-junit), Mockito, component tests, coverage -->
    <dependencies>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit</artifactId>
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
    </dependencies>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>${quarkus.platform.group-id}</groupId>
                    <artifactId>quarkus-maven-plugin</artifactId>
                    <version>${quarkus.platform.version}</version>
                    <extensions>true</extensions>
                </plugin>
                <!-- Library modules that contribute CDI beans, resources or entities are indexed, because only the
                     app module runs Quarkus augmentation. -->
                <plugin>
                    <groupId>io.smallrye</groupId>
                    <artifactId>jandex-maven-plugin</artifactId>
                    <version>${jandex-plugin.version}</version>
                    <executions>
                        <execution>
                            <id>make-index</id>
                            <goals>
                                <goal>jandex</goal>
                            </goals>
                        </execution>
                    </executions>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
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
        </pluginManagement>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>${compiler-plugin.version}</version>
                <configuration>
                    <parameters>true</parameters>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>${surefire-plugin.version}</version>
                <configuration>
                    <argLine>@{argLine}</argLine>
                    <systemPropertyVariables>
                        <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
                        <maven.home>${maven.home}</maven.home>
                        <jacoco-agent.destfile>${project.build.directory}/jacoco-quarkus.exec</jacoco-agent.destfile>
                    </systemPropertyVariables>
                </configuration>
            </plugin>
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
                            <haltOnFailure>true</haltOnFailure>
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
                    <configLocation>${checkstyle.config.location}</configLocation>
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
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-checkstyle-plugin</artifactId>
                <version>${checkstyle-plugin.version}</version>
                <configuration>
                    <configLocation>${checkstyle.config.location}</configLocation>
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

### Module poms

Aggregators (`infrastructure/pom.xml`, `infrastructure/database/pom.xml`, `api/pom.xml`) are `pom`-packaged with only
`<modules>`; their `<parent>` is the directory above. A library module that holds beans/resources/entities:

`infrastructure/database/postgresql/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId><group-id></groupId>
        <artifactId><artifact-id>-infrastructure-database</artifactId>
        <version><project-version></version>
    </parent>
    <artifactId><artifact-id>-infrastructure-database-postgresql</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId><group-id></groupId>
            <artifactId><artifact-id>-domain</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-hibernate-orm-panache</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>io.smallrye</groupId>
                <artifactId>jandex-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

`api/rest/pom.xml (add quarkus-junit-component to any module whose tests use @QuarkusComponentTest)`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId><group-id></groupId>
        <artifactId><artifact-id>-api</artifactId>
        <version><project-version></version>
    </parent>
    <artifactId><artifact-id>-api-rest</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId><group-id></groupId>
            <artifactId><artifact-id>-application</artifactId>
        </dependency>
        <dependency>
            <groupId><group-id></groupId>
            <artifactId><artifact-id>-domain</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest-jackson</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit-component</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>io.smallrye</groupId>
                <artifactId>jandex-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

`app/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId><group-id></groupId>
        <artifactId><artifact-id>-parent</artifactId>
        <version><project-version></version>
    </parent>
    <artifactId><artifact-id>-app</artifactId>
    <packaging>quarkus</packaging>

    <dependencies>
        <dependency>
            <groupId><group-id></groupId>
            <artifactId><artifact-id>-domain</artifactId>
        </dependency>
        <dependency>
            <groupId><group-id></groupId>
            <artifactId><artifact-id>-application</artifactId>
        </dependency>
        <dependency>
            <groupId><group-id></groupId>
            <artifactId><artifact-id>-infrastructure-configuration</artifactId>
        </dependency>
        <dependency>
            <groupId><group-id></groupId>
            <artifactId><artifact-id>-infrastructure-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId><group-id></groupId>
            <artifactId><artifact-id>-api-rest</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-arc</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-jdbc-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-smallrye-health</artifactId>
        </dependency>
        <dependency>
            <groupId>io.rest-assured</groupId>
            <artifactId>rest-assured</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.tngtech.archunit</groupId>
            <artifactId>archunit-junit5</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>${quarkus.platform.group-id}</groupId>
                <artifactId>quarkus-maven-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-failsafe-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

`coverage/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId><group-id></groupId>
        <artifactId><artifact-id>-parent</artifactId>
        <version><project-version></version>
    </parent>
    <artifactId><artifact-id>-coverage</artifactId>
    <packaging>pom</packaging>

    <description>Aggregated JaCoCo report across every module (report only; each module enforces its own 80% check).</description>

    <dependencies>
        <dependency>
            <groupId><group-id></groupId>
            <artifactId><artifact-id>-domain</artifactId>
        </dependency>
        <dependency>
            <groupId><group-id></groupId>
            <artifactId><artifact-id>-application</artifactId>
        </dependency>
        <dependency>
            <groupId><group-id></groupId>
            <artifactId><artifact-id>-infrastructure-configuration</artifactId>
        </dependency>
        <dependency>
            <groupId><group-id></groupId>
            <artifactId><artifact-id>-infrastructure-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId><group-id></groupId>
            <artifactId><artifact-id>-api-rest</artifactId>
        </dependency>
        <dependency>
            <groupId><group-id></groupId>
            <artifactId><artifact-id>-app</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <id>report-aggregate</id>
                        <phase>verify</phase>
                        <goals>
                            <goal>report-aggregate</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

`checkstyle.xml` stays at the repository root (`quality-config.md`); the parent resolves it for every module through
`${maven.multiModuleProjectDirectory}`. Copy `src/main/docker/` from the `create` output into `app/src/main/docker/`
(its `COPY target/quarkus-app/…` lines are relative to the module, which is the Docker build context CI uses).

### Java sample, module by module

Every module carries a test so each one's 80% check passes from the unit stage alone; the app module's
`@QuarkusTest` exercises the whole chain against a Dev Services database.

`domain/src/main/java/<base-package-path>/domain/greeting/package-info.java`

```java
/**
 * Greeting domain: the {@link <base-package>.domain.greeting.Greeting} value object and the ports the
 * outer layers implement. Plain Java — no framework types.
 */
package <base-package>.domain.greeting;
```

`domain/src/main/java/<base-package-path>/domain/greeting/Greeting.java`

```java
package <base-package>.domain.greeting;

import java.util.Objects;

/**
 * A greeting addressed to someone.
 *
 * @param name    name being greeted; never blank.
 * @param message full greeting message.
 */
public record Greeting(String name, String message) {

    /**
     * Validates the greeting.
     *
     * @param name    name being greeted; never blank.
     * @param message full greeting message.
     * @throws IllegalArgumentException if {@code name} is blank.
     */
    public Greeting {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(message, "message");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
```

`domain/src/main/java/<base-package-path>/domain/greeting/DefaultNameProvider.java`

```java
package <base-package>.domain.greeting;

/**
 * Port: supplies the name greeted when the caller gives none.
 */
public interface DefaultNameProvider {

    /**
     * Returns the default name.
     *
     * @return the default name; never blank.
     */
    String defaultName();
}
```

`domain/src/main/java/<base-package-path>/domain/greeting/GreetingRepository.java`

```java
package <base-package>.domain.greeting;

/**
 * Port: records every greeting handed out.
 */
public interface GreetingRepository {

    /**
     * Records a greeting.
     *
     * @param greeting greeting to record.
     */
    void record(Greeting greeting);
}
```

`domain/src/test/java/<base-package-path>/domain/greeting/GreetingTest.java`

```java
package <base-package>.domain.greeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Greeting}.
 */
class GreetingTest {

    /**
     * Verifies that a valid greeting keeps its values.
     */
    @Test
    void constructor_whenValid_keepsValues() {
        final var greeting = new Greeting("Ann", "Hello Ann");

        assertEquals("Ann", greeting.name());
        assertEquals("Hello Ann", greeting.message());
    }

    /**
     * Verifies that a blank name is rejected.
     */
    @Test
    void constructor_whenNameBlank_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> new Greeting(" ", "Hello"));
    }
}
```

`application/src/main/java/<base-package-path>/application/greeting/package-info.java`

```java
/**
 * Greeting use cases. Framework-free: the app module produces them as CDI beans.
 */
package <base-package>.application.greeting;
```

`application/src/main/java/<base-package-path>/application/greeting/GreetUseCase.java`

```java
package <base-package>.application.greeting;

import <base-package>.domain.greeting.DefaultNameProvider;
import <base-package>.domain.greeting.Greeting;
import <base-package>.domain.greeting.GreetingRepository;

/**
 * Use case: greet someone (or the default name) and record the greeting.
 */
public class GreetUseCase {

    /**
     * Port supplying the default name.
     */
    private final DefaultNameProvider defaultNameProvider;

    /**
     * Port recording greetings.
     */
    private final GreetingRepository greetingRepository;

    /**
     * Creates the use case.
     *
     * @param defaultNameProvider port supplying the default name.
     * @param greetingRepository  port recording greetings.
     */
    public GreetUseCase(final DefaultNameProvider defaultNameProvider, final GreetingRepository greetingRepository) {
        this.defaultNameProvider = defaultNameProvider;
        this.greetingRepository = greetingRepository;
    }

    /**
     * Greets {@code name}, or the default name when it is {@code null} or blank, and records the greeting.
     *
     * @param name name to greet; may be {@code null}.
     * @return the greeting handed out.
     */
    public Greeting greet(final String name) {
        final var target = name == null || name.isBlank() ? defaultNameProvider.defaultName() : name;
        final var greeting = new Greeting(target, "Hello " + target);
        greetingRepository.record(greeting);
        return greeting;
    }
}
```

`application/src/test/java/<base-package-path>/application/greeting/GreetUseCaseTest.java`

```java
package <base-package>.application.greeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import <base-package>.domain.greeting.DefaultNameProvider;
import <base-package>.domain.greeting.Greeting;
import <base-package>.domain.greeting.GreetingRepository;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GreetUseCase}.
 */
class GreetUseCaseTest {

    /**
     * Verifies that a given name is greeted and recorded.
     */
    @Test
    void greet_whenNameGiven_greetsAndRecords() {
        final var provider = mock(DefaultNameProvider.class);
        final var repository = mock(GreetingRepository.class);
        final var useCase = new GreetUseCase(provider, repository);

        final var greeting = useCase.greet("Ann");

        assertEquals(new Greeting("Ann", "Hello Ann"), greeting);
        verify(repository).record(greeting);
    }

    /**
     * Verifies that a missing name falls back to the default name.
     */
    @Test
    void greet_whenNameMissing_usesDefaultName() {
        final var provider = mock(DefaultNameProvider.class);
        when(provider.defaultName()).thenReturn("World");
        final var useCase = new GreetUseCase(provider, mock(GreetingRepository.class));

        assertEquals("Hello World", useCase.greet(" ").message());
        assertEquals("Hello World", useCase.greet(null).message());
    }
}
```

`infrastructure/configuration/src/main/java/<base-package-path>/infrastructure/configuration/package-info.java`

```java
/**
 * Configuration adapter: {@code @ConfigMapping} interfaces and the domain ports they implement.
 */
package <base-package>.infrastructure.configuration;
```

`infrastructure/configuration/src/main/java/<base-package-path>/infrastructure/configuration/GreetingConfig.java`

```java
package <base-package>.infrastructure.configuration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration of the greeting feature, bound from the {@code greeting.*} keys.
 */
@ConfigMapping(prefix = "greeting")
public interface GreetingConfig {

    /**
     * Name greeted when the caller doesn't supply one.
     *
     * @return the default name.
     */
    @WithDefault("World")
    String name();
}
```

`infrastructure/configuration/src/main/java/<base-package-path>/infrastructure/configuration/ConfiguredDefaultNameProvider.java`

```java
package <base-package>.infrastructure.configuration;

import <base-package>.domain.greeting.DefaultNameProvider;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Adapter: supplies the default name from {@link GreetingConfig}. Thread-safe; never blocks.
 */
@ApplicationScoped
public class ConfiguredDefaultNameProvider implements DefaultNameProvider {

    /**
     * Configuration holding the default name.
     */
    private final GreetingConfig config;

    /**
     * Creates the adapter.
     *
     * @param config configuration holding the default name.
     */
    public ConfiguredDefaultNameProvider(final GreetingConfig config) {
        this.config = config;
    }

    @Override
    public String defaultName() {
        return config.name();
    }
}
```

`infrastructure/configuration/src/test/java/<base-package-path>/infrastructure/configuration/ConfiguredDefaultNameProviderComponentTest.java`

```java
package <base-package>.infrastructure.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.component.QuarkusComponentTest;
import io.quarkus.test.component.TestConfigProperty;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Component tests for {@link ConfiguredDefaultNameProvider}: the bean with its {@code @ConfigMapping}, no container.
 */
@QuarkusComponentTest
@TestConfigProperty(key = "greeting.name", value = "Quarkus")
class ConfiguredDefaultNameProviderComponentTest {

    /**
     * Bean under test.
     */
    @Inject
    ConfiguredDefaultNameProvider provider;

    /**
     * Verifies that the configured name is returned.
     */
    @Test
    void defaultName_whenConfigured_returnsConfiguredName() {
        assertEquals("Quarkus", provider.defaultName());
    }
}
```

`infrastructure/database/postgresql/src/main/java/<base-package-path>/infrastructure/database/postgresql/package-info.java`

```java
/**
 * PostgreSQL persistence adapter: Panache entities and repositories implementing the domain repository ports.
 */
package <base-package>.infrastructure.database.postgresql;
```

`infrastructure/database/postgresql/src/main/java/<base-package-path>/infrastructure/database/postgresql/GreetingEntity.java`

```java
package <base-package>.infrastructure.database.postgresql;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Persistence model of a recorded greeting. Never leaves this module.
 */
@Entity
@Table(name = "greeting")
public class GreetingEntity extends PanacheEntity {

    /**
     * Name that was greeted.
     */
    @Column(nullable = false)
    public String name;

    /**
     * Message handed out.
     */
    @Column(nullable = false)
    public String message;

    /**
     * Creates an empty entity; required by JPA.
     */
    public GreetingEntity() {
        // fields are assigned by the mapper or by Hibernate
    }
}
```

`infrastructure/database/postgresql/src/main/java/<base-package-path>/infrastructure/database/postgresql/GreetingEntityRepository.java`

```java
package <base-package>.infrastructure.database.postgresql;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Panache repository of {@link GreetingEntity}.
 */
@ApplicationScoped
public class GreetingEntityRepository implements PanacheRepository<GreetingEntity> {

    /**
     * Creates the repository; every query method is inherited from {@link PanacheRepository}.
     */
    public GreetingEntityRepository() {
        // no state
    }
}
```

`infrastructure/database/postgresql/src/main/java/<base-package-path>/infrastructure/database/postgresql/PanacheGreetingRepositoryAdapter.java`

```java
package <base-package>.infrastructure.database.postgresql;

import <base-package>.domain.greeting.Greeting;
import <base-package>.domain.greeting.GreetingRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Adapter: records greetings in PostgreSQL. Blocking; runs on a worker thread.
 */
@ApplicationScoped
public class PanacheGreetingRepositoryAdapter implements GreetingRepository {

    /**
     * Panache repository the adapter writes through.
     */
    private final GreetingEntityRepository repository;

    /**
     * Creates the adapter.
     *
     * @param repository Panache repository the adapter writes through.
     */
    public PanacheGreetingRepositoryAdapter(final GreetingEntityRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void record(final Greeting greeting) {
        repository.persist(toEntity(greeting));
    }

    /**
     * Maps a domain greeting to its persistence model.
     *
     * @param greeting domain greeting.
     * @return the new, unsaved entity.
     */
    static GreetingEntity toEntity(final Greeting greeting) {
        final var entity = new GreetingEntity();
        entity.name = greeting.name();
        entity.message = greeting.message();
        return entity;
    }
}
```

`infrastructure/database/postgresql/src/test/java/<base-package-path>/infrastructure/database/postgresql/PanacheGreetingRepositoryAdapterTest.java`

```java
package <base-package>.infrastructure.database.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import <base-package>.domain.greeting.Greeting;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PanacheGreetingRepositoryAdapter}: mapping and delegation, with the Panache repository mocked.
 */
class PanacheGreetingRepositoryAdapterTest {

    /**
     * Verifies that recording persists the mapped entity.
     */
    @Test
    void record_whenCalled_persistsMappedEntity() {
        final var repository = mock(GreetingEntityRepository.class);
        final var adapter = new PanacheGreetingRepositoryAdapter(repository);

        adapter.record(new Greeting("Ann", "Hello Ann"));

        verify(repository).persist(argThat((GreetingEntity e) -> "Ann".equals(e.name)));
    }

    /**
     * Verifies the domain-to-entity mapping.
     */
    @Test
    void toEntity_whenCalled_copiesFields() {
        final var entity = PanacheGreetingRepositoryAdapter.toEntity(new Greeting("Ann", "Hello Ann"));

        assertEquals("Ann", entity.name);
        assertEquals("Hello Ann", entity.message);
    }
}
```

`api/rest/src/main/java/<base-package-path>/api/rest/package-info.java`

```java
/**
 * REST entry points: resources calling use cases, never adapters.
 */
package <base-package>.api.rest;
```

`api/rest/src/main/java/<base-package-path>/api/rest/GreetingResource.java`

```java
package <base-package>.api.rest;

import <base-package>.application.greeting.GreetUseCase;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * REST entry point of the greeting use case.
 */
@Path("/hello")
public class GreetingResource {

    /**
     * Use case answering the request.
     */
    private final GreetUseCase greetUseCase;

    /**
     * Creates the resource.
     *
     * @param greetUseCase use case answering the request.
     */
    public GreetingResource(final GreetUseCase greetUseCase) {
        this.greetUseCase = greetUseCase;
    }

    /**
     * Returns a greeting for the given name.
     *
     * @param name optional name to greet.
     * @return the greeting message as plain text.
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello(@QueryParam("name") final String name) {
        return greetUseCase.greet(name).message();
    }
}
```

`api/rest/src/test/java/<base-package-path>/api/rest/GreetingResourceTest.java`

```java
package <base-package>.api.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import <base-package>.application.greeting.GreetUseCase;
import <base-package>.domain.greeting.Greeting;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GreetingResource} with the use case mocked. The HTTP contract is tested by the app
 * module's {@code @QuarkusTest}.
 */
class GreetingResourceTest {

    /**
     * Verifies that the resource returns the use case's message.
     */
    @Test
    void hello_whenCalled_returnsUseCaseMessage() {
        final var useCase = mock(GreetUseCase.class);
        when(useCase.greet("Ann")).thenReturn(new Greeting("Ann", "Hello Ann"));

        assertEquals("Hello Ann", new GreetingResource(useCase).hello("Ann"));
    }
}
```

`app/src/main/java/<base-package-path>/package-info.java`

```java
/**
 * Composition root: CDI producers wiring framework-free use cases to their adapters.
 */
package <base-package>;
```

`app/src/main/java/<base-package-path>/UseCaseProducers.java`

```java
package <base-package>;

import <base-package>.application.greeting.GreetUseCase;
import <base-package>.domain.greeting.DefaultNameProvider;
import <base-package>.domain.greeting.GreetingRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Produces the application module's framework-free use cases as CDI beans.
 */
@ApplicationScoped
public class UseCaseProducers {

    /**
     * Creates the producer bean.
     */
    public UseCaseProducers() {
        // no state
    }

    /**
     * Produces the greeting use case.
     *
     * @param defaultNameProvider adapter supplying the default name.
     * @param greetingRepository  adapter recording greetings.
     * @return the use case bean.
     */
    @Produces
    @ApplicationScoped
    GreetUseCase greetUseCase(final DefaultNameProvider defaultNameProvider,
            final GreetingRepository greetingRepository) {
        return new GreetUseCase(defaultNameProvider, greetingRepository);
    }
}
```

`app/src/main/resources/db/migration/V1__greeting.sql`

```sql
CREATE SEQUENCE greeting_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE greeting (
    id      BIGINT       NOT NULL PRIMARY KEY,
    name    VARCHAR(255) NOT NULL,
    message VARCHAR(255) NOT NULL
);
```

`app/src/main/resources/application.properties`

```properties
quarkus.application.name=<artifact-id>

# Sample feature (replace with the service's real configuration)
greeting.name=World

# Persistence — PostgreSQL via Hibernate ORM with Panache (entities in infrastructure/database/postgresql, indexed by
# Jandex), schema owned by Flyway. Dev and test get a Dev Services container (no URL configured). Production settings
# come only from the environment: QUARKUS_DATASOURCE_JDBC_URL, QUARKUS_DATASOURCE_USERNAME, QUARKUS_DATASOURCE_PASSWORD.
quarkus.datasource.db-kind=postgresql
quarkus.hibernate-orm.schema-management.strategy=validate
quarkus.flyway.migrate-at-start=true

%dev.quarkus.log.console.json.enabled=false

# Coverage: one data set per module merged by jacoco-maven-plugin (see the parent pom); no second quarkus-jacoco report.
%test.quarkus.jacoco.report=false
```

`app/src/test/java/<base-package-path>/UseCaseProducersTest.java`

```java
package <base-package>;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import <base-package>.domain.greeting.DefaultNameProvider;
import <base-package>.domain.greeting.GreetingRepository;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link UseCaseProducers}.
 */
class UseCaseProducersTest {

    /**
     * Verifies that the greeting use case is produced.
     */
    @Test
    void greetUseCase_whenCalled_returnsUseCase() {
        final var producers = new UseCaseProducers();

        assertNotNull(producers.greetUseCase(mock(DefaultNameProvider.class), mock(GreetingRepository.class)));
    }
}
```

`app/src/test/java/<base-package-path>/ArchitectureTest.java`

```java
package <base-package>;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces the hexagonal dependency direction across every module on the app module's classpath.
 */
@AnalyzeClasses(packages = "<base-package>", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * The domain depends on no framework.
     */
    @ArchTest
    static final ArchRule DOMAIN_IS_FRAMEWORK_FREE = noClasses().that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage("io.quarkus..", "jakarta.enterprise..",
            "jakarta.persistence..", "jakarta.ws.rs..", "io.smallrye.mutiny..",
            "..application..", "..infrastructure..", "..api..");

    /**
     * Use cases never reach outward to adapters or entry points.
     */
    @ArchTest
    static final ArchRule APPLICATION_DEPENDS_ONLY_INWARD = noClasses().that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "..api..");

    /**
     * Entry points call use cases, never adapters.
     */
    @ArchTest
    static final ArchRule API_DOES_NOT_USE_INFRASTRUCTURE = noClasses().that().resideInAPackage("..api..")
        .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    /**
     * Adapters never depend on entry points.
     */
    @ArchTest
    static final ArchRule INFRASTRUCTURE_DOES_NOT_USE_API = noClasses().that().resideInAPackage("..infrastructure..")
        .should().dependOnClassesThat().resideInAPackage("..api..");

    /**
     * Adapters are independent of each other.
     */
    @ArchTest
    static final ArchRule ADAPTERS_ARE_INDEPENDENT = slices().matching("..infrastructure.(**)")
        .should().notDependOnEachOther();
}
```

`app/src/test/java/<base-package-path>/api/GreetingResourceTest.java`

```java
package <base-package>.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Full-application tests of the greeting endpoint: REST resource, use case, configuration and PostgreSQL adapter
 * wired together. Starts a PostgreSQL Dev Services container.
 */
@QuarkusTest
class GreetingResourceTest {

    /**
     * Expected HTTP status of a successful request.
     */
    private static final int HTTP_OK = 200;

    /**
     * Verifies the HTTP contract of {@code GET /hello} with the configured default name.
     */
    @Test
    void hello_whenNameMissing_returnsDefaultGreeting() {
        given()
            .when().get("/hello")
            .then()
            .statusCode(HTTP_OK)
            .body(is("Hello World"));
    }
}
```

`app/src/test/java/<base-package-path>/api/GreetingResourceIT.java`

```java
package <base-package>.api;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Re-runs {@link GreetingResourceTest} against the packaged application.
 */
@QuarkusIntegrationTest
class GreetingResourceIT extends GreetingResourceTest {
}
```

## Gradle (Kotlin DSL)

No `coverage` project: each module writes its own `build/reports/jacoco/test/jacocoTestReport.xml` and the Sonar
Gradle plugin picks every module's report up by default; Dokka aggregates the whole service from the root
(`./gradlew :dokkaGeneratePublicationHtml` → `build/dokka/html`). Module boundaries are enforced by the ArchUnit test
(there is no enforcer plugin on Gradle) and by declaring `project(":…")` dependencies exactly per the table. Cross-
project configuration lives in the root `subprojects {}` block — one file to read instead of a `build-logic` included
build; move it to convention plugins if the build grows. Keep `gradlew`, `gradlew.bat`, `gradle/wrapper/*` from the
`create` output, and `src/main/docker/` under `app/`.

`settings.gradle.kts`

```kotlin
pluginManagement {
    val quarkusPluginVersion: String by settings
    val quarkusPluginId: String by settings
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id(quarkusPluginId) version quarkusPluginVersion
    }
}

rootProject.name = "<artifact-id>"

include(
    ":domain",
    ":application",
    ":infrastructure:configuration",
    ":infrastructure:database:postgresql",
    ":api:rest",
    ":app",
)
```

`gradle.properties`

```properties
# Project coordinates — the single place the version lives (read by iru-java-bump-version and the Sync workflow)
group=<group-id>
version=<project-version>

quarkusPluginId=io.quarkus
quarkusPluginVersion=<platform-version>
quarkusPlatformGroupId=io.quarkus.platform
quarkusPlatformArtifactId=quarkus-bom
quarkusPlatformVersion=<platform-version>
```

`build.gradle.kts (root) — Kotlin modules`

```kotlin
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.getSupportedKotlinVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

// Root of the hexagonal multi-project build (iru-setup-quarkus-service). Every module with sources gets the same
// Kotlin, test, coverage and quality setup here; only :app applies io.quarkus, and the library modules that hold
// CDI beans, resources or entities are Jandex-indexed in their own build file.
plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.allopen") version "2.4.10" apply false
    kotlin("plugin.noarg") version "2.4.10" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
    id("org.jetbrains.dokka") version "2.2.0"
    id("org.kordamp.gradle.jandex") version "2.3.0" apply false
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

// Aggregator directories (:infrastructure, :api, :infrastructure:database) have no build file and no sources.
val codeModules =
    setOf(
        ":domain",
        ":application",
        ":infrastructure:configuration",
        ":infrastructure:database:postgresql",
        ":api:rest",
        ":app",
    )

subprojects {
    if (path !in codeModules) return@subprojects

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.allopen")
    apply(plugin = "org.jetbrains.kotlin.plugin.noarg")
    apply(plugin = "jacoco")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "org.jetbrains.dokka")

    repositories {
        mavenCentral()
    }

    dependencies {
        "implementation"(enforcedPlatform("$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"))
        "testImplementation"("io.quarkus:quarkus-junit")
        "testImplementation"("io.quarkus:quarkus-junit-component")
        "testImplementation"("io.quarkus:quarkus-junit-mockito")
        "testImplementation"("io.quarkus:quarkus-jacoco")
        "testImplementation"("io.mockk:mockk-jvm:1.14.11")
    }

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    extensions.configure<KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
            javaParameters = true
        }
    }

    extensions.configure<org.jetbrains.kotlin.allopen.gradle.AllOpenExtension> {
        annotation("jakarta.ws.rs.Path")
        annotation("jakarta.enterprise.context.ApplicationScoped")
        annotation("jakarta.enterprise.context.RequestScoped")
        annotation("jakarta.persistence.Entity")
        annotation("io.quarkus.test.junit.QuarkusTest")
    }

    extensions.configure<org.jetbrains.kotlin.noarg.gradle.NoArgExtension> {
        annotation("jakarta.persistence.Entity")
        annotation("jakarta.persistence.Embeddable")
        annotation("jakarta.persistence.MappedSuperclass")
    }

    // Coverage: same scheme as the single-module build — agent data + quarkus-jacoco data, merged, 80% line check.
    val quarkusJacocoData = layout.buildDirectory.file("jacoco-quarkus.exec")
    val jacocoExecutionData = fileTree(layout.buildDirectory) { include("jacoco/test.exec", "jacoco-quarkus.exec") }

    tasks.named<Test>("test") {
        useJUnitPlatform()
        systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
        systemProperty("jacoco-agent.destfile", quarkusJacocoData.get().asFile.absolutePath)
        doFirst { delete(quarkusJacocoData) }
        extensions.configure<JacocoTaskExtension> {
            excludeClassLoaders = listOf("*QuarkusClassLoader")
        }
        // ci-tests: unit — CI passes -PexcludeTestsFile=<file> listing the @QuarkusTest classes
        providers.gradleProperty("excludeTestsFile").orNull?.let { path ->
            rootProject.file(path).readLines().filter { it.isNotBlank() }.forEach { exclude(it.trim()) }
        }
        finalizedBy("jacocoTestReport")
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn("test")
        executionData.setFrom(jacocoExecutionData)
        reports {
            xml.required = true
            csv.required = true
            html.required = true
        }
    }

    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        dependsOn("test")
        executionData.setFrom(jacocoExecutionData)
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = "0.80".toBigDecimal()
                }
            }
        }
    }

    tasks.named("check") {
        dependsOn("jacocoTestCoverageVerification")
    }

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("detekt.yml"))
        source.setFrom("src/main/kotlin", "src/test/kotlin", "src/integrationTest/kotlin")
    }

    configurations.matching { it.name == "detekt" }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(getSupportedKotlinVersion())
            }
        }
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "21"
        reports {
            xml.required = true
            html.required = true
            sarif.required = false
            md.required = false
            txt.required = false
        }
    }

    extensions.configure<KtlintExtension> {
        reporters {
            reporter(ReporterType.PLAIN)
            reporter(ReporterType.CHECKSTYLE)
        }
    }

    extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
        dokkaSourceSets.configureEach {
            includes.from("module.md")
        }
    }
}

repositories {
    mavenCentral()
}

// One Dokka site for the whole service: ./gradlew :dokkaGeneratePublicationHtml → build/dokka/html
dependencies {
    codeModules.forEach { dokka(project(it)) }
}
```

`domain/build.gradle.kts`

```kotlin
// domain: plain Kotlin — no Quarkus, no Jakarta. Depends on nothing in this project.
```

`application/build.gradle.kts`

```kotlin
// application: framework-free use cases; the app module produces them as CDI beans.
dependencies {
    implementation(project(":domain"))
}
```

`infrastructure/database/postgresql/build.gradle.kts`

```kotlin
plugins {
    id("org.kordamp.gradle.jandex")
}

dependencies {
    implementation(project(":domain"))
    implementation("io.quarkus:quarkus-hibernate-orm-panache-kotlin")
}
```

`api/rest/build.gradle.kts`

```kotlin
plugins {
    id("org.kordamp.gradle.jandex")
}

dependencies {
    implementation(project(":application"))
    implementation(project(":domain"))
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
}
```

`app/build.gradle.kts`

```kotlin
plugins {
    id("io.quarkus")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":infrastructure:configuration"))
    implementation(project(":infrastructure:database:postgresql"))
    implementation(project(":api:rest"))
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
}
```

`infrastructure/configuration/build.gradle.kts` is the database module's file with `quarkus-arc` instead of the
Panache extension (`quarkus-junit-component` is already in the root block for every module). **Java on Gradle**: same structure, but the root `subprojects {}` applies `java`, `jacoco`,
`checkstyle`, `pmd`, `com.github.spotbugs` and configures them exactly as `gradle.md`'s Java build does (Checkstyle
`configFile = rootProject.file("checkstyle.xml")`, Javadoc doclint), with no Kotlin/detekt/ktlint/Dokka plugins and
`mockk` dropped (Mockito comes with `quarkus-junit-mockito`) — not run as a whole, see above.

### Kotlin sample, module by module

Each module also gets a `module.md` (`# Module <gradle-project-name>` — `domain`, `configuration`, `postgresql`,
`rest`, `app` — plus a `# Package` section per package) for Dokka.

`domain/src/main/kotlin/<base-package-path>/domain/greeting/Greeting.kt`

```kotlin
package <base-package>.domain.greeting

/**
 * A greeting addressed to someone.
 *
 * @property name name being greeted; never blank.
 * @property message full greeting message.
 */
data class Greeting(
    val name: String,
    val message: String,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
    }
}
```

`domain/src/main/kotlin/<base-package-path>/domain/greeting/Ports.kt`

```kotlin
package <base-package>.domain.greeting

/**
 * Port: supplies the name greeted when the caller gives none.
 */
fun interface DefaultNameProvider {
    /**
     * Returns the default name.
     *
     * @return the default name; never blank.
     */
    fun defaultName(): String
}

/**
 * Port: records every greeting handed out.
 */
fun interface GreetingRepository {
    /**
     * Records [greeting].
     *
     * @param greeting greeting to record.
     */
    fun record(greeting: Greeting)
}
```

`domain/src/test/kotlin/<base-package-path>/domain/greeting/GreetingTest.kt`

```kotlin
package <base-package>.domain.greeting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for [Greeting].
 */
class GreetingTest {
    /** A valid greeting keeps its values. */
    @Test
    fun `valid greeting keeps its values`() {
        assertEquals("Ann", Greeting("Ann", "Hello Ann").name)
    }

    /** A blank name is rejected. */
    @Test
    fun `blank name is rejected`() {
        assertThrows<IllegalArgumentException> { Greeting(" ", "Hello") }
    }
}
```

`application/src/main/kotlin/<base-package-path>/application/greeting/GreetUseCase.kt`

```kotlin
package <base-package>.application.greeting

import <base-package>.domain.greeting.DefaultNameProvider
import <base-package>.domain.greeting.Greeting
import <base-package>.domain.greeting.GreetingRepository

/**
 * Use case: greet someone (or the default name) and record the greeting.
 *
 * @property defaultNameProvider port supplying the default name.
 * @property greetingRepository port recording greetings.
 */
class GreetUseCase(
    private val defaultNameProvider: DefaultNameProvider,
    private val greetingRepository: GreetingRepository,
) {
    /**
     * Greets [name], or the default name when it is `null` or blank, and records the greeting.
     *
     * @param name name to greet.
     * @return the greeting handed out.
     */
    fun greet(name: String?): Greeting {
        val target = name?.takeIf { it.isNotBlank() } ?: defaultNameProvider.defaultName()
        return Greeting(target, "Hello $target").also(greetingRepository::record)
    }
}
```

`application/src/test/kotlin/<base-package-path>/application/greeting/GreetUseCaseTest.kt`

```kotlin
package <base-package>.application.greeting

import <base-package>.domain.greeting.DefaultNameProvider
import <base-package>.domain.greeting.Greeting
import <base-package>.domain.greeting.GreetingRepository
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for [GreetUseCase].
 */
class GreetUseCaseTest {
    /** A given name is greeted and recorded. */
    @Test
    fun `greets and records the given name`() {
        val repository = mockk<GreetingRepository>()
        justRun { repository.record(any()) }
        val greeting = GreetUseCase(mockk(), repository).greet("Ann")

        assertEquals(Greeting("Ann", "Hello Ann"), greeting)
        verify { repository.record(greeting) }
    }

    /** A missing name falls back to the default name. */
    @Test
    fun `falls back to the default name`() {
        val provider = mockk<DefaultNameProvider>()
        every { provider.defaultName() } returns "World"
        val repository = mockk<GreetingRepository>()
        justRun { repository.record(any()) }

        assertEquals("Hello World", GreetUseCase(provider, repository).greet(null).message)
    }
}
```

`infrastructure/configuration/src/main/kotlin/<base-package-path>/infrastructure/configuration/ConfiguredDefaultNameProvider.kt`

```kotlin
package <base-package>.infrastructure.configuration

import <base-package>.domain.greeting.DefaultNameProvider
import jakarta.enterprise.context.ApplicationScoped

/**
 * Adapter: supplies the default name from [GreetingConfig]. Thread-safe; never blocks.
 *
 * @property config configuration holding the default name.
 */
@ApplicationScoped
class ConfiguredDefaultNameProvider(
    private val config: GreetingConfig,
) : DefaultNameProvider {
    override fun defaultName(): String = config.name()
}
```

`infrastructure/database/postgresql/src/main/kotlin/<base-package-path>/infrastructure/database/postgresql/GreetingEntity.kt`

```kotlin
package <base-package>.infrastructure.database.postgresql

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * Persistence model of a recorded greeting. Never leaves this module.
 */
@Entity
@Table(name = "greeting")
class GreetingEntity : PanacheEntity() {
    /** Name that was greeted. */
    @Column(nullable = false)
    lateinit var name: String

    /** Message handed out. */
    @Column(nullable = false)
    lateinit var message: String
}
```

`infrastructure/database/postgresql/src/main/kotlin/<base-package-path>/infrastructure/database/postgresql/GreetingEntityRepository.kt`

```kotlin
package <base-package>.infrastructure.database.postgresql

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

/**
 * Panache repository of [GreetingEntity].
 */
@ApplicationScoped
class GreetingEntityRepository : PanacheRepository<GreetingEntity>
```

`infrastructure/database/postgresql/src/main/kotlin/<base-package-path>/infrastructure/database/postgresql/PanacheGreetingRepositoryAdapter.kt`

```kotlin
package <base-package>.infrastructure.database.postgresql

import <base-package>.domain.greeting.Greeting
import <base-package>.domain.greeting.GreetingRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * Adapter: records greetings in PostgreSQL. Blocking; runs on a worker thread.
 *
 * @property repository Panache repository the adapter writes through.
 */
@ApplicationScoped
class PanacheGreetingRepositoryAdapter(
    private val repository: GreetingEntityRepository,
) : GreetingRepository {
    @Transactional
    override fun record(greeting: Greeting) {
        repository.persist(greeting.toEntity())
    }
}

/**
 * Maps a domain greeting to its persistence model.
 *
 * @return the new, unsaved entity.
 */
internal fun Greeting.toEntity(): GreetingEntity =
    GreetingEntity().also {
        it.name = name
        it.message = message
    }
```

`infrastructure/database/postgresql/src/test/kotlin/<base-package-path>/infrastructure/database/postgresql/PanacheGreetingRepositoryAdapterTest.kt`

```kotlin
package <base-package>.infrastructure.database.postgresql

import <base-package>.domain.greeting.Greeting
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for [PanacheGreetingRepositoryAdapter]: mapping and delegation, with the Panache repository mocked.
 */
class PanacheGreetingRepositoryAdapterTest {
    /** Recording persists the mapped entity. */
    @Test
    fun `record persists the mapped entity`() {
        val repository = mockk<GreetingEntityRepository>()
        justRun { repository.persist(any<GreetingEntity>()) }

        PanacheGreetingRepositoryAdapter(repository).record(Greeting("Ann", "Hello Ann"))

        verify { repository.persist(match<GreetingEntity> { it.name == "Ann" && it.message == "Hello Ann" }) }
    }

    /** The mapping copies every field. */
    @Test
    fun `toEntity copies the fields`() {
        assertEquals("Hello Ann", Greeting("Ann", "Hello Ann").toEntity().message)
    }
}
```

`api/rest/src/main/kotlin/<base-package-path>/api/rest/GreetingResource.kt`

```kotlin
package <base-package>.api.rest

import <base-package>.application.greeting.GreetUseCase
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

/**
 * REST entry point of the greeting use case.
 *
 * @property greetUseCase use case answering the request.
 */
@Path("/hello")
class GreetingResource(
    private val greetUseCase: GreetUseCase,
) {
    /**
     * Returns a greeting for [name].
     *
     * @param name optional name to greet.
     * @return the greeting message as plain text.
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    fun hello(
        @QueryParam("name") name: String?,
    ): String = greetUseCase.greet(name).message
}
```

`app/src/main/kotlin/<base-package-path>/UseCaseProducers.kt`

```kotlin
package <base-package>

import <base-package>.application.greeting.GreetUseCase
import <base-package>.domain.greeting.DefaultNameProvider
import <base-package>.domain.greeting.GreetingRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * Produces the application module's framework-free use cases as CDI beans.
 */
@ApplicationScoped
class UseCaseProducers {
    /**
     * Produces the greeting use case.
     *
     * @param defaultNameProvider adapter supplying the default name.
     * @param greetingRepository adapter recording greetings.
     * @return the use case bean.
     */
    @Produces
    @ApplicationScoped
    fun greetUseCase(
        defaultNameProvider: DefaultNameProvider,
        greetingRepository: GreetingRepository,
    ): GreetUseCase = GreetUseCase(defaultNameProvider, greetingRepository)
}
```

`app/src/test/kotlin/<base-package-path>/ArchitectureTest.kt`

```kotlin
package <base-package>

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

/**
 * Enforces the hexagonal dependency direction across every module on the app module's classpath — the only
 * boundary check on Gradle, where there is no enforcer plugin.
 */
@AnalyzeClasses(packages = ["<base-package>"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {
    companion object {
        /** The domain depends on no framework and on no outer layer. */
        @ArchTest
        @JvmField
        val domainIsFrameworkFree: ArchRule =
            noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                    "io.quarkus..",
                    "jakarta.enterprise..",
                    "jakarta.persistence..",
                    "jakarta.ws.rs..",
                    "io.smallrye.mutiny..",
                    "..application..",
                    "..infrastructure..",
                    "..api..",
                )

        /** Use cases never reach outward to adapters or entry points. */
        @ArchTest
        @JvmField
        val applicationDependsOnlyInward: ArchRule =
            noClasses()
                .that()
                .resideInAPackage("..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..infrastructure..", "..api..")

        /** Entry points call use cases, never adapters. */
        @ArchTest
        @JvmField
        val apiDoesNotUseInfrastructure: ArchRule =
            noClasses()
                .that()
                .resideInAPackage("..api..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..infrastructure..")

        /** Adapters never depend on entry points. */
        @ArchTest
        @JvmField
        val infrastructureDoesNotUseApi: ArchRule =
            noClasses()
                .that()
                .resideInAPackage("..infrastructure..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..api..")

        /** Adapters are independent of each other. */
        @ArchTest
        @JvmField
        val adaptersAreIndependent: ArchRule =
            slices()
                .matching("..infrastructure.(**)")
                .should()
                .notDependOnEachOther()
    }
}
```

The remaining Kotlin files mirror the Java ones one-to-one and the single-module Kotlin samples in `sample-code.md`
(`GreetingConfig.kt`, the component test of the configuration adapter, `api/rest`'s plain `GreetingResourceTest`
with MockK, the app module's `UseCaseProducersTest`, `@QuarkusTest` `api/GreetingResourceTest.kt` in `src/test`, and
the standalone `@QuarkusIntegrationTest` `api/GreetingResourceIT.kt` in `app/src/integrationTest/kotlin`).

## Commands

```bash
# Maven
./mvnw verify -DskipITs=false                                   # everything, per-module 80% checks, coverage/ aggregate
./mvnw -pl app -am test -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false   # boundary check alone
./mvnw site site:stage -DskipTests -Djacoco.skip                # multi-module Maven site → target/staging
# Gradle
./gradlew check :app:quarkusIntTest                             # everything
./gradlew :dokkaGeneratePublicationHtml                         # aggregated KDoc → build/dokka/html
```

The unit-only (`ci-tests: unit`) commands are the single-module ones (`maven.md` / `gradle.md`) run from the root —
the excludes file is resolved per module on Maven and from the root project on Gradle.
