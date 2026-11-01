package jasx.web;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Supplier;
import java.util.stream.Stream;
import jasx.runtime.Element;

/**
 * Renders element values of a {@link Page} to HTML (targets/web.md §8.2), like the html target's renderer
 * (targets/html.md §3), plus what makes a page live:
 *
 * <ul>
 *   <li>a dynamic child becomes a {@link ChildHole}, written between {@code <!--jx:N-->} markers;
 *   <li>a dynamic attribute becomes an {@link AttributeHole}, and its element gets {@code data-jx-id};
 *   <li>event handlers are registered with the page and listed in {@code data-jx-on}.
 * </ul>
 *
 * <p>A <em>static</em> renderer (for markup used as an attribute value) evaluates dynamic values once and
 * leaves handlers out.
 */
final class Renderer {
    /** oninput is debounced by default, so typing doesn't send every keystroke (targets/web.md §7). */
    static final int DEFAULT_INPUT_DELAY = 300;

    private static final Set<String> VOID_ELEMENTS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr");

    /** Content parsed as raw text: not escaped, and no hole markers (comments are text there, §8.2). */
    private static final Set<String> RAW_TEXT_ELEMENTS = Set.of("script", "style");

    /** Content parsed as escapable text: escaped, but no hole markers either. */
    private static final Set<String> TEXT_ONLY_ELEMENTS = Set.of("title", "textarea");

    private final Page page;
    private final boolean isStatic;
    private final StringBuilder out = new StringBuilder();
    private boolean escapeText = true;
    /** Inside an element whose content can't hold markers: dynamic children are evaluated once. */
    private boolean noHoles;

    /** The style sheets used, in the order they were first used (targets/web.md §9). */
    private final Set<StyleSheet> sheets = new LinkedHashSet<>();
    /** Where things are inserted into a whole document: -1 if not seen. */
    private int stylesAt = -1;
    private int headEnd = -1;
    private int bodyEnd = -1;

    Renderer(Page page, boolean isStatic) {
        this.page = page;
        this.isStatic = isStatic;
    }

    /** Markup used as an attribute value, rendered to HTML once. */
    static String staticHtml(Page page, Element element) {
        Renderer renderer = new Renderer(page, true);
        renderer.child(element);
        return renderer.html();
    }

    String html() {
        return out.toString();
    }

    Set<StyleSheet> sheets() {
        return sheets;
    }

    void useSheets(Set<StyleSheet> used) {
        sheets.addAll(used);
    }

    // ---------------------------------------------------------------------------------------------
    // A whole page
    // ---------------------------------------------------------------------------------------------

    /**
     * The HTML of a whole page (targets/web.md §6): {@code root} rendered, the client script inserted before
     * {@code </body>} (or at the end), and the style sheets at {@code Web.styles()}, before {@code </head>},
     * or at the start.
     */
    String document(Object root, String scriptTag) {
        child(root);
        String styles = stylesElement();
        int scriptAt = bodyEnd >= 0 ? bodyEnd : out.length();
        int sheetsAt = stylesAt >= 0 ? stylesAt : headEnd >= 0 ? headEnd : 0;

        // Insert at the later position first, so the earlier one stays valid.
        if (scriptAt >= sheetsAt) {
            out.insert(scriptAt, scriptTag);
            out.insert(sheetsAt, styles);
        } else {
            out.insert(sheetsAt, styles);
            out.insert(scriptAt, scriptTag);
        }
        return out.toString();
    }

    private String stylesElement() {
        if (sheets.isEmpty()) {
            return "";
        }
        List<String> css = new ArrayList<>();
        for (StyleSheet sheet : sheets) {
            css.add(sheet.css());
        }
        return "<style>" + String.join("\n", css) + "</style>";
    }

    // ---------------------------------------------------------------------------------------------
    // Children (semantics.md §3.4)
    // ---------------------------------------------------------------------------------------------

    /** The child value contract (semantics.md §3.4). */
    void child(Object value) {
        switch (value) {
            case null -> {} // nothing
            case WebElement element -> element(element);
            case Element other -> text(String.valueOf(other)); // an element of another target
            case Optional<?> optional -> optional.ifPresent(this::child);
            case Iterable<?> items -> items.forEach(this::child);
            case Stream<?> items -> items.forEachOrdered(this::child);
            default -> {
                if (value.getClass().isArray()) {
                    for (int i = 0; i < Array.getLength(value); i++) {
                        child(Array.get(value, i));
                    }
                } else {
                    text(String.valueOf(value)); // any other value, including Boolean, is text
                }
            }
        }
    }

    private void element(WebElement element) {
        switch (element) {
            case WebElement.Tag tag -> tag(tag, null);
            case WebElement.Fragment fragment -> {
                for (Object child : fragment.children()) {
                    child(child);
                }
            }
            case WebElement.Keyed keyed -> child(keyed.element());
            case WebElement.Text text -> text(text.value());
            case WebElement.Raw raw -> out.append(raw.html());
            case WebElement.Dynamic dynamic -> dynamic(dynamic);
            case WebElement.Scoped scoped -> {
                sheets.add(scoped.sheet());
                if (scoped.element() instanceof WebElement.Tag tag) {
                    tag(tag, scoped.sheet().scope());
                } else {
                    child(scoped.element());
                }
            }
            case WebElement.Unscoped unscoped -> {
                sheets.add(unscoped.sheet());
                child(unscoped.element());
            }
            case WebElement.Styles styles -> {
                if (stylesAt < 0) {
                    stylesAt = out.length();
                }
            }
        }
    }

    /** A dynamic child: a hole, unless it is where markers can't go (or this renderer is static). */
    private void dynamic(WebElement.Dynamic dynamic) {
        if (isStatic || noHoles) {
            child(dynamic.value().get());
            return;
        }
        ChildHole hole = new ChildHole(page, page.nextId(), dynamic.value());
        out.append(hole.firstRender(this));
    }

    // ---------------------------------------------------------------------------------------------
    // Elements
    // ---------------------------------------------------------------------------------------------

    /** An element; {@code scopeClass} is its style sheet's class, or null if it isn't scoped. */
    private void tag(WebElement.Tag tag, String scopeClass) {
        String name = tag.tag();
        out.append('<').append(name);
        attributes(tag.attributes(), scopeClass);
        out.append('>');

        if (VOID_ELEMENTS.contains(name)) {
            return; // no content and no closing tag
        }

        boolean rawText = RAW_TEXT_ELEMENTS.contains(name);
        boolean textOnly = rawText || TEXT_ONLY_ELEMENTS.contains(name);
        boolean outerEscape = escapeText;
        boolean outerNoHoles = noHoles;
        escapeText = outerEscape && !rawText;
        noHoles = outerNoHoles || textOnly;
        int contentStart = out.length();
        try {
            for (Object child : tag.children()) {
                child(child);
            }
        } finally {
            escapeText = outerEscape;
            noHoles = outerNoHoles;
        }
        if (rawText) {
            checkRawText(name, contentStart);
        }

        if (name.equals("head") && headEnd < 0) {
            headEnd = out.length();
        }
        if (name.equals("body") && bodyEnd < 0) {
            bodyEnd = out.length();
        }
        out.append("</").append(name).append('>');
    }

    /** The content of script or style must not close the element early (targets/html.md §3.3). */
    private void checkRawText(String name, int contentStart) {
        String content = out.substring(contentStart).toLowerCase(Locale.ROOT);
        if (content.contains("</" + name)) {
            throw new IllegalArgumentException("the content of <" + name + "> must not contain \"</" + name + "\"");
        }
    }

    /**
     * The attributes of an element: static ones as on the html target, dynamic ones as holes, handlers
     * registered with the page. For a scoped element, the scope class is added to the class attribute.
     */
    private void attributes(Attributes<?> attributes, String scopeClass) {
        boolean classWritten = false;
        Integer elementId = null;
        boolean needsId = false; // only if one of its dynamic attributes can change
        StringJoiner handlers = new StringJoiner(" ");
        int count = attributes != null ? attributes.size() : 0;

        for (int i = 0; i < count; i++) {
            String name = attributes.name(i);
            Object value = attributes.value(i);

            if (value instanceof Handler handler) {
                if (!isStatic) {
                    handlers.add(handlerEntry(name, handler));
                }
                continue;
            }

            boolean isClass = name.equals("class");
            String scope = isClass ? scopeClass : null;
            classWritten |= isClass;

            Object written;
            if (value instanceof Supplier<?> supplier && !isStatic) {
                if (elementId == null) {
                    elementId = page.nextId();
                }
                AttributeHole hole = new AttributeHole(page, elementId, name, supplier, scope);
                written = hole.firstValue();
                needsId |= hole.canChange();
            } else if (value instanceof Supplier<?> supplier) {
                written = AttributeHole.normalize(page, supplier.get(), scope);
            } else {
                written = AttributeHole.normalize(page, value, scope);
            }
            attribute(name, written);
        }

        if (scopeClass != null && !classWritten) {
            attribute("class", scopeClass);
        }
        if (needsId) {
            attribute("data-jx-id", String.valueOf(elementId));
        }
        if (handlers.length() > 0) {
            attribute("data-jx-on", handlers.toString());
        }
    }

    /**
     * {@code onclick} with a handler  →  {@code click:12}, or {@code input:13:300} with its debounce delay
     * (targets/web.md §7, §8.2).
     */
    private String handlerEntry(String attributeName, Handler handler) {
        String type = attributeName.startsWith("on") ? attributeName.substring(2) : attributeName;
        int delay;
        if (handler instanceof Handler.Debounced debounced) {
            delay = debounced.delay();
        } else {
            delay = type.equals("input") ? DEFAULT_INPUT_DELAY : 0;
        }
        int id = page.addHandler(handler, Computation.current());
        return delay > 0 ? type + ":" + id + ":" + delay : type + ":" + id;
    }

    /** One attribute, as {@link AttributeHole#normalize} gives it: absent, bare, or with a value. */
    private void attribute(String name, Object written) {
        if (written == null) {
            return;
        }
        out.append(' ').append(name);
        if (written instanceof String text) {
            out.append("=\"");
            escape(text, true);
            out.append('"');
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Text
    // ---------------------------------------------------------------------------------------------

    private void text(String text) {
        if (escapeText) {
            escape(text, false);
        } else {
            out.append(text);
        }
    }

    /** targets/html.md §3.2: escape {@code & < >}, and in attribute values also {@code "}. */
    private void escape(String s, boolean inAttribute) {
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
