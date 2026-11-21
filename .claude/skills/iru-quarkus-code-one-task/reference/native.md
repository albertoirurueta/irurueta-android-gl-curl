# Native executables (GraalVM / Mandrel)

Read this **only** when the build produces a native executable — a `native` Maven profile or
`quarkus.native.enabled=true`, `src/main/docker/Dockerfile.native*` actually used by CI, a Gradle `testNative`
task wired into CI, or `-Dquarkus.native.*` flags in a workflow — **and** the task adds something native-image's
closed-world analysis can't see. A JVM-only service ignores this file entirely; don't add native annotations
"just in case".

## What breaks, and the fix

| The task adds… | Under native it… | Fix |
|---|---|---|
| Reflection on an application class (Jackson/JSON-B on a type only reached reflectively, `Class.forName`, a library that reflects on your DTOs) | fails at runtime: class/field/method missing | `@io.quarkus.runtime.annotations.RegisterForReflection` on the class (`targets = {...}` for classes you can't annotate). REST request/response types used directly in resource signatures are registered automatically — don't annotate those. |
| Reflection on a third-party class you can't annotate | same | `@RegisterForReflection(targets = Foo.class)` on a holder class, or `src/main/resources/META-INF/native-image/<group>/<artifact>/reflect-config.json` referenced from `native-image.properties`. First check for a Quarkus/Quarkiverse extension for the library. |
| A classpath resource loaded with `getResourceAsStream` | missing at runtime | `quarkus.native.resources.includes=<glob>,…` |
| A JDK dynamic proxy for an interface | fails at runtime | prefer an extension that registers it; otherwise `proxy-config.json` |
| Java serialization (`ObjectInputStream`) | under-registered classes | use Jackson/JSON-B instead |
| A static initializer holding per-process state (`SecureRandom`, a loaded native library) | frozen into the image at build time | `quarkus.native.additional-build-args=--initialize-at-run-time=<class>` |
| Kotlin reflection (`kotlin-reflect`, `KClass` member access) | partially missing | register the classes; avoid `kotlin-reflect` in hot paths |

## Rules

- Prefer static, build-time-visible code paths over reflection in new code; that's the cheapest native fix.
- Don't add `@RegisterForReflection` to beans, resources, entities, or `@ConfigMapping` interfaces — their
  extensions already register what's needed.
- Never run a native build from this skill (minutes and gigabytes). Native correctness is verified by
  `@QuarkusIntegrationTest` against a native artifact, in CI or a dedicated stage — note in the report which new
  code paths are reflection- or resource-dependent so that run covers them.
- JaCoCo cannot measure a native run; coverage always comes from the JVM-mode tests.
