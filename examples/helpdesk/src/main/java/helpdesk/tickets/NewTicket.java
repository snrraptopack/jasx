package helpdesk.tickets;

import helpdesk.tickets.Ticket.Priority;
import java.util.ArrayList;
import java.util.List;

/**
 * What opening a ticket takes: from the inbox's form ({@code e.as(NewTicket.class)}) or from the REST API (as JSON).
 */
public record NewTicket(String subject, String requester, Priority priority, String body) {
    public NewTicket {
        subject = subject == null ? "" : subject.strip();
        requester = requester == null ? "" : requester.strip();
        priority = priority == null ? Priority.NORMAL : priority;
        body = body == null ? "" : body.strip();
    }

    /** What is wrong with it, for the person who filled it in; empty if nothing is. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (subject.isEmpty()) {
            problems.add("The subject is missing.");
        } else if (subject.length() > 120) {
            problems.add("The subject is longer than 120 characters.");
        }
        if (!requester.matches("[^@\\s]+@[^@\\s]+")) {
            problems.add("The requester's email address doesn't look right.");
        }
        if (body.isEmpty()) {
            problems.add("Say what the customer asked.");
        }
        return problems;
    }
}
