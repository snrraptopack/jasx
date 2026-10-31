package jasx.lsp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import jasx.compiler.CompilationResult;
import jasx.compiler.CompilerDiagnostic;
import jasx.compiler.ComponentIndex;
import jasx.compiler.JasxCompiler;
import jasx.compiler.SourceFile;
import jasx.compiler.Target;
import jasx.parser.Diagnostic;
import jasx.parser.LineMap;

/**
 * The files the language server knows about, and the JASX analysis of them.
 *
 * <p><b>Groups.</b> Markup in one file can use components of other files, so files are analysed together,
 * in the groups a build would compile together. A file in a Gradle-style source directory
 * ({@code .../src/<set>/java} or {@code .../src/<set>/jasx}) is analysed with every {@code .jasx} and
 * {@code .java} file of that directory. Any other file is analysed on its own.
 *
 * <p><b>Text.</b> An open document is analysed with the text the editor has, which may not be saved yet;
 * every other file with its text on disk.
 *
 * <p><b>Limits.</b> Only JASX's own checks run: syntax, components, the target schema and style blocks.
 * Java type errors need the project's classpath and are left to the build. Components of the project's
 * dependencies (jars) are not known, so using one reports it as unknown.
 */
final class Workspace {

    /** A group of files analysed together, and the result of the last analysis. */
    record Analysis(
            /** The source directory, or the directory of a file analysed on its own. */
            Path base,
            CompilationResult result,
            /** Each analysed file by its path relative to {@code base}, as the compiler knows it. */
            Map<String, Path> files,
            /** The text each file was analysed with, by relative path. */
            Map<String, String> texts) {

        /** The relative path of {@code file} in this group, or null if it isn't in it. */
        String relativePath(Path file) {
            for (Map.Entry<String, Path> entry : files.entrySet()) {
                if (entry.getValue().equals(file)) {
                    return entry.getKey();
                }
            }
            return null;
        }
    }

    /** One diagnostic, located in a file by LSP positions (0-based line and UTF-16 column). */
    record FileDiagnostic(Diagnostic diagnostic, int startLine, int startColumn, int endLine, int endColumn) {}

    private final Target target;
    private final ComponentIndex dependencies;

    /** The text of each open document, as the editor has it. */
    private final Map<Path, String> openDocuments = new HashMap<>();
    /** The URI the client used for each file, so that results go back under the URI it knows. */
    private final Map<Path, String> clientUris = new HashMap<>();
    /** The last analysis of each group, by the group's base directory. */
    private final Map<Path, Analysis> analyses = new HashMap<>();
    /** The files of each group that were last given diagnostics, so that they can be cleared. */
    private final Map<Path, Set<Path>> filesWithDiagnostics = new HashMap<>();

    Workspace(Target target, ComponentIndex dependencies) {
        this.target = target;
        this.dependencies = dependencies;
    }

    // ---------------------------------------------------------------------------------------------
    // Documents
    // ---------------------------------------------------------------------------------------------

    void open(String uri, String text) {
        Path file = toPath(uri);
        clientUris.put(file, uri);
        openDocuments.put(file, text);
    }

    void change(String uri, String text) {
        open(uri, text);
    }

    /** The editor closed the document: from now on, its text on disk counts. */
    void close(String uri) {
        openDocuments.remove(toPath(uri));
    }

    // ---------------------------------------------------------------------------------------------
    // Analysis
    // ---------------------------------------------------------------------------------------------

    /**
     * Analyses the group that contains the file at {@code uri}. Returns the diagnostics to publish, by
     * URI: every file of the group that has diagnostics, and an empty list for each file that had some
     * before but has none now.
     */
    Map<String, List<FileDiagnostic>> analyze(String uri) {
        Path file = toPath(uri);
        Path base = sourceDirectory(file);
        List<Path> paths = base != null ? sourceFiles(base) : List.of(file);
        if (base == null) {
            base = file.getParent();
        }
        if (!paths.contains(file) && isSource(file)) {
            paths = new ArrayList<>(paths);
            paths.add(file); // a new file that isn't on disk yet
        }

        // Read every file of the group, preferring the editor's text.
        Map<String, Path> files = new LinkedHashMap<>();
        Map<String, String> texts = new LinkedHashMap<>();
        List<SourceFile> sources = new ArrayList<>();
        for (Path path : paths) {
            String relative = base.relativize(path).toString().replace('\\', '/');
            String text = textOf(path);
            if (text == null) {
                continue; // deleted meanwhile
            }
            files.put(relative, path);
            texts.put(relative, text);
            sources.add(new SourceFile(relative, text));
        }

        CompilationResult result = new JasxCompiler(target, dependencies).compile(sources);
        Analysis analysis = new Analysis(base, result, files, texts);
        analyses.put(base, analysis);
        return diagnosticsToPublish(analysis, file);
    }

    /**
     * Groups the analysis's diagnostics by file. The file that was analysed always gets a list, even an
     * empty one, and so does every file that had diagnostics before: an empty list clears them.
     */
    private Map<String, List<FileDiagnostic>> diagnosticsToPublish(Analysis analysis, Path analysed) {
        Map<Path, List<FileDiagnostic>> byFile = new LinkedHashMap<>();
        for (CompilerDiagnostic d : analysis.result().diagnostics()) {
            Path file = analysis.files().get(d.path());
            if (file == null) {
                continue;
            }
            LineMap.Position start = d.lines().position(d.diagnostic().start());
            LineMap.Position end = d.lines().position(d.diagnostic().end());
            byFile.computeIfAbsent(file, f -> new ArrayList<>()).add(new FileDiagnostic(
                    d.diagnostic(), start.line() - 1, start.column(), end.line() - 1, end.column()));
        }

        Set<Path> withDiagnostics = new HashSet<>(byFile.keySet());
        Set<Path> previously = filesWithDiagnostics.getOrDefault(analysis.base(), Set.of());
        for (Path file : previously) {
            byFile.putIfAbsent(file, List.of()); // clean now: clear its old diagnostics
        }
        byFile.putIfAbsent(analysed, List.of());
        filesWithDiagnostics.put(analysis.base(), withDiagnostics);

        Map<String, List<FileDiagnostic>> byUri = new LinkedHashMap<>();
        byFile.forEach((file, list) -> byUri.put(uriOf(file), list));
        return byUri;
    }

    // ---------------------------------------------------------------------------------------------
    // Questions about a position
    // ---------------------------------------------------------------------------------------------

    /** A place in a file, in LSP positions. */
    record Location(String uri, int startLine, int startColumn, int endLine, int endColumn) {}

    /** The component tag at a position, and the analysis it came from. */
    private record UseAt(Analysis analysis, CompilationResult.Use use) {}

    /** Where the component whose tag is at the position is declared, or null. */
    Location definition(String uri, int line, int column) {
        UseAt at = useAt(uri, line, column);
        if (at == null) {
            return null;
        }
        ComponentIndex.Component component = at.use().component();
        for (CompilationResult.Declaration declaration : at.analysis().result().declarations()) {
            boolean same = declaration.component().className().equals(component.className())
                    && declaration.component().method().equals(component.method());
            if (same) {
                return location(at.analysis(), declaration.path(), declaration.nameStart(), declaration.nameEnd());
            }
        }
        return null; // a component of a dependency: no source to go to
    }

    /** A description of the component whose tag is at the position, in Markdown, or null. */
    String hover(String uri, int line, int column) {
        UseAt at = useAt(uri, line, column);
        return at == null ? null : Hovers.component(at.use().component());
    }

    private UseAt useAt(String uri, int line, int column) {
        Path file = toPath(uri);
        for (Analysis analysis : analyses.values()) {
            String relative = analysis.relativePath(file);
            if (relative == null) {
                continue;
            }
            int offset = offsetOf(analysis.texts().get(relative), line, column);
            for (CompilationResult.Use use : analysis.result().uses()) {
                // The cursor may be on the name or right after it.
                boolean onName = use.path().equals(relative) && use.nameStart() <= offset && offset <= use.nameEnd();
                if (onName) {
                    return new UseAt(analysis, use);
                }
            }
        }
        return null;
    }

    private Location location(Analysis analysis, String relative, int start, int end) {
        LineMap lines = new LineMap(analysis.texts().get(relative));
        LineMap.Position from = lines.position(start);
        LineMap.Position to = lines.position(end);
        return new Location(uriOf(analysis.files().get(relative)),
                from.line() - 1, from.column(), to.line() - 1, to.column());
    }

    // ---------------------------------------------------------------------------------------------
    // Files
    // ---------------------------------------------------------------------------------------------

    /**
     * The Gradle-style source directory that contains {@code file}: an ancestor named {@code java} or
     * {@code jasx} whose grandparent is named {@code src} ({@code src/main/java}). Null if there is none.
     */
    static Path sourceDirectory(Path file) {
        for (Path dir = file.getParent(); dir != null; dir = dir.getParent()) {
            Path name = dir.getFileName();
            Path set = dir.getParent();
            Path src = set != null ? set.getParent() : null;
            boolean isSourceDirectory = name != null && src != null && src.getFileName() != null
                    && (name.toString().equals("java") || name.toString().equals("jasx"))
                    && src.getFileName().toString().equals("src");
            if (isSourceDirectory) {
                return dir;
            }
        }
        return null;
    }

    /** Every {@code .jasx} and {@code .java} file under {@code directory}. */
    private static List<Path> sourceFiles(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile).filter(Workspace::isSource).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isSource(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".jasx") || name.endsWith(".java");
    }

    /** The editor's text of an open document, otherwise the text on disk, or null if it doesn't exist. */
    private String textOf(Path file) {
        String open = openDocuments.get(file);
        if (open != null) {
            return open;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The path of a {@code file:} URI. VS Code writes Windows drive letters encoded ({@code file:///c%3A/x}),
     * which {@link URI#getPath()} decodes to {@code /c:/x}; the leading slash is removed there.
     */
    static Path toPath(String uri) {
        String path = URI.create(uri).getPath();
        boolean windowsDrive = path.length() >= 3 && path.charAt(0) == '/' && path.charAt(2) == ':';
        return Path.of(windowsDrive ? path.substring(1) : path).toAbsolutePath().normalize();
    }

    /** The URI to report a file under: the client's own, if it told us one. */
    private String uriOf(Path file) {
        String known = clientUris.get(file);
        return known != null ? known : file.toUri().toString();
    }

    /** The UTF-16 offset of an LSP position (0-based line and column) in {@code text}. */
    static int offsetOf(String text, int line, int column) {
        int offset = 0;
        for (int l = 0; l < line && offset < text.length(); l++) {
            int newline = text.indexOf('\n', offset);
            if (newline < 0) {
                return text.length();
            }
            offset = newline + 1;
        }
        return Math.min(offset + column, text.length());
    }
}
