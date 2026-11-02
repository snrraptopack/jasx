// The browser client of the JASX web target (jasx-spec/targets/web.md §6–§8).
//
// It finds the page's handlers in data-jx-on attributes, sends their events to the server one at a time,
// and applies the batches of patches the server answers or pushes on the page's stream, in version order.
// Typing stays in the browser: input events are debounced, and edits the server hasn't received yet are
// never overwritten. If a batch goes missing, it reconnects and the server sends what it missed (§6.1).
// The tabs of an application share one stream (§6.2). A link to another page of the application loads it
// without reloading the browser's page (§6.4): this client stops, and one for the new page starts.
(() => {
  "use strict";

  // Everything this client registers is recorded, so that it can stop completely when a navigation starts a
  // client for the next page (§6.4): listeners on the document and window, and timers. Inside the client,
  // setTimeout and setInterval are these, so every timer is recorded and does nothing once it has stopped.
  let stopped = false;
  const registrations = [];
  const timers = new Set();

  function listen(target, type, listener, options) {
    target.addEventListener(type, listener, options);
    registrations.push([target, type, listener, options]);
  }

  const setTimeout = (fn, ms) => {
    const id = window.setTimeout(() => {
      timers.delete(id);
      if (!stopped) fn();
    }, ms);
    timers.add(id);
    return id;
  };

  const setInterval = (fn, ms) => {
    const id = window.setInterval(() => { if (!stopped) fn(); }, ms);
    timers.add(id);
    return id;
  };

  const script = document.currentScript;
  const pageId = script.dataset.jxPage;
  /** Pages to name in the next stream request as left for good (§6.4): the one this page replaced, if any. */
  const leaving = script.dataset.jxReplaces ? [script.dataset.jxReplaces] : [];
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
    if (!batch || stopped) return; // an answer that arrives after a navigation is for the old page
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
      case "html": replaceHole(patch.id, htmlOf(patch)); break;
      case "list": arrangeItems(patch.id, patch.items); break;
      case "attr": setAttribute(patch.id, patch.name, patch.value); break;
      case "css": addStyleSheet(patch.css); break;
      case "tpl": templates.set(patch.t, patch.s); break;
      case "navigate": follow(patch.url); break;
      case "reset": resetForm(patch.event); break;
      default: console.warn("jasx: unknown patch", patch);
    }
  }

  // Templates (§8.4): the statics of a shape the server sent once, by number. Content that uses one carries only
  // its values, already escaped: the HTML is statics[0] + values[0] + statics[1] + ... + statics[n].
  const templates = new Map();

  // The HTML of an html patch or a new list item: given as it is, or as a template's values.
  function htmlOf(content) {
    if (content.t === undefined) return content.html;
    const statics = templates.get(content.t);
    if (!statics) {
      console.error("jasx: template " + content.t + " is unknown");
      reload();
      return "";
    }
    let html = statics[0];
    for (let i = 0; i < content.v.length; i++) html += content.v[i] + statics[i + 1];
    return html;
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

  // The start and end markers of hole `id`; null if the hole isn't in the page (it was inside content
  // replaced meanwhile). A start without an end means the page's markup no longer matches what the server
  // rendered: then it reloads, rather than guess.
  function holeRange(id) {
    const start = holeStart(id);
    if (!start) return null;
    const end = holeEnd(start, id);
    if (!end) {
      console.error("jasx: the end of hole " + id + " is missing");
      reload();
      return null;
    }
    return { start, end };
  }

  // An html patch (§8.4): the hole's new content is merged into what the page has, so elements that stay
  // keep their focus, their typed text and their scroll position.
  function replaceHole(id, html) {
    const range = holeRange(id);
    if (!range) return;
    const parent = range.start.parentNode;
    forEachBetween(range.start, range.end, forgetHoles); // ids of holes inside change with each rendering
    const content = parseIn(parent, html);
    rememberRendered(content);
    morphChildren(parent, range.start.nextSibling, range.end, content);
    forEachBetween(range.start, range.end, indexHoles);
    listenFor(parent);
  }

  function forEachBetween(start, end, visit) {
    for (let node = start.nextSibling; node && node !== end; node = node.nextSibling) visit(node);
  }

  // A list patch (§8.4): `entries` are the items of keyed hole `id` in their new order. A number is an item
  // the page has, which is moved only if it is out of place, so it keeps its DOM state; {id, html} is a new
  // item. Items not listed are removed first, so that removing one doesn't move the others.
  function arrangeItems(id, entries) {
    const range = holeRange(id);
    if (!range) return;
    const parent = range.start.parentNode;
    const kept = new Set(entries.filter((entry) => typeof entry === "number").map(String));
    removeItemsExcept(range, kept);

    let cursor = range.start.nextSibling; // where the next item belongs
    for (const entry of entries) {
      if (typeof entry === "number") {
        const item = holeRange(entry);
        if (!item || item.start.parentNode !== parent) {
          reload(); // the page lost an item the server kept: start afresh
          return;
        }
        if (item.start !== cursor) moveRange(item, cursor);
        cursor = item.end.nextSibling;
      } else {
        const item = newItem(parent, entry);
        parent.insertBefore(item, cursor);
      }
    }
  }

  // Removes the items of a keyed hole that aren't in `kept` (their ids as strings).
  function removeItemsExcept(range, kept) {
    for (let node = range.start.nextSibling; node && node !== range.end; ) {
      const isItemStart = node.nodeType === Node.COMMENT_NODE && node.data.startsWith("jx:");
      if (!isItemStart) {
        node = node.nextSibling;
        continue;
      }
      const itemId = node.data.substring(3);
      const end = holeEnd(node, itemId);
      const after = end ? end.nextSibling : null;
      if (!kept.has(itemId)) {
        for (let n = node; n && n !== after; ) {
          const next = n.nextSibling;
          forgetHoles(n);
          n.remove();
          n = next;
        }
      }
      node = after;
    }
  }

  function moveRange(item, before) {
    const parent = item.start.parentNode;
    const after = item.end.nextSibling;
    for (let node = item.start; node !== after; ) {
      const next = node.nextSibling;
      parent.insertBefore(node, before);
      node = next;
    }
  }

  // A new item between its own markers, indexed and listened to.
  function newItem(parent, entry) {
    const item = document.createDocumentFragment();
    item.appendChild(document.createComment("jx:" + entry.id));
    item.appendChild(parseIn(parent, htmlOf(entry)));
    item.appendChild(document.createComment("/jx:" + entry.id));
    rememberRendered(item);
    indexHoles(item);
    listenFor(item);
    return item;
  }

  // ---------------------------------------------------------------------------------------------
  // Morphing (§8.4): new content merged into existing nodes
  // ---------------------------------------------------------------------------------------------

  // Makes the children of `parent` from `first` up to `stop` (exclusive; null for the last child) like the
  // children of `source`. Nodes are matched in order, by kind and tag; an element with an id is matched to
  // the element with that id, wherever it is among the remaining nodes. A matched node is updated in place;
  // an unmatched one is inserted, and old nodes left over are removed.
  function morphChildren(parent, first, stop, source) {
    let old = first;
    let incoming = source.firstChild;
    while (incoming) {
      const nextIncoming = incoming.nextSibling;
      const byId = incoming.nodeType === Node.ELEMENT_NODE && incoming.id ? findById(old, stop, incoming.id) : null;
      if (byId && byId !== old) {
        parent.insertBefore(byId, old);
        old = byId;
      }
      if (old && old !== stop && sameKind(old, incoming)) {
        morphNode(old, incoming);
        old = old.nextSibling;
      } else {
        parent.insertBefore(incoming, old && old !== stop ? old : stop);
      }
      incoming = nextIncoming;
    }
    while (old && old !== stop) {
      const next = old.nextSibling;
      old.remove();
      old = next;
    }
  }

  function findById(from, stop, id) {
    for (let node = from; node && node !== stop; node = node.nextSibling) {
      if (node.nodeType === Node.ELEMENT_NODE && node.id === id) return node;
    }
    return null;
  }

  // Nodes match when they are of the same kind and tag. Elements with ids match only the same id, so that
  // an element inserted before one with an id doesn't take its place (and its focus).
  function sameKind(old, incoming) {
    if (old.nodeType !== incoming.nodeType) return false;
    if (old.nodeType !== Node.ELEMENT_NODE) return true;
    return old.localName === incoming.localName && old.namespaceURI === incoming.namespaceURI
      && old.id === incoming.id;
  }

  function morphNode(old, incoming) {
    if (old.nodeType !== Node.ELEMENT_NODE) {
      if (old.nodeValue !== incoming.nodeValue) old.nodeValue = incoming.nodeValue;
      return;
    }
    morphAttributes(old, incoming);
    if (old.localName === "textarea") {
      const text = incoming.textContent;
      if (old.defaultValue !== text) old.defaultValue = text;
      if (!hasUnsentEdits(old)) old.value = text;
      return;
    }
    morphChildren(old, old.firstChild, null, incoming);
  }

  // What the server last rendered for each element: its attributes, by name (§8.4). A merge changes only
  // what the server changed since, so what the browser did to an element stays: a <details> the user opened,
  // a class or attribute added by a script or an extension, the busy marks of a running event.
  const rendered = new WeakMap();

  function rememberRendered(root) {
    if (root.nodeType === Node.ELEMENT_NODE) rendered.set(root, attributesOf(root));
    if (root.querySelectorAll) {
      for (const element of root.querySelectorAll("*")) rendered.set(element, attributesOf(element));
    }
  }

  function attributesOf(element) {
    const attributes = new Map();
    for (const attribute of element.attributes) attributes.set(attribute.name, attribute.value);
    return attributes;
  }

  // A three-way merge: an attribute changes only if the server's new value differs from its previous one.
  // An element the client never saw rendered (made by a script) is compared with what it shows, which makes
  // it like the server's rendering. The value of a field with unsent edits is never touched (§7).
  function morphAttributes(element, incoming) {
    const before = rendered.get(element) ?? attributesOf(element);
    const after = rendered.get(incoming) ?? attributesOf(incoming);
    const keepValue = isField(element) && hasUnsentEdits(element);
    for (const name of new Set([...before.keys(), ...after.keys()])) {
      const was = before.get(name) ?? null;
      const now = after.get(name) ?? null;
      if (was === now) continue; // the server didn't change it: what the browser did to it stays
      if (keepValue && name === "value") continue;
      if (name === "class") mergeClasses(element, was, now);
      else setRendered(element, name, now);
    }
    rendered.set(element, after);
    if (element.jxBusy > 0) {
      element.classList.add("jx-loading");
      element.setAttribute("aria-busy", "true");
    }
  }

  // Classes the server added are added and those it removed are removed; the others, including classes
  // added in the browser, stay.
  function mergeClasses(element, was, now) {
    const previous = classNames(was);
    const next = classNames(now);
    for (const name of previous) if (!next.has(name)) element.classList.remove(name);
    for (const name of next) if (!previous.has(name)) element.classList.add(name);
    if (element.getAttribute("class") === "" && now === null) element.removeAttribute("class");
  }

  function classNames(value) {
    return new Set(value ? value.split(/\s+/).filter(Boolean) : []);
  }

  function setRendered(element, name, value) {
    if (value === null) element.removeAttribute(name);
    else element.setAttribute(name, value);
    syncProperty(element, name, value);
  }

  // value, checked and selected are what a field shows: the attribute alone doesn't change it.
  function syncProperty(element, name, value) {
    if (name === "value" && isField(element)) element.value = value === null ? "" : value;
    else if (name === "checked" || name === "selected") element[name] = value !== null;
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

  // An attr patch: the server changed one attribute. `class` is merged by name, as in a merge (§8.4), and
  // the element's remembered rendering is updated.
  function setAttribute(id, name, value) {
    const element = document.querySelector('[data-jx-id="' + id + '"]');
    if (!element) return;
    const now = value === null || value === false ? null : value === true ? "" : String(value);
    const remembered = rendered.get(element) ?? attributesOf(element);
    const was = remembered.has(name) ? remembered.get(name) : null;
    if (now === null) remembered.delete(name);
    else remembered.set(name, now);
    rendered.set(element, remembered);
    if (name === "value" && hasUnsentEdits(element)) return; // never overwrite what the user typed (§7)
    if (name === "class") mergeClasses(element, was, now);
    else setRendered(element, name, now);
  }

  // Each edit of a field bumps its revision. An event remembers the revision of each field it carries,
  // and once it is sent, those revisions are the server's. A field has unsent edits while its revision is
  // ahead of what the server received; such a field is kept as the user left it, if the server will
  // receive it (it has a handler, or its form has) or the user is still in it.
  listen(document, "input", (event) => {
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
        ensureListening(entry.split(":")[0]);
      }
    }
  }

  // One listener per event type, for browser attributes and Java handlers alike: on an element with both, the
  // browser attribute runs first (§7.1), then the Java handler's event is sent.
  function ensureListening(type) {
    if (listening.has(type)) return;
    listening.add(type);
    listen(document, type, (event) => {
      runBrowserAttribute(event);
      onEvent(event);
    }, true);
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
    queue.push({ element, payload, revisions, uploads: form ? chosenFiles(form, payload.fields) : [] });
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

  // The form's fields, as FormData gives them, without its file fields: chosenFiles adds those.
  function formFields(form, submitter) {
    let data;
    try { data = new FormData(form, submitter); } catch (e) { data = new FormData(form); }
    const fields = {};
    for (const [name, value] of data) {
      if (typeof value === "string") (fields[name] ||= []).push(value);
    }
    return fields;
  }

  // Uploads (§8.1): each file chosen in the form's file fields is uploaded before the event is sent, and the
  // event's field carries its token in place of the file. Here each file gets its place in `fields`, filled
  // in once its upload is done.
  function chosenFiles(form, fields) {
    const uploads = [];
    for (const input of form.querySelectorAll("input[type=file][name]")) {
      if (input.disabled || !input.files || input.files.length === 0) continue;
      const values = (fields[input.name] ||= []);
      for (const file of input.files) {
        uploads.push({ name: input.name, index: values.length, file });
        values.push("");
      }
    }
    return uploads;
  }

  async function uploadFiles(item) {
    for (const upload of item.uploads) {
      const response = await fetch(base + "/upload" + query, {
        method: "POST",
        headers: {
          "Content-Type": "application/octet-stream",
          "X-Jasx-Name": encodeURIComponent(upload.file.name),
          "X-Jasx-Type": upload.file.type || "application/octet-stream",
        },
        body: upload.file,
      });
      if (response.status === 410) throw new Gone();
      if (response.status !== 200) {
        console.error("jasx: the upload of " + upload.file.name + " failed with status " + response.status);
        continue; // the event goes without this file
      }
      const answer = await response.json();
      item.payload.fields[upload.name][upload.index] = "jx-upload:" + answer.upload;
    }
  }

  class Gone extends Error {}

  async function pump() {
    if (sending) return;
    sending = true;
    while (queue.length > 0) {
      const item = queue.shift();
      markSent(item.revisions);
      try {
        await uploadFiles(item);
        const response = await fetch(base + "/event" + query, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(item.payload),
        });
        if (response.status === 410) { reload(); return; }
        if (response.status === 200) receive(await response.json());
        else if (response.status !== 204) console.error("jasx: event failed with status " + response.status);
        item.done?.(response.status === 200 || response.status === 204);
      } catch (error) {
        item.done?.(false);
        if (error instanceof Gone) { reload(); return; }
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
  // Browser code (§7.1): browser attributes (data-jx-js-<event>), refs, @render and cleanups
  // ---------------------------------------------------------------------------------------------
  //
  // A browser attribute names a function of a module and its payload: ["web/Menu.jasx.js", "toggleMenu",
  // {...}]. The function is called as fn(event, payload), with refs ({"$ref": name}) resolved to elements and
  // Java handlers ({"handler": id}) turned into functions that send to the server. What it returns, if a function
  // (or a promise of one), is its cleanup: run before the same attribute runs again on the element, and when the
  // element leaves the page.

  const modulesBase = script.dataset.jxJs || null;
  /** Modules being loaded or loaded, by path; and the loaded ones, so calls can be synchronous. */
  const loadingModules = new Map();
  const loadedModules = new Map();
  /** The pending cleanups: element -> Map(attribute event -> cleanup). */
  const cleanups = new Map();
  /** The payload each element's @render last ran with, so it runs again only when it changes. */
  const renderedWith = new WeakMap();
  let observer = null;

  // How a module is imported: import() in a browser; tests give their own.
  const importModule = window.__jasxImport || ((url) => import(url));

  function loadModule(path) {
    if (!loadingModules.has(path)) {
      const loading = importModule(modulesBase + path).then((module) => {
        loadedModules.set(path, module);
        return module;
      });
      loading.catch((error) => console.error("jasx: the module " + path + " didn't load", error));
      loadingModules.set(path, loading);
    }
    return loadingModules.get(path);
  }

  function browserAttributes(element) {
    const found = [];
    for (const attribute of element.attributes || []) {
      if (attribute.name.startsWith("data-jx-js-")) found.push(attribute.name.substring("data-jx-js-".length));
    }
    return found;
  }

  // Prepares the elements under root that have browser attributes: listens for their events, loads their
  // modules, and runs each @render that hasn't run with its current payload.
  function bindBrowserCode(root) {
    if (!modulesBase) return;
    const elements = [];
    if (root.nodeType === 1) elements.push(root);
    if (root.querySelectorAll) elements.push(...root.querySelectorAll("*"));
    for (const element of elements) {
      for (const type of browserAttributes(element)) {
        const call = parseCall(element, type);
        if (call) loadModule(call[0]);
        if (type === "render") renderIfChanged(element);
        else ensureListening(type);
      }
    }
  }

  function renderIfChanged(element) {
    const payload = element.getAttribute("data-jx-js-render");
    if (payload === null || renderedWith.get(element) === payload) return;
    renderedWith.set(element, payload);
    callBrowserAttribute(element, "render", new CustomEvent("render"));
  }

  // The nearest element with a browser attribute for the event's type, as if the event bubbled to it.
  function runBrowserAttribute(event) {
    if (!modulesBase || !event.target || event.target.nodeType !== 1) return;
    const name = "data-jx-js-" + event.type;
    for (let element = event.target; element && element !== document; element = element.parentNode) {
      if (element.hasAttribute && element.hasAttribute(name)) {
        callBrowserAttribute(element, event.type, event);
        return;
      }
      if (!event.bubbles) return;
    }
  }

  function parseCall(element, type) {
    try {
      return JSON.parse(element.getAttribute("data-jx-js-" + type));
    } catch (error) {
      console.error("jasx: a malformed browser attribute", element, error);
      return null;
    }
  }

  // Calls the element's function for this browser attribute: at once if its module is loaded (so that
  // preventDefault() works), otherwise once it has loaded.
  function callBrowserAttribute(element, type, event) {
    const call = parseCall(element, type);
    if (!call) return;
    const [path, name, payload] = call;
    const run = (module) => {
      if (stopped) return;
      const fn = module[name];
      if (typeof fn !== "function") {
        console.error("jasx: " + name + " is not a function of " + path);
        return;
      }
      runCleanup(element, type);
      let result;
      try {
        result = fn.call(element, event, resolvePayload(element, payload));
      } catch (error) {
        console.error(error);
        return;
      }
      // A cleanup returned at once is kept at once, so a second click right after the first still runs it.
      if (typeof result === "function") {
        keepCleanup(element, type, result);
      } else if (result && typeof result.then === "function") {
        result.then((cleanup) => {
          if (typeof cleanup === "function") keepCleanup(element, type, cleanup);
        }, (error) => console.error(error));
      }
    };
    const module = loadedModules.get(path);
    if (module) run(module);
    else loadModule(path).then(run, () => {});
  }

  function keepCleanup(element, type, cleanup) {
    if (!cleanups.has(element)) cleanups.set(element, new Map());
    cleanups.get(element).set(type, cleanup);
  }

  function runCleanup(element, type) {
    const forElement = cleanups.get(element);
    const cleanup = forElement?.get(type);
    if (!cleanup) return;
    forElement.delete(type);
    if (forElement.size === 0) cleanups.delete(element);
    try {
      cleanup();
    } catch (error) {
      console.error(error);
    }
  }

  // Runs the cleanups of elements that have left the page.
  function cleanUpRemoved() {
    for (const element of Array.from(cleanups.keys())) {
      if (!element.isConnected) {
        for (const type of Array.from(cleanups.get(element).keys())) runCleanup(element, type);
      }
    }
  }

  // The payload as the function receives it: refs as elements, handlers as functions.
  function resolvePayload(origin, value) {
    if (Array.isArray(value)) return value.map((item) => resolvePayload(origin, item));
    if (value === null || typeof value !== "object") return value;
    const keys = Object.keys(value);
    if (keys.length === 1 && keys[0] === "$ref") return findRef(origin, value.$ref);
    if (keys.length === 1 && keys[0] === "handler" && typeof value.handler === "number") {
      return serverHandler(origin, value.handler);
    }
    const resolved = {};
    for (const key of keys) resolved[key] = resolvePayload(origin, value[key]);
    return resolved;
  }

  // The element with this ref nearest to origin: the fewest steps up to a common ancestor and down to it; the
  // first in document order among equally near ones.
  function findRef(origin, name) {
    const depthOf = new Map();
    let depth = 0;
    for (let node = origin; node; node = node.parentNode) depthOf.set(node, depth++);
    let best = null;
    let bestDistance = Infinity;
    for (const candidate of document.querySelectorAll('[data-jx-ref="' + name + '"]')) {
      let down = 0;
      let node = candidate;
      while (node && !depthOf.has(node)) {
        node = node.parentNode;
        down++;
      }
      const distance = node ? depthOf.get(node) + down : Infinity;
      if (distance < bestDistance) {
        best = candidate;
        bestDistance = distance;
      }
    }
    return best;
  }

  // A Java handler, as a function: handler(value) sends an event to it through the queue, with a string,
  // number or boolean as the value, or an object's properties as fields; it resolves once the update is in the
  // page, and rejects if the request fails or this client has stopped.
  function serverHandler(element, handler) {
    return (value) => new Promise((resolve, reject) => {
      if (stopped) {
        reject(new Error("jasx: the page has gone"));
        return;
      }
      const payload = { handler, seq: ++lastSeq, type: "call" };
      if (value !== null && typeof value === "object") {
        payload.fields = {};
        for (const [key, field] of Object.entries(value)) {
          payload.fields[key] = Array.isArray(field) ? field.map(String) : [String(field)];
        }
      } else if (value !== undefined) {
        payload.value = String(value);
      }
      flushPending(); // the handler sees the latest input
      busy(element, +1);
      queue.push({ element, payload, revisions: [], uploads: [], done: (ok) => {
        if (ok) resolve();
        else reject(new Error("jasx: the event failed"));
      } });
      pump();
    });
  }

  function startBrowserCode() {
    if (!modulesBase) return;
    bindBrowserCode(document);
    observer = new MutationObserver((records) => {
      for (const record of records) {
        if (record.type === "attributes") renderIfChanged(record.target);
        for (const added of record.addedNodes || []) bindBrowserCode(added);
      }
      cleanUpRemoved();
    });
    observer.observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ["data-jx-js-render"] });
  }

  function stopBrowserCode() {
    if (observer) observer.disconnect();
    for (const element of Array.from(cleanups.keys())) {
      for (const type of Array.from(cleanups.get(element).keys())) runCleanup(element, type);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The stream (§6, §6.2): one connection for every tab of the application
  // ---------------------------------------------------------------------------------------------
  //
  // Over HTTP/1.1 a browser opens only about six connections per server, and each open stream holds one.
  // So the tabs share one stream: the tab holding a Web Lock is the leader. It opens the stream for every
  // tab's page (stream?pages=A:12,B:7) and passes each frame on over a BroadcastChannel; the other tabs
  // tell it their page and version. When the leader closes, the lock passes to another tab, which asks
  // every tab for its version and reconnects, and the server catches each page up (§6.1).
  //
  // Without Web Locks or BroadcastChannel (an old browser, or a page served over plain http from another
  // host: Web Locks needs a secure context), each tab leads itself alone, and hidden tabs release their
  // stream instead.

  const shared = Boolean(navigator.locks) && typeof BroadcastChannel === "function";
  const channel = shared ? new BroadcastChannel("jasx-stream:" + base) : null;
  /** Whether this tab holds the stream: the leader, or a tab that can't share. */
  let leading = !shared;
  /** Releases the Web Lock this tab holds as leader (a navigation hands it to the next page's client). */
  let releaseLock = null;
  /** The other tabs' pages the leader streams: page id -> {since, seen}. */
  const members = new Map();

  /** How long the leader gathers tabs joining (a roll call's answers) before it reconnects once. */
  const JOIN_DELAY = 50;
  /** How often the leader asks every tab whether it is still there; a tab silent for 2.5 rounds is dropped. */
  const ROLL_CALL_INTERVAL = 30000;

  let source = null;
  let retries = 0;
  let retryTimer = null;
  let joinTimer = null;

  // Opens the stream, telling the server the last version applied for each page, so it first sends what
  // was missed (§6.1). The client reconnects by itself rather than letting EventSource do it, because each
  // reconnection must carry the current versions.
  function connect() {
    if (!leading || asleep) return; // a hidden tab catches up when it is shown again
    clearTimeout(retryTimer);
    clearTimeout(joinTimer);
    retryTimer = joinTimer = null;
    if (source) source.close();
    const left = leaving.length ? "&leave=" + leaving.join(",") : "";
    const stream = new EventSource(base + "/stream?pages=" + streamedPages() + left);
    source = stream;
    stream.onopen = () => {
      retries = 0;
      leaving.length = 0; // the server has discarded them
    };
    stream.onmessage = (message) => onFrame(JSON.parse(message.data));
    stream.onerror = () => {
      if (source !== stream) return;
      stream.close();
      source = null;
      retries++;
      retryTimer = setTimeout(connect, Math.min(10000, 500 * 2 ** Math.min(retries, 5)));
    };
  }

  // This tab's page first, then the other tabs': id:version,id:version.
  function streamedPages() {
    const pages = [[pageId, applied], ...Array.from(members, ([page, member]) => [page, member.since])];
    return pages.map(([page, since]) => encodeURIComponent(page) + ":" + since).join(",");
  }

  // Tabs join in bursts (a roll call is answered by every tab), so the leader waits a moment and reconnects
  // once. While it waits to retry a failed stream, the retry carries the new pages.
  function reconnectSoon() {
    if (retryTimer !== null || joinTimer !== null) return;
    joinTimer = setTimeout(() => { joinTimer = null; connect(); }, JOIN_DELAY);
  }

  // A frame names the page its batch is for (§8.1): this tab's own is applied here, and the others are
  // passed on to their tabs.
  function onFrame(frame) {
    if (frame.page === pageId) {
      receive(frame.batch);
      return;
    }
    const member = members.get(frame.page);
    if (frame.batch.reload) {
      members.delete(frame.page); // the server no longer streams it: its tab reloads and joins afresh
    } else if (member && frame.batch.version > member.since) {
      member.since = frame.batch.version; // a lower version would only resend what the tab has
    }
    channel.postMessage({ type: "frame", page: frame.page, batch: frame.batch });
  }

  // Asks the server again for everything after the last version applied: the leader reconnects, another
  // tab asks the leader to.
  function resync() {
    if (leading) connect();
    else announce(true);
  }

  // Tells the leader this tab's page and version. `resync` asks it to reconnect even if it knows the page.
  function announce(resync) {
    // A copy: if no other tab leads (this one will), its own first stream request still names them.
    channel.postMessage({ type: "join", page: pageId, since: applied, resync, left: leaving.slice() });
  }

  function onMessage(message) {
    switch (message.type) {
      case "frame":
        if (message.page === pageId) receive(message.batch);
        break;
      case "rollcall":
        announce(false);
        break;
      case "join":
        if (leading) join(message);
        break;
      case "leave":
        if (leading && members.delete(message.page)) reconnectSoon();
        break;
    }
  }

  function join(message) {
    for (const page of message.left || []) leaving.push(page); // named in the reconnection join causes
    const isNew = !members.has(message.page);
    members.set(message.page, { since: message.since, seen: Date.now() });
    if (isNew || message.resync) reconnectSoon();
  }

  // The lock passed to this tab: it streams for every tab from now on.
  function lead() {
    leading = true;
    channel.postMessage({ type: "rollcall" }); // every tab answers with its page and version
    setInterval(rollCall, ROLL_CALL_INTERVAL);
    reconnectSoon();
  }

  // A tab that went away without saying so (it crashed, or was frozen) stops being streamed, so the
  // server can discard its page. It joins again if it comes back.
  function rollCall() {
    const silentSince = Date.now() - 2.5 * ROLL_CALL_INTERVAL;
    let dropped = false;
    for (const [page, member] of members) {
      if (member.seen < silentSince) {
        members.delete(page);
        dropped = true;
      }
    }
    if (dropped) reconnectSoon();
    channel.postMessage({ type: "rollcall" });
  }

  if (shared) {
    channel.onmessage = (message) => onMessage(message.data);
    // Every tab asks for the lock; the first holds it until it closes, and the others wait their turn.
    navigator.locks.request("jasx-stream:" + base, () => {
      if (stopped) return undefined; // granted after a navigation stopped this client: let it go at once
      lead();
      return new Promise((resolve) => { releaseLock = resolve; }); // held until the tab closes or navigates
    });
    announce(false); // to a leader that is already there
    listen(window, "pagehide", () => {
      if (!leading) channel.postMessage({ type: "leave", page: pageId });
    });
    listen(window, "pageshow", (event) => {
      if (event.persisted && !leading) announce(true); // back from the back-forward cache
    });
  }

  // Hidden tabs (§6), when tabs can't share a stream: a tab that stays hidden closes its stream and pings
  // instead, which keeps its page alive on the server. Shown again, it reconnects and catches up. A quick
  // switch between tabs keeps the stream. With a shared stream, a tab shown again only checks that the
  // leader still streams its page (a frozen tab may have been dropped).
  const HIDDEN_GRACE = Number(script.dataset.jxHiddenGrace || 10000);
  const PING_INTERVAL = 60000;
  let asleep = false;
  let hiddenTimer = null;
  let pingTimer = null;

  listen(document, "visibilitychange", () => {
    const visible = document.visibilityState !== "hidden";
    if (shared) {
      if (visible && !leading) announce(false);
    } else if (!visible) {
      clearTimeout(hiddenTimer);
      hiddenTimer = setTimeout(fallAsleep, HIDDEN_GRACE);
    } else {
      clearTimeout(hiddenTimer);
      hiddenTimer = null;
      if (asleep) wakeUp();
    }
  });

  function fallAsleep() {
    hiddenTimer = null;
    asleep = true;
    clearTimeout(retryTimer);
    if (source) {
      source.close();
      source = null;
    }
    ping();
    pingTimer = setInterval(ping, PING_INTERVAL);
  }

  function wakeUp() {
    asleep = false;
    clearInterval(pingTimer);
    pingTimer = null;
    connect(); // with the last version applied: what was pushed meanwhile arrives as one batch
  }

  async function ping() {
    try {
      const response = await fetch(base + "/ping" + query, { method: "POST" });
      // A page the server discarded: the stream tells the client to reload when the tab is shown.
      if (response.status === 410) clearInterval(pingTimer);
    } catch (error) {
      // Offline for now: the next ping tries again.
    }
  }

  // Loads a fresh page, at most once every 10 seconds, so a broken server can't cause a reload loop.
  function reload() {
    const last = Number(sessionStorage.getItem("jx-reload") || 0);
    if (Date.now() - last < 10000) {
      console.error("jasx: this page is no longer known to the server; reload it to continue");
      return;
    }
    sessionStorage.setItem("jx-reload", String(Date.now()));
    saveForms();
    location.reload();
  }

  // ---------------------------------------------------------------------------------------------
  // Form recovery (§6.1): what the user typed survives a reload of a discarded page
  // ---------------------------------------------------------------------------------------------

  const FORMS_KEY = "jx-forms:" + location.pathname + location.search;
  /** Saved values older than this are not restored. */
  const FORMS_MAX_AGE = 5 * 60 * 1000;
  /** Fields whose values are never saved: secrets, values the page sets itself, and files. */
  const NOT_RECOVERED = new Set(["password", "hidden", "file", "submit", "button", "reset", "image"]);

  // Each recoverable field, with a key that finds it again in the reloaded page: its form (by id, or by its
  // position among the page's forms), its name, and its position among the fields of that name.
  function recoverableFields() {
    const forms = Array.from(document.forms);
    const seen = new Map();
    const fields = [];
    for (const field of document.querySelectorAll("input[name], select[name], textarea[name]")) {
      if (field.tagName === "INPUT" && NOT_RECOVERED.has(field.type)) continue;
      const form = field.form ? (field.form.id || "form" + forms.indexOf(field.form)) : "page";
      const base = form + "|" + field.name;
      const index = seen.get(base) || 0;
      seen.set(base, index + 1);
      fields.push({ key: base + "|" + index, field });
    }
    return fields;
  }

  function saveForms() {
    const values = {};
    for (const { key, field } of recoverableFields()) {
      if (field.type === "checkbox" || field.type === "radio") values[key] = { checked: field.checked };
      else if (field.tagName === "SELECT") values[key] = { selected: Array.from(field.selectedOptions, (o) => o.value) };
      else values[key] = { value: field.value };
    }
    try {
      sessionStorage.setItem(FORMS_KEY, JSON.stringify({ at: Date.now(), values }));
    } catch (error) {
      // Storage full or disabled: the reload goes on without recovery.
    }
  }

  // Puts saved values back into the matching fields of the new page, and fires input and change for each
  // field that changed, so that the page's handlers (and its server state) catch up with what the user had.
  function restoreForms() {
    const saved = sessionStorage.getItem(FORMS_KEY);
    if (!saved) return;
    sessionStorage.removeItem(FORMS_KEY);
    let data;
    try { data = JSON.parse(saved); } catch (error) { return; }
    if (!data || Date.now() - data.at > FORMS_MAX_AGE) return;
    for (const { key, field } of recoverableFields()) {
      const value = data.values[key];
      if (!value || !restoreField(field, value)) continue;
      field.dispatchEvent(new Event("input", { bubbles: true }));
      field.dispatchEvent(new Event("change", { bubbles: true }));
    }
  }

  /** Sets a field to a saved value; whether it changed. */
  function restoreField(field, value) {
    if ("checked" in value && (field.type === "checkbox" || field.type === "radio")) {
      if (field.checked === value.checked) return false;
      field.checked = value.checked;
      return true;
    }
    if ("selected" in value && field.tagName === "SELECT") {
      const before = Array.from(field.selectedOptions, (o) => o.value).join("\n");
      for (const option of field.options) option.selected = value.selected.includes(option.value);
      return before !== value.selected.join("\n");
    }
    if ("value" in value && field.value !== value.value) {
      field.value = value.value;
      return true;
    }
    return false;
  }

  // ---------------------------------------------------------------------------------------------
  // Navigation (§6.4): a link to another page of the application, without reloading the browser's page
  // ---------------------------------------------------------------------------------------------

  /** The path and query shown now: a popstate that keeps them is a change of hash, not of page. */
  let shownPath = location.pathname + location.search;

  listen(document, "click", (event) => {
    const link = navigableLink(event);
    if (!link) return;
    event.preventDefault();
    navigate(link.href, true);
  });

  listen(window, "popstate", () => {
    if (location.pathname + location.search !== shownPath) navigate(location.href, false);
  });

  // The link a click follows, if it is one this client can load itself: a plain click on a link to another
  // page of the same origin, not to the runtime's own requests, not opening a new window or downloading, and
  // not inside an element marked data-jx-reload.
  function navigableLink(event) {
    const plainClick = event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey;
    if (event.defaultPrevented || !plainClick || !event.target.closest) return null;
    const link = event.target.closest("a[href]");
    if (!link || link.hasAttribute("download") || link.closest("[data-jx-reload]")) return null;
    const target = link.getAttribute("target");
    if (target && target !== "_self") return null;
    const url = new URL(link.href, location.href);
    if (url.origin !== location.origin || url.pathname.startsWith(base + "/")) return null;
    const samePage = url.pathname === location.pathname && url.search === location.search;
    if (samePage && url.hash) return null; // an anchor on this page: the browser scrolls to it
    return link;
  }

  // Goes to `url` as a click on a link to it would (§8.4): in place for a page of the application, otherwise
  // the ordinary way.
  function follow(url) {
    const target = new URL(url, location.href);
    const inApplication = target.origin === location.origin && !target.pathname.startsWith(base + "/");
    if (inApplication) navigate(target.href, true);
    else location.assign(target.href);
  }

  // Fetches the page at `url` and shows it in place of this one: its title, new style sheets and body, and a
  // client for it, started after this one stops. Anything unexpected (not HTML, not a JASX page, a network
  // error) loads it the ordinary way instead.
  async function navigate(url, push) {
    let next;
    try {
      const response = await fetch(url, { headers: { Accept: "text/html" } });
      const type = response.headers.get("Content-Type") || "";
      if (!response.ok || !type.includes("text/html")) throw new Error("not a page");
      next = new DOMParser().parseFromString(await response.text(), "text/html");
    } catch (error) {
      location.assign(url);
      return;
    }
    const nextScript = next.querySelector("script[data-jx-page]");
    if (!nextScript || !next.body) {
      location.assign(url);
      return;
    }
    stop();
    if (push) history.pushState({}, "", url); // before the next client starts: it reads the location
    document.title = next.title;
    addStyles(next.head);
    const client = document.createElement("script"); // a script element made here runs, unlike a parsed one
    for (const attribute of nextScript.attributes) client.setAttribute(attribute.name, attribute.value);
    client.setAttribute("data-jx-replaces", pageId); // its stream tells the server this page is gone
    nextScript.remove();
    document.body.replaceWith(document.importNode(next.body, true));
    document.body.appendChild(client);
    scrollToTarget(new URL(url, location.href).hash);
  }

  // The next page's style elements that this page doesn't have yet.
  function addStyles(head) {
    const present = new Set(Array.from(document.head.querySelectorAll("style"), (style) => style.textContent));
    for (const style of head.querySelectorAll("style")) {
      if (!present.has(style.textContent)) document.head.appendChild(document.importNode(style, true));
    }
  }

  function scrollToTarget(hash) {
    const target = hash ? document.getElementById(decodeURIComponent(hash.substring(1))) : null;
    if (target) target.scrollIntoView();
    else window.scrollTo(0, 0);
  }

  // Stops this client for good: its listeners and timers, its stream (or its part in the shared one), its
  // queued events, which were for this page, and every cleanup browser code left (§7.1).
  function stop() {
    stopped = true;
    stopBrowserCode();
    for (const [target, type, listener, options] of registrations) target.removeEventListener(type, listener, options);
    for (const id of timers) {
      window.clearTimeout(id);
      window.clearInterval(id);
    }
    if (source) source.close();
    if (channel) {
      if (!leading) channel.postMessage({ type: "leave", page: pageId });
      channel.close();
    }
    if (releaseLock) releaseLock();
    queue.length = 0;
    pending.clear();
  }

  rememberRendered(document.documentElement); // the page as the server rendered it
  indexHoles(document);
  listenFor(document);
  startBrowserCode();
  restoreForms();
  connect(); // a tab that can't share streams at once; a shared stream opens when its leader is chosen
})();
