package jasx.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jasx.compiler.CompilationResult;
import jasx.compiler.CompilerDiagnostic;
import jasx.compiler.JasxCompiler;
import jasx.compiler.JavacRunner;
import jasx.compiler.SourceFile;
import jasx.compiler.Target;
import jasx.web.attrs.Button;
import jasx.web.server.WebServer;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WebTest {
    private final WebApp app = new WebApp();

    @AfterEach
    void closeApp() {
        app.close();
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static String pageId(String html) {
        return find(html, "data-jx-page=\"([^\"]+)\"");
    }

    /** The handler id of the {@code occurrence}-th (1-based) handler for {@code type} in {@code html}. */
    private static int handler(String html, String type, int occurrence) {
        Matcher m = Pattern.compile("\\b" + type + ":(\\d+)").matcher(html);
        for (int i = 0; i < occurrence; i++) {
            assertTrue(m.find(), "no handler for " + type + " in " + html);
        }
        return Integer.parseInt(m.group(1));
    }

    private static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertTrue(m.find(), "no " + regex + " in " + text);
        return m.group(1);
    }

    private WebApp.Response click(String html, int handlerId) {
        return app.event(pageId(html), "{\"handler\":" + handlerId + ",\"type\":\"click\"}");
    }

    // ---------------------------------------------------------------------------------------------

    @Nested
    class ReactiveValues {
        @Test
        void derivedValuesAreComputedLazilyAndAgainAfterAChange() {
            State<Integer> count = Web.state(2);
            int[] runs = {0};
            Derived<Integer> doubled = Web.derived(() -> {
                runs[0]++;
                return count.get() * 2;
            });
            assertEquals(0, runs[0]);
            assertEquals(4, doubled.get());
            assertEquals(4, doubled.get());
            assertEquals(1, runs[0]);
            count.set(5);
            assertEquals(10, doubled.get());
            assertEquals(2, runs[0]);
        }

        @Test
        void effectsRunAgainWhenAValueTheyReadChanges() {
            State<String> name = Web.state("a");
            List<String> seen = new ArrayList<>();
            Web.effect(() -> seen.add(name.get()));
            name.set("b");
            name.set("b"); // equal: no change
            assertEquals(List.of("a", "b"), seen);
        }

        @Test
        void aStateAsAChildIsItsValue() {
            State<Integer> count = Web.state(3);
            assertEquals("3", count.toString());
        }
    }

    @Nested
    class Pages {
        @Test
        void aClickUpdatesOnlyTheHolesThatReadTheChangedState() {
            String html = app.render(() -> {
                State<Integer> count = Web.state(0);
                return Web.h("div", null,
                        Web.h("button", Button.create().onclick(e -> count.set(count.get() + 1)), "+"),
                        Web.h("p", null, Web.dynamic(() -> count.get())),
                        Web.h("p", null, Web.dynamic(() -> "static")));
            });
            assertTrue(html.contains("<p><!--jx:2-->0<!--/jx:2--></p>"), html);

            WebApp.Response response = click(html, handler(html, "click", 1));
            assertEquals(200, response.status());
            assertEquals("{\"version\":1,\"patches\":[{\"op\":\"html\",\"id\":2,\"html\":\"1\"}]}", response.body());
        }

        @Test
        void holesThatReadNoStateGetNoMarkersOrIds() {
            String html = app.render(() -> {
                State<String> name = Web.state("a");
                return Web.h("div", null,
                        Web.h("p", Button.create().title(() -> "fixed"), Web.dynamic(() -> "plain")),
                        Web.h("p", Button.create().title(() -> name.get()), Web.dynamic(() -> name.get())));
            });
            assertTrue(html.contains("<p title=\"fixed\">plain</p>"), html);
            assertTrue(html.matches("(?s).*<p title=\"a\" data-jx-id=\"\\d+\"><!--jx:\\d+-->a<!--/jx:\\d+--></p>.*"), html);
        }

        @Test
        void anEventThatChangesNothingAnswers204() {
            String html = app.render(() -> Web.h("button", Button.create().onclick(e -> {}), "x"));
            assertEquals(204, click(html, handler(html, "click", 1)).status());
        }

        @Test
        void anUnknownPageAnswers410() {
            assertEquals(410, app.event("nope", "{\"handler\":1}").status());
        }

        @Test
        void aDynamicAttributeIsPatchedOnItsElement() {
            String html = app.render(() -> {
                State<Boolean> busy = Web.state(false);
                return Web.h("button", Button.create().disabled(() -> busy.get()).onclick(e -> busy.set(true)), "Go");
            });
            String elementId = find(html, "data-jx-id=\"(\\d+)\"");
            assertFalse(html.contains("disabled"), html);
            String body = click(html, handler(html, "click", 1)).body();
            assertTrue(body.contains("{\"op\":\"attr\",\"id\":" + elementId + ",\"name\":\"disabled\",\"value\":true}"), body);
        }

        @Test
        void aHoleThatReRunsReplacesTheHandlersInside() {
            String html = app.render(() -> {
                State<Integer> count = Web.state(0);
                return Web.h("div", null, Web.dynamic(() ->
                        Web.h("button", Button.create().onclick(e -> count.set(count.get() + 1)), "n=" + count.get())));
            });
            int first = handler(html, "click", 1);
            String body = click(html, first).body();
            int second = handler(body, "click", 1);
            assertTrue(second != first, body);
            assertEquals(204, click(html, first).status(), "the old handler is gone");
            assertTrue(click(html, second).body().contains("n=2"));
        }

        @Test
        void theClientScriptAndStyleSheetsAreInsertedIntoTheDocument() {
            StyleSheet sheet = Web.styleSheet("jx-1", "p.jx-1 { color: red; }");
            String html = app.render(() -> Web.h("html", null,
                    Web.h("head", null, Web.h("title", null, "T")),
                    Web.h("body", null, Web.scoped(sheet, Web.h("p", null, "x")))));
            assertTrue(html.contains("<title>T</title><style>p.jx-1 { color: red; }</style></head>"), html);
            assertTrue(html.contains("<p class=\"jx-1\">x</p><script src=\"/_jasx/web.js\" data-jx-page="), html);
            assertTrue(html.endsWith("defer></script></body></html>"), html);
        }

        @Test
        void aStyleSheetFirstUsedInAnUpdateIsSentAsCss() {
            StyleSheet sheet = Web.styleSheet("jx-2", "b.jx-2 { color: blue; }");
            String html = app.render(() -> {
                State<Boolean> shown = Web.state(false);
                return Web.h("div", null,
                        Web.h("button", Button.create().onclick(e -> shown.set(true)), "show"),
                        Web.dynamic(() -> shown.get() ? Web.scoped(sheet, Web.h("b", null, "hi")) : null));
            });
            String body = click(html, handler(html, "click", 1)).body();
            assertTrue(body.contains("{\"op\":\"css\",\"css\":\"b.jx-2 { color: blue; }\"}"), body);
            assertTrue(body.contains("\\u003cb class=\\\"jx-2\\\">hi\\u003c/b>"), body);
        }

        @Test
        void navigateSendsTheBrowserElsewhere() {
            String html = app.render(() -> Web.h("button", Button.create().onclick(e -> Web.navigate("/done")), "x"));
            assertTrue(click(html, handler(html, "click", 1)).body().contains("{\"op\":\"navigate\",\"url\":\"/done\"}"));
        }

        @Test
        void stateChangedFromAnotherThreadIsPushedOnTheStream() throws Exception {
            State<String> message = Web.state("first");
            String html = app.render(() -> Web.h("p", null, Web.dynamic(() -> message.get())));
            BlockingQueue<String> frames = new LinkedBlockingQueue<>();
            Thread stream = Thread.ofVirtual().start(() -> app.stream(pageId(html), frames::add));
            try {
                assertEquals(": connected\n\n", frames.poll(5, TimeUnit.SECONDS));
                message.set("second");
                String frame = frames.poll(5, TimeUnit.SECONDS);
                assertNotNull(frame);
                assertTrue(frame.startsWith("data: {\"version\":1,\"patches\":[{\"op\":\"html\",\"id\":1,\"html\":\"second\"}]}"), frame);
            } finally {
                stream.interrupt();
            }
        }

        @Test
        void aHandlerThatFailsLeavesThePageUsable() {
            String html = app.render(() -> {
                State<Integer> count = Web.state(0);
                return Web.h("div", null,
                        Web.h("button", Button.create().onclick(e -> {
                            count.set(1);
                            throw new IllegalStateException("boom (expected by the test)");
                        }), "x"),
                        Web.dynamic(() -> count.get()));
            });
            assertTrue(click(html, handler(html, "click", 1)).body().contains("\"html\":\"1\""));
        }

        @Test
        void pagesWithoutAStreamExpire() throws Exception {
            try (WebApp shortLived = new WebApp("/_jasx", Duration.ofSeconds(1))) {
                shortLived.render(() -> Web.h("p", null, "x"));
                assertEquals(1, shortLived.pageCount());
                for (int i = 0; i < 50 && shortLived.pageCount() > 0; i++) {
                    Thread.sleep(100);
                }
                assertEquals(0, shortLived.pageCount());
            }
        }
    }

    /** .jasx compiled with the web target: its lowering, builders and runtime together (targets/web.md). */
    @Nested
    class CompiledMarkup {
        private static final Target WEB = Target.load("web", WebTest.class.getClassLoader());
        private static final String CLASSPATH = System.getProperty("java.class.path");

        /** Compiles a .jasx file T with {@code members}, and returns a supplier calling its {@code Page()}. */
        private static Supplier<Object> compile(String members) {
            SourceFile file = new SourceFile("T.jasx", "import jasx.runtime.Element;\nimport java.util.*;\n"
                    + "import static jasx.web.Web.*;\n\nclass T {\n" + members + "\n}\n");
            CompilationResult result = new JasxCompiler(WEB).compile(List.of(file));
            assertFalse(result.hasErrors(), () -> format(result.diagnostics()));
            JavacRunner.Result compiled = JavacRunner.compile(result.files(), List.of(), CLASSPATH);
            assertTrue(compiled.success(), () -> format(compiled.diagnostics()) + "\n" + result.files().get(0).content());
            try {
                Method page = compiled.classLoader(WebTest.class.getClassLoader()).loadClass("T").getDeclaredMethod("Page");
                page.setAccessible(true);
                return () -> {
                    try {
                        return page.invoke(null);
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError(e);
                    }
                };
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }

        private static String format(List<CompilerDiagnostic> diagnostics) {
            return diagnostics.stream().map(CompilerDiagnostic::format).collect(Collectors.joining("\n"));
        }

        @Test
        void aCounterWithDerivedValuesAndAStatementChild() {
            String html = app.render(compile("""
                    static Element Page() {
                      var count = state(1);
                      var doubled = derived(() -> count.get() * 2);
                      return (
                        <div>
                          <button onclick={e -> count.set(count.get() + 1)}>+</button>
                          <p>{count} x 2 = {doubled}</p>
                          {if (count.get() > 1) { <b>many</b> }}
                        </div>
                      );
                    }
                    """));
            assertTrue(html.contains("<p><!--jx:2-->1<!--/jx:2--> x 2 = <!--jx:3-->2<!--/jx:3--></p><!--jx:4--><!--/jx:4-->"), html);

            String body = click(html, handler(html, "click", 1)).body();
            assertEquals("{\"version\":1,\"patches\":["
                    + "{\"op\":\"html\",\"id\":2,\"html\":\"2\"},"
                    + "{\"op\":\"html\",\"id\":3,\"html\":\"4\"},"
                    + "{\"op\":\"html\",\"id\":4,\"html\":\"\\u003cb>many\\u003c/b>\"}]}", body);
        }

        @Test
        void aFormSendsItsFieldsOnSubmitAndInputIsDebounced() {
            String html = app.render(compile("""
                    static Element Page() {
                      var items = state(List.<String>of());
                      var query = state("");
                      return (
                        <div>
                          <form onsubmit={e -> {
                            var next = new ArrayList<>(items.get());
                            next.add(e.field("title"));
                            items.set(next);
                          }}>
                            <input name="title" />
                          </form>
                          <input oninput={e -> query.set(e.value())} />
                          <input oninput={debounce(500, e -> query.set(e.value()))} />
                          <input oninput={immediate(e -> query.set(e.value()))} />
                          <ul>{for (String item : items.get()) { <li>{item}</li> }}</ul>
                          <p>{query}</p>
                        </div>
                      );
                    }
                    """));
            assertTrue(html.matches("(?s).*data-jx-on=\"input:\\d+:300\".*data-jx-on=\"input:\\d+:500\".*data-jx-on=\"input:\\d+\".*"), html);

            int submit = handler(html, "submit", 1);
            String body = app.event(pageId(html), "{\"handler\":" + submit + ",\"type\":\"submit\",\"fields\":{\"title\":[\"Milk\"]}}").body();
            // {item} reads no state, so it has no markers of its own (targets/web.md §8.2).
            assertTrue(body.contains("\"html\":\"\\u003cli>Milk\\u003c/li>\""), body);

            int input = handler(html, "input", 1);
            String typed = app.event(pageId(html), "{\"handler\":" + input + ",\"type\":\"input\",\"value\":\"ab\"}").body();
            assertTrue(typed.contains("\"html\":\"ab\""), typed);
        }

        @Test
        void aComponentRunsOnceAndOnlyItsHolesUpdate() {
            String html = app.render(compile("""
                    static int renders = 0;
                    static Element Counter(String label) {
                      renders++;
                      var count = state(0);
                      return <button class={count.get() > 0 ? "on" : "off"} onclick={e -> count.update(n -> n + 1)}>{label}: {count}</button>;
                    }
                    static Element Page() {
                      return <div><Counter label="a" /><Counter label="b" /><i>{renders}</i></div>;
                    }
                    """));
            assertTrue(html.contains("class=\"off\""), html);
            String body = click(html, handler(html, "click", 2)).body();
            assertTrue(body.contains("{\"op\":\"attr\",") && body.contains("\"value\":\"on\""), body);
            assertTrue(body.contains("\"html\":\"1\""), body);
            // The component didn't run again: the hole showing how often components ran has no patch.
            assertEquals(1, body.split("\"op\":\"html\"", -1).length - 1, body);
        }
    }

    @Nested
    class JdkServer {
        @Test
        void servesPagesEventsAndTheClientScript() throws Exception {
            WebServer server = WebServer.create(0)
                    .page("/", request -> {
                        State<Integer> count = Web.state(Integer.parseInt(request.query("start")));
                        return Web.h("button", Button.create().onclick(e -> count.set(count.get() + 1)),
                                Web.dynamic(() -> count.get()));
                    })
                    .start();
            try {
                HttpClient client = HttpClient.newHttpClient();
                String base = "http://localhost:" + server.port();

                HttpResponse<String> page = client.send(HttpRequest.newBuilder(URI.create(base + "/?start=5")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, page.statusCode());
                assertTrue(page.body().contains(">5<"), page.body());

                String eventUrl = base + "/_jasx/event?page=" + pageId(page.body());
                String event = "{\"handler\":" + handler(page.body(), "click", 1) + ",\"type\":\"click\"}";
                HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(eventUrl))
                        .POST(HttpRequest.BodyPublishers.ofString(event)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, answer.statusCode());
                assertTrue(answer.body().contains("\"html\":\"6\""), answer.body());

                HttpResponse<String> script = client.send(HttpRequest.newBuilder(URI.create(base + "/_jasx/web.js")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, script.statusCode());
                assertTrue(script.body().contains("data-jx-on"));

                HttpResponse<String> gone = client.send(HttpRequest.newBuilder(URI.create(base + "/_jasx/stream?page=nope")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(410, gone.statusCode());
            } finally {
                server.stop();
            }
        }
    }

}
