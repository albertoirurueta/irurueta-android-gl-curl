# Code style

This catalog's stated preferences. Where a preference here disagrees with what most Spring Boot write-ups
assume — notably on MapStruct — this file wins, and says why.

The non-negotiable rules — the ones that hold even when the surrounding code does otherwise — live in
`mandatory-rules.md`, which is always read alongside this file. Everything here is consistent with them.

Beyond those: **match the surrounding code.** If the module you are editing already does something a specific
way, follow it and note the divergence in the report rather than converting the file to this document's
preference as a side effect of an unrelated task.

## Records for immutable types

Use a `record` for anything immutable: value objects, DTOs, commands, queries, events, and configuration
property holders.

```java
/**
 * A monetary amount in a specific currency.
 *
 * @param amount   the amount; never {@code null}, scale is preserved as given.
 * @param currency the currency; never {@code null}.
 */
public record Money(BigDecimal amount, Currency currency) {

    /**
     * Creates a monetary amount.
     *
     * @throws IllegalArgumentException if {@code amount} or {@code currency} is {@code null}.
     */
    public Money {
        if (amount == null || currency == null) {
            throw new IllegalArgumentException("amount and currency are required");
        }
    }
}
```

Notes that matter in practice:

- **Validate in the compact constructor.** That is what makes "a value object is always valid" true, which in
  turn means callers never have to null-check one. See `ddd-tactical-patterns.md`.
- **A record is shallowly immutable.** A `record Order(List<Item> items)` hands out the caller's mutable list.
  Copy defensively in the compact constructor (`List.copyOf(items)`) for any collection or mutable field —
  mandatory, since SpotBugs' `EI_EXPOSE_REP` fails the build otherwise.
- **Don't use a record for a JPA `@Entity`** — JPA needs a no-arg constructor and non-final fields. Persistence
  entities in `infrastructure/database/*` are ordinary classes; the *domain* type they map to is the record.
  Document store and R2DBC mappings vary — check what the module already does.

## Lombok where it earns its place

Use Lombok wherever it removes real boilerplate — and for constructors and loggers, always
(`mandatory-rules.md`):

- `@RequiredArgsConstructor` on Spring components for constructor injection — the standard shape for an adapter
  or use case with `private final` dependencies. Never a hand-written injection constructor.
- `@Builder` where a type has enough optional fields that positional construction is unreadable.
- `@Getter`/`@Setter`, `@EqualsAndHashCode`, `@ToString` on mutable classes such as persistence entities.
- `@Slf4j` for a logger — never a hand-written `LoggerFactory.getLogger(...)` field.

Don't use Lombok where it adds nothing or actively hurts:

- **Not on records** — they already generate accessors, `equals`, `hashCode`, and `toString`.
- **Not `@Data` on a persistence entity.** Its generated `equals`/`hashCode` covers every field, which breaks
  for entities with generated ids and lazy associations, and its `@ToString` will happily trigger lazy loading.
  Prefer explicit `@Getter`/`@Setter` plus an `equals`/`hashCode` on the identifier only.
- **Not `@AllArgsConstructor` on a Spring bean** — it silently changes the injected constructor's signature when
  a field is added or reordered.

If Lombok and MapStruct are both in play, `lombok-mapstruct-binding` must be on the annotation processor path,
in the order lombok → binding → mapstruct-processor. Without it MapStruct runs before Lombok has generated the
accessors and emits mappers that silently map nothing.

## MapStruct only for simple mappers — otherwise map by hand

This is the preference that runs against most published advice, so the reasoning matters.

**Use MapStruct when** the mapping is field-for-field with matching names and types, or needs only a trivial
rename. That is the case it was built for, and hand-writing it is pure boilerplate. The reactor sets
`unmappedTargetPolicy=ERROR`, so a forgotten field fails compilation rather than silently producing a null.

**Write the mapper by hand when** the mapping involves any of:

- conditional logic, or a branch on the source's state;
- building a value object through a factory or validating constructor (a generated mapper reaching for a
  constructor it doesn't understand tends to produce either a compile error or a bypassed invariant);
- flattening or restructuring — several sources into one target, or one source into a nested graph;
- a lookup, a default, or a derived field that isn't a pure function of one source field;
- more than a couple of `@Mapping(expression = "java(...)")` entries. Java-in-a-string annotation is unreadable,
  untestable in isolation, and invisible to refactoring tools. Once you are reaching for the second one, the
  hand-written mapper is shorter *and* clearer.

A hand-written mapper is a plain `@Component` (or a static factory) with a method per direction and full
Javadoc. It is ordinary code: it can be read, debugged, and unit-tested directly. That is the point.

## Type inference

Use `var` for locals wherever the type can be inferred — including generic static factories, through an
explicit type witness (`mandatory-rules.md`):

```java
final var order = orderRepository.findById(orderId);
final var items = new ArrayList<OrderItem>();
final var empty = List.<String>of();
```

Keep the explicit type when `var` would genuinely hide something the reader needs — a non-obvious factory
return, a numeric type where width matters, or a `var` that would resolve to an unintended supertype. Never use
`var` for a field, a parameter, or a return type; it isn't legal there anyway.

New and rewritten code uses `var` even if the module doesn't elsewhere; don't retrofit it into lines the task
doesn't touch.

## `final` wherever possible

- **Fields**: `private final` for every injected dependency and every field set once.
- **Locals**: `final var` / `final Type` by default. It documents that the reference doesn't change and makes an
  accidental reassignment a compile error.
- **Parameters**: `final` on every parameter you write — mandatory, and enforced by Checkstyle's
  `FinalParameters`. Don't retrofit it across untouched methods for an unrelated task.
- **Classes**: `final` for value objects that aren't records, mappers, and utility classes. Not on Spring
  `@Configuration` classes or anything needing a proxy (CGLIB cannot subclass a final class, and the failure
  message is unhelpful).

## Class member ordering: public to private

Every type's body is ordered from public to private, in a fixed sequence of member kinds:

1. static constants, 2. static fields, 3. instance fields, 4. static initialisers, 5. constructors,
6. methods, 7. nested classes/interfaces/enums/records.

Within each band, order by visibility: `public`, then `protected`, then package-private, then `private`. Two
rules cut across the sequence: **overloads stay contiguous**, ordered by visibility inside the group and placed
where their most visible member would sit; and **a private helper goes in the private band**, not next to the
method it serves — the public API has to be readable without scrolling past implementation details.

In practice, for the types in this architecture: a use case or adapter is `private final` dependency fields, then
its (usually Lombok-generated) constructor, then the port method(s) it implements, then its private helpers. An
interface's members are implicitly public, so constants come first, then abstract methods, then `default`, then
`static`, then any `private` (Java 9+) method. A record's components live in its header, so its body starts at
static constants, then the compact constructor.

Insert new members in their correct band, and **don't reorder a file that already violates the order** — a
wholesale reshuffle buries the actual change in an unreviewable diff. This is the same agreement documented in
full, with an annotated skeleton, in `iru-java-code-one-task/reference/class-member-ordering.md`; Checkstyle's
`DeclarationOrder` and `OverloadMethodsDeclarationOrder` are what enforce it, one gate later, in the group skill.

## Javadoc on everything

**All code must be fully documented.** On every type, method, constructor, and field — private ones included,
and in test sources as much as in production sources (`mandatory-rules.md`):

- what it does, and any contract a caller must satisfy;
- `@param` for every parameter — including every record component, on the record's own Javadoc;
- `@return`, unless `void`;
- `@throws` for every exception the caller can reasonably be expected to handle, with the condition that
  triggers it. This is not optional decoration: the `@throws` list is what the tests are written against.

For private members, make the Javadoc say *why* — why the member exists or why an invariant holds is worth far
more than a restatement of what the next line does. In test sources, a test method's Javadoc states the scenario
and the expected outcome in one sentence.

For a port interface, the Javadoc is the contract every adapter must honour — state what an implementation
must guarantee (ordering, idempotency, whether it may return `null`, what it does when nothing is found). These
interfaces are the architecture's real documentation.

## Other conventions

- **`IllegalArgumentException` for invalid arguments**, with a message naming the offending parameter. Domain
  rule violations throw a domain exception instead (see `ddd-tactical-patterns.md`).
- **Constructor injection only.** No `@Autowired` on fields — it defeats `final` and makes the class
  untestable without a container.
- **No new runtime dependency** unless the task explicitly calls for it. If one seems unavoidable, that is a
  blocker to report, not a decision to make silently — and remember the version would have to go in the root
  pom.
- **Don't catch and swallow.** Either handle it meaningfully or let it propagate; a bare
  `catch (Exception e) { log.error(...); }` in an adapter turns a failure into silent data loss.
- **No `System.out`** — use the `@Slf4j` logger, and don't log a secret, a token, or a full request body
  containing personal data.
- **Modern Java idioms, LF line endings, pinned locales** — `getFirst()`/`getLast()`, method references,
  `switch` expressions, stream predicates, no redundant `this`, `StringUtils.hasText`/`CollectionUtils.isEmpty`,
  `"\n"` rather than `System.lineSeparator()`, and `Locale.ROOT` rather than the default locale — are mandatory,
  and listed with examples in `mandatory-rules.md`.
