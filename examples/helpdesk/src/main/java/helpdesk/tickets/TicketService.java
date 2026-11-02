package helpdesk.tickets;

import helpdesk.tickets.Ticket.Attachment;
import helpdesk.tickets.Ticket.Message;
import helpdesk.tickets.Ticket.Priority;
import helpdesk.tickets.Ticket.Status;
import jasx.ui.State;
import jasx.web.Web;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;

/**
 * The helpdesk's use cases: opening, answering, moving and assigning tickets, and who is looking at which.
 *
 * <p>Every change is saved to the {@link TicketRepository}, then published through a {@link State}: each open page
 * that shows tickets reads that state, so it updates when anyone changes anything, from a page, another agent's
 * browser or the REST API. Reads inside a page's {@code {...}} make that part of the page follow the change.
 */
@Service
public class TicketService {
    /** One line of the activity feed. */
    public record Activity(String text, Instant at) {}

    /** A file to attach, as it arrived (from a form or an API). */
    public record Upload(String name, String contentType, byte[] content) {}

    private final TicketRepository repository;
    private final AttachmentStore attachments;

    private final State<List<Ticket>> tickets;
    private final State<List<Activity>> activity = Web.state(List.of());
    /** Who has each ticket open, once per page: an agent with two windows on it is there twice. */
    private final State<Map<Long, List<String>>> viewers = Web.state(Map.of());

    public TicketService(TicketRepository repository, AttachmentStore attachments) {
        this.repository = repository;
        this.attachments = attachments;
        this.tickets = Web.state(repository.findAll());
    }

    // ---------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------

    /** All tickets, the latest changed first. */
    public List<Ticket> all() {
        return tickets.get();
    }

    public Optional<Ticket> find(long id) {
        return tickets.get().stream().filter(t -> t.id() == id).findFirst();
    }

    /** The tickets in {@code status} (all if null) whose subject or requester contains {@code query}. */
    public List<Ticket> search(String query, Status status) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return tickets.get().stream()
                .filter(t -> status == null || t.status() == status)
                .filter(t -> needle.isEmpty() || t.subject().toLowerCase(Locale.ROOT).contains(needle)
                        || t.requester().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    /** How many tickets are in each status, in the order of {@link Status}: {@code {"Open": 2, ...}}. */
    public Map<String, Integer> countsByStatus() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Status status : Status.values()) {
            counts.put(status.label(), (int) tickets.get().stream().filter(t -> t.status() == status).count());
        }
        return counts;
    }

    /** How many unsolved tickets each agent has, and {@code "Nobody"} for the unassigned ones. */
    public Map<String, Integer> workload(List<String> agents) {
        Map<String, Integer> load = new LinkedHashMap<>();
        agents.forEach(agent -> load.put(agent, 0));
        load.put("Nobody", 0);
        for (Ticket t : tickets.get()) {
            if (t.status() != Status.SOLVED) {
                load.merge(t.isAssigned() ? t.assignee() : "Nobody", 1, Integer::sum);
            }
        }
        return load;
    }

    /** The latest changes, newest first. */
    public List<Activity> activity() {
        return activity.get();
    }

    /** The agents who have ticket {@code id} open, each once. */
    public List<String> viewersOf(long id) {
        return viewers.get().getOrDefault(id, List.of()).stream().distinct().toList();
    }

    // ---------------------------------------------------------------------------------------------
    // Changing
    // ---------------------------------------------------------------------------------------------

    /** Opens a ticket. {@code problems()} must be empty: callers show them to whoever filled the form in. */
    public synchronized Ticket open(NewTicket ticket, String by) {
        if (!ticket.problems().isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", ticket.problems()));
        }
        Instant now = Instant.now();
        Message first = new Message(ticket.requester(), ticket.body(), now, false, List.of());
        Ticket created = new Ticket(repository.nextId(), ticket.subject(), ticket.requester(), Status.OPEN,
                ticket.priority(), null, now, now, List.of(first));
        repository.save(created);
        publish(by + " opened #" + created.id() + " \"" + created.subject() + "\"");
        return created;
    }

    /** Adds a reply, or an internal note, with its files. A reply to a solved ticket opens it again. */
    public synchronized void reply(long id, String author, String body, boolean internal, List<Upload> uploads) {
        List<Attachment> stored = new ArrayList<>();
        for (Upload upload : uploads) {
            stored.add(attachments.store(upload.name(), upload.contentType(), upload.content()));
        }
        Message message = new Message(author, body.strip(), Instant.now(), internal, stored);
        change(id, t -> {
            Ticket answered = t.withMessage(message);
            return !internal && t.status() == Status.SOLVED ? answered.withStatus(Status.OPEN, message.at()) : answered;
        });
        publish(author + (internal ? " added a note to #" : " replied to #") + id);
    }

    public synchronized void move(long id, Status status, String by) {
        Ticket before = find(id).orElse(null);
        if (before == null || before.status() == status) {
            return;
        }
        change(id, t -> t.withStatus(status, Instant.now()));
        publish(by + " moved #" + id + " to " + status.label());
    }

    /** Assigns the ticket to {@code agent}, or to nobody if it is null or empty. */
    public synchronized void assign(long id, String agent, String by) {
        String assignee = agent == null || agent.isBlank() ? null : agent;
        change(id, t -> t.withAssignee(assignee, Instant.now()));
        publish(by + (assignee == null ? " unassigned #" + id : " assigned #" + id + " to " + assignee));
    }

    public synchronized void prioritize(long id, Priority priority, String by) {
        change(id, t -> t.withPriority(priority, Instant.now()));
        publish(by + " set #" + id + " to " + priority.label());
    }

    public synchronized void delete(long id, String by) {
        repository.deleteById(id);
        publish(by + " deleted #" + id);
    }

    /** Records that {@code agent} has ticket {@code id} open in one more page. */
    public synchronized void join(long id, String agent) {
        Map<Long, List<String>> next = new HashMap<>(viewers.get());
        List<String> names = new ArrayList<>(next.getOrDefault(id, List.of()));
        names.add(agent);
        next.put(id, List.copyOf(names));
        viewers.set(Map.copyOf(next));
    }

    /** Undoes one {@link #join}: the page closed. */
    public synchronized void leave(long id, String agent) {
        Map<Long, List<String>> next = new HashMap<>(viewers.get());
        List<String> names = new ArrayList<>(next.getOrDefault(id, List.of()));
        names.remove(agent);
        if (names.isEmpty()) {
            next.remove(id);
        } else {
            next.put(id, List.copyOf(names));
        }
        viewers.set(Map.copyOf(next));
    }

    private void change(long id, UnaryOperator<Ticket> change) {
        repository.findById(id).ifPresent(t -> repository.save(change.apply(t)));
    }

    /** Publishes the repository's tickets to every page, with a line in the activity feed. */
    private void publish(String line) {
        tickets.set(repository.findAll());
        List<Activity> next = new ArrayList<>(activity.get());
        next.addFirst(new Activity(line, Instant.now()));
        activity.set(List.copyOf(next.subList(0, Math.min(next.size(), 20))));
    }
}
