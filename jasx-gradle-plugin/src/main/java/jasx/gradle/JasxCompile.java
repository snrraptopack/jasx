package jasx.gradle;

import jasx.compiler.BuildSources;
import jasx.compiler.CompilationResult;
import jasx.compiler.CompilerDiagnostic;
import jasx.compiler.GeneratedFile;
import jasx.compiler.JavacRunner;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;

/**
 * Compiles {@code .jasx} files to Java with the JASX compiler, and type-checks the result.
 *
 * <p>The target (its descriptor and schema) and the components of dependencies
 * ({@code META-INF/jasx/components.json}) are read from the classpath. Only resources are read from it;
 * no class of the project or its dependencies is loaded.
 *
 * <p>Errors are printed as {@code /absolute/path/File.jasx:line:column: error: message [JASX....]}, and fail
 * the task.
 *
 * <h2>The type check</h2>
 *
 * <p>Gradle's {@code compileJava} compiles the generated files, and it runs javac itself, so its messages
 * can't be changed: an error in code that came from markup would point at
 * {@code build/generated/sources/jasx/main/app/Page.java}. So, unless {@code jasx.typeCheck} is off, this
 * task first checks the generated files with javac itself ({@link JavacRunner#typeCheck}), together with the
 * source set's {@code .java} files, against the same classpath, with the same annotation processors, Java
 * release and compiler arguments ({@code compileJava}'s {@code options.compilerArgs}, less those the check sets
 * itself and those about warnings: {@link JavacRunner.BuildOptions#compilerArgs}):
 *
 * <ul>
 *   <li>If javac finds an error in a {@code .jasx} file, the task prints every error javac found, those in
 *       {@code .jasx} files at their {@code .jasx} location ({@code Page.jasx:7:20: error: javac: ...}),
 *       and fails. {@code compileJava} doesn't run, so each error is printed once.
 *   <li>Otherwise the task prints nothing more. Errors only in {@code .java} files are left to
 *       {@code compileJava}, which reports them as usual.
 * </ul>
 *
 * <p>If the check itself can't run (javac rejects the release, or an annotation processor crashes), the
 * task says so and carries on; {@code compileJava} then reports any error.
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

    /** Whether to type-check the generated Java (see the class comment). */
    @Input
    public abstract Property<Boolean> getTypeCheck();

    /** The classpath the type check compiles against: the source set's compile classpath. */
    @Classpath
    public abstract ConfigurableFileCollection getCompileClasspath();

    /** The annotation processors the type check runs: the source set's annotation processor path. */
    @Classpath
    public abstract ConfigurableFileCollection getProcessorPath();

    /** The Java release the type check compiles for, as {@code compileJava} does; absent for the running JDK's. */
    @Input
    @Optional
    public abstract Property<Integer> getRelease();

    /** {@code compileJava}'s {@code options.compilerArgs}, for the type check. */
    @Input
    public abstract ListProperty<String> getCompilerArgs();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @TaskAction
    public void compile() throws IOException {
        BuildSources sources = BuildSources.read(paths(getSourceRoots()));
        CompilationResult result;
        try {
            result = sources.compile(paths(getClasspath()), getTarget().get());
        } catch (IllegalArgumentException e) {
            throw new GradleException(e.getMessage() + ". Add the target to your dependencies, for example"
                    + " implementation(\"io.github.snrraptopack:jasx-target-" + getTarget().get() + ":VERSION\").", e);
        }

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

        if (getTypeCheck().get()) {
            typeCheck(sources, result.files());
        }
        BuildSources.writeGeneratedFiles(result.files(), getOutputDirectory().get().getAsFile().toPath());
    }

    /** Runs javac over the generated and plain sources, and fails if it finds an error in a .jasx file. */
    private void typeCheck(BuildSources sources, List<GeneratedFile> generated) throws IOException {
        Integer release = getRelease().getOrNull();
        int runningJdk = Runtime.version().feature();
        if (release != null && release > runningJdk) {
            // javac can only compile for releases up to its own. Gradle runs on this JDK; compileJava uses
            // the toolchain's.
            getLogger().lifecycle("JASX: Gradle runs on Java " + runningJdk + ", which can't compile for Java "
                    + release + ", so javac's errors in .jasx files will be reported by compileJava, in the"
                    + " generated files.");
            return;
        }

        JavacRunner.BuildOptions options = new JavacRunner.BuildOptions(
                pathString(getCompileClasspath()), pathString(getProcessorPath()), release,
                getTemporaryDir().toPath(), getCompilerArgs().get());
        List<CompilerDiagnostic> javacErrors;
        try {
            javacErrors = sources.javacErrorsToReport(generated, options);
        } catch (RuntimeException e) {
            // The check is a convenience: if it can't run, compileJava still reports every error.
            getLogger().lifecycle("JASX: could not type-check the generated Java (" + e
                    + "); javac's errors in .jasx files will be reported by compileJava, in the generated files.");
            return;
        }
        if (javacErrors.isEmpty()) {
            return; // no errors, or only in .java files: compileJava reports those itself
        }
        for (CompilerDiagnostic d : javacErrors) {
            getLogger().error(sources.format(d));
        }
        throw new GradleException("Java compilation of the .jasx files failed with " + javacErrors.size()
                + " error(s); see the messages above.");
    }

    private static List<Path> paths(FileCollection files) {
        List<Path> paths = new ArrayList<>();
        for (File file : files.getFiles()) {
            paths.add(file.toPath());
        }
        return paths;
    }

    private static String pathString(FileCollection files) {
        List<String> entries = new ArrayList<>();
        for (File entry : files.getFiles()) {
            entries.add(entry.getAbsolutePath());
        }
        return String.join(File.pathSeparator, entries);
    }
}
