package jasx.ui;

/**
 * Where computations wait to re-run (jasx-spec/ui.md §3): a target's unit of updates, such as a page of the
 * {@code web} target or a window of a desktop target.
 *
 * <p>When a value a computation read changes, the computation is {@linkplain #schedule scheduled}. The target
 * then re-runs what was scheduled at a moment of its choosing (after an event handler returns, or on its UI
 * thread), by calling {@link Computation#update()} on each, in {@linkplain Computation#order() creation
 * order}, holes before effects.
 *
 * <p>{@code schedule} may be called from any thread: state can be shared and set from a background job.
 */
@FunctionalInterface
public interface Scheduler {

    /** Marks {@code computation} to re-run at the next update. */
    void schedule(Computation computation);
}
