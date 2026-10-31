package jasx.lsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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
 *   <li>lifecycle: {@code initialize}, {@code initialized}, {@code shutdown}, {@code exit};
 *   <li>documents: {@code didOpen}, {@code didChange} (whole text), {@code didSave}, {@code didClose},
 *       and {@code workspace/didChangeWatchedFiles}. Each of them re-analyses the file's group and
 *       publishes its diagnostics ({@code textDocument/publishDiagnostics});
 *   <li>{@code textDocument/definition} and {@code textDocument/hover} on component tags.
 * </ul>
 *
 * <p>Messages are handled one at a time, in order. The protocol class is separate from {@link Main}, which
 * owns the streams, so that tests can drive it with plain JSON strings.
 */
final class JasxLanguageServer {
    /** JSON-RPC error codes. */
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INTERNAL_ERROR = -32603;

    private final Consumer<String> send;
    private Workspace workspace;
    private boolean shutdownRequested;
    private boolean exitRequested;

    /** @param send writes one message (a JSON string) to the client */
    JasxLanguageServer(Consumer<String> send) {
        this.send = send;
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
    void handle(String json) {
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
            return null; // a response to a request of ours; the server sends none
        }
        switch (method) {
            case "initialize":
                return initialize(params);
            case "initialized":
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
                return null;
            }
            case "textDocument/didChange": {
                String uri = documentUri(params);
                List<?> changes = (List<?>) params.get("contentChanges");
                // Full synchronization: the last change holds the whole new text.
                Map<String, Object> last = Json.object(changes.get(changes.size() - 1));
                workspace().change(uri, Json.string(last.get("text")));
                publish(workspace().analyze(uri));
                return null;
            }
            case "textDocument/didSave":
                publish(workspace().analyze(documentUri(params)));
                return null;
            case "textDocument/didClose": {
                String uri = documentUri(params);
                workspace().close(uri);
                publish(workspace().analyze(uri));
                return null;
            }
            case "workspace/didChangeWatchedFiles":
                for (Object change : (List<?>) params.get("changes")) {
                    String uri = Json.string(Json.object(change).get("uri"));
                    publish(workspace().analyze(uri));
                }
                return null;
            case "textDocument/definition":
                return definition(params);
            case "textDocument/hover":
                return hover(params);
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

        Map<String, Object> sync = new LinkedHashMap<>();
        sync.put("openClose", true);
        sync.put("change", 1); // full text on every change
        sync.put("save", Map.of("includeText", false));

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("textDocumentSync", sync);
        capabilities.put("definitionProvider", true);
        capabilities.put("hoverProvider", true);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("capabilities", capabilities);
        result.put("serverInfo", Map.of("name", "jasx-lsp", "version", "0.1.0"));
        return result;
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
        if (location == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uri", location.uri());
        result.put("range", range(location.startLine(), location.startColumn(), location.endLine(), location.endColumn()));
        return result;
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
