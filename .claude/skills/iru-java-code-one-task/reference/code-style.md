# Code style

This catalog's general Java code agreements. They apply to any plain Java/Maven project, and they hold in a
Spring Boot service too.

The non-negotiable rules — the ones that hold even when the surrounding code does otherwise — live in
`mandatory-rules.md`, which is always read alongside this file. Everything here is consistent with them.

Beyond those: **match the surrounding code.** These are the defaults for new code. If the file you are editing
consistently does something else, follow it and note the divergence in the report — don't restyle a file as a
side effect of an unrelated task.

## Type inference: use `var` as much as possible

Declare local variables with `var` wherever the type is inferable. It removes the duplication in
`Foo foo = new Foo()`, it keeps declarations aligned on the *name* rather than the type, and it makes a later
type change a one-line edit instead of a sweep.

```java
final var order = orderRepository.findById(orderId);
final var items = new ArrayList<OrderItem>();
final var byId = new HashMap<String, Order>();
for (final var item : items) {
    ...
}
try (final var reader = Files.newBufferedReader(path)) {
    ...
}
```

`var` is legal only for locals, `for`/enhanced-`for` variables, try-with-resources resources, and lambda
parameters (all-or-nothing, and only with an explicit parameter list). It is **not** legal for a field, a method
parameter, a return type, or a catch parameter — write the type there.

Keep the explicit type in the few cases where `var` genuinely costs the reader something:

- **The right-hand side doesn't name the type.** `var result = compute();` tells the reader nothing;
  `final ProjectionResult result = compute();` does. A `new` expression, a cast, or a literal is self-naming —
  a bare method call often isn't.
- **The numeric width or precision matters.** `var count = 0;` is `int`; if the arithmetic needs `long`, write
  `final long count = 0;` rather than relying on a later reader noticing.
- **You need the supertype or the interface.** `var list = new ArrayList<String>();` infers `ArrayList<String>`,
  so a later reassignment to `List.of(...)` breaks. If the variable is meant to be a `List`, declare it as one.
- **The inferred type would be something you can't write down** — an anonymous class, an intersection type, or a
  capture. `var` compiles, but the variable is then hard to reason about and impossible to extract.
- **A diamond with no other clue.** `var x = new HashMap<>();` infers `HashMap<Object, Object>` and silently
  loses all type safety. Give the diamond its arguments (`new HashMap<String, Order>()`) — and for a generic
  static factory, an explicit type witness (`final var empty = List.<String>of();`) rather than a declared type.

Never write `var` and then immediately cast it, and never use `var` for a variable initialised to `null`.

## `final` wherever possible

`final` is the default, not the exception. It documents that a thing doesn't change, and it turns a class of
mistakes — accidental reassignment, an unintended override, a partially initialised object escaping — into
compile errors instead of misbehaviour at runtime.

- **Fields**: `private final` for anything set once, in the constructor or at declaration. A non-final field
  should be non-final because it genuinely has to change.
- **Locals**: `final var` / `final Type` by default, including loop variables and try-with-resources resources.
- **Parameters**: `final` on every parameter — mandatory, and enforced by Checkstyle's `FinalParameters`.
  Reassigning a parameter is one of the easiest ways to write a confusing method, and `final` forbids it.
- **Classes**: `final` unless the class is *designed* to be extended. If it has no protected member, no
  documented extension point, and nothing in the codebase subclasses it, it is not designed for extension —
  make it final. Utility classes (all-static) are final *and* have a private constructor.
- **Methods**: `final` on any public/protected method of a non-final class that is not meant to be overridden.
  A method that a subclass may override is part of the class's contract and needs Javadoc saying what an
  override must preserve (see `javadoc.md`). Don't mark private or static methods final — it's redundant.
- **Constants**: `private static final` (widen only if callers outside the class genuinely need it), named in
  `SCREAMING_SNAKE_CASE` — and every magic number becomes one (`mandatory-rules.md`).

Where `final` is wrong, and why:

- **A class or method a framework must proxy.** CGLIB-style subclass proxies cannot extend a final class or
  override a final method, and the failure is a confusing runtime error rather than a compile error. If the type
  is a bean the framework proxies (transactional, cached, aspect-advised), leave it non-final.
- **A type mocked by an old Mockito.** Mockito 5 and later use the inline mock maker by default and can mock
  final classes and methods; Mockito 4 and earlier cannot without the `mockito-inline` artifact. Check which is
  on the test classpath before making a collaborator final — a final class that the tests need to mock is a
  compile-time-clean change that breaks the build one gate later.
- **Retrofitting it across an untouched file.** Adding `final` to every parameter of a class you're editing for
  one unrelated reason is diff noise. New and rewritten members get it (parameters always); the rest of the file
  keeps whatever it has.

## Immutability by default

- Prefer immutable types: all fields `private final`, no setters, state supplied through the constructor.
- Use a `record` for anything that is purely a carrier of immutable values — value objects, DTOs, results,
  events, coordinate pairs. Validate in the compact constructor so a constructed instance is always valid, and
  throw `IllegalArgumentException` naming the offending component.
- **A record is only shallowly immutable.** `record Order(List<Item> items)` keeps the caller's mutable list.
  Copy defensively in the compact constructor (`List.copyOf(items)`) for any collection or otherwise mutable
  component, and return a copy or an unmodifiable view from any accessor that hands out mutable state.
- Records are implicitly final and cannot extend a class — they aren't a fit where inheritance or a mutable
  identity (a JPA entity, for instance) is required. Those stay ordinary classes.
- Prefer `List.of`/`Map.of`/`Set.of` and `Collectors.toUnmodifiableList()` for fixed collections.

## Visibility: the narrowest that works

Start at `private` and widen only when a caller outside the class needs it. Package-private is a real and
underused option — it makes a member visible to its tests in the same package without publishing it as API.
Never expose a mutable field; never widen a member's visibility just to test it directly (test it through the
public behaviour that uses it — see `testing.md`).

## Naming

- Classes and interfaces: `UpperCamelCase` nouns. No `I` prefix on interfaces, no `Impl` suffix unless the
  codebase already uses one — name the implementation after what makes it specific (`FileOrderStore`, not
  `OrderStoreImpl`).
- Methods: `lowerCamelCase` verbs; `getX`/`setX` for accessors on ordinary classes, `isX`/`hasX` for booleans.
  A record's accessors are the component names, with no `get` prefix.
- Variables and parameters: `lowerCamelCase`, meaningful, no Hungarian prefixes, no single letters except a
  loop index or a lambda parameter whose scope is one line.
- Type parameters: single capital letter (`T`, `K`, `V`, `R`) or a short `UpperCamelCase` word when several
  parameters would otherwise be indistinguishable.
- Tests: `<ClassName>Test` for unit tests, `<ClassName>IT` for integration tests, and test methods named
  `methodBeingTested_whenCondition_expectedResult` (`mandatory-rules.md`).

## Exceptions and error handling

- **`IllegalArgumentException` for an invalid argument**, with a message naming the offending parameter and what
  was wrong with it. `NullPointerException` (via `Objects.requireNonNull`) is the idiomatic choice for a null
  that is simply not allowed; be consistent with the surrounding code, and document whichever you throw with
  `@throws`.
- **`IllegalStateException`** when the object is in the wrong state for the call, not the argument.
- **Fail fast, at the boundary.** Validate in the constructor or at method entry so an invalid value never
  propagates far enough to produce a confusing failure somewhere else.
- **Don't catch and swallow.** A bare `catch (Exception e) { }` — or one that only logs — turns a failure into
  silent wrong behaviour. Either handle it meaningfully, translate it into a more appropriate exception
  (preserving the original as the cause), or let it propagate.
- **Never catch `Throwable` or `Error`**, and don't catch an exception you only intend to rethrow unchanged.
- **No `System.out`/`System.err`** — use the logger the project already uses, and never log a secret, token, or
  credential.
- Prefer a specific custom exception over reusing an unrelated built-in when callers need to distinguish the
  failure; give it a cause-taking constructor.

## Collections, `Optional`, and null

- Return an empty collection, never `null`, from a method whose result is a collection.
- `Optional` is for a *return type* where absence is a normal outcome. Don't use it for a field, a parameter, or
  a collection element, and never return `null` from a method declared to return `Optional`.
- Program to the interface (`List`, `Map`, `Set`) in fields, parameters, and return types; pick the concrete
  implementation only where you construct it.
- Override `equals` and `hashCode` together, or neither, and keep them consistent with `compareTo` when the type
  is `Comparable`.

## Other conventions

- **Constructor injection over field mutation.** A dependency arrives through the constructor and is stored in
  a `private final` field; that's what makes the class testable without a container.
- **No new runtime dependency** unless the task explicitly calls for it. If one seems unavoidable, that's a
  blocker to report, not a decision to make silently.
- **Don't leave dead code, commented-out code, or `TODO`s** behind for work the task itself covers.
- **Keep methods short and single-purpose.** If a method needs a comment to explain its sections, those sections
  are the methods you should have extracted — but extract only within the task's scope.
- **`@Override` on every override**, including interface implementations.
- **Use the standard library** before writing a helper: `Objects`, `Optional`, `Comparator`, `Streams`,
  `String.join`, `Files`, `Math`. Don't add a utility class that duplicates one of them.
- **Text blocks** (`"""`) for multi-line string literals; `String.format`/`formatted` or a parameterised log
  statement instead of long `+` chains. Line endings in produced output are always `"\n"` — never `%n` or
  `System.lineSeparator()` (`mandatory-rules.md`).
- **Modern Java idioms** — `getFirst()`/`getLast()`, method references, `switch` expressions, stream predicates
  for simple scans, no redundant `this`, utility predicates for null-and-empty checks — are mandatory, and listed
  with examples in `mandatory-rules.md`.
