package jasx.web.test;

import jasx.web.WebApp;
import jasx.web.test.Dom.Comment;
import jasx.web.test.Dom.Element;
import jasx.web.test.Dom.Node;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * A web-target page under test, without a browser (targets/web.md):
 *
 * <pre>
 *   try (TestPage page = TestPage.render(() -&gt; &lt;Counter /&gt;)) {
 *       page.click("button.plus");
 *       assertEquals("1", page.text("output"));
 *
 *       page.submit("form", Map.of("title", "Milk"));
 *       assertEquals(List.of("Milk"), page.findAll("ul > li").stream().map(TestElement::text).toList());
 *   }
 * </pre>
 *
 * <p>It works as the browser client does, through {@link WebApp}'s public API: it renders the page with a session
 * cookie of its own (targets/web.md §6.3), sends each event as the client would describe it (§8.3), and applies the
 * batches the server answers or pushes (§8.4) to its own copy of the page's HTML. State changed from elsewhere (a
 * store, another page, a background job) arrives on the page's stream: {@link #await} waits for it.
 *
 * <p>Differences from a browser: an event is sent at once (no debounce, so a test needn't wait), a hole's new content
 * replaces the old instead of being merged into it, and nothing of CSS, layout or JavaScript is run.
 *
 * <p>Close the page when the test is done; {@link #render(Supplier)} also closes the {@link WebApp} it made.
 */
public final class TestPage implements AutoCloseable {
    /** How long {@link #await} waits by default. */
    public static final Duration DEFAULT_WAIT = Duration.ofSeconds(5);

    /** Events that reach only their own element (they don't bubble). */
    private static final Set<String> NON_BUBBLING = Set.of("focus", "blur", "mouseenter", "mouseleave", "load", "scroll");

    private final WebApp app;
    private final boolean ownsApp;
    private final String session;
    private final String pageId;
    private final Element document;
    private final Thread stream;

    private long applied;
    private final Map<Long, Map<String, Object>> waiting = new TreeMap<>();
    private int lastSeq;
    private final Map<Integer, Element> formsOfEvents = new HashMap<>();
    private String navigatedTo;
    private boolean gone;

    private TestPage(WebApp app, boolean ownsApp, Supplier<?> root) {
        this.app = app;
        this.ownsApp = ownsApp;
        WebApp.Response response = app.render(request(Map.of(), null), root);
        if (response.status() != 200) {
            throw new AssertionError("the page answered " + response.status());
        }
        this.session = sessionOf(response.headers().get("Set-Cookie"));
        this.document = HtmlParser.parse(response.body());
        Element script = Selector.parse("script[data-jx-page]").select(document).stream().findFirst()
                .orElseThrow(() -> new AssertionError("the page has no client script: " + response.body()));
        this.pageId = script.attribute("data-jx-page");
        this.stream = Thread.ofVirtual().name("jasx-test-stream").start(this::streamPushes);
    }

    /** Renders {@code root} as a new page of a {@link WebApp} of its own, closed with the page. */
    public static TestPage render(Supplier<?> root) {
        return new TestPage(new WebApp(), true, root);
    }

    /** Renders {@code root} as a new page of {@code app}: an application's own, such as a Spring bean. */
    public static TestPage render(WebApp app, Supplier<?> root) {
        return new TestPage(app, false, root);
    }

    // ---------------------------------------------------------------------------------------------
    // Reading the page
    // ---------------------------------------------------------------------------------------------

    /** The page's HTML as it is now. */
    public synchronized String html() {
        return Dom.innerHtml(document);
    }

    /** The first element that matches {@code selector}; fails if there is none. */
    public TestElement find(String selector) {
        List<TestElement> found = findAll(selector);
        if (found.isEmpty()) {
            throw new AssertionError("no element matches \"" + selector + "\" in the page:\n" + html());
        }
        return found.get(0);
    }

    /**
     * The first element that matches {@code selector} and whose text contains {@code text}, such as
     * {@code find("button", "Delete")}; fails if there is none.
     */
    public TestElement find(String selector, String text) {
        return findAll(selector).stream().filter(element -> element.text().contains(text)).findFirst()
                .orElseThrow(() -> new AssertionError("no element matches \"" + selector + "\" with the text \"" + text
                        + "\" in the page:\n" + html()));
    }

    /** Every element that matches {@code selector}, in document order. */
    public synchronized List<TestElement> findAll(String selector) {
        return Selector.parse(selector).select(document).stream().map(element -> new TestElement(this, element)).toList();
    }

    /** Whether an element matches {@code selector}. */
    public boolean has(String selector) {
        return !findAll(selector).isEmpty();
    }

    /** The text of the first element that matches {@code selector}. */
    public String text(String selector) {
        return find(selector).text();
    }

    /** Where a handler sent the browser with {@code Web.navigate}, or null. */
    public synchronized String navigatedTo() {
        return navigatedTo;
    }

    /**
     * Runs {@code assertion} until it passes, for what the server pushes from elsewhere (another page, a store, a
     * background job): {@code page.await(() -> assertEquals("2", page.text("output")))}. Fails with the assertion's
     * own failure after {@link #DEFAULT_WAIT}.
     */
    public void await(Runnable assertion) {
        long deadline = System.nanoTime() + DEFAULT_WAIT.toNanos();
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError failure) {
                if (System.nanoTime() > deadline) {
                    throw failure;
                }
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting", e);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Acting on the page, as a user in a browser
    // ---------------------------------------------------------------------------------------------

    /**
     * Clicks the first element that matches {@code selector}. A checkbox or radio button changes and fires
     * {@code click}, {@code input} and {@code change}; a submit button also submits its form. Fails if the click does
     * nothing: no handler on the element or around it, and no form to submit.
     */
    public TestPage click(String selector) {
        return click(target(selector), selector);
    }

    /** Clicks the first element that matches {@code selector} and whose text contains {@code text}: {@code click("button", "Save")}. */
    public TestPage click(String selector, String text) {
        return click(find(selector, text).node(), selector + " with \"" + text + "\"");
    }

    private TestPage click(Element target, String selector) {
        if (target.hasAttribute("disabled")) {
            throw new AssertionError("\"" + selector + "\" is disabled: " + Dom.html(target));
        }
        if (Fields.isCheckable(target)) {
            check(target, Fields.type(target).equals("radio") || !Fields.checked(target));
            dispatch(target, "click", mouse());
            dispatch(target, "input", Map.of());
            dispatch(target, "change", Map.of());
            return this;
        }
        boolean handled = dispatch(target, "click", mouse());
        Element form = Fields.isSubmitButton(target) ? formOf(target) : null;
        if (form != null) {
            handled |= dispatch(form, "submit", Map.of(), target);
        }
        if (!handled) {
            throw new AssertionError("clicking \"" + selector + "\" does nothing: no click handler on it or around it, "
                    + "and no form to submit: " + Dom.html(target));
        }
        return this;
    }

    /** Types {@code text} into a field (replacing what it had), which fires {@code input} as typing does. */
    public TestPage type(String selector, String text) {
        Element field = field(selector);
        synchronized (this) {
            field.value = text;
        }
        dispatch(field, "input", Map.of());
        return this;
    }

    /**
     * Sets a field's value as a user's choice does, and fires {@code input} and {@code change}: for a select, the
     * option with that value; for another field, its text (as if the user typed it and left the field).
     */
    public TestPage change(String selector, String value) {
        Element field = field(selector);
        synchronized (this) {
            if (field.tag.equals("select")) {
                Fields.select(field, value);
            } else {
                field.value = value;
            }
        }
        dispatch(field, "input", Map.of());
        dispatch(field, "change", Map.of());
        return this;
    }

    /** Submits the form that matches {@code selector} (or that contains the element that does), with its fields as they are. */
    public TestPage submit(String selector) {
        return submit(selector, Map.of());
    }

    /**
     * Fills the named fields of the form, then submits it: {@code page.submit("form", Map.of("title", "Milk"))}. A
     * checkbox is checked by {@code "true"} or {@code "on"}, a select chooses the option with that value.
     */
    public TestPage submit(String selector, Map<String, String> values) {
        Element target = target(selector);
        Element form = target.tag.equals("form") ? target : formOf(target);
        if (form == null) {
            throw new AssertionError("\"" + selector + "\" is not a form or in one: " + Dom.html(target));
        }
        synchronized (this) {
            values.forEach((name, value) -> fill(form, name, value));
        }
        if (!dispatch(form, "submit", Map.of())) {
            throw new AssertionError("the form has no onsubmit handler: " + Dom.html(form));
        }
        return this;
    }

    /** Presses a key in the element that matches {@code selector}: a {@code keydown} event with that key. */
    public TestPage keydown(String selector, String key) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("key", key);
        details.putAll(modifiers());
        dispatch(target(selector), "keydown", details);
        return this;
    }

    /** Fires an event of any type on the element that matches {@code selector}: {@code fire("input", "blur")}. */
    public TestPage fire(String selector, String type) {
        if (!dispatch(target(selector), type, Map.of())) {
            throw new AssertionError("no " + type + " handler on \"" + selector + "\" or around it");
        }
        return this;
    }

    /** Closes the page's stream, and the WebApp if this page made it. */
    @Override
    public void close() {
        stream.interrupt();
        if (ownsApp) {
            app.close();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Events (targets/web.md §7, §8.3)
    // ---------------------------------------------------------------------------------------------

    private boolean dispatch(Element target, String type, Map<String, Object> details) {
        return dispatch(target, type, details, null);
    }

    /**
     * Sends the event to the nearest handler for its type, as the client does: the target's, or for an event that
     * bubbles, an ancestor's. Returns false if there is none.
     */
    private boolean dispatch(Element target, String type, Map<String, Object> details, Element submitter) {
        String payload;
        synchronized (this) {
            if (gone) {
                throw new AssertionError("the page is gone (the server discarded it, or asked the browser to reload)");
            }
            Element element = target;
            Integer handler = null;
            while (element != null && !element.tag.equals("#root")) {
                handler = handlerOf(element, type);
                if (handler != null || NON_BUBBLING.contains(type)) {
                    break;
                }
                element = element.parent;
            }
            if (handler == null) {
                return false;
            }
            payload = describe(element, handler, type, details, submitter);
        }
        send(payload);
        return true;
    }

    /** The handler for {@code type} in the element's {@code data-jx-on} (§8.2), or null. */
    private static Integer handlerOf(Element element, String type) {
        String spec = element.attribute("data-jx-on");
        if (spec == null) {
            return null;
        }
        for (String entry : spec.split(" ")) {
            String[] parts = entry.split(":");
            if (parts[0].equals(type)) {
                return Integer.valueOf(parts[1]);
            }
        }
        return null;
    }

    /** The event as the client describes it (targets/web.md §8.3). */
    private String describe(Element element, int handler, String type, Map<String, Object> details, Element submitter) {
        int seq = ++lastSeq;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("handler", handler);
        payload.put("seq", seq);
        payload.put("type", type);
        payload.putAll(details);
        if (Fields.isField(element)) {
            payload.put("value", Fields.value(element));
            payload.put("checked", Fields.checked(element));
            if (element.tag.equals("select") && element.hasAttribute("multiple")) {
                payload.put("values", Fields.selectedValues(element));
            }
        }
        Element form = element.tag.equals("form") ? element : (type.equals("submit") ? formOf(element) : null);
        if (form != null) {
            payload.put("fields", Fields.submitted(form, submitter));
        }
        Element formToReset = form != null ? form : formOf(element);
        if (formToReset != null) {
            formsOfEvents.put(seq, formToReset);
        }
        return JsonWriter.write(payload);
    }

    private void send(String payload) {
        WebApp.Response response = app.event(request(Map.of("page", pageId), session), payload);
        switch (response.status()) {
            case 200 -> receive(JsonReader.object(JsonReader.read(response.body())));
            case 204 -> { }
            case 410 -> {
                synchronized (this) {
                    gone = true;
                }
                throw new AssertionError("the page is gone (the server answered 410)");
            }
            default -> throw new AssertionError("the event failed with status " + response.status() + ": " + payload);
        }
    }

    private static Map<String, Object> mouse() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("button", 0);
        details.put("x", 0);
        details.put("y", 0);
        details.putAll(modifiers());
        return details;
    }

    private static Map<String, Object> modifiers() {
        Map<String, Object> modifiers = new LinkedHashMap<>();
        modifiers.put("ctrl", false);
        modifiers.put("shift", false);
        modifiers.put("alt", false);
        modifiers.put("meta", false);
        return modifiers;
    }

    // ---------------------------------------------------------------------------------------------
    // Batches and patches (targets/web.md §8.4), from answers and from the stream
    // ---------------------------------------------------------------------------------------------

    /** Applies batches in version order, as the client does; a batch that comes early waits for the one before. */
    private synchronized void receive(Map<String, Object> batch) {
        if (Boolean.TRUE.equals(batch.get("reload"))) {
            gone = true;
            return;
        }
        long version = ((Number) batch.get("version")).longValue();
        long from = batch.containsKey("from") ? ((Number) batch.get("from")).longValue() : version;
        if (version <= applied) {
            return;
        }
        waiting.put(from, batch);
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (var entry : List.copyOf(waiting.entrySet())) {
                long batchVersion = ((Number) entry.getValue().get("version")).longValue();
                if (batchVersion <= applied) {
                    waiting.remove(entry.getKey());
                } else if (entry.getKey() <= applied + 1) {
                    waiting.remove(entry.getKey());
                    applied = batchVersion;
                    JsonReader.list(entry.getValue().get("patches")).forEach(patch -> apply(JsonReader.object(patch)));
                    progressed = true;
                }
            }
        }
    }

    private void apply(Map<String, Object> patch) {
        switch (String.valueOf(patch.get("op"))) {
            case "html" -> replaceHole(((Number) patch.get("id")).longValue(), (String) patch.get("html"));
            case "list" -> arrangeItems(((Number) patch.get("id")).longValue(), JsonReader.list(patch.get("items")));
            case "attr" -> setAttribute(((Number) patch.get("id")).longValue(), (String) patch.get("name"), patch.get("value"));
            case "navigate" -> navigatedTo = (String) patch.get("url");
            case "reset" -> resetForm(((Number) patch.get("event")).intValue());
            default -> { } // css: no styles here
        }
    }

    /** An {@code html} patch: the hole's content is replaced. */
    private void replaceHole(long id, String html) {
        Comment start = findComment("jx:" + id);
        if (start == null) {
            return; // inside content replaced meanwhile
        }
        Element parent = start.parent;
        int from = parent.children.indexOf(start) + 1;
        int to = indexOfComment(parent, "/jx:" + id, from);
        List<Node> content = HtmlParser.parse(html).children;
        replaceRange(parent, from, to, content);
    }

    /** A {@code list} patch: kept items in their new order, and new items between their own markers. */
    private void arrangeItems(long id, List<Object> entries) {
        Comment start = findComment("jx:" + id);
        if (start == null) {
            return;
        }
        Element parent = start.parent;
        int from = parent.children.indexOf(start) + 1;
        int to = indexOfComment(parent, "/jx:" + id, from);
        Map<Long, List<Node>> items = itemsBetween(parent, from, to);
        List<Node> arranged = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof Number kept) {
                List<Node> item = items.get(kept.longValue());
                if (item == null) {
                    throw new AssertionError("the server kept item " + kept + ", which the page doesn't have");
                }
                arranged.addAll(item);
            } else {
                Map<String, Object> created = JsonReader.object(entry);
                long itemId = ((Number) created.get("id")).longValue();
                arranged.add(new Comment("jx:" + itemId));
                arranged.addAll(HtmlParser.parse((String) created.get("html")).children);
                arranged.add(new Comment("/jx:" + itemId));
            }
        }
        replaceRange(parent, from, to, arranged);
    }

    /** The items of a keyed hole (nodes from an item's start marker to its end marker), by id. */
    private static Map<Long, List<Node>> itemsBetween(Element parent, int from, int to) {
        Map<Long, List<Node>> items = new HashMap<>();
        for (int i = from; i < to; i++) {
            if (parent.children.get(i) instanceof Comment marker && marker.data.startsWith("jx:")) {
                long itemId = Long.parseLong(marker.data.substring(3));
                int end = indexOfComment(parent, "/jx:" + itemId, i + 1);
                items.put(itemId, new ArrayList<>(parent.children.subList(i, Math.min(end + 1, to))));
                i = end;
            }
        }
        return items;
    }

    private static void replaceRange(Element parent, int from, int to, List<Node> nodes) {
        parent.children.subList(from, to).clear();
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            node.parent = parent;
            parent.children.add(from + i, node);
        }
    }

    /** An {@code attr} patch; a field then shows what the server set, as the client does without unsent typing. */
    private void setAttribute(long id, String name, Object value) {
        List<Element> matches = Selector.parse("[data-jx-id=\"" + id + "\"]").select(document);
        if (matches.isEmpty()) {
            return;
        }
        Element element = matches.get(0);
        boolean absent = value == null || Boolean.FALSE.equals(value);
        if (absent) {
            element.attributes.remove(name);
        } else {
            element.attributes.put(name, Boolean.TRUE.equals(value) ? "" : String.valueOf(value));
        }
        switch (name) {
            case "value" -> element.value = null;
            case "checked" -> element.checked = null;
            case "selected" -> element.selected = null;
            default -> { }
        }
    }

    private void resetForm(int event) {
        Element form = formsOfEvents.get(event);
        if (form != null) {
            Dom.eachElement(form, element -> {
                if (Fields.isField(element)) {
                    Fields.reset(element);
                }
            });
        }
    }

    private Comment findComment(String data) {
        Comment[] found = {null};
        Dom.eachElement(document, element -> {
            for (Node child : element.children) {
                if (found[0] == null && child instanceof Comment comment && comment.data.equals(data)) {
                    found[0] = comment;
                }
            }
        });
        return found[0];
    }

    private static int indexOfComment(Element parent, String data, int from) {
        for (int i = from; i < parent.children.size(); i++) {
            if (parent.children.get(i) instanceof Comment comment && comment.data.equals(data)) {
                return i;
            }
        }
        throw new AssertionError("the end marker <!--" + data + "--> is missing");
    }

    /** Applies what the server pushes for this page until the stream ends or the page is closed (§6.1, §6.2). */
    private void streamPushes() {
        try {
            app.stream(request(Map.of("pages", pageId + ":0"), session), new WebApp.EventStream() {
                @Override
                public void open() {}

                @Override
                public void send(String frame) {
                    if (!frame.startsWith("data: ")) {
                        return; // a comment: the greeting or a heartbeat
                    }
                    Map<String, Object> message = JsonReader.object(JsonReader.read(frame.substring(6).strip()));
                    if (pageId.equals(message.get("page"))) {
                        receive(JsonReader.object(message.get("batch")));
                    }
                }
            });
        } catch (java.io.IOException e) {
            // the stream ended
        }
    }

    // ---------------------------------------------------------------------------------------------

    private synchronized Element target(String selector) {
        return find(selector).node();
    }

    private Element field(String selector) {
        Element element = target(selector);
        if (!Fields.isField(element)) {
            throw new AssertionError("\"" + selector + "\" is not a form field: " + Dom.html(element));
        }
        return element;
    }

    private static Element formOf(Element element) {
        for (Element ancestor = element.parent; ancestor != null; ancestor = ancestor.parent) {
            if (ancestor.tag.equals("form")) {
                return ancestor;
            }
        }
        return null;
    }

    private void check(Element box, boolean checked) {
        synchronized (this) {
            if (Fields.type(box).equals("radio") && checked) {
                Element scope = formOf(box) != null ? formOf(box) : document;
                String name = box.attribute("name");
                Dom.eachElement(scope, other -> {
                    if (other != box && Fields.isCheckable(other) && name != null && name.equals(other.attribute("name"))) {
                        other.checked = false;
                    }
                });
            }
            box.checked = checked;
        }
    }

    /** Fills field {@code name} of the form as {@link #submit(String, Map)} describes. */
    private static void fill(Element form, String name, String value) {
        List<Element> fields = Selector.parse("[name=\"" + name + "\"]").select(form);
        if (fields.isEmpty()) {
            throw new AssertionError("the form has no field named \"" + name + "\": " + Dom.html(form));
        }
        Element field = fields.get(0);
        if (field.tag.equals("select")) {
            Fields.select(field, value);
        } else if (Fields.isCheckable(field)) {
            field.checked = value.equals("true") || value.equals("on");
        } else {
            field.value = value;
        }
    }

    /** What a request from this page's browser looks like to {@link WebApp}. */
    private static WebApp.Request request(Map<String, String> query, String session) {
        return new WebApp.Request() {
            @Override
            public String query(String name) {
                return query.get(name);
            }

            @Override
            public String header(String name) {
                return switch (name.toLowerCase(Locale.ROOT)) {
                    case "cookie" -> session == null ? null : "jasx_session=" + session;
                    case "content-type" -> "application/json";
                    case "sec-fetch-site" -> "same-origin";
                    default -> null;
                };
            }

            @Override
            public boolean secure() {
                return false;
            }
        };
    }

    private static String sessionOf(String setCookie) {
        if (setCookie == null || !setCookie.startsWith("jasx_session=")) {
            throw new AssertionError("the page set no session cookie");
        }
        int end = setCookie.indexOf(';');
        return setCookie.substring("jasx_session=".length(), end < 0 ? setCookie.length() : end);
    }
}
