package jasx.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import jasx.runtime.Element;
import jasx.ui.Action;
import jasx.ui.Derived;
import jasx.ui.Effect;
import jasx.ui.Event;
import jasx.ui.Handler;
import jasx.ui.Reactive;
import jasx.ui.State;

/**
 * The factory class of the web target (semantics.md §6.2, targets/web.md), and its API for applications.
 *
 * <p>Lowered markup calls {@link #h}, {@link #fragment}, {@link #keyed}, {@link #text} and {@link #dynamic},
 * and in files with a style block {@link #styleSheet}, {@link #scoped} and {@link #unscoped}.
 *
 * <p>Components use {@link #state}, {@link #derived} and {@link #effect} for reactive values (§4), and
 * {@link #debounce}, {@link #immediate} and {@link #navigate} in event handlers (§7). {@link WebApp} serves
 * pages.
 */
public final class Web {
    private static final Object[] NO_CHILDREN = {};

    private Web() {}

    // ---------------------------------------------------------------------------------------------
    // The factory functions called by lowered markup
    // ---------------------------------------------------------------------------------------------

    /** An element: {@code <tag attributes>children</tag>}. {@code attributes} may be null. */
    public static Element h(String tag, Attributes<?> attributes, Object... children) {
        return new WebElement.Tag(tag, attributes, children != null ? children : NO_CHILDREN);
    }

    /** A fragment: its children without a surrounding tag. */
    public static Element fragment(Object... children) {
        return new WebElement.Fragment(children != null ? children : NO_CHILDREN);
    }

    /**
     * A keyed element, as lowered markup makes it (targets/web.md §3): {@code <li key={id}>...</li>} becomes
     * {@code keyed(id, () -> h("li", ...))}. The element is a function, so that in a list the runtime can keep
     * an item whose key and inputs didn't change without running its code again (§4.3).
     */
    public static Element keyed(Object key, Supplier<?> element) {
        return new WebElement.Keyed(key, element);
    }

    /** A keyed element already built. It is never kept between runs, because its inputs can't be compared. */
    public static Element keyed(Object key, Element element) {
        return new WebElement.Keyed(key, () -> element);
    }

    /** A text element. Its text is escaped when rendered. */
    public static Element text(String value) {
        return new WebElement.Text(value);
    }

    /**
     * A dynamic child (targets/web.md §3): {@code {expression}} lowers to {@code dynamic(() -> (expression))}.
     * It is re-evaluated, and updated in the browser, when a state it reads changes.
     */
    public static Element dynamic(Supplier<Object> value) {
        return new WebElement.Dynamic(value);
    }

    /** The style sheet of a file with a style block, with its CSS already scoped (semantics.md §10.5). */
    public static StyleSheet styleSheet(String scope, String css) {
        return new StyleSheet(scope, css);
    }

    /** An element written in a file whose style sheet is {@code sheet}; it gets the sheet's scope class. */
    public static Element scoped(Object sheet, Element element) {
        return new WebElement.Scoped((StyleSheet) sheet, element);
    }

    /** An element written in a file whose style sheet doesn't apply to it; the sheet is still delivered. */
    public static Element unscoped(Object sheet, Element element) {
        return new WebElement.Unscoped((StyleSheet) sheet, element);
    }

    /**
     * Where the CSS of the style sheets used on the page goes (targets/web.md §9). Without it, the CSS is
     * inserted before {@code </head>}.
     */
    public static Element styles() {
        return new WebElement.Styles();
    }

    /** Inserts {@code html} without any escaping. Only use it for trusted content. */
    public static Element rawHtml(String html) {
        return new WebElement.Raw(html);
    }

    // ---------------------------------------------------------------------------------------------
    // Reactive values (targets/web.md §4)
    // ---------------------------------------------------------------------------------------------

    // They are jasx.ui's (jasx-spec/ui.md §2), re-exported so that `import static jasx.web.Web.*` is enough.

    /** A reactive value. Holes that read it update when it is set to a different value. */
    public static <T> State<T> state(T initial) {
        return Reactive.state(initial);
    }

    /** A cached value computed from other reactive values, computed again after one of them changes. */
    public static <T> Derived<T> derived(Supplier<T> fn) {
        return Reactive.derived(fn);
    }

    /**
     * Runs {@code fn} now, and again after every update in which a reactive value it read changed. Created in
     * a component, it stops when the component's hole re-runs (targets/web.md §4.2).
     */
    public static Effect effect(Runnable fn) {
        return Reactive.effect(fn);
    }

    /** Runs {@code body} without subscribing the hole running now to what it reads. */
    public static <T> T untracked(Supplier<T> body) {
        return Reactive.untracked(body);
    }

    // ---------------------------------------------------------------------------------------------
    // Events (targets/web.md §7)
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code handler}, with its events sent {@code milliseconds} after the last one: for example a search
     * field, {@code oninput={debounce(500, e -> query.set(e.value()))}}.
     */
    public static <E extends Event> Handler<E> debounce(int milliseconds, Handler<E> handler) {
        return new Debounced.OfHandler<>(milliseconds, handler);
    }

    /** {@code action}, with its events sent {@code milliseconds} after the last one. */
    public static Action debounce(int milliseconds, Action action) {
        return new Debounced.OfAction(milliseconds, action);
    }

    /** {@code handler}, with every event sent at once, even for {@code oninput}, which is debounced by default. */
    public static <E extends Event> Handler<E> immediate(Handler<E> handler) {
        return new Debounced.OfHandler<>(0, handler);
    }

    /** {@code action}, with every event sent at once. */
    public static Action immediate(Action action) {
        return new Debounced.OfAction(0, action);
    }

    /**
     * Makes the browser go to {@code url}, after the current event's other changes. Call it in an event
     * handler.
     *
     * @throws IllegalStateException outside an event of a page
     */
    public static void navigate(String url) {
        Page page = Page.current();
        if (page == null) {
            throw new IllegalStateException("navigate() must be called while a page handles an event");
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("op", "navigate");
        patch.put("url", url);
        page.addPatch(patch);
    }
}
