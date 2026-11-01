package jasx.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import jasx.runtime.Element;

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

    /** Attaches a key (semantics.md §6.4). Keys don't preserve instances yet (targets/web.md §10). */
    public static Element keyed(Object key, Element element) {
        return new WebElement.Keyed(key, element);
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

    /** A reactive value. Holes that read it update when it is set to a different value. */
    public static <T> State<T> state(T initial) {
        return new State<>(initial);
    }

    /** A cached value computed from other reactive values, computed again after one of them changes. */
    public static <T> Derived<T> derived(Supplier<T> fn) {
        return new Derived<>(fn);
    }

    /**
     * Runs {@code fn} now, and again after every update in which a reactive value it read changed. Created in
     * a component, it stops when the component's hole re-runs (targets/web.md §4.2).
     */
    public static void effect(Runnable fn) {
        Computation owner = Computation.current();
        Page page = owner != null ? owner.page : Page.current();
        new Effect(page, fn).run();
    }

    // ---------------------------------------------------------------------------------------------
    // Events (targets/web.md §7)
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code handler}, with its events sent {@code milliseconds} after the last one: for example a search
     * field, {@code oninput={debounce(500, e -> query.set(e.value()))}}.
     */
    public static Handler debounce(int milliseconds, Handler handler) {
        return new Handler.Debounced(milliseconds, handler);
    }

    /** {@code handler}, with every event sent at once, even for {@code oninput}, which is debounced by default. */
    public static Handler immediate(Handler handler) {
        return new Handler.Debounced(0, handler);
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
