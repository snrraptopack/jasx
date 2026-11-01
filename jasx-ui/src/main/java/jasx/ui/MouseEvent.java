package jasx.ui;

/**
 * A pointer event (jasx-spec/ui.md §4): {@code click}, {@code dblclick}, {@code mousedown}, {@code wheel} and
 * the like.
 */
public interface MouseEvent extends Event {

    /** The button: 0 for the main button (usually the left), 1 for the middle, 2 for the secondary. */
    int button();

    /** The horizontal position of the pointer in the window's content area, in CSS pixels (or the platform's). */
    double x();

    /** The vertical position of the pointer in the window's content area. */
    double y();

    boolean ctrl();

    boolean shift();

    boolean alt();

    /** The Command key on macOS, the Windows key on Windows. */
    boolean meta();
}
