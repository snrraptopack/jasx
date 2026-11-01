package jasx.web;

import jasx.ui.FormEvent;
import jasx.ui.KeyEvent;
import jasx.ui.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An event sent by the browser (targets/web.md §7, §8.3), as every kind of {@code jasx.ui} event at once. A
 * handler sees it through the kind its event attribute declares ({@code onsubmit} gives a {@link FormEvent},
 * {@code onkeydown} a {@link KeyEvent}); the client sends what that kind needs, and the rest is empty.
 */
final class WebEvent implements FormEvent, KeyEvent, MouseEvent {
    private final String type;
    private final String value;
    private final boolean checked;
    private final List<String> values;
    private final String key;
    private final boolean ctrl;
    private final boolean shift;
    private final boolean alt;
    private final boolean meta;
    private final int button;
    private final double x;
    private final double y;
    private final Map<String, List<String>> fields;
    /** The browser's number for this event, so that a reset patch can name the form it answers (§8.4). */
    private final long seq;

    private WebEvent(Map<String, Object> json) {
        this.type = string(json, "type");
        this.value = string(json, "value");
        this.checked = flag(json, "checked");
        this.values = strings(json.get("values"));
        this.key = string(json, "key");
        this.ctrl = flag(json, "ctrl");
        this.shift = flag(json, "shift");
        this.alt = flag(json, "alt");
        this.meta = flag(json, "meta");
        this.button = (int) number(json, "button");
        this.x = number(json, "x");
        this.y = number(json, "y");
        this.fields = fields(json.get("fields"));
        this.seq = (long) number(json, "seq");
    }

    /** Reads an event sent by the browser client (§8.3). Missing members are empty. */
    static WebEvent fromJson(Map<String, Object> json) {
        return new WebEvent(json);
    }

    // Event

    @Override
    public String type() {
        return type;
    }

    // InputEvent

    @Override
    public String value() {
        return value;
    }

    @Override
    public boolean checked() {
        return checked;
    }

    @Override
    public List<String> values() {
        if (!values.isEmpty()) {
            return values; // a <select multiple>
        }
        return value.isEmpty() ? List.of() : List.of(value);
    }

    // KeyEvent and MouseEvent

    @Override
    public String key() {
        return key;
    }

    @Override
    public boolean ctrl() {
        return ctrl;
    }

    @Override
    public boolean shift() {
        return shift;
    }

    @Override
    public boolean alt() {
        return alt;
    }

    @Override
    public boolean meta() {
        return meta;
    }

    @Override
    public int button() {
        return button;
    }

    @Override
    public double x() {
        return x;
    }

    @Override
    public double y() {
        return y;
    }

    // FormEvent

    @Override
    public String field(String name) {
        List<String> all = fields(name);
        return all.isEmpty() ? "" : all.get(0);
    }

    @Override
    public List<String> fields(String name) {
        return fields.getOrDefault(name, List.of());
    }

    @Override
    public Set<String> fieldNames() {
        return fields.keySet();
    }

    /**
     * Asks the browser to reset the form the event came from, after the event's other changes (§7, the
     * {@code reset} patch of §8.4).
     *
     * @throws IllegalStateException outside the handling of this event
     */
    @Override
    public void resetForm() {
        Page page = Page.current();
        if (page == null) {
            throw new IllegalStateException("resetForm() must be called while the event is handled");
        }
        // The patch names its event: the batch may be applied after others, or replayed after a reconnection,
        // when the browser no longer knows which response it came in.
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("op", "reset");
        patch.put("event", seq);
        page.addPatch(patch);
    }

    @Override
    public String toString() {
        return "WebEvent[" + type + "]";
    }

    // ---------------------------------------------------------------------------------------------
    // Reading the JSON leniently: a member the client didn't send is empty
    // ---------------------------------------------------------------------------------------------

    private static String string(Map<String, Object> json, String name) {
        return json.get(name) instanceof String s ? s : "";
    }

    private static boolean flag(Map<String, Object> json, String name) {
        return Boolean.TRUE.equals(json.get(name));
    }

    private static double number(Map<String, Object> json, String name) {
        return json.get(name) instanceof Number n ? n.doubleValue() : 0;
    }

    private static List<String> strings(Object list) {
        if (!(list instanceof List<?> items)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof String s) {
                result.add(s);
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, List<String>> fields(Object object) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (object instanceof Map<?, ?> map) {
            map.forEach((name, values) -> result.put(String.valueOf(name), strings(values)));
        }
        return result;
    }
}
