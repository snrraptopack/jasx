package jasx.web;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A reactive value, made by {@link Web#state} (targets/web.md §4). Holes, {@link Derived} values and effects
 * that read it are updated when it is set to a different value.
 *
 * <p>It may be set from any thread: inside an event handler the change is sent with the handler's answer;
 * from elsewhere (a scheduled job, a message listener) it is pushed to every page that shows it (§5).
 */
public final class State<T> extends Source {
    private volatile T value;

    State(T initial) {
        this.value = initial;
    }

    /** The current value. Read inside a hole, it makes the hole update when the value changes. */
    public T get() {
        read();
        return value;
    }

    /** Sets the value. Setting an equal value ({@link Objects#equals}) changes nothing. */
    public void set(T newValue) {
        synchronized (this) {
            if (Objects.equals(value, newValue)) {
                return;
            }
            value = newValue;
        }
        changed();
    }

    /** Sets the value to {@code fn} applied to the current one, such as {@code count.update(n -> n + 1)}. */
    public void update(UnaryOperator<T> fn) {
        T newValue;
        synchronized (this) {
            newValue = fn.apply(value);
        }
        set(newValue);
    }

    /**
     * The value's string form. As a child, {@code {count}} is therefore the same as {@code {count.get()}}
     * (semantics.md §3.4: any other value is text), and the hole updates when the value changes.
     */
    @Override
    public String toString() {
        return String.valueOf(get());
    }
}
