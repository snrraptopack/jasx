package helpdesk;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The helpdesk's settings, from {@code application.properties} (or the environment: {@code HELPDESK_AGENTS=...}).
 *
 * @param agents             who can sign in
 * @param maxAttachmentBytes the largest attachment kept
 */
@ConfigurationProperties("helpdesk")
public record HelpdeskProperties(List<String> agents, long maxAttachmentBytes) {
    public HelpdeskProperties {
        agents = agents == null || agents.isEmpty() ? List.of("Agent") : List.copyOf(agents);
        maxAttachmentBytes = maxAttachmentBytes <= 0 ? 5 * 1024 * 1024 : maxAttachmentBytes;
    }

    /** The agent named {@code name}, or the first one if there is no such agent (or no name). */
    public String agentOrDefault(String name) {
        return agents.contains(name) ? name : agents.getFirst();
    }
}
