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
  "styles": "css"
}
```

The schema is the schema of the `html` target (`targets/html.md` §2), with the same elements, attributes and event attributes.

## 2. Attribute builders

The builders (`jasx.web.attrs.<Tag>`) are generated from the schema, like those of `html`, with one more overload per attribute for its dynamic form:

| Attribute | Static value | Dynamic value (§3) |
|---|---|---|
| boolean (`disabled`) | `disabled(boolean)` | `disabled(Supplier<Boolean>)` |
| other (`class`, `value`, …) | `className(Object)` | `className(Supplier<?>)` |
| event (`onclick`, …) | `onclick(String)`, inline JavaScript | `onclick(jasx.ui.Action)` and `onclick(jasx.ui.Handler<? super MouseEvent>)`, a Java handler of the event's kind (`ui.md` §4) |

## 3. Lowering

The lowering is factory lowering (`semantics.md` §6) with these changes:

| Position (`semantics.md` §8) | Lowers to |
|---|---|
| dynamic child `{e}` | `jasx.web.Web.dynamic(() -> (e))` |
| statement child `{for ...}`, `{if ...}` | `jasx.web.Web.dynamic(() -> switch (0) { ... })` |
| dynamic attribute `a={e}` | `.a(() -> (e))` |
| handler `onclick={e}` | `.onclick((e))`, unchanged |
| keyed element `<li key={k}>...</li>` | `jasx.web.Web.keyed((k), () -> element)`: the key is evaluated once, the element is a function (§4.3) |
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

A component method runs **once per instance**, when its element is rendered. Code in its body outside holes, `derived`, `effect` and handlers is not reactive:

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

When a hole re-runs, everything created while it last ran (its holes, components, `Derived` values, effects and handlers) is disposed and created again, except the items of a keyed list that are kept (§4.3). A component inside a hole therefore loses its state when that hole re-runs, unless it is in a kept keyed item.

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

### 6.2 One stream for every tab

Over HTTP/1.1, browsers open only about six connections per server, and every open stream holds one, so a few tabs of an application could block each other. The tabs of a browser therefore share one stream:

1. The tab holding the Web Lock `jasx-stream:<base path>` is the **leader**. It opens the stream for every tab's page, `stream?pages=A:12,B:7` (its own page first), and passes each frame to the tab it is for over the BroadcastChannel of the same name. Each frame names its page (§8.1).
2. Every other tab tells the leader its page and last applied version when it starts, when it needs to catch up (§6.1), and when the leader asks. The leader reconnects with the new list when a tab joins, asks to catch up, or leaves (on `pagehide`). It waits 50 ms first, so that tabs joining together cost one reconnection.
3. When the leader closes, the lock passes to another tab. The new leader asks every tab for its version, then opens the stream, and the server catches each page up (§6.1).
4. Every 30 seconds the leader asks every tab whether it is still there. A tab silent for 75 seconds (crashed or frozen) is dropped from the stream, so its page can expire. A tab that is shown again tells the leader its page, in case it was dropped.
5. On the server, a page is streamed by one connection at a time: a newer connection that names the page takes it, and a connection left with no page ends. A stream request names at most 100 pages; the server ignores the rest.

A browser without Web Locks or BroadcastChannel gives each tab its own stream. Web Locks need a secure context, so this is also what happens on a page served over plain HTTP from a host other than `localhost`. Serving over HTTP/2 removes the connection limit altogether; the JDK's HTTP server (`WebServer`) supports only HTTP/1.1, so production deployments **SHOULD** use HTTP/2 (through their server or a reverse proxy).

## 7. Events and input

Handlers and events are `jasx.ui`'s (`ui.md` §4): a handler is an `Action` (`() -> ...`) or a `Handler` of the event's kind (`e -> ...`). `onsubmit` gives a `FormEvent`, `oninput` and `onchange` an `InputEvent`, `onkeydown` a `KeyEvent`, `onclick` a `MouseEvent`, and every other event attribute a plain `Event`. A handler runs on the server with the page's lock held.

**Input belongs to the browser.** Typing sends nothing to the server unless the page asks for it:

- `onsubmit` on a form sends all its fields once, when it is submitted. The form's default submission is prevented.
- `onchange` sends a field's value when it changes (for a text field, when it loses focus or Enter is pressed).
- `oninput` is **debounced by default**: the event is sent 300 ms after the last keystroke. `Web.debounce(ms, handler)` sets another delay, on any event, and `Web.immediate(handler)` sends every event (both accept a `Handler` or an `Action`).
- Before an event is sent, every pending debounced event is sent first, so a handler always sees the latest input (a submit right after typing carries the final text).
- **Typing is never overwritten.** Each edit of a field gives it a new revision, and each event remembers the revisions of the fields it carries (the field with the handler, and a form's fields). A field has *unsent edits* while it has a revision no sent event carried. A patch doesn't change the `value` of a field with unsent edits if the server will receive them (the field has a handler, or its form has one) or the field is focused. So the answer to an older event never overwrites newer typing, even when that event waited in the queue. `checked`, `selected` and `value` are set as DOM properties.
- **A hole that re-runs is merged into the page** (§8.4): elements that stay keep their focus and typed text, and the kept items of a keyed list aren't touched (§4.3). Component state inside a plain hole is still created again (§4.2).
- Events are sent one at a time, in order. While its event is queued or running, an element has the class `jx-loading` and `aria-busy="true"`. A form whose submit is in progress ignores further submits.

In a handler, `e.resetForm()` clears the form the event came from (typed text belongs to the browser, so the server can't clear it by setting state): each field returns to its initial value, except a field edited after the event was sent, which keeps the new text. `Web.navigate(url)` makes the browser go to `url`.

## 8. Protocol

The runtime serves its client script, events and streams under a base path, `/_jasx` by default.

### 8.1 Requests

| Request | Purpose |
|---|---|
| `GET /_jasx/web.js` | the client script |
| `POST /_jasx/event?page=ID` | an event (§8.3); answers a batch, or `204` if nothing changed |
| `POST /_jasx/ping?page=ID` | a hidden tab keeping its page alive (§6); answers `204`, or `410` for an unknown page |
| `GET /_jasx/stream?pages=ID:N,ID:N` | the stream of the pages of a browser's tabs, each after its version `N` (§6.1, §6.2): `text/event-stream`, a comment `: connected` once the stream is live, one frame per `data:` line, and a comment every 25 seconds to keep the connection open. A missing or malformed `N` reads as 0 |

Each stream frame is `{"page": "ID", "batch": B}`, where `B` is a batch, a merged batch (§8.4), or `{"reload": true}`.

A request body that isn't valid JSON, or an event without a numeric `handler`, answers `400`.

### 8.2 Markup

| Marker | Meaning |
|---|---|
| `<!--jx:N-->` … `<!--/jx:N-->` | the content of dynamic child hole `N` |
| `data-jx-id="N"` | element `N`, which has a dynamic attribute |
| `data-jx-on="click:H input:H:300"` | the element's handlers: event type, handler id, and the debounce delay in milliseconds if any |
| `<script src="/_jasx/web.js" data-jx-page="ID" defer>` | the client script and the page id |

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
| `fields` | for a submit, or any event on a form: each field's values, by name, in form order | `FormEvent` |

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
| `html` | the new content of hole `id`, which the client **merges** into the page (below) |
| `list` | the items of keyed list `id` in their new order (§4.3): `items` holds the id of each kept item, or `{"id": N, "html": "..."}` for a new one |
| `attr` | sets attribute `name` of element `id`: a string, `true` for a bare attribute, or `null` to remove it |
| `css` | adds a style sheet the page didn't have yet |
| `navigate` | goes to `url` |
| `reset` | resets the form of the event whose `seq` is `event` (§7), wherever the batch arrives: in the event's answer, after an earlier batch it waited for, or in a catch-up |

Batch versions increase by one per batch of a page. The client applies batches in version order, whether they arrive on the stream or in an event response.

A **merged batch**, `{"from": A, "version": B, "patches": [...]}`, holds the patches of batches `A` to `B` in order (§6.1). An `html` patch is left out when a later patch of the merge replaces the same hole, and an `attr` patch when a later one sets the same attribute of the same element, so a browser that slept through a minute of a clock receives one value. Applying the rest gives the same page.

**Merging.** The client makes the nodes of a hole like the new content. It matches nodes in order, by kind and tag, and elements with an `id` only by that id. A matched node is updated in place: its attributes are set and removed as rendered (except the `value` of a field with unsent edits, §7, and the busy marks of an element whose event is running), and its children are merged the same way. Unmatched new nodes are inserted and leftover old ones removed. So an element that stays keeps its DOM state: focus, typed text, scroll position, running transitions.

**Lists.** For a `list` patch, the client first removes the items not listed, then walks the entries in order: a kept item is moved only if it isn't already in place, and a new item is inserted between its own markers. So removing or inserting an item moves no other item. If the page doesn't have a kept item, the client reloads.

In a merged batch (§6.1), an `html` patch supersedes earlier `html` and `list` patches of the same hole. A `list` patch supersedes nothing, because it refers to items created by earlier `list` patches.

Patches can be applied again harmlessly: `html` and `attr` set content and values, the client adds a style sheet only once, and a reset leaves alone the fields edited after its event. A patch whose hole or element isn't in the page (it was inside content replaced since) is ignored.

## 9. Styles

Style blocks work as on `html` (`targets/html.md` §3.5): `Web.styleSheet`, `Web.scoped`, `Web.unscoped`, and `Web.styles()` to place the sheets used by the page. Without `Web.styles()`, they are inserted before `</head>`. When a patch renders an element whose style sheet the page doesn't have yet, a `css` patch adds it.

## 10. Not yet

- **Client commands:** showing, hiding and toggling classes in the browser without a round trip.
- **Static templates:** sending the static parts of a hole's markup once, and only the dynamic values afterwards.
- **Warnings** JASX3001 (a state read outside a hole) and JASX3002 (a list without keys) stay reserved.
- File uploads, and restoring form input after the page is discarded.
