package jasx.web.servlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jasx.ui.State;
import jasx.web.Web;
import jasx.web.WebApp;
import jasx.web.attrs.Button;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link JasxServlet} on a real container, an embedded Tomcat, talked to as a browser would. */
class JasxServletTest {
    @TempDir
    Path tomcatDir;

    private Tomcat tomcat;
    private final HttpClient client = HttpClient.newHttpClient();
    private final State<String> message = Web.state("first");

    @AfterEach
    void stop() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    /** Starts Tomcat with the servlet mapped at /* in context {@code contextPath}; returns the base URL. */
    private String start(String contextPath, WebApp app) throws Exception {
        JasxServlet servlet = new JasxServlet(app)
                .page("/", request -> {
                    State<Integer> count = Web.state(Integer.parseInt(request.getParameter("start")));
                    return Web.h("button", Button.create().onclick(() -> count.update(n -> n + 1)),
                            Web.dynamic(() -> count.get()));
                })
                .page("/news", () -> Web.h("p", null, Web.dynamic(() -> message.get())));
        tomcat = new Tomcat();
        tomcat.setBaseDir(tomcatDir.toString());
        tomcat.setPort(0);
        tomcat.getConnector(); // creates the default connector
        Context context = tomcat.addContext(contextPath, tomcatDir.toString());
        Wrapper wrapper = Tomcat.addServlet(context, "jasx", servlet);
        wrapper.setAsyncSupported(true);
        context.addServletMappingDecoded("/*", "jasx");
        tomcat.start();
        return "http://localhost:" + tomcat.getConnector().getLocalPort() + contextPath;
    }

    private HttpResponse<String> get(String url, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, String cookie, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertTrue(m.find(), "no " + regex + " in " + text);
        return m.group(1);
    }

    private static String cookieOf(HttpResponse<?> page) {
        return page.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    private static String clickEvent(String html) {
        return "{\"handler\":" + find(html, "click:(\\d+)") + ",\"type\":\"click\"}";
    }

    @Test
    void servesPagesEventsAndTheClientScript() throws Exception {
        String base = start("", new WebApp());

        HttpResponse<String> page = get(base + "/?start=5", null);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains(">5<"), page.body());
        assertEquals("text/html;charset=utf-8", page.headers().firstValue("Content-Type").orElseThrow().replace(" ", ""));
        String cookie = cookieOf(page);
        String eventUrl = base + "/_jasx/event?page=" + find(page.body(), "data-jx-page=\"([^\"]+)\"");

        HttpResponse<String> answer = post(eventUrl, cookie, clickEvent(page.body()));
        assertEquals(200, answer.statusCode());
        assertTrue(answer.body().contains("\"html\":\"6\""), answer.body());
        assertEquals(410, post(eventUrl, null, clickEvent(page.body())).statusCode(), "another browser can't use the page");

        HttpResponse<String> script = get(base + "/_jasx/web.js", null);
        assertEquals(200, script.statusCode());
        assertTrue(script.body().contains("data-jx-on"));
        assertEquals(404, get(base + "/nowhere", null).statusCode());
    }

    @Test
    void pushesStateChangesOnTheStream() throws Exception {
        String base = start("", new WebApp());
        HttpResponse<String> page = get(base + "/news", null);
        String pageId = find(page.body(), "data-jx-page=\"([^\"]+)\"");

        HttpRequest streamRequest = HttpRequest.newBuilder(URI.create(base + "/_jasx/stream?pages=" + pageId + ":0"))
                .header("Cookie", cookieOf(page)).build();
        HttpResponse<Stream<String>> stream = client.send(streamRequest, HttpResponse.BodyHandlers.ofLines());
        assertEquals(200, stream.statusCode());
        assertEquals("text/event-stream", stream.headers().firstValue("Content-Type").orElseThrow());

        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            Iterator<String> it = stream.body().iterator();
            while (it.hasNext()) {
                lines.add(it.next());
            }
        });
        try {
            assertEquals(": connected", lines.poll(5, TimeUnit.SECONDS));
            message.set("second");
            String frame = null;
            for (int i = 0; i < 3 && (frame == null || frame.isEmpty()); i++) {
                frame = lines.poll(5, TimeUnit.SECONDS);
            }
            assertEquals("data: {\"page\":\"" + pageId + "\",\"batch\":{\"version\":1,\"patches\":"
                    + "[{\"op\":\"html\",\"id\":1,\"html\":\"second\"}]}}", frame);
        } finally {
            stream.body().close();
            reader.interrupt();
        }
    }

    @Test
    void aContextPathGoesInTheAppsBasePath() throws Exception {
        String base = start("/shop", new WebApp("/shop/_jasx", Duration.ofMinutes(2)));
        HttpResponse<String> page = get(base + "/?start=1", null);
        assertTrue(page.body().contains("src=\"/shop/_jasx/web.js\""), page.body());
        String eventUrl = base + "/_jasx/event?page=" + find(page.body(), "data-jx-page=\"([^\"]+)\"");
        assertEquals(200, post(eventUrl, cookieOf(page), clickEvent(page.body())).statusCode());
    }

    @Test
    void anEventOverTheLimitIsNotReadToItsEnd() throws Exception {
        String base = start("", new WebApp("/_jasx", Duration.ofMinutes(2), Duration.ofSeconds(30), new WebApp.Limits(1000, 50)));
        HttpResponse<String> page = get(base + "/?start=1", null);
        String eventUrl = base + "/_jasx/event?page=" + find(page.body(), "data-jx-page=\"([^\"]+)\"");
        assertEquals(413, post(eventUrl, cookieOf(page), "x".repeat(100_000)).statusCode());
    }
}
