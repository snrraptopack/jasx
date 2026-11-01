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
| event (`onclick`, …) | `onclick(String)`, inline JavaScript | `onclick(jasx.web.Handler)`, a Java handler |

## 3. Lowering

The lowering is factory lowering (`semantics.md` §6) with these changes:

| Position (`semantics.md` §8) | Lowers to |
|---|---|
| dynamic child `{e}` | `jasx.web.Web.dynamic(() -> (e))` |
| statement child `{for ...}`, `{if ...}` | `jasx.web.Web.dynamic(() -> switch (0) { ... })` |
| dynamic attribute `a={e}` | `.a(() -> (e))` |
| handler `onclick={e}` | `.onclick((e))`, unchanged |
| prop, key | unchanged: evaluated once |

### 3.1 Captured variables

Because holes are lambdas, Java requires every local variable a hole reads to be **effectively final**, and a statement child can't assign the enclosing method's local variables. Code that compiles on `html` can fail on `web`:

```java
String label = "a";
if (x) label = "b";
return <p>{label}</p>;     // web: javac error, label is not effectively final
```

javac reports the error at its `.jasx` position. The fix is a `final` copy, or a `state`/`derived` value. A future version **SHOULD** add a note to that error explaining that `{...}` is a reactive hole on this target.

## 4. State

`jasx.web.Web` provides:

| Function | Purpose |
|---|---|
| `State<T> state(T initial)` | A reactive value: `get()`, `set(value)`, `update(fn)`. |
| `Derived<T> derived(Supplier<T> fn)` | A cached value computed from other reactive values; recomputed lazily after one of them changes. |
| `void effect(Runnable fn)` | Runs `fn` once when created, then after every update in which a value it read changed. |

**Compile time identifies holes. Run time discovers dependencies.** When a hole, a `Derived` or an effect runs, the runtime records every `State` and `Derived` it reads, and subscribes to them.

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

When a hole re-runs, everything created while it last ran (its holes, components, `Derived` values, effects and handlers) is disposed and created again. A component inside a hole therefore loses its state when that hole re-runs. Keeping keyed instances across re-runs (reconciliation by `key`) is planned (§10).

## 5. Update cycle

1. An event arrives and its handler runs. `State.set` during the handler marks the state changed; nothing re-runs yet. A `set` with a value equal to the current one (`Objects.equals`) changes nothing.
2. When the handler returns, the runtime **flushes**: it re-runs every hole subscribed (directly or through a `Derived`) to a changed state, in document order, each at most once. A hole inside another hole that re-runs is not re-run separately: it is created again with its parent.
3. Each re-run hole whose output differs from its previous output produces a patch (§8.4).
4. Effects subscribed to a changed state run after the holes. If they change state, another flush follows.
5. The patches are sent to the browser as one batch.

State changed from another thread (a scheduled job, a message listener, another page) is applied under the page's lock, flushed at once, and the batch is pushed to the browser over the page's stream (§6).

## 6. Pages

A **page** is one rendering of a root element, alive on the server while the browser shows it.

1. A request for a page renders its root to complete HTML (an `html` element, usually). The runtime inserts its client script before `</body>` (or at the end), and the page's style sheets before `</head>` unless `Web.styles()` places them (§9). The page gets an unguessable id.
2. The client script opens the page's **stream** (server-sent events), on which the server pushes batches that don't answer an event.
3. Events are sent as POST requests; the response carries the batch the event produced.
4. A page is discarded when it has had no stream connected and no event for the page timeout (2 minutes by default). An event or stream request for an unknown page answers `410 Gone`, and the client reloads the page.

## 7. Events and input

A handler is a `jasx.web.Handler`: a lambda `e -> ...` that receives a `jasx.web.Event`:

| `Event` method | Value |
|---|---|
| `type()` | the DOM event type, such as `click` |
| `value()` | the `value` of the element that has the handler, if it is a form field |
| `checked()` | whether a checkbox or radio button is checked |
| `key()` | the key of a keyboard event |
| `field(name)`, `fields(name)`, `fieldNames()` | the fields of the form, for `submit` |

**Input belongs to the browser.** Typing sends nothing to the server unless the page asks for it:

- `onsubmit` on a form sends all its fields once, when it is submitted. The form's default submission is prevented.
- `onchange` sends a field's value when it changes (for a text field, when it loses focus or Enter is pressed).
- `oninput` is **debounced by default**: the event is sent 300 ms after the last keystroke. `Web.debounce(ms, handler)` sets another delay, on any event, and `Web.immediate(handler)` sends every event.
- Before an event is sent, every pending debounced event is sent first, so a handler always sees the latest input (a submit right after typing carries the final text).
- When a patch changes the `value` of the focused field and the user has typed in it since its last event was sent, the patch doesn't change the field, so typing is never overwritten. `checked`, `selected` and `value` are set as DOM properties.
- Events are sent one at a time, in order. While its event is queued or running, an element has the class `jx-loading` and `aria-busy="true"`. A form whose submit is in progress ignores further submits.

In a handler, `e.resetForm()` clears the form the event came from (typed text belongs to the browser, so the server can't clear it by setting state), and `Web.navigate(url)` makes the browser go to `url`.

## 8. Protocol

The runtime serves its client script, events and streams under a base path, `/_jasx` by default.

### 8.1 Requests

| Request | Purpose |
|---|---|
| `GET /_jasx/web.js` | the client script |
| `POST /_jasx/event?page=ID` | an event (§8.3); answers a batch, or `204` if nothing changed |
| `GET /_jasx/stream?page=ID` | the page's stream: `text/event-stream`, one batch per `data:` line, and a comment every 25 seconds to keep the connection open |

### 8.2 Markup

| Marker | Meaning |
|---|---|
| `<!--jx:N-->` … `<!--/jx:N-->` | the content of dynamic child hole `N` |
| `data-jx-id="N"` | element `N`, which has a dynamic attribute |
| `data-jx-on="click:H input:H:300"` | the element's handlers: event type, handler id, and the debounce delay in milliseconds if any |
| `<script src="/_jasx/web.js" data-jx-page="ID" defer>` | the client script and the page id |

Dynamic children of `script`, `style`, `title` and `textarea` are evaluated once and are not holes, because comments are not markup inside those elements.

A hole that read no `State` or `Derived` when it first ran can never change, so it gets no markers (holes in its content still get theirs), and an element none of whose dynamic attributes read one gets no `data-jx-id`. So `{todo.title()}` costs nothing in the page.

### 8.3 Events

```json
{"handler": 12, "type": "submit", "value": null, "checked": false, "key": null, "fields": {"title": ["Milk"]}}
```

An event for a handler that no longer exists (its hole re-ran) is ignored.

### 8.4 Batches and patches

```json
{"version": 7, "patches": [
  {"op": "html", "id": 3, "html": "<li>Milk</li>"},
  {"op": "attr", "id": 5, "name": "disabled", "value": true},
  {"op": "css", "css": ".todo.jx-1a2b3c4d { ... }"},
  {"op": "navigate", "url": "/done"},
  {"op": "reset"}
]}
```

| Patch | Effect |
|---|---|
| `html` | replaces the content of hole `id` |
| `attr` | sets attribute `name` of element `id`: a string, `true` for a bare attribute, or `null` to remove it |
| `css` | adds a style sheet the page didn't have yet |
| `navigate` | goes to `url` |
| `reset` | resets the form the answered event came from; ignored in a pushed batch |

Batch versions increase by one per batch of a page. The client applies batches in version order, whether they arrive on the stream or in an event response.

## 9. Styles

Style blocks work as on `html` (`targets/html.md` §3.5): `Web.styleSheet`, `Web.scoped`, `Web.unscoped`, and `Web.styles()` to place the sheets used by the page. Without `Web.styles()`, they are inserted before `</head>`. When a patch renders an element whose style sheet the page doesn't have yet, a `css` patch adds it.

## 10. Not yet

- **Keyed reconciliation:** keeping component instances with the same `key` when a hole re-runs, and sending list changes as moves.
- **Client commands:** showing, hiding and toggling classes in the browser without a round trip.
- **Static templates:** sending the static parts of a hole's markup once, and only the dynamic values afterwards.
- **Warnings** JASX3001 (a state read outside a hole) and JASX3002 (a list without keys) stay reserved.
- File uploads, and restoring form input after the page is discarded.
