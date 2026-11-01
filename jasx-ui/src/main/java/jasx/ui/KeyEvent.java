package jasx.ui;

/**
 * A keyboard event (jasx-spec/ui.md §4): {@code keydown}, {@code keyup}, {@code keypress}. On a field, it also
 * carries the field's value, so {@code onkeydown={e -> { if (e.key().equals("Enter")) send(e.value()); }}}
 * needs nothing else.
 */
public interface KeyEvent extends InputEvent {

    /** The key, as the platform names it: {@code "a"}, {@code "Enter"}, {@code "ArrowUp"}, {@code "Escape"}. */
    String key();

    boolean ctrl();

    boolean shift();

    boolean alt();

    /** The Command key on macOS, the Windows key on Windows. */
    boolean meta();
}
