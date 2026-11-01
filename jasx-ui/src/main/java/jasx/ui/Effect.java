package jasx.ui;

/**
 * An effect, made by {@link Reactive#effect} (jasx-spec/ui.md §2): it runs once when created, and again after
 * every update in which a value it read changed. A scheduler re-runs effects after the holes (ui.md §3).
 */
public final class Effect extends Computation {
    private final Runnable fn;

    Effect(Scheduler scheduler, Runnable fn) {
        super(scheduler);
        this.fn = fn;
    }

    /** Runs the effect again, subscribing to what it reads this time. */
    @Override
    public void update() {
        reset();
        track(() -> {
            fn.run();
            return null;
        });
    }
}
