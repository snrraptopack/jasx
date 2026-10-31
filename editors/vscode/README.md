# JASX for VS Code

Support for `.jasx` files (Java with JSX-style markup): highlighting, and errors, completion, go-to-definition and hover from the JASX language server.

**Highlighting** (a TextMate grammar):

- Java code is highlighted by VS Code's built-in Java grammar.
- Markup is highlighted on top of it: tags (`div`), components (`Card`, `Ui.Card`), attributes, strings, character references (`&amp;`) and `{...}` expressions. The Java inside an expression is highlighted as Java, and markup inside that Java is found again.
- Style blocks are highlighted as CSS, by VS Code's built-in CSS grammar: `<style>...</style>` where a declaration starts, and inside markup.

**Language server** (`jasx-lsp`, started with Java 21 or later):

- JASX errors appear while you type, with their codes (`JASX1001`, ...): markup syntax, unknown components and props, attributes and tags the target doesn't have, and mistakes in style blocks. Unsaved changes in one file count in the others.
- **Completion** in markup: after `<`, the components the file can use (named as they resolve there: `Card`, `Ui.Card` or fully qualified) and the target's elements; in an opening tag, a component's props that aren't given yet (required ones first) or the attributes the target allows on an element. Nothing is offered in Java code, in text or in attribute values.
- **Go to Definition** (F12, or Ctrl+click) on a component tag opens the component's method.
- **Hover** on a component tag shows its signature and how each prop is passed.

The server checks JASX only. Java type errors (`javac`) still come from the build, because they need the project's classpath. Components from the project's dependencies (jars) are not known to the server yet, so a tag that uses one is reported as unknown.

Files are analysed in the groups a build compiles together: a file in a Gradle-style source directory (`src/main/java`, `src/test/jasx`, ...) with every `.jasx` and `.java` file of that directory, and any other file on its own.

### Settings

| Setting | Default | Meaning |
|---|---|---|
| `jasx.languageServer.enabled` | `true` | Run the language server. |
| `jasx.languageServer.java` | empty | The java executable. Empty: `JAVA_HOME`, then the Java extension's `java.jdt.ls.java.home`, then `java` on the PATH. |
| `jasx.target` | `html` | The target whose schema markup is checked against. Only `html` is bundled. |

## Install

From the root of the repository, build the language server into this folder, then package and install the extension:

```
./gradlew :jasx-lsp:installServer
cd editors/vscode
npm install
npx @vscode/vsce@3 package --skip-license
code --install-extension jasx-0.3.0.vsix --force
```

Then reload VS Code (Command Palette: **Developer: Reload Window**) and open a `.jasx` file. Its language, shown at the right of the status bar, should be **JASX**. The server's messages are in the Output panel, under **JASX Language Server**. Repeat these steps after changing the grammar, the extension or the server.

## How markup is recognized

The JASX specification decides whether `<` starts markup from the token before it (jasx-spec/grammar.md §4). A TextMate grammar can only approximate that with regular expressions. Here, `<` followed by a name or `>` starts markup when it comes after `(`, `[`, `,`, `=`, `?`, `:`, `{`, `->`, `&&`, `||`, `return` or `yield` on the same line, or at the start of a line.

Known imperfection: a generic method with no modifiers whose declaration starts a line is recognized by its shape (`<T> Element ListOf(`), which covers type parameters with simple bounds (`<T extends Number>`). With a nested generic bound (`<T extends Comparable<T>> void sort(`) it is highlighted as markup. Adding a modifier (`static <T ...>`) avoids it.

A style block is recognized when `<style>` comes at the start of a line or after `;`, `{` or `}`, outside markup, or as a child in markup when the text after `<style>` on the same line doesn't start with `{`. So `<style>` followed by a line break and then `{css}` is highlighted as CSS, although it is an ordinary element. A `<style>` element that starts a line inside Java parentheses (rare; markup usually starts after `return (`, `=` or similar on the same line) is therefore highlighted as a style block.

## Tests

```
npm install
npm test
```

The tests tokenize JASX snippets with VS Code's TextMate engine (`vscode-textmate`) and check the scopes of the tokens. Small stand-ins for the Java and CSS grammars are used (`test/java-stub.tmLanguage.json`, `test/css-stub.tmLanguage.json`).
