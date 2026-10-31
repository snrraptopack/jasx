# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository.

## What JASX is

JASX is **JSX for Java**: Java source files (`.jasx`) in which markup such as `<div class="card">{name}</div>` is a primary expression. A compiler rewrites only the markup into plain Java, and javac type-checks the result.

JASX is a **language, not a framework**, exactly as JSX is to React:

- The **core language** defines the syntax, the AST, children normalization, component resolution, the static/dynamic classification of positions and a standard **factory lowering**: markup becomes calls such as `F.h("div", attrs, children...)` on a factory class `F`.
- A **target** is a library that supplies the factory, typed attribute builders, an element/attribute schema and, optionally, its own lowering. This works like React, Preact or Solid supplying the runtime for JSX.

## Current focus: the core language only

**The goal right now is JASX as JSX is without React.** Targets come later.

- `html` exists only as a small **reference target**, so that core semantics can be tested (`jasx-spec/tests/render`).
- **`live` (a reactive, server-driven target) is deferred and NOT decided.** `jasx-spec/targets/live.md` only records ideas and is not normative. Don't implement it, don't design core features around it, and don't describe it as a goal.
- Reactivity is not part of the core. The core only says which positions are **static** or **dynamic** (`semantics.md` §8), not when they are re-evaluated.

## Current state

- `jasx-spec/` is the **normative source of truth**. `JASX-update1.md` is the **historical** design proposal: don't implement from it.
- **Milestones 1 and 2 are done.** The parser passes the parse and error conformance tests, and the compiler with the `html` reference target passes the render tests: `.jasx` compiles to Java, javac compiles it, and it renders the expected HTML.
- **Milestone 3 is mostly done:** the annotation processor, the Gradle plugin (with javac errors reported at `.jasx` locations), VS Code syntax highlighting, publishing to Maven Local, and a runnable example (`examples/hello`). Still missing: publishing to a public repository, and a language server.

## Known gaps

- **javac errors in Gradle builds** are printed twice: first by `compileJava`, pointing at the generated file (same line number as the `.jasx` source), then by `reportJasxJavacErrors` at the `.jasx` location. The second compilation doesn't run annotation processors, so code that uses processor-generated classes can get extra errors there.
- **Not published to a public repository.** `./gradlew publishToMavenLocal` installs everything in `~/.m2`; `examples/hello` uses a composite build instead.

## Repository layout

```
JASX-update1.md            historical design proposal
jasx-spec/                 normative specification (see jasx-spec/README.md)
  grammar.md, ast.md, ast.schema.json, semantics.md, diagnostics.md, conformance.md
  targets/html.md          reference target
  targets/live.md          deferred ideas, not normative
  CHANGELOG.md
  tests/parse/   *.jasx -> *.ast.json          core: parser
  tests/errors/  *.jasx -> *.diagnostics.json  core: diagnostics
  tests/render/  *.jasx -> *.html              reference target: html
jasx-parser/               JavaLexer, markup parser, AST, JSON output, CLI (no third-party deps)
  src/main/resources/jasx/parser/entities.txt  HTML named character references (generated)
jasx-runtime/              jasx.runtime.Element and @jasx.Component / @jasx.Optional / @jasx.Default
jasx-compiler/             declaration scanner, file classes, component index, resolver, factory lowering, scoped CSS, span maps,
                           in-memory javac runner with diagnostic remapping, CLI
jasx-target-html/          html reference target: descriptor, schemas/html.json, factory jasx.html.Html,
                           renderer; attribute builders jasx.html.attrs.* generated from the schema
jasx-processor/            annotation processor: writes META-INF/jasx/components.json
jasx-gradle-plugin/        Gradle plugin id "dev.jasx": compileJasx task per source set
jasx-conformance/          JUnit runner for jasx-spec/tests (all levels)
editors/vscode/            VS Code extension: TextMate grammar + Node tests (npm test)
examples/hello/            standalone example build (composite build of this repository)
```

## Implementation plan

These decisions are already made. Follow them unless the user changes them.

- **Java 21**, **Gradle 9 (Kotlin DSL)** through the committed wrapper, multi-module:

| Module | Contents | Status |
|---|---|---|
| `jasx-parser` | `JavaLexer`, markup parser with error recovery, AST, JSON serializer, `--dump-ast` CLI | done |
| `jasx-runtime` | `jasx.runtime.Element`, `@jasx.Component`, `@jasx.Optional`, `@jasx.Default` | done |
| `jasx-compiler` | declaration scanner, component index, name resolution, normalization, factory lowering, line preservation, span maps, javac diagnostic remapping, target loading, CLI | done |
| `jasx-target-html` | `html` reference target: descriptor, schema, factory, renderer, generated attribute builders | done |
| `jasx-conformance` | runner for `jasx-spec/tests`: parse, errors, AST schema, render | done |
| `jasx-processor` | annotation processor that writes `META-INF/jasx/components.json` (Gradle aggregating incremental processor) | done |
| `jasx-gradle-plugin` | `dev.jasx` plugin: `compileJasx` per source set, output added to the Java sources; TestKit functional tests | done |
| `editors/vscode` | TextMate grammar injected into VS Code's Java grammar; tests with `vscode-textmate` | done |

  Later: publishing, LSP, IntelliJ plugin. Other targets are undecided.
- The Java package root is `jasx` (`jasx.parser`, `jasx.runtime`, `jasx.html`, …), matching the names the spec uses.
- `jasx-parser`, `jasx-runtime` and `jasx-compiler` must stay free of third-party runtime dependencies (the compiler has its own small JSON reader). Test-only dependencies (JUnit, Jackson, json-schema-validator) are fine.
- The compiler never references a target at compile time. It loads the target's descriptor and schema from the classpath (`Target.load`).

### Milestones

1. **Parser** (done): `tests/parse` and `tests/errors` pass, and every AST validates against `ast.schema.json`.
2. **Compiler and `html` reference target** (done): factory lowering, component index, line preservation, diagnostic remapping. `tests/render` passes.
3. **Tooling** (mostly done): annotation processor, Gradle plugin and TextMate grammar are done; publishing, javac remapping in Gradle builds, and an LSP remain.
4. **Targets:** to be decided after the core is complete.

### How the compiler works

1. Parse each `.jasx` file (`JasxParser`).
2. Scan each source with `JavaStructure` (tokens only, markup skipped) for the package, imports, named types, method headers and top-level declarations, and build the component index. If a `.jasx` file has top-level fields or methods, `JavaStructure` creates its **file class** (`semantics.md` §9) and `FileClass` computes the text that wraps the file in it. Problems here are JASX2006, JASX2010–JASX2013. `StyleBlocks` then checks the file's style block (`semantics.md` §10) and scopes its CSS with `ScopedCss` (JASX2014–JASX2016, JASX2106).
3. Lower each `.jasx` file without syntax errors (`Lowering`, writing through `Emitter`). Java code is copied verbatim, except for **insertions** (`Lowering.Insertion`) on the same line: `@jasx.Component` before components, and for a file class its header after the imports, `static` in each top-level declaration, and `}` after the end of the file. Markup becomes `F.h(...)`, `F.fragment(...)`, `F.keyed(...)` and direct component calls. Style blocks are removed (their lines stay, empty); the file's one style sheet becomes a `jasx$styles` field inserted at the start of the style class (the file class, or the top-level class of the first block), and every intrinsic element of the file is wrapped in `F.scoped(C.jasx$styles, ...)`. `Emitter` only adds line breaks to catch up with the source line, and records a `SpanMap`.
4. `JavacRunner` compiles the generated Java in memory and remaps javac's diagnostics through the span maps (code `javac`).

## Commands

Git Bash on this machine doesn't have `java` on its `PATH`, so set `JAVA_HOME` first:

```
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot"
./gradlew build                          # compile, unit tests, conformance suite
./gradlew :jasx-parser:test              # parser unit tests only
./gradlew :jasx-conformance:test         # conformance suite only
./gradlew :jasx-compiler:test            # compiler unit tests only
./gradlew :jasx-gradle-plugin:test       # plugin functional tests (runs real Gradle builds; slow)
(cd editors/vscode && npm install && npm test)   # grammar tests (Node)
./gradlew publishToMavenLocal            # install all dev.jasx artifacts in ~/.m2
./gradlew -p examples/hello run --args=--print   # build and run the example (prints the page)
./gradlew -p examples/hello run          # serve the example at http://localhost:8080
./gradlew -q :jasx-parser:run --args="--dump-ast ../jasx-spec/tests/parse/001-element.jasx"
./gradlew -q :jasx-compiler:run --args="../jasx-spec/tests/render/001-basic.jasx"
                                         # prints the generated Java; --out DIR writes files
                                         # paths are relative to the module directory
```

The CLI prints the AST (or `--diagnostics` JSON) to stdout and console-form diagnostics to stderr. Its JSON format matches the fixtures byte for byte, so it can draft a new parse fixture. **Review every drafted fixture by hand**: a fixture generated by the parser only proves the parser agrees with itself. Compute expected diagnostic ranges by hand.

When running Java directly on Windows, separate classpath entries with `;`, not `:`.

## Rules

### Spec

- **Every behaviour change goes through the spec.** If implementation work shows that the spec is wrong or ambiguous, fix the spec text, add an entry to `jasx-spec/CHANGELOG.md`, and add or adjust a conformance test, in the same change.
- **Tests are never renumbered.** New tests take the next number in their directory. Each test covers one behaviour and is named after it.
- **Changing an existing expected output is a spec change** and must be listed in the CHANGELOG.
- **Diagnostic codes are never renumbered or reused.** `JASX1xxx` is syntax, `JASX20xx` core semantics, `JASX21xx` target capability, and `JASX3xxx` warnings. JASX2104, JASX3001 and JASX3002 are reserved and must not be reported.
- Keep the spec target-neutral: anything specific to one target belongs in `jasx-spec/targets/<id>.md`.
- Normative wording uses RFC 2119 keywords (**MUST**, **SHOULD**, **MAY**).

### Fixtures

- Fixtures use **LF line endings**, enforced by `.gitattributes`. AST ranges are byte-exact, so a CRLF checkout breaks every parse test.
- `range` and `column` are in **UTF-16 code units**: `range` is `[start, end)`, `line` is 1-based and `column` is 0-based.
- Every `*.ast.json` must validate against `ast.schema.json` (the conformance runner checks this) and every `loc` must agree with its `range`.
- Expected diagnostics compare only `code`, `severity`, `range` and `loc`. Messages are free text.

### Code style

- **The user reads this code.** Write it to be read: a header comment on every class saying what it does and where it fits, named intermediate values instead of long boolean chains, small methods with clear names, and comments that explain *why*. Cite the spec section a rule comes from (`semantics.md §5.2`).
- Prefer clarity over brevity, while keeping the style consistent with the surrounding code.

### Implementation invariants

- The parser **never builds a Java syntax tree**. It only tokenizes Java (`grammar.md` §3).
- Markup detection is the exact rule in `grammar.md` §4: the previous significant token must be in set **S**, braces are tracked by kind, and `yield` counts only at the start of a statement. Don't add heuristics.
- After an error, the parser recovers and keeps going. Only the innermost construct reports reaching end of input, so one missing `}` doesn't cascade into several errors.
- `JavaCode` segments are emitted **byte for byte**, apart from the insertions the spec defines (`@jasx.Component`, `semantics.md` §5.1, and the file class, §9.4), which never contain a line break. Lowering must not add or remove lines in them, and generated code starts on the same line as its markup (`semantics.md` §6.5).
- Generated code uses fully qualified names. Each child is passed to `h`/`fragment` as exactly one variadic argument, cast to `Object` (`semantics.md` §6.7).
- Components are called directly and positionally, with arguments in **parameter order**. Only names that don't start with a lowercase letter are components.
- Markup errors are reported by JASX with `.jasx` locations **before** javac sees generated code. javac errors are remapped and prefixed `javac:`.

## Gotchas

- **Agent file tools decode Unicode escapes.** Writing a backslash, `u` and four hex digits through the Write or Edit tool stores the decoded character instead. `grammar.md` §3 and §5 deliberately contain two literal escapes: backslash-`u0041` and backslash-`u007B`. After editing `grammar.md`, check that they are still escapes. In Java tests, build escapes at run time (`"\\" + "u" + hex`, see `JasxParserTest.esc`). In prose, describe escapes in words.
- **javac also processes Unicode escapes in comments.** A backslash-`u` followed by non-hex text in a Java comment is a compile error, so never write one in Java source.
- In Java mode, Unicode escapes **are** significant: the escape backslash-`u007D` is a real `}` to javac, so it can close an expression container (and the container's range then includes the whole escape). In markup, they are not processed.
- `Boolean` children render as `true`/`false`. JASX doesn't hide them as JSX does, and `&&` is not a rendering idiom.
- A `.jasx` file with a top-level field or method is wrapped in a `public final` class named after the file (`semantics.md` §9), and its top-level types become nested types. The file name must therefore be a valid class name. Render tests are compiled as `T.jasx` (`conformance.md` §4).
- `<style>` is a **style block** (raw CSS up to `</style>`, `grammar.md` §6.5, §7.3) in two places: where a statement or member starts (in a class body, a method body, or at the top level of a file class), and as a child in markup when its content doesn't start with `{`. `<style>{css}</style>` and `<style>` with attributes are ordinary elements. A file may have several style blocks; they form one style sheet.
- `ScopedCss` is not a CSS parser: it only finds selectors. Keep it that way; the spec (`semantics.md` §10.4) defines exactly what it reads.
- A cast directly on markup (`(Element) <div/>`) is not supported, by design.
- Character references are decoded in text only. Attribute strings use Java escapes, so `title="a&amp;b"` keeps `&amp;` literally.

## Environment

- Development machine: Windows 11 (temporary). Shells: PowerShell and Git Bash.
- JDK: Eclipse Temurin 21 at `C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot`. Gradle comes from the wrapper.
- Node.js is available for one-off scripts.
- Git: work on a branch off `main` and **push after every commit**, because the machine is temporary. Remote: `https://github.com/snrraptopack/jasx`.
