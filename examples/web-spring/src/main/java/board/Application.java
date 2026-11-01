package board;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A team board: a Spring Boot application whose pages are written in JASX. Run it with
 * {@code ./gradlew -p examples/web-spring run}, then open http://localhost:8080 in two windows.
 *
 * <p>There's no JASX configuration: adding {@code jasx-web-spring-boot-starter} is enough.
 */
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
