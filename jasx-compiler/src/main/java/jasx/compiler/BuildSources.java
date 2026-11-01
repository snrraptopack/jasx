package jasx.compiler;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The sources of one source set, read from disk, and their JASX compilation: the part of a build plugin
 * that doesn't depend on the build tool. The Gradle plugin and the Maven plugin both use it.
 *
 * <p>{@code .jasx} files are named by their path relative to their source root ({@code app/Page.jasx}),
 * which becomes the generated file's path ({@code app/Page.java}). {@code .java} files are named by their
 * absolute path. {@link #format} prints a diagnostic with the file's absolute path, as build tools do.
 */
public final class BuildSources {
    private final List<SourceFile> jasxFiles = new ArrayList<>();
    private final List<SourceFile> javaFiles = new ArrayList<>();
    private final Map<String, Path> absolutePaths = new HashMap<>();

    private BuildSources() {}

    /**
     * Reads the {@code .jasx} files under {@code sourceRoots}, and the {@code .java} files under them too
     * ({@code @jasx.Component} methods in plain Java can be used from markup).
     */
    public static BuildSources read(Iterable<Path> sourceRoots) throws IOException {
        BuildSources sources = new BuildSources();
        for (Path root : sourceRoots) {
            if (Files.isDirectory(root)) {
                sources.readRoot(root);
            }
        }
        return sources;
    }

    private void readRoot(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".jasx")) {
                    String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
                    jasxFiles.add(new SourceFile(relative, Files.readString(file, StandardCharsets.UTF_8)));
                    absolutePaths.put(relative, file);
                } else if (name.endsWith(".java")) {
                    String absolute = file.toAbsolutePath().toString();
                    javaFiles.add(new SourceFile(absolute, Files.readString(file, StandardCharsets.UTF_8)));
                    absolutePaths.put(absolute, file);
                }
            }
        }
    }

    public boolean hasJasxFiles() {
        return !jasxFiles.isEmpty();
    }

    public List<SourceFile> javaFiles() {
        return javaFiles;
    }

    /**
     * Compiles the {@code .jasx} files with the target {@code targetId} found on {@code classpath}, and the
     * components of the dependencies found there ({@code META-INF/jasx/components.json}). Only resources are
     * read from the classpath, and the target's own lowering class if it has one (semantics.md §7.4); no other
     * class of the project or its dependencies is loaded.
     *
     * @throws IllegalArgumentException if the target isn't on the classpath, or its lowering can't be loaded
     */
    public CompilationResult compile(Iterable<Path> classpath, String targetId) throws IOException {
        List<SourceFile> all = new ArrayList<>(jasxFiles);
        all.addAll(javaFiles);
        try (TargetClassLoader loader = new TargetClassLoader(classpath)) {
            Target target = Target.load(targetId, loader);
            ComponentIndex dependencies = ComponentIndex.fromClasspath(loader);
            return new JasxCompiler(target, dependencies).compile(all);
        }
    }

    /**
     * Type-checks the generated files with javac, as the build will compile them, and returns the errors to
     * report before the build's own compilation runs: every javac error if at least one is in a {@code .jasx}
     * file (the build should then stop, so that each error is reported once, at its {@code .jasx} location),
     * otherwise none (errors only in {@code .java} files are left to the build, which reports them itself).
     *
     * @throws IllegalArgumentException if javac rejects the options
     */
    public List<CompilerDiagnostic> javacErrorsToReport(List<GeneratedFile> generated, JavacRunner.BuildOptions options)
            throws IOException {
        List<CompilerDiagnostic> errors = JavacRunner.typeCheck(generated, javaFiles, options).stream()
                .filter(CompilerDiagnostic::isError)
                .toList();
        boolean anyInJasx = errors.stream().anyMatch(d -> d.path().endsWith(".jasx"));
        return anyInJasx ? errors : List.of();
    }

    /** Writes the generated files under {@code directory}, replacing whatever was there. */
    public static void writeGeneratedFiles(List<GeneratedFile> files, Path directory) throws IOException {
        deleteContents(directory);
        for (GeneratedFile file : files) {
            Path target = directory.resolve(file.javaPath());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content(), StandardCharsets.UTF_8);
        }
    }

    /** The console form of {@code d}, with the source file's absolute path. */
    public String format(CompilerDiagnostic d) {
        Path file = absolutePaths.get(d.path());
        String path = file != null ? file.toString() : d.path();
        return new CompilerDiagnostic(path, d.diagnostic(), d.lines()).format();
    }

    /** Deletes everything inside {@code directory} (creating it if needed), so no stale file is left behind. */
    private static void deleteContents(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            Files.createDirectories(directory);
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            // Deepest first, so that directories are empty when they are deleted.
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (!p.equals(directory)) {
                    Files.delete(p);
                }
            }
        }
    }
}
