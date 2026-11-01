package web;

import jasx.web.server.WebServer;

/**
 * Starts the example on the JDK's own HTTP server: {@code ./gradlew -p examples/web run}, then open
 * http://localhost:8080. Set the {@code PORT} environment variable to use another port.
 */
public class Main {
    public static void main(String[] args) {
        TodoStore todos = new TodoStore();
        ChatRoom room = new ChatRoom();
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        WebServer server = WebServer.create(port)
                .page("/", Pages::Home)
                .page("/todos", () -> Pages.Todos(todos))
                .page("/search", Pages::Search)
                .page("/live", () -> Pages.Live(room))
                .start();
        System.out.println("Serving the JASX web example at http://localhost:" + server.port());
    }
}
