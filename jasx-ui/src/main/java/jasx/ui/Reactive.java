package jasx.ui;

import java.util.function.Supplier;

/**
 * The reactive functions of interactive targets (jasx-spec/ui.md §2). A target usually re-exports them from
 * its factory class, so that one static import is enough ({@code import static jasx.web.Web.*}).
 */
public final class Reactive {
    private Reactive() {}

    /** A reactive value. Holes that read it update when it is set to a different value. */
    public static <T> State<T> state(T initial) {
        return new State<>(initial);
    }

    /** A cached value computed from other reactive values, computed again after one of them changes. */
    public static <T> Derived<T> derived(Supplier<T> fn) {
        return new Derived<>(fn);
    }

    /**
     * Runs {@code fn} now, and again after every update in which a reactive value it read changed. Created in a
     * component, it stops when the hole the component is in re-runs (ui.md §2.3).
     */
    public static Effect effect(Runnable fn) {
        Computation owner = Computation.current();
        Effect effect = new Effect(owner != null ? owner.scheduler() : null, fn);
        effect.update();
        return effect;
    }

    /** Runs {@code body} without subscribing to what it reads: {@code untracked(() -> count.get())}. */
    public static <T> T untracked(Supplier<T> body) {
        return Computation.untracked(body);
    }
}
