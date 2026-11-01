package jasx.maven;

import java.util.ArrayList;
import java.util.List;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * What the project's maven-compiler-plugin is configured with, as far as the JASX type check needs it, so
 * that javac checks the generated Java as Maven's compiler will compile it:
 *
 * <ul>
 *   <li>{@code annotationProcessorPaths}: the processors to run instead of those found on the classpath
 *       (Lombok, MapStruct, ...). The artifacts are only named here; {@link CompileMojo} resolves them, with
 *       their dependencies, as the compiler plugin does;
 *   <li>the options that change how javac reads the code: {@code compilerArgs}, {@code compilerArgument},
 *       {@code parameters}, {@code enablePreview}, {@code proc} and {@code annotationProcessors}.
 * </ul>
 *
 * <p>The plugin's own configuration is merged with that of the execution that compiles the scope
 * ({@code default-compile} or {@code default-testCompile}), the execution's taking precedence, as Maven does.
 * The model Maven gives has its {@code ${...}} properties replaced already.
 */
final class CompilerConfiguration {
    /** An entry of {@code annotationProcessorPaths}; {@code version} is null when the POM leaves it out. */
    record ProcessorArtifact(String groupId, String artifactId, String version, String classifier, String type) {}

    private static final String COMPILER_PLUGIN = "org.apache.maven.plugins:maven-compiler-plugin";

    final List<ProcessorArtifact> processorPaths;
    final List<String> compilerArgs;

    private CompilerConfiguration(List<ProcessorArtifact> processorPaths, List<String> compilerArgs) {
        this.processorPaths = processorPaths;
        this.compilerArgs = compilerArgs;
    }

    /** The configuration of the project's compiler plugin for the main or test scope; empty if it has none. */
    static CompilerConfiguration of(MavenProject project, boolean test) {
        Plugin plugin = project.getPlugin(COMPILER_PLUGIN);
        if (plugin == null) {
            return read(null);
        }
        Xpp3Dom pluginConfiguration = (Xpp3Dom) plugin.getConfiguration();
        PluginExecution execution = plugin.getExecutionsAsMap().get(test ? "default-testCompile" : "default-compile");
        Xpp3Dom executionConfiguration = execution != null ? (Xpp3Dom) execution.getConfiguration() : null;
        if (executionConfiguration == null) {
            return read(pluginConfiguration);
        }
        if (pluginConfiguration == null) {
            return read(executionConfiguration);
        }
        // mergeXpp3Dom changes its first argument, so merge into a copy.
        return read(Xpp3Dom.mergeXpp3Dom(new Xpp3Dom(executionConfiguration), pluginConfiguration));
    }

    /** Reads a {@code <configuration>} element of the compiler plugin; null reads as empty. */
    static CompilerConfiguration read(Xpp3Dom configuration) {
        if (configuration == null) {
            return new CompilerConfiguration(List.of(), List.of());
        }
        List<ProcessorArtifact> paths = new ArrayList<>();
        for (Xpp3Dom path : children(configuration.getChild("annotationProcessorPaths"))) {
            paths.add(new ProcessorArtifact(value(path, "groupId"), value(path, "artifactId"), value(path, "version"),
                    value(path, "classifier"), value(path, "type") != null ? value(path, "type") : "jar"));
        }

        List<String> arguments = new ArrayList<>();
        for (Xpp3Dom arg : children(configuration.getChild("compilerArgs"))) {
            if (arg.getValue() != null && !arg.getValue().isBlank()) {
                arguments.add(arg.getValue().strip());
            }
        }
        String argument = value(configuration, "compilerArgument");
        if (argument != null) {
            arguments.addAll(List.of(argument.strip().split("\\s+")));
        }
        if ("true".equals(value(configuration, "parameters"))) {
            arguments.add("-parameters");
        }
        if ("true".equals(value(configuration, "enablePreview"))) {
            arguments.add("--enable-preview");
        }
        String proc = value(configuration, "proc");
        if (proc != null) {
            arguments.add("-proc:" + proc);
        }
        List<String> processors = new ArrayList<>();
        for (Xpp3Dom processor : children(configuration.getChild("annotationProcessors"))) {
            processors.add(processor.getValue().strip());
        }
        if (!processors.isEmpty()) {
            arguments.addAll(List.of("-processor", String.join(",", processors)));
        }
        return new CompilerConfiguration(List.copyOf(paths), List.copyOf(arguments));
    }

    private static List<Xpp3Dom> children(Xpp3Dom element) {
        return element == null ? List.of() : List.of(element.getChildren());
    }

    /** The trimmed text of the child {@code name}, or null if it is absent or empty. */
    private static String value(Xpp3Dom element, String name) {
        Xpp3Dom child = element.getChild(name);
        String value = child != null ? child.getValue() : null;
        return value == null || value.isBlank() ? null : value.strip();
    }
}
