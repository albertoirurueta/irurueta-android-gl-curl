# Library binary compatibility and API surface

Conventions specific to a change in the `lib` module's public or `internal` surface — what makes a Kotlin/Android
library safe to consume from another Gradle module, from Java, and across a version bump without breaking
existing consumers. Read this alongside whichever of `compose.md`/`views.md` applies if the changed declaration
is also UI-facing.

## `public` is a promise; everything else defaults to `internal`

Every `public` class, function, and property in `lib` is part of `lib`'s published API the moment a release with
it ships — a consumer's code can call it, and `iru-android-bump-version`/the publish workflow doesn't know to
warn you before a `public` signature changes underneath them. Before widening a declaration from `internal`/
`private` to `public` (see `code-style.md`'s visibility section), ask: does a consumer of this library actually
need to call this, or is it something the library needs internally across its own files? Only the first case
justifies `public`. Once something is `public`, treat these as breaking changes that need a major version bump
(or, at minimum, a deliberate decision recorded in the task's report, not a silent side effect):

- Removing a `public` declaration, or narrowing its visibility.
- Removing or renaming a `public` function's parameter, changing its type, or reordering parameters that aren't
  all named at every call site.
- Changing a `public` function's or property's return/declared type to something not assignable to the old one.
- Adding a new abstract member to a `public` `interface`/non-`sealed` `abstract class` — existing implementors
  won't compile against it.
- Removing a constructor overload a Java caller relies on (see `@JvmOverloads` below) — Kotlin's own default
  parameters don't generate one, only the annotation does.

Adding a new `public` member, a new default-valued parameter to an existing Kotlin-only call site, or a new
overload is additive and safe.

## `@JvmStatic`, `@JvmOverloads`, `@JvmField`

These three annotations exist entirely for the library's Java consumers — a pure-Kotlin consumer never needs
them, but this catalog's Android libraries (see the reference sample's own consumer, its `app` module, and any
external Java project depending on the published artifact) can't assume every caller is Kotlin.

- **`@JvmStatic`** on a function or property inside a `companion object` (or an `object`) so Java sees a real
  static method (`OrientationHelper.getCameraDisplayOrientation(...)`) instead of having to write
  `OrientationHelper.INSTANCE.getCameraDisplayOrientation(...)`. `OrientationHelper` in the reference sample is a
  Kotlin `object` whose members are called exactly like the static utility class it replaces — worth
  `@JvmStatic` on every member if Java callers are a real audience for this specific type (an `object` used only
  internally by Kotlin code doesn't need it).
- **`@JvmOverloads`** on a constructor or function with default-valued parameters, so Java sees the full set of
  overloads a Kotlin caller gets "for free" via named/defaulted arguments — see `views.md`'s `GLTextureView`
  constructor for the case where skipping this actually breaks XML inflation, not just Java call convenience.
  Apply it to any `public` function/constructor with defaults that Java callers are expected to use.
- **`@JvmField`** on a `public` property to expose it as a plain Java field instead of a getter/setter pair —
  appropriate only for a `val`/`var` with no custom getter/setter logic and no need to ever add validation later
  (once it's a field, it can't become a computed property without breaking Java callers's direct field access).
  Rare; prefer a normal property unless a specific Java-interop need calls for it.

## `internal` + `@PublishedApi`

An `inline` `public` function can only call other `public` declarations — Kotlin's inliner would otherwise leak
an `internal` implementation detail's bytecode into the consumer's own compiled output, across the module
boundary `internal` exists to protect. When an `inline` `public` function genuinely needs to call an `internal`
helper, mark that helper `@PublishedApi internal` — it stays invisible in this module's public *source* API
(IDE autocomplete, KDoc) while being callable from the inline function, and it inherits the same
binary-compatibility obligations as `public` from that point on (a consumer's already-inlined call sites depend
on it existing with the same signature). Don't reach for `@PublishedApi` unless an `inline` function actually
requires it — it's a narrow escape hatch, not a general way to relax `internal`.

## Consumer ProGuard/R8 rules

`lib/proguard-rules.pro` configures how R8 shrinks/obfuscates *this module's own* release build; it does **not**
travel to a consumer app's build. A library that needs specific classes/members kept in every *consumer's*
shrunk build (e.g. a class only referenced via reflection, a `Parcelable`, a class this library's Dokka-visible
API exposes and R8's default keep rules for public APIs don't already cover) ships that requirement as a
**consumer ProGuard rules** file:

```kotlin
android {
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}
```

`consumer-rules.pro` (a separate file from `proguard-rules.pro`) is bundled into the published AAR and merged
into every consumer's own R8 run automatically — the library author never has to ask consumers to add anything by
hand. A task that adds a class reached only via reflection (e.g. a JSON-deserialized model, a class named from a
string) needs a `-keep` rule added there; a task that only adds ordinary, directly-referenced `public` API needs
nothing here, since directly-referenced code is already R8-safe by construction.

## `api` vs. `implementation`

In `lib/build.gradle.kts`, a dependency declared `implementation(...)` is invisible to `lib`'s own consumers'
compile classpath; one declared `api(...)` is re-exposed on it. The reference sample's own `dependencies` block
shows both in use side by side — `implementation(libs.material)` (an internal dependency, not part of the
public API) versus `api(libs.geometry)` (a dependency whose types — `Matrix`, `Rotation2D`,
`PinholeCameraIntrinsicParameters` — appear directly in `lib`'s own `public` function signatures, like
`OrientationHelper.toViewCoordinatesRotation`, so a consumer calling those functions necessarily needs that
dependency's types on their own classpath too).

- **Default to `implementation`.** Only promote a dependency to `api` when a `public` declaration in `lib`
  genuinely has that dependency's type in its signature (a parameter, return type, or a supertype of an exposed
  type) — never as a convenience so a consumer "gets it for free."
- **Never leak a transitive dependency via `api` that isn't itself part of the deliberate public surface.** A
  task that adds a new dependency only to use its types internally declares it `implementation`, full stop — if
  the compiler complains a `public` signature needs it as `api`, that's a signal the signature itself is
  exposing something it shouldn't (reconsider the signature, don't just widen the dependency scope to make the
  error go away).

## Explicit API mode (optional)

Kotlin's [explicit API mode](https://kotlinlang.org/docs/whatsnew14.html#explicit-api-mode-for-library-authors)
(`kotlin { explicitApi() }` in `lib/build.gradle.kts`) makes the compiler *require* an explicit visibility
modifier and return type on every `public` declaration, catching an accidentally-public declaration (one that
forgot an explicit `internal`) at compile time instead of at KDoc-audit time. This catalog's scaffold ships it
opt-in, not on by default (see `iru-setup-android-library`) — if the project has it enabled, a task adding a new
top-level/class member without an explicit visibility modifier and return type will fail to compile at Step 5's
compile-check; add both explicitly rather than disabling the mode to work around it.

## `@Deprecated` with `ReplaceWith`

Deprecating a `public` declaration instead of removing it outright (removal is the breaking change described
above) uses the annotation with both a message and a machine-applicable replacement whenever one exists:

```kotlin
@Deprecated(
    message = "Use toViewCoordinatesRotation(context, characteristics) instead.",
    replaceWith = ReplaceWith("toViewCoordinatesRotation(context, characteristics)")
)
fun toViewCoordinatesRotation(context: Context, cameraId: String): Rotation2D
```

`replaceWith` is what lets an IDE offer an automatic quick-fix at every call site — worth including whenever the
replacement is a straightforward substitution, and worth leaving out (with just `message`) when the migration
genuinely needs a human decision. Set `level = DeprecationLevel.WARNING` (the default) for a deprecation
consumers still need to compile against for now; only escalate to `DeprecationLevel.ERROR`/removal in a later,
deliberate major-version task — never as a side effect of the task that introduces the deprecation.
