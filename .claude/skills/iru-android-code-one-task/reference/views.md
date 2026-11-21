# Custom Views and ViewBinding

Conventions specific to classic `View`-based UI: a custom `View`/`ViewGroup` subclass (most relevant to the
`lib` module — a library that ships a reusable view, like the reference sample's `GLTextureView`), or
ViewBinding-backed screens in the `app` module. Everything in `code-style.md`, `kdoc.md`, and `testing.md` still
applies. Read `compose.md` instead for a task that touches a `@Composable` — a screen/component is classic Views
or Compose, not both. For anything that is a screen, component or list, also read `ui-contract.md` and
`ui-implementation.md` — decomposition, single-Activity navigation, recycling collections, UI tests, screenshots and
accessibility checks live there.

## Screens are thin Fragments; lists are `RecyclerView`s

- One `Activity` hosts a `NavHostFragment`; each screen is a `Fragment` destination of the navigation graph that
  only inflates its binding, collects the view model's state (`repeatOnLifecycle(Lifecycle.State.STARTED)`),
  calls `render(state)` on its components and forwards their events — navigation calls stay in the Fragment, never
  in a component, adapter or `ViewHolder`.
- Reusable UI is a custom or compound `View` (a `<merge>` layout inflated with ViewBinding) exposing
  `render(state)` and typed listener properties — not a `Fragment`, and not a subclass of another screen.
- Every data-driven or long list/grid is a `RecyclerView` + `ListAdapter` + `DiffUtil.ItemCallback` (stable ids,
  one `ViewHolder` per item type, `ConcatAdapter` to combine); Paging 3 `PagingDataAdapter` for paged data;
  `ViewPager2` + `TabLayoutMediator` for pages and many tabs. Never a `LinearLayout` filled with `addView` in a
  loop, and never a `wrap_content` `RecyclerView` inside a `NestedScrollView`. A `ViewHolder` keeps no selection
  or expansion state of its own — that lives in the view model, keyed by id. See `ui-implementation.md` for the
  adapter sketch.
- Layouts use `start`/`end` (`paddingStart`, `layout_constraintStart_toStartOf`, `textAlignment="viewStart"`), text
  comes from string resources, and directional vector drawables set `android:autoMirrored="true"`.

## Custom `View` subclasses: the `AttributeSet` constructor triplet

A custom `View` meant to be usable both from code and from an XML layout (including via a library consumer's own
XML) exposes the same three constructors Android's own views do, collapsed into one via `@JvmOverloads` exactly
as the reference sample's `GLTextureView` does:

```kotlin
open class GLTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr)
```

- **`@JvmOverloads`** generates the three overloads (`View(Context)`, `View(Context, AttributeSet?)`,
  `View(Context, AttributeSet?, Int)`) a Java caller or the Android view-inflation machinery expects, from the
  single Kotlin constructor with defaults — without it, a Kotlin default-parameter constructor compiles to only
  *one* JVM constructor, and inflating the view from an XML layout fails at runtime with a `NoSuchMethodException`
  the compiler gives no warning about. This is the single most common mistake writing a custom Kotlin `View`; see
  `library-api.md` for the broader `@Jvm*` picture.
- `attrs: AttributeSet? = null` is always nullable — a view constructed purely from code passes no attributes.
- If the view reads custom XML attributes, do it in an `init { }` block using a `TypedArray` obtained via
  `context.obtainStyledAttributes(attrs, R.styleable.MyView)`, and **always** `recycle()` it (a `try`/`finally`,
  or `.use { }` if the project has the `androidx.core:core-ktx` `TypedArray.use` extension) — a leaked
  `TypedArray` is a real, easy-to-introduce resource leak.

## Lifecycle: `onAttachedToWindow`/`onDetachedFromWindow`

Any resource a custom view creates that outlives a single draw call — a background thread, a registered
listener, a native/GL resource — is acquired in `onAttachedToWindow()` and released in `onDetachedFromWindow()`,
never left to a `finalize()`/GC-triggered cleanup as the only safety net (a view attached and detached repeatedly,
e.g. inside a `RecyclerView`, must not leak or double-acquire). The reference sample's `GLTextureView` follows
this exactly:

```kotlin
override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (detached && renderer != null) {
        initializeGlThread()
    }
    detached = false
}

override fun onDetachedFromWindow() {
    glThread?.requestExitAndWait()
    detached = true
    super.onDetachedFromWindow()
}
```

- Call `super.onAttachedToWindow()` **before** your own setup, and `super.onDetachedFromWindow()` **after** your
  own teardown — the reference sample does the latter deliberately (`requestExitAndWait()` runs first, so the
  thread is fully stopped before the superclass tears down the surface it might still be using).
  `onAttachedToWindow`'s super call comes first because superclass state (like the view's `Context`/window
  attachment flags) is expected to be established before subclass logic that might rely on it runs.
  Match whichever pattern the class's superclass actually requires — `SurfaceView`/`TextureView`'s own docs are
  explicit about ordering; a `ViewGroup` subclass may have different requirements.
- A resource explicitly `set` before attachment (a renderer passed via `setRenderer()`, as in the reference
  sample) needs its own guard for "already set" (`checkRenderThreadState()`/`check()`, see `code-style.md`) so a
  caller can't reconfigure a view mid-lifecycle in a way that leaves two resources alive at once.

## `SurfaceView`/`TextureView` patterns

A view that draws through its own thread (OpenGL, camera preview, custom rendering) — the reference sample's
`GLTextureView` is the worked example — separates three concerns, each worth keeping separate when writing a
similar view:

1. **The view itself** owns the public API (`setRenderer`, `renderMode`, `onPause`/`onResume`) and forwards
   lifecycle events (`SurfaceTextureListener` callbacks, `onAttachedToWindow`/`onDetachedFromWindow`) to the
   rendering thread — it never draws directly on the UI thread.
2. **A dedicated rendering thread** (`GLThread` in the reference sample) owns the actual draw loop and every
   piece of state the draw loop reads, protected by its own lock/condition rather than shared unguarded with the
   UI thread — the reference sample's `GLThreadManager` is a small monitor object purely for this, not general
   application state.
3. **A `WeakReference` back to the view** from the rendering thread (`glSurfaceViewWeakRef` in the reference
   sample), never a strong reference — the thread must not be the reason the view (and the `Activity`/`Fragment`
   holding it) is kept alive after the caller has otherwise let go of it. `@Suppress("LeakingThis")` on a
   `WeakReference(this)` taken inside the view's own `init { }` documents that the leak is deliberately bounded
   (nothing but the weak reference itself escapes construction).

Prefer `TextureView` over `SurfaceView` when the view needs to participate in normal view composition — animated,
transformed, or alpha-blended like any other view (`TextureView`'s whole reason to exist, per its own KDoc in
the reference sample) — and `SurfaceView` when the content is opaque, full-bleed, and composition overhead isn't
wanted.

## ViewBinding

For an ordinary (non-custom-drawing) screen backed by an XML layout, use ViewBinding rather than `findViewById`
or synthetic view accessors:

```kotlin
private var _binding: ScreenOrientationBinding? = null
private val binding get() = requireNotNull(_binding) { "Binding accessed outside view lifecycle" }

override fun onCreateView(...): View {
    _binding = ScreenOrientationBinding.inflate(inflater, container, false)
    return binding.root
}

override fun onDestroyView() {
    super.onDestroyView()
    _binding = null   // Fragment-only: the binding must not outlive the Fragment's view
}
```

- In a `Fragment`, null out the binding in `onDestroyView()` — a `Fragment`'s view is destroyed well before the
  `Fragment` object itself, and a binding held past that point is a real, common leak.
- In an `Activity`, the binding can be a plain non-nullable `val` set once in `onCreate` — the `Activity`'s view
  hierarchy and the `Activity` share the same lifetime.
- `binding.root` is what `setContentView`/the fragment's returned `View` is; never inflate the layout a second
  time via `LayoutInflater` directly once ViewBinding already does it.
