package jasx.web;

import java.util.function.Supplier;

/**
 * A value computed from other reactive values, made by {@link Web#derived} (targets/web.md §4). It is
 * computed when first read, cached, and computed again on the next read after one of the values it read
 * changes. Reading it inside a hole makes the hole update when it changes.
 */
public final class Derived<T> extends Source {
    private final Supplier<T> fn;
    private final Computation computation;
    private T value;
    private boolean stale = true;

    Derived(Supplier<T> fn) {
        this.fn = fn;
        Computation owner = Computation.current();
        this.computation = new Computation(owner != null ? owner.page : null) {
            @Override
            void invalidate() {
                markStale();
            }
        };
    }

    /** The current value, computed now if one of the values it depends on changed since the last read. */
    public T get() {
        read();
        synchronized (this) {
            if (stale) {
                computation.reset();
                value = computation.track(fn);
                stale = false;
            }
            return value;
        }
    }

    /** A value it read changed: compute again on the next read, and tell its own readers. */
    private void markStale() {
        synchronized (this) {
            if (stale) {
                return; // its readers were already told
            }
            stale = true;
        }
        changed();
    }

    /** The value's string form, so {@code {doubled}} as a child is {@code {doubled.get()}} (see {@link State#toString}). */
    @Override
    public String toString() {
        return String.valueOf(get());
    }
}
