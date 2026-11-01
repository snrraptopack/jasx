package web;

import jasx.web.State;
import jasx.web.Web;
import java.util.ArrayList;
import java.util.List;

/**
 * The to-do items, shared by every open page: the list is one {@link State}, so when anyone changes it,
 * every page that shows it is updated (targets/web.md §5). A real application would keep the items in a
 * database and set the state after each change.
 */
public final class TodoStore {
    /** One item. */
    public record Todo(int id, String title, boolean done) {}

    private final State<List<Todo>> todos = Web.state(List.of(
            new Todo(1, "Try the counter", true),
            new Todo(2, "Add an item here", false),
            new Todo(3, "Open this page in a second window", false)));
    private int nextId = 4;

    /** The items. Read inside a hole, the hole updates when they change. */
    public List<Todo> all() {
        return todos.get();
    }

    public synchronized void add(String title) {
        List<Todo> next = new ArrayList<>(todos.get());
        next.add(new Todo(nextId++, title, false));
        todos.set(List.copyOf(next));
    }

    public synchronized void toggle(int id) {
        todos.set(todos.get().stream()
                .map(t -> t.id() == id ? new Todo(t.id(), t.title(), !t.done()) : t)
                .toList());
    }

    public synchronized void remove(int id) {
        todos.set(todos.get().stream().filter(t -> t.id() != id).toList());
    }

    public synchronized void clearDone() {
        todos.set(todos.get().stream().filter(t -> !t.done()).toList());
    }
}
