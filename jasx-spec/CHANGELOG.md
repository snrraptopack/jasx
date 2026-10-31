# Changelog

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
