# JASX Conformance

**Spec version:** 1.0-draft

The conformance suite in `tests/` is what makes this specification testable, in the role test262 plays for ECMAScript. An implementation conforms at a level if it passes every test of that level.

---

## 1. Levels

Levels are split into **core** levels, which test the language, and **target** levels, which test one target.

| Level | Kind | What is tested | Test directory |
|---|---|---|---|
| **Parser** | Core | Producing the AST defined in `ast.md` | `tests/parse/` |
| **Diagnostics** | Core | Reporting syntax errors with the right codes and locations | `tests/errors/` |
| **HTML target** | Target (reference) | Lowering, compiling and rendering to the expected HTML (`targets/html.md`) | `tests/render/` |

No other target levels are defined. A directory name for a reactive target (`tests/live/`) is reserved, but such a target is not decided (`semantics.md` §7.6).

An editor plugin, formatter or alternative parser (for example in Rust or TypeScript) typically aims for the core levels. A compiler aims for the core levels plus the levels of the targets it ships. A third-party target can publish its own target-level tests in the same format.

Core semantics (children normalization, the child value contract and component lowering) are observable only through a target, so they are tested through the render tests of the `html` target.

---

## 2. Parser tests

```
tests/parse/NNN-name.jasx        input
tests/parse/NNN-name.ast.json    expected AST
```

**Pass condition:** the implementation's AST, serialized as JSON, is deep-equal to the expected AST, with these rules:

- Field order is ignored.
- Fields whose names start with `_` are ignored.
- `sourceFile` is ignored.
- All other fields, including every `range` and `loc`, must match exactly.

Every expected AST is valid against `ast.schema.json`.

---

## 3. Diagnostic tests

```
tests/errors/NNN-name.jasx               input
tests/errors/NNN-name.diagnostics.json   expected diagnostics (array)
```

**Pass condition:** the implementation reports the same **set** of diagnostics, compared on `code`, `severity`, `range` and `loc` only. `message` and `related` are not compared.

An implementation **MAY** report additional diagnostics after the first error (error recovery), but the expected diagnostics must all be present and the first reported diagnostic must be the first expected one.

---

## 4. HTML target tests

```
tests/render/NNN-name.jasx       input; declares a component named Test with no parameters
tests/render/NNN-name.html       expected output of rendering <Test />
```

**Pass condition:** the implementation lowers the file with the `html` target, compiles it, renders `Test()` to a string, and the output is identical to the expected HTML after both are normalized as follows:

- remove the trailing newline of the expected file, if any.

Tests compare **rendered output**, not generated Java. Generated Java is implementation-defined, so two compilers with different lowering strategies can both conform.

The expected output follows the rendering rules of the `html` target (`targets/html.md` §3).

---

## 5. Adding tests

- Number tests sequentially within their directory (`001`, `002`, …), and never renumber.
- Each test covers one behavior, named for it (`004-text-whitespace.jasx`).
- A change to expected output of an existing test is a spec change and must be listed in `CHANGELOG.md`.
