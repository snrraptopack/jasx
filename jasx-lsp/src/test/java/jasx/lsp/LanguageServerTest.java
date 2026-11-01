package jasx.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jasx.compiler.ComponentIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LanguageServerTest {

    @Nested
    class JsonFormat {
        @Test
        void roundTrip() {
            String json = "{\"a\":[1,2.5,true,null,\"x\\\"y\\n\"],\"b\":{}}";
            assertEquals(json, Json.write(Json.parse(json)));
        }

        @Test
        void unicodeEscapesAreRead() {
            // Built at run time so that no escape appears in this source file.
            String escape = "\\" + "u0041";
            assertEquals("A", Json.parse("\"" + escape + "\""));
        }

        @Test
        void controlCharactersAreEscaped() {
            assertEquals("\"\\" + "u0001\"", Json.write("\u0001".replace("\u0001", String.valueOf((char) 1))));
        }
    }

    @Nested
    class Framing {
        @Test
        void readsMessagesByByteLength() throws IOException {
            String body = "{\"text\":\"é\"}"; // é is two bytes in UTF-8
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String input = "Content-Length: " + bytes.length + "\r\nContent-Type: x\r\n\r\n" + body
                    + "Content-Length: 2\r\n\r\n{}";
            Transport transport = new Transport(
                    new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream());
            assertEquals(body, transport.read());
            assertEquals("{}", transport.read());
            assertNull(transport.read());
        }

        @Test
        void writesTheHeader() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new Transport(new ByteArrayInputStream(new byte[0]), out).write("{\"a\":\"é\"}");
            assertEquals("Content-Length: 10\r\n\r\n{\"a\":\"é\"}", out.toString(StandardCharsets.UTF_8));
        }
    }

    /** The server, driven with JSON messages, on a small Gradle-style project in a temporary directory. */
    @Nested
    class Server {
        @TempDir
        Path project;

        private final List<Map<String, Object>> sent = new ArrayList<>();
        private JasxLanguageServer server;
        private Path ui;
        private Path page;
        private int nextId = 1;

        private static final String UI = """
                package app;

                import jasx.runtime.Element;
                import java.util.List;

                /** A card. */
                public Element Card(String title, List<Element> children) {
                  return <section><h2>{title}</h2>{children}</section>;
                }
                """;

        private static final String PAGE = """
                package app;

                import jasx.runtime.Element;

                class Page {
                  static Element Home() {
                    return <Ui.Card title="Hi"><p>body</p></Ui.Card>;
                  }
                }
                """;

        @BeforeEach
        void setUp() throws IOException {
            Path sources = Files.createDirectories(project.resolve("src/main/java/app"));
            ui = Files.writeString(sources.resolve("Ui.jasx"), UI);
            page = Files.writeString(sources.resolve("Page.jasx"), PAGE);
            // Runnable::run: javac checks finish before the message that started them is handled.
            server = new JasxLanguageServer(json -> sent.add(Json.object(Json.parse(json))), Runnable::run);
            request("initialize", Map.of("rootUri", project.toUri().toString()));
            notification("initialized", Map.of());
        }

        // --- Sending ---------------------------------------------------------------------------

        private Map<String, Object> request(String method, Map<String, Object> params) {
            int id = nextId++;
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("jsonrpc", "2.0");
            message.put("id", id);
            message.put("method", method);
            message.put("params", params);
            server.handle(Json.write(message));
            return sent.stream()
                    .filter(m -> Long.valueOf(id).equals(m.get("id")))
                    .findFirst().orElseThrow();
        }

        private void notification(String method, Map<String, Object> params) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("jsonrpc", "2.0");
            message.put("method", method);
            message.put("params", params);
            server.handle(Json.write(message));
        }

        private static String uri(Path file) {
            return file.toUri().toString();
        }

        private void open(Path file, String text) {
            notification("textDocument/didOpen", Map.of("textDocument",
                    Map.of("uri", uri(file), "languageId", "jasx", "version", 1, "text", text)));
        }

        private void change(Path file, String text) {
            notification("textDocument/didChange", Map.of(
                    "textDocument", Map.of("uri", uri(file), "version", 2),
                    "contentChanges", List.of(Map.of("text", text))));
        }

        private static Map<String, Object> position(Path file, int line, int character) {
            return Map.of("textDocument", Map.of("uri", uri(file)),
                    "position", Map.of("line", line, "character", character));
        }

        // --- Reading -----------------------------------------------------------------------------

        /** The diagnostics most recently published for {@code file}, as "code@line:column". */
        private List<String> diagnosticsOf(Path file) {
            Map<String, Object> last = null;
            for (Map<String, Object> message : sent) {
                Map<String, Object> params = Json.object(message.get("params"));
                boolean forFile = "textDocument/publishDiagnostics".equals(message.get("method"))
                        && Workspace.toPath(Json.string(params.get("uri"))).equals(file.toAbsolutePath().normalize());
                if (forFile) {
                    last = params;
                }
            }
            assertTrue(last != null, "no diagnostics were published for " + file);
            List<String> codes = new ArrayList<>();
            for (Object d : (List<?>) last.get("diagnostics")) {
                Map<String, Object> diagnostic = Json.object(d);
                Map<String, Object> start = Json.object(Json.object(diagnostic.get("range")).get("start"));
                codes.add(diagnostic.get("code") + "@" + start.get("line") + ":" + start.get("character"));
            }
            return codes;
        }

        // --- Tests -------------------------------------------------------------------------------

        @Test
        void announcesItsCapabilities() {
            Map<String, Object> init = sent.get(0);
            Map<String, Object> capabilities = Json.object(Json.object(init.get("result")).get("capabilities"));
            assertEquals(true, capabilities.get("hoverProvider"));
            assertEquals(true, capabilities.get("definitionProvider"));
        }

        @Test
        void asksTheClientToWatchFilesOnlyIfItCan() {
            // setUp's client didn't say it supports registration, so nothing was asked.
            assertTrue(sent.stream().noneMatch(m -> "client/registerCapability".equals(m.get("method"))));

            sent.clear();
            server = new JasxLanguageServer(json -> sent.add(Json.object(Json.parse(json))), Runnable::run);
            Map<String, Object> capabilities = Map.of("workspace",
                    Map.of("didChangeWatchedFiles", Map.of("dynamicRegistration", true)));
            request("initialize", Map.of("rootUri", project.toUri().toString(), "capabilities", capabilities));
            notification("initialized", Map.of());

            Map<String, Object> registration = sent.stream()
                    .filter(m -> "client/registerCapability".equals(m.get("method")))
                    .findFirst().orElseThrow();
            String watchers = Json.write(Json.object(registration.get("params")).get("registrations"));
            assertTrue(watchers.contains("\"workspace/didChangeWatchedFiles\""), watchers);
            assertTrue(watchers.contains("\"**/*.jasx\"") && watchers.contains("\"**/*.java\""), watchers);
        }

        @Test
        void reportsErrorsWhileTyping() {
            open(page, PAGE);
            assertEquals(List.of(), diagnosticsOf(page));

            // A closing tag that doesn't match, and an unknown prop: line 6 (0-based).
            change(page, PAGE.replace("</Ui.Card>", "</Ui.Cards>").replace("title=\"Hi\"", "titel=\"Hi\""));
            assertEquals(List.of("JASX1001@6:42"), diagnosticsOf(page));

            change(page, PAGE.replace("title=\"Hi\"", "titel=\"Hi\""));
            // "titel" is unknown (JASX2005), and the required "title" is missing (JASX2007).
            assertEquals(List.of("JASX2005@6:20", "JASX2007@6:11"), diagnosticsOf(page));

            change(page, PAGE);
            assertEquals(List.of(), diagnosticsOf(page)); // cleared
        }

        @Test
        void theJavaAndJasxDirectoriesOfASourceSetAreOneGroup() throws IOException {
            // The build compiles src/main/java and src/main/jasx together, so Ui.Card (in java/) is known in jasx/.
            Path other = Files.createDirectories(project.resolve("src/main/jasx/app")).resolve("Other.jasx");
            String text = PAGE.replace("class Page", "class Other");
            Files.writeString(other, text);
            open(other, text);
            assertEquals(List.of(), diagnosticsOf(other));
        }

        @Test
        void filesOutsideSourceDirectoriesKeepTheirOwnAnalyses() throws IOException {
            Path loose = Files.createDirectories(project.resolve("scratch"));
            String a = """
                    import jasx.runtime.Element;
                    class A {
                      /** A card. */
                      static Element Card() { return <p>a</p>; }
                      static Element a() { return <Card />; }
                    }
                    """;
            Path first = Files.writeString(loose.resolve("A.jasx"), a);
            Path second = Files.writeString(loose.resolve("B.jasx"), "class B { Object b() { return <p>b</p>; } }\n");
            open(first, a);
            open(second, Files.readString(second));
            // Before, both were cached under their directory, and B's analysis replaced A's: hover found nothing.
            Map<String, Object> hover = request("textDocument/hover", position(first, 4, 33));
            assertTrue(String.valueOf(hover.get("result")).contains("A.Card()"), hover.toString());
            assertEquals(List.of(), diagnosticsOf(second));
        }

        @Test
        void unsavedChangesInOneFileAffectAnother() {
            // Rename the prop in Ui.jasx without saving: Page.jasx now passes an unknown prop.
            open(page, PAGE);
            open(ui, UI.replace("String title", "String heading"));
            assertEquals(List.of("JASX2005@6:20", "JASX2007@6:11"), diagnosticsOf(page));
        }

        @Test
        void goesToTheComponentsDeclaration() {
            open(page, PAGE);
            // Line 6 is "    return <Ui.Card ...": the cursor on "Card" of the opening tag.
            Map<String, Object> result = Json.object(request("textDocument/definition", position(page, 6, 16)).get("result"));
            assertEquals(ui.toAbsolutePath().normalize(), Workspace.toPath(Json.string(result.get("uri"))));
            Map<String, Object> start = Json.object(Json.object(result.get("range")).get("start"));
            assertEquals(6L, start.get("line"));      // "public Element Card(" is on line 6
            assertEquals(15L, start.get("character"));
        }

        @Test
        void describesAComponentOnHover() {
            open(page, PAGE);
            Map<String, Object> result = Json.object(request("textDocument/hover", position(page, 6, 13)).get("result"));
            String markdown = Json.string(Json.object(result.get("contents")).get("value"));
            assertTrue(markdown.contains("app.Ui.Card(String title, List<Element> children)"), markdown);
            assertTrue(markdown.contains("- `title`: required"), markdown);
            assertTrue(markdown.contains("- `children`: the children between the tags"), markdown);
        }

        // --- Completion --------------------------------------------------------------------------

        /**
         * Opens {@code file} with {@code text}, in which {@code |} marks the cursor, and requests completion
         * there. Returns the items.
         */
        private List<Map<String, Object>> completeAt(Path file, String text) {
            int cursor = text.indexOf('|');
            String before = text.substring(0, cursor);
            int line = (int) before.chars().filter(c -> c == '\n').count();
            int character = cursor - (before.lastIndexOf('\n') + 1);
            open(file, text.substring(0, cursor) + text.substring(cursor + 1));
            Map<String, Object> list = Json.object(request("textDocument/completion", position(file, line, character)).get("result"));
            List<Map<String, Object>> items = new ArrayList<>();
            for (Object item : (List<?>) list.get("items")) {
                items.add(Json.object(item));
            }
            return items;
        }

        private static List<String> labels(List<Map<String, Object>> items) {
            return items.stream().map(item -> Json.string(item.get("label"))).toList();
        }

        private static Map<String, Object> item(List<Map<String, Object>> items, String label) {
            return items.stream().filter(i -> label.equals(i.get("label"))).findFirst().orElseThrow();
        }

        private static String pageWith(String returned) {
            return PAGE.replace("<Ui.Card title=\"Hi\"><p>body</p></Ui.Card>;", returned);
        }

        @Test
        void announcesCompletion() {
            Map<String, Object> init = sent.get(0);
            Map<String, Object> capabilities = Json.object(Json.object(init.get("result")).get("capabilities"));
            Map<String, Object> completion = Json.object(capabilities.get("completionProvider"));
            assertEquals(List.of("<", " ", "."), completion.get("triggerCharacters"));
        }

        @Test
        void tagNamesAreComponentsAndElements() {
            List<Map<String, Object>> items = completeAt(page, pageWith("<|"));
            // Ui.Card isn't imported into Page, so it is offered by its class and name.
            assertTrue(labels(items).contains("Ui.Card"), labels(items).toString());
            assertTrue(labels(items).contains("div"), labels(items).toString());
            String documentation = Json.string(Json.object(item(items, "Ui.Card").get("documentation")).get("value"));
            assertTrue(documentation.contains("- `title`: required"), documentation);
        }

        @Test
        void aComponentInTheSameClassIsOfferedByItsSimpleName() {
            String text = UI + "\nElement Other() { return <|; }\n";
            assertTrue(labels(completeAt(ui, text)).contains("Card"));
        }

        @Test
        void aTypedPrefixIsReplaced() {
            List<Map<String, Object>> items = completeAt(page, pageWith("<Ui.Ca|"));
            Map<String, Object> edit = Json.object(item(items, "Ui.Card").get("textEdit"));
            Map<String, Object> start = Json.object(Json.object(edit.get("range")).get("start"));
            // Line 6 is "    return <Ui.Ca": the replaced text starts after '<', at column 12.
            assertEquals(6L, start.get("line"));
            assertEquals(12L, start.get("character"));
            assertEquals("Ui.Card", edit.get("newText"));
        }

        @Test
        void attributeNamesOfAComponentAreItsProps() {
            List<Map<String, Object>> items = completeAt(page, pageWith("<Ui.Card |"));
            // The children parameter is not an attribute.
            assertEquals(List.of("title"), labels(items));
            // This client didn't announce snippet support, so the value is left empty, without "$1".
            Map<String, Object> edit = Json.object(item(items, "title").get("textEdit"));
            assertEquals("title=\"\"", edit.get("newText"));
            assertEquals(1L, item(items, "title").get("insertTextFormat"));
        }

        @Test
        void propsAlreadyGivenAreNotOffered() {
            assertEquals(List.of(), labels(completeAt(page, pageWith("<Ui.Card title=\"x\" |"))));
        }

        @Test
        void attributeNamesOfAnElementComeFromTheSchema() {
            List<Map<String, Object>> items = completeAt(page, pageWith("<a hr|"));
            assertTrue(labels(items).contains("href"), labels(items).toString());
            assertTrue(labels(items).contains("onclick"), labels(items).toString());
            assertEquals("onclick={}", Json.object(item(items, "onclick").get("textEdit")).get("newText"));
        }

        @Test
        void markupInsideAnExpressionIsCompleted() {
            List<Map<String, Object>> items = completeAt(page, pageWith("<ul>{java.util.List.of(1).stream().map(i -> <|"));
            assertTrue(labels(items).contains("li"), labels(items).toString());
        }

        @Test
        void markupStatementsAreCompleted() {
            List<Map<String, Object>> tags = completeAt(page, pageWith("<ul>{for (int i = 0; i < 3; i++) { <|"));
            assertTrue(labels(tags).contains("li"), labels(tags).toString());
            List<Map<String, Object>> props = completeAt(page, pageWith("<ul>{if (true) { <Ui.Card |"));
            assertEquals(List.of("title"), labels(props));
        }

        @Test
        void nothingIsOfferedInAttributeStringsOrText() {
            assertEquals(List.of(), labels(completeAt(page, pageWith("<a href=\"x|"))));
            assertEquals(List.of(), labels(completeAt(page, pageWith("<p>some te|"))));
        }

        // --- Java completion ----------------------------------------------------------------------

        @Test
        void javaMembersAfterADotHaveTheirGenericsResolved() {
            List<Map<String, Object>> items = completeAt(page, pageWith("<p>{java.util.List.of(\"a\").|}</p>;"));
            assertTrue(labels(items).contains("size()"), labels(items).toString());
            assertEquals("String get(int)", item(items, "get(int)").get("detail"));
            // A comparison isn't markup (grammar.md §4): Java names are completed there too.
            List<Map<String, Object>> afterLess = completeAt(page, pageWith("1 < Ho|"));
            assertTrue(labels(afterLess).contains("Home()"), labels(afterLess).toString());
        }

        @Test
        void javaNamesInScopeInsideMarkup() {
            List<Map<String, Object>> items = completeAt(page, pageWith("<p>{Ho|}</p>;"));
            assertTrue(labels(items).contains("Home()"), labels(items).toString());
            assertTrue(labels(items).contains("Element"), "an imported type: " + labels(items));
            assertFalse(labels(items).stream().anyMatch(label -> label.contains("$")), "no names JASX made up");
        }

        /** A page on the web target, with its state and typed events. */
        private static final String WEB_PAGE = """
                package app;

                import jasx.runtime.Element;
                import static jasx.web.Web.*;

                class Page {
                  static Element Home() {
                    var count = state(0);
                    return MARKUP;
                  }
                }
                """;

        @Test
        void aHandlerInMarkupKnowsItsKindOfEvent() throws IOException {
            writeBuildInfo("web", List.of());
            String click = WEB_PAGE.replace("MARKUP", "<button onclick={e -> e.|}>x</button>");
            List<String> mouse = labels(completeAt(page, click));
            assertTrue(mouse.contains("button()") && mouse.contains("ctrl()"), mouse.toString());
            assertFalse(mouse.contains("field(String)"), "a click has no form fields: " + mouse);

            String submit = WEB_PAGE.replace("MARKUP", "<form onsubmit={e -> { e.| }}></form>");
            List<String> form = labels(completeAt(page, submit));
            assertTrue(form.contains("field(String)") && form.contains("resetForm()"), form.toString());
        }

        /** Like {@link #completeAt}, but the whole CompletionList. */
        private Map<String, Object> completionListAt(Path file, String text) {
            int cursor = text.indexOf('|');
            String before = text.substring(0, cursor);
            int line = (int) before.chars().filter(c -> c == '\n').count();
            int character = cursor - (before.lastIndexOf('\n') + 1);
            open(file, text.substring(0, cursor) + text.substring(cursor + 1));
            return Json.object(request("textDocument/completion", position(file, line, character)).get("result"));
        }

        @Test
        void typesOfJavaLangAndOfTheOwnPackageAreInScope() {
            List<String> labels = labels(completeAt(page, pageWith("<p>{Str|}</p>;")));
            assertTrue(labels.contains("String") && labels.contains("StringBuilder"), labels.toString());
            assertTrue(labels.contains("Ui"), "Ui.jasx's class is in the same package: " + labels);
            Map<String, Object> string = item(completeAt(page, pageWith("<p>{Str|}</p>;")), "String");
            assertNull(string.get("additionalTextEdits"), "java.lang needs no import");
        }

        @Test
        void aTypeThatIsNotImportedBringsItsImport() {
            Map<String, Object> list = completionListAt(page, pageWith("<p>{ArrayL|}</p>;"));
            List<Map<String, Object>> items = new ArrayList<>();
            ((List<?>) list.get("items")).forEach(i -> items.add(Json.object(i)));
            Map<String, Object> arrayList = item(items, "ArrayList");
            assertEquals("java.util.ArrayList", arrayList.get("detail"));
            Map<String, Object> edit = Json.object(((List<?>) arrayList.get("additionalTextEdits")).get(0));
            assertEquals("import java.util.ArrayList;\n", edit.get("newText"));
            // After "import jasx.runtime.Element;", which is line 2 (0-based).
            assertEquals(3L, Json.object(Json.object(edit.get("range")).get("start")).get("line"));
        }

        @Test
        void typesThatAreNotImportedNeedAFirstLetter() {
            Map<String, Object> list = completionListAt(page, pageWith("<p>{|}</p>;"));
            assertEquals(true, list.get("isIncomplete"), "the editor must ask again once a letter is typed");
            String labels = String.valueOf(list.get("items"));
            assertFalse(labels.contains("java.util.ArrayList"), "not offered before a letter is typed");
        }

        @Test
        void onDemandImportsBringTheirTypesIntoScope() {
            String text = PAGE.replace("import jasx.runtime.Element;", "import jasx.runtime.Element;\nimport java.util.*;")
                    .replace("<Ui.Card title=\"Hi\"><p>body</p></Ui.Card>;", "<p>{LinkedL|}</p>;");
            Map<String, Object> linked = item(completeAt(page, text), "LinkedList");
            assertNull(linked.get("additionalTextEdits"), "java.util.* is imported already");
        }

        @Test
        void aPackageNameIsFollowedByItsTypes() {
            List<String> labels = labels(completeAt(page, pageWith("<p>{java.util.Arr|}</p>;")));
            assertTrue(labels.contains("ArrayList") && labels.contains("Arrays"), labels.toString());
        }

        @Test
        void keywordsAreOffered() {
            List<String> labels = labels(completeAt(page, pageWith("<p>{ne|}</p>;")));
            assertTrue(labels.contains("new"), labels.toString());
        }

        @Test
        void typeNamesMatchByTheirLetters() {
            assertTrue(JavaCompletions.matches("ArrayList", "ArLi"));
            assertTrue(JavaCompletions.matches("ArrayList", "alist"));
            assertFalse(JavaCompletions.matches("ArrayList", "List"), "the first letter must match");
            assertFalse(JavaCompletions.matches("ArrayList", "Arz"));
        }

        @Test
        void importsGoAfterTheLastImportOrThePackage() {
            assertEquals(3, Workspace.importPlace("package a;\n\nimport b.C;\nclass D {}\n").line());
            Workspace.ImportPlace afterPackage = Workspace.importPlace("package a;\nclass D {}\n");
            assertEquals(1, afterPackage.line());
            assertEquals("\n", afterPackage.before());
            assertEquals(0, Workspace.importPlace("class D { Object x = <p>import a;</p>; }\n").line());
        }

        // --- Hover beyond component tags ---------------------------------------------------------

        /** Opens {@code file} with {@code text}, in which {@code |} marks the cursor, and returns the hover's Markdown. */
        private String hoverAt(Path file, String text) {
            int cursor = text.indexOf('|');
            String before = text.substring(0, cursor);
            int line = (int) before.chars().filter(c -> c == '\n').count();
            int character = cursor - (before.lastIndexOf('\n') + 1);
            open(file, text.substring(0, cursor) + text.substring(cursor + 1));
            Object result = request("textDocument/hover", position(file, line, character)).get("result");
            return result == null ? null : Json.string(Json.object(Json.object(result).get("contents")).get("value"));
        }

        @Test
        void aStateVariableShowsItsType() throws IOException {
            writeBuildInfo("web", List.of());
            String hover = hoverAt(page, WEB_PAGE.replace("MARKUP", "<p>{cou|nt}</p>"));
            assertTrue(hover != null && hover.contains("State<Integer> count"), String.valueOf(hover));
        }

        @Test
        void aHandlersParameterShowsItsKindOfEvent() throws IOException {
            writeBuildInfo("web", List.of());
            String hover = hoverAt(page, WEB_PAGE.replace("MARKUP", "<button onclick={|e -> e.button()}>x</button>"));
            assertTrue(hover != null && hover.contains("MouseEvent e"), String.valueOf(hover));

            String method = hoverAt(page, WEB_PAGE.replace("MARKUP", "<button onclick={e -> e.butt|on()}>x</button>"));
            assertTrue(method != null && method.contains("MouseEvent.button()"), String.valueOf(method));
        }

        @Test
        void aMethodOfAGenericTypeShowsItsActualTypes() throws IOException {
            writeBuildInfo("web", List.of());
            String hover = hoverAt(page, WEB_PAGE.replace("MARKUP", "<p>{count.g|et()}</p>"));
            assertTrue(hover != null && hover.contains("Integer State.get()"), String.valueOf(hover));
        }

        @Test
        void anElementAndItsAttributesDescribeThemselves() {
            String element = hoverAt(page, pageWith("<sec|tion class=\"x\"></section>;"));
            assertTrue(element != null && element.contains("<section>") && element.contains("MDN"), String.valueOf(element));
            String attribute = hoverAt(page, pageWith("<section cla|ss=\"x\"></section>;"));
            assertTrue(attribute != null && attribute.contains("Global attribute"), String.valueOf(attribute));
        }

        @Test
        void aPropShowsItsTypeAndHowItIsPassed() {
            String hover = hoverAt(page, PAGE.replace("title=\"Hi\"", "tit|le=\"Hi\""));
            assertTrue(hover != null && hover.contains("String title") && hover.contains("required"), String.valueOf(hover));
        }

        @Test
        void nothingOnPlainText() {
            assertNull(hoverAt(page, pageWith("<p>some te|xt</p>;")));
        }

        @Test
        void stateMembersShowTheirValueType() throws IOException {
            writeBuildInfo("web", List.of());
            List<Map<String, Object>> items = completeAt(page,
                    WEB_PAGE.replace("MARKUP", "<button onclick={() -> count.|}>x</button>"));
            assertEquals("Integer get()", item(items, "get()").get("detail"));
            assertTrue(labels(items).contains("update(UnaryOperator<Integer>)"), labels(items).toString());
        }

        // --- What the build says ----------------------------------------------------------------

        /**
         * Writes the file the Gradle plugin writes ({@code build/jasx/editor/main.json}): the test's own
         * classpath (which has the html target and the runtime), plus {@code extraJasxClasspath}.
         */
        private void writeBuildInfo(List<String> extraJasxClasspath) throws IOException {
            writeBuildInfo("html", extraJasxClasspath);
        }

        private void writeBuildInfo(String target, List<String> extraJasxClasspath) throws IOException {
            List<String> classpath = List.of(System.getProperty("java.class.path").split(File.pathSeparator));
            List<String> jasxClasspath = new ArrayList<>(classpath);
            jasxClasspath.addAll(extraJasxClasspath);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("target", target);
            info.put("jasxClasspath", jasxClasspath);
            info.put("compileClasspath", classpath);
            info.put("processorPath", List.of());
            info.put("release", null);
            info.put("generatedSources", project.resolve("build/generated/sources/jasx/main").toString());
            Path file = project.resolve("build/jasx/editor/main.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(info));
        }

        @Test
        void componentsOfTheBuildsDependenciesAreKnown() throws IOException {
            String text = pageWith("<lib.Lib.Badge text=\"x\" />;");
            open(page, text);
            assertTrue(diagnosticsOf(page).get(0).startsWith("JASX2001@"), diagnosticsOf(page).toString());

            // A dependency of the build: a directory with a component index, as a jar would have.
            ComponentIndex index = new ComponentIndex();
            index.add(new ComponentIndex.Component("lib.Lib", "Badge",
                    List.of(new ComponentIndex.Prop("text", "String", ComponentIndex.PropKind.REQUIRED, null)),
                    ComponentIndex.Children.NONE));
            Path library = project.resolve("library");
            Files.createDirectories(library.resolve("META-INF/jasx"));
            Files.writeString(library.resolve(ComponentIndex.RESOURCE), index.toJson());
            writeBuildInfo(List.of(library.toString()));

            change(page, text);
            // The made-up library has an index but no class, which javac reports; JASX knows the component.
            List<String> jasxDiagnostics = diagnosticsOf(page).stream().filter(d -> !d.startsWith("javac@")).toList();
            assertEquals(List.of(), jasxDiagnostics);
        }

        @Test
        void javacErrorsFollowTheTextWhenTypingPauses() throws IOException {
            writeBuildInfo(List.of());
            String text = pageWith("<p>{undefinedName}</p>;");

            open(page, text); // opening starts a javac check
            // Line 6 is "    return <p>{undefinedName}": the name is at column 15.
            assertEquals(List.of("javac@6:15"), diagnosticsOf(page));

            // A change checks again once typing pauses (at once in this test): the error moved a line down.
            change(page, text.replace("class Page {", "class Page {\n"));
            assertEquals(List.of("javac@7:15"), diagnosticsOf(page));

            change(page, PAGE);
            assertEquals(List.of(), diagnosticsOf(page));
        }

        @Test
        void aJavacErrorIsHiddenWhileItsTextIsBeingEdited() throws IOException {
            writeBuildInfo(List.of());
            String text = pageWith("<p>{undefinedName}</p>;");
            open(page, text);

            // A server that waits for a pause: the change hides the error, the late check is dropped.
            List<Runnable> waiting = new ArrayList<>();
            sent.clear();
            server = new JasxLanguageServer(json -> sent.add(Json.object(Json.parse(json))), waiting::add, 60_000);
            request("initialize", Map.of("rootUri", project.toUri().toString()));
            open(page, text);
            waiting.forEach(Runnable::run); // the check that opening started
            assertEquals(List.of("javac@6:15"), diagnosticsOf(page));

            change(page, text + "\n");
            assertEquals(List.of(), diagnosticsOf(page), "hidden until typing pauses: it may have moved");
        }

        @Test
        void theGeneratedJavaFollowsTheEditor() throws IOException {
            writeBuildInfo(List.of());
            Path generated = project.resolve("build/generated/sources/jasx/main/app/Page.java");

            open(page, PAGE);
            assertTrue(Files.readString(generated).contains("Home()"), "written on open");

            // An unsaved rename reaches the generated Java once typing pauses (at once in this test), so the
            // editor's Java support sees it in .java files.
            change(page, PAGE.replace("Home()", "Start()"));
            assertTrue(Files.readString(generated).contains("Start()"), Files.readString(generated));

            // A file with a JASX error keeps its last good Java.
            change(page, PAGE.replace("Home()", "Broken()").replace("</Ui.Card>", "</Ui.Cards>"));
            assertTrue(Files.readString(generated).contains("Start()"), "kept while the file has errors");
        }

        @Test
        void withoutBuildInformationJavacDoesNotRun() {
            open(page, pageWith("<p>{undefinedName}</p>;"));
            assertEquals(List.of(), diagnosticsOf(page));
        }

        @Test
        void nothingOutsideAComponentTag() {
            open(page, PAGE);
            assertNull(request("textDocument/hover", position(page, 0, 2)).get("result"));
            assertNull(request("textDocument/definition", position(page, 6, 30)).get("result"));
        }

        @Test
        void aFileOutsideASourceDirectoryIsAnalysedAlone() throws IOException {
            Path loose = Files.writeString(project.resolve("Scratch.jasx"), "class Scratch { Object x = <p></q>; }\n");
            open(loose, Files.readString(loose));
            assertEquals(List.of("JASX1001@0:30"), diagnosticsOf(loose));
        }

        @Test
        void unknownRequestsGetAnError() {
            Map<String, Object> response = request("textDocument/formatting", Map.of());
            assertEquals(-32601L, Json.object(response.get("error")).get("code"));
        }

        @Test
        void shutdownThenExit() {
            request("shutdown", Map.of());
            notification("exit", Map.of());
            assertTrue(server.exitRequested());
            assertEquals(0, server.exitCode());
        }
    }

    @Nested
    class Uris {
        @Test
        void vsCodeStyleWindowsUrisAreFiles() {
            if (!System.getProperty("os.name").toLowerCase().contains("win")) {
                return;
            }
            assertEquals(Path.of("C:/Users/x/A.jasx"), Workspace.toPath("file:///c%3A/Users/x/A.jasx"));
        }

        @Test
        void sourceDirectories() {
            Path file = Path.of("/p/src/main/java/app/A.jasx").toAbsolutePath();
            assertEquals(Path.of("/p/src/main/java").toAbsolutePath(), Workspace.sourceDirectory(file));
            assertEquals(Path.of("/p/src/test/jasx").toAbsolutePath(),
                    Workspace.sourceDirectory(Path.of("/p/src/test/jasx/B.jasx").toAbsolutePath()));
            assertNull(Workspace.sourceDirectory(Path.of("/p/other/C.jasx").toAbsolutePath()));
        }

        @Test
        void buildInformationOfGradleAndMaven(@TempDir Path project) throws IOException {
            Path sources = project.resolve("src/main/java");
            Path gradle = project.resolve("build/jasx/editor/main.json");
            Path maven = project.resolve("target/jasx/editor/main.json");
            assertEquals(gradle, BuildInfo.fileFor(sources)); // neither exists yet: Gradle's

            Files.createDirectories(maven.getParent());
            Files.writeString(maven, "{}");
            assertEquals(maven, BuildInfo.fileFor(sources)); // only Maven's
        }
    }
}
