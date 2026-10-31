# JASX Language Specification

**Version:** 1.0-draft
**Status:** Draft — open for review
**Editor:** Amihere Theophilus Junior

JASX is Java with JSX-style markup expressions. This repository is the **normative specification** of the JASX language: its grammar, its abstract syntax tree (AST), its semantics, and a conformance test suite.

The specification follows the model of the [JSX specification](https://facebook.github.io/jsx/), which extends ESTree with a small set of markup nodes rather than redefining JavaScript. JASX does the same for Java. It defines **only the markup constructs** and treats Java source between them as opaque spans.

Like JSX, JASX is a language, not a framework. The core specification defines the syntax and a standard transform to calls on a **factory** supplied by a **target** library, just as JSX compiles to calls on React, Preact or another library chosen with `jsxImportSource`. Anyone can write a target without changing the language.

**Current focus: the core language**, JASX as JSX is without React. The specification defines a single small reference target, `html`, so that core semantics can be tested. Further targets, including any reactive target, are **not decided yet** (`semantics.md` §7.6). `targets/live.md` only records ideas and is not normative.

---

## Documents

| Document | Contents | Normative |
|---|---|---|
| [`grammar.md`](grammar.md) | Lexical and syntactic grammar, markup detection rule, early errors | Yes |
| [`ast.md`](ast.md) | AST node definitions (ESTree-style interface notation), location conventions | Yes |
| [`ast.schema.json`](ast.schema.json) | JSON Schema for the serialized AST | Yes |
| [`semantics.md`](semantics.md) | Core semantics: name classification, text normalization, child value contract, component resolution, factory lowering, target interface, static and dynamic positions | Yes |
| [`diagnostics.md`](diagnostics.md) | Diagnostic codes, severities and the diagnostic JSON format | Yes |
| [`targets/html.md`](targets/html.md) | The `html` reference target: renders markup to HTML | Yes |
| [`targets/live.md`](targets/live.md) | Ideas for a server-driven reactive target. **Deferred, not decided** | **No** |
| [`conformance.md`](conformance.md) | How conformance is defined and how the test suite is structured | Yes |
| [`tests/`](tests/) | Conformance test fixtures | Yes |
| [`CHANGELOG.md`](CHANGELOG.md) | Changes between drafts | No |

---

## Design principles

1. **Markup is an expression.** JASX adds one new kind of primary expression to Java. It adds nothing else.
2. **Java semantics are not changed.** No operator, statement or type rule of Java behaves differently in a `.jasx` file.
3. **Java is opaque to the spec.** A conforming parser never needs to parse Java expressions, only to tokenize Java well enough to find markup. Tools that need a Java AST parse the `JavaCode` spans with a Java parser of their choice.
4. **The language is target-neutral; targets are libraries.** All targets share the grammar, the AST, children normalization and component resolution. A target supplies a factory, attribute builders and a schema, and may supply its own lowering. Using an attribute, event or feature that the selected target doesn't support is a compile-time error.
5. **Errors come from JASX first.** Markup mistakes are reported by the JASX compiler with `.jasx` locations before any Java is generated.

---

## Terminology

| Term | Meaning |
|---|---|
| **JASX source file** | A file with the `.jasx` extension. |
| **Markup** | A `JasxElement` or `JasxFragment`. |
| **Intrinsic element** | An element whose name denotes a target-defined tag (e.g. `div`). |
| **Component element** | An element whose name denotes a component method (e.g. `TodoItem`). |
| **Component** | A static Java method that returns `jasx.runtime.Element` and is registered in the component index. |
| **Target** | A library that gives markup its meaning: a descriptor, a factory class, attribute builders, a schema, and optionally a custom lowering (e.g. `html`). |
| **Factory** | The target class whose static functions (`h`, `fragment`, `keyed`, `text`) lowered markup calls. |
| **Core** | Everything in this specification that does not depend on a target. |
| **Lowering** | Rewriting markup into plain Java source. |
| **Static / dynamic position** | Whether a value position in markup is fixed in the source (text, string literals) or computed by a Java expression (`semantics.md` §8). |
| **Skeleton** | The static part of a markup tree, with holes for its dynamic positions (`semantics.md` §8). |
| **Conforming implementation** | An implementation that passes the conformance suite at a given level (see `conformance.md`). |

The key words **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT** and **MAY** are used as described in RFC 2119.

---

## Versioning

- The specification is versioned as `MAJOR.MINOR`, like ESTree's per-edition documents.
- A **minor** version may add node types, attributes, diagnostics or optional fields. Existing valid ASTs remain valid.
- A **major** version may change or remove existing nodes or semantics.
- Every serialized AST carries the version in `JasxCompilationUnit.specVersion`.

---

## Repository layout

```
jasx-spec/
  README.md
  grammar.md
  ast.md
  ast.schema.json
  semantics.md
  diagnostics.md
  conformance.md
  CHANGELOG.md
  targets/
    html.md
    live.md
  tests/
    parse/    *.jasx  → *.ast.json         (parser conformance)
    errors/   *.jasx  → *.diagnostics.json (diagnostic conformance)
    render/   *.jasx  → *.html             (HTML target conformance)
```
