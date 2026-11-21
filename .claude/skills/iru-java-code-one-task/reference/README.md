# Java development reference

General Java code agreements for implementing a task in a plain Java/Maven project — the conventions this
catalog expects, independent of any framework. These files exist so `iru-java-code-one-task` can load **only**
what the task in front of it actually needs, instead of carrying every rule in context on every task.

For a Spring Boot service, the equivalent (and additional, architecture-specific) guidance lives in
`iru-java-springboot-code-one-task/reference/` instead — hexagonal module boundaries, DDD tactical patterns,
SOLID, and the distributed patterns. Everything in *this* directory still holds there; nothing here contradicts
it.

## Read this much, and no more

| Read | When |
|---|---|
| `mandatory-rules.md` | **Always.** The non-negotiable rules for production *and* test code: JUnit not AssertJ, Mockito mocks/spies not fakes, `method_whenCondition_expectedResult` test names, one `*Test`/`*IT` class per public class, `var` with type witnesses, records with defensive copies, `final` parameters, Javadoc on every member, named constants, modern collection accessors, method references, `switch` expressions, no redundant `this`, utility predicates, LF line endings. |
| `code-style.md` | **Always.** Type inference (`var`), `final` wherever possible, immutability, visibility, naming, exceptions. |
| `class-member-ordering.md` | The task adds a new type, or adds/moves a constant, field, constructor, method, or nested type in an existing one. Skip it only for a change that stays inside an existing method body. |
| `javadoc.md` | The task adds or changes a type, or any public/protected member. |
| `testing.md` | The task writes or updates tests — which is nearly every task, since writing the tests is part of implementing it. |

A task that only changes logic inside one existing method needs `mandatory-rules.md`, `code-style.md`, and
`testing.md`, and nothing else. Don't read the rest speculatively.

## The three principles that override everything else

1. **Match the surrounding code — except for `mandatory-rules.md`.** Every other rule here is the default for
   *new* code. If the file or package you're editing consistently does something else, follow it and note the
   divergence in the report rather than converting the file to this document's preference as a side effect of an
   unrelated task. The rules in `mandatory-rules.md` are the exception: the code you write follows them even
   where the surrounding code doesn't — but they still don't license restyling untouched code. Reordering or
   restyling untouched code turns a two-line change into an unreviewable diff.
2. **A task implements what the task says.** These references tell you *how* to write what was asked for. They
   never license adding an abstraction, an interface, a builder, or a "while I'm here" refactor the plan didn't
   ask for.
3. **This skill doesn't validate.** It never runs the test suite, coverage, code-quality checks, license
   headers, or a Javadoc audit — the calling `iru-java-code-one-task-group` does all of that once for the whole
   task group. Write code that will pass those gates; don't run them here.

## Sources

Compiled from the following, cross-checked against each other, with this catalog's own stated preferences taking
precedence where they are stricter (notably on `final`, on `var`, and on the public-to-private declaration
order, which most published style guides leave unspecified).

- Local variable type inference style guidelines: <https://openjdk.org/projects/amber/guides/lvti-style-guide>
- Effective Java conventions (minimize mutability, minimize accessibility, favour composition, fail fast) as
  summarised in <https://www.oracle.com/java/technologies/javase/codeconventions-contents.html>
- Google Java Style Guide (member ordering, overload grouping): <https://google.github.io/styleguide/javaguide.html>
- Checkstyle `DeclarationOrder` and `OverloadMethodsDeclarationOrder`:
  <https://checkstyle.sourceforge.io/checks/coding/declarationorder.html>,
  <https://checkstyle.sourceforge.io/checks/coding/overloadmethodsdeclarationorder.html>
- Javadoc authoring: <https://www.oracle.com/technical-resources/articles/java/javadoc-tool.html>
- JUnit 5 user guide: <https://junit.org/junit5/docs/current/user-guide/>
