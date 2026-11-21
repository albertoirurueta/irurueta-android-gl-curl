# Quarkus development reference

Best practices and code-style rules for implementing a task in a Quarkus service, written in Java or Kotlin, laid
out either as a single-module (feature-packaged) application or as a DDD/hexagonal multi-module build. These files
exist so `iru-quarkus-code-one-task` can load **only** what the task in front of it actually needs, instead of
carrying every rule in context on every task.

## Read this much, and no more

| Read | When |
|---|---|
| `java.md` **or** `kotlin.md` | **Always — exactly one**, matching the language of the module being edited (both only for a mixed module where the task touches both). The non-negotiable code and test rules for that language. `kotlin.md` also holds the all-open/no-arg build check that must run *before* any Kotlin code is written. |
| `cdi-and-beans.md` | **Always.** Every Quarkus class is a CDI bean or is used by one; scope, injection style, and build-time discovery decide whether the code even runs. |
| `testing.md` | **Always.** Which of the four test levels to write, which ones start containers, and which ones this skill may run. |
| `hexagonal-architecture.md` | The repository is a hexagonal multi-module build (Step 0 of the skill decides). Skip it entirely for a single-module or layered service. |
| `rest.md` | The task adds or changes a `@Path` resource, an exception mapper, a filter, or a REST Client interface. |
| `persistence-panache.md` | The task touches an `@Entity`, a Panache entity/repository, a query, a `@Transactional` boundary, or a Flyway/Liquibase migration. |
| `reactive-mutiny.md` | The task returns or consumes `Uni`/`Multi`, uses a reactive client or Hibernate Reactive, writes a Kotlin `suspend` endpoint, or uses `@RunOnVirtualThread`. |
| `configuration.md` | The task adds or reads a configuration property, a `@ConfigMapping`, or a profile-specific value. |
| `native.md` | **Only** when the build has a native profile/flag (`-Pnative`, `quarkus.native.enabled`, `Dockerfile.native*`, a `testNative` task) **and** the task adds reflection, resources, proxies, or serialization. Never otherwise. |

A one-line change inside an existing bean needs the language file, `cdi-and-beans.md`, and `testing.md`, and
nothing else. Don't read the rest speculatively.

## The three rules that override everything else

1. **Follow the architecture the repository already has.** Apply `hexagonal-architecture.md` only to a hexagonal
   multi-module build; in a single-module service, put new code in the existing feature package next to its
   siblings, using the same resource → service → repository layering they use. Never introduce a module, a port,
   or a layer the plan didn't ask for.
2. **A task implements what the task says.** These references tell you *how* to write what was asked for; they
   never license switching a module from Panache active record to repositories, from blocking to reactive, from
   Mutiny to coroutines, or from field to constructor injection as a side effect.
3. **No containers.** This skill runs plain JUnit and `@QuarkusComponentTest` classes only, with Dev Services
   disabled. `@QuarkusTest` and `@QuarkusIntegrationTest` belong to the calling group skill. See `testing.md`.

The language file (`java.md` / `kotlin.md`) sits alongside these: where any other file here says "match the
surrounding code", its mandatory rules are the exception — the code you write follows them even where the
module's existing code doesn't (Panache public fields and the entity-shape conventions in
`persistence-panache.md` are the documented exceptions to the Java accessor/`final` rules).

## Sources

Compiled from the user's own documentation, cross-checked against the official Quarkus guides. Where they
disagree with this catalog's Java rules (e.g. AssertJ in the docs' examples), the catalog's rules win and the
file says so.

- irurueta docs: `backend/quarkus/cdi-and-the-programming-model.adoc`, `architecture-and-build-time.adoc`
- irurueta docs: `backend/quarkus/rest-services.adoc`, `rest-client.adoc`, `virtual-threads.adoc`
- irurueta docs: `backend/quarkus/panache.adoc`, `hibernate-orm-and-jpa.adoc`, `datasources-and-transactions.adoc`,
  `database-migrations.adoc`, `reactive-data-access.adoc`
- irurueta docs: `backend/quarkus/reactive-programming-and-mutiny.adoc`
- irurueta docs: `backend/quarkus/configuration.adoc`, `developer-experience.adoc` (Dev Services)
- irurueta docs: `backend/quarkus/native-executables.adoc`
- irurueta docs: `backend/quarkus/testing.adoc`
- irurueta docs: `backend/quarkus/java-or-kotlin.adoc`, `build-tooling-and-languages.adoc`
- This catalog: `iru-java-code-one-task/reference/` (Java rules), `iru-java-springboot-code-one-task/reference/hexagonal-architecture.md`
- Quarkus guides: <https://quarkus.io/guides/cdi-reference>, <https://quarkus.io/guides/rest>,
  <https://quarkus.io/guides/rest-client>, <https://quarkus.io/guides/hibernate-orm-panache>,
  <https://quarkus.io/guides/hibernate-orm-panache-kotlin>, <https://quarkus.io/guides/mutiny-primer>,
  <https://quarkus.io/guides/config-mappings>, <https://quarkus.io/guides/writing-native-applications-tips>,
  <https://quarkus.io/guides/getting-started-testing>, <https://quarkus.io/guides/testing-components>,
  <https://quarkus.io/guides/dev-services>, <https://quarkus.io/guides/kotlin>,
  <https://quarkus.io/guides/tests-with-coverage>
- Kotlin compiler plugins: <https://kotlinlang.org/docs/all-open-plugin.html>, <https://kotlinlang.org/docs/no-arg-plugin.html>
