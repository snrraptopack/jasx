package jasx.gradle;

import jasx.compiler.CompilationResult;
import jasx.compiler.CompilerDiagnostic;
import jasx.compiler.JavacRunner;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Reports javac's errors in generated code at their {@code .jasx} locations (diagnostics.md §6).
 *
 * <p>{@code compileJava} compiles the generated Java, so when it fails, javac points at the generated file
 * ({@code build/generated/sources/jasx/main/app/Page.java:12}). Gradle runs javac itself, so those messages
 * can't be changed. Instead, this task runs after a failed {@code compileJava} (as its finalizer). It
 * compiles the same sources again in memory, translates each error that falls in generated code back to the
 * {@code .jasx} file through the span maps, and prints it as
 * {@code /path/Page.jasx:7:20: error: javac: cannot find symbol}.
 *
 * <p>It does nothing when {@code compileJava} succeeds, so successful builds pay nothing.
 *
 * <p>Limitation: annotation processors don't run in this second compilation, so if the {@code .jasx} code
 * uses classes that a processor generates, it may print errors that {@code compileJava} didn't have.
 */
@DisableCachingByDefault(because = "Only prints diagnostics")
public abstract class JasxJavacReport extends DefaultTask {

    @Internal
    public abstract ConfigurableFileCollection getSourceRoots();

    @Internal
    public abstract ConfigurableFileCollection getJavaSources();

    /** The classpath javac compiles against: the source set's compile classpath. */
    @Internal
    public abstract ConfigurableFileCollection getCompileClasspath();

    /** The classpath the target is loaded from, as for {@link JasxCompile#getClasspath()}. */
    @Internal
    public abstract ConfigurableFileCollection getJasxClasspath();

    @Internal
    public abstract Property<String> getTarget();

    @TaskAction
    public void report() throws IOException {
        JasxSources sources = JasxSources.read(getSourceRoots().getFiles(), getJavaSources().getFiles());
        CompilationResult jasx = sources.compile(getJasxClasspath().getFiles(), getTarget().get());
        if (jasx.hasErrors()) {
            return; // compileJasx already reported these, and compileJava didn't run
        }

        JavacRunner.Result javac = JavacRunner.compile(jasx.files(), sources.javaFiles(), classpathString());

        // Only errors in .jasx files: errors in plain .java files are already reported correctly by javac.
        List<String> messages = new ArrayList<>();
        for (CompilerDiagnostic d : javac.diagnostics()) {
            if (d.isError() && d.path().endsWith(".jasx")) {
                messages.add(sources.format(d));
            }
        }
        if (messages.isEmpty()) {
            return;
        }

        getLogger().error("JASX: javac's errors in generated code, at their .jasx locations:");
        for (String message : messages) {
            getLogger().error(message);
        }
    }

    private String classpathString() {
        List<String> entries = new ArrayList<>();
        for (File entry : getCompileClasspath().getFiles()) {
            entries.add(entry.getAbsolutePath());
        }
        return String.join(File.pathSeparator, entries);
    }
}
