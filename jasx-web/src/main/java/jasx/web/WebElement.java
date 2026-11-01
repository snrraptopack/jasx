package jasx.web;

import java.util.function.Supplier;
import jasx.runtime.Element;

/**
 * The element values of the web target, built by lowered markup through {@link Web}. They describe what to
 * render; a {@link Page} renders them, turning each {@link Dynamic} into a hole (targets/web.md §3).
 */
public sealed interface WebElement extends Element {

    /** An intrinsic element. {@code attributes} may be null. */
    record Tag(String tag, Attributes<?> attributes, Object[] children) implements WebElement {}

    /** A fragment: its children without a surrounding tag. */
    record Fragment(Object[] children) implements WebElement {}

    /**
     * An element with a key (semantics.md §6.4). Its element is a function, so that a list can keep an item
     * without running it again (targets/web.md §4.3).
     */
    record Keyed(Object key, Supplier<?> element) implements WebElement {}

    /**
     * A component element: the call of the component, as a function, so that a hole running again can keep the
     * instance it made before instead of running the component again (targets/web.md §4.4).
     */
    record Component(Supplier<?> call) implements WebElement {}

    /** Text, escaped when rendered. */
    record Text(String value) implements WebElement {}

    /** Trusted HTML, written as it is. */
    record Raw(String html) implements WebElement {}

    /** A dynamic child: re-evaluated, and re-rendered in the browser, when the state it reads changes. */
    record Dynamic(Supplier<Object> value) implements WebElement {}

    /** An element written in a file whose style sheet may apply to it: it gets the scope class. */
    record Scoped(StyleSheet sheet, Element element) implements WebElement {}

    /** An element written in a file with a style sheet that doesn't apply to it: the sheet is still used. */
    record Unscoped(StyleSheet sheet, Element element) implements WebElement {}

    /** Where the style sheets used by the page are placed (targets/web.md §9). */
    record Styles() implements WebElement {}
}
