> **Historical document.** This is the original design proposal. The normative specification is [`jasx-spec/`](jasx-spec/README.md), which supersedes this document wherever they differ (for example: components are called directly instead of through props builders, and targets are libraries plugged into a target-neutral core). See [`jasx-spec/CHANGELOG.md`](jasx-spec/CHANGELOG.md).

# JASX — JSX for Java

**Status:** Design proposal — **Update 1**
**Author:** Amihere Theophilus Junior

---

## Update 1 — Changelog

This revision incorporates an external design review. Summary of changes from the original draft:

| # | Area | Change |
|---|---|---|
| 1 | Reactivity | **Corrected claim.** The compiler does *not* know statically which state each slot reads. The compiler identifies **reactive boundaries**, and the runtime tracks which state they read. (§5) |
| 2 | Reactivity | Added explicit primitives `state`, `computed`, `effect`. Code in the component body outside markup runs **once** and is not reactive. A compiler warning flags state reads outside a boundary. (§5.2–5.3) |
| 3 | Control flow | Reactive conditionals must be **inside markup** (ternary / `when`). An early `if`/`return` on state in the component body is not reactive. (§5.4) |
| 4 | Lists | Added `key` attribute and keyed reconciliation, which also preserves the state of nested components. (§5.5) |
| 5 | Attributes | **Fixed contradiction.** `attrs(Object...)` cannot type lambdas. Lowercase tags now lower to **typed attribute builders** generated from an HTML schema. (§3.3) |
| 6 | Components | Java has no named arguments, and parameter names aren't reliably in class files. Components now get a **generated props builder** (annotation processor), so attribute order doesn't matter and components from jars work. (§3.2) |
| 7 | Compiler | Added a **markup IR** between parsing and codegen. Each target has its own lowering. (§4.2) |
| 8 | Parser | Clarified why markup detection is exact (no Java expression starts with `<`). Added a dedicated markup parser with error recovery. (§4.1) |
| 9 | Source mapping | **Corrected claim.** SMAP handles debugger stepping only. Compiler diagnostics, debugger mapping and runtime stack traces are now separate requirements. (§4.4) |
| 10 | Targets | Replaced "same code, any target" with **shared syntax plus target-specific capabilities**, checked at compile time. (§6) |
| 11 | Positioning | Added a comparison with JTE and Vaadin Flow. The centerpiece is the compiled reactive model of the Live target, not the syntax alone. (§1.1) |
| 12 | Roadmap | Phase 0 expanded to include typed attributes, the props builder and reactive semantics. (§7) |

---

## 1. What is JASX?

JASX is Java with JSX-style markup. You write `.jasx` files that mix normal Java with HTML-like tags. A compiler turns the markup into plain Java, and a **target** decides what that Java produces: server-rendered HTML, a live server-driven UI, and later JavaFX.

JASX is a **language extension first**. It has three layers:

| Layer | Responsibility |
|---|---|
| JASX language | JSX-like markup embedded in Java as expressions |
| JASX compiler | Converts markup into valid, type-checkable Java |
| JASX targets | Decide how that Java becomes a real interface |

### Why

- Java UI code is either verbose imperative construction (JavaFX, Swing) or string templates with their own expression language (Thymeleaf, JSP, FreeMarker).
- Visual builders like Scene Builder exist largely because the code APIs are unpleasant to write.
- JSX showed that markup as a first-class expression makes UI code declarative and composable, using the host language's full power.

### Goals

- Plain HTML tags and real CSS. No framework types like `VBox` or `Node` in user code.
- Full Java everywhere: records, sealed types, streams, switch expressions, lambdas.
- Components are ordinary Java methods.
- Compile-time type checking of components and attributes.
- **JASX adds markup expressions and does not change how Java behaves.**

### Non-goals (for now)

- Changing Java semantics (e.g. JS-style `&&`).
- Whole-program dataflow analysis of Java code.
- Client-side Java execution (see "Client islands" in Phase 4).

### 1.1 Positioning

| Technology | Primary model |
|---|---|
| Thymeleaf | HTML templates with server-side expressions |
| JTE | Compiled, type-safe Java templates (separate template files, IntelliJ plugin) |
| Vaadin Flow | Server-side imperative Java component tree, synchronized to the browser |
| **JASX HTML** | Markup as expressions directly inside Java, with components as Java methods |
| **JASX Live** | Compiled reactive boundaries with server-side execution and fine-grained patches |
| **JASX JavaFX** | Declarative markup targeting desktop nodes |

JASX is **not** the first compile-time-checked Java template system (JTE already is), and it is **not** the first server-driven Java UI (Vaadin already is). What makes it different is markup as Java expressions combined with a **compiled reactive execution model** for the Live target.

---

## 2. Developer experience

### A component

```java
// TodoPage.jasx
record Todo(int id, String title, boolean done) {}

public class TodoPage {

  static Element TodoItem(Todo todo, Consumer<Integer> onToggle) {
    return (
      <li class={todo.done() ? "item done" : "item"}>
        <input type="checkbox" checked={todo.done()}
               onChange={e -> onToggle.accept(todo.id())} />
        <span>{todo.title()}</span>
      </li>
    );
  }

  static Element TodoPage(TodoService service) {
    var todos = state(service.all());
    var draft = state("");
    var doneCount = computed(() -> todos.get().stream().filter(Todo::done).count());

    return (
      <main class="todos">
        <form onSubmit={e -> {
          service.create(draft.get());
          todos.set(service.all());
          draft.set("");
        }}>
          <input value={draft.get()} onInput={e -> draft.set(e.value())} />
          <button type="submit">Add</button>
        </form>

        <ul>
          {todos.get().stream()
              .map(t -> <TodoItem key={t.id()} todo={t} onToggle={id -> {
                service.toggle(id);
                todos.set(service.all());
              }} />)
              .toList()}
        </ul>

        <p>{doneCount.get()} done</p>

        {todos.get().isEmpty()
            ? <p class="empty">Nothing here</p>
            : null}
      </main>
    );
  }
}
```

```css
/* todos.css — real CSS */
.todos { display: flex; flex-direction: column; gap: 8px; padding: 16px; }
.item.done span { text-decoration: line-through; opacity: 0.6; }
```

### Wiring it up (plain Java)

```java
// App.java
Jasx.live(app, "/", () -> TodoPage.TodoPage(service));
```

### Build (illustrative)

```kotlin
// build.gradle.kts
plugins { id("dev.jasx") version "0.1.0" }
jasx { target = "html-live" }
```

### Full Java inside and around markup

```java
// switch expressions + pattern matching
{switch (filter.get()) {
  case All a    -> <span>Showing all</span>;
  case Active a -> <span>Showing active</span>;
  case Done d   -> <span>Showing completed</span>;
}}

// lists via streams, with keys
<ul>{items.get().stream().map(i -> <li key={i.id()}>{i.name()}</li>).toList()}</ul>

// block lambdas for handlers
<button onClick={e -> {
  var n = todos.get().size();
  log.info("count: " + n);
}}>Log</button>
```

---

## 3. Language rules

### 3.1 General rules

| Rule | Decision |
|---|---|
| File extension | `.jasx`. Compiled to `.java` in a generated sources folder. Mixes freely with `.java`. A `.jasx` file with no markup is valid Java. |
| Lowercase tags | HTML elements, lowered to typed attribute builders (§3.3). |
| Capitalized tags | Component references, lowered through generated props builders (§3.2). |
| Expressions | `{...}` holds any Java expression. Markup may nest inside it, recursively. |
| Conditionals | Ternary (`cond ? <a/> : null`) or `when(cond, () -> <a/>)`. **No JSX-style `&&`.** |
| Lists | `List<Element>` children are flattened. Use `key={...}` for identity (§5.5). |
| Whitespace | JSX rules: whitespace between tags is trimmed, and whitespace inside text runs is kept. |
| Casts on markup | `(Element) <div/>` is not supported (§4.1). |
| Why not `.java` | javac rejects markup, and IDEs would show markup as errors. |
| Why not `.jsf` | It collides with JavaServer Faces. |

### 3.2 Components and props

Java has no named arguments, and parameter names are only in class files when code is compiled with `-parameters`. So JASX doesn't map attributes to parameters by position.

Instead, an **annotation processor generates a props builder for every component**:

```java
// User writes (in .jasx, or in .java with @Component)
static Element UserCard(String name, int age, boolean verified) { ... }
```

```java
<UserCard verified={true} name={"Theo"} age={21} />
```

lowers to:

```java
UserCard(UserCardProps.builder().verified(true).name("Theo").age(21).build())
```

- **Attribute order doesn't matter.** Each attribute is a named builder method.
- **javac types the lambdas.** `onToggle(Consumer<Integer>)` is the target type, so no casts are needed.
- **Components from jars work**, because the builder is compiled into the jar.
- **Missing required props** fail in `build()`, and a later version can enforce them at compile time with a staged builder.
- javac allows generated code to reference types that an annotation processor creates in the same compilation.
- Methods in `.jasx` files get `@Component` added automatically. In plain `.java` files, developers annotate component methods themselves.

### 3.3 Typed attributes for HTML tags

`attrs(Object...)` can't work, because a Java lambda needs a functional-interface target type. Lowercase tags lower to **typed attribute builders** generated from an HTML element/attribute schema:

```java
<button disabled={loading.get()} onClick={e -> save()}>Save</button>
```

lowers to (conceptually):

```java
h("button",
  ButtonAttrs.create()
      .disabled(loading.get())
      .onClick(e -> save()),
  "Save")
```

where `ButtonAttrs onClick(Consumer<ClickEvent> handler)`.

- Unknown attributes are compile errors.
- **Extension points:** `data-*`, `aria-*`, and custom attributes such as `hx-post` go through a typed `attr(String, String)` escape hatch or target-provided schema extensions.
- The schema is part of the target (§6), so the HTML target can reject `onClick` lambdas.

### 3.4 Desugaring summary

```java
<div class="card">
  <p>Count: {count.get()}</p>
  <TodoItem key={t.id()} todo={t} onToggle={toggle} />
</div>
```

lowers (on the HTML target) to roughly:

```java
h("div", DivAttrs.create().className("card"),
  h("p", null, "Count: ", count.get()),
  keyed(t.id(), TodoItem(TodoItemProps.builder().todo(t).onToggle(toggle).build()))
)
```

On the Live target, the same markup lowers differently (§4.2): a static skeleton plus slots.

---

## 4. Compiler design

### 4.1 Parsing

The parser has two parts:

1. **Java-aware tokenizer.** It skips strings, chars, text blocks and comments, counts braces, and identifies **expression-start positions**.
2. **Dedicated markup parser.** It handles elements, attributes, fragments, nested `{...}` expressions and closing tags.

**Why detection is exact, not heuristic:** no valid Java expression begins with `<`. If the tokenizer correctly identifies expression-start positions, `<` there can only be markup. Expression-start positions are after `return`, `(`, `,`, `=`, `->`, `?`, `:`, `{`, `&&`, `||`, never after an identifier, `)`, `]` or `.`. The only casualty is a cast applied directly to markup (`(Element) <div/>`), which is disallowed.

**Error recovery.** The markup parser reports JASX-level errors (mismatched tags, unclosed `{`, invalid attribute syntax) with `.jasx` locations, *before* any Java is generated. javac should never be the first to see a markup mistake.

```
TodoPage.jasx:12:16: error: closing tag </div> does not match <p> opened at 12:9
```

No full Java parser is needed. javac type-checks the generated code.

### 4.2 Markup IR

Parsing produces a **markup IR** describing only the markup constructs, not the whole Java program:

```
Element(div)
  Attributes: class = "counter"
  Children:
    Element(h1) Text("Counter")            ← static
    Element(p)
      Boundary: count.get()                ← reactive boundary
    Element(button)
      Event: click = <lambda>
```

Each IR node keeps its **source location** and the original Java expression text, so the generated code stays valid, type-checkable and mappable.

```
.jasx → Tokenizer + Markup parser → Markup IR
                                       │
                         ┌─────────────┼─────────────┐
                         ▼             ▼             ▼
                   HTML lowering  Live lowering  JavaFX lowering
                         │             │             │
                         ▼             ▼             ▼
                   Java source    Java source    Java source
                         └──────────► javac ◄───────┘
```

- **HTML lowering:** element construction or streaming output.
- **Live lowering:** a **static HTML skeleton** plus independently executable **slots** and **event registrations**. For the `div` above, that's one skeleton and one slot, instead of rebuilding the whole tree.

### 4.3 Codegen

- Rewrite only the markup spans. Leave the rest of the file byte-for-byte unchanged.
- **Keep generated code on the same lines as the source** wherever possible. This is the main mechanism that makes errors and stack traces line up.

### 4.4 Source mapping (three separate requirements)

| Requirement | Mechanism |
|---|---|
| **Compiler diagnostics** | Capture javac diagnostics on generated files and remap positions to `.jasx` using the compiler's own span map. |
| **Debugger stepping/breakpoints** | Install a JSR-45 SMAP in the class file's `SourceDebugExtension` attribute, with a `JASX` stratum. JDI-based debuggers can use it. |
| **Runtime stack traces** | `Throwable` uses only `SourceFile` and line numbers, not SMAP. Rely on line preservation (§4.3), plus an optional runtime remapper for lines that couldn't be preserved. |

### 4.5 Implementation language

The transpiler is written in **Java or Kotlin**, so that:
- the Gradle and Maven plugins call it directly, with no native binaries to ship for each OS, and
- the IntelliJ plugin reuses the same parser for highlighting and errors.

---

## 5. Reactive model (Live target)

### 5.1 Principle

> **Compile time finds reactive boundaries. Runtime discovers dependencies.**

The compiler can't know statically which state an arbitrary Java expression reads (method calls, interfaces, code in jars). So:

- **Compile time:** every `{...}` in markup, and every reactive attribute value, becomes a **boundary**, i.e. an independently executable slot.
- **Runtime:** when a slot runs, it records which `state`/`computed` values it reads, and it subscribes to them.

Later versions may skip runtime tracking where the dependencies are provably known (an optimization, not a requirement).

### 5.2 Primitives

```java
var count   = state(0);                          // mutable reactive value
var doubled = computed(() -> count.get() * 2);   // derived, cached, tracked
effect(() -> log.info("count = " + count.get())); // side effect on change
```

### 5.3 Component body runs once

The component method runs **once per instance**. Code in the body outside markup is **not reactive**:

```java
var count = state(0);
var doubled = count.get() * 2;     // ✗ evaluated once, never updates
return <p>{doubled}</p>;
```

Correct:

```java
var count = state(0);
var doubled = computed(() -> count.get() * 2);   // ✓
return <p>{doubled.get()}</p>;
// or simply: <p>{count.get() * 2}</p>
```

**Compiler warning:** a `.get()` on a `state`/`computed` in the component body, outside a boundary, `computed`, `effect` or event handler, produces:

```
TodoPage.jasx:8:19: warning: state read outside a reactive boundary is evaluated once.
  Use computed(...) or move it into markup.
```

### 5.4 Conditional rendering

Reactive control flow must be **inside markup**:

```java
// ✗ not reactive — runs once
if (loggedIn.get()) return <Dashboard />;
return <Login />;

// ✓ reactive boundary
return (
  <main>
    {loggedIn.get() ? <Dashboard /> : <Login />}
  </main>
);
```

This keeps Java's normal behavior outside markup and makes reactive boundaries explicit. Making ordinary Java control flow reactive would require transforming whole methods, and that is out of scope.

### 5.5 Lists and keys

> **Dependency tracking tells you what to reconsider. Reconciliation tells you what to change.**

```java
<ul>
  {todos.get().stream()
      .map(t -> <TodoItem key={t.id()} todo={t} />)
      .toList()}
</ul>
```

- A list boundary re-runs when `todos` changes. The runtime then **reconciles by key**: it keeps, moves, inserts or removes children, and only patches what differs.
- Keys preserve **component instances and their local state** across list updates.
- A list without keys falls back to index-based reconciliation, with a compiler warning.

### 5.6 Update cycle

When `count.set(...)` runs:

1. The state invalidates its subscribed slots.
2. The scheduler batches the updates (once per event).
3. Each affected slot re-runs.
4. The runtime compares the new result with the previous one.
5. Only the necessary DOM patches are sent to the browser.

---

## 6. Targets

JASX promises **shared syntax and a shared component model, with target-specific capabilities**. It does not promise that the same code runs unchanged on every target. Each target supplies an attribute/event schema, and **unsupported features are compile errors**.

| Feature | HTML | Live | JavaFX |
|---|---|---|---|
| Static markup, components | ✓ | ✓ | ✓ |
| `onClick={lambda}` | ✗ compile error | ✓ server-side | ✓ local |
| `state` / `computed` | ✗ (render-time values only) | ✓ | ✓ |
| `hx-*`, `data-*` attributes | ✓ | ✓ | ✗ |
| Web CSS | ✓ | ✓ | Partial (translated subset) |

### 6.1 HTML (server-rendered) — first target

- Produces HTML, used with any Java web framework (Spring, Javalin, etc.).
- Interactivity comes from attributes (`hx-post` for HTMX, etc.), not Java lambdas.
- **Compared with Thymeleaf/JSP:** templates are type-checked at compile time, components are real Java methods instead of fragments, and you write full Java instead of an expression language.

> Thymeleaf, JSP, FreeMarker and JTE are server-side templates with **no event handling**; interactivity always comes from JS, HTMX or Alpine.

### 6.2 Live (server-driven) — main target

The same family as Phoenix LiveView, Laravel Livewire and Datastar, but built on JASX's compiled reactive model (§5).

**How it works**
1. The first request renders full HTML over HTTP, giving fast first paint and SEO.
2. The browser opens a connection, and the server holds component state for that session.
3. Events are sent to the server, which runs the **Java lambda handler**.
4. Affected slots re-run (§5.6), and a small JS client applies the patches.

**What's different**
- The compiler produces the static skeleton and the slot boundaries, so the static HTML is sent once and only slot output changes.
- Handler and element IDs are compiler-generated, so there are no routes and no hand-written JS.

**Where it works well:** dashboards, admin panels, internal tools, forms, CRUD, real-time feeds and presence, large business apps.

**Known limits of the server-driven model**
- Latency: every interaction is a round trip.
- High-frequency interactions (drag-and-drop, canvas, rich text editing, 60fps animation).
- No offline support.
- Server memory per connected user.
- State is lost on reconnect or restart unless it's persisted.
- Scaling needs sticky sessions or shared state.

These are addressed by **client islands** (Phase 4).

### 6.3 JavaFX — later

- HTML tags map to JavaFX nodes. A **subset** of web CSS is translated to JavaFX CSS (`color` → `-fx-text-fill`, `background` → `-fx-background-color`).
- **Layout:** every `div` maps to one custom `Region` backed by the **Yoga** flexbox engine, so flexbox is honored. This covers flexbox only, not the full web CSS layout model.
- CSS properties with no JavaFX equivalent are **compile errors**.
- Form elements map to `TextField`, `ComboBox` and `TextArea` while keeping HTML semantics (`value`, `onInput`).

---

## 7. Roadmap

| Phase | Scope | Estimate |
|---|---|---|
| **0. Spec** | Grammar; markup IR; **typed attribute schema and builders**; **component props builder (annotation processor)**; **reactive semantics** (boundaries, `state`/`computed`/`effect`, body-runs-once, keys); target capability model | 2–3 weeks |
| **1. Compiler MVP** | Tokenizer + markup parser with error recovery, IR, HTML lowering, line-preserving codegen, diagnostic remapping, Gradle plugin, TextMate grammar | 5–7 weeks |
| **2. Live target** | Live lowering (skeleton + slots), runtime dependency tracking, scheduler, keyed reconciliation, handler IDs, transport, JS client, patch protocol, session state, reconnection, SMAP | 2–3 months |
| **3. Tooling** | LSP (proxying `{...}` regions to jdtls through the span map), then an IntelliJ plugin. Runs alongside Phase 2. | 2–4 months |
| **4. Later** | Client islands, JavaFX target, Maven plugin, static dependency optimization | Open |

**First milestone:** a `.jasx` file compiles and serves HTML, with typed attributes and components.
**Main milestone:** the Live target with compiled boundaries and fine-grained patches.

### Main risks
1. **Editor support.** Without IntelliJ support (highlighting, completion and errors in `.jasx`), adoption stalls. This is the largest effort.
2. **Reactive semantics.** The body-runs-once rule is a known trap (the same one Solid has). Warnings and documentation must make it hard to get wrong.
3. **Typed attributes and props builders.** If the design is wrong, it's painful to change later. It must make lambda typing invisible to users.
4. **Error quality.** Markup errors must come from JASX, and javac errors must map back cleanly.
5. **JavaFX CSS translation** (Phase 4): the long tail of CSS edge cases.

---

## 8. Open questions

- Exact `h` / `Element` runtime contract, and how much of it is shared across targets versus defined by the IR lowering.
- HTML schema source for typed attributes: hand-written, or generated from a spec (e.g. the HTML element/attribute definitions)?
- Required props: runtime check in `build()`, or a staged builder that enforces them at compile time?
- Generic components (`<List<T> items={...} />`): syntax and inference.
- Live target transport: WebSocket only, or SSE + POST as a fallback?
- State persistence strategy for reconnection and horizontal scaling.
- Lifetime of component-local state in conditionals (is state destroyed when a branch hides, or kept?).
- Client islands: written in JS, or a Java subset compiled to JS (comparable to GWT or TeaVM)?
