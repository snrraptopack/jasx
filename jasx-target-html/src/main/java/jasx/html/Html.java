package jasx.html;

import java.lang.reflect.Array;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import jasx.runtime.Element;

/**
 * The factory class of the html reference target (semantics.md §6.2, targets/html.md). Lowered markup
 * calls {@link #h}, {@link #fragment}, {@link #keyed} and {@link #text}; applications call
 * {@link #render} and, for trusted markup, {@link #rawHtml}.
 */
public final class Html {
    private static final Set<String> VOID_ELEMENTS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr");
    private static final Object[] NO_CHILDREN = {};

    private Html() {}

    public static Element h(String tag, Attributes<?> attributes, Object... children) {
        return new HtmlElement.Tag(tag, attributes, children != null ? children : NO_CHILDREN);
    }

    public static Element fragment(Object... children) {
        return new HtmlElement.Fragment(children != null ? children : NO_CHILDREN);
    }

    /** Attaches a key. The html target does not reconcile, so keys are ignored when rendering. */
    public static Element keyed(Object key, Element element) {
        return new HtmlElement.Keyed(key, element);
    }

    public static Element text(String value) {
        return new HtmlElement.Text(value);
    }

    /** Inserts {@code html} without escaping. Only use it for trusted content. */
    public static Element rawHtml(String html) {
        return new HtmlElement.Raw(html);
    }

    /** Renders a child value (an element, text, null, Optional, Iterable, array or Stream) to HTML. */
    public static String render(Object value) {
        StringBuilder out = new StringBuilder();
        renderChild(value, out);
        return out.toString();
    }

    /** The child value contract (semantics.md §3.4). */
    private static void renderChild(Object value, StringBuilder out) {
        switch (value) {
            case null -> {}
            case HtmlElement e -> renderElement(e, out);
            case Element e -> escapeText(String.valueOf(e), out);
            case Optional<?> o -> o.ifPresent(v -> renderChild(v, out));
            case Iterable<?> items -> items.forEach(v -> renderChild(v, out));
            case Stream<?> items -> items.forEachOrdered(v -> renderChild(v, out));
            default -> {
                if (value.getClass().isArray()) {
                    for (int i = 0, n = Array.getLength(value); i < n; i++) {
                        renderChild(Array.get(value, i), out);
                    }
                } else {
                    escapeText(String.valueOf(value), out);
                }
            }
        }
    }

    private static void renderElement(HtmlElement element, StringBuilder out) {
        switch (element) {
            case HtmlElement.Tag t -> {
                out.append('<').append(t.tag());
                if (t.attributes() != null) {
                    renderAttributes(t.attributes(), out);
                }
                out.append('>');
                if (!VOID_ELEMENTS.contains(t.tag())) {
                    for (Object child : t.children()) {
                        renderChild(child, out);
                    }
                    out.append("</").append(t.tag()).append('>');
                }
            }
            case HtmlElement.Fragment f -> {
                for (Object child : f.children()) {
                    renderChild(child, out);
                }
            }
            case HtmlElement.Keyed k -> renderChild(k.element(), out);
            case HtmlElement.Text t -> escapeText(t.value(), out);
            case HtmlElement.Raw r -> out.append(r.html());
        }
    }

    /** targets/html.md §3.1: true is a bare name, false and null are omitted. */
    private static void renderAttributes(Attributes<?> attributes, StringBuilder out) {
        for (int i = 0; i < attributes.size(); i++) {
            Object value = attributes.value(i);
            if (value == null || Boolean.FALSE.equals(value)) {
                continue;
            }
            out.append(' ').append(attributes.name(i));
            if (!Boolean.TRUE.equals(value)) {
                String text = value instanceof Element ? render(value) : String.valueOf(value);
                out.append("=\"");
                escapeAttribute(text, out);
                out.append('"');
            }
        }
    }

    private static void escapeText(String s, StringBuilder out) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(c);
            }
        }
    }

    private static void escapeAttribute(String s, StringBuilder out) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> out.append(c);
            }
        }
    }
}
