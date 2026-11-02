package helpdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * A support helpdesk: a Spring Boot application whose pages are written in JASX. Run it with
 * {@code ./gradlew -p examples/helpdesk run}, then open http://localhost:8085 in two windows, signed in as two
 * different agents.
 *
 * <p>The packages are those of an ordinary Spring application: {@code tickets} is the domain (records, a
 * repository, the service), {@code web} the pages (controllers, one {@code .jasx} file per view, and the
 * browser's {@code .js} files next to them), {@code api} the REST API that other systems call.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
