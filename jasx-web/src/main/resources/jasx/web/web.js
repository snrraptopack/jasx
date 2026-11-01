// The browser client of the JASX web target (jasx-spec/targets/web.md §6–§8).
//
// It finds the page's handlers in data-jx-on attributes, sends their events to the server one at a time,
// and applies the batches of patches the server answers or pushes on the page's stream, in version order.
// Typing stays in the browser: input events are debounced, and edits the server hasn't received yet are
// never overwritten. If a batch goes missing, it reconnects and the server sends what it missed (§6.1).
(() => {
  "use strict";

  const script = document.currentScript;
  const pageId = script.dataset.jxPage;
  const base = new URL(script.src, location.href).pathname.replace(/\/web\.js$/, "");
  const query = "?page=" + encodeURIComponent(pageId);

  /** How long a missing batch may be waited for before asking the server again (§6.1). */
  const GAP_DEADLINE = 3000;
  /** Batches waiting for a missing one, at most; beyond that, ask the server again at once. */
  const MAX_WAITING = 500;
  /** Events whose answers may still arrive, remembered for their reset patches (§8.4). */
  const MAX_REMEMBERED_EVENTS = 200;

  // ---------------------------------------------------------------------------------------------
  // Batches (§8.4): applied in version order, whether they come from an event or the stream
  // ---------------------------------------------------------------------------------------------

  let applied = 0;
  const waiting = new Map(); // by first version: batches that arrived before the one they follow
  let gapTimer = null;

  // A batch is {version, patches}, or {from, version, patches} when it merges several (a catch-up).
  // A merged batch may overlap versions already applied: applying a patch again is harmless (§8.4).
  function receive(batch) {
    if (!batch) return;
    if (batch.reload) { reload(); return; }
    if (batch.version <= applied) return;
    const from = batch.from ?? batch.version;
    if (from > applied + 1) {
      waiting.set(from, batch);
      if (waiting.size > MAX_WAITING) resync();
    } else {
      applyBatch(batch);
      applyWaiting();
    }
    watchForGap();
  }

  function applyBatch(batch) {
    applied = batch.version;
    batch.patches.forEach(apply);
  }

  // Applies the waiting batches that now follow on, and drops those already covered.
  function applyWaiting() {
    let progressed = true;
    while (progressed) {
      progressed = false;
      for (const [from, batch] of waiting) {
        if (batch.version <= applied) {
          waiting.delete(from);
        } else if (from <= applied + 1) {
          waiting.delete(from);
          applyBatch(batch);
          progressed = true;
        }
      }
    }
  }

  // The deadline starts when a gap appears and is not pushed back by later batches, so a page with
  // frequent pushes still recovers (§6.1).
  function watchForGap() {
    if (waiting.size === 0) {
      clearTimeout(gapTimer);
      gapTimer = null;
    } else if (gapTimer === null) {
      gapTimer = setTimeout(() => { gapTimer = null; resync(); }, GAP_DEADLINE);
    }
  }

  function apply(patch) {
    switch (patch.op) {
      case "html": replaceHole(patch.id, patch.html); break;
      case "attr": setAttribute(patch.id, patch.name, patch.value); break;
      case "css": addStyleSheet(patch.css); break;
      case "navigate": location.assign(patch.url); break;
      case "reset": resetForm(patch.event); break;
      default: console.warn("jasx: unknown patch", patch);
    }
  }

  const styleSheets = new Set();

  function addStyleSheet(css) {
    if (styleSheets.has(css)) return; // a catch-up may send it again
    styleSheets.add(css);
    const style = document.createElement("style");
    style.textContent = css;
    document.head.appendChild(style);
  }

  // ---------------------------------------------------------------------------------------------
  // Holes (§8.2): content between the comments <!--jx:N--> and <!--/jx:N-->
  // ---------------------------------------------------------------------------------------------

  const holes = new Map();

  function indexHoles(root) {
    eachComment(root, (comment) => {
      if (comment.data.startsWith("jx:")) holes.set(comment.data.substring(3), comment);
    });
  }

  // Removed content takes its holes with it: forgetting them keeps the index from holding on to
  // detached nodes on a page that lives long.
  function forgetHoles(node) {
    eachComment(node, (comment) => {
      if (comment.data.startsWith("jx:")) holes.delete(comment.data.substring(3));
    });
  }

  function eachComment(root, visit) {
    if (root.nodeType === Node.COMMENT_NODE) { visit(root); return; }
    if (!root.childNodes || root.childNodes.length === 0) return;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_COMMENT);
    while (walker.nextNode()) visit(walker.currentNode);
  }

  function holeStart(id) {
    let start = holes.get(String(id));
    if (!start || !start.isConnected) {
      indexHoles(document);
      start = holes.get(String(id));
    }
    return start && start.isConnected ? start : null;
  }

  function replaceHole(id, html) {
    const start = holeStart(id);
    if (!start) return; // inside content that was replaced meanwhile: nothing to do
    const end = holeEnd(start, id);
    if (!end) {
      // The page's markup no longer matches what the server rendered: start afresh rather than guess.
      console.error("jasx: the end of hole " + id + " is missing");
      reload();
      return;
    }
    for (let node = start.nextSibling; node !== end; ) {
      const next = node.nextSibling;
      forgetHoles(node);
      node.remove();
      node = next;
    }
    const content = parseIn(start.parentNode, html);
    indexHoles(content);
    listenFor(content);
    start.parentNode.insertBefore(content, end);
  }

  function holeEnd(start, id) {
    const endData = "/jx:" + id;
    for (let node = start.nextSibling; node; node = node.nextSibling) {
      if (node.nodeType === Node.COMMENT_NODE && node.data === endData) return node;
    }
    return null;
  }

  const SVG = "http://www.w3.org/2000/svg";
  const MATHML = "http://www.w3.org/1998/Math/MathML";

  // Parses HTML as content of `parent`: inside SVG or MathML, elements must be created in that
  // namespace, which a plain template doesn't do. A template also parses table rows and doesn't run
  // scripts.
  function parseIn(parent, html) {
    const template = document.createElement("template");
    const namespace = parent.namespaceURI;
    const foreign = (namespace === SVG && parent.localName !== "foreignObject") ? "svg"
      : namespace === MATHML ? "math" : null;
    if (!foreign) {
      template.innerHTML = html;
      return template.content;
    }
    template.innerHTML = "<" + foreign + ">" + html + "</" + foreign + ">";
    const wrapper = template.content.firstChild;
    const content = document.createDocumentFragment();
    while (wrapper.firstChild) content.appendChild(wrapper.firstChild);
    return content;
  }

  // ---------------------------------------------------------------------------------------------
  // Attributes and fields (§7)
  // ---------------------------------------------------------------------------------------------

  function setAttribute(id, name, value) {
    const element = document.querySelector('[data-jx-id="' + id + '"]');
    if (!element) return;
    if (name === "value" && hasUnsentEdits(element)) return; // never overwrite what the user typed (§7)
    if (value === null || value === false) {
      element.removeAttribute(name);
    } else {
      element.setAttribute(name, value === true ? "" : String(value));
    }
    if (name === "value") element.value = value === null || value === true ? "" : String(value);
    else if (name === "checked" || name === "selected") element[name] = value !== null && value !== false;
  }

  // Each edit of a field bumps its revision. An event remembers the revision of each field it carries,
  // and once it is sent, those revisions are the server's. A field has unsent edits while its revision is
  // ahead of what the server received; such a field is kept as the user left it, if the server will
  // receive it (it has a handler, or its form has) or the user is still in it.
  document.addEventListener("input", (event) => {
    const field = event.target;
    field.jxRevision = (field.jxRevision || 0) + 1;
  }, true);

  function hasUnsentEdits(field) {
    const ahead = (field.jxRevision || 0) > (field.jxSent || 0);
    if (!ahead) return false;
    const willBeSent = field.hasAttribute("data-jx-on") || (field.form && field.form.hasAttribute("data-jx-on"));
    return willBeSent || field === document.activeElement;
  }

  // The fields an event carries, with the revision each has now.
  function revisionsOf(element, form) {
    const revisions = new Map();
    if (isField(element)) revisions.set(element, element.jxRevision || 0);
    if (form) for (const field of form.elements) revisions.set(field, field.jxRevision || 0);
    return revisions;
  }

  function markSent(revisions) {
    for (const [field, revision] of revisions) {
      field.jxSent = Math.max(field.jxSent || 0, revision);
    }
  }

  // Resets the form of event `seq` (e.resetForm()). A field edited since that event was sent keeps the
  // new text: only what the event carried is cleared.
  function resetForm(seq) {
    const sent = rememberedEvents.get(seq);
    if (!sent || !sent.form) return;
    for (const field of sent.form.elements) {
      const carried = sent.revisions.get(field);
      if (carried === undefined || (field.jxRevision || 0) > carried) continue;
      resetField(field);
    }
  }

  function resetField(field) {
    if (field.type === "checkbox" || field.type === "radio") {
      field.checked = field.defaultChecked;
    } else if (field.tagName === "SELECT") {
      for (const option of field.options) option.selected = option.defaultSelected;
    } else if ("defaultValue" in field) {
      field.value = field.defaultValue;
    }
  }

  function isField(element) {
    return element.tagName === "INPUT" || element.tagName === "SELECT" || element.tagName === "TEXTAREA";
  }

  // ---------------------------------------------------------------------------------------------
  // Events (§7): delegated, debounced, and sent one at a time
  // ---------------------------------------------------------------------------------------------

  const listening = new Set();

  // Listens on the document, in the capture phase, for every event type a handler is registered for, so
  // that events that don't bubble (focus, blur) are seen too.
  function listenFor(root) {
    const elements = root.querySelectorAll ? root.querySelectorAll("[data-jx-on]") : [];
    for (const element of elements) {
      for (const entry of element.getAttribute("data-jx-on").split(" ")) {
        const type = entry.split(":")[0];
        if (!listening.has(type)) {
          listening.add(type);
          document.addEventListener(type, onEvent, true);
        }
      }
    }
  }

  function handlerOf(element, type) {
    const spec = element.getAttribute && element.getAttribute("data-jx-on");
    if (!spec) return null;
    for (const entry of spec.split(" ")) {
      const [entryType, id, delay] = entry.split(":");
      if (entryType === type) return { id: Number(id), delay: delay ? Number(delay) : 0 };
    }
    return null;
  }

  // The nearest element with a handler for the event's type, as if the event bubbled to it.
  function onEvent(event) {
    for (let element = event.target; element && element !== document; element = element.parentNode) {
      const handler = handlerOf(element, event.type);
      if (handler) {
        trigger(element, handler, event);
        return;
      }
      if (!event.bubbles) return;
    }
  }

  const pending = new Map(); // debounced events not sent yet, by handler

  function trigger(element, handler, event) {
    if (event.type === "submit") event.preventDefault();
    // What the event itself carries is read now; the element's value is read when the event is queued.
    const details = eventDetails(event);
    const send = () => enqueue(element, handler.id, event.type, details, event.submitter || null);
    if (handler.delay > 0) {
      const id = handler.id;
      clearTimeout(pending.get(id)?.timer);
      pending.set(id, { send, timer: setTimeout(() => { pending.delete(id); send(); }, handler.delay) });
      return;
    }
    flushPending(); // a handler always sees the latest input
    send();
  }

  function flushPending() {
    for (const [id, entry] of pending) {
      clearTimeout(entry.timer);
      pending.delete(id);
      entry.send();
    }
  }

  const queue = [];
  let sending = false;
  let lastSeq = 0;
  const rememberedEvents = new Map(); // seq -> {form, revisions}, for reset patches

  function enqueue(element, handler, type, details, submitter) {
    if (type === "submit" && element.jxBusy) return; // no double submit
    const form = element.tagName === "FORM" ? element : (type === "submit" ? element.form : null);
    // The payload is read now, so a debounced event carries the field's latest value.
    const seq = ++lastSeq;
    const payload = describe(element, handler, type, details, form, submitter, seq);
    const revisions = revisionsOf(element, form);
    remember(seq, { form: form || element.form || null, revisions });
    busy(element, +1);
    queue.push({ element, payload, revisions });
    pump();
  }

  function remember(seq, sent) {
    rememberedEvents.set(seq, sent);
    if (rememberedEvents.size > MAX_REMEMBERED_EVENTS) {
      rememberedEvents.delete(rememberedEvents.keys().next().value);
    }
  }

  // The keys and pointer of keyboard and mouse events (jasx-spec/ui.md §4): KeyEvent and MouseEvent.
  function eventDetails(event) {
    const details = {};
    const isKey = "key" in event && typeof event.key === "string";
    const isMouse = "button" in event && "clientX" in event;
    if (isKey) details.key = event.key;
    if (isMouse) {
      details.button = event.button;
      details.x = event.clientX;
      details.y = event.clientY;
    }
    if (isKey || isMouse) {
      details.ctrl = event.ctrlKey;
      details.shift = event.shiftKey;
      details.alt = event.altKey;
      details.meta = event.metaKey;
    }
    return details;
  }

  // The event as the server reads it (targets/web.md §8.3): the element's value if it is a field (InputEvent),
  // the form's fields for a submit (FormEvent), and the event's own details.
  function describe(element, handler, type, details, form, submitter, seq) {
    const payload = { handler, seq, type, ...details };
    if (isField(element)) {
      payload.value = element.value;
      payload.checked = Boolean(element.checked);
      if (element.tagName === "SELECT" && element.multiple) {
        payload.values = Array.from(element.selectedOptions, (option) => option.value);
      }
    }
    if (form) payload.fields = formFields(form, submitter);
    return payload;
  }

  function formFields(form, submitter) {
    let data;
    try { data = new FormData(form, submitter); } catch (e) { data = new FormData(form); }
    const fields = {};
    for (const [name, value] of data) {
      (fields[name] ||= []).push(typeof value === "string" ? value : value.name);
    }
    return fields;
  }

  async function pump() {
    if (sending) return;
    sending = true;
    while (queue.length > 0) {
      const item = queue.shift();
      markSent(item.revisions);
      try {
        const response = await fetch(base + "/event" + query, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(item.payload),
        });
        if (response.status === 410) { reload(); return; }
        if (response.status === 200) receive(await response.json());
        else if (response.status !== 204) console.error("jasx: event failed with status " + response.status);
      } catch (error) {
        // The handler may have run although its answer was lost: the server still has the batch, and
        // a reconnection fetches it (§6.1).
        console.error("jasx: event failed", error);
        resync();
      } finally {
        busy(item.element, -1);
      }
    }
    sending = false;
  }

  // While its event is queued or running, an element has the class jx-loading and aria-busy (§7).
  function busy(element, delta) {
    element.jxBusy = (element.jxBusy || 0) + delta;
    const isBusy = element.jxBusy > 0;
    element.classList.toggle("jx-loading", isBusy);
    if (isBusy) element.setAttribute("aria-busy", "true");
    else element.removeAttribute("aria-busy");
  }

  // ---------------------------------------------------------------------------------------------
  // The stream (§6): batches the server pushes, and catching up after a gap
  // ---------------------------------------------------------------------------------------------

  let source = null;
  let retries = 0;
  let retryTimer = null;

  // Opens the stream, telling the server the last version applied, so it first sends what was missed
  // (§6.1). The client reconnects by itself rather than letting EventSource do it, because each
  // reconnection must carry the current version.
  function connect() {
    clearTimeout(retryTimer);
    if (source) source.close();
    const stream = new EventSource(base + "/stream" + query + "&since=" + applied);
    source = stream;
    stream.onopen = () => { retries = 0; };
    stream.onmessage = (message) => receive(JSON.parse(message.data));
    stream.onerror = () => {
      if (source !== stream) return;
      stream.close();
      source = null;
      retries++;
      retryTimer = setTimeout(connect, Math.min(10000, 500 * 2 ** Math.min(retries, 5)));
    };
  }

  // Asks the server again for everything after the last version applied.
  function resync() {
    connect();
  }

  // Loads a fresh page, at most once every 10 seconds, so a broken server can't cause a reload loop.
  function reload() {
    const last = Number(sessionStorage.getItem("jx-reload") || 0);
    if (Date.now() - last < 10000) {
      console.error("jasx: this page is no longer known to the server; reload it to continue");
      return;
    }
    sessionStorage.setItem("jx-reload", String(Date.now()));
    location.reload();
  }

  indexHoles(document);
  listenFor(document);
  connect();
})();
