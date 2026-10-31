package jasx.html;

import jasx.runtime.Element;

/** The element values of the html target. {@link #toString()} renders them as HTML. */
public sealed interface HtmlElement extends Element {

    record Tag(String tag, Attributes<?> attributes, Object[] children) implements HtmlElement {
        @Override
        public String toString() {
            return Html.render(this);
        }
    }

    record Fragment(Object[] children) implements HtmlElement {
        @Override
        public String toString() {
            return Html.render(this);
        }
    }

    record Keyed(Object key, Element element) implements HtmlElement {
        @Override
        public String toString() {
            return Html.render(this);
        }
    }

    record Text(String value) implements HtmlElement {
        @Override
        public String toString() {
            return Html.render(this);
        }
    }

    record Raw(String html) implements HtmlElement {
        @Override
        public String toString() {
            return html;
        }
    }
}
