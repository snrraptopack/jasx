# Discussion: plain JavaScript in the browser

**Status:** proposal with every question decided; waiting for the user's feedback. Nothing is implemented.

**Date:** 2026-11-01.

**Scope:** the `web` target. Other targets reject script blocks until they support them (§9).

## 1. Goal

To do small things in the browser without a round trip to the server: open a menu, switch a tab, copy to the clipboard, preview a colour, draw a chart. The code is plain JavaScript, in `.js` files with full editor support, connected to `.jasx` files by a `<script>` block.

The server stays in charge of its state. This is not a JavaScript framework: there is no client state system, no Java-to-JavaScript compiler, and no API to learn beyond one way of calling a function.

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
            <button onclick="toggleMenu({menu: $menu})">Menu</button>
            <nav ref="menu" hidden>...</nav>
        </div>
    );
}
```

- `onclick="toggleMenu(...)"` calls `toggleMenu` from this file's script, in the browser. Nothing is sent to the server.
- The function always gets the **event first**, then what is written in the brackets.
- `$menu` is a **ref**: the element with `ref="menu"` in the same component.
- A bare name, such as `color` or `save`, is a **Java variable** (§3.2).

## 3. Rules

### 3.1 Calling a function

An event attribute whose value is a string literal of the form `name` or `name(arguments)` calls the function `name` of the file's script (§4). The script must import or declare `name`; otherwise the build fails at that attribute.

```java
<button onclick="toggleMenu">...</button>                      // toggleMenu(event)
<button onclick="toggleMenu({menu: $menu})">...</button>       // toggleMenu(event, {menu: <nav>})
<button onclick="move(1, -1)">...</button>                     // move(event, 1, -1)
```

Any other string, such as `this.hidden = true` or `window.print()`, is inline JavaScript, as in HTML. In a file without a script block, every string is inline JavaScript, exactly as today.

### 3.2 What can go in the brackets

Only values, never JavaScript code:

| Written | The function receives |
|---|---|
| `"text"`, `3`, `-1.5`, `true`, `false`, `null` | that literal value |
| `$name` | the element with `ref="name"` in the same component (§3.3) |
| a Java name: `color`, `t.id()`, `user.address().city()` | the value of that Java expression, converted to JSON (below) |
| a Java name whose value is a `State` or `Derived` | its value, **kept current**: when it changes, the server sends the new value, and the next call gets it |
| a Java name whose value is a Java handler (`Handler` or `Action`) | a JavaScript function that sends to that handler on the server (§3.4) |
| `{a: ..., b: ...}`, `[..., ...]` | an object or a list of the above |
| `{color}` | shorthand for `{color: color}` |

- A Java name is a variable, optionally followed by fields and no-argument method calls. javac checks it, so a typo is a compile error at the `.jasx` line. For anything more complex, use a local variable.
- Values are converted to JSON: strings, numbers, booleans, `null`, enums (by name), lists and arrays, maps with string keys, and records (by component name).
- `$` means a ref only outside quotes: `"$5"` is the string `$5`.

### 3.3 Refs

`ref="name"` marks an element. A `$name` in a call refers to the element with that ref **in the same markup**: the component's own elements, not those of components inside it or around it. Each instance of a component has its own refs, so a list of fifty rows, each with `$details`, gets fifty different elements.

### 3.4 Sending to the server from JavaScript

A Java handler passed in the brackets arrives as a function. Calling it sends an event to that handler, through the same queue as every other event:

```java
Handler<InputEvent> saveColor = e -> color.set(e.value());
<input type="color" oninput="preview({save: saveColor})" />
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
5. The answer is the usual update. The promise returned by `save(...)` resolves once the update is in the page, and rejects if the request fails.

Values from JavaScript are input from the browser, like form fields, so the server checks them as it would check a form.

### 3.5 Values the browser changes

What the function receives is a **copy**. Changing it in JavaScript changes nothing on the server; to change server state, call a handler (§3.4). State that only matters in this browser (a menu open, a tab chosen) lives in the DOM or in JavaScript variables.

### 3.6 Running code when an element appears: `onrender`

`onrender="name(...)"` calls the function when the element appears in the page (on load, or when an update inserts it), and again whenever one of its kept-current values changes. The event is a `CustomEvent` named `render`. This is for code that follows server state, such as a chart (§5.8).

### 3.7 Which file's script

A name refers to the script of the `.jasx` file **where the element is written**, as scoped CSS does. A child that `Page.jasx` passes into `<Menu>` uses `Page.jasx`'s script, and two files can each have their own `toggle`.

## 4. Script blocks and JavaScript files

- A `<script>` block may appear where a top-level declaration or a class member starts, as style blocks do (`semantics.md` §10). Its content is raw JavaScript up to `</script>`.
- All the blocks of a `.jasx` file form **one ES module**, in source order. A function used in an attribute must be imported or declared at the top level of it (`import { a } from`, `function b`, `const c =`, ...). It need not be exported: the compiler exports what the file's attributes use.
- A `<script>` **inside markup** stays an ordinary HTML element, as today (import maps, JSON-LD, analytics snippets).
- `.js` files live next to the `.jasx` files, in `src/<set>/jasx`. Relative imports (`./menu.js`, `../shared/util.js`) work as in any ES module.
- **Types in the editor:** start a `.js` file with `// @ts-check` and use JSDoc types, and VS Code checks it like TypeScript (§5.11). Real `.ts` files come in phase 3 (§8).
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
            <button onclick="showTab({show: $profile, hide: [$billing]})">Profile</button>
            <button onclick="showTab({show: $billing, hide: [$profile]})">Billing</button>
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
    return <button onclick="copy({text: url})">Copy link</button>;
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
            <textarea name="text" oninput="count({counter: $counter, max: 280})"></textarea>
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
            <button onclick="confirmDelete({title: t.title(), remove})">Delete</button>
        </li>
    );
}
```

```js
// confirm.js
export async function confirmDelete(event, { title, remove }) {
  if (confirm("Delete \"" + title + "\"?")) {
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
            <input type="color" value={color} onchange="preview({swatch: $swatch, save: saveColor})" />
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
                    ondragstart="startDrag({index})"
                    ondragover="event.preventDefault()"
                    ondrop="drop({index, move})">{store.get(i).title()}</li>
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
  import { open, close, saveAndClose } from "./dialog.js";
</script>

record Profile(String name, String email) {}

public Element EditProfile(UserStore users) {
    Handler<FormEvent> save = e -> users.update(e.as(Profile.class));
    return (
        <div>
            <button onclick="open({dialog: $dialog})">Edit profile</button>
            <dialog ref="dialog" onkeydown="close({dialog: $dialog})">
                <form ref="form" onsubmit="saveAndClose({dialog: $dialog, form: $form, save})">
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
export function open(event, { dialog }) {
  dialog.showModal();
}

export function close(event, { dialog }) {
  if (event.key === "Escape") dialog.close();
}

export async function saveAndClose(event, { dialog, form, save }) {
  event.preventDefault();
  await save(Object.fromEntries(new FormData(form)));   // arrives as fields: e.as(Profile.class)
  dialog.close();
}
```

### 5.8 A chart that follows server state

```java
<script>
  import { drawChart } from "./chart.js";
</script>

public Element Sales(SalesStore store) {
    var points = store.points();   // a State<List<Point>>, updated by the server
    return <canvas ref="canvas" data-jx-ignore onrender="drawChart({canvas: $canvas, points})"></canvas>;
}
```

```js
// chart.js
import Chart from "chart.js";

const charts = new WeakMap();

export function drawChart(event, { canvas, points }) {
  const chart = charts.get(canvas) ?? new Chart(canvas, { type: "line", data: { datasets: [{ data: [] }] } });
  charts.set(canvas, chart);
  chart.data.datasets[0].data = points;
  chart.update();
}
```

`onrender` runs when the canvas appears, and again each time the server changes `points`. `data-jx-ignore` keeps server updates out of the chart's own content (§6).

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
            <dt onclick="toggle({answer: $answer})">{question.text()}</dt>
            <dd ref="answer" hidden>{question.answer()}</dd>
        </div>
    );
}
```

Each `QuestionRow` has its own `$answer`, so clicking a question opens only its own answer.

### 5.10 A one-liner: inline JavaScript (exists today)

```java
<button onclick="window.print()">Print</button>
```

### 5.11 Types in the editor

```js
// @ts-check

/**
 * @param {MouseEvent} event
 * @param {{ menu: HTMLElement, save: import("jasx").ServerHandler }} values
 */
export function toggleMenu(event, { menu, save }) {
  menu.hidden = !menu.hidden;
}
```

The build provides `jasx.d.ts` (types such as `ServerHandler`), and a `jsconfig.json` with `"checkJs": true` in `src/main/jasx` turns checking on for every file.

## 6. Server updates keep browser changes

This rule also fixes a bug that exists today: a `<details>` the user opened closes again when anything in the same hole changes.

**The rule.** The client remembers, for each element, the attributes the server last rendered. When a hole is merged, or an `attr` patch arrives, the client changes an attribute only if the **server's new value differs from the server's previous value**. An attribute the server didn't change keeps whatever the browser did to it.

1. The server renders `<nav hidden>`.
2. JavaScript shows it (`hidden = false`).
3. Something else in the hole changes, and the server renders `<nav hidden>` again. The server's value didn't change, so the nav stays visible.
4. If the server later renders `<nav>` without `hidden`, and then `<nav hidden>` again, those are real changes, and they are applied.

- **`class` is merged name by name.** Classes the server added are added, classes it removed are removed, and classes added by JavaScript stay.
- `style` is one attribute: kept if the server didn't change it, replaced if it did.
- Field values keep the existing rule: typing the server hasn't received is never overwritten (`targets/web.md` §7).
- **Structure is still the server's.** Inside a hole, nodes that JavaScript inserted or removed are reconciled with what the server rendered. An element marked `data-jx-ignore` keeps its children as JavaScript left them; its attributes still follow the rule above.

## 7. What reaches the browser

- **Files.** The build plugins copy the `.js` files from `src/<set>/jasx` into the jar, keeping their folders, and add one generated module per `.jasx` file that has script blocks. It is served under `/_jasx/js/<build>/`, where `<build>` is a hash of all the files, so the URLs can be cached forever and relative imports work.
- **Markup.** `onclick="toggleMenu({menu: $menu, save})"` in `Menu.jasx` renders as one attribute with the module, the function and the values as JSON:

  ```html
  <button data-jx-js='{"click":["web/Menu","toggleMenu",[{"menu":{"$ref":"menu"},"save":{"handler":12}}]]}'>
  ```

  `ref="menu"` renders as `data-jx-ref="menu"`, and the component's root element gets `data-jx-scope`, which bounds where refs are looked up. If a value is kept current, the attribute is dynamic, and an `attr` patch updates it.
- **Loading.** The page's `<head>` gets `<link rel="modulepreload">` for each module it uses, and a `module` patch adds one that an update needs, as `css` patches do for style sheets. An event that fires before its module has loaded waits for it; for such an event, `preventDefault()` comes too late.
- **Calling.** The client's one listener per event type finds the element's function, resolves the refs and handlers, and calls `fn(event, ...values)`. Exceptions are reported in the console, as for any listener.

## 8. Phases

1. **Server updates keep browser changes** (§6), with `data-jx-ignore`. It is small, fixes today's bug, and everything else depends on it.
2. **Script blocks and calls**: the parser and spec, the call syntax with refs, Java values and handlers, `onrender`, the build plugins copying files, serving and loading, and highlighting and completion in the editor.
3. **TypeScript**: `.ts` files next to the `.jasx` files, converted to `.js` by esbuild (a small binary the build downloads, only for projects with `.ts` files). Imports follow TypeScript's convention, `import { toggleMenu } from "./menu.js"` for `menu.ts`. Optional bundling of npm packages with the same tool, if import maps prove not enough.

## 9. Decided questions

| Question | Decision |
|---|---|
| A Java DSL for browser state (signals) or commands (`Js`)? | No. Plain JavaScript only. |
| How does a function get server data? | Java names in the call's brackets (§3.2); `State` values are kept current. |
| How does JavaScript change server state? | It calls a Java handler passed in the brackets (§3.4). |
| How does JavaScript react to server changes? | `onrender` (§3.6). |
| Refs? | `ref="name"`, used as `$name`, scoped to the component instance (§3.3). |
| Can the brackets hold JavaScript code? | No, only values. The browser never evaluates code strings, so a strict Content Security Policy works. |
| A typo in a function name? | In a file with a script block, `name` and `name(...)` must be a function of its script: a compile error otherwise. |
| `<script src="./x.js">` as a block form? | No. Use `import` in the block; one way to do it. |
| Must functions be exported? | No. Imported or top-level names are enough. |
| Is `<script>` inside markup a block? | No. It stays an HTML element. |
| A JavaScript function and a Java handler on the same event? | One attribute per event. The function calls the handler (§3.4). |
| TypeScript? | `// @ts-check` with JSDoc from phase 2; real `.ts` through esbuild in phase 3. |
| npm packages? | URLs or an import map; no bundler before phase 3. |
| Widgets that own their DOM? | `data-jx-ignore`. |
| The `html` target? | It rejects script blocks and calls (a target capability error, as for style blocks without `"styles"`) until it gets a loader of its own. |
| Where does this go in the spec? | Script blocks, the call syntax and refs in the core (`grammar.md`, `semantics.md`), with a descriptor capability `"scripts": "module"`; serving, loading, values and merging in `targets/web.md`. |

## 10. What changes in the repository when this is accepted

- `AGENTS.md`: replace "no browser-only layer" with "browser code is plain JavaScript (script blocks and calls, `targets/web.md`); no Java DSL for it".
- The spec, as in §9's last row, with conformance tests for parsing script blocks, the call syntax and its errors.
- `docs/web.md`: a section "JavaScript in the browser", with §5's examples.
- `examples/web`: tabs, a copy button and a confirmed delete, using `.js` files.
