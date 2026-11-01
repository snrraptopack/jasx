package jasx.web.servlet;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jasx.web.WebApp;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Connects servlet requests to a {@link WebApp}: it describes a request as a {@link WebApp.Request}, sends a
 * {@link WebApp.Response} as it is, and serves the runtime's own requests (the client script, events, streams and
 * pings under the app's base path, targets/web.md §8.1). Everything else, sessions and checks included
 * (targets/web.md §6.3), is the app's.
 *
 * <p>{@link JasxServlet} uses it with its own pages. A framework that routes pages itself (Spring, say) calls
 * {@link #handle} for each request and {@link #render} from its page handlers.
 *
 * <p><b>Context paths.</b> The app's base path is the full path the browser requests, so an application deployed
 * at {@code /shop} uses {@code new WebApp("/shop/_jasx", ...)}.
 */
public final class ServletAdapter {
    private ServletAdapter() {}

    /**
     * Serves the request if it is one of the runtime's own: the client script, an event, a stream or a ping.
     *
     * @return false if it isn't, and nothing was done
     */
    public static boolean handle(WebApp app, HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        String base = app.basePath();
        if (!path.startsWith(base + "/")) {
            return false;
        }
        String method = request.getMethod();
        String endpoint = path.substring(base.length());
        switch (endpoint) {
            case "/web.js" -> {
                if (!method.equals("GET")) return false;
                send(response, new WebApp.Response(200, WebApp.clientScript(),
                        Map.of("Content-Type", "text/javascript; charset=utf-8")));
            }
            case "/event" -> {
                if (!method.equals("POST")) return false;
                event(app, request, response);
            }
            case "/stream" -> {
                if (!method.equals("GET")) return false;
                stream(app, request, response);
            }
            case "/ping" -> {
                if (!method.equals("POST")) return false;
                send(response, app.ping(request(request)));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Renders a new page for the browser of {@code request}: its HTML, and its session cookie if it had none. */
    public static void render(WebApp app, HttpServletRequest request, HttpServletResponse response, Supplier<?> root)
            throws IOException {
        send(response, app.render(request(request), root));
    }

    /**
     * What {@link WebApp} reads of a servlet request. The query is read from the query string itself, never with
     * {@code getParameter}, which would read a form-encoded body too.
     */
    public static WebApp.Request request(HttpServletRequest request) {
        String query = request.getQueryString();
        return new WebApp.Request() {
            @Override
            public String query(String name) {
                return queryParameter(query, name);
            }

            @Override
            public String header(String name) {
                return request.getHeader(name);
            }

            @Override
            public boolean secure() {
                return request.isSecure();
            }
        };
    }

    /** Sends a response as it is: status, headers and body. */
    public static void send(HttpServletResponse response, WebApp.Response answer) throws IOException {
        response.setStatus(answer.status());
        answer.headers().forEach(response::setHeader);
        byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
        if (body.length > 0) {
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** An event; a body over the limit is not read to its end (targets/web.md §6.3). */
    private static void event(WebApp app, HttpServletRequest request, HttpServletResponse response) throws IOException {
        int limit = app.limits().maxEventBytes();
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) {
            send(response, new WebApp.Response(413, ""));
            return;
        }
        send(response, app.event(request(request), new String(body, StandardCharsets.UTF_8)));
    }

    /**
     * The stream. With async support (register the servlet with {@code asyncSupported}), it runs on a virtual
     * thread and the container's thread is free at once; without it, it holds the container's thread while open.
     */
    private static void stream(WebApp app, HttpServletRequest request, HttpServletResponse response) throws IOException {
        WebApp.Request described = request(request);
        if (!request.isAsyncSupported()) {
            serveStream(app, described, response);
            return;
        }
        AsyncContext async = request.startAsync();
        async.setTimeout(0); // the stream ends when the browser leaves, or when newer streams take its pages
        Thread.ofVirtual().name("jasx-stream").start(() -> {
            try {
                serveStream(app, described, response);
            } catch (IOException e) {
                // The browser went away; it reconnects if it is still there.
            } finally {
                async.complete();
            }
        });
    }

    private static void serveStream(WebApp app, WebApp.Request request, HttpServletResponse response) throws IOException {
        ServletOutputStream out = response.getOutputStream();
        Optional<WebApp.Response> refused = app.stream(request, new WebApp.EventStream() {
            @Override
            public void open() throws IOException {
                response.setStatus(200);
                response.setContentType("text/event-stream");
                response.setHeader("Cache-Control", "no-store");
                response.flushBuffer();
            }

            @Override
            public void send(String frame) throws IOException {
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        });
        if (refused.isPresent()) {
            send(response, refused.get());
        }
    }

    /** The first value of {@code name} in a raw query string, decoded; null if absent. */
    private static String queryParameter(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String key = decode(equals >= 0 ? pair.substring(0, equals) : pair);
            if (key.equals(name)) {
                return equals >= 0 ? decode(pair.substring(equals + 1)) : "";
            }
        }
        return null;
    }

    private static String decode(String text) {
        return URLDecoder.decode(text, StandardCharsets.UTF_8);
    }
}
