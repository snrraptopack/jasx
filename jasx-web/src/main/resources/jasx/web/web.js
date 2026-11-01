// The browser client of the JASX web target (jasx-spec/targets/web.md §6–§8).
//
// It finds the page's handlers in data-jx-on attributes, sends their events to the server one at a time,
// and applies the batches of patches the server answers or pushes on the page's stream, in version order.
// Typing stays in the browser: input events are debounced, and a field being typed in is never overwritten.
(() => {
  "use strict";

  const script = document.currentScript;
  const pageId = script.dataset.jxPage;
  const base = new URL(script.src, location.href).pathname.replace(/\/web\.js$/, "");
  const query = "?page=" + encodeURIComponent(pageId);

  // ---------------------------------------------------------------------------------------------
  // Batches (§8.4): applied in version order, whether they come from an event or the stream
  // ---------------------------------------------------------------------------------------------

  let applied = 0;
  const waiting = new Map();
  let gapTimer = null;
  let answering = null; // the element whose event's answer is being applied, for "reset"

  function receive(batch) {
    if (!batch || batch.version <= applied) return;
    waiting.set(batch.version, batch);
    while (waiting.has(applied + 1)) {
      const next = waiting.get(applied + 1);
      waiting.delete(applied + 1);
      applied++;
      next.patches.forEach(apply);
    }
    // A batch that never arrives would block every later one: start again from a fresh page.
    clearTimeout(gapTimer);
    if (waiting.size > 0) gapTimer = setTimeout(reload, 5000);
  }

  function apply(patch) {
    switch (patch.op) {
      case "html": replaceHole(patch.id, patch.html); break;
      case "attr": setAttribute(patch.id, patch.name, patch.value); break;
      case "css": {
        const style = document.createElement("style");
        style.textContent = patch.css;
        document.head.appendChild(style);
        break;
      }
      case "navigate": location.assign(patch.url); break;
      case "reset": {
        // Only in the answer to an event: the form that sent it.
        const form = answering && (answering.tagName === "FORM" ? answering : answering.form);
        if (form) {
          form.reset();
          for (const field of form.elements) field.jxTyped = false;
        }
        break;
      }
      default: console.warn("jasx: unknown patch", patch);
    }
  }

  // Holes are delimited by the comments <!--jx:N--> and <!--/jx:N--> (§8.2).
  const holes = new Map();

  function indexHoles(root) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_COMMENT);
    while (walker.nextNode()) {
      const data = walker.currentNode.data;
      if (data.startsWith("jx:")) holes.set(data.substring(3), walker.currentNode);
    }
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
    if (!start) return; // inside content that was replaced meanwhile
    const endData = "/jx:" + id;
    let node = start.nextSibling;
    while (node && !(node.nodeType === Node.COMMENT_NODE && node.data === endData)) {
      const next = node.nextSibling;
      node.remove();
      node = next;
    }
    // A template parses any content, table rows included, and doesn't run scripts.
    const template = document.createElement("template");
    template.innerHTML = html;
    indexHoles(template.content);
    listenFor(template.content);
    start.parentNode.insertBefore(template.content, node);
  }

  function setAttribute(id, name, value) {
    const element = document.querySelector('[data-jx-id="' + id + '"]');
    if (!element) return;
    const isProperty = name === "value" || name === "checked" || name === "selected";
    // Never overwrite what the user is typing (§7).
    if (name === "value" && element === document.activeElement && element.jxTyped) return;
    if (value === null || value === false) {
      element.removeAttribute(name);
    } else {
      element.setAttribute(name, value === true ? "" : String(value));
    }
    if (isProperty) {
      if (name === "value") element.value = value === null || value === true ? "" : String(value);
      else element[name] = value !== null && value !== false;
    }
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

  const pending = new Map(); // debounced events not sent yet, by element and handler

  function trigger(element, handler, event) {
    if (event.type === "submit") event.preventDefault();
    // What the event itself carries is read now; the element's value is read when the event is sent.
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

  // Typing marks a field, so that a patch doesn't overwrite it until its next event is sent.
  document.addEventListener("input", (event) => { event.target.jxTyped = true; }, true);

  const queue = [];
  let sending = false;

  function enqueue(element, handler, type, details, submitter) {
    if (type === "submit" && element.jxBusy) return; // no double submit
    // The payload is read now, so a debounced event carries the field's latest value.
    const payload = describe(element, handler, type, details, submitter);
    busy(element, +1);
    queue.push({ element, payload, type });
    pump();
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
  function describe(element, handler, type, details, submitter) {
    const payload = { handler, type, ...details };
    const isField = element.tagName === "INPUT" || element.tagName === "SELECT" || element.tagName === "TEXTAREA";
    if (isField) {
      payload.value = element.value;
      payload.checked = Boolean(element.checked);
      if (element.tagName === "SELECT" && element.multiple) {
        payload.values = Array.from(element.selectedOptions, (option) => option.value);
      }
    }
    const form = element.tagName === "FORM" ? element : (type === "submit" ? element.form : null);
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
      clearTyped(item);
      try {
        const response = await fetch(base + "/event" + query, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(item.payload),
        });
        if (response.status === 410) { reload(); return; }
        if (response.status === 200) {
          const batch = await response.json();
          answering = item.element;
          try { receive(batch); } finally { answering = null; }
        }
        else if (response.status !== 204) console.error("jasx: event failed with status " + response.status);
      } catch (error) {
        console.error("jasx: event failed", error);
      } finally {
        busy(item.element, -1);
      }
    }
    sending = false;
  }

  // The fields an event sends are no longer "being typed": patches may change them again.
  function clearTyped(item) {
    item.element.jxTyped = false;
    if (item.type === "submit" && item.element.elements) {
      for (const field of item.element.elements) field.jxTyped = false;
    }
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
  // The stream (§6): batches the server pushes
  // ---------------------------------------------------------------------------------------------

  function connect() {
    const source = new EventSource(base + "/stream" + query);
    source.onmessage = (message) => receive(JSON.parse(message.data));
    source.onerror = () => {
      // EventSource reconnects by itself while the server is unreachable. It closes when the server
      // answers with an error, which means the page is gone (a restart, or it expired).
      if (source.readyState === EventSource.CLOSED) reload();
    };
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
