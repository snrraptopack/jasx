# Building interactive pages with the `web` target

This guide shows how to write pages with JASX's `web` target, using examples. The exact rules are in the specification: [`targets/web.md`](../jasx-spec/targets/web.md) for the target, and [`ui.md`](../jasx-spec/ui.md) for state and events.

- [How it works](#how-it-works)
- [Setting up](#setting-up)
- [State](#state)
- [Events](#events)
- [Forms and input](#forms-and-input)
- [Lists](#lists)
- [Pushing from the server](#pushing-from-the-server)
- [JavaScript in the browser](#javascript-in-the-browser)
- [Styles](#styles)
- [Moving between pages](#moving-between-pages)
- [Serving pages](#serving-pages)
- [Testing pages](#testing-pages)
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
    id("io.github.snrraptopack.jasx")
}

jasx { target.set("web") }

dependencies { implementation("io.github.snrraptopack:jasx-web:0.1.0-SNAPSHOT") }
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

`navigate("/done")` in a handler sends the browser to another page. A page of your application is shown in place, as a link to it would be ([Moving between pages](#moving-between-pages)); anything else is loaded the ordinary way.

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

### Files

A form's file fields work as you'd expect: when the form is submitted, the browser uploads the chosen files first, then sends the event, and the handler gets them with `e.file(name)`, `e.files(name)`, or a record component of type `UploadedFile`:

```java
record NewPhoto(String caption, UploadedFile image) {}

<form onsubmit={e -> {
    NewPhoto photo = e.as(NewPhoto.class);
    if (photo.image() != null) {
        photo.image().saveTo(storage.resolve(photo.image().name()));
    }
}}>
    <input name="caption" />
    <input type="file" name="image" accept="image/*" />
    <button>Upload</button>
</form>
```

An `UploadedFile` has `name()`, `contentType()`, `size()`, `open()`, `bytes()` and `saveTo(path)`. Its content is there while the handler runs and is deleted after it, so save what you need in the handler. A file may be up to 10 MiB (`WebApp.Limits`, or `jasx.web.max-upload-bytes` in Spring). The name comes from the user's computer: never use it as a path without checking it.

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

**Give list items a key.** Then a change re-renders only what changed:

```java
<ul>
    {for (Todo t : store.all()) {
        <TodoItem key={t.id()} todo={t} />
    }}
</ul>
```

When the list changes, an item whose key and values are unchanged is **kept**: its code doesn't run again, its components keep their state (an open menu stays open, an edit box keeps its text), and the browser doesn't touch it. Only new or changed items are sent, with the new order. Adding one to-do to a list of a thousand sends one `<li>`, and once the browser has seen the shape of an item, only its values: the server sends the markup of a repeated shape once, as a template.

An item counts as changed when the values it uses change: toggling a to-do replaces its record, so only that row is rendered again. That's why list items should be immutable values such as records. If you change an object in place, the item looks the same and isn't updated.

Without keys, the list is rendered again as a whole, and the browser merges the new HTML into the page: unchanged elements stay, with their focus and typed text. Components keep their state too, as in any hole that runs again (below), but every item's HTML is sent again; keys are what let the server send only the new ones.

### Components in a hole that runs again

When a hole runs again, a component it renders with the same props as last time is **kept**: it doesn't run again, and keeps its state, its holes and its handlers. Only a component whose props changed starts afresh.

```java
{loggedIn.get() ? <main>{user.get().name()} <Counter label="clicks" /></main> : <Login />}
```

When `user` changes, the hole runs again and `Counter` keeps its count. Props are compared with `equals` (a `State` only with itself), so pass immutable values: a list changed in place looks the same and isn't passed again.

A merge never undoes what happened in the browser unless the server changed it: a `<details>` the user opened stays open, a ticked checkbox stays ticked, and a class added by a script stays, until the server renders a different value for that attribute.

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

The browser keeps one stream open for all its tabs of your application (`GET /_jasx/stream`): one tab holds it and passes each update to the tab it is for, and when that tab closes another takes over. Nothing goes through it when nothing changes, apart from a keep-alive comment every 25 seconds.

If the connection drops (a laptop asleep, a train tunnel), the browser reconnects and tells the server the last update it applied. The server sends what was missed as one update, keeping only the latest value of each spot, so a clock that ticked sixty times while you were away sends one value. If too much was missed, or the server discarded the page, the page reloads, and what the user had typed in its forms is put back (not passwords), with `input` and `change` fired so your handlers catch up.

## JavaScript in the browser

Some things shouldn't wait for the server: opening a menu, switching a tab, copying to the clipboard, counting characters while typing, drawing a chart. Write them in plain JavaScript, and they run in the browser without a round trip. Many need no JavaScript at all: a `popover` (`<button popovertarget="menu">` with `<nav id="menu" popover="auto">`) or a `<details>` is handled by the browser itself.

**`onclick` is Java, `@click` is JavaScript.** `onclick={...}` runs a Java handler on the server; `@click="..."` runs JavaScript in the browser. The same goes for every event: `@input`, `@keydown`, `@dragover`, ... An element can have both; the JavaScript runs first.

Put the JavaScript in a `.js` file next to your `.jasx` file, and import it in a `<script>` block:

```js
// web/menu.js
export function toggleMenu(event, { menu }) {
  menu.hidden = !menu.hidden;
}
```

```java
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

**Every function gets `(event, payload)`.** In the payload:

- `$name` is a **ref**: the element marked `ref="name"`. If there are several (a list of rows, each with its own `ref="details"`), it is the one nearest to the element you clicked, so each row finds its own.
- Every other name is a **Java variable**, checked by javac: `{text: url}`, `{id: t.id()}`, or `{url}` for short. Values arrive as JSON: strings, numbers, booleans, lists, maps, records (as objects) and enums (by name).
- A `State` arrives as its value, and stays **current**: when it changes on the server, the next call gets the new value.
- A Java handler arrives as a **function that calls the server**: `await save(value)` runs it, and resolves once the page has updated. A string or number arrives as `e.value()`, an object as fields (`e.field("from")`, `e.as(Move.class)`).
- Strings in a payload are written in single quotes: `{question: 'Delete it?'}`.

```java
Action remove = () -> store.remove(t.id());
<button @click="confirmDelete({title: t.title(), remove})">Delete</button>
```

```js
export async function confirmDelete(event, { title, remove }) {
  if (window.confirm("Delete " + title + "?")) await remove();
}
```

**One line, inline.** Anything that isn't a call of one of your functions is inline JavaScript. It sees `event` and your refs:

```java
<button @click="$details.open = !$details.open">More</button>
<li @dragover="event.preventDefault()" @drop="drop({index, move})">...</li>
<button @click="window.print()">Print</button>
```

Browser functions are written `window.alert(...)`, `window.print()`: a bare `alert(...)` looks like a call of your own function, and the build fails if your script has none by that name. Inline code can't see Java variables; for those, call a function with a payload.

**Following server state: `@render`.** `@render` runs when the element appears, and again whenever a `State` in its payload changes:

```java
<div ref="chart" data-jx-ignore @render="drawChart({chart: $chart, points})"></div>
```

```js
export function drawChart(event, { chart, points }) {
  const instance = new Chart(chart, { type: "line", data: { datasets: [{ data: points }] } });
  return () => instance.destroy();
}
```

`data-jx-ignore` keeps the server's updates out of what your JavaScript draws inside the element.

**Cleanups.** Any function may return a function: its cleanup. It runs before the same attribute runs again on the element (so a second click stops the first click's timer), and when the element leaves the page, whether an update removes it or the user moves to another page.

**What reaches the browser.** The build packages your `.js` files and what it generates from your `<script>` blocks; the server serves them under `/_jasx/js/`, cached for good, and the browser loads each one when a page first needs it. Your JavaScript never runs from a string, so a strict Content Security Policy works. Imports between your files work as in any ES module; for npm packages, import from a URL or map their names with an import map in your layout's `<head>`.

[`examples/web`](../examples/web) has a page with tabs, a copy button, a confirmed reset, a character counter and a chart (`Browser.jasx`, `browser.js`).

## Styles

A `<style>` block in a `.jasx` file is scoped to that file's markup, as with every target. The page gets the style sheets it uses in its `<head>`, or where you place `{styles()}`. A style sheet first needed by an update is sent with it.

So keep each view's styles in its own file, next to its markup, and write them with native CSS nesting, which scoping supports:

```css
.task {
    display: flex;

    & a {
        flex: 1;
    }

    &.done a {
        text-decoration: line-through;
    }
}
```

Styles every view shares, such as those of form controls, go in the layout's file as `:global(...)` rules. A rule nested inside a `:global` rule is scoped to its file again, so write a shared state as its own rule: `:global(button:hover) { ... }`. [`examples/web-spring`](../examples/web-spring) has one file per view (`Layout.jasx`, `BoardView.jasx`, `TaskView.jasx`), each with its own styles.

## Moving between pages

Links between your pages need nothing special. A plain click on `<a href="/tasks/2">` fetches that page and shows it in place: the browser keeps its connection and scripts, swaps in the new page's body, title and styles, and updates the history, so back and forward work. It feels like a single-page application, while each page is still an ordinary server-rendered page, with its own URL.

The browser loads a link the ordinary way when it opens a new window (`target="_blank"`, ctrl+click), downloads (`download`), leaves your site, or answers with something other than a page (a PDF, JSON). Mark a link, or an element around links, with `data-jx-reload` to always load it the ordinary way.

The page you leave is discarded on the server at once (going back renders it anew), and the client script is cached under its version, so a move between pages costs one request for the page and one for its stream.

## Serving pages

The runtime is independent of any HTTP server. An adapter describes each request with a `WebApp.Request` (a query parameter, a header, and whether it came over HTTPS) and sends back the `Response` it gets (status, headers, body):

| Request | `WebApp` method |
|---|---|
| a page | `render(request, () -> <Page />)` |
| `POST /_jasx/event?page=ID` | `event(request, body)` |
| `GET /_jasx/stream?pages=ID:N,...` | `stream(request, eventStream)` writes server-sent events until the browser leaves |
| `POST /_jasx/ping?page=ID` | `ping(request)` |

The client script is `WebApp.clientScript()`, served at `/_jasx/web.js`. `WebServer` is the adapter for the JDK's HTTP server.

**With Spring Boot** (3.x with `spring-boot-starter-web`, or 4.x with `spring-boot-starter-webmvc`), add `io.github.snrraptopack:jasx-web-spring-boot-starter`. Pages are ordinary controller methods that return their markup:

```java
@Controller
class Pages {
    private final TodoStore store;

    Pages(TodoStore store) {
        this.store = store;
    }

    @GetMapping("/")
    Element home() {
        return <Home />;
    }

    @GetMapping("/todos/{id}")
    Element todo(@PathVariable long id) {
        return <TodoDetail todo={store.get(id)} />;
    }
}
```

A method that returns an `Element` runs inside a new page: Spring binds its arguments first, then the starter runs the method body within the page, so the state its components create belongs to that page. Routing, path variables, request parameters, dependency injection, exceptions (a `ResponseStatusException` is still a 404) and Spring Security work as usual.

The starter does this through Spring Boot's `WebMvcRegistrations`, of which an application can have only one. If yours defines its own, return a `WebPage` instead, a function the starter calls inside the page: `WebPage home() { return () -> <Home />; }`. The starter serves the runtime's requests under `/_jasx` (on async requests, so open streams hold no threads), takes `server.servlet.context-path` into account, and closes pages when the application stops. Settings:

```properties
jasx.web.base-path=/_jasx
jasx.web.page-timeout=2m
jasx.web.first-connect-timeout=30s
jasx.web.max-event-bytes=1048576
jasx.web.max-pages-per-session=50
jasx.web.max-upload-bytes=10485760
```

[`examples/web-spring`](../examples/web-spring) is a complete application: `./gradlew -p examples/web-spring run`.

With Spring Security, let the runtime's requests through Spring's CSRF check; the runtime protects them its own way (above): `http.csrf(csrf -> csrf.ignoringRequestMatchers("/_jasx/**"))`.

**On a servlet container** (Tomcat 10.1+, Jetty 12, Undertow: Jakarta Servlet 6), add `io.github.snrraptopack:jasx-web-servlet` and register a `JasxServlet` with your pages:

```java
@WebListener
public class Pages implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent event) {
        JasxServlet servlet = new JasxServlet(new WebApp())
                .page("/", () -> <Home />)
                .page("/todos", request -> <Todos filter={request.getParameter("filter")} />);
        ServletRegistration.Dynamic registration = event.getServletContext().addServlet("jasx", servlet);
        registration.addMapping("/*");
        registration.setAsyncSupported(true); // open streams then don't hold the container's threads
    }
}
```

If your application has a context path, put it in the base path: `new WebApp("/shop/_jasx", Duration.ofMinutes(2))`. A framework that routes pages itself uses `ServletAdapter`: `handle(app, request, response)` serves the runtime's own requests, and `render(app, request, response, () -> <Page />)` renders a page.

A page is kept for 2 minutes after its browser goes away (no stream and no event). After that, a browser that comes back reloads it. A page whose browser never connects (a crawler, a link preview, a prefetch, `curl`) is dropped after 30 seconds. `new WebApp(basePath, pageTimeout, firstConnectTimeout)` changes both.

**Security comes built in**, whatever server you use:

- **Sessions.** The first page a browser gets sets a `jasx_session` cookie, and each page answers only the browser that rendered it. Someone who copies a page id from your screen or a log can't use it.
- **Other sites.** Events must be JSON, and requests the browser marks as coming from another site are refused, so another site can't make your browser click your buttons.
- **Limits.** Event bodies are limited to 1 MiB, and one browser keeps at most 50 pages alive (its oldest is dropped first). `new WebApp(basePath, pageTimeout, firstConnectTimeout, new WebApp.Limits(maxEventBytes, maxPagesPerSession))` changes both.

Pages need cookies to work. Rate limiting is up to your reverse proxy.

**Many tabs over HTTP/1.1.** Browsers open only about six connections per server over HTTP/1.1, which is why the tabs share one stream. Sharing needs Web Locks and BroadcastChannel, which every current browser has, and a secure context: HTTPS, or `localhost` while developing. On plain HTTP from another host (a phone on your network trying `http://192.168.1.5:8080`), each tab keeps its own stream, and hidden tabs close theirs after 10 seconds. HTTP/2 removes the limit entirely, so prefer it in production. The JDK's built-in server (`WebServer`) speaks only HTTP/1.1: put a reverse proxy with HTTP/2 in front of it, or use a server adapter that supports HTTP/2.

### Several instances, deploys and restarts

**A page lives in the server that rendered it.** Its state, holes and handlers are objects in that JVM, so a browser's events and stream must reach the same instance. With one instance there is nothing to do. With several behind a load balancer, turn on **cookie stickiness**, keyed on the `jasx_session` cookie the runtime already sets, so every request of a browser goes to the same instance:

- **nginx:** `hash $cookie_jasx_session consistent;` in the `upstream` block.
- **AWS Application Load Balancer:** target group stickiness, application-based, with the cookie name `jasx_session`.
- **Kubernetes (ingress-nginx):** the annotations `nginx.ingress.kubernetes.io/affinity: "cookie"` and `nginx.ingress.kubernetes.io/session-cookie-name: "jasx_session"`, or `upstream-hash-by: "$cookie_jasx_session"`.

A browser without the cookie yet (its very first page) may land anywhere; the page it gets sets the cookie, and the browser sticks from then on.

**Deploy by draining.** In a rolling deploy, stop sending new browsers to an old instance, let it go on serving its open pages for a while (a few minutes is plenty: pages are short-lived), then stop it. Most users never notice. With Spring Boot, the starter closes the pages when the application starts to stop, so graceful shutdown doesn't wait for open streams; set `server.shutdown=graceful` and a load balancer deregistration delay for the draining.

**What happens when a page is lost.** If a browser comes back to a server that no longer has its page (it was restarted, or the page timed out), the stream tells it so, and the browser reloads the page:

1. Before reloading, it saves the values of the page's named form fields (not passwords, hidden fields or files).
2. It loads the page again, which renders it afresh on the server it now reaches.
3. It puts the saved values back, and fires `input` and `change` on each field, so your handlers catch up.

So a user in the middle of a form keeps what they typed. What can't come back is state that lived only in the lost JVM: a `State` holding a counter or the step of a wizard starts from its initial value. Keep state that must survive a restart in the URL (a query parameter, such as `?step=3` or `?tab=billing`) or in your database, and use `State` for what the screen shows while the page is open.

## Testing pages

`io.github.snrraptopack:jasx-web-test` tests pages without a browser, in plain JUnit tests. `TestPage` renders a component, then clicks, types and submits as a user would, and reads the page as it is after each update:

```java
class BoardTest {
    private final TaskService tasks = new TaskService();

    @Test
    void addingATaskShowsIt() {
        try (TestPage page = TestPage.render(() -> BoardView.Board(tasks, "all"))) {
            page.submit("form", Map.of("title", "Write tests"));
            assertEquals("Write tests", page.findAll("ul > li a").get(3).text());
            assertEquals("", page.find("input[name=title]").value()); // the form was reset
        }
    }

    @Test
    void aChangeInOneWindowReachesTheOthers() {
        try (TestPage mine = TestPage.render(() -> BoardView.Board(tasks, "all"));
             TestPage theirs = TestPage.render(() -> BoardView.Board(tasks, "all"))) {
            mine.click(".filters button", "Done");                      // a selector and the button's text
            mine.click("ul > li input[type=checkbox]");
            theirs.await(() -> assertTrue(theirs.text("p.hint").startsWith("3 open.")));
        }
    }
}
```

| Method | What it does |
|---|---|
| `click(selector)`, `click(selector, text)` | clicks; a checkbox toggles, a submit button submits its form |
| `type(selector, text)` | types into a field (fires `input`) |
| `change(selector, value)` | chooses a select's option, or sets a field (fires `input` and `change`) |
| `submit(selector)`, `submit(selector, fields)` | fills the named fields of the form (or of the form holding the element), then submits it |
| `keydown(selector, key)`, `fire(selector, type)` | a key press; any event |
| `attach(selector, name, type, bytes)` | chooses a file in a file field; the next submit uploads it |
| `call(selector, event, name)`, with a value or a map of fields | calls a Java handler that an `@event` attribute passes to its JavaScript, as the JavaScript would |
| `find`, `findAll`, `has`, `text`, `html` | read the page; an element gives `text()`, `attribute(name)`, `hasClass(name)`, `value()`, `checked()` |
| `await(() -> ...)` | repeats an assertion until it passes, for what the server pushes from elsewhere |
| `navigatedTo()` | where `navigate(url)` sent the browser |

Selectors are CSS: tags, `#id`, `.class`, `[attr]`, `[attr=value]`, descendant and `>` combinators, and lists separated by commas. A mistake fails with the page's HTML in the message.

Each `TestPage` is a separate browser with its own session, going through the same requests a browser sends, so the page's handlers, holes, keyed lists and security checks all run for real. Events are sent at once (no 300 ms debounce to wait for). JavaScript isn't run: for `<button @click="confirmThen({question: 'Reset?', action: reset})">`, the function's `await action()` is `page.call("button", "click", "action")`, and `await save("Milk")` is `page.call("button", "click", "save", "Milk")`. Pass an application's own `WebApp` (a Spring bean, say) with `TestPage.render(app, () -> ...)`. [`examples/web-spring`](../examples/web-spring) has a complete test.

## Pitfalls

- **Local variables read in a hole must not be reassigned.** Every `{...}` is a Java lambda on this target, so this doesn't compile:

  ```java
  String label = "a";
  if (x) label = "b";
  return <p>{label}</p>;   // error: label is not effectively final
  ```

  Use a `final` copy, a ternary, or a `state`.
- **Reading state outside a hole doesn't follow it.** `var n = count.get();` in the component body is read once.
- **A component whose props changed is created again**, with fresh state, when the hole around it runs again. Keep state that must survive a change of props higher up, or in a store.
- **A component runs when its element is rendered**, not when the markup is evaluated: one that is never rendered never runs, and the components you pass as children run where the parent places them.
- **Keys in a classic `for (int i ...)` loop** need a final copy of `i`: a keyed item is a function, so what it reads must be effectively final.
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
