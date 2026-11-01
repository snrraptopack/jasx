package jasx.gradle;

import jasx.compiler.EditorInfo;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Writes what an editor needs to know about a source set's build ({@link EditorInfo}: the target, the
 * classpaths, the annotation processor path, the Java release and where compileJasx writes) to
 * {@code build/jasx/editor/<sourceSet>.json}, for the JASX language server.
 *
 * <p>The language server finds the file from a source directory ({@code src/main/java} gives
 * {@code build/jasx/editor/main.json}), so it knows the components of the project's jars and can type-check
 * {@code .jasx} files with javac as the build does. {@code compileJasx} depends on this task, so every build
 * keeps the file up to date.
 */
@DisableCachingByDefault(because = "Writes a small file of paths")
public abstract class JasxEditorInfo extends DefaultTask {

    @Input
    public abstract Property<String> getTarget();

    /** Only the paths matter, not the contents, so the classpaths are {@code @Internal}; see {@link #getPaths}. */
    @Internal
    public abstract ConfigurableFileCollection getJasxClasspath();

    @Internal
    public abstract ConfigurableFileCollection getCompileClasspath();

    @Internal
    public abstract ConfigurableFileCollection getProcessorPath();

    @Input
    @Optional
    public abstract Property<Integer> getRelease();

    /** compileJasx's output directory, as an absolute path (a path, so that this task doesn't depend on it). */
    @Input
    public abstract Property<String> getGeneratedSources();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /** The paths written, as the task's input: the file is rewritten only when one of them changes. */
    @Input
    public List<String> getPaths() {
        List<String> paths = new ArrayList<>();
        paths.addAll(pathsOf(getJasxClasspath()));
        paths.add("|");
        paths.addAll(pathsOf(getCompileClasspath()));
        paths.add("|");
        paths.addAll(pathsOf(getProcessorPath()));
        return paths;
    }

    @TaskAction
    public void write() throws IOException {
        EditorInfo info = new EditorInfo(getTarget().get(), pathsOf(getJasxClasspath()),
                pathsOf(getCompileClasspath()), pathsOf(getProcessorPath()), getRelease().getOrNull(),
                getGeneratedSources().get());
        Path file = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(file.getParent());
        Files.writeString(file, info.toJson(), StandardCharsets.UTF_8);
    }

    private static List<String> pathsOf(ConfigurableFileCollection files) {
        List<String> paths = new ArrayList<>();
        for (File file : files.getFiles()) {
            paths.add(file.getAbsolutePath());
        }
        return paths;
    }
}
