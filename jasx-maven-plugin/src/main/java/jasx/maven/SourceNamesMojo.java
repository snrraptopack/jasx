package jasx.maven;

import jasx.compiler.JasxSourceNames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

/**
 * The goals {@code jasx:source-names} (phase {@code compile}) and {@code jasx:test-source-names}
 * (phase {@code test-compile}): after Maven's compiler has run, make the classes compiled from
 * generated files name their {@code .jasx} source ({@link JasxSourceNames}), so that stack traces say
 * {@code Pages.jasx:12} instead of {@code Pages.java:12}.
 *
 * <p>Fields are set by Maven from the parameters in plugin.xml.
 */
public final class SourceNamesMojo extends AbstractMojo {
    private MavenProject project;
    /** {@code main} or {@code test}; set by plugin.xml for each goal. */
    private String scope;

    @Override
    public void execute() throws MojoExecutionException {
        SourceScope sources = new SourceScope(project, scope);
        Path generated = sources.generatedSources();
        if (!Files.isDirectory(generated)) {
            return; // no .jasx files
        }
        try (Stream<Path> walk = Files.walk(generated)) {
            Set<String> generatedFiles = walk.filter(p -> p.toString().endsWith(".java"))
                    .map(p -> generated.relativize(p).toString().replace('\\', '/'))
                    .collect(Collectors.toSet());
            int changed = JasxSourceNames.rewrite(sources.classesDirectory(), generatedFiles);
            getLog().debug("JASX: " + changed + " class file(s) now name their .jasx source");
        } catch (IOException e) {
            throw new MojoExecutionException("JASX: " + e.getMessage(), e);
        }
    }
}
