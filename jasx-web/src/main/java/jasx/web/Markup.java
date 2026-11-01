package jasx.web;

import java.util.ArrayList;
import java.util.List;

/**
 * HTML as the renderer writes it, kept as <em>statics</em> and <em>values</em> (targets/web.md §8.4, templates):
 * the statics are what the renderer writes itself (tags, attribute names, quotes, hole markers), the values are
 * what comes from the page's data (escaped text, attribute values, ids). The HTML is
 * {@code statics[0] + values[0] + statics[1] + ... + statics[n]}, so there is always one static more than values.
 *
 * <p>Two renderings with the same statics have the same <em>shape</em>: a browser that knows the shape needs only
 * the values ({@link Page#content}).
 */
final class Markup {
    private final List<String> statics = new ArrayList<>();
    private final List<String> values = new ArrayList<>();
    private final StringBuilder current = new StringBuilder();
    private int length;

    /** Appends markup the renderer writes itself. */
    Markup append(String text) {
        current.append(text);
        length += text.length();
        return this;
    }

    Markup append(char c) {
        current.append(c);
        length++;
        return this;
    }

    /** Appends a value: text that comes from the page's data, already escaped. */
    Markup value(String html) {
        statics.add(current.toString());
        current.setLength(0);
        values.add(html);
        length += html.length();
        return this;
    }

    /** Appends another markup, keeping its statics and values apart. */
    Markup append(Markup other) {
        for (int i = 0; i < other.values.size(); i++) {
            append(other.statics.get(i));
            value(other.values.get(i));
        }
        return append(other.current.toString());
    }

    /** The number of characters of the HTML. */
    int length() {
        return length;
    }

    /** The statics: one more than {@link #values()}. */
    List<String> statics() {
        List<String> all = new ArrayList<>(statics);
        all.add(current.toString());
        return all;
    }

    List<String> values() {
        return List.copyOf(values);
    }

    /** The HTML. */
    @Override
    public String toString() {
        StringBuilder html = new StringBuilder(length);
        for (int i = 0; i < values.size(); i++) {
            html.append(statics.get(i)).append(values.get(i));
        }
        return html.append(current).toString();
    }
}
