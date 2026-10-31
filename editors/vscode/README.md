# JASX for VS Code

Syntax highlighting for `.jasx` files: Java with JSX-style markup.

- Java code is highlighted by VS Code's built-in Java grammar.
- Markup is highlighted on top of it: tags (`div`), components (`Card`, `Ui.Card`), attributes, strings, character references (`&amp;`) and `{...}` expressions. The Java inside an expression is highlighted as Java, and markup inside that Java is found again.
- Style blocks (`<style>...</style>` where a declaration starts) are highlighted as CSS, by VS Code's built-in CSS grammar.

This extension only highlights syntax. It doesn't report errors or offer completion; those need a language server, which is planned.

## Install from this folder

1. In VS Code, open the Command Palette and run **Developer: Install Extension from Location...**.
2. Choose this folder (`editors/vscode`).
3. Open any `.jasx` file.

On older VS Code versions, copy or link this folder into `~/.vscode/extensions/jasx` instead, then restart VS Code.

## How markup is recognized

The JASX specification decides whether `<` starts markup from the token before it (jasx-spec/grammar.md §4). A TextMate grammar can only approximate that with regular expressions. Here, `<` followed by a name or `>` starts markup when it comes after `(`, `[`, `,`, `=`, `?`, `:`, `{`, `->`, `&&`, `||`, `return` or `yield` on the same line, or at the start of a line.

Known imperfection: a generic method with no modifiers whose declaration starts a line is recognized by its shape (`<T> Element ListOf(`), which covers type parameters with simple bounds (`<T extends Number>`). With a nested generic bound (`<T extends Comparable<T>> void sort(`) it is highlighted as markup. Adding a modifier (`static <T ...>`) avoids it.

A style block is recognized when `<style>` comes at the start of a line or after `;`, `{` or `}`, outside markup. A `<style>` element that starts a line inside Java parentheses (rare; markup usually starts after `return (`, `=` or similar on the same line) is therefore highlighted as a style block.

## Tests

```
npm install
npm test
```

The tests tokenize JASX snippets with VS Code's TextMate engine (`vscode-textmate`) and check the scopes of the tokens. Small stand-ins for the Java and CSS grammars are used (`test/java-stub.tmLanguage.json`, `test/css-stub.tmLanguage.json`).
