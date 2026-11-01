package jasx.web.test;

import jasx.web.test.Dom.Comment;
import jasx.web.test.Dom.Element;
import jasx.web.test.Dom.Text;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Reads the HTML the web target renders into a {@link Dom}. The renderer's output is regular (every element is
 * closed, attribute values are double-quoted, only {@code & < > "} are escaped), so this reads exactly that, plus
 * the usual variations a hand-written page might add (single quotes, unquoted values, numeric references). It is
 * not a full HTML parser: it doesn't move misplaced elements as browsers do.
 */
final class HtmlParser {
    private final String html;
    private int pos;

    private HtmlParser(String html) {
        this.html = html;
    }

    /** The nodes of {@code html}, as children of a {@code #root} element. */
    static Element parse(String html) {
        return new HtmlParser(html).parseAll();
    }

    private Element parseAll() {
        Element root = new Element("#root");
        Deque<Element> open = new ArrayDeque<>();
        open.push(root);
        while (pos < html.length()) {
            if (html.startsWith("<!--", pos)) {
                int end = indexOrEnd("-->", pos + 4);
                open.peek().append(new Comment(html.substring(pos + 4, end)));
                pos = Math.min(html.length(), end + 3);
            } else if (html.startsWith("<!", pos) || html.startsWith("<?", pos)) {
                pos = Math.min(html.length(), indexOrEnd(">", pos) + 1); // a doctype or processing instruction
            } else if (html.startsWith("</", pos)) {
                closeElement(open);
            } else if (html.charAt(pos) == '<' && pos + 1 < html.length() && Character.isLetter(html.charAt(pos + 1))) {
                openElement(open);
            } else {
                int next = html.indexOf('<', pos + 1);
                int end = next < 0 ? html.length() : next;
                open.peek().append(new Text(decode(html.substring(pos, end))));
                pos = end;
            }
        }
        return root;
    }

    private void openElement(Deque<Element> open) {
        pos++; // <
        String tag = name().toLowerCase(Locale.ROOT);
        Element element = new Element(tag);
        readAttributes(element);
        boolean selfClosed = html.startsWith("/>", pos);
        pos = Math.min(html.length(), pos + (selfClosed ? 2 : 1));
        open.peek().append(element);
        if (selfClosed || Dom.VOID_ELEMENTS.contains(tag)) {
            return;
        }
        boolean raw = Dom.RAW_TEXT_ELEMENTS.contains(tag);
        if (raw || Dom.TEXT_ONLY_ELEMENTS.contains(tag)) {
            int end = indexOrEnd("</" + tag, pos);
            String content = html.substring(pos, end);
            if (!content.isEmpty()) {
                element.append(new Text(raw ? content : decode(content)));
            }
            pos = Math.min(html.length(), indexOrEnd(">", end) + 1);
            return;
        }
        open.push(element);
    }

    /** Closes the nearest open element of that name; an end tag that matches nothing is ignored. */
    private void closeElement(Deque<Element> open) {
        pos += 2;
        String tag = name().toLowerCase(Locale.ROOT);
        pos = Math.min(html.length(), indexOrEnd(">", pos) + 1);
        boolean isOpen = open.stream().anyMatch(element -> element.tag.equals(tag));
        if (!isOpen) {
            return;
        }
        while (!open.peek().tag.equals(tag)) {
            open.pop();
        }
        open.pop();
    }

    private void readAttributes(Element element) {
        while (true) {
            skipSpace();
            if (pos >= html.length() || html.charAt(pos) == '>' || html.startsWith("/>", pos)) {
                return;
            }
            String name = name().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                pos++; // a stray character: skip it
                continue;
            }
            skipSpace();
            String value = "";
            if (pos < html.length() && html.charAt(pos) == '=') {
                pos++;
                skipSpace();
                value = decode(attributeValue());
            }
            element.attributes.putIfAbsent(name, value);
        }
    }

    private String attributeValue() {
        if (pos >= html.length()) {
            return "";
        }
        char quote = html.charAt(pos);
        if (quote == '"' || quote == '\'') {
            int end = indexOrEnd(String.valueOf(quote), pos + 1);
            String value = html.substring(pos + 1, end);
            pos = Math.min(html.length(), end + 1);
            return value;
        }
        int start = pos;
        while (pos < html.length() && !Character.isWhitespace(html.charAt(pos)) && html.charAt(pos) != '>') {
            pos++;
        }
        return html.substring(start, pos);
    }

    private String name() {
        int start = pos;
        while (pos < html.length()) {
            char c = html.charAt(pos);
            if (Character.isWhitespace(c) || c == '>' || c == '/' || c == '=') {
                break;
            }
            pos++;
        }
        return html.substring(start, pos);
    }

    private void skipSpace() {
        while (pos < html.length() && Character.isWhitespace(html.charAt(pos))) {
            pos++;
        }
    }

    private int indexOrEnd(String text, int from) {
        int index = html.indexOf(text, from);
        return index < 0 ? html.length() : index;
    }

    /** The references the renderer writes, {@code &amp; &lt; &gt; &quot;}, plus numeric ones and {@code &#39;}. */
    static String decode(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int semicolon = c == '&' ? text.indexOf(';', i) : -1;
            String reference = semicolon > i ? text.substring(i + 1, semicolon) : null;
            String decoded = reference == null ? null : decodeReference(reference);
            if (decoded == null) {
                out.append(c);
            } else {
                out.append(decoded);
                i = semicolon;
            }
        }
        return out.toString();
    }

    private static String decodeReference(String reference) {
        return switch (reference) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            case "nbsp" -> Character.toString(0xA0); // a no-break space
            default -> numericReference(reference);
        };
    }

    private static String numericReference(String reference) {
        try {
            if (reference.startsWith("#x") || reference.startsWith("#X")) {
                return Character.toString(Integer.parseInt(reference.substring(2), 16));
            }
            if (reference.startsWith("#")) {
                return Character.toString(Integer.parseInt(reference.substring(1)));
            }
        } catch (IllegalArgumentException e) {
            return null; // not a reference after all: kept as it is
        }
        return null;
    }
}
