# Ports and adapters in a Nest repository (optional)

Read this **only** when Step 1 found that the repository clearly follows ports and adapters. Most Nest codebases
use the feature-module layout in `code-style.md`; don't impose this one on them.

## Recognizing it

At least two of these, consistently, across features:

- Per-feature (or top-level) `domain/`, `application/` (or `use-cases/`), and `infrastructure/` (or `adapters/`)
  folders — or libs named that way in a monorepo (`libs/<context>-domain`, …).
- Repository/gateway **interfaces or abstract classes** in `domain` (`BooksRepository`), implemented by classes in
  `infrastructure` (`TypeOrmBooksRepository`), bound with `{ provide: BooksRepository | BOOKS_REPOSITORY, useClass:
  … }` in the Nest module.
- Domain classes with no `@nestjs/*`, ORM, or `class-validator` imports; separate persistence entities/schemas and
  mappers.
- An architecture lint (`eslint-plugin-boundaries`, `dependency-cruiser`, Nx module-boundary tags) enforcing it.

If only one feature looks like this, follow that feature's shape when the task is inside it, and the plain layout
elsewhere.

## Rules when it applies

- **Dependencies point inward**: `domain` imports nothing from the framework, the ORM, or the other layers;
  `application` imports `domain` only; `infrastructure` and the HTTP/messaging adapters (controllers, consumers,
  gateways) import inward and never each other.
- **Keep `domain` free of Nest decorators** — no `@Injectable()`, `@Inject()`, `@Entity()`, `@Prop()`, `@IsString()`
  there. Domain objects are plain classes and functions, constructed with `new` and tested without
  `Test.createTestingModule`.
- **Ports are contracts in `domain`** (or `application` for use-case-facing ports): an abstract class (its own DI
  token) or an interface plus an exported `Symbol` token. Adapters implement them in `infrastructure` and carry the
  Nest/ORM decorators.
- **Use cases** (`application`) are the Nest providers that orchestrate: `@Injectable()` there is acceptable when
  the project already does it; otherwise wire them in the module with `useFactory` so even `application` stays
  framework-free. Follow whichever the repository chose.
- **Wiring lives in the Nest module** of the feature (or a dedicated `*.providers.ts`): binding ports to adapters,
  `forFeature` registrations, exports. Controllers call use cases, never repositories.
- **Validation stays at the edge**: request DTOs (class-validator or schemas) live with the controller adapter and
  are mapped to domain inputs; domain invariants are enforced in domain constructors/factories and raise domain
  errors, which an exception filter (or the controller) maps to HTTP statuses.
- **Persistence models are not domain objects**: a TypeORM entity / Mongoose schema / Prisma row is mapped to and
  from the domain type in the adapter.
- Order of work for a task spanning layers: domain type + port → adapter → use case → controller → module wiring.

## Testing in this layout

Domain and use cases: plain unit tests with in-memory port doubles or `vi.fn()` objects — no Nest container needed.
Adapters: unit-test their mapping with the ORM double; their real queries belong to the e2e/integration suite
(`testing.md`). Controllers: `Test.createTestingModule` with the use case overridden.
