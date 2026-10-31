# JASX Diagnostics

**Spec version:** 1.0-draft

This document lists the diagnostics a conforming implementation reports, and defines their serialized form.

---

## 1. Format

A diagnostic is serialized as:

```json
{
  "code": "JASX1001",
  "severity": "error",
  "message": "closing tag </div> does not match <p> opened at 3:9",
  "range": [52, 58],
  "loc": { "start": { "line": 3, "column": 16 }, "end": { "line": 3, "column": 22 } },
  "related": [
    { "message": "<p> opened here", "range": [45, 48],
      "loc": { "start": { "line": 3, "column": 9 }, "end": { "line": 3, "column": 12 } } }
  ]
}
```

| Field | Rule |
|---|---|
| `code` | Required. One of the codes below. |
| `severity` | Required. `"error"` or `"warning"`. |
| `message` | Required. Human-readable. **Not** compared by the conformance suite; wording is up to the implementation. |
| `range`, `loc` | Required. The primary location, using the conventions of `ast.md` §3. |
| `related` | Optional. Secondary locations. Not compared by the conformance suite. |

The human-readable console form **SHOULD** be:

```
<file>:<line>:<column+1>: <severity>: <message> [<code>]
```

(Columns are 1-based in console output for compatibility with javac and editors, and 0-based in JSON.)

---

## 2. Code ranges

| Range | Category | Reported by |
|---|---|---|
| `JASX1xxx` | Syntax errors | Parser |
| `JASX20xx` | Core semantic errors (components, keys) | Compiler (resolution and lowering) |
| `JASX21xx` | Target capability errors, checked against the target descriptor and schema | Compiler (lowering) |
| `JASX3xxx` | Warnings. Each warning is defined either by the core specification or by a target document | Compiler |

Codes are never renumbered or reused.

---

## 3. Syntax errors (JASX1xxx)

| Code | Condition | Primary location |
|---|---|---|
| JASX1001 | Closing tag name does not match the opening tag name | The closing element |
| JASX1002 | Unclosed element or fragment at end of input | The opening element or fragment |
| JASX1003 | Unclosed expression container at end of input | The `{` |
| JASX1004 | Duplicate attribute name on one element | The second occurrence |
| JASX1005 | Invalid attribute value | The value |
| JASX1006 | Unexpected token in markup | The token |
| JASX1007 | Spread attribute `{...expr}` (reserved) | The container |
| JASX1008 | Empty expression container used as an attribute value | The container |
| JASX1009 | `}` or `>` in text | The character |
| JASX1010 | Unknown named character reference | The reference |
| JASX1011 | Identifier ends with `-`, or qualified name contains `-` | The identifier |

---

## 4. Semantic and target errors (JASX2xxx)

| Code | Condition | Primary location |
|---|---|---|
| JASX2001 | Component name cannot be resolved | The element name |
| JASX2002 | Component name is ambiguous | The element name |
| JASX2003 | Children given to a component that takes no `children` | The first child |
| JASX2004 | A component with a single `children` parameter received zero or several children | The element |
| JASX2005 | Unknown prop for a component | The attribute |
| JASX2006 | Overloaded component (two components with the same name in one class) | The second method name |
| JASX2007 | Missing required prop | The opening element |
| JASX2008 | `children` given as an attribute | The attribute |
| JASX2009 | Invalid `key` value (not a string literal or expression) | The attribute |
| JASX2010 | `@jasx.Component` on a method that is not static, does not return `Element`, or has a name starting with a lowercase letter | The annotation |
| JASX2011 | `@jasx.Default` value is not a single Java literal | The annotation value |
| JASX2101 | Attribute not defined by the target schema | The attribute |
| JASX2102 | Children on a void element | The first child |
| JASX2103 | Java event handler on a target without event handlers | The attribute value |
| JASX2104 | *Reserved* for reactive targets (not defined in 1.0) | — |
| JASX2105 | Tag not defined by the target schema | The element name |

---

## 5. Warnings (JASX3xxx)

No warnings are defined in 1.0. These codes are **reserved** for a possible reactive target (`targets/live.md`, deferred, not decided), and implementations **MUST NOT** report them:

| Code | Reserved for |
|---|---|
| JASX3001 | A state read outside a reactive position |
| JASX3002 | A list produced inside markup without keys, on a target that reconciles lists |

---

## 6. javac diagnostics

Errors that javac reports on generated code are not JASX diagnostics. A conforming compiler **MUST** remap their positions to the `.jasx` source (`semantics.md` §6.6) and **MUST** report them with javac's own message and the prefix `javac:` in place of a JASX code.

When a javac error is caused by the shape of the generated code rather than by the user's Java (for example, an "effectively final" error caused by a lambda that a target's custom lowering introduced), the compiler **MUST** attach a related note that explains the cause in terms of the `.jasx` source.
