package jasx.html;

import java.lang.reflect.Array;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import jasx.runtime.Element;

/**
 * The factory class of the html reference target (semantics.md §6.2, targets/html.md).
 *
 * <p>Lowered markup calls {@link #h}, {@link #fragment}, {@link #keyed} and {@link #text}. Applications
 * call {@link #render} to turn the result into an HTML string, and {@link #rawHtml} to insert trusted
 * HTML without escaping.
 */
public final class Html {
    /** Elements that have no content and no closing tag (targets/html.md §3). */
    private static final Set<String> VOID_ELEMENTS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr");

    /** Elements whose content HTML parses as raw text, so it must not be escaped (targets/html.md §3.3). */
    private static final Set<String> RAW_TEXT_ELEMENTS = Set.of("script", "style");

    private static final Object[] NO_CHILDREN = {};

    private Html() {}

    // ---------------------------------------------------------------------------------------------
    // The factory functions called by lowered markup
    // ---------------------------------------------------------------------------------------------

    /** An element: {@code <tag attributes>children</tag>}. {@code attributes} may be null. */
    public static Element h(String tag, Attributes<?> attributes, Object... children) {
        return new HtmlElement.Tag(tag, attributes, children != null ? children : NO_CHILDREN);
    }

    /** A fragment: its children without a surrounding tag. */
    public static Element fragment(Object... children) {
        return new HtmlElement.Fragment(children != null ? children : NO_CHILDREN);
    }

    /** Attaches a key. The html target doesn't reconcile lists, so keys are ignored when rendering. */
    public static Element keyed(Object key, Element element) {
        return new HtmlElement.Keyed(key, element);
    }

    /** A text element. Its text is escaped when rendered. */
    public static Element text(String value) {
        return new HtmlElement.Text(value);
    }

    /** Inserts {@code html} without any escaping. Only use it for trusted content. */
    public static Element rawHtml(String html) {
        return new HtmlElement.Raw(html);
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------

    /**
     * Renders a value to HTML. The value can be anything markup accepts as a child: an element, text,
     * null, an Optional, an Iterable, an array or a Stream (semantics.md §3.4).
     */
    public static String render(Object value) {
        Renderer renderer = new Renderer();
        renderer.child(value);
        return renderer.out.toString();
    }

    /** Renders into one buffer. Inside {@code <script>} and {@code <style>}, text is not escaped. */
    private static final class Renderer {
        final StringBuilder out = new StringBuilder();
        boolean escapeText = true;

        /** The child value contract (semantics.md §3.4). */
        void child(Object value) {
            switch (value) {
                case null -> {} // nothing
                case HtmlElement element -> element(element);
                case Element other -> text(String.valueOf(other)); // an element of another target
                case Optional<?> optional -> optional.ifPresent(this::child);
                case Iterable<?> items -> items.forEach(this::child);
                case Stream<?> items -> items.forEachOrdered(this::child);
                default -> {
                    if (value.getClass().isArray()) {
                        // Array.get also handles primitive arrays such as int[].
                        for (int i = 0; i < Array.getLength(value); i++) {
                            child(Array.get(value, i));
                        }
                    } else {
                        text(String.valueOf(value)); // any other value, including Boolean, is text
                    }
                }
            }
        }

        void element(HtmlElement element) {
            switch (element) {
                case HtmlElement.Tag tag -> tag(tag);
                case HtmlElement.Fragment fragment -> {
                    for (Object child : fragment.children()) {
                        child(child);
                    }
                }
                case HtmlElement.Keyed keyed -> child(keyed.element());
                case HtmlElement.Text text -> text(text.value());
                case HtmlElement.Raw raw -> out.append(raw.html());
            }
        }

        void tag(HtmlElement.Tag tag) {
            out.append('<').append(tag.tag());
            if (tag.attributes() != null) {
                attributes(tag.attributes());
            }
            out.append('>');

            if (VOID_ELEMENTS.contains(tag.tag())) {
                return; // no content and no closing tag
            }

            if (RAW_TEXT_ELEMENTS.contains(tag.tag())) {
                rawTextContent(tag);
            } else {
                for (Object child : tag.children()) {
                    child(child);
                }
            }
            out.append("</").append(tag.tag()).append('>');
        }

        /**
         * The content of {@code <script>} or {@code <style>}, written without escaping. It must not contain
         * the element's closing tag, which would end the element early and let the rest run as HTML.
         */
        void rawTextContent(HtmlElement.Tag tag) {
            int start = out.length();
            boolean outerEscapeText = escapeText;
            escapeText = false;
            try {
                for (Object child : tag.children()) {
                    child(child);
                }
            } finally {
                escapeText = outerEscapeText;
            }

            String content = out.substring(start).toLowerCase(Locale.ROOT);
            if (content.contains("</" + tag.tag())) {
                throw new IllegalArgumentException("the content of <" + tag.tag() + "> must not contain \"</"
                        + tag.tag() + "\"");
            }
        }

        /** targets/html.md §3.1: true is written as the bare name, false and null are left out. */
        void attributes(Attributes<?> attributes) {
            for (int i = 0; i < attributes.size(); i++) {
                Object value = attributes.value(i);
                if (value == null || Boolean.FALSE.equals(value)) {
                    continue;
                }

                out.append(' ').append(attributes.name(i));
                if (Boolean.TRUE.equals(value)) {
                    continue;
                }

                // Markup as an attribute value is rendered to HTML first, then escaped like any text.
                String text = value instanceof Element ? render(value) : String.valueOf(value);
                out.append("=\"");
                escape(text, true);
                out.append('"');
            }
        }

        void text(String text) {
            if (escapeText) {
                escape(text, false);
            } else {
                out.append(text);
            }
        }

        /** targets/html.md §3.2: escape {@code & < >}, and in attribute values also {@code "}. */
        void escape(String s, boolean inAttribute) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '&' -> out.append("&amp;");
                    case '<' -> out.append("&lt;");
                    case '>' -> out.append("&gt;");
                    case '"' -> out.append(inAttribute ? "&quot;" : "\"");
                    default -> out.append(c);
                }
            }
        }
    }
}
