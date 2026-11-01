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

Children can also come from Java statements: a `{...}` child that starts with `if`, `for`, `while`, `do` or a block runs that statement, and every piece of markup written at the start of a statement inside it adds a child:

```java
<ul>
  {for (Todo t : todos) {
    <li>{t.title()}</li>
  }}
  {if (todos.isEmpty()) {
    <p>Nothing to do</p>
  } else {
    int done = countDone(todos);
    <p>{done} of {todos.size()} done</p>
  }}
</ul>
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

The compiler adds a file-specific class to every selector, and to the elements of the file that a selector may match, so `.card` here can't affect another file's `.card`. All style blocks of a file form one style sheet, and a style block inside markup renders nothing where it is written, so the CSS is sent once per page however often a component is used. Mistakes in the CSS are compile errors. With the `html` target, put `{Html.styles()}` in your page's `<head>`: it outputs the CSS of every style sheet used on the page. (`<style>{css}</style>`, with an expression, is still an ordinary `<style>` element.)

Like JSX, JASX is **a language, not a framework**. What markup turns into is decided by a **target** library. Two targets come with it:

- `html`, a small reference target that renders markup to an HTML string;
- `web`, for interactive pages whose code runs on the server ([below](#interactive-pages-the-web-target)).

## Interactive pages: the `web` target

With the `web` target, a page is rendered on the server, and then stays alive there while it is open in the browser, like Phoenix LiveView. Event handlers are Java lambdas that run on the server, and when they change state, the browser receives only the places that changed. The whole application is one Java program: handlers call your services and database directly, with no REST API and no JavaScript.

```java
import static jasx.web.Web.*;

public Element Counter() {
    var count = state(0);
    var doubled = derived(() -> count.get() * 2);
    return (
        <div>
            <button onclick={() -> count.update(n -> n + 1)}>+1</button>
            <p>{count} × 2 = {doubled}</p>
        </div>
    );
}
```

- **Components run once.** Each `{...}` in markup is a *hole* that runs again when a state it reads changes. Only its new content is sent.
- **Typed events.** Each event attribute gives its handler the right kind of event: `onsubmit` a `FormEvent` (`e.field("title")`, or `e.as(NewTodo.class)` to read the fields into a record), `onkeydown` a `KeyEvent` (`e.key()`), `onclick` a `MouseEvent`. A handler that needs no event is `() -> ...`.
- **Typing stays in the browser.**
  - A form sends its fields once, on submit.
  - `oninput` is sent 300 ms after the user stops typing (`debounce(ms, ...)` and `immediate(...)` change that).
  - A field being typed in is never overwritten by the server.
- **State set from anywhere is pushed.** A scheduled job, a message listener, or another user's action updates every open page that shows it, over server-sent events.
- **Plain HTTP.** Events are POST requests and updates come back as server-sent events. There's no WebSocket library, so it runs on the JDK's own HTTP server (`WebServer`), on any servlet container (`jasx-web-servlet`), in Spring Boot (`jasx-web-spring-boot-starter`), or on any server through `WebApp`. The tabs of a browser share one stream.
- **Safe by default.** Each page answers only the browser that rendered it, events from other sites are refused, and requests have size limits, whatever server you use.

```kotlin
jasx { target.set("web") }
dependencies { implementation("dev.jasx:jasx-web:0.1.0-SNAPSHOT") }
```

**[The guide to the `web` target](docs/web.md)** explains state, events, forms, lists and server push with examples.

State and events come from `jasx-ui` ([`ui.md`](jasx-spec/ui.md)), a small library shared by interactive targets, so a future desktop target can offer the same API. The target is a draft: [`targets/web.md`](jasx-spec/targets/web.md) specifies it, including what's not there yet (static templates and uploads).

## Status

Early development. The specification is a draft.

- [`jasx-spec/`](jasx-spec/README.md): the language specification and its conformance tests.
- Parser, compiler and `html` target: implemented, and passing the whole conformance suite.
- Gradle plugin, Maven plugin, annotation processor, VS Code highlighting and a language server (errors while typing, completion, go-to-definition and hover): implemented.
- Targets with their own lowering, so a third-party target decides what markup becomes (see [Writing a target](#writing-a-target)): implemented.
- The `web` target: a first version, with a server on the JDK's HTTP server and an adapter for servlet containers.
- A Spring Boot starter for `web`: pages are controller methods (`jasx-web-spring-boot-starter`).
- Not yet: artifacts in a public repository.

## Try the examples

```
./gradlew -p examples/hello run
./gradlew -p examples/web run
./gradlew -p examples/web-spring run
```

[`examples/hello`](examples/hello) is a small to-do page rendered to HTML with the `html` target; add `--args=--print` to print the HTML instead.

[`examples/web`](examples/web) is interactive, with the `web` target. It has four pages:
- a counter;
- a to-do list shared by every open window;
- a live search;
- a page with a server clock and a chat, pushed from the server.

[`examples/web-spring`](examples/web-spring) is the `web` target in Spring Boot: a team board whose pages are controller methods, whose tasks live in a `@Service`, and whose REST API updates every open board (`curl -X POST localhost:8080/api/tasks -d title="Ship it"`).

Each serves at http://localhost:8080.

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

Compiled classes name their `.jasx` file, so stack traces read `at app.Pages.Home(Pages.jasx:12)`, with the line number of the `.jasx` source. The plugin works with Gradle's configuration cache and in multi-project builds.

The artifacts are not in a public repository yet. Run `./gradlew publishToMavenLocal` in this repository, and add `mavenLocal()` to your project's `repositories` and to `pluginManagement.repositories` in its settings.

For libraries, add `jasx-processor` as an annotation processor. It writes `META-INF/jasx/components.json` into your jar, so other projects can use your components from markup.

## Using JASX in a Maven project

Add a target, and the plugin with its two goals:

```xml
<dependencies>
  <dependency>
    <groupId>dev.jasx</groupId>
    <artifactId>jasx-target-html</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>dev.jasx</groupId>
      <artifactId>jasx-maven-plugin</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <executions>
        <execution>
          <goals>
            <goal>compile</goal>        <!-- .jasx to Java, before the Java compiler -->
            <goal>source-names</goal>   <!-- stack traces name the .jasx files -->
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

It works as the Gradle plugin does: `.jasx` files go in `src/main/java` or `src/main/jasx`, errors are reported at their `.jasx` locations, javac's errors in markup once, and the target is set with the property `jasx.target` (`jasx.typeCheck=false` turns the type check off). For `.jasx` files in tests, add the goals `test-compile` and `test-source-names`. [`examples/hello-maven`](examples/hello-maven) is a complete project; until the artifacts are in a public repository, run `./gradlew publishToMavenLocal` here first.

## Writing a target

A target is an ordinary library (`semantics.md` §7). It contains:

- a descriptor, `META-INF/jasx/target.json`, which gives its `id` (what a build selects with `jasx { target.set("id") }` or `jasx.target`);
- a schema of its elements and attributes;
- a factory class and attribute builders.

That is enough for factory lowering: markup becomes `F.h(...)` calls on your factory.

For more control, a target can name its own lowering class in the descriptor (`"lowering": "com.acme.AcmeLowering"`, `semantics.md` §7.4). It runs inside the compiler, so the target compiles it against `jasx-compiler` as a `compileOnly` dependency. The compiler first analyses the markup and reports every error. It then hands the lowering a tree of each markup expression: normalized children, attributes classified as static, dynamic or handler, and component calls. The lowering decides what elements, fragments, keys and children become.

The user's own Java code reaches the lowering only as pieces it places, exactly once, but can't change. So line numbers, error locations and stack traces keep working with every target. The easiest start is to extend `FactoryLowering` and override only what you need. For example, this passes every dynamic child to the runtime as a function:

```java
public class AcmeLowering extends FactoryLowering {
    public AcmeLowering(Target target) { super(target); }

    @Override
    protected void dynamicChild(MarkupTree.Dynamic child, LoweringOutput out) {
        out.write("com.acme.Acme.dynamic(() -> (", child.start());
        out.code(child.expression());
        out.write("))", child.end() - 1);
    }
}
```

Implement `MarkupLowering` directly to generate something else entirely. See the Javadoc of `MarkupLowering`, `MarkupTree` and `LoweringOutput`.

## Editor support

[`editors/vscode`](editors/vscode/README.md) is a VS Code extension: it highlights `.jasx` files (CSS in style blocks too), and runs the JASX language server ([`jasx-lsp`](jasx-lsp)), which shows JASX errors while you type, completes tag names and props in markup and Java members and names in Java code (also inside `{...}`, where a handler knows its kind of event), and offers go-to-definition and hover on components. Once the project has been built with the Gradle or Maven plugin, it also knows the components of your dependencies and shows javac's errors in `.jasx` files when you save.

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
| `jasx-compiler/` | Compiler: resolves components and lowers markup to Java; the interface for a target's own lowering |
| `jasx-runtime/` | `Element` and the component annotations |
| `jasx-target-html/` | The `html` reference target |
| `jasx-ui/` | Shared runtime of interactive targets: reactive state and typed events |
| `jasx-web/` | The `web` target: runtime, lowering, browser client, JDK server |
| `jasx-web-servlet/` | The `web` target on servlet containers (Tomcat, Jetty, Undertow) |
| `jasx-web-spring-boot-starter/` | The `web` target in Spring Boot: pages as controller methods |
| `docs/` | Guides: [the `web` target](docs/web.md) |
| `jasx-processor/` | Annotation processor that publishes a library's components |
| `jasx-gradle-plugin/` | Gradle plugin `dev.jasx` |
| `jasx-maven-plugin/` | Maven plugin `dev.jasx:jasx-maven-plugin` |
| `jasx-conformance/` | Runs the conformance tests |
| `jasx-lsp/` | Language server: JASX errors while typing, completion, go-to-definition and hover |
| `editors/vscode/` | VS Code extension (highlighting, language server client) |
| `JASX-update1.md` | The original design proposal (historical) |

Contributors and coding agents: see [`AGENTS.md`](AGENTS.md).
