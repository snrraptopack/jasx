package jasx.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An event from the browser, as a {@link Handler} receives it (targets/web.md §7, §8.3).
 *
 * <p>For a {@code submit}, the form's fields are available by name; for an event on a form field, its
 * {@link #value()} and {@link #checked()}; for a keyboard event, its {@link #key()}.
 */
public final class Event {
    private final String type;
    private final String value;
    private final boolean checked;
    private final String key;
    private final Map<String, List<String>> fields;

    Event(String type, String value, boolean checked, String key, Map<String, List<String>> fields) {
        this.type = type;
        this.value = value;
        this.checked = checked;
        this.key = key;
        this.fields = fields;
    }

    /** Reads an event sent by the browser client. */
    @SuppressWarnings("unchecked")
    static Event fromJson(Map<String, Object> json) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        if (json.get("fields") instanceof Map<?, ?> map) {
            map.forEach((name, values) -> fields.put((String) name, List.copyOf((List<String>) values)));
        }
        return new Event((String) json.get("type"), (String) json.get("value"),
                Boolean.TRUE.equals(json.get("checked")), (String) json.get("key"), fields);
    }

    /** The DOM event type, such as {@code click} or {@code submit}. */
    public String type() {
        return type;
    }

    /**
     * The value of the element that has the handler, if it is a form field ({@code input}, {@code select},
     * {@code textarea}); otherwise the empty string.
     */
    public String value() {
        return value != null ? value : "";
    }

    /** Whether the element that has the handler is a checked checkbox or radio button. */
    public boolean checked() {
        return checked;
    }

    /** The key of a keyboard event, such as {@code Enter}; otherwise the empty string. */
    public String key() {
        return key != null ? key : "";
    }

    /** The first value of form field {@code name} (for a {@code submit}), or the empty string if there is none. */
    public String field(String name) {
        List<String> values = fields.get(name);
        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    /** Every value of form field {@code name}, such as the checked boxes of a group. */
    public List<String> fields(String name) {
        return fields.getOrDefault(name, List.of());
    }

    /** The names of the form's fields, in form order. */
    public Set<String> fieldNames() {
        return fields.keySet();
    }

    /**
     * Clears the form this event came from (its fields go back to their initial values), after the event's
     * other changes (targets/web.md §7). Typed text belongs to the browser, so the server can't clear it
     * by setting state; this asks the browser to.
     *
     * @throws IllegalStateException outside the handling of this event
     */
    public void resetForm() {
        Page page = Page.current();
        if (page == null) {
            throw new IllegalStateException("resetForm() must be called while the event is handled");
        }
        page.addPatch(new LinkedHashMap<>(Map.of("op", "reset")));
    }

    @Override
    public String toString() {
        return "Event[" + type + "]";
    }
}
