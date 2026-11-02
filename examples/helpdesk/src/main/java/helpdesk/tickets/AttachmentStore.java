package helpdesk.tickets;

import helpdesk.tickets.Ticket.Attachment;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The content of attachments, in memory. A real application would put them in object storage (S3, a disk) and keep
 * only the {@link Attachment} with the ticket, as here.
 */
@Component
public class AttachmentStore {
    /** An attachment and its bytes. */
    public record StoredFile(Attachment attachment, byte[] content) {}

    private final Map<Long, StoredFile> files = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    /** Keeps a file. {@code name} is shown and offered as the download's name, never used as a path. */
    public Attachment store(String name, String contentType, byte[] content) {
        String safeName = name.replaceAll("[\\\\/\\r\\n\"]", "_");
        Attachment attachment = new Attachment(ids.incrementAndGet(), safeName, contentType, content.length);
        files.put(attachment.id(), new StoredFile(attachment, content.clone()));
        return attachment;
    }

    public Optional<StoredFile> find(long id) {
        return Optional.ofNullable(files.get(id));
    }
}
