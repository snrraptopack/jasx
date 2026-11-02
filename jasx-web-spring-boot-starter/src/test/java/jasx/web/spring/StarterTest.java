package jasx.web.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jasx.runtime.Element;
import jasx.ui.State;
import jasx.web.Web;
import jasx.web.WebApp;
import jasx.web.attrs.Button;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The starter in a real Spring Boot application on a random port, talked to as a browser would. */
class StarterTest {
    /** Pushed to the pages that show it, from outside any request. */
    static final State<String> NEWS = Web.state("first");

    /** Read by an effect that a page method creates. */
    static final State<String> TOPIC = Web.state("a");
    static final AtomicInteger EFFECT_RUNS = new AtomicInteger();

    /** Pages as ordinary controller methods, returning their markup: the whole method runs inside the page. */
    @Controller
    static class Pages {
        @GetMapping("/counter/{start}")
        Element counter(@PathVariable("start") int start) {
            State<Integer> count = Web.state(start);
            return Web.h("button", Button.create().onclick(() -> count.update(n -> n + 1)), Web.dynamic(() -> count.get()));
        }

        @GetMapping("/news")
        Element news() {
            return Web.h("p", null, Web.dynamic(() -> NEWS.get()));
        }

        /** An effect made in the method belongs to the page, and is disposed with it. */
        @GetMapping("/effect")
        Element effect() {
            Web.effect(() -> {
                TOPIC.get();
                EFFECT_RUNS.incrementAndGet();
            });
            return Web.h("p", null, "watching");
        }

        @GetMapping("/missing/{id}")
        Element missing(@PathVariable("id") long id) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no item " + id);
        }

        /** The fallback form: a function the starter calls inside the page. */
        @GetMapping("/as-function")
        WebPage asFunction() {
            return () -> Web.h("p", null, "rendered from a WebPage");
        }
    }

    /** A @RestController's body handler must not claim a page. */
    @RestController
    static class Api {
        @GetMapping("/rest-page")
        Element page() {
            return Web.h("p", null, "from a rest controller");
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({Pages.class, Api.class})
    static class Application {}

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static HttpResponse<String> get(String url) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String url, String cookie, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertTrue(m.find(), "no " + regex + " in " + text);
        return m.group(1);
    }

    private static String cookieOf(HttpResponse<?> page) {
        return page.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    private static String pageId(String html) {
        return find(html, "data-jx-page=\"([^\"]+)\"");
    }

    private static String clickEvent(String html) {
        return "{\"handler\":" + find(html, "click:(\\d+)") + ",\"type\":\"click\"}";
    }

    @Nested
    @SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class AtTheRoot {
        @LocalServerPort
        int port;

        @Autowired
        WebApp app;

        private String base() {
            return "http://localhost:" + port;
        }

        @Test
        void aControllerMethodRendersAPageWhoseEventsWork() throws Exception {
            HttpResponse<String> page = get(base() + "/counter/5");
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains(">5<"), page.body());
            assertTrue(page.body().contains("src=\"/_jasx/web.js?v="), page.body());

            String eventUrl = base() + "/_jasx/event?page=" + pageId(page.body());
            HttpResponse<String> answer = post(eventUrl, cookieOf(page), clickEvent(page.body()));
            assertEquals(200, answer.statusCode());
            assertTrue(answer.body().contains("\"html\":\"6\""), answer.body());
            assertEquals(410, post(eventUrl, null, clickEvent(page.body())).statusCode(), "the page is bound to its browser");
            assertTrue(app.hasPage(pageId(page.body())));
        }

        @Test
        void aRestControllerCanReturnAPageToo() throws Exception {
            HttpResponse<String> page = get(base() + "/rest-page");
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("<p>from a rest controller</p>"), page.body());
            assertTrue(page.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
        }

        @Test
        void aWebPageFunctionStillWorks() throws Exception {
            HttpResponse<String> page = get(base() + "/as-function");
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("<p>rendered from a WebPage</p>"), page.body());
        }

        @Test
        void aStatusExceptionFromAPageMethodReachesSpringAndDiscardsThePage() throws Exception {
            int pagesBefore = app.pageCount();
            assertEquals(404, get(base() + "/missing/7").statusCode());
            assertEquals(pagesBefore, app.pageCount(), "the page the method failed in is discarded");
        }

        @Test
        void theClientScriptIsServed() throws Exception {
            HttpResponse<String> script = get(base() + "/_jasx/web.js");
            assertEquals(200, script.statusCode());
            assertTrue(script.body().contains("data-jx-on"));
        }

        @Test
        void stateChangesArePushedOnTheStream() throws Exception {
            HttpResponse<String> page = get(base() + "/news");
            String pageId = pageId(page.body());
            HttpRequest streamRequest = HttpRequest.newBuilder(URI.create(base() + "/_jasx/stream?pages=" + pageId + ":0"))
                    .header("Cookie", cookieOf(page)).build();
            HttpResponse<Stream<String>> stream = CLIENT.send(streamRequest, HttpResponse.BodyHandlers.ofLines());
            assertEquals(200, stream.statusCode());

            BlockingQueue<String> lines = new LinkedBlockingQueue<>();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    Iterator<String> it = stream.body().iterator();
                    while (it.hasNext()) {
                        lines.add(it.next());
                    }
                } catch (java.io.UncheckedIOException closedByTheTest) {
                    // the test is over
                }
            });
            try {
                assertEquals(": connected", lines.poll(5, TimeUnit.SECONDS));
                NEWS.set("second");
                String frame = null;
                for (int i = 0; i < 3 && (frame == null || frame.isEmpty()); i++) {
                    frame = lines.poll(5, TimeUnit.SECONDS);
                }
                assertTrue(frame != null && frame.startsWith("data: {\"page\":\"" + pageId + "\""), String.valueOf(frame));
                assertTrue(frame.contains("\"html\":\"second\""), frame);
            } finally {
                stream.body().close();
                reader.interrupt();
            }
        }
    }

    @Nested
    @SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "jasx.web.max-pages-per-session=1")
    class PageOwnership {
        @LocalServerPort
        int port;

        @Test
        void whatAPageMethodCreatesBelongsToThePage() throws Exception {
            String base = "http://localhost:" + port;
            HttpResponse<String> page = get(base + "/effect");
            String cookie = cookieOf(page);
            int runs = EFFECT_RUNS.get();

            TOPIC.set("b"); // the page is alive: its effect runs again (on the page's flush)
            for (int i = 0; i < 50 && EFFECT_RUNS.get() == runs; i++) {
                Thread.sleep(20);
            }
            assertEquals(runs + 1, EFFECT_RUNS.get());

            // One page per session here: a second page discards the first, and its effect with it.
            CLIENT.send(HttpRequest.newBuilder(URI.create(base + "/counter/1")).header("Cookie", cookie).build(),
                    HttpResponse.BodyHandlers.ofString());
            TOPIC.set("c");
            Thread.sleep(300);
            assertEquals(runs + 1, EFFECT_RUNS.get(), "the effect was disposed with its page");
        }
    }

    @Nested
    @SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"server.servlet.context-path=/shop", "jasx.web.max-pages-per-session=7"})
    class WithAContextPathAndProperties {
        @LocalServerPort
        int port;

        @Autowired
        WebApp app;

        @Test
        void theBasePathIncludesTheContextPath() throws Exception {
            String base = "http://localhost:" + port + "/shop";
            HttpResponse<String> page = get(base + "/counter/1");
            assertTrue(page.body().contains("src=\"/shop/_jasx/web.js?v="), page.body());
            String eventUrl = base + "/_jasx/event?page=" + pageId(page.body());
            assertEquals(200, post(eventUrl, cookieOf(page), clickEvent(page.body())).statusCode());
            assertEquals(7, app.limits().maxPagesPerSession());
            assertEquals("/shop/_jasx", app.basePath());
        }
    }
}
