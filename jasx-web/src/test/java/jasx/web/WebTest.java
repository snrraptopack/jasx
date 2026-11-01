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
import jasx.ui.Derived;
import jasx.ui.State;
import jasx.web.attrs.Button;
import jasx.web.server.WebServer;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Opens the page's stream on a virtual thread, as a browser that applied every batch up to {@code since}. */
    private Thread openStream(String html, long since, BlockingQueue<String> frames) {
        return openStream(Map.of(pageId(html), since), frames);
    }

    /** Opens one stream for several pages (targets/web.md §6.2), as the leading tab of a browser does. */
    private Thread openStream(Map<String, Long> pages, BlockingQueue<String> frames) {
        return Thread.ofVirtual().start(() -> app.stream(pages, frames::add));
    }

    /** The stream frame carrying {@code batch} for the page of {@code html}. */
    private static String frame(String html, String batch) {
        return framePrefix(html, batch) + "}\n\n";
    }

    /** How the frame carrying a batch that starts with {@code batchStart} starts. */
    private static String framePrefix(String html, String batchStart) {
        return "data: {\"page\":\"" + pageId(html) + "\",\"batch\":" + batchStart;
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
            Thread stream = openStream(html, 0, frames);
            try {
                assertEquals(": connected\n\n", frames.poll(5, TimeUnit.SECONDS));
                message.set("second");
                assertEquals(frame(html, "{\"version\":1,\"patches\":[{\"op\":\"html\",\"id\":1,\"html\":\"second\"}]}"),
                        frames.poll(5, TimeUnit.SECONDS));
            } finally {
                stream.interrupt();
            }
        }

        @Test
        void oneStreamCarriesThePagesOfSeveralTabs() throws Exception {
            State<String> shared = Web.state("a");
            String first = app.render(() -> Web.h("p", null, Web.dynamic(() -> shared.get())));
            String second = app.render(() -> Web.h("b", null, Web.dynamic(() -> shared.get() + "!")));
            Map<String, Long> pages = new LinkedHashMap<>();
            pages.put(pageId(first), 0L);
            pages.put(pageId(second), 0L);
            pages.put("closed-tab", 0L);
            BlockingQueue<String> frames = new LinkedBlockingQueue<>();
            Thread stream = openStream(pages, frames);
            try {
                assertEquals(": connected\n\n", frames.poll(5, TimeUnit.SECONDS));
                // A page the server no longer has: only its tab reloads, the stream goes on.
                assertEquals("data: {\"page\":\"closed-tab\",\"batch\":{\"reload\":true}}\n\n", frames.poll(5, TimeUnit.SECONDS));
                shared.set("b");
                Set<String> pushed = Set.of(frames.poll(5, TimeUnit.SECONDS), frames.poll(5, TimeUnit.SECONDS));
                assertEquals(Set.of(
                        frame(first, "{\"version\":1,\"patches\":[{\"op\":\"html\",\"id\":1,\"html\":\"b\"}]}"),
                        frame(second, "{\"version\":1,\"patches\":[{\"op\":\"html\",\"id\":1,\"html\":\"b!\"}]}")), pushed);
            } finally {
                stream.interrupt();
            }
        }

        @Test
        void aNewerStreamTakesItsPagesAndTheOldOneEndsWhenItHasNone() throws Exception {
            String first = app.render(() -> Web.h("p", null, "1"));
            String second = app.render(() -> Web.h("p", null, "2"));
            BlockingQueue<String> oldFrames = new LinkedBlockingQueue<>();
            Thread old = openStream(Map.of(pageId(first), 0L, pageId(second), 0L), oldFrames);
            assertEquals(": connected\n\n", oldFrames.poll(5, TimeUnit.SECONDS));

            // The leading tab reconnects with one page more, say, or another tab took over.
            BlockingQueue<String> newFrames = new LinkedBlockingQueue<>();
            Thread newer = openStream(Map.of(pageId(first), 0L), newFrames);
            try {
                assertEquals(": connected\n\n", newFrames.poll(5, TimeUnit.SECONDS));
                Thread.sleep(200);
                assertTrue(old.isAlive(), "the old stream still has the second page");
                BlockingQueue<String> newest = new LinkedBlockingQueue<>();
                Thread third = openStream(Map.of(pageId(second), 0L), newest);
                try {
                    old.join(5000);
                    assertFalse(old.isAlive(), "with no page left, the old stream ended");
                } finally {
                    third.interrupt();
                }
            } finally {
                newer.interrupt();
                old.interrupt();
            }
        }

        @Test
        void theStreamsPagesParameterIsReadLeniently() {
            assertEquals(Map.of("a", 12L, "b", 0L, "c", 0L), WebApp.streamPages("a:12,b,c:x,:5,"));
            assertEquals(Map.of(), WebApp.streamPages(null));
            String many = java.util.stream.IntStream.range(0, 500).mapToObj(i -> "p" + i + ":0")
                    .collect(Collectors.joining(","));
            assertEquals(SharedStream.MAX_PAGES, WebApp.streamPages(many).size());
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

    /** Delivery, failures and malformed input (targets/web.md §5, §6.1, §8): regressions from a review. */
    @Nested
    class Robustness {
        @Test
        void aBrowserThatMissedBatchesCatchesUpWithOneMergedBatch() throws Exception {
            State<String> clock = Web.state("0");
            String html = app.render(() -> Web.h("p", null, Web.dynamic(() -> clock.get())));
            for (int second = 1; second <= 3; second++) {
                clock.set(String.valueOf(second));
                waitForVersion(html, second);
            }
            BlockingQueue<String> frames = new LinkedBlockingQueue<>();
            Thread stream = openStream(html, 0, frames);
            try {
                assertEquals(": connected\n\n", frames.poll(5, TimeUnit.SECONDS));
                // Three values of the same hole: only the last is sent.
                assertEquals(frame(html, "{\"from\":1,\"version\":3,\"patches\":[{\"op\":\"html\",\"id\":1,\"html\":\"3\"}]}"),
                        frames.poll(5, TimeUnit.SECONDS));
            } finally {
                stream.interrupt();
            }
        }

        @Test
        void aCatchUpKeepsListPatchesUnlessTheWholeHoleIsReplacedLater() {
            Map<String, Object> list = Map.of("op", "list", "id", 5, "items", List.of());
            Map<String, Object> html = Map.of("op", "html", "id", 5, "html", "x");
            String listsOnly = BatchLog.merged(List.of(
                    new BatchLog.Batch(1, List.of(list), "", false), new BatchLog.Batch(2, List.of(list), "", false)));
            assertEquals(2, listsOnly.split("\"op\":\"list\"", -1).length - 1, "a later list refers to earlier items");
            String replaced = BatchLog.merged(List.of(
                    new BatchLog.Batch(1, List.of(list), "", false), new BatchLog.Batch(2, List.of(html), "", false)));
            assertFalse(replaced.contains("\"list\""), replaced);
        }

        @Test
        void aBrowserTheLogCantCatchUpIsToldToReload() throws Exception {
            String html = app.render(() -> Web.h("p", null, "x"));
            BlockingQueue<String> frames = new LinkedBlockingQueue<>();
            openStream(html, 99, frames).join(5000); // a version this page never sent
            assertTrue(frames.contains(frame(html, "{\"reload\":true}")), frames.toString());

            BlockingQueue<String> unknown = new LinkedBlockingQueue<>();
            app.stream(Map.of("no-such-page", 0L), unknown::add); // returns: it has no page to stream
            assertEquals(List.of(": connected\n\n", "data: {\"page\":\"no-such-page\",\"batch\":{\"reload\":true}}\n\n"),
                    List.copyOf(unknown));
        }

        @Test
        void theStreamDoesntPushEventAnswersButACatchUpIncludesThem() throws Exception {
            State<Integer> count = Web.state(0);
            String html = app.render(() -> Web.h("button", Button.create().onclick(() -> count.update(n -> n + 1)),
                    Web.dynamic(() -> count.get())));
            BlockingQueue<String> frames = new LinkedBlockingQueue<>();
            Thread stream = openStream(html, 0, frames);
            try {
                assertEquals(": connected\n\n", frames.poll(5, TimeUnit.SECONDS));
                assertEquals(200, click(html, handler(html, "click", 1)).status()); // version 1, in the answer
                count.set(5); // version 2, pushed
                String pushed = frames.poll(5, TimeUnit.SECONDS);
                assertTrue(pushed.startsWith(framePrefix(html, "{\"version\":2,")), pushed);
            } finally {
                stream.interrupt();
            }
            // The answer was lost, say: catching up from version 0 brings it back.
            BlockingQueue<String> catchUp = new LinkedBlockingQueue<>();
            Thread again = openStream(html, 0, catchUp);
            try {
                catchUp.poll(5, TimeUnit.SECONDS);
                String merged = catchUp.poll(5, TimeUnit.SECONDS);
                assertTrue(merged.startsWith(framePrefix(html, "{\"from\":1,\"version\":2,")), merged);
            } finally {
                again.interrupt();
            }
        }

        @Test
        void aHoleThatFailsToUpdateKeepsItsContentAndHandlers() {
            String html = app.render(() -> {
                State<Integer> count = Web.state(0);
                return Web.h("div", null, Web.dynamic(() -> {
                    if (count.get() == 1) {
                        throw new IllegalStateException("boom (expected by the test)");
                    }
                    return Web.h("button", Button.create().onclick(() -> count.update(n -> n + 1)), "n=" + count.get());
                }));
            });
            int button = handler(html, "click", 1);
            assertEquals(204, click(html, button).status(), "the failed update sends nothing");
            String body = click(html, button).body(); // the old button still works
            assertTrue(body.contains("n=2"), body);
        }

        @Test
        void aPageWhoseRenderingFailsIsDisposed() throws Exception {
            State<Integer> shared = Web.state(0);
            int[] runs = {0};
            try {
                app.render(() -> Web.h("div", null,
                        Web.dynamic(() -> {
                            runs[0]++;
                            return shared.get();
                        }),
                        Web.dynamic(() -> {
                            throw new IllegalStateException("boom (expected by the test)");
                        })));
            } catch (IllegalStateException expected) {
                // the rendering failed
            }
            assertEquals(0, app.pageCount());
            shared.set(1);
            Thread.sleep(200); // a flush, if one were scheduled, would run on another thread
            assertEquals(1, runs[0], "the failed page no longer follows the shared state");
        }

        @Test
        void malformedEventsAnswer400() {
            String html = app.render(() -> Web.h("button", Button.create().onclick(() -> {}), "x"));
            String page = pageId(html);
            String backslash = "\\";
            for (String body : List.of(
                    "{\"handler\":1,\"type\":\"a" + backslash,           // cut off after a backslash
                    "{\"handler\":1,\"type\":\"" + backslash + "u12",     // cut off in a hex escape
                    "{\"handler\":1,\"type\":\"" + backslash + "q\"}",    // an unknown escape
                    "[".repeat(10_000),                                    // nested too deeply
                    "{\"handler\":\"one\"}")) {
                assertEquals(400, app.event(page, body).status(), body.length() > 40 ? body.substring(0, 40) : body);
            }
        }

        @Test
        void rowsAndHolesDirectlyInATableGoInATbody() {
            String html = app.render(() -> {
                State<List<String>> rows = Web.state(List.of("a"));
                return Web.h("table", null,
                        Web.h("caption", null, "c"),
                        Web.dynamic(() -> rows.get().stream().map(r -> Web.h("tr", null, Web.h("td", null, r))).toList()));
            });
            assertTrue(html.contains("<table><caption>c</caption><tbody><!--jx:1--><tr><td>a</td></tr><!--/jx:1--></tbody></table>"), html);
        }

        @Test
        void aPageNoBrowserConnectsToIsDiscardedSoon() throws Exception {
            // A crawler or a link preview renders the page but never runs its script.
            try (WebApp app = new WebApp("/_jasx", Duration.ofMinutes(10), Duration.ofSeconds(1))) {
                String visited = app.render(() -> Web.h("p", null, "x"));
                app.render(() -> Web.h("p", null, "crawled"));
                assertEquals(204, app.ping(pageId(visited)).status()); // this one's browser connected
                for (int i = 0; i < 50 && app.pageCount() > 1; i++) {
                    Thread.sleep(100);
                }
                assertEquals(1, app.pageCount());
                assertTrue(app.hasPage(pageId(visited)), "the page a browser connected to is kept");
            }
        }

        @Test
        void aPingFromAnUnknownPageAnswers410() {
            assertEquals(410, app.ping("nope").status());
        }

        @Test
        void aResetPatchNamesTheEventItAnswers() {
            String html = app.render(() -> Web.h("form", jasx.web.attrs.Form.create().onsubmit(e -> e.resetForm())));
            String body = app.event(pageId(html), "{\"handler\":" + handler(html, "submit", 1) + ",\"seq\":7,\"type\":\"submit\"}").body();
            assertTrue(body.contains("{\"op\":\"reset\",\"event\":7}"), body);
        }

        /** Waits until the page has produced batch {@code version} (a push is flushed on another thread). */
        private void waitForVersion(String html, long version) throws InterruptedException {
            for (int i = 0; i < 100; i++) {
                BlockingQueue<String> frames = new LinkedBlockingQueue<>();
                // A stream from `version` gets nothing but its greeting if the batch exists, a reload if not.
                Thread probe = openStream(html, version, frames);
                Thread.sleep(20);
                probe.interrupt();
                probe.join(1000);
                if (!frames.contains(frame(html, "{\"reload\":true}"))) {
                    return;
                }
            }
            throw new AssertionError("batch " + version + " never came");
        }
    }

    /** Templates (targets/web.md §8.4): a shape's statics sent once, then only its values. */
    @Nested
    class Templates {
        @Test
        void markupKeepsStaticsAndValuesApartAndJoinsThemBack() {
            Markup inner = new Markup().append("<b>").value("x").append("</b>");
            Markup markup = new Markup().append("<p class=\"").value("a&amp;b").append("\">").append(inner).append("</p>");
            assertEquals("<p class=\"a&amp;b\"><b>x</b></p>", markup.toString());
            assertEquals(List.of("<p class=\"", "\"><b>", "</b></p>"), markup.statics());
            assertEquals(List.of("a&amp;b", "x"), markup.values());
            assertEquals(markup.toString().length(), markup.length());
        }

        /** A hole whose content has a sizable shape: a card with a title, a count and a link. */
        private String renderCards(State<Integer> count) {
            return app.render(() -> Web.h("div", null, Web.dynamic(() -> Web.h("article", null,
                    Web.h("h2", null, "Inbox"),
                    Web.h("p", null, "You have ", count.get(), " new messages waiting for you"),
                    Web.h("a", jasx.web.attrs.A.create().href("/messages?page=" + count.get()), "Open the inbox")))));
        }

        @Test
        void aShapeIsSentAsHtmlThenDefinedOnceThenOnlyItsValues() throws Exception {
            State<Integer> count = Web.state(1);
            String html = renderCards(count);
            BlockingQueue<String> frames = new LinkedBlockingQueue<>();
            Thread stream = openStream(html, 0, frames);
            try {
                assertEquals(": connected\n\n", frames.poll(5, TimeUnit.SECONDS));
                count.set(2); // the shape is seen for the first time in a patch: HTML, numbered
                String first = frames.poll(5, TimeUnit.SECONDS);
                assertTrue(first.contains("\"html\":\"\\u003carticle>"), first);
                count.set(3); // the second time: its statics once, then the values
                String second = frames.poll(5, TimeUnit.SECONDS);
                assertTrue(second.contains("{\"op\":\"tpl\",\"t\":1,\"s\":["), second);
                assertTrue(second.contains("{\"op\":\"html\",\"id\":1,\"t\":1,\"v\":[\"Inbox\",\"You have \",\"3\""), second);
                count.set(4); // from then on, only the values
                String third = frames.poll(5, TimeUnit.SECONDS);
                assertFalse(third.contains("\"tpl\""), third);
                assertTrue(third.contains("\"t\":1,\"v\":[\"Inbox\",\"You have \",\"4\""), third);
                assertTrue(third.length() < first.length(), "values are shorter than the HTML: " + third);
            } finally {
                stream.interrupt();
            }
        }

        @Test
        void aCatchUpKeepsTheTemplateEvenWhenItsFirstUseIsSuperseded() throws Exception {
            State<Integer> count = Web.state(1);
            String html = renderCards(count);
            for (int n = 2; n <= 4; n++) {
                count.set(n);
                Thread.sleep(50); // each push is its own batch
            }
            BlockingQueue<String> frames = new LinkedBlockingQueue<>();
            Thread stream = openStream(html, 0, frames); // a browser that missed everything
            try {
                frames.poll(5, TimeUnit.SECONDS);
                String merged = frames.poll(5, TimeUnit.SECONDS);
                assertTrue(merged.contains("{\"op\":\"tpl\",\"t\":1,"), "the definition is never dropped: " + merged);
                assertTrue(merged.contains("\"t\":1,\"v\":[\"Inbox\",\"You have \",\"4\""), merged);
                assertFalse(merged.contains("\"3\""), "earlier values of the hole are superseded: " + merged);
            } finally {
                stream.interrupt();
            }
        }

        @Test
        void aSmallHoleStaysHtml() {
            String html = app.render(() -> {
                State<Integer> n = Web.state(0);
                return Web.h("button", Button.create().onclick(() -> n.update(x -> x + 1)), Web.dynamic(() -> n.get()));
            });
            int button = handler(html, "click", 1);
            click(html, button);
            String second = click(html, button).body();
            assertTrue(second.contains("\"html\":\"2\""), "a template would be longer than \"2\": " + second);
        }
    }

    /** Sessions, same-site checks and limits (targets/web.md §6.3), through the API adapters call. */
    @Nested
    class Security {
        /** A request as an adapter would describe it. */
        record FakeRequest(Map<String, String> query, Map<String, String> headers, boolean secure) implements WebApp.Request {
            FakeRequest {
                Map<String, String> caseInsensitive = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                caseInsensitive.putAll(headers);
                headers = caseInsensitive;
            }

            @Override
            public String query(String name) {
                return query.get(name);
            }

            @Override
            public String header(String name) {
                return headers.get(name);
            }
        }

        /** A page request from a browser with session cookie {@code session} (null: none yet). */
        private static FakeRequest pageRequest(String session) {
            return new FakeRequest(Map.of(), session == null ? Map.of() : Map.of("Cookie", "theme=dark; jasx_session=" + session), false);
        }

        /** An event request, as the client sends it, from the browser with {@code session}. */
        private static FakeRequest eventRequest(String pageId, String session, String... extraHeaders) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Sec-Fetch-Site", "same-origin");
            if (session != null) {
                headers.put("Cookie", "jasx_session=" + session);
            }
            for (int i = 0; i < extraHeaders.length; i += 2) {
                headers.put(extraHeaders[i], extraHeaders[i + 1]);
            }
            return new FakeRequest(Map.of("page", pageId), headers, false);
        }

        /** Renders a counter for a browser without a session; returns {html, session}. */
        private String[] renderForNewBrowser(WebApp app) {
            WebApp.Response page = app.render(pageRequest(null), () -> {
                State<Integer> count = Web.state(0);
                return Web.h("button", Button.create().onclick(() -> count.update(n -> n + 1)), Web.dynamic(() -> count.get()));
            });
            String session = find(page.headers().get("Set-Cookie"), "jasx_session=([^;]+)");
            return new String[] {page.body(), session};
        }

        private String clickBody(String html) {
            return "{\"handler\":" + handler(html, "click", 1) + ",\"type\":\"click\"}";
        }

        @Test
        void aBrowserGetsASessionCookieWithItsFirstPageOnly() {
            WebApp.Response first = app.render(pageRequest(null), () -> Web.h("p", null, "x"));
            String cookie = first.headers().get("Set-Cookie");
            assertTrue(cookie.matches("jasx_session=[A-Za-z0-9_-]{22}; Path=/; HttpOnly; SameSite=Lax"), cookie);
            assertEquals("text/html; charset=utf-8", first.headers().get("Content-Type"));

            String session = find(cookie, "jasx_session=([^;]+)");
            WebApp.Response second = app.render(pageRequest(session), () -> Web.h("p", null, "y"));
            assertFalse(second.headers().containsKey("Set-Cookie"), "the browser keeps its session");

            WebApp.Response overHttps = app.render(new FakeRequest(Map.of(), Map.of(), true), () -> Web.h("p", null, "z"));
            assertTrue(overHttps.headers().get("Set-Cookie").endsWith("; Secure"));
        }

        @Test
        void aPageAnswersOnlyTheSessionThatRenderedIt() throws Exception {
            String[] page = renderForNewBrowser(app);
            String html = page[0];
            String session = page[1];
            String other = renderForNewBrowser(app)[1];

            assertEquals(200, app.event(eventRequest(pageId(html), session), clickBody(html)).status());
            // Another browser, or none, gets what an unknown page gets: nothing tells it the page exists.
            assertEquals(410, app.event(eventRequest(pageId(html), other), clickBody(html)).status());
            assertEquals(410, app.event(eventRequest(pageId(html), null), clickBody(html)).status());
            assertEquals(410, app.ping(eventRequest(pageId(html), other)).status());
            assertEquals(204, app.ping(eventRequest(pageId(html), session)).status());

            // Its stream: the page isn't taken from its own browser, and the other browser is told to reload.
            List<String> frames = new ArrayList<>();
            FakeRequest stream = new FakeRequest(Map.of("pages", pageId(html) + ":0"),
                    Map.of("Cookie", "jasx_session=" + other, "Sec-Fetch-Site", "same-origin"), false);
            assertTrue(app.stream(stream, new WebApp.EventStream() {
                @Override
                public void open() {}

                @Override
                public void send(String frame) {
                    frames.add(frame);
                }
            }).isEmpty());
            assertEquals(List.of(": connected\n\n", frame(html, "{\"reload\":true}")), frames);
        }

        @Test
        void eventsMustBeJsonFromTheSameSite() {
            String[] page = renderForNewBrowser(app);
            String id = pageId(page[0]);
            String body = clickBody(page[0]);
            assertEquals(415, app.event(eventRequest(id, page[1], "Content-Type", "text/plain"), body).status());
            assertEquals(415, app.event(eventRequest(id, page[1], "Content-Type", "application/x-www-form-urlencoded"), body).status());
            assertEquals(403, app.event(eventRequest(id, page[1], "Sec-Fetch-Site", "cross-site"), body).status());
            assertEquals(403, app.event(eventRequest(id, page[1], "Sec-Fetch-Site", "same-site"), body).status());
            assertEquals(200, app.event(eventRequest(id, page[1], "Content-Type", "application/json; charset=utf-8"), body).status());
        }

        @Test
        void aStreamFromAnotherSiteIsRefusedBeforeItOpens() throws Exception {
            boolean[] opened = {false};
            FakeRequest crossSite = new FakeRequest(Map.of("pages", "a:0"), Map.of("Sec-Fetch-Site", "cross-site"), false);
            var refused = app.stream(crossSite, new WebApp.EventStream() {
                @Override
                public void open() {
                    opened[0] = true;
                }

                @Override
                public void send(String frame) {}
            });
            assertEquals(403, refused.orElseThrow().status());
            assertFalse(opened[0]);
        }

        @Test
        void eventBodiesOverTheLimitAreRefused() {
            try (WebApp small = new WebApp("/_jasx", Duration.ofMinutes(2), Duration.ofSeconds(30), new WebApp.Limits(100, 50))) {
                String[] page = renderForNewBrowser(small);
                String padded = clickBody(page[0]).replace("}", ",\"pad\":\"" + "x".repeat(200) + "\"}");
                assertEquals(413, small.event(eventRequest(pageId(page[0]), page[1]), padded).status());
                assertEquals(200, small.event(eventRequest(pageId(page[0]), page[1]), clickBody(page[0])).status());
            }
        }

        @Test
        void aSessionKeepsAtMostItsLimitOfPagesDroppingTheOldest() {
            try (WebApp small = new WebApp("/_jasx", Duration.ofMinutes(2), Duration.ofSeconds(30), new WebApp.Limits(1024, 3))) {
                String[] first = renderForNewBrowser(small);
                String session = first[1];
                List<String> ids = new ArrayList<>(List.of(pageId(first[0])));
                for (int i = 0; i < 4; i++) {
                    ids.add(pageId(small.render(pageRequest(session), () -> Web.h("p", null, "x")).body()));
                }
                String otherBrowser = pageId(renderForNewBrowser(small)[0]);
                assertEquals(List.of(false, false, true, true, true), ids.stream().map(small::hasPage).toList());
                assertTrue(small.hasPage(otherBrowser), "another session's pages are not affected");
            }
        }

        @Test
        void onlyCookieValuesWebAppIssuesAreRead() {
            assertEquals("abcdefghijklmnopqrstuv", SessionCookie.read("a=1; jasx_session=abcdefghijklmnopqrstuv; b=2"));
            assertEquals(null, SessionCookie.read("jasx_session=short"));
            assertEquals(null, SessionCookie.read("jasx_session=abcdefghijklmnopqrstu!"));
            assertEquals(null, SessionCookie.read("other=abcdefghijklmnopqrstuv"));
            assertEquals(null, SessionCookie.read(null));
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

        @Test
        void aKeyedListKeepsUnchangedItemsWithTheirStateAndSendsOnlyNewOnes() {
            String html = app.render(compile("""
                    record Todo(int id, String title) {}
                    static Element Item(Todo todo) {
                      var open = state(false);
                      return <li><button onclick={() -> open.set(!open.get())}>{todo.title()}</button>{open.get() ? "open" : "closed"}</li>;
                    }
                    static Element Page() {
                      var todos = state(List.of(new Todo(1, "a"), new Todo(2, "b")));
                      return (
                        <div>
                          <button onclick={() -> todos.update(l -> { var n = new ArrayList<>(l); n.add(new Todo(3, "c")); return n; })}>add</button>
                          <button onclick={() -> todos.update(l -> List.of(new Todo(1, "A"), l.get(1), l.get(2)))}>rename</button>
                          <ul>{for (Todo t : todos.get()) { <Item key={t.id()} todo={t} /> }}</ul>
                        </div>
                      );
                    }
                    """));
            int add = handler(html, "click", 1);
            int rename = handler(html, "click", 2);
            int toggleFirst = handler(html, "click", 3);
            String page = pageId(html);

            assertTrue(click(html, toggleFirst).body().contains("\"html\":\"open\""));

            // Adding a third to-do: the first two are kept (their ids only), the third is sent.
            String added = app.event(page, "{\"handler\":" + add + ",\"type\":\"click\"}").body();
            assertTrue(added.matches(".*\\{\"op\":\"list\",\"id\":\\d+,\"items\":\\[\\d+,\\d+,\\{\"id\":\\d+,\"html\":\"[^}]*c[^}]*\"\\}\\]\\}.*"), added);

            // The first item was kept with its state and its handler: toggling it again closes it.
            assertTrue(click(html, toggleFirst).body().contains("\"html\":\"closed\""));

            // Renaming the first to-do changes its record: only that item is rendered afresh. Its shape was seen
            // when "c" was added, so its statics go once in a tpl patch, and the item carries only its values (§8.4).
            String renamed = app.event(page, "{\"handler\":" + rename + ",\"type\":\"click\"}").body();
            assertTrue(renamed.contains("{\"op\":\"tpl\""), renamed);
            assertTrue(renamed.matches(".*\"items\":\\[\\{\"id\":\\d+,\"t\":\\d+,\"v\":\\[[^\\]]*\"A\"[^\\]]*\\]\\},\\d+,\\d+\\].*"), renamed);
            assertEquals(204, click(html, toggleFirst).status(), "the replaced item's handler is gone");
        }

        @Test
        void eachEventAttributeGivesItsHandlerItsKindOfEvent() {
            String html = app.render(compile("""
                    record Todo(String title, int count) {}
                    static Element Page() {
                      var log = state("");
                      return (
                        <div>
                          <button onclick={() -> log.set("action")}>a</button>
                          <button onclick={e -> log.set("mouse " + e.button() + " " + e.ctrl())}>m</button>
                          <input onkeydown={e -> log.set("key " + e.key() + " " + e.value())} />
                          <form onsubmit={e -> log.set("form " + e.as(Todo.class))}><input name="title" /></form>
                          <p>{log}</p>
                        </div>
                      );
                    }
                    """));
            String page = pageId(html);
            assertTrue(app.event(page, "{\"handler\":" + handler(html, "click", 1) + ",\"type\":\"click\"}")
                    .body().contains("\"html\":\"action\""));
            assertTrue(app.event(page, "{\"handler\":" + handler(html, "click", 2) + ",\"type\":\"click\",\"button\":2,\"ctrl\":true}")
                    .body().contains("\"html\":\"mouse 2 true\""));
            assertTrue(app.event(page, "{\"handler\":" + handler(html, "keydown", 1) + ",\"type\":\"keydown\",\"key\":\"Enter\",\"value\":\"hi\"}")
                    .body().contains("\"html\":\"key Enter hi\""));
            String form = app.event(page, "{\"handler\":" + handler(html, "submit", 1)
                    + ",\"type\":\"submit\",\"fields\":{\"title\":[\"Milk\"],\"count\":[\"2\"]}}").body();
            assertTrue(form.contains("\"html\":\"form Todo[title=Milk, count=2]\""), form);
        }

        @Test
        void aHandlerCantUseWhatItsKindOfEventDoesntHave() {
            SourceFile file = new SourceFile("T.jasx", "import jasx.runtime.Element;\nimport static jasx.web.Web.*;\n\n"
                    + "class T {\n  static Element Page() {\n"
                    + "    return <button onclick={e -> System.out.println(e.field(\"x\"))}>x</button>;\n  }\n}\n");
            CompilationResult result = new JasxCompiler(WEB).compile(List.of(file));
            assertFalse(result.hasErrors(), () -> format(result.diagnostics()));
            JavacRunner.Result compiled = JavacRunner.compile(result.files(), List.of(), CLASSPATH);
            assertFalse(compiled.success());
            CompilerDiagnostic error = compiled.diagnostics().get(0);
            assertTrue(error.format().contains("T.jasx:6"), error.format()); // reported at its .jasx line
            assertTrue(error.format().contains("field"), error.format());
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
                // The browser's session (§6.3): sent back with every request, as a browser does.
                String cookie = page.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];

                String eventUrl = base + "/_jasx/event?page=" + pageId(page.body());
                String event = "{\"handler\":" + handler(page.body(), "click", 1) + ",\"type\":\"click\"}";
                HttpRequest.Builder post = HttpRequest.newBuilder(URI.create(eventUrl))
                        .POST(HttpRequest.BodyPublishers.ofString(event)).header("Content-Type", "application/json");
                HttpResponse<String> answer = client.send(post.copy().header("Cookie", cookie).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, answer.statusCode());
                assertTrue(answer.body().contains("\"html\":\"6\""), answer.body());
                assertEquals(410, client.send(post.build(), HttpResponse.BodyHandlers.ofString()).statusCode(),
                        "without the browser's session, the page is unknown");

                HttpResponse<String> huge = client.send(HttpRequest.newBuilder(URI.create(eventUrl)).header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("x".repeat(2 * 1024 * 1024))).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(413, huge.statusCode());

                HttpResponse<String> script = client.send(HttpRequest.newBuilder(URI.create(base + "/_jasx/web.js")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, script.statusCode());
                assertTrue(script.body().contains("data-jx-on"));

                HttpResponse<String> gone = client.send(HttpRequest.newBuilder(URI.create(base + "/_jasx/stream?pages=nope:0")).build(),
                        HttpResponse.BodyHandlers.ofString());
                // The stream of an unknown page tells its tab to reload (§6.1), and ends: it has nothing else.
                assertEquals(200, gone.statusCode());
                assertEquals(": connected\n\ndata: {\"page\":\"nope\",\"batch\":{\"reload\":true}}\n\n", gone.body());
            } finally {
                server.stop();
            }
        }
    }

}
