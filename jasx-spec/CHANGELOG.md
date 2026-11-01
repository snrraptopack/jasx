# Changelog

## 1.0-draft, revision 21

### Added

- `targets/web.md` §8.4, **templates**: the server numbers the shapes (statics) of a page's patch content; the second time a shape is rendered, a `tpl` patch defines it once, and later content of that shape carries only its values (`"t"`, `"v"`) in `html` patches and new list items, when that is shorter than the HTML. `tpl` patches are never dropped from a catch-up.

### Changed

- `targets/web.md` §10: static templates are no longer listed as missing; templates from the compiler are.

## 1.0-draft, revision 20

### Changed

- `targets/web.md` §8.4, **attributes are merged three ways**: when a hole is merged or an `attr` patch arrives, the client changes an attribute only if the server's new value differs from its previous one, and merges `class` by class name. Before, every attribute was reset to what the server rendered, so a `<details>` the user opened closed again whenever its hole re-rendered, and classes added in the browser were removed.

## 1.0-draft, revision 19

### Added

- `targets/web.md` §6.3, **security**, done by the runtime for every server adapter:
  - **sessions:** a `jasx_session` cookie (HttpOnly, SameSite=Lax), and each page belongs to the session that rendered it. A request from another session is answered as for an unknown page;
  - **requests from other sites:** event bodies must be declared as `application/json` (`415`), and requests whose `Sec-Fetch-Site` names another site are refused (`403`);
  - **limits:** event bodies up to 1 MiB (`413`), and at most 50 pages per session, the oldest discarded first.

### Changed

- `targets/web.md` §8.1: the new status codes and the session cookie.

## 1.0-draft, revision 18

### Added

- `targets/web.md` §6.2, **one stream for every tab**: the tabs of a browser share one stream, so they don't use up the browser's HTTP/1.1 connections. The tab holding a Web Lock leads: it streams every tab's page and passes frames on over a BroadcastChannel; when it closes, another tab takes over and the server catches each page up. A browser without those APIs (or a page outside a secure context) gives each tab its own stream, as before.

### Changed

- `targets/web.md` §6.1, §8.1: the stream request names several pages with their versions, `stream?pages=ID:N,ID:N`, instead of `stream?page=ID&since=N`. Each frame is `{"page": ID, "batch": ...}`, and a reload message concerns one page; the stream goes on for the others. A newer stream that names a page takes it from the older one, and a stream left with no page ends.
- `targets/web.md` §6: hidden tabs close their stream and ping only in a browser whose tabs can't share a stream.

## 1.0-draft, revision 17

### Added

- `targets/web.md` §4.3, **keyed lists**: a hole whose children all have keys keeps each item whose key and inputs (the element function's captured values, compared with `equals`) are unchanged, with its components' state, holes and handlers. Only new or changed items are rendered, and the browser gets a `list` patch (§8.4) with the new order and the new items' HTML.
- `targets/web.md` §8.4, **merging**: the client merges an `html` patch into the page (matching nodes by kind and tag, and by `id`), so elements that stay keep their focus, typed text and scroll position. Before, a hole's content was replaced.
- `ui.md` §2.5: `Computation.keep`, for keeping what a previous run created.

### Changed

- `targets/web.md` §3: a keyed element lowers to `jasx.web.Web.keyed((key), () -> element)`; the element is a function.
- `targets/web.md` §6.1, §8.4: in a merged batch, `list` patches are kept unless a later `html` patch replaces the whole hole.
- `targets/web.md` §10: keyed reconciliation is no longer listed as missing.

## 1.0-draft, revision 16

### Changed

- **Character references are decoded in attribute strings** (`grammar.md` §5, `ast.md` §6.4), as in text and as in JSX. Markup copied from HTML now means the same: `href="?a=1&amp;b=2"` is the URL `?a=1&b=2`, where before it kept `&amp;` literally and rendered as `&amp;amp;`. Java escapes are still processed, a `&` that doesn't start a complete reference is literal, and an unknown named reference is JASX1010.
- Expected output of parse conformance test `010-character-references`: the attribute `title="a&amp;b"` has the value `a&b`.
- `targets/web.md` §6: a page whose browser never connects is discarded after a **first-connect timeout** (30 seconds by default), so crawlers, link previews and prefetches don't keep pages for 2 minutes. A tab hidden for 10 seconds closes its stream and **pings** (`POST /_jasx/ping`, §8.1) instead, so hidden tabs don't use up the browser's HTTP/1.1 connections; production deployments **SHOULD** serve over HTTP/2.
- `targets/web.md` §7: states that a hole that re-runs loses the focus and unsent text of fields inside it.

### Added

- Render conformance test `020-character-references-in-attribute-strings`.

## 1.0-draft, revision 15

Fixes from an external review of the `web` target and `jasx.ui`.

### Added

- `ui.md` §2.4, **failed runs**: a hole or effect that throws keeps its previous run (what it created, its cleanups, its subscriptions) and runs again on the next change; `State.update` is atomic.
- `targets/web.md` §6.1, **delivery and catching up**: batches are kept in a bounded log; the client opens the stream with the last version it applied (`since`), and receives what it missed as one merged batch, or a reload message if the log no longer reaches back. The client reconnects after a failed stream, a failed event request, or a batch missing for 3 seconds (a deadline later batches don't push back).
- `targets/web.md` §8.4, **merged batches** (`from`, `version`): superseded `html` and `attr` patches are left out. Patches are defined to be harmless when applied again.
- `targets/web.md` §8.3: events carry `seq`, the client's number for them.

### Changed

- `targets/web.md` §7: protecting typing is defined by **field revisions** instead of "typed since its last event". The answer to an older queued event no longer overwrites newer typing. `resetForm()` leaves alone the fields edited after the event was sent.
- `targets/web.md` §8.4: a `reset` patch names its event (`"event": seq`), so it still finds its form when its batch waits for an earlier one or comes in a catch-up. Before, it applied only to the event response being processed.
- `targets/web.md` §6: the stream of an unknown page sends a reload message instead of answering `410`, because the client now reconnects by itself and can't see statuses.
- `targets/web.md` §8.2: rows and holes directly in a `table` are rendered inside a `tbody`, so a hole's markers stay together; hole content is parsed in its parent's namespace (SVG, MathML); a missing end marker removes nothing and reloads.
- `targets/web.md` §5: failures in hole and effect updates are isolated, and a page whose first rendering fails is discarded.
- `targets/web.md` §8.1: a malformed event body answers `400`, including truncated escapes and deep nesting.

## 1.0-draft, revision 14

### Added

- **`ui.md` (draft): `jasx.ui`, the shared runtime of interactive targets.** Reactive values (`State`, `Derived`, `Effect`), the engine under them (`Computation`, and the `Scheduler` a target supplies), and typed events and handlers. They were part of the `web` target. They are now a library that any interactive target (a desktop target, say) can implement, so applications and components read the same on every such target. It is not part of the core: the core still has no state and no events.
- **Typed events** (`ui.md` §4). Each event attribute gives its handler the most specific kind of event it has: `FormEvent` for `submit`, `InputEvent` for `input`, `change`, `focus` and `blur`, `KeyEvent` for keyboard events, `MouseEvent` for pointer events, and `Event` for the others. Using a method the kind doesn't have (`onclick={e -> e.field("x")}`) is a compile-time error. A handler that needs nothing from its event is an `Action`: `onclick={() -> save()}`.
- **Forms as records** (`ui.md` §4.2): `e.as(NewTodo.class)` reads each record component from the form field of the same name.
- `State.peek()` and `untracked(...)`, to read without subscribing.

### Changed

- `targets/web.md` §2, §4, §7: the state API, handlers and events are `jasx.ui`'s; `jasx.web.Handler` and `jasx.web.Event` are gone. `Web.state`, `derived` and `effect` remain, re-exported.
- `targets/web.md` §8.3: an event carries the members its kind needs: `values` for a multiple select, `button`, `x` and `y` for mouse events, and modifier keys for keyboard and mouse events. Members that aren't sent read as empty.
- Event handlers on `web` no longer run with no owner: they run with the page as owner (`ui.md` §2.1), so an effect created in a handler belongs to the page.

## 1.0-draft, revision 13

### Added

- **The `web` target** (`targets/web.md`, draft), which replaces the non-normative ideas of `targets/live.md`. It is server-driven, like Phoenix LiveView. Components run on the server, and every dynamic position (`semantics.md` §8) becomes a reactive hole. Event handlers are Java lambdas run on the server. The browser receives patches for the holes that changed: event answers over HTTP, and pushed batches over server-sent events. Input belongs to the browser: `oninput` is debounced by default, forms send their fields on submit, and a field being typed in is never overwritten. The draft also defines the state API (`state`, `derived`, `effect`), the page lifecycle and the protocol.

### Changed

- `semantics.md` §7.6: the targets table lists `web` as a draft instead of `live` as deferred.
- `conformance.md`: `tests/web/` is the reserved directory name for the `web` target, instead of `tests/live/`.
- `diagnostics.md` §5: JASX3001 and JASX3002 stay reserved, now for reactive targets such as `web`.

## 1.0-draft, revision 12

### Clarified

- `semantics.md` §7.4: a non-normative note on how the reference implementation loads and runs a custom lowering (`jasx.compiler.MarkupLowering`). No rule changed.

## 1.0-draft, revision 11

### Added

- **Statement containers** (`grammar.md` §6.5, §6.7, `ast.md` §5, §6.8, `semantics.md` §3.5, §6.8). A child `{...}` whose first token is `{`, `if`, `for`, `while` or `do` holds a Java statement instead of an expression. Inside it, markup at the start of a statement (after `;`, `}`, a block `{`, or another markup statement) is a **markup statement**, which adds its markup to the children each time it runs. So loops, `if`/`else` and local variables can produce children: `{for (Todo t : todos) { <li>{t}</li> }}`. It lowers to a switch expression whose block fills a list, so the statement can read and assign the enclosing local variables; `return` is not allowed in it. A statement child value is a dynamic child (`semantics.md` §8).
- AST nodes `JasxStatementContainer`, `JavaStatement` and `JasxMarkupStatement`.
- JASX1003 also covers an unclosed statement container.
- Conformance tests `parse/016-statement-container`, `errors/014-unclosed-statement-container`, and `render/016-statement-for`, `017-statement-if-else`, `018-statement-block-and-locals`, `019-nested-statement-containers`.

## 1.0-draft, revision 10

### Changed

- **The scope class only on elements the scoped CSS may match** (`semantics.md` §10.4, §10.5). An intrinsic element of a file with a style sheet lowers to `F.scoped(sheet, e)` only if one of the file's scoped compounds may match it, judged from its tag name and its literal `class` and `id` attributes; otherwise it lowers to the new factory function `F.unscoped(sheet, e)`, which still uses the style sheet but adds no scope class. Before, every intrinsic element was scoped, so elements such as `<meta>` or `<title>` carried a scope class no rule could use.
- Expected output of render conformance test `011-style-block-in-class`: its `<ul>` no longer has the scope class, because the only scoped compound is `li`.
- `targets/html.md` §3.5: `Html.unscoped`, and `Html.styles()` also delivers the style sheets of unscoped elements.

### Added

- Render conformance tests `014-scope-class-where-it-may-match` and `015-unscoped-elements-use-the-style-sheet`.

## 1.0-draft, revision 9

### Changed

- **Style blocks in method bodies** (`semantics.md` §10.1). A style block may now be written in a method, constructor or initializer body, where a statement starts, for example just before a component's `return`. It is removed from the generated code like any style block. JASX2015 now only applies to a style block outside any class.

### Added

- Render conformance test `013-style-block-in-method`.

## 1.0-draft, revision 8

### Added

- **Style blocks inside markup** (`grammar.md` §6.5, §7.3, `semantics.md` §10). A `<style>` child of an element or fragment whose content does not start with `{` is a style block, so CSS can be written next to the elements it styles. Like every style block, it belongs to the file's style sheet and renders nothing where it is written. `<style>{css}</style>` remains an ordinary element.
- Conformance tests `parse/015-style-block-in-markup` and `render/012-style-block-in-markup`.

### Changed

- **A file may contain several style blocks.** Their scoped CSS is joined, in source order, into the one style sheet of the file. JASX2016 is retired.
- **Where the style sheet field is declared** (`semantics.md` §10.5): at the start of the file class, or of the top-level class containing the first style block, instead of in place of the style block. The rendered output is unchanged.

## 1.0-draft, revision 7

### Added

- **Style blocks** (`grammar.md` §7.3, `ast.md` §6.7, `semantics.md` §10). A `<style>...</style>` block where a field could be declared holds plain CSS for the markup of its file. The compiler scopes it: each selector gets the file's scope class (`jx-` and 8 hex digits of a hash of the file's qualified name) on its last compound selector, `:global(...)` opts out, and every intrinsic element written in the file is marked with `F.scoped(sheet, element)`. CSS mistakes are compile-time errors with `.jasx` locations. Targets opt in with the descriptor field `"styles": "css"`.
- AST node `JasxStyleBlock`.
- Diagnostics JASX1012 (unclosed style block), JASX2014 (invalid CSS), JASX2015 (misplaced style block), JASX2016 (second style block in a file) and JASX2106 (target without style support).
- `html` target: `"styles": "css"`, scoped elements get the scope class, and `Html.styles()` renders the style sheets used in a render (`targets/html.md` §3.5).
- Conformance tests `parse/014-style-block`, `errors/013-unclosed-style-block`, `render/010-style-block` and `render/011-style-block-in-class`.

### Changed

- **Markup detection is unchanged, but `<style>` at the start of a statement or member now starts a style block** (`grammar.md` §7.3). A generic method or constructor declared without modifiers can no longer have a first type parameter named `style`.

## 1.0-draft, revision 6

### Added

- **Top-level members and file classes** (`grammar.md` §7.2, `semantics.md` §9). A `.jasx` file may declare fields and methods at the top level, outside any class. Such a file is wrapped in its *file class*, a `public final` class named after the file, and every top-level declaration (including types) becomes a static member of it. Components no longer need a holder class: `public Element Card(...)` at the top level of `Ui.jasx` is the component `Ui.Card`. This is the second extension of the Java grammar, next to markup.
- Diagnostics JASX2012 (the file name is not a valid class name) and JASX2013 (a constructor or initializer block at the top level).
- Render conformance test `009-top-level-components`.

### Changed

- **Render tests are compiled as `T.jasx`** (`conformance.md` §4), so that a test can declare `Test` at the top level. Existing tests declare class `T` and are unaffected.

## 1.0-draft, revision 5

### Changed

- **`html` target: raw text elements** (`targets/html.md` §3.3). The content of `script` and `style` is no longer escaped, because HTML parses it as raw text (`a > b` in CSS used to become `a &gt; b`). Content that contains the element's own closing tag is rejected. Found while building the example project.

### Added

- Render conformance test `008-raw-text-elements`.

## 1.0-draft, revision 4

Clarifications found while implementing the reference compiler and the `html` target. No AST node, diagnostic code or conformance expectation changed; all render tests pass.

### Changed

- **Children passed to components** (`semantics.md` §5.5). An expression child value is converted with `fragment((Object) expr)`, not with `text`, so the child value contract still applies (a `null` becomes an empty fragment, an `Element` stays an element). Text child values still use `text`.
- **Component index** (`semantics.md` §5.3): `params` lists every parameter in declaration order, including the `children` parameter, whose `kind` is `"children"`. `components.json` is a JSON array of entries.

### Clarified

- `@jasx.Default` (`semantics.md` §5.2): the annotation's string value is the literal's source text (`@jasx.Default("\"Untitled\"")`), written as `@jasx.Default("…")` or `@jasx.Default(value = "…")`.
- The inserted `@jasx.Component` goes on the same line as the method header (`semantics.md` §5.1).
- `targets/html.md` §2 describes the schema actually shipped (HTML plus common SVG elements, boolean-typed boolean attributes, builders generated from `schemas/html.json`). §3.1: an `Element` attribute value is rendered to HTML, then escaped.

## 1.0-draft, revision 3

This revision narrows the current scope to the core language, JASX as JSX is without React, and adds tests written while implementing the reference parser. No AST node changed.

### Changed

- **The `live` target is deferred and not decided.** `targets/live.md` is no longer normative; it only records ideas. Nothing in the core depends on it (`semantics.md` §7.6).
- **`html` is the reference target**, used to test core semantics through the render tests (`targets/html.md`, `semantics.md` §7.6).
- **Target descriptor** (`semantics.md` §7.2): the `reactive` field is removed. `eventHandlers` is `"none"` or `"java"` (formerly `"none"`, `"server"` or `"local"`); where a handler runs is target-defined. Unknown descriptor fields are ignored.
- **Diagnostics** JASX2104, JASX3001 and JASX3002 are reserved and must not be reported (`diagnostics.md` §4, §5).
- **Keys** (`semantics.md` §6.4) no longer refer to `live`.

### Added

- **Static and dynamic positions** (`semantics.md` §8). The core classifies every value position as static, dynamic child, dynamic attribute, handler, prop or key, and defines the static skeleton. This is target-neutral: it says which parts of markup are computed, not when they are re-evaluated.
- `ast.md` §6.6: a closing `}` written as a Unicode escape is included entirely in the container's range.
- Parser conformance tests `010-character-references`, `011-comments-and-text-blocks`, `012-lambda-and-switch` and `013-empty-file`.
- Diagnostic conformance tests `007-unclosed-element` (JASX1002), `008-invalid-attribute-value` (JASX1005), `009-unexpected-token` (JASX1006), `010-unknown-character-reference` (JASX1010), `011-identifier-ends-with-dash` (JASX1011) and `012-brace-in-text` (JASX1009 for `}`).

## 1.0-draft, revision 2

This revision makes JASX a target-neutral language, like JSX, with targets as libraries, and fixes errors found in review of the first draft. No AST node changed.

### Changed

- **The core language and its targets are separated.** `semantics.md` now defines only target-neutral semantics, plus the **target interface**: a descriptor (`META-INF/jasx/target.json`), a factory class, attribute builders, a schema, and an optional custom lowering (`semantics.md` §7). This is the analogue of JSX with `jsxImportSource`, where React, Preact and Solid are libraries. The `html` and `live` targets are specified in `targets/html.md` and `targets/live.md`. The reactive semantics (former `semantics.md` §6.4, §6.5 and §8) moved to `targets/live.md` unchanged, except as noted below.
- **Factory lowering.** Lowered code calls `h`, `fragment`, `keyed` and `text` on the factory class named by the target descriptor, instead of a fixed `jasx.runtime.Runtime`. The core runtime artifact contains only `jasx.runtime.Element` and the component annotations (`semantics.md` §6.2).
- **The child value contract is part of the core** (`semantics.md` §3.4), and every target must follow it.
- **Keys have no core meaning.** `key` is still reserved and lowered to `keyed(...)`, but reconciliation is defined by targets that reconcile (`semantics.md` §6.4).

### Fixed

- **Markup detection after `{`** (`grammar.md` §4). The first draft put every `{` in the markup-introducing set **S**, so valid Java such as `class A { <T> void m() {} }` and `A() { <String>this("x"); }` was misread as markup. Now only a `{` that opens an array initializer, or an expression container, is in **S**. The tokenizer tracks brace kinds (`grammar.md` §4.2).
- **`yield` is in S only when it begins a statement** (`grammar.md` §4.3). A variable named `yield` followed by `<` is Java. Two constructs that cannot be told apart by tokenizing are explicitly unsupported.
- **The Java-mode tokenizer must process Unicode escapes**, as javac does, and lex `::` as one token (`grammar.md` §3).
- **Component argument evaluation order** (`semantics.md` §5.6). The first draft required evaluation in attribute source order using local temporaries. That cannot be implemented: markup is an expression, so there is nowhere to declare locals, and a temporary cannot hold a lambda without a target type. Arguments are now evaluated in **parameter order**, like any Java call.
- **`@jasx.Default` values must be literals** (`semantics.md` §5.2, JASX2011). The first draft copied an arbitrary expression to each call site, where it resolved names against the caller's imports and scope. Non-literal defaults are written in the component body with `@jasx.Optional`.
- **Only methods whose names do not start with a lowercase letter are components** (`semantics.md` §5.1). Lowercase static helpers that return `Element` are ordinary methods and may be overloaded. `@jasx.Component` on an invalid method is JASX2010.
- **Variadic children** (`semantics.md` §6.7). Lowering must stop javac from treating a lone `null` or array child as the variadic array.
- **Captured variables in `live` boundaries** (`targets/live.md` §3.3). The compiler must explain javac's "effectively final" errors inside boundaries.
- `grammar.md` §5: the Unicode escape example had been rendered as a plain `A`.

### Added

- Diagnostics JASX2010, JASX2011 and JASX2105. Code ranges `JASX20xx` (core) and `JASX21xx` (target capability).
- Parser conformance tests `007-type-arguments-after-brace`, `008-array-initializer` and `009-yield`.
- Conformance levels are split into core and target levels (`conformance.md` §1).

### Removed

- `when(cond, () -> ...)` from the design proposal is not part of the language. Use the conditional operator.

## 1.0-draft

First draft of the formal specification, derived from the JASX design proposal (Update 1).

### Differences from the design proposal (Update 1)

- **Component props (supersedes proposal §3.2).** The proposal lowered components through generated props builders (`UserCardProps.builder()...build()`). Writing the spec showed that this needs Java symbol resolution to find the props class for a tag, and doesn't match the component's real method signature. The spec instead defines a **component index** (`semantics.md` §5.3), built from `.jasx` files, `@jasx.Component` method headers in `.java` files, and `META-INF/jasx/components.json` in dependencies. Components lower to **direct positional calls** (`TodoPage.TodoItem(t, toggle)`), so javac types lambdas from the real signature. Optional props use `@jasx.Optional` and `@jasx.Default`.
- **Components cannot be overloaded** (JASX2006), so that resolution by name is unambiguous.
- **Component props are not reactive boundaries.** Pass a `State` or `Computed` to make a prop reactive (now `targets/live.md` §3.1).
- **Hidden component state is discarded** when a branch is hidden (now `targets/live.md` §4.1). This answers an open question from the proposal.
- **`Boolean` children render as text** (`true`/`false`), not hidden as in JSX, so JASX doesn't change Java semantics (`semantics.md` §3.4).
- **Casting markup directly** (`(Element) <div/>`) is not supported. Use `(Element) (<div/>)`, though a cast is never needed (`grammar.md` §4.5).
- **Spread attributes** (`{...props}`) are reserved (JASX1007).
- **Attribute strings** use Java escape sequences. Text content uses HTML character references.

### Added

- Grammar, with the exact markup detection rule and early errors (`grammar.md`)
- ESTree-style AST with `JavaCode` / `JavaExpression` segment nodes (`ast.md`)
- JSON Schema for the serialized AST (`ast.schema.json`)
- Semantics: classification, text normalization, component index and resolution, lowering, reactive semantics, target capability model (`semantics.md`)
- Diagnostic codes and JSON format (`diagnostics.md`)
- Conformance levels and test format (`conformance.md`)
- Conformance tests: 6 parser, 6 diagnostic, 7 HTML render
