# Target: `live` (deferred, not decided)

**Spec version:** 1.0-draft
**Status:** **Deferred. Not normative. Not part of current work.**

> Nothing in this document has been decided. It records ideas carried over from the design proposal so that they aren't lost. The current goal is the core language: JASX as JSX is without React (`semantics.md`). Whether JASX will have a `live` target, and what it would look like, will be decided after the core language is finished. Implementations **MUST NOT** treat this document as a requirement, and the core specification **MUST NOT** depend on it.

The idea is a server-driven UI, in the same family as Phoenix LiveView, Laravel Livewire and Datastar. Components would run on the server, event handlers would be Java lambdas executed on the server, and the browser would receive fine-grained patches. Such a target would use a **custom lowering** (`semantics.md` §7.4), which shows that the target interface can support it. The transport, patch protocol and session model are not specified.

---

## 1. Descriptor

```json
{
  "id": "live",
  "specVersion": "1.0",
  "factory": "jasx.live.Live",
  "attributes": "jasx.live.attrs",
  "schema": "schemas/html-live.json",
  "lowering": "jasx.live.compiler.LiveLowering",
  "eventHandlers": "java",
  "reactive": true
}
```

(`reactive` is not a core descriptor field; a compiler that doesn't know it ignores it, `semantics.md` §7.2.)

The schema is the HTML schema of `targets/html.md`, with event attributes (`onClick`, `onInput`, `onSubmit`, …) of kind `event` that accept Java handlers such as `Consumer<jasx.event.ClickEvent>`.

---

## 2. Reactive API

The factory class adds:

| Function | Purpose |
|---|---|
| `State<T> state(T initial)` | Create a reactive value. |
| `Computed<T> computed(Supplier<T> fn)` | Create a derived, cached reactive value. |
| `void effect(Runnable fn)` | Run a side effect after updates that change its dependencies. |
| `Element slot(Supplier<?> fn)` | A reactive child boundary. |

---

## 3. Boundaries

> **Compile time identifies boundaries. Runtime discovers dependencies.**

The compiler does not determine which state a boundary reads. When a boundary runs, the runtime records every `State` and `Computed` read during its execution and subscribes the boundary to them.

### 3.1 Which positions are boundaries

Boundaries would be exactly the core's **dynamic children** and **dynamic attributes** (`semantics.md` §8). Handlers, props and keys are evaluated once.

| Position | Boundary? |
|---|---|
| An expression child value (`{...}` as a child) | Yes |
| An expression container as the value of an intrinsic attribute that is **not** an event attribute | Yes |
| An expression container as the value of an **event attribute** (schema kind `event`) | No: it is a handler, evaluated once |
| An expression container as the value of a component prop | No: evaluated once when the component instance is created (pass a `State` or `Computed` to make a prop reactive) |
| String literals, shorthand attributes | No: constant |

### 3.2 How boundaries lower

- A child boundary lowers to `slot(() -> expr)`.
- An attribute boundary lowers to the builder overload that takes a `Supplier`: `.className(() -> expr)`.
- The lowering **SHOULD** produce, for each markup tree, a static template (all intrinsic elements, constant attributes and text) plus the list of boundaries and handlers, so that static content is sent once and only boundary results are sent on update.

### 3.3 Captured variables

Because boundaries become lambdas, Java requires every local variable a boundary reads to be **effectively final**. Code that compiles on `html` can fail on `live`:

```java
String label = "a";
if (x) label = "b";
return <p>{label}</p>;     // live: javac error, label is not effectively final
```

When javac reports this error inside a boundary, the compiler **MUST** attach a related note that explains that `{...}` in markup is a reactive boundary on the `live` target and suggests copying the value into a `final` local (or using `state`/`computed`).

---

## 4. Component instances

- A component method is invoked **once per component instance**. Its body runs once, and the element tree it returns is the instance's template.
- Code in the component body outside boundaries, `computed`, `effect` and handlers is **not reactive**:

```java
var count = state(0);
var doubled = count.get() * 2;                 // evaluated once
var doubled2 = computed(() -> count.get() * 2); // reactive
```

- Control flow in the body is evaluated once. Reactive conditionals **MUST** be written inside a boundary:

```java
if (loggedIn.get()) return <Dashboard />;      // not reactive
return <main>{loggedIn.get() ? <Dashboard /> : <Login />}</main>;  // reactive
```

### 4.1 Instance lifetime

- An instance is **created** when its element is first produced (initial render, or a boundary producing it).
- An instance is **kept** across a boundary re-evaluation if reconciliation (§5) matches it: same component method, and same key (or same position when unkeyed).
- Otherwise, it is **disposed**: its state is discarded, its effects are stopped, and its subscriptions are removed. A component in a branch that becomes hidden loses its state.

---

## 5. Keys and reconciliation

> **Dependency tracking tells you what to reconsider. Reconciliation tells you what to change.**

- When a boundary re-evaluates and produces a list of elements, the runtime **reconciles** the new list with the previous one. Elements with the same key (and the same component or tag) are kept and moved, and others are created or disposed.
- Elements without keys are reconciled by position. The compiler **SHOULD** warn (JASX3002) when a list is produced from a stream or loop inside markup without keys.

---

## 6. Update cycle

1. An event arrives and its handler runs. Calls to `State.set` during the handler mark the state changed. They do not run anything immediately.
2. When the handler returns, the runtime **flushes**:
   1. It collects every boundary and effect subscribed, directly or through `Computed` values, to a changed state.
   2. It re-evaluates the affected boundaries in document order. Each boundary runs **at most once** per flush, and a `Computed` recomputes at most once per flush, on first read.
   3. It reconciles boundary results (§5) and computes the patches.
   4. It runs affected effects, after all boundaries.
3. The runtime sends the patches. If effects changed state, another flush follows.

A boundary whose new result equals its previous result (by `Objects.equals` for non-element values, or by structural comparison for elements) produces no patch.

---

## 7. State reads outside boundaries (warning)

The compiler warns (JASX3001) about a call `x.get()` that appears in a component body outside a boundary, a `computed`/`effect` argument, or a lambda, **when `x` is a local variable syntactically initialized by a call to `state(...)` or `computed(...)`**, or declared with type `State<…>` or `Computed<…>`.

This check is **syntactic and best effort**: the compiler has no Java type information, so it does not catch reads through method calls, fields or other indirection.

---

## 8. Open questions

- Transport: WebSocket only, or SSE plus POST as a fallback.
- Patch protocol and the JavaScript client.
- Session state, reconnection and horizontal scaling.
- Client islands for high-frequency interactions.
