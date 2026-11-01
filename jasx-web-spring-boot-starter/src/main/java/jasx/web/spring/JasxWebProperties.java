package jasx.web.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The {@code jasx.web.*} properties of the starter, for {@link jasx.web.WebApp}:
 *
 * <pre>
 *   jasx.web.base-path=/_jasx                 # where the client script, events and streams are served
 *   jasx.web.page-timeout=2m                  # how long a page is kept after its browser goes away
 *   jasx.web.first-connect-timeout=30s        # how long a page waits for its browser to connect
 *   jasx.web.max-event-bytes=1048576          # the largest event body accepted (targets/web.md §6.3)
 *   jasx.web.max-pages-per-session=50         # the most pages one browser keeps alive
 * </pre>
 *
 * <p>The base path is within the application: with {@code server.servlet.context-path=/shop}, the browser
 * requests {@code /shop/_jasx/...}.
 */
@ConfigurationProperties("jasx.web")
public record JasxWebProperties(
        @DefaultValue("/_jasx") String basePath,
        @DefaultValue("2m") Duration pageTimeout,
        @DefaultValue("30s") Duration firstConnectTimeout,
        @DefaultValue("1048576") int maxEventBytes,
        @DefaultValue("50") int maxPagesPerSession) {}
