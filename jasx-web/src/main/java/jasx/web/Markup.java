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
 *
 * <p>A hole inside the markup is kept as a reference to the hole ({@link #hole}), not as a copy of its content:
 * whenever the markup is turned into HTML, each hole contributes its content as it is <em>then</em>. So a parent
 * that sends its content again includes a kept component instance or keyed item as it is now, with the changes its
 * own holes made since it was rendered (targets/web.md §4.4).
 */
final class Markup {
    /** A piece: text the renderer wrote, a value, or a hole whose current content goes here. */
    private sealed interface Part {}

    private record Static(String text) implements Part {}

    private record Value(String html) implements Part {}

    /** A hole's content, between its markers if it has them or {@code alwaysMarked}. */
    private record Embedded(ChildHole hole, boolean alwaysMarked) implements Part {}

    private final List<Part> parts = new ArrayList<>();
    private final StringBuilder current = new StringBuilder();
    /** The length of the HTML as rendered: holes count with the content they had when they were added. */
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
        flushStatic();
        parts.add(new Value(html));
        length += html.length();
        return this;
    }

    /** Appends another markup, keeping its statics, values and holes apart. */
    Markup append(Markup other) {
        flushStatic();
        parts.addAll(other.parts);
        current.append(other.current);
        length += other.length;
        return this;
    }

    /**
     * Appends a hole: its content as it is whenever this markup becomes HTML, between its markers if it has them
     * ({@link ChildHole#hasMarkers}) or {@code alwaysMarked} (an item of a list that can change).
     */
    Markup hole(ChildHole hole, boolean alwaysMarked) {
        flushStatic();
        Embedded embedded = new Embedded(hole, alwaysMarked);
        parts.add(embedded);
        length += expand(embedded).length;
        return this;
    }

    private void flushStatic() {
        if (!current.isEmpty()) {
            parts.add(new Static(current.toString()));
            current.setLength(0);
        }
    }

    /**
     * The number of characters of the HTML as it was rendered. The renderer uses it for positions while it
     * renders, when every hole's content is the one it just rendered.
     */
    int length() {
        return length;
    }

    /** The statics: one more than {@link #values()}. */
    List<String> statics() {
        return flatten().statics;
    }

    List<String> values() {
        return flatten().values;
    }

    /** The HTML, with every hole's current content. */
    @Override
    public String toString() {
        Flat flat = flatten();
        StringBuilder html = new StringBuilder(length);
        for (int i = 0; i < flat.values.size(); i++) {
            html.append(flat.statics.get(i)).append(flat.values.get(i));
        }
        return html.append(flat.statics.getLast()).toString();
    }

    /** Statics and values, with holes expanded into theirs. */
    private record Flat(List<String> statics, List<String> values) {}

    private Flat flatten() {
        List<String> statics = new ArrayList<>();
        List<String> values = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        flattenInto(statics, values, pending);
        statics.add(pending.toString());
        return new Flat(statics, values);
    }

    /** Adds this markup's pieces; {@code pending} is the static text not yet closed by a value. */
    private void flattenInto(List<String> statics, List<String> values, StringBuilder pending) {
        for (Part part : parts) {
            switch (part) {
                case Static text -> pending.append(text.text());
                case Value value -> {
                    statics.add(pending.toString());
                    pending.setLength(0);
                    values.add(value.html());
                }
                case Embedded embedded -> expand(embedded).flattenInto(statics, values, pending);
            }
        }
        pending.append(current);
    }

    /** A hole's current content, with its markers if it has them. */
    private static Markup expand(Embedded embedded) {
        return embedded.hole().currentMarkup(embedded.alwaysMarked());
    }

}
