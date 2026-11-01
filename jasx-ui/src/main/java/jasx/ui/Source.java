package jasx.ui;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A reactive value that computations can read: a {@link State} or a {@link Derived} (jasx-spec/ui.md §2).
 * Reading it while a computation tracks subscribes that computation; {@link #changed} invalidates every
 * subscriber.
 *
 * <p>A source can be shared by several schedulers (a chat's messages shown on several pages), each running its
 * computations on its own thread, so the observers are a concurrent set.
 */
abstract sealed class Source permits State, Derived {
    private final Set<Computation> observers = ConcurrentHashMap.newKeySet();

    /** Subscribes the computation tracking now, if any. Call it on every read. */
    final void read() {
        Computation reader = Computation.tracker();
        if (reader != null && !reader.isDisposed()) {
            observers.add(reader);
            reader.subscribedTo(this);
        }
    }

    /** Invalidates every subscriber. A subscriber subscribes again when it runs again. */
    final void changed() {
        for (Computation observer : List.copyOf(observers)) {
            observers.remove(observer);
            observer.invalidate();
        }
    }

    /** Subscribes {@code observer} again, after a failed run put its previous run back (ui.md §2.4). */
    final void resubscribe(Computation observer) {
        if (!observer.isDisposed()) {
            observers.add(observer);
        }
    }

    final void unsubscribe(Computation observer) {
        observers.remove(observer);
    }
}
