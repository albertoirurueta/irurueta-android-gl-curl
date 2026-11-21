# REST: Quarkus REST resources and REST Client

Read this when the task adds or changes a `@Path` resource, an exception mapper, a filter, or a typed REST
client. It assumes **Quarkus REST** (`quarkus-rest`, formerly RESTEasy Reactive). If the module still uses
RESTEasy Classic (`quarkus-resteasy*` artifacts), keep using what it has and note it in the report — migration is
its own task.

## Resources

- One resource class per resource/aggregate, `@Path` at class level, HTTP-method annotations on methods. Keep
  resources **thin**: parse/validate the request, call one service (or use case), map the result to a response.
  No persistence calls, no business branching, no `@Transactional` on the resource unless the module's existing
  resources already do it that way.
- Request/response bodies are dedicated DTOs (Java `record`, Kotlin `data class`), never the `@Entity` itself,
  unless the module already exposes entities and the task doesn't ask to change that.
- Return the domain-shaped type and let the status default (200, 204 for `void`), or return `RestResponse<T>`
  (typed) when the status/headers vary. Use `jakarta.ws.rs.core.Response` only if the module already does.
  `201 Created` with a `Location` header for creates.
- Quarkus REST shorthand (`@RestPath`, `@RestQuery`, `@RestHeader`, `@RestForm`) or classic `@PathParam`/
  `@QueryParam` — follow the module.
- Validation: `quarkus-hibernate-validator` + `@Valid` on the body and constraint annotations on parameters gives a
  `400` before the method runs. Don't hand-roll checks the annotations already express.
- OpenAPI: if the module uses `@Operation`/`@APIResponse`/`@Tag` (smallrye-openapi), annotate new endpoints the
  same way; if the build generates resources from an OpenAPI spec, implement the generated interface rather than
  writing a free-standing resource.

## Threading: worker vs event loop — decided by the signature

Quarkus REST dispatches each request on a Vert.x event loop and decides per method where it runs:

| Method shape | Runs on | Allowed to block? |
|---|---|---|
| Plain return type (`T`, `List<T>`, `RestResponse<T>`, `void`) | worker thread | yes |
| `Uni<T>` / `Multi<T>` / `CompletionStage<T>` | event loop | **never** |
| Kotlin `suspend fun` | event loop (coroutine dispatcher) | **never** (wrap blocking work in `withContext(Dispatchers.IO)` or make the method non-suspend) |
| `@Blocking` (`io.smallrye.common.annotation.Blocking`) | worker thread, overriding the return type | yes |
| `@NonBlocking` | event loop, overriding the return type | **never** |
| `@RunOnVirtualThread` | a new virtual thread | yes, but no `synchronized` around blocking I/O (pins the carrier), no pooling of expensive objects in `ThreadLocal`s |

Rules that follow:

- A method that calls JDBC, blocking Hibernate ORM/Panache, a blocking REST client, or the filesystem must have
  a plain return type or `@Blocking` (or `@RunOnVirtualThread` where the module already uses virtual threads).
  Never return `Uni` from a method that blocks.
- Don't add `@NonBlocking` unless every call in the method is verifiably non-blocking.
- Use `@RunOnVirtualThread` only if the module already does, or the task asks for it; it needs Java 21+.
- A blocking call on the event loop logs `BlockedThreadChecker` warnings and stalls other requests — it is a
  defect, not a performance note.

## Exception mapping

- Map domain exceptions to HTTP once, centrally: `@ServerExceptionMapper` methods (Quarkus REST, preferred for new
  code) or an `ExceptionMapper<T>` annotated `@Provider` — follow what the module has. One mapper per exception
  family; the most specific mapper wins, so a mapper on a base exception is the fallback.
- Don't catch-and-convert inside each resource method; throw the domain exception and let the mapper translate it.
- Error bodies keep the module's existing shape (problem+json, a project error record, …). Never leak stack
  traces or persistence exception messages to clients.
- `WebApplicationException` subclasses (`NotFoundException`, `BadRequestException`) are fine in the resource
  layer, never in domain/service code.

## Filters and interceptors

`@ServerRequestFilter`/`@ServerResponseFilter` methods (or `ContainerRequestFilter` `@Provider`s) for
cross-cutting HTTP concerns. Keep them small; anything with business meaning belongs in a service.

## REST Client (outgoing calls)

- Declare a typed interface with `@RegisterRestClient(configKey = "<name>")` and HTTP annotations; inject it with
  `@RestClient`. Configure the base URL as `quarkus.rest-client.<name>.url` — never hardcode a host.
- Translate the client's errors into domain exceptions at the boundary (a `ResponseExceptionMapper` registered with
  `@RegisterProvider`, or a try/catch in the adapter that owns the client). A `WebApplicationException` from the
  remote service must not escape into domain code.
- Set timeouts (`quarkus.rest-client.<name>.connect-timeout` / `read-timeout`) when adding a new client;
  resilience (`@Retry`, `@Timeout`, `@CircuitBreaker` from `quarkus-smallrye-fault-tolerance`) only if the module
  uses it or the task asks.
- A blocking client interface is called from worker-thread code; a `Uni`-returning client from reactive code. Don't
  mix within one call chain.
- In a hexagonal build, the REST Client interface and its adapter live in the client adapter module and implement
  a domain port (see `hexagonal-architecture.md`).

## Testing implication

Resource HTTP behaviour (routing, status, serialization, validation) is verified by a `@QuarkusTest` with
RestAssured — written here, run by the group skill (it boots the app and may start Dev Services). What this skill
runs: plain unit tests of the mapper/DTO conversion and the exception-mapper method, and `@QuarkusComponentTest`
for a service behind the resource. A REST Client is unit-tested by mocking the client interface; its real HTTP
behaviour is a `@QuarkusTest` with a WireMock `@QuarkusTestResource`. See `testing.md`.
