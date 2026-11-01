package jasx.maven;

import jasx.compiler.BuildSources;
import jasx.compiler.CompilationResult;
import jasx.compiler.CompilerDiagnostic;
import jasx.compiler.EditorInfo;
import jasx.compiler.JavacRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

/**
 * The goals {@code jasx:compile} (phase {@code generate-sources}) and {@code jasx:test-compile} (phase
 * {@code generate-test-sources}): compile the {@code .jasx} files to Java, type-check the result with javac,
 * and add the generated Java to the project's sources, so that Maven's compiler compiles it.
 *
 * <p>It does in a Maven build what {@code compileJasx} does in a Gradle build:
 *
 * <ul>
 *   <li>JASX errors are printed with their {@code .jasx} location, and fail the build.
 *   <li>Unless {@code typeCheck} is off, javac checks the generated files with the project's classpath and
 *       Java release ({@code maven.compiler.release}). If it finds an error in a {@code .jasx} file, every
 *       javac error is printed, at {@code .jasx} locations where they come from markup, and the build
 *       fails before Maven's compiler would report them in the generated files. Annotation processors on the
 *       classpath run, as they do by default in Maven.
 *   <li>It writes {@code target/jasx/editor/<scope>.json} for the JASX language server.
 * </ul>
 *
 * <p>Fields are set by Maven from the parameters in plugin.xml.
 */
public final class CompileMojo extends AbstractMojo {
    private MavenProject project;
    /** {@code main} or {@code test}; set by plugin.xml for each goal. */
    private String scope;
    /** The id of the target ({@code jasx.target}, default {@code html}). */
    private String target;
    /** Whether to type-check the generated Java ({@code jasx.typeCheck}, default true). */
    private boolean typeCheck;
    /** {@code maven.compiler.release}, if the project sets it. */
    private String release;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        SourceScope sources = new SourceScope(project, scope);
        List<String> classpath = sources.classpath();
        Integer javaRelease = release == null || release.isBlank() ? null : Integer.valueOf(release.strip());
        try {
            writeEditorInfo(sources, classpath, javaRelease);

            BuildSources files = BuildSources.read(sources.sourceRoots());
            if (!files.hasJasxFiles()) {
                getLog().debug("JASX: no .jasx files in " + sources.sourceRoots());
                return;
            }
            CompilationResult result = compile(files, classpath);
            if (typeCheck) {
                typeCheck(files, result, classpath, javaRelease, sources.workDirectory());
            }
            BuildSources.writeGeneratedFiles(result.files(), sources.generatedSources());
            sources.addGeneratedSourcesToCompilation();
        } catch (IOException e) {
            throw new MojoExecutionException("JASX: " + e.getMessage(), e);
        }
    }

    /** Runs the JASX compiler; prints its diagnostics, and fails on errors. */
    private CompilationResult compile(BuildSources files, List<String> classpath)
            throws IOException, MojoExecutionException, MojoFailureException {
        CompilationResult result;
        try {
            result = files.compile(paths(classpath), target);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage() + ". Add the target to your dependencies, for example"
                    + " io.github.snrraptopack:jasx-target-" + target + ".", e);
        }

        int errors = 0;
        for (CompilerDiagnostic d : result.diagnostics()) {
            if (d.isError()) {
                errors++;
                getLog().error(files.format(d));
            } else {
                getLog().warn(files.format(d));
            }
        }
        if (errors > 0) {
            throw new MojoFailureException("JASX compilation failed with " + errors + " error(s); see the messages above.");
        }
        return result;
    }

    /** Type-checks with javac, and fails if it finds an error in a .jasx file. */
    private void typeCheck(BuildSources files, CompilationResult result, List<String> classpath, Integer javaRelease,
            Path workDirectory) throws IOException, MojoFailureException {
        int runningJdk = Runtime.version().feature();
        if (javaRelease != null && javaRelease > runningJdk) {
            getLog().info("JASX: Maven runs on Java " + runningJdk + ", which can't compile for Java " + javaRelease
                    + ", so javac's errors in .jasx files will be reported by the compiler, in the generated files.");
            return;
        }

        // Maven runs the annotation processors found on the classpath unless told otherwise, so the check
        // uses the classpath as the processor path.
        String path = String.join(File.pathSeparator, classpath);
        JavacRunner.BuildOptions options = new JavacRunner.BuildOptions(path, path, javaRelease, workDirectory);
        List<CompilerDiagnostic> errors;
        try {
            errors = files.javacErrorsToReport(result.files(), options);
        } catch (RuntimeException e) {
            // The check is a convenience: if it can't run, Maven's compiler still reports every error.
            getLog().info("JASX: could not type-check the generated Java (" + e + ")");
            return;
        }
        if (errors.isEmpty()) {
            return;
        }
        for (CompilerDiagnostic d : errors) {
            getLog().error(files.format(d));
        }
        throw new MojoFailureException("Java compilation of the .jasx files failed with " + errors.size()
                + " error(s); see the messages above.");
    }

    /** Writes {@code target/jasx/editor/<scope>.json} for the language server ({@link EditorInfo}). */
    private void writeEditorInfo(SourceScope sources, List<String> classpath, Integer javaRelease) throws IOException {
        EditorInfo info = new EditorInfo(target, classpath, classpath, classpath, javaRelease,
                sources.generatedSources().toString());
        Path file = sources.buildDirectory().resolve(EditorInfo.relativePath(sources.name()));
        Files.createDirectories(file.getParent());
        Files.writeString(file, info.toJson(), StandardCharsets.UTF_8);
    }

    private static List<Path> paths(List<String> classpath) {
        List<Path> paths = new ArrayList<>();
        for (String entry : classpath) {
            paths.add(Path.of(entry));
        }
        return paths;
    }
}
