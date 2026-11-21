---
name: iru-setup-java-springboot-apis
description: "Invoked as `/iru-setup-java-springboot-apis` (args: `stack-file`, `mode`, `on-existing`), normally by `iru-setup-java-springboot`. Sets up the contract-first `apis/` tree (OpenAPI, protobuf, GraphQL SDL, AVRO, AsyncAPI) with starter specs and code generation wired into the consuming modules, for Java or Kotlin."
model: sonnet
---

# Setup Java Spring Boot APIs

Wire up the contract-first API layer. Two invariants:

1. **Every contract lives under `apis/` at the repository root** — never inside a module's `src/`. One directory
   is where a reviewer looks to see everything this service exposes and consumes, and it keeps a contract from
   being coupled to the module that happens to implement it today. `apis/` is not a Maven module or Gradle project.
2. **Generated code is never committed and never hand-edited.** It lands in each module's
   `target/generated-sources/` (Maven) or `build/generated/` (Gradle), is git-ignored, and is excluded from
   static analysis (Checkstyle/PMD/SpotBugs for Java, detekt/ktlint for Kotlin), JaCoCo and Sonar. If something
   about the generated code is wrong, the fix is in the spec or the generator configuration.

## Step 0 — Resolve inputs

Parse `args` for `key: value` lines: `stack-file: <path>`, defaulting to `springboot-stack.yml`; `mode`
(`new`/`existing`, informational — it never means "update silently"); and `on-existing`
(`ask`/`keep`/`update`/`replace`, default `ask`). Read the manifest and determine exactly which contracts are
needed:

| Manifest field | Contract directory | Generated into | Generator |
|---|---|---|---|
| `stack.restServer: true` | `apis/rest-server/` | `api/rest-server` | `openapi-generator-maven-plugin` (`spring` generator) |
| `stack.grpcServer: true` | `apis/grpc-server/` | `api/grpc-server` | `protobuf-maven-plugin` |
| `stack.graphqlServer: true` | `apis/graphql-server/` (SDL → code) | `api/graphql-server` | `graphql-codegen-maven-plugin` |
| `stack.graphqlServer: true` | `apis/graphql-server/` (SDL → docs) | documentation only — no Java | `spectaql` (+ optional `graphql-voyager`) via `frontend-maven-plugin` |
| each `stack.restClients[]` | `apis/rest-client/<name>/` | `infrastructure/clients/<name>` | `openapi-generator-maven-plugin` (client generator) |
| each `stack.grpcClients[]` | `apis/grpc-client/<name>/` | `infrastructure/clients/<name>` | `protobuf-maven-plugin` |
| each `stack.graphqlClients[]` | `apis/graphql-client/<name>/` | `infrastructure/clients/<name>` | `graphql-codegen-maven-plugin` |
| `stack.messaging.enabled: true` | `apis/messaging/` (AVRO schemas) | `infrastructure/producers` and/or `api/consumers` | `avro-maven-plugin` |
| `stack.messaging.enabled: true` | `apis/messaging/` (AsyncAPI spec) | documentation only — no Java | `@asyncapi/html-template` via `frontend-maven-plugin` |

If none apply, report that and stop — there's nothing to set up.

**Language and build tool.** Read `project.language` (`java` | `kotlin`, absent ⇒ `java`) and `project.buildTool`
(`maven` | `gradle`, absent ⇒ `maven`; `gradle` is only valid with `kotlin` — stop and report a manifest that pairs
`gradle` with `java`). The contracts in `apis/` (Steps 1, 2, 3, 3a, 4, 5, 5a, 6, 6a) are identical in all three
combinations — a contract never depends on the language implementing it. What changes is the generator column
above:

| Contract | `java` + `maven` (unchanged) | `kotlin` + `maven` | `kotlin` + `gradle` |
|---|---|---|---|
| REST server | `spring`, `interfaceOnly` | `kotlin-spring`, `interfaceOnly`, `useSpringBoot4`, `useJackson3` | same options, `org.openapi.generator` `GenerateTask` |
| REST client, blocking | `java` + `library=restclient` | `kotlin` + `library=jvm-spring-restclient`, `useSpringBoot4`, `useJackson3` | same, `GenerateTask` |
| REST client, reactive | `java` + `library=webclient` | `kotlin-spring` + `library=spring-declarative-http-interface`, `reactive`, coroutines | same, `GenerateTask` |
| gRPC | protoc Java + `protoc-gen-grpc-java` | the same **plus** `kotlinEnabled` (Kotlin DSL builders) and `protoc-gen-grpc-kotlin` (coroutine stubs) | `com.google.protobuf` with `kotlin` builtin + `grpc` + `grpckt` plugins |
| GraphQL server/client | `graphql-codegen-maven-plugin`, Java | same plugin, `generatedLanguage=KOTLIN` | `io.github.kobylynskyi.graphql.codegen` `GraphQLCodegenGradleTask`, `KOTLIN` |
| AVRO | `avro-maven-plugin` (Java) | unchanged — Java classes, called from Kotlin | `com.bakdata.gradle.avro` (Java output) |
| REST/gRPC HTML docs | `html2`, `protoc-gen-doc` | unchanged | `html2` `GenerateTask` + `protoc-gen-doc` protoc plugin, `documentation` group |
| AsyncAPI / GraphQL HTML docs | npm via `frontend-maven-plugin` | unchanged | the same npm toolchains via `Exec` tasks (Step 6c) |

Why each Kotlin choice, and the alternatives rejected — all checked against openapi-generator 7.26.0, grpc-kotlin
1.5.0, graphql-java-codegen 5.10.0 and Spring Boot 4.1 (see Step 6c's verification note):

- **`kotlin-spring` for servers.** It has a real `useSpringBoot4` option (Jakarta, Spring 7) and `useJackson3`
  (defaults to `true` with `useSpringBoot4`); its `interfaceOnly` output is a Kotlin interface with
  non-null/nullable parameters and `data class` DTOs that carry the spec's Bean Validation constraints — what a
  hand-written Kotlin controller wants to implement. The Java `spring` generator would also compile, but hands the
  controller platform types instead of the spec's nullability.
- **`kotlin` + `jvm-spring-restclient` for blocking clients.** Supports `useSpringBoot4` + `useJackson3`
  (`tools.jackson` imports, Spring 7's `JacksonJsonHttpMessageConverter`); the generated `ItemsApi(restClient)` takes
  Boot's auto-configured `RestClient`. Requires `serializationLibrary=jackson`.
- **`kotlin-spring` + `spring-declarative-http-interface` for reactive clients**, *not* `kotlin` +
  `jvm-spring-webclient`: the WebClient library rejects `useJackson3` ("only supported for the jvm-okhttp4 and
  jvm-spring-restclient libraries") and emits Jackson 2 `ObjectMapper`/`Jackson2JsonDecoder` code, which is not on a
  Boot 4 classpath. The declarative library emits an `@HttpExchange` interface with `suspend` methods, serialized
  by whatever codecs the `WebClient` it is bound to already has; it requires `useFlowForArrayReturnType=false`.
- **gRPC keeps the grpc-java output and adds Kotlin on top.** grpc-kotlin's `protoc-gen-grpc-kotlin` generates
  `<Service>GrpcKt.<Service>CoroutineImplBase` (implemented with `suspend fun` / `Flow`) and coroutine stubs, but it
  *wraps* the Java message classes — protoc's Java output is always needed. `kotlinEnabled` adds the `orderReply { }`
  DSL builders. The coroutine base class implements `BindableService`, so Spring gRPC registers it like a Java one.
- **GraphQL `generatedLanguage=KOTLIN`** emits Kotlin resolver interfaces with nullable return types matching the
  SDL and `data class` models; the client mode emits Kotlin request/projection classes that still extend
  `graphql-java-codegen`'s Java base types (same runtime dependency as Java). The plugin's last release is 5.10.0
  (February 2024), so say in the report that it is stable but slow-moving.
- **AVRO stays Java.** No maintained AVRO→Kotlin generator targets Avro 1.12 SpecificRecord; the generated Java
  builders are idiomatic enough from Kotlin and the Confluent serializer needs SpecificRecord anyway. On Gradle,
  `com.github.davidmc24.gradle.plugin.avro` is **archived** (December 2023); use `com.bakdata.gradle.avro`
  (bakdata's maintained fork — same extension/task model, Avro 1.12.x, Gradle 8.4–8.14 tested). Re-check its
  maintenance on every run (`gh api repos/bakdata/gradle-avro-plugin --jq '{archived,pushed_at}'`); if it is
  archived too, generate with Avro's own `SpecificCompiler` from a small `JavaExec` task and report why.

Look up current versions rather than trusting the ones above: openapi-generator from Maven Central
(`org/openapitools/openapi-generator-maven-plugin`) — the Gradle plugin portal id `org.openapi.generator` carries
the same version; `com.google.protobuf` and `com.bakdata.gradle.avro` from `plugins.gradle.org/m2`;
`io.grpc:protoc-gen-grpc-java`, `io.grpc:protoc-gen-grpc-kotlin`, `com.google.protobuf:protoc` aligned to the
`grpc-java.version`, `grpc-kotlin.version` and `protobuf-java.version` the Spring Boot BOM manages (Boot 4.1.1:
1.83.1 / 1.5.0 / 4.35.1) so generated code and runtime agree; `io.github.pseudomuto:protoc-gen-doc` from Maven
Central (it publishes per-OS executables, so the protoc plugin resolves on Linux CI and macOS alike).

Verify the target module directories exist (from `iru-setup-java-springboot-pom`). If a module is missing, report it
and skip that contract rather than creating a module here.

**Maven:** read the root `pom.xml` to confirm the generator plugins are already version-managed in
`<pluginManagement>` (the pom skill puts them there). **Declare no `<version>` in any module pom below** — if a
version is missing from `pluginManagement`, add it to the root pom rather than inline in the module.

**Gradle:** read `gradle/libs.versions.toml`, the root `build.gradle.kts`, and each target module's
`build.gradle.kts` (module paths from `settings.gradle.kts`, e.g. `:api:rest-server`). Every generator plugin,
protoc artifact and runtime library this skill needs gets a `[versions]` + `[plugins]`/`[libraries]` entry in the
catalog (add the missing ones — the same "no inline versions" rule as the root pom), the root `build.gradle.kts`
declares each codegen plugin `apply false`, and the module applies it with `alias(libs.plugins.…)` and no version.
Never name a catalog alias so that it is a prefix of another (`protoc` next to `protoc-gen-grpc-java`): Gradle
turns `libs.protoc` into a group accessor and `libs.protoc.get()` stops compiling — use `protobuf-protoc`,
`grpc-protoc-gen-java`, `grpc-protoc-gen-kotlin` as below.

Say whether this run is **creating the API layer from scratch** (no `apis/` tree, no generator executions) or
**updating an existing one** (list what exists: contract files per directory, `apis/messaging/docs/` and
`apis/graphql-server/docs/` toolchains, generator executions already in module poms or generator tasks/blocks already
in module `build.gradle.kts` files). For each pre-existing
artifact this skill owns, warn and — unless `on-existing` says otherwise — ask with `AskUserQuestion` (up to 4 per
call, one question each; group "all existing contract files" into one question when there are many):

- **Update (Recommended)** — add what's missing (a directory/starter spec for a newly added API or client, a
  missing generator execution, a missing documentation step) and correct generator configuration this skill owns;
  **existing contract files are never rewritten** — a contract is the service's real API, not a template. Your
  customizations survive, but a customized generator execution this skill owns may be rewritten.
- **Keep as is** — leave it untouched; it may lag behind the manifest (e.g. no contract for a newly added client)
  — Step 10 lists each gap.
- **Replace** — regenerate from this skill's templates. For a generator execution or documentation toolchain this
  only loses its customizations; for a **contract file** it replaces a real API definition with a placeholder, so
  only offer it for contracts after stating that explicitly, and never recommend it.

**Templates.** The wiring fragments and toolchain files of Steps 2-6c are files under this skill's `templates/` directory, not inline text: `templates/<variant>/<path of the generated file>`, holding the exact bytes of the template (a `.<suffix>` after the file name marks a fragment to merge into that file; `__name__`, `__service__` and `__module__` stand for the client name, the service name and the module directory). Variants: `maven` (Java or Kotlin), `maven-java`, `maven-kotlin`, `gradle-kotlin`, `common` (build-tool independent). Read only the files for the manifest's `language`/`build-tool` and the contracts it needs, each in full, before filling placeholders:

| Generated file | Template | Applies when |
|---|---|---|
| `api/rest-server/pom.xml` | `templates/maven-java/api/rest-server/pom.xml.build` | `stack.restServer` on Maven with Java: `<build>` block with the `generate-rest-server-v1` (`spring` generator) and `generate-rest-server-docs` (`html2`) executions (Step 2) |
| `api/rest-server/pom.xml` | `templates/maven-kotlin/api/rest-server/pom.xml.server-configuration` | `stack.restServer` on Maven with Kotlin: replaces the `<configuration>` of the server execution (`kotlin-spring` generator); the `html2` execution is unchanged (Step 2) |
| `api/grpc-server/pom.xml` | `templates/maven/api/grpc-server/pom.xml.protobuf-plugin` | `stack.grpcServer` on Maven (Java or Kotlin): protobuf plugin wiring, using the groupId the root pom manages (Step 3) |
| `api/grpc-server/pom.xml` | `templates/maven-kotlin/api/grpc-server/pom.xml.protobuf-configuration` | `stack.grpcServer` on Maven with Kotlin (`io.github.ascopes` flavour): protobuf plugin `<configuration>` with `kotlinEnabled` and `protoc-gen-grpc-kotlin`; also reused for Kotlin gRPC clients (Steps 3 and 5) |
| `api/graphql-server/pom.xml` | `templates/maven/api/graphql-server/pom.xml.codegen-plugin` | `stack.graphqlServer` on Maven (Java or Kotlin; Kotlin adds `<generatedLanguage>KOTLIN</generatedLanguage>`): `graphql-codegen-maven-plugin` execution (Step 3a) |
| `api/graphql-server/pom.xml` | `templates/maven/api/graphql-server/pom.xml.copy-graphql-schema-plugin` | `stack.graphqlServer` on Maven (Java or Kotlin): `maven-resources-plugin` execution copying the SDL onto the runtime classpath, mandatory (Step 3a) |
| `apis/graphql-server/docs/package.json` | `templates/common/apis/graphql-server/docs/package.json` | `api/graphql-server/` exists (any build tool): GraphQL documentation toolchain, drop `graphql-voyager` if Voyager is not wanted (Step 3b) |
| `apis/graphql-server/docs/spectaql-v1.yml` | `templates/common/apis/graphql-server/docs/spectaql-v1.yml` | `api/graphql-server/` exists (any build tool): SpectaQL configuration, one file per contract version (Step 3b) |
| `apis/graphql-server/docs/build-voyager.mjs` | `templates/common/apis/graphql-server/docs/build-voyager.mjs` | `api/graphql-server/` exists and Voyager is wanted (any build tool): offline Voyager build script (Step 3b) |
| `api/graphql-server/pom.xml` | `templates/maven/api/graphql-server/pom.xml.docs-executions` | `api/graphql-server/` exists on Maven (Java or Kotlin): `frontend-maven-plugin` SpectaQL and Voyager executions; omit the Voyager one if not wanted (Step 3b) |
| `infrastructure/clients/<name>/pom.xml` | `templates/maven-java/infrastructure/clients/__name__/pom.xml.rest-client-configuration` | Per `stack.restClients` entry on Maven with Java: OpenAPI client generator `<configuration>` (Step 4) |
| `infrastructure/clients/<name>/pom.xml` | `templates/maven-kotlin/infrastructure/clients/__name__/pom.xml.rest-client-blocking-configuration` | Per `stack.restClients` entry on Maven with Kotlin, blocking (`stack.concurrency` blocking or both): `kotlin` generator with `jvm-spring-restclient` (Step 4) |
| `infrastructure/clients/<name>/pom.xml` | `templates/maven-kotlin/infrastructure/clients/__name__/pom.xml.rest-client-reactive-configuration` | Per `stack.restClients` entry on Maven with Kotlin, reactive: `kotlin-spring` generator with `spring-declarative-http-interface` (Step 4) |
| `infrastructure/clients/<name>/pom.xml` | `templates/maven/infrastructure/clients/__name__/pom.xml.graphql-client-configuration` | Per `stack.graphqlClients` entry on Maven (Java or Kotlin; Kotlin adds `<generatedLanguage>KOTLIN</generatedLanguage>`): GraphQL client generator `<configuration>` (Step 5a) |
| `infrastructure/producers/pom.xml and/or api/consumers/pom.xml` | `templates/maven/infrastructure/producers/pom.xml.avro-plugin` | `stack.messaging.enabled` on Maven (Java or Kotlin): `avro-maven-plugin` in whichever of the two modules exist (Step 6) |
| `apis/messaging/<service>-async-api-v1.yaml` | `templates/common/apis/messaging/__service__-async-api-v1.yaml` | `stack.messaging.enabled` (any build tool): starter AsyncAPI specification (Step 6a) |
| `apis/messaging/docs/package.json` | `templates/common/apis/messaging/docs/package.json` | `stack.messaging.enabled` (any build tool): AsyncAPI documentation toolchain, keep `overrides` only if `npm install` needs it (Step 6b) |
| `infrastructure/producers/pom.xml (else api/consumers/pom.xml)` | `templates/maven/infrastructure/producers/pom.xml.asyncapi-docs-plugin` | `stack.messaging.enabled` on Maven (Java or Kotlin): `frontend-maven-plugin` AsyncAPI validate and generate executions, in one module only (Step 6b) |
| `gradle/libs.versions.toml` | `templates/gradle-kotlin/gradle/libs.versions.toml.append` | Kotlin with Gradle: catalog entries to add, only what the manifest needs (Step 6c) |
| `<module>/build.gradle.kts` | `templates/gradle-kotlin/__module__/build.gradle.kts.codegen` | Kotlin with Gradle: codegen plugins, generator tasks, source sets, protobuf and AVRO wiring for whichever modules Step 0's table names (Step 6c) |
| `<module>/build.gradle.kts` | `templates/gradle-kotlin/__module__/build.gradle.kts.docs-tasks` | Kotlin with Gradle: `documentation`-group API documentation tasks (Step 6c) |

## Step 1 — Create the `apis/` tree

```
apis/
  README.md
  rest-server/
    <service>-api-v1.yaml
  grpc-server/
    <service>_v1.proto
  graphql-server/
    <service>-v1.graphqls
    docs/
      package.json
      spectaql-v1.yml
      build-voyager.mjs
  rest-client/
    <name>/
      <name>-api.yaml
  grpc-client/
    <name>/
      <name>.proto
  graphql-client/
    <name>/
      <name>-api.graphqls
  messaging/
    <service>-async-api-v1.yaml
    <event-name>-v1.avsc
    docs/
      package.json
```

`apis/messaging/` holds **two kinds of contract, not one**: the AVRO schemas define the *payloads*, and the
AsyncAPI specification defines the *interface* — which topics exist, in which direction the service uses each one,
which message each one carries, and which server (broker) they live on. The AVRO schemas are what
`avro-maven-plugin` compiles into Java; the AsyncAPI document is what the documentation generators read. Keeping
them in one directory is deliberate: a new event means a new `.avsc` *and* a new channel in the AsyncAPI file, and
a reviewer should see both in the same diff.

Create only the subdirectories the manifest calls for. Write `apis/README.md` explaining the layout, which module
each directory generates into, that generated code is never committed, and how to regenerate
(`mvn generate-sources` on Maven; `./gradlew build -x test -x integrationTest` on Gradle, or the module's individual
generator task, e.g. `./gradlew :api:rest-server:generateRestServerV1`). This file is what stops the next developer from adding a spec in the wrong place.

Version contracts **in the filename and in the path inside the contract**, not by mutating a file in place: an
OpenAPI spec's paths carry `/v1/...`, a protobuf file declares `package <service>.v1`, an AVRO schema's namespace
ends in `.v1`, and a GraphQL SDL — which has no in-band version at all — is versioned by filename and by the
package its types generate into. A breaking change means a `v2` file next to the `v1` one, with both generating and both served
until `v1` is retired. State this convention in `apis/README.md`.

## Step 2 — REST server contract and generator

Write a starter OpenAPI 3.1 spec at `apis/rest-server/<service>-api-v1.yaml` covering one resource, and make it
complete enough to be a real template rather than a stub: `info` with a version and description, a server entry,
one tagged path with parameters and request/response bodies, `components/schemas` with per-field `description`
and `required` lists, and — importantly — a documented **error schema** plus `4xx`/`5xx` responses that reference
it. If `stack.security.enabled`, add a `securitySchemes` entry (`bearerFormat: JWT`) and apply it.

Every field in every schema needs a `description`, and any field with a constraint needs it expressed in the spec
(`maxLength`, `pattern`, `minimum`, `format`) — those constraints become Bean Validation annotations on the
generated DTOs *and* they're what the documentation page reads. A spec without descriptions produces both
undocumented code and an undocumentable API.

Wire into `api/rest-server/pom.xml`:

Template: `templates/maven-java/api/rest-server/pom.xml.build` — read it in full before filling placeholders

Notes:

- `interfaceOnly=true` is the important setting. With it, the generator owns the interfaces and DTOs and the
  hand-written `@RestController` implements them — so `mvn generate-sources` is always safe to re-run. Without it,
  the generator emits controllers and every regeneration is a merge conflict.
- `reactive` must match `stack.concurrency`. Getting it wrong produces signatures the controllers can't implement,
  which surfaces as a confusing compile error rather than an obvious misconfiguration.
- The `html2` generator produces the standalone HTML documentation. It lands in
  `target/generated-docs/rest-server/v1` where the CI workflow can publish it to GitHub Pages next to the Antora
  site — the documentation page then links to it rather than duplicating the endpoint list by hand.
- Add one execution **per version file**. When `v2` arrives, it gets its own execution and its own
  `apiPackage`/`modelPackage` ending in `.v2.`, and both keep generating.

**Kotlin + Maven** — same plugin, same two executions; the server execution switches generator and options (the
`html2` documentation execution is unchanged). Verified against openapi-generator 7.26.0 on Spring Boot 4.1.1:

Template: `templates/maven-kotlin/api/rest-server/pom.xml.server-configuration` — read it in full before filling placeholders

`kotlin-maven-plugin` picks the generated directory up by itself: the generator registers
`target/generated-sources/openapi-rest-server-v1/src/main/kotlin` as a compile source root, and the Kotlin compile
execution compiles every registered root in addition to its own `<sourceDirs>` (verified with the mixed-sources
`sourceDirs` configuration `iru-setup-java-springboot-pom` writes). The same holds for every generator below, Java
output included — `kotlinc` resolves against generated `.java` files and `javac` (re-bound after it) compiles them.
The hand-written controller is `@RestController class OrdersController : OrdersApi` overriding plain `fun`s
(blocking) or `suspend fun`s returning `ResponseEntity<T>` (reactive; list operations return
`ResponseEntity<Flow<T>>` unless `useFlowForArrayReturnType=false`).

## Step 3 — gRPC server contract and generator

Write a starter `.proto` at `apis/grpc-server/<service>_v1.proto`: `syntax = "proto3"`, `package <service>.v1`,
`option java_package = "<base>.api.grpc.v1.generated"`, `option java_multiple_files = true`, one service with one
unary RPC and one server-streaming RPC, request/response messages, and an enum. Comment every message, every
field, and every RPC — protobuf comments are what `protoc-gen-doc` turns into the generated documentation, so an
uncommented field is an undocumented API field.

Field-number discipline belongs in the file as a comment: numbers are never reused or renumbered, a removed field
is marked `reserved`, and a deprecated field carries `[deprecated = true]` with the reason in its comment.

Wire into `api/grpc-server/pom.xml`, using the protobuf plugin **groupId that the root pom manages** — Spring
Boot 4's Initializr wires `io.github.ascopes:protobuf-maven-plugin`, while older setups used
`org.xolstice.maven.plugins:protobuf-maven-plugin`; the two have different configuration schemas, so read the root
pom to see which is in play and configure that one rather than assuming:

Template: `templates/maven/api/grpc-server/pom.xml.protobuf-plugin` — read it in full before filling placeholders

Consult the plugin's own current documentation for the exact element names (`sourceDirectories` vs
`protoSourceRoot`, how the `grpc-java` plugin is declared, whether `protocVersion` is needed) instead of guessing —
a wrong element name here fails the build with an unhelpful "unknown parameter" error. Verify by running
`mvn generate-sources` in Step 7.

**Kotlin + Maven** — keep everything above and add Kotlin on top (verified with `io.github.ascopes` 5.1.x, the
flavour Spring Boot 4 manages; on the `xolstice` flavour, migrate to `ascopes` first rather than bolting Kotlin onto
an unmaintained plugin):

Template: `templates/maven-kotlin/api/grpc-server/pom.xml.protobuf-configuration` — read it in full before filling placeholders

`${protobuf-java.version}`, `${grpc-java.version}` and `${grpc-kotlin.version}` are Spring Boot BOM properties, so
nothing new is pinned. The module additionally needs `io.grpc:grpc-kotlin-stub`, `com.google.protobuf:protobuf-kotlin`
and `org.jetbrains.kotlinx:kotlinx-coroutines-core` (all BOM-managed — no `<version>`). The service is implemented
as `class OrderGrpcService : OrderServiceGrpcKt.OrderServiceCoroutineImplBase()` with `override suspend fun` for
unary RPCs and `override fun …: Flow<Reply>` for server streaming.

For **HTML documentation**, add a second execution using the `protoc-gen-doc` protoc plugin
(`io.github.pseudomuto:protoc-gen-doc`) with `--doc_opt=html,index.html`, writing into
`${project.build.directory}/generated-docs/grpc-server/v1`. If the protobuf plugin in use can't invoke a custom
protoc plugin cleanly, fall back to a `docker run pseudomuto/protoc-gen-doc` step and say so in the report — don't
silently ship no gRPC documentation, since the documentation page is specified to link to it.

## Step 3a — GraphQL server contract and generator

Only if `stack.graphqlServer`. Write a starter SDL at `apis/graphql-server/<service>-v1.graphqls`: one `type` for
the aggregate, an `enum`, an `input` type, a `Query` with one field, and a `Mutation` with one field. Give **every
type, field, argument and enum value a description string** (`"""..."""` or `"..."` above the element) — SDL
descriptions are what the generated Javadoc carries and what any schema explorer shows, so an undescribed field is
an undocumented API field, exactly as with OpenAPI.

Version by file and by type-name suffix rather than by mutating in place. GraphQL has no URL path to carry a
version, so a breaking change means a `v2` SDL file whose types are generated into a `.v2.` package; both keep
generating and both stay served until `v1` is retired. Say so in `apis/README.md`.

Wire into `api/graphql-server/pom.xml`:

Template: `templates/maven/api/graphql-server/pom.xml.codegen-plugin` — read it in full before filling placeholders

**Kotlin + Maven** — the same execution plus `<generatedLanguage>KOTLIN</generatedLanguage>`. Output is Kotlin
resolver interfaces whose return types follow the SDL's nullability (`order(id: String): Order?`) and `data class`
models with a builder; keep `modelValidationAnnotation` as above (harmless for Kotlin output, and correct if the
setting is ever flipped back). Verified with plugin 5.10.0.

The generator emits a `QueryResolver`/`MutationResolver` interface plus one `<Field><Operation>Resolver` interface
per field, and the model classes. Implement the resolver interfaces from a hand-written `@Controller` using
`@QueryMapping`/`@MutationMapping`/`@SchemaMapping`.

**Then copy the SDL onto the runtime classpath — this step is not optional.** Spring for GraphQL reads the schema
at startup from `spring.graphql.schema.locations`, whose default is `classpath:graphql/**/` with extensions
`.graphqls`/`.gqls`. The contract lives outside the module, so nothing puts it there by itself, and the failure
mode is a service that compiles perfectly and then refuses to start because the schema is empty:

Template: `templates/maven/api/graphql-server/pom.xml.copy-graphql-schema-plugin` — read it in full before filling placeholders

Confirm in Step 7 that the file actually landed in `target/classes/graphql/`. If the service sets a non-default
`spring.graphql.schema.locations`, copy to wherever that points instead and note the divergence in the report.

Two more things worth setting and reporting:

- **`spring.graphql.schema.inspection.enabled`** (on by default in recent Boot lines) reports schema fields with no
  corresponding resolver at startup. Leave it on — it's how a field added to the SDL and never implemented gets
  noticed at boot rather than as a runtime `null`.
- **Query depth and complexity limits.** A GraphQL endpoint with neither lets any client issue a deeply nested
  query that walks the object graph until the service dies — the standard denial-of-service shape for GraphQL, and
  not a hypothetical one. There is no default limit. Add `MaxQueryDepthInstrumentation` and
  `MaxQueryComplexityInstrumentation` beans, and say in the report that the chosen numbers are a starting point the
  team must tune against their real schema.

## Step 3b — GraphQL documentation generation, wired into the build

Only if `api/graphql-server/` exists. `graphql-codegen-maven-plugin` generates *code*, not documentation, so unlike
REST (`html2`) and gRPC (`protoc-gen-doc`) the GraphQL contract would otherwise publish nothing. Close that gap the
same way `apis/messaging/` does: an npm toolchain pinned beside the contract, driven from the Maven build by
`frontend-maven-plugin`, writing into `target/generated-docs/` for the workflow to publish.

| Tool | Produces | Role |
|---|---|---|
| `spectaql` | a single self-contained `index.html` reference | the primary reference — types, fields, arguments, enums, inputs and operations, with every SDL description rendered |
| `graphql-voyager` | an interactive schema graph | optional; answers "how do these types connect?", which a flat reference can't |

The two are complements, not alternatives: SpectaQL is the page someone reads to integrate, Voyager is the picture
someone opens to understand the shape of the schema. Generate SpectaQL always; ask before adding Voyager, since it
roughly doubles the published bytes for that API.

Write `apis/graphql-server/docs/package.json`:

Template: `templates/common/apis/graphql-server/docs/package.json` — read it in full before filling placeholders

`npm install` there once and **commit `package-lock.json`**; add `apis/graphql-server/docs/node_modules/` to
`.gitignore`. Drop `graphql-voyager` from the dependencies if Voyager wasn't wanted — `graphql` stays either way,
since the Voyager build script needs it and SpectaQL wants a matching version.

**SpectaQL** is configured by a YAML file per contract version, at `apis/graphql-server/docs/spectaql-v1.yml`:

Template: `templates/common/apis/graphql-server/docs/spectaql-v1.yml` — read it in full before filling placeholders

**Voyager** has no CLI and takes an introspection result rather than SDL, so it needs a small build script at
`apis/graphql-server/docs/build-voyager.mjs`. It stays offline by copying Voyager's own standalone bundle next to
the page rather than loading it from a CDN:

Template: `templates/common/apis/graphql-server/docs/build-voyager.mjs` — read it in full before filling placeholders

On Gradle the toolchain files above are identical and the build wiring is the `generateGraphqlDocs` task of Step 6c
instead of the executions below. On Maven (Java or Kotlin), wire both into `api/graphql-server/pom.xml` with
`frontend-maven-plugin`, exactly as Step 6b does for AsyncAPI —
same `install-node-and-npm` + `npm ci` prelude, same `<skip>` escape hatch (`${graphql.docs.skip}`), same
`generate-resources` phase as every other documentation generator in the reactor:

Template: `templates/maven/api/graphql-server/pom.xml.docs-executions` — read it in full before filling placeholders

`workingDirectory` is `${maven.multiModuleProjectDirectory}/apis/graphql-server/docs` and `installDirectory` is
`${project.build.directory}`, as in Step 6b. The layout that results —
`generated-docs/graphql-server/v1/index.html` with `generated-docs/graphql-server/v1/voyager/index.html` beneath
it — publishes to `doc/api/graphql-server/v1/`, next to `doc/api/rest-server/v1/` and `doc/api/grpc-server/v1/`.
Add one pair of executions per SDL version file. Omit the Voyager execution entirely if it wasn't wanted rather
than leaving a disabled one behind.

Two things to confirm in Step 7 rather than assume:

- **The SpectaQL page shows the SDL descriptions.** An SDL with no description strings produces a structurally
  complete page that documents nothing — the same failure the OpenAPI spec has, and the reason Step 3a insists on
  describing every element.
- **The Voyager page actually draws the graph.** It is a client-side render, so a broken introspection payload
  yields a blank page with a console error and no build failure at all. Open it and look; the bundled
  `voyager.standalone.js` and `voyager.css` must sit beside `index.html` in the published output, since the page
  loads them by relative path.

## Step 4 — REST clients

Per entry in `stack.restClients`, put the downstream service's OpenAPI spec at
`apis/rest-client/<name>/<name>-api.yaml`. This is the **downstream team's contract, not yours** — the starter file
should be a minimal placeholder with a prominent comment saying to replace it with the real published spec, plus a
note in `apis/README.md` about keeping it in sync (ideally by pulling it from the downstream service's own
published artifact in CI rather than by hand).

Wire into `infrastructure/clients/<name>/pom.xml`:

Template: `templates/maven-java/infrastructure/clients/__name__/pom.xml.rest-client-configuration` — read it in full before filling placeholders

`<generator>` follows `stack.concurrency`: `java` with `library=restclient` for blocking (matching the
`spring-restclient` starter), or `java` with `library=webclient` for reactive. Confirm the library name against
the generator's current option list rather than assuming — these names have changed across generator versions.
No HTML documentation execution is needed for clients: the contract belongs to the downstream service and is
documented there.

**Kotlin + Maven** — blocking (`stack.concurrency` blocking or both, per client):

Template: `templates/maven-kotlin/infrastructure/clients/__name__/pom.xml.rest-client-blocking-configuration` — read it in full before filling placeholders

The adapter builds `ItemsApi(restClient)` from the `RestClient` Boot auto-configures (`spring-boot-starter-restclient`).
The library also generates a small `infrastructure` package (`ApiClient`, `RequestConfig`, `Serializer`) under the
generated package — part of the generated output, excluded like the rest.

Reactive — **not** `kotlin` + `jvm-spring-webclient` (Jackson 2 only, see Step 0); use Spring's declarative HTTP
interface instead:

Template: `templates/maven-kotlin/infrastructure/clients/__name__/pom.xml.rest-client-reactive-configuration` — read it in full before filling placeholders

It emits `interface ItemsApi { @HttpExchange(...) suspend fun getItem(...): ResponseEntity<Item> }`; the adapter
module's configuration binds it with
`HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClientBuilder.baseUrl(<url>).build())).build().createClient<ItemsApi>()`
(`spring-boot-starter-webclient` + `kotlinx-coroutines-reactor`). The same library with `reactive=false` would serve
blocking clients too, over `RestClientAdapter` — stay with `jvm-spring-restclient` there unless the team prefers one
client style everywhere, and say which was chosen.

**Give every operation at least one request/response `example` pair.** This spec has a second consumer besides the
generator: the API mock in the compose stack (`stack.apiMock` — Microcks or WireMock, set up by
`iru-setup-java-springboot-testcontainers`) is built from this file, and it is the examples, not the schemas, that
become the mocked responses. A spec with complete schemas and no examples generates a perfectly good client and a
mock with nothing to say — which surfaces later as an integration test failing against an empty response body,
a long way from its cause. Name each example after the case it represents (`found`, `not-found`, `rate-limited`),
and cover the error responses too, since the adapter's exception translation is what most needs testing.

## Step 5 — gRPC clients

Per entry in `stack.grpcClients`, put the downstream `.proto` at `apis/grpc-client/<name>/<name>.proto`, with the
same "this is someone else's contract" comment. Wire the protobuf plugin into
`infrastructure/clients/<name>/pom.xml` with `java_package` under
`<base>.infrastructure.clients.<name>.generated`, generating the stubs. Generate only the client stubs, not
service base classes. **Kotlin:** add the same `kotlinEnabled` + `protoc-gen-grpc-kotlin` configuration as Step 3 —
the adapter then calls `OrderServiceGrpcKt.OrderServiceCoroutineStub(channel)` with `suspend` calls instead of a
blocking or future stub (an unused `CoroutineImplBase` is generated alongside; harmless, since nothing extends it).

This `.proto` is also what the compose stack's mock serves. A protobuf file has no `examples` section, so the
sample responses live alongside it — Microcks reads them from a companion examples artifact next to the `.proto`
in the same directory (a Postman collection or a Microcks-format YAML; check the pinned version's supported
formats), while WireMock's gRPC extension needs a descriptor set built from this file plus hand-written mappings.
Whichever `stack.apiMock` names, put those example files **in this same directory**, so a downstream contract and
the responses mocked for it are updated in one place.

## Step 5a — GraphQL clients

Per entry in `stack.graphqlClients`, put the downstream service's SDL at
`apis/graphql-client/<name>/<name>-api.graphqls`, with the same "this is someone else's contract, replace it with
the real published schema" comment as the other client directories. A GraphQL server can produce its own SDL
(`spring.graphql.schema.printer.enabled` exposes it, and any introspection-enabled endpoint can be dumped with
`gql-cli`/`apollo client:download-schema`), so say in `apis/README.md` that this file should be refreshed from the
downstream service rather than hand-edited.

Wire into `infrastructure/clients/<name>/pom.xml`:

Template: `templates/maven/infrastructure/clients/__name__/pom.xml.graphql-client-configuration` — read it in full before filling placeholders

**Client generation needs a runtime dependency that server generation doesn't.** The generated
`<Op>Request`/`<Type>ResponseProjection` classes extend base types from
`io.github.kobylynskyi:graphql-java-codegen` — the same artifact as the plugin, at the same version. Add it as a
compile dependency of the client module (version-managed in the root pom like everything else). Without it the
module generates and then fails to compile with `cannot find symbol: class GraphQLResponseField`, which reads like
a generator bug rather than a missing dependency.

**Kotlin:** add `<generatedLanguage>KOTLIN</generatedLanguage>`; the generated `<Op>QueryRequest`/
`<Type>ResponseProjection` are Kotlin classes, used with
`com.kobylynskyi.graphql.codegen.model.graphql.GraphQLRequest(request, projection).toHttpJsonBody()` — the
`graphql-java-codegen` dependency is still required (verified).

The adapter itself uses Spring's `HttpGraphQlClient` (reactive) or `HttpSyncGraphQlClient` (blocking), matching
`stack.concurrency`; the generated request objects serialise the query and the projections declare which fields
come back.

This SDL is also what the compose stack's mock serves when `stack.apiMock` is Microcks, which reads GraphQL
schemas directly. Put the example responses in this same directory alongside the SDL, as for the other client
kinds — a downstream contract and the responses mocked for it belong in one place. If `stack.apiMock` is WireMock,
note in the report that GraphQL stubs must match on the request body rather than the path (every operation is a
`POST /graphql`) and that the SDL is not consumed by the mock at all, so the two can drift.

## Step 6 — AVRO messaging schemas

Only if `stack.messaging.enabled`. Steps 6, 6a and 6b are all conditional on it.

Write one starter schema per event at
`apis/messaging/<event-name>-v1.avsc`: a record with `namespace` ending in `.v1`, a `doc` on the record and on
**every** field, and — critically — a **default value on every optional field**. Defaults are what make schema
evolution work: a field added without one is a backward-incompatible change that the registry will reject and that
breaks every existing consumer.

Wire into whichever of `infrastructure/producers` and `api/consumers` exist (both, if the service produces and
consumes):

Template: `templates/maven/infrastructure/producers/pom.xml.avro-plugin` — read it in full before filling placeholders

`stringType=String` matters: the default (`CharSequence`) makes generated classes awkward to map with MapStruct —
and, for Kotlin, makes every string field a `CharSequence` the extension-function mappers must convert.

**Kotlin + Maven** — unchanged. The output is Java; `kotlinc` resolves it from the registered
`generated-sources/avro` root and `javac` compiles it (verified). Kotlin callers use the builder
(`OrderCreated.newBuilder().setOrderId(…).build()`); nullable union fields (`["null","string"]`) surface as
platform types, so the producer/consumer adapters should map them to Kotlin `String?` explicitly.

The Confluent AVRO serializer resolves schemas through the schema registry at runtime, so also record in the
report that the registry's **compatibility mode** (`BACKWARD` is the usual choice) has to be configured in the
registry itself — neither this scaffold nor the poms can set it. If a subject naming strategy other than the
default is wanted (e.g. `TopicRecordNameStrategy` for multiple event types per topic), it must be configured in the
producer/consumer binding properties; note whether you set one.

## Step 6a — The AsyncAPI specification

The AVRO schemas say what a message *looks like*. They say nothing about which topic carries it, in which
direction, under which consumer group, on which broker — so on their own they can't document the messaging
interface the way the OpenAPI spec documents the REST one. That's what the AsyncAPI document is for.

Write one at `apis/messaging/<service>-async-api-v1.yaml`, declaring the **current AsyncAPI version** — 3.1.0 at
the time of writing. Confirm what's current rather than copying that number: `npx asyncapi validate <spec>` reports
`asyncapi-latest-version` as an informational finding when a newer one exists, so the toolchain tells you directly.

Template: `templates/common/apis/messaging/__service__-async-api-v1.yaml` — read it in full before filling placeholders

Three things to get right, each of which silently produces wrong documentation rather than an error:

- **`action` is written from this service's point of view.** `send` is what the service produces, `receive` is what
  it consumes. This is the one thing AsyncAPI 3 deliberately fixed from 2.x, where `publish`/`subscribe` meant the
  opposite of what nearly everyone assumed. If you're adapting an older 2.x document, its `subscribe` becomes
  `send` and its `publish` becomes `receive` — not the other way round.
- **Payloads are `$ref`s to the `.avsc` files**, under a `schemaFormat` + `schema` pair, resolved relative to the
  AsyncAPI file — which is exactly why the two live in the same directory. Restating the fields inline creates a
  second copy that drifts from the one the code is generated from.
- **`address` is the topic name from the binding configuration**, not the channel key. Cross-check against
  `spring.cloud.stream.bindings.*.destination` and say in the report if the bindings don't exist yet.

Cover both directions in this one file if the service both produces and consumes; it documents the service's whole
messaging interface, not one module's half of it. Give `info.description`, every channel, every operation
(`summary`), and every message a real description — as with the OpenAPI spec, the generated pages are only as good
as the text in the contract. Add a `v2` file alongside when a breaking change arrives, following the same rule as
the other contracts.

## Step 6b — AsyncAPI documentation generation, wired into the build

The official AsyncAPI HTML generator runs over the AsyncAPI file on every build, producing a static documentation
site published to GitHub Pages next to the REST and gRPC pages. It is driven by `@asyncapi/cli`, which bundles the
generator; `@asyncapi/html-template` is the template it renders with.

Template: `templates/common/apis/messaging/docs/package.json` — read it in full before filling placeholders

Check whether the `overrides` block is still needed before adding it — run `npm install` without it first and keep
it only if npm actually fails with `ETARGET`. A stale override silently pins a transitive dependency below what the
CLI expects.

Write it to `apis/messaging/docs/package.json`, run `npm install` there once, and **commit the resulting
`package-lock.json`** — it is what makes the documentation build reproducible and what lets CI use `npm ci`. Add
`apis/messaging/docs/node_modules/` to `.gitignore`. Verify the install actually succeeds before moving on; if npm
reports `ETARGET` on a package other than `generator-hooks`, pin that one in `overrides` too and record it in the
report rather than leaving a build that only works on your machine.

On Gradle, `package.json`/lock file/`.asyncapi-analytics` are identical and the build wiring is the
`generateAsyncApiDocs` task of Step 6c instead of the plugin below.

Then wire the generator into Maven with `frontend-maven-plugin`, so `mvn generate-resources` produces the
documentation with no globally installed Node — the same guarantee the REST and gRPC documentation already have.
Put the executions in **one** module only: `infrastructure/producers` if it exists, otherwise `api/consumers`. The
AsyncAPI document describes the whole service, so generating it in both modules would just publish the same pages
twice under two paths.

Template: `templates/maven/infrastructure/producers/pom.xml.asyncapi-docs-plugin` — read it in full before filling placeholders

Declare `<asyncapi.docs.skip>false</asyncapi.docs.skip>` as a root-pom property so a developer with no network can
build with `-Dasyncapi.docs.skip=true`, and say so in the report — a build step that downloads Node and ~1600 npm
packages must have an escape hatch, and CI must **not** use it or the published documentation goes stale silently.

The executions bind to `generate-resources`, the same phase as the REST `html2` and gRPC `protoc-gen-doc`
executions, so every API documentation artifact in the repository is produced by one predictable command. Add one
validate/generate pair per AsyncAPI version file, writing into `.../messaging/v2/` when `v2` arrives.

Version numbers: `frontend-maven-plugin` goes in the root pom's `<pluginManagement>` like every other plugin —
add it there (see `iru-setup-java-springboot-pom`) rather than inline here. The npm package versions are the one
exception to this repository's no-inline-versions rule, because they live in `package.json`, not in a pom; pin them
to exact currently-published versions and let the committed lock file do the rest.

## Step 6c — Gradle wiring (`kotlin` + `gradle` only)

Skip on Maven. On Gradle every generator from Steps 2–6b becomes a task in the module that consumes the contract,
with the same packages, the same options (Step 2's Kotlin `configOptions` map one-to-one), and the same per-version
rule (one task per contract version file). Verified end to end on Gradle 8.14 (`gradle:8-jdk21`), Kotlin 2.3.21,
Spring Boot 4.1.1: generation, `compileKotlin` of hand-written Kotlin implementing every generated interface,
`build -x test`, and the `html2`/`protoc-gen-doc` documentation tasks. (The Kotlin + Maven snippets of Steps 2–6
were verified the same way with Maven 3.9: `mvn clean compile` over Kotlin code implementing every generated
interface, with `kotlin-maven-plugin`'s mixed-source configuration.) The npm `Exec` tasks were checked for task
wiring only — their commands are the ones Steps 3b/6b already run.

**Catalog (`gradle/libs.versions.toml`)** — add only what the manifest needs; resolve each version live (Step 0):

Template: `templates/gradle-kotlin/gradle/libs.versions.toml.append` — read it in full before filling placeholders

Root `build.gradle.kts` gains `alias(libs.plugins.<x>) apply false` for each codegen plugin used, so every module
loads one copy of it.

**Module `build.gradle.kts`** — the fragments below go into whichever module Step 0's table names (`:api:rest-server`
gets the REST server and its docs task, `:infrastructure:clients:<name>` its client task, and so on); the module's
existing `plugins {}` (the `iru.*` convention plugins from `iru-setup-java-springboot-pom`) keep their place:

Template: `templates/gradle-kotlin/__module__/build.gradle.kts.codegen` — read it in full before filling placeholders

Add the runtime libraries each module's generated code needs to its `dependencies {}` (Step 3/4/5a lists them; on
Gradle they are `implementation(libs.…)` aliases with no version, resolved through the
`platform(libs.spring.boot.dependencies)` the convention plugin already applies, except `avro` and
`graphql-java-codegen`, which carry catalog versions).

**API documentation tasks** — all in group `documentation`, all writing under `<module>/build/generated-docs/`
(the Gradle twin of `target/generated-docs/`), which is where `iru-setup-java-springboot-github-workflows` looks
for them (its survey runs `./gradlew -q :<project>:tasks --group documentation` per codegen module and passes the
non-npm ones to its docs job — the root-level `./gradlew tasks --group documentation` omits subproject tasks such as
`generateRestServerV1Docs`, listing only `generateApiDocs`).
Use exactly these names, so that discovery is predictable:

| Task | Module | Writes | Replaces (Maven) |
|---|---|---|---|
| `generateRestServerV<n>Docs` | `:api:rest-server` | `build/generated-docs/rest-server/v<n>/index.html` | `generate-rest-server-docs` (`html2`) |
| `generateGrpcServerDocs` | `:api:grpc-server` | `build/generated-docs/grpc-server/v1/index.html` | `protoc-gen-doc` execution |
| `generateAsyncApiDocs` | `:infrastructure:producers` (else `:api:consumers`) | `build/generated-docs/messaging/v<n>/` | Step 6b `frontend-maven-plugin` |
| `generateGraphqlDocs` | `:api:graphql-server` | `build/generated-docs/graphql-server/v<n>/` (+ `voyager/`) | Step 3b `frontend-maven-plugin` |
| `generateApiDocs` | each of the above | aggregate: depends on that module's doc tasks | — |

Template: `templates/gradle-kotlin/__module__/build.gradle.kts.docs-tasks` — read it in full before filling placeholders

Two Gradle-only notes for the report: the CI workflow on Gradle runs the npm toolchains directly (not through these
`Exec` tasks), so the tasks exist for local builds and for discovery — keep their output paths identical to the
workflow's; and the `doc` protoc plugin's output directory is registered on the source set like every protoc output,
which is harmless (it holds only `index.html`) but is why `doc` belongs only in the gRPC server module.

## Step 7 — Verify generation actually works

Delegate to `iru-gate-runner`:

```bash
# Maven (Java or Kotlin)
mvn -q clean generate-sources    # every generator runs
mvn -q clean compile             # hand-written code compiles against the generated code

# Gradle (Kotlin)
./gradlew clean build -x test -x integrationTest   # every generator + compileKotlin/compileJava
./gradlew generateApiDocs -PapiDocsNpmSkip=true      # html2 + protoc-gen-doc (drop the flag when Node is available)
./gradlew -q :api:rest-server:tasks --group documentation   # per codegen module: the doc tasks the workflow skill discovers
```

Then confirm by inspection:

- generated sources exist under each expected module's `target/generated-sources/` (Gradle:
  `build/generated/openapi/`, `build/generated/graphql/`, `build/generated/sources/proto/main/{java,kotlin,grpc,grpckt}`,
  and the AVRO plugin's output);
- for Kotlin, at least one hand-written class per generated contract compiles against it (a controller implementing
  the REST interface, a `CoroutineImplBase` subclass, a resolver implementation) — generation succeeding proves
  nothing about whether the Kotlin signatures are the ones the modules skill's adapters expect;
- the packages match what Steps 2–6 configured, so the hand-written mappers and controllers resolve;
- if GraphQL is enabled, `target/classes/graphql/` (Gradle: `build/resources/main/graphql/`) actually contains the SDL, and no generated file imports
  `javax.validation` — both are silent-at-generation, loud-later failures (a service that won't start, and a
  module that won't compile);
- if GraphQL is enabled, `generated-docs/graphql-server/v1/index.html` exists and shows the SDL descriptions, and
  (when Voyager was wanted) `generated-docs/graphql-server/v1/voyager/index.html` exists with
  `voyager.standalone.js` and `voyager.css` beside it;
- generated HTML documentation exists under `target/generated-docs/` (Gradle: `build/generated-docs/`), including
  `generated-docs/messaging/v1/index.html` if messaging is enabled;
- that page actually lists the AVRO field names and their `doc` text. The `asyncapi-validate` execution catches the
  usual causes before generation — a `$ref` to a missing `.avsc` and an `.avsc` that isn't valid AVRO both fail it
  with `asyncapi-document-resolved` errors, since the validator resolves and type-checks the referenced schema —
  so an empty payload table here means something subtler, most often a message declared in `components` but never
  wired to a channel or an operation.

A generator that runs but produces nothing usually means a wrong `inputSpec`/`sourceDirectory` path — remember
these are absolute via `${maven.multiModuleProjectDirectory}` (Gradle: `rootProject.layout.projectDirectory.dir("apis")`),
because the contract lives outside the module. On Gradle, `Unresolved reference` from `compileKotlin` for a class
that *was* generated means a missing task dependency — a source directory added by path instead of by task
provider, or the AVRO `compileKotlin` dependency of Step 6c left out.

Fix whatever fails. This is the step most likely to need real iteration, particularly the protobuf plugin's
configuration schema and the OpenAPI generator's `library` option names — check the plugin's actual documentation
rather than trying variations.

## Step 8 — Keep generated output out of git and out of analysis

- Confirm `.gitignore` covers `target/` (Gradle: `build/`), which already covers the generated sources and
  `generated-docs`. If any generator was configured to write outside `target/`/`build/`, either change it to write inside `target/` — strongly
  preferred — or add an explicit ignore entry and explain why in the report.
- Add `apis/graphql-server/docs/node_modules/` to `.gitignore` if the GraphQL documentation toolchain was set up;
  its `package.json`, `package-lock.json`, `spectaql-*.yml` and `build-voyager.mjs` are committed.
- Add `apis/messaging/docs/node_modules/` to `.gitignore` if the AsyncAPI toolchain was set up — it's the one
  generated directory that can't live under `target/`, because npm requires it beside its `package.json`. Its
  `package.json`, `package-lock.json`, and `.asyncapi-analytics` **are** committed; nothing else in that directory
  is.
- Confirm the root pom's Checkstyle `sourceDirectories`, PMD `excludeRoots`, SpotBugs `excludeFilterFile`, JaCoCo
  `excludes`, and `sonar.exclusions` all cover the generated packages. `iru-setup-java-springboot-pom` sets these
  up; verify they match the package names actually configured here, and correct them if the generated packages
  ended up somewhere the exclusions don't reach. Thousands of findings on generated DTOs is how a team learns to
  ignore the quality report entirely.
- **Kotlin:** detekt and ktlint replace Checkstyle/PMD/SpotBugs, and both read *registered source roots*, which now
  include the generated Kotlin. On Maven, confirm the detekt/ktlint plugin configuration in the root pom limits
  input to `src/main/kotlin`/`src/test/kotlin` (or excludes `**/generated-sources/**`); on Gradle, confirm the
  `iru.quality` convention plugin excludes generated files — detekt via `source.setFrom("src/main/kotlin",
  "src/test/kotlin")` (or `exclude("**/generated/**")` on the `Detekt` tasks), ktlint via
  `filter { exclude { it.file.path.contains("/build/") } }` — and that JaCoCo's class-directory exclusions and
  `sonar.exclusions` name the generated packages. Run `./gradlew detekt ktlintCheck` (or the Maven equivalents)
  once and look at the finding count: hundreds of findings in `…generated…` packages mean the exclusion is
  missing. Correct it in the owning file and say so in the report; Dokka should likewise not document generated
  packages (`suppressedFiles`/`perPackageOption` with `suppress = true` on `.*\.generated(\..*)?`).

## Step 9 — Contract linting (recommended)

Add a lightweight check per contract kind so a malformed contract fails in CI rather than at generation time, and
report what you added:

- **OpenAPI** — the generator's own `validateSpec` option (on by default) catches structural errors. For style and
  breaking-change detection, note Spectral (`@stoplight/spectral-cli`) and `oasdiff` as the tools to add to CI;
  wire one in if the user wants it now.
- **protobuf** — `buf lint` and `buf breaking` are the standard; adding a `buf.yaml` is cheap and catches
  field-number reuse, which is otherwise a silent wire-compatibility break.
- **AVRO** — schema compatibility is checked by the registry. Note that
  `io.confluent:kafka-schema-registry-maven-plugin` can run `test-compatibility` against a real registry in CI,
  which catches an incompatible schema before deploy rather than at first publish.
- **GraphQL** — the generator parses the SDL, so a syntactically broken schema already fails the build. For style
  and breaking-change detection, note GraphQL Inspector (`@graphql-inspector/cli`, with a `diff` command that
  classifies each change as breaking, dangerous, or safe) and `graphql-schema-linter` as the tools to add to CI;
  wire one in if the user wants it now. Breaking-change detection matters more here than for REST, because GraphQL
  has no URL version to hide behind — removing a field breaks every client that selected it.
- **AsyncAPI** — already wired in Step 6b as the `asyncapi-validate` execution, so a malformed contract fails with
  a validation error rather than an obscure generator crash. `asyncapi validate` also reports
  `asyncapi-latest-version` informationally when a newer specification version is available, which is the cheapest
  way to notice the document has fallen behind. Note `asyncapi diff` as the breaking-change check to add to CI
  later.

## Step 10 — Report

Summarize:

- Whether the API layer was created from scratch or updated, and per pre-existing artifact the action taken
  (**created / updated / replaced / kept as is**), with each gap left in a kept artifact.
- Every contract file created, with its path and what it describes.
- The language and build tool read from the manifest, and for Kotlin which generator was chosen per contract
  (Step 0's table) and any deviation from it with the reason.
- Every generator execution (Maven) or task (Gradle) added, to which module, generating into which package, and
  where its HTML documentation lands — on Gradle, the exact `documentation`-group task names, since the workflows
  skill discovers them.
- On Gradle, every `gradle/libs.versions.toml` entry added, with each version and where it was resolved from, and
  the AVRO plugin's maintenance status at the time of the run.
- The `reactive`/`library` settings resolved from `stack.concurrency`, and the protobuf plugin flavour detected.
- For GraphQL: where the SDL is copied to for runtime loading, that `modelValidationAnnotation` was set to the
  jakarta annotation, whether `graphql-java-codegen` was added to each GraphQL client module, the query
  depth/complexity limits chosen, whether Voyager was generated alongside SpectaQL, and the published paths of
  both.
- For messaging: the AsyncAPI version declared, which module hosts the documentation executions, the exact npm
  package versions pinned in `apis/messaging/docs/package.json`, whether `npm install` needed any `overrides` at
  all, and the Node version pinned in `frontend-maven-plugin` (Gradle: that the `Exec` tasks use the Node on `PATH`).
- The result of Step 7, including whether HTML documentation was actually produced for REST, gRPC, GraphQL and
  messaging, whether the messaging page's payload tables came out populated rather than empty, and whether the
  GraphQL pages showed real descriptions and a drawn schema graph.
- Whether the analysis exclusions in Step 8 already covered the generated packages or had to be corrected.
- What linting was wired versus merely recommended.

Warn explicitly:

- **The client specs under `apis/rest-client/` and `apis/grpc-client/` are placeholders for someone else's
  contract** — they must be replaced with the downstream service's real published spec before the client adapters
  mean anything, and kept in sync afterwards. They have two consumers, not one: the client generator *and* the
  compose stack's API mock (`stack.apiMock`), so a spec left as a placeholder produces both a useless client and a
  mock that agrees with it. Report which client specs still carry no `examples`, since those mock nothing.
- **Generated code must never be committed or edited.** Fix the spec or the generator configuration instead.
- **A GraphQL endpoint has no depth or complexity limit by default**, and the numbers set here are a guess until
  someone tunes them against the real schema. Until then a single nested query can exhaust the service. This is the
  one item in this skill's output that is a live availability risk rather than a tidiness concern.
- **The GraphQL SDL is read twice — at build time and at startup.** Changing where it lives, or the
  `spring.graphql.schema.locations` property, breaks the second one only, and only at runtime.
- The AVRO registry compatibility mode and the gRPC field-numbering discipline are enforced outside this
  repository (in the registry, and by review or `buf breaking`) — the scaffold can't enforce either.
- **Adding an event means editing the `.avsc` *and* the AsyncAPI file.** An `.avsc` added alone compiles to a Java
  class that appears in no documentation at all, and nothing in the build will say so. State which direction
  (`send`/`receive`) each operation documents, since an inverted action is the one error here that produces a
  confident, wrong diagram rather than a failure.
- Every contract's descriptions and constraints feed the documentation pages, so an under-described spec produces
  under-described documentation. `/iru-update-java-springboot-documentation` reads these files.
