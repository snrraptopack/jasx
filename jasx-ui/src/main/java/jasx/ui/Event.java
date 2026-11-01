package jasx.ui;

/**
 * An event, as a {@link Handler} receives it (jasx-spec/ui.md §4). Each event attribute of a target gives its
 * handler the most specific kind the event has:
 *
 * <table>
 *   <caption>Event kinds</caption>
 *   <tr><th>Kind</th><th>Events</th></tr>
 *   <tr><td>{@link InputEvent}</td><td>{@code input}, {@code beforeinput}, {@code change}, {@code select},
 *       {@code invalid}, {@code focus}, {@code blur}</td></tr>
 *   <tr><td>{@link KeyEvent}</td><td>{@code keydown}, {@code keyup}, {@code keypress}</td></tr>
 *   <tr><td>{@link MouseEvent}</td><td>{@code click}, {@code dblclick}, {@code auxclick},
 *       {@code contextmenu}, {@code mousedown}, {@code mouseup}, {@code mousemove}, {@code mouseover},
 *       {@code mouseout}, {@code mouseenter}, {@code mouseleave}, {@code wheel}, {@code drag…},
 *       {@code drop}</td></tr>
 *   <tr><td>{@link FormEvent}</td><td>{@code submit}</td></tr>
 *   <tr><td>{@code Event}</td><td>every other event</td></tr>
 * </table>
 *
 * <p>A handler that needs nothing from its event is an {@link Action}: {@code onclick={() -> save()}}.
 */
public interface Event {

    /** The event's type, such as {@code click} or {@code submit}. */
    String type();
}
