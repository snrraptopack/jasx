package hello;

import com.sun.net.httpserver.HttpServer;
import jasx.html.Html;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serves the home page at http://localhost:8080 with the JDK's built-in HTTP server.
 *
 * <p>With {@code --print}, prints the HTML instead and exits.
 */
public final class Main {
    private static final List<Todo> TODOS = List.of(
            new Todo(1, "Write the JASX spec", true),
            new Todo(2, "Build the compiler", true),
            new Todo(3, "Decide on targets", false));

    public static void main(String[] args) throws IOException {
        // Markup is an ordinary Java value: rendering it to HTML is one call on the html target.
        String html = "<!doctype html>" + Html.render(Pages.Home(TODOS));

        if (args.length > 0 && args[0].equals("--print")) {
            System.out.println(html);
            return;
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", exchange -> {
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        System.out.println("Serving http://localhost:8080 (Ctrl+C to stop)");
    }
}
