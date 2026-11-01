package jasx.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Something that runs with dependency tracking (targets/web.md §4): a hole, the computation of a
 * {@link Derived} value, or an effect.
 *
 * <p>While it runs ({@link #track}), every {@link Source} it reads subscribes it, and everything created
 * during the run (nested holes, derived values, effects, handlers) is <em>owned</em> by it. When a source
 * changes, the computation is {@link #invalidate invalidated}; before it runs again, everything from its
 * previous run is disposed ({@link #reset}), which is what keeps subscriptions and handlers from piling up
 * (targets/web.md §4.2).
 *
 * <p>A computation belongs to one {@link Page} (null for one created outside any page) and is only run under
 * that page's lock. Sources may be shared between pages, so they keep their observers in a concurrent set.
 */
abstract class Computation {
    /** Creation order: holes and effects are re-run in this order, which is document order (§5). */
    private static final AtomicLong CREATED = new AtomicLong();

    /** The computation running on this thread, if any: it tracks reads and owns what is created. */
    private static final ThreadLocal<Computation> CURRENT = new ThreadLocal<>();

    final Page page;
    final long order = CREATED.incrementAndGet();
    private final List<Computation> owned = new ArrayList<>();
    private final List<Runnable> cleanups = new ArrayList<>();
    private final Set<Source> sources = new LinkedHashSet<>();
    private boolean disposed;

    /** Creates a computation owned by the one running now (if any), so that it is disposed with it. */
    Computation(Page page) {
        this.page = page;
        Computation owner = CURRENT.get();
        if (owner != null) {
            owner.owned.add(this);
        }
    }

    /** The computation running on this thread, or null. */
    static Computation current() {
        return CURRENT.get();
    }

    /** Runs {@code body} as this computation: reads subscribe it, and what is created is owned by it. */
    final <T> T track(Supplier<T> body) {
        Computation previous = CURRENT.get();
        CURRENT.set(this);
        try {
            return body.get();
        } finally {
            CURRENT.set(previous);
        }
    }

    /** Runs {@code body} with no computation: reads subscribe nothing (an event handler, say). */
    static <T> T untracked(Supplier<T> body) {
        Computation previous = CURRENT.get();
        CURRENT.remove();
        try {
            return body.get();
        } finally {
            CURRENT.set(previous);
        }
    }

    /** Called when one of the sources it read changed. */
    abstract void invalidate();

    /** Records that this computation read {@code source}. */
    final void subscribedTo(Source source) {
        sources.add(source);
    }

    /** Runs {@code cleanup} when this computation is reset or disposed (used to drop handlers). */
    final void onReset(Runnable cleanup) {
        cleanups.add(cleanup);
    }

    /**
     * Undoes the previous run: disposes what it created, runs its cleanups and drops its subscriptions, so
     * that the next run starts clean.
     */
    final void reset() {
        for (Computation child : owned) {
            child.dispose();
        }
        owned.clear();
        for (Runnable cleanup : cleanups) {
            cleanup.run();
        }
        cleanups.clear();
        for (Source source : sources) {
            source.unsubscribe(this);
        }
        sources.clear();
    }

    /** Resets this computation for good: it will never run again. */
    final void dispose() {
        reset();
        disposed = true;
    }

    final boolean isDisposed() {
        return disposed;
    }

    /**
     * Whether its last run read any reactive value. One that read none can never be invalidated, so it
     * will never run again.
     */
    final boolean hasSources() {
        return !sources.isEmpty();
    }
}
