package jasx.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Something that runs with dependency tracking (jasx-spec/ui.md §2): a target's hole, an {@link Effect}, or the
 * computation of a {@link Derived} value.
 *
 * <p>While it runs ({@link #track}), every {@link State} or {@link Derived} it reads subscribes it, and
 * everything created during the run (nested holes, derived values, effects, a target's handlers) is
 * <em>owned</em> by it. When a value it read changes, it is {@linkplain #invalidate invalidated}, which by
 * default schedules it with its {@link Scheduler}. Before it runs again, everything from its previous run is
 * disposed ({@link #reset}), so subscriptions and handlers never pile up (ui.md §2.3).
 *
 * <p>Two things are tracked per thread: the <em>owner</em> (what newly created computations belong to) and
 * whether reads <em>subscribe</em>. {@link #track} sets both; {@link #untracked} and {@link #runWithOwner} keep
 * or set the owner without subscribing, which is how a target runs event handlers.
 *
 * <p>A computation is only run by its target, one thread at a time (a page's lock, a UI thread). Sources may be
 * shared between schedulers, so they keep their observers in a concurrent set.
 */
public abstract class Computation {
    /** Creation order: holes and effects re-run in this order, which is document order (ui.md §3). */
    private static final AtomicLong CREATED = new AtomicLong();

    /** What runs on this thread: the owner of new computations, and whether reads subscribe it. */
    private record Context(Computation owner, boolean tracking) {}

    private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();

    private final Scheduler scheduler;
    private final long order = CREATED.incrementAndGet();
    private final List<Computation> owned = new ArrayList<>();
    private final List<Runnable> cleanups = new ArrayList<>();
    private final Set<Source> sources = new LinkedHashSet<>();
    private boolean disposed;

    /**
     * Creates a computation owned by the one running now (if any), so that it is disposed with it.
     *
     * @param scheduler where it waits to re-run; null for one that re-runs at once when invalidated
     */
    // The owner only stores the reference, to dispose it later: nothing calls into the unfinished subclass.
    @SuppressWarnings("this-escape")
    protected Computation(Scheduler scheduler) {
        this.scheduler = scheduler;
        Computation owner = current();
        if (owner != null) {
            owner.owned.add(this);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // What runs on this thread
    // ---------------------------------------------------------------------------------------------

    /** The owner on this thread: the computation running now, or the one set by {@link #runWithOwner}. */
    public static Computation current() {
        Context context = CONTEXT.get();
        return context != null ? context.owner() : null;
    }

    /** The computation that reads subscribe now, or null if reads are not tracked. */
    static Computation tracker() {
        Context context = CONTEXT.get();
        return context != null && context.tracking() ? context.owner() : null;
    }

    /** Runs {@code body} as this computation: reads subscribe it, and what is created is owned by it. */
    protected final <T> T track(Supplier<T> body) {
        return with(new Context(this, true), body);
    }

    /** Runs {@code body} without subscribing to what it reads, keeping the current owner. */
    public static <T> T untracked(Supplier<T> body) {
        return with(new Context(current(), false), body);
    }

    /**
     * Runs {@code body} with {@code owner} owning what it creates, and without subscribing to what it reads.
     * A target runs event handlers this way, with its page or window as the owner.
     */
    public static <T> T runWithOwner(Computation owner, Supplier<T> body) {
        return with(owner != null ? new Context(owner, false) : null, body);
    }

    private static <T> T with(Context context, Supplier<T> body) {
        Context previous = CONTEXT.get();
        CONTEXT.set(context);
        try {
            return body.get();
        } finally {
            CONTEXT.set(previous);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Running again
    // ---------------------------------------------------------------------------------------------

    /** Where it waits to re-run, or null. */
    public final Scheduler scheduler() {
        return scheduler;
    }

    /** Its creation order, the order in which a scheduler re-runs computations (ui.md §3). */
    public final long order() {
        return order;
    }

    /**
     * Called when a value it read changed. By default it is scheduled, or updated at once if it has no
     * scheduler.
     */
    protected void invalidate() {
        if (scheduler != null) {
            scheduler.schedule(this);
        } else {
            update();
        }
    }

    /** Runs again now, after a value it read changed. Its scheduler calls it. */
    public abstract void update();

    // ---------------------------------------------------------------------------------------------
    // Subscriptions and ownership
    // ---------------------------------------------------------------------------------------------

    /** Records that this computation read {@code source}. */
    final void subscribedTo(Source source) {
        sources.add(source);
    }

    /** Runs {@code cleanup} when this computation is reset or disposed (a target drops handlers this way). */
    public final void onCleanup(Runnable cleanup) {
        cleanups.add(cleanup);
    }

    /**
     * Undoes the previous run: disposes what it created, runs its cleanups and drops its subscriptions, so
     * that the next run starts clean.
     */
    protected final void reset() {
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
    public final void dispose() {
        reset();
        disposed = true;
    }

    public final boolean isDisposed() {
        return disposed;
    }

    /**
     * Whether its last run read any reactive value. One that read none can never be invalidated, so it will
     * never run again (a target can skip what it would need to update it).
     */
    protected final boolean hasSources() {
        return !sources.isEmpty();
    }
}
