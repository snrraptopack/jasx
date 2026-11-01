package jasx.ui;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A reactive value, made by {@link Reactive#state} (jasx-spec/ui.md §2). Holes, {@link Derived} values and
 * effects that read it are updated when it is set to a different value.
 *
 * <p>It may be set from any thread: inside an event handler the change is part of the handler's update; from
 * elsewhere (a scheduled job, a message listener), every scheduler showing it is told (ui.md §3).
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

    /** The current value, without subscribing the computation running now. */
    public T peek() {
        return value;
    }

    /** Sets the value. Setting an equal value ({@link Objects#equals}) changes nothing. */
    public void set(T newValue) {
        update(current -> newValue);
    }

    /**
     * Sets the value to {@code fn} applied to the current one, such as {@code count.update(n -> n + 1)}.
     *
     * <p>Reading, applying {@code fn} and storing happen as one step, so two threads updating a shared state at
     * the same time never lose a change. Subscribers are told afterwards, outside the lock, so they can't
     * deadlock with it. Keep {@code fn} short and free of side effects: it runs while other updates wait.
     */
    public void update(UnaryOperator<T> fn) {
        synchronized (this) {
            T newValue = fn.apply(value);
            if (Objects.equals(value, newValue)) {
                return;
            }
            value = newValue;
        }
        changed();
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
