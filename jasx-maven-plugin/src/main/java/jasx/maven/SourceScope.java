package jasx.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

/**
 * Where the main or the test sources of a Maven project are, and where JASX writes for them. The goals for
 * main code ({@code compile}, {@code source-names}) and for test code ({@code test-compile},
 * {@code test-source-names}) share their implementation; plugin.xml gives each goal its scope.
 *
 * <pre>
 *                    main                                   test
 *   .jasx files      src/main/java, src/main/jasx           src/test/java, src/test/jasx
 *   generated Java   target/generated-sources/jasx          target/generated-test-sources/jasx
 *   classes          target/classes                         target/test-classes
 *   editor info      target/jasx/editor/main.json           target/jasx/editor/test.json
 * </pre>
 */
final class SourceScope {
    private final MavenProject project;
    private final boolean test;

    /** @param scope {@code "main"} or {@code "test"}, as plugin.xml sets it */
    SourceScope(MavenProject project, String scope) {
        this.project = project;
        this.test = scope.equals("test");
    }

    /** {@code main} or {@code test}: the source set's name, as in {@code src/main}. */
    String name() {
        return test ? "test" : "main";
    }

    /**
     * The directories to read {@code .jasx} and {@code .java} files from: the project's source roots (without
     * JASX's own output, which this plugin adds to them) and {@code src/<scope>/jasx}.
     */
    List<Path> sourceRoots() {
        List<Path> roots = new ArrayList<>();
        Path generated = generatedSources();
        for (String root : test ? project.getTestCompileSourceRoots() : project.getCompileSourceRoots()) {
            Path path = Path.of(root).toAbsolutePath().normalize();
            if (!path.equals(generated)) {
                roots.add(path);
            }
        }
        Path jasxDirectory = project.getBasedir().toPath().resolve("src").resolve(name()).resolve("jasx");
        if (!roots.contains(jasxDirectory)) {
            roots.add(jasxDirectory);
        }
        return roots;
    }

    /** Where the generated Java goes. */
    Path generatedSources() {
        String directory = test ? "generated-test-sources" : "generated-sources";
        return buildDirectory().resolve(directory).resolve("jasx");
    }

    /** Where the browser modules are written (targets/web.md §7.1): a resources directory of the scope. */
    Path generatedResources() {
        String directory = test ? "generated-test-resources" : "generated-resources";
        return buildDirectory().resolve(directory).resolve("jasx");
    }

    /** Makes Maven copy the browser modules into the classes, so they are in the jar. */
    void addGeneratedResources() {
        org.apache.maven.model.Resource resource = new org.apache.maven.model.Resource();
        resource.setDirectory(generatedResources().toString());
        if (test) {
            project.addTestResource(resource);
        } else {
            project.addResource(resource);
        }
    }

    /** Makes Maven compile the generated Java along with the rest. */
    void addGeneratedSourcesToCompilation() {
        String root = generatedSources().toString();
        if (test) {
            project.addTestCompileSourceRoot(root);
        } else {
            project.addCompileSourceRoot(root);
        }
    }

    /** Where Maven's compiler writes the classes. */
    Path classesDirectory() {
        return Path.of(test ? project.getBuild().getTestOutputDirectory() : project.getBuild().getOutputDirectory());
    }

    /** The classpath javac compiles against, which also holds the target and the dependencies' components. */
    List<String> classpath() throws MojoExecutionException {
        try {
            return test ? project.getTestClasspathElements() : project.getCompileClasspathElements();
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("the project's dependencies are not resolved", e);
        }
    }

    /** A scratch directory for this scope under {@code target/jasx}. */
    Path workDirectory() {
        return buildDirectory().resolve("jasx").resolve("work-" + name());
    }

    Path buildDirectory() {
        return Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
    }
}
