package jasx.web.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsExchange;
import jasx.web.WebApp;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
            WebApp.Request request = request(exchange);

            if (path.equals(base + "/web.js") && method.equals("GET")) {
                send(exchange, app.clientScript(request));
            } else if (path.startsWith(base + "/js/") && method.equals("GET")) {
                send(exchange, app.module(path.substring((base + "/js/").length())));
            } else if (path.equals(base + "/event") && method.equals("POST")) {
                event(exchange, request);
            } else if (path.equals(base + "/stream") && method.equals("GET")) {
                stream(exchange, request);
            } else if (path.equals(base + "/upload") && method.equals("POST")) {
                send(exchange, app.upload(request, exchange.getRequestBody()));
            } else if (path.equals(base + "/ping") && method.equals("POST")) {
                exchange.getRequestBody().readNBytes(1024);
                send(exchange, app.ping(request));
            } else if (pages.containsKey(path) && method.equals("GET")) {
                WebRequest page = new WebRequest(exchange.getRequestURI());
                send(exchange, app.render(request, () -> pages.get(path).apply(page)));
            } else {
                send(exchange, new WebApp.Response(404, "Not found", Map.of("Content-Type", "text/plain; charset=utf-8")));
            }
        }
    }

    /** An event; a body over the limit is not read to its end (targets/web.md §6.3). */
    private void event(HttpExchange exchange, WebApp.Request request) throws IOException {
        int limit = app.limits().maxEventBytes();
        byte[] bytes = exchange.getRequestBody().readNBytes(limit + 1);
        if (bytes.length > limit) {
            send(exchange, new WebApp.Response(413, ""));
            return;
        }
        send(exchange, app.event(request, new String(bytes, StandardCharsets.UTF_8)));
    }

    /** The stream of server-sent events for the pages of a browser's tabs; blocks this (virtual) thread while open. */
    private void stream(HttpExchange exchange, WebApp.Request request) throws IOException {
        OutputStream out = exchange.getResponseBody();
        Optional<WebApp.Response> refused = app.stream(request, new WebApp.EventStream() {
            @Override
            public void open() throws IOException {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, 0); // 0: chunked, of unknown length
            }

            @Override
            public void send(String frame) throws IOException {
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        });
        if (refused.isPresent()) {
            send(exchange, refused.get());
        }
    }

    /** What WebApp reads of a request. The JDK's headers are already case-insensitive. */
    private static WebApp.Request request(HttpExchange exchange) {
        Map<String, List<String>> query = WebRequest.parseQuery(exchange.getRequestURI().getRawQuery());
        return new WebApp.Request() {
            @Override
            public String query(String name) {
                List<String> values = query.get(name);
                return values == null || values.isEmpty() ? null : values.get(0);
            }

            @Override
            public String header(String name) {
                return exchange.getRequestHeaders().getFirst(name);
            }

            @Override
            public boolean secure() {
                return exchange instanceof HttpsExchange;
            }
        };
    }

    private static void send(HttpExchange exchange, WebApp.Response response) throws IOException {
        response.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(response.status(), -1); // -1: no body
            return;
        }
        exchange.sendResponseHeaders(response.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
