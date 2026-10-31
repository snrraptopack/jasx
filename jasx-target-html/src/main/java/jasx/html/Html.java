package jasx.html;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import jasx.runtime.Element;

/**
 * The factory class of the html reference target (semantics.md §6.2, targets/html.md).
 *
 * <p>Lowered markup calls {@link #h}, {@link #fragment}, {@link #keyed} and {@link #text}, and in files with a
 * style block {@link #styleSheet}, {@link #scoped} and {@link #unscoped}. Applications call {@link #render} to turn the result
 * into an HTML string, {@link #rawHtml} to insert trusted HTML without escaping, and {@link #styles} to place
 * the CSS of the style blocks used on a page.
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

    /** The style sheet of a file with a style block, with its CSS already scoped (semantics.md §10.5). */
    public static StyleSheet styleSheet(String scope, String css) {
        return new StyleSheet(scope, css);
    }

    /** An element written in a file whose style sheet is {@code sheet}; it gets the sheet's scope class. */
    public static Element scoped(Object sheet, Element element) {
        return new HtmlElement.Scoped((StyleSheet) sheet, element);
    }

    /**
     * An element written in a file whose style sheet is {@code sheet}, that none of the sheet's CSS can apply
     * to: it gets no scope class, but the sheet is still delivered by {@link #styles()}.
     */
    public static Element unscoped(Object sheet, Element element) {
        return new HtmlElement.Unscoped((StyleSheet) sheet, element);
    }

    /**
     * Renders as a {@code <style>} element with the CSS of every style sheet used in the same
     * {@link #render} call, or as nothing if none is used (targets/html.md §3.5). Put it in {@code <head>}:
     * it is filled in after the rest of the page is rendered.
     */
    public static Element styles() {
        return new HtmlElement.Styles();
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
        renderer.insertStyles();
        return renderer.out.toString();
    }

    /**
     * Renders into one buffer. Inside {@code <script>} and {@code <style>}, text is not escaped. It also
     * collects the style sheets of the scoped elements it renders, for {@link #styles()}.
     */
    private static final class Renderer {
        final StringBuilder out = new StringBuilder();
        boolean escapeText = true;

        /** The style sheets used so far, in the order they were first used. */
        final Set<StyleSheet> sheets = new LinkedHashSet<>();
        /** Where the first {@code styles()} element is in {@link #out}, or -1 if there is none. */
        int stylesAt = -1;

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
                case HtmlElement.Scoped scoped -> scoped(scoped);
                case HtmlElement.Unscoped unscoped -> {
                    sheets.add(unscoped.sheet()); // no scope class, but the sheet is used (§3.5)
                    child(unscoped.element());
                }
                case HtmlElement.Styles styles -> {
                    if (stylesAt < 0) {
                        stylesAt = out.length(); // filled in by insertStyles(), once everything is rendered
                    }
                }
            }
        }

        /** A scoped element: its tag gets the scope class, and its style sheet is recorded (§3.5). */
        void scoped(HtmlElement.Scoped scoped) {
            sheets.add(scoped.sheet());
            if (scoped.element() instanceof HtmlElement.Tag tag) {
                tag(tag, scoped.sheet().scope());
            } else {
                child(scoped.element());
            }
        }

        /** Puts the CSS of the style sheets used at the place of the first {@code styles()} element. */
        void insertStyles() {
            if (stylesAt < 0 || sheets.isEmpty()) {
                return;
            }
            List<String> css = new ArrayList<>();
            for (StyleSheet sheet : sheets) {
                css.add(sheet.css());
            }
            out.insert(stylesAt, "<style>" + String.join("\n", css) + "</style>");
        }

        void tag(HtmlElement.Tag tag) {
            tag(tag, null);
        }

        /** An element; {@code scopeClass} is the class of its style sheet, or null if it isn't scoped. */
        void tag(HtmlElement.Tag tag, String scopeClass) {
            out.append('<').append(tag.tag());
            attributes(tag.attributes(), scopeClass);
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

        /**
         * targets/html.md §3.1: true is written as the bare name, false and null are left out. For a scoped
         * element (§3.5), the scope class is appended to the class attribute, or written as one.
         *
         * @param attributes the attributes, or null if there are none
         * @param scopeClass the scope class, or null if the element isn't scoped
         */
        void attributes(Attributes<?> attributes, String scopeClass) {
            boolean scopeWritten = false;
            int count = attributes != null ? attributes.size() : 0;
            for (int i = 0; i < count; i++) {
                String name = attributes.name(i);
                Object value = attributes.value(i);
                if (value == null || Boolean.FALSE.equals(value)) {
                    continue;
                }

                boolean isScopedClass = scopeClass != null && name.equals("class");
                if (isScopedClass && Boolean.TRUE.equals(value)) {
                    continue; // a bare "class" is replaced by class="scope" below
                }

                out.append(' ').append(name);
                if (Boolean.TRUE.equals(value)) {
                    continue;
                }

                // Markup as an attribute value is rendered to HTML first, then escaped like any text.
                String text = value instanceof Element ? render(value) : String.valueOf(value);
                if (isScopedClass) {
                    text = text + " " + scopeClass;
                    scopeWritten = true;
                }
                out.append("=\"");
                escape(text, true);
                out.append('"');
            }

            if (scopeClass != null && !scopeWritten) {
                out.append(" class=\"").append(scopeClass).append('"');
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
