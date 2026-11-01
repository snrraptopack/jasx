# The shared runtime of interactive targets: `jasx.ui` (draft)

**Spec version:** 1.0-draft
**Status:** **Draft.** Implemented by `jasx-ui`, and used by the `web` target (`targets/web.md`). It may still change. The core language does not depend on it.

The core language has no state and no events: it only says which positions of markup are static or dynamic (`semantics.md` §8). A target that makes dynamic positions live (re-evaluates them when something changes, and runs Java when the user acts) needs reactive values and events. This document defines them once, in a library, so that every interactive target offers the same API: an application written for one target can move to another, and its components read the same way.

`jasx.ui` is a library, not part of the core: a target that has no use for it ignores it, and a target may offer more than it defines.

| Part | Types | Section |
|---|---|---|
| Reactive values | `State`, `Derived`, `Effect`, `Reactive` | §2 |
| The engine under them, for targets | `Computation`, `Scheduler` | §2, §3 |
| Events and handlers | `Event`, `InputEvent`, `KeyEvent`, `MouseEvent`, `FormEvent`, `Handler`, `Action` | §4 |

---

## 1. Model

**Compile time identifies holes. Run time discovers dependencies.** A target's lowering turns each dynamic position into a *hole* (`semantics.md` §7.4). When a hole runs, the runtime records every reactive value it reads, and subscribes the hole to them. When one of them changes, the hole runs again, and the target updates the corresponding part of its output (a patch to the browser, a widget property).

Components run **once per instance**. Code in a component's body outside holes, derived values, effects and handlers is not reactive.

## 2. Reactive values

`jasx.ui.Reactive` provides (a target usually re-exports them from its factory class, so that one static import is enough):

| Function | Purpose |
|---|---|
| `State<T> state(T initial)` | A reactive value: `get()`, `set(value)`, `update(fn)`, and `peek()` to read without subscribing. |
| `Derived<T> derived(Supplier<T> fn)` | A cached value computed from other reactive values; computed when first read, and again on the next read after one of them changed. |
| `Effect effect(Runnable fn)` | Runs `fn` once when created, then after every update in which a value it read changed. |
| `T untracked(Supplier<T> body)` | Runs `body` without subscribing to what it reads. |

`State.set` with a value equal to the current one (`Objects.equals`) changes nothing. `State` and `Derived` values **MAY** be shared and set from any thread. The string form of a `State` or `Derived` is the string form of its value, read with `get()`, so `{count}` as a child is the same as `{count.get()}`.

### 2.1 Computations

A `Computation` is something that runs with dependency tracking: a target's hole, an effect, or the computation of a derived value. While it runs (`track`), reads subscribe it, and what is created is *owned* by it.

Two things are tracked per thread: the **owner** of new computations, and whether reads **subscribe**. `untracked` keeps the owner and stops subscribing; `runWithOwner(owner, body)` sets the owner and does not subscribe. A target runs event handlers with `runWithOwner`, so that state and effects created in a handler belong to the page or window, and reads in a handler subscribe nothing.

### 2.2 Tracking

When a computation reads a `State` or `Derived` while tracking, it subscribes to it. When the value changes, every subscriber is **invalidated** and unsubscribed; it subscribes again to what it reads when it runs again. A derived value that is invalidated becomes stale and invalidates its own subscribers, without computing anything.

### 2.3 Lifetime

Before a computation runs again, everything from its previous run is disposed: the computations it owns (holes inside a hole, derived values and effects created by a component inside it), its cleanups (`onCleanup`, which a target uses to drop handlers), and its subscriptions. A component inside a hole therefore loses its state when that hole runs again. A disposed computation never runs again.

### 2.4 Failed runs

A run that throws must not leave half a computation behind. A target re-runs holes with `retrack`: the new run starts with nothing, and the previous run is kept aside until the new one succeeds.

- **On success**, what the previous run created is disposed, its cleanups run, and the subscriptions only it had are dropped.
- **On failure**, the new run is undone instead (what it created is disposed, the subscriptions only it had are dropped), and the previous run is put back: what it created, its cleanups and its subscriptions, so the computation runs again when one of the values it read changes. The exception goes to the target, which reports it.

So one failing expression leaves the rest of a page or window, and its own previous output, usable. Effects follow the same rule. A derived value whose computation throws stays stale and passes the exception to its reader; the next change of a value it read tells its readers again.

`State.update(fn)` reads the value, applies `fn` and stores the result as one step, so concurrent updates of a shared state never lose a change. Subscribers are told afterwards, outside that step.

## 3. Scheduling

A computation is created with a `Scheduler`: the target's unit of updates, such as a page of `web` or a window of a desktop target. An invalidated computation is **scheduled**; it does not run at once. The target then runs what was scheduled (`Computation.update()`) at a moment it chooses, such as after an event handler returns, under these rules:

1. Scheduled computations run in **creation order** (`order()`), which is document order. Each runs at most once per round.
2. **Holes run before effects.** If effects change state, another round follows.
3. A computation disposed by an earlier one in the same round (a hole inside a hole that ran again) is skipped.
4. A target **SHOULD** stop after a bounded number of rounds, so effects that keep changing state can't run forever.

`schedule` may be called from any thread, because state can be set by a background job; the target applies the update on its own thread or under its own lock. A computation without a scheduler (created outside any target) runs again at once when invalidated.

## 4. Events

An event attribute (`onclick`, `onsubmit`, …) takes a **handler**:

| Handler | Written | Use |
|---|---|---|
| `Action` | `() -> count.update(n -> n + 1)` | the handler needs nothing from its event |
| `Handler<E>` | `e -> query.set(e.value())` | the handler reads its event |

Java tells them apart by the number of parameters of the lambda. A handler **MAY** throw; the target reports the exception and the page or window stays usable.

Each event attribute gives its handler the most specific **kind** of event it has. A target's attribute builders **MUST** type each event attribute's handler parameter as `Handler<? super K>`, where `K` is the kind:

| Kind | Events | Methods |
|---|---|---|
| `Event` | every event not listed below | `type()` |
| `InputEvent` | `input`, `beforeinput`, `change`, `select`, `invalid`, `focus`, `blur` | `value()`, `checked()`, `values()` |
| `KeyEvent` (an `InputEvent`) | `keydown`, `keyup`, `keypress` | `key()`, `ctrl()`, `shift()`, `alt()`, `meta()`, and the field's value |
| `MouseEvent` | `click`, `dblclick`, `auxclick`, `contextmenu`, `mousedown`, `mouseup`, `mousemove`, `mouseover`, `mouseout`, `mouseenter`, `mouseleave`, `wheel`, `drag`, `dragstart`, `dragend`, `dragenter`, `dragleave`, `dragover`, `drop` | `button()`, `x()`, `y()`, `ctrl()`, `shift()`, `alt()`, `meta()` |
| `FormEvent` | `submit` | `field(name)`, `fields(name)`, `fieldNames()`, `resetForm()`, `as(type)` |

So `onclick={e -> e.field("title")}` is a compile-time error (a `MouseEvent` has no fields), reported by javac at its `.jasx` location.

- `value()` is the value of the field that has the handler, or `""` if it isn't a field. `values()` is the selected values of a `<select multiple>`, or a list holding `value()`.
- `key()` is the key as the platform names it (`"Enter"`, `"a"`, `"ArrowUp"`).
- `button()` is 0 for the main button, 1 for the middle and 2 for the secondary; `x()` and `y()` are the pointer's position in the window's content area.
- `field(name)` is the first value of a form field, or `""`; `fields(name)` all its values in form order; `fieldNames()` the names of the fields that have a value.
- `resetForm()` returns the form's fields to their initial values once the event's other changes are applied. Typed text belongs to the user's side, so setting state can't clear it.

### 4.1 Values the user types

Text being typed belongs to the user's side until the application asks for it. A target **SHOULD** follow `web`'s rules (`targets/web.md` §7): forms send their fields when submitted, `input` events are debounced, and a field the user is typing in is not overwritten by an update.

### 4.2 Forms as records

`e.as(Type.class)` returns the form's fields as a record of type `Type`: each component is read from the field of the same name.

| Component type | Value |
|---|---|
| `String` | `field(name)`; `""` if absent |
| `boolean`, `Boolean` | true if the field has a value other than `"false"` (a checked checkbox), false otherwise |
| primitive numbers and `char` | parsed; an empty or absent field is an error |
| boxed numbers, `Character`, `BigDecimal`, `BigInteger`, enums (by constant name), `LocalDate`, `LocalTime`, `LocalDateTime`, `UUID` | parsed; `null` if empty or absent |
| `List<T>` of any of these | every value of the field, each converted; an empty list if absent |

A value that can't be converted is an `IllegalArgumentException` naming its field. An exception thrown by the record's constructor (its own validation) is let through.
