package jasx.web.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jasx.web.WebApp;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Serves web-target pages with the JDK's own HTTP server, with no other dependency (targets/web.md §8.1):
 *
 * <pre>
 *   WebServer.create(8080)
 *       .page("/", () -&gt; &lt;Home /&gt;)
 *       .page("/todos", request -&gt; &lt;Todos filter={request.query("filter")} /&gt;)
 *       .start();
 * </pre>
 *
 * <p>Each request runs on a virtual thread, so the long-lived streams cost no platform threads. Pages are
 * matched by their exact path.
 */
public final class WebServer {
    private final HttpServer server;
    private final WebApp app;
    private final Map<String, Function<WebRequest, ?>> pages = new LinkedHashMap<>();

    private WebServer(HttpServer server, WebApp app) {
        this.server = server;
        this.app = app;
    }

    /** A server on {@code port} (0 picks a free one), with a new {@link WebApp}. */
    public static WebServer create(int port) {
        return create(port, new WebApp());
    }

    public static WebServer create(int port, WebApp app) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            WebServer webServer = new WebServer(server, app);
            server.createContext("/", webServer::handle);
            return webServer;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Serves the page {@code root} at {@code path}. */
    public WebServer page(String path, Supplier<?> root) {
        return page(path, request -> root.get());
    }

    /** Serves at {@code path} the page {@code root} makes from the request (its query parameters, say). */
    public WebServer page(String path, Function<WebRequest, ?> root) {
        pages.put(path, root);
        return this;
    }

    public WebServer start() {
        server.start();
        return this;
    }

    /** The port the server listens on. */
    public int port() {
        return server.getAddress().getPort();
    }

    public WebApp app() {
        return app;
    }

    /** Stops the server and discards its pages. */
    public void stop() {
        server.stop(0);
        app.close();
    }

    // ---------------------------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String base = app.basePath();

            if (path.equals(base + "/web.js") && method.equals("GET")) {
                send(exchange, 200, "text/javascript; charset=utf-8", WebApp.clientScript());
            } else if (path.equals(base + "/event") && method.equals("POST")) {
                event(exchange);
            } else if (path.equals(base + "/stream") && method.equals("GET")) {
                stream(exchange);
            } else if (path.equals(base + "/ping") && method.equals("POST")) {
                exchange.getRequestBody().readAllBytes();
                WebApp.Response response = app.ping(pageId(exchange));
                send(exchange, response.status(), "text/plain; charset=utf-8", response.body());
            } else if (pages.containsKey(path) && method.equals("GET")) {
                WebRequest request = new WebRequest(exchange.getRequestURI());
                String html = app.render(() -> pages.get(path).apply(request));
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                send(exchange, 200, "text/html; charset=utf-8", html);
            } else {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found");
            }
        }
    }

    private void event(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        WebApp.Response response = app.event(pageId(exchange), body);
        send(exchange, response.status(), "application/json", response.body());
    }

    /** The stream of server-sent events for the pages of a browser's tabs; blocks this (virtual) thread while open. */
    private void stream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0); // 0: chunked, of unknown length
        OutputStream out = exchange.getResponseBody();
        app.stream(WebApp.streamPages(queryParameter(exchange, "pages")), frame -> {
            out.write(frame.getBytes(StandardCharsets.UTF_8));
            out.flush();
        });
    }

    private static String pageId(HttpExchange exchange) {
        return queryParameter(exchange, "page");
    }

    private static String queryParameter(HttpExchange exchange, String name) {
        return WebRequest.parseQuery(exchange.getRequestURI().getRawQuery()).getOrDefault(name, java.util.List.of())
                .stream().findFirst().orElse(null);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1); // -1: no body
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
