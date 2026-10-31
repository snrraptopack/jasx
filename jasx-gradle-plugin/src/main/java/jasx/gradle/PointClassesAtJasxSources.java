package jasx.gradle;

import jasx.compiler.JasxSourceNames;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.compile.JavaCompile;

/**
 * Runs at the end of {@code compileJava}: makes the classes compiled from generated files name their
 * {@code .jasx} source ({@link JasxSourceNames}), so that stack traces say {@code Pages.jasx:12}.
 *
 * <p>It only needs the directory of generated files (to know which {@code .java} files are generated) and
 * the task's output directory, both read when it runs, so it works with Gradle's configuration cache.
 */
final class PointClassesAtJasxSources implements Action<Task> {
    private final Provider<Directory> generatedSources;

    /** @param generatedSources the output directory of the source set's {@code compileJasx} task */
    PointClassesAtJasxSources(Provider<Directory> generatedSources) {
        this.generatedSources = generatedSources;
    }

    @Override
    public void execute(Task task) {
        Path classes = ((JavaCompile) task).getDestinationDirectory().get().getAsFile().toPath();
        JasxSourceNames.rewrite(classes, generatedFiles(generatedSources.get().getAsFile().toPath()));
    }

    /** The generated files, as paths relative to their directory ({@code app/Pages.java}). */
    private static Set<String> generatedFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return Set.of();
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .map(p -> directory.relativize(p).toString().replace('\\', '/'))
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
