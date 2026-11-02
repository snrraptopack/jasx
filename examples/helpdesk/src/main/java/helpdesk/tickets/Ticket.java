package helpdesk.tickets;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A support ticket: what a customer asked, and the conversation since. Immutable: a change makes a new ticket
 * ({@code with...}), which the {@link TicketService} saves.
 *
 * @param assignee the agent working on it, or {@code null} while nobody is
 */
public record Ticket(long id, String subject, String requester, Status status, Priority priority, String assignee,
        Instant createdAt, Instant updatedAt, List<Message> messages) {

    public Ticket {
        messages = List.copyOf(messages);
    }

    /** Where a ticket is in its life. The board has one column for each. */
    public enum Status {
        OPEN("Open"), PENDING("Waiting on customer"), SOLVED("Solved");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Priority {
        LOW, NORMAL, URGENT;

        public String label() {
            return name().charAt(0) + name().substring(1).toLowerCase();
        }
    }

    /** One message of the conversation. An internal note is seen by agents only. */
    public record Message(String author, String body, Instant at, boolean internal, List<Attachment> attachments) {
        public Message {
            attachments = List.copyOf(attachments);
        }
    }

    /** A file attached to a message; its content is in the {@link AttachmentStore}. */
    public record Attachment(long id, String name, String contentType, long size) {}

    public boolean isAssigned() {
        return assignee != null;
    }

    /** The first message: what the customer wrote. */
    public String summary() {
        return messages.isEmpty() ? "" : messages.getFirst().body();
    }

    public Ticket withStatus(Status status, Instant now) {
        return new Ticket(id, subject, requester, status, priority, assignee, createdAt, now, messages);
    }

    public Ticket withPriority(Priority priority, Instant now) {
        return new Ticket(id, subject, requester, status, priority, assignee, createdAt, now, messages);
    }

    public Ticket withAssignee(String assignee, Instant now) {
        return new Ticket(id, subject, requester, status, priority, assignee, createdAt, now, messages);
    }

    public Ticket withMessage(Message message) {
        List<Message> next = new ArrayList<>(messages);
        next.add(message);
        return new Ticket(id, subject, requester, status, priority, assignee, createdAt, message.at(), next);
    }
}
