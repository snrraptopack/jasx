# Target: `web` (draft)

**Spec version:** 1.0-draft
**Status:** **Draft.** Implemented by `jasx-web`. It describes that implementation and may still change; the core specification does not depend on it.

`web` is JASX's server-driven target, in the same family as Phoenix LiveView, Laravel Livewire and Blazor Server. Components run on the server. A page is rendered to complete HTML, and then stays alive on the server while it is open in the browser. Event handlers are Java lambdas that run on the server; when they change state, the browser receives small patches for exactly the places that changed. The application is one Java program: handlers call services and databases directly, with no API layer and no JavaScript.

The browser keeps what belongs to it: text being typed stays in the browser until the application needs it (§7).

It uses a **custom lowering** (`semantics.md` §7.4): every dynamic position (§8) becomes a reactive *hole* that is re-evaluated when the state it reads changes.

---

## 1. Descriptor

```json
{
  "id": "web",
  "specVersion": "1.0",
  "factory": "jasx.web.Web",
  "attributes": "jasx.web.attrs",
  "schema": "schemas/web.json",
  "lowering": "jasx.web.compiler.WebLowering",
  "eventHandlers": "java",
  "styles": "css",
  "scripts": "module"
}
```

The schema is the schema of the `html` target (`targets/html.md` §2), with the same elements, attributes and event attributes.

## 2. Attribute builders

The builders (`jasx.web.attrs.<Tag>`) are generated from the schema, like those of `html`, with one more overload per attribute for its dynamic form:

| Attribute | Static value | Dynamic value (§3) |
|---|---|---|
| boolean (`disabled`) | `disabled(boolean)` | `disabled(Supplier<Boolean>)` |
| other (`class`, `value`, …) | `className(Object)` | `className(Supplier<?>)` |
| event (`onclick`, …) | none: browser code is a browser attribute, `@click` (§7.1) | `onclick(jasx.ui.Action)` and `onclick(jasx.ui.Handler<? super MouseEvent>)`, a Java handler of the event's kind (`ui.md` §4) |

## 3. Lowering

The lowering is factory lowering (`semantics.md` §6) with these changes:

| Position (`semantics.md` §8) | Lowers to |
|---|---|
| dynamic child `{e}` | `jasx.web.Web.dynamic(() -> (e))` |
| statement child `{for ...}`, `{if ...}` | `jasx.web.Web.dynamic(() -> switch (0) { ... })` |
| dynamic attribute `a={e}` | `.a(() -> (e))` |
| handler `onclick={e}` | `.onclick((e))`, unchanged |
| keyed element `<li key={k}>...</li>` | `jasx.web.Web.keyed((k), () -> element)`: the key is evaluated once, the element is a function (§4.3) |
| component `<Card title="t" />` | `jasx.web.Web.component(() -> app.Ui.Card("t"))`: the call of `semantics.md` §5.6, as a function (§4.4) |
| prop | unchanged: evaluated once |

### 3.1 Captured variables

Because holes are lambdas, Java requires every local variable a hole reads to be **effectively final**, and a statement child can't assign the enclosing method's local variables. Code that compiles on `html` can fail on `web`:

```java
String label = "a";
if (x) label = "b";
return <p>{label}</p>;     // web: javac error, label is not effectively final
```

javac reports the error at its `.jasx` position. The fix is a `final` copy, or a `state`/`derived` value. A future version **SHOULD** add a note to that error explaining that `{...}` is a reactive hole on this target.

## 4. State

State is `jasx.ui`'s (`ui.md` §2), shared with other interactive targets. `jasx.web.Web` re-exports its functions, so `import static jasx.web.Web.*` is enough:

| Function | Purpose |
|---|---|
| `State<T> state(T initial)` | A reactive value: `get()`, `set(value)`, `update(fn)`, `peek()`. |
| `Derived<T> derived(Supplier<T> fn)` | A cached value computed from other reactive values; recomputed lazily after one of them changes. |
| `Effect effect(Runnable fn)` | Runs `fn` once when created, then after every update in which a value it read changed. |
| `T untracked(Supplier<T> body)` | Runs `body` without subscribing to what it reads. |

**Compile time identifies holes. Run time discovers dependencies.** When a hole, a `Derived` or an effect runs, the runtime records every `State` and `Derived` it reads, and subscribes to them (`ui.md` §2.2). Each page is the `Scheduler` of its holes and effects (`ui.md` §3).

### 4.1 Components run once

A component method runs **once per instance**, when its element is rendered: not when the markup expression is evaluated. A component whose element is never rendered never runs, and the components among another component's children run where it renders them. Code in its body outside holes, `derived`, `effect` and handlers is not reactive:

```java
var count = state(0);
var doubled = count.get() * 2;                  // evaluated once
var doubled2 = derived(() -> count.get() * 2);  // reactive
```

Control flow in the body runs once. A reactive condition is written in a hole:

```java
if (loggedIn.get()) return <Dashboard />;                          // not reactive
return <main>{loggedIn.get() ? <Dashboard /> : <Login />}</main>;  // reactive
```

### 4.2 Lifetime

When a hole re-runs, everything created while it last ran (its holes, components, `Derived` values, effects and handlers) is disposed and created again, except the items of a keyed list that are kept (§4.3) and the component instances that are kept (§4.4).

### 4.3 Keyed lists

A hole whose value's children (flattened as by the child value contract, `semantics.md` §3.4) **all** have a key is a **keyed list**. Each child is an **item**: a hole of its own, made from the keyed element's function.

```java
<ul>{for (Todo t : todos.get()) { <TodoItem key={t.id()} todo={t} /> }}</ul>
```

When the list re-runs, each new child is matched with a previous item that has an equal key (`Objects.equals`) and **equal inputs**: the same element function (the same place in the code), whose captured values are equal (`equals`; a `State` is equal only to itself). A matched item is **kept**: its code doesn't run again, and it keeps its components' state, its holes and its handlers (`ui.md` §2.5). Every other child becomes a new item and is rendered, and previous items that weren't matched are disposed. So toggling one to-do re-creates only that row, because its record changed, and the other rows keep everything.

An item updates by itself when a value its own code read changes, like any hole. The browser receives a `list` patch (§8.4): the items in their new order, and the HTML of the new ones only.

- Captured values are compared with `equals`, so items **SHOULD** use immutable values (records). If an item captured a list that is then changed in place, it compares equal and isn't rendered again.
- If the runtime can't read an element function's captured values, the items are taken as different and rendered afresh, which is always correct.
- A list that mixes keyed and unkeyed children is a plain hole. An empty list stays a keyed list if it was one.
- Keyed elements are functions, so the locals they read must be effectively final (§3.1): a key in a classic `for (int i ...)` loop needs a final copy of `i`.

### 4.4 Component instances

Each rendering of a component element is an **instance**: the component's method runs, and what it creates (its state, holes, `Derived` values, effects and handlers) belongs to the instance. An instance is a hole of its own, made from the element's function (§3), so a value its method read outside holes makes it run again, as a new instance; reads belong in holes (§4.1).

When a hole re-runs, each component element it renders is matched with an instance the hole's previous run created, as keyed items are matched (§4.3), without a key: the same element function (the same place in the code) with **equal inputs**, its captured values equal (`equals`; a `State` is equal only to itself). Previous instances are considered in the order they were created, and each is used at most once. A matched instance is **kept**: its method doesn't run again, and it keeps everything it created, which goes on updating by itself. Its content in the hole's new content is its content as it is now. An element without a match is a new instance, and previous instances without one are disposed.

```java
<section>{String.valueOf(tick.get())} <Counter label="a" /></section>   // in a hole that re-runs on tick
```

When `tick` changes, the hole runs again, and `Counter` keeps its count: its label is the same. With `label={"tick " + tick.get()}`, its input changes, and it is a new instance with a new count.

- As with keyed items, inputs **SHOULD** be immutable values. A component given a value that changes in place compares equal and isn't run again.
- A component element where holes can't go (in `title`, `textarea`, `script` or `style`, or markup used as an attribute value) is rendered by running the component, every time.
- A component element directly in a `table` is taken as a row (`targets/web.md` §8.2), because finding out would run it.

## 5. Update cycle

1. An event arrives and its handler runs. `State.set` during the handler marks the state changed; nothing re-runs yet. A `set` with a value equal to the current one (`Objects.equals`) changes nothing.
2. When the handler returns, the runtime **flushes**: it re-runs every hole subscribed (directly or through a `Derived`) to a changed state, in document order, each at most once. A hole inside another hole that re-runs is not re-run separately: it is created again with its parent.
3. Each re-run hole whose output differs from its previous output produces a patch (§8.4).
4. Effects subscribed to a changed state run after the holes. If they change state, another flush follows.
5. The patches are sent to the browser as one batch.

State changed from another thread (a scheduled job, a message listener, another page) is applied under the page's lock, flushed at once, and the batch is pushed to the browser over the page's stream (§6).

**Failures.** An exception in a handler is logged, and what the handler changed before it is still flushed. An exception while a hole or an effect re-runs is logged, and that computation keeps its previous run (`ui.md` §2.4): the browser keeps its content, its handlers keep working, and it runs again on the next change of a value it read. The rest of the flush goes on. If the first rendering of a page throws, the page is discarded, so nothing it created keeps following shared state.

## 6. Pages

A **page** is one rendering of a root element, alive on the server while the browser shows it.

1. A request for a page renders its root to complete HTML (an `html` element, usually). The runtime inserts its client script before `</body>` (or at the end), and the page's style sheets before `</head>` unless `Web.styles()` places them (§9). The page gets an unguessable id.
2. The page's **stream** (server-sent events) carries the batches the server pushes, those that don't answer an event. The tabs of a browser share one stream (§6.2).
3. Events are sent as POST requests; the response carries the batch the event produced.
4. A page is discarded when it has had no stream connected and no sign of life (an event or a ping) for the page timeout (2 minutes by default). A page whose browser never connects (a crawler, a link preview, a prefetch) is discarded after the **first-connect timeout**, 30 seconds by default (never more than the page timeout). An event for an unknown page answers `410 Gone`, and the stream of an unknown page sends a reload message (§6.1); either way, the client reloads the page.
5. **Hidden tabs**, in a browser whose tabs can't share a stream (§6.2): a tab that stays hidden for 10 seconds closes its stream, and sends a ping (§8.1) every minute to keep its page alive. Shown again, it reconnects and catches up (§6.1).

### 6.1 Delivery and catching up

Writing a batch to a connection doesn't prove the browser received it, and the answer to an event can be lost after its handler ran. So delivery is settled only by the version the browser reports:

1. The server keeps each page's batches, answers and pushes alike, in a bounded log: at most 1000 batches and 4 MiB of them.
2. The stream is opened with the last version applied for each page: `stream?pages=ID:N,ID:N` (§6.2). For each page, the server first sends every batch after `N` as **one merged batch** (§8.4), then each pushed batch as it is made. Batches that answer events are not pushed, because their requests carry them.
3. If the page is unknown, the log no longer holds the first batch the client is missing, or `N` is a version the page never sent, the server sends `{"reload":true}` for that page, and its tab reloads. The stream goes on for the other pages.
4. The client asks again, with its current version: after the stream fails (waiting 0.5 s, doubling up to 10 s), when an event's request fails, and when a batch has been missing for 3 seconds. That deadline starts when the gap appears and later batches don't push it back. More than 500 batches waiting for a missing one also makes it ask at once. Asking again means reconnecting the stream, through the leader when tabs share it (§6.2).

A merged batch may cover versions the client had already applied (an event answer that arrived during the reconnection). Applying a patch again is harmless (§8.4), so the client applies a batch whose versions go beyond the last applied one.

**Form recovery.** Before the client reloads a page (a reload message, a `410`, a missing hole end), it saves in `sessionStorage`, under the page's path and query, the values of the page's named fields, except `password`, `hidden`, `file` and button inputs. Each field is found again by its form (its `id`, or its position among the page's forms), its name, and its position among the fields of that name. When the reloaded page starts, the client puts saved values back into the matching fields, then fires `input` and `change` on each field that changed, so its handlers (and the server's state) catch up. Saved values are used once, and not at all after 5 minutes.

### 6.2 One stream for every tab

Over HTTP/1.1, browsers open only about six connections per server, and every open stream holds one, so a few tabs of an application could block each other. The tabs of a browser therefore share one stream:

1. The tab holding the Web Lock `jasx-stream:<base path>` is the **leader**. It opens the stream for every tab's page, `stream?pages=A:12,B:7` (its own page first), and passes each frame to the tab it is for over the BroadcastChannel of the same name. Each frame names its page (§8.1).
2. Every other tab tells the leader its page and last applied version when it starts, when it needs to catch up (§6.1), and when the leader asks. The leader reconnects with the new list when a tab joins, asks to catch up, or leaves (on `pagehide`). It waits 50 ms first, so that tabs joining together cost one reconnection.
3. When the leader closes, the lock passes to another tab. The new leader asks every tab for its version, then opens the stream, and the server catches each page up (§6.1).
4. Every 30 seconds the leader asks every tab whether it is still there. A tab silent for 75 seconds (crashed or frozen) is dropped from the stream, so its page can expire. A tab that is shown again tells the leader its page, in case it was dropped.
5. On the server, a page is streamed by one connection at a time: a newer connection that names the page takes it, and a connection left with no page ends. A stream request names at most 100 pages; the server ignores the rest.

A browser without Web Locks or BroadcastChannel gives each tab its own stream. Web Locks need a secure context, so this is also what happens on a page served over plain HTTP from a host other than `localhost`. Serving over HTTP/2 removes the connection limit altogether; the JDK's HTTP server (`WebServer`) supports only HTTP/1.1, so production deployments **SHOULD** use HTTP/2 (through their server or a reverse proxy).

### 6.3 Security

The runtime (`WebApp`) does the following itself, so that every server adapter gets it:

1. **Sessions.** A page request from a browser without a session cookie gets one: `jasx_session`, 128 random bits, `Path=/; HttpOnly; SameSite=Lax`, and `Secure` when the request came over HTTPS. It has no expiry. Each page belongs to the session that rendered it. An event, a ping or a stream that names a page without carrying that session's cookie gets exactly what an unknown page gets (`410`, or a reload message, §6.1), so a page id that leaks is useless, and no answer tells whether a page id exists. Sessions are compared in constant time. Only cookie values of the form the runtime issues are read.
2. **Requests from other sites.** The page id is a secret token per page, carried in the URL of every request but never in a cookie. In addition:
   - an event's body **MUST** be declared as `application/json` (otherwise `415`). A page on another site can't send that content type without the browser asking the server first, and the server never allows it;
   - an event, a ping or a stream whose `Sec-Fetch-Site` header is present and is neither `same-origin` nor `none` is refused with `403`. The client only ever calls its own origin.
3. **Limits.** An event body larger than `maxEventBytes` (1 MiB by default) answers `413`, and adapters **SHOULD** stop reading at that size. A session keeps at most `maxPagesPerSession` pages alive (50 by default): rendering one more discards its oldest page. A stream names at most 100 pages (§6.2). JSON nesting is limited to 32 levels.

Pages need cookies: a browser that refuses them can render pages but not use them. Two pages rendered at the same moment for a browser without a session can each issue a cookie. The later one wins, and the page bound to the earlier one reloads once (§6.1) in the session the browser kept. Rate limiting is not part of the runtime; put it in front of the server (a reverse proxy) where needed.

### 6.4 Navigation

A click on a link to another page of the application loads that page without reloading the browser's page:

1. The client follows a link itself when it is a plain click (main button, no modifier key) on an `a` with an `href` of the same origin, outside the base path, without `download`, without a `target` other than `_self`, and not inside an element with `data-jx-reload`. A link to an anchor of the same page is left to the browser.
2. It fetches the URL. If the answer is not a successful `text/html` response with the client script (`data-jx-page`), or the request fails, the browser loads the URL the ordinary way.
3. Otherwise the client **stops**: it removes its listeners and timers, closes its stream (or leaves the shared one, §6.2, handing on the Web Lock if it led), and drops its queued events. It pushes the URL to the history, takes the new page's title, adds the style elements the page doesn't have yet, replaces the body with the new page's, and starts a client for the new page, which connects as a page loaded the ordinary way does, except that its first stream request names the page it replaced (`leave`, §8.1); in a tab that shares its stream, the new page's client tells the leading tab, which names it in its next stream request. The window scrolls to the URL's anchor, or to the top. The client script's URL is the same for every page, so the browser takes it from its cache (§8.1).
4. Going back or forward in the history to another path or query loads that page the same way.

The page left behind is discarded by the server as soon as a stream request names it in `leave`: it is never shown again, because going back renders the page anew. Should that request never come, the page is discarded when its timeout passes (§6), as for a tab that was closed.

## 7. Events and input

Handlers and events are `jasx.ui`'s (`ui.md` §4): a handler is an `Action` (`() -> ...`) or a `Handler` of the event's kind (`e -> ...`). `onsubmit` gives a `FormEvent`, `oninput` and `onchange` an `InputEvent`, `onkeydown` a `KeyEvent`, `onclick` a `MouseEvent`, and every other event attribute a plain `Event`. A handler runs on the server with the page's lock held.

**Input belongs to the browser.** Typing sends nothing to the server unless the page asks for it:

- `onsubmit` on a form sends all its fields once, when it is submitted. The form's default submission is prevented.
- `onchange` sends a field's value when it changes (for a text field, when it loses focus or Enter is pressed).
- `oninput` is **debounced by default**: the event is sent 300 ms after the last keystroke. `Web.debounce(ms, handler)` sets another delay, on any event, and `Web.immediate(handler)` sends every event (both accept a `Handler` or an `Action`).
- Before an event is sent, every pending debounced event is sent first, so a handler always sees the latest input (a submit right after typing carries the final text).
- **Typing is never overwritten.** Each edit of a field gives it a new revision, and each event remembers the revisions of the fields it carries (the field with the handler, and a form's fields). A field has *unsent edits* while it has a revision no sent event carried. A patch doesn't change the `value` of a field with unsent edits if the server will receive them (the field has a handler, or its form has one) or the field is focused. So the answer to an older event never overwrites newer typing, even when that event waited in the queue. `checked`, `selected` and `value` are set as DOM properties.
- **A hole that re-runs is merged into the page** (§8.4): elements that stay keep their focus and typed text, the kept items of a keyed list aren't touched (§4.3), and component instances whose inputs are unchanged are kept (§4.4).
- Events are sent one at a time, in order. While its event is queued or running, an element has the class `jx-loading` and `aria-busy="true"`. A form whose submit is in progress ignores further submits.

In a handler, `e.resetForm()` clears the form the event came from (typed text belongs to the browser, so the server can't clear it by setting state): each field returns to its initial value, except a field edited after the event was sent, which keeps the new text. `Web.navigate(url)` makes the browser go to `url`, as a click on a link to it would (§6.4): a page of the application is loaded in place, anything else the ordinary way.

### 7.1 Browser code

Script blocks, browser attributes and refs (`semantics.md` §11) run JavaScript in the browser, without a round trip. `onclick={...}` is Java, run on the server; `@click="..."` is JavaScript, run in the browser.

**Functions.** Every function a browser attribute calls receives the event first, then the payload: `fn(event, payload)`. In the payload:

| Value | What the function receives |
|---|---|
| `$name` | the element the ref stands for (below) |
| a Java value | its JSON form: strings, numbers, booleans and `null` as they are, enums by name, records as objects (by component name), lists and arrays as arrays, maps with string keys as objects; any other value as its `toString()` |
| a `State` or `Derived` | its value, as above, **kept current**: when it changes, the server sends the new payload (an `attr` patch, §8.4), and the next call gets it |
| a Java handler (`Action` or `Handler`) | a function that sends an event to that handler on the server (below) |

**Calling the server.** `handler(value)` sends an event to the handler through the page's queue, as `{"handler": H, "value": V}` for a string, number or boolean, or `{"handler": H, "fields": {...}}` for an object, each property as a field with one value (`e.field(name)`, `e.as(Type.class)`, `ui.md` §4); `handler()` sends neither. It returns a promise that resolves once the answer's update is in the page, and rejects if the request fails or the page's client has stopped.

**Inline code** runs as the body of a function of the file's module, called as `fn(event, { $a, ... })`: `event` is the event, `$name` the refs it uses.

**Refs.** `ref="menu"` renders as `data-jx-ref="menu"`. A `$menu` stands for the element with that ref **nearest** to the element whose browser attribute runs: the one with the fewest steps up and down the page's tree from it (up to a common ancestor, then down), the first in document order among equally near ones, or `null` if the page has none. So each row of a list finds its own `$details`, and a component's buttons find its own elements.

**`@render`** is called when the element appears in the page (on load, when an update inserts it, or after a navigation), and again whenever its payload changes (a kept-current value changed). Its event is a `CustomEvent` named `render`.

**Cleanups.** A function called from a browser attribute may return a function, or a promise of one: its **cleanup**. The client calls it before the same browser attribute runs again on the same element, and when the element leaves the page (an update removes it, or a navigation replaces the page, §6.4).

**Modules.** The compiler's modules (`semantics.md` §11.6) and the `.js` files next to the `.jasx` files are packaged by the build under `META-INF/jasx/js/` with a file `META-INF/jasx/js/.build` holding a hash of them, and served at `/_jasx/js/<build>/<path>` (§8.1), where `<build>` combines the hashes of every `.build` on the classpath. Relative imports (`./menu.js`) work as in any ES module. A page whose application has modules names the base URL in its client script element (`data-jx-js`, §8.2), and the client imports a module when an element first needs it; an event that fires before its module has loaded is handled once it has, so `preventDefault()` comes too late for it.

**Order.** On an element with both, the browser attribute runs before the Java handler is sent. Exceptions in browser code are reported in the browser's console.

## 8. Protocol

The runtime serves its client script, events and streams under a base path, `/_jasx` by default.

### 8.1 Requests

| Request | Purpose |
|---|---|
| `GET /_jasx/js/BUILD/PATH` | a module or `.js` file of the application (§7.1), from `META-INF/jasx/js/PATH` on the classpath, as `text/javascript`, cached for good (`immutable`); `404` for anything else (another build, a path with `..`, or not ending in `.js`) |
| `GET /_jasx/web.js?v=VERSION` | the client script. Pages load it with its version, a hash of its content, under which it is cached for good (`Cache-Control: public, max-age=31536000, immutable`); a new script has a new URL. Without the current version it answers `Cache-Control: no-cache`. It has an `ETag`, and a request with a matching `If-None-Match` answers `304` |
| `POST /_jasx/event?page=ID` | an event (§8.3); answers a batch, or `204` if nothing changed |
| `POST /_jasx/ping?page=ID` | a hidden tab keeping its page alive (§6); answers `204`, or `410` for an unknown page |
| `POST /_jasx/upload?page=ID` | a file chosen in a form's file field, sent before the form's event (below): the body is the file, `X-Jasx-Name` (required, URL-encoded) its name and `X-Jasx-Type` its media type; answers `{"upload": TOKEN}`, or `400`, `403`, `410`, or `413` for a file over `maxUploadBytes` (10 MiB by default) or more than 10 uploads waiting |
| `GET /_jasx/stream?pages=ID:N,ID:N&leave=ID,ID` | the stream of the pages of a browser's tabs, each after its version `N` (§6.1, §6.2): `text/event-stream`, a comment `: connected` once the stream is live, one frame per `data:` line, and a comment every 25 seconds to keep the connection open. A missing or malformed `N` reads as 0. The optional `leave` names pages the browser navigated away from (§6.4): the server discards those of this browser's session that the request doesn't also stream, at most 100 |

Each stream frame is `{"page": "ID", "batch": B}`, where `B` is a batch, a merged batch (§8.4), or `{"reload": true}`.

A request body that isn't valid JSON, or an event without a numeric `handler`, answers `400`. An event whose body isn't declared as `application/json` answers `415`, one over the size limit `413`, and an event, ping, upload or stream from another site `403` (§6.3). A page request without a session cookie sets one (§6.3).

**Uploads.** When an event carries a form's fields and the form has file fields with chosen files, the client first uploads each file (`POST /_jasx/upload`), one at a time, and the event's field holds `"jx-upload:"` followed by each upload's token in place of the file. A file whose upload fails is left out, and the event is sent without it. The server keeps each upload in a temporary file, bound to its page, until the event that names it has been handled (its handler reads it through `FormEvent.files`, `ui.md` §4), then deletes it; uploads whose event never comes are deleted with the page. An upload must carry the `X-Jasx-Name` header, which a page on another site can't send without the browser asking the server first.

### 8.2 Markup

| Marker | Meaning |
|---|---|
| `<!--jx:N-->` … `<!--/jx:N-->` | the content of dynamic child hole `N` |
| `data-jx-id="N"` | element `N`, which has a dynamic attribute |
| `data-jx-on="click:H input:H:300"` | the element's handlers: event type, handler id, and the debounce delay in milliseconds if any |
| `<script src="/_jasx/web.js?v=VERSION" data-jx-page="ID" data-jx-js="/_jasx/js/BUILD/" defer>` | the client script, the page id, and the base URL of the application's modules if it has any (§7.1) |
| `data-jx-ref="menu"` | a ref (§7.1) |
| `data-jx-js-click='["web/Menu.jasx.js","toggleMenu",{"menu":{"$ref":"menu"},"save":{"handler":12}}]'` | a browser attribute: the module, the function and the payload as JSON, where `{"$ref": name}` is a ref and `{"handler": H}` a Java handler. With a kept-current value, it is a dynamic attribute (`data-jx-id`), updated by `attr` patches |

Dynamic children of `script`, `style`, `title` and `textarea` are evaluated once and are not holes, because comments are not markup inside those elements.

Rows (`tr`) and dynamic children written directly in a `table` are rendered inside a `tbody`, where a browser would create one. Otherwise the browser would move the rows into a `tbody` of its own and separate a hole's start marker from its end marker. A hole directly in a table therefore holds rows; to fill a `thead` or `tfoot` from a hole, write the hole inside it.

The client parses a hole's new content as content of the hole's parent: inside `svg` (except in `foreignObject`) its elements are SVG elements, inside `math` MathML elements. If a hole's end marker is missing (the page's markup was changed by something else), the client removes nothing and reloads the page.

A hole that read no `State` or `Derived` when it first ran can never change, so it gets no markers (holes in its content still get theirs), and an element none of whose dynamic attributes read one gets no `data-jx-id`. So `{todo.title()}` costs nothing in the page.

Each item of a keyed list (§4.3) has markers of its own, `<!--jx:I-->` … `<!--/jx:I-->`, when the list can change or the item can change by itself, so that `list` patches can move it and its own `html` patches can reach it.

### 8.3 Events

```json
{"handler": 12, "type": "submit", "fields": {"title": ["Milk"]}}
{"handler": 13, "type": "keydown", "key": "Enter", "ctrl": false, "shift": false, "alt": false, "meta": false, "value": "hi", "checked": false}
```

| Member | Sent | Read by |
|---|---|---|
| `handler`, `type` | always | |
| `seq` | always: the client's number for the event, increasing by one per event of the page | a `reset` patch (§8.4) |
| `value`, `checked` | when the element with the handler is a form field | `InputEvent` |
| `values` | for a `<select multiple>`: its selected values | `InputEvent.values()` |
| `key` | for a keyboard event | `KeyEvent` |
| `button`, `x`, `y` | for a mouse event (`clientX`, `clientY`) | `MouseEvent` |
| `ctrl`, `shift`, `alt`, `meta` | for a keyboard or mouse event | `KeyEvent`, `MouseEvent` |
| `fields` | for a submit, or any event on a form: each field's values, by name, in form order; a chosen file is `"jx-upload:"` followed by its upload's token | `FormEvent` |

A member that is not sent reads as empty (`""`, `false`, `0`, no fields). An event for a handler that no longer exists (its hole re-ran) is ignored.

### 8.4 Batches and patches

```json
{"version": 7, "patches": [
  {"op": "html", "id": 3, "html": "<li>Milk</li>"},
  {"op": "attr", "id": 5, "name": "disabled", "value": true},
  {"op": "css", "css": ".todo.jx-1a2b3c4d { ... }"},
  {"op": "navigate", "url": "/done"},
  {"op": "reset", "event": 4}
]}
```

| Patch | Effect |
|---|---|
| `html` | the new content of hole `id`, which the client **merges** into the page (below): `html`, or a template's values (below) |
| `list` | the items of keyed list `id` in their new order (§4.3): `items` holds the id of each kept item, or `{"id": N, "html": "..."}` for a new one (or `{"id": N, "t": T, "v": [...]}`, below) |
| `attr` | sets attribute `name` of element `id`: a string, `true` for a bare attribute, or `null` to remove it |
| `css` | adds a style sheet the page didn't have yet |
| `tpl` | defines template `t`: its statics `s`, a list of strings (below) |
| `navigate` | goes to `url` as a link to it would (§6.4): in place for a page of the application (same origin, outside the base path, a JASX page), otherwise the ordinary way |
| `reset` | resets the form of the event whose `seq` is `event` (§7), wherever the batch arrives: in the event's answer, after an earlier batch it waited for, or in a catch-up |

Batch versions increase by one per batch of a page. The client applies batches in version order, whether they arrive on the stream or in an event response.

A **merged batch**, `{"from": A, "version": B, "patches": [...]}`, holds the patches of batches `A` to `B` in order (§6.1). An `html` patch is left out when a later patch of the merge replaces the same hole, and an `attr` patch when a later one sets the same attribute of the same element, so a browser that slept through a minute of a clock receives one value. Applying the rest gives the same page.

**Merging.** The client makes the nodes of a hole like the new content. It matches nodes in order, by kind and tag, and elements with an `id` only by that id. A matched node is updated in place, and its children are merged the same way. Unmatched new nodes are inserted and leftover old ones removed. So an element that stays keeps its DOM state: focus, typed text, scroll position, running transitions.

**Attributes are merged three ways.** The client remembers, for each element, the attributes the server last rendered for it (in the page, an `html` patch, a new list item, or an `attr` patch). When it merges, it changes an attribute only if the server's new value differs from the server's previous value; an attribute the server didn't change keeps whatever the browser did to it. So a `<details>` the user opened stays open, and classes or attributes added by a script, an extension or the client itself (busy marks) stay, until the server itself changes that attribute. `class` is merged by class name, in merges and in `attr` patches: the classes the server added are added and those it removed are removed, and the others stay. The `value` of a field with unsent edits is never changed (§7). An element the client never saw rendered is compared with what it shows, which makes its attributes those the server rendered.

**Lists.** For a `list` patch, the client first removes the items not listed, then walks the entries in order: a kept item is moved only if it isn't already in place, and a new item is inserted between its own markers. So removing or inserting an item moves no other item. If the page doesn't have a kept item, the client reloads.

In a merged batch (§6.1), an `html` patch supersedes earlier `html` and `list` patches of the same hole. A `list` patch supersedes nothing, because it refers to items created by earlier `list` patches.

**Templates.** Rendered HTML consists of *statics*, what the renderer writes itself (tags, attribute names, quotes, the text of hole markers), and *values*, what comes from the page's data (escaped text, attribute values, the ids in hole markers): `statics[0] + values[0] + statics[1] + … + statics[n]`. Content with the same statics has the same *shape*. The server numbers the shapes of a page's patch content: the first time a shape is rendered, it is sent as `html`; the second time, a `tpl` patch defines it once (`{"op": "tpl", "t": T, "s": [...]}`, before the patch that uses it), and from then on content of that shape is sent as `"t": T, "v": [values]` in place of `"html"`, in `html` patches and in new list items. The client rebuilds the HTML from the template's statics and the values, and merges it as usual. A template is used only when its values are shorter than the HTML, so a hole such as `{count}` is still sent as `html`. A page numbers at most 500 shapes. A `tpl` patch supersedes nothing and is never superseded, so a catch-up (§6.1) always carries the definitions its patches use. A client that meets an unknown template reloads the page.

Patches can be applied again harmlessly: `html` and `attr` set content and values, the client adds a style sheet only once, and a reset leaves alone the fields edited after its event. A patch whose hole or element isn't in the page (it was inside content replaced since) is ignored.

## 9. Styles

Style blocks work as on `html` (`targets/html.md` §3.5): `Web.styleSheet`, `Web.scoped`, `Web.unscoped`, and `Web.styles()` to place the sheets used by the page. Without `Web.styles()`, they are inserted before `</head>`. When a patch renders an element whose style sheet the page doesn't have yet, a `css` patch adds it.

## 10. Not yet

- **Browser code:** a design for plain JavaScript is under discussion (`docs/discussions/browser-javascript.md`).
- **Templates from the compiler:** templates are found at run time, by shape (§8.4); the lowering could mark static parts at compile time, so that text written in the source is never sent again either.
- **Warnings** JASX3001 (a state read outside a hole) and JASX3002 (a list without keys) stay reserved.
- File uploads, and restoring form input after the page is discarded.
