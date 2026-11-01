package jasx.web;

import jasx.ui.Action;
import jasx.ui.Event;
import jasx.ui.Handler;

/**
 * A handler whose events the browser sends {@code delay} milliseconds after the last one, made by
 * {@link Web#debounce} and {@link Web#immediate} (a delay of 0 sends every event) (targets/web.md §7).
 * It is still an ordinary {@link Handler} or {@link Action}, so it fits the same event attributes.
 */
sealed interface Debounced permits Debounced.OfHandler, Debounced.OfAction {

    /** The delay in milliseconds; 0 sends every event at once. */
    int delay();

    private static void check(int delay) {
        if (delay < 0) {
            throw new IllegalArgumentException("a debounce delay can't be negative: " + delay);
        }
    }

    record OfHandler<E extends Event>(int delay, Handler<E> handler) implements Debounced, Handler<E> {
        public OfHandler {
            check(delay);
        }

        @Override
        public void handle(E event) throws Exception {
            handler.handle(event);
        }
    }

    record OfAction(int delay, Action action) implements Debounced, Action {
        public OfAction {
            check(delay);
        }

        @Override
        public void run() throws Exception {
            action.run();
        }
    }
}
