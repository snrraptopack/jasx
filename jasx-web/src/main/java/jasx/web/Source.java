package jasx.web;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A reactive value that computations can read: a {@link State} or a {@link Derived} (targets/web.md §4).
 * Reading it inside a running {@link Computation} subscribes that computation; {@link #changed} invalidates
 * every subscriber.
 *
 * <p>A source can be shared by several pages (a chat's messages, say), and each page runs its computations on
 * its own thread, so the observers are a concurrent set.
 */
abstract sealed class Source permits State, Derived {
    private final Set<Computation> observers = ConcurrentHashMap.newKeySet();

    /** Subscribes the computation running now, if any. Call it on every read. */
    final void read() {
        Computation reader = Computation.current();
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

    final void unsubscribe(Computation observer) {
        observers.remove(observer);
    }
}
