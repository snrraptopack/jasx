package jasx.gradle;

import jasx.compiler.CompilationResult;
import jasx.compiler.CompilerDiagnostic;
import jasx.compiler.ComponentIndex;
import jasx.compiler.JasxCompiler;
import jasx.compiler.SourceFile;
import jasx.compiler.Target;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.gradle.api.GradleException;

/**
 * The sources of one source set, read from disk, and the JASX compilation of them, for {@link JasxCompile}.
 *
 * <p>{@code .jasx} files are named by their path relative to their source root ({@code app/Page.jasx}),
 * which becomes the generated file's path ({@code app/Page.java}). {@code .java} files are named by their
 * absolute path. {@link #absolutePath} gives the file on disk for either, for diagnostics.
 */
final class JasxSources {
    private final List<SourceFile> jasxFiles = new ArrayList<>();
    private final List<SourceFile> javaFiles = new ArrayList<>();
    private final Map<String, Path> absolutePaths = new HashMap<>();

    /** Reads the {@code .jasx} files under {@code sourceRoots} and the given {@code .java} files. */
    static JasxSources read(Iterable<File> sourceRoots, Iterable<File> javaFiles) throws IOException {
        JasxSources sources = new JasxSources();
        for (File root : sourceRoots) {
            sources.readJasxFiles(root.toPath());
        }
        for (File javaFile : javaFiles) {
            String path = javaFile.getAbsolutePath();
            sources.javaFiles.add(new SourceFile(path, Files.readString(javaFile.toPath(), StandardCharsets.UTF_8)));
            sources.absolutePaths.put(path, javaFile.toPath());
        }
        return sources;
    }

    private void readJasxFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".jasx")).sorted().toList()) {
                String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
                jasxFiles.add(new SourceFile(relative, Files.readString(file, StandardCharsets.UTF_8)));
                absolutePaths.put(relative, file);
            }
        }
    }

    List<SourceFile> javaFiles() {
        return javaFiles;
    }

    /** Compiles the {@code .jasx} files with the target {@code targetId} found on {@code classpath}. */
    CompilationResult compile(Iterable<File> classpath, String targetId) throws IOException {
        List<SourceFile> all = new ArrayList<>(jasxFiles);
        all.addAll(javaFiles);
        try (URLClassLoader resources = resourcesOnly(classpath)) {
            Target target = loadTarget(targetId, resources);
            ComponentIndex dependencies = ComponentIndex.fromClasspath(resources);
            return new JasxCompiler(target, dependencies).compile(all);
        }
    }

    /** The console form of {@code d}, with the source file's absolute path. */
    String format(CompilerDiagnostic d) {
        Path file = absolutePaths.get(d.path());
        String path = file != null ? file.toString() : d.path();
        return new CompilerDiagnostic(path, d.diagnostic(), d.lines()).format();
    }

    /**
     * A class loader over the classpath with no parent. It is only used to find resources: the target's
     * descriptor and schema, and the component indexes of dependencies. No class is loaded from it.
     */
    private static URLClassLoader resourcesOnly(Iterable<File> classpath) {
        List<URL> urls = new ArrayList<>();
        for (File entry : classpath) {
            try {
                urls.add(entry.toURI().toURL());
            } catch (MalformedURLException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), null);
    }

    private static Target loadTarget(String id, ClassLoader resources) {
        try {
            return Target.load(id, resources);
        } catch (IllegalArgumentException e) {
            throw new GradleException(e.getMessage() + ". Add the target to your dependencies, for example"
                    + " implementation(\"dev.jasx:jasx-target-" + id + ":VERSION\").", e);
        }
    }
}
