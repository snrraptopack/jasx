# Target: `html`

**Spec version:** 1.0-draft

The `html` target renders markup to an HTML string. It is the **reference target** of the specification (`semantics.md` §7.6): the simplest useful meaning for markup, used by the render conformance tests (`conformance.md` §4) to check the core semantics. It uses **factory lowering** (`semantics.md` §6) without changes and is not reactive: every dynamic position (`semantics.md` §8) is evaluated once, when the markup expression is evaluated.

It can be used with any Java web framework (Spring, Javalin, Helidon, plain servlets). Interactivity comes from attributes interpreted by the browser or by client libraries (`hx-*` for htmx, `x-*` for Alpine), never from Java lambdas.

---

## 1. Descriptor

```json
{
  "id": "html",
  "specVersion": "1.0",
  "factory": "jasx.html.Html",
  "attributes": "jasx.html.attrs",
  "schema": "schemas/html.json",
  "lowering": "factory",
  "eventHandlers": "none",
  "styles": "css"
}
```

The target supports style blocks (`semantics.md` §10). See §3.5.

---

## 2. Schema

- The elements and attributes of the HTML Living Standard, plus common SVG elements (`svg`, `g`, `path`, `circle`, `ellipse`, `rect`, `line`, `polyline`, `polygon`, `use`).
- Global attributes (`class`, `id`, `style`, `title`, …) are accepted on every element.
- Boolean attributes (`disabled`, `checked`, `inert`, …) have builder methods that take a `boolean`, so javac rejects `disabled="yes"`. Other attributes take any value, which is rendered with `String.valueOf` (`hidden` is not boolean-typed, because it also accepts `until-found`).
- Event attributes such as `onclick` are accepted only with **string** values (inline script). A Java expression as their value is an error (JASX2103).
- Extensions: `data-*`, `aria-*`, `hx-*` and `x-*` attribute names, through `.attr(name, value)`.
- Custom elements (tags containing `-`) are accepted with the builder `jasx.html.attrs.Custom`, and all their attributes are passed through `.attr(name, value)`.

The schema is the resource `schemas/html.json` of the target. The attribute builders (`jasx.html.attrs.A`, `Button`, …, `HtmlAttributes` for the global attributes) are generated from it at build time, so the compiler's checks and the builders always agree.

---

## 3. Rendering

`Element` values are rendered to a string as follows.

| Construct | Output |
|---|---|
| Element | `<tag` + attributes + `>` + children + `</tag>` |
| Void element (`area`, `base`, `br`, `col`, `embed`, `hr`, `img`, `input`, `link`, `meta`, `source`, `track`, `wbr`) | `<tag` + attributes + `>`, with no closing tag and no slash |
| Fragment | its children only |
| Text | the text, escaped (except inside `script` and `style`, §3.3) |
| Keyed element | the element (keys are ignored) |
| Scoped element | the element, with its scope name added to its `class` attribute (§3.5) |

### 3.1 Attributes

- Attributes are emitted in source order, each preceded by one space.
- An attribute whose value is `true` (including a shorthand attribute) is emitted as the bare name: `disabled`.
- An attribute whose value is `false` or `null` is omitted.
- Any other value is emitted as `name="value"`, where `value` is `String.valueOf` of the value, escaped. An `Element` value (markup as an attribute value) is rendered to HTML first, then escaped.

### 3.2 Escaping

| Character | In text | In attribute values |
|---|---|---|
| `&` | `&amp;` | `&amp;` |
| `<` | `&lt;` | `&lt;` |
| `>` | `&gt;` | `&gt;` |
| `"` | unchanged | `&quot;` |

Raw, unescaped HTML is inserted only through an explicit call, `jasx.html.Html.rawHtml(String)`, which returns an `Element`.

### 3.3 Raw text elements

HTML parses the content of `script` and `style` as raw text: character references are not decoded there, so escaping would corrupt the content (`a > b` would become `a &gt; b`). Text inside these two elements is therefore written **without escaping**.

The content of a `script` or `style` element **MUST NOT** contain its closing tag (`</script` or `</style`, compared case-insensitively), because that would end the element early and let the rest be parsed as HTML. Rendering such content fails with an error.

### 3.4 Whitespace

The renderer adds no whitespace and no line breaks, except inside the `<style>` element of §3.5. The only other text in the output is the text child values produced by children normalization (`semantics.md` §3.1) and by expression children.

### 3.5 Style sheets

The factory functions of `semantics.md` §10.5 are `jasx.html.Html.styleSheet(String scope, String css)`, which returns a `jasx.html.StyleSheet` (with the methods `scope()` and `css()`), `jasx.html.Html.scoped(Object sheet, Element element)` and `jasx.html.Html.unscoped(Object sheet, Element element)`.

**Scoped elements.** A scoped element is rendered as its element, with the scope name of its style sheet added to its `class` attribute:

- if the element has a `class` attribute whose value is not `null`, `false` or `true`, a space and the scope name are appended to the value: `class="card jx-1a2b3c4d"`;
- otherwise, `class="jx-1a2b3c4d"` is written after its other attributes, in place of any `class` attribute it has.

**Unscoped elements.** An unscoped element is rendered as its element, unchanged.

**Delivering the CSS.** `jasx.html.Html.styles()` returns an element that renders as the style sheets used in the same `Html.render` call: a `<style>` element containing the scoped CSS of every style sheet of a scoped or unscoped element rendered in that call, in the order in which each was first used, separated by LF. If no such element is rendered, it renders as nothing. It may appear before the elements whose style sheets it contains (typically in `<head>`), because the renderer fills it in at the end of the call. If a call renders several `styles()` elements, only the first receives the style sheets; the others render as nothing.

An application that serves CSS as a file can read `StyleSheet.css()` instead.
