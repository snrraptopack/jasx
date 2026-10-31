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

    /** An element written in a file with a style sheet: rendered with the sheet's scope class (§3.5). */
    record Scoped(StyleSheet sheet, Element element) implements HtmlElement {
        @Override
        public String toString() {
            return Html.render(this);
        }
    }

    /** Where {@link Html#styles()} puts the style sheets used in a render (§3.5). */
    record Styles() implements HtmlElement {
        @Override
        public String toString() {
            return Html.render(this);
        }
    }
}
