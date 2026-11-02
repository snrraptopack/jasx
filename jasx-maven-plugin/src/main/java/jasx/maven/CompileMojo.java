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
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.util.artifact.JavaScopes;

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
 *       fails before Maven's compiler would report them in the generated files. The annotation processors
 *       are those the maven-compiler-plugin runs: its {@code annotationProcessorPaths}, resolved with their
 *       dependencies, or else those on the classpath, as by default in Maven; and its compiler options apply
 *       ({@link CompilerConfiguration}).
 *   <li>It writes {@code target/jasx/editor/<scope>.json} for the JASX language server.
 * </ul>
 *
 * <p>Fields are set by Maven from the parameters in plugin.xml.
 */
public final class CompileMojo extends AbstractMojo {
    private MavenProject project;
    private MavenSession session;
    /** Resolves {@code annotationProcessorPaths}; injected by Maven (a requirement in plugin.xml). */
    private RepositorySystem repositorySystem;
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
        CompilerConfiguration compiler = CompilerConfiguration.of(project, scope.equals("test"));
        List<String> processorPath = processorPath(compiler, classpath);
        try {
            writeEditorInfo(sources, classpath, processorPath != null ? processorPath : classpath, javaRelease);

            BuildSources files = BuildSources.read(sources.sourceRoots());
            if (!files.hasJasxFiles()) {
                getLog().debug("JASX: no .jasx files in " + sources.sourceRoots());
                return;
            }
            CompilationResult result = compile(files, classpath);
            if (typeCheck) {
                typeCheck(files, result, classpath, processorPath, compiler.compilerArgs, javaRelease,
                        sources.workDirectory());
            }
            BuildSources.writeGeneratedFiles(result.files(), sources.generatedSources());
            sources.addGeneratedSourcesToCompilation();
            files.writeScriptModules(result.modules(), sources.generatedResources());
            sources.addGeneratedResources();
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

    /**
     * The annotation processor path Maven's compiler will use: the compiler plugin's
     * {@code annotationProcessorPaths} with their dependencies, or the classpath if it has none (Maven's
     * default). Null if they can't be resolved (offline, say): the type check is then skipped, and Maven's
     * compiler reports the problem itself.
     */
    private List<String> processorPath(CompilerConfiguration compiler, List<String> classpath) {
        if (compiler.processorPaths.isEmpty()) {
            return classpath;
        }
        try {
            CollectRequest collect = new CollectRequest();
            for (CompilerConfiguration.ProcessorArtifact artifact : compiler.processorPaths) {
                String version = artifact.version() != null ? artifact.version() : managedVersion(artifact);
                String classifier = artifact.classifier() != null ? artifact.classifier() : "";
                collect.addDependency(new Dependency(new DefaultArtifact(artifact.groupId(), artifact.artifactId(),
                        classifier, artifact.type(), version), JavaScopes.RUNTIME));
            }
            collect.setRepositories(project.getRemoteProjectRepositories());
            DependencyResult resolved = repositorySystem.resolveDependencies(session.getRepositorySession(),
                    new DependencyRequest(collect, null));
            List<String> files = new ArrayList<>();
            for (ArtifactResult artifact : resolved.getArtifactResults()) {
                files.add(artifact.getArtifact().getFile().getAbsolutePath());
            }
            return files;
        } catch (DependencyResolutionException | RuntimeException e) {
            getLog().info("JASX: could not resolve the compiler's annotationProcessorPaths (" + e.getMessage()
                    + "), so the generated Java isn't type-checked before Maven's compiler runs.");
            return null;
        }
    }

    /**
     * An annotation processor's version from the project's dependency management, for an entry of
     * {@code annotationProcessorPaths} without one (as maven-compiler-plugin 3.12 and later allow).
     */
    private String managedVersion(CompilerConfiguration.ProcessorArtifact artifact) {
        DependencyManagement management = project.getDependencyManagement();
        if (management != null) {
            for (org.apache.maven.model.Dependency managed : management.getDependencies()) {
                boolean same = managed.getGroupId().equals(artifact.groupId())
                        && managed.getArtifactId().equals(artifact.artifactId());
                if (same) {
                    return managed.getVersion();
                }
            }
        }
        throw new IllegalArgumentException(artifact.groupId() + ":" + artifact.artifactId() + " has no version");
    }

    /** Type-checks with javac, and fails if it finds an error in a .jasx file. */
    private void typeCheck(BuildSources files, CompilationResult result, List<String> classpath,
            List<String> processorPath, List<String> compilerArgs, Integer javaRelease, Path workDirectory)
            throws IOException, MojoFailureException {
        if (processorPath == null) {
            return; // see processorPath
        }
        int runningJdk = Runtime.version().feature();
        if (javaRelease != null && javaRelease > runningJdk) {
            getLog().info("JASX: Maven runs on Java " + runningJdk + ", which can't compile for Java " + javaRelease
                    + ", so javac's errors in .jasx files will be reported by the compiler, in the generated files.");
            return;
        }

        JavacRunner.BuildOptions options = new JavacRunner.BuildOptions(String.join(File.pathSeparator, classpath),
                String.join(File.pathSeparator, processorPath), javaRelease, workDirectory, compilerArgs);
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
    private void writeEditorInfo(SourceScope sources, List<String> classpath, List<String> processorPath,
            Integer javaRelease) throws IOException {
        EditorInfo info = new EditorInfo(target, classpath, classpath, processorPath, javaRelease,
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
