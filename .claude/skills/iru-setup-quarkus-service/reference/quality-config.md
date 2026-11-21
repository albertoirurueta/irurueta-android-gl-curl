# Quality-tool configuration files

Repository-root files the build templates in `maven.md` / `gradle.md` point at. Write them only when absent; an
existing ruleset is the project's own bar — keep it (Step 8 of the skill asks Update / Keep / Replace only for files
this skill wrote before, recognisable by the "written by iru-setup-quarkus-service" header comment).

All four were run against the sample code in `sample-code.md` with zero findings (Checkstyle 14.3.0, detekt 1.23.8,
ktlint via `org.jlleitschuh.gradle.ktlint` 14.2.0 and `ktlint-maven-plugin` 3.7.1, Dokka 2.2.0).

## Java — `checkstyle.xml`

The catalog's Java rules (`iru-quarkus-code-one-task/reference/java.md`) expressed as Checkstyle checks: Javadoc on
every type/member at every visibility, `final` parameters, named constants, no star or AssertJ imports, test names
`method_whenCondition_expectedResult`. Test source sets (`src/test`, Gradle's `src/integrationTest`,
`src/native-test`) need no `package-info.java`, and ArchUnit test classes holding only static rules are exempt from
the utility-class check. `JavadocStyle` is intentionally absent — Checkstyle 14 no longer ships it.

```xml
<?xml version="1.0"?>
<!DOCTYPE module PUBLIC
    "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
    "https://checkstyle.org/dtds/configuration_1_3.dtd">
<!-- Starting ruleset written by iru-setup-quarkus-service: the catalog's Java rules (Javadoc everywhere, final
     parameters, named constants, no star imports) adapted to Quarkus — package-private injection points and
     Panache public fields are allowed. Tighten or relax it as the project's own bar evolves. -->
<module name="Checker">
    <property name="charset" value="UTF-8"/>
    <property name="severity" value="warning"/>
    <property name="fileExtensions" value="java"/>
    <module name="NewlineAtEndOfFile">
        <property name="lineSeparator" value="lf"/>
    </module>
    <module name="FileTabCharacter"/>
    <module name="JavadocPackage"/>
    <module name="LineLength">
        <property name="max" value="120"/>
        <property name="ignorePattern" value="^package.*|^import.*|a href|href|http://|https://|ftp://"/>
    </module>
    <module name="SuppressWarningsFilter"/>
    <!-- Test sources: no package-info.java of their own; ArchUnit test classes hold only static rules -->
    <module name="SuppressionSingleFilter">
        <property name="checks" value="JavadocPackage|HideUtilityClassConstructor"/>
        <property name="files" value="[\\/]src[\\/](test|integrationTest|native-test)[\\/]"/>
    </module>
    <module name="TreeWalker">
        <module name="SuppressWarningsHolder"/>
        <!-- Javadoc -->
        <module name="MissingJavadocType">
            <property name="scope" value="private"/>
        </module>
        <module name="MissingJavadocMethod">
            <property name="scope" value="private"/>
            <property name="allowMissingPropertyJavadoc" value="false"/>
            <property name="tokens" value="METHOD_DEF, CTOR_DEF, ANNOTATION_FIELD_DEF, COMPACT_CTOR_DEF"/>
        </module>
        <module name="JavadocVariable">
            <property name="accessModifiers" value="public, protected, package, private"/>
        </module>
        <module name="JavadocMethod">
            <property name="accessModifiers" value="public, protected, package, private"/>
        </module>
        <module name="InvalidJavadocPosition"/>
        <!-- Naming -->
        <module name="ConstantName"/>
        <module name="LocalFinalVariableName"/>
        <module name="LocalVariableName"/>
        <module name="MemberName"/>
        <module name="MethodName">
            <!-- test names: methodBeingTested_whenCondition_expectedResult -->
            <property name="format" value="^[a-z][a-zA-Z0-9]*(_[a-zA-Z0-9]+)*$"/>
        </module>
        <module name="PackageName"/>
        <module name="ParameterName"/>
        <module name="StaticVariableName"/>
        <module name="TypeName"/>
        <!-- Imports -->
        <module name="AvoidStarImport">
            <property name="allowStaticMemberImports" value="true"/>
        </module>
        <module name="IllegalImport">
            <property name="illegalPkgs" value="sun, org.assertj"/>
        </module>
        <module name="RedundantImport"/>
        <module name="UnusedImports"/>
        <!-- Code -->
        <module name="FinalParameters"/>
        <module name="MagicNumber">
            <property name="ignoreHashCodeMethod" value="true"/>
            <property name="ignoreAnnotation" value="true"/>
        </module>
        <module name="EmptyBlock"/>
        <module name="NeedBraces"/>
        <module name="EqualsHashCode"/>
        <module name="SimplifyBooleanExpression"/>
        <module name="SimplifyBooleanReturn"/>
        <module name="MissingSwitchDefault"/>
        <module name="ModifierOrder"/>
        <module name="RedundantModifier"/>
        <module name="UpperEll"/>
        <module name="ArrayTypeStyle"/>
        <module name="OneStatementPerLine"/>
        <module name="MultipleVariableDeclarations"/>
        <module name="HideUtilityClassConstructor"/>
        <module name="WhitespaceAround"/>
        <module name="WhitespaceAfter"/>
        <module name="NoWhitespaceBefore"/>
        <module name="ParenPad"/>
    </module>
</module>
```

Maven: every module resolves it through `<configLocation>` (`checkstyle.xml` single-module;
`${maven.multiModuleProjectDirectory}/checkstyle.xml` in a reactor, which needs the `.mvn/` directory the Maven
wrapper already provides). Gradle: `checkstyle { configFile = rootProject.file("checkstyle.xml") }`.

## Kotlin — `detekt.yml`

Applied on top of detekt's bundled defaults (`buildUponDefaultConfig = true`). It switches on the KDoc rules the
catalog's Kotlin agreements require for production code and extends the backtick-test-name exemption to Quarkus's
`src/integrationTest` source set (detekt's default `FunctionNaming` excludes only cover test directories), and exempts
ArchUnit test classes (rules in a `companion object`) from `UtilityClassWithPublicConstructor`.

```yaml
# Starting detekt configuration written by iru-setup-quarkus-service, applied on top of detekt's defaults
# (buildUponDefaultConfig = true). Turns on the KDoc rules the catalog's Kotlin agreements require.
comments:
  UndocumentedPublicClass:
    active: true
    excludes: ['**/test/**', '**/integrationTest/**']
  UndocumentedPublicFunction:
    active: true
    excludes: ['**/test/**', '**/integrationTest/**']
  UndocumentedPublicProperty:
    active: true
    excludes: ['**/test/**', '**/integrationTest/**']
naming:
  FunctionNaming:
    # backtick test names, also in Quarkus's src/integrationTest (detekt's default excludes only cover test dirs)
    excludes: ['**/test/**', '**/integrationTest/**', '**/*Test.kt', '**/*IT.kt']
style:
  UtilityClassWithPublicConstructor:
    # ArchUnit test classes hold their rules in a companion object
    excludes: ['**/test/**', '**/integrationTest/**']
```

## Kotlin — `.editorconfig` (ktlint's configuration)

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true

[*.{kt,kts}]
indent_size = 4
max_line_length = 120
ktlint_code_style = ktlint_official
```

If the repository already has an `.editorconfig`, merge only the `[*.{kt,kts}]` section in (never replace the file).
`ktlint_official` also lints `*.gradle.kts`: on Gradle, run `./gradlew ktlintFormat` once after writing the build
files — it reformats the Quarkus-generated `settings.gradle.kts` (`rootProject.name="…"` → `rootProject.name = "…"`)
and long expressions in `build.gradle.kts` — then `ktlintCheck` is green.

## Kotlin — `module.md` (Dokka module/package docs)

One per module (single-module: the repository root), wired through Dokka `includes` (see `gradle.md` / `maven.md`).
The `# Module` name must equal Dokka's module name — the Gradle project name or the Maven `artifactId`.

```markdown
# Module <artifact-id>

<one-line service description>

# Package <base-package>.greeting

Sample greeting feature: a configuration mapping, a service, and a REST resource, each covered at the test level
that fits it. Replace it with the service's real features.
```
