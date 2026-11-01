package jasx.web;

import jasx.ui.Computation;
import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A dynamic child of a page (targets/web.md §3): {@code {expression}} or a statement child. It renders its
 * value between the markers {@code <!--jx:N-->} and {@code <!--/jx:N-->} (§8.2), and when a value it read
 * changes, its page re-runs it ({@link #update}).
 *
 * <p>It has two modes:
 *
 * <ul>
 *   <li><b>Plain.</b> The whole content is rendered again and sent as an {@code html} patch, which the browser
 *       merges into the page (§8.4).
 *   <li><b>Keyed list</b>, when every child of the value has a key, such as
 *       {@code {for (Todo t : todos) { <li key={t.id()}>...</li> }}} (§4.3). Each child is an <em>item</em>: a
 *       hole of its own, made from the keyed element's function. When the list re-runs, an item whose key and
 *       inputs are unchanged ({@link Captures#same}) is kept as it is, with its state, holes and handlers; only
 *       new or changed items are rendered. The browser gets a {@code list} patch: the order of the items, and
 *       the HTML of the new ones.
 * </ul>
 *
 * <p>An item is itself a {@code ChildHole} in plain mode: when a value its own code read changes, it renders
 * again and sends an {@code html} patch for itself.
 *
 * <p><b>Component instances</b> (§4.4). A component element is a function ({@code Web.component}), and each
 * instance is a {@code ChildHole} too, made from it by the renderer. A hole remembers the instances its last
 * run created; when it runs again, an instance whose call is the same code with equal captured values
 * ({@link Captures#same}) is kept ({@link #keptInstance}), with its state, holes and handlers, instead of
 * running the component again. Content refers to its holes rather than copying them ({@link Markup#hole}), so
 * a kept instance appears in the new content as it is now.
 */
final class ChildHole extends Computation {
    final int id;
    private final Page page;
    private final Supplier<?> value;
    /** The content as last sent to the browser, without this hole's markers. */
    private Markup markup;
    /** Its HTML, to skip patches that change nothing. */
    private String html;
    /** In keyed-list mode, the items in order; null in plain mode. */
    private List<Item> items;
    /** Whether its content is written between markers: it read a reactive value on its first run (§8.2). */
    private boolean hasMarkers;
    /** The component instances its last successful run created or kept, in the order it met them. */
    private List<ChildHole> instances = List.of();
    /** During a run: the instances it has created or kept so far; otherwise null. */
    private List<ChildHole> runInstances;
    /** During a run again: the previous run's instances not kept yet; null otherwise (and on a first run). */
    private List<ChildHole> keepable;

    /** A keyed item: its key, the function that made it (to compare inputs), and its own hole. */
    private record Item(Object key, Supplier<?> element, ChildHole hole) {}

    /**
     * What one run rendered. In keyed-list mode, {@code items} are the items and {@code entries} the list patch's
     * entries: the id of a kept item, or the hole of a new one. Both are null in plain mode.
     */
    private record Rendered(Markup markup, Set<StyleSheet> sheets, List<Item> items, List<Object> entries) {}

    ChildHole(Page page, int id, Supplier<?> value) {
        super(page);
        this.page = page;
        this.id = id;
        this.value = value;
    }

    /**
     * The first rendering, as part of {@code parent}, which then adds the hole to its content
     * ({@link Markup#hole}). A hole that read no reactive value (such as {@code {todo.title()}}) can never
     * change, so it needs no markers; holes inside its content still get theirs.
     */
    void firstRender(Renderer parent) {
        parent.useSheets(renderFirstTime().sheets());
    }

    /** Runs for the first time, and keeps what it rendered. */
    private Rendered renderFirstTime() {
        Rendered content = run(false, () -> track(this::renderContent));
        items = content.items();
        setContent(items != null ? itemsMarkup(hasSources()) : content.markup());
        hasMarkers = hasSources();
        return content;
    }

    /** Whether its content is written between its markers ({@code <!--jx:N-->}). */
    boolean hasMarkers() {
        return hasMarkers;
    }

    /**
     * Its content as it is now, for the markup of whatever contains it: between its markers if it has them or
     * {@code alwaysMarked}.
     */
    Markup currentMarkup(boolean alwaysMarked) {
        return alwaysMarked || hasMarkers ? marked(id, markup) : markup;
    }

    /**
     * Runs {@code body} as a run of this hole, keeping track of the component instances it creates and keeps:
     * on success they become the instances the next run can keep; on failure the previous ones stay.
     */
    private Rendered run(boolean again, Supplier<Rendered> body) {
        runInstances = new ArrayList<>();
        keepable = again ? new ArrayList<>(instances) : null;
        try {
            Rendered content = body.get();
            instances = List.copyOf(runInstances);
            return content;
        } finally {
            runInstances = null;
            keepable = null;
        }
    }

    /**
     * During a run, for the renderer: an instance the previous run created from the same code with equal
     * captured values (targets/web.md §4.4), now kept by this run; or null if there is none to keep.
     */
    ChildHole keptInstance(Supplier<?> call) {
        if (keepable == null) {
            return null;
        }
        for (ChildHole candidate : keepable) {
            if (!candidate.isDisposed() && Captures.same(candidate.value, call)) {
                keepable.remove(candidate);
                keep(candidate); // not disposed when this run succeeds (ui.md §2.5)
                runInstances.add(candidate);
                return candidate;
            }
        }
        return null;
    }

    /** During a run, for the renderer: an instance this run created, which the next run may keep. */
    void addInstance(ChildHole instance) {
        if (runInstances != null) {
            runInstances.add(instance);
        }
    }

    private void setContent(Markup content) {
        markup = content;
        html = content.toString();
    }

    /**
     * Renders again after a value it read changed, and records a patch if the browser's content must change. If
     * rendering throws, the hole keeps its previous content, items, handlers and subscriptions
     * (jasx-spec/ui.md §2.4), and the page reports the exception (targets/web.md §5).
     */
    @Override
    public void update() {
        List<Item> before = items;
        Rendered content = run(true, () -> retrack(this::renderContent));
        page.useSheets(content.sheets()); // a css patch for any style sheet the page didn't have (§9)

        boolean isKeyedNow = content.items() != null;
        boolean wasKeyed = before != null;
        items = content.items();
        if (isKeyedNow && wasKeyed) {
            setContent(itemsMarkup(true));
            if (!onlyKeptInTheSameOrder(content.entries(), before)) {
                patchList(content.entries());
            }
            return;
        }
        Markup newMarkup = isKeyedNow ? itemsMarkup(true) : content.markup();
        if (!newMarkup.toString().equals(html)) {
            setContent(newMarkup);
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("op", "html");
            patch.put("id", id);
            patch.putAll(page.content(newMarkup)); // "html", or a template's "t" and "v" (§8.4)
            page.addPatch(patch);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering a run
    // ---------------------------------------------------------------------------------------------

    /**
     * Evaluates the value and renders it. It runs tracked (the caller tracks): components called by the
     * expression, and lazy values such as streams consumed while rendering, subscribe this hole too.
     */
    private Rendered renderContent() {
        List<Object> children = flatten(value.get());
        List<WebElement.Keyed> keyed = allKeyed(children);
        // An empty list stays a keyed list if it was one, so that emptying a list and filling it again is cheap.
        boolean keyedList = keyed != null && (!keyed.isEmpty() || items != null);
        if (keyedList) {
            return renderItems(keyed);
        }
        Renderer content = new Renderer(page, false, this);
        content.child(children);
        return new Rendered(content.markup(), content.sheets(), null, null);
    }

    /**
     * The items of a keyed list (§4.3): each previous item with the same key and the same inputs is kept, without
     * running its code; every other one is created and rendered now.
     */
    private Rendered renderItems(List<WebElement.Keyed> keyed) {
        Map<Object, ArrayDeque<Item>> previousByKey = new HashMap<>();
        for (Item item : items != null ? items : List.<Item>of()) {
            previousByKey.computeIfAbsent(item.key(), k -> new ArrayDeque<>()).add(item);
        }

        List<Item> next = new ArrayList<>();
        List<Object> entries = new ArrayList<>();
        Set<StyleSheet> sheets = new LinkedHashSet<>();
        for (WebElement.Keyed child : keyed) {
            Item reusable = takeReusable(previousByKey, child);
            if (reusable != null) {
                keep(reusable.hole()); // not disposed when this run succeeds (ui.md §2.5)
                next.add(reusable);
                entries.add(reusable.hole().id);
                continue;
            }
            ChildHole hole = new ChildHole(page, page.nextId(), child.element());
            Rendered itemContent = hole.renderFirstTime();
            sheets.addAll(itemContent.sheets());
            next.add(new Item(child.key(), child.element(), hole));
            entries.add(hole); // its content is described when the list patch is made (patchList)
        }
        return new Rendered(null, sheets, next, entries);
    }

    /**
     * The list patch: each entry is a kept item's id, or a new item's id with its content. Describing the content
     * only now, not while rendering, keeps the page's first rendering from registering templates (§8.4).
     */
    private void patchList(List<Object> entries) {
        List<Object> described = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (entry instanceof ChildHole created) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", created.id);
                item.putAll(page.content(created.markup));
                described.add(item);
            } else {
                described.add(entry);
            }
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("op", "list");
        patch.put("id", id);
        patch.put("items", described);
        page.addPatch(patch);
    }

    /** The previous item with this child's key and the same inputs, taken so it isn't used twice; or null. */
    private static Item takeReusable(Map<Object, ArrayDeque<Item>> previousByKey, WebElement.Keyed child) {
        ArrayDeque<Item> sameKey = previousByKey.get(child.key());
        if (sameKey == null) {
            return null;
        }
        for (Item candidate : sameKey) {
            if (Captures.same(candidate.element(), child.element())) {
                sameKey.remove(candidate);
                return candidate;
            }
        }
        return null;
    }

    /**
     * The items' HTML, each between its own markers when it can move or change: always once the list can change
     * ({@code listCanChange}), otherwise only an item that can change by itself.
     */
    private Markup itemsMarkup(boolean listCanChange) {
        Markup out = new Markup();
        for (Item item : items) {
            out.hole(item.hole(), listCanChange);
        }
        return out;
    }

    /** Whether a run changed nothing in the browser: the same items, kept, in the same order. */
    private static boolean onlyKeptInTheSameOrder(List<Object> entries, List<Item> before) {
        if (entries.size() != before.size()) {
            return false;
        }
        for (int i = 0; i < entries.size(); i++) {
            boolean sameItem = entries.get(i) instanceof Integer keptId && keptId == before.get(i).hole().id;
            if (!sameItem) {
                return false;
            }
        }
        return true;
    }

    /** The content between the hole's markers; the id in a marker is a value, so that the shape doesn't change with it. */
    private static Markup marked(int id, Markup content) {
        String hole = String.valueOf(id);
        return new Markup().append("<!--jx:").value(hole).append("-->")
                .append(content)
                .append("<!--/jx:").value(hole).append("-->");
    }

    // ---------------------------------------------------------------------------------------------
    // The children of a value (semantics.md §3.4)
    // ---------------------------------------------------------------------------------------------

    /**
     * The children a value stands for, as the child value contract flattens them (semantics.md §3.4): nothing
     * for null, the elements of iterables, streams, arrays and optionals, the children of a fragment, and any
     * other value as itself. A stream can be read once, so this is done first and the list rendered after.
     */
    private static List<Object> flatten(Object value) {
        List<Object> children = new ArrayList<>();
        flattenInto(value, children);
        return children;
    }

    private static void flattenInto(Object value, List<Object> out) {
        switch (value) {
            case null -> {}
            case WebElement.Fragment fragment -> {
                for (Object child : fragment.children()) {
                    flattenInto(child, out);
                }
            }
            case Optional<?> optional -> optional.ifPresent(child -> flattenInto(child, out));
            case Iterable<?> iterable -> iterable.forEach(child -> flattenInto(child, out));
            case Stream<?> stream -> stream.forEachOrdered(child -> flattenInto(child, out));
            default -> {
                if (value.getClass().isArray()) {
                    for (int i = 0; i < Array.getLength(value); i++) {
                        flattenInto(Array.get(value, i), out);
                    }
                } else {
                    out.add(value);
                }
            }
        }
    }

    /** The children as keyed elements, if every one of them has a key; otherwise null. */
    private static List<WebElement.Keyed> allKeyed(List<Object> children) {
        List<WebElement.Keyed> keyed = new ArrayList<>(children.size());
        for (Object child : children) {
            if (!(child instanceof WebElement.Keyed keyedChild)) {
                return null;
            }
            keyed.add(keyedChild);
        }
        return keyed;
    }
}
