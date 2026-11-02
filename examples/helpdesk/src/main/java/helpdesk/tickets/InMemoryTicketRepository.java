package helpdesk.tickets;

import helpdesk.tickets.Ticket.Message;
import helpdesk.tickets.Ticket.Priority;
import helpdesk.tickets.Ticket.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Repository;

/** Tickets in memory, with a few to start with. Thread-safe: requests from several pages arrive at once. */
@Repository
public class InMemoryTicketRepository implements TicketRepository {
    private final Map<Long, Ticket> tickets = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    public InMemoryTicketRepository() {
        Instant now = Instant.now();
        seed("Can't sign in after changing my password", "kwame@example.com", Status.OPEN, Priority.URGENT, null,
                now.minus(Duration.ofMinutes(12)), "Since this morning the app says my password is wrong, but I reset it twice.");
        seed("Invoice shows the wrong company name", "adjoa@example.com", Status.OPEN, Priority.NORMAL, "Kofi",
                now.minus(Duration.ofHours(3)), "Our March invoice still has our old name. Could you send a corrected one?");
        seed("Export to CSV is empty", "mensah@example.com", Status.PENDING, Priority.NORMAL, "Ama",
                now.minus(Duration.ofDays(1)), "When I export last month's orders the file has only the header row.");
        seed("How do I add a second admin?", "efua@example.com", Status.SOLVED, Priority.LOW, "Esi",
                now.minus(Duration.ofDays(2)), "We'd like two people to manage the account.");
    }

    private void seed(String subject, String requester, Status status, Priority priority, String assignee, Instant at,
            String body) {
        long id = nextId();
        Message first = new Message(requester, body, at, false, List.of());
        tickets.put(id, new Ticket(id, subject, requester, status, priority, assignee, at, at, List.of(first)));
    }

    @Override
    public List<Ticket> findAll() {
        List<Ticket> all = new ArrayList<>(tickets.values());
        all.sort(Comparator.comparing(Ticket::updatedAt).reversed());
        return List.copyOf(all);
    }

    @Override
    public Optional<Ticket> findById(long id) {
        return Optional.ofNullable(tickets.get(id));
    }

    @Override
    public long nextId() {
        return ids.incrementAndGet();
    }

    @Override
    public void save(Ticket ticket) {
        tickets.put(ticket.id(), ticket);
    }

    @Override
    public void deleteById(long id) {
        tickets.remove(id);
    }
}
