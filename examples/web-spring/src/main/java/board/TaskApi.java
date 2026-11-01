package board;

import board.TaskService.Task;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A plain REST API next to the pages. It changes the same {@link TaskService}, so a task added here appears at once
 * in every open board:
 *
 * <pre>
 *   curl -X POST localhost:8080/api/tasks -d title="Ship it" -d assignee=Yaw
 * </pre>
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskApi {
    private final TaskService tasks;

    public TaskApi(TaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    public List<Task> all() {
        return tasks.all();
    }

    @PostMapping
    public Task add(@RequestParam String title, @RequestParam(defaultValue = "Ama") String assignee) {
        return tasks.add(title, assignee);
    }
}
