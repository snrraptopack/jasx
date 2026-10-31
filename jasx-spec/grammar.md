# JASX Grammar

**Spec version:** 1.0-draft

This document defines the syntax of JASX. It extends the *Java Language Specification* (JLS, Java SE 21 or later); productions not defined here are those of the JLS. Grammar notation follows the JLS: terminals are in `code`, `?` marks an optional symbol, and alternatives are written on separate lines.

---

## 1. Overview

A JASX source file is a Java compilation unit in which **markup** may appear wherever a Java *primary expression* may appear. Markup is either an element (`<div>…</div>`, `<br />`) or a fragment (`<>…</>`).

JASX extends the Java grammar in two places:

- **Markup expressions** (below, §4–§6), the main extension.
- **The compilation unit** (§7): a `.jasx` file may declare fields and methods at the top level, outside any class (§7.2).

Markup is added as a new kind of primary expression:

```
Primary:
    PrimaryNoNewArray
    ArrayCreationExpression
    JasxElement
    JasxFragment
```

Because markup is a primary expression, it can be returned, assigned, passed as an argument, used as an operand of the conditional operator, or appear in a lambda body or a switch expression arm.

---

## 2. Source text

### 2.1 Encoding

A JASX source file is a sequence of Unicode code points, encoded in UTF-8. Unicode escapes (`\uXXXX`, JLS §3.3) are processed **only inside `JavaCode` spans**, exactly as javac processes them. They are **not** processed inside markup.

### 2.2 Line terminators

Line terminators are those of JLS §3.4: LF, CR, and CR LF.

---

## 3. Two lexical modes

A conforming parser operates in two lexical modes and switches between them:

| Mode | Active | Recognizes |
|---|---|---|
| **Java mode** | At the start of the file, and inside every `JasxExpressionContainer` | Java tokens (JLS §3), well enough to skip comments, string literals, text blocks and character literals, to count braces, and to identify the previous significant token |
| **Markup mode** | Inside markup, outside expression containers | The tokens defined in §5 |

A parser **MUST NOT** need to build a Java syntax tree. It needs only a Java tokenizer.

The Java-mode tokenizer **MUST**:

- process Unicode escapes before recognizing tokens, as javac does (JLS §3.3). `\u007B` is a `{` and `\u0022` begins or ends a string literal;
- recognize multi-character operators as single tokens (`::`, `->`, `&&`, `>>>=`, …), so that, for example, the `:` of `::` is never taken as a conditional-operator `:`;
- keep a stack of unclosed `(`, `[` and `{` tokens, recording for each `{` whether it opens an **array initializer** (§4.2), and for each `(` the token that precedes it.

---

## 4. Markup detection

### 4.1 The rule

In Java mode, a `<` token begins markup **if and only if** the previous significant token is a member of the set **S** below. Otherwise, `<` is a Java token (a relational operator or the start of type arguments or type parameters).

Comments and whitespace are not significant tokens. If there is no previous significant token, `<` does not begin markup.

**S** (markup-introducing tokens):

| Category | Tokens |
|---|---|
| Keywords | `return`; `yield` **only when it begins a statement** (§4.3) |
| Separators | `(` `[` `,` |
| Braces | a `{` that opens an **array initializer** (§4.2) |
| Assignment operators | `=` `+=` `-=` `*=` `/=` `%=` `&=` `\|=` `^=` `<<=` `>>=` `>>>=` |
| Lambda and switch arrows | `->` |
| Conditional operator | `?` `:` |
| Conditional logical operators | `&&` `\|\|` |
| Markup | the `{` that opens a `JasxExpressionContainer`, and the `=` of a `JasxAttribute` |

A `{` that opens a type body, a block, a lambda body or a switch block is **not** in **S**.

### 4.2 Array-initializer braces

A `{` in Java mode opens an array initializer if and only if:

- its previous significant token is `=` or `]` (`Element[] xs = {`, `new Element[] {`), or
- its previous significant token is `{` or `,`, and the innermost unclosed bracket (`(`, `[` or `{`) is itself an array-initializer `{` (nested initializers: `{{`, `{a, {`).

Every other Java-mode `{` (after `)`, `->`, an identifier, `;`, `}`, `else`, `try`, `do`, …) opens a type body, a block, a lambda body or a switch block.

### 4.3 `yield`

`yield` is a contextual keyword. It is a member of **S** only when it begins a statement, which a parser determines from the token before `yield`:

- `;` (when the innermost unclosed bracket is not `(`), `}`, `else`, `do`, or no token;
- a `{` that is not an array-initializer brace;
- `)` or `:`.

Everywhere else, `yield` is an identifier (a variable named `yield`) and `<` after it is Java.

Two constructs are not supported because the `)` and `:` cases cannot tell them apart from a statement start by tokenizing: a cast applied to a variable named `yield` that is immediately followed by `<` (`(int) yield < 2`), and a conditional expression whose `:` operand is a variable named `yield` that is immediately followed by `<` (`c ? a : yield < 2`). Write `((int) yield) < 2` or `c ? a : (yield < 2)` instead.

### 4.4 Why the rule is exact

No production of the JLS derives an expression that begins with `<`. In Java, `<` appears only

- as a binary operator, which always follows a complete operand (an identifier, a literal, `)`, `]`, or a keyword such as `this`), or
- as the start of type arguments or type parameters, which follow an identifier, `.`, `::`, `new`, or a modifier, **or** a `{` or `;` or `}` in two declaration forms:
  - a generic method or constructor declaration without modifiers at the start of a type body: `class A { <T> void m() {} }`;
  - an explicit generic constructor invocation at the start of a constructor body: `A() { <String>this("x"); }`.

In both declaration forms, the `{` opens a type body or a block, never an array initializer, so it is not in **S**. Conversely, markup cannot directly follow a block-opening `{`, because an element is not a statement expression (JLS §14.8) and so cannot begin a statement.

None of the other tokens that precede a Java `<` is in **S**. Therefore, in a syntactically valid program, a `<` that follows a token in **S** cannot be Java, and a `<` that follows any other token cannot be markup. The only exceptions are the `yield` constructs listed in §4.3 and the cast in §4.5, and both are explicitly unsupported.

### 4.5 Consequences

- `List<String> xs = <ul/>;` — the first `<` follows the identifier `List`, so it's Java. The second follows `=`, so it's markup.
- `if (a < b)` — `<` follows the identifier `a`, so it's Java.
- `this.<T>make()` — `<` follows `.`, so it's Java.
- `(Element) <div/>` — `<` follows `)`, so it is parsed as Java and the program is a Java syntax error. **Casting markup directly is not supported.** Write `(Element) (<div/>)` instead. (Markup has static type `Element`, so a cast is never needed.)
- `x -> <li/>` — `<` follows `->`, so it's markup.
- `cond ? <a/> : <b/>` — both follow `?` and `:`, so both are markup.
- `Element[] xs = { <a/>, <b/> };` — the `{` follows `=`, so it opens an array initializer and `<a/>` is markup. `<b/>` follows `,`.
- `class A { <T> void m() {} }` — the `{` opens a type body, so `<T>` is Java.
- `A() { <String>this("x"); }` — the `{` opens a block, so `<String>` is Java.
- `return yield < 2;` — `yield` follows `return`, so it is an identifier and `<` is Java.
- `default -> { yield <br/>; }` — `yield` follows a block-opening `{`, so it begins a statement and `<br/>` is markup.

---

## 5. Markup lexical grammar

The following tokens are recognized in markup mode.

```
JasxIdentifier:
    JavaLetter JasxIdentifierPart*

JasxIdentifierPart:
    JavaLetterOrDigit
    -
```

`JavaLetter` and `JavaLetterOrDigit` are as defined in JLS §3.8. A `JasxIdentifier` **MUST NOT** end with `-`.

```
JasxStringLiteral:
    " JasxStringCharacter* "

JasxStringCharacter:
    InputCharacter but not " or \
    EscapeSequence
```

`InputCharacter` and `EscapeSequence` are as defined in JLS §3.4 and §3.10.7. Attribute strings use Java escape semantics (`\n`, `\"`, `\\`, and so on). `\u0041` is **not** processed, because Unicode escapes are not processed in markup (§2.1). Single-quoted attribute strings are not supported.

```
JasxText:
    JasxTextCharacter+

JasxTextCharacter:
    SourceCharacter but not one of { } < >
    CharacterReference

CharacterReference:
    & EntityName ;
    &# DecimalDigits ;
    &#x HexDigits ;
```

`EntityName` is one of the named character references of the HTML Living Standard. Character references are decoded in the AST's `value` field (see `ast.md`). A `}` or `>` in text is an error (JASX1009); write `&#125;` or `&gt;`.

---

## 6. Markup syntactic grammar

### 6.1 Elements

```
JasxElement:
    JasxSelfClosingElement
    JasxOpeningElement JasxChildren? JasxClosingElement

JasxSelfClosingElement:
    < JasxElementName JasxAttributes? / >

JasxOpeningElement:
    < JasxElementName JasxAttributes? >

JasxClosingElement:
    < / JasxElementName >
```

### 6.2 Fragments

```
JasxFragment:
    < > JasxChildren? < / >
```

### 6.3 Element names

```
JasxElementName:
    JasxIdentifier
    JasxQualifiedName

JasxQualifiedName:
    JasxIdentifier . JasxIdentifier
    JasxQualifiedName . JasxIdentifier
```

The identifiers of a `JasxQualifiedName` **MUST NOT** contain `-`.

### 6.4 Attributes

```
JasxAttributes:
    JasxAttribute
    JasxAttributes JasxAttribute

JasxAttribute:
    JasxAttributeName JasxAttributeInitializer?

JasxAttributeName:
    JasxIdentifier
    JasxNamespacedName

JasxNamespacedName:
    JasxIdentifier : JasxIdentifier

JasxAttributeInitializer:
    = JasxAttributeValue

JasxAttributeValue:
    JasxStringLiteral
    JasxExpressionContainer
    JasxElement
    JasxFragment
```

An attribute without an initializer (`<input disabled />`) has the value `true` (see `semantics.md`).

**Reserved:** `{...expr}` (spread attributes) is reserved for a future version and is an error in 1.0 (JASX1007).

### 6.5 Children

```
JasxChildren:
    JasxChild
    JasxChildren JasxChild

JasxChild:
    JasxText
    JasxElement
    JasxFragment
    JasxExpressionContainer
```

### 6.6 Expression containers

```
JasxExpressionContainer:
    { JavaExpression? }
```

The content between `{` and the matching `}` is scanned in **Java mode**. Brace matching counts `{` and `}` tokens in Java mode only (braces inside comments, strings, text blocks and character literals are ignored), and markup nested inside the expression is parsed recursively according to §4.

A container whose content is empty, or contains only whitespace and comments, is a `JasxEmptyExpression`. An empty container is permitted as a child (it renders nothing, and is the idiomatic way to write a comment in markup: `{/* note */}`) but not as an attribute value (JASX1008).

`JavaExpression` is not parsed by JASX. Its validity as a Java expression is checked by javac after lowering.

---

## 7. Compilation unit

### 7.1 Segments

```
JasxCompilationUnit:
    JasxUnitSegment*

JasxUnitSegment:
    JavaCode
    JasxElement
    JasxFragment
```

`JavaCode` is a maximal run of source text scanned in Java mode that contains no markup. A JASX source file containing no markup is a single `JavaCode` segment and is a valid Java compilation unit.

### 7.2 Top-level members

In Java, the top level of a compilation unit (after the package and import declarations) holds only type declarations. In a `.jasx` file it may also hold field and method declarations:

```
TopLevelClassOrInterfaceDeclaration:
    ClassDeclaration
    InterfaceDeclaration
    ;
    JasxTopLevelMember

JasxTopLevelMember:
    FieldDeclaration
    MethodDeclaration
```

Top-level members are Java source. The parser does not recognize them: they are part of `JavaCode` segments, and only the compiler's declaration scan finds them. What they mean (they become static members of the file's **file class**) is defined in `semantics.md` §9. Constructors and initializer blocks are not top-level members.

Markup detection (§4) is unchanged at the top level. `;` and `}` are not in **S**, so a generic method declared without modifiers (`<T> Element ListView(...)`) is Java, exactly as it is at the start of a type body.

---

## 8. Early errors

The following are syntax errors that a conforming parser **MUST** report, with the given diagnostic code (see `diagnostics.md`), before any lowering takes place.

| Condition | Code |
|---|---|
| The `JasxElementName` of a `JasxClosingElement` is not identical to that of its `JasxOpeningElement` | JASX1001 |
| End of input inside markup (an unclosed element or fragment) | JASX1002 |
| End of input inside a `JasxExpressionContainer` | JASX1003 |
| Two attributes of the same element have the same `JasxAttributeName` | JASX1004 |
| An attribute value is not a string literal, expression container, element or fragment | JASX1005 |
| An unexpected token in markup mode | JASX1006 |
| A spread attribute `{...expr}` | JASX1007 |
| A `JasxEmptyExpression` used as an attribute value | JASX1008 |
| `}` or `>` appears in `JasxText` | JASX1009 |
| An unknown named character reference | JASX1010 |
| A `JasxIdentifier` ends with `-`, or a qualified name contains `-` | JASX1011 |

Two element names are **identical** if they have the same AST shape and the same identifier strings. `<a.b>` closes only with `</a.b>`.
