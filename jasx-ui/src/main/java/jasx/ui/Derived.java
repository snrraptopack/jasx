package jasx.ui;

import java.util.function.Supplier;

/**
 * A value computed from other reactive values, made by {@link Reactive#derived} (jasx-spec/ui.md §2). It is
 * computed when first read, cached, and computed again on the next read after one of the values it read
 * changes. Reading it inside a hole makes the hole update when it changes.
 */
public final class Derived<T> extends Source {
    private final Supplier<T> fn;
    private final Computation computation;
    private T value;
    private boolean stale = true;
    /** Whether its readers were told it is stale since it was last computed; then they needn't be told again. */
    private boolean readersTold;

    Derived(Supplier<T> fn) {
        this.fn = fn;
        Computation owner = Computation.current();
        this.computation = new Computation(owner != null ? owner.scheduler() : null) {
            @Override
            protected void invalidate() {
                markStale(); // computed lazily, on the next read: nothing to schedule
            }

            @Override
            public void update() {
                markStale();
            }
        };
    }

    /** The current value, computed now if one of the values it depends on changed since the last read. */
    public T get() {
        read();
        synchronized (this) {
            if (stale) {
                computation.reset(); // drops the subscriptions of the previous computation
                try {
                    value = computation.track(fn);
                } catch (RuntimeException | Error failure) {
                    // Still stale. Readers that failed with it retry when one of its values changes, so the
                    // next change must tell them again.
                    readersTold = false;
                    throw failure;
                }
                stale = false;
                readersTold = false;
            }
            return value;
        }
    }

    /** A value it read changed: compute again on the next read, and tell its own readers. */
    private void markStale() {
        synchronized (this) {
            if (stale && readersTold) {
                return; // nothing was computed since they were told
            }
            stale = true;
            readersTold = true;
        }
        changed();
    }

    /** The value's string form, so {@code {doubled}} as a child is {@code {doubled.get()}} (see {@link State#toString}). */
    @Override
    public String toString() {
        return String.valueOf(get());
    }
}
