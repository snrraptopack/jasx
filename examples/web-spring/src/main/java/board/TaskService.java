package board;

import jasx.ui.State;
import jasx.web.Web;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;

/**
 * The board's tasks, an ordinary Spring bean. Its data are {@link State}s, so every page showing them updates when
 * they change, whoever changes them: a page's form, another browser, or the REST API ({@link TaskApi}). A real
 * application would save to a database here and then set the state.
 */
@Service
public class TaskService {
    /** The people a task can be assigned to. */
    public static final List<String> PEOPLE = List.of("Ama", "Kofi", "Esi", "Yaw");

    /** One task. */
    public record Task(long id, String title, String assignee, boolean done, List<String> comments) {
        /** Whether the board's filter ("all", "open" or "done") shows this task. */
        public boolean shownBy(String filter) {
            return switch (filter) {
                case "open" -> !done;
                case "done" -> done;
                default -> true;
            };
        }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final State<List<Task>> tasks = Web.state(List.of(
            new Task(1, "Set up the Spring Boot project", "Ama", true, List.of("Done with the starter.")),
            new Task(2, "Write the board page in JASX", "Kofi", false, List.of()),
            new Task(3, "Open this board in a second window", "Esi", false, List.of())));
    private final State<List<String>> activity = Web.state(List.of());
    private long nextId = 4;

    // ---------------------------------------------------------------------------------------------
    // Reading: inside a {...} of a page, these make it update when the tasks change
    // ---------------------------------------------------------------------------------------------

    public List<Task> all() {
        return tasks.get();
    }

    public Optional<Task> find(long id) {
        return tasks.get().stream().filter(t -> t.id() == id).findFirst();
    }

    public long openCount() {
        return tasks.get().stream().filter(t -> !t.done()).count();
    }

    /** The latest changes, newest first. */
    public List<String> activity() {
        return activity.get();
    }

    // ---------------------------------------------------------------------------------------------
    // Changing
    // ---------------------------------------------------------------------------------------------

    public synchronized Task add(String title, String assignee) {
        Task task = new Task(nextId++, title, assignee, false, List.of());
        List<Task> next = new ArrayList<>(tasks.get());
        next.add(task);
        tasks.set(List.copyOf(next));
        log(assignee + " got \"" + title + "\"");
        return task;
    }

    public synchronized void toggle(long id) {
        change(id, t -> new Task(t.id(), t.title(), t.assignee(), !t.done(), t.comments()));
        find(id).ifPresent(t -> log("\"" + t.title() + "\" is " + (t.done() ? "done" : "open again")));
    }

    public synchronized void rename(long id, String title) {
        change(id, t -> new Task(t.id(), title, t.assignee(), t.done(), t.comments()));
        log("A task was renamed \"" + title + "\"");
    }

    public synchronized void assign(long id, String person) {
        change(id, t -> new Task(t.id(), t.title(), person, t.done(), t.comments()));
        find(id).ifPresent(t -> log(person + " took \"" + t.title() + "\""));
    }

    public synchronized void comment(long id, String text) {
        change(id, t -> {
            List<String> comments = new ArrayList<>(t.comments());
            comments.add(text);
            return new Task(t.id(), t.title(), t.assignee(), t.done(), List.copyOf(comments));
        });
        log("New comment: " + text);
    }

    public synchronized void remove(long id) {
        find(id).ifPresent(t -> log("\"" + t.title() + "\" was deleted"));
        tasks.set(tasks.get().stream().filter(t -> t.id() != id).toList());
    }

    private void change(long id, UnaryOperator<Task> update) {
        tasks.set(tasks.get().stream().map(t -> t.id() == id ? update.apply(t) : t).toList());
    }

    private void log(String line) {
        List<String> next = new ArrayList<>();
        next.add(LocalTime.now().format(TIME) + "  " + line);
        next.addAll(activity.get());
        activity.set(List.copyOf(next.subList(0, Math.min(6, next.size()))));
    }
}
