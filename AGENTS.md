# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository.

## What JASX is

JASX is **JSX for Java**: Java source files (`.jasx`) in which markup such as `<div class="card">{name}</div>` is a primary expression. A compiler rewrites only the markup into plain Java, and javac type-checks the result.

JASX is a **language, not a framework**, exactly as JSX is to React:

- The **core language** defines the syntax, the AST, children normalization, component resolution, the static/dynamic classification of positions and a standard **factory lowering**: markup becomes calls such as `F.h("div", attrs, children...)` on a factory class `F`.
- A **target** is a library that supplies the factory, typed attribute builders, an element/attribute schema and, optionally, its own lowering. This works like React, Preact or Solid supplying the runtime for JSX.

## Current focus: from the core language to targets

**The core language is JASX as JSX is without React, and it is complete.** Work has moved to targets (Milestone 4), before publishing: the extension point for targets (custom lowering) is done, and JASX's own runtime target, **`web`**, has a first version.

- `html` exists only as a small **reference target**, so that core semantics can be tested (`jasx-spec/tests/render`).
- **`web` is JASX's runtime target, decided by the user: server-driven, like Phoenix LiveView** (not state in the browser, not islands). Its spec is the draft `jasx-spec/targets/web.md`; the module is `jasx-web`. The user named it `web` (not `live`). Input must stay in the browser: never send every keystroke (`oninput` debounced by default, forms send fields on submit). **No browser-only layer:** the user rejected Java DSLs for browser code (browser commands such as a `Js` builder, signals). A design for plain JavaScript (script blocks importing `.js` files, calls such as `onclick="toggleMenu({menu: $menu, save})"`) is **parked** in `docs/discussions/browser-javascript.md`: don't implement it until the user says so.
- Reactivity is not part of the core. The core only says which positions are **static** or **dynamic** (`semantics.md` §8); `web` makes them reactive through its own lowering. Don't move reactive features into the core.
- **State and events live in `jasx-ui`** (`jasx-spec/ui.md`, draft), a dependency-free library shared by interactive targets, so a future desktop target offers the same API. It is not the core and not the `html` target. A target supplies a `Scheduler` and its own holes (subclasses of `jasx.ui.Computation`), and implements the event kinds (`web`'s `WebEvent` implements them all).
- **Order of work agreed with the user:** (1) robustness fixes from an external review (done: spec revision 15); (2) Java completion inside `.jasx` in the language server (done: `JavaCompletions`); (3) performance: keyed updates and DOM morphing (done: spec revision 17), the shared stream across tabs (done: revision 18), then static templates; (4) session binding, same-site checks and limits in `WebApp` (done: revision 19, `SessionCookie`), then `jasx-web-servlet` and the Spring Boot starter on top of it (both done; adapters must stay thin so other frameworks are easy).

## Remaining work (agreed with the user on 2026-11-01)

The user asked for items 1–4 to be done in this order, then the rest. Mark each item done here, with its commit, as it lands; keep this list as the hand-over point between sessions.

1. **(done, spec revision 20)** **Server updates keep browser changes.** Before, the client's merge (`morphAttributes` in `web.js`) resets every attribute to what the server rendered, so a `<details>` the user opened closes again when its hole re-renders. Fix: the client remembers each element's last server-rendered attributes and changes only those the server changed (three-way merge); `class` merged per class name. Spec: `targets/web.md` §8.4 (merging). Design notes in `docs/discussions/browser-javascript.md` §6 (only this part; the rest of that document stays parked).
2. **(done: `jasx-web-test`)** **A testing library for pages** (like Phoenix's LiveViewTest): render a page in a test, click, type, submit, and check the HTML, without a browser, over `WebApp`'s public API. New module.
3. **Static templates:** send a hole's static markup once, and only the changed values afterwards (`targets/web.md` §10).
4. **Forms and navigation:** (a) restore form input when a discarded page reloads; (b) file uploads; (c) navigation between pages without a full reload.
5. **Benchmarks** of the `web` target (pages per server, update latency, payload sizes).
6. **Publishing to Maven Central.** Waits on the user: a license (Apache-2.0 suggested), a group id (`dev.jasx` needs the domain `jasx.dev` verified; otherwise `io.github.snrraptopack`), a Sonatype Central account and a GPG key.
7. **Plain JavaScript in the browser:** parked in `docs/discussions/browser-javascript.md`; only when the user says so.

## Current state

- `jasx-spec/` is the **normative source of truth**. `JASX-update1.md` is the **historical** design proposal: don't implement from it.
- **Milestones 1 and 2 are done.** The parser passes the parse and error conformance tests, and the compiler with the `html` reference target passes the render tests: `.jasx` compiles to Java, javac compiles it, and it renders the expected HTML.
- **Milestone 3 is mostly done:** the annotation processor, the Gradle and Maven plugins (with javac errors reported at `.jasx` locations, and `.jasx` names in stack traces), VS Code syntax highlighting, a language server (`jasx-lsp`: JASX errors while typing, javac errors on save once the project is built, completion of tags and props in markup and of Java members and names in Java code, go-to-definition and hover on components) with its VS Code client, publishing to Maven Local, and a runnable example (`examples/hello`). Still missing: publishing to a public repository, which waits until Milestone 4 has JASX's own runtime target and more examples.
- **Milestone 4 has started:** targets can have their own lowering (`semantics.md` §7.4, `MarkupLowering`), and the `web` target works (`jasx-web`: reactive holes, events over HTTP, pushes over server-sent events, a server on the JDK's HTTP server, the browser client, `examples/web`, a servlet adapter and a Spring Boot starter). Next: more examples, then publishing.

## Known gaps

- **The Gradle type check compiles twice.** `compileJasx` type-checks the generated Java with javac (`JavacRunner.typeCheck`: same classpath, annotation processors and release as `compileJava`, no class files) so that javac's errors in `.jasx` code are reported once, at `.jasx` locations, and `compileJava` doesn't run. On success, `compileJava` then compiles the source set again. `jasx { typeCheck.set(false) }` turns the check off; javac's errors then point at the generated files (same line numbers). Other `compileJava` options (`compilerArgs`) are not copied into the check.
- **The language server needs a build for the full picture.** It analyses a Gradle-style source set (`src/<set>/java` and `src/<set>/jasx` together, as the build plugins compile them) as one group, and any other file alone, each under its own key. The Gradle plugin's `writeJasxEditorInfo` task writes `build/jasx/editor/<set>.json` (`EditorInfo`: target, classpaths, processor path, release) on every build, and the Maven plugin `target/jasx/editor/<set>.json`; with it, the server knows the components of the project's jars and runs javac (`JavacRunner.typeCheck`) on open and save, in the background, showing its errors in `.jasx` files only. Before the first build (or without the plugin, or with a custom build directory) it uses its bundled `html` target, knows no jar's components (JASX2001), and shows no javac errors. javac errors are hidden in a file as soon as it is edited, until the next save.
- **Not published to a public repository.** `./gradlew publishToMavenLocal` installs everything in `~/.m2`; `examples/hello` uses a composite build instead, and `examples/hello-maven` needs `publishToMavenLocal` first.
- **The `web` target is a first version** (`targets/web.md` §10). Keyed lists keep unchanged items (`targets/web.md` §4.3; inputs compared through the lambda's captured fields, `Captures`), and the client merges `html` patches into the DOM, but a plain hole still renders its whole content again (component state inside it starts afresh), and there are no static templates yet (each patch carries full HTML). `WebServer` (the JDK's HTTP server) is HTTP/1.1 only; the tabs of a browser share one stream (`targets/web.md` §6.2, `SharedStream`, a leader tab chosen with Web Locks), except outside a secure context, where each tab has its own and many tabs can hit the browser's six-connections-per-server limit. The core is `WebApp`, which any server can call through `WebApp.Request`; `WebServer` is the JDK adapter, `jasx-web-servlet` the servlet one, and `jasx-web-spring-boot-starter` (built and tested with Spring Boot 3.4; Spring Boot 4 untested) the Spring one. `WebApp` binds pages to a `jasx_session` cookie and checks events (`targets/web.md` §6.3); there is no rate limiting. The browser client's tests (jsdom) run in `./gradlew build` (`:jasx-web:clientTests`) only when Node.js is installed; without it they are skipped with a warning.
- **The Maven plugin's type check uses the classpath as the annotation processor path** (Maven's default). It doesn't read `annotationProcessorPaths` from the maven-compiler-plugin's configuration, nor other compiler options. Its plugin descriptor (`META-INF/maven/plugin.xml`) is written by hand; keep it in sync with the mojos' fields (the functional tests catch a mismatch).

## Repository layout

```
JASX-update1.md            historical design proposal
jasx-spec/                 normative specification (see jasx-spec/README.md)
  grammar.md, ast.md, ast.schema.json, semantics.md, diagnostics.md, conformance.md
  targets/html.md          reference target
  targets/web.md           the web target (draft)
  ui.md                    jasx.ui: state, scheduling and typed events shared by interactive targets (draft)
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
jasx-gradle-plugin/        Gradle plugin id "dev.jasx": compileJasx task per source set (compiles .jasx, type-checks with javac),
                           writeJasxEditorInfo (classpaths for the language server)
jasx-conformance/          JUnit runner for jasx-spec/tests (all levels)
jasx-lsp/                  language server over stdio (no third-party deps): JASX and javac diagnostics, completion, definition, hover
editors/vscode/            VS Code extension: TextMate grammar, language server client (extension.js), Node tests
jasx-maven-plugin/         Maven plugin: goals compile, test-compile, source-names, test-source-names (hand-written plugin.xml)
jasx-ui/                   shared runtime of interactive targets: jasx.ui (State, Derived, Effect, Reactive, Computation,
                           Scheduler; Event, InputEvent, KeyEvent, MouseEvent, FormEvent, Handler, Action, form-to-record binding)
jasx-web/                  the web target: runtime jasx.web (Web, WebApp, Page, WebEvent, holes, Renderer), lowering
                           jasx.web.compiler.WebLowering, JDK server jasx.web.server.WebServer, client jasx/web/web.js,
                           builders jasx.web.attrs.* generated from the html schema; src/test/js: client tests (jsdom)
jasx-web-servlet/          the web target on Jakarta Servlet 6 containers: ServletAdapter (requests to WebApp), JasxServlet
                           (pages); tests on an embedded Tomcat
jasx-web-spring-boot-starter/  web in Spring Boot: JasxWebAutoConfiguration (WebApp bean from jasx.web.* properties, Element pages,
                           the runtime's servlet, WebPage return values), WebPage; tests in a real Spring Boot app
jasx-web-test/             page tests without a browser: TestPage (render, click, type, submit, await pushes) over
                           WebApp's public API, with its own small DOM (Dom, HtmlParser, Selector, Fields)
docs/web.md                user guide to the web target
docs/discussions/          design proposals under discussion (not specified, not implemented)
examples/hello/            standalone example build (composite build of this repository)
examples/hello-maven/      the same idea with Maven (needs ./gradlew publishToMavenLocal)
examples/web/              the web target: counter, shared to-do list, live search, server push (clock and chat)
examples/web-spring/       the web target in Spring Boot: a team board (controller pages, one .jasx per view with its own nested CSS, a @Service with state,
                           a REST API whose changes reach open pages); application plugin, not the Boot plugin
```

## Implementation plan

These decisions are already made. Follow them unless the user changes them.

- **Java 21**, **Gradle 9 (Kotlin DSL)** through the committed wrapper, multi-module:

| Module | Contents | Status |
|---|---|---|
| `jasx-parser` | `JavaLexer`, markup parser with error recovery, AST, JSON serializer, `--dump-ast` CLI | done |
| `jasx-runtime` | `jasx.runtime.Element`, `@jasx.Component`, `@jasx.Optional`, `@jasx.Default` | done |
| `jasx-compiler` | declaration scanner, component index, name resolution, normalization, factory lowering, line preservation, span maps, javac diagnostic remapping, target loading, CLI; the public interface for a target's own lowering (`MarkupLowering`, `MarkupTree`, `LoweringOutput`, `FactoryLowering`, `TargetClassLoader`) | done |
| `jasx-target-html` | `html` reference target: descriptor, schema, factory, renderer, generated attribute builders | done |
| `jasx-conformance` | runner for `jasx-spec/tests`: parse, errors, AST schema, render | done |
| `jasx-processor` | annotation processor that writes `META-INF/jasx/components.json` (Gradle aggregating incremental processor) | done |
| `jasx-gradle-plugin` | `dev.jasx` plugin: `compileJasx` per source set, output added to the Java sources, `.jasx` names in class files; TestKit functional tests (including the configuration cache and a multi-project build) | done |
| `jasx-lsp` | language server (LSP over stdio, no third-party deps): JASX diagnostics while typing, javac diagnostics on save (with the build's classpath), completion of tag names and attributes/props in markup and of Java members and names in Java code (`JavaCompletions`: the group lowered with a marker at the cursor, analysed with javac's tree API), go-to-definition and hover on components; `installServer` copies a self-contained jar into the VS Code extension | done (first version) |
| `jasx-maven-plugin` | Maven plugin `dev.jasx:jasx-maven-plugin`: the same steps as the Gradle plugin, sharing `BuildSources`, `EditorInfo` and `JasxSourceNames` from the compiler | done; functional tests run real Maven builds of `examples/hello-maven` |
| `editors/vscode` | TextMate grammar injected into VS Code's Java grammar; language server client; tests with `vscode-textmate` | done |
| `jasx-ui` | shared runtime of interactive targets (`ui.md`): reactive values and their engine (`Computation`, `Scheduler`), typed events and handlers, `FormEvent.as` record binding; no dependencies | first version |
| `jasx-web` | the `web` target (`targets/web.md`): holes on `jasx-ui`, `WebLowering`, builders with typed event overloads, pages and protocol (`WebApp`), JDK server (`WebServer`), browser client; no third-party dependencies | first version |
| `jasx-web-servlet` | `web` on servlet containers: `ServletAdapter` translates requests and responses (the security checks stay in `WebApp`), `JasxServlet` serves pages; streams use async servlets on virtual threads; the servlet API is `compileOnly` (the container provides it) | first version |
| `jasx-web-spring-boot-starter` | `web` in Spring Boot 3: auto-configuration (`WebApp` bean, `JasxWebProperties`, the runtime's async servlet, a lifecycle bean that closes pages before graceful shutdown), controller methods returning `Element` run inside a new page (`PageHandlerAdapter` through `WebMvcRegistrations`), `WebPage` (`() -> <Page />`) is the fallback when an app has its own `WebMvcRegistrations`; Spring is `compileOnly` | first version |
| `jasx-web-test` | page tests without a browser: `TestPage` renders through `WebApp`'s public API (its own session), sends events as the client describes them (no debounce), applies answers and pushes to a small DOM, CSS selectors with a text filter, `await` for pushes; no dependencies besides `jasx-web` | first version |

  Next: the order of work in "Current focus", more examples, then publishing. Later: an IntelliJ client for the language server (and later a deeper IntelliJ plugin), more language server features (rename, find references).
- The Java package root is `jasx` (`jasx.parser`, `jasx.runtime`, `jasx.html`, …), matching the names the spec uses.
- `jasx-parser`, `jasx-runtime`, `jasx-compiler`, `jasx-lsp`, `jasx-ui` and `jasx-web` must stay free of third-party runtime dependencies (the compiler has its own small JSON reader). Test-only dependencies (JUnit, Jackson, json-schema-validator) are fine.
- The compiler never references a target at compile time. It loads the target's descriptor and schema from the classpath (`Target.load`), and the target's lowering class if the descriptor names one.
- The lowering interface (`MarkupLowering`, `MarkupTree`, `LoweringOutput`, `FactoryLowering`) is public API for third-party targets: keep it small, and change it only on purpose. Anything every target must get right (JASX errors, Java code byte for byte, component calls, line preservation) stays in the compiler, not in a lowering.

### Milestones

1. **Parser** (done): `tests/parse` and `tests/errors` pass, and every AST validates against `ast.schema.json`.
2. **Compiler and `html` reference target** (done): factory lowering, component index, line preservation, diagnostic remapping. `tests/render` passes.
3. **Tooling** (mostly done): annotation processor, Gradle and Maven plugins, javac errors at `.jasx` locations, `.jasx` names in stack traces, TextMate grammar and a language server are done; publishing to a public repository remains.
4. **Targets** (started, before publishing): custom lowering is done (`MarkupLowering`, tested with a lowering loaded through `TargetClassLoader` and a Gradle build using a target from another project), and the `web` target has a first version (`jasx-web`, `examples/web`, `jasx-web-servlet`, `jasx-web-spring-boot-starter`). Next: more examples.

### How the compiler works

1. Parse each `.jasx` file (`JasxParser`).
2. Scan each source with `JavaStructure` (tokens only, markup skipped) for the package, imports, named types, method headers and top-level declarations, and build the component index. If a `.jasx` file has top-level fields or methods, `JavaStructure` creates its **file class** (`semantics.md` §9) and `FileClass` computes the text that wraps the file in it. Problems here are JASX2006, JASX2010–JASX2013. `StyleBlocks` then checks the file's style block (`semantics.md` §10) and scopes its CSS with `ScopedCss` (JASX2014–JASX2016, JASX2106).
3. Lower each `.jasx` file without syntax errors (`Lowering`, writing through `Emitter`). Each markup expression is first **analysed** into a `MarkupTree` (normalized children, resolved and checked components, attributes checked and classified per `semantics.md` §8; every JASX error is reported here), then **written** by the target's `MarkupLowering`: `FactoryLowering` by default, or the class the descriptor names (`"lowering"`, §7.4), loaded through `TargetClassLoader`. The lowering decides what intrinsic elements, fragments, keys and children become; the user's Java code reaches it only as `MarkupTree.Code`, which it must place exactly once (`Lowering.Output` checks), and the compiler writes component calls and the statement container frame itself. Java code is copied verbatim, except for **insertions** (`Lowering.Insertion`) on the same line: `@jasx.Component` before components, and for a file class its header after the imports, `static` in each top-level declaration, and `}` after the end of the file. Markup becomes `F.h(...)`, `F.fragment(...)`, `F.keyed(...)` and direct component calls. Style blocks are removed (their lines stay, empty); the file's one style sheet becomes a `jasx$styles` field inserted at the start of the style class (the file class, or the top-level class of the first block), and every intrinsic element of the file is wrapped in `F.scoped(C.jasx$styles, ...)` if one of the file's scoped compounds may match it (`ScopeMatch`: tag name and literal `class`/`id`, `semantics.md` §10.5), otherwise in `F.unscoped(...)`, which uses the sheet but adds no class. `Emitter` only adds line breaks to catch up with the source line, and records a `SpanMap`.
4. `JavacRunner` compiles the generated Java and remaps javac's diagnostics through the span maps (code `javac`): `compile` in memory for the tests and the conformance suite, `typeCheck` as a build would (classpath, annotation processors, release) for the Gradle plugin.
5. After the build's own compilation, `JasxSourceNames` replaces the `SourceFile` attribute of classes compiled from generated files (`Pages.java` becomes `Pages.jasx`), so stack traces and debuggers name the `.jasx` file. Line numbers already match, because lines are preserved. The Gradle plugin does this at the end of `compileJava`, the Maven plugin in its `source-names` goal.

The build plugins share `BuildSources` (reading a source set, compiling, the type-check decision), `EditorInfo` (the file for the language server) and `JasxSourceNames`, all in the compiler; only the build tool's wiring is in each plugin.

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
./gradlew :jasx-lsp:test                 # language server tests
./gradlew :jasx-lsp:installServer        # build the server jar into editors/vscode/server/
(cd editors/vscode && npx @vscode/vsce@3 package --skip-license && code --install-extension jasx-0.6.0.vsix --force)
                                         # package and install the VS Code extension
./gradlew -p examples/hello run --args=--print   # build and run the example (prints the page)
./gradlew -p examples/hello run          # serve the example at http://localhost:8080
./gradlew :jasx-web:test                 # web target: reactive core, pages, compiled .jasx, JDK server
(cd jasx-web/src/test/js && npm install && npm test)   # web target: browser client tests in jsdom
./gradlew :jasx-ui:test                  # shared runtime: reactive engine, form binding
./gradlew :jasx-web-servlet:test         # servlet adapter on an embedded Tomcat
./gradlew :jasx-web-test:test              # the page testing library
./gradlew -p examples/web-spring test    # the example's page tests (TestPage)
./gradlew :jasx-web-spring-boot-starter:test   # the starter in a Spring Boot app (downloads Spring on first run)
./gradlew -p examples/web run            # serve the web example at http://localhost:8080 (PORT=... to change)
./gradlew -p examples/web-spring run     # the Spring Boot example at http://localhost:8080 (PORT=... to change)
./gradlew :jasx-maven-plugin:test       # Maven plugin functional tests: downloads Maven 3.9.9 as a dependency,
                                         # installs the JASX artifacts in ~/.m2, builds examples/hello-maven (slow)
(cd examples/hello-maven && mvn compile exec:java)   # the Maven example by hand, after ./gradlew publishToMavenLocal
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
- `ScopedCss` is not a CSS parser: it only finds selectors. Keep it that way; the spec (`semantics.md` §10.4) defines exactly what it reads. The same goes for `ScopeMatch` (§10.5), which only reads type, class and ID selectors at the top level of a compound and must err towards "may match".
- A cast directly on markup (`(Element) <div/>`) is not supported, by design.
- A child `{...}` starting with `{`, `if`, `for`, `while` or `do` is a **statement container** (`grammar.md` §6.7). Inside it, `<` at the start of a statement (after `;` outside parentheses, `}`, a block `{`, or a previous markup statement) is a markup statement; everywhere else §4 applies as usual. Bodies with markup statements must be blocks (`if (c) { <p/> }`). It lowers to `switch (0) { default -> { ArrayList jasx$childrenN ...; yield ...; } }` (`semantics.md` §6.8), which is why `return` is rejected inside.
- A target's lowering class runs inside the compiler, loaded by `TargetClassLoader`: it sees the project's classpath and the JDK, but `jasx.compiler.*` and `jasx.parser.*` always come from the running compiler (so a target never brings its own copy of the interface). The build tools and the language server must load targets through it and keep it open while compiling; the language server keeps it open only for targets with their own lowering, because an open loader locks jars on Windows.
- On the `web` target, every dynamic child and dynamic attribute is a lambda (`targets/web.md` §3), so the locals it reads must be effectively final, and a statement child can't assign the method's locals: code that compiles on `html` can fail on `web`. Components run once; only holes re-run. A hole that read no `State`/`Derived` on its first run gets no markers or id (§8.2). `State.toString()` is its value, so `{count}` works as a child.
- Event attributes on `web` have three overloads: `String` (inline JavaScript), `jasx.ui.Action` (`() -> ...`) and `jasx.ui.Handler<? super K>` (`e -> ...`), where `K` is the event kind from `jasx.ui.Event`'s table (`ui.md` §4). The kind table is duplicated in `jasx-web/build.gradle.kts` (`eventKinds`); keep both in step. Handlers run with the page as owner and without tracking (`Computation.runWithOwner`).
- `web`'s state can be shared by several pages (a `State` in a store or a static field): sources keep observers in a concurrent set, each page re-runs its holes under its own lock, and a change from another thread schedules a flush whose batch goes to the page's stream.
- Character references are decoded in text and in attribute strings (`title="a&amp;b"` is `a&b`, spec revision 16), as in JSX. Attribute strings also process Java escapes. A bare `&` stays literal in both.

## Environment

- Development machine: Windows 11 (temporary). Shells: PowerShell and Git Bash.
- JDK: Eclipse Temurin 21 at `C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot`. Gradle comes from the wrapper.
- Node.js is available for one-off scripts.
- Git: work on a branch off `main` and **push after every commit**, because the machine is temporary. Remote: `https://github.com/snrraptopack/jasx`.
