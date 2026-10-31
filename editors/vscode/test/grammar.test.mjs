// Tests for the JASX TextMate grammar. Each test tokenizes a small piece of JASX with VS Code's own
// TextMate engine (vscode-textmate) and checks the scopes of some tokens.
//
// Run with: npm install && npm test   (from editors/vscode)

import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";
import vsctm from "vscode-textmate";
import oniguruma from "vscode-oniguruma";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));

// --- Set up the TextMate engine --------------------------------------------------------------------

const wasm = fs.readFileSync(require.resolve("vscode-oniguruma/release/onig.wasm")).buffer;
await oniguruma.loadWASM(wasm);

const grammarFiles = {
  "source.jasx": path.join(here, "..", "syntaxes", "jasx.tmLanguage.json"),
  "source.java": path.join(here, "java-stub.tmLanguage.json"),
  "source.css": path.join(here, "css-stub.tmLanguage.json"),
};

const registry = new vsctm.Registry({
  onigLib: Promise.resolve({
    createOnigScanner: (patterns) => new oniguruma.OnigScanner(patterns),
    createOnigString: (s) => new oniguruma.OnigString(s),
  }),
  loadGrammar: async (scopeName) => {
    const file = grammarFiles[scopeName];
    return file ? vsctm.parseRawGrammar(fs.readFileSync(file, "utf8"), file) : null;
  },
});

const grammar = await registry.loadGrammar("source.jasx");

// --- Helpers ---------------------------------------------------------------------------------------

/** Tokenizes `source` and returns [{ text, scopes }] for every token on every line. */
function tokenize(source) {
  const tokens = [];
  let state = vsctm.INITIAL;
  for (const line of source.split("\n")) {
    const result = grammar.tokenizeLine(line, state);
    for (const token of result.tokens) {
      tokens.push({ text: line.substring(token.startIndex, token.endIndex), scopes: token.scopes });
    }
    state = result.ruleStack;
  }
  return tokens;
}

/** The scopes of the `occurrence`-th token (1-based) whose text is exactly `text`. */
function scopesOf(tokens, text, occurrence = 1) {
  const matches = tokens.filter((t) => t.text === text);
  assert.ok(matches.length >= occurrence, `no token "${text}" (#${occurrence}) in: ${JSON.stringify(tokens.map((t) => t.text))}`);
  return matches[occurrence - 1].scopes;
}

function assertScope(source, text, scope, occurrence = 1) {
  const scopes = scopesOf(tokenize(source), text, occurrence);
  assert.ok(scopes.includes(scope), `"${text}" should have scope ${scope}, but has ${scopes.join(" ")}`);
}

/**
 * Checks that no token containing `text` has `scope`. Text without special scopes is often merged
 * into one larger token (a whole line of plain Java), so this looks for tokens that contain the text.
 */
function assertNoScope(source, text, scope) {
  const containing = tokenize(source).filter((t) => t.text.includes(text));
  assert.ok(containing.length > 0, `no token contains "${text}"`);
  for (const token of containing) {
    assert.ok(!token.scopes.includes(scope), `"${token.text}" should not have scope ${scope}, but has ${token.scopes.join(" ")}`);
  }
}

const tests = [];
function test(name, body) {
  tests.push({ name, body });
}

// --- Tests -----------------------------------------------------------------------------------------

test("an intrinsic element with an attribute, text and an expression", () => {
  const source = 'Element e = <div class="a">Hi {name}</div>;';
  assertScope(source, "div", "entity.name.tag.jasx", 1);
  assertScope(source, "div", "entity.name.tag.jasx", 2);
  assertScope(source, "class", "entity.other.attribute-name.jasx");
  assertScope(source, "a", "string.quoted.double.jasx");
  assertScope(source, "{", "punctuation.section.embedded.begin.jasx");
  assertScope(source, "name", "meta.embedded.expression.jasx");
  assertScope(source, "Hi ", "meta.children.jasx");
});

test("capitalized and qualified names are components", () => {
  assertScope("x = <Card title={t} />;", "Card", "support.class.component.jasx");
  assertScope("x = <Ui.Card />;", "Ui.Card", "support.class.component.jasx");
  assertScope("x = <my-widget />;", "my-widget", "entity.name.tag.jasx");
});

test("markup inside class and method bodies (through the injection)", () => {
  const source = "class T {\n  static Element f() {\n    return <p>x</p>;\n  }\n}";
  assertScope(source, "p", "entity.name.tag.jasx");
});

test("multi-line markup that starts on its own line", () => {
  const source = "return (\n  <div>\n    <p>a</p>\n  </div>\n);";
  assertScope(source, "div", "entity.name.tag.jasx", 1);
  assertScope(source, "p", "entity.name.tag.jasx", 1);
  assertScope(source, "div", "entity.name.tag.jasx", 2);
});

test("generics and comparisons are not markup", () => {
  const source = "List<String> xs = a < b ? c : d;";
  assertNoScope(source, "String", "entity.name.tag.jasx");
  assertNoScope(source, "String", "support.class.component.jasx");
});

test("fragments", () => {
  const source = "x = <>a<b/></>;";
  assertScope(source, "b", "entity.name.tag.jasx");
  assertScope(source, "a", "meta.tag.jasx");
});

test("character references in text", () => {
  assertScope("x = <p>a &amp; b</p>;", "&amp;", "constant.character.entity.jasx");
});

test("markup nested in an expression inside markup", () => {
  const source = "x = <ul>{xs.map(i -> <li>{i}</li>)}</ul>;";
  assertScope(source, "li", "entity.name.tag.jasx", 1);
  assertScope(source, "li", "entity.name.tag.jasx", 2);
});

test("markup statements in a statement container (grammar.md §6.7)", () => {
  const source = "x = <ul>{for (A a : as) { <li>{a}</li>; <hr/> }}</ul>;";
  assertScope(source, "li", "entity.name.tag.jasx", 1);
  assertScope(source, "hr", "entity.name.tag.jasx", 1);
  // A generic method after ';' is still Java.
  assertNoScope("int x; <T> void m(T t) {}", "T", "entity.name.tag.jasx");
});

test("markup as an attribute value", () => {
  assertScope("x = <Card icon=<Icon /> />;", "Icon", "support.class.component.jasx");
});

test("no markup in comments or strings", () => {
  assertNoScope("// return <p>x</p>;", "p", "entity.name.tag.jasx");
  assertNoScope('String s = "<p>";', "p", "entity.name.tag.jasx");
});

test("text after the markup is Java again", () => {
  const source = "Element e = <p/>; boolean b = x < y;";
  assertNoScope(source, "x < y", "meta.tag.jasx");
});

test("a generic method at the start of a line is Java, not markup", () => {
  const source = [
    "<I> Element ListOf(List<I> items) {",
    "  return <ul/>;",
    "}",
    "<K, V extends Number> Map<K, V> sorted(Map<K, V> m) {}",
  ].join("\n");
  assertNoScope(source, "Element ListOf", "meta.tag.jasx");
  assertNoScope(source, "sorted", "meta.tag.jasx");
  assertScope(source, "ul", "entity.name.tag.jasx");
});

test("an element at the start of a line is still markup", () => {
  assertScope(["return (", "<Row item={x} />", ");"].join("\n"), "Row", "support.class.component.jasx");
});

test("a style block's content is CSS", () => {
  const source = ["<style>", "  .card { padding: 1rem; }", "</style>", "Element Card() { return <div/>; }"].join("\n");
  assertScope(source, ".card", "entity.other.attribute-name.class.css");
  assertScope(source, ".card", "meta.embedded.block.css");
  assertScope(source, "style", "entity.name.tag.jasx", 1);
  assertScope(source, "div", "entity.name.tag.jasx");
});

test("a style block in a class body (through the injection)", () => {
  const source = ["class T {", "  <style>.a { }</style>", "}"].join("\n");
  assertScope(source, ".a", "entity.other.attribute-name.class.css");
});

test("a style block inside markup is CSS", () => {
  const source = ["return (", "  <div class=\"card\">", "    <style>", "      .card { padding: 1rem; }", "    </style>", "    <h2>x</h2>", "  </div>", ");"].join("\n");
  assertScope(source, ".card", "entity.other.attribute-name.class.css");
  assertScope(source, "h2", "entity.name.tag.jasx");
  assertNoScope(source, "h2", "meta.embedded.block.css");
});

test("a <style> element with an expression inside markup is not a style block", () => {
  const source = ["return (", "  <head>", "    <style>{CSS}</style>", "  </head>", ");"].join("\n");
  assertNoScope(source, "CSS", "meta.embedded.block.css");
  assertScope(source, "style", "entity.name.tag.jasx", 1);
});

// --- Run -------------------------------------------------------------------------------------------

let failed = 0;
for (const { name, body } of tests) {
  try {
    body();
    console.log(`ok   ${name}`);
  } catch (error) {
    failed++;
    console.log(`FAIL ${name}\n     ${error.message}`);
  }
}
console.log(`\n${tests.length - failed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
