package jasx.lsp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import jasx.compiler.CompilerDiagnostic;
import jasx.compiler.ComponentIndex;
import jasx.compiler.Target;
import jasx.parser.Diagnostic;

/**
 * The JASX language server: the protocol side. It receives Language Server Protocol messages as JSON,
 * updates the {@link Workspace}, and sends back responses and diagnostics through {@code send}.
 *
 * <p>Supported:
 *
 * <ul>
 *   <li>lifecycle: {@code initialize}, {@code initialized}, {@code shutdown}, {@code exit}. On
 *       {@code initialized} the server asks the client to watch {@code .jasx} and {@code .java} files
 *       ({@code client/registerCapability}), if the client supports that, because a change on disk to
 *       one file can change what another means (a component declared elsewhere);
 *   <li>documents: {@code didOpen}, {@code didChange} (whole text), {@code didSave}, {@code didClose},
 *       and {@code workspace/didChangeWatchedFiles}. Each of them re-analyses the file's group and
 *       publishes its diagnostics ({@code textDocument/publishDiagnostics}). {@code didOpen} and
 *       {@code didSave} also start a javac check of the group, if the build describes it
 *       ({@link Workspace#javacCheck}), and so does {@code didChange} once typing pauses
 *       ({@code typingPauseMillis} without another change); its errors are published when it finishes;
 *   <li>{@code textDocument/definition} on component tags, and {@code textDocument/hover} on component
 *       tags, other markup and Java names ({@link Workspace#hover});
 *   <li>{@code textDocument/references}, {@code textDocument/prepareRename} and {@code textDocument/rename}
 *       of components, props and Java names ({@link JavaReferences}); a rename that would break code is
 *       refused with a message ({@code RequestFailed});
 *   <li>{@code textDocument/documentSymbol}: the outline of a {@code .jasx} file ({@link Outline}), nested if
 *       the client supports it, otherwise flat;
 *   <li>{@code textDocument/completion} of tag and attribute names in markup ({@link Completions}), and of
 *       Java members and names in Java code, also inside {@code {...}} ({@link JavaCompletions}).
 * </ul>
 *
 * <p>Messages are handled one at a time, in order. javac checks run on the {@code background} executor;
 * handling a message and taking a finished check's result both lock the server, so they never interleave.
 * The protocol class is separate from {@link Main}, which owns the streams, so that tests can drive it with
 * plain JSON strings.
 */
final class JasxLanguageServer {
    /** JSON-RPC error codes. */
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INTERNAL_ERROR = -32603;
    private static final int REQUEST_FAILED = -32803;

    private final Consumer<String> send;
    private final Executor background;
    private Workspace workspace;
    /** Whether the client accepts snippets ({@code $1}) in completions; if not, plain text is sent. */
    private boolean snippetSupport;
    /** Whether the client takes a nested outline (DocumentSymbol); if not, a flat one (SymbolInformation). */
    private boolean hierarchicalOutline;
    /** Whether the client lets the server register file watchers ({@code client/registerCapability}). */
    private boolean watcherRegistration;
    /** The id of the server's next request to the client. */
    private int nextRequestId = 1;
    private boolean shutdownRequested;
    private boolean exitRequested;
    /** How long typing must pause before a change starts a javac check. */
    private final long typingPauseMillis;
    /** Counts changes, so that a check waiting for a pause can see that typing went on. */
    private int changes;

    /**
     * @param send              writes one message (a JSON string) to the client
     * @param background        runs javac checks, one at a time; tests pass one that runs them at once
     * @param typingPauseMillis how long after a change, without another, its javac check starts
     */
    JasxLanguageServer(Consumer<String> send, Executor background, long typingPauseMillis) {
        this.send = send;
        this.background = background;
        this.typingPauseMillis = typingPauseMillis;
    }

    /** A server whose checks after a change start at once (for tests). */
    JasxLanguageServer(Consumer<String> send, Executor background) {
        this(send, background, 0);
    }

    /** True once the client sent {@code exit}: the server should stop. */
    boolean exitRequested() {
        return exitRequested;
    }

    /** The process exit code after {@code exit}: 0 if {@code shutdown} came first, as the protocol asks. */
    int exitCode() {
        return shutdownRequested ? 0 : 1;
    }

    /** Handles one message from the client. */
    synchronized void handle(String json) {
        Map<String, Object> message = Json.object(Json.parse(json));
        String method = Json.string(message.get("method"));
        Object id = message.get("id");
        Map<String, Object> params = Json.object(message.get("params"));
        boolean isRequest = id != null && method != null;

        try {
            Object result = dispatch(method, params, isRequest);
            if (isRequest) {
                respond(id, result);
            }
        } catch (Workspace.Refused e) {
            if (isRequest) {
                respondWithError(id, REQUEST_FAILED, e.getMessage());
            }
        } catch (UnsupportedOperationException e) {
            if (isRequest) {
                respondWithError(id, METHOD_NOT_FOUND, "method not supported: " + method);
            }
        } catch (RuntimeException e) {
            if (isRequest) {
                respondWithError(id, INTERNAL_ERROR, String.valueOf(e));
            } else {
                log("JASX: error while handling " + method + ": " + e);
            }
        }
    }

    /** Runs one method; returns the result of a request (ignored for notifications). */
    private Object dispatch(String method, Map<String, Object> params, boolean isRequest) {
        if (method == null) {
            return null; // the client's answer to a request of ours (registerCapability), which needs no handling
        }
        switch (method) {
            case "initialize":
                return initialize(params);
            case "initialized":
                registerFileWatchers();
                return null;
            case "shutdown":
                shutdownRequested = true;
                return null;
            case "exit":
                exitRequested = true;
                return null;
            case "textDocument/didOpen": {
                Map<String, Object> document = Json.object(params.get("textDocument"));
                String uri = Json.string(document.get("uri"));
                workspace().open(uri, Json.string(document.get("text")));
                publish(workspace().analyze(uri));
                workspace().writeGeneratedSources(uri);
                startJavacCheck(uri);
                return null;
            }
            case "textDocument/didChange": {
                String uri = documentUri(params);
                List<?> changes = (List<?>) params.get("contentChanges");
                // Full synchronization: the last change holds the whole new text.
                Map<String, Object> last = Json.object(changes.get(changes.size() - 1));
                workspace().change(uri, Json.string(last.get("text")));
                publish(workspace().analyze(uri));
                startJavacCheckWhenTypingPauses(uri);
                return null;
            }
            case "textDocument/didSave": {
                String uri = documentUri(params);
                publish(workspace().analyze(uri));
                workspace().writeGeneratedSources(uri);
                startJavacCheck(uri);
                return null;
            }
            case "textDocument/didClose": {
                String uri = documentUri(params);
                workspace().close(uri);
                publish(workspace().analyze(uri));
                return null;
            }
            case "workspace/didChangeWatchedFiles":
                for (Object change : (List<?>) params.get("changes")) {
                    String uri = Json.string(Json.object(change).get("uri"));
                    if (uri.endsWith(".json")) {
                        for (String document : workspace().documentsForBuildInfo(uri)) {
                            publish(workspace().analyze(document));
                            workspace().writeGeneratedSources(document);
                            startJavacCheck(document);
                        }
                    } else {
                        publish(workspace().analyze(uri));
                    }
                }
                return null;
            case "textDocument/definition":
                return definition(params);
            case "textDocument/hover":
                return hover(params);
            case "textDocument/completion":
                return completion(params);
            case "textDocument/documentSymbol":
                return documentSymbols(params);
            case "textDocument/references":
                return references(params);
            case "textDocument/prepareRename":
                return prepareRename(params);
            case "textDocument/rename":
                return rename(params);
            default:
                if (isRequest) {
                    throw new UnsupportedOperationException(method);
                }
                return null; // notifications we don't know are ignored ($/cancelRequest, ...)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    /**
     * Loads the target (the {@code target} initialization option, {@code html} by default) and answers
     * with the server's capabilities.
     */
    private Map<String, Object> initialize(Map<String, Object> params) {
        String targetId = Json.string(Json.object(params.get("initializationOptions")).get("target"));
        ClassLoader loader = JasxLanguageServer.class.getClassLoader();
        Target target = Target.load(targetId != null ? targetId : "html", loader);
        workspace = new Workspace(target, ComponentIndex.fromClasspath(loader));

        Map<String, Object> completionItem = Json.object(Json.object(Json.object(Json.object(
                params.get("capabilities")).get("textDocument")).get("completion")).get("completionItem"));
        snippetSupport = Boolean.TRUE.equals(completionItem.get("snippetSupport"));
        Map<String, Object> renameCapabilities = Json.object(Json.object(Json.object(
                params.get("capabilities")).get("textDocument")).get("rename"));
        boolean prepareSupport = Boolean.TRUE.equals(renameCapabilities.get("prepareSupport"));
        Map<String, Object> symbolCapabilities = Json.object(Json.object(Json.object(
                params.get("capabilities")).get("textDocument")).get("documentSymbol"));
        hierarchicalOutline = Boolean.TRUE.equals(symbolCapabilities.get("hierarchicalDocumentSymbolSupport"));
        Map<String, Object> watchedFiles = Json.object(Json.object(Json.object(
                params.get("capabilities")).get("workspace")).get("didChangeWatchedFiles"));
        watcherRegistration = Boolean.TRUE.equals(watchedFiles.get("dynamicRegistration"));

        Map<String, Object> sync = new LinkedHashMap<>();
        sync.put("openClose", true);
        sync.put("change", 1); // full text on every change
        sync.put("save", Map.of("includeText", false));

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("textDocumentSync", sync);
        capabilities.put("definitionProvider", true);
        capabilities.put("hoverProvider", true);
        capabilities.put("referencesProvider", true);
        capabilities.put("documentSymbolProvider", true);
        // With prepareRename, the editor asks first, and shows why a rename is refused before the user types.
        capabilities.put("renameProvider", prepareSupport ? Map.of("prepareProvider", true) : true);
        // '<' starts a tag, ' ' an attribute, '.' a qualified name, '@' a browser event, '$' a ref.
        capabilities.put("completionProvider", Map.of("triggerCharacters", List.of("<", " ", ".", "@", "$")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("capabilities", capabilities);
        result.put("serverInfo", Map.of("name", "jasx-lsp", "version", "0.2.0"));
        return result;
    }

    /**
     * Asks the client to report changes to source files and Gradle/Maven editor metadata on disk
     * ({@code workspace/didChangeWatchedFiles}). The client's answer needs no handling.
     */
    private void registerFileWatchers() {
        if (!watcherRegistration) {
            return;
        }
        List<Object> watchers = List.of(
                Map.of("globPattern", "**/*.jasx"), Map.of("globPattern", "**/*.java"),
                Map.of("globPattern", "**/build/jasx/editor/*.json"),
                Map.of("globPattern", "**/target/jasx/editor/*.json"));
        Map<String, Object> registration = new LinkedHashMap<>();
        registration.put("id", "jasx-watched-files");
        registration.put("method", "workspace/didChangeWatchedFiles");
        registration.put("registerOptions", Map.of("watchers", watchers));
        request("client/registerCapability", Map.of("registrations", List.of(registration)));
    }

    private Workspace workspace() {
        if (workspace == null) {
            throw new IllegalStateException("the client must send 'initialize' first");
        }
        return workspace;
    }

    // ---------------------------------------------------------------------------------------------
    // Requests about a position
    // ---------------------------------------------------------------------------------------------

    private Object definition(Map<String, Object> params) {
        Map<String, Object> position = Json.object(params.get("position"));
        Workspace.Location location = workspace().definition(documentUri(params),
                Json.integer(position.get("line")), Json.integer(position.get("character")));
        return location == null ? null : location(location);
    }

    private Object hover(Map<String, Object> params) {
        Map<String, Object> position = Json.object(params.get("position"));
        String markdown = workspace().hover(documentUri(params),
                Json.integer(position.get("line")), Json.integer(position.get("character")));
        if (markdown == null) {
            return null;
        }
        return Map.of("contents", Map.of("kind", "markdown", "value", markdown));
    }

    /** The file's outline: DocumentSymbols nested as declared, or flat SymbolInformation for older clients. */
    private Object documentSymbols(Map<String, Object> params) {
        String uri = documentUri(params);
        List<Workspace.OutlineSymbol> outline = workspace().outline(uri);
        List<Object> result = new ArrayList<>();
        if (hierarchicalOutline) {
            for (Workspace.OutlineSymbol symbol : outline) {
                result.add(documentSymbol(symbol));
            }
        } else {
            addFlat(uri, outline, null, result);
        }
        return result;
    }

    private static Map<String, Object> documentSymbol(Workspace.OutlineSymbol symbol) {
        List<Object> children = new ArrayList<>();
        for (Workspace.OutlineSymbol child : symbol.children()) {
            children.add(documentSymbol(child));
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", symbol.name());
        if (symbol.detail() != null) {
            json.put("detail", symbol.detail());
        }
        json.put("kind", symbol.kind());
        json.put("range", range(symbol.range()));
        json.put("selectionRange", range(symbol.selection()));
        json.put("children", children);
        return json;
    }

    private static void addFlat(String uri, List<Workspace.OutlineSymbol> symbols, String container, List<Object> into) {
        for (Workspace.OutlineSymbol symbol : symbols) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("name", symbol.name());
            json.put("kind", symbol.kind());
            json.put("location", Map.of("uri", uri, "range", range(symbol.range())));
            if (container != null) {
                json.put("containerName", container);
            }
            into.add(json);
            addFlat(uri, symbol.children(), symbol.name(), into);
        }
    }

    private static Map<String, Object> range(Workspace.Range range) {
        return range(range.startLine(), range.startColumn(), range.endLine(), range.endColumn());
    }

    /** A Location for every place the symbol at the position is written. */
    private Object references(Map<String, Object> params) {
        Map<String, Object> position = Json.object(params.get("position"));
        boolean includeDeclaration = Boolean.TRUE.equals(Json.object(params.get("context")).get("includeDeclaration"));
        List<Object> locations = new ArrayList<>();
        for (Workspace.Location location : workspace().references(documentUri(params),
                Json.integer(position.get("line")), Json.integer(position.get("character")), includeDeclaration)) {
            locations.add(location(location));
        }
        return locations;
    }

    /** The range of the name to rename and the name itself, or an error saying why it can't be renamed. */
    private Object prepareRename(Map<String, Object> params) {
        Map<String, Object> position = Json.object(params.get("position"));
        Workspace.RenameAt at = workspace().prepareRename(documentUri(params),
                Json.integer(position.get("line")), Json.integer(position.get("character")));
        Workspace.Location range = at.range();
        return Map.of("range", range(range.startLine(), range.startColumn(), range.endLine(), range.endColumn()),
                "placeholder", at.placeholder());
    }

    /** A WorkspaceEdit with the edits of every file, or an error saying why the rename can't be done. */
    private Object rename(Map<String, Object> params) {
        Map<String, Object> position = Json.object(params.get("position"));
        Map<String, List<Workspace.Edit>> edits = workspace().rename(documentUri(params),
                Json.integer(position.get("line")), Json.integer(position.get("character")),
                Json.string(params.get("newName")));
        Map<String, Object> changes = new LinkedHashMap<>();
        edits.forEach((uri, list) -> {
            List<Object> textEdits = new ArrayList<>();
            for (Workspace.Edit edit : list) {
                textEdits.add(Map.of("range", range(edit.startLine(), edit.startColumn(), edit.endLine(), edit.endColumn()),
                        "newText", edit.newText()));
            }
            changes.put(uri, textEdits);
        });
        return Map.of("changes", changes);
    }

    private static Map<String, Object> location(Workspace.Location location) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uri", location.uri());
        result.put("range", range(location.startLine(), location.startColumn(), location.endLine(), location.endColumn()));
        return result;
    }

    /** A CompletionList; every item replaces the name being typed (a textEdit from its start to the cursor). */
    private Object completion(Map<String, Object> params) {
        Map<String, Object> position = Json.object(params.get("position"));
        int line = Json.integer(position.get("line"));
        int character = Json.integer(position.get("character"));
        Workspace.CompletionAt at = workspace().completion(documentUri(params), line, character);

        Map<String, Object> replaced = range(at.fromLine(), at.fromColumn(), line, character);
        List<Object> items = new ArrayList<>();
        for (Completions.Item item : at.items()) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("label", item.label());
            json.put("kind", item.kind());
            json.put("detail", item.detail());
            if (item.documentation() != null) {
                json.put("documentation", Map.of("kind", "markdown", "value", item.documentation()));
            }
            json.put("sortText", item.sortText());
            // Filter on the label: the replaced text may include a qualifier (Ui.Ca) that a word doesn't.
            json.put("filterText", item.label());
            boolean asSnippet = item.snippet() && snippetSupport;
            String newText = item.snippet() && !snippetSupport ? item.newText().replace("$1", "") : item.newText();
            json.put("textEdit", Map.of("range", replaced, "newText", newText));
            json.put("insertTextFormat", asSnippet ? 2 : 1);
            if (item.importName() != null) {
                // A type that isn't imported yet brings its import.
                Map<String, Object> importRange = range(at.importLine(), 0, at.importLine(), 0);
                String importText = at.beforeImport() + "import " + item.importName() + ";\n";
                json.put("additionalTextEdits", List.of(Map.of("range", importRange, "newText", importText)));
            }
            items.add(json);
        }
        Map<String, Object> list = new LinkedHashMap<>();
        list.put("isIncomplete", at.incomplete());
        list.put("items", items);
        return list;
    }

    // ---------------------------------------------------------------------------------------------
    // javac checks
    // ---------------------------------------------------------------------------------------------

    /** Starts a javac check of the group of {@code uri} in the background, if it can run. */
    private void startJavacCheck(String uri) {
        Workspace.JavacCheck check = workspace().javacCheck(uri);
        if (check != null) {
            background.execute(() -> run(check));
        }
    }

    /**
     * Starts a javac check of the group of {@code uri} once typing pauses: if no other change came within
     * {@code typingPauseMillis}. Checking on every keystroke would only produce results that are stale before
     * they arrive. The check is prepared when the pause ends, from the text as it is then.
     */
    private void startJavacCheckWhenTypingPauses(String uri) {
        int change = ++changes;
        background.execute(() -> {
            pause(typingPauseMillis);
            Workspace.JavacCheck check;
            synchronized (this) {
                boolean typingWentOn = change != changes;
                if (!typingWentOn) {
                    workspace().writeGeneratedSources(uri);
                }
                check = typingWentOn ? null : workspace().javacCheck(uri);
            }
            if (check != null) {
                run(check);
            }
        });
    }

    private void run(Workspace.JavacCheck check) {
        try {
            List<CompilerDiagnostic> diagnostics = check.run();
            javacFinished(check, diagnostics);
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                log("JASX: the javac check failed: " + e);
            }
        }
    }

    private static void pause(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void javacFinished(Workspace.JavacCheck check, List<CompilerDiagnostic> diagnostics) {
        publish(workspace().javacFinished(check, diagnostics));
    }

    // ---------------------------------------------------------------------------------------------
    // Messages to the client
    // ---------------------------------------------------------------------------------------------

    /** Sends {@code textDocument/publishDiagnostics} for each file. */
    private void publish(Map<String, List<Workspace.FileDiagnostic>> byUri) {
        byUri.forEach((uri, diagnostics) -> {
            List<Object> list = new ArrayList<>();
            for (Workspace.FileDiagnostic d : diagnostics) {
                list.add(diagnostic(d));
            }
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("uri", uri);
            params.put("diagnostics", list);
            notify("textDocument/publishDiagnostics", params);
        });
    }

    /** One diagnostic in LSP form; the code is JASXnnnn, or "javac" (diagnostics.md §6). */
    private static Map<String, Object> diagnostic(Workspace.FileDiagnostic d) {
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("range", range(d.startLine(), d.startColumn(), d.endLine(), d.endColumn()));
        diagnostic.put("severity", d.diagnostic().severity() == Diagnostic.Severity.ERROR ? 1 : 2);
        diagnostic.put("code", d.diagnostic().code());
        diagnostic.put("source", "jasx");
        diagnostic.put("message", d.diagnostic().message());
        return diagnostic;
    }

    private static Map<String, Object> range(int startLine, int startColumn, int endLine, int endColumn) {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("start", Map.of("line", startLine, "character", startColumn));
        range.put("end", Map.of("line", endLine, "character", endColumn));
        return range;
    }

    private void log(String text) {
        notify("window/logMessage", Map.of("type", 1, "message", text));
    }

    private void notify(String method, Object params) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.put("params", params);
        send.accept(Json.write(message));
    }

    private void request(String method, Object params) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", "jasx-" + nextRequestId++);
        message.put("method", method);
        message.put("params", params);
        send.accept(Json.write(message));
    }

    private void respond(Object id, Object result) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("result", result);
        send.accept(Json.write(message));
    }

    private void respondWithError(Object id, int code, String text) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("error", Map.of("code", code, "message", text));
        send.accept(Json.write(message));
    }

    private static String documentUri(Map<String, Object> params) {
        return Json.string(Json.object(params.get("textDocument")).get("uri"));
    }
}
