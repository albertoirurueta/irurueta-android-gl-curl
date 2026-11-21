# TypeScript development reference

General TypeScript code agreements for implementing a task in any npm/TypeScript project this catalog scaffolds
— library, React, Angular, Ionic, or a Vaadin Hilla frontend (React Native apps have their own `react-native` key,
`iru-react-native-code-one-task`; `react-native.md` here is only a pointer plus a minimal fallback for repositories
that haven't installed it) — plus one file per framework for the
conventions that only apply there. These files exist so `iru-typescript-code-one-task` can load **only** what the
task in front of it actually needs, instead of carrying every rule in context on every task.

## Read this much, and no more

Detect the framework first (`iru-typescript-code-one-task` Step 1 — inspect `package.json`/`pom.xml`), then read:

| Read | When |
|---|---|
| `code-style.md` | **Always.** Strict typing, `readonly`, discriminated unions, no `any`, named exports, ESM, error handling, async. |
| `tsdoc.md` | The task adds or changes an exported function, class, interface, type alias, or enum. |
| `testing.md` | The task writes or updates tests — which is nearly every task, since writing the tests is part of implementing it. |
| exactly one framework file below | Matching the framework Step 1 detected — never more than one, and none when the project is a plain library. |
| `ui-contract.md` + `ui-implementation.md` | The task adds or changes a screen, component, list or other UI. The contract is the catalog-wide rule set (verbatim in every UI coding skill); `ui-implementation.md` maps it onto React, Angular, Ionic and Hilla — read its shared part plus your framework's section. `ui-screenshots.ts` is the shared Playwright helper to copy into `e2e/support/` when the project has none. |

| Framework key | Detected by | Framework file |
|---|---|---|
| `library` | None of the signals below match — a plain npm/TypeScript package. | *(none — `code-style.md`/`tsdoc.md`/`testing.md` are the whole list)* |
| `react` | `react` + `vite` in `package.json` dependencies/devDependencies. | `react.md` |
| `angular` | `@angular/core` in `package.json` dependencies. | `angular.md` |
| `react-native` | `expo` or `react-native` in `package.json` dependencies. | *(handed to `iru-react-native-code-one-task` when installed — `SKILL.md` Step 1; otherwise `react-native.md`'s minimal fallback)* |
| `ionic` | `@ionic/angular` or `@ionic/react` together with `@capacitor/core`. | `ionic.md` |
| `hilla-frontend` | `@vaadin/hilla`/`hilla-spring-boot-starter` in `pom.xml`, or a `src/main/frontend/` directory. | `hilla-frontend.md` |

A task that only changes logic inside one existing function in a plain library, with no exported-symbol change,
needs `code-style.md` plus `testing.md`, and nothing else. Don't read the rest speculatively.

## The three rules that override everything else

1. **Match the surrounding code.** Every rule here is the default for *new* code. If the file or package you're
   editing consistently does something else, follow it and note the divergence in the report rather than
   converting the file to this document's preference as a side effect of an unrelated task. Reordering or
   restyling untouched code turns a two-line change into an unreviewable diff.
2. **A task implements what the task says.** These references tell you *how* to write what was asked for. They
   never license adding an abstraction, a wrapper hook, a new state layer, or a "while I'm here" refactor the
   plan didn't ask for.
3. **This skill doesn't validate.** It never runs the test suite, coverage, code-quality/lint checks, license
   headers, or a TSDoc audit — the calling `iru-typescript-code-one-task-group` does all of that once for the
   whole task group. Write code that will pass those gates; don't run them here.

## Sources

Compiled from the following, cross-checked against each other, with this catalog's own stated preferences taking
precedence where they are stricter (notably on `any`, on `readonly`, and on default vs. named exports, which most
published style guides leave to project taste).

- TypeScript Handbook, "Do's and Don'ts": <https://www.typescriptlang.org/docs/handbook/declaration-files/do-s-and-don-ts.html>
- `typescript-eslint` recommended and strict rule sets: <https://typescript-eslint.io/rules/>
- TSDoc specification: <https://tsdoc.org/>
- Vitest guide: <https://vitest.dev/guide/>, Jest docs: <https://jestjs.io/docs/getting-started>
- Testing Library guiding principles and query priority: <https://testing-library.com/docs/queries/about/#priority>
- React docs, "Thinking in React" and rules of hooks: <https://react.dev/learn/thinking-in-react>, <https://react.dev/reference/rules/rules-of-hooks>
- Angular style guide and signals guide: <https://angular.dev/style-guide>, <https://angular.dev/guide/signals>
- Expo Router docs: <https://docs.expo.dev/router/introduction/> (the full React Native guidance and its sources live in
  `iru-react-native-code-one-task/reference/README.md`)
- Ionic Framework component docs: <https://ionicframework.com/docs/components>, Capacitor docs: <https://capacitorjs.com/docs>
- Vaadin Hilla docs (frontend views, generated endpoints, React components): <https://vaadin.com/docs/latest/hilla>
- UI contract sources (Playwright component testing and accessibility testing, TanStack Virtual, Angular CDK scrolling,
  Ionic infinite scroll, Vaadin `VirtualList`/`Grid`, axe-core): listed in `ui-implementation.md`
