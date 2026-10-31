# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository.

## What JASX is

JASX is **JSX for Java**: Java source files (`.jasx`) in which markup such as `<div class="card">{name}</div>` is a primary expression. A compiler rewrites only the markup into plain Java, and javac type-checks the result.

JASX is a **language, not a framework**, exactly as JSX is to React:

- The **core language** defines the syntax, the AST, children normalization, component resolution and a standard **factory lowering**: markup becomes calls such as `F.h("div", attrs, children...)` on a factory class `F`.
- A **target** is a library that supplies the factory, typed attribute builders, an element/attribute schema and, optionally, its own lowering. This works like React, Preact or Solid supplying the runtime for JSX via `jsxImportSource`.
- Standard targets: `html` (server-rendered HTML, the first target) and `live` (server-driven reactive UI like LiveView, the long-term goal). Third parties can write more without changing the language.

**Order of work: make the core language solid first, then add targets.** Don't put target-specific behaviour into the core.

## Current state

- The repo contains the **specification only**. No implementation code exists yet.
- `jasx-spec/` is the **normative source of truth**.
- `JASX-update1.md` is the **historical** design proposal. It is superseded wherever it differs from the spec. Don't implement from it.

## Repository layout

```
JASX-update1.md          historical design proposal
jasx-spec/
  README.md              principles, terminology, versioning
  grammar.md             lexical/syntactic grammar, markup detection rule, early errors
  ast.md, ast.schema.json  ESTree-style AST and its JSON Schema
  semantics.md           core semantics + target interface (descriptor, factory, schema)
  diagnostics.md         diagnostic codes and JSON format
  conformance.md         conformance levels and test format
  targets/html.md        html target
  targets/live.md        live target (less mature)
  CHANGELOG.md
  tests/parse/   *.jasx -> *.ast.json          core: parser
  tests/errors/  *.jasx -> *.diagnostics.json  core: diagnostics
  tests/render/  *.jasx -> *.html              target: html
```

## Planned implementation

These decisions are already made. Follow them unless the user changes them.

- **Java 21**, built with **Gradle (Kotlin DSL)**, as a multi-module project:

| Module | Contents | Depends on |
|---|---|---|
| `jasx-parser` | Java-mode tokenizer, markup parser with error recovery, AST, JSON serializer (`--dump-ast`) | nothing (no third-party deps) |
| `jasx-runtime` | `jasx.runtime.Element`, `@jasx.Component`, `@jasx.Optional`, `@jasx.Default` | nothing |
| `jasx-compiler` | normalization, component index, name resolution, factory lowering, span map, javac diagnostic remapping, target loading | parser |
| `jasx-processor` | annotation processor that writes `META-INF/jasx/components.json` | runtime |
| `jasx-target-html` | `html` target: descriptor, factory, generated attribute builders, schema, renderer | runtime |
| `jasx-conformance` | runner for `jasx-spec/tests` (all levels) | parser, compiler, target-html |
| `jasx-gradle-plugin` | compiles `.jasx` to generated sources | compiler |

  Later: `jasx-target-live`, `jasx-lsp`, an IntelliJ plugin and a TextMate grammar.
- The Java package root is `jasx` (`jasx.runtime`, `jasx.html`, …), matching the names the spec uses.

### Milestones

1. **Parser:** `jasx-parser` and `jasx-conformance` pass `tests/parse` and `tests/errors`. The conformance runner comes first, and it is the definition of done.
2. **Compiler and `html` target:** factory lowering, component index, line preservation, diagnostic remapping. `tests/render` passes.
3. **Tooling:** Gradle plugin, TextMate grammar, then LSP.
4. **`live` target:** custom lowering (skeleton plus slots), reactive runtime, transport.

## Rules

### Spec

- **Every behaviour change goes through the spec.** If implementation work shows that the spec is wrong or ambiguous, fix the spec text, add an entry to `jasx-spec/CHANGELOG.md`, and add or adjust a conformance test, in the same change.
- **Tests are never renumbered.** New tests take the next number in their directory. Each test covers one behaviour and is named after it.
- **Changing an existing expected output is a spec change** and must be listed in the CHANGELOG.
- **Diagnostic codes are never renumbered or reused.** `JASX1xxx` is syntax, `JASX20xx` core semantics, `JASX21xx` target capability, and `JASX3xxx` warnings.
- Keep the spec target-neutral: anything specific to one target belongs in `jasx-spec/targets/<id>.md`.
- Normative wording uses RFC 2119 keywords (**MUST**, **SHOULD**, **MAY**).

### Fixtures

- Fixtures use **LF line endings**, enforced by `.gitattributes`. AST ranges are byte-exact, so a CRLF checkout breaks every parse test.
- `range` and `column` are in **UTF-16 code units**: `range` is `[start, end)`, `line` is 1-based and `column` is 0-based.
- Every `*.ast.json` must validate against `ast.schema.json`, and every `loc` must agree with its `range`. When writing ASTs by hand, generate them with a script rather than counting offsets.
- Expected diagnostics compare only `code`, `severity`, `range` and `loc`. Messages are free text.

### Implementation invariants

- The parser **never builds a Java syntax tree**. It only tokenizes Java (`grammar.md` §3).
- Markup detection is the exact rule in `grammar.md` §4: the previous significant token must be in set **S**, braces are tracked by kind, and `yield` counts only at the start of a statement. Don't add heuristics.
- `JavaCode` segments are emitted **byte for byte**. Lowering must not add or remove lines in them, and generated code starts on the same line as its markup (`semantics.md` §6.5).
- Generated code uses fully qualified names. Each child is passed to `h`/`fragment` as exactly one variadic argument, cast to `Object` (`semantics.md` §6.7).
- Components are called directly and positionally, with arguments in **parameter order**. Only names that don't start with a lowercase letter are components.
- Markup errors are reported by JASX with `.jasx` locations **before** javac sees generated code. javac errors are remapped and prefixed `javac:`.

## Gotchas

- **Agent file tools decode Unicode escapes.** Writing a backslash, `u` and four hex digits through the Write or Edit tool stores the decoded character instead. `grammar.md` §3 and §5 deliberately contain two literal escapes: backslash-`u0041` and backslash-`u007B`. After editing `grammar.md`, check that they are still escapes and not a plain `A` or `{`. In prose such as this file, describe escapes in words instead of writing them out.
- In Java mode, Unicode escapes **are** significant: the escape backslash-`u007B` is a real `{` to javac, so the tokenizer must decode escapes before counting braces. In markup, they are not processed.
- `Boolean` children render as `true`/`false`. JASX doesn't hide them as JSX does, and `&&` is not a rendering idiom.
- A cast directly on markup (`(Element) <div/>`) is not supported, by design.
- On `live`, every `{...}` becomes a lambda, so captured locals must be effectively final. Code that compiles on `html` can fail on `live`.

## Environment

- Development machine: Windows 11. Shells: PowerShell and Git Bash.
- **No JDK, Gradle or Maven is installed yet.** Install a JDK 21 (for example Eclipse Temurin) before building. The Gradle wrapper will be committed once the build exists, so a global Gradle isn't needed.
- Node.js is available for one-off scripts, such as generating fixture ASTs.

## Commands

There are no build commands yet. Once the Gradle build exists, document them here:

```
./gradlew build                 # compile and unit-test everything
./gradlew :jasx-conformance:run # run the conformance suite
```
