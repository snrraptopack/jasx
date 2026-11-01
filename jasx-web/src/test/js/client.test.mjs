// Tests of the web target's browser client (src/main/resources/jasx/web/web.js, targets/web.md §6–§8).
//
// Each test loads a page in jsdom with the client, a fake server that answers its events, and a fake stream
// the test can push batches on.
//
// Run with: npm install && npm test   (from jasx-web/src/test/js)

import { after, test } from "node:test";
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

// Every page's window is closed after the tests, so that their timers (a leader's roll call) let Node exit.
const openWindows = [];
after(() => openWindows.forEach((window) => window.close()));

/**
 * A page whose body is `body`, with the client running. `answer(payload)` plays the server: it returns the
 * batch for an event, null for "nothing changed", "gone" or "lost"; it may return a promise. The page's id
 * is `id`; a page made with a `browser` (browserWithTabs) is one of its tabs, otherwise it can't share its
 * stream.
 */
async function page(body, answer = () => null, scriptAttributes = "", { id = "P1", browser = null } = {}) {
  const sent = [];
  const streams = [];
  const pings = [];
  const dom = new JSDOM(
    `<!DOCTYPE html><html><head></head><body>${body}<script src="/_jasx/web.js" data-jx-page="${id}" ${scriptAttributes} defer></script></body></html>`,
    {
      url: "http://localhost/page",
      runScripts: "dangerously",
      resources: new ClientLoader(),
      beforeParse(window) {
        if (browser) browser.install(window, id);
        else window.BroadcastChannel = undefined;
        window.fetch = async (url, init) => {
          if (url.includes("/ping")) {
            pings.push(url);
            return { status: 204 };
          }
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
          /** Pushes a batch for this tab's page. */
          push(batch) {
            this.pushFor(id, batch);
          }
          /** Pushes a batch for page `page`: each frame names its page (§8.1). */
          pushFor(page, batch) {
            this.onmessage({ data: JSON.stringify({ page, batch }) });
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
  openWindows.push(window);
  return { window, document: window.document, sent, streams, pings };
}

/**
 * The tabs of one browser (targets/web.md §6.2): pages made with it share a BroadcastChannel and Web Locks,
 * faked here because jsdom has neither. Messages are delivered asynchronously, as browsers do.
 */
function browserWithTabs() {
  const channels = new Set();
  const waiting = []; // lock requests in order: {tab, name, callback}
  let holder = null;
  const grantNext = () => {
    if (holder !== null || waiting.length === 0) return;
    const next = waiting.shift();
    holder = next.tab;
    next.callback({ name: next.name });
  };
  return {
    install(window, tab) {
      window.BroadcastChannel = class {
        constructor(name) {
          this.name = name;
          this.tab = tab;
          channels.add(this);
        }
        postMessage(data) {
          const copy = JSON.parse(JSON.stringify(data));
          for (const other of channels) {
            if (other !== this && other.name === this.name) setTimeout(() => other.onmessage?.({ data: copy }), 0);
          }
        }
        close() {
          channels.delete(this);
        }
      };
      Object.defineProperty(window.navigator, "locks", {
        configurable: true,
        value: {
          request(name, callback) {
            waiting.push({ tab, name, callback });
            setTimeout(grantNext, 0);
            return new Promise(() => {});
          },
        },
      });
    },
    /** Closes a tab: its page is hidden for good, its channel closes, and a lock it held passes on. */
    close(window, tab) {
      window.dispatchEvent(new window.Event("pagehide"));
      for (const channel of channels) if (channel.tab === tab) channels.delete(channel);
      waiting.splice(0, waiting.length, ...waiting.filter((request) => request.tab !== tab));
      if (holder === tab) {
        holder = null;
        setTimeout(grantNext, 0);
      }
      window.close();
    },
  };
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
  assert.equal(streams[0].url, "/_jasx/stream?pages=P1:0");

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
  assert.equal(streams[1].url, "/_jasx/stream?pages=P1:0");
  streams[1].push({ from: 1, version: 6, patches: [{ op: "html", id: 4, html: "6" }] });
  assert.equal(document.querySelector("p").textContent, "6");
});

test("a lost event answer makes the client fetch what it missed", async () => {
  const { window, document, streams } = await page(`<button data-jx-on="click:1">x</button>`, () => "lost");
  fire(window, document.querySelector("button"), "click");
  await wait();
  assert.equal(streams.length, 2, "reconnected to catch up");
  assert.equal(streams[1].url, "/_jasx/stream?pages=P1:0");
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

test("a hidden tab closes its stream, pings, and catches up when shown again", async () => {
  const { window, document, streams, pings } = await page(`<p><!--jx:4-->0<!--/jx:4--></p>`, () => null, `data-jx-hidden-grace="50"`);
  const setVisibility = (state) => {
    Object.defineProperty(document, "visibilityState", { value: state, configurable: true });
    document.dispatchEvent(new window.Event("visibilitychange"));
  };
  streams[0].push({ version: 1, patches: [{ op: "html", id: 4, html: "1" }] });

  setVisibility("hidden");
  await wait(20);
  setVisibility("visible"); // a quick switch keeps the stream
  setVisibility("hidden");
  await wait(100);
  assert.ok(streams[0].closed, "the hidden tab released its connection");
  assert.equal(streams.length, 1);
  assert.deepEqual(pings, ["/_jasx/ping?page=P1"]);

  setVisibility("visible");
  assert.equal(streams.length, 2);
  assert.equal(streams[1].url, "/_jasx/stream?pages=P1:1", "it asks for what was pushed while hidden");
});

// -------------------------------------------------------------------------------------------------
// One stream for every tab (targets/web.md §6.2)
// -------------------------------------------------------------------------------------------------

const HOLE = `<p><!--jx:4-->-<!--/jx:4--></p>`;

/** Two tabs of one browser, once the first leads and streams both pages. */
async function twoTabs(secondAnswer = () => null) {
  const browser = browserWithTabs();
  const first = await page(HOLE, () => null, "", { id: "P1", browser });
  const second = await page(HOLE + `<button data-jx-on="click:1">x</button>`, secondAnswer, "", { id: "P2", browser });
  await wait(150);
  return { browser, first, second };
}

test("tabs share one stream: the leader streams every tab's page and passes frames on", async () => {
  const { first, second } = await twoTabs();
  assert.equal(second.streams.length, 0, "the second tab opens no stream of its own");
  const stream = first.streams.at(-1);
  assert.equal(stream.url, "/_jasx/stream?pages=P1:0,P2:0");
  assert.ok(first.streams.slice(0, -1).every((old) => old.closed), "one stream at a time");

  stream.pushFor("P2", { version: 1, patches: [{ op: "html", id: 4, html: "two" }] });
  stream.pushFor("P1", { version: 1, patches: [{ op: "html", id: 4, html: "one" }] });
  await wait();
  assert.equal(first.document.querySelector("p").textContent, "one");
  assert.equal(second.document.querySelector("p").textContent, "two");
});

test("a tab that lost an event's answer asks the leader to reconnect with its version", async () => {
  const { first, second } = await twoTabs(() => "lost");
  const before = first.streams.length;
  fire(second.window, second.document.querySelector("button"), "click");
  await wait(150);
  assert.equal(first.streams.length, before + 1, "the leader reconnected");
  assert.equal(first.streams.at(-1).url, "/_jasx/stream?pages=P1:0,P2:0");
});

test("a closed tab leaves the stream, so the server can discard its page", async () => {
  const { browser, first, second } = await twoTabs();
  browser.close(second.window, "P2");
  await wait(150);
  assert.equal(first.streams.at(-1).url, "/_jasx/stream?pages=P1:0");
});

test("when the leader closes, another tab takes over and carries on from its version", async () => {
  const { browser, first, second } = await twoTabs();
  first.streams.at(-1).pushFor("P2", { version: 1, patches: [{ op: "html", id: 4, html: "two" }] });
  await wait();
  browser.close(first.window, "P1");
  await wait(150);
  assert.equal(second.streams.length, 1, "the second tab streams now");
  assert.equal(second.streams[0].url, "/_jasx/stream?pages=P2:1");
  second.streams[0].push({ version: 2, patches: [{ op: "html", id: 4, html: "still live" }] });
  assert.equal(second.document.querySelector("p").textContent, "still live");
});

// -------------------------------------------------------------------------------------------------
// Keyed lists and morphing (targets/web.md §4.3, §8.4)
// -------------------------------------------------------------------------------------------------

const LIST = `<ul><!--jx:1-->`
  + `<!--jx:2--><li>a</li><!--/jx:2-->`
  + `<!--jx:3--><li><input></li><!--/jx:3-->`
  + `<!--jx:4--><li>c</li><!--/jx:4-->`
  + `<!--/jx:1--></ul>`;

test("a list patch removes, inserts and keeps items, without moving the ones in place", async () => {
  const { document, streams } = await page(LIST);
  const input = document.querySelector("input");
  input.focus();
  input.value = "typing";
  streams[0].push({ version: 1, patches: [{ op: "list", id: 1, items: [3, { id: 5, html: "<li>new</li>" }, 4] }] });
  assert.deepEqual(Array.from(document.querySelectorAll("li"), (li) => li.textContent), ["", "new", "c"]);
  assert.equal(document.activeElement, input, "the kept item wasn't touched: its field keeps the focus");
  assert.equal(input.value, "typing");
  // The new item is a hole of its own, which later patches can reach.
  streams[0].push({ version: 2, patches: [{ op: "html", id: 5, html: "<li>newer</li>" }] });
  assert.equal(document.querySelectorAll("li")[1].textContent, "newer");
});

test("a list patch moves items into their new order", async () => {
  const { document, streams } = await page(LIST);
  streams[0].push({ version: 1, patches: [{ op: "list", id: 1, items: [4, 2, 3] }] });
  assert.deepEqual(Array.from(document.querySelectorAll("li"), (li) => li.textContent), ["c", "a", ""]);
});

test("an html patch merges into the page: a field keeps its element, focus and typed text", async () => {
  const { window, document, streams } = await page(
    `<div><!--jx:1--><p>count 1</p><input id="q" value=""><!--/jx:1--></div>`,
  );
  const input = document.querySelector("#q");
  const paragraph = document.querySelector("p");
  input.focus();
  type(window, input, "hello");
  streams[0].push({ version: 1, patches: [{ op: "html", id: 1, html: `<p class="x">count 2</p><input id="q" value="">` }] });
  assert.equal(document.querySelector("#q"), input, "the same element");
  assert.equal(document.activeElement, input);
  assert.equal(input.value, "hello");
  assert.equal(document.querySelector("p"), paragraph);
  assert.equal(paragraph.textContent, "count 2");
  assert.equal(paragraph.className, "x");
});

test("morphing matches elements by id when others are inserted before them", async () => {
  const { document, streams } = await page(`<div><!--jx:1--><input id="b"><!--/jx:1--></div>`);
  const field = document.querySelector("#b");
  streams[0].push({ version: 1, patches: [{ op: "html", id: 1, html: `<input id="a"><input id="b">` }] });
  assert.equal(document.querySelector("#b"), field, "the element with the id moved, not recreated");
  assert.equal(document.querySelectorAll("input").length, 2);
});
