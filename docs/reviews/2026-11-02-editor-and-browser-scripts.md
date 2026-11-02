# Project review: editor support and browser scripts, 2026-11-02

JASX is a Java language extension with target-defined rendering. Its core rewrites markup and leaves Java type checking to javac. The interactive `web` target adds server-owned pages, reactive holes and events; plain JavaScript in script blocks and `@event` attributes supplies browser behavior. These are separate layers, and an editor problem can come from a different layer than an application build problem.

This review traced the specification, parser, script analysis and lowering, build metadata, language server, both editor clients, module serving and browser invocation. It investigated the installed IntelliJ plugin directly. It is a focused review, not an exhaustive runtime, security or performance audit.

## How the pieces fit

| Layer | Responsibility | Main implementation |
|---|---|---|
| Specification | Syntax, target capabilities, static/dynamic positions, lowering contracts | `jasx-spec/grammar.md`, `semantics.md`, `targets/web.md`, `ui.md` |
| Parser | Distinguish Java and markup; recognize script blocks and `JasxEventName` attributes | `jasx-parser`, `JasxParser`, AST |
| Compiler | Resolve components, validate target capabilities and browser payloads, generate Java and ES modules, preserve source positions | `jasx-compiler`, `Lowering`, `ScriptReader`, `BrowserAttribute`, `SpanMap` |
| Build integration | Compile generated Java, package generated modules and adjacent `.js` files, describe the project to editors | Gradle/Maven plugins, `BuildSources`, `EditorInfo` |
| Shared UI | State, dependency tracking, computations and typed Java events | `jasx-ui` |
| Web runtime | Own pages and component instances, dispatch events, stream updates and serve modules | `jasx-web`, `WebApp`, `Page`, `ScriptModules`, `web.js` |
| Server adapters | Translate server APIs to `WebApp` | JDK server, Servlet adapter, Spring Boot starter |
| Editor analysis | Analyze source sets with the build's target and classpaths, then answer LSP requests | `jasx-lsp`, `Workspace`, `BuildInfo`, javac analysis |
| Editor clients | Package and launch the server; load the shared TextMate grammar | `editors/intellij`, `editors/vscode` |

The architectural separation should be retained. Reactivity belongs to targets and `jasx-ui`; JavaScript should remain JavaScript. The IntelliJ client does not need a second implementation of parsing or language semantics to fix these errors.

## Confirmed cause of the reported errors

**The installed IntelliJ plugin predates browser-script support.** Its bundled `server/jasx-lsp.jar` contains none of `ScriptReader`, `BrowserAttribute`, `JasxScriptBlock` or `JasxEventName`. IntelliJ's log shows the JASX server running, so this is not a missing LSP registration.

Sending actual LSP messages to that installed jar with the current `examples/web/src/main/java/web/Browser.jasx` reproduces:

- `JASX1006: unexpected '@' in markup` for the browser attributes;
- `JASX2013` top-level constructor/initializer errors around the script and following style block.

The updated server reports an empty initial diagnostic list for the same file. The example's editor metadata exists, names `web`, and supplies the target's classpath. Rebuilding the application alone cannot replace the server bundled in an installed IDE plugin. The plugin and grammar need to be rebuilt, installed, and loaded by an IntelliJ restart.

## Fixes made with this review

1. **Completion registration:** the LSP now advertises `@` and `$` trigger characters. Completion after `@` already existed, but editors were not asked to invoke it automatically for that character.
2. **Build metadata refresh:** the LSP now watches Gradle and Maven editor metadata. Creating, replacing or deleting metadata rechecks the corresponding open source sets, refreshes generated Java and starts a type check when possible. Previously, a first build could leave fallback-target errors visible until another source change.
3. **Regression coverage:** protocol tests cover valid script blocks and browser attributes on `web`, metadata creation/deletion and Maven discovery; the IntelliJ handshake test checks browser completion triggers.
4. **Installation documentation:** the IntelliJ README explains the installed-server lifecycle and distinguishes old-parser errors from `JASX2107`, which means the selected target lacks browser scripts.

## Remaining findings, in priority order

### 1. LSP typing checks accumulate delays

[`JasxLanguageServer.startJavacCheckWhenTypingPauses`](../../jasx-lsp/src/main/java/jasx/lsp/JasxLanguageServer.java) queues a runnable for every document change. Each runnable sleeps before checking whether it is obsolete. [`Main`](../../jasx-lsp/src/main/java/jasx/lsp/Main.java) uses a single-thread executor for those runnables and javac.

A burst of 20 changes can therefore enqueue roughly 10 seconds of sleeps at the production 500 ms setting, even though only the newest change needs checking. The counter is also global: an edit in one source set can suppress the pending check in another. This follows directly from the scheduling code; this review did not benchmark the IDE's resulting latency.

Recommended next change: a cancellable timer per source set, with javac work submitted only when that timer expires. Keep timers separate from the serial compiler worker. Add deterministic tests for a burst of edits and edits in two source sets.

### 2. Default and aliased exports can fail in the browser

[`ScriptReader.topLevelNames`](../../jasx-compiler/src/main/java/jasx/compiler/ScriptReader.java) treats a default export or an aliased export as though the local name were already available as a named export. [`Lowering.module`](../../jasx-compiler/src/main/java/jasx/compiler/Lowering.java) consequently omits the additional export. The browser invokes `module[name]` in [`callBrowserAttribute`](../../jasx-web/src/main/resources/jasx/web/web.js).

Both of these compile without diagnostics when called as `@click="go()"`:

```js
export default function go() {}
```

```js
function go() {}
export { go as other };
```

Inspecting the compiler's generated modules confirms that neither gains `export { go };`. Importing those modules with Node confirms `module.go` is `undefined`; their exported names are `default` and `other`, respectively.

Recommended next change: track whether the exact local name is exported under that same name, independently of default/aliased exports. Add generated-module execution tests. Clarify the meaning of "exported already" in `semantics.md` §11.1/§11.6 with the fix, since the current wording also conflates those cases.

### 3. Target-loading failures are silent

[`Workspace.buildInfoFor`](../../jasx-lsp/src/main/java/jasx/lsp/Workspace.java) catches metadata/target-loading exceptions and silently uses the default `html` target. This is useful while a build is writing a file, but a persistent broken path or incompatible target can look like unsupported language syntax to the user. `JASX2107` then describes the fallback target rather than explaining why the intended target failed to load.

Recommended next change: retain best-effort recovery, but report the metadata path and load failure through `window/logMessage`, without logging on every keystroke. Avoid guessing the target from source text.

### 4. Browser-language tooling remains partial

The existing LSP offers script names and refs in browser attribute strings. It deliberately does not yet offer Java payload names there; a test asserts that limitation. Go-to-definition currently follows component tags, rather than imports into adjacent `.js` files. TextMate highlighting provides JavaScript colors, not JavaScript semantic analysis. These are unfinished features already recorded in `AGENTS.md`, rather than evidence that the LSP is disconnected.

Generated payload declarations and serving JavaScript directly from development source folders also remain unfinished. The latter still needs the agreed source-folder discovery decision. Neither should be advertised as completed runtime behavior.

## Validation

- Parser, compiler, conformance and LSP Gradle tasks passed; unchanged suites were up to date, and the changed LSP tests executed successfully.
- All 21 TextMate grammar tests passed, including script blocks in classes and JavaScript browser attributes.
- An LSP subprocess probe reproduced errors from the installed jar and confirmed the updated jar accepts the same example.
- The export finding was reproduced through compiler-generated modules and Node ES module imports.
- Both IntelliJ headless integration tests passed against the installed IDEA 2026.1.1: the server handshake (including `@`/`$` triggers) and TextMate grammar discovery. The updated plugin zip was built successfully.
- The installed server and grammar were updated after backing up the previous files under `build/diagnostics/intellij-backup-2026-11-02`. Hashes match the tested package. A second subprocess probe against the installed jar reports no initial diagnostics for `Browser.jasx`. The already-running IDE still needs a restart to load those replacements.

The user's existing example edits were preserved. Broader runtime and build-plugin functional suites were not rerun for these LSP-only changes.
