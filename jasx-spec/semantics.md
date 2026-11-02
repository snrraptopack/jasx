# JASX Semantics

**Spec version:** 1.0-draft

This document defines what JASX markup **means** independently of any target. It covers how element names are classified, how text and attributes are normalized, how components are resolved and called, the standard **factory lowering**, the **target interface** through which targets plug in, the classification of **static and dynamic positions**, **file classes** for top-level declarations, scoped **style blocks**, and **statement containers** (children produced by Java statements).

JASX relates to its targets the way JSX relates to React, Preact or Solid. The language defines syntax and a standard transform to calls on a factory supplied by a library. A target is that library. It supplies the factory and an attribute schema and, optionally, its own lowering. Target-specific behaviour is specified separately, under [`targets/`](targets/).

All rules operate on the AST defined in `ast.md`.

---

## 1. Static type of markup

Every `JasxElement` and `JasxFragment` expression has the static Java type **`jasx.runtime.Element`**.

`jasx.runtime.Element` is an interface defined by the core runtime artifact (§6.2). Every target's element types implement it. Lowering **MUST** produce a Java expression whose type is assignable to `jasx.runtime.Element`.

---

## 2. Element name classification

Each `JasxElement` is classified from its opening element's `name`:

| Name | Classification | Example |
|---|---|---|
| `JasxIdentifier` containing `-` | **Intrinsic** (custom element) | `my-widget` |
| Other `JasxIdentifier` whose first character is a lowercase letter (`Character.isLowerCase`) | **Intrinsic** | `div` |
| Other `JasxIdentifier` | **Component** | `TodoItem` |
| `JasxQualifiedName` | **Component** | `Ui.Card`, `app.ui.Card` |

Intrinsic elements are defined by the target's schema (§7.3). Component elements are resolved through the component index (§5).

---

## 3. Children normalization

Before lowering, the `children` of every element and fragment are normalized to a list of **child values**.

### 3.1 Text

Each `JasxText` child is transformed by this algorithm (the same algorithm JSX uses):

1. Take the node's `value` (character references already decoded).
2. Split it into lines on line terminators.
3. For every line **except the first**, remove leading whitespace (spaces and tabs).
4. For every line **except the last**, remove trailing whitespace.
5. Discard lines that are now empty.
6. Join the remaining lines with a single space (U+0020).
7. If the result is empty, the child is removed. Otherwise, it becomes a **text child value**.

Consequences:

```java
<p>
  Hello,
  world
</p>
// → one text child: "Hello, world"

<p>Count: {n}</p>
// → text "Count: " (trailing space kept: single line), then the expression

<p>
  {a}
  {b}
</p>
// → no text between a and b (whitespace-only lines are dropped)
// write {a}{" "}{b} to keep a space
```

### 3.2 Expression containers

- A container with a `JasxEmptyExpression` is removed.
- A container with a `JavaExpression` becomes an **expression child value**.

### 3.3 Elements and fragments

Nested `JasxElement` and `JasxFragment` children become **element child values**.

A `JasxStyleBlock` child (§10) is removed: its CSS belongs to the file's style sheet, not to the element it is written in.

### 3.4 Child value contract

Every target **MUST** interpret an expression child value according to its runtime type as follows:

| Runtime value | Meaning |
|---|---|
| `null` | nothing |
| `jasx.runtime.Element` | that element |
| `java.util.Optional` | its value if present (recursively), otherwise nothing |
| `java.lang.Iterable`, array, `java.util.stream.Stream` | each item in order (recursively, flattened) |
| any other value, including `Boolean` | text: `String.valueOf(value)` |

A `Boolean` is text (`true` / `false`) and is not hidden, because JASX does not change Java's semantics. Conditional rendering uses the conditional operator: `{flag ? <A/> : null}`.

This contract is part of the language, not of a target, so that a component means the same thing on every target. How a target escapes text (for example HTML-escaping), and how it inserts raw content (for example a `rawHtml(String)` call), is target-defined.

### 3.5 Statement containers

A `JasxStatementContainer` (`grammar.md` §6.7) becomes a **statement child value**. Its value is a list: its statement is executed once, when the markup expression is evaluated, and every markup statement executed during that run appends the value of its markup to the list, in execution order. A markup statement inside a loop therefore appends once per iteration, and one in a branch not taken appends nothing.

The list is interpreted by §3.4 like any `Iterable`: its items are the children, in order, where the container is written. A statement container that appends nothing renders nothing.

The statement is ordinary Java and runs in the scope where the markup is written: it may read and assign local variables (an assignment is visible after the markup expression), declare its own, call methods, and use `break` and `continue` for loops inside it. It **MUST NOT** use `return`, or `break`, `continue` or `yield` that would leave the container; javac rejects them (§6.8).

---

## 4. Attribute normalization

Each `JasxAttribute` is normalized to a **name** and an **attribute value**:

| AST `value` | Attribute value |
|---|---|
| `null` (shorthand) | the Java literal `true` |
| `JasxStringLiteral` | the Java string literal with the same `value` |
| `JasxExpressionContainer` | its Java expression |
| `JasxElement` / `JasxFragment` | the lowered markup expression (type `Element`) |

The name is the attribute's source name: the `JasxIdentifier.name`, or `namespace:name` for a `JasxNamespacedName`.

### 4.1 Reserved attributes

| Name | Meaning | Applies to |
|---|---|---|
| `key` | Identity of the element among its siblings (§6.4). Never passed to the attribute builder or the component. | All elements |

A `key` attribute value **MUST** be an expression container or a string literal (JASX2009).

---

## 5. Components

### 5.1 What a component is

A **component** is a Java method that:

1. is `static` (methods at the top level of a `.jasx` file are, §9),
2. has return type `jasx.runtime.Element`,
3. has a name whose first character is **not** a lowercase letter (the same test as §2, so that a component can be named by a tag),
4. is declared in a `.jasx` file, or is annotated with `@jasx.Component` in a `.java` file,
5. has a name that is unique among the components of its declaring class (**components cannot be overloaded**, JASX2006).

Methods in `.jasx` files that satisfy (1)–(3) are components automatically. The compiler adds `@jasx.Component` to them in the generated Java, on the same line as the method header, unless they already have it. Static methods with lowercase names that return `Element` (helpers such as `renderRow`) are ordinary Java methods: they are not indexed, they can be overloaded, and they are called as Java (`{renderRow(r)}`).

`@jasx.Component` on a method that violates (1)–(3) is an error (JASX2010).

### 5.2 Parameters are props

Each parameter of a component is a **prop**, named by the parameter name.

| Parameter | Meaning |
|---|---|
| Any parameter | A required prop. |
| Parameter annotated `@jasx.Optional` | An optional prop. If the attribute is absent, the argument is `null` (or the default value of the primitive type). |
| Parameter annotated `@jasx.Default("literal")` | An optional prop. If the attribute is absent, the argument is the given Java **literal**. |
| Parameter named `children` of type `jasx.runtime.Element` or `java.util.List<jasx.runtime.Element>` | Receives the element's children (§5.5). It **MUST NOT** also be given as an attribute (JASX2008). |

An annotation element can only hold a constant, so the annotation's string value is the literal's **source text**: `@jasx.Default("5")` for the `int` 5, and `@jasx.Default("\"Untitled\"")` for the string `Untitled`. The annotation's value **MUST** be written as a string literal (`@jasx.Default("…")` or `@jasx.Default(value = "…")`), and its content **MUST** be the source text of a single Java literal: an integer, floating-point, boolean, character, string or text-block literal, optionally preceded by `-` for numbers (JASX2011). A literal means the same thing at every call site, whatever that site's imports and scope. For any other default, declare the prop `@jasx.Optional` and compute the default in the component body:

```java
static Element Timer(@jasx.Optional Duration every) {
  var interval = every != null ? every : Duration.ofSeconds(5);
  ...
}
```

### 5.3 The component index

To lower `<TodoItem todo={t} />`, the compiler must know the declaring class of `TodoItem` and the order and names of its parameters. Java class files do not reliably preserve parameter names, and JASX does not perform Java symbol resolution. So JASX maintains a **component index**.

Each index entry records:

```json
{
  "class": "app.TodoPage",
  "method": "TodoItem",
  "params": [
    { "name": "todo",     "type": "app.Todo",                         "kind": "required" },
    { "name": "onToggle", "type": "java.util.function.Consumer<java.lang.Integer>", "kind": "required" },
    { "name": "label",    "type": "java.lang.String", "kind": "default", "default": "\"Untitled\"" }
  ],
  "children": "none"
}
```

`params` lists **every** parameter in declaration order, including the `children` parameter, so that the compiler can place each argument. `kind` is `"required"`, `"optional"`, `"default"` or `"children"`. `default` is the literal's source text (§5.2). `children` is one of `"none"`, `"single"` (`Element`) or `"list"` (`List<Element>`). Parameter types are informational: javac checks them after lowering.

`META-INF/jasx/components.json` is a JSON array of such entries.

The index is built from three sources:

| Source | How |
|---|---|
| `.jasx` files being compiled | The compiler reads component method headers directly. |
| `.java` files in the same source set | The compiler scans for `@jasx.Component` (or `@Component` with the matching import) and reads the method header that follows. Only the method header is read, never the body. |
| Dependencies (jars) | The JASX annotation processor writes `META-INF/jasx/components.json` into every compiled artifact. The compiler reads this resource from each classpath entry. |

Reading a method header requires only tokenizing: modifiers, return type, name, and the parameter list with annotations, types and names. No Java syntax tree is required.

### 5.4 Name resolution

A component element name is resolved against the index, using the imports of the `.jasx` file (also read by tokenizing):

1. **Simple name** `<N>`, in order:
   1. a component `N` declared in the same class as the markup, or in an enclosing class;
   2. a component imported with `import static C.N;`;
   3. a component imported by `import static C.*;`.
2. **Qualified name** `<Q.N>`: `Q` is resolved as a Java type name (same package, single-type import, on-demand import, or fully qualified), and `N` is a component of that class.

If no component is found, it's an error (JASX2001). If a simple name matches through more than one step-3 import, it's an error (JASX2002).

### 5.5 Children of components

- `"none"`: the element **MUST** have no child values (JASX2003).
- `"single"`: the element **MUST** have exactly one child value, which is passed as the argument (JASX2004 otherwise).
- `"list"`: all child values are passed as a `List<Element>`, in order (possibly empty).

A child value passed to a component is converted to an `Element`: markup as it is, a text child value with the factory's `text` function, and an expression child value with `fragment((Object) expr)` (§6.2), so that the child value contract (§3.4) still applies to it. For example, a `null` expression becomes an empty fragment, not a `null` argument.

### 5.6 Component lowering

A component element lowers to a **direct, positional static method call**:

```java
<TodoItem onToggle={toggle} todo={t} />
```

lowers to

```java
app.TodoPage.TodoItem(t, toggle)
```

- Arguments are placed in **parameter order**, whatever the attribute order in the source.
- Arguments are therefore evaluated in parameter order, by Java's ordinary left-to-right argument evaluation. This differs from JSX, which evaluates props in source order. Prop expressions should not depend on one another's side effects.
- Because the call targets the real method, javac infers lambda parameter types from the parameter types, performs generic inference, and reports type errors.
- An absent optional prop is passed as `null` or the primitive default. An absent default prop is passed as its literal.
- Unknown attribute: JASX2005. Missing required prop: JASX2007.

Generated call sites always use the fully qualified class name, so lowering doesn't depend on the generated file's imports.

A target with a custom lowering (§7.4) **MAY** wrap this call in a target-defined function (for example to create a component instance), but the method call itself **MUST** be as above.

### 5.7 Generic components

A generic component is resolved and called like any other:

```java
static <T> Element ListView(List<T> items, Function<T, Element> render) { ... }

<ListView items={users} render={u -> <li>{u.name()}</li>} />
// → app.Views.ListView(users, u -> ...)   (javac infers T)
```

Explicit type arguments in markup are not supported in 1.0.

---

## 6. Factory lowering

### 6.1 Overview

Lowering replaces every top-level markup segment of the compilation unit with a Java expression, and leaves every `JavaCode` segment unchanged, **byte for byte**.

The standard lowering is **factory lowering**: intrinsic elements and fragments become calls to static functions on the target's **factory class**, and components become direct calls (§5.6). This is the analogue of JSX's transform to `jsx()` / `createElement()` calls on the library named by `jsxImportSource`. The factory class is named by the target descriptor (§7.2).

A target **MAY** replace factory lowering with its own (§7.4). The requirements of §5.6 and §6.4–§6.7 apply to every lowering.

### 6.2 Core runtime and factory contract

The **core runtime** artifact is target-independent and contains only:

| Name | Purpose |
|---|---|
| `jasx.runtime.Element` | The static type of markup (§1). |
| `@jasx.Component`, `@jasx.Optional`, `@jasx.Default` | Component annotations (§5). |

A target's **factory class** `F` provides these static functions:

| Function | Purpose |
|---|---|
| `Element F.h(String tag, A attrs, Object... children)` | Create an intrinsic element. `A` is the attribute builder type for `tag` (§6.3). |
| `Element F.fragment(Object... children)` | Create a fragment. |
| `Element F.keyed(Object key, Element element)` | Attach a key (§6.4). |
| `Element F.text(String value)` | Create a text element (used where a child must be an `Element`). |

A target that supports style blocks also provides `styleSheet`, `scoped` and `unscoped` (§10.5).

Each child passed to `h` or `fragment` is a child value (§3). Text child values are passed as Java string literals, and expression child values as their Java expression. All of them **MUST** be interpreted by the child value contract (§3.4).

### 6.3 Intrinsic lowering

An intrinsic element lowers to a call to `F.h` with a **typed attribute builder** for its tag, generated from the target's schema:

```java
<button class="primary" disabled={busy}>Save</button>
```

lowers (HTML target, conceptually) to

```java
jasx.html.Html.h("button",
    jasx.html.attrs.Button.create()
        .className("primary")
        .disabled(busy),
    "Save")
```

The builder class for a tag is `<attributes>.<PascalCase(tag)>` (`button` → `Button`, `my-widget` → `MyWidget`), where `<attributes>` is the package named by the target descriptor, unless the schema names another class. Builders are created with a static `create()` method. An element with no attributes **MAY** pass `null` instead of a builder.

Attribute builder methods are named by the schema. The default mapping:

| Attribute name | Builder method |
|---|---|
| `kebab-case` | `camelCase` (`tab-index` → `tabIndex`) |
| A Java keyword | schema alias (`class` → `className`, `for` → `htmlFor`) |
| `data-*`, `aria-*`, and names allowed by a schema extension | `.attr("name", value)` |
| `ns:name` | `.attr("ns:name", value)` |

A tag the schema doesn't define is an error (JASX2105). An attribute the schema doesn't define is an error (JASX2101). Because each builder method has a declared parameter type, javac types every lambda (for example `onClick(Consumer<ClickEvent>)` on a target with event handlers).

### 6.4 Keys

`key={k}` on any element lowers to `F.keyed(k, <lowered element>)`. Keys have no meaning in the core language. A target that reconciles element lists (as React does with JSX keys) defines what they mean. A target without reconciliation ignores them.

### 6.5 Line preservation

Lowering **MUST** place the code generated for a markup span so that the generated Java starts on the same line as the markup, and so that each line of markup source corresponds to the same line in the generated file wherever the generated code allows. Lowering **MUST NOT** add or remove lines in `JavaCode` segments.

This makes most javac diagnostics and runtime stack traces carry correct `.jasx` line numbers without further mapping.

### 6.6 Source mapping

| Requirement | Mechanism |
|---|---|
| Compiler diagnostics | The compiler records a span map from generated positions to `.jasx` positions and remaps javac diagnostics through it. |
| Debuggers | The compiler installs a JSR-45 SMAP with a stratum named `JASX` in each class file's `SourceDebugExtension` attribute. |
| Runtime stack traces | Rely on §6.5. A runtime MAY provide a remapper for lines that couldn't be preserved. |

### 6.7 Generated-code requirements

- Every child **MUST** reach `h` or `fragment` as exactly one variadic argument. A lowering **MUST** prevent javac from treating a lone `null` or array child as the variadic array itself, for example by casting each expression child to `Object`. (Otherwise `<p>{null}</p>` would pass a `null` array.)
- Generated code **MUST** use fully qualified names for factory classes, builder classes and component classes.

### 6.8 Statement containers

A statement container lowers to a switch expression whose single `default` rule is a block, because a switch expression can hold statements and, unlike a lambda, can read and assign the enclosing local variables:

```java
(java.lang.Object) switch (0) { default -> {
    java.util.ArrayList<java.lang.Object> V = new java.util.ArrayList<>();
    S
    yield V; } }
```

- `S` is the container's statement, lowered as Java code with markup in it (`JavaCode` copied, markup expressions lowered as everywhere else), except that each **markup statement** becomes `V.add((java.lang.Object) M);`, where `M` is the lowering of its markup. A style block in the statement is removed (§10.5).
- `V` is a local variable name, chosen by the compiler, that starts with `jasx$` and is different from the name used by every other statement container of the file, so that nested containers don't clash.
- The switch expression is the child argument; like every child (§6.7), it is cast to `Object`.
- Line preservation (§6.5) applies: the text before `S` is placed on the line of the container's `{`, and the text after it on the line of its `}`.

The lowering is why `return` is rejected inside a statement container: javac doesn't allow `return` to jump out of a switch expression. A `yield` in the statement would leave the generated switch, so it is not allowed either; `break` and `continue` work for loops inside the statement.

---

## 7. Targets

### 7.1 Model

A **target** is a library that gives markup its meaning, like React, Preact or Solid for JSX. It consists of:

- a **descriptor** (§7.2), which is published as `META-INF/jasx/target.json` in the target's compiler-side artifact;
- a **factory class** and **attribute builders** (§6.2, §6.3);
- a **schema** of intrinsic elements and attributes (§7.3);
- optionally, a **custom lowering** (§7.4).

The build selects one target per source set by its `id`. All targets share the grammar, the AST, children normalization, the child value contract and component resolution. A component written against capabilities two targets share works on both.

### 7.2 Descriptor

```json
{
  "id": "html",
  "specVersion": "1.0",
  "factory": "jasx.html.Html",
  "attributes": "jasx.html.attrs",
  "schema": "schemas/html.json",
  "lowering": "factory",
  "eventHandlers": "none"
}
```

| Field | Values | Meaning |
|---|---|---|
| `id` | string | Target name used in build configuration. |
| `specVersion` | string | Spec version the target implements. |
| `factory` | class name | The factory class `F` (§6.2). |
| `attributes` | package name | Package of the generated attribute builders (§6.3). |
| `schema` | resource path | Element and attribute schema (§7.3). |
| `lowering` | `"factory"` or a class name | Factory lowering (§6), or the class implementing a custom lowering (§7.4). |
| `eventHandlers` | `"none"` or `"java"` | Whether event attributes accept Java expressions (such as lambdas) as handlers. Where and when a handler runs is defined by the target. |
| `styles` | `"none"` (the default) or `"css"` | Whether the target supports style blocks (§10). |
| `scripts` | `"none"` (the default) or `"module"` | Whether the target supports script blocks, browser attributes and refs (§11). |

A descriptor **MAY** contain other fields, which a compiler that doesn't know them ignores. The core defines no field for reactive targets: a reactive target such as `web` uses a custom lowering (§7.4).

### 7.3 Schema

The schema lists, for every intrinsic tag:

- its attributes, each with a Java type and a builder method name;
- its event attributes (kind `event`), each with a handler type such as `java.util.function.Consumer<jasx.event.ClickEvent>`;
- whether it may have children (void elements such as `br` and `input` may not, JASX2102).

A schema **MAY** declare **extensions**: attribute name patterns (for example `hx-*`) accepted through `.attr(name, value)`, and whether custom elements (tags containing `-`) are accepted with a generic builder.

### 7.4 Custom lowering

A target **MAY** provide its own lowering instead of factory lowering, as Solid replaces the JSX transform with its own compiler. A custom lowering receives the AST of each compilation unit, together with the normalized children, the classification and the resolved components, and produces Java source. It **MUST**:

- leave `JavaCode` segments unchanged, byte for byte (§6.1);
- call components as defined in §5.6;
- honour the child value contract (§3.4);
- meet the line preservation, source mapping and generated-code requirements (§6.5–§6.7).

The interface through which the compiler loads a custom lowering is defined by the reference implementation, not by this specification.

> **Note (non-normative).** In the reference implementation, the descriptor's `lowering` names a class implementing `jasx.compiler.MarkupLowering`, shipped in the target's artifact. The compiler analyses each markup expression first (every JASX error is reported whatever the lowering does) and gives the lowering a tree of the markup: normalized children, attributes with their §8 classification, and resolved component calls. The lowering decides what intrinsic elements, fragments, keys and children become. The user's Java code reaches it only as opaque pieces that it places but can't change, and the compiler writes component calls and statement containers itself, so the requirements above hold for every lowering. The standard factory lowering is the default implementation, and a target can extend it to change only some positions, for example to pass dynamic children as functions.

### 7.5 Capability errors

| Condition | Code |
|---|---|
| An attribute not in the schema or its extensions | JASX2101 |
| Children on a void element | JASX2102 |
| A Java expression as the value of an event attribute on a target with `eventHandlers: "none"` | JASX2103 |
| A tag not in the schema | JASX2105 |
| A style block on a target without `"styles": "css"` (§10.6) | JASX2106 |
| A script block, browser attribute or `ref` on a target without `"scripts": "module"` (§11.8) | JASX2107 |
| On a target with `"scripts": "module"`, an event attribute whose value is a string (§11.8) | JASX2108 |

JASX2104 is reserved for reactive targets; no target reports it yet.

### 7.6 Targets in this specification

The current goal is the **core language**: JASX as JSX is without React. The specification therefore defines only one target, `html`, as a small **reference target**. It is the simplest useful meaning for markup, and the render conformance tests use it to check core semantics (children normalization, the child value contract, component calls).

| Target | Status | Lowering | Specification |
|---|---|---|---|
| `html` | Normative reference target | factory | [`targets/html.md`](targets/html.md) |
| `web` | **Draft.** JASX's own server-driven target, implemented by `jasx-web` | custom | [`targets/web.md`](targets/web.md) |
| `javafx` | Idea only | custom | — |

Third-party targets are expected: a target needs no change to the language or the compiler, only a descriptor, a factory, builders and a schema, and optionally a custom lowering.

---

## 8. Static and dynamic positions

Every value position in markup is **static** or **dynamic**. This classification is purely syntactic and part of the core, so that every target, reactive or not, uses the same terms. The core says nothing about *when* or *how often* a dynamic position is evaluated. Factory lowering evaluates each one once, when the markup expression is evaluated, like any Java expression. A target with a custom lowering defines its own evaluation (for example, a reactive target could re-evaluate dynamic children when data changes).

| Position | Kind |
|---|---|
| A text child value (§3.1) | **static** |
| A string-literal attribute value, a shorthand attribute | **static** |
| An element or fragment, as a child or attribute value | static in itself; its own positions are classified separately |
| An expression child value (§3.2) | **dynamic child** |
| A statement child value (§3.5) | **dynamic child**; the markup of its markup statements is classified separately |
| An expression container as the value of an intrinsic attribute whose schema kind is not `event` | **dynamic attribute** |
| An expression container as the value of an intrinsic attribute of schema kind `event` | **handler** |
| An expression container as the value of a component prop | **prop** |
| An expression container as the value of `key` | **key** |

The **static skeleton** of a markup tree is its intrinsic elements and fragments, their static attributes and its static text, with each dynamic position marked as a hole. A target **MAY** use the skeleton to render or send static content once (for example, the `html` target could pre-render it to a constant string). Using the skeleton **MUST NOT** change the result of rendering.

---

## 9. File classes

Java requires every field and method to belong to a class, so a file of components needs a class whose only job is to hold them. A `.jasx` file may leave that class out and declare its fields and methods at the top level (`grammar.md` §7.2). The compiler then puts them in a class named after the file, its **file class**, much as Kotlin puts top-level functions in a class named after their file.

```java
// hello/ui/Layout.jasx
package hello.ui;

import jasx.runtime.Element;

public Element Badge(String text) {
  return <span class="badge">{text}</span>;
}
```

is the same as

```java
package hello.ui;

import jasx.runtime.Element;

public final class Layout {
  private Layout() {}

  public static Element Badge(String text) {
    return <span class="badge">{text}</span>;
  }
}
```

### 9.1 Which files have a file class

A `.jasx` file has a file class if and only if its top level contains at least one field or method declaration. A file without one is an ordinary Java compilation unit and is lowered as in §6.

### 9.2 The file class

- Its simple name is the file name without the `.jasx` extension (`Layout.jasx` → `Layout`). That name **MUST** be a Java identifier and not a keyword (JASX2012).
- It is in the package of the file, and it is `public` and `final`.
- It has a `private` constructor without parameters, and no members other than the file's top-level declarations.
- **Every** top-level declaration of the file becomes a member of the file class, in source order: fields, methods, and also class, interface, enum, record and annotation type declarations, which become nested types (`Layout.Theme`).
- Every member is `static`. Access modifiers are kept as written: a member without one has package access, as anywhere in Java, so a component meant for other packages is declared `public`.
- A constructor or an initializer block (`{ ... }` or `static { ... }`) at the top level is an error (JASX2013).

### 9.3 Components in a file class

A top-level method is static (§9.2), so it is a component when it returns `Element` and its name does not start with a lowercase letter (§5.1). Its class in the component index (§5.3) is the file class: `Badge` in `hello/ui/Layout.jasx` is indexed with `"class": "hello.ui.Layout"`. Other files use it like any other component, with `import static hello.ui.Layout.*;` and `<Badge text="new" />`, or as `<Layout.Badge text="new" />`.

### 9.4 Lowering

The compiler turns the file into its file class by inserting text into the `JavaCode` segments. None of the insertions adds a line break before the end of the file, so line preservation (§6.5) holds:

1. `public final class N { private N() {} `, where `N` is the file class name, is inserted directly after the `;` of the last package or import declaration, or at the start of the file if there is none.
2. `static ` is inserted into each top-level declaration that is not already `static`, after its annotations and modifiers and before the rest of the declaration (the type parameters, the type, or the `class`, `interface`, `enum`, `record` or `@interface` keyword).
3. A line terminator, if the file does not end with one, and `}` are appended at the end of the file.

---

## 10. Style blocks

A style block (`grammar.md` §7.3) holds CSS for the markup of its file. The CSS is **scoped**: its rules apply only to the intrinsic elements written in the same file, so two files can both style `.title` without affecting each other. The CSS is plain CSS; JASX adds nothing to it except `:global(...)` (§10.4).

A style block can be written among declarations, in a method before or between its statements (typically just before the `return`), or inside markup, next to the elements it styles. Either way it contributes to the **style sheet of the file**: all style blocks of a file share one scope name, and their rules apply to every intrinsic element of the file.

```java
<style>
  .card  { padding: 1rem; }
</style>

public Element Card(String title) {
  return (
    <div class="card">
      <style>
        .title { font-weight: 600; }
      </style>
      <h2 class="title">{title}</h2>
    </div>
  );
}
```

The core defines where a style block may appear, which elements it applies to, how its CSS is scoped and how it is lowered. How the CSS reaches the output (for example, as a `<style>` element in an HTML page) is defined by the target. Style blocks require a target that supports them (§10.6).

### 10.1 Placement

- A style block in Java code (`grammar.md` §7.3 detects it where a declaration or statement starts) **MUST** be inside a class: in the body of a class, interface, enum or record, in a method, constructor or initializer body, or at the top level of a file that has a file class (§9). At the top level of a file without a file class it is an error (JASX2015). In a body, it is not a statement: it is removed (§10.5), and it may be written before, between or after statements.
- A style block inside markup may be any child of an element or fragment. It is not a child value (§3.3), so it renders nothing where it is written, and it is not passed to a component as part of its children.
- A file may contain any number of style blocks.

### 10.2 What a style block applies to

A style block applies to every **intrinsic element written in its file**, in any class of the file. It does not apply to elements written in other files, even when they are passed into this file's markup (as `children`, for example). A component's elements are styled by the style block of the file that declares the component.

### 10.3 The scope name

Each file with a style block has a **scope name**: `jx-` followed by the first 8 hexadecimal digits, in lowercase, of the SHA-256 hash of the UTF-8 encoding of the file's **qualified name**. The qualified name is the package name, a `.`, and the file name without `.jasx` (`hello.ui.Layout` for `hello/ui/Layout.jasx` in package `hello.ui`), or only the file name without `.jasx` in the unnamed package. The scope name is a valid CSS class name.

### 10.4 Scoped CSS

The content of each style block is transformed into its **scoped CSS** as follows, and the **scoped CSS of the file** is the scoped CSS of its style blocks, in source order, joined with LF. The transformation reads CSS only as far as needed to find selectors; it never interprets properties or values.

**Reading.** The content is read as CSS text in which:

- a comment runs from `/*` to the next `*/`;
- a string runs from `"` or `'` to the next unescaped matching quote, and may not contain a line terminator;
- `\` escapes the next character;
- `(` with `)` and `[` with `]` are matched pairs. Inside them, commas and semicolons do not separate anything, and a brace is an error.

**Rules.** The content is a **rule list**. Each item of a rule list is either:

- an **at-rule**: `@name` and its prelude, ending at a `;` (`@import url(a.css);`) or followed by a block in braces;
- a **style rule**: a prelude, which is its **selector list**, followed by a block in braces.

The block of a **grouping at-rule** (`@media`, `@supports`, `@container`, `@layer`, `@scope`, `@starting-style`, `@document`) is again a rule list. The block of any other at-rule (such as `@keyframes`, `@font-face`, `@page` or `@property`) is copied unchanged.

Each item in the block of a style rule is a declaration, which ends at a `;` or at the end of the block, or, if a `{` comes before any `;`, a **nested rule** in CSS nesting syntax: an at-rule, read as above, or a nested style rule.

**Scoping a selector list.** Every selector list of a style rule, including nested ones, is split at the commas that are not inside parentheses, brackets or strings. Each part, with its surrounding whitespace removed, is a **complex selector**:

- If it is `:global(` *S* `)`, it is replaced by *S*, unscoped.
- Otherwise, its **last compound selector** is the text after the last **combinator** (whitespace, `>`, `+` or `~`) that is not inside parentheses, brackets or strings. A `.` followed by the scope name is inserted into that compound: before its first pseudo-element (`::` followed by a name, or one of `:before`, `:after`, `:first-line`, `:first-letter`), or at its end if it has none.

The parts are then joined with `, `. The text of each compound selector the scope name was inserted into, from its start to the insertion point, is a **scoped compound** of the file (for `ul > li:hover`, it is `li:hover`; for `.title::before`, `.title`).

| Selector | Scoped (with scope name `jx-1`) |
|---|---|
| `.card` | `.card.jx-1` |
| `ul > li:hover` | `ul > li:hover.jx-1` |
| `.title::before` | `.title.jx-1::before` |
| `a, b:not(.x, .y)` | `a.jx-1, b:not(.x, .y).jx-1` |
| `:global(body)` | `body` |
| `.dark .card` | `.dark .card.jx-1`: only the last compound is scoped, so `.dark` may match an element of another file |
| `&:hover` (nested) | `&:hover.jx-1` |

**Output.** Comments are removed. Then leading and trailing whitespace is removed from every line, empty lines are removed, and the remaining lines are joined with LF.

**Errors.** The following are errors (JASX2014), located at the offending text in the `.jasx` file:

- a comment, string, parenthesis, bracket or block that is not closed;
- a `}` that closes nothing, or a brace inside parentheses or brackets;
- a style rule with an empty selector list, or with an empty complex selector;
- the text `</style` in any letter case, which would end an HTML `<style>` element early.

### 10.5 Lowering

A target that supports style blocks provides two more factory functions (§6.2):

| Function | Purpose |
|---|---|
| `Object F.styleSheet(String scope, String css)` | Create a file's style sheet from its scope name and scoped CSS. |
| `Element F.scoped(Object sheet, Element element)` | Mark an intrinsic element as written in a file with the style sheet `sheet`, and as one its scoped CSS may apply to. |
| `Element F.unscoped(Object sheet, Element element)` | Mark an intrinsic element as written in a file with the style sheet `sheet`, but as one its scoped CSS cannot apply to. |

- The file's style sheet is the field `static final java.lang.Object jasx$styles = F.styleSheet("scope", "css");`, where `css` is the scoped CSS of the file as a Java string literal. It is declared in the **style class**: the file class, if the file has one, or otherwise the top-level class that contains the first style block. It is inserted at the start of the style class's body, on the same line: directly after the file class header (§9.4), or directly after the `{` that opens the class body.
- Every style block is removed from the generated code. The lines it spans remain, empty (§6.5).
- Every intrinsic element of the file that a scoped compound **may match** (below) lowers to `F.scoped(C.jasx$styles, e)`, and every other intrinsic element of the file to `F.unscoped(C.jasx$styles, e)`, where `e` is its lowering by §6.3 and `C` is the fully qualified name of the style class. A `key` is applied outside: `F.keyed(k, F.scoped(C.jasx$styles, e))`. Fragments and component calls are not wrapped.

A scoped compound **may match** an element unless its text shows that it cannot. Only the text outside parentheses, brackets and strings counts, so `:not(.x)` requires nothing. In that text, a **name** is a run of ASCII letters, ASCII digits, `-`, `_` and non-ASCII characters. The compound cannot match the element if:

1. it starts with a name (a **type selector**) that differs from the element's tag name, compared ASCII case-insensitively; or
2. it contains `.` followed by a name *c* (a **class selector**), and the element has no `class` attribute, or its `class` attribute is a string literal whose value, split at ASCII whitespace, does not contain *c*; or
3. it contains `#` followed by a name *i* (an **ID selector**), and the element has no `id` attribute, or its `id` attribute is a string literal whose value is not *i*.

A compound that contains `\`, `&` or `|` may match every element. A `class` or `id` attribute whose value is an expression, or that has no value, may have any value.

| Scoped compounds | Element | Lowers to |
|---|---|---|
| `li` | `<li>`, `<ul>` | `F.scoped(...)`, `F.unscoped(...)` |
| `.card` | `<div class="card big">`, `<div class="other">`, `<div>`, `<div class={c}>` | scoped, unscoped, unscoped, scoped |
| `a:hover`, `#main` | `<a>`, `<main id="main">`, `<p>` | scoped, scoped, unscoped |
| `:not(.x)` | any element | scoped |

Both functions mark the element as using the style sheet; only `F.scoped` elements need the scope name (§10.6). A file whose style sheet has only `:global(...)` rules has no scoped compounds, so all its elements lower to `F.unscoped`, and its style sheet is still used.

### 10.6 Target support

A target supports style blocks if its descriptor has `"styles": "css"` (§7.2). A style block in a file compiled for any other target is an error (JASX2106).

What a style sheet, a scoped element and an unscoped element mean, and how the scoped CSS is delivered, is target-defined. A target **SHOULD** give each scoped element, and no unscoped element, the scope name of its style sheet as a CSS class, so that the scoped CSS applies to it, and **SHOULD** make the scoped CSS of the scoped and unscoped elements it renders available to the application.

## 11. Browser scripts

A target that runs pages in a browser can let a file carry JavaScript for it: **script blocks** (`grammar.md` §7.4) hold the file's JavaScript, **browser attributes** such as `@click` (`grammar.md` §6.4) run it for an event of an element, and **refs** (`ref="name"`) name elements for it. Java event attributes (`onclick={...}`) stay Java; browser attributes are JavaScript, run in the browser.

```java
<script>
  import { toggleMenu } from "./menu.js";
</script>

Element Menu(Action save) {
    return (
        <div>
            <button @click="toggleMenu({menu: $menu, save})">Menu</button>
            <nav ref="menu" hidden>...</nav>
        </div>
    );
}
```

The core defines what these mean at compile time and how they are lowered. How the JavaScript reaches the browser, and how it is called there, is defined by the target. They require a target that supports them (§11.8).

### 11.1 The file's script

All the script blocks of a file, in source order and joined by line breaks, form the file's **script**: one JavaScript module (ES module). A script block may be anywhere `grammar.md` §7.4 detects one; it is removed from the Java (its lines stay, empty), wherever it is.

The compiler reads only what it needs from the script: the names it declares at its top level. It is not a JavaScript parser. It skips comments (`//` to the end of the line, `/*` to `*/`), string literals (`'...'`, `"..."`, with `\` escaping the next character) and template literals (`` `...` ``, including `${...}` substitutions), and tracks `(`, `[` and `{`. A `/` starts a regular expression literal when the previous significant token is none, one of `( , = : [ ! & | ? { } ; + - * % < > ~ ^`, or the word `return`, `typeof`, `case`, `do`, `else` or `in`; it then runs to the next `/` not escaped and not in a `[...]` class. Outside every bracket, it reads these **top-level names**:

| Written | Names |
|---|---|
| `import d from "m"` | `d` |
| `import { a, b as c } from "m"` | `a`, `c` |
| `import * as ns from "m"` | `ns` |
| `import d, { a } from "m"`, `import d, * as ns from "m"` | `d`, `a` / `d`, `ns` |
| `function f`, `async function f`, `function* f`, `async function* f` | `f` |
| `class C` | `C` |
| `const x =`, `let x =`, `var x =` (each name of a list `const x = 1, y = 2`) | `x` (`x`, `y`) |

Any of these may be preceded by `export` (and `export default` before `function` or `class`); the name is then **exported** already. Names bound by destructuring (`const { a } = o`) are not read: they can't be called from an attribute.

### 11.2 Browser attributes

A browser attribute is an attribute named by a `JasxEventName`: `@` and the name of a DOM event (`@click`, `@input`, `@dragover`), or `@render` (defined by the target). It **MUST** be on an intrinsic element (JASX2018) and **MUST** have a string literal as its value (JASX2017). The string is JavaScript, with Java escape sequences and character references decoded as in any attribute string (`grammar.md` §5). It is one of two things:

- **A call** `name()` or `name(payload)`: the whole value, ignoring whitespace around it, is an identifier, `(`, anything, and the `)` that matches the `(`. `name` **MUST** be a top-level name of the file's script (JASX2019), and what is between the parentheses, if anything, **MUST** be a payload (§11.3, JASX2020). A browser global is written as inline code: `window.alert('Saved')`.
- **Inline code**: any other value (`$menu.hidden = !$menu.hidden`, `event.preventDefault()`, `window.print()`).

An element may have one browser attribute per event, and both a browser attribute and a Java event attribute for the same event (`@click` and `onclick`).

### 11.3 Payloads

A payload is one value, written in this notation (whitespace may separate tokens):

```
Payload:    Value
Value:      Ref | JavaName | Literal | Object | List
Ref:        $ Name
JavaName:   JavaPart ( . JavaPart )*
JavaPart:   Name ( ( ) )?
Literal:    String | Number | true | false | null
String:     ' ( any character but ' and \, or \' or \\ )* '
Number:     -? Digits ( . Digits )?
Object:     { ( Entry ( , Entry )* ,? )? }
Entry:      Key : Value | Name
Key:        Name | String
List:       [ ( Value ( , Value )* ,? )? ]
```

`Name` is a Java identifier (JLS §3.8) other than `true`, `false` and `null`. An `Entry` that is a lone `Name` is shorthand for `Name: Name`.

- A `Ref` is the element with that ref (§11.5).
- A `JavaName` is a **Java expression**: a variable, field or no-argument method call, optionally followed by fields and no-argument method calls (`url`, `t.id()`, `user.address().city()`). It is Java code, written into the generated Java exactly as in the source (§11.6), so javac checks it. What a Java value becomes in the browser is defined by the target.
- Literals, objects and lists are what they are in JSON.

A payload holds values only, never code: its names are Java names, and refs are written `$name`.

### 11.4 Inline code

Inline code is JavaScript that runs as the body of a function for the event. It sees the event as `event`, the refs it uses as `$name` (§11.5), and JavaScript's own names (`window`, ...). It does not see Java variables; code that needs a Java value calls a function of the script with a payload.

The refs inline code uses are found by reading it as §11.1 reads the script (comments, strings and template literals skipped): each `$` that starts a JavaScript identifier, followed by a `Name`, outside those, is a ref. Inline code is not evaluated from a string in the browser: each snippet becomes a function of the file's module (§11.6).

### 11.5 Refs

A `ref` attribute marks an intrinsic element for browser code. It **MUST** be on an intrinsic element (JASX2018), and its value **MUST** be a string literal holding a `Name` (JASX2022). A ref used in a payload or in inline code, `$name`, **MUST** be declared by a `ref` attribute somewhere in the same file (JASX2021). Which element a ref stands for in the browser is defined by the target.

### 11.6 Lowering

**The file's module.** A file with script blocks or browser attributes has a **module**, named after the file's path in its source set with `.jasx.js` appended (`web/Menu.jasx` gives `web/Menu.jasx.js`). It is the file's script (§11.1), followed by:

1. an `export { ... };` of every top-level name a call uses that the script doesn't export already, if there is one; and
2. for each inline snippet, in source order, a function `export function jx$N(event, { $a, $b }) { code }` with the refs the snippet uses (or `export function jx$N(event) { code }` without refs), where `N` counts the file's snippets from 1.

The compiler provides the modules of the files it compiles, with their names; how they reach the browser is target-defined.

**Attributes.** A browser attribute `@event` is lowered as a dynamic attribute (§8) passed by name, `.attr("@event", value)`, whose value is

```java
F.script("web/Menu.jasx.js", "toggleMenu", payload)   // a call
F.script("web/Menu.jasx.js", "jx$1", refs)            // inline code
```

where the payload is written with the factory functions `F.object(key1, value1, key2, value2, ...)`, `F.list(...)` and `F.ref("name")`, string literals for strings and keys, Java literals for numbers, `true`, `false` and `null`, and each `JavaName` as its source text, cast to `Object`. A call without a payload passes `null`. For inline code, `refs` is `F.object("$a", F.ref("a"), ...)` with the refs the snippet uses, or `null`. A `ref` attribute is a static attribute passed by name, `.attr("ref", "name")`.

A target with `"scripts": "module"` provides these factory functions, in addition to those of §6.2:

| Function | Purpose |
|---|---|
| `Object F.script(String module, String function, Object payload)` | A call of `function` in `module`, with `payload`. |
| `Object F.object(Object... keysAndValues)` | An object of a payload: keys (strings) and values, alternately. |
| `Object F.list(Object... values)` | A list of a payload. |
| `Object F.ref(String name)` | A ref of a payload. |

### 11.7 What is checked

Everything above that names a code is reported by JASX, at its `.jasx` position, before javac runs. Java names in payloads are then checked by javac, with their errors reported at their place in the attribute string (§6.6). JavaScript itself is not checked: a syntax error in a script block or in inline code is reported by the browser.

### 11.8 Target support

A target supports browser scripts if its descriptor has `"scripts": "module"` (§7.2). A script block, browser attribute or `ref` attribute in a file compiled for any other target is an error (JASX2107). On a target that supports them, browser code is written only as browser attributes: an event attribute (kind `event`, §7.3) whose value is a string literal is an error (JASX2108).
