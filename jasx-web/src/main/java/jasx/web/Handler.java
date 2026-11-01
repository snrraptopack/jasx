package jasx.web;

/**
 * An event handler: a lambda {@code e -> ...} given to an event attribute such as {@code onclick}. It runs
 * on the server when the event happens in the browser (targets/web.md §7).
 */
@FunctionalInterface
public interface Handler {
    void handle(Event event) throws Exception;

    /**
     * A handler whose events the browser sends {@code delay} milliseconds after the last one, made by
     * {@link Web#debounce} and {@link Web#immediate} (a delay of 0 sends every event).
     */
    record Debounced(int delay, Handler handler) implements Handler {
        public Debounced {
            if (delay < 0) {
                throw new IllegalArgumentException("a debounce delay can't be negative: " + delay);
            }
        }

        @Override
        public void handle(Event event) throws Exception {
            handler.handle(event);
        }
    }
}
