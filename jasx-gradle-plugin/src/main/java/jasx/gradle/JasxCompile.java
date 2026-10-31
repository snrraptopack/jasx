package jasx.gradle;

import jasx.compiler.CompilationResult;
import jasx.compiler.CompilerDiagnostic;
import jasx.compiler.ComponentIndex;
import jasx.compiler.GeneratedFile;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;

/**
 * Compiles {@code .jasx} files to Java with the JASX compiler.
 *
 * <p>The target (its descriptor and schema) and the components of dependencies
 * ({@code META-INF/jasx/components.json}) are read from the compile classpath. Only resources are read
 * from it; no class of the project or its dependencies is loaded.
 *
 * <p>Errors are printed as {@code /absolute/path/File.jasx:line:column: error: message [JASX....]}, and fail
 * the task.
 */
@CacheableTask
public abstract class JasxCompile extends DefaultTask {

    /** The directories to search for {@code .jasx} files. Their paths give the generated files' paths. */
    @Internal
    public abstract ConfigurableFileCollection getSourceRoots();

    /** The {@code .jasx} files: the task's real input, and skipped when empty. */
    @InputFiles
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSource();

    /** {@code .java} files scanned for {@code @jasx.Component} methods (they are not rewritten). */
    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getJavaSources();

    /** The compile classpath: provides the target and the component indexes of dependencies. */
    @Classpath
    public abstract ConfigurableFileCollection getClasspath();

    @Input
    public abstract Property<String> getTarget();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @TaskAction
    public void compile() throws IOException {
        // Gather the sources. .jasx files get paths relative to their source root (app/Page.jasx), which
        // become the generated files' paths (app/Page.java). Absolute paths are kept for diagnostics.
        Map<String, Path> absolutePaths = new HashMap<>();
        List<SourceFile> sources = new ArrayList<>();
        for (File root : getSourceRoots().getFiles()) {
            sources.addAll(readJasxFiles(root.toPath(), absolutePaths));
        }
        for (File javaFile : getJavaSources().getFiles()) {
            String path = javaFile.getAbsolutePath();
            sources.add(new SourceFile(path, Files.readString(javaFile.toPath(), StandardCharsets.UTF_8)));
            absolutePaths.put(path, javaFile.toPath());
        }

        CompilationResult result;
        try (URLClassLoader classpath = resourcesOnly(getClasspath().getFiles())) {
            Target target = loadTarget(classpath);
            ComponentIndex dependencies = ComponentIndex.fromClasspath(classpath);
            result = new JasxCompiler(target, dependencies).compile(sources);
        }

        int errors = report(result.diagnostics(), absolutePaths);
        if (errors > 0) {
            throw new GradleException("JASX compilation failed with " + errors + " error(s); see the messages above.");
        }

        writeGeneratedFiles(result.files());
    }

    /** Reads every {@code .jasx} file under {@code root}, keyed by its path relative to {@code root}. */
    private static List<SourceFile> readJasxFiles(Path root, Map<String, Path> absolutePaths) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<SourceFile> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".jasx")).sorted().toList()) {
                String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
                files.add(new SourceFile(relative, Files.readString(file, StandardCharsets.UTF_8)));
                absolutePaths.put(relative, file);
            }
        }
        return files;
    }

    /** A class loader over the classpath with no parent, used only to find resources. */
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

    private Target loadTarget(ClassLoader classpath) {
        String id = getTarget().get();
        try {
            return Target.load(id, classpath);
        } catch (IllegalArgumentException e) {
            throw new GradleException(e.getMessage() + ". Add the target to your dependencies, for example"
                    + " implementation(\"dev.jasx:jasx-target-" + id + ":VERSION\").", e);
        }
    }

    /** Logs every diagnostic with the source's absolute path; returns the number of errors. */
    private int report(List<CompilerDiagnostic> diagnostics, Map<String, Path> absolutePaths) {
        int errors = 0;
        for (CompilerDiagnostic d : diagnostics) {
            Path file = absolutePaths.get(d.path());
            String path = file != null ? file.toString() : d.path();
            String message = new CompilerDiagnostic(path, d.diagnostic(), d.lines()).format();
            if (d.isError()) {
                errors++;
                getLogger().error(message);
            } else {
                getLogger().warn(message);
            }
        }
        return errors;
    }

    /** Replaces the output directory's contents with the generated files. */
    private void writeGeneratedFiles(List<GeneratedFile> files) throws IOException {
        Path out = getOutputDirectory().get().getAsFile().toPath();
        deleteContents(out);
        for (GeneratedFile file : files) {
            Path target = out.resolve(file.javaPath());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content(), StandardCharsets.UTF_8);
        }
    }

    /** Deletes everything inside {@code directory}, so that no stale generated file is left behind. */
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
