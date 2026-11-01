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
import jasx.compiler.GeneratedFile;
import jasx.compiler.JasxCompiler;
import jasx.compiler.JavacRunner;
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
 * {@code .java} file of its source set, in both directories. Any other file is analysed on its own.
 *
 * <p><b>Text.</b> An open document is analysed with the text the editor has, which may not be saved yet;
 * every other file with its text on disk.
 *
 * <p><b>The build.</b> For a source directory of a project built with the JASX Gradle plugin, the plugin's
 * {@link BuildInfo} file gives the project's target, the components of its jars, and how to run javac. The
 * file is read again when a build rewrites it. Without it (a project not built yet, or a file outside a
 * source directory), the target bundled with the server is used, and no component of a jar is known.
 *
 * <p><b>Checks.</b> JASX's own checks (syntax, components, the target schema, style blocks) run on every
 * change. With build information, javac also type-checks the group ({@link #javacCheck}) when the server
 * asks for it (on open and save), in the background. Its errors are shown in {@code .jasx} files only,
 * because an editor's Java support already reports {@code .java} files, and only while the file's text is
 * still the one that was checked: after an edit they would point at the wrong place.
 */
final class Workspace {

    /** A group of files analysed together, and the result of the last analysis. */
    record Analysis(
            /** The group's key: its source set ({@code src/main}), or the file analysed on its own. */
            Path base,
            CompilationResult result,
            /** Each analysed file by its path relative to its source directory, as the compiler knows it. */
            Map<String, Path> files,
            /** The text each file was analysed with, by relative path. */
            Map<String, String> texts,
            /** The target and dependencies used: the build's, or the server's defaults. */
            Target target,
            ComponentIndex dependencies,
            /** What the build says about this group, or null. */
            BuildInfo build) {

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

    /** The javac errors in {@code .jasx} files of a group, and the text of each file when it was checked. */
    private record JavacResult(Map<String, String> texts, List<CompilerDiagnostic> diagnostics) {}

    /** Used when a group has no build information. */
    private final Target defaultTarget;
    private final ComponentIndex defaultDependencies;

    /** The build information read so far, by the path of its file. */
    private final Map<Path, BuildInfo> buildInfos = new HashMap<>();
    /** The latest javac result of each group, by base directory. */
    private final Map<Path, JavacResult> javacResults = new HashMap<>();
    /** How many javac checks were started for each group: only the latest one's result is kept. */
    private final Map<Path, Integer> javacGenerations = new HashMap<>();
    /** Where annotation processors write during javac checks; created when first needed. */
    private Path javacWorkRoot;

    /** The text of each open document, as the editor has it. */
    private final Map<Path, String> openDocuments = new HashMap<>();
    /** The URI the client used for each file, so that results go back under the URI it knows. */
    private final Map<Path, String> clientUris = new HashMap<>();
    /** The last analysis of each group, by the group's base directory. */
    private final Map<Path, Analysis> analyses = new HashMap<>();
    /** The files of each group that were last given diagnostics, so that they can be cleared. */
    private final Map<Path, Set<Path>> filesWithDiagnostics = new HashMap<>();

    /**
     * @param defaultTarget       the target for groups without build information
     * @param defaultDependencies the components of dependencies for groups without build information
     */
    Workspace(Target defaultTarget, ComponentIndex defaultDependencies) {
        this.defaultTarget = defaultTarget;
        this.defaultDependencies = defaultDependencies;
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
        Path sourceDirectory = sourceDirectory(file);
        // The group's key: the source set (src/main) for a file in a source directory, so that src/main/java
        // and src/main/jasx form one group as in the build; otherwise the file itself, so that two files
        // analysed on their own in one directory don't replace each other's analysis.
        Path base = sourceDirectory != null ? sourceDirectory.getParent() : file;
        List<Path> paths = sourceDirectory != null ? sourceSetFiles(base) : new ArrayList<>(List.of(file));
        if (!paths.contains(file) && isSource(file)) {
            paths.add(file); // a new file that isn't on disk yet
        }

        // Read every file of the group, preferring the editor's text. Each file is named relative to its own
        // source directory, as the build names it.
        Map<String, Path> files = new LinkedHashMap<>();
        Map<String, String> texts = new LinkedHashMap<>();
        List<SourceFile> sources = new ArrayList<>();
        for (Path path : paths) {
            String relative = relativeName(path);
            String text = textOf(path);
            if (text == null || files.containsKey(relative)) {
                continue; // deleted meanwhile, or the same name in both source directories (javac reports it)
            }
            files.put(relative, path);
            texts.put(relative, text);
            sources.add(new SourceFile(relative, text));
        }

        BuildInfo build = sourceDirectory != null ? buildInfoFor(sourceDirectory) : null;
        Target target = build != null ? build.target() : defaultTarget;
        ComponentIndex dependencies = build != null ? build.dependencies() : defaultDependencies;
        CompilationResult result = new JasxCompiler(target, dependencies).compile(sources);
        Analysis analysis = new Analysis(base, result, files, texts, target, dependencies, build);
        analyses.put(base, analysis);
        return diagnosticsToPublish(analysis, file);
    }

    /**
     * The build information for a source directory, read again if a build rewrote it, or null if the
     * project has none (or it can't be read: the server then works as without it).
     */
    private BuildInfo buildInfoFor(Path sourceDirectory) {
        Path file = BuildInfo.fileFor(sourceDirectory);
        BuildInfo known = buildInfos.get(file);
        if (known != null && !known.isStale()) {
            return known;
        }
        if (known != null) {
            buildInfos.remove(file);
            known.close();
        }
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            BuildInfo read = BuildInfo.read(file);
            buildInfos.put(file, read);
            return read;
        } catch (RuntimeException e) {
            return null; // a broken or half-written file: the next build rewrites it
        }
    }

    /**
     * Groups the analysis's diagnostics by file: JASX's, and javac's from the last check of the files that
     * haven't changed since. The file that was analysed (if not null) always gets a list, even an empty one,
     * and so does every file that had diagnostics before: an empty list clears them.
     */
    private Map<String, List<FileDiagnostic>> diagnosticsToPublish(Analysis analysis, Path analysed) {
        Map<Path, List<FileDiagnostic>> byFile = new LinkedHashMap<>();
        for (CompilerDiagnostic d : analysis.result().diagnostics()) {
            add(byFile, analysis, d);
        }
        JavacResult javac = javacResults.get(analysis.base());
        if (javac != null) {
            for (CompilerDiagnostic d : javac.diagnostics()) {
                String checkedText = javac.texts().get(d.path());
                boolean unchangedSinceChecked = checkedText != null && checkedText.equals(analysis.texts().get(d.path()));
                if (unchangedSinceChecked) {
                    add(byFile, analysis, d);
                }
            }
        }

        Set<Path> withDiagnostics = new HashSet<>(byFile.keySet());
        Set<Path> previously = filesWithDiagnostics.getOrDefault(analysis.base(), Set.of());
        for (Path file : previously) {
            byFile.putIfAbsent(file, List.of()); // clean now: clear its old diagnostics
        }
        if (analysed != null) {
            byFile.putIfAbsent(analysed, List.of());
        }
        filesWithDiagnostics.put(analysis.base(), withDiagnostics);

        Map<String, List<FileDiagnostic>> byUri = new LinkedHashMap<>();
        byFile.forEach((file, list) -> byUri.put(uriOf(file), list));
        return byUri;
    }

    /** Adds {@code d} to the list of its file, in LSP positions; diagnostics outside the group are dropped. */
    private static void add(Map<Path, List<FileDiagnostic>> byFile, Analysis analysis, CompilerDiagnostic d) {
        Path file = analysis.files().get(d.path());
        if (file == null) {
            return;
        }
        LineMap.Position start = d.lines().position(d.diagnostic().start());
        LineMap.Position end = d.lines().position(d.diagnostic().end());
        byFile.computeIfAbsent(file, f -> new ArrayList<>()).add(new FileDiagnostic(
                d.diagnostic(), start.line() - 1, start.column(), end.line() - 1, end.column()));
    }

    // ---------------------------------------------------------------------------------------------
    // javac
    // ---------------------------------------------------------------------------------------------

    /**
     * A javac check of a group, prepared from its last analysis. {@link #run} uses no workspace state, so the
     * server runs it in the background, and hands its result to {@link #javacFinished}.
     */
    record JavacCheck(Path base, int generation, List<GeneratedFile> generated, List<SourceFile> java,
            Map<String, String> texts, JavacRunner.BuildOptions options) {

        List<CompilerDiagnostic> run() throws IOException {
            return JavacRunner.typeCheck(generated, java, options);
        }
    }

    /**
     * The javac check for the group of the file at {@code uri}, or null if it can't run: the group has no
     * build information, or it has JASX errors (then some files have no generated code, and javac's errors
     * would only repeat them).
     */
    JavacCheck javacCheck(String uri) {
        Analysis analysis = analysisOf(toPath(uri));
        if (analysis == null || analysis.build() == null || analysis.result().hasErrors()) {
            return null;
        }
        List<SourceFile> java = new ArrayList<>();
        analysis.texts().forEach((relative, text) -> {
            if (relative.endsWith(".java")) {
                java.add(new SourceFile(relative, text));
            }
        });
        int generation = javacGenerations.merge(analysis.base(), 1, Integer::sum);
        JavacRunner.BuildOptions options = analysis.build().javacOptions(javacWorkDirectory(analysis.base()));
        return new JavacCheck(analysis.base(), generation, analysis.result().files(), java,
                Map.copyOf(analysis.texts()), options);
    }

    /**
     * Keeps the result of a javac check, unless a newer check of the group was started meanwhile, and returns
     * the diagnostics to publish (none if the result was dropped).
     */
    Map<String, List<FileDiagnostic>> javacFinished(JavacCheck check, List<CompilerDiagnostic> diagnostics) {
        boolean isLatest = javacGenerations.getOrDefault(check.base(), 0) == check.generation();
        Analysis analysis = analyses.get(check.base());
        if (!isLatest || analysis == null) {
            return Map.of();
        }
        // .java files are left to the editor's Java support (see the class comment).
        List<CompilerDiagnostic> inJasxFiles = diagnostics.stream().filter(d -> d.path().endsWith(".jasx")).toList();
        javacResults.put(check.base(), new JavacResult(check.texts(), inJasxFiles));
        return diagnosticsToPublish(analysis, null);
    }

    /** A directory of its own for each group's javac checks, under one temporary directory. */
    private Path javacWorkDirectory(Path base) {
        try {
            if (javacWorkRoot == null) {
                javacWorkRoot = Files.createTempDirectory("jasx-lsp");
            }
            return javacWorkRoot.resolve(Integer.toHexString(base.hashCode()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The last analysis of a group that contains {@code file}, or null. */
    private Analysis analysisOf(Path file) {
        for (Analysis analysis : analyses.values()) {
            if (analysis.relativePath(file) != null) {
                return analysis;
            }
        }
        return null;
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

    /** Completion suggestions, and the LSP position where the text they replace starts. */
    record CompletionAt(List<Completions.Item> items, int fromLine, int fromColumn) {}

    /**
     * The completion suggestions at a position of an analysed file: tag and attribute names in markup
     * ({@link Completions}). Empty if the file wasn't analysed or the position isn't in a tag.
     */
    CompletionAt completion(String uri, int line, int column) {
        Path file = toPath(uri);
        for (Analysis analysis : analyses.values()) {
            String relative = analysis.relativePath(file);
            if (relative == null) {
                continue;
            }
            String text = analysis.texts().get(relative);
            int offset = offsetOf(text, line, column);
            // The group's own components, and those of dependencies they don't hide.
            ComponentIndex index = analysis.result().index().withFallback(analysis.dependencies());
            Completions.Result result =
                    Completions.at(new SourceFile(relative, text), offset, index, analysis.target().schema());
            LineMap.Position from = new LineMap(text).position(result.replaceFrom());
            return new CompletionAt(result.items(), from.line() - 1, from.column());
        }
        return new CompletionAt(List.of(), line, column);
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

    /**
     * Every source file of a source set ({@code src/main}): those of its {@code java} and {@code jasx}
     * directories, which the build plugins compile together.
     */
    private static List<Path> sourceSetFiles(Path sourceSet) {
        List<Path> paths = new ArrayList<>();
        for (String root : List.of("java", "jasx")) {
            Path directory = sourceSet.resolve(root);
            if (Files.isDirectory(directory)) {
                paths.addAll(sourceFiles(directory));
            }
        }
        return paths;
    }

    /** A file's name as the compiler knows it: relative to its source directory, or its bare name. */
    private static String relativeName(Path file) {
        Path sourceDirectory = sourceDirectory(file);
        Path relative = sourceDirectory != null ? sourceDirectory.relativize(file) : file.getFileName();
        return relative.toString().replace('\\', '/');
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
