# Building interactive pages with the `web` target

This guide shows how to write pages with JASX's `web` target, using examples. The exact rules are in the specification: [`targets/web.md`](../jasx-spec/targets/web.md) for the target, and [`ui.md`](../jasx-spec/ui.md) for state and events.

- [How it works](#how-it-works)
- [Setting up](#setting-up)
- [State](#state)
- [Events](#events)
- [Forms and input](#forms-and-input)
- [Lists](#lists)
- [Pushing from the server](#pushing-from-the-server)
- [Styles](#styles)
- [Serving pages](#serving-pages)
- [Pitfalls](#pitfalls)
- [Reference](#reference)

---

## How it works

A page is rendered on the server and stays alive there while the browser shows it. Your components run on the server, once. Each `{...}` in markup is a **hole**. When a hole reads a `State`, it runs again when that state changes, and the browser receives only the new content of that hole.

```java
import static jasx.web.Web.*;

public Element Counter() {
    var count = state(0);                                   // runs once
    return (
        <div>
            <button onclick={() -> count.update(n -> n + 1)}>+1</button>
            <p>Clicked {count} times</p>                     // {count} is a hole
        </div>
    );
}
```

A click on the button:

1. The browser sends one small POST request.
2. The lambda runs on the server.
3. The server answers with the new text of `{count}`, and nothing else.

There's no API to write and no JavaScript.

## Setting up

With Gradle:

```kotlin
plugins {
    java
    id("dev.jasx")
}

jasx { target.set("web") }

dependencies { implementation("dev.jasx:jasx-web:0.1.0-SNAPSHOT") }
```

A small server, on the JDK's own HTTP server:

```java
import jasx.web.server.WebServer;

public static void main(String[] args) throws Exception {
    WebServer.create(8080)
            .page("/", () -> <Counter />)
            .page("/todos", request -> <Todos filter={request.query("filter")} />)
            .start();
}
```

Each request for a page renders a new instance of it, with its own state.

## State

| Function | What it gives |
|---|---|
| `state(initial)` | A value that holes follow: `get()`, `set(v)`, `update(fn)` |
| `derived(() -> ...)` | A value computed from others, recomputed only when they change |
| `effect(() -> ...)` | Code that runs again whenever a value it reads changes |

```java
var items = state(List.<Item>of());
var total = derived(() -> items.get().stream().mapToInt(Item::price).sum());
effect(() -> log.info("total is now " + total.get()));
```

In markup, `{count}` and `{count.get()}` are the same: both show the value and follow it.

**Components run once.** Only holes, `derived` and `effect` are re-run. So a condition must be inside a hole to follow state:

```java
if (loggedIn.get()) return <Dashboard />;                          // decided once, when rendered
return <main>{loggedIn.get() ? <Dashboard /> : <Login />}</main>;  // follows loggedIn
```

**State can be shared.** A `State` kept in a service or a static field, and shown on several pages, updates all of them when it changes, even when it is set from another thread (see [Pushing from the server](#pushing-from-the-server)).

## Events

An event attribute takes a lambda that runs on the server. If the handler doesn't need its event, write `() -> ...`:

```java
<button onclick={() -> count.set(0)}>Reset</button>
```

If it needs the event, write `e -> ...`. The type of `e` depends on the attribute, so your editor and the compiler know what it has:

| Attribute | `e` is a | What you can read |
|---|---|---|
| `onsubmit` | `FormEvent` | `e.field("name")`, `e.fields("tags")`, `e.as(Record.class)`, `e.resetForm()` |
| `oninput`, `onchange`, `onfocus`, `onblur` | `InputEvent` | `e.value()`, `e.checked()`, `e.values()` |
| `onkeydown`, `onkeyup` | `KeyEvent` | `e.key()`, `e.ctrl()`, `e.shift()`, `e.alt()`, `e.meta()`, and `e.value()` |
| `onclick`, `ondblclick`, `onmousedown`, … | `MouseEvent` | `e.button()`, `e.x()`, `e.y()`, and the modifier keys |
| any other | `Event` | `e.type()` |

```java
<input onkeydown={e -> {
    if (e.key().equals("Enter")) {
        chat.send(e.value());
    }
}} />
```

`onclick={e -> e.field("x")}` doesn't compile, because a click has no form fields. The error appears at that line of your `.jasx` file.

A handler can throw. The error is logged and the page stays usable; changes made before the exception are still sent.

`navigate("/done")` in a handler sends the browser to another page.

## Forms and input

**What you type stays in the browser** until the page needs it.

- **A form sends its fields once, when it is submitted.** The browser's own submission is prevented.

  ```java
  <form onsubmit={e -> {
      store.add(e.field("title"));
      e.resetForm();                       // clears the form
  }}>
      <input name="title" required />
      <button>Add</button>
  </form>
  ```

- **`e.as(...)` reads the fields into a record.** Each component is read from the field of the same name, and converted:

  ```java
  record NewTodo(String title, int priority, boolean urgent, LocalDate due) {}

  <form onsubmit={e -> store.add(e.as(NewTodo.class))}>
      <input name="title" />
      <input name="priority" type="number" />
      <input name="urgent" type="checkbox" />
      <input name="due" type="date" />
  </form>
  ```

  A checkbox gives `true` when it is checked. A number field left empty gives `null` for an `Integer`, and an error for an `int`. A value that can't be converted is an `IllegalArgumentException` naming the field. Validation in the record's constructor works as usual.

- **`oninput` is sent 300 ms after the user stops typing**, not on every key:

  ```java
  <input type="search" oninput={e -> query.set(e.value())} />                  // after 300 ms
  <input type="search" oninput={debounce(800, e -> query.set(e.value()))} />   // after 800 ms
  <input oninput={immediate(e -> preview.set(e.value()))} />                   // every key
  ```

- **`onchange`** sends a field's value when it changes: for a text field, when it loses focus or Enter is pressed; for a checkbox or select, at once.
- **Order is kept.** Typing still waiting to be sent goes before a click or a submit, so the server always has the latest text.
- **Typing is never overwritten.** If the server changes the `value` of the field the user is typing in, the browser keeps what the user typed.
- **No double submits.** While its event is being handled, an element has the class `jx-loading` and `aria-busy="true"`, and a form ignores a second submit. Style it to show progress:

  ```css
  .jx-loading { opacity: .6; cursor: progress; }
  ```

## Lists

A statement child loops in markup:

```java
<ul>
    {for (Todo t : store.all()) {
        if (t.hidden()) continue;
        <li class={t.done() ? "done" : null}>
            <input type="checkbox" checked={t.done()} onchange={() -> store.toggle(t.id())} />
            {t.title()}
        </li>
    }}
</ul>
```

The loop reads `store.all()`, a state, so it runs again when the list changes. `{t.title()}` reads no state, so it costs nothing extra in the page.

In this version, the whole list is sent again when it changes, and any component state inside it is lost. Keyed updates are planned.

## Pushing from the server

State set outside an event, from a scheduled job, a message listener or another user's action, is pushed to every open page that shows it, over server-sent events:

```java
public final class Clock {
    final State<String> time = state(now());

    Clock(ScheduledExecutorService scheduler) {
        scheduler.scheduleAtFixedRate(() -> time.set(now()), 1, 1, TimeUnit.SECONDS);
    }
}

<p>It is {clock.time}</p>
```

The browser keeps one stream open per page (`GET /_jasx/stream`). Nothing goes through it when nothing changes, apart from a keep-alive comment every 25 seconds.

If the connection drops (a laptop asleep, a train tunnel), the browser reconnects and tells the server the last update it applied. The server sends what was missed as one update, keeping only the latest value of each spot, so a clock that ticked sixty times while you were away sends one value. If too much was missed, the page reloads.

## Styles

A `<style>` block in a `.jasx` file is scoped to that file's markup, as with every target. The page gets the style sheets it uses in its `<head>`, or where you place `{styles()}`. A style sheet first needed by an update is sent with it.

## Serving pages

The runtime is independent of any HTTP server. `WebApp` has three methods, one per kind of request:

| Request | `WebApp` method |
|---|---|
| a page | `render(() -> <Page />)` returns its HTML |
| `POST /_jasx/event?page=ID` | `event(pageId, body)` returns the status and body |
| `GET /_jasx/stream?page=ID` | `stream(pageId, sink)` writes server-sent events until the browser leaves |

The client script is `WebApp.clientScript()`, served at `/_jasx/web.js`. `WebServer` is the adapter for the JDK's HTTP server. Adapters for servlet containers and Spring Boot are planned.

A page is kept for 2 minutes after its browser goes away (no stream and no event). After that, a browser that comes back reloads it. A page whose browser never connects (a crawler, a link preview, a prefetch, `curl`) is dropped after 30 seconds. `new WebApp(basePath, pageTimeout, firstConnectTimeout)` changes both.

**Use HTTP/2 in production.** Each visible tab keeps one stream open, and over HTTP/1.1 browsers open only about six connections per server. Hidden tabs close their stream after 10 seconds and ping instead, which helps, but HTTP/2 removes the limit. The JDK's built-in server (`WebServer`) speaks only HTTP/1.1: put a reverse proxy with HTTP/2 in front of it, or use a server adapter that supports HTTP/2.

## Pitfalls

- **Local variables read in a hole must not be reassigned.** Every `{...}` is a Java lambda on this target, so this doesn't compile:

  ```java
  String label = "a";
  if (x) label = "b";
  return <p>{label}</p>;   // error: label is not effectively final
  ```

  Use a `final` copy, a ternary, or a `state`.
- **Reading state outside a hole doesn't follow it.** `var n = count.get();` in the component body is read once.
- **A field inside a hole that re-runs loses its focus and unsent text**, because the hole's content is replaced. Keep fields outside the holes that change, as the to-do example does with its form.
- **A component inside a hole that re-runs is created again**, with fresh state. Keep state that must survive higher up, or in a store.
- **A hole directly in a `<table>` holds rows.** It is placed in a `<tbody>`, as the browser would do. To fill a `<thead>` from a hole, put the hole inside the `<thead>`.
- **If a hole throws while updating**, the error is logged and the hole keeps showing what it showed, with working buttons. It tries again on the next change.
- **`Boolean` values render as text**, as on every JASX target: `{done}` shows `true`. Use `{done ? "✓" : ""}`.

## Reference

| `jasx.web.Web` | |
|---|---|
| `state(v)`, `derived(fn)`, `effect(fn)`, `untracked(fn)` | reactive values (`jasx.ui`) |
| `debounce(ms, handler)`, `immediate(handler)` | when an event is sent |
| `navigate(url)` | sends the browser elsewhere, from a handler |
| `styles()` | where the page's style sheets go |
| `rawHtml(html)` | trusted HTML, not escaped |

| `jasx.ui` | |
|---|---|
| `State<T>` | `get()`, `set(v)`, `update(fn)`, `peek()` |
| `Derived<T>` | `get()` |
| `Action` | `() -> ...` |
| `Handler<E>` | `e -> ...` |
| `Event`, `InputEvent`, `KeyEvent`, `MouseEvent`, `FormEvent` | see [Events](#events) |
