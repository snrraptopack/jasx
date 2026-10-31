# JASX — JSX for Java

JASX lets you write markup directly in Java, the way JSX does for JavaScript:

```java
import jasx.runtime.Element;
import java.util.List;

class Todos {
  static Element Item(String title, boolean done) {
    return <li class={done ? "done" : "todo"}>{title}</li>;
  }

  static Element Page(List<String> titles) {
    return (
      <ul>
        {titles.stream().map(t -> <Item title={t} done={false} />).toList()}
      </ul>
    );
  }
}
```

A `.jasx` file is ordinary Java plus markup expressions. The JASX compiler rewrites only the markup, into plain method calls, and javac type-checks the result. Components are plain static methods, attributes are checked at compile time, and every Java feature works inside `{...}`.

Like JSX, JASX is **a language, not a framework**. What markup turns into is decided by a **target** library. The current focus is the core language. It ships with one small reference target, `html`, which renders markup to an HTML string. Other targets are not decided yet.

## Status

Early development. The specification is a draft.

- [`jasx-spec/`](jasx-spec/README.md): the language specification and its conformance tests.
- Parser, compiler and `html` target: implemented, and passing the whole conformance suite.
- Gradle plugin and editor support: not yet. For now, use the compiler from the command line or as a library.

## Building

Requires JDK 21.

```
./gradlew build
./gradlew -q :jasx-compiler:run --args="../jasx-spec/tests/render/005-components-lists.jasx"
```

The second command prints the Java generated for a sample file.

## Repository

| Path | Contents |
|---|---|
| `jasx-spec/` | Specification (grammar, AST, semantics, diagnostics) and conformance tests |
| `jasx-parser/` | Parser: finds and parses markup in Java source |
| `jasx-compiler/` | Compiler: resolves components and lowers markup to Java |
| `jasx-runtime/` | `Element` and the component annotations |
| `jasx-target-html/` | The `html` reference target |
| `jasx-conformance/` | Runs the conformance tests |
| `JASX-update1.md` | The original design proposal (historical) |

Contributors and coding agents: see [`AGENTS.md`](AGENTS.md).
