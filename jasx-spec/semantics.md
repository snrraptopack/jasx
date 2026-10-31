# JASX Semantics

**Spec version:** 1.0-draft

This document defines what JASX markup **means** independently of any target. It covers how element names are classified, how text and attributes are normalized, how components are resolved and called, the standard **factory lowering**, the **target interface** through which targets plug in, the classification of **static and dynamic positions**, and **file classes** for top-level declarations.

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

A descriptor **MAY** contain other fields, which a compiler that doesn't know them ignores. Fields for reactive targets (such as a `reactive` flag) are not defined in 1.0 (§7.6).

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

### 7.5 Capability errors

| Condition | Code |
|---|---|
| An attribute not in the schema or its extensions | JASX2101 |
| Children on a void element | JASX2102 |
| A Java expression as the value of an event attribute on a target with `eventHandlers: "none"` | JASX2103 |
| A tag not in the schema | JASX2105 |

JASX2104 is reserved for reactive targets, which are not defined in 1.0.

### 7.6 Targets in this specification

The current goal is the **core language**: JASX as JSX is without React. The specification therefore defines only one target, `html`, as a small **reference target**. It is the simplest useful meaning for markup, and the render conformance tests use it to check core semantics (children normalization, the child value contract, component calls).

| Target | Status | Lowering | Specification |
|---|---|---|---|
| `html` | Normative reference target | factory | [`targets/html.md`](targets/html.md) |
| `live` | **Deferred, not decided.** Ideas only; not normative | custom | [`targets/live.md`](targets/live.md) |
| `javafx` | Idea only | custom | — |

Which further targets JASX will have, including whether there will be a reactive target, will be decided after the core language is complete. Third-party targets are expected: a target needs no change to the language or the compiler, only a descriptor, a factory, builders and a schema, and optionally a custom lowering.

---

## 8. Static and dynamic positions

Every value position in markup is **static** or **dynamic**. This classification is purely syntactic and part of the core, so that every target, reactive or not, uses the same terms. The core says nothing about *when* or *how often* a dynamic position is evaluated. Factory lowering evaluates each one once, when the markup expression is evaluated, like any Java expression. A target with a custom lowering defines its own evaluation (for example, a reactive target could re-evaluate dynamic children when data changes).

| Position | Kind |
|---|---|
| A text child value (§3.1) | **static** |
| A string-literal attribute value, a shorthand attribute | **static** |
| An element or fragment, as a child or attribute value | static in itself; its own positions are classified separately |
| An expression child value (§3.2) | **dynamic child** |
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
