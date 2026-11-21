# Kotlin/Android development reference

General Kotlin/Android code agreements for implementing a task in an Android library or app project this catalog
scaffolds (`iru-setup-android-library`, `iru-setup-android-app`) — genericized from
[irurueta-android-glutils](https://github.com/albertoirurueta/irurueta-android-glutils), a real published Android
library repository, as the style sample (its `GLTextureView`/`OrientationHelper` sources, `*Test.kt` files,
`lib/build.gradle.kts`, and `lib/proguard-rules.pro`). These files exist so `iru-android-code-one-task` can load
**only** what the task in front of it actually needs, instead of carrying every rule in context on every task.

## Read this much, and no more

Detect the module first (`iru-android-code-one-task` Step 1 — `lib` or `app`, from the task's file paths or
description), then read:

| Read | When |
|---|---|
| `code-style.md` | **Always.** Kotlin official style, `val`/`var`, null-safety, visibility, member ordering, naming, exceptions. |
| `kdoc.md` | The task adds or changes a class, object, interface, function, or public/internal property. |
| `testing.md` | The task writes or updates tests — which is nearly every task, since writing the tests is part of implementing it. |
| `compose.md` | The task adds or changes a `@Composable` function or other Jetpack Compose UI. |
| `views.md` | The task adds or changes a custom `View` subclass, ViewBinding usage, or XML-layout-backed UI. |
| `library-api.md` | The task changes a public or `internal` declaration in the `lib` module — binary compatibility, `@Jvm*` annotations, consumer ProGuard rules. |
| `ui-contract.md` + `ui-implementation.md` | The task adds or changes a screen, component, list or other UI — the catalog-wide UI contract (verbatim) and its mapping onto Views/Compose: decomposition, inputs/events, `RecyclerView`/`Lazy*` collections, Robolectric/Roborazzi and instrumented UI tests, the screenshot helper, accessibility checks. |

A task that only changes logic inside one existing function, with no signature change and no UI involved, needs
`code-style.md` plus `testing.md`, and nothing else. Don't read the rest speculatively. Read at most one of
`compose.md`/`views.md` — a project uses one UI toolkit or the other for a given screen/component, never both for
the same piece of UI. Any UI task reads `ui-contract.md` and `ui-implementation.md` in addition to that one.

## The three rules that override everything else

1. **Match the surrounding code.** Every rule here is the default for *new* code. If the file or package you're
   editing consistently does something else, follow it and note the divergence in the report rather than
   converting the file to this document's preference as a side effect of an unrelated task. Reordering or
   restyling untouched code turns a two-line change into an unreviewable diff.
2. **A task implements what the task says.** These references tell you *how* to write what was asked for. They
   never license adding an abstraction, a wrapper, or a "while I'm here" refactor the plan didn't ask for.
3. **This skill doesn't validate.** Beyond a compile-check of the module it touched, it never runs the full test
   suite, coverage, lint/detekt/ktlint, license headers, or a KDoc audit — the calling
   `iru-android-code-one-task-group` does all of that once for the whole task group. Write code that will pass
   those gates; don't run them here.

## Sources

Compiled from the following, cross-checked against each other and against the reference repository's own actual
code, with this catalog's own stated preferences taking precedence where they are stricter (notably on `!!`, on
`internal` as the default library visibility, and on companion-object/member placement, which some of these
leave to taste).

- Kotlin coding conventions: <https://kotlinlang.org/docs/coding-conventions.html>
- Android Kotlin style guide: <https://developer.android.com/kotlin/style-guide>
- Kotlin/JetBrains API guidelines for library authors: <https://kotlinlang.org/docs/api-guidelines-introduction.html>
- Kotlin documentation comments (KDoc): <https://kotlinlang.org/docs/kotlin-doc.html>
- Dokka: <https://kotlinlang.org/docs/dokka-introduction.html>
- Kotlin coroutines guide: <https://kotlinlang.org/docs/coroutines-guide.html>, Flow: <https://kotlinlang.org/docs/flow.html>
- JUnit 4 user guide: <https://junit.org/junit4/>
- MockK: <https://mockk.io/>
- Robolectric: <https://robolectric.org/>
- AndroidX Test: <https://developer.android.com/training/testing>, Espresso: <https://developer.android.com/training/testing/espresso>
- Jetpack Compose documentation: <https://developer.android.com/jetpack/compose/documentation>, state and
  Compose: <https://developer.android.com/jetpack/compose/state>
- Android library binary compatibility / API guidelines: <https://developer.android.com/topic/libraries/support-library/preparing-consumers>
- Keeping your Kotlin library binary-compatible (`@JvmStatic`/`@JvmOverloads`/explicit API mode):
  <https://kotlinlang.org/docs/whatsnew14.html#explicit-api-mode-for-library-authors>
- ProGuard/R8 consumer rules: <https://developer.android.com/build/shrink-code#consumer-proguard-rules>
- UI contract sources (Roborazzi, Accessibility Test Framework, Compose/Views lists, navigation): see
  `ui-implementation.md`'s own Sources list.
- Reference sample: <https://github.com/albertoirurueta/irurueta-android-glutils> (`lib/`, `lib/src/test`,
  `lib/build.gradle.kts`, `lib/proguard-rules.pro`)
