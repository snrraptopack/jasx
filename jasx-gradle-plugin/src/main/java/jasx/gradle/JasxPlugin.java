package jasx.gradle;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

/**
 * The JASX Gradle plugin, {@code id("dev.jasx")}.
 *
 * <p>For each source set ({@code main}, {@code test}, ...) it adds a task {@code compileJasx} (for
 * {@code main}; {@code compileTestJasx} for {@code test}, ...). It compiles the {@code .jasx} files found in
 * the source set's Java directories ({@code src/main/java}) and in {@code src/main/jasx}, type-checks the
 * result with javac (see {@link JasxCompile}), and writes the generated Java to
 * {@code build/generated/sources/jasx/main}. It also reads the {@code .java} files there, so that
 * {@code @jasx.Component} methods in plain Java can be used from markup.
 *
 * <p>Before it, {@code writeJasxEditorInfo} ({@code writeTestJasxEditorInfo}, ...) writes the source set's
 * classpaths to {@code build/jasx/editor/main.json} for the JASX language server ({@link JasxEditorInfo}).
 *
 * <p>The output directory is added to the source set's Java sources, so {@code compileJava} depends on
 * {@code compileJasx} and compiles the generated files along with the hand-written ones. Afterwards, the
 * classes compiled from generated files are made to name their {@code .jasx} source
 * ({@link PointClassesAtJasxSources}), so stack traces say {@code Pages.jasx:12}.
 */
public final class JasxPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);

        JasxExtension extension = project.getExtensions().create("jasx", JasxExtension.class);
        extension.getTarget().convention("html");
        extension.getTypeCheck().convention(true);

        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        sourceSets.all(sourceSet -> configure(project, extension, sourceSet));
    }

    private static void configure(Project project, JasxExtension extension, SourceSet sourceSet) {
        // Where .jasx files may live: next to .java files, or in their own directory. This is read before
        // the output directory is added to the Java sources below, so the output is never an input.
        Set<File> sourceRootDirs = new LinkedHashSet<>(sourceSet.getJava().getSrcDirs());
        sourceRootDirs.add(project.file("src/" + sourceSet.getName() + "/jasx"));
        FileCollection sourceRoots = project.files(sourceRootDirs);
        FileCollection jasxFiles = sourceRoots.getAsFileTree().matching(files -> files.include("**/*.jasx"));
        FileCollection javaFiles = sourceRoots.getAsFileTree().matching(files -> files.include("**/*.java"));

        // The target's descriptor and schema are resources. A project dependency (in a multi-project or
        // composite build) puts only its classes on the compile classpath, and its resources only on the
        // runtime classpath, so both are needed. The runtime classpath *configuration* is used, not
        // sourceSet.getRuntimeClasspath(), which would include this source set's own output and so
        // depend on compileJava, which depends on compileJasx.
        FileCollection jasxClasspath = sourceSet.getCompileClasspath()
                .plus(project.getConfigurations().getByName(sourceSet.getRuntimeClasspathConfigurationName()));

        Provider<Directory> outputDirectory = project.getLayout().getBuildDirectory()
                .dir("generated/sources/jasx/" + sourceSet.getName());
        Provider<Integer> release = javaRelease(project, sourceSet);

        // What the language server needs to know about this build (JasxEditorInfo). Every compileJasx
        // runs it first, so the file stays up to date.
        TaskProvider<JasxEditorInfo> editorInfo = project.getTasks().register(
                sourceSet.getTaskName("write", "JasxEditorInfo"), JasxEditorInfo.class, task -> {
                    task.setDescription("Writes the " + sourceSet.getName() + " classpaths for the JASX language server.");
                    task.getTarget().set(extension.getTarget());
                    task.getJasxClasspath().from(jasxClasspath);
                    task.getCompileClasspath().from(sourceSet.getCompileClasspath());
                    task.getProcessorPath().from(sourceSet.getAnnotationProcessorPath());
                    task.getRelease().set(release);
                    task.getOutputFile().set(project.getLayout().getBuildDirectory()
                            .file("jasx/editor/" + sourceSet.getName() + ".json"));
                });

        TaskProvider<JasxCompile> compileJasx = project.getTasks().register(
                sourceSet.getTaskName("compile", "Jasx"), JasxCompile.class, task -> {
                    task.setGroup("build");
                    task.setDescription("Compiles the " + sourceSet.getName() + " .jasx files to Java.");
                    task.getSourceRoots().from(sourceRoots);
                    task.getSource().from(jasxFiles);
                    task.getJavaSources().from(javaFiles);
                    task.getClasspath().from(jasxClasspath);
                    task.getTarget().set(extension.getTarget());
                    task.getTypeCheck().set(extension.getTypeCheck());
                    task.getCompileClasspath().from(sourceSet.getCompileClasspath());
                    task.getProcessorPath().from(sourceSet.getAnnotationProcessorPath());
                    task.getRelease().set(release);
                    task.getOutputDirectory().set(outputDirectory);
                    task.dependsOn(editorInfo);
                });

        // Adding the task's output as a source directory also makes compileJava depend on the task.
        Provider<Directory> generatedSources = compileJasx.flatMap(JasxCompile::getOutputDirectory);
        sourceSet.getJava().srcDir(generatedSources);

        // Classes compiled from generated files name their .jasx source, for stack traces and debuggers.
        project.getTasks().named(sourceSet.getCompileJavaTaskName(), JavaCompile.class).configure(task ->
                task.doLast("pointClassesAtJasxSources", new PointClassesAtJasxSources(generatedSources)));
    }

    /**
     * The Java release {@code compileJava} compiles for, so that the type check compiles for the same one:
     * its {@code options.release} if set, otherwise the version of the project's Java toolchain if one is
     * configured, otherwise none (the JDK Gradle runs on).
     */
    private static Provider<Integer> javaRelease(Project project, SourceSet sourceSet) {
        TaskProvider<JavaCompile> compileJava =
                project.getTasks().named(sourceSet.getCompileJavaTaskName(), JavaCompile.class);
        Provider<Integer> toolchainVersion = project.getExtensions().getByType(JavaPluginExtension.class)
                .getToolchain().getLanguageVersion().map(JavaLanguageVersion::asInt);
        return compileJava.flatMap(task -> task.getOptions().getRelease()).orElse(toolchainVersion);
    }
}
