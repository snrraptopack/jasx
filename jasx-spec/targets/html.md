# Target: `html`

**Spec version:** 1.0-draft

The `html` target renders markup to an HTML string on the server. It is the first standard target, and it is the target used by the render conformance tests (`conformance.md` §4). It uses **factory lowering** (`semantics.md` §6) without changes.

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
  "reactive": false,
  "eventHandlers": "none"
}
```

---

## 2. Schema

- The intrinsic elements and attributes of the HTML Living Standard, with builder classes in `jasx.html.attrs`.
- Event attributes such as `onclick` are accepted only with **string** values (inline script). A Java expression as their value is an error (JASX2103).
- Extensions: `data-*`, `aria-*`, `hx-*` and `x-*` attribute names, through `.attr(name, value)`.
- Custom elements (tags containing `-`) are accepted with a generic builder that only has `.attr(name, value)`.

The reference implementation generates the schema from machine-readable HTML element and attribute data, not by hand.

---

## 3. Rendering

`Element` values are rendered to a string as follows.

| Construct | Output |
|---|---|
| Element | `<tag` + attributes + `>` + children + `</tag>` |
| Void element (`area`, `base`, `br`, `col`, `embed`, `hr`, `img`, `input`, `link`, `meta`, `source`, `track`, `wbr`) | `<tag` + attributes + `>`, with no closing tag and no slash |
| Fragment | its children only |
| Text | the text, escaped |
| Keyed element | the element (keys are ignored) |

### 3.1 Attributes

- Attributes are emitted in source order, each preceded by one space.
- An attribute whose value is `true` (including a shorthand attribute) is emitted as the bare name: `disabled`.
- An attribute whose value is `false` or `null` is omitted.
- Any other value is emitted as `name="value"`, where `value` is `String.valueOf` of the value, escaped.

### 3.2 Escaping

| Character | In text | In attribute values |
|---|---|---|
| `&` | `&amp;` | `&amp;` |
| `<` | `&lt;` | `&lt;` |
| `>` | `&gt;` | `&gt;` |
| `"` | unchanged | `&quot;` |

Raw, unescaped HTML is inserted only through an explicit call, `jasx.html.Html.rawHtml(String)`, which returns an `Element`.

### 3.3 Whitespace

The renderer adds no whitespace and no line breaks. The only text in the output is the text child values produced by children normalization (`semantics.md` §3.1) and by expression children.
