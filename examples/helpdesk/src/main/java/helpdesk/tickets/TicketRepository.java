package helpdesk.tickets;

import java.util.List;
import java.util.Optional;

/**
 * Where tickets are kept. The example keeps them in memory ({@link InMemoryTicketRepository}); a real application
 * would implement this with Spring Data JPA or JDBC, and nothing else would change.
 */
public interface TicketRepository {
    List<Ticket> findAll();

    Optional<Ticket> findById(long id);

    /** A new id, for a ticket about to be saved for the first time. */
    long nextId();

    void save(Ticket ticket);

    void deleteById(long id);
}
