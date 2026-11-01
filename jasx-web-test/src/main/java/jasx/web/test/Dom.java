package jasx.web.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The small DOM {@link TestPage} keeps: elements, text and comments (the hole markers, targets/web.md §8.2), and
 * the state a browser keeps apart from attributes: what a field shows ({@link Element#value}, {@link Element#checked},
 * {@link Element#selected}). Patches change it as the browser client changes the real page.
 */
final class Dom {
    private Dom() {}

    /** Elements without content or end tag. */
    static final Set<String> VOID_ELEMENTS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr");

    /** Elements whose content is raw text, not escaped. */
    static final Set<String> RAW_TEXT_ELEMENTS = Set.of("script", "style");

    /** Elements whose content is text only (escaped, but no elements or comments). */
    static final Set<String> TEXT_ONLY_ELEMENTS = Set.of("title", "textarea");

    abstract static sealed class Node permits Element, Text, Comment {
        Element parent;
    }

    static final class Element extends Node {
        final String tag;
        /** In source order; a bare attribute has the value "". */
        final Map<String, String> attributes = new LinkedHashMap<>();
        final List<Node> children = new ArrayList<>();
        /** What a field shows once typed in or set (null: what its markup says). */
        String value;
        Boolean checked;
        /** For an option: whether it is selected once changed (null: what its markup says). */
        Boolean selected;

        Element(String tag) {
            this.tag = tag;
        }

        String attribute(String name) {
            return attributes.get(name);
        }

        boolean hasAttribute(String name) {
            return attributes.containsKey(name);
        }

        void append(Node child) {
            child.parent = this;
            children.add(child);
        }
    }

    static final class Text extends Node {
        String text;

        Text(String text) {
            this.text = text;
        }
    }

    static final class Comment extends Node {
        final String data;

        Comment(String data) {
            this.data = data;
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** Calls {@code visit} on {@code root} and every element under it, in document order. */
    static void eachElement(Element root, Consumer<Element> visit) {
        visit.accept(root);
        for (Node child : List.copyOf(root.children)) {
            if (child instanceof Element element) {
                eachElement(element, visit);
            }
        }
    }

    /** The text of a node and everything under it, as {@code textContent} gives it. */
    static String text(Node node) {
        StringBuilder out = new StringBuilder();
        appendText(node, out);
        return out.toString();
    }

    private static void appendText(Node node, StringBuilder out) {
        switch (node) {
            case Text text -> out.append(text.text);
            case Element element -> element.children.forEach(child -> appendText(child, out));
            case Comment comment -> { }
        }
    }

    /** The HTML of a node, as the renderer writes it. */
    static String html(Node node) {
        StringBuilder out = new StringBuilder();
        appendHtml(node, out, false);
        return out.toString();
    }

    /** The HTML of an element's children. */
    static String innerHtml(Element element) {
        StringBuilder out = new StringBuilder();
        boolean raw = RAW_TEXT_ELEMENTS.contains(element.tag);
        element.children.forEach(child -> appendHtml(child, out, raw));
        return out.toString();
    }

    private static void appendHtml(Node node, StringBuilder out, boolean rawText) {
        switch (node) {
            case Text text -> out.append(rawText ? text.text : escape(text.text, false));
            case Comment comment -> out.append("<!--").append(comment.data).append("-->");
            case Element element -> {
                out.append('<').append(element.tag);
                element.attributes.forEach((name, value) -> {
                    out.append(' ').append(name);
                    if (!value.isEmpty()) {
                        out.append("=\"").append(escape(value, true)).append('"');
                    }
                });
                out.append('>');
                if (!VOID_ELEMENTS.contains(element.tag)) {
                    out.append(innerHtml(element)).append("</").append(element.tag).append('>');
                }
            }
        }
    }

    /** targets/html.md §3.2: {@code & < >}, and in attribute values also {@code "}. */
    static String escape(String text, boolean inAttribute) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append(inAttribute ? "&quot;" : "\"");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
