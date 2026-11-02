# JASX AST

**Spec version:** 1.0-draft

This document defines the abstract syntax tree that a conforming JASX parser produces. It uses the interface notation of [ESTree](https://github.com/estree/estree), so that the AST is language-neutral: a parser in Java, Kotlin, Rust or TypeScript produces the same tree, and the tree can be serialized to JSON.

The JSON Schema in [`ast.schema.json`](ast.schema.json) is normative for the serialized form.

---

## 1. Scope

The JASX AST describes **markup only**. Java source is represented by `JavaCode` nodes containing raw source text. This mirrors how the JSX specification extends ESTree with markup nodes, with one difference: Java has no community-standard AST equivalent to ESTree, so JASX does not embed a Java AST. Tools that need one parse `JavaCode` spans themselves (with javac's Compiler Tree API, JavaParser, Eclipse JDT, etc.).

---

## 2. Notation

```
interface Name <: Parent {
  field: Type;
}
```

- `<:` means the interface inherits all fields of its parent.
- `"literal"` types are exact string values.
- `[ T ]` is an array of `T`.
- `T | null` means the field is present and may be `null`.
- Every concrete node has a `type` field whose value is the interface name.

---

## 3. Locations

```
interface Node {
  type: string;
  range: [ number, number ];
  loc: SourceLocation;
}

interface SourceLocation {
  start: Position;
  end: Position;
}

interface Position {
  line: number;     // 1-based
  column: number;   // 0-based
}
```

| Convention | Rule |
|---|---|
| Offsets and columns | Counted in **UTF-16 code units** (the unit of `java.lang.String` and of JavaScript strings), from the start of the file. |
| `range` | `[start, end)` — `start` inclusive, `end` exclusive. |
| `line` | 1-based. A line terminator (CR, LF or CR LF) ends a line; CR LF counts as one terminator. |
| `column` | 0-based, measured from the start of the line. |
| `loc.end` | The position of the first code unit **after** the node. |

These match ESTree's conventions, so existing tooling (source maps, editors) can consume JASX locations directly.

---

## 4. Compilation unit

```
interface JasxCompilationUnit <: Node {
  type: "JasxCompilationUnit";
  specVersion: string;                      // e.g. "1.0"
  sourceFile: string | null;                // file name, if known
  segments: [ JavaCode | JasxElement | JasxFragment | JasxStyleBlock | JasxScriptBlock ];
}
```

```
interface JasxScriptBlock <: Node {
  type: "JasxScriptBlock";
  script: string;                           // the text between <script> and </script>, as written
}
```

`segments` covers the whole file in order, with no gaps and no overlaps: the `range` of each segment starts where the previous one ends, the first starts at `0`, and the last ends at the file length. A file with no markup has exactly one `JavaCode` segment. An empty file has no segments.

---

## 5. Java nodes

```
interface JavaCode <: Node {
  type: "JavaCode";
  source: string;          // the exact source text of the span
}
```

`source` is the exact text, including comments, whitespace and unprocessed Unicode escapes. `source` **MUST** equal the substring of the file at `range`.

```
interface JavaExpression <: Node {
  type: "JavaExpression";
  segments: [ JavaCode | JasxElement | JasxFragment ];
}
```

The content of a non-empty expression container. Markup nested inside the Java expression appears as `JasxElement` or `JasxFragment` segments. Like the compilation unit, `segments` covers the whole range of the expression with no gaps, and the range excludes the surrounding `{` and `}`.

**Example.** For `{items.stream().map(i -> <li>{i}</li>).toList()}`, the `JavaExpression` has three segments:

1. `JavaCode` with source `items.stream().map(i -> `
2. `JasxElement` for `<li>{i}</li>`
3. `JavaCode` with source `).toList()`

```
interface JavaStatement <: Node {
  type: "JavaStatement";
  segments: [ JavaCode | JasxElement | JasxFragment | JasxMarkupStatement | JasxStyleBlock ];
}
```

The content of a statement container (§6.8). Like `JavaExpression`, `segments` covers the whole range with no gaps, and the range excludes the surrounding `{` and `}`. Markup at the start of a statement appears as a `JasxMarkupStatement`, a style block written there as a `JasxStyleBlock`, and any other markup (an expression, by `grammar.md` §4) as `JasxElement` or `JasxFragment`.

**Example.** For `{for (T t : ts) { <li>{t}</li> }}`, the `JavaStatement` has three segments:

1. `JavaCode` with source `for (T t : ts) { `
2. `JasxMarkupStatement` for `<li>{t}</li>`
3. `JavaCode` with source ` }`

---

## 6. Markup nodes

### 6.1 Names

```
interface JasxIdentifier <: Node {
  type: "JasxIdentifier";
  name: string;                 // e.g. "div", "hx-post", "TodoItem"
}

interface JasxQualifiedName <: Node {
  type: "JasxQualifiedName";
  qualifier: JasxIdentifier | JasxQualifiedName;
  name: JasxIdentifier;
}
```

`<ui.forms.Input>` is `JasxQualifiedName(JasxQualifiedName(ui, forms), Input)`.

```
interface JasxNamespacedName <: Node {
  type: "JasxNamespacedName";
  namespace: JasxIdentifier;
  name: JasxIdentifier;
}
```

```
interface JasxEventName <: Node {
  type: "JasxEventName";
  name: JasxIdentifier;                     // click, for @click: a browser attribute (semantics.md §11)
}
```

Used only for attribute names (e.g. `xlink:href`).

### 6.2 Elements

```
interface JasxElement <: Node {
  type: "JasxElement";
  openingElement: JasxOpeningElement;
  children: [ JasxChild ];
  closingElement: JasxClosingElement | null;
}

interface JasxOpeningElement <: Node {
  type: "JasxOpeningElement";
  name: JasxIdentifier | JasxQualifiedName;
  attributes: [ JasxAttribute ];
  selfClosing: boolean;
}

interface JasxClosingElement <: Node {
  type: "JasxClosingElement";
  name: JasxIdentifier | JasxQualifiedName;
}
```

For a self-closing element, `selfClosing` is `true`, `children` is empty, and `closingElement` is `null`. An element written as `<div></div>` has `selfClosing: false`, empty `children` and a non-null `closingElement`.

### 6.3 Fragments

```
interface JasxFragment <: Node {
  type: "JasxFragment";
  openingFragment: JasxOpeningFragment;
  children: [ JasxChild ];
  closingFragment: JasxClosingFragment;
}

interface JasxOpeningFragment <: Node {
  type: "JasxOpeningFragment";
}

interface JasxClosingFragment <: Node {
  type: "JasxClosingFragment";
}
```

### 6.4 Attributes

```
interface JasxAttribute <: Node {
  type: "JasxAttribute";
  name: JasxIdentifier | JasxNamespacedName | JasxEventName;
  value: JasxStringLiteral | JasxExpressionContainer | JasxElement | JasxFragment | null;
}
```

`value` is `null` for a shorthand attribute (`<input disabled />`). Attributes appear in source order.

```
interface JasxStringLiteral <: Node {
  type: "JasxStringLiteral";
  value: string;     // escape sequences processed and character references decoded
  raw: string;       // exact source text, including the quotes
}
```

### 6.5 Children

```
type JasxChild = JasxText | JasxElement | JasxFragment | JasxExpressionContainer | JasxStatementContainer
               | JasxStyleBlock;

interface JasxText <: Node {
  type: "JasxText";
  value: string;     // character references decoded; whitespace NOT normalized
  raw: string;       // exact source text
}
```

The parser does not normalize whitespace. Normalization is a semantic step (`semantics.md` §3), so the AST preserves the source exactly and formatters can round-trip it. Every maximal run of text between other children is one `JasxText` node, including runs that are whitespace only.

### 6.6 Expression containers

```
interface JasxExpressionContainer <: Node {
  type: "JasxExpressionContainer";
  expression: JavaExpression | JasxEmptyExpression;
}

interface JasxEmptyExpression <: Node {
  type: "JasxEmptyExpression";
  source: string;    // the whitespace and comments between the braces
}
```

The container's `range` includes the braces. The range of its `expression` excludes them. The closing `}` is scanned in Java mode, so it may be written as a Unicode escape (`grammar.md` §3); the container's range then ends after the whole escape.

### 6.7 Style blocks

```
interface JasxStyleBlock <: Node {
  type: "JasxStyleBlock";
  css: string;    // the text between <style> and </style>, exactly as written
}
```

A style block (`grammar.md` §7.3) appears in `JasxCompilationUnit.segments`, in `children` when it is written inside markup, or in `JavaStatement.segments` when it is written at the start of a statement in a statement container. Its `range` runs from the `<` of `<style>` to the end of `</style>`. Its `css` is not processed in any way: comments, whitespace and line terminators are kept. If the block is not closed (JASX1012), its range and its `css` run to the end of the file.

### 6.8 Statement containers

```
interface JasxStatementContainer <: Node {
  type: "JasxStatementContainer";
  statement: JavaStatement;
}

interface JasxMarkupStatement <: Node {
  type: "JasxMarkupStatement";
  markup: JasxElement | JasxFragment;
}
```

A statement container (`grammar.md` §6.7) is a child. Its `range` includes the braces, and the range of its `statement` excludes them; as for an expression container, the closing `}` may be a Unicode escape.

A markup statement's `range` starts at its markup and ends after the `;` that follows it, if there is one, or else at the end of the markup. Comments and whitespace between the markup and the `;` are inside the range.

---

## 7. Node summary

| Node | Parent field(s) where it appears |
|---|---|
| `JasxCompilationUnit` | root |
| `JavaCode` | `JasxCompilationUnit.segments`, `JavaExpression.segments`, `JavaStatement.segments` |
| `JavaExpression` | `JasxExpressionContainer.expression` |
| `JavaStatement` | `JasxStatementContainer.statement` |
| `JasxElement` | `segments`, `children`, `JasxAttribute.value`, `JasxMarkupStatement.markup` |
| `JasxFragment` | `segments`, `children`, `JasxAttribute.value`, `JasxMarkupStatement.markup` |
| `JasxStatementContainer` | `children` |
| `JasxMarkupStatement` | `JavaStatement.segments` |
| `JasxOpeningElement` | `JasxElement.openingElement` |
| `JasxClosingElement` | `JasxElement.closingElement` |
| `JasxOpeningFragment` | `JasxFragment.openingFragment` |
| `JasxClosingFragment` | `JasxFragment.closingFragment` |
| `JasxIdentifier` | element names, attribute names, qualified/namespaced parts |
| `JasxQualifiedName` | element names |
| `JasxNamespacedName` | `JasxAttribute.name` |
| `JasxAttribute` | `JasxOpeningElement.attributes` |
| `JasxStringLiteral` | `JasxAttribute.value` |
| `JasxText` | `children` |
| `JasxExpressionContainer` | `children`, `JasxAttribute.value` |
| `JasxEmptyExpression` | `JasxExpressionContainer.expression` |
| `JasxStyleBlock` | `JasxCompilationUnit.segments`, `children`, `JavaStatement.segments` |

---

## 8. Serialization

- The serialized form is JSON, valid against `ast.schema.json`.
- Field order is not significant, but implementations **SHOULD** emit `type`, `range`, `loc`, then the remaining fields in the order listed in this document, so that output diffs are readable.
- Implementations **MAY** add extra fields whose names start with `_` (e.g. `_cache`). Consumers **MUST** ignore unknown fields that start with `_`. Conformance comparison ignores them.
- A reference parser **SHOULD** offer a `--dump-ast` option that prints this JSON.

---

## 9. Example

Source (one line, no trailing newline):

```java
Element e = <div class="card">Hi</div>;
```

AST (abbreviated: `loc` omitted, ranges kept):

```json
{
  "type": "JasxCompilationUnit",
  "range": [0, 39],
  "specVersion": "1.0",
  "sourceFile": "example.jasx",
  "segments": [
    { "type": "JavaCode", "range": [0, 12], "source": "Element e = " },
    {
      "type": "JasxElement", "range": [12, 38],
      "openingElement": {
        "type": "JasxOpeningElement", "range": [12, 30],
        "name": { "type": "JasxIdentifier", "range": [13, 16], "name": "div" },
        "attributes": [{
          "type": "JasxAttribute", "range": [17, 29],
          "name": { "type": "JasxIdentifier", "range": [17, 22], "name": "class" },
          "value": { "type": "JasxStringLiteral", "range": [23, 29], "value": "card", "raw": "\"card\"" }
        }],
        "selfClosing": false
      },
      "children": [
        { "type": "JasxText", "range": [30, 32], "value": "Hi", "raw": "Hi" }
      ],
      "closingElement": {
        "type": "JasxClosingElement", "range": [32, 38],
        "name": { "type": "JasxIdentifier", "range": [34, 37], "name": "div" }
      }
    },
    { "type": "JavaCode", "range": [38, 39], "source": ";" }
  ]
}
```

The complete fixture, with `loc`, is `tests/parse/001-element.ast.json`.
