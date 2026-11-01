package jasx.ui;

import java.util.List;

/**
 * An event of a form field (jasx-spec/ui.md §4): {@code input}, {@code change}, {@code focus}, {@code blur} and
 * the like. It carries the field's value as it is when the event is sent.
 */
public interface InputEvent extends Event {

    /** The value of the field that has the handler; the empty string if it isn't a field. */
    String value();

    /** Whether the field that has the handler is a checked checkbox or radio button. */
    boolean checked();

    /**
     * The selected values of a {@code <select multiple>}; for any other field, a list holding {@link #value()}
     * (or no element if it isn't a field).
     */
    List<String> values();
}
