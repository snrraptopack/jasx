# Discussion: plain JavaScript in the browser

**Status:** phase 2's core is implemented as spec revision 28 (`semantics.md` §11, `targets/web.md` §7.1); the user said go on 2026-11-02. One deviation from this document: refs resolve to the **nearest** element with that ref (`targets/web.md` §7.1), not per component instance (§3.6 below), because component children are evaluated inside the child's instance since revision 25, so instance scoping can't be told apart at run time, and scope attributes on roots break fragments. Not done yet: highlighting and the language server, generated payload types, `TestPage` calling payload handlers, serving `.js` from source in development, and phase 3.

**Dates:** 2026-11-01, revised 2026-11-02.

**Scope:** the `web` target. Other targets reject script blocks and `@` attributes until they support them (§10).

## 1. Goal

To do small things in the browser without a round trip to the server: open a menu, switch a tab, copy to the clipboard, preview a colour, draw a chart. The code is plain JavaScript, in `.js` files with full editor support, connected to `.jasx` files by a `<script>` block, or written inline for one line.

The server stays in charge of its state. This is not a JavaScript framework: there is no client state system, no Java-to-JavaScript compiler, and nothing to learn beyond one shape of function.

**Before reaching for JavaScript:** much of this needs none. A menu can be a `popover` (`<button popovertarget="menu">` and `<nav id="menu" popover="auto">`), a collapsible section a `<details>`; the browser does them itself, and server updates leave them as the user set them (§7).

## 2. The idea in one example

```js
// src/main/jasx/web/menu.js: an ordinary ES module
export function toggleMenu(event, { menu }) {
  menu.hidden = !menu.hidden;
}
```

```java
// src/main/jasx/web/Menu.jasx
<script>
  import { toggleMenu } from "./menu.js";
</script>

public Element Menu() {
    return (
        <div>
            <button @click="toggleMenu({menu: $menu})">Menu</button>
            <nav ref="menu" hidden>...</nav>
        </div>
    );
}
```

- **`on...` is Java, `@...` is JavaScript.** `onclick={...}` is a Java handler, run on the server. `@click="..."` is JavaScript, run in the browser; nothing is sent to the server.
- `@click="toggleMenu(...)"` calls `toggleMenu` from this file's script. Every function called this way has the same shape: **`(event, payload)`**.
- In the payload, `$menu` is a **ref**: the element with `ref="menu"` in the same component. Any other name is a **Java variable**.

## 3. Rules

### 3.1 `@` attributes

An attribute named `@` followed by an event name (`@click`, `@input`, `@keydown`, `@dragover`, ...) holds JavaScript for that event, in a string literal. Its value is one of two things:

- **A call**, `name()` or `name(payload)`, where `name` is a function of the file's script (§4): imported or declared at its top level. The function receives the event and the payload. A bare `name(...)` that isn't a function of the script is a compile error at that attribute, so a typo fails the build.
- **Inline code**: any other JavaScript (§3.4).

There is one `@` attribute per event on an element, and an element may have both `onclick={...}` (Java) and `@click="..."` (JavaScript); the JavaScript runs first. The Java event attributes no longer take a string: `onclick="..."` is a compile error, and inline JavaScript is written as `@click="..."`.

### 3.2 The payload

The payload is one value:

| Written | The function receives |
|---|---|
| `$name` | the element with `ref="name"` in the same component (§3.6) |
| a Java name: `url`, `t.id()`, `user.address().city()` | the value of that Java expression, converted to JSON (below) |
| a Java name whose value is a `State` or `Derived` | its value, **kept current**: when it changes, the server sends the new value, and the next call gets it |
| a Java name whose value is a Java handler (`Handler` or `Action`) | a JavaScript function that sends to that handler on the server (§3.3) |
| `'text'`, `3`, `-1.5`, `true`, `false`, `null` | that literal value (strings in single quotes: the attribute itself is in double quotes) |
| `{a: ..., b: ...}`, `[..., ...]` | an object or a list of the above |
| `{url}` | shorthand for `{url: url}` |

- A Java name is a variable, optionally followed by fields and no-argument method calls. The lowering writes it as Java code, so javac checks it, and a typo is a compile error at the `.jasx` line. For anything more complex, use a local variable.
- Values are converted to JSON: strings, numbers, booleans, `null`, enums (by name), lists and arrays, maps with string keys, and records (by component name).
- The payload holds only values, never code. Names in it are Java's, refs are `$name`; there is nothing else to tell apart.

### 3.3 Calling the server from JavaScript

A Java handler in the payload arrives as a function. Calling it sends an event to that handler, through the same queue as every other event:

```java
Handler<InputEvent> saveColor = e -> color.set(e.value());
<input type="color" @input="preview({save: saveColor})" />
```

```js
export async function preview(event, { save }) {
  document.body.style.background = event.target.value;   // instantly, in the browser
  await save(event.target.value);                        // then on the server
}
```

1. When the page renders, the server registers `saveColor` as it registers any handler, with an id, say `12`.
2. The browser receives `{"handler": 12}` in place of the function and turns it into a JavaScript function.
3. `save("#ff0000")` sends `{"handler": 12, "value": "#ff0000"}`, as an event does.
4. On the server, the handler runs with `e.value()` equal to `"#ff0000"`. An object, such as `save({from: 2, to: 5})`, arrives as fields: `e.field("from")`, or `e.as(Move.class)`. A handler written as an `Action` (`() -> ...`) is called with no argument.
5. The answer is the usual update. The promise returned by `save(...)` resolves once the update is in the page, and rejects if the request fails or the page's client has stopped (after a navigation).

Values from JavaScript are input from the browser, like form fields, so the server checks them as it would check a form.

### 3.4 Inline code

Anything that isn't a call (§3.1) is inline code. It sees the **event** as `event`, the component's **refs** as `$name`, and JavaScript's own names:

```java
<button @click="$menu.hidden = !$menu.hidden">Menu</button>
<li @dragover="event.preventDefault()" @drop="drop({index, move})">...</li>
<button @click="window.print()">Print</button>
```

- **Browser globals are written `window.name`**: `window.print()`, `window.alert('Saved')`, `window.navigator.clipboard`. The compiler trusts them and doesn't check that they exist. A bare call such as `alert('Saved')` has the form of a call, so it must be a function of the script (§3.1); written `window.alert('Saved')`, it is inline code.
- Inline code has no payload, so it can't use Java variables. Code that needs a Java value is a function called with a payload.
- Inline code is not evaluated from a string in the browser: the build moves each snippet into the file's generated module as a function (§8), and the attribute names that function. So a strict Content Security Policy works.

### 3.5 `@render` and cleanups

`@render="name(payload)"` is called when the element appears in the page (on load, when an update inserts it, or after a navigation), and again whenever a kept-current value in its payload changes. The event is a `CustomEvent` named `render`. It is for code that follows server state, such as a chart (§5.8).

**Every function called from an `@` attribute may return a cleanup function**, or a promise of one. The client calls it:

- before the same attribute runs again on the same element, so ten clicks don't leave ten timers running; and
- when the element leaves the page: removed by an update, or by a navigation (§6).

```js
export function drawChart(event, { canvas, points }) {
  const chart = new Chart(canvas, { type: "line", data: { datasets: [{ data: points }] } });
  return () => chart.destroy();   // before the next draw, and when the canvas leaves the page
}
```

A widget can also manage its own lifecycle as a **custom element** (`connectedCallback`, `attributeChangedCallback`, `disconnectedCallback`), which the browser calls itself; the server updates its attributes with the usual `attr` patches. JASX needs nothing for that.

### 3.6 Refs

`ref="name"` marks an element. A `$name` refers to the element with that ref **in the same markup**: the component's own elements, not those of components inside it or around it. Each instance of a component has its own refs, so a list of fifty rows, each with `$details`, gets fifty different elements.

A component may render several top-level elements (a fragment) or none with a ref; refs are looked up among all the elements one rendering of the component produced (§8).

### 3.7 Which file's script

A name refers to the script of the `.jasx` file **where the element is written**, as scoped CSS does. A child that `Page.jasx` passes into `<Menu>` uses `Page.jasx`'s script, and two files can each have their own `toggle`.

## 4. Script blocks and JavaScript files

- A `<script>` block may appear where a top-level declaration or a class member starts, as style blocks do (`semantics.md` §10). Its content is raw JavaScript up to `</script>`.
- All the blocks of a `.jasx` file form **one ES module**, in source order. A function called from an attribute must be imported or declared at the top level of it (`import { a } from`, `function b`, `const c =`, ...). It need not be exported: the compiler exports what the file's attributes use.
- A `<script>` **inside markup** stays an ordinary HTML element, as today (import maps, JSON-LD, analytics snippets).
- `.js` files live next to the `.jasx` files, in `src/<set>/jasx`. Relative imports (`./menu.js`, `../shared/util.js`) work as in any ES module.
- **npm packages:** import them from a URL, or map their names once with an import map in the layout's `<head>`:

  ```java
  static final String IMPORTS = """
      {"imports": {"chart.js": "https://cdn.jsdelivr.net/npm/chart.js@4/auto/+esm"}}""";

  <head>
    <script type="importmap">{IMPORTS}</script>
  </head>
  ```

## 5. Examples

### 5.1 Tabs, in the browser only

```java
<script>
  import { showTab } from "./tabs.js";
</script>

public Element Settings() {
    return (
        <div class="tabs">
            <button @click="showTab({show: $profile, hide: [$billing]})">Profile</button>
            <button @click="showTab({show: $billing, hide: [$profile]})">Billing</button>
            <section ref="profile">...</section>
            <section ref="billing" hidden>...</section>
        </div>
    );
}
```

```js
// tabs.js
export function showTab(event, { show, hide }) {
  show.hidden = false;
  hide.forEach((section) => (section.hidden = true));
}
```

### 5.2 Copying a value the server knows

```java
<script>
  import { copy } from "./clipboard.js";
</script>

public Element ShareLink(String url) {
    return <button @click="copy({text: url})">Copy link</button>;
}
```

```js
// clipboard.js
export async function copy(event, { text }) {
  await navigator.clipboard.writeText(text);
  event.currentTarget.textContent = "Copied";
}
```

### 5.3 A character counter, with nothing sent while typing

```java
<script>
  import { count } from "./counter.js";
</script>

public Element Tweet() {
    return (
        <form onsubmit={e -> posts.add(e.field("text"))}>
            <textarea name="text" @input="count({counter: $counter, max: 280})"></textarea>
            <span ref="counter">0 / 280</span>
            <button>Post</button>
        </form>
    );
}
```

```js
// counter.js
export function count(event, { counter, max }) {
  const length = event.target.value.length;
  counter.textContent = length + " / " + max;
  counter.classList.toggle("too-long", length > max);
}
```

### 5.4 Confirming before the server deletes

```java
<script>
  import { confirmDelete } from "./confirm.js";
</script>

public Element TodoRow(Todo t, TodoStore store) {
    Action remove = () -> store.remove(t.id());
    return (
        <li>
            <span>{t.title()}</span>
            <button @click="confirmDelete({title: t.title(), remove})">Delete</button>
        </li>
    );
}
```

```js
// confirm.js
export async function confirmDelete(event, { title, remove }) {
  if (window.confirm("Delete \"" + title + "\"?")) {
    await remove();
  }
}
```

### 5.5 Live preview, then save

```java
<script>
  import { preview } from "./color.js";
</script>

public Element ColorPicker() {
    var color = state("#000000");
    Handler<InputEvent> saveColor = e -> color.set(e.value());
    return (
        <div>
            <input type="color" value={color} @change="preview({swatch: $swatch, save: saveColor})" />
            <div ref="swatch" class="swatch"></div>
            <p>Saved: {color}</p>
        </div>
    );
}
```

```js
// color.js
export function preview(event, { swatch, save }) {
  swatch.style.background = event.target.value;   // instant
  save(event.target.value);                       // the server stores it, and "Saved:" updates
}
```

### 5.6 Reordering by drag and drop

```java
<script>
  import { startDrag, drop } from "./drag.js";
</script>

record Move(int from, int to) {}

public Element Board(TaskStore store) {
    Handler<FormEvent> move = e -> {
        Move m = e.as(Move.class);
        store.move(m.from(), m.to());
    };
    return (
        <ul>
            {for (int i = 0; i < store.size(); i++) {
                int index = i;
                <li key={store.get(i).id()} draggable="true"
                    @dragstart="startDrag({index})"
                    @dragover="event.preventDefault()"
                    @drop="drop({index, move})">{store.get(i).title()}</li>
            }}
        </ul>
    );
}
```

```js
// drag.js
export function startDrag(event, { index }) {
  event.dataTransfer.setData("text/plain", String(index));
}

export function drop(event, { index, move }) {
  event.preventDefault();
  const from = Number(event.dataTransfer.getData("text/plain"));
  move({ from, to: index });   // the server reorders, and the keyed list moves the items
}
```

### 5.7 A dialog that saves, then closes

```java
<script>
  import { saveAndClose } from "./dialog.js";
</script>

record Profile(String name, String email) {}

public Element EditProfile(UserStore users) {
    Handler<FormEvent> save = e -> users.update(e.as(Profile.class));
    return (
        <div>
            <button @click="$dialog.showModal()">Edit profile</button>
            <dialog ref="dialog">
                <form ref="form" @submit="saveAndClose({dialog: $dialog, form: $form, save})">
                    <input name="name" />
                    <input name="email" type="email" />
                    <button>Save</button>
                </form>
            </dialog>
        </div>
    );
}
```

```js
// dialog.js
export async function saveAndClose(event, { dialog, form, save }) {
  event.preventDefault();
  await save(Object.fromEntries(new FormData(form)));   // arrives as fields: e.as(Profile.class)
  dialog.close();
}
```

A `<dialog>` already closes on Escape by itself.

### 5.8 A chart that follows server state

```java
<script>
  import { drawChart } from "./chart.js";
</script>

public Element Sales(SalesStore store) {
    var points = store.points();   // a State<List<Point>>, updated by the server
    return <canvas ref="canvas" data-jx-ignore @render="drawChart({canvas: $canvas, points})"></canvas>;
}
```

```js
// chart.js
import Chart from "chart.js";

export function drawChart(event, { canvas, points }) {
  const chart = new Chart(canvas, { type: "line", data: { datasets: [{ data: points }] } });
  return () => chart.destroy();
}
```

`@render` runs when the canvas appears, and again each time the server changes `points`; each time, the previous chart is destroyed first. `data-jx-ignore` keeps server updates out of the chart's own content (§7).

### 5.9 The same component many times

```java
<script>
  import { toggle } from "./toggle.js";
</script>

public Element Faq(List<Question> questions) {
    return (
        <dl>
            {for (Question q : questions) {
                <QuestionRow question={q} />
            }}
        </dl>
    );
}

Element QuestionRow(Question question) {
    return (
        <div>
            <dt @click="toggle({answer: $answer})">{question.text()}</dt>
            <dd ref="answer" hidden>{question.answer()}</dd>
        </div>
    );
}
```

Each `QuestionRow` has its own `$answer`, so clicking a question opens only its own answer.

### 5.10 One line, inline

```java
<button @click="window.print()">Print</button>
<button @click="$details.open = !$details.open">More</button>
```

### 5.11 A timer, cleaned up

```java
<button @click="startCountdown({label: $label})">Start</button>
<span ref="label"></span>
```

```js
export function startCountdown(event, { label }) {
  let seconds = 10;
  const timer = window.setInterval(() => { label.textContent = String(--seconds); }, 1000);
  return () => window.clearInterval(timer);   // a second click stops this countdown first
}
```

## 6. Navigation, and pages

- **Cleanups on navigation.** When a navigation replaces the page in place (`targets/web.md` §6.4), every pending cleanup of the old body runs before the new page's client starts.
- **Modules stay loaded across navigations.** In-place navigation keeps the document, so ES modules are loaded once per tab and their top-level code runs once. Module-level state (a cache, a `WeakMap`) survives navigations; listeners and timers belong in functions that return a cleanup, not at a module's top level.
- **Loading.** The page's `<head>` gets `<link rel="modulepreload">` for each module it uses, and a `module` patch adds one that an update needs, as `css` patches do for style sheets. On a navigation, the client loads the next page's modules before starting its client. An event that fires before its module has loaded waits for it; for such an event, `preventDefault()` comes too late.

## 7. Server updates keep browser changes

**Implemented** (spec revision 20, `targets/web.md` §8.4). It is what lets JavaScript and server updates share an element.

The client remembers, for each element, the attributes the server last rendered. When a hole is merged, or an `attr` patch arrives, the client changes an attribute only if the **server's new value differs from the server's previous value**. An attribute the server didn't change keeps whatever the browser did to it.

- **`class` is merged name by name.** Classes the server added are added, classes it removed are removed, and classes added by JavaScript stay.
- `style` is one attribute: kept if the server didn't change it, replaced if it did.
- Field values keep the existing rule: typing the server hasn't received is never overwritten (`targets/web.md` §7).
- **Structure is still the server's.** Inside a hole, nodes that JavaScript inserted or removed are reconciled with what the server rendered. An element marked `data-jx-ignore` keeps its children as JavaScript left them; its attributes still follow the rule above.

## 8. What reaches the browser

- **Files.** The build plugins copy the `.js` files from `src/<set>/jasx` into the jar, keeping their folders, and add one generated module per `.jasx` file that has script blocks or inline code. It holds the file's script, and one function for each inline snippet. Modules are served under `/_jasx/js/<build>/`, where `<build>` is a hash of all the files, so the URLs are cached for good, as the client script is (`targets/web.md` §8.1), and relative imports work.
- **In development**, the server serves the `.js` files from the source directories instead, so a browser reload picks up an edited file without a rebuild.
- **Markup.** `@click="toggleMenu({menu: $menu, save})"` in `Menu.jasx` renders as one attribute with the module, the function and the payload as JSON:

  ```html
  <button data-jx-js='{"click":["web/Menu","toggleMenu",{"menu":{"$ref":"menu"},"save":{"handler":12}}]}'>
  ```

  Inline code renders the same way, naming its generated function and the refs it uses. `ref="menu"` renders as `data-jx-ref="menu"`. Each top-level element of a component's rendering gets `data-jx-scope` with the same instance id, which bounds where its refs are looked up, so components that render a fragment work too. If the payload holds a kept-current value, the attribute is dynamic, and an `attr` patch updates it.
- **Calling.** The client's one listener per event type finds the element's function, resolves the refs and handlers, runs the previous cleanup if any, and calls `fn(event, payload)`. Exceptions are reported in the console, as for any listener.
- **Sizes.** A kept-current value is sent again as a whole when it changes. That suits small values; a large list that changes often (chart data) is better sent in smaller pieces, through a handler the JavaScript calls for what it needs.

## 9. Tooling and tests

- **Editor.** The language server completes, in `@` attributes, the functions of the file's script, the refs of the component, and Java names in the payload, and gives hover and go-to-definition on them (into the `.js` file, or to the Java declaration). Java names in a payload are Java code inside a string, so completion, hover and rename treat them as the Java they are. Highlighting covers `@` attributes as JavaScript.
- **Types in `.js` files.** With `// @ts-check` and JSDoc, VS Code checks a `.js` file like TypeScript. The build provides `jasx.d.ts`, and per call a type for its payload derived from the Java types (`State<Integer>` gives `number`, a record gives an object type, a handler a function), so a mismatch between JavaScript and Java shows in the editor.
- **Tests.** `jasx-web-test` runs no JavaScript. A `TestPage` can call a Java handler that a payload passes, with the value the JavaScript would send, so flows like a confirmed delete or a drop are tested without a browser; the JavaScript itself is tested with a JavaScript test runner.

## 10. Decisions

| Question | Decision |
|---|---|
| A Java DSL for browser state (signals) or commands (`Js`)? | No. Plain JavaScript only. |
| How is browser code told apart from Java handlers? | `@event` attributes are JavaScript; `on...` attributes are Java only, and no longer take a string. |
| The shape of a browser function? | Always `(event, payload)`. |
| What is in a payload? | `$name` refs; every other name is a Java variable: a value (JSON), a `State` or `Derived` (kept current), or a handler (a function that calls the server). Literals, objects and lists of those. Never code. |
| Inline code? | Anything that isn't a call. It sees `event`, the refs and JavaScript's names, not Java variables. The build moves it into the generated module, so a strict CSP works. |
| Browser globals? | `window.name`, trusted without a check. A bare `name(...)` must be a function of the script, so typos fail the build. |
| How does JavaScript change server state? | It calls a Java handler passed in the payload (§3.3). |
| How does JavaScript follow server changes? | `@render`, called when the element appears and when a kept-current value in its payload changes. Custom elements work too. |
| Cleanup? | Any function called from `@` may return a cleanup (or a promise of one), run before that attribute runs again on the element and when the element leaves the page. |
| Refs? | `ref="name"`, used as `$name`, scoped to the component instance, fragments included. |
| `<script src="./x.js">` as a block form? | No. Use `import` in the block; one way to do it. |
| Must functions be exported? | No. Imported or top-level names are enough. |
| Is `<script>` inside markup a block? | No. It stays an HTML element. |
| TypeScript? | `// @ts-check` with JSDoc and generated payload types in phase 2; real `.ts` through esbuild in phase 3. |
| npm packages? | URLs or an import map; no bundler before phase 3. |
| Widgets that own their DOM? | `data-jx-ignore`. |
| The `html` target? | It rejects script blocks and `@` attributes (a target capability error, as for style blocks without `"styles"`) until it gets a loader of its own. |
| Where does this go in the spec? | `@` attribute names in `grammar.md`; script blocks, calls, payloads, inline code and refs in `semantics.md`, with a descriptor capability `"scripts": "module"`; serving, loading, values, `@render`, cleanups and merging in `targets/web.md`. |

## 11. Phases

1. **Server updates keep browser changes** (§7), with `data-jx-ignore`. Done (spec revision 20).
2. **Script blocks and `@` attributes**: the grammar and spec, calls with payloads, inline code moved into generated modules, refs, `@render` and cleanups, removing the string form of `on...`, the build plugins copying files, serving (and serving from source in development), loading and navigation, the language server and highlighting, generated payload types, and `TestPage` calling payload handlers.
3. **TypeScript**: `.ts` files next to the `.jasx` files, converted to `.js` by esbuild (a small binary the build downloads, only for projects with `.ts` files). Imports follow TypeScript's convention, `import { toggleMenu } from "./menu.js"` for `menu.ts`. Optional bundling of npm packages with the same tool, if import maps prove not enough.

## 12. What changes in the repository when this is accepted

- `AGENTS.md`: replace "no browser-only layer" with "browser code is plain JavaScript in `@` attributes and script blocks (`targets/web.md`); `on...` is Java only; no Java DSL for browser code".
- The spec, as in §10's last row, with conformance tests for parsing script blocks and `@` attributes, payloads and their errors.
- `jasx-web`: the `on...(String)` builder overloads are removed.
- `docs/web.md`: a section "JavaScript in the browser", with §5's examples.
- `examples/web`: tabs, a copy button and a confirmed delete, using `.js` files.
