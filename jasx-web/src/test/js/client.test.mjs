// Tests of the web target's browser client (src/main/resources/jasx/web/web.js, targets/web.md §6–§8).
//
// Each test loads a page in jsdom with the client, a fake server that answers its events, and a fake stream
// the test can push batches on.
//
// Run with: npm install && npm test   (from jasx-web/src/test/js)

import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { JSDOM, ResourceLoader } from "jsdom";

const here = path.dirname(fileURLToPath(import.meta.url));
const CLIENT = fs.readFileSync(path.join(here, "..", "..", "main", "resources", "jasx", "web", "web.js"), "utf8");

/** Serves the client script at /_jasx/web.js, as the server does. */
class ClientLoader extends ResourceLoader {
  fetch(url, options) {
    if (url.endsWith("/_jasx/web.js")) return Promise.resolve(Buffer.from(CLIENT));
    return null;
  }
}

/**
 * A page whose body is `body`, with the client running. `answer(payload)` plays the server: it returns the
 * batch for an event, null for "nothing changed", or "gone"; it may return a promise.
 */
async function page(body, answer = () => null) {
  const sent = [];
  const streams = [];
  const dom = new JSDOM(
    `<!DOCTYPE html><html><head></head><body>${body}<script src="/_jasx/web.js" data-jx-page="P1" defer></script></body></html>`,
    {
      url: "http://localhost/page",
      runScripts: "dangerously",
      resources: new ClientLoader(),
      beforeParse(window) {
        window.fetch = async (url, init) => {
          const payload = JSON.parse(init.body);
          sent.push({ url, payload });
          const batch = await answer(payload);
          if (batch === "gone") return { status: 410 };
          if (batch === "lost") throw new TypeError("the connection was lost");
          return batch ? { status: 200, json: async () => batch } : { status: 204 };
        };
        window.EventSource = class {
          static CLOSED = 2;
          constructor(url) {
            this.url = url;
            this.readyState = 1;
            this.closed = false;
            streams.push(this);
          }
          push(batch) {
            this.onmessage({ data: JSON.stringify(batch) });
          }
          close() {
            this.closed = true;
            this.readyState = 2;
          }
        };
      },
    },
  );
  await new Promise((resolve) => dom.window.addEventListener("load", resolve));
  const window = dom.window;
  return { window, document: window.document, sent, streams };
}

const wait = (ms = 20) => new Promise((resolve) => setTimeout(resolve, ms));

function fire(window, element, type, init = {}) {
  const event = type === "click"
    ? new window.MouseEvent(type, { bubbles: true, cancelable: true, ...init })
    : new window.Event(type, { bubbles: true, cancelable: true, ...init });
  element.dispatchEvent(event);
  return event;
}

function type(window, input, text) {
  input.value = text;
  fire(window, input, "input");
}

// -------------------------------------------------------------------------------------------------

test("a click sends its handler and applies the answer", async () => {
  const { window, document, sent, streams } = await page(
    `<button data-jx-on="click:3"><span>+</span></button><p><!--jx:4-->0<!--/jx:4--></p>`,
    () => ({ version: 1, patches: [{ op: "html", id: 4, html: "<b>1</b>" }] }),
  );
  assert.equal(streams[0].url, "/_jasx/stream?page=P1&since=0");

  fire(window, document.querySelector("span"), "click"); // on a child: the button's handler
  await wait();

  assert.equal(sent.length, 1);
  assert.equal(sent[0].url, "/_jasx/event?page=P1");
  assert.deepEqual(sent[0].payload, {
    handler: 3, seq: 1, type: "click", button: 0, x: 0, y: 0, ctrl: false, shift: false, alt: false, meta: false,
  });
  assert.equal(document.querySelector("p").innerHTML, "<!--jx:4--><b>1</b><!--/jx:4-->");
});

test("new content is live: its holes can be patched and its handlers fire", async () => {
  let version = 0;
  const { window, document, sent } = await page(
    `<button data-jx-on="click:1">go</button><div><!--jx:2--><!--/jx:2--></div>`,
    (payload) => {
      version++;
      return payload.handler === 1
        ? { version, patches: [{ op: "html", id: 2, html: `<i data-jx-on="dblclick:5"><!--jx:6-->a<!--/jx:6--></i>` }] }
        : { version, patches: [{ op: "html", id: 6, html: "b" }] };
    },
  );
  fire(window, document.querySelector("button"), "click");
  await wait();
  fire(window, document.querySelector("i"), "dblclick");
  await wait();
  assert.deepEqual(sent.map((s) => s.payload.handler), [1, 5]);
  assert.equal(document.querySelector("i").textContent, "b");
});

test("input is debounced: one event with the latest value after the pause", async () => {
  const { window, document, sent } = await page(`<input data-jx-on="input:7:300">`);
  const input = document.querySelector("input");
  type(window, input, "a");
  type(window, input, "ab");
  await wait(100);
  assert.equal(sent.length, 0, "nothing is sent while typing");
  await wait(350);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].payload.value, "ab");
});

test("pending input is sent before another event", async () => {
  const { window, document, sent } = await page(`<input data-jx-on="input:7:300"><button data-jx-on="click:8">x</button>`);
  type(window, document.querySelector("input"), "typed");
  fire(window, document.querySelector("button"), "click");
  await wait();
  assert.deepEqual(sent.map((s) => [s.payload.handler, s.payload.value]), [[7, "typed"], [8, undefined]]);
});

test("a submit sends the form's fields once, and reset clears the form", async () => {
  const { window, document, sent } = await page(
    `<form data-jx-on="submit:2"><input name="title"><input name="tag" value="a"><button>Add</button></form>`,
    (payload) => ({ version: 1, patches: [{ op: "reset", event: payload.seq }] }),
  );
  const title = document.querySelector("input[name=title]");
  title.value = "Milk";
  const event = fire(window, document.querySelector("form"), "submit");
  assert.ok(event.defaultPrevented, "the browser's own submission is prevented");
  await wait();
  assert.equal(sent.length, 1);
  assert.deepEqual(sent[0].payload.fields, { title: ["Milk"], tag: ["a"] });
  assert.equal(title.value, "", "reset");
});

test("a field being typed in is not overwritten until its next event is sent", async () => {
  const { window, document, streams } = await page(`<input data-jx-id="5" data-jx-on="change:9" value="a">`);
  const input = document.querySelector("input");
  input.focus();
  type(window, input, "abc");
  streams[0].push({ version: 1, patches: [{ op: "attr", id: 5, name: "value", value: "server" }] });
  assert.equal(input.value, "abc");

  fire(window, input, "change");
  await wait();
  streams[0].push({ version: 2, patches: [{ op: "attr", id: 5, name: "value", value: "" }] });
  assert.equal(input.value, "");
});

test("attributes are set, made bare, and removed", async () => {
  const { document, streams } = await page(`<button data-jx-id="3" class="a">x</button>`);
  const button = document.querySelector("button");
  streams[0].push({ version: 1, patches: [
    { op: "attr", id: 3, name: "class", value: "b" },
    { op: "attr", id: 3, name: "disabled", value: true },
  ] });
  assert.equal(button.className, "b");
  assert.ok(button.disabled);
  streams[0].push({ version: 2, patches: [{ op: "attr", id: 3, name: "disabled", value: null }] });
  assert.ok(!button.disabled);
});

test("batches are applied in version order", async () => {
  const { document, streams } = await page(`<p><!--jx:4-->0<!--/jx:4--></p>`);
  const p = document.querySelector("p");
  streams[0].push({ version: 2, patches: [{ op: "html", id: 4, html: "two" }] });
  assert.equal(p.textContent, "0", "version 2 waits for version 1");
  streams[0].push({ version: 1, patches: [{ op: "html", id: 4, html: "one" }] });
  assert.equal(p.textContent, "two");
  streams[0].push({ version: 1, patches: [{ op: "html", id: 4, html: "old" }] });
  assert.equal(p.textContent, "two", "an old batch is ignored");
});

test("an element is marked busy while its event runs, and a form can't be submitted twice", async () => {
  let release;
  const { window, document, sent } = await page(
    `<form data-jx-on="submit:2"><button>Add</button></form>`,
    () => new Promise((resolve) => { release = () => resolve(null); }),
  );
  const form = document.querySelector("form");
  fire(window, form, "submit");
  await wait();
  assert.ok(form.classList.contains("jx-loading"));
  assert.equal(form.getAttribute("aria-busy"), "true");
  fire(window, form, "submit");
  await wait();
  assert.equal(sent.length, 1, "the second submit is ignored");
  release();
  await wait();
  assert.ok(!form.classList.contains("jx-loading"));
  assert.equal(form.getAttribute("aria-busy"), null);
});

test("events that don't bubble reach only their own element", async () => {
  const { document, sent } = await page(`<div data-jx-on="focus:1"><input data-jx-on="focus:2"></div>`);
  document.querySelector("input").focus();
  await wait();
  assert.deepEqual(sent.map((s) => s.payload.handler), [2]);
});

test("a key event sends its key, modifiers and the field's value", async () => {
  const { window, document, sent } = await page(`<input data-jx-on="keydown:4">`);
  const input = document.querySelector("input");
  input.value = "hi";
  input.dispatchEvent(new window.KeyboardEvent("keydown", { key: "Enter", shiftKey: true, bubbles: true }));
  await wait();
  assert.deepEqual(sent[0].payload, {
    handler: 4, seq: 1, type: "keydown", key: "Enter", ctrl: false, shift: true, alt: false, meta: false, value: "hi", checked: false,
  });
});

test("a multiple select sends every selected value", async () => {
  const { window, document, sent } = await page(
    `<select multiple data-jx-on="change:6"><option value="a" selected>A</option><option value="b">B</option><option value="c" selected>C</option></select>`,
  );
  fire(window, document.querySelector("select"), "change");
  await wait();
  assert.deepEqual(sent[0].payload.values, ["a", "c"]);
});

test("a style sheet patch adds the CSS to the head", async () => {
  const { document, streams } = await page(``);
  streams[0].push({ version: 1, patches: [{ op: "css", css: "b { color: red; }" }] });
  assert.equal(document.head.querySelector("style").textContent, "b { color: red; }");
});

// -------------------------------------------------------------------------------------------------
// Recovery and robustness (targets/web.md §6.1, §7, §8.2, §8.4)
// -------------------------------------------------------------------------------------------------

test("an old queued event doesn't unprotect newer typing", async () => {
  let releaseFirst;
  const { window, document, streams } = await page(
    `<button data-jx-on="click:1">slow</button><input data-jx-id="5" data-jx-on="input:2:50" value="">`,
    (payload) => payload.handler === 1 ? new Promise((resolve) => { releaseFirst = () => resolve(null); }) : null,
  );
  const input = document.querySelector("input");
  fire(window, document.querySelector("button"), "click"); // a slow request is running
  await wait();
  input.focus();
  type(window, input, "A");  // after the debounce, queued behind it, carrying "A"
  await wait(80);
  type(window, input, "AB"); // newer typing, still waiting for its debounce
  releaseFirst();
  await wait(5); // the queued "A" event is sent now
  streams[0].push({ version: 1, patches: [{ op: "attr", id: 5, name: "value", value: "A" }] });
  assert.equal(input.value, "AB", "the answer to \"A\" doesn't overwrite \"AB\"");
});

test("a reset keeps its form when its batch waits for an earlier one", async () => {
  const { window, document, streams } = await page(
    `<form data-jx-on="submit:2"><input name="title"></form>`,
    (payload) => ({ version: 2, patches: [{ op: "reset", event: payload.seq }] }),
  );
  const title = document.querySelector("input");
  title.value = "Milk";
  fire(window, document.querySelector("form"), "submit");
  await wait(); // version 2 arrived first and waits for version 1
  assert.equal(title.value, "Milk");
  streams[0].push({ version: 1, patches: [] });
  assert.equal(title.value, "", "the waiting reset still finds its form");
});

test("a reset doesn't clear what was typed after the submit", async () => {
  let answer;
  const { window, document } = await page(
    `<form data-jx-on="submit:2"><input name="title"><input name="note"></form>`,
    (payload) => new Promise((resolve) => { answer = () => resolve({ version: 1, patches: [{ op: "reset", event: payload.seq }] }); }),
  );
  const [title, note] = document.querySelectorAll("input");
  title.value = "Milk";
  fire(window, document.querySelector("form"), "submit");
  await wait();
  type(window, title, "Eggs"); // typed while the submit was being handled
  answer();
  await wait();
  assert.equal(title.value, "Eggs");
  assert.equal(note.value, "");
});

test("a missing batch makes the client reconnect with its version, however many batches follow", async () => {
  const { document, streams } = await page(`<p><!--jx:4-->0<!--/jx:4--></p>`);
  for (let v = 2; v <= 6; v++) {
    streams[0].push({ version: v, patches: [{ op: "html", id: 4, html: String(v) }] });
    await wait(400); // batches keep arriving, but the deadline isn't pushed back
  }
  await wait(1200);
  assert.ok(streams.length >= 2, "reconnected");
  assert.ok(streams[0].closed);
  assert.equal(streams[1].url, "/_jasx/stream?page=P1&since=0");
  streams[1].push({ from: 1, version: 6, patches: [{ op: "html", id: 4, html: "6" }] });
  assert.equal(document.querySelector("p").textContent, "6");
});

test("a lost event answer makes the client fetch what it missed", async () => {
  const { window, document, streams } = await page(`<button data-jx-on="click:1">x</button>`, () => "lost");
  fire(window, document.querySelector("button"), "click");
  await wait();
  assert.equal(streams.length, 2, "reconnected to catch up");
  assert.equal(streams[1].url, "/_jasx/stream?page=P1&since=0");
});

test("a merged catch-up that overlaps applied versions is applied once more, harmlessly", async () => {
  const { document, streams } = await page(`<p><!--jx:4-->0<!--/jx:4--></p>`);
  streams[0].push({ version: 1, patches: [{ op: "html", id: 4, html: "1" }] });
  streams[0].push({ from: 1, version: 3, patches: [{ op: "html", id: 4, html: "3" }, { op: "css", css: "p{}" }] });
  streams[0].push({ from: 2, version: 3, patches: [{ op: "css", css: "p{}" }] }); // all applied: ignored
  assert.equal(document.querySelector("p").textContent, "3");
  assert.equal(document.head.querySelectorAll("style").length, 1, "a style sheet is added once");
});

test("a reload frame loads the page afresh", async () => {
  const { window, streams } = await page(``);
  streams[0].push({ reload: true });
  assert.ok(window.sessionStorage.getItem("jx-reload"), "a reload was attempted");
});

test("content of an SVG hole is created in the SVG namespace", async () => {
  const { document, streams } = await page(`<svg><g><!--jx:3--><!--/jx:3--></g></svg>`);
  streams[0].push({ version: 1, patches: [{ op: "html", id: 3, html: `<circle r="4"></circle>` }] });
  const circle = document.querySelector("circle");
  assert.equal(circle.namespaceURI, "http://www.w3.org/2000/svg");
});

test("rows of a hole in a tbody are parsed as rows", async () => {
  const { document, streams } = await page(`<table><tbody><!--jx:3--><!--/jx:3--></tbody></table>`);
  streams[0].push({ version: 1, patches: [{ op: "html", id: 3, html: `<tr><td>a</td></tr><tr><td>b</td></tr>` }] });
  assert.equal(document.querySelectorAll("tbody > tr").length, 2);
});

test("a hole whose end marker is missing removes nothing", async () => {
  const { window, document, streams } = await page(`<div><!--jx:3--><p>keep</p></div><p>after</p>`);
  streams[0].push({ version: 1, patches: [{ op: "html", id: 3, html: "new" }] });
  assert.equal(document.querySelectorAll("p").length, 2);
  assert.ok(window.sessionStorage.getItem("jx-reload"), "it reloads instead of guessing");
});
