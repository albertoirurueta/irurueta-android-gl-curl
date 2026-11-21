# Class member ordering

Every type's body is ordered **from public to private**, in a fixed sequence of member kinds. This is a hard
convention in this catalog: a reader opening any file knows that the API is at the top and the machinery is at
the bottom, and a reviewer can see at a glance whether a change widened a type's surface.

## The order

Within a class, interface, enum, or record body, declare members in this sequence:

1. **Static constants** (`static final`) — `public`, then `protected`, then package-private, then `private`.
2. **Static (non-constant) fields**, same visibility order. Rare; if you're adding mutable static state, be sure
   the task actually asked for it.
3. **Instance fields** — `public`, then `protected`, then package-private, then `private`. (In practice almost
   all of these are `private final`; see `code-style.md`.)
4. **Static initialiser blocks**, if any, after the fields they populate.
5. **Constructors** — `public`, then `protected`, then package-private, then `private`.
6. **Methods** — `public`, then `protected`, then package-private, then `private`. Static and instance methods
   share this single visibility ordering; a static factory method is just a method in its visibility band.
7. **Nested types** — inner classes, interfaces, enums, and records, last, again ordered `public` → `protected`
   → package-private → `private`.

Two rules cut across the sequence:

- **Overloads stay together.** All methods (or constructors) sharing a name form one contiguous group, with no
  unrelated member between them. Inside the group, order by visibility: `public` overloads first, then
  `protected`, then package-private, then `private`. The group as a whole sits at the position its **most
  visible** member would occupy — so a `public foo(int)` with a `private foo(int, boolean)` helper puts both in
  the public band, not one at each end of the file.
- **A private helper does not follow the method it serves.** It goes in the private band with the other private
  methods. Proximity is tempting, but it defeats the point of the ordering: the public API has to be readable
  without scrolling past implementation details.

Annotations, and the `@Override`/`@Deprecated` markers, don't affect placement. Neither does whether a method is
`final`.

## Skeleton

```java
/**
 * Computes shipping quotes for an order.
 */
public final class ShippingQuoter {

    /** Maximum number of quotes returned by a single call. */
    public static final int MAX_QUOTES = 10;

    /** Currency every quote is expressed in. */
    protected static final Currency QUOTE_CURRENCY = Currency.getInstance("EUR");

    /** Timeout applied by {@link #quote(Order)} when the caller doesn't supply one. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /** Rate table every quote is priced against. */
    private final RateTable rates;

    /** Clock used to decide which rates are currently valid. */
    private final Clock clock;

    /**
     * Creates a quoter using the system clock.
     *
     * @param rates the rate table to price against; must not be {@code null}.
     * @throws IllegalArgumentException if {@code rates} is {@code null}.
     */
    public ShippingQuoter(final RateTable rates) {
        this(rates, Clock.systemUTC());
    }

    /**
     * Creates a quoter with an explicit clock, for testing.
     *
     * @param rates the rate table to price against; must not be {@code null}.
     * @param clock the clock used to resolve rate validity; must not be {@code null}.
     * @throws IllegalArgumentException if any argument is {@code null}.
     */
    ShippingQuoter(final RateTable rates, final Clock clock) {
        if (rates == null || clock == null) {
            throw new IllegalArgumentException("rates and clock are required");
        }
        this.rates = rates;
        this.clock = clock;
    }

    // ---- public methods; overloads of quote() stay together, most visible first ----

    /**
     * Quotes an order using the default timeout.
     *
     * @param order the order to quote; must not be {@code null}.
     * @return the available quotes, never {@code null}, at most {@link #MAX_QUOTES} entries.
     * @throws IllegalArgumentException if {@code order} is {@code null}.
     */
    public List<Quote> quote(final Order order) {
        return quote(order, DEFAULT_TIMEOUT);
    }

    /**
     * Quotes an order, giving up after the supplied timeout.
     *
     * @param order   the order to quote; must not be {@code null}.
     * @param timeout how long to wait for the rate table; must be positive.
     * @return the available quotes, never {@code null}, at most {@link #MAX_QUOTES} entries.
     * @throws IllegalArgumentException if {@code order} is {@code null} or {@code timeout} is not positive.
     */
    public List<Quote> quote(final Order order, final Duration timeout) {
        ...
    }

    /**
     * Hook for subclasses to adjust a computed quote. The default implementation returns it unchanged; an
     * override must return a non-{@code null} quote for the same carrier.
     *
     * @param quote the computed quote; never {@code null}.
     * @return the adjusted quote; never {@code null}.
     */
    protected Quote adjust(final Quote quote) {
        return quote;
    }

    /**
     * Prices a shipment of the given weight with one carrier, using the rates valid now.
     *
     * @param carrier the carrier to price with; never {@code null}.
     * @param weight  the shipment's weight; never {@code null}.
     * @return the price; never {@code null}.
     */
    private Money priceFor(final Carrier carrier, final Weight weight) {
        ...
    }

    /**
     * A single carrier's price for an order.
     *
     * @param carrier the quoting carrier; never {@code null}.
     * @param price   the quoted price; never {@code null}.
     */
    public record Quote(Carrier carrier, Money price) {
    }

    /** Geographic band a destination falls into, which selects the rate row. */
    private enum Zone {
        DOMESTIC, EU, INTERNATIONAL
    }
}
```

Note in the skeleton: the package-private constructor comes after the public one; both `quote` overloads sit
together in the public band even though one delegates to the other; `priceFor` is private and therefore below
`adjust`, not next to its caller; and both nested types are last, public before private.

## Interfaces, enums, and records

- **Interface**: constants first, then abstract methods, then `default` methods, then `static` methods, then
  nested types. Members are implicitly public, so the visibility ordering collapses; keep `private` methods
  (Java 9+) last.
- **Enum**: constants first (they must be, syntactically), then fields, then the constructor, then methods,
  then nested types — the same sequence as a class from step 3 onward.
- **Record**: the components are in the header, so the body starts at static constants, then the compact
  constructor and any additional constructors, then methods, then nested types. Instance fields other than the
  components aren't allowed.

## Applying it to an existing file

- **Insert new members in their correct band.** That's the whole obligation for a task that adds something.
- **Don't reorder a file that already violates the convention.** A wholesale reshuffle produces a diff no
  reviewer can read, and it buries the actual change. If the file's ordering is badly wrong, say so in the report
  and let it become its own task.
- **Moving a member is a change to review.** If the task itself requires a member to move (a field promoted to a
  constant, a method's visibility narrowed), move it into the right band and mention it in the report so the
  diff's shape is expected.
- **Keep an overload group intact.** Adding an overload means inserting it into the existing group at the right
  visibility position — never appending it at the end of the class.

Checkstyle encodes most of this if the project runs it: `DeclarationOrder` covers the static-then-instance,
fields-then-constructors-then-methods sequence and the visibility ordering of variables, and
`OverloadMethodsDeclarationOrder` covers the contiguous overload rule. `iru-java-code-quality` is what actually
runs those checks — one gate later, in the task group — so getting the order right here is what keeps that gate
green.
