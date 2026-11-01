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
 */
final class ChildHole extends Computation {
    final int id;
    private final Page page;
    private final Supplier<?> value;
    /** The content as last sent to the browser, without this hole's markers, to skip patches that change nothing. */
    private String html;
    /** In keyed-list mode, the items in order; null in plain mode. */
    private List<Item> items;

    /** A keyed item: its key, the function that made it (to compare inputs), and its own hole. */
    private record Item(Object key, Supplier<?> element, ChildHole hole) {}

    /**
     * What one run rendered. In keyed-list mode, {@code items} are the items and {@code entries} the list patch's
     * entries: the id of a kept item, or the id and HTML of a new one. Both are null in plain mode.
     */
    private record Rendered(String html, Set<StyleSheet> sheets, List<Item> items, List<Object> entries) {}

    ChildHole(Page page, int id, Supplier<?> value) {
        super(page);
        this.page = page;
        this.id = id;
        this.value = value;
    }

    /**
     * The first rendering, as part of {@code parent}: the content between its markers. A hole that read no
     * reactive value (such as {@code {todo.title()}}) can never change, so it needs no markers; holes inside
     * its content still get theirs.
     */
    String firstRender(Renderer parent) {
        Rendered content = track(this::renderContent);
        parent.useSheets(content.sheets());
        items = content.items();
        html = items != null ? itemsHtml(hasSources()) : content.html();
        return hasSources() ? marked(id, html) : html;
    }

    /**
     * Renders again after a value it read changed, and records a patch if the browser's content must change. If
     * rendering throws, the hole keeps its previous content, items, handlers and subscriptions
     * (jasx-spec/ui.md §2.4), and the page reports the exception (targets/web.md §5).
     */
    @Override
    public void update() {
        List<Item> before = items;
        Rendered content = retrack(this::renderContent);
        page.useSheets(content.sheets()); // a css patch for any style sheet the page didn't have (§9)

        boolean isKeyedNow = content.items() != null;
        boolean wasKeyed = before != null;
        items = content.items();
        if (isKeyedNow && wasKeyed) {
            html = itemsHtml(true);
            if (!onlyKeptInTheSameOrder(content.entries(), before)) {
                patch("list", "items", content.entries());
            }
            return;
        }
        String newHtml = isKeyedNow ? itemsHtml(true) : content.html();
        if (!newHtml.equals(html)) {
            html = newHtml;
            patch("html", "html", newHtml);
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
        Renderer content = new Renderer(page, false);
        content.child(children);
        return new Rendered(content.html(), content.sheets(), null, null);
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
            Rendered itemContent = hole.track(hole::renderContent);
            hole.items = itemContent.items();
            hole.html = hole.items != null ? hole.itemsHtml(hole.hasSources()) : itemContent.html();
            sheets.addAll(itemContent.sheets());
            next.add(new Item(child.key(), child.element(), hole));

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", hole.id);
            entry.put("html", hole.html);
            entries.add(entry);
        }
        return new Rendered(null, sheets, next, entries);
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
    private String itemsHtml(boolean listCanChange) {
        StringBuilder out = new StringBuilder();
        for (Item item : items) {
            ChildHole hole = item.hole();
            boolean needsMarkers = listCanChange || hole.hasSources();
            out.append(needsMarkers ? marked(hole.id, hole.html) : hole.html);
        }
        return out.toString();
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

    private void patch(String op, String name, Object content) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("op", op);
        patch.put("id", id);
        patch.put(name, content);
        page.addPatch(patch);
    }

    private static String marked(int id, String html) {
        return "<!--jx:" + id + "-->" + html + "<!--/jx:" + id + "-->";
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
