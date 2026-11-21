# Angular

Conventions specific to an Angular (standalone, `iru-setup-angular-web`) project. Everything in `code-style.md`,
`tsdoc.md`, and `testing.md` still applies; this file adds what's specific to components, signals, DI, and
`HttpClient`.

## Standalone components — no `NgModule` for new code

- **Every new component, directive, and pipe is `standalone: true`** (the default in current Angular; don't add
  `standalone: false` or write a new `@NgModule` unless the task explicitly touches a still-module-based legacy
  area). Import what the template needs directly in the component's `imports` array.

  ```ts
  @Component({
    selector: "app-order-summary",
    standalone: true,
    imports: [CommonModule, RouterLink],
    templateUrl: "./order-summary.component.html",
    changeDetection: ChangeDetectionStrategy.OnPush,
  })
  export class OrderSummaryComponent {
    readonly order = input.required<Order>();
    readonly checkout = output<string>();
  }
  ```

- One component/directive/pipe per file, file named `<name>.component.ts` / `.directive.ts` / `.pipe.ts`,
  matching Angular's own generator convention (Angular 20+ generators drop the suffix — `order-summary.ts` with
  class `OrderSummary`; follow whichever the project uses).
- **Presentational vs. screen** (UI contract §3–§4): presentational components take their UI state through
  `input()`s and emit intent-named `output()`s (`open`, `retry`) with typed payloads; routed screens inject services
  and pass signals down. Compose with `<ng-content>` slots, never by extending another component. Long or
  data-driven lists use the CDK virtual scroll viewport (`*cdkVirtualFor` + `trackBy`); `@for (…; track item.id)`
  only for short, bounded lists. See `ui-implementation.md` (Angular section) for the list, stories, the component
  gallery and the screenshot/accessibility tests.

## Signals over `@Input`/`@Output` decorators and manual subscriptions

- **`input()`/`input.required()`** for component inputs, **`output()`** for component outputs, in new components
  — not the `@Input()`/`@Output()` decorators, which this catalog's scaffold treats as legacy syntax kept only
  where an existing file already uses it.
- **`signal()`** for local component state that drives the template; **`computed()`** for anything derived from
  one or more signals — never duplicate a computed value into its own `signal()`.
- **`effect()`** only for a genuine side effect that must run in response to signal changes (syncing to
  `localStorage`, imperative DOM work a template binding can't express) — not as a substitute for `computed()`
  when the goal is just to derive a value.
- Read a signal by calling it (`this.order()`), never destructure/unwrap it into a plain variable that goes
  stale; pass the signal itself (or a `computed`) down to a child input.

## `inject()` over constructor injection

- **`inject()` at property-initializer position** is this catalog's default for a component/directive/service's
  own dependencies — it composes more easily with an intermediate base class or a function-based `provide*`
  registration than a constructor parameter list does:

  ```ts
  export class OrderService {
    private readonly http = inject(HttpClient);
    private readonly config = inject(APP_CONFIG);
  }
  ```

- A file that already uses constructor-parameter injection consistently keeps doing so — `inject()` is the
  default for *new* files, not a reason to convert an untouched one.

## Change detection: `OnPush` by default

- Every new component sets `changeDetection: ChangeDetectionStrategy.OnPush`. Combined with signals (which
  notify `OnPush` components correctly on their own), this is what keeps change detection fast; a component
  that still needs `Default` because it reads a mutable object without a signal is a sign the state should be a
  signal instead, not a reason to drop `OnPush`.
- Never call `ChangeDetectorRef.detectChanges()`/`markForCheck()` as a workaround for a signal/input that isn't
  updating the view — fix the reactive source instead.

## `HttpClient` via `provideHttpClient`

- **`provideHttpClient(...)`** in the app/route config (never `HttpClientModule`) — with `withFetch()` when the
  project targets it (this catalog's scaffold default) for the fetch-backed implementation, and
  `withInterceptors([...])` for functional interceptors (not the older class-based `HTTP_INTERCEPTORS` token,
  unless the file being edited already uses that).
- A functional interceptor is a plain function matching `HttpInterceptorFn`, registered in `withInterceptors`,
  not a class implementing `HttpInterceptor` — unless matching an existing class-based one in the same project.
- Type every HTTP response (`this.http.get<Order[]>(url)`) — never leave it inferred as `any`/`Object`.

## Testing: `TestBed` + `HttpTestingController`

- **`TestBed.configureTestingModule({ imports: [OrderSummaryComponent], providers: [...] })`** for a standalone
  component — pass the component itself in `imports`, not `declarations` (standalone components aren't declared).
- **Signal inputs in a test**: set with the component's `ComponentRef.setInput("order", testOrder)` — a signal
  `input()` cannot be assigned by writing directly to the property from outside the component.
- **`HttpTestingController`** for any test touching a service built on `HttpClient`: provide `provideHttpClient()`
  + `provideHttpClientTesting()`, inject `HttpTestingController`, then `httpMock.expectOne(url)` /
  `.flush(response)` and finish with `httpMock.verify()` in an `afterEach` to catch unexpected/unflushed
  requests.

  ```ts
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
    service = TestBed.inject(OrderService);
  });

  afterEach(() => httpMock.verify());

  it("fetches an order by id", () => {
    let result: Order | undefined;
    service.getOrder("o1").subscribe((order) => (result = order));

    httpMock.expectOne("/api/orders/o1").flush(testOrder);

    expect(result).toEqual(testOrder);
  });
  ```

- The project's `ng test` runs on the Vitest-backed `@angular/build:unit-test` builder (this catalog's scaffold
  default) — write tests with the same Vitest `describe`/`it`/`expect` surface as `testing.md` describes; don't
  assume Jasmine/Karma matchers unless the project's own tests already use them.
- Prefer testing a component through its template/DOM (`fixture.nativeElement.querySelector(...)`,
  `fixture.debugElement.query(By.css(...))`) over reaching into its class internals directly.
- `TestBed` tests run in a DOM emulator: they prove behaviour, never produce screenshots. The visual states,
  right-to-left × light/dark captures and axe checks are Playwright component tests against the gallery
  (`ui-implementation.md`).

## RxJS, where the project still uses it

- Some APIs (`HttpClient`, the router, forms `valueChanges`) are Observable-based regardless of signals; use the
  `async` pipe in templates over a manual `.subscribe()` wherever possible, and always unsubscribe a manual
  subscription (`takeUntilDestroyed()` from `@angular/core/rxjs-interop`, called inside an injection context)
  rather than tracking a `Subscription` by hand.
- Prefer converting an Observable to a signal at the boundary (`toSignal(...)`) when the rest of the component
  is signal-based, so the template doesn't mix `| async` and direct signal reads for related data.
