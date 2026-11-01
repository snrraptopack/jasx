package jasx.ui;

/**
 * An event handler that receives its event: a lambda {@code e -> ...} given to an event attribute. The event
 * attribute decides the kind of event (see {@link Event}): {@code onsubmit} gives a {@link FormEvent},
 * {@code oninput} an {@link InputEvent}, and so on (jasx-spec/ui.md §4.1).
 *
 * <p>It may throw: the target reports the exception and keeps the page or window usable.
 */
@FunctionalInterface
public interface Handler<E extends Event> {
    void handle(E event) throws Exception;
}
