package jasx.gradle;

import jasx.compiler.CompilationResult;
import jasx.compiler.CompilerDiagnostic;
import jasx.compiler.GeneratedFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
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
 * ({@code META-INF/jasx/components.json}) are read from the classpath. Only resources are read from it;
 * no class of the project or its dependencies is loaded.
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

    /** Provides the target and the component indexes of dependencies. */
    @Classpath
    public abstract ConfigurableFileCollection getClasspath();

    @Input
    public abstract Property<String> getTarget();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @TaskAction
    public void compile() throws IOException {
        JasxSources sources = JasxSources.read(getSourceRoots().getFiles(), getJavaSources().getFiles());
        CompilationResult result = sources.compile(getClasspath().getFiles(), getTarget().get());

        int errors = 0;
        for (CompilerDiagnostic d : result.diagnostics()) {
            if (d.isError()) {
                errors++;
                getLogger().error(sources.format(d));
            } else {
                getLogger().warn(sources.format(d));
            }
        }
        if (errors > 0) {
            throw new GradleException("JASX compilation failed with " + errors + " error(s); see the messages above.");
        }

        writeGeneratedFiles(result.files());
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
