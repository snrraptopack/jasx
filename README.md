# JASX — JSX for Java

JASX lets you write markup directly in Java, the way JSX does for JavaScript:

```java
// app/Todos.jasx
package app;

import jasx.runtime.Element;
import java.util.List;

Element Item(String title, boolean done) {
  return <li class={done ? "done" : "todo"}>{title}</li>;
}

Element Page(List<String> titles) {
  return (
    <ul>
      {titles.stream().map(t -> <Item title={t} done={false} />).toList()}
    </ul>
  );
}
```

A `.jasx` file is ordinary Java plus markup expressions. The JASX compiler rewrites only the markup, into plain method calls, and javac type-checks the result. Components are plain static methods, attributes are checked at compile time, and every Java feature works inside `{...}`.

Methods and fields can be written at the top level of a `.jasx` file, as above. They become static members of a class named after the file (`app.Todos`), so other files use them with `import static app.Todos.*;`, or as `<Todos.Item ... />`. Without an access modifier they have package access, as in Java; declare them `public` to use them from other packages. Files with an ordinary `class` work too.

A `.jasx` file can also hold **style blocks** of plain CSS, scoped to the elements written in that file. Write them at the top, in a component before its `return`, or inside markup next to the elements they style:

```java
<style>
  .card { padding: 1rem; border: 1px solid #ddd; }
</style>

public Element Card(String title) {
  <style>
    h2 { margin: 0; }
  </style>

  return (
    <div class="card">
      <style>
        p { color: gray; }
      </style>
      <h2>{title}</h2>
      <p>A card.</p>
    </div>
  );
}
```

The compiler adds a file-specific class to every selector and to every element of the file, so `.card` here can't affect another file's `.card`. All style blocks of a file form one style sheet, and a style block inside markup renders nothing where it is written, so the CSS is sent once per page however often a component is used. Mistakes in the CSS are compile errors. With the `html` target, put `{Html.styles()}` in your page's `<head>`: it outputs the CSS of every style sheet used on the page. (`<style>{css}</style>`, with an expression, is still an ordinary `<style>` element.)

Like JSX, JASX is **a language, not a framework**. What markup turns into is decided by a **target** library. The current focus is the core language. It ships with one small reference target, `html`, which renders markup to an HTML string. Other targets are not decided yet.

## Status

Early development. The specification is a draft.

- [`jasx-spec/`](jasx-spec/README.md): the language specification and its conformance tests.
- Parser, compiler and `html` target: implemented, and passing the whole conformance suite.
- Gradle plugin, annotation processor, VS Code highlighting and a language server (errors while typing, completion, go-to-definition and hover): implemented.
- Not yet: artifacts in a public repository; Java type errors in the editor (they come from the build).

## Try the example

```
./gradlew -p examples/hello run
```

This builds [`examples/hello`](examples/hello) (a small to-do page written in `.jasx`) and serves it at http://localhost:8080. Add `--args=--print` to print the HTML instead.

## Using JASX in a Gradle project

Apply the plugin and add a target:

```kotlin
plugins {
    java
    id("dev.jasx")
}

dependencies {
    implementation("dev.jasx:jasx-target-html:0.1.0-SNAPSHOT")   // the html reference target
}
```

Put `.jasx` files next to your `.java` files (`src/main/java`) or in `src/main/jasx`. The `compileJasx` task turns them into Java before `compileJava` runs. JASX errors, and javac errors in code that came from markup, are reported once, with their `.jasx` file, line and column. To do that, `compileJasx` type-checks the generated Java with javac before `compileJava` runs (with the same classpath, annotation processors and Java release). This compiles the source set a second time; if that is too slow, `jasx { typeCheck.set(false) }` turns it off, and javac's errors then point at the generated files, on the same line numbers.

The artifacts are not in a public repository yet. Run `./gradlew publishToMavenLocal` in this repository, and add `mavenLocal()` to your project's `repositories` and to `pluginManagement.repositories` in its settings.

For libraries, add `jasx-processor` as an annotation processor. It writes `META-INF/jasx/components.json` into your jar, so other projects can use your components from markup.

## Editor support

[`editors/vscode`](editors/vscode/README.md) is a VS Code extension: it highlights `.jasx` files (CSS in style blocks too), and runs the JASX language server ([`jasx-lsp`](jasx-lsp)), which shows JASX errors while you type, completes tag names and props, and offers go-to-definition and hover on components.

## Building

Requires JDK 21.

```
./gradlew build
./gradlew -q :jasx-compiler:run --args="../jasx-spec/tests/render/005-components-lists.jasx"
```

The second command prints the Java generated for a sample file.

## Repository

| Path | Contents |
|---|---|
| `jasx-spec/` | Specification (grammar, AST, semantics, diagnostics) and conformance tests |
| `jasx-parser/` | Parser: finds and parses markup in Java source |
| `jasx-compiler/` | Compiler: resolves components and lowers markup to Java |
| `jasx-runtime/` | `Element` and the component annotations |
| `jasx-target-html/` | The `html` reference target |
| `jasx-processor/` | Annotation processor that publishes a library's components |
| `jasx-gradle-plugin/` | Gradle plugin `dev.jasx` |
| `jasx-conformance/` | Runs the conformance tests |
| `jasx-lsp/` | Language server: JASX errors while typing, completion, go-to-definition and hover |
| `editors/vscode/` | VS Code extension (highlighting, language server client) |
| `JASX-update1.md` | The original design proposal (historical) |

Contributors and coding agents: see [`AGENTS.md`](AGENTS.md).
